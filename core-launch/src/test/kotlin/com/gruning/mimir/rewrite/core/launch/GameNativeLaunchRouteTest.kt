package com.gruning.mimir.rewrite.core.launch

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage for the GameNative launch route: the extension -> `GameSource` mapping and the
 * shortcut body -> `app_id` parse, plus the end-to-end file read. Deliberately plain JVM (real
 * [java.io.File]s, no Android) so the shortcut failure mode -- a malformed shortcut must be a
 * STATED failure, never a silent no-op -- is provable here rather than only in an unrunnable
 * instrumented test.
 */
class GameNativeLaunchRouteTest {

    @Test
    fun isTargetMatchesOnlyTheGameNativePackage() {
        assertTrue(GameNativeLaunchRoute.isTarget("app.gamenative"))
        assertEquals(false, GameNativeLaunchRoute.isTarget("app.gamenative.gold"))
        assertEquals(false, GameNativeLaunchRoute.isTarget("org.dolphinemu.dolphinemu"))
    }

    // -- extension -> GameSource -------------------------------------------------------------------

    @Test
    fun everyContractExtensionMapsToItsExactGameSourceConstant() {
        assertEquals("STEAM", GameNativeLaunchRoute.gameSourceForExtension("steam"))
        assertEquals("EPIC", GameNativeLaunchRoute.gameSourceForExtension("epic"))
        assertEquals("GOG", GameNativeLaunchRoute.gameSourceForExtension("gog"))
        assertEquals("AMAZON", GameNativeLaunchRoute.gameSourceForExtension("amazon"))
    }

    /**
     * Failure-mode coverage: the dex carries `FRONTEND_SYNC_DIR_CUSTOM` for the SYNC
     * DIRECTORY, which is what makes "CUSTOM" look right -- but the extra rides `Enum.name()`, and
     * GameNative's actual enum constant is `CUSTOM_GAME`. Getting this wrong fails SILENTLY on
     * GameNative's side (parseLaunchIntent logs and returns null), so this is a whole-expression pin,
     * not a `contains`/`startsWith` check that a typo could still slip through.
     */
    @Test
    fun pcgameExtensionMapsToCustomGameNotCustom() {
        assertEquals("CUSTOM_GAME", GameNativeLaunchRoute.gameSourceForExtension("pcgame"))
    }

    @Test
    fun extensionLookupIsCaseInsensitive() {
        assertEquals("STEAM", GameNativeLaunchRoute.gameSourceForExtension("STEAM"))
        assertEquals("CUSTOM_GAME", GameNativeLaunchRoute.gameSourceForExtension("PcGame"))
    }

    @Test
    fun unrecognizedOrMissingExtensionReturnsNull() {
        assertNull(GameNativeLaunchRoute.gameSourceForExtension("txt"))
        assertNull(GameNativeLaunchRoute.gameSourceForExtension(""))
        // The old shipping vocabulary this project keeps re-discovering isn't GameNative's real constant.
        assertNull(GameNativeLaunchRoute.gameSourceForExtension("custom"))
    }

    // -- shortcut body -> app_id --------------------------------------------------------------------

    @Test
    fun parsesAPlainPositiveDecimalBody() {
        assertEquals(440, GameNativeLaunchRoute.parseAppId("440"))
        assertEquals(1, GameNativeLaunchRoute.parseAppId("1"))
    }

    @Test
    fun trimsSurroundingWhitespaceAndABomBeforeParsing() {
        assertEquals(440, GameNativeLaunchRoute.parseAppId("  440  \n"))
        // A UTF-8 BOM is exactly what a player typing an id into Notepad on Windows can prepend --
        // A shortcut file on player-controlled storage, exercised with real bytes.
        assertEquals(440, GameNativeLaunchRoute.parseAppId("\uFEFF440"))
    }

    @Test
    fun emptyOrBlankBodyIsMalformed() {
        assertNull(GameNativeLaunchRoute.parseAppId(""))
        assertNull(GameNativeLaunchRoute.parseAppId("   "))
        assertNull(GameNativeLaunchRoute.parseAppId("\n"))
    }

    @Test
    fun nonNumericBodyIsMalformed() {
        assertNull(GameNativeLaunchRoute.parseAppId("abc"))
        assertNull(GameNativeLaunchRoute.parseAppId("440 half-life"))
        assertNull(GameNativeLaunchRoute.parseAppId("440.5"))
        assertNull(GameNativeLaunchRoute.parseAppId("0x1B8"))
    }

    /** GameNative's own `getIntExtra("app_id", -1)` default. */
    @Test
    fun negativeOneIsMalformedNotAValidId() {
        assertNull(GameNativeLaunchRoute.parseAppId("-1"))
    }

    @Test
    fun zeroIsRejectedDefensively() {
        // No real Steam/Epic/GOG/Amazon/custom id is ever
        // 0 -- rejected the same defensive way as -1 rather than silently accepted as "id 0".
        assertNull(GameNativeLaunchRoute.parseAppId("0"))
    }

    @Test
    fun absurdlyLargeBodyOverflowsToNull() {
        assertNull(GameNativeLaunchRoute.parseAppId("99999999999999999999"))
    }

    // -- end-to-end file read -----------------------------------------------------------------------

    private fun tempShortcut(name: String, body: String? = "440"): File {
        val dir = Files.createTempDirectory("gamenative-route-test").toFile()
        val file = File(dir, name)
        if (body != null) file.writeText(body)
        return file
    }

    @Test
    fun validSteamShortcutParsesToAPayload() {
        val file = tempShortcut("Half-Life 2.steam", "440")
        val result = GameNativeLaunchRoute.parse(file)
        assertEquals(GameNativeLaunchRoute.ParseResult.Payload(440, "STEAM"), result)
    }

    @Test
    fun validCustomGameShortcutParsesWithTheCustomGameSource() {
        val file = tempShortcut("My Ripped Game.pcgame", "12345")
        val result = GameNativeLaunchRoute.parse(file)
        assertEquals(GameNativeLaunchRoute.ParseResult.Payload(12345, "CUSTOM_GAME"), result)
    }

    @Test
    fun missingFileIsUnreadableNotMalformed() {
        val dir = Files.createTempDirectory("gamenative-route-test").toFile()
        val neverWritten = File(dir, "Ghost.steam")
        val result = GameNativeLaunchRoute.parse(neverWritten)
        assertTrue("missing shortcut must surface as Unreadable", result is GameNativeLaunchRoute.ParseResult.Unreadable)
    }

    @Test
    fun emptyShortcutFileIsMalformed() {
        val file = tempShortcut("Empty.steam", "")
        val result = GameNativeLaunchRoute.parse(file)
        assertTrue("empty body must surface as Malformed", result is GameNativeLaunchRoute.ParseResult.Malformed)
    }

    @Test
    fun nonNumericShortcutBodyIsMalformed() {
        val file = tempShortcut("Bad.steam", "not-a-number")
        val result = GameNativeLaunchRoute.parse(file)
        assertTrue(result is GameNativeLaunchRoute.ParseResult.Malformed)
    }

    @Test
    fun negativeOneShortcutBodyIsMalformed() {
        val file = tempShortcut("Bad.steam", "-1")
        val result = GameNativeLaunchRoute.parse(file)
        assertTrue(result is GameNativeLaunchRoute.ParseResult.Malformed)
    }

    @Test
    fun unrecognizedExtensionIsMalformedRegardlessOfBody() {
        val file = tempShortcut("Not A Shortcut.txt", "440")
        val result = GameNativeLaunchRoute.parse(file)
        assertTrue(result is GameNativeLaunchRoute.ParseResult.Malformed)
    }

    @Test
    fun oversizedShortcutFileIsMalformed() {
        val file = tempShortcut("TooBig.steam", "4".repeat(200))
        val result = GameNativeLaunchRoute.parse(file)
        assertTrue("a file far larger than any real app_id must be refused", result is GameNativeLaunchRoute.ParseResult.Malformed)
    }
}
