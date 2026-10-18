package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.audio.MusicCatalogTrack
import com.gruning.mimir.rewrite.core.audio.UnknownArtistName
import com.gruning.mimir.rewrite.core.audio.buildMusicTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Artist / Album browser's pure side : its rows, its cursor, and what a press and Back do at each level. */
class MusicBrowseModelTest {
    private fun track(
        id: String,
        artist: String? = null,
        album: String? = null,
        title: String = id,
        number: Int? = null,
        disc: Int? = null,
        durationMs: Long? = null,
        albumArtist: String? = null,
        art: Boolean = false,
    ) = MusicCatalogTrack(
        id = id,
        fileName = "$id.mp3",
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

    private val tree = buildMusicTree(
        listOf(
            track("a1", "Alpha", "First", "Sweep", number = 1, durationMs = 6_000L, art = true),
            track("a2", "Alpha", "First", "Tone", number = 2, durationMs = 3_725_000L),
            track("a3", "Alpha", "Second", "Noise", number = 1),
            track("b1", "Beta", "Only", "Single", number = 1, durationMs = 61_000L),
            track("u1", null, null, "Loose"),
        ),
    )

    private val artists = MusicBrowseState().resolved(tree)

    // ── the rows ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the top level lists the artists with their counts, the unknown one last`() {
        val rows = musicBrowseRows(tree, artists, nowPlayingId = null).also { assertEquals(MusicBrowseAllKey, it.first().key) }.drop(1)
        assertEquals(listOf("Alpha", "Beta", UnknownArtistName), rows.map { it.title })
        assertEquals("2 albums · 3 tracks", rows[0].subtitle)
        assertEquals("1 album · 1 track", rows[1].subtitle)
        assertTrue(rows.all { it.opens && it.trailing == null && it.number == null })
        assertEquals("the artist's picture is its first album's", "a1", rows[0].artTrackId)
        assertNull(rows[1].artTrackId)
    }

    @Test
    fun `an artist's albums say how many tracks and how long`() {
        val rows = musicBrowseRows(tree, MusicBrowseState(MusicBrowseLevel.ALBUMS, artistKey = "alpha"), null)
        assertEquals(listOf("First", "Second"), rows.map { it.title })
        assertEquals("2 tracks · 1:02:11", rows[0].subtitle)
        assertEquals("an album whose tracks have no length says only the count", "1 track", rows[1].subtitle)
    }

    @Test
    fun `an album's tracks are numbered, timed and ordered, and the track on air is marked`() {
        val state = MusicBrowseState(MusicBrowseLevel.TRACKS, "alpha", "first")
        val rows = musicBrowseRows(tree, state, nowPlayingId = "a2")
        assertEquals(listOf("Sweep", "Tone"), rows.map { it.title })
        assertEquals(listOf("1", "2"), rows.map { it.number })
        assertEquals(listOf("0:06", "1:02:05"), rows.map { it.trailing })
        assertEquals(listOf(false, true), rows.map { it.playing })
        assertTrue(rows.none { it.opens })
        assertEquals("a track with a picture offers it", "a1", rows[0].artTrackId)
        assertNull(rows[1].artTrackId)
    }

    @Test
    fun `a multi-disc album numbers its tracks by disc and track`() {
        val box = buildMusicTree(
            listOf(
                track("1", "A", "Box", number = 1, disc = 1),
                track("2", "A", "Box", number = 5, disc = 2),
                track("3", "A", "Box", number = 6),
            ),
        )
        val rows = musicBrowseRows(box, MusicBrowseState(MusicBrowseLevel.TRACKS, "a", "box"), null)
        assertEquals(listOf("1-01", "1-06", "2-05"), rows.map { it.number })
    }

    @Test
    fun `a compilation's track names its own artist when it is not the album's`() {
        val compilation = buildMusicTree(
            listOf(
                track("1", "Guest One", "Hits", number = 1, albumArtist = "Various Artists"),
                track("2", "Various Artists", "Hits", number = 2, albumArtist = "Various Artists"),
                track("3", null, "Hits", number = 3, albumArtist = "Various Artists"),
            ),
        )
        val rows = musicBrowseRows(compilation, MusicBrowseState(MusicBrowseLevel.TRACKS, "various artists", "hits"), null)
        assertEquals(listOf("Guest One", null, null), rows.map { it.subtitle })
        assertTrue(rows[0].subtitleAuthored)
    }

    @Test
    fun `counts and lengths are written the way the rows show them`() {
        assertEquals("1 album", musicBrowseCount(1, "album"))
        assertEquals("0 albums", musicBrowseCount(0, "album"))
        assertEquals("12 tracks", musicBrowseCount(12, "track"))
        assertEquals("0:06", musicBrowseDuration(6_000L))
        assertEquals("rounds to the nearest second", "0:07", musicBrowseDuration(6_600L))
        assertEquals("3:45", musicBrowseDuration(225_000L))
        assertEquals("1:00:00", musicBrowseDuration(3_600_000L))
        assertNull(musicBrowseDuration(null))
        assertNull(musicBrowseDuration(0L))
    }

    // ── the screen's model ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `the top shows the level word only, and a level below it names its group`() {
        val top = musicBrowseUi(tree, artists, null, tagsRead = 5, trackCount = 5)
        assertNull(top.context)
        assertFalse(top.canGoBack)
        assertNull(top.reading)
        assertNull(top.empty)
        val albums = musicBrowseUi(tree, MusicBrowseState(MusicBrowseLevel.ALBUMS, "alpha"), null, 5, 5)
        assertEquals("Alpha", albums.context)
        assertEquals("Albums", albums.level.eyebrow)
        assertTrue(albums.canGoBack)
        val tracks = musicBrowseUi(tree, MusicBrowseState(MusicBrowseLevel.TRACKS, "alpha", "first"), null, 5, 5)
        assertEquals("First", tracks.context)
        assertEquals("Alpha", tracks.contextSub)
    }

    @Test
    fun `while tags are still being read the top says how far they are`() {
        assertEquals("Reading tags 120 of 446", musicBrowseUi(tree, artists, null, tagsRead = 120, trackCount = 446).reading)
        assertNull(musicBrowseUi(tree, artists, null, tagsRead = 446, trackCount = 446).reading)
        assertNull("no tracks, nothing to read", musicBrowseUi(buildMusicTree(emptyList()), MusicBrowseState(), null, 0, 0).reading)
    }

    @Test
    fun `an empty library says so`() {
        val ui = musicBrowseUi(buildMusicTree(emptyList()), MusicBrowseState(), null, 0, 0)
        assertEquals(MusicBrowseEmptyCopy, ui.empty)
        assertTrue(ui.rows.isEmpty())
    }

    // ── the cursor ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a state is made true to its tree and starts on the first row`() {
        assertEquals("the first row is All music", MusicBrowseAllKey, artists.selectedKey)
        assertEquals("a cursor on a row that exists stays", "beta", MusicBrowseState(selectedKey = "beta").resolved(tree).selectedKey)
        assertEquals("one that is gone falls to the first", MusicBrowseAllKey, MusicBrowseState(selectedKey = "gone").resolved(tree).selectedKey)
    }

    @Test
    fun `an artist that has gone from the tree pops the browser up to the top, keeping the cursor on a row`() {
        val gone = MusicBrowseState(MusicBrowseLevel.TRACKS, "vanished", "x", "a1").resolved(tree)
        assertEquals(MusicBrowseLevel.ARTISTS, gone.level)
        assertEquals(MusicBrowseAllKey, gone.selectedKey)
        val album = MusicBrowseState(MusicBrowseLevel.TRACKS, "alpha", "vanished", "a1").resolved(tree)
        assertEquals(MusicBrowseLevel.ALBUMS, album.level)
        assertEquals("alpha", album.artistKey)
        assertEquals("first", album.selectedKey)
    }

    @Test
    fun `the cursor moves a row at a time and stops at both ends`() {
        val rows = musicBrowseRows(tree, artists, null)
        val down = artists.moved(rows, 2)
        assertEquals("All music, then Alpha, then Beta", "beta", down.selectedKey)
        assertEquals(UnknownArtistKey, down.moved(rows, 1).selectedKey)
        assertEquals("no wrap past the last row", UnknownArtistKey, down.moved(rows, 1).moved(rows, 1).selectedKey)
        assertEquals("no wrap past the first", MusicBrowseAllKey, artists.moved(rows, -1).selectedKey)
        assertEquals("an empty list has nowhere to go", artists, artists.moved(emptyList(), 1))
    }

    // ── a press, and Back ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a press on an artist opens its albums, on an album its tracks, and on a track plays it`() {
        val toAlbums = artists.pressed(tree, "alpha", nowPlayingId = null) as MusicBrowseAction.Go
        assertEquals(MusicBrowseLevel.ALBUMS, toAlbums.state.level)
        assertEquals("alpha", toAlbums.state.artistKey)
        assertEquals("first", toAlbums.state.selectedKey)

        val toTracks = toAlbums.state.pressed(tree, "first", nowPlayingId = null) as MusicBrowseAction.Go
        assertEquals(MusicBrowseLevel.TRACKS, toTracks.state.level)
        assertEquals("a1", toTracks.state.selectedKey)

        assertEquals(MusicBrowseAction.Play("a2"), toTracks.state.pressed(tree, "a2", nowPlayingId = null))
    }

    @Test
    fun `All music is a press that plays everything, and opens nothing`() {
        assertEquals(MusicBrowseAction.PlayAll, artists.pressed(tree, MusicBrowseAllKey, nowPlayingId = "a2"))
    }

    @Test
    fun `an album that holds the track on air opens with the cursor on it`() {
        val albums = MusicBrowseState(MusicBrowseLevel.ALBUMS, "alpha", selectedKey = "first")
        val go = albums.pressed(tree, "first", nowPlayingId = "a2") as MusicBrowseAction.Go
        assertEquals("a2", go.state.selectedKey)
        val elsewhere = albums.pressed(tree, "first", nowPlayingId = "b1") as MusicBrowseAction.Go
        assertEquals("a1", elsewhere.state.selectedKey)
    }

    @Test
    fun `a press on nothing, or on a row the tree no longer has, does nothing`() {
        assertEquals(MusicBrowseAction.None, artists.pressed(tree, null, null))
        assertEquals(MusicBrowseAction.None, artists.pressed(tree, "nobody", null))
        assertEquals(MusicBrowseAction.None, MusicBrowseState(MusicBrowseLevel.ALBUMS, "alpha").pressed(tree, "nothing", null))
        assertEquals(MusicBrowseAction.None, MusicBrowseState(MusicBrowseLevel.TRACKS, "alpha", "first").pressed(tree, "b1", null))
    }

    @Test
    fun `Back goes up one level with the cursor on the group the player came from, and from the top it closes`() {
        val tracks = MusicBrowseState(MusicBrowseLevel.TRACKS, "alpha", "second", "a3")
        val albums = tracks.back()!!
        assertEquals(MusicBrowseLevel.ALBUMS, albums.level)
        assertEquals("alpha", albums.artistKey)
        assertEquals("second", albums.selectedKey)
        val top = albums.back()!!
        assertEquals(MusicBrowseLevel.ARTISTS, top.level)
        assertEquals("alpha", top.selectedKey)
        assertNull(top.back())
    }

    // ── All music, and the row that is picked (the policy, 10-09) ────────────────────────────

    @Test
    fun `All music is the first row of Artists only, with the track count, and is the panel's word and not a name`() {
        val all = musicBrowseRows(tree, artists, null).first()
        assertEquals(MusicBrowseAllKey, all.key)
        assertEquals("All music", all.title)
        assertFalse("not an artist's name: the theme draws it as it draws a control", all.titleAuthored)
        assertEquals("5 tracks", all.subtitle)
        assertFalse("a press plays; it opens nothing", all.opens)
        assertNull(all.artTrackId)
        assertTrue("no artist's key can equal it", musicBrowseRows(tree, artists, null).drop(1).none { it.key == MusicBrowseAllKey })
        assertTrue("a key a cleaned name can never produce", MusicBrowseAllKey.contains('\n'))
        assertTrue(musicBrowseRows(tree, MusicBrowseState(MusicBrowseLevel.ALBUMS, "alpha"), null).none { it.key == MusicBrowseAllKey })
        assertTrue(musicBrowseRows(tree, MusicBrowseState(MusicBrowseLevel.TRACKS, "alpha", "first"), null).none { it.key == MusicBrowseAllKey })
    }

    @Test
    fun `an empty library has no All music either`() {
        assertTrue(musicBrowseRows(buildMusicTree(emptyList()), MusicBrowseState(), null).isEmpty())
    }

    @Test
    fun `with no pick All music is the picked row, and no artist is`() {
        val rows = musicBrowseRows(tree, artists, null, pickedTrackId = null)
        assertEquals(listOf(true, false, false, false), rows.map { it.picked })
    }

    @Test
    fun `a pick marks its artist and its album, and All music lets go`() {
        val top = musicBrowseRows(tree, artists, null, pickedTrackId = "a2")
        assertEquals(listOf(MusicBrowseAllKey to false, "alpha" to true, "beta" to false, UnknownArtistKey to false), top.map { it.key to it.picked })

        val albums = musicBrowseRows(tree, MusicBrowseState(MusicBrowseLevel.ALBUMS, "alpha"), null, pickedTrackId = "a2")
        assertEquals(listOf("first" to true, "second" to false), albums.map { it.key to it.picked })

        val elsewhere = musicBrowseRows(tree, MusicBrowseState(MusicBrowseLevel.ALBUMS, "beta"), null, pickedTrackId = "a2")
        assertEquals("another artist's albums are not the pick", listOf(false), elsewhere.map { it.picked })

        val tracks = musicBrowseRows(tree, MusicBrowseState(MusicBrowseLevel.TRACKS, "alpha", "first"), "a1", pickedTrackId = "a2")
        assertTrue("the tracks are marked by what is on air, not by the pick", tracks.none { it.picked })
    }

    @Test
    fun `two artists with an album of the same name, only the picked artist's is marked`() {
        val twins = buildMusicTree(
            listOf(
                track("x1", "Xena", "Greatest Hits", number = 1),
                track("y1", "Yara", "Greatest Hits", number = 1),
            ),
        )
        val rows = musicBrowseRows(twins, MusicBrowseState(MusicBrowseLevel.ALBUMS, "yara"), null, pickedTrackId = "y1")
        assertEquals(listOf(true), rows.map { it.picked })
        assertEquals(listOf(false), musicBrowseRows(twins, MusicBrowseState(MusicBrowseLevel.ALBUMS, "xena"), null, pickedTrackId = "y1").map { it.picked })
    }

    @Test
    fun `a pick whose track the tree no longer holds is no pick`() {
        val rows = musicBrowseRows(tree, artists, null, pickedTrackId = "gone-after-a-rescan")
        assertEquals("All music is the selected row again", listOf(true, false, false, false), rows.map { it.picked })
    }

    @Test
    fun `the screen's model carries the pick through`() {
        val ui = musicBrowseUi(tree, artists, null, tagsRead = 5, trackCount = 5, pickedTrackId = "b1")
        assertEquals(listOf(MusicBrowseAllKey, "alpha", "beta", UnknownArtistKey), ui.rows.map { it.key })
        assertEquals("beta", ui.rows.single { it.picked }.key)
    }

    @Test
    fun `a row key appears once however the tags repeat`() {
        // The same id twice (a provider that listed it twice, an index a loop wrote): the tree drops the repeat, and the rows could not key a list twice.
        val repeated = buildMusicTree(listOf(track("dup", "A", "X", number = 1), track("dup", "A", "X", number = 1), track("ok", "A", "X", number = 2)))
        val rows = musicBrowseRows(repeated, MusicBrowseState(MusicBrowseLevel.TRACKS, "a", "x"), null)
        assertEquals(listOf("dup", "ok"), rows.map { it.key })
        assertEquals(rows.map { it.key }.toSet().size, rows.size)
    }

    private companion object {
        /** The key of the group a missing tag falls in. */
        const val UnknownArtistKey = ""
    }
}
