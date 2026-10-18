package com.gruning.mimir.rewrite.core.storage.dao

import com.gruning.mimir.rewrite.core.storage.entity.GameEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class ExactHashPlannerTest {
    private val hash = "raw-sha1:" + "b".repeat(40)

    @Test
    fun `same-platform exact primaries prefer SAF then stable logical key`() {
        val plans = planExactHashReconciliation(
            listOf(
                game("managed-a", "a", "ps1", "MANAGED_PATH"),
                game("saf-z", "z", "ps1", "SAF_TREE_GRANT"),
                game("saf-z-copy", "z", "ps1", "SAF_TREE_GRANT"),
            ),
        )
        assertEquals(listOf(ExactHashPlan(winnerGameId = "z", loserGameIds = listOf("a"))), plans)
    }

    @Test
    fun `cross-platform malformed and non-primary evidence never plans a fold`() {
        val plans = planExactHashReconciliation(
            listOf(
                game("ps1", "a", "ps1", "SAF_TREE_GRANT"),
                game("ps2", "b", "ps2", "SAF_TREE_GRANT"),
                game("track", "c", "ps1", "SAF_TREE_GRANT", primary = false),
                game("bad", "d", "ps1", "SAF_TREE_GRANT", contentHash = "b".repeat(40)),
            ),
        )
        assertEquals(emptyList<ExactHashPlan>(), plans)
    }

    private fun game(
        entryKey: String,
        gameId: String,
        platform: String,
        location: String,
        primary: Boolean = true,
        contentHash: String = hash,
    ) = GameEntity(
        entryKey = entryKey,
        gameId = gameId,
        platformId = platform,
        contentKind = "GAME",
        displayName = "$gameId.bin",
        title = gameId,
        region = null,
        version = null,
        serial = null,
        titleCatalogKey = null,
        identitySource = "FILENAME",
        locationKind = location,
        availability = "AVAILABLE",
        sizeBytes = 1L,
        contentHash = contentHash,
        sourceRootUri = "/root",
        sourceDocumentUri = null,
        managedPath = "/$entryKey",
        relativePath = entryKey,
        discNumber = 0,
        duplicateGroupId = null,
        isPrimaryMember = primary,
        duplicateGroupKind = null,
    )
}
