package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * the ORDER of the splash-first mount handshake ([SplashFirstDrawHandshake]), against fake platform ports.
 *
 * The property: the content never mounts inside a frame-commit callback, never on the splash's FIRST committed frame,
 * and never ahead of the queue. Frame 1's commit asks for a second frame; frame 2's commit posts the mount to the back
 * of the main queue; only that post mounts. Why each step exists is in SplashFirstContentMount.kt (Android 13 sources).
 *
 * What this cannot see: the device's draw report. These fakes stand in for the RenderThread's commit messages and the
 * main queue; that `reportDrawFinished` really runs before the content's composition on the Thor is device-owed and read
 * from the MimirColdStart lines against `Displayed`.
 */
class SplashFirstDrawHandshakeTest {

    private class FakePorts : SplashFirstDrawPorts {
        val pendingCommits = mutableListOf<Runnable>()
        var frameRequests = 0
        val backOfQueue = mutableListOf<Runnable>()
        val delayed = mutableListOf<Pair<Long, Runnable>>()

        override fun onNextFrameCommit(callback: Runnable): () -> Unit {
            pendingCommits += callback
            return { pendingCommits.remove(callback) }
        }

        override fun requestFrame() {
            frameRequests++
        }

        override fun postToBackOfQueue(task: Runnable): () -> Unit {
            backOfQueue += task
            return { backOfQueue.remove(task) }
        }

        override fun postDelayed(delayMs: Long, task: Runnable): () -> Unit {
            val entry = delayMs to task
            delayed += entry
            return { delayed.remove(entry) }
        }

        /** The window committed a frame: run what was registered for it, as its commit message would. */
        fun commitFrame() {
            val due = pendingCommits.toList()
            pendingCommits.clear()
            due.forEach(Runnable::run)
        }

        fun runQueue() {
            val due = backOfQueue.toList()
            backOfQueue.clear()
            due.forEach(Runnable::run)
        }

        fun fireDeadline() {
            val due = delayed.toList()
            delayed.clear()
            due.forEach { it.second.run() }
        }
    }

    private val ports = FakePorts()
    private val mounts = mutableListOf<SplashFirstMountReason>()
    private val handshake = SplashFirstDrawHandshake(
        ports = ports,
        onMount = { mounts += it },
        deadlineMs = 1_000L,
        trace = { _, _ -> },
    )

    @Test
    fun `arming registers one frame-commit callback and the deadline, and mounts nothing`() {
        handshake.start()

        assertEquals(emptyList<SplashFirstMountReason>(), mounts)
        assertEquals(1, ports.pendingCommits.size)
        assertEquals(listOf(1_000L), ports.delayed.map { it.first })
        assertEquals(0, ports.frameRequests)
        assertTrue(ports.backOfQueue.isEmpty())
    }

    @Test
    fun `the splash's first committed frame asks for a second frame and still mounts nothing`() {
        handshake.start()
        ports.commitFrame()

        assertEquals(
            "frame 1's commit must not mount: without a buffer sync the report is queued after this callback",
            emptyList<SplashFirstMountReason>(),
            mounts,
        )
        assertEquals(1, ports.frameRequests)
        assertEquals(1, ports.pendingCommits.size)
        assertTrue(ports.backOfQueue.isEmpty())
    }

    @Test
    fun `the second committed frame posts the mount to the back of the queue, and only that post mounts`() {
        handshake.start()
        ports.commitFrame()
        ports.commitFrame()

        assertEquals(
            "frame 2's commit must post, not mount inside the callback",
            emptyList<SplashFirstMountReason>(),
            mounts,
        )
        assertEquals(1, ports.backOfQueue.size)
        assertEquals("no third frame is asked for", 1, ports.frameRequests)

        ports.runQueue()

        assertEquals(listOf(SplashFirstMountReason.HANDSHAKE), mounts)
        assertTrue("the deadline is withdrawn once the handshake mounts", ports.delayed.isEmpty())
    }

    @Test
    fun `the deadline mounts once when no frame commits, and a late commit changes nothing`() {
        handshake.start()
        ports.fireDeadline()

        assertEquals(listOf(SplashFirstMountReason.DEADLINE), mounts)
        assertTrue("the pending frame-commit callback is withdrawn", ports.pendingCommits.isEmpty())

        ports.commitFrame()
        ports.commitFrame()
        ports.runQueue()

        assertEquals(listOf(SplashFirstMountReason.DEADLINE), mounts)
    }

    @Test
    fun `a deadline after the first commit mounts once and withdraws the second callback`() {
        handshake.start()
        ports.commitFrame()
        ports.fireDeadline()

        assertEquals(listOf(SplashFirstMountReason.DEADLINE), mounts)
        assertTrue(ports.pendingCommits.isEmpty())
    }

    @Test
    fun `a posted mount is withdrawn when the deadline gets there first`() {
        handshake.start()
        ports.commitFrame()
        ports.commitFrame()
        ports.fireDeadline()

        assertEquals(listOf(SplashFirstMountReason.DEADLINE), mounts)
        assertTrue(ports.backOfQueue.isEmpty())

        ports.runQueue()

        assertEquals(listOf(SplashFirstMountReason.DEADLINE), mounts)
    }

    @Test
    fun `cancelling before the mount withdraws everything and nothing mounts`() {
        handshake.start()
        ports.commitFrame()
        handshake.cancel()

        assertTrue(ports.pendingCommits.isEmpty())
        assertTrue(ports.delayed.isEmpty())

        ports.commitFrame()
        ports.runQueue()
        ports.fireDeadline()

        assertEquals(emptyList<SplashFirstMountReason>(), mounts)
    }

    @Test
    fun `the mount deadline is 1 s and stays within the first-draw beat`() {
        // Hardcoded, not read back from the constant: the splash must never dissolve onto content that has not
        // mounted, and the beat's 3.2 s floor starts after the deadline is armed.
        assertEquals(1_000L, SplashFirstMountDeadlineMs)
        assertTrue(SplashFirstMountDeadlineMs < 3_200L)
        assertEquals(3_200L, WelcomeBackColdStartMs)
    }
}
