package com.gruning.mimir.rewrite.feature.companion

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.model.CompanionAppEntry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CompanionRecentsQuickLaunchLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun compactTabManagerKeepsQuicklaunchVisibleContainedAndOutsideThePanel() {
        composeRule.setContent {
            Box(Modifier.requiredSize(538.dp, 444.dp).testTag(ViewportTag)) {
                CompanionRecentsDrawer(
                    entries = listOf(CompanionAppEntry("org.example.live", "Live", closable = true)),
                    launchLocked = false,
                    launchMessage = null,
                    themeId = "midnight",
                    quickSlots = listOf("org.example.pinned", null, null),
                    quickLaunchApps = listOf(CompanionAppEntry("org.example.pinned", "Pinned")),
                    onQuickLaunch = {},
                    onQuickAssign = { _, _ -> },
                    onLaunch = {},
                    onClose = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.waitForIdle()

        val viewport = bounds(ViewportTag)
        val panel = bounds(CompanionRecentsPanelTag)
        val quicklaunch = bounds(CompanionQuickLaunchTag)

        assertContained(panel, viewport)
        assertContained(quicklaunch, viewport)
        assertTrue("tab panel overlaps quicklaunch: $panel vs $quicklaunch", panel.bottom <= quicklaunch.top + 1f)
    }

    private fun bounds(tag: String): Rect =
        composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun assertContained(inner: Rect, outer: Rect) {
        assertTrue("left clipped: $inner outside $outer", inner.left >= outer.left - 1f)
        assertTrue("top clipped: $inner outside $outer", inner.top >= outer.top - 1f)
        assertTrue("right clipped: $inner outside $outer", inner.right <= outer.right + 1f)
        assertTrue("bottom clipped: $inner outside $outer", inner.bottom <= outer.bottom + 1f)
    }

    private companion object {
        const val ViewportTag = "companion-recents-test-viewport"
    }
}
