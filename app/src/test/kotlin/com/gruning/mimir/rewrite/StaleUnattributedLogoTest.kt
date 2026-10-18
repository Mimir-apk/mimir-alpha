package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.ArtworkFetchController.ManagedMediaSlot
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A legacy enrich screenshot saved as a logo survives a wipe when replacement has no file to write.
 * Remove it on the unattributed re-check, even though that deletes card art.
 */
class StaleUnattributedLogoTest {

    @Test
    fun `only an unattributed logo the verified record cannot replace is removed`() {
        assertTrue(staleUnattributedLogoShouldClear(replaceFlatArt = true, verifiedLogoUrl = null))
        assertFalse("a clear logo replaces it instead", staleUnattributedLogoShouldClear(true, "https://x/wheel.png"))
        assertFalse("an attributed or absent logo is never touched", staleUnattributedLogoShouldClear(false, null))
        assertFalse(staleUnattributedLogoShouldClear(false, "https://x/wheel.png"))
    }

    @Test
    fun `the unattributed re-check asks about a logo already on disk, so the removal is reachable`() {
        val toFetch = ArtworkFetchController.artworkSlotsToFetch(
            present = setOf(ManagedMediaSlot.COVER, ManagedMediaSlot.LOGO, ManagedMediaSlot.HERO),
            unattributed = true,
            forceLookup = false,
            slotCoolingDown = { false },
        )
        assertTrue(ManagedMediaSlot.LOGO in toFetch)
        val attributed = ArtworkFetchController.artworkSlotsToFetch(
            present = setOf(ManagedMediaSlot.COVER, ManagedMediaSlot.LOGO, ManagedMediaSlot.HERO),
            unattributed = false,
            forceLookup = true,
            slotCoolingDown = { false },
        )
        assertFalse("an attributed logo is never re-asked, so never removed", ManagedMediaSlot.LOGO in attributed)
    }

    /**
     * The post-onboarding metadata pass verified up to eight games'
     * identity and recorded them as attributed, so their art skipped this re-check until the next wipe.
     */
    @Test
    fun `the metadata pass never marks a game's art as verified`() {
        val source = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/ConnectedMetadataHydrationWorker.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/ConnectedMetadataHydrationWorker.kt"),
        ).first(File::isFile).readText()
        assertTrue("search window", source.contains("ScreenScraperMetadataSource.LookupOutcome.Loaded ->"))
        assertFalse(source.contains("ArtResolutionStore.record("))
    }

    @Test
    fun `only a logo inside media_art is ever deleted`() {
        assertTrue(isManagedLogoPath("/storage/7BF4-F932/ROMs/dreamcast/media_art/House of the Dead (USA)-logo.png"))
        assertTrue(isManagedLogoPath("7BF4-F932:ROMs/snes/media_art/Super Metroid-logo.PNG"))
        assertTrue(isManagedLogoPath("primary:ROMs/gba/media_art/Minish Cap-logo.webp"))
        assertFalse("beside the ROM is never Mimir's", isManagedLogoPath("/storage/x/ROMs/snes/Super Metroid-logo.png"))
        assertFalse("the cover", isManagedLogoPath("/storage/x/ROMs/snes/media_art/Super Metroid.png"))
        assertFalse("the hero", isManagedLogoPath("/storage/x/ROMs/snes/media_art/Super Metroid-hero.png"))
        assertFalse("a backup folder", isManagedLogoPath("/storage/x/ROMs/Mimir-media-art-backup-1/Game-logo.png"))
        assertFalse("a nested backup", isManagedLogoPath("/storage/x/media_art/backup/Game-logo.png"))
        assertFalse("no base name", isManagedLogoPath("/storage/x/ROMs/snes/media_art/-logo.png"))
        assertFalse("not an image", isManagedLogoPath("/storage/x/ROMs/snes/media_art/Game-logo.txt"))
        assertFalse("a volume root", isManagedLogoPath("7BF4-F932:media_art-logo.png"))
    }

    @Test
    fun `the removal publishes like a write and never counts as a logo written`() {
        val source = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/ArtworkFetchController.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/ArtworkFetchController.kt"),
        ).first(File::isFile).readText()
        val finalWrites = source.substringAfter("val finalWrites = listOf(").substringBefore(")")
        assertTrue(finalWrites.contains("staleLogoClear"))
        assertTrue(source.contains("flatArtWritten = flatArtWrite.written,"))
        val clear = source.substringAfter("private suspend fun clearStaleUnattributedLogo(")
            .substringBefore("private fun localArtPath(")
        val guards = listOf(
            "currentGame.entryKey != requestedGame.entryKey",
            "SafWritePreflight(context).allowsWrite(",
            "currentManagedMediaUri(context, currentGame, ManagedMediaSlot.LOGO) != checkedUri",
            "isManagedLogoPath(path)",
            "deleteLocalArt(context, checkedUri)",
        )
        val positions = guards.map { clear.indexOf(it) }
        assertTrue("every guard is present: $positions", positions.all { it >= 0 })
        assertEquals("every guard runs before the delete", positions.sorted(), positions)
        val logLine = clear.lines().first { it.contains("Log.i(") }
        assertFalse("never logs a path or URL: $logLine", logLine.contains("checkedUri") || logLine.contains("path"))
    }
}
