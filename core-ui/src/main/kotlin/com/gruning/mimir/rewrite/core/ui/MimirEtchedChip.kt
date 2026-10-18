package com.gruning.mimir.rewrite.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The etched chip's surface: the struck-metal plate's leather ground and metal rim ([drawMimirMetalPlate], the family the pad slab, the media plates and the
 * progress bar wear), cut to a capsule. [warmed] warms the top of the ground with the counter accent: the chip is SELECTED (the hub's own state), or pressed.
 * [primary] is the screen's main button (etched outline and accent text, with no filled blob): the SAME dark ground as
 * the unselected chip beside it, and the rim struck in the accent, whole, instead of the metal's gradient. Its word is [etchedChipInk] in the accent too. A primary is never
 * [warmed]: warming is the selected look, and a lighter plate reads as a fill. Every colour is a palette token. The brush is built once per size and the state is read in
 * the draw block only.
 *
 * [corner] cuts the plate to a rounded rectangle of that radius instead of a capsule: for the Files inspector's rectangular buttons. Null,
 * the default, is the capsule every other chip is.
 *
 * [focused] hands the rim to the focus ring: a focused chip draws its ground only, so the ring that hugs the plate is its ONE edge. A rim left under
 * the ring reads as two outlines with a gap (observed on a focused "Region · Any" chip). Pass the same value the ring gets. A primary's accent outline hands over
 * to the ring the same way. **The hand-over is eased on the ring's own clock** ([rememberEtchedChipRimHandover]): the rim is as strong as the ring is not, at every frame of
 * a focus move, so the destination chip never stands rimless waiting for its ring and the chip being left never wears rim and ring whole together .
 *
 * **[edgeFlush] makes it ONE band** (): pass it with the same value on the ring (`mimirSelectionRing(edgeFlush = true)`,
 * whose bright stroke then starts at the edge, abutting the rim) and the rim is not faded out but TAKES THE RING'S COLOUR as the hand-over runs, so the ring grows outward from
 * the line that was already there and two lines never show. Without it the rim fades out under the default ring, which stands half a dp off the edge.
 */
@Composable
fun Modifier.mimirEtchedChipSurface(
    palette: MimirThemePalette,
    warmed: Boolean = false,
    focused: Boolean = false,
    primary: Boolean = false,
    corner: Dp? = null,
    edgeFlush: Boolean = false,
): Modifier {
    val handover = rememberEtchedChipRimHandover(focused)
    return this.drawWithCache {
        val metal: Brush = if (primary) SolidColor(palette.accent) else platformConsoleIconBrush(palette)
        val restRimAlpha = if (primary) 1f else mimirMetalRimAlpha(warmed)
        val cornerPx = corner?.toPx()
        // The ring's own light ramp over the ring's own span (10 dp past the edge), so the rim and the ring agree at every point of the edge.
        val ringLit = if (edgeFlush) {
            val reach = 10.dp.toPx()
            mimirFocusRingLitBrush(palette.tileFocusRing, startY = -reach, endY = size.height + reach)
        } else {
            null
        }
        onDrawBehind {
            val h = handover()
            val plateCorner = cornerPx ?: (size.height / 2f)
            if (ringLit == null) {
                drawMimirMetalPlate(palette, metal, mimirEtchedChipRimAlpha(restRimAlpha, h), warmed, cornerPx = plateCorner)
            } else {
                // The rim is never faded: its metal gives way to the ring's colour, in place, as the ring arrives beside it, until it is the ring's own bright
                // stroke continued inward. The metal under the tint is thinned so the two together run straight from the rim at rest to that stroke (no dip half way).
                drawMimirMetalPlate(palette, metal, mimirEtchedChipRimUnderAlpha(restRimAlpha, h), warmed, cornerPx = plateCorner)
                if (h > 0f) drawMimirMetalRim(ringLit, mimirEtchedChipRimTintAlpha(h), plateCorner)
            }
        }
    }
}

/**
 * The etched chip's label: the filter chips' own type (SupportCompact, SemiBold). Every pressable that wears the chip's surface speaks it, so a button the player
 * finds in one place reads as the same control in the next (update: no filled blob, one chip family).
 */
val MimirEtchedChipLabelStyle: TextStyle = MimirTypeScale.SupportCompact.copy(fontWeight = FontWeight.SemiBold)

/**
 * The chip's text colour at rest: the leather's own ink. A warmed (selected) chip, and a [primary] one (the screen's main button), read in
 * [MimirThemePalette.accentBright] instead.
 */
fun MimirThemePalette.etchedChipInk(warmed: Boolean = false, primary: Boolean = false): Color = if (warmed || primary) accentBright else materials.hero.onCover

/**
 * One etched chip's visible plate: the struck-metal surface ([mimirEtchedChipSurface]) with the chip's padding (md across, sm down) around its content on one centred row.
 * A pressable chip puts this INSIDE its own hit box (at least 48 dp each way), so the target is larger than the plate and nothing visible grew to earn it. The plate has no fill
 * of its own; [warmed] is the selected look and [primary] the main button's. A focus ring goes in [modifier], which is applied BEFORE the surface: the ring then hugs the
 * plate itself, with no inset, and its wash is under the opaque plate (update). Pass [focused] the same value the ring gets, so the rim hands over to it.
 */
@Composable
fun MimirEtchedChipPlate(
    modifier: Modifier = Modifier,
    warmed: Boolean = false,
    focused: Boolean = false,
    primary: Boolean = false,
    palette: MimirThemePalette = LocalMimirPalette.current,
    /** Pass the same value the ring gets (`mimirSelectionRing(edgeFlush = ...)`): see [mimirEtchedChipSurface]. */
    edgeFlush: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .mimirEtchedChipSurface(palette, warmed = warmed, focused = focused, primary = primary, edgeFlush = edgeFlush)
            .padding(horizontal = MimirSpacing.md, vertical = MimirSpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(MimirSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * The footer hint's keycap (the footer keycaps must not be tinted): the etched chip family's plate (struck metal with its own rim, cut to a capsule, a circle for one
 * glyph) with the key's name in the chip's own ink. The ONE keycap every footer draws, so every footer follows it: the Settings and Browse shell's legend, the shelf and gamelist legends
 * and the Files footer. It is the unselected chip, not a warmed or primary one: a key is a label, not a state, and nothing about it is lit. [compact] is the 720 dp squares' smaller plate.
 * The caller's own gap to the word beside it goes in [modifier]; the plate adds none.
 */
@Composable
fun MimirFooterKeycap(
    key: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    palette: MimirThemePalette = LocalMimirPalette.current,
) {
    val plate = if (compact) FooterKeycapCompactPlate else FooterKeycapPlate
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = plate, minHeight = plate)
            .mimirEtchedChipSurface(palette)
            .padding(horizontal = if (compact) 6.dp else 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = key,
            style = if (compact) MimirEtchedChipLabelStyle.copy(fontSize = MimirTypeStep.Micro) else MimirEtchedChipLabelStyle,
            color = palette.etchedChipInk(),
            maxLines = 1,
        )
    }
}

/** The keycap plate's height (and its least width), in the two sizes the shell draws it. */
private val FooterKeycapPlate = 24.dp
private val FooterKeycapCompactPlate = 20.dp

/** What a screen reader says for a count chip's "12/40": "12 of 40 achievements". */
fun mimirAchievementChipDescription(label: String): String {
    val parts = label.split('/')
    return if (parts.size == 2 && parts.all { it.trim().all(Char::isDigit) && it.isNotBlank() }) {
        "${parts[0].trim()} of ${parts[1].trim()} achievements"
    } else {
        "$label achievements"
    }
}

/**
 * A compact etched chip for a game's achievement count ("12/40"), led by the Mimir medal, the product's own achievement glyph. It is one line that never
 * shrinks or ellipsizes: a chip that does not fit beside a name is wrapped onto the next line by its host, never cut. Non-interactive.
 */
@Composable
fun MimirAchievementChip(
    label: String,
    modifier: Modifier = Modifier,
    palette: MimirThemePalette = LocalMimirPalette.current,
) {
    Row(
        // The semantics sit OUTSIDE the padding, so the chip's accessibility bounds (a screen reader's focus box) are the whole chip, not just its text.
        modifier = modifier
            .semantics(mergeDescendants = true) { contentDescription = mimirAchievementChipDescription(label) }
            .mimirEtchedChipSurface(palette)
            .padding(horizontal = MimirSpacing.sm, vertical = MimirSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(MimirSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MimirAchievementMedal(state = MimirMedalState.EARNED, size = MimirAchievementChipMedalSize, palette = palette)
        Text(
            text = label,
            style = MimirTypeScale.MicroStatus,
            color = palette.materials.hero.onCover,
            maxLines = 1,
            softWrap = false,
        )
    }
}

private val MimirAchievementChipMedalSize = MimirSpacing.md + MimirSpacing.xs / 2
