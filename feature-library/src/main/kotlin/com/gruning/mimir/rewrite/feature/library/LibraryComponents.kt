package com.gruning.mimir.rewrite.feature.library

import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import com.gruning.mimir.rewrite.core.ui.MimirArtInkHoldCapMs
import kotlinx.coroutines.delay
import com.gruning.mimir.rewrite.core.ui.MimirArtInkStore
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.input.mimirActivatable
import com.gruning.mimir.rewrite.core.input.mimirFocusable
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.model.library.ShelfFaceShape
import com.gruning.mimir.rewrite.core.ui.MimirBoxArtCorner
import com.gruning.mimir.rewrite.core.ui.MimirNorseBookAspectRatio
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.MediaArtFallbackGlyph
import com.gruning.mimir.rewrite.core.ui.MediaArtFrame
import com.gruning.mimir.rewrite.core.ui.MimirBoxArt
import com.gruning.mimir.rewrite.core.ui.MediaArtBookCover
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.MimirFocusRestingAlpha
import com.gruning.mimir.rewrite.core.ui.MimirFocusTileRingReach
import com.gruning.mimir.rewrite.core.ui.MimirRingGleam
import com.gruning.mimir.rewrite.core.ui.MimirRingShape
import com.gruning.mimir.rewrite.core.ui.MimirTimeCopy
import com.gruning.mimir.rewrite.core.ui.mimirSelectionRing
import com.gruning.mimir.rewrite.core.ui.theme.MimirColors
import com.gruning.mimir.rewrite.core.ui.theme.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.theme.MimirType
import java.time.ZoneId

/**
 * Shared visual building blocks for the Library — the tiles + the honest media-art holding frame. All
 * focus + tap flow through the single [FocusAuthority] ; never
 * a Compose `focusable()`. Tokens only (every color/gap is a `Mimir*` token), so Library matches
 * Settings + Diagnostics exactly.
 */

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// RC-37 — the gamelist LIST view's focus reach, and the room its container must leave for it
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/**
 *
 *
 * **The header is not the clipper.** `GamelistView` lays the header and the list out as SIBLINGS in a
 * `Column` (`LibraryScreen.kt:2887`), so the header cannot overdraw the list. The list's own `LazyColumn`
 * (`LibraryScreen.kt:2960`) clips tightly on its scrolling axis — Compose Foundation applies
 * `clipScrollableContainer` to every lazy list, which is exact on the main axis — and its `contentPadding`
 * carries a `bottom` only, no `top` (`LibraryScreen.kt:2962-2964`). The first row's focus treatment
 * therefore paints ABOVE the list's own top bound and is cut there. Same defect, unreported, at the bottom
 * bound whenever `bottomControlInset` is 0.
 *
 * **Since the ring migration  the row's treatment is the one selection ring**, which paints
 * [MimirFocusTileRingReach] outside the row and nothing else: the 1 dp lift and the 8 dp `clip = false` shadow
 * that used to be the reach retired with the old border, and the hairline the ring paints at rest is stroked
 * inside the bounds. The reach is the family's, read from core-ui rather than restated, so the padding and
 * the row cannot be tuned apart. It is applied to both clipped edges, as before.
 */
internal val LibraryListRowFocusOutwardReach: Dp = MimirFocusTileRingReach

/** The corner every library list row clips and rings on (R-1: one symbol for both). */
internal val LibraryListRowCorner: Dp = 12.dp

/**
 * The content padding for a library list-view `LazyColumn` — the gamelist AND the native-favourites detail list, which carry the identical focus treatment: enough room at BOTH clipped edges for a focused
 * row's [LibraryListRowFocusOutwardReach], derived from that reach rather than authored as a nudge, so it
 * tracks the row instead of drifting when the row's lift or elevation is tuned.
 *
 * [bottomInset] is the caller's existing keyboard/bottom-control inset. It is combined with `maxOf`, not
 * added: the two serve different masters — the inset keeps content clear of a control band that is already
 * far taller than the reach, so adding them would over-pad the common case, while `maxOf` still guarantees
 * the reach when the inset is 0 and the last row would otherwise clip exactly like the first.
 */
internal fun libraryListContentPadding(bottomInset: Dp): PaddingValues = PaddingValues(
    top = LibraryListRowFocusOutwardReach,
    bottom = maxOf(bottomInset, LibraryListRowFocusOutwardReach),
)

/** List preview media keeps its selected physical/cover aspect and Fits the full source without crop or stretch. */
internal val LibraryListCoverContentScale: ContentScale = ContentScale.Fit

enum class LibraryListPreviewMediaSource {
    PHYSICAL,
    COVER,

    /**
     * A square icon tile the player picked, or the automatic SteamGridDB square standing in for a missing cover (the Icon art,
     * policy 2026-10-10). Square like [PHYSICAL] -- aspect [LibraryPhysicalMediaCoverAspect] -- but a different object: it is the
     * Switch tile the shelf paints, not the game's cartridge or disc, and a reader that must tell them apart no longer reads one as the other.
     */
    SQUARE,

    /**
     * Resolution has not completed, so nothing is known yet. a row in this state
     * wears NOTHING — a quiet themed surface that claims nothing — never the book.
     *
     * Distinct from [FALLBACK] on purpose. They look different because they MEAN different things: this one
     * says *"we have not finished looking"*, and the book says *"this game has no art"*. Collapsing them is
     * how the book got painted over two GameCube games whose covers were on the SD card.
     */
    UNRESOLVED,
    FALLBACK,
}

data class LibraryListPreviewMedia(
    val artUri: String?,
    val aspectRatio: Float,
    val source: LibraryListPreviewMediaSource,
) {
    val usesFramelessRealArt: Boolean get() = artUri != null
}

/**
 * The lane proportion a cartridge/disc/card render is given, everywhere this product paints one.
 *
 * Square, and NOT measured per bitmap: `ContentScale.Fit` inside a square lane is what keeps a tall GBA
 * cart and a round CD reading as the same kind of object. This was already the literal `1f` in
 * [libraryListPreviewMedia] below; it is named here because the launch card now solves against it too
 * (- see `homeLaunchCardCoverBoxWidth`'s KDoc), and two surfaces holding the same
 * untitled literal is how the aspect of one of them silently drifts.
 *
 * **`[V]` on a captured SD card, from commit `5de72476` (RC-49), which audited all 21 `-support.png`
 * files on it:** 18 genuine disc renders, 2 genuine Switch cartridges, and exactly ONE wrong file --
 * RE5's, a 1024x680 flattened box wrapper that a provider fallback installed as disc art. A real disc
 * render there measures 600x600. So square is not an assumption about what providers send; it is what the
 * corpus on the reference device actually is, and the single 1.51-aspect outlier is a defect that lane is fixing at
 * the source rather than a proportion this lane should widen the lane for.
 */
internal const val LibraryPhysicalMediaCoverAspect = 1f

/**
 * The list display law (DL-1..DL-5) scopes the Cartridge Hero disc-first swap to the gamelist
 * list view.
 *
 * Cover, then disc, then the honest fallback. The Cartridge Hero switch that could put the disc
 * first was removed 2026-09-22; a hand-picked disc still wins, because that is resolved earlier.
 *
 * This function used to take no gate at all and return `PHYSICAL` unconditionally, which is the whole
 * of the bug the regression report described. Device evidence, a measured Nova, 2026-08-25: every GameCube title carries a
 * provider `-support.png`, so the disc always won and its cover -- present on the SD beside every ROM
 * -- was never once painted. Wii looked healthy only because the one Wii title on that card has no
 * disc render and therefore fell through. **Same rule, different corpus.** That is why this is fixed
 * as a law rather than as a platform special-case: nothing about GameCube was ever special.
 *
 * DL-4: the fallback is the LAST tier, never the second. Real art of either kind outranks the
 * placeholder, so a game with a disc and no cover shows its disc. **DL-4 still stands HERE**, and that is
 * deliberate rather than an oversight: the shelf-face policy reversed it for the SHELF face law, where
 * many games sit side by side and a per-game fallback makes the shelf look assembled from two libraries.
 * A list row carries its own title and its own lane, so the same fallback costs nothing there. The
 * placeholder asserts *"this game has no art"*, and painting it over a game whose art is on disk is the
 * product claiming an absence that is false.
 *
 * DL-5: the aspect follows the WINNER, not the surface -- [LibraryPhysicalMediaCoverAspect] for a
 * disc, the platform aspect for a cover -- so the lane is never chosen before the tier is.
 */
/*
 * `gamelistLaunchCardShowsPhysicalMedia(viewMode)` stood here and is DELETED (C1, 2026-08-25), for the
 * same reason as its app-side twin: it DERIVED the launch card's face from which view you happened to be
 * in, and C1 replaces derivation with an explicit LAUNCH CARD state that the card reads directly.
 *
 * The `GamelistViewMode.entries.size` guard that lived with its test has been REHOMED rather than deleted
 * -- see LibraryShelfStabilizationContractTest. It pins that adding a third view mode forces a decision
 * instead of inheriting one, which is still true and is nothing to do with this function.
 */
fun libraryListPreviewMedia(
    game: GameRow,
): LibraryListPreviewMedia {
    val platformAspect = platformCoverAspect(game.platformId.value)
    // S1 -- the face he wired BY HAND. A hand-picked face replaces the current state regardless of the
    // Cartridge Hero switch, so it outranks the automatic tiers below it. It is a separate tier rather than
    // a value folded into artUri because folding it in is exactly what made the first Cover picker violate
    // C4: the ladder reached the provider's disc render first, and a cover he had chosen by hand lost to
    // art he never picked.
    //
    // FL-6: the lane follows the WINNER, not the tier -- a pick may be a disc where the automatic answer
    // would have been a cover, and the aspect has to follow the picture that is actually painted.
    game.wiredFace?.let { wired ->
        val uri = wired.uri.trim()
        if (uri.isNotEmpty()) {
            // The shape decides both, never the uri: a square is reported as a SQUARE (not as a disc at the disc's aspect), a
            // disc as PHYSICAL, a cover as a COVER at the platform's proportion.
            return when (wired.shape) {
                ShelfFaceShape.SQUARE -> LibraryListPreviewMedia(uri, LibraryPhysicalMediaCoverAspect, LibraryListPreviewMediaSource.SQUARE)
                ShelfFaceShape.PHYSICAL -> LibraryListPreviewMedia(uri, LibraryPhysicalMediaCoverAspect, LibraryListPreviewMediaSource.PHYSICAL)
                ShelfFaceShape.LEDGE_COVER, ShelfFaceShape.NONE -> LibraryListPreviewMedia(uri, platformAspect, LibraryListPreviewMediaSource.COVER)
            }
        }
    }
    val physical = game.physicalMediaUri?.trim()?.takeIf(String::isNotEmpty)?.let {
        LibraryListPreviewMedia(
            artUri = it,
            aspectRatio = LibraryPhysicalMediaCoverAspect,
            source = LibraryListPreviewMediaSource.PHYSICAL,
        )
    }
    val cover = game.artUri?.trim()?.takeIf(String::isNotEmpty)?.let {
        LibraryListPreviewMedia(
            artUri = it,
            aspectRatio = platformAspect,
            source = LibraryListPreviewMediaSource.COVER,
        )
    }
    // Cover first, always. The switch that could reverse this was removed with the Cartridge Hero
    // promotion ; this was already its documented DEFAULT order.
    val ordered = listOf(cover, physical)
    ordered.firstOrNull { it != null }?.let { return it }
    // Nothing to show. WHY there is nothing decides what gets painted: the book is a claim, and it may
    // only be made once the product has actually established the absence.
    return LibraryListPreviewMedia(
        artUri = null,
        aspectRatio = platformAspect,
        source = if (game.artResolutionSettled) {
            LibraryListPreviewMediaSource.FALLBACK
        } else {
            LibraryListPreviewMediaSource.UNRESOLVED
        },
    )
}

/** Walk-32i: how many rows before and after the focused one the list has the art ink measured for ahead of the player. */
internal const val GamelistInkLookBehind = 3
internal const val GamelistInkLookAhead = 6

/**
 * The real art the list's preview panel will show for the rows around [centre], nearest first: the focused row, then the row
 * after it and the row before it, outward, further ahead than behind because that is the way a list is walked. Only art
 * that is painted as art (a row that is showing the book or nothing has no ink to measure), each once.
 */
internal fun gamelistInkPrefetchUris(games: List<GameRow>, centre: Int): List<String> {
    if (games.isEmpty()) return emptyList()
    val at = centre.coerceIn(0, games.lastIndex)
    val order = buildList {
        add(at)
        for (step in 1..maxOf(GamelistInkLookBehind, GamelistInkLookAhead)) {
            if (step <= GamelistInkLookAhead) add(at + step)
            if (step <= GamelistInkLookBehind) add(at - step)
        }
    }
    return order
        .filter { it in games.indices }
        .map { libraryListPreviewMedia(games[it]) }
        .filter { it.usesFramelessRealArt }
        .mapNotNull { it.artUri }
        .distinct()
}

/**
 * A small collapsed-group badge — "N discs" / "N versions". Only rendered when a group folds more than one member behind the tile.
 */
@Composable
fun CollapseBadge(text: String) {
    val palette = LocalMimirPalette.current
    Box(
        Modifier
            .clip(RoundedCornerShape(MimirSpacing.radiusPill))
            .background(palette.accent.copy(alpha = 0.16f))
            .padding(horizontal = MimirSpacing.sm, vertical = 2.dp),
    ) {
        Text(text, style = MimirType.Pill, color = palette.accent)
    }
}

/**
 * A platform shelf tile: label + "N games". Focus-reactive (gold ring + gold label when focused).
 * Registers in [scope] under [key]; a tap requests focus AND opens (touch/gamepad parity).
 */
@Composable
fun PlatformTile(
    label: String,
    gameCount: Int,
    focused: Boolean,
    authority: FocusAuthority,
    key: FocusKey,
    scope: FocusScope,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The one selection ring (ring migration, 2026-09-28): a card ring on the tile's own corner, one plain sweep,
    // no grow -- the family has no scale. Outermost, before the clip, so it can reach past the card.
    val palette = LocalMimirPalette.current
    Column(
        modifier = modifier
            .width(180.dp)
            .mimirSelectionRing(focused = focused, identity = label, shape = MimirRingShape.Card(MimirSpacing.radiusCard))
            .clip(RoundedCornerShape(MimirSpacing.radiusCard))
            .background(if (focused) palette.surfaceHigh else palette.surfaceRaised)
            .mimirFocusable(authority, key, scope)
            // A tap focuses, then opens, with no second Compose focus node (motion audit batch A).
            .mimirActivatable(label = stringResource(R.string.library_shelf_open_description), authority = authority, key = key, onActivate = onOpen)
            .padding(MimirSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
    ) {
        Text(
            label,
            style = MimirType.RailItem,
            color = if (focused) palette.accentBright else palette.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        // Canon "N games" (local-only policy).
        Text(
            libraryResources().getQuantityString(R.plurals.library_shelf_games_count, gameCount, gameCount),
            style = MimirType.RowSupport,
            color = if (focused) palette.accent else palette.textSecondary,
        )
    }
}

/**
 * A game tile: the media-art holding frame with an optional collapse badge, and the title beneath.
 * Focus-reactive; a tap requests focus AND activates (opens the detail stub).
 */
@Composable
fun GameTile(
    title: String,
    badge: String?,
    focused: Boolean,
    authority: FocusAuthority,
    key: FocusKey,
    scope: FocusScope,
    onActivate: () -> Unit,
    modifier: Modifier = Modifier,
    isFavorite: Boolean = false,
    lastPlayedMillis: Long? = null,
    genre: String? = null,
    supportLabel: String? = null,
    artUri: String? = null,
    tileWidth: Dp = 150.dp,
) {
    val palette = LocalMimirPalette.current
    Column(
        modifier = modifier
            .width(tileWidth)
            .clip(RoundedCornerShape(MimirSpacing.radiusCard))
            .mimirFocusable(authority, key, scope)
            .mimirActivatable(label = stringResource(R.string.library_shelf_open_description), authority = authority, key = key, onActivate = onActivate)
            .padding(MimirSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
    ) {
        Box {
            // The ONE shared box-art frame (core-ui). The gamelist tile shows its title BELOW the frame,
            // so the frame's honest fallback renders the glyph only (showFallbackTitle = false). When the
            // offline resolver found a LOCAL cover ([artUri]), Coil paints it into the frame's art slot;
            // a load miss degrades to the SAME honest glyph the frame draws when no art exists.
            MediaArtFrame(
                title = title,
                focused = focused,
                showFallbackTitle = false,
                artRef = artUri,
                artContent = { ref -> MimirBoxArt(model = ref, title = title, focused = focused) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (badge != null) {
                Box(Modifier.align(Alignment.TopEnd).padding(MimirSpacing.sm)) {
                    CollapseBadge(badge)
                }
            }
            // A favorite ★ marker, top-start, on a quiet scrim so it reads on art or the glyph fallback.
            if (isFavorite) {
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(MimirSpacing.sm)
                        .clip(RoundedCornerShape(MimirSpacing.radiusPill))
                        .background(palette.surfaceBase.copy(alpha = 0.66f))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                ) {
                    Text("★", style = MimirType.Pill, color = palette.accent)
                }
            }
        }
        Text(
            title,
            style = MimirType.RowTitle,
            color = if (focused) palette.accentBright else palette.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Start,
        )
        if (supportLabel != null) {
            Text(
                supportLabel,
                style = MimirType.RowSupport,
                color = palette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // The offline genre tag — a quiet tertiary line under the title (only when the bundled map knows it).
        if (genre != null) {
            Text(
                genre,
                style = MimirType.RowSupport,
                color = palette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // A quiet "last played" line — only when this group has a successful launch on record.
        if (lastPlayedMillis != null) {
            Text(
                relativePlayed(lastPlayedMillis),
                style = MimirType.RowSupport,
                color = palette.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Deduplicate support/platform vs genre so a normal List caller cannot paint the same fact twice. */
internal fun distinctPreviewSupportFacts(supportLabel: String?, genre: String?): List<String> {
    val support = supportLabel?.trim()?.takeIf { it.isNotEmpty() }
    val genreFact = genre?.trim()?.takeIf { it.isNotEmpty() }
    if (support == null) return listOfNotNull(genreFact)
    if (genreFact == null || support.equals(genreFact, ignoreCase = true)) return listOf(support)
    return listOf(support, genreFact)
}

/** Join distinct support/platform + genre for a single leather metadata line. */
internal fun previewSupportGenreLine(supportLabel: String?, genre: String?): String? =
    distinctPreviewSupportFacts(supportLabel, genre).joinToString(" · ").ifBlank { null }

/**
 * THE LIST PREVIEW STAGE: **same painted size for every cover**.
 *
 * What it replaces, and why the replacement is a different shape of function. `solveGamePreviewArtLayout`
 * gave the art whatever the chrome left over, per game, per aspect. Measured on the reference hardware
 *  that produced two separate size lotteries:
 *
 *  - **Per-row.** The reserve came from what the SELECTED game happened to carry, so the same cover
 *    painted 104×144 with one metadata line and **46×64** with the full set — a fifth of the area,
 *    changed by scrolling one row.
 *  - **Per-aspect.** Every cover was height-clamped, so width was just `stageHeight × aspect`: N64
 *    19.7k dp², disc 14.4k, GameCube 10.3k, PSP 8.5k. A 2.3× spread, and the tall covers lost.
 *
 * Both are gone because the stage is now solved from the PANEL and nothing else. [gamelistPreviewArtAreaDp2]
 * runs once per panel; [gamelistPreviewArtSize] then spends that one area on whatever aspect the row's
 * winner happens to be. Two functions rather than one because that IS the invariant: a signature that
 * cannot see the game cannot size by the game.
 */
internal data class GamePreviewArtSolve(
    val artWidthDp: Float,
    val artHeightDp: Float,
)

/**
 * The aspect range the stage must hold. Same numbers the previous solver clamped to, now named,
 * because they are load-bearing twice over: they bound the area target below, and they are what makes
 * a [MeasuredCoverAspects] drift unable to move the size of every OTHER platform's cover. The target
 * is derived from these bounds, never from the aspect of the game in front of you.
 */
internal const val GamelistPreviewAspectMin = 0.45f
internal const val GamelistPreviewAspectMax = 1.60f

/**
 * The one painted area every cover on this list gets, in dp².
 *
 * At area `A` a cover of aspect `a` is `sqrt(A·a)` wide and `sqrt(A/a)` tall. So the WIDEST supported
 * cover bounds `A` by the panel's width and the TALLEST bounds it by the stage's height, and the
 * answer is whichever binds first — that is the largest area that fits every shape without any of
 * them overflowing.
 *
 * On both handhelds the width bound governs (Odin stage 220dp, Nova 200dp, panel 170dp wide → the
 * height term is 21.8k/18.0k against a width term of 18.06k), which is why **one target serves both
 * devices** rather than each screen inventing its own idea of a standard size.
 */
internal fun gamelistPreviewArtAreaDp2(
    availableWidthDp: Float,
    availableHeightDp: Float,
    reservedBelowArtDp: Float,
): Float {
    val width = availableWidthDp.coerceAtLeast(1f)
    val stageHeight = (availableHeightDp - reservedBelowArtDp).coerceAtLeast(1f)
    return min(
        width * width / GamelistPreviewAspectMax,
        GamelistPreviewAspectMin * stageHeight * stageHeight,
    ).coerceAtLeast(1f)
}

/**
 * Spends [areaDp2] on one cover. Aspect is preserved exactly; only the seat changes shape.
 *
 * The `coerceAtMost` pair is belt-and-braces, not the mechanism: because [aspectRatio] is clamped to
 * the same bounds [gamelistPreviewArtAreaDp2] derived the target from, `sqrt(A·a) ≤ W` and
 * `sqrt(A/a) ≤ H` hold by construction. They are kept so a degenerate panel — a one-frame zero
 * measurement during layout — cannot hand back art larger than the stage that contains it.
 */
internal fun gamelistPreviewArtSize(
    areaDp2: Float,
    aspectRatio: Float,
    maxWidthDp: Float,
    maxHeightDp: Float,
): GamePreviewArtSolve {
    val aspect = aspectRatio.coerceIn(GamelistPreviewAspectMin, GamelistPreviewAspectMax)
    val area = areaDp2.coerceAtLeast(1f)
    val width = sqrt(area * aspect).coerceAtMost(maxWidthDp.coerceAtLeast(1f)).coerceAtLeast(1f)
    val height = (width / aspect).coerceAtMost(maxHeightDp.coerceAtLeast(1f)).coerceAtLeast(1f)
    // Re-derive width from the possibly-clamped height so the aspect survives both clamps.
    return GamePreviewArtSolve(artWidthDp = height * aspect, artHeightDp = height)
}

/**
 * The constant reserve beneath the stage: the media-action chip, and ONE line for the transient
 * launch status with its card padding.
 *
 * **Unconditional on purpose.** A reserve that asks what the selected game carries is precisely the
 * per-row resize the fixed reserve prevents, and the launch line is transient — a game that starts while you
 * look at it would otherwise shrink its own cover mid-launch.
 *
 * It is this small because the panel no longer repeats the row. Policy 2026-08-28, second
 * `AskUserQuestion` of the session: the card under the art printed `Played 6d ago · GameCube ·
 * Action-Adventure · Launch · Dolphin · Ready` while the focused row 40dp to its left already printed
 * every one of those facts. Giving that height back to the cover is what lets equal-area be an
 * increase (GameCube +75%, PSP +112%, disc +25%) instead of the regression it is at the old stage
 * height, where the best area that fits every aspect is 7.2k — below what all but the tallest covers
 * already had.
 */
internal fun gamelistPreviewChromeReserveDp(
    fontScale: Float,
    spacingSmDp: Float = 8f,
): Float {
    val scale = fontScale.coerceAtLeast(0.01f)
    val mediaAction = 28f * scale + spacingSmDp
    val transientStatus = 16f + 16f * scale + spacingSmDp
    return mediaAction + transientStatus
}

/*
 * `estimateGamePreviewChromeDp` stood here and is DELETED . It reserved the
 * AUTHORED MAXIMA of a metadata block that no longer exists — support and launcher two lines each even
 * when they rendered one, which cost a measured 38.5dp of permanently dead panel on the Odin — and it
 * took five per-game flags, which is what made the stage a function of the selected row. Its successor
 * is [gamelistPreviewChromeReserveDp], which takes the font scale and nothing else.
 *
 * The two facts it encoded that OUTLIVED it are kept rather than lost with it: the 28dp media-action
 * chip and the 16dp text line at scale 1.0 are both carried forward into the reserve above, and the
 * "reserve authored maxima, never lumped fact counts" discipline is why the transient status line is
 * reserved whether or not a launch is in progress.
 */

/** Clearance between the List content bottom and the bottom-right L/R hint envelope. */
internal const val GamelistViewModeHintClearanceDp = 8f

/** Bottom padding under the L/R view-mode hint: lg + hint-rail button + rail gap. */
internal fun gamelistViewModeHintBottomPadDp(): Float =
    MimirSpacing.lg.value + HintButtonSize.value + HintRailButtonGap.value

/**
 * List bottom control inset: the real hint envelope (pad + ControlHintCompact text height) plus
 * [GamelistViewModeHintClearanceDp]. [hintTextHeightDp] should come from
 * `ControlHintCompact.fontSize.toDp()` via LocalDensity so fontScale is honored.
 */
internal fun gamelistListBottomControlInsetDp(
    hintTextHeightDp: Float,
    bottomPadDp: Float = gamelistViewModeHintBottomPadDp(),
    clearanceDp: Float = GamelistViewModeHintClearanceDp,
): Float = bottomPadDp + hintTextHeightDp.coerceAtLeast(0f) + clearanceDp

/**
 * Favorites' Games list bottom inset. With the preview panel (standard screens) there is none, as before: the rows
 * stop left of the panel, under which the M hint-rail button sits. Without it (compact, policy D3) rows run
 * the full width, so the list ends above the button's envelope (pad + button) plus [GamelistViewModeHintClearanceDp].
 * A 2026-09-28 reproduction caught rows scrolling under the button, and the last row's chip and ring under it at the end.
 * Favorites has no L1/R1 hint, so the envelope carries no hint text.
 */
internal fun gamelistFavoritesBottomControlInsetDp(compact: Boolean): Float =
    if (gamelistListShowsPreviewPanel(compact)) {
        0f
    } else {
        MimirSpacing.lg.value + HintButtonSize.value + GamelistViewModeHintClearanceDp
    }

/** Pure fontScale path for contract tests — mirrors LocalDensity sp→dp (`sp * fontScale`). */
internal fun controlHintCompactTextHeightDp(
    fontScale: Float,
    fontSizeSp: Float = MimirTypeScale.ControlHintCompact.fontSize.value,
): Float = fontSizeSp * fontScale.coerceAtLeast(0.01f)

internal fun gamelistListBottomControlInsetForFontScale(fontScale: Float): Float =
    gamelistListBottomControlInsetDp(hintTextHeightDp = controlHintCompactTextHeightDp(fontScale))

/**
 * How far the preview's book stands in from its frame on every side. The frame's accent rim is [MimirSpacing.focusRingWidth] and its corner is [MimirSpacing.radiusCard]; a book
 * of the proportional corner [com.gruning.mimir.rewrite.core.ui.mimirNorseBookCoverCorner] (about 5 dp here) clears that curve from 4 dp in, and this leaves a dp of air past it.
 */
internal val GamelistPreviewBookInset: Dp = 5.dp

/** Walk-32i: the panel as a whole, and the art box inside it, for the test that watches what the player is shown. */
internal const val GamelistPreviewPanelTestTag = "gamelist_preview_panel"
internal const val GamelistPreviewArtTestTag = "gamelist_preview_art"

/** Whether the panel is showing what it holds (true) or holding it out of sight for the art's ink (false). */
internal val GamelistPreviewShownSemantics = SemanticsPropertyKey<Boolean>("GamelistPreviewShown")
internal var SemanticsPropertyReceiver.gamelistPreviewShown by GamelistPreviewShownSemantics

/**
 * The List view's preview panel. Art, the media action, and — only while a launch is actually running
 * — one transient status line.
 *
 * **What it deliberately no longer says.** the leather card that used to sit
 * here printed `Played 6d ago · GameCube · Action-Adventure · Launch · Dolphin · Ready`, and the
 * focused ROW 40dp to its left already prints every one of those facts ([GamelistRow] builds them from
 * the same [distinctPreviewSupportFacts]/[relativePlayed]/`launchStatusChipLabel` helpers). It was the
 * panel repeating its own list. [runtimeLaunchLine] is the one thing the row cannot show, because it is
 * transient — live launch progress for the game being started — so it stays.
 *
 * That is not a copy trim, it is where the cover's height comes from. See
 * [gamelistPreviewChromeReserveDp]: at the old reserve the largest equal area that fits every supported
 * aspect was 7.2k dp², BELOW what all but the tallest covers already had, so the equal-area target could not be
 * honoured without taking that height back.
 *
 * [supportLabel] survives the trim and is still a parameter: the book fallback prints it on the spine
 * ([MediaArtBookCover]), which is a different job from listing it as a fact.
 */
@Composable
internal fun GamePreviewPanel(
    title: String,
    supportLabel: String?,
    /** Live launch progress for the displayed game, or null. The ROW carries every settled fact. */
    runtimeLaunchLine: String? = null,
    previewMedia: LibraryListPreviewMedia,
    platformIdValue: String? = null,
    compactArt: Boolean = false,
    mediaAction: (@Composable () -> Unit)? = null,
    bottomControlInset: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    val palette = LocalMimirPalette.current
    val hero = palette.materials.hero
    val density = LocalDensity.current
    val context = LocalContext.current
    //  Both are Box (3D) renders; the
    // difference is how much empty canvas each provider file carries. Measured once per art, off the main
    // thread, and remembered -- so the seat is shaped by the ART and equal area is spent on ink rather
    // than on artboard.
    val artUri = previewMedia.artUri
    LaunchedEffect(artUri) {
        MimirArtInkStore.ensureLoaded(context)
        MimirArtInkStore.measure(context, artUri)
    }
    val ink = MimirArtInkStore.boundsFor(artUri)?.takeIf { previewMedia.usesFramelessRealArt }
    // Nova cold-launch list-view regression: artwork resized after each focus move. The seat below is
    // solved from the ink, and the ink of an art the store has not measured yet arrives a decode later, so the art was painted
    // at the platform's seat on the frame it was reached and again at its own a moment after: seen, and the chip under it
    // moved with it. Nothing can know an art's ink before a decode has scanned it, so the panel does not show the art until the
    // store has a verdict ([MimirArtInkStore.hasVerdict]): the list measures the arts around the focused one ahead of the
    // player, so that is nearly always already so, and what is held is held out of sight (the art, and the chip that follows
    // its height), at a seat nobody sees, to be shown once, at the seat it keeps. No motion is added: the art's own reveal,
    // a fade for a cold bitmap and a snap for a warm one, starts when it is let go. Only real art is held; the book and the
    // quiet surface have no ink to wait for. It is never held past [MimirArtInkHoldCapMs].
    val verdictIn = !previewMedia.usesFramelessRealArt || MimirArtInkStore.hasVerdict(artUri)
    var holdLapsed by remember(artUri) { mutableStateOf(false) }
    LaunchedEffect(artUri, verdictIn) {
        if (!verdictIn) {
            delay(MimirArtInkHoldCapMs.toLong())
            holdLapsed = true
        }
    }
    val shown = verdictIn || holdLapsed
    // The book fallback (resolved, no art) is shown WHOLE (alpha-cert sweep 10-09: the 3:4 book sat in a wider platform frame, its top and bottom under the frame's
    // rim and its platform name cut to "SUPER NINTE..."). Its frame takes the book's own 3:4, the way the Icon art's Default tile does  and the carousel's
    // book ring does (R-4.3), and the book stands inset in it. The stage still spends the one equal area on it, so a book is exactly as big as any cover. The quiet
    // surface of a row still resolving keeps the platform's shape: it claims nothing.
    val showsBook = !previewMedia.usesFramelessRealArt && previewMedia.source != LibraryListPreviewMediaSource.UNRESOLVED
    val frameAspectRatio = if (showsBook) {
        MimirNorseBookAspectRatio
    } else {
        ink?.aspect?.coerceIn(GamelistPreviewAspectMin, GamelistPreviewAspectMax) ?: previewMedia.aspectRatio
    }
    BoxWithConstraints(
        modifier = modifier
            .fillMaxHeight()
            .padding(bottom = bottomControlInset),
    ) {
        val panelWidth = maxWidth
        val panelHeight = maxHeight
        val widthBudget = if (compactArt) minOf(panelWidth, 146.dp) else panelWidth
        // Solved from the PANEL, and from nothing about this game: one area for every row on this list.
        val reservedBelowArt = gamelistPreviewChromeReserveDp(
            fontScale = density.fontScale,
            spacingSmDp = MimirSpacing.sm.value,
        ).dp
        val artArea = gamelistPreviewArtAreaDp2(
            availableWidthDp = widthBudget.value,
            availableHeightDp = panelHeight.value,
            reservedBelowArtDp = reservedBelowArt.value,
        )
        val artSolve = gamelistPreviewArtSize(
            areaDp2 = artArea,
            aspectRatio = frameAspectRatio,
            maxWidthDp = widthBudget.value,
            maxHeightDp = (panelHeight.value - reservedBelowArt.value).coerceAtLeast(1f),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (shown) 1f else 0f)
                .testTag(GamelistPreviewPanelTestTag)
                .semantics { gamelistPreviewShown = shown },
            verticalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Was a stacking Box for the art + a collapse badge overlaid on it. The badge left on
            // 2026-08-28 (R-1); the Box stays because the two art branches below still need one parent.
            Box {
                if (previewMedia.usesFramelessRealArt) {
                    Box(
                        modifier = Modifier
                            .width(artSolve.artWidthDp.dp)
                            .height(artSolve.artHeightDp.dp)
                            .testTag(GamelistPreviewArtTestTag)
                            // Was MimirSpacing.radiusCard (14dp) -- a CARD radius on a BOX ART, which is
                            // why the same game wore two different curves one L/R press apart.
                            .clip(RoundedCornerShape(MimirBoxArtCorner)),
                    ) {
                        MimirBoxArt(
                            model = checkNotNull(previewMedia.artUri),
                            title = title,
                            focused = true,
                            contentScale = LibraryListCoverContentScale,
                            ink = ink,
                            held = !shown,
                        )
                    }
                } else {
                    // claim nothing until the product knows. UNRESOLVED passes NO
                    // fallbackContent, so MediaArtFrame shows its plain themed surface and wears no costume
                    // -- the treatment MimirBoxArt already uses while a known source is still loading. The
                    // book is reserved for resolution finished and found nothing.
                    //
                    // Sized from the SAME solve as real art (2026-08-28). If only the real-art branch took
                    // the equal-area stage, a book and a cover one row apart would disagree about how big a
                    // cover is -- which is the reported defect wearing a different hat.
                    val unresolved = previewMedia.source == LibraryListPreviewMediaSource.UNRESOLVED
                    MediaArtFrame(
                        title = title,
                        focused = true,
                        frameAspectRatio = frameAspectRatio,
                        showFallbackTitle = false,
                        artRef = null,
                        showNoArtCostume = !unresolved,
                        fallbackContent = {
                            // Inset past the frame's rim and the curve its card corner cuts, so the book's own corners and edges all show.
                            Box(Modifier.fillMaxSize().padding(GamelistPreviewBookInset)) {
                                MediaArtBookCover(
                                    title = title,
                                    platformIdValue = platformIdValue,
                                    platformLabel = supportLabel,
                                    focused = true,
                                    // The platform's whole display name, wrapped at its spaces when one line does not hold it.
                                    fitEyebrow = true,
                                    fitTitle = true,
                                )
                            }
                        },
                        modifier = Modifier.width(artSolve.artWidthDp.dp).testTag(GamelistPreviewArtTestTag),
                    )
                }
            }
            mediaAction?.invoke()
            if (runtimeLaunchLine != null) {
                val metadataShape = RoundedCornerShape(10.dp)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            brush = Brush.verticalGradient(
                                listOf(hero.coverTop, hero.coverMiddle, hero.coverBottom),
                            ),
                            shape = metadataShape,
                        )
                        .border(
                            MimirSpacing.hairlineWidth,
                            hero.hardware.copy(alpha = 0.48f),
                            metadataShape,
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(MimirSpacing.xs),
                ) {
                    Text(
                        runtimeLaunchLine,
                        style = MimirType.RowSupport.copy(fontSize = 12.sp),
                        color = palette.emberHot,
                        // One line, because one line is what the stage reserves. Two would eat the cover
                        // it was measured against.
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/*
 * `PreviewStatusTag` stood here and is DELETED with the metadata block it lived in (policy
 * 2026-08-28). It was the panel's private copy of the READY/status pill. The row's own
 * [LibraryStatusBadge] below is the surviving one and was always the same claim about the same game --
 * the panel drew it a second time, 40dp to the right.
 */

@Composable
fun LibraryStatusBadge(
    label: String,
    ready: Boolean,
    rowStyle: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val palette = LocalMimirPalette.current
    val color = if (ready) MimirColors.SuccessGreen else MimirColors.WarningAmber
    val shape = RoundedCornerShape(MimirSpacing.radiusPill)
    val filledReady = ready && rowStyle
    Box(
        modifier
            .widthIn(min = 72.dp)
            .clip(shape)
            .background(if (filledReady) color.copy(alpha = 0.92f) else color.copy(alpha = 0.14f))
            .then(
                if (filledReady) {
                    Modifier
                } else {
                    Modifier.border(width = 1.dp, color = color.copy(alpha = 0.52f), shape = shape)
                },
            )
            .padding(horizontal = MimirSpacing.sm, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        MimirChromeText(
            label,
            style = if (rowStyle) MimirType.Pill.copy(fontSize = 11.sp) else MimirType.Pill,
            color = if (filledReady) palette.onSuccess else color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The height of the row-style [LibraryStatusBadge], with no chip and no width: a line that seats a chip only some of the time stands this
 * beside it so the line is as tall without the chip as with it (the compact title block's eyebrow row, where a game with no status moved
 * the title up by the chip's height). The chip is MEASURED, never guessed: its line is font-scaled text, so no dp constant is right at both
 * 1.0 and 1.3. Nothing is placed, drawn or announced.
 */
@Composable
internal fun LibraryStatusBadgeHeightReserve(modifier: Modifier = Modifier) {
    Layout(
        content = { LibraryStatusBadge(label = " ", ready = false, rowStyle = true) },
        modifier = modifier.clearAndSetSemantics { },
    ) { measurables, constraints ->
        val chip = measurables.single().measure(Constraints(maxWidth = constraints.maxWidth))
        layout(width = 0, height = chip.height) { }
    }
}

/**
 * A short, human "last played" phrase from an epoch-millis timestamp (* plain, approved language). Coarse buckets — a launcher doesn't need to-the-minute precision.
 *
 * The day is a LOCAL CALENDAR day, counted by [MimirTimeCopy.calendarDaysAgo], the same count the Bottom's Activity page words with
 * [MimirTimeCopy.daysAgo]. It was `now - millis` in 24-hour slabs, which called 13:28 yesterday "today" at 10:58 and 19:01 two days back
 * "yesterday" (observed on Thor). Only the day count is shared: past a day the units stay compact ("3d ago", "2w ago").
 *
 * The default [now] is [PlayedDayClock.nowMillis], so a composition that calls this turns the label over at local midnight by itself.
 */
// Public so the companion HOME_SHELF_DETAIL payload (FUX-E) can reuse it without duplicating the buckets.
fun relativePlayed(millis: Long, now: Long = PlayedDayClock.nowMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
    val days = MimirTimeCopy.calendarDaysAgo(millis, now, zone)
    val phrase = when {
        millis > now -> "just now"
        days < 1L -> "today"
        days < 2L -> "yesterday"
        days < 7L -> "${days}d ago"
        days < 30L -> "${days / 7L}w ago"
        days < 365L -> "${days / 30L}mo ago"
        else -> "${days / 365L}y ago"
    }
    return "Played $phrase"
}

/** Human byte size for the primary detail page ("586.0 MB"), or null when unknown/zero. */
fun formatSizeLabel(bytes: Long): String? {
    if (bytes <= 0L) return null
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024.0 && i < units.size - 1) {
        v /= 1024.0
        i++
    }
    return if (i == 0) "$bytes B" else "%.1f %s".format(v, units[i])
}

/** A gold-outlined action affordance (Add / rescan, Rescan). Focus + tap both go through the authority. */
@Composable
fun LibraryActionTile(
    label: String,
    supporting: String,
    focused: Boolean,
    authority: FocusAuthority,
    key: FocusKey,
    scope: FocusScope,
    onActivate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The one selection ring on a full-width CTA: a row ring, whose wash is the lift the accentGlow fill was.
    val palette = LocalMimirPalette.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            // The tile paints no fill, so the resting hairline and the 0.88 rest dim the old ring drew are its
            // whole resting shape: without them the unfocused choice is bare text on the card.
            .mimirSelectionRing(
                focused = focused,
                identity = label,
                shape = MimirRingShape.Row(MimirSpacing.radiusCard),
                restingOutline = true,
                restingAlpha = MimirFocusRestingAlpha,
            )
            .clip(RoundedCornerShape(MimirSpacing.radiusCard))
            .mimirFocusable(authority, key, scope)
            .mimirActivatable(label = label, authority = authority, key = key, onActivate = onActivate)
            .padding(MimirSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(MimirSpacing.xs),
    ) {
        MimirChromeText(
            label,
            style = MimirTypeScale.MenuAction,
            color = if (focused) palette.accentBright else palette.accent,
        )
        Text(supporting, style = MimirType.RowSupport, color = palette.textSecondary)
    }
}

/** How far the support line's text stands in from the edge of the plate it is set on, which is also how far the row's title stands in from the column's edge. */
internal val GamelistRowSupportInset: Dp = 7.dp

/**
 * The Library's LIST-view row -- box art, title, support facts, the launch-status chip and the
 * favourite marker, in one horizontal band.
 *
 * QA-16 moved this out of `LibraryScreen.kt`, where it was `private`, and made it public without
 * changing a line of its body. Onboarding's FAVORITES step renders the discovered library with this
 * exact composable, so the step reuse the existing list-view language rather than
 * grow a second card design. `internal` would not have been enough -- onboarding lives in `:app`, a
 * different Gradle module.
 */
@Composable
fun GamelistRow(
    row: GameRow,
    supportLabel: String?,
    badge: String?,
    launchStatusLine: String?,
    focused: Boolean,
    authority: FocusAuthority,
    key: FocusKey,
    onActivate: () -> Unit,
    // QA-16: defaulted so the Library's own call site is untouched. Onboarding's FAVORITES step passes its
    // own scope -- registering onboarding's rows in GamelistScope would put them in the same focus scope as
    // the real gamelist's, which is not a place onboarding's dispatch looks.
    scope: FocusScope = GamelistScope,
    /**
     * Walk-32i: false for the row of a game that has just left the list and is dissolving where it stood. It is painted as it
     * was and registers with no focus authority and takes no press: a key nothing lists any more must not be reachable.
     */
    interactive: Boolean = true,
    /** Walk-32h: the Library's own list hands its focused row a capture layer and a bounds writer here; others pass nothing. */
    modifier: Modifier = Modifier,
) {
    val palette = LocalMimirPalette.current
    val hero = palette.materials.hero
    val shape = RoundedCornerShape(LibraryListRowCorner)
    val metadataShape = RoundedCornerShape(8.dp)
    val targetLine = launchTargetLine(launchStatusLine)
    val supportFacts = distinctPreviewSupportFacts(supportLabel, row.genre)
    val supportLine = listOfNotNull(
        supportFacts.joinToString(" · ").ifBlank { null },
        targetLine,
    ).joinToString(" · ").ifBlank { null }
    val playedLine = row.lastPlayedMillis?.let { relativePlayed(it) }
    val statusChip = launchStatusChipLabel(launchStatusLine)
    val leatherAlpha = if (focused) 0.98f else 0.90f
    val rowFill = Brush.verticalGradient(
        listOf(
            hero.coverTop.copy(alpha = leatherAlpha),
            hero.coverMiddle.copy(alpha = leatherAlpha),
            hero.coverBottom.copy(alpha = leatherAlpha),
        ),
    )
    val rowBorderColor = hero.hardware.copy(alpha = 0.42f)
    val titleColor = hero.onCover
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MimirSpacing.minTarget)
            // The list's edge-aware reveal bridge is the sole scroll owner. A second
            // BringIntoViewRequester races it and leaves the familiar focus/scroll ghost trail.
            .then(if (interactive) Modifier.mimirFocusable(authority, key, scope, autoBringIntoView = false) else Modifier)
            // RC-37, since the ring migration (2026-09-28): the ring IS the outward reach `libraryListContentPadding`
            // reserves. A game row gleams as the family's game rows do (three sweeps and the sparkle); the ring
            // paints the leather so the wash lands over it, and the hardware hairline stays at rest, yielding to
            // the ring. Keyed on the game, never the slot.
            .mimirSelectionRing(
                focused = focused,
                identity = key,
                shape = MimirRingShape.Row(LibraryListRowCorner),
                gleam = MimirRingGleam.Game,
                restingOutline = true,
                outlineColor = rowBorderColor,
                fill = rowFill,
            )
            .clip(shape)
            .mimirActivatable(label = stringResource(R.string.library_shelf_open_description), enabled = interactive, authority = authority, key = key, onActivate = onActivate)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RomRowPlatformIcon(platformIdValue = row.platformId.value, focused = focused)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                // The title's text starts where the support line's text does (the plate's own inset in from the column's edge): one start edge for both lines
                // (alpha-cert sweep 10-09: the always-mounted favourite mark led the title and set it ~29 dp right of the line under it).
                modifier = Modifier.fillMaxWidth().padding(start = GamelistRowSupportInset),
                horizontalArrangement = Arrangement.spacedBy(MimirSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    row.title,
                    style = MimirType.RowTitle.copy(fontSize = 14.sp),
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // The favourite mark is always mounted (it animates presence, never mounts), so its width is reserved on every row; it trails the title, where it can
                // neither push the title off the line's start edge nor change when a game is starred.
                LibraryFavoriteMarker(favorite = row.isFavorite)
                if (playedLine != null) {
                    Text(
                        playedLine,
                        style = MimirType.RowSupport.copy(fontSize = 11.sp),
                        color = hero.onCover.copy(alpha = 0.62f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (supportLine != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            brush = Brush.horizontalGradient(
                                listOf(
                                    hero.coverMiddle.copy(alpha = 0.72f),
                                    hero.coverBottom.copy(alpha = 0.86f),
                                ),
                            ),
                            shape = metadataShape,
                        )
                        .border(
                            MimirSpacing.hairlineWidth,
                            hero.onCover.copy(alpha = 0.12f),
                            metadataShape,
                        )
                        .padding(horizontal = GamelistRowSupportInset, vertical = 3.dp),
                ) {
                    Text(
                        supportLine,
                        style = MimirType.RowSupport.copy(fontSize = 11.sp),
                        color = hero.onCover.copy(alpha = 0.78f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (badge != null) {
            CollapseBadge(badge)
        }
        if (statusChip != null) {
            LibraryStatusBadge(label = statusChip, ready = launchStatusReady(launchStatusLine), rowStyle = true)
        }
    }
}
