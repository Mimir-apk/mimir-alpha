package com.gruning.mimir.rewrite

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.Density
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.ui.MimirKeyboardPanelTag
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirTheme
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
 * Sweep 10-09 (F10): the Rename keyboard sheet's inner padding was uneven on Main: about 7 px between its top edge and the field, 16 below the last key row, 26 at the sides. The floating
 * panel's air is now one value on all four sides, the larger of the two it had ([MimirSpacing.md]). This renders the real [GameTitleEditOverlay] at Main's 832 x 468 dp (mdpi, 1 px = 1 dp)
 * at font 1.0 and 1.3 and reads the gap from the panel's edge to everything inside it, at the top, bottom, left and right. PNGs land in `build/test-captures/sweep28b/`; the pixels are the JVM's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w832dp-h468dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GameTitleEditKeyboardPaddingTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun descendants(node: SemanticsNode): List<SemanticsNode> = node.children.flatMap { listOf(it) + descendants(it) }

    private fun check(fontScale: Float, fileName: String) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                MimirTheme {
                    GameTitleEditOverlay(
                        request = GameTitleEditRequest(GameId("tetris"), "Tetris", hasOverride = false),
                        onSave = { _, _ -> },
                        onReset = {},
                        onCancel = {},
                    )
                }
            }
        }
        compose.waitForIdle()

        val panelNode = compose.onAllNodesWithTag(MimirKeyboardPanelTag, useUnmergedTree = true).fetchSemanticsNodes().first()
        val panel: Rect = panelNode.boundsInRoot
        // Everything the panel holds that has a place: the field, the tool row, every key. The union of their bounds is what the air is measured to.
        val inside = descendants(panelNode).map { it.boundsInRoot }.filter { it.width > 0f && it.height > 0f }
        val union = Rect(inside.minOf { it.left }, inside.minOf { it.top }, inside.maxOf { it.right }, inside.maxOf { it.bottom })
        val top = union.top - panel.top
        val bottom = panel.bottom - union.bottom
        val left = union.left - panel.left
        val right = panel.right - union.right
        println("RENAMEPAD font $fontScale: panel=$panel union=$union top=$top bottom=$bottom left=$left right=$right")

        val air = MimirSpacing.md.value
        assertEquals("the air above the field", air, top, 1f)
        assertEquals("the air below the last key row", air, bottom, 1f)
        assertEquals("the air at the left", air, left, 1f)
        assertEquals("the air at the right", air, right, 1f)

        val decor = compose.activity.window.decorView
        val full = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(full))
        val out = File(File("build/test-captures/sweep28b").apply { if (!isDirectory) mkdirs() }, fileName)
        out.outputStream().use { full.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("RENAMEPAD wrote ${out.absolutePath}")
        assertTrue("the PNG was written: ${out.absolutePath}", out.isFile && out.length() > 1_000L)
    }

    @Test
    fun `font 1 - the Rename keyboard's air is the same on all four sides`() {
        check(1f, "f10-rename-keyboard-1.0.png")
    }

    @Test
    fun `font 1_3 - and the same at the larger type`() {
        check(1.3f, "f10-rename-keyboard-1.3.png")
    }
}
