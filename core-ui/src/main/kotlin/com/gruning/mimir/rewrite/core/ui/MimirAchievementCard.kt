package com.gruning.mimir.rewrite.core.ui

import android.animation.ValueAnimator
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import kotlin.math.roundToInt

/**
 * The RetroAchievements card — **a page of the game's own ledger**, not a service panel.
 *
 * ## Why it looks like this
 *
 * Mimir's identity is a bound Norse volume: the shelf holds coverless books, and
 * [MimirAchievementMedal]s are struck from that volume's own hardware. This card is the matching page.
 * Three fixed bands with one scrolling body:
 *
 * ```
 *   ACHIEVEMENTS                       0/43     ← head band  (fixed)
 *   Ace Combat Advance
 *   0 of 235 points
 *   ──────────────◆──────────────               ← binding rule
 *      ◉  Welded Woes              25           ← ledger body (scrolls)
 *         Complete Mission #3 on…
 *      ◉  Finale                   10
 *   ──────────────◆──────────────               ← binding rule
 *   Achievements unlock inside your emulator…   ← foot band  (fixed)
 * ```
 *
 * ## The material is the app's, not a new one
 *
 * The body is [MimirLiquidGlassPanel] — the ONE house glass that UXP-03/04 established for the Home Edit
 * menu and the Utility panel — so RA cannot drift into a third material. That panel washes the authored
 * [MimirHeroMaterial] (`coverTop`/`coverMiddle`/`coverBottom`) and inks with `hero.onCover`, which is a
 * **dark readable volume with light ink on all nine identities, including the two light ones**. The
 * previous recipe used `materials.controlTop`→`controlBottom` — the status-pill *control* gradient — which
 * on the dark themes fades to `0xB8080D12` and dissolved the lower two-thirds of the card into the shelf
 * behind it (device-observed 2026-07-19). Switching to the hero volume is
 * what makes this read as a tome and fixes the contrast collapse in one move.
 *
 * ## Contract
 *
 * **This card requires a bounded height.** The ledger body is `weight(1f, fill = false)`, so the foot band
 * is measured first and can never be squeezed out again; in an unbounded-height parent a weighted child
 * resolves to zero and the rows would vanish. Every production mount is a centred modal in a `fillMaxSize`
 * box, which is bounded.
 *
 * **No achievement text is discarded.** Row titles and descriptions carry no `maxLines` and no overflow
 * at all: a condition list cut at "…destroying at least 5 aircraft …" has had the actual requirement
 * deleted, and RA copy — especially localised — runs past any cap worth setting. Page length is the
 * bounded viewport's job, not the sentence's. The only capped string on the card is the GAME title in the
 * fixed head band, which is not achievement text; see its call site for why.
 *
 * ⚠️ HONESTY: Mimir is a launcher — it does not emulate, so it can never unlock an achievement. The
 * RA-enabled emulator does. Every string here is past/observational ("Unlocked", "Not yet"); nothing
 * promises Mimir will award, track live, or is required for achievements to work. [state] carries an
 * explicit unknown so an unresolved lookup never renders as a confident "0 of 0", and the foot band's
 * honesty line now renders in **every** state rather than only in [MimirAchievementCardState.Loaded].
 *
 * READ-ONLY by design, with one exception: nothing focusable, no [androidx.compose.foundation.focusable] and no
 * second focus writer. Scrolling a page is a scroll, not focus traversal, so FocusAuthority is untouched. The
 * exception (product review 2026-09-28, F3): when the card says NEEDS SETUP and the host passes [onOpenProfile],
 * the page shows one "Open Settings > Profile" pill. It is the card's only control, so it is always drawn focused
 * and nothing registers focus; A reaches it through the app's dispatchAchievementCard, touch through the pill.
 *
 * @param listState hoisted so the overlay dispatcher can drive it from the D-pad via [mimirLedgerStep].
 * @param reducedMotion snaps the continuation caps instead of easing them; defaults to the system setting.
 * @param onOpenProfile opens Settings on the RetroAchievements row. Null shows no action (previews, tests).
 */
@Composable
fun MimirAchievementCard(
    state: MimirAchievementCardState,
    modifier: Modifier = Modifier,
    palette: MimirThemePalette = LocalMimirPalette.current,
    platformIdValue: String? = null,
    listState: LazyListState = rememberLazyListState(),
    reducedMotion: Boolean = mimirReducedMotion(),
    onOpenProfile: (() -> Unit)? = null,
) {
    // The panel and every child read LocalMimirPalette, so an explicitly-passed palette has to become the
    // local one — otherwise the caller's palette and the panel's material could disagree.
    CompositionLocalProvider(LocalMimirPalette provides palette) {
        MimirLiquidGlassPanel(
            // Width is floored so a one-line state is not a narrow slip of a card, but height is NOT:
            // a min height padded every short state with dead space below its own footer, which read as
            // an unfinished page rather than a compact one. What actually keeps the states coherent is
            // that the head and foot bands are structurally identical in all of them.
            modifier = modifier
                .widthIn(min = MimirAchievementCardMinWidth, max = MimirAchievementCardMaxWidth),
            contentPadding = MimirSpacing.lg,
            verticalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
        ) {
            val pending = state is MimirAchievementCardState.Unknown &&
                mimirAchievementUnknownTone(state.reason) == MimirAchievementUnknownTone.PENDING

            LedgerHeadBand(state = state, palette = palette)
            MimirLedgerRule(palette = palette, pulse = pending, reducedMotion = reducedMotion)

            /**
 * The phase-varying body is one advancing surface: fade and resize share
 * `tween(OverlayMs, Easing)`. Bare `togetherWith` would introduce a default spring size clock.
 * Animate height rather than reserving a fixed minimum, which leaves dead space below short states.
 *
 * A departing page remains composed during its fade. The Unknown page's NEEDS SETUP action checks
 * that it is still the live page before acting; no other page has a callback.
 *
 * Only the body below is animated. [LedgerHeadBand] adds game-title and points lines for Loaded
 * outside AnimatedContent, so Unknown/NotMatched -> Loaded still hard-cuts the head-band height
 * on the same commit frame. Whether that band should join the animated region remains unresolved.
 *
 * `Modifier.weight(1f, fill = false)` belongs on this AnimatedContent, the direct child of the
 * panel's outer ColumnScope, rather than LedgerBody's inner Box. AnimatedContent's content lambda
 * has an AnimatedContentScope receiver and cannot reach that ColumnScope. All three pages share
 * this placement: non-weighted head band, rules and foot line measure first; the weighted slot
 * takes the remaining height without forced expansion. Unbounded-height misuse still yields
 * zero-height rows rather than an unbounded card.
 *
 * LAYOUT EVIDENCE LIMIT: this follows the documented Column-weight/AnimatedContent measurement
 * policy; it is not a device or Robolectric pixel observation. A device/screenshot check of bounded
 * long lists and compact short lists remains outstanding.
 */
            AnimatedContent(
                targetState = state,
                contentKey = ::mimirAchievementCardPageKey,
                modifier = Modifier.weight(1f, fill = false),
                contentAlignment = Alignment.TopStart,
                transitionSpec = {
                    (fadeIn(tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing)) togetherWith
                        fadeOut(tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing)))
                        .using(
                            SizeTransform(clip = false) { _, _ ->
                                tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing)
                            },
                        )
                },
                label = "achievement-card-phase",
            ) { page ->
                when (page) {
                    is MimirAchievementCardState.Unknown -> Column(
                        verticalArrangement = Arrangement.spacedBy(MimirSpacing.md),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(MimirSpacing.xs)) {
                            LedgerStatement(
                                text = page.reason,
                                palette = palette,
                                emphasis = mimirAchievementUnknownTone(page.reason) ==
                                    MimirAchievementUnknownTone.ACTION_NEEDED,
                            )
                            if (page.reason == MimirAchievementCopy.NothingCheckedYet) {
                                Text(
                                    text = MimirAchievementCopy.CheckGamesToLoad,
                                    style = LedgerSupportStyle,
                                    color = palette.materials.hero.onCover.copy(alpha = InkSupport),
                                )
                            }
                        }
                        if (onOpenProfile != null && mimirAchievementCardOffersProfileSetup(page)) {
                            LedgerSetupAction(
                                palette = palette,
                                onOpen = { if (page == state) onOpenProfile() },
                            )
                        }
                    }

                    is MimirAchievementCardState.NotMatched -> Column(
                        verticalArrangement = Arrangement.spacedBy(MimirSpacing.xs),
                    ) {
                        LedgerStatement(
                            text = MimirAchievementCopy.NoSetMatchedHeadline,
                            palette = palette,
                            emphasis = false,
                        )
                        Text(
                            text = page.detail,
                            style = LedgerSupportStyle,
                            color = palette.materials.hero.onCover.copy(alpha = InkSupport),
                        )
                    }

                    is MimirAchievementCardState.Loaded -> if (page.rows.isEmpty()) {
                        // Defensive: the app path folds an empty set into PROGRESS_UNAVAILABLE, so this is
                        // unreachable today. It must still be a sentence rather than a void page.
                        LedgerStatement(
                            text = page.emptyLine,
                            palette = palette,
                            emphasis = false,
                        )
                    } else {
                        LedgerBody(
                            rows = page.rows,
                            listState = listState,
                            palette = palette,
                            platformIdValue = platformIdValue,
                            reducedMotion = reducedMotion,
                        )
                    }
                }
            }

            MimirLedgerRule(palette = palette, pulse = false, reducedMotion = reducedMotion)
            Text(
                text = MimirAchievementCopy.EarnedInEmulator,
                style = LedgerSupportStyle,
                color = palette.materials.hero.onCover.copy(alpha = InkSupport),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// BANDS
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * Eyebrow + title + points on the left, and on the right the ONE slot that always carries the truth:
 * either the real `unlocked/total` count or a status word. Same slot in every state, so the reader's eye
 * never has to hunt for "what is this card telling me".
 *
 * The LedgerHeadBand seam: this band renders TWO
 * dialects of content and only ONE of them may join the animated region. [MimirAchievementCopy.CardEyebrow]
 * is phase-invariant chrome, drawn identically in every state — it stays a plain [Text], outside any
 * `AnimatedContent`, so it never crossfades with itself on a phase change (§5 — "The frame persists. Only
 * the page inside it changes."). The game title and points exist ONLY for [MimirAchievementCardState.Loaded]
 * — that conditional part is what joins the animated region, on the SAME one-clock grammar the card body's
 * own `AnimatedContent` already uses (`MimirAchievementCard`, above): fade in, fade out and resize all on
 * `tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing)`, an explicit `SizeTransform(clip = false)` so
 * the bare `togetherWith` form's default SPRING size clock (spec §5c) never gets a chance to race the tween.
 *
 * **Content key: PRESENT/ABSENT (has-title vs no-title), not per-title.** The title only exists for
 * `Loaded`, so that boundary is the whole story here — UNLIKE the card body's own
 * [mimirAchievementCardPageKey], this key does not need a third discrimination for "which Loaded" because
 * no two DIFFERENT Loaded games are ever presented inside one mount of this composable to transition
 * between. Traced, not merely inferred from the modal's shape: `MimirAchievementsCoordinator.openCard`/
 * `openCardFromHome` are only reachable from `dispatchMimirInput`'s library/home dispatch arms
 * (`MimirAppInputDispatch.kt`, `onOpenAchievements =` / `onOpenAchievements = {` wiring), and that function
 * short-circuits BEFORE ever reaching those arms whenever any overlay is active — `bindings.overlays
 * .dispatch(action)?.let { return it }` (`MimirAppInputDispatch.kt:286`) returns non-null (and so returns
 * early) exactly when `top != null`, since `MimirOverlayCoordinator.dispatch` is `top?.let { dispatchTop(it,
 * action) }` (`MimirOverlayCoordinator.kt:226`) — null only when no overlay is showing. So while this card's
 * own overlay (`GAMELIST_ACHIEVEMENTS`) is `top`, `openCard`/`openCardFromHome` are provably unreachable;
 * they only fire again after `dismissCard()` has cleared `openForGameId` and the overlay has closed. A
 * genuinely new game therefore always re-enters through a fresh mount, starting at `Unknown`, never a live
 * Loaded-to-Loaded jump this key would need to catch. Simplest correct thing for the transition set that
 * actually exists; if that dispatch gating ever changes to allow swapping games without a remount, this key
 * must widen to discriminate on `gameTitle` too.
 */
@Composable
private fun LedgerHeadBand(state: MimirAchievementCardState, palette: MimirThemePalette) {
    val hero = palette.materials.hero
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        // NOT spacedBy(xs) at THIS Column's own level, deliberately: this Column always has exactly two
        // children now (the eyebrow, then the AnimatedContent below) -- unlike the pre-pass-two version,
        // where the title/points Texts were only composed at all when Loaded, so an Unknown/NotMatched
        // card had exactly ONE child and spacedBy inserted nothing. AnimatedContent is a real layout node
        // even when its own content lambda composes nothing for the current page (zero height, but still
        // a child); spacedBy(xs) does NOT collapse its gap for a zero-size neighbour, so leaving it here
        // would permanently pad every Unknown/NotMatched card with a dead xs gap under the eyebrow -- the
        // exact "dead space below its own footer" failure this file's own panel comment (above,
        // `MimirAchievementCard`) already rejected on visible grounds, just relocated one band up.
        // The gap moves INSIDE the Loaded branch instead (`Modifier.padding(top = MimirSpacing.xs)` on the
        // title/points Column below), so it only exists when there is a title to gap against, and rides
        // the SAME size clock as the title's own fade/resize rather than being a static outer-Column gap.
        Column(Modifier.weight(1f)) {
            Text(
                text = MimirAchievementCopy.CardEyebrow,
                style = MimirTypeScale.RunicEyebrow,
                color = palette.accent,
            )
            AnimatedContent(
                targetState = state,
                contentKey = ::mimirLedgerHeadBandTitleKey,
                contentAlignment = Alignment.TopStart,
                transitionSpec = {
                    (fadeIn(tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing)) togetherWith
                        fadeOut(tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing)))
                        .using(
                            SizeTransform(clip = false) { _, _ ->
                                tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing)
                            },
                        )
                },
                label = "achievement-card-headband-title",
            ) { page ->
                if (page is MimirAchievementCardState.Loaded) {
                    Column(
                        modifier = Modifier.padding(top = MimirSpacing.xs),
                        verticalArrangement = Arrangement.spacedBy(MimirSpacing.xs),
                    ) {
                        Text(
                            text = page.gameTitle,
                            style = LedgerTitleStyle,
                            color = hero.onCover,
                            // The ONE deliberate cap on this card, and it is not achievement text: this is
                            // the GAME's name, in fixed head-band chrome, and the reader already knows
                            // which game they opened. Letting it wrap without limit would let a
                            // pathological title push the ledger and the foot band off a small viewport —
                            // the exact failure this redesign fixes. Two lines, not one, so a long
                            // localised title still wraps rather than losing its second half. Achievement
                            // titles and descriptions below are UNCAPPED. UNCHANGED by the pass-two move —
                            // still exactly this cap, still exactly this reasoning.
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (page.pointsKnown) {
                            Text(
                                text = mimirAchievementPointsLabel(page.earnedPoints, page.totalPoints),
                                style = LedgerSupportStyle,
                                color = hero.onCover.copy(alpha = InkSupport),
                            )
                        }
                    }
                }
            }
        }
        val status = mimirAchievementStatusLabel(state)
        if (status != null) {
            Text(
                text = status.text,
                style = MimirTypeScale.MicroStatus,
                color = if (status.isCount || status.isActionable) palette.accent else hero.onCover.copy(alpha = InkSecondary),
                maxLines = 1,
            )
        }
    }
}

/** A statement of fact — the body for every state that has no rows to list. */
@Composable
private fun LedgerStatement(text: String, palette: MimirThemePalette, emphasis: Boolean) {
    val hero = palette.materials.hero
    Text(
        text = text,
        style = LedgerStatementStyle,
        // Emphasis is reserved for the states the user can actually act on (credentials, opt-in). A
        // brighter ink on "couldn't reach RetroAchievements" would be shouting about something they
        // cannot fix from here.
        color = if (emphasis) hero.onCover else hero.onCover.copy(alpha = InkSecondary),
    )
}

/**
 * True exactly when the head band says NEEDS SETUP (no credentials, connected services off, key rejected): the
 * states the reader fixes in Settings > Profile. Product review 2026-09-28 (F3).
 */
fun mimirAchievementCardOffersProfileSetup(state: MimirAchievementCardState): Boolean =
    mimirAchievementStatusLabel(state)?.isActionable == true

/** The setup pill's ring identity: one card, one action, no index. */
private const val MimirAchievementSetupActionIdentity = "achievements/open-profile-settings"

/**
 * The card's only control (F3). Always drawn focused with the one house ring, because there is nothing else on the
 * card for focus to be on; no scale. Touch is a raw tap plus one accessibility click, the same shape as
 * core-input's mimirActivatable (core-ui cannot depend on core-input), so no Compose focus node is planted.
 */
@Composable
private fun LedgerSetupAction(palette: MimirThemePalette, onOpen: () -> Unit) {
    val open by rememberUpdatedState(onOpen)
    Box(
        modifier = Modifier
            // The ring reaches MimirFocusTileRingReach (14 dp) past the pill. This air plus the panel's own gap keeps
            // it off the binding rule below; the panel's lg padding covers the start edge.
            .padding(vertical = MimirSpacing.sm)
            .mimirSelectionRing(
                focused = true,
                identity = MimirAchievementSetupActionIdentity,
                shape = MimirRingShape.Pill,
                flare = true,
            )
            .clip(RoundedCornerShape(MimirSpacing.radiusPill))
            .pointerInput(Unit) { detectTapGestures(onTap = { open() }) }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                onClick(label = MimirAchievementCopy.OpenProfileSettings) {
                    open()
                    true
                }
            }
            .padding(horizontal = MimirSpacing.lg, vertical = MimirSpacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        MimirChromeText(
            text = MimirAchievementCopy.OpenProfileSettings,
            style = MimirTypeScale.Pill,
            color = palette.materials.hero.onCover,
            maxLines = 1,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// LEDGER BODY + CONTINUATION
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * The scrolling page, with two truthful continuation signals — both read from the REAL [LazyListState],
 * never from a guess about how many rows "probably" fit:
 *
 *  - **Page-edge caps.** A wash of the volume's own `coverBottom` at whichever edge has content beyond
 *    it: top only when [LazyListState.canScrollBackward], bottom only when
 *    [LazyListState.canScrollForward]. A list that fits shows neither, so a flat card is an honest
 *    statement that there is nothing more.
 *  - **Edge index.** A proportional rail on the right, present only while the list actually scrolls.
 *
 * Both animate alpha ONLY (compositor-safe, invariant #7) — a scroll never re-lays-out the chrome.
 *
 * Callable from a plain `@Composable` context, NOT a `ColumnScope` extension: [MimirAchievementCard] now invokes this from inside an `AnimatedContent`
 * page lambda (`AnimatedContentScope`, which does not expose `ColumnScope`), so the `weight(1f, fill =
 * false)` that used to sit on this function's own [Box] moved one level out, onto that `AnimatedContent`
 * itself. This [Box] carries no size modifier at all — it wraps to the [LazyColumn]'s own content-bounded
 * size, exactly like before, now inheriting its outer bound from the already-weighted, already
 * fill=false-shrinkwrapped `AnimatedContent` ancestor instead of finding a `ColumnScope` of its own.
 */
@Composable
private fun LedgerBody(
    rows: List<MimirAchievementRow>,
    listState: LazyListState,
    palette: MimirThemePalette,
    platformIdValue: String?,
    reducedMotion: Boolean,
) {
    val rail by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            mimirLedgerRail(
                totalItems = info.totalItemsCount,
                firstVisibleIndex = listState.firstVisibleItemIndex,
                visibleItemCount = info.visibleItemsInfo.size,
            )
        }
    }
    Box(Modifier.testTag(MimirAchievementLedgerTestTag)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(MimirSpacing.xs),
            contentPadding = PaddingValues(
                // Breathing room under the caps so the first and last rows are never pinned to a hard edge.
                top = MimirSpacing.sm,
                bottom = MimirSpacing.sm,
                // The rail draws over the rows rather than beside them (it is matchParentSize, so it
                // costs no layout), so the gutter has to be reserved here or the points column runs
                // underneath it. Reserved ALWAYS, including on a page that does not scroll: the rows
                // must not shift sideways the moment a rail appears.
                end = LedgerRailGutter,
            ),
        ) {
            items(rows, key = { it.id }) { row ->
                LedgerRow(row = row, palette = palette, platformIdValue = platformIdValue)
            }
        }
        // Every scroll-derived read below is DEFERRED into the child that needs it — a lambda for the
        // caps, the State itself for the rail. Reading them here instead would recompose this scope, and
        // this scope owns the LazyColumn: the rail's offset changes on every scroll frame, so a direct
        // read would re-run the list's composable once per frame for a 3dp indicator.
        LedgerCap(
            visible = { listState.canScrollBackward },
            top = true,
            palette = palette,
            reducedMotion = reducedMotion,
        )
        LedgerCap(
            visible = { listState.canScrollForward },
            top = false,
            palette = palette,
            reducedMotion = reducedMotion,
        )
        LedgerEdgeIndex(rail = { rail }, palette = palette)
    }
}

/** One achievement: medal, title, description, points. Top-aligned so long copy grows DOWN. */
@Composable
private fun LedgerRow(row: MimirAchievementRow, palette: MimirThemePalette, platformIdValue: String?) {
    val hero = palette.materials.hero
    val locked = row.medal == MimirMedalState.LOCKED
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
        // Top, not Centre: a tall wrapped description must not drag the medal down the page with it.
        verticalAlignment = Alignment.Top,
    ) {
        MimirAchievementMedal(
            state = row.medal,
            modifier = Modifier.padding(top = MedalOpticalTop),
            platformIdValue = platformIdValue,
            palette = palette,
        )
        // NO-LOSS: neither of these carries a maxLines or an overflow. An achievement's title and its
        // description are the achievement — a condition list truncated at "…destroying at least 5
        // aircraft …" has had the actual requirement removed, which is worse than a longer row. Raising
        // the caps (1→2 and 2→3) was not enough: RA descriptions and their localisations run past any
        // cap worth setting, so there is no cap. Page length is the BOUNDED VIEWPORT's job — the ledger
        // body is weighted and scrolls — not the text's.
        Column(Modifier.weight(1f)) {
            Text(
                text = row.title,
                style = LedgerRowTitleStyle,
                color = if (locked) hero.onCover.copy(alpha = InkLockedTitle) else hero.onCover,
            )
            Text(
                text = row.description,
                style = LedgerRowBodyStyle,
                color = hero.onCover.copy(alpha = if (locked) InkLockedBody else InkSecondary),
            )
        }
        Text(
            text = "${row.points}",
            style = LedgerRowPointsStyle,
            color = hero.onCover.copy(alpha = if (locked) InkLockedBody else InkSecondary),
            maxLines = 1,
            textAlign = TextAlign.End,
            // A right-aligned column so the points stack like a ledger's figures rather than ragging
            // along the end of whatever description precedes them. A MINIMUM, not a fixed width: a
            // four-figure score has to widen the column, not get cut off by it.
            modifier = Modifier.widthIn(min = PointsColumnWidth).padding(top = MedalOpticalTop),
        )
    }
}

/** The page-edge wash. Absent, not merely faint, when there is nothing beyond that edge. */
@Composable
private fun BoxScope.LedgerCap(
    visible: () -> Boolean,
    top: Boolean,
    palette: MimirThemePalette,
    reducedMotion: Boolean,
) {
    val capAlpha = animateFloatAsState(
        targetValue = if (visible()) 1f else 0f,
        animationSpec = if (reducedMotion) {
            snap()
        } else {
            tween(MimirMotion.FocusMs, easing = MimirMotion.Easing)
        },
        label = if (top) "raLedgerCapTop" else "raLedgerCapBottom",
    )
    // Composition sees only whether the cap is present at all (audit 2026-10-14, the P3 draw pass): the fade itself is read in the layer below, so a cap
    // that is fading recomposes at its two ends and never per frame.
    val present by remember { derivedStateOf { capAlpha.value > CapInvisibleThreshold } }
    if (!present) return
    val edge = palette.materials.hero.coverBottom.copy(alpha = CapEdgeAlpha)
    val stops = if (top) listOf(edge, Color.Transparent) else listOf(Color.Transparent, edge)
    Box(
        Modifier
            .align(if (top) Alignment.TopCenter else Alignment.BottomCenter)
            .fillMaxWidth()
            .height(LedgerCapHeight)
            .graphicsLayer { alpha = capAlpha.value }
            .background(Brush.verticalGradient(stops)),
    )
}

/**
 * The proportional position rail. Draws nothing when [mimirLedgerRail] says the page already fits.
 *
 * The node is composed unconditionally and the emptiness decided in the DRAW: a page that becomes
 * scrollable (rows arriving, a rotation) then costs a repaint rather than an insertion, so the ledger's
 * geometry never shifts underneath a reader mid-scroll.
 */
@Composable
private fun BoxScope.LedgerEdgeIndex(rail: () -> MimirLedgerRail?, palette: MimirThemePalette) {
    val hero = palette.materials.hero
    // matchParentSize, NOT fillMaxHeight: a fillMaxHeight child EXPANDS the Box to the full weighted
    // share, which padded a short set's page with dead space below its last row. matchParentSize is
    // explicitly excluded from the Box's own sizing, so the ledger stays as tall as its rows.
    Canvas(Modifier.matchParentSize()) {
        val metrics = rail() ?: return@Canvas
        val width = LedgerRailWidth.toPx()
        val inset = MimirSpacing.sm.toPx()
        val trackTop = inset
        val trackHeight = (size.height - inset * 2f).coerceAtLeast(0f)
        val left = size.width - width
        val radius = width / 2f
        drawRoundRect(
            color = hero.onCover.copy(alpha = RailTroughAlpha),
            topLeft = Offset(left, trackTop),
            size = Size(width, trackHeight),
            cornerRadius = CornerRadius(radius, radius),
        )
        drawRoundRect(
            color = palette.accent.copy(alpha = RailThumbAlpha),
            topLeft = Offset(left, trackTop + trackHeight * metrics.offsetFraction),
            size = Size(width, trackHeight * metrics.thumbFraction),
            cornerRadius = CornerRadius(radius, radius),
        )
    }
}

/**
 * The book's sewn head-band: a hairline with a struck node at its centre. The node is drawn in
 * [MimirHeroMaterial.hardware] — the same metal as the medals' clasps — so the page and its medals are
 * visibly one object.
 *
 * When [pulse] is set (and motion is allowed) a low-alpha accent sweep travels the rule. That is the
 * card's ENTIRE loading treatment: no spinner, and deliberately no skeleton rows, because skeleton rows
 * would imply Mimir already knows how many achievements this game has.
 */
@Composable
private fun MimirLedgerRule(palette: MimirThemePalette, pulse: Boolean, reducedMotion: Boolean) {
    val hero = palette.materials.hero
    val sweep = if (pulse && !reducedMotion) {
        val transition = rememberInfiniteTransition(label = "raLedgerRulePulse")
        transition.animateFloat(
            initialValue = -SweepHalfWidth,
            targetValue = 1f + SweepHalfWidth,
            animationSpec = infiniteRepeatable(
                animation = tween(LedgerPulseMs, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "raLedgerRuleSweep",
        ).value
    } else {
        null
    }
    Canvas(Modifier.fillMaxWidth().height(LedgerRuleHeight)) {
        val y = size.height / 2f
        drawLine(
            color = hero.onCover.copy(alpha = RuleAlpha),
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = MimirSpacing.hairlineWidth.toPx(),
        )
        val node = LedgerNodeRadius.toPx()
        val centre = Offset(size.width / 2f, y)
        drawPath(
            path = Path().apply {
                moveTo(centre.x, centre.y - node)
                lineTo(centre.x + node, centre.y)
                lineTo(centre.x, centre.y + node)
                lineTo(centre.x - node, centre.y)
                close()
            },
            color = hero.hardware.copy(alpha = NodeAlpha),
        )
        if (sweep != null) {
            val half = size.width * SweepHalfWidth
            val x = size.width * sweep
            drawLine(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, palette.accent.copy(alpha = SweepAlpha), Color.Transparent),
                    startX = x - half,
                    endX = x + half,
                ),
                start = Offset((x - half).coerceAtLeast(0f), y),
                end = Offset((x + half).coerceAtMost(size.width), y),
                strokeWidth = MimirSpacing.focusRingWidth.toPx(),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// CONTROLLER SEAM — a scroll, never a focus move
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * How far one D-pad press moves the ledger: most of a viewport, minus an overlap band so the reader keeps
 * the row they were looking at and never loses their place across a step.
 *
 * Pure so the step is pinned by a JVM test rather than felt for.
 */
fun mimirAchievementScrollStep(viewportHeightPx: Int): Int =
    (viewportHeightPx * LedgerStepFraction).roundToInt().coerceAtLeast(1)

/**
 * The whole controller seam, in one call. The overlay dispatcher owns the [LazyListState] and calls this
 * for D-pad up/down; nothing here focuses, and nothing here is focusable.
 *
 * The app overlay owns the request epoch and invokes this function from its hoisted [LazyListState].
 */
suspend fun LazyListState.mimirLedgerStep(forward: Boolean, reducedMotion: Boolean = false) {
    val step = mimirAchievementScrollStep(layoutInfo.viewportSize.height).toFloat()
    val delta = if (forward) step else -step
    if (reducedMotion) scrollBy(delta) else animateScrollBy(delta)
}

/**
 * The scrolling ledger body. Tagged because the continuation caps are a DRAW, not a node — the only
 * honest way to assert "the top edge darkens once there is content above it" is to capture this exact
 * region's pixels before and after a scroll.
 */
const val MimirAchievementLedgerTestTag: String = "mimir_achievement_ledger"

/** Position of the ledger's edge index, as fractions of the track. */
data class MimirLedgerRail(
    /** Thumb height as a fraction of the track. */
    val thumbFraction: Float,
    /** Thumb top as a fraction of the track. */
    val offsetFraction: Float,
)

/**
 * The edge index, by item index rather than by pixel.
 *
 * Deliberately an approximation, and the KDoc says so rather than the code implying otherwise: a
 * `LazyColumn` cannot report the pixel height of rows it has never measured, so a pixel-exact rail would
 * have to invent the sizes of the rows below the fold. Index-space is the honest measure — "you are around
 * item 12 of 43" — and it is exact at both ends of the travel.
 *
 * Returns null when everything already fits, so a non-scrolling page draws no rail at all.
 */
fun mimirLedgerRail(totalItems: Int, firstVisibleIndex: Int, visibleItemCount: Int): MimirLedgerRail? {
    if (totalItems <= 0 || visibleItemCount <= 0 || visibleItemCount >= totalItems) return null
    val thumb = (visibleItemCount.toFloat() / totalItems).coerceIn(MinThumbFraction, 1f)
    val lastStart = (totalItems - visibleItemCount).toFloat()
    val progress = if (lastStart <= 0f) 0f else (firstVisibleIndex / lastStart).coerceIn(0f, 1f)
    return MimirLedgerRail(thumbFraction = thumb, offsetFraction = progress * (1f - thumb))
}

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// STATE PROJECTION
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/** What the card can honestly show. Unknown is a first-class state, never rendered as an empty Loaded. */
sealed interface MimirAchievementCardState {
    /** The lookup has not resolved — opted out, no credentials, offline, or the request failed. */
    data class Unknown(val reason: String) : MimirAchievementCardState

    /**
     * RA answered a real file search and carries no achievement set for it. A system Mimir cannot hash must
     * never land here — it resolves to [Unknown] instead, because this state renders "No achievement set
     * matched this file", a claim about RA's COVERAGE that cannot honestly be made when nothing was hashed.
     */
    data class NotMatched(val detail: String) : MimirAchievementCardState

    /**
     * @param pointsKnown false when the caller has no honest points total for the set (the Bottom's last-checked snapshot carries earned rows only): the head band then
     *   omits its "N of M points" line instead of printing a "0 of 0". Defaults to true, so every existing caller (Main's) is unchanged, the card draws exactly what it
     *   did, and [earnedPoints] and [totalPoints] are read only when it is true.
     * @param emptyLine the one line a page with no rows says. Defaults to [MimirAchievementCopy.SetListedNothing] ("this set didn't list any achievements"), which is
     *   Main's, unchanged. A caller whose rows are EARNED ones only (the Bottom) passes [MimirAchievementCopy.NoneEarnedYet] for a set that has achievements and none
     *   earned, so the card never claims a set is empty when it only has nothing earned in it.
     */
    data class Loaded(
        val gameTitle: String,
        val unlocked: Int,
        val total: Int,
        val earnedPoints: Int,
        val totalPoints: Int,
        val rows: List<MimirAchievementRow>,
        val pointsKnown: Boolean = true,
        val emptyLine: String = MimirAchievementCopy.SetListedNothing,
    ) : MimirAchievementCardState
}

/**
 * Stable page identity for [MimirAchievementCardState]. One arm per sealed subclass — [UNKNOWN],
 * [NOT_MATCHED], [LOADED] — exhaustive so a new [MimirAchievementCardState] cannot be added without
 * choosing its transition key.
 *
 * **Deliberate transition boundary:** [MimirAchievementCardState.Unknown]'s `reason` carries FOUR distinct tones via
 * [mimirAchievementUnknownTone] — PENDING, UNAVAILABLE, ACTION_NEEDED, UNSUPPORTED — and this key does NOT discriminate on
 * tone/reason. `Unknown("Checking RetroAchievements…")` → `Unknown("Couldn't reach RetroAchievements just
 * now.")` is therefore a HARD CUT: the text (and, for ACTION_NEEDED, the ink alpha) swaps in place inside
 * the SAME `AnimatedContent` entry, no fade/resize.
 *
 * Two reasons this is the correct call, not a shortcut:
 *  1. **It is pass one's own collapsing criterion, applied consistently.** Every Unknown tone renders
 *     through the IDENTICAL shape — one [LedgerStatement], no buttons; only the string and (for
 *     ACTION_NEEDED alone) the ink alpha change. That is "same shape, same buttons, no new transition",
 *     word for word the reasoning `ArtworkFetchOverlay.kt` gives for collapsing `Done(writeBlocked = true)`
 *     into `DONE` rather than splitting it into a second page.
 *  2. **The alternative is the exact strobe failure §5b's `contentKey` requirement exists to prevent.**
 *     `reason` is not fixed at construction the way `Done.writeBlocked` is — a live connectivity re-check
 *     can walk PENDING → UNAVAILABLE → PENDING again in a retry loop. Keying on tone would replay the fade
 *     on every one of those flips, which is a strobe on a slow connection, not a fix — the SAME failure
 *     mode `ArtworkFetchUi.Running`'s single RUNNING key (`ArtworkFetchOverlay.kt`) was written to avoid
 *     for a progress tick.
 *
 * **The tone change still registers visibly, just not as a page transition**: [MimirLedgerRule]'s pulse
 * sweep (driven by `pending`, computed from [mimirAchievementUnknownTone] directly, OUTSIDE this
 * `AnimatedContent`) starts and stops on exactly the PENDING boundary this key intentionally does not
 * animate, so leaving PENDING is not silent — the rule's own motion marks it.
 */
internal enum class MimirAchievementCardPageKey { UNKNOWN, NOT_MATCHED, LOADED }

internal fun mimirAchievementCardPageKey(state: MimirAchievementCardState): MimirAchievementCardPageKey =
    when (state) {
        is MimirAchievementCardState.Unknown -> MimirAchievementCardPageKey.UNKNOWN
        is MimirAchievementCardState.NotMatched -> MimirAchievementCardPageKey.NOT_MATCHED
        is MimirAchievementCardState.Loaded -> MimirAchievementCardPageKey.LOADED
    }

/**
 * Stable content identity for [LedgerHeadBand]'s conditional title/points slot. See that function's own
 * KDoc for why PRESENT/ABSENT — has-title vs no-title — is the whole boundary and not a third,
 * per-`gameTitle` discrimination.
 */
internal enum class MimirLedgerHeadBandTitleKey { PRESENT, ABSENT }

internal fun mimirLedgerHeadBandTitleKey(state: MimirAchievementCardState): MimirLedgerHeadBandTitleKey =
    if (state is MimirAchievementCardState.Loaded) {
        MimirLedgerHeadBandTitleKey.PRESENT
    } else {
        MimirLedgerHeadBandTitleKey.ABSENT
    }

data class MimirAchievementRow(
    val id: Int,
    val title: String,
    val description: String,
    val points: Int,
    val medal: MimirMedalState,
)

/**
 * How an [MimirAchievementCardState.Unknown] should READ. The four tones answer the only question the
 * reader actually has: does this resolve on its own, can I fix it, or is it simply how things are?
 */
enum class MimirAchievementUnknownTone {
    /** Mimir is looking right now. The only state that animates. */
    PENDING,

    /** Something outside the device did not answer. Nothing for the reader to do but come back. */
    UNAVAILABLE,

    /** The reader can fix this from Settings. */
    ACTION_NEEDED,

    /** A settled statement of scope, not a failure. */
    UNSUPPORTED,
}

/**
 * Projects an [MimirAchievementCardState.Unknown]'s prose back into a tone.
 *
 * The app collapses ten typed reasons into one string at the `core-ui` boundary, so the type is gone by
 * the time it reaches this card. Matching against the canonical constants in [MimirAchievementCopy] — both
 * sides' single source for these strings — recovers it without inventing a parallel contract, and
 * `MimirAchievementSurfaceTest` fails if a copy edit ever orphans a constant from its tone.
 *
 * Anything unrecognised falls back to [MimirAchievementUnknownTone.UNAVAILABLE] — never PENDING, so an
 * unknown string can never put a live "checking" animation on a card that is not checking anything.
 */
fun mimirAchievementUnknownTone(reason: String): MimirAchievementUnknownTone = when (reason) {
    MimirAchievementCopy.Checking, MimirAchievementCopy.ConnectValidating ->
        MimirAchievementUnknownTone.PENDING

    MimirAchievementCopy.NoCredentials,
    MimirAchievementCopy.CredentialsRejected,
    MimirAchievementCopy.OptedOut,
    -> MimirAchievementUnknownTone.ACTION_NEEDED

    // ConsoleUnmapped is a settled statement of scope like the other two — a gap in Mimir's mapping, not
    // a failure the reader can retry into working.
    MimirAchievementCopy.SystemUnsupported,
    MimirAchievementCopy.IdentificationUnavailable,
    MimirAchievementCopy.TooLarge,
    MimirAchievementCopy.ConsoleUnmapped,
    -> MimirAchievementUnknownTone.UNSUPPORTED

    // Incomplete provider data is the provider not answering properly — the same tone as an unreachable
    // service, not an action for the reader and not a claim about the file.
    MimirAchievementCopy.ProviderDataIncomplete -> MimirAchievementUnknownTone.UNAVAILABLE

    else -> MimirAchievementUnknownTone.UNAVAILABLE
}

/** The head band's right-hand slot: the real count, or the word for why there isn't one. */
data class MimirAchievementStatus(val text: String, val isCount: Boolean, val isActionable: Boolean)

/**
 * One slot, one truth. A resolved set shows its real `unlocked/total`; everything else shows the status
 * word for its tone. A [MimirAchievementCardState.Loaded] whose counts do not form an honest label (an
 * unresolved or zero total) shows nothing at all rather than a manufactured "0/0".
 */
fun mimirAchievementStatusLabel(state: MimirAchievementCardState): MimirAchievementStatus? = when (state) {
    is MimirAchievementCardState.Loaded ->
        mimirAchievementProgressLabel(state.unlocked, state.total)
            ?.let { MimirAchievementStatus(it, isCount = true, isActionable = false) }

    is MimirAchievementCardState.NotMatched ->
        MimirAchievementStatus(MimirAchievementCopy.StatusNoSet, isCount = false, isActionable = false)

    is MimirAchievementCardState.Unknown -> when (state.reason) {
        MimirAchievementCopy.IdentificationUnavailable ->
            MimirAchievementStatus(MimirAchievementCopy.StatusFormatNotReady, isCount = false, isActionable = false)

        MimirAchievementCopy.TooLarge ->
            MimirAchievementStatus(MimirAchievementCopy.StatusFileTooLarge, isCount = false, isActionable = false)

        MimirAchievementCopy.ConsoleUnmapped ->
            MimirAchievementStatus(MimirAchievementCopy.StatusSystemUnmapped, isCount = false, isActionable = false)

        // Nothing is unavailable and nothing is wrong: no game has been checked yet, so the slot says nothing.
        MimirAchievementCopy.NothingCheckedYet -> null

        else -> when (mimirAchievementUnknownTone(state.reason)) {
            MimirAchievementUnknownTone.PENDING ->
                MimirAchievementStatus(MimirAchievementCopy.StatusChecking, isCount = false, isActionable = false)

            MimirAchievementUnknownTone.UNAVAILABLE ->
                MimirAchievementStatus(MimirAchievementCopy.StatusUnavailable, isCount = false, isActionable = false)

            MimirAchievementUnknownTone.ACTION_NEEDED ->
                MimirAchievementStatus(MimirAchievementCopy.StatusNeedsSetup, isCount = false, isActionable = true)

            MimirAchievementUnknownTone.UNSUPPORTED ->
                MimirAchievementStatus(MimirAchievementCopy.StatusUnsupported, isCount = false, isActionable = false)
        }
    }
}

/** User-facing RA strings, kept together so the honesty wording is reviewable in one place. */
object MimirAchievementCopy {
    /**
     * The load-bearing line. Mimir shows progress; the emulator is what actually unlocks. Without this a
     * user could reasonably assume turning RA on in Mimir makes achievements work.
     */
    const val EarnedInEmulator: String =
        "Achievements unlock inside your emulator, not in Mimir — Mimir shows the progress."

    const val CardEyebrow: String = "ACHIEVEMENTS"

    const val NoSetMatchedHeadline: String = "No achievement set matched this file."

    /** A resolved set that listed nothing. Defensive — the app folds this into ProgressUnavailable. */
    const val SetListedNothing: String = "This set didn’t list any achievements."

    /** A set that has achievements, none of them earned: what an earned-rows-only page says when its rows are empty. Opt-in through `Loaded.emptyLine`; Main never passes it. */
    const val NoneEarnedYet: String = "None earned yet."

    /** A page whose count came from the bulk progress read: it has the numbers and no list of achievements, and nothing has read them yet. */
    const val BadgesNotLoaded: String = "Badges aren’t loaded yet."

    /** A card's badge strip while its read is due or under way: the count above it is already true. */
    const val BadgesLoading: String = "Loading badges…"

    /** A card's badge strip after a read that got no answer (offline, RetroAchievements down or asking for a pause): it says what happened and that a tap tries again. */
    const val BadgesFailed: String = "Badges couldn’t load. Tap to try again."

    /** A card's badge strip once its read has been under way past the read's deadline: nothing is cut short and it may still land, and a tap asks again. */
    const val BadgesStalled: String = "Still loading badges… Tap to try again."

    /** A card's badge strip while RetroAchievements has asked every client to wait: nothing is sent until it has passed, and a tap tries again. */
    const val BadgesHeld: String = "RetroAchievements asked us to wait. Try again in a moment."

    // ── Head-band status words. Each states a condition; none claims Mimir does the unlocking. ──
    const val StatusChecking: String = "CHECKING"
    const val StatusUnavailable: String = "UNAVAILABLE"
    const val StatusNeedsSetup: String = "NEEDS SETUP"
    const val StatusUnsupported: String = "UNSUPPORTED"
    const val StatusFormatNotReady: String = "FORMAT NOT READY"
    const val StatusFileTooLarge: String = "FILE TOO LARGE"
    const val StatusSystemUnmapped: String = "PLATFORM NOT READY"
    const val StatusNoSet: String = "NO SET"

    const val Checking: String = "Checking RetroAchievements…"

    const val NoAnswerYet: String = "Mimir doesn’t have an answer for this game yet."

    /**
     * The Activity-level page's empty state: a connected account and no game checked yet. [NoAnswerYet] speaks of ONE game, which this page has none of.
     * The same two lines the Activity card says (it reads these constants), so the card and the page it opens agree.
     */
    const val NothingCheckedYet: String = "No achievements checked yet"

    const val CheckGamesToLoad: String = "Check games in the library to load achievements"

    const val NoCredentials: String =
        "Add your RetroAchievements username and Web API key in Settings › Profile to see progress here."

    const val OptedOut: String =
        "Connected services are off. Reconnect RetroAchievements in Settings › Profile to check this game."

    const val CredentialsRejected: String =
        "RetroAchievements isn’t accepting your saved Web API key. Update it in Settings › Profile."

    /** The NEEDS SETUP card's action (F3). It names where it goes, the same words the reasons above use. */
    const val OpenProfileSettings: String = "Open Settings › Profile"

    const val Unreachable: String = "Couldn’t reach RetroAchievements just now."

    const val ProgressUnavailable: String =
        "Found the achievement set, but couldn’t read your progress just now."

    const val TooLarge: String = "This file is too large for Mimir to check here."

    const val FileUnavailable: String = "Mimir can’t read this file right now."

    const val SystemUnsupported: String =
        "RetroAchievements doesn’t currently support achievements for this platform."

    /** RA supports the platform; only Mimir's file-identification implementation is still owed. */
    const val IdentificationUnavailable: String =
        "RetroAchievements supports this platform, but Mimir can’t identify this game format yet."

    /**
     * The system is one Mimir has no RetroAchievements console mapping for yet.
     *
     * DISTINCT FROM [SystemUnsupported] on purpose. The latter is an RA product-support fact; this one names
     * a missing Mimir↔RA console-name mapping. It also remains distinct from [IdentificationUnavailable],
     * where the platform is mapped but Mimir has not yet ported the file-identification algorithm.
     */
    const val ConsoleUnmapped: String =
        "Mimir can’t check achievements for this platform yet."

    /**
     * RA answered, but its structural data was not complete enough for a safe file claim.
     *
     * Deliberately NOT [Unreachable] (nothing failed to answer) and NOT [NoSetForGame] (a set exists).
     * Naming it plainly keeps the card from turning a provider-shape problem into a false network outage
     * or a false claim that the file has no set.
     */
    const val ProviderDataIncomplete: String =
        "RetroAchievements returned incomplete data, so Mimir couldn’t finish checking this game."

    const val NoSetForGame: String =
        "This file didn’t match a set. RetroAchievements may not cover this version."

    const val RecognizedNoSet: String =
        "RetroAchievements recognizes this file. No achievement set is attached to it."

    const val ConnectValidating: String = "Checking your key with RetroAchievements…"

    const val ConnectAcceptedTitle: String = "Key accepted"

    const val ConnectAcceptedBodyPrefix: String =
        "RetroAchievements accepted this key. Progress will be read for "

    const val ConnectUsernameRejected: String =
        "RetroAchievements accepted the Web API key, but that username was not found. " +
            "Check the account name and try again. Nothing was saved."

    const val ConnectKeyRejected: String =
        "RetroAchievements did not accept that Web API key. Copy the current key from " +
            "retroachievements.org › Settings › Web API Key, then try again. Nothing was saved."

    const val ConnectUnreachable: String =
        "Couldn’t reach RetroAchievements. Check your connection and try again. Nothing was saved."

    const val ConnectSaveFailed: String =
        "RetroAchievements accepted the account, but Mimir couldn’t activate it safely. " +
            "No new connection is active; try again."

    const val ConnectRecoveryUncertain: String =
        "RetroAchievements is off for this session, but Mimir couldn’t prove which encrypted account " +
            "record is durable. Retry cleanup before restarting Mimir."

    const val SettingsNeverChecked: String =
        "Mimir hasn’t checked this key yet."
}

/** "310 of 1,200 points". Pure so the string is pinned by a JVM test. */
fun mimirAchievementPointsLabel(earned: Int, total: Int): String =
    "%,d of %,d points".format(earned.coerceAtLeast(0), total.coerceAtLeast(0))

/**
 * The system's "remove animations" setting, read once per composition.
 *
 * Same signal the Home shelf already honours (`MimirAppHost`'s `homeReducedMotion`), lifted into `core-ui`
 * so a component can default to it instead of every call site re-deriving it.
 */
@Composable
fun mimirReducedMotion(): Boolean = remember { !ValueAnimator.areAnimatorsEnabled() }

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// LEDGER TOKENS
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * Every text on this page carries an explicit `lineHeight` and a [LineHeightStyle] that trims NOTHING.
 *
 * This is the fix for the reported clipping. [MimirTypeScale] leaves `lineHeight` unspecified, which is
 * fine for the single-line chrome it was written for, but on a wrapped paragraph it leaves the leading to
 * the font — and a localised string with stacked diacritics or deep descenders then loses its extremities
 * to the line box. Setting the leading here (rather than on the shared scale) keeps the fix inside this
 * card instead of re-flowing every other surface in the app.
 */
private val LedgerLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private val LedgerTitleStyle: TextStyle =
    MimirTypeScale.Title.copy(fontWeight = FontWeight.SemiBold, lineHeight = 30.sp, lineHeightStyle = LedgerLineHeight)

private val LedgerRowTitleStyle: TextStyle =
    MimirTypeScale.Row.copy(lineHeight = 20.sp, lineHeightStyle = LedgerLineHeight)

/**
 * Descriptions are PROSE, so they drop [MimirTypeScale.Pill]'s SemiBold weight and its 0.3sp tracking —
 * that style is a chip label, and a page of forty bold, tracked sentences is what made the list read as a
 * data dump rather than a book.
 */
private val LedgerRowBodyStyle: TextStyle = MimirTypeScale.Pill.copy(
    fontWeight = FontWeight.Normal,
    letterSpacing = 0.sp,
    lineHeight = 17.sp,
    lineHeightStyle = LedgerLineHeight,
)

private val LedgerRowPointsStyle: TextStyle =
    MimirTypeScale.Pill.copy(lineHeight = 20.sp, lineHeightStyle = LedgerLineHeight)

private val LedgerStatementStyle: TextStyle =
    MimirTypeScale.Support.copy(lineHeight = 19.sp, lineHeightStyle = LedgerLineHeight)

private val LedgerSupportStyle: TextStyle = MimirTypeScale.Pill.copy(
    fontWeight = FontWeight.Normal,
    letterSpacing = 0.sp,
    lineHeight = 17.sp,
    lineHeightStyle = LedgerLineHeight,
)

/** Ink steps on the hero volume — the ladder the Home Edit menu and Utility panel already use. */
private const val InkSecondary = 0.72f
private const val InkSupport = 0.62f
private const val InkLockedTitle = 0.52f
private const val InkLockedBody = 0.40f

/** A one-line state must not render as a narrow slip. Height deliberately has no floor — see the card. */
private val MimirAchievementCardMinWidth: Dp = 320.dp
private val MimirAchievementCardMaxWidth: Dp = 520.dp

private val LedgerRuleHeight: Dp = 10.dp
private val LedgerNodeRadius: Dp = 3.dp
private val LedgerCapHeight: Dp = 28.dp
private val LedgerRailWidth: Dp = 3.dp

/** Rail width plus clearance, reserved on the ledger's end edge whether or not a rail is drawn. */
private val LedgerRailGutter: Dp = 11.dp
private val PointsColumnWidth: Dp = 32.dp

/** Lifts the medal and the points figure onto the row title's optical baseline. */
private val MedalOpticalTop: Dp = 2.dp

private const val RuleAlpha = 0.16f
private const val NodeAlpha = 0.50f

// SweepAlpha/SweepHalfWidth/LedgerPulseMs moved to MimirIndeterminateBar.kt so this
// rule's sweep and the reusable indeterminate bar's sweep share one source of truth for motion. Same
// package, so these `internal const val`s resolve here without an import.
private const val CapEdgeAlpha = 0.94f
private const val CapInvisibleThreshold = 0.01f
private const val RailTroughAlpha = 0.10f
private const val RailThumbAlpha = 0.55f
private const val MinThumbFraction = 0.12f

/** A page minus an overlap band, so a step never loses the reader's place. */
private const val LedgerStepFraction = 0.78f
