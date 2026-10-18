package com.gruning.mimir.rewrite

import android.content.res.Resources
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.ui.LocalMimirControllerHintLabels
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.MediaArtBookCover
import com.gruning.mimir.rewrite.core.ui.MimirControllerHintLabels
import com.gruning.mimir.rewrite.core.ui.MimirHintKeycap
import com.gruning.mimir.rewrite.core.ui.MimirLiquidGlassPanel
import com.gruning.mimir.rewrite.core.ui.MimirMannazPin
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirNorseBookCover
import com.gruning.mimir.rewrite.core.ui.MimirPagerFlingVelocity
import com.gruning.mimir.rewrite.core.ui.MimirPageRunes
import com.gruning.mimir.rewrite.core.ui.MimirQuickRailAction
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import com.gruning.mimir.rewrite.core.ui.mimirFocusTileRing
import com.gruning.mimir.rewrite.core.ui.mimirHintPillColors
import com.gruning.mimir.rewrite.core.ui.mimirPagerSettlePage
import com.gruning.mimir.rewrite.core.ui.mimirReducedMotion
import com.gruning.mimir.rewrite.core.ui.theme.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.theme.MimirType
import kotlin.math.abs
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/*
 * The loading hint carousel uses interactive pictures to explain setup.
 *
 * Interaction and visual design:
 * - **Engine: a pager that follows the finger**; a neighbour peeking at
 *   each side is what says "swipe me". Its own scroll is off (`userScrollEnabled = false`) and a horizontal
 *   `draggable` drives it instead, so the pager exposes no scroll action and no scrollable node: onboarding's
 *   STANDARD pages never scroll, and focus stays with the FocusAuthority (the carousel is never focused).
 * - **Pictures: drawn from the product's own parts**: the no-art face, the Norse Tome, the dock's runes and
 *   the house focus ring. They take the live theme, ship no one's cover art, need no bitmap, and cannot go stale
 *   the way a screenshot of today's Home would.
 * - **Deck and words:** FINISH's bindings and words where they overlap, so the carousel
 *   teaches what FINISH later recaps. A keycap shows as the player's pad names it; a blank label drops the card.
 * - **Placement:** the carousel explains the permissions on ORIENTATION ([onboardingPermissionDeck]). The Home actions it showed
 *   during the library scan are pages of their own (OnboardingActionTour.kt), drawn by the same [OnboardingHintSceneView],
 *   and the favourites intro shows its MARK card alone ([OnboardingHintDemoCard]).
 */

/**
 * Which picture a card draws. The PERMISSION_ scenes are ORIENTATION's, the ACCESS_ scenes each permission screen's
 * example of what it does, and the rest are
 * Home's actions.
 */
internal enum class OnboardingHintScene {
    MARK, OPEN, EDIT, TOMES, DOCK, M_MENU,
    PERMISSION_STEPS, PERMISSION_ALLOW, PERMISSION_CONFIRM, PERMISSION_LATER,
    ACCESS_FILES, ACCESS_USAGE, ACCESS_OVERLAY, ACCESS_HOME,
}

/**
 * One card: a keycap (none for a card that teaches no button), the words, and the picture. [steps] is how many
 * permission screens follow, for [OnboardingHintScene.PERMISSION_STEPS]'s runes.
 */
internal data class OnboardingHintCard(
    val scene: OnboardingHintScene,
    val key: String?,
    val action: String,
    val steps: Int = 0,
)

/**
 * ORIENTATION's four cards explain how permission screens work, rather than what each one asks:
 * the picture's runes already count the screens, "Choose" suits touch and pad, and "turn it on" is OVERLAY's verb. [steps] is the number of screens this device will show after ORIENTATION. The
 * third says "may": the Home app screen opens Android's chooser, where there is nothing to turn on. The fourth names
 * Android's settings : these permissions live there, not in Mimir's Settings. The privacy promise
 * is not a card: it stays on the page, so a player who presses Continue at once has still read it.
 */
internal fun onboardingPermissionDeck(res: Resources, steps: Int): List<OnboardingHintCard> = listOf(
    OnboardingHintCard(
        OnboardingHintScene.PERMISSION_STEPS,
        key = null,
        action = res.getString(R.string.app_onboarding_access_card_steps),
        steps = steps,
    ),
    OnboardingHintCard(
        OnboardingHintScene.PERMISSION_ALLOW,
        key = null,
        action = res.getString(R.string.app_onboarding_access_card_allow),
    ),
    OnboardingHintCard(
        OnboardingHintScene.PERMISSION_CONFIRM,
        key = null,
        action = res.getString(R.string.app_onboarding_access_card_confirm),
    ),
    OnboardingHintCard(
        OnboardingHintScene.PERMISSION_LATER,
        key = null,
        action = res.getString(R.string.app_onboarding_access_card_later),
    ),
)

/** The dock card's keycap. There is no DOWN token in the pad labels, so it is a fixed glyph, like the footers' ◂ ▸. */
internal const val OnboardingHintDockKey = "▾"

/**
 * The deck, in the order a player meets the buttons on Home: open a game, edit it, the Tomes, the dock, then the
 * floating M where this device has one ([onboardingTeachesMButton], FINISH's rule). Where a card overlaps FINISH it
 * uses FINISH's exact words ([onboardingFinishLegend]). No SELECT card: SELECT was dropped from FINISH because
 * it has two meanings, and no START card, because inside a gamelist START hides a game.
 *
 * [markFirst] leads with the one thing the favourites step asks for, A marks a game for the shelf. It replaced
 * that page's how-line; the Tome's own footer still says
 * "A unmark" on a marked game, so taking one off is taught where it is done.
 */
internal fun onboardingHintDeck(
    res: Resources,
    teachMButton: Boolean,
    labels: MimirControllerHintLabels = MimirControllerHintLabels(),
    markFirst: Boolean = false,
): List<OnboardingHintCard> = buildList {
    fun pad(token: String, scene: OnboardingHintScene, action: String) {
        val key = labels.labelFor(token)
        if (key.isNotBlank()) add(OnboardingHintCard(scene = scene, key = key, action = action))
    }
    if (markFirst) pad("A", OnboardingHintScene.MARK, res.getString(R.string.app_onboarding_hint_mark))
    pad("A", OnboardingHintScene.OPEN, res.getString(R.string.app_onboarding_hint_open))
    pad("Y", OnboardingHintScene.EDIT, res.getString(R.string.app_onboarding_hint_edit))
    pad("X", OnboardingHintScene.TOMES, res.getString(R.string.app_onboarding_hint_tomes))
    add(OnboardingHintCard(OnboardingHintScene.DOCK, OnboardingHintDockKey, res.getString(R.string.app_onboarding_hint_dock)))
    if (teachMButton) add(OnboardingHintCard(OnboardingHintScene.M_MENU, "M", res.getString(R.string.app_onboarding_hint_menu)))
}

/** A card's dwell before the carousel turns by itself: the Compose TV Carousel's default. An policy. */
internal const val OnboardingHintDwellMs = 5_000L

/** After the player turns it, by pad or by finger, how long before the carousel turns by itself again. */
internal const val OnboardingHintInputHoldMs = 10_000L

/**
 * A card's picture plays once when it settles (the ring lands, the menu opens), at 3x the house
 * transient bound so the demonstration is readable.
 */
internal const val OnboardingHintDemoMs: Int = MimirMotion.OverlayMs * 3

/** Laps of the deck the pager holds, starting in the middle, so every turn in either direction is one page. */
internal const val OnboardingHintLoopLaps = 200

/** How much of each neighbour shows at the sides. */
private val OnboardingHintPeek = MimirSpacing.xxl + MimirSpacing.sm

/** The picture's smallest and largest height, and what the rest of a loading page takes around it. */
internal val OnboardingHintSceneMinHeight = 96.dp
internal val OnboardingHintSceneMaxHeight = 168.dp
private val OnboardingHintPageChrome = 232.dp

/**
 * The picture's height for a viewport of [viewportHeight]: what is left after the page's own chrome, between
 * [OnboardingHintSceneMinHeight] and [OnboardingHintSceneMaxHeight]. The 700x350 floor gets 118 dp; the Nova, the
 * Odin and the Thor get the cap. With no viewport (a natural-height probe) it is the minimum.
 */
internal fun onboardingHintSceneHeight(viewportHeight: Dp): Dp {
    if (!viewportHeight.isSpecified || viewportHeight <= 0.dp) return OnboardingHintSceneMinHeight
    return (viewportHeight - OnboardingHintPageChrome).coerceIn(OnboardingHintSceneMinHeight, OnboardingHintSceneMaxHeight)
}

/** The favourites intro's picture: smaller, because the page also carries its count, its progress and two pills. */
internal val OnboardingFavoritesHintSceneMinHeight = 64.dp
internal val OnboardingFavoritesHintSceneMaxHeight = 120.dp
private val OnboardingFavoritesHintPageChrome = 392.dp

/**
 * The favourites intro's picture height, or null when the viewport leaves no room for one: the cards then show
 * their keycap and words only (the 700x350 floor), so the page still fits without scrolling. With no viewport
 * (a natural-height probe) it is the minimum.
 */
internal fun onboardingFavoritesHintSceneHeight(viewportHeight: Dp): Dp? {
    if (!viewportHeight.isSpecified || viewportHeight <= 0.dp) return OnboardingFavoritesHintSceneMinHeight
    val room = viewportHeight - OnboardingFavoritesHintPageChrome
    if (room < OnboardingFavoritesHintSceneMinHeight) return null
    return room.coerceAtMost(OnboardingFavoritesHintSceneMaxHeight)
}

/**
 * ORIENTATION's picture: what is left after its eyebrow, title, the card's words and dots, the promise and Continue.
 * That is 240 dp measured (OnboardingScreenFitTest, 2026-09-28: the page is 304 dp at the default scale with the 64 dp
 * minimum picture), plus 8 dp to spare, so the 700x350 floor gets 102 dp and the taller handhelds the cap.
 */
internal val OnboardingPermissionSceneMinHeight = 64.dp
internal val OnboardingPermissionSceneMaxHeight = 132.dp
private val OnboardingPermissionPageChrome = 248.dp

/**
 * ORIENTATION's picture height, or null for caption-only cards when the viewport leaves less than the minimum. A
 * raised [fontScale] grows the words, so the picture gives way first. The minimum with no viewport (a natural-height
 * probe).
 */
internal fun onboardingPermissionSceneHeight(viewportHeight: Dp, fontScale: Float = 1f): Dp? {
    if (!viewportHeight.isSpecified || viewportHeight <= 0.dp) return OnboardingPermissionSceneMinHeight
    val room = viewportHeight - OnboardingPermissionPageChrome * fontScale.coerceAtLeast(1f)
    if (room < OnboardingPermissionSceneMinHeight) return null
    return room.coerceAtMost(OnboardingPermissionSceneMaxHeight)
}

/**
 * A permission screen's picture: what is left after
 * the header, "N OF 7", the title, the line, the small print and the pills, up to ORIENTATION's cap so one picture
 * size runs through the whole ACCESS sequence. That is 211 dp measured (OnboardingScreenFitTest, 2026-09-28: every
 * permission screen is 275 dp at the default scale with the 64 dp minimum picture), plus 8 dp to spare, so the
 * 700x350 floor gets 131 dp and the taller handhelds the cap.
 */
private val OnboardingAccessPageChrome = 219.dp

/**
 * A permission screen's picture height, or null where the viewport leaves less than the minimum: the screen then shows
 * its words alone and still fits without scrolling. A raised [fontScale] grows the words, so the picture gives way
 * first. The minimum with no viewport (a natural-height probe), which is what the fit matrix measures.
 */
internal fun onboardingAccessSceneHeight(viewportHeight: Dp, fontScale: Float = 1f): Dp? {
    if (!viewportHeight.isSpecified || viewportHeight <= 0.dp) return OnboardingPermissionSceneMinHeight
    val room = viewportHeight - OnboardingAccessPageChrome * fontScale.coerceAtLeast(1f)
    if (room < OnboardingPermissionSceneMinHeight) return null
    return room.coerceAtMost(OnboardingPermissionSceneMaxHeight)
}

/** A caption-only card's width: the longest caption and its keycap, with room to spare. */
private val OnboardingHintCaptionOnlyWidth = 300.dp

/**
 * A card's width: the picture's 16:9, but never narrower than its words. On the emulator at the Nova's 640x480
 * (2026-09-28) the favourites intro's 88 dp picture made a 172 dp card, and "Mark a game for your
 * shelf" ended "for y…" with the scene's fourth book cut at the edge; the carousel had the same width.
 */
internal fun onboardingHintCardWidth(sceneHeight: Dp?, fontScale: Float = 1f): Dp {
    // The words grow with the font size and the card did not, so a caption ended in an ellipsis (the 2026-10-06 large-font
    // debt). At a raised scale the words' share of the card grows with it, up to the 2.0 the system offers; a viewport
    // narrower than that still caps the card, and the caption wraps there. At 1.0 and below it is exactly as it was.
    val wordsWidth = OnboardingHintCaptionOnlyWidth * (if (fontScale.isFinite()) fontScale.coerceIn(1f, 2f) else 1f)
    return if (sceneHeight == null) {
        wordsWidth
    } else {
        maxOf(sceneHeight * 16f / 9f + MimirSpacing.sm * 2, wordsWidth)
    }
}

/** The root viewport's height, which the hosts size the picture from; 0 where none is provided. */
@Composable
internal fun onboardingHintViewportHeight(): Dp = LocalMimirViewportProfile.current.heightDp.dp

/** -1 for LEFT, +1 for RIGHT, 0 for anything the carousel does not take. */
internal fun onboardingHintTurn(action: ControllerAction): Int = when (action) {
    ControllerAction.NAV_LEFT -> -1
    ControllerAction.NAV_RIGHT -> 1
    else -> 0
}

/**
 * The router-owned half, shared by [dispatchOnboarding] and the carousel on stage, as the favourites grid's state
 * is. The dispatch only asks for turns; the carousel plays them. A turn asked for while no carousel is on stage is
 * dropped, never replayed onto the next one.
 */
@Stable
internal class OnboardingHintCarouselState {
    private val requests = MutableSharedFlow<Int>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val turns: SharedFlow<Int> get() = requests

    /** True when [action] turned the carousel. */
    fun turn(action: ControllerAction): Boolean {
        val delta = onboardingHintTurn(action)
        if (delta == 0) return false
        requests.tryEmit(delta)
        return true
    }
}

/** Tags the carousel, so a test can find it without reading its words. */
internal const val OnboardingHintCarouselTag = "onboarding/hint-carousel"

/**
 * The carousel. Nothing in it is focusable or clickable: the pad turns it through [state], a finger drags it, and
 * after [OnboardingHintDwellMs] it turns itself, holding for [OnboardingHintInputHoldMs] after any input. Under
 * reduced motion it never turns itself and every turn is a cut. Every pager read sits in a draw or layer lambda
 * except the settled page, which changes once per turn.
 *
 * [sceneHeight] null draws the cards without their pictures. [autoAdvance] false stops the clock where it is (the
 * favourites intro once identification is done: the wait is over, and a card never jumps away under the player).
 */
@Composable
internal fun OnboardingHintCarousel(
    state: OnboardingHintCarouselState,
    deck: List<OnboardingHintCard>,
    sceneHeight: Dp?,
    modifier: Modifier = Modifier,
    autoAdvance: Boolean = true,
) {
    if (deck.isEmpty()) return
    val palette = LocalMimirPalette.current
    // Not `reducedMotion`: OnboardingAccessMotionTest counts AnimatedContent hosts by that name.
    val carouselReducedMotion = mimirReducedMotion()
    val turnSpec: AnimationSpec<Float> = remember(carouselReducedMotion) {
        onboardingAccessMotionSpec(carouselReducedMotion)
    }
    val pagerState = rememberPagerState(initialPage = deck.size * (OnboardingHintLoopLaps / 2)) {
        deck.size * OnboardingHintLoopLaps
    }
    // Every input restarts the clock: the dwell becomes the longer hold.
    var inputEpoch by remember { mutableIntStateOf(0) }
    LaunchedEffect(state, pagerState) {
        state.turns.collect { delta ->
            inputEpoch += 1
            // targetPage, not currentPage: a held pad turns one card per press even mid-turn; nothing queues.
            launch { pagerState.animateScrollToPage(pagerState.targetPage + delta, animationSpec = turnSpec) }
        }
    }
    // Read per pass, never a key: a key would cancel a turn in flight when the flag flips (the favourites intro stops
    // the clock the moment identification finishes) and leave the card frozen between seats.
    val currentAutoAdvance by rememberUpdatedState(autoAdvance)
    LaunchedEffect(inputEpoch, carouselReducedMotion) {
        if (carouselReducedMotion) return@LaunchedEffect
        delay(if (inputEpoch == 0) OnboardingHintDwellMs else OnboardingHintInputHoldMs)
        while (true) {
            if (currentAutoAdvance && !pagerState.isScrollInProgress) {
                pagerState.animateScrollToPage(pagerState.currentPage + 1, animationSpec = turnSpec)
            }
            delay(OnboardingHintDwellMs)
        }
    }

    val pageWidth = onboardingHintCardWidth(sceneHeight, LocalDensity.current.fontScale)
    val flingPx = with(LocalDensity.current) { MimirPagerFlingVelocity.toPx() }
    val dragFrom = remember { intArrayOf(0) }
    val dragState = rememberDraggableState { delta -> pagerState.dispatchRawDelta(-delta) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(OnboardingHintCarouselTag),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val pagerWidth = minOf(maxWidth, pageWidth + OnboardingHintPeek * 2)
            val sidePad = ((pagerWidth - pageWidth) / 2).coerceAtLeast(0.dp)
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .width(pagerWidth)
                    .onboardingHintPeekFade(sidePad)
                    .draggable(
                        state = dragState,
                        orientation = Orientation.Horizontal,
                        onDragStarted = {
                            pagerState.stopScroll()
                            dragFrom[0] = pagerState.currentPage
                            inputEpoch += 1
                        },
                        onDragStopped = { velocity ->
                            val target = mimirPagerSettlePage(dragFrom[0], pagerState.currentPage, velocity, flingPx)
                            pagerState.animateScrollToPage(target, animationSpec = turnSpec)
                        },
                    ),
                pageSize = PageSize.Fixed(pageWidth.coerceAtMost(pagerWidth)),
                contentPadding = PaddingValues(horizontal = sidePad),
                pageSpacing = MimirSpacing.md,
                beyondViewportPageCount = 0,
                userScrollEnabled = false,
                key = { it },
            ) { page ->
                val card = deck[page % deck.size]
                val settled = pagerState.settledPage == page
                OnboardingHintCardView(
                    card = card,
                    palette = palette,
                    sceneHeight = sceneHeight,
                    pageWidth = pageWidth,
                    settled = settled,
                    reducedMotion = carouselReducedMotion,
                    distance = { onboardingHintPageDistance(pagerState, page) },
                )
            }
        }
        Spacer(Modifier.height(MimirSpacing.sm))
        OnboardingHintPageDots(pagerState, deck.size, palette)
    }
}

/**
 * Fades the neighbours out across the peek instead of slicing them at the pager's edge. The pager clips hard at its own
 * bounds, which cut the side cards with a vertical line. The mask reaches
 * full strength where the centre card begins, so the card on stage is untouched; a turning card fades as it leaves.
 * An offscreen layer is safe here: the cards draw nothing outside themselves, and the pager clips horizontally anyway.
 */
private fun Modifier.onboardingHintPeekFade(peek: Dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    // RC-52 (audit 2026-10-14, O-15a): the four-stop brush was rebuilt on every frame of a drag or a turn. It is built once per size,
    // here, and the draw block only paints it.
    .drawWithCache {
        val fade = (peek.toPx() / size.width).coerceIn(0f, 0.5f)
        val mask = if (fade > 0f) {
            Brush.horizontalGradient(
                0f to Color.Transparent,
                fade to Color.Black,
                1f - fade to Color.Black,
                1f to Color.Transparent,
            )
        } else {
            null
        }
        onDrawWithContent {
            drawContent()
            if (mask != null) drawRect(brush = mask, blendMode = BlendMode.DstIn)
        }
    }

/** How many pages [page] is from the centre, fractionally; 0 when it is the card on stage. */
private fun onboardingHintPageDistance(pagerState: PagerState, page: Int): Float =
    abs((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction)

/**
 * One card: FINISH's glass, the picture, then the keycap and the verb as one node ("A, Open a game to play"). The
 * neighbours sit a little smaller and dimmer: scale on the layer, a scrim over the picture only, never layer alpha,
 * because the glass, the picture and the words overlap and alpha would push each neighbour into an offscreen buffer.
 */
@Composable
private fun OnboardingHintCardView(
    card: OnboardingHintCard,
    palette: MimirThemePalette,
    sceneHeight: Dp?,
    pageWidth: Dp,
    settled: Boolean,
    reducedMotion: Boolean,
    distance: () -> Float,
) {
    // Plays once, the first time this card settles; a card that leaves keeps its end state rather than snapping
    // back, and a card turned back to does not replay. A card two away is disposed and starts fresh.
    val cardDescription = onboardingHintCardDescription(onboardingResources(), card)
    val demo = remember { Animatable(if (reducedMotion) 1f else 0f) }
    LaunchedEffect(settled, reducedMotion) {
        when {
            reducedMotion -> demo.snapTo(1f)
            settled -> demo.animateTo(1f, tween(OnboardingHintDemoMs, easing = MimirMotion.Easing))
        }
    }
    MimirLiquidGlassPanel(
        modifier = Modifier
            .width(pageWidth)
            .graphicsLayer {
                val scale = 1f - 0.06f * distance().coerceIn(0f, 1f)
                scaleX = scale
                scaleY = scale
            }
            .semantics(mergeDescendants = true) { contentDescription = cardDescription },
        contentPadding = MimirSpacing.sm,
        verticalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
        gradientBody = false,
    ) {
        if (sceneHeight != null) {
            OnboardingHintSceneFrame(sceneHeight, palette, dim = distance) {
                OnboardingHintSceneView(card, palette, sceneHeight, demo = { demo.value })
            }
        }
        OnboardingHintCaption(card, palette, Modifier.align(Alignment.CenterHorizontally))
    }
}

/** "A, Open a game to play"; a card with no keycap reads its words alone. */
internal fun onboardingHintCardDescription(res: Resources, card: OnboardingHintCard): String =
    card.key?.let { res.getString(R.string.app_onboarding_hint_card_description, it, card.action) } ?: card.action

/** A card's keycap and words, one line. */
@Composable
private fun OnboardingHintCaption(card: OnboardingHintCard, palette: MimirThemePalette, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        card.key?.let { MimirHintKeycap(key = it, colors = mimirHintPillColors(palette)) }
        Text(
            text = card.action,
            style = MimirType.RowSupport,
            color = palette.textPrimary,
            // One line at the default size; at a raised font scale the words wrap in a narrow viewport rather than ending in an
            // ellipsis (the card widens first, onboardingHintCardWidth), the 2026-10-06 large-font debt.
            maxLines = if (LocalDensity.current.fontScale > 1f) Int.MAX_VALUE else 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * One card on its own, no pager: the favourites intro's MARK demonstration. Its picture plays once, when [playing] first turns true (the host
 * passes the step's arrival), and holds its end state. [sceneHeight] null shows the words alone.
 */
@Composable
internal fun OnboardingHintDemoCard(
    card: OnboardingHintCard,
    sceneHeight: Dp?,
    playing: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = LocalMimirPalette.current
    val demoReducedMotion = mimirReducedMotion()
    val cardDescription = onboardingHintCardDescription(onboardingResources(), card)
    val demo = remember { Animatable(if (demoReducedMotion) 1f else 0f) }
    LaunchedEffect(playing, demoReducedMotion) {
        when {
            demoReducedMotion -> demo.snapTo(1f)
            playing -> demo.animateTo(1f, tween(OnboardingHintDemoMs, easing = MimirMotion.Easing))
        }
    }
    MimirLiquidGlassPanel(
        modifier = modifier
            .widthIn(max = onboardingHintCardWidth(sceneHeight, LocalDensity.current.fontScale))
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = cardDescription },
        contentPadding = MimirSpacing.sm,
        verticalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
        gradientBody = false,
    ) {
        if (sceneHeight != null) {
            OnboardingHintSceneFrame(sceneHeight, palette) {
                OnboardingHintSceneView(card, palette, sceneHeight, demo = { demo.value })
            }
        }
        OnboardingHintCaption(card, palette, Modifier.align(Alignment.CenterHorizontally))
    }
}

/** The raised box a picture sits in, the same on a card and on an action page. */
@Composable
internal fun OnboardingHintSceneFrame(
    sceneHeight: Dp,
    palette: MimirThemePalette,
    modifier: Modifier = Modifier,
    dim: () -> Float = { 0f },
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(sceneHeight)
            .clip(RoundedCornerShape(MimirSpacing.radiusCard))
            .background(palette.surfaceRaised.copy(alpha = 0.55f))
            .drawWithContent {
                drawContent()
                val d = dim().coerceIn(0f, 1f)
                if (d > 0f) drawRect(palette.scrim, alpha = 0.45f * d)
            }
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        // A picture is drawn, not read: the mock-up's own words (a book's title, a menu's rows) are part of the drawing, sized
        // for the frame. At a raised font scale they grew past it and ended in an ellipsis ("Favorites" on a 33 dp book), so
        // the picture keeps the default size, the way the header strip keeps its (the 2026-10-06 large-font debt).
        val density = LocalDensity.current
        val pictureDensity = remember(density.density, density.fontScale) {
            if (density.fontScale > 1f) Density(density = density.density, fontScale = 1f) else density
        }
        CompositionLocalProvider(LocalDensity provides pictureDensity) { content() }
    }
}

/**
 * A no-art game face: the honest book every surface draws when a game has no art yet, with no title and no one's
 * cover. Blank, not the fallback glyph: that one always titles its book, and a picture of "a game" read "Untitled".
 */
@Composable
private fun OnboardingHintFace(height: Dp, palette: MimirThemePalette, modifier: Modifier = Modifier, mannazAlpha: (() -> Float)? = null) {
    Box(modifier.size(width = height * 0.75f, height = height)) {
        MediaArtBookCover(title = "", platformIdValue = null, palette = palette, blankTitle = true, mannazAlpha = mannazAlpha)
    }
}

/** A card's picture. [demo] runs 0 to 1 once: the ring lands, the menu opens, the sheet rises. */
@Composable
internal fun OnboardingHintSceneView(
    card: OnboardingHintCard,
    palette: MimirThemePalette,
    sceneHeight: Dp,
    demo: () -> Float,
) {
    val face = sceneHeight * 0.62f
    when (card.scene) {
        // A row of the Tome's games; the ring lands on one, then its Mannaz pin arrives: marked for the shelf.
        OnboardingHintScene.MARK -> Row(
            horizontalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(4) { index ->
                val marked = index == 2
                // The pin's arrival, 0 to 1 over the demo's second half: the marked book's embossed Mannaz gives way as exactly that much of the pin arrives (one ᛗ per book, the shared book marker), so one mark stands at every instant, never neither and never both at full strength. Both read it in the draw phase, so the row never recomposes for it.
                val pinProgress: () -> Float = { ((demo() - 0.5f) * 2f).coerceIn(0f, 1f) }
                Box(
                    modifier = if (marked) {
                        Modifier.mimirFocusTileRing(
                            progress = { (demo() * 2f).coerceAtMost(1f) },
                            color = palette.tileFocusRing,
                            cornerRadius = 6.dp,
                        )
                    } else {
                        Modifier
                    },
                ) {
                    OnboardingHintFace(face * 0.9f, palette, mannazAlpha = if (marked) ({ 1f - pinProgress() }) else null)
                    if (marked) {
                        MimirMannazPin(
                            present = true,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(MimirSpacing.xs)
                                .graphicsLayer {
                                    val pin = pinProgress()
                                    alpha = pin
                                    scaleX = 0.6f + 0.4f * pin
                                    scaleY = 0.6f + 0.4f * pin
                                },
                            height = 14.dp,
                        )
                    }
                }
            }
        }
        // Three games on a shelf; the ring lands on the middle one.
        OnboardingHintScene.OPEN -> Row(
            horizontalArrangement = Arrangement.spacedBy(MimirSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OnboardingHintFace(face * 0.86f, palette)
            OnboardingHintFace(
                face,
                palette,
                Modifier.mimirFocusTileRing(progress = demo, color = palette.tileFocusRing, cornerRadius = 6.dp),
            )
            OnboardingHintFace(face * 0.86f, palette)
        }
        // A game with its menu opening beside it, in the menu's own words.
        OnboardingHintScene.EDIT -> Row(
            horizontalArrangement = Arrangement.spacedBy(MimirSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OnboardingHintFace(
                face,
                palette,
                Modifier.mimirFocusTileRing(progress = { 1f }, color = palette.tileFocusRing, cornerRadius = 6.dp),
            )
            Column(
                modifier = Modifier
                    .graphicsLayer {
                        val d = demo()
                        alpha = d
                        translationX = (1f - d) * -MimirSpacing.lg.toPx()
                    }
                    .clip(RoundedCornerShape(MimirSpacing.sm))
                    .background(palette.surfaceRaised)
                    .padding(horizontal = MimirSpacing.md, vertical = MimirSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(MimirSpacing.xs),
            ) {
                listOf(
                    HomeTileMenuAction.CUSTOM_ENRICH,
                    HomeTileMenuAction.ADD_TO_COLLECTION,
                    HomeTileMenuAction.ACHIEVEMENTS,
                ).forEachIndexed { index, action ->
                    Text(
                        text = action.label,
                        style = MimirType.MicroStatus,
                        color = if (index == 0) palette.textPrimary else palette.textMuted,
                        maxLines = 1,
                    )
                }
            }
        }
        // Two bound Tomes; the ring lands on the first.
        OnboardingHintScene.TOMES -> Row(
            horizontalArrangement = Arrangement.spacedBy(MimirSpacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val book = Modifier.size(width = face * 0.75f, height = face)
            MimirNorseBookCover(
                focused = false,
                eyebrow = "",
                title = stringResource(R.string.app_onboarding_hint_scene_favorites),
                modifier = book.mimirFocusTileRing(progress = demo, color = palette.tileFocusRing, cornerRadius = 6.dp),
                showFocusRing = false,
                compact = true,
                palette = palette,
            )
            MimirNorseBookCover(
                focused = false,
                eyebrow = "",
                title = stringResource(R.string.app_onboarding_hint_scene_tome),
                modifier = book,
                showFocusRing = false,
                compact = true,
                palette = palette,
            )
        }
        // Home's shelf above, the dock below it; the ring lands on the Library rune.
        OnboardingHintScene.DOCK -> Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(MimirSpacing.sm)) {
                repeat(4) { OnboardingHintFace(face * 0.55f, palette) }
            }
            OnboardingHintDockRow(MimirQuickRailAction.LIBRARY, palette, demo)
        }
        // The floating M, and its menu opening beside it when held.
        OnboardingHintScene.M_MENU -> Row(
            horizontalArrangement = Arrangement.spacedBy(MimirSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(face * 0.5f)
                    // The ring before the clip, so the circle does not cut it (the dock strip's fix, same review).
                    .mimirFocusTileRing(progress = demo, color = palette.tileFocusRing, cornerRadius = face * 0.25f)
                    .clip(CircleShape)
                    .background(palette.surfaceRaised),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "M", style = MimirType.RowSupport, color = palette.accentBright)
            }
            Column(
                modifier = Modifier
                    .graphicsLayer {
                        val d = demo()
                        alpha = d
                        translationX = (1f - d) * -MimirSpacing.lg.toPx()
                    }
                    .clip(RoundedCornerShape(MimirSpacing.sm))
                    .background(palette.surfaceRaised)
                    .padding(MimirSpacing.md),
                verticalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
            ) {
                // Bars, not words: the menu's rows vary with the device, so the picture does not name them.
                OnboardingHintBars(face, listOf(0.9f, 0.7f, 0.8f), palette)
            }
        }
        // One screen at a time: a page slides in, and under it the runes the permission screens count with, the
        // first lit.
        OnboardingHintScene.PERMISSION_STEPS -> Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
        ) {
            Column(
                modifier = Modifier
                    .graphicsLayer {
                        val d = demo()
                        alpha = d
                        translationX = (1f - d) * MimirSpacing.lg.toPx()
                    }
                    .clip(RoundedCornerShape(MimirSpacing.sm))
                    .background(palette.surfaceRaised)
                    .border(1.dp, palette.outline, RoundedCornerShape(MimirSpacing.sm))
                    .padding(MimirSpacing.sm),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MimirSpacing.xs),
            ) {
                OnboardingHintBars(face * 0.9f, listOf(0.7f, 1f, 0.8f), palette)
                OnboardingHintPillShape(face * 0.45f, palette, gold = true)
            }
            MimirPageRunes(current = 0, count = card.steps.coerceIn(1, 9), palette = palette)
        }
        // Allow, and Not now beside it; the ring lands on Allow.
        OnboardingHintScene.PERMISSION_ALLOW -> Row(
            horizontalArrangement = Arrangement.spacedBy(MimirSpacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OnboardingHintPillShape(
                width = face * 0.8f,
                palette = palette,
                gold = true,
                label = stringResource(R.string.app_common_allow),
                modifier = Modifier.mimirFocusTileRing(progress = demo, color = palette.tileFocusRing, cornerRadius = 14.dp),
            )
            OnboardingHintPillShape(width = face * 0.8f, palette = palette, gold = false, label = stringResource(R.string.app_common_not_now))
        }
        // Android's own page rising over Mimir, and B to come back.
        OnboardingHintScene.PERMISSION_CONFIRM -> Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
        ) {
            OnboardingHintPanel(
                palette = palette,
                modifier = Modifier.graphicsLayer {
                    val d = demo()
                    alpha = d
                    translationY = (1f - d) * MimirSpacing.lg.toPx()
                },
            ) {
                OnboardingHintBars(face * 1.2f, listOf(0.5f, 1f, 0.85f), palette)
            }
            val back = LocalMimirControllerHintLabels.current.labelFor("B")
            if (back.isNotBlank()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(MimirSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MimirHintKeycap(key = back, colors = mimirHintPillColors(palette))
                    Text(text = stringResource(R.string.app_onboarding_access_card_back_to_mimir), style = MimirType.MicroStatus, color = palette.textSecondary, maxLines = 1)
                }
            }
        }
        // Android's settings, where every one of these can be changed later: a page of rows, one already on and the
        // next turning on. No ring and no Mimir rune: this page is Android's .
        OnboardingHintScene.PERMISSION_LATER -> OnboardingHintPanel(palette) {
            OnboardingHintBars(face * 0.7f, listOf(1f), palette)
            listOf(0.9f, 0.65f).forEachIndexed { row, fraction ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(MimirSpacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.width(face * 1.3f)) {
                        OnboardingHintBars(face * 1.3f, listOf(fraction), palette)
                    }
                    OnboardingHintSwitch(on = if (row == 0) ({ 1f }) else demo, palette = palette)
                }
            }
        }
        // All files: a folder of games, and each game's art arriving beside it, one after the other.
        OnboardingHintScene.ACCESS_FILES -> OnboardingHintPanel(palette) {
            repeat(2) { row ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OnboardingHintBars(face * 1.1f, listOf(if (row == 0) 0.9f else 0.7f), palette)
                    OnboardingHintFace(
                        face * 0.4f,
                        palette,
                        Modifier.graphicsLayer {
                            val d = (demo() * 2f - row).coerceIn(0f, 1f)
                            alpha = d
                            translationX = (1f - d) * -MimirSpacing.lg.toPx()
                        },
                    )
                }
            }
        }
        // Usage access: the game sinks away and Home's shelf comes back, the ring on the game just played.
        OnboardingHintScene.ACCESS_USAGE -> Box(contentAlignment = Alignment.Center) {
            Row(
                // ModulateAlpha, not the default layer (O-18): the centre face wears a focus ring that reaches past the faces, and a default
                // layer is composited at its own bounds, so the ring's top and bottom were cut while `demo` was below 1 and popped out on the
                // last frame. The faces do not overlap each other, so per-draw alpha reads the same.
                modifier = Modifier.graphicsLayer {
                    alpha = demo()
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                },
                horizontalArrangement = Arrangement.spacedBy(MimirSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OnboardingHintFace(face * 0.86f, palette)
                OnboardingHintFace(
                    face,
                    palette,
                    Modifier.mimirFocusTileRing(progress = demo, color = palette.tileFocusRing, cornerRadius = 6.dp),
                )
                OnboardingHintFace(face * 0.86f, palette)
            }
            OnboardingHintPanel(
                palette = palette,
                modifier = Modifier.graphicsLayer {
                    val d = demo()
                    alpha = 1f - d
                    translationY = d * MimirSpacing.lg.toPx()
                },
            ) {
                OnboardingHintBars(face * 1.6f, listOf(0.5f, 1f, 0.85f), palette)
            }
        }
        // Draw over other apps: the floating M arriving on a game's corner.
        OnboardingHintScene.ACCESS_OVERLAY -> Box {
            OnboardingHintPanel(palette, Modifier.padding(top = face * 0.2f, end = face * 0.2f)) {
                OnboardingHintBars(face * 1.6f, listOf(0.5f, 1f, 0.85f), palette)
            }
            OnboardingHintFloatingM(
                size = face * 0.5f,
                palette = palette,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .graphicsLayer {
                        val d = demo()
                        alpha = d
                        scaleX = 0.6f + 0.4f * d
                        scaleY = 0.6f + 0.4f * d
                    },
            )
        }
        // Home: Home's shelf above the dock, the ring on the Home rune.
        OnboardingHintScene.ACCESS_HOME -> Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(MimirSpacing.sm)) {
                repeat(4) { OnboardingHintFace(face * 0.55f, palette) }
            }
            OnboardingHintDockRow(MimirQuickRailAction.HOME, palette, demo)
        }
    }
}

/** A page that is not Mimir's, a game or Android's own: a raised, outlined panel for bars to sit on. */
@Composable
private fun OnboardingHintPanel(
    palette: MimirThemePalette,
    modifier: Modifier = Modifier,
    padding: Dp = MimirSpacing.sm,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(MimirSpacing.sm))
            .background(palette.surfaceRaised)
            .border(1.dp, palette.outline, RoundedCornerShape(MimirSpacing.sm))
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(MimirSpacing.xs),
        content = content,
    )
}

/** The floating M, as M_MENU draws it, outlined so it reads over a panel. */
@Composable
private fun OnboardingHintFloatingM(size: Dp, palette: MimirThemePalette, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(palette.surfaceRaised)
            .border(1.dp, palette.outline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "M", style = MimirType.MicroStatus, color = palette.accentBright)
    }
}

/**
 * An Android switch in the picture: a muted track that turns gold as [on] reaches 1, its thumb sliding across. Read
 * in the draw phase.
 */
@Composable
private fun OnboardingHintSwitch(on: () -> Float, palette: MimirThemePalette) {
    Box(
        modifier = Modifier
            .width(OnboardingHintSwitchWidth)
            .height(OnboardingHintSwitchThumb + OnboardingHintSwitchInset * 2)
            .clip(RoundedCornerShape(MimirSpacing.radiusPill))
            .background(palette.textMuted.copy(alpha = 0.3f)),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = on().coerceIn(0f, 1f) }
                .background(androidx.compose.ui.graphics.Brush.linearGradient(onboardingGoldPillFill(palette, focused = false))),
        )
        Box(
            Modifier
                .padding(OnboardingHintSwitchInset)
                .size(OnboardingHintSwitchThumb)
                .graphicsLayer {
                    val travel = OnboardingHintSwitchWidth - OnboardingHintSwitchThumb - OnboardingHintSwitchInset * 2
                    translationX = on().coerceIn(0f, 1f) * travel.toPx()
                }
                .clip(CircleShape)
                .background(palette.textPrimary),
        )
    }
}

private val OnboardingHintSwitchWidth = 24.dp
private val OnboardingHintSwitchThumb = 8.dp
private val OnboardingHintSwitchInset = 2.dp

/** Muted bars standing in for words that vary (a menu's rows, an Android page). */
@Composable
private fun OnboardingHintBars(width: Dp, fractions: List<Float>, palette: MimirThemePalette) {
    fractions.forEach { fraction ->
        Box(
            Modifier
                .width(width * fraction)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(palette.textMuted.copy(alpha = 0.45f)),
        )
    }
}

/** A pill in the picture: gold like onboarding's own, or a ghost; never focusable, never a control. */
@Composable
private fun OnboardingHintPillShape(
    width: Dp,
    palette: MimirThemePalette,
    gold: Boolean,
    label: String? = null,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .width(width)
            .height(if (label == null) 10.dp else 28.dp)
            .clip(RoundedCornerShape(MimirSpacing.radiusPill))
            .background(
                if (gold) {
                    androidx.compose.ui.graphics.Brush.linearGradient(onboardingGoldPillFill(palette, focused = false))
                } else {
                    androidx.compose.ui.graphics.SolidColor(palette.surfaceRaised)
                },
            )
            .then(
                if (gold) Modifier else Modifier.border(1.dp, palette.outline, RoundedCornerShape(MimirSpacing.radiusPill)),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (label != null) {
            Text(
                text = label,
                style = MimirType.MicroStatus,
                color = if (gold) palette.onAccent else palette.textSecondary,
                maxLines = 1,
            )
        }
    }
}

/**
 * The dock's runes, [lit] ringed by [demo]. The strip is a shaped background, not a clip: the ring reaches 13.5 dp
 * past its rune, and a clip cut its outer strokes flat. Home's own dock draws the
 * same ring unclipped.
 */
@Composable
private fun OnboardingHintDockRow(lit: MimirQuickRailAction, palette: MimirThemePalette, demo: () -> Float) {
    Row(
        modifier = Modifier
            .background(palette.surfaceRaised, RoundedCornerShape(MimirSpacing.radiusPill))
            .padding(horizontal = MimirSpacing.sm, vertical = MimirSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MimirQuickRailAction.entries.forEach { action ->
            val isLit = action == lit
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .then(
                        if (isLit) {
                            Modifier.mimirFocusTileRing(progress = demo, color = palette.tileFocusRing, cornerRadius = 9.dp)
                        } else {
                            Modifier
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = action.rune,
                    style = MimirType.RowSupport,
                    color = if (isLit) palette.accentBright else palette.textMuted,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** One dot per card, the shown one lit. Read in the draw phase; says "2 of 5" to a screen reader. */
@Composable
private fun OnboardingHintPageDots(pagerState: PagerState, count: Int, palette: MimirThemePalette) {
    val res = onboardingResources()
    val dot = 6.dp
    val gap = MimirSpacing.sm
    val rest = palette.textMuted.copy(alpha = 0.4f)
    val lit = palette.focusRing
    Box(
        Modifier
            .size(width = dot * count + gap * (count - 1), height = dot)
            .semantics {
                // Read in the semantics phase, as before: the dots repaint without recomposing the pager.
                stateDescription = res.getString(R.string.app_onboarding_progress_of, pagerState.currentPage % count + 1, count)
            }
            .drawBehind {
                val shown = pagerState.currentPage % count
                val r = dot.toPx() / 2f
                val step = (dot + gap).toPx()
                repeat(count) { i -> drawCircle(if (i == shown) lit else rest, r, Offset(r + i * step, r)) }
            },
    )
}

/** The deck for the device, as the player's pad names its buttons. */
@Composable
internal fun rememberOnboardingHintDeck(teachMButton: Boolean, markFirst: Boolean = false): List<OnboardingHintCard> {
    val labels = LocalMimirControllerHintLabels.current
    val res = onboardingResources()
    return remember(res, teachMButton, labels, markFirst) { onboardingHintDeck(res, teachMButton, labels, markFirst) }
}
