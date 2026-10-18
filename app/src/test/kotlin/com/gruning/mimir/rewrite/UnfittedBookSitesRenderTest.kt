package com.gruning.mimir.rewrite

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.audio.EqualizerPresetId
import com.gruning.mimir.rewrite.core.audio.EqualizerSettings
import com.gruning.mimir.rewrite.core.audio.MusicControlState
import com.gruning.mimir.rewrite.core.audio.OtherPlayerSnapshot
import com.gruning.mimir.rewrite.core.audio.YourMusicState
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MediaArtBookTitleTag
import com.gruning.mimir.rewrite.core.ui.MediaArtFallbackGlyph
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.mimirBookMannazTopDp
import java.io.File
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Render coverage for word-safe title fitting in three coverless-book painters, with one ᛗ per book.
 *
 *  - The onboarding favourites cell ([OnboardingFavoritesCell], the book drawn at the Tome's face width and scaled to its 75 dp grid) at font 1.0 and 1.3, for a favourite and a book
 *    that is not: no line of the title ends inside a word, the favourite wears its pin and no embossed Mannaz, the other the emboss and no pin.
 *  - Main's music panel, another player's card with no cover ([MusicPanelOverlay], the art seat at 96 dp).
 *  - The Bottom workspace's media art seat (56 dp; the production site is private and its argument is pinned from the source too).
 *
 * The title is one long word, which no size holds whole at these seats. The host ships no hyphenation patterns, so the word ends in the ellipsis here and would take a syllable hyphen
 * on a handheld ([com.gruning.mimir.rewrite.core.ui.MimirBookTitleSeatsTest] pins the hyphen with an engine that has them). The frames are written to
 * `build/test-captures/sweepfix32/round2/`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h760dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UnfittedBookSitesRenderTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val density = 2f
    private val shy = '\u00AD'
    private val longWord = "Counterintelligence"

    private fun draw(fontScale: Float, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                MimirTheme { content() }
            }
        }
        repeat(3) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeBy(500L)
            compose.waitForIdle()
        }
    }

    private fun frame(): Bitmap {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    private fun save(bitmap: Bitmap, name: String) {
        val file = File(File("build/test-captures/sweepfix32/round2").apply { mkdirs() }, name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("ROUND2 wrote ${file.absolutePath} (${bitmap.width}x${bitmap.height} px)")
        assertTrue("the PNG was written: $file", file.isFile && file.length() > 1_000L)
    }

    private fun spread(bitmap: Bitmap, box: Rect): Float {
        val left = box.left.toInt().coerceIn(0, bitmap.width - 1)
        val top = box.top.toInt().coerceIn(0, bitmap.height - 1)
        val right = box.right.toInt().coerceIn(left + 1, bitmap.width)
        val bottom = box.bottom.toInt().coerceIn(top + 1, bitmap.height)
        var sum = 0.0
        var squares = 0.0
        var count = 0
        for (y in top until bottom) for (x in left until right) {
            val pixel = bitmap.getPixel(x, y)
            val luma = 0.299 * ((pixel shr 16) and 0xFF) + 0.587 * ((pixel shr 8) and 0xFF) + 0.114 * (pixel and 0xFF)
            sum += luma
            squares += luma * luma
            count++
        }
        val mean = sum / count
        return sqrt((squares / count - mean * mean).coerceAtLeast(0.0)).toFloat()
    }

    /** Where the embossed Mannaz stands on a book box (`MediaArtBookCover`'s own numbers, in px). */
    private fun mannazBox(book: Rect): Rect {
        val spine = maxOf(book.width * 0.075f, 3f * density)
        val foreEdge = maxOf(book.width * 0.055f, 2f * density)
        val leaf = book.width - foreEdge - spine
        val width = leaf * 0.18f
        val height = width * 1.06f
        val cx = book.left + spine + leaf / 2f
        val cy = book.top + book.height * 0.84f
        return Rect(cx - width / 2f, cy - height / 2f, cx + width / 2f, cy + height / 2f)
    }

    /** The 3:4 book a cell holds: as wide as the cell, centred in its height. */
    private fun bookIn(cell: Rect): Rect {
        val height = minOf(cell.height, cell.width * 4f / 3f)
        return Rect(cell.left, cell.center.y - height / 2f, cell.right, cell.center.y + height / 2f)
    }

    private fun titleNodes(): List<SemanticsNode> =
        compose.onAllNodes(hasTestTag(MediaArtBookTitleTag), useUnmergedTree = true).fetchSemanticsNodes().sortedBy { it.boundsInWindow.left }

    private fun layoutOf(node: SemanticsNode): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        assertTrue("the title exposes its layout", node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results))
        return results.single()
    }

    private fun lines(layout: TextLayoutResult) =
        (0 until layout.lineCount).map { layout.layoutInput.text.text.substring(layout.getLineStart(it), layout.getLineEnd(it)).replace(shy.toString(), "<->") }

    /** No line but the last ends inside a word: a line ends at a space, a hyphen or right after a soft hyphen (which the engine draws as a hyphen). */
    private fun assertNoForcedCut(layout: TextLayoutResult, what: String) {
        val text = layout.layoutInput.text.text
        for (line in 0 until layout.lineCount - 1) {
            val end = layout.getLineEnd(line)
            val before = text[end - 1]
            val after = text.getOrNull(end)
            val forced = before != shy && before.isLetterOrDigit() && after != null && after.isLetterOrDigit()
            assertTrue("$what: line $line ends inside a word with no hyphen: ${lines(layout)}", !forced)
        }
    }

    // ── The onboarding favourites cell ──────────────────────────────────────────────────────────

    private fun row(index: Int, favorite: Boolean) = GameRow(
        gameId = GameId("onboarding-$index"),
        title = longWord,
        platformId = PlatformId.SNES,
        memberCount = 1,
        duplicateKind = null,
        isFavorite = favorite,
        artResolutionSettled = true,
    )

    private fun onboarding(fontScale: Float, tag: String) {
        val authority = DefaultFocusAuthority().apply { pushScope(OnboardingScope) }
        draw(fontScale) {
            Box(Modifier.background(LocalMimirPalette.current.surfaceBase).padding(24.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    listOf(false, true).forEachIndexed { index, favorite ->
                        Box(Modifier.width(OnboardingFavoritesFaceWidthDp.dp).testTag("cell-$index")) {
                            OnboardingFavoritesCell(row = row(index, favorite), focused = false, authority = authority, onToggle = {})
                        }
                    }
                }
            }
        }
        val shot = frame()
        save(shot, "book-onboarding-$tag.png")
        val titles = titleNodes()
        assertEquals("the two cells each draw a book", 2, titles.size)
        listOf(false, true).forEachIndexed { index, favorite ->
            val layout = layoutOf(titles[index])
            val what = "onboarding cell (favourite=$favorite) at font $fontScale"
            println("ROUND2 $what title lines=${lines(layout)} size=${layout.layoutInput.style.fontSize}")
            assertNoForcedCut(layout, what)
            val cell = compose.onNodeWithTag("cell-$index").fetchSemanticsNode().boundsInWindow
            val emboss = spread(shot, mannazBox(bookIn(cell)))
            println("ROUND2 $what emboss spread=$emboss")
            if (favorite) {
                assertTrue("$what: the favourite's book draws no embossed Mannaz (spread $emboss)", emboss < 8f)
            } else {
                assertTrue("$what: a book that is not a favourite draws its embossed Mannaz (spread $emboss)", emboss > 15f)
            }
        }
    }

    @Test
    fun `the onboarding cell's book sets its title to the book at font 1_0, and shows one Mannaz`() = onboarding(1.0f, "1.0")

    @Test
    fun `the onboarding cell's book sets its title to the book at font 1_3, and shows one Mannaz`() = onboarding(1.3f, "1.3")

    // ── Main's music panel ──────────────────────────────────────────────────────────────────────

    private fun music(fontScale: Float, tag: String) {
        val other = OtherPlayerSnapshot(title = longWord, artist = "An Artist", appLabel = "Spotify", artwork = null, isPlaying = true, canPrevious = true, canNext = true)
        val control = MusicControlState(
            yours = YourMusicState(title = "Dreams of the Hedgehog", isPlaying = false, canPrevious = true, canNext = true),
            other = other,
            otherAccess = true,
        )
        val model = musicPanelModel(
            control = control,
            stopped = false,
            musicVolume = 0.4f,
            mediaVolume = 0.7f,
            mediaAvailable = true,
            mediaFixed = false,
            cappedByMedia = false,
            capPushed = false,
            equalizer = EqualizerSettings(enabled = true, presetId = EqualizerPresetId.FLAT),
        )
        draw(fontScale) {
            Box(Modifier.size(400.dp, 760.dp).background(LocalMimirPalette.current.surfaceBase), contentAlignment = Alignment.Center) {
                MusicPanelOverlay(
                    model = model,
                    focused = null,
                    authority = null,
                    anchor = null,
                    onDismiss = {},
                    onPlayPause = { _ -> },
                    onPrevious = { _ -> },
                    onNext = { _ -> },
                    onMediaRequested = {},
                    onMusicRequested = {},
                    onMusicInteractionEnd = {},
                    onRequestFocus = {},
                )
            }
        }
        save(frame(), "book-music-panel-$tag.png")
        val layout = layoutOf(titleNodes().single())
        val what = "Main's music panel at font $fontScale"
        println("ROUND2 $what title lines=${lines(layout)} size=${layout.layoutInput.style.fontSize} maxLines=${layout.layoutInput.maxLines}")
        assertNoForcedCut(layout, what)
    }

    @Test
    fun `Main's music panel sets its coverless book's title to the book at font 1_0`() = music(1.0f, "1.0")

    @Test
    fun `Main's music panel sets its coverless book's title to the book at font 1_3`() = music(1.3f, "1.3")

    // ── The Bottom workspace's media art seat ───────────────────────────────────────────────────

    private fun workspace(fontScale: Float, tag: String) {
        draw(fontScale) {
            val palette = LocalMimirPalette.current
            Box(Modifier.background(palette.surfaceBase).padding(24.dp)) {
                // The seat as CompanionWorkspaceSurface's MediaArtwork draws it: 56 dp, the plate behind the no-art fallback, the book with the track's title.
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(palette.surfaceHigh), contentAlignment = Alignment.Center) {
                    MediaArtFallbackGlyph(longWord, palette = palette, showTitle = false, fitTitle = true)
                }
            }
        }
        save(frame(), "book-bottom-workspace-$tag.png")
        val layout = layoutOf(titleNodes().single())
        val what = "the Bottom workspace's media art at font $fontScale"
        println("ROUND2 $what title lines=${lines(layout)} size=${layout.layoutInput.style.fontSize}")
        assertNoForcedCut(layout, what)
    }

    @Test
    fun `the Bottom workspace's media art sets its coverless book's title to the book at font 1_0`() = workspace(1.0f, "1.0")

    @Test
    fun `the Bottom workspace's media art sets its coverless book's title to the book at font 1_3`() = workspace(1.3f, "1.3")

    // ── The production call sites ───────────────────────────────────────────────────────────────

    private fun source(name: String): String =
        listOf(File("src/main/kotlin/com/gruning/mimir/rewrite/$name"), File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name")).first(File::isFile).readText()

    @Test
    fun `every coverless book painter in the app sets its title to the book`() {
        val workspace = source("CompanionWorkspaceSurface.kt")
        assertTrue(workspace.contains("MediaArtFallbackGlyph(track.title, palette = LocalMimirPalette.current, showTitle = false, fitTitle = true)"))
        val onboarding = source("OnboardingFlow.kt")
        assertEquals("both scaled-book glyphs are fitted", 2, Regex("""MediaArtFallbackGlyph\(title, palette = palette, showTitle = true, fitTitle = true, showMannaz = showMannaz\)""").findAll(onboarding).count())
        assertTrue(onboarding.contains("OnboardingFavoritesScaledBook(title = row.title, palette = palette, showMannaz = !row.isFavorite)"))
        val music = source("MusicPanel.kt")
        val mainCall = music.substringAfter("MusicPanelArtwork(\n            model = shown,").substringBefore("\n        )")
        assertTrue("Main's call to the art seat passes the fit: $mainCall", mainCall.contains("fitTitle = true"))
    }

    @Test
    fun `a pinned Tome's own embossed Mannaz gives way on the pin's clock`() {
        // The cover's emboss itself is read from the pixels in core-ui's MimirNorseBookMannazTest; here, that the Tome library hands it the pin's state and the pin's 110 ms.
        val overlay = source("HomeTomeLibraryOverlay.kt")
        val face = overlay.substringAfter("HomeTomeFace(\n                    tome = entry.model,").substringBefore("\n                )")
        assertTrue("the face is told to hide its emboss while the pin shows: $face", face.contains("showMannaz = !entry.pinned"))
        assertTrue("on the pin's own clock: $face", face.contains("mannazFadeMs = HomeTomeMutationAckMs"))
        assertTrue("the pin it crosses over with runs that clock", overlay.contains("durationMs = HomeTomeMutationAckMs"))
    }

    @Test
    fun `the Mannaz math the pixel reads agree with the book's own formula`() {
        // The test reads the emboss in the box of its four tips; the book's formula for where it begins is what the shelf stands marks clear of.
        val book = Rect(0f, 0f, 120f * density, 160f * density)
        val tipTop = mannazBox(book).top / density
        val formulaTop = mimirBookMannazTopDp(120f, 160f)
        assertEquals("the emboss's top edge", formulaTop, tipTop, 0.6f)
    }
}
