package com.gruning.mimir.rewrite

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.metadata.online.RaAwardKind
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsAchievement
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsProgress
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import kotlin.coroutines.EmptyCoroutineContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A set of [count] badges, the first [earned] of them earned, only the first of those in hardcore. */
private fun awardProgress(count: Int, earned: Int) = RetroAchievementsProgress(
    gameId = 77,
    title = "Main Game",
    achievements = (1..count).map { id ->
        RetroAchievementsAchievement(
            id = id,
            title = "Badge $id",
            description = "Do thing $id",
            points = 5,
            unlocked = id <= earned,
            unlockedHardcore = id == 1,
            badgeName = "%05d".format(id),
            earnedAt = if (id <= earned) "2023-05-0${id % 9 + 1} 10:00:00" else null,
            earnedHardcoreAt = if (id == 1) "2023-05-01 12:34:56" else null,
        )
    },
)

/**
 * Update: Main's achievement page names the award RetroAchievements gave the game (Beaten, Beaten (softcore), Completed, Mastered) beside the count, in the Bottom's own
 * words, from the same source: the Activity page's row for the game, which carries RA's award word and its hardcore count (a per-game read carries neither). Pixel values are
 * device-owed; here the mark's words, its absence, and that nothing is cut off at font 1.0 and 2.0.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w832dp-h468dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MimirMainAchievementAwardTest {

    @get:Rule
    val utc = UtcDefaultTimeZoneRule()

    @get:Rule
    val compose = createComposeRule()

    private val authority = DefaultFocusAuthority()

    private fun mount(progress: RetroAchievementsProgress, snapshot: CompanionActivityAchievementSnapshot, font: Float = 1.0f) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2.625f, font)) {
                MimirTheme {
                    Box(Modifier.requiredSize(832.dp, 468.dp), contentAlignment = Alignment.Center) {
                        MimirMainAchievementPage(
                            progress = progress,
                            artSnapshot = snapshot,
                            artResolver = ActivityAchievementArtResolver { ActivityAchievementArtSources(null, null) },
                            badgeSource = ActivityBadgeSource { null },
                            reducedMotion = true,
                            authority = authority,
                            onBack = {},
                            modifier = Modifier.padding(MimirSpacing.xl).widthIn(max = MainAchievementPageMaxWidth).fillMaxSize(),
                            artContext = EmptyCoroutineContext,
                        )
                    }
                }
            }
        }
        repeat(2) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeBy(300L)
            compose.waitForIdle()
        }
    }

    private fun textOf(tag: String): String? =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
            ?.config?.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }

    private fun boundsOf(tag: String): Rect? =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.boundsInRoot

    private fun artFor(progress: RetroAchievementsProgress, awardKind: RaAwardKind?, earnedHardcore: Int = 0) =
        mainAchievementArtSnapshot(progress, "g77").copy(awardKind = awardKind, earnedHardcore = earnedHardcore)

    @Test
    fun `Main names the award RetroAchievements gave the game, in the words the Bottom uses`() {
        val progress = awardProgress(count = 12, earned = 6)

        mount(progress, artFor(progress, RaAwardKind.BEATEN_HARDCORE))
        assertEquals("beaten in hardcore", "Beaten", textOf(AchievementPanelAwardTag))
    }

    @Test
    fun `a softcore beaten award says so`() {
        val progress = awardProgress(count = 12, earned = 6)

        mount(progress, artFor(progress, RaAwardKind.BEATEN_SOFTCORE))
        assertEquals("Beaten (softcore)", textOf(AchievementPanelAwardTag))
    }

    @Test
    fun `a game with no award and unfinished shows no award mark at all`() {
        val progress = awardProgress(count = 12, earned = 6)

        mount(progress, artFor(progress, awardKind = null))
        assertNull("no award, no mark", textOf(AchievementPanelAwardTag))
    }

    @Test
    fun `every achievement earned in hardcore reads Mastered from RA's own hardcore count, which the page's rows alone do not carry`() {
        val progress = awardProgress(count = 12, earned = 12)

        mount(progress, artFor(progress, awardKind = null, earnedHardcore = 12))
        assertEquals("the bulk read's hardcore figure counts, as on the Bottom", "Mastered", textOf(AchievementPanelAwardTag))
        assertEquals("and so does the hardcore mark", "HC 12", textOf(AchievementPanelHardcoreTag))
    }

    private fun row(gameId: String, raGameId: Int?, awardKind: RaAwardKind?, earnedHardcore: Int = 0, account: String = "alice") =
        CompanionActivityAchievementSnapshot(gameId = gameId, account = account, unlocked = 6, total = 12, raGameId = raGameId, earnedHardcore = earnedHardcore, awardKind = awardKind)

    @Test
    fun `the award and the hardcore count come from the signed-in account's row for the open game's own set, under whichever key it sits`() {
        val progress = awardProgress(count = 12, earned = 6)
        val byLibrary = row("g77", 77, RaAwardKind.BEATEN_HARDCORE, earnedHardcore = 5)
        val other = row("g12", 12, RaAwardKind.MASTERED, earnedHardcore = 9)

        assertEquals(byLibrary, mainAchievementActivityRow(listOf(other, byLibrary), "alice", progress.gameId))
        // A game the library could not be matched to sits under RA's own key: the same RA game.
        val byRa = row("ra:77", 77, RaAwardKind.COMPLETED)
        assertEquals(byRa, mainAchievementActivityRow(listOf(other, byRa), "alice", progress.gameId))
        // A card check's row names no award; the bulk read's row for the same RA game does, and is the one used.
        val check = row("g77", 77, awardKind = null)
        assertEquals(byRa, mainAchievementActivityRow(listOf(check, byRa), "alice", progress.gameId))
        assertNull("nothing for this game", mainAchievementActivityRow(listOf(other), "alice", progress.gameId))

        // Another account's row for the same set is never this account's, and nobody signed in is no row.
        assertNull(mainAchievementActivityRow(listOf(row("g77", 77, RaAwardKind.MASTERED, account = "bob")), "alice", progress.gameId))
        assertNull(mainAchievementActivityRow(listOf(byLibrary), null, progress.gameId))
        assertNull(mainAchievementActivityRow(listOf(byLibrary), "  ", progress.gameId))

        val art = mainAchievementArtSnapshot(progress, "g77", byLibrary)
        assertEquals(RaAwardKind.BEATEN_HARDCORE, art.awardKind)
        assertEquals(5, art.earnedHardcore)
        assertEquals("the art is still asked for this game", "g77", art.gameId)
        assertNull(mainAchievementArtSnapshot(progress, "g77").awardKind)
    }

    @Test
    fun `a ROM replaced by another set shows neither the old set's award nor its hardcore count, on Main or on the Bottom`() {
        CompanionActivityAchievements.resetForTest()
        try {
            // The library game X held set A (RA id 1): Mastered, 40 of its 40 earned in hardcore. The file is then replaced by set B (RA id 2): 12 badges, 3 earned, none in hardcore.
            val setA = CompanionActivityAchievementSnapshot(
                gameId = "gX", account = "alice", unlocked = 40, total = 40, gameTitle = "Set A", rowsKnown = false, raGameId = 1, earnedHardcore = 40, awardKind = RaAwardKind.MASTERED,
            )
            CompanionActivityAchievements.publishBulk("alice", listOf(setA), totalPoints = null)
            val setB = awardProgress(count = 12, earned = 3).copy(gameId = 2, title = "Set B").let { progress ->
                progress.copy(achievements = progress.achievements.map { it.copy(unlockedHardcore = false, earnedHardcoreAt = null) })
            }

            // Opening X reads set B and publishes it for X.
            CompanionActivityAchievements.publish("gX", "alice", MimirAchievementLaneState.Loaded(setB))

            val bottom = CompanionActivityAchievements.snapshots.value.single { it.account == "alice" && it.gameId == "gX" }
            assertEquals("the Bottom's row for X is set B", 2, bottom.raGameId)
            assertNull("the Bottom shows no Mastered for set B", bottom.awardKind)
            assertEquals("and no hardcore count of set A's", 0, bottom.earnedHardcore)

            // Main finds no row of set A's for set B, even while the old row is still held.
            assertNull(mainAchievementActivityRow(listOf(setA), "alice", setB.gameId))
            val main = mainAchievementActivityRow(CompanionActivityAchievements.snapshots.value, "alice", setB.gameId)
            assertNull("Main shows no Mastered", main?.awardKind)
            assertEquals("and no HC 40", 0, main?.earnedHardcore ?: 0)
            val art = mainAchievementArtSnapshot(setB, "gX", main)
            assertNull(art.awardKind)
            assertEquals(0, art.earnedHardcore)
        } finally {
            CompanionActivityAchievements.resetForTest()
        }
    }

    @Test
    fun `a game checked at its card keeps the bulk read's award when the set is the same one`() {
        CompanionActivityAchievements.resetForTest()
        try {
            val bulk = CompanionActivityAchievementSnapshot(
                gameId = "gX", account = "alice", unlocked = 3, total = 12, rowsKnown = false, raGameId = 2, earnedHardcore = 0, awardKind = RaAwardKind.BEATEN_SOFTCORE,
            )
            CompanionActivityAchievements.publishBulk("alice", listOf(bulk), totalPoints = null)
            val sameSet = awardProgress(count = 12, earned = 4).copy(gameId = 2, title = "Set B")

            CompanionActivityAchievements.publish("gX", "alice", MimirAchievementLaneState.Loaded(sameSet))

            val held = CompanionActivityAchievements.snapshots.value.single { it.account == "alice" && it.gameId == "gX" }
            assertEquals("the award of the same set is kept", RaAwardKind.BEATEN_SOFTCORE, held.awardKind)
        } finally {
            CompanionActivityAchievements.resetForTest()
        }
    }

    private fun assertMarksWhole(font: Float) {
        val progress = awardProgress(count = 12, earned = 12)
        mount(progress, artFor(progress, RaAwardKind.MASTERED, earnedHardcore = 12), font)
        val page = requireNotNull(boundsOf(MainAchievementPageTag))
        val award = requireNotNull(boundsOf(AchievementPanelAwardTag)) { "the award mark at font $font" }
        val hardcore = requireNotNull(boundsOf(AchievementPanelHardcoreTag)) { "the hardcore mark at font $font" }
        assertTrue("the award mark inside the page at font $font: $award in $page", award.left >= page.left && award.right <= page.right && award.top >= page.top && award.bottom <= page.bottom)
        assertTrue("the marks do not overlap at font $font: $hardcore vs $award", hardcore.right <= award.left || hardcore.bottom <= award.top || award.bottom <= hardcore.top)
    }

    @Test
    fun `the award and the hardcore marks stay whole inside the page at font 1_0`() = assertMarksWhole(1.0f)

    @Test
    fun `the award and the hardcore marks stay whole inside the page at font 2_0`() = assertMarksWhole(2.0f)
}
