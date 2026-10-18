package com.gruning.mimir.rewrite

import android.app.usage.UsageEvents
import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.gruning.mimir.rewrite.core.companionnative.AppEntry
import com.gruning.mimir.rewrite.core.companionnative.SecondaryTaskKind
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch

/** A GAME tab as the O14 decision reads it: the emulator it ran in, and the library game it names (canonical id). */
internal data class BottomGameTabIdentity(
    val packageName: String,
    val gameId: String?,
)

/** What a retired Bottom GAME tab does to the open play-time measurement a dead process left (O14). */
internal sealed interface BottomOrphanPlayTimeDecision {
    /** Close the measurement at [endElapsedMillis]: the emulator's first move out of the foreground after its start. */
    data class Finish(
        val returnToken: String,
        val startElapsedMillis: Long,
        val endElapsedMillis: Long,
        val endWallClockMs: Long,
        val endSource: String,
        val returnedToForegroundLater: Boolean,
    ) : BottomOrphanPlayTimeDecision

    /** Leave the measurement exactly as it is, as it was left before O14; [reason] is logged. */
    data class Leave(val reason: String) : BottomOrphanPlayTimeDecision
}

/**
 * O14 : When a game tab clears, close its elapsed measurement at the emulator's last foreground activity
 * from Usage Access. This counts the crashed session but excludes time after the game ended.
 *
 * Thor test unit, 2026-09-12: Minish Cap ran on the Bottom, Mimir's process died under it, and RetroArch quit in a
 * process that owned no session for it. Main's return never finishes a BOTTOM measurement
 * ([homeExternalSessionOrphanToken]), so `active` stayed open until the next launch replaced it.
 *
 * Reached when a Tabs liveness reconcile retired the game's tab and this process held no live session for its
 * emulator. In this order, each check leaves the measurement exactly as it is and names why:
 * - nothing is open, or the open measurement is not a Bottom one;
 * - the tab names no game, or another game than the measurement (both ids canonical);
 * - another GAME tab names the same game, so which emulator ran it cannot be told;
 * - the boot count is unreadable or differs: another boot's elapsedRealtime cannot be placed on this clock;
 * - this process began the measurement: its own return path finishes it;
 * - no Usage Access, or the event log could not be read;
 * - no `ACTIVITY_PAUSED` (the old `MOVE_TO_BACKGROUND`) or `ACTIVITY_STOPPED` of the emulator at or after the start.
 *
 * Otherwise the end is the FIRST such event. The start is placed on the wall clock with the current
 * elapsedRealtime offset and the end is brought back with the same offset, so the span counted is the span between
 * the two instants on the event clock. A resume of the emulator after that end is reported
 * ([BottomOrphanPlayTimeDecision.Finish.returnedToForegroundLater]) and not counted: nothing ties it to this game.
 */
internal fun bottomOrphanPlayTimeDecision(
    active: HomeExternalSession?,
    retired: BottomGameTabIdentity,
    otherGameTabs: List<BottomGameTabIdentity>,
    currentBootCount: Int?,
    processStartElapsedMillis: Long,
    nowElapsedMillis: Long,
    nowWallClockMs: Long,
    usageAccessGranted: Boolean,
    activityEvents: (fromWallClockMs: Long, toWallClockMs: Long) -> List<MainHomeActivityEvent>?,
): BottomOrphanPlayTimeDecision {
    val session = active ?: return BottomOrphanPlayTimeDecision.Leave("no_open_measurement")
    if (session.placement != MimirGamePlacement.BOTTOM) {
        return BottomOrphanPlayTimeDecision.Leave("measurement_not_bottom")
    }
    val gameId = retired.gameId?.takeIf(String::isNotBlank)
        ?: return BottomOrphanPlayTimeDecision.Leave("tab_names_no_game")
    if ((session.itemId as? HomeShelfItemId.Emulated)?.gameId?.value != gameId) {
        return BottomOrphanPlayTimeDecision.Leave("different_game")
    }
    if (otherGameTabs.any { it.gameId == gameId }) return BottomOrphanPlayTimeDecision.Leave("ambiguous_match")
    val bootCount = currentBootCount ?: return BottomOrphanPlayTimeDecision.Leave("boot_count_unreadable")
    if (bootCount != session.bootCount) return BottomOrphanPlayTimeDecision.Leave("different_boot")
    if (session.dispatchedAtElapsedMillis >= processStartElapsedMillis) {
        return BottomOrphanPlayTimeDecision.Leave("begun_by_this_process")
    }
    if (!usageAccessGranted) return BottomOrphanPlayTimeDecision.Leave("usage_access_missing")
    val wallMinusElapsed = nowWallClockMs - nowElapsedMillis
    val startWallClockMs = session.dispatchedAtElapsedMillis + wallMinusElapsed
    val events = activityEvents(startWallClockMs, nowWallClockMs)
        ?: return BottomOrphanPlayTimeDecision.Leave("usage_events_unreadable")
    val emulator = events
        .filter { it.packageName == retired.packageName && it.timeMs >= startWallClockMs }
        .sortedBy(MainHomeActivityEvent::timeMs)
    val exit = emulator.firstOrNull { it.type in ForegroundExitTypes }
        ?: return BottomOrphanPlayTimeDecision.Leave("no_qualifying_event")
    return BottomOrphanPlayTimeDecision.Finish(
        returnToken = session.returnToken,
        startElapsedMillis = session.dispatchedAtElapsedMillis,
        endElapsedMillis = (exit.timeMs - wallMinusElapsed).coerceIn(session.dispatchedAtElapsedMillis, nowElapsedMillis),
        endWallClockMs = exit.timeMs,
        endSource = foregroundExitName(exit.type),
        returnedToForegroundLater = emulator.any {
            it.type == UsageEvents.Event.ACTIVITY_RESUMED && it.timeMs > exit.timeMs
        },
    )
}

private val ForegroundExitTypes = setOf(UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED)

private fun foregroundExitName(type: Int): String =
    if (type == UsageEvents.Event.ACTIVITY_STOPPED) "ACTIVITY_STOPPED" else "ACTIVITY_PAUSED"

/**
 * `MimirSession: bottom play time decision=finished reason=last_activity game=… package=… end=HH:mm:ss.SSS endWallMs=…
 * endSource=ACTIVITY_PAUSED measuredMs=… addedMs=… returnedLater=false finalized=true`, or
 * `MimirSession: bottom play time decision=left reason=… game=… package=…`. `addedMs` is what the store's writer added
 * to the game's total: a span at or under the quick-return threshold adds nothing.
 */
internal fun bottomOrphanPlayTimeLogLine(
    decision: BottomOrphanPlayTimeDecision,
    tab: BottomGameTabIdentity,
    endLocalTime: String? = null,
    addedMs: Long? = null,
    finalized: Boolean? = null,
): String {
    val subject = "game=${tab.gameId ?: "none"} package=${tab.packageName}"
    return when (decision) {
        is BottomOrphanPlayTimeDecision.Leave -> "bottom play time decision=left reason=${decision.reason} $subject"
        is BottomOrphanPlayTimeDecision.Finish ->
            "bottom play time decision=finished reason=last_activity $subject end=${endLocalTime ?: "unformatted"} " +
                "endWallMs=${decision.endWallClockMs} endSource=${decision.endSource} " +
                "measuredMs=${decision.endElapsedMillis - decision.startElapsedMillis} " +
                "addedMs=${addedMs ?: "unknown"} returnedLater=${decision.returnedToForegroundLater} " +
                "finalized=${finalized ?: "unknown"}"
    }
}

/**
 * The Android half of [bottomOrphanPlayTimeDecision]: the store's open measurement, canonical game ids, O10's Usage
 * Access reader ([AndroidMainHomeRecoveryPlatform]), and [HomeExternalSessionElapsedStore.finalize] -- the writer every
 * normal finish uses, token-matched, so a launch that replaced the measurement meanwhile is never closed by this. Off
 * the main thread, one log line per retired tab, and nothing it throws reaches the Tabs reconcile.
 */
internal object BottomOrphanPlayTime {
    private const val Tag = "MimirSession"

    fun finishAtLastActivity(
        context: Context,
        retired: AppEntry,
        retiredTogether: List<AppEntry>,
        stillTracked: List<AppEntry>,
    ) {
        val app = context.applicationContext
        CompanionProcessRuntime.processScope.launch(Dispatchers.IO + NonCancellable) {
            val line = runCatching { decideAndFinish(app, retired, retiredTogether, stillTracked) }.getOrElse { failure ->
                "bottom play time decision=left reason=failed:${failure::class.java.simpleName} " +
                    "game=${retired.gameId ?: "none"} package=${retired.packageName}"
            }
            runCatching { Log.i(Tag, line) }
        }
    }

    private suspend fun decideAndFinish(
        app: Context,
        retired: AppEntry,
        retiredTogether: List<AppEntry>,
        stillTracked: List<AppEntry>,
    ): String {
        val active = HomeExternalSessionElapsedStore.activeMeasurement(app)
        val others = (retiredTogether + stillTracked)
            .filter { it.kind == SecondaryTaskKind.GAME && it.packageName != retired.packageName }
            .distinctBy(AppEntry::packageName)
        val measuredId = (active?.itemId as? HomeShelfItemId.Emulated)?.gameId
        val canonical = GameIdentityAliasResolver.resolve(
            app,
            (listOfNotNull(retired.gameId, measuredId?.value) + others.mapNotNull(AppEntry::gameId))
                .filter(String::isNotBlank)
                .map(::GameId),
        ).entries.associate { (from, to) -> from.value to to.value }
        fun identity(row: AppEntry) = BottomGameTabIdentity(row.packageName, row.gameId?.let { canonical[it] ?: it })
        val tab = identity(retired)
        val canonicalActive = if (active != null && measuredId != null) {
            active.copy(itemId = HomeShelfItemId.Emulated(GameId(canonical[measuredId.value] ?: measuredId.value)))
        } else {
            active
        }
        val platform = AndroidMainHomeRecoveryPlatform(app)
        val decision = bottomOrphanPlayTimeDecision(
            active = canonicalActive,
            retired = tab,
            otherGameTabs = others.map(::identity),
            currentBootCount = HomeExternalSessionElapsedStore.bootCountNow(app),
            processStartElapsedMillis = Process.getStartElapsedRealtime(),
            nowElapsedMillis = SystemClock.elapsedRealtime(),
            nowWallClockMs = System.currentTimeMillis(),
            usageAccessGranted = platform.usageAccessGranted(),
            activityEvents = platform::activityEvents,
        )
        val finish = decision as? BottomOrphanPlayTimeDecision.Finish
            ?: return bottomOrphanPlayTimeLogLine(decision, tab)
        // The total is keyed by the measurement's stored id, which is the id the writer adds to.
        val totalKey = requireNotNull(active).itemId
        val before = HomeExternalSessionElapsedStore.observe(app).value[totalKey] ?: 0L
        val finalized = HomeExternalSessionElapsedStore.finalize(
            context = app,
            returnToken = finish.returnToken,
            nowElapsedMillis = finish.endElapsedMillis,
        )
        val after = HomeExternalSessionElapsedStore.observe(app).value[totalKey] ?: 0L
        return bottomOrphanPlayTimeLogLine(
            decision = finish,
            tab = tab,
            endLocalTime = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(finish.endWallClockMs)),
            addedMs = after - before,
            finalized = finalized,
        )
    }
}
