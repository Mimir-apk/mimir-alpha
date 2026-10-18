package com.gruning.mimir.rewrite.feature.library

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onParent
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.LocalMimirTextLayoutObserver
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirViewportPosture
import com.gruning.mimir.rewrite.core.ui.MimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.MimirTypeStep
import com.gruning.mimir.rewrite.core.ui.mimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.theme.MimirSpacing
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Sweep 10-09 (F9), the gamelist's Search pill, on the real [GamelistHeader] at Main's 832.8 x 468.3 dp and 369 dpi (the SNES list, "Super Nintendo", 3 games):
 *  - F9: at font 1.3 the pill read "SEARCH GA..." (the label cut by the pill). It now sets the label at the largest size down to the type floor at which it stands whole, and when not
 *    even the floor holds it the pill is the magnifier alone (Customize media's mark since the earlier update). At font 1.0 nothing changes: the label whole at the Label size.
 * PNGs land in `build/test-captures/sweep28b/`; the pixels are the JVM's, a handheld's are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w833dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GamelistSearchPillFitTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val authority = DefaultFocusAuthority()
    private val rootDensity = 369f / 160f

    /** The layout of every text the header drew, by its spoken words (the product's own test hook, [LocalMimirTextLayoutObserver]). */
    private val layouts = mutableMapOf<String, TextLayoutResult>()

    private fun mount(
        fontScale: Float,
        widthDp: Float = 832.8f,
        profile: MimirViewportProfile = mimirViewportProfile(widthDp, 468.3f),
    ) {
        compose.runOnUiThread { compose.activity.actionBar?.hide() }
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(rootDensity, fontScale),
                LocalMimirViewportProfile provides profile,
                LocalMimirTextLayoutObserver provides { spoken, layout -> layouts[spoken] = layout },
            ) {
                MimirTheme {
                    Box(Modifier.requiredSize(widthDp.dp, 468.3.dp).background(LocalMimirPalette.current.surfaceBase)) {
                        Column(Modifier.fillMaxSize().padding(horizontal = LibraryGamelistBodyPaddingDp, vertical = MimirSpacing.md)) {
                            GamelistHeader(
                                title = "Super Nintendo",
                                metaLabel = "3 games",
                                showSearch = true,
                                searchQuery = "",
                                searchPlaceholder = "Search games",
                                authority = authority,
                                searchControllerEnabled = true,
                                searchFocused = false,
                                onOpenSearch = {},
                                onClearSearch = {},
                                segments = listOf(FavoriteSegment.GAMES),
                                segmentCounts = emptyMap(),
                                activeSegment = FavoriteSegment.GAMES,
                                onSelectSegment = {},
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun save(fileName: String) {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val top = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, (bitmap.height * 0.2f).toInt())
        val dir = File("build/test-captures/sweep28b").apply { if (!isDirectory) mkdirs() }
        val out = File(dir, fileName)
        out.outputStream().use { top.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SEARCHFIT wrote ${out.absolutePath}")
        assertTrue("the PNG was written: ${out.path}", out.isFile && out.length() > 1_000L)
    }

    private fun pill() = compose.onNode(hasContentDescription("Search games"), useUnmergedTree = true)

    /** The layout Compose drew for the pill's label, or null when the pill is the magnifier alone (the glyph carries the words then). */
    private fun labelLayout(): TextLayoutResult? = layouts["Search games"]

    private fun check(fontScale: Float, fileName: String) {
        mount(fontScale)
        val label = labelLayout()
        val pillWidth = pill().onParent().getUnclippedBoundsInRoot().width
        println("SEARCHFIT font $fontScale: label=${label?.layoutInput?.style?.fontSize} ellipsized=${label?.isLineEllipsized(0)} pill=$pillWidth")
        if (label != null) {
            assertFalse("the label is never cut: \"${label.layoutInput.text.text}\" at ${label.layoutInput.style.fontSize}", label.isLineEllipsized(0))
            val sp = label.layoutInput.style.fontSize.value
            assertTrue("the label is set between the floor and the Label size, not below it: $sp", sp >= MimirTypeStep.Micro.value && sp <= MimirTypeStep.Label.value)
        } else {
            assertEquals("the magnifier alone is the 48 dp mark", LibraryGamelistSearchMarkWidthDp, pillWidth.value, 0.6f)
        }
        save(fileName)
    }

    @Test
    fun `font 1 - the label stands whole at the Label size, as before`() {
        mount(1f)
        val label = labelLayout()
        requireNotNull(label) { "at font 1.0 the pill still carries its label" }
        assertFalse(label.isLineEllipsized(0))
        assertEquals(MimirTypeStep.Label.value, label.layoutInput.style.fontSize.value, 0.01f)
        save("f9-search-1.0.png")
    }

    @Test
    fun `font 1_3 - the label is never cut, whole at a smaller size or the magnifier alone`() {
        check(1.3f, "f9-search-1.3.png")
    }

    @Test
    fun `where not even the floor holds the label the pill is the magnifier alone, and a screen reader hears the words, not the caps`() {
        // A 300 dp header at font 1.5: the pill's room (136 dp less its padding, the glyph and the gap) is under the width "SEARCH GAMES" needs even at the 9 sp floor.
        val width = 300f + 2f * LibraryGamelistBodyPaddingDp.value
        mount(1.5f, widthDp = width, profile = MimirViewportProfile(MimirViewportPosture.STANDARD, width, 468.3f))
        assertNull("no label is drawn: the mark stands alone", labelLayout())
        // Found by the spoken words, "Search games": the drawn caps ("SEARCH GAMES") are not what TalkBack reads.
        val mark = pill().onParent().getUnclippedBoundsInRoot().width
        println("SEARCHFIT mark-only at font 1.5, header 300 dp: pill $mark")
        assertEquals("the pill is the 48 dp mark", LibraryGamelistSearchMarkWidthDp, mark.value, 0.6f)
        save("f9-search-mark-1.5.png")
    }

    @Test
    fun `the label's size is the largest that fits, down to the floor, or null`() {
        assertEquals(11f, gamelistSearchLabelSizeSp(11f, 9f) { true }!!, 0.001f)
        assertEquals("one half-step down when only that fits", 10.5f, gamelistSearchLabelSizeSp(11f, 9f) { it <= 10.5f }!!, 0.001f)
        assertEquals("the floor itself counts", 9f, gamelistSearchLabelSizeSp(11f, 9f) { it <= 9f }!!, 0.001f)
        assertNull("nothing fits: no label at all", gamelistSearchLabelSizeSp(11f, 9f) { false })
    }
}
