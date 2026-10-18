package com.gruning.mimir.rewrite.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * a two-layer pixel host that goes A to nothing to B (or to A again) inside A's 180 ms dissolve recycled the
 * layer A was still fading on. The real [MimirTransitionEpochLedger] is driven exactly as the two hosts in `MimirAppHost.kt` drive it
 * (the direct-overlay host retains `from != to`; the Quick Launch picker host retains `from != null && from != to`), and
 * [mimirRetainLingeringLayers] is asked what the layers still show.
 */
class MimirRetainLingeringLayersTest {

    private class Host(
        initial: String?,
        private val retain: (String?, String?) -> Boolean,
    ) {
        val ledger = MimirTransitionEpochLedger<String?>(initial)
        val targets = arrayOfNulls<String>(2).also { it[0] = initial }
        val alphas = floatArrayOf(if (initial != null) 1f else 0f, 0f)

        /** One presentation: the ledger's frame, adjusted, committed, and the primary layer drawn at [startAlpha]. */
        fun present(target: String?, startAlpha: Float = 1f): MimirTransitionFrame<String?> {
            val prepared = ledger.prepare(target, retain)
            val frame = mimirRetainLingeringLayers(prepared, { targets[it] }, alphas)
            ledger.commit(frame)
            targets[frame.primaryLayerIndex] = target
            alphas[frame.primaryLayerIndex] = startAlpha
            return frame
        }
    }

    private fun directHost(initial: String?) = Host(initial) { from, to -> from != to }
    private fun pickerHost(initial: String?) = Host(initial) { from, to -> from != null && from != to }

    @Test
    fun `A to nothing to B inside A's fade leaves A fading on its own layer and puts B on the other`() {
        val host = directHost("A")
        val closing = host.present(null)
        assertEquals("fixture: A is the outgoing layer", 0, closing.outgoingLayerIndex)
        host.alphas[0] = 0.4f // A, part-way through its fade
        val reopened = host.present("B", startAlpha = 0f)
        assertEquals("A's layer stays on screen as the outgoing one", 0, reopened.outgoingLayerIndex)
        assertEquals("B takes the other layer", 1, reopened.primaryLayerIndex)
        assertEquals("and A's painted alpha is what its fade carries on from", 0.4f, host.alphas[0], 0f)
    }

    @Test
    fun `the harness sees the ledger alone recycle A's layer for B`() {
        val ledger = MimirTransitionEpochLedger<String?>("A")
        ledger.commit(ledger.prepare(null) { from, to -> from != to })
        val bare = ledger.prepare("B") { from, to -> from != to }
        assertEquals("the ledger puts B on the layer A is fading on", 0, bare.primaryLayerIndex)
        assertEquals("with a blank outgoing one: A's fade is cut", 1, bare.outgoingLayerIndex)
    }

    @Test
    fun `the same picker reopened inside its own close carries on from the alpha it had`() {
        val host = pickerHost(null)
        host.present("A", startAlpha = 1f)
        val closing = host.present(null)
        assertEquals(0, closing.outgoingLayerIndex)
        host.alphas[0] = 0.3f
        val reopened = host.present("A", startAlpha = 0.3f)
        assertEquals("A is the primary again, on the layer that still holds it", 0, reopened.primaryLayerIndex)
        assertEquals("beside the blank one", 1, reopened.outgoingLayerIndex)
        assertEquals("with the alpha it painted", "A", host.targets[reopened.primaryLayerIndex])
    }

    @Test
    fun `another picker mode opened inside the close arrives beside the first one's dissolve`() {
        val host = pickerHost(null)
        host.present("A", startAlpha = 1f)
        host.present(null)
        host.alphas[0] = 0.5f
        val handedOver = host.present("X", startAlpha = 0f)
        assertEquals(1, handedOver.primaryLayerIndex)
        assertEquals(0, handedOver.outgoingLayerIndex)
    }

    @Test
    fun `a fade that has finished is not resurrected`() {
        val host = directHost("A")
        host.present(null)
        host.alphas[0] = 0f // A's fade ran out
        val later = host.present("B", startAlpha = 0f)
        assertEquals("the ledger's own frame stands", 0, later.primaryLayerIndex)
    }

    @Test
    fun `a layer the final frame no longer shows is forgotten, so an abandoned fade cannot come back as a ghost`() {
        // A frame that presents nothing and carries no outgoing page leaves layer 1 (still holding X at 0.5) unshown.
        val abandoned = MimirTransitionFrame<String?>(epoch = 3L, target = null, primaryLayerIndex = 0, outgoingLayerIndex = null)
        val alphas = floatArrayOf(1f, 0.5f)
        val kept = mimirRetainLingeringLayers(abandoned, { if (it == 1) "X" else null }, alphas)
        assertEquals(abandoned, kept)
        assertEquals("what an abandoned layer last painted is zeroed", 0f, alphas[1], 0f)
        assertEquals("a shown layer keeps its alpha", 1f, alphas[0], 0f)
    }

    @Test
    fun `the first frame, a closing frame and a real hand-over are left to the ledger`() {
        val first = MimirTransitionEpochLedger<String?>("A").prepare("A") { from, to -> from != to }
        assertEquals(0L, first.epoch)
        val alphas = floatArrayOf(1f, 0f)
        assertEquals(first, mimirRetainLingeringLayers(first, { if (it == 0) "A" else null }, alphas))

        val host = directHost("A")
        val handOver = host.present("B", startAlpha = 0f)
        assertEquals("A is a real outgoing page: nothing to override", 0, handOver.outgoingLayerIndex)
        assertEquals(1, handOver.primaryLayerIndex)

        val closingHost = pickerHost(null)
        closingHost.present("A")
        val closing = closingHost.present(null)
        assertNull(closing.target)
        assertEquals(0, closing.outgoingLayerIndex)
    }

    @Test
    fun `a lingering page finishes its fade in the share of the clock it has left (review 2026-10-15)`() {
        assertEquals("a page painted at 1 keeps the whole clock", 180, mimirLingeringDepartMs(180, 1f))
        assertEquals("A to nothing to B at 90 ms: half-faded, half the clock", 90, mimirLingeringDepartMs(180, 0.5f))
        assertEquals("at 0.2 a fifth of it, not a restart of the whole eased fade (the ~270 ms ghost)", 36, mimirLingeringDepartMs(180, 0.2f))
        assertEquals("a layer that is all but gone still gets a frame to finish in", 1, mimirLingeringDepartMs(180, 0f))
        assertEquals("a cut stays a cut", 0, mimirLingeringDepartMs(0, 0.5f))
        assertEquals("an alpha outside 0..1 is clamped", 180, mimirLingeringDepartMs(180, 1.4f))
        val departs = (1..10).map { mimirLingeringDepartMs(180, it / 10f) }
        assertEquals("the clock never grows as the page is further faded: $departs", departs.sorted(), departs)
    }
}
