package com.gruning.mimir.rewrite

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.ui.MimirButtonMark
import com.gruning.mimir.rewrite.core.ui.MimirSceneGround
import com.gruning.mimir.rewrite.core.ui.MimirSceneGroundBlur
import com.gruning.mimir.rewrite.core.ui.MimirSceneGroundDim
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import com.gruning.mimir.rewrite.feature.settings.BackgroundAddKey
import com.gruning.mimir.rewrite.feature.settings.BackgroundAtmosphereKey
import com.gruning.mimir.rewrite.feature.settings.BackgroundPickerCommand
import com.gruning.mimir.rewrite.feature.settings.BackgroundPickerImage
import com.gruning.mimir.rewrite.feature.settings.BackgroundPickerOverlay
import com.gruning.mimir.rewrite.feature.settings.BackgroundPickerScope
import com.gruning.mimir.rewrite.feature.settings.BackgroundPickerState
import com.gruning.mimir.rewrite.feature.settings.MimirButtonAddKey
import com.gruning.mimir.rewrite.feature.settings.MimirButtonPickerCommand
import com.gruning.mimir.rewrite.feature.settings.MimirButtonPickerMark
import com.gruning.mimir.rewrite.feature.settings.MimirButtonPickerOverlay
import com.gruning.mimir.rewrite.feature.settings.MimirButtonPickerScope
import com.gruning.mimir.rewrite.feature.settings.MimirButtonPickerState
import com.gruning.mimir.rewrite.feature.settings.MimirButtonRuneKey
import com.gruning.mimir.rewrite.feature.settings.backgroundPickerInitialState
import com.gruning.mimir.rewrite.feature.settings.backgroundPickerOnFocus
import com.gruning.mimir.rewrite.feature.settings.backgroundPickerTransition
import com.gruning.mimir.rewrite.feature.settings.backgroundTileKey
import com.gruning.mimir.rewrite.feature.settings.landPickerFocus
import com.gruning.mimir.rewrite.feature.settings.mimirButtonPickerInitialState
import com.gruning.mimir.rewrite.feature.settings.mimirButtonPickerOnFocus
import com.gruning.mimir.rewrite.feature.settings.mimirButtonPickerTransition
import com.gruning.mimir.rewrite.feature.settings.mimirButtonTileKey
import com.gruning.mimir.rewrite.feature.settings.previewGround
import com.gruning.mimir.rewrite.feature.settings.previewMark
import com.gruning.mimir.rewrite.feature.settings.withImportCancelled
import com.gruning.mimir.rewrite.feature.settings.withImportLanded
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Where focus lands around an appearance picker's image import (gatekeeper S1, ), on the REAL [DefaultFocusAuthority] and the real overlays, in the shape production has:
 *
 *  - the host pushes the picker's scope, then the picker composes and lands focus on the tile in use;
 *  - + Add image opens Mimir's image browser, which covers the picker (only the top overlay composes) and pushes its own
 *    scope; the picker remounts when the browser closes;
 *  - an import that finishes lands its result AFTER that -- the host writes the new state and starts the landing in the
 *    same UI-thread task, so the new tile composes, and registers its bounds in `onGloballyPositioned`, a layout AFTER
 *    the request. `FocusAuthority.requestFocus` drops a key that is not registered yet, which is what left focus (and so
 *    A) on the old tile.
 *
 * The stage previews from focus, so "the stage sits on the new tile" is asserted as the state's preview: what the host's
 * `onFocus` wrote once focus landed. The two pickers are covered because each has its own template wiring and its own
 * `initialFocusKey`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppearancePickerFocusLandingTest {

    @get:Rule
    val compose = createComposeRule()

    private val browserScope = FocusScope("test/imageBrowser")

    private fun frames(count: Int = 12) {
        compose.waitForIdle()
        repeat(count) { compose.mainClock.advanceTimeByFrame() }
        compose.waitForIdle()
    }

    // ── the Background picker ──────────────────────────────────────────────────────────────────────────────────

    private fun image(id: String) = BackgroundPickerImage(id, "Your image $id", "/none/$id.jpg", 1920, 1080, p90 = 0.2f)

    private fun ground(image: BackgroundPickerImage) = MimirSceneGround(
        id = image.id,
        path = image.path!!,
        width = image.width,
        height = image.height,
        p90 = image.p90,
        dim = MimirSceneGroundDim.STRONG,
        blur = MimirSceneGroundBlur.OFF,
    )

    private inner class BackgroundHost(val authority: DefaultFocusAuthority, initial: BackgroundPickerState) {
        var state by mutableStateOf(initial)
        var browserOpen by mutableStateOf(false)
        var scope: CoroutineScope? = null

        @Composable
        fun Content() {
            scope = rememberCoroutineScope()
            MimirTheme {
                if (!browserOpen) {
                    BackgroundPickerOverlay(
                        authority = authority,
                        state = state,
                        dualScreen = false,
                        // The host's own wiring: the stage follows focus, every transition is written back.
                        onFocus = { key -> state = backgroundPickerOnFocus(state, key) },
                        onTransition = { transition -> state = transition.state },
                        presenceManagedExternally = true,
                    )
                }
            }
        }

        val focused get() = authority.state.value.focused

        /** A on the focused key, exactly as `dispatchBackgroundPicker` routes it. */
        fun pressA() = backgroundPickerTransition(state, ControllerAction.CONFIRM, authority.state.value.focused)
            .also { state = it.state }

        fun openBrowser() {
            browserOpen = true
            authority.pushScope(browserScope)
        }

        fun closeBrowser() {
            authority.popScope()
            browserOpen = false
        }
    }

    private fun openBackgroundPicker(current: MimirSceneGround?, saved: List<BackgroundPickerImage>): BackgroundHost {
        val authority = DefaultFocusAuthority()
        val host = BackgroundHost(authority, backgroundPickerInitialState(MimirThemeId.DEFAULT, current, saved))
        // openBackgroundPicker: the scope is pushed before the picker composes.
        authority.pushScope(BackgroundPickerScope)
        compose.setContent { host.Content() }
        frames()
        return host
    }

    @Test
    fun `the Background picker opens on the tile in use`() {
        val a = image("aaaa")
        val host = openBackgroundPicker(ground(a), listOf(a, image("bbbb")))
        assertEquals(backgroundTileKey("aaaa"), host.focused)
    }

    @Test
    fun `cancelling the file picker returns focus to + Upload, the tile it was opened from`() {
        val a = image("aaaa")
        val b = image("bbbb")
        val host = openBackgroundPicker(ground(a), listOf(a, b))
        // The pad passes tile B on its way to + Add image: the stage previews B while the ring sits on Add.
        compose.runOnIdle { host.authority.moveFocus(ControllerAction.NAV_RIGHT) }
        frames(3)
        assertEquals(backgroundTileKey("bbbb"), host.focused)
        assertEquals("bbbb", host.state.previewId)
        compose.runOnIdle { host.authority.moveFocus(ControllerAction.NAV_RIGHT) }
        frames(3)

        compose.runOnIdle {
            val transition = host.pressA()
            assertEquals(BackgroundPickerCommand.Import, transition.command)
            host.openBrowser()
        }
        frames()
        compose.runOnIdle { host.closeBrowser() } // cancelled: nothing to import
        frames()

        assertEquals(
            "focus is back on + Upload, where the file picker was opened from, not on the tile in use or the tile the pad passed on the way",
            BackgroundAddKey,
            host.focused,
        )
        assertEquals("the stage keeps showing the tile in use", "aaaa", host.state.previewId)
        assertFalse("the return is spent once focus has landed", host.state.importOpen)
    }

    @Test
    fun `a system picker dismissed with nothing chosen leaves no return to + Upload for a later remount`() {
        val a = image("aaaa")
        val host = openBackgroundPicker(ground(a), listOf(a))
        compose.runOnIdle { host.authority.moveFocus(ControllerAction.NAV_RIGHT) }
        frames(3)
        assertEquals(BackgroundAddKey, host.focused)
        compose.runOnIdle { assertEquals(BackgroundPickerCommand.Import, host.pressA().command) }
        // The system picker covers the screen without remounting the picker, and nothing is chosen: the coordinator's appearanceImportCancelled.
        assertTrue("the player left from + Upload", host.state.importOpen)
        compose.runOnIdle { host.state = host.state.withImportCancelled() }
        // Some later, unrelated cover and return remounts the picker: it lands on the tile in use, not on + Upload.
        compose.runOnIdle { host.openBrowser() }
        frames()
        compose.runOnIdle { host.closeBrowser() }
        frames()
        assertEquals(backgroundTileKey("aaaa"), host.focused)
    }

    @Test
    fun `the coordinator tells the open picker when the system picker is dismissed with nothing chosen`() {
        fun source(name: String): String = listOf(File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"), File("src/main/kotlin/com/gruning/mimir/rewrite/$name"))
            .firstOrNull(File::isFile)?.readText() ?: error("$name not found from ${File(".").absolutePath}")
        val launcher = source("MimirAppCoordinator.kt")
            .substringAfter("val appearanceImportLauncher = rememberLauncherForActivityResult(")
            .substringBefore("fun startAppearanceImageImport(")
        assertTrue("a dismissed system picker reaches the settings coordinator", "else if (kind != null) settings.appearanceImportCancelled(kind)" in launcher)
        val cancelled = source("MimirSettingsCoordinator.kt").substringAfter("val appearanceImportCancelled:").substringBefore("val appearanceImportLanded:")
        assertTrue("it ends the return on the Background picker", "state.backgroundPicker = it.withImportCancelled()" in cancelled)
        assertTrue("and on the Mimir button picker", "state.mimirButtonPicker = it.withImportCancelled()" in cancelled)
    }

    @Test
    fun `the Background picker with Atmosphere in use also returns to + Upload, not to Atmosphere`() {
        val host = openBackgroundPicker(null, listOf(image("aaaa")))
        assertEquals("Atmosphere is the tile in use", BackgroundAtmosphereKey, host.focused)
        compose.runOnIdle { host.authority.moveFocus(ControllerAction.NAV_RIGHT) }
        frames(3)
        compose.runOnIdle { host.authority.moveFocus(ControllerAction.NAV_RIGHT) }
        frames(3)
        compose.runOnIdle {
            assertEquals(BackgroundPickerCommand.Import, host.pressA().command)
            host.openBrowser()
        }
        frames()
        compose.runOnIdle { host.closeBrowser() }
        frames()
        assertEquals(BackgroundAddKey, host.focused)
        assertEquals("the stage shows Atmosphere, the tile in use", null, host.state.previewId)
    }

    @Test
    fun `an import lands focus and the stage on the new tile though the tile registers after the state write`() {
        val a = image("aaaa")
        val b = image("bbbb")
        val fresh = image("cccc")
        val host = openBackgroundPicker(ground(a), listOf(a, b))
        compose.runOnIdle { host.authority.moveFocus(ControllerAction.NAV_RIGHT) }
        frames(3)
        compose.runOnIdle { host.authority.moveFocus(ControllerAction.NAV_RIGHT) }
        frames(3)
        compose.runOnIdle {
            assertEquals(BackgroundPickerCommand.Import, host.pressA().command)
            host.openBrowser()
        }
        frames()
        // The browser closes itself, THEN the import runs: the picker has already remounted and landed on + Upload.
        compose.runOnIdle { host.closeBrowser() }
        frames()
        assertEquals(BackgroundAddKey, host.focused)

        // The import finishes: new state and the landing start in the same UI-thread task, like appearanceImportLanded.
        compose.runOnIdle {
            host.state = host.state.withImportLanded(listOf(a, b, fresh), importedId = "cccc", note = "Uploaded · Saved")
            host.scope!!.launch { host.authority.landPickerFocus(backgroundTileKey("cccc")) }
        }
        frames()

        assertEquals("focus is on the new tile, so A commits the new image", backgroundTileKey("cccc"), host.focused)
        assertEquals("the stage previews the new image", "cccc", host.state.previewId)
        assertEquals("and the ground it would commit is the new one", "cccc", host.state.previewGround()?.id)
        assertEquals("the old tile is still the committed one until A", "aaaa", host.state.current?.id)
    }

    @Test
    fun `focus lands on a tile that only registers frames after the first attempts were made`() {
        // The property the retrying lander exists for, made deterministic: the landing starts BEFORE the tile exists, so
        // its first attempts are dropped (`requestFocus` ignores a key that has not registered), exactly what a request made
        // the frame a tile is added is -- only here the tile arrives several frames late, not one layout late. A
        // one-attempt lander leaves focus on the old tile; the shared one keeps trying until the tile registers.
        val a = image("aaaa")
        val b = image("bbbb")
        val fresh = image("cccc")
        val host = openBackgroundPicker(ground(a), listOf(a, b))
        // The clock is paused so the frames between the landing's start and the tile's arrival are counted, not skipped
        // by an idle wait that would run the landing to exhaustion first.
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { host.scope!!.launch { host.authority.landPickerFocus(backgroundTileKey("cccc")) } }
        repeat(2) { compose.mainClock.advanceTimeByFrame() }
        assertEquals("nothing to land on yet: focus stays on the tile in use", backgroundTileKey("aaaa"), host.focused)

        compose.runOnUiThread {
            host.state = host.state.withImportLanded(listOf(a, b, fresh), importedId = "cccc", note = null)
            Snapshot.sendApplyNotifications()
        }
        repeat(5) { compose.mainClock.advanceTimeByFrame() }

        assertEquals(backgroundTileKey("cccc"), host.focused)
        assertEquals("cccc", host.state.previewId)
    }

    @Test
    fun `an import that finishes before the picker's own landing settles agrees on the new tile`() {
        val a = image("aaaa")
        val fresh = image("cccc")
        // The picker mounts with the import already in the state (the browser closed and the import beat the first frame).
        val opened = backgroundPickerInitialState(MimirThemeId.DEFAULT, ground(a), listOf(a))
            .withImportLanded(listOf(a, fresh), importedId = "cccc", note = null)
        val authority = DefaultFocusAuthority()
        val host = BackgroundHost(authority, opened)
        authority.pushScope(BackgroundPickerScope)
        compose.setContent { host.Content() }
        frames()
        assertEquals(backgroundTileKey("cccc"), host.focused)
    }

    @Test
    fun `removing the last saved image lands focus on Atmosphere`() {
        val a = image("aaaa")
        val host = openBackgroundPicker(null, listOf(a))
        compose.runOnIdle {
            host.state = host.state.copy(saved = emptyList(), previewId = null)
            host.scope!!.launch { host.authority.landPickerFocus(BackgroundAtmosphereKey) }
        }
        frames()
        assertEquals(BackgroundAtmosphereKey, host.focused)
    }

    // ── the Mimir-button picker ────────────────────────────────────────────────────────────────────────────────

    private fun mark(id: String) = MimirButtonPickerMark(id, "Your mark $id", "/none/$id.png")

    private inner class ButtonHost(val authority: DefaultFocusAuthority, initial: MimirButtonPickerState) {
        var state by mutableStateOf(initial)
        var browserOpen by mutableStateOf(false)
        var scope: CoroutineScope? = null

        @Composable
        fun Content() {
            scope = rememberCoroutineScope()
            MimirTheme {
                if (!browserOpen) {
                    MimirButtonPickerOverlay(
                        authority = authority,
                        state = state,
                        onFocus = { key -> state = mimirButtonPickerOnFocus(state, key) },
                        onTransition = { transition -> state = transition.state },
                        presenceManagedExternally = true,
                        stage = { _ -> },
                    )
                }
            }
        }

        val focused get() = authority.state.value.focused

        fun pressA() = mimirButtonPickerTransition(state, ControllerAction.CONFIRM, authority.state.value.focused)
            .also { state = it.state }

        fun openBrowser() {
            browserOpen = true
            authority.pushScope(browserScope)
        }

        fun closeBrowser() {
            authority.popScope()
            browserOpen = false
        }
    }

    private fun openButtonPicker(current: MimirButtonMark?, saved: List<MimirButtonPickerMark>): ButtonHost {
        val authority = DefaultFocusAuthority()
        val host = ButtonHost(authority, mimirButtonPickerInitialState(current, saved))
        authority.pushScope(MimirButtonPickerScope)
        compose.setContent { host.Content() }
        frames()
        return host
    }

    @Test
    fun `the Mimir-button picker opens on the tile in use and cancelling the file picker returns to + Upload`() {
        val a = mark("aaaa")
        val b = mark("bbbb")
        val host = openButtonPicker(MimirButtonMark(a.id, a.path!!), listOf(a, b))
        assertEquals(mimirButtonTileKey("aaaa"), host.focused)

        compose.runOnIdle { host.authority.moveFocus(ControllerAction.NAV_RIGHT) }
        frames(3)
        assertEquals(mimirButtonTileKey("bbbb"), host.focused)
        assertEquals("bbbb", host.state.previewId)
        compose.runOnIdle { host.authority.moveFocus(ControllerAction.NAV_RIGHT) }
        frames(3)

        compose.runOnIdle {
            assertEquals(MimirButtonPickerCommand.Import, host.pressA().command)
            host.openBrowser()
        }
        frames()
        compose.runOnIdle { host.closeBrowser() }
        frames()

        assertEquals("focus is back on + Upload, where the file picker was opened from", MimirButtonAddKey, host.focused)
        assertEquals("the stage keeps showing the tile in use", "aaaa", host.state.previewId)
        assertFalse("the return is spent once focus has landed", host.state.importOpen)
    }

    @Test
    fun `the Mimir-button picker with no mark in use also returns to + Upload, not to the rune`() {
        val host = openButtonPicker(null, listOf(mark("aaaa")))
        assertEquals("the rune is the tile in use", MimirButtonRuneKey, host.focused)
        compose.runOnIdle { host.authority.moveFocus(ControllerAction.NAV_RIGHT) }
        frames(3)
        compose.runOnIdle { host.authority.moveFocus(ControllerAction.NAV_RIGHT) }
        frames(3)
        compose.runOnIdle {
            assertEquals(MimirButtonPickerCommand.Import, host.pressA().command)
            host.openBrowser()
        }
        frames()
        compose.runOnIdle { host.closeBrowser() }
        frames()
        assertEquals(MimirButtonAddKey, host.focused)
        assertEquals("the stage shows the rune, the tile in use", null, host.state.previewId)
    }

    @Test
    fun `focus lands on a mark tile that only registers frames after the first attempts were made`() {
        val a = mark("aaaa")
        val fresh = mark("cccc")
        val host = openButtonPicker(null, listOf(a))
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { host.scope!!.launch { host.authority.landPickerFocus(mimirButtonTileKey("cccc")) } }
        repeat(2) { compose.mainClock.advanceTimeByFrame() }
        assertEquals(MimirButtonRuneKey, host.focused)

        compose.runOnUiThread {
            host.state = host.state.withImportLanded(listOf(a, fresh), importedId = "cccc", note = null)
            Snapshot.sendApplyNotifications()
        }
        repeat(5) { compose.mainClock.advanceTimeByFrame() }

        assertEquals(mimirButtonTileKey("cccc"), host.focused)
        assertEquals("cccc", host.state.previewId)
    }

    @Test
    fun `a mark import lands focus and the stage on the new tile though the tile registers after the state write`() {
        val a = mark("aaaa")
        val fresh = mark("cccc")
        val host = openButtonPicker(null, listOf(a))
        assertEquals(MimirButtonRuneKey, host.focused)
        compose.runOnIdle { host.authority.moveFocus(ControllerAction.NAV_RIGHT) }
        frames(3)
        compose.runOnIdle { host.authority.moveFocus(ControllerAction.NAV_RIGHT) }
        frames(3)
        compose.runOnIdle {
            assertEquals(MimirButtonPickerCommand.Import, host.pressA().command)
            host.openBrowser()
        }
        frames()
        compose.runOnIdle { host.closeBrowser() }
        frames()
        assertEquals("the picker came back on + Upload", MimirButtonAddKey, host.focused)

        compose.runOnIdle {
            host.state = host.state.withImportLanded(listOf(a, fresh), importedId = "cccc", note = "Uploaded · Saved")
            host.scope!!.launch { host.authority.landPickerFocus(mimirButtonTileKey("cccc")) }
        }
        frames()

        assertEquals(mimirButtonTileKey("cccc"), host.focused)
        assertEquals("cccc", host.state.previewId)
        assertNotNull("the stage would preview the new mark", host.state.previewMark())
        assertEquals("cccc", host.state.previewMark()?.id)
        assertEquals("the rune is still committed until A", null, host.state.current)
    }

    // ── the host's landing is the template's ───────────────────────────────────────────────────────────────────

    @Test
    fun `the coordinator lands picker focus with the shared retrying lander and no attempt of its own`() {
        val file = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/MimirSettingsCoordinator.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/MimirSettingsCoordinator.kt"),
        ).firstOrNull(File::isFile) ?: error("MimirSettingsCoordinator.kt not found from ${File(".").absolutePath}")
        val code = file.readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")
        val start = code.indexOf("fun landPickerFocus(key: FocusKey) {")
        assertTrue("the coordinator's landPickerFocus is gone -- move this pin with it", start >= 0)
        val body = code.substring(start, code.indexOf("val closeBackgroundPicker", start)).replace(Regex("""\s+"""), " ")
        assertTrue(
            "one lander for both pickers: the template's, so a tile that registers a layout late still gets focus",
            "scope.launch { authority.landPickerFocus(key) }" in body,
        )
        assertFalse("a one-frame attempt is what lost the import's new tile", "requestFocus(" in body)
        assertFalse("a one-frame attempt is what lost the import's new tile", "withFrameNanos" in body)
    }
}
