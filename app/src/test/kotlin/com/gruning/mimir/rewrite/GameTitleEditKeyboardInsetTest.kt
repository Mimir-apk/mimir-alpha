package com.gruning.mimir.rewrite

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.gruning.mimir.rewrite.core.model.GameId
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
 * Emulator report: the Rename keyboard ran to the very bottom of the screen with no margin. It now stands the Customize media keyboard sheet's own bottom
 * inset ([EnrichKeyboardSheetAirDp]) above the screen's bottom edge, as the same all-corner floating panel. This renders the real [GameTitleEditOverlay] at Thor
 * Main's 832 x 468 and reads where the keyboard's panel ends; the overlay is saved as a PNG under this module's `build/reports` (the path is printed).
 * Pixels are the JVM's; the handheld's are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w832dp-h468dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GameTitleEditKeyboardInsetTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `the Rename keyboard stands the Customize keyboard sheet's bottom inset above the screen's edge`() {
        compose.setContent {
            MimirTheme {
                GameTitleEditOverlay(
                    request = GameTitleEditRequest(GameId("tetris"), "Tetris", hasOverride = false),
                    onSave = { _, _ -> },
                    onReset = {},
                    onCancel = {},
                )
            }
        }
        compose.waitForIdle()

        val decor = compose.activity.window.decorView
        val screenBottom = decor.height.toFloat()
        // Every touch target on the overlay: the scrim (the whole screen), the keyboard's panel (which swallows touches in its own bounds) and its keys.
        // The scrim is the one as tall as the screen; the panel is the lowest of the rest, and the keys all end above it.
        val targets = compose.onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
        val panel = targets.filter { it.height < screenBottom - 1f }.maxByOrNull { it.bottom }
        assertTrue("the keyboard's panel is on the overlay (targets=$targets)", panel != null)
        val inset = screenBottom - panel!!.bottom
        println("RENAME-KEYBOARD panel=$panel screenBottom=$screenBottom inset=$inset")
        assertEquals("the panel ends the Customize sheet's inset above the screen's bottom edge", EnrichKeyboardSheetAirDp.toFloat(), inset, 0.75f)
        assertTrue("and its keys are above that, not on the edge", targets.none { it.bottom > panel.bottom + 0.5f && it.height < screenBottom - 1f })

        val full = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(full))
        val out = File(File(System.getProperty("user.dir"), "build/reports"), "rename-keyboard-bottom-inset.png")
        out.parentFile?.mkdirs()
        out.outputStream().use { full.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("RENAME-KEYBOARD-PNG ${out.absolutePath}")
        assertTrue("the PNG was written: ${out.absolutePath}", out.isFile && out.length() > 0)
    }
}
