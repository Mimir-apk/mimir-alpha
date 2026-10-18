package com.gruning.mimir.rewrite.feature.files

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.files.FileSearchFilter
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import java.io.File
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pictures for update's second sweep (10-09) of the two Files surfaces that cannot be rendered through the whole [FileBrowserSurface] (its listing, its inspector and its footer are
 * recorded layers, which a software canvas cannot draw): the inspector's buttons (Open is the primary chip in a rounded rectangle, beside a tonal and an outlined one), and the search screen's
 * control row (the query field and the five chips as one family of capsule plates, one gap, the selected chip warmed), at rest and with the field and then a chip focused. The real
 * [FileBrowserButton] and [FileBrowserSearchScreen]. On Main at 853 x 480 dp (density 2), API 30. PNGs land in `feature-files/build/btn265/` and are copied to `build/test-captures` when it exists. Pixel
 * values are the JVM's; a handheld's are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class, qualifiers = "w853dp-h480dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FileBrowserEtchedCaptureTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun save(fileName: String) {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val dir = File("build/btn265").apply { mkdirs() }
        val out = File(dir, fileName)
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val shared = File("build/test-captures")
        if (shared.isDirectory) out.copyTo(File(shared, fileName), overwrite = true)
        println("BTN265 wrote ${out.absolutePath} (${out.length()} bytes, ${bitmap.width}x${bitmap.height} px)")
        assertTrue("the PNG was written: ${out.path}", out.isFile && out.length() > 1_000L)
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(1_500L)
        compose.waitForIdle()
    }

    @Test
    fun `the inspector's buttons - Open is the primary chip, Verify and Extract the unselected ones`() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, 1f)) {
                MimirTheme {
                    val palette = LocalMimirPalette.current
                    Box(
                        Modifier.requiredSize(853.dp, 480.dp).background(palette.surfaceBase),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            FileBrowserButton("Open", FileBrowserButtonVariant.PRIMARY, palette) {}
                            FileBrowserButton("Verify", FileBrowserButtonVariant.TONAL, palette) {}
                            FileBrowserButton("Extract…", FileBrowserButtonVariant.OUTLINED, palette) {}
                        }
                    }
                }
            }
        }
        settle()
        save("btn265-files-open.png")
    }

    private fun mountSearch(focused: FocusKey?) {
        val authority = DefaultFocusAuthority()
        val controller = FileBrowserController(
            authority = authority,
            rootPath = "/root",
            mode = FileBrowserMode.MANAGE,
            listDirectory = { emptyList() },
            onChoose = {},
            onDismiss = {},
        )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, 1f)) {
                MimirTheme {
                    val palette = LocalMimirPalette.current
                    Box(Modifier.requiredSize(853.dp, 480.dp).background(palette.surfaceBase)) {
                        FileBrowserSearchScreen(
                            controller = controller,
                            authority = authority,
                            focusedKey = focused,
                            search = { _, _ -> emptyFlow() },
                            volumeLabel = "SD card",
                            crumbLabels = emptyMap(),
                            palette = palette,
                            grid = fileBrowserSolveGrid(853f, 480f),
                            modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 24.dp),
                        )
                    }
                }
            }
        }
        settle()
    }

    @Test
    fun `the search row at rest - the field and the chips are one family of capsule plates with one gap`() {
        mountSearch(focused = null)
        save("btn265-files-search-rest.png")
    }

    @Test
    fun `the search row with the query field focused - a ring on the capsule, no fill`() {
        mountSearch(focused = FileBrowserSearchFocus.QueryKey)
        save("btn265-files-search-query.png")
    }

    @Test
    fun `the search row with the Games chip focused`() {
        mountSearch(focused = FileBrowserSearchFocus.filterKey(FileSearchFilter.GAMES))
        save("btn265-files-search-chip.png")
    }
}
