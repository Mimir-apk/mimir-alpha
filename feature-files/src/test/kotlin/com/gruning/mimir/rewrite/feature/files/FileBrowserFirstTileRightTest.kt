package com.gruning.mimir.rewrite.feature.files

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.files.FileItem
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * Thor drive 3 on combined45 (`THOR.md`, sweep): "the QUEUE chip is one DPAD-RIGHT from the first Files tile". **Does not reproduce.** RIGHT moves along the grid row; it
 * does not leave the folio for the header's QUEUE plate (a touch shortcut, deliberately not a focus participant: `FileBrowserQueueHeaderPlate`) and does not open the
 * queue screen (only START and the plate's tap do). Pinned here on the whole [FileBrowserSurface] with a real [DefaultFocusAuthority], on the Drives page's own two
 * tiles and in a one-tile row, at font scales (1.0, 1.3) and on the Thor's Main geometry (833 x 468 dp at 2.306) as well as 960 x 540.
 *
 * What the drive's own log shows is touch, not a pad press: between the Drives frame (`fl0_main.png`, 07:41:37) and the queue screen's (`fl1_main.png`, 07:42:04) there are two
 * touch interactions (07:41:45.043 and 07:41:46.720, the gesture monitors and `PointerEventDispatcher0` in the line; `MimirMotionGate source=TOUCH root=PRIMARY`) and no
 * `source=CONTROLLER` transition after the idle at 07:41:40.358. The same drive notes the first RIGHT after idle being eaten (THOR.md, section 3).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FileBrowserFirstTileRightTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val root = "/storage"
    private val authority = DefaultFocusAuthority()

    private fun dir(name: String) = FileItem(name = name, path = "$root/$name", isDirectory = true)

    private fun file(name: String) = FileItem(name = name, path = "$root/$name", size = 36L)

    private fun mount(
        listing: List<FileItem>,
        fontScale: Float,
        widthDp: Float = 960f,
        heightDp: Float = 540f,
        density: Float = 2f,
    ): FileBrowserController {
        val controller = FileBrowserController(
            authority = authority,
            rootPath = root,
            mode = FileBrowserMode.MANAGE,
            listDirectory = { path -> if (path == root) listing else emptyList() },
            onChoose = {},
            onDismiss = {},
        )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                MimirTheme {
                    Box(Modifier.requiredSize(widthDp.dp, heightDp.dp)) {
                        // The queue exists (the header plate and the START hint are drawn), as on the handheld.
                        FileBrowserSurface(controller = controller, authority = authority, queue = emptyList(), modifier = Modifier)
                    }
                }
            }
        }
        settle()
        assertTrue("the queue is wired, so the header plate is drawn as on the handheld", controller.queueAvailable)
        return controller
    }

    private fun settle() {
        ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS)
        compose.mainClock.advanceTimeBy(1_500L)
        compose.waitForIdle()
    }

    /** One pad RIGHT, taken or not: what it returns, and where focus is after. */
    private fun right(controller: FileBrowserController): Boolean {
        var taken = false
        compose.runOnUiThread { taken = controller.dispatch(ControllerAction.NAV_RIGHT) }
        settle()
        return taken
    }

    private fun tile(name: String) = FileBrowserFocus.tileKey("$root/$name")

    private fun assertStillBrowsing(controller: FileBrowserController) {
        assertFalse("RIGHT never opens the queue", controller.queueOpen)
        assertFalse("nor search", controller.searchOpen)
    }

    private fun drives(fontScale: Float, widthDp: Float = 960f, heightDp: Float = 540f, density: Float = 2f) {
        val controller = mount(listOf(dir("android"), dir("Internal Storage")), fontScale, widthDp, heightDp, density)
        assertEquals("the first tile is focused", tile("android"), authority.state.value.focused)
        right(controller)
        assertEquals("RIGHT lands on the next tile of the row, at font $fontScale", tile("Internal Storage"), authority.state.value.focused)
        assertStillBrowsing(controller)
    }

    @Test
    fun `the drives page, one RIGHT from the first tile lands on the second, at font 1,0`() = drives(1.0f)

    @Test
    fun `the drives page, one RIGHT from the first tile lands on the second, at font 1,3`() = drives(1.3f)

    @Test
    fun `the drives page on the Thor's Main display (833 x 468 dp), RIGHT lands on the second tile, at font 1,0`() = drives(1.0f, 833f, 468f, 2.306f)

    @Test
    fun `the drives page on the Thor's Main display (833 x 468 dp), RIGHT lands on the second tile, at font 1,3`() = drives(1.3f, 833f, 468f, 2.306f)

    @Test
    fun `a one-tile row keeps the ring on its only tile and never reaches the queue, at font 1,0`() {
        val controller = mount(listOf(dir("android")), 1.0f)
        assertEquals(tile("android"), authority.state.value.focused)
        assertTrue("the press is taken, not passed down to Home underneath", right(controller))
        assertEquals("nothing is to its right: the ring stays", tile("android"), authority.state.value.focused)
        assertStillBrowsing(controller)
    }

    @Test
    fun `a one-tile row keeps the ring on its only tile and never reaches the queue, at font 1,3`() {
        val controller = mount(listOf(dir("android")), 1.3f)
        assertEquals(tile("android"), authority.state.value.focused)
        assertTrue("the press is taken, not passed down to Home underneath", right(controller))
        assertEquals("nothing is to its right: the ring stays", tile("android"), authority.state.value.focused)
        assertStillBrowsing(controller)
    }

    @Test
    fun `a files-only folder, RIGHT from the first file lands on the second, at font 1,3`() {
        val controller = mount(listOf(file("a.gba"), file("b.gba"), file("c.gba")), 1.3f)
        assertEquals(tile("a.gba"), authority.state.value.focused)
        right(controller)
        assertEquals(tile("b.gba"), authority.state.value.focused)
        assertStillBrowsing(controller)
    }
}
