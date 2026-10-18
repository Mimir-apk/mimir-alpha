package com.gruning.mimir.rewrite.core.companionnative

import android.view.Display
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSecondaryTaskRegistryTest {
    @Test
    fun `secondary swap uses the coordinator prepared host token`() {
        val local = UUID(0L, 40L)
        val prepared = UUID(0L, 41L)

        val selected = swapOperationId(
            targetDisplayId = 4,
            localOperationId = { local },
            prepareSecondaryOperation = { prepared.toString() },
        )

        assertEquals(prepared, selected)
    }

    @Test
    fun `primary swap uses a nonzero dispatch token without fabricating a host`() {
        val local = UUID(0L, 42L)
        var prepared = false

        val selected = swapOperationId(
            targetDisplayId = Display.DEFAULT_DISPLAY,
            localOperationId = { local },
            prepareSecondaryOperation = {
                prepared = true
                UUID(0L, 43L).toString()
            },
        )

        assertEquals(local, selected)
        assertFalse(prepared)
        assertEquals(
            null,
            swapOperationId(
                targetDisplayId = Display.DEFAULT_DISPLAY,
                localOperationId = { UUID(0L, 0L) },
                prepareSecondaryOperation = { null },
            ),
        )
    }

    @Test
    fun `display attach publishes the newly available id`() {
        var availableDisplayId: Int? = null
        val published = mutableListOf<Int?>()
        val observer = SecondaryDisplayAvailabilityObserver(
            initialDisplayId = null,
            displayIdSource = { availableDisplayId },
            publishDisplayId = published::add,
        )

        availableDisplayId = 4

        assertTrue(observer.refresh())
        assertEquals(listOf(4), published)
        assertFalse(observer.refresh())
        assertEquals(listOf(4), published)
    }

    @Test
    fun `display detach publishes null instead of retaining the stale id`() {
        var availableDisplayId: Int? = 4
        val published = mutableListOf<Int?>()
        val observer = SecondaryDisplayAvailabilityObserver(
            initialDisplayId = 4,
            displayIdSource = { availableDisplayId },
            publishDisplayId = published::add,
        )

        availableDisplayId = null

        assertTrue(observer.refresh())
        assertEquals(listOf<Int?>(null), published)
        assertFalse(observer.refresh())
        assertEquals(listOf<Int?>(null), published)
    }

    @Test
    fun `display replacement publishes the new selected id before the next target read`() {
        var selectedDisplayId: Int? = 4
        val published = mutableListOf<Int?>()
        val observer = SecondaryDisplayAvailabilityObserver(
            initialDisplayId = 4,
            displayIdSource = { selectedDisplayId },
            publishDisplayId = published::add,
        )

        selectedDisplayId = 5

        assertTrue(observer.refresh())
        assertEquals(listOf<Int?>(5), published)
        assertFalse(observer.refresh())
    }

    //  A Bottom game used to be excluded from the
    // ledger entirely; it is a tab now, and its KIND is what keeps it from being read as an app launch.
    @Test
    fun `a secondary game launch becomes a GAME tab and every other mode an APP tab`() {
        assertEquals(
            SecondaryTaskKind.GAME,
            secondaryLedgerKindFor(CompanionReturnHostContract.LAUNCH_MODE_SECONDARY_GAME),
        )
        listOf(
            CompanionReturnHostContract.LAUNCH_MODE_SECONDARY_APP,
            CompanionReturnHostContract.LAUNCH_MODE_RESUME,
            CompanionReturnHostContract.LAUNCH_MODE_SCREEN_TRANSFER,
            CompanionReturnHostContract.LAUNCH_MODE_HOME_RECLAIM,
        ).forEach { mode ->
            assertEquals(SecondaryTaskKind.APP, secondaryLedgerKindFor(mode))
        }
    }

    // ------------------------------------------------------------------------- close escalation
    //
    // AYN Thor, 2026-09-11 09:34: the ✕ on Chrome force-stopped a whitelisted package, the command
    // was accepted, and the app stayed alive on display 4 (and could be reopened from Android Recents). The close sequence now reads liveness back and evicts the surviving task
    // instead of believing the acceptance.

    @Test
    fun `a surviving package is closed by evicting its tracked task`() {
        val removed = mutableListOf<Int>()
        val killed = mutableListOf<String>()
        val resolverCalls = mutableListOf<String>()

        val resolution = resolveSecondaryClose(
            request = closeRequest(taskId = 41),
            stop = { SecondaryPrivilegedCloseOutcome.STILL_ALIVE },
            removeTask = { taskId ->
                removed += taskId
                true
            },
            resolveTaskId = {
                resolverCalls += it
                CHROME_TASK_ID
            },
            killBackground = { killed += it },
        )

        assertEquals(SecondaryCloseResolution.TASK_REMOVED, resolution)
        assertEquals(listOf(41), removed)
        // A tracked id is already the answer; the privileged lookup is not paid for.
        assertTrue(resolverCalls.isEmpty())
        assertTrue(killed.isEmpty())
        assertTrue(resolution.closeVerified)
        assertEquals("task_removed", resolution.reason)
    }

    @Test
    fun `a survivor with no tracked id is closed by the resolved task id`() {
        val removed = mutableListOf<Int>()
        val killed = mutableListOf<String>()
        val resolverCalls = mutableListOf<String>()

        val resolution = resolveSecondaryClose(
            request = closeRequest(taskId = null),
            stop = { SecondaryPrivilegedCloseOutcome.STILL_ALIVE },
            removeTask = { taskId ->
                removed += taskId
                true
            },
            resolveTaskId = {
                resolverCalls += it
                CHROME_TASK_ID
            },
            killBackground = { killed += it },
        )

        // The Thor's Chrome block: RootTask displayId=4, taskId=2260, visible=true.
        assertEquals(listOf(TRACKED_PACKAGE), resolverCalls)
        assertEquals(listOf(CHROME_TASK_ID), removed)
        assertEquals(SecondaryCloseResolution.TASK_REMOVED, resolution)
        assertTrue(resolution.closeVerified)
        assertTrue(killed.isEmpty())
    }

    @Test
    fun `a verified stop finishes the close without touching the task`() {
        val removed = mutableListOf<Int>()
        val killed = mutableListOf<String>()
        val resolverCalls = mutableListOf<String>()

        val resolution = resolveSecondaryClose(
            request = closeRequest(taskId = 41),
            stop = { SecondaryPrivilegedCloseOutcome.STOPPED },
            removeTask = { taskId ->
                removed += taskId
                true
            },
            resolveTaskId = {
                resolverCalls += it
                CHROME_TASK_ID
            },
            killBackground = { killed += it },
        )

        assertEquals(SecondaryCloseResolution.STOPPED, resolution)
        assertTrue(removed.isEmpty())
        // Nothing survived, so no privileged task lookup happens either.
        assertTrue(resolverCalls.isEmpty())
        assertTrue(killed.isEmpty())
        assertTrue(resolution.closeVerified)
        assertEquals("process_absent", resolution.reason)
    }

    @Test
    fun `a survivor with neither a tracked nor a resolvable task id falls back and says so`() {
        val removed = mutableListOf<Int>()
        val killed = mutableListOf<String>()
        val resolverCalls = mutableListOf<String>()

        val resolution = resolveSecondaryClose(
            request = closeRequest(taskId = null),
            stop = { SecondaryPrivilegedCloseOutcome.STILL_ALIVE },
            removeTask = { taskId ->
                removed += taskId
                true
            },
            resolveTaskId = {
                resolverCalls += it
                null
            },
            killBackground = { killed += it },
        )

        assertEquals(listOf(TRACKED_PACKAGE), resolverCalls)
        assertEquals(SecondaryCloseResolution.NO_TASK_ID, resolution)
        assertEquals("no_task_id", resolution.reason)
        assertFalse(resolution.closeVerified)
        assertTrue(removed.isEmpty())
        assertEquals(listOf(TRACKED_PACKAGE), killed)
    }

    @Test
    fun `a failed eviction still runs the best-effort kill and claims nothing`() {
        val killed = mutableListOf<String>()

        val resolution = resolveSecondaryClose(
            request = closeRequest(taskId = 41),
            stop = { SecondaryPrivilegedCloseOutcome.STILL_ALIVE },
            removeTask = { false },
            resolveTaskId = { null },
            killBackground = { killed += it },
        )

        assertEquals(SecondaryCloseResolution.TASK_REMOVAL_FAILED, resolution)
        assertFalse(resolution.closeVerified)
        assertEquals(listOf(TRACKED_PACKAGE), killed)
    }

    @Test
    fun `an unverified acceptance keeps the old accepted path and an absent authority still kills`() {
        val killed = mutableListOf<String>()
        val removed = mutableListOf<Int>()
        val resolverCalls = mutableListOf<String>()

        val accepted = resolveSecondaryClose(
            request = closeRequest(taskId = 41),
            stop = { SecondaryPrivilegedCloseOutcome.REQUEST_UNVERIFIED },
            removeTask = { taskId ->
                removed += taskId
                true
            },
            resolveTaskId = {
                resolverCalls += it
                CHROME_TASK_ID
            },
            killBackground = { killed += it },
        )
        // Pre-change behaviour for an accepted stop: no best-effort kill, and no terminal claim.
        assertEquals(SecondaryCloseResolution.ACCEPTED_UNVERIFIED, accepted)
        assertFalse(accepted.closeVerified)
        assertTrue(killed.isEmpty())
        assertTrue(removed.isEmpty())

        val unavailable = resolveSecondaryClose(
            request = closeRequest(taskId = 41),
            stop = { SecondaryPrivilegedCloseOutcome.UNAVAILABLE },
            removeTask = { taskId ->
                removed += taskId
                true
            },
            resolveTaskId = {
                resolverCalls += it
                CHROME_TASK_ID
            },
            killBackground = { killed += it },
        )
        // Pre-change behaviour for a refused stop: best-effort kill, and no eviction on an unknown
        // liveness -- a task id is only spent, or even looked up, when the authority saw it survive.
        assertEquals(SecondaryCloseResolution.BEST_EFFORT, unavailable)
        assertEquals(listOf(TRACKED_PACKAGE), killed)
        assertTrue(removed.isEmpty())
        assertTrue(resolverCalls.isEmpty())
    }

    private fun closeRequest(taskId: Int?) = SecondaryCloseRequest(
        packageName = TRACKED_PACKAGE,
        operationId = UUID(0L, 61L),
        taskId = taskId,
    )

    private companion object {
        const val TRACKED_PACKAGE = "org.example.game"

        /** The id the Thor's `am stack list` reported for Chrome on display 4, 2026-09-11. */
        const val CHROME_TASK_ID = 2260
    }
}
