package com.gruning.mimir.rewrite.feature.library

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.MimirCopy
import com.gruning.mimir.rewrite.core.ui.MimirFocusTileRingReach
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirViewportPosture
import com.gruning.mimir.rewrite.core.ui.MimirViewportProfile
import java.io.File
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
 * Observed on emulator with Tetris: the Rename chip's focus ring touched the "s" of the name, and its RENAME caption stood higher than the name's centre line.
 * This renders the real [HomeLaunchCard] with Rename focused and holds both: the glyph stands the ring's full reach ([MimirFocusTileRingReach]) from the name's
 * last letter, and the glyph and its caption are centred on the name's line, as the achievement count beside the name already is. At fonts 1.0 and 1.3.
 * The card, and the title line cropped and enlarged, are saved as PNGs under this module's `build/reports` (the paths are printed). Pixels are the JVM's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w900dp-h520dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeLaunchCardRenameChipTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var fontScale by mutableStateOf(1f)
    private val authority = DefaultFocusAuthority().apply { pushScope(HomeLaunchCardScope) }
    private val cardTag = "rename-chip-card"

    @After
    fun unpark() {
        HomeLaunchCardResume.set(null)
    }

    private val cardState = HomeLaunchCardUiState(
        row = HomeShelfRow(
            itemId = HomeShelfItemId.Emulated(GameId("tetris")),
            rank = 0L,
            addedAtMillis = 0L,
            title = "Tetris",
            supportingLabel = "gb",
        ),
        platformLabel = "Game Boy",
        emulatorLabel = "RetroArch",
        lastPlayedLabel = "Yesterday",
    )

    private fun mount() {
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = 1f, fontScale = fontScale),
                LocalMimirViewportProfile provides MimirViewportProfile(MimirViewportPosture.STANDARD, 832.5f, 468.3f),
            ) {
                MimirTheme {
                    Box(modifier = Modifier.requiredWidth(832.5.dp).requiredHeight(468.3.dp).testTag(cardTag)) {
                        HomeLaunchCard(
                            state = cardState,
                            onPlay = {},
                            onAchievements = {},
                            onOpenWith = {},
                            onClose = {},
                            authority = authority,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { authority.requestFocus(HomeLaunchCardTitleEditKey) }
        compose.waitForIdle()
    }

    private fun rect(tag: String): Rect = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot

    /** The RENAME caption: the node that speaks "Rename" and is not the glyph (it stands right of it). Its width is reported as zero by design; its height and place are real. */
    private fun caption(glyph: Rect): Rect = compose.onAllNodes(hasContentDescription(MimirCopy.RenameGame), useUnmergedTree = true)
        .fetchSemanticsNodes()
        .map { it.boundsInRoot }
        .filter { it.left >= glyph.right - 1f }
        .also { assertTrue("the RENAME caption is drawn while Rename is focused (glyph=$glyph)", it.isNotEmpty()) }
        .first()

    private fun savePng(name: String, zoomTitleLine: Boolean) {
        val decor = compose.activity.window.decorView
        val full = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(full))
        val card = compose.onAllNodes(hasTestTag(cardTag), useUnmergedTree = true).fetchSemanticsNodes().first().boundsInWindow
        val cardLeft = Math.round(card.left).coerceIn(0, full.width - 1)
        val cardTop = Math.round(card.top).coerceIn(0, full.height - 1)
        val out = File(File(System.getProperty("user.dir"), "build/reports"), "$name.png")
        out.parentFile?.mkdirs()
        val image = if (zoomTitleLine) {
            val title = compose.onAllNodes(hasTestTag(HomeLaunchCardTitleTestTag), useUnmergedTree = true).fetchSemanticsNodes().first().boundsInWindow
            val glyph = compose.onAllNodes(hasTestTag(HomeLaunchCardRenameTestTag), useUnmergedTree = true).fetchSemanticsNodes().first().boundsInWindow
            val left = Math.round(title.left - 24f).coerceIn(0, full.width - 1)
            val top = Math.round(title.top - 36f).coerceIn(0, full.height - 1)
            val width = minOf(Math.round(glyph.right + 150f) - left, full.width - left)
            val height = minOf(Math.round(title.bottom + 36f) - top, full.height - top)
            Bitmap.createScaledBitmap(Bitmap.createBitmap(full, left, top, width, height), width * 4, height * 4, true)
        } else {
            Bitmap.createBitmap(full, cardLeft, cardTop, minOf(Math.round(card.width), full.width - cardLeft), minOf(Math.round(card.height), full.height - cardTop))
        }
        out.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("RENAME-CHIP-PNG ${out.absolutePath}")
        assertTrue("the PNG was written: ${out.absolutePath}", out.isFile && out.length() > 0)
    }

    @Test
    fun `the Rename ring has its full reach of room from the name and the glyph and its caption are centred on the name's line at fonts 1 and 1_3`() {
        mount()
        for (font in listOf(1.0f, 1.3f)) {
            fontScale = font
            compose.waitForIdle()
            val title = rect(HomeLaunchCardTitleTestTag)
            val glyph = rect(HomeLaunchCardRenameTestTag)
            val caption = caption(glyph)
            val room = glyph.left - title.right
            println("RENAME-CHIP font=$font title=$title glyph=$glyph caption=$caption room=$room")
            assertTrue("font=$font: the ring's whole reach (${MimirFocusTileRingReach.value} dp) stands between the name and the glyph, not ${room}", room >= MimirFocusTileRingReach.value - 0.5f)
            assertEquals("font=$font: the glyph is centred on the name's line (the 2 dp optical nudge up)", title.center.y, glyph.center.y, 3f)
            assertEquals("font=$font: the RENAME caption is on the name's centre line", title.center.y, caption.center.y, 1.5f)
            assertTrue("font=$font: the caption stands after the glyph, on the same line as the name: $caption vs $title", caption.top < title.bottom && caption.bottom > title.top)
            savePng("launch-card-rename-focus-font-$font", zoomTitleLine = false)
            savePng("launch-card-rename-chip-font-$font", zoomTitleLine = true)
        }
    }
}
