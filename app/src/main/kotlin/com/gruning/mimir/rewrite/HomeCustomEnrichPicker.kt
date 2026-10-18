package com.gruning.mimir.rewrite

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInRoot
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.input.MimirNavFeedback
import com.gruning.mimir.rewrite.core.input.mimirActivatable
import com.gruning.mimir.rewrite.core.input.mimirFocusable
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.BeamSeparator
import com.gruning.mimir.rewrite.core.ui.MimirBeforeFirstValidViewport
import com.gruning.mimir.rewrite.core.ui.MediaArtBookCover
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.MimirAnimatedChromeText
import com.gruning.mimir.rewrite.core.ui.MimirControlHintText
import com.gruning.mimir.rewrite.core.ui.MimirIntentText
import com.gruning.mimir.rewrite.core.ui.MimirTextFragment
import com.gruning.mimir.rewrite.core.ui.MimirTextIntent
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import com.gruning.mimir.rewrite.core.ui.MimirCopy
import com.gruning.mimir.rewrite.core.ui.MimirKeyboard
import com.gruning.mimir.rewrite.core.ui.mimirKeyboardPanelWidth
import com.gruning.mimir.rewrite.core.ui.MimirLiquidGlassPanel
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirFocusFlareReach
import com.gruning.mimir.rewrite.core.ui.MimirFocusTileRingReach
import com.gruning.mimir.rewrite.core.ui.MimirRingShape
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.MimirWordEllipsisText
import com.gruning.mimir.rewrite.core.ui.PlatformConsoleArt
import com.gruning.mimir.rewrite.core.ui.mimirGlassBackdropProgress
import com.gruning.mimir.rewrite.core.ui.mimirSelectionRing
import com.gruning.mimir.rewrite.core.ui.mimirMeasuredViewport
import com.gruning.mimir.rewrite.core.ui.mimirReducedMotion
import com.gruning.mimir.rewrite.core.ui.mimirSheetEnter
import com.gruning.mimir.rewrite.core.ui.mimirSheetExit
import com.gruning.mimir.rewrite.core.ui.mimirSheetSpec
import com.gruning.mimir.rewrite.core.ui.mimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.platformConsoleIconBrush
import com.gruning.mimir.rewrite.core.ui.rememberMimirGlassProgress
import com.gruning.mimir.rewrite.core.ui.rememberMeasuredViewport
import com.gruning.mimir.rewrite.core.ui.resolve
import com.gruning.mimir.rewrite.feature.library.VideoBackdrop
import java.io.File
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * The ring's reach along a scroll host's axis (ring migration, 2026-09-28). A horizontal scroll clips exactly on
 * its own axis, so the chip strip and the gallery strip pad their start and end by the whole tile ring reach;
 * the viewport and follow-scroll laws below read the same number. An `Int`, as every geometry law here is.
 */
internal val EnrichRingClearanceDp: Int = MimirFocusTileRingReach.value.toInt()

/**
 * The glass panel's content padding: sm at compact typography, md otherwise. Named because the panel clips to its
 * shape, and the ring-bearing controls on its content edge read this to know how much of their reach it covers.
 */
internal fun homeCustomEnrichPanelPadding(compactTypography: Boolean): Dp =
    if (compactTypography) MimirSpacing.sm else MimirSpacing.md

/**
 * What a ring-bearing control on the panel's start edge adds to the panel's padding so the panel's clip cuts
 * neither its settled ring ([MimirFocusTileRingReach]) nor, for the flaring pills, its arrival flare
 * ([MimirFocusFlareReach]); never negative. The search field rings without a flare (6 dp at sm, 2 at md); Upload
 * and Stop/Retry flare (12 at sm, 8 at md). The tile menu's `homeTileMenuRowRingInset`, with the flare.
 */
internal fun homeCustomEnrichEdgeRingInset(panelPadding: Dp, flare: Boolean): Dp =
    (MimirFocusTileRingReach + (if (flare) MimirFocusFlareReach else 0.dp) - panelPadding).coerceAtLeast(0.dp)

/**
 * The filter chip strip's start/end air. The chips wear no ring (the metallic label, no chip, no
 * ring), so the strip keeps its old 6 dp rather than the ring's 14: the full reach would squeeze the compact filter
 * viewport below the longest governed seat ("Source · ScreenScraper" at font scale 1.3).
 */
internal const val EnrichChipStripClearanceDp = 6

/**
 * The air above and below a gallery tile inside its band. A scroll host does not clip its cross axis, so this
 * stays the height budget's own 6 and the certified 148/124 bands hold; the ring's halo is free to reach past
 * the band onto the preview caption, as it does on Home.
 */
internal const val EnrichGalleryVerticalAirDp = 6
/**
 * The strip's overflow cue (a drawn chevron) stands in the bleed zone just outside the column, so the strip's tiles keep the whole column:
 * the seat's distance from the column's edge. It is drawn only while the strip has more to scroll on that side (sweep 10-09).
 */
internal const val EnrichGalleryCueSeatDp = 11

/**
 * The air between the keyboard's opaque sheet and the page's panel edge, on the sides and below (sweep 10-09). The Rename keyboard stands the same
 * distance above the screen's bottom edge ([GameTitleEditOverlay]), so the two keyboards sit alike.
 */
internal const val EnrichKeyboardSheetAirDp = 6

/** The clear space kept between the keyboard's sheet and the top row above it, and the least air the sheet keeps around its keys when the room is short. */
private const val EnrichKeyboardSheetRowGapDp = 4
private const val EnrichKeyboardSheetMinAirDp = 4

/** Just above the least the shared keyboard's keys can be pressed to (five rows of 34 dp, its caption and its paddings, about 236 dp). */
private const val EnrichKeyboardCompressFloorDp = 240

/**
 * The air between the status line and the strip. A lit tile's halo reaches past its band (the band's own air is 6 dp, the ring reaches 14)
 * and touched the status line's descenders; 12 dp clears it (sweep 10-09). The compact page, whose height is all spent, keeps its row gap.
 */
internal const val EnrichStatusToStripGapDp = 12
private const val EnrichPreviewChromeBudgetDp = 28
private const val EnrichPreviewMinMediaHeightDp = 96

/**
 * The stage's floor where the strip has grown by a bar (Main): the bar's height comes out of the stage, so a stage that had 96 dp of media
 * before it keeps showing with what is left (a hero stage on the 853 x 480 page had 5 dp to spare), at 72 dp, never hidden by the strip it sits under.
 */
private const val EnrichPreviewGrownMinMediaHeightDp = 72
private const val EnrichPreviewLandscapeMaxHeightDp = 200
private const val EnrichPreviewCompactMaxHeightDp = 240
private const val EnrichPreviewRailGapDp = 4
private const val EnrichPreviewCaptionHeightDp = 16
private const val EnrichPreviewCaptionGapDp = 8
private const val EnrichPreviewRuneInsetDp = 8
private const val EnrichPreviewRuneArmDp = 14
private const val EnrichPreviewRuneStrokeDp = 1
internal const val HomeCustomEnrichExpandedWidthFraction = 0.96f
internal const val HomeCustomEnrichExpandedHeightFraction = 0.94f
internal const val HomeCustomEnrichExpandedContentPaddingDp = 8
internal const val HomeCustomEnrichExpandedHeroAspect = 16f / 9f
internal const val HomeCustomEnrichHeroSelectionWidthDp = 144
private const val EnrichFilterDropdownFallbackTopCompactDp = 92
private const val EnrichFilterDropdownFallbackTopRegularDp = 104
internal const val HomeCustomEnrichStatusFontSizeSp = 12
internal const val HomeCustomEnrichNoAutomaticMediaSpoken = "No automatic media"
internal const val HomeCustomEnrichPanelTestTag = "home_custom_enrich_panel"
internal const val HomeCustomEnrichNoAutomaticMediaVisual = "NO AUTOMATIC MEDIA"
internal const val HomeCustomEnrichAutomaticMotionReadySpoken = "Automatic Hero Motion ready"
internal const val HomeCustomEnrichAutomaticMotionReadyVisual = "MOTION READY"

internal const val HomeCustomEnrichDownloadedChoicesSpoken = "Downloaded choices ready"
internal const val HomeCustomEnrichDownloadedChoicesVisual = "DOWNLOADED CHOICES READY"
internal const val HomeCustomEnrichPageSize = 12

/** Dark Liquid Glass controls must not reuse Folkvangr/Baldr's pale app-field surfaces. */
internal fun homeCustomEnrichGlassControlFill(
    palette: MimirThemePalette,
    lightAlpha: Float,
    darkAlpha: Float,
): Color = if (palette.usesBrightField) {
    palette.materials.hero.coverMiddle.copy(alpha = lightAlpha)
} else {
    palette.surfaceRaised.copy(alpha = darkAlpha)
}

// Named (lightAlpha, darkAlpha) pairs for every homeCustomEnrichGlassControlFill call site below.
// Each pair is tuned per surface and deliberately NOT consolidated even where two happen to
// coincide today — a shared constant would silently couple two different UI
// roles the next time either is retuned independently. Values are copied verbatim from the prior
// inline literals; this block is a naming-only change with no resolved-value difference.
// `internal`, not private, since 2026-09-18: the FAVORITES search field is the same control and must
// carry the same fill. Opened one PAIR, deliberately -- the block's rule above is that these never
// consolidate, and widening the rest would invite exactly the coupling it warns about.
internal const val EnrichSearchFieldLightAlpha = 0.72f
internal const val EnrichSearchFieldDarkAlpha = 0.62f
private const val EnrichExpandedPreviewLightAlpha = 0.72f
private const val EnrichExpandedPreviewDarkAlpha = 0.58f
private const val EnrichGalleryTileLightAlpha = 0.70f
private const val EnrichGalleryTileDarkAlpha = 0.58f
private const val EnrichTileLabelBarLightAlpha = 0.88f
private const val EnrichTileLabelBarDarkAlpha = 0.78f

internal fun homeCustomEnrichDownloadedChoicesIntent(): MimirTextIntent =
    MimirTextIntent.StaticChrome(
        spokenText = HomeCustomEnrichDownloadedChoicesSpoken,
        visualOverride = HomeCustomEnrichDownloadedChoicesVisual,
    )

internal fun homeCustomEnrichTileWidthDp(kind: EnrichMediaKind, compactSquare: Boolean): Int = when (kind) {
    EnrichMediaKind.ICON -> if (compactSquare) 136 else 112
    // The gallery is a chooser, not the inspection surface. Keep its Hero card smaller than the
    // minimum 171×96 inline preview so visual hierarchy always points downward toward PREVIEW.
    EnrichMediaKind.HERO -> HomeCustomEnrichHeroSelectionWidthDp
    EnrichMediaKind.CLIP -> if (compactSquare) 192 else 164
    // Derived from the gallery height budget above and the portrait aspect, NOT chosen: a cover tile is
    // as wide as a portrait shape can be inside the height the cartridge lane already fits in.
    EnrichMediaKind.COVER -> (
        (homeCustomEnrichGalleryMinHeightDp(kind, compactSquare) - EnrichGalleryVerticalAirDp * 2) *
            homeCustomEnrichPreviewStageAspect(kind)
        ).toInt()
    EnrichMediaKind.TOME_PHYSICAL -> if (compactSquare) 136 else 112
}

/**
 * Deterministic gallery scroll seat for a focused asset: the previous tile stays peeked on the left
 * and the ring clearance is honored, so controller focus always lands inside the authored viewport.
 * Single-sourced from the same tile/gap constants the layout uses — no measured drift. The strip is plain
 * tiles at one gap: there is no "Results" label between the saved tiles and the results .
 */
internal fun homeCustomEnrichGalleryScrollOffsetDp(
    focusedAssetIndex: Int,
    tileWidthDp: Int,
    gapDp: Int,
): Float {
    if (focusedAssetIndex <= 0) return 0f
    val childX = EnrichRingClearanceDp +
        focusedAssetIndex * tileWidthDp +
        gapDp * focusedAssetIndex
    return (childX - tileWidthDp - gapDp - EnrichRingClearanceDp).coerceAtLeast(0).toFloat()
}

internal fun homeCustomEnrichGalleryMinHeightDp(kind: EnrichMediaKind, compactSquare: Boolean): Int =
    when (kind) {
        EnrichMediaKind.ICON -> if (compactSquare) 148 else 124
        EnrichMediaKind.HERO ->
            kotlin.math.ceil(HomeCustomEnrichHeroSelectionWidthDp / homeCustomEnrichPreviewStageAspect(kind))
                .toInt() + EnrichGalleryVerticalAirDp * 2
        EnrichMediaKind.CLIP -> if (compactSquare) 120 else 104
        EnrichMediaKind.TOME_PHYSICAL -> if (compactSquare) 148 else 124
        // Deliberately the SAME height budget the cartridge lane already passes, rather than a height
        // solved from a chosen width. The certified viewport budget is the binding constraint here -- a
        // portrait tile sized by eye blew past the 700x350 landscape panel on the first attempt -- so the
        // height is taken as given and the WIDTH is derived from it (see homeCustomEnrichTileWidthDp).
        EnrichMediaKind.COVER -> if (compactSquare) 148 else 124
    }

/**
 * The preview spends only measured slack below the unchanged gallery rail. The 28dp chrome budget is
 * the rail-to-caption gap, one runic caption lane, and the caption-to-enclosure gap. A preview that
 * cannot retain 96dp of actual media is hidden instead of compressing search, filters, tiles, or footer.
 */
internal fun homeCustomEnrichPreviewHeightDp(
    measuredGalleryHeightDp: Int,
    kind: EnrichMediaKind,
    compactSquare: Boolean,
    /** How much taller than its certified band the strip stands ([homeCustomEnrichStripGrowthDp]): the stage gives that height up, and its floor with it. */
    growthDp: Int = 0,
): Int {
    val availableMediaHeight = measuredGalleryHeightDp -
        homeCustomEnrichGalleryMinHeightDp(kind, compactSquare) -
        growthDp -
        EnrichPreviewChromeBudgetDp
    val floor = if (growthDp > 0) EnrichPreviewGrownMinMediaHeightDp else EnrichPreviewMinMediaHeightDp
    if (availableMediaHeight < floor) return 0
    val cap = if (compactSquare) EnrichPreviewCompactMaxHeightDp else EnrichPreviewLandscapeMaxHeightDp
    return availableMediaHeight.coerceAtMost(cap)
}

/** Fixed preview stage geometry: square for icon/physical, widescreen for hero/clip. */
internal fun homeCustomEnrichPreviewStageAspect(kind: EnrichMediaKind): Float = when (kind) {
    EnrichMediaKind.ICON, EnrichMediaKind.TOME_PHYSICAL -> 1f
    EnrichMediaKind.HERO, EnrichMediaKind.CLIP -> 16f / 9f
    // The only PORTRAIT kind. A cover in a square stage would letterbox into two grey bars, and the
    // whole point of this picker is judging box art by eye. 0.72 is the platform cover aspect the
    // gamelist already uses for GameCube/Wii -- the shape most of the example library actually is.
    EnrichMediaKind.COVER -> 0.72f
}

/** Measured ring reach of a 48dp pill (2dp ring + ~1.5dp scale overhang), rounded up. */
internal const val EnrichTargetRingAirDp = 4

/**
 * The footer's air under its pills, carved out of the 56 dp action floor (the 48 dp pills sit at the band's top
 * with both [EnrichTargetRingAirDp] beneath them), so the certified height budget does not move. With the panel's
 * padding that is 20 dp at md, where the flare clears, and 16 dp at sm, where the settled ring clears but the
 * flare's outer 4 dp still meets the clip for its 540 ms: closing that needs the 360x360 budget re-certified.
 */
internal const val EnrichFooterBottomAirDp: Int = EnrichTargetRingAirDp * 2

/**
 * Top band floor: the search field keeps its full 48dp target and every chip ring keeps
 * uncompressed air above and below. Never a fixed height.
 */
internal fun homeCustomEnrichTopBandMinHeightDp(): Int = MimirSpacing.minTarget.value.toInt() + EnrichTargetRingAirDp * 2

/** Footer floor: Upload/Retry are true 48dp targets with the same uncompressed ring air. */
internal fun homeCustomEnrichActionRowMinHeightDp(): Int = MimirSpacing.minTarget.value.toInt() + EnrichTargetRingAirDp * 2

/** Conservative one-action + one-legend compact footer width at the live font scale. */
internal fun homeCustomEnrichCompactFooterRequiredWidthDp(
    actionLabel: String,
    legend: String,
    fontScale: Float = 1.3f,
): Int {
    fun ink(text: String, sp: Int): Int =
        kotlin.math.ceil(text.length * sp * fontScale * 0.68f).toInt()
    return homeCustomEnrichEdgeRingInset(MimirSpacing.sm, flare = true).value.toInt() +
        MimirSpacing.md.value.toInt() * 2 + ink(actionLabel, 11) +
        MimirSpacing.md.value.toInt() + ink(legend, 9)
}

internal fun homeCustomEnrichCompactFooterAvailableWidthDp(viewportWidthDp: Int = 360): Int =
    (viewportWidthDp * 0.94f).toInt() - MimirSpacing.sm.value.toInt() * 2

/** Single-line lane height for an sp size at a font scale (1.4 covers ascent/descent + padding). */
internal fun homeCustomEnrichTextLaneDp(sp: Int, fontScale: Float): Int =
    kotlin.math.ceil(sp * fontScale * 1.4f).toInt()

internal fun homeCustomEnrichStatusLaneHeightDp(
    compactSquare: Boolean,
    fontScale: Float,
): Int = homeCustomEnrichTextLaneDp(HomeCustomEnrichStatusFontSizeSp, fontScale) *
    (if (compactSquare) 2 else 1)

/**
 * Ring-safe tile width: wide tiles shrink inside the measured gallery viewport instead of
 * overflowing it; square tiles are already smaller than every certified viewport.
 */
internal fun homeCustomEnrichEffectiveTileWidthDp(
    kind: EnrichMediaKind,
    compactSquare: Boolean,
    galleryViewportWidthDp: Int,
): Int {
    val authored = homeCustomEnrichTileWidthDp(kind, compactSquare)
    if (galleryViewportWidthDp <= 0) return authored
    return minOf(authored, (galleryViewportWidthDp - EnrichRingClearanceDp * 2).coerceAtLeast(96))
}

internal data class HomeCustomEnrichTopBandWeights(
    val search: Float,
    val filters: Float,
)

internal data class HomeCustomEnrichControlGeometry(
    val compactTypography: Boolean,
    val narrowWidth: Boolean,
)

/**
 * Controls respond to the actual Compose viewport and font scale, never the device model or physical
 * diagonal. Short landscape surfaces such as Thor only need compact type; 1:1, 3:2, 4:3, and vertical
 * handheld viewports also need narrower horizontal seats.
 */
internal fun homeCustomEnrichControlGeometry(
    widthDp: Float,
    heightDp: Float,
    fontScale: Float,
): HomeCustomEnrichControlGeometry {
    require(widthDp.isFinite() && heightDp.isFinite() && fontScale.isFinite())
    require(widthDp > 0f && heightDp > 0f && fontScale > 0f)
    val narrowWidth = widthDp < 600f
    return HomeCustomEnrichControlGeometry(
        compactTypography = narrowWidth || heightDp <= 520f || fontScale > 1.1f,
        narrowWidth = narrowWidth,
    )
}

/** Narrow Search stays terse while Filters owns enough width for `SOURCE · ScreenScraper` at 1.3. */
internal fun homeCustomEnrichTopBandWeights(narrowWidth: Boolean): HomeCustomEnrichTopBandWeights =
    if (narrowWidth) {
        HomeCustomEnrichTopBandWeights(search = 0.31f, filters = 0.69f)
    } else {
        HomeCustomEnrichTopBandWeights(search = 0.38f, filters = 0.62f)
    }

internal fun homeCustomEnrichFilterHorizontalPaddingDp(compactTypography: Boolean): Int =
    if (compactTypography) MimirSpacing.sm.value.toInt() else MimirSpacing.md.value.toInt()

/** Conservative one-line chip seat used by the square/fontScale geometry gate. */
internal fun homeCustomEnrichFilterSeatWidthDp(
    text: String,
    fontScale: Float = 1.3f,
    compactTypography: Boolean = true,
): Int = kotlin.math.ceil(text.length * 11 * fontScale * 0.56f).toInt() +
    homeCustomEnrichFilterHorizontalPaddingDp(compactTypography) * 2

internal fun homeCustomEnrichCompactFilterViewportWidthDp(viewportWidthDp: Int = 360): Int {
    val panelContent = (viewportWidthDp * 0.94f).toInt() - MimirSpacing.sm.value.toInt() * 2
    val sharedBand = panelContent - MimirSpacing.sm.value.toInt()
    return (sharedBand * homeCustomEnrichTopBandWeights(narrowWidth = true).filters).toInt() -
        EnrichChipStripClearanceDp * 2
}

/**
 * Content-aware chip settlement: retain the current scroll when the full ring-cleared chip is visible,
 * reveal its trailing edge when needed, and park over-wide/pathological seats at their leading edge.
 */
internal fun homeCustomEnrichChipScrollTargetPx(
    currentScrollPx: Int,
    viewportWidthPx: Int,
    chipOffsetPx: Int,
    chipWidthPx: Int,
    clearancePx: Int,
): Int {
    val start = (chipOffsetPx - clearancePx).coerceAtLeast(0)
    if (viewportWidthPx <= 0 || chipWidthPx + clearancePx * 2 >= viewportWidthPx) return start
    val end = chipOffsetPx + chipWidthPx + clearancePx
    return when {
        start < currentScrollPx -> start
        end > currentScrollPx + viewportWidthPx -> (end - viewportWidthPx).coerceAtLeast(0)
        else -> currentScrollPx
    }
}

/** Kinds with no truthful filters give Search the whole band instead of a dead half. */
internal fun HomeCustomEnrichState.searchOwnsFullBand(): Boolean = visibleFilters().isEmpty()

/**
 * Truthful composition budget for the certified 360×360 and 700×350 release viewports: every
 * text lane is modeled at the live font scale (header Row 15sp, status Support 13sp), the two
 * 48dp action bands carry their ring air, and compact squares reserve two lines for actionable
 * provider/setup failures instead of hiding the destination behind an ellipsis.
 */
internal fun homeCustomEnrichRequiredHeightDp(
    kind: EnrichMediaKind,
    compactSquare: Boolean,
    fontScale: Float = 1f,
): Int = homeCustomEnrichGalleryMinHeightDp(kind, compactSquare) +
    homeCustomEnrichTopBandMinHeightDp() +
    homeCustomEnrichActionRowMinHeightDp() +
    homeCustomEnrichTextLaneDp(15, fontScale) +
    homeCustomEnrichTextLaneDp(13, fontScale) * (if (compactSquare) 2 else 1) +
    4 * (if (compactSquare) 0 else 4)

internal val HomeCustomEnrichScope = FocusScope("home/custom-enrich")

/**
 * Leave [HomeCustomEnrichScope] on dispose -- **total, not guarded.**
 *
 * The picker used to pop only `if (authority.state.value.scope == HomeCustomEnrichScope)`. That was
 * correct for as long as nothing could dispose the picker while covering it. As of 2026-09-11 something
 * can: the Upload action opens Mimir's own file browser (`FileBrowserMode.PICK_IMAGE`), which
 * `resolveMimirOverlayStack` ranks above `HOME_TILE_MENU`, so `HomeTileMenuOverlayHost` fades the whole
 * tile menu out underneath it and this composable is disposed with the browser's scope active. A guarded
 * pop declines there; the scope is stranded beneath the browser's; the browser's own pop then lands on
 * the stale entry; and the re-entering picker pushes a SECOND copy, whose later pop leaves the first one
 * covering Home -- the divorced-input shape `HomeTomeLeaveScopeOrphanTest` and `HomeQuickLaunchOverlay`'s
 * `discardScope` both exist to kill. `discardScope` removes a buried entry with no state emission and is
 * a plain pop when the scope is active, so every path that worked before is byte-identical and the
 * buried path stops corrupting the deque.
 */
internal fun leaveHomeCustomEnrichScope(authority: FocusAuthority) {
    authority.discardScope(HomeCustomEnrichScope)
}

internal enum class HomeCustomEnrichLane { SEARCH, FILTERS, GALLERY, FOOTER }

internal data class HomeCustomEnrichFilter(
    val key: String,
    val label: String,
    val values: List<String>,
    val selectedIndex: Int = 0,
) {
    val selected: String get() = values.getOrElse(selectedIndex) { values.first() }
    val selectedDisplay: String
        get() = when (key to selected) {
            "style" to "All" -> "Any"
            "fit" to "Mimir" -> "Best fit"
            "encode" to "Both" -> "Any"
            "encode" to "Normalized" -> "Optimized"
            // The box's back (the value stays "Back" for the provider request): "Back" alone read as a way to go back (sweep 10-09).
            "box" to "Back" -> "Back cover"
            else -> selected
        }
    fun cycle(): HomeCustomEnrichFilter = copy(selectedIndex = (selectedIndex + 1) % values.size)
}

internal sealed interface HomeCustomEnrichSearchStatus {
    data object Idle : HomeCustomEnrichSearchStatus
    data class Searching(val provider: String, val found: Int) : HomeCustomEnrichSearchStatus
    data class Complete(val found: Int) : HomeCustomEnrichSearchStatus
    data class Cancelled(val kept: Int) : HomeCustomEnrichSearchStatus
    data class NeedsAccess(val message: String) : HomeCustomEnrichSearchStatus
    data class Unreachable(val message: String) : HomeCustomEnrichSearchStatus
    data class ProviderFailed(val message: String) : HomeCustomEnrichSearchStatus
    data class Busy(val message: String) : HomeCustomEnrichSearchStatus
    data class Failed(val message: String) : HomeCustomEnrichSearchStatus
}

internal enum class HomeCustomEnrichSearchControl { NONE, RETRY, STOP }

internal fun HomeCustomEnrichState.searchControl(): HomeCustomEnrichSearchControl = when (searchStatus) {
    is HomeCustomEnrichSearchStatus.Searching -> HomeCustomEnrichSearchControl.STOP
    is HomeCustomEnrichSearchStatus.Failed,
    is HomeCustomEnrichSearchStatus.Busy,
    is HomeCustomEnrichSearchStatus.Unreachable,
    is HomeCustomEnrichSearchStatus.ProviderFailed,
    is HomeCustomEnrichSearchStatus.Cancelled,
    -> HomeCustomEnrichSearchControl.RETRY
    else -> HomeCustomEnrichSearchControl.NONE
}

internal enum class HomeCustomEnrichOperation { IDLE, APPLY_FETCHING, APPLY_COMMITTING }

/** A confirmed remote gallery choice reaches one terminal result even if its owning surface moves. */
internal suspend fun <T> runHomeCustomEnrichCommittedPick(block: suspend () -> T): T =
    withContext(NonCancellable) { block() }

internal sealed interface HomeCustomEnrichResult {
    val stableId: String
    val label: String
    val previewPath: String?
    val width: Int
    val height: Int
    val durationMillis: Long?
    val mediaType: CustomHeroMediaType?
    val providerReference: String?
    /** The provider's own media type, when this result came from one. Null for uploads. */
    val providerMediaType: String? get() = null
    val saved: EnrichSavedAsset?

    data class Icon(
        val candidate: SteamGridDbTileFetcher.Candidate,
        override val saved: EnrichSavedAsset? = null,
    ) : HomeCustomEnrichResult {
        override val stableId: String = candidate.stableId
        override val label: String = if (candidate.width > 0 && candidate.height > 0) {
            "${candidate.providerLabel} · ${candidate.width}×${candidate.height}"
        } else {
            candidate.providerLabel
        }
        override val previewPath: String? = candidate.previewPath
        override val width: Int = candidate.width
        override val height: Int = candidate.height
        override val durationMillis: Long? = null
        override val mediaType: CustomHeroMediaType? = null
        override val providerMediaType: String? = candidate.providerMediaType
        override val providerReference: String = candidate.url
    }

    data class Hero(
        val candidate: CustomHeroCandidate,
        override val saved: EnrichSavedAsset? = null,
    ) : HomeCustomEnrichResult {
        override val stableId: String = candidate.stableId
        override val label: String = candidate.displayLabel + candidateDimensionsLabel(candidate)
        override val previewPath: String? = candidate.pickerArtPath
        override val width: Int = candidate.width
        override val height: Int = candidate.height
        override val durationMillis: Long? = candidate.durationMillis
        override val mediaType: CustomHeroMediaType = candidate.mediaType
        override val providerReference: String? = candidate.providerAssetId ?: candidate.remoteUrl
    }

    data class Clip(
        val candidate: CustomHeroCandidate,
        override val saved: EnrichSavedAsset? = null,
    ) : HomeCustomEnrichResult {
        override val stableId: String = candidate.stableId
        override val label: String = candidate.displayLabel + candidateDimensionsLabel(candidate)
        override val previewPath: String? = candidate.pickerArtPath
        override val width: Int = candidate.width
        override val height: Int = candidate.height
        override val durationMillis: Long? = candidate.durationMillis
        override val mediaType: CustomHeroMediaType = candidate.mediaType
        override val providerReference: String = candidate.providerAssetId ?: candidate.remoteUrl
    }
}

/** The dimensions a found tile's label carries; with none known it carries nothing, no filler ("size unknown" told the player nothing). */
private fun candidateDimensionsLabel(candidate: CustomHeroCandidate): String =
    if (candidate.width > 0 && candidate.height > 0) " · ${candidate.width}×${candidate.height}" else ""

internal sealed interface HomeCustomEnrichAsset {
    val stableId: String
    val label: String
    val previewPath: String?

    data object Automatic : HomeCustomEnrichAsset {
        override val stableId: String = "auto"
        override val label: String = MimirCopy.Default
        override val previewPath: String? = null
    }

    data class Saved(val record: EnrichSavedAsset) : HomeCustomEnrichAsset {
        override val stableId: String = record.assetId
        // A choice saved before the earlier update may still carry the old " · size unknown" filler in its stored label.
        override val label: String = enrichLabelWithoutSizeFiller(record.label)
        override val previewPath: String? = record.previewPath
            .takeIf(String::isNotBlank)
            ?.takeUnless { preview ->
                record.mediaType in setOf(
                    CustomHeroMediaType.VIDEO,
                    CustomHeroMediaType.ANIMATED_IMAGE,
                ) && preview == record.localPath
            }
    }

    data class Result(val result: HomeCustomEnrichResult) : HomeCustomEnrichAsset {
        override val stableId: String = "result-${result.stableId}"
        override val label: String = result.label
        override val previewPath: String? = result.previewPath
    }

    data object More : HomeCustomEnrichAsset {
        override val stableId: String = "more"
        override val label: String = "More results"
        override val previewPath: String? = null
    }
}

/** Whether this picker session is customising a LIBRARY platform tile or a game/app tile. */
internal enum class HomeCustomEnrichSubject { PLATFORM, GAME }

/**
 * What the `Default` entry actually PAINTS for this subject.
 *
 * Policy (CP-03): every other tile in the gallery shows real art, so an unlabelled reset symbol made the
 * user choose between "this art" and "a symbol". Default now shows the art it would restore, badged. This type
 * exists so that decision is a PURE function of facts the opening call site already holds — the picker never
 * reaches into a store to guess what "default" means.
 *
 * Each arm names a renderable that already ships and is already what the destination surface paints on revert;
 * nothing here is a new drawing. [Unavailable] is a first-class answer, not a failure: three of the four media
 * kinds genuinely have no default renderable, and an honest reset glyph beats an invented tile.
 */
internal sealed interface HomeCustomEnrichDefaultArt {
    /**
     * Mimir's own console line-art — literally the Library platform tile's default face
     * (`PlatformHeroTile`'s `iconContent` → `PlatformConsoleArt`) and the companion's platform grid.
     */
    data class ConsoleLineArt(val platformIdValue: String) : HomeCustomEnrichDefaultArt

    /**
     * The image the AUTOMATIC pipeline already resolved — the scraped ledge cover for an emulated game, the
     * launcher icon for an Android package. Never a user pick: `HomeShelfRow.artworkUri` carries automatic art
     * and the chosen icon rides the separate `tileIconUri`, so reverting is exactly what this paints.
     */
    data class Automatic(val model: String) : HomeCustomEnrichDefaultArt

    /**
     * The coverless-game bound book (`MediaArtBookCover`) — what the shelf paints when automatic art resolved
     * to nothing at all, so "default" for that game is genuinely the book.
     */
    data class TitleBook(val title: String, val platformIdValue: String?) : HomeCustomEnrichDefaultArt

    /**
     * Automatic preview clip: the bounded local video exists, but only a local/static poster may be painted.
     * Never hand the video URI to an image renderer.
     */
    data class ClipAutomatic(val posterUri: String?) : HomeCustomEnrichDefaultArt

    /** No default renderable exists in this context. */
    data object Unavailable : HomeCustomEnrichDefaultArt
}

internal fun homeCustomEnrichNoAutomaticMediaIntent(): MimirTextIntent =
    MimirTextIntent.StaticChrome(
        spokenText = HomeCustomEnrichNoAutomaticMediaSpoken,
        visualOverride = HomeCustomEnrichNoAutomaticMediaVisual,
    )

internal fun homeCustomEnrichAutomaticMotionReadyIntent(): MimirTextIntent =
    MimirTextIntent.StaticChrome(
        spokenText = HomeCustomEnrichAutomaticMotionReadySpoken,
        visualOverride = HomeCustomEnrichAutomaticMotionReadyVisual,
    )

/**
 * Decide what `Default` paints. Pure — every input is a fact the caller already holds.
 *
 * @param automaticArtUri `HomeShelfRow.artworkUri` — automatic art only, never the chosen override.
 * @param tileIconUri normal Tome well / square tile art (`tileIconUri ?: artworkUri` policy).
 * @param heroArtUri automatic managed hero for Library entry only; Home hero reset stays absent.
 * @param previewVideoUri automatic local preview clip; never painted directly.
 * @param clipPosterUri optional local/static poster paired with [previewVideoUri].
 */
internal fun homeCustomEnrichDefaultArt(
    subject: HomeCustomEnrichSubject,
    kind: EnrichMediaKind,
    title: String,
    platformIdValue: String?,
    automaticArtUri: String?,
    tileIconUri: String? = null,
    heroArtUri: String? = null,
    previewVideoUri: String? = null,
    clipPosterUri: String? = null,
): HomeCustomEnrichDefaultArt = when (kind) {
    EnrichMediaKind.ICON -> when {
        subject == HomeCustomEnrichSubject.PLATFORM ->
            platformIdValue?.takeIf(String::isNotBlank)
                ?.let(HomeCustomEnrichDefaultArt::ConsoleLineArt)
                ?: HomeCustomEnrichDefaultArt.Unavailable
        !automaticArtUri.isNullOrBlank() -> HomeCustomEnrichDefaultArt.Automatic(automaticArtUri)
        else -> HomeCustomEnrichDefaultArt.TitleBook(title, platformIdValue)
    }
    EnrichMediaKind.TOME_PHYSICAL -> {
        val tomeWell = tileIconUri?.takeIf(String::isNotBlank) ?: automaticArtUri?.takeIf(String::isNotBlank)
        when {
            tomeWell != null -> HomeCustomEnrichDefaultArt.Automatic(tomeWell)
            subject == HomeCustomEnrichSubject.GAME ->
                HomeCustomEnrichDefaultArt.TitleBook(title, platformIdValue)
            else -> HomeCustomEnrichDefaultArt.Unavailable
        }
    }
    EnrichMediaKind.CLIP -> when {
        !previewVideoUri.isNullOrBlank() ->
            HomeCustomEnrichDefaultArt.ClipAutomatic(clipPosterUri?.takeIf(String::isNotBlank))
        else -> HomeCustomEnrichDefaultArt.Unavailable
    }
    EnrichMediaKind.HERO -> when {
        !heroArtUri.isNullOrBlank() -> HomeCustomEnrichDefaultArt.Automatic(heroArtUri)
        else -> HomeCustomEnrichDefaultArt.Unavailable
    }
    // "Default" for a cover is whatever the automatic ladder resolved -- restoring the automatic result after a pick. A title with no automatic cover shows its book, the same
    // honest placeholder the gamelist gives it.
    EnrichMediaKind.COVER -> when {
        !automaticArtUri.isNullOrBlank() -> HomeCustomEnrichDefaultArt.Automatic(automaticArtUri)
        title.isNotBlank() -> HomeCustomEnrichDefaultArt.TitleBook(title, platformIdValue)
        else -> HomeCustomEnrichDefaultArt.Unavailable
    }
}

internal sealed interface HomeCustomEnrichExpandedPreview {
    data object None : HomeCustomEnrichExpandedPreview
    data class Loading(val resultId: String) : HomeCustomEnrichExpandedPreview
    data class Ready(val resultId: String, val localPath: String) : HomeCustomEnrichExpandedPreview
    data class Failed(val resultId: String) : HomeCustomEnrichExpandedPreview
}

internal data class HomeCustomEnrichState(
    val sessionId: String,
    val kind: EnrichMediaKind,
    val cacheKey: String,
    val gameTitle: String,
    val queryDraft: String,
    val submittedQuery: String? = null,
    val filters: List<HomeCustomEnrichFilter> = homeCustomEnrichDefaultFilters(kind),
    /** Frozen reopen ordering; activating within this session does not move an entry. */
    val saved: List<EnrichSavedAsset> = emptyList(),
    val activeAssetId: String? = null,
    val results: List<HomeCustomEnrichResult> = emptyList(),
    val page: Int = 0,
    val hasMore: Boolean = false,
    val generation: Int = 0,
    val searchStatus: HomeCustomEnrichSearchStatus = HomeCustomEnrichSearchStatus.Idle,
    val operation: HomeCustomEnrichOperation = HomeCustomEnrichOperation.IDLE,
    /** False only while provisional legacy media is receiving its durable Saved identity, or while the first read of the saved stores runs ([catalogFirstRead]). */
    val catalogReady: Boolean = true,
    /**
     * True from the page's first frame until the stores' first read lands (audit 2026-10-14, RC-29): the disk reads that fill the Saved gallery
     * run on IO behind the page's arrival lead, not in the key handler that opened it. The page is not ready yet -- non-directional presses wait,
     * as they do for a migration -- but it SAYS nothing about it: "Preparing saved choices" is for a migration the player can see, and a read
     * that lands in a frame or two would only flash it.
     */
    val catalogFirstRead: Boolean = false,
    val keyboardOpen: Boolean = false,
    /** Inspect-only Hero preview. It never changes focus, selection, or provider/apply state. */
    val previewExpanded: Boolean = false,
    /** Full-quality inspect state. Search thumbnails are never promoted into the expanded renderer. */
    val expandedPreview: HomeCustomEnrichExpandedPreview = HomeCustomEnrichExpandedPreview.None,
    /** Transient filter menu; the selected filter index changes only when an option is confirmed. */
    val openFilterKey: String? = null,
    val note: String? = null,
    val laneMemory: Map<HomeCustomEnrichLane, String> = emptyMap(),
    /**
     * What the `Default` entry paints (policy CP-03). Presentation only — it never changes what Default
     * DOES, which is still a plain revert. Defaults to [HomeCustomEnrichDefaultArt.Unavailable] so a caller
     * that cannot resolve one keeps the honest reset glyph instead of an empty tile.
     */
    val defaultArt: HomeCustomEnrichDefaultArt = HomeCustomEnrichDefaultArt.Unavailable,
    /** the state half of the (kind, state) this page shows. [kind] alone cannot tell Icon art from Launch card (both are the face; the state says which). */
    val mediaState: MediaState = MediaState.SHELF,
    /** The Media chip's values for this game. Empty for a platform picker, which keeps its single type and no chip. */
    val mediaTypes: List<CustomMediaType> = emptyList(),
    /** The chips each of [mediaTypes] opens with, so the top row seats Search for the widest type and holds one width across them ([customMediaPageSeating]). */
    val mediaTypeFilters: Map<CustomMediaSelection, List<HomeCustomEnrichFilter>> = emptyMap(),
    /** The Media panel is open. Transient, like [openFilterKey]. */
    val mediaPanelOpen: Boolean = false,
    /** The Filters panel (the folded chips' one chip) is open. Transient, like [openFilterKey]. */
    val filtersPanelOpen: Boolean = false,
    /**
     * This is the merged "Icon art" picker of a GAME : kind ICON, state SHELF, and it offers a square, a
     * box, the physical article and an upload in one grid, each tile the shape of its art. The platform picker is also kind ICON and
     * is not this: it keeps its SteamGridDB-only search and its All/Grids/Icons filter.
     */
    val shelfIconArt: Boolean = false,
    /** Which of the three Saved catalogs each [saved] asset lives in (the merged picker only), so activate and remove go to its store. */
    val savedLanes: Map<String, IconArtLane> = emptyMap(),
) {
    val assets: List<HomeCustomEnrichAsset>
        get() = buildList {
            add(HomeCustomEnrichAsset.Automatic)
            saved.forEach { add(HomeCustomEnrichAsset.Saved(it)) }
            results.forEach { add(HomeCustomEnrichAsset.Result(it)) }
            if (hasMore && results.isNotEmpty()) add(HomeCustomEnrichAsset.More)
        }

    val durableSavedCount: Int
        get() = (saved.map(EnrichSavedAsset::assetId) + results.mapNotNull { it.saved?.assetId })
            .distinct()
            .size
}

/**
 * The kind whose gallery geometry this picker follows. The merged Icon art picker holds boxes and squares side by side, so it
 * takes the cover lane's law -- each tile the shape of its art, a square asset a square tile -- which the icon lane's one fixed
 * width cannot give. The two share their height budget, so nothing else moves.
 */
internal val HomeCustomEnrichState.geometryKind: EnrichMediaKind
    get() = if (shelfIconArt) EnrichMediaKind.COVER else kind

internal fun HomeCustomEnrichState.visibleFilters(): List<HomeCustomEnrichFilter> {
    if (shelfIconArt) return visibleIconArtFilters()
    return when (kind) {
        EnrichMediaKind.ICON -> when (filterValue("kind")) {
            "All" -> filters.filter { it.key == "kind" }
            "Grids" -> filters
            "Icons" -> filters.filterNot { it.key == "style" }
            else -> filters.filter { it.key == "kind" }
        }
        EnrichMediaKind.CLIP -> if (filterValue("source") == "Both") {
            filters
        } else {
            // A provider-specific source already fixes the only truthful media family. Hiding the
            // redundant Format chip prevents impossible pairs such as SteamGridDB + Video while
            // [effectiveFilterValue] returns the neutral All policy to the request coordinator.
            filters.filter { it.key == "source" }
        }
        else -> filters
    }
}

/**
 * The merged picker's filters: Type always; Region where ScreenScraper is asked at all (an emulated game that is not a PC game: its Type list holds the
 * box family, [IconArtType.ThreeD] among it) and the Type is a box, the article, or All; Style and Size where SteamGridDB answers (a square, or All).
 * A PC game's Type list is All, Square, Front and an Android row's is All, Square: ScreenScraper is never asked for either, so a Region chip there
 * would constrain nothing. A filter that is not painted does not constrain the request ([effectiveFilterValue]).
 */
private fun HomeCustomEnrichState.visibleIconArtFilters(): List<HomeCustomEnrichFilter> =
    iconArtVisibleFilters(filters, filterValue(IconArtTypeFilterKey) ?: IconArtType.All)

/** The merged picker's chips that stand when its Type is [type] ([visibleIconArtFilters]); All is the most it ever shows at once. */
internal fun iconArtVisibleFilters(filters: List<HomeCustomEnrichFilter>, type: String): List<HomeCustomEnrichFilter> {
    val screenScraperIsAsked = filters.firstOrNull { it.key == IconArtTypeFilterKey }?.values?.contains(IconArtType.ThreeD) == true
    return filters.filter { filter ->
        when (filter.key) {
            IconArtTypeFilterKey -> true
            "region" -> screenScraperIsAsked && type != IconArtType.Square
            else -> type == IconArtType.Square || type == IconArtType.All
        }
    }
}

/**
 * The shape a merged-picker tile takes when its asset brings no size, from the lane it belongs in; null for a box (the cover law's own
 * portrait default) and for every asset of a picker that is not the merged one.
 */
internal fun HomeCustomEnrichState.iconArtLaneAspect(asset: HomeCustomEnrichAsset): Float? {
    if (!shelfIconArt) return null
    val lane = when (asset) {
        is HomeCustomEnrichAsset.Saved -> savedLanes[asset.record.assetId]
        is HomeCustomEnrichAsset.Result -> (asset.result as? HomeCustomEnrichResult.Icon)?.let { icon ->
            iconArtLaneOf(icon.providerMediaType, icon.candidate.savedSource == EnrichSavedSource.STEAMGRIDDB)
        }
        HomeCustomEnrichAsset.Automatic, HomeCustomEnrichAsset.More -> null
    }
    return lane?.let(::iconArtDefaultAspect)
}

/** Explicit user paging remains bounded without truncating the first provider page. */
internal fun homeCustomEnrichResultCap(kind: EnrichMediaKind): Int = when (kind) {
    EnrichMediaKind.ICON -> 240
    EnrichMediaKind.HERO -> 120
    EnrichMediaKind.CLIP -> 18
    EnrichMediaKind.TOME_PHYSICAL -> 8
    // Box art comes back in regional families -- six box-3D variants for one GameCube title is normal --
    // so the cap has to leave room for a family rather than a handful.
    EnrichMediaKind.COVER -> 40
}

internal fun homeCustomEnrichSearchLabel(kind: EnrichMediaKind): String = when (kind) {
    EnrichMediaKind.ICON -> "Search icon art"
    EnrichMediaKind.HERO -> "Search hero art"
    EnrichMediaKind.CLIP -> "Search Hero Motion"
    EnrichMediaKind.TOME_PHYSICAL -> "Search Cartridge Hero"
    EnrichMediaKind.COVER -> "Search cover art"
}

internal fun homeCustomEnrichSearchIntent(
    state: HomeCustomEnrichState,
    compactSquare: Boolean,
): MimirTextIntent = if (state.queryDraft.isBlank()) {
    MimirTextIntent.StaticChrome(
        spokenText = homeCustomEnrichSearchLabel(state.kind),
        visualOverride = if (compactSquare && !state.searchOwnsFullBand()) "SEARCH" else null,
    )
} else {
    MimirTextIntent.Authored(state.queryDraft)
}

internal fun homeCustomEnrichScreenScraperIdentityUse(
    seededTitle: String,
    submittedQuery: String,
): Pair<Boolean, Boolean> {
    val useVerifiedIdentity = com.gruning.mimir.rewrite.core.metadata.online.ProviderTitleMatcher
        .matches(seededTitle, submittedQuery)
    return useVerifiedIdentity to false
}

/**
 * a pc_games title looks itself up by its Steam app id in the Customize Media
 * HERO picker whenever the query box is still the seeded title — the moment he types, the typed text
 * wins. R-23  already declined filename/title matching for
 * `.steam` shortcuts elsewhere, because sequels and editions are exactly where that guess goes wrong; when
 * nothing has been typed there is no guess to make at all, so this reuses the exact key the shortcut
 * already carries instead. [PlatformId.PC_GAMES] gates it to the one platform with an app-id route; `page
 * == 0` matters because the exact-id lookup returns a single non-paged set, never a "load more" page; and
 * [com.gruning.mimir.rewrite.core.metadata.online.ProviderTitleMatcher] is the same "query still
 * effectively the seeded title" test [homeCustomEnrichScreenScraperIdentityUse] already trusts above, not
 * a new comparison.
 */
internal fun homeCustomEnrichPcGameUsesExactAppId(
    platformId: PlatformId?,
    seededTitle: String,
    submittedQuery: String,
    page: Int,
): Boolean = platformId == PlatformId.PC_GAMES &&
    page == 0 &&
    com.gruning.mimir.rewrite.core.metadata.online.ProviderTitleMatcher.matches(seededTitle, submittedQuery)

/** Query-first ScreenScraper ladder with seeded game-title fallback; never persists presentation aliases. */
internal fun homeCustomEnrichScreenScraperCandidateNames(
    seededTitle: String,
    submittedQuery: String,
): List<String> = buildList {
    val query = submittedQuery.trim()
    if (query.isNotBlank()) add(query)
    val seeded = seededTitle.trim()
    if (seeded.isNotBlank() && seeded != query) add(seeded)
}

internal fun homeCustomEnrichHeroPreviewNamespace(
    generation: Int,
    kind: EnrichMediaKind,
    provider: String,
    page: Int,
): String {
    val mediaKind = when (kind) {
        EnrichMediaKind.HERO -> "hero"
        EnrichMediaKind.CLIP -> "clip"
        else -> kind.name.lowercase()
    }
    val providerKey = when (provider.lowercase()) {
        "steamgriddb" -> "sgdb"
        "screenscraper" -> "ss"
        else -> provider.lowercase().replace(Regex("[^a-z0-9]"), "")
    }
    return "manual-$generation-$mediaKind-$providerKey-p$page"
}

internal fun homeCustomEnrichHeroProviderBudget(
    source: String,
): Int = if (source == "Both") {
    // Keep SteamGridDB's API limit stable for the whole search generation, even if the other
    // provider becomes unavailable between pages. Changing 6 to 12 changes the provider offset
    // and silently skips six heroes.
    HomeCustomEnrichPageSize / 2
} else {
    HomeCustomEnrichPageSize
}

/**
 * Resolves HERO picker search status after provider attempts complete.
 *
 * When source is Both, ScreenScraper results settle Complete even if the SteamGridDB gate is closed.
 * NeedsAccess is returned only when no usable provider results exist.
 */
internal fun homeCustomEnrichHeroSearchStatus(
    resultCount: Int,
    attempted: Boolean,
    needsAccess: Boolean,
    unreachable: Boolean,
    providerFailed: Boolean,
    needsAccessMessage: String = "Connect artwork: Settings › Appearance › Enrichment & artwork.",
): HomeCustomEnrichSearchStatus = homeCustomEnrichProviderSearchStatus(
    resultCount = resultCount,
    attempted = attempted,
    needsAccess = needsAccess,
    unreachable = unreachable,
    providerFailed = providerFailed,
    needsAccessMessage = needsAccessMessage,
    notAttemptedMessage = "Connect artwork: Settings › Appearance › Enrichment & artwork.",
    unreachableMessage = "Couldn’t reach artwork services. Saved choices are still available.",
    providerFailedMessage = "Artwork services couldn’t complete this request. Saved choices are still available.",
)

/** Shared settlement for HERO, CLIP, and TOME_PHYSICAL provider searches. */
internal fun homeCustomEnrichProviderSearchStatus(
    resultCount: Int,
    attempted: Boolean,
    needsAccess: Boolean,
    unreachable: Boolean,
    providerFailed: Boolean,
    needsAccessMessage: String,
    notAttemptedMessage: String,
    unreachableMessage: String,
    providerFailedMessage: String,
): HomeCustomEnrichSearchStatus = when {
    resultCount > 0 -> HomeCustomEnrichSearchStatus.Complete(resultCount)
    needsAccess -> HomeCustomEnrichSearchStatus.NeedsAccess(needsAccessMessage)
    unreachable -> HomeCustomEnrichSearchStatus.Unreachable(unreachableMessage)
    providerFailed -> HomeCustomEnrichSearchStatus.ProviderFailed(providerFailedMessage)
    !attempted -> HomeCustomEnrichSearchStatus.NeedsAccess(notAttemptedMessage)
    else -> HomeCustomEnrichSearchStatus.Complete(0)
}

internal fun newHomeCustomEnrichState(
    sessionId: String,
    kind: EnrichMediaKind,
    cacheKey: String,
    gameTitle: String,
    catalog: EnrichSavedCatalog,
    catalogReady: Boolean = true,
    defaultArt: HomeCustomEnrichDefaultArt = HomeCustomEnrichDefaultArt.Unavailable,
    /** R-24 : open with Source already on SteamGridDB. No effect on ICON (already SteamGridDB-only, no Source
     *  filter) or TOME_PHYSICAL (ScreenScraper-only). */
    preferSteamGridDb: Boolean = false,
    mediaState: MediaState = MediaState.SHELF,
    mediaTypes: List<CustomMediaType> = emptyList(),
    mediaTypeFilters: Map<CustomMediaSelection, List<HomeCustomEnrichFilter>> = emptyMap(),
    /** The merged Icon art picker of a game: its three catalogs as one gallery, and the Type values this game can answer. Null for every other picker. */
    iconArt: IconArtCatalog? = null,
    faceTypes: List<String> = iconArtTypeValues(platformId = null, emulated = true),
): HomeCustomEnrichState = HomeCustomEnrichState(
    sessionId = sessionId,
    kind = kind,
    cacheKey = cacheKey,
    gameTitle = gameTitle,
    queryDraft = gameTitle,
    filters = if (iconArt != null) {
        homeCustomEnrichIconArtFilters(faceTypes)
    } else {
        homeCustomEnrichDefaultFilters(kind, preferSteamGridDb)
    },
    saved = iconArt?.saved ?: catalog.orderedForOpen(),
    activeAssetId = iconArt?.let { it.activeAssetId } ?: catalog.activeAssetId,
    catalogReady = catalogReady,
    defaultArt = defaultArt,
    mediaState = mediaState,
    mediaTypes = mediaTypes,
    mediaTypeFilters = mediaTypeFilters,
    shelfIconArt = iconArt != null,
    savedLanes = iconArt?.lanes.orEmpty(),
)

/** Source values in their normal order, so [HomeCustomEnrichFilter.cycle] behavior never changes —
 *  only [preferSteamGridDb] moves the STARTING selection from "Both" to "SteamGridDB". */
private fun homeCustomEnrichSourceFilter(preferSteamGridDb: Boolean): HomeCustomEnrichFilter {
    val values = listOf("Both", "SteamGridDB", "ScreenScraper")
    return HomeCustomEnrichFilter(
        "source",
        "Source",
        values,
        selectedIndex = if (preferSteamGridDb) values.indexOf("SteamGridDB") else 0,
    )
}

/**
 * The merged Icon art picker's filters . Type is every shape a provider can answer for this game
 * ([iconArtTypeValues]); Region is ScreenScraper's, Style and Size SteamGridDB's.
 *
 * Every filter OPENS on its all/any value, which is the first of its list (): Type All, Region Any, Style All (drawn "Any"), Size Any. Style used to open on "Clean" and so quietly narrowed the first search.
 */
internal fun homeCustomEnrichIconArtFilters(
    typeValues: List<String> = iconArtTypeValues(platformId = null, emulated = true),
): List<HomeCustomEnrichFilter> = listOf(
    HomeCustomEnrichFilter(IconArtTypeFilterKey, "Type", typeValues),
    HomeCustomEnrichFilter("region", "Region", listOf("Any", "World", "US", "EU", "JP")),
    HomeCustomEnrichFilter("style", "Style", listOf("All", "Clean", "Material", "Alternate")),
    HomeCustomEnrichFilter("size", "Size", listOf("Any", "512", "1024")),
)

/**
 * The chips each kind of picker opens with. Every one opens on its all/any value , the first of its list: Type All, Style All (drawn "Any"), Size Any,
 * Region Any, Format All, Fit Any (the "Best fit" aspect range is the second value, not the opening one), and Source Both, the all-sources value -- except for a PC
 * game, which opens on SteamGridDB, the only provider that ever answers for it (R-24).
 */
internal fun homeCustomEnrichDefaultFilters(
    kind: EnrichMediaKind,
    preferSteamGridDb: Boolean = false,
): List<HomeCustomEnrichFilter> = when (kind) {
    EnrichMediaKind.ICON -> listOf(
        HomeCustomEnrichFilter("kind", "Type", listOf("All", "Grids", "Icons")),
        HomeCustomEnrichFilter("style", "Style", listOf("All", "Clean", "Material", "Alternate")),
        HomeCustomEnrichFilter("size", "Size", listOf("Any", "512", "1024")),
    )
    EnrichMediaKind.HERO -> listOf(
        homeCustomEnrichSourceFilter(preferSteamGridDb),
        HomeCustomEnrichFilter("size", "Size", listOf("Any", "HD", "2K+")),
        HomeCustomEnrichFilter("fit", "Fit", listOf("Any", "Mimir", "Wide")),
    )
    EnrichMediaKind.CLIP -> listOf(
        homeCustomEnrichSourceFilter(preferSteamGridDb),
        // Provider-neutral choices: Motion Art is SteamGridDB animated imagery; Video is
        // ScreenScraper motion video. A visible combination can no longer route to neither provider.
        HomeCustomEnrichFilter("encode", "Format", listOf("All", "Motion Art", "Video")),
    )
    // Tome cartridge art is an explicit opt-in ScreenScraper search (plus upload); the provider
    // exposes no truthful physical-media filter dimensions yet, and projection/render stay local-only.
    EnrichMediaKind.TOME_PHYSICAL -> emptyList()
    // A picker candidate is legitimate when labelled for what it is and explicitly chosen by the
    // player. Default to broad, labelled choices; both dimensions are visible rather than silently
    // constraining the provider request.
    //
    // Region especially. The automatic ladder leads with `wor`, but box-3D publishes NO `wor` at all
    // (verified live 2026-08-25: Metroid Prime's six are de/eu/fr/jp/uk/us). Inheriting that ladder here
    // as a hidden filter would quietly hide most of the available choices.
    EnrichMediaKind.COVER -> listOf(
        HomeCustomEnrichFilter("box", "Type", listOf("All", "Front", "3D", "Texture", "Back")),
        HomeCustomEnrichFilter("region", "Region", listOf("Any", "World", "US", "EU", "JP")),
    )
}

internal fun HomeCustomEnrichState.filterValue(key: String): String? =
    filters.firstOrNull { it.key == key }?.selected

/**
 * The floor between a BARREN search and the next attempt.
 *
 * A tuning value for the retry delay.
 */
internal const val HomeCustomEnrichRetryFloorMs = 900L

/**
 * May a picker search start now?
 *
 * Only a search that came back **empty or failed** arms the floor. A search that returned results is not
 * a retry, and paging through them must never be throttled — that distinction is the whole reason this
 * is a predicate rather than a blanket debounce on the submit.
 *
 * `lastEmptyOrFailedAt == Long.MIN_VALUE` means nothing has come back barren yet; the subtraction would
 * overflow, so the first search is always allowed.
 */
internal fun homeCustomEnrichRetryAllowed(
    nowMillis: Long,
    lastEmptyOrFailedAt: Long,
    floorMillis: Long = HomeCustomEnrichRetryFloorMs,
): Boolean = lastEmptyOrFailedAt == Long.MIN_VALUE ||
    (nowMillis - lastEmptyOrFailedAt) >= floorMillis

internal fun homeCustomEnrichClipUsesSteamGridDb(source: String, format: String): Boolean =
    source != "ScreenScraper" && format != "Video"

internal fun homeCustomEnrichClipUsesScreenScraper(source: String, format: String, page: Int): Boolean =
    page == 0 && source != "SteamGridDB" && format != "Motion Art"

/** A contextual filter that is not painted cannot silently constrain the provider request. */
internal fun HomeCustomEnrichState.effectiveFilterValue(key: String, hiddenDefault: String): String =
    if (visibleFilters().any { it.key == key }) filterValue(key) ?: hiddenDefault else hiddenDefault

internal fun customEnrichHeroMatchesFilters(
    candidate: CustomHeroCandidate,
    size: String,
    fit: String,
): Boolean {
    // Only provider-declared or full-asset-verified dimensions may constrain size/fit. Preview-derived
    // proxy bounds are left unknown on the candidate and therefore cannot discard a live result.
    val dimensionsKnown = candidate.width > 0 && candidate.height > 0
    if (!dimensionsKnown) return true
    val landscape = candidate.width > candidate.height
    val sizeMatches = when (size) {
        "HD" -> landscape &&
            validCustomHeroStaticApplyDimensions(candidate.width, candidate.height)
        "2K+" -> landscape && candidate.width >= 2_560 && candidate.height >= 1_000
        else -> true
    }
    val aspect = candidate.width.toFloat() / candidate.height
    val fitMatches = when (fit) {
        "Wide" -> aspect >= 2.8f
        "Mimir" -> aspect in 1.65f..3.4f
        else -> true
    }
    val applyQualityMatches = validCustomHeroStaticApplyDimensions(candidate.width, candidate.height)
    return sizeMatches && fitMatches && applyQualityMatches
}

internal fun HomeCustomEnrichState.appendSearchResult(
    expectedGeneration: Int,
    result: HomeCustomEnrichResult,
    provider: String,
    uniqueProviderRefHashes: Set<String>? = null,
): HomeCustomEnrichState {
    if (generation != expectedGeneration || searchStatus !is HomeCustomEnrichSearchStatus.Searching ||
        operation != HomeCustomEnrichOperation.IDLE
    ) return this
    if (results.any { it.stableId == result.stableId }) return this
    if (results.size >= homeCustomEnrichResultCap(kind)) return this
    // Saved is the durable history rail; Results is what this provider answered just now. The same
    // asset may honestly stand in both (policy), so a match already in Saved still lands here.
    // Binding its record makes selecting the fresh tile a local activation instead of a second
    // download — see homeCustomEnrichSavedPreviewIndex for the matching picker-art reuse.
    // ScreenScraper distinct media often share one path identity after query stripping, so binding and
    // cache reuse are allowed only when this response has exactly one candidate for that hash.
    val providerHash = result.providerReference?.let(::enrichProviderReferenceHash)
    val hashBindable = providerHash != null &&
        (uniqueProviderRefHashes == null || providerHash in uniqueProviderRefHashes) &&
        results.none { it.saved?.providerRefHash == providerHash }
    val savedMatch = if (hashBindable) {
        saved.firstOrNull { it.providerRefHash == providerHash }
    } else {
        null
    }
    val next = results + result.withSavedAsset(savedMatch)
    return copy(
        results = next,
        searchStatus = HomeCustomEnrichSearchStatus.Searching(provider, next.size),
    )
}

/**
 * The provider reference the gallery will record for [candidate] in [kind]'s lane — the exact string
 * [HomeCustomEnrichResult] exposes, so a Saved binding and a cached-preview lookup can never disagree.
 */
internal fun homeCustomEnrichProviderReference(
    kind: EnrichMediaKind,
    candidate: CustomHeroCandidate,
): String = when (kind) {
    // TOME_PHYSICAL results are projected through asPhysicalIconCandidate, whose reference is the url.
    EnrichMediaKind.TOME_PHYSICAL -> candidate.remoteUrl
    else -> candidate.providerAssetId ?: candidate.remoteUrl
}

/**
 * Response-scoped hashes that may bind Saved or reuse its local bytes. A hash shared by two or more
 * current candidates is never bindable — every candidate still lands, none claim the Saved record.
 */
internal fun homeCustomEnrichUniqueProviderRefHashes(
    providerReferences: Iterable<String?>,
): Set<String> {
    val counts = LinkedHashMap<String, Int>()
    for (reference in providerReferences) {
        val hash = reference?.takeIf(String::isNotBlank)?.let(::enrichProviderReferenceHash) ?: continue
        counts[hash] = (counts[hash] ?: 0) + 1
    }
    return counts.filterValues { it == 1 }.keys
}

internal fun homeCustomEnrichUniqueProviderRefHashes(
    kind: EnrichMediaKind,
    candidates: List<CustomHeroCandidate>,
): Set<String> = homeCustomEnrichUniqueProviderRefHashes(
    candidates.map { homeCustomEnrichProviderReference(kind, it) },
)

/**
 * Saved local media keyed by provider-reference hash. An explicit rerun always re-asks the provider;
 * this only lets a match the user already owns paint from disk instead of transferring picker art
 * again. Entries with no reference or no recorded file are absent, so hydration falls back remotely.
 */
internal fun homeCustomEnrichSavedPreviewIndex(saved: List<EnrichSavedAsset>): Map<String, String> =
    saved.mapNotNull { asset ->
        val reference = asset.providerRefHash?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val preview = asset.previewPath.takeIf(String::isNotBlank) ?: return@mapNotNull null
        if (asset.mediaType in setOf(
                CustomHeroMediaType.VIDEO,
                CustomHeroMediaType.ANIMATED_IMAGE,
            ) && preview == asset.localPath
        ) {
            return@mapNotNull null
        }
        reference to preview
    }.toMap()

internal data class HomeCustomEnrichClipProviderBudgets(
    val steamGridDb: Int,
    val screenScraper: Int,
)

/** Reserves visible-result capacity for both providers instead of letting the first response starve the second. */
internal fun homeCustomEnrichClipProviderBudgets(
    source: String,
    format: String,
): HomeCustomEnrichClipProviderBudgets {
    val cap = homeCustomEnrichResultCap(EnrichMediaKind.CLIP)
    val usesSteamGridDb = homeCustomEnrichClipUsesSteamGridDb(source, format)
    val usesScreenScraper = homeCustomEnrichClipUsesScreenScraper(source, format, page = 0)
    return if (usesSteamGridDb && usesScreenScraper) {
        HomeCustomEnrichClipProviderBudgets(
            steamGridDb = (cap + 1) / 2,
            screenScraper = cap / 2,
        )
    } else {
        HomeCustomEnrichClipProviderBudgets(
            steamGridDb = cap.takeIf { usesSteamGridDb } ?: 0,
            screenScraper = cap.takeIf { usesScreenScraper } ?: 0,
        )
    }
}

/**
 * Local picker-art reuse for one candidate. Colliding response hashes return null so every candidate
 * hydrates remotely instead of painting the same Saved file.
 */
internal fun homeCustomEnrichCachedPreviewPath(
    savedPreviews: Map<String, String>,
    providerReference: String,
    uniqueProviderRefHashes: Set<String>,
): String? {
    val hash = enrichProviderReferenceHash(providerReference)
    if (hash !in uniqueProviderRefHashes) return null
    return savedPreviews[hash]
}

internal fun HomeCustomEnrichState.resultById(stableId: String): HomeCustomEnrichResult? =
    results.firstOrNull { it.stableId == stableId }

internal fun HomeCustomEnrichState.canRemove(asset: HomeCustomEnrichAsset): Boolean {
    if (!catalogReady || operation != HomeCustomEnrichOperation.IDLE) return false
    val savedId = when (asset) {
        HomeCustomEnrichAsset.Automatic -> null
        is HomeCustomEnrichAsset.Saved -> asset.record.assetId
        is HomeCustomEnrichAsset.Result -> asset.result.saved?.assetId
        HomeCustomEnrichAsset.More -> null
    }
    return savedId != null && savedId != activeAssetId
}

internal fun HomeCustomEnrichResult.withSavedAsset(asset: EnrichSavedAsset?): HomeCustomEnrichResult = when (this) {
    is HomeCustomEnrichResult.Icon -> copy(saved = asset)
    is HomeCustomEnrichResult.Hero -> copy(saved = asset)
    is HomeCustomEnrichResult.Clip -> copy(saved = asset)
}

internal fun homeCustomEnrichSavedSource(result: HomeCustomEnrichResult): EnrichSavedSource = when (result) {
    is HomeCustomEnrichResult.Icon -> result.candidate.savedSource
    is HomeCustomEnrichResult.Hero -> when (result.candidate.source) {
        CustomHeroSource.STEAMGRIDDB -> EnrichSavedSource.STEAMGRIDDB
        CustomHeroSource.SCREENSCRAPER -> EnrichSavedSource.SCREENSCRAPER
    }
    is HomeCustomEnrichResult.Clip -> when (result.candidate.source) {
        CustomHeroSource.STEAMGRIDDB -> EnrichSavedSource.STEAMGRIDDB
        CustomHeroSource.SCREENSCRAPER -> EnrichSavedSource.SCREENSCRAPER
    }
}

internal sealed interface HomeCustomEnrichCommand {
    data object None : HomeCustomEnrichCommand
    data class Search(
        val generation: Int,
        val query: String,
        val page: Int = 0,
        val append: Boolean = false,
    ) : HomeCustomEnrichCommand
    data object CancelSearch : HomeCustomEnrichCommand
    data object Back : HomeCustomEnrichCommand
    data object OpenKeyboard : HomeCustomEnrichCommand
    /** L1/R1 or the Media panel: show another (kind, state) on the same page. The caller builds its picker. */
    data class SwitchMedia(val selection: CustomMediaSelection) : HomeCustomEnrichCommand
    data object Upload : HomeCustomEnrichCommand
    data class PrepareExpandedPreview(
        val resultId: String,
        val priorLocalPath: String? = null,
    ) : HomeCustomEnrichCommand
    data object CancelExpandedPreview : HomeCustomEnrichCommand
    data object Automatic : HomeCustomEnrichCommand
    data class ActivateSaved(val assetId: String) : HomeCustomEnrichCommand
    data class ApplyResult(val resultId: String, val activate: Boolean) : HomeCustomEnrichCommand
    data class RemoveSaved(val assetId: String) : HomeCustomEnrichCommand
}

internal data class HomeCustomEnrichTransition(
    val state: HomeCustomEnrichState,
    val command: HomeCustomEnrichCommand = HomeCustomEnrichCommand.None,
)

/**
 * Device evidence: CONFIRM on a focused keyboard key opened Customize Media and navigated, but
 * never typed a character. Reconciles a [reduceHomeCustomEnrich] [transition] against the
 * caller's live picker storage before it gets written back.
 *
 * [reduceHomeCustomEnrich]'s keyboardOpen branch dispatches CONFIRM through
 * `dispatchMimirKeyboardInput` -> `MimirKeyboardFocusBinding.activateFocused()`, whose activator closures
 * write typed characters (and the Done key's close) SYNCHRONOUSLY and OUT-OF-BAND — straight into the
 * caller's own picker storage via `onCustomEnrichQueryChange` / `onCustomEnrichKeyboardDone`
 * (`MimirAppHost.kt`) — DURING the reducer call itself, before `transition` is even constructed. When that
 * happens, the reducer makes no structural change of its own and returns its OWN input object unchanged
 * (`HomeCustomEnrichTransition(state)`, no `.copy()` — see the keyboardOpen branch), so `transition.state
 * === preDispatchPicker` holds by reference. A caller that unconditionally writes `transition.state` back
 * in that case silently overwrites the character (or close) the activator JUST wrote with the stale
 * pre-dispatch snapshot — the whole keyboard read as "navigation works, nothing types". Confirmed by
 * `confirmOnFocusedCharacterKeySurvivesTheCoordinatorWriteBack` (HomeCustomEnrichPickerTest.kt): CONFIRM
 * on a focused key returns `true` from `activateFocused()` and reaches `onQueryChange`, but without this
 * reconciliation the typed character never survives the write-back. `reduceHomeCustomEnrich` never
 * mutates `queryDraft`/`note` itself on that
 * branch (only the out-of-band callback does), so preferring [live] whenever the reducer made no change of
 * its own can never discard a reducer-authored change — the `===` check is exact, not a heuristic.
 */
internal fun reconcileCustomEnrichPickerForWrite(
    preDispatchPicker: HomeCustomEnrichState,
    transition: HomeCustomEnrichTransition,
    live: HomeCustomEnrichState?,
): HomeCustomEnrichState = if (transition.state === preDispatchPicker) {
    live ?: transition.state
} else {
    transition.state
}

internal fun homeCustomEnrichPrefix(state: HomeCustomEnrichState): String =
    "custom-enrich/${state.cacheKey.hashCode().toUInt().toString(16)}/${state.kind.wireName}"

internal fun homeCustomEnrichSearchKey(state: HomeCustomEnrichState): FocusKey =
    FocusKey("${homeCustomEnrichPrefix(state)}/search")

internal fun homeCustomEnrichFilterKey(state: HomeCustomEnrichState, filter: HomeCustomEnrichFilter): FocusKey =
    FocusKey("${homeCustomEnrichPrefix(state)}/filter/${filter.key}")

internal fun homeCustomEnrichFilterOptionKey(
    state: HomeCustomEnrichState,
    filter: HomeCustomEnrichFilter,
    optionIndex: Int,
): FocusKey = FocusKey("${homeCustomEnrichPrefix(state)}/filter/${filter.key}/option/$optionIndex")

internal fun homeCustomEnrichAssetKey(state: HomeCustomEnrichState, asset: HomeCustomEnrichAsset): FocusKey =
    FocusKey("${homeCustomEnrichPrefix(state)}/asset/${asset.stableId}")

internal fun homeCustomEnrichUploadKey(state: HomeCustomEnrichState): FocusKey =
    FocusKey("${homeCustomEnrichPrefix(state)}/upload")

internal fun homeCustomEnrichRetryKey(state: HomeCustomEnrichState): FocusKey =
    FocusKey("${homeCustomEnrichPrefix(state)}/retry")

internal fun homeCustomEnrichLaneKeys(
    state: HomeCustomEnrichState,
    folded: Boolean = CustomMediaFold.isFolded(state.sessionId),
): Map<HomeCustomEnrichLane, List<FocusKey>> =
    linkedMapOf(
        HomeCustomEnrichLane.SEARCH to listOf(homeCustomEnrichSearchKey(state)),
        HomeCustomEnrichLane.FILTERS to customMediaTopKeys(state, folded) + state.visibleFilters().flatMap { filter ->
            if (filter.key == state.openFilterKey) {
                filter.values.indices.map { optionIndex ->
                    homeCustomEnrichFilterOptionKey(state, filter, optionIndex)
                }
            } else if (state.mediaTypes.isNotEmpty() && folded) {
                // Folded into the Filters chip: the chips have no stops of their own.
                emptyList()
            } else {
                listOf(homeCustomEnrichFilterKey(state, filter))
            }
        },
        HomeCustomEnrichLane.GALLERY to state.assets.map { homeCustomEnrichAssetKey(state, it) },
        HomeCustomEnrichLane.FOOTER to buildList {
            if (state.kind != EnrichMediaKind.CLIP) add(homeCustomEnrichUploadKey(state))
            if (state.searchControl() != HomeCustomEnrichSearchControl.NONE) {
                add(homeCustomEnrichRetryKey(state))
            }
        },
    )

internal fun homeCustomEnrichInitialKey(state: HomeCustomEnrichState): FocusKey {
    val asset = state.assets.firstOrNull { it is HomeCustomEnrichAsset.Saved && it.record.assetId == state.activeAssetId }
        ?: HomeCustomEnrichAsset.Automatic
    return homeCustomEnrichAssetKey(state, asset)
}

/**
 * Whether [asset] is the one in use now: exactly ONE tile of the gallery is, and it wears the check mark .
 *
 * Default is in use when no saved choice is active. A found tile is in use only when it is bound to the active saved
 * record: an unsaved result has no record, and "no record" is not "the active record" even while Default is in use
 * (`null == null` made every unsaved result read as in use, so each wore a check). The same record may also stand in
 * the Saved rail and in Results (policy); the Saved tile is the one in use then, never both.
 */
internal fun homeCustomEnrichAssetIsActive(
    state: HomeCustomEnrichState,
    asset: HomeCustomEnrichAsset,
): Boolean = when (asset) {
    HomeCustomEnrichAsset.Automatic -> state.activeAssetId == null
    is HomeCustomEnrichAsset.Saved -> asset.record.assetId == state.activeAssetId
    is HomeCustomEnrichAsset.Result -> {
        val boundId = asset.result.saved?.assetId
        boundId != null && boundId == state.activeAssetId && state.saved.none { it.assetId == boundId }
    }
    HomeCustomEnrichAsset.More -> false
}

/**
 * Presentation-only preview identity. Gallery focus wins; focus in another lane retains the gallery's
 * remembered identity; then the active saved choice is used. `More` deliberately keeps the preceding
 * result visible so paging never replaces real art with a fabricated placeholder.
 */
internal fun homeCustomEnrichPreviewAsset(
    state: HomeCustomEnrichState,
    focusedKey: FocusKey?,
): HomeCustomEnrichAsset {
    val assets = state.assets
    fun normalize(candidate: HomeCustomEnrichAsset?): HomeCustomEnrichAsset? = when (candidate) {
        HomeCustomEnrichAsset.More -> assets.getOrNull(assets.indexOf(candidate) - 1)
            ?.takeUnless { it == HomeCustomEnrichAsset.More }
        else -> candidate
    }

    val focused = assets.firstOrNull { homeCustomEnrichAssetKey(state, it) == focusedKey }
    val remembered = state.laneMemory[HomeCustomEnrichLane.GALLERY]?.let { rememberedKey ->
        assets.firstOrNull { homeCustomEnrichAssetKey(state, it).value == rememberedKey }
    }
    val active = assets.firstOrNull { homeCustomEnrichAssetIsActive(state, it) }
    return normalize(focused) ?: normalize(remembered) ?: active ?: HomeCustomEnrichAsset.Automatic
}

/** What the preview says of the Default tile. Its badge on the tile already says DEFAULT, so the caption never draws it . */
internal const val HomeCustomEnrichPreviewDefaultStatus = "DEFAULT"

/** Whether the preview caption draws [status]: everything but a second "DEFAULT" for the Default tile. */
internal fun homeCustomEnrichPreviewCaptionShown(asset: HomeCustomEnrichAsset, status: String): Boolean =
    !(asset == HomeCustomEnrichAsset.Automatic && status == HomeCustomEnrichPreviewDefaultStatus)

/** Short visual state only; the existing status lane remains the textual authority. */
internal fun homeCustomEnrichPreviewStatus(
    state: HomeCustomEnrichState,
    asset: HomeCustomEnrichAsset,
): String {
    when (state.operation) {
        HomeCustomEnrichOperation.APPLY_FETCHING -> return "PREPARING"
        HomeCustomEnrichOperation.APPLY_COMMITTING -> return "FINISHING"
        HomeCustomEnrichOperation.IDLE -> Unit
    }
    when (state.searchStatus) {
        is HomeCustomEnrichSearchStatus.Searching -> return "SEARCHING"
        is HomeCustomEnrichSearchStatus.NeedsAccess,
        is HomeCustomEnrichSearchStatus.Busy,
        is HomeCustomEnrichSearchStatus.Failed,
        is HomeCustomEnrichSearchStatus.Unreachable,
        is HomeCustomEnrichSearchStatus.ProviderFailed,
        -> return "SEARCH UNAVAILABLE"
        else -> Unit
    }
    return when {
        asset == HomeCustomEnrichAsset.Automatic -> HomeCustomEnrichPreviewDefaultStatus
        homeCustomEnrichResultAwaitingPrepare(asset) -> "SELECT TO PREPARE"
        asset.previewPath.isNullOrBlank() -> "PREVIEW UNAVAILABLE"
        homeCustomEnrichAssetIsActive(state, asset) -> "CURRENT"
        asset is HomeCustomEnrichAsset.Saved -> "SAVED"
        asset is HomeCustomEnrichAsset.Result && asset.result.saved != null -> "SAVED"
        asset is HomeCustomEnrichAsset.Result -> "FOUND"
        else -> "PREVIEW"
    }
}

/** Pure label for inline/tile fallback when a selectable remote result has no usable local preview. */
internal fun homeCustomEnrichResultAwaitingPrepare(asset: HomeCustomEnrichAsset): Boolean =
    asset is HomeCustomEnrichAsset.Result &&
        asset.result.saved == null &&
        asset.previewPath.isNullOrBlank()

internal fun homeCustomEnrichPreviewActionLabel(
    asset: HomeCustomEnrichAsset,
    previewUnavailable: Boolean = false,
): String = when {
    homeCustomEnrichResultAwaitingPrepare(asset) ||
        (previewUnavailable && asset is HomeCustomEnrichAsset.Result && asset.result.saved == null) ->
        "SELECT TO PREPARE"
    else -> "PREVIEW UNAVAILABLE"
}

internal fun homeCustomEnrichPreviewActionVisual(label: String): String = when (label) {
    "SELECT TO PREPARE" -> "ᛋ · SELECT TO PREPARE"
    else -> "ᚾ · PREVIEW UNAVAILABLE"
}

internal fun homeCustomEnrichPreviewCanExpand(
    state: HomeCustomEnrichState,
    asset: HomeCustomEnrichAsset,
): Boolean {
    //  COVER joins HERO and CLIP.
    //
    // ICON stays out on purpose: a 48dp tile IS its own full size, so an "expanded" icon would be an
    // upscale of something already fully visible. A cover is the opposite case — it is the one kind
    // whose whole point is judging art by eye before committing it to a shelf he looks at every day.
    if (
        state.kind != EnrichMediaKind.HERO &&
        state.kind != EnrichMediaKind.CLIP &&
        state.geometryKind != EnrichMediaKind.COVER
    ) {
        return false
    }
    return when (asset) {
        is HomeCustomEnrichAsset.Saved -> asset.record.localPath.isNotBlank()
        is HomeCustomEnrichAsset.Result -> when (asset.result) {
            is HomeCustomEnrichResult.Hero,
            is HomeCustomEnrichResult.Clip,
            -> asset.result.saved?.localPath?.isNotBlank() ?: true
            // A cover candidate is expandable as soon as it has a file to expand. Cover results reuse
            // the Icon result type -- the cover lane is fed by the same provider candidate -- so this
            // branch carries BOTH kinds and cannot answer on the type alone.
            is HomeCustomEnrichResult.Icon ->
                state.geometryKind == EnrichMediaKind.COVER && !asset.result.previewPath.isNullOrBlank()
        }
        HomeCustomEnrichAsset.Automatic ->
            state.kind == EnrichMediaKind.HERO &&
                state.defaultArt != HomeCustomEnrichDefaultArt.Unavailable
        HomeCustomEnrichAsset.More -> false
    }
}

internal fun homeCustomEnrichExpandedPreviewNeedsPrepare(
    asset: HomeCustomEnrichAsset,
): String? {
    val result = (asset as? HomeCustomEnrichAsset.Result)?.result ?: return null
    if (result.saved != null) return null
    return when (result) {
        is HomeCustomEnrichResult.Hero,
        is HomeCustomEnrichResult.Clip,
        -> result.stableId
        is HomeCustomEnrichResult.Icon -> null
    }
}

internal fun homeCustomEnrichExpandedPreviewPath(
    state: HomeCustomEnrichState,
    asset: HomeCustomEnrichAsset,
): String? = when (asset) {
    is HomeCustomEnrichAsset.Saved -> asset.record.localPath.takeIf(String::isNotBlank)
    is HomeCustomEnrichAsset.Result -> asset.result.saved
        ?.localPath
        ?.takeIf(String::isNotBlank)
        ?: (state.expandedPreview as? HomeCustomEnrichExpandedPreview.Ready)
            ?.takeIf { it.resultId == asset.result.stableId }
            ?.localPath
            ?.takeIf(String::isNotBlank)
        // COVER only, and it is the difference between this surface working and showing an empty
        // stage. Hero and clip stage a full-quality inspection file first (`needsPrepare`), and the
        // rule above them -- never promote the bounded gallery thumbnail -- exists because their
        // thumbnail really is a 640x360 reduction. A cover has NO second fetch: `pickerArtPath` is the
        // art itself, and refusing it here would mean SELECT opened a blank panel.
        ?: asset.result
            .takeIf { state.geometryKind == EnrichMediaKind.COVER }
            ?.previewPath
            ?.takeIf(String::isNotBlank)
    HomeCustomEnrichAsset.Automatic,
    HomeCustomEnrichAsset.More,
    -> null
}

/** The expanded surface names what it is showing. It called everything "Hero" until COVER joined it. */
internal fun homeCustomEnrichExpandedPreviewTitle(kind: EnrichMediaKind): String = when (kind) {
    EnrichMediaKind.CLIP -> "Hero Motion preview"
    EnrichMediaKind.COVER -> "Cover art preview"
    EnrichMediaKind.TOME_PHYSICAL -> "Cartridge art preview"
    EnrichMediaKind.ICON -> "Icon art preview"
    EnrichMediaKind.HERO -> "Hero art preview"
}

internal fun homeCustomEnrichExpandedPreviewTitleVisual(kind: EnrichMediaKind): String = when (kind) {
    EnrichMediaKind.CLIP -> "ᛗ · HERO MOTION PREVIEW"
    EnrichMediaKind.COVER -> "ᛒ · COVER ART PREVIEW"
    EnrichMediaKind.TOME_PHYSICAL -> "ᛏ · CARTRIDGE ART PREVIEW"
    EnrichMediaKind.ICON -> "ᛁ · ICON ART PREVIEW"
    EnrichMediaKind.HERO -> "ᚺ · HERO ART PREVIEW"
}

internal fun homeCustomEnrichExpandedToInlineLinearRatio(
    viewportHeightDp: Int,
    kind: EnrichMediaKind,
    compactSquare: Boolean,
    measuredGalleryHeightDp: Int,
): Float {
    val panelHeightFraction = if (compactSquare) 0.98f else 0.96f
    val expandedHeight = viewportHeightDp * panelHeightFraction * HomeCustomEnrichExpandedHeightFraction
    val inlineHeight = homeCustomEnrichPreviewHeightDp(
        measuredGalleryHeightDp = measuredGalleryHeightDp,
        kind = kind,
        compactSquare = compactSquare,
    ).coerceAtLeast(1)
    return expandedHeight / inlineHeight
}

/** The words the controller legend uses for the SELECT that opens the full preview (MimirCopy.HintMediaPreviewable). */
internal const val HomeCustomEnrichSelectPreviewWords = "SELECT PREVIEW"

/**
 * Whether the footer shows the SELECT · EXPAND cue: ONE hint for that action, never two. When the controller [legend] already says
 * "SELECT PREVIEW" (the gallery has focus, which is where the legend names the keys), the cue is not stacked above it; it stands alone where the legend is another lane's (Search, Filters, Upload) and says nothing of SELECT.
 */
internal fun homeCustomEnrichFooterSaysExpand(
    state: HomeCustomEnrichState,
    focusedKey: FocusKey?,
    legend: String?,
): Boolean = legend?.contains(HomeCustomEnrichSelectPreviewWords) != true && homeCustomEnrichShowsExpandHint(state, focusedKey)

/**
 * Whether the preview could be expanded by SELECT, for Hero art and Hero Motion: the cue's precondition ([homeCustomEnrichFooterSaysExpand]
 * decides whether the footer shows it). A cover's SELECT is in its legend ("SELECT PREVIEW") and the cue is never repeated there.
 */
internal fun homeCustomEnrichShowsExpandHint(
    state: HomeCustomEnrichState,
    focusedKey: FocusKey?,
): Boolean {
    if (state.kind != EnrichMediaKind.HERO && state.kind != EnrichMediaKind.CLIP) return false
    if (!state.catalogReady) return false
    if (state.operation != HomeCustomEnrichOperation.IDLE) return false
    if (state.keyboardOpen) return false
    val asset = homeCustomEnrichPreviewAsset(state, focusedKey)
    return homeCustomEnrichPreviewCanExpand(state, asset)
}

private fun submitHomeCustomMediaSearch(
    state: HomeCustomEnrichState,
    remembered: Map<HomeCustomEnrichLane, String>,
): HomeCustomEnrichTransition {
    val query = state.queryDraft.trim()
    if (query.isEmpty()) return HomeCustomEnrichTransition(state.copy(note = "Enter a search first."))
    val nextGeneration = state.generation + 1
    val normalizedSaved = (state.saved + state.results.mapNotNull(HomeCustomEnrichResult::saved))
        .distinctBy(EnrichSavedAsset::assetId)
    return HomeCustomEnrichTransition(
        state.copy(
            generation = nextGeneration,
            submittedQuery = query,
            saved = normalizedSaved,
            results = emptyList(),
            page = 0,
            hasMore = false,
            searchStatus = HomeCustomEnrichSearchStatus.Searching("Community media", 0),
            keyboardOpen = false,
            note = null,
            laneMemory = remembered,
        ),
        HomeCustomEnrichCommand.Search(nextGeneration, query, page = 0, append = false),
    )
}

private fun cancelHomeCustomMediaSearch(state: HomeCustomEnrichState): HomeCustomEnrichTransition =
    HomeCustomEnrichTransition(
        state.copy(
            generation = state.generation + 1,
            searchStatus = HomeCustomEnrichSearchStatus.Cancelled(state.results.size),
        ),
        HomeCustomEnrichCommand.CancelSearch,
    )

/** Pure command reducer; FocusAuthority remains the only focused-key truth. */
internal fun reduceHomeCustomEnrich(
    authority: FocusAuthority,
    state: HomeCustomEnrichState,
    action: ControllerAction,
): HomeCustomEnrichTransition {
    val searchKey = homeCustomEnrichSearchKey(state)
    if (state.previewExpanded) {
        return if (
            action == ControllerAction.CANCEL ||
            action == ControllerAction.SYSTEM_BACK ||
            action == ControllerAction.SYSTEM_SELECT
        ) {
            val wasLoading = state.expandedPreview is HomeCustomEnrichExpandedPreview.Loading
            HomeCustomEnrichTransition(
                state.copy(
                    previewExpanded = false,
                    expandedPreview = if (wasLoading) {
                        HomeCustomEnrichExpandedPreview.None
                    } else {
                        state.expandedPreview
                    },
                ),
                if (wasLoading) {
                    HomeCustomEnrichCommand.CancelExpandedPreview
                } else {
                    HomeCustomEnrichCommand.None
                },
            )
        } else {
            HomeCustomEnrichTransition(state)
        }
    }
    if (state.keyboardOpen) {
        val closingTransition = HomeCustomEnrichTransition(state.copy(keyboardOpen = false))
        val host = HomeCustomEnrichKeyboardHostHolder.hostFor(state.sessionId)
        if (host != null) {
            val handled = com.gruning.mimir.rewrite.core.input.dispatchMimirKeyboardInput(
                authority = authority,
                scope = com.gruning.mimir.rewrite.core.input.MimirKeyboardFocusScope,
                action = action,
                binding = host.binding,
                onDismiss = { authority.requestFocus(searchKey) },
            )
            if (handled) {
                return if (action == ControllerAction.CANCEL || action == ControllerAction.SYSTEM_BACK) {
                    closingTransition
                } else {
                    HomeCustomEnrichTransition(state)
                }
            }
        }
        return if (action == ControllerAction.CANCEL || action == ControllerAction.SYSTEM_BACK) {
            authority.requestFocus(searchKey)
            closingTransition
        } else {
            HomeCustomEnrichTransition(state)
        }
    }
    state.openFilterKey?.let { openFilterKey ->
        val openFilter = state.visibleFilters().firstOrNull { it.key == openFilterKey }
            ?: return HomeCustomEnrichTransition(state.copy(openFilterKey = null))
        val optionKeys = openFilter.values.indices.map { optionIndex ->
            homeCustomEnrichFilterOptionKey(state, openFilter, optionIndex)
        }
        val focusedOptionIndex = optionKeys.indexOf(authority.state.value.focused)
        when (action) {
            ControllerAction.CANCEL,
            ControllerAction.SYSTEM_BACK,
            -> {
                // Focus returns to the chip, or to the Filters chip when the chips are folded into it.
                val filterKey = customMediaFilterReturnKey(state, homeCustomEnrichFilterKey(state, openFilter))
                authority.requestFocus(filterKey)
                return HomeCustomEnrichTransition(
                    state.copy(
                        openFilterKey = null,
                        laneMemory = state.laneMemory +
                            (HomeCustomEnrichLane.FILTERS to filterKey.value),
                    ),
                )
            }
            ControllerAction.NAV_UP,
            ControllerAction.NAV_DOWN,
            -> {
                val delta = if (action == ControllerAction.NAV_UP) -1 else 1
                val target = optionKeys.getOrNull(focusedOptionIndex + delta)
                if (target != null) {
                    authority.requestFocus(target)
                    MimirNavFeedback.consumed()
                }
                return HomeCustomEnrichTransition(state)
            }
            ControllerAction.CONFIRM -> if (focusedOptionIndex >= 0) {
                // Focus returns to the chip, or to the Filters chip when the chips are folded into it.
                val filterKey = customMediaFilterReturnKey(state, homeCustomEnrichFilterKey(state, openFilter))
                if (focusedOptionIndex == openFilter.selectedIndex) {
                    authority.requestFocus(filterKey)
                    return HomeCustomEnrichTransition(
                        state.copy(
                            openFilterKey = null,
                            laneMemory = state.laneMemory +
                                (HomeCustomEnrichLane.FILTERS to filterKey.value),
                        ),
                    )
                }
                val normalizedSaved = (state.saved + state.results.mapNotNull(HomeCustomEnrichResult::saved))
                    .distinctBy(EnrichSavedAsset::assetId)
                val filterIndex = state.filters.indexOfFirst { it.key == openFilter.key }
                return HomeCustomEnrichTransition(
                    state.copy(
                        filters = state.filters.mapIndexed { index, filter ->
                            if (index == filterIndex) filter.copy(selectedIndex = focusedOptionIndex) else filter
                        },
                        openFilterKey = null,
                        generation = state.generation + 1,
                        submittedQuery = null,
                        saved = normalizedSaved,
                        results = emptyList(),
                        page = 0,
                        hasMore = false,
                        searchStatus = HomeCustomEnrichSearchStatus.Idle,
                        note = "Filters changed · search again",
                        laneMemory = state.laneMemory +
                            (HomeCustomEnrichLane.FILTERS to filterKey.value),
                    ),
                )
            }
            else -> return HomeCustomEnrichTransition(state)
        }
    }
    customMediaPanelReduce(authority, state, action)?.let { return it }
    // A confirmed gallery result owns the picker through remote staging and durable activation.
    // Controller, touch-back, and system-back input cannot interrupt that hand-picked transaction.
    if (state.operation != HomeCustomEnrichOperation.IDLE) return HomeCustomEnrichTransition(state)
    if (action == ControllerAction.CANCEL || action == ControllerAction.SYSTEM_BACK) {
        if (state.searchStatus is HomeCustomEnrichSearchStatus.Searching) {
            return cancelHomeCustomMediaSearch(state)
        }
        return HomeCustomEnrichTransition(state, HomeCustomEnrichCommand.Back)
    }
    if (!state.catalogReady && !action.isDirectional) return HomeCustomEnrichTransition(state)

    val graph = homeCustomEnrichLaneKeys(state)
    val focused = authority.state.value.focused ?: homeCustomEnrichInitialKey(state)
    if (action == ControllerAction.SYSTEM_SELECT) {
        val previewAsset = homeCustomEnrichPreviewAsset(state, focused)
        return if (homeCustomEnrichPreviewCanExpand(state, previewAsset)) {
            val resultId = homeCustomEnrichExpandedPreviewNeedsPrepare(previewAsset)
            val ready = (state.expandedPreview as? HomeCustomEnrichExpandedPreview.Ready)
                ?.takeIf { it.resultId == resultId }
            if (resultId != null && ready == null) {
                val priorLocalPath = (state.expandedPreview as? HomeCustomEnrichExpandedPreview.Ready)
                    ?.localPath
                HomeCustomEnrichTransition(
                    state.copy(
                        previewExpanded = true,
                        expandedPreview = HomeCustomEnrichExpandedPreview.Loading(resultId),
                    ),
                    HomeCustomEnrichCommand.PrepareExpandedPreview(resultId, priorLocalPath),
                )
            } else {
                HomeCustomEnrichTransition(state.copy(previewExpanded = true))
            }
        } else {
            HomeCustomEnrichTransition(state)
        }
    }
    val lane = graph.entries.firstOrNull { focused in it.value }?.key ?: HomeCustomEnrichLane.GALLERY
    // Search and its filters share ONE last-key identity: whichever of the two held focus last is
    // what Up from the gallery restores — mirrored into both slots so lookup order can't lie.
    val remembered = if (lane == HomeCustomEnrichLane.SEARCH || lane == HomeCustomEnrichLane.FILTERS) {
        state.laneMemory +
            (HomeCustomEnrichLane.SEARCH to focused.value) +
            (HomeCustomEnrichLane.FILTERS to focused.value)
    } else {
        state.laneMemory + (lane to focused.value)
    }
    customMediaTopRowReduce(authority, state, action, focused, graph, remembered)?.let { return it }
    if (action.isDirectional) {
        val keys = graph.getValue(lane)
        val target = when (action) {
            ControllerAction.NAV_LEFT, ControllerAction.NAV_RIGHT -> {
                val currentIndex = keys.indexOf(focused).coerceAtLeast(0)
                val delta = if (action == ControllerAction.NAV_LEFT) -1 else 1
                keys.getOrNull(currentIndex + delta)
                    ?: when {
                        // The search field and its filter chips read as one row; Left/Right cross
                        // that boundary naturally instead of dead-ending inside the lane.
                        lane == HomeCustomEnrichLane.SEARCH && action == ControllerAction.NAV_RIGHT ->
                            graph.getValue(HomeCustomEnrichLane.FILTERS).firstOrNull()
                        lane == HomeCustomEnrichLane.FILTERS && action == ControllerAction.NAV_LEFT ->
                            searchKey
                        else -> null
                    }
            }
            ControllerAction.NAV_UP, ControllerAction.NAV_DOWN -> {
                // Search and its filters are one logical row: Up from either is the top of the
                // graph (no-op), Down from either lands in the gallery, and Up from the gallery
                // restores the row's last stable key — search or filter alike.
                val topRow = graph.getValue(HomeCustomEnrichLane.SEARCH) + graph.getValue(HomeCustomEnrichLane.FILTERS)
                // The Media page draws Search, Media, the chips, but a way up from below lands on the type control (Media), not on
                // the search: it is the row's first FILTERS stop. (A row the pad was on before still restores its own last stop.)
                val topLanding = if (state.mediaTypes.isEmpty()) topRow.firstOrNull() else graph.getValue(HomeCustomEnrichLane.FILTERS).firstOrNull()
                val rows = listOf(
                    topRow,
                    graph.getValue(HomeCustomEnrichLane.GALLERY),
                    graph.getValue(HomeCustomEnrichLane.FOOTER),
                ).filter { it.isNotEmpty() }
                val currentRowIndex = rows.indexOfFirst { focused in it }.coerceAtLeast(0)
                val delta = if (action == ControllerAction.NAV_UP) -1 else 1
                rows.getOrNull(currentRowIndex + delta)?.let { nextRow ->
                    val rowMemory = listOf(
                        remembered[HomeCustomEnrichLane.SEARCH],
                        remembered[HomeCustomEnrichLane.FILTERS],
                        remembered[HomeCustomEnrichLane.GALLERY],
                        remembered[HomeCustomEnrichLane.FOOTER],
                    ).mapNotNull { it?.let(::FocusKey) }
                    rowMemory.firstOrNull { it in nextRow }
                        ?: homeCustomEnrichInitialKey(state).takeIf { it in nextRow }
                        ?: (if (nextRow == topRow) topLanding else nextRow.firstOrNull())
                }
            }
            else -> null
        }
        if (target != null && target != focused) {
            authority.requestFocus(target)
            MimirNavFeedback.consumed()
            val targetLane = graph.entries.first { target in it.value }.key
            val landed = if (targetLane == HomeCustomEnrichLane.SEARCH ||
                targetLane == HomeCustomEnrichLane.FILTERS
            ) {
                remembered +
                    (HomeCustomEnrichLane.SEARCH to target.value) +
                    (HomeCustomEnrichLane.FILTERS to target.value)
            } else {
                remembered + (targetLane to target.value)
            }
            return HomeCustomEnrichTransition(state.copy(laneMemory = landed))
        }
        return HomeCustomEnrichTransition(state.copy(laneMemory = remembered))
    }

    if (action == ControllerAction.CONFIRM) {
        if (focused == searchKey) {
            if (state.searchStatus is HomeCustomEnrichSearchStatus.Searching) {
                return HomeCustomEnrichTransition(state)
            }
            return HomeCustomEnrichTransition(
                state.copy(keyboardOpen = true, laneMemory = remembered),
                HomeCustomEnrichCommand.OpenKeyboard,
            )
        }
        state.visibleFilters().firstOrNull { homeCustomEnrichFilterKey(state, it) == focused }
            ?.let { visible -> state.filters.indexOf(visible) }
            ?.takeIf { it >= 0 }
            ?.let { index ->
                if (state.searchStatus is HomeCustomEnrichSearchStatus.Searching) {
                    return HomeCustomEnrichTransition(state)
                }
                return HomeCustomEnrichTransition(
                    state.copy(
                        openFilterKey = state.filters[index].key,
                        laneMemory = remembered,
                    ),
                )
            }
        state.assets.firstOrNull { homeCustomEnrichAssetKey(state, it) == focused }?.let { asset ->
            return when (asset) {
                HomeCustomEnrichAsset.Automatic -> HomeCustomEnrichTransition(
                    state.copy(operation = HomeCustomEnrichOperation.APPLY_COMMITTING, laneMemory = remembered),
                    HomeCustomEnrichCommand.Automatic,
                )
                is HomeCustomEnrichAsset.Saved -> HomeCustomEnrichTransition(
                    state.copy(operation = HomeCustomEnrichOperation.APPLY_COMMITTING, laneMemory = remembered),
                    HomeCustomEnrichCommand.ActivateSaved(asset.record.assetId),
                )
                is HomeCustomEnrichAsset.Result -> asset.result.saved?.let { saved ->
                    HomeCustomEnrichTransition(
                        state.copy(operation = HomeCustomEnrichOperation.APPLY_COMMITTING, laneMemory = remembered),
                        HomeCustomEnrichCommand.ActivateSaved(saved.assetId),
                    )
                } ?: HomeCustomEnrichTransition(
                    state.copy(operation = HomeCustomEnrichOperation.APPLY_FETCHING, laneMemory = remembered),
                    HomeCustomEnrichCommand.ApplyResult(asset.result.stableId, activate = true),
                )
                HomeCustomEnrichAsset.More -> {
                    val query = state.submittedQuery ?: state.queryDraft.trim()
                    if (query.isBlank() || state.searchStatus is HomeCustomEnrichSearchStatus.Searching) {
                        HomeCustomEnrichTransition(state)
                    } else {
                        state.results.lastOrNull()?.let { last ->
                            authority.requestFocus(
                                homeCustomEnrichAssetKey(state, HomeCustomEnrichAsset.Result(last)),
                            )
                        }
                        val nextPage = state.page + 1
                        HomeCustomEnrichTransition(
                            state.copy(
                                searchStatus = HomeCustomEnrichSearchStatus.Searching("Community media", state.results.size),
                                note = null,
                                laneMemory = remembered,
                            ),
                            HomeCustomEnrichCommand.Search(
                                generation = state.generation,
                                query = query,
                                page = nextPage,
                                append = true,
                            ),
                        )
                    }
                }
            }
        }
        if (focused == homeCustomEnrichUploadKey(state) && state.kind != EnrichMediaKind.CLIP) {
            return HomeCustomEnrichTransition(state.copy(laneMemory = remembered), HomeCustomEnrichCommand.Upload)
        }
        if (focused == homeCustomEnrichRetryKey(state)) {
            return when (state.searchControl()) {
                HomeCustomEnrichSearchControl.RETRY -> submitHomeCustomMediaSearch(state, remembered)
                HomeCustomEnrichSearchControl.STOP -> cancelHomeCustomMediaSearch(state)
                HomeCustomEnrichSearchControl.NONE -> HomeCustomEnrichTransition(state)
            }
        }
    }
    //  Search answers on Y (TERTIARY) here, as it already does in
    // the file manager -- `FileBrowserDispatch.kt`'s F-08 arm, legended `Y SEARCH`. This surface was the
    // last one in the product still answering search on X, and the collision was inside ONE modal: X
    // submitted the query on the search field and removed a saved asset one lane down, so the same key did
    // two unrelated things depending on where focus happened to be. After this it does one.
    //
    // Its own arm rather than a second condition inside SECONDARY, deliberately: the legend
    // ([MimirCopy.HintSearch]) and the dispatcher have to be readable as the same statement, and a shared
    // arm is how they drift.
    if (action == ControllerAction.TERTIARY && focused == searchKey) {
        if (state.searchStatus is HomeCustomEnrichSearchStatus.Searching) {
            return HomeCustomEnrichTransition(state)
        }
        return submitHomeCustomMediaSearch(state, remembered)
    }
    if (action == ControllerAction.SECONDARY) {
        state.assets.firstOrNull { homeCustomEnrichAssetKey(state, it) == focused }?.let { asset ->
            return when (asset) {
                is HomeCustomEnrichAsset.Result -> asset.result.saved?.let { saved ->
                    if (saved.assetId == state.activeAssetId) {
                        HomeCustomEnrichTransition(state.copy(note = "Switch first before removing the current choice."))
                    } else {
                        HomeCustomEnrichTransition(state, HomeCustomEnrichCommand.RemoveSaved(saved.assetId))
                    }
                    // X is REMOVE on this surface and nothing else (moved
                    // search to Y). An unsaved result is inert on X — A is the single media-transfer
                    // trigger, and there is nothing saved yet to take away.
                } ?: HomeCustomEnrichTransition(state)
                is HomeCustomEnrichAsset.Saved -> if (asset.record.assetId == state.activeAssetId) {
                    HomeCustomEnrichTransition(state.copy(note = "Switch first before removing the current choice."))
                } else {
                    HomeCustomEnrichTransition(state, HomeCustomEnrichCommand.RemoveSaved(asset.record.assetId))
                }
                HomeCustomEnrichAsset.Automatic -> HomeCustomEnrichTransition(state)
                HomeCustomEnrichAsset.More -> HomeCustomEnrichTransition(state)
            }
        }
    }
    return HomeCustomEnrichTransition(state.copy(laneMemory = remembered))
}

internal object HomeCustomEnrichKeyboardHostHolder {
    private var sessionId: String? = null
    private var host: MimirKeyboardHost? = null

    fun hostFor(sessionId: String): MimirKeyboardHost? =
        host?.takeIf { this.sessionId == sessionId }

    fun publish(sessionId: String, value: MimirKeyboardHost?) {
        this.sessionId = sessionId
        host = value
    }

    fun clear(sessionId: String) {
        if (this.sessionId == sessionId) {
            host = null
            this.sessionId = null
        }
    }
}

@Composable
internal fun HomeCustomEnrichPickerOverlay(
    authority: FocusAuthority,
    state: HomeCustomEnrichState,
    onAction: (ControllerAction) -> Unit,
    onQueryChange: (String) -> Unit,
    onKeyboardDone: () -> Unit,
    /**
     * True from the commit that takes this page away (its host's exit has started), while the page is still on screen dissolving. The scope is
     * released then, not when the dissolve ends and the page is disposed: the shelf's ring waited out the whole fade in the dark (audit 10-14, RC-50).
     */
    leaving: Boolean = false,
) {
    val palette = LocalMimirPalette.current
    val hero = palette.materials.hero
    // Whether the top row has folded the type's chips into one Filters chip (it measures; see CustomMediaFold).
    var chipsFolded by remember(state.sessionId) { mutableStateOf(false) }
    val graph = homeCustomEnrichLaneKeys(state, chipsFolded)
    val keys = graph.values.flatten()
    val registered = remember(state.sessionId) { linkedSetOf<FocusKey>() }
    val scopeHold = remember(authority, state.sessionId) { HomeCustomEnrichScopeHold(authority) }

    DisposableEffect(authority, state.sessionId) {
        keys.forEachIndexed { index, key ->
            authority.registerVirtual(key, HomeCustomEnrichScope, syntheticEnrichBounds(key, graph, index))
            registered += key
        }
        val initial = homeCustomEnrichInitialKey(state)
        authority.rememberFocusForScope(HomeCustomEnrichScope, initial)
        scopeHold.acquire()
        authority.requestFocus(initial)
        onDispose {
            // Total, not guarded -- this picker can now be disposed UNDER the PICK_IMAGE browser; see
            // leaveHomeCustomEnrichScope. Already released on the commit: the hold does it once.
            scopeHold.release()
            registered.forEach(authority::unregisterVirtual)
            registered.clear()
        }
    }
    LaunchedEffect(leaving) {
        if (leaving) scopeHold.release() else scopeHold.reacquire()
    }
    LaunchedEffect(keys) {
        val desired = keys.toSet()
        registered.filterNot(desired::contains).toList().forEach { key ->
            authority.unregisterVirtual(key)
            registered.remove(key)
        }
        keys.filterNot { it in registered }.forEachIndexed { index, key ->
            authority.registerVirtual(key, HomeCustomEnrichScope, syntheticEnrichBounds(key, graph, index))
            registered += key
        }
        if (authority.state.value.scope == HomeCustomEnrichScope && authority.state.value.focused !in desired) {
            val dropdownLanding = state.openFilterKey
                ?.let { openKey ->
                    state.visibleFilters().firstOrNull { it.key == openKey }?.let { filter ->
                        filter.selectedIndex.takeIf { it in filter.values.indices }?.let { index ->
                            homeCustomEnrichFilterOptionKey(state, filter, index)
                        }
                    }
                } ?: customMediaPanelLanding(state)
            val rememberedFilter = state.laneMemory[HomeCustomEnrichLane.FILTERS]
                ?.let(::FocusKey)
                ?.takeIf { it in desired }
            authority.requestFocus(dropdownLanding ?: rememberedFilter ?: homeCustomEnrichInitialKey(state))
        }
    }
    // A page being opened mounts before its stores' first read lands (RC-29, `buildHomeCustomEnrichPicker`): it lands on the Default tile, and
    // when the catalog arrives focus goes to the choice in use -- unless the player has already moved, which is theirs to keep.
    val mountedFocus = remember(state.sessionId) { homeCustomEnrichInitialKey(state) }
    LaunchedEffect(state.catalogReady, state.activeAssetId) {
        if (!state.catalogReady) return@LaunchedEffect
        val target = homeCustomEnrichInitialKey(state)
        val now = authority.state.value
        if (target != mountedFocus && now.scope == HomeCustomEnrichScope && now.focused == mountedFocus) authority.requestFocus(target)
    }
    val focus by authority.state.collectAsState()
    val focusedKey = focus.focused
    val previewAsset = homeCustomEnrichPreviewAsset(state, focusedKey)
    BackHandler(enabled = true) { onAction(ControllerAction.CANCEL) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            // No scrim here: this picker is hosted only as a page of TileArtMenuOverlay, whose surface-fade
            // container paints the one veil both pages share, so the swap between them never uncovers the shelf.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        val viewport = rememberMeasuredViewport(
            maxWidth = maxWidth,
            maxHeight = maxHeight,
            inDomain = { widthDp, heightDp ->
                widthDp.isFinite() && heightDp.isFinite() && density.fontScale.isFinite() &&
                    widthDp > 0f && heightDp > 0f && density.fontScale > 0f
            },
            beforeFirstValid = MimirBeforeFirstValidViewport.RENDER_NOTHING,
        )
        if (!viewport.shouldRender) return@BoxWithConstraints
        val rootProfile = LocalMimirViewportProfile.current
        val compactSquare = if (rootProfile.widthDp > 0f) {
            rootProfile.isCompact
        } else {
            mimirViewportProfile(viewport.width.value, viewport.height.value).isCompact
        }
        val controlGeometry = homeCustomEnrichControlGeometry(
            widthDp = viewport.width.value,
            heightDp = viewport.height.value,
            fontScale = density.fontScale,
        )
        val compactTypography = controlGeometry.compactTypography
        val topBandWeights = homeCustomEnrichTopBandWeights(controlGeometry.narrowWidth)
        // fillMaxWidth(fraction).widthIn(max=) was inert here: fillMaxWidth fixes min==max at the
        // fraction of this BoxWithConstraints's maxWidth, so widthIn's cap only ever coerced UP to
        // that fixed width.
        // The Customize media page holds a focused ring's whole reach between its column and the panel's clip (customMediaPanelSideAir): the panel is
        // that much wider on each side and its content is padded by the same, so the content keeps the width it had.
        val panelSideAir = if (state.mediaTypes.isNotEmpty()) customMediaPanelSideAir(compactTypography) else 0.dp
        val backdropBleed = customMediaBackdropBleed(compactTypography)
        val panelWidth = minOf(minOf(viewport.width * (if (compactSquare) 0.94f else 0.88f), 680.dp) + panelSideAir * 2, viewport.width)
        val panelHeightFraction = if (compactSquare) 0.98f else 0.90f
        val filterDropdownOpen = state.openFilterKey != null || state.mediaPanelOpen || state.filtersPanelOpen
        // "Dissolve, then clear" : the page behind a leaving dropdown stays glass for the OverlayDissolveCoverMs the dropdown
        // needs to be gone to the eye, and only then clears on the shared glass clock. The glass is a progress read in the DRAW pass
        // (`mimirGlassBackdropProgress`), not a radius read in composition: the page no longer recomposes per frame for 180 ms, the blur
        // layer is never toggled in and out (the text re-rasterised on each edge), and no raw `Modifier.blur` reaches its small-radius tail.
        val filterGlassHeld = rememberHomeCustomEnrichGlassHold(requested = filterDropdownOpen)
        // No identity: a TYPE change builds a new picker (a new session id) on this same page, and keyed on it the glass was reborn at 0 on the pick
        // ("the frost lifts"), ramped back up for the hold, then cleared. It is per mount, which is per open of the page.
        val filterGlass = rememberMimirGlassProgress(visible = filterGlassHeld)
        LocalCustomMediaMotionProbe.current?.glass = filterGlass
        var renderedFilterKey by remember(state.sessionId) { mutableStateOf<String?>(null) }
        if (state.openFilterKey != null) renderedFilterKey = state.openFilterKey
        val renderedFilter = renderedFilterKey?.let { key ->
            state.filters.firstOrNull { it.key == key }
        }
        var panelOrigin by remember(state.sessionId) { mutableStateOf(Offset.Zero) }
        var panelSizePx by remember(state.sessionId) { mutableStateOf(IntSize.Zero) }
        var filterAnchorOrigin by remember(state.sessionId) { mutableStateOf(Offset.Zero) }
        var filterAnchorHeightPx by remember(state.sessionId) { mutableStateOf(0) }
        // Where the panel starts and where its top row (Media, chips, Search) stands, in root pixels: the keyboard's sheet starts below that row or covers it
        // whole, never across it (CustomMediaKeyboardSheet.kt).
        var panelRootTopPx by remember(state.sessionId) { mutableStateOf(0f) }
        var topRowRootBoundsPx by remember(state.sessionId) { mutableStateOf<Pair<Float, Float>?>(null) }
        // The row's INK, not its touch box: the plates stand centred in a row that is taller (48 dp targets round 30 dp plates), and the sheet may stand over that
        // invisible air (reported on device: it covered the row over a 108 px empty band because the touch box reached into the keyboard).
        val chipPlateHeightPx = with(density) { rememberActivityChipPlateHeight().roundToPx() }
        fun recordTopRow(coordinates: androidx.compose.ui.layout.LayoutCoordinates) {
            if (viewport.suppressDrawing) return
            val top = coordinates.positionInRoot().y
            val air = ((coordinates.size.height - chipPlateHeightPx) / 2f).coerceAtLeast(0f)
            val bounds = (top + air) to (top + coordinates.size.height - air)
            if (bounds != topRowRootBoundsPx) topRowRootBoundsPx = bounds
        }
        val fallbackDropdownTopPx = with(density) {
            (if (compactTypography) {
                EnrichFilterDropdownFallbackTopCompactDp
            } else {
                EnrichFilterDropdownFallbackTopRegularDp
            }).dp.roundToPx()
        }
        val dropdownWidthPx = with(density) { 300.dp.roundToPx() }
        val hasFilterAnchor = panelSizePx.width > 0 && filterAnchorHeightPx > 0
        val dropdownOffset = if (hasFilterAnchor) {
            val desiredX = (filterAnchorOrigin.x - panelOrigin.x).roundToInt()
            val desiredY = (filterAnchorOrigin.y - panelOrigin.y).roundToInt() + filterAnchorHeightPx
            IntOffset(
                desiredX.coerceIn(0, (panelSizePx.width - dropdownWidthPx).coerceAtLeast(0)),
                desiredY.coerceAtLeast(0),
            )
        } else {
            IntOffset(0, fallbackDropdownTopPx)
        }
        // The panel, keyboard, and expanded-preview layers are separate top-level children, so this
        // always-present wrapper is the decision-sized seat that preserves centering while suppressing
        // held-zero drawing.
        Box(
            modifier = Modifier.mimirMeasuredViewport(viewport),
            contentAlignment = Alignment.Center,
        ) {
        MimirLiquidGlassPanel(
            modifier = Modifier
                .width(panelWidth)
                .fillMaxHeight(panelHeightFraction)
                .testTag(HomeCustomEnrichPanelTestTag)
                .onGloballyPositioned { coordinates ->
                    if (viewport.suppressDrawing) return@onGloballyPositioned
                    val top = coordinates.positionInRoot().y
                    if (top != panelRootTopPx) panelRootTopPx = top
                },
            contentPadding = homeCustomEnrichPanelPadding(compactTypography),
            verticalArrangement = Arrangement.Top,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { coordinates ->
                        if (viewport.suppressDrawing) return@onGloballyPositioned
                        val origin = coordinates.positionInRoot()
                        if (origin != panelOrigin) panelOrigin = origin
                        if (coordinates.size != panelSizePx) panelSizePx = coordinates.size
                    },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        // The glass layer clips to its own bounds on every device that blurs: the panel's whole interior, not its content box (customMediaBackdropBleed).
                        .customMediaBackdropBleedBy(backdropBleed)
                        .testTag(HomeCustomEnrichBackdropLayerTag)
                        .mimirGlassBackdropProgress { filterGlass() }
                        .padding(horizontal = panelSideAir + backdropBleed, vertical = backdropBleed),
                    verticalArrangement = Arrangement.spacedBy(
                        if (compactTypography) MimirSpacing.xs else MimirSpacing.sm,
                    ),
                ) {
            Column(
                // The Customize media page keeps one column (customMediaColumnStart): the title starts where the chips, strip and Upload do.
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = if (state.mediaTypes.isNotEmpty()) customMediaColumnStart(compactTypography) else 0.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val header = state.currentMediaType()?.let(::customMediaHeader) ?: when (state.kind) {
                    EnrichMediaKind.ICON -> "Icon art" to "ᚨ · ICON ART"
                    EnrichMediaKind.HERO -> "Hero art" to "ᚺ · HERO ART"
                    EnrichMediaKind.CLIP -> "Hero Motion" to "ᚺ · HERO MOTION"
                    EnrichMediaKind.TOME_PHYSICAL -> "Cartridge Hero" to "ᛗ · CARTRIDGE HERO"
                    EnrichMediaKind.COVER -> "Cover art" to "ᛒ · COVER ART"
                }
                MimirChromeText(
                    text = header.first,
                    visualOverride = header.second,
                    style = if (compactTypography) {
                        MimirTypeScale.RunicEyebrowCompact
                    } else {
                        MimirTypeScale.RunicEyebrow
                    },
                    color = hero.onCover.copy(alpha = 0.84f),
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                BeamSeparator(modifier = Modifier.height(1.dp), showCenterNode = false)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = if (compactSquare) 20.dp else 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
                ) {
                    // The game's name on ONE line: whole when it fits, else cut at a WORD with an ellipsis, never inside one (reported at 360 dp and font 1.3). It was a one-line scroller, which a pad cannot scroll and the panel's clip cut mid-word. One line, so the top row
                    // under it never moves; the whole name stays the node's spoken text.
                    val title = MimirTextIntent.Authored(state.gameTitle).resolve()
                    MimirWordEllipsisText(
                        text = title.visualText,
                        style = MimirTypeScale.Row,
                        color = hero.onCover,
                        maxLines = 1,
                        modifier = Modifier
                            .weight(1f)
                            // The tag first: a clearing modifier clears every semantic after it in the chain, the tag among them.
                            .testTag(CustomMediaTitleTag)
                            .clearAndSetSemantics { contentDescription = title.spokenText },
                    )
                    if (!compactSquare) {
                        MimirIntentText(
                            intent = MimirTextIntent.Mixed(
                                listOf(
                                    MimirTextFragment.Authored(state.durableSavedCount.toString()),
                                    MimirTextFragment.Chrome(" saved"),
                                ),
                            ),
                            style = MimirTypeScale.MicroStatus,
                            color = hero.onCover.copy(alpha = 0.62f),
                        )
                    }
                }
            }

            if (state.mediaTypes.isNotEmpty()) {
                // the earlier update the Customize media page: one top row (Media, the type's chips, Search) that holds still,
                // and a body under it that dissolves when the type changes. CustomMediaTopRow.kt.
                // Search is the first stop and a chip can be the last: both ring on the plate and sit on the panel's clip (customMediaTopRowEdgeInset).
                CustomMediaTopRow(
                    authority,
                    state,
                    focusedKey,
                    onAction,
                    Modifier
                        .onGloballyPositioned { recordTopRow(it) }
                        .fillMaxWidth()
                        .padding(start = customMediaColumnStart(compactTypography), end = customMediaTopRowEdgeInset(compactTypography)),
                    onFold = { chipsFolded = it },
                )
                CustomMediaBody(
                    authority = authority,
                    state = state,
                    focusedKey = focusedKey,
                    compactSquare = compactSquare,
                    compactTypography = compactTypography,
                    narrowWidth = controlGeometry.narrowWidth,
                    onAction = onAction,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    behindGlass = filterGlassHeld,
                )
            } else {
            // A local BoxWithConstraints, not the panel-level one above: fillMaxWidth(fraction) here was
            // always sized against this ROW's own available width, not the panel's, so the cap has to
            // bind against that same width — reusing the outer maxWidth would cap against the wrong base.
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                // fillMaxWidth(fraction).widthIn(max=) was inert: fillMaxWidth fixes min==max, so
                // widthIn's cap only ever coerced UP to that fixed width.
                val searchOnlyWidth = minOf(
                    maxWidth * (if (controlGeometry.narrowWidth) 0.68f else 0.38f),
                    250.dp,
                )
                Row(
                    modifier = Modifier
                        .onGloballyPositioned { recordTopRow(it) }
                        .fillMaxWidth()
                        .heightIn(min = homeCustomEnrichTopBandMinHeightDp().dp)
                        // The search field sits on the panel's start edge, and the panel clips: pull the band in by
                        // the ring's reach its padding does not cover. The end is the chip strip (no ring, unchanged) or a width-capped field, so it needs nothing.
                        .padding(start = homeCustomEnrichEdgeRingInset(homeCustomEnrichPanelPadding(compactTypography), flare = false)),
                    horizontalArrangement = Arrangement.spacedBy(
                        if (controlGeometry.narrowWidth) MimirSpacing.xs else MimirSpacing.sm,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.searchOwnsFullBand()) {
                        EnrichSearchRow(
                            authority,
                            state,
                            focusedKey,
                            controlGeometry,
                            onAction,
                            Modifier.width(searchOnlyWidth),
                        )
                    } else {
                        EnrichSearchRow(
                            authority,
                            state,
                            focusedKey,
                            controlGeometry,
                            onAction,
                            Modifier.weight(topBandWeights.search),
                        )
                        EnrichFilterRow(
                            authority,
                            state,
                            focusedKey,
                            controlGeometry,
                            onAction,
                            Modifier.weight(topBandWeights.filters),
                            onFilterRowMeasured = { origin, height ->
                                if (!viewport.suppressDrawing) {
                                    if (origin != filterAnchorOrigin) filterAnchorOrigin = origin
                                    if (height != filterAnchorHeightPx) filterAnchorHeightPx = height
                                }
                            },
                        )
                    }
                }
            }

            EnrichStatusLine(state, compactSquare)
            EnrichGallery(
                authority = authority,
                state = state,
                focusedKey = focusedKey,
                compactSquare = compactSquare,
                onAction = onAction,
                onExpandPreview = { onAction(ControllerAction.SYSTEM_SELECT) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = homeCustomEnrichGalleryMinHeightDp(state.geometryKind, compactSquare).dp)
                    .weight(1f),
            )
            EnrichFooter(
                authority = authority,
                state = state,
                focusedKey = focusedKey,
                compactTypography = compactTypography,
                narrowWidth = controlGeometry.narrowWidth,
                onAction = onAction,
            )
            }
                }
                if (state.mediaTypes.isNotEmpty()) {
                    CustomMediaPanels(authority, state, renderedFilter, focusedKey, onAction)
                }
                if (renderedFilter != null && state.mediaTypes.isEmpty()) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = filterDropdownOpen,
                        // Batch F: the scrim fades on the sheet's own clock, beside the dropdown below.
                        enter = fadeIn(mimirSheetSpec(mimirReducedMotion())),
                        exit = fadeOut(mimirSheetSpec(mimirReducedMotion())),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(palette.scrim.copy(alpha = 0.18f))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = {
                                        if (filterDropdownOpen) onAction(ControllerAction.CANCEL)
                                    },
                                ),
                        )
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = filterDropdownOpen,
                        // Batch F: the sheet tier -- slide by its own height from the top plus a fade, one OverlayMs
                        // clock (it slid half its height under a FocusMs fade, two clocks on one arrival).
                        enter = mimirSheetEnter(mimirReducedMotion(), fromTop = true),
                        exit = mimirSheetExit(mimirReducedMotion(), fromTop = true),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset { dropdownOffset },
                    ) {
                        EnrichFilterDropdown(
                            authority = authority,
                            state = state,
                            filter = renderedFilter,
                            focusedKey = focusedKey,
                            onAction = onAction,
                            compactTypography = compactTypography,
                            enabled = filterDropdownOpen,
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = state.keyboardOpen,
            // Batch F: the sheet tier, one OverlayMs clock (it slid on SceneMs with no easing under a FocusMs fade).
            enter = mimirSheetEnter(mimirReducedMotion()),
            exit = mimirSheetExit(mimirReducedMotion()),
            // INSIDE the page's panel (sweep 10-09): the keyboard was the viewport's whole width, wider than the panel, and ran off the
            // screen's bottom. It stands on the panel's width, less the sheet's own air each side, above the panel's bottom edge.
            modifier = Modifier
                .align(Alignment.Center)
                .width(panelWidth - EnrichKeyboardSheetAirDp.dp * 2)
                .fillMaxHeight(panelHeightFraction),
        ) {
            val keyboardHost = rememberMimirKeyboardHost(authority, state.keyboardOpen)
            SideEffect {
                HomeCustomEnrichKeyboardHostHolder.publish(
                    sessionId = state.sessionId,
                    value = keyboardHost.takeIf { state.keyboardOpen },
                )
            }
            DisposableEffect(state.sessionId) {
                onDispose { HomeCustomEnrichKeyboardHostHolder.clear(state.sessionId) }
            }
            // An OPAQUE sheet under the keyboard's own glass (the page's tiles and words showed through it), starting BELOW the top row so the row
            // stays whole and readable above it, or covering the row entirely when the keyboard does not fit under it: never across it (reported 10-09: the sheet's edge cut the search bar and the chips in half). The keyboard keeps its own side padding, the sheet its own top and
            // bottom, so the keys stand the same air from every edge.
            CustomMediaKeyboardSheet(
                surface = palette.surfaceBase,
                shape = RoundedCornerShape(MimirSpacing.radiusCard),
                rowTopPx = { topRowRootBoundsPx?.let { it.first - panelRootTopPx } },
                rowBottomPx = { topRowRootBoundsPx?.let { it.second - panelRootTopPx } },
                gapPx = with(density) { EnrichKeyboardSheetRowGapDp.dp.roundToPx() },
                airPx = with(density) { MimirSpacing.md.roundToPx() },
                minAirPx = with(density) { EnrichKeyboardSheetMinAirDp.dp.roundToPx() },
                compressFloorPx = with(density) { EnrichKeyboardCompressFloorDp.dp.roundToPx() },
                panelWidth = ::mimirKeyboardPanelWidth,
                modifier = Modifier.fillMaxSize().padding(bottom = EnrichKeyboardSheetAirDp.dp),
            ) {
                MimirKeyboard(
                    value = state.queryDraft,
                    onValueChange = onQueryChange,
                    onDone = onKeyboardDone,
                    controller = keyboardHost?.controller,
                    keyDecorator = keyboardHost?.keyDecorator,
                    targetLabel = homeCustomEnrichSearchLabel(state.kind),
                    floating = true,
                )
            }
        }

        AnimatedVisibility(
            visible = state.previewExpanded,
            enter = fadeIn(tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing)),
            exit = fadeOut(tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing)),
        ) {
            EnrichExpandedMediaPreview(
                state = state,
                asset = previewAsset,
                onDismiss = { onAction(ControllerAction.CANCEL) },
            )
        }
        }
    }
}

@Composable
private fun EnrichSearchRow(
    authority: FocusAuthority,
    state: HomeCustomEnrichState,
    focusedKey: FocusKey?,
    geometry: HomeCustomEnrichControlGeometry,
    onAction: (ControllerAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalMimirPalette.current
    val key = homeCustomEnrichSearchKey(state)
    val focused = focusedKey == key
    val textScroll = rememberScrollState()
    Row(
        modifier = modifier
            .heightIn(min = MimirSpacing.minTarget)
            .mimirFocusable(authority, key, HomeCustomEnrichScope, autoBringIntoView = false)
            // The one ring (ring migration, 2026-09-28): a row ring on the field's own corner. The ring paints the
            // field's glass itself, so the house wash lands over the glass rather than under it; the clip stays
            // for the text.
            .mimirSelectionRing(
                focused = focused,
                identity = key,
                shape = MimirRingShape.Row(MimirSpacing.radiusCard),
                restingOutline = true,
                fill = SolidColor(
                    homeCustomEnrichGlassControlFill(
                        palette,
                        lightAlpha = EnrichSearchFieldLightAlpha,
                        darkAlpha = EnrichSearchFieldDarkAlpha,
                    ),
                ),
            )
            .clip(RoundedCornerShape(MimirSpacing.radiusCard))
            // Focus first, then confirm: the second tap acts. No authority here, so the helper does not fold the two.
            .mimirActivatable(label = "Search") {
                if (focused) onAction(ControllerAction.CONFIRM) else authority.requestFocus(key)
            }
            .padding(
                horizontal = if (geometry.compactTypography) MimirSpacing.sm else MimirSpacing.md,
                vertical = MimirSpacing.sm,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "ᛋ",
            style = if (geometry.compactTypography) {
                MimirTypeScale.RuneAction.copy(fontSize = 18.sp)
            } else {
                MimirTypeScale.RuneAction
            },
            color = palette.runeMark,
        )
        MimirIntentText(
            intent = homeCustomEnrichSearchIntent(state, geometry.narrowWidth),
            style = if (state.queryDraft.isBlank()) {
                MimirTypeScale.MenuActionCompact.copy(fontSize = 10.sp)
            } else {
                MimirTypeScale.Support
            },
            color = palette.textPrimary,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .padding(start = if (geometry.compactTypography) MimirSpacing.xs else MimirSpacing.sm)
                .weight(1f)
                .horizontalScroll(textScroll),
        )
    }
}

@Composable
private fun EnrichFilterRow(
    authority: FocusAuthority,
    state: HomeCustomEnrichState,
    focusedKey: FocusKey?,
    geometry: HomeCustomEnrichControlGeometry,
    onAction: (ControllerAction) -> Unit,
    modifier: Modifier = Modifier,
    onFilterRowMeasured: (Offset, Int) -> Unit = { _, _ -> },
) {
    val palette = LocalMimirPalette.current
    val metalBrush = remember(palette) { platformConsoleIconBrush(palette) }
    val visibleFilters = state.visibleFilters()
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    var viewportWidthPx by remember(state.sessionId) { mutableStateOf(0) }
    val chipSeats = remember(state.sessionId) { mutableStateMapOf<String, Pair<Int, Int>>() }
    val focusedChip = visibleFilters.firstOrNull { homeCustomEnrichFilterKey(state, it) == focusedKey }
    val focusedSeat = focusedChip?.let { chipSeats[it.key] }
    // One scroll authority: the measured follow effect; chips never bring-into-view themselves.
    LaunchedEffect(focusedKey, visibleFilters.map { it.key }, focusedSeat, viewportWidthPx) {
        val seat = focusedSeat ?: return@LaunchedEffect
        val clearancePx = with(density) { EnrichChipStripClearanceDp.dp.roundToPx() }
        scroll.animateScrollTo(
            homeCustomEnrichChipScrollTargetPx(
                currentScrollPx = scroll.value,
                viewportWidthPx = viewportWidthPx,
                chipOffsetPx = seat.first,
                chipWidthPx = seat.second,
                clearancePx = clearancePx,
            ).coerceAtMost(scroll.maxValue),
            // The ring that follows focus moves on FocusMs; the strip it rides in takes the same clock, not the default spring.
            tween(MimirMotion.FocusMs, easing = MimirMotion.Easing),
        )
    }
    Row(
        modifier = modifier
            .onSizeChanged { viewportWidthPx = it.width }
            .onGloballyPositioned { coordinates ->
                onFilterRowMeasured(coordinates.positionInRoot(), coordinates.size.height)
            }
            .horizontalScroll(scroll)
            // The chip is 48dp; the 4dp air on each vertical edge is the top-band contract (the scroll does not
            // clip its cross axis). The chips wear no ring, so start/end keep the strip's own air.
            .padding(horizontal = EnrichChipStripClearanceDp.dp, vertical = EnrichTargetRingAirDp.dp),
        horizontalArrangement = Arrangement.spacedBy(
            if (geometry.compactTypography) MimirSpacing.xs else MimirSpacing.sm,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        visibleFilters.forEach { filter ->
          // Same invariant as the asset rail below: identity from the FILTER, not its slot. The
          // visible set changes as providers report in, so an unkeyed chip inherits the focused
          // chip's ring animations when the list shifts under a stationary player.
          key(filter.key) {
            val key = homeCustomEnrichFilterKey(state, filter)
            val focused = focusedKey == key
            Row(
                modifier = Modifier
                    .heightIn(min = MimirSpacing.minTarget)
                    .onGloballyPositioned { coordinates ->
                        chipSeats[filter.key] =
                            (coordinates.positionInParent().x.roundToInt() + scroll.value) to coordinates.size.width
                    }
                    .mimirFocusable(authority, key, HomeCustomEnrichScope, autoBringIntoView = false)
                    .graphicsLayer { alpha = if (focused) 1f else 0.82f }
                    .mimirActivatable(label = filter.label) {
                        // Touch filters are direct controls. Publish exact focus before dispatch so a
                        // single tap opens this filter and cannot activate the shelf beneath the modal.
                        if (!focused) authority.requestFocus(key)
                        onAction(ControllerAction.CONFIRM)
                    }
                    .padding(
                        horizontal = homeCustomEnrichFilterHorizontalPaddingDp(
                            geometry.compactTypography,
                        ).dp,
                        vertical = MimirSpacing.sm,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MimirAnimatedChromeText(
                    intent = MimirTextIntent.Mixed(
                        listOf(
                            MimirTextFragment.Chrome(filter.label),
                            MimirTextFragment.Chrome(" · "),
                            MimirTextFragment.Authored(filter.selectedDisplay),
                        ),
                    ),
                    modifier = Modifier.enrichFilterTextFocus(focused, palette),
                    color = Color.Unspecified,
                    style = MimirTypeScale.MenuActionCompact.copy(brush = metalBrush, fontSize = 10.sp),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
      }
    }
}

/**
 * A quiet accent ellipse keeps metallic filter labels legible without making a chip or box.
 *
 * `internal`, not private, since 2026-09-18: the FAVORITES platform filter is the same control in the
 * same package and must share its appearance. Shared rather than copied — a second ellipse is a
 * second thing to drift.
 */
internal fun Modifier.enrichFilterTextFocus(
    focused: Boolean,
    palette: MimirThemePalette,
): Modifier = drawWithCache {
    if (!focused) {
        onDrawBehind { }
    } else {
        val center = Offset(size.width / 2f, size.height / 2f)
        val glow = Brush.radialGradient(
            colorStops = arrayOf(
                0f to palette.accent.copy(alpha = 0.30f),
                0.6f to palette.accent.copy(alpha = 0.14f),
                1f to palette.accent.copy(alpha = 0f),
            ),
            center = center,
            radius = 1f,
        )
        onDrawBehind {
            scale(
                scaleX = size.width * 0.50f,
                scaleY = size.height * 0.65f,
                pivot = center,
            ) {
                drawCircle(brush = glow, radius = 1f, center = center)
            }
        }
    }
}

@Composable
private fun EnrichFilterDropdown(
    authority: FocusAuthority,
    state: HomeCustomEnrichState,
    filter: HomeCustomEnrichFilter,
    focusedKey: FocusKey?,
    onAction: (ControllerAction) -> Unit,
    compactTypography: Boolean,
    enabled: Boolean,
) {
    val palette = LocalMimirPalette.current
    val hero = palette.materials.hero
    val metalBrush = remember(palette) { platformConsoleIconBrush(palette) }
    Column(
        modifier = Modifier
            .widthIn(min = 90.dp, max = 150.dp)
            .clip(RoundedCornerShape(MimirSpacing.radiusCard))
            .background(
                Brush.verticalGradient(
                    listOf(
                        hero.coverTop.copy(alpha = 0.96f),
                        hero.coverMiddle.copy(alpha = 0.96f),
                        hero.coverBottom.copy(alpha = 0.96f),
                    ),
                ),
            )
            .padding(if (compactTypography) MimirSpacing.xs else MimirSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        filter.values.forEachIndexed { optionIndex, option ->
            val optionKey = homeCustomEnrichFilterOptionKey(state, filter, optionIndex)
            val focused = focusedKey == optionKey
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MimirSpacing.minTarget)
                    .mimirFocusable(authority, optionKey, HomeCustomEnrichScope, autoBringIntoView = false)
                    .graphicsLayer { alpha = if (focused) 1f else 0.82f }
                    .drawBehind {
                        if (optionIndex > 0) {
                            drawLine(
                                color = palette.materials.separator.core.copy(alpha = 0.46f),
                                start = Offset(0f, 0f),
                                end = Offset(size.width, 0f),
                                strokeWidth = 1.dp.toPx(),
                            )
                        }
                        if (focused) {
                            val radius = 2.dp.toPx()
                            val centerX = MimirSpacing.md.toPx() / 2f
                            val centerY = size.height / 2f
                            inset(
                                left = centerX - radius,
                                top = centerY - radius,
                                right = size.width - centerX - radius,
                                bottom = size.height - centerY - radius,
                            ) {
                                drawCircle(
                                    brush = metalBrush,
                                    radius = radius,
                                    center = Offset(radius, radius),
                                )
                            }
                        }
                    }
                    .mimirActivatable(label = option, enabled = enabled) {
                        if (!focused) authority.requestFocus(optionKey)
                        onAction(ControllerAction.CONFIRM)
                    }
                    .padding(horizontal = MimirSpacing.md, vertical = MimirSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    MimirIntentText(
                        intent = MimirTextIntent.Authored(option),
                        modifier = Modifier.enrichFilterTextFocus(focused, palette),
                        style = MimirTypeScale.MenuActionCompact.copy(
                            brush = metalBrush,
                            fontSize = 11.sp,
                        ),
                        color = Color.Unspecified,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                if (optionIndex == filter.selectedIndex) {
                    MimirIntentText(
                        intent = MimirTextIntent.StaticChrome("Selected", visualOverride = "ᛗ"),
                        style = MimirTypeScale.MicroStatus,
                        color = palette.textSecondary.copy(alpha = 0.72f),
                    )
                }
            }
        }
    }
}

/**
 * The status line's one casing: the page's all-caps micro-label family (sweep 10-09). The idle and search lines were already chrome or mixed copy
 * (the resolver caps the chrome and leaves the authored counts and provider names as they are); the app's own notes and failure messages
 * were prose, so the line read in two casings. They are chrome now. What is spoken keeps its sentence case either way.
 */
internal fun homeCustomEnrichStatusCaps(intent: MimirTextIntent): MimirTextIntent = when (intent) {
    is MimirTextIntent.Body -> MimirTextIntent.StaticChrome(spokenText = intent.spokenText)
    is MimirTextIntent.StaticChrome, is MimirTextIntent.Mixed, is MimirTextIntent.Authored -> intent
}

@Composable
internal fun EnrichStatusLine(
    state: HomeCustomEnrichState,
    compactSquare: Boolean,
) {
    val palette = LocalMimirPalette.current
    val fontScale = LocalDensity.current.fontScale
    val statusIntent: MimirTextIntent = when (val value = state.searchStatus) {
        HomeCustomEnrichSearchStatus.Idle -> homeCustomEnrichIdleStatusIntent(state)
        is HomeCustomEnrichSearchStatus.Searching -> MimirTextIntent.Mixed(
            listOf(
                MimirTextFragment.Chrome("Searching "),
                MimirTextFragment.Authored(value.provider),
                MimirTextFragment.Chrome("… · "),
                MimirTextFragment.Authored(value.found.toString()),
                MimirTextFragment.Chrome(" found"),
            ),
        )
        is HomeCustomEnrichSearchStatus.Complete -> if (value.found == 0) {
            MimirTextIntent.StaticChrome("No matches. Try another title or broader filters.")
        } else if (state.hasMore) {
            MimirTextIntent.Mixed(
                listOf(
                    MimirTextFragment.Authored(value.found.toString()),
                    MimirTextFragment.Chrome(" ${homeCustomEnrichResultsWord(value.found)} · more available"),
                ),
            )
        } else if (value.found >= homeCustomEnrichResultCap(state.kind)) {
            MimirTextIntent.Mixed(
                listOf(
                    MimirTextFragment.Authored(value.found.toString()),
                    MimirTextFragment.Chrome(" ${homeCustomEnrichResultsWord(value.found)} · refine the search for more"),
                ),
            )
        } else {
            MimirTextIntent.Mixed(
                listOf(
                    MimirTextFragment.Authored(value.found.toString()),
                    MimirTextFragment.Chrome(" ${homeCustomEnrichResultsWord(value.found)}"),
                ),
            )
        }
        is HomeCustomEnrichSearchStatus.Cancelled -> MimirTextIntent.Mixed(
            listOf(
                MimirTextFragment.Chrome("Search stopped · "),
                MimirTextFragment.Authored(value.kept.toString()),
                MimirTextFragment.Chrome(if (value.kept == 1) " result remains" else " results remain"),
            ),
        )
        is HomeCustomEnrichSearchStatus.NeedsAccess -> MimirTextIntent.Body(value.message)
        is HomeCustomEnrichSearchStatus.Unreachable -> MimirTextIntent.Body(value.message)
        is HomeCustomEnrichSearchStatus.ProviderFailed -> MimirTextIntent.Body(value.message)
        is HomeCustomEnrichSearchStatus.Busy -> MimirTextIntent.Body(value.message)
        is HomeCustomEnrichSearchStatus.Failed -> MimirTextIntent.Body(value.message)
    }
    Row(
        // Idle, failure, retry, and searching all own one fixed lane. A two-line failure on compact
        // square hardware can no longer resize the gallery when X starts Retry.
        modifier = Modifier
            .fillMaxWidth()
            .height(homeCustomEnrichStatusLaneHeightDp(compactSquare, fontScale).dp)
            .testTag(CustomMediaStatusTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MimirAnimatedChromeText(
            intent = homeCustomEnrichStatusCaps(state.note?.let { MimirTextIntent.Body(it) } ?: statusIntent),
            // One stable role across Idle/Search/Complete/Failure prevents a visible size jump while the sentence changes, and one
            // casing: the page's all-caps micro-label family (sweep 10-09), as the saved count above and the tiles' words are.
            style = MimirTypeScale.MicroStatus,
            color = palette.textSecondary,
            maxLines = if (compactSquare) 2 else 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Widens the strip's viewport by [bleed] on each side of the column and seats it there, so the first tile starts ON the column's edge
 * (the strip pads its content by the same [bleed]) and the ring's reach, which the scroll clips on its own axis, has the room it needs.
 * [verticalBleed] does the same above and below the band without changing the band's height: the viewport is taller by it, the strip pads its
 * content by the same, and the reported height is the band's own, so the certified height budget holds.
 */
private fun Modifier.enrichStripBleed(bleed: Dp, verticalBleed: Dp = 0.dp): Modifier = layout { measurable, constraints ->
    val extra = (bleed * 2).roundToPx()
    val extraHeight = (verticalBleed * 2).roundToPx()
    val placeable = measurable.measure(
        constraints.copy(
            minWidth = (constraints.minWidth + extra).coerceAtLeast(0),
            maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + extra else constraints.maxWidth,
            maxHeight = if (constraints.hasBoundedHeight) constraints.maxHeight + extraHeight else constraints.maxHeight,
        ),
    )
    layout(
        (placeable.width - extra).coerceIn(constraints.minWidth, constraints.maxWidth),
        (placeable.height - extraHeight).coerceAtLeast(0),
    ) {
        placeable.place(-bleed.roundToPx(), -verticalBleed.roundToPx())
    }
}

@Composable
internal fun EnrichGallery(
    authority: FocusAuthority,
    state: HomeCustomEnrichState,
    focusedKey: FocusKey?,
    compactSquare: Boolean,
    onAction: (ControllerAction) -> Unit,
    onExpandPreview: () -> Unit,
    modifier: Modifier = Modifier,
    /** The overflow cue's seat past the column's edge: the room the panel's clip leaves it ([customMediaCueSeatDp]), never more than [EnrichGalleryCueSeatDp]. */
    cueSeatDp: Int = EnrichGalleryCueSeatDp,
) {
    val palette = LocalMimirPalette.current
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val assets = state.assets
    val focusedIndex = assets.indexOfFirst { homeCustomEnrichAssetKey(state, it) == focusedKey }
    // Every tile is its art over one bar (CustomMediaTile.kt), the bar carved out of the certified tile height: the art is what the bar leaves.
    val barDp = kotlin.math.ceil(rememberEnrichTileBarHeightDp()).toInt()
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // The strip is the column's width and no less: no cue column stands beside it (the old 18 dp cue boxes pushed the first tile 32 dp
        // in from the title). It bleeds the ring's reach past the column on each side, which the viewport law below counts back in.
        val galleryViewportWidthDp = maxWidth.value.toInt() + EnrichRingClearanceDp * 2
        // Where the page has the room (Main) the strip grows by the bar's height, so the art keeps its certified height and its widths and the
        // preview stage below the strip gives that height up (CustomMediaTile.kt); a compact square, whose height is all spent, grows nothing.
        val measuredDp = maxHeight.value.toInt()
        val growthDp = homeCustomEnrichStripGrowthDp(measuredDp, state.geometryKind, compactSquare, barDp)
        // The Default tile is never narrower than its word and its check need, at the live font scale.
        val defaultTileFloorDp = rememberEnrichDefaultTileFloorDp()
        val tileWidthDp = homeCustomEnrichBarredTileWidthDp(state.geometryKind, compactSquare, galleryViewportWidthDp, barDp, growthDp)
        // A cover tile is the shape of its art (CustomMediaTile.kt): the Default tile reports its art's aspect once it has loaded,
        // a saved or found choice brings its dimensions. Every other kind keeps its one width.
        // Seeded from what the process already learned about this art (EnrichDefaultAspectMemory): a second open sizes the Default tile on its
        // first frame. The first open reads the header on IO behind the page's arrival lead and keeps the answer.
        val defaultArtModel = (state.defaultArt as? HomeCustomEnrichDefaultArt.Automatic)?.model
        var defaultAspect by remember(state.sessionId, defaultArtModel) {
            mutableStateOf(EnrichDefaultAspectMemory.peek(defaultArtModel))
        }
        val artHeightDp = homeCustomEnrichTileArtHeightDp(state.geometryKind, compactSquare, barDp, growthDp)
        val tileWidthsDp = assets.map { asset ->
            // The Default tile of a book or a loaded picture is that art's own shape (contained, nothing cropped, no band beside it).
            val ownShape = if (asset == HomeCustomEnrichAsset.Automatic) {
                homeCustomEnrichDefaultTileWidthDp(state.geometryKind, state.defaultArt, defaultAspect, artHeightDp, tileWidthDp)
            } else {
                null
            }
            val width = if (ownShape != null) {
                ownShape
            } else if (state.geometryKind == EnrichMediaKind.COVER) {
                // Each tile is the shape of its art. A merged-picker asset that brings no size (a square from the provider, a Saved choice
                // adopted from disk) takes the shape its lane gives it: a square tile for a square, the portrait box for a box.
                homeCustomEnrichCoverTileWidthDp(
                    compactSquare,
                    homeCustomEnrichAssetAspect(asset, defaultAspect) ?: state.iconArtLaneAspect(asset),
                    galleryViewportWidthDp,
                    barDp,
                    growthDp,
                )
            } else {
                tileWidthDp
            }
            if (asset == HomeCustomEnrichAsset.Automatic) maxOf(width, defaultTileFloorDp) else width
        }
        val previewHeightDp = homeCustomEnrichPreviewHeightDp(
            measuredGalleryHeightDp = measuredDp,
            kind = state.geometryKind,
            compactSquare = compactSquare,
            growthDp = growthDp,
        )
        val previewAsset = homeCustomEnrichPreviewAsset(state, focusedKey)
        // One scroll authority: this effect follows reducer-routed focus; tiles never bring-into-view.
        var followSettled by remember(state.sessionId) { mutableStateOf(false) }
        LaunchedEffect(focusedIndex, assets.size, tileWidthsDp, galleryViewportWidthDp) {
            if (focusedIndex < 0) return@LaunchedEffect
            val targetPx = with(density) {
                homeCustomEnrichGalleryScrollOffsetDp(
                    focusedAssetIndex = focusedIndex,
                    tileWidthsDp = tileWidthsDp,
                    gapDp = MimirSpacing.lg.value.toInt(),
                    viewportDp = galleryViewportWidthDp,
                ).dp.roundToPx()
            }
            if (followSettled) {
                scroll.animateScrollTo(targetPx, tween(MimirMotion.FocusMs, easing = MimirMotion.Easing))
            } else {
                scroll.scrollTo(targetPx)
                followSettled = true
            }
        }
        Column(Modifier.fillMaxSize()) {
            // The controller rail stays top-anchored and geometrically unchanged. Preview chrome spends
            // only the measured vacancy below it, so no FocusAuthority seat or scroll law moves.
            Box(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .enrichStripBleed(EnrichRingClearanceDp.dp, EnrichGalleryRingVerticalBleedDp.dp)
                        .testTag(CustomMediaStripHostTag)
                        .horizontalScroll(scroll)
                        // Start/end: the ring's reach, since the scroll clips on this axis. Top/bottom: the band's air and the bleed that
                        // makes it the ring's reach too, so a scroll host that clips its cross axis (stretch overscroll) cuts no ring.
                        .padding(
                            horizontal = EnrichRingClearanceDp.dp,
                            vertical = (EnrichGalleryVerticalAirDp + EnrichGalleryRingVerticalBleedDp).dp,
                        ),
                    horizontalArrangement = Arrangement.spacedBy(MimirSpacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    assets.forEachIndexed { index, asset ->
                        // No "Results" label between the saved tiles and the results : the status line above the strip
                        // already says "6 results", so the strip is tiles at one gap.
                        //
                        // Composition identity from the ASSET, never its position in the rail. Without
                        // this the tile's focus ring is a property of the SLOT: search results arrive
                        // under a stationary player, so the focused tile hands its lit ring animations
                        // to whatever result lands in its cell, and the ring fades out over an image the
                        // player never chose. Same defect the file browser's folio carried, found by the
                        // product-wide sweep -- see GhostFreeIdentityTest in core-ui/feature-library/
                        // feature-settings for the invariant and its census.
                        key(homeCustomEnrichAssetKey(state, asset)) {
                            EnrichAssetTile(
                                authority,
                                state,
                                asset,
                                focusedKey,
                                compactSquare,
                                tileWidthsDp.getOrElse(index) { tileWidthDp },
                                onAction,
                                onDefaultAspect = { if (defaultAspect == null) defaultAspect = it },
                                barGrowthDp = growthDp,
                                stageShown = previewHeightDp > 0,
                            )
                        }
                    }
                }
                // More this way: a drawn chevron, only while the strip has more to scroll on that side (a strip that fits, as two tiles
                // do, shows none), standing in the bleed just outside the column so it covers no tile.
                if (scroll.canScrollBackward) {
                    EnrichGalleryCue(
                        pointsRight = false,
                        color = palette.runeMark,
                        modifier = Modifier.align(Alignment.CenterStart).offset(x = (-cueSeatDp).dp),
                    )
                }
                if (scroll.canScrollForward) {
                    EnrichGalleryCue(
                        pointsRight = true,
                        color = palette.runeMark,
                        modifier = Modifier.align(Alignment.CenterEnd).offset(x = cueSeatDp.dp),
                    )
                }
            }
            if (previewHeightDp > 0) {
                Spacer(Modifier.height(EnrichPreviewRailGapDp.dp))
                EnrichPreviewBlock(
                    state = state,
                    asset = previewAsset,
                    mediaHeightDp = previewHeightDp,
                    onExpand = onExpandPreview,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

internal data class SettledPreviewOwner(
    val ownerPath: String,
    val painter: Painter,
)

internal sealed class SettledPreviewDecodePhase {
    data object Loading : SettledPreviewDecodePhase()
    data class Success(val decodedOwnerPath: String) : SettledPreviewDecodePhase()
    data object Error : SettledPreviewDecodePhase()
}

internal fun settledPreviewOwnerAfterDecode(
    current: SettledPreviewOwner?,
    requestedPath: String?,
    phase: SettledPreviewDecodePhase,
    successPainter: Painter? = null,
): SettledPreviewOwner? {
    if (requestedPath.isNullOrBlank()) return null
    return when (phase) {
        SettledPreviewDecodePhase.Loading ->
            current?.takeIf { it.ownerPath == requestedPath }
        SettledPreviewDecodePhase.Error -> null
        is SettledPreviewDecodePhase.Success ->
            if (phase.decodedOwnerPath == requestedPath && successPainter != null) {
                SettledPreviewOwner(phase.decodedOwnerPath, successPainter)
            } else {
                null
            }
    }
}

internal fun settledPreviewPainterForPath(
    ownerPath: String?,
    requestedPath: String?,
    painter: Painter?,
): Painter? {
    if (ownerPath.isNullOrBlank() || requestedPath.isNullOrBlank() || painter == null) return null
    return painter.takeIf { ownerPath == requestedPath }
}

@Composable
private fun EnrichPreviewBlock(
    state: HomeCustomEnrichState,
    asset: HomeCustomEnrichAsset,
    mediaHeightDp: Int,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalMimirPalette.current
    val requestedPath = asset.previewPath?.takeIf(String::isNotBlank)
    var requestedFailed by remember(requestedPath) { mutableStateOf(false) }
    val previewStatus = if (requestedFailed) {
        homeCustomEnrichPreviewActionLabel(asset, previewUnavailable = true)
    } else {
        homeCustomEnrichPreviewStatus(state, asset)
    }
    // One rule for every kind (sweep 10-09): the stage is the whole slot and the media is CONTAINED in it, centred, so what stands
    // around the media is symmetric whatever its shape (a 2:1 upload was cropped to a square inside a portrait card, 15 px from the
    // bottom against 70 at the top). Only a video keeps a 16:9 frame of its own: it cannot be contained in a wider one.
    val previewContentScale = ContentScale.Fit
    val framed = state.kind == EnrichMediaKind.CLIP

    Column(modifier.clearAndSetSemantics { }) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(EnrichPreviewCaptionHeightDp.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MimirChromeText(
                text = "Preview",
                visualOverride = "ᚾ · PREVIEW",
                style = MimirTypeScale.RunicEyebrowCompact,
                color = palette.runeMark,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.weight(1f),
            )
            if (homeCustomEnrichPreviewCaptionShown(asset, previewStatus)) {
                MimirAnimatedChromeText(
                    text = previewStatus,
                    style = MimirTypeScale.MicroStatus,
                    color = palette.textSecondary.copy(alpha = 0.84f),
                    maxLines = 1,
                    softWrap = false,
                )
            }
            // The SELECT · EXPAND cue is the footer's alone (homeCustomEnrichShowsExpandHint): it stood here too, twice on one page.
        }
        Spacer(Modifier.height(EnrichPreviewCaptionGapDp.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(mediaHeightDp.dp)
                .testTag(CustomMediaPreviewStageTag),
        ) {
            val runeColor = palette.runeMark.copy(alpha = 0.42f)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (homeCustomEnrichPreviewCanExpand(state, asset)) {
                            Modifier
                                .pointerInput(onExpand) {
                                    detectTapGestures(onTap = { onExpand() })
                                }
                                .semantics {
                                    contentDescription = if (state.kind == EnrichMediaKind.CLIP) {
                                        "Expand Hero Motion preview"
                                    } else {
                                        "Expand Hero art preview"
                                    }
                                    onClick {
                                        onExpand()
                                        true
                                    }
                                }
                        } else {
                            Modifier
                        },
                    )
                    .drawBehind {
                        val stroke = EnrichPreviewRuneStrokeDp.dp.toPx()
                        // The brackets stand on the stage's own edge, which is the page's column (half the stroke in, so the line is whole);
                        // the media keeps its own air inside them (the padding below).
                        val inset = stroke / 2f
                        val arm = minOf(
                            EnrichPreviewRuneArmDp.dp.toPx(),
                            ((size.width - inset * 2f) / 2f).coerceAtLeast(0f),
                            ((size.height - inset * 2f) / 2f).coerceAtLeast(0f),
                        )
                        val left = inset
                        val right = size.width - inset
                        val top = inset
                        val bottom = size.height - inset
                        drawLine(runeColor, Offset(left, top), Offset(left + arm, top), stroke)
                        drawLine(runeColor, Offset(left, top), Offset(left, top + arm), stroke)
                        drawLine(runeColor, Offset(right, top), Offset(right - arm, top), stroke)
                        drawLine(runeColor, Offset(right, top), Offset(right, top + arm), stroke)
                        drawLine(runeColor, Offset(left, bottom), Offset(left + arm, bottom), stroke)
                        drawLine(runeColor, Offset(left, bottom), Offset(left, bottom - arm), stroke)
                        drawLine(runeColor, Offset(right, bottom), Offset(right - arm, bottom), stroke)
                        drawLine(runeColor, Offset(right, bottom), Offset(right, bottom - arm), stroke)
                    }
                    .padding(EnrichPreviewRuneInsetDp.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = (if (framed) Modifier.fillMaxHeight().aspectRatio(16f / 9f) else Modifier.fillMaxSize())
                        .clip(RoundedCornerShape(MimirSpacing.radiusCard)),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        requestedPath != null && !requestedFailed -> {
                            // The last good image stays while the next candidate decodes (RC-28): focus moving across the strip no longer blanks the stage.
                            RetainedArtImage(
                                model = File(requestedPath),
                                contentDescription = null,
                                contentScale = previewContentScale,
                                modifier = Modifier.fillMaxSize(),
                                onError = { requestedFailed = true },
                            )
                        }
                        asset == HomeCustomEnrichAsset.Automatic -> {
                            when (val art = state.defaultArt) {
                                is HomeCustomEnrichDefaultArt.ClipAutomatic -> {
                                    val poster = art.posterUri
                                    if (!poster.isNullOrBlank()) {
                                        RetainedArtImage(
                                            model = poster,
                                            contentDescription = null,
                                            contentScale = previewContentScale,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    } else {
                                        EnrichAutomaticMotionReadyLabel()
                                    }
                                }
                                HomeCustomEnrichDefaultArt.Unavailable -> EnrichNoAutomaticMediaLabel()
                                else -> EnrichDefaultArtFace(
                                    art = art,
                                    contentScale = previewContentScale,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        else -> EnrichPreviewActionLabel(asset, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun EnrichExpandedMediaPreview(
    state: HomeCustomEnrichState,
    asset: HomeCustomEnrichAsset,
    onDismiss: () -> Unit,
) {
    val palette = LocalMimirPalette.current
    val motion = state.kind == EnrichMediaKind.CLIP
    // Saved media already owns its full local asset. Unsaved search results may render only the
    // explicitly staged full-quality inspection file; their bounded 640×360 gallery thumbnail is
    // never allowed into this expanded surface.
    val requestedPath = homeCustomEnrichExpandedPreviewPath(state, asset)
    val transparentVeil = remember {
        Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            // Opaque (sweep 10-09): at 0.88 the page behind showed through the glass, its words ghosting under the stage.
            .background(palette.scrim.copy(alpha = 1f))
            .pointerInput(onDismiss) { detectTapGestures(onTap = { onDismiss() }) }
            .semantics {
                contentDescription = "Expanded ${homeCustomEnrichExpandedPreviewTitle(state.kind)}"
                onClick {
                    onDismiss()
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        MimirLiquidGlassPanel(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.94f),
            contentPadding = HomeCustomEnrichExpandedContentPaddingDp.dp,
            verticalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
        ) {
            val glassPalette = LocalMimirPalette.current
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MimirAnimatedChromeText(
                    text = homeCustomEnrichExpandedPreviewTitle(state.kind),
                    visualOverride = homeCustomEnrichExpandedPreviewTitleVisual(state.kind),
                    style = MimirTypeScale.RunicEyebrow,
                    color = glassPalette.runeMark,
                    modifier = Modifier.weight(1f),
                )
                MimirControlHintText(
                    text = "B · Close",
                    style = MimirTypeScale.ControlHint,
                    color = glassPalette.textSecondary,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                // One rule, the inline preview's (sweep 10-09): the stage is the whole slot, with the same air on every side of it, and the media
                // is CONTAINED in it, centred, whatever its shape: a cover shows its whole design for review
                // before committing; a wide upload is not cropped to a card's shape. Only a video keeps a 16:9 frame of its own.
                Box(
                    modifier = (if (motion) Modifier.aspectRatio(16f / 9f) else Modifier.fillMaxSize())
                        .clip(RoundedCornerShape(MimirSpacing.radiusCard))
                        .background(
                            homeCustomEnrichGlassControlFill(
                                palette = glassPalette,
                                lightAlpha = EnrichExpandedPreviewLightAlpha,
                                darkAlpha = EnrichExpandedPreviewDarkAlpha,
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        requestedPath != null && motion -> VideoBackdrop(
                            videoUri = when {
                                requestedPath.startsWith("file://") ||
                                    requestedPath.startsWith("content://") -> requestedPath
                                else -> Uri.fromFile(File(requestedPath)).toString()
                            },
                            artUri = asset.previewPath,
                            modifier = Modifier.fillMaxSize(),
                            veil = transparentVeil,
                            settleMs = 0L,
                        )
                        requestedPath != null -> RetainedArtImage(
                                model = File(requestedPath),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        asset == HomeCustomEnrichAsset.Automatic ->
                            EnrichDefaultArtFace(
                                art = state.defaultArt,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        state.expandedPreview is HomeCustomEnrichExpandedPreview.Loading ->
                            MimirChromeText(
                                text = "Preparing full preview",
                                visualOverride = "ᛋ · PREPARING FULL PREVIEW",
                                style = MimirTypeScale.MicroStatus,
                                color = glassPalette.runeMark,
                                maxLines = 1,
                            )
                        state.expandedPreview is HomeCustomEnrichExpandedPreview.Failed ->
                            MimirChromeText(
                                text = "Full preview unavailable",
                                visualOverride = "FULL PREVIEW UNAVAILABLE",
                                style = MimirTypeScale.MicroStatus,
                                color = glassPalette.textSecondary,
                                maxLines = 1,
                            )
                        else -> EnrichPreviewActionLabel(asset, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

/**
 * Paint the art that `Default` would restore. Every arm delegates to the SAME composable the destination
 * surface uses on revert — no redraw, no second copy of the art to keep in sync.
 *
 * Deliberately carries no semantics: the tile that hosts it already speaks `MimirCopy.Default` through
 * `clearAndSetSemantics`, and the preview block clears its own.
 */
@Composable
private fun EnrichNoAutomaticMediaLabel(modifier: Modifier = Modifier) {
    val palette = LocalMimirPalette.current
    MimirChromeText(
        text = HomeCustomEnrichNoAutomaticMediaSpoken,
        visualOverride = HomeCustomEnrichNoAutomaticMediaVisual,
        style = MimirTypeScale.MicroStatus,
        color = palette.textSecondary.copy(alpha = 0.84f),
        maxLines = 2,
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
}

@Composable
private fun EnrichAutomaticMotionReadyLabel(modifier: Modifier = Modifier) {
    val palette = LocalMimirPalette.current
    MimirChromeText(
        text = HomeCustomEnrichAutomaticMotionReadySpoken,
        visualOverride = HomeCustomEnrichAutomaticMotionReadyVisual,
        style = MimirTypeScale.MicroStatus,
        color = palette.textSecondary.copy(alpha = 0.84f),
        maxLines = 2,
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
}

@Composable
internal fun EnrichPreviewActionLabel(
    asset: HomeCustomEnrichAsset,
    modifier: Modifier = Modifier,
) {
    val palette = LocalMimirPalette.current
    val label = homeCustomEnrichPreviewActionLabel(asset, previewUnavailable = true)
    MimirAnimatedChromeText(
        text = label,
        visualOverride = homeCustomEnrichPreviewActionVisual(label),
        style = MimirTypeScale.MicroStatus,
        color = palette.textSecondary.copy(alpha = 0.84f),
        maxLines = 2,
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
}

@Composable
private fun EnrichDefaultArtFace(
    art: HomeCustomEnrichDefaultArt,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
    /** On a gallery tile the face runs to the tile's clip, as art does ; in a preview stage the book keeps its own 3:4. */
    coverTile: Boolean = false,
    /** Told the art's own aspect (width over height) once it has loaded, so a cover tile can take its shape. */
    onAspect: (Float) -> Unit = {},
) {
    val palette = LocalMimirPalette.current
    when (art) {
        is HomeCustomEnrichDefaultArt.ConsoleLineArt -> {
            // Same tint + metallic gradient the Library hero tile and the companion platform grid pass, and
            // the same 0.72 seat fraction the companion's square tile uses, so this reads as that tile's face.
            val brush = remember(palette) { platformConsoleIconBrush(palette) }
            Box(modifier, contentAlignment = Alignment.Center) {
                PlatformConsoleArt(
                    platformIdValue = art.platformIdValue,
                    tint = palette.accent,
                    tintBrush = brush,
                    modifier = Modifier.fillMaxSize(0.72f),
                )
            }
        }
        is HomeCustomEnrichDefaultArt.Automatic -> {
            // A local file's shape is in its header: read it there, off the main thread, so the tile takes it at once. The painter's
            // own size (below) reports it for art that is not a file. Either answer is kept for the process, so the next open of this
            // page sizes the tile from it on its first frame instead of jumping to it (H-09d).
            LaunchedEffect(art.model) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { customMediaLocalImageAspect(art.model) }?.let { aspect ->
                    EnrichDefaultAspectMemory.remember(art.model, aspect)
                    onAspect(aspect)
                }
            }
            RetainedArtImage(
                model = art.model,
                contentDescription = null,
                contentScale = contentScale,
                alpha = 0.96f,
                modifier = modifier,
                onIntrinsicSize = { size ->
                    val aspect = size.width / size.height
                    EnrichDefaultAspectMemory.remember(art.model, aspect)
                    onAspect(aspect)
                },
            )
        }
        // The book keeps its own 3:4 and centres inside whatever slot it is given. In a stage that is right. On a gallery tile it
        // left a band of the tile's glass beside the book (a plate inset in a plate, so the ring framed the glass and not the
        // book): there the book is sized to COVER the tile and the tile's clip trims it, so its leather runs to the edge .
        is HomeCustomEnrichDefaultArt.TitleBook -> if (coverTile) {
            BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
                // The whole book, contained: its rule, its title and its rune all stand in the art box, clear of the bar under it (reported on device: the cover was cut at the
                // top, its rule gone; earlier the bar covered the rune). The tile takes the book's own 3:4 (homeCustomEnrichDefaultTileWidthDp), so the book fills it. Where a
                // floor (DEFAULT at a large font) leaves a sliver beside the book, the book's own leather runs behind it: the ring still frames leather, never glass.
                val bookWidth = homeCustomEnrichTitleBookWidthDp(maxWidth.value, maxHeight.value)
                val bookHeight = bookWidth * (4f / 3f)
                if (bookWidth < maxWidth.value - 0.5f || bookHeight < maxHeight.value - 0.5f) {
                    val cover = palette.materials.hero
                    val leather = remember(cover) { Brush.verticalGradient(listOf(cover.coverTop, cover.coverMiddle, cover.coverBottom)) }
                    Box(Modifier.fillMaxSize().background(leather))
                }
                MediaArtBookCover(
                    title = art.title,
                    platformIdValue = art.platformIdValue,
                    fitTitle = true,
                    modifier = Modifier
                        .requiredSize(bookWidth.dp, bookHeight.dp)
                        .testTag(CustomMediaTitleBookTag),
                )
            }
        } else {
            MediaArtBookCover(
                title = art.title,
                platformIdValue = art.platformIdValue,
                fitTitle = true,
                modifier = modifier,
            )
        }
        is HomeCustomEnrichDefaultArt.ClipAutomatic -> {
            val poster = art.posterUri
            if (!poster.isNullOrBlank()) {
                RetainedArtImage(
                    model = poster,
                    contentDescription = null,
                    contentScale = contentScale,
                    alpha = 0.96f,
                    modifier = modifier,
                )
            } else {
                Box(modifier, contentAlignment = Alignment.Center) {
                    EnrichAutomaticMotionReadyLabel()
                }
            }
        }
        HomeCustomEnrichDefaultArt.Unavailable -> Box(modifier, contentAlignment = Alignment.Center) {
            EnrichNoAutomaticMediaLabel()
        }
    }
}

@Composable
private fun EnrichAssetTile(
    authority: FocusAuthority,
    state: HomeCustomEnrichState,
    asset: HomeCustomEnrichAsset,
    focusedKey: FocusKey?,
    compactSquare: Boolean,
    tileWidthDp: Int,
    onAction: (ControllerAction) -> Unit,
    onDefaultAspect: (Float) -> Unit = {},
    /** How much taller than the certified tile the strip stands (CustomMediaTile.kt): with a whole bar the art keeps its certified height. */
    barGrowthDp: Int = 0,
    /** Whether the preview stage is on the page: where it is not, the Default tile of no art says NO ART itself (nothing else on the page would). */
    stageShown: Boolean = true,
) {
    val palette = LocalMimirPalette.current
    val key = homeCustomEnrichAssetKey(state, asset)
    val focused = focusedKey == key
    val active = homeCustomEnrichAssetIsActive(state, asset)
    val saved = asset is HomeCustomEnrichAsset.Saved || (asset as? HomeCustomEnrichAsset.Result)?.result?.saved != null
    // Whether the tile CAN be removed, lit or not: the remove mark's seat is in the bar either way, so focusing a tile moves nothing in it.
    val removable = state.canRemove(asset)
    val isDefault = asset == HomeCustomEnrichAsset.Automatic
    val hasBar = asset != HomeCustomEnrichAsset.More
    // One line, whole, chosen by measuring (see CustomMediaTile.kt). The bar is below the art: the Default tile's DEFAULT, the provider
    // and the size, and the check, dot and remove marks are all said there, never over the art.
    val tileLabel = rememberEnrichTileLabelLayout(
        label = asset.label,
        showsLabel = !isDefault && asset != HomeCustomEnrichAsset.More,
        sizeKnown = homeCustomEnrichAssetSizeKnown(asset),
        tileWidthDp = tileWidthDp,
        active = active,
        savedDot = saved && !active,
        removable = removable,
        fixedBadge = if (isDefault) MimirCopy.Default.uppercase() else null,
    )
    // Width is the ring-safe authority, and the art's own shape (CustomMediaTile.kt) at the art's height: the tile is the certified band less its
    // air, the bar stands under the art and is carved out of that height. A cover is the shape of its art, so the art fills it and the ring hugs it;
    // with no aspect known it is the portrait tile the preview stage is drawn at. This is the ART's box.
    val barDp = kotlin.math.ceil(tileLabel.barHeightDp).toInt()
    // The art's box clips what it draws: no face (a book taller than its box, a cropped still) ever paints under the bar below it.
    val artModifier = Modifier.fillMaxWidth().height(homeCustomEnrichTileArtHeightDp(state.geometryKind, compactSquare, barDp, barGrowthDp).dp).clipToBounds()
    val activate: () -> Unit = {
        if (focused) onAction(ControllerAction.CONFIRM) else authority.requestFocus(key)
    }
    Column(
        modifier = Modifier
            .width(tileWidthDp.dp)
            .testTag(customMediaTileTag(asset.stableId))
            .mimirFocusable(authority, key, HomeCustomEnrichScope, autoBringIntoView = false)
            // The one ring on art: a card ring, no wash, no grow (the family has no scale). The strip's start/end
            // padding is its clearance; the band's air lets its halo past the band, as on Home. Every tile's content runs
            // full-bleed to the clip below (no plate inset in the glass, the earlier update), so the ring hugs exactly what is drawn, and
            // "in use" is the check mark alone: nothing else on a tile draws a border.
            .mimirSelectionRing(focused = focused, identity = key, shape = MimirRingShape.Card(MimirSpacing.radiusCard), restingOutline = true)
            .clip(RoundedCornerShape(MimirSpacing.radiusCard))
            .background(
                homeCustomEnrichGlassControlFill(
                    palette,
                    lightAlpha = EnrichGalleryTileLightAlpha,
                    darkAlpha = EnrichGalleryTileDarkAlpha,
                ),
            )
            // The semantics below name the tile; the helper only carries the tap.
            .mimirActivatable(label = asset.label, role = null, onActivate = activate)
            .clearAndSetSemantics {
                contentDescription = when {
                    asset != HomeCustomEnrichAsset.Automatic -> asset.label
                    state.defaultArt is HomeCustomEnrichDefaultArt.Unavailable ->
                        "${MimirCopy.Default}. ${homeCustomEnrichNoAutomaticMediaIntent().spokenText}."
                    state.defaultArt is HomeCustomEnrichDefaultArt.ClipAutomatic &&
                        state.defaultArt.posterUri.isNullOrBlank() ->
                        "${MimirCopy.Default}. ${homeCustomEnrichAutomaticMotionReadyIntent().spokenText}."
                    else -> MimirCopy.Default
                }
                onClick(label = "Use ${asset.label}") {
                    activate()
                    true
                }
            },
    ) {
        Box(artModifier) {
        // Policy CP-03: Default shows the art it would restore instead of a bare reset symbol —
        // every other tile in this gallery shows real art, so a symbol made the user compare unlike things.
        // Presentation only; activating this tile still performs the same plain revert it always did.
        val defaultArt = state.defaultArt.takeIf { asset == HomeCustomEnrichAsset.Automatic }
        if (defaultArt != null && defaultArt !is HomeCustomEnrichDefaultArt.Unavailable) {
            EnrichDefaultArtFace(
                art = defaultArt,
                // A cover, an icon and a cartridge contain their Default art (CoverLanePreviewTest) and the tile is the art's own shape, so this fills the tile
                // with no crop (a Default cover was cut at its top); a hero and a clip still fill and crop.
                contentScale = if (state.kind == EnrichMediaKind.COVER || state.kind == EnrichMediaKind.ICON || state.kind == EnrichMediaKind.TOME_PHYSICAL) {
                    ContentScale.Fit
                } else {
                    ContentScale.Crop
                },
                modifier = Modifier.fillMaxSize().testTag(CustomMediaTilePlateTag),
                coverTile = true,
                onAspect = onDefaultAspect,
            )
        } else if (asset == HomeCustomEnrichAsset.Automatic &&
            state.defaultArt is HomeCustomEnrichDefaultArt.Unavailable
        ) {
            // The preview stage says NO AUTOMATIC MEDIA, once. The tile that stands for it shows the rune mark; with no stage on the page
            // (a compact square, a short Main) nothing else would say it, so the tile says NO ART under the mark.
            Box(Modifier.fillMaxSize().testTag(CustomMediaTilePlateTag), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "ᛗ",
                        style = MimirTypeScale.RuneAction,
                        color = palette.runeMark.copy(alpha = 0.72f),
                    )
                    if (!stageShown) {
                        MimirChromeText(
                            text = "No art",
                            visualOverride = "NO ART",
                            style = MimirTypeScale.MicroStatus,
                            color = palette.textSecondary.copy(alpha = 0.84f),
                            maxLines = 1,
                            softWrap = false,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.testTag(CustomMediaDefaultNoArtTag),
                        )
                    }
                }
            }
        } else if (asset.previewPath != null) {
            // Every cartridge preview keeps its silhouette — Saved entries reopened from the
            // catalog must not crop just because the provider flag lives on the live candidate.
            //
            //  A COVER tile is a
            // 0.72 portrait and was drawn with ContentScale.Crop and zero padding, so anything that was
            // not already 0.72 lost its edges — and the box-3D renders C6 made the enrich default are
            // exactly that, a box on a squarer canvas. The tile is a CHOOSER: it has one job, which is
            // letting him tell two pieces of art apart, and a cropped box-3D and a cropped box-2D of the
            // same game look identical. Covers now contain, like the cartridge lane always has.
            val squarePreview = state.kind == EnrichMediaKind.ICON ||
                state.kind == EnrichMediaKind.COVER ||
                state.kind == EnrichMediaKind.TOME_PHYSICAL ||
                (asset as? HomeCustomEnrichAsset.Result)
                    ?.result
                    ?.let { it as? HomeCustomEnrichResult.Icon }
                    ?.candidate
                    ?.normalizeSquare == true
            var previewFailed by remember(asset.previewPath) { mutableStateOf(false) }
            if (previewFailed) {
                EnrichTileNoPreview(asset)
            } else {
                RetainedArtImage(
                    model = asset.previewPath?.let(::File),
                    contentDescription = asset.label,
                    contentScale = if (squarePreview) ContentScale.Fit else ContentScale.Crop,
                    onError = { previewFailed = true },
                    alpha = 0.96f,
                    // Contained art (a cover or an icon, whole) touches the clip along its limiting side: no air around it.
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(CustomMediaTilePlateTag),
                )
            }
        } else if (asset == HomeCustomEnrichAsset.More) {
            Box(Modifier.fillMaxSize().testTag(CustomMediaTilePlateTag).padding(MimirSpacing.sm), contentAlignment = Alignment.Center) {
                MimirIntentText(
                    intent = MimirTextIntent.StaticChrome(
                        spokenText = "More results",
                        visualOverride = "›\nMORE RESULTS",
                    ),
                    style = MimirTypeScale.Support,
                    color = palette.textSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            EnrichTileNoPreview(asset)
        }
        }
        // The bar: below the art, one height for every tile (the measured label line and its padding), lit or not. The provider or DEFAULT,
        // the line, and the marks (check, dot, and the remove mark's seat) all stand in it, so nothing is ever drawn over the art and
        // focusing a tile moves nothing. The More tile has no words of its own: the bar's height goes to its plate.
        if (hasBar) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(tileLabel.barHeightDp.dp)
                    .testTag(CustomMediaTileBarTag)
                    .background(
                        homeCustomEnrichGlassControlFill(
                            palette,
                            lightAlpha = EnrichTileLabelBarLightAlpha,
                            darkAlpha = EnrichTileLabelBarDarkAlpha,
                        ),
                    )
                    .padding(horizontal = MimirSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The gap before each child is its own 5 dp (the measured room counts exactly these), so a bar that says only its badge
                // and marks has no gap more than the room allowed.
                tileLabel.plan.badge?.let { badge ->
                    MimirChromeText(
                        text = badge,
                        style = MimirTypeScale.MicroStatus,
                        color = palette.textSecondary,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                val barText = tileLabel.plan.text
                if (barText != null) {
                    MimirAnimatedChromeText(
                        intent = MimirTextIntent.Authored(barText),
                        style = MimirTypeScale.RailAction,
                        color = palette.textPrimary,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = if (tileLabel.plan.badge != null) 5.dp else 0.dp),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (active) EnrichTileCheck(palette.accentBright, Modifier.padding(start = 5.dp))
                if (saved && !active) {
                    Box(
                        Modifier
                            .padding(start = 5.dp)
                            .size(6.dp)
                            .testTag(CustomMediaTileDotTag)
                            .background(palette.accentBright, RoundedCornerShape(3.dp)),
                    )
                }
                if (removable) {
                    // The seat is always here; the mark is lit, and takes a tap, only on the focused tile.
                    Text(
                        text = "×",
                        style = MimirTypeScale.Pill,
                        color = if (focused) palette.danger else Color.Transparent,
                        modifier = Modifier
                            .padding(start = 5.dp)
                            .testTag(CustomMediaTileRemoveTag)
                            .clip(RoundedCornerShape(MimirSpacing.radiusPill))
                            .then(if (focused) Modifier.clickable { onAction(ControllerAction.SECONDARY) } else Modifier)
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                    )
                }
            }
        } else {
            Spacer(Modifier.fillMaxWidth().height(tileLabel.barHeightDp.dp))
        }
    }
}

@Composable
internal fun EnrichFooter(
    authority: FocusAuthority,
    state: HomeCustomEnrichState,
    focusedKey: FocusKey?,
    compactTypography: Boolean,
    narrowWidth: Boolean,
    onAction: (ControllerAction) -> Unit,
    /** Where the row starts: the ring-and-flare reach the panel's padding lacks. The Customize media page's column already holds it, so it passes 0. */
    startInset: androidx.compose.ui.unit.Dp = homeCustomEnrichEdgeRingInset(homeCustomEnrichPanelPadding(compactTypography), flare = true),
) {
    val palette = LocalMimirPalette.current
    val graph = homeCustomEnrichLaneKeys(state)
    val lane = graph.entries.firstOrNull { focusedKey in it.value }?.key
    val removableFocused = state.assets
        .firstOrNull { homeCustomEnrichAssetKey(state, it) == focusedKey }
        ?.let(state::canRemove) == true
    val retryFocused = focusedKey == homeCustomEnrichRetryKey(state)
    // Asked of the SAME predicate the SELECT handler consults, not a second reading of the kind -- so
    // the legend cannot advertise a preview the dispatcher would refuse to open.
    val previewExpandable = lane == HomeCustomEnrichLane.GALLERY &&
        focusedKey != null &&
        homeCustomEnrichPreviewAsset(state, focusedKey)
            ?.let { homeCustomEnrichPreviewCanExpand(state, it) } == true
    val footerCopy = homeCustomEnrichFooterCopy(
        state,
        lane,
        removableFocused,
        retryFocused,
        previewExpandable,
    )
    val searchControl = state.searchControl()
    val searchControlAvailable = searchControl != HomeCustomEnrichSearchControl.NONE
    // Narrow screens keep both FocusAuthority destinations but paint only the destination that owns
    // focus. This prevents an irrelevant Upload action from squeezing Retry/Stop and its truthful legend.
    val showUpload = state.kind != EnrichMediaKind.CLIP &&
        (!narrowWidth ||
            !searchControlAvailable ||
            (searchControl != HomeCustomEnrichSearchControl.STOP && !retryFocused))
    val showSearchControl = homeCustomEnrichShouldShowSearchControl(
        state = state,
        narrowWidth = narrowWidth,
        retryFocused = retryFocused,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = homeCustomEnrichActionRowMinHeightDp().dp)
            // Upload (or Stop/Retry when Upload is hidden) sits on the panel's start and bottom edges, and the panel
            // clips: pull the row in by the ring-and-flare reach the panel's padding does not cover, and lift the
            // pills off the bottom by the floor's own air (inside the 56 dp floor, so the height budget holds).
            .padding(
                start = startInset,
                bottom = EnrichFooterBottomAirDp.dp,
            ),
        horizontalArrangement = Arrangement.spacedBy(MimirSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Upload and Retry/Stop are unselected etched chips of the top row's own family , never a filled or tinted
        // pill: see CustomMediaActionChip.kt.
        if (showUpload) {
            val key = homeCustomEnrichUploadKey(state)
            val focused = focusedKey == key
            EnrichActionChip(
                authority = authority,
                key = key,
                focused = focused,
                label = "Upload",
                onActivate = { if (focused) onAction(ControllerAction.CONFIRM) else authority.requestFocus(key) },
                plateTag = EnrichFooterUploadPlateTag,
                modifier = Modifier.testTag(EnrichFooterUploadTag),
            ) {
                EnrichUploadPlus(palette.accentBright)
                MimirChromeText(
                    if (compactTypography) "Upload" else "Upload image",
                    style = ActivityChipLabelStyle,
                    color = palette.materials.hero.onCover,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        if (showSearchControl) {
            val key = homeCustomEnrichRetryKey(state)
            val focused = focusedKey == key
            val stopping = searchControl == HomeCustomEnrichSearchControl.STOP
            EnrichActionChip(
                authority = authority,
                key = key,
                focused = focused,
                label = if (stopping) "Stop" else "Retry",
                onActivate = { if (focused) onAction(ControllerAction.CONFIRM) else authority.requestFocus(key) },
                plateTag = EnrichFooterRetryPlateTag,
                modifier = Modifier.testTag(EnrichFooterRetryTag),
                plateMinWidth = EnrichSearchControlSeat,
            ) {
                if (!stopping) {
                    Text("↻", style = ActivityChipLabelStyle, color = palette.materials.hero.onCover)
                }
                MimirChromeText(
                    text = if (stopping) "Stop" else "Retry",
                    style = ActivityChipLabelStyle,
                    color = if (stopping) palette.accentBright else palette.materials.hero.onCover,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        // The legend takes only the room to the right of the action pills : a legend too long for it stacks as whole-segment
        // lines (never clipped mid-word, never crowding Upload), and a status too long wraps at its words.
        Box(
            modifier = Modifier.weight(1f).testTag(EnrichFooterHintSlotTag),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (homeCustomEnrichFooterSaysExpand(state, focusedKey, footerCopy.legend)) {
                    EnrichHintLines(
                        text = "SELECT · Expand",
                        style = if (compactTypography) {
                            MimirTypeScale.ControlHintCompact
                        } else {
                            MimirTypeScale.ControlHint
                        },
                        color = palette.materials.hero.onCover.copy(alpha = 0.78f),
                    )
                }
                footerCopy.legend?.let { legend ->
                    EnrichHintLines(
                        text = legend,
                        style = if (compactTypography) MimirTypeScale.ControlHintCompact else MimirTypeScale.ControlHint,
                        color = palette.materials.hero.onCover.copy(alpha = 0.62f),
                    )
                }
                footerCopy.status?.let { status ->
                    MimirAnimatedChromeText(
                        text = status,
                        style = MimirTypeScale.MicroStatus,
                        color = palette.materials.hero.onCover.copy(alpha = 0.62f),
                        maxLines = 2,
                        softWrap = true,
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
    }
}

internal fun homeCustomEnrichShouldShowSearchControl(
    state: HomeCustomEnrichState,
    narrowWidth: Boolean,
    retryFocused: Boolean,
): Boolean {
    val control = state.searchControl()
    return control != HomeCustomEnrichSearchControl.NONE &&
        (control == HomeCustomEnrichSearchControl.STOP ||
            !narrowWidth ||
            retryFocused ||
            state.kind == EnrichMediaKind.CLIP)
}

internal data class HomeCustomEnrichFooterCopy(
    val legend: String? = null,
    val status: String? = null,
)

/** The shoulders step through the Media types on the Customize media page (customMediaTopRowReduce). */
internal const val HomeCustomEnrichTypeStepLegend = "L1 R1 TYPE"

/**
 * The legend with the shoulders' action added, ahead of B BACK: only on the Customize media page (a picker with Media types) and not
 * while a search runs, when L1 and R1 do nothing. A legend must never advertise a key that does nothing.
 */
internal fun homeCustomEnrichFooterCopy(
    state: HomeCustomEnrichState,
    lane: HomeCustomEnrichLane?,
    removableFocused: Boolean = false,
    retryFocused: Boolean = false,
    previewExpandable: Boolean = false,
): HomeCustomEnrichFooterCopy {
    val copy = homeCustomEnrichFooterCopyBase(state, lane, removableFocused, retryFocused, previewExpandable)
    val legend = copy.legend ?: return copy
    if (state.mediaTypes.isEmpty() || state.searchStatus is HomeCustomEnrichSearchStatus.Searching) return copy
    val back = " · B BACK"
    val stepped = if (legend.endsWith(back)) {
        legend.removeSuffix(back) + " · " + HomeCustomEnrichTypeStepLegend + back
    } else {
        legend + " · " + HomeCustomEnrichTypeStepLegend
    }
    return copy.copy(legend = stepped)
}

private fun homeCustomEnrichFooterCopyBase(
    state: HomeCustomEnrichState,
    lane: HomeCustomEnrichLane?,
    removableFocused: Boolean = false,
    retryFocused: Boolean = false,
    /** True when SELECT on the focused candidate opens the full preview. Defaults false: a legend
     * must never advertise a key that does nothing. */
    previewExpandable: Boolean = false,
): HomeCustomEnrichFooterCopy = when {
    !state.catalogReady && !state.catalogFirstRead -> HomeCustomEnrichFooterCopy(status = "Preparing saved choices…")
    state.operation == HomeCustomEnrichOperation.APPLY_COMMITTING ->
        HomeCustomEnrichFooterCopy(status = "Finishing…")
    state.operation == HomeCustomEnrichOperation.APPLY_FETCHING ->
        HomeCustomEnrichFooterCopy(status = "Preparing selection…")
    lane == HomeCustomEnrichLane.SEARCH &&
        state.searchControl() == HomeCustomEnrichSearchControl.STOP ->
        HomeCustomEnrichFooterCopy(legend = "B STOP")
    lane == HomeCustomEnrichLane.SEARCH -> HomeCustomEnrichFooterCopy(legend = MimirCopy.HintSearch)
    lane == HomeCustomEnrichLane.FILTERS -> HomeCustomEnrichFooterCopy(legend = MimirCopy.HintOpen)
    lane == HomeCustomEnrichLane.FOOTER &&
        retryFocused &&
        state.searchControl() == HomeCustomEnrichSearchControl.STOP ->
        HomeCustomEnrichFooterCopy(legend = "A STOP · B STOP")
    lane == HomeCustomEnrichLane.FOOTER && retryFocused ->
        HomeCustomEnrichFooterCopy(legend = MimirCopy.HintRetry)
    lane == HomeCustomEnrichLane.FOOTER -> HomeCustomEnrichFooterCopy(legend = MimirCopy.HintUpload)
    removableFocused && previewExpandable ->
        HomeCustomEnrichFooterCopy(legend = MimirCopy.HintMediaRemovablePreviewable)
    removableFocused -> HomeCustomEnrichFooterCopy(legend = MimirCopy.HintMediaRemovable)
    previewExpandable -> HomeCustomEnrichFooterCopy(legend = MimirCopy.HintMediaPreviewable)
    else -> HomeCustomEnrichFooterCopy(legend = MimirCopy.HintMedia)
}

internal fun homeCustomEnrichHint(
    state: HomeCustomEnrichState,
    lane: HomeCustomEnrichLane?,
    removableFocused: Boolean = false,
    retryFocused: Boolean = false,
    /** True when SELECT on the focused candidate opens the full preview. Defaults false: a legend
     * must never advertise a key that does nothing. */
    previewExpandable: Boolean = false,
): String = homeCustomEnrichFooterCopy(state, lane, removableFocused, retryFocused, previewExpandable)
    .let { copy ->
    listOfNotNull(copy.legend, copy.status).joinToString(" · ")
}

internal fun syntheticEnrichBounds(
    key: FocusKey,
    graph: Map<HomeCustomEnrichLane, List<FocusKey>>,
    fallbackIndex: Int,
): FocusBounds {
    val laneEntry = graph.entries.firstOrNull { key in it.value }
    val lane = laneEntry?.key ?: HomeCustomEnrichLane.GALLERY
    val index = laneEntry?.value?.indexOf(key)?.coerceAtLeast(0) ?: fallbackIndex
    // Virtual bounds override physical bounds in the authority, so the synthetic map must tell
    // the same story as the painted panel: Search and its filters are ONE row at the same y.
    val top = when (lane) {
        HomeCustomEnrichLane.SEARCH, HomeCustomEnrichLane.FILTERS -> 0f
        HomeCustomEnrichLane.GALLERY -> 160f
        HomeCustomEnrichLane.FOOTER -> 300f
    }
    val left = when (lane) {
        HomeCustomEnrichLane.FILTERS -> (index + 1) * 180f
        else -> index * 180f
    }
    return FocusBounds(left = left, top = top, right = left + 160f, bottom = top + 64f)
}
