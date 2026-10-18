package com.gruning.mimir.rewrite.feature.companion

import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.PressInteraction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Thor Bottom revamp B5 fix round: what the lattice's touch handling did to a tile's press, read off the REAL [CompanionDragStateMachine] by [companionTilePresses], and
 * the [CompanionTilePressBus] that turns it into interactions on the tile's source. A production lattice tile has no tap node, so this is the only thing that tells it
 * it is held: Down on a tile, Up when it activates, Cancel when the press becomes a lift, a pan the pager took, a second finger, or a long hold lifted without a drag.
 */
class CompanionTilePressTest {

    private val model = CompanionDragModel(
        version = 1L,
        tiles = listOf("a", "b", "c").map { CompanionDragTileSnapshot(it, false) },
    )
    private val geometry = run {
        val rects = (0 until 9).associate { slot ->
            slot to DragRect((slot % 3) * 100f, (slot / 3) * 100f, (slot % 3 + 1) * 100f, (slot / 3 + 1) * 100f)
        }
        CompanionDragGeometry(
            generation = 1L,
            modelVersion = 1L,
            pageCount = 1,
            settledPage = 0,
            railBounds = DragRect(0f, 0f, 300f, 300f),
            slotRects = rects,
        )
    }

    private var machine = CompanionDragMachine.initial(model)

    /** Sends one event to the real reducer and returns what the lattice would tell the tiles. */
    private fun send(event: CompanionDragEvent): List<CompanionTilePress> {
        val before = machine.phase
        val reduction = CompanionDragStateMachine.reduce(machine, event)
        machine = reduction.machine
        return companionTilePresses(before, reduction.machine.phase, reduction.effects)
    }

    private fun down(slot: Int, atMillis: Long = 0L) =
        send(CompanionDragEvent.Down(PointerId, DragPoint((slot % 3) * 100f + 50f, (slot / 3) * 100f + 50f), atMillis, geometry))

    private fun up(slot: Int, atMillis: Long) =
        send(CompanionDragEvent.Up(PointerId, DragPoint((slot % 3) * 100f + 50f, (slot / 3) * 100f + 50f), atMillis, geometry))

    @Test
    fun `a finger down on a tile presses it, and a tap up releases it`() {
        assertEquals(listOf(CompanionTilePress.Down("a")), down(slot = 0))
        assertEquals(listOf(CompanionTilePress.Up("a")), up(slot = 0, atMillis = 60L))
    }

    @Test
    fun `a finger down on an empty slot presses nothing`() {
        assertEquals(emptyList<CompanionTilePress>(), down(slot = 5))
    }

    @Test
    fun `a press the pager takes is cancelled`() {
        down(slot = 0)
        val pan = send(
            CompanionDragEvent.Move(PointerId, DragPoint(20f, 50f), 40L, geometry, pagerConsumed = true),
        )
        assertEquals(listOf(CompanionTilePress.Cancel("a")), pan)
        assertEquals("nothing more is reported when it ends", emptyList<CompanionTilePress>(), up(slot = 0, atMillis = 300L))
    }

    @Test
    fun `a press that becomes a lift is cancelled`() {
        down(slot = 0)
        val pressed = machine.phase as CompanionDragPhase.Pressed
        val lift = send(
            CompanionDragEvent.LongPressElapsed(PointerId, pressed.pressToken, "transaction", CompanionDragStateMachine.LongPressMillis, geometry),
        )
        assertEquals(listOf(CompanionTilePress.Cancel("a")), lift)
    }

    @Test
    fun `a second finger or a system cancel cancels the press`() {
        down(slot = 1)
        assertEquals(listOf(CompanionTilePress.Cancel("b")), send(CompanionDragEvent.Cancel(PointerId)))
    }

    @Test
    fun `a hold lifted without a drag is cancelled, not released, because it does not activate`() {
        down(slot = 0)
        assertEquals(
            listOf(CompanionTilePress.Cancel("a")),
            up(slot = 0, atMillis = CompanionDragStateMachine.LongPressMillis),
        )
    }

    @Test
    fun `the bus turns those into press, release and cancel on the tile's own source, and never leaves one dangling`() = runBlocking {
        val bus = CompanionTilePressBus()
        val seen = mutableListOf<Interaction>()
        // Unconfined: the collector has subscribed (a source has no replay) by the time launch returns.
        val job = CoroutineScope(Dispatchers.Unconfined).launch { bus.sourceFor("a").interactions.collect { seen += it } }
        bus.apply(listOf(CompanionTilePress.Down("a")))
        bus.apply(listOf(CompanionTilePress.Up("a")))
        bus.apply(listOf(CompanionTilePress.Down("a")))
        bus.apply(listOf(CompanionTilePress.Cancel("a")))
        // A Down that follows an unclosed Down closes the first.
        bus.apply(listOf(CompanionTilePress.Down("a")))
        bus.apply(listOf(CompanionTilePress.Down("a")))
        // And a surface that is leaving lets go of what is held.
        bus.cancelAll()
        job.cancel()
        val kinds = seen.map {
            when (it) {
                is PressInteraction.Press -> "press"
                is PressInteraction.Release -> "release"
                is PressInteraction.Cancel -> "cancel"
                else -> "other"
            }
        }
        assertEquals(listOf("press", "release", "press", "cancel", "press", "cancel", "press", "cancel"), kinds)
        // Every release or cancel names the press it closes.
        val presses = seen.filterIsInstance<PressInteraction.Press>()
        assertTrue(seen.filterIsInstance<PressInteraction.Release>().all { it.press in presses })
        assertTrue(seen.filterIsInstance<PressInteraction.Cancel>().all { it.press in presses })
    }

    private fun pressed(tileId: String?, token: Long = 5L, released: Boolean = false) = CompanionDragPhase.Pressed(
        pointerId = PointerId,
        downRoot = DragPoint(50f, 50f),
        currentRoot = DragPoint(50f, 50f),
        tileId = tileId,
        slot = if (tileId == null) null else 0,
        downAtMillis = 0L,
        pressToken = token,
        sourceRect = null,
        modelVersion = 1L,
        layoutGeneration = 1L,
        pendingUp = if (released) CompanionPendingUp(PointerId, DragPoint(50f, 50f), 80L) else null,
    )

    /**
     * A press is (token, tile). The model changing under a held finger can lose the tile, or find one, with the press token unchanged.
     * Keyed on the token alone, Pressed(A) -> Pressed(null) -> Idle never cancelled A, which stayed drawn in at 0.97, and Pressed(null) -> Pressed(B) never downed B.
     */
    @Test
    fun `a tile lost under one held press is cancelled at once, and a later release has nothing left to cancel`() {
        val lost = companionTilePresses(pressed("a"), pressed(null), emptyList())
        assertEquals(listOf<CompanionTilePress>(CompanionTilePress.Cancel("a")), lost)
        assertEquals("the end of the press reports nothing more", emptyList<CompanionTilePress>(), companionTilePresses(pressed(null), CompanionDragPhase.Idle, emptyList()))

        val bus = CompanionTilePressBus()
        val seen = mutableListOf<Interaction>()
        runBlocking {
            val job = CoroutineScope(Dispatchers.Unconfined).launch { bus.sourceFor("a").interactions.collect { seen += it } }
            bus.apply(companionTilePresses(CompanionDragPhase.Idle, pressed("a"), emptyList()))
            bus.apply(lost)
            job.cancel()
        }
        assertTrue("the press on a was opened, then cancelled: $seen", seen.size == 2 && seen[0] is PressInteraction.Press && seen[1] is PressInteraction.Cancel)
    }

    @Test
    fun `a tile found under one held press is pressed, and a tile that changes under it moves the press`() {
        assertEquals(listOf<CompanionTilePress>(CompanionTilePress.Down("b")), companionTilePresses(pressed(null), pressed("b"), emptyList()))
        assertEquals(
            "one press ends and the next begins",
            listOf(CompanionTilePress.Cancel("a"), CompanionTilePress.Down("b")),
            companionTilePresses(pressed("a"), pressed("b"), emptyList()),
        )
        assertEquals("the same press on the same tile reports nothing", emptyList<CompanionTilePress>(), companionTilePresses(pressed("a"), pressed("a"), emptyList()))
    }

    /**
     * Partial geometry can let a finger lift before the tile under it is known: Pressed(null, pendingUp) -> Pressed(B, pendingUp). The finger is no longer held, so B must
     * not go down (Down would reopen a press nothing is left to close); its end is Up or Cancel with nothing opened.
     */
    @Test
    fun `a tile resolved after the finger has lifted is never pressed`() {
        assertEquals(
            "no Down for a press that has a pending release",
            emptyList<CompanionTilePress>(),
            companionTilePresses(pressed(null, released = true), pressed("b", released = true), emptyList()),
        )
        // The end of it: Up (the pending tap activates) with no Down before it, and the bus has nothing open to release.
        val end = companionTilePresses(pressed("b", released = true), CompanionDragPhase.Idle, listOf(CompanionDragEffect.ActivateTile("b")))
        assertEquals(listOf<CompanionTilePress>(CompanionTilePress.Up("b")), end)
        val bus = CompanionTilePressBus()
        val seen = mutableListOf<Interaction>()
        runBlocking {
            val job = CoroutineScope(Dispatchers.Unconfined).launch { bus.sourceFor("b").interactions.collect { seen += it } }
            bus.apply(companionTilePresses(pressed(null, released = true), pressed("b", released = true), emptyList()))
            bus.apply(end)
            job.cancel()
        }
        assertEquals("the tile was never drawn in: $seen", emptyList<Interaction>(), seen)
        // A finger still held finds its tile as before.
        assertEquals(listOf<CompanionTilePress>(CompanionTilePress.Down("b")), companionTilePresses(pressed(null), pressed("b"), emptyList()))
    }

    private companion object {
        const val PointerId = 7L
    }
}
