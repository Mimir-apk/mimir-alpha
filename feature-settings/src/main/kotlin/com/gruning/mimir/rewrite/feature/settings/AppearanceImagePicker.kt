package com.gruning.mimir.rewrite.feature.settings

import com.gruning.mimir.rewrite.core.ui.select.PickerArt
import com.gruning.mimir.rewrite.core.ui.select.rememberPickerArt
import com.gruning.mimir.rewrite.core.ui.select.pickerThumbnailKey

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.input.isLitFocusIn
import com.gruning.mimir.rewrite.core.input.mimirActivatable
import com.gruning.mimir.rewrite.core.input.mimirFocusable
import com.gruning.mimir.rewrite.core.model.MimirMarkPlacement
import com.gruning.mimir.rewrite.core.ui.LocalMimirControllerHintLabels
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.MimirControlHintText
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.mimirGestureHandleClearance
import com.gruning.mimir.rewrite.core.ui.MimirRingShape
import com.gruning.mimir.rewrite.core.ui.MimirSoftHyphenTextStyle
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirTypeStep
import com.gruning.mimir.rewrite.core.ui.MimirWordEllipsisText
import com.gruning.mimir.rewrite.core.ui.mimirHyphenateWideWords
import com.gruning.mimir.rewrite.core.ui.mimirSelectionRing
import com.gruning.mimir.rewrite.core.ui.theme.MimirType
import java.io.File
import java.util.Locale

/**
 * The shared shell of the two appearance-image pickers (concept A): Background and Mimir button.
 * Full screen over the theme gallery's OPAQUE backing (a picker that shows another screen through
 * it cannot be used to judge a look), on the gallery's font-scale layout: a `ᛗ · TITLE` header with a subline, a
 * STAGE (the top ~55%, the picker's own preview), a row of TILES -- the default, the saved images, `+ Add image` --
 * an optional ROWS slot beneath (Dim and Blur for the background), a note line, and the `A USE · X REMOVE · B BACK`
 * footer.
 *
 * FOCUS/POSTURE: the host pushes [scope] before opening and pops it on close. Tiles register in [scope] through
 * `mimirFocusable` + `mimirActivatable` (never Compose `focusable()`), wear the one selection ring on the card's
 * corner with no scale, as the theme gallery's cards do, and announce their name and state ("Your image 2, in use").
 * A tap does what A does; a FOCUSED saved tile shows a 48 dp ✕ for touch. The picker takes data and callbacks --
 * never a store -- and previews only on its stage: nothing here writes a global preview.
 */

/** One tile of an appearance picker. */
sealed interface AppearancePickerTile {
    val key: FocusKey

    /** The theme's own look: Atmosphere, or the rune. Reads DEFAULT. */
    data class Default(override val key: FocusKey, val label: String) : AppearancePickerTile

    /**
     * A saved image. [path] is null when the app's copy is missing or corrupt (the tile reads PREVIEW UNAVAILABLE and X
     * removes it); [usedBy] names the OTHER themes whose committed choice this is; [placement] is where the player put a
     * Mimir-button mark on its coin , which that picker's tile draws, and a background has none.
     */
    data class Saved(
        override val key: FocusKey,
        val id: String,
        val label: String,
        val path: String?,
        val current: Boolean,
        val usedBy: List<String> = emptyList(),
        val placement: MimirMarkPlacement = MimirMarkPlacement.Default,
    ) : AppearancePickerTile

    /** The Upload tile (a `+` over the word): A opens the import. */
    data class Add(override val key: FocusKey) : AppearancePickerTile
}

internal const val AppearanceTileBadgeDefault = "DEFAULT"
internal const val AppearanceTileBadgeCurrent = "CURRENT"
internal const val AppearanceTileBadgeSaved = "SAVED"
internal const val AppearanceTileBadgeUnavailable = "PREVIEW UNAVAILABLE"
internal const val AppearancePickerControlHint = "A USE · X REMOVE · B BACK"
/** The footer on a tile X cannot remove and A applies (a picker's default or built-in tile -- Atmosphere, the rune): no X. */
internal const val AppearancePickerControlHintNoRemove = "A USE · B BACK"
/**
 * The footer on the Upload tile : A there opens the file picker, it does not use anything, so the footer names the word the tile itself wears
 * ([AppearanceUploadLabel]). No X either: there is nothing on the tile to remove.
 */
internal const val AppearancePickerControlHintUpload = "A UPLOAD · B BACK"

/**
 * The Upload tile: a large `+` over one word, centred in the tile with no inner box, so nothing can clip at any
 * font scale. What it announces is [AppearanceUploadSpokenLabel].
 */
internal const val AppearanceUploadGlyph = "+"
internal const val AppearanceUploadLabel = "Upload"
internal const val AppearanceUploadSpokenLabel = "Upload an image"

/** The badge on a tile, Customize Media's vocabulary: DEFAULT · CURRENT · SAVED · PREVIEW UNAVAILABLE. */
internal fun appearanceTileBadge(tile: AppearancePickerTile, defaultIsCurrent: Boolean): String? = when (tile) {
    is AppearancePickerTile.Default -> if (defaultIsCurrent) AppearanceTileBadgeDefault else AppearanceTileBadgeDefault
    is AppearancePickerTile.Saved -> when {
        tile.path == null -> AppearanceTileBadgeUnavailable
        tile.current -> AppearanceTileBadgeCurrent
        else -> AppearanceTileBadgeSaved
    }
    is AppearancePickerTile.Add -> null
}

/** What a tile announces: its name and state ("Your image 2, in use"; "Atmosphere, the default"). */
internal fun appearanceTileSpokenLabel(tile: AppearancePickerTile, defaultIsCurrent: Boolean): String = when (tile) {
    is AppearancePickerTile.Default -> if (defaultIsCurrent) "${tile.label}, the default, in use" else "${tile.label}, the default"
    is AppearancePickerTile.Saved -> when {
        tile.path == null -> "${tile.label}, preview unavailable"
        tile.current -> "${tile.label}, in use"
        tile.usedBy.isNotEmpty() -> "${tile.label}, saved, used by ${tile.usedBy.joinToString()}"
        else -> "${tile.label}, saved"
    }
    is AppearancePickerTile.Add -> AppearanceUploadSpokenLabel
}

/** The stage band's test tag: what a render test measures the stage's contents against. */
internal const val AppearancePickerStageTag = "appearance-picker-stage"

/** The most tiles a picker lays out: the default, six saved images and Add. */
internal const val AppearancePickerMaxTiles = 8

/** The lines a tile's name may wrap to. */
internal const val AppearancePickerLabelLines = 2

/** The air a tile's mark keeps above its state word: a 36 dp coin and 2 dp of air a side. */
internal const val AppearancePickerArtMinDp = 40f

/** The state word's one line, in sp at the user's font scale: the micro type's 9 sp and its leading. */
internal const val AppearancePickerBadgeLineSp = 11f

/** The band under a tile's art that holds its state word: its one line and 2 dp of air. Zero when the art fills the whole tile and the word stands on it. */
internal fun appearancePickerBadgeBandDp(fontScale: Float): Float = AppearancePickerBadgeLineSp * fontScale.coerceAtLeast(1f) + 2f

/**
 * The fixed bands the shell lays out, as numbers. Only what the shell READS is here: the header takes the height its own lines measure (a subtitle may wrap to two), and the stage is the
 * `weight(1f)` that takes whatever every other band leaves, so neither has a height of its own to pin.
 */
internal data class AppearancePickerGeometry(
    val horizontalPaddingDp: Float,
    val verticalPaddingDp: Float,
    val sectionGapDp: Float,
    val tileWidthDp: Float,
    val tileHeightDp: Float,
    /** The tile's image box: the art, and under it the [badgeBandDp] when the state word has a band of its own. */
    val tileImageHeightDp: Float,
    val tileLabelHeightDp: Float,
    val tileGapDp: Float,
    /** The band under the art that holds the state word, so the word never stands on the art; 0 when the art fills the tile. */
    val badgeBandDp: Float,
    val rowsHeightDp: Float,
    val rowsSideBySide: Boolean,
    val noteHeightDp: Float,
    val footerHeightDp: Float,
    val compact: Boolean,
)

/**
 * The picker's fixed bands: tiles, rows, note, footer -- the theme gallery's type reservation (`14 * fontScale + 2` footer) and its 16/10 dp paddings, so the two full-screen pickers
 * share one rhythm. Dim and Blur sit side by side from 560 dp of width, stacked beneath it. Compact (under 480 dp wide) shrinks the tiles so a square handheld keeps a stage; what
 * those bands leave is the stage, laid out by the shell itself. Typography itself never tracks the viewport.
 *
 * [artFillsTile] is a tile whose art covers the whole of it (the Background picker's photo): the state word then stands ON that art, as a small chip, and the tile is the size it was.
 * Otherwise the art is a mark with room of its own (the Mimir button's coin) and the state word has its own band under it ([appearancePickerBadgeBandDp]), so the word never covers
 * the art: the image box is as tall as the art's [AppearancePickerArtMinDp] and that band, never shorter than the 48 or 64 dp it was.
 */
internal fun solveAppearancePickerGeometry(
    viewportWidthDp: Float,
    fontScale: Float,
    rowCount: Int,
    tileCount: Int = AppearancePickerMaxTiles,
    artFillsTile: Boolean = false,
): AppearancePickerGeometry {
    val width = viewportWidthDp.coerceAtLeast(1f)
    val scale = fontScale.coerceAtLeast(1f)
    val compact = width < 480f
    val horizontalPadding = 16f
    val verticalPadding = 10f
    val sectionGap = 8f
    val footerHeight = 14f * scale + 2f
    // A tile's name wraps to two lines at its spaces and is cut at a word only past that (review 2026-10-13: "Your mark 1" ended "Your ..." on a compact tile),
    // so the band holds two lines of the 12 sp label at the user's font scale -- and the face's own leading, which the text engine adds once to a paragraph
    // (0.25 of the font size: two 12 sp lines measure 26.5 dp at font 1.0, and a band of 26 made the engine drop the second line and ellipsize the first).
    val tileLabelHeight = AppearancePickerLabelLines * 12f * scale + 3f * scale + 2f
    val badgeBand = if (artFillsTile) 0f else appearancePickerBadgeBandDp(scale)
    val tileBaseHeight = if (compact) 48f else 64f
    val tileImageHeight = maxOf(tileBaseHeight, if (artFillsTile) 0f else AppearancePickerArtMinDp + badgeBand)
    val tileHeight = tileImageHeight + tileLabelHeight + 2f
    val tileGap = 8f
    val contentWidth = (width - horizontalPadding * 2f).coerceAtLeast(1f)
    // Up to eight tiles (default + six saved + add) share the width; each keeps a 16:10 image and a 48 dp floor. A compact screen shares it among the tiles
    // it actually has, so a name and a badge get the room five tiles leave (59 dp, not the 48 dp the eight-tile rule left); a wider one keeps the eight-way share.
    val slots = if (compact) tileCount.coerceIn(1, AppearancePickerMaxTiles) else AppearancePickerMaxTiles
    val tileWidth = ((contentWidth - tileGap * (slots - 1)) / slots).coerceAtLeast(48f).coerceAtMost(tileBaseHeight * 1.6f)
    val rowsSideBySide = rowCount > 1 && width >= 560f
    val rowHeight = 48f
    val rowsHeight = when {
        rowCount <= 0 -> 0f
        rowsSideBySide -> rowHeight
        else -> rowCount * rowHeight + (rowCount - 1) * 4f
    }
    val noteHeight = 12f * scale + 2f
    return AppearancePickerGeometry(
        horizontalPaddingDp = horizontalPadding,
        verticalPaddingDp = verticalPadding,
        sectionGapDp = sectionGap,
        tileWidthDp = tileWidth,
        tileHeightDp = tileHeight,
        tileImageHeightDp = tileImageHeight,
        tileLabelHeightDp = tileLabelHeight,
        tileGapDp = tileGap,
        badgeBandDp = badgeBand,
        rowsHeightDp = rowsHeight,
        rowsSideBySide = rowsSideBySide,
        noteHeightDp = noteHeight,
        footerHeightDp = footerHeight,
        compact = compact,
    )
}

/**
 * @param defaultIsCurrent whether the default tile is the committed choice (no saved tile is CURRENT).
 * @param initialFocusKey where focus lands once the tiles register: the + Upload tile the picker left from for the file picker , the tile an import has just added, otherwise
 *   the tile in use (see `BackgroundPickerState.initialFocusKey` and `MimirButtonPickerState.initialFocusKey`).
 * @param stage the picker's own preview, drawn in the stage band.
 * @param rows the optional bands under the tiles (Dim and Blur); receives whether they sit side by side.
 * @param defaultTile how the default tile paints itself (the theme's atmosphere, the rune's coin).
 * @param thumbnail how a saved tile paints its image (the background's photo, the mark's coin).
 * @param presenceManagedExternally true under the app-shell overlay host, which owns the entrance fade already.
 * @param controlHint the footer legend, given the focused key: the pickers whose legend changes with the tile (the Mimir button's Y ADJUST
 *   shows on a saved image only) say so here; the default is [AppearancePickerControlHint].
 * @param controlHintFallback the shorter legend a footer falls back to when [controlHint] does not fit on its one line at this width and font
 *   (null = there is none, and the footer is drawn as it always was): adjust mode's, which drops X RESET before it would drop the size keys.
 * @param tilesOverlay a band drawn over the tile row in its place, for the Mimir button's adjust mode (the chips Smaller,
 *   Larger, Reset and Done stand where the tiles do). The tiles STAY composed and registered under it, hidden and silent, so the pad's focus never
 *   leaves the tile being adjusted: unmounting them would walk focus across their siblings one removal at a time and flash the stage.
 * @param artFillsTile true when a tile's art covers the whole of it (the Background's photo), so the state word stands on it as a small chip. False, the default, is a mark with room
 *   of its own (the Mimir button's coin): the state word has a band under the art and never covers it ([solveAppearancePickerGeometry]).
 */
@Composable
fun AppearanceImagePickerOverlay(
    authority: FocusAuthority,
    scope: FocusScope,
    title: String,
    subtitle: String,
    tiles: List<AppearancePickerTile>,
    defaultIsCurrent: Boolean,
    initialFocusKey: FocusKey,
    note: String?,
    onActivateTile: (AppearancePickerTile) -> Unit,
    onRemoveTile: (AppearancePickerTile.Saved) -> Unit,
    onDismiss: () -> Unit,
    stage: @Composable BoxScope.() -> Unit,
    defaultTile: @Composable BoxScope.() -> Unit,
    thumbnail: @Composable BoxScope.(AppearancePickerTile.Saved) -> Unit,
    rowCount: Int = 0,
    rows: (@Composable (sideBySide: Boolean) -> Unit)? = null,
    presenceManagedExternally: Boolean = false,
    controlHint: (focused: FocusKey?) -> String = { AppearancePickerControlHint },
    controlHintFallback: (focused: FocusKey?) -> String? = { null },
    tilesOverlay: (@Composable BoxScope.() -> Unit)? = null,
    artFillsTile: Boolean = false,
) {
    val palette = LocalMimirPalette.current
    val chrome = themeGalleryChromeTreatment(palette)
    // The theme gallery's opaque backing , so the stage is the only thing being judged.
    val backing = palette.scrim.compositeOver(palette.scrim).compositeOver(palette.surfaceBase)
    val focus by authority.state.collectAsState()
    val density = LocalDensity.current

    // The tile focus starts on, landed once the tiles register. Deliberately the key at MOUNT, captured once, not read
    // afresh through rememberUpdatedState (compose-expert side-effects.md's rule for a long-running effect): focus moves
    // the stage's preview and the preview picks the key, and the very first tile to register is auto-seeded (Atmosphere,
    // which clears the preview), so a re-read key chases its own tail. A later landing -- the host's, on a tile an
    // import adds after this has settled -- is the host's own call of the same lander.
    LaunchedEffect(Unit) { authority.landPickerFocus(initialFocusKey) }
    BackHandler(enabled = true, onBack = onDismiss)

    val appear = remember(presenceManagedExternally) { Animatable(if (presenceManagedExternally) 1f else 0f) }
    LaunchedEffect(presenceManagedExternally) {
        if (!presenceManagedExternally) appear.animateTo(1f, tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing))
    }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = appear.value }
            .background(backing)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
    ) {
        val geometry = remember(maxWidth, density.fontScale, rowCount, tiles.size, artFillsTile) {
            solveAppearancePickerGeometry(maxWidth.value, density.fontScale, rowCount, tiles.size, artFillsTile)
        }
        Column(
            Modifier
                .fillMaxSize()
                // Thor drive 3: the system's gesture handle is drawn over the bottom strip, so the footer legend stands above it; the stage, which takes what the bands leave, gives the room.
                .padding(bottom = mimirGestureHandleClearance())
                .padding(horizontal = geometry.horizontalPaddingDp.dp, vertical = geometry.verticalPaddingDp.dp)
                // The column swallows the gutter tap so a miss lands on nothing rather than on Back.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            verticalArrangement = Arrangement.spacedBy(geometry.sectionGapDp.dp),
        ) {
            // The header takes the height its own lines need (title, and a subtitle that may wrap to two) and the stage takes what every other band leaves
            // (`weight(1f)`), so the geometry above holds only the fixed bands. A pure budget once reserved two subtitle lines for every picker and sized the
            // stage from them, which on a 360 dp square at font 1.3 left the stage 25 dp short of the 2x copy and its caption (review 2026-10-13); the shell
            // never read that stage height, and the budget no longer has one.
            Column(Modifier.fillMaxWidth()) {
                MimirChromeText(
                    text = title,
                    style = MimirType.RunicEyebrow,
                    color = chrome.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MimirType.RowSupport.copy(fontSize = MimirTypeStep.Label, lineHeight = 14.sp),
                    color = chrome.secondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag(AppearancePickerStageTag)
                    .clip(RoundedCornerShape(AppearancePickerStageCorner))
                    .border(1.dp, palette.outline, RoundedCornerShape(AppearancePickerStageCorner)),
            ) {
                stage()
            }
            Box(Modifier.fillMaxWidth().height(geometry.tileHeightDp.dp)) {
                Row(
                    Modifier
                        .fillMaxSize()
                        .then(if (tilesOverlay == null) Modifier else Modifier.graphicsLayer { alpha = 0f }.clearAndSetSemantics { }),
                    horizontalArrangement = Arrangement.spacedBy(geometry.tileGapDp.dp),
                ) {
                    tiles.forEach { tile ->
                        key(tile.key) {
                            AppearancePickerTileCard(
                                tile = tile,
                                badge = appearanceTileBadge(tile, defaultIsCurrent),
                                spoken = appearanceTileSpokenLabel(tile, defaultIsCurrent),
                                focused = authority.isLitFocusIn(scope, tile.key, focus),
                                authority = authority,
                                scope = scope,
                                geometry = geometry,
                                onActivate = { onActivateTile(tile) },
                                onRemove = { (tile as? AppearancePickerTile.Saved)?.let(onRemoveTile) },
                                defaultTile = defaultTile,
                                thumbnail = thumbnail,
                            )
                        }
                    }
                }
                if (tilesOverlay != null) {
                    // A band that swallows every touch it covers (a tap between two chips must not reach a hidden tile under it).
                    Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { } }) { tilesOverlay() }
                }
            }
            if (rows != null && geometry.rowsHeightDp > 0f) {
                Box(Modifier.fillMaxWidth().height(geometry.rowsHeightDp.dp)) { rows(geometry.rowsSideBySide) }
            }
            Text(
                text = note.orEmpty(),
                style = MimirType.RowSupport.copy(fontSize = MimirTypeStep.Label, lineHeight = 14.sp),
                color = chrome.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().height(geometry.noteHeightDp.dp),
            )
            Row(
                Modifier.fillMaxWidth().height(geometry.footerHeightDp.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val footerFocus = focus.focused.takeIf { focus.scope == scope }
                PickerFooterHint(
                    text = controlHint(footerFocus),
                    fallback = controlHintFallback(footerFocus),
                    style = if (geometry.compact) MimirType.ControlHintCompact else MimirType.ControlHint,
                    color = chrome.secondary,
                )
            }
        }
    }
}

/** Held back from the footer's width, 4 dp a side, so a legend that only just fits here (rounding, another device's metrics) is not clipped on the player's screen. */
private val PickerFooterSlack = 8.dp

/**
 * The footer legend a picker draws: [whole] when it fits on the footer's one line, otherwise [fallback] (null = there is none, and [whole] is drawn as it always was).
 * [fits] says whether a candidate stands on one line. Pure, so the choice is a table test; the fallback is taken whenever the whole does not fit, since nothing
 * shorter than it exists to try next.
 */
internal fun fittingControlHint(whole: String, fallback: String?, fits: (String) -> Boolean): String =
    if (fallback == null || fits(whole)) whole else fallback

/**
 * The picker's one-line legend. A picker with a [fallback] legend has the whole one measured as it is drawn (projected through the applied controller map, uppercase, at [style])
 * against the footer's width and drops to the fallback when it would not fit: the footer is `maxLines = 1`, so a legend too wide for it would lose its last words in silence.
 * A picker without one draws exactly what it always drew.
 */
@Composable
private fun PickerFooterHint(text: String, fallback: String?, style: TextStyle, color: Color) {
    if (fallback == null) {
        MimirControlHintText(text, style = style, color = color, maxLines = 1)
        return
    }
    val labels = LocalMimirControllerHintLabels.current
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val room = constraints.maxWidth - with(density) { PickerFooterSlack.roundToPx() }
        val shown = remember(text, fallback, labels, style, room, density.density, density.fontScale) {
            fittingControlHint(text, fallback) { candidate ->
                measurer.measure(
                    AnnotatedString(labels.rewrite(candidate).uppercase(Locale.ROOT)),
                    style = style,
                    softWrap = false,
                    maxLines = 1,
                ).size.width <= room
            }
        }
        MimirControlHintText(shown, style = style, color = color, maxLines = 1)
    }
}

/**
 * The frames [landPickerFocus] tries before giving up. A tile registers its bounds in `onGloballyPositioned`, one layout
 * after it composes, so a key for a tile added THIS frame lands on the second attempt or the third.
 */
private const val LandPickerFocusFrames = 8

/**
 * Land focus on [key] once its tile has registered. `FocusAuthority.requestFocus` is a silent no-op for a key that has
 * not registered its bounds yet, so a single attempt made the frame a tile is added is dropped: this retries across a few
 * frames until focus sits on [key].
 *
 * The ONE lander for both appearance pickers: the template's landing on open, and the host's landing on a tile an import
 * has just added or a removal has just left behind. Never duplicate it -- a one-shot copy is what left focus on the old
 * tile after an import.
 */
suspend fun FocusAuthority.landPickerFocus(key: FocusKey) {
    repeat(LandPickerFocusFrames) {
        withFrameNanos { }
        requestFocus(key)
        if (state.value.focused == key) return
    }
}

@Composable
private fun AppearancePickerTileCard(
    tile: AppearancePickerTile,
    badge: String?,
    spoken: String,
    focused: Boolean,
    authority: FocusAuthority,
    scope: FocusScope,
    geometry: AppearancePickerGeometry,
    onActivate: () -> Unit,
    onRemove: () -> Unit,
    defaultTile: @Composable BoxScope.() -> Unit,
    thumbnail: @Composable BoxScope.(AppearancePickerTile.Saved) -> Unit,
) {
    val palette = LocalMimirPalette.current
    val shape = RoundedCornerShape(AppearancePickerTileCorner)
    val label = when (tile) {
        is AppearancePickerTile.Default -> tile.label
        is AppearancePickerTile.Saved -> tile.label
        is AppearancePickerTile.Add -> ""
    }
    // The art's own box: the whole tile when the art fills it (a photo), else the tile less the band under it that holds the state word, so the word never stands on the art.
    val art = Modifier.fillMaxWidth().height((geometry.tileImageHeightDp - geometry.badgeBandDp).dp)
    Column(Modifier.width(geometry.tileWidthDp.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(geometry.tileImageHeightDp.dp)
                .mimirFocusable(authority, tile.key, scope)
                .mimirSelectionRing(
                    focused = focused,
                    identity = tile.key,
                    shape = MimirRingShape.Card(AppearancePickerTileCorner),
                    restingAlpha = AppearancePickerTileRestingAlpha,
                )
                .clip(shape)
                .background(palette.surfaceRaised)
                .mimirActivatable(label = spoken, authority = authority, key = tile.key, onActivate = onActivate),
        ) {
            when (tile) {
                is AppearancePickerTile.Saved -> {
                    if (tile.path != null) Box(art) { thumbnail(tile) }
                }
                is AppearancePickerTile.Default -> Box(art) { defaultTile() }
                is AppearancePickerTile.Add -> {
                    // A `+` over "Upload", centred, with no inner box (the bordered "+ Add image"
                    // clipped). One line each, never wrapped: the Display glyph and the Label word stack to ~34 dp
                    // inside the 48 dp compact tile even at a raised font scale.
                    // The glyph is a symbol, not reading text: it keeps its size in dp whatever the font scale, so at 1.3 the `+` over "Upload" still stands in a
                    // 48 dp compact tile (it was cut at the tile's bottom edge, review 2026-10-13); the word beneath scales as every word does.
                    val plusSize = (MimirTypeStep.Display.value / LocalDensity.current.fontScale.coerceAtLeast(1f)).sp
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            AppearanceUploadGlyph,
                            style = MimirType.RowTitle.copy(
                                fontSize = plusSize,
                                lineHeight = plusSize,
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = palette.textPrimary,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            softWrap = false,
                        )
                        Text(
                            AppearanceUploadLabel,
                            style = MimirType.RowSupport.copy(fontSize = MimirTypeStep.Label, lineHeight = 12.sp),
                            color = palette.textSecondary,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (badge != null) {
                TileBadge(
                    badge = badge,
                    color = if (tile is AppearancePickerTile.Saved && tile.current) palette.accentBright else palette.textSecondary,
                    tileWidth = geometry.tileWidthDp.dp,
                    compact = geometry.compact,
                    band = geometry.badgeBandDp.dp,
                )
            }
            // Touch: a focused saved tile shows a 48 dp ✕. Tap-only -- it holds no focus key, so the D-pad never lands on
            // it; X on the pad does the same thing. A rounded square on the step corner, not a circle: the pickers keep
            // one rectangular family ; only the button's coin stays round.
            if (focused && tile is AppearancePickerTile.Saved) {
                val removeShape = RoundedCornerShape(AppearancePickerStepCorner)
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(MimirSpacing.minTarget)
                        .mimirActivatable(label = "Remove ${tile.label}", onActivate = onRemove),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(22.dp)
                            .clip(removeShape)
                            .background(palette.surfaceBase.copy(alpha = 0.82f))
                            .border(1.dp, palette.outline, removeShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("✕", color = palette.textPrimary, fontSize = MimirTypeStep.Label, lineHeight = 12.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        TileName(
            label = label,
            style = MimirType.RowSupport.copy(
                fontSize = MimirTypeStep.Label,
                lineHeight = 12.sp,
                fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = if (focused) palette.textPrimary else palette.textSecondary,
            modifier = Modifier.fillMaxWidth().height(geometry.tileLabelHeightDp.dp),
        )
    }
}

/**
 * Where a name's words may be broken at a syllable hyphen, for the tile names. Null, the product's value, asks the platform's hyphenator ([mimirHyphenateWideWords]); a host JVM ships no
 * hyphenation patterns, so a test of the drawing provides a dictionary here.
 */
internal val LocalAppearanceNameBreaks = compositionLocalOf<((word: String) -> List<Int>?)?> { null }

/** How a tile's name sets in its room: [hyphenated] is the name with soft hyphens in the words too wide for a line, [tooWide] that some word is wider than the room. */
private data class TileNameFit(val tooWide: Boolean, val hyphenated: String?)

/**
 * A tile's name under its image: wrapped at its spaces to two lines and cut at a WORD only past that, never inside one ("Your ..." on a compact tile, review 2026-10-13);
 * the whole name stays the node's spoken name when it is shortened. A name with one word wider than the tile ("Atmosphere" on a 59 dp tile) has no space to wrap at, so it
 * breaks at a SYLLABLE HYPHEN, "Atmo-" / "sphere" (the name ran wider than its tile; [mimirHyphenateWideWords]). Only when the platform gives that word no hyphen
 * that holds (a language it has no patterns for, a font so large that a half is still too wide) does it stand whole on one line and run a little past the tile on both sides
 * (centred; the gap and the neighbours' short names leave room) rather than break inside the word.
 */
@Composable
private fun TileName(label: String, style: TextStyle, color: Color, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val syllableBreaks = LocalAppearanceNameBreaks.current
    BoxWithConstraints(modifier) {
        val room = constraints.maxWidth
        val fit = remember(label, room, style, density.density, density.fontScale, syllableBreaks) {
            val tooWide = label.split(' ').any { word ->
                word.isNotEmpty() && measurer.measure(AnnotatedString(word), style = style, softWrap = false, maxLines = 1).size.width > room
            }
            TileNameFit(
                tooWide = tooWide,
                hyphenated = if (tooWide) mimirHyphenateWideWords(measurer, label, style, room, AppearancePickerLabelLines, syllableBreaks) else null,
            )
        }
        if (fit.hyphenated != null) {
            Text(
                text = fit.hyphenated,
                style = style.merge(MimirSoftHyphenTextStyle),
                color = color,
                maxLines = AppearancePickerLabelLines,
                softWrap = true,
                textAlign = TextAlign.Center,
                // The whole name, without the hyphens, is what is spoken.
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            )
        } else if (fit.tooWide) {
            Text(
                text = label,
                style = style,
                color = color,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Visible,
                modifier = Modifier.align(Alignment.TopCenter).wrapContentWidth(Alignment.CenterHorizontally, unbounded = true),
            )
        } else {
            MimirWordEllipsisText(
                text = label,
                style = style,
                color = color,
                modifier = Modifier.fillMaxWidth(),
                maxLines = AppearancePickerLabelLines,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * A tile's state word (DEFAULT, CURRENT, SAVED, PREVIEW UNAVAILABLE), whole or not at all. One line when it fits; a badge of two words that does not fit one line wraps at its
 * space ("PREVIEW" over "UNAVAILABLE") when each word fits alone; and a single word, which has no word boundary to cut at, is left off only on a tile too narrow for it at this
 * font ("DEFAULT" on a 59 dp tile at font 2) rather than drawn as "DE...". The state is still the tile's spoken name, and the stage and the note say which tile is in use.
 *
 * With a [band] (a mark with room of its own, the Mimir button's coin) the word stands in that band, centred under the art, with the whole tile's width, and never
 * on the art (SAVED covered the coin and DEFAULT was dropped on the Rune tile). Without one (a photo that fills the tile) it is a small chip on the photo's bottom-left
 * corner, padded as little as a compact tile needs to hold the word.
 */
@Composable
private fun BoxScope.TileBadge(badge: String, color: Color, tileWidth: Dp, compact: Boolean, band: Dp) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val inBand = band > 0.dp
    // In its band the word sets on the line the band is sized for ([AppearancePickerBadgeLineSp]); on the photo it keeps the face's own leading.
    val base = if (compact) MimirType.MicroStatusCompact else MimirType.MicroStatus
    val style = if (inBand) base.copy(lineHeight = AppearancePickerBadgeLineSp.sp) else base
    val fit = remember(badge, tileWidth, compact, inBand, density.density, density.fontScale) {
        // The padding a chip on the photo takes, widest first (outer, inner a side): a compact tile gives the word its room by padding it less, and the first padding that holds the
        // word is the one drawn ("CURRENT" at font 1.3 on a 59 dp tile is 57.5 dp across, so it takes the least). In its band the word has the whole tile's width.
        val pads = if (inBand) listOf(0.dp to 0.dp) else if (compact) listOf(1.dp to 1.dp, 0.dp to 1.dp, 0.dp to 0.5.dp) else listOf(4.dp to 3.dp)
        fun width(text: String) = measurer.measure(AnnotatedString(text), style = style, softWrap = false, maxLines = 1).size.width
        pads.firstNotNullOfOrNull { (outer, inner) ->
            val room = with(density) { (tileWidth - outer * 2 - inner * 2).roundToPx() }
            tileBadgeText(badge, ::width, room)?.let { Triple(it, outer, inner) }
        }
    } ?: return
    val (shown, outer, inner) = fit
    val palette = LocalMimirPalette.current
    if (inBand) {
        // A two-line word (PREVIEW / UNAVAILABLE, on a tile with no art to cover) grows upward from the band's bottom.
        Text(
            shown,
            style = style,
            color = color,
            textAlign = TextAlign.Center,
            maxLines = shown.count { it == '\n' } + 1,
            softWrap = false,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 1.dp),
        )
    } else {
        Text(
            shown,
            style = style,
            color = color,
            maxLines = shown.count { it == '\n' } + 1,
            softWrap = false,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(outer)
                .background(palette.surfaceBase.copy(alpha = 0.72f), RoundedCornerShape(3.dp))
                .padding(horizontal = inner, vertical = 1.dp),
        )
    }
}

/**
 * What a tile's [badge] draws as in [room] pixels, [widthOf] being the width of a string on one line: the badge itself when it fits, its words stacked one to a line when it
 * does not and every word fits alone, and null (left off) when even a single word does not fit. Pure, so the order is a table test.
 */
internal fun tileBadgeText(badge: String, widthOf: (String) -> Int, room: Int): String? {
    if (widthOf(badge) <= room) return badge
    val words = badge.split(' ').filter { it.isNotEmpty() }
    return if (words.size > 1 && words.all { widthOf(it) <= room }) words.joinToString("\n") else null
}

/**
 * A small decoded copy of a saved image for a tile or a Settings row, decoded once per path on IO with a sample
 * that keeps it near [targetWidthPx] wide, and kept in [PickerBitmapCache]: a path seen before paints on the composition's
 * first frame (S-07, S-24); a first decode draws nothing until it lands and then fades in over FocusMs (Q13). No image while a first
 * decode runs or when the file fails, which the tile reads as PREVIEW UNAVAILABLE. Local files under the app's own filesDir only; nothing here reaches the player's storage.
 */
@Composable
internal fun rememberAppearanceThumbnailArt(path: String?, targetWidthPx: Int): PickerArt =
    rememberPickerArt(path?.let { pickerThumbnailKey(it, targetWidthPx) }) {
        path?.let { file -> decodeSampledThumbnail(file, targetWidthPx)?.asImageBitmap() }
    }

/**
 * [file] decoded with the largest sample that keeps it at least [targetWidthPx] wide, or null when it is missing or not an image.
 * Blocking, so a caller runs it on IO; shared by [rememberAppearanceThumbnailArt] and the Mimir-button picker's mark loader.
 *
 * A file that is not there never reaches `BitmapFactory`: its `decodeFile` logs "Unable to decode stream" with the file's PATH in
 * it, and these are the app's private copies of the player's images, whose paths nothing here may write to a log.
 */
internal fun decodeSampledThumbnail(file: String, targetWidthPx: Int): Bitmap? = runCatching {
    if (!File(file).isFile) return@runCatching null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetWidthPx.coerceAtLeast(1)) sample *= 2
    BitmapFactory.decodeFile(file, BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()

/** The photo of a saved background inside its tile: cropped to fill, the way the shelf will show it. */
@Composable
fun AppearanceThumbnailImage(path: String, modifier: Modifier = Modifier) {
    val art = rememberAppearanceThumbnailArt(path, AppearanceThumbnailWidthPx)
    val bitmap = art.bitmap
    if (bitmap != null) {
        androidx.compose.foundation.Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            // A cold first decode fades in from nothing over FocusMs (Q13); a warm one is whole on its first frame.
            modifier = modifier.fillMaxSize().graphicsLayer { alpha = art.alpha },
        )
    }
}

internal const val AppearanceThumbnailWidthPx = 256
private val AppearancePickerTileCorner = 8.dp
private val AppearancePickerStageCorner = 10.dp
private const val AppearancePickerTileRestingAlpha = 0.94f

/**
 * The pickers' small rectangle -- a Dim or Blur step, the ✕ on a focused tile -- is a rounded RECTANGLE on this corner,
 * the tile's card-corner family one step smaller, never a capsule. One
 * symbol for the clip and for `MimirRingShape.Card` (R-1), so the ring hugs exactly the rectangle it clips.
 */
internal val AppearancePickerStepCorner: Dp = 6.dp

/** The visible height of a Dim or Blur step, centred in its [MimirSpacing.minTarget] touch target. */
internal val AppearancePickerStepHeight: Dp = 32.dp

/** A disabled step at rest: the ring's own rest dim, so no alpha layer sits over the ring. (No step is disabled today; the step shape keeps the seam.) */
internal const val AppearancePickerStepDisabledAlpha = 0.28f

/** The visible step inside its target: [AppearancePickerStepGeometry] as numbers, so the JVM can pin the shape. */
internal data class AppearancePickerStepGeometry(
    val targetHeightDp: Float,
    val stepHeightDp: Float,
    val cornerDp: Float,
) {
    /** The clear space above and below the visible rectangle inside the target; the ring is drawn from the rectangle. */
    val verticalInsetDp: Float get() = (targetHeightDp - stepHeightDp) / 2f

    /** A rectangle, not a capsule: the corner is well under half the visible height. */
    val rectangular: Boolean get() = cornerDp * 2f < stepHeightDp
}

/** The step geometry the two pickers draw: the 48 dp target, the 32 dp rectangle, the 6 dp corner. */
internal fun appearancePickerStepGeometry(): AppearancePickerStepGeometry = AppearancePickerStepGeometry(
    targetHeightDp = MimirSpacing.minTarget.value,
    stepHeightDp = AppearancePickerStepHeight.value,
    cornerDp = AppearancePickerStepCorner.value,
)
