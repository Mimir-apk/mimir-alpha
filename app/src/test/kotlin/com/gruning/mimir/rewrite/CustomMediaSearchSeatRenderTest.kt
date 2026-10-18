package com.gruning.mimir.rewrite

import android.animation.ValueAnimator
import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.feature.library.HomeShelfRow
import java.io.File
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A reported type-switch width change on Odin, Tears of the Kingdom (Switch), 10-13. The REAL page
 * ([HomeCustomEnrichPickerOverlay]) with the long title in Search, at the Main widths and the compact one, at font 1.0 and 1.3, on each of the four types the game offers:
 * Search is ONE width, whatever the type, and its name is whole, cut at a WORD with an ellipsis, or (when not even the first word stands) the 48 dp magnifier, never cut
 * inside a word.
 *
 * Widths (the panel is 88% of the viewport, capped at 680 dp, so every Main viewport wider than ~773 dp gives the top row the same ~652 dp):
 *  - the Odin's Main, 1920 x 1080 px at density 369 = 832.5 x 468.3 dp (`HomeTileMenuPanelWidthCapTest`);
 *  - the Thor's Main, the same 832.5 dp across (`GameOpeningIntroLogoStageTest`: 832.5 x 444.4 dp post-inset);
 *  - 960 dp, wider still: the 680 dp cap means the row is the same, which the test shows;
 *  - the 360 dp compact screen (the square RG Rotate).
 * Each case writes a PNG of the top of the page, Search focused (so the ring's hug is on the picture), to `build/test-captures/cmsearch/`. Pixel values are the JVM's; the pixels on a
 * handheld are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w1920dp-h1080dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CustomMediaSearchSeatRenderTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val authority = DefaultFocusAuthority()

    private val menu = HomeTileMenuState(
        row = HomeShelfRow(
            itemId = HomeShelfItemId.Emulated(GameId("switch/totk")),
            rank = 1L,
            addedAtMillis = 10L,
            title = TotkTitle,
            platformId = PlatformId.SWITCH,
        ),
        removable = true,
    )
    private val types = homeTileCustomMediaTypes(menu)
    private val typeFilters = customMediaTypeFilters(menu, types)

    private fun stateFor(type: CustomMediaType) = HomeCustomEnrichState(
        sessionId = "seat-${type.label}",
        kind = type.kind,
        filters = typeFilters.getValue(type.selection),
        shelfIconArt = type.isMergedIconArt,
        cacheKey = mediaStorageKey("switch/totk", type.mediaState),
        gameTitle = TotkTitle,
        queryDraft = TotkTitle,
        mediaState = type.mediaState,
        mediaTypes = types,
        mediaTypeFilters = typeFilters,
        defaultArt = HomeCustomEnrichDefaultArt.TitleBook(TotkTitle, "switch"),
    )

    private var shown by mutableStateOf(stateFor(types.first()))
    private var density = 2f

    @After
    fun resetAnimators() = setAnimatorScale(1f)

    private fun setAnimatorScale(scale: Float) {
        ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, scale)
    }

    private fun settle() {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(1_000L)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    private fun mount(widthDp: Float, heightDp: Float, font: Float) {
        setAnimatorScale(0f)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, font)) {
                MimirTheme {
                    Box(Modifier.requiredSize(widthDp.dp, heightDp.dp)) {
                        HomeCustomEnrichPickerOverlay(
                            authority = authority,
                            state = shown,
                            onAction = {},
                            onQueryChange = {},
                            onKeyboardDone = {},
                        )
                    }
                }
            }
        }
        settle()
        settle()
    }

    private fun nodes(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()

    private fun rect(tag: String): Rect = nodes(tag).first().boundsInRoot

    private fun dp(px: Float) = px / density

    private fun textsUnder(root: SemanticsNode): List<Pair<Rect, TextLayoutResult>> {
        val out = ArrayList<Pair<Rect, TextLayoutResult>>()
        fun walk(node: SemanticsNode) {
            val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action
            if (action != null) {
                val results = ArrayList<TextLayoutResult>()
                action.invoke(results)
                results.firstOrNull()?.let { out += node.boundsInRoot to it }
            }
            node.children.forEach(::walk)
        }
        walk(root)
        return out
    }

    /** What Search drew: its text, or null when it is only the magnifier. */
    private fun searchText(): String? {
        val plate = rect(CustomMediaSearchPlateTag)
        return textsUnder(nodes(CustomMediaSearchTag).first())
            .firstOrNull { (box, _) -> box.left >= plate.left - 0.5f && box.right <= plate.right + 0.5f }
            ?.second?.layoutInput?.text?.text
    }

    private fun drawWindow(): Bitmap {
        val decor = compose.activity.window.decorView
        val full = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(android.graphics.Canvas(full))
        return full
    }

    /** The whole page as it opens (the first type, Icon art: every filter on its all/any value): the panel and a margin of the window round it. */
    private fun writePage(name: String) {
        val panel = nodes(HomeCustomEnrichPanelTestTag).first().boundsInRoot
        val window = drawWindow()
        val padPx = (24f * density).toInt()
        val left = (panel.left.toInt() - padPx).coerceAtLeast(0)
        val top = (panel.top.toInt() - padPx).coerceAtLeast(0)
        val right = (panel.right.toInt() + padPx).coerceAtMost(window.width)
        val bottom = (panel.bottom.toInt() + padPx).coerceAtMost(window.height)
        val crop = Bitmap.createBitmap(window, left, top, right - left, bottom - top)
        val out = File("${System.getenv("MIMIR_RENDER_DIR") ?: "build/test-captures/cmsearch"}/$name.png").apply { parentFile?.mkdirs() }
        out.outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The top of the page: the panel's width, from its top to a little under the top row. */
    private fun writeTop(name: String) {
        val panel = nodes(HomeCustomEnrichPanelTestTag).first().boundsInRoot
        val row = rect(CustomMediaTopRowTag)
        val window = drawWindow()
        val padPx = (24f * density).toInt()
        val left = (panel.left.toInt() - padPx).coerceAtLeast(0)
        val top = (panel.top.toInt() - padPx).coerceAtLeast(0)
        val right = (panel.right.toInt() + padPx).coerceAtMost(window.width)
        val bottom = (row.bottom.toInt() + padPx).coerceAtMost(window.height)
        val crop = Bitmap.createBitmap(window, left, top, right - left, bottom - top)
        val out = File("${System.getenv("MIMIR_RENDER_DIR") ?: "build/test-captures/cmsearch"}/$name.png").apply { parentFile?.mkdirs() }
        out.outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("the render was written: ${out.path}", out.isFile && out.length() > 1_000L)
    }

    private fun assertWholeOrWordCut(where: String, full: String, drawn: String) {
        if (drawn == full) return
        assertTrue("$where: a shortened name ends in the ellipsis: '$drawn'", drawn.endsWith("…"))
        val stem = drawn.removeSuffix("…")
        assertTrue("$where: '$stem' starts '$full'", full.startsWith(stem))
        assertTrue("$where: the cut stands where a word ends, not inside one: '$drawn' of '$full'", full.length > stem.length && full[stem.length].isWhitespace())
    }

    /**
     * Walks the four types at one viewport and font and checks the page's one Search: the same target width in every type, a name that is whole or cut at a word (or the
     * magnifier, in the same types for the same reason), the row on one line, and the ring on the plate.
     */
    private fun walk(viewport: String, widthDp: Float, heightDp: Float, font: Float, expectName: Boolean, density: Float = 2f) {
        this.density = density
        mount(widthDp, heightDp, font)
        val widths = linkedMapOf<String, Float>()
        val lefts = linkedMapOf<String, Float>()
        val drawn = linkedMapOf<String, String?>()
        val tops = linkedMapOf<String, Float>()
        types.forEach { type ->
            val next = stateFor(type)
            // A bare global write would poison every later Compose class in this fork (AppNotIdle); wrap it.
            Snapshot.withMutableSnapshot { shown = next }
            settle()
            authority.requestFocus(homeCustomEnrichSearchKey(next))
            settle()
            val where = "$viewport font $font ${type.label}"
            val search = rect(CustomMediaSearchTag)
            val plate = rect(CustomMediaSearchPlateTag)
            val media = rect(CustomMediaTypeChipTag)
            val row = rect(CustomMediaTopRowTag)
            widths[type.label] = dp(search.width)
            lefts[type.label] = dp(search.left - row.left)
            tops[type.label] = dp(row.top)
            // The game's name above the row (reported at 360 dp and font 1.3): whole, or cut at a WORD with an ellipsis, on one line, inside the page.
            val titleNode = nodes(CustomMediaTitleTag).first()
            val title = textsUnder(titleNode).firstOrNull()?.second?.layoutInput?.text?.text
            assertTrue("$where: the title draws its text", title != null)
            assertWholeOrWordCut("$where title", TotkTitle, title!!)
            assertTrue("$where: the title stands inside the row: ${titleNode.boundsInRoot} vs ${row}", titleNode.boundsInRoot.right <= row.right + 0.5f)
            assertTrue("$where: the title is one line: ${dp(titleNode.boundsInRoot.height)} dp", dp(titleNode.boundsInRoot.height) < 2f * 24f * font)
            val text = searchText()
            drawn[type.label] = text
            assertTrue("$where: the row starts with Search", search.left <= row.left + 0.5f)
            assertTrue("$where: Media follows Search with the gap: ${dp(media.left)} vs ${dp(search.right)}", media.left >= search.right - 0.5f)
            assertTrue("$where: Search and Media stand on one line", abs(search.center.y - media.center.y) < 4f)
            assertTrue("$where: the plate stands inside Search's target", plate.left >= search.left - 0.5f && plate.right <= search.right + 0.5f)
            if (text != null) {
                assertWholeOrWordCut(where, TotkTitle, text)
            } else {
                assertTrue("$where: collapsed, the plate is the magnifier mark: ${dp(plate.width)} dp", dp(plate.width) <= 41f)
            }
            println("CUSTOM-MEDIA-SEAT $where: Search ${dp(search.width)} dp (plate ${dp(plate.width)} dp), draws ${text ?: "the magnifier only"}; Media ${dp(media.left)}..${dp(media.right)} of the row's ${dp(row.width)} dp")
            writeTop("$viewport-font$font-${type.label.replace(' ', '-').lowercase()}")
            if (type == types.first()) writePage("$viewport-font$font-opening-page")
        }
        // The Search seating contract: Search is ONE width and stands in ONE place whatever the type.
        val seats = widths.values
        assertTrue("$viewport font $font: Search keeps one width across the types: $widths", seats.max() - seats.min() <= 0.5f)
        assertTrue("$viewport font $font: and one place: $lefts", lefts.values.max() - lefts.values.min() <= 0.5f)
        // And the top row stands at one height whatever the type: the title above it never pushes it.
        assertTrue("$viewport font $font: the top row does not move between types: $tops", tops.values.max() - tops.values.min() <= 0.5f)
        // So the verdict on the name is the same in every type: either every type shows it (whole or cut at a word) or every type shows the magnifier.
        assertEquals("$viewport font $font: one verdict on the name across the types: $drawn", 1, drawn.values.map { it == null }.distinct().size)
        if (expectName) {
            assertFalse("$viewport font $font: the long name is cut at a word, not collapsed to the magnifier: $drawn", drawn.values.first() == null)
        }
    }

    // The Main widths: the Odin's, the Thor's (same width, a shorter post-inset height), and a wider one.

    @Test
    fun `Odin Main font 1_0 keeps Search one width and cuts the name at a word`() =
        walk("odin-main", 832.5f, 468.3f, 1.0f, expectName = true, density = OdinDensity)

    @Test
    fun `Odin Main font 1_3 keeps Search one width`() =
        walk("odin-main", 832.5f, 468.3f, 1.3f, expectName = false, density = OdinDensity)

    @Test
    fun `Thor Main font 1_0 keeps Search one width and cuts the name at a word`() =
        walk("thor-main", 832.5f, 444.4f, 1.0f, expectName = true, density = OdinDensity)

    @Test
    fun `Thor Main font 1_3 keeps Search one width`() =
        walk("thor-main", 832.5f, 444.4f, 1.3f, expectName = false, density = OdinDensity)

    @Test
    fun `a 960 dp Main font 1_0 keeps Search one width and cuts the name at a word`() =
        walk("wide-960", 960f, 540f, 1.0f, expectName = true)

    @Test
    fun `a 960 dp Main font 1_3 keeps Search one width`() =
        walk("wide-960", 960f, 540f, 1.3f, expectName = false)

    // The compact screen: 360 x 360 dp. The widest type's chips leave Search only its mark here, in every type.

    @Test
    @Config(sdk = [30], qualifiers = "w360dp-h360dp-xhdpi")
    fun `a 360 dp compact screen font 1_0 keeps Search one width`() =
        walk("compact-360", 360f, 360f, 1.0f, expectName = false)

    @Test
    @Config(sdk = [30], qualifiers = "w360dp-h360dp-xhdpi")
    fun `a 360 dp compact screen font 1_3 keeps Search one width`() =
        walk("compact-360", 360f, 360f, 1.3f, expectName = false)

    private companion object {
        const val TotkTitle = "The Legend of Zelda Tears of the Kingdom"

        /** 1920 px across 832.5 dp: the Odin's (and the Thor's) Main density. */
        const val OdinDensity = 1920f / 832.5f
    }
}
