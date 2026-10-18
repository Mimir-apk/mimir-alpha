package com.gruning.mimir.tweaker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.os.UserManager
import android.util.Log
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Live daemon telemetry has no display-wide frame producer; GameNative owns the HUD FPS stream. */
private object LiveGameFrameTelemetryUnavailable : FrameTelemetryPort {
    override fun read(packageName: String): FrameTelemetryReading = FrameTelemetryReading(
        status = FrameTelemetryStatus.NO_PACKAGE_LAYER,
        diagnostic = "live frame telemetry is supplied by app.gamenative",
    )
}

open class MimirTweakerService : Service() {
    private lateinit var deviceProtectedContext: Context
    private lateinit var probe: HardwareProbe
    private lateinit var pServer: PServerClient
    private lateinit var systemReader: SystemReader

    /**
     * The discovered tuner's session, held here so it outlives the app that set it (policy R2,
     * 2026-09-01). Lazy rather than built in `onCreate` because it needs [probe], and because a device
     * whose owner never touches the discovered tuner should never pay for the file at all.
     *
     * **`composeDiscoveredAxisAdmission` is per-posture and is the deterministic proof's only daemon
     * seam.** In SAFE and PRODUCTION it is the identity function, so this is exactly
     * `ProbeDiscoveredAxisAdmission()` and those APKs do not contain any other implementation. In LAB it
     * returns a union carrying one synthetic cluster in addition to the real one. Either way the value
     * handed to the store is a DISCOVERY, and [DiscoveredAxisAdmissionCheck] admits against it
     * unchanged — the seam is the input to admission, never admission.
     */
    private val discoveredAxisRecords: DiscoveredAxisRecordStore by lazy {
        DiscoveredAxisRecordStore.create(this, composeDiscoveredAxisAdmission(ProbeDiscoveredAxisAdmission()))
    }

    /**
     * Admission from THIS process's own hardware discovery, never from the caller's claim.
     *
     * `probe.probe()` rather than `capabilities()`: the qualification manifest, the certification
     * store and the dry-run flag have no business in a decision about whether a frequency exists, and
     * this family must not consult them. The static topology behind the probe is cached, so admitting
     * a record does not re-walk cpufreq.
     */
    private inner class ProbeDiscoveredAxisAdmission : DiscoveredAxisAdmission {
        override fun cpuFrequenciesKhz(): Map<Int, Set<Int>> {
            val discovered = probe.probe()
            val out = mutableMapOf<Int, Set<Int>>()
            discovered.cpuPolicies.forEach { policy ->
                val published = policy.availableFrequenciesKhz.toSet()
                policy.cpuIds.forEach { cpuId -> out[cpuId] = published }
            }
            return out
        }

        override fun gpuPowerLevelCount(): Int = probe.probe().gpuFrequenciesKhz.size
    }

    /**
     * API v10: the discovered clock ceiling, WRITTEN here .
     *
     * Lazy for the same two reasons [discoveredAxisRecords] is: it needs [probe], and a device whose
     * owner never touches the discovered tuner should never pay for the file.
     *
     * **The command port is the UN-FENCED one, deliberately.** `pServer::executeChecked` is the same
     * privileged transport every other write in this process ends at, minus
     * [GuardianFencedHardwareCommandPort] — which could not admit this write anyway, since it
     * requires a Guardian fence identity that exists only inside an armed transaction. See
     * [DiscoveredCeilingActuator]'s KDoc for why a ceiling arms nothing.
     *
     * The record store is [DiscoveredAxisRecordStore.createCeiling] — the v9 memo's sibling file,
     * never its file. Same admission, same shape, different owner.
     */
    private val discoveredCeiling: DiscoveredCeilingActuator by lazy {
        DiscoveredCeilingActuator(
            discovery = ProbeCeilingDiscovery(),
            nodes = { path -> systemReader.read(path) },
            commands = { command -> pServer.executeChecked(command) },
            records = DiscoveredAxisCeilingRecordStore(
                DiscoveredAxisRecordStore.createCeiling(
                    this,
                    composeDiscoveredAxisAdmission(ProbeDiscoveredAxisAdmission()),
                ),
            ),
            clock = object : CeilingClock {
                override fun wallClockMs(): Long = System.currentTimeMillis()
                override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
            },
            session = {
                // "No session / stock" is the only answer that is not an active named profile.
                // APPLYING, ACTIVE, RESTORING, RECOVERY_FAILED, CORRUPT and UNKNOWN all mean a
                // profile may be holding these very nodes, and the profile is the richer claim.
                publicSessionTruth().state
                    .takeIf { it != PublicSessionState.STOCK }
                    ?.name
            },
            keeper = ceilingKeeper,
            hardwareWritesCompiled = BuildConfig.HARDWARE_WRITES_COMPILED,
            log = { line -> Log.i(TAG, line) },
        )
    }

    /**
     * The held ceiling's 1 s watcher: its OWN scheduler, never [monitorExecutor].
     *
     * Separate and independent by construction. The session supervisor's monitor exists only while
     * a named profile session does, and a ceiling is refused while a session exists — so sharing
     * that executor would mean the ceiling's watcher could only run at the exact times a ceiling
     * cannot be held. It is also not a second control path: every tick funnels its hardware work
     * through [onTransactionWorker] onto the same single [transactionExecutor] the monitor cycle
     * and every apply use, so a tick can never interleave with an apply, a withdraw or a profile
     * mutation — whichever reaches the worker first runs to completion before the other starts.
     */
    private val ceilingKeeper = ServiceCeilingKeeper()

    /**
     * API v11: the game audio-focus hold . See [GameAudioFocusController].
     *
     * Lazy for the reason [discoveredCeiling] is: a device whose owner never launches a game through
     * Mimir should not pay for the journal file. The command port is `pServer::executeChecked`, the
     * same un-fenced privileged transport the ceiling uses -- a hold is a per-app preference, not a
     * transaction, so it arms no Guardian envelope and holds no lease.
     */
    private val gameAudioFocusSession: GameAudioFocusSession by lazy {
        GameAudioFocusSession(
            controller = GameAudioFocusController(
                journal = FileGameAudioFocusJournal(gameAudioFocusJournalFile()),
                control = PServerGameAudioFocusControlPort(pServer),
                packages = AndroidGameAudioFocusPackagePolicy(this),
                bootIdentity = { systemReader.read(HardwareProbe.BOOT_ID_PATH)?.trim()?.takeIf(String::isNotEmpty) },
                elapsedRealtimeMs = SystemClock::elapsedRealtime,
                log = { line -> Log.i(TAG_GAME_AUDIO_FOCUS, line) },
            ),
            executeAsync = ::executeTransactionWork,
        )
    }

    private fun gameAudioFocusJournalFile(): File =
        File(deviceProtectedContext.filesDir, FileGameAudioFocusJournal.FILE_NAME)

    /** Cheap "is anything held": the journal's own file, including its staging spelling. */
    private fun gameAudioFocusJournalPresent(): Boolean =
        runCatching { gameAudioFocusJournalFilesPresent(deviceProtectedContext.filesDir) }.getOrDefault(false)

    /**
     * The boot receiver's audio-only start: an app-op held at `ignore` survives a reboot, so a journal with nothing else
     * in the way still needs this daemon in the foreground to put the package back and read it back. Only on this
     * explicit action, and only with no live owner: an ordinary start must never release a hold a client still owns.
     */
    private fun beginGameAudioFocusRecovery() {
        if (!gameAudioFocusJournalPresent()) return
        val foregroundEstablished = runCatching {
            ensureForeground(getString(R.string.notification_recovery_restore))
            true
        }.getOrDefault(false)
        if (!foregroundEstablished) {
            requestTerminalSettlement()
            return
        }
        executeTransactionWork {
            if (!gameAudioFocusSession.ownerPresent()) runCatching { gameAudioFocusSession.recoverOrphan() }
        }
    }

    /**
     * A held package outlives this process -- app-ops persist across reboot -- so a daemon start puts it
     * back. The file check is ahead of the lazy session on purpose, as [resumeDiscoveredCeilingKeeper]'s is.
     */
    private fun releaseOrphanedGameAudioFocus() {
        if (!gameAudioFocusJournalPresent()) return
        runCatching { executeTransactionWork { runCatching { gameAudioFocusSession.recoverOrphan() } } }
    }

    /** [CeilingKeeperScheduler] over one daemon-owned scheduled executor. */
    private inner class ServiceCeilingKeeper : CeilingKeeperScheduler {
        private var executor: ScheduledExecutorService? = null
        private var future: ScheduledFuture<*>? = null

        @Synchronized
        override fun start(tick: () -> Unit) {
            if (destroyed.get()) return
            // A read-only build has nothing to re-assert with; it must not log repairs it cannot do.
            if (!BuildConfig.HARDWARE_WRITES_COMPILED) return
            if (isRunningLocked()) return
            stopLocked()
            val created = try {
                Executors.newSingleThreadScheduledExecutor { runnable ->
                    Thread(runnable, TX_TICK_CEILING_THREAD).apply { isDaemon = true }
                }
            } catch (_: RuntimeException) {
                return
            }
            executor = created
            future = try {
                created.scheduleWithFixedDelay(
                    {
                        if (!destroyed.get()) {
                            // Never let one tick's throw cancel the schedule: a keeper that stopped
                            // silently is the exact failure this whole family exists to close.
                            runCatching { onTransactionWorker(tick) }
                        }
                    },
                    DiscoveredCeilingActuator.KEEPER_PERIOD_MS,
                    DiscoveredCeilingActuator.KEEPER_PERIOD_MS,
                    TimeUnit.MILLISECONDS,
                )
            } catch (_: RuntimeException) {
                stopLocked()
                null
            }
        }

        @Synchronized
        override fun stop() = stopLocked()

        @Synchronized
        override fun isRunning(): Boolean = isRunningLocked()

        private fun isRunningLocked(): Boolean = future?.isDone == false

        private fun stopLocked() {
            future?.cancel(false)
            future = null
            executor?.shutdownNow()
            executor = null
        }
    }

    /**
     * A daemon restart must not orphan a held ceiling — see [DiscoveredCeilingActuator.resumeIfHeld].
     *
     * The file check is deliberately ahead of the actuator: [discoveredCeiling] is lazy precisely so
     * a device whose owner never touched the discovered tuner never pays for the record file, and
     * touching it unconditionally on every create would spend that on every device. Off the main
     * thread because reading the record is file work, on the same worker every other hardware
     * decision in this process runs on.
     */
    private fun resumeDiscoveredCeilingKeeper() {
        if (!BuildConfig.HARDWARE_WRITES_COMPILED) return
        if (!ceilingRecordFilePresent()) return
        runCatching { executeTransactionWork { runCatching { discoveredCeiling.resumeIfHeld() } } }
    }

    /**
     * A post-control fan hold outlives the process that entered it : its
     * `fan_mode 6` is a persistent setting, so a daemon restart, a reboot (the boot receiver starts
     * this service on [ACTION_POST_CONTROL_HOLD_RELEASE] when the marker is on disk) and a
     * re-install all arrive here with the fan still held. The [PostControlHoldMarker] is the
     * obligation: it holds the started lifetime open through
     * [StartedServiceTerminalTruth.postControlHoldInactive] until a tick of THIS process hands the
     * fan back (`POST_CONTROL_HOLD exit reason=stale_owner`) -- or, if the device is still hot,
     * holds it again through the ordinary force-then-hold path -- and [evaluateTerminalSettlement]
     * re-arms the watch whenever it finds the obligation with no watch running, so a watch stopped
     * before its first tick settled cannot strand the marker. Entering a hold promotes the service
     * to that started lifetime itself ([promotePostControlHoldLifetime]) rather than releasing in
     * `onDestroy`: a system kill never runs `onDestroy`, and a checked write with a 3 s settle has
     * no place on the main thread during destruction. Called at create and from the action; one
     * file stat here, the hardware work on the worker. A dry-run build cannot write the release,
     * says so once, and leaves the marker for a build that can.
     */
    private fun resumePostControlHold() {
        if (!postControlHoldMarker.exists()) return
        val unreleasable = postControlHoldUnreleasableReason()
        if (unreleasable != null) {
            // Said once per reason, never the `armed` line: the marker is not this build's (or this
            // moment's) to release, and it is no obligation of the started lifetime either.
            if (postControlHoldUnreleasableLogged != unreleasable) {
                postControlHoldUnreleasableLogged = unreleasable
                Log.i(FAN_CONTROL_TAG, "postControlWatch=stale_hold_marker_unreleasable reason=$unreleasable")
            }
            return
        }
        if (postControlFuture != null) {
            // The hold's own promotion start: the watch that entered it is already running.
            Log.i(FAN_CONTROL_TAG, "postControlWatch=running reason=hold_promoted")
            return
        }
        Log.i(FAN_CONTROL_TAG, "postControlWatch=armed reason=stale_hold_marker")
        startPostControlWatch()
    }

    /**
     * Why this build, right now, cannot write the hold's release -- or null when it can. A read-only
     * build never can; before the user unlocks, nothing here reads preferences
     * (`prefs()` is set only after unlock -- the same guard [capabilities] uses), and the
     * USER_UNLOCKED dispatch starts this service again; a LAB build in dry run cannot write. An
     * unreleasable marker is no obligation: the terminal gate stays open, and it is left for a
     * build that can release it.
     */
    private fun postControlHoldUnreleasableReason(): String? = when {
        !BuildConfig.HARDWARE_WRITES_COMPILED -> "readOnlyBuild"
        !isUserUnlocked() -> "locked"
        BuildConfig.DIAGNOSTICS_COMPILED && !credentialStoresReady() -> "locked"
        dryRun() -> "dryRun"
        else -> null
    }

    /** [ACTION_POST_CONTROL_HOLD_RELEASE]: the boot receiver's (or this service's own) start over a marker. */
    private fun beginPostControlHoldRelease() {
        // A startForegroundService start must go foreground; the gate ends it at once when there is
        // nothing this build can release (the marker gone, or unreleasable here).
        val foregroundEstablished = runCatching {
            ensureForeground(getString(R.string.notification_recovery_restore))
            true
        }.getOrDefault(false)
        if (!foregroundEstablished) {
            requestTerminalSettlement()
            return
        }
        resumePostControlHold()
        requestTerminalSettlement()
    }

    /**
     * The hold's marker was just written: from here the hold must outlive a bind-only lifetime and
     * a watch stop, so the service starts itself on [ACTION_POST_CONTROL_HOLD_RELEASE] (sticky: a
     * system kill brings it back, and `onCreate` finds the marker) and goes foreground, exactly as
     * the recovery obligations do. Failure is logged, never thrown: the marker and the create-time
     * resume still cover the next start.
     */
    private fun promotePostControlHoldLifetime() {
        if (destroyed.get()) return
        val promoted = runCatching {
            ensureForeground(getString(R.string.notification_recovery_restore))
            startService(Intent(this, MimirTweakerService::class.java).setAction(ACTION_POST_CONTROL_HOLD_RELEASE))
            true
        }.getOrDefault(false)
        Log.i(FAN_CONTROL_TAG, "POST_CONTROL_HOLD lifetime=${if (promoted) "started" else "promote_failed"}")
    }

    /**
     * A live hold, or a marker on disk, is a started obligation -- for a build that can release it.
     * A marker this build cannot release ([postControlHoldUnreleasableReason]) is not: a SAFE or
     * dry-run LAB build installed over a live production hold must not sit foreground forever
     * over it. Cached presence: this is also read on main.
     */
    private fun postControlHoldObligationAbsent(): Boolean {
        if (postControlThermalWatch.isHolding()) return false
        if (!(this::deviceProtectedContext.isInitialized && postControlHoldMarker.isPresentCached())) return true
        return postControlHoldUnreleasableReason() != null
    }

    /** Cheap "is there anything to resume": the record's own file, including AtomicFile's spellings. */
    private fun ceilingRecordFilePresent(): Boolean = runCatching {
        val directory = deviceProtectedContext.filesDir ?: return false
        val name = DiscoveredAxisRecordStore.CEILING_RECORD_FILE
        File(directory, name).exists() ||
            File(directory, "$name.bak").exists() ||
            File(directory, "$name.new").exists()
    }.getOrDefault(false)

    /**
     * The ceiling's view of this process's own hardware discovery.
     *
     * `probe.probe()` and never `capabilities()`, for exactly the reason
     * [ProbeDiscoveredAxisAdmission] gives: the qualification manifest, the certification store and
     * the dry-run flag have no business in a decision about whether a frequency exists.
     */
    private inner class ProbeCeilingDiscovery : CeilingDiscovery {
        override fun cpuPolicies(): List<CeilingCpuPolicy> {
            val discovered = probe.probe()
            return discovered.cpuPolicies.map { policy ->
                CeilingCpuPolicy(
                    id = policy.id,
                    path = policy.path,
                    cpuIds = policy.cpuIds,
                    availableKhz = policy.availableFrequenciesKhz,
                    settledFloorKhz = discovered.cpuSettledFloorsKhz[policy.id],
                )
            }
        }

        override fun gpuFrequenciesKhz(): List<Int> = probe.probe().gpuFrequenciesKhz

        override fun gpuAxisAvailable(): Boolean = probe.probe().gpuNodeAvailable
    }

    /**
     * A held discovered ceiling must be given back BEFORE a named profile captures it as stock.
     *
     * Returns null when the apply may proceed; a refusal code when it must not. Called at the entry
     * of every named-profile apply path, ahead of any capture.
     */
    private fun ceilingPrecedenceFailureCode(): String? = CeilingPrecedenceGate.refusalCode(
        // Fails CLOSED: an exception from the check refuses the apply (it used to read as "nothing held" and proceed).
        // On the transaction worker, serialised with every ceiling mutation and the keeper (see [ceilingOnTransactionWorker]).
        evaluate = { onTransactionWorker { discoveredCeiling.withdrawBeforeProfileApply() } },
        log = { message -> Log.w(TAG, message) },
    )

    /**
     * The second half of the ceiling/profile exclusion: read-only, and only meaningful ON the transaction worker, inside
     * the apply's own block (see [runApplyWithSessionCommit]). Null when no ceiling record remains; a refusal code when
     * one does (a v10 apply landed after [ceilingPrecedenceFailureCode] ran) or when it cannot be read.
     */
    private fun ceilingRecheckFailureCode(): String? = CeilingPrecedenceGate.refusalCode(
        evaluate = { discoveredCeiling.recordedCeilingBeforeCapture() },
        log = { message -> Log.w(TAG, message) },
    )

    /**
     * A shared QoS node that nobody owns but is still held is given back BEFORE this apply selects its
     * CPU transport -- reclaim, do not demote (see [SharedQosNodeReclaimer]). Also writes the one
     * per-session `CPU_TRANSPORT` line naming the transport this apply will use and, when it is not the
     * shared node, exactly why.
     *
     * Returns null when the apply may proceed; a refusal when the node is held, unowned, and could not be restored
     * and read back (the apply is then refused rather than silently run on the vendor-reasserted
     * `scaling_max_freq`), and ALSO when this check could not run at all -- it fails CLOSED, see
     * [CpuTransportPrecedence]: an unconfirmed privileged route means nothing is written.
     */
    private fun cpuTransportPrecedenceRefusal(): CpuTransportRefusal? {
        if (!BuildConfig.HARDWARE_WRITES_COMPILED) return null
        return CpuTransportPrecedence.refusal(log = { message -> Log.w(TAG, message) }, evaluate = {
            onTransactionWorker {
                val before = probe.probe()
                val outcome = qosReclaimer.reclaimIfOrphaned(before.cpuPolicies)
                val after = if (outcome is QosReclaimOutcome.Reclaimed) probe.probe() else before
                val decision = CpuCeilingTransport.decide(
                    policies = after.cpuPolicies,
                    rawQosMap = systemReader.read(HardwareProbe.CPU_QOS_MAX_PATH),
                    // POSITIVE presence only: a read that timed out or failed is not "no such node". PRESENT and
                    // UNKNOWN both leave an empty read as NODE_UNREADABLE / NODE_MISSING_OR_UNREADABLE, which refuses.
                    nodePresent = when (OsNodePresenceProbe.probe(HardwareProbe.CPU_QOS_MAX_PATH)) {
                        NodePresence.ABSENT -> false
                        NodePresence.PRESENT -> true
                        NodePresence.UNKNOWN -> null
                    },
                )
                Log.i(TAG, CpuCeilingTransport.describeChoice(decision, outcome.wire))
                CpuTransportPrecedence.confirm(outcome, decision)
            }
        })
    }

    private val qosReclaimer: SharedQosNodeReclaimer by lazy {
        SharedQosNodeReclaimer(
            nodes = { path -> systemReader.read(path) },
            modeOf = { path -> OsPosixNodeMetadataReader.read(path)?.mode },
            presenceOf = OsNodePresenceProbe,
            commands = { command -> pServer.executeChecked(command) },
            ownerOrNull = {
                publicSessionTruth().state.takeIf { it != PublicSessionState.STOCK }?.name
            },
            log = { line -> Log.i(TAG, line) },
        )
    }

    private lateinit var transaction: TransactionCoordinator
    private lateinit var journal: TransactionJournal

    /**
     * The same journal, read through its lock-free snapshot. Public truth and status read THIS and never [journal]'s
     * `load()`, which takes the journal lock and does file and authentication I/O (see [SnapshotTransactionJournal]).
     */
    private lateinit var journalSnapshots: SnapshotTransactionJournal
    private lateinit var fpsLimiterJournal: FpsLimiterJournal
    private lateinit var fpsLimiterSession: FpsLimiterServiceSession
    private var calibrationStore: SharedPreferencesCalibrationReportStore? = null
    private var certificationStore: CertificationStore? = null
    private var calibrationRunner: CalibrationSessionRunner? = null
    private var rollbackDrillRunner: RollbackDrillRunner? = null
    private var preferences: android.content.SharedPreferences? = null
    private lateinit var supervisor: HardwareSessionSupervisor
    private lateinit var attestor: ForegroundWorkloadAttestor
    private lateinit var livePerformanceTelemetry: LivePerformanceTelemetrySampler
    private lateinit var liveTelemetryCache: LiveTelemetrySnapshotCache
    private lateinit var supervisorPolicy: SupervisorPolicy
    private lateinit var ownerHardLeasePolicy: OwnerHardLeasePolicy
    private lateinit var productQualificationResolver: ProductQualificationResolver
    private var remoteGuardianAuthority: RemoteGuardianProcessAuthority? = null
    /**
     * F2 (fan fence, 2026-10-14): the Guardian's hand-back, carried from the heartbeat thread to the
     * monitor tick that owns session state -- see [GuardianHandbackGate]. Read on every tick before
     * any decision executes; written by [onGuardianSessionRetired].
     */
    private val guardianHandback = GuardianHandbackGate()
    /** F2: the Guardian's terminal receipt store, so the tick can see a restore the Guardian ran alone. */
    @Volatile
    private var guardianTerminalReceipts: GuardianTerminalReceiptPort? = null
    /**
     * F2: the ORDINARY monitor cycle, kept so a hand-back can run it at once instead of waiting
     * for the next 1 Hz slot -- the same "never a second, lesser control path" rule as
     * [registerPowerTransitionRecovery]. Null while no monitor runs.
     */
    @Volatile
    private var monitorCycleRunnable: Runnable? = null
    private val fanController = SmartFanThermalController()
    // Reconstructed with durable SharedPreferences-backed persistence once credential-protected
    // storage is available -- see ensureCredentialStores(). Pre-unlock this is process-memory-only
    // (no active session is reachable before unlock regardless).
    private var fanControlSelections = ActiveFanControlSelectionStore()
    private val manualFanHandoffStore = ManualFanHandoffStore()
    private var manualFanHandoffIdentity: ManualFanHandoffIdentity? = null
    /**
     * D2 : the code
     * [selectSystemProfileOnWorker]'s `applyBody` recorded for its own MANUAL-entry attempt on
     * THIS select-path request -- the actual failure code when a MANUAL attempt was made and
     * failed, or [SELECT_PATH_FAN_ENTRY_NOT_ATTEMPTED_CODE] when the profile/hardware were
     * ineligible and MANUAL was never attempted at all. Cleared to null at the top of every
     * `applyBody` turn (so a stale code from an earlier request can never leak forward) and again
     * when [settleMimirFanOwnershipAfterProfileApplyOnWorker]'s `SCHEDULE_RENEWAL` route consumes
     * it via [selectPathFanEntryRenewalCode] -- the same single-shot-consume shape
     * [manualFanHandoffIdentity] itself follows.
     */
    private var selectPathFanEntryFallbackCode: String? = null
    /**
     * CPU-awake fan liveness: every timestamp passed to this deadman uses uptimeMillis, so a
     * suspended CPU cannot age a healthy controller or repair window. Guardian payloads and
     * transaction deadlines retain their elapsed-realtime domain at their own seams.
     */
    private val manualFanMonitorLiveness = ManualFanMonitorLiveness()
    private var fanDutyMutationPort: FanDutyMutationPort? = null
    private var activeFanCapabilities: HardwareCapabilities? = null
    /**
     * Generation-
     * keyed renewal state, set when [settleMimirFanOwnershipAfterProfileApplyOnWorker]'s
     * `SCHEDULE_RENEWAL` route defers instead of failing the apply (the profile stays applied,
     * D2 having already attempted -- and failed or been ineligible for -- MANUAL entry on this
     * same apply) or when [fanControlDecision]'s SMART branch observes the live fan mode has
     * drifted away from Mimir's contract under an active session. Keyed to the exact
     * generation the degrade/drift happened under ([FanOwnershipRenewalState.generation]) so a
     * renewal from one session can never be acted on inside a different one --
     * [FanOwnershipRenewalPolicy.forActiveGeneration] drops it the moment the active generation no
     * longer matches. Cleared to null on a genuine settle, a session ending, or a renewal that
     * itself concludes SESSION_LOST (I5 -- the ordinary session-end machinery then owns what
     * happens next).
     */
    private var fanOwnershipRenewal: FanOwnershipRenewalState? = null

    /**
     * E4 : elapsed-realtime
     * deadline before which [observeVendorSmartDuty] must not fold a sample into the learned
     * vendor floor -- set to `now + VENDOR_FLOOR_LEARNING_SUPPRESSION_MS` on every CONFIRMED
     * Mimir-initiated `fan_mode` write reached by this service
     * ([stampVendorFloorLearningSuppression]): the transition entry's hand-to-4, the reassert
     * re-entry's hand-to-4, and the post-control watch's `forceCoolingSmartReassert()`. The
     * port's own writes have a separate local clock; [observeVendorSmartDuty] folds both into
     * one effective deadline. Coordinator restore completion also stamps this daemon clock:
     * the remote Guardian's port clock cannot be observed here, including after internal rollback.
     * Every one of these writes commands full PWM at mode 4 by construction (I3) -- never a
     * genuine vendor-chosen duty -- so folding one into [VendorSmartFanFloor] would teach it "the
     * vendor always drives 100%".
     */
    private var vendorFloorLearningSuppressedUntilElapsedMs: Long = 0L

    /**
     * Guards [renewMimirFanOwnershipOnWorker] against a second tick dispatching a concurrent
     * renewal attempt while one is already queued/running on [transactionExecutor] -- the tick
     * itself only ever reads/writes [fanOwnershipRenewal] on that same executor, but the dispatch
     * in [fanControlDecision] happens from inside an already-running transaction-worker task (see
     * that function's own kdoc for why it must not block), so this flag is the single-attempt-at-
     * a-time fence instead of executor mutual exclusion.
     */
    private val fanOwnershipRenewalInFlight = AtomicBoolean(false)
    /** Last emitted fan-availability line; see [logFanSectionAvailability] for why it is gated. */
    @Volatile
    private var lastFanSectionAvailabilityLine: String? = null
    /** Exact sample produced by the current supervisor tick and consumed once by fan control. */
    private var latestMonitorFanSample: FanControlSample? = null
    /**
     * Concrete reference alongside the interface-typed `commands` handed to [transaction] --
     * D2's [postControlThermalWatch] runs with no active session and therefore no journal to
     * fence, so it deliberately calls the direct port rather than going through
     * [TransactionCoordinator] or the Guardian-fencing wrapper (see
     * [PServerHardwareCommandPort.forceCoolingSmartReassert]'s own kdoc). Set once in
     * [onCreate], never reassigned.
     */
    private var directHardwareCommands: PServerHardwareCommandPort? = null

    private val transactionExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, TX_WORKER_THREAD_NAME).apply { isDaemon = true }
    }
    /**
     * Where terminal settlement reads the durable journals, off the main thread (see [requestTerminalSettlement]).
     * Deliberately not [transactionExecutor]: every task there ends by requesting a settlement.
     */
    private val settlementExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mimir-tweaker-settlement").apply { isDaemon = true }
    }
    private val publicOwners = PublicOwnerRegistry(::onPublicOwnerDeath)
    private var monitorExecutor: ScheduledExecutorService? = null
    @Volatile private var monitorFuture: ScheduledFuture<*>? = null
    private var powerTransitionReceiver: BroadcastReceiver? = null
    /**
     * Manual fan backstop: session-independent watch armed only after a genuine
     * committed Restore (or a thermally-rejected apply, see D3's wiring) leaves the device at
     * vendor Smart with nobody watching. See [PostControlThermalWatch]'s own kdoc for the full
     * mechanism. Mutually exclusive with [monitorExecutor] by construction -- [stopMonitor] always
     * disarms this too, and [startMonitor] always calls [stopMonitor] first.
     */
    private val postControlThermalWatch = PostControlThermalWatch(
        clock = PostControlClock { SystemClock.elapsedRealtime() },
    )
    private var postControlExecutor: ScheduledExecutorService? = null
    @Volatile private var postControlFuture: ScheduledFuture<*>? = null
    /** the watch's actuation; see [postControlHoldController]. */
    private var postControlHold: PostControlHoldController? = null
    /** The hold's durable obligation; lazy so it is built after [deviceProtectedContext] (first used at the end of onCreate). */
    private val postControlHoldMarker: PostControlHoldMarker by lazy {
        PostControlHoldMarker(File(deviceProtectedContext.filesDir, PostControlHoldMarker.FILE_NAME))
    }
    /** The last `stale_hold_marker_unreleasable reason=` this process logged; each reason once. */
    @Volatile private var postControlHoldUnreleasableLogged: String? = null

    private var foreground = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val destroyed = AtomicBoolean(false)
    private val labApplyHold = LabApplyHoldLiveness()
    /** LAB diagnostics only; process-memory loss intentionally destroys an unconsumed offer. */
    private val labPreMutationBaselineHandoff = LabPreMutationBaselineHandoff(SystemClock::elapsedRealtime)
    private val recoveryOwnership = RecoveryOwnership()
    private val foregroundEstablishBackoff = ForegroundEstablishBackoff()
    private val recoveryRetryFuture = AtomicReference<ScheduledFuture<*>?>(null)
    private var recoveryScheduler: ScheduledExecutorService? = null
    private val fgsRetryFuture = AtomicReference<ScheduledFuture<*>?>(null)
    private val startedServiceLifetime = StartedServiceLifetime()
    private val pendingAdmissionFuture = AtomicReference<ScheduledFuture<*>?>(null)
    private val fpsPendingAdmissionFuture = AtomicReference<ScheduledFuture<*>?>(null)
    private val transactionWorkCount = AtomicInteger(0)
    private val boundedTransactionWorker = BoundedTransactionWorker(
        executor = transactionExecutor,
        workCount = transactionWorkCount,
        workerThreadName = TX_WORKER_THREAD_NAME,
        onSettled = ::requestTerminalSettlement,
        log = { message -> Log.w(TAG, message) },
    )
    /** Where a failed public V4 apply is settled: on the worker, never on the Binder thread that answers it. */
    private val failedApplySettlement = FailedApplySettlementHandoff(
        onWorker = { block -> onTransactionWorker(block) },
        enqueue = { block -> executeTransactionWork(block) },
        log = { message -> Log.w(TAG, message) },
    )

    /** What a monitor tick's real restore (or "no session") outcome settles; also the tick's late settlement on the worker. */
    private val monitorTickSettlement = MonitorTickSettlement(
        abortCalibration = { reason -> calibrationRunner?.abort(reason) },
        clearPublicOwnerForSession = { generation, transactionId ->
            publicOwners.clearMatchingSession(generation, transactionId)
        },
        clearCurrentPublicOwner = { publicOwners.snapshot()?.let { publicOwners.clearIfToken(it.tokenId) } },
        clearLabApplyHold = { labApplyHold.clear() },
        settleLifetime = { startedServiceLifetime.settleActiveOrRecovery() },
        requestTerminalSettlement = ::requestTerminalSettlement,
        stopMonitor = ::stopMonitor,
        startPostControlWatch = ::startPostControlWatch,
    )
    private val terminalSettlementPosted = AtomicBoolean(false)
    private val lastLabEvidenceSampleElapsedMs = AtomicLong(0L)
    /**
     * See [LabQualificationEvidenceSampler] for why this exists and why it runs on its OWN
     * thread instead of the fan-monitor thread: LAB-only, gives the WLE harness a qualification
     * sample even with no live-telemetry consumer polling, without ever adding work to the
     * fan-control closure. Started/stopped from `startMonitor()`/`stopMonitor()` -- the same
     * choke points that start/stop the fan-monitor thread -- so it keeps the "runs for the whole
     * hold, stops when the hold ends" lifecycle without sharing that thread. [liveTelemetryCache]
     * is a `lateinit var` assigned in `onCreate`; the lambda below only dereferences it once a
     * tick actually runs, by which point setup has completed (this sampler is never started
     * before `onCreate` finishes, since `startMonitor()` is only ever called after it).
     */
    private val labQualificationEvidenceSampler = LabQualificationEvidenceSampler(
        enabled = ::labQualificationEvidenceEnabled,
        sampleTelemetry = { liveTelemetryCache.sample() },
    )
    private var lifetimeScheduler: ScheduledExecutorService? = null
    private val rct1ProbeRandom = SecureRandom()

    private val binder = object : IMimirTweaker.Stub() {
        override fun getApiVersion(): Int = guarded { API_VERSION }

        override fun getCapabilities(): Bundle = guarded {
            // The one door into this daemon that has no rate limit, no cross-call memo, and -- since
            // the negative-resolution cache landed -- no logging of its own at all. It is also where
            // an unexplained 1.13-1.14s cadence was measured three times on the reference Nova and then
            // stopped reproducing. See CapabilityCallCadenceWatch for why every earlier instrument
            // could only exonerate its own surface. Edge-triggered: two lines per episode, never per
            // call.
            noteCapabilityCall()
            capabilities().toBundle()
        }

        override fun getTelemetry(): Bundle = guarded {
            // Served from LiveTelemetrySnapshotCache (750 ms, original stamps preserved) so the
            // sysfs sweep runs per refresh, not per Binder transaction. The hardware inventory inside
            // the probe is cached for the life of the process (see HardwareProbe.StaticHardwareTopology).
            // The daemon reads no frames: the live sampler is wired to LiveGameFrameTelemetryUnavailable
            // and the HUD's FPS is GameNative's own stream. The fan-control readout is cheap in-memory
            // session state and stays per-call so a fan mutation is never reported stale; composed after
            // the cached stamp, it can only look older than it is (over-reject, never under-reject).
            val sample = liveTelemetryCache.sample()
            sample.telemetry.toBundle()
                .putLivePerformanceTelemetry(sample.live)
                .putActiveFanControlReadout()
        }

        override fun applyProfile(profileId: String?, packageName: String?): Bundle = guarded {
            if (legacyMutationRequiresUpgrade()) {
                return@guarded apiUpgradeRequiredBundle()
            }
            ensureCredentialStores()
            val safeProfile = profileId.orEmpty().trim().lowercase()
            val safePackage = packageName.orEmpty().trim()
            if (!PACKAGE_PATTERN.matches(safePackage)) {
                return@guarded TransactionResult(
                    success = false,
                    applied = false,
                    restored = false,
                    code = "INVALID_PACKAGE",
                    message = "A concrete Android package name is required.",
                ).toBundle()
            }
            // A held discovered ceiling is given back BEFORE this apply can capture it as stock.
            ceilingPrecedenceFailureCode()?.let { code ->
                return@guarded TransactionResult(
                    success = false,
                    applied = false,
                    restored = false,
                    code = code,
                    message = CeilingPrecedenceGate.refusalMessage(code),
                ).toBundle()
            }
            cpuTransportPrecedenceRefusal()?.let { refusal ->
                return@guarded TransactionResult(
                    success = false,
                    applied = false,
                    restored = false,
                    code = refusal.code,
                    message = refusal.message,
                ).toBundle()
            }
            val ownerUid = Binder.getCallingUid()
            val requestIdentity = "binder:$ownerUid:$safeProfile:$safePackage:${SystemClock.elapsedRealtime()}"
            val cancelToken = CancelToken(requestIdentity)
            if (BuildConfig.DIAGNOSTICS_COMPILED && !labApplyHold.tryBeginPendingHold()) {
                return@guarded TransactionResult(
                    success = false,
                    applied = false,
                    restored = false,
                    code = "LAB_HOLD_ACTIVE",
                    message = "An exact LAB hold is already active.",
                ).toBundle()
            }
            val result = runCatching {
                onTransactionWorker {
                    runApplyWithSessionCommit(
                        profileId = safeProfile,
                        ownerUid = ownerUid,
                        ownerTokenId = LAB_OWNER_TOKEN_ID,
                        targetPackage = safePackage,
                        requestIdentity = requestIdentity,
                        cancelToken = cancelToken,
                        isOwnerAlive = {
                            !BuildConfig.DIAGNOSTICS_COMPILED || labApplyHold.isPendingOrActive()
                        },
                    ) { operationCapabilities, isCancelled, onAppliedAdopt ->
                        transaction.apply(
                            profileId = safeProfile,
                            packageName = safePackage,
                            capabilities = operationCapabilities,
                            dryRun = dryRun(),
                            isCancelled = isCancelled,
                            onAppliedAdopt = onAppliedAdopt,
                            onAdoptionRollback = ::clearProvisionalAdoption,
                        )
                    }
                }
            }.getOrElse { error ->
                TransactionResult(
                    success = false,
                    applied = false,
                    restored = false,
                    code = transactionWorkerFailureCode(error),
                    message = error.message ?: "Legacy apply failed.",
                    requestId = requestIdentity,
                )
            }
            val disposition = runCatching {
                onTransactionWorker { observePostTransactionDisposition(result) }
            }.getOrNull()
            if (BuildConfig.DIAGNOSTICS_COMPILED) {
                var committed = false
                try {
                    committed = result.applied &&
                        disposition == PostTransactionDisposition.KeepActiveApplied &&
                        labApplyHold.commitPendingHold()
                } finally {
                    if (!committed) labApplyHold.clear()
                }
            }
            result.toBundle()
        }

        /**
         * LAB-only thermal-derate rung verification programme (WAVE2_THERMAL_DERATE_SPEC.md "Rung
         * verification programme", 2026-08-08): applies [ThermalDerateLadder]'s
         * compiled-in rungIndex for profileId through the SAME transaction/journal/Guardian path
         * as [applyProfile] -- see [TransactionCoordinator.apply]'s rungOverride parameter -- never
         * the signed [TransactionCoordinator.applyDerate] path, so this neither requires nor
         * consults any qualification state. rungIndex is 1-based; an unknown profileId or an
         * out-of-range rungIndex fails closed with a readable code rather than clamping to nominal
         * or the floor. Fails closed (LAB_ONLY) before touching any other state whenever
         * DIAGNOSTICS_COMPILED is false, i.e. always in production and safe builds.
         */
        override fun applyThermalDerateRungLab(
            profileId: String?,
            packageName: String?,
            rungIndex: Int,
        ): Bundle = guarded {
            thermalDerateRungLabFailureCode(BuildConfig.DIAGNOSTICS_COMPILED)?.let { code ->
                return@guarded TransactionResult(
                    success = false,
                    applied = false,
                    restored = false,
                    code = code,
                    message = "Thermal-derate rung apply is a LAB-only diagnostic.",
                ).toBundle()
            }
            if (legacyMutationRequiresUpgrade()) {
                return@guarded apiUpgradeRequiredBundle()
            }
            ensureCredentialStores()
            val safeProfile = profileId.orEmpty().trim().lowercase()
            val safePackage = packageName.orEmpty().trim()
            val resolution = ThermalDerateRungRequest.resolve(safeProfile, rungIndex)
            val rung = when (resolution) {
                is ThermalDerateRungResolution.UnknownProfile -> return@guarded TransactionResult(
                    success = false,
                    applied = false,
                    restored = false,
                    code = "UNKNOWN_PROFILE",
                    message = "Unknown thermal-derate profile: $safeProfile",
                ).toBundle()
                is ThermalDerateRungResolution.OutOfRange -> return@guarded TransactionResult(
                    success = false,
                    applied = false,
                    restored = false,
                    code = "RUNG_OUT_OF_RANGE",
                    message = "Rung $rungIndex is out of range for $safeProfile " +
                        "(valid: 1..${resolution.maxRung}).",
                ).toBundle()
                is ThermalDerateRungResolution.Resolved -> resolution.rung
            }
            if (!PACKAGE_PATTERN.matches(safePackage)) {
                return@guarded TransactionResult(
                    success = false,
                    applied = false,
                    restored = false,
                    code = "INVALID_PACKAGE",
                    message = "A concrete Android package name is required.",
                ).toBundle()
            }
            val ownerUid = Binder.getCallingUid()
            val requestIdentity =
                "binder:$ownerUid:$safeProfile:rung$rungIndex:$safePackage:${SystemClock.elapsedRealtime()}"
            val cancelToken = CancelToken(requestIdentity)
            if (!labApplyHold.tryBeginPendingHold()) {
                return@guarded TransactionResult(
                    success = false,
                    applied = false,
                    restored = false,
                    code = "LAB_HOLD_ACTIVE",
                    message = "An exact LAB hold is already active.",
                ).toBundle()
            }
            val result = runCatching {
                onTransactionWorker {
                    runApplyWithSessionCommit(
                        profileId = safeProfile,
                        ownerUid = ownerUid,
                        ownerTokenId = LAB_OWNER_TOKEN_ID,
                        targetPackage = safePackage,
                        requestIdentity = requestIdentity,
                        cancelToken = cancelToken,
                        isOwnerAlive = { labApplyHold.isPendingOrActive() },
                    ) { operationCapabilities, isCancelled, onAppliedAdopt ->
                        transaction.apply(
                            profileId = safeProfile,
                            packageName = safePackage,
                            capabilities = operationCapabilities,
                            dryRun = dryRun(),
                            isCancelled = isCancelled,
                            onAppliedAdopt = onAppliedAdopt,
                            onAdoptionRollback = ::clearProvisionalAdoption,
                            rungOverride = rung,
                        )
                    }
                }
            }.getOrElse { error ->
                TransactionResult(
                    success = false,
                    applied = false,
                    restored = false,
                    code = transactionWorkerFailureCode(error),
                    message = error.message ?: "Rung apply failed.",
                    requestId = requestIdentity,
                )
            }
            val disposition = runCatching {
                onTransactionWorker { observePostTransactionDisposition(result) }
            }.getOrNull()
            var committed = false
            try {
                committed = result.applied &&
                    disposition == PostTransactionDisposition.KeepActiveApplied &&
                    labApplyHold.commitPendingHold()
            } finally {
                if (!committed) labApplyHold.clear()
            }
            result.toBundle()
        }

        override fun restore(): Bundle = guarded {
            if (legacyMutationRequiresUpgrade()) {
                return@guarded apiUpgradeRequiredBundle()
            }
            val result = try {
            onTransactionWorker {
                calibrationRunner?.abort("PROFILE_RESTORE_REQUESTED")
                labPreMutationBaselineHandoff.clear()
                stopMonitor()
                // The legacy (no-args) restore is the one restore family
                // restoreBoundStamped cannot cover -- it has no generation/transactionId to bind
                // against. Stamp it the same way, guarded on `.restored`, so a legacy client's
                // restore is a Mimir-initiated `fan_mode` write like every other restore family.
                val restoreResult = transaction.restore().also {
                    if (it.restored) stampVendorFloorLearningSuppression()
                }
                when (
                    val commit = ExplicitRestoreGate.afterRestoreAttempt(
                        result = restoreResult,
                        loadJournal = { journal.load() },
                    )
                ) {
                    is ExplicitRestoreCommit.Cleared -> {
                        supervisor.clearSession()
                        labApplyHold.clear()
                        // Same terminal settlement as recovery settle: release ownership,
                        // leave FGS, honor pending stopSelf — never leave while ownership latched.
                        settleRecoveryLeave()
                        restoreResult
                    }
                    is ExplicitRestoreCommit.RetainedHold -> {
                        commit.record?.let(::retainGuardianRecoveryIdentity)
                        supervisor.enterCrossStoreHold()
                        cancelRecoveryRetry()
                        startedServiceLifetime.markRecovery()
                        restoreResult
                    }
                    is ExplicitRestoreCommit.RetainedRetry -> {
                        val record = commit.record
                        if (record != null) {
                            val now = SystemClock.elapsedRealtime()
                            supervisor.retainOrAdoptForRecovery(
                                ActiveSessionIdentity(
                                    generation = record.generation,
                                    transactionId = record.transactionId,
                                    requestId = record.requestId,
                                    ownerUid = Process.myUid(),
                                    targetPackage = record.targetPackage,
                                    activationDeadlineMs = now,
                                    leaseDeadlineMs = record.leaseDeadline,
                                    targetEverForeground = true,
                                    ownerTokenId = RECOVERY_OWNER_TOKEN_ID,
                                    requestDeadlineElapsedRealtimeMs = record.requestDeadline,
                                    hardLeaseDeadlineMs = record.leaseDeadline,
                                    scope = ActiveSessionScope.RECOVERY,
                                ),
                            )
                            supervisor.armBoundedRetryAfterFailedRestore()
                            scheduleRecoveryRetry()
                        }
                        restoreResult
                    }
                }
            }
            } catch (busy: TransactionWorkerBusyException) {
                busyTransactionResult(busy)
            }
            result.toBundle()
        }

        override fun getDryRun(): Boolean = guarded {
            ensureCredentialStores()
            dryRun()
        }

        override fun setDryRun(enabled: Boolean): Boolean = guarded {
            ensureCredentialStores()
            if (!BuildConfig.DIAGNOSTICS_COMPILED) return@guarded dryRun()
            if (!enabled && !BuildConfig.HARDWARE_WRITES_COMPILED) return@guarded false
            onTransactionWorker {
                // Keep real cooling/recovery obligations live instead of labelling their writes
                // a dry run. Serialize this change with the work whose posture it controls.
                if (enabled && !dryRun() && (
                        supervisor.activeSession() != null ||
                            journal.load() != JournalLoadResult.Empty ||
                            monitorFuture != null || postControlFuture != null ||
                            publicOwners.snapshot() != null || recoveryOwnership.isOwned()
                        )
                ) {
                    Log.w(FAN_CONTROL_TAG, "dryRun=refused reason=hardwareControlActive")
                    return@onTransactionWorker false
                }
                prefs().edit().putBoolean(KEY_DRY_RUN, enabled).apply()
                dryRun()
            }
        }

        override fun getCalibrationStatus(): Bundle = guarded {
            ensureCredentialStores()
            calibrationRunner!!.status().toBundle()
        }

        override fun startCalibrationObservation(
            profileId: String?,
            packageName: String?,
            durationSeconds: Int,
        ): Bundle = guarded {
            ensureCredentialStores()
            val safeProfile = profileId.orEmpty().trim().lowercase()
            val safePackage = packageName.orEmpty().trim()
            if (!PACKAGE_PATTERN.matches(safePackage)) {
                return@guarded CalibrationActionResult(
                    success = false,
                    code = "INVALID_PACKAGE",
                    message = "A concrete Android workload package is required.",
                    report = calibrationRunner!!.status(),
                ).toBundle()
            }
            val result = calibrationRunner!!.start(safeProfile, safePackage, durationSeconds)
            if (result.success) {
                val foregroundStarted = runCatching {
                    // An admitted profile already owns this FGS. Re-starting the service with an
                    // empty intent would be interpreted as a durable-journal recovery trigger and
                    // correctly restore the active profile before calibration could observe it.
                    if (CalibrationForegroundStartPolicy.requiresServiceStart(foreground)) {
                        startForegroundService(Intent(this@MimirTweakerService, MimirTweakerService::class.java))
                    }
                    ensureForeground(getString(R.string.notification_calibration_observation))
                }.isSuccess
                if (!foregroundStarted) {
                    val stopped = calibrationRunner!!.stop()
                    return@guarded CalibrationActionResult(
                        success = false,
                        code = "FOREGROUND_START_FAILED",
                        message = "Observation was stopped because foreground execution could not start.",
                        report = stopped.report,
                    ).toBundle()
                }
            }
            result.toBundle()
        }

        override fun stopCalibrationObservation(): Bundle = guarded {
            ensureCredentialStores()
            val result = calibrationRunner!!.stop()
            requestTerminalSettlement()
            result.toBundle()
        }

        override fun runRollbackDrills(profileId: String?, packageName: String?, drillCount: Int): Bundle =
            guarded {
                if (legacyMutationRequiresUpgrade()) {
                    return@guarded apiUpgradeRequiredBundle()
                }
                ensureCredentialStores()
                val safeProfile = profileId.orEmpty().trim().lowercase()
                val safePackage = packageName.orEmpty().trim()
                if (!PACKAGE_PATTERN.matches(safePackage)) {
                    return@guarded rollbackDrillResult(
                        success = false,
                        attempts = 0,
                        successes = 0,
                        code = "INVALID_PACKAGE",
                        message = "A concrete Android workload package is required.",
                    )
                }
                val result = try {
                    onTransactionWorker(TX_LAB_WAIT_MS) {
                        rollbackDrillRunner!!.run(safeProfile, safePackage, drillCount)
                    }
                } catch (busy: TransactionWorkerBusyException) {
                    return@guarded rollbackDrillResult(
                        success = false,
                        attempts = 0,
                        successes = 0,
                        code = SERVICE_BUSY_CODE,
                        message = busy.message ?: "The transaction worker is busy.",
                    )
                }
                if (BuildConfig.DIAGNOSTICS_COMPILED) {
                    Log.i(
                        TAG,
                        "LAB_ROLLBACK_DRILLS terminal code=${result.code} attempts=${result.attempts} " +
                            "successes=${result.successes} settlement=${result.settlementCode}",
                    )
                }
                rollbackDrillResult(
                    success = result.success,
                    attempts = result.attempts,
                    successes = result.successes,
                    code = result.code,
                    message = result.message,
                    settlementCode = result.settlementCode,
                    observedSettlementWindowMs = result.observedSettlementWindowMs,
                    settlementCleanRounds = result.settlementCleanRounds,
                    minimumCleanSettlementWindowMs = result.minimumCleanSettlementWindowMs,
                )
            }

        override fun recordLabSafetyEvidence(
            writesExercised: Boolean,
            rollbackAttempts: Int,
            rollbackSuccesses: Int,
            rebootRestoreVerified: Boolean,
        ): Bundle = guarded {
            ensureCredentialStores()
            val result = rollbackDrillRunner!!.recordLabEvidence(
                writesExercised = writesExercised,
                rollbackAttempts = rollbackAttempts,
                rollbackSuccesses = rollbackSuccesses,
                rebootRestoreVerified = rebootRestoreVerified,
            )
            Bundle().apply {
                putBoolean("success", result.success)
                putString("code", result.code)
                putString("message", result.message)
            }
        }

        override fun applyProfileV4(
            requestId: String?,
            deadlineElapsedRealtimeMs: Long,
            clientToken: IBinder?,
            profileId: String?,
            packageName: String?,
        ): Bundle = guarded {
            val exactRequestId = requestId.orEmpty()
            publicRequestFailure(exactRequestId, deadlineElapsedRealtimeMs)?.let {
                return@guarded it
            }
            val binderToken = clientToken
                ?: return@guarded publicFailure(
                    code = "INVALID_CLIENT_TOKEN",
                    message = "A live Binder client token is required.",
                    callerRequestId = exactRequestId,
                    callerDeadlineElapsedRealtimeMs = deadlineElapsedRealtimeMs,
                )
            val safeProfile = profileId.orEmpty().trim().lowercase()
            val safePackage = packageName.orEmpty().trim()
            if (!PACKAGE_PATTERN.matches(safePackage)) {
                return@guarded publicFailure(
                    code = "INVALID_PACKAGE",
                    message = "A concrete Android package name is required.",
                    callerRequestId = exactRequestId,
                    callerDeadlineElapsedRealtimeMs = deadlineElapsedRealtimeMs,
                )
            }
            publicApplyPostureFailureCode(BuildConfig.HARDWARE_WRITES_COMPILED)?.let { code ->
                return@guarded publicFailure(
                    code = code,
                    message = "This build is read-only; the hardware write gate is disabled.",
                    callerRequestId = exactRequestId,
                    callerDeadlineElapsedRealtimeMs = deadlineElapsedRealtimeMs,
                )
            }
            // A held discovered ceiling is given back BEFORE this apply can capture it as stock.
            ceilingPrecedenceFailureCode()?.let { code ->
                return@guarded publicFailure(
                    code = code,
                    message = CeilingPrecedenceGate.refusalMessage(code),
                    callerRequestId = exactRequestId,
                    callerDeadlineElapsedRealtimeMs = deadlineElapsedRealtimeMs,
                )
            }
            cpuTransportPrecedenceRefusal()?.let { refusal ->
                return@guarded publicFailure(
                    code = refusal.code,
                    message = refusal.message,
                    callerRequestId = exactRequestId,
                    callerDeadlineElapsedRealtimeMs = deadlineElapsedRealtimeMs,
                )
            }
            val now = SystemClock.elapsedRealtime()
            val lifetimeAdmission = when (startedServiceLifetime.admitApply(now)) {
                is StartedServiceLifetime.ApplyAdmission.Admitted -> {
                    cancelPendingAdmissionExpiry()
                    startedServiceLifetime.snapshot().phaseStartId
                }
                StartedServiceLifetime.ApplyAdmission.NotStarted,
                StartedServiceLifetime.ApplyAdmission.Expired,
                StartedServiceLifetime.ApplyAdmission.Busy,
                -> null
            } ?: return@guarded publicFailure(
                code = "SERVICE_LIFETIME_NOT_STARTED",
                message = "Start the bounded API-V4 active-session service lifetime before apply.",
                callerRequestId = exactRequestId,
                callerDeadlineElapsedRealtimeMs = deadlineElapsedRealtimeMs,
            )
            val hardLeaseDeadline = PublicRequestContract.hardLeaseDeadline(ownerHardLeasePolicy, now)
                ?: return@guarded failAdmittedPublicApply(
                    admittedStartId = lifetimeAdmission,
                    code = "LEASE_POLICY_UNAVAILABLE",
                    message = "Product hard-lease duration is not owner-configured; apply fails closed.",
                    callerRequestId = exactRequestId,
                    callerDeadlineElapsedRealtimeMs = deadlineElapsedRealtimeMs,
                )
            val ownerUid = Binder.getCallingUid()
            val registration = when (
                val registered = publicOwners.registerApply(
                    ownerUid = ownerUid,
                    requestId = exactRequestId,
                    token = AndroidClientTokenHandle(binderToken),
                )
            ) {
                is PublicOwnerRegistration.Registered -> registered
                is PublicOwnerRegistration.Busy -> {
                    return@guarded failAdmittedPublicApply(
                        admittedStartId = lifetimeAdmission,
                        code = "APPLY_BUSY",
                        message = "Another exact owner/request is active.",
                        callerRequestId = exactRequestId,
                        callerDeadlineElapsedRealtimeMs = deadlineElapsedRealtimeMs,
                    )
                }
                PublicOwnerRegistration.DeadToken -> {
                    return@guarded failAdmittedPublicApply(
                        admittedStartId = lifetimeAdmission,
                        code = "CLIENT_TOKEN_DEAD",
                        message = "Client token died before journal capture.",
                        callerRequestId = exactRequestId,
                        callerDeadlineElapsedRealtimeMs = deadlineElapsedRealtimeMs,
                    )
                }
            }
            val credentialReady = runCatching { ensureCredentialStores() }
            if (credentialReady.isFailure) {
                publicOwners.completeApply(registration.owner.tokenId, retainActiveOwner = false)
                return@guarded failAdmittedPublicApply(
                    admittedStartId = lifetimeAdmission,
                    code = "SERVICE_NOT_READY",
                    message = credentialReady.exceptionOrNull()?.message
                        ?: "Credential-protected service state is unavailable.",
                    callerRequestId = exactRequestId,
                    callerDeadlineElapsedRealtimeMs = deadlineElapsedRealtimeMs,
                )
            }
            val ownerTokenId = registration.owner.tokenId
            fun failedApply(error: Throwable) = TransactionResult(
                success = false,
                applied = false,
                restored = false,
                code = transactionWorkerFailureCode(error),
                message = error.message ?: "Versioned apply failed.",
                requestId = exactRequestId,
                requestDeadlineElapsedRealtimeMs = deadlineElapsedRealtimeMs,
                hardLeaseDeadlineElapsedRealtimeMs = hardLeaseDeadline,
            )
            // The apply may outlive this Binder call. When it does, settlement is the worker's job, after the block,
            // with the block's real result: settling here would clear the owner and the lifetime under a transaction
            // that is still mutating the supervisor and the journal.
            var settlementHandedToWorker = false
            val result = runCatching {
                onTransactionWorkerSettlingLate(
                    onLateFinish = { late: Result<TransactionResult> ->
                        settlePublicApplyResult(
                            lifetimeAdmission,
                            ownerTokenId,
                            late.getOrElse { failedApply(it) },
                        )
                    },
                ) {
                    val applied = runApplyWithSessionCommit(
                        profileId = safeProfile,
                        ownerUid = ownerUid,
                        ownerTokenId = registration.owner.tokenId,
                        targetPackage = safePackage,
                        requestIdentity = exactRequestId,
                        cancelToken = registration.cancelToken,
                        isOwnerAlive = {
                            publicOwners.snapshot()?.let { owner ->
                                owner.tokenId == registration.owner.tokenId && owner.alive
                            } == true
                        },
                    ) { operationCapabilities, isCancelled, onAppliedAdopt ->
                        transaction.apply(
                            profileId = safeProfile,
                            packageName = safePackage,
                            capabilities = operationCapabilities,
                            dryRun = dryRun(),
                            requestId = exactRequestId,
                            requestDeadlineElapsedRealtimeMs = deadlineElapsedRealtimeMs,
                            hardLeaseDeadlineElapsedRealtimeMs = hardLeaseDeadline,
                            isCancelled = isCancelled,
                            onAppliedAdopt = onAppliedAdopt,
                            onAdoptionRollback = ::clearProvisionalAdoption,
                        )
                    }
                    applied
                }
            }.getOrElse { error ->
                settlementHandedToWorker = (error as? TransactionWorkerBusyException)?.started == true
                failedApply(error)
            }
            // Every computed transaction result, including SERVICE_ERROR fallbacks, reaches
            // the durable journal disposition exactly once before owner/lifetime settlement.
            // A FAILED result is settled on the transaction worker, never on this Binder thread: settling loads the
            // global journal and may enter RECOVERY under the supervisor's monitor (see [FailedApplySettlementHandoff]).
            if (!settlementHandedToWorker) {
                if (result.applied) {
                    settlePublicApplyResult(lifetimeAdmission, ownerTokenId, result)
                } else {
                    failedApplySettlement.settle(onFailed = { holdAfterUnsettledFailedApply(ownerTokenId) }) {
                        settlePublicApplyResult(lifetimeAdmission, ownerTokenId, result)
                    }
                }
            }
            result.toPublicBundle(
                callerRequestId = exactRequestId,
                callerDeadlineElapsedRealtimeMs = deadlineElapsedRealtimeMs,
                truth = publicSessionTruth(),
            ).withApiVersion()
        }

        override fun cancelRequestV4(
            requestId: String?,
            deadlineElapsedRealtimeMs: Long,
            clientToken: IBinder?,
            generation: Long,
            transactionId: String?,
        ): Bundle = guarded {
            val exactRequestId = requestId.orEmpty()
            publicRequestFailure(exactRequestId, deadlineElapsedRealtimeMs)?.let {
                return@guarded it
            }
            val token = clientToken
                ?: return@guarded publicFailure(
                    "INVALID_CLIENT_TOKEN",
                    "A live Binder client token is required.",
                    exactRequestId,
                    deadlineElapsedRealtimeMs,
                )
            val exactTransactionId = transactionId.orEmpty()
            when (
                val match = publicOwners.cancelMatching(
                    ownerUid = Binder.getCallingUid(),
                    requestId = exactRequestId,
                    token = AndroidClientTokenHandle(token),
                    generation = generation,
                    transactionId = exactTransactionId,
                )
            ) {
                PublicOwnerMatch.Stale -> publicFailure(
                    "STALE_OWNER",
                    "Cancel identity did not match the exact owner/session; zero commands executed.",
                    exactRequestId,
                    deadlineElapsedRealtimeMs,
                )
                is PublicOwnerMatch.InFlight -> publicSuccess(
                    code = "CANCEL_REQUESTED",
                    message = "Matching in-flight apply was cancelled; late completion will restore exactly.",
                    callerRequestId = exactRequestId,
                    callerDeadlineElapsedRealtimeMs = deadlineElapsedRealtimeMs,
                )
                is PublicOwnerMatch.Active -> restoreOwnedSession(
                    owner = match.owner,
                    callerRequestId = exactRequestId,
                    callerDeadlineElapsedRealtimeMs = deadlineElapsedRealtimeMs,
                    cause = "REQUEST_CANCELLED",
                )
            }
        }

        override fun renewLeaseV4(
            requestId: String?,
            deadlineElapsedRealtimeMs: Long,
            clientToken: IBinder?,
            generation: Long,
            transactionId: String?,
        ): Bundle = guarded {
            val exactRequestId = requestId.orEmpty()
            publicRequestFailure(exactRequestId, deadlineElapsedRealtimeMs)?.let {
                return@guarded it
            }
            val token = clientToken
                ?: return@guarded publicFailure(
                    "INVALID_CLIENT_TOKEN",
                    "A live Binder client token is required.",
                    exactRequestId,
                    deadlineElapsedRealtimeMs,
                )
            val exactTransactionId = transactionId.orEmpty()
            val owner = publicOwners.matchActive(
                ownerUid = Binder.getCallingUid(),
                requestId = exactRequestId,
                token = AndroidClientTokenHandle(token),
                generation = generation,
                transactionId = exactTransactionId,
            ) ?: return@guarded publicFailure(
                "STALE_OWNER",
                "Renew identity did not match the exact owner/session.",
                exactRequestId,
                deadlineElapsedRealtimeMs,
            )
            if (!ownerSessionMatches(owner)) {
                return@guarded publicFailure(
                    "STALE_OWNER",
                    "Renew identity is stale against service/journal truth.",
                    exactRequestId,
                    deadlineElapsedRealtimeMs,
                )
            }
            val renewed = supervisor.renewLease(
                ownerUid = owner.ownerUid,
                ownerTokenId = owner.tokenId,
                generation = generation,
                transactionId = exactTransactionId,
                requestId = exactRequestId,
            )
            if (renewed) {
                publicSuccess(
                    code = "LEASE_RENEWED",
                    message = "Finite owner lease renewed within its immutable hard deadline.",
                    callerRequestId = exactRequestId,
                    callerDeadlineElapsedRealtimeMs = deadlineElapsedRealtimeMs,
                )
            } else {
                publicFailure(
                    "LEASE_NOT_RENEWED",
                    "Lease is expired, at its hard deadline, retrying, or no longer renewable.",
                    exactRequestId,
                    deadlineElapsedRealtimeMs,
                )
            }
        }

        override fun querySessionV4(
            requestId: String?,
            deadlineElapsedRealtimeMs: Long,
            clientToken: IBinder?,
        ): Bundle = guarded {
            val exactRequestId = requestId.orEmpty()
            publicRequestFailure(exactRequestId, deadlineElapsedRealtimeMs)?.let {
                return@guarded it
            }
            val token = clientToken
            if (token == null || !AndroidClientTokenHandle(token).isAlive()) {
                return@guarded publicFailure(
                    "INVALID_CLIENT_TOKEN",
                    "A live Binder client token is required.",
                    exactRequestId,
                    deadlineElapsedRealtimeMs,
                )
            }
            publicSuccess(
                code = "SESSION_QUERY",
                message = "Authoritative service session truth.",
                callerRequestId = exactRequestId,
                callerDeadlineElapsedRealtimeMs = deadlineElapsedRealtimeMs,
            )
        }

        override fun restoreSessionV4(
            requestId: String?,
            deadlineElapsedRealtimeMs: Long,
            clientToken: IBinder?,
            generation: Long,
            transactionId: String?,
        ): Bundle = guarded {
            val exactRequestId = requestId.orEmpty()
            publicRequestFailure(exactRequestId, deadlineElapsedRealtimeMs)?.let {
                return@guarded it
            }
            val token = clientToken
                ?: return@guarded publicFailure(
                    "INVALID_CLIENT_TOKEN",
                    "A live Binder client token is required.",
                    exactRequestId,
                    deadlineElapsedRealtimeMs,
                )
            val exactTransactionId = transactionId.orEmpty()
            val owner = publicOwners.matchActive(
                ownerUid = Binder.getCallingUid(),
                requestId = exactRequestId,
                token = AndroidClientTokenHandle(token),
                generation = generation,
                transactionId = exactTransactionId,
            ) ?: return@guarded publicFailure(
                "STALE_OWNER",
                "Restore identity did not match the exact owner/session; zero commands executed.",
                exactRequestId,
                deadlineElapsedRealtimeMs,
            )
            restoreOwnedSession(
                owner = owner,
                callerRequestId = exactRequestId,
                callerDeadlineElapsedRealtimeMs = deadlineElapsedRealtimeMs,
                cause = "EXPLICIT_OWNER_RESTORE",
            )
        }

        override fun selectSystemProfileV5(
            requestId: String?,
            deadlineElapsedRealtimeMs: Long,
            clientToken: IBinder?,
            profileId: String?,
        ): Bundle = guarded {
            val exactRequestId = requestId.orEmpty()
            publicRequestFailure(exactRequestId, deadlineElapsedRealtimeMs)?.let {
                return@guarded it
            }
            val token = clientToken
                ?: return@guarded publicFailure(
                    "INVALID_CLIENT_TOKEN",
                    "A live Binder client token is required.",
                    exactRequestId,
                    deadlineElapsedRealtimeMs,
                )
            val tokenHandle = AndroidClientTokenHandle(token)
            if (!tokenHandle.isAlive()) {
                return@guarded publicFailure(
                    "CLIENT_TOKEN_DEAD",
                    "Client token died before system-wide selection.",
                    exactRequestId,
                    deadlineElapsedRealtimeMs,
                )
            }
            val safeProfile = profileId.orEmpty().trim().lowercase()
            if (safeProfile !in SYSTEM_WIDE_PROFILE_IDS) {
                return@guarded publicFailure(
                    "UNKNOWN_PROFILE",
                    "System-wide selection requires stock, quiet, balanced, or performance.",
                    exactRequestId,
                    deadlineElapsedRealtimeMs,
                )
            }
            if (safeProfile != SystemWideProfileTransition.STOCK_PROFILE_ID) {
                publicApplyPostureFailureCode(BuildConfig.HARDWARE_WRITES_COMPILED)?.let { code ->
                    return@guarded publicFailure(
                        code,
                        "This build is read-only; the hardware write gate is disabled.",
                        exactRequestId,
                        deadlineElapsedRealtimeMs,
                    )
                }
                // A held discovered ceiling is given back BEFORE this apply can capture it as
                // stock. Deliberately inside the non-stock branch, the same place the posture gate
                // sits: selecting `stock` is a RESTORE, it captures nothing, and a restore must
                // never be blocked by a withdraw that could not be delivered.
                ceilingPrecedenceFailureCode()?.let { code ->
                    return@guarded publicFailure(
                        code,
                        CeilingPrecedenceGate.refusalMessage(code),
                        exactRequestId,
                        deadlineElapsedRealtimeMs,
                    )
                }
                cpuTransportPrecedenceRefusal()?.let { refusal ->
                    return@guarded publicFailure(
                        refusal.code,
                        refusal.message,
                        exactRequestId,
                        deadlineElapsedRealtimeMs,
                    )
                }
            }

            val result = runCatching {
                val ownerUid = Binder.getCallingUid()
                onTransactionWorker {
                    selectSystemProfileOnWorker(
                        requestId = exactRequestId,
                        deadlineElapsedRealtimeMs = deadlineElapsedRealtimeMs,
                        token = tokenHandle,
                        profileId = safeProfile,
                        ownerUid = ownerUid,
                    )
                }
            }.getOrElse { error ->
                TransactionResult(
                    success = false,
                    applied = false,
                    restored = false,
                    code = transactionWorkerFailureCode(error),
                    message = error.message ?: "System-wide profile selection failed.",
                    requestId = exactRequestId,
                    requestDeadlineElapsedRealtimeMs = deadlineElapsedRealtimeMs,
                )
            }
            maybeLogLabQualificationSelection(safeProfile, result)
            result.toPublicBundle(
                callerRequestId = exactRequestId,
                callerDeadlineElapsedRealtimeMs = deadlineElapsedRealtimeMs,
                truth = publicSessionTruth(),
            ).withApiVersion()
        }

        override fun getAdvancedCapabilitiesV6(): Bundle = guarded {
            onTransactionWorker { advancedCapabilitiesV6() }
        }

        override fun setFanControlV6(
            callerRequestId: String?,
            deadlineElapsedRealtimeMs: Long,
            clientToken: IBinder?,
            ownerRequestId: String?,
            generation: Long,
            transactionId: String?,
            mode: String?,
            curveBias: Int,
        ): Bundle = guarded {
            val exactCallerRequestId = callerRequestId.orEmpty()
            publicRequestFailure(exactCallerRequestId, deadlineElapsedRealtimeMs)?.let {
                return@guarded it
            }
            val token = clientToken
                ?: return@guarded publicFailure(
                    "INVALID_CLIENT_TOKEN",
                    "A live Binder client token is required.",
                    exactCallerRequestId,
                    deadlineElapsedRealtimeMs,
                )
            val tokenHandle = AndroidClientTokenHandle(token)
            if (!tokenHandle.isAlive()) {
                return@guarded publicFailure(
                    "CLIENT_TOKEN_DEAD",
                    "Client token died before fan control selection.",
                    exactCallerRequestId,
                    deadlineElapsedRealtimeMs,
                )
            }
            val exactOwnerRequestId = ownerRequestId.orEmpty()
            val exactTransactionId = transactionId.orEmpty()
            val requestedMode = mode.orEmpty().trim().uppercase()
                .let { runCatching { PublicFanControlMode.valueOf(it) }.getOrNull() }
                ?: return@guarded publicFailure(
                    "INVALID_FAN_MODE",
                    "Fan mode must be Auto or Manual.",
                    exactCallerRequestId,
                    deadlineElapsedRealtimeMs,
                )
            val ownerUid = Binder.getCallingUid()
            val result = try {
            onTransactionWorker {
                val owner = publicOwners.matchActive(
                    ownerUid = ownerUid,
                    requestId = exactOwnerRequestId,
                    token = tokenHandle,
                    generation = generation,
                    transactionId = exactTransactionId,
                )
                val active = supervisor.activeSession()
                if (owner == null || active == null ||
                    active.generation != generation ||
                    active.transactionId != exactTransactionId ||
                    active.requestId != exactOwnerRequestId
                ) {
                    return@onTransactionWorker publicFailure(
                        "STALE_OWNER",
                        "Fan control identity did not match the active system profile.",
                        exactCallerRequestId,
                        deadlineElapsedRealtimeMs,
                    )
                }
                if (!validFanControlSelection(requestedMode, curveBias)) {
                    return@onTransactionWorker publicFailure(
                        "INVALID_FAN_CURVE",
                        "Fan curve bias is outside the supported range or step.",
                        exactCallerRequestId,
                        deadlineElapsedRealtimeMs,
                    )
                }
                val activeContract = active.appliedTargetContract
                if (activeContract == null ||
                    // publicFailure never restores/rolls back -- reports a clean failure without
                    // touching the active profile.
                    (activeContract.fanMode == TuningPlanner.FAN_SMART && !manualFanControlAvailable(active))
                ) {
                    // An earlier draft hoisted the availability call into a local so this line could
                    // print it. That broke `user fan-control command checks manual availability
                    // before declaring unavailable`, which pins the literal expression AND its
                    // position before any transition attempt -- a real ordering invariant, not a
                    // formatting preference. The expression is left exactly as it was: inside this
                    // branch, with a non-null contract carrying Smart, availability was false by
                    // construction, so naming the branch is as precise as re-reading it would be
                    // and costs no second live-telemetry probe.
                    Log.w(
                        FAN_CONTROL_TAG,
                        "decline=FAN_CONTROL_UNAVAILABLE stage=availability " +
                            "contractFan=${activeContract?.fanMode} " +
                            "reason=${if (activeContract == null) "noAppliedContract" else "manualUnavailable"}",
                    )
                    // A null activeContract used to fall through this gate (the old condition
                    // short-circuited on `activeContract != null` and never returned) straight into
                    // fanControlSelections.current(active) below, which requires a resolved
                    // contract (checkNotNull, AdvancedFanControl.kt) and threw
                    // IllegalStateException("Required value was null.") uncaught across the Binder
                    // -- the active-session-but-unresolved-contract window is the same one
                    // advancedCapabilitiesV6 hit (see the comment there). Reported the same way the
                    // sibling FAN_SMART-not-yet-manual-available case already was: an honest,
                    // non-mutating rejection instead of a crash.
                    return@onTransactionWorker publicFailure(
                        "FAN_CONTROL_UNAVAILABLE",
                        "Mimir fan control is not qualified for the active profile.",
                        exactCallerRequestId,
                        deadlineElapsedRealtimeMs,
                    )
                }
                val prior = fanControlSelections.current(active)
                val hardwareMode = active.appliedTargetContract?.fanMode
                // This was `hardwareMode == FAN_SMART` alone, and
                // `transitionFanControlModeOnWorker` never consults `requestedMode` before calling
                // `applyProfile(FanIntent.MANUAL_OVERRIDE)` -- it only reads it afterwards, to write
                // the selection record. So a player pressing **AUTO** against a vendor-SMART
                // contract was driven INTO Mimir-owned fan mode 6: contradicting Auto semantics and this row's own copy telling
                // them the device's controller stays in charge.
                //
                // Previously unreachable: the manifest gate refused the path on every
                // device, so nothing reached it. Retiring that gate made it live, which is why a
                // pre-existing wrong-direction branch became a shipping defect in this landing.
                //
                // AUTO against a SMART contract needs no transition at all -- vendor SMART IS what
                // Auto means under R2/R3 -- so it now falls through to the selection update below.
                // Two transitions, not one. ENTER is Smart -> Mimir duty ownership; EXIT is the way
                // back, added 2026-08-23 after review found Manual was a one-way door: with a
                // duty-owned contract an AUTO request only rewrote the durable preference, no
                // hardware write ever returned `fan_mode` to Smart, and `strategy()` maps AUTO and
                // MANUAL to the same AutoCurve -- so Mimir kept owning duty while the row told the
                // player the device's own controller was back in charge. Auto and Manual need both entry and exit transitions.
                //
                // The exit predicate MIRRORS `autoExitAvailable` in advancedCapabilitiesV6 exactly
                // -- contract is FAN_MANUAL and the transition journal is bound. A looser gate here
                // would offer an exit the daemon then refuses, which is F1's defect class pointing
                // the other way.
                val entersDutyOwnership = hardwareMode == TuningPlanner.FAN_SMART &&
                    requestedMode == PublicFanControlMode.MANUAL
                val exitsToVendorAuto = hardwareMode == TuningPlanner.FAN_MANUAL &&
                    requestedMode == PublicFanControlMode.AUTO &&
                    fanTransitionJournalBound(active)
                val requiresTransition = entersDutyOwnership || exitsToVendorAuto
                Log.i(
                    FAN_CONTROL_TAG,
                    "request mode=$requestedMode bias=$curveBias contractFan=$hardwareMode " +
                        "priorMode=${prior?.mode} priorBias=${prior?.curveBias} " +
                        "requiresTransition=$requiresTransition",
                )
                if (requiresTransition) {
                    val fanIntent = if (exitsToVendorAuto) {
                        TuningPlanner.FanIntent.PROFILE_AUTO
                    } else {
                        TuningPlanner.FanIntent.MANUAL_OVERRIDE
                    }
                    val response = transitionFanControlModeOnWorker(
                        ownerUid = ownerUid,
                        token = tokenHandle,
                        owner = owner,
                        active = active,
                        requestedMode = requestedMode,
                        curveBias = curveBias,
                        callerRequestId = exactCallerRequestId,
                        callerDeadlineElapsedRealtimeMs = deadlineElapsedRealtimeMs,
                        fanIntent = fanIntent,
                    )
                    // Mirrors renewMimirFanOwnershipOnWorker's own
                    // classification exactly, for the one path that function's own dispatch never
                    // reaches -- a player's own MANUAL_OVERRIDE request that degrades began no daemon renewal
                    // at all before this; only the frontend retried, never the daemon (I6), and
                    // I1's "renewal PENDING while REFUSED" was not honoured on this toggle path.
                    // Never for the exit-to-Auto direction: PROFILE_AUTO settles as
                    // ADOPT_VENDOR_SMART/ADOPT_ALREADY_OWNED by design, not a degrade --
                    // fanOwnershipSettleOutcome is only ever meaningful for a MANUAL_OVERRIDE
                    // attempt. Never changes the response returned to the client below.
                    if (fanIntent == TuningPlanner.FanIntent.MANUAL_OVERRIDE) {
                        val settled = supervisor.activeSession()
                        val outcome = fanOwnershipSettleOutcome(
                            transitionSucceeded = response.getBoolean("success"),
                            settledFanMode = settled?.appliedTargetContract?.fanMode,
                            settledScope = settled?.scope,
                            settledProfileId = settled?.appliedTargetContract?.profileId,
                            stagedProfileId = active.appliedTargetContract?.profileId,
                            settledGeneration = settled?.generation,
                            stagedGeneration = active.generation,
                        )
                        when (outcome) {
                            FanOwnershipSettleOutcome.SETTLED_MANUAL -> fanOwnershipRenewal = null
                            FanOwnershipSettleOutcome.DEGRADED_VENDOR_SMART -> settled?.let { s ->
                                val code = response.getString("code").orEmpty()
                                    .ifBlank { "FAN_PROFILE_HANDOFF_FAILED" }
                                // Bind to the durable settled generation/transactionId.
                                fanOwnershipRenewal = FanOwnershipRenewalPolicy.begin(
                                    generation = s.generation,
                                    transactionId = s.transactionId,
                                    code = code,
                                    nowElapsedMs = SystemClock.elapsedRealtime(),
                                )
                                FanOwnershipObservability.onSettleDeferred(
                                    code,
                                    "Mimir fan ownership was not entered on this apply.",
                                )
                            }
                            // I5: this is a player's own request, not a renewal attempt -- it
                            // never restores or re-applies anything itself. Leave the renewal
                            // state exactly as it was; the ordinary session-end machinery already
                            // owns what happens next (same reasoning renewMimirFanOwnershipOnWorker's
                            // own SESSION_LOST branch documents).
                            FanOwnershipSettleOutcome.SESSION_LOST -> Unit
                        }
                    }
                    return@onTransactionWorker response
                }
                val selected = fanControlSelections.update(active, requestedMode, curveBias)
                    ?: return@onTransactionWorker publicFailure(
                        "INVALID_FAN_CURVE",
                        "Fan curve bias is outside the supported range or step.",
                        exactCallerRequestId,
                        deadlineElapsedRealtimeMs,
                    )
                publicSuccess(
                    code = if (selected == prior) "FAN_CONTROL_UNCHANGED" else "FAN_CONTROL_UPDATED",
                    message = "Authoritative fan control selection.",
                    callerRequestId = exactCallerRequestId,
                    callerDeadlineElapsedRealtimeMs = deadlineElapsedRealtimeMs,
                ).putFanControlSelection(selected)
            }
            } catch (busy: TransactionWorkerBusyException) {
                publicFailure(
                    SERVICE_BUSY_CODE,
                    busy.message ?: "The transaction worker is busy.",
                    exactCallerRequestId,
                    deadlineElapsedRealtimeMs,
                )
            }
            result
        }

        override fun getFpsLimiterCapabilityV7(packageName: String?): Bundle = guarded {
            val safePackage = packageName.orEmpty().trim()
            if (!PACKAGE_PATTERN.matches(safePackage)) {
                return@guarded fpsCapabilityBundle(API_VERSION, safePackage, null)
            }
            onTransactionWorker {
                fpsCapabilityBundle(API_VERSION, safePackage, fpsLimiterSession.capability(safePackage))
            }
        }

        override fun setFpsLimitV7(
            callerRequestId: String?,
            deadlineElapsedRealtimeMs: Long,
            clientToken: IBinder?,
            packageName: String?,
            fps: Int,
        ): Bundle = guarded {
            val exactRequestId = callerRequestId.orEmpty()
            publicRequestFailure(exactRequestId, deadlineElapsedRealtimeMs)?.let {
                return@guarded it
            }
            val binderToken = clientToken ?: return@guarded fpsApplyBundle(
                API_VERSION,
                exactRequestId,
                deadlineElapsedRealtimeMs,
                FpsLimiterApplyResult.Rejected("FPS_CLIENT_TOKEN_DEAD"),
            )
            val safePackage = packageName.orEmpty().trim()
            if (!PACKAGE_PATTERN.matches(safePackage)) {
                return@guarded fpsApplyBundle(
                    API_VERSION,
                    exactRequestId,
                    deadlineElapsedRealtimeMs,
                    FpsLimiterApplyResult.Rejected("FPS_LIMIT_UNAVAILABLE"),
                )
            }
            val token = AndroidClientTokenHandle(binderToken)
            if (!token.isAlive()) {
                return@guarded fpsApplyBundle(
                    API_VERSION,
                    exactRequestId,
                    deadlineElapsedRealtimeMs,
                    FpsLimiterApplyResult.Rejected("FPS_CLIENT_TOKEN_DEAD"),
                )
            }
            val ownerUid = Binder.getCallingUid()
            cancelFpsPendingAdmissionExpiry()
            val result: FpsLimiterApplyResult = try {
            onTransactionWorker {
                fpsLimiterSession.apply(
                    request = FpsLimiterRequest(
                        requestId = exactRequestId,
                        ownerUid = ownerUid,
                        packageName = safePackage,
                        targetFps = fps,
                        deadlineElapsedRealtimeMs = deadlineElapsedRealtimeMs,
                    ),
                    token = token,
                )
            }
            } catch (_: TransactionWorkerBusyException) {
                FpsLimiterApplyResult.Rejected(SERVICE_BUSY_CODE)
            }
            fpsApplyBundle(API_VERSION, exactRequestId, deadlineElapsedRealtimeMs, result)
        }

        override fun queryFpsLimiterV7(packageName: String?): Bundle = guarded {
            val safePackage = packageName.orEmpty().trim()
            if (!PACKAGE_PATTERN.matches(safePackage)) {
                return@guarded fpsQueryBundle(
                    apiVersion = API_VERSION,
                    requestedPackageName = safePackage,
                    terminalTruth = FpsLimiterTerminalTruth(true, true, true),
                    record = null,
                ).apply {
                    putBoolean("success", false)
                    putString("code", "INVALID_PACKAGE")
                    putString("message", "A concrete Android game package is required.")
                }
            }
            onTransactionWorker {
                fpsQueryBundle(
                    apiVersion = API_VERSION,
                    requestedPackageName = safePackage,
                    terminalTruth = fpsLimiterSession.terminalTruth(),
                    record = fpsLimiterSession.activeRecord(),
                )
            }
        }

        override fun restoreFpsLimitV7(
            callerRequestId: String?,
            deadlineElapsedRealtimeMs: Long,
            clientToken: IBinder?,
            generation: Long,
            transactionId: String?,
        ): Bundle = guarded {
            val exactRequestId = callerRequestId.orEmpty()
            publicRequestFailure(exactRequestId, deadlineElapsedRealtimeMs)?.let {
                return@guarded it
            }
            val binderToken = clientToken ?: return@guarded fpsRestoreBundle(
                API_VERSION,
                exactRequestId,
                deadlineElapsedRealtimeMs,
                FpsLimiterRestoreResult.Retained("FPS_STALE_OWNER"),
            )
            val token = AndroidClientTokenHandle(binderToken)
            val ownerUid = Binder.getCallingUid()
            val result: FpsLimiterRestoreResult = try {
            onTransactionWorker {
                fpsLimiterSession.restore(
                    ownerUid = ownerUid,
                    requestId = exactRequestId,
                    token = token,
                    generation = generation,
                    transactionId = transactionId.orEmpty(),
                )
            }
            } catch (_: TransactionWorkerBusyException) {
                FpsLimiterRestoreResult.Retained(SERVICE_BUSY_CODE)
            }
            fpsRestoreBundle(API_VERSION, exactRequestId, deadlineElapsedRealtimeMs, result)
        }

        /**
         * RETIRED. This was the B02 privileged display-rate write (2026-08-08); Mimir removed
         * display-rate switching on 2026-09-15 and nothing sends it, so the daemon no longer performs it.
         * The declaration stays in the AIDL so every later transaction code keeps its number and an older client
         * still gets an answer; the answer is always [RetiredDisplayRateWrite.refusal]. It never writes, never
         * reaches the privileged transport, and never reads the key or the value. The Tweaker has no read-only
         * display-rate query to keep: the supported-Hz read existed only to validate this write and went with it.
         */
        override fun setDisplayRateSettingV8(key: String?, value: String?): Bundle = guarded {
            Log.w(TAG, "setDisplayRateSettingV8 refused: ${RetiredDisplayRateWrite.CODE}; nothing was written")
            RetiredDisplayRateWrite.refusal(API_VERSION)
        }

        /**
         * Remember what the discovered tuner captured, so a cap outlives the app that set it.
         *
         * **Gated by `guarded` and by nothing else**: no qualification manifest, no journal, no
         * Guardian, no lease, no service lifetime. It does not even need `HARDWARE_WRITES_COMPILED`,
         * because it performs no hardware mutation at all — it writes a file. Reachable in every
         * posture on purpose: capability is DISCOVERED, never declared, and a device that can tune
         * must be able to remember that it is tuning. (It was written beside the v8 display-rate
         * write, which this daemon no longer performs: [setDisplayRateSettingV8] is retired and
         * always refuses.)
         */
        override fun recordDiscoveredSessionV9(
            bootIdentity: String?,
            cpuStockRow: String?,
            cpuRequestedRow: String?,
            gpuStockMinLevel: Int,
            gpuStockMaxLevel: Int,
            gpuRequestedMinLevel: Int,
            gpuRequestedMaxLevel: Int,
        ): Bundle = guarded {
            val record = DiscoveredAxisRecord(
                bootIdentity = bootIdentity.orEmpty().trim(),
                cpuStockRow = cpuStockRow.orEmpty().trim(),
                cpuRequestedRow = cpuRequestedRow.orEmpty().trim(),
                gpuStockMinLevel = gpuStockMinLevel,
                gpuStockMaxLevel = gpuStockMaxLevel,
                gpuRequestedMinLevel = gpuRequestedMinLevel,
                gpuRequestedMaxLevel = gpuRequestedMaxLevel,
            )
            val verdict = try {
                discoveredAxisRecords.write(record)
            } catch (error: Throwable) {
                DiscoveredAxisAdmissionResult.Refused("STORE_UNAVAILABLE", error.javaClass.simpleName)
            }
            Bundle().apply {
                putInt("apiVersion", API_VERSION)
                putBoolean("success", verdict is DiscoveredAxisAdmissionResult.Admitted)
                putString("status", if (verdict is DiscoveredAxisAdmissionResult.Admitted) "ADMITTED" else "REFUSED")
                if (verdict is DiscoveredAxisAdmissionResult.Refused) {
                    putString("code", verdict.code)
                    putString("detail", verdict.detail)
                }
            }
        }

        override fun queryDiscoveredSessionV9(): Bundle = guarded {
            val record = try {
                discoveredAxisRecords.read()
            } catch (_: Throwable) {
                null
            }
            Bundle().apply {
                putInt("apiVersion", API_VERSION)
                putBoolean("success", true)
                putBoolean("held", record != null)
                if (record != null) {
                    putString("bootIdentity", record.bootIdentity)
                    putString("cpuStockRow", record.cpuStockRow)
                    putString("cpuRequestedRow", record.cpuRequestedRow)
                    putInt("gpuStockMinLevel", record.gpuStockMinLevel)
                    putInt("gpuStockMaxLevel", record.gpuStockMaxLevel)
                    putInt("gpuRequestedMinLevel", record.gpuRequestedMinLevel)
                    putInt("gpuRequestedMaxLevel", record.gpuRequestedMaxLevel)
                }
            }
        }

        override fun clearDiscoveredSessionV9(): Bundle = guarded {
            val cleared = try {
                discoveredAxisRecords.clear()
            } catch (_: Throwable) {
                false
            }
            Bundle().apply {
                putInt("apiVersion", API_VERSION)
                putBoolean("success", cleared)
            }
        }

        /**
         * API v10: request a ceiling on one discovered policy, written by THIS process.
         *
         * `guarded` and then [DiscoveredCeilingActuator]'s own ordered admission — posture, profile
         * precedence, discovery, snap, live-floor clamp, capture, write, verify — with the first
         * failure winning. Nothing the caller supplies reaches a command: [axis] and [policyId]
         * select a policy this process enumerated, and [ceilingKhz] is snapped onto that policy's
         * own published table before anything is written.
         */
        override fun applyDiscoveredCeilingV10(axis: Int, policyId: Int, ceilingKhz: Int): Bundle = guarded {
            discoveredCeilingBundle(ceilingOnTransactionWorker { discoveredCeiling.apply(axis, policyId, ceilingKhz) })
        }

        override fun withdrawDiscoveredCeilingV10(): Bundle = guarded {
            discoveredCeilingBundle(ceilingOnTransactionWorker { discoveredCeiling.withdraw() })
        }

        /**
         * A query reads and reports; it never writes hardware (see [DiscoveredCeilingActuator.query]). A query that
         * could not be answered is [CeilingOutcome.Refused], which the client reads as "unavailable", never as NotHeld:
         * "I could not ask" is not "no ceiling is held", and acting on the second at cold start widens a held cap.
         */
        override fun queryDiscoveredCeilingV10(): Bundle = guarded {
            discoveredCeilingBundle(ceilingOnTransactionWorker { discoveredCeiling.query() })
        }

        /**
         * API v11: make [packageName]'s `requestAudioFocus` fail so other music keeps playing.
         *
         * `guarded`, then the edge check, then [GameAudioFocusController]'s ordered admission. The caller
         * supplies one package and one Binder; nothing else it sends reaches a command. Reachable in every
         * posture, like [setDisplayRateSettingV8]: an app-op is a per-app preference, not clock or thermal
         * state, and never consults qualification, certification or the Guardian.
         */
        override fun holdGameAudioFocusV11(clientToken: IBinder?, packageName: String?): Bundle = guardedMimirOnly {
            val safePackage = packageName.orEmpty().trim()
            val result = when {
                clientToken == null -> GameAudioFocusHoldResult.Refused(
                    TweakerGameAudioFocusContract.REFUSAL_CLIENT_TOKEN_DEAD,
                    "no client token",
                )
                !PACKAGE_PATTERN.matches(safePackage) -> GameAudioFocusHoldResult.Refused(
                    TweakerGameAudioFocusContract.REFUSAL_INVALID_PACKAGE,
                    "not a package name",
                )
                else -> try {
                    onTransactionWorker {
                        gameAudioFocusSession.hold(AndroidClientTokenHandle(clientToken), safePackage)
                    }
                } catch (error: Throwable) {
                    if (error is InterruptedException) Thread.currentThread().interrupt()
                    val cause = (error as? java.util.concurrent.ExecutionException)?.cause ?: error
                    GameAudioFocusHoldResult.Refused(
                        code = transactionWorkerFailureCode(
                            cause,
                            fallback = TweakerGameAudioFocusContract.REFUSAL_HOST_UNAVAILABLE,
                        ),
                        detail = cause.javaClass.simpleName,
                        // A worker that had STARTED may have journalled and written: its effect is unconfirmed.
                        releaseDebt = (cause as? TransactionWorkerBusyException)?.started == true,
                    )
                }
            }
            gameAudioFocusHoldBundle(API_VERSION, safePackage, result)
        }

        override fun releaseGameAudioFocusV11(): Bundle = guardedMimirOnly {
            val result = try {
                onTransactionWorker { gameAudioFocusSession.release() }
            } catch (error: Throwable) {
                if (error is InterruptedException) Thread.currentThread().interrupt()
                val cause = (error as? java.util.concurrent.ExecutionException)?.cause ?: error
                GameAudioFocusReleaseResult.Retained(
                    null,
                    transactionWorkerFailureCode(cause, fallback = "HOST_UNAVAILABLE:${cause.javaClass.simpleName}"),
                )
            }
            gameAudioFocusReleaseBundle(API_VERSION, result)
        }

        /**
         * Reads the app-op back (bounded) and reports what it says. A worker that failed or did not answer is
         * UNAVAILABLE, never NOT_HELD: "I could not ask" is not "nothing is held".
         */
        override fun queryGameAudioFocusV11(): Bundle = guardedMimirOnly {
            val result = try {
                onTransactionWorker { gameAudioFocusSession.query() }
            } catch (error: Throwable) {
                if (error is InterruptedException) Thread.currentThread().interrupt()
                val cause = (error as? java.util.concurrent.ExecutionException)?.cause ?: error
                GameAudioFocusQueryResult.Unavailable(
                    transactionWorkerFailureCode(cause, fallback = TweakerGameAudioFocusContract.REFUSAL_HOST_UNAVAILABLE),
                )
            }
            gameAudioFocusQueryBundle(API_VERSION, result)
        }
    }

    /**
     * Every v10 ceiling operation runs on the one transaction worker, like every profile mutation and the keeper's own
     * tick: serialised with them, bounded by the Binder wait, and never holding a Binder thread under the actuator's lock
     * while a privileged write is stuck. A worker that did not answer in time is [SERVICE_BUSY_CODE]; whether the
     * operation ran is then unknown, and the detail says so. Any other failure is a refusal that says UNCONFIRMED.
     */
    private fun ceilingOnTransactionWorker(block: () -> CeilingOutcome): CeilingOutcome =
        try {
            onTransactionWorker(block)
        } catch (error: Throwable) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            CeilingWorkerFailure.refusal(error)
        }

    /** One reply shape for all three v10 methods; every key comes from the contract verbatim. */
    private fun discoveredCeilingBundle(outcome: CeilingOutcome): Bundle = Bundle().apply {
        putInt(TweakerDiscoveredCeilingContract.KEY_API_VERSION, API_VERSION)
        putString(TweakerDiscoveredCeilingContract.KEY_OUTCOME, outcome.wireOutcome())
        when (outcome) {
            is CeilingOutcome.Applied -> {
                putInt(TweakerDiscoveredCeilingContract.KEY_AXIS, outcome.axis)
                putInt(TweakerDiscoveredCeilingContract.KEY_POLICY_ID, outcome.policyId)
                putInt(TweakerDiscoveredCeilingContract.KEY_REQUESTED_KHZ, outcome.requestedKhz)
                putInt(TweakerDiscoveredCeilingContract.KEY_ADMITTED_KHZ, outcome.admittedKhz)
                putInt(TweakerDiscoveredCeilingContract.KEY_IN_FORCE_KHZ, outcome.inForceKhz)
                putBoolean(TweakerDiscoveredCeilingContract.KEY_HELD, true)
                putCeilingRecord(outcome.record)
            }
            is CeilingOutcome.Held -> {
                putBoolean(TweakerDiscoveredCeilingContract.KEY_HELD, true)
                // held is about the RECORD, inForce about the NODE. On a node with a second writer
                // they come apart, and the surface has to be able to say which one it has.
                putBoolean(TweakerDiscoveredCeilingContract.KEY_IN_FORCE, outcome.inForce)
                putCeilingRecord(outcome.record)
            }
            is CeilingOutcome.Withdrawn -> {
                putBoolean(TweakerDiscoveredCeilingContract.KEY_HELD, false)
                putInt(TweakerDiscoveredCeilingContract.KEY_RESTORED_CPUS, outcome.restoredCpus)
                putInt(TweakerDiscoveredCeilingContract.KEY_LEFT_ALONE_CPUS, outcome.leftAloneCpus)
                putBoolean(TweakerDiscoveredCeilingContract.KEY_GPU_RESTORED, outcome.gpuRestored)
            }
            CeilingOutcome.NothingHeld, CeilingOutcome.NotHeld ->
                putBoolean(TweakerDiscoveredCeilingContract.KEY_HELD, false)
            is CeilingOutcome.Refused -> {
                putString(TweakerDiscoveredCeilingContract.KEY_REFUSAL_CODE, outcome.code)
                putString(TweakerDiscoveredCeilingContract.KEY_REFUSAL_DETAIL, outcome.detail)
            }
        }
    }

    private fun Bundle.putCeilingRecord(record: DiscoveredAxisRecord) {
        putString(TweakerDiscoveredCeilingContract.KEY_BOOT_IDENTITY, record.bootIdentity)
        putString(TweakerDiscoveredCeilingContract.KEY_CPU_STOCK_ROW, record.cpuStockRow)
        putString(TweakerDiscoveredCeilingContract.KEY_CPU_REQUESTED_ROW, record.cpuRequestedRow)
        putInt(TweakerDiscoveredCeilingContract.KEY_GPU_STOCK_MIN_LEVEL, record.gpuStockMinLevel)
        putInt(TweakerDiscoveredCeilingContract.KEY_GPU_STOCK_MAX_LEVEL, record.gpuStockMaxLevel)
        putInt(TweakerDiscoveredCeilingContract.KEY_GPU_REQUESTED_MIN_LEVEL, record.gpuRequestedMinLevel)
        putInt(TweakerDiscoveredCeilingContract.KEY_GPU_REQUESTED_MAX_LEVEL, record.gpuRequestedMaxLevel)
    }

    override fun onCreate() {
        super.onCreate()
        // Explicit device-protected context for journal/recovery — never credential prefs here.
        deviceProtectedContext = createDeviceProtectedStorageContext()
        pServer = PServerClient()
        val reader = SystemReader(PServerProtectedNodeAccess(pServer))
        // Held as a field only so the v10 discovered-ceiling actuator reads the nodes it writes
        // through the SAME bounded reader discovery uses. RP5 policy0 exposes some cpufreq nodes as
        // system-only, so a direct app-process read can fail after a confirmed privileged write.
        systemReader = reader
        // TWO frame ports, deliberately not one. The SurfaceFlinger port stays the calibration
        // path: its samples become `frameTelemetryAttested` inside signed qualification evidence,
        // and that evidence means "this app layer sustained this rate", which the display-wide DRM
        // node cannot say. RC-47's policy was about what the performance CARD shows, not
        // about what a signed manifest attests, so only the live sampler moves. Calibration runs
        // per session rather than per tick, so leaving it here costs the tick nothing.
        val surfaceFlingerFrameTelemetry = PServerFrameTelemetryPort(pServer, pServer::isAvailable)
        probe = HardwareProbe(deviceProtectedContext, reader, pServer)
        registerDisplayTopologyInvalidation()
        fpsLimiterJournal = createAuthenticatedFpsLimiterJournal(deviceProtectedContext)
        fpsLimiterSession = FpsLimiterServiceSession(
            controller = FpsLimiterController(
                journal = fpsLimiterJournal,
                packageIdentity = AndroidGameModeFpsPackageIdentityPort(this),
                control = PServerGameModeFpsControlPort(pServer),
                refreshRate = AndroidActiveRefreshRatePort(this),
                userUnlocked = ::isUserUnlocked,
                bootIdentity = {
                    reader.read(HardwareProbe.BOOT_ID_PATH)?.trim()?.takeIf(String::isNotEmpty)
                },
                elapsedRealtimeMs = SystemClock::elapsedRealtime,
            ),
            journal = fpsLimiterJournal,
            elapsedRealtimeMs = SystemClock::elapsedRealtime,
            executeAsync = ::executeTransactionWork,
            expiryScheduler = FpsLimiterExpiryScheduler { delayMs, action ->
                val scheduler = lifetimeScheduler ?: Executors.newSingleThreadScheduledExecutor { runnable ->
                    Thread(runnable, "mimir-tweaker-started-lifetime").apply { isDaemon = true }
                }.also { lifetimeScheduler = it }
                val future = scheduler.schedule(
                    { if (!destroyed.get()) action() },
                    delayMs,
                    TimeUnit.MILLISECONDS,
                )
                FpsLimiterExpiryHandle { future.cancel(false) }
            },
            onStateChanged = ::requestTerminalSettlement,
        )
        fanDutyMutationPort = PServerFanDutyMutationPort(
            pServer = pServer,
            reader = reader,
            capabilities = { activeFanCapabilities ?: capabilities() },
            fanModeRaw = probe::fanModeRaw,
        )
        productQualificationResolver = BuildInjectedProductQualificationResolver(deviceProtectedContext)
        journalSnapshots = SnapshotTransactionJournal(createAuthenticatedTransactionJournal(deviceProtectedContext))
        journal = journalSnapshots
        attestor = ForegroundWorkloadAttestor(deviceProtectedContext)
        livePerformanceTelemetry = LivePerformanceTelemetrySampler(
            foregroundPackage = attestor::currentPackage,
            // Keep SurfaceFlinger/DRM ports for explicit qualification only. Periodic live telemetry
            // must never fall back to a display-wide rate that can masquerade as the game FPS.
            frameTelemetry = LiveGameFrameTelemetryUnavailable,
        )
        liveTelemetryCache = LiveTelemetrySnapshotCache(
            compose = {
                // `probe.probe()`, NOT `capabilities()`. Measured on the reference Nova 2026-08-30:
                // one compose cost ~244 ms of daemon CPU (653/649 jiffies per 120 s with Mimir
                // alive against 5 with it force-stopped, over 27/26 composes) -- 5.4% of a core
                // burned continuously at the hidden 5 s cadence -- a measured stance. (An oft-quoted
                // "~24% at 1 Hz" is arithmetic for the card-wanted cadence and the device was never
                // observed in it; it is an extrapolation, not a measurement.)
                // Telemetry needs to remain reliable without excessive battery or performance cost.
                //
                // `capabilities()` layers qualification and posture truth on top of the probe:
                // `productQualificationResolver.resolve` (signed-manifest base64 + payload decode),
                // `HardwareFingerprint.create` (SHA-256 over the whole capability set),
                // `UsageAccessProbe.granted` (an AppOps query) and a calibration-store read. The
                // telemetry path reads NONE of it. Verified exhaustively rather than assumed:
                // `telemetry()`, `lightTelemetry()` and `thermalWitnesses()` between them touch
                // exactly `cpuCeilingPath`, `cpuPolicies`, `manufacturer`, `model` and `socModel`,
                // and `capabilities()` assigns none of those five -- it only ever adds fields.
                // So this is a strict removal of dead work, not a cheaper substitute for live truth.
                //
                // Deliberately NOT the other two levers: the overlay's visible 1 Hz beat and
                // `LiveTelemetrySnapshotCache.FRESH_FOR_MS` are both documented timing contracts. Make the
                // sample cheaper while preserving both intervals.
                val capabilities = probe.probe()
                val telemetry = probe.telemetry(capabilities)
                val live = livePerformanceTelemetry.sample()
                maybeLogLabQualificationSample(telemetry, live)
                ComposedLiveTelemetry(telemetry = telemetry, live = live)
            },
        )
        supervisorPolicy = SupervisorPolicy()
        ownerHardLeasePolicy = createOwnerHardLeasePolicy()
        val certifiedLookup: (TuningPlan, HardwareCapabilities) -> Boolean = ::planAdmittedForApply
        val state = AndroidHardwareStatePort(probe, ::capabilities)
        val directGuardianTruth = if (BuildConfig.HARDWARE_WRITES_COMPILED) {
            val authority = DirectBootKeyAuthority(deviceProtectedContext)
            when (val inspected = authority.inspect()) {
                DirectBootKeyAuthorityTruth.Fresh -> authority.provisionFresh()
                else -> inspected
            }
        } else {
            DirectBootKeyAuthorityTruth.Fresh
        }
        // **The one branch that can refuse EVERY apply resolved here, silently.** When this is not
        // `Available` the envelope port is a null stub, and `TransactionCoordinator` then returns
        // GUARDIAN_BINDING_UNAVAILABLE on every press for the life of the process -- with no log at
        // the branch and no log of this truth, so the device could not say which of eight walls it
        // had hit. a refusal that cannot name itself costs a whole session.
        //
        // Always-on rather than DEBUG-gated, one line per process start, in every posture: this is the
        // line that turns "profile rejected" from a mystery into a sentence.
        Log.w(
            "MimirTweakerGuardian",
            "truth=" + when (directGuardianTruth) {
                is DirectBootKeyAuthorityTruth.Available -> "Available"
                DirectBootKeyAuthorityTruth.Fresh -> "Fresh"
                is DirectBootKeyAuthorityTruth.Corrupt -> "Corrupt(${directGuardianTruth.reason})"
                is DirectBootKeyAuthorityTruth.Unavailable -> "Unavailable(${directGuardianTruth.reason})"
                // Distinct from Corrupt on purpose -- a re-signed install (a routine
                // install path) is not corruption, and this is the exact line an operator would
                // otherwise read "Corrupt" from with no way to tell the two apart.
                is DirectBootKeyAuthorityTruth.CertificateChanged -> "CertificateChanged(${directGuardianTruth.reason})"
            },
        )
        val availableGuardian = directGuardianTruth as? DirectBootKeyAuthorityTruth.Available
        val armStore = availableGuardian?.let {
            FileRestoreGuardianArmStore(
                it,
                File(deviceProtectedContext.filesDir, GuardianProcessPaths.ARM_DIRECTORY).absoluteFile,
            )
        }
        val terminalStore = GuardianTerminalReceiptStore(
            File(deviceProtectedContext.filesDir, GuardianProcessPaths.ROOT_DIRECTORY).absoluteFile,
        )
        val guardian = if (availableGuardian != null && armStore != null) {
            RemoteGuardianProcessAuthority(
                context = this,
                available = availableGuardian,
                armStore = armStore,
                terminalStore = terminalStore,
                // The lambda
                // `publishHeartbeat` calls -- a single `denialReason` read, one state transition.
                // F1 (fan fence, 2026-10-14): no timestamp is threaded through any more. The
                // `journal.load()` below (which shares its lock with the tick's own writes and can
                // stall for hundreds of ms under load) runs first; the gate then samples its own
                // CPU-awake clock at the comparison, so a tick that landed during this load is
                // younger than the sample -- never the "HEALTHY_STALE age=-779" that asked the
                // guardian to restore a healthy session on the Odin.
                heartbeatDenialReason = { envelope, liveFanMode ->
                    val load = journal.load()
                    val record = (load as? JournalLoadResult.Loaded)?.record
                    val truth = record?.let {
                        ManualFanMonitorLiveness.JournalTruth(
                            generation = it.generation,
                            transactionId = it.transactionId,
                            phase = it.phase,
                        )
                    }
                    val reason = manualFanMonitorLiveness.denialReason(
                        envelope = envelope,
                        truth = truth,
                        liveFanMode = liveFanMode,
                    )
                    if (BuildConfig.DIAGNOSTICS_COMPILED && reason != null) {
                        Log.e(
                            TAG,
                            "FAN_HEARTBEAT_DENIED load=${load.javaClass.simpleName} " +
                                manualFanMonitorLiveness.diagnosticSummary(
                                    envelope,
                                    SystemClock.uptimeMillis(),
                                    truth,
                                ),
                        )
                    }
                    reason
                },
                // Phase B (§3 B3, L3): a Settings read, safe off the transaction thread --
                // RemoteGuardianProcessAuthority.publishHeartbeat samples this exactly once per
                // heartbeat and threads the SAME value into whichever gate lambda above runs.
                // LOW (review): guarded with runCatching so a throwing Settings read cannot kill
                // this periodic task -- a thrown exception here would otherwise propagate out of
                // the lambda and stop the heartbeat loop entirely, which is a strictly worse
                // outcome than the null this already falls through to on any other failure shape.
                liveFanMode = { runCatching { probe.fanModeRaw()?.toIntOrNull() }.getOrNull() },
                onEnvelopeReady = {
                    manualFanMonitorLiveness.disarm()
                    // F2: a new epoch; a hand-back pending for the old one must not settle the new.
                    guardianHandback.clear()
                    // P3-6: a re-applied profile ends the last restore incident -- the companion must
                    // not announce it again, on a cold start or otherwise.
                    GuardianRestoreObservability.clearLastRestore()
                },
                onSessionRetired = ::onGuardianSessionRetired,
            )
        } else {
            null
        }
        remoteGuardianAuthority = guardian
        guardianTerminalReceipts = terminalStore
        val directCommands = PServerHardwareCommandPort(
            pServer = pServer,
            reader = reader,
            capabilities = ::capabilities,
            fanModeRaw = probe::fanModeRaw,
            manualFanHandoffStore = manualFanHandoffStore,
            manualFanHandoffIdentity = { manualFanHandoffIdentity },
            elapsedRealtimeMs = SystemClock::elapsedRealtime,
            // The vendor performance mode: the direct Settings read, and the origin record the probe
            // reads at capture. `probe` was built on the DEVICE-PROTECTED context (above), so this is
            // the same directory the :guardian process opens for its restore.
            vendorModeRaw = probe::vendorModeRaw,
            vendorModeOrigin = probe.vendorModeOriginStore,
            // The AYN fan-and-mode coupling flag, read through the app's own Settings read so a mode
            // write can wait out SystemUI's fan rewrite before the fan is written .
            vendorFanCouplingRaw = probe::vendorFanCouplingRaw,
        )
        directHardwareCommands = directCommands
        val commands: HardwareCommandPort = if (guardian != null) {
            GuardianFencedHardwareCommandPort(
                delegate = directCommands,
                authority = guardian,
                fence = GuardianProcessMutationFence(
                    File(deviceProtectedContext.filesDir, GuardianProcessPaths.FENCE_DIRECTORY).absoluteFile,
                ),
            )
        } else {
            directCommands
        }
        transaction = TransactionCoordinator(
            planner = TuningPlanner(certifiedLookup),
            state = state,
            commands = commands,
            journal = journal,
            hardwareWritesCompiled = BuildConfig.HARDWARE_WRITES_COMPILED,
            requiresGuardianAuthority = BuildConfig.HARDWARE_WRITES_COMPILED,
            guardianPreparedEnvelope = availableGuardian?.let {
                AndroidGuardianPreparedEnvelopePort(this, it)
            } ?: GuardianPreparedEnvelopePort { null },
            guardianArmAuthority = guardian ?: NoGuardianArmAuthority,
            onRestoreAttemptFinished = ::stampVendorFloorLearningSuppression,
        )
        supervisor = HardwareSessionSupervisor(
            policy = supervisorPolicy,
            clock = SupervisorClock { SystemClock.elapsedRealtime() },
            workload = WorkloadObservationPort { target -> attestor.observe(target) },
            telemetry = ThermalTelemetryPort {
                latestMonitorFanSample = null
                val sample = runCatching {
                    val sampleStartedAtMs = SystemClock.elapsedRealtime()
                    val admitted = activeFanCapabilities ?: capabilities().also { resolved ->
                        activeFanCapabilities = resolved
                    }
                    val snap = probe.supervisorTelemetry(admitted)
                    if (BuildConfig.DIAGNOSTICS_COMPILED) {
                        Log.i(
                            TAG,
                            "FAN_MONITOR_SAMPLE elapsedMs=" +
                                (SystemClock.elapsedRealtime() - sampleStartedAtMs) +
                                " packageC=${snap.cpuPackageTempC}" +
                                " hottestCpuC=${snap.cpuTempC}" +
                                " gpuC=${snap.gpuTempC}" +
                                " fan=${snap.vendorFanMode}/${snap.fanDuty}/${snap.fanPeriod}" +
                                " vmode=${snap.vendorPerformanceMode}" +
                                " gpuLevel=${snap.gpuMinPowerLevel}/${snap.gpuMaxPowerLevel}" +
                                " qos=${snap.cpuQosCeilingKhz.toSortedMap()}",
                        )
                    }
                    latestMonitorFanSample = FanControlSample(
                        // 2026-08-23: was `snap.cpuPackageTempC`, the user-facing MEAN, which under-reports the
                        // hottest core AND is only populated for the 8-Elite family -- the tenth
                        // manual-fan blocker. Safety takes the hottest classified CPU zone, exactly as
                        // HardwareCapabilities' own KDoc has always specified.
                        cpuSafetyTempC = snap.cpuTempC,
                        hottestCpuTempC = snap.cpuTempC,
                        gpuTempC = snap.gpuTempC,
                        vendorFanMode = snap.vendorFanMode,
                        duty = snap.fanDuty,
                        period = snap.fanPeriod,
                        rpm = snap.fanRpm,
                    )
                    ThermalTelemetrySample(
                        androidThermalStatus = snap.thermalStatus,
                        cpuTempC = snap.cpuTempC,
                        gpuTempC = snap.gpuTempC,
                        batteryTempDeciC = snap.batteryTempDeciC,
                        cpuQosCeilingKhz = snap.cpuQosCeilingKhz.takeIf { it.isNotEmpty() },
                        // The writable top per policy, from the same table admission snaps against.
                        // A policy whose table could not be read contributes nothing and is compared
                        // verbatim, which is what every policy did before this field existed.
                        cpuWritableMaxKhz = admitted.cpuPolicies.mapNotNull { policy ->
                            CpuWindowAdmissionPolicy.publishedOperatingPointsKhz(policy).maxOrNull()
                                ?.let { policy.id to it }
                        }.toMap(),
                        cpuQosTransport = snap.cpuQosTransport.takeIf { it != CpuQosTransport.UNKNOWN },
                        gpuMinPowerLevel = snap.gpuMinPowerLevel,
                        gpuMaxPowerLevel = snap.gpuMaxPowerLevel,
                        gpuDevfreqMaxHz = snap.gpuDevfreqMaxHz,
                        vendorFanMode = snap.vendorFanMode,
                        // The mode this session applied is watched for a player's change (a yield, never a
                        // repair). `supervisorTelemetry` must read it for the contract leg to be satisfiable.
                        vendorPerformanceMode = snap.vendorPerformanceMode,
                        bootId = snap.bootId,
                    )
                }.getOrNull()
                // This closure does exactly the fan-control work above and nothing else: no
                // telemetry-cache composition, no cross-process probe work beyond
                // supervisorTelemetry() above. The LAB qualification evidence sample (own thread,
                // own cadence, started/stopped from startMonitor()/stopMonitor()) deliberately
                // does not run here -- putting it on this thread was the WLE regression this
                // comment exists to prevent from coming back.
                sample
            },
            ownerLiveness = OwnerLivenessPort { identity ->
                when (identity.ownerTokenId) {
                    // The supervisor's first tick can run before the transaction worker
                    // commits PENDING -> ACTIVE. PENDING is already the exact bounded LAB
                    // owner used by the transaction cancellation fence; treating it as dead
                    // races every successful apply into an immediate Guardian restore.
                    LAB_OWNER_TOKEN_ID -> labApplyHold.isPendingOrActive()
                    RECOVERY_OWNER_TOKEN_ID -> true
                    else -> publicOwners.isOwnerAlive(identity)
                }
            },
            restorer = GenerationBoundRestorer { generation, transactionId ->
                restoreBoundStamped(generation, transactionId)
            },
            targetRepairer = GenerationBoundTargetRepairer { generation, transactionId ->
                transaction.repairAppliedCpuQosBound(generation, transactionId) {
                    supervisor.activeSession()?.let { active ->
                        active.generation == generation &&
                            active.transactionId == transactionId &&
                            publicOwners.isOwnerAlive(active)
                    } == true
                }
            },
            // WAVE2 Stage B: resolve rung -> the profile's SIGNED plan (never a diagnostics
            // bypass here -- unlike certifiedLookup above, this resolves one SPECIFIC signed
            // alternate plan, the same posture qualifiedManualFanPlan call sites already use) and
            // apply it through the normal Guardian-fenced transaction path. An unsigned rung
            // degrades to "remain": zero commands, and the caller never falls back to Restore.
            derater = GenerationBoundDerater { generation, transactionId, rung ->
                val active = supervisor.activeSession()
                val profileId = active?.appliedTargetContract?.profileId?.takeIf { it.isNotBlank() }
                val packageName = active?.targetPackage
                if (active == null ||
                    active.generation != generation ||
                    active.transactionId != transactionId ||
                    profileId == null ||
                    packageName == null
                ) {
                    TransactionResult(
                        success = false,
                        applied = false,
                        restored = false,
                        code = "THERMAL_DERATE_SESSION_UNAVAILABLE",
                        message = "No exact matching active session to resolve a thermal derate rung against.",
                        transactionId = transactionId,
                        generation = generation,
                    )
                } else {
                    val resolvedCapabilities = activeFanCapabilities
                        ?: capabilities().also { activeFanCapabilities = it }
                    val rungPlan = productQualificationResolver.qualifiedDerateRungPlan(
                        profileId = profileId,
                        packageName = packageName,
                        rung = rung,
                        capabilities = resolvedCapabilities,
                    )
                    if (rungPlan == null) {
                        TransactionResult(
                            success = false,
                            applied = false,
                            restored = false,
                            code = "THERMAL_DERATE_UNSIGNED_RUNG",
                            message = "Thermal derate rung $rung is not signed for profile " +
                                "$profileId; remaining at the current ceiling.",
                            transactionId = transactionId,
                            generation = generation,
                        )
                    } else {
                        transaction.applyDerate(
                            expectedGeneration = generation,
                            expectedTransactionId = transactionId,
                            rungPlan = rungPlan,
                            capabilities = resolvedCapabilities,
                        ) {
                            supervisor.activeSession()?.let { current ->
                                current.generation == generation &&
                                    current.transactionId == transactionId &&
                                    publicOwners.isOwnerAlive(current)
                            } == true
                        }
                    }
                }
            },
            // S4.3 (OWED-2): re-assert the SAME already-signed `fanMode`
            // (SPORT or MANUAL -- TuningPlanner.MIMIR_DUTY_OWNED_FAN_MODES) through the same
            // bounded/journalled/Guardian-fenced path repairAppliedCpuQosBound/applyDerate above
            // use -- never a hardcoded value; TransactionCoordinator.reassertAppliedFanBound reads
            // it back from the already-signed applied contract. On failure this never falls back
            // to Restore -- see HardwareSessionSupervisor.execute's `is RepairFanTarget` branch,
            // unchanged by this operation -- a fan-only cause must never cost the compute profile.
            //
            // A MANUAL
            // (mode 6) contract's re-entry precondition (live fan_mode == 4, a prepared handoff
            // matching this exact profile) is prepared HERE, before reassertAppliedFanBound ever
            // runs -- see prepareManualFanReentryForReassert's own kdoc for the gap this closes.
            // A SPORT contract is unaffected: prepareManualFanReentryForReassert returns null
            // immediately for any non-MANUAL fan mode and the path below is unchanged for it.
            fanReasserter = GenerationBoundFanReasserter { generation, transactionId ->
                val active = supervisor.activeSession()
                if (active == null || active.generation != generation || active.transactionId != transactionId) {
                    return@GenerationBoundFanReasserter TransactionResult(
                        success = false,
                        applied = false,
                        restored = false,
                        code = "STALE_GENERATION",
                        message = "Fan re-entry preparation found no matching active session.",
                        transactionId = transactionId,
                        generation = generation,
                    )
                }
                val contract = active.appliedTargetContract
                    ?: return@GenerationBoundFanReasserter TransactionResult(
                        success = false,
                        applied = false,
                        restored = false,
                        code = "FAN_REASSERT_CONTEXT_REJECTED",
                        message = "contract=absent",
                        transactionId = transactionId,
                        generation = generation,
                    )
                prepareManualFanReentryForReassert(active, contract)?.let {
                    return@GenerationBoundFanReasserter it
                }
                try {
                    // Unconditional, every posture -- events are rare (a fan repair in progress).
                    Log.i(FAN_CONTROL_TAG, "reassert stage=execute generation=$generation")
                    transaction.reassertAppliedFanBound(generation, transactionId) {
                        supervisor.activeSession()?.let { current ->
                            current.generation == generation &&
                                current.transactionId == transactionId &&
                                publicOwners.isOwnerAlive(current)
                        } == true
                    }
                } finally {
                    // A consumed (or never-consumed, on a decline) handoff must never be reusable
                    // by a later tick -- mirrors transitionFanControlModeOnWorker's own finally
                    // shape around its handoff lifetime.
                    manualFanHandoffStore.clear()
                    manualFanHandoffIdentity = null
                }
            },
        )
        if (isUserUnlocked()) {
            ensureCredentialStores(surfaceFlingerFrameTelemetry)
        }
        // Bind-only creation is query-scoped and performs no recovery *work* here. Sticky
        // recreation and explicit boot/package starts own foreground recovery in onStart -- but a
        // durable record whose started lifetime was destroyed (force-stop) reaches neither, and
        // then nothing owns it, ever. Dispatch the boot receiver's own plan so onStartCommand does.
        // See CreateTimeRecoveryDispatchPolicy for the device evidence and why the idle gate is
        // load-bearing. This starts the service; it does not recover on the create path itself.
        dispatchCreateTimeRecoveryObligation()
        // The record outlives this process; the keeper does not. A daemon that restarted while a
        // player's cap was held would otherwise leave it to the next vendor reset with nobody
        // watching, while the record went on saying HELD.
        resumeDiscoveredCeilingKeeper()
        // a post-control fan hold outlives the process the same way. One
        // file stat here; the first watch tick hands the fan back on the worker.
        resumePostControlHold()
        // Same shape for a held game package: the journal outlives the process and so does the app-op.
        releaseOrphanedGameAudioFocus()
    }

    /**
     * Hands an unowned durable-recovery obligation to [onStartCommand], which is where every
     * recovery in this service is admitted, sequenced and journalled. Failing to start (a
     * background FGS refusal) is logged and left for the next creation rather than degraded into a
     * bespoke create-path restore -- this method must never gain hardware authority of its own.
     */
    private fun dispatchCreateTimeRecoveryObligation() {
        val plan = runCatching {
            CreateTimeRecoveryDispatchPolicy.plan(
                startedLifetime = startedServiceLifetime.snapshot(),
                hardwareWritesCompiled = BuildConfig.HARDWARE_WRITES_COMPILED,
                load = journal.load(),
                fpsLoad = fpsLimiterJournal.load(),
            )
        }.getOrElse { error ->
            // Never silent. A journal read that THREW and "nothing is owed" both arrive here as a
            // bare `return`, and a stage that can decline without saying so is the exact shape of
            // defect this method exists to close -- shipping one inside the fix would be the joke
            // writing itself. The quiet case stays quiet on purpose: a healthy device reaches this
            // on every bind and has nothing to report.
            Log.w(TAG_CREATE_RECOVERY, "createRecovery plan=unreadable", error)
            null
        } ?: return
        val guardianStarted = if (plan.guardianRecoveryRequired) {
            runCatching {
                startForegroundService(
                    Intent(this, RestoreGuardianProcessService::class.java)
                        .setAction(RestoreGuardianProcessService.ACTION_RECOVER),
                )
            }.isSuccess
        } else {
            false
        }
        val serviceStarted = runCatching {
            startForegroundService(
                Intent(this, MimirTweakerService::class.java).setAction(plan.serviceAction),
            )
        }.isSuccess
        Log.i(
            TAG_CREATE_RECOVERY,
            "createRecovery action=${plan.serviceAction} guardian=${plan.guardianRecoveryRequired}" +
                " guardianStarted=$guardianStarted serviceStarted=$serviceStarted",
        )
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // A service bind has no owner identity. Exact client-token death is the only
        // public owner-loss signal; unbind must perform zero restore/clear work.
        return false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startedServiceLifetime.recordStart(startId)
        if (intent?.action == ACTION_GAME_AUDIO_FOCUS_RECOVERY_RESTORE) beginGameAudioFocusRecovery()
        if (intent?.action == ACTION_POST_CONTROL_HOLD_RELEASE) beginPostControlHoldRelease()
        val fpsJournalAtStart = fpsLimiterJournal.load()
        val fpsRecoveryRequired = fpsJournalAtStart !is FpsLimiterJournalLoad.Empty
        if (fpsRecoveryRequired) {
            beginFpsRecovery(startId)
        }
        val guardianSettlement = intent?.action == ACTION_GUARDIAN_SETTLEMENT
        val recoverySignalIdentity = if (guardianSettlement) {
            recoveryJournalIdentityOrNull(
                generation = intent.getLongExtra(EXTRA_RECOVERY_GENERATION, 0L),
                transactionId = intent.getStringExtra(EXTRA_RECOVERY_TRANSACTION_ID),
            )
        } else {
            null
        }
        if (guardianSettlement) {
            val ingress = GuardianSettlementIngress.dispatch(
                identity = recoverySignalIdentity,
                establishForeground = {
                    runCatching {
                        ensureForeground(getString(R.string.notification_recovery_restore))
                        true
                    }.getOrDefault(false)
                },
                retainMalformedHold = {
                    // Incomplete coordinates never degrade into wildcard recovery authority.
                    // Retain durable truth in an FGS hold, but execute zero restore commands.
                    val malformedLoad = journal.load()
                    if (durableJournalRequiresStartedRecovery(malformedLoad)) {
                        startedServiceLifetime.markRecovery(startId)
                        establishDurableForegroundHold()
                    } else {
                        requestTerminalSettlement()
                    }
                },
                retryForegroundOnly = { identity ->
                    val claim = recoveryOwnership.claimOrMerge(
                        stopWhenSettled = true,
                        startId = startId,
                        journalIdentity = identity,
                    )
                    if (claim.isOwner) scheduleBoundGuardianForegroundRetry()
                },
                enqueueSerialized = { identity ->
                    // Restore selection and RECOVERY lifetime admission wait behind any
                    // in-flight profile switch on the single transaction worker.
                    executeTransactionWork {
                        maybeBeginRecovery(
                            trigger = RecoveryTrigger.SERVICE_CREATE,
                            stopWhenSettled = true,
                            startId = startId,
                            journalIdentity = identity,
                        )
                    }
                },
            )
            return if (ingress == GuardianSettlementIngressResult.REJECTED_MALFORMED) {
                START_NOT_STICKY
            } else {
                START_STICKY
            }
        }
        val journalAtStart = journal.load()
        val durableActionHandled = DurableStartedActionDispatch.dispatch(
            precedence = durableStartedActionPrecedence(
                load = journalAtStart,
                hardwareWritesCompiled = BuildConfig.HARDWARE_WRITES_COMPILED,
            ),
            startForegroundRecovery = {
                startedServiceLifetime.markRecovery(startId)
                maybeBeginRecovery(
                    trigger = TweakerRecoveryPolicy.triggerForAction(intent?.action)
                        ?: RecoveryTrigger.SERVICE_CREATE,
                    stopWhenSettled = true,
                    startId = startId,
                    journalIdentity = null,
                )
            },
            retainForegroundHold = {
                startedServiceLifetime.markRecovery(startId)
                establishDurableForegroundHold()
                if (journalAtStart is JournalLoadResult.Corrupt) {
                    supervisor.enterCorruptHold()
                }
            },
        )
        if (durableActionHandled) return START_STICKY
        if (intent?.action == ACTION_RCT1_CHANNEL_PROBE) {
            val probeGate = SafeLiveReceiptProbeGate.evaluate(
                posture = BuildConfig.POSTURE,
                hardwareWritesCompiled = BuildConfig.HARDWARE_WRITES_COMPILED,
                userUnlocked = isUserUnlocked(),
                controlPermissionGranted = checkCallingOrSelfPermission(CONTROL_PERMISSION) == PackageManager.PERMISSION_GRANTED,
                journalEmpty = journalAtStart is JournalLoadResult.Empty,
                pServerInitialized = ::pServer.isInitialized,
            )
            if (probeGate == SafeLiveReceiptProbeGate.Open) {
                executeTransactionWork {
                    runSafeRct1ChannelProbe()
                }
            } else {
                val marker = if (probeGate == SafeLiveReceiptProbeGate.UserLocked) {
                    "PRE_UNLOCK_BLOCKED reason=not_observed_no_direct_boot_claim"
                } else {
                    "GuardianReadyLoad.Ambiguous gate=$probeGate"
                }
                Log.i(TAG, "RCT1_CHANNEL_PROBE terminal $marker")
                requestTerminalSettlement()
            }
            return START_NOT_STICKY
        }
        if (intent?.action == TweakerServiceActions.ACTION_BEGIN_ACTIVE_SESSION_V4) {
            val foregroundEstablished = runCatching {
                ensureForeground(getString(R.string.notification_active_profile))
                true
            }.getOrDefault(false)
            if (foregroundEstablished) {
                val pending = startedServiceLifetime.beginPending(
                    startId = startId,
                    nowElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                    graceMs = supervisorPolicy.activationGraceMs,
                )
                schedulePendingAdmissionExpiry(pending)
            } else {
                requestTerminalSettlement()
            }
            return START_STICKY
        }
        if (intent?.action == TweakerServiceActions.ACTION_BEGIN_FPS_LIMIT_SESSION_V7) {
            if (fpsRecoveryRequired) return START_STICKY
            val foregroundEstablished = runCatching {
                ensureForeground(getString(R.string.notification_active_profile))
                true
            }.getOrDefault(false)
            if (foregroundEstablished) {
                val pending = fpsLimiterSession.begin(
                    startId = startId,
                    graceMs = supervisorPolicy.activationGraceMs,
                )
                scheduleFpsPendingAdmissionExpiry(pending)
            } else {
                requestTerminalSettlement()
            }
            return START_STICKY
        }
        val selection = TweakerRecoveryLifecycle.selectionForServiceStart(
            intentAction = intent?.action,
            hardwareWritesCompiled = BuildConfig.HARDWARE_WRITES_COMPILED,
            load = journalAtStart,
        )
        val explicitRecovery = intent?.action == ACTION_RECOVERY_RESTORE
        if (explicitRecovery || selection != RecoverySelection.NONE) {
            startedServiceLifetime.markRecovery(startId)
            val trigger = if (explicitRecovery) {
                RecoveryTrigger.SERVICE_CREATE
            } else {
                TweakerRecoveryPolicy.triggerForAction(intent?.action) ?: RecoveryTrigger.SERVICE_CREATE
            }
            maybeBeginRecovery(
                trigger = trigger,
                stopWhenSettled = true,
                startId = startId,
                journalIdentity = null,
            )
            return START_STICKY
        }
        if (intent?.action == TweakerServiceActions.ACTION_BEGIN_LAB_PROFILE_HOLD &&
            intent.getBooleanExtra(TweakerServiceActions.EXTRA_LAB_APPLY_HOLD, false)
        ) {
            if (!BuildConfig.DIAGNOSTICS_COMPILED) {
                return START_NOT_STICKY
            }
            ensureCredentialStores()
            val profileId = intent.getStringExtra(TweakerServiceActions.EXTRA_PROFILE_ID)
                .orEmpty().ifBlank { "balanced" }
            val observationDurationSeconds = intent.getIntExtra(
                TweakerServiceActions.EXTRA_LAB_OBSERVE_SECONDS,
                0,
            )
            val systemScopeRequest = intent.getBooleanExtra(EXTRA_LAB_SYSTEM_SCOPE, false) &&
                observationDurationSeconds == 0
            val packageName = if (systemScopeRequest) {
                SystemWideProfileTransition.TARGET_PACKAGE_ID
            } else {
                intent.getStringExtra(TweakerServiceActions.EXTRA_WORKLOAD_PACKAGE).orEmpty()
                    .ifBlank { "dev.eden.eden_emulator" }
            }
            val ownerValidationRequest = intent.getBooleanExtra(
                TweakerServiceActions.EXTRA_LAB_OWNER_VALIDATION,
                false,
            ) && observationDurationSeconds == 0
            // WAVE2 thermal-derate rung verification programme (2026-08-08,
            // revised same day after device evidence showed a mid-hold second dispatch is
            // unreliable -- see the LAB_APPLY_RUNG kdoc below): an EXPLICIT ABSENT extra means
            // "no override," never rung 1 by default, so every existing applyHold caller that
            // does not know about this extra is byte-identical.
            val rungIndexExtra = if (intent.hasExtra(EXTRA_RUNG_INDEX)) {
                intent.getIntExtra(EXTRA_RUNG_INDEX, 1)
            } else {
                null
            }
            val foregroundEstablished = runCatching {
                ensureForeground(getString(R.string.notification_active_profile))
                true
            }.getOrDefault(false)
            if (!foregroundEstablished) {
                requestTerminalSettlement()
                return START_NOT_STICKY
            }
            executeTransactionWork {
                val requestIdentity = "lab:$profileId:$packageName:${SystemClock.elapsedRealtime()}"
                val hardLeaseDeadline = DiagnosticsCalibrationHoldLeasePolicy
                    .hardDeadlineElapsedRealtimeMs(
                        nowElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                        diagnosticsCompiled = BuildConfig.DIAGNOSTICS_COMPILED,
                    )
                if (hardLeaseDeadline == null) {
                    Log.i(
                        TAG,
                        "LAB_APPLY_HOLD terminal code=INVALID_CALIBRATION_LEASE applied=false " +
                            "restored=false message=Diagnostics calibration lease was rejected.",
                    )
                    return@executeTransactionWork
                }
                // Resolved BEFORE tryBeginPendingHold so an unknown profile or an out-of-range
                // rung index fails closed without ever touching labApplyHold's state machine --
                // same fail-closed posture as applyThermalDerateRungLab's own resolve() call.
                val rungOverride: ThermalDerateRung? = if (rungIndexExtra != null) {
                    when (val resolution = ThermalDerateRungRequest.resolve(profileId, rungIndexExtra)) {
                        is ThermalDerateRungResolution.Resolved -> resolution.rung
                        is ThermalDerateRungResolution.UnknownProfile -> {
                            Log.i(
                                TAG,
                                "LAB_APPLY_HOLD terminal code=UNKNOWN_PROFILE applied=false " +
                                    "restored=false message=Unknown thermal-derate profile: $profileId",
                            )
                            return@executeTransactionWork
                        }
                        is ThermalDerateRungResolution.OutOfRange -> {
                            Log.i(
                                TAG,
                                "LAB_APPLY_HOLD terminal code=RUNG_OUT_OF_RANGE applied=false " +
                                    "restored=false message=Rung $rungIndexExtra is out of range " +
                                    "for $profileId (valid: 1..${resolution.maxRung}).",
                            )
                            return@executeTransactionWork
                        }
                    }
                } else {
                    null
                }
                if (!labApplyHold.tryBeginPendingHold()) {
                    Log.i(
                        TAG,
                        "LAB_APPLY_HOLD terminal code=LAB_HOLD_ACTIVE applied=false " +
                            "restored=false message=An exact LAB hold is already active.",
                    )
                    return@executeTransactionWork
                }
                val activationCapabilities = capabilities()
                if (ownerValidationRequest && !LabOwnerValidationActivationGate.await(
                        observe = { attestor.observe(packageName) },
                        sampleMutableAxes = {
                            LabOwnerValidationMutableAxisVector.from(
                                probe.telemetry(activationCapabilities),
                            )
                        },
                        nowElapsedRealtimeMs = SystemClock::elapsedRealtime,
                        sleepMs = Thread::sleep,
                    )
                ) {
                    Log.i(
                        TAG,
                        "LAB_APPLY_HOLD terminal code=TARGET_NOT_READY applied=false restored=false " +
                            "message=Target did not remain foreground through vendor quiescence.",
                    )
                    labApplyHold.clear()
                    return@executeTransactionWork
                }
                val result = runCatching {
                    runApplyWithSessionCommit(
                        profileId = profileId,
                        ownerUid = Process.myUid(),
                        ownerTokenId = LAB_OWNER_TOKEN_ID,
                        targetPackage = packageName,
                        requestIdentity = requestIdentity,
                        cancelToken = CancelToken(requestIdentity),
                        isOwnerAlive = labApplyHold::isPendingOrActive,
                        requiresLabCalibrationBaseline = !ownerValidationRequest && !systemScopeRequest,
                        sessionScope = if (systemScopeRequest) {
                            ActiveSessionScope.SYSTEM
                        } else {
                            ActiveSessionScope.LAUNCH
                        },
                    ) { operationCapabilities, isCancelled, onAppliedAdopt ->
                        transaction.apply(
                            profileId,
                            packageName,
                            operationCapabilities,
                            dryRun = false,
                            requestId = requestIdentity,
                            hardLeaseDeadlineElapsedRealtimeMs = hardLeaseDeadline,
                            isCancelled = isCancelled,
                            onAppliedAdopt = onAppliedAdopt,
                            onAdoptionRollback = ::clearProvisionalAdoption,
                            rungOverride = rungOverride,
                        )
                    }
                }.getOrElse { error ->
                    TransactionResult(
                        success = false,
                        applied = false,
                        restored = false,
                        code = "LAB_APPLY_FAILED",
                        message = error.message ?: "LAB apply failed",
                    )
                }
                Log.i(
                    TAG,
                    "LAB_APPLY_HOLD terminal code=${result.code} applied=${result.applied} " +
                        "restored=${result.restored} message=${result.message}",
                )
                var committed = false
                try {
                    val disposition = observePostTransactionDisposition(result)
                    committed = result.applied &&
                        disposition == PostTransactionDisposition.KeepActiveApplied &&
                        labApplyHold.commitPendingHold()
                } finally {
                    if (!committed) labApplyHold.clear()
                }
                if (committed && observationDurationSeconds > 0) {
                    val observation = calibrationRunner!!.start(
                        profileId = profileId,
                        packageName = packageName,
                        durationSeconds = observationDurationSeconds,
                    )
                    Log.i(
                        TAG,
                        "LAB_APPLY_HOLD observation code=${observation.code} " +
                            "success=${observation.success} disposition=${observation.report.disposition}",
                    )
                }
            }
        } else {
            requestTerminalSettlement()
        }
        return START_STICKY
    }

    /**
     * The one runtime event that can legitimately move the cached hardware inventory: a display
     * appearing, disappearing, or changing its mode table, which changes `refreshRatesHz` and
     * therefore `hardwareFingerprint`. Registered on the main looper because that is the thread
     * that owns a Handler here; the probe's own cache is synchronized.
     */
    private var displayTopologyListener: DisplayManager.DisplayListener? = null

    private fun registerDisplayTopologyInvalidation() {
        val manager = getSystemService(DisplayManager::class.java) ?: return
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = probe.invalidateStaticTopology()

            override fun onDisplayRemoved(displayId: Int) = probe.invalidateStaticTopology()

            // Fires on every refresh-rate switch and rotation too, so the probe compares the mode
            // table before it drops anything.
            override fun onDisplayChanged(displayId: Int) =
                probe.invalidateStaticTopologyIfDisplaysChanged()
        }
        runCatching { manager.registerDisplayListener(listener, mainHandler) }
            .onSuccess { displayTopologyListener = listener }
    }

    private fun unregisterDisplayTopologyInvalidation() {
        val listener = displayTopologyListener ?: return
        displayTopologyListener = null
        runCatching { getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(listener) }
    }

    override fun onDestroy() {
        destroyed.set(true)
        publicOwners.snapshot()?.let { publicOwners.clearIfToken(it.tokenId) }
        unregisterDisplayTopologyInvalidation()
        labPreMutationBaselineHandoff.clear()
        labApplyHold.clear()
        stopMonitor()
        // The ceiling's watcher is not the monitor and is not torn down by it. The RECORD survives
        // this process on purpose; the thread must not.
        ceilingKeeper.stop()
        cancelRecoveryRetry()
        cancelFgsRetry()
        cancelPendingAdmissionExpiry()
        cancelFpsPendingAdmissionExpiry()
        calibrationRunner?.shutdown()
        recoveryScheduler?.shutdownNow()
        lifetimeScheduler?.shutdownNow()
        // A held game package is put back on the way out, queued rather than awaited: `shutdown()` still
        // runs what is queued, main never blocks on it, and if the process dies first the journal is the
        // authority and the next daemon start finishes the job.
        if (gameAudioFocusJournalPresent()) {
            runCatching {
                transactionExecutor.execute { runCatching { gameAudioFocusSession.recoverOrphan() } }
            }
        }
        // Durable journal is the process-death authority — never block main on restore.
        transactionExecutor.shutdown()
        settlementExecutor.shutdown()
        super.onDestroy()
    }

    /**
     * FGS must succeed before any recovery restore. Explicit/sticky starts share
     * [recoveryOwnership] so only one owner schedules work. Failed FGS retains journal
     * and retries FGS only. No forceReselect bypass.
     */
    private fun maybeBeginRecovery(
        trigger: RecoveryTrigger,
        stopWhenSettled: Boolean,
        startId: Int = -1,
        journalIdentity: RecoveryJournalIdentity? = null,
        claimAlreadyOwned: Boolean = false,
    ) {
        if (destroyed.get()) return
        val claim = if (claimAlreadyOwned) {
            recoveryOwnership.obligation()
        } else {
            recoveryOwnership.claimOrMerge(
                stopWhenSettled = stopWhenSettled,
                startId = startId,
                journalIdentity = journalIdentity,
            )
        }
        if (!claim.isOwner) {
            // A distinct request is retained by RecoveryOwnership and promoted when the
            // current turn settles; duplicate requests never start a second restore pass.
            return
        }
        val load = journal.load()
        if (claim.journalIdentity != null && !recoveryOwnerMatches(load)) {
            settleStaleRecoverySignal(load)
            return
        }
        if (claim.journalIdentity != null && claim.stopStartId >= 0) {
            startedServiceLifetime.markRecovery(claim.stopStartId)
        }
        val selection = TweakerRecoveryPolicy.selectRecovery(
            trigger = trigger,
            hardwareWritesCompiled = BuildConfig.HARDWARE_WRITES_COMPILED,
            load = load,
        )
        if (selection == RecoverySelection.NONE) {
            // Explicit empty-journal recovery is settled — never strand FGS.
            settleRecoveryLeave()
            return
        }
        val begin = ForegroundRecoveryGate.begin(
            selection = selection,
            establishFgs = {
                runCatching {
                    ensureForeground(getString(R.string.notification_recovery_restore))
                    true
                }.getOrDefault(false)
            },
        )
        when (begin) {
            ForegroundRecoveryBegin.SettledLeave -> {
                settleRecoveryLeave()
            }
            ForegroundRecoveryBegin.RetryForegroundOnly -> {
                // Retain ownership + journal; zero hardware commands; bounded FGS-only retry.
                scheduleForegroundEstablishRetry(trigger)
            }
            ForegroundRecoveryBegin.HoldCorrupt -> {
                foregroundEstablishBackoff.reset()
                executeTransactionWork {
                    runRecoveryPass(RecoverySelection.HOLD_CORRUPT_JOURNAL)
                }
            }
            ForegroundRecoveryBegin.ProceedRecovery -> {
                foregroundEstablishBackoff.reset()
                cancelFgsRetry()
                executeTransactionWork {
                    if (!recoveryOwnerMatches(journal.load())) {
                        settleStaleRecoverySignal(journal.load())
                        return@executeTransactionWork
                    }
                    val decision = runRecoveryPass(selection)
                    if (decision == RecoveryLifecycleDecision.SettledLeaveForeground) {
                        // After success always leave FGS; honor merged stopSelf obligation.
                        settleRecoveryLeave()
                    }
                }
            }
        }
    }

    private fun settleRecoveryLeave() {
        foregroundEstablishBackoff.reset()
        cancelFgsRetry()
        cancelRecoveryRetry()
        val next = recoveryOwnership.completeAndTakeNext()
        if (next != null) {
            continueOwnedRecovery(next)
            return
        }
        startedServiceLifetime.settleActiveOrRecovery()
        requestTerminalSettlement()
    }

    private fun recoveryOwnerMatches(load: JournalLoadResult): Boolean = when (load) {
        JournalLoadResult.Empty -> recoveryOwnership.ownsJournal(null, null)
        is JournalLoadResult.Corrupt -> recoveryOwnership.ownsUnscopedJournal()
        is JournalLoadResult.Loaded -> recoveryOwnership.ownsJournal(
            generation = load.record.generation,
            transactionId = load.record.transactionId,
        )
    }

    /** Settle an old Guardian signal without downgrading or stopping a replacement active session. */
    private fun settleStaleRecoverySignal(
        observedLoad: JournalLoadResult = journal.load(),
    ) {
        foregroundEstablishBackoff.reset()
        cancelFgsRetry()
        cancelRecoveryRetry()
        val loaded = observedLoad as? JournalLoadResult.Loaded
        val active = supervisor.activeSession()
        val replacementActive = loaded?.record?.let { record ->
            record.phase == TransactionPhase.APPLIED &&
                active != null &&
                active.generation == record.generation &&
                active.transactionId == record.transactionId &&
                active.requestId == record.requestId
        } == true
        val next = recoveryOwnership.completeAndTakeNext(
            reselectCurrentIfNoPending = observedLoad !is JournalLoadResult.Empty && !replacementActive,
        )
        if (next != null) {
            continueOwnedRecovery(next)
            return
        }
        if (replacementActive) {
            startedServiceLifetime.retainActiveAfterStaleRecovery()
        } else {
            startedServiceLifetime.settleActiveOrRecovery()
        }
        requestTerminalSettlement()
    }

    private fun continueOwnedRecovery(claim: RecoveryOwnership.ClaimResult) {
        maybeBeginRecovery(
            trigger = RecoveryTrigger.SERVICE_CREATE,
            stopWhenSettled = claim.stopWhenSettled,
            startId = claim.stopStartId,
            journalIdentity = claim.journalIdentity,
            claimAlreadyOwned = true,
        )
    }

    private fun scheduleForegroundEstablishRetry(trigger: RecoveryTrigger) {
        if (destroyed.get()) return
        val scheduler = recoveryScheduler ?: Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "mimir-tweaker-recovery-retry").apply { isDaemon = true }
        }.also { recoveryScheduler = it }
        fgsRetryFuture.getAndSet(null)?.cancel(false)
        val delayMs = foregroundEstablishBackoff.nextDelayMs()
        // Keep ownership so a concurrent onStart cannot start a duplicate immediate pass.
        val future = scheduler.schedule(
            {
                if (destroyed.get()) return@schedule
                val obligation = recoveryOwnership.obligation()
                maybeBeginRecovery(
                    trigger = trigger,
                    stopWhenSettled = obligation.stopWhenSettled,
                    startId = obligation.stopStartId,
                    journalIdentity = obligation.journalIdentity,
                    claimAlreadyOwned = true,
                )
            },
            delayMs,
            TimeUnit.MILLISECONDS,
        )
        fgsRetryFuture.set(future)
    }

    /**
     * A Guardian start must satisfy Android's prompt FGS deadline without moving restore work in
     * front of an active profile transaction. This retries only foreground establishment; exact
     * journal validation remains serialized on the transaction worker.
     */
    private fun scheduleBoundGuardianForegroundRetry() {
        if (destroyed.get()) return
        val scheduler = recoveryScheduler ?: Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "mimir-tweaker-recovery-retry").apply { isDaemon = true }
        }.also { recoveryScheduler = it }
        fgsRetryFuture.getAndSet(null)?.cancel(false)
        val delayMs = foregroundEstablishBackoff.nextDelayMs()
        val future = scheduler.schedule(
            {
                if (destroyed.get()) return@schedule
                val obligation = recoveryOwnership.obligation()
                if (!obligation.isOwner || obligation.journalIdentity == null) return@schedule
                val established = runCatching {
                    ensureForeground(getString(R.string.notification_recovery_restore))
                    true
                }.getOrDefault(false)
                if (!established) {
                    scheduleBoundGuardianForegroundRetry()
                    return@schedule
                }
                foregroundEstablishBackoff.reset()
                cancelFgsRetry()
                executeTransactionWork { continueOwnedRecovery(obligation) }
            },
            delayMs,
            TimeUnit.MILLISECONDS,
        )
        fgsRetryFuture.set(future)
    }

    private fun cancelFgsRetry() {
        fgsRetryFuture.getAndSet(null)?.cancel(false)
    }

    private fun beginFpsRecovery(startId: Int) {
        val foregroundEstablished = runCatching {
            ensureForeground(getString(R.string.notification_recovery_restore))
            true
        }.getOrDefault(false)
        if (!foregroundEstablished) {
            fpsLimiterSession.markRecovery(startId)
            requestTerminalSettlement()
            return
        }
        executeTransactionWork {
            fpsLimiterSession.recoverOrphan(startId)
        }
    }

    private fun establishDurableForegroundHold(): Boolean {
        val established = DurableForegroundHoldGate.establishOrRetry(
            establishForeground = {
                runCatching {
                    ensureForeground(getString(R.string.notification_recovery_restore))
                    true
                }.getOrDefault(false)
            },
            scheduleForegroundOnlyRetry = ::scheduleDurableForegroundHoldRetry,
        )
        if (established) {
            foregroundEstablishBackoff.reset()
            cancelFgsRetry()
        }
        return established
    }

    /** SAFE/corrupt hold retry: foreground establishment only, never transaction work. */
    private fun scheduleDurableForegroundHoldRetry() {
        if (destroyed.get()) return
        val scheduler = recoveryScheduler ?: Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "mimir-tweaker-recovery-retry").apply { isDaemon = true }
        }.also { recoveryScheduler = it }
        fgsRetryFuture.getAndSet(null)?.cancel(false)
        val delayMs = foregroundEstablishBackoff.nextDelayMs()
        val future = scheduler.schedule(
            {
                if (destroyed.get()) return@schedule
                mainHandler.post {
                    if (destroyed.get()) return@post
                    if (!durableJournalRequiresStartedRecovery(journal.load())) {
                        foregroundEstablishBackoff.reset()
                        cancelFgsRetry()
                        startedServiceLifetime.settleActiveOrRecovery()
                        requestTerminalSettlement()
                    } else {
                        establishDurableForegroundHold()
                    }
                }
            },
            delayMs,
            TimeUnit.MILLISECONDS,
        )
        fgsRetryFuture.set(future)
    }

    private fun schedulePendingAdmissionExpiry(snapshot: StartedServiceLifetime.Snapshot) {
        val expectedStartId = snapshot.phaseStartId ?: return
        val expectedDeadline = snapshot.pendingDeadlineElapsedRealtimeMs ?: return
        val scheduler = lifetimeScheduler ?: Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "mimir-tweaker-started-lifetime").apply { isDaemon = true }
        }.also { lifetimeScheduler = it }
        cancelPendingAdmissionExpiry()
        val delayMs = (expectedDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        val future = scheduler.schedule(
            {
                val expired = startedServiceLifetime.expirePending(
                    expectedStartId = expectedStartId,
                    expectedDeadlineElapsedRealtimeMs = expectedDeadline,
                    nowElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                )
                if (expired) requestTerminalSettlement()
            },
            delayMs,
            TimeUnit.MILLISECONDS,
        )
        pendingAdmissionFuture.set(future)
    }

    private fun cancelPendingAdmissionExpiry() {
        pendingAdmissionFuture.getAndSet(null)?.cancel(false)
    }

    private fun scheduleFpsPendingAdmissionExpiry(snapshot: StartedServiceLifetime.Snapshot) {
        val expectedStartId = snapshot.phaseStartId ?: return
        val expectedDeadline = snapshot.pendingDeadlineElapsedRealtimeMs ?: return
        val scheduler = lifetimeScheduler ?: Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "mimir-tweaker-started-lifetime").apply { isDaemon = true }
        }.also { lifetimeScheduler = it }
        cancelFpsPendingAdmissionExpiry()
        val delayMs = (expectedDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        val future = scheduler.schedule(
            {
                fpsLimiterSession.expirePending(
                    expectedStartId = expectedStartId,
                    expectedDeadlineElapsedRealtimeMs = expectedDeadline,
                )
            },
            delayMs,
            TimeUnit.MILLISECONDS,
        )
        fpsPendingAdmissionFuture.set(future)
    }

    private fun cancelFpsPendingAdmissionExpiry() {
        fpsPendingAdmissionFuture.getAndSet(null)?.cancel(false)
    }

    /**
     * Apply body for service paths: coordinator receives the adoption callback so APPLIED
     * persist → late-abort → adopt/monitor is one synchronized turn on the trusted record.
     */
    private fun runApplyWithSessionCommit(
        profileId: String,
        ownerUid: Int,
        ownerTokenId: Long,
        targetPackage: String,
        requestIdentity: String,
        cancelToken: CancelToken,
        isOwnerAlive: () -> Boolean,
        requiresLabCalibrationBaseline: Boolean = true,
        sessionScope: ActiveSessionScope = ActiveSessionScope.LAUNCH,
        // F5 : the fan intent the apply body WILL use, so the cooler-re-arm
        // comparison judges the plan that is actually applied -- a MANUAL fan is judged on its
        // curve against the live duty, a SMART one is vendor-owned and passes the fan axis.
        admissionFanIntent: (HardwareCapabilities) -> TuningPlanner.FanIntent = { TuningPlanner.FanIntent.PROFILE_AUTO },
        /**
         * P1-2 (fence review, 2026-10-14): called when the thermal status trip was waived because the
         * candidate judged by [admissionFanIntent] is structurally cooler. The body then owes that
         * exact plan: a caller whose body has fallbacks to a different fan plan must refuse them.
         */
        onCoolerRearmWaived: () -> Unit = {},
        applyBody: (
            operationCapabilities: HardwareCapabilities,
            isCancelled: () -> Boolean,
            onAppliedAdopt: (TransactionJournalRecord) -> Boolean,
        ) -> TransactionResult,
    ): TransactionResult {
        // A verified family catalog that was downloaded is promoted only here, at an apply with no session running and no
        // journal awaiting restore, and before this apply's capabilities are read (admit forces them), so the new families take
        // effect from this apply on and never mid-session. It never throws, and costs one file check when nothing is staged
        // (the idle question, a volatile journal snapshot read, is asked only when something is).
        FamilyCatalogPromotion.promoteIfIdle(
            context = this,
            isIdle = {
                FamilyCatalogPromotion.isIdle(
                    sessionActive = supervisor.activeSession() != null,
                    journal = journalSnapshots.snapshot(),
                )
            },
            invalidate = { probe.invalidateStaticTopology() },
        )
        val operationCapabilities by lazy(LazyThreadSafetyMode.NONE) { capabilities() }
        return ApplyOrchestrator.run(
            establishFgs = {
                runCatching {
                    ensureForeground(getString(R.string.notification_active_profile))
                    true
                }.getOrDefault(false)
            },
            admit = {
                // Policy 2026-10-14 (A), "the hold wins": while the post-control fan hold runs,
                // no profile re-arm may take the fan over, cooler or not. It waits until the hold
                // releases on its own. Judged first, before any telemetry, and never on a dry run
                // (which touches no fan). The yield below the capture is only the race backstop.
                if (!dryRun() && postControlHoldController().isHeld()) {
                    return@run ApplyAdmissionResult.Rejected(
                        code = POST_CONTROL_HOLD_ACTIVE_CODE,
                        message = "A post-control fan hold is cooling the device; re-apply once it releases.",
                    )
                }
                val telemetry = thermalTelemetryForAdmission { operationCapabilities }
                // Policy 2026-10-14, fan-fence fix F5: while Android's thermal status is at
                // the trip, the candidate is compared with the live state (CoolerRearmAdmission)
                // and admitted only when it is STRICTLY cooler -- no axis hotter, at least one
                // lower. The plan is the planner's own; the live state is the probe's full
                // telemetry; the Manual curve is judged with the bias the fan loop will run
                // (P3-7) -- no new bridge operation. Anything that cannot be derived or read refuses.
                val coolerRearm: () -> String? = {
                    runCatching {
                        val live = operationCapabilities
                        val candidate = TuningPlanner().plan(
                            profileId = profileId,
                            packageName = targetPackage,
                            capabilities = live,
                            fanIntent = admissionFanIntent(live),
                        )
                        CoolerRearmAdmission.refusal(
                            candidate,
                            LiveRearmState.from(probe.telemetry(live)),
                            live,
                            bias = FanCurveBias(fanControlSelections.preferenceFor(profileId).curveBias),
                        )
                    }.getOrElse { CoolerRearmAdmission.LIVE_STATE_UNREADABLE }
                        .also { refusal -> if (refusal == null) onCoolerRearmWaived() }
                }
                val admission = if (sessionScope == ActiveSessionScope.SYSTEM) {
                    ApplyAdmission.evaluateSystemWide(telemetry, supervisorPolicy, coolerRearm)
                } else {
                    ApplyAdmission.evaluate(
                        usageAccessGranted = UsageAccessProbe.granted(this@MimirTweakerService),
                        telemetry = telemetry,
                        policy = supervisorPolicy,
                        coolerRearm = coolerRearm,
                    )
                }
                // Rejecting a profile for high temperature leaves cooling unchanged. A rejection with
                // no active Mimir session means the device is already sitting ownerless at
                // vendor Stock -- exactly the state the post-control watch exists for. Arm it
                // (idempotent if already armed) so a hot, unresponsive fan gets a bounded
                // re-kick attempt within its own next tick, instead of the rejection being a
                // silent no-op. Deliberately does NOT loosen admission itself -- a profile that
                // would raise the compute ceiling is still correctly refused while genuinely
                // hot; only the fan-recovery side effect is added.
                if (admission is ApplyAdmissionResult.Rejected && supervisor.activeSession() == null) {
                    startPostControlWatch()
                }
                admission
            },
            apply = {
                if (cancelToken.isCancelled() || !isOwnerAlive()) {
                    return@run TransactionResult(
                        success = false,
                        applied = false,
                        restored = false,
                        code = "REQUEST_CANCELLED",
                        message = "Exact owner request was cancelled before journal/commands.",
                        requestId = requestIdentity,
                    )
                }
                // a running hold REFUSES the apply at admission above --
                // the hold is never handed to a session. A hold can still enter between that
                // admission and this worker step (fence re-check leftover), so the hold is asked
                // again here, right before the capture, and wins again: refuse, fail closed on an
                // unreadable mode, hand nothing over. Dry run never touches the fan.
                if (!dryRun() && postControlHoldController().isHeld()) {
                    return@run TransactionResult(
                        success = false,
                        applied = false,
                        restored = false,
                        code = POST_CONTROL_HOLD_ACTIVE_CODE,
                        message = "A post-control fan hold started before capture; re-apply once it releases.",
                        requestId = requestIdentity,
                    )
                }
                // Not held by the read above. The yield stays as the last line before the capture:
                // the capture below would otherwise record MANUAL as stock, which the session's own
                // restore refuses, so a hold that cannot be handed back declines the apply rather
                // than capturing over it.
                if (!dryRun() && postControlHoldController().yieldToSession() == PostControlHoldYield.RELEASE_FAILED) {
                    return@run TransactionResult(
                        success = false,
                        applied = false,
                        restored = false,
                        code = POST_CONTROL_HOLD_RELEASE_FAILED_CODE,
                        message = "A post-control fan hold could not be handed back to vendor Smart before capture.",
                        requestId = requestIdentity,
                    )
                }
                // The profile's own check/use. The Binder-side precedence check ran BEFORE this block reached the worker,
                // and a v10 ceiling apply (also on the worker) can land in that gap; capturing stock over it would record
                // the cap as stock. Here, on the worker and ahead of any capture, nothing can interleave: a ceiling and a
                // named profile cannot both proceed. Whichever reaches the worker first wins, and the other is refused.
                ceilingRecheckFailureCode()?.let { code ->
                    return@run TransactionResult(
                        success = false,
                        applied = false,
                        restored = false,
                        code = code,
                        message = CeilingPrecedenceGate.refusalMessage(code),
                        requestId = requestIdentity,
                    )
                }
                if (!requiresLabCalibrationBaseline) {
                    // Owner validation is deliberately not calibration evidence. Destroy any
                    // one-shot offer left by a prior LAB calibration attempt before mutation.
                    labPreMutationBaselineHandoff.clear()
                }
                val preMutationReady = LabPreMutationBaselineApplyGate.permits(
                    diagnosticsCompiled = BuildConfig.DIAGNOSTICS_COMPILED,
                    profileId = profileId,
                    requiresCalibrationBaseline = requiresLabCalibrationBaseline,
                ) {
                    labPreMutationBaselineHandoff.captureBeforeApply(
                        profileId = profileId,
                        packageName = targetPackage,
                        capabilities = operationCapabilities,
                        fullTelemetry = { probe.telemetry(operationCapabilities) },
                    )
                }
                if (!preMutationReady) {
                    return@run TransactionResult(
                        success = false,
                        applied = false,
                        restored = false,
                        code = "PRE_MUTATION_BASELINE_INVALID",
                        message = "LAB Quiet requires three coherent Stock vectors before mutation.",
                    )
                }
                val result = applyBody(operationCapabilities, cancelToken::isCancelled) { record ->
                    val baselineAttached = if (
                        requiresLabCalibrationBaseline && BuildConfig.DIAGNOSTICS_COMPILED &&
                        profileId == LabPreMutationBaselineHandoff.QUIET_PROFILE
                    ) labPreMutationBaselineHandoff.onAppliedAdopt(record) else true
                    if (!baselineAttached) {
                        labPreMutationBaselineHandoff.clear()
                        false
                    } else {
                        val adopted = adoptSessionFromJournal(
                            ownerUid = ownerUid,
                            ownerTokenId = ownerTokenId,
                            targetPackage = targetPackage.ifBlank { record.targetPackage },
                            record = record,
                            scope = sessionScope,
                        )
                        if (adopted) activeFanCapabilities = operationCapabilities
                        adopted
                    }
                }
                if (!result.applied) {
                    labPreMutationBaselineHandoff.clear()
                    clearAbandonedApplyLiveness()
                }
                result
            },
        )
    }

    /**
     * Pure extraction of `runApplyWithSessionCommit`'s telemetry-sample expression -- unchanged
     * behaviour, just callable from a second site: [transitionFanControlModeOnWorker]'s pre-handoff
     * admission check needs the identical sample shape so the two admission calls agree.
     *
     * [capabilities] is a supplier, not a value, on purpose: the original site forced its
     * `by lazy` capabilities INSIDE this `runCatching`, so a throwing `capabilities()` became a
     * null sample and a clean `TELEMETRY_ADMISSION_FAILED`. Evaluating it at the call site would
     * move that throw outside the guard at every `runApplyWithSessionCommit` caller.
     */
    private fun thermalTelemetryForAdmission(capabilities: () -> HardwareCapabilities): ThermalTelemetrySample? =
        runCatching {
            val snap = probe.lightTelemetry(capabilities())
            ThermalTelemetrySample(
                androidThermalStatus = snap.thermalStatus,
                cpuTempC = snap.cpuTempC,
                gpuTempC = snap.gpuTempC,
                batteryTempDeciC = snap.batteryTempDeciC,
            )
        }.getOrNull()

    private fun observePostTransactionDisposition(
        result: TransactionResult,
    ): PostTransactionDisposition = PostTransactionDispositionDispatch.dispatch(
        result,
        journal.load(),
    ) { disposition ->
            when (disposition) {
                PostTransactionDisposition.KeepActiveApplied -> Unit
                PostTransactionDisposition.LeaveForegroundSettled -> {
                    labApplyHold.clear()
                    startedServiceLifetime.snapshot().phaseStartId?.let {
                        startedServiceLifetime.settleApplying(it)
                    }
                    requestTerminalSettlement()
                }
                PostTransactionDisposition.HoldCorrupt -> {
                    labApplyHold.clear()
                    stopMonitor()
                    cancelRecoveryRetry()
                    supervisor.enterCorruptHold()
                    startedServiceLifetime.markRecovery()
                    // Retain FGS — zero commands / no tight loop.
                }
                is PostTransactionDisposition.RetainRecovery -> {
                    val record = disposition.record
                    val now = SystemClock.elapsedRealtime()
                    supervisor.clearSession()
                    supervisor.adoptAppliedSession(
                        ActiveSessionIdentity(
                            generation = record.generation,
                            transactionId = record.transactionId,
                            requestId = record.requestId,
                            ownerUid = Process.myUid(),
                            targetPackage = record.targetPackage,
                            activationDeadlineMs = now,
                            leaseDeadlineMs = record.leaseDeadline,
                            targetEverForeground = true,
                            ownerTokenId = RECOVERY_OWNER_TOKEN_ID,
                            requestDeadlineElapsedRealtimeMs = record.requestDeadline,
                            hardLeaseDeadlineMs = record.leaseDeadline,
                            scope = ActiveSessionScope.RECOVERY,
                        ),
                    )
                    publicOwners.clearMatchingSession(record.generation, record.transactionId)
                    labApplyHold.clear()
                    if (
                        result.code == "CROSS_STORE_RECONCILIATION_REQUIRED" ||
                        result.code == "GUARDIAN_ARM_RETAINED"
                    ) {
                        supervisor.enterCrossStoreHold()
                        cancelRecoveryRetry()
                    } else {
                        supervisor.armBoundedRetryAfterFailedRestore()
                        scheduleRecoveryRetry()
                    }
                    startedServiceLifetime.markRecovery()
                    // Retain FGS while recovery remains owed.
                }
            }
        }

    private fun runRecoveryPass(selection: RecoverySelection): RecoveryLifecycleDecision {
        val restoreResult: TransactionResult? = when (selection) {
            RecoverySelection.NONE -> null
            RecoverySelection.HOLD_CORRUPT_JOURNAL -> {
                stopMonitor()
                supervisor.enterCorruptHold()
                null
            }
            RecoverySelection.START_FOREGROUND_RECOVERY -> {
                when (val loaded = journal.load()) {
                    JournalLoadResult.Empty -> {
                        settleRecoveredRuntimeState("NO_ACTIVE_SESSION")
                        TransactionResult(true, false, false, "NO_ACTIVE_SESSION", "empty")
                    }
                    is JournalLoadResult.Corrupt -> {
                        supervisor.enterCorruptHold()
                        TransactionResult(false, false, false, "JOURNAL_CORRUPT", loaded.reason)
                    }
                    is JournalLoadResult.Loaded -> {
                        // P3-6 (fence review, 2026-10-14): read BEFORE the restore, which may
                        // acknowledge the receipt. A Guardian receipt for this very generation means
                        // the Guardian already handed the hardware back on its own and this pass only
                        // reconciles it -- a hand-back the player must hear about, not a silent
                        // service restore (the race that hid gen 51 on the Odin).
                        val guardianSettled = (guardianTerminalReceipts?.loadUntil(0) as? GuardianTerminalLoad.Received)
                            ?.stock?.receipt?.generation == loaded.record.generation
                        val result = restoreBoundStamped(
                            loaded.record.generation,
                            loaded.record.transactionId,
                        )
                        GuardianRestoreObservability.onServiceRestore(
                            cause = if (guardianSettled) "GUARDIAN_STARTUP_RECOVERY_RESTORE" else "STARTUP_RECOVERY_RESTORE",
                            generation = loaded.record.generation,
                            transactionId = loaded.record.transactionId,
                            restored = result.restored,
                            code = result.code,
                            // The record is already loaded for this pass, so surfacing the causal
                            // string it has been carrying costs nothing and destroys nothing. This
                            // is the read path for a `lastFailure` persisted by an EARLIER,
                            // already-finished failure: a retained record survives process death by
                            // design (that is the premise of this whole recovery pass), so the
                            // restart that runs this pass is where an otherwise-unreadable cause
                            // finally reaches logcat.
                            journalCause = loaded.record.lastFailure,
                            // The adjudication that decided this restore. Already in `result`;
                            // this is THE line that was missing when the reference Nova held at
                            // CROSS_STORE_RECONCILIATION_REQUIRED on 2026-08-30 and the daemon
                            // could not say whether the guardian arm was gone, corrupt, or merely
                            // disagreeing -- three different repairs behind one code.
                            settlement = result.message,
                        )
                        if (!result.restored) {
                            val now = SystemClock.elapsedRealtime()
                            supervisor.retainOrAdoptForRecovery(
                                ActiveSessionIdentity(
                                    generation = loaded.record.generation,
                                    transactionId = loaded.record.transactionId,
                                    requestId = loaded.record.requestId,
                                    ownerUid = Process.myUid(),
                                    targetPackage = loaded.record.targetPackage,
                                    activationDeadlineMs = now,
                                    leaseDeadlineMs = loaded.record.leaseDeadline,
                                    targetEverForeground = true,
                                    ownerTokenId = RECOVERY_OWNER_TOKEN_ID,
                                    requestDeadlineElapsedRealtimeMs = loaded.record.requestDeadline,
                                    hardLeaseDeadlineMs = loaded.record.leaseDeadline,
                                    scope = ActiveSessionScope.RECOVERY,
                                ),
                            )
                            if (
                                result.code == "CROSS_STORE_RECONCILIATION_REQUIRED" ||
                                result.code == "GUARDIAN_ARM_RETAINED"
                            ) {
                                supervisor.enterCrossStoreHold()
                            } else {
                                supervisor.armBoundedRetryAfterFailedRestore()
                            }
                            result
                        } else {
                            settleRecoveredRuntimeState("ACTIVE_PROFILE_RESTORED")
                            result
                        }
                    }
                }
            }
        }
        val decision = TweakerRecoveryLifecycle.decideAfterPass(selection, restoreResult)
        when (decision) {
            RecoveryLifecycleDecision.KeepForegroundRetry -> {
                scheduleRecoveryRetry()
            }
            RecoveryLifecycleDecision.HoldCorrupt -> {
                cancelRecoveryRetry()
                // Keep FGS while corrupt journal remains blocking truth.
            }
            RecoveryLifecycleDecision.HoldCrossStore -> {
                cancelRecoveryRetry()
                supervisor.enterCrossStoreHold()
                // This execution turn is complete. Preserve the merged stop obligation, but
                // yield the claim so Guardian's later durable terminal signal can reconcile.
                // No timer or legacy restore loop is scheduled here.
                recoveryOwnership.yieldForGuardianSignal()?.let { next ->
                    executeTransactionWork { continueOwnedRecovery(next) }
                }
            }
            RecoveryLifecycleDecision.SettledLeaveForeground -> {
                cancelRecoveryRetry()
                // Ownership released by settleRecoveryLeave caller.
            }
            RecoveryLifecycleDecision.None -> {
                // Reserved / unused after NONE→SettledLeaveForeground; settle via caller.
                cancelRecoveryRetry()
            }
        }
        return decision
    }

    private fun scheduleRecoveryRetry() {
        if (destroyed.get()) return
        val scheduler = recoveryScheduler ?: Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "mimir-tweaker-recovery-retry").apply { isDaemon = true }
        }.also { recoveryScheduler = it }
        recoveryRetryFuture.getAndSet(null)?.cancel(false)
        val delayMs = supervisor.pendingRetryDelayMs()
            ?: SupervisorPolicy().restoreRetryInitialMs
        val future = scheduler.schedule(
            {
                if (destroyed.get()) return@schedule
                executeTransactionWork {
                    if (!recoveryOwnerMatches(journal.load())) {
                        settleStaleRecoverySignal(journal.load())
                        return@executeTransactionWork
                    }
                    val selection = TweakerRecoveryPolicy.selectRecovery(
                        trigger = RecoveryTrigger.SERVICE_CREATE,
                        hardwareWritesCompiled = BuildConfig.HARDWARE_WRITES_COMPILED,
                        load = journal.load(),
                    )
                    val decision = runRecoveryPass(selection)
                    // SettledLeave (incl. NONE/empty after retry) must settle merged obligation
                    // and leave FGS — never release ownership while FGS remains.
                    if (TweakerRecoveryLifecycle.requiresTerminalSettlement(decision)) {
                        settleRecoveryLeave()
                    }
                }
            },
            delayMs,
            TimeUnit.MILLISECONDS,
        )
        recoveryRetryFuture.set(future)
    }

    private fun cancelRecoveryRetry() {
        recoveryRetryFuture.getAndSet(null)?.cancel(false)
    }

    /** Reconciles process-memory ownership only after durable truth is empty/restored. */
    private fun settleRecoveredRuntimeState(calibrationReason: String) {
        settleRecoveredRuntimeState(
            calibrationReason = calibrationReason,
            exactPublicOwnerTokenId = publicOwners.snapshot()?.tokenId,
        )
    }

    private fun settleRecoveredRuntimeState(
        calibrationReason: String,
        exactPublicOwnerTokenId: Long?,
    ) {
        calibrationRunner?.abort(calibrationReason)
        supervisor.clearSession()
        exactPublicOwnerTokenId?.let(publicOwners::clearIfToken)
        labPreMutationBaselineHandoff.clear()
        labApplyHold.clear()
        stopMonitor()
        cancelRecoveryRetry()
    }

    /** A failed apply may have heartbeated before adoption. Keep retained recovery truth gated. */
    private fun clearAbandonedApplyLiveness() {
        if (journal.load() == JournalLoadResult.Empty && supervisor.activeSession() == null) {
            manualFanMonitorLiveness.disarm()
        }
    }

    /** Clears provisional supervisor/monitor after post-adopt exact-restore rollback. */
    private fun clearProvisionalAdoption() {
        stopMonitor()
        supervisor.clearSession()
        labApplyHold.clear()
    }

    private fun adoptSessionFromJournal(
        ownerUid: Int,
        ownerTokenId: Long,
        targetPackage: String,
        record: TransactionJournalRecord? = null,
        scope: ActiveSessionScope = ActiveSessionScope.LAUNCH,
    ): Boolean {
        val loadedRecord = (journal.load() as? JournalLoadResult.Loaded)?.record ?: return false
        if (record != null && !sameAppliedSessionIdentity(record, loadedRecord)) return false
        val targetContract = AppliedTargetContract.fromAuthenticatedAppliedRecord(loadedRecord)
            ?: return false
        val now = SystemClock.elapsedRealtime()
        if (now >= loadedRecord.leaseDeadline) return false
        if (ownerTokenId > NO_OWNER_TOKEN_ID &&
            !publicOwners.bindAppliedSession(
                tokenId = ownerTokenId,
                generation = loadedRecord.generation,
                transactionId = loadedRecord.transactionId,
            )
        ) {
            return false
        }
        val renewableDeadline = ActiveSessionLeaseDeadline.initial(
            scope = scope,
            nowElapsedRealtimeMs = now,
            renewableWindowMs = supervisorPolicy.leaseWindowMs,
            hardDeadlineElapsedRealtimeMs = loadedRecord.leaseDeadline,
        )
        GuardianSessionLeaseObservability.onLeaseArmed(
            scope = scope.name,
            generation = loadedRecord.generation,
            transactionId = loadedRecord.transactionId,
            leaseDeadlineElapsedRealtimeMs = renewableDeadline,
            hardLeaseDeadlineElapsedRealtimeMs = loadedRecord.leaseDeadline,
            nowElapsedRealtimeMs = now,
        )
        return runCatching {
            supervisor.adoptAppliedSession(
                ActiveSessionIdentity(
                    generation = loadedRecord.generation,
                    transactionId = loadedRecord.transactionId,
                    requestId = loadedRecord.requestId,
                    ownerUid = ownerUid,
                    targetPackage = targetPackage.ifBlank { loadedRecord.targetPackage },
                    activationDeadlineMs = safeElapsedAdd(now, supervisorPolicy.activationGraceMs),
                    leaseDeadlineMs = renewableDeadline,
                    targetEverForeground = scope == ActiveSessionScope.SYSTEM,
                    ownerTokenId = ownerTokenId,
                    requestDeadlineElapsedRealtimeMs = loadedRecord.requestDeadline,
                    hardLeaseDeadlineMs = loadedRecord.leaseDeadline,
                    appliedTargetContract = targetContract,
                    scope = scope,
                ),
            )
            if (!startMonitor()) {
                // Clear provisional supervisor session so coordinator exact-restores.
                supervisor.clearSession()
                false
            } else if (ownerTokenId > NO_OWNER_TOKEN_ID) {
                publicOwners.isOwnerAlive(checkNotNull(supervisor.activeSession()))
            } else true
        }.getOrElse {
            supervisor.clearSession()
            false
        }
    }

    private fun sameAppliedSessionIdentity(
        candidate: TransactionJournalRecord,
        authenticated: TransactionJournalRecord,
    ): Boolean =
        candidate.phase == TransactionPhase.APPLIED &&
            authenticated.phase == TransactionPhase.APPLIED &&
            candidate.generation == authenticated.generation &&
            candidate.transactionId == authenticated.transactionId &&
            candidate.requestId == authenticated.requestId &&
            candidate.profileId == authenticated.profileId &&
            candidate.targetPackage == authenticated.targetPackage &&
            candidate.hardwareFingerprint == authenticated.hardwareFingerprint &&
            candidate.planHash == authenticated.planHash &&
            candidate.bootIdentity == authenticated.bootIdentity &&
            candidate.leaseDeadline == authenticated.leaseDeadline &&
            candidate.lastCompletedMutationBoundary == authenticated.lastCompletedMutationBoundary &&
            candidate.lastCompletedRestoreBoundary == authenticated.lastCompletedRestoreBoundary

    /**
     * True only when a scheduled monitor future exists. destroyed, write-disabled, and
     * executor/schedule exceptions return false and clean partial state.
     */
    private fun startMonitor(): Boolean {
        stopMonitor()
        if (destroyed.get()) return false
        if (!BuildConfig.HARDWARE_WRITES_COMPILED) return false
        return try {
            val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, TX_TICK_SUPERVISOR_THREAD).apply { isDaemon = true }
            }
            monitorExecutor = executor
            // Named so the power-transition window can re-run the ORDINARY cycle. Extra runs must
            // never be a second, lesser control path -- see [registerPowerTransitionRecovery].
            val monitorCycle = Runnable {
                run cycle@{
                    if (destroyed.get()) return@cycle
                    val result = runCatching {
                        // A tick whose sampling and restore outlive its bounded wait is dropped by this thread, so the
                        // worker runs the settlement below itself, with the tick's real result, after the block.
                        onTransactionWorkerSettlingLate(
                            onLateFinish = { late: Result<TransactionResult?> ->
                                monitorTickSettlement.settle(late.getOrNull())
                            },
                        ) {
                            supervisor.activeSession()?.takeIf { active ->
                                BuildConfig.DIAGNOSTICS_COMPILED &&
                                    active.ownerTokenId == LAB_OWNER_TOKEN_ID &&
                                    labApplyHold.isActive()
                            }?.let { active ->
                                supervisor.renewLease(
                                    ownerUid = active.ownerUid,
                                    generation = active.generation,
                                    transactionId = active.transactionId,
                                    requestId = active.requestId,
                                    ownerTokenId = active.ownerTokenId,
                                )
                            }
                            latestMonitorFanSample = null
                            var observedDecision: SupervisorDecision = supervisor.tick()
                            // F2 (fan fence, 2026-10-14): the Guardian's hand-back outranks every
                            // decision below. A session whose heartbeat gate asked the Guardian to
                            // restore, whose heartbeat the Guardian refused, or whose terminal
                            // receipt is already on disk is settled through the ordinary Restore
                            // path NOW -- never a RepairFanTarget dispatched into a generation the
                            // Guardian has terminalized (gen 51 on the Odin: 24 s of that).
                            // P2-3: never from the receipt while the supervisor holds or backs off --
                            // a cross-store hold normally HAS a receipt and is settled by
                            // ACTION_GUARDIAN_SETTLEMENT, not by restoreBound at 1 Hz from here.
                            observedDecision = guardianHandback.override(
                                supervisor.activeSession(),
                                observedDecision,
                                holdOrBackoff = supervisor.isHoldOrBackoff(),
                            ) {
                                guardianTerminalReceipts?.loadUntil(0) is GuardianTerminalLoad.Received
                            }
                            var repairAttempt: TransactionResult? = null
                            var derateAttempt: TransactionResult? = null
                            var fanReassertAttempt: TransactionResult? = null
                            if (observedDecision is SupervisorDecision.Continue) {
                                observedDecision = fanControlDecision(supervisor.activeSession())
                            }
                            if (observedDecision is SupervisorDecision.Restore) {
                                stopFanControl()
                                // Reason observability is always-on (every posture, including
                                // Production) — see GuardianRestoreObservability. Only the LAB
                                // qualification evidence line stays diagnostics-gated below.
                                GuardianRestoreObservability.onRestoreDecided(
                                    decision = observedDecision,
                                    diagnosticsCompiled = BuildConfig.DIAGNOSTICS_COMPILED,
                                    labEvidenceEnabled = ::labQualificationEvidenceEnabled,
                                    logLabEvidence = { message ->
                                        Log.i(LAB_QUALIFICATION_EVIDENCE_TAG, message)
                                    },
                                )
                            }
                            // The tick's RepairFanTarget dispatch, logged before the actual reassert
                            // work runs (`supervisor.execute` -> `HardwareSessionSupervisor`'s `is
                            // RepairFanTarget` branch -> `fanReasserter`). Events are rare (a fan
                            // repair in progress), so a plain Log.i is fine -- see FanOwnershipObservability
                            // for the dedup shape used by the higher-volume decision/attempt lines below.
                            (observedDecision as? SupervisorDecision.RepairFanTarget)?.let { dispatching ->
                                Log.i(
                                    FAN_CONTROL_TAG,
                                    "reassert dispatch generation=${dispatching.generation} " +
                                        "liveFanMode=${probe.fanModeRaw()?.toIntOrNull()}",
                                )
                                // The tick just dispatched a bounded fan repair for
                                // THIS generation -- counts as controller liveness until the
                                // repair either lands (markHealthy below, on FAN_REASSERTED) or
                                // the window itself goes stale (MAX_REPAIR_AGE_MS). Guarded on
                                // the decision's own generation/transaction matching the CURRENT
                                // active session so a session that already moved on between
                                // `tick()` and here is never marked repairing for.
                                supervisor.activeSession()?.takeIf {
                                    it.generation == dispatching.generation &&
                                        it.transactionId == dispatching.transactionId
                                }?.let { active ->
                                    manualFanMonitorLiveness.markRepairing(active, SystemClock.uptimeMillis())
                                }
                            }
                            val outcome = supervisor.execute(
                                observedDecision,
                                onRepairAttempt = { repairAttempt = it },
                                onDerateAttempt = { derateAttempt = it },
                                onFanReassertAttempt = { fanReassertAttempt = it },
                            )
                            // F2: a repair/derate/reassert that met the Guardian's closed terminal
                            // fence was settled by the supervisor as the restore it really is
                            // (HardwareSessionSupervisor.settleGuardianTerminalFence). Say so the
                            // way every other restore is said, so the companion bundle names it.
                            listOfNotNull(repairAttempt, derateAttempt, fanReassertAttempt)
                                .firstOrNull { it.code == TransactionCoordinator.GUARDIAN_TERMINAL_FENCE }
                                ?.let { terminal ->
                                    stopFanControl()
                                    GuardianRestoreObservability.onRestoreDecided(
                                        decision = SupervisorDecision.Restore(
                                            generation = terminal.generation ?: 0L,
                                            transactionId = terminal.transactionId.orEmpty(),
                                            reason = GuardianHeartbeatRetirePolicy.TERMINAL_FENCE,
                                        ),
                                        diagnosticsCompiled = BuildConfig.DIAGNOSTICS_COMPILED,
                                        labEvidenceEnabled = ::labQualificationEvidenceEnabled,
                                        logLabEvidence = { message ->
                                            Log.i(LAB_QUALIFICATION_EVIDENCE_TAG, message)
                                        },
                                    )
                                }
                            (observedDecision as? SupervisorDecision.Derate)?.let { decision ->
                                // Reason observability is always-on (every posture, including
                                // Production) — see GuardianDerateObservability. Logged AFTER
                                // execute (WAVE2 Stage B: derate is now actuated) so [attempt]
                                // reports the real outcome instead of the Stage A hardcoded
                                // "unactuated=true".
                                GuardianDerateObservability.onDerateDecided(
                                    decision = decision,
                                    attempt = derateAttempt,
                                    diagnosticsCompiled = BuildConfig.DIAGNOSTICS_COMPILED,
                                    labEvidenceEnabled = ::labQualificationEvidenceEnabled,
                                    logLabEvidence = { message ->
                                        Log.i(LAB_QUALIFICATION_EVIDENCE_TAG, message)
                                    },
                                )
                            }
                            (observedDecision as? SupervisorDecision.RepairCpuTarget)?.let { decision ->
                                // Reason observability is always-on (every posture, including
                                // Production) — see GuardianRepairObservability. Only the LAB
                                // qualification evidence line stays diagnostics-gated below.
                                GuardianRepairObservability.onRepairDecided(
                                    decision = decision,
                                    attempt = repairAttempt,
                                    settlement = outcome,
                                    diagnosticsCompiled = BuildConfig.DIAGNOSTICS_COMPILED,
                                    labEvidenceEnabled = ::labQualificationEvidenceEnabled,
                                    logLabEvidence = { message ->
                                        Log.i(LAB_QUALIFICATION_EVIDENCE_TAG, message)
                                    },
                                )
                            }
                            (observedDecision as? SupervisorDecision.RepairFanTarget)?.let { decision ->
                                // P5 :
                                // always-on (every posture, including Production) observability for
                                // a decision/attempt pair that previously reached logcat in NEITHER
                                // direction -- see FanOwnershipObservability's own kdoc for the
                                // 02:39->02:58 window this closes. Logged after execute, same as
                                // RepairCpuTarget above, so the attempt reports the real outcome.
                                FanOwnershipObservability.onRenewDecision(
                                    reason = decision.reason,
                                    liveFanMode = probe.fanModeRaw()?.toIntOrNull(),
                                    contractFan = supervisor.activeSession()?.appliedTargetContract?.fanMode,
                                )
                                fanReassertAttempt?.let { attempt ->
                                    FanOwnershipObservability.onReassertAttempt(
                                        generation = decision.generation,
                                        transactionId = decision.transactionId,
                                        code = attempt.code,
                                        message = attempt.message,
                                    )
                                    // Phase B (§3 B2, L2): the daemon just wrote the duty target
                                    // it computed for this generation -- a healthy controller
                                    // action, exactly like the MANUAL branch's own markHealthy
                                    // call sites (Hold / a CONFIRMED WriteDuty). Matches
                                    // TransactionCoordinator.FAN_REASSERTED literally, following
                                    // this file's own convention of comparing TransactionResult
                                    // codes by string literal (see e.g. "NO_ACTIVE_SESSION" above).
                                    if (attempt.code == "FAN_REASSERTED") {
                                        supervisor.activeSession()?.takeIf {
                                            it.generation == decision.generation &&
                                                it.transactionId == decision.transactionId
                                        }?.let { active ->
                                            // A re-entry is a fresh start for the duty
                                            // controller, not a continuation of the stale tick
                                            // clock it just spent ~4.6 s away from -- without this,
                                            // the first tick() after re-entry sees a >6 s gap and
                                            // returns the STICKY FailSafe("FAN_CONTROLLER_STALLED"),
                                            // which never clears itself and the controller never
                                            // marks healthy again.
                                            fanController.reset()
                                            manualFanMonitorLiveness.markHealthy(active, SystemClock.uptimeMillis())
                                        }
                                    }
                                }
                            }
                            outcome
                        }
                    }.getOrNull()
                    // The caller took the answer (or the tick failed or never ran): it settles here. A caller that had
                    // already left a running tick gets null above and does nothing; the worker settles that one.
                    monitorTickSettlement.settle(result)
                }
            }
            val future = executor.scheduleWithFixedDelay(
                monitorCycle,
                0L,
                supervisorPolicy.sampleIntervalMs,
                TimeUnit.MILLISECONDS,
            )
            monitorFuture = future
            monitorCycleRunnable = monitorCycle
            registerPowerTransitionRecovery(executor, monitorCycle)
            // Own thread, own cadence -- see LabQualificationEvidenceSampler. A no-op outside
            // LAB. Started alongside the fan monitor (not from within its closure) so it shares
            // the "runs for the whole hold, stops when the hold ends" lifecycle without ever
            // adding work to the fan-control tick itself.
            labQualificationEvidenceSampler.start()
            true
        } catch (_: RuntimeException) {
            stopMonitor()
            false
        }
    }

    private fun stopMonitor() {
        stopFanControl()
        labQualificationEvidenceSampler.stop()
        activeFanCapabilities = null
        unregisterPowerTransitionRecovery()
        monitorCycleRunnable = null
        monitorFuture?.cancel(false)
        monitorFuture = null
        monitorExecutor?.shutdownNow()
        monitorExecutor = null
        // A fresh ordinary monitor (this function is always startMonitor's own first line) means
        // a real session exists again -- the post-control watch's whole reason for running is
        // gone. Also reached from onDestroy and every other stopMonitor call site, which is
        // exactly the blanket "stop watching" this additive, independent watch should follow.
        stopPostControlWatch()
    }

    /**
     * Manual fan backstop: starts (or is a no-op if already armed) the
     * session-independent watch described on [PostControlThermalWatch]. Deliberately its OWN
     * `ScheduledExecutorService`, never [monitorExecutor] -- by the time this is ever called,
     * [stopMonitor] has already torn [monitorExecutor] down (see the two call sites: the monitor
     * cycle's post-Restore branch, and `runApplyWithSessionCommit`'s `admit` lambda on a
     * thermally-rejected apply with no active session). Every tick still funnels its actual
     * hardware-touching work through [onTransactionWorker] onto the single shared
     * [transactionExecutor], exactly like the ordinary monitor cycle does, so this can never race
     * a fresh apply at the hardware level -- whichever reaches [transactionExecutor] first runs to
     * completion before the other starts.
     */
    // Synchronized with stopPostControlWatch: the settlement thread (the obligation re-arm), main (the hold
    // action) and the worker can all arm it; two unsynchronized arms would each build an executor and the
    // loser would tick for the process's life, outside every stop.
    @Synchronized
    private fun startPostControlWatch() {
        if (destroyed.get()) return
        // The HARDWARE_WRITES_COMPILED check below is not the dry-run
        // gate -- a LAB posture (a DIAGNOSTICS build with the dry-run preference on) still
        // compiles hardware writes, so this tick's own forced-cooling reassert further down would
        // write real full-PWM + mode-4 to the fan while dryRun() is true. This follows the
        // same dry-run invariant that the select path (R2-2) and the reassert re-entry (P4) already
        // honor.
        if (dryRun()) {
            Log.i(FAN_CONTROL_TAG, "postControlWatch=skipped reason=dryRun")
            return
        }
        if (!BuildConfig.HARDWARE_WRITES_COMPILED) return
        if (postControlFuture != null) return
        postControlThermalWatch.reset()
        val executor = try {
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, TX_TICK_POST_CONTROL_THREAD).apply { isDaemon = true }
            }
        } catch (_: RuntimeException) {
            return
        }
        postControlExecutor = executor
        // A tick whose block outlives its bounded wait is settled by the worker, with the tick's real evaluation (see
        // [PostControlTickSettlement]). Stopping is tied to THIS watch's executor: a late result from a watch that has
        // since been stopped and replaced must not disarm the replacement.
        val tickSettlement = PostControlTickSettlement(
            onDecision = PostControlThermalObservability::onDecision,
            stopWatch = { if (postControlExecutor === executor) stopPostControlWatch() },
        )
        val tick = Runnable {
            if (destroyed.get() || supervisor.activeSession() != null) {
                stopPostControlWatch()
                return@Runnable
            }
            val evaluation = runCatching {
                onTransactionWorkerSettlingLate(
                    onLateFinish = { late: Result<PostControlThermalEvaluation?> ->
                        tickSettlement.settle(late.getOrNull())
                    },
                ) {
                    // Re-check with the transaction lock effectively held (this thread is the
                    // only writer): closes the narrow window between the check above and this
                    // block actually running against a fresh session that started in between.
                    if (supervisor.activeSession() != null || dryRun()) return@onTransactionWorkerSettlingLate null
                    val admitted = activeFanCapabilities ?: capabilities().also {
                        activeFanCapabilities = it
                    }
                    val snap = probe.supervisorTelemetry(admitted)
                    val sample = PostControlThermalSample(
                        // 2026-08-23: was `snap.cpuPackageTempC`, the user-facing MEAN, which under-reports the
                        // hottest core AND is only populated for the 8-Elite family -- the tenth
                        // manual-fan blocker. Safety takes the hottest classified CPU zone, exactly as
                        // HardwareCapabilities' own KDoc has always specified.
                        cpuSafetyTempC = snap.cpuTempC,
                        gpuTempC = snap.gpuTempC,
                        vendorFanMode = snap.vendorFanMode,
                        duty = snap.fanDuty,
                        period = snap.fanPeriod,
                    )
                    // the controller acts on the
                    // watch's decision -- force, hold, correct, release -- through the command port's
                    // own checked fan writes (E4: each confirmed write stamps the same floor-learning
                    // suppression window the transition entry's hand-to-4 does), and feeds every
                    // confirmation back to the watch so its phase never disagrees with the hardware.
                    postControlHoldController().tick(sample)
                }
            }.getOrNull()
            // The caller took the answer (or the tick failed or never ran): it settles here. A caller that had already
            // left a running tick gets null above and does nothing; the worker settles that one.
            // D2: disarm once confirmed cool -- see PostControlThermalWatch's own kdoc for what
            // that requires (never merely "we had nothing to say" -- incomplete telemetry is
            // never eligible). A null evaluation (sampling itself threw, or a fresh session beat
            // this tick to the transaction lock) never disarms -- only an explicit, confirmed-safe
            // signal does; ignorance keeps this watch armed, not the other way around.
            tickSettlement.settle(evaluation)
        }
        runCatching {
            postControlFuture = executor.scheduleWithFixedDelay(
                tick,
                0L,
                supervisorPolicy.sampleIntervalMs,
                TimeUnit.MILLISECONDS,
            )
        }.onFailure { stopPostControlWatch() }
    }

    /**
     * The hold's actuation, built once on first use (the command port exists by then) and shared
     * with the apply path's yield ([PostControlHoldController.yieldToSession]) and the create-time
     * stale release ([resumePostControlHold]). Every write it can issue is one of
     * [PServerHardwareCommandPort]'s existing checked fan shapes; the first of them is the same
     * `forceCoolingSmartReassert(` the watch has always issued, still behind the tick's own
     * dry-run re-check above.
     */
    private fun postControlHoldController(): PostControlHoldController =
        postControlHold ?: PostControlHoldController(
            watch = postControlThermalWatch,
            marker = postControlHoldMarker,
            live = { directHardwareCommands?.postControlHoldLive() ?: PostControlHoldLive(null, null, null) },
            force = { directHardwareCommands?.forceCoolingSmartReassert() },
            enter = { directHardwareCommands?.enterPostControlHold() },
            correct = { directHardwareCommands?.correctPostControlHold() },
            release = { directHardwareCommands?.releasePostControlHold() },
            onFanWrite = { stampVendorFloorLearningSuppression() }, // E4: after every confirmed write, the force first
            now = SystemClock::elapsedRealtime,
            // The marker is written before the entry write: from that moment the hold is a started
            // obligation (see resumePostControlHold), and it is settled once the marker is gone.
            onHoldObligation = ::promotePostControlHoldLifetime,
            onObligationCleared = ::requestTerminalSettlement,
        ).also { postControlHold = it }

    @Synchronized
    private fun stopPostControlWatch() {
        val holding = postControlThermalWatch.isHolding()
        val markerPresent = this::deviceProtectedContext.isInitialized && postControlHoldMarker.isPresentCached()
        if (holding || markerPresent) {
            // Never silent: the fan stays at MANUAL/full (the safe direction) and the marker stays
            // with it. The obligation keeps the started lifetime alive and the settlement pass
            // re-arms the watch, so a tick of this process still hands the fan back.
            Log.w(
                FAN_CONTROL_TAG,
                "POST_CONTROL_HOLD watch_stopped holding=$holding marker=$markerPresent heldMs=${postControlThermalWatch.heldMs()}",
            )
        }
        postControlFuture?.cancel(false)
        postControlFuture = null
        postControlExecutor?.shutdownNow()
        postControlExecutor = null
        // A disarmed watch may have been the last obligation of a started lifetime (policy
        // 2026-10-14): ask, and let the gate decide. Idempotent, and a no-op once destroyed.
        requestTerminalSettlement()
    }

    /**
     * Re-run the ordinary control cycle across the window where a vendor fan re-init lands.
     *
     * See [PowerTransitionFanRecovery] for the device evidence. In short: the charger transition
     * makes the vendor slam duty to 50% of the period ~530 ms later, and until now the first thing
     * that noticed was the next 1 Hz tick -- so the player heard up to a full second of fan every
     * time a cable moved.
     *
     * Three deliberate properties:
     *
     * - **It schedules the SAME cycle**, never a private fast path. Every extra run carries the
     *   whole contract: session/Guardian fencing, drift checks, and the duty-reassert budget. A
     *   cycle that finds nothing wrong writes nothing and costs no budget.
     * - **It does not test for an active session here.** `onReceive` runs on the main thread, and
     *   session state belongs to the transaction worker; the cycle itself already no-ops without
     *   one. Reaching for that state here would be the unsynchronised read, not the safety check.
     * - **Registration failure is not fatal.** A device that refuses the broadcast keeps exactly
     *   today's 1 Hz behaviour rather than losing the monitor.
     */
    private fun registerPowerTransitionRecovery(
        executor: ScheduledExecutorService,
        monitorCycle: Runnable,
    ) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED -> Unit
                    // F3 (fan fence, 2026-10-14): after a suspend the Guardian's watchdog now counts
                    // awake time only, and this refreshes it the moment the screen comes back
                    // rather than at the next 5 s slot. Non-blocking: it only submits to the
                    // heartbeat thread. Registered with the monitor, so it exists only while a
                    // session has a heartbeat to refresh.
                    Intent.ACTION_SCREEN_ON -> {
                        if (!destroyed.get()) remoteGuardianAuthority?.publishHeartbeatNow()
                        return
                    }
                    else -> return
                }
                if (destroyed.get()) return
                PowerTransitionFanRecovery.recoveryOffsetsMs().forEach { offsetMs ->
                    // Rejected once the executor is shut down, which is a normal race with
                    // stopMonitor, not an error worth failing the broadcast over.
                    runCatching { executor.schedule(monitorCycle, offsetMs, TimeUnit.MILLISECONDS) }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        runCatching { registerReceiver(receiver, filter) }
            .onSuccess { powerTransitionReceiver = receiver }
    }

    private fun unregisterPowerTransitionRecovery() {
        val receiver = powerTransitionReceiver ?: return
        powerTransitionReceiver = null
        runCatching { unregisterReceiver(receiver) }
    }

    /**
     * F2 (fan fence, 2026-10-14): `RemoteGuardianProcessAuthority.onSessionRetired`. Runs on the
     * heartbeat thread, so it only records the hand-back and asks for a monitor cycle; the cycle
     * itself consumes it on the transaction worker, where session state lives (see the
     * [GuardianHandbackGate] call at the top of the monitor cycle). Scheduling the ORDINARY cycle
     * rather than a private settle path keeps every fence, drift check and budget on the one
     * route; if the executor is already gone the next regular tick, or the recovery pass, settles
     * it -- the pending entry survives either way. Reasons and identifiers only in the log.
     */
    private fun onGuardianSessionRetired(generation: Long, transactionId: String, cause: String) {
        guardianHandback.markRestoreRequested(generation, transactionId, cause)
        Log.w(TAG, "GUARDIAN handback generation=$generation transactionId=$transactionId cause=$cause")
        val cycle = monitorCycleRunnable ?: return
        val executor = monitorExecutor ?: return
        // Rejected once the executor is shut down -- a normal race with stopMonitor, not an error.
        runCatching { executor.execute(cycle) }
    }

    private fun fanControlDecision(active: ActiveSessionIdentity?): SupervisorDecision {
        if (active == null) {
            // P3 : a pending renewal
            // is minted for one exact session and must never survive the session it was minted
            // for -- see fanOwnershipRenewal's own kdoc.
            fanOwnershipRenewal = null
            stopFanControl()
            return SupervisorDecision.Continue
        }
        val contract = active.appliedTargetContract
        if (contract == null) {
            stopFanControl()
            return SupervisorDecision.Continue
        }
        if (contract.fanMode == TuningPlanner.FAN_SMART) {
            // A fresh tick observing the vendor's running loop ends a failed handoff episode.
            // A later external drift then gets a bounded repair window, rather than inheriting
            // an hours-old deadline from the previous attempt. Failed attempts alone never reset it.
            if (probe.fanModeRaw()?.toIntOrNull() == TuningPlanner.FAN_SMART) {
                manualFanMonitorLiveness.markHealthy(active, SystemClock.uptimeMillis())
            }
            // Tick-driven
            // renewal of Mimir fan ownership for a SMART-staged contract under an active session.
            // Runs BEFORE the existing SMART-branch body below (observeVendorSmartDuty() etc,
            // unchanged) so a dispatched renewal short-circuits the tick exactly like every other
            // SupervisorDecision path here.
            // The ENTIRE mint-and-dispatch sub-branch below is gated on
            // renewalScopeAdmitted(active.scope) -- see that function's own kdoc for what an
            // unguarded mint used to risk (90f4dbd5). Pre-fix this minted and logged a renewal
            // decision for ANY scope, so a per-game (LAUNCH) session with an external fan change
            // re-minted every 1s tick, each dispatch declining NOT_SYSTEM_SCOPE one function down
            // -- two warn lines per second for a session this daemon does not own the fan section
            // of, and the tick returning early each time. A non-SYSTEM session still runs
            // observeVendorSmartDuty() and everything else below unconditionally; it only skips
            // the mint, the dispatch, and the per-tick log.
            if (renewalScopeAdmitted(active.scope)) {
                val nowElapsedRealtimeMs = SystemClock.elapsedRealtime()
                fanOwnershipRenewal = FanOwnershipRenewalPolicy.forActiveGeneration(
                    fanOwnershipRenewal,
                    active.generation,
                )
                val pendingRenewal = fanOwnershipRenewal
                if (pendingRenewal == null) {
                    val liveFanMode = probe.fanModeRaw()?.toIntOrNull()
                    // Deliberately `liveFanMode != null &&`, narrower than a literal reading of the
                    // spec's "not FAN_SMART" test: the spec's own parenthetical names the intent as
                    // "an external WRITE put the hardware in another mode" -- a null read is a failed
                    // telemetry sample, not an observed write, and must not itself mint an immediate
                    // (unbacked-off) fan ownership attempt every tick a read happens to fail.
                    if (liveFanMode != null && liveFanMode != TuningPlanner.FAN_SMART) {
                        // Neither-owner state: an external write drifted `fan_mode` away from the
                        // vendor Smart this contract itself expects. Immediate, not backed off --
                        // nothing has been attempted yet for this drift, unlike the settle worker's
                        // own degrade (FanOwnershipRenewalPolicy.begin's FIRST_RETRY_MS delay).
                        fanOwnershipRenewal = FanOwnershipRenewalState(
                            generation = active.generation,
                            transactionId = active.transactionId,
                            code = "EXTERNAL_FAN_MODE_$liveFanMode",
                            attempts = 0,
                            nextAttemptAtElapsedMs = nowElapsedRealtimeMs,
                        )
                        FanOwnershipObservability.onRenewDecision(
                            "EXTERNAL_FAN_MODE",
                            liveFanMode,
                            contract.fanMode,
                        )
                    }
                } else if (pendingRenewal.exhausted) {
                    // G (found in review of executor F's commit): a renewal held at the exhausted
                    // 10-minute backoff is reset onto the ordinary cadence early by an observed
                    // external drift -- but EDGE-triggered, on a CHANGE of the live mode, never
                    // LEVEL-triggered on it merely being non-Smart. Pre-fix this called
                    // onExternalDrift directly whenever liveFanMode != FAN_SMART on ANY tick: if
                    // handFanToVendorSmartForRenewal can never win mode 4 back from the vendor (a
                    // game or the vendor UI keeps re-writing SPORT/SILENT), the live mode is != 4
                    // on EVERY tick, so the 10-minute hold was re-armed every ~1s tick and the
                    // daemon slammed full PWM + mode 4 at the fan every ~20s for the rest of the
                    // session. observeWhileExhausted (AdvancedFanControl.kt) owns that
                    // record-then-compare distinction now; see its own kdoc.
                    val liveFanMode = probe.fanModeRaw()?.toIntOrNull()
                    val observed = FanOwnershipRenewalPolicy.observeWhileExhausted(
                        pendingRenewal,
                        liveFanMode,
                        nowElapsedRealtimeMs,
                    )
                    fanOwnershipRenewal = observed
                    if (!observed.exhausted) {
                        FanOwnershipObservability.onRenewDecision(
                            "EXTERNAL_FAN_MODE_DRIFT_RESET",
                            liveFanMode,
                            contract.fanMode,
                        )
                    }
                }
                if (FanOwnershipRenewalPolicy.due(fanOwnershipRenewal, active.generation, nowElapsedRealtimeMs) &&
                    fanOwnershipRenewalInFlight.compareAndSet(false, true)
                ) {
                    // Dispatched, never awaited: this tick is already running ON the single-thread
                    // transactionExecutor (via onTransactionWorker, from startMonitor's monitorCycle),
                    // so a synchronous transactionExecutor.submit(...).get() would enqueue behind
                    // the currently-running task on that SAME thread and deadlock forever.
                    // executeTransactionWork's plain .execute() has no such wait: it queues the
                    // renewal to run immediately after this tick returns, on the same thread, so it
                    // can never race a concurrently-running tick.
                    executeTransactionWork {
                        try {
                            renewMimirFanOwnershipOnWorker(active)
                        } finally {
                            fanOwnershipRenewalInFlight.set(false)
                        }
                    }
                    return SupervisorDecision.Continue
                }
            }
            // THIS is where the vendor is driving its own fan, and it is the only place Mimir can
            // learn what SMART actually does on this chassis. Policy 2026-08-25, "floor it to
            // smart curve": Manual must never command less duty than the vendor would have at the
            // same temperature, and the honest source for that is SMART itself rather than a curve
            // authored per chassis from data we do not have above 33 C. `fanMode` 4 is the shipped
            // default on all three profiles, so this path runs constantly and the floor is warm by
            // the time a player ever presses Manual.
            //
            // Observed BEFORE the reset below: `reset()` clears the controller's session state and
            // must not be read as clearing what it has learned about the hardware.
            observeVendorSmartDuty()
            fanController.reset()
            fanControlSelections.clear()
            manualFanMonitorLiveness.disarm()
            return SupervisorDecision.Continue
        }
        if (contract.fanMode !in TuningPlanner.MIMIR_DUTY_OWNED_FAN_MODES) {
            stopFanControl()
            return SupervisorDecision.Continue
        }
        val profile = FanThermalProfiles.forAppliedProfile(
            profileId = contract.profileId,
            deviceProfileId = contract.deviceProfile,
        ) ?: return active.fanRestore("FAN_PROFILE_UNAVAILABLE")
        val fanCapabilities = activeFanCapabilities ?: capabilities().also { resolved ->
            activeFanCapabilities = resolved
        }
        if (fanCapabilities.deviceProfileId != contract.deviceProfile ||
            TuningAxis.FAN !in fanCapabilities.supportedAxes ||
            !fanCapabilities.fanNodeAvailable
        ) {
            return active.fanRestore("FAN_CAPABILITY_DRIFT")
        }
        val telemetry = latestMonitorFanSample
            ?: return fanControlDegraded("FAN_TELEMETRY_UNAVAILABLE")
        // Timestamp after any one-time capability resolution. Sampling latency must not
        // masquerade as a dead controller; the gap measures completed control decisions.
        // SmartFanThermalController's tick clock and the deadman marks below share the
        // CPU-awake uptime domain; the historical parameter name remains an API detail.
        val nowUptimeMs = SystemClock.uptimeMillis()
        val decision = fanController.tick(
            profile = profile,
            sample = telemetry,
            nowElapsedRealtimeMs = nowUptimeMs,
            strategy = fanControlSelections.current(active).strategy(),
        )
        return when (decision) {
            FanControlDecision.Hold -> {
                manualFanMonitorLiveness.markHealthy(active, SystemClock.uptimeMillis())
                ManualFanTickObservability.onHealthy("Hold")
                SupervisorDecision.Continue
            }
            is FanControlDecision.WriteDuty -> {
                // the 20% floor is retired for every profile, so the
                // actuator-level floor asserted on this write is unconditionally
                // FanPwmBounds.MIN_SPIN_PERCENT (0) now. This specific floor tag was already 0
                // for "quiet" (unchanged for quiet here); balanced/performance move from 20 to
                // 0. Quiet's separate CONTROLLER floor (FanThermalProfile.minSpinPercent, which
                // shaped decision.duty above) was still 20 before this ruling and changes too --
                // do not read this line as "quiet's behavior is unchanged".
                val minimumDutyPercent = FanPwmBounds.MIN_SPIN_PERCENT
                val write = fanDutyMutationPort?.write(
                    decision.duty,
                    decision.period,
                    minimumDutyPercent,
                )
                if (write?.status == CheckedCommandStatus.CONFIRMED) {
                    manualFanMonitorLiveness.markHealthy(active, SystemClock.uptimeMillis())
                    ManualFanTickObservability.onHealthy("WriteDuty")
                    SupervisorDecision.Continue
                } else {
                    val writeStatus = write?.status?.name ?: "MISSING"
                    Log.e(
                        TAG,
                        "Fan duty mutation failed " +
                            "status=$writeStatus " +
                            "requestedDuty=${decision.duty} period=${decision.period} " +
                            "detail=${sanitizeFanDutyDiagnostic(write?.detail)}",
                    )
                    // The
                    // richer status-bearing line, logged here where the real CheckedCommandStatus
                    // is in hand -- fanControlDegraded's own classification deliberately skips
                    // FAN_DUTY_WRITE_FAILED (see its kdoc) so this tick emits exactly one manual
                    // tick line, not two.
                    ManualFanTickObservability.onWriteFailed(writeStatus)
                    fanControlDegraded("FAN_DUTY_WRITE_FAILED")
                }
            }
            is FanControlDecision.FailSafe -> fanControlDegraded(decision.reason)
        }
    }

    private fun ActiveSessionIdentity.fanRestore(reason: String) = SupervisorDecision.Restore(
        generation = generation,
        transactionId = transactionId,
        reason = reason,
    )

    /**
     * S4.3 -- composed-path correction. Every reason reachable here
     * (FAN_TELEMETRY_UNAVAILABLE; every [SmartFanThermalController] [FanControlDecision.FailSafe]
     * reason -- FAN_OWNERSHIP_LOST, FAN_PERIOD/DUTY/PACKAGE_TEMP/GPU_TEMP_UNAVAILABLE,
     * FAN_CLOCK_REGRESSION, FAN_CONTROLLER_STALLED; and
     * FAN_DUTY_WRITE_FAILED) is scoped entirely to this session's own fan telemetry/mode/duty
     * control loop -- none of them ever inspects or depends on CPU/GPU/boot/owner state.
     *
     * Constructing [SupervisorDecision.Restore] for any of them here would reproduce the exact
     * defect class this lane fixed at the supervisor layer
     * ([HardwareSessionSupervisor.admitFanReassert] / `soleRepairableFanDrift`): the SAME live
     * vendor `fan_mode` disagreement that mechanism already detects and bounds-retries via
     * [SupervisorDecision.RepairFanTarget] can also surface here, exactly one tick after that
     * mechanism's own rate limiter degrades to [SupervisorDecision.Continue] -- `fanControlDecision`
     * only ever runs when the supervisor's own `tick()` decision for this same tick IS `Continue`
     * (see `startMonitor`'s `if (observedDecision is SupervisorDecision.Continue)` gate) -- so an
     * unconditional restore here would silently override the supervisor's own bounded
     * degrade-honestly decision for the identical condition, one layer up.
     *
     * This skips fan control for this tick without resetting controller state. A stalled control
     * clock remains failed until reset; successful fan re-entry supplies that reset before marking
     * healthy. Without healthy progress, the independent heartbeat gate expires. The supervisor
     * owns bounded fan-drift retries; this method does not duplicate them.
     */
    private fun fanControlDegraded(reason: String): SupervisorDecision.Continue {
        ManualFanTickObservability.onDegradedReason(reason)
        return SupervisorDecision.Continue
    }

    private fun stopFanControl() {
        fanController.reset()
        fanControlSelections.clear()
        manualFanMonitorLiveness.disarm()
        latestMonitorFanSample = null
        manualFanHandoffStore.clear()
        manualFanHandoffIdentity = null
    }

    private fun sanitizeFanDutyDiagnostic(detail: String?): String = detail.orEmpty()
        .replace(FAN_DIAGNOSTIC_UNSAFE_CHARACTERS, "?")
        .take(FAN_DIAGNOSTIC_MAX_LENGTH)

    /**
     * Read-only product truth for Mimir's existing Utility row. This exposes no actuator:
     * manual PWM remains owned exclusively by the authenticated monitor/Guardian session.
     */
    private fun Bundle.putActiveFanControlReadout(): Bundle = apply {
        val active = supervisor.activeSession() ?: return@apply
        val contract = active.appliedTargetContract ?: return@apply
        if (contract.fanMode !in
            setOf(TuningPlanner.FAN_SMART, TuningPlanner.FAN_SPORT, TuningPlanner.FAN_MANUAL)
        ) {
            return@apply
        }
        val profile = FanThermalProfiles.forAppliedProfile(
            profileId = contract.profileId,
            deviceProfileId = contract.deviceProfile,
        ) ?: return@apply
        putString("activeFanProfileId", profile.profileId)
        val selection = fanControlSelections.current(active)
        putString("fanControlStrategy", selection.strategy().let {
            when (it) {
                FanThermalStrategy.HoldTarget -> "HOLD_TARGET"
                is FanThermalStrategy.AutoCurve -> "AUTO_CURVE"
            }
        })
        putString("fanControlMode", selection.mode.name)
        putInt("fanCurveBias", selection.curveBias)
        putInt("fanTargetTempC", profile.targetTempC)
        putInt("fanPowerReferenceMw", profile.calibrationPowerReferenceMw)
    }

    /**
     * One compiled-in line naming why the fan section is or is not offered, emitted only when the
     * answer or its reasons CHANGE.
     *
     * Deliberately a plain [Log.w], not a `DIAGNOSTICS_COMPILED` diagnostic: that flag is false in
     * PRODUCTION and cannot be flipped without the daemon refusing its own posture attestation, so
     * a diagnostic-gated line would be invisible on exactly production builds. Change-gated
     * rather than per-call because `advancedCapabilitiesV6` is polled roughly once a minute by the
     * frontend's rearm watch, and a steady-state repeat every 60 s is noise that buries the
     * transition anyone actually needs to see.
     */
    private fun logFanSectionAvailability(
        fanSectionAvailable: Boolean,
        autoExitAvailable: Boolean,
        audit: ManualFanAudit?,
    ) {
        val line = "section=$fanSectionAvailable manualEntry=${audit?.admitted == true} " +
            "autoExit=$autoExitAvailable ${audit?.reason ?: "session=absent"}"
        if (line == lastFanSectionAvailabilityLine) return
        lastFanSectionAvailabilityLine = line
        Log.w(FAN_CONTROL_TAG, "availability $line")
    }

    /**
     * Every conjunct behind [manualFanControlAvailable], kept separately so a refusal can be READ
     * rather than guessed at.
     *
     * [[instrument-the-whole-chain-not-the-leaf]]. The actuation path has carried a `decline=` line
     * since the ninth blocker; availability -- the gate that decides whether the row is even
     * OFFERED -- had none, so "fan shows unavailable" was a report with no observable cause and the
     * only way to answer it was to read thirteen conjuncts and reason. [admitted] is still the real
     * predicate's answer; [reason] is a parallel description of the same inputs and is a
     * DIAGNOSTIC, never an authority -- if the two ever disagree, believe [admitted] and fix this
     * string.
     */
    private data class ManualFanAudit(val admitted: Boolean, val reason: String)

    private fun manualFanControlAvailable(active: ActiveSessionIdentity): Boolean =
        manualFanControlAudit(active).admitted

    private fun manualFanControlAudit(active: ActiveSessionIdentity): ManualFanAudit {
        val contract = active.appliedTargetContract
            ?: return ManualFanAudit(false, "contract=absent")
        val resolved = activeFanCapabilities ?: capabilities().also { activeFanCapabilities = it }
        // **2026-08-23: this predicate no longer routes through the signed manifest.** It used to
        // read `productQualificationResolver.qualifiedManualFanPlan(...)`, and that is why manual
        // fan control has never been available on ANY device, ever: both sequence-10 and
        // sequence-11 sign `manualFanMinimumDutyPercent=null` on all three profiles, so
        // `qualifiedManualFanPlan` resolved to null and this returned false on the Odin exactly as
        // it did on the Nova. The manifest no longer serves as tuning authority, so this question
        // is now answered by code the tree owns.
        //
        // The property preserved is the one the signed comparison actually enforced: **the manual
        // plan Mimir would apply is the same CPU/GPU vector as the contract already applied,
        // differing only in fan mode.** Manual fan is a fan-axis change, never a back door to a
        // different clock envelope. The duty FLOOR conjunct is not dropped either, only relocated
        // to where it is enforceable on live hardware -- `PServerFanDutyMutationPort.write` refuses
        // any `minimumDutyPercent != FanPwmBounds.MIN_SPIN_PERCENT` on every single duty write.
        //
        // A bare `TuningPlanner()` is used deliberately: its `calibrationLookup` defaults to
        // `{ _, _ -> false }`, so re-deriving here cannot recurse back into `planAdmittedForApply`.
        // Only the three axes are compared, and `calibrated` is not one of them.
        val manualPlan = TuningPlanner().plan(
            profileId = contract.profileId,
            packageName = active.targetPackage,
            capabilities = resolved,
            fanIntent = TuningPlanner.FanIntent.MANUAL_OVERRIDE,
        )
        // 2026-08-25: this was a third hand-copy of the same law, and it is the copy that made
        // the row read UNAVAILABLE on every devfreq chassis (see manualFanPlanGpuMatchesContract's
        // KDoc -- the eleventh blocker). Availability and actuation now call ONE predicate.
        val manualPlanMatchesContract = manualFanHandoffPlanAdmitted(manualPlan, contract, resolved)
        val guardianJournalBound = fanTransitionJournalBound(active)
        val thermal = runCatching { probe.supervisorTelemetry(resolved) }.getOrNull()
        val admitted = manualFanControlAdmitted(
            active = active,
            capabilities = resolved,
            hardwareWritesCompiled = BuildConfig.HARDWARE_WRITES_COMPILED,
            manualPlanMatchesContract = manualPlanMatchesContract,
            // THE TENTH BLOCKER, closed 2026-08-23. This read `cpuPackageTempC`, which is
            // populated only for the Snapdragon-8-Elite family, so manual fan was refused on the
            // Nova and the RP5 -- exactly the hardware the family-wide ticket set out to unblock --
            // long after all nine device-id and manifest gates had moved. It is not a device-id
            // test, which is why a sweep built on grepping ODIN_3_PROFILE could not see it.
            //
            // The fix is not a widening: `cpuTempC` (hottest classified CPU zone) is what
            // HardwareCapabilities' KDoc already reserves for safety, and it is populated on every
            // device that has thermal zones at all. Admission and the controller now agree on one
            // sensor, so this can no longer admit a device the first controller tick would then
            // fail safe on -- enter-then-die would have been worse than refusing.
            cpuSafetySensorAvailable = thermal?.cpuTempC != null,
            gpuSensorAvailable = thermal?.gpuTempC != null,
            mutationPortAvailable = fanDutyMutationPort != null,
            guardianJournalBound = guardianJournalBound,
        )
        return ManualFanAudit(
            admitted = admitted,
            reason = "writes=${BuildConfig.HARDWARE_WRITES_COMPILED} " +
                "scope=${active.scope} " +
                "profileIdentity=${resolved.deviceProfileId == contract.deviceProfile} " +
                "pserver=${resolved.pServerAvailable} " +
                "fanNode=${resolved.fanNodeAvailable} " +
                "fanAxis=${TuningAxis.FAN in resolved.supportedAxes} " +
                "contractFan=${contract.fanMode} " +
                "profileQualifiable=${
                    contract.profileId in TweakerPostureTruth.PRODUCT_QUALIFIABLE_PROFILE_IDS
                } " +
                "planDerived=${manualPlan != null} " +
                "cpuMatch=${
                    manualPlan != null &&
                        manualFanPlanCpuMatchesContract(manualPlan, contract, resolved)
                } " +
                // A bare `cpuMatch=false` names the conjunct and not the disagreement, which is
                // half an answer: the vectors are what say WHY, and they are what identified the
                // twelfth blocker in one line. `cpuStockMax` is printed with them because the
                // relation's upper bound is the third number in the comparison and a reader cannot
                // check the verdict without it. Printed only on disagreement -- the steady-state
                // line must stay short enough to read.
                (
                    if (manualPlan != null && manualPlan.cpuMaxKhz != contract.cpuQosCeilingKhz) {
                        "cpuPlan=${manualPlan.cpuMaxKhz.toSortedMap()} " +
                            "cpuContract=${contract.cpuQosCeilingKhz.toSortedMap()} " +
                            "cpuStockMax=${
                                resolved.cpuPolicies.associate { it.id to it.maxKhz }.toSortedMap()
                            } "
                    } else {
                        ""
                    }
                    ) +
                "gpuMatch=${
                    manualPlan != null &&
                        manualFanPlanGpuMatchesContract(manualPlan, contract, resolved)
                } " +
                "gpuTransport=${if (contract.gpuDevfreqMaxHz != null) "devfreq" else "pwrlevel"} " +
                "cpuSensor=${thermal?.cpuTempC != null} " +
                "gpuSensor=${thermal?.gpuTempC != null} " +
                "mutationPort=${fanDutyMutationPort != null} " +
                "journalBound=$guardianJournalBound",
        )
    }

    /**
     * One vendor-SMART observation into [SmartFanThermalController]'s learned floor.
     *
     * Deliberately narrow about what counts. The duty is only the vendor's if the vendor is
     * actually holding the fan mode right now -- reading a stale `vendorFanMode` would let Mimir's
     * own duty be learned as the vendor's, turning the floor into a mirror of the thing it is meant
     * to bound. The demand temperature is the GPU zone, matching what the AutoCurve path evaluates,
     * so the floor and the curve are indexed by the same number.
     */
    private fun observeVendorSmartDuty() {
        val resolved = activeFanCapabilities ?: return
        val sample = runCatching { probe.supervisorTelemetry(resolved) }.getOrNull() ?: return
        if (sample.vendorFanMode != TuningPlanner.FAN_SMART) return
        val period = sample.fanPeriod?.takeIf { it > 0 } ?: return
        val duty = sample.fanDuty?.takeIf { it in 0..period } ?: return
        // E4 : fold in the
        // fan-first restore's own write clock (exposed by the port -- see
        // vendorFloorLearningSuppressedUntilElapsedMs's own kdoc for why the service cannot stamp
        // that write itself) alongside this service's own stamped deadline, and take whichever is
        // later.
        val portSuppressedUntil = directHardwareCommands?.lastVendorFanModeWriteElapsedRealtimeMs()
            ?.let { it + VENDOR_FLOOR_LEARNING_SUPPRESSION_MS }
        val suppressedUntil = maxOf(
            vendorFloorLearningSuppressedUntilElapsedMs,
            portSuppressedUntil ?: vendorFloorLearningSuppressedUntilElapsedMs,
        )
        if (!vendorFloorLearningAllowed(SystemClock.elapsedRealtime(), suppressedUntil)) return
        fanController.observeVendorDuty(
            tempC = sample.gpuTempC?.toDouble(),
            dutyPercent = ((duty.toLong() * 100L) / period.toLong()).toInt(),
        )
    }

    /** A live-session handoff shares the same cross-process fence as its fan target write. */
    private fun handFanToVendorSmartBound(): CheckedCommandResult {
        val identity = remoteGuardianAuthority?.currentFenceIdentity()
            ?: return CheckedCommandResult(CheckedCommandStatus.FAILED, detail = "Guardian fan handoff identity absent")
        val fence = GuardianProcessMutationFence(
            File(createDeviceProtectedStorageContext().filesDir, GuardianProcessPaths.FENCE_DIRECTORY).absoluteFile,
        )
        return try {
            when (val result = fence.withApply(identity) {
                directHardwareCommands?.handFanToVendorSmartForRenewal()
                    ?: CheckedCommandResult(CheckedCommandStatus.FAILED, detail = "Fan command port absent")
            }) {
                is GuardianFencedResult.Executed -> result.value
                else -> CheckedCommandResult(CheckedCommandStatus.FAILED, detail = "Guardian fan handoff fence closed")
            }
        } finally {
            stampVendorFloorLearningSuppression()
        }
    }

    /** Suppress floor learning after local fan writes and every completed restore attempt. */
    private fun stampVendorFloorLearningSuppression() {
        vendorFloorLearningSuppressedUntilElapsedMs =
            SystemClock.elapsedRealtime() + VENDOR_FLOOR_LEARNING_SUPPRESSION_MS
    }

    /**
     * A `restoreBound(...)` that actually writes (`restored`) is a Mimir-initiated `fan_mode`
     * write exactly like the transition entry's own hand-to-4 -- [stampVendorFloorLearningSuppression]
     * must follow it every time, not only at the two sites the second review's F fix (R2-1) named
     * (the transition, and the select path's restore lambda). R3-2's named gap: five sibling
     * `transaction.restoreBound` call sites (the supervisor `restorer`, STARTUP_RECOVERY_RESTORE,
     * OWNER_DEATH_RESTORE, `restoreOwnedSession`, `rejectStagedFanOwnership`) never stamped, so a
     * Guardian restore from a MANUAL/SPORT session on any of those paths (e.g. frontend killed
     * during a MANUAL session -> OWNER_DEATH restore -> bridge re-arm ~4.4s later -> select with
     * live 4 -> `enterManualFanMode` refuses `duty >= period` -> AUTO fallback -> the first SMART
     * tick learns 100% into the floor bucket) went unsuppressed. This is now the ONE call site
     * that reaches the coordinator's `restoreBound` anywhere in this service -- every direct
     * caller goes through here instead, so the stamp can never again be added at some sites and
     * missed at others.
     */
    private fun restoreBoundStamped(generation: Long, transactionId: String): TransactionResult =
        transaction.restoreBound(generation, transactionId).also {
            if (it.restored || it.code == "RESTORE_FAILED" ||
                it.code == "CROSS_STORE_RECONCILIATION_REQUIRED"
            ) stampVendorFloorLearningSuppression()
        }

    /** Exact durable authority needed both for Manual entry and an unconditional Auto exit. */
    private fun fanTransitionJournalBound(active: ActiveSessionIdentity): Boolean =
        (journal.load() as? JournalLoadResult.Loaded)?.record?.let { record ->
            val capturedFanMode = record.snapshot.nodes.singleOrNull {
                it.logicalId == HardwareSnapshot.FAN_LOGICAL_ID
            }?.originalValue
            record.phase == TransactionPhase.APPLIED &&
                record.schemaVersion == TRANSACTION_JOURNAL_SCHEMA_V5 &&
                record.requestId == active.requestId &&
                record.generation == active.generation &&
                record.transactionId == active.transactionId &&
                capturedFanMode == TuningPlanner.FAN_SMART &&
                record.guardianBinding != null &&
                GuardianJournalBindingValidator.validate(record).isEmpty()
        } == true

    /**
     * Resolves the live truth -- session, fan availability, refresh rate, current selection -- and
     * hands it to [advancedCapabilitiesBundle], which owns the marshalling. Split that way so the
     * wire shape has a seam a unit test can drive; every decision below still belongs here.
     */
    private fun advancedCapabilitiesV6(): Bundle {
        val active = supervisor.activeSession()
        val audit = active?.let(::manualFanControlAudit)
        val manualEntryAvailable = audit?.admitted == true
        val autoExitAvailable = active?.let { exact ->
            exact.appliedTargetContract?.fanMode == TuningPlanner.FAN_MANUAL &&
                fanTransitionJournalBound(exact)
        } == true
        val fanSectionAvailable = fanControlSectionAvailable(
            manualEntryAvailable = manualEntryAvailable,
            autoExitAvailable = autoExitAvailable,
            contractFanMode = active?.appliedTargetContract?.fanMode,
        )
        logFanSectionAvailability(fanSectionAvailable, autoExitAvailable, audit)
        // This aggregate is only the active-display divisor set used to populate Mimir's global
        // launch-policy picker. Exact package identity, Game Mode support, baseline readback, and
        // journal admission remain mandatory in getFpsLimiterCapabilityV7 at game launch.
        val aggregateFrameLimits = AndroidActiveRefreshRatePort(this@MimirTweakerService)
            .activeRefreshHz()
            ?.let(::supportedGameModeFrameLimits)
            .orEmpty()
        return advancedCapabilitiesBundle(
            apiVersion = API_VERSION,
            perGameOverridesAvailable = API_VERSION >= 7,
            frameLimitsHz = aggregateFrameLimits,
            fanSectionAvailable = fanSectionAvailable,
            manualModeAdvertised = manualFanControlModeAdvertised(
                manualEntryAvailable = manualEntryAvailable,
                contractFanMode = active?.appliedTargetContract?.fanMode,
            ),
            contractFanMode = active?.appliedTargetContract?.fanMode,
            // ActiveFanControlSelectionStore.current() requires a resolved appliedTargetContract
            // (checkNotNull, AdvancedFanControl.kt) -- true for every OTHER call site in this file,
            // each of which already null-checks the contract first (fanControlDecision,
            // putActiveFanControlReadout). This one did not: a session can be active
            // (supervisor.activeSession() != null) before its target contract has been
            // reconstructed from the authenticated APPLIED journal record (see
            // AppliedTargetContract.fromAuthenticatedAppliedRecord's own fail-closed `return null`
            // conditions), so the naked `active?.let(...)` reached current() with a null contract
            // and threw IllegalStateException("Required value was null.") uncaught across the
            // Binder -- observed on-device 22 times in one session .
            // fanSectionAvailable above is independently false whenever appliedTargetContract is
            // null (manualEntryAvailable/autoExitAvailable/contractFanMode all key off the same
            // nullable field), so this guard cannot make an offered section report no selection.
            selection = active?.takeIf { it.appliedTargetContract != null }?.let(fanControlSelections::current),
            // F4 (fan fence, 2026-10-14): every restore family, not only the supervisor's -- see
            // GuardianRestoreObservability.lastRestoreReason.
            lastRestoreReason = GuardianRestoreObservability.lastRestoreReason,
            lastRestoreGeneration = GuardianRestoreObservability.lastRestoreGeneration,
            activeDerate = activeDerateForSession(
                GuardianDerateObservability.lastActuated,
                supervisor.activeSession(),
            ),
        )
    }

    /**
     * Runs [block] on the one transaction worker and waits for it -- but no longer forever. A Binder caller waits at
     * most [TX_BINDER_WAIT_MS] and then gets a [TransactionWorkerBusyException] (each Binder method maps it to
     * `SERVICE_BUSY`); the supervisor, post-control and ceiling ticks wait [TX_TICK_WAIT_MS], and while their previous
     * call is still on the worker the next one is skipped and logged instead of queued. See [BoundedTransactionWorker].
     */
    private fun <T> onTransactionWorker(block: () -> T): T = onTransactionWorkerSettlingLate(null, block)

    /**
     * [onTransactionWorker] for a block whose effect the caller must settle: when the Binder caller gives up on a block
     * that is already running, [onLateFinish] is run by the worker with the block's real outcome, after the block (see
     * [BoundedTransactionWorker]); when the caller gets the answer (or the block never started), it is never called.
     */
    private fun <T> onTransactionWorkerSettlingLate(onLateFinish: ((Result<T>) -> Unit)?, block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            error("Tweaker transaction/recovery work must not run on the Android main thread")
        }
        val caller = Thread.currentThread().name
        return if (caller in TX_TICK_THREADS) {
            boundedTransactionWorker.call(TX_TICK_WAIT_MS, caller, onLateFinish, block)
        } else {
            boundedTransactionWorker.call(binderTransactionWaitMs(), null, onLateFinish, block)
        }
    }

    /** How long a Binder caller waits on the transaction worker. A seam so a test can shorten it; production never overrides it. */
    internal open fun binderTransactionWaitMs(): Long = TX_BINDER_WAIT_MS

    /** [onTransactionWorker] with its own wait, for work that is legitimately long (the LAB rollback drills). */
    private fun <T> onTransactionWorker(waitMs: Long, block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            error("Tweaker transaction/recovery work must not run on the Android main thread")
        }
        return boundedTransactionWorker.call(waitMs, null, block = block)
    }

    private fun executeTransactionWork(block: () -> Unit) {
        transactionWorkCount.incrementAndGet()
        try {
            transactionExecutor.execute {
                try {
                    block()
                } finally {
                    transactionWorkCount.decrementAndGet()
                    requestTerminalSettlement()
                }
            }
        } catch (error: RuntimeException) {
            transactionWorkCount.decrementAndGet()
            requestTerminalSettlement()
            throw error
        }
    }

    /**
     * Explicit SAFE-only receipt observation. This neither arms a guardian nor applies a
     * transaction: one bounded read is normalized fail-closed, then the started service settles.
     */
    private fun runSafeRct1ChannelProbe() {
        val topology = GuardianReceiptChannelTopology(nextRct1ProbeNonce())
        val readyPort = GuardianReceiptChannelReadyPort(
            topology = topology,
            executor = GuardianReceiptChannelPServerExecutor(pServer::executeRaw),
            expected = GuardianReceiptChannelPostUnlockTopology.READY_EXPECTATION,
            runtimeAppUid = Process.myUid(),
            decode = { null },
            nowElapsedRealtimeMs = SystemClock::elapsedRealtime,
        )
        val startedAt = SystemClock.elapsedRealtime()
        val deadline = if (startedAt > Long.MAX_VALUE - RestoreGuardianController.READY_TIMEOUT_MS) {
            Long.MAX_VALUE
        } else {
            startedAt + RestoreGuardianController.READY_TIMEOUT_MS
        }
        val loaded = runCatching { readyPort.loadUntil(deadline) }.getOrDefault(GuardianReadyLoad.Ambiguous)
        // No wire decoder is authorized in this SAFE observation. A Received state is therefore
        // unreachable in this lane, but any observed terminal state is logged faithfully.
        Log.i(TAG, "RCT1_CHANNEL_PROBE terminal ${guardianReadyLoadMarker(loaded)}")
    }

    private fun nextRct1ProbeNonce(): String = ByteArray(RCT1_PROBE_NONCE_BYTES).also(rct1ProbeRandom::nextBytes)
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    private fun onPublicOwnerDeath(owner: PublicOwnerSnapshot) {
        if (destroyed.get()) return
        // The registry has already cancelled the in-flight coordinator token. An active
        // generation is restored only after exact supervisor + journal identity checks.
        val generation = owner.generation ?: return
        val transactionId = owner.transactionId ?: return
        executeTransactionWork {
            if (!ownerSessionMatches(owner, requireAliveOwner = false)) {
                settleDurablyEmptyDeadOwnerRuntime(owner)
                return@executeTransactionWork
            }
            stopMonitor()
            val result = restoreBoundStamped(generation, transactionId)
            GuardianRestoreObservability.onServiceRestore(
                cause = "OWNER_DEATH_RESTORE",
                generation = generation,
                transactionId = transactionId,
                restored = result.restored,
                code = result.code,
                // No journalCause here on purpose: no record is in hand at this point, and this
                // path must not add a journal read of its own. settleOwnerBoundRestore loads the
                // record one call later and logs the cause from there.
                //
                // settlement DOES belong here: it is the cross-store adjudication already carried
                // in this exact result, needs no read of anything, and is the field whose absence
                // made the 2026-08-30 Nova hold undiagnosable in PRODUCTION.
                settlement = result.message,
            )
            settleOwnerBoundRestore(owner, result, cause = "OWNER_DEATH_RESTORE")
        }
    }

    private fun restoreOwnedSession(
        owner: PublicOwnerSnapshot,
        callerRequestId: String,
        callerDeadlineElapsedRealtimeMs: Long,
        cause: String,
    ): Bundle {
        if (!ownerSessionMatches(owner)) {
            return publicFailure(
                code = "STALE_OWNER",
                message = "$cause identity is stale; zero commands executed.",
                callerRequestId = callerRequestId,
                callerDeadlineElapsedRealtimeMs = callerDeadlineElapsedRealtimeMs,
            )
        }
        val generation = owner.generation
            ?: return publicFailure(
                "STALE_OWNER",
                "$cause has no active generation.",
                callerRequestId,
                callerDeadlineElapsedRealtimeMs,
            )
        val transactionId = owner.transactionId
            ?: return publicFailure(
                "STALE_OWNER",
                "$cause has no active transaction.",
                callerRequestId,
                callerDeadlineElapsedRealtimeMs,
            )
        val result = runCatching {
            onTransactionWorker {
                if (!ownerSessionMatches(owner)) {
                    return@onTransactionWorker TransactionResult(
                        success = false,
                        applied = false,
                        restored = false,
                        code = "STALE_OWNER",
                        message = "$cause became stale before restore; zero commands executed.",
                        transactionId = transactionId,
                        requestId = owner.requestId,
                        generation = generation,
                    )
                }
                stopMonitor()
                calibrationRunner?.abort("PROFILE_RESTORE_REQUESTED")
                restoreBoundStamped(generation, transactionId).also {
                    settleOwnerBoundRestore(owner, it, cause = cause)
                }
            }
        }.getOrElse { error ->
            TransactionResult(
                success = false,
                applied = false,
                restored = false,
                code = transactionWorkerFailureCode(error),
                message = error.message ?: "$cause failed.",
                transactionId = transactionId,
                requestId = owner.requestId,
                generation = generation,
            )
        }
        return result.toPublicBundle(
            callerRequestId = callerRequestId,
            callerDeadlineElapsedRealtimeMs = callerDeadlineElapsedRealtimeMs,
            truth = publicSessionTruth(),
        ).withApiVersion()
    }

    private fun settleOwnerBoundRestore(
        owner: PublicOwnerSnapshot,
        result: TransactionResult,
        cause: String,
    ) {
        val commit = ExplicitRestoreGate.afterRestoreAttempt(
            result = result,
            loadJournal = { journal.load() },
        )
        // Observability only — computed from `commit`, changes no branch below. Which branch fired
        // decides whether anything on the daemon will try again (RetainedRetry arms a bounded retry;
        // RetainedHold cancels retry and holds), and until 2026-08-15 that was unlogged: the stock
        // collapse trace could only record RetainedHold as a leading [D]. The record loaded by
        // afterRestoreAttempt is reused here, so this reads the journal zero extra times.
        val settledRecord = when (commit) {
            is ExplicitRestoreCommit.Cleared -> null
            is ExplicitRestoreCommit.RetainedHold -> commit.record
            is ExplicitRestoreCommit.RetainedRetry -> commit.record
        }
        GuardianRestoreObservability.onRestoreSettled(
            settle = when (commit) {
                is ExplicitRestoreCommit.Cleared -> "CLEARED"
                is ExplicitRestoreCommit.RetainedHold -> "RETAINED_HOLD"
                is ExplicitRestoreCommit.RetainedRetry -> "RETAINED_RETRY"
            },
            cause = cause,
            generation = owner.generation,
            transactionId = owner.transactionId,
            code = result.code,
            journalPhase = settledRecord?.phase,
            journalCause = settledRecord?.lastFailure,
        )
        when (commit) {
            is ExplicitRestoreCommit.Cleared -> {
                calibrationRunner?.abort("ACTIVE_PROFILE_RESTORED")
                supervisor.clearSession()
                publicOwners.clearIfToken(owner.tokenId)
                labApplyHold.clear()
                cancelRecoveryRetry()
                startedServiceLifetime.settleActiveOrRecovery()
                requestTerminalSettlement()
            }
            is ExplicitRestoreCommit.RetainedHold -> {
                val record = commit.record
                if (record == null) {
                    settleDurablyEmptyDeadOwnerRuntime(owner)
                    return
                }
                if (record.generation != owner.generation ||
                    record.transactionId != owner.transactionId ||
                    record.requestId != owner.requestId
                ) {
                    return
                }
                retainGuardianRecoveryIdentity(record)
                publicOwners.clearIfToken(owner.tokenId)
                labApplyHold.clear()
                supervisor.enterCrossStoreHold()
                cancelRecoveryRetry()
                startedServiceLifetime.markRecovery()
            }
            is ExplicitRestoreCommit.RetainedRetry -> {
                val record = commit.record
                if (record == null ||
                    record.generation != owner.generation ||
                    record.transactionId != owner.transactionId ||
                    record.requestId != owner.requestId
                ) {
                    // Stale owner can never adopt/restore/clear a newer record.
                    if (result.code == "STALE_GENERATION") {
                        startMonitor()
                    }
                    return
                }
                val now = SystemClock.elapsedRealtime()
                supervisor.clearSession()
                supervisor.adoptAppliedSession(
                    ActiveSessionIdentity(
                        generation = record.generation,
                        transactionId = record.transactionId,
                        requestId = record.requestId,
                        ownerUid = Process.myUid(),
                        targetPackage = record.targetPackage,
                        activationDeadlineMs = now,
                        leaseDeadlineMs = record.leaseDeadline,
                        targetEverForeground = true,
                        ownerTokenId = RECOVERY_OWNER_TOKEN_ID,
                        requestDeadlineElapsedRealtimeMs = record.requestDeadline,
                        hardLeaseDeadlineMs = record.leaseDeadline,
                        scope = ActiveSessionScope.RECOVERY,
                    ),
                )
                publicOwners.clearIfToken(owner.tokenId)
                labApplyHold.clear()
                supervisor.armBoundedRetryAfterFailedRestore()
                scheduleRecoveryRetry()
                startedServiceLifetime.markRecovery()
            }
        }
    }

    private fun settleDurablyEmptyDeadOwnerRuntime(owner: PublicOwnerSnapshot): Boolean {
        val exactPublicOwnerTokenId = when (
            val settlement = EmptyJournalDeadOwnerSettlementGate.evaluate(
                journal = journal.load(),
                deadOwner = owner,
                currentOwner = publicOwners.snapshot(),
                activeSession = supervisor.activeSession(),
            )
        ) {
            is EmptyJournalDeadOwnerSettlement.ExactDeadOwner -> settlement.tokenId
            EmptyJournalDeadOwnerSettlement.AlreadyAbsent -> null
            EmptyJournalDeadOwnerSettlement.Rejected -> return false
        }
        settleRecoveredRuntimeState(
            calibrationReason = "GUARDIAN_DURABLY_SETTLED",
            exactPublicOwnerTokenId = exactPublicOwnerTokenId,
        )
        startedServiceLifetime.settleActiveOrRecovery()
        requestTerminalSettlement()
        return true
    }

    private fun retainGuardianRecoveryIdentity(record: TransactionJournalRecord) {
        val now = SystemClock.elapsedRealtime()
        supervisor.retainOrAdoptForRecovery(
            ActiveSessionIdentity(
                generation = record.generation,
                transactionId = record.transactionId,
                requestId = record.requestId,
                ownerUid = Process.myUid(),
                targetPackage = record.targetPackage,
                activationDeadlineMs = now,
                leaseDeadlineMs = record.leaseDeadline,
                targetEverForeground = true,
                ownerTokenId = RECOVERY_OWNER_TOKEN_ID,
                requestDeadlineElapsedRealtimeMs = record.requestDeadline,
                hardLeaseDeadlineMs = record.leaseDeadline,
                scope = ActiveSessionScope.RECOVERY,
            ),
        )
    }

    private fun ownerSessionMatches(
        owner: PublicOwnerSnapshot,
        requireAliveOwner: Boolean = true,
    ): Boolean {
        if (requireAliveOwner) {
            val registered = publicOwners.snapshot() ?: return false
            if (registered.tokenId != owner.tokenId ||
                registered.ownerUid != owner.ownerUid ||
                registered.requestId != owner.requestId ||
                !registered.alive
            ) {
                return false
            }
        }
        val generation = owner.generation ?: return false
        val transactionId = owner.transactionId ?: return false
        val active = supervisor.activeSession() ?: return false
        if (active.ownerUid != owner.ownerUid ||
            active.ownerTokenId != owner.tokenId ||
            active.requestId != owner.requestId ||
            active.generation != generation ||
            active.transactionId != transactionId
        ) {
            return false
        }
        val loaded = journal.load() as? JournalLoadResult.Loaded ?: return false
        return loaded.record.requestId == owner.requestId &&
            loaded.record.generation == generation &&
            loaded.record.transactionId == transactionId
    }

    /**
     * The truth every public answer carries, including the SERVICE_BUSY answer for a caller the worker just timed out.
     * It reads the supervisor through its lock-free snapshot: a stuck supervisor write holds the supervisor's monitor,
     * and a response built under it would block the very Binder thread the worker timeout freed.
     */
    private fun publicSessionTruth(): PublicSessionTruth {
        val active = supervisor.activeSessionNoLock()
        // The snapshot, never a load: a status answer must not wait on the journal lock or on file and key-store work.
        val loaded = journalSnapshots.snapshot()
        return PublicSessionTruthResolver.resolve(
            journal = loaded,
            active = active,
            recoveryOwned = recoveryOwnerMatches(loaded) ||
                active?.ownerTokenId == RECOVERY_OWNER_TOKEN_ID,
            inFlightOwner = publicOwners.snapshot(),
            leaseRenewable = supervisor.leaseRenewableNoLock(),
        )
    }

    /**
     * Prepares a MANUAL-owned
     * contract's fan for re-entry BEFORE `transaction.reassertAppliedFanBound` runs -- the gap that occurs because `enterManualFanMode` refuses unless live `fan_mode == 4`, a matching
     * handoff is already prepared, and no older than `FAN_HANDOFF_MAX_AGE_MS`, and the ordinary
     * reassert path (unlike an explicit apply/transition) prepared none of those, so a MANUAL
     * contract could never re-enter 6 after an external `fan_mode` change. Mirrors
     * [transitionFanControlModeOnWorker]'s own entry branch (hand-to-4, sample, capture, prepare)
     * exactly, minus that function's restore-and-reapply -- this is a lightweight re-entry against
     * the SAME journalled generation, never a new one.
     *
     * Returns null when the reassert may proceed as-is (a non-MANUAL contract -- SPORT reasserts
     * are unaffected and unchanged by this function -- or a MANUAL contract whose re-entry
     * preparation just succeeded); returns a [TransactionResult] the caller must return VERBATIM,
     * without ever calling `reassertAppliedFanBound`, when re-entry preparation itself declined.
     * Every decline is logged via [FanOwnershipObservability.onReentryDeclined] before returning,
     * in addition to whatever [FanOwnershipObservability.onReassertAttempt] later logs for the same
     * [TransactionResult] once it reaches `HardwareSessionSupervisor.execute`'s `onFanReassertAttempt`
     * callback -- the two lines answer different questions (which stage declined and why, vs. the
     * generic attempt outcome) and neither substitutes for the other.
     */
    private fun prepareManualFanReentryForReassert(
        active: ActiveSessionIdentity,
        contract: AppliedTargetContract,
    ): TransactionResult? {
        // Unconditional,
        // every posture -- events are rare (a fan repair in progress).
        Log.i(FAN_CONTROL_TAG, "reassert stage=prepare generation=${active.generation}")
        if (contract.fanMode != TuningPlanner.FAN_MANUAL) return null

        fun decline(code: String, detail: String): TransactionResult {
            FanOwnershipObservability.onReentryDeclined(active.generation, active.transactionId, code, detail)
            return TransactionResult(
                success = false,
                applied = false,
                restored = false,
                code = code,
                message = detail,
                transactionId = active.transactionId,
                generation = active.generation,
            )
        }

        // Owner precheck before ANY hardware write below -- alive, token alive, same tokenId, not
        // cancelled. isOwnerAlive(active) already checks alive/token-alive/ownerUid/tokenId/
        // requestId/generation/transactionId against the exact session identity; it does not read
        // cancelled, so that is checked separately here (the same field
        // activeOwnerForRenewal's own E3 fix reads on the registry's tick-driven renewal path).
        if (!publicOwners.isOwnerAlive(active) || publicOwners.snapshot()?.cancelled != false) {
            return decline("FAN_REASSERT_OWNER_UNAVAILABLE", "stage=ownerPrecheck")
        }

        // I3: the contract's own journalled profileId binds this reassert's manual handoff --
        // never the port's leftover transactionProfileId, which is set ONLY by plannedMutations
        // and can therefore lag a RECOVERED session (observePostTransactionDisposition's
        // RetainRecovery branch re-adopts an OLDER journalled record while transactionProfileId
        // is left at whatever profile the failed/superseding apply attempt last planned). See
        // PServerHardwareCommandPort.bindReassertProfile's own kdoc for the sourced case.
        directHardwareCommands?.bindReassertProfile(contract.profileId)

        val liveFanModeBeforeHandoff = probe.fanModeRaw()?.toIntOrNull()
        when (fanManualReentryStep(liveFanModeBeforeHandoff, handoffPrepared = false)) {
            FanManualReentryStep.HAND_TO_VENDOR_SMART -> {
                val handToVendorSmart = directHardwareCommands?.handFanToVendorSmartForRenewal()
                if (handToVendorSmart?.status != CheckedCommandStatus.CONFIRMED) {
                    return decline(
                        "FAN_REASSERT_HANDOFF_UNAVAILABLE",
                        "stage=handToVendorSmart liveFanMode=$liveFanModeBeforeHandoff " +
                            "detail=${handToVendorSmart?.detail}",
                    )
                }
                // CONFIRMED: the hardware is now at 4 with the vendor driving. Fall through to the
                // capture below in the SAME pass -- no second tick is required.
                stampVendorFloorLearningSuppression()
            }
            FanManualReentryStep.CAPTURE_HANDOFF -> Unit
            FanManualReentryStep.REASSERT -> return null
        }

        val operationCapabilities = activeFanCapabilities ?: capabilities().also { activeFanCapabilities = it }
        val snapshot = runCatching { probe.supervisorTelemetry(operationCapabilities) }.getOrNull()
        val fresh = snapshot?.let { observed ->
            FanControlSample(
                cpuSafetyTempC = observed.cpuTempC,
                hottestCpuTempC = observed.cpuTempC,
                gpuTempC = observed.gpuTempC,
                vendorFanMode = observed.vendorFanMode,
                duty = observed.fanDuty,
                period = observed.fanPeriod,
                rpm = observed.fanRpm,
            )
        } ?: return decline("FAN_REASSERT_HANDOFF_UNAVAILABLE", "stage=telemetrySample")

        val handoff = ManualFanHandoffCapture.capture(
            profileId = contract.profileId,
            ownerRequestId = active.requestId,
            source = ManualFanHandoffSource.Session(active.generation, active.transactionId),
            minimumDutyPercent = FanPwmBounds.MIN_SPIN_PERCENT,
            sample = fresh,
            nowElapsedRealtimeMs = SystemClock.elapsedRealtime(),
        ) ?: return decline("FAN_REASSERT_HANDOFF_UNAVAILABLE", "stage=entryDutySettle")

        manualFanHandoffIdentity = handoff.identity
        if (!manualFanHandoffStore.prepare(handoff)) {
            manualFanHandoffIdentity = null
            return decline("FAN_REASSERT_HANDOFF_BUSY", "stage=handoffPrepare")
        }
        return null
    }

    /**
     * Performs one
     * tick-driven renewal attempt of Mimir fan ownership for the SMART-staged contract [active] is
     * running under. Dispatched from [fanControlDecision]'s SMART branch via
     * [executeTransactionWork] -- see that call site's own comment for why this runs on
     * [transactionExecutor] instead of being called synchronously from the tick.
     *
     * There is no calling client for this renewal (the daemon acts on its own tick, not in
     * response to a Binder request), so [PublicOwnerRegistry.activeOwnerForRenewal] stands in for
     * the caller-presented `(ownerUid, requestId, token)` triple every other
     * [transitionFanControlModeOnWorker] caller has -- it is fenced exactly like
     * [PublicOwnerRegistry.matchActive] (alive, not mid-transition, bound to a generation). The
     * owner's own identity is cross-checked against [active] below so a renewal can never act
     * across a session boundary it did not itself observe.
     *
     * Returns true only on [FanOwnershipSettleOutcome.SETTLED_MANUAL]. The caller (the tick)
     * discards this return value via `executeTransactionWork`'s fire-and-forget dispatch -- it is
     * exposed for host tests.
     */
    private fun renewMimirFanOwnershipOnWorker(active: ActiveSessionIdentity): Boolean {
        // A renewal state is begun/kept only while the active
        // session's own scope is SYSTEM. Defence in depth now, not the only guard -- until this
        // review, this decline was the ONLY place renewalScopeAdmitted was consulted at all, and
        // fanControlDecision's own SMART branch minted and dispatched a renewal for ANY scope one
        // function up: at 90f4dbd5 a per-game (LAUNCH) session with an external vendor fan change
        // would have been minted a renewal, dispatched, and -- once the OWED, pre-existing
        // LAUNCH-scope fan-toggle gap this comment already named (`setFanControlV6` has no scope
        // check, so the transition re-applies as SYSTEM regardless) closes on the transition side
        // -- silently converted into a standing SYSTEM session by the daemon's own tick, with no
        // player action. fanControlDecision now gates that mint directly on the same predicate, so
        // a non-SYSTEM session's tick never reaches this function with a renewal to attempt in the
        // first place; this decline stays for a renewal that reaches it some other way (a stale
        // in-flight dispatch racing a scope change) and for the fan-toggle gap above, until that
        // closes. No reschedule via afterAttempt: this is not a transient decline to retry, it is
        // a state this renewal should never have been driven for.
        if (!renewalScopeAdmitted(active.scope)) {
            val attemptsAtDecline = fanOwnershipRenewal?.attempts ?: 0
            fanOwnershipRenewal = null
            FanOwnershipObservability.onRenewAttempt(
                active.generation,
                active.transactionId,
                attemptsAtDecline,
                "NOT_SYSTEM_SCOPE",
            )
            return false
        }
        val (owner, token) = publicOwners.activeOwnerForRenewal() ?: run {
            fanOwnershipRenewal = fanOwnershipRenewal?.let {
                if (it.generation == active.generation && it.transactionId == active.transactionId) {
                    FanOwnershipRenewalPolicy.afterAttempt(it, "OWNER_UNAVAILABLE", SystemClock.elapsedRealtime())
                } else it
            }
            FanOwnershipObservability.onRenewAttempt(
                active.generation,
                active.transactionId,
                fanOwnershipRenewal?.attempts ?: 0,
                "OWNER_UNAVAILABLE",
            )
            return false
        }
        if (owner.generation != active.generation ||
            owner.transactionId != active.transactionId ||
            owner.ownerUid != active.ownerUid ||
            // E3 (review finding F3): the owner's own token must still be the exact one this
            // renewal's [active] identity was minted for -- activeOwnerForRenewal's own fences
            // (alive, not in flight, not cancelled) do not by themselves prove it is bound to
            // THIS session rather than a newer one that reused the same generation/transactionId
            // pairing improbably; ownerTokenId is the identity field the rest of this daemon
            // treats as authoritative for "is this the same owner".
            owner.tokenId != active.ownerTokenId
        ) {
            fanOwnershipRenewal = fanOwnershipRenewal?.let {
                if (it.generation == active.generation && it.transactionId == active.transactionId) {
                    FanOwnershipRenewalPolicy.afterAttempt(it, "OWNER_UNAVAILABLE", SystemClock.elapsedRealtime())
                } else it
            }
            FanOwnershipObservability.onRenewAttempt(
                active.generation,
                active.transactionId,
                fanOwnershipRenewal?.attempts ?: 0,
                "OWNER_UNAVAILABLE",
            )
            return false
        }
        val response = transitionFanControlModeOnWorker(
            ownerUid = owner.ownerUid,
            token = token,
            owner = owner,
            active = active,
            requestedMode = PublicFanControlMode.AUTO,
            curveBias = 0,
            callerRequestId = owner.requestId,
            // No caller-presented deadline exists for a daemon-initiated renewal -- there is no
            // Binder client waiting. The renewable lease window is the natural analogue (the same
            // horizon an ordinary session's own lease renews by), and
            // PublicRequestContract.validate only requires the deadline be strictly after `now`,
            // which a positive leaseWindowMs always satisfies.
            callerDeadlineElapsedRealtimeMs = SystemClock.elapsedRealtime() + supervisorPolicy.leaseWindowMs,
            fanIntent = TuningPlanner.FanIntent.MANUAL_OVERRIDE,
        )
        val settled = supervisor.activeSession()
        val outcome = fanOwnershipSettleOutcome(
            transitionSucceeded = response.getBoolean("success"),
            settledFanMode = settled?.appliedTargetContract?.fanMode,
            settledScope = settled?.scope,
            settledProfileId = settled?.appliedTargetContract?.profileId,
            stagedProfileId = active.appliedTargetContract?.profileId,
            settledGeneration = settled?.generation,
            stagedGeneration = active.generation,
        )
        val code = response.getString("code").orEmpty().ifBlank { "FAN_PROFILE_HANDOFF_FAILED" }
        return when (outcome) {
            FanOwnershipSettleOutcome.SETTLED_MANUAL -> {
                val attempt = (fanOwnershipRenewal?.attempts ?: 0) + 1
                fanOwnershipRenewal = null
                FanOwnershipObservability.onRenewAttempt(active.generation, active.transactionId, attempt, code)
                true
            }
            FanOwnershipSettleOutcome.DEGRADED_VENDOR_SMART -> {
                val nowElapsedMs = SystemClock.elapsedRealtime()
                // Defensively falls back to a fresh begin() if fanOwnershipRenewal was somehow
                // already cleared -- the only wired call site (the tick, gated on
                // FanOwnershipRenewalPolicy.due) never dispatches with a null state, but this
                // function must never crash the daemon if that invariant is ever violated.
                val base = fanOwnershipRenewal ?: FanOwnershipRenewalPolicy.begin(
                    generation = active.generation,
                    transactionId = active.transactionId,
                    code = code,
                    nowElapsedMs = nowElapsedMs,
                )
                // Bind retry accounting to durable settled truth. The fan-only amendment keeps
                // this identity unchanged; re-keying also handles recovery-adopted sessions.
                val rekeyed = rekeyRenewalToSettledGeneration(
                    base,
                    settledGeneration = settled?.generation,
                    settledTransactionId = settled?.transactionId,
                )
                val retried = FanOwnershipRenewalPolicy.afterAttempt(rekeyed, code, nowElapsedMs)
                fanOwnershipRenewal = retried
                FanOwnershipObservability.onRenewAttempt(
                    active.generation,
                    active.transactionId,
                    retried.attempts,
                    code,
                )
                // Log the exhaustion once, on the TRANSITION into it
                // (`!rekeyed.exhausted` -- `rekeyed` is this attempt's state BEFORE afterAttempt
                // records it). A later DEGRADED attempt against an already-exhausted renewal
                // legitimately keeps re-entering this branch every EXHAUSTED_HOLD_MS until an
                // external drift or a success clears it -- `retried.exhausted` alone would log
                // every one of those too.
                if (retried.exhausted && !rekeyed.exhausted) {
                    FanOwnershipObservability.onRenewalExhausted(
                        active.generation,
                        active.transactionId,
                        retried.attempts,
                        code,
                    )
                }
                false
            }
            FanOwnershipSettleOutcome.SESSION_LOST -> {
                // I5: this is a renewal attempt, not a fresh apply -- it never restores or
                // re-applies anything itself. The ordinary session-end machinery (the monitor's
                // own post-Restore/NO_ACTIVE_SESSION handling in startMonitor's monitorCycle)
                // already owns what happens next.
                val attempt = (fanOwnershipRenewal?.attempts ?: 0) + 1
                fanOwnershipRenewal = null
                FanOwnershipObservability.onRenewAttempt(
                    active.generation,
                    active.transactionId,
                    attempt,
                    "SESSION_LOST/$code",
                )
                false
            }
        }
    }

    /**
     * Change only the fan under the current Guardian envelope's signed 4/6 authority. The
     * active generation, lease, compute projection and original Stock snapshot are preserved.
     * A refused or failed fan transition never restores/re-applies the compute profile.
     */
    private fun transitionFanControlModeOnWorker(
        ownerUid: Int,
        token: ClientTokenHandle,
        owner: PublicOwnerSnapshot,
        active: ActiveSessionIdentity,
        requestedMode: PublicFanControlMode,
        curveBias: Int,
        callerRequestId: String,
        callerDeadlineElapsedRealtimeMs: Long,
        /**
         * Which direction this transition goes. `MANUAL_OVERRIDE` enters Mimir duty ownership;
         * `PROFILE_AUTO` is the exit, deriving vendor Smart again and settling via
         * `ADOPT_VENDOR_SMART`. This was hardcoded to `MANUAL_OVERRIDE`, which is how a request for
         * AUTO came to drive the device INTO mode 6 (review finding, 2026-08-23).
         */
        fanIntent: TuningPlanner.FanIntent,
    ): Bundle {
        if (!validFanControlSelection(requestedMode, curveBias)) {
            return publicFailure(
                "INVALID_FAN_CURVE",
                "Fan curve bias is outside the supported range or step.",
                callerRequestId,
                callerDeadlineElapsedRealtimeMs,
            )
        }
        val contract = active.appliedTargetContract
            ?: return publicFailure(
                "FAN_CONTROL_UNAVAILABLE",
                "The active profile has no authoritative fan contract.",
                callerRequestId,
                callerDeadlineElapsedRealtimeMs,
            )
        val profileId = contract.profileId
        if (dryRun() || active.scope != ActiveSessionScope.SYSTEM) {
            return publicFailure("FAN_TRANSITION_UNAVAILABLE", "Fan ownership changes require a live SYSTEM session.",
                callerRequestId, callerDeadlineElapsedRealtimeMs)
        }
        val targetFanMode = if (fanIntent == TuningPlanner.FanIntent.MANUAL_OVERRIDE) {
            TuningPlanner.FAN_MANUAL
        } else TuningPlanner.FAN_SMART
        if (!transaction.fanOwnershipTransitionAdmitted(active.generation, active.transactionId, targetFanMode)) {
            return publicFailure("FAN_TRANSITION_UNAVAILABLE", "This session has no signed fan-only transition authority; the active profile is retained.",
                callerRequestId, callerDeadlineElapsedRealtimeMs)
        }
        if (!fanTransitionJournalBound(active)) {
            return publicFailure(
                "FAN_TRANSITION_UNAVAILABLE",
                "The active fan profile is not bound to an exact Guardian restore record.",
                callerRequestId,
                callerDeadlineElapsedRealtimeMs,
            )
        }
        val operationCapabilities = activeFanCapabilities ?: capabilities().also {
            activeFanCapabilities = it
        }

        // Admission precedes every handoff write. The signed fan-only amendment below leaves
        // compute and its session intact, so rejection has no profile restore/re-apply to unwind.
        return FanTransitionOrchestrator.run(
            admit = {
                ApplyAdmission.evaluateSystemWide(
                    thermalTelemetryForAdmission { operationCapabilities },
                    supervisorPolicy,
                    // at SEVERE a fan-mode change that only raises
                    // cooling is admitted (Smart -> Manual full); one that lowers it stays refused;
                    // an unreadable live state refuses. Judged with this request's own curve bias.
                    coolerRearm = {
                        runCatching {
                            CoolerRearmAdmission.fanTransitionRefusal(
                                targetFanMode = targetFanMode,
                                profileId = profileId,
                                live = LiveRearmState.from(probe.telemetry(operationCapabilities)),
                                bias = FanCurveBias(curveBias),
                            )
                        }.getOrElse { CoolerRearmAdmission.LIVE_STATE_UNREADABLE }
                    },
                )
            },
            onRejected = { rejected ->
                Log.w(
                    FAN_CONTROL_TAG,
                    "decline=${rejected.code} stage=preRestoreAdmission sessionRetained=true " +
                        "message=${rejected.message}",
                )
                publicFailure(
                    rejected.code,
                    rejected.message,
                    callerRequestId,
                    callerDeadlineElapsedRealtimeMs,
                )
            },
            proceed = {
                manualFanMonitorLiveness.markRepairing(active, SystemClock.uptimeMillis())
                directHardwareCommands?.bindReassertProfile(profileId)
                if (targetFanMode == TuningPlanner.FAN_SMART) {
                    if (!ownerEligibleForFanHardwareWrite(publicOwners.snapshot(), owner.tokenId) ||
                        SystemClock.elapsedRealtime() >= callerDeadlineElapsedRealtimeMs
                    ) return publicFailure("FAN_OWNER_UNAVAILABLE", "Fan ownership request is no longer live.",
                        callerRequestId, callerDeadlineElapsedRealtimeMs)
                    // Keep the vendor's fan-before-compute settlement barrier on exit, too.
                    val handed = handFanToVendorSmartBound()
                    if (handed.status != CheckedCommandStatus.CONFIRMED) {
                        return publicFailure("FAN_HANDOFF_UNAVAILABLE", handed.detail,
                            callerRequestId, callerDeadlineElapsedRealtimeMs)
                    }
                }
                // Entry only. On the exit path the contract is already FAN_MANUAL, so this is
                // skipped -- there is no manual handoff to capture when the destination is the
                // vendor's own loop. Admission (above) has already run by the time this executes.
                val handoff = if (contract.fanMode == TuningPlanner.FAN_SMART) {
                    // 2026-08-23: this was `productQualificationResolver.qualifiedManualFanPlan(...) ?:
                    // return publicFailure("FAN_CONTROL_UNAVAILABLE", "Manual fan control is not signed for
                    // this profile.")` -- a SECOND, independent copy of the signed-manifest dependency the
                    // same day's landing retired from `manualFanControlAvailable`. No sequence has ever
                    // signed a manual plan, so it resolved null on every device and refused every switch.
                    // Retiring only the availability predicate made the row offer Manual while the actuation
                    // still refused it. See `manualFanHandoffPlanAdmitted`'s KDoc for the full account.
                    val derivedManualPlan = TuningPlanner().plan(
                        profileId = profileId,
                        packageName = active.targetPackage,
                        capabilities = operationCapabilities,
                        fanIntent = TuningPlanner.FanIntent.MANUAL_OVERRIDE,
                    )
                    if (!manualFanHandoffPlanAdmitted(derivedManualPlan, contract, operationCapabilities)) {
                        Log.w(
                            FAN_CONTROL_TAG,
                            "decline=FAN_CONTROL_UNAVAILABLE stage=manualPlan profile=$profileId " +
                                "derivedFan=${derivedManualPlan?.fanMode} contractFan=${contract.fanMode} " +
                                "cpuMatch=${
                                    derivedManualPlan != null &&
                                        manualFanPlanCpuMatchesContract(
                                            derivedManualPlan,
                                            contract,
                                            operationCapabilities,
                                        )
                                } " +
                                "cpuPlan=${derivedManualPlan?.cpuMaxKhz?.toSortedMap()} " +
                                "cpuContract=${contract.cpuQosCeilingKhz.toSortedMap()} " +
                                // Must report the SAME question the gate above asked. Printing the raw
                                // index comparison here while the gate compared devfreq Hz would have made
                                // a passing build look like a declining one, and vice versa.
                                "gpuMatch=${
                                    derivedManualPlan != null &&
                                        manualFanPlanGpuMatchesContract(
                                            derivedManualPlan,
                                            contract,
                                            operationCapabilities,
                                        )
                                } " +
                                "gpuTransport=${if (contract.gpuDevfreqMaxHz != null) "devfreq" else "pwrlevel"}",
                        )
                        return publicFailure(
                            "FAN_CONTROL_UNAVAILABLE",
                            "Manual fan control is unavailable for this profile on this hardware.",
                            callerRequestId,
                            callerDeadlineElapsedRealtimeMs,
                        )
                    }
                    // I3 step (1) (policy 2026-09-07 04:10): from ANY hardware state, hand
                    // the fan to vendor Smart at full PWM in the same checked write BEFORE this
                    // handoff samples telemetry and captures it. A hand-to-4 is cooling-positive by
                    // construction -- the vendor's own regulation loop ends up driving full PWM.
                    // Without this, `enterManualFanMode` refuses unless live `fan_mode == 4` (see
                    // its own kdoc), so the neither-owner state (an external write drifted
                    // `fan_mode` away from 4 -- exactly the measured 02:39->02:58 window) makes every
                    // renewal attempt fail here instead of recovering from it.
                    //
                    // E3 (review finding F3): owner precheck immediately before this write -- alive,
                    // not cancelled, bound to the exact token this request's own registration
                    // holds. `publicOwners.snapshot()` is read FRESH here, not the (possibly stale
                    // by now) `owner` snapshot this function was called with -- only its `tokenId`
                    // is still trusted, as the identity this write must remain bound to.
                    val liveFanModeBeforeHandoff = probe.fanModeRaw()?.toIntOrNull()
                    if (liveFanModeBeforeHandoff != TuningPlanner.FAN_SMART) {
                        if (!ownerEligibleForFanHardwareWrite(publicOwners.snapshot(), owner.tokenId)) {
                            Log.w(
                                FAN_CONTROL_TAG,
                                "decline=FAN_OWNER_UNAVAILABLE stage=ownerPrecheck " +
                                    "liveFanMode=$liveFanModeBeforeHandoff",
                            )
                            return publicFailure(
                                "FAN_OWNER_UNAVAILABLE",
                                "The requesting owner is no longer eligible before the manual handoff.",
                                callerRequestId,
                                callerDeadlineElapsedRealtimeMs,
                            )
                        }
                        val handToVendorSmart = handFanToVendorSmartBound()
                        if (handToVendorSmart.status != CheckedCommandStatus.CONFIRMED) {
                            Log.w(
                                FAN_CONTROL_TAG,
                                "decline=FAN_HANDOFF_UNAVAILABLE stage=handToVendorSmart " +
                                    "liveFanMode=$liveFanModeBeforeHandoff detail=${handToVendorSmart.detail}",
                            )
                            return publicFailure(
                                "FAN_HANDOFF_UNAVAILABLE",
                                "Vendor Smart could not be re-entered before the manual handoff.",
                                callerRequestId,
                                callerDeadlineElapsedRealtimeMs,
                            )
                        }
                        stampVendorFloorLearningSuppression()
                    }
                    val snapshot = runCatching { probe.supervisorTelemetry(operationCapabilities) }.getOrNull()
                    val fresh = snapshot?.let { observed ->
                        FanControlSample(
                            // Same correction as the monitor feeds above: hottest classified CPU zone, not
                            // the user-facing package mean.
                            cpuSafetyTempC = observed.cpuTempC,
                            hottestCpuTempC = observed.cpuTempC,
                            gpuTempC = observed.gpuTempC,
                            vendorFanMode = observed.vendorFanMode,
                            duty = observed.fanDuty,
                            period = observed.fanPeriod,
                            rpm = observed.fanRpm,
                        )
                    }
                    ManualFanHandoffCapture.capture(
                        profileId = profileId,
                        ownerRequestId = owner.requestId,
                        source = ManualFanHandoffSource.Session(active.generation, active.transactionId),
                        // Code-resident since 2026-08-23, was `qualified.minimumDutyPercent` off the
                        // signed manifest. Same value the duty port independently enforces on every write.
                        minimumDutyPercent = FanPwmBounds.MIN_SPIN_PERCENT,
                        sample = fresh ?: return publicFailure(
                            "FAN_HANDOFF_UNAVAILABLE",
                            "Vendor Smart telemetry was unavailable; Auto remains active.".also {
                                Log.w(FAN_CONTROL_TAG, "decline=FAN_HANDOFF_UNAVAILABLE stage=telemetrySample")
                            },
                            callerRequestId,
                            callerDeadlineElapsedRealtimeMs,
                        ),
                        nowElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                    ) ?: return publicFailure(
                        "FAN_HANDOFF_UNAVAILABLE",
                        "Vendor Smart has not settled at the qualified Manual entry duty.".also {
                            Log.w(FAN_CONTROL_TAG, "decline=FAN_HANDOFF_UNAVAILABLE stage=entryDutySettle")
                        },
                        callerRequestId,
                        callerDeadlineElapsedRealtimeMs,
                    )
                } else null

                if (handoff != null) {
                    if (!manualFanHandoffStore.prepare(handoff)) {
                        Log.w(FAN_CONTROL_TAG, "decline=FAN_HANDOFF_BUSY stage=handoffPrepare")
                        return publicFailure("FAN_HANDOFF_BUSY", "Another fan handoff is pending.",
                            callerRequestId, callerDeadlineElapsedRealtimeMs)
                    }
                    manualFanHandoffIdentity = handoff.identity
                }
                val result = try {
                    transaction.transitionAppliedFanBound(active.generation, active.transactionId, targetFanMode) {
                        ownerEligibleForFanHardwareWrite(publicOwners.snapshot(), owner.tokenId) &&
                            supervisor.activeSession()?.let {
                                it.generation == active.generation && it.transactionId == active.transactionId
                            } == true && SystemClock.elapsedRealtime() < callerDeadlineElapsedRealtimeMs
                    }
                } finally {
                    manualFanHandoffStore.clear()
                    manualFanHandoffIdentity = null
                    stampVendorFloorLearningSuppression()
                }
                Log.i(FAN_CONTROL_TAG, "transition fan code=${result.code} success=${result.success} stage=fanAmend")
                // Read durable truth even after a failed fsync acknowledgement. Never advertise an
                // old target if the atomic write actually landed, and never reset lease/budget state.
                val record = (journal.load() as? JournalLoadResult.Loaded)?.record
                val amended = record?.takeIf {
                    it.generation == active.generation && it.transactionId == active.transactionId
                }?.let(AppliedTargetContract::fromAuthenticatedAppliedRecord)
                if (amended == null) {
                    Log.w(FAN_CONTROL_TAG, "transition fan decline=FAN_CONTRACT_DESYNC reason=durableContractUnavailable")
                    return publicFailure("FAN_CONTRACT_DESYNC", "The durable fan contract could not be reconciled.",
                        callerRequestId, callerDeadlineElapsedRealtimeMs)
                }
                if (!supervisor.amendFanContract(active.generation, active.transactionId, amended)) {
                    Log.w(FAN_CONTROL_TAG, "transition fan amendment=rejected action=reconcileAppliedContract")
                    if (!supervisor.reconcileAppliedContract(record)) {
                        return publicFailure("FAN_CONTRACT_DESYNC", "The active session no longer matches the durable fan contract.",
                            callerRequestId, callerDeadlineElapsedRealtimeMs)
                    }
                }
                if (!result.success) {
                    // Entry may have reached mode 6 before verification or durable amendment failed.
                    // If Smart is still the durable target, return ONLY fan authority to Smart.
                    // Compute, owner, journal, generation and the original Stock envelope remain live.
                    if (amended.fanMode == TuningPlanner.FAN_SMART &&
                        ownerEligibleForFanHardwareWrite(publicOwners.snapshot(), owner.tokenId)
                    ) {
                        try {
                            val fallback = handFanToVendorSmartBound()
                            if (fallback.status == CheckedCommandStatus.CONFIRMED) {
                                // Smart entry can replay vendor compute policy as well. Reconcile
                                // the same authenticated targets after its settlement barrier.
                                transaction.transitionAppliedFanBound(active.generation, active.transactionId,
                                    TuningPlanner.FAN_SMART) {
                                    ownerEligibleForFanHardwareWrite(publicOwners.snapshot(), owner.tokenId) &&
                                        supervisor.activeSession()?.let {
                                            it.generation == active.generation && it.transactionId == active.transactionId
                                        } == true && SystemClock.elapsedRealtime() < callerDeadlineElapsedRealtimeMs
                                }
                            }
                        } finally {
                            stampVendorFloorLearningSuppression()
                        }
                    }
                    return publicFailure(result.code, result.message, callerRequestId, callerDeadlineElapsedRealtimeMs)
                }
                fanController.reset()
                manualFanMonitorLiveness.markHealthy(active, SystemClock.uptimeMillis())
                val selected = fanControlSelections.update(supervisor.activeSession() ?: active, requestedMode, curveBias)
                    ?: return publicFailure(
                        "INVALID_FAN_CURVE",
                        "Fan curve bias is outside the supported range or step.",
                        callerRequestId,
                        callerDeadlineElapsedRealtimeMs,
                    )
                return publicSuccess(
                    code = "FAN_CONTROL_UPDATED",
                    message = "Mimir fan ownership settled.",
                    callerRequestId = callerRequestId,
                    callerDeadlineElapsedRealtimeMs = callerDeadlineElapsedRealtimeMs,
                ).putFanControlSelection(selected)
            },
        )
    }

    /**
     * **Policy 2026-09-07 04:10 supersedes this function's 2026-08-22 (R2/R3) description.**
     * During an active Mimir session, vendor fan changes trigger renewal of Mimir control.
     * Under R2/R3, the common case for a SMART-staged contract
     * was [FanOwnershipSettleRoute.ADOPT_VENDOR_SMART] -- nothing to hand off, staged result
     * returned unmodified. That is now the DEGRADED case.
     *
     * **The 2026-09-07 update supersedes this KDoc's own next paragraph, which described
     * `TRANSITION_TO_MANUAL` running the signed mode-6 transition inline from this worker.** D2
     * (the same update) moved manual entry onto the select path itself
     * ([selectSystemProfileOnWorker]'s `applyBody` now applies MANUAL directly from Stock, in the
     * SAME generation as the profile apply). A SMART-staged contract reaching this worker
     * therefore already reflects D2's own MANUAL attempt on THIS apply having failed or been
     * ineligible -- there is nothing left to transition here. The ordinary case for a SMART-staged
     * contract on a device where manual fan control is available is now
     * [FanOwnershipSettleRoute.SCHEDULE_RENEWAL]: return `stagedResult` UNMODIFIED (I4: never
     * lose the profile over the fan) and begin [fanOwnershipRenewal] for a later tick
     * ([renewMimirFanOwnershipOnWorker], P3) to retry entry, carrying D2's own recorded fallback
     * code via [selectPathFanEntryRenewalCode] ([SELECT_PATH_FAN_ENTRY_NOT_ATTEMPTED_CODE] when
     * D2 never attempted MANUAL at all). Logged via `FanOwnershipObservability.onSettleDeferred`,
     * same as [FanOwnershipSettleRoute.ADOPT_VENDOR_SMART]'s own degrade. Never calls the
     * profile-rollback helper below -- see that helper's own KDoc for the routes that still use
     * it.
     *
     * The Odin's own [FanOwnershipSettleRoute.ADOPT_ALREADY_OWNED] case (SPORT entered directly,
     * TR-08 territory, unchanged by these updates) is unmoved: ownership is already settled by
     * the single apply transaction above, and this only records Auto as the neutral Mimir curve --
     * no second transaction, no handoff.
     */
    private fun settleMimirFanOwnershipAfterProfileApplyOnWorker(
        requestId: String,
        deadlineElapsedRealtimeMs: Long,
        token: ClientTokenHandle,
        ownerUid: Int,
        stagedResult: TransactionResult,
    ): TransactionResult {
        val staged = supervisor.activeSession()
            ?: return stagedResult.copy(
                success = false,
                applied = false,
                code = "FAN_PROFILE_SESSION_MISSING",
                message = "The staged profile lost its active session before fan ownership settled.",
            )
        val contract = staged.appliedTargetContract
            ?: return rejectStagedFanOwnership(
                staged = staged,
                stagedResult = stagedResult,
                code = "FAN_PROFILE_CONTRACT_MISSING",
                message = "The staged profile has no authoritative fan contract.",
            )
        when (
            fanOwnershipSettleRoute(
                contractFanMode = contract.fanMode,
                fanAxisAvailable = TuningAxis.FAN in (
                    activeFanCapabilities ?: capabilities().also { activeFanCapabilities = it }
                    ).supportedAxes,
                manualFanControlAvailable = { manualFanControlAvailable(staged) },
            )
        ) {
            // A chassis with no fan axis: the applied CPU/GPU vector IS the whole profile. Same
            // "no second transaction, no handoff" disposition as ADOPT_ALREADY_OWNED below, and
            // deliberately a separate route rather than folded into it, because the two reach it
            // for opposite reasons -- one owns the fan already, one has no fan to own -- and a
            // single label would make the next reader of a device log guess which.
            FanOwnershipSettleRoute.ADOPT_NO_FAN_AXIS -> return stagedResult
            // the vendor's own SMART loop already owns duty (R2/R3) --
            // nothing to hand off, no second transaction. Same disposition as ADOPT_NO_FAN_AXIS
            // immediately above, for the same reason, kept as its own branch rather than folded
            // into it because the two reach it for opposite facts about the fan axis (absent vs.
            // present-and-vendor-owned) and a single label would make the next log reader guess
            // which. See FanOwnershipSettleRoute.ADOPT_VENDOR_SMART's own KDoc for the rollback
            // this closes: before this branch existed, every SMART-carrying apply on BOTH devices
            // fell through to REJECT_OWNERSHIP_UNAVAILABLE and rolled back to Stock.
            FanOwnershipSettleRoute.ADOPT_VENDOR_SMART -> return stagedResult
            FanOwnershipSettleRoute.ADOPT_ALREADY_OWNED -> {
                // No explicit registration needed: ActiveFanControlSelectionStore.current()
                // already derives this profile's setting from its own durable per-profile
                // preference (Auto/0 the first time this profileId is ever seen). Forcing an
                // update(..., AUTO, 0) here on every ordinary apply -- this is the common case
                // since Odin 3 now enters SPORT directly -- would overwrite that
                // profile's persisted Manual selection on every switch back to it, which is
                // the regression where a manual selection failed to survive a profile change.
                return stagedResult
            }
            FanOwnershipSettleRoute.REJECT_STAGING_INVALID -> return rejectStagedFanOwnership(
                staged = staged,
                stagedResult = stagedResult,
                code = "FAN_PROFILE_STAGING_INVALID",
                message = "The staged profile did not reach the qualified Smart handoff state.",
            )
            FanOwnershipSettleRoute.REJECT_OWNERSHIP_UNAVAILABLE -> return rejectStagedFanOwnership(
                staged = staged,
                stagedResult = stagedResult,
                code = "FAN_PROFILE_OWNERSHIP_UNAVAILABLE",
                message = "The qualified GPU-led fan controller is unavailable on this device state.",
            )
            // D3 : a SMART
            // contract reaching here means D2's own MANUAL attempt on THIS apply
            // (selectSystemProfileOnWorker's applyBody) already failed or was ineligible -- there
            // is nothing left to transition inline from this worker any more (see this function's
            // own KDoc, and FanOwnershipSettleRoute.SCHEDULE_RENEWAL's, for the full account of
            // what this route used to do before D2/D3). Return the staged result UNMODIFIED (I4)
            // and begin a generation-keyed renewal for renewMimirFanOwnershipOnWorker (P3) to
            // retry on a later tick, carrying D2's own recorded fallback code.
            FanOwnershipSettleRoute.SCHEDULE_RENEWAL -> {
                val deferredCode = selectPathFanEntryRenewalCode(selectPathFanEntryFallbackCode)
                selectPathFanEntryFallbackCode = null
                fanOwnershipRenewal = FanOwnershipRenewalPolicy.begin(
                    generation = staged.generation,
                    transactionId = staged.transactionId,
                    code = deferredCode,
                    nowElapsedMs = SystemClock.elapsedRealtime(),
                )
                FanOwnershipObservability.onSettleDeferred(
                    deferredCode,
                    "Mimir fan ownership was not entered on this apply.",
                )
                return stagedResult
            }
        }
    }

    /**
     * Rolls a just-applied profile back because fan ownership could not settle.
     *
     * **This is a fourth restore family, and until 2026-08-22 it was the one still silent.**
     * [GuardianRestoreObservability]'s own kdoc records that two of three restore paths emitted
     * nothing and that the silence cost a day of work at the wrong mechanism; `onServiceRestore`
     * was added for `OWNER_DEATH_RESTORE` and `STARTUP_RECOVERY_RESTORE`. This path was missed,
     * and it is the one that fired on the reference Nova
     * `[V] logcat 08-22 03:37:19.447 -> 03:37:28.771, device test unit`: nine seconds of real
     * apply-then-rollback with **no `restore reason=` line anywhere in 25k lines of logcat**. The
     * rollback had to be inferred from `am_foreground_service_stop` bracketing plus a sysfs read
     * of the CPU/GPU nodes afterwards — which is exactly the "absence of the only restore line was
     * read as absence of a restore" trap that kdoc exists to close.
     *
     * The `reason=` token is shared with the other three so one grep still catches the family.
     */
    private fun rejectStagedFanOwnership(
        staged: ActiveSessionIdentity,
        stagedResult: TransactionResult,
        code: String,
        message: String,
    ): TransactionResult {
        stopMonitor()
        val restored = runCatching {
            restoreBoundStamped(staged.generation, staged.transactionId)
        }.getOrElse { error ->
            TransactionResult(
                success = false,
                applied = false,
                restored = false,
                code = "FAN_PROFILE_RESTORE_EXCEPTION",
                message = error.message ?: "The staged fan profile could not be restored.",
                requestId = staged.requestId,
            )
        }
        GuardianRestoreObservability.onServiceRestore(
            cause = "FAN_STAGING_REJECT_RESTORE/$code",
            generation = staged.generation,
            transactionId = staged.transactionId,
            restored = restored.restored,
            code = restored.code,
            settlement = restored.message,
        )
        observePostTransactionDisposition(restored)
        if (restored.restored && journal.load() == JournalLoadResult.Empty) {
            settleSystemWideStockRuntime()
        }
        return stagedResult.copy(
            success = false,
            applied = false,
            restored = restored.restored,
            code = if (restored.restored) code else restored.code,
            message = if (restored.restored) message else restored.message,
        )
    }

    private fun selectSystemProfileOnWorker(
        requestId: String,
        deadlineElapsedRealtimeMs: Long,
        token: ClientTokenHandle,
        profileId: String,
        ownerUid: Int,
    ): TransactionResult {
        val loaded = journal.load()
        val active = supervisor.activeSession()
        val activeRecord = (loaded as? JournalLoadResult.Loaded)?.record
        val exactActive = activeRecord?.takeIf { record ->
            record.phase == TransactionPhase.APPLIED &&
                active != null &&
                active.generation == record.generation &&
                active.transactionId == record.transactionId &&
                active.requestId == record.requestId
        }
        if (loaded is JournalLoadResult.Corrupt ||
            loaded is JournalLoadResult.Loaded && exactActive == null ||
            loaded == JournalLoadResult.Empty && active != null
        ) {
            return TransactionResult(
                success = false,
                applied = false,
                restored = false,
                code = "SESSION_NOT_SETTLED",
                message = "System-wide selection requires exact Stock or an exact active journal session.",
                requestId = requestId,
                requestDeadlineElapsedRealtimeMs = deadlineElapsedRealtimeMs,
            )
        }

        // Same defect family as FAN_PROFILE_STAGING_INVALID, one field over, and found while
        // fixing it. This asks "is the live session Mimir's own applied system profile?" and used
        // a fan-ownership test as its proxy -- true on the Odin, where entry signs SPORT, and
        // false forever on a chassis with no fan axis, whose contract correctly carries no mode.
        // Left alone, the Nova answers "no" to its own live profile: re-selecting the profile
        // already active would miss SYSTEM_PROFILE_UNCHANGED and instead run a full restore +
        // reapply -- two Guardian transactions and ~9 s of real hardware writes to reach the state
        // it was already in.
        //
        // Widened by exactly the second leg the settle route uses, and no further: a device with a
        // live fan axis still requires Mimir to own the duty, so every fan-transition meaning of
        // this predicate is untouched and the Odin's answer is bit-identical.
        // `appliedTargetContract != null` is required explicitly, not incidentally. Without it a
        // contract-less SYSTEM session would reach the predicate with a null fanMode and, on a
        // fan-less chassis, answer "this is my profile" -- claiming a profile is already active
        // while holding no authoritative contract for it, and skipping the healing
        // restore-then-reapply. That case is unreachable today (adoption refuses a record with no
        // reconstructable contract, and the recovery identity is RECOVERY-scope), but the old
        // `null in MIMIR_DUTY_OWNED_FAN_MODES` spelling was fail-closed there by accident and this
        // must not quietly become fail-open.
        val mimirOwnedSystemSession = active?.scope == ActiveSessionScope.SYSTEM &&
            active.appliedTargetContract != null &&
            activeSystemSessionIsMimirOwned(active.appliedTargetContract.fanMode)
        val exactMimirOwnedProfile = exactActive != null &&
            mimirOwnedSystemSession &&
            exactActive.profileId == profileId
        val needsApply = profileId != SystemWideProfileTransition.STOCK_PROFILE_ID &&
            !exactMimirOwnedProfile
        val admittedStartId = if (needsApply && exactActive == null) {
            when (val admission = startedServiceLifetime.admitApply(SystemClock.elapsedRealtime())) {
                is StartedServiceLifetime.ApplyAdmission.Admitted -> {
                    cancelPendingAdmissionExpiry()
                    admission.startId
                }
                StartedServiceLifetime.ApplyAdmission.NotStarted,
                StartedServiceLifetime.ApplyAdmission.Expired,
                StartedServiceLifetime.ApplyAdmission.Busy,
                -> return TransactionResult(
                    success = false,
                    applied = false,
                    restored = false,
                    code = "SERVICE_LIFETIME_NOT_STARTED",
                    message = "Start the bounded active-session service lifetime before system-wide apply.",
                    requestId = requestId,
                    requestDeadlineElapsedRealtimeMs = deadlineElapsedRealtimeMs,
                )
            }
        } else {
            null
        }

        var registration: PublicOwnerRegistration.Registered? = null
        val result = SystemWideProfileTransition.execute(
            requestedProfileId = profileId,
            activeMutation = exactActive != null,
            activeSystemProfileId = exactActive?.profileId?.takeIf { mimirOwnedSystemSession },
            restore = {
                val record = (journal.load() as? JournalLoadResult.Loaded)?.record
                    ?: return@execute TransactionResult(
                        true,
                        false,
                        false,
                        "NO_ACTIVE_SESSION",
                        "No active transaction exists.",
                    )
                stopMonitor()
                // Now folded into restoreBoundStamped (I-2): same reasoning
                // as every other explicit restore path -- production restores
                // execute through the guardian process's own port instance, whose clock this
                // service never observes, so the DAEMON's own suppression window must be stamped
                // here directly. restoreBoundStamped already guards the stamp on `.restored`.
                restoreBoundStamped(record.generation, record.transactionId)
            },
            verifiedStock = { journal.load() == JournalLoadResult.Empty },
            onVerifiedStock = ::settleSystemWideStockRuntime,
            onUnverifiedRestore = ::observePostTransactionDisposition,
            apply = {
                val now = SystemClock.elapsedRealtime()
                val hardLeaseDeadline = PublicRequestContract.hardLeaseDeadline(ownerHardLeasePolicy, now)
                    ?: return@execute TransactionResult(
                        false,
                        false,
                        false,
                        "LEASE_POLICY_UNAVAILABLE",
                        "Product hard-lease duration is unavailable.",
                        requestId = requestId,
                    )
                val registered = when (
                    val owner = publicOwners.registerApply(
                        ownerUid = ownerUid,
                        requestId = requestId,
                        token = token,
                    )
                ) {
                    is PublicOwnerRegistration.Registered -> owner
                    is PublicOwnerRegistration.Busy -> return@execute TransactionResult(
                        false,
                        false,
                        false,
                        "APPLY_BUSY",
                        "Another exact owner/request is active.",
                        requestId = requestId,
                    )
                    PublicOwnerRegistration.DeadToken -> return@execute TransactionResult(
                        false,
                        false,
                        false,
                        "CLIENT_TOKEN_DEAD",
                        "Client token died before journal capture.",
                        requestId = requestId,
                    )
                }
                registration = registered
                // P1-2: set by the admission when the thermal status trip was waived for this
                // candidate; the body below then owes the Manual plan the admission judged.
                var coolerRearmWaived = false
                runApplyWithSessionCommit(
                    profileId = profileId,
                    ownerUid = ownerUid,
                    ownerTokenId = registered.owner.tokenId,
                    targetPackage = SystemWideProfileTransition.TARGET_PACKAGE_ID,
                    requestIdentity = requestId,
                    cancelToken = registered.cancelToken,
                    isOwnerAlive = {
                        publicOwners.snapshot()?.let { owner ->
                            owner.tokenId == registered.owner.tokenId && owner.alive
                        } == true
                    },
                    requiresLabCalibrationBaseline = false,
                    sessionScope = ActiveSessionScope.SYSTEM,
                    // F5: the same two gates the body below applies before it chooses the manual
                    // entry over autoApply(), so admission judges the plan that will actually run.
                    admissionFanIntent = { live ->
                        if (manualFanPlanEligible(profileId, live) && !dryRun()) {
                            TuningPlanner.FanIntent.MANUAL_OVERRIDE
                        } else {
                            TuningPlanner.FanIntent.PROFILE_AUTO
                        }
                    },
                    onCoolerRearmWaived = { coolerRearmWaived = true },
                ) { operationCapabilities, isCancelled, onAppliedAdopt ->
                    // D2 : apply
                    // MANUAL directly from Stock, in the SAME generation as this profile apply,
                    // instead of staging Smart and handing off to a second Guardian transaction
                    // from the settle worker (F1: that used to cost three transactions per apply,
                    // with a post-restore refusal landing the profile at Stock). This lambda is
                    // the applyBody `runApplyWithSessionCommit` invokes AFTER `admit` and the
                    // cancel/owner fence above -- no hardware write happens before that point.
                    selectPathFanEntryFallbackCode = null
                    fun autoApply(): TransactionResult = transaction.apply(
                        profileId = profileId,
                        packageName = SystemWideProfileTransition.TARGET_PACKAGE_ID,
                        capabilities = operationCapabilities,
                        dryRun = dryRun(),
                        requestId = requestId,
                        requestDeadlineElapsedRealtimeMs = deadlineElapsedRealtimeMs,
                        hardLeaseDeadlineElapsedRealtimeMs = hardLeaseDeadline,
                        isCancelled = isCancelled,
                        onAppliedAdopt = onAppliedAdopt,
                        onAdoptionRollback = ::clearProvisionalAdoption,
                    )
                    // P1-2 (fence review, 2026-10-14): once the thermal waiver admitted this apply as
                    // structurally cooler, the MANUAL plan it judged is the only plan that may run.
                    // The auto fallback would hand the fan to vendor Smart, which the admission could
                    // not see and which ran 37-47 % at 103 C on the Odin. Refuse instead; the
                    // session stays unarmed and the player may try again.
                    fun fallbackOrRefuse(code: String): TransactionResult {
                        selectPathFanEntryFallbackCode = code
                        if (!coolerRearmWaived) return autoApply()
                        Log.w(FAN_CONTROL_TAG, "fanEntry=refused stage=coolerRearmFallback code=$code")
                        return TransactionResult(
                            success = false,
                            applied = false,
                            restored = false,
                            code = COOLER_REARM_FALLBACK_REFUSED_CODE,
                            message = "The profile was admitted while hot only as its Manual fan plan; its fallback ($code) was refused.",
                            requestId = requestId,
                        )
                    }

                    if (!manualFanPlanEligible(profileId, operationCapabilities)) {
                        selectPathFanEntryFallbackCode = SELECT_PATH_FAN_ENTRY_NOT_ATTEMPTED_CODE
                        return@runApplyWithSessionCommit autoApply()
                    }

                    // LAB dry-run guard: decline the MANUAL entry attempt on a dry
                    // run BEFORE any live hardware read or write. `dryRun()` defaults true on a
                    // DIAGNOSTICS build, and the ONLY place that used to consult it was
                    // `transaction.apply(dryRun = dryRun())` -- well after the live
                    // `probe.fanModeRaw()` read and the `handFanToVendorSmartForRenewal()`
                    // hardware write immediately below, both of which ran for real on a dry-run
                    // build. `autoApply()` is itself dry-run-safe (its own `transaction.apply`
                    // call already threads `dryRun()` through) -- this only skips the manual
                    // entry's OWN live reads/writes, which have no dry-run gate of their own.
                    if (dryRun()) {
                        selectPathFanEntryFallbackCode = SELECT_PATH_FAN_ENTRY_NOT_ATTEMPTED_CODE
                        return@runApplyWithSessionCommit autoApply()
                    }

                    // Same live read the transition entry uses (~:4670, `liveFanModeBeforeHandoff`).
                    val liveFanModeBeforeEntry = probe.fanModeRaw()?.toIntOrNull()
                    if (liveFanModeBeforeEntry != TuningPlanner.FAN_SMART) {
                        val handToVendorSmart = directHardwareCommands?.handFanToVendorSmartForRenewal()
                        if (handToVendorSmart?.status != CheckedCommandStatus.CONFIRMED) {
                            val code = "FAN_ENTRY_HAND_TO_VENDOR_SMART_FAILED"
                            Log.w(FAN_CONTROL_TAG, "fanEntry=skipped stage=handToVendorSmart code=$code")
                            return@runApplyWithSessionCommit fallbackOrRefuse(code)
                        }
                        // E4 (spec §5): every Mimir-initiated fan_mode write suspends vendor-floor
                        // learning for VENDOR_FLOOR_LEARNING_SUPPRESSION_MS -- this hand-to-4 wrote
                        // full PWM first, and observeVendorSmartDuty keeps a per-bucket MAX.
                        stampVendorFloorLearningSuppression()
                    }

                    // Same FanControlSample/telemetry shape as the transition entry (~:4694).
                    val snapshot = runCatching { probe.supervisorTelemetry(operationCapabilities) }.getOrNull()
                    val fresh = snapshot?.let { observed ->
                        FanControlSample(
                            cpuSafetyTempC = observed.cpuTempC,
                            hottestCpuTempC = observed.cpuTempC,
                            gpuTempC = observed.gpuTempC,
                            vendorFanMode = observed.vendorFanMode,
                            duty = observed.fanDuty,
                            period = observed.fanPeriod,
                            rpm = observed.fanRpm,
                        )
                    }
                    if (fresh == null) {
                        val code = "FAN_ENTRY_TELEMETRY_SAMPLE_FAILED"
                        Log.w(FAN_CONTROL_TAG, "fanEntry=skipped stage=telemetrySample code=$code")
                        return@runApplyWithSessionCommit fallbackOrRefuse(code)
                    }

                    val baseline = ManualFanHandoffCapture.capture(
                        profileId = profileId,
                        ownerRequestId = requestId,
                        source = ManualFanHandoffSource.FreshApply,
                        minimumDutyPercent = FanPwmBounds.MIN_SPIN_PERCENT,
                        sample = fresh,
                        nowElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                    )
                    if (baseline == null) {
                        val code = "FAN_ENTRY_HANDOFF_CAPTURE_FAILED"
                        Log.w(FAN_CONTROL_TAG, "fanEntry=skipped stage=entryDutySettle code=$code")
                        return@runApplyWithSessionCommit fallbackOrRefuse(code)
                    }

                    if (!manualFanHandoffStore.prepare(baseline)) {
                        val code = "FAN_ENTRY_HANDOFF_BUSY"
                        Log.w(FAN_CONTROL_TAG, "fanEntry=skipped stage=handoffBusy code=$code")
                        return@runApplyWithSessionCommit fallbackOrRefuse(code)
                    }
                    manualFanHandoffIdentity = baseline.identity
                    val manualResult = try {
                        transaction.apply(
                            profileId = profileId,
                            packageName = SystemWideProfileTransition.TARGET_PACKAGE_ID,
                            capabilities = operationCapabilities,
                            dryRun = dryRun(),
                            fanIntent = TuningPlanner.FanIntent.MANUAL_OVERRIDE,
                            requestId = requestId,
                            requestDeadlineElapsedRealtimeMs = deadlineElapsedRealtimeMs,
                            hardLeaseDeadlineElapsedRealtimeMs = hardLeaseDeadline,
                            isCancelled = isCancelled,
                            onAppliedAdopt = onAppliedAdopt,
                            onAdoptionRollback = ::clearProvisionalAdoption,
                        )
                    } finally {
                        manualFanHandoffStore.clear()
                        manualFanHandoffIdentity = null
                    }

                    if (manualResult.applied) return@runApplyWithSessionCommit manualResult
                    val journalEmptyAfterManualFailure = journal.load() == JournalLoadResult.Empty
                    when (
                        selectPathFanEntryFallback(manualResult.applied, journalEmptyAfterManualFailure)
                    ) {
                        SelectPathFanEntryFallback.FALLBACK_TO_AUTO -> {
                            clearAbandonedApplyLiveness()
                            Log.w(
                                FAN_CONTROL_TAG,
                                "fanEntry=MANUAL_FAILED fallback=AUTO code=${manualResult.code}",
                            )
                            fallbackOrRefuse(manualResult.code)
                        }
                        // journal not Empty: SystemWideProfileTransition.execute's own
                        // verifiedStock()/restore() tail already handles a non-Empty journal on a
                        // failed apply -- return the MANUAL failure unchanged, do not race it.
                        SelectPathFanEntryFallback.RETURN_MANUAL_RESULT -> manualResult
                    }
                }.also(::observePostTransactionDisposition)
            },
        )
        registration?.let { registered ->
            val retainOwner = result.applied &&
                publicOwners.snapshot()?.let { it.tokenId == registered.owner.tokenId && it.alive } == true &&
                supervisor.activeSession()?.ownerTokenId == registered.owner.tokenId
            publicOwners.completeApply(registered.owner.tokenId, retainActiveOwner = retainOwner)
        }

        val settledResult = if (
            result.applied && profileId != SystemWideProfileTransition.STOCK_PROFILE_ID
        ) {
            settleMimirFanOwnershipAfterProfileApplyOnWorker(
                requestId = requestId,
                deadlineElapsedRealtimeMs = deadlineElapsedRealtimeMs,
                token = token,
                ownerUid = ownerUid,
                stagedResult = result,
            )
        } else {
            result
        }

        val journalAfter = journal.load()
        when {
            (settledResult.applied || settledResult.code == "SYSTEM_PROFILE_UNCHANGED") &&
                journalAfter is JournalLoadResult.Loaded &&
                supervisor.activeSession()?.scope == ActiveSessionScope.SYSTEM -> {
                if (admittedStartId != null) startedServiceLifetime.markActive(admittedStartId)
            }
            journalAfter == JournalLoadResult.Empty -> {
                admittedStartId?.let(startedServiceLifetime::settleApplying)
                startedServiceLifetime.settleActiveOrRecovery()
                requestTerminalSettlement()
            }
            else -> startedServiceLifetime.markRecovery()
        }
        return settledResult
    }

    private fun settleSystemWideStockRuntime() {
        supervisor.clearSession()
        publicOwners.snapshot()?.let { publicOwners.clearIfToken(it.tokenId) }
        labPreMutationBaselineHandoff.clear()
        labApplyHold.clear()
        stopMonitor()
        cancelRecoveryRetry()
    }

    private fun publicRequestFailure(
        requestId: String,
        deadlineElapsedRealtimeMs: Long,
    ): Bundle? {
        val failure = PublicRequestContract.validate(
            requestId = requestId,
            deadlineElapsedRealtimeMs = deadlineElapsedRealtimeMs,
            nowElapsedRealtimeMs = SystemClock.elapsedRealtime(),
        ) ?: return null
        return publicFailure(
            code = failure.code,
            message = failure.message,
            callerRequestId = requestId,
            callerDeadlineElapsedRealtimeMs = deadlineElapsedRealtimeMs,
        )
    }

    private fun failAdmittedPublicApply(
        admittedStartId: Int,
        code: String,
        message: String,
        callerRequestId: String,
        callerDeadlineElapsedRealtimeMs: Long,
    ): Bundle {
        startedServiceLifetime.settleApplying(admittedStartId)
        requestTerminalSettlement()
        return publicFailure(
            code = code,
            message = message,
            callerRequestId = callerRequestId,
            callerDeadlineElapsedRealtimeMs = callerDeadlineElapsedRealtimeMs,
        )
    }

    /**
     * The one settlement of a public V4 apply, whoever runs it: the Binder thread when the apply answered (or never
     * started), the transaction worker when the Binder caller gave up on a running apply (see [BoundedTransactionWorker]).
     * Disposition first, then the owner, then the started lifetime.
     */
    private fun settlePublicApplyResult(
        admittedStartId: Int,
        ownerTokenId: Long,
        result: TransactionResult,
    ) {
        observePostTransactionDisposition(result)
        val ownerAfter = publicOwners.snapshot()
        val retainOwner = result.applied &&
            ownerAfter?.tokenId == ownerTokenId &&
            ownerAfter.alive &&
            supervisor.activeSession()?.ownerTokenId == ownerTokenId
        publicOwners.completeApply(ownerTokenId, retainOwner)
        reconcileStartedLifetimeAfterPublicApply(admittedStartId, result, retainOwner)
    }

    /**
     * A failed apply's settlement threw (or could not be queued), so nothing established what the journal and the
     * supervisor now say. The existing fail-closed state is RECOVERY: the started service stays up and the recovery path
     * re-reads the journal, instead of the owner and the lifetime being left half settled. Every step is independent and
     * contained: this runs where the failure was caught and must itself never throw.
     */
    private fun holdAfterUnsettledFailedApply(ownerTokenId: Long) {
        runCatching { publicOwners.completeApply(ownerTokenId, false) }
        runCatching { startedServiceLifetime.markRecovery() }
        runCatching { requestTerminalSettlement() }
    }

    private fun reconcileStartedLifetimeAfterPublicApply(
        admittedStartId: Int,
        result: TransactionResult,
        retainOwner: Boolean,
    ) {
        when (journal.load()) {
            JournalLoadResult.Empty -> startedServiceLifetime.settleApplying(admittedStartId)
            is JournalLoadResult.Corrupt -> startedServiceLifetime.markRecovery()
            is JournalLoadResult.Loaded -> {
                if (result.applied && retainOwner && supervisor.activeSession() != null) {
                    startedServiceLifetime.markActive(admittedStartId)
                } else {
                    startedServiceLifetime.markRecovery()
                }
            }
        }
        requestTerminalSettlement()
    }

    private fun publicSuccess(
        code: String,
        message: String,
        callerRequestId: String,
        callerDeadlineElapsedRealtimeMs: Long,
    ): Bundle = publicStatusBundle(
        success = true,
        code = code,
        message = message,
        callerRequestId = callerRequestId,
        callerDeadlineElapsedRealtimeMs = callerDeadlineElapsedRealtimeMs,
        truth = publicSessionTruth(),
    ).withApiVersion()

    private fun publicFailure(
        code: String,
        message: String,
        callerRequestId: String,
        callerDeadlineElapsedRealtimeMs: Long,
    ): Bundle = publicStatusBundle(
        success = false,
        code = code,
        message = message,
        callerRequestId = callerRequestId,
        callerDeadlineElapsedRealtimeMs = callerDeadlineElapsedRealtimeMs,
        truth = publicSessionTruth(),
    ).withApiVersion()

    private fun Bundle.withApiVersion(): Bundle = apply {
        putInt("apiVersion", API_VERSION)
    }

    /**
     * The only foreground/start release gate. A false/throwing observation is a retained
     * obligation, never permission to stop.
     *
     * **The reads run off the main thread.** Every transaction ends here, and the evaluation loads both durable
     * journals (a file read plus authentication), which used to run as a main-looper message after every apply,
     * restore and tick. They now run on [settlementExecutor], a thread of their own (never [transactionExecutor],
     * whose every task ends by requesting a settlement and would re-post forever). Only the stop itself returns to
     * the main thread, because [foreground] is a plain main-thread flag.
     *
     * **What actually guards the stop.** Android's `stopSelfResult(id)` only refuses an id that is no longer the latest
     * start, so it protects against a start whose `recordStart` came AFTER the snapshot and nothing else. A start
     * that was recorded before the snapshot but had not yet reached `beginPending` (`onStartCommand` runs on main and
     * does durable loads in between) reads as IDLE with `latestStartId == N+1`, and `stopSelf(N+1)` would be accepted.
     * So the decision is taken a second time on the main thread, at the instant of the stop: the post re-reads the
     * lifetime phases and every in-memory obligation, and stops only if the same start id is still the one that may be
     * stopped ([StartedServiceTerminalGate.mayStopNow]). Because main is single-threaded, an `onStartCommand` that was
     * in flight when the snapshot was taken has finished, and its PENDING phase is visible, by the time the post runs.
     * Only the two disk-backed reads (the journals' emptiness and the calibration status) are carried over from the
     * first pass: a mutation that could change them also holds an owner, a session or transaction work, all re-read.
     */
    private fun requestTerminalSettlement() {
        if (destroyed.get()) return
        if (!terminalSettlementPosted.compareAndSet(false, true)) return
        try {
            settlementExecutor.execute { evaluateTerminalSettlement() }
        } catch (_: RuntimeException) {
            // Shut down with the service: nothing is left to settle.
            terminalSettlementPosted.set(false)
        }
    }

    private fun evaluateTerminalSettlement() {
        terminalSettlementPosted.set(false)
        if (destroyed.get()) return
        val lifetime = startedServiceLifetime.snapshot()
        val journalEmpty = runCatching { journal.load() is JournalLoadResult.Empty }
            .getOrDefault(false)
        val calibrationInactive = runCatching {
            calibrationRunner?.status()?.disposition != CalibrationDisposition.OBSERVING
        }.getOrDefault(false)
        val fpsTruth = runCatching { fpsLimiterSession.terminalTruth() }.getOrElse {
            FpsLimiterTerminalTruth(
                journalEmpty = false,
                ownerAbsent = false,
                lifetimeIdle = false,
            )
        }
        val fpsJournalEmpty = fpsTruth.journalEmpty
        val truth = terminalTruthNow(
            lifetime = lifetime,
            journalEmpty = journalEmpty && fpsJournalEmpty,
            calibrationInactive = calibrationInactive,
            fpsSessionIdle = fpsTruth.ownerAbsent && fpsTruth.lifetimeIdle && fpsTruth.expiryIdle,
        )
        // a hold obligation with no watch to discharge it (a watch stopped
        // before its first tick settled, a start that found the marker) is re-armed here, never
        // stopped. The watch's own tick stops it again at once if a session is active.
        if (!truth.postControlHoldInactive && postControlFuture == null && truth.supervisorSessionAbsent) {
            Log.i(FAN_CONTROL_TAG, "postControlWatch=armed reason=hold_obligation_without_watch")
            startPostControlWatch()
        }
        val stopStartId = StartedServiceTerminalGate.stopStartIdOrNull(
            truth = truth,
            latestStartId = lifetime.latestStartId,
        ) ?: return
        mainHandler.post {
            if (destroyed.get()) return@post
            // Decided again, on main, after any in-flight onStartCommand has finished (see requestTerminalSettlement).
            val lifetimeNow = startedServiceLifetime.snapshot()
            val truthNow = terminalTruthNow(
                lifetime = lifetimeNow,
                journalEmpty = journalEmpty && fpsJournalEmpty,
                calibrationInactive = calibrationInactive,
                fpsSessionIdle = fpsLimiterSession.inMemoryIdle(),
            )
            if (!StartedServiceTerminalGate.mayStopNow(
                    plannedStartId = stopStartId,
                    truthNow = truthNow,
                    latestStartIdNow = lifetimeNow.latestStartId,
                )
            ) {
                return@post
            }
            StartedServiceStopExecutor.stopLatest(
                latestStartId = stopStartId,
                stopSelfResult = ::stopSelfResult,
                leaveForeground = ::leaveForeground,
            )
        }
    }

    /** Every in-memory obligation, read now. The disk-backed facts ([journalEmpty], [calibrationInactive]) come from the caller. */
    private fun terminalTruthNow(
        lifetime: StartedServiceLifetime.Snapshot,
        journalEmpty: Boolean,
        calibrationInactive: Boolean,
        fpsSessionIdle: Boolean,
    ): StartedServiceTerminalTruth = StartedServiceTerminalTruth(
        journalEmpty = journalEmpty,
        publicOwnerAbsent = publicOwners.snapshot() == null,
        // Lock-free: this is also decided on the main thread, which must never wait behind a stuck supervisor write.
        supervisorSessionAbsent = supervisor.activeSessionNoLock() == null,
        monitorAbsent = monitorFuture?.isDone != false,
        recoveryOwnerAbsent = !recoveryOwnership.isOwned(),
        restoreRetryAbsent = recoveryRetryFuture.get()?.isDone != false,
        foregroundRetryAbsent = fgsRetryFuture.get()?.isDone != false,
        lifetimeIdle = lifetime.isIdle,
        labHoldInactive = !labApplyHold.isActive(),
        calibrationInactive = calibrationInactive,
        transactionWorkTerminal = transactionWorkCount.get() == 0,
        postControlWatchInactive = postControlFuture == null,
        fpsSessionIdle = fpsSessionIdle,
        postControlHoldInactive = postControlHoldObligationAbsent(),
    )

    /** The answer a caller gets when the transaction worker timed out: nothing is claimed about the hardware. */
    private fun busyTransactionResult(busy: TransactionWorkerBusyException): TransactionResult = TransactionResult(
        success = false,
        applied = false,
        restored = false,
        code = SERVICE_BUSY_CODE,
        message = busy.message ?: "The transaction worker is busy.",
    )

    private fun legacyMutationRequiresUpgrade(): Boolean =
        legacyMutationFailureCode(BuildConfig.POSTURE) != null

    private fun apiUpgradeRequiredBundle(): Bundle = TransactionResult(
        success = false,
        applied = false,
        restored = false,
        code = "API_UPGRADE_REQUIRED",
        message = "Production mutation requires the generation-bound API v$API_VERSION contract.",
    ).toBundle().apply {
        putInt("apiVersion", API_VERSION)
    }

    /** Product-owned immutable ceiling; clients can renew only the shorter live lease. */
    internal open fun createOwnerHardLeasePolicy(): OwnerHardLeasePolicy =
        ProductOwnerHardLeasePolicy

    private fun safeElapsedAdd(value: Long, delta: Long): Long =
        if (delta > 0L && value > Long.MAX_VALUE - delta) Long.MAX_VALUE else value + delta

    /**
     * The ONE plan-admission truth feeding every [TuningPlanner] this service builds -- the gate
     * whose result becomes [TuningPlan.calibrated], and therefore the single thing standing between
     * a resolved plan and `TransactionCoordinator.apply`'s `PLAN_NOT_ADMITTED` refusal.
     *
     * Extracted to one method by chipset-support stage 3. It was previously an identical lambda
     * duplicated at both `TuningPlanner(certifiedLookup)` construction sites; widening admission in
     * two places independently is exactly the drift this codebase has shipped before, so selectable and appliable must share one
     * truth.
     *
     * **The signed manifest no longer controls admission:** the `productQualificationResolver`'s `planMatches` leg that used to run
     * first is gone. [SupportedUncertifiedProfiles.admitsPlan] -- chip-family membership plus this exact
     * unit's own live-OPP self-validation -- is now the only admission route, for every device
     * including the Odin. It was never a device-id allow-list and needed no widening to take over
     * this role alone.
     *
     * A signed manifest is not deleted as a *concept* by this change -- `productQualificationResolver`
     * still resolves one where it exists (the Odin's sequence-11 bridge manifest stands) and still
     * feeds `qualifiedDerateRungPlan`/`qualifiedManualFanPlan` (manifest-only constructs with no code-resident replacement) and the client-facing certified/uncertified label
     * in [capabilities]. It is deleted only as the thing that decides whether an ordinary
     * quiet/balanced/performance apply is *allowed*.
     *
     * That consequence is CLOSED as of 2026-08-23 and this paragraph is kept only so the sequence
     * is legible: a `fanMode = FAN_MANUAL` plan (the advanced manual-fan-control apply,
     * [TuningPlanner.FanIntent.MANUAL_OVERRIDE]) used to be admitted through `planMatches`'s
     * `qualifiedManualFanPlan` special case, and when the manifest stopped being consulted it was
     * refused on every device including the Odin. [SupportedUncertifiedProfiles.admitsPlan] now
     * carries its own explicit `MANUAL_OVERRIDE` route -- same equality-against-recompute strength,
     * re-derived with the intent the plan claims -- so the manual apply has a code-resident
     * admission again and no longer depends on a signature that was never issued.
     *
     * LAB's `DIAGNOSTICS_COMPILED` short-circuit is untouched and still evaluated first.
     */
    private fun planAdmittedForApply(plan: TuningPlan, caps: HardwareCapabilities): Boolean =
        if (BuildConfig.DIAGNOSTICS_COMPILED) {
            true
        } else {
            SupportedUncertifiedProfiles.admitsPlan(plan = plan, capabilities = caps)
        }

    /**
     * Is an active SYSTEM session Mimir's own applied profile, for the purpose of
     * "already active -- zero commands" and "which profile am I replacing"?
     *
     * **Three** disjoint ways to be one, mirroring `fanOwnershipSettleRoute`'s adopt routes exactly,
     * because they answer the same question at two moments and must not disagree:
     *  - Mimir owns the fan duty ([TuningPlanner.MIMIR_DUTY_OWNED_FAN_MODES]) -- the Odin, unchanged;
     *  - the contract carries vendor SMART, which the vendor's own loop regulates -- every RP/AYN
     *    device using the SMART default;
     *  - the chassis has no fan axis and the contract correctly carries no mode.
     *
     * The SMART leg was added with `ADOPT_VENDOR_SMART` and is not optional. The KDoc above already
     * required this function to mirror the router; when the router grew a third adopt route and this
     * kept only two, the `contractFanMode != null` guard below started answering "not Mimir-owned"
     * for every SMART-contracted fan-bearing session — churning restore-then-reapply at every
     * session boundary on exactly SMART-contracted devices. The shared predicate now includes that route.
     *
     * The fan-axis leg is still required for the null case, not merely the null: a fan-BEARING
     * device whose contract lost its fan mode is an anomaly and must keep answering "not
     * Mimir-owned", so the caller restores before replacing rather than treating a half-owned
     * session as settled.
     */
    private fun activeSystemSessionIsMimirOwned(contractFanMode: Int?): Boolean {
        if (contractFanMode in TuningPlanner.MIMIR_DUTY_OWNED_FAN_MODES) return true
        if (contractFanMode == TuningPlanner.FAN_SMART) return true
        if (contractFanMode != null) return false
        val resolved = activeFanCapabilities ?: capabilities().also { activeFanCapabilities = it }
        return TuningAxis.FAN !in resolved.supportedAxes
    }

    private val capabilityCadenceWatch = CapabilityCallCadenceWatch()

    /**
     * Runs inside the binder transaction so [Binder.getCallingPid] is the real caller. Costs one
     * subtraction and a few fields per call and allocates nothing unless an edge fires; the logging
     * itself is `runCatching`-wrapped because an instrument must never be able to fail the call it
     * is measuring.
     */
    private fun noteCapabilityCall() {
        val event = runCatching {
            capabilityCadenceWatch.onCall(
                nowElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                callingPid = Binder.getCallingPid(),
            )
        }.getOrNull() ?: return
        runCatching {
            when (event) {
                is CapabilityCallCadenceWatch.Event.Entered -> Log.w(
                    TAG_CAPABILITY_CADENCE,
                    "capabilityCadence=fast pid=${event.callingPid} " +
                        "intervalMs=${event.intervalMs} afterCalls=${event.afterCalls}",
                )
                is CapabilityCallCadenceWatch.Event.Settled -> Log.w(
                    TAG_CAPABILITY_CADENCE,
                    "capabilityCadence=settled pid=${event.callingPid} " +
                        "episodeMs=${event.episodeMs} calls=${event.calls} " +
                        "fastestIntervalMs=${event.fastestIntervalMs} " +
                        "multiplePids=${event.multiplePids}",
                )
            }
        }
    }

    private fun capabilities(): HardwareCapabilities {
        val base = probe.probe()
        val dry = if (credentialStoresReady()) dryRun() else !BuildConfig.HARDWARE_WRITES_COMPILED
        val store = certificationStore
        val productQualification = productQualificationResolver.resolve(base)
        val qualifiedProfileIds = productQualification.authority.certifiedProfileIds(
            HardwareFingerprint.create(base),
        )
        val qualificationSource = if (productQualification.available) {
            TweakerPostureTruth.QUALIFICATION_SIGNED_MANIFEST
        } else {
            TweakerPostureTruth.qualificationSource(store?.hasLocalCalibrationReport() == true)
        }
        // Chipset-support stage 3: make the two sets genuinely DISJOINT on the wire, which until
        // now was only a KDoc claim. `SupportedUncertifiedProfiles.compute` runs inside
        // `probe()`, where `certifiedProfileIds` is still the hardcoded-empty
        // `TweakerPostureTruth.productQualifiedProfileIds()`, so it cannot subtract the certified
        // set itself -- the real one is not resolved until right here. Device-confirmed on a measured Odin 2026-08-22: it reports certified {quiet,balanced,performance} AND
        // uncertified {quiet,balanced,performance}, so without this subtraction a certified device
        // would hand the client an id in both buckets and the honesty label would be a coin flip.
        // Certified always wins; an id is uncertified only if it is not also signed.
        val uncertifiedProfileIds = base.supportedUncertifiedProfileIds - qualifiedProfileIds
        return base.copy(
            certifiedProfileIds = qualifiedProfileIds,
            supportedUncertifiedProfileIds = uncertifiedProfileIds,
            posture = TweakerPostureTruth.posture,
            mutationAvailable = TweakerPostureTruth.mutationAvailable(
                hardwareWritesCompiled = BuildConfig.HARDWARE_WRITES_COMPILED,
                dryRun = dry,
                capabilityStatus = base.status,
                pServerAvailable = base.pServerAvailable,
                qualificationSource = qualificationSource,
                qualifiedProfileIds = qualifiedProfileIds,
                supportedUncertifiedProfileIds = uncertifiedProfileIds,
            ),
            dryRun = dry,
            usageAccessGranted = UsageAccessProbe.granted(this),
            qualificationSource = qualificationSource,
        )
    }

    private fun dryRun(): Boolean = when {
        !BuildConfig.HARDWARE_WRITES_COMPILED -> true
        BuildConfig.DIAGNOSTICS_COMPILED -> prefs().getBoolean(KEY_DRY_RUN, true)
        else -> false
    }

    private fun ensureForeground(contentText: String = getString(R.string.notification_active_profile)) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Mimir Tweaker session", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentText(contentText)
            .setContentTitle(getString(R.string.app_name))
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        foreground = true
    }

    private fun leaveForeground() {
        if (!foreground) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
    }

    /**
     * Idempotent: most callers are Binder methods that find the stores already ready and return at once. [frameTelemetry]
     * is the calibration frame port; when a caller does not supply one (the stores became ready after an unlock, not at
     * create), the service's own [pServer] client serves it, so no second client and no second call pool is built.
     */
    private fun ensureCredentialStores(frameTelemetry: PServerFrameTelemetryPort? = null) {
        if (credentialStoresReady()) return
        check(isUserUnlocked()) {
            "Credential-protected SharedPreferences unavailable during direct-boot recovery"
        }
        preferences = getSharedPreferences("tweaker_settings", MODE_PRIVATE)
        fanControlSelections = ActiveFanControlSelectionStore(
            SharedPreferencesFanControlPreferenceStore(prefs()),
        )
        val store = SharedPreferencesCalibrationReportStore(this)
        calibrationStore = store
        val cert = CertificationStore(store)
        certificationStore = cert
        probe.attachCertificationStore(cert)
        val certifiedLookup: (TuningPlan, HardwareCapabilities) -> Boolean = ::planAdmittedForApply
        rollbackDrillRunner = RollbackDrillRunner(
            transaction = transaction,
            capabilities = ::capabilities,
            planner = TuningPlanner(certifiedLookup),
            calibrationStore = store,
            hardwareWritesCompiled = BuildConfig.HARDWARE_WRITES_COMPILED,
            diagnosticsCompiled = BuildConfig.DIAGNOSTICS_COMPILED,
        )
        calibrationRunner = CalibrationSessionRunner(
            telemetryProbe = object : CalibrationTelemetryProbe {
                override fun lightTelemetry(capabilities: HardwareCapabilities): TelemetrySnapshot =
                    probe.lightTelemetry(capabilities)

                override fun fullTelemetry(capabilities: HardwareCapabilities): TelemetrySnapshot =
                    probe.telemetry(capabilities)
            },
            capabilities = ::capabilities,
            planner = TuningPlanner(certifiedLookup),
            evaluator = CalibrationEvaluator(),
            store = store,
            foregroundPackage = attestor::currentPackage,
            frameTelemetry = frameTelemetry ?: PServerFrameTelemetryPort(pServer, { true }),
            onFinished = {
                mainHandler.post {
                    requestTerminalSettlement()
                }
            },
            externalBaseline = { profileId, packageName, caps ->
                if (BuildConfig.DIAGNOSTICS_COMPILED && profileId == LabPreMutationBaselineHandoff.QUIET_PROFILE) {
                    labPreMutationBaselineHandoff.consume(
                        profileId,
                        packageName,
                        caps,
                        (journal.load() as? JournalLoadResult.Loaded)?.record,
                    )
                } else {
                    null
                }
            },
            requiresExternalBaseline = { profileId ->
                BuildConfig.DIAGNOSTICS_COMPILED && profileId == LabPreMutationBaselineHandoff.QUIET_PROFILE
            },
            appliedTarget = { profileId, packageName, caps ->
                (journal.load() as? JournalLoadResult.Loaded)?.record?.let { record ->
                    CalibrationAppliedTargetOffer.fromAuthenticatedAppliedRecord(
                        record = record,
                        profileId = profileId,
                        packageName = packageName,
                        capabilities = caps,
                        nowElapsedMs = SystemClock.elapsedRealtime(),
                    )
                }
            },
            requiresAppliedTarget = { true },
        )
    }

    private fun credentialStoresReady(): Boolean = calibrationStore != null

    private fun maybeLogLabQualificationSelection(
        requestedProfileId: String,
        result: TransactionResult,
    ) {
        if (!labQualificationEvidenceEnabled()) return
        Log.i(
            LAB_QUALIFICATION_EVIDENCE_TAG,
            LabQualificationEvidence.selection(requestedProfileId, result, publicSessionTruth()),
        )
    }

    private fun maybeLogLabQualificationSample(
        telemetry: TelemetrySnapshot,
        live: LivePerformanceTelemetry,
    ) {
        if (!labQualificationEvidenceEnabled()) return
        val sampledAt = live.sampledAtElapsedRealtimeMs
        val prior = lastLabEvidenceSampleElapsedMs.get()
        if (prior > 0L && sampledAt - prior < LAB_QUALIFICATION_SAMPLE_INTERVAL_MS) return
        if (!lastLabEvidenceSampleElapsedMs.compareAndSet(prior, sampledAt)) return
        Log.i(
            LAB_QUALIFICATION_EVIDENCE_TAG,
            LabQualificationEvidence.sample(telemetry, live, publicSessionTruth()),
        )
    }

    private fun labQualificationEvidenceEnabled(): Boolean =
        BuildConfig.POSTURE == TweakerPostureTruth.LAB &&
            BuildConfig.DIAGNOSTICS_COMPILED &&
            BuildConfig.HARDWARE_WRITES_COMPILED &&
            credentialStoresReady() &&
            !dryRun()

    private fun prefs(): android.content.SharedPreferences =
        checkNotNull(preferences) { "Credential preferences not initialized" }

    private fun isUserUnlocked(): Boolean {
        val userManager = getSystemService(UserManager::class.java) ?: return true
        return userManager.isUserUnlocked
    }

    private fun rollbackDrillResult(
        success: Boolean,
        attempts: Int,
        successes: Int,
        code: String,
        message: String,
        settlementCode: String? = null,
        observedSettlementWindowMs: Long = 0L,
        settlementCleanRounds: Int = 0,
        minimumCleanSettlementWindowMs: Long = 0L,
    ): Bundle = Bundle().apply {
        putBoolean("success", success)
        putInt("rollbackAttempts", attempts)
        putInt("rollbackSuccesses", successes)
        putString("code", code)
        putString("message", message)
        putString("settlementCode", settlementCode)
        putLong("observedSettlementWindowMs", observedSettlementWindowMs)
        putInt("settlementCleanRounds", settlementCleanRounds)
        putLong("minimumCleanSettlementWindowMs", minimumCleanSettlementWindowMs)
    }

    private inline fun <T> guarded(block: () -> T): T {
        if (Binder.getCallingUid() != Process.myUid()) {
            enforceCallingPermission(CONTROL_PERMISSION, "Caller is not authorized to control Mimir Tweaker")
        }
        return block()
    }

    /**
     * [guarded], and ALSO the caller UID must belong to the Mimir app package (see [GameAudioFocusCallerPolicy]). The
     * signature permission says "signed by the same key"; the v11 hold is for Mimir alone, so any other same-signed app
     * that was granted the permission is refused here.
     */
    private inline fun <T> guardedMimirOnly(block: () -> T): T {
        guarded {
            val callingUid = Binder.getCallingUid()
            if (!GameAudioFocusCallerPolicy.admits(
                    callingUid = callingUid,
                    ownUid = Process.myUid(),
                    devVariantsAllowed = BuildConfig.DEBUG,
                    packagesForUid = { uid -> packageManager.getPackagesForUid(uid)?.toList().orEmpty() },
                )
            ) {
                throw SecurityException("Caller is not Mimir: the game audio-focus hold is for Mimir alone")
            }
        }
        return block()
    }

    companion object {
        const val CONTROL_PERMISSION = "com.gruning.mimir.tweaker.permission.CONTROL_TWEAKER"
        // v8: appended setDisplayRateSettingV8 (unlike the LAB-only applyThermalDerateRungLab append, which
        // deliberately did not bump this -- see IMimirTweaker.aidl). The method is RETIRED: Mimir removed display-rate
        // switching on 2026-09-15, so the declaration stays only to keep later transaction codes stable and its body
        // refuses (RetiredDisplayRateWrite) and never writes. The number stays 8 here because v9 and v10 are numbered
        // after it and a client gates on getApiVersion() >= N; it no longer advertises a display-rate capability.
        // v9: appends the discovered-axis record family . Bumped for the
        // same reason v8 was -- the app gates on a cheap getApiVersion() >= 9 rather than discovering
        // an older daemon's missing method through a failed Binder transaction, which for THIS family
        // matters more than usual: a failed query must be read as "cannot ask", never as "holds
        // nothing", and a version check is how the app tells those apart before it asks.
        // v10: appends the discovered-CEILING family (policy 2026-09-03, built 2026-09-11) --
        // the same three axes v9 remembers, now WRITTEN here. Bumped for the reason v8 and v9 were,
        // and for one more that is specific to this family: routing the write through the daemon is
        // only correct when the daemon HAS the write, so the app's "route through the daemon or
        // write it myself" fork is decided by a cheap getApiVersion() >= 10 before it asks. An older
        // daemon must read as "write it myself", never as "the daemon holds nothing".
        // v11: appends the game audio-focus hold  -- three methods taking ONE package
        // and nothing else; the daemon owns the command templates. Bumped so Mimir gates on a cheap
        // getApiVersion() >= 11 and an older daemon reads as "unsupported": music behaves as it always did.
        const val API_VERSION = 11
        /** The one transaction worker's thread; a call made from it runs inline. */
        const val TX_WORKER_THREAD_NAME = "mimir-tweaker-tx"
        const val TX_TICK_SUPERVISOR_THREAD = "mimir-tweaker-supervisor"
        const val TX_TICK_POST_CONTROL_THREAD = "mimir-tweaker-post-control"
        const val TX_TICK_CEILING_THREAD = "mimir-tweaker-ceiling"
        /** The threads that tick on a schedule: a tick skips instead of queueing behind its own stuck predecessor. */
        private val TX_TICK_THREADS = setOf(
            TX_TICK_SUPERVISOR_THREAD,
            TX_TICK_POST_CONTROL_THREAD,
            TX_TICK_CEILING_THREAD,
        )
        /**
         * How long a Binder caller waits on the transaction worker. An apply legitimately runs to its 45 s request window
         * plus cooling dwell and a rollback, so this is a wedge safety net above that, not a latency target; past it the
         * caller gets SERVICE_BUSY and, if the work had started, treats it as unconfirmed.
         */
        const val TX_BINDER_WAIT_MS = 150_000L
        /** How long a scheduled tick waits; a tick that outlives it is skipped while its predecessor is still running. */
        const val TX_TICK_WAIT_MS = 60_000L
        /** LAB rollback drills run N apply/restore cycles on the worker and are driven by a lab harness, not a UI. */
        const val TX_LAB_WAIT_MS = 3_600_000L
        private const val KEY_DRY_RUN = "dry_run"
        const val EXTRA_LAB_APPLY_HOLD = TweakerServiceActions.EXTRA_LAB_APPLY_HOLD
        const val EXTRA_LAB_OBSERVE_SECONDS = TweakerServiceActions.EXTRA_LAB_OBSERVE_SECONDS
        const val EXTRA_LAB_SYSTEM_SCOPE = "labSystemScope"
        /**
         * WAVE2 thermal-derate rung verification programme: same extra name/semantics as
         * [MainActivity.EXTRA_RUNG_INDEX] (1-based [ThermalDerateLadder] rung index), forwarded
         * into the [TweakerServiceActions.ACTION_BEGIN_LAB_PROFILE_HOLD] intent so a LAB hold can
         * hold a RUNG's plan instead of the profile nominal. Absent means no override -- see
         * onStartCommand's rungIndexExtra handling.
         */
        const val EXTRA_RUNG_INDEX = "labRungIndex"
        const val ACTION_RECOVERY_RESTORE = "com.gruning.mimir.tweaker.action.RECOVERY_RESTORE"
        const val ACTION_FPS_RECOVERY_RESTORE =
            "com.gruning.mimir.tweaker.action.FPS_RECOVERY_RESTORE"
        const val ACTION_GAME_AUDIO_FOCUS_RECOVERY_RESTORE =
            "com.gruning.mimir.tweaker.action.GAME_AUDIO_FOCUS_RECOVERY_RESTORE"
        const val ACTION_GUARDIAN_SETTLEMENT =
            "com.gruning.mimir.tweaker.action.GUARDIAN_SETTLEMENT"
        const val EXTRA_RECOVERY_GENERATION = "com.gruning.mimir.tweaker.extra.RECOVERY_GENERATION"
        const val EXTRA_RECOVERY_TRANSACTION_ID =
            "com.gruning.mimir.tweaker.extra.RECOVERY_TRANSACTION_ID"
        const val ACTION_RCT1_CHANNEL_PROBE = "com.gruning.mimir.tweaker.action.RCT1_CHANNEL_PROBE"
        const val EXTRA_PROFILE_ID = TweakerServiceActions.EXTRA_PROFILE_ID
        const val EXTRA_WORKLOAD_PACKAGE = TweakerServiceActions.EXTRA_WORKLOAD_PACKAGE
        private const val CHANNEL_ID = "mimir_tweaker_session"
        private const val NOTIFICATION_ID = 4201
        private const val RCT1_PROBE_NONCE_BYTES = 24
        private const val TAG = "MimirTweakerService"
        /** Its own tag: one `-s MimirGameAudioFocus` shows every hold and release with its result. */
        private const val TAG_GAME_AUDIO_FOCUS = "MimirGameAudioFocus"
        internal const val CPU_QOS_NODE_UNRECLAIMABLE_CODE = CpuTransportPrecedence.UNRECLAIMABLE_CODE
        /** Its own tag: this line is the only evidence that an unowned obligation was seen. */
        private const val TAG_CREATE_RECOVERY = "MimirTweakerRecovery"
        /** Its own tag so a cadence episode can be grepped without the rest of the daemon. */
        private const val TAG_CAPABILITY_CADENCE = "MimirTweakerCadence"
        private const val LAB_QUALIFICATION_EVIDENCE_TAG = "MimirTweakerEvidence"

        /**
         * 2026-08-23. The fan-control transition path had **zero** `Log` calls in any posture, so
         * when switching from Auto to Manual was reported to apply no fan response,
         * logcat held nothing at all -- not a failure, not an attempt. The instrument could not see
         * the defect, so watching harder would have proved nothing and the only honest next step
         * was to make the chain observable first. Every stage below that can silently decline now
         * names itself. Deliberately `Log.i`/`Log.w`, not diagnostics-gated: a decline must remain diagnosable on production builds.
         */
        private const val FAN_CONTROL_TAG = "MimirFanControl"
        /** The apply declined because a live post-control fan hold could not be handed back before capture. */
        internal const val POST_CONTROL_HOLD_RELEASE_FAILED_CODE = "POST_CONTROL_HOLD_RELEASE_FAILED"
        /** a profile re-arm was refused because a post-control fan hold is running. */
        internal const val POST_CONTROL_HOLD_ACTIVE_CODE = "POST_CONTROL_HOLD_ACTIVE"
        /** P1-2: a hot apply admitted only as its Manual fan plan refused the auto fallback instead of running it. */
        internal const val COOLER_REARM_FALLBACK_REFUSED_CODE = "COOLER_REARM_FALLBACK_REFUSED"
        /** A start over a post-control hold marker: the boot receiver's, or the service's own promotion on entry. */
        const val ACTION_POST_CONTROL_HOLD_RELEASE = "com.gruning.mimir.tweaker.action.POST_CONTROL_HOLD_RELEASE"
        private const val LAB_QUALIFICATION_SAMPLE_INTERVAL_MS = 1_000L
        private const val FAN_DIAGNOSTIC_MAX_LENGTH = 240
        private val PACKAGE_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
        private val FAN_DIAGNOSTIC_UNSAFE_CHARACTERS = Regex("[^A-Za-z0-9 .,:;_=/+?-]")
        private val SYSTEM_WIDE_PROFILE_IDS =
            TweakerPostureTruth.PRODUCT_QUALIFIABLE_PROFILE_IDS + SystemWideProfileTransition.STOCK_PROFILE_ID
    }
}

/** Pure gate for the explicit, side-effect-free RCT-1 receipt observation action. */
internal enum class SafeLiveReceiptProbeGate {
    Open,
    PostureRejected,
    WriteBuildRejected,
    UserLocked,
    PermissionRejected,
    JournalNotEmpty,
    PServerUnavailable,
    ;

    companion object {
        fun evaluate(
            posture: String,
            hardwareWritesCompiled: Boolean,
            userUnlocked: Boolean,
            controlPermissionGranted: Boolean,
            journalEmpty: Boolean,
            pServerInitialized: Boolean,
        ): SafeLiveReceiptProbeGate = when {
            posture != TweakerPostureTruth.SAFE -> PostureRejected
            hardwareWritesCompiled -> WriteBuildRejected
            !userUnlocked -> UserLocked
            !controlPermissionGranted -> PermissionRejected
            !journalEmpty -> JournalNotEmpty
            !pServerInitialized -> PServerUnavailable
            else -> Open
        }
    }
}

internal fun guardianReadyLoadMarker(load: GuardianReadyLoad): String = when (load) {
    is GuardianReadyLoad.Received -> "GuardianReadyLoad.Received"
    GuardianReadyLoad.TimedOut -> "GuardianReadyLoad.TimedOut"
    GuardianReadyLoad.Unavailable -> "GuardianReadyLoad.Unavailable"
    GuardianReadyLoad.Ambiguous -> "GuardianReadyLoad.Ambiguous"
}

/** Public V4 apply preserves the SAFE posture gate before any owner lease qualification. */
internal fun publicApplyPostureFailureCode(hardwareWritesCompiled: Boolean): String? =
    if (hardwareWritesCompiled) null else "WRITE_GATE_DISABLED"

/** Every write-bearing legacy transaction is unavailable in the production contract. */
internal fun legacyMutationFailureCode(posture: String): String? =
    if (posture == TweakerPostureTruth.PRODUCTION) "API_UPGRADE_REQUIRED" else null

/**
 * The WAVE2 thermal-derate rung diagnostic is LAB-only -- it is unreachable (fails closed with
 * this code) in every build where DIAGNOSTICS_COMPILED is false, i.e. production and safe alike.
 */
internal fun thermalDerateRungLabFailureCode(diagnosticsCompiled: Boolean): String? =
    if (diagnosticsCompiled) null else "LAB_ONLY"
