package com.gruning.mimir.rewrite.core.resolver

import com.gruning.mimir.rewrite.core.model.ContentKind
import com.gruning.mimir.rewrite.core.model.EntryKey
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.ResidentFile
import com.gruning.mimir.rewrite.core.model.ResidentLocationKind
import com.gruning.mimir.rewrite.core.model.physicalResidentKey
import com.gruning.mimir.rewrite.core.resolver.scan.WalkedFile
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uPlaylistResolverTest {

    @Test
    fun `playlist references group scenario-named discs and assign stable order`() = runBlocking {
        val playlist = resident("Resident Evil 2.m3u", "PSX/Resident Evil 2.m3u", ContentKind.MULTI_DISC_PLAYLIST)
        val leon = resident("Leon.iso", "PSX/Leon.iso", ContentKind.GAME)
        val claire = resident("Claire.iso", "PSX/Claire.iso", ContentKind.GAME)
        val walked = listOf(playlist, leon, claire).map(::walked)
        val body = """
            #EXTM3U
            Leon.iso
            "Claire.iso"
        """.trimIndent()

        val resolution = M3uPlaylistResolver.resolve(walked, listOf(playlist, leon, claire)) { file, max ->
            assertEquals(M3uPlaylistResolver.MAX_PLAYLIST_BYTES, max)
            body.takeIf { file.displayName.endsWith(".m3u") }
        }
        val group = KeyingDedupGrouper().group(resolution.files, resolution.groups).single()

        assertEquals(ContentKind.MULTI_DISC_PLAYLIST, group.primary.contentKind)
        assertEquals(3, group.memberCount)
        assertEquals(
            listOf(1, 2),
            group.allMembers.filter { it.contentKind == ContentKind.DISC_MEMBER }.map { it.discNumber }.sorted(),
        )
        assertEquals("2 discs", group.collapseBadge)
    }

    @Test
    fun `playlist cannot escape the walked source or bind another platform`() = runBlocking {
        val playlist = resident("Set.m3u", "PSX/Set.m3u", ContentKind.MULTI_DISC_PLAYLIST)
        val local = resident("Local.iso", "PSX/Local.iso", ContentKind.GAME)
        val otherPlatform = resident("Other.iso", "PSX/Other.iso", ContentKind.GAME, PlatformId.PS2)
        val resolution = M3uPlaylistResolver.resolve(
            listOf(playlist, local, otherPlatform).map(::walked),
            listOf(playlist, local, otherPlatform),
        ) { _, _ -> "../Outside.iso\nLocal.iso\nOther.iso" }

        assertTrue(resolution.groups.isEmpty())
        assertTrue(resolution.files.all { it.discNumber == 0 })
        assertNull(M3uPlaylistResolver.normalizeRelative("../Outside.iso"))
    }

    @Test
    fun `parser ignores comments blank lines and utf8 bom`() {
        assertEquals(
            listOf("Disc 1.chd", "Disc 2.chd"),
            M3uPlaylistResolver.parseEntries("\uFEFF#EXTM3U\n\n'Disc 1.chd'\nDisc 2.chd\n"),
        )
    }

    @Test
    fun `unreadable playlist is ignored without failing resident recognition`() = runBlocking {
        val playlist = resident("Set.m3u", "PSX/Set.m3u", ContentKind.MULTI_DISC_PLAYLIST)
        val disc1 = resident("Set (Disc 1).chd", "PSX/Set (Disc 1).chd", ContentKind.DISC_MEMBER)
        val disc2 = resident("Set (Disc 2).chd", "PSX/Set (Disc 2).chd", ContentKind.DISC_MEMBER)

        val resolution = M3uPlaylistResolver.resolve(
            listOf(playlist, disc1, disc2).map(::walked),
            listOf(playlist, disc1, disc2),
        ) { _, _ -> throw SecurityException("revoked provider") }

        assertTrue(resolution.groups.isEmpty())
        assertEquals(listOf(playlist, disc1, disc2), resolution.files)
    }

    @Test
    fun `canonical p2 lexical order cannot steer physical playlist order`() = runBlocking {
        val root = "root"
        val physicalFirstPlaylist = physicalResident(
            root,
            "a/Set.m3u",
            ContentKind.MULTI_DISC_PLAYLIST,
        )
        val physicalSecondPlaylist = physicalResident(
            root,
            "zzzz/Set.m3u",
            ContentKind.MULTI_DISC_PLAYLIST,
        )
        assertTrue(
            physicalFirstPlaylist.entryKey.value > physicalSecondPlaylist.entryKey.value,
        )
        val residents = listOf(
            physicalSecondPlaylist,
            physicalResident(root, "zzzz/Disc 1.chd", ContentKind.GAME),
            physicalResident(root, "zzzz/Disc 2.chd", ContentKind.GAME),
            physicalFirstPlaylist,
            physicalResident(root, "a/Disc 1.chd", ContentKind.GAME),
            physicalResident(root, "a/Disc 2.chd", ContentKind.GAME),
        )

        val resolution = M3uPlaylistResolver.resolve(
            residents.map(::walked),
            residents,
        ) { file, _ ->
            "Disc 1.chd\nDisc 2.chd\n".takeIf { file.displayName.endsWith(".m3u") }
        }

        assertEquals(
            listOf(physicalFirstPlaylist.entryKey, physicalSecondPlaylist.entryKey),
            resolution.groups.map(M3uPlaylistGroup::playlistEntryKey),
        )
    }

    @Test(expected = CancellationException::class)
    fun `playlist read never converts cancellation into an optional miss`() = runBlocking {
        val playlist = resident("Set.m3u", "PSX/Set.m3u", ContentKind.MULTI_DISC_PLAYLIST)

        M3uPlaylistResolver.resolve(listOf(walked(playlist)), listOf(playlist)) { _, _ ->
            throw CancellationException("cancel playlist read")
        }
        Unit
    }

    @Test(expected = CancellationException::class)
    fun `cancelled read IOException is normalized back to cancellation`() = runBlocking {
        val playlist = resident("Set.m3u", "PSX/Set.m3u", ContentKind.MULTI_DISC_PLAYLIST)

        M3uPlaylistResolver.resolve(listOf(walked(playlist)), listOf(playlist)) { _, _ ->
            currentCoroutineContext()[Job]?.cancel(CancellationException("cancel before close failure"))
            throw IOException("descriptor close resumed the blocked read")
        }
        Unit
    }

    private fun resident(
        name: String,
        relativePath: String,
        kind: ContentKind,
        platformId: PlatformId = PlatformId.PS1,
    ): ResidentFile {
        val extension = name.substringAfterLast('.', "")
        val identity = GameIdentityBuilder().build(
            platformId,
            ResolutionInput(fileName = name, extension = extension),
        )
        return ResidentFile(
            entryKey = EntryKey("root::$relativePath"),
            platformId = platformId,
            contentKind = kind,
            displayName = name,
            location = ResidentLocationKind.MANAGED_PATH,
            sizeBytes = 2L * 1024 * 1024,
            managedPath = "/roms/$relativePath",
            relativePath = relativePath,
            identity = identity,
        )
    }

    private fun physicalResident(
        root: String,
        relativePath: String,
        kind: ContentKind,
    ): ResidentFile = resident(
        name = relativePath.substringAfterLast('/'),
        relativePath = relativePath,
        kind = kind,
    ).copy(
        entryKey = EntryKey(physicalResidentKey(root, relativePath)),
        sourceRootUri = root,
    )

    private fun walked(file: ResidentFile): WalkedFile = WalkedFile(
        displayName = file.displayName,
        relativePath = file.relativePath,
        sizeBytes = file.sizeBytes,
        documentUri = file.sourceDocumentUri,
        filePath = file.managedPath,
    )
}
