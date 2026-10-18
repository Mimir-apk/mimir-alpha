package com.gruning.mimir.tweaker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Same-APK, separate-process rollback authority. It accepts no external package, derives every
 * transaction identity from authenticated device-protected stores, and owns no product-profile
 * selection. PServer remains only the typed hardware transport used after the terminal fence.
 */
class RestoreGuardianProcessService : Service() {
    private lateinit var protectedContext: Context
    private lateinit var pServer: PServerClient
    private lateinit var reader: SystemReader
    private lateinit var probe: HardwareProbe
    private lateinit var hardware: GuardianProcessHardware
    private lateinit var terminalStore: GuardianTerminalReceiptStore
    private lateinit var mutationFence: GuardianProcessMutationFence
    private lateinit var sessionKeys: AndroidGuardianProcessSessionKeyStore
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mimir-guardian-process").apply { isDaemon = true }
    }
    private var scheduler: ScheduledExecutorService? = null
    private var watchFuture: ScheduledFuture<*>? = null
    private var crossBootRetryFuture: ScheduledFuture<*>? = null
    private var crossBootRetryAttempts = 0
    private var sameGenerationRetry: GuardianSameGenerationRestoreRetry? = null
    private var active: ActiveGuardianProcess? = null
    private var foreground = false

    private val binder = object : IRestoreGuardianProcess.Stub() {
        override fun arm(ownerToken: IBinder?): Bundle = guarded {
            val token = ownerToken ?: return@guarded result(false, CODE_OWNER_TOKEN_MISSING)
            onWorker { armInternal(token) }
        }

        override fun confirmReady(): Boolean = guarded {
            onWorker { confirmReadyInternal() }
        }

        override fun heartbeat(signedHeartbeat: Bundle?): Bundle = guarded {
            val signed = signedHeartbeat?.let(GuardianProcessBundleCodec::decodeHeartbeat)
                ?: return@guarded result(false, CODE_HEARTBEAT_INVALID)
            onWorker { heartbeatInternal(signed) }
        }

        override fun requestRestore(): Bundle = guarded {
            onWorker { requestRestoreInternal(CODE_DAEMON_REQUESTED) }
        }

        override fun terminal(): Bundle = guarded {
            onWorker { terminalInternal() }
        }

        override fun cleanup(): Boolean = guarded {
            onWorker { cleanupInternal() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        protectedContext = createDeviceProtectedStorageContext()
        pServer = PServerClient()
        reader = SystemReader(PServerProtectedNodeAccess(pServer))
        probe = HardwareProbe(protectedContext, reader, pServer)
        hardware = GuardianProcessHardware(probe, reader, pServer)
        terminalStore = GuardianTerminalReceiptStore(
            File(protectedContext.filesDir, GuardianProcessPaths.ROOT_DIRECTORY).absoluteFile,
        )
        mutationFence = GuardianProcessMutationFence(
            File(protectedContext.filesDir, GuardianProcessPaths.FENCE_DIRECTORY).absoluteFile,
        )
        sessionKeys = AndroidGuardianProcessSessionKeyStore(protectedContext)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()
        if (!BuildConfig.HARDWARE_WRITES_COMPILED) {
            stopGuardianForeground(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_ARM) {
            // ARM is a started-FGS lifetime handoff, not a recovery request. The host
            // has already persisted STAGED truth and will bind immediately to pin
            // READY. A later process restart still enters durable recovery normally.
            return START_STICKY
        }
        worker.execute {
            recoverDurableArmIfPresent(startId)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        cancelWatch()
        cancelCrossBootStockRetry()
        cancelSameGenerationRestoreRetry()
        detachOwner(active)
        active?.sessionAuthority?.close()
        active = null
        scheduler?.shutdownNow()
        scheduler = null
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun armInternal(ownerToken: IBinder): Bundle {
        if (!BuildConfig.HARDWARE_WRITES_COMPILED) return result(false, CODE_WRITE_GATE_DISABLED)
        if (active != null) return result(false, CODE_ALREADY_ARMED)
        if (terminalStore.loadUntil(0) !is GuardianTerminalLoad.Unavailable) {
            return result(false, CODE_TERMINAL_TRUTH_RETAINED)
        }
        // The main process mints this session's device profile from the family catalog in force and may have
        // promoted a newer one since this process started. Read the same one before anything is restored from
        // it (a manual fan restore needs the same profile, hence the same fan authority). Never fails the arm.
        GuardianFamilyCatalog.installAtArm(protectedContext) { probe.invalidateStaticTopology() }
        val durable = loadDurableArm() ?: return result(false, CODE_ARM_UNAVAILABLE)
        val staged = durable.arm as? GuardianArmStoreLoad.LoadedStaged
            ?: return result(false, CODE_ARM_NOT_STAGED)
        val envelope = staged.signed.envelope
        if (liveBootIdentity() != envelope.bootIdentity || SystemClock.elapsedRealtime() >= envelope.hardDeadlineElapsedRealtimeMs) {
            return result(false, CODE_ARM_EXPIRED)
        }
        val fenceIdentity = GuardianRunnerFencePolicy.identityFromAuthenticatedEnvelope(envelope)
        if (mutationFence.stage(fenceIdentity) != GuardianFenceDisposition.CONFIRMED) {
            return result(false, CODE_FENCE_UNAVAILABLE)
        }
        val session = sessionKeys.loadOrCreate(envelope, expectedPublicKey = null)
            as? GuardianProcessSessionKeyTruth.Available
            ?: return result(false, CODE_SESSION_KEY_UNAVAILABLE)
        val collector = GuardianProcessReceiptCollector(terminalStore)
        val engine = RestoreGuardianRunnerEngine(
            receiptAuthentication = session.authority,
            receiptOutput = collector,
            sessionKey = session.publicKey,
        )
        val now = SystemClock.elapsedRealtime()
        if (
            engine.stage(
                GuardianEnvelopeVerificationOutcome.Verified(envelope),
                now,
                liveBootIdentity(),
            ).state != GuardianRunnerLifecycleState.VERIFIED_INITIAL_ENVELOPE
        ) {
            session.authority.close()
            return result(false, CODE_ARM_REJECTED)
        }
        if (engine.admitReady(SystemClock.elapsedRealtime(), SystemClock.uptimeMillis()).state != GuardianRunnerLifecycleState.READY) {
            session.authority.close()
            return result(false, CODE_READY_REJECTED)
        }
        val readyWire = collector.readyWire() ?: run {
            session.authority.close()
            return result(false, CODE_READY_REJECTED)
        }
        val deathRecipient = IBinder.DeathRecipient {
            worker.execute { requestRestoreInternal(CODE_OWNER_DIED) }
        }
        val linked = runCatching {
            ownerToken.linkToDeath(deathRecipient, 0)
            true
        }.getOrDefault(false)
        if (!linked || !ownerToken.isBinderAlive) {
            runCatching { ownerToken.unlinkToDeath(deathRecipient, 0) }
            session.authority.close()
            return result(false, CODE_OWNER_TOKEN_DEAD)
        }
        active = ActiveGuardianProcess(
            envelope = envelope,
            expected = durable.expected,
            armStore = durable.armStore,
            available = durable.available,
            engine = engine,
            collector = collector,
            sessionAuthority = session.authority,
            sessionPublicKey = session.publicKey,
            ownerToken = ownerToken,
            ownerDeath = deathRecipient,
            readyConfirmed = false,
        )
        return result(true, CODE_READY).apply { putByteArray(KEY_READY_WIRE, readyWire) }
    }

    private fun confirmReadyInternal(): Boolean {
        val current = active ?: return false
        if (current.readyConfirmed) return true
        val loaded = current.armStore.load(current.expected) as? GuardianArmStoreLoad.LoadedReady ?: return false
        if (
            loaded.sessionKey != current.sessionPublicKey ||
            loaded.signed.envelope.digestSha256() != current.envelope.digestSha256()
        ) {
            return false
        }
        if (
            current.engine.beginWatching(SystemClock.elapsedRealtime(), SystemClock.uptimeMillis()).state !=
            GuardianRunnerLifecycleState.WATCHING
        ) {
            return false
        }
        current.readyConfirmed = true
        scheduleWatch()
        return true
    }

    private fun heartbeatInternal(signed: SignedRestoreGuardianHeartbeat): Bundle {
        val current = active ?: return result(false, CODE_NOT_ARMED)
        if (!current.readyConfirmed) return result(false, CODE_READY_NOT_CONFIRMED)
        val exact = RestoreGuardianCrypto.verifyHeartbeat(
            signed,
            current.available.envelopeVerifier(),
            current.envelope,
        )
        val state = current.engine.heartbeat(
            signed.heartbeat,
            signatureValid = exact,
            now = SystemClock.elapsedRealtime(),
            nowUptimeMs = SystemClock.uptimeMillis(),
        ).state
        if (BuildConfig.DIAGNOSTICS_COMPILED) {
            Log.i(TAG, "Guardian heartbeat exact=$exact state=$state counter=${signed.heartbeat.counter}")
        }
        return if (state == GuardianRunnerLifecycleState.WATCHING) {
            result(true, CODE_HEARTBEAT_ACCEPTED)
        } else {
            restoreActive(current, CODE_HEARTBEAT_REJECTED)
        }
    }

    private fun requestRestoreInternal(cause: String): Bundle {
        when (val terminal = terminalStore.loadUntil(0)) {
            is GuardianTerminalLoad.Received -> return terminalInternal()
            is GuardianTerminalLoad.Ambiguous -> {
                val current = active
                if (current == null || !current.collector.hasPendingTerminalReceipt()) {
                    return result(false, CODE_TERMINAL_UNAVAILABLE)
                }
            }
            GuardianTerminalLoad.Unavailable -> Unit
            GuardianTerminalLoad.TimedOut -> return result(false, CODE_TERMINAL_UNAVAILABLE)
        }
        val current = active ?: return recoverDurableArmForRequest(cause)
        current.engine.ownerCancelled()
        return restoreActive(current, cause)
    }

    private fun restoreActive(
        current: ActiveGuardianProcess,
        cause: String,
        scheduleRetry: Boolean = true,
    ): Bundle {
        // Restore observability: every
        // restore decision speaks unconditionally now -- this line used to be
        // DIAGNOSTICS_COMPILED-only, which is exactly a diagnostics posture rather than production visibility, so a device-only restore cause was invisible in production. Cause string only.
        Log.w(TAG, "Guardian restore requested cause=$cause")
        val currentState = current.engine.ownerCancelled().state
        if (currentState == GuardianRunnerLifecycleState.STOCK_CONFIRMED) {
            val terminalOutcome = finalizeStockConfirmedTerminal(current)
            if (terminalOutcome.getBoolean(KEY_SUCCESS)) cancelSameGenerationRestoreRetry()
            if (scheduleRetry) scheduleSameGenerationRestoreRetry(current, terminalOutcome)
            return terminalOutcome
        }
        cancelWatch()
        val liveBoot = liveBootIdentity()
        if (liveBoot == null) {
            val outcome = result(false, CODE_BOOT_UNAVAILABLE)
            if (scheduleRetry) scheduleSameGenerationRestoreRetry(current, outcome)
            return outcome
        }
        val identity = GuardianRunnerFencePolicy.identityFromAuthenticatedEnvelope(current.envelope)
        val terminalized = GuardianFenceAmbiguityRetry.execute(Thread::sleep) {
            mutationFence.terminalizeAndRestoreWithPrior(identity) { terminalWasAbsent ->
                val observed = hardware.observe(current.envelope.restoreEntries)
                    ?: return@terminalizeAndRestoreWithPrior RestoreExecution(false, CODE_OBSERVATION_UNAVAILABLE)
                val evidence = if (liveBoot == current.envelope.bootIdentity) {
                    GuardianRestoreFenceEvidence.SameBoot(
                        FenceDiskState(true, identity, identity, true),
                    )
                } else {
                    GuardianRestoreFenceEvidence.DifferentBootPersistent(
                        currentBootIdentity = liveBoot,
                        proof = GuardianPersistentRestoreFenceProof(
                            directoryPresent = true,
                            lockAcquired = true,
                            terminalMarkerAbsent = terminalWasAbsent,
                        ),
                    )
                }
                val changedBootReadbackRetry =
                    liveBoot != current.envelope.bootIdentity && !terminalWasAbsent
                val restoreIntents = if (changedBootReadbackRetry) {
                    if (current.engine.ownerCancelled().state != GuardianRunnerLifecycleState.RESTORING) {
                        return@terminalizeAndRestoreWithPrior RestoreExecution(false, CODE_RESTORE_NOT_ADMITTED)
                    }
                    // The exact terminal fence already blocks every later apply. A retry
                    // may only re-read Stock; persistent writes are never replayed.
                    emptyList()
                } else {
                    val planned = current.engine.planRestore(liveBoot, evidence, observed)
                    if (planned.state != GuardianRunnerLifecycleState.RESTORING) {
                        return@terminalizeAndRestoreWithPrior RestoreExecution(false, CODE_RESTORE_NOT_ADMITTED)
                    }
                    planned.restoreIntents
                }
                if (!hardware.execute(restoreIntents)) {
                    return@terminalizeAndRestoreWithPrior RestoreExecution(false, CODE_RESTORE_COMMAND_FAILED)
                }
                // Housekeeping for the player-owned vendor mode: release its origin record once the
                // origin is confirmed live. Never writes; a no-op when the envelope names no such entry.
                hardware.settleVendorOrigin(current.envelope.restoreEntries)
                val first = hardware.readback(
                    current.envelope.restoreEntries,
                    persistentOnly = false,
                ) ?: return@terminalizeAndRestoreWithPrior RestoreExecution(false, CODE_READBACK_UNAVAILABLE)
                Thread.sleep(GuardianDoubleStockReadbackPolicy.MIN_SETTLE_GAP_MS)
                val second = hardware.readback(
                    current.envelope.restoreEntries,
                    persistentOnly = false,
                ) ?: return@terminalizeAndRestoreWithPrior RestoreExecution(false, CODE_READBACK_UNAVAILABLE)
                val confirmed = current.engine.confirmStock(first, second)
                when (confirmed.state) {
                    GuardianRunnerLifecycleState.STOCK_CONFIRMED -> {
                        // UNCONDITIONAL, for the same reason as its sibling below, and rarer still:
                        // it fires only when Stock was confirmed WITH an axis that no longer vetoes
                        // ([vetoesWindowedVerification]). A confirmation that quietly stopped checking
                        // something would be the 08-30 silence all over again, so the clean case stays
                        // silent and the suppressed case says which axis and what it read.
                        runCatching {
                            val described = GuardianDoubleStockReadbackPolicy.describe(
                                current.envelope.restoreEntries,
                                first,
                                second,
                            )
                            if (described.contains("unheld=")) {
                                Log.w(TAG, "Guardian stock confirmed $described")
                            }
                        }
                        if (current.collector.hasPendingTerminalReceipt()) {
                            val pending = current.collector.retryPendingTerminalReceipt()
                            val exited = if (pending == GuardianReceiptOutputDisposition.CONFIRMED) {
                                current.engine.exit(SystemClock.elapsedRealtime())
                            } else {
                                null
                            }
                            if (exited?.state == GuardianRunnerLifecycleState.EXITED &&
                                current.collector.terminalAvailable()
                            ) {
                                signalMainSettlement(current.envelope)
                                RestoreExecution(true, CODE_TERMINAL_CONFIRMED)
                            } else {
                                RestoreExecution(false, CODE_TERMINAL_UNAVAILABLE)
                            }
                        } else {
                        val terminal = terminalStore.loadUntil(0)
                        if (terminal is GuardianTerminalLoad.Ambiguous || terminal is GuardianTerminalLoad.TimedOut) {
                            if (!current.collector.hasPendingTerminalReceipt()) {
                                return@terminalizeAndRestoreWithPrior RestoreExecution(
                                    false,
                                    CODE_TERMINAL_UNAVAILABLE,
                                )
                            }
                        }
                        val exited = current.engine.exit(SystemClock.elapsedRealtime())
                        if (exited.state == GuardianRunnerLifecycleState.EXITED &&
                            current.collector.terminalAvailable()
                        ) {
                            signalMainSettlement(current.envelope)
                            RestoreExecution(true, CODE_TERMINAL_CONFIRMED)
                        } else {
                            RestoreExecution(false, CODE_TERMINAL_UNAVAILABLE)
                        }
                        }
                    }
                    else -> {
                        // UNCONDITIONAL, unlike every other log in this process. On 2026-08-30 the
                        // measured Nova sat at STOCK_UNCONFIRMED through three daemon installs and
                        // every force-stop, and this branch -- the one that decided it -- said
                        // nothing, because all guardian logging here is behind
                        // BuildConfig.DIAGNOSTICS_COMPILED and PRODUCTION is what the reference devices run.
                        // A terminal refusal that cannot say why is a hold nobody can clear.
                        //
                        // Rare by construction, so it needs no throttle: this fires once per
                        // restore attempt, and only on the failing branch. The success path stays
                        // silent -- it is already described by CODE_TERMINAL_CONFIRMED upstream.
                        runCatching {
                            Log.w(
                                TAG,
                                "Guardian stock unconfirmed persistentAxes=" +
                                    current.envelope.restoreEntries.count { it.isPersistentGuardianAxis() } +
                                    " " + GuardianDoubleStockReadbackPolicy.describe(
                                    current.envelope.restoreEntries,
                                    first,
                                    second,
                                ),
                            )
                        }
                        RestoreExecution(false, CODE_STOCK_UNCONFIRMED)
                    }
                }
            }
        }
        val outcome = when (terminalized) {
            is GuardianFencedResult.Executed -> result(
                terminalized.value.success,
                terminalized.value.code,
            ).apply { putString(KEY_CAUSE, cause) }
            GuardianFencedResult.Terminal -> terminalInternal()
            GuardianFencedResult.Rejected -> result(false, CODE_FENCE_REJECTED)
            GuardianFencedResult.Ambiguous -> result(false, CODE_FENCE_AMBIGUOUS)
        }
        if (BuildConfig.DIAGNOSTICS_COMPILED) {
            Log.i(
                TAG,
                "Guardian restore result cause=$cause accepted=${outcome.getBoolean(KEY_SUCCESS)} " +
                    "code=${outcome.getString(KEY_CODE)}",
            )
        }
        if (outcome.getBoolean(KEY_SUCCESS)) cancelSameGenerationRestoreRetry()
        if (scheduleRetry) scheduleSameGenerationRestoreRetry(current, outcome)
        return outcome
    }

    /** Finish EXITED from Stock without observing or replaying any hardware restore command. */
    private fun finalizeStockConfirmedTerminal(current: ActiveGuardianProcess): Bundle {
        return when (val terminal = terminalStore.loadUntil(0)) {
            is GuardianTerminalLoad.Received -> {
                val terminalResult = terminalInternal()
                if (!terminalResult.getBoolean(KEY_SUCCESS)) {
                    terminalResult
                } else if (current.engine.exit(SystemClock.elapsedRealtime()).state ==
                    GuardianRunnerLifecycleState.EXITED
                ) {
                    signalMainSettlement(current.envelope)
                    terminalResult
                } else {
                    result(false, CODE_TERMINAL_UNAVAILABLE)
                }
            }
            is GuardianTerminalLoad.Ambiguous,
            GuardianTerminalLoad.TimedOut,
            -> {
                if (!current.collector.hasPendingTerminalReceipt()) {
                    result(false, CODE_TERMINAL_UNAVAILABLE)
                } else {
                    finishOwnedPendingTerminal(current)
                }
            }
            GuardianTerminalLoad.Unavailable -> finishOwnedPendingTerminal(current)
        }
    }

    private fun finishOwnedPendingTerminal(current: ActiveGuardianProcess): Bundle {
        if (current.collector.hasPendingTerminalReceipt() &&
            current.collector.retryPendingTerminalReceipt() != GuardianReceiptOutputDisposition.CONFIRMED
        ) {
            return result(false, CODE_TERMINAL_UNAVAILABLE)
        }
        val exited = current.engine.exit(SystemClock.elapsedRealtime())
        return if (exited.state == GuardianRunnerLifecycleState.EXITED &&
            current.collector.terminalAvailable()
        ) {
            signalMainSettlement(current.envelope)
            result(true, CODE_TERMINAL_CONFIRMED)
        } else {
            result(false, CODE_TERMINAL_UNAVAILABLE)
        }
    }

    private fun terminalInternal(): Bundle {
        val terminal = terminalStore.loadUntil(0) as? GuardianTerminalLoad.Received
            ?: return result(false, CODE_TERMINAL_UNAVAILABLE)
        val exited = terminal.exited ?: return result(false, CODE_TERMINAL_UNAVAILABLE)
        val wire = GuardianTerminalWireCodec.encode(terminal.stock, exited)
            ?: return result(false, CODE_TERMINAL_UNAVAILABLE)
        return result(true, CODE_TERMINAL_CONFIRMED).apply {
            putByteArray(KEY_TERMINAL_WIRE, wire)
        }
    }

    private fun cleanupInternal(): Boolean {
        val current = active
        val journalEmpty = createAuthenticatedTransactionJournal(protectedContext).load() is JournalLoadResult.Empty
        val armAbsent = current?.armStore?.presence() == GuardianArmStorePresence.ABSENT ||
            loadArmPresence() == GuardianArmStorePresence.ABSENT
        if (!journalEmpty || !armAbsent || terminalStore.loadUntil(0) !is GuardianTerminalLoad.Received) {
            return false
        }
        val cleanedKey = current?.let {
            sessionKeys.deleteExpected(it.envelope, it.sessionPublicKey)
        } ?: true
        val cleanedFence = current?.let {
            mutationFence.clearTerminal(GuardianRunnerFencePolicy.identityFromAuthenticatedEnvelope(it.envelope))
        } ?: true
        if (!cleanedKey || !cleanedFence || !terminalStore.clear()) return false
        cancelSameGenerationRestoreRetry()
        cancelWatch()
        detachOwner(current)
        current?.sessionAuthority?.close()
        active = null
        stopGuardianForeground()
        return true
    }

    private fun recoverDurableArmIfPresent(startId: Int) {
        if (terminalStore.loadUntil(0) is GuardianTerminalLoad.Received) {
            stopGuardianForeground(startId)
            return
        }
        val durable = loadDurableArm()
        val ready = durable?.arm as? GuardianArmStoreLoad.LoadedReady
        if (durable == null || ready == null) {
            stopGuardianForeground(startId)
            return
        }
        val existing = active
        if (
            existing != null &&
            guardianRecoveryIdentityMatches(
                existing.envelope,
                existing.sessionPublicKey,
                ready.signed.envelope,
                ready.sessionKey,
            )
        ) {
            recoverExistingActive(existing, startId)
            return
        }
        val session = sessionKeys.loadOrCreate(ready.signed.envelope, ready.sessionKey)
            as? GuardianProcessSessionKeyTruth.Available
        if (session == null) {
            return // Retain foreground and durable truth; no unauthenticated recovery attempt.
        }
        val collector = GuardianProcessReceiptCollector(terminalStore)
        val engine = RestoreGuardianRunnerEngine(
            receiptAuthentication = session.authority,
            receiptOutput = collector,
            sessionKey = session.publicKey,
        )
        val resumed = engine.resumeFromDurableReady(
            ready,
            SystemClock.elapsedRealtime(),
            liveBootIdentity(),
            nowUptimeMs = SystemClock.uptimeMillis(),
        )
        if (resumed.state !in setOf(
                GuardianRunnerLifecycleState.WATCHING,
                GuardianRunnerLifecycleState.RESTORING,
            )
        ) {
            session.authority.close()
            return
        }
        val recovered = ActiveGuardianProcess(
            envelope = ready.signed.envelope,
            expected = durable.expected,
            armStore = durable.armStore,
            available = durable.available,
            engine = engine,
            collector = collector,
            sessionAuthority = session.authority,
            sessionPublicKey = session.publicKey,
            ownerToken = null,
            ownerDeath = null,
            readyConfirmed = true,
        )
        if (active !== recovered) cancelSameGenerationRestoreRetry()
        active = recovered
        val recoveryPlan = guardianRecoveryRetryPlan(resumed.state)
        if (resumed.state == GuardianRunnerLifecycleState.WATCHING) {
            engine.ownerDecision(GuardianOwnerDecision.OWNER_GONE)
        }
        restoreActive(
            recovered,
            CODE_PROCESS_RECOVERY,
            scheduleRetry = recoveryPlan == GuardianRecoveryRetryPlan.SAME_GENERATION,
        )
        if (
            recoveryPlan == GuardianRecoveryRetryPlan.EXISTING_CROSS_BOOT &&
            terminalStore.loadUntil(0) !is GuardianTerminalLoad.Received
        ) {
            scheduleCrossBootStockRetry(recovered, startId)
        }
        if (terminalStore.loadUntil(0) is GuardianTerminalLoad.Received) {
            stopGuardianForeground(startId)
        }
    }

    private fun recoverExistingActive(
        current: ActiveGuardianProcess,
        startId: Int,
    ): Bundle {
        val outcome = restoreActive(
            current,
            CODE_PROCESS_RECOVERY,
            scheduleRetry = crossBootRetryFuture == null,
        )
        if (outcome.getBoolean(KEY_SUCCESS)) stopGuardianForeground(startId)
        return outcome
    }

    private fun recoverDurableArmForRequest(cause: String): Bundle {
        recoverDurableArmIfPresent(0)
        val recovered = active ?: return result(false, CODE_NOT_ARMED)
        if (recovered.engine.ownerCancelled().state != GuardianRunnerLifecycleState.RESTORING) {
            return result(false, CODE_RESTORE_NOT_ADMITTED)
        }
        return restoreActive(recovered, cause)
    }

    private fun loadDurableArm(): DurableGuardianArm? {
        val journal = createAuthenticatedTransactionJournal(protectedContext).load()
            as? JournalLoadResult.Loaded ?: return null
        val expected = GuardianArmExpectedIdentityDerivation.from(journal.record) ?: return null
        val available = DirectBootKeyAuthority(protectedContext).inspect()
            as? DirectBootKeyAuthorityTruth.Available ?: return null
        val armStore = FileRestoreGuardianArmStore(
            available,
            File(protectedContext.filesDir, GuardianProcessPaths.ARM_DIRECTORY).absoluteFile,
        )
        return DurableGuardianArm(
            expected,
            armStore,
            armStore.load(expected),
            available,
        )
    }

    private fun loadArmPresence(): GuardianArmStorePresence {
        val available = DirectBootKeyAuthority(protectedContext).inspect()
            as? DirectBootKeyAuthorityTruth.Available ?: return GuardianArmStorePresence.UNREADABLE
        return FileRestoreGuardianArmStore(
            available,
            File(protectedContext.filesDir, GuardianProcessPaths.ARM_DIRECTORY).absoluteFile,
        ).presence()
    }

    private fun scheduleSameGenerationRestoreRetry(
        current: ActiveGuardianProcess,
        outcome: Bundle,
    ) {
        if (outcome.getBoolean(KEY_SUCCESS) || !sameGenerationRetryEligible(current, outcome)) return
        val executor = scheduler ?: Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "mimir-guardian-watch").apply { isDaemon = true }
        }.also { scheduler = it }
        val retry = sameGenerationRetry ?: GuardianSameGenerationRestoreRetry(
            scheduler = executor,
            worker = worker,
        ).also { sameGenerationRetry = it }
        retry.schedule(
            token = current,
            guard = { sameGenerationRetryAdmitted(current) },
            attempt = {
                if (active !== current) {
                    GuardianSameGenerationRetryResult.HELD
                } else {
                    val state = current.engine.ownerCancelled().state
                    if (state == GuardianRunnerLifecycleState.STOCK_CONFIRMED) {
                        val finalized = finalizeStockConfirmedTerminal(current)
                        if (finalized.getBoolean(KEY_SUCCESS)) {
                            GuardianSameGenerationRetryResult.SETTLED
                        } else if (sameGenerationRetryEligible(current, finalized)) {
                            GuardianSameGenerationRetryResult.RETRY
                        } else {
                            GuardianSameGenerationRetryResult.HELD
                        }
                    } else {
                        val retried = restoreActive(
                            current,
                            CODE_SAME_GENERATION_RETRY,
                            scheduleRetry = false,
                        )
                        if (retried.getBoolean(KEY_SUCCESS)) {
                            GuardianSameGenerationRetryResult.SETTLED
                        } else if (sameGenerationRetryEligible(current, retried)) {
                            GuardianSameGenerationRetryResult.RETRY
                        } else {
                            GuardianSameGenerationRetryResult.HELD
                        }
                    }
                }
            },
        )
    }

    private fun sameGenerationRetryEligible(
        current: ActiveGuardianProcess,
        outcome: Bundle,
    ): Boolean {
        val code = outcome.getString(KEY_CODE) ?: return false
        if (code !in SAME_GENERATION_RETRY_CODES) return false
        val state = current.engine.ownerCancelled().state
        return state == GuardianRunnerLifecycleState.RESTORING ||
            (state == GuardianRunnerLifecycleState.STOCK_CONFIRMED &&
                current.collector.hasPendingTerminalReceipt())
    }

    /** Admission is fail-closed for identity, arm, fence, and unrelated durable truth. */
    private fun sameGenerationRetryAdmitted(current: ActiveGuardianProcess): Boolean {
        if (active !== current) return false
        val loaded = current.armStore.load(current.expected) as? GuardianArmStoreLoad.LoadedReady
            ?: return false
        if (loaded.sessionKey != current.sessionPublicKey ||
            loaded.signed.envelope.digestSha256() != current.envelope.digestSha256()
        ) return false
        val identity = GuardianRunnerFencePolicy.identityFromAuthenticatedEnvelope(current.envelope)
        val fence = mutationFence.state(identity)
        if (!fence.directoryPresent || !fence.lockAcquired || fence.sessionMarkerIdentity != identity) {
            return false
        }
        val terminal = terminalStore.loadUntil(0)
        return when (terminal) {
            GuardianTerminalLoad.Unavailable -> true
            is GuardianTerminalLoad.Ambiguous,
            GuardianTerminalLoad.TimedOut,
            -> current.collector.hasPendingTerminalReceipt()
            is GuardianTerminalLoad.Received -> false
        }
    }

    private fun scheduleWatch() {
        cancelWatch()
        val executor = scheduler ?: Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "mimir-guardian-watch").apply { isDaemon = true }
        }.also { scheduler = it }
        watchFuture = executor.scheduleWithFixedDelay(
            {
                worker.execute {
                    val current = active ?: return@execute
                    if (!current.readyConfirmed) return@execute
                    // F3 (fan fence): staleness on the CPU-awake clock, the deadline on boottime.
                    val state = current.engine.watchTick(SystemClock.elapsedRealtime(), SystemClock.uptimeMillis()).state
                    if (state == GuardianRunnerLifecycleState.RESTORING) {
                        restoreActive(current, CODE_WATCHDOG_EXPIRED)
                    }
                }
            },
            WATCH_INTERVAL_MS,
            WATCH_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    /**
     * Odin's vendor compute owner can settle minutes after LOCKED_BOOT_COMPLETED. Keep the
     * exact terminal fence closed and retry only complete Stock readback on the worker; never
     * replay persistent restore commands after the first changed-boot attempt.
     */
    private fun scheduleCrossBootStockRetry(current: ActiveGuardianProcess, startId: Int) {
        cancelCrossBootStockRetry()
        crossBootRetryAttempts = 0
        val executor = scheduler ?: Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "mimir-guardian-watch").apply { isDaemon = true }
        }.also { scheduler = it }
        crossBootRetryFuture = executor.scheduleWithFixedDelay(
            {
                worker.execute {
                    if (active !== current) {
                        cancelCrossBootStockRetry()
                        return@execute
                    }
                    if (terminalStore.loadUntil(0) is GuardianTerminalLoad.Received) {
                        cancelCrossBootStockRetry()
                        stopGuardianForeground(startId)
                        return@execute
                    }
                    if (crossBootRetryAttempts >= CROSS_BOOT_STOCK_RETRY_ATTEMPTS) {
                        cancelCrossBootStockRetry()
                        return@execute // Retain foreground and durable truth for a later start.
                    }
                    crossBootRetryAttempts += 1
                    if (BuildConfig.DIAGNOSTICS_COMPILED) {
                        Log.i(TAG, "Guardian cross-boot Stock retry=$crossBootRetryAttempts")
                    }
                    restoreActive(current, CODE_PROCESS_RECOVERY, scheduleRetry = false)
                    if (terminalStore.loadUntil(0) is GuardianTerminalLoad.Received) {
                        cancelCrossBootStockRetry()
                        stopGuardianForeground(startId)
                    }
                }
            },
            CROSS_BOOT_STOCK_RETRY_INTERVAL_MS,
            CROSS_BOOT_STOCK_RETRY_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun cancelWatch() {
        watchFuture?.cancel(false)
        watchFuture = null
    }

    private fun cancelCrossBootStockRetry() {
        crossBootRetryFuture?.cancel(false)
        crossBootRetryFuture = null
    }

    private fun cancelSameGenerationRestoreRetry() {
        sameGenerationRetry?.cancel()
        sameGenerationRetry = null
    }

    /** Terminal truth is durable before this signal; failure leaves both stores retained. */
    private fun signalMainSettlement(envelope: RestoreGuardianEnvelope) {
        val signalled = runCatching {
            startForegroundService(
                Intent(this, MimirTweakerService::class.java).apply {
                    action = MimirTweakerService.ACTION_GUARDIAN_SETTLEMENT
                    putExtra(MimirTweakerService.EXTRA_RECOVERY_GENERATION, envelope.generation)
                    putExtra(MimirTweakerService.EXTRA_RECOVERY_TRANSACTION_ID, envelope.transactionId)
                },
            )
            true
        }.getOrDefault(false)
        if (BuildConfig.DIAGNOSTICS_COMPILED) {
            Log.i(TAG, "Guardian terminal settlement signal=$signalled")
        }
    }

    private fun liveBootIdentity(): String? = reader.read(LIVE_BOOT_ID_PATH)?.trim()?.takeIf(String::isNotEmpty)

    private fun ensureForeground() {
        if (foreground) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Mimir recovery guardian", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Hardware recovery protection is active")
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        foreground = true
    }

    private fun stopGuardianForeground(startId: Int? = null) {
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        if (startId != null && startId > 0) stopSelfResult(startId) else stopSelf()
    }

    private fun detachOwner(current: ActiveGuardianProcess?) {
        val token = current?.ownerToken ?: return
        val death = current.ownerDeath ?: return
        runCatching { token.unlinkToDeath(death, 0) }
    }

    private fun <T> guarded(block: () -> T): T {
        val caller = Binder.getCallingUid()
        if (!GuardianProcessCallerPolicy.allows(caller, applicationInfo.uid)) {
            throw SecurityException("Guardian caller UID rejected")
        }
        return block()
    }

    private fun <T> onWorker(block: () -> T): T {
        if (Thread.currentThread().name == "mimir-guardian-process") return block()
        return worker.submit<T> { block() }.get(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private data class ActiveGuardianProcess(
        val envelope: RestoreGuardianEnvelope,
        val expected: RestoreGuardianExpectedIdentity,
        val armStore: RestoreGuardianArmStore,
        val available: DirectBootKeyAuthorityTruth.Available,
        val engine: RestoreGuardianRunnerEngine,
        val collector: GuardianProcessReceiptCollector,
        val sessionAuthority: GuardianProcessSessionAuthority,
        val sessionPublicKey: GuardianSessionPublicKey,
        val ownerToken: IBinder?,
        val ownerDeath: IBinder.DeathRecipient?,
        var readyConfirmed: Boolean,
    )

    private data class DurableGuardianArm(
        val expected: RestoreGuardianExpectedIdentity,
        val armStore: RestoreGuardianArmStore,
        val arm: GuardianArmStoreLoad,
        val available: DirectBootKeyAuthorityTruth.Available,
    )

    private data class RestoreExecution(val success: Boolean, val code: String)

    companion object {
        const val ACTION_ARM = "com.gruning.mimir.tweaker.action.GUARDIAN_ARM"
        const val ACTION_RECOVER = "com.gruning.mimir.tweaker.action.GUARDIAN_RECOVER"
        private const val LIVE_BOOT_ID_PATH = "/proc/sys/kernel/random/boot_id"
        private const val CHANNEL_ID = "mimir_guardian_recovery"
        private const val NOTIFICATION_ID = 0x4D47
        private const val WATCH_INTERVAL_MS = 1_000L
        private const val CROSS_BOOT_STOCK_RETRY_ATTEMPTS = 120
        private const val CROSS_BOOT_STOCK_RETRY_INTERVAL_MS = 2_500L
        private const val WORKER_TIMEOUT_SECONDS = 60L
        private const val TAG = "MimirGuardianProcess"

        internal const val KEY_SUCCESS = "success"
        internal const val KEY_CODE = "code"
        internal const val KEY_READY_WIRE = "readyWire"
        internal const val KEY_TERMINAL_WIRE = "terminalWire"
        internal const val KEY_CAUSE = "cause"

        internal const val CODE_READY = "READY"
        internal const val CODE_READY_REJECTED = "READY_REJECTED"
        internal const val CODE_READY_NOT_CONFIRMED = "READY_NOT_CONFIRMED"
        internal const val CODE_HEARTBEAT_ACCEPTED = "HEARTBEAT_ACCEPTED"
        internal const val CODE_HEARTBEAT_INVALID = "HEARTBEAT_INVALID"
        internal const val CODE_HEARTBEAT_REJECTED = "HEARTBEAT_REJECTED"
        internal const val CODE_TERMINAL_CONFIRMED = "TERMINAL_CONFIRMED"
        internal const val CODE_TERMINAL_UNAVAILABLE = "TERMINAL_UNAVAILABLE"
        internal const val CODE_REBOOT_PERSISTENT_RETAINED = "REBOOT_PERSISTENT_RETAINED"
        private const val CODE_OWNER_TOKEN_MISSING = "OWNER_TOKEN_MISSING"
        private const val CODE_OWNER_TOKEN_DEAD = "OWNER_TOKEN_DEAD"
        private const val CODE_OWNER_DIED = "OWNER_DIED"
        private const val CODE_DAEMON_REQUESTED = "DAEMON_REQUESTED"
        private const val CODE_WATCHDOG_EXPIRED = "WATCHDOG_EXPIRED"
        private const val CODE_PROCESS_RECOVERY = "PROCESS_RECOVERY"
        private const val CODE_SAME_GENERATION_RETRY = "SAME_GENERATION_RETRY"
        private const val CODE_WRITE_GATE_DISABLED = "WRITE_GATE_DISABLED"
        private const val CODE_ALREADY_ARMED = "ALREADY_ARMED"
        private const val CODE_NOT_ARMED = "NOT_ARMED"
        private const val CODE_TERMINAL_TRUTH_RETAINED = "TERMINAL_TRUTH_RETAINED"
        private const val CODE_ARM_UNAVAILABLE = "ARM_UNAVAILABLE"
        private const val CODE_ARM_NOT_STAGED = "ARM_NOT_STAGED"
        private const val CODE_ARM_EXPIRED = "ARM_EXPIRED"
        private const val CODE_ARM_REJECTED = "ARM_REJECTED"
        private const val CODE_FENCE_UNAVAILABLE = "FENCE_UNAVAILABLE"
        private const val CODE_FENCE_REJECTED = "FENCE_REJECTED"
        private const val CODE_FENCE_AMBIGUOUS = "FENCE_AMBIGUOUS"
        private const val CODE_SESSION_KEY_UNAVAILABLE = "SESSION_KEY_UNAVAILABLE"
        private const val CODE_BOOT_UNAVAILABLE = "BOOT_UNAVAILABLE"
        private const val CODE_OBSERVATION_UNAVAILABLE = "OBSERVATION_UNAVAILABLE"
        private const val CODE_RESTORE_NOT_ADMITTED = "RESTORE_NOT_ADMITTED"
        private const val CODE_RESTORE_COMMAND_FAILED = "RESTORE_COMMAND_FAILED"
        private const val CODE_READBACK_UNAVAILABLE = "READBACK_UNAVAILABLE"
        private const val CODE_STOCK_UNCONFIRMED = "STOCK_UNCONFIRMED"
        private val SAME_GENERATION_RETRY_CODES = setOf(
            CODE_BOOT_UNAVAILABLE,
            CODE_OBSERVATION_UNAVAILABLE,
            CODE_RESTORE_COMMAND_FAILED,
            CODE_READBACK_UNAVAILABLE,
            CODE_STOCK_UNCONFIRMED,
            CODE_TERMINAL_UNAVAILABLE,
            CODE_SAME_GENERATION_RETRY,
        )

        private fun result(success: Boolean, code: String) = Bundle().apply {
            putBoolean(KEY_SUCCESS, success)
            putString(KEY_CODE, code)
        }
    }
}

internal enum class GuardianRecoveryRetryPlan {
    SAME_GENERATION,
    EXISTING_CROSS_BOOT,
    NONE,
}

internal fun guardianRecoveryRetryPlan(
    resumedState: GuardianRunnerLifecycleState,
): GuardianRecoveryRetryPlan = when (resumedState) {
    GuardianRunnerLifecycleState.WATCHING -> GuardianRecoveryRetryPlan.SAME_GENERATION
    GuardianRunnerLifecycleState.RESTORING -> GuardianRecoveryRetryPlan.EXISTING_CROSS_BOOT
    else -> GuardianRecoveryRetryPlan.NONE
}

internal fun guardianRecoveryIdentityMatches(
    activeEnvelope: RestoreGuardianEnvelope,
    activeSessionKey: GuardianSessionPublicKey,
    durableEnvelope: RestoreGuardianEnvelope,
    durableSessionKey: GuardianSessionPublicKey,
): Boolean =
    activeSessionKey == durableSessionKey &&
        activeEnvelope.digestSha256() == durableEnvelope.digestSha256()

internal enum class GuardianSameGenerationRetryResult {
    RETRY,
    HELD,
    SETTLED,
}

/** One Guardian-owned, generation-scoped retry queue with a finite attempt budget. */
internal class GuardianSameGenerationRestoreRetry(
    private val scheduler: ScheduledExecutorService,
    private val worker: Executor,
    private val maxAttempts: Int = 4,
    private val initialDelayMs: Long = 250L,
    private val maxDelayMs: Long = 2_500L,
) {
    init {
        require(maxAttempts > 0)
        require(initialDelayMs >= 0)
        require(maxDelayMs >= initialDelayMs)
    }

    private val lock = Any()
    private var future: ScheduledFuture<*>? = null
    private var running = false
    private var attempts = 0
    private var token: Any? = null

    fun schedule(
        token: Any,
        guard: () -> Boolean,
        attempt: () -> GuardianSameGenerationRetryResult,
    ) {
        synchronized(lock) {
            if (future != null || running || attempts >= maxAttempts) return
            this.token = token
            val ordinal = attempts++
            val delay = minOf(maxDelayMs, initialDelayMs * (1L shl ordinal.coerceAtMost(20)))
            future = scheduler.schedule(
                {
                    val queued = synchronized(lock) {
                        if (this.token !== token) {
                            false
                        } else {
                            future = null
                            running = true
                            true
                        }
                    }
                    if (!queued) return@schedule
                    try {
                        worker.execute {
                            val admitted = synchronized(lock) {
                                if (this.token !== token) {
                                    running = false
                                    false
                                } else {
                                    true
                                }
                            }
                            val result = if (!admitted) {
                                GuardianSameGenerationRetryResult.HELD
                            } else {
                                runCatching {
                                    if (!stillAdmitted(token) || !guard() || !stillAdmitted(token)) {
                                        GuardianSameGenerationRetryResult.HELD
                                    } else {
                                        attempt()
                                    }
                                }.getOrDefault(GuardianSameGenerationRetryResult.HELD)
                            }
                            synchronized(lock) {
                                running = false
                                if (result != GuardianSameGenerationRetryResult.RETRY) {
                                    this@GuardianSameGenerationRestoreRetry.token = null
                                    attempts = maxAttempts
                                }
                            }
                            if (result == GuardianSameGenerationRetryResult.RETRY) {
                                schedule(token, guard, attempt)
                            }
                        }
                    } catch (_: RuntimeException) {
                        synchronized(lock) {
                            running = false
                            this@GuardianSameGenerationRestoreRetry.token = null
                            attempts = maxAttempts
                        }
                    }
                },
                delay,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    private fun stillAdmitted(token: Any): Boolean = synchronized(lock) { this.token === token }

    fun cancel() {
        synchronized(lock) {
            token = null
            future?.cancel(false)
            future = null
        }
    }
}

internal object GuardianProcessPaths {
    const val ROOT_DIRECTORY = "restore_guardian_process"
    const val ARM_DIRECTORY = "restore_guardian_arm"
    const val FENCE_DIRECTORY = "restore_guardian_process/fence"
}

internal object GuardianProcessBundleCodec {
    private const val KEY_SCHEMA = "schema"
    private const val KEY_HELPER = "helper"
    private const val KEY_BOOT = "boot"
    private const val KEY_GENERATION = "generation"
    private const val KEY_REQUEST = "request"
    private const val KEY_TRANSACTION = "transaction"
    private const val KEY_NONCE = "nonce"
    private const val KEY_ENVELOPE = "envelope"
    private const val KEY_COUNTER = "counter"
    private const val KEY_ELAPSED = "elapsed"
    private const val KEY_DEADLINE = "deadline"
    private const val KEY_SIGNATURE = "signature"

    fun encodeHeartbeat(signed: SignedRestoreGuardianHeartbeat): Bundle = Bundle().apply {
        val heartbeat = signed.heartbeat
        putInt(KEY_SCHEMA, heartbeat.schemaVersion)
        putString(KEY_HELPER, heartbeat.helperBuildIdentity)
        putString(KEY_BOOT, heartbeat.bootIdentity)
        putLong(KEY_GENERATION, heartbeat.generation)
        putString(KEY_REQUEST, heartbeat.requestId)
        putString(KEY_TRANSACTION, heartbeat.transactionId)
        putString(KEY_NONCE, heartbeat.taskNonce)
        putString(KEY_ENVELOPE, heartbeat.envelopeSha256)
        putLong(KEY_COUNTER, heartbeat.counter)
        putLong(KEY_ELAPSED, heartbeat.elapsedRealtimeMs)
        putLong(KEY_DEADLINE, heartbeat.hardDeadlineElapsedRealtimeMs)
        putByteArray(KEY_SIGNATURE, signed.signature)
    }

    fun decodeHeartbeat(bundle: Bundle): SignedRestoreGuardianHeartbeat? = runCatching {
        val heartbeat = RestoreGuardianHeartbeat(
            schemaVersion = bundle.getInt(KEY_SCHEMA, -1),
            helperBuildIdentity = requireNotNull(bundle.getString(KEY_HELPER)),
            bootIdentity = requireNotNull(bundle.getString(KEY_BOOT)),
            generation = bundle.getLong(KEY_GENERATION, -1),
            requestId = requireNotNull(bundle.getString(KEY_REQUEST)),
            transactionId = requireNotNull(bundle.getString(KEY_TRANSACTION)),
            taskNonce = requireNotNull(bundle.getString(KEY_NONCE)),
            envelopeSha256 = requireNotNull(bundle.getString(KEY_ENVELOPE)),
            counter = bundle.getLong(KEY_COUNTER, -1),
            elapsedRealtimeMs = bundle.getLong(KEY_ELAPSED, -1),
            hardDeadlineElapsedRealtimeMs = bundle.getLong(KEY_DEADLINE, -1),
        )
        SignedRestoreGuardianHeartbeat(
            heartbeat,
            requireNotNull(bundle.getByteArray(KEY_SIGNATURE)),
        )
    }.getOrNull()
}

internal class GuardianProcessReceiptCollector(
    private val terminalStore: GuardianTerminalReceiptStore,
) : GuardianReceiptOutputPort {
    private var ready: ByteArray? = null
    private var stock: SignedRestoreGuardianStockConfirmedReceipt? = null
    private var pendingExited: SignedRestoreGuardianExitedReceipt? = null
    private var pendingTerminalBlocked = false
    private var pendingTerminalDurable = false

    @Synchronized
    override fun emit(receipt: GuardianAuthenticatedReceipt): GuardianReceiptOutputDisposition =
        when (receipt.type) {
            GuardianReceiptType.READY -> {
                val encoded = GuardianReadyWireCodec.encode(
                    receipt.canonicalPayload,
                    receipt.authentication,
                ) ?: return GuardianReceiptOutputDisposition.REJECTED
                ready = encoded.copyOf()
                GuardianReceiptOutputDisposition.CONFIRMED
            }
            GuardianReceiptType.STOCK_CONFIRMED -> {
                val signed = GuardianTerminalWireCodec.signedStock(
                    receipt.canonicalPayload,
                    receipt.authentication,
                ) ?: return GuardianReceiptOutputDisposition.REJECTED
                stock = signed
                GuardianReceiptOutputDisposition.CONFIRMED
            }
            GuardianReceiptType.EXITED -> {
                val exactStock = stock ?: return GuardianReceiptOutputDisposition.REJECTED
                val exited = GuardianTerminalWireCodec.signedExited(
                    receipt.canonicalPayload,
                    receipt.authentication,
                ) ?: return GuardianReceiptOutputDisposition.REJECTED
                val pending = pendingExited
                if (pending != null && pendingTerminalDurable &&
                    sameTerminalIdentity(pending, exited) &&
                    terminalStore.containsExact(exactStock, pending)
                ) {
                    pendingExited = null
                    pendingTerminalBlocked = false
                    pendingTerminalDurable = false
                    GuardianReceiptOutputDisposition.CONFIRMED
                } else when (terminalStore.persistOwnedPending(exactStock, exited)) {
                    GuardianTerminalPersistDisposition.CONFIRMED -> {
                        pendingExited = null
                        pendingTerminalBlocked = false
                        pendingTerminalDurable = false
                        GuardianReceiptOutputDisposition.CONFIRMED
                    }
                    GuardianTerminalPersistDisposition.RETRYABLE -> {
                        pendingExited = exited
                        pendingTerminalBlocked = false
                        pendingTerminalDurable = false
                        GuardianReceiptOutputDisposition.AMBIGUOUS
                    }
                    GuardianTerminalPersistDisposition.RETAINED -> {
                        pendingExited = exited
                        pendingTerminalBlocked = true
                        pendingTerminalDurable = false
                        GuardianReceiptOutputDisposition.AMBIGUOUS
                    }
                }
            }
        }

    @Synchronized
    fun readyWire(): ByteArray? = ready?.copyOf()

    @Synchronized
    fun hasPendingTerminalReceipt(): Boolean = pendingExited != null && !pendingTerminalBlocked

    @Synchronized
    fun retryPendingTerminalReceipt(): GuardianReceiptOutputDisposition {
        val exactStock = stock ?: return GuardianReceiptOutputDisposition.REJECTED
        val exited = pendingExited ?: return GuardianReceiptOutputDisposition.REJECTED
        return when (terminalStore.persistOwnedPending(exactStock, exited)) {
            GuardianTerminalPersistDisposition.CONFIRMED -> {
                pendingTerminalBlocked = false
                pendingTerminalDurable = true
                GuardianReceiptOutputDisposition.CONFIRMED
            }
            GuardianTerminalPersistDisposition.RETRYABLE -> {
                GuardianReceiptOutputDisposition.AMBIGUOUS
            }
            GuardianTerminalPersistDisposition.RETAINED -> {
                pendingTerminalBlocked = true
                GuardianReceiptOutputDisposition.REJECTED
            }
        }
    }

    fun terminalAvailable(): Boolean = terminalStore.loadUntil(0) is GuardianTerminalLoad.Received

    private fun sameTerminalIdentity(
        first: SignedRestoreGuardianExitedReceipt,
        second: SignedRestoreGuardianExitedReceipt,
    ): Boolean = first.receipt.copy(issuedElapsedRealtimeMs = second.receipt.issuedElapsedRealtimeMs) == second.receipt
}

internal class GuardianProcessHardware(
    private val probe: HardwareProbe,
    reader: SystemReader,
    pServer: PServerClient,
    /** Seams for the coupling settle (production: the probe's own Settings reads and a real sleep). */
    fanModeRaw: () -> String? = probe::fanModeRaw,
    vendorFanCouplingRaw: () -> String? = probe::vendorFanCouplingRaw,
    sleeper: (Long) -> Unit = Thread::sleep,
    /**
     * Defaulted seams, byte-identical production behaviour: the live probe's capabilities and the host
     * filesystem's `/sys` resolution. A host test of the fan steps this process runs (they pass through
     * the port's canonical allowlist, which a Windows host cannot resolve) needs both taken out.
     */
    capabilities: () -> HardwareCapabilities = probe::probe,
    sysfsResolver: SysfsCanonicalResolver = FileSysfsCanonicalResolver,
    /** Last, so a caller's trailing lambda is still the clock. */
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {
    // The vendor `performance_mode` restore goes through the SAME real port the daemon uses, so the
    // three-way rule, the redundant-write skip, the origin-record release AND the fan-coupling settle
    // after the mode write live in exactly one place. [probe] was built on the device-protected
    // context, so [HardwareProbe.vendorModeOriginStore] is the very directory the daemon process saved
    // the origin record into.
    private val commands = PServerHardwareCommandPort(
        pServer = pServer,
        reader = reader,
        capabilities = capabilities,
        fanModeRaw = fanModeRaw,
        sleeper = sleeper,
        elapsedRealtimeMs = now,
        vendorModeRaw = probe::vendorModeRaw,
        vendorModeOrigin = probe.vendorModeOriginStore,
        sysfsResolver = sysfsResolver,
        vendorFanCouplingRaw = vendorFanCouplingRaw,
    )

    fun observe(entries: List<RestoreGuardianRestoreEntry>): Map<String, GuardianObservedState>? =
        runCatching {
            entries.associate { entry -> entry.logicalId to observe(entry) }
        }.getOrNull()

    fun execute(intents: List<GuardianRestoreIntent>): Boolean = intents.all { intent ->
        val mutation = mutation(intent.entry, afterVendorMode = intent.afterVendorMode)
        commands.execute(mutation).status == CheckedCommandStatus.CONFIRMED &&
            observe(intent.entry).let { after ->
                // A vendor write the port correctly SKIPPED (the player moved the mode between the
                // plan and the write) is a landed restore: their choice stands. Name it; the daemon
                // port's own detail is not carried out of this call.
                val landed = GuardianTypedRestorePolicy.restoreLanded(intent.entry, after)
                if (landed && !GuardianTypedRestorePolicy.exactReadback(intent.entry, after)) {
                    runCatching {
                        Log.w(VENDOR_TAG, "Guardian left ${intent.entry.logicalId} alone: the player changed it ($after)")
                    }
                }
                landed
            }
    }

    /**
     * After a restore pass: release the origin record unless the restore did not land
     * ([VendorModeOriginPolicy.recordAfterRestore]). A restore that found the origin already in place,
     * or a mode the player chose, plans no write and so never reaches the port's own release; without
     * this the record would sit until the next capture retired it. Never writes the key; the same
     * policy as the daemon port, so a player-changed skip releases the record here too.
     */
    fun settleVendorOrigin(entries: List<RestoreGuardianRestoreEntry>) {
        if (entries.none { it is RestoreGuardianVendorModePutEntry }) return
        runCatching {
            val store = probe.vendorModeOriginStore
            val held = store.load().recordOrNull ?: return@runCatching
            val live = probe.vendorModeRaw()?.trim()?.toIntOrNull()
            if (VendorModeOriginPolicy.recordAfterRestore(held, live) == null && !store.clear()) {
                Log.w(VENDOR_TAG, "origin record could not be cleared after a confirmed restore")
            }
        }
    }

    fun readback(
        entries: List<RestoreGuardianRestoreEntry>,
        persistentOnly: Boolean,
    ): GuardianStockReadback? = runCatching {
        val selected = entries.filter { entry ->
            !persistentOnly || entry.isPersistentGuardianAxis()
        }
        GuardianStockReadback(
            entries = selected.associate { entry -> entry.logicalId to observe(entry) },
            observedElapsedRealtimeMs = now(),
        )
    }.getOrNull()

    private fun observe(entry: RestoreGuardianRestoreEntry): GuardianObservedState = when (entry) {
        is RestoreGuardianSysfsEntry -> {
            val metadata = OsPosixNodeMetadataReader.read(entry.canonicalSysfsPath)
            val capabilities = probe.probe()
            GuardianObservedState.Sysfs(
                value = probe.sysfsIntRaw(
                    entry.logicalId,
                    entry.canonicalSysfsPath,
                    capabilities,
                ),
                mode = metadata?.mode,
                uid = metadata?.uid,
                gid = metadata?.gid,
            )
        }
        is RestoreGuardianFanPutEntry,
        is RestoreGuardianFanDeleteEntry,
        -> GuardianObservedState.FanMode(probe.fanModeRaw()?.toIntOrNull())
        is RestoreGuardianVendorModePutEntry ->
            GuardianObservedState.VendorMode(probe.vendorModeRaw()?.trim()?.toIntOrNull())
    }

    private fun mutation(entry: RestoreGuardianRestoreEntry, afterVendorMode: Boolean = false): LogicalMutation = when (entry) {
        is RestoreGuardianSysfsEntry -> LogicalMutation(
            logicalId = entry.logicalId,
            axis = if (entry.logicalId.startsWith("cpu:")) TuningAxis.CPU else TuningAxis.GPU,
            canonicalPath = entry.canonicalSysfsPath,
            kind = LogicalMutationKind.SYSFS_WRITE,
            value = entry.expectedStock.value,
            restoreMode = entry.expectedStock.mode,
            restoreUid = entry.expectedStock.uid,
            restoreGid = entry.expectedStock.gid,
            expectPresent = true,
            capturedSnapshotAuthority = true,
        )
        is RestoreGuardianFanPutEntry -> LogicalMutation(
            logicalId = entry.logicalId,
            axis = TuningAxis.FAN,
            canonicalPath = HardwareSnapshot.FAN_SETTINGS_PATH,
            kind = LogicalMutationKind.SETTINGS_PUT,
            value = entry.expectedStock.value,
            restoreMode = null,
            expectPresent = true,
            capturedSnapshotAuthority = true,
            fanRestoreAfterVendorMode = afterVendorMode,
        )
        is RestoreGuardianFanDeleteEntry -> LogicalMutation(
            logicalId = entry.logicalId,
            axis = TuningAxis.FAN,
            canonicalPath = HardwareSnapshot.FAN_SETTINGS_PATH,
            kind = LogicalMutationKind.SETTINGS_DELETE,
            value = null,
            restoreMode = null,
            expectPresent = false,
            capturedSnapshotAuthority = true,
        )
        // The envelope carries both values, so the port can apply the three-way rule itself: it
        // writes the origin only while the key still reads what Mimir applied.
        is RestoreGuardianVendorModePutEntry -> LogicalMutation(
            logicalId = entry.logicalId,
            axis = TuningAxis.VENDOR_MODE,
            canonicalPath = HardwareSnapshot.VENDOR_MODE_SETTINGS_PATH,
            kind = LogicalMutationKind.SETTINGS_PUT,
            value = entry.origin,
            restoreMode = null,
            expectPresent = true,
            capturedSnapshotAuthority = true,
            vendorModeContext = VendorModeMutationContext(
                origin = entry.origin,
                applied = entry.applied,
                // Audit only; a restore decision never depends on it.
                transactionId = GUARDIAN_AUDIT_ID,
            ),
        )
    }

    private companion object {
        const val VENDOR_TAG = "MimirVendorMode"
        const val GUARDIAN_AUDIT_ID = "guardian-restore"
    }
}
