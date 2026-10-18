package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Walk-32h: what the hide slot decides, without a screen. A hide the shatter cannot play must still say where the game
 * went, once and at once, and must break nothing: reduced motion is a cut with the receipt, and so is every view or card
 * the shatter declines.
 */
class LibraryShatterStageTest {

    private val game = GameRow(
        gameId = GameId("g1"),
        title = "Game 1",
        platformId = PlatformId.GBA,
        memberCount = 1,
        duplicateKind = null,
    )

    private fun eligible(
        enabled: Boolean = true,
        gameCount: Int = 4,
        centreGameId: String? = "g1",
        hasCapture: Boolean = true,
        hasBounds: Boolean = true,
    ) = libraryShatterEligible(enabled, gameCount, centreGameId, "g1", hasCapture, hasBounds)

    @Test
    fun `a hide is broken only when the animators are on, there is a game to close over, and it is the centred card with a picture and a place`() {
        assertTrue(eligible())
        assertFalse("reduced motion cuts", eligible(enabled = false))
        assertFalse("the only game has nothing to close over", eligible(gameCount = 1))
        assertFalse("a card that is not the centre is not the one on screen", eligible(centreGameId = "g2"))
        assertFalse("no centred card at all", eligible(centreGameId = null))
        assertFalse("no picture can be taken", eligible(hasCapture = false))
        assertFalse("no place to break it", eligible(hasBounds = false))
    }

    private fun declinedReceipts(configure: LibraryShatterStage.() -> Unit): Pair<Int, LibraryShatterStage> {
        val stage = LibraryShatterStage().apply(configure)
        var receipts = 0
        runBlocking { stage.play(game, this) { receipts++ } }
        return receipts to stage
    }

    @Test
    fun `reduced motion is a cut, and the receipt still shows, once`() {
        val (receipts, stage) = declinedReceipts {
            enabled = false
            gameCount = 4
            centreGameId = "g1"
        }
        assertEquals(1, receipts)
        assertNull("nothing is broken", stage.job)
    }

    @Test
    fun `a view the shatter cannot play yet, or a card it cannot picture, still says where the game went`() {
        // The list view and the Favorites platform never enable it; a centred card nothing can capture declines too.
        val (listView, _) = declinedReceipts { enabled = false }
        assertEquals(1, listView)
        val (noPicture, stage) = declinedReceipts {
            enabled = true
            gameCount = 4
            centreGameId = "g1"
        }
        assertEquals("no capture, no bounds: declined, receipt at once", 1, noPicture)
        assertNull(stage.job)
        val (notCentre, _) = declinedReceipts {
            enabled = true
            gameCount = 4
            centreGameId = "g2"
        }
        assertEquals(1, notCentre)
    }

    @Test
    fun `an Android package that cannot be broken still says where it went, once and at once`() {
        // The native shelf and Favorites break a package by its name. Reduced motion, a shelf of one, a tile nothing can
        // capture and a tile that is not the centred one all decline, and each still posts the receipt.
        fun receipts(configure: LibraryShatterStage.() -> Unit): Pair<Int, LibraryShatterStage> {
            val stage = LibraryShatterStage().apply(configure)
            var posted = 0
            runBlocking { stage.play("com.example.game", this) { posted++ } }
            return posted to stage
        }
        assertEquals(1, receipts { enabled = false; gameCount = 4; centreGameId = "com.example.game" }.first)
        assertEquals("a shelf of one has nothing to hand focus to", 1, receipts { enabled = true; gameCount = 1; centreGameId = "com.example.game" }.first)
        val (noPicture, stage) = receipts { enabled = true; gameCount = 4; centreGameId = "com.example.game" }
        assertEquals("no capture and no place: declined", 1, noPicture)
        assertNull(stage.job)
        assertEquals("the centred tile is another package", 1, receipts { enabled = true; gameCount = 4; centreGameId = "com.other" }.first)
        assertTrue(
            "the same rule decides a package as a game",
            libraryShatterEligible(true, 4, "com.example.game", "com.example.game", hasCapture = true, hasBounds = true),
        )
    }

    @Test
    fun `a stage with no job shatters no game, so a SELECT un-favourite is a dissolve`() {
        val stage = LibraryShatterStage()
        assertFalse(stage.shatters(game))
    }
}
