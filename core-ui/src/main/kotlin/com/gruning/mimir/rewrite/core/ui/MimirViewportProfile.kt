package com.gruning.mimir.rewrite.core.ui

import kotlin.math.max
import kotlin.math.min
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Dimension-owned primary-display posture. Device model strings never decide layout: the RG Rotate is
 * recognised by its compact, near-square app viewport while RP5/Thor retain the established landscape path.
 */
enum class MimirViewportPosture {
    STANDARD,
    COMPACT_SQUARE,
    COMPACT_PORTRAIT,
}

/** User choice for the primary single-display layout. The regular layout remains the safe default. */
enum class MimirViewportMode(val label: String) {
    AUTOMATIC("Automatic"),
    COMPACT("Compact"),
}

/** Process-wide snapshot state shared by the primary host and Settings. Persistence is owned by app. */
object MimirViewportSettings {
    var mode by mutableStateOf(MimirViewportMode.AUTOMATIC)
}

/** The measured primary app-window profile. Children must read this instead of classifying reduced panes. */
val LocalMimirViewportProfile = staticCompositionLocalOf {
    MimirViewportProfile(MimirViewportPosture.STANDARD, 0f, 0f)
}

@Composable
fun MimirPrimaryViewportProfile(
    mode: MimirViewportMode = MimirViewportSettings.mode,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewport = rememberMeasuredViewport(
            maxWidth = maxWidth,
            maxHeight = maxHeight,
            inDomain = { widthDp, heightDp ->
                widthDp.isFinite() && heightDp.isFinite() && widthDp > 0f && heightDp > 0f
            },
            beforeFirstValid = MimirBeforeFirstValidViewport.RENDER_NOTHING,
        )
        val inheritedProfile = LocalMimirViewportProfile.current
        val profile = if (viewport.shouldRender) {
            mimirViewportProfile(viewport.width.value, viewport.height.value, mode)
        } else {
            inheritedProfile
        }
        // Keep the provider and child in one stable composition structure while a window briefly
        // measures at zero during rotation or display handoff. The held viewport keeps the same
        // dimensions for transient zero; the first zero simply composes into a zero-sized box.
        CompositionLocalProvider(LocalMimirViewportProfile provides profile) {
            Box(
                modifier = if (viewport.shouldRender) {
                    Modifier.mimirMeasuredViewport(viewport)
                } else {
                    Modifier
                },
            ) {
                content()
            }
        }
    }
}

data class MimirViewportProfile(
    val posture: MimirViewportPosture,
    val widthDp: Float,
    val heightDp: Float,
) {
    val isCompactSquare: Boolean get() = posture == MimirViewportPosture.COMPACT_SQUARE
    val isCompact: Boolean get() = posture != MimirViewportPosture.STANDARD
    val isPortrait: Boolean get() = heightDp > widthDp

    /**
     * A 4:3 or square field: long side over short side at most [FourThreeOrSquareMaximumAspect], whatever the
     * posture or mode. The Nova's 640x480 (STANDARD, 1.33), every compact square and the 1080 dp square boundary
     * qualify; the Odin/Thor (1.78), the RP5-class shelf (2.0) and the QA emulator (2.22) do not. Orientation-blind
     * like the compact-square test below, so a 480x640 portrait counts and a 360x640 one does not. The unmeasured
     * 0x0 default is never 4:3.
     *
     * the Default theme's sun moves below the text column on
     * these fields -- `MimirShelfBackground`'s `sceneLightBelowText` -- and stays exactly where it is on wide ones.
     */
    val isFourThreeOrSquare: Boolean
        get() = widthDp > 0f && heightDp > 0f &&
            max(widthDp, heightDp) / min(widthDp, heightDp) <= FourThreeOrSquareMaximumAspect

    /**
     * Whether the Default theme's sun sits below the text column on this field -- `MimirShelfBackground`'s
     * `sceneLightBelowText`. Initially applied to 4:3 and square fields ([isFourThreeOrSquare]); OR-3
     *  extends it to 16:9, where the Thor showed the default anchor over
     * onboarding's lines ("camera, microphone or contacts.", "achievements."). Every measured field now; an
     * unmeasured one (no provider) keeps the default anchor.
     */
    val sceneLightBelowText: Boolean
        get() = widthDp > 0f && heightDp > 0f
}

private const val CompactSquareMaximumAspect = 1.12f

/**
 * Between 4:3 (1.333) and 3:2 (1.5): a 4:3 panel with rounding slack is in, and every certified wide viewport (1.78
 * and above) is out. See [MimirViewportProfile.isFourThreeOrSquare].
 */
private const val FourThreeOrSquareMaximumAspect = 1.40f
private const val CompactSquareMaximumSideDp = 760f
private const val CompactPortraitMaximumWidthDp = 600f

fun mimirViewportProfile(
    widthDp: Float,
    heightDp: Float,
    mode: MimirViewportMode = MimirViewportMode.AUTOMATIC,
): MimirViewportProfile {
    require(widthDp.isFinite() && heightDp.isFinite()) { "viewport dimensions must be finite" }
    require(widthDp > 0f && heightDp > 0f) { "viewport dimensions must be positive" }
    val shortSide = min(widthDp, heightDp)
    val longSide = max(widthDp, heightDp)
    val compactSquare = longSide / shortSide <= CompactSquareMaximumAspect &&
        longSide <= CompactSquareMaximumSideDp
    val compactPortrait = heightDp / widthDp > CompactSquareMaximumAspect &&
        widthDp < CompactPortraitMaximumWidthDp
    val posture = when (mode) {
        MimirViewportMode.COMPACT -> if (heightDp > widthDp) {
            MimirViewportPosture.COMPACT_PORTRAIT
        } else {
            MimirViewportPosture.COMPACT_SQUARE
        }
        MimirViewportMode.AUTOMATIC -> when {
            compactSquare -> MimirViewportPosture.COMPACT_SQUARE
            compactPortrait -> MimirViewportPosture.COMPACT_PORTRAIT
            else -> MimirViewportPosture.STANDARD
        }
    }
    return MimirViewportProfile(
        posture = posture,
        widthDp = widthDp,
        heightDp = heightDp,
    )
}
