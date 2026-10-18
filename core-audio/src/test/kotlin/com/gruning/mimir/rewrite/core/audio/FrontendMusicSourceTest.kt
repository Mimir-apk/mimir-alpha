package com.gruning.mimir.rewrite.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which music the frontend plays when the source is Your music: the folder's tracks in index order when it has some,
 * the theme bed with an honest reason when it cannot, and nothing decided while the index is still being read.
 */
class FrontendMusicSourceTest {
    private val tree = "content://tree/music"
    private val tracks = listOf(
        MusicTrack("", "a - Overworld.mp3", "id-a"),
        MusicTrack("snes", "b.ogg", "id-b"),
        MusicTrack("", "c.flac", "id-c"),
    )
    private val library = MusicLibraryState(treeUri = tree, trackCount = 3, lastScanMillis = 5L)
    private val playlist = MusicPlaylist(tree, scannedAtMillis = 5L, tracks = tracks)
    private val bed = MimirFrontendBed.Ambient

    private fun resolve(
        source: MimirMusicSource = MimirMusicSource.CUSTOM,
        library: MusicLibraryState = this.library,
        playlist: MusicPlaylist = this.playlist,
        failure: FolderPlaybackFailure? = null,
        selection: MusicSelection? = null,
    ) = FrontendMusicSourceResolver.resolve(source, bed, library, playlist, failure, selection)

    @Test
    fun `Mimir's music is the theme bed whatever the folder holds`() {
        val choice = resolve(source = MimirMusicSource.MIMIR)!!
        assertEquals(FrontendMusicPlan.Bed(bed), choice.plan)
        assertNull(choice.fallback)
    }

    @Test
    fun `Your music with a folder of tracks plays the root tracks in index order and leaves platform folders alone`() {
        val choice = resolve()!!
        assertNull(choice.fallback)
        assertEquals(FrontendMusicPlan.Folder(tree, 5L, listOf(tracks[0], tracks[2])), choice.plan)
    }

    @Test
    fun `a folder holding only platform folders plays all its tracks rather than reading as empty`() {
        val platformOnly = listOf(MusicTrack("snes", "b.ogg", "id-b"), MusicTrack("ps2", "d.mp3", "id-d"))
        val choice = resolve(playlist = playlist.copy(tracks = platformOnly))!!
        assertNull(choice.fallback)
        assertEquals(FrontendMusicPlan.Folder(tree, 5L, platformOnly), choice.plan)
    }

    @Test
    fun `a rescan that only changes a platform folder restarts nothing`() {
        val before = resolve()!!.plan
        val rescanned = resolve(
            playlist = playlist.copy(scannedAtMillis = 9L, tracks = tracks + MusicTrack("ps2", "e.mp3", "id-e")),
        )!!.plan
        assertTrue(rescanned.playsTheSameAs(before))
    }

    @Test
    fun `switching the source back restores the theme bed`() {
        assertTrue(resolve()!!.plan is FrontendMusicPlan.Folder)
        assertEquals(FrontendMusicPlan.Bed(bed), resolve(source = MimirMusicSource.MIMIR)!!.plan)
    }

    @Test
    fun `no folder falls back to the bed and says so`() {
        val choice = resolve(library = MusicLibraryState(), playlist = MusicPlaylist())!!
        assertEquals(FrontendMusicPlan.Bed(bed), choice.plan)
        assertEquals(CustomMusicFallback.NO_FOLDER, choice.fallback)
    }

    @Test
    fun `an empty folder falls back to the bed and says so`() {
        val choice = resolve(playlist = playlist.copy(tracks = emptyList()))!!
        assertEquals(FrontendMusicPlan.Bed(bed), choice.plan)
        assertEquals(CustomMusicFallback.EMPTY, choice.fallback)
    }

    @Test
    fun `a revoked or unreadable folder falls back with its own reason even with last good tracks`() {
        val lost = resolve(library = library.copy(error = MusicLibraryError.GRANT_LOST))!!
        assertEquals(FrontendMusicPlan.Bed(bed), lost.plan)
        assertEquals(CustomMusicFallback.GRANT_LOST, lost.fallback)
        val unreadable = resolve(library = library.copy(error = MusicLibraryError.UNREADABLE))!!
        assertEquals(CustomMusicFallback.UNREADABLE, unreadable.fallback)
    }

    @Test
    fun `a folder whose tracks would not open falls back until a rescan or another folder`() {
        val failure = FolderPlaybackFailure(tree, scannedAtMillis = 5L, reason = CustomMusicFallback.GRANT_LOST)
        val held = resolve(failure = failure)!!
        assertEquals(FrontendMusicPlan.Bed(bed), held.plan)
        assertEquals(CustomMusicFallback.GRANT_LOST, held.fallback)

        val rescanned = resolve(playlist = playlist.copy(scannedAtMillis = 9L), failure = failure)!!
        assertTrue("a new index is given a fresh try", rescanned.plan is FrontendMusicPlan.Folder)

        val otherFolder = resolve(failure = failure.copy(tree = "content://tree/other"))!!
        assertTrue("a failure is held against its own folder only", otherFolder.plan is FrontendMusicPlan.Folder)
    }

    @Test
    fun `nothing is decided while the chosen folder's index is still being read`() {
        assertNull(resolve(playlist = MusicPlaylist(tree, loaded = false)))
        assertNull("the index of a previous folder is not this folder's", resolve(playlist = playlist.copy(tree = "content://tree/old")))
        // Mimir's music never waits for the folder.
        assertEquals(FrontendMusicPlan.Bed(bed), resolve(source = MimirMusicSource.MIMIR, playlist = MusicPlaylist(tree, loaded = false))!!.plan)
    }

    @Test
    fun `a rescan in flight holds what is playing, and a rescan that finds the same tracks restarts nothing`() {
        assertNull("the decision lands when the scan finishes", resolve(library = library.copy(scanning = true)))
        assertNull(resolve(library = library.copy(scanning = true, error = MusicLibraryError.UNREADABLE)))
        // No folder is decided at once, scanning or not; Mimir's music never waits.
        assertEquals(CustomMusicFallback.NO_FOLDER, resolve(library = MusicLibraryState(), playlist = MusicPlaylist())!!.fallback)
        assertEquals(FrontendMusicPlan.Bed(bed), resolve(source = MimirMusicSource.MIMIR, library = library.copy(scanning = true))!!.plan)

        val before = FrontendMusicPlan.Folder(tree, 5L, tracks)
        assertTrue(FrontendMusicPlan.Folder(tree, 9L, tracks).playsTheSameAs(before))
        assertTrue(!FrontendMusicPlan.Folder(tree, 9L, tracks.drop(1)).playsTheSameAs(before))
        assertTrue(!FrontendMusicPlan.Folder("content://tree/other", 5L, tracks).playsTheSameAs(before))
        assertTrue(!before.playsTheSameAs(FrontendMusicPlan.Bed(bed)))
        assertTrue(!FrontendMusicPlan.Bed(bed).playsTheSameAs(FrontendMusicPlan.Bed(bed)))
        assertTrue(!before.playsTheSameAs(null))
    }

    @Test
    fun `a track is titled by its file name without the extension`() {
        assertEquals("a - Overworld", musicTrackTitle("a - Overworld.mp3"))
        assertEquals("Song.v2", musicTrackTitle("Song.v2.flac"))
        assertEquals("noext", musicTrackTitle("noext"))
        assertEquals(".mp3", musicTrackTitle(".mp3"))
    }

    @Test
    fun `now playing names the bed as Mimir and a folder track as Your music`() {
        val bedNow = FrontendMusicPlan.Bed(bed).nowPlayingAt(0)!!
        assertEquals(MimirMusicSource.MIMIR, bedNow.source)
        assertEquals(bed.title, bedNow.title)
        assertTrue(bedNow.singleTrack)

        val folder = FrontendMusicPlan.Folder(tree, 5L, tracks)
        val track = folder.nowPlayingAt(1)!!
        assertEquals("b", track.title)
        assertEquals(MimirMusicSource.CUSTOM, track.source)
        assertEquals("a folder with a next track skips", false, track.singleTrack)
        assertNull(folder.nowPlayingAt(7))

        assertTrue("a folder of one restarts", FrontendMusicPlan.Folder(tree, 5L, tracks.take(1)).nowPlayingAt(0)!!.singleTrack)
    }

    @Test
    fun `the next track follows index order and wraps`() {
        val folder = FrontendMusicPlan.Folder(tree, 5L, tracks)
        assertEquals(1, folder.nextCursor(0))
        assertEquals(2, folder.nextCursor(1))
        assertEquals(0, folder.nextCursor(2))
        assertEquals(0, FrontendMusicPlan.Folder(tree, 5L, tracks.take(1)).nextCursor(0))
        assertEquals(0, FrontendMusicPlan.Bed(bed).nextCursor(0))
    }

    // ── a pick from the Artist / Album tree ──────────────────────────────────────────────────────────

    private fun pick(vararg ids: String, start: String = ids.first(), serial: Long = 1L, on: String = tree) = MusicSelection(on, ids.toList(), start, serial)

    @Test
    fun `a pick plays its album in the album's order, opening on the track pressed, instead of the folder's own order`() {
        val plan = resolve(selection = pick("id-c", "id-a", start = "id-a"))!!.plan as FrontendMusicPlan.Folder
        assertEquals(listOf(tracks[2], tracks[0]), plan.tracks)
        assertEquals(MusicStart("id-a", 1L), plan.start)
        assertEquals(1, plan.startIndex())
    }

    @Test
    fun `a pick may reach a platform folder's track, which the folder's own order leaves alone`() {
        val plan = resolve(selection = pick("id-b"))!!.plan as FrontendMusicPlan.Folder
        assertEquals(listOf(tracks[1]), plan.tracks)
    }

    @Test
    fun `a pick whose track the index no longer holds is dropped, and the folder plays in its own order`() {
        val gone = resolve(selection = pick("id-a", "id-c", start = "id-zzz"))!!.plan as FrontendMusicPlan.Folder
        assertEquals(listOf(tracks[0], tracks[2]), gone.tracks)
        assertNull(gone.start)
        // Part of the album gone: the rest plays.
        val partly = resolve(selection = pick("id-a", "id-missing", "id-c", start = "id-c"))!!.plan as FrontendMusicPlan.Folder
        assertEquals(listOf(tracks[0], tracks[2]), partly.tracks)
        assertEquals(1, partly.startIndex())
    }

    @Test
    fun `a pick made in another folder is ignored`() {
        val plan = resolve(selection = pick("id-a", on = "content://tree/other"))!!.plan as FrontendMusicPlan.Folder
        assertNull(plan.start)
        assertEquals(listOf(tracks[0], tracks[2]), plan.tracks)
    }

    @Test
    fun `a pick never changes which source plays or why it cannot`() {
        assertEquals(FrontendMusicPlan.Bed(bed), resolve(source = MimirMusicSource.MIMIR, selection = pick("id-a"))!!.plan)
        assertEquals(CustomMusicFallback.EMPTY, resolve(playlist = playlist.copy(tracks = emptyList()), selection = pick("id-a"))!!.fallback)
    }

    @Test
    fun `a rescan keeps a pick without restarting it, a new pick of the same album is a jump and a new queue is a reload`() {
        val first = resolve(selection = pick("id-a", "id-c", start = "id-a", serial = 1L))!!.plan
        val rescanned = resolve(playlist = playlist.copy(scannedAtMillis = 9L), selection = pick("id-a", "id-c", start = "id-a", serial = 1L))!!.plan
        assertTrue(rescanned.playsTheSameAs(first))
        assertEquals((first as FrontendMusicPlan.Folder).start, (rescanned as FrontendMusicPlan.Folder).start)
        // Another track of the same album, or the same one pressed again: the queue is the same, the pick is new.
        val again = resolve(selection = pick("id-a", "id-c", start = "id-a", serial = 2L))!!.plan as FrontendMusicPlan.Folder
        assertTrue(again.playsTheSameAs(first))
        assertTrue(again.start != first.start)
        // A different album is a different queue.
        val other = resolve(selection = pick("id-b", serial = 3L))!!.plan
        assertTrue(!other.playsTheSameAs(first))
    }
}
