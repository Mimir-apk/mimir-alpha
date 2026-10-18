package com.gruning.mimir.rewrite.feature.companion

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.model.CompanionAppEntry
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Thor regression: long-press the ᛗ, TABS, then the grid icon opens Quick Launch, and the Tabs panel's copy showed through Quick Launch's tiles. Two faults: the Tabs panel
 * was left standing under the picker (the grid icon is the Tabs drawer's own rail seat, and the picker is drawn in the same window), and the picker's body was glass (0.78 / 0.88 alpha).
 *
 * Now the picker clears the Tabs panel while it is up, its ground is the theme's hero material at full alpha, and closing it closes the drawer with it. A pick keeps the drawer, as it did.
 *
 * Fix 29 G1 (sweep vqa8, Thor, 1.0 and 1.3): the drawer's own rail (the grid, the bars, the + marks) was still drawn under the picker. The picker's card stops 7 % short of the bottom edge,
 * so the seats' lower ends showed under it as cut slivers. The rail now leaves with the Tabs panel: nothing of Tabs is on the screen while the picker is up, and the strip below the card is
 * the scrim over the field and nothing else ([checkNothingOfTabsBelowTheCard]).
 *
 * Rendered at the Bottom's 538 x 468 dp at 369 dpi (in a 520 dp window, so the whole scene is on screen), at font scales 1.0 and 1.3, over a stand-in field no Mimir surface wears, so any of it showing through is plain. The PNGs are
 * written to `build/test-captures/fix29a/` and `feature-companion/build/fix29a/`. Pixel values are the JVM's; a handheld's are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h520dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionQuickLaunchFromTabsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val field = Color(0xFFD81B8A)
    private val tabsCopy = "Apps opened from Mimir appear here. Earlier apps may still be running."
    private val apps = listOf("Calendar", "Camera", "Chrome", "Clock", "Contacts", "Drive", "Files", "Gmail", "Maps")
        .map { CompanionAppEntry("org.example.${it.lowercase()}", it) }

    private var dismissed = 0
    private val launched = mutableListOf<String>()
    private var density = 0f

    private fun clickLabel(label: String) = SemanticsMatcher("click label '$label'") {
        it.config.getOrNull(SemanticsActions.OnClick)?.label == label
    }

    private fun mount(fontScale: Float) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(Density369, fontScale)) {
                density = LocalDensity.current.density
                Box(Modifier.requiredSize(538.dp, 468.dp).background(field).testTag(SceneTag)) {
                    CompanionRecentsDrawer(
                        modifier = Modifier.fillMaxSize(),
                        entries = emptyList(),
                        launchLocked = false,
                        launchMessage = null,
                        themeId = null,
                        quickSlots = List(5) { null },
                        quickLaunchApps = apps,
                        onQuickLaunch = { launched += it },
                        onQuickAssign = { _, _ -> },
                        onLaunch = {},
                        onClose = {},
                        onDismiss = { dismissed++ },
                    )
                }
            }
        }
        settle()
    }

    private fun settle(ms: Long = 2_000L) {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(ms)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    private fun tapAt(point: Offset) {
        compose.onRoot().performTouchInput { click(point) }
        settle(2_000L)
    }

    private fun count(matcher: SemanticsMatcher) = compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().size

    /** What a screen reader finds: the merged tree, where a node that clears its semantics takes its descendants with it (the unmerged tree keeps them). */
    private fun announced(matcher: SemanticsMatcher) = compose.onAllNodes(matcher, useUnmergedTree = false).fetchSemanticsNodes().size

    private fun tabsPanels() = compose.onAllNodesWithTag(CompanionRecentsPanelTag, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun tabsCopies() = compose.onAllNodesWithContentDescription(tabsCopy, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun frame(): Bitmap {
        val decor = compose.activity.window.decorView
        val full = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(full))
        val scene = compose.onNodeWithTag(SceneTag).fetchSemanticsNode().boundsInWindow
        val left = scene.left.toInt().coerceAtLeast(0)
        val top = scene.top.toInt().coerceAtLeast(0)
        return Bitmap.createBitmap(full, left, top, minOf(scene.width.toInt(), full.width - left), minOf(scene.height.toInt(), full.height - top))
    }

    private fun savePng(fileName: String) {
        val bitmap = frame()
        val dir = File("build/fix29a").apply { mkdirs() }
        val out = File(dir, fileName)
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val shared = File("build/test-captures/fix29a").apply { mkdirs() }
        if (shared.isDirectory) out.copyTo(File(shared, fileName), overwrite = true)
        println("FIX29A wrote ${out.absolutePath} (${out.length()} bytes, ${bitmap.width}x${bitmap.height} px)")
        assertTrue("the PNG was written: ${out.path}", out.isFile && out.length() > 1_000L)
    }

    private fun openQuickLaunch() {
        val seat = compose.onAllNodes(clickLabel("All apps"), useUnmergedTree = true).fetchSemanticsNodes().single().boundsInRoot.center
        tapAt(seat)
    }

    /** The panel's own ground: a pixel in its left padding, mid-height, where nothing but the ground is drawn. */
    private fun groundProbe(): Pair<Int, Int> = (538f * 0.10f * density + 8f * density).toInt() to (468f * 0.5f * density).toInt()

    private fun Color.dist(other: Color) = maxOf(abs(red - other.red), abs(green - other.green), abs(blue - other.blue))

    /**
     * The strip under the picker's card (its bottom edge is at 93 % of the scene's height: it is 86 % tall and centred): every row of it, from the card's edge down to the bottom of the
     * Bottom, is one flat colour (the scrim over the stand-in field). Each row is held to its own leftmost pixel, so a scrim that grades with height cannot fail it; a rail seat's lower end
     * (the grid's dots, a bar, a + mark) is a handful of pixels far from the scrim's colour and does. Returns the largest deviation seen, for the log.
     */
    private fun checkNothingOfTabsBelowTheCard(tag: String): Float {
        val bitmap = frame().asImageBitmap().toPixelMap()
        val firstRow = (bitmap.height * 0.93f).toInt() + (3f * density).toInt()
        var worst = 0f
        var worstAt = ""
        for (y in firstRow until bitmap.height) {
            val reference = bitmap[2, y]
            for (x in 0 until bitmap.width) {
                val d = bitmap[x, y].dist(reference)
                if (d > worst) { worst = d; worstAt = "$x,$y" }
            }
        }
        println("FIX29A $tag strip below the card: rows $firstRow..${bitmap.height - 1} of ${bitmap.height}, worst deviation from the row's own scrim = ${worst * 255f} /255 at $worstAt")
        assertTrue("nothing of Tabs shows under the card: the strip is the scrim only, worst deviation ${worst * 255f}/255 at $worstAt", worst <= 6f / 255f)
        return worst
    }

    private fun checkOpaqueQuickLaunchReplacesTabs(fontScale: Float, tag: String) {
        mount(fontScale)
        assertEquals("the Tabs panel is up before the grid icon is tapped", 1, tabsPanels())
        assertEquals("with its copy", 1, tabsCopies())
        savePng("tabs-$tag.png")

        openQuickLaunch()

        assertEquals("Quick Launch's picker is up", 1, count(clickLabel("Close app picker")))
        assertEquals("the Tabs panel was cleared, not left standing under it", 0, tabsPanels())
        assertEquals("so its copy cannot read through the tiles", 0, tabsCopies())
        assertEquals("the drawer's rail (its All apps seat) is gone with it, for a screen reader too", 0, announced(clickLabel("All apps")))
        assertEquals("and the rail itself is not announced", 0, compose.onAllNodesWithTag(CompanionQuickLaunchTag, useUnmergedTree = false).fetchSemanticsNodes().size)
        savePng("quick-launch-from-tabs-$tag.png")
        checkNothingOfTabsBelowTheCard(tag)

        val (x, y) = groundProbe()
        val pixel = frame().asImageBitmap().toPixelMap()[x, y]
        val ground = MimirThemePalette.forId(MimirThemeId.DEFAULT).materials.hero.coverMiddle
        println("FIX29A $tag ground probe at $x,$y = $pixel, hero coverMiddle = $ground, stand-in field = $field")
        assertTrue("the picker's body is its ground at full alpha, none of what is beneath it: $pixel vs $ground", pixel.dist(ground.copy(alpha = 1f)) <= 2f / 255f)
        assertTrue("and nothing of the stand-in field", pixel.dist(field) > 0.2f)
        compose.onNodeWithText("Calendar", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `the grid icon opens Quick Launch as the only panel, on an opaque ground, at font 1_0`() =
        checkOpaqueQuickLaunchReplacesTabs(1.0f, "f10")

    @Test
    fun `the grid icon opens Quick Launch as the only panel, on an opaque ground, at font 1_3`() =
        checkOpaqueQuickLaunchReplacesTabs(1.3f, "f13")

    @Test
    fun `closing Quick Launch closes the drawer with it and the Tabs panel does not come back on the way out`() {
        mount(1.0f)
        openQuickLaunch()
        assertEquals(0, dismissed)

        val close = compose.onAllNodes(clickLabel("Close app picker"), useUnmergedTree = true).fetchSemanticsNodes().single().boundsInRoot.center
        tapAt(close)

        assertEquals("the drawer was asked to close once", 1, dismissed)
        assertEquals("the picker is gone", 0, count(clickLabel("Close app picker")))
        assertEquals("and the Tabs panel is not drawn again while the drawer leaves", 0, tabsPanels())
        assertEquals("nor is the rail, which left with it", 0, announced(clickLabel("All apps")))
    }

    @Test
    fun `a pick keeps the drawer, and its panel (the launch lock and a failure's message) comes back`() {
        mount(1.0f)
        openQuickLaunch()
        assertEquals(0, tabsPanels())

        val chrome = compose.onNodeWithText("Chrome", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center
        tapAt(chrome)

        assertEquals(listOf("org.example.chrome"), launched)
        assertEquals("a pick is not a dismissal: the drawer was not asked to close", 0, dismissed)
        assertEquals("the picker closed", 0, count(clickLabel("Close app picker")))
        assertEquals("and the panel is back", 1, tabsPanels())
        assertEquals("with its rail", 1, announced(clickLabel("All apps")))
    }

    companion object {
        const val Density369 = 369f / 160f
        const val SceneTag = "fix29a-scene"
    }
}
