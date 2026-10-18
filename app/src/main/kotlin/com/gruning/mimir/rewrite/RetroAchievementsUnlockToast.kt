package com.gruning.mimir.rewrite

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.withTimeoutOrNull

/** How long an "Achievement earned" toast stays up: about five seconds . */
internal const val ACHIEVEMENT_TOAST_MS: Long = 5_000L

/** How much longer than its own time a host may take to report a showing done (a frame to compose it, a busy second display) before it counts as not shown. */
internal const val ACHIEVEMENT_TOAST_DISPLAY_GRACE_MS: Long = 3_000L

internal const val AchievementToastTag = "achievement-toast"
internal const val AchievementToastBadgeTag = "achievement-toast-badge"

/** The widest the toast is (it is as wide as its words, to this). */
internal val AchievementToastMaxWidth: Dp = 360.dp

/** The size of the toast's badge art. */
internal val AchievementToastBadgeSize: Dp = 32.dp

/**
 * What the toast says: one unlock ([count] 1: its [title] and [points]) or several earned at once (the total points; the [title] and [badgePath] are the newest unlock's),
 * in [gameTitle]. [key] tells one showing from the next (the state stamps it).
 */
internal data class AchievementToast(
    val key: Long,
    val raGameId: Int,
    val gameTitle: String,
    val count: Int,
    val title: String,
    val points: Int,
    val badgePath: String?,
)

/** The eyebrow line: "Achievement earned", or for several at once "3 achievements earned · Chrono Cross". */
internal fun achievementToastHeadline(toast: AchievementToast): String =
    if (toast.count <= 1) "Achievement earned" else "${toast.count} achievements earned · ${toast.gameTitle}"

/** The second line: the unlock's title, or (for several) the newest one's. */
internal fun achievementToastTitle(toast: AchievementToast): String = toast.title

/** The last line: the points ("10 points", "1 point"). */
internal fun achievementToastPoints(toast: AchievementToast): String = if (toast.points == 1) "1 point" else "${toast.points} points"

/** What a screen reader says: the whole toast in one phrase. */
internal fun achievementToastDescription(toast: AchievementToast): String =
    achievementToastHeadline(toast) + ", " + achievementToastTitle(toast) + ", " + achievementToastPoints(toast) +
        if (toast.count <= 1) ", ${toast.gameTitle}" else ""

/**
 * The toast on the screen now, process-wide (the unlock watch posts it, the Bottom's Presentation draws it). One at a time: a newer showing replaces the one up, because the
 * newer unlock is the news (and several that arrive together are already one toast).
 */
internal object AchievementToasts {
    private val mutable = MutableStateFlow<AchievementToast?>(null)
    val current: StateFlow<AchievementToast?> = mutable.asStateFlow()
    private val serial = AtomicLong(0L)

    /** The showings a host has run to their end, by key: the only proof a toast was drawn. */
    private val displayed = MutableSharedFlow<Long>(extraBufferCapacity = 8)

    /**
     * Whether a host is composed and collecting [current] right now: the Bottom's Presentation draws the toast and nothing else does
     * (Main has no toast host), so on a single-screen device this is false and a toast posted would be seen by no one.
     */
    val hostMounted: Boolean get() = mutable.subscriptionCount.value > 0

    /** Show [toast] now, stamped as a new showing. */
    fun show(toast: AchievementToast) {
        mutable.value = toast.copy(key = serial.incrementAndGet())
    }

    /**
     * Show [toast] and say whether it was DISPLAYED (RC-56, ): true only once a host has run
     * the showing to its end. With no host mounted nothing is posted and the answer is false at once; with a host that never finishes
     * the showing (within its own time and a grace) the showing is taken down and the answer is false. The caller keeps an unlock it
     * was told was not shown as unshown, so it surfaces again rather than being spent on a toast no one saw.
     */
    suspend fun showAndAwaitDisplay(
        toast: AchievementToast,
        timeoutMs: Long = ACHIEVEMENT_TOAST_MS + ACHIEVEMENT_TOAST_DISPLAY_GRACE_MS,
    ): Boolean {
        if (!hostMounted) return false
        var key = 0L
        // Subscribed BEFORE the showing is posted, or a host that finishes at once could be missed.
        val shown = withTimeoutOrNull(timeoutMs) {
            displayed.onSubscription {
                key = serial.incrementAndGet()
                mutable.value = toast.copy(key = key)
            }.first { it == key }
        }
        if (shown == null && key != 0L) takeDown(key)
        return shown != null
    }

    /** The showing [key] has run its time: take it down, unless a newer one has already replaced it. */
    fun dismiss(key: Long) {
        if (takeDown(key)) displayed.tryEmit(key)
    }

    /** Removes the showing [key] if it is still the one up; true when it was. */
    private fun takeDown(key: Long): Boolean {
        while (true) {
            val showing = mutable.value ?: return false
            if (showing.key != key) return false
            if (mutable.compareAndSet(showing, null)) return true
        }
    }

    fun resetForTest() {
        mutable.value = null
    }
}
