package com.gruning.mimir.rewrite

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeTestRule
import java.io.File
import org.junit.Assert.assertTrue

/**
 * Shared by the music renders : pictures of the real composables at the Thor's two geometries, written to `build/test-captures/music250/` for someone to LOOK at.
 * Drawn the way this repo rasterises (the window's view hierarchy into a software bitmap; see [CustomMediaPageCaptureTest]). Pixel values are the JVM's; the
 * pixels on a handheld are device-owed.
 */
internal object MusicRenderSupport {
    /** Main: 1920 x 1080 px at 369 dpi is 832 x 468 dp. */
    const val MainQualifiers = "w833dp-h468dp-369dpi"

    /** The Bottom's Presentation: 1240 x 1080 px at 369 dpi is 537.7 x 468.3 dp. */
    const val BottomQualifiers = "w538dp-h468dp-369dpi"

    const val Density = 369f / 160f

    private val out = File("build/test-captures/music250").apply { mkdirs() }

    fun window(decor: android.view.View): Bitmap {
        val full = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(full))
        return full
    }

    /**
     * A loud gradient with a dark disc at its centre, like a cover and nothing like a frame: a crop moves the disc off its centre and a stretch makes it an ellipse, and
     * the picture has no border of its own to be mistaken for the app's (a white rim here once read as a ring around every cover). [seed] varies the hue between covers.
     */
    fun cover(side: Int, seed: Int): Bitmap {
        val palette = listOf(
            0xFF1E88E5.toInt() to 0xFFD81B60.toInt(),
            0xFF43A047.toInt() to 0xFFFDD835.toInt(),
            0xFF8E24AA.toInt() to 0xFF00ACC1.toInt(),
            0xFFF4511E.toInt() to 0xFF3949AB.toInt(),
        )
        val (from, to) = palette[seed.mod(palette.size)]
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawRect(0f, 0f, side.toFloat(), side.toFloat(), Paint().apply { shader = LinearGradient(0f, 0f, side.toFloat(), side.toFloat(), from, to, Shader.TileMode.CLAMP) })
        canvas.drawCircle(side / 2f, side / 2f, side * 0.26f, Paint().apply { color = 0x66000000 })
        return bitmap
    }

    /** Writes [image] as [name] under [dirName] (a named capture folder), or under `build/test-captures/music250/` when none is given. */
    fun write(image: Bitmap, name: String, dirName: String? = null): File {
        val file = File(dirName?.let { File(it).apply { mkdirs() } } ?: out, name)
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("MUSIC-RENDER wrote ${file.absolutePath} (${image.width}x${image.height} px)")
        assertTrue("the PNG was written: ${file.path}", file.isFile && file.length() > 1_000L)
        return file
    }

    /** Every browser row composed, in dp, top to bottom. */
    fun rowBounds(rule: ComposeTestRule): List<Rect> {
        val density = rule.density.density
        return rule.onAllNodes(SemanticsMatcher("a browser row") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(MusicBrowserRowTestTag) == true })
            .fetchSemanticsNodes()
            .map { node -> node.boundsInRoot.let { Rect(it.left / density, it.top / density, it.right / density, it.bottom / density) } }
            .sortedBy { it.top }
    }

    /**
     * The browser's rows stand clear of one another by at least [minGapDp] and none overlaps another: the focused row's ring reaches that far past it, so a smaller gap
     * puts its halo into the next row's thumbnail (the earlier update review). Geometry, which a count of drawn pixels never sees.
     */
    fun assertRowsClear(rule: ComposeTestRule, minGapDp: Float, what: String) {
        rowBounds(rule).zipWithNext().forEach { (above, below) ->
            val gap = below.top - above.bottom
            assertTrue("$what: two rows are $gap dp apart, under the $minGapDp dp the ring needs", gap >= minGapDp - 0.6f)
        }
    }

    /** The picture drew something: not a flat ground. */
    fun assertDrew(image: Bitmap, what: String) {
        val ground = image.getPixel(2, image.height - 2)
        var differing = 0
        for (y in 0 until image.height step 3) for (x in 0 until image.width step 3) if (image.getPixel(x, y) != ground) differing += 1
        assertTrue("$what drew something: $differing sampled pixels differ from the ground", differing > 1_000)
    }
}

/**
 * The library the Artist / Album renders draw : enough artists that the list scrolls, one with a name too long for a row, an album with several discs'
 * worth of numbering left out (the model tests cover that), and tracks with and without a picture.
 */
internal object MusicLibraryFixtures {
    const val LongArtist = "A Remarkably Long Named Orchestra and Chamber Ensemble of the Northern Provinces"
    const val LongAlbum = "An Equally Long Album Title That Will Not Fit On One Line At All"

    private fun track(
        id: String,
        artist: String?,
        album: String?,
        title: String,
        number: Int? = null,
        durationMs: Long? = 200_000L,
        art: Boolean = false,
    ) = com.gruning.mimir.rewrite.core.audio.MusicCatalogTrack(
        id = id,
        fileName = "$id.mp3",
        folder = "",
        title = title,
        artist = artist,
        album = album,
        trackNumber = number,
        durationMs = durationMs,
        hasEmbeddedArt = art,
    )

    /** Ids of the tracks that carry a picture, for the art source; the catalog says so too. */
    val artIds = setOf("a1", "b1", "d1", "l1")

    val tracks = listOf(
        track("a1", "Alpha", "First", "Sweep", 1, 215_000L, art = true),
        track("a2", "Alpha", "First", "Tone", 2, 3_725_000L),
        track("a3", "Alpha", "First", "A Track With A Rather Long Title That Cannot Fit On One Row", 3, 61_000L),
        track("a4", "Alpha", "Second", "Noise", 1, 187_000L),
        track("b1", "Beta", "Only", "Single", 1, 241_000L, art = true),
        track("c1", "Gamma", "Echoes", "One", 1),
        track("d1", "Delta", "Hours", "Early", 1, art = true),
        track("e1", "Epsilon", "Waves", "Crest", 1),
        track("f1", "Zeta", "Late", "Last", 1),
        track("l1", LongArtist, LongAlbum, "Overture", 1, 421_000L, art = true),
        track("l2", LongArtist, LongAlbum, "Finale", 2, 388_000L),
        track("u1", null, null, "Loose Track"),
    )

    /** The same tracks with every picture present, or none. */
    fun tracksWithArt(all: Boolean): List<com.gruning.mimir.rewrite.core.audio.MusicCatalogTrack> = tracks.map { it.copy(hasEmbeddedArt = all) }

    fun view(tracks: List<com.gruning.mimir.rewrite.core.audio.MusicCatalogTrack> = this.tracks, tagsRead: Int = tracks.size) =
        com.gruning.mimir.rewrite.core.audio.MusicLibraryView(
            tree = com.gruning.mimir.rewrite.core.audio.buildMusicTree(tracks),
            tagsRead = tagsRead,
            trackCount = tracks.size,
            loaded = true,
        )
}

/** The art a track "has" in a render: a cover for each id in [ids] (a different hue per track), none for the rest. */
internal class FakeYourMusicArt(private val ids: Set<String>) : YourMusicArtSource {
    override val revision: kotlinx.coroutines.flow.StateFlow<Long> = kotlinx.coroutines.flow.MutableStateFlow(0L)

    override fun hint(trackId: String) = if (trackId in ids) com.gruning.mimir.rewrite.core.audio.EmbeddedArtHint.YES else com.gruning.mimir.rewrite.core.audio.EmbeddedArtHint.NO

    override fun cached(trackId: String, px: Int): Bitmap? = null

    override fun knownMissing(trackId: String, px: Int) = trackId !in ids

    override suspend fun load(context: android.content.Context, trackId: String, px: Int): Bitmap? =
        if (trackId in ids) MusicRenderSupport.cover(px.coerceIn(16, 640), seed = trackId.hashCode()) else null
}
