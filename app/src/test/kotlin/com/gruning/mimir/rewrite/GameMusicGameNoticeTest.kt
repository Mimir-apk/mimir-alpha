package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.ui.MimirMessageClass
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Download music for this game" answers where it was asked. This is the whole copy table, state to message, with no
 * Android in it: start, the second press, both refusals, and each way a single game's run ends.
 */
class GameMusicGameNoticeTest {
    private val game = "Chrono Trigger"

    private fun notice(press: GameMusicPress, name: String = game) = gameMusicGameNotice(press, name)

    private fun ended(
        written: Int = 0,
        skipped: Int = 0,
        failed: Int = 0,
        alreadyHad: Int = 0,
        stopped: Boolean = false,
    ) = GameMusicPress.Ended(
        GameMusicTally(done = written + skipped + failed, total = 1, written = written, skipped = skipped, failed = failed, alreadyHad = alreadyHad),
        stopped = stopped,
    )

    @Test
    fun `a press that starts says it is finding music, with the game's name`() {
        assertEquals(
            GameMusicNotice("Finding music for Chrono Trigger…", MimirMessageClass.Guidance),
            notice(GameMusicPress.Started),
        )
    }

    @Test
    fun `a second press while that game's run is going says it is still finding music, where it used to say nothing`() {
        assertEquals(
            GameMusicNotice("Still finding music for Chrono Trigger…", MimirMessageClass.Guidance),
            notice(GameMusicPress.Busy(sameGame = true)),
        )
    }

    @Test
    fun `a press while the Settings batch or another game's run holds the gate gets the house line, not a claim about this game`() {
        val busy = notice(GameMusicPress.Busy(sameGame = false))
        assertEquals(GameMusicNotice("Game music is downloading.", MimirMessageClass.Guidance, holdMs = GameMusicRefusalHoldMs), busy)
        assertFalse("it must not claim to be finding music for THIS game", busy!!.text.contains(game))
    }

    @Test
    fun `not connected is refused with why and exactly where to fix it`() {
        val refused = notice(GameMusicPress.Refused(GameMusicBlock.NotConnected))!!
        assertEquals("Connect ScreenScraper to download music.", refused.text)
        assertEquals("Settings › Appearance › Enrichment & artwork", refused.supportingText)
        assertEquals(MimirMessageClass.Warning, refused.messageClass)
    }

    @Test
    fun `a running artwork download is refused with why and when to try again`() {
        assertEquals(
            GameMusicNotice("Artwork is downloading. Try again when it’s done.", MimirMessageClass.Guidance, holdMs = GameMusicRefusalHoldMs),
            notice(GameMusicPress.Refused(GameMusicBlock.EnrichRunning)),
        )
    }

    @Test
    fun `a sound written is saved`() {
        assertEquals(
            GameMusicNotice("Music saved for Chrono Trigger.", MimirMessageClass.Confirmation, holdMs = GameMusicResultHoldMs),
            notice(ended(written = 1)),
        )
    }

    @Test
    fun `a game that already had its sound says so, and is not a miss`() {
        assertEquals(
            GameMusicNotice("Chrono Trigger already has music.", MimirMessageClass.Guidance, holdMs = GameMusicResultHoldMs),
            notice(ended(skipped = 1, alreadyHad = 1)),
        )
    }

    @Test
    fun `every other skip is none found, since the tally cannot tell no record from no clip from no usable audio`() {
        assertEquals(
            GameMusicNotice("No music found for Chrono Trigger.", MimirMessageClass.Guidance, holdMs = GameMusicResultHoldMs),
            notice(ended(skipped = 1)),
        )
    }

    @Test
    fun `a real failure is the failed wording and nothing else is`() {
        assertEquals(
            GameMusicNotice("Couldn’t download music for Chrono Trigger. Try again later.", MimirMessageClass.Warning, holdMs = GameMusicRefusalHoldMs),
            notice(ended(failed = 1)),
        )
        // A skip is never reported as a failure, and a failure is never reported as none found.
        assertFalse(notice(ended(skipped = 1))!!.text.contains("Couldn’t"))
        assertFalse(notice(ended(failed = 1))!!.text.contains("No music found"))
    }

    @Test
    fun `a run the player stopped from Settings says nothing`() {
        assertNull(notice(GameMusicPress.Ended(GameMusicTally(done = 0, total = 1), stopped = true)))
        assertNull(notice(ended(failed = 1, stopped = true)))
    }

    @Test
    fun `a run that counted nothing never reached the provider and reads as the connection refusal, not as a result`() {
        val gateClosed = notice(GameMusicPress.Ended(GameMusicTally(done = 0, total = 1)))!!
        assertEquals("Connect ScreenScraper to download music.", gateClosed.text)
        assertEquals("Settings › Appearance › Enrichment & artwork", gateClosed.supportingText)
    }

    @Test
    fun `written wins over a failure if a run ever reported both`() {
        assertEquals("Music saved for Chrono Trigger.", notice(ended(written = 1, failed = 1))!!.text)
    }

    @Test
    fun `the name is kept on one line, cut with an ellipsis when long, and never blank`() {
        assertEquals("this game", gameMusicDisplayName("   "))
        assertEquals("Super Mario World", gameMusicDisplayName("  Super\n Mario   World "))
        val long = "The Legend of Zelda: A Link to the Past and Four Swords"
        val cut = gameMusicDisplayName(long)
        assertEquals("cut on a word's edge, no space left before the ellipsis", "The Legend of Zelda: A Link to the…", cut)
        assertTrue(cut.length <= GameMusicNameMaxChars)
        assertEquals("Finding music for this game…", notice(GameMusicPress.Started, "")!!.text)
        assertEquals("Music saved for The Legend of Zelda: A Link to the…", notice(ended(written = 1), long)!!.text)
    }

    @Test
    fun `a name that ends in punctuation is not given a second mark`() {
        val long = "The Legend of Zelda: A Link to the Past and Four Swords"
        assertEquals("Finding music for The Legend of Zelda: A Link to the…", notice(GameMusicPress.Started, long)!!.text)
        assertEquals("Music saved for The Legend of Zelda: A Link to the…", notice(ended(written = 1), long)!!.text)
        assertEquals(
            "Couldn’t download music for The Legend of Zelda: A Link to the… Try again later.",
            notice(ended(failed = 1), long)!!.text,
        )
        assertEquals("Music saved for Wario Land II!", notice(ended(written = 1), "Wario Land II!")!!.text)
        assertEquals("No music found for Mr. Driller.", notice(ended(skipped = 1), "Mr. Driller")!!.text)
    }

    @Test
    fun `no message is blank, and none ends in a stray double period`() {
        val all = listOf(
            GameMusicPress.Started,
            GameMusicPress.Busy(true),
            GameMusicPress.Busy(false),
            GameMusicPress.Refused(GameMusicBlock.NotConnected),
            GameMusicPress.Refused(GameMusicBlock.EnrichRunning),
            ended(written = 1),
            ended(skipped = 1, alreadyHad = 1),
            ended(skipped = 1),
            ended(failed = 1),
        )
        all.forEach { press ->
            val message = notice(press)
            assertNotNull(press.toString(), message)
            assertTrue(message!!.text.isNotBlank())
            assertFalse(message.text.contains(".."))
        }
    }

    // ── How long each note stays (the sweep saw it gone in about 1.5 s) ─────────────────────────────────────────────

    @Test
    fun `no note is shorter than 4 s, outcomes outlast the progress note, and refusals and failures outlast outcomes`() {
        val progress = listOf(notice(GameMusicPress.Started), notice(GameMusicPress.Busy(sameGame = true)))
        val outcomes = listOf(ended(written = 1), ended(skipped = 1, alreadyHad = 1), ended(skipped = 1)).map { notice(it) }
        val refusals = listOf(
            notice(GameMusicPress.Busy(sameGame = false)),
            notice(GameMusicPress.Refused(GameMusicBlock.NotConnected)),
            notice(GameMusicPress.Refused(GameMusicBlock.EnrichRunning)),
            notice(ended(failed = 1)),
            notice(GameMusicPress.Ended(GameMusicTally(done = 0, total = 1))),
        )
        (progress + outcomes + refusals).forEach { assertTrue("a note stays at least 4 s: $it", it!!.holdMs >= 4_000L) }
        progress.forEach { assertEquals(GameMusicProgressHoldMs, it!!.holdMs) }
        outcomes.forEach { assertEquals(GameMusicResultHoldMs, it!!.holdMs) }
        refusals.forEach { assertEquals(GameMusicRefusalHoldMs, it!!.holdMs) }
        assertTrue(GameMusicProgressHoldMs < GameMusicResultHoldMs && GameMusicResultHoldMs < GameMusicRefusalHoldMs)
        assertEquals("minimum game-music progress display interval", 4_000L, GameMusicProgressHoldMs)
    }

    @Test
    fun `the whole settings path is one supporting line, and the sentence above it never carries half of it`() {
        val refused = notice(GameMusicPress.Refused(GameMusicBlock.NotConnected))!!
        val path = refused.supportingText!!
        assertEquals("Settings › Appearance › Enrichment & artwork", path)
        assertFalse("the path is a single line of text", path.contains("\n"))
        assertFalse("the sentence does not name Settings: its path does, whole", refused.text.contains("Settings"))
        assertEquals(path, GameMusicConnectWhereCopy)
    }

    // ── The wiring, as source: the menu's one action, and the Settings batch untouched ───────────────────────────

    private fun source(name: String): String = listOf(
        File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
    ).firstOrNull(File::isFile)?.readText() ?: throw AssertionError("$name not reachable from the test working directory")

    @Test
    fun `the menu action hands every notice to Main's rail through showMimirMessage, for Home Edit and the Library hero menu alike`() {
        val coordinator = source("MimirAppCoordinator.kt")
        val action = coordinator.substringAfter("onDownloadGameMusic = {").substringBefore("\n            )\n")
        assertTrue(action.contains("artwork.state.downloadGameMusicFromMenu("))
        assertTrue(action.contains("showMimirMessage(core.context, notice.text, notice.messageClass, notice.supportingText, holdMs = notice.holdMs)"))
        // Both menus carry their game here: the Library hero menu's own row, Home Edit's row id.
        assertTrue(action.contains("current.sourceGame?.gameId"))
        assertTrue(action.contains("HomeShelfItemId.Emulated"))
        assertTrue("the display name is the game's own title", action.contains("current.sourceGame?.title ?: current.row.title"))
        // The old silent path and its second, differently worded refusal are gone: the state machine refuses and says why.
        assertFalse(action.contains("gameMusicEntryMayStart"))
        assertFalse(action.contains("ArtworkDownloadingCopy"))
        // Only the menu action is wired this way; the Library has no second route to the per-game download.
        assertEquals(1, Regex("""downloadGameMusicFromMenu\(""").findAll(coordinator).count())
        assertEquals(1, Regex("""downloadMusicForGame\(""").findAll(source("GameMusicGameFeedback.kt")).count())
    }

    @Test
    fun `Main's host stands the rail above the dock on Home and the Library, grows the band, and leaves every other screen at its old seat`() {
        val host = source("MimirAppHost.kt")
        assertTrue(
            host.contains("MimirActivityMessageHost(clearsDock = mainMessageRailClearsDock(bindings.navigation.destination, bindings.core.libraryViewModel.openPlatform.collectAsState().value))"),
        )
        val messages = source("MimirDisplayMessages.kt")
        assertTrue("the dock's top edge is the one shared number", messages.contains("homeShelfQuickRailClearanceDp(heightDp)"))
        assertTrue("Main's rail may grow", messages.contains("growsToFit = true"))
        assertTrue("every other screen keeps its seat", messages.contains("else MimirSpacing.xl"))
        // The shared rail grows only where a host asks: Home's own lane, the Library's and the brightness overlay's keep the fixed band.
        val rail = File("core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirHintToastRail.kt")
            .takeIf(File::isFile)?.readText()
            ?: File("../core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirHintToastRail.kt").readText()
        assertTrue(rail.contains("growsToFit: Boolean = false"))
        assertTrue(rail.contains("if (growsToFit) Modifier.heightIn(min = railHeight) else Modifier.height(railHeight)"))
    }

    @Test
    fun `the rail stands above the dock on Home and the Library shelf only, and keeps its old seat everywhere else`() {
        assertTrue(mainMessageRailClearsDock(MimirDestination.HOME, null))
        assertTrue(mainMessageRailClearsDock(MimirDestination.LIBRARY, null))
        assertFalse("an open gamelist has no dock", mainMessageRailClearsDock(MimirDestination.LIBRARY, com.gruning.mimir.rewrite.core.model.PlatformId("snes")))
        assertFalse(mainMessageRailClearsDock(MimirDestination.SETTINGS, null))
        assertFalse(mainMessageRailClearsDock(MimirDestination.BROWSE, null))
        assertEquals(8f, MimirMainMessageRailDockGap.value, 0f)
    }

    @Test
    fun `the Settings batch is started with no report, so it says nothing new and its states are unchanged`() {
        val artwork = source("MimirArtworkCoordinator.kt")
        assertTrue(artwork.contains("claimAndLaunchGameMusic(runGameId = null, report = null) { onProgress -> runAll(context, onProgress) }"))
        assertEquals(
            "the Settings Start row still calls the batch entry with no arguments",
            1,
            Regex("""artwork\.state\.startGameMusicDownload\(\)""").findAll(source("MimirSettingsCoordinator.kt")).count(),
        )
    }
}
