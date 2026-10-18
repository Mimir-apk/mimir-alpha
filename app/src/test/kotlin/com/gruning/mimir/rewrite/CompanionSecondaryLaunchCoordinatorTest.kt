package com.gruning.mimir.rewrite

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentLinkedQueue

class CompanionSecondaryLaunchCoordinatorTest {
    @Test
    fun `tap publishes exact lock before off-main persistence and dispatch`() {
        val io = ArrayDeque<() -> Unit>()
        val main = ArrayDeque<() -> Unit>()
        val events = mutableListOf<String>()
        val operationId = UUID(0L, 801L)
        val plan = plan(operationId)
        val launchCoordinator = coordinator(
            plan = plan,
            io = io,
            main = main,
            events = events,
        )
        val recents = CompanionRecentsCoordinator(
            operationIdFactory = { UUID(0L, 999L) },
            deadlineScheduler = inertDeadlineScheduler(),
        )

        val launched = recents.requestPreparedLaunch("org.example.game", "Example Game", launchCoordinator::prepare)

        assertEquals(operationId, launched)
        assertEquals(operationId, (recents.ui.value.launchState as CompanionDrawerLaunchState.LaunchPending).operationId)
        assertEquals(listOf("prepare", "schedule"), events)
        assertFalse(events.contains("persist"))
        assertEquals(1, io.size)

        io.removeFirst().invoke()
        assertEquals(listOf("prepare", "schedule", "persist", "main"), events)
        assertFalse(events.contains("launch"))
        main.removeFirst().invoke()
        assertEquals(listOf("prepare", "schedule", "persist", "main", "current", "launch"), events)
    }

    @Test
    fun `stale operation completion never dispatches`() {
        val io = ArrayDeque<() -> Unit>()
        val main = ArrayDeque<() -> Unit>()
        val events = mutableListOf<String>()
        val coordinator = coordinator(plan(UUID(0L, 802L)), io, main, events, current = false)

        val prepared = coordinator.prepare("org.example.game")
        assertNotNull(prepared)
        assertTrue(prepared!!.dispatch())
        io.removeFirst().invoke()
        main.removeFirst().invoke()

        assertTrue(events.contains("current"))
        assertFalse(events.contains("launch"))
    }

    @Test
    fun `persistence failure settles only its planned operation`() {
        val io = ArrayDeque<() -> Unit>()
        val main = ArrayDeque<() -> Unit>()
        val events = mutableListOf<String>()
        val failed = mutableListOf<Pair<UUID, String>>()
        val plan = plan(UUID(0L, 803L))
        val coordinator = coordinator(plan, io, main, events, persist = false, failed = failed)

        assertNull(coordinator.prepare(" "))
        val prepared = coordinator.prepare(plan.packageName)!!
        assertTrue(prepared.dispatch())
        io.removeFirst().invoke()
        main.removeFirst().invoke()

        assertEquals(listOf(plan.operationId to "pending_operation_persist_failed"), failed)
        assertFalse(events.contains("launch"))
    }

    @Test
    fun `game await returns the actual accepted launch result after main callback claims`() {
        val callbackReady = CountDownLatch(1)
        val callback = AtomicReference<(() -> Unit)?>(null)
        val launchEntered = CountDownLatch(1)
        val releaseLaunch = CountDownLatch(1)
        val launched = AtomicInteger(0)
        val result = AtomicReference<Boolean>()
        val callerDone = CountDownLatch(1)
        val plan = plan(UUID(0L, 804L), game = true)
        val coordinator = CompanionSecondaryLaunchCoordinator(
            preparePlan = { plan },
            persistPlan = { true },
            isCurrentPlan = { true },
            launchPlan = {
                launched.incrementAndGet()
                launchEntered.countDown()
                assertTrue(releaseLaunch.await(1, TimeUnit.SECONDS))
                true
            },
            failPlan = { _, _ -> },
            workScheduler = CompanionLaunchWorkScheduler { work ->
                Thread(work, "secondary-game-test-io").start()
                true
            },
            mainDispatcher = CompanionLaunchMainDispatcher { work ->
                callback.set(work)
                callbackReady.countDown()
            },
            gameHostDispatchTimeoutMs = 20L,
        )

        val prepared = coordinator.prepare(plan.packageName)!!
        Thread({
            result.set(prepared.dispatchAwaitingHost())
            callerDone.countDown()
        }, "secondary-game-test-caller").start()

        assertTrue(callbackReady.await(1, TimeUnit.SECONDS))
        val callbackThread = Thread({ callback.get()!!.invoke() }, "secondary-game-test-main").also { it.start() }
        assertTrue(launchEntered.await(1, TimeUnit.SECONDS))
        // The host callback has claimed the gate, but launchPlan is still completing past the wait.
        // The caller must wait for its actual result instead of reporting timeout=false.
        assertFalse(callerDone.await(100, TimeUnit.MILLISECONDS))
        releaseLaunch.countDown()
        assertTrue(callerDone.await(1, TimeUnit.SECONDS))
        callbackThread.join(1_000L)
        assertTrue(result.get())
        assertEquals(1, launched.get())
    }

    @Test
    fun `game await timeout cancels a late queued callback without launching`() {
        val callbackReady = CountDownLatch(1)
        val callbacks = ConcurrentLinkedQueue<() -> Unit>()
        val launched = AtomicInteger(0)
        val result = AtomicReference<Boolean>()
        val plan = plan(UUID(0L, 805L), game = true)
        val coordinator = CompanionSecondaryLaunchCoordinator(
            preparePlan = { plan },
            persistPlan = { true },
            isCurrentPlan = { true },
            launchPlan = { launched.incrementAndGet(); true },
            failPlan = { _, _ -> },
            workScheduler = CompanionLaunchWorkScheduler { work ->
                Thread(work, "secondary-game-timeout-io").start()
                true
            },
            mainDispatcher = CompanionLaunchMainDispatcher { work ->
                callbacks.add(work)
                callbackReady.countDown()
            },
            gameHostDispatchTimeoutMs = 20L,
        )

        val prepared = coordinator.prepare(plan.packageName)!!
        val caller = Thread({ result.set(prepared.dispatchAwaitingHost()) }, "secondary-game-timeout-caller")
        caller.start()
        assertTrue(callbackReady.await(1, TimeUnit.SECONDS))
        val queuedLaunch = callbacks.poll()
        assertNotNull(queuedLaunch)
        caller.join(1_000L)
        assertFalse(result.get())

        // The main callback was already queued when the timeout claimed cancellation. It must close
        // its latch but may not dispatch the ROM host after the caller has been told it was refused.
        queuedLaunch!!.invoke()
        while (true) {
            val callback = callbacks.poll() ?: break
            callback.invoke()
        }
        assertEquals(0, launched.get())
    }

    @Test
    fun `game await persistence failure returns false and never dispatches launch`() {
        val failed = mutableListOf<String>()
        val result = AtomicReference<Boolean>()
        val callerDone = CountDownLatch(1)
        val plan = plan(UUID(0L, 806L), game = true)
        val coordinator = CompanionSecondaryLaunchCoordinator(
            preparePlan = { plan },
            persistPlan = { error("storage unavailable") },
            isCurrentPlan = { true },
            launchPlan = { error("must not launch") },
            failPlan = { _, reason -> failed += reason },
            workScheduler = CompanionLaunchWorkScheduler { work ->
                Thread(work, "secondary-game-persist-failure-io").start()
                true
            },
            mainDispatcher = CompanionLaunchMainDispatcher { work -> work() },
            gameHostDispatchTimeoutMs = 1_000L,
        )
        val prepared = coordinator.prepare(plan.packageName)!!
        Thread({
            result.set(prepared.dispatchAwaitingHost())
            callerDone.countDown()
        }, "secondary-game-persist-failure-caller").start()

        assertTrue(callerDone.await(1, TimeUnit.SECONDS))
        assertFalse(result.get())
        assertEquals(listOf("pending_operation_persist_failed"), failed)
    }

    @Test
    fun `game await launch exception settles its latch`() {
        val result = AtomicReference<Boolean>()
        val callerDone = CountDownLatch(1)
        val plan = plan(UUID(0L, 807L), game = true)
        val coordinator = CompanionSecondaryLaunchCoordinator(
            preparePlan = { plan },
            persistPlan = { true },
            isCurrentPlan = { true },
            launchPlan = { error("dispatch crashed") },
            failPlan = { _, _ -> },
            workScheduler = CompanionLaunchWorkScheduler { work ->
                Thread(work, "secondary-game-launch-failure-io").start()
                true
            },
            mainDispatcher = CompanionLaunchMainDispatcher { work -> work() },
            gameHostDispatchTimeoutMs = 1_000L,
        )
        val prepared = coordinator.prepare(plan.packageName)!!
        Thread({
            result.set(prepared.dispatchAwaitingHost())
            callerDone.countDown()
        }, "secondary-game-launch-failure-caller").start()

        assertTrue(callerDone.await(1, TimeUnit.SECONDS))
        assertFalse(result.get())
    }

    private fun coordinator(
        plan: CompanionSecondaryLaunchPlan,
        io: ArrayDeque<() -> Unit>,
        main: ArrayDeque<() -> Unit>,
        events: MutableList<String>,
        current: Boolean = true,
        persist: Boolean = true,
        failed: MutableList<Pair<UUID, String>> = mutableListOf(),
    ) = CompanionSecondaryLaunchCoordinator(
        preparePlan = {
            events += "prepare"
            plan
        },
        persistPlan = {
            events += "persist"
            persist
        },
        isCurrentPlan = {
            events += "current"
            current
        },
        launchPlan = {
            events += "launch"
            true
        },
        failPlan = { failedPlan, reason -> failed += failedPlan.operationId to reason },
        workScheduler = CompanionLaunchWorkScheduler { work ->
            events += "schedule"
            io += work
            true
        },
        mainDispatcher = CompanionLaunchMainDispatcher { work ->
            events += "main"
            main += work
        },
    )

    private fun plan(operationId: UUID, game: Boolean = false): CompanionSecondaryLaunchPlan = CompanionSecondaryLaunchPlan(
        operationId = operationId,
        operationToken = OperationId.parseOrNull(operationId.toString())!!,
        packageName = "org.example.game",
        displayId = 4,
        primaryOrigin = PrimaryReturnOrigin("LIBRARY", revision = 4L),
        stateVersion = 12L,
        launchMode = if (game) {
            com.gruning.mimir.rewrite.core.companionnative.CompanionReturnHostContract.LAUNCH_MODE_SECONDARY_GAME
        } else {
            com.gruning.mimir.rewrite.core.companionnative.CompanionReturnHostContract.LAUNCH_MODE_SECONDARY_APP
        },
    )

    private fun inertDeadlineScheduler() = LaunchDeadlineScheduler { _, _ -> LaunchDeadlineHandle {} }
}
