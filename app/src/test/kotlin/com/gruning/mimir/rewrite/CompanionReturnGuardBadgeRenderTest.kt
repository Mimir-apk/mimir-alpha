package com.gruning.mimir.rewrite

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.viewinterop.AndroidView
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import com.gruning.mimir.rewrite.feature.companion.CompanionMannazCover
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * when Mimir takes the Bottom back from a covering app, the restore guard (`Mimir.ReturnGuard`,
 * [CompanionReturnActivity]) showed a wordmark and lines that cut to the Mannaz badge of the Presentation's handoff cover ([CompanionMannazCover])
 * on the same ground. The guard now shows the SAME cover, so the handoff is one image with nothing to cut.
 *
 * Proof: the guard's real content view ([companionReturnGuardView]) and the Presentation's composition of its cover, each drawn into a
 * 1240x1080 px frame (the Thor Bottom, 537.7x468.3 dp at 369 dpi), are pixel-identical in every theme, with the 56 dp badge dead-centre on the
 * guard's ground token. The first theme's pair is written side by side to `build/renders/guard-badge-render.png`. The two WINDOWS' bounds on the
 * device (an Activity fitting the system-window insets against a Presentation spanning the display) are device-owed; the guard lays out edge-to-edge
 * for them (`enterRestoreGuardMode`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionReturnGuardBadgeRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private enum class Side { GUARD, COVER }

    private val density369 = 369f / 160f
    /** The Thor Bottom's 537.7 x 468.3 dp at 369 dpi, as Robolectric's `w538dp-h468dp` window gives it: 1240 x 1079 px, read from the host at the shot. */
    private var widthPx = 0
    private var heightPx = 0
    private val badgePx = (56f * density369).toInt()
    private var side by mutableStateOf(Side.GUARD)
    private var themeKey by mutableStateOf(MimirThemeId.DEFAULT.storageKey)
    private var rootView: View? = null

    @Test
    fun `the guard's frame is the Presentation's handoff cover, pixel for pixel, badge dead-centre, in every theme`() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density369, 1f)) {
                rootView = LocalView.current.rootView
                Box(Modifier.fillMaxSize()) {
                    when (side) {
                        // The guard's content view, exactly as CompanionReturnActivity builds it in restore-guard mode.
                        Side.GUARD -> key(themeKey) {
                            AndroidView(
                                factory = { context -> companionReturnGuardView(context, themeKey, companionReturnGuardGroundArgb(themeKey)) },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        // The Presentation's composition of its reclaim handoff cover (MimirCompanionPresentation, `reclaimReveals`).
                        Side.COVER -> MimirTheme(themeId = MimirThemeId.fromStorageKey(themeKey)) { CompanionMannazCover() }
                    }
                }
            }
        }
        var rendered: Pair<IntArray, IntArray>? = null
        MimirThemeId.entries.forEach { theme ->
            set(theme.storageKey, Side.GUARD)
            val guard = shoot()
            set(theme.storageKey, Side.COVER)
            val cover = shoot()
            val ground = companionReturnGuardGroundArgb(theme.storageKey)
            // Something was composed and drawn on each side: two blank frames would also be identical.
            val guardInk = guard.count { it != ground }
            val coverInk = cover.count { it != ground }
            assertTrue("$theme: the guard wears the badge (non-ground px $guardInk)", guardInk > badgePx * badgePx / 2)
            assertTrue("$theme: the cover wears the badge (non-ground px $coverInk)", coverInk > badgePx * badgePx / 2)
            assertEquals("$theme: the corner is the guard's ground token", ground, guard[0])
            assertEquals("$theme: the cover's ground is the same token", ground, cover[0])
            val box = inkBox(guard, ground)
            assertTrue("$theme: the badge box ${box.toList()} is about $badgePx px square", abs(box[2] - box[0] + 1 - badgePx) <= 2 && abs(box[3] - box[1] + 1 - badgePx) <= 2)
            assertTrue(
                "$theme: the badge is dead-centre (box ${box.toList()})",
                abs((box[0] + box[2]) / 2f - (widthPx - 1) / 2f) <= 1.5f && abs((box[1] + box[3]) / 2f - (heightPx - 1) / 2f) <= 1.5f,
            )
            val differing = guard.indices.count { guard[it] != cover[it] }
            assertEquals("$theme: the guard's frame and the cover's differ in $differing px", 0, differing)
            if (rendered == null) rendered = guard to cover
        }
        writeSideBySide(checkNotNull(rendered))
    }

    /** The guard composes the cover under the theme resolution the Presentation uses, and nothing else. */
    @Test
    fun `the guard composes the Presentation's cover, resolved as the Presentation resolves it, and no wordmark`() {
        val guard = File("src/main/kotlin/com/gruning/mimir/rewrite/CompanionReturnActivity.kt").readText()
        val presentation = File("src/main/kotlin/com/gruning/mimir/rewrite/MimirCompanionPresentation.kt").readText()
        assertTrue(guard.contains("setContent { MimirTheme(themeId = MimirThemeId.fromStorageKey(themeStorageKey)) { CompanionMannazCover() } }"))
        assertTrue(guard.contains("val guard = companionReturnGuardView(this, CompanionState.payload.value.themeId, ground)"))
        assertTrue(presentation.contains("MimirTheme(themeId = MimirThemeId.fromStorageKey(payload.themeId)) {\n                            CompanionMannazCover("))
        val restoreMarker = "if (isRestoreGuard()) {"
        val restoreStart = guard.indexOf(restoreMarker)
        assertTrue("restore-guard branch must exist", restoreStart >= 0)
        val restoreEnd = guard.indexOf("} else {", startIndex = restoreStart)
        assertTrue("host-mode branch must follow restore guard", restoreEnd > restoreStart)
        val restoreBranch = guard.substring(restoreStart + restoreMarker.length, restoreEnd)
        assertFalse("the wordmark view is host mode's only", restoreBranch.contains("buildHostView("))
        assertTrue("laid out edge-to-edge, for the Presentation's bounds", guard.contains("WindowCompat.setDecorFitsSystemWindows(this, false)"))
    }

    private fun set(key: String, which: Side) {
        Snapshot.withMutableSnapshot {
            themeKey = key
            side = which
        }
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
        ShadowLooper.idleMainLooper()
        compose.waitForIdle()
    }

    private fun shoot(): IntArray {
        val view = checkNotNull(rootView)
        widthPx = view.width
        heightPx = view.height
        assertTrue("the host is the Thor Bottom's size, ${view.width}x${view.height}", abs(view.width - 1240) <= 1 && abs(view.height - 1080) <= 1)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels
    }

    /** Bounding box (minX, minY, maxX, maxY) of every pixel that is not [ground]. */
    private fun inkBox(pixels: IntArray, ground: Int): IntArray {
        val box = intArrayOf(Int.MAX_VALUE, Int.MAX_VALUE, -1, -1)
        for (index in pixels.indices) {
            if (pixels[index] == ground) continue
            val x = index % widthPx
            val y = index / widthPx
            if (x < box[0]) box[0] = x
            if (y < box[1]) box[1] = y
            if (x > box[2]) box[2] = x
            if (y > box[3]) box[3] = y
        }
        return box
    }

    private fun writeSideBySide(frames: Pair<IntArray, IntArray>) {
        val divider = 8
        val sheet = Bitmap.createBitmap(widthPx * 2 + divider, heightPx, Bitmap.Config.ARGB_8888)
        sheet.eraseColor(Color.WHITE)
        sheet.setPixels(frames.first, 0, widthPx, 0, 0, widthPx, heightPx)
        sheet.setPixels(frames.second, 0, widthPx, widthPx + divider, 0, widthPx, heightPx)
        val dir = File("build/renders").apply { mkdirs() }
        File(dir, "guard-badge-render.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
