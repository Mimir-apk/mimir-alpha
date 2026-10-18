package com.gruning.mimir.rewrite.core.metadata

import com.gruning.mimir.rewrite.core.model.PlatformId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The offline foundation of the metadata subsystem resolves a canonical title with zero network. */
class OfflineMetadataRepositoryTest {

    private val repo = OfflineMetadataRepository()

    @Test
    fun `resolves a catalogued title fully offline via the bundled catalog`() = runBlocking {
        val r = repo.resolve(
            MetadataQuery(PlatformId.GB, rawBaseName = "Pokemon - Red Version (USA, Europe) (SGB Enhanced)"),
        )
        assertEquals("Pokémon Red", r?.canonicalTitle)
        assertEquals(MetadataOrigin.TITLE_CATALOG, r?.origin)
    }

    @Test
    fun `returns null for an uncatalogued title (never over-claims)`() = runBlocking {
        assertNull(repo.resolve(MetadataQuery(PlatformId.GB, rawBaseName = "Some Obscure Homebrew (USA)")))
    }
}
