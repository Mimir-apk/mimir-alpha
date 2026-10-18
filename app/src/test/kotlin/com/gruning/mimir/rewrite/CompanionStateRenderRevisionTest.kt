package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.CompanionMode
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * a render revision is spent only when the payload actually changed.
 *
 * `_payload` always conflated an equal value; `_renderPayload` did not, because every publish minted
 * a fresh revision. The revision feeds `CompanionPresentationFrameToken`, the token keys a
 * `LaunchedEffect`, and that effect re-arms the second screen's frame-commit handshake — so an
 * unchanged payload still drew a frame on display 4. Measured on the Thor test unit at 80 frames per
 * 40 focus moves with the pixels byte-identical.
 *
 * `CompanionState` is a process singleton, so each test drives it to a known state first and asserts
 * on revision DELTAS rather than absolute values.
 */
class CompanionStateRenderRevisionTest {

    private fun payload(title: String) = CompanionPayload(mode = CompanionMode.NOW_PLAYING, title = title)

    @Test
    fun `republishing the identical payload spends no revision`() {
        CompanionState.set(payload("Castlevania"))
        val after = CompanionState.renderPayload.value.revision

        repeat(40) { CompanionState.set(payload("Castlevania")) }

        assertEquals(
            "40 identical publishes must not move the revision -- each one re-armed a frame commit " +
                "for content that had not changed",
            after,
            CompanionState.renderPayload.value.revision,
        )
    }

    @Test
    fun `a genuine change still spends one revision`() {
        CompanionState.set(payload("Castlevania"))
        val before = CompanionState.renderPayload.value.revision

        CompanionState.set(payload("Final Fantasy"))

        assertEquals(
            "the fix must not suppress a real update -- that would be a blank second screen, which is " +
                "worse than a redundant frame",
            before + 1L,
            CompanionState.renderPayload.value.revision,
        )
        assertEquals("Final Fantasy", CompanionState.renderPayload.value.payload.title)
    }

    @Test
    fun `alternating payloads spend one revision each, so nothing is coalesced away`() {
        CompanionState.set(payload("A"))
        val before = CompanionState.renderPayload.value.revision

        repeat(5) {
            CompanionState.set(payload("B"))
            CompanionState.set(payload("A"))
        }

        assertEquals(
            "ten real changes must spend ten revisions",
            before + 10L,
            CompanionState.renderPayload.value.revision,
        )
    }

    @Test
    fun `the render payload never disagrees with the plain payload`() {
        // The two flows are written together and the dedup keys off the render one, so a divergence
        // would mean readers of `payload` and readers of `renderPayload` see different screens.
        CompanionState.set(payload("Metroid"))
        repeat(3) { CompanionState.set(payload("Metroid")) }

        assertEquals(CompanionState.payload.value, CompanionState.renderPayload.value.payload)
        assertNotEquals(0L, CompanionState.renderPayload.value.revision)
    }
}
