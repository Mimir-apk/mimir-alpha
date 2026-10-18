package com.gruning.mimir.rewrite.feature.files

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gruning.mimir.rewrite.core.files.FILE_SEARCH_SCOPE_COPY
import com.gruning.mimir.rewrite.core.files.FileItem
import com.gruning.mimir.rewrite.core.files.FileSearchFilter
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.input.mimirFocusable
import com.gruning.mimir.rewrite.core.input.rememberMimirKeyboardFocusBinding
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirKeyboard
import com.gruning.mimir.rewrite.core.ui.MimirKeyboardController
import com.gruning.mimir.rewrite.core.ui.MimirKeyboardKeyDecoration
import com.gruning.mimir.rewrite.core.ui.MimirKeyboardKeyDecorator
import com.gruning.mimir.rewrite.core.ui.MimirSearchGlyph
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.MimirTypeStep
import com.gruning.mimir.rewrite.core.ui.MimirFocusTileRingReach
import com.gruning.mimir.rewrite.core.ui.MimirRingShape
import com.gruning.mimir.rewrite.core.ui.mimirSelectionRing
import com.gruning.mimir.rewrite.core.ui.etchedChipInk
import com.gruning.mimir.rewrite.core.ui.mimirEtchedChipSurface
import kotlinx.coroutines.flow.Flow

/**
 * **F-08 — the search screen.**
 *
 * This file is presentation and Compose lifecycle only. Every decision it renders — which key holds
 * focus, what a press does, whether a query is worth walking for, what the player is told while nothing
 * has arrived — is a pure function in `FileBrowserSearch.kt`, because `feature-files` has no Compose
 * harness and a decision made inside a composable here could never be asserted.
 *
 * ## How a controller-only player types a query
 *
 * The same way they type one anywhere else in this app, through the same component: they focus the query
 * field, press A, and `core-ui`'s [MimirKeyboard] comes up driven by `core-input`'s
 * `MimirKeyboardFocusBinding`.
 *
 * **The model came from `feature-library`'s `LibrarySearchOverlay`, the existing
 * precedent — and its answer to this exact question is that it does NOT own a keyboard.** Its query
 * control (`LibrarySearchOverlay.kt:414-425`) is a plain focusable that calls `onOpenKeyboard()`; the
 * keyboard itself is mounted by `app/`, which assembles a `MimirKeyboardHost` from
 * `rememberMimirKeyboardFocusBinding` and routes input to it through `dispatchMimirKeyboardInput`. Five
 * surfaces in this tree repeat that arrangement.
 *
 * This file follows the same arrangement with **one seam moved and nothing forked.** The keyboard is
 * assembled here rather than in `app/` because `app/`'s router is an enumerated
 * `when (top: MimirOverlayKind)` (`MimirAppInputDispatch.kt:118+`) whose `else` arm returns `false` — so
 * a FOLDER_BROWSER keyboard would otherwise need a new branch there. Every part is public API from a module `feature-files` already depends on:
 * `rememberMimirKeyboardFocusBinding`, `MimirKeyboardFocusScope` and `dispatchMimirKeyboardInput` from
 * `core-input`; [MimirKeyboard], [MimirKeyboardController] and [MimirKeyboardKeyDecorator] from
 * `core-ui`. **Nothing is copied.** What IS re-stated locally is `app/`'s ~15-line
 * `rememberMimirKeyboardHost` wiring (`MimirKeyboardHost.kt:20-47`), which is `internal` to that module —
 * assembly of public parts; hoisting the shared wiring into core-ui remains deferred.
 *
 * Input reaches the keyboard through [FileBrowserController.dispatch]'s own first arm rather than through
 * `app/`: `dispatchFolderBrowser` already calls that function for every press, and the enumerated router
 * above it returns `false` for this overlay, so the press arrives here unclaimed. No `app/` dispatch edit.
 *
 * ## Layout
 *
 * The design's own search screen (`Mimir Files.dc.html:78-85, 150-152, 226-231`): the query field and its
 * five filter chips on one row above the results, the count line beneath them, and the Scope card in the
 * right pane carrying [FILE_SEARCH_SCOPE_COPY]'s sentence — a `const` in `core-files` pinned by `FileSearchPolicyTest`. Since localization step 4b the screen
 * draws it from `files_search_scope_copy` (English word for word the const, Spanish its faithful rendering; `FilesSearchTextTest` pins both).
 */
@Composable
internal fun FileBrowserSearchScreen(
    controller: FileBrowserController,
    authority: FocusAuthority,
    focusedKey: FocusKey?,
    search: (String, FileSearchFilter) -> Flow<FileItem>,
    volumeLabel: String?,
    crumbLabels: Map<String, String>,
    palette: MimirThemePalette,
    // F-S1/F-S2: the grid the enclosing BoxWithConstraints just solved -- RC-12's own value, threaded
    // rather than re-measured. Taken as a PARAMETER and not read off the controller on purpose: the
    // controller still holds FileBrowserGrid.Authored (the Odin's 833dp box) until `reprojectGrid` runs
    // one composition later, and seating the scope card off that would flash a seated card for a frame
    // on every device narrower than the Odin -- which is every device this defect is about.
    grid: FileBrowserGrid,
    modifier: Modifier = Modifier,
) {
    val results = controller.searchResults
    // R6-P2-07: one cache per WALK, not per controller and not per batch (review finding 2,
    // 2026-09-15). Keying on `controller.searchQuery` and `controller.searchFilter`, not just
    // `controller`, is what bounds retention to the walk actually running -- a slow typist's "a" over
    // a 100k-file card under ALL narrowing to "alien" starts a fresh, empty cache instead of the old
    // one still pinning every tile "a" ever classified (~60k tiles and their FileItems, ~30MB, versus
    // ~10 for "alien" alone). `results` stays out of the key on purpose: it is what GROWS batch over
    // batch within one walk, and keying on it would defeat the incremental classification this cache
    // exists for in the first place. It also resets on every `closeSearch`, not only on a fresh mount
    // or `switchVolume`'s re-root: `FileBrowserSearchScreen` is composed only
    // `if (controller.searchOpen)` (`FileBrowserSurface.kt`), so leaving search drops this whole
    // `remember` scope regardless of key, and `openSearch` unconditionally resets `searchQuery` to ""
    // and `searchFilter` to ALL on the way back in either way.
    //
    // R6-F-09, 2026-09-15: deliberately NOT keyed on `controller.searchWalkToken` (below, in
    // `FileBrowserSearchCollector`) as well. A re-press of the already-active chip changes the token, so
    // the walk restarts, but changes neither `searchQuery` nor `searchFilter` -- this `remember` key
    // tuple comes back identical, so the SAME cache instance survives the restart and every tile the
    // previous walk already classified for this exact query/filter is a hit rather than a re-classify.
    // That is the intended behaviour, not an oversight: the token exists to make the WALK repeat, not to
    // make classification pay for itself twice.
    val classificationCache = remember(controller, controller.searchQuery, controller.searchFilter) {
        mutableMapOf<String, FileBrowserTile>()
    }
    val tiles = remember(results, controller) {
        fileBrowserSearchTiles(
            items = results,
            cache = classificationCache,
            classifyPlatform = controller.searchClassifier,
        )
    }

    // F-S1. The layout boundary, in one line: the scope card holds its seat only while the query field it is
    // sitting next to still clears its own floor. See fileBrowserSearchScopeCardSeated.
    val scopeCardSeated = fileBrowserSearchScopeCardSeated(grid.surfaceWidthDp)

    FileBrowserSearchCollector(controller = controller, search = search, grid = grid)

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            FileBrowserSearchControls(
                controller = controller,
                authority = authority,
                focusedKey = focusedKey,
                palette = palette,
            )
            FileBrowserSearchStatusLine(
                count = results.size,
                phase = controller.searchPhase,
                volumeLabel = volumeLabel,
                palette = palette,
            )
            // The yielded seat. Drawn UNCONDITIONALLY of the status line above it, which returns null
            // while no query has been expressed -- the promise is owed to a player who has not typed
            // yet just as much as to one who has, and on the seated layout the card always shows it.
            if (!scopeCardSeated) {
                FileBrowserSearchScopeSentence(palette = palette, modifier = Modifier.fillMaxWidth())
            }
            FileBrowserSearchResults(
                tiles = tiles,
                controller = controller,
                authority = authority,
                focusedKey = focusedKey,
                crumbLabels = crumbLabels,
                palette = palette,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
        if (scopeCardSeated) {
            FileBrowserSearchScopeCard(
                palette = palette,
                modifier = Modifier.width(FileBrowserInspectorWidthDp.dp).fillMaxHeight(),
            )
        }
    }
}

/**
 * Runs the walk, debounced, and publishes what it finds in batches.
 *
 * **Keyed on the query, the filter and [FileBrowserController.searchWalkToken], so a change CANCELS the
 * previous walk** — `LaunchedEffect` tears down its coroutine on a key change, which cancels the
 * collector, which cancels the cold `Flow` mid-directory. That is the property `FileSearchPolicy`'s own
 * KDoc names as this feature's cost bound ("a collector that stops ... cancels the walk mid-directory
 * instead of paying for the rest of it"), used here exactly as written rather than re-derived.
 *
 * **The walk token, specifically, is what makes a re-press of the already-active chip restart anything**
 * (R6-F-09, 2026-09-15). `FileBrowserController.setSearchFilter` writes the same value back to
 * `searchFilter` on a re-press, and Compose does not notify this effect's key comparison of a write that
 * does not change the value — without the token, `query` and `filter` above read back identical to the
 * previous composition and this `LaunchedEffect` simply never re-launched, leaving
 * `resetSearchResults`'s empty list on screen with nothing left running to replace it. The token is
 * bumped on every `setSearchFilter` call, re-press included, so it is never equal to its previous value
 * and the effect always restarts.
 *
 * The [delay] is what makes the cancel-on-key-change bound worth having: without it every keystroke
 * starts a volume walk that the next keystroke throws away. See [FileBrowserSearchDebounceMs] for where
 * the number comes from — it is derived from `core-input`'s own directional pace floor, not chosen.
 */
@Composable
private fun FileBrowserSearchCollector(
    controller: FileBrowserController,
    search: (String, FileSearchFilter) -> Flow<FileItem>,
    grid: FileBrowserGrid,
) {
    val query = controller.searchQuery
    val filter = controller.searchFilter
    val walkToken = controller.searchWalkToken
    LaunchedEffect(controller, query, filter, walkToken, grid) {
        if (!fileBrowserSearchLaunches(query, filter)) {
            controller.publishSearchResults(emptyList(), FileBrowserSearchPhase.IDLE)
            return@LaunchedEffect
        }
        controller.publishSearchResults(emptyList(), FileBrowserSearchPhase.RUNNING)
        kotlinx.coroutines.delay(FileBrowserSearchDebounceMs)

        val found = mutableListOf<FileItem>()
        var published = 0
        var lastFlushMs = System.currentTimeMillis()
        search(query, filter).collect { item ->
            found.add(item)
            val now = System.currentTimeMillis()
            if (fileBrowserSearchShouldFlush(found.size - published, now - lastFlushMs, grid)) {
                published = found.size
                lastFlushMs = now
                controller.publishSearchResults(found.toList(), FileBrowserSearchPhase.RUNNING)
            }
        }
        // The final publish is unconditional, so the settled state never depends on the batching above.
        controller.publishSearchResults(found.toList(), FileBrowserSearchPhase.SETTLED)
    }
}

// ──────────────────────────────────────────────────────────────────── Query field + chips ──

@Composable
private fun FileBrowserSearchControls(
    controller: FileBrowserController,
    authority: FocusAuthority,
    focusedKey: FocusKey?,
    palette: MimirThemePalette,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileBrowserSearchQueryField(
            query = controller.searchQuery,
            focused = focusedKey == FileBrowserSearchFocus.QueryKey,
            authority = authority,
            scope = controller.searchScope,
            controller = controller,
            palette = palette,
            modifier = Modifier.weight(1f),
        )
        FileSearchFilter.entries.forEach { filter ->
            FileBrowserSearchChip(
                filter = filter,
                selected = controller.searchFilter == filter,
                focused = focusedKey == FileBrowserSearchFocus.filterKey(filter),
                authority = authority,
                scope = controller.searchScope,
                controller = controller,
                palette = palette,
            )
        }
    }
}

/**
 * The query field — `<input class="mm-input" placeholder="Search SD card">` (design line 80), at its own
 * stated `min-height:34px`.
 *
 * **It is not a text input.** There is no `BasicTextField` here and no Compose focus requester of any
 * kind (the repo-wide grep gate forbids naming one, so this sentence deliberately does not): this
 * surface has one focus authority, and a Compose text field would bring a second one with it. It is a focusable plate that shows the current query and opens [MimirKeyboard] on activation — the
 * identical shape as `LibrarySearchOverlay`'s own `LibrarySearchControl` (`:474-515`), which shows
 * `criteria.query.trim().ifBlank { "TITLE OR NAME" }` and calls `onOpenKeyboard`.
 */
@Composable
private fun FileBrowserSearchQueryField(
    query: String,
    focused: Boolean,
    authority: FocusAuthority,
    scope: FocusScope,
    controller: FileBrowserController,
    palette: MimirThemePalette,
    modifier: Modifier = Modifier,
) {
    val key = FileBrowserSearchFocus.QueryKey
    // TWO BOXES, as the chips beside it have: the outer one is the hit target and the focus registration, [FileBrowserSearchControlRowHeightDp] tall (the standing 48 dp floor, and the
    // control row's layout math); the inner one is the plate, the same capsule at the same 36 dp as a chip beside it (update's sweep, 10-09: the field was a 110 px rounded rectangle
    // beside 83 px capsules). The ring hugs the PLATE, a pill before the surface, with no inset, so its wash is under the opaque metal; the rim hands over to it while focused.
    Box(
        modifier = modifier
            // F-S4: the design mock's own `min-height:34px` is below the standing 48dp touch floor, and the mock is not authority over it.
            .defaultMinSize(minHeight = FileBrowserSearchControlRowHeightDp.dp)
            // autoBringIntoView off: this screen owns its own scroll, exactly as the folio does.
            .mimirFocusable(authority, key, scope, autoBringIntoView = false)
            .pointerInput(key) { detectTapGestures(onTap = { controller.handleSearchTap(key) }) },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .mimirSelectionRing(focused = focused, identity = key, shape = MimirRingShape.Pill, edgeFlush = true)
                .mimirEtchedChipSurface(palette, warmed = false, focused = focused, edgeFlush = true)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // The one drawn magnifier at the one search size, core-ui's (the font glyph was 14 px beside 30 px text): the same mark the Library, the Activity filter and
            // Customize media draw. Its width is the width model's glyph term ([fileBrowserSearchQueryFloorDp]).
            MimirSearchGlyph(color = palette.textMuted)
            Text(
                text = if (query.isBlank()) filesResources().getString(FileBrowserSearchPlaceholder) else query,
                // F-S3: 13sp -> Body. Read from the SAME symbol the width model divides by, so the painted
                // placeholder and fileBrowserSearchQueryFloorDp can never disagree about how wide it is.
                fontSize = FileBrowserSearchQueryStepSp.sp,
                color = if (query.isBlank()) palette.textMuted else palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * The placeholder — the design says `"Search SD card"` (line 80), which this cannot say: the mock has one
 * fake volume and a real device may be searching internal storage. Naming the wrong card is the same
 * unverified assertion [fileBrowserBreadcrumbs] refuses to make about volume names.
 */
internal val FileBrowserSearchPlaceholder: Int = R.string.files_search_placeholder

/**
 * One filter chip — the design's pill (line 785-795): `minHeight:30`, `borderRadius:999`, ember when
 * selected and muted when not.
 *
 * Its label is [fileBrowserSearchFilterLabel] (localization step 4b): `core-files`' [FileSearchFilter.label] is the English the width model was measured against and
 * stays as it is; the chip draws the host's language. This file writes none of the five.
 */
@Composable
private fun FileBrowserSearchChip(
    filter: FileSearchFilter,
    selected: Boolean,
    focused: Boolean,
    authority: FocusAuthority,
    scope: FocusScope,
    controller: FileBrowserController,
    palette: MimirThemePalette,
) {
    val key = FileBrowserSearchFocus.filterKey(filter)
    // F-S4, TWO BOXES ON PURPOSE — the hit target and the ink are different rectangles.
    //
    // The outer Box is what a finger has to find: >= MimirSpacing.minTarget on BOTH axes, and it is
    // where the focus registration and the tap gesture live, so the bounds the authority is told about
    // are the bounds that actually respond. The inner Box is the pill the design draws: unchanged at its
    // intrinsic width and its 30dp floor, centred inside the target. 'All' painted 37.5dp wide and 36dp
    // tall -- a 30x46dp finger miss the mock's own `min-height:30` licensed and the standing floor never
    // did.
    //
    // Collapsing these into one Box would grow the visible pill instead, which is a visual-weight
    // change outside this fix. The 10dp of width this costs the control row is not free and is not
    // hidden: fileBrowserSearchChipWidthDp applies the same floor, so the clearance function prices it.
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = MimirSpacing.minTarget, minHeight = MimirSpacing.minTarget)
            .mimirFocusable(authority, key, scope, autoBringIntoView = false)
            .pointerInput(key) { detectTapGestures(onTap = { controller.handleSearchTap(key) }) },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                // The plate is as wide as the hit box when its word is short, so every gap in the row is the row's own 7 dp: a 37.5 dp "All" in a 48 dp box left 28 px beside its neighbour against
                // 16 px elsewhere (update's sweep, 10-09). The width model already prices the 48 dp ([fileBrowserSearchChipWidthDp]), so no layout moves.
                .defaultMinSize(minWidth = MimirSpacing.minTarget, minHeight = 30.dp)
                // Update: an etched chip (warmed when selected), not an ember-tinted capsule. Its width model ([fileBrowserSearchChipWidthDp]) is unchanged: the same
                // ink, the same 11 dp padding and step, because that model is calibrated on a handheld and a re-typeset chip would need a new measurement.
                // The ring hugs the PLATE (the 30 dp chip), not the 48 dp hit box around it, with no inset, and goes BEFORE the surface so its edge is the plate's edge and its
                // wash is under the opaque metal.
                .mimirSelectionRing(focused = focused, identity = key, shape = MimirRingShape.Pill, edgeFlush = true)
                .mimirEtchedChipSurface(palette, warmed = selected, focused = focused, edgeFlush = true)
                .padding(horizontal = 11.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = fileBrowserSearchFilterLabel(filesResources(), filter),
                // F-S3: 11.5sp was between steps. Label -- which is `Pill`'s own step, this element's
                // role. Same symbol the width model rescales the measured ink by.
                fontSize = FileBrowserSearchChipStepSp.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = palette.etchedChipInk(warmed = selected),
                maxLines = 1,
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────────── Results ──

@Composable
private fun FileBrowserSearchStatusLine(
    count: Int,
    phase: FileBrowserSearchPhase,
    volumeLabel: String?,
    palette: MimirThemePalette,
) {
    val label = fileBrowserSearchStatusLabel(filesResources(), count, phase, volumeLabel) ?: return
    Text(
        text = label,
        style = MimirTypeScale.RunicEyebrow,
        color = palette.textMuted,
        maxLines = 1,
    )
}

@Composable
private fun FileBrowserSearchResults(
    tiles: List<FileBrowserTile>,
    controller: FileBrowserController,
    authority: FocusAuthority,
    focusedKey: FocusKey?,
    crumbLabels: Map<String, String>,
    palette: MimirThemePalette,
    modifier: Modifier = Modifier,
) {
    val empty = fileBrowserSearchEmptyMessage(filesResources(), tiles.size, controller.searchPhase)
    if (empty != null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(text = empty, style = MimirTypeScale.Support, color = palette.textMuted)
        }
        return
    }

    val listState = rememberLazyListState()
    val density = LocalDensity.current
    // The folio's own settlement model, reused rather than restated: `fileBrowserRevealAction` is pure
    // and takes plain numbers, so it applies to any LazyColumn. F-02's cadence therefore holds on this
    // screen too, with no second scroll owner (every `mimirFocusable` here passes autoBringIntoView=false).
    LaunchedEffect(focusedKey, tiles) {
        val index = tiles.indexOfFirst { FileBrowserSearchFocus.resultKey(it.path) == focusedKey }
        if (index < 0) return@LaunchedEffect
        val layout = listState.layoutInfo
        val item = layout.visibleItemsInfo.firstOrNull { it.index == index }
        when (
            val action = fileBrowserRevealAction(
                targetIndex = index,
                firstVisibleIndex = layout.visibleItemsInfo.firstOrNull()?.index,
                visibleCount = layout.visibleItemsInfo.size,
                focusedItemOffset = item?.offset,
                focusedItemSize = item?.size,
                viewportStart = layout.viewportStartOffset,
                viewportEnd = layout.viewportEndOffset,
                estimatedItemSize = with(density) {
                    (FileBrowserSearchResultRowPitchDp - FileBrowserRowGapDp).dp.roundToPx()
                },
            )
        ) {
            FileBrowserRevealAction.None -> Unit
            is FileBrowserRevealAction.SeekTo -> listState.scrollToItem(action.index, action.scrollOffset)
            is FileBrowserRevealAction.AnimateTo -> listState.animateScrollToItem(action.index, action.scrollOffset)
            is FileBrowserRevealAction.ScrollBy -> listState.animateScrollBy(
                value = action.delta,
                animationSpec = tween(MimirMotion.FocusMs, easing = MimirMotion.Easing),
            )
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(FileBrowserRowGapDp.dp),
        // Top and bottom: a lazy list clips exactly on its axis, and a focused row's ring reaches
        // MimirFocusTileRingReach past the row (ring migration, 2026-09-28). End stays the design's own.
        contentPadding = PaddingValues(top = MimirFocusTileRingReach, end = 5.dp, bottom = MimirFocusTileRingReach),
    ) {
        items(tiles, key = { it.path }) { tile ->
            FileBrowserSearchResultRow(
                tile = tile,
                focused = focusedKey == FileBrowserSearchFocus.resultKey(tile.path),
                whereLabel = remember(tile.path, controller.rootPath, crumbLabels) {
                    fileBrowserSearchParentPath(tile.path)
                        ?.let { fileBrowserFriendlyPath(controller.rootPath, it, crumbLabels) }
                        .orEmpty()
                },
                authority = authority,
                scope = controller.searchScope,
                controller = controller,
                palette = palette,
            )
        }
    }
}

/**
 * One hit: its badge, its name, and **where it lives** — the design's own search-tile override
 * (`Mimir Files.dc.html:721-722`, which replaces the tile's meta line with `this.friendly(x.p)`).
 *
 * Full-width rather than a grid cell; see [fileBrowserSearchRows] for that disclosed deviation and its
 * reasoning. Nothing here is faded: every hit
 * wears the badge word the folio's tile for it would ([fileBrowserBadgeLabel]), at full presence.
 */
@Composable
private fun FileBrowserSearchResultRow(
    tile: FileBrowserTile,
    focused: Boolean,
    whereLabel: String,
    authority: FocusAuthority,
    scope: FocusScope,
    controller: FileBrowserController,
    palette: MimirThemePalette,
) {
    val key = FileBrowserSearchFocus.resultKey(tile.path)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // F-S2/F-S4: the row's floor is now its REAL painted height (62dp of two-line content),
            // which also clears MimirSpacing.minTarget -- the old 46 was a single-line assumption that
            // both under-stated the target and lied to the page-stride lattice.
            .defaultMinSize(minHeight = FileBrowserSearchResultRowHeightDp.dp)
            // The one ring (ring migration, 2026-09-28): a row ring on the rows' shared corner. The ring paints the
            // row's own grey lift so the family's wash lands over it. Keyed on the row's own focus key.
            .mimirSelectionRing(
                focused = focused,
                identity = key,
                shape = MimirRingShape.Row(FileBrowserRowCorner),
                fill = SolidColor(if (focused) palette.surfaceHigh else palette.surfaceRaised),
            )
            .clip(RoundedCornerShape(FileBrowserRowCorner))
            .mimirFocusable(authority, key, scope, autoBringIntoView = false)
            .pointerInput(key) { detectTapGestures(onTap = { controller.handleSearchTap(key) }) }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = fileBrowserBadgeLabel(filesResources(), tile),
            style = MimirTypeScale.Pill,
            color = if (tile.kind == FileBrowserTileKind.ARCHIVE) palette.emberHot else palette.textSecondary,
            maxLines = 1,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = fileBrowserDisplayName(tile),
                // F-S3: 13sp -> Body, the reading size. Sequenced BEHIND the width solve deliberately --
                // conformance alone would have made this column's truncation worse on the Nova, which is
                // why the spec's build order is binding.
                fontSize = MimirTypeStep.Body,
                fontWeight = FontWeight.SemiBold,
                color = palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                // The containing folder, not the whole path: the design's `friendly(x.p)` is the trail of
                // the DIRECTORY the hit sits in, and the hit's own name is already the line above. Drawn
                // through the SAME helper the inspector's Where row uses, so the two can never disagree
                // about how a path reads — including about the volume's friendly name.
                text = whereLabel,
                // F-S3: 10sp -> Label. This is reading copy (a path the player parses), not a counter,
                // so it conforms UP to Label rather than down to Micro -- Micro is reserved for
                // dense state, counters and identifiers use this tier; player-readable prose sits higher.
                fontSize = MimirTypeStep.Label,
                color = palette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────────── Scope card ──

/**
 * The design's Scope card (`Mimir Files.dc.html:226-231`) — the promise the player is owed about what
 * this feature does and does not do.
 *
 * The sentence is `files_search_scope_copy`, whose English is [FILE_SEARCH_SCOPE_COPY] word for word (`FilesSearchTextTest` pins it, so the sentence on screen and the
 * sentence the engine's own contract documents stay the same text — a paraphrase here could drift into a promise the engine does not keep).
 */
@Composable
private fun FileBrowserSearchScopeCard(palette: MimirThemePalette, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(palette.surfaceRaised)
                .padding(13.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = filesResources().getString(R.string.files_search_scope_eyebrow),
                style = MimirTypeScale.RunicEyebrow,
                color = palette.textMuted,
                maxLines = 1,
            )
            Text(
                text = filesResources().getString(R.string.files_search_scope_copy),
                // F-S3: 11.5sp -> SupportCompact, which IS Label. The same style the yielded seat below
                // uses, so the sentence reads identically whichever seat it is in.
                style = MimirTypeScale.SupportCompact,
                color = palette.textMuted,
            )
        }
    }
}

/**
 * **F-S1's yielded seat: the same sentence, full-width under the status line.**
 *
 * When [fileBrowserSearchScopeCardSeated] is false the card leaves the right column entirely and the
 * promise re-seats here. What moves is the SEAT, not the sentence — `files_search_scope_copy` is the same resource in both seats, and this file still
 * restates it nowhere.
 *
 * The card's "Scope" eyebrow does NOT come with it, and that is deliberate rather than an omission: an
 * eyebrow is a CARD's title, and inline under a status line it would be a label on nothing. Losing the
 * plate is the visible cost of the layout change — a static promise gives up its furniture before the feature's
 * primary control gives up its glyph.
 */
@Composable
private fun FileBrowserSearchScopeSentence(palette: MimirThemePalette, modifier: Modifier = Modifier) {
    Text(
        text = filesResources().getString(R.string.files_search_scope_copy),
        style = MimirTypeScale.SupportCompact,
        color = palette.textMuted,
        modifier = modifier,
    )
}

// ──────────────────────────────────────────────────────────────────── Keyboard ──

/**
 * The on-screen keyboard, on the sheet tier: [FileBrowserSurface] composes it inside a `MimirSheetHost` whose `visible` is
 * [FileBrowserController.searchKeyboardOpen], so it slides in
 * from the bottom edge and fades on the sheet tier's one clock, the way the Library's keyboard does, and it stays composed
 * for its exit.
 *
 * [MimirKeyboard]'s own `DisposableEffect(controller)` (`MimirKeyboard.kt:243-246`) drives the focus scope, and it drives
 * it from the FLIP, not from the end of the fade: opening it calls `binding.enter()` (push `MimirKeyboardFocusScope`, land
 * on the key the field starts on), and the frame [FileBrowserController.searchKeyboardOpen] turns false the binding is
 * null, so the keyboard is handed no controller and no decorator and the old controller's `onDispose` calls
 * `binding.exit()` (pop). The leaving keyboard therefore registers no key with the authority: there is one focus graph, the
 * search screen's, from that frame on, which is what the browser's history of a second live graph
 * (`FileBrowserSurfaceMotion.kt`, "What is deliberately NOT animated") asks of anything that outlives its scope. Touch is
 * the host's: the leaving sheet is inert. This is the same lifecycle every other keyboard host in this app gets from
 * `rememberMimirKeyboardHost`, obtained here from the same public parts rather than from that `internal` helper — see this
 * file's header.
 *
 * [FileBrowserController.attachSearchKeyboard] hands the binding down so `dispatch` can route to it, and
 * so `closeSearch` can `exit()` defensively when a host teardown disposes these effects in an order this
 * module does not control.
 */
@Composable
internal fun FileBrowserSearchKeyboard(
    controller: FileBrowserController,
    authority: FocusAuthority,
    modifier: Modifier = Modifier,
) {
    val binding = rememberMimirKeyboardFocusBinding(authority, active = controller.searchKeyboardOpen)
    DisposableEffect(controller, binding) {
        controller.attachSearchKeyboard(binding)
        onDispose { controller.attachSearchKeyboard(null) }
    }
    // No early return on a null binding (this composable used to return there, which was right while the keyboard was
    // mounted only while open): the sheet keeps this composed through its exit, and it must keep DRAWING while it leaves,
    // with nothing wired to it.
    val keyboardController = remember(binding) {
        binding?.let { open ->
            MimirKeyboardController(
                onEnter = { open.enter() },
                onEnterAt = { open.enter(it) },
                onExit = open::exit,
                onKeysChanged = open::reconcileFocus,
                onShoulderActionsChanged = open::registerShoulderActions,
                onRowLayoutChanged = open::registerRowLayout,
            )
        }
    }
    val keyDecorator: MimirKeyboardKeyDecorator? = remember(binding) {
        binding?.let { open ->
            val decorator: MimirKeyboardKeyDecorator = { id, activate ->
                val (keyModifier, focused) = open.decorateKey(id.value, activate)
                MimirKeyboardKeyDecoration(keyModifier, focused)
            }
            decorator
        }
    }
    MimirKeyboard(
        value = controller.searchQuery,
        onValueChange = controller::setSearchQuery,
        onDone = controller::closeSearchKeyboard,
        controller = keyboardController,
        keyDecorator = keyDecorator,
        targetLabel = filesResources().getString(R.string.files_search_keyboard_label),
        modifier = modifier,
    )
}

/** Reports whether a search provider was wired at all, so nothing about search is offered when one was
 * not — see [FileBrowserController.searchAvailable]. `SideEffect` rather than `DisposableEffect`: this is
 * a plain state mirror with no resource to release, and `close()` already ends the session. */
@Composable
internal fun FileBrowserSearchAvailability(controller: FileBrowserController, available: Boolean) {
    SideEffect { controller.setSearchAvailable(available) }
}
