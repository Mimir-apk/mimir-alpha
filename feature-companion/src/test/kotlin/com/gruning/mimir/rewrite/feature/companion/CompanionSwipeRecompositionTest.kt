package com.gruning.mimir.rewrite.feature.companion

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.ObserverHandle
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.model.CompanionHomeLane
import com.gruning.mimir.rewrite.core.model.CompanionHomeTile
import com.gruning.mimir.rewrite.core.model.CompanionHomeTileKind
import com.gruning.mimir.rewrite.core.model.CompanionMode
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import com.gruning.mimir.rewrite.core.ui.LocalBottomUiDisplay
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * D37: a Bottom swipe shares Main's UI thread, so a swipe must not recompose what it does not change. Each test drives 50 finger moves on the REAL
 * [CompanionSurface] at the Thor Bottom's size (paused clock, real touch events), in the style of the trackpad's "at most two recompositions per 50
 * moves" budget, and counts the scopes that recomposed through the surface's own [CompanionRecompositionSite] sites (the probe is null in production).
 * Every count is printed, so the numbers are in the JUnit output whether the budget held or not.
 *
 *  - A lattice page drag that crosses the halfway point changes the pager's current page once, and the surface reports the page it settled on up to
 *    the host's store. The host reads that store only as the page to START on, so the page it hands the surface must not be something the host
 *    observes: nothing the host reads may change during the drag (the host's own scope, and the surface scope under it, recompose no time at all).
 *  - A strip pan moves the panes by their draw-phase layers only: the surface, the lattice and the Activity pane recompose no more than twice over the
 *    whole pan (the first frame that builds the panes is outside the 50 moves).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionSwipeRecompositionTest {

    @get:Rule
    val compose = createComposeRule()

    private val widthDp = 1240f / Density369
    private val heightDp = 1080f / Density369
    private val tiles = (1..19).map { CompanionHomeTile("p$it", CompanionHomeTileKind.PLATFORM, "Platform number $it") }
    private val payload = CompanionPayload(
        mode = CompanionMode.HOME,
        statsLabel = "19 platforms",
        homeLanes = listOf(CompanionHomeLane(id = "emulation", title = "Emulation", hint = "", tiles = tiles)),
    )

    private val counts = HashMap<String, Int>()
    private val probe: (String) -> Unit = { site -> counts[site] = (counts[site] ?: 0) + 1 }
    private fun count(site: String): Int = counts[site] ?: 0

    /** The snapshot states written while a swipe runs, by identity: a budget that fails names the one that was rewritten most (a swipe's per-move cost has a state behind it). */
    private val written = HashMap<Int, Pair<String, Int>>()

    private fun watchWrites(): ObserverHandle = Snapshot.registerApplyObserver { changed, _ ->
        for (state in changed) {
            val summary = (state as? State<*>)?.let { s -> s.value.let { v -> "${v?.javaClass?.simpleName}=${v.toString().take(100)}" } } ?: state.javaClass.simpleName
            val id = System.identityHashCode(state)
            written[id] = summary to ((written[id]?.second ?: 0) + 1)
        }
    }

    private fun mostWritten(): String =
        written.values.sortedByDescending { it.second }.take(4).joinToString(" | ") { "${it.second}x ${it.first}" }

    private var density = 0f
    private val pageStore = MutableStateFlow(0)
    private var hostRecompositions = 0
    private var stripX by mutableFloatStateOf(0f)
    private var panActive by mutableStateOf(false)

    private fun width(): Float = widthDp * density

    private fun frames(ms: Long) {
        compose.mainClock.advanceTimeBy(ms)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    private fun page(): String =
        compose.onNodeWithTag(CompanionPageRunesTag, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsProperties.ContentDescription].single()

    private fun mount(content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(Density369, 1f),
                LocalBottomUiDisplay provides DISPLAY,
                LocalCompanionRecompositionProbe provides probe,
            ) {
                MimirTheme {
                    density = LocalDensity.current.density
                    content()
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000L)
        compose.waitForIdle()
        counts.clear()
        hostRecompositions = 0
    }

    /** The Presentation's wiring of the page the lattice starts on: the host keeps the store the lattice writes to, and hands the surface a function that reads it (it observes nothing). */
    @Composable
    private fun PageStoreHost() {
        SideEffect { hostRecompositions += 1 }
        CompanionSurface(
            payload = payload,
            cursorId = "p1",
            restoredPageProvider = { pageStore.value },
            onPageSettled = { pageStore.value = it },
            onWorkspaceDrag = { _: Float -> 0f },
            onWorkspaceDragEnd = { _: Float -> false },
            onWorkspaceDragCancel = {},
            showActivitySwipeHint = false,
            monotonicUptimeMillis = { compose.mainClock.currentTime },
            modifier = Modifier.requiredSize(widthDp.dp, heightDp.dp),
        )
    }

    @Test
    fun `a fifty-move lattice page drag across the halfway point recomposes the host and the surface no time at all and the lattice at most three times`() {
        mount { PageStoreHost() }
        assertEquals("fixture: the lattice starts on page 1", "Page 1 of 3", page())
        val root = compose.onNodeWithTag(CompanionDragRootTag, useUnmergedTree = true)
        val start = Offset(width() * 0.9f, heightDp * density / 2f)
        val perMove = Offset(-width() * 0.7f / 50f, 0f)
        val watch = watchWrites()
        root.performTouchInput { down(start) }
        repeat(10) {
            root.performTouchInput { repeat(5) { moveBy(perMove, delayMillis = 16) } }
            frames(16)
        }
        frames(100)
        watch.dispose()
        assertEquals("fixture: the drag crossed the halfway point, so the pager's current page changed", "Page 2 of 3", page())
        println("D37 lattice page drag, 50 moves: lattice=${count("lattice")} surface=${count("surface")} host=$hostRecompositions store=${pageStore.value}")
        println("D37 most rewritten states: ${mostWritten()}")
        assertTrue("the lattice recomposed ${count("lattice")} times in a 50-move page drag (budget 3: the touch-down and the page crossing). Most rewritten: ${mostWritten()}", count("lattice") <= 3)
        assertEquals("the surface was recomposed by a page the host observes", 0, count("surface"))
        assertEquals("the host was recomposed by the page it observes", 0, hostRecompositions)
        root.performTouchInput { up() }
        frames(500)
        assertEquals("the surface still reported the page it settled on", 1, pageStore.value)
    }

    @Test
    fun `a fifty-move strip pan recomposes the surface, the lattice and the Activity pane at most twice`() {
        var neighbourSeen = false
        val neighbour: @Composable () -> Unit = {
            CompanionRecompositionSite("neighbour")
            SideEffect { neighbourSeen = true }
            Box(Modifier.fillMaxSize().testTag("d37-neighbour"))
        }
        mount {
            CompanionSurface(
                payload = payload,
                cursorId = "p1",
                restoredPage = 0,
                neighbourScene = neighbour,
                scenePanActive = panActive,
                sceneStripX = { stripX },
                // The seam takes what it is offered, as the strip does: it starts panning and moves with the finger up to one width.
                onWorkspaceDrag = { delta: Float ->
                    val next = (stripX + delta).coerceIn(0f, width())
                    val used = next - stripX
                    stripX = next
                    if (used != 0f) panActive = true
                    delta - used
                },
                onWorkspaceDragEnd = { _: Float -> true },
                onWorkspaceDragCancel = {},
                showActivitySwipeHint = false,
                monotonicUptimeMillis = { compose.mainClock.currentTime },
                modifier = Modifier.requiredSize(widthDp.dp, heightDp.dp),
            )
        }
        val root = compose.onNodeWithTag(CompanionDragRootTag, useUnmergedTree = true)
        val start = Offset(width() * 0.1f, heightDp * density / 2f)
        // The pan begins on the first moves past the touch slop; the frame that builds the panes is not one of the 50 counted moves.
        root.performTouchInput { down(start) }
        root.performTouchInput { repeat(8) { moveBy(Offset(12f, 0f), delayMillis = 16) } }
        frames(100)
        assertTrue("fixture: the strip is panning and the Activity pane is built", panActive && neighbourSeen)
        counts.clear()
        val before = stripX
        val perMove = Offset(width() * 0.6f / 50f, 0f)
        val watch = watchWrites()
        repeat(10) {
            root.performTouchInput { repeat(5) { moveBy(perMove, delayMillis = 16) } }
            frames(16)
        }
        frames(100)
        watch.dispose()
        assertTrue("fixture: the strip followed the finger (${stripX - before} px)", stripX - before > width() * 0.5f)
        println("D37 strip pan, 50 moves: surface=${count("surface")} lattice=${count("lattice")} neighbour=${count("neighbour")}")
        println("D37 most rewritten states: ${mostWritten()}")
        assertTrue("the surface recomposed ${count("surface")} times (budget 2)", count("surface") <= 2)
        assertTrue("the lattice recomposed ${count("lattice")} times (budget 2). Most rewritten: ${mostWritten()}", count("lattice") <= 2)
        assertTrue("the Activity pane recomposed ${count("neighbour")} times (budget 2)", count("neighbour") <= 2)
        root.performTouchInput { up() }
        frames(100)
    }

    private companion object {
        const val Density369 = 369f / 160f
        const val DISPLAY = 4
    }
}
