package com.gruning.mimir.tweaker

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Renders and signs only the exact capture/plan projection supplied by [TransactionCoordinator]. */
internal class AndroidGuardianPreparedEnvelopePort(
    context: Context,
    private val authority: DirectBootKeyAuthorityTruth.Available,
    private val certificateDigest: String = AndroidAppSigningCertificateDigestSource(context).currentDigest(),
    private val helperBuildIdentity: String = GuardianHelperBuildIdentity.current(context),
    private val random: SecureRandom = SecureRandom(),
) : GuardianPreparedEnvelopePort {
    override fun preparedEnvelope(identity: GuardianJournalIdentity): SignedRestoreGuardianEnvelope? = null

    override fun preparedEnvelope(
        request: GuardianPreparedEnvelopeRequest,
    ): SignedRestoreGuardianEnvelope? = runCatching {
        val identity = request.identity
        val nodes = request.snapshot.nodes.associateBy { it.logicalId }
        val restoreOrder = if (request.fanOwnershipTransitionsAllowed) {
            request.plannedMutations.withIndex().sortedBy {
                if (it.value.axis == TuningAxis.FAN) -1 else request.plannedMutations.size - it.index
            }
        } else RestoreMutationOrder.indexed(request.plannedMutations)
        val entries = restoreOrder.map { indexed ->
            val applyOrdinal = indexed.index
            val mutation = indexed.value
            val stock = requireNotNull(nodes[mutation.logicalId])
            require(stock.axis == mutation.axis)
            when (mutation.axis) {
                TuningAxis.CPU, TuningAxis.GPU -> RestoreGuardianSysfsEntry(
                    applyOrdinal = applyOrdinal,
                    logicalId = mutation.logicalId,
                    canonicalSysfsPath = mutation.canonicalPath,
                    // The guardian restores from its own envelope in a separate process, so it has
                    // to expect the same value the in-process restore writes. Deriving both from
                    // [restoreWriteValue] is what keeps the two paths from disagreeing.
                    expectedStock = RestoreGuardianStockState.PresentSysfs(
                        value = requireNotNull(restoreWriteValue(stock)),
                        mode = requireNotNull(restoreWriteMode(stock)),
                        uid = requireNotNull(stock.originalUid),
                        gid = requireNotNull(stock.originalGid),
                    ),
                    rendererAppliedIntermediates = listOf(requireNotNull(mutation.value)),
                )

                TuningAxis.FAN -> if (stock.present) {
                    RestoreGuardianFanPutEntry(
                        applyOrdinal = applyOrdinal,
                        expectedStock = RestoreGuardianStockState.PresentFanMode(
                            requireNotNull(stock.originalValue),
                        ),
                        rendererAppliedIntermediates = if (request.fanOwnershipTransitionsAllowed) {
                            require(mutation.value in FAN_OWNERSHIP_TARGETS)
                            FAN_OWNERSHIP_TARGETS
                        } else listOf(requireNotNull(mutation.value)),
                    )
                } else {
                    RestoreGuardianFanDeleteEntry(applyOrdinal = applyOrdinal)
                }

                // The vendor mode survives a reboot, so a plan that drives it must not run without a
                // Guardian entry that can put the player's mode back. Both values are signed: the
                // ORIGIN from the captured node (the honest origin, never a live read -- see
                // [VendorModeOriginRecord]) and the APPLIED mode from the planned write, which is the
                // only live value at which the Guardian may write the origin. Anything inconsistent
                // throws, the runCatching below yields a null envelope, and the coordinator refuses
                // the apply with zero commands executed.
                TuningAxis.VENDOR_MODE -> {
                    val context = requireNotNull(mutation.vendorModeContext) { "vendor mode mutation carries no origin" }
                    require(stock.present) { "vendor mode node not present" }
                    val origin = requireNotNull(stock.originalValue) { "vendor mode origin missing" }
                    val applied = requireNotNull(mutation.value) { "vendor mode applied value missing" }
                    require(
                        mutation.kind == LogicalMutationKind.SETTINGS_PUT &&
                            mutation.canonicalPath == HardwareSnapshot.VENDOR_MODE_SETTINGS_PATH &&
                            !mutation.capturedSnapshotAuthority &&
                            context.origin == origin &&
                            context.applied == applied,
                    ) { "vendor mode mutation disagrees with the captured node" }
                    RestoreGuardianVendorModePutEntry(
                        applyOrdinal = applyOrdinal,
                        origin = origin,
                        applied = applied,
                    )
                }
            }
        }
        val envelope = RestoreGuardianEnvelope(
            helperBuildIdentity = helperBuildIdentity,
            tweakerCertificateSha256 = certificateDigest,
            hardwareFingerprint = identity.hardwareFingerprint,
            deviceProfile = identity.deviceProfile,
            bootIdentity = identity.bootIdentity,
            generation = identity.generation,
            requestId = identity.requestId,
            transactionId = identity.transactionId,
            taskNonce = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) },
            topologySha256 = identity.expectedTopologySha256,
            snapshotSha256 = identity.snapshotSha256,
            planSha256 = identity.planSha256,
            hardDeadlineElapsedRealtimeMs = request.hardDeadlineElapsedRealtimeMs,
            restoreEntries = entries,
        )
        authority.signEnvelope(envelope)
    }.getOrNull()
}

internal object GuardianHelperBuildIdentity {
    fun current(context: Context): String {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return "mimir-tweaker:${info.longVersionCode}:${BuildConfig.VERSION_NAME}:${BuildConfig.POSTURE.lowercase()}"
    }
}

/**
 * Host half of the same-APK Guardian protocol. The arm store is app-authenticated, while READY
 * and terminal receipts are signed by a distinct non-exportable key owned by `:guardian`.
 */
internal class RemoteGuardianProcessAuthority(
    private val context: Context,
    private val available: DirectBootKeyAuthorityTruth.Available,
    private val armStore: RestoreGuardianArmStore,
    private val terminalStore: GuardianTerminalReceiptStore,
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
    // Heartbeat gate: the one gate
    // [publishHeartbeat] asks -- one call, one state transition, no cached field. The default
    // always allows. F1 (fan fence, 2026-10-14): no timestamp is passed any more. The gate owns its
    // CPU-awake clock (`ManualFanMonitorLiveness.uptimeClock`) and samples it at the comparison,
    // AFTER the live fan-mode read below and after the caller's own journal load -- the authority
    // used to capture `uptimeMillis()` before both reads and the gate then called a tick stamped
    // during them "stale" (`HEALTHY_STALE age=-779`, gen 51 on the Odin).
    private val heartbeatDenialReason: (RestoreGuardianEnvelope, Int?) -> String? = { _, _ -> null },
    // Phase B (§3 B3, L3): the live vendor `fan_mode` at the instant of THIS heartbeat -- a
    // Settings read, safe off the transaction thread. Evaluated exactly once per [publishHeartbeat]
    // and threaded through to the gate lambda above, so a later reader of this heartbeat would see
    // the SAME sample rather than two Settings reads racing a vendor write between them. Defaults
    // to `{ null }`: a caller that supplies nothing gets pre-B3 behaviour exactly (a null read always
    // falls through to the existing L1/L2 rules -- L1 wins on uncertainty).
    private val liveFanMode: () -> Int? = { null },
    private val onEnvelopeReady: () -> Unit = {},
    // F2 (fan fence, 2026-10-14): the host session named here is over -- its own gate denied the
    // heartbeat and asked the Guardian to restore, or the Guardian answered a heartbeat with a
    // refusal / its terminal receipt is on disk. Called OFF the transaction worker (the heartbeat
    // thread) with reasons and identifiers only, so the implementation must be non-blocking: the
    // service records it in `GuardianHandbackGate` and the next monitor tick settles the session.
    // Never called for a binder transport failure alone (see GuardianHeartbeatRetirePolicy).
    private val onSessionRetired: (generation: Long, transactionId: String, cause: String) -> Unit = { _, _, _ -> },
) : GuardianArmAuthorityPort {
    private val connectionLock = Any()
    private var remote: IRestoreGuardianProcess? = null
    private var connection: ServiceConnection? = null
    private val ownerToken = Binder()
    private var active: GuardianReadyHandle? = null
    private var activeSessionKey: GuardianSessionPublicKey? = null
    private var activeEnvelope: RestoreGuardianEnvelope? = null
    private var heartbeatCounter = 0L
    private var heartbeatScheduler: ScheduledExecutorService? = null
    private var heartbeatFuture: ScheduledFuture<*>? = null

    override fun armToReady(
        signed: SignedRestoreGuardianEnvelope,
        expected: RestoreGuardianExpectedIdentity,
    ): GuardianArmAdmission = synchronized(this) {
        if (active != null) return GuardianArmAdmission.ArmRejected(GuardianArmRejectReason.CONCURRENT_ARM)
        val envelope = signed.envelope
        if (!expected.matches(envelope)) {
            return GuardianArmAdmission.ArmRejected(GuardianArmRejectReason.IDENTITY_MISMATCH)
        }
        if (!RestoreGuardianCrypto.verifyEnvelope(signed, available.envelopeVerifier())) {
            return GuardianArmAdmission.ArmRejected(GuardianArmRejectReason.INVALID_ENVELOPE_SIGNATURE)
        }
        if (armStore.arm(signed) != GuardianArmStoreWrite.Armed) {
            return GuardianArmAdmission.ArmRetained(GuardianArmRetainedReason.ARM_STORE_AMBIGUOUS)
        }
        val guardian = connect() ?: return GuardianArmAdmission.ArmRetained(
            GuardianArmRetainedReason.GUARDIAN_LAUNCH_AMBIGUOUS,
        )
        val response = runCatching { guardian.arm(ownerToken) }.getOrNull()
            ?: return GuardianArmAdmission.ArmRetained(GuardianArmRetainedReason.GUARDIAN_LAUNCH_AMBIGUOUS)
        if (!response.getBoolean(RestoreGuardianProcessService.KEY_SUCCESS)) {
            return GuardianArmAdmission.ArmRetained(GuardianArmRetainedReason.READY_UNCONFIRMED)
        }
        val readyWire = response.getByteArray(RestoreGuardianProcessService.KEY_READY_WIRE)
            ?: return GuardianArmAdmission.ArmRetained(GuardianArmRetainedReason.READY_UNCONFIRMED)
        val ready = GuardianReadyWireCodec.decode(readyWire)
            ?: return GuardianArmAdmission.ArmRetained(GuardianArmRetainedReason.READY_UNCONFIRMED)
        val now = elapsedRealtimeMs()
        val sessionKey = ready.receipt.sessionPublicKey
        val reflectedAppKey = available.envelopeVerifier().matchesSessionSec1(sessionKey.sec1Bytes())
        val exactReady = !reflectedAppKey &&
            now >= 0 && now < envelope.hardDeadlineElapsedRealtimeMs &&
            RestoreGuardianCrypto.verifyReadyReceipt(ready, envelope) &&
            RestoreGuardianCrypto.receiptMatches(envelope, ready.receipt) &&
            ready.receipt.issuedElapsedRealtimeMs in 0..now
        if (!exactReady) {
            return GuardianArmAdmission.ArmRetained(GuardianArmRetainedReason.READY_KEY_REJECTED)
        }
        when (armStore.pinSessionKey(expected, sessionKey, ready, now)) {
            GuardianSessionPinWrite.Pinned -> Unit
            GuardianSessionPinWrite.Rejected -> return GuardianArmAdmission.ArmRetained(
                GuardianArmRetainedReason.SESSION_PIN_REJECTED,
            )
            GuardianSessionPinWrite.Ambiguous -> return GuardianArmAdmission.ArmRetained(
                GuardianArmRetainedReason.SESSION_PIN_AMBIGUOUS,
            )
        }
        val durable = armStore.load(expected) as? GuardianArmStoreLoad.LoadedReady
            ?: return GuardianArmAdmission.ArmRetained(GuardianArmRetainedReason.SESSION_PIN_UNREADABLE)
        if (!runCatching { guardian.confirmReady() }.getOrDefault(false)) {
            return GuardianArmAdmission.ArmRetained(GuardianArmRetainedReason.READY_UNCONFIRMED)
        }
        val handle = GuardianReadyHandle(expected, durable.signed, sessionKey.sha256())
        active = handle
        activeSessionKey = sessionKey
        activeEnvelope = durable.signed.envelope
        // A new authenticated Guardian envelope is a new liveness epoch. Clear any
        // completed prior-generation controller state before its first heartbeat.
        onEnvelopeReady()
        startHeartbeat()
        GuardianArmAdmission.Ready(handle)
    }

    override fun presence(): GuardianArmStorePresence = armStore.presence()

    override fun verifyTerminal(handle: GuardianReadyHandle): GuardianTerminalVerification = synchronized(this) {
        if (active !== handle) return GuardianTerminalVerification.Rejected(
            GuardianTerminalRejectReason.INACTIVE_HANDLE,
        )
        activeEnvelope ?: return GuardianTerminalVerification.Rejected(
            GuardianTerminalRejectReason.IDENTITY_MISMATCH,
        )
        activeSessionKey ?: return GuardianTerminalVerification.Rejected(
            GuardianTerminalRejectReason.INVALID_STOCK,
        )
        val loaded = terminalStore.loadUntil(0) as? GuardianTerminalLoad.Received
            ?: return GuardianTerminalVerification.Retained(GuardianTerminalRetainedReason.TRANSPORT_UNAVAILABLE)
        val durable = armStore.load(handle.expectedIdentity) as? GuardianArmStoreLoad.LoadedReady
            ?: return GuardianTerminalVerification.Retained(GuardianTerminalRetainedReason.TRANSPORT_UNAVAILABLE)
        val digest = GuardianTransactionReconciler.authenticTerminalDigest(durable, loaded)
            ?: return GuardianTerminalVerification.Rejected(GuardianTerminalRejectReason.INVALID_STOCK)
        GuardianTerminalVerification.Verified(digest)
    }

    override fun disarm(handle: GuardianReadyHandle): Boolean = synchronized(this) {
        if (active !== handle || verifyTerminal(handle) !is GuardianTerminalVerification.Verified) return false
        if (!armStore.clear()) return false
        stopHeartbeat()
        active = null
        activeSessionKey = null
        activeEnvelope = null
        true
    }

    override fun restoreAndSettle(journal: TransactionJournal): GuardianCrossStoreSettlement = synchronized(this) {
        val loaded = journal.load() as? JournalLoadResult.Loaded
            ?: return GuardianCrossStoreSettlement.Retained("GUARDIAN_JOURNAL_UNAVAILABLE")
        val expected = GuardianArmExpectedIdentityDerivation.from(loaded.record)
            ?: return GuardianCrossStoreSettlement.Retained("GUARDIAN_IDENTITY_UNAVAILABLE")
        val arm = armStore.load(expected)
        if (GuardianPreReadySettlementPolicy.allows(loaded.record, arm)) {
            if (terminalStore.loadUntil(0) !is GuardianTerminalLoad.Unavailable) {
                return GuardianCrossStoreSettlement.Retained("GUARDIAN_PRE_READY_TERMINAL_PRESENT")
            }
            if (arm is GuardianArmStoreLoad.LoadedStaged && !armStore.clear()) {
                return GuardianCrossStoreSettlement.Retained("GUARDIAN_PRE_READY_ARM_CLEAR_FAILED")
            }
            if (armStore.presence() != GuardianArmStorePresence.ABSENT) {
                return GuardianCrossStoreSettlement.Retained("GUARDIAN_PRE_READY_ARM_NOT_ABSENT")
            }
            if (!journal.clear(loaded.record.transactionId)) {
                return GuardianCrossStoreSettlement.Retained("GUARDIAN_PRE_READY_JOURNAL_CLEAR_FAILED")
            }
            return if (journal.load() is JournalLoadResult.Empty &&
                armStore.presence() == GuardianArmStorePresence.ABSENT
            ) {
                GuardianCrossStoreSettlement.Settled
            } else {
                GuardianCrossStoreSettlement.Retained("GUARDIAN_PRE_READY_STORES_NOT_EMPTY")
            }
        }
        val ready = arm as? GuardianArmStoreLoad.LoadedReady
            ?: return GuardianCrossStoreSettlement.Retained("GUARDIAN_READY_UNAVAILABLE")
        if (active == null) {
            active = GuardianReadyHandle(expected, ready.signed, ready.sessionKey.sha256())
            activeSessionKey = ready.sessionKey
            activeEnvelope = ready.signed.envelope
        }
        if (!requestRestore()) {
            return GuardianCrossStoreSettlement.Retained("GUARDIAN_TERMINAL_UNAVAILABLE")
        }
        val executor = GuardianTransactionReconciliationExecutor(
            journal = journal,
            armStore = armStore,
            terminalReceipts = terminalStore,
        )
        var last = "GUARDIAN_RECONCILIATION_INCOMPLETE"
        repeat(MAX_RECONCILIATION_STEPS) {
            if (journal.load() is JournalLoadResult.Empty &&
                armStore.presence() == GuardianArmStorePresence.ABSENT
            ) {
                val cleanup = cleanupAfterStoresCleared()
                return if (cleanup) {
                    active = null
                    activeSessionKey = null
                    activeEnvelope = null
                    GuardianCrossStoreSettlement.Settled
                } else {
                    GuardianCrossStoreSettlement.Retained("GUARDIAN_CLEANUP_UNCONFIRMED")
                }
            }
            val turn = executor.reconcileOnce()
            last = "${turn.outcome.disposition}:${turn.appliedStep}:${turn.completed}"
            if (BuildConfig.DIAGNOSTICS_COMPILED) {
                Log.i(TAG, "cross-store reconciliation $last")
            }
            if (turn.outcome !is GuardianTransactionOutcome.Resume || !turn.completed) {
                return GuardianCrossStoreSettlement.Retained(last)
            }
        }
        GuardianCrossStoreSettlement.Retained(last)
    }

    /**
     * Three ways to answer false, and until 2026-08-30 all three logged the same thing in
     * PRODUCTION: nothing.
     *
     * A Nova test device held at `CROSS_STORE_RECONCILIATION_REQUIRED` with
     * `settlement=GUARDIAN_TERMINAL_UNAVAILABLE`, which locates the failure at exactly this
     * function -- and then stopped, because the one line that says WHICH of the three fired sat
     * behind `BuildConfig.DIAGNOSTICS_COMPILED`, and the device ran PRODUCTION. The three are not interchangeable: an unbindable guardian is a lifecycle bug,
     * a throwing call is a transport bug, and a refusal carries the guardian's own code and is a
     * protocol answer. Three different repairs behind one silent `false`.
     *
     * So the outcome is now reported unconditionally, at `warn`, and it is **edge-safe by
     * construction rather than by throttling**: a restore request is a rare, explicitly-triggered
     * event, not a poll. `accepted=true` still says nothing -- the successful path is already
     * described by the `SERVICE restore ... restored=true` line one layer up, and duplicating it
     * here would be the once-a-second noise RC-47 removed from this daemon.
     */
    fun requestRestore(): Boolean {
        stopHeartbeat()
        val guardian = connect()
        if (guardian == null) {
            logRestoreRequestOutcome("unbindable", null)
            return false
        }
        val result = runCatching { guardian.requestRestore() }
        val bundle = result.getOrNull()
        if (bundle == null) {
            logRestoreRequestOutcome(
                "transport_failed",
                result.exceptionOrNull()?.javaClass?.simpleName ?: "null_result",
            )
            return false
        }
        val accepted = bundle.getBoolean(RestoreGuardianProcessService.KEY_SUCCESS) ||
            bundle.getString(RestoreGuardianProcessService.KEY_CODE) ==
            RestoreGuardianProcessService.CODE_TERMINAL_CONFIRMED ||
            terminalStore.loadUntil(0) is GuardianTerminalLoad.Received
        if (!accepted) {
            logRestoreRequestOutcome(
                "refused",
                bundle.getString(RestoreGuardianProcessService.KEY_CODE),
            )
        }
        return accepted
    }

    /** Reasons and identifiers only -- never a path, package, or any owner-library content. */
    private fun logRestoreRequestOutcome(outcome: String, detail: String?) {
        runCatching {
            Log.w(
                TAG,
                "restoreRequest accepted=false outcome=$outcome" +
                    if (detail == null) "" else " detail=$detail",
            )
        }
    }

    fun terminalReceipts(): GuardianTerminalReceiptPort = terminalStore

    fun cleanupAfterStoresCleared(): Boolean {
        val guardian = connect() ?: return false
        val cleaned = runCatching { guardian.cleanup() }.getOrDefault(false)
        if (cleaned) disconnect()
        return cleaned
    }

    fun currentFenceIdentity(): GenerationFenceIdentity? = synchronized(this) {
        activeEnvelope?.let(GuardianRunnerFencePolicy::identityFromAuthenticatedEnvelope)
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatCounter = 0L
        val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "mimir-guardian-heartbeat").apply { isDaemon = true }
        }.also { heartbeatScheduler = it }
        heartbeatFuture = executor.scheduleWithFixedDelay(
            { publishHeartbeat() },
            HEARTBEAT_INTERVAL_MS,
            HEARTBEAT_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    /**
     * F3 (fan fence, 2026-10-14): one heartbeat now, on the heartbeat thread. The service calls
     * this on `ACTION_SCREEN_ON`: after a suspend the Guardian's watchdog counts awake time only
     * (F3 in `RestoreGuardianRunnerEngine`), and this refreshes it at once rather than after the
     * next 5 s slot. Submitted to the ONE heartbeat executor, never run inline -- two publishers on
     * two threads would race on `heartbeatCounter`, and an out-of-order counter is a REJECTED
     * heartbeat, which is a restore. A no-op while no heartbeat runs.
     */
    fun publishHeartbeatNow() {
        val scheduler = synchronized(this) { heartbeatScheduler } ?: return
        // Rejected once the scheduler is shut down -- a normal race with stopHeartbeat, not an error.
        runCatching { scheduler.execute { publishHeartbeat() } }
    }

    private sealed interface HeartbeatStep {
        class Denied(
            val guardian: IRestoreGuardianProcess,
            val envelope: RestoreGuardianEnvelope,
            val reason: String,
        ) : HeartbeatStep

        class Beat(
            val guardian: IRestoreGuardianProcess,
            val envelope: RestoreGuardianEnvelope,
            val wire: Bundle,
        ) : HeartbeatStep
    }

    /**
     * F3 (fan fence, 2026-10-14): the binder calls run OUTSIDE the authority monitor. The Guardian
     * serves `heartbeat()`, `requestRestore()` and the restore itself on ONE worker, so a heartbeat
     * call can sit behind a running restore for the restore's whole duration (167 s on a measured
     * Odin, gen 37) -- and this used to hold `synchronized(this)` the whole time, which blocked
     * `currentFenceIdentity()` and so every fenced command the transaction worker tried. The
     * decision and the signing happen under the monitor in [prepareHeartbeat]; the call is made
     * with the monitor released; the answer is settled under the monitor again, against the
     * session that is active THEN.
     */
    private fun publishHeartbeat() {
        when (val step = synchronized(this) { prepareHeartbeat() }) {
            null -> Unit
            is HeartbeatStep.Denied -> {
                // F2, order fixed by the fence review (P1-1, 2026-10-14): retire FIRST, under the
                // monitor, and only then ask the Guardian. `requestRestore()` is synchronous on the
                // Guardian's one worker -- it returns after the restore has RUN (24 s on gen 51, up
                // to 60 s) -- and while it was out, the next tick dispatched RepairFanTarget into
                // the dead generation. With the session retired before the call, the tick's
                // hand-back override settles it instead. A step whose session moved on while it
                // was prepared requests nothing: the Guardian's restore is arm-scoped and must
                // never restore a successor.
                GuardianDenialSequence.run(
                    retireUnderMonitor = {
                        synchronized(this) {
                            val current = activeEnvelope
                            if (current == null ||
                                current.generation != step.envelope.generation ||
                                current.transactionId != step.envelope.transactionId
                            ) {
                                false
                            } else {
                                stopHeartbeat()
                                onSessionRetired(
                                    step.envelope.generation,
                                    step.envelope.transactionId,
                                    GuardianHeartbeatRetirePolicy.denialCause(step.reason),
                                )
                                true
                            }
                        }
                    },
                    requestRestore = { runCatching { step.guardian.requestRestore() } },
                )
            }
            is HeartbeatStep.Beat -> {
                val response = runCatching { step.guardian.heartbeat(step.wire) }.getOrNull()
                synchronized(this) { settleHeartbeatAnswer(step, response) }
            }
        }
    }

    private fun prepareHeartbeat(): HeartbeatStep? {
        val envelope = activeEnvelope ?: return null
        val guardian = remote ?: return null
        val now = elapsedRealtimeMs()
        if (now < 0 || now >= envelope.hardDeadlineElapsedRealtimeMs) return null
        // Phase B (§3 B3): sampled exactly once per heartbeat, before the gate reads it, so the
        // gate and (if it ever needs it later) any other reader of this heartbeat see the SAME
        // live value rather than two Settings reads racing a vendor write between them.
        //
        // F1 (fan fence, 2026-10-14): this read and the gate's own journal load both run BEFORE
        // the gate samples its clock. No `uptimeMillis()` is captured here any more -- the sample
        // the gate compares against is taken inside the comparison, so a controller tick that
        // lands during these reads is younger than the sample, not "stale by -779 ms".
        val liveFanMode = liveFanMode()
        // Restore observability: every
        // restore decision speaks unconditionally now -- this replaces the old
        // DIAGNOSTICS_COMPILED-only "manual fan controller is stale" line with the same event, at
        // WARN, carrying the actual gate reason and generation instead of a constant string.
        val denialReason = heartbeatDenialReason(envelope, liveFanMode)
        if (denialReason != null) {
            Log.w(
                TAG,
                "heartbeat=denied action=requestRestore reason=$denialReason generation=${envelope.generation}",
            )
            // The restore request itself, and the retirement, run in publishHeartbeat with the
            // monitor released -- the log line above always precedes them.
            return HeartbeatStep.Denied(guardian, envelope, denialReason)
        }
        val heartbeat = RestoreGuardianHeartbeat(
            helperBuildIdentity = envelope.helperBuildIdentity,
            bootIdentity = envelope.bootIdentity,
            generation = envelope.generation,
            requestId = envelope.requestId,
            transactionId = envelope.transactionId,
            taskNonce = envelope.taskNonce,
            envelopeSha256 = envelope.digestSha256(),
            counter = ++heartbeatCounter,
            elapsedRealtimeMs = now,
            hardDeadlineElapsedRealtimeMs = envelope.hardDeadlineElapsedRealtimeMs,
        )
        val signed = available.signHeartbeat(heartbeat)
        return HeartbeatStep.Beat(guardian, envelope, GuardianProcessBundleCodec.encodeHeartbeat(signed))
    }

    private fun settleHeartbeatAnswer(step: HeartbeatStep.Beat, response: Bundle?) {
        val accepted = response?.getBoolean(RestoreGuardianProcessService.KEY_SUCCESS) == true
        if (BuildConfig.DIAGNOSTICS_COMPILED) {
            Log.i(
                TAG,
                "heartbeat counter=$heartbeatCounter accepted=$accepted " +
                    "code=${response?.getString(RestoreGuardianProcessService.KEY_CODE)}",
            )
        }
        if (accepted) return
        if (response == null) {
            // P2-4 (fence review, 2026-10-14): a binder transport failure changes NOTHING here. The
            // heartbeat keeps its slot and the next one tries again -- the counter only has to keep
            // rising -- and if the Guardian is truly gone its own 30 s watchdog restores and its
            // receipt retires the session through the hand-back gate on the next tick. Stopping the
            // heartbeat on one hiccup used to make that restore certain 30 s later.
            Log.w(TAG, "heartbeat=transport_failed action=none generation=${step.envelope.generation}")
            return
        }
        // The session moved on (disarmed or settled) while the call was out: nothing of ours left.
        val current = activeEnvelope ?: return
        if (current.generation != step.envelope.generation || current.transactionId != step.envelope.transactionId) return
        stopHeartbeat()
        // F2: a refusal is the Guardian saying it has run `restoreActive` (gen 37: the binder
        // call sat 167 s behind the restore, then answered HEARTBEAT_REJECTED); its receipt, when
        // already on disk, names the cause more exactly than the code.
        val answer = GuardianHeartbeatAnswer.Rejected(response.getString(RestoreGuardianProcessService.KEY_CODE))
        val cause = GuardianHeartbeatRetirePolicy.retireCause(answer) {
            terminalStore.loadUntil(0) is GuardianTerminalLoad.Received
        }
        if (cause != null) {
            Log.w(TAG, "heartbeat=rejected action=retireSession cause=$cause generation=${step.envelope.generation}")
            onSessionRetired(step.envelope.generation, step.envelope.transactionId, cause)
        }
    }

    private fun stopHeartbeat() {
        heartbeatFuture?.cancel(false)
        heartbeatFuture = null
        heartbeatScheduler?.shutdownNow()
        heartbeatScheduler = null
    }

    private fun connect(): IRestoreGuardianProcess? = synchronized(connectionLock) {
        remote?.let { return it }
        val started = runCatching {
            context.startForegroundService(
                Intent(context, RestoreGuardianProcessService::class.java).apply {
                    action = RestoreGuardianProcessService.ACTION_ARM
                },
            )
            true
        }.getOrDefault(false)
        if (!started) return null
        val latch = CountDownLatch(1)
        var observed: IRestoreGuardianProcess? = null
        val candidate = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                observed = service?.let(IRestoreGuardianProcess.Stub::asInterface)
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                synchronized(connectionLock) { remote = null }
            }

            override fun onBindingDied(name: ComponentName?) {
                observed = null
                latch.countDown()
            }

            override fun onNullBinding(name: ComponentName?) {
                latch.countDown()
            }
        }
        val bound = runCatching {
            context.bindService(
                Intent(context, RestoreGuardianProcessService::class.java),
                candidate,
                Context.BIND_AUTO_CREATE,
            )
        }.getOrDefault(false)
        if (!bound) return null
        connection = candidate
        if (!latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            disconnect()
            return null
        }
        observed?.also { remote = it }
    }

    private fun disconnect() = synchronized(connectionLock) {
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
        remote = null
    }

    private companion object {
        const val BIND_TIMEOUT_MS = 5_000L
        const val HEARTBEAT_INTERVAL_MS = 5_000L
        const val MAX_RECONCILIATION_STEPS = 6
        const val TAG = "MimirGuardianHost"
    }
}

/**
 * ARMING plus an authenticated STAGED (or already-cleared) arm is proof that READY was never
 * pinned, so the host command fence could not admit a first mutation. This is the one terminal-
 * receipt-free cross-store cleanup path, and it always clears arm before journal.
 */
internal object GuardianPreReadySettlementPolicy {
    fun allows(record: TransactionJournalRecord, arm: GuardianArmStoreLoad): Boolean {
        val binding = record.guardianBinding ?: return false
        val preReady = record.schemaVersion == TRANSACTION_JOURNAL_SCHEMA_V5 &&
            record.phase == TransactionPhase.ARMING &&
            record.lastCompletedMutationBoundary == 0 &&
            binding.readySessionSha256 == null &&
            binding.terminalReceiptSha256 == null
        return preReady &&
            (arm is GuardianArmStoreLoad.LoadedStaged || arm is GuardianArmStoreLoad.Empty)
    }
}

/** Every host command shares the Guardian's app-private cross-process terminal fence. */
internal class GuardianFencedHardwareCommandPort(
    private val delegate: HardwareCommandPort,
    private val authority: RemoteGuardianProcessAuthority,
    private val fence: GuardianProcessMutationFence,
) : HardwareCommandPort by delegate {
    override fun execute(mutation: LogicalMutation): CheckedCommandResult {
        val identity = authority.currentFenceIdentity()
            ?: return CheckedCommandResult(CheckedCommandStatus.AMBIGUOUS, detail = "Guardian fence identity absent")
        return when (val fenced = fence.withApply(identity) { delegate.execute(mutation) }) {
            is GuardianFencedResult.Executed -> fenced.value
            GuardianFencedResult.Terminal -> CheckedCommandResult(
                CheckedCommandStatus.FAILED,
                detail = "Guardian terminal fence closed",
                guardianTerminal = true,
            )
            GuardianFencedResult.Rejected -> CheckedCommandResult(
                CheckedCommandStatus.AMBIGUOUS,
                detail = "Guardian fence identity rejected",
            )
            GuardianFencedResult.Ambiguous -> CheckedCommandResult(
                CheckedCommandStatus.AMBIGUOUS,
                detail = "Guardian fence unavailable",
            )
        }
    }
}
