package com.gruning.mimir.rewrite.feature.companion

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.model.CompanionHomeLane
import com.gruning.mimir.rewrite.core.model.CompanionHomeTile
import com.gruning.mimir.rewrite.core.model.CompanionHomeTileKind
import com.gruning.mimir.rewrite.core.model.CompanionMode
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import com.gruning.mimir.rewrite.core.ui.BottomTrackpadPlateTag
import com.gruning.mimir.rewrite.core.ui.BottomTrackpadPlate
import com.gruning.mimir.rewrite.core.ui.LocalBottomTrackpadOpen
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirCompactHeader
import com.gruning.mimir.rewrite.core.ui.MimirBottomMarkDefaultTopDp
import com.gruning.mimir.rewrite.core.ui.MimirCompactHeaderRowTag
import com.gruning.mimir.rewrite.core.ui.MimirCompactHeaderTag
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
 * The lattice's header, with the Bottom's coin free to go anywhere (policy 2026-10-09, which retired the 2026-10-08 corner slot): the trailing cluster is the Trackpad
 * plate, at the header's end inset, with no empty band where the coin used to rest. Rendered on the real [CompanionSurface] at the Thor Bottom's 1240 x 1080 px at 369 dpi
 * (537.7 x 468.3 dp), at font scale 1.0 and 1.3.
 *
 * Held: the plate is the last control in the row and ends exactly at the row's end (the shell's 18 dp inset); the gap between the text and the plate is no wider than the normal
 * spacing; every text in the header is one line; the header (the row, its rule and the air under it) ends above the coin's default seat ([MimirBottomMarkDefaultTopDp]), so a
 * coin that has never been moved covers no header control. The header is saved as \`build/reports/hdr-lattice-1.0.png\` in this module (the path is printed). Pixels are the JVM's;
 * the handheld's are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionLatticeHeaderClusterRenderTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val density = 369f / 160f
    private val widthDp = 1240f / density
    private val heightDp = 1080f / density

    private val tiles = (1..19).map { CompanionHomeTile("p$it", CompanionHomeTileKind.PLATFORM, if (it == 9) "Nintendo DS" else "Platform number $it") }
    private val payload = CompanionPayload(
        mode = CompanionMode.HOME,
        statsLabel = "19 platforms · 1,234 games",
        homeLanes = listOf(CompanionHomeLane(id = "emulation", title = "Emulation", hint = "", tiles = tiles)),
    )

    private fun mount(fontScale: Float) {
        compose.mainClock.autoAdvance = false
        val openTrackpad: () -> Unit = {}
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density, fontScale),
                LocalBottomTrackpadOpen provides openTrackpad,
            ) {
                MimirTheme {
                    CompanionSurface(
                        payload = payload,
                        cursorId = "p1",
                        modifier = Modifier.requiredSize(widthDp.dp, heightDp.dp).testTag(HostTag),
                    )
                }
            }
        }
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(1_500L)
        compose.waitForIdle()
    }

    private class Box4(val x: Float, val y: Float, val w: Float, val h: Float) {
        val right get() = x + w
        val bottom get() = y + h
        override fun toString() = "%.1fx%.1f@(%.1f,%.1f)".format(w, h, x, y)
    }

    /** The panel is centred in a window of the JVM's own size, so every box is measured from the panel's own corner. */
    private fun SemanticsNode.dp(): Box4 {
        val o = compose.onNodeWithTag(HostTag).fetchSemanticsNode().positionInRoot
        val p = positionInRoot
        return Box4((p.x - o.x) / density, (p.y - o.y) / density, size.width / density, size.height / density)
    }

    private fun bounds(tag: String): Box4 = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().dp()

    private fun assertClusterAtHeaderEnd(fontScale: Float) {
        mount(fontScale)
        val row = bounds(MimirCompactHeaderRowTag)
        val header = bounds(MimirCompactHeaderTag)
        val plate = bounds(BottomTrackpadPlateTag)
        println("HDR-LATTICE font=$fontScale row=$row header=$header plate=$plate")
        assertEquals("the row ends at the shell's 18 dp end inset", widthDp - 18f, row.right, 1f)
        assertEquals("the Trackpad plate is the cluster, and it ends where the row does (no blank band at the top right)", row.right, plate.right, 1f)
        // Every text in the header's row is one line, and the widest gap in the row (the title group's, which takes what is left) never leaves the plate stranded: the text
        // that precedes it ends no further than the normal spacing from it unless it is the stat, which is right-aligned against it.
        val texts = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true).fetchSemanticsNodes()
            .filter { it.dp().let { box -> box.y >= row.y - 0.5f && box.bottom <= row.bottom + 0.5f } }
        for (node in texts) {
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            if (node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts) == true && layouts.isNotEmpty()) {
                assertEquals("'${layouts.first().layoutInput.text.text}' is one line", 1, layouts.first().lineCount)
            }
            assertTrue("a header text ${node.dp()} stops short of the plate $plate", node.dp().right <= plate.x + 0.5f)
        }
        val gap = plate.x - texts.maxOf { it.dp().right }
        assertTrue("the gap between the last text and the plate is the title group's own gap, no wider than the normal spacing: $gap", gap <= MimirSpacing.md.value + 0.6f)
        assertTrue("the header (to ${header.bottom} dp) ends above the coin's default seat at ${MimirBottomMarkDefaultTopDp}", header.bottom <= MimirBottomMarkDefaultTopDp - 1f)
    }

    @Test
    fun `the lattice header's trailing cluster is the plate at the header's end at font scale 1 point 0`() = assertClusterAtHeaderEnd(1.0f)

    @Test
    fun `the lattice header's trailing cluster is the plate at the header's end at font scale 1 point 3`() = assertClusterAtHeaderEnd(1.3f)

    /**
     * The picture for the eye: the lattice's header as the shell composes it (the same [MimirCompactHeader] call as CompanionShell's: Mimir, the tagline, the stat, the Trackpad
     * plate, the rule bleeding past the 18 dp gutter), drawn into a software bitmap under this module's build/reports (never an absolute machine path). The whole surface cannot
     * be rasterised here (the software canvas refuses its render-node layers), so the picture is the header on its own, on the page's ground.
     */
    @Test
    fun `the lattice header is saved as a picture`() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, 1.0f)) {
                MimirTheme {
                    val palette = LocalMimirPalette.current
                    Box(Modifier.requiredSize(widthDp.dp, 84.dp).testTag(HostTag).background(palette.surfaceBase)) {
                        Box(Modifier.padding(start = 18.dp, top = 10.dp, end = 18.dp)) {
                            MimirCompactHeader(
                                title = "Mimir",
                                palette = palette,
                                stat = "19 platforms · 1,234 games",
                                tagline = CompanionHeaderTagline,
                                trailing = { BottomTrackpadPlate(onOpen = {}) },
                                ruleBleed = 18.dp,
                            )
                        }
                    }
                }
            }
        }
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        val decor = compose.activity.window.decorView
        val full = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(full))
        val host = compose.onNodeWithTag(HostTag).fetchSemanticsNode().boundsInWindow
        val left = host.left.toInt().coerceIn(0, full.width - 1)
        val top = host.top.toInt().coerceIn(0, full.height - 1)
        val band = Bitmap.createBitmap(full, left, top, minOf(host.width.toInt(), full.width - left), minOf(host.height.toInt(), full.height - top))
        val out = File(File(System.getProperty("user.dir"), "build/reports"), "hdr-lattice-1.0.png")
        out.parentFile?.mkdirs()
        out.outputStream().use { band.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("HDR-LATTICE-PNG ${out.absolutePath}")
        assertTrue("the PNG was written: ${out.absolutePath}", out.isFile && out.length() > 0)
    }

    private companion object {
        const val HostTag = "hdr-lattice-host"
    }
}
