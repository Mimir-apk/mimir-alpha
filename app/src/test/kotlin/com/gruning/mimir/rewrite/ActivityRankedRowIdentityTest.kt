package com.gruning.mimir.rewrite

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.model.library.launchesPerLocalDay
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import java.io.File
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
 * Ledger D10: the Activity page's ranked list (Most launched) is keyed by each game's content identity, so a re-rank moves a row WITH its game.
 *
 * A row holds its own state (its press source and press response, and a tap in flight on its gesture node). Unkeyed, or keyed by the row's place,
 * that state belongs to the slot: a re-rank hands the first row's state to whichever game is now first. The composed tests read the one thing a
 * slot owns and a game does not, the row's semantics node id, which is the id of its layout node: it is the same node before and after a re-rank
 * exactly when the row was moved with its game. Three re-ranks are run on one mounted page: a game entering the list while another leaves, every row
 * moving at once, and the sort chip's own re-rank. The pin below holds the production site to a key made of the game's id and never its place.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActivityRankedRowIdentityTest {

    @get:Rule
    val compose = createComposeRule()

    private val density369 = 369f / 160f
    private val hostWidthDp = 1240f / density369
    private val hostHeightDp = 1080f / density369

    private val zone = ZoneId.of("UTC")
    private val now = Instant.parse("2026-10-02T12:00:00Z").toEpochMilli()
    private val day = 24L * 60L * 60L * 1000L

    private fun game(id: String, title: String, plays: Int, lastDaysAgo: Long, firstDaysAgo: Long) = GameRow(
        gameId = GameId(id),
        title = title,
        platformId = PlatformId("genesis"),
        memberCount = 1,
        duplicateKind = null,
        playCount = plays,
        lastPlayedMillis = now - lastDaysAgo * day,
        firstPlayedMillis = now - firstDaysAgo * day,
        artResolutionSettled = true,
    )

    /** Most launched under the default sort: Streets (22), Sonic (14), Phantasy (9); Shining Force (5) and Gunstar (3) are below the three rows. */
    private val launched = listOf(
        game("g1", "Sonic the Hedgehog 2", plays = 14, lastDaysAgo = 1, firstDaysAgo = 200),
        game("g2", "Streets of Rage 2", plays = 22, lastDaysAgo = 3, firstDaysAgo = 150),
        game("g3", "Phantasy Star IV", plays = 9, lastDaysAgo = 12, firstDaysAgo = 90),
        game("g4", "Shining Force", plays = 5, lastDaysAgo = 40, firstDaysAgo = 41),
        game("g5", "Gunstar Heroes", plays = 3, lastDaysAgo = 60, firstDaysAgo = 61),
    )

    private fun data(games: List<GameRow>) = ActivityBentoData(
        gameCount = games.size,
        launched = games,
        shelfPool = emptyList(),
        week = launchesPerLocalDay(listOf(now - day), now, zone),
        liveGameId = null,
        liveTitle = null,
        liveRow = null,
        nowMillis = now,
        zone = zone,
    )

    private val bento = mutableStateOf(data(launched))

    private fun mount() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density369, 1.0f)) {
                MimirTheme {
                    Box(Modifier.requiredSize(hostWidthDp.dp, hostHeightDp.dp)) {
                        ActivityPage(
                            data = bento.value,
                            navigationEnabled = true,
                            mediaTile = null,
                            achievementSnapshots = emptyList(),
                            achievementsAccessEnabled = false,
                            onOpenAchievements = {},
                            onOpenGame = {},
                            onOpenLiveGame = {},
                            trailingReserve = 0.dp,
                        )
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
    }

    /** A paused clock needs the write announced and two frames: one to deliver it, one to recompose. */
    private fun settle() {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
    }

    private fun rerank(games: List<GameRow>) {
        compose.runOnIdle { bento.value = data(games) }
        settle()
    }

    /** A ranked row as the semantics tree has it: the game's title, the row's layout node id, and where it sits. */
    private class Row(val title: String, val nodeId: Int, val top: Float)

    /** The ranked tile's rows, top to bottom. Only the rows inside the ranked tile: Last played wears the same description for its own game. */
    private fun rankedRows(): List<Row> {
        val rows = mutableListOf<Row>()
        fun walk(node: SemanticsNode) {
            if (SemanticsProperties.ContentDescription in node.config) {
                val description = node.config[SemanticsProperties.ContentDescription].joinToString()
                if (description.startsWith("Open ") && description.endsWith(" launch card")) {
                    rows += Row(description.removePrefix("Open ").removeSuffix(" launch card"), node.id, node.positionInRoot.y)
                }
            }
            node.children.forEach(::walk)
        }
        walk(compose.onNodeWithTag(ActivityTileRankedTag).fetchSemanticsNode())
        return rows.sortedBy { it.top }
    }

    private fun titles(rows: List<Row>) = rows.map { it.title }

    @Test
    fun `a game entering the list and another leaving moves the survivors with their games`() {
        mount()
        val before = rankedRows()
        assertEquals(listOf("Streets of Rage 2", "Sonic the Hedgehog 2", "Phantasy Star IV"), titles(before))
        assertEquals("fixture: each row is its own node", 3, before.map { it.nodeId }.toSet().size)
        val nodeOf = before.associate { it.title to it.nodeId }

        // Shining Force overtakes the leaders: it enters at the top, Streets and Sonic each move down a row, Phantasy Star leaves.
        rerank(launched.map { if (it.gameId.value == "g4") it.copy(playCount = 30) else it })
        val after = rankedRows()
        assertEquals(listOf("Shining Force", "Streets of Rage 2", "Sonic the Hedgehog 2"), titles(after))
        assertEquals("Streets of Rage 2 moved from row 1 to row 2 and kept its node", nodeOf.getValue("Streets of Rage 2"), after[1].nodeId)
        assertEquals("Sonic the Hedgehog 2 moved from row 2 to row 3 and kept its node", nodeOf.getValue("Sonic the Hedgehog 2"), after[2].nodeId)
        assertFalse(
            "Shining Force is a new node, not the first row's old one handed on",
            after[0].nodeId in nodeOf.values,
        )
    }

    @Test
    fun `every row moving at once, by new counts and by the sort chip, keeps each row on its own game`() {
        mount()
        val original = rankedRows()
        val nodeOf = original.associate { it.title to it.nodeId }
        assertEquals(listOf("Streets of Rage 2", "Sonic the Hedgehog 2", "Phantasy Star IV"), titles(original))

        // Phantasy Star IV is launched more than anything: all three rows change place.
        rerank(launched.map { if (it.gameId.value == "g3") it.copy(playCount = 40) else it })
        val byCount = rankedRows()
        assertEquals(listOf("Phantasy Star IV", "Streets of Rage 2", "Sonic the Hedgehog 2"), titles(byCount))
        byCount.forEach { assertEquals("${it.title} kept its node across the count re-rank", nodeOf.getValue(it.title), it.nodeId) }

        // The sort chip re-ranks the same three: most recently played first puts Sonic (1 day) ahead of Streets (3) and Phantasy Star (12).
        compose.onNodeWithTag(ActivitySortChipTag).performClick()
        settle()
        compose.onNodeWithContentDescription("Sort by Last played").performClick()
        settle()
        compose.onNodeWithContentDescription("Sort order: Last played").assertExists()
        val bySort = rankedRows()
        assertEquals(listOf("Sonic the Hedgehog 2", "Streets of Rage 2", "Phantasy Star IV"), titles(bySort))
        bySort.forEach { assertEquals("${it.title} kept its node across the sort re-rank", nodeOf.getValue(it.title), it.nodeId) }
    }

    /**
     * The production site: the ranked rows are emitted through `key(game.gameId.value)`, the row's whole body is inside it, and the key reads no
     * position. Brace-matched from the loop's own opening brace, so a key that is deleted, narrowed to part of the row, or rebuilt from the index
     * fails here whether or not a composed test happens to move a row.
     */
    @Test
    fun `the ranked rows are keyed by the game's id and the key reads no position`() {
        val source = File("src/main/kotlin/com/gruning/mimir/rewrite/CompanionActivityBento.kt").readText()
        val header = "ranked.forEachIndexed { index, game ->"
        assertEquals("exactly one ranked loop", 1, Regex(Regex.escape(header)).findAll(source).count())
        val open = source.indexOf(header) + header.indexOf('{')
        var depth = 0
        var end = -1
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) { end = i; break }
            }
        }
        assertTrue("the loop's closing brace was found", end > open)
        val body = source.substring(open + 1, end).substringAfter("->").trim()
        val keyed = "key(game.gameId.value) {"
        assertTrue("the loop's body opens with $keyed, not a row of its own", body.startsWith(keyed))
        val keyBody = body.removePrefix(keyed)
        var keyDepth = 1
        var keyEnd = -1
        for (i in keyBody.indices) {
            when (keyBody[i]) {
                '{' -> keyDepth++
                '}' -> if (--keyDepth == 0) { keyEnd = i; break }
            }
        }
        assertTrue("the key closes the row's whole body: nothing of the row is outside it", keyEnd >= 0 && keyBody.substring(keyEnd + 1).isBlank())
        assertTrue("the row itself is inside the key", keyBody.contains("mimirActivatable(") && keyBody.contains("ActivityGameFace(game"))
        val keyCalls = Regex("""\bkey\(([^)]*)\)""").findAll(body).map { it.groupValues[1] }.toList()
        assertTrue("every key in the row reads the game, never a position: $keyCalls", keyCalls.all { !Regex("""\bindex\b|\w*Index\b""").containsMatchIn(it) })
    }
}
