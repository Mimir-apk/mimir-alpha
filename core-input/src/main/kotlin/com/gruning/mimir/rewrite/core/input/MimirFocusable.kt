package com.gruning.mimir.rewrite.core.input

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * SKETCH — the one Modifier every focusable element uses to plug into the [FocusAuthority].
 *
 * This is deliberately a small, readable sketch (not the final production hardened version) so the
 * shape of the "one focus authority" wiring is legible to contributors. It demonstrates the intended
 * contract; the final version will add key-event interception plumbed from [InputAuthority] and
 * scroll-into-view. It intentionally does NOT use Compose's own `Modifier.focusable()` /
 * `focusTarget()` — that would reintroduce a SECOND focus engine (the platform's), exactly the
 * two-authorities problem the old product suffered . Focus
 * here is a pure app concept: the authority decides, the element only reports its bounds and reads
 * whether it is the focused key.
 *
 * Usage sketch:
 * ```
 * val focus = LocalFocusAuthority.current            // provided at the app root
 * val key = FocusKey("shelf/$platformId")
 * Box(
 *     Modifier
 *         .mimirFocusable(focus, key, scope, onFocusChanged = { focused -> /* draw gold ring */ })
 * )
 * ```
 *
 * @param authority the single [FocusAuthority] (normally obtained from a CompositionLocal at the root).
 * @param key this element's stable focus identity.
 * @param scope the scope this element lives in (defaults to the root shell scope).
 * @param autoBringIntoView whether this element asks its scroll ancestor to reveal it on focus. Shelf coverflows
 *   disable this because they perform their own center snap and must not run two scroll systems per focus move.
 * @param revealOutset how far past its own bounds this element is revealed. A focus ring is paint outside the element
 *   (`MimirFocusTileRingReach`, 14 dp), and a plain reveal stops a focused row flush with the viewport edge, ring cut
 *   (observed on the bottom-bar PlayStation 2 row). Zero keeps the plain reveal, byte-identical.
 * @param onFocusChanged optional callback invoked when this element gains/loses focus (e.g. to trigger
 *   a compositor-only focus animation — focus anim is compositor-only).
 * @return a Modifier that (1) registers/updates this element's bounds with the authority for the life
 *   of the composition and (2) unregisters on dispose so a departed element can never stay focused.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.mimirFocusable(
    authority: FocusAuthority,
    key: FocusKey,
    scope: FocusScope = FocusScope.Root,
    autoBringIntoView: Boolean = true,
    onFocusChanged: ((focused: Boolean) -> Unit)? = null,
    revealOutset: Dp = 0.dp,
): Modifier {
    // (2) Deregister on dispose — the structural cure for stale-focus traps: an element that leaves
    // the tree is removed from the focus graph and therefore can never remain "focused".
    DisposableEffect(key, scope) {
        onDispose { authority.unregister(key) }
    }

    // Observe the single source of focus truth; notify the caller on transitions.
    // Collect without `by` so global FocusState emissions do not invalidate every mounted target.
    val focusState = authority.state.collectAsState()
    // structuralEqualityPolicy is load-bearing: unchanged false bystanders must not invalidate.
    val isFocused by remember(focusState, key, scope) {
        derivedStateOf(structuralEqualityPolicy()) {
            focusState.value.scope == scope && focusState.value.focused == key
        }
    }

    // (3) Scroll-into-view: when this element becomes the focused key, ask its nearest scrollable ancestor
    // (verticalScroll / LazyColumn / LazyRow — they respond automatically) to bring it fully into view. This
    // is what makes below-fold rows in long Settings/detail panels D-pad-reachable (parity CONTROLLER gate).
    // Disabled by shelf carousels that own their own snapped centering. onFocusChanged fires FIRST so the focus
    // ring (compositor-only) never lags behind scroll correction.
    val bringIntoView = remember { BringIntoViewRequester() }
    val revealOutsetPx = with(LocalDensity.current) { revealOutset.toPx() }
    // The laid-out size, for the grown reveal rect. A plain array, not snapshot state: it is written on every layout
    // pass and read only when focus lands, so nothing should recompose for it.
    val laidOut = remember { IntArray(2) }
    LaunchedEffect(isFocused) {
        onFocusChanged?.invoke(isFocused)
        if (isFocused && autoBringIntoView) {
            bringIntoView.bringIntoView(mimirRevealRect(revealOutsetPx, laidOut[0], laidOut[1]))
        }
    }

    // (1) Report bounds to the authority so directional moves can pick this element as a neighbor.
    // NOTE (final version): also a pointerInput{} → authority.requestFocus(key) on tap (touch parity,
    // the design constraint) and key-event interception fed from InputAuthority.
    // Tap is NOT this modifier's job and is not planned to become it: chain [Modifier.mimirActivatable]
    // (2026-08-15) alongside it instead. This comment used to send consumers to their own
    // `Modifier.clickable`, which is what put a second Compose focus target on the same element as this
    // registration across the tree — the Settings rail it named as the example was itself one.
    return this
        .bringIntoViewRequester(bringIntoView)
        .onGloballyPositioned { coords ->
        // SC01 root cause (2026-07-17, instrument-proven on the RP5): boundsInWindow() CLIPS to the
        // visible window, so a row fully below a verticalScroll fold registered as an EMPTY rect at the
        // origin (ring dump: "backdrop@0..0") — invisible to every directional search, the whole
        // below-the-fold dead-end class (Settings content, pickers, Utility modal). Register the
        // UNCLIPPED geometry instead: position-in-window + laid-out size. Clipped rows then carry their
        // true offsets, directional search finds them, and the bring-into-view above scrolls them in.
        val position = coords.positionInWindow()
        val size = coords.size
        // A genuinely zero-sized node must not take focus (and must not park a phantom at the origin).
        if (size.width <= 0 || size.height <= 0) return@onGloballyPositioned
        laidOut[0] = size.width
        laidOut[1] = size.height
        authority.register(
            key = key,
            scope = scope,
            bounds = FocusBounds(
                left = position.x,
                top = position.y,
                right = position.x + size.width,
                bottom = position.y + size.height,
            ),
        )
    }
}

/**
 * The rect a focused element asks its scroll host to reveal, in its own coordinates: its bounds grown by [outsetPx]
 * on every side. Null -- the plain reveal of the element itself -- when there is no outset, or no laid-out size yet.
 */
internal fun mimirRevealRect(outsetPx: Float, width: Int, height: Int): Rect? {
    if (outsetPx <= 0f || width <= 0 || height <= 0) return null
    return Rect(-outsetPx, -outsetPx, width + outsetPx, height + outsetPx)
}
