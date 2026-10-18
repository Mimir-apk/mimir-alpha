package com.gruning.mimir.rewrite.feature.files

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.files.FileItem
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Files root-folder focus ring appeared absent while the Mimir-button picker's grid showed one. The picker's folder grid IS the Files folio in another mode
 * (`FileBrowserMode.PICK_IMAGE`), so the two draw one tile: [TomeTileForCapture] hands `focused` to `MimirNorseBookCover`, whose `mimirFocusTileRing` is the ring every tome in the product
 * wears, and nothing in the tile's code depends on the mode. This draws the real folder tile unfocused and then focused, at the Thor's Main geometry and at font 1.0 and 1.3, and reads the
 * pixels in the band just above its top edge (where the ring's bright stroke sits, 0.5 to 3.5 dp out): bare ground unfocused, lit focused. The pixels are the JVM's; a handheld's are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w833dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FileBrowserTomeRingCaptureTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val authority = DefaultFocusAuthority()
    private val controller = FileBrowserController(
        authority = authority,
        rootPath = "/root",
        mode = FileBrowserMode.MANAGE,
        listDirectory = { emptyList() },
        onChoose = {},
        onDismiss = {},
    )
    private val tile = FileBrowserTile(
        item = FileItem(name = "Audiobooks", path = "/root/Audiobooks", isDirectory = true, childCount = 0),
        kind = FileBrowserTileKind.DIRECTORY,
        machinery = false,
        platformIdValue = null,
    )

    private fun capture(): Bitmap {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    private fun case(fontScale: Float) {
        var focused by mutableStateOf(false)
        val density = 369f / 160f
        compose.runOnUiThread { compose.activity.actionBar?.hide() }
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                MimirTheme {
                    val palette = LocalMimirPalette.current
                    Box(Modifier.requiredSize(833.dp, 468.dp).background(palette.surfaceBase)) {
                        Box(Modifier.padding(start = 60.dp, top = 40.dp).width(118.dp)) {
                            TomeTileForCapture(
                                tile = tile,
                                focused = focused,
                                authority = authority,
                                key = FileBrowserFocus.tileKey(tile.path),
                                scope = FileBrowserFocus.DefaultScope,
                                controller = controller,
                                palette = palette,
                                modifier = Modifier.width(118.dp),
                            )
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_500L)
        compose.waitForIdle()
        val tileNode = compose.onAllNodes(SemanticsMatcher.keyIsDefined(FileBrowserRingLitSemantics), useUnmergedTree = true).fetchSemanticsNodes().single()
        assertEquals("the tile reports itself unfocused", false, tileNode.config[FileBrowserRingLitSemantics])
        val bounds = tileNode.boundsInRoot
        val unfocused = capture()
        // The ground as the bitmap holds it (the window's own pixel format, not the palette's float), sampled clear of the tile.
        val ground = unfocused.getPixel(4, 4)

        compose.runOnUiThread { focused = true }
        compose.mainClock.advanceTimeBy(1_500L)
        compose.waitForIdle()
        val lit = compose.onAllNodes(SemanticsMatcher.keyIsDefined(FileBrowserRingLitSemantics), useUnmergedTree = true).fetchSemanticsNodes().single()
        assertEquals("the tile reports itself focused", true, lit.config[FileBrowserRingLitSemantics])
        val focusedBitmap = capture()

        // The straight stretch of the top edge, clear of the corners: the ring's bright stroke is the 3 dp from 0.5 dp out.
        val left = (bounds.left + 16f * density).toInt()
        val right = (bounds.right - 16f * density).toInt()
        val bandTop = (bounds.top - 3.5f * density).toInt()
        val bandBottom = (bounds.top - 0.5f * density).toInt()
        var bare = 0
        var struck = 0
        var total = 0
        for (y in bandTop until bandBottom) {
            for (x in left until right) {
                total += 1
                if (unfocused.getPixel(x, y) == ground) bare += 1
                if (focusedBitmap.getPixel(x, y) != ground) struck += 1
            }
        }
        println("TOMERING font $fontScale band=${right - left}x${bandBottom - bandTop} px: bare when unfocused $bare of $total, lit when focused $struck of $total")
        assertEquals("font $fontScale: with no focus the band above the tile is bare ground", total, bare)
        assertTrue("font $fontScale: with focus the ring is drawn above the tile ($struck of $total px lit)", struck > total * 9 / 10)
    }

    @Test
    fun `a focused folder tile draws the ring above its edge, at font 1 point 0`() = case(1.0f)

    @Test
    fun `a focused folder tile draws the ring above its edge, at font 1 point 3`() = case(1.3f)
}
