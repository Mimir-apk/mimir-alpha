package com.gruning.mimir.rewrite.feature.companion

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.model.CompanionAppEntry
import com.gruning.mimir.rewrite.core.model.CompanionHomeLane
import com.gruning.mimir.rewrite.core.model.CompanionHomeTile
import com.gruning.mimir.rewrite.core.model.CompanionHomeTileKind
import com.gruning.mimir.rewrite.core.model.CompanionMode
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import com.gruning.mimir.rewrite.core.ui.LocalBottomUiDisplay
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirPressScale
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Thor Bottom revamp B5 (policy R4), on the REAL dock at the Bottom's 538 dp at 369 dpi, on a paused clock with real touch events: a held dock seat DRAWS in to the press scale and a release brings
 * it back. The scale is read off the pixels: a solid-colour app icon is the probe, and its area (pixels within a tolerance of the ink) goes as the scale squared.
 * Pixel values are the JVM's; the pixels on a handheld are device-owed. (The lattice's tiles are in `CompanionLatticePressTest`: they are driven from the drag
 * root, not from a tap node, and that test composes the whole surface.) Also here: the P3, a tap node whose source is replaced.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h445dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionPressResponseRenderTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val ink = Color(0xFFFF00FF)
    private var density = 0f

    private val iconFile: File = File.createTempFile("b5-icon", ".png").also { file ->
        val bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(0xFFFF00FF.toInt())
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        companionAppIconCache.put(file.path, bitmap.asImageBitmap())
    }

    @After
    fun tearDown() {
        companionAppIconCache.remove(iconFile.path)
        iconFile.delete()
    }

    private fun frames(ms: Long) {
        // Apply first: a state written since the last frame must be seen by the recomposition this advance runs.
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(ms)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    /** The ink's area on screen, in px: every pixel within a fifth of the ink colour. The scale is the root of this over the area at rest. */
    private fun inkArea(): Int {
        val decor = compose.activity.window.decorView
        val full = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(full))
        val pixels = full.asImageBitmap().toPixelMap()
        var count = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val p = pixels[x, y]
            if (max(max(abs(p.red - ink.red), abs(p.green - ink.green)), abs(p.blue - ink.blue)) < 0.2f) count++
        }
        return count
    }

    private fun scaleOf(area: Int, rest: Int): Float = sqrt(area.toFloat() / rest.toFloat())

    // ---------------------------------------------------------------------------------------------------------------------------------
    // The dock seat.
    // ---------------------------------------------------------------------------------------------------------------------------------

    private val withIcon = CompanionAppEntry("org.example.alpha", "Alpha", iconPath = iconFile.path, closable = true)

    @Test
    fun `a held dock seat draws in to the press scale, a release returns it`() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(Density369, 1f)) {
                MimirTheme {
                    density = LocalDensity.current.density
                    CompanionQuickLaunchLayer(
                        instrumentsShown = false,
                        slots = listOf(withIcon.packageName, null, null, null, null),
                        apps = listOf(withIcon),
                        launchLocked = false,
                        launchMessage = null,
                        onLaunch = {},
                        onAssign = { _, _ -> },
                        modifier = Modifier.fillMaxSize().testTag(DockRoot),
                    )
                }
            }
        }
        frames(2_000L)
        val rest = inkArea()
        assertTrue("the probe icon is on screen: $rest px", rest > 1_000)

        val icon = compose.onNodeWithTag(CompanionQuickIconTag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag(DockRoot).performTouchInput { down(Offset(icon.center.x, icon.center.y)) }
        frames(MimirMotion.FocusMs.toLong() + 64L)
        val held = scaleOf(inkArea(), rest)
        println("B5-RENDER seat held scale=$held token=$MimirPressScale")
        assertEquals("a held seat is drawn at the press scale, to within the edge's antialiasing: $held", MimirPressScale, held, 0.025f)
        assertTrue("and visibly in: $held", held < 0.99f)

        compose.onNodeWithTag(DockRoot).performTouchInput { up() }
        frames(MimirMotion.FocusMs.toLong() + 64L)
        val released = scaleOf(inkArea(), rest)
        println("B5-RENDER seat released scale=$released")
        assertEquals("a released seat is whole: $released", 1f, released, 0.012f)
    }

    // ---------------------------------------------------------------------------------------------------------------------------------
    // A replaced source still gets the presses (the tap node's keys are only `enabled`).
    // ---------------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a tap node whose source is replaced while it stays enabled reports to the new source, not the first`() {
        val first = MutableInteractionSource()
        val second = MutableInteractionSource()
        var current by mutableStateOf(first)
        val seenFirst = mutableListOf<Interaction>()
        val seenSecond = mutableListOf<Interaction>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LaunchedEffect(first) { first.interactions.collect { seenFirst += it } }
            LaunchedEffect(second) { second.interactions.collect { seenSecond += it } }
            Box(Modifier.size(100.dp).companionChoiceTap(enabled = true, label = "Probe", interactionSource = current) {}.testTag(SwapProbe))
        }
        frames(100)
        // A first press starts the gesture's coroutine (it starts on the first pointer event) with the first source in hand.
        compose.onNodeWithTag(SwapProbe).performTouchInput { down(center) }
        frames(32)
        compose.onNodeWithTag(SwapProbe).performTouchInput { up() }
        frames(32)
        assertEquals("the first source got that press and its release: $seenFirst", 2, seenFirst.size)
        // Then the source is replaced while the node stays enabled: the gesture's keys are unchanged, so it keeps running.
        current = second
        frames(100)
        compose.onNodeWithTag(SwapProbe).performTouchInput { down(center) }
        frames(32)
        compose.onNodeWithTag(SwapProbe).performTouchInput { up() }
        frames(32)
        assertTrue("the replaced source got the press and its release: $seenSecond", seenSecond.any { it is PressInteraction.Press } && seenSecond.any { it is PressInteraction.Release })
        assertEquals("and the first source got nothing after the swap: $seenFirst", 2, seenFirst.size)
    }

    private companion object {
        const val Density369 = 369f / 160f
        const val DISPLAY = 4
        const val DockRoot = "b5-dock-root"
        const val SwapProbe = "b5-swap-probe"
    }
}
