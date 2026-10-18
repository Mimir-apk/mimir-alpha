package com.gruning.mimir.rewrite.feature.companion

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import com.gruning.mimir.rewrite.core.model.CompanionAppEntry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * R1 : over a dual-screen game the Tabs drawer is an overlay window above the emulator's own, and the emulator's
 * screen must not show through its frost. The drawer lays the theme's opaque base under its scrim only when asked; everywhere else it
 * stays the glass it was. The picture over a game is rendered by the app module's `BottomUtilityWindowRenderTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionRecentsOpaqueBackdropTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun mount(opaqueBackdrop: Boolean) {
        compose.setContent {
            CompanionRecentsDrawer(
                entries = listOf(CompanionAppEntry("org.example.app", "Example")),
                launchLocked = false,
                launchMessage = null,
                themeId = null,
                quickSlots = listOf(null, null, null, null, null),
                quickLaunchApps = emptyList(),
                onQuickLaunch = {},
                onQuickAssign = { _, _ -> },
                onLaunch = {},
                onClose = {},
                onDismiss = {},
                opaqueBackdrop = opaqueBackdrop,
            )
        }
        compose.waitForIdle()
    }

    @Test
    fun `the opaque base is laid under the scrim only when the drawer is asked for it`() {
        mount(opaqueBackdrop = true)
        assertEquals(1, compose.onAllNodesWithTag(CompanionRecentsOpaqueBackdropTag).fetchSemanticsNodes().size)
    }

    @Test
    fun `by default the drawer keeps its glass and lays nothing opaque under it`() {
        mount(opaqueBackdrop = false)
        assertEquals(0, compose.onAllNodesWithTag(CompanionRecentsOpaqueBackdropTag).fetchSemanticsNodes().size)
    }
}
