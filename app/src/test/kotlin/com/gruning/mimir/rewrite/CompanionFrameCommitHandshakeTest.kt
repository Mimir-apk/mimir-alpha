package com.gruning.mimir.rewrite

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionFrameCommitHandshakeTest {
    @Test
    fun `presentation intro waits for content unless already settled`() {
        assertFalse(companionPresentationIntroMayStart(hasContent = false, alreadySettled = false, waitedMs = 0L))
        assertTrue(companionPresentationIntroMayStart(hasContent = true, alreadySettled = false, waitedMs = 0L))
        assertTrue(companionPresentationIntroMayStart(hasContent = false, alreadySettled = true, waitedMs = 0L))
        assertTrue(companionPresentationIntroMayStart(hasContent = false, alreadySettled = false, waitedMs = 400L))
    }

    @Test
    fun `presentation fade waits for the current frame commit`() {
        assertFalse(companionPresentationFadeMayStart(hasContent = true, frameCommitted = false, alreadySettled = false))
        assertFalse(companionPresentationFadeMayStart(hasContent = false, frameCommitted = true, alreadySettled = false))
        assertTrue(companionPresentationFadeMayStart(hasContent = true, frameCommitted = true, alreadySettled = false))
        assertTrue(companionPresentationFadeMayStart(hasContent = false, frameCommitted = false, alreadySettled = true))
    }

    @Test
    fun `shelf enter holds until the shelf has a frame`() {
        assertEquals(0f, companionShelfEnterTarget(shelfHasFrame = false))
        assertEquals(1f, companionShelfEnterTarget(shelfHasFrame = true))
    }

    @Test
    fun `shelf exit holds for surface readiness`() {
        assertEquals(1f, companionShelfExitTarget(platformOpen = true, exitSurfaceReady = true))
        assertEquals(1f, companionShelfExitTarget(platformOpen = false, exitSurfaceReady = false))
        assertEquals(0f, companionShelfExitTarget(platformOpen = false, exitSurfaceReady = true))
    }

    @Test
    fun `shelf exit skips the animation when the shelf never had a frame`() {
        assertTrue(companionShelfExitSkipsAnimation(exitPending = true, shelfHasFrame = false))
        assertFalse(companionShelfExitSkipsAnimation(exitPending = true, shelfHasFrame = true))
        assertFalse(companionShelfExitSkipsAnimation(exitPending = false, shelfHasFrame = false))
    }

    @Test
    fun `cold compositor arm requests one follow-up frame for the exact token`() {
        val handshake = CompanionFrameCommitHandshake<UUID>()
        val operationId = UUID.randomUUID()

        assertTrue(handshake.arm(operationId))
        assertFalse(handshake.arm(operationId))

        val plan = handshake.beginDraw(operationId, compositorCallbackAvailable = true)

        assertEquals(operationId, plan?.token)
        assertEquals(CompanionFrameCommitDelivery.COMPOSITOR_CALLBACK, plan?.delivery)
        assertTrue(plan?.requestFollowUpFrame == true)
        assertNull(handshake.beginDraw(operationId, compositorCallbackAvailable = true))
        assertTrue(handshake.complete(operationId))
        assertFalse(handshake.complete(operationId))
    }

    @Test
    fun `new exact payload token invalidates an awaiting stale callback`() {
        val handshake = CompanionFrameCommitHandshake<CompanionPresentationFrameToken>()
        val coldStart = CompanionPresentationFrameToken(UUID.randomUUID(), 4L, 1L, 1L)
        val revision = coldStart.copy(payloadRevision = 5L)

        assertTrue(handshake.arm(coldStart))
        assertEquals(coldStart, handshake.beginDraw(coldStart, compositorCallbackAvailable = true)?.token)
        assertTrue(handshake.arm(revision))

        assertFalse(handshake.complete(coldStart))
        assertNull(handshake.beginDraw(coldStart, compositorCallbackAvailable = true))
        assertEquals(revision, handshake.beginDraw(revision, compositorCallbackAvailable = true)?.token)
        assertTrue(handshake.complete(revision))
    }

    @Test
    fun `legacy fallback remains animation acknowledged without an extra invalidation`() {
        val handshake = CompanionFrameCommitHandshake<UUID>()
        val operationId = UUID.randomUUID()

        assertTrue(handshake.arm(operationId))
        val plan = handshake.beginDraw(operationId, compositorCallbackAvailable = false)

        assertEquals(CompanionFrameCommitDelivery.ANIMATION_FALLBACK, plan?.delivery)
        assertFalse(plan?.requestFollowUpFrame ?: true)
        assertTrue(handshake.complete(operationId))
    }

    @Test
    fun `teardown rejects a late compositor callback`() {
        val handshake = CompanionFrameCommitHandshake<CompanionPresentationFrameToken>()
        val token = CompanionPresentationFrameToken(
            operationId = UUID.randomUUID(),
            payloadRevision = 8L,
            sceneRevision = 2L,
            commitEpoch = 1L,
        )

        assertTrue(handshake.arm(token))
        assertEquals(token, handshake.beginDraw(token, compositorCallbackAvailable = true)?.token)

        handshake.clear()

        assertFalse(handshake.complete(token))
    }

    @Test
    fun `presentation commit is exact across operation payload scene and rebind epoch`() {
        val handshake = CompanionFrameCommitHandshake<CompanionPresentationFrameToken>()
        val operationId = UUID.randomUUID()
        val stale = CompanionPresentationFrameToken(operationId, 7L, 11L, 2L)
        val latest = stale.copy(payloadRevision = 8L, sceneRevision = 12L, commitEpoch = 3L)

        assertTrue(handshake.arm(stale))
        assertEquals(stale, handshake.beginDraw(stale, compositorCallbackAvailable = true)?.token)
        assertTrue(handshake.arm(latest))

        assertFalse(handshake.complete(stale))
        assertNull(handshake.beginDraw(stale, compositorCallbackAvailable = true))
        assertEquals(latest, handshake.beginDraw(latest, compositorCallbackAvailable = true)?.token)
        assertTrue(handshake.complete(latest))
    }
}
