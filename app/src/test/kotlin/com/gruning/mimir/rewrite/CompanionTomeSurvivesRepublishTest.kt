package com.gruning.mimir.rewrite

import android.animation.ValueAnimator
import android.app.Application
import android.os.Looper
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performClick
import com.gruning.mimir.rewrite.core.model.CompanionHomeLane
import com.gruning.mimir.rewrite.core.model.CompanionHomeTile
import com.gruning.mimir.rewrite.core.model.CompanionHomeTileKind
import com.gruning.mimir.rewrite.core.model.CompanionMode
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import com.gruning.mimir.rewrite.core.ui.BottomUiReceiptFormat
import com.gruning.mimir.rewrite.core.ui.LocalBottomUiDisplay
import com.gruning.mimir.rewrite.feature.companion.CompanionSurface
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Tome state retention on the REAL [CompanionSurface] under Robolectric.
 *
 * P2: "Move to shelf" republishes the Tome's changed children. The Home used to `remember(tiles)` its open Tome, so that republish
 * closed the Tome under its own confirm and the return swap never ran. The open Tome is now keyed on the folder's id: it survives
 * a change to its own children and a reorder, and closes only when the folder is gone (or its last child left it).
 *
 * P3: the Tome's swap reads the animator setting at each swap edge (as the dock does), so a Tome mounted under "remove animations"
 * and used after they come back animates and names the transition, and the reverse.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CompanionTomeSurvivesRepublishTest {

    @get:Rule
    val compose = createComposeRule()

    private fun child(id: String, label: String) = CompanionHomeTile(id, CompanionHomeTileKind.PLATFORM, label)

    private val alpha = child("c-alpha", "Alphaville")
    private val bravo = child("c-bravo", "Bravonia")
    private val charlie = child("c-charlie", "Charlotte")
    private val gba = CompanionHomeTile("gba", CompanionHomeTileKind.PLATFORM, "Game Boy Advance")

    private fun tome(children: List<CompanionHomeTile>) =
        CompanionHomeTile("folder-1", CompanionHomeTileKind.FOLDER, "Shelf Tome", children = children)

    private fun payloadOf(tiles: List<CompanionHomeTile>) = CompanionPayload(
        mode = CompanionMode.HOME,
        homeLanes = listOf(CompanionHomeLane(id = "emulation", title = "Emulation", hint = "", tiles = tiles)),
    )

    private var payload by mutableStateOf(payloadOf(listOf(gba, tome(listOf(alpha, bravo, charlie)))))
    private val bookOpen = mutableListOf<Boolean>()
    private val unbound = mutableListOf<String>()

    @Before
    fun clean() {
        ShadowLog.clear()
        bookOpen.clear()
        unbound.clear()
    }

    @After
    fun resetAnimators() = setAnimatorScale(1f)

    private fun setAnimatorScale(scale: Float) {
        ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, scale)
    }

    private fun mount(display: Int, withChildren: List<CompanionHomeTile>? = null) {
        if (withChildren != null) payload = payloadOf(listOf(gba, tome(withChildren)))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalBottomUiDisplay provides display) {
                CompanionSurface(
                    payload = payload,
                    cursorId = "gba",
                    onBookOpenChanged = { bookOpen += it },
                    // The runtime's unbind republishes the Tome with that child gone; do the same here.
                    onUnbindChild = { _, childId ->
                        unbound += childId
                        republishChildren { children -> children.filterNot { it.id == childId } }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        advance(300)
    }

    private fun republishChildren(change: (List<CompanionHomeTile>) -> List<CompanionHomeTile>) {
        val current = payload.homeLanes.single().tiles
        payload = payloadOf(current.map { if (it.kind == CompanionHomeTileKind.FOLDER) tome(change(it.children)) else it })
        Snapshot.sendApplyNotifications()
    }

    private fun advance(ms: Long) {
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50L))
    }

    private fun openTheTome() {
        label("Shelf Tome").performClick()
        advance(700)
    }

    private fun longPress(text: String) {
        label(text).performSemanticsAction(SemanticsActions.OnLongClick)
        // The paused clock recomposes only after the state write is applied (see the paused-clock note in the memory).
        Snapshot.sendApplyNotifications()
        advance(700)
    }

    /** Whether a close was reported after the first open (the Home also reports `false` once at its first composition). */
    private fun closedAfterOpening(): Boolean = bookOpen.dropWhile { !it }.any { !it }

    /** Chrome text speaks through its content description, so a label is matched by either. */
    private fun matches(text: String) = hasText(text) or hasContentDescription(text)

    private fun label(text: String) = compose.onAllNodes(matches(text)).onFirst()

    private fun showing(text: String): Boolean = compose.onAllNodes(matches(text)).fetchSemanticsNodes().isNotEmpty()

    private fun lines(display: Int): List<String> =
        ShadowLog.getLogsForTag(BottomUiReceiptFormat.TAG).map { it.msg }.filter { "display=$display" in it }

    private fun settleLogs() = Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5_000L))

    @Test
    fun `moving one child out of a three child Tome keeps it open showing the other two and returns to the leaves`() {
        mount(display = 21)
        openTheTome()
        assertTrue("the Tome opened: $bookOpen", bookOpen.last())
        assertTrue(showing("Alphaville") && showing("Bravonia") && showing("Charlotte"))

        longPress("Bravonia")
        assertTrue("the confirm is up", showing("Move to shelf"))
        label("Move to shelf").performClick()
        advance(700)

        assertEquals(listOf("c-bravo"), unbound)
        assertFalse("the Tome never reported a close, so it stayed open throughout: $bookOpen", closedAfterOpening())
        assertFalse("the confirm returned to the leaves", showing("Move to shelf"))
        assertTrue("the two that stayed are still showing", showing("Alphaville") && showing("Charlotte"))
        assertFalse("the one that left is not", showing("Bravonia"))
        assertTrue("and the Leaves page's own footer is back", showing("Remove Tome"))

        settleLogs()
        val written = lines(21)
        assertFalse("tome-open never exited while the child moved out: $written", written.any { it.startsWith("ev=surface.exit surface=tome-open") })
        assertTrue(
            "the confirm's return was named on its own clock: $written",
            written.any { it.startsWith("ev=surface.exit surface=tome-move-out") && it.endsWith("transition=tome-move-out.exit") },
        )
        assertTrue(
            "and was measured: $written",
            written.any { it.startsWith("ev=transition.end") && "name=tome-move-out.exit" in it && "result=done" in it },
        )
    }

    @Test
    fun `a reorder of the shelf keeps the open Tome open`() {
        mount(display = 22)
        openTheTome()
        assertTrue(bookOpen.last())

        // The shelf's order changes under the open Tome: same folder, same children, different cell.
        payload = payloadOf(listOf(tome(listOf(alpha, bravo, charlie)), gba))
        Snapshot.sendApplyNotifications()
        advance(300)

        assertFalse("no close was reported: $bookOpen", closedAfterOpening())
        assertTrue(showing("Alphaville") && showing("Bravonia") && showing("Charlotte"))
    }

    @Test
    fun `a child filed into the open Tome keeps it open`() {
        mount(display = 23, withChildren = listOf(alpha, bravo))
        openTheTome()
        republishChildren { it + charlie }
        advance(300)
        assertFalse("no close was reported: $bookOpen", closedAfterOpening())
        assertTrue(showing("Charlotte"))
    }

    @Test
    fun `deleting the folder closes the open Tome`() {
        mount(display = 24)
        openTheTome()
        assertTrue(bookOpen.last())

        payload = payloadOf(listOf(gba, alpha, bravo, charlie))
        Snapshot.sendApplyNotifications()
        advance(700)

        assertFalse("the Tome closed: $bookOpen", bookOpen.last())
        assertFalse("and its page is gone", showing("Remove Tome"))
    }

    @Test
    fun `moving the last child out closes the Tome as a changed tile list always did`() {
        mount(display = 25, withChildren = listOf(alpha))
        openTheTome()
        assertTrue(bookOpen.last())
        longPress("Alphaville")
        label("Move to shelf").performClick()
        advance(700)
        assertFalse("an emptied Tome closes: $bookOpen", bookOpen.last())
    }

    @Test
    fun `a Tome opened empty stays open on its empty page`() {
        mount(display = 26, withChildren = emptyList())
        openTheTome()
        assertTrue(bookOpen.last())
        assertTrue(showing("Remove Tome"))
        republishChildren { it }
        payload = payloadOf(listOf(gba, tome(emptyList()), alpha))
        Snapshot.sendApplyNotifications()
        advance(300)
        assertTrue("still open: $bookOpen", bookOpen.last())
    }

    // ---- P3: the swap reads the animator setting at its own edge ---------------------------------------------------------------

    @Test
    fun `a Tome mounted under reduced motion and used after animations return animates and names the swap`() {
        setAnimatorScale(0f)
        mount(display = 27)
        openTheTome() // the Tome page is mounted under reduced motion, so a per-mount read would stay a cut
        setAnimatorScale(1f) // "remove animations" turned off, no remount
        longPress("Bravonia")
        label("Move to shelf").performClick()
        advance(700)
        settleLogs()

        val written = lines(27)
        assertTrue(
            "the long-press names the swap that runs: $written",
            written.any { it.startsWith("ev=surface.enter surface=tome-move-out") && it.endsWith("transition=tome-move-out.enter") },
        )
        assertTrue(
            "and a begin and an end back it: $written",
            written.any { it.startsWith("ev=transition.begin") && "name=tome-move-out.enter" in it } &&
                written.any { it.startsWith("ev=transition.end") && "name=tome-move-out.enter" in it && "result=done" in it },
        )
        assertFalse("no edge is a cut while the swap runs: $written", written.any { "surface=tome-move-out" in it && "reason=reduced_motion" in it })
        assertFalse(written.any { "transition.missing" in it || "transition.unfinished" in it })
    }

    @Test
    fun `a Tome mounted with animations on and used after they are turned off writes the cut at that edge`() {
        mount(display = 28)
        openTheTome()
        setAnimatorScale(0f) // "remove animations" turned on after the Tome opened, no remount
        longPress("Bravonia")
        label("Move to shelf").performClick()
        advance(700)
        settleLogs()

        val written = lines(28)
        assertTrue(
            "the confirm is a cut, named as one: $written",
            written.any {
                it.startsWith("ev=surface.enter surface=tome-move-out") && it.endsWith("transition=none reason=reduced_motion")
            },
        )
        assertFalse("and runs no swap pair: $written", written.any { "name=tome-move-out" in it })
        assertFalse("nor waits for one: $written", written.any { "transition.missing" in it || "transition.unfinished" in it })
        assertFalse("a cut leaves no page behind: the confirm is gone at once", showing("Move to shelf"))
    }
}

