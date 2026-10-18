package com.gruning.mimir.rewrite

import android.content.Context
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import com.gruning.mimir.rewrite.core.ui.MimirAccentStop
import com.gruning.mimir.rewrite.core.ui.MimirAppearanceOverrides
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import com.gruning.mimir.rewrite.core.ui.TrackpadPointerColor
import com.gruning.mimir.rewrite.core.ui.TrackpadPointerShape
import com.gruning.mimir.rewrite.core.ui.TrackpadPointerSize
import com.gruning.mimir.rewrite.core.ui.TrackpadPointerStyle
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The pointer's look  is stored beside the speed and natural scroll, read the same way, and reaches the pointer
 * that is on screen: the defaults for an install that never chose, the round trip, the cyclers Settings' rows press, and the
 * wiring that the host cannot show a unit test (it needs a connected accessibility service) as source pins.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MimirTrackpadPointerStyleTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences("mimir_trackpad", Context.MODE_PRIVATE)

    // MimirTrackpadSettings is process-wide: every test starts from an install that has never chosen, loaded fresh.
    @Before
    fun freshInstall() {
        prefs.edit().clear().commit()
        MimirTrackpadSettings.load(context)
    }

    @After
    fun restore() {
        prefs.edit().clear().commit()
        MimirTrackpadSettings.load(context)
    }

    @Test
    fun anInstallThatNeverChoseReadsSmallSlimArrowWhite() {
        // A stale in-memory choice from earlier in the process must not survive a load of prefs that hold none.
        MimirTrackpadSettings.setPointerStyle(
            context,
            TrackpadPointerStyle(TrackpadPointerSize.LARGE, TrackpadPointerShape.DOT, TrackpadPointerColor.ACCENT),
        )
        prefs.edit().clear().commit()
        MimirTrackpadSettings.load(context)
        assertEquals(TrackpadPointerStyle(TrackpadPointerSize.SMALL, TrackpadPointerShape.SLIM_ARROW, TrackpadPointerColor.WHITE), MimirTrackpadSettings.pointerStyle.value)
        assertEquals(TrackpadPointerStyle.Default, MimirTrackpadSettings.pointerStyle.value)
        assertFalse("nothing is stored until the player chooses", prefs.contains("pointer_size") || prefs.contains("pointer_shape") || prefs.contains("pointer_color"))
    }

    @Test
    fun theThreeChoicesPersistAndLoadBack() {
        for (size in TrackpadPointerSize.entries) {
            for (shape in TrackpadPointerShape.entries) {
                for (color in TrackpadPointerColor.entries) {
                    val style = TrackpadPointerStyle(size, shape, color)
                    MimirTrackpadSettings.setPointerStyle(context, style)
                    assertEquals(style, MimirTrackpadSettings.pointerStyle.value)
                    assertEquals(size.name, prefs.getString("pointer_size", null))
                    assertEquals(shape.name, prefs.getString("pointer_shape", null))
                    assertEquals(color.name, prefs.getString("pointer_color", null))
                    // A fresh process reads what the last one stored.
                    MimirTrackpadSettings.setPointerStyle(context, TrackpadPointerStyle.Default)
                    prefs.edit().putString("pointer_size", size.name).putString("pointer_shape", shape.name).putString("pointer_color", color.name).commit()
                    MimirTrackpadSettings.load(context)
                    assertEquals(style, MimirTrackpadSettings.pointerStyle.value)
                }
            }
        }
    }

    @Test
    fun theChoicesLiveBesideTheSpeedAndTheScrollWithoutDisturbingThem() {
        MimirTrackpadSettings.setSpeedStep(context, 5)
        MimirTrackpadSettings.setNaturalScroll(context, false)
        MimirTrackpadSettings.setPointerStyle(context, TrackpadPointerStyle(TrackpadPointerSize.MEDIUM, TrackpadPointerShape.DOT, TrackpadPointerColor.THEME_METAL))
        MimirTrackpadSettings.load(context)
        assertEquals(5, MimirTrackpadSettings.speedStep.value)
        assertFalse(MimirTrackpadSettings.naturalScroll.value)
        assertEquals(TrackpadPointerSize.MEDIUM, MimirTrackpadSettings.pointerStyle.value.size)
        MimirTrackpadSettings.setSpeedStep(context, 3)
        MimirTrackpadSettings.setNaturalScroll(context, true)
        assertEquals("a speed change leaves the look alone", TrackpadPointerShape.DOT, MimirTrackpadSettings.pointerStyle.value.shape)
    }

    @Test
    fun aNameThisBuildDoesNotKnowReadsAsTheDefaultForThatChoiceOnly() {
        prefs.edit().putString("pointer_shape", "CLASSIC_ARROW").putString("pointer_size", "LARGE").putString("pointer_color", "ACCENT").commit()
        MimirTrackpadSettings.load(context)
        assertEquals(TrackpadPointerStyle(TrackpadPointerSize.LARGE, TrackpadPointerShape.SLIM_ARROW, TrackpadPointerColor.ACCENT), MimirTrackpadSettings.pointerStyle.value)
    }

    @Test
    fun eachRowCyclesItsOwnChoiceStoresItAndLeavesTheOtherTwo() {
        assertEquals(TrackpadPointerSize.MEDIUM, MimirTrackpadSettings.cyclePointerSize(context))
        assertEquals(TrackpadPointerSize.LARGE, MimirTrackpadSettings.cyclePointerSize(context))
        assertEquals(TrackpadPointerSize.SMALL, MimirTrackpadSettings.cyclePointerSize(context))
        assertEquals(TrackpadPointerShape.DOT, MimirTrackpadSettings.cyclePointerShape(context))
        assertEquals(TrackpadPointerShape.SLIM_ARROW, MimirTrackpadSettings.cyclePointerShape(context))
        assertEquals(TrackpadPointerColor.THEME_METAL, MimirTrackpadSettings.cyclePointerColor(context))
        assertEquals(TrackpadPointerColor.ACCENT, MimirTrackpadSettings.cyclePointerColor(context))
        assertEquals(TrackpadPointerColor.WHITE, MimirTrackpadSettings.cyclePointerColor(context))
        MimirTrackpadSettings.cyclePointerSize(context)
        MimirTrackpadSettings.cyclePointerShape(context)
        assertEquals(TrackpadPointerStyle(TrackpadPointerSize.MEDIUM, TrackpadPointerShape.DOT, TrackpadPointerColor.WHITE), MimirTrackpadSettings.pointerStyle.value)
        assertEquals("MEDIUM", prefs.getString("pointer_size", null))
        assertEquals("DOT", prefs.getString("pointer_shape", null))
    }

    /**
     * `accentStop` is Compose snapshot state, and the sdk 34 LEGACY-graphics Robolectric sandbox shares one `GlobalSnapshotManager`
     * across every class that runs in it. A plain write here is a GLOBAL snapshot write: once an earlier Compose class has started
     * that manager its write observer sets `sent` and queues the apply-notifications consumer on the main looper, and nothing in this
     * test idles the looper, so the queued resume is lost when Robolectric resets it after the test. `sent` then stays true for the
     * rest of the JVM and every later LEGACY Compose class in the fork fails with AppNotIdleException (combined gate 13:
     * RetroAchievementsUsernameToKeyFocusTest, SettingsLibraryReadFailureRenderTest, SplashFirstCompositionTest,
     * ThemeChangeMotionWakeTest, TweakerRailRenderOrderTest, WelcomeHoldBackstopTest). A write inside a mutable snapshot is applied
     * at once and never reaches the global write observer.
     */
    @Test
    fun theFillIsReadFromTheThemeAndTheAccentTheAppUses() {
        try {
            Snapshot.withMutableSnapshot { MimirAppearanceOverrides.accentStop = MimirAccentStop.ROSE_GOLD }
            val accent = mimirTrackpadPointerFill(TrackpadPointerColor.ACCENT, MimirThemeId.DEFAULT)
            assertEquals(listOf(MimirAccentStop.ROSE_GOLD.accentBright.toArgb()), accent.argb)
        } finally {
            Snapshot.withMutableSnapshot { MimirAppearanceOverrides.accentStop = MimirAccentStop.CLASSIC_GOLD }
        }
    }

    // ---- wiring the host's window cannot show a unit test ------------------------------------------------------------

    private fun source(name: String): String = listOf(
        File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
    ).first { it.isFile }.readText()

    @Test
    fun aChangeReachesAPointerThatIsOnScreenThroughOneWatchOnTheChoiceTheThemeAndTheAccent() {
        val service = source("MimirTrackpadAccessibilityService.kt")
        assertTrue(service.contains("combine(MimirTrackpadSettings.pointerStyle, ThemeStore.themeId, AppearanceOverrideStore.revision)"))
        val watch = service.substringAfter("private fun watchPointerStyle() {").substringBefore("private fun ensurePointerWindow()")
        assertFalse("no emission is skipped: a choice landing just after the window mounts is applied", watch.contains("drop("))
        assertTrue("it re-applies the style", watch.contains("applyPointerStyle(view, params)"))
        assertTrue("and re-places the window, since the dot's origin depends on its size", watch.contains("placePointer(view, params, it)"))
        assertTrue("and shows the pointer again when the choice itself changed", watch.contains("if (changed) revealPointer(view)"))
        val hide = service.substringAfter("private fun hidePointer() {").substringBefore("private fun registerScreenOff()")
        assertTrue("the watch ends with the window", hide.contains("pointerStyleWatch?.cancel()"))
        val ensure = service.substringAfter("private fun ensurePointerWindow(): Boolean {").substringBefore("private fun hidePointer()")
        assertTrue("a pointer is styled before it is added, so its first frame is right", ensure.indexOf("applyPointerStyle(view, params)") in 0 until ensure.indexOf("wm.addView(view, params)"))
        assertTrue("and the watch starts once it is mounted", ensure.contains("watchPointerStyle()"))
        assertFalse("the fixed 26 dp is gone", service.contains("PointerDp"))
    }

    @Test
    fun theWindowIsPlacedByTheShapeOnScreen() {
        val service = source("MimirTrackpadAccessibilityService.kt")
        val place = service.substringAfter("private fun placePointer(").substringBefore("private fun revealPointer(")
        assertTrue("the window type decides whether the dot may sit on the click", place.contains("mimirTrackpadPointerOrigin(pointerShape, at.x, at.y, pointerWindowPx, params.type)"))
        val show = service.substringAfter("private fun showPointer() {").substringBefore("/** The window's top-left")
        assertTrue(show.contains("placePointer(view, params, at)"))
    }

    @Test
    fun theViewOnlyPaintsAndTheSettingsRowsAndTheReadModelCarryTheChoice() {
        val pointer = source("MimirTrackpadPointer.kt")
        val onDraw = pointer.substringAfter("override fun onDraw(canvas: Canvas) {").substringBefore("}")
        assertEquals("painter.draw(canvas)", onDraw.trim())
        val coordinator = source("MimirSettingsCoordinator.kt")
        assertTrue(coordinator.contains("canonicalRowId == TrackpadPointerSizeRowId -> MimirTrackpadSettings.cyclePointerSize(context)"))
        assertTrue(coordinator.contains("canonicalRowId == TrackpadPointerShapeRowId -> MimirTrackpadSettings.cyclePointerShape(context)"))
        assertTrue(coordinator.contains("canonicalRowId == TrackpadPointerColorRowId -> MimirTrackpadSettings.cyclePointerColor(context)"))
        val read = source("MimirSettingsReadCoordinator.kt")
        assertTrue(read.contains("pointer = trackpadPointer"))
        assertTrue("the read model is rebuilt when the look changes", read.contains("naturalScroll, trackpadSpeed, trackpadPointer"))
    }
}
