package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirTransitionEpochLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Alpha audit F3 (2026-09-30): Quick Launch's picker appeared in one frame at alpha 1 and left on a 180 ms fade. Nothing
 * before it on the host's layer pair means it is opening over the dock, so it now starts at 0 and takes the step tier: the
 * 130 ms lead, then [MimirMotion.OverlayMs], 310 ms from the press and symmetric with its close. This pins where each
 * arrival starts; the hold and the touch shield that wait on it are pinned in OverlayArrivalInputGateTest.
 */
class HomeQuickLaunchPickerStartAlphaTest {

    private val general = HomeQuickLaunchPickerMode.General
    private val assign = HomeQuickLaunchPickerMode.Assign(slotIndex = 2)

    private fun start(
        activeMode: HomeQuickLaunchPickerMode?,
        hasOutgoing: Boolean = false,
        cachedLayerTarget: HomeQuickLaunchPickerMode? = null,
        cachedLayerAlpha: Float = 0f,
        reducedMotion: Boolean = false,
    ) = homeQuickLaunchPickerStartAlpha(activeMode, hasOutgoing, cachedLayerTarget, cachedLayerAlpha, reducedMotion)

    @Test
    fun `a picker opening over the dock starts at 0, so it arrives instead of cutting in`() {
        assertEquals(0f, start(general), 0f)
        assertEquals(0f, start(assign), 0f)
    }

    @Test
    fun `reduced motion keeps the cut it always had`() {
        assertEquals(1f, start(general, reducedMotion = true), 0f)
        assertEquals(1f, start(assign, reducedMotion = true), 0f)
    }

    @Test
    fun `a picker that is closing has no arrival and stands at 1 while its pixels dissolve`() {
        assertEquals(1f, start(activeMode = null), 0f)
        assertEquals(1f, start(activeMode = null, hasOutgoing = true, cachedLayerTarget = general, cachedLayerAlpha = 0.4f), 0f)
    }

    @Test
    fun `one mode replacing another starts at 0 and waits out the old one's dissolve cover`() {
        assertEquals(0f, start(assign, hasOutgoing = true, cachedLayerTarget = general, cachedLayerAlpha = 0.9f), 0f)
        assertEquals(0f, start(assign, hasOutgoing = true, cachedLayerTarget = null), 0f)
    }

    @Test
    fun `a reversal carries on from the alpha its layer painted, so it never blinks out and arrives again`() {
        assertEquals(0.4f, start(general, hasOutgoing = true, cachedLayerTarget = general, cachedLayerAlpha = 0.4f), 0f)
        assertEquals("never above 1", 1f, start(general, hasOutgoing = true, cachedLayerTarget = general, cachedLayerAlpha = 1.3f), 0f)
        assertEquals("never below 0", 0f, start(general, hasOutgoing = true, cachedLayerTarget = general, cachedLayerAlpha = -0.2f), 0f)
    }

    @Test
    fun `through the host's ledger a cold opening has no outgoing layer and a mode swap has one`() {
        // The host's own retention rule (pinned in OverlayArrivalInputGateTest): a cold null-to-picker entry records no
        // outgoing pixels, a picker replacing another does, and so does the one closing.
        val ledger = MimirTransitionEpochLedger<HomeQuickLaunchPickerMode?>(null)
        val retain = { from: HomeQuickLaunchPickerMode?, to: HomeQuickLaunchPickerMode? -> from != null && from != to }

        val open = ledger.prepare(general, retain)
        assertNull("nothing precedes a cold opening", open.outgoingLayerIndex)
        assertEquals(0f, start(general, hasOutgoing = open.outgoingLayerIndex != null), 0f)
        ledger.commit(open)

        val swap = ledger.prepare(assign, retain)
        assertNotNull("the old mode's pixels dissolve while the new one arrives", swap.outgoingLayerIndex)
        assertEquals(0f, start(assign, hasOutgoing = swap.outgoingLayerIndex != null, cachedLayerTarget = null), 0f)
        ledger.commit(swap)

        val close = ledger.prepare(null, retain)
        assertNotNull("the closing picker's pixels dissolve", close.outgoingLayerIndex)
        assertEquals(1f, start(null, hasOutgoing = close.outgoingLayerIndex != null), 0f)
    }

    @Test
    fun `the arrival is the step tier, 310 ms from the press and the same 180 ms as the close`() {
        assertEquals(
            "the picker's fade runs on the step tier's clock",
            mimirOverlayStepArriveMs(reducedMotion = false),
            HomeOverlayDissolveMs,
        )
        assertEquals(310, MimirMotion.OverlayDissolveCoverMs + HomeOverlayDissolveMs)
    }
}
