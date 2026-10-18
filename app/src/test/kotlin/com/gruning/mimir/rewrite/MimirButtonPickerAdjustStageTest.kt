package com.gruning.mimir.rewrite

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.companionnative.CompanionButtonMark
import com.gruning.mimir.rewrite.core.companionnative.createMimirButtonPreviewView
import com.gruning.mimir.rewrite.core.companionnative.setMimirButtonPreviewRinged
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.model.MimirMarkPlacement
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import com.gruning.mimir.rewrite.feature.settings.MimirButtonPickerCommand
import com.gruning.mimir.rewrite.feature.settings.MimirButtonPickerMark
import com.gruning.mimir.rewrite.feature.settings.MimirButtonPickerOverlay
import com.gruning.mimir.rewrite.feature.settings.MimirButtonPickerScope
import com.gruning.mimir.rewrite.feature.settings.dispatchMimirButtonPicker
import com.gruning.mimir.rewrite.feature.settings.mimirButtonPickerInitialState
import com.gruning.mimir.rewrite.feature.settings.mimirButtonPickerOnFocus
import com.gruning.mimir.rewrite.feature.settings.mimirButtonTileKey
import java.io.File
import java.io.FileOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

/**
 * The Mimir-button picker's ADJUST stage with the REAL button on it : the large coin to work on and the real 56 dp button
 * beside it are the native overlay's own view, built once for the image and followed IN PLACE by every nudge, pinch and drag, so the two coins
 * and the live button cannot disagree about where the image sits, and no half dp rebuilds or cross-fades a coin.
 *
 * The ADJUST coins are `CompanionMenuCoinView`s found where the compose view put them; the browse stage's seats are bitmaps (audit
 * 2026-10-14, RC-13: [MimirButtonPickerStageTest]). What each paints is [CompanionButtonMarkDrawTest]'s, and the stage's pixels with the
 * real fonts are [MimirButtonAdjustRenderTest]'s.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w832dp-h468dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MimirButtonPickerAdjustStageTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File.createTempFile("adjuststage", "dir").apply { delete(); mkdirs() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun triangle(name: String): String {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val path = android.graphics.Path().apply {
            moveTo(128f, 8f)
            lineTo(248f, 248f)
            lineTo(8f, 248f)
            close()
        }
        Canvas(bitmap).drawPath(path, android.graphics.Paint().apply { color = Color.WHITE })
        val file = File(dir, "$name.png")
        FileOutputStream(file).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        return file.absolutePath
    }

    private fun coins(): List<View> {
        val found = mutableListOf<View>()
        fun walk(view: View) {
            if (view.javaClass.simpleName == "CompanionMenuCoinView") found += view
            if (view is ViewGroup) for (index in 0 until view.childCount) walk(view.getChildAt(index))
        }
        walk(compose.activity.window.decorView)
        return found
    }

    private fun placementOf(coin: View): MimirMarkPlacement = ReflectionHelpers.getField(coin, "placement")

    private fun draw(view: View): Bitmap {
        val width = view.width.coerceAtLeast(1)
        val height = view.height.coerceAtLeast(1)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    }

    private val authority = DefaultFocusAuthority()
    private lateinit var star: MimirButtonPickerMark
    private var state by mutableStateOf(mimirButtonPickerInitialState(null, emptyList()))
    private val commands = mutableListOf<MimirButtonPickerCommand?>()

    private fun open(placement: MimirMarkPlacement) {
        star = MimirButtonPickerMark("aaaaaaaa", "Your mark 1", triangle("aaaaaaaa"), placement)
        state = mimirButtonPickerInitialState(null, listOf(star))
        authority.pushScope(MimirButtonPickerScope)
        compose.setContent {
            MimirTheme {
                Box(Modifier.size(832.dp, 420.dp)) {
                    MimirButtonPickerOverlay(
                        authority = authority,
                        state = state,
                        onFocus = { key -> state = mimirButtonPickerOnFocus(state, key) },
                        onTransition = { transition ->
                            commands += transition.command
                            state = transition.state
                        },
                        presenceManagedExternally = true,
                        stage = { preview -> MimirButtonPickerLiveStage(preview) },
                        adjustCoin = { mark, scale -> MimirButtonPickerAdjustCoin(mark, scale) },
                    )
                }
            }
        }
        frames()
        compose.runOnIdle { authority.requestFocus(mimirButtonTileKey(star.id)) }
        frames(3)
    }

    private fun frames(count: Int = 6) {
        repeat(count) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
        }
        compose.waitForIdle()
    }

    private fun press(action: ControllerAction, times: Int = 1) {
        repeat(times) {
            compose.runOnIdle {
                dispatchMimirButtonPicker(authority, state, action) { transition ->
                    commands += transition.command
                    state = transition.state
                }
            }
        }
        frames(3)
    }

    @Test
    fun `the large coin and the real one are the same native view, wearing the placement being shaped`() {
        val saved = MimirMarkPlacement(scale = 0.8f, offsetX = 3f, offsetY = -2f)
        open(saved)
        assertEquals("the browse stage hosts no native coin: its seats are pixels the host's recording owns (audit 10-14, RC-13)", 0, coins().size)
        assertEquals("the browse stage's three seats", 3, compose.onAllNodesWithTag(MimirButtonStageCoinTag, useUnmergedTree = true).fetchSemanticsNodes().size)

        press(ControllerAction.TERTIARY)

        val adjust = coins()
        assertEquals("the large coin and the real button", 2, adjust.size)
        adjust.forEach { coin ->
            assertFalse("a stage coin holds no focus", coin.isFocusable || coin.isFocusableInTouchMode)
            assertFalse("and takes no touch", coin.isClickable)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, coin.importantForAccessibility)
            assertTrue("the player's image, not the rune", ReflectionHelpers.getField<Any?>(coin, "mark") is BitmapDrawable)
            assertEquals("each wears the placement the image was saved with", saved, placementOf(coin))
        }
    }

    @Test
    fun `a nudge or a size follows in place on both coins, with no rebuild, and they agree with the live button's own pixels`() {
        open(MimirMarkPlacement.Default)
        press(ControllerAction.TERTIARY)
        val before = coins()
        assertEquals(2, before.size)

        press(ControllerAction.NAV_RIGHT, times = 6)
        press(ControllerAction.NAV_DOWN, times = 4)
        press(ControllerAction.PAGE_LEFT, times = 3)

        val working = state.adjust!!.working
        assertEquals(MimirMarkPlacement(0.85f, 3f, 2f).scale, working.scale, 0.00001f)
        val after = coins()
        assertEquals(2, after.size)
        before.zip(after).forEach { (was, now) ->
            assertSame("the same view: a half dp step does not rebuild or cross-fade the coin", was, now)
            assertEquals(working, placementOf(now))
        }

        // The real 56 dp coin on the stage paints exactly what the live button's coin paints for that image and placement: the live button is
        // built from the same overlay theme, so what the player judges here is what the overlay will wear.
        val live = createMimirButtonPreviewView(
            compose.activity,
            companionOverlayTheme(MimirThemeId.DEFAULT, buttonMark = com.gruning.mimir.rewrite.core.ui.MimirButtonMark(star.id, star.path!!, scale = working.scale, offsetX = working.offsetX, offsetY = working.offsetY)),
        )
        val real = after.minByOrNull { it.width }!!
        live.measure(View.MeasureSpec.makeMeasureSpec(real.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(real.height, View.MeasureSpec.EXACTLY))
        live.layout(0, 0, real.width, real.height)
        assertTrue("the real 56 dp coin on the stage is the live button's coin for that placement", draw(live).sameAs(draw(real)))

        // The large coin is that same view built over a context of `scale` times the density and laid out `scale` times as large (so it draws sharp, not a 56 dp raster
        // magnified): proportion for proportion it is the real coin. Its own pixels are the real coin's drawn magnified, to within resampling.
        val large = after.maxByOrNull { it.width }!!
        val scale = large.width.toFloat() / real.width
        assertTrue("the large coin is laid out larger, not scaled by the compositor ($scale)", scale > 1.5f)
        assertEquals("and it is square", large.width, large.height)
        val magnified = Bitmap.createBitmap(large.width, large.height, Bitmap.Config.ARGB_8888).also { bitmap ->
            Canvas(bitmap).apply { scale(scale, scale); real.draw(this) }
        }
        assertEquals("the same placement on the large coin", working, placementOf(large))
        assertTrue("the large coin hands its rim to the picker's focus ring, so the ring is its one edge", ReflectionHelpers.getField<Boolean>(large, "ringed"))
        assertFalse("the real button beside it is not focused and keeps its rim", ReflectionHelpers.getField<Boolean>(real, "ringed"))
        // The rim is the one thing the ring takes; give it back for the comparison, which is of the coin's face and the image on it.
        compose.runOnUiThread { setMimirButtonPreviewRinged(large, false) }
        val spread = pixelDifference(draw(large), magnified)
        assertTrue("the large coin is the real coin magnified: mean ${spread.first}, ${spread.second} of pixels far off", spread.first < 6.0 && spread.second < 0.04)
    }

    /** The mean absolute channel difference (0 to 255) between two same-sized bitmaps, and the share of pixels that differ by more than 96 in some channel. */
    private fun pixelDifference(a: Bitmap, b: Bitmap): Pair<Double, Double> {
        check(a.width == b.width && a.height == b.height) { "${a.width}x${a.height} vs ${b.width}x${b.height}" }
        val pa = IntArray(a.width * a.height).also { a.getPixels(it, 0, a.width, 0, 0, a.width, a.height) }
        val pb = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        var total = 0L
        var far = 0
        for (i in pa.indices) {
            var worst = 0
            for (shift in intArrayOf(0, 8, 16, 24)) {
                val d = kotlin.math.abs(((pa[i] shr shift) and 0xFF) - ((pb[i] shr shift) and 0xFF))
                total += d
                worst = maxOf(worst, d)
            }
            if (worst > 96) far++
        }
        return (total.toDouble() / (pa.size * 4)) to (far.toDouble() / pa.size)
    }

    @Test
    fun `A saves the placement shaped on the stage and the command carries it for the host`() {
        open(MimirMarkPlacement.Default)
        press(ControllerAction.TERTIARY)
        press(ControllerAction.NAV_LEFT, times = 4)
        press(ControllerAction.PAGE_RIGHT, times = 2)
        val working = state.adjust!!.working
        press(ControllerAction.CONFIRM)

        val saved = commands.filterIsInstance<MimirButtonPickerCommand.SaveAdjusted>().single()
        assertEquals(star.id, saved.mark.id)
        assertEquals(working, MimirMarkPlacement(saved.mark.scale, saved.mark.offsetX, saved.mark.offsetY))
        assertEquals("the stage is the browse stage again: three seats, none of them a native view", 0, coins().size)
        assertEquals(3, compose.onAllNodesWithTag(MimirButtonStageCoinTag, useUnmergedTree = true).fetchSemanticsNodes().size)
        val overlayMark = saved.mark.let { companionOverlayTheme(MimirThemeId.DEFAULT, buttonMark = it).mark }
        assertEquals("and the live button's mark is that placement", CompanionButtonMark.Image(star.id, star.path!!, working), overlayMark)
    }
}
