package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.DuplicateGroupKind
import com.gruning.mimir.rewrite.core.storage.entity.GameEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class ConfigBackupTitlePolicyTest {

    @Test fun `backup strips a grouped disc stamp but preserves lone and unknown kinds`() {
        assertEquals(
            "Resident Evil 2 - Dual Shock Ver. (USA)",
            canonicalTitleForBackup(
                game(
                    canonicalTitle = "Resident Evil 2 - Dual Shock Ver. (USA) (Disc 1)",
                    groupKind = DuplicateGroupKind.MULTI_DISC.name,
                ),
            ),
        )

        val loneDisc = "Resident Evil - Code Veronica X (USA) (Disc 2)"
        assertEquals(loneDisc, canonicalTitleForBackup(game(canonicalTitle = loneDisc, groupKind = null)))
        assertEquals(loneDisc, canonicalTitleForBackup(game(canonicalTitle = loneDisc, groupKind = "FUTURE_KIND")))
    }

    @Test fun `backup falls back to the raw title and applies the same group policy`() {
        assertEquals(
            "Chrono Cross",
            canonicalTitleForBackup(
                game(
                    canonicalTitle = null,
                    groupKind = DuplicateGroupKind.MULTI_DISC.name,
                    title = "Chrono Cross (Disc 2)",
                ),
            ),
        )
    }

    private fun game(canonicalTitle: String?, groupKind: String?, title: String = "Fallback"): GameEntity = GameEntity(
        entryKey = "primary",
        gameId = "game",
        platformId = "ps1",
        contentKind = "GAME",
        displayName = "game.m3u",
        title = title,
        region = null,
        version = null,
        serial = null,
        titleCatalogKey = null,
        identitySource = "FILENAME",
        locationKind = "SAF",
        availability = "AVAILABLE",
        sizeBytes = 64,
        contentHash = null,
        sourceRootUri = "content://root",
        sourceDocumentUri = "content://root/game.m3u",
        managedPath = null,
        relativePath = "game.m3u",
        discNumber = 0,
        duplicateGroupId = "game",
        isPrimaryMember = true,
        duplicateGroupKind = groupKind,
        canonicalTitle = canonicalTitle,
        collapseKey = "collapse:game",
        identityOrigin = "REDUMP",
        identityResolvedAtMillis = 1234L,
    )
}
