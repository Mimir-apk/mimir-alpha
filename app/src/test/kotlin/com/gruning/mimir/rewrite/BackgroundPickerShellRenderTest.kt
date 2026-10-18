package com.gruning.mimir.rewrite

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.PlatformRow
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import com.gruning.mimir.rewrite.core.ui.mimirViewportProfile
import com.gruning.mimir.rewrite.feature.settings.BackgroundPickerImage
import com.gruning.mimir.rewrite.feature.settings.BackgroundPickerOverlay
import com.gruning.mimir.rewrite.feature.settings.BackgroundPickerScope
import com.gruning.mimir.rewrite.feature.settings.backgroundPickerInitialState
import com.gruning.mimir.rewrite.feature.settings.backgroundPickerOnFocus
import com.gruning.mimir.rewrite.feature.settings.backgroundTileKey
import java.io.File
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Background picker on the shell it shares with the Mimir-button picker (review 2026-10-13: the shell's header is now measured and the stage takes what is left, a
 * compact screen shares the tile row among the tiles it has, a tile's name wraps to two lines, a badge is whole or left off), so the picker that did NOT ask for any of it
 * is rendered too. The real overlay and the real shelf chrome on the stage, with real fonts; PNGs land in `build/test-captures/markadjust/round2/` when that folder exists.
 *
 * What it asserts is only what a render cannot show: no text is shortened with an ellipsis, and each saved tile's name stands whole. The look is read off the PNGs.
 */
abstract class BackgroundPickerShellRenderBase {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var dir: File
    private val authority = DefaultFocusAuthority()
    private var fontScale by mutableStateOf(1f)

    @Before
    fun setUp() {
        dir = File.createTempFile("bgshell", "dir").apply { delete(); mkdirs() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    protected abstract val screen: String

    /** The screen in dp: the viewport the app measures at its root and provides, which the stage reads for its shelf. */
    protected abstract val viewport: Pair<Float, Float>

    private fun photo(name: String, color: Int): String {
        val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val file = File(dir, "$name.png")
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        return file.absolutePath
    }

    private fun frames(count: Int = 8) {
        repeat(count) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
        }
        compose.waitForIdle()
    }

    private fun shoot(scale: Float) {
        fontScale = scale
        val images = listOf(
            BackgroundPickerImage("aaaaaaaa", "Your image 1", photo("aaaaaaaa", Color.rgb(40, 90, 150)), 1920, 1080, 0.3f),
            BackgroundPickerImage("bbbbbbbb", "Your image 2", photo("bbbbbbbb", Color.rgb(150, 70, 60)), 1920, 1080, 0.5f),
            BackgroundPickerImage("cccccccc", "Your image 3", photo("cccccccc", Color.rgb(60, 130, 80)), 1920, 1080, 0.2f),
        )
        var state by mutableStateOf(backgroundPickerInitialState(MimirThemeId.DEFAULT, null, images))
        authority.pushScope(BackgroundPickerScope)
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
                // What the app provides at its root: the measured viewport, which the stage reads for the width and the posture of the shelf it shows.
                LocalMimirViewportProfile provides mimirViewportProfile(viewport.first, viewport.second),
            ) {
                MimirTheme {
                    BackgroundPickerOverlay(
                        authority = authority,
                        state = state,
                        dualScreen = false,
                        onFocus = { key -> state = backgroundPickerOnFocus(state, key) },
                        onTransition = { transition -> state = transition.state },
                        presenceManagedExternally = true,
                        stageChrome = {
                            BackgroundPickerLiveStageChrome(
                                authority = authority,
                                platforms = listOf(
                                    PlatformRow(PlatformId("snes"), "Super Nintendo", 12),
                                    PlatformRow(PlatformId("dreamcast"), "Dreamcast", 1),
                                    PlatformRow(PlatformId("gba"), "Game Boy Advance", 7),
                                ),
                                gameCount = 50,
                            )
                        },
                    )
                }
            }
        }
        frames()
        compose.runOnIdle { authority.requestFocus(backgroundTileKey(images[0].id)) }
        frames(4)

        for (name in listOf("Atmosphere") + images.map { it.label }) {
            val node = compose.onNode(hasText(name), useUnmergedTree = true).fetchSemanticsNode()
            val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            val asked = node.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult)?.action?.invoke(results) ?: false
            assertTrue("the tile name \"$name\" reports its layout", asked && results.isNotEmpty())
            val layout = results.first()
            assertTrue("\"$name\" has at most two lines, not ellipsized", layout.lineCount <= 2 && (0 until layout.lineCount).none { layout.isLineEllipsized(it) } && !layout.didOverflowHeight)
            for (line in 0 until layout.lineCount - 1) {
                val end = layout.getLineEnd(line, visibleEnd = false)
                assertTrue("\"$name\" breaks inside a word at $end", name[end - 1].isWhitespace() || name[end].isWhitespace())
            }
        }
        val cut = compose.onAllNodes(hasText("…", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("text cut with an ellipsis: ${cut.map { it.config }}", cut.isEmpty())

        val decor = compose.activity.window.decorView
        val shot = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(shot))
        val folder = File(System.getenv("MIMIR_RENDER_DIR") ?: "build/test-captures/markadjust/round2")
        if (folder.isDirectory) File(folder, "bgpicker-$screen-font$scale.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun atFont1() = shoot(1.0f)

    @Test
    fun atFont13() = shoot(1.3f)
}

/** The Thor's Main: 1920 x 1080 px at 369 dpi. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w832dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackgroundPickerShellThorRenderTest : BackgroundPickerShellRenderBase() {
    override val screen = "thor"
    override val viewport = 832f to 468f
}

/** The compact square: 360 x 360 dp, the RG Rotate's 720 px at 320 dpi. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h360dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackgroundPickerShellCompactRenderTest : BackgroundPickerShellRenderBase() {
    override val screen = "compact"
    override val viewport = 360f to 360f
}
