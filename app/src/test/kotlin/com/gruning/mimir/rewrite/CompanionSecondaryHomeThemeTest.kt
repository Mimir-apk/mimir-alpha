package com.gruning.mimir.rewrite

import android.app.Application
import androidx.compose.ui.graphics.Color
import android.graphics.Bitmap
import android.graphics.Canvas
import android.app.Activity
import androidx.compose.ui.test.junit4.createEmptyComposeRule

import androidx.compose.ui.graphics.toArgb
import com.gruning.mimir.rewrite.core.model.CompanionMode
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import com.gruning.mimir.rewrite.core.ui.withAppearanceOverrides
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

/**
 * The display-4 floor must paint its Mannaz cover in the theme [CompanionState] carries NOW, not the one it carried
 * when the floor was created.
 *
 * The floor is one long-lived instance: `singleTop`, every configuration change handled, and nothing recreates it
 * when the theme changes. Its `setContent` used to read `CompanionState.payload.value.themeId` once, and a
 * `StateFlow.value` read is not snapshot state, so nothing ever recomposed that scope. Two orders reach it:
 * - **The floor is created before Main pushes a themed payload.** After a process death or an install the platform
 *   restarts the floor on display 4 in a process with no MainActivity (`MimirMainHomeRecovery`), so the payload holds
 *   only what process start seeded (`seedCompanionThemeAtProcessStart`, pinned by CompanionThemeSeedTest) until Main's
 *   composition pushes. This test starts from the unthemed `CompanionPayload.idle()`, which resolves to Default, so
 *   Main's push is the only thing that can re-theme the cover.
 * - **The theme changes while the floor lives.** Main's Appearance picker republishes the payload with the new key;
 *   the Presentation collects it, the floor did not.
 *
 * What the cover paints is read back from the window's pixels (the cover carries no text since ruling 6): its ground is the
 * theme's `surfaceBase` and its ᛗ badge is inked in `accent`, each resolved through the same
 * `MimirThemePalette.forId(id).withAppearanceOverrides()` lookup `MimirTheme` uses. This proves the JVM composition
 * re-themes; the pixels on the Thor's Bottom are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionSecondaryHomeThemeTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun setUp() {
        // onResume reports to the return coordinator; a bare runtime keeps the process-wide one uninitialized.
        CompanionReturnCoordinator.installRuntime(
            CompanionRuntimeCoordinator(CompanionRuntimeScheduler { _, _, _ -> RuntimeCancellation {} }),
        )
        CompanionState.set(CompanionPayload.idle())
    }

    @After
    fun tearDown() {
        CompanionState.set(CompanionPayload.idle())
        CompanionReturnCoordinator.resetTestOverrides()
    }

    @Test
    fun `a floor created before Main's first themed push takes the theme when it arrives`() {
        assertDistinct(MimirThemeId.DEFAULT, MimirThemeId.HEL)
        withFloor { floor ->
            assertEquals(
                "a floor created in a process Main has not pushed to yet paints the idle payload's theme",
                coverColorsOf(MimirThemeId.DEFAULT),
                floor.coverColors(MimirThemeId.DEFAULT),
            )

            CompanionState.set(CompanionPayload(mode = CompanionMode.HOME, themeId = MimirThemeId.HEL.storageKey))
            compose.waitForIdle()

            assertEquals(
                "Main's first themed payload must re-theme the floor's cover",
                coverColorsOf(MimirThemeId.HEL),
                floor.coverColors(MimirThemeId.HEL),
            )
        }
    }

    @Test
    fun `a theme change while the floor lives re-themes its cover`() {
        assertDistinct(MimirThemeId.MANI, MimirThemeId.BALDR)
        CompanionState.set(CompanionPayload(mode = CompanionMode.HOME, themeId = MimirThemeId.MANI.storageKey))
        withFloor { floor ->
            assertEquals(
                "the floor starts in the theme the payload carries when it is created",
                coverColorsOf(MimirThemeId.MANI),
                floor.coverColors(MimirThemeId.MANI),
            )

            CompanionState.set(CompanionPayload(mode = CompanionMode.HOME, themeId = MimirThemeId.BALDR.storageKey))
            compose.waitForIdle()

            assertEquals(
                "a theme picked on Main while the floor is alive must reach the floor's cover",
                coverColorsOf(MimirThemeId.BALDR),
                floor.coverColors(MimirThemeId.BALDR),
            )
        }
    }

    /**
     * Robolectric attaches every Activity to the Application's own base `ContextImpl`, a non-UI context, so the
     * floor's `display?.displayId` in onCreate and onResume throws "Tried to obtain display from a Context not
     * associated with one". On a device an Activity's context is an ACTIVITY context and that read returns its
     * display. The harness marks the shared base context that way for the floor's lifetime only, then restores it.
     */
    private fun withFloor(block: (Activity) -> Unit) {
        val base = RuntimeEnvironment.getApplication().baseContext
        val originalType = ReflectionHelpers.getField<Int>(base, "mContextType")
        ReflectionHelpers.setField(
            base,
            "mContextType",
            ReflectionHelpers.getStaticField<Int>(base.javaClass, "CONTEXT_TYPE_ACTIVITY"),
        )
        try {
            // P2-3: the floor ends itself on the default display, which is the only display Robolectric has.
            CompanionFloorDisplayGuard.refuseDefaultDisplay = false
            val controller = Robolectric.buildActivity(CompanionSecondaryHomeActivity::class.java).setup()
            try {
                compose.waitForIdle()
                block(controller.get())
            } finally {
                controller.pause().stop().destroy()
            }
        } finally {
            ReflectionHelpers.setField(base, "mContextType", originalType)
            CompanionFloorDisplayGuard.refuseDefaultDisplay = true
        }
    }

    /** The two themes must differ in what the cover paints, or a stale cover would pass. */
    private fun assertDistinct(first: MimirThemeId, second: MimirThemeId) {
        assertNotEquals(
            "fixture: $first and $second must paint the cover differently",
            coverColorsOf(first),
            coverColorsOf(second),
        )
    }

    private fun coverColorsOf(id: MimirThemeId): CoverColors =
        CoverColors(ground = MimirThemePalette.forId(id).withAppearanceOverrides().surfaceBase.toArgb(), sigilInAccent = true)

    /**
     * What the cover paints, read back from the window's own pixels (it has no text to read since ruling 6): its ground, a pixel well clear of the centred badge
     * pixel, and whether the badge's ᛗ is inked in [theme]'s accent, whose staves are 3.5 dp lines of one opaque colour.
     */
    private fun Activity.coverColors(theme: MimirThemeId): CoverColors {
        val decor = window.decorView
        check(decor.width > 0 && decor.height > 0) { "the floor's window has no size yet" }
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bitmap))
        val accent = MimirThemePalette.forId(theme).withAppearanceOverrides().accent.toArgb()
        val pixels = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
        return CoverColors(ground = bitmap.getPixel(bitmap.width / 8, bitmap.height / 2), sigilInAccent = pixels.any { it == accent })
    }

    private data class CoverColors(val ground: Int, val sigilInAccent: Boolean)
}
