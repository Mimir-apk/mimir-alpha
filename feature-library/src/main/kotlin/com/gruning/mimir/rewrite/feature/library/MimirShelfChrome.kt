package com.gruning.mimir.rewrite.feature.library

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.gruning.mimir.rewrite.core.input.mimirActivatable
import com.gruning.mimir.rewrite.core.ui.HeaderRule
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.LocalMimirControllerHintLabels
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.MimirHeaderRuleHeightDp
import com.gruning.mimir.rewrite.core.ui.MimirMarkBadge
import com.gruning.mimir.rewrite.core.ui.MimirFooterKeycap
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.theme.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.theme.MimirType

/**
 * Mimir Alpha :feature-library — the shelf CHROME (Lane L6).
 *
 * The console frame ABOVE and BELOW the coverflow: the identity header (rune badge + `MIMIR` wordmark +
 * `ᛗᛁᛗᛁᚱ · THE WELL OF MEMORY` sub-eyebrow + the right-side `N platforms · M games · HH:MM` stat line), a
 * [BeamSeparator] (the L2 core-ui atmosphere rule) carrying a left section eyebrow, the mode strip
 * (segmented `EMULATION | ANDROID GAMES` pill + a light `ADD GAMES` button — ANDROID APPS retired), and the footer control legend (`◄► Shelf · L/R Mode · A Open · X Favorites ·
 * Start Settings`) + the Elder-Futhark runic signature far-right.
 *
 * Ports the shipping WebView chrome (`.eok-identity` / `.eok-mark` / `.eok-rune-line` / `.eok-mode-switch` /
 * `.eok-shelf-runes` in `styles.css` + `index.html`) 1:1. EVERY colour reads from the live
 * [LocalMimirPalette] so the whole frame re-skins per theme with zero rework — the badge glow, the wordmark
 * tint, the active-segment fill, the beam, the runes all follow the accent. Type is [MimirType], gaps are
 * [MimirSpacing]; no surface hand-types a number.
 *
 * FOCUS/POSTURE (spec §2): this chrome renders the live mode state;
 * mode switching is owned by the library dispatch (L/R), and the coverflow owns focus. No Compose `focusable()`.
 * PERF : the badge Mannaz mark is a single [drawBehind] of stroked
 * lines (no bitmap, no per-frame alloc); nothing here reacts to focus, so a D-pad sweep never re-draws it.
 */

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// TUNING — transcribed from styles.css, all provisional (owed a Thor/RP5 legibility pass)
// ─────────────────────────────────────────────────────────────────────────────────────────────────

// QA-25 (policy 2026-09-16, wide): was "LIBRARY SHELF". "Shelf" named three different things
// across the product -- this screen, a platform row inside it, and Home's favourites surface -- so the
// word could not tell a player which one they were looking at. This screen IS the library; the word
// added nothing here and collided everywhere else. Its words are `library_shelf_title` (localization step 3).

/** The footer runic signature — WELL OF MEMORY in Elder Futhark (shipping `.eok-shelf-runes`). */
private const val RunicSignature = "ᚹᛖᛚᛚ ᛟᚠ ᛗᛖᛗᛟᚱᚤ"

internal const val ShelfIdentityRowHeightDp = 36
internal const val ShelfIdentityRuleGapDp = 6
internal const val ShelfHeaderRuleHeightDp = MimirHeaderRuleHeightDp
internal const val ShelfIdentityHeaderHeightDp =
    ShelfIdentityRowHeightDp + ShelfIdentityRuleGapDp + ShelfHeaderRuleHeightDp
private val HeaderStatsLaneWidth = 210.dp

/**
 * The footer control legend (spec §1.5 / shipping `.eok-hint-keys`): each controller token beside its word, in the language of [res].
 * The tokens (the arrows, L/R, A, X, START, SELECT, Y, B) are never translated; the words are stored in sentence case.
 *
 * QA-25: the arrows' word was "SHELF", which read as "go to the Home shelf" to a player onboarding had just taught that
 * word to. Named Platforms to match this legend's own established grammar -- the native shelf's arrows
 * say Apps and the gamelist's say Games, both naming WHAT THE ARROWS MOVE AMONG rather than the surface
 * they are on. LibraryDispatch's branch for this scope is commented "-- Platforms shelf --" and
 * LEFT/RIGHT walks the platform ring, so this is that same rule applied consistently.
 */
private fun footerHints(res: Resources): List<Pair<String, String>> = listOf(
    "◂ ▸" to res.getString(R.string.library_hint_platforms),
    "L/R" to res.getString(R.string.library_hint_mode),
    "A" to res.getString(R.string.library_hint_open),
    "X" to res.getString(R.string.library_hint_favorites),
    "START" to res.getString(R.string.library_hint_settings),
)

// One grammar with the gamelist, 2026-08-25: SELECT marks, Y opens the media menu, START hides.
// This shelf is one shoulder-press from the emulation shelf, where Y has opened a tile's art menu
// since 2026-07-15 — the two running opposite bindings is the observed inconsistency.
private fun nativeFooterHints(res: Resources): List<Pair<String, String>> = listOf(
    "◂ ▸" to res.getString(R.string.library_hint_apps),
    "L/R" to res.getString(R.string.library_hint_mode),
    "A" to res.getString(R.string.library_hint_open),
    "SELECT" to res.getString(R.string.library_hint_favorite),
    "Y" to res.getString(R.string.library_hint_media),
    "START" to res.getString(R.string.library_hint_hide),
)

/** The shelf's control-legend hints, for the collapsible [HintRailButton] (emulation vs native modes), in the language of [res]. */
internal fun shelfFooterHints(res: Resources, nativeMode: Boolean): List<Pair<String, String>> =
    if (nativeMode) nativeFooterHints(res) else footerHints(res)

internal fun gamelistFooterHints(
    res: Resources,
    inFavorites: Boolean,
    favoritesOnly: Boolean,
    canSwitchFavoriteSegments: Boolean,
    hasFavoritesInPlatform: Boolean = true,
): List<Pair<String, String>> = buildList {
    add("A" to res.getString(R.string.library_hint_open))
    add("B" to res.getString(R.string.library_hint_back))
    // In the per-platform coverflow, LEFT/RIGHT moves through games (the design's `◂▸ Games`).
    if (!inFavorites) add("◂▸" to res.getString(R.string.library_hint_games))
    if (inFavorites) {
        if (canSwitchFavoriteSegments) add("L/R" to res.getString(R.string.library_hint_category))
    } else {
        add("L/R" to res.getString(R.string.library_hint_platforms))
    }
    // SELECT marks the favorite, Y opens Customize Media on the focused game
    // — the media seat's own action, without walking focus over to the seat first.
    add("SELECT" to res.getString(R.string.library_hint_favorite))
    if (!inFavorites && (favoritesOnly || hasFavoritesInPlatform)) {
        add("X" to res.getString(if (favoritesOnly) R.string.library_hint_show_all else R.string.library_hint_show_favorites))
    }
    add("Y" to res.getString(R.string.library_hint_media))
    // CORRECTED, and it was wrong before the remap too: this line read `SELECT to HIDE` while the
    // dispatcher has bound hide to START inside a gamelist all along (its own comment says so --
    // *"START owns hide inside every gamelist"*). A legend naming the wrong key is worse than none,
    // because it is the only thing telling him which key to press.
    add("START" to res.getString(R.string.library_hint_hide))
}


// ─────────────────────────────────────────────────────────────────────────────────────────────────
// (a) IDENTITY HEADER — rune badge + LIBRARY SHELF title, fixed stat lane, beam separator
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * The shelf identity header: a fixed left title lane (`LIBRARY SHELF`), a fixed quiet inventory/time lane,
 * and a fixed separator below. Text ellipsizes inside its lane rather than moving the separator or tools rail.
 *
 * @param platformCount how many platforms are on the shelf (canon `N platforms`).
 * @param gameCount total games across the library (canon `N games`, local-only policy).
 * @param timeLabel a pre-formatted wall-clock string (e.g. `9:41 AM`) — the caller owns formatting so this
 *   composable stays locale/clock-agnostic and never allocates on recomposition.
 * @param statLabel optional full right-side stat line for non-emulation shelves; when null, the canonical
 *   `N platforms · M games · <time>` line is derived from [platformCount], [gameCount], and [timeLabel].
 * @param modifier caller sizing — typically `Modifier.fillMaxWidth()` with the screen gutter.
 * @param sectionEyebrow the left-of-beam section label (`CHOOSE A PLATFORM` / `ANDROID APPS`); pass `null`
 *   to draw a bare beam with no eyebrow slot.
 * @param palette the active theme; defaults to the CompositionLocal-provided one (re-skins per theme).
 */
@Composable
fun MimirIdentityHeader(
    platformCount: Int,
    gameCount: Int,
    timeLabel: String,
    statLabel: String? = null,
    modifier: Modifier = Modifier,
    sectionEyebrow: String? = stringResource(R.string.library_shelf_eyebrow_platforms),
    // How far the void/crimson molten separator bleeds past the header's content width (the shelf gutter), so it
    // reaches the physical screen edges. Default 0 keeps it content-width for callers with no full-bleed intent.
    bleed: Dp = 0.dp,
    compact: Boolean = false,
    palette: MimirThemePalette = LocalMimirPalette.current,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // ── Lockup row: identity left, stat line right ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(ShelfIdentityRowHeightDp.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // LEFT — fixed brand mark + product title lane.
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compact) MimirSpacing.sm else MimirSpacing.md),
            ) {
                MimirMarkBadge(palette = palette, sizeDp = if (compact) 28.dp else 32.dp)
                MimirChromeText(
                    text = stringResource(R.string.library_shelf_title),
                    style = if (compact) MimirTypeScale.TitleCapsCompact else MimirTypeScale.TitleCaps,
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }

            // RIGHT — stable width, so inventory updates cannot recenter the title or separator.
            Text(
                text = statLabel ?: statLine(platformCount, gameCount, timeLabel),
                style = MimirType.RowSupport,
                color = palette.textSecondary,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(if (compact) 112.dp else HeaderStatsLaneWidth),
            )
        }

        Spacer(Modifier.height(ShelfIdentityRuleGapDp.dp))

        // ── The top header rule: shipping draws a clean line here; the diamond belongs to the shelf-head gap. ──
        Box(Modifier.fillMaxWidth()) {
            HeaderRule(palette = palette, bleed = bleed)
            if (sectionEyebrow != null) {
                // The eyebrow sits over the beam's left, on the section-header baseline (shipping
                // `.eok-shelf-head > .eok-eyebrow` left, ornament stretching across). A quiet base scrim so
                // the accent letters read where the beam brightens.
                MimirChromeText(
                    text = sectionEyebrow,
                    style = MimirTypeScale.RunicEyebrow.copy(letterSpacing = 0.18.em),
                    color = palette.accent,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .background(palette.surfaceBase.copy(alpha = 0.72f))
                        .padding(end = MimirSpacing.sm),
                )
            }
        }
    }
}

@Composable
fun ShelfHeadOrnament(
    modifier: Modifier = Modifier,
    palette: MimirThemePalette = LocalMimirPalette.current,
) {
    Box(
        modifier
            .height(18.dp)
            .drawWithCache {
                val cy = size.height * 0.5f
                val lineInset = 10f * density
                val lineStart = lineInset.coerceAtMost(size.width * 0.5f)
                val lineEnd = (size.width - lineInset).coerceAtLeast(lineStart)
                val lineBand = 5f * density
                val topLineY = cy - lineBand * 0.5f
                val bottomLineY = cy + lineBand * 0.5f
                val topLineBrush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        palette.emberSoft.copy(alpha = 0.55f),
                        palette.accentBright.copy(alpha = 0.7f),
                        palette.emberSoft.copy(alpha = 0.55f),
                        Color.Transparent,
                    ),
                    startX = lineStart,
                    endX = lineEnd,
                )
                val bottomLineBrush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        palette.outline.copy(alpha = 0.4f),
                        palette.accent.copy(alpha = 0.5f),
                        palette.outline.copy(alpha = 0.4f),
                        Color.Transparent,
                    ),
                    startX = lineStart,
                    endX = lineEnd,
                )
                val d = 9f * density
                val center = Offset(size.width * 0.5f, cy)
                val diamondTop = Offset(center.x - d * 0.5f, center.y - d * 0.5f)
                val diamondBrush = Brush.linearGradient(
                    colors = listOf(palette.accentBright, palette.accent, palette.tileRing),
                    start = diamondTop,
                    end = Offset(diamondTop.x + d, diamondTop.y + d),
                )
                val diamondCornerRadius = CornerRadius(1.5f * density, 1.5f * density)
                val diamondSize = Size(d, d)
                val rimInset = 1.15f * density
                val rimTopLeft = Offset(diamondTop.x + rimInset, diamondTop.y + rimInset)
                val rimSize = Size(d - rimInset * 2f, d - rimInset * 2f)
                val rimColor = palette.textPrimary.copy(alpha = 0.78f)
                val rimStyle = Stroke(width = 0.65f * density)
                val lineWidth = 1f * density

                onDrawBehind {
                    drawLine(
                        brush = topLineBrush,
                        start = Offset(lineStart, topLineY),
                        end = Offset(lineEnd, topLineY),
                        strokeWidth = lineWidth,
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        brush = bottomLineBrush,
                        start = Offset(lineStart, bottomLineY),
                        end = Offset(lineEnd, bottomLineY),
                        strokeWidth = lineWidth,
                        cap = StrokeCap.Round,
                    )
                    rotate(45f, pivot = center) {
                        drawRoundRect(
                            brush = diamondBrush,
                            topLeft = diamondTop,
                            size = diamondSize,
                            cornerRadius = diamondCornerRadius,
                        )
                        drawRoundRect(
                            color = rimColor,
                            topLeft = rimTopLeft,
                            size = rimSize,
                            cornerRadius = diamondCornerRadius,
                            style = rimStyle,
                        )
                    }
                }
            },
    )
}


/** `N platforms · M games · <time>` — canon plurals, the shipping right-side `.eok-shelf-total`. */
private fun statLine(platformCount: Int, gameCount: Int, timeLabel: String): String {
    val plats = "$platformCount ${if (platformCount == 1) "platform" else "platforms"}"
    val games = "$gameCount ${if (gameCount == 1) "game" else "games"}"
    return "$plats · $games · $timeLabel"
}

/**
 * The identity badge — a rounded-square, themed frame with the ᛗ Mannaz mark drawn dead-centre. Ports the
 * shipping `.eok-mark`: 32×32, radius 9px, a neutral dark navy→bronze fill, a themed hairline
 * ([MimirThemePalette.emberSoft]) + a themed outer glow ([MimirThemePalette.tileGlow]); the ᛗ inside is
 * stroked in the theme accent so the mark re-tints per theme while the frame stays neutral.
 */
@Composable
private fun MannazBadge(palette: MimirThemePalette) {
    MimirMarkBadge(palette = palette)
}

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// (b) MODE STRIP — segmented EMULATION | ANDROID GAMES pill + light ADD GAMES button
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * The shelf mode strip: a segmented pill (`EMULATION | ANDROID GAMES`) on the left and a
 * light `ADD GAMES` button on the right. Ports the shipping `.eok-mode-switch` + `.eok-add-platform`.
 * The Android Apps shelf mode is retired — Android apps stay launchable via the dock's Apps seat and stay
 * classified via [NativeShelfKind.APP]; they just no longer have a shelf lane of their own.
 *
 * The pill renders both segments but only [active] is highlighted. Controller L/R mode switching is handled by the
 * library's single input dispatch, not by focusable segment buttons; a TAP on an inactive segment reports its label
 * to [onSelect] (the host runs the same focus-stamped switch R1/L1 do), and the active segment is inert to touch.
 * The active segment uses the shipping treatment — a faint accent fill (`rgba(accent,.12)`) + accent text; inactive
 * segments are muted.
 *
 * @param active the highlighted mode, by its identity ([LibraryShelfMode.stripLabel]); defaults to the emulation mode.
 * @param modifier caller sizing — typically wrapped in a `Row` with the identity header's gutter.
 * @param palette the active theme; defaults to the CompositionLocal-provided one (re-skins per theme).
 * @param onSelect a tap on an INACTIVE segment, with that segment's identity ([LibraryShelfMode.stripLabel]); never called for the [active] one.
 */
@Composable
fun ShelfModeStrip(
    active: String = LibraryShelfMode.EMULATION.stripLabel,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    palette: MimirThemePalette = LocalMimirPalette.current,
    onSelect: (String) -> Unit = {},
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ── Mode tabs ──
        // LIB-02b: the shipping rail is FREE TEXT tabs — the active mode wears a
        // subtle accent chip, the others are muted text — NOT a bordered segmented box. Mimir Alpha had wrapped
        // them in a surfaceRaised container that deviated from that layout; this restores the loose tabs.
        Row(
            modifier = Modifier.defaultMinSize(minHeight = 36.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.Start),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The three shelf modes, in the shipping order (spec §1.2). The strip reports a tap by the mode's identity
            // ([LibraryShelfMode.stripLabel]); the words on the tab are the mode's resource, in the host's language.
            LibraryShelfMode.entries.forEach { mode ->
                ModeSegment(
                    // Full labels on compact too (): about 295 dp with the Add pill, in the RG's 524.7 dp row.
                    label = stringResource(mode.labelRes),
                    active = mode.stripLabel == active,
                    palette = palette,
                    compact = compact,
                    onSelect = { onSelect(mode.stripLabel) },
                    modifier = Modifier.height(28.dp),
                )
            }
        }
        // The ADD GAMES affordance is rendered by the host (a FUNCTIONAL, controller-reachable pill in the
        // shelf scope) so it isn't duplicated here — this strip is the mode segments only.
    }
}

/**
 * One segment of the mode pill — active = faint accent fill + accent text; inactive = muted, transparent. An inactive
 * segment answers a tap through [mimirActivatable] (no Compose focus node, no focus write: the host owns focus); the
 * active one is inert, so tapping it is a no-op and it reports no click action.
 */
@Composable
private fun ModeSegment(
    label: String,
    active: Boolean,
    palette: MimirThemePalette,
    compact: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(7.dp))
            .mimirActivatable(label = label, enabled = !active, role = Role.Tab, onActivate = onSelect)
            .background(if (active) palette.accent.copy(alpha = 0.14f) else Color.Transparent)
            .padding(horizontal = if (compact) 6.dp else 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        MimirChromeText(
            text = label,
            style = MimirTypeScale.RunicEyebrowCompact,
            color = if (active) palette.accentBright else palette.textMuted,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// (c) FOOTER LEGEND — control legend + Elder-Futhark runic signature
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * The shelf footer: the control legend (`◄► Shelf · L/R Mode · A Open · X Favorites · Start Settings`) on
 * the left and the Elder-Futhark runic signature (`ᚹᛖᛚᛚ ᛟᚠ ᛗᛖᛗᛟᚱᚤ` — "well of memory") far-right. Ports
 * the shipping `.eok-hints` legend + `.eok-shelf-runes` signature; both muted, the runes at a tracked whisper.
 *
 * @param modifier caller sizing — typically `Modifier.fillMaxWidth()` pinned to the screen bottom gutter.
 * @param palette the active theme; defaults to the CompositionLocal-provided one (re-skins per theme).
 */
@Composable
fun ShelfFooterLegend(
    nativeMode: Boolean = false,
    modifier: Modifier = Modifier,
    palette: MimirThemePalette = LocalMimirPalette.current,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Control legend — real keycaps + quiet labels (shipping `.eok-hint-keys b`).
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MimirSpacing.lg),
        ) {
            val hints = shelfFooterHints(libraryResources(), nativeMode)
            hints.forEach { (key, label) ->
                FooterHint(key = key, label = label, palette = palette)
            }
        }

        // Runic signature — themed muted, tracked (shipping `.eok-shelf-runes`, letter-spacing .1em).
        Text(
            text = RunicSignature,
            style = MimirTypeScale.MicroStatus,
            color = palette.accent.copy(alpha = 0.55f),
            letterSpacing = 0.16.em,
        )
    }
}

/**
 * Visual-only gamelist footer, matching shipping `.eok-gl-foot`.
 *
 * It deliberately does not register focusable keys: controller behavior is owned by the existing
 * `GamelistScope` dispatch path. Inside Favorites, `L/R` says `Category` and the `X Show favorites` hint is hidden.
 */
@Composable
fun GamelistFooterLegend(
    inFavorites: Boolean,
    favoritesOnly: Boolean = false,
    canSwitchFavoriteSegments: Boolean = false,
    modifier: Modifier = Modifier,
    palette: MimirThemePalette = LocalMimirPalette.current,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MimirSpacing.lg),
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MimirSpacing.lg),
        ) {
            gamelistFooterHints(
                res = libraryResources(),
                inFavorites = inFavorites,
                favoritesOnly = favoritesOnly,
                canSwitchFavoriteSegments = canSwitchFavoriteSegments,
            ).forEach { (key, label) ->
                FooterHint(key = key, label = label, palette = palette)
            }
        }
    }
}

@Composable
private fun FooterHint(key: String, label: String, palette: MimirThemePalette) {
    val projectedKey = LocalMimirControllerHintLabels.current.labelFor(key)
    if (projectedKey.isBlank()) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        MimirFooterKeycap(key = projectedKey, palette = palette)
        MimirChromeText(
            text = label,
            style = MimirTypeScale.ControlHintCompact,
            color = palette.textMuted,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// Letter-spacing uses the `androidx.compose.ui.unit.em` extension (imported), so tracking scales with
// font size exactly like the CSS `em` values it ports.
// ─────────────────────────────────────────────────────────────────────────────────────────────────
