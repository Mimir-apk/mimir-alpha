package com.gruning.mimir.rewrite.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Artist / Album tree : who a track is filed under, "Unknown Artist" and "Unknown Album" only for a missing tag, the order an album plays in, and
 * keys that survive the rebuilds that tags arriving cause.
 */
class MusicTreeTest {
    private fun track(
        id: String,
        title: String = id,
        artist: String? = null,
        album: String? = null,
        albumArtist: String? = null,
        number: Int? = null,
        disc: Int? = null,
        art: Boolean = false,
        durationMs: Long? = null,
        file: String = "$id.mp3",
    ) = MusicCatalogTrack(
        id = id,
        fileName = file,
        folder = "",
        title = title,
        artist = artist,
        album = album,
        albumArtist = albumArtist,
        trackNumber = number,
        discNumber = disc,
        durationMs = durationMs,
        hasEmbeddedArt = art,
    )

    // ── who a track is filed under ───────────────────────────────────────────────────────────────────

    private fun compilationTrack(id: String, artist: String?, albumArtist: String? = null, album: String? = "Now That's What I Call Tests", compilation: Boolean = true) =
        track(id, artist = artist, album = album, albumArtist = albumArtist, number = id.filter { it.isDigit() }.toIntOrNull()).copy(compilation = compilation)

    @Test
    fun `a compilation with no album artist is one album under Various Artists, not one artist per track`() {
        val tree = buildMusicTree(
            listOf(
                compilationTrack("t1", artist = "Artist One"),
                compilationTrack("t2", artist = "Artist Two"),
                compilationTrack("t3", artist = "Artist Three"),
                track("s1", artist = "Solo", album = "Own Album"),
            ),
        )
        assertEquals(listOf("Solo", "Various Artists"), tree.artists.map { it.name })
        val various = tree.artist("various artists")!!
        assertFalse(various.isUnknown)
        assertEquals(listOf("Now That's What I Call Tests"), various.albums.map { it.name })
        assertEquals(listOf("t1", "t2", "t3"), various.albums.single().tracks.map { it.id })
    }

    @Test
    fun `an album artist tag wins over the compilation mark`() {
        val tree = buildMusicTree(
            listOf(
                compilationTrack("t1", artist = "Guest One", albumArtist = "The Host"),
                compilationTrack("t2", artist = "Guest Two", albumArtist = "The Host"),
            ),
        )
        assertEquals(listOf("The Host"), tree.artists.map { it.name })
    }

    @Test
    fun `a compilation tagged Various Artists and one only marked as a compilation are the same artist`() {
        val tree = buildMusicTree(
            listOf(
                compilationTrack("t1", artist = "A", albumArtist = "various artists", album = "Mixed One"),
                compilationTrack("t2", artist = "B", album = "Mixed Two"),
            ),
        )
        assertEquals(1, tree.artists.size)
        assertEquals(listOf("Mixed One", "Mixed Two"), tree.artists.single().albums.map { it.name })
    }

    @Test
    fun `a track not marked as a compilation is still filed under its own artist`() {
        val tree = buildMusicTree(listOf(compilationTrack("t1", artist = "Own", compilation = false)))
        assertEquals(listOf("Own"), tree.artists.map { it.name })
    }

    @Test
    fun `a track id listed twice is one track in the tree`() {
        val tree = buildMusicTree(
            listOf(
                track("dup", artist = "A", album = "X", number = 1),
                track("dup", artist = "A", album = "X", number = 1),
                track("ok", artist = "A", album = "X", number = 2),
            ),
        )
        assertEquals(2, tree.trackCount)
        assertEquals(listOf("dup", "ok"), tree.artists.single().albums.single().tracks.map { it.id })
    }

    @Test
    fun `an artist holds albums and an album holds tracks`() {
        val tree = buildMusicTree(
            listOf(
                track("a1", artist = "Alpha", album = "First", number = 1),
                track("a2", artist = "Alpha", album = "First", number = 2),
                track("a3", artist = "Alpha", album = "Second", number = 1),
                track("b1", artist = "Beta", album = "Only", number = 1),
            ),
        )
        assertEquals(listOf("Alpha", "Beta"), tree.artists.map { it.name })
        assertEquals(listOf("First", "Second"), tree.artists[0].albums.map { it.name })
        assertEquals(listOf("a1", "a2"), tree.artists[0].albums[0].tracks.map { it.id })
        assertEquals(4, tree.trackCount)
        assertEquals(3, tree.artists[0].trackCount)
        assertFalse(tree.isEmpty)
        assertTrue(buildMusicTree(emptyList()).isEmpty)
    }

    @Test
    fun `the album artist files a track, the artist tag is the fallback, and a missing tag is Unknown Artist`() {
        val tree = buildMusicTree(
            listOf(
                track("c1", artist = "Guest One", albumArtist = "Various Artists", album = "Compilation", number = 1),
                track("c2", artist = "Guest Two", albumArtist = "Various Artists", album = "Compilation", number = 2),
                track("s1", artist = "Solo", album = "Record", number = 1),
                track("n1", album = "Orphan", number = 1),
            ),
        )
        val names = tree.artists.map { it.name }
        assertEquals(listOf("Solo", "Various Artists", UnknownArtistName), names)
        assertEquals("one album, both guests, under the album artist", 1, tree.artists[1].albums.size)
        assertEquals(2, tree.artists[1].albums[0].tracks.size)
        val unknown = tree.artists.last()
        assertTrue(unknown.isUnknown)
        assertFalse(tree.artists.first().isUnknown)
        assertEquals("a track with an album tag but no artist keeps its album", "Orphan", unknown.albums.single().name)
    }

    @Test
    fun `a missing album tag is Unknown Album, and an album that is really called that is an ordinary album`() {
        val tree = buildMusicTree(
            listOf(
                track("a", artist = "Alpha", number = 1),
                track("b", artist = "Alpha", album = "Real", number = 1),
                track("c", artist = "Alpha", album = UnknownAlbumName, number = 1),
            ),
        )
        val albums = tree.artists.single().albums
        assertEquals(listOf("Real", UnknownAlbumName, UnknownAlbumName), albums.map { it.name })
        assertEquals("the album the tag called Unknown Album is a real one, sorted among the others", listOf(false, false, true), albums.map { it.isUnknown })
        assertEquals("and the two do not merge", 3, albums.size)
        assertNull("unknown has no tag to fold", albums.last().tracks.single().album)
    }

    @Test
    fun `an artist the tags spell with other capitals or spaces is one artist, in the first spelling met`() {
        val tree = buildMusicTree(
            listOf(
                track("1", artist = "ABBA", album = "Arrival"),
                track("2", artist = "  Abba ", album = "arrival"),
                track("3", artist = "A B B A", album = "Arrival"),
            ),
        )
        assertEquals(listOf("A B B A", "ABBA"), tree.artists.map { it.name })
        assertEquals("one album, both tracks", 2, tree.artists[1].albums.single().tracks.size)
        assertEquals("Arrival", tree.artists[1].albums.single().name)
    }

    @Test
    fun `blank tags are missing tags`() {
        val tree = buildMusicTree(listOf(track("x", artist = "   ", album = "", albumArtist = " ")))
        assertEquals(UnknownArtistName, tree.artists.single().name)
        assertEquals(UnknownAlbumName, tree.artists.single().albums.single().name)
    }

    // ── the order an album plays in ──────────────────────────────────────────────────────────────────

    @Test
    fun `tracks play by disc, then track number, then title`() {
        val tree = buildMusicTree(
            listOf(
                track("d2t1", title = "Disc Two One", artist = "A", album = "Box", number = 1, disc = 2),
                track("d1t2", title = "Disc One Two", artist = "A", album = "Box", number = 2, disc = 1),
                track("d1t10", title = "Disc One Ten", artist = "A", album = "Box", number = 10, disc = 1),
                track("d1t1", title = "Disc One One", artist = "A", album = "Box", number = 1, disc = 1),
                track("nodisc", title = "No Disc Tag", artist = "A", album = "Box", number = 3),
            ),
        )
        val album = tree.artists.single().albums.single()
        // A track with no disc tag is on disc 1.
        assertEquals(listOf("d1t1", "d1t2", "nodisc", "d1t10", "d2t1"), album.tracks.map { it.id })
        assertEquals(2, album.discCount)
    }

    @Test
    fun `tracks without a number come after the numbered ones, by title`() {
        val tree = buildMusicTree(
            listOf(
                track("z", title = "Zebra", artist = "A", album = "B"),
                track("n2", title = "Second", artist = "A", album = "B", number = 2),
                track("a", title = "apple", artist = "A", album = "B"),
                track("n1", title = "First", artist = "A", album = "B", number = 1),
            ),
        )
        assertEquals(listOf("n1", "n2", "a", "z"), tree.artists.single().albums.single().tracks.map { it.id })
    }

    @Test
    fun `two tracks that tie on everything keep a fixed order`() {
        val a = track("id-a", title = "Same", artist = "A", album = "B", number = 1, file = "x.mp3")
        val b = track("id-b", title = "Same", artist = "A", album = "B", number = 1, file = "x.mp3")
        assertEquals(tracksOf(buildMusicTree(listOf(a, b))), tracksOf(buildMusicTree(listOf(b, a))))
    }

    @Test
    fun `artists and albums are in name order with numbers by value, and the unknown ones last`() {
        val tree = buildMusicTree(
            listOf(
                track("1", artist = "zeta", album = "Album 10"),
                track("2", artist = "Alpha", album = "Album 2"),
                track("3", artist = "Alpha", album = "Album 10"),
                track("4", artist = "Alpha"),
                track("5", artist = "Alpha", album = "Album 1"),
                track("6", album = "Lonely"),
                track("7", artist = "beta"),
            ),
        )
        assertEquals(listOf("Alpha", "beta", "zeta", UnknownArtistName), tree.artists.map { it.name })
        assertEquals(listOf("Album 1", "Album 2", "Album 10", UnknownAlbumName), tree.artists[0].albums.map { it.name })
    }

    @Test
    fun `the natural order compares digit runs by value and the rest by letter`() {
        assertTrue(MusicNames.compare("album 2", "album 10") < 0)
        assertEquals("leading zeros do not change the value", 0, MusicNames.compare("album 02", "album 2"))
        assertTrue(MusicNames.compare("a", "b") < 0)
        assertTrue(MusicNames.compare("abc", "ab") > 0)
        assertEquals(0, MusicNames.compare("same", "same"))
        assertTrue(MusicNames.compare("track 9", "track 10") < 0)
    }

    // ── keys and art ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a group keeps its key when other tracks arrive around it`() {
        val before = buildMusicTree(listOf(track("1", artist = "Gamma", album = "Keep")))
        val after = buildMusicTree(
            listOf(
                track("0", artist = "Aardvark", album = "New"),
                track("1", artist = "Gamma", album = "Keep"),
                track("2", artist = "gamma", album = "KEEP", number = 2),
            ),
        )
        assertEquals(before.artists.single().key, after.artist(before.artists.single().key)?.key)
        assertEquals(before.artists.single().albums.single().key, after.artist("gamma")?.album("keep")?.key)
        assertNull(after.artist("nobody"))
        assertNull(after.artist(null))
    }

    @Test
    fun `the art of a group is its first track known to carry a picture`() {
        val tree = buildMusicTree(
            listOf(
                track("1", artist = "A", album = "One", number = 1),
                track("2", artist = "A", album = "One", number = 2, art = true),
                track("3", artist = "A", album = "Two", number = 1, art = true),
                track("4", artist = "B", album = "Bare", number = 1),
            ),
        )
        val a = tree.artists[0]
        assertEquals("2", a.albums[0].artTrackId)
        assertEquals("the artist takes its first album's", "2", a.artTrackId)
        assertNull(tree.artists[1].artTrackId)
    }

    @Test
    fun `an album knows its length when its tracks do`() {
        val tree = buildMusicTree(
            listOf(
                track("1", artist = "A", album = "One", durationMs = 60_000L),
                track("2", artist = "A", album = "One", durationMs = 125_000L),
                track("3", artist = "A", album = "Two"),
            ),
        )
        assertEquals(185_000L, tree.artists.single().albums[0].totalDurationMs)
        assertNull(tree.artists.single().albums[1].totalDurationMs)
    }

    @Test
    fun `the queue behind a pick is its album, found by the track`() {
        val tree = buildMusicTree(
            listOf(
                track("b1", artist = "B", album = "X", number = 1),
                track("a2", artist = "A", album = "Y", number = 2),
                track("a1", artist = "A", album = "Y", number = 1),
            ),
        )
        assertEquals(listOf("a1", "a2"), tree.albumOf("a2")?.tracks?.map { it.id })
        assertEquals(listOf("b1"), tree.albumOf("b1")?.tracks?.map { it.id })
        assertNull(tree.albumOf("gone"))
    }

    private fun tracksOf(tree: MusicTree): List<String> = tree.artists.flatMap { artist -> artist.albums.flatMap { album -> album.tracks.map { it.id } } }
}
