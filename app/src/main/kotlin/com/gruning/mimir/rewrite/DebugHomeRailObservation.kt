package com.gruning.mimir.rewrite

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.ui.MimirQuickRailAction
import com.gruning.mimir.rewrite.core.ui.MimirQuickRailTouchPhase

/** One bounded, in-memory event emitted by the opt-in Home rail diagnostic. */
internal data class HomeRailObservationEvent(
    val source: String,
    val detail: String,
)

/**
 * Pure bounded session state for the Home rail diagnostic.
 *
 * This class deliberately has no Android or persistence dependency so its expiry and event budget are
 * testable without a device. The app wrapper below supplies the debug-only arm gate and log sink.
 */
internal class HomeRailObservationSession(
    private val nowMs: () -> Long,
    private val sink: (HomeRailObservationEvent) -> Unit,
    private val durationMs: Long = HomeRailObservationSession.DurationMs,
    private val maxEvents: Int = HomeRailObservationSession.MaxEvents,
) {
    private var armedUntilMs: Long? = null
    private var eventCount: Int = 0

    fun arm() {
        armedUntilMs = nowMs() + durationMs
        eventCount = 0
    }

    fun expire() {
        armedUntilMs = null
    }

    fun isArmed(): Boolean {
        val deadline = armedUntilMs ?: return false
        if (nowMs() >= deadline) {
            armedUntilMs = null
            return false
        }
        return eventCount < maxEvents
    }

    fun record(event: HomeRailObservationEvent) {
        if (!isArmed()) return
        if (eventCount >= maxEvents) return
        sink(event)
        eventCount += 1
        if (eventCount >= maxEvents) armedUntilMs = null
    }

    internal companion object {
        const val DurationMs: Long = 30_000L
        const val MaxEvents: Int = 100
    }
}

/**
 * Debug-only Home rail input observation. Production routing does not consult this object.
 *
 * The only arm call is inside [MainActivity]'s [DebugIntentHookGate]. Records are short logcat strings,
 * kept in memory until emitted, and stop automatically after 30 seconds or 100 events.
 */
internal object DebugHomeRailObservation {
    private const val Tag = "MimirHomeRailInput"
    private val lock = Any()
    private val handler = Handler(Looper.getMainLooper())
    private val expire = Runnable {
        synchronized(lock) { session.expire() }
    }
    private val session = HomeRailObservationSession(
        nowMs = { SystemClock.elapsedRealtime() },
        sink = { event -> Log.i(Tag, "${event.source} ${event.detail}") },
    )

    fun isArmed(): Boolean = BuildConfig.DEBUG && synchronized(lock) { session.isArmed() }

    fun arm() {
        if (!BuildConfig.DEBUG) return
        synchronized(lock) {
            session.arm()
            handler.removeCallbacks(expire)
            handler.postDelayed(expire, HomeRailObservationSession.DurationMs)
            Log.i(Tag, "armed durationMs=${HomeRailObservationSession.DurationMs} maxEvents=${HomeRailObservationSession.MaxEvents}")
        }
    }

    fun recordRailTouch(
        action: MimirQuickRailAction,
        phase: MimirQuickRailTouchPhase,
        offset: Offset,
    ) {
        if (!isArmed()) return
        record(
            HomeRailObservationEvent(
                source = "rail",
                detail = "action=${action.name} phase=${phase.name} x=${offset.x} y=${offset.y}",
            ),
        )
    }

    fun recordRootTouch(
        event: MotionEvent,
        activity: String,
        displayId: Int,
        outcome: String,
        handled: Boolean,
    ) {
        if (!isArmed()) return
        val phase = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> "DOWN"
            MotionEvent.ACTION_UP -> "UP"
            MotionEvent.ACTION_CANCEL -> "CANCEL"
            else -> return
        }
        record(
            HomeRailObservationEvent(
                source = "root",
                detail = "phase=$phase x=${event.x} y=${event.y} display=$displayId activity=$activity outcome=$outcome handled=$handled",
            ),
        )
    }

    fun recordNavigation(
        action: MimirQuickRailAction,
        before: MimirDestination,
        after: MimirDestination,
    ) {
        if (!isArmed()) return
        record(
            HomeRailObservationEvent(
                source = "navigation",
                detail = "action=${action.name} before=${before.name} after=${after.name}",
            ),
        )
    }

    fun recordController(
        action: ControllerAction,
        scopeBefore: String,
        focusedBefore: String?,
        scopeAfter: String,
        focusedAfter: String?,
        handled: Boolean,
    ) {
        if (!isArmed()) return
        record(
            HomeRailObservationEvent(
                source = "controller",
                detail = "action=${action.name} scopeBefore=$scopeBefore focusedBefore=$focusedBefore scopeAfter=$scopeAfter focusedAfter=$focusedAfter handled=$handled",
            ),
        )
    }

    fun recordAppDispatch(
        action: ControllerAction,
        destination: MimirDestination,
        scope: String,
        focused: String?,
        overlay: String?,
    ) {
        if (!isArmed()) return
        record(HomeRailObservationEvent(
            source = "dispatch",
            detail = "action=$action destination=$destination scope=$scope focused=$focused overlay=$overlay",
        ))
    }

    fun recordLibraryDetail(originScope: String, originFocus: String) {
        if (!isArmed()) return
        record(HomeRailObservationEvent(
            source = "libraryDetail",
            detail = "originScope=$originScope originFocus=$originFocus",
        ))
    }

    private fun record(event: HomeRailObservationEvent) {
        if (!BuildConfig.DEBUG) return
        synchronized(lock) { session.record(event) }
    }
}
