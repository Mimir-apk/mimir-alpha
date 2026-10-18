package com.gruning.mimir.rewrite.core.audio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reading tags : the retriever's strings turned into tags, the cache that keeps a rescan from reading an unchanged file again, the pass that
 * reads the rest in batches, the index file that remembers it all, and the rule that a tag arriving never restarts the music.
 */
class MusicTagsTest {
    // ── the retriever's strings ──────────────────────────────────────────────────────────────────────

    @Test
    fun `a tag is its trimmed text, and a blank or missing one is no tag`() {
        assertEquals("Song", MusicTagParse.text("  Song \n"))
        assertNull(MusicTagParse.text(null))
        assertNull(MusicTagParse.text(""))
        assertNull(MusicTagParse.text("   "))
        assertEquals("a stray NUL from a bad encoder is dropped", "AB", MusicTagParse.text("A\u0000B"))
        assertNull(MusicTagParse.text("\u0000"))
        assertEquals("accents, Japanese and emoji pass through", "夜に駆ける – Café 🎵", MusicTagParse.text("夜に駆ける – Café 🎵"))
    }

    @Test
    fun `a track or disc number is the number before a slash, and nothing else is a number`() {
        assertEquals(3, MusicTagParse.number("3"))
        assertEquals(3, MusicTagParse.number("03"))
        assertEquals(3, MusicTagParse.number("3/12"))
        assertEquals(3, MusicTagParse.number(" 3 / 12 "))
        assertNull("zero is not a track", MusicTagParse.number("0"))
        assertNull(MusicTagParse.number("-2"))
        assertNull(MusicTagParse.number("/12"))
        assertNull(MusicTagParse.number("three"))
        assertNull(MusicTagParse.number(""))
        assertNull(MusicTagParse.number(null))
    }

    @Test
    fun `a duration is a positive count of milliseconds`() {
        assertEquals(6_013L, MusicTagParse.durationMs("6013"))
        assertEquals(6_013L, MusicTagParse.durationMs(" 6013 "))
        assertNull(MusicTagParse.durationMs("0"))
        assertNull(MusicTagParse.durationMs("-4"))
        assertNull(MusicTagParse.durationMs("6.5"))
        assertNull(MusicTagParse.durationMs(null))
    }

    @Test
    fun `the compilation flag is the platform's one, and anything but a yes is no`() {
        assertTrue(MusicTagParse.compilation("1"))
        assertTrue(MusicTagParse.compilation(" 1 "))
        assertTrue(MusicTagParse.compilation("true"))
        assertTrue(MusicTagParse.compilation("Yes"))
        assertFalse(MusicTagParse.compilation("0"))
        assertFalse(MusicTagParse.compilation(""))
        assertFalse(MusicTagParse.compilation("2"))
        assertFalse(MusicTagParse.compilation(null))
        assertEquals("the platform's METADATA_KEY_COMPILATION", 15, MusicTagParse.KeyCompilation)
        assertTrue(MusicTagParse.tags { if (it == MusicTagParse.KeyCompilation) "1" else null }.compilation)
        assertFalse(MusicTagParse.tags { null }.compilation)
    }

    @Test
    fun `the seven tags are read by the platform's keys, each independently`() {
        val raw = mapOf(
            MusicTagParse.KeyTitle to " Sweep ",
            MusicTagParse.KeyArtist to "Cert Artist Alpha",
            MusicTagParse.KeyAlbum to "Alpha Album (2021)",
            MusicTagParse.KeyAlbumArtist to "",
            MusicTagParse.KeyTrackNumber to "1/10",
            MusicTagParse.KeyDiscNumber to "2/2",
            MusicTagParse.KeyDuration to "6000",
        )
        assertEquals(
            MusicTags(
                title = "Sweep",
                artist = "Cert Artist Alpha",
                album = "Alpha Album (2021)",
                albumArtist = null,
                trackNumber = 1,
                discNumber = 2,
                durationMs = 6_000L,
            ),
            MusicTagParse.tags { raw[it] },
        )
        assertEquals("a file with no tags at all reads as empty tags", MusicTags(), MusicTagParse.tags { null })
    }

    // ── the display title falls back to the file name ────────────────────────────────────────────────

    private val track = MusicTrack("", "06 - No Title Tag.mp3", "id-6")

    @Test
    fun `a track is called by its title tag, and by its file name without the extension when it has none`() {
        assertEquals("06 - No Title Tag", musicDisplayTitle(track, null))
        assertEquals("06 - No Title Tag", musicDisplayTitle(track, MusicTrackMeta(state = MusicTagState.READ, tags = MusicTags(artist = "A"))))
        assertEquals("Real Title", musicDisplayTitle(track, MusicTrackMeta(state = MusicTagState.READ, tags = MusicTags(title = "Real Title"))))
    }

    @Test
    fun `the media session label has the artist tag, else the album artist, else none`() {
        fun label(tags: MusicTags) = yourMusicLabel(track, MusicTrackMeta(state = MusicTagState.READ, tags = tags))
        assertEquals(YourMusicLabel("T", "A", "Al"), label(MusicTags(title = "T", artist = "A", albumArtist = "AA", album = "Al")))
        assertEquals(YourMusicLabel("T", "AA", null), label(MusicTags(title = "T", albumArtist = "AA")))
        assertEquals(YourMusicLabel("06 - No Title Tag", null, null), label(MusicTags()))
    }

    // ── the cache: a rescan reads only what is new or changed ────────────────────────────────────────

    private fun stamp(size: Long, time: Long) = MusicFileStamp(size, time)

    private fun fresh(vararg files: Triple<String, Long, Long>): MusicIndex {
        val tracks = files.map { MusicTrack("", "${it.first}.mp3", it.first) }
        val meta = files.associate { it.first to MusicTrackMeta(stamp = stamp(it.second, it.third)) }
        return MusicIndex("t", 2L, tracks, meta)
    }

    private fun read(title: String, size: Long, time: Long, art: Boolean = false) =
        MusicTrackMeta(stamp(size, time), MusicTagState.READ, MusicTags(title = title), art)

    @Test
    fun `an unchanged file keeps what was read, a changed or new one is read again`() {
        val previous = mapOf(
            "same" to read("Same", 100, 5),
            "grew" to read("Grew", 100, 5),
            "touched" to read("Touched", 100, 5),
            "gone" to read("Gone", 1, 1),
        )
        val merged = MusicTagCache.carryOver(
            previous,
            fresh(Triple("same", 100, 5), Triple("grew", 101, 5), Triple("touched", 100, 6), Triple("new", 7, 7)),
        )
        assertEquals("Same", merged.meta.getValue("same").tags.title)
        assertEquals(MusicTagState.READ, merged.meta.getValue("same").state)
        assertEquals("a changed size", MusicTagState.UNREAD, merged.meta.getValue("grew").state)
        assertEquals("a changed time", MusicTagState.UNREAD, merged.meta.getValue("touched").state)
        assertEquals(MusicTagState.UNREAD, merged.meta.getValue("new").state)
        assertEquals("a changed file keeps the NEW stamp", stamp(101, 5), merged.meta.getValue("grew").stamp)
        assertFalse("a file that is gone leaves the cache", "gone" in merged.meta)
        assertEquals(listOf("grew", "touched", "new"), MusicTagCache.pending(merged).map { it.id })
    }

    @Test
    fun `a file that failed to read is not tried again until it changes`() {
        val failed = MusicTrackMeta(stamp(10, 10), MusicTagState.FAILED)
        val kept = MusicTagCache.carryOver(mapOf("bad" to failed), fresh(Triple("bad", 10, 10)))
        assertEquals(MusicTagState.FAILED, kept.meta.getValue("bad").state)
        assertTrue(MusicTagCache.pending(kept).isEmpty())
        val changed = MusicTagCache.carryOver(mapOf("bad" to failed), fresh(Triple("bad", 11, 10)))
        assertEquals(listOf("bad"), MusicTagCache.pending(changed).map { it.id })
    }

    @Test
    fun `a stamp with an unknown part proves nothing, so that file is read again on each rescan`() {
        val before = mapOf("odd" to read("Odd", -1, 0))
        val merged = MusicTagCache.carryOver(before, fresh(Triple("odd", -1, 0)))
        assertEquals(MusicTagState.UNREAD, merged.meta.getValue("odd").state)
        assertFalse(stamp(-1, 5).complete)
        assertFalse(stamp(5, 0).complete)
        assertTrue(stamp(0, 1).complete)
    }

    @Test
    fun `the result of a read is laid over the stamp, and an unreachable file is left as it was`() {
        val base = MusicTrackMeta(stamp(3, 4))
        val readOne = MusicTagCache.applied(base, MusicTagResult.Read(MusicTags(title = "T"), hasEmbeddedArt = true))
        assertEquals(MusicTrackMeta(stamp(3, 4), MusicTagState.READ, MusicTags(title = "T"), true), readOne)
        assertEquals(MusicTrackMeta(stamp(3, 4), MusicTagState.FAILED), MusicTagCache.applied(readOne, MusicTagResult.Failed))
        assertEquals(base, MusicTagCache.applied(base, MusicTagResult.Unavailable))
    }

    // ── the pass that reads what is left ─────────────────────────────────────────────────────────────

    private class FakeReader(val answers: (MusicTagSource) -> MusicTagResult) : MusicTagReader {
        val calls = AtomicInteger()

        override fun read(source: MusicTagSource): MusicTagResult {
            calls.incrementAndGet()
            return answers(source)
        }
    }

    private fun tracks(count: Int) = (1..count).map { MusicTrack("", "t$it.mp3", "id$it") }

    @Test
    fun `the pass reads every pending file and reports them in index order, in batches`() {
        val list = tracks(MusicTagPass.BatchSize * 2 + 5)
        val reader = FakeReader { source -> MusicTagResult.Read(MusicTags(title = (source as MusicTagSource.Path).absolutePath), false) }
        val batches = ArrayList<List<MusicTrack>>()
        val reached = runBlocking {
            MusicTagPass.run(list, { MusicTagSource.Path(it.id) }, reader) { batch -> batches += batch.map { it.first } }
        }
        assertTrue(reached)
        assertEquals(list.size, reader.calls.get())
        assertEquals(listOf(MusicTagPass.BatchSize, MusicTagPass.BatchSize, 5), batches.map { it.size })
        assertEquals("in order across batches", list, batches.flatten())
    }

    @Test
    fun `a file whose read throws is a failed file and the pass carries on`() {
        val list = tracks(3)
        val reader = FakeReader { source ->
            if ((source as MusicTagSource.Path).absolutePath == "id2") error("boom with a path in the message /storage/emulated/0/Music") else MusicTagResult.Read(MusicTags(), false)
        }
        val seen = ArrayList<Pair<String, MusicTagResult>>()
        val reached = runBlocking {
            MusicTagPass.run(list, { MusicTagSource.Path(it.id) }, reader) { batch -> seen += batch.map { it.first.id to it.second } }
        }
        assertTrue(reached)
        assertEquals(MusicTagResult.Failed, seen.single { it.first == "id2" }.second)
        assertEquals(3, seen.size)
    }

    @Test
    fun `an unreachable folder stops the pass and nothing after it is recorded`() {
        val list = tracks(10)
        val reader = FakeReader { source ->
            if ((source as MusicTagSource.Path).absolutePath == "id4") MusicTagResult.Unavailable else MusicTagResult.Read(MusicTags(), false)
        }
        val recorded = ArrayList<String>()
        val reached = runBlocking {
            MusicTagPass.run(list, { MusicTagSource.Path(it.id) }, reader) { batch -> recorded += batch.map { it.first.id } }
        }
        assertFalse(reached)
        assertEquals("the files before the unreachable one are kept; it and the rest stay unread", listOf("id1", "id2", "id3"), recorded)
    }

    @Test
    fun `a batch callback that cancels ends the pass`() {
        val list = tracks(MusicTagPass.BatchSize * 3)
        val reader = FakeReader { MusicTagResult.Read(MusicTags(), false) }
        var batches = 0
        try {
            runBlocking {
                MusicTagPass.run(list, { MusicTagSource.Path(it.id) }, reader) {
                    batches += 1
                    if (batches == 2) throw CancellationException("the library moved on")
                }
            }
        } catch (_: CancellationException) {
            // The pass ended where the library said it was stale.
        }
        assertEquals(2, batches)
        assertTrue("it did not read the third batch", reader.calls.get() <= MusicTagPass.BatchSize * 2)
    }

    @Test
    fun `a read that never returns is a failed file, its slot is free and the pass goes on`() {
        val hung = CountDownLatch(1)
        try {
            val list = tracks(5)
            val reader = FakeReader { source ->
                if ((source as MusicTagSource.Path).absolutePath == "id2") {
                    hung.await()
                    MusicTagResult.Read(MusicTags(title = "too late"), false)
                } else {
                    MusicTagResult.Read(MusicTags(), false)
                }
            }
            val seen = ArrayList<Pair<String, MusicTagResult>>()
            val reached = runBlocking {
                MusicTagPass.run(list, { MusicTagSource.Path(it.id) }, reader, readTimeoutMs = 150L) { batch -> seen += batch.map { it.first.id to it.second } }
            }
            assertTrue("every file was reached", reached)
            assertEquals(MusicTagResult.Failed, seen.single { it.first == "id2" }.second)
            assertEquals("the others read normally", 4, seen.count { it.second is MusicTagResult.Read })
            assertEquals("and in index order", list.map { it.id }, seen.map { it.first })
            assertEquals("the stuck read is counted until it returns", 1, AbandonableWork.stuck)
        } finally {
            hung.countDown()
            waitUntilNothingIsStuck()
        }
    }

    @Test
    fun `once too many reads are stuck the pass stops like a lost grant, and the files after stay unread`() {
        val hung = CountDownLatch(1)
        try {
            // Every read hangs. Two run at a time and each is given up on after the timeout, so the stuck ones pile up to the limit; from there no read is started at all
            // (the order the reads reach the gate in is not fixed, so only what holds in every order is asserted).
            val list = tracks(8)
            val reader = FakeReader {
                hung.await()
                MusicTagResult.Read(MusicTags(), false)
            }
            val recorded = ArrayList<String>()
            val reached = runBlocking {
                MusicTagPass.run(list, { MusicTagSource.Path(it.id) }, reader, readTimeoutMs = 100L) { batch -> recorded += batch.map { it.first.id } }
            }
            assertFalse("the pass did not reach every file", reached)
            assertTrue("what was recorded is a prefix of the list, in order: $recorded", recorded == list.take(recorded.size).map { it.id })
            assertTrue("fewer files were recorded than there are: $recorded", recorded.size < list.size)
            assertTrue("no read was started past the limit: ${reader.calls.get()} started", reader.calls.get() <= AbandonableWork.MaxStuck + MusicTagPass.Parallelism - 1)
            assertTrue("the stuck reads stand at the limit", AbandonableWork.stuck >= AbandonableWork.MaxStuck)
        } finally {
            hung.countDown()
            waitUntilNothingIsStuck()
        }
    }

    private fun waitUntilNothingIsStuck() {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (AbandonableWork.stuck > 0 && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals("every abandoned read returned once released", 0, AbandonableWork.stuck)
    }

    @Test
    fun `nothing pending reads nothing`() {
        val reader = FakeReader { error("must not be called") }
        assertTrue(runBlocking { MusicTagPass.run(emptyList(), { MusicTagSource.Path(it.id) }, reader) { error("no batch") } })
    }

    // ── the catalog the UI reads ─────────────────────────────────────────────────────────────────────

    @Test
    fun `the catalog lays tags over the tracks and counts how many have been read`() {
        val list = listOf(MusicTrack("", "01 - Sweep.mp3", "a"), MusicTrack("snes", "zelda.flac", "b"), MusicTrack("", "c.ogg", "c"))
        val meta = mapOf(
            "a" to MusicTrackMeta(
                stamp(1, 1),
                MusicTagState.READ,
                MusicTags(title = "Sweep", artist = "Alpha", album = "Album", albumArtist = "AA", trackNumber = 1, discNumber = 1, durationMs = 6_000L),
                hasEmbeddedArt = true,
            ),
            "b" to MusicTrackMeta(stamp(2, 2), MusicTagState.FAILED),
        )
        val catalog = musicCatalogOf("tree", list, meta, loaded = true)
        assertEquals(2, catalog.tagsRead)
        assertTrue("c has not been read", catalog.reading)
        val sweep = catalog.tracks[0]
        assertEquals("Sweep", sweep.title)
        assertEquals("Alpha", sweep.artist)
        assertEquals(6_000L, sweep.durationMs)
        assertTrue(sweep.hasEmbeddedArt)
        assertEquals("a failed read leaves the file name as the title", "zelda", catalog.tracks[1].title)
        assertEquals("snes", catalog.tracks[1].folder)
        assertNull(catalog.tracks[1].artist)
        assertEquals("c", catalog.tracks[2].title)
        assertFalse(musicCatalogOf("tree", list, list.associate { it.id to MusicTrackMeta(state = MusicTagState.READ) }, true).reading)
    }

    @Test
    fun `a track id the index lists twice is one track in the catalog`() {
        val list = listOf(MusicTrack("", "a.mp3", "same"), MusicTrack("", "a again.mp3", "same"), MusicTrack("", "b.mp3", "other"))
        val catalog = musicCatalogOf("tree", list, emptyMap(), loaded = true)
        assertEquals(listOf("same", "other"), catalog.tracks.map { it.id })
        assertEquals("a.mp3", catalog.tracks[0].fileName)
    }

    @Test
    fun `the catalog carries the compilation flag from the tags`() {
        val list = listOf(MusicTrack("", "a.mp3", "a"), MusicTrack("", "b.mp3", "b"))
        val meta = mapOf(
            "a" to MusicTrackMeta(stamp(1, 1), MusicTagState.READ, MusicTags(title = "A", compilation = true)),
            "b" to MusicTrackMeta(stamp(2, 2), MusicTagState.READ, MusicTags(title = "B")),
        )
        val tracks = musicCatalogOf("tree", list, meta, loaded = true).tracks
        assertTrue(tracks[0].compilation)
        assertFalse(tracks[1].compilation)
    }

    // ── the index file remembers it all ──────────────────────────────────────────────────────────────

    @Test
    fun `the index file round-trips tags and stamps, awkward characters and missing tags included`() {
        val index = MusicIndex(
            tree = "content://tree/primary%3Amusic",
            scannedAtMillis = 9L,
            tracks = listOf(MusicTrack("", "a.mp3", "a"), MusicTrack("snes", "b\tb.flac", "b"), MusicTrack("", "c.ogg", "c"), MusicTrack("", "d.wav", "d")),
            meta = mapOf(
                "a" to MusicTrackMeta(
                    stamp(1_234, 1_700_000_000_000L),
                    MusicTagState.READ,
                    MusicTags("Tab\there", "Line\nbreak", "Back\\slash", "Album Artist", 3, 2, 215_000L),
                    hasEmbeddedArt = true,
                ),
                "b" to MusicTrackMeta(stamp(-1, 0), MusicTagState.FAILED),
                "c" to MusicTrackMeta(stamp(5, 6), MusicTagState.READ, MusicTags(title = "Only a title")),
                "d" to MusicTrackMeta(stamp(7, 8)),
            ),
        )
        assertEquals(index, MusicIndexCodec.decode(MusicIndexCodec.encode(index)))
    }

    @Test
    fun `the compilation flag survives the index file`() {
        val index = MusicIndex(
            tree = "t",
            scannedAtMillis = 1L,
            tracks = listOf(MusicTrack("", "a.mp3", "a"), MusicTrack("", "b.mp3", "b")),
            meta = mapOf(
                "a" to MusicTrackMeta(stamp(1, 1), MusicTagState.READ, MusicTags(title = "A", compilation = true)),
                "b" to MusicTrackMeta(stamp(2, 2), MusicTagState.READ, MusicTags(title = "B")),
            ),
        )
        val text = MusicIndexCodec.encode(index)
        assertTrue("the third format", text.startsWith("MIMIRMUSIC3\n"))
        val decoded = MusicIndexCodec.decode(text)!!
        assertTrue(decoded.meta.getValue("a").tags.compilation)
        assertFalse(decoded.meta.getValue("b").tags.compilation)
        assertEquals(index, decoded)
    }

    @Test
    fun `an index written before the compilation flag still decodes, with the flag off`() {
        // Format 2: fourteen fields, no compilation column.
        val v2 = "MIMIRMUSIC2\n9\ntree\n\ta.mp3\tid-a\t1234\t5678\tr\tTitle\tArtist\tAlbum\tAA\t3\t1\t215000\t1\n"
        val index = MusicIndexCodec.decode(v2)!!
        val meta = index.meta.getValue("id-a")
        assertEquals(MusicTagState.READ, meta.state)
        assertEquals("Title", meta.tags.title)
        assertTrue(meta.hasEmbeddedArt)
        assertFalse(meta.tags.compilation)
        assertEquals("an old index carries its folder labels until the next rescan", "", index.tracks.single().folder)
    }

    @Test
    fun `an installed names-only index still decodes, as tracks with nothing read`() {
        val old = "MIMIRMUSIC1\n5\ncontent://tree\n\ta.mp3\tid-a\nsnes\tb.ogg\tid-b\n"
        val index = MusicIndexCodec.decode(old)!!
        assertEquals(listOf(MusicTrack("", "a.mp3", "id-a"), MusicTrack("snes", "b.ogg", "id-b")), index.tracks)
        assertTrue("so the first rescan (or the next start) reads the tags", MusicTagCache.pending(index).size == 2)
    }

    @Test
    fun `a malformed tag line is no index and never throws`() {
        assertNull(MusicIndexCodec.decode("MIMIRMUSIC2\n1\nt\n\ta\tb\n"))
        assertNull(MusicIndexCodec.decode("MIMIRMUSIC2\n1\nt\n\ta\tid\t1\t2\tx\t\t\t\t\t\t\t\t0\n"))
        assertNull("a size that is not a number", MusicIndexCodec.decode("MIMIRMUSIC2\n1\nt\n\ta\tid\tbig\t2\tr\t\t\t\t\t\t\t\t0\n"))
    }

    // ── a tag arriving never restarts the music ──────────────────────────────────────────────────────

    @Test
    fun `tags change what a track is called, not what the player plays`() {
        val tree = "file:///music"
        val list = listOf(MusicTrack("", "a.mp3", "/music/a.mp3"), MusicTrack("", "b.mp3", "/music/b.mp3"))
        val uris = object : YourMusicUris {
            override fun forPath(path: String) = "file://$path"

            override fun forDocument(tree: String, documentId: String) = "doc://$documentId"
        }
        val before = yourMusicEntries(tree, list, uris)
        val tagged = yourMusicEntries(tree, list, uris) { YourMusicLabel("Real ${it.name}", "Artist", "Album") }
        assertTrue("the entries differ in their labels", before != tagged)
        assertTrue("and play the same", before.playsTheSameAs(tagged))
        assertFalse(before.playsTheSameAs(tagged.reversed()))
        assertFalse(before.playsTheSameAs(tagged.take(1)))
        assertEquals("Real a.mp3", tagged[0].title)
        assertEquals("Artist", tagged[0].artist)
        assertEquals("Album", tagged[0].album)
        assertNull("without labels the session keeps its generic artist", before[0].artist)
    }

    @Test
    fun `the plan a folder plays is the same plan whatever the tags say`() {
        val tracks = listOf(MusicTrack("", "a.mp3", "id-a"), MusicTrack("", "b.mp3", "id-b"))
        val one = FrontendMusicPlan.Folder("tree", 1L, tracks)
        val other = FrontendMusicPlan.Folder("tree", 2L, tracks)
        assertTrue("a rescan that finds the same files restarts nothing", one.playsTheSameAs(other))
        val nowPlaying = one.nowPlayingAt(1) { YourMusicLabel("Tagged ${it.id}", artist = "Artist ${it.id}") }
        assertEquals("Tagged id-b", nowPlaying?.title)
        assertEquals("the tag artist rides with the title", "Artist id-b", nowPlaying?.artist)
        assertEquals("b", one.nowPlayingAt(1)?.title)
        assertNull("an unread track names no artist", one.nowPlayingAt(1)?.artist)
    }
}
