package com.gruning.mimir.rewrite.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirQuickRailAction
import com.gruning.mimir.rewrite.core.ui.MimirRunicQuickRail
import com.gruning.mimir.rewrite.feature.library.data.FakeLibraryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryShelfViewportTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun shippingViewportsAtFontScale13KeepFixedBandsAndSameFrameFocusSeat() {
        val viewModel = LibraryViewModel(FakeLibraryRepository())
        val authority = DefaultFocusAuthority()
        var fixture by mutableStateOf(ViewportFixture(DpSize(960.dp, 540.dp)))

        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = fixture.density, fontScale = 1.3f),
            ) {
                MimirTheme {
                    Box(Modifier.size(fixture.size.width, fixture.size.height)) {
                        LibraryScreen(
                            viewModel = viewModel,
                            authority = authority,
                            quickRailContent = {
                                MimirRunicQuickRail(
                                    selectedAction = MimirQuickRailAction.LIBRARY,
                                    focusedAction = MimirQuickRailAction.LIBRARY,
                                    onActionFocus = {},
                                    onActionActivate = {},
                                )
                            },
                        )
                    }
                }
            }
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            viewModel.platforms.value.size >= 2 && authority.state.value.focused != null
        }
        compose.waitForIdle()

        assertViewportGeometry(fixture)
        compose.onNodeWithText("Library", ignoreCase = true).assertExists()
        compose.onNodeWithText("Emulation", ignoreCase = true).assertExists()
        compose.onNodeWithText("Android games", ignoreCase = true).assertExists()
        compose.onNodeWithText("ANDROID APPS").assertExists()
        compose.onNodeWithText("Identifying", substring = true).assertDoesNotExist()

        val logicalSlotWidth = bounds(LibraryShelfSelectedSlotTestTag).width / fixture.density
        val target = FocusKey("library/shelf/${viewModel.platforms.value[1].platformId.value}")
        compose.runOnIdle {
            authority.requestFocus(target)
            assertEquals(target, authority.state.value.focused)
        }
        compose.waitForIdle()
        assertNear(
            logicalSlotWidth,
            bounds(LibraryShelfSelectedSlotTestTag).width / fixture.density,
        )

        listOf(
            ViewportFixture(DpSize(1920.dp, 1080.dp), density = 0.5f),
            ViewportFixture(DpSize(833.dp, 444.dp)),
            ViewportFixture(DpSize(538.dp, 444.dp)),
        ).forEach { nextFixture ->
            compose.runOnIdle { fixture = nextFixture }
            compose.waitForIdle()
            assertViewportGeometry(fixture)
            assertNear(
                logicalSlotWidth,
                bounds(LibraryShelfSelectedSlotTestTag).width / fixture.density,
            )
        }
    }

    @Test
    fun allThreeLibraryModesKeepHeaderToolsHeroAndQuickRailAnchorsFixed() {
        val viewModel = LibraryViewModel(FakeLibraryRepository())
        val authority = DefaultFocusAuthority()
        val androidGames = listOf(
            NativeShelfRow("com.example.game", "Android Game", null, isGame = true),
        )
        val androidApps = listOf(
            NativeShelfRow("com.example.app", "Android App", null, isGame = false),
        )

        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.3f)) {
                MimirTheme {
                    Box(Modifier.size(833.dp, 444.dp)) {
                        LibraryScreen(
                            viewModel = viewModel,
                            authority = authority,
                            nativeGameRows = androidGames,
                            nativeAppRows = androidApps,
                            quickRailContent = {
                                MimirRunicQuickRail(
                                    selectedAction = MimirQuickRailAction.LIBRARY,
                                    focusedAction = MimirQuickRailAction.LIBRARY,
                                    onActionFocus = {},
                                    onActionActivate = {},
                                )
                            },
                        )
                    }
                }
            }
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            viewModel.platforms.value.isNotEmpty() && authority.state.value.focused != null
        }
        compose.waitForIdle()

        val baseline = fixedBandBounds()
        // ANDROID_APPS retired 2026-08-21: ANDROID_GAMES is now the only non-EMULATION shelf mode.
        listOf(LibraryShelfMode.ANDROID_GAMES).forEach { expectedMode ->
            compose.runOnIdle { viewModel.cycleShelfMode(1) }
            compose.waitUntil(timeoutMillis = 5_000) { viewModel.shelfMode.value == expectedMode }
            compose.waitForIdle()
            fixedBandBounds().zip(baseline).forEach { (actual, expected) ->
                assertNear(expected.left, actual.left)
                assertNear(expected.top, actual.top)
                assertNear(expected.width, actual.width)
                assertNear(expected.height, actual.height)
            }
        }
    }

    private fun fixedBandBounds(): List<Rect> = listOf(
        bounds(LibraryShelfHeaderTestTag),
        bounds(LibraryShelfToolsTestTag),
        bounds(LibraryShelfHeroBandTestTag),
        bounds(LibraryShelfQuickRailHostTestTag),
    )

    private fun assertViewportGeometry(fixture: ViewportFixture) {
        val root = bounds(LibraryShelfRootTestTag)
        val header = bounds(LibraryShelfHeaderTestTag)
        val tools = bounds(LibraryShelfToolsTestTag)
        val hero = bounds(LibraryShelfHeroBandTestTag)
        val selected = bounds(LibraryShelfSelectedSlotTestTag)
        val quickRail = bounds(LibraryShelfQuickRailHostTestTag)

        assertNear(fixture.size.width.value * fixture.density, root.width)
        assertNear(fixture.size.height.value * fixture.density, root.height)
        listOf(header, tools, hero, selected, quickRail).forEach { assertTrue(root.containsBounds(it)) }
        assertFalse(header.overlaps(tools))
        assertFalse(tools.overlaps(hero))
        assertFalse(hero.overlaps(quickRail))
        assertNear(CoverflowHeroBandHeightDp * fixture.density, hero.height)
        assertNear(CoverflowMotionBookFocusWidthDp * fixture.density, selected.width)
        assertNear(ShelfQuickRailLaneHeightDp * fixture.density, quickRail.height)
        MimirQuickRailAction.entries.forEach { action ->
            val label = InstrumentationRegistry.getInstrumentation().targetContext.getString(action.labelRes)
            val actionBounds = compose.onNodeWithContentDescription(label)
                .fetchSemanticsNode()
                .boundsInRoot
            assertTrue("$label must remain inside the quick-rail lane", quickRail.containsBounds(actionBounds))
        }
    }

    private fun bounds(tag: String): Rect =
        compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun assertNear(expected: Float, actual: Float, tolerance: Float = 1.1f) {
        assertTrue("expected $expected, actual $actual", kotlin.math.abs(expected - actual) <= tolerance)
    }

    private fun Rect.containsBounds(other: Rect): Boolean =
        other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom
}

private data class ViewportFixture(
    val size: DpSize,
    val density: Float = 1f,
)
