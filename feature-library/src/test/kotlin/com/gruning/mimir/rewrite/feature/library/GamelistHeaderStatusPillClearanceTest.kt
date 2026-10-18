package com.gruning.mimir.rewrite.feature.library

import android.app.Application
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onParent
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.ui.MimirLiquidStatusPill
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.mimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.theme.MimirColors
import com.gruning.mimir.rewrite.core.ui.theme.MimirSpacing
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Combined33 sweep (P2, a regression from the count's seat growing with the font): at font 1.3 on the 832.5 dp Main with Favorites on, the Library header let its Search pill run under
 * the status pill. The header left the room for the pill as it was at font 1.0 (136 dp); the pill's text is in sp and the pill is 166 dp wide at 1.3x, so Search ended past where the pill
 * began. The header now measures the pill ([com.gruning.mimir.rewrite.core.ui.mimirLiquidStatusPillWidthDp]) and the solver reserves its real width ([gamelistStatusRailDp]).
 *
 * The real [GamelistHeader] with the real [MimirLiquidStatusPill] in the host's seat (top end, 16 dp in), on Thor's Main (1920 x 1080 px), the emulator's 832.5 dp Main and the RP5
 * (700 x 350 dp), at density 2.306 (the emulator's) and 2.0 (the Nova's), font 1.0 and 1.3, with the Favorites count and with a plain count. Search ends short of the pill in every one, the tag
 * stands whole, and the title is cut only at a word. The header bands are written to `build/test-captures/sweepfix32/round3/`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w1100dp-h700dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GamelistHeaderStatusPillClearanceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val authority = DefaultFocusAuthority()
    private var pill = Rect.Zero

    private fun mount(widthDp: Float, heightDp: Float, density: Float, fontScale: Float, meta: String) {
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density, fontScale),
                LocalMimirViewportProfile provides mimirViewportProfile(widthDp, heightDp),
            ) {
                MimirTheme {
                    Box(Modifier.requiredSize(widthDp.dp, heightDp.dp).background(MimirColors.SurfaceBase)) {
                        Column(Modifier.fillMaxSize().padding(horizontal = LibraryGamelistBodyPaddingDp, vertical = MimirSpacing.md)) {
                            GamelistHeader(
                                title = "Super Nintendo",
                                metaLabel = meta,
                                showSearch = true,
                                searchQuery = "",
                                searchPlaceholder = "Search games",
                                authority = authority,
                                searchControllerEnabled = true,
                                searchFocused = false,
                                onOpenSearch = {},
                                onClearSearch = {},
                                segments = emptyList(),
                                segmentCounts = emptyMap(),
                                activeSegment = FavoriteSegment.GAMES,
                                onSelectSegment = {},
                            )
                        }
                        // The host's seat for the glance row: top end, 16 dp in (MimirAppHost).
                        MimirLiquidStatusPill(
                            clockLabel = "03:53",
                            batteryPercent = 100,
                            charging = false,
                            wifiConnected = true,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(16.dp)
                                .onGloballyPositioned { pill = it.boundsInRoot() },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun shown(startsWith: String): String =
        compose.onAllNodes(hasText(startsWith, substring = true), useUnmergedTree = true).fetchSemanticsNodes()
            .first { node -> node.config[SemanticsProperties.Text].joinToString("") { it.text }.startsWith(startsWith) }
            .config[SemanticsProperties.Text].joinToString("") { it.text }

    private fun save(bitmapWidthDp: Float, density: Float, name: String) {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        // The window's decor draws its own title bar above the composition: the band starts at the composition's top.
        val top = compose.onRoot().fetchSemanticsNode().boundsInWindow.top.toInt().coerceAtLeast(0)
        val band = Bitmap.createBitmap(
            bitmap,
            0,
            top,
            (bitmapWidthDp * density).toInt().coerceAtMost(bitmap.width),
            (80f * density).toInt().coerceAtMost(bitmap.height - top),
        )
        val out = File(File("build/test-captures/sweepfix32/round3").apply { mkdirs() }, name)
        out.outputStream().use { band.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("the PNG was written: $out", out.isFile && out.length() > 500L)
    }

    private fun check(surface: String, widthDp: Float, heightDp: Float, density: Float, fontScale: Float, favorites: Boolean) {
        val meta = if (favorites) "Favorites · 2 games" else "17 games"
        mount(widthDp, heightDp, density, fontScale, meta)
        val label = "$surface ${widthDp}x${heightDp} dp, density $density, font $fontScale, ${if (favorites) "Favorites" else "plain"}"
        val search = compose.onNode(hasContentDescription("Search games"), useUnmergedTree = true).onParent().fetchSemanticsNode().boundsInRoot
        val tag = compose.onNodeWithTag(GamelistLocalLibraryTagTestTag).fetchSemanticsNode().boundsInRoot
        val title = shown("Super")
        val count = shown(if (favorites) "Favorites ·" else "17")
        val gap = (pill.left - search.right) / density
        println("GAMELISTPILL $label: search ${search.left / density}..${search.right / density} dp (${search.width / density} wide), status pill from ${pill.left / density} dp, gap $gap dp, title <$title>, count <$count>, tag ${tag.width / density} dp")
        save(widthDp, density, "header-${surface.lowercase().replace(' ', '-')}-d${density}-f${fontScale}-${if (favorites) "fav" else "plain"}.png")
        assertTrue("$label: Search ends at ${search.right / density} dp, under the status pill that begins at ${pill.left / density} dp", gap >= 1.5f)
        assertEquals("$label: the count stands whole", meta, count)
        assertTrue("$label: the title is whole or cut at a word: <$title>", title == "Super Nintendo" || (title == "Super…"))
        assertTrue("$label: the tag stands whole (${tag.width / density} dp)", tag.width / density > 90f)
    }

    private val surfaces = listOf(
        Triple("Thor Main", 1920f, 1080f),
        Triple("Emulator Main", 832.5f, 468.3f),
        Triple("RP5", 700f, 350f),
    )

    private fun surface(index: Int, density: Float, fontScale: Float, favorites: Boolean) {
        val (name, a, b) = surfaces[index]
        // Thor's Main is 1920 x 1080 px: its dp are the pixels over the density.
        val widthDp = if (name == "Thor Main") a / density else a
        val heightDp = if (name == "Thor Main") b / density else b
        check(name, widthDp, heightDp, density, fontScale, favorites)
    }

    @Test fun `Thor Main at density 2_306, font 1_0, plain`() = surface(0, 2.306f, 1.0f, false)
    @Test fun `Thor Main at density 2_306, font 1_0, Favorites`() = surface(0, 2.306f, 1.0f, true)
    @Test fun `Thor Main at density 2_306, font 1_3, plain`() = surface(0, 2.306f, 1.3f, false)
    @Test fun `Thor Main at density 2_306, font 1_3, Favorites`() = surface(0, 2.306f, 1.3f, true)
    @Test fun `Thor Main at density 2_0, font 1_0, plain`() = surface(0, 2.0f, 1.0f, false)
    @Test fun `Thor Main at density 2_0, font 1_0, Favorites`() = surface(0, 2.0f, 1.0f, true)
    @Test fun `Thor Main at density 2_0, font 1_3, plain`() = surface(0, 2.0f, 1.3f, false)
    @Test fun `Thor Main at density 2_0, font 1_3, Favorites`() = surface(0, 2.0f, 1.3f, true)

    @Test fun `the emulator's 832_5 dp at density 2_306, font 1_0, plain`() = surface(1, 2.306f, 1.0f, false)
    @Test fun `the emulator's 832_5 dp at density 2_306, font 1_0, Favorites`() = surface(1, 2.306f, 1.0f, true)
    @Test fun `the emulator's 832_5 dp at density 2_306, font 1_3, plain`() = surface(1, 2.306f, 1.3f, false)
    @Test fun `the emulator's 832_5 dp at density 2_306, font 1_3, Favorites`() = surface(1, 2.306f, 1.3f, true)
    @Test fun `the emulator's 832_5 dp at density 2_0, font 1_0, plain`() = surface(1, 2.0f, 1.0f, false)
    @Test fun `the emulator's 832_5 dp at density 2_0, font 1_0, Favorites`() = surface(1, 2.0f, 1.0f, true)
    @Test fun `the emulator's 832_5 dp at density 2_0, font 1_3, plain`() = surface(1, 2.0f, 1.3f, false)
    @Test fun `the emulator's 832_5 dp at density 2_0, font 1_3, Favorites`() = surface(1, 2.0f, 1.3f, true)

    @Test fun `the RP5 at density 2_306, font 1_0, plain`() = surface(2, 2.306f, 1.0f, false)
    @Test fun `the RP5 at density 2_306, font 1_0, Favorites`() = surface(2, 2.306f, 1.0f, true)
    @Test fun `the RP5 at density 2_306, font 1_3, plain`() = surface(2, 2.306f, 1.3f, false)
    @Test fun `the RP5 at density 2_306, font 1_3, Favorites`() = surface(2, 2.306f, 1.3f, true)
    @Test fun `the RP5 at density 2_0, font 1_0, plain`() = surface(2, 2.0f, 1.0f, false)
    @Test fun `the RP5 at density 2_0, font 1_0, Favorites`() = surface(2, 2.0f, 1.0f, true)
    @Test fun `the RP5 at density 2_0, font 1_3, plain`() = surface(2, 2.0f, 1.3f, false)
    @Test fun `the RP5 at density 2_0, font 1_3, Favorites`() = surface(2, 2.0f, 1.3f, true)

    @Test
    fun `the solver reserves the pill's real width, and never less than the one measured at font 1_0`() {
        assertEquals(LibraryGamelistStatusEndInsetDp, gamelistStatusRailDp(149.3f), 0.01f)
        assertEquals(LibraryGamelistStatusEndInsetDp, gamelistStatusRailDp(120f), 0.01f)
        assertEquals("a pill of 166 dp reaches 166 - 16 + 2.5 into the header", 152.5f, gamelistStatusRailDp(166f), 0.01f)
        val one = resolveGamelistHeaderSearchGeometry(768.5f, compact = false)
        val grown = resolveGamelistHeaderSearchGeometry(768.5f, compact = false, statusRailDp = 153f)
        assertEquals(153f, grown.statusEndInsetDp, 0.01f)
        assertEquals("the title gives up what the rail takes", one.titleMaxWidthDp - 17f, grown.titleMaxWidthDp, 0.01f)
    }

    @Test
    fun `Search gives way first, keeping its seat, narrowing to what is left, then the magnifier, and only then does the title give way`() {
        fun room(room: Float) = gamelistSearchSqueezeDp(rowWidthDp = 259f + 126.5f + 20f + room, titleWholeDp = 259f, metaWholeDp = 126.5f, gapDp = 10f, fullSearchDp = 136f)
        assertEquals("room to spare: the full pill", 136f, room(200f)!!, 0.01f)
        assertEquals("exactly its seat", 136f, room(136f)!!, 0.01f)
        assertEquals("a little short: the pill narrows to what is left", 125f, room(125f)!!, 0.01f)
        assertEquals("at its readability floor", LibraryGamelistSearchReadabilityFloorDp, room(LibraryGamelistSearchReadabilityFloorDp)!!, 0.01f)
        assertEquals("under the floor: the magnifier", LibraryGamelistSearchMarkWidthDp, room(100f)!!, 0.01f)
        assertEquals("as wide as the magnifier is", LibraryGamelistSearchMarkWidthDp, room(LibraryGamelistSearchMarkWidthDp)!!, 0.01f)
        assertEquals("not even the magnifier: the title gives way", null, room(40f))
    }
}
