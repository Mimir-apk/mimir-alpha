package com.gruning.mimir.rewrite

import android.app.Application
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import com.gruning.mimir.rewrite.core.ui.BottomUiReceiptFormat
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * D37: the workspace strip's release and its landing must not recompose the Presentation's root. The Presentation is a Dialog on the Main thread both
 * screens share, and its root body is the largest scope in the app; it used to read `workspaceStrip.settle` and `workspaceStrip.settling` itself, so every
 * Activity swipe recomposed all of it twice (the first time on the first frame of the settle's animation).
 *
 * The composed test drives the REAL strip through a 50-move drag, its release, the settle on the pager clock and the landing, under a host whose root is
 * the Presentation's shape: the real [CompanionWorkspaceSettleRunner], and a consumer that reads `settling` where it composes. The root's recompositions
 * are counted and printed. The legacy host (the root reading the strip's state itself, as the Presentation did) is measured beside it, so the
 * numbers before and after are in one run. The source pins are what hold the Presentation to that shape, since the Presentation itself cannot be
 * composed on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionWorkspaceSettleRecompositionTest {

    @get:Rule
    val compose = createComposeRule()

    private val width = 1000f
    private val fling = 800f
    private val strip = CompanionWorkspaceStrip().also { it.widthPx = width }
    private var rootRecompositions = 0
    private var consumerRecompositions = 0
    private val commits = mutableListOf<CompanionWorkspaceSettle>()

    /** The Presentation's shape: the runner is its own scope, and the root reads nothing of the strip. */
    @Composable
    private fun Root() {
        SideEffect { rootRecompositions += 1 }
        CompanionWorkspaceSettleRunner(
            strip = strip,
            receiptDisplayId = BottomUiReceiptFormat.NO_DISPLAY,
            coverUp = { false },
            commit = { commits += it },
        )
        Consumer()
    }

    /** A consumer of "may the page take a touch": it reads `settling` where it composes, as the Presentation's consumers now do. */
    @Composable
    private fun Consumer() {
        val enabled = !strip.settling
        SideEffect { consumerRecompositions += if (enabled) 1 else 1 }
    }

    /** What the Presentation did: the root reads the settle and `settling` itself. */
    @Composable
    private fun LegacyRoot() {
        SideEffect { rootRecompositions += 1 }
        val settle = strip.settle
        val enabled = !strip.settling
        LaunchedEffect(settle) {
            val request = settle ?: return@LaunchedEffect
            Animatable(request.fromX).animateTo(request.toX, tween(180)) { strip.moveTo(request, value) }
            if (strip.finish(request)) commits += request
        }
        SideEffect { consumerRecompositions += if (enabled) 1 else 1 }
    }

    private fun frames(ms: Long) {
        compose.mainClock.advanceTimeBy(ms)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    /** A 50-move drag from the lattice to 60% of the strip, a release at rest (the nearer page is Activity), the settle and the landing. */
    private fun swipeToActivity() {
        repeat(50) {
            strip.drag(CompanionWorkspacePage.SHELF, width * 0.6f / 50f)
            Snapshot.sendApplyNotifications()
            frames(16)
        }
        assertTrue("fixture: the strip is off its rest", strip.active)
        assertNotNull("fixture: the release begins a settle", strip.release(0f, fling))
        Snapshot.sendApplyNotifications()
        frames(16)
        repeat(30) { frames(16) }
        strip.reconcile(CompanionWorkspacePage.ACTIVITY)
        Snapshot.sendApplyNotifications()
        frames(100)
    }

    @Test
    fun `a fifty-move strip drag, its release and its landing recompose the host's root no time at all`() {
        compose.mainClock.autoAdvance = false
        compose.setContent { Root() }
        compose.mainClock.advanceTimeBy(100L)
        compose.waitForIdle()
        rootRecompositions = 0
        consumerRecompositions = 0
        swipeToActivity()
        println("D37 strip swipe (runner shape): root=$rootRecompositions consumer=$consumerRecompositions commits=${commits.size}")
        assertEquals("the settle landed on Activity and committed once", 1, commits.size)
        assertEquals("the host's root was recomposed by the strip", 0, rootRecompositions)
        assertTrue("the consumer recomposed $consumerRecompositions times over a swipe (begin and end of the settle, budget 2)", consumerRecompositions <= 2)
    }

    @Test
    fun `the legacy shape, a root that reads the strip itself, is recomposed by the release and the landing`() {
        compose.mainClock.autoAdvance = false
        compose.setContent { LegacyRoot() }
        compose.mainClock.advanceTimeBy(100L)
        compose.waitForIdle()
        rootRecompositions = 0
        consumerRecompositions = 0
        swipeToActivity()
        println("D37 strip swipe (legacy root): root=$rootRecompositions commits=${commits.size}")
        assertEquals("the settle landed on Activity and committed once", 1, commits.size)
        assertTrue("the legacy root recomposed $rootRecompositions times: the release and the landing", rootRecompositions >= 2)
    }

    @Test
    fun `the Presentation's root reads neither the strip's settle nor its settling, only the consumers do`() {
        val presentation = File("src/main/kotlin/com/gruning/mimir/rewrite/MimirCompanionPresentation.kt").readText()
        assertEquals("the settle is read by the runner and not by the Presentation", 0, Regex("workspaceStrip\\.settle\\b").findAll(presentation).count())
        assertEquals("`settling` is read in one place, the function the consumers ask", 1, Regex("workspaceStrip\\.settling").findAll(presentation).count())
        assertTrue(
            "that function is the base plus the strip's settling",
            presentation.contains("val workspaceNavigationEnabled: () -> Boolean = { workspaceNavigationBase && !workspaceStrip.settling }"),
        )
        assertEquals("and the five consumers ask it where they compose", 5, Regex("workspaceNavigationEnabled\\(\\)").findAll(presentation).count())
        assertTrue("the root hands the settle to the runner", presentation.contains("CompanionWorkspaceSettleRunner("))
    }

    @Test
    fun `the Presentation does not observe the page the lattice starts on, and builds the jobs list once per change`() {
        val presentation = File("src/main/kotlin/com/gruning/mimir/rewrite/MimirCompanionPresentation.kt").readText()
        assertEquals(
            "the page store is asked when the lattice mounts, never collected in composition (every page a swipe turns is written into it)",
            0,
            Regex("CompanionOrderStore\\.page\\.collectAsState").findAll(presentation).count(),
        )
        assertTrue(presentation.contains("val restoredPageProvider = remember { { CompanionOrderStore.page.value } }"))
        assertTrue(presentation.contains("restoredPageProvider = restoredPageProvider,"))
        assertEquals("the surface is not handed the page as a value", 0, Regex("restoredPage = ").findAll(presentation).count())
        assertTrue("the jobs list is remembered on the running jobs, not rebuilt by every recomposition", presentation.contains("jobs = remember(runningJobs) {"))
    }
}
