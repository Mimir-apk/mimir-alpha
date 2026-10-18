package com.gruning.mimir.rewrite

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirMotion
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
 * A contact sheet of the return cover's exit, captured 2026-10-14: the restored card stands under the cover, the request ends,
 * and the cover leaves over the one-frame beat and [MimirMotion.OverlayMs] instead of being cut. Frames, left to right: the request
 * (cover opaque), the end of the beat, then 45, 90, 135 and 180 ms into the fade. The card here is a stand-in with the card's
 * proportions (title, art, play band), not the product's own: the product card's glass is hand-recorded `GraphicsLayer`s, which a
 * JVM software canvas cannot draw. The PNG goes to `build/ttx/` and to `build/test-captures/ttx/` when it exists. Pixels are the JVM's; the
 * handheld's are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w853dp-h480dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReturnCoverFadeRenderTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var requested by mutableStateOf(true)

    private fun shoot(): Bitmap {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    private fun step(ms: Long) {
        if (ms > 0L) compose.mainClock.advanceTimeBy(ms)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    @Test
    fun `the cover fades off the restored card in frames`() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MimirTheme {
                val palette = LocalMimirPalette.current
                Box(Modifier.fillMaxSize().background(palette.surfaceBase)) {
                    // The restored card: a frosted panel with art, a title line and a play band.
                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .width(560.dp)
                            .height(300.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF2B3A4A)),
                    ) {
                        Box(Modifier.padding(16.dp).width(150.dp).height(268.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF8C6B3F)))
                        Text("Metroid: Samus Returns", color = Color.White, modifier = Modifier.offset(190.dp, 24.dp))
                        Text("Nintendo 3DS  -  Azahar", color = Color(0xFFB8C4D0), modifier = Modifier.offset(190.dp, 56.dp))
                        Box(
                            Modifier
                                .align(Alignment.BottomEnd)
                                .padding(16.dp)
                                .width(300.dp)
                                .height(48.dp)
                                .clip(RoundedCornerShape(24.dp))
                                .background(Color(0xFFC89B3C)),
                        )
                    }
                    MimirReturnCoverSurface(requested = requested)
                }
            }
        }
        step(0L)
        val frames = mutableListOf<Bitmap>()
        frames += shoot()
        Snapshot.withMutableSnapshot { requested = false }
        step(16L)
        frames += shoot()
        for (ms in listOf(45L, 45L, 45L, 45L)) {
            step(ms)
            frames += shoot()
        }
        val tile = 6
        val w = frames.first().width / tile
        val h = frames.first().height / tile
        val sheet = Bitmap.createBitmap(w * frames.size + 8 * (frames.size - 1), h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(android.graphics.Color.rgb(20, 20, 20))
        frames.forEachIndexed { i, frame ->
            canvas.drawBitmap(Bitmap.createScaledBitmap(frame, w, h, true), (i * (w + 8)).toFloat(), 0f, null)
        }
        val out = File(File("build/ttx").apply { mkdirs() }, "return-cover-fade.png")
        out.outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val shared = File("build/test-captures/ttx")
        if (shared.isDirectory) out.copyTo(File(shared, "return-cover-fade.png"), overwrite = true)
        println("TTX wrote ${out.absolutePath} (${out.length()} bytes, ${sheet.width}x${sheet.height} px)")
        assertTrue("the sheet was written", out.isFile && out.length() > 1_000L)
    }
}
