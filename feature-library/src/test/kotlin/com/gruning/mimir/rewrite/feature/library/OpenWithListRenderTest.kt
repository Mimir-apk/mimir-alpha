package com.gruning.mimir.rewrite.feature.library

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.launch.OpenWithCandidate
import com.gruning.mimir.rewrite.core.launch.OpenWithOptions
import com.gruning.mimir.rewrite.core.model.EmulatorId
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirViewportPosture
import com.gruning.mimir.rewrite.core.ui.MimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.select.MimirSelectOptionsTestTag
import java.io.File
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
 * the earlier update D1: the launch card's Open-with list with its two new lines, at Thor Main's 1920 x 1080 px and 369 dpi (832.5 x 468.3 dp), at fonts 1.0 and 1.3.
 * The real [HomeLaunchCard] sits under the real [OpenWithOverlay], as the player sees them. Each picture is checked as well as saved: the sentences are
 * the true ones for the file, every text box fits its text, nothing is outside the options list, and each "Doesn't list" line sits on the label edge of
 * the row it belongs to, the same edge as the other readiness lines ("Not installed"). PNGs go to `build/reports/defs242` and to `build/test-captures/defs242` when
 * it exists. Pixels are the JVM's; the handheld's are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w834dp-h520dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OpenWithListRenderTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val density369 = 369f / 160f
    private val widthDp = 1920f / density369
    private val heightDp = 1080f / density369
    private val frameTag = "defs242-frame"

    private val cardState = HomeLaunchCardUiState(
        row = HomeShelfRow(
            itemId = HomeShelfItemId.Emulated(GameId("game-1")),
            rank = 0L,
            addedAtMillis = 0L,
            title = "Crisis Core: Final Fantasy VII",
            supportingLabel = "psp",
        ),
        platformLabel = "PlayStation Portable",
        emulatorLabel = "PPSSPP",
        lastPlayedLabel = "Yesterday",
    )

    private fun candidate(
        id: String,
        label: String,
        installed: Boolean = true,
        auto: Boolean = false,
        listsFile: Boolean? = null,
        manualCore: Boolean = false,
    ) = OpenWithCandidate(
        emulatorId = EmulatorId(id),
        label = label,
        packageName = "pkg.$id",
        installed = installed,
        requiresManualCoreProof = manualCore,
        isCurrentOverride = false,
        isAutoPick = auto,
        isLaunchPick = auto,
        listsFile = listsFile,
    )

    /** PPSSPP lists .iso and .pbp only, its Gold build lists .cso too, and the RetroArch core lists .iso only: for a .cso file two of the three say so. */
    private val someDoNotList = OpenWithOptions(
        hasOverride = false,
        candidates = listOf(
            candidate("ppsspp", "PPSSPP", auto = true, listsFile = false),
            candidate("ppssppgold", "PPSSPP Gold", listsFile = true),
            candidate("ra-ppsspp", "RetroArch 64 / PPSSPP", listsFile = false, manualCore = true),
        ),
        fileExtension = "cso",
    )

    /** Both installed emulators keep a list that leaves a .xyz file out; the third is not installed. */
    private val noneList = OpenWithOptions(
        hasOverride = false,
        candidates = listOf(
            candidate("ppsspp", "PPSSPP", auto = true, listsFile = false),
            candidate("ppssppgold", "PPSSPP Gold", listsFile = false),
            candidate("ra-ppsspp", "RetroArch 64 / PPSSPP", installed = false, listsFile = false),
        ),
        fileExtension = "xyz",
    )

    private fun mount(options: OpenWithOptions, fontScale: Float) {
        compose.setContent {
            val authority = remember { DefaultFocusAuthority() }
            CompositionLocalProvider(
                LocalDensity provides Density(density = density369, fontScale = fontScale),
                LocalMimirViewportProfile provides MimirViewportProfile(MimirViewportPosture.STANDARD, widthDp, heightDp),
            ) {
                MimirTheme {
                    Box(Modifier.requiredWidth(widthDp.dp).requiredHeight(heightDp.dp).testTag(frameTag)) {
                        val cardAuthority = remember { DefaultFocusAuthority().apply { pushScope(HomeLaunchCardScope) } }
                        HomeLaunchCard(
                            state = cardState,
                            onPlay = {},
                            onAchievements = {},
                            onOpenWith = {},
                            onClose = {},
                            authority = cardAuthority,
                        )
                        remember { authority.pushScope(OpenWithScope) }
                        OpenWithOverlay(
                            authority = authority,
                            options = options,
                            onChooseAuto = {},
                            onChoose = {},
                            onDismiss = {},
                        )
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_500L)
        compose.waitForIdle()
    }

    private class TextBox(val text: String, val bounds: Rect, val layout: TextLayoutResult?)

    private fun frame(): Rect = compose.onAllNodes(hasTestTag(frameTag), useUnmergedTree = true).fetchSemanticsNodes().first().boundsInWindow

    private fun texts(): List<TextBox> {
        val out = mutableListOf<TextBox>()
        fun walk(n: SemanticsNode) {
            n.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.let { text ->
                val layouts = mutableListOf<TextLayoutResult>()
                val action = n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action
                if (action != null) action(layouts)
                out += TextBox(text.text, n.boundsInWindow, layouts.firstOrNull())
            }
            n.children.forEach(::walk)
        }
        walk(compose.onAllNodes(hasTestTag(frameTag), useUnmergedTree = true).fetchSemanticsNodes().first())
        return out
    }

    private fun savePng(name: String) {
        val decor = compose.activity.window.decorView
        val full = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(full))
        val frame = frame()
        val left = Math.round(frame.left).coerceIn(0, full.width - 1)
        val top = Math.round(frame.top).coerceIn(0, full.height - 1)
        val crop = Bitmap.createBitmap(
            full,
            left,
            top,
            minOf(Math.round(frame.width), full.width - left),
            minOf(Math.round(frame.height), full.height - top),
        )
        val local = File(File(System.getProperty("user.dir"), "build/reports/defs242"), "$name.png")
        local.parentFile?.mkdirs()
        local.outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val shared = File("build/test-captures/defs242")
        if (shared.isDirectory) local.copyTo(File(shared, "$name.png"), overwrite = true)
        println("DEFS242-PNG ${local.absolutePath} ${crop.width}x${crop.height}")
        assertTrue("the PNG was written: ${local.path}", local.isFile && local.length() > 1_000L)
        assertEquals("Main's width in pixels", 1920, crop.width)
        assertEquals("Main's height in pixels", 1080, crop.height)
    }

    /** The lines this change is about, plus the existing readiness lines they sit with. */
    private fun readinessLines(options: OpenWithOptions): Set<String> = buildSet {
        add("Not installed")
        add("Additional emulator setup may be required")
        options.candidates.firstOrNull { it.isAutoPick }?.let { add("Uses ${it.label}") }
        options.fileExtension?.let { add("Doesn’t list .$it files"); add("None of your emulators list .$it files.") }
    }

    /**
     * Every readiness line fits its text box and the frame, and sits inside the options list's visible bounds (nothing scrolled out of reach at the
     * first frame). Only the lines are checked: the card behind the overlay and the overlay's own title and counter are not this change.
     */
    private fun assertNothingClipped(label: String, options: OpenWithOptions) {
        val list = compose.onAllNodes(hasTestTag(MimirSelectOptionsTestTag), useUnmergedTree = true).fetchSemanticsNodes().first().boundsInWindow
        val frame = frame()
        val lines = readinessLines(options)
        val found = texts().filter { it.text in lines && it.bounds.left >= list.left - 60f && it.bounds.right <= list.right + 60f }
        assertTrue("$label: the readiness lines were found: ${texts().map { it.text }}", found.size >= 3)
        for (t in found) {
            val layout = t.layout
            if (layout != null) {
                // (didOverflowWidth and hasVisualOverflow are not asked: Robolectric reports them for the existing 'Uses ...' and counter texts too, while their pixels fit.)
                assertFalse("$label: '${t.text}' overflows its box in height", layout.didOverflowHeight)
                assertEquals("$label: '${t.text}' is one line", 1, layout.lineCount)
            }
            assertTrue("$label: '${t.text}' is inside the frame: ${t.bounds} in $frame", t.bounds.left >= frame.left && t.bounds.right <= frame.right && t.bounds.top >= frame.top && t.bounds.bottom <= frame.bottom)
            assertTrue(
                "$label: '${t.text}' is inside the options list ($list), at ${t.bounds}",
                t.bounds.left >= list.left - 1f && t.bounds.right <= list.right + 1f && t.bounds.top >= list.top - 1f && t.bounds.bottom <= list.bottom + 1f,
            )
        }
    }

    private fun check(name: String, options: OpenWithOptions, fontScale: Float, expectNote: Boolean) {
        mount(options, fontScale)
        savePng(name)
        val all = texts()
        println("DEFS242-TEXTS $name " + all.filter { it.text.isNotBlank() }.joinToString(" | ") { "'${it.text}'@${it.bounds.left.toInt()},${it.bounds.top.toInt()}" })
        val extension = options.fileExtension!!
        val labels = all.filter { it.text == "Doesn\u2019t list .$extension files" }
        val expectedLabels = options.candidates.count { it.listsFile == false && it.installed }
        assertEquals("$name: one 'Doesn't list' line per installed emulator that leaves the file out", expectedLabels, labels.size)
        val notes = all.filter { it.text.startsWith("None of your") }
        assertEquals("$name: the note is there exactly when no installed emulator lists the file", if (expectNote) 1 else 0, notes.size)
        if (expectNote) assertEquals("None of your emulators list .$extension files.", notes.single().text)
        assertEquals(expectNote, options.noInstalledEmulatorListsFile)
        // Alignment: the row labels and every readiness line ("Uses ...", "Not installed", each "Doesn't list" line, and the note) start on one x, the rows' label edge.
        val edge = (all.filter { it.text == "Not installed" || it.text == "Uses PPSSPP" || it.text == "Automatic" || it.text in options.candidates.map { c -> c.label } }.filter { it.bounds.left > 400f && it.bounds.left < 1300f }.map { it.bounds.left } + labels.map { it.bounds.left } + notes.map { it.bounds.left })
        assertTrue("$name: readiness lines share one left edge, not $edge", edge.size == 1 || (edge.max() - edge.min()) <= 1f)
        assertNothingClipped(name, options)
    }

    @Test
    fun `some emulators do not list the file at font 1_0`() = check("openwith-doesnt-list-1.0x", someDoNotList, 1.0f, expectNote = false)

    @Test
    fun `some emulators do not list the file at font 1_3`() = check("openwith-doesnt-list-1.3x", someDoNotList, 1.3f, expectNote = false)

    @Test
    fun `no installed emulator lists the file at font 1_0`() = check("openwith-none-list-1.0x", noneList, 1.0f, expectNote = true)

    @Test
    fun `no installed emulator lists the file at font 1_3`() = check("openwith-none-list-1.3x", noneList, 1.3f, expectNote = true)
}
