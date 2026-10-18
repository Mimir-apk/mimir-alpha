package com.gruning.mimir.rewrite

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.metadata.online.RaProgressOutcome
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsAchievement
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsProgress
import com.gruning.mimir.rewrite.core.ui.MimirAchievementCardState
import com.gruning.mimir.rewrite.core.ui.MimirAchievementCopy
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private fun staysProgress(gameId: Int) = RetroAchievementsProgress(
    gameId = gameId,
    title = "Game $gameId",
    achievements = listOf(
        RetroAchievementsAchievement(41, "Green Hill Hero", "d", 5, unlocked = true, unlockedHardcore = false, badgeName = "00041", earnedAt = "2023-05-01 10:00:00"),
        RetroAchievementsAchievement(42, "Locked One", "d", 5, unlocked = false, unlockedHardcore = false, badgeName = "00042"),
    ),
)

/**
 * The Bottom's hub on the REAL shared detail cache and loader (a fake clock and fetch): badges that are on screen never fall back to "Loading badges…". Other games seed the
 * cache (Main's probes), the clock passes five minutes, another game asks, and the cache is flooded past its bound; the shown card's game is only ever stale, never gone, because the
 * card holds it for as long as it is composed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MimirBottomDetailStaysShownRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private var now = 1_000_000L
    private val book = RetroAchievementsLaneBook()
    private val owner = Any()
    private val fetched = mutableListOf<Int>()
    private lateinit var loader: ActivityCardDetailLoader

    @Before
    fun setUp() {
        CompanionActivityCardDetails.resetForTest()
        loader = ActivityCardDetailLoader(
            scope = CoroutineScope(Dispatchers.Unconfined),
            book = book,
            account = { "me" },
            fetch = { id ->
                fetched += id
                RaProgressOutcome.Read(staysProgress(id))
            },
            sink = CompanionActivityCardDetails.sink,
            clock = { now },
            workContext = EmptyCoroutineContext,
            publishContext = EmptyCoroutineContext,
        )
        CompanionActivityCardDetails.install(owner, loader)
    }

    @After
    fun tearDown() {
        CompanionActivityCardDetails.resetForTest()
    }

    private fun mount() {
        val shown = CompanionActivityAchievementSnapshot(
            gameId = "g1",
            account = "me",
            unlocked = 1,
            total = 2,
            gameTitle = "Game 1",
            rowsKnown = false,
            raGameId = 1,
        )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(369f / 160f, 1.0f)) {
                MimirTheme {
                    Box(Modifier.requiredSize(538.dp, 468.dp)) {
                        // The hub's own defaults: the shared cache, its request and its hold.
                        CompanionActivityAchievementHub(
                            snapshots = listOf(shown),
                            unknownState = MimirAchievementCardState.Unknown(MimirAchievementCopy.NoAnswerYet),
                            navigationEnabled = true,
                            onBack = {},
                            badgeSource = NoActivityBadgeArt,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun texts(): List<String> {
        val out = ArrayList<String>()
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
            node.children.forEach(::walk)
        }
        compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach(::walk)
        return out
    }

    private fun badgesShown(): Boolean =
        compose.onAllNodesWithTag(achievementPanelBadgeTag(41), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `a shown card keeps its badges while other games seed, five minutes pass, another game asks and the cache is flooded`() {
        mount()
        assertTrue("the card's badges are up: ${texts()}", badgesShown())
        assertEquals("the card asked for its game once", listOf(1), fetched)

        // B, C and D seed (Main's card probes), five minutes and a minute pass, and another game asks.
        listOf(2, 3, 4).forEach { loader.seed(staysProgress(it), book.accountEpoch()) }
        now += ACTIVITY_CARD_DETAIL_REFRESH_MS + 60_000L
        loader.request(5)
        compose.waitForIdle()
        assertTrue("its badges are still up: ${texts()}", badgesShown())
        assertFalse("and never back to Loading: ${texts()}", "Loading badges…" in texts())
        assertTrue(loader.isStale(1))
        assertTrue("stale, not gone", CompanionActivityCardDetails.state.value[1] is ActivityCardDetail.Loaded)

        // Flood the cache far past its bound: the held game stays, an unheld one goes.
        (10..40).forEach { loader.seed(staysProgress(it), book.accountEpoch()) }
        compose.waitForIdle()
        assertTrue("still up after the flood: ${texts()}", badgesShown())
        assertFalse("and still not Loading: ${texts()}", "Loading badges…" in texts())
        assertTrue(CompanionActivityCardDetails.state.value[1] is ActivityCardDetail.Loaded)
        assertFalse("a game nobody holds went", CompanionActivityCardDetails.state.value.containsKey(2))
        assertEquals("the flood asked for nothing (only the shown card and the one other ask did)", listOf(1, 5), fetched)
    }
}
