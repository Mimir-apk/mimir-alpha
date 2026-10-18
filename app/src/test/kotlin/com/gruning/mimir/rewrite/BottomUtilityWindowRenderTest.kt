package com.gruning.mimir.rewrite

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RenderNode
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.down
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayRole
import com.gruning.mimir.rewrite.core.companionnative.companionPanelWindowParams
import com.gruning.mimir.rewrite.core.model.CompanionAppEntry
import com.gruning.mimir.rewrite.core.tweaker.TweakerUtilityAvailability
import com.gruning.mimir.rewrite.core.ui.AmbientMotionPhase
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import com.gruning.mimir.rewrite.core.ui.MimirTransitionInputGate
import com.gruning.mimir.rewrite.feature.companion.CompanionRecentsDrawer
import java.io.File
import java.util.UUID
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Bottom's overlay windows, rendered at the Bottom's size (537.7 x 468.3 dp at 369 dpi, 1240 x 1080 px) over a
 * stand-in for a dual-screen emulator's own window: Utility, the Tweaker (the same window's other face), and the Tabs drawer. The
 * window's real content ([BottomUtilityWindowContent]: the theme, the overlay clocks, the touch gate, the opaque backdrop) around the
 * real panel ([MimirBrightnessOverlay], as the Bottom's host draws it over a game: no controller hints, no BackHandler, no pad held,
 * no Files row), on a model with no vendor gateway behind it.
 *
 * What is proven here: the window is lent the whole display, so the scene is the Bottom's size read off the display and not a size
 * the test chose; the backdrop is opaque, in that none of the emulator's colour reaches the pixels where its caption is; the panel's
 * Close chip lies wholly inside the Bottom; and a tap on Close closes the model, the window asking to be removed only after its exit
 * fade. The pictures are saved to `app/build/bottomfix/` and copied to `build/test-captures/bottomfix-*.png` when that exists. Pixel values are
 * the JVM's (it draws the glass flat, where a handheld frosts it); the pixels on a handheld, and what the emulator's real window does
 * beneath these, are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
// The Bottom's display, to the dp the qualifier syntax allows: 538 x 468 dp at 369 dpi is 1241 x 1079 px. The scene is sized from the display.
@Config(sdk = [30], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BottomUtilityWindowRenderTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val density369 = 369f / 160f

    /** The Bottom display's size in px, as the display reports it: what a MATCH_PARENT window with no insets to fit is. */
    private val displayWidthPx get() = compose.activity.resources.displayMetrics.widthPixels
    private val displayHeightPx get() = compose.activity.resources.displayMetrics.heightPixels

    /** The emulator's window, as a flat saturated field no Mimir surface wears, so any of it showing through is plain. */
    private val emulatorField = Color(0xFFD81B8A)
    private val emulatorFieldEnd = Color(0xFF8A1B6A)
    private val captionText = "EMULATOR BOTTOM SCREEN"

    private val hidden = mutableListOf<Long>()

    @Before
    fun gateClock() {
        MimirTransitionInputGate.reset()
        MimirTransitionInputGate.clock = { compose.mainClock.currentTime }
    }

    @After
    fun resetGate() = MimirTransitionInputGate.reset()

    /** A Utility model with the panel the Bottom's host draws and none of the vendor machinery behind it. */
    private class FakeUtility(initial: UtilitySurface?) : BottomUtilityModel {
        var shown by mutableStateOf(initial)
        override val shownSurface: UtilitySurface? get() = shown

        override fun showOnBottom(surface: UtilitySurface): Boolean {
            shown = surface
            return true
        }

        override fun dismissFromBottom() {
            shown = null
        }

        @Composable
        override fun Panel(presentationToken: UUID) {
            MimirBrightnessOverlay(
                state = bottomUtilityRenderPanelState(),
                focusedControl = null,
                onSetFraction = { _, _ -> },
                onSetThemeAwareJoystickLight = {},
                onRequestWriteAccess = {},
                onRequestPrivilegedAccess = {},
                onOpenTweaker = {},
                onOpenFiles = {},
                // R2: these windows are for use over a game, and a game running withdraws the row (utilityFilesRowOffered).
                filesRouteAvailable = utilityFilesRowOffered(hostsInAppSurfaces = true, gameRunning = true),
                allFilesAccessGranted = true,
                tweakerRow = DefaultTweakerUtilityRow.copy(availability = TweakerUtilityAvailability.READY),
                surface = shown ?: UtilitySurface.UTILITY,
                onBack = { dismissFromBottom() },
                onClose = { dismissFromBottom() },
                backActionLabel = "Close",
                controllerHintsAvailable = false,
                enableBackHandler = false,
                holdPad = false,
            )
        }
    }

    private val utility = FakeUtility(UtilitySurface.UTILITY)

    /** What an emulator's own window leaves on the Bottom: a field and a stand-in screen with a caption, drawn beneath the window. */
    @Composable
    private fun EmulatorBeneath() {
        Box(
            Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(emulatorField, emulatorFieldEnd))),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(300.dp, 220.dp).background(emulatorField))
            BasicText(captionText, style = TextStyle(color = Color.White, fontSize = 18.sp))
        }
    }

    private fun sceneSize(): Pair<Dp, Dp> {
        val metrics = compose.activity.resources.displayMetrics
        return Dp(metrics.widthPixels / metrics.density) to Dp(metrics.heightPixels / metrics.density)
    }

    private fun mountScene(content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        val (width, height) = sceneSize()
        compose.setContent {
            Box(Modifier.requiredSize(width, height).testTag("scene")) {
                EmulatorBeneath()
                content()
            }
        }
        advance(2_000L)
    }

    private fun mount(model: FakeUtility = utility) = mountScene {
        BottomUtilityWindowContent(
            utility = model,
            presentationToken = UUID.randomUUID(),
            themeId = MimirThemeId.fromStorageKey(null),
            motionPhase = AmbientMotionPhase.IDLE,
            keyboardHaptic = null,
            onFullyHidden = { hidden += compose.mainClock.currentTime },
        )
    }

    private fun advance(ms: Long) {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(ms)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    private fun sceneBitmap(): Bitmap {
        val decor = compose.activity.window.decorView
        val full = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        // The JVM's software canvas cannot draw a recorded Compose layer and throws. The Tweaker face has one: its profile row's state
        // swap (`TweakerRowStateSwap`) records its words into a layer. Skipping that draw leaves those few words out of the Tweaker's
        // picture and out of nothing else; a handheld draws them.
        decor.draw(object : Canvas(full) {
            override fun drawRenderNode(renderNode: RenderNode) = Unit
        })
        val bounds = compose.onNodeWithTag("scene").fetchSemanticsNode().boundsInWindow
        val left = bounds.left.toInt().coerceAtLeast(0)
        val top = bounds.top.toInt().coerceAtLeast(0)
        return Bitmap.createBitmap(
            full,
            left,
            top,
            minOf(bounds.width.toInt(), full.width - left),
            minOf(bounds.height.toInt(), full.height - top),
        )
    }

    private fun writePng(fileName: String) {
        val scene = sceneBitmap()
        val dir = File("build/bottomfix").apply { mkdirs() }
        val out = File(dir, fileName)
        out.outputStream().use { scene.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val shared = File("build/test-captures")
        if (shared.isDirectory) out.copyTo(File(shared, "bottomfix-$fileName"), overwrite = true)
        println("BOTTOMFIX wrote ${out.absolutePath} (${out.length()} bytes, ${scene.width}x${scene.height} px)")
        assertTrue("the PNG was written: ${out.path}", out.isFile && out.length() > 1_000L)
    }

    private fun pixel(x: Int, y: Int): Int = sceneBitmap().asImageBitmap().toPixelMap()[x, y].toArgb()

    private fun closeNode() = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().filter { node ->
        val words = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        words.any { it.trim().equals("Close", ignoreCase = true) }
    }

    private fun tapAt(point: Offset) {
        compose.onRoot().performTouchInput {
            down(point)
            up()
        }
        advance(50L)
    }

    /** A point inside the stand-in screen's solid field, clear of its caption: the emulator's colour exactly while nothing covers it. */
    private fun fieldProbe(): Pair<Int, Int> =
        (displayWidthPx / 2 - (140f * density369).toInt()) to (displayHeightPx / 2 + (100f * density369).toInt())

    /** Whether [argb] is close to either stop of the emulator's field: the colour that must never show through. */
    private fun isEmulatorColour(argb: Int): Boolean = listOf(emulatorField, emulatorFieldEnd).any { stop ->
        val s = stop.toArgb()
        abs(((argb shr 16) and 0xFF) - ((s shr 16) and 0xFF)) +
            abs(((argb shr 8) and 0xFF) - ((s shr 8) and 0xFF)) +
            abs((argb and 0xFF) - (s and 0xFF)) < 60
    }

    /**
     * A grid of pixels across the caption's rectangle, in the scene bitmap's own coordinates: where "EMULATOR BOTTOM SCREEN" is drawn
     * beneath the window, and so where it would show through a translucent plate.
     */
    private fun captionGrid(): List<Pair<Int, Int>> {
        val scene = compose.onNodeWithTag("scene").fetchSemanticsNode().boundsInWindow
        val caption = compose.onNodeWithText(captionText).fetchSemanticsNode().boundsInWindow
        val xs = (0 until 9).map { (caption.left - scene.left + (caption.width * (it + 0.5f) / 9f)).toInt() }
        val ys = (0 until 5).map { (caption.top - scene.top + (caption.height * (it + 0.5f) / 5f)).toInt() }
        return xs.flatMap { x -> ys.map { y -> x to y } }
    }

    private fun assertBackdropOpaque(what: String) {
        val bitmap = sceneBitmap().asImageBitmap().toPixelMap()
        val grid = captionGrid()
        assertTrue("$what: the grid lies inside the scene", grid.all { (x, y) -> x in 0 until bitmap.width && y in 0 until bitmap.height })
        val showing = grid.filter { (x, y) -> isEmulatorColour(bitmap[x, y].toArgb()) }
        println("BOTTOMFIX $what caption grid ${grid.size} pixels, ${showing.size} in the emulator's colour; sample ${Integer.toHexString(bitmap[grid[0].first, grid[0].second].toArgb())}")
        assertTrue(
            "$what: the emulator's colour shows through the plate where its caption is, at $showing",
            showing.isEmpty(),
        )
        val corners = listOf(20 to 20, bitmap.width - 21 to 20, 20 to bitmap.height - 21, bitmap.width - 21 to bitmap.height - 21)
        corners.forEach { (x, y) ->
            assertFalse("$what: the emulator's field shows at the corner $x,$y", isEmulatorColour(bitmap[x, y].toArgb()))
        }
    }

    // -- the window the overlay controller lends ---------------------------------------------------------------------------------

    @Test
    fun `the Bottom's windows are lent the whole display - the size is the display's, read from the params`() {
        val params = companionPanelWindowParams(focusable = false, title = "Mimir companion tabs", role = CompanionOverlayRole.SECONDARY_COMPANION)

        assertEquals("MATCH_PARENT wide", WindowManager.LayoutParams.MATCH_PARENT, params.width)
        assertEquals("MATCH_PARENT tall", WindowManager.LayoutParams.MATCH_PARENT, params.height)
        assertEquals("no system-bar inset to fit: the window was 445 dp tall on a 468.3 dp display", 0, params.fitInsetsTypes)
        assertTrue("laid out in the screen", params.flags and WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN != 0)
        assertTrue("touch-only: never focusable", params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        val primary = companionPanelWindowParams(focusable = true, title = "Mimir global controls", role = CompanionOverlayRole.PRIMARY_SINGLE)
        assertEquals("Main's panel keeps the platform default", WindowManager.LayoutParams().fitInsetsTypes, primary.fitInsetsTypes)

        // The scene below is that window: the display, read from the display, which is the Bottom's size.
        assertEquals("the Bottom's width in dp", 537.7f, displayWidthPx / density369, 0.6f)
        assertEquals("the Bottom's height in dp", 468.3f, displayHeightPx / density369, 0.6f)
    }

    // -- Utility -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `the window covers what is under it, opaquely, and uncovers it when the model closes`() {
        mount()

        val scene = compose.onNodeWithTag("scene").fetchSemanticsNode().boundsInRoot
        assertEquals("the scene is the display's width", displayWidthPx.toFloat(), scene.width, 1.5f)
        assertEquals("and its height", displayHeightPx.toFloat(), scene.height, 1.5f)
        writePng("bottom-utility-window.png")

        val (probeX, probeY) = fieldProbe()
        assertNotEquals("the emulator's field shows through the window", emulatorField.toArgb(), pixel(probeX, probeY))
        assertBackdropOpaque("Utility")
        assertEquals("no Files row over a game", 0, compose.onAllNodesWithText("Files").fetchSemanticsNodes().size)

        Snapshot.withMutableSnapshot { utility.shown = null }
        advance(MimirMotion.OverlayMs * 4L)

        assertEquals("the window asked to be removed once, after the model closed", 1, hidden.size)
        assertEquals("and the emulator's field is back where the panel was", emulatorField.toArgb(), pixel(probeX, probeY))
        writePng("bottom-utility-window-closed.png")
    }

    @Test
    fun `the Close chip lies wholly inside the Bottom, and nothing that takes a tap spills past its edge`() {
        mount()

        val scene = compose.onNodeWithTag("scene").fetchSemanticsNode().boundsInRoot
        val close = closeNode()
        assertEquals("one live Close", 1, close.size)
        val bounds = close.single().boundsInRoot
        println("BOTTOMFIX close ${bounds.left / density369},${bounds.top / density369} ${bounds.width / density369}x${bounds.height / density369} dp in ${scene.width / density369}x${scene.height / density369}")
        assertInside("Close", bounds, scene)

        // Every control that is on screen (the rail scrolls, so what is off screen is not a clip) lies inside the Bottom.
        val onScreen = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().filter { node ->
            val b = node.boundsInRoot
            b.right > scene.left && b.left < scene.right && b.bottom > scene.top && b.top < scene.bottom
        }
        assertTrue("there are controls on the Bottom", onScreen.isNotEmpty())
        onScreen.forEach { node ->
            val b = node.boundsInRoot
            // A control the rail has partly scrolled out of view is the rail's clip, not the window's; none may start outside it.
            assertTrue(
                "a control starts outside the Bottom: ${b.left / density369},${b.top / density369} ${b.width / density369}x${b.height / density369} dp",
                b.left >= scene.left - 1f && b.top >= scene.top - 1f && b.right <= scene.right + 1f,
            )
        }
    }

    @Test
    fun `a tap on Close closes the model, and the window asks to go only after its exit fade`() {
        mount()
        assertTrue("nothing has asked to go while the panel is up", hidden.isEmpty())

        tapAt(closeNode().single().boundsInRoot.center)

        assertNull("Close dismissed the model", utility.shownSurface)
        assertTrue("the exit fade has not finished yet", hidden.isEmpty())
        advance(MimirMotion.OverlayMs * 4L)
        assertEquals("the window asked to be removed once, after the fade", 1, hidden.size)
        val (probeX, probeY) = fieldProbe()
        assertEquals("and the emulator's field is back where the panel was", emulatorField.toArgb(), pixel(probeX, probeY))
    }

    @Test
    fun `a Utility press while the window is up keeps one window and the panel stays`() {
        mount()

        assertTrue(utility.showOnBottom(UtilitySurface.UTILITY))
        advance(MimirMotion.OverlayMs * 2L)

        assertTrue("it did not ask to go", hidden.isEmpty())
        assertEquals("the same one Close", 1, closeNode().size)
    }

    // -- R1: the Tweaker and the Tabs drawer over a game ----------------------------------------------------------------------

    @Test
    fun `the Tweaker opens in the same window, opaque over the game, and Close returns the game's screen`() {
        val tweaker = FakeUtility(UtilitySurface.TWEAKER)
        mount(tweaker)

        assertEquals(UtilitySurface.TWEAKER, tweaker.shownSurface)
        writePng("bottom-tweaker-window.png")
        assertBackdropOpaque("Tweaker")
        val scene = compose.onNodeWithTag("scene").fetchSemanticsNode().boundsInRoot
        assertTrue("the Tweaker face has its Close", closeNode().isNotEmpty())
        closeNode().forEach { assertInside("Close", it.boundsInRoot, scene) }

        tapAt(closeNode().first().boundsInRoot.center)
        advance(MimirMotion.OverlayMs * 4L)

        assertNull(tweaker.shownSurface)
        assertEquals("the window asked to go once", 1, hidden.size)
        val (probeX, probeY) = fieldProbe()
        assertEquals("the emulator's field is back", emulatorField.toArgb(), pixel(probeX, probeY))
    }

    @Test
    fun `the Tabs drawer over a game has an opaque backdrop and its panel lies inside the Bottom`() {
        var dismissed = 0
        mountScene {
            CompanionRecentsDrawer(
                instrumentsShown = false,
                onToggleInstruments = {},
                entries = listOf(
                    CompanionAppEntry("com.limelight.noir", "Artemis", favorite = true, kindLabel = "Streaming"),
                    CompanionAppEntry("com.android.chrome", "Chrome", favorite = false, kindLabel = "Browser"),
                    CompanionAppEntry("org.ppsspp.ppsspp", "PPSSPP", favorite = true, kindLabel = "Emulator"),
                ),
                launchLocked = false,
                launchMessage = null,
                themeId = null,
                quickSlots = List(4) { null },
                quickLaunchApps = emptyList(),
                onQuickLaunch = {},
                onQuickAssign = { _, _ -> },
                onLaunch = {},
                onClose = {},
                onDismiss = { dismissed++ },
                opaqueBackdrop = true,
            )
        }

        writePng("bottom-tabs-over-game.png")
        assertBackdropOpaque("Tabs")
        assertEquals(0, dismissed)
    }

    private fun assertInside(what: String, inner: Rect, outer: Rect) {
        assertTrue(
            "$what ${inner.left / density369},${inner.top / density369}..${inner.right / density369},${inner.bottom / density369} dp is not inside the Bottom",
            inner.left >= outer.left - 1f && inner.top >= outer.top - 1f && inner.right <= outer.right + 1f && inner.bottom <= outer.bottom + 1f,
        )
    }
}

/** The Utility panel's state with the controls a Thor shows: one brightness line and the privileged rows, enough to fill the rail. */
private fun bottomUtilityRenderPanelState(): BrightnessState = BrightnessState(
    capabilities = BrightnessCapabilities(
        canWriteSystemSettings = true,
        mainSystemBrightnessActive = true,
        secondaryWindowAvailable = false,
        secondarySupport = SecondaryBrightnessSupport.UNAVAILABLE,
        joystickLightSupported = false,
        joystickLightColorSupported = false,
        mimirHapticsSupported = false,
        mimirHapticAmplitudeControl = false,
        mediaVolumeSupported = true,
        mediaVolumeFixed = false,
        mainWriteAccessUseful = true,
        privilegedUtilityPosture = PrivilegedUtilityPosture(PrivilegedUtilityStatus.READY),
        navSoundSupported = false,
        ambientSoundSupported = false,
    ),
    primaryFraction = 0.5f,
    primaryWindowFallback = null,
    secondaryFraction = null,
    secondaryWindowFallback = null,
    joystickLightFraction = null,
    joystickColorFraction = null,
    joystickColorArgb = null,
    themeAwareJoystickLight = false,
    mimirHapticFraction = null,
    mediaVolumeFraction = 0.4f,
    themeAwareJoystickLightRequested = false,
    themeAwareJoystickLightRestorePending = false,
    ambientSoundFraction = null,
)
