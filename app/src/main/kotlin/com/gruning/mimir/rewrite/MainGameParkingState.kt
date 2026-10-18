package com.gruning.mimir.rewrite

import android.os.SystemClock
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardResume

/**
 * The process-local lease for a DS/3DS game whose native Main task is temporarily covered by Mimir.
 *
 * This is deliberately a tiny identity record. The task id is the Android task we observed before
 * bringing Mimir forward; the operation and game id prevent a later launch from inheriting it, and
 * the package is the value used to re-resolve that same task before a return. [parkedAtElapsedMillis]
 * is [SystemClock.elapsedRealtime] at the moment parking began and is not part of the lease's identity — see [MainGameParkingState.sameIdentity].
 */
internal data class MainGameParkingLease(
    val operation: MimirGameSessionOperation,
    val gameId: GameId,
    val targetPackageName: String,
    val taskId: Int,
    val parkedAtElapsedMillis: Long,
    /**
     * Cert 2026-10-08 F3: armed by a HOME press over a single-screen game ([MainGameHomePark]), not by a Bottom app launch over
     * a dual-screen one. Such a lease matches a session whose [MimirGameSession.dualScreen] is false; every other lease still
     * needs a dual-screen game. Not part of the lease's identity.
     */
    val homeParked: Boolean = false,
    /**
     * the game has a second screen (melonDS, Azahar), so a HOME-parked one also owns the Bottom's emulator window. Not part of the
     * lease's identity; it decides only how an unanswered gateway is read ([releasesOnUnansweredGateway]) and how Resume fronts it.
     */
    val dualScreen: Boolean = false,
    /**
     * Parking both displays: the game was placed on the Bottom and a HOME press on display [displayId] parked it there. A Bottom lease is a HOME
     * lease only; it matches a session whose placement is BOTTOM, its Resume re-fronts the game on the Bottom (the game tab's own reopen), and the
     * task probes ask display [displayId], not Main's. Not part of the lease's identity.
     */
    val bottom: Boolean = false,
    /** The display the game's task lives on: Main's (0) unless [bottom]. */
    val displayId: Int = 0,
)

/**
 * Whether an unanswered gateway probe releases this lease at a press (Play, Quit, Resume). Only a single-screen HOME lease does: it
 * ends the session exactly as HOME did before F3. A dual-screen lease keeps the DS ruling instead (an unanswered probe retains: a live
 * Lime3DS was once orphaned by a timeout), HOME-parked or not. A Bottom lease lets go like a single-screen one: its game has a tab on the Bottom, so ending the
 * session orphans nothing. The 5 s watch is unaffected, it only ever ends a session on proof.
 */
internal val MainGameParkingLease.releasesOnUnansweredGateway: Boolean get() = homeParked && (bottom || !dualScreen)

/** R6-P1-07 re-ruling threshold: about ten minutes before Android may have frozen the parked process. */
private const val ReturnMayRestartThresholdMillis = 600_000L

/**
 * Pure predicate behind the Return card's "Return may restart the game." sub-label: true once [nowElapsedMillis]
 * is at least [ReturnMayRestartThresholdMillis] past [parkedAtElapsedMillis]. Both values are
 * [SystemClock.elapsedRealtime] readings.
 */
internal fun returnMayRestart(parkedAtElapsedMillis: Long, nowElapsedMillis: Long): Boolean =
    nowElapsedMillis - parkedAtElapsedMillis >= ReturnMayRestartThresholdMillis

/** Why an exact parking request was accepted or refused. */
internal enum class MainGameParkingArmResult {
    ARMED,
    ALREADY_ARMED,
    INVALID_TASK,
    NOT_CURRENT_DUAL_SCREEN_GAME,
    REPLACEMENT_ACTIVE,
}

internal const val MainGameParkingResumeTimeoutMs = 3_000L

/**
 * One process-wide parking lease. It survives Activity recreation and Main foreground callbacks;
 * only an exact return/cleanup path may clear it.
 */
internal object MainGameParkingState {
    private val lock = Any()

    @Volatile
    private var lease: MainGameParkingLease? = null

    @Volatile
    private var restoringLease: MainGameParkingLease? = null

    /**
     * F3: told which game a HOME-armed lease holds, or null, after every change of the lease; the launch card reads it to say
     * "Resume" ([HomeLaunchCardResume]). A seam, so a test can watch it without writing Compose state.
     */
    internal var homeParkedPublisher: (GameId?) -> Unit = HomeLaunchCardResume::set

    /**
     * Told after every change of the lease (a park armed, a Resume, a release, the Bottom tab's reopen clearing it): the music control reconsiders
     * whether a game is on screen. A seam, installed by [YourMusicEverywhere]; it must only hand off (it runs under this object's lock).
     */
    @Volatile
    internal var leaseObserver: () -> Unit = {}

    private fun publishHomeParked() {
        homeParkedPublisher(lease?.takeIf { it.homeParked }?.gameId)
        runCatching { leaseObserver() }
    }

    /**
     * Arms the exact current session. The caller should use this after its IO task lookup and before
     * bringing Mimir Main forward. A second request for the same identity is idempotent; a different
     * operation can never replace a live lease.
     */
    fun arm(session: MimirGameSession, taskId: Int): MainGameParkingArmResult {
        val candidate = candidate(session, taskId, homeParked = false) ?: return when {
            taskId <= 0 -> MainGameParkingArmResult.INVALID_TASK
            else -> MainGameParkingArmResult.NOT_CURRENT_DUAL_SCREEN_GAME
        }
        return place(candidate)
    }

    /**
     * F3: arms a game that a HOME press left running behind Mimir (dual-screen ones too). [arm] keeps refusing a single-screen
     * game; this is the one path that accepts it, and it stamps the lease [MainGameParkingLease.homeParked].
     */
    fun armForHome(session: MimirGameSession, taskId: Int, displayId: Int = 0): MainGameParkingArmResult {
        val candidate = candidate(session, taskId, homeParked = true, displayId = displayId) ?: return when {
            taskId <= 0 -> MainGameParkingArmResult.INVALID_TASK
            else -> MainGameParkingArmResult.NOT_CURRENT_DUAL_SCREEN_GAME
        }
        return place(candidate)
    }

    /** [armForHome] for the authority's current game, when it is still the operation the HOME press found. */
    fun armForHomeCurrentSession(operation: MimirGameSessionOperation, taskId: Int, displayId: Int = 0): MainGameParkingArmResult {
        reconcileWithCurrentAuthority()
        val current = MimirSessionAuthority.state.value.gameSession
        if (current == null || current.operation != operation) {
            return if (taskId <= 0) {
                MainGameParkingArmResult.INVALID_TASK
            } else {
                MainGameParkingArmResult.NOT_CURRENT_DUAL_SCREEN_GAME
            }
        }
        return armForHome(current, taskId, displayId)
    }

    private fun place(candidate: MainGameParkingLease): MainGameParkingArmResult {
        synchronized(lock) {
            val current = lease
            return when {
                // Compare identity only: candidate() stamps a fresh parkedAtElapsedMillis on every call, so a
                // full data-class equality would never match on a second arm() of the same live session.
                current != null && current.sameIdentity(candidate) -> MainGameParkingArmResult.ALREADY_ARMED
                current != null -> MainGameParkingArmResult.REPLACEMENT_ACTIVE
                else -> {
                    lease = candidate
                    restoringLease = null
                    publishHomeParked()
                    MainGameParkingArmResult.ARMED
                }
            }
        }
    }

    /** Arms only when [session] is still the process authority's exact current game session. */
    fun armForCurrentSession(session: MimirGameSession, taskId: Int): MainGameParkingArmResult {
        reconcileWithCurrentAuthority()
        if (MimirSessionAuthority.state.value.gameSession != session) {
            return if (taskId <= 0) {
                MainGameParkingArmResult.INVALID_TASK
            } else {
                MainGameParkingArmResult.NOT_CURRENT_DUAL_SCREEN_GAME
            }
        }
        return arm(session, taskId)
    }

    /** Returns the lease only when every retained identity field matches [session]. */
    fun currentFor(session: MimirGameSession): MainGameParkingLease? = synchronized(lock) {
        lease?.takeIf { it.matches(session) }
    }

    /** Returns the lease for an exact operation/game/package tuple. */
    fun currentFor(
        operation: MimirGameSessionOperation,
        gameId: GameId,
        targetPackageName: String,
    ): MainGameParkingLease? = synchronized(lock) {
        lease?.takeIf {
            it.operation == operation &&
                it.gameId == gameId &&
                it.targetPackageName == targetPackageName
        }
    }

    /** Current lease for the authority's current game, or null for a replacement/stale session. */
    fun currentForCurrentSession(): MainGameParkingLease? {
        reconcileWithCurrentAuthority()
        val current = MimirSessionAuthority.state.value.gameSession ?: return null
        return currentFor(current)
    }

    /** Used by the resume ledger to retain only a live parked game's return ticket. */
    fun isCurrentOperation(operation: MimirGameSessionOperation): Boolean {
        reconcileWithCurrentAuthority()
        val current = MimirSessionAuthority.state.value.gameSession ?: return false
        return current.operation == operation && currentFor(current) != null
    }

    fun current(): MainGameParkingLease? = synchronized(lock) { lease }

    /** Marks an exact lease as returning; a replacement cannot claim the restoring flag. */
    fun beginRestoring(expected: MainGameParkingLease): Boolean = synchronized(lock) {
        if (lease != expected) return@synchronized false
        if (restoringLease != null && restoringLease != expected) return@synchronized false
        restoringLease = expected
        true
    }

    fun isRestoring(expected: MainGameParkingLease): Boolean = synchronized(lock) {
        restoringLease == expected && lease == expected
    }

    /** Cancels a failed focus attempt while retaining the lease for a later explicit retry. */
    fun cancelRestoring(expected: MainGameParkingLease): Boolean = synchronized(lock) {
        if (restoringLease != expected || lease != expected) return@synchronized false
        restoringLease = null
        true
    }

    /** Clears the parked game only when this exact operation was marked as restoring. */
    fun clearRestoringOnPrimaryStop(operation: MimirGameSessionOperation): Boolean = synchronized(lock) {
        val restoring = restoringLease ?: return@synchronized false
        if (restoring.operation != operation || lease != restoring) return@synchronized false
        lease = null
        restoringLease = null
        publishHomeParked()
        true
    }

    /** Clears only the lease still owned by the exact operation/game/task tuple. */
    fun clear(expected: MainGameParkingLease): Boolean = synchronized(lock) {
        if (lease != expected) return@synchronized false
        lease = null
        restoringLease = null
        publishHomeParked()
        true
    }

    /** Drops a stale lease after the session authority has replaced or ended its game. */
    private fun reconcileWithCurrentAuthority() = synchronized(lock) {
        val current = MimirSessionAuthority.state.value.gameSession
        val currentLease = lease ?: return@synchronized
        if (current == null || !currentLease.matchesLiveSession(current)) {
            lease = null
            restoringLease = null
            publishHomeParked()
        }
    }

    private fun candidate(session: MimirGameSession, taskId: Int, homeParked: Boolean, displayId: Int = 0): MainGameParkingLease? {
        if (taskId <= 0) return null
        // A Bottom game parks only by a HOME press, and only with the Bottom display it was found on.
        val bottom = session.placement == MimirGamePlacement.BOTTOM
        if (bottom && (!homeParked || displayId <= 0)) return null
        if ((session.placement != MimirGamePlacement.MAIN && !bottom) ||
            (!session.dualScreen && !homeParked) ||
            session.phase !in ParkableMainGamePhases
        ) return null
        val packageName = session.targetPackageName?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return MainGameParkingLease(
            operation = session.operation,
            gameId = session.gameId,
            targetPackageName = packageName,
            taskId = taskId,
            parkedAtElapsedMillis = SystemClock.elapsedRealtime(),
            homeParked = homeParked,
            dualScreen = session.dualScreen,
            bottom = bottom,
            displayId = if (bottom) displayId else 0,
        )
    }

    private fun MainGameParkingLease.sameIdentity(other: MainGameParkingLease): Boolean =
        operation == other.operation &&
            gameId == other.gameId &&
            targetPackageName == other.targetPackageName &&
            taskId == other.taskId

    private fun MainGameParkingLease.placement(): MimirGamePlacement = if (bottom) MimirGamePlacement.BOTTOM else MimirGamePlacement.MAIN

    private fun MainGameParkingLease.matches(session: MimirGameSession): Boolean =
        operation == session.operation &&
            gameId == session.gameId &&
            targetPackageName == session.targetPackageName?.trim() &&
            session.placement == placement() &&
            (session.dualScreen || homeParked) &&
            session.phase in ParkableMainGamePhases

    private fun MainGameParkingLease.matchesLiveSession(session: MimirGameSession): Boolean =
        operation == session.operation &&
            gameId == session.gameId &&
            targetPackageName == session.targetPackageName?.trim() &&
            session.placement == placement() &&
            (session.dualScreen || homeParked) &&
            session.phase in ActivePresentationPhases

    private val ParkableMainGamePhases = setOf(
        MimirSessionPhase.DISPATCHED,
        MimirSessionPhase.FOREGROUND_PROVEN,
        MimirSessionPhase.YIELDED_TO_SECONDARY_TASK,
    )
}
