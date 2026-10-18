package com.gruning.mimir.rewrite

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCard
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardMeasuredViewportTestTag
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardUiState
import com.gruning.mimir.rewrite.feature.library.HomeShelfRow
import com.gruning.mimir.rewrite.feature.library.HomeShelfScreen
import com.gruning.mimir.rewrite.feature.library.HomeShelfMeasuredViewportTestTag
import com.gruning.mimir.rewrite.feature.library.HomeTomeFolio
import com.gruning.mimir.rewrite.feature.library.HomeTomeFolioMode
import com.gruning.mimir.rewrite.feature.library.HomeTomeFolioPanelViewportTestTag
import com.gruning.mimir.rewrite.feature.library.HomeTomeFolioScope
import com.gruning.mimir.rewrite.feature.library.HomeTomeFolioUiState
import com.gruning.mimir.rewrite.feature.library.HomeTomeMember
import com.gruning.mimir.rewrite.feature.library.HomeShelfTome
import com.gruning.mimir.rewrite.feature.library.LibraryScreen
import com.gruning.mimir.rewrite.feature.library.LibraryShelfRootTestTag
import com.gruning.mimir.rewrite.feature.library.LibraryViewModel
import com.gruning.mimir.rewrite.feature.library.TomeId
import com.gruning.mimir.rewrite.feature.library.data.FakeLibraryRepository
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Host-level proof for the four feature-library viewport boundaries. The custom [FixedViewport] sends
 * exact `Constraints.fixed` measurements to each boundary, including the transient zero that Android
 * can produce while a surface is being reparented. Bounds are always read from the tagged child inside
 * the boundary; the host and either BoxWithConstraints node are deliberately never measured.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeatureLibraryZeroViewportTest {

    private val expectedFatal = ExpectedFatalRule()
    val compose = createComposeRule()

    // The compose rule records a composition/layout exception and rethrows it at teardown even after the test body
    // caught it, so a strict-solver throw must be expected OUTSIDE the compose rule.
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(expectedFatal).around(compose)

    @Test
    fun `HomeLaunchCard holds A through zero and uses B on the first B frame`() {
        val harness = launchCardHarness(ViewportA)
        assertSurface(LAUNCH_SURFACE)
        // The card's own BoxWithConstraints is narrower than the host, so A's size is measured, not assumed.
        val sizeA = measured(HomeLaunchCardMeasuredViewportTestTag)

        harness.resize(Zero)
        assertSurface(LAUNCH_SURFACE)
        assertBounds(HomeLaunchCardMeasuredViewportTestTag, sizeA)

        harness.resize(ViewportB)
        assertSurface(LAUNCH_SURFACE)
        val sizeB = measured(HomeLaunchCardMeasuredViewportTestTag)
        assertTrue(
            "B must be used at once, not the held A ($sizeA -> $sizeB)",
            kotlin.math.abs(sizeB.widthDp - sizeA.widthDp) > 1f || kotlin.math.abs(sizeB.heightDp - sizeA.heightDp) > 1f,
        )
        assertTrue("B's measured box must fit B", sizeB.widthDp <= ViewportB.widthDp + 1f && sizeB.heightDp <= ViewportB.heightDp + 1f)
    }

    @Test
    fun `LibraryScreen holds A through zero and uses B on the first B frame`() {
        val harness = libraryHarness(ViewportA)
        assertSurface(LIBRARY_SURFACE)
        assertBounds(LibraryShelfRootTestTag, ViewportA)

        harness.resize(Zero)
        assertSurface(LIBRARY_SURFACE)
        assertBounds(LibraryShelfRootTestTag, ViewportA)

        harness.resize(ViewportB)
        assertSurface(LIBRARY_SURFACE)
        assertBounds(LibraryShelfRootTestTag, ViewportB)
    }

    @Test
    fun `HomeShelfScreen holds A through zero and uses B on the first B frame`() {
        val harness = homeShelfHarness(ViewportA)
        assertSurface(HOME_SURFACE)
        assertBounds(HomeShelfMeasuredViewportTestTag, ViewportA)

        harness.resize(Zero)
        assertSurface(HOME_SURFACE)
        assertBounds(HomeShelfMeasuredViewportTestTag, ViewportA)

        harness.resize(ViewportB)
        assertSurface(HOME_SURFACE)
        assertBounds(HomeShelfMeasuredViewportTestTag, ViewportB)
    }

    @Test
    fun `HomeTomeFolio holds its panel viewport A through zero and uses B on the first B frame`() {
        val harness = tomeHarness(ViewportA)
        assertSurface(TOME_SURFACE)
        assertBounds(HomeTomeFolioPanelViewportTestTag, ViewportA.panel)

        harness.resize(Zero)
        assertSurface(TOME_SURFACE)
        assertBounds(HomeTomeFolioPanelViewportTestTag, ViewportA.panel)

        harness.resize(ViewportB)
        assertSurface(TOME_SURFACE)
        assertBounds(HomeTomeFolioPanelViewportTestTag, ViewportB.panel)
    }

    @Test
    fun `HomeShelfScreen zero before first valid measurement is fatal`() {
        assertThrows(IllegalArgumentException::class.java) {
            homeShelfHarness(Zero)
        }
    }

    @Test
    fun `HomeShelfScreen rejects a 319 by 300 viewport after a valid frame`() {
        val harness = homeShelfHarness(ViewportA)
        expectedFatal.expect(IllegalArgumentException::class.java, HomeShelfFloorMessage)
        harness.resize(Viewport(319f, 300f))
    }

    @Test
    fun `HomeShelfScreen rejects a 320 by 299 viewport after a valid frame`() {
        val harness = homeShelfHarness(ViewportA)
        expectedFatal.expect(IllegalArgumentException::class.java, HomeShelfFloorMessage)
        harness.resize(Viewport(320f, 299f))
    }

    private fun launchCardHarness(initial: Viewport): Harness {
        val viewport = mutableStateOf(initial)
        val authority = DefaultFocusAuthority()
        val row = HomeShelfRow(
            itemId = HomeShelfItemId.Emulated(GameId("viewport-launch")),
            rank = 0L,
            addedAtMillis = 0L,
            title = "Viewport launch",
            supportingLabel = "snes",
        )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                MimirTheme {
                    FixedViewport(viewport.value) {
                        HomeLaunchCard(
                            state = HomeLaunchCardUiState(
                                row = row,
                                platformLabel = LAUNCH_SURFACE_TEXT,
                            ),
                            onPlay = {},
                            onAchievements = {},
                            onOpenWith = {},
                            onClose = {},
                            authority = authority,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        return Harness(viewport, compose)
    }

    private fun libraryHarness(initial: Viewport): Harness {
        val viewport = mutableStateOf(initial)
        val viewModel = LibraryViewModel(FakeLibraryRepository())
        val authority = DefaultFocusAuthority()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                MimirTheme {
                    FixedViewport(viewport.value) {
                        LibraryScreen(
                            viewModel = viewModel,
                            authority = authority,
                        )
                    }
                }
            }
        }
        compose.waitUntil(timeoutMillis = 5_000) { viewModel.platforms.value.isNotEmpty() }
        compose.waitForIdle()
        return Harness(viewport, compose)
    }

    private fun homeShelfHarness(initial: Viewport): Harness {
        val viewport = mutableStateOf(initial)
        val authority = DefaultFocusAuthority()
        val row = HomeShelfRow(
            itemId = HomeShelfItemId.Emulated(GameId("viewport-shelf")),
            rank = 0L,
            addedAtMillis = 0L,
            title = HOME_SURFACE_TEXT,
        )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                MimirTheme {
                    FixedViewport(viewport.value) {
                        HomeShelfScreen(
                            rows = listOf(row),
                            authority = authority,
                            onLaunch = {},
                            onOpenLibrary = {},
                            onQuickAction = {},
                            surfaceLabel = HOME_SURFACE_TEXT,
                            reducedMotion = true,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        return Harness(viewport, compose)
    }

    private fun tomeHarness(initial: Viewport): Harness {
        val viewport = mutableStateOf(initial)
        val authority = DefaultFocusAuthority().apply { pushScope(HomeTomeFolioScope) }
        val member = HomeTomeMember(
            itemId = HomeShelfItemId.Emulated(GameId("viewport-tome-member")),
            title = "Viewport tome member",
        )
        val tome = HomeShelfTome(
            tomeId = TomeId.Custom(UUID.fromString("00000000-0000-0000-0000-0000000000a1")),
            title = TOME_SURFACE_TEXT,
            previews = emptyList(),
            memberCount = 1,
        )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                MimirTheme {
                    FixedViewport(viewport.value) {
                        HomeTomeFolio(
                            state = HomeTomeFolioUiState(
                                tome = tome,
                                members = listOf(member),
                                mode = HomeTomeFolioMode.BROWSE,
                                focusSettled = true,
                            ),
                            authority = authority,
                            onFocusMember = {},
                            onOpenMember = {},
                            onArrangeAction = {},
                            onFirstInteraction = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        return Harness(viewport, compose)
    }

    private fun assertSurface(matcher: androidx.compose.ui.test.SemanticsMatcher) {
        assertTrue(
            "the surface's distinctive element must be present",
            compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(),
        )
    }

    private fun assertBounds(tag: String, expected: Size) {
        val bounds = compose
            .onNodeWithTag(tag, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertEquals("$tag width", expected.widthDp, (bounds.right - bounds.left).value, 1f)
        assertEquals("$tag height", expected.heightDp, (bounds.bottom - bounds.top).value, 1f)
    }

    private fun measured(tag: String): Size {
        val bounds = compose.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()
        return Size((bounds.right - bounds.left).value, (bounds.bottom - bounds.top).value)
    }

    /** Passes only if an armed exception type with the given message reaches it from the body or the teardown. */
    private class ExpectedFatalRule : TestRule {
        private var type: Class<out Throwable>? = null
        private var fragment: String = ""

        fun expect(type: Class<out Throwable>, messageFragment: String) {
            this.type = type
            fragment = messageFragment
        }

        override fun apply(base: Statement, description: Description): Statement = object : Statement() {
            override fun evaluate() {
                val armed = { type }
                try {
                    base.evaluate()
                } catch (thrown: Throwable) {
                    val expected = armed() ?: throw thrown
                    if (generateSequence(thrown) { it.cause }.any { expected.isInstance(it) && it.message.orEmpty().contains(fragment) }) return
                    throw thrown
                }
                armed()?.let { throw AssertionError("expected ${it.simpleName} containing \"$fragment\", but nothing was thrown") }
            }
        }
    }

    private fun assertBounds(tag: String, expected: Viewport) {
        assertBounds(tag, Size(expected.widthDp, expected.heightDp))
    }

    private class Harness(
        private val viewport: MutableState<Viewport>,
        private val compose: ComposeContentTestRule,
    ) {
        fun resize(next: Viewport) {
            compose.runOnIdle { viewport.value = next }
            compose.waitForIdle()
        }
    }

    private data class Size(val widthDp: Float, val heightDp: Float)

    private data class Viewport(
        val widthDp: Float,
        val heightDp: Float,
    ) {
        val panel: Size get() = Size(widthDp * 0.9f, heightDp * 0.9f)
    }

    /** Forces the child to receive exact constraints, including `Constraints.fixed(0, 0)`. */
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

    private companion object {
        const val LAUNCH_SURFACE_TEXT = "Viewport Launch"
        const val HOME_SURFACE_TEXT = "Viewport Shelf"
        const val TOME_SURFACE_TEXT = "Viewport Tome"
        val LAUNCH_SURFACE = hasText(LAUNCH_SURFACE_TEXT, substring = true) or
            hasContentDescription(LAUNCH_SURFACE_TEXT, substring = true)
        // The Library title is the string resource "Library", drawn in caps by the chrome text; match either form.
        val LIBRARY_SURFACE = hasText("Library", substring = true, ignoreCase = true) or
            hasContentDescription("Library", substring = true, ignoreCase = true)
        val HOME_SURFACE = hasText(HOME_SURFACE_TEXT, substring = true) or
            hasContentDescription(HOME_SURFACE_TEXT, substring = true)
        val TOME_SURFACE = hasText(TOME_SURFACE_TEXT, substring = true) or
            hasContentDescription(TOME_SURFACE_TEXT, substring = true)
        val ViewportA = Viewport(832.5f, 468.3f)
        val ViewportB = Viewport(720f, 420f)
        val Zero = Viewport(0f, 0f)
        const val HomeShelfFloorMessage = "Home Shelf requires at least 320 x 300"
    }
}
