package com.gruning.mimir.rewrite.feature.files

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import java.io.File
import kotlin.math.abs
import com.gruning.mimir.rewrite.core.model.jobs.FileQueueState
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
 * *** The queue card's status chip was the detail panel's old recipe, a pill tinted with its tone (the ember, the success green, the danger red). It is an outline
 * now, in all four tones. The real card ([FileBrowserQueueCardRowForCapture], the list's own composable) at a 1.3 font, one card per tone; the PNG `queue-chips-1.3.png` lands in `MIMIR_RENDER_DIR`
 * (default `build/test-captures/tfp`). What is asserted is read off the pixels: inside each chip's padding the ground is the card's own, and the chip's rim is the tone's outline colour.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w833dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FileBrowserQueueChipCaptureTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val authority = DefaultFocusAuthority()
    private val controller = FileBrowserController(
        authority = authority,
        rootPath = "/root",
        mode = FileBrowserMode.PICK_IMAGE,
        listDirectory = { emptyList() },
        onChoose = {},
        onDismiss = {},
    )

    private fun card(id: Long, title: String, state: FileQueueState, tone: FileBrowserQueueTone) = FileBrowserQueueCard(
        entryId = id,
        title = title,
        subtitle = "To Internal Storage › Download",
        state = state,
        tone = tone,
        progress = null,
        actions = emptyList(),
    )

    private val cards = listOf(
        card(1, "Roms.zip", FileQueueState.Running(fraction = null), FileBrowserQueueTone.NEUTRAL),
        card(2, "Saves.zip", FileQueueState.AwaitingConflict, FileBrowserQueueTone.PENDING),
        card(3, "Covers.zip", FileQueueState.Done, FileBrowserQueueTone.DONE),
        card(4, "Music.zip", FileQueueState.Failed("Could not read the archive."), FileBrowserQueueTone.FAILED),
    )

    private var palette: com.gruning.mimir.rewrite.core.ui.MimirThemePalette? = null

    private fun mount(fontScale: Float) {
        compose.runOnUiThread { compose.activity.actionBar?.hide() }
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(369f / 160f, fontScale)) {
                MimirTheme {
                    val colours = LocalMimirPalette.current
                    palette = colours
                    Box(Modifier.requiredSize(833.dp, 468.dp).background(colours.surfaceBase)) {
                        Column(Modifier.padding(start = 24.dp, top = 16.dp).width(520.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            cards.forEach { FileBrowserQueueCardRowForCapture(it, controller, authority, null, colours) }
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_500L)
        compose.waitForIdle()
    }

    private fun save(fileName: String): Bitmap {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val dir = File(System.getenv("MIMIR_RENDER_DIR") ?: "build/test-captures/tfp")
        if (!dir.isDirectory) dir.mkdirs()
        val out = File(dir, fileName)
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("QUEUECHIP wrote ${out.absolutePath} (${out.length()} bytes, ${bitmap.width}x${bitmap.height} px)")
        assertTrue("the PNG was written: ${out.path}", out.isFile && out.length() > 1_000L)
        return bitmap
    }

    private fun chip(word: String): SemanticsNode = compose.onAllNodes(
        SemanticsMatcher("chip $word") { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == word } == true },
        useUnmergedTree = true,
    ).fetchSemanticsNodes().firstOrNull() ?: error("the chip '$word' is not drawn")

    private fun distance(a: Int, b: Int) = abs(AndroidColor.red(a) - AndroidColor.red(b)) + abs(AndroidColor.green(a) - AndroidColor.green(b)) + abs(AndroidColor.blue(a) - AndroidColor.blue(b))

    /** [rim] drawn over [ground]: the palette's outline is translucent, so a rim pixel is its blend with the card's ground. */
    private fun over(rim: Color, ground: Color): Int {
        val a = rim.alpha
        fun mix(r: Float, g: Float) = ((r * a + g * (1f - a)) * 255f + 0.5f).toInt()
        return AndroidColor.rgb(mix(rim.red, ground.red), mix(rim.green, ground.green), mix(rim.blue, ground.blue))
    }

    /** The chip's rim is the colour of its word: some pixel on the row through the word, in the chip's left padding, is that colour. */
    private fun assertOutlined(bitmap: Bitmap, word: String, ground: Color, rim: Color) {
        val density = compose.activity.resources.displayMetrics.density
        val node = chip(word)
        val y = node.boundsInRoot.center.y.toInt()
        val inside = bitmap.getPixel((node.boundsInRoot.left - 4.5f * density).toInt(), y)
        println("QUEUECHIP '$word' inside=#${Integer.toHexString(inside)} ground=#${Integer.toHexString(ground.toArgb())}")
        assertTrue("'$word': the chip's inside (#${Integer.toHexString(inside)}) is the card's ground (#${Integer.toHexString(ground.toArgb())}), not a fill", distance(inside, ground.toArgb()) <= 24)
        val rimPixels = ((node.boundsInRoot.left - 11f * density).toInt()..(node.boundsInRoot.left - 7f * density).toInt()).map { bitmap.getPixel(it, y) }
        val closest = rimPixels.minOf { distance(it, over(rim, ground)) }
        assertTrue("'$word': the chip's rim is the tone's colour over the ground (#${Integer.toHexString(over(rim, ground))}); the closest pixel is off by $closest", closest <= 40)
    }

    @Test
    fun `the queue card's chip is an outline in all four tones at 1_3x`() {
        mount(1.3f)
        val bitmap = save("queue-chips-1.3.png")
        val colours = palette ?: error("no palette")
        assertEquals("four cards", 4, cards.size)
        assertOutlined(bitmap, "Working…", colours.surfaceRaised, colours.outline)
        assertOutlined(bitmap, "Name taken", colours.surfaceRaised, colours.emberHot)
        assertOutlined(bitmap, "Done", colours.surfaceRaised, colours.success)
        assertOutlined(bitmap, "Failed", colours.surfaceRaised, colours.danger)
        // The word is not shrunk by it: each chip says its whole word.
        listOf("Working…", "Name taken", "Done", "Failed").forEach { word ->
            assertFalse("'$word' is drawn", chip(word).boundsInRoot.isEmpty)
        }
    }
}
