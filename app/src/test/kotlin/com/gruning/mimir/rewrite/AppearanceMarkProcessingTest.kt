package com.gruning.mimir.rewrite

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog

/**
 * The mark seam end to end (concept A, brief §B, §D): [AppearanceMarkProcessing.process] decodes a real picture, cuts the
 * middle square at 256 px, writes the mask as a PNG with alpha, and answers the sidecar's facts.
 *
 * The decode is Android's `ImageDecoder`, so this runs on Robolectric's native graphics: pixels are read back from the file
 * the seam wrote, not from the seam's own arithmetic. The maths on their own are pinned by [AppearanceMarkMaskTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppearanceMarkProcessingTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File.createTempFile("mark", "dir").apply { delete(); mkdirs() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
        AppearanceImageStore.root(RuntimeEnvironment.getApplication(), AppearanceImageKind.MARK).deleteRecursively()
    }

    private fun write(name: String, bitmap: Bitmap, format: Bitmap.CompressFormat): File =
        File(dir, name).also { file -> FileOutputStream(file).use { check(bitmap.compress(format, 100, it)) } }

    /** A [width] x [height] opaque picture: [paint] answers each column's colour. */
    private fun columns(width: Int, height: Int, paint: (x: Int) -> Int): Bitmap =
        Bitmap.createBitmap(IntArray(width * height) { index -> paint(index % width) }, width, height, Bitmap.Config.ARGB_8888)

    private fun read(file: File): Bitmap = checkNotNull(BitmapFactory.decodeFile(file.absolutePath)) { "the seam wrote no image" }

    @Test
    fun `a wide opaque picture keeps its subject whole, fitted into the mark, and its luminance becomes the alpha`() {
        // 300 x 200: black | white | black in thirds. The black field is one colour and edge-connected, so it is cut out;
        // the white third (100 x 200) is the subject, fitted 1.12x to 112 x 224 and centred: ink from x 72 to 184, 16 px
        // clear above and below. The old centre crop showed it at 64..192 with no inset.
        val source = write("wide.png", columns(300, 200) { x -> if (x in 100 until 200) Color.WHITE else Color.BLACK }, Bitmap.CompressFormat.PNG)
        val target = File(dir, "wide.mark.part")

        val processed = checkNotNull(AppearanceMarkProcessing.process(source, target))

        assertEquals(AppearanceMarkProcessing.MarkEdgePx, processed.width)
        assertEquals(AppearanceMarkProcessing.MarkEdgePx, processed.height)
        assertEquals(0f, processed.p90, 0f)
        assertNull("no caveat for a subject over half the mask's size", processed.note)
        val mask = read(target)
        assertEquals(256, mask.width)
        assertEquals(256, mask.height)
        assertTrue("the file keeps an alpha channel", mask.hasAlpha())
        fun alphaAt(x: Int, y: Int = 128) = Color.alpha(mask.getPixel(x, y))
        assertEquals("left of the subject is see-through", 0, alphaAt(10))
        assertEquals("the white subject is ink", 255, alphaAt(128))
        assertEquals("right of it is see-through", 0, alphaAt(245))
        assertTrue("fitted, so the subject's own ends are kept", alphaAt(80) > 200 && alphaAt(176) > 200)
        assertTrue(alphaAt(60) < 40 && alphaAt(196) < 40)
        assertEquals("the inset above", 0, alphaAt(128, 8))
        assertTrue("and the subject just inside it", alphaAt(128, 24) > 200)
        assertEquals("white ink, so a thumbnail reads on a dark tile", Color.WHITE, mask.getPixel(128, 128))
    }

    @Test
    fun `a picture with real transparency keeps its own alpha and an image with nothing to strike is refused`() {
        // 256 x 256 transparent, with an opaque RED square in the middle: alpha is the shape, colour is dropped, and the
        // square is fitted to the mark's content box (16 px in), not left at its 64 px.
        val logo = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
            for (y in 96 until 160) for (x in 96 until 160) setPixel(x, y, Color.RED)
        }
        val logoFile = write("logo.png", logo, Bitmap.CompressFormat.PNG)
        val logoTarget = File(dir, "logo.mark.part")
        assertNotNull(AppearanceMarkProcessing.process(logoFile, logoTarget))
        val mask = read(logoTarget)
        assertEquals(255, Color.alpha(mask.getPixel(128, 128)))
        assertEquals(Color.WHITE, mask.getPixel(128, 128))
        assertEquals("fitted out to the inset", 255, Color.alpha(mask.getPixel(20, 20)))
        assertEquals("the inset itself stays clear", 0, Color.alpha(mask.getPixel(8, 8)))

        val blank = write("blank.png", columns(256, 256) { Color.BLACK }, Bitmap.CompressFormat.PNG)
        val blankTarget = File(dir, "blank.mark.part")
        assertNull("an all-black picture leaves a button with no mark on it", AppearanceMarkProcessing.process(blank, blankTarget))
        assertTrue("and nothing is left behind for the import to install", !blankTarget.exists() || blankTarget.length() == 0L)
    }

    /** A 256 x 256 opaque picture of [background] with a [size]-px square of [ink] in the middle. */
    private fun squareOn(background: Int, ink: Int, size: Int): Bitmap =
        Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
            eraseColor(background)
            val from = (256 - size) / 2
            for (y in from until from + size) for (x in from until from + size) setPixel(x, y, ink)
        }

    private fun maskLogLines(): List<String> = ShadowLog.getLogsForTag("MimirAppearanceImage").map { it.msg }.filter { "mask=" in it }

    @Test
    fun `a dark logo on white comes out as the logo, and a light glyph on dark as it always did`() {
        ShadowLog.clear()
        // Dark on white: the light edges say the dark parts are the mark. Before this a metal square with the logo cut out.
        val logoFile = write("darklogo.png", squareOn(Color.WHITE, Color.BLACK, size = 96), Bitmap.CompressFormat.PNG)
        val logoTarget = File(dir, "darklogo.mark.part")
        assertNotNull(AppearanceMarkProcessing.process(logoFile, logoTarget))
        val logo = read(logoTarget)
        assertEquals("the logo is ink", 255, Color.alpha(logo.getPixel(128, 128)))
        assertEquals("white ink, so a thumbnail reads on a dark tile", Color.WHITE, logo.getPixel(128, 128))
        assertEquals("the white ground is cut out and see-through", 0, Color.alpha(logo.getPixel(8, 8)))
        assertEquals(0, Color.alpha(logo.getPixel(248, 248)))
        assertEquals("the logo is fitted to the inset", 255, Color.alpha(logo.getPixel(20, 20)))

        // Light on dark: unchanged, but fitted the same way.
        val glyphFile = write("lightglyph.png", squareOn(Color.BLACK, Color.WHITE, size = 96), Bitmap.CompressFormat.PNG)
        val glyphTarget = File(dir, "lightglyph.mark.part")
        assertNotNull(AppearanceMarkProcessing.process(glyphFile, glyphTarget))
        val glyph = read(glyphTarget)
        assertEquals(255, Color.alpha(glyph.getPixel(128, 128)))
        assertEquals(0, Color.alpha(glyph.getPixel(8, 8)))
        assertEquals(255, Color.alpha(glyph.getPixel(20, 20)))

        val lines = maskLogLines()
        assertEquals(2, lines.size)
        assertTrue("the branch is logged, an id-free line with no path: ${lines[0]}", lines[0].startsWith("kind=MARK mask=luminance-dark cutout=true "))
        assertTrue(lines[1].startsWith("kind=MARK mask=luminance cutout=true "))
        assertTrue("the fit is logged too", lines[0].contains(" subject=96x96 fit=224x224 "))

        // An all-white picture inverts to nothing and is refused, as an all-black one is.
        val blank = write("white.png", columns(256, 256) { Color.WHITE }, Bitmap.CompressFormat.PNG)
        assertNull(AppearanceMarkProcessing.process(blank, File(dir, "white.mark.part")))
    }

    @Test
    fun `a small picture is scaled up with a note and a tiny one is refused`() {
        val small = write("small.png", columns(64, 64) { x -> if (x < 32) Color.WHITE else Color.BLACK }, Bitmap.CompressFormat.PNG)
        val processed = checkNotNull(AppearanceMarkProcessing.process(small, File(dir, "small.mark.part")))
        assertEquals(256, processed.width)
        assertEquals(AppearanceImageStore.NoteSoft, processed.note)

        val tiny = write("tiny.png", columns(16, 16) { Color.WHITE }, Bitmap.CompressFormat.PNG)
        assertNull(AppearanceMarkProcessing.process(tiny, File(dir, "tiny.mark.part")))
        assertNull("not an image at all", AppearanceMarkProcessing.process(File(dir, "missing.png"), File(dir, "missing.mark.part")))
    }

    @Test
    fun `importing a picture saves the mask under its content hash, once`() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val pick = write("pick.png", columns(300, 200) { x -> if (x in 100 until 200) Color.WHITE else Color.BLACK }, Bitmap.CompressFormat.PNG)

        val first = AppearanceImageStore.importMark(app, Uri.fromFile(pick))

        val saved = (first as AppearanceImportOutcome.Saved).image
        assertEquals(AppearanceImageKind.MARK, saved.kind)
        assertEquals(256, saved.width)
        assertTrue("the processed copy is on disk in the app's own marks directory", saved.available)
        assertEquals(AppearanceImageStore.NoteSaved, first.note)
        assertEquals(listOf(saved.id), AppearanceImageStore.list(app, AppearanceImageKind.MARK).map { it.id })
        assertEquals(256, read(saved.file).width)
        assertEquals("the import records the copy's length", saved.file.length(), saved.bytes)
        assertEquals("and the sidecar carries it", saved.file.length(), AppearanceImageStore.find(app, AppearanceImageKind.MARK, saved.id)?.bytes)

        // The same picture again is the same entry, not a second copy.
        val again = AppearanceImageStore.importMark(app, Uri.fromFile(pick)) as AppearanceImportOutcome.Saved
        assertEquals(saved.id, again.image.id)
        assertEquals(AppearanceImageStore.NoteAlreadySaved, again.note)
        assertEquals(1, AppearanceImageStore.list(app, AppearanceImageKind.MARK).size)

        // A copy cut short afterwards is not the copy the import wrote: it reads unavailable, not SAVED with a blank tile.
        saved.file.writeBytes(saved.file.readBytes().copyOf((saved.file.length() / 2).toInt()))
        assertTrue("still not empty", saved.file.length() > 0L)
        assertTrue(!AppearanceImageStore.find(app, AppearanceImageKind.MARK, saved.id)!!.available)
    }

    // ── the true reason for a refusal (alpha cert 2026-10-08, E6) ──────────────────────────────────────────

    @Test
    fun `a blank picture has no subject, which is not the same as a picture that cannot be used`() {
        val black = write("black.png", columns(256, 256) { Color.BLACK }, Bitmap.CompressFormat.PNG)
        val white = write("white-flat.png", columns(256, 256) { Color.WHITE }, Bitmap.CompressFormat.PNG)
        assertEquals(AppearanceProcessOutcome.NoSubject, AppearanceMarkProcessing.processOutcome(black, File(dir, "black.mark.part")))
        assertEquals(AppearanceProcessOutcome.NoSubject, AppearanceMarkProcessing.processOutcome(white, File(dir, "white.mark.part")))

        val tiny = write("tiny-flat.png", columns(16, 16) { Color.WHITE }, Bitmap.CompressFormat.PNG)
        assertEquals(AppearanceProcessOutcome.Unusable, AppearanceMarkProcessing.processOutcome(tiny, File(dir, "tiny.mark.part")))
        assertEquals(
            "not an image at all",
            AppearanceProcessOutcome.Unusable,
            AppearanceMarkProcessing.processOutcome(File(dir, "missing.png"), File(dir, "missing.mark.part")),
        )
    }

    @Test
    fun `importing a blank picture is refused with its own reason and saves nothing`() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val blank = write("flat.png", columns(256, 256) { Color.WHITE }, Bitmap.CompressFormat.PNG)

        val outcome = AppearanceImageStore.importMark(app, Uri.fromFile(blank))

        assertEquals(AppearanceImportOutcome.NoSubject, outcome)
        assertTrue("nothing was saved", AppearanceImageStore.list(app, AppearanceImageKind.MARK).isEmpty())
    }

    @Test
    fun `each refusal speaks its own plain sentence and the format hint stays for real decode failures`() {
        assertEquals(
            "That image has no shape to use as a mark. Choose one with a clear subject.",
            appearanceImportNote(AppearanceImportOutcome.NoSubject),
        )
        assertEquals(
            "That image couldn’t be used. Choose a PNG, JPEG, WebP, or HEIF.",
            appearanceImportNote(AppearanceImportOutcome.Unusable),
        )
        assertEquals(AppearanceImageStore.NoteFull, appearanceImportNote(AppearanceImportOutcome.Full))
    }
}
