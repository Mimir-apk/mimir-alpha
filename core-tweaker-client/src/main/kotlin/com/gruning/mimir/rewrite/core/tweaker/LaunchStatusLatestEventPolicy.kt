package com.gruning.mimir.rewrite.core.tweaker

/** One cross-app launch-status card emission. */
data class LaunchStatusPresentation(
    val eventId: Long,
    val message: TweakerLaunchOverlayMessage,
    val expiresAtMs: Long,
)

/**
 * Latest-event-wins policy for the cross-app launch-status presenter.
 * Every distinct [eventId] replaces the active card and resets the 2.2 s window.
 */
class LaunchStatusLatestEventPolicy(
    private val displayDurationMs: Long = 2_200L,
) {
    private var active: LaunchStatusPresentation? = null

    init {
        require(displayDurationMs > 0L) { "Launch status display duration must be positive" }
    }

    fun present(
        eventId: Long,
        message: TweakerLaunchOverlayMessage,
        nowMs: Long,
    ): LaunchStatusPresentation {
        val presentation = LaunchStatusPresentation(
            eventId = eventId,
            message = message,
            expiresAtMs = nowMs + displayDurationMs,
        )
        active = presentation
        return presentation
    }

    fun current(nowMs: Long): LaunchStatusPresentation? =
        active?.takeIf { nowMs < it.expiresAtMs }

    fun isCurrent(presentation: LaunchStatusPresentation): Boolean =
        active?.eventId == presentation.eventId && active?.expiresAtMs == presentation.expiresAtMs
}
