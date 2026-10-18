package com.gruning.mimir.rewrite

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.ui.MimirAchievementCardState
import com.gruning.mimir.rewrite.core.ui.MimirAchievementCopy
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import java.io.File
import java.time.ZoneOffset
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val RecentNow = 1_700_000_000_000L

private fun recentUnlock(
    id: Int,
    game: Int,
    atMillis: Long? = RecentNow - id * 1_000L,
    hardcore: Boolean = false,
) = ActivityRecentUnlock(
    achievementId = id,
    title = "Badge $id",
    description = "Do thing $id",
    points = 10,
    badgePath = "/Badge/%05d.png".format(id),
    raGameId = 100 + game,
    gameTitle = "Game $game",
    awardedAtMillis = atMillis,
    hardcore = hardcore,
)

/** The strip's rules without a screen: what it shows, how it reads aloud, how an unlock's detail line is written. */
class CompanionActivityRecentStripTest {

    @Test
    fun `the strip shows at most twelve, newest first, and only unlocks whose game has a card`() {
        val unlocks = (1..30).map { recentUnlock(it, game = if (it % 5 == 0) 9 else 1) }
        val shown = activityRecentShown(unlocks, cardGameIds = setOf(101, 102))
        assertEquals(ACTIVITY_RECENT_STRIP_MAX, shown.size)
        assertEquals(12, ACTIVITY_RECENT_STRIP_MAX)
        assertEquals("the order is the lane's, and game 9 has no card", listOf(1, 2, 3, 4, 6, 7, 8, 9, 11, 12, 13, 14), shown.map { it.achievementId })
        assertTrue(activityRecentShown(unlocks, cardGameIds = emptySet()).isEmpty())
        assertTrue(activityRecentShown(emptyList(), cardGameIds = setOf(101)).isEmpty())
        assertEquals(3, activityRecentShown(unlocks.take(3), cardGameIds = setOf(101)).size)
    }

    @Test
    fun `a tile reads aloud as the unlock and its game`() {
        assertEquals("Recently unlocked: Badge 4, Game 2", activityRecentLabel(recentUnlock(4, game = 2)))
    }

    @Test
    fun `the detail line names the points, hardcore, and the date RA gave, in the viewer's zone`() {
        val softcore = activityRecentDetail(recentUnlock(3, game = 1, atMillis = 1_700_000_000_000L), ZoneOffset.UTC)
        assertEquals("Badge 3", softcore.title)
        assertEquals("Do thing 3", softcore.description)
        assertEquals("10 points · Earned 2023-11-14 22:13:20", softcore.meta)
        assertEquals("10 points · Earned in hardcore 2023-11-14 22:13:20", activityRecentDetail(recentUnlock(3, game = 1, atMillis = 1_700_000_000_000L, hardcore = true), ZoneOffset.UTC).meta)
        assertEquals("a missing date is left out, not guessed", "10 points · Earned", activityRecentDetail(recentUnlock(3, game = 1, atMillis = null)).meta)
    }

    @Test
    fun `the strip's sources keep the repo's focus and colour rules`() {
        val names = listOf("CompanionActivityRecentStrip.kt", "CompanionActivityAchievementPanel.kt", "CompanionActivityAchievementHub.kt")
        names.forEach { name ->
            val text = (File("src/main/kotlin/com/gruning/mimir/rewrite/$name").takeIf { it.isFile }
                ?: File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name")).readText()
            assertFalse("$name never uses Compose focus", text.contains("focusable()") || text.contains("focusTarget()"))
            assertFalse("$name has no hex colour literal", Regex("Color\\(0x[0-9A-Fa-f]+\\)").containsMatchIn(text))
        }
    }
}

/**
 * The strip on the REAL [CompanionActivityAchievementHub], at the Bottom's 537.7 x 468.3 dp at 369 dpi: hidden when empty (and taking no room), clamped to twelve, the
 * a11y label, the tile size, a tap that jumps the pager to the game's card and opens that badge's details (also when a filter hides the card, which it clears
 * first), a single game's strip, and the card still whole at font 1.3 with the strip on the page. Pixel values are the JVM's; the pixels on a handheld are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionActivityRecentStripRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val density369 = 369f / 160f
    private val hostWidthDp = 1240f / density369
    private val hostHeightDp = 1080f / density369
    private val day = 24L * 60L * 60L * 1000L

    // Default order (recent activity): Game 1 ... Game 6, so game n is page n.
    private fun game(n: Int) = CompanionActivityAchievementSnapshot(
        gameId = "g$n",
        account = "me",
        unlocked = n,
        total = 40,
        gameTitle = "Game $n",
        checkedAtEpochMillis = 1L,
        rowsKnown = false,
        raGameId = 100 + n,
        consoleName = if (n % 2 == 0) "SNES" else "Genesis",
        lastAwardedAtMillis = RecentNow - n * day,
    )

    private val games = (1..6).map(::game)
    private val defaultUnknown: MimirAchievementCardState = MimirAchievementCardState.Unknown(MimirAchievementCopy.NoAnswerYet)
    private val requested = ArrayList<Int>()

    private fun mount(fontScale: Float, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density369, fontScale)) {
                MimirTheme { Box(Modifier.requiredSize(hostWidthDp.dp, hostHeightDp.dp)) { content() } }
            }
        }
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
    }

    private fun hub(
        holder: ActivityAchievementFilterHolder,
        recent: List<ActivityRecentUnlock>,
        snapshots: List<CompanionActivityAchievementSnapshot> = games,
    ): @Composable () -> Unit = {
        CompanionActivityAchievementHub(
            snapshots = snapshots,
            unknownState = defaultUnknown,
            navigationEnabled = true,
            onBack = {},
            details = MutableStateFlow<Map<Int, ActivityCardDetail>>(emptyMap()),
            requestDetail = { requested += it },
            filterHolder = holder,
            nowMillis = { RecentNow },
            recent = recent,
        )
    }

    private fun advance(ms: Long = 300L) {
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(ms)
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        advance()
    }

    private fun exists(tag: String): Boolean = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun rect(tag: String): Rect = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot

    private fun desc(tag: String): String? =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
            ?.config?.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()

    private fun textOf(tag: String): String? =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
            ?.config?.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text

    private fun walkAll(visit: (SemanticsNode) -> Unit) {
        fun walk(node: SemanticsNode) {
            visit(node)
            node.children.forEach(::walk)
        }
        compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach(::walk)
    }

    private fun texts(): List<String> = ArrayList<String>().also { out ->
        walkAll { node -> node.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text } }
    }

    /** The recent tiles in the tree, in tree order (every tile is composed, scrolled into view or not). */
    private fun tileTags(): List<String> = ArrayList<String>().also { out ->
        walkAll { node ->
            val tag = node.config.getOrNull(SemanticsProperties.TestTag)
            if (tag != null && tag.startsWith("achievement-recent-") && tag != AchievementRecentStripTag) out += tag
        }
    }

    private fun runes(): String? = desc(AchievementHubRunesTag)

    private fun dp(px: Float) = px / density369

    // ── hidden, clamped, labelled, sized ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `with no recent unlocks the strip is hidden and the header is as it was`() {
        mount(1.0f, hub(ActivityAchievementFilterHolder(), recent = emptyList()))
        assertFalse(exists(AchievementRecentStripTag))
        assertTrue(tileTags().isEmpty())
        assertTrue(exists(AchievementFilterCountTag))
        assertEquals("6 games", textOf(AchievementFilterCountTag))
    }

    @Test
    fun `only unlocks of a game with a card are shown, so a strip never holds a tile that opens nothing`() {
        mount(1.0f, hub(ActivityAchievementFilterHolder(), recent = listOf(recentUnlock(1, game = 9), recentUnlock(2, game = 9))))
        assertFalse("every unlock is of a game the page does not have", exists(AchievementRecentStripTag))
    }

    @Test
    fun `fifteen unlocks show twelve tiles, newest first, each reading as the unlock and its game`() {
        val unlocks = (1..15).map { recentUnlock(it, game = (it % 6) + 1) }
        mount(1.0f, hub(ActivityAchievementFilterHolder(), recent = unlocks))
        assertTrue(exists(AchievementRecentStripTag))
        assertEquals((1..12).map(::achievementRecentTag), tileTags())
        assertFalse(exists(achievementRecentTag(13)))
        assertEquals("Recently unlocked: Badge 1, Game 2", desc(achievementRecentTag(1)))
        assertEquals("Recently unlocked: Badge 12, Game 1", desc(achievementRecentTag(12)))
    }

    @Test
    fun `a tile is at least 48 dp across and the strip sits between the chips and the card`() {
        mount(1.0f, hub(ActivityAchievementFilterHolder(), recent = (1..3).map { recentUnlock(it, game = 1) }))
        val tile = rect(achievementRecentTag(1))
        println("RECENT-STRIP font=1.0 tile=${dp(tile.left)},${dp(tile.top)} ${dp(tile.width)}x${dp(tile.height)} dp")
        assertTrue("48 dp wide: ${dp(tile.width)}", dp(tile.width) >= 47.9f)
        assertTrue("48 dp tall: ${dp(tile.height)}", dp(tile.height) >= 47.9f)
        assertEquals("a whole-number scale of the 64 px art, so it stays crisp", 0f, (tile.width % 64f), 0.6f)
        assertTrue("below the chips: ${tile.top} vs ${rect(AchievementFilterConsoleTag).bottom}", tile.top >= rect(AchievementFilterConsoleTag).bottom - 0.5f)
        assertTrue("above the card: ${tile.bottom} vs ${rect(AchievementHubCardTag).top}", tile.bottom <= rect(AchievementHubCardTag).top + 0.5f)
        assertTrue(tile.right <= 1240f + 0.5f && tile.left >= -0.5f)
        // The count keeps its text node and its words.
        assertEquals("6 games", textOf(AchievementFilterCountTag))
    }

    @Test
    fun `at font 1_3 the card is still whole with the strip on the page`() {
        mount(1.3f, hub(ActivityAchievementFilterHolder(), recent = (1..12).map { recentUnlock(it, game = 1) }))
        val card = rect(AchievementHubCardTag)
        val detail = rect(AchievementPanelDetailTag)
        val progress = rect(AchievementPanelProgressTag)
        println("RECENT-STRIP font=1.3 card=${dp(card.top)}..${dp(card.bottom)} progress=${dp(progress.top)}..${dp(progress.bottom)} detail=${dp(detail.top)}..${dp(detail.bottom)} dp")
        assertTrue("the detail area keeps room: ${dp(detail.height)} dp", detail.height > 0f)
        assertTrue("the detail area ends inside the card: ${detail.bottom} vs ${card.bottom}", detail.bottom <= card.bottom + 0.5f)
        assertTrue("the card ends on the screen", card.bottom <= 1080f + 0.5f)
        assertTrue("the strip is above the card", rect(AchievementRecentStripTag).bottom <= card.top + 0.5f)
    }

    // ── the jump ────────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a tap on a tile jumps the pager to that game's card and opens that badge's details`() {
        val unlock = recentUnlock(31, game = 3)
        mount(1.0f, hub(ActivityAchievementFilterHolder(), recent = listOf(recentUnlock(30, game = 1), unlock)))
        assertEquals("Game 1 of 6", runes())

        tap(achievementRecentTag(31))

        assertEquals("Game 3 of 6", runes())
        val expected = activityRecentDetail(unlock)
        assertTrue("the badge's title is shown: ${texts()}", expected.title in texts())
        assertTrue("and its points and date", expected.meta in texts())
        advance(ACTIVITY_CARD_ASK_DEBOUNCE_MS + 50L)
        assertTrue("the card asked for its own detail, as any composed card does", 103 in requested)
    }

    @Test
    fun `a tile for the card already on the page opens its badge without moving the pager`() {
        val unlock = recentUnlock(40, game = 1)
        mount(1.0f, hub(ActivityAchievementFilterHolder(), recent = listOf(unlock)))
        tap(achievementRecentTag(40))
        assertEquals("Game 1 of 6", runes())
        assertTrue(activityRecentDetail(unlock).meta in texts())
    }

    @Test
    fun `a filter that hides the card is cleared, and the pager lands on the card`() {
        val holder = ActivityAchievementFilterHolder()
        holder.setQuery("Game 1")
        val unlock = recentUnlock(51, game = 4)
        mount(1.0f, hub(holder, recent = listOf(unlock)))
        assertEquals("the query leaves one game", "1 of 6 games", textOf(AchievementFilterCountTag))

        tap(achievementRecentTag(51))

        assertEquals("the filter is cleared", "", holder.state.value.query)
        assertEquals("6 games", textOf(AchievementFilterCountTag))
        assertEquals("Game 4 of 6", runes())
        assertTrue(activityRecentDetail(unlock).meta in texts())
    }

    @Test
    fun `a status filter that leaves no card at all is cleared and the pager comes back on the card`() {
        val holder = ActivityAchievementFilterHolder()
        holder.setStatus(ActivityAchievementStatus.MASTERED)
        val unlock = recentUnlock(61, game = 2)
        mount(1.0f, hub(holder, recent = listOf(unlock)))
        assertTrue("nothing is mastered, so the page says so", exists(AchievementFilterEmptyTag))

        tap(achievementRecentTag(61))

        assertEquals(ActivityAchievementStatus.ALL, holder.state.value.status)
        assertFalse(exists(AchievementFilterEmptyTag))
        assertEquals("Game 2 of 6", runes())
        assertTrue(activityRecentDetail(unlock).meta in texts())
    }

    @Test
    fun `a single game still gets its strip, in a row of its own, and the tap opens the badge`() {
        val unlock = recentUnlock(71, game = 1)
        mount(1.0f, hub(ActivityAchievementFilterHolder(), recent = listOf(unlock, recentUnlock(72, game = 3)), snapshots = listOf(game(1))))
        assertFalse("one game has no header", exists(AchievementFilterHeaderTag))
        assertTrue(exists(AchievementRecentStripTag))
        assertEquals("the other game has no card here", listOf(achievementRecentTag(71)), tileTags())
        assertTrue("above the card", rect(achievementRecentTag(71)).bottom <= rect(AchievementHubCardTag).top + 0.5f)

        tap(achievementRecentTag(71))
        assertTrue(activityRecentDetail(unlock).meta in texts())
    }
}
