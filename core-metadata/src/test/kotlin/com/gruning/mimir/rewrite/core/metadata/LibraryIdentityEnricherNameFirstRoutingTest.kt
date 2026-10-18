package com.gruning.mimir.rewrite.core.metadata

import com.gruning.mimir.rewrite.core.storage.dao.GameDao
import com.gruning.mimir.rewrite.core.storage.dao.IdentityWrite
import com.gruning.mimir.rewrite.core.storage.entity.GameEntity
import java.io.File
import java.io.InputStream
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WHERE the name lookup sits in [LibraryIdentityEnricher.resolveWith], proved by making any file read fatal.
 *
 * This is a separate test from [DatNameResolutionCorpusTest] on purpose. That one asks the catalogue
 * whether a name resolves; this one asks the enricher whether it bothered to look before reading 350 MB.
 * Both must hold, and only this one fails if the call is moved.
 *
 * The specific hazard: `ContainerRoutingPolicy.Route.CHD` returns straight to `resolveChd` at the top of
 * the routing switch, so a name check placed in the non-keyed branch below — the obvious place, since that
 * is where the hashing lives — would silently skip every PS1, PSP and Dreamcast `.chd`. Those are most of
 * what is slow, and `resolveChd` is the worst case of all: it decompresses the whole image to confirm the
 * header's own claim.
 */
class LibraryIdentityEnricherNameFirstRoutingTest {

    /** Every seam an identity adapter could use to touch bytes. Any call is the failure. */
    private class ForbiddenOpener(val reads: MutableList<String> = mutableListOf()) : ContentOpener {
        override fun open(documentUri: String?, managedPath: String?): InputStream? {
            reads += "open($managedPath)"
            return null
        }

        override fun openRandomAccess(documentUri: String?, managedPath: String?): RandomAccessContent? {
            reads += "openRandomAccess($managedPath)"
            return null
        }

        override fun openNativeSeekablePath(documentUri: String?, managedPath: String?): NativeSeekablePath? {
            reads += "openNativeSeekablePath($managedPath)"
            return null
        }
    }

    private fun realDatAssets(): PackedDatAssetProvider {
        val directory = listOf(File("../app/src/main/assets/dat"), File("app/src/main/assets/dat"))
            .firstOrNull(File::isDirectory)
            ?: error("bundled asset directory is not visible from ${File(".").absolutePath}")
        return PackedDatAssetProvider { name ->
            File(directory, name).takeIf(File::isFile)?.let { ByteBuffer.wrap(it.readBytes()) }
        }
    }

    private fun game(platform: String, fileName: String) = GameEntity(
        entryKey = "root::$fileName",
        gameId = "$platform-game",
        platformId = platform,
        contentKind = "GAME",
        displayName = fileName,
        title = "Before",
        region = null,
        version = null,
        serial = null,
        titleCatalogKey = null,
        identitySource = "FILENAME",
        locationKind = "MANAGED_PATH",
        availability = "AVAILABLE",
        sizeBytes = 100L,
        contentHash = null,
        sourceRootUri = "root",
        sourceDocumentUri = null,
        managedPath = "/roms/$fileName",
        relativePath = fileName,
        discNumber = 0,
        duplicateGroupId = null,
        isPrimaryMember = true,
        duplicateGroupKind = null,
    )

    private class Harness(val entity: GameEntity) {
        val writes = mutableListOf<IdentityWrite>()
        private var served = false

        @Suppress("UNCHECKED_CAST")
        val dao: GameDao = Proxy.newProxyInstance(
            GameDao::class.java.classLoader,
            arrayOf(GameDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "unresolvedPrimaries", "unresolvedPrimariesAfter" ->
                    if (served) emptyList<GameEntity>() else listOf(entity).also { served = true }
                "setIdentities" -> {
                    writes += (args?.firstOrNull() as? List<IdentityWrite>).orEmpty()
                    Unit
                }
                else -> Unit
            }
        } as GameDao
    }

    private fun enricher(harness: Harness, opener: ContentOpener) = LibraryIdentityEnricher(
        chdIdentity = ChdIdentitySource.RECOMPUTED_PAYLOAD,
        dao = harness.dao,
        datSource = BundledDatSource(realDatAssets()),
        idSource = BundledIdSource(DatAssetProvider { null }, manifest = emptyList()),
        opener = opener,
        nowMillis = { 41L },
    )

    @Test
    fun `a CHD on a non keyed platform is identified from its name without being opened`() = runTest {
        // A real PS1 disc from the reference device. 350 MB on disk; more than that once libchdr expands it.
        val harness = Harness(game("ps1", "Xenogears (USA) (Disc 1).chd"))
        val opener = ForbiddenOpener()

        enricher(harness, opener).enrich(reconcile = false)

        assertEquals(
            "the file must not be touched at all -- if this lists a seam, the name check has moved below " +
                "the Route.CHD return and every PS1/PSP/Dreamcast .chd is being decompressed again",
            emptyList<String>(),
            opener.reads,
        )
        assertEquals("the game must still be identified", 1, harness.writes.size)
        assertEquals("Xenogears (USA) (Disc 1)", harness.writes.single().title)
    }

    @Test
    fun `a plain ISO on a non keyed platform is identified from its name without being opened`() = runTest {
        // The DEFAULT route, for completeness: 3.6 GB that used to be read end-to-end.
        val harness = Harness(
            game("ps2", "Castlevania - Curse of Darkness (Europe) (En,Fr,De,Es,It).iso"),
        )
        val opener = ForbiddenOpener()

        enricher(harness, opener).enrich(reconcile = false)

        assertEquals(emptyList<String>(), opener.reads)
        assertEquals(
            "Castlevania - Curse of Darkness (Europe) (En,Fr,De,Es,It)",
            harness.writes.single().title,
        )
    }

    /**
     * The 55-second cold start, measured on the reference device 2026-09-17 and pinned here at its cause.
     *
     * PS1 discs sit behind an `.m3u`, and the playlist is the primary. It is also TINY, so the enricher's
     * smallest-first walk reaches it before anything else -- and when it fell through to the byte path it
     * dragged ~700 MB of CHD decompression behind it, three sets in parallel, before the progress counter
     * could move once. The first thing a player saw was a bar that sat still for 55 seconds.
     */
    @Test
    fun `a multi-disc playlist is identified from its name without opening any member`() = runTest {
        val harness = Harness(
            game("ps1", "Chrono Cross (USA).m3u").copy(duplicateGroupKind = "MULTI_DISC"),
        )
        val opener = ForbiddenOpener()

        enricher(harness, opener).enrich(reconcile = false)

        assertEquals(
            "neither the playlist nor its discs may be opened -- this is the 55-second stall",
            emptyList<String>(),
            opener.reads,
        )
        assertEquals(
            "and the title must be the GROUP's, not disc one's, matching what resolveDisc already wrote",
            "Chrono Cross (USA, Canada)",
            harness.writes.single().title,
        )
        assertNull(
            "a multi-disc group's collapse key is deliberately null; the members share one gameId",
            harness.writes.single().collapse,
        )
    }

    @Test
    fun `a single-file game is still refused the disc shortcut`() {
        // The same untagged name WITHOUT the group signal must keep failing closed and hashing, or the
        // fix has quietly become "any untagged name may pick disc one".
        val harness = Harness(game("ps1", "Chrono Cross (USA).chd"))
        val opener = ForbiddenOpener()

        kotlinx.coroutines.runBlocking { enricher(harness, opener).enrich(reconcile = false) }

        assertTrue("it must reach the byte path", opener.reads.isNotEmpty())
        assertEquals(0, harness.writes.size)
    }

    @Test
    fun `a name the catalogue cannot resolve still falls through to the byte path`() {
        // The other half of the contract, and the one that keeps this from becoming a name-only resolver:
        // an unresolved name must leave the existing routing untouched rather than abstain early. This
        // name is in no shipped DAT at all, which is the common case for a renamed or homebrew file.
        val harness = Harness(game("ps1", "definitely-not-a-dump-name.chd"))
        val opener = ForbiddenOpener()

        kotlinx.coroutines.runBlocking { enricher(harness, opener).enrich(reconcile = false) }

        assertTrue(
            "an unresolvable name must hand the file to the CHD adapter, which then reads it",
            opener.reads.isNotEmpty(),
        )
        assertEquals("and nothing may be written from a name that did not resolve", 0, harness.writes.size)
    }
}
