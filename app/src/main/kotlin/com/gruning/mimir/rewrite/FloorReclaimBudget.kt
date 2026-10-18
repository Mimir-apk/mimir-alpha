package com.gruning.mimir.rewrite

/**
 * How many times in a row an automatic resume may reclaim over a `RecoveryNeeded` it caused. ONE budget for the two
 * resumes that reclaim on their own: the secondary-home floor's ([CompanionReturnCoordinator.noteSecondaryHomeResumed]) and, since the
 * combined43 sweep (P1, 2026-10-15), Main's ([CompanionReturnCoordinator.reclaimOnPrimaryResume]).
 *
 * The floor reclaims whenever it resumes in `RecoveryNeeded` ([secondaryHomeResumeShouldReclaim]); Main does the same
 * ([shouldReclaimCompanionAfterPrimaryResume]). A reclaim that keeps failing (the restore guard cannot start, the Presentation cannot commit a
 * frame) ends in `RecoveryNeeded` again, and the guard it starts takes top focus from BOTH hosts and hands it back on the way out, so each
 * failure was itself the next resume: a reclaim every few seconds, for as long as the cause lasted. The floor's half was budgeted first; Main's
 * ran unbudgeted in streaks of 7, 25 and 21 on the starved emulator (`combined43/sweep`, logcat 001905), each guard taking Main's window
 * focus, so `awaitGameReturnTicket` never saw Main own its window and the game return never fired. Two budgets would not do: each path's
 * failure id is "not ours" to the other, so they would reset each other and the loop would only slow down.
 *
 * The budget knows only the reclaim operations the automatic paths issued ([issued]) and what the disposition says at each resume ([onResume]):
 *  - `RecoveryNeeded` carrying the id of the reclaim issued last is a failed automatic reclaim, counted once however many resumes see it.
 *    The first [limit] - 1 of them may try again; the [limit]th gives up, once, and the Bottom is left to the paths that are not automatic (an
 *    edge swipe, the menu's Return to shelf, a tab close), which is what a recovery has always had.
 *  - `RecoveryNeeded` carrying any other id was not caused here (a process recovery, or a reclaim a user action issued and lost): its count starts at zero.
 *  - `MirrorPresented` and `Yielded` reset it: the display is Mimir's again, or a new lease is a new episode.
 *  - A new game launch since the episode began ([gameSequence] changed) resets it: the player did something, and the automatic paths may try again.
 *  - A reclaim in flight, a launch, no display: unchanged, neither resume is asked to reclaim there at all.
 *  - Main stopped and started again ([hostStopped], [hostResumedAfterStop]): a new episode when the PLAYER caused the stop (Recents, another
 *    app, the screen off), so a later return reclaims again; not when the loop's own guard did. The guard cycle STOPS Main (combined43
 *    emulator: `Activity reported stop` on 26 of the 25-reclaim streak's cycles, 23 of 21, 8 of 7; repeat check of the second run), so a stop
 *    is not by itself the player leaving, and the budget decides from the Bottom's state at the stop, never from timing.
 *
 * Main thread, like every host callback; synchronised anyway because the coordinator's tests drive it from the test thread.
 */
internal class FloorReclaimBudget(
    private val limit: Int = DefaultLimit,
    /** The session ledger's game sequence, read when a failure is counted and at every resume; null where there is no ledger (pure tests). */
    private val gameSequence: () -> Long? = { null },
) {
    enum class Verdict {
        /** The resume may reclaim (when its own policy says the disposition wants one). */
        ALLOW,

        /** This resume reached the limit: do not reclaim, and say so once. */
        GIVE_UP,

        /** The limit was reached on this very `RecoveryNeeded` already and said so: do not reclaim, say nothing. */
        STILL_GIVEN_UP,
    }

    private var lastIssued: OperationId? = null
    private var counted: OperationId? = null
    private var givenUpOn: OperationId? = null
    private var failures = 0
    private var episodeGameSequence: Long? = null
    private var stoppedInsideOwnReclaim = false

    /** Consecutive failed automatic reclaims counted so far. */
    @Synchronized
    fun failures(): Int = failures

    /** An automatic resume issued [operationId] as its reclaim. */
    @Synchronized
    fun issued(operationId: OperationId) {
        lastIssued = operationId
    }

    /** The floor's name for [onResume]. */
    fun onFloorResume(disposition: SecondaryDisplayDisposition): Verdict = onResume(disposition)

    @Synchronized
    fun onResume(disposition: SecondaryDisplayDisposition): Verdict {
        if (disposition is SecondaryDisplayDisposition.MirrorPresented || disposition is SecondaryDisplayDisposition.Yielded) {
            reset()
            return Verdict.ALLOW
        }
        val recovery = disposition as? SecondaryDisplayDisposition.RecoveryNeeded ?: return Verdict.ALLOW
        if (failures > 0 && gameSequence() != episodeGameSequence) reset()
        val ours = lastIssued
        if (ours == null || recovery.operationId != ours) {
            reset()
            return Verdict.ALLOW
        }
        if (counted != ours) {
            counted = ours
            failures += 1
            if (failures == 1) episodeGameSequence = gameSequence()
        }
        return when {
            failures < limit -> Verdict.ALLOW
            givenUpOn == ours -> Verdict.STILL_GIVEN_UP
            else -> {
                givenUpOn = ours
                Verdict.GIVE_UP
            }
        }
    }

    /**
     * Main stopped (a real stop, not a configuration change) with the Bottom in [disposition]. The stop is the loop's own, and keeps the
     * episode, when a reclaim this budget issued is still in flight (`ReclaimPending` on its id: the guard is covering Main) or its deadline
     * has already turned it into a `RecoveryNeeded` that no resume has counted yet (the deadline fired while Main was still covered). Any
     * other stop is the player's: a settled recovery, a presented or yielded Bottom, no Bottom.
     */
    @Synchronized
    fun hostStopped(disposition: SecondaryDisplayDisposition) {
        val ours = lastIssued
        stoppedInsideOwnReclaim = ours != null && when (disposition) {
            is SecondaryDisplayDisposition.ReclaimPending -> disposition.lease.operationId == ours
            is SecondaryDisplayDisposition.RecoveryNeeded -> disposition.operationId == ours && counted != ours
            else -> false
        }
    }

    /**
     * Main started again after a real stop: a new episode, unless the stop was the loop's own ([hostStopped]). A stop this budget never heard
     * of (the process is new) is the player's. True when the episode was reset.
     */
    @Synchronized
    fun hostResumedAfterStop(): Boolean {
        val own = stoppedInsideOwnReclaim
        stoppedInsideOwnReclaim = false
        if (own) return false
        reset()
        return true
    }

    @Synchronized
    fun reset() {
        lastIssued = null
        counted = null
        givenUpOn = null
        failures = 0
        episodeGameSequence = null
    }

    companion object {
        /** Two consecutive failed automatic reclaims, then stop. */
        const val DefaultLimit = 2

        fun giveUpLogLine(displayId: Int?, failures: Int, operationId: OperationId): String =
            "secondary home resume reclaim gave up display=$displayId failures=$failures op=$operationId"

        fun primaryGiveUpLogLine(displayId: Int?, failures: Int, operationId: OperationId): String =
            "primary resume reclaim gave up display=$displayId failures=$failures op=$operationId"
    }
}
