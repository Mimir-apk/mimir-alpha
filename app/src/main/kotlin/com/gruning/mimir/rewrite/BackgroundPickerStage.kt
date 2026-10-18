package com.gruning.mimir.rewrite

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.model.library.PlatformRow
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.MimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.feature.library.MimirIdentityHeader
import com.gruning.mimir.rewrite.feature.library.PlatformHeroTile
import com.gruning.mimir.rewrite.feature.library.PlatformTileArtStore
import com.gruning.mimir.rewrite.feature.settings.BackgroundPickerScope

/**
 * The Background picker's stage with the REAL shelf on it (concept A, the preview must show
 * the background as the shelf really draws it, with real content over it).
 *
 * The ground under this is the picker's own: the real `MimirShelfBackground` bake at stage size (crop to fill, the
 * setting's blur, `surfaceBase` at the chosen dim, as stored), cross-fading as the preview changes.
 * This stands on it, static, and reads no preview: the Library's identity header (the badge, LIBRARY, the beam with
 * CHOOSE A PLATFORM on it, the status pill in the corner the Library gives it), the first three platforms of the player's
 * own shelf as the emulation coverflow's book tiles -- the middle one centred and lit, each wearing the art the player
 * picked for it -- and the centred platform's title band. Every piece is the shelf's own composable at the shelf's own
 * dp, laid out at the viewport's width and shown through a smaller density, so the stage is the shelf scaled down:
 * crisp text at the shown size, and no layer for anything to clip in.
 *
 * Nothing here takes focus or touch. The tiles compose with `interactive = false` and `registerPhysicalFocus = false`,
 * which attach neither `mimirFocusable` nor a click, so no key of theirs reaches the picker's scope -- the picker's tiles
 * hold every focus key -- and a tap on the stage lands on the picker's own swallow, as before.
 *
 * The stage is a wide, short window (about 3:1) on a 4:3 or 16:9 shelf, so what it shows is the shelf's top: the header
 * at its own height, then the tiles and the title band in the height that is left, with the lanes compressed the way the
 * compact shelf compresses them (the title band at its floor, the tiles taking the rest -- about the size of the old
 * silhouettes on a 16:9 device, about twice that on the Nova). The mode strip, the Add pill and the dock are not shown:
 * they are private to the Library screen or carry focus.
 */
@Composable
internal fun BoxScope.BackgroundPickerLiveStageChrome(
    authority: FocusAuthority,
    platforms: List<PlatformRow>,
    gameCount: Int,
) {
    // Read the shelf's width BEFORE the density changes below: the measured viewport, or the stage's own width when
    // nothing has measured one (a test's bare rule).
    val profile = LocalMimirViewportProfile.current
    val density = LocalDensity.current
    val art = PlatformTileArtStore.art.collectAsState().value
    val hydrated = PlatformTileArtStore.hydrated.collectAsState().value
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val stageWidthDp = maxWidth.value
        val stageHeightDp = maxHeight.value
        val shelfWidthDp = if (profile.widthDp > 0f) profile.widthDp else stageWidthDp
        val compactShelf = backgroundStageShelfIsCompact(profile, shelfWidthDp)
        val geometry = remember(stageWidthDp, stageHeightDp, shelfWidthDp, density.fontScale) {
            solveBackgroundStageGeometry(stageWidthDp, stageHeightDp, shelfWidthDp, density.fontScale)
        }
        val shown = platforms.take(BackgroundStageTileCount)
        val centred = shown.getOrNull(if (shown.size == BackgroundStageTileCount) 1 else 0)
        // The shelf at the stage's scale: the same density scaled, the same font scale, so every dp and sp the shelf's
        // pieces are authored in lands at (scale x) its size, and `fillMaxSize` here IS the shelf's layout size.
        CompositionLocalProvider(LocalDensity provides Density(density.density * geometry.scale, density.fontScale)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(start = BackgroundStageGutter, end = BackgroundStageGutter, top = BackgroundStageTopPadding),
            ) {
                MimirIdentityHeader(
                    platformCount = platforms.size,
                    gameCount = gameCount,
                    timeLabel = "",
                    // The Library blanks its header stat: the status pill owns that corner (item 4).
                    statLabel = "",
                    bleed = BackgroundStageGutter,
                    compact = compactShelf,
                    modifier = Modifier.testTag(BackgroundStageHeaderTestTag),
                )
                Spacer(Modifier.height(BackgroundStageHeaderGap))
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(geometry.gapDp.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        shown.forEach { platform ->
                            key(platform.platformId.value) {
                            val isCentred = platform === centred
                            PlatformHeroTile(
                                label = platform.label,
                                gameCount = platform.gameCount,
                                platformIdValue = platform.platformId.value,
                                // The art the player picked for this platform (Y on the shelf), as the shelf shows it.
                                overrideCoverArt = art[platform.platformId.value],
                                suppressDefaultArtWhileHydrating = !hydrated,
                                bookHero = true,
                                bookFocusWidthDp = geometry.seatWidthDp.dp,
                                bookFocusHeightDp = geometry.seatHeightDp.dp,
                                showLabels = false,
                                focused = isCentred,
                                centered = isCentred,
                                ringLit = isCentred,
                                animateFocusEntrance = false,
                                authority = authority,
                                key = backgroundStageTileKey(platform),
                                scope = BackgroundPickerScope,
                                registerPhysicalFocus = false,
                                autoBringIntoView = false,
                                interactive = false,
                                onOpen = {},
                                modifier = Modifier.testTag(backgroundStageTileTestTag(platform, isCentred)),
                            )
                            }
                        }
                    }
                }
                BackgroundStageTitleBand(centred, Modifier.height(geometry.titleBandDp.dp))
            }
            // The Liquid Mimir glance pill owns the shelf's upper-right corner (FUX-02 R3), under the same inset.
            HomeStatusPillHost(Modifier.align(Alignment.TopEnd).padding(MimirSpacing.lg))
        }
    }
}

/**
 * Whether the shelf the stage shows is compact: the header's compact form (a 28 dp badge, a 112 dp stat lane) and the shelf's width come from ONE answer. With a measured viewport it is
 * the profile's. With none (a bare host: the profile is the unmeasured default and not compact) the stage stands in for the shelf at [shelfWidthDp], its own width, and a shelf narrower
 * than the picker's compact limit is compact too -- it read the unmeasured default beside a compact-width shelf, which laid the wide header's 210 dp stat lane out in a 328 dp stage and left
 * the title "L..." (reported at 360 dp and font 1.3).
 */
internal fun backgroundStageShelfIsCompact(profile: MimirViewportProfile, shelfWidthDp: Float): Boolean =
    if (profile.widthDp > 0f) profile.isCompact else shelfWidthDp < BackgroundStageCompactShelfDp

/**
 * The coverflow's title band for the centred platform: its label over its game count, centred, one line each -- the
 * same lane the emulation shelf draws under its hero (`ShelfHeroTitle`, which the Library keeps to itself). Blank when
 * the shelf has no platform to centre.
 */
@Composable
private fun BackgroundStageTitleBand(centred: PlatformRow?, modifier: Modifier) {
    val palette = LocalMimirPalette.current
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = MimirSpacing.xxl).testTag(BackgroundStageTitleTestTag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (centred == null) return@Column
        Text(
            text = centred.label,
            color = palette.textPrimary,
            fontSize = 15.sp,
            lineHeight = BackgroundStageTitleLineSp.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "${centred.gameCount} ${if (centred.gameCount == 1) "game" else "games"}",
            color = palette.textSecondary,
            fontSize = 10.5.sp,
            lineHeight = BackgroundStageCountLineSp.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** How the shelf fits the stage: the scale it is shown at, and the lanes solved in the shelf's own dp. */
internal data class BackgroundStageGeometry(
    /** The stage's width over the shelf's: the density the shelf is shown through. Never above 1. */
    val scale: Float,
    /** The stage in the shelf's dp: the width is the viewport's, the height is what the stage's height comes to. */
    val layoutWidthDp: Float,
    val layoutHeightDp: Float,
    /** The title band under the tiles, at the compact shelf's rule (a fifth of the hero band, 38 to 48), and never shorter than its two lines at the font scale. */
    val titleBandDp: Float,
    /** The art band the tiles stand in: what is left under the header and above the title. */
    val artBandDp: Float,
    /** The book tile's box, the art band less the ring's room, over the focus scale; 144 x 164 at the shelf's cap. */
    val seatWidthDp: Float,
    val seatHeightDp: Float,
    /** Between tiles: the shelf's ruled gap-to-seat ratio (20 : 144). */
    val gapDp: Float,
)

/**
 * The stage is [stageWidthDp] x [stageHeightDp]; the shelf it shows is [shelfWidthDp] wide. The shelf is scaled to the
 * stage's width (never up) and its top lanes fill the stage's height: the Library's top padding, its identity header,
 * the header gap, then the hero band -- title at the compact rule, the tiles in the rest -- as the compact shelf solves
 * its own lanes (`solveLibraryShelfGeometry`). The seat is the art band less the ring's room over the focus scale, capped
 * at the ruled 144 x 164 book and floored so a tile is never a speck.
 */
internal fun solveBackgroundStageGeometry(
    stageWidthDp: Float,
    stageHeightDp: Float,
    shelfWidthDp: Float,
    fontScale: Float = 1f,
): BackgroundStageGeometry {
    val stageWidth = stageWidthDp.coerceAtLeast(1f)
    val stageHeight = stageHeightDp.coerceAtLeast(1f)
    val shelfWidth = shelfWidthDp.coerceAtLeast(1f)
    val scale = (stageWidth / shelfWidth).coerceIn(BackgroundStageMinScale, 1f)
    val layoutWidth = stageWidth / scale
    val layoutHeight = stageHeight / scale
    val hero = (
        layoutHeight - BackgroundStageTopPadding.value - BackgroundStageHeaderHeightDp - BackgroundStageHeaderGap.value
        ).coerceAtLeast(0f)
    // Sweep 10-15, F7 (font 1.3): the band's two lines are sp, so they grow with the font and the band did not (38 dp for 40.3 dp of lines: "1 game" was cut at the stage's edge). The band is the larger
    // of the shelf's rule and its own two lines at this font scale, and the tiles take the rest.
    val lines = (BackgroundStageTitleLineSp + BackgroundStageCountLineSp) * fontScale.coerceAtLeast(1f)
    val title = (hero * BackgroundStageTitleShare).coerceIn(BackgroundStageTitleFloorDp, BackgroundStageTitleCapDp).coerceAtLeast(lines)
    val art = (hero - title).coerceAtLeast(0f)
    val seatHeight = ((art - BackgroundStageRingRoomDp) / BackgroundStageFocusScale)
        .coerceIn(BackgroundStageSeatFloorDp, BackgroundStageBookHeightDp)
    val seatWidth = seatHeight * BackgroundStageBookWidthDp / BackgroundStageBookHeightDp
    return BackgroundStageGeometry(
        scale = scale,
        layoutWidthDp = layoutWidth,
        layoutHeightDp = layoutHeight,
        titleBandDp = title,
        artBandDp = art,
        seatWidthDp = seatWidth,
        seatHeightDp = seatHeight,
        gapDp = seatWidth * BackgroundStageGapToSeat,
    )
}

/** The three tiles the stage shows: the shelf's first platforms, the middle one centred. */
internal const val BackgroundStageTileCount = 3

internal fun backgroundStageTileKey(platform: PlatformRow): FocusKey = FocusKey("appearance/backgroundStage/${platform.platformId.value}")

internal const val BackgroundStageHeaderTestTag = "appearance-background-stage-header"
internal const val BackgroundStageTitleTestTag = "appearance-background-stage-title"
internal const val BackgroundStageCentredTileTestTag = "appearance-background-stage-tile-centred"
internal fun backgroundStageTileTestTag(platform: PlatformRow, centred: Boolean): String =
    if (centred) BackgroundStageCentredTileTestTag else "appearance-background-stage-tile:${platform.platformId.value}"

// The Library shelf's own lanes and the coverflow's ruled book, in the shelf's dp (LibraryScreen.kt's
// `solveLibraryShelfGeometry`, MimirShelfChrome.kt's identity header, CoverflowMotion.kt's book constants). They are
// the Library's internals, repeated here by value so the stage lays the shelf out as the shelf does.
private val BackgroundStageGutter = 20.dp
private val BackgroundStageTopPadding = 8.dp
private val BackgroundStageHeaderGap = 10.dp
private const val BackgroundStageHeaderHeightDp = 45f
private const val BackgroundStageTitleShare = 0.22f
private const val BackgroundStageTitleFloorDp = 38f
private const val BackgroundStageTitleCapDp = 48f
/** The band's two lines' heights in sp, the label's and the count's: the band is never shorter than their sum at the font scale. */
private const val BackgroundStageTitleLineSp = 18f
private const val BackgroundStageCountLineSp = 13f
private const val BackgroundStageBookWidthDp = 144f
private const val BackgroundStageBookHeightDp = 164f
private const val BackgroundStageGapToSeat = 20f / 144f
private const val BackgroundStageFocusScale = 1.03f
/** Twice the bloom outset (10 dp), the room the lit tile's ring is given above and below in the art band. */
private const val BackgroundStageRingRoomDp = 20f
private const val BackgroundStageSeatFloorDp = 32f
private const val BackgroundStageMinScale = 0.25f

/** The shelf width under which an unmeasured stage reads its shelf as compact: the appearance pickers' own compact limit. */
private const val BackgroundStageCompactShelfDp = 480f
