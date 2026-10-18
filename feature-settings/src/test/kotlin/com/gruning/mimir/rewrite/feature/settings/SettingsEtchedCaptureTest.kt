package com.gruning.mimir.rewrite.feature.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.model.library.LibrarySummary
import com.gruning.mimir.rewrite.core.model.library.PlatformRow
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pictures for update's second sweep (10-09) of the real Library pane ([SettingsDetailPanel] on [SettingsCategory.LIBRARY]), whole, in a tall window so nothing is below the fold: the Backup
 * and Recovery button pairs with the ring's room above them (a focused Export's halo must stop short of the caption), and a hidden game's row naming its platform. API 30 (the level at which
 * the platform cannot blur, because a software canvas cannot draw the glass sheet's RenderNode layers), 560 dp wide, density 2. PNGs land in `feature-settings/build/btn265/` and are copied to
 * `build/test-captures` when it exists. Pixel values are the JVM's; a handheld's are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class, qualifiers = "w853dp-h1400dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsEtchedCaptureTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val authority = DefaultFocusAuthority()

    private val tetris = GameRow(
        gameId = GameId("g1"),
        title = "Tetris",
        platformId = PlatformId("gb"),
        memberCount = 1,
        duplicateKind = null,
    )

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

    private fun mountLibraryPane() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, 1f)) {
                MimirTheme {
                    Box(Modifier.requiredSize(560.dp, 1400.dp).background(LocalMimirPalette.current.surfaceBase)) {
                        SettingsDetailPanel(
                            category = SettingsCategory.LIBRARY,
                            authority = authority,
                            onRowActivate = {},
                            library = LibraryReadModel(
                                summary = LibrarySummary(gameCount = 1, platformCount = 0),
                                sources = emptyList(),
                                hiddenGames = listOf(tetris),
                                platforms = listOf(PlatformRow(PlatformId("gb"), "Game Boy", gameCount = 0)),
                            ),
                        )
                    }
                }
            }
        }
        settle()
    }

    @Test
    fun `the Library pane at rest - the Backup pairs stand a ring's reach under their captions, a hidden game names its platform`() {
        mountLibraryPane()
        save("btn265-settings-library-rest.png")
    }

    @Test
    fun `the Library pane with Export backup focused - the halo stops short of the caption above it`() {
        mountLibraryPane()
        compose.runOnIdle {
            authority.pushScope(DetailScope)
            authority.requestFocus(detailKey("library/backup/export"))
        }
        settle()
        save("btn265-settings-library-export-focused.png")
    }
}
