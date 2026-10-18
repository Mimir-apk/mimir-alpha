package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.model.home.AddToShelfResult
import com.gruning.mimir.rewrite.core.model.home.HomeShelfMembership
import com.gruning.mimir.rewrite.feature.library.HomeLaunchReturnTarget
import com.gruning.mimir.rewrite.feature.library.HomeShelfRow
import kotlinx.coroutines.delay

internal sealed interface HomeLaunchReturnResolution {
    /** The restored context's rows have not produced a first emission yet — the cover holds. */
    data object AwaitingRowTruth : HomeLaunchReturnResolution
    /** Rows are loaded and the launched identity is not among them — release after the grace window. */
    data object RowAbsent : HomeLaunchReturnResolution
    data class Ready(val focusKey: FocusKey) : HomeLaunchReturnResolution
}

/**
 * Return resolves against the restored Favorite root or Tome membership truth. Only the captured typed
 * identity resolves; rank and list position are never fallbacks.
 */
internal fun resolveHomeLaunchReturn(
    target: HomeLaunchReturnTarget,
    rows: List<HomeShelfRow>?,
): HomeLaunchReturnResolution {
    if (rows == null) return HomeLaunchReturnResolution.AwaitingRowTruth
    val row = rows.firstOrNull { it.itemId == target.itemId }
        ?: return HomeLaunchReturnResolution.RowAbsent
    return HomeLaunchReturnResolution.Ready(row.focusKey)
}

internal enum class HomeShelfMutationDisposition {
    CHANGED,
    NO_OP,
    FAILURE,
}

internal data class HomeShelfMutationFeedback(
    val disposition: HomeShelfMutationDisposition,
    val message: String,
) {
    val changed: Boolean
        get() = disposition == HomeShelfMutationDisposition.CHANGED
}

internal fun resolveHomeShelfAddMutation(
    result: Result<AddToShelfResult>,
): HomeShelfMutationFeedback = result.fold(
    onSuccess = { addResult ->
        when (addResult) {
            is AddToShelfResult.Added -> HomeShelfMutationFeedback(
                disposition = HomeShelfMutationDisposition.CHANGED,
                message = "Favorite added",
            )
            is AddToShelfResult.AlreadyPresent -> HomeShelfMutationFeedback(
                disposition = HomeShelfMutationDisposition.NO_OP,
                message = "Already a favorite",
            )
        }
    },
    onFailure = {
        HomeShelfMutationFeedback(
            disposition = HomeShelfMutationDisposition.FAILURE,
            message = "Favorite not saved",
        )
    },
)

internal fun resolveHomeShelfRemoveMutation(
    result: Result<Boolean>,
): HomeShelfMutationFeedback = result.fold(
    onSuccess = { removed ->
        if (removed) {
            HomeShelfMutationFeedback(
                disposition = HomeShelfMutationDisposition.CHANGED,
                message = "Favorite removed",
            )
        } else {
            HomeShelfMutationFeedback(
                disposition = HomeShelfMutationDisposition.NO_OP,
                message = "Not a favorite",
            )
        }
    },
    onFailure = {
        HomeShelfMutationFeedback(
            disposition = HomeShelfMutationDisposition.FAILURE,
            message = "Favorite not saved",
        )
    },
)

internal fun nextHomeShelfMutationRevision(
    current: Long,
    feedback: HomeShelfMutationFeedback,
): Long = if (feedback.changed) current + 1L else current

internal const val HomeShelfFeedbackWindowMs = 2_400L
internal const val HomeLaunchReturnRetryMs = 100L

/**
 * Grace window before an [HomeLaunchReturnResolution.AwaitingRenderableRow] releases the return cover
 * (panel review 2026-07-14): long enough for the native shelf snapshot and Room projection to land on a
 * cold resume, short enough that a permanently unprojectable row (app uninstalled while away) never
 * wedges the surface. Any truth change restarts the window.
 */
internal const val HomeLaunchReturnRenderableGraceMs = 4_000L

/**
 * Grace window before the **cold-process surface restore** stops waiting for its focus to land and
 * releases the return cover anyway — the P0 of 2026-08-14 (Odin test device stranded on the loading rune for
 * ~80 minutes).
 *
 * The restore's focus wait used to be an unbounded `while (isActive)` spin whose only exit was
 * `FocusAuthority.state.scope == HomeShelfScope` with the remembered key actually taking focus. A
 * cold start that opens ANY full-screen surface owning its own [com.gruning.mimir.rewrite.core.input.FocusScope]
 * before that lands — device-reproduced with the file manager, whose `FileBrowserController.open()` pushes
 * `FocusScope("files")` — makes that condition unreachable, so `pendingSurfaceRestore` never cleared,
 * `launchReturnRestoring` stayed true, and `MimirPrimaryReturnCover` held the screen **forever** while
 * swallowing every key, every pointer event and Back. That cover's own comment already claimed its worst
 * case was "the 4s absent-row grace"; this constant is what finally makes that claim true on this arm too.
 *
 * Derived from [HomeLaunchReturnRenderableGraceMs] rather than restating 4000: the return cover has ONE
 * patience budget and a second literal could drift away from the comment that documents it — the same
 * "express the budget once" rule `WelcomeHoldMaxMs` already applies to the welcome splash.
 *
 * **Giving up costs nothing.** [awaitHomeSurfaceFocusSettled]'s caller has already applied the restored
 * mode and roots and already called `rememberFocusForScope(HomeShelfScope, key)`, so when the Home shelf
 * does become the active scope — the player closes the browser, an overlay pops — `popScope` restores that
 * remembered key. The player still lands on the tile they left; they just are not held behind an opaque
 * rune until it happens.
 */
internal const val HomeSurfaceRestoreFocusGraceMs = HomeLaunchReturnRenderableGraceMs

/**
 * Wait for a cold-process Home surface restore's focus to settle — **bounded, always terminating**.
 *
 * Returns TRUE when [settleAttempt] reports the focus landed, FALSE when [deadlineMs] passed first. The
 * caller clears `pendingSurfaceRestore` either way; the boolean exists so a test can tell a landing from
 * an expiry, and so a future caller can log the difference.
 *
 * Lifted out of `HomeShelfCoordinator`'s `LaunchedEffect` with every seam injected, following this
 * codebase's own precedent for making async coordinator logic drivable by a plain JUnit test
 * (`FileBrowserStore.runExtraction`, `FileBrowserStore.openAfterRomFolderRefresh`,
 * `fileQueueAfterDismiss`). The composition-only parts — `withFrameNanos`, the real [FocusAuthority] —
 * stay behind [awaitFrame] and [settleAttempt].
 *
 * [deadlineMs] is an **absolute** instant on [nowMillis]' clock, not a duration, for the same reason
 * `returnGraceDeadlines` is: the effect around this is keyed on library truth, so an art bump or a Room
 * re-emission restarts it, and a duration would let a churning source extend the window without limit.
 * The caller owns the deadline across those restarts.
 *
 * [awaitFrame] runs BEFORE the first attempt, preserving the original loop's ordering: focus cannot be
 * requested against a key the shelf has not had a frame to register.
 *
 * The deadline is checked AFTER a failed attempt, so a zero or already-passed [deadlineMs] still makes
 * exactly one honest attempt rather than declining without trying — a caller that recomposes late must
 * not be denied the landing it would otherwise have got on its first frame.
 */
internal suspend fun awaitHomeSurfaceFocusSettled(
    deadlineMs: Long,
    nowMillis: () -> Long,
    awaitFrame: suspend () -> Unit,
    retryMs: Long = HomeLaunchReturnRetryMs,
    settleAttempt: suspend () -> Boolean,
): Boolean {
    while (true) {
        awaitFrame()
        if (settleAttempt()) return true
        if (nowMillis() >= deadlineMs) return false
        delay(retryMs)
    }
}
