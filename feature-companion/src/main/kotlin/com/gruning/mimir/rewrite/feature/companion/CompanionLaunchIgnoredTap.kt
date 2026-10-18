package com.gruning.mimir.rewrite.feature.companion

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/** Receipt target for the rail's General ("All apps") seat, which has no package of its own. */
internal const val CompanionLaunchIgnoredAllApps = "all_apps"

/** QA D17 target for the drawer's backdrop, whose tap dismisses the drawer when no launch is pending. */
internal const val CompanionLaunchIgnoredDismiss = "dismiss"

/** Receipt target for an unpinned rail seat, which has no package either: `empty_q<N>`, N its storage slot (Q1-Q5). */
internal fun companionLaunchIgnoredEmptySeat(slotIndex: Int): String = "empty_q${slotIndex + 1}"

/**
 * the second tap of a fast double tap on a rail pin left no trace. The launch lock disables
 * the seat, and in foundation 1.7.3 a disabled `combinedClickable` still runs `detectTapGestures`
 * (`CombinedClickableNodeImpl.clickPointerInput` checks `enabled` only to pick its double-tap and long-press lambdas),
 * which consumes the down and the up on the Main pass and then declines to call `onClick`.
 *
 * While [locked], this OBSERVES each gesture on the Initial pass -- before any clickable's Main-pass consumption -- and
 * calls [onIgnored] once per completed tap. A gesture a scroll consumes or that leaves the bounds is not a tap and
 * reports nothing. It consumes nothing, so the click handling, the `enabled` flag, the locked look and the semantics
 * stay exactly as they were. Unlocked, the handler returns at once.
 *
 * The block restarts only when [locked] changes, so [onIgnored] is the lambda from that composition: callers pass one
 * that reads through `rememberUpdatedState`.
 */
internal fun Modifier.companionLaunchLockedTapObserver(locked: Boolean, onIgnored: () -> Unit): Modifier =
    pointerInput(locked) {
        if (!locked) return@pointerInput
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (waitForUpOrCancellation(PointerEventPass.Initial) != null) onIgnored()
        }
    }
