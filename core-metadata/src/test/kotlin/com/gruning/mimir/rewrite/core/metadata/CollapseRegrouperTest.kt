package com.gruning.mimir.rewrite.core.metadata

import com.gruning.mimir.rewrite.core.metadata.LibraryCollapseRegrouper.Companion.baseTitle
import com.gruning.mimir.rewrite.core.model.physicalResidentKey
import com.gruning.mimir.rewrite.core.storage.entity.GameEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The collapse guard is the load-bearing correctness: two games only fold if they share a base title, so a
 * demo and a full game that carry the same DAT clone-parent key are NOT merged.
 */
class CollapseRegrouperTest {

    @Test
    fun `genuine region variants share a base title (would fold)`() {
        assertEquals(
            baseTitle("Pokemon - Red Version (USA, Europe) (SGB Enhanced)"),
            baseTitle("Pokemon - Red Version (Europe) (Rev 1)"),
        )
        assertEquals(
            baseTitle("The Legend of Zelda (USA)"),
            baseTitle("The Legend of Zelda (Europe) (Rev 1)"),
        )
    }

    @Test
    fun `a demo and the full game do NOT share a base title (stay separate)`() {
        // Real hazard: No-Intro files "First Hunt" (demo) as a clone of the full "Hunters" — same key.
        assertNotEquals(
            baseTitle("Metroid Prime - Hunters - First Hunt (USA, Europe)"),
            baseTitle("Metroid Prime - Hunters (USA) (Rev 1)"),
        )
    }

    @Test
    fun `base title strips parens and brackets, collapses whitespace, lower-cases`() {
        assertEquals("metroid prime - hunters", baseTitle("Metroid Prime - Hunters (USA) (Rev 1)"))
        assertEquals("pokemon legends z-a", baseTitle("Pokemon Legends Z-A [0100F43008C44000] [v0]"))
    }

    @Test
    fun `catalog fold uses physical tuple when canonical p2 lexical order is opposite`() {
        val physicalFirst = game("aa", "same.gba")
        val physicalSecond = game("z", "same.gba")
        assertTrue(physicalFirst.entryKey > physicalSecond.entryKey)

        assertEquals(
            physicalFirst.entryKey,
            orderedCatalogFoldVariants(listOf(physicalSecond, physicalFirst)).single().entryKey,
        )
    }

    private fun game(sourceRootUri: String, relativePath: String) = GameEntity(
        entryKey = physicalResidentKey(sourceRootUri, relativePath),
        gameId = "same-logical-game",
        platformId = "gba",
        contentKind = "GAME",
        displayName = "Same.gba",
        title = "Same",
        region = null,
        version = null,
        serial = null,
        titleCatalogKey = null,
        identitySource = "FILENAME",
        locationKind = "SAF_TREE_GRANT",
        availability = "AVAILABLE",
        sizeBytes = 1024,
        contentHash = null,
        sourceRootUri = sourceRootUri,
        sourceDocumentUri = null,
        managedPath = "/roms/$relativePath",
        relativePath = relativePath,
        discNumber = 0,
        duplicateGroupId = null,
        isPrimaryMember = true,
        duplicateGroupKind = null,
        canonicalTitle = "Same",
        collapseKey = "same",
        identityOrigin = "NO_INTRO",
        identityResolvedAtMillis = 1,
    )
}
