package com.gruning.mimir.rewrite.core.storage.mapper

import com.gruning.mimir.rewrite.core.model.ContentKind
import com.gruning.mimir.rewrite.core.model.EntryKey
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.GameIdentity
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.ResidentFile
import com.gruning.mimir.rewrite.core.model.ResidentLocationKind
import com.gruning.mimir.rewrite.core.model.physicalResidentKey
import com.gruning.mimir.rewrite.core.storage.entity.GameEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ResidentFileMapperTest {
    @Test
    fun gameEntityPreservesExactSourceDocumentUriOnReadback() {
        val resident = entity(
            sourceRootUri = "content://com.android.externalstorage.documents/tree/primary%3ARoms",
            sourceDocumentUri = "content://com.android.externalstorage.documents/document/primary%3ARoms%2Fds%2Fwhite.nds",
        ).toResidentFile()

        assertEquals(
            "content://com.android.externalstorage.documents/document/primary%3ARoms%2Fds%2Fwhite.nds",
            resident.sourceDocumentUri,
        )
        assertEquals(
            "content://com.android.externalstorage.documents/tree/primary%3ARoms",
            resident.sourceRootUri,
        )
    }

    @Test
    fun `persistence rejects a missing exact source owner`() {
        val resident = resident(sourceRootUri = null, entryKey = "legacy")

        assertThrows(IllegalArgumentException::class.java) {
            resident.toEntity()
        }
    }

    @Test
    fun `persistence rejects an entry key that disagrees with exact ownership`() {
        val resident = resident(sourceRootUri = "root", entryKey = "root::game.rom")

        assertThrows(IllegalArgumentException::class.java) {
            resident.toEntity()
        }
    }

    @Test
    fun `persistence accepts only the canonical physical resident key`() {
        val root = "content://root:with::delimiter"
        val relativePath = "folder/game::disc.rom"
        val resident = resident(
            sourceRootUri = root,
            relativePath = relativePath,
            entryKey = physicalResidentKey(root, relativePath),
        )

        val entity = resident.toEntity()

        assertEquals(root, entity.sourceRootUri)
        assertEquals(relativePath, entity.relativePath)
        assertEquals(physicalResidentKey(root, relativePath), entity.entryKey)
    }

    private fun resident(
        sourceRootUri: String?,
        relativePath: String = "game.rom",
        entryKey: String,
    ): ResidentFile = ResidentFile(
        entryKey = EntryKey(entryKey),
        platformId = PlatformId("snes"),
        contentKind = ContentKind.GAME,
        displayName = "Game",
        location = ResidentLocationKind.SAF_TREE_GRANT,
        sourceRootUri = sourceRootUri,
        sourceDocumentUri = "content://document/game",
        relativePath = relativePath,
        identity = GameIdentity(
            gameId = GameId("game-1"),
            title = "Game",
        ),
    )

    private fun entity(
        sourceRootUri: String,
        sourceDocumentUri: String?,
    ): GameEntity = GameEntity(
        entryKey = "entry-1",
        gameId = "game-1",
        platformId = "nds",
        contentKind = "ROM",
        displayName = "Pokemon White.nds",
        title = "Pokemon White",
        region = null,
        version = null,
        serial = null,
        titleCatalogKey = null,
        identitySource = "NONE",
        locationKind = "EXTERNAL_REFERENCE",
        availability = "AVAILABLE",
        sizeBytes = 1_024,
        contentHash = null,
        sourceRootUri = sourceRootUri,
        sourceDocumentUri = sourceDocumentUri,
        managedPath = null,
        relativePath = "ds/Pokemon White.nds",
        discNumber = 1,
        duplicateGroupId = null,
        isPrimaryMember = true,
        duplicateGroupKind = null,
    )
}
