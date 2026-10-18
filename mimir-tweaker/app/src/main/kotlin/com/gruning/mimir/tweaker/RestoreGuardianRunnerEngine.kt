package com.gruning.mimir.tweaker

/** The lifecycle states a guardian session moves through; see [RestoreGuardianRunnerEngine]. */
internal enum class GuardianRunnerLifecycleState {
    STAGING, VERIFIED_INITIAL_ENVELOPE, READY, WATCHING, RESTORING,
    STOCK_CONFIRMED, PERSISTENT_AXES_CONFIRMED, EXITED, ARM_REJECTED, FAULT,
}

internal sealed interface GuardianEnvelopeVerificationOutcome {
    data class Verified(val envelope: RestoreGuardianEnvelope) : GuardianEnvelopeVerificationOutcome
    data object Rejected : GuardianEnvelopeVerificationOutcome
}
internal enum class GuardianReceiptType { READY, STOCK_CONFIRMED, EXITED }
internal enum class GuardianReceiptOutputDisposition { CONFIRMED, REJECTED, AMBIGUOUS }
internal fun interface GuardianReceiptAuthenticationStrategy { fun authenticate(type: GuardianReceiptType, canonicalPayload: ByteArray): ByteArray? }
internal fun interface GuardianReceiptOutputPort { fun emit(receipt: GuardianAuthenticatedReceipt): GuardianReceiptOutputDisposition }

/** Opaque authentication may later carry the guardian session public key; it is never an app-key Signed model. */
internal class GuardianAuthenticatedReceipt(val type: GuardianReceiptType, payload: ByteArray, authentication: ByteArray) {
    private val pinnedPayload = payload.copyOf()
    private val pinnedAuthentication = authentication.copyOf()
    val canonicalPayload get() = pinnedPayload.copyOf()
    val authentication get() = pinnedAuthentication.copyOf()
}

internal class GuardianRestoreIntent(
    entry: RestoreGuardianRestoreEntry,
    /**
     * The fan's FINAL step -- a put of the captured value, or the delete of a key that was absent at
     * capture -- planned after a vendor-mode write so it lands behind SystemUI's coupled `fan_mode`
     * rewrite ([RestoreMutationOrder]). Nothing follows it, so the port may skip its fan-before-compute
     * barrier when the mode's settle observed that rewrite land and the fan already reads the target.
     */
    val afterVendorMode: Boolean = false,
) {
    private val pinnedEntry = entry.deepCopy()
    val entry: RestoreGuardianRestoreEntry get() = pinnedEntry.deepCopy()
}
internal data class GuardianRunnerEngineResult(val state: GuardianRunnerLifecycleState, val armRejected: Boolean = false, val restoreIntents: List<GuardianRestoreIntent> = emptyList())

/** Future live adapter must prove this separately; no current-boot marker is inferred from old envelope truth. */
internal sealed interface GuardianRestoreFenceEvidence {
    data class SameBoot(val disk: FenceDiskState) : GuardianRestoreFenceEvidence
    data class DifferentBootPersistent(val currentBootIdentity: String, val proof: GuardianPersistentRestoreFenceProof) : GuardianRestoreFenceEvidence
}
internal data class GuardianPersistentRestoreFenceProof(val directoryPresent: Boolean, val lockAcquired: Boolean, val terminalMarkerAbsent: Boolean)
internal object GuardianRestoreFenceEvidencePolicy {
    fun allows(envelope: RestoreGuardianEnvelope, liveBootIdentity: String?, evidence: GuardianRestoreFenceEvidence): Boolean = when (evidence) {
        is GuardianRestoreFenceEvidence.SameBoot -> {
            val identity = GuardianRunnerFencePolicy.identityFromAuthenticatedEnvelope(envelope)
            liveBootIdentity == envelope.bootIdentity && evidence.disk.directoryPresent && evidence.disk.lockAcquired &&
                evidence.disk.sessionMarkerIdentity == identity && evidence.disk.terminalMarkerIdentity == identity
        }
        is GuardianRestoreFenceEvidence.DifferentBootPersistent -> liveBootIdentity == evidence.currentBootIdentity &&
            evidence.currentBootIdentity != envelope.bootIdentity && evidence.proof.directoryPresent &&
            evidence.proof.lockAcquired && evidence.proof.terminalMarkerAbsent
    }
}

/**
 * Authentication-agnostic guardian lifecycle reducer.
 *
 * CORRECTED 2026-09-06 (guardian map §4.1): this class's KDoc used to say "all live adapters
 * remain unwired". That stopped being true well before this correction -- it is constructed in
 * the live `:guardian` process by `RestoreGuardianProcessService` on both the arm path and the
 * durable-recovery path, wired to a real AndroidKeyStore signer and a real receipt store. It is
 * the code that produced the receipts that stranded the Odin. What IS still true, and is
 * the reason for the "authentication-agnostic" name: this class receives authentication results
 * and receipts as opaque inputs/outputs (see [GuardianReceiptAuthenticationStrategy],
 * [GuardianReceiptOutputPort]) and never itself touches a key, a signature, or the filesystem --
 * that seam, not liveness, is what stays pure.
 */
internal class RestoreGuardianRunnerEngine(
    private val receiptAuthentication: GuardianReceiptAuthenticationStrategy,
    private val receiptOutput: GuardianReceiptOutputPort,
    private val sessionKey: GuardianSessionPublicKey,
) {
    companion object { const val STALE_HEARTBEAT_THRESHOLD_MS = 30_000L }
    private var pinnedEnvelope: RestoreGuardianEnvelope? = null
    private var state = GuardianRunnerLifecycleState.STAGING
    private var heartbeatState = GuardianHeartbeatState()
    private var lastHeartbeatAt: Long? = null
    private var lastObservedTime: Long = -1
    private var readyAt: Long? = null
    /**
     * Fan-fence fix F3 (Odin,2026-10-13 15:24): the
     * stale-heartbeat origins in the CPU-AWAKE clock (`SystemClock.uptimeMillis`). The watchdog used
     * to measure staleness on `elapsedRealtime` (CLOCK_BOOTTIME, which counts a suspend) while the
     * host's 5 s heartbeat is a `scheduleWithFixedDelay` executor (CLOCK_MONOTONIC, which does not),
     * so a >30 s nap always expired the watchdog 6 s after wake, before the host could possibly beat
     * -- a live controller restored for sleeping. Staleness is now measured on the clock the host
     * can actually keep up with; the HARD deadline stays on `elapsedRealtime` ([watchTick]), so a
     * dead host is still caught after 30 s of awake time and a lease still expires on wall time.
     * Every entry point takes `nowUptimeMs` defaulted to `now`, so a single-clock caller (and
     * every existing test) keeps the old, stricter semantics unchanged.
     */
    private var readyUptimeAt: Long? = null
    private var lastHeartbeatUptimeAt: Long? = null
    private var restoreStartedAt: Long? = null
    private var persistentOnly = false
    private var recoveryBootIdentity: String? = null
    private var terminalScope: GuardianTerminalRestoreScope? = null
    private var verifiedEntrySetSha256: String? = null
    private var stockDigest: String? = null
    private var stockIssuedAt: Long? = null

    fun stage(outcome: GuardianEnvelopeVerificationOutcome, now: Long, liveBootIdentity: String?): GuardianRunnerEngineResult {
        if (state != GuardianRunnerLifecycleState.STAGING) return result()
        val verified = outcome as? GuardianEnvelopeVerificationOutcome.Verified
        if (verified == null || !acceptTime(now) || liveBootIdentity != verified.envelope.bootIdentity || now >= verified.envelope.hardDeadlineElapsedRealtimeMs) return reject()
        pinnedEnvelope = verified.envelope.deepCopy()
        recoveryBootIdentity = liveBootIdentity
        state = GuardianRunnerLifecycleState.VERIFIED_INITIAL_ENVELOPE
        return result()
    }

    fun admitReady(now: Long, nowUptimeMs: Long = now): GuardianRunnerEngineResult {
        if (state != GuardianRunnerLifecycleState.VERIFIED_INITIAL_ENVELOPE) return result()
        val envelope = pinnedEnvelope ?: return reject()
        if (!acceptTime(now) || now >= envelope.hardDeadlineElapsedRealtimeMs) return reject()
        val bytes = RestoreGuardianReadyReceipt(helperBuildIdentity = envelope.helperBuildIdentity, bootIdentity = envelope.bootIdentity, generation = envelope.generation, requestId = envelope.requestId, transactionId = envelope.transactionId, taskNonce = envelope.taskNonce, envelopeSha256 = envelope.digestSha256(), sessionPublicKey = sessionKey, issuedElapsedRealtimeMs = now).canonicalBytes()
        if (!emit(GuardianReceiptType.READY, bytes)) return reject()
        readyAt = now; readyUptimeAt = nowUptimeMs; state = GuardianRunnerLifecycleState.READY
        return result()
    }

    fun beginWatching(now: Long, nowUptimeMs: Long = now): GuardianRunnerEngineResult {
        val envelope = pinnedEnvelope ?: return reject()
        if (state != GuardianRunnerLifecycleState.READY) return result()
        if (!acceptTime(now) || now >= envelope.hardDeadlineElapsedRealtimeMs) return restore()
        state = GuardianRunnerLifecycleState.WATCHING; lastHeartbeatAt = now; lastHeartbeatUptimeAt = nowUptimeMs
        return result()
    }

    /**
     * Reconstructs only a durable READY arm after this Guardian process is restarted. The arm
     * codec has already authenticated the app envelope, READY receipt, session key, and app-signed
     * pin. A same-boot restart resumes watching for one stale-heartbeat window; a changed boot can
     * restore persistent axes only and never re-enters WATCHING.
     */
    fun resumeFromDurableReady(
        ready: GuardianArmStoreLoad.LoadedReady,
        now: Long,
        liveBootIdentity: String?,
        nowUptimeMs: Long = now,
    ): GuardianRunnerEngineResult {
        if (state != GuardianRunnerLifecycleState.STAGING || !acceptTime(now)) return reject()
        val envelope = ready.signed.envelope
        val exactReady = ready.sessionKey == sessionKey &&
            ready.signedReady.receipt.sessionPublicKey == sessionKey &&
            RestoreGuardianCrypto.receiptMatches(envelope, ready.signedReady.receipt) &&
            RestoreGuardianCrypto.verifyReadyReceipt(ready.signedReady, envelope) &&
            ready.signedPin.pin.sessionKeySha256 == sessionKey.sha256() &&
            ready.signedPin.pin.envelopeSha256 == envelope.digestSha256()
        if (!exactReady || liveBootIdentity.isNullOrBlank()) return reject()
        pinnedEnvelope = envelope.deepCopy()
        recoveryBootIdentity = liveBootIdentity
        readyAt = ready.signedReady.receipt.issuedElapsedRealtimeMs
        return if (liveBootIdentity == envelope.bootIdentity) {
            if (now >= envelope.hardDeadlineElapsedRealtimeMs) {
                persistentOnly = false
                state = GuardianRunnerLifecycleState.RESTORING
                restoreStartedAt = now
            } else {
                state = GuardianRunnerLifecycleState.WATCHING
                lastHeartbeatAt = now
                lastHeartbeatUptimeAt = nowUptimeMs
            }
            result()
        } else {
            persistentOnly = true
            state = GuardianRunnerLifecycleState.RESTORING
            restoreStartedAt = now
            result()
        }
    }

    fun heartbeat(signed: RestoreGuardianHeartbeat, signatureValid: Boolean, now: Long, nowUptimeMs: Long = now): GuardianRunnerEngineResult {
        val envelope = pinnedEnvelope ?: return result()
        if (state != GuardianRunnerLifecycleState.WATCHING) return result()
        if (!acceptTime(now)) return restore()
        if (RestoreGuardianHeartbeatPolicy.evaluate(envelope, heartbeatState, signed, now, signatureValid) != HeartbeatDecision.ACCEPT) return restore()
        heartbeatState = RestoreGuardianHeartbeatPolicy.advanced(heartbeatState, signed); lastHeartbeatAt = now; lastHeartbeatUptimeAt = nowUptimeMs
        return result()
    }

    /**
     * F3: the stale origin is compared in the CPU-awake clock ([nowUptimeMs], see [readyUptimeAt]);
     * the hard deadline and the monotonic acceptance stay on [now] (elapsed realtime).
     */
    fun watchTick(now: Long, nowUptimeMs: Long = now): GuardianRunnerEngineResult {
        val envelope = pinnedEnvelope ?: return result()
        if (state !in setOf(GuardianRunnerLifecycleState.READY, GuardianRunnerLifecycleState.WATCHING)) return result()
        val staleOrigin = if (state == GuardianRunnerLifecycleState.READY) readyUptimeAt else lastHeartbeatUptimeAt
        if (!acceptTime(now) || now >= envelope.hardDeadlineElapsedRealtimeMs || staleOrigin?.let { nowUptimeMs - it >= STALE_HEARTBEAT_THRESHOLD_MS } == true) return restore()
        return result()
    }

    fun ownerDecision(decision: GuardianOwnerDecision): GuardianRunnerEngineResult = when (decision) {
        GuardianOwnerDecision.OWNER_LIVE -> result()
        GuardianOwnerDecision.BOOT_MISMATCH -> restore(true)
        GuardianOwnerDecision.OWNER_GONE -> restore()
        GuardianOwnerDecision.PID_REUSED_OR_MISMATCH -> fault()
    }
    fun ownerCancelled(): GuardianRunnerEngineResult = restore()

    fun planRestore(liveBootIdentity: String?, evidence: GuardianRestoreFenceEvidence, observed: Map<String, GuardianObservedState>): GuardianRunnerEngineResult {
        val envelope = pinnedEnvelope ?: return result()
        if (state != GuardianRunnerLifecycleState.RESTORING || !GuardianRestoreFenceEvidencePolicy.allows(envelope, liveBootIdentity, evidence)) return result()
        val otherBoot = evidence is GuardianRestoreFenceEvidence.DifferentBootPersistent
        if (otherBoot != persistentOnly) return result() // scope must be selected explicitly, never silently flipped.
        recoveryBootIdentity = if (otherBoot) {
            (evidence as GuardianRestoreFenceEvidence.DifferentBootPersistent).currentBootIdentity
        } else {
            envelope.bootIdentity
        }
        val eligible = axesThisRestoreMayTouch(envelope)
        val required = eligible.filter { entry ->
            GuardianTypedRestorePolicy.decide(
                entry,
                observed[entry.logicalId] ?: return@filter false,
            ) == GuardianRestoreDecision.WRITE_REQUIRED
        }
        // Keyed to the same TuningPlanner.MIMIR_DUTY_OWNED_FAN_MODES set the envelope validator
        // (RestoreGuardianEnvelope.requireRestoreOrder) and the renderer ordering
        // (RestoreMutationOrder) use -- SPORT and MANUAL alike (S4.3 /
        // OWED-1). This runs in the separate Guardian watchdog process; a marker mismatch here
        // would silently drop the fan-first settlement barrier for a SPORT-owned session.
        // The fan's ordinal is whatever the apply gave it (0 under tw06's fan-first apply, 1 behind the
        // vendor mode since 2026-10-13); what marks the release step is its PLACE -- first -- and the
        // duty-owned marker, the same two things RestoreGuardianEnvelope.requireRestoreOrder accepts.
        val manualFan = eligible.firstOrNull()?.takeIf { entry ->
            !persistentOnly &&
                entry is RestoreGuardianFanPutEntry &&
                entry.mayOwnManualFan()
        }
        val manualFanDecision = manualFan?.let { entry ->
            observed[entry.logicalId]?.let { state -> GuardianTypedRestorePolicy.decide(entry, state) }
        }
        val computeRepairRequired = required.any { it is RestoreGuardianSysfsEntry }
        if (
            manualFan != null &&
            computeRepairRequired &&
            manualFanDecision !in setOf(
                GuardianRestoreDecision.ALREADY_STOCK,
                GuardianRestoreDecision.WRITE_REQUIRED,
            )
        ) {
            return result()
        }
        val ordered = if (
            manualFan != null &&
            computeRepairRequired &&
            manualFanDecision == GuardianRestoreDecision.ALREADY_STOCK
        ) {
            listOf(manualFan) + required
        } else required
        // The fan's FINAL write (RestoreMutationOrder, policy 2026-10-13): a vendor-mode write
        // makes SystemUI rewrite `fan_mode` on a coupled AYN device, so whenever this plan writes the
        // mode and a fan put is in scope, the fan is put back ONCE MORE after it -- whatever the fan
        // read at planning time. A fan that was ALREADY_STOCK here is exactly the one the mode write
        // disturbs; without this intent the double readback failed on it (`fan1!=fan4`, Thor). The
        // port's own compare-before-write makes it a no-op when the coupling left the fan alone.
        val intents = ordered.map { GuardianRestoreIntent(it) }.toMutableList()
        val vendorAt = ordered.indexOfFirst { it is RestoreGuardianVendorModePutEntry }
        // By logical id: a fan PUT, or the fan DELETE of a capture that found no `fan_mode` key -- the
        // key SystemUI then creates on the mode's restore must go again, through the same validated
        // `settings delete` the entry already carries.
        val fanEntry = eligible.firstOrNull { it.logicalId == HardwareSnapshot.FAN_LOGICAL_ID }
        if (vendorAt >= 0 && fanEntry != null) {
            // The envelope's own fan entry already follows the mode (the non-duty-owned order): that
            // step IS the final one. Otherwise (release first, mode last) the fan is repeated after it.
            val fanAfterVendorAt = ordered.indexOfLast { it.logicalId == HardwareSnapshot.FAN_LOGICAL_ID }.takeIf { it > vendorAt }
            if (fanAfterVendorAt != null) {
                intents[fanAfterVendorAt] = GuardianRestoreIntent(ordered[fanAfterVendorAt], afterVendorMode = true)
            } else {
                intents += GuardianRestoreIntent(fanEntry, afterVendorMode = true)
            }
        }
        return result(intents)
    }

    /** The one place `persistentOnly` becomes the scope every other consumer authenticates against. */
    private fun terminalRestoreScope(): GuardianTerminalRestoreScope = if (persistentOnly) {
        GuardianTerminalRestoreScope.CROSS_BOOT_PERSISTENT_REPLAY_FULL_STOCK
    } else {
        GuardianTerminalRestoreScope.SAME_BOOT_FULL
    }

    /**
     * The axes this restore is PERMITTED to write, which on a changed boot is persistent axes only.
     *
     * Deliberately ONE function with two call sites -- [planRestore] and [confirmStock]. Those two
     * having independent copies of this expression is precisely the defect this replaces: restore
     * filtered, confirmation did not, and a record could be created that neither could ever settle.
     * Keeping them textually identical was not enough and would not have been enough; only sharing
     * the definition makes drift impossible.
     *
     * Escalated 2026-09-05: the same defect shape crossed the process boundary. This now delegates
     * to [GuardianTerminalRestoreScope.eligibleRestoreEntries] -- the identical function
     * [RestoreGuardianCrypto.terminalScopeRefusal] calls to recompute the receipt's digest -- so
     * the producer and the verifier can no longer carry independent copies of the filter either.
     */
    private fun axesThisRestoreMayTouch(
        envelope: RestoreGuardianEnvelope,
    ): List<RestoreGuardianRestoreEntry> =
        terminalRestoreScope().eligibleRestoreEntries(envelope.restoreEntries)

    fun confirmStock(first: GuardianStockReadback, second: GuardianStockReadback): GuardianRunnerEngineResult {
        val envelope = pinnedEnvelope ?: return result()
        if (state != GuardianRunnerLifecycleState.RESTORING || !readbacksPostdateRestore(first, second)) return result()
        // *** Verification scope is now IDENTICAL to restore scope, through the one function
        // both call, so the two can never drift apart again.
        //
        // The text that stood here said terminal settlement "requires exact double readback of the
        // complete captured Stock set, including volatile axes reset by boot", and that a zero-write
        // cross-boot restore "still provides meaningful terminal recovery proof". That check was unsatisfiable on the measured Nova: the captured Stock was every CPU unpinned (INT_MAX through the
        // shared-QoS transport), two policies read concrete ceilings after a reboot, the cross-boot
        // filter forbids writing them, and the full-set check therefore demanded a value nothing was
        // permitted to produce. Unsatisfiable by construction: four installs and every force-stop
        // left it holding.
        //
        // **What this gives up, stated plainly.** For a CPU/GPU-only plan on a changed boot the
        // eligible set is empty, and settlement then rests on the surviving discipline rather than
        // on any axis comparison: [readbacksPostdateRestore] above, and the settle-gap leg inside
        // `evaluate`, which both still run on an empty set. No axis is verified because none was
        // restorable.
        //
        // **Why that is sound rather than merely convenient.** A volatile axis is volatile because
        // it cannot carry an applied value across a boot -- cpufreq and the msm_performance
        // parameters are reinitialised by the kernel. So on a changed boot the reboot IS the
        // restore, and there is no applied state left to prove absent. The check was not protecting
        // anything; it was asserting that a value nobody wrote had returned to a value nobody
        // recorded it leaving.
        //
        // Persistent axes are unaffected: a plan carrying fan entries still restores them AND still
        // verifies them, because they are in the eligible set on both sides.
        //
        // The receipt's truthfulness is not a property of this comment -- it is ENFORCED by
        // `GuardianTerminalRestoreScope.eligibleRestoreEntries` (RestoreGuardianEnvelope.kt), the
        // one function both this call and `RestoreGuardianCrypto.terminalScopeRefusal` use to decide
        // which entries a scope covers. `verifiedStockEntrySetSha256` below is a digest of THIS
        // list. Before 2026-09-05 this paragraph asserted that truthfully as a standing fact while
        // the verifier silently held an independent, unfiltered copy of the filter -- a prose
        // guarantee about a cross-file invariant is exactly what goes stale when someone narrows a
        // scope two files away and never rereads this one. If you are narrowing what this restore
        // may touch, change `eligibleRestoreEntries`, not this filter alone.
        val entries = axesThisRestoreMayTouch(envelope)
        // **The empty case is NOT settled here, by the signed receipt format.**
        //
        // fail-closed IS the answer, and the signed receipt format is not
        // being extended. A cross-boot restore that can verify nothing does not settle -- full stop.
        //
        // `GuardianRestoreEntrySetDigest.of` refuses an empty list ("restore entries missing"), and
        // it is right to: `verifiedStockEntrySetSha256` is a fixed 64-char field in SIGNED receipt
        // material, chained between the stock and exited receipts by
        // `GuardianTransactionReconciler:311`. The format has no way to say "nothing was
        // verifiable", and inventing a sentinel for signed security material is not a change to
        // make silently while implementing a scope narrowing.
        //
        // So a plan whose eligible set is empty on a changed boot -- one with no persistent axis at
        // all -- still does not settle, exactly as before this change. It fails CLOSED. What the
        // cross-boot scope change fixed is the far more common case where the plan HAS a persistent axis:
        // that axis is now both restored and verified, and the volatile axes nobody was permitted to
        // write are no longer demanded.
        //
        // The one thing the diagnostic distinction changes: this case must not read as a digest mismatch
        // to an operator. `RestoreGuardianCrypto.terminalScopeRefusal` now names this branch
        // "entry_set_empty", distinctly from a genuine mismatch's "entry_set" -- "nothing was
        // verifiable on this boot" and "these two digests disagree" are different situations, and
        // only one of them is a bug. The disposition (refuse, fail closed) is unchanged.
        if (entries.isEmpty()) return result()
        if (GuardianDoubleStockReadbackPolicy.evaluate(entries, first, second) != GuardianDoubleStockDecision.STOCK_CONFIRMED) return result()
        val scope = terminalRestoreScope()
        val recoveryBoot = recoveryBootIdentity ?: return result()
        val entrySetSha256 = GuardianRestoreEntrySetDigest.of(entries)
        val receipt = RestoreGuardianStockConfirmedReceipt(
            helperBuildIdentity = envelope.helperBuildIdentity,
            bootIdentity = envelope.bootIdentity,
            recoveryBootIdentity = recoveryBoot,
            restoreScope = scope,
            generation = envelope.generation,
            requestId = envelope.requestId,
            transactionId = envelope.transactionId,
            taskNonce = envelope.taskNonce,
            envelopeSha256 = envelope.digestSha256(),
            sessionKeySha256 = sessionKey.sha256(),
            verifiedStockEntrySetSha256 = entrySetSha256,
            firstReadbackElapsedRealtimeMs = first.observedElapsedRealtimeMs,
            secondReadbackElapsedRealtimeMs = second.observedElapsedRealtimeMs,
            issuedElapsedRealtimeMs = second.observedElapsedRealtimeMs,
        )
        if (!emit(GuardianReceiptType.STOCK_CONFIRMED, receipt.canonicalBytes())) return result()
        stockDigest = receipt.digestSha256()
        stockIssuedAt = second.observedElapsedRealtimeMs
        terminalScope = scope
        verifiedEntrySetSha256 = entrySetSha256
        state = GuardianRunnerLifecycleState.STOCK_CONFIRMED
        return result()
    }

    fun exit(now: Long): GuardianRunnerEngineResult {
        val envelope = pinnedEnvelope ?: return result(); val digest = stockDigest ?: return result(); val issued = stockIssuedAt ?: return result()
        val recoveryBoot = recoveryBootIdentity ?: return result()
        val scope = terminalScope ?: return result()
        val entrySetSha256 = verifiedEntrySetSha256 ?: return result()
        if (state != GuardianRunnerLifecycleState.STOCK_CONFIRMED || now < issued || !acceptTime(now)) return result()
        val bytes = RestoreGuardianExitedReceipt(
            helperBuildIdentity = envelope.helperBuildIdentity,
            bootIdentity = envelope.bootIdentity,
            recoveryBootIdentity = recoveryBoot,
            restoreScope = scope,
            generation = envelope.generation,
            requestId = envelope.requestId,
            transactionId = envelope.transactionId,
            taskNonce = envelope.taskNonce,
            envelopeSha256 = envelope.digestSha256(),
            sessionKeySha256 = sessionKey.sha256(),
            verifiedStockEntrySetSha256 = entrySetSha256,
            stockConfirmedReceiptSha256 = digest,
            issuedElapsedRealtimeMs = now,
        ).canonicalBytes()
        if (!emit(GuardianReceiptType.EXITED, bytes)) return result()
        state = GuardianRunnerLifecycleState.EXITED; return result()
    }

    private fun restore(persistent: Boolean = false): GuardianRunnerEngineResult {
        if (state in setOf(GuardianRunnerLifecycleState.ARM_REJECTED, GuardianRunnerLifecycleState.FAULT, GuardianRunnerLifecycleState.STOCK_CONFIRMED, GuardianRunnerLifecycleState.PERSISTENT_AXES_CONFIRMED, GuardianRunnerLifecycleState.EXITED)) return result()
        if (state == GuardianRunnerLifecycleState.STAGING || state == GuardianRunnerLifecycleState.VERIFIED_INITIAL_ENVELOPE) return reject()
        persistentOnly = persistentOnly || persistent; state = GuardianRunnerLifecycleState.RESTORING; restoreStartedAt ?: run { restoreStartedAt = lastObservedTime }; return result()
    }
    private fun fault(): GuardianRunnerEngineResult { if (state !in setOf(GuardianRunnerLifecycleState.ARM_REJECTED, GuardianRunnerLifecycleState.STOCK_CONFIRMED, GuardianRunnerLifecycleState.PERSISTENT_AXES_CONFIRMED, GuardianRunnerLifecycleState.EXITED)) state = GuardianRunnerLifecycleState.FAULT; return result() }
    private fun reject(): GuardianRunnerEngineResult { if (state == GuardianRunnerLifecycleState.STAGING || state == GuardianRunnerLifecycleState.VERIFIED_INITIAL_ENVELOPE) state = GuardianRunnerLifecycleState.ARM_REJECTED; return result(armRejected = state == GuardianRunnerLifecycleState.ARM_REJECTED) }
    private fun acceptTime(now: Long): Boolean {
        if (now < 0 || now < lastObservedTime) return false
        lastObservedTime = now
        return true
    }
    private fun readbacksPostdateRestore(first: GuardianStockReadback, second: GuardianStockReadback): Boolean {
        val afterReady = persistentOnly || first.observedElapsedRealtimeMs >= (readyAt ?: Long.MAX_VALUE)
        return first.observedElapsedRealtimeMs >= 0 &&
            second.observedElapsedRealtimeMs >= 0 &&
            afterReady &&
            first.observedElapsedRealtimeMs >= (restoreStartedAt ?: Long.MAX_VALUE)
    }
    private fun emit(type: GuardianReceiptType, bytes: ByteArray): Boolean = try { receiptAuthentication.authenticate(type, bytes)?.takeIf { it.isNotEmpty() }?.let { receiptOutput.emit(GuardianAuthenticatedReceipt(type, bytes, it)) == GuardianReceiptOutputDisposition.CONFIRMED } ?: false } catch (_: Throwable) { false }
    private fun result(intents: List<GuardianRestoreIntent> = emptyList(), armRejected: Boolean = false) = GuardianRunnerEngineResult(state, armRejected, intents)
}

internal fun RestoreGuardianRestoreEntry.deepCopy(): RestoreGuardianRestoreEntry = when (this) {
    is RestoreGuardianSysfsEntry -> copy(rendererAppliedIntermediates = rendererAppliedIntermediates.toList())
    is RestoreGuardianFanPutEntry -> copy(rendererAppliedIntermediates = rendererAppliedIntermediates.toList())
    is RestoreGuardianFanDeleteEntry -> copy(rendererAppliedIntermediates = rendererAppliedIntermediates.toList())
    is RestoreGuardianVendorModePutEntry -> copy()
}
internal fun RestoreGuardianEnvelope.deepCopy() = copy(restoreEntries = restoreEntries.map { it.deepCopy() })
