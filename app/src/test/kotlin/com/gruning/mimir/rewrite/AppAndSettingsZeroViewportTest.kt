package com.gruning.mimir.rewrite

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirRingStop
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.feature.settings.ControllerMappingOverlay
import com.gruning.mimir.rewrite.feature.settings.ControllerMappingRow
import com.gruning.mimir.rewrite.feature.settings.ControllerMappingViewportTestTag
import com.gruning.mimir.rewrite.feature.settings.MimirColorDialOverlay
import com.gruning.mimir.rewrite.feature.settings.MimirColorDialRowTestTag
import com.gruning.mimir.rewrite.core.ui.select.MimirSelectItem
import com.gruning.mimir.rewrite.core.ui.select.MimirSelectOptionsTestTag
import com.gruning.mimir.rewrite.core.ui.select.MimirSelectOverlay
import com.gruning.mimir.rewrite.feature.settings.SettingsHome
import com.gruning.mimir.rewrite.feature.settings.SettingsHomeRailTestTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every app/settings zero-viewport boundary gets one real-composable replay: valid A, exact (0, 0),
 * then a distinct valid B. The tagged node is deliberately a descendant of the decision seat so these
 * assertions never measure the host or the node carrying [mimirMeasuredViewport] itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppAndSettingsZeroViewportTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `app shell holds header through zero and settles B`() {
        assertBoundary(
            initial = Viewport(900f, 600f),
            final = Viewport(500f, 500f),
            tag = AppShellHeaderTestTag,
            chrome = hasText("Settings", substring = true) or hasContentDescription("Settings", substring = true),
        ) {
            AppShellScaffold(activeTab = ShellTab.SETTINGS, onSelectTab = {}) {
                androidx.compose.material3.Text("Fixture shell")
            }
        }
    }

    @Test
    fun `quick launch holds slots through zero and settles B`() {
        val authority = DefaultFocusAuthority()
        val slots = listOf(
            HomeQuickLaunchSlotUi(index = 0, packageName = "fixture.app", label = "Fixture"),
            HomeQuickLaunchSlotUi(index = 1, packageName = null, label = null),
            HomeQuickLaunchSlotUi(index = 2, packageName = null, label = null),
            HomeQuickLaunchSlotUi(index = 3, packageName = null, label = "All apps", general = true),
        )
        assertBoundary(
            initial = Viewport(900f, 700f),
            final = Viewport(420f, 360f),
            tag = HomeQuickLaunchSlotsTestTag,
            chrome = hasText(HomeQuickLaunchHeader, substring = true) or
                hasContentDescription(HomeQuickLaunchHeader, substring = true),
        ) {
            HomeQuickLaunchOverlay(
                authority = authority,
                slots = slots,
                onLaunch = {},
                onOpenGeneral = {},
                onEdit = {},
                onDismiss = {},
            )
        }
    }

    @Test
    fun `custom enrich holds panel through zero and settles B`() {
        val authority = DefaultFocusAuthority()
        val state = HomeCustomEnrichState(
            sessionId = "zero-viewport-test",
            kind = EnrichMediaKind.ICON,
            cacheKey = "fixture",
            gameTitle = "Fixture",
            queryDraft = "Fixture",
        )
        assertBoundary(
            initial = Viewport(900f, 600f),
            final = Viewport(500f, 500f),
            tag = HomeCustomEnrichPanelTestTag,
            chrome = hasText("Icon art", substring = true) or hasContentDescription("Icon art", substring = true),
        ) {
            HomeCustomEnrichPickerOverlay(
                authority = authority,
                state = state,
                onAction = {},
                onQueryChange = {},
                onKeyboardDone = {},
            )
        }
    }

    @Test
    fun `tile menu holds panel through zero and settles B`() {
        assertBoundary(
            initial = Viewport(900f, 600f),
            final = Viewport(420f, 360f),
            tag = HomeTileMenuPanelTestTag,
            chrome = hasText("Fixture tile", substring = true) or
                hasContentDescription("Fixture tile", substring = true),
        ) {
            TileArtMenuOverlay(
                title = "Fixture tile",
                options = listOf("Play", "Customize"),
                menuFocus = 0,
                phase = HomeTileMenuPhase.Menu,
                onMenuFocus = {},
                onActivateMenu = {},
                onPickFocus = {},
                onActivatePick = {},
            )
        }
    }

    @Test
    fun `settings home holds rail through zero and settles B`() {
        val authority = DefaultFocusAuthority()
        assertBoundary(
            initial = Viewport(900f, 600f),
            final = Viewport(640f, 480f),
            tag = SettingsHomeRailTestTag,
            chrome = hasText("Profile", substring = true) or hasContentDescription("Profile", substring = true),
        ) {
            SettingsHome(authority = authority)
        }
    }

    @Test
    fun `controller mapping holds viewport through zero and settles B`() {
        val authority = DefaultFocusAuthority()
        val rows = listOf(
            ControllerMappingRow(
                id = "fixture-action",
                actionName = "fixture-action",
                title = "Fixture action",
                binding = "A",
            ),
        )
        assertBoundary(
            initial = Viewport(900f, 600f),
            final = Viewport(420f, 360f),
            tag = ControllerMappingViewportTestTag,
            chrome = hasText("CONTROLLER MAPPING", substring = true) or
                hasContentDescription("CONTROLLER MAPPING", substring = true),
        ) {
            ControllerMappingOverlay(
                authority = authority,
                rows = rows,
                status = "Ready",
                pendingActionName = null,
                exitConfirmationVisible = false,
                onBeginCapture = {},
                onCancelCapture = {},
                onReset = {},
                onApply = {},
                onDiscard = {},
                onContinueEditing = {},
                onDismiss = {},
            )
        }
    }

    @Test
    fun `color dial holds inner row through zero and settles B`() {
        assertBoundary(
            initial = Viewport(900f, 600f),
            final = Viewport(420f, 360f),
            tag = MimirColorDialRowTestTag,
            chrome = hasText("Focus highlight", substring = true) or
                hasContentDescription("Focus highlight", substring = true),
        ) {
            MimirColorDialOverlay(
                focusedStop = MimirRingStop.THEME_DEFAULT,
                authoredPalette = LocalMimirPalette.current,
                onStepTo = {},
                onCommit = {},
                onDismiss = {},
                presenceManagedExternally = true,
            )
        }
    }

    @Test
    fun `select overlay holds inner options through zero and settles B`() {
        val authority = DefaultFocusAuthority()
        val scope = FocusScope("zero-viewport-test/select")
        assertBoundary(
            initial = Viewport(900f, 600f),
            final = Viewport(420f, 360f),
            tag = MimirSelectOptionsTestTag,
            chrome = hasText("Fixture choice", substring = true) or
                hasContentDescription("Fixture choice", substring = true),
        ) {
            MimirSelectOverlay(
                authority = authority,
                scope = scope,
                title = "Fixture picker",
                items = listOf(
                    MimirSelectItem.Option(
                        key = FocusKey("zero-viewport-test/select/fixture"),
                        label = "Fixture choice",
                        current = true,
                    ),
                ),
                onSelect = {},
                onDismiss = {},
                presenceManagedExternally = true,
            )
        }
    }

    private fun assertBoundary(
        initial: Viewport,
        final: Viewport,
        tag: String,
        chrome: SemanticsMatcher,
        content: @Composable () -> Unit,
    ) {
        val viewport = mutableStateOf(initial)
        install(viewport, content)
        assertChrome(chrome)
        val a = bounds(tag)
        assertTrue("A descendant geometry must be positive", a.width > 0f && a.height > 0f)

        compose.runOnIdle { viewport.value = Zero }
        compose.waitForIdle()
        assertChrome(chrome)
        assertBounds("held zero", a, bounds(tag))

        compose.runOnIdle { viewport.value = final }
        compose.waitForIdle()
        assertChrome(chrome)
        val b = bounds(tag)
        assertTrue("B descendant geometry must remain positive", b.width > 0f && b.height > 0f)
        assertTrue("B must settle a distinct descendant geometry", b != a)
    }

    private fun install(viewport: MutableState<Viewport>, content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                MimirTheme {
                    FixedViewport(viewport.value, content)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun assertChrome(chrome: SemanticsMatcher) {
        assertTrue(
            "distinctive chrome must remain present",
            compose.onAllNodes(chrome, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(),
        )
    }

    private fun bounds(tag: String): MeasuredBounds {
        val rect = compose.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()
        return MeasuredBounds(width = (rect.right - rect.left).value, height = (rect.bottom - rect.top).value)
    }

    private fun assertBounds(label: String, expected: MeasuredBounds, actual: MeasuredBounds) {
        assertEquals("$label width", expected.width, actual.width, BoundToleranceDp)
        assertEquals("$label height", expected.height, actual.height, BoundToleranceDp)
    }

    /** Forces the child to receive exact viewport constraints, including [Constraints.fixed] (0, 0). */
    @Composable
    private fun FixedViewport(viewport: Viewport, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        val widthPx = with(density) { viewport.widthDp.dp.roundToPx() }
        val heightPx = with(density) { viewport.heightDp.dp.roundToPx() }
        Layout(
            content = {
                Box(
                    propagateMinConstraints = true,
                    content = { content() },
                )
            },
        ) { measurables, constraints ->
            val placeable = measurables.single().measure(Constraints.fixed(widthPx, heightPx))
            val layoutWidth = widthPx.coerceIn(constraints.minWidth, constraints.maxWidth)
            val layoutHeight = heightPx.coerceIn(constraints.minHeight, constraints.maxHeight)
            layout(layoutWidth, layoutHeight) {
                placeable.placeRelative(0, 0)
            }
        }
    }

    private data class Viewport(val widthDp: Float, val heightDp: Float)

    private data class MeasuredBounds(val width: Float, val height: Float)

    private companion object {
        val Zero = Viewport(0f, 0f)
        const val BoundToleranceDp = 0.51f
    }
}
