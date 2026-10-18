package com.gruning.mimir.rewrite.feature.files

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.files.FileItem
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * Folder-up, 2026-10-15. The Thor drive of combined44 (`THOR.md` check 5, `fl_up1.jpg`) measured the way up out of a folder at **513 ms** (predicted 340) with
 * **one near-empty frame**: the child faded off, the parent was still hidden, then appeared in one step at 0.88 and dipped in, and its ring and panel settled
 * after that. Its duration had already been reduced, and an empty frame is a dip, which the bar forbids.
 *
 * The whole [FileBrowserSurface] as production wires it (a real [DefaultFocusAuthority]; the store's per-path cache, so a listing seen before answers without
 * suspending and backing out costs nothing), on the paused frame clock, 16 ms a step from the press. What is read is not pixels (a software canvas cannot draw
 * the surface's recorded layers) but the four alphas that make the pane -- the departing child's, from the handoff itself, and the arriving page's own glide
 * and seat gate, from [FileBrowserMotionProbe] -- and the ring flag each tile paints with ([FileBrowserRingLitSemantics]). They are composed the way the screen
 * composes them: the child is the top layer over the parent, so `1 - (1 - child)(1 - parent)` is how much of one whole page is on screen. The bar: never a frame
 * below 0.75, and settled -- child gone, ring on the folder the player came out of -- by about the clear's own length.
 *
 * Measured on the JVM at the fix (16 ms beats from the press): the child holds whole for two beats (the handoff's own launch), then clears over the 180 ms; the listing and the
 * panel are whole under it on every frame (coverage 1.00, where the Thor had one frame near empty); the ring is lit on the third beat and has faded in inside the clear; and the child is
 * gone by about 210 ms, against the Thor's 513. The handheld's pixels are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FileBrowserFolderUpOverlapTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val root = "/storage/emulated/0"
    private val movies = "$root/Movies"

    private fun dir(name: String, parent: String = root, children: Int = 0) =
        FileItem(name = name, path = "$parent/$name", isDirectory = true, childCount = children)

    private fun file(name: String, parent: String) = FileItem(name = name, path = "$parent/$name", size = 36L)

    private val items: Map<String, List<FileItem>> = mapOf(
        root to listOf(dir("Alarms"), dir("Android", children = 3), dir("Audiobooks"), dir("DCIM"), dir("Documents"), dir("Download"), dir("FilesTest", children = 7), dir("Movies", children = 5)),
        movies to listOf(dir("Trailers", movies, children = 2), dir("Clips", movies), file("a.mp4", movies), file("b.mp4", movies), file("c.mp4", movies)),
    )

    private val authority = DefaultFocusAuthority()
    private val probe = FileBrowserMotionProbe()
    /** The store's per-path cache. The root is in it: the player came out of it, which is the only way to be going back up to it. */
    private val cache = mutableMapOf(root to items.getValue(root))

    /** Wired like `FileBrowserStore`: a cold listing hops (here, a short delay); one seen before answers from memory without suspending. */
    private fun controller() = FileBrowserController(
        authority = authority,
        rootPath = root,
        mode = FileBrowserMode.MANAGE,
        listDirectory = { path -> items[path].orEmpty() },
        listDirectoryAsync = { path -> cache[path] ?: run { delay(50); items[path].orEmpty().also { cache[path] = it } } },
        loadScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
        onChoose = {},
        onDismiss = {},
    )

    private fun mount(controller: FileBrowserController) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, 1f), LocalFileBrowserMotionProbe provides probe) {
                MimirTheme {
                    Box(Modifier.requiredSize(960.dp, 540.dp)) {
                        FileBrowserSurface(controller = controller, authority = authority, modifier = Modifier)
                    }
                }
            }
        }
        settle()
    }

    private fun settle() {
        ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS)
        compose.mainClock.advanceTimeBy(1_500L)
        compose.waitForIdle()
    }

    private fun press(controller: FileBrowserController, vararg actions: ControllerAction) {
        actions.forEach { action ->
            compose.runOnUiThread { assertTrue("$action is taken", controller.dispatch(action)) }
            settle()
        }
    }

    /** The folder tiles' ring flags, in reading order. */
    private fun ringFlags(): List<Boolean> = compose.onAllNodes(
        SemanticsMatcher.keyIsDefined(FileBrowserRingLitSemantics),
        useUnmergedTree = true,
    ).fetchSemanticsNodes()
        .sortedWith(compareBy<SemanticsNode>({ Math.round(it.positionInRoot.y) / 40 }, { it.positionInRoot.x }))
        .map { it.config.getOrNull(FileBrowserRingLitSemantics) == true }

    private class Frame(
        val ms: Int,
        val departing: Boolean,
        val listingChild: Float,
        val listingParent: Float,
        val panelChild: Float,
        val panelParent: Float,
        val ringLit: Boolean,
    ) {
        /** How much of one whole page is on screen: the child is the top layer over the parent, over the ground. */
        val listingCoverage: Float get() = 1f - (1f - listingChild) * (1f - listingParent)
        val panelCoverage: Float get() = 1f - (1f - panelChild) * (1f - panelParent)

        override fun toString() = "t=$ms child=%.2f parent=%.2f panel=%.2f/%.2f ring=$ringLit".format(listingChild, listingParent, panelChild, panelParent)
    }

    private fun sample(ms: Int) = Frame(
        ms = ms,
        departing = probe.listing.departing() || probe.panel.departing(),
        listingChild = probe.listing.outgoingAlpha(),
        // The arriving listing paints at its glide alpha, and not at all on the one frame the seat gate hides it.
        listingParent = probe.listingGlideAlpha() * if (probe.listingSeated()) 1f else 0f,
        panelChild = probe.panel.outgoingAlpha(),
        panelParent = probe.panelGlideAlpha(),
        ringLit = ringFlags().any { it },
    )

    /** One 16 ms beat of the paused clock, the cold-listing delay riding the same beat. */
    private fun beat() {
        ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS)
        compose.mainClock.advanceTimeBy(16L)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    /** Mounts, goes into Movies (a cold load), and is at rest there with the clock paused. */
    private fun atRestInMovies(): FileBrowserController {
        val controller = controller()
        mount(controller)
        // Movies is the eighth tile: three to the right, one down.
        press(controller, ControllerAction.NAV_RIGHT, ControllerAction.NAV_RIGHT, ControllerAction.NAV_RIGHT, ControllerAction.NAV_DOWN, ControllerAction.CONFIRM)
        assertEquals(movies, controller.currentPath)
        assertTrue("the child has a ring of its own at rest", ringFlags().any { it })
        compose.mainClock.autoAdvance = false
        return controller
    }

    @Test
    fun `going up is one whole page on every frame, and settled with the clear`() {
        val controller = atRestInMovies()
        compose.runOnUiThread { assertTrue("B goes up", controller.dispatch(ControllerAction.CANCEL)) }
        Snapshot.sendApplyNotifications()

        val frames = (1..30).map { beat(); sample(it * 16) }
        val log = frames.joinToString("\n")

        assertEquals(root, controller.currentPath)
        assertTrue("the child departs as recorded pixels on the first beat\n$log", frames.first().departing)
        assertTrue("and it is whole there, so the arrival has not left a gap before it: ${frames.first()}", frames.first().listingChild > 0.9f)
        // The bar (lead, 2026-10-15): at least about 0.75 through the whole gesture, the listing and the panel both.
        val listing = frames.minOf { it.listingCoverage }
        val panel = frames.minOf { it.panelCoverage }
        assertTrue("the listing is never below 0.75 of a page: $listing\n$log", listing >= 0.75f)
        assertTrue("nor the panel: $panel\n$log", panel >= 0.75f)
        // What the design promises is stronger: the parent stands whole under the child from the beat it is seated, so the pane never dips.
        assertTrue("the parent is whole once seated, never dipping in: ${frames.map { it.listingParent }}", frames.drop(2).all { it.listingParent == 1f })
        assertTrue("and so is the panel, from its first frame: ${frames.map { it.panelParent }}", frames.all { it.panelParent == 1f })
        assertTrue("the pane never dips under 0.9 of a page: $listing\n$log", listing >= 0.9f)

        val settledAt = frames.firstOrNull { !it.departing && it.ringLit }?.ms
        assertNotNull("the child cleared and the ring is on the parent within half a second\n$log", settledAt)
        // The clear is OverlayMs; the settle is that and a frame or two of beats, not 513 ms (Thor).
        assertTrue("settled at $settledAt ms, expected about ${MimirMotion.OverlayMs} ms\n$log", settledAt!! <= MimirMotion.OverlayMs + 3 * 16)
        val ringAt = frames.firstOrNull { it.ringLit }?.ms
        assertTrue("the ring is on the parent well inside the clear, not after it: $ringAt ms\n$log", ringAt != null && ringAt <= MimirMotion.OverlayMs / 2)
        assertEquals(
            "the ring is on the folder the player came out of: Movies, the last tile",
            listOf(false, false, false, false, false, false, false, true),
            ringFlags(),
        )
    }

    @Test
    fun `a press made while the child is still clearing lands at once`() {
        val controller = atRestInMovies()
        compose.runOnUiThread { assertTrue("B goes up", controller.dispatch(ControllerAction.CANCEL)) }
        Snapshot.sendApplyNotifications()
        repeat(3) { beat() }
        assertTrue("the child is still on screen at 48 ms", probe.listing.departing() && probe.listing.outgoingAlpha() in 0.2f..0.99f)

        // LEFT from Movies (the last tile) is FilesTest. It is taken now and the focus has moved before another frame is drawn.
        compose.runOnUiThread { assertTrue("a press is taken while the child clears", controller.dispatch(ControllerAction.NAV_LEFT)) }
        assertEquals(FileBrowserFocus.tileKey("$root/FilesTest"), authority.state.value.focused)
        beat()
        assertTrue("and the ring is on the folder it moved to, not held for the clear: ${ringFlags()}", ringFlags()[6])
        assertTrue("the child was not waited for: it is still clearing", probe.listing.departing())
    }

    @Test
    fun `going into a folder still dips in, with nothing departing`() {
        val controller = controller()
        mount(controller)
        press(controller, ControllerAction.NAV_RIGHT, ControllerAction.NAV_RIGHT, ControllerAction.NAV_RIGHT, ControllerAction.NAV_DOWN)
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { assertTrue("A opens it", controller.dispatch(ControllerAction.CONFIRM)) }
        Snapshot.sendApplyNotifications()

        val frames = (1..30).map { beat(); sample(it * 16) }
        assertEquals(movies, controller.currentPath)
        assertFalse("nothing departs on a descent", frames.any { it.departing })
        assertTrue("the arriving listing dips in from below whole: ${frames.map { it.listingParent }}", frames.any { it.listingParent in 0.5f..0.97f })
        assertEquals("and lands at 1", 1f, frames.last().listingParent, 0f)
    }
}
