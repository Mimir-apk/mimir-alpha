package com.gruning.mimir.rewrite

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import com.gruning.mimir.rewrite.core.input.ControllerAction
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
import com.gruning.mimir.rewrite.feature.settings.MimirButtonPickerMark
import com.gruning.mimir.rewrite.feature.settings.MimirButtonPickerOverlay
import com.gruning.mimir.rewrite.feature.settings.MimirButtonPickerScope
import com.gruning.mimir.rewrite.feature.settings.MimirButtonRuneKey
import com.gruning.mimir.rewrite.feature.settings.BackgroundAtmosphereKey
import com.gruning.mimir.rewrite.feature.settings.backgroundPickerInitialState
import com.gruning.mimir.rewrite.feature.settings.backgroundPickerOnFocus
import com.gruning.mimir.rewrite.feature.settings.backgroundTileKey
import com.gruning.mimir.rewrite.feature.settings.mimirButtonPickerInitialState
import com.gruning.mimir.rewrite.feature.settings.mimirButtonPickerOnFocus
import com.gruning.mimir.rewrite.feature.settings.mimirButtonTileKey
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The footers as the pickers word them (the constants are `internal` to `:feature-settings`). */
private const val UploadFooter = "A UPLOAD · B BACK"
private const val UseFooter = "A USE · B BACK"
private const val SavedFooter = "A USE · X REMOVE · B BACK"
private const val SavedMarkFooter = "A USE · X REMOVE · Y ADJUST · B BACK"

/**
 * The Upload tile's footer on both appearance pickers as pixels (device review 2026-10-13: the footer read "A USE" on a tile where A opens the file picker). The real
 * Mimir-button and Background overlays with the real fonts, the pad walked onto the Upload tile exactly as a player does (the last saved tile, then right), at font 1.0 and 1.3 on the
 * Thor's Main (832 x 468 dp) and the 360 dp square the RG Rotate is.
 *
 * What it asserts is what a render cannot show: the footer on the Upload tile reads `A UPLOAD · B BACK` and nothing else is a footer then, it stands whole on ONE line inside the
 * page's 16 dp gutters, and the footers of the tiles beside it are as they were (the Rune and Atmosphere `A USE · B BACK`, a saved image its own legend). The PNGs
 * (`upload-footer-<picker>-<screen>-font<scale>.png` and the footer band alone, 3x, `upload-footer-crop-...`) land in `build/test-captures/tlog` (or `MIMIR_RENDER_DIR`).
 */
abstract class AppearanceUploadFooterRenderBase {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var dir: File
    private val authority = DefaultFocusAuthority()
    private var fontScale by mutableStateOf(1f)

    protected abstract val screen: String

    /** The screen in dp: the viewport the app measures at its root and provides, which the Background stage reads for its shelf. */
    protected abstract val viewport: Pair<Float, Float>

    @Before
    fun setUp() {
        dir = File.createTempFile("uploadfooter", "dir").apply { delete(); mkdirs() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private val density get() = compose.activity.resources.displayMetrics.density

    private fun picture(name: String, color: Int, circle: Boolean): String {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply { this.color = color; style = Paint.Style.FILL; isAntiAlias = true }
        if (circle) canvas.drawCircle(128f, 128f, 120f, paint) else canvas.drawRoundRect(8f, 96f, 248f, 160f, 24f, 24f, paint)
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

    private fun footerBounds(hint: String) =
        compose.onNode(hasContentDescription(hint), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun footersOnScreen(): List<String> =
        listOf(UploadFooter, UseFooter, SavedFooter, SavedMarkFooter).filter {
            compose.onAllNodes(hasContentDescription(it), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }

    /**
     * The one footer on screen is [hint]; and when [mustFit] (the Upload and A USE footers this change is about) it stands whole on one line inside the page's gutters. The saved
     * images' longer legends are asserted for identity only: how they fit a 360 dp square at font 1.3 is not this change's.
     */
    private fun assertFooter(hint: String, where: String, mustFit: Boolean = true) {
        assertEquals("$where: exactly this footer is on screen", listOf(hint), footersOnScreen())
        if (!mustFit) return
        val b = footerBounds(hint)
        val root = compose.activity.window.decorView.width.toFloat()
        val gutter = 16f * density
        assertTrue(
            "$where: \"$hint\" ($b) stands inside the ${root / density} dp screen with room either side of it",
            b.left >= gutter + 3f * density && b.right <= root - gutter - 3f * density,
        )
        assertTrue("$where: and it is one line: ${b.height / density} dp", b.height / density < 24f * fontScale)
        val cut = compose.onAllNodes(androidx.compose.ui.test.hasText("…", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("$where: text cut with an ellipsis: ${cut.map { it.config }}", cut.isEmpty())
    }

    private fun frame(): Bitmap {
        val decor = compose.activity.window.decorView
        val full = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(full))
        return full
    }

    private fun save(frame: Bitmap, name: String) {
        val folder = File(System.getenv("MIMIR_RENDER_DIR") ?: "build/test-captures/tlog").also { it.mkdirs() }
        val file = File(folder, name)
        file.outputStream().use { check(frame.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        assertTrue("the render landed: ${file.absolutePath}", file.isFile && file.length() > 0L)
        println("TLOG-RENDER ${file.absolutePath}")
    }

    /** The footer band alone, 3x, so the legend can be read word by word off the PNG. */
    private fun footerCrop(frame: Bitmap, hint: String): Bitmap {
        val b = footerBounds(hint)
        val pad = (10 * density).toInt()
        val top = (b.top.toInt() - pad).coerceAtLeast(0)
        val bottom = (b.bottom.toInt() + pad).coerceAtMost(frame.height)
        val band = Bitmap.createBitmap(frame, 0, top, frame.width, bottom - top)
        return Bitmap.createScaledBitmap(band, band.width * 3, band.height * 3, true)
    }

    // ---- the Mimir button picker ----------------------------------------------------------------------------------------------

    private fun mimirButton(scale: Float) {
        fontScale = scale
        val marks = listOf(
            MimirButtonPickerMark("aaaaaaaa", "Your mark 1", picture("aaaaaaaa", Color.WHITE, circle = true)),
            MimirButtonPickerMark("bbbbbbbb", "Your mark 2", picture("bbbbbbbb", Color.WHITE, circle = false)),
        )
        var state by mutableStateOf(mimirButtonPickerInitialState(null, marks))
        authority.pushScope(MimirButtonPickerScope)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                MimirTheme {
                    MimirButtonPickerOverlay(
                        authority = authority,
                        state = state,
                        onFocus = { key -> state = mimirButtonPickerOnFocus(state, key) },
                        onTransition = { transition -> state = transition.state },
                        presenceManagedExternally = true,
                        stage = { preview -> MimirButtonPickerLiveStage(preview) },
                        adjustCoin = { mark, adjustScale -> MimirButtonPickerAdjustCoin(mark, adjustScale) },
                    )
                }
            }
        }
        frames()
        val where = "Mimir button, $screen, font $scale"

        // A saved image keeps its own legend; the Rune keeps A USE.
        compose.runOnIdle { authority.requestFocus(mimirButtonTileKey(marks.last().id)) }
        frames(4)
        assertFooter(SavedMarkFooter, "$where, on a saved image", mustFit = false)
        compose.runOnIdle { authority.requestFocus(MimirButtonRuneKey) }
        frames(4)
        assertFooter(UseFooter, "$where, on the Rune")

        // The pad's way to Upload: the last saved tile, then right.
        compose.runOnIdle { authority.requestFocus(mimirButtonTileKey(marks.last().id)) }
        frames(4)
        compose.runOnIdle { authority.moveFocus(ControllerAction.NAV_RIGHT) }
        frames(6)
        assertFooter(UploadFooter, "$where, on Upload")
        val shot = frame()
        save(shot, "upload-footer-mimirbutton-$screen-font$scale.png")
        save(footerCrop(shot, UploadFooter), "upload-footer-crop-mimirbutton-$screen-font$scale.png")
    }

    // ---- the Background picker ------------------------------------------------------------------------------------------------

    private fun background(scale: Float) {
        fontScale = scale
        val images = listOf(
            BackgroundPickerImage("aaaaaaaa", "Your image 1", picture("bg1", Color.rgb(40, 90, 150), circle = false), 1920, 1080, 0.3f),
            BackgroundPickerImage("bbbbbbbb", "Your image 2", picture("bg2", Color.rgb(150, 70, 60), circle = false), 1920, 1080, 0.5f),
        )
        var state by mutableStateOf(backgroundPickerInitialState(MimirThemeId.DEFAULT, null, images))
        authority.pushScope(BackgroundPickerScope)
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
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
        val where = "Background, $screen, font $scale"

        // A saved image keeps its own legend; Atmosphere keeps A USE.
        compose.runOnIdle { authority.requestFocus(backgroundTileKey(images.last().id)) }
        frames(4)
        assertFooter(SavedFooter, "$where, on a saved image", mustFit = false)
        compose.runOnIdle { authority.requestFocus(BackgroundAtmosphereKey) }
        frames(4)
        assertFooter(UseFooter, "$where, on Atmosphere")

        // The pad's way to Upload: the last saved tile, then right.
        compose.runOnIdle { authority.requestFocus(backgroundTileKey(images.last().id)) }
        frames(4)
        compose.runOnIdle { authority.moveFocus(ControllerAction.NAV_RIGHT) }
        frames(6)
        assertFooter(UploadFooter, "$where, on Upload")
        val shot = frame()
        save(shot, "upload-footer-background-$screen-font$scale.png")
        save(footerCrop(shot, UploadFooter), "upload-footer-crop-background-$screen-font$scale.png")
    }

    @Test
    fun mimirButtonAtFont1() = mimirButton(1.0f)

    @Test
    fun mimirButtonAtFont13() = mimirButton(1.3f)

    @Test
    fun backgroundAtFont1() = background(1.0f)

    @Test
    fun backgroundAtFont13() = background(1.3f)
}

/** The Thor's Main: 1920 x 1080 px at 369 dpi. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w832dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppearanceUploadFooterThorRenderTest : AppearanceUploadFooterRenderBase() {
    override val screen = "thor"
    override val viewport = 832f to 468f
}

/** The compact square: 360 x 360 dp, the RG Rotate's 720 px at 320 dpi. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h360dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppearanceUploadFooterCompactRenderTest : AppearanceUploadFooterRenderBase() {
    override val screen = "compact"
    override val viewport = 360f to 360f
}
