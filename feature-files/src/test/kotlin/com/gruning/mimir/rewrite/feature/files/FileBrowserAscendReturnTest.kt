package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.files.FileItem
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Motion audit 2026-09-28, batch F: the file browser's ascend is a page return. `feature-files` composes nothing, so
 * this pins the DECISION (which arrival departs, what the parent does) and the wiring; whether the child's glide back
 * reads as leaving requires device observation.
 *
 * Nova input-delay correction, 2026-09-30: going up takes presses at once. It held the input
 * gate while the parent waited hidden for the child to clear; that hold is gone, and going INTO a folder, which never held
 * presses, is unchanged. The child is recorded pixels with no tree, no input and no focus (core-ui's MimirPageHandoffTest
 * proves the pixels-only half on a keyed page), and the controller half is proved below on a real focus authority: from the
 * call that goes up the parent's is the only focus graph and a press acts on it. The surface itself is composed, and the gate
 * watched across an ascent, in the app's FileBrowserAscendInputGateTest.
 */
class FileBrowserAscendReturnTest {

    private val up = FileBrowserArrival(FileBrowserArrivalKind.DIRECTORY, FileBrowserGlideReturn, 1L)
    private val down = FileBrowserArrival(FileBrowserArrivalKind.DIRECTORY, FileBrowserGlideEnter, 2L)

    @Test
    fun `only going up hands the listing over, everything else keeps its own arrival`() {
        assertTrue(fileBrowserArrivalDeparts(up))
        assertFalse(fileBrowserArrivalDeparts(down))
        assertFalse(fileBrowserArrivalDeparts(FileBrowserArrival(FileBrowserArrivalKind.SCREEN, epoch = 3L)))
        assertFalse(fileBrowserArrivalDeparts(FileBrowserNoArrival))
        assertFalse(fileBrowserArrivalDeparts(null))
    }

    @Test
    fun `the parent stands at rest under the clearing child, and the clock still runs for the gates`() {
        // Folder-up, 2026-10-15 (Thor, combined44: 513 ms and one near-empty frame): the parent is opaque from its first frame, under the child that clears.
        assertEquals("opaque from the first frame: nothing dips", 1f, fileBrowserArrivalStartAlpha(up), 0f)
        assertEquals(0f, fileBrowserArrivalTravelDp(up), 0f)
        // Files uses the common clear: OverlayMs on the shared easing. The return parent no longer
        // waits for the child clear or holds presses during it.
        assertEquals("the folder-up clear is the overlay clock", MimirMotion.OverlayMs, FileBrowserChildClearMs)
        assertEquals("half the swap tier's short travel (4 dp), now over the 180 ms clear: slower than the 50 ms clear it had", MimirMotion.SwapTravelDp / 2f, FileBrowserChildClearTravelDp, 0f)
        assertTrue("onSettled must still fire, so the ring lands as the child clears", fileBrowserArrivalAnimates(up))
        assertEquals("a descent is unchanged", FileBrowserGlideTravelDp, fileBrowserArrivalTravelDp(down), 0f)
        assertEquals("the travel is the page token", MimirMotion.GlideTravelDp.toFloat(), FileBrowserGlideTravelDp, 0f)
    }

    @Test
    fun `the listing's call site clears the child off the top of a parent that stands opaque under it`() {
        val source = listOf(
            File("src/main/kotlin/com/gruning/mimir/rewrite/feature/files/FileBrowserSurface.kt"),
            File("feature-files/src/main/kotlin/com/gruning/mimir/rewrite/feature/files/FileBrowserSurface.kt"),
        ).first(File::isFile).readText()
        val handoff = source.substringAfter("MimirPageHandoff(").substringBefore("FileBrowserContent(")
        assertTrue(handoff.contains("epoch = arrival.epoch,"))
        assertTrue(handoff.contains("departs = fileBrowserArrivalDeparts(arrival),"))
        assertTrue(handoff.contains("exitTravelPx = MimirPageMotion.returnTravelPx(FileBrowserGlideEnter, ascendTravelPx),"))
        assertTrue("the handoff draws the parent as it is; its own glide hides it", handoff.contains("incomingAlpha = { 1f },"))
        assertTrue("and at rest", handoff.contains("incomingTravelPx = { 0f },"))
        assertTrue("the child clears on the overlay clock", handoff.contains("exitMs = FileBrowserChildClearMs,"))
        assertTrue(source.contains("val ascendTravelPx = with(LocalDensity.current) { FileBrowserChildClearTravelDp.dp.toPx() }"))
        val glide = source.substringAfter("private fun Modifier.fileBrowserArrivalGlide(").substringBefore("\n}\n")
        assertFalse("the parent is never held hidden for the clear (Thor, 2026-10-15: that was the empty frame)", glide.contains("cleared"))
        assertTrue("it is painted at its start alpha, which is 1 for a return", glide.contains("startAlpha + ((1f - startAlpha) * progress.value.coerceIn(0f, 1f))"))
        assertTrue("and the layer paints exactly that, with no second gate in front of it (the frame-sequence test reads it)", glide.contains("alpha = paintedAlpha()"))
        assertTrue("a return animates nothing: the dip is the descent's", glide.contains("if (!standsAtRest) {") && glide.indexOf("if (!standsAtRest) {") < glide.indexOf("progress.animateTo("))
        assertTrue("the ring is released on the frame beat, not after a tween", glide.indexOf("withFrameNanos { }") in 0 until glide.indexOf("onSettled()"))
        assertFalse("no linear clock of its own", glide.contains("LinearEasing"))
        assertFalse("no hold going up: presses do not wait while it is hidden", glide.contains("holdMimirInputUntilShown"))
        assertFalse("and there is no switch left to hold with", source.contains("holdsInput"))
        assertFalse("nor an import for it: the surface holds nothing", source.contains("import com.gruning.mimir.rewrite.core.ui.holdMimirInputUntilShown"))
        assertTrue("FileBrowserContent still plays the descent's glide", source.contains("arrival = directoryArrival"))
        // Previous regression: unkeyed, the layered listing survived the flip and the departing page showed the parent over itself.
        // Correction: keyed on the folder (a cold load no longer remounts the old listing), and the page carries
        // its own ground so the departing child is opaque.
        assertTrue("the listing is keyed on the folder", handoff.contains("key(controller.currentPath) {"))
        assertTrue(
            "the recorded page carries its ground",
            handoff.contains("Box(Modifier.fillMaxSize().background(palette.surfaceBase)) {"),
        )
    }

    @Test
    fun `going into a folder is unchanged, and never held a press`() {
        assertFalse("nothing departs on a descent", fileBrowserArrivalDeparts(down))
        assertEquals("it dips in from the folio's own start alpha", FileBrowserGlideStartAlpha, fileBrowserArrivalStartAlpha(down), 0f)
        assertEquals("and travels as PR #138 shipped it", FileBrowserGlideEnter * FileBrowserGlideTravelDp, fileBrowserArrivalTravelDp(down), 0f)
        assertTrue(fileBrowserArrivalAnimates(down))
        val source = surfaceSource()
        assertTrue(
            "the ring still waits for the glide, and is released by its own completion",
            source.contains("onSettled = { glideSettled.value = true },") && source.contains("fileBrowserRingEligible("),
        )
        val glide = source.substringAfter("private fun Modifier.fileBrowserArrivalGlide(").substringBefore("\n}\n")
        assertFalse("a descent holds no press, and neither does anything else in the glide", glide.contains("holdMimirInputUntilShown"))
        assertTrue("standing at rest is only for a return", glide.contains("val standsAtRest = fileBrowserArrivalDeparts(arrival)"))
    }

    // ───────────────────────────────────────────── one focus graph, and a press that lands on it ──

    private val tree = mapOf(
        "/root" to listOf(
            FileItem(name = "Games", path = "/root/Games", isDirectory = true),
            FileItem(name = "pack.zip", path = "/root/pack.zip", extension = "zip"),
            FileItem(name = "readme.txt", path = "/root/readme.txt", extension = "txt"),
        ),
        "/root/Games" to listOf(FileItem(name = "game.gba", path = "/root/Games/game.gba", extension = "gba")),
    )

    private fun controller(authority: DefaultFocusAuthority) = FileBrowserController(
        authority = authority,
        rootPath = "/root",
        mode = FileBrowserMode.MANAGE,
        listDirectory = { tree[it].orEmpty() },
        onChoose = {},
        onDismiss = {},
    )

    @Test
    fun `from the call that goes up the parent is the only focus graph and a press acts on it at once`() {
        val authority = DefaultFocusAuthority()
        val c = controller(authority)
        c.open()
        assertTrue(c.dispatch(ControllerAction.CONFIRM))
        assertEquals("/root/Games", c.currentPath)
        val child = FileBrowserFocus.tileKey("/root/Games/game.gba")
        assertTrue("standing in the child, its tile is the focus graph", authority.registeredKeysInScope(c.scope).contains(child))

        assertTrue(c.dispatch(ControllerAction.CANCEL))

        // The child listing is recorded pixels by now (core-ui's handoff), and nothing of it is a focus target: the
        // tracker replaced the registrations in the Up itself, not when the composition caught up.
        val keys = authority.registeredKeysInScope(c.scope)
        assertFalse("no key of the child survives the Up", keys.contains(child))
        assertEquals(
            "the parent's tiles, and only them",
            setOf("/root/Games", "/root/pack.zip", "/root/readme.txt").map(FileBrowserFocus::tileKey).toSet(),
            keys.toSet(),
        )
        assertEquals("each registered once: one graph, not two stacked", 3, keys.size)
        assertEquals("the ring is on the folder the player came out of", FileBrowserFocus.tileKey("/root/Games"), authority.state.value.focused)

        // A press in the very next breath lands on it: no hold stands in its way, and it does something.
        assertTrue(c.dispatch(ControllerAction.CONFIRM))
        assertEquals("A on the folder went back into it", "/root/Games", c.currentPath)
        assertEquals(listOf(child), authority.registeredKeysInScope(c.scope))
        c.close()
    }

    @Test
    fun `a direction pressed at once after going up moves within the parent`() {
        val authority = DefaultFocusAuthority()
        val c = controller(authority)
        c.open()
        c.dispatch(ControllerAction.CONFIRM)
        c.dispatch(ControllerAction.CANCEL)
        val before = authority.state.value.focused

        assertTrue(c.dispatch(ControllerAction.NAV_RIGHT))

        val after = authority.state.value.focused
        assertTrue("the press moved the ring along the parent's tiles: $before -> $after", after != null && after != before)
        assertTrue("and it is a parent tile", authority.registeredKeysInScope(c.scope).contains(after))
        assertEquals("still in the parent", "/root", c.currentPath)
        c.close()
    }

    private fun surfaceSource(): String = listOf(
        File("src/main/kotlin/com/gruning/mimir/rewrite/feature/files/FileBrowserSurface.kt"),
        File("feature-files/src/main/kotlin/com/gruning/mimir/rewrite/feature/files/FileBrowserSurface.kt"),
    ).first(File::isFile).readText()
}
