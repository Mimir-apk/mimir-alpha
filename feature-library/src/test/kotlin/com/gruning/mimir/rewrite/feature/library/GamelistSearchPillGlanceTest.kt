package com.gruning.mimir.rewrite.feature.library

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onParent
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.ui.LocalMimirGlanceClearance
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.MimirGlanceClearance
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.mimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.theme.MimirSpacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Sweep 10-09 (F4): the song-title chip beside the status pill was drawn over the gamelist's Search pill whenever a track began. The real [GamelistHeader] at Main's 832.8 x 468.3 dp
 * and 369 dpi registers the pill's window bounds with the glance row ([MimirGlanceClearance]), so the chip keeps off it; the chip's own side is in the app module's
 * `MusicTitleChipKeepClearTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w833dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GamelistSearchPillGlanceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val authority = DefaultFocusAuthority()
    private val rootDensity = 369f / 160f
    private var showSearch by mutableStateOf(true)

    private fun mount(clearance: MimirGlanceClearance) {
        compose.runOnUiThread { compose.activity.actionBar?.hide() }
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(rootDensity, 1f),
                LocalMimirViewportProfile provides mimirViewportProfile(832.8f, 468.3f),
                LocalMimirGlanceClearance provides clearance,
            ) {
                MimirTheme {
                    Box(Modifier.requiredSize(832.8.dp, 468.3.dp).background(LocalMimirPalette.current.surfaceBase)) {
                        Column(Modifier.fillMaxSize().padding(horizontal = LibraryGamelistBodyPaddingDp, vertical = MimirSpacing.md)) {
                            GamelistHeader(
                                title = "Super Nintendo",
                                metaLabel = "3 games",
                                showSearch = showSearch,
                                searchQuery = "",
                                searchPlaceholder = "Search games",
                                authority = authority,
                                searchControllerEnabled = true,
                                searchFocused = false,
                                onOpenSearch = {},
                                onClearSearch = {},
                                segments = listOf(FavoriteSegment.GAMES),
                                segmentCounts = emptyMap(),
                                activeSegment = FavoriteSegment.GAMES,
                                onSelectSegment = {},
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun pill() = compose.onNode(hasContentDescription("Search games"), useUnmergedTree = true)

    @Test
    fun `the pill keeps the song-title chip off it - its right edge is registered with the glance row, and withdrawn with it`() {
        val clearance = MimirGlanceClearance()
        mount(clearance)
        val right = pill().onParent().getUnclippedBoundsInRoot().right.value * rootDensity
        val top = pill().onParent().getUnclippedBoundsInRoot().top.value * rootDensity
        val registered = clearance.clearRightPx(top, top + 10f)
        println("GLANCE pill right $right px, registered $registered px")
        assertEquals("the registered edge is the pill's", right, registered, 2f)
        assertTrue("the pill stands below the screen's top edge", top > 2f)
        assertEquals("a band above the pill has nothing to keep clear of", 0f, clearance.clearRightPx(0f, top - 1f), 0.01f)

        compose.runOnUiThread { showSearch = false }
        compose.waitForIdle()
        assertEquals("with the pill gone the row has nothing to keep clear of", 0f, clearance.clearRightPx(0f, 100_000f), 0.01f)
        assertNull(runCatching { pill().fetchSemanticsNode() }.getOrNull())
    }

}
