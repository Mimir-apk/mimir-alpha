package com.gruning.mimir.rewrite.feature.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.structuralEqualityPolicy
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.input.FocusState
import com.gruning.mimir.rewrite.core.model.PlatformId

/*
 * What the Library screen's composition hears of focus (owperf, 2026-10-15).
 *
 * The screen used to unwrap the authority's own state in its body, its pieces and the gamelist under the card, so every emission re-ran the whole screen: the
 * platform book, the header, the game shelf and the card (Thor, OPEN WITH, Balanced: 17 ms of recomposition on the key-down frame, 11-27 ms on the next, which
 * only exists because opening the picker seats its first row, and 17 ms again on the close). A picker's pad moves, the seating of its first row and a chip moving
 * on the card are not things the screen behind them draws, so the screen is told about the SCOPE and, for the two scopes it draws focus for (the platform shelf
 * and the gamelist), the focused key too. The picker, the card and the rows each ask the authority themselves, in their own scopes.
 */

/**
 * The scopes whose surface asks the authority for its own focus (the Open-with and disc / version pickers, and the launch card): the screen behind them
 * needs to know one of them is up, never which row or chip the pad is on.
 *
 * **WARNING: this list is hard-coded, and it is a promise that nothing composed from [LibraryHostFocus.host] reads the focused key in these scopes.** If a
 * NEW surface under the Library screen starts reading `focusState.focused` (a ring, a highlight, a hint, a `wakeSignal`) while one of these scopes is on top, it
 * is handed `null` and will look unfocused forever: the pad moves, the authority's state changes, and nothing on that surface repaints, with no error and no
 * failing count test (they only bound re-runs from above; `OpenWithStaleUiGuardTest` reads what the picker and the card draw, for these surfaces only). Such a
 * surface must ask the authority itself in its own scope (`isLitFocusIn` / `isRenderedFocusIn` over [LibraryHostFocus.raw], as the picker rows and the card
 * do), or its scope must come off this list and the counts in `OpenWithRecompositionCountTest` be accepted back. A NEW scope that belongs to a surface of this
 * kind is not on the list until it is added here: until then the screen re-runs on every pad move inside it, which is slow, not wrong.
 */
private val LibraryHostBlindScopes: Set<FocusScope> = setOf(OpenWithScope, LaunchChoiceScope, GameDetailScope)

/**
 * The focus the Library screen composes with: the authority's own state, except that the focused key is dropped while a scope in [LibraryHostBlindScopes] is on
 * top. Everything the screen reads of a key it gates on a scope it draws focus for (the shelf's, the gamelist's), so no answer differs.
 */
internal fun libraryHostFocus(raw: FocusState): FocusState =
    if (raw.focused != null && raw.scope in LibraryHostBlindScopes) FocusState(raw.scope, null) else raw

/** What the gamelist layer is told when the gamelist is not the scope that owns input: it paints its conserved state, whichever scope is on top. */
internal val LibraryGamelistLayerUnowned: FocusState = FocusState(FocusScope("library/gamelist-unowned"), null)

/**
 * The focus the gamelist layer (the game shelf, the header's search ring) composes with. Both read it only through `isRenderedFocusIn` / `isLitFocusIn`, which
 * answer from the authority's remembered key and no ring for any scope but their own, so every other scope is the same answer and the layer is not re-run
 * for the card, the picker or Search coming and going over it, nor for a pad move inside them.
 *
 * **WARNING: `GamelistScope` is the only scope hard-coded here, and the layer is blind to the scope and the key of every other one.** Anything composed from
 * [LibraryHostFocus.gamelist] (the game shelf, the header's search ring) that starts to read `focusState.scope` or `focusState.focused` for another scope
 * (say, a hint that shows while the card is up, or a shelf tile that dims under Search) is handed [LibraryGamelistLayerUnowned] and will never see that scope:
 * it paints its conserved state and stays put when the pad moves elsewhere, silently. Such a read must come from [LibraryHostFocus.host] (or `raw`), at the
 * price of the layer being re-run again by every scope change, which is what this indirection exists to avoid.
 */
internal fun libraryGamelistLayerFocus(raw: FocusState): FocusState =
    if (raw.scope == GamelistScope) raw else LibraryGamelistLayerUnowned

/** The authority's state as the Library screen reads it: [raw] for a surface that must hear every move, [host] for the screen, [gamelist] for the layer under the card. */
internal class LibraryHostFocus(
    val raw: State<FocusState>,
    val host: State<FocusState>,
    val gamelist: State<FocusState>,
)

@Composable
internal fun rememberLibraryHostFocus(authority: FocusAuthority): LibraryHostFocus {
    val raw = authority.state.collectAsState()
    return remember(raw) {
        LibraryHostFocus(
            raw = raw,
            host = derivedStateOf(structuralEqualityPolicy()) { libraryHostFocus(raw.value) },
            gamelist = derivedStateOf(structuralEqualityPolicy()) { libraryGamelistLayerFocus(raw.value) },
        )
    }
}

/**
 * [value] if it equals the one this call returned last time, else [value] itself. A snapshot rebuilt on every pass is a new instance each time, and a lambda
 * that captures it is a new lambda, so the screen under it is re-run for a pass that changed nothing it draws. Equality is the snapshot's own (a data class).
 */
@Composable
fun <T> rememberEqualInstance(value: T): T {
    val last = remember { arrayOfNulls<Any>(1) }

    @Suppress("UNCHECKED_CAST")
    val kept = last[0] as T?
    if (kept != null && kept == value) return kept
    last[0] = value
    return value
}

/** [libraryShelfWindowMemoryKey], made again only when one of its three answers changes (it is a new object each call, and the shelf takes it as a parameter). */
@Composable
internal fun rememberLibraryShelfWindowMemoryKey(
    openPlatform: PlatformId,
    favoritesOnly: Boolean,
    searching: Boolean,
): Any = remember(openPlatform, favoritesOnly, searching) { libraryShelfWindowMemoryKey(openPlatform, favoritesOnly, searching) }
