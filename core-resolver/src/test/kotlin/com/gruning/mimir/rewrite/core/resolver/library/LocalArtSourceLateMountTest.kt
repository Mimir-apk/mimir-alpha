package com.gruning.mimir.rewrite.core.resolver.library

import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.resolver.scan.SourceLister
import com.gruning.mimir.rewrite.core.resolver.scan.SourceWalkResult
import com.gruning.mimir.rewrite.core.storage.dao.GameDao
import com.gruning.mimir.rewrite.core.storage.dao.GameRowProjection
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **The covers on an SD card that mounts after the library has drawn.**
 *
 * Mimir is the home app, so at boot the shelves are drawn before the card is mounted. Every probe for a cover on it
 * answers "no file", and [LocalArtSource] memoises that for the life of the process: a plain-file probe is always
 * settled, and a SAF listing that succeeded empty is settled too. Nothing asks again, so the covers stay on their glyphs
 * until the process dies -- the boot-time blank other frontends report (Pegasus #1125, Cocoon D#275, RetroHrai the earlier update).
 *
 * `StorageVolumeMonitor` now calls `LibraryRepository.refreshAllMedia()` when a reconcile restores games or a volume
 * mounts. These tests pin the part that has to be true for that call to be enough: the memo forgets, and the shelves
 * re-project.
 */
class LocalArtSourceLateMountTest {

    private val rom = "/storage/6E0E-FBB0/ROMS/gba/Metroid Fusion.gba"
    private val cover = "/storage/6E0E-FBB0/ROMS/gba/media_art/Metroid Fusion.png"
    private val coverUri = "file:///storage/6E0E-FBB0/ROMS/gba/media_art/Metroid%20Fusion.png"

    @Test
    fun `a cover missed while the card was away stays missed until the memo is dropped`() {
        val access = CardAccess()
        val source = LocalArtSource(access)

        assertNull("the card is not mounted yet", source.artUriFor(null, rom))

        access.files[cover] = coverUri // the card mounts

        assertNull("the miss is memoised -- this is the defect: nothing asks again", source.artUriFor(null, rom))

        source.invalidateAll() // what StorageVolumeMonitor now triggers on a mount

        assertEquals(coverUri, source.artUriFor(null, rom))
    }

    @Test
    fun `a SAF cover whose listing succeeded empty while the card was away resolves after the drop`() {
        val romDoc = "content://provider/tree/6E0E-FBB0%3AROMS/document/6E0E-FBB0%3AROMS%2Fgba%2FMetroid%20Fusion.gba"
        val parent = "content://provider/tree/6E0E-FBB0%3AROMS/document/6E0E-FBB0%3AROMS%2Fgba"
        val managedDir = "$parent%2Fmedia_art"
        val coverDoc = "$managedDir%2FMetroid%20Fusion.png"
        val access = CardAccess().apply {
            displayNames[romDoc] = "Metroid Fusion.gba"
            parents[romDoc] = parent
        }
        val source = LocalArtSource(access)

        assertNull(source.artUriFor(romDoc, null))
        assertTrue("an empty but successful listing is a settled answer", source.artResolutionSettled(romDoc, null))

        access.children[parent to "media_art"] = managedDir
        access.children[managedDir to "Metroid Fusion.png"] = coverDoc

        assertNull("settled, so memoised", source.artUriFor(romDoc, null))

        source.invalidateAll()

        assertEquals(coverDoc, source.artUriFor(romDoc, null))
    }

    @Test
    fun `refreshAllMedia on the repository drops the memo and re-projects the shelf with the cover`() = runBlocking {
        val access = CardAccess()
        val source = LocalArtSource(access)
        val repository = repository(source, projection("gba/metroid-fusion", rom, "Metroid Fusion"))

        val before = repository.observeGames(PlatformId.GBA).first().single()
        assertNull("drawn before the card mounted", before.artUri)

        access.files[cover] = coverUri // the card mounts; the reconcile restores its games

        val withoutRefresh = repository.observeGames(PlatformId.GBA).first().single()
        assertNull("re-emitting the rows alone cannot see it: the memo still says absent", withoutRefresh.artUri)

        repository.refreshAllMedia()

        val after = repository.observeGames(PlatformId.GBA).first().single()
        assertNotNull("the cover comes back without a restart", after.artUri)
        assertTrue(after.artUri!!.startsWith(coverUri))
    }

    // ── harness ───────────────────────────────────────────────────────────────

    private fun projection(gameId: String, managedPath: String, title: String) = GameRowProjection(
        gameId = gameId,
        title = title,
        platformId = PlatformId.GBA.value,
        duplicateGroupKind = null,
        memberCount = 1,
        managedPath = managedPath,
    )

    private fun repository(source: LocalArtSource, vararg projections: GameRowProjection): LibraryRepositoryImpl {
        val gameDao = proxy<GameDao> { methodName ->
            when (methodName) {
                "visibleGameRowsForPlatform" -> flowOf(projections.toList())
                else -> error("Unexpected GameDao call: $methodName")
            }
        }
        return LibraryRepositoryImpl(
            gameDao = gameDao,
            platformDao = unusedProxy(),
            sourceRootDao = unusedProxy(),
            hiddenItemDao = unusedProxy(),
            favoriteDao = unusedProxy(),
            artSource = source,
            previewVideoSource = source,
            walker = object : SourceLister {
                override suspend fun walk(rootUri: String): SourceWalkResult =
                    SourceWalkResult.Complete(emptyList())
            },
            runScanTransaction = { block -> block() },
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    /** A card that answers from literal maps: whatever is in them is "mounted", and an empty map is a card that is away. */
    private class CardAccess : LocalArtAccess {
        val files = mutableMapOf<String, String>()
        val displayNames = mutableMapOf<String, String>()
        val parents = mutableMapOf<String, String>()
        val children = mutableMapOf<Pair<String, String>, String>()

        override fun existingFileUri(path: String): String? = files[path]
        override fun documentDisplayName(documentUri: String): String? = displayNames[documentUri]
        override fun parentDocumentUri(documentUri: String): String? = parents[documentUri]
        override fun existingChildUri(
            parentDocumentUri: String,
            displayName: String,
            requiredMimeType: String?,
        ): String? = children[parentDocumentUri to displayName]
    }

    private inline fun <reified T> unusedProxy(): T = proxy { methodName ->
        error("Unexpected ${T::class.java.simpleName} call: $methodName")
    }

    private inline fun <reified T> proxy(crossinline answer: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            answer(method.name)
        } as T
}
