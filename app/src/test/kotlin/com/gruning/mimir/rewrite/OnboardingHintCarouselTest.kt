package com.gruning.mimir.rewrite

import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.ui.MimirControllerHintLabels
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The loading step shows an interactive picture carousel instead of paragraphs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnboardingHintCarouselTest {
    private val res = onboardingTestResources()

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the deck teaches Home's buttons, with FINISH's words where they overlap`() {
        val deck = onboardingHintDeck(res, teachMButton = true)
        assertEquals(
            listOf("A" to "Open a game to play", "Y" to "Edit a game", "X" to "Open Tomes", "▾" to "Library and Settings", "M" to "Hold for menu"),
            deck.map { it.key to it.action },
        )
        // FINISH's hints row points past the recap to the Library's legend; the deck has no card for it.
        val finish = onboardingFinishLegend(res, teachMButton = true)
            .filterNot { it.action == res.getString(R.string.app_onboarding_hint_show_hints) }
            .map { it.key to it.action }
        val shared = deck.map { it.key to it.action }.filter { (key, _) -> finish.any { it.first == key } }
        // The same key and the same words; the order differs (the deck follows Home, FINISH's list is its own).
        assertEquals(finish.toSet(), shared.toSet())
    }

    @Test
    fun `no M card where the device has no M, and a blank pad label drops its card`() {
        assertFalse(onboardingHintDeck(res, teachMButton = false).any { it.scene == OnboardingHintScene.M_MENU })
        val labels = MimirControllerHintLabels(byFactoryToken = mapOf("Y" to "", "X" to "□"))
        val deck = onboardingHintDeck(res, teachMButton = false, labels = labels)
        assertEquals(listOf("A", "□", "▾"), deck.map { it.key })
        assertFalse(deck.any { it.scene == OnboardingHintScene.EDIT })
    }

    @Test
    fun `LEFT and RIGHT turn the carousel, nothing else does`() {
        val state = OnboardingHintCarouselState()
        assertTrue(state.turn(ControllerAction.NAV_RIGHT))
        assertTrue(state.turn(ControllerAction.NAV_LEFT))
        for (action in listOf(ControllerAction.CONFIRM, ControllerAction.CANCEL, ControllerAction.NAV_UP, ControllerAction.NAV_DOWN)) {
            assertFalse(action.name, state.turn(action))
        }
        assertEquals(1, onboardingHintTurn(ControllerAction.NAV_RIGHT))
        assertEquals(-1, onboardingHintTurn(ControllerAction.NAV_LEFT))
    }

    @Test
    fun `the carousel settles through core-ui's pager rule, at the shared fling velocity`() {
        // The settle table itself moved to core-ui (MimirPagerSettleTest) when the Bottom's pagers took the same rule.
        val source = java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/OnboardingHintCarousel.kt").readText()
        assertTrue(source.contains("mimirPagerSettlePage(dragFrom[0], pagerState.currentPage, velocity, flingPx)"))
        assertTrue(source.contains("MimirPagerFlingVelocity.toPx()"))
        assertFalse("no private copy of the rule", source.contains("fun onboardingHintSettlePage") || source.contains("OnboardingHintFlingVelocity"))
    }

    @Test
    fun `the picture takes what the viewport leaves, between its floor and its cap`() {
        assertEquals(OnboardingHintSceneMinHeight, onboardingHintSceneHeight(0.dp))
        assertEquals(118.dp, onboardingHintSceneHeight(350.dp))
        assertEquals(OnboardingHintSceneMaxHeight, onboardingHintSceneHeight(480.dp))
        assertEquals(OnboardingHintSceneMaxHeight, onboardingHintSceneHeight(564.7.dp))
        assertEquals(OnboardingHintSceneMinHeight, onboardingHintSceneHeight(300.dp))
    }

    @Test
    fun `the library ask shows before a pick and after a failure, not while scanning or once done`() {
        assertTrue(onboardingLibraryAsksForFolder(OnboardingLibraryStatus.IDLE))
        assertTrue(onboardingLibraryAsksForFolder(OnboardingLibraryStatus.FAILED))
        assertFalse(onboardingLibraryAsksForFolder(OnboardingLibraryStatus.SCANNING))
        assertFalse(onboardingLibraryAsksForFolder(OnboardingLibraryStatus.DONE))
    }

    @Test
    fun `the pad turns the carousel on stage, the clock turns it after the dwell, and it never scrolls`() {
        val state = OnboardingHintCarouselState()
        val deck = onboardingHintDeck(res, teachMButton = true)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MimirTheme {
                OnboardingHintCarousel(
                    state = state,
                    deck = deck,
                    sceneHeight = OnboardingHintSceneMinHeight,
                    modifier = Modifier.requiredWidth(500.dp),
                )
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithContentDescription("A, Open a game to play").assertExists()
        assertEquals("1 of 5", shownDot())
        // No scroll action anywhere in it: the finger drives a draggable, not the pager's own scroll.
        assertTrue(compose.onAllNodes(hasScrollAction(), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())

        compose.runOnUiThread {
            state.turn(ControllerAction.NAV_RIGHT)
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeBy(OnboardingHintDemoMs.toLong() + 64L)
        assertEquals("2 of 5", shownDot())

        // Input held the clock for the longer hold; after it, the carousel turns by itself.
        compose.mainClock.advanceTimeBy(OnboardingHintDwellMs)
        assertEquals("2 of 5", shownDot())
        compose.mainClock.advanceTimeBy(OnboardingHintInputHoldMs - OnboardingHintDwellMs + 500L)
        assertEquals("3 of 5", shownDot())
    }

    /** ruling 21's how-line is retired; the favourites intro's carousel leads with MARK. */
    @Test
    fun `the favourites deck leads with A mark, then the Home deck`() {
        val deck = onboardingHintDeck(res, teachMButton = true, markFirst = true)
        assertEquals(OnboardingHintScene.MARK, deck.first().scene)
        assertEquals("A" to "Mark a game for your Home shelf", deck.first().key to deck.first().action)
        assertEquals(onboardingHintDeck(res, teachMButton = true), deck.drop(1))
    }

    @Test
    fun `the favourites picture is smaller, and gone where the viewport has no room for it`() {
        assertEquals(OnboardingFavoritesHintSceneMinHeight, onboardingFavoritesHintSceneHeight(0.dp))
        assertEquals(null, onboardingFavoritesHintSceneHeight(350.dp))
        assertEquals(88.dp, onboardingFavoritesHintSceneHeight(480.dp))
        assertEquals(OnboardingFavoritesHintSceneMaxHeight, onboardingFavoritesHintSceneHeight(564.7.dp))
    }

    @Test
    fun `with no picture the cards still say their key and words, and the clock can be stopped`() {
        val state = OnboardingHintCarouselState()
        val deck = onboardingHintDeck(res, teachMButton = false, markFirst = true)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MimirTheme {
                OnboardingHintCarousel(
                    state = state,
                    deck = deck,
                    sceneHeight = null,
                    autoAdvance = false,
                    modifier = Modifier.requiredWidth(500.dp),
                )
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithContentDescription("A, Mark a game for your Home shelf").assertExists()
        compose.mainClock.advanceTimeBy(OnboardingHintDwellMs * 3)
        assertEquals("1 of ${deck.size}", shownDot())
    }

    /**
     * The favourites intro stops the clock when identification finishes, and that can land in the middle of a
     * turn. The turn must still land: a card must never be left frozen between seats.
     */
    @Test
    fun `stopping the clock mid-turn still lands the card in its seat`() {
        val state = OnboardingHintCarouselState()
        val deck = onboardingHintDeck(res, teachMButton = true)
        var auto by mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MimirTheme {
                OnboardingHintCarousel(
                    state = state,
                    deck = deck,
                    sceneHeight = OnboardingHintSceneMinHeight,
                    autoAdvance = auto,
                    modifier = Modifier.requiredWidth(500.dp),
                )
            }
        }
        compose.mainClock.advanceTimeByFrame()
        // Into the first turn by the clock, which runs for MimirMotion.OverlayMs.
        compose.mainClock.advanceTimeBy(OnboardingHintDwellMs + 64L)
        compose.runOnUiThread {
            auto = false
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeBy(1_000L)
        val carousel = compose.onNodeWithTag(OnboardingHintCarouselTag).fetchSemanticsNode().boundsInRoot
        val card = compose.onNodeWithContentDescription("Y, Edit a game").fetchSemanticsNode().boundsInRoot
        assertEquals("the second card sits in the centre seat", carousel.center.x, card.center.x, 1f)
        // And the clock stays stopped from there.
        compose.mainClock.advanceTimeBy(OnboardingHintDwellMs * 2)
        assertEquals("2 of 5", shownDot())
    }

    private fun shownDot(): String {
        compose.onNodeWithTag(OnboardingHintCarouselTag).assertExists()
        val nodes = compose.onAllNodes(
            androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        return nodes.single().config[SemanticsProperties.StateDescription]
    }
}
