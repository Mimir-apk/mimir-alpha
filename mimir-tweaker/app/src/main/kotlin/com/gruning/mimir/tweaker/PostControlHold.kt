package com.gruning.mimir.tweaker

import java.io.File

/**
 * The live fan state the hold acts on, read fresh by the caller: `fan_mode`, and the PWM duty and
 * period nodes. Any channel may be null when the read failed; the controller never guesses -- a
 * null mode fails every path closed (no write, the marker stays).
 */
internal data class PostControlHoldLive(val mode: Int?, val duty: Int?, val period: Int?)

/**
 * A durable "a post-control hold may be live" record: present from just before the MANUAL entry
 * write until just after a confirmed release. Presence is the whole content -- a daemon that finds
 * it on start (or a watch that is not holding and finds it on a tick) owns a fan it did not
 * release, and hands it back to vendor Smart before anything else. Written BEFORE the entry write
 * so a process death between the two can never leave a hold the next start does not see; it is
 * also what the boot receiver and the service's started lifetime read, so the process that must
 * release it is started and kept alive until it has.
 */
internal class PostControlHoldMarker(
    private val file: File,
    /** Test seam: the one filesystem call a delete makes. */
    private val deleter: (File) -> Boolean = { it.delete() },
) {
    @Volatile
    private var cached: Boolean? = null

    fun exists(): Boolean = runCatching { file.isFile }.getOrDefault(false).also { cached = it }

    /**
     * The last answer [exists], [write] or [delete] produced, for a caller that must not touch the
     * disk (the main-thread terminal gate); stats once when nothing has asked yet.
     */
    fun isPresentCached(): Boolean = cached ?: exists()

    fun write(enteredElapsedMs: Long): Boolean = runCatching {
        file.parentFile?.mkdirs()
        file.writeText("enteredElapsedMs=$enteredElapsedMs\n")
        cached = true
        true
    }.getOrDefault(false)

    /** True only when the marker is confirmed gone; a failed delete leaves the cache at "present". */
    fun delete(): Boolean = runCatching {
        (!file.exists() || deleter(file)).also { if (it) cached = false }
    }.getOrDefault(false)

    companion object {
        const val FILE_NAME = "post-control-hold"
    }
}

/** The boot receiver's one file check, ahead of any service: is a hold marker on disk? */
internal fun postControlHoldMarkerPresent(directory: File?): Boolean = runCatching {
    if (directory == null) return@runCatching false
    File(directory, PostControlHoldMarker.FILE_NAME).isFile
}.getOrDefault(false)

/** What [PostControlHoldController.yieldToSession] found and did. */
internal enum class PostControlHoldYield {
    /** No hold was live (no marker, or the live mode is a known value that is not MANUAL and the marker is cleared). */
    NOT_HELD,
    /** A live hold was released to vendor Smart, confirmed by readback. */
    RELEASED,
    /** A hold is (or may be) live and was not released: the session must not capture over it. */
    RELEASE_FAILED,
}

/**
 * The full-fan hold until cooling completes: the actuation half of
 * [PostControlThermalWatch]. The watch decides; this acts on the decision through the command
 * port's already-validated fan writes, keeps the [PostControlHoldMarker] honest around them, logs
 * every enter/exit/correction (and a rate-limited heartbeat while holding), and feeds the outcome
 * back through [PostControlThermalWatch.acknowledge] so the watch's phase never disagrees with the
 * hardware. Pure except for the lambdas it is given: JVM-testable with fakes, and exercised against
 * the real [PServerHardwareCommandPort] in `PostControlHoldDeviceTest`.
 *
 * Every write is one of the command port's existing checked shapes with an authoritative
 * readback; a failed write is reported, never retried here (the watch's budget owns the tick
 * retries; the two paths outside a tick -- [yieldToSession] before a session captures stock, and
 * the stale-hold release at the start of a tick when the marker outlived its owner -- back off for
 * [staleRetryBackoffMs] after a failure, and so does a marker that will not delete). A `fan_mode`
 * that cannot be read is never "not ours": every path treats null as "may still be held" and does
 * nothing but say so.
 *
 * [onHoldObligation] fires the moment the marker is written, before the entry write, and
 * [onObligationCleared] after the marker is deleted: the service uses them to give the hold a
 * started lifetime and to settle it once nothing is held.
 */
internal class PostControlHoldController(
    private val watch: PostControlThermalWatch,
    private val marker: PostControlHoldMarker,
    private val live: () -> PostControlHoldLive,
    private val force: () -> CheckedCommandResult?,
    private val enter: () -> CheckedCommandResult?,
    private val correct: () -> CheckedCommandResult?,
    private val release: () -> CheckedCommandResult?,
    private val onFanWrite: () -> Unit,
    private val now: () -> Long,
    private val log: (String) -> Unit = PostControlThermalObservability::holdLine,
    private val heartbeatMs: Long = HEARTBEAT_MS,
    private val staleRetryBackoffMs: Long = STALE_RETRY_BACKOFF_MS,
    private val onHoldObligation: () -> Unit = {},
    private val onObligationCleared: () -> Unit = {},
) {
    private var lastHeartbeatMs: Long? = null
    private var staleRetryNotBeforeMs: Long = 0L
    private var sessionReleaseNotBeforeMs: Long = 0L

    /**
     * One watch tick: a stale hold (marker present, watch not holding) is released first, then
     * the sample is evaluated and the decision acted on. Returns the evaluation for the caller's
     * disarm settlement -- never true while holding, by the watch's construction.
     */
    fun tick(sample: PostControlThermalSample): PostControlThermalEvaluation {
        if (!watch.isHolding() && marker.exists()) releaseStale()
        val evaluation = watch.evaluate(sample)
        act(evaluation.decision, sample)
        return evaluation
    }

    /**
     * Policy 2026-10-14 (A), "the hold wins": is a post-control hold live right now? The
     * read-only prefix of [yieldToSession] -- nothing held and no marker: no; an unreadable mode:
     * yes (fail closed, as every path here treats null); a known value that is not MANUAL: no;
     * MANUAL: yes. Reads only; releases nothing, clears nothing. The apply admission refuses a
     * profile re-arm while this is true, and the apply body asks again right before the capture
     * (a hold can enter between the two), so the hold is never handed over -- it releases on its
     * own when the device is cool.
     */
    fun isHeld(): Boolean {
        if (!watch.isHolding() && !marker.exists()) return false
        val mode = live().mode ?: return true
        return mode == TuningPlanner.FAN_MANUAL
    }

    /**
     * A session is about to capture stock: a live hold must be handed back first, or the capture
     * would record MANUAL as stock and the session's own restore would refuse it. Fails closed --
     * the caller declines the apply on [PostControlHoldYield.RELEASE_FAILED] -- on an unreadable
     * mode, on a failed release, and for [staleRetryBackoffMs] after a failed release, so a
     * release that keeps failing is not re-issued on every apply.
     */
    fun yieldToSession(): PostControlHoldYield {
        if (!watch.isHolding() && !marker.exists()) return PostControlHoldYield.NOT_HELD
        val state = live()
        val mode = state.mode
        if (mode == null) {
            log("POST_CONTROL_HOLD exit_failed reason=$REASON_SESSION mode=null duty=${state.duty} period=${state.period} detail=mode_unreadable")
            return PostControlHoldYield.RELEASE_FAILED
        }
        if (mode != TuningPlanner.FAN_MANUAL) {
            // A known other value: not ours any more (or never landed). Nothing to hand back,
            // nothing to capture over.
            dropHold(reason = REASON_LOST, state = state)
            return PostControlHoldYield.NOT_HELD
        }
        val at = now()
        if (at < sessionReleaseNotBeforeMs) {
            log("POST_CONTROL_HOLD exit_deferred reason=$REASON_SESSION remainingMs=${sessionReleaseNotBeforeMs - at} mode=$mode duty=${state.duty} period=${state.period}")
            return PostControlHoldYield.RELEASE_FAILED
        }
        return if (releaseHold(reason = REASON_SESSION, state = state)) {
            PostControlHoldYield.RELEASED
        } else {
            sessionReleaseNotBeforeMs = at + staleRetryBackoffMs
            PostControlHoldYield.RELEASE_FAILED
        }
    }

    private fun act(decision: PostControlThermalDecision, sample: PostControlThermalSample) {
        when (decision) {
            is PostControlThermalDecision.ForceCooling -> {
                val result = force()
                val confirmed = result?.status == CheckedCommandStatus.CONFIRMED
                if (confirmed) onFanWrite()
                watch.acknowledge(decision, confirmed)
            }
            is PostControlThermalDecision.EnterHold -> enterHold(decision)
            is PostControlThermalDecision.CorrectHold -> {
                val before = sample.duty
                val result = correct()
                val confirmed = result?.status == CheckedCommandStatus.CONFIRMED
                if (confirmed) onFanWrite()
                val after = live()
                log(
                    "POST_CONTROL_HOLD correct duty=$before->${after.duty} period=${after.period} " +
                        "mode=${after.mode} result=${result?.status} heldMs=${watch.heldMs()}",
                )
                watch.acknowledge(decision, confirmed)
            }
            is PostControlThermalDecision.ReleaseHold -> {
                val state = live()
                val released = releaseHold(reason = decision.reason, state = state)
                watch.acknowledge(decision, released)
            }
            is PostControlThermalDecision.HoldLost -> {
                // The watch already left the hold; the marker follows, and the line says why. A
                // marker that will not delete is the stale path's from the next tick on, with its backoff.
                clearMarker()
                log("POST_CONTROL_HOLD exit reason=$REASON_LOST mode=${decision.liveMode} duty=${sample.duty} period=${sample.period}")
            }
            is PostControlThermalDecision.Holding -> heartbeat(sample)
            is PostControlThermalDecision.LeftToVendor,
            is PostControlThermalDecision.Escalate,
            PostControlThermalDecision.Idle,
            -> Unit
        }
    }

    private fun enterHold(decision: PostControlThermalDecision.EnterHold) {
        // Marker first: a death between here and the readback must leave something the next
        // start can see, and the started lifetime that will outlive this tick begins with it.
        // It is cleared below only when the readback KNOWS nothing is held.
        if (!marker.write(now())) {
            log("POST_CONTROL_HOLD enter_refused reason=${decision.reason} detail=marker_unwritable")
            watch.acknowledge(decision, confirmed = false)
            return
        }
        onHoldObligation()
        val result = enter()
        val confirmed = result?.status == CheckedCommandStatus.CONFIRMED
        val state = live()
        if (confirmed) {
            onFanWrite()
            lastHeartbeatMs = now()
            log(
                "POST_CONTROL_HOLD enter reason=${decision.reason} mode=${state.mode} " +
                    "duty=${state.duty} period=${state.period}",
            )
        } else {
            log(
                "POST_CONTROL_HOLD enter_failed reason=${decision.reason} mode=${state.mode} " +
                    "duty=${state.duty} period=${state.period} detail=${result?.detail}",
            )
            // The port hands a failed entry back to Smart itself; the readback decides the marker,
            // and only a KNOWN non-MANUAL value clears it -- an unreadable mode may still be held.
            if (state.mode != null && state.mode != TuningPlanner.FAN_MANUAL) clearMarker()
        }
        watch.acknowledge(decision, confirmed)
    }

    private fun releaseHold(reason: String, state: PostControlHoldLive): Boolean {
        val heldMs = watch.heldMs()
        val result = release()
        val confirmed = result?.status == CheckedCommandStatus.CONFIRMED
        val after = live()
        if (confirmed) {
            onFanWrite()
            clearMarker()
            log(
                "POST_CONTROL_HOLD exit reason=$reason mode=${after.mode} duty=${after.duty} " +
                    "period=${after.period} heldMs=$heldMs",
            )
            if (reason == REASON_SESSION || reason == REASON_STALE_OWNER) watch.holdReleasedExternally()
        } else {
            log(
                "POST_CONTROL_HOLD exit_failed reason=$reason mode=${after.mode} duty=${after.duty} " +
                    "period=${after.period} before=${state.mode}/${state.duty} detail=${result?.detail}",
            )
        }
        return confirmed
    }

    /** Returns whether the marker is confirmed gone. */
    private fun dropHold(reason: String, state: PostControlHoldLive): Boolean {
        val cleared = clearMarker()
        watch.holdReleasedExternally()
        log("POST_CONTROL_HOLD exit reason=$reason mode=${state.mode} duty=${state.duty} period=${state.period}")
        return cleared
    }

    /** "Cleared" is said only on a confirmed delete; a marker that stays is still an obligation. */
    private fun clearMarker(): Boolean {
        val cleared = marker.delete()
        if (cleared) onObligationCleared() else log("POST_CONTROL_HOLD marker_undeletable detail=delete_failed")
        return cleared
    }

    /** The marker outlived its owner (daemon restart, or a watch stopped without a release). */
    private fun releaseStale() {
        val at = now()
        if (at < staleRetryNotBeforeMs) return
        val state = live()
        when (state.mode) {
            null -> {
                // Unreadable: it may still be held. The marker stays, and this asks again later.
                staleRetryNotBeforeMs = at + staleRetryBackoffMs
                log("POST_CONTROL_HOLD stale_release_deferred reason=mode_unreadable duty=${state.duty} period=${state.period}")
            }
            TuningPlanner.FAN_MANUAL -> {
                if (!releaseHold(reason = REASON_STALE_OWNER, state = state) || marker.exists()) {
                    staleRetryNotBeforeMs = at + staleRetryBackoffMs
                }
            }
            else -> {
                // A marker that will not delete backs off like a failed release: one line per backoff.
                if (!dropHold(reason = REASON_STALE_LOST, state = state)) staleRetryNotBeforeMs = at + staleRetryBackoffMs
            }
        }
    }

    private fun heartbeat(sample: PostControlThermalSample) {
        val at = now()
        val last = lastHeartbeatMs
        if (last != null && at - last < heartbeatMs) return
        lastHeartbeatMs = at
        log(
            "POST_CONTROL_HOLD holding cpu=${sample.cpuSafetyTempC} gpu=${sample.gpuTempC} " +
                "mode=${sample.vendorFanMode} duty=${sample.duty} period=${sample.period} heldMs=${watch.heldMs()}",
        )
    }

    internal companion object {
        const val REASON_SESSION = "session"
        const val REASON_LOST = "lost"
        const val REASON_STALE_OWNER = "stale_owner"
        const val REASON_STALE_LOST = "stale_owner_lost"
        const val HEARTBEAT_MS = 60_000L
        const val STALE_RETRY_BACKOFF_MS = 30_000L
    }
}
