package com.gruning.mimir.rewrite

import android.content.res.Resources
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.MimirControllerHintLabels
import com.gruning.mimir.rewrite.core.ui.MimirHintKeycap
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.mimirHintPillColors
import com.gruning.mimir.rewrite.core.ui.mimirReducedMotion
import com.gruning.mimir.rewrite.core.ui.theme.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.theme.MimirType

/*
 * One onboarding page per action:
 * - The pages run during the library scan, the longest wait in onboarding with nothing to press.
 * - A goes forward, B goes back one page, and Skip ends them. Nothing advances by itself, and nothing swipes.
 * - FINISH keeps its short list as the recap, in the same words.
 * Design updated 2026-09-28.
 *
 * The scan and the pages run side by side. A scan that finishes first changes the status line to the count, and
 * the last page's Done lands on the result. Pages that end first leave the plain building screen. A scan that
 * fails shows in the status line, and the next press ends the pages onto the failure, never mid-page.
 *
 * They show once per run. That is held here, in router-owned memory (MimirNavigationCoordinator), not in the
 * onboarding checkpoint:
 * - they only ever show during a scan, and a relaunch that lands past the scan never meets them;
 * - a relaunch mid-scan shows them again. That is the one gap, the trade for not threading a stored flag through the
 *   store, the coordinator and both hosts the way ORIENTATION's RC-13 bit is.
 */

internal val OnboardingActionTourNextKey = FocusKey("onboarding/library/tour/next")
internal val OnboardingActionTourSkipKey = FocusKey("onboarding/library/tour/skip")

/** The pages' two pills, in reading order. */
internal val OnboardingActionTourKeys = listOf(OnboardingActionTourNextKey, OnboardingActionTourSkipKey)

/**
 * Where the player is in the pages. Shared by [dispatchOnboarding] and the screen, like the hint carousel's state.
 * [pageCount] is set by the screen from the deck it draws, so the dispatch and the screen agree on the last page.
 */
@Stable
internal class OnboardingActionTourState {
    var page by mutableIntStateOf(0)
        private set

    /** The page the player turned from, so the glide travels the way they went. */
    var previousPage by mutableIntStateOf(0)
        private set

    var started by mutableStateOf(false)
        private set

    var finished by mutableStateOf(false)
        private set

    var pageCount by mutableIntStateOf(0)

    fun start() {
        started = true
    }

    /** A on Next: the next page, or the end after the last one (or with no pages at all). */
    fun next() {
        previousPage = page
        if (page + 1 >= pageCount) finished = true else page += 1
    }

    /** B: one page back. On the first page it does nothing, because LIBRARY's Back is forward-only. */
    fun back(): Boolean {
        if (page == 0) return false
        previousPage = page
        page -= 1
        return true
    }

    fun skip() {
        finished = true
    }

    /** A harness seam: the fit matrix measures every page from one shared state. */
    internal fun showForTest(page: Int) {
        finished = false
        started = true
        previousPage = page
        this.page = page
    }
}

/**
 * Whether LIBRARY shows the pages: from the pick until they end. A scan that finishes or fails first waits for them;
 * a scan started after they ended (a second folder) never shows them again.
 */
internal fun onboardingActionTourShows(status: OnboardingLibraryStatus, tour: OnboardingActionTourState?): Boolean {
    if (tour == null || tour.finished) return false
    return when (status) {
        OnboardingLibraryStatus.SCANNING -> true
        OnboardingLibraryStatus.DONE, OnboardingLibraryStatus.FAILED -> tour.started
        OnboardingLibraryStatus.IDLE -> false
    }
}

/** One page: a card from the hint deck (its keycap, FINISH's words, its picture), and one line more. */
internal data class OnboardingActionPage(val card: OnboardingHintCard, val detail: String)

/**
 * The pages, one per card of the Home deck ([onboardingHintDeck]): A open, Y edit, X Tomes, ▾ the dock, and M where
 * the device has one. The titles are FINISH's words, so the recap says what the pages taught.
 */
internal fun onboardingActionPages(
    res: Resources,
    teachMButton: Boolean,
    labels: MimirControllerHintLabels = MimirControllerHintLabels(),
): List<OnboardingActionPage> = onboardingHintDeck(res, teachMButton, labels).map { card ->
    OnboardingActionPage(card, onboardingActionPageDetail(res, card.scene))
}

/**
 * Each page's line under the picture names WHERE the button works, using the player-visible
 * place names ("your Home shelf", "the Library's game lists") and the Mimir button. Open Tomes
 * works only on the Home shelf. Each place is checked against dispatch:
 * - A opens a game on Home's shelf (feature-library HomeShelfModels, CONFIRM -> onLaunch) and in the Library's
 *   gamelist (LibraryDispatch).
 * - Y opens the game's menu on Home's shelf only (HomeShelfModels, TERTIARY). On a Tome it does nothing, and in the
 *   Library it opens Customize media alone.
 * - X opens the Tomes from Home (HomeShelfModels, SECONDARY). In the Library X means Favorites.
 * - Down reaches the dock from Home's shelf (NAV_DOWN) and from the Library's platform shelf, not from a gamelist.
 * - The M is held anywhere, a game included (MainActivity's long press).
 */
internal fun onboardingActionPageDetail(res: Resources, scene: OnboardingHintScene): String = when (scene) {
    OnboardingHintScene.OPEN -> res.getString(R.string.app_onboarding_tour_open)
    OnboardingHintScene.EDIT -> res.getString(R.string.app_onboarding_tour_edit)
    OnboardingHintScene.TOMES -> res.getString(R.string.app_onboarding_tour_tomes)
    OnboardingHintScene.DOCK -> res.getString(R.string.app_onboarding_tour_dock)
    OnboardingHintScene.M_MENU -> res.getString(R.string.app_onboarding_tour_menu)
    else -> ""
}

/**
 * The picture's smallest and largest height on a page, and what the page takes around it: 218 dp measured
 * (OnboardingScreenFitTest, 2026-09-28: a page is 290 dp at the default scale with the 72 dp minimum picture), plus
 * 8 dp to spare. At the 700x350 floor the picture gets 124 dp; the Odin, the Nova and the Thor get the cap.
 */
internal val OnboardingActionSceneMinHeight = 72.dp
internal val OnboardingActionSceneMaxHeight = 200.dp
private val OnboardingActionPageChrome = 226.dp

/**
 * A page's picture height for a viewport of [viewportHeight]: what is left after the header, the status line, the
 * page's words and its pills, between the minimum and the maximum. A raised [fontScale] grows the words, so the
 * picture gives way first. With no viewport (a natural-height probe) it is the minimum, which is what the fit
 * matrix measures.
 */
internal fun onboardingActionSceneHeight(viewportHeight: Dp, fontScale: Float = 1f): Dp {
    if (!viewportHeight.isSpecified || viewportHeight <= 0.dp) return OnboardingActionSceneMinHeight
    val chrome = OnboardingActionPageChrome * fontScale.coerceAtLeast(1f)
    return (viewportHeight - chrome).coerceIn(OnboardingActionSceneMinHeight, OnboardingActionSceneMaxHeight)
}

/**
 * One page's body: "2 OF 5", the keycap and the verb, the picture, and its line. The picture plays once when
 * [playing] first turns true (the host passes "this page is the one arriving"), after the page's own glide lands,
 * and holds its end state; under reduced motion it shows the end state.
 */
@Composable
internal fun OnboardingActionPageBody(
    page: OnboardingActionPage,
    index: Int,
    count: Int,
    sceneHeight: Dp,
    playing: Boolean,
) {
    val palette = LocalMimirPalette.current
    val pageReducedMotion = mimirReducedMotion()
    val demo = remember(page.card) { Animatable(if (pageReducedMotion) 1f else 0f) }
    LaunchedEffect(page.card, playing, pageReducedMotion) {
        when {
            pageReducedMotion -> demo.snapTo(1f)
            playing -> demo.animateTo(
                1f,
                tween(OnboardingHintDemoMs, delayMillis = OnboardingMotionMs, easing = MimirMotion.Easing),
            )
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        MimirChromeText(
            text = stringResource(R.string.app_onboarding_progress_of, index + 1, count),
            style = MimirType.MicroStatus,
            color = palette.textMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(MimirSpacing.xs))
        Row(
            modifier = Modifier.semantics(mergeDescendants = true) { heading() },
            horizontalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            page.card.key?.let { MimirHintKeycap(key = it, colors = mimirHintPillColors(palette)) }
            MimirChromeText(
                text = page.card.action,
                style = MimirType.TitleCaps,
                color = palette.textPrimary,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(MimirSpacing.sm))
        OnboardingHintSceneFrame(
            sceneHeight = sceneHeight,
            palette = palette,
            modifier = Modifier.width(sceneHeight * 16f / 9f),
        ) {
            OnboardingHintSceneView(page.card, palette, sceneHeight, demo = { demo.value })
        }
        if (page.detail.isNotEmpty()) {
            Spacer(Modifier.height(MimirSpacing.sm))
            Text(
                text = page.detail,
                style = MimirType.RowSupport,
                color = palette.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 460.dp),
            )
        }
    }
}
