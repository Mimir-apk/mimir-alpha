package com.gruning.mimir.rewrite

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.DailyLaunchCount
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.model.library.launchesPerLocalDay
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Activity page's data restarts at null on every entry (the scene is mounted afresh, audit RC-36, B-61/B-62c/B-62d/B-65a), and the page used to fold that null into an
 * empty list: "Nothing here yet" and "Nothing launched yet" printed until the library first answered, and then cut to the tiles. Null is now "not read yet": the tiles stand
 * empty at their settled height and say nothing, and only an EMPTY answer (the library really has nothing) prints the copy. Every stepped frame is read, so a copy that shows
 * for a single frame fails.
 *
 * The empty-list cases below are the control: they are what the old fold produced for a null, and they DO print the copy, so the harness sees the defect it is guarding.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActivityPendingDataTest {

    @get:Rule
    val compose = createComposeRule()

    private val density369 = 369f / 160f
    private val hostWidthDp = 1240f / density369
    private val hostHeightDp = 1080f / density369

    private val zone = ZoneId.of("UTC")
    private val now = Instant.parse("2026-10-02T12:00:00Z").toEpochMilli()
    private val day = 24L * 60L * 60L * 1000L

    private fun game(id: String, title: String, plays: Int, lastDaysAgo: Long) = GameRow(
        gameId = GameId(id),
        title = title,
        platformId = PlatformId("genesis"),
        memberCount = 1,
        duplicateKind = null,
        playCount = plays,
        lastPlayedMillis = now - lastDaysAgo * day,
        firstPlayedMillis = now - (lastDaysAgo + 50) * day,
        artResolutionSettled = true,
    )

    private val launchedGames = listOf(
        game("g1", "Sonic the Hedgehog 2", plays = 14, lastDaysAgo = 1),
        game("g2", "Streets of Rage 2", plays = 22, lastDaysAgo = 3),
        game("g3", "Phantasy Star IV", plays = 9, lastDaysAgo = 12),
        game("g4", "Shining Force", plays = 5, lastDaysAgo = 40),
        game("g5", "Gunstar Heroes", plays = 3, lastDaysAgo = 60),
    )
    private val shelfGames = listOf(game("s1", "Alex Kidd", plays = 0, lastDaysAgo = 0), game("s2", "Columns", plays = 0, lastDaysAgo = 0))

    private val weekCounts: List<DailyLaunchCount> = launchesPerLocalDay(listOf(now - day, now - 3600_000L), now, zone)

    private var launched by mutableStateOf<List<GameRow>?>(null)
    private var pool by mutableStateOf<List<GameRow>?>(null)

    private fun write(block: () -> Unit) = Snapshot.withMutableSnapshot(block)

    private fun step(ms: Long = 16L) {
        compose.mainClock.advanceTimeBy(ms)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    private fun mount() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density369, 1f)) {
                MimirTheme {
                    Box(Modifier.requiredSize(hostWidthDp.dp, hostHeightDp.dp)) {
                        ActivityPage(
                            data = ActivityBentoData(
                                gameCount = null,
                                launched = launched,
                                shelfPool = pool,
                                week = weekCounts,
                                liveGameId = null,
                                liveTitle = null,
                                liveRow = null,
                                nowMillis = now,
                                zone = zone,
                            ),
                            navigationEnabled = true,
                            mediaTile = null,
                            achievementSnapshots = emptyList(),
                            achievementsAccessEnabled = false,
                            onOpenAchievements = {},
                            onOpenGame = {},
                            onOpenLiveGame = null,
                            trailingReserve = 0.dp,
                        )
                    }
                }
            }
        }
        step(0L)
    }

    private fun count(text: String) = compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun emptyCopy() = count("Nothing here yet") + count("Nothing launched yet")

    private fun hasTag(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun tileHeight(tag: String): Float =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().first().size.height / density369

    @Test
    fun `control - an EMPTY answer prints the copy, which is what a null used to be folded into`() {
        write { launched = emptyList(); pool = emptyList() }
        mount()
        assertEquals("Been a while twice, in both tiles of the left column", 2, count("Nothing here yet"))
        assertEquals("Most launched", 1, count("Nothing launched yet"))
    }

    @Test
    fun `nothing read yet draws no empty copy and no sort chip, in any frame`() {
        write { launched = null; pool = null }
        mount()
        repeat(30) {
            assertEquals("no empty copy in frame $it", 0, emptyCopy())
            assertFalse("no sort chip in frame $it", hasTag(ActivitySortChipTag))
            step()
        }
        assertTrue("the tiles stand as shells", hasTag(ActivityTileLastPlayedTag) && hasTag(ActivityTileLowerLeftTag) && hasTag(ActivityTileRankedTag))
    }

    @Test
    fun `the answer lands without the copy ever showing, and Most launched does not change height when its rows arrive`() {
        write { launched = null; pool = null }
        mount()
        step(100L)
        val pendingHeight = tileHeight(ActivityTileRankedTag)
        write { launched = launchedGames; pool = emptyList() }
        repeat(12) {
            assertEquals("no empty copy in frame $it of the landing", 0, emptyCopy())
            step()
        }
        assertTrue("the rows arrived", hasTag(ActivitySortChipTag))
        assertEquals("Most launched holds its height", pendingHeight, tileHeight(ActivityTileRankedTag), 1f)
    }

    @Test
    fun `an empty library answers honestly, but the pool still being read says nothing for Been a while`() {
        // The launched list has answered (nothing was ever launched) and the shelf pool has not: Most launched may say so, Been a while may not yet.
        write { launched = emptyList(); pool = null }
        mount()
        repeat(10) {
            assertEquals("Been a while stays blank while its pool is read (frame $it)", 0, count("Nothing here yet"))
            assertEquals("Most launched has its answer", 1, count("Nothing launched yet"))
            step()
        }
        write { pool = shelfGames }
        repeat(10) {
            assertEquals("and the shelf game cuts in with no copy before it (frame $it)", 0, count("Nothing here yet"))
            step()
        }
        assertTrue(
            "a shelf game is on the page",
            listOf("Alex Kidd", "Columns").any { compose.onAllNodesWithText(it, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() },
        )
    }
}
