package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.CompanionMode
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.hypot

internal enum class CompanionWorkspacePage {
    SHELF,
    ACTIVITY,
}

/** Process state so a Presentation remount does not reset the workspace page. */
internal object CompanionWorkspaceState {
    private val mutablePage = MutableStateFlow(CompanionWorkspacePage.SHELF)
    val page: StateFlow<CompanionWorkspacePage> = mutablePage.asStateFlow()

    fun show(page: CompanionWorkspacePage) {
        mutablePage.value = page
    }

    fun toggle() {
        mutablePage.value = when (mutablePage.value) {
            CompanionWorkspacePage.SHELF -> CompanionWorkspacePage.ACTIVITY
            CompanionWorkspacePage.ACTIVITY -> CompanionWorkspacePage.SHELF
        }
    }
}

internal enum class CompanionWorkspaceSwipe {
    LEFT,
    RIGHT,
    NONE,
}

internal fun companionWorkspaceSwipe(
    deltaX: Float,
    deltaY: Float,
    touchSlop: Float,
): CompanionWorkspaceSwipe {
    val threshold = touchSlop.coerceAtLeast(0f)
    if (hypot(deltaX.toDouble(), deltaY.toDouble()).toFloat() < threshold) return CompanionWorkspaceSwipe.NONE
    if (abs(deltaX) <= abs(deltaY)) return CompanionWorkspaceSwipe.NONE
    return when {
        deltaX < 0f -> CompanionWorkspaceSwipe.LEFT
        deltaX > 0f -> CompanionWorkspaceSwipe.RIGHT
        else -> CompanionWorkspaceSwipe.NONE
    }
}

/**
 * The current game could disappear after a swipe back when it was not playing on the Bottom. Presence of the current game on the Activity page comes from the session authority
 * alone — a retained NOW_PLAYING card (the old payload-game-id gate) can be absent for a whole session
 * (never seeded while a foreign app owned the Bottom at launch, or cleared by a resume backstop) while the
 * session itself still holds the game live. The card still drives decoration and handoff only (hero art,
 * the detail card, Return to game) via its own scoped lookup — a mismatch there yields no art, not a wrong
 * game.
 */
internal fun companionActivityGame(session: MimirGameSession?): MimirGameSession? {
    val game = session ?: return null
    return game.takeIf {
        it.phase in setOf(
            MimirSessionPhase.DISPATCHED,
            MimirSessionPhase.FOREGROUND_PROVEN,
            MimirSessionPhase.YIELDED_TO_SECONDARY_TASK,
            MimirSessionPhase.RETURNING,
        )
    }
}

internal fun companionPresentationPayload(
    current: CompanionPayload,
    retainedHome: CompanionPayload?,
): CompanionPayload = if (
    current.mode == CompanionMode.IDLE &&
    retainedHome?.mode == CompanionMode.HOME
) {
    retainedHome
} else {
    current
}

/** The Shelf keeps its HOME read model visible while Activity uses the authoritative payload. */
internal fun companionShelfPayload(
    current: CompanionPayload,
    retainedHome: CompanionPayload?,
): CompanionPayload {
    if (current.mode == CompanionMode.HOME) return current
    val source = current.takeIf { it.homeLanes.isNotEmpty() }
        ?: retainedHome?.takeIf { it.mode == CompanionMode.HOME }
    val themeId = current.themeId.ifBlank { source?.themeId.orEmpty() }
    return (source ?: current).copy(
        mode = CompanionMode.HOME,
        gameId = null,
        title = null,
        platformId = null,
        platformLabel = null,
        heroArtRef = null,
        heroArtIsLogo = false,
        themeSigilRef = current.themeSigilRef ?: source?.themeSigilRef,
        themeId = themeId,
        statsLabel = null,
        statusLabel = null,
        statusReady = false,
        launcherLabel = null,
        positionLabel = null,
        isLoading = false,
        runningApp = null,
        homeShelfDetail = null,
    )
}

/** Semantic selection survives temporary input locks, including the dock. */
internal data class CompanionActivityDetailState(
    val selectedSessionKey: String?,
    val showGame: Boolean,
    val canOpen: Boolean,
)

internal fun companionActivityDetailState(
    selectedSessionKey: String?,
    liveSessionKey: String?,
    matchingCardAvailable: Boolean,
    navigationEnabled: Boolean,
): CompanionActivityDetailState {
    val valid = matchingCardAvailable && liveSessionKey != null
    val selection = selectedSessionKey?.takeIf { valid && it == liveSessionKey }
    return CompanionActivityDetailState(selection, selection != null, valid && navigationEnabled)
}

internal fun companionActivitySessionElapsed(startElapsedMs: Long?, nowElapsedMs: Long): Long? =
    startElapsedMs?.takeIf { it >= 0 && nowElapsedMs >= it }?.let { nowElapsedMs - it }

/**
 * B6: whether a committed lattice -> Activity switch actually landed on Activity. The Activity swipe hint retires only then: a swipe that was cancelled never
 * reaches the switch, and a switch that did not land (the page is still the shelf) teaches nothing the player has done.
 */
internal fun companionActivitySwipeLanded(page: CompanionWorkspacePage): Boolean = page == CompanionWorkspacePage.ACTIVITY
