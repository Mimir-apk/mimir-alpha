package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task A, floor first: which reclaims bring Mimir's secondary-home floor to the front of the display before the restore
 * guard finishes, and what refuses the request. Thor, builds afbd7af3 and 5fa4c795, 2026-09-11: after an edge-swipe
 * reclaim the guard finished and YouTube stayed RESUMED as display 4's top with that display's key focus while the floor
 * sat STOPPED; two DPAD_RIGHT keys changed 0 / 3,075 / 0 px on Main, against 10,476 / 403,598 / 1,119 with the floor
 * focused . Pure pins only; the device result
 * is owed to the Thor pass.
 */
class CompanionFloorFrontDecisionTest {

    private val floorClass = "com.gruning.mimir.rewrite.CompanionSecondaryHomeActivity"

    @Test
    fun `the edge swipe menu return tab close and primary-resume recovery ask for the floor`() {
        listOf(
            "overlay_edge_back",
            "secondary_menu_return_to_shelf",
            "tab_close_reclaim",
            "primary_activity_resumed",
        ).forEach { reason ->
            assertTrue("$reason must bring the floor forward before the guard finishes", reclaimRequestsFloorFront(reason))
        }
    }

    @Test
    fun `reasons with work queued behind the settle and unknown reasons keep today's behaviour`() {
        listOf(
            "secondary_home_resumed" to "the floor itself is already the resumed top of the display",
            "overlay_swap_to_primary" to "the Main transfer is dispatched first; a floor start would pull top focus back to the Bottom",
            "overlay_swap_exchange" to "leg 2 starts a Bottom launch host right after this settle",
            "presentation_window_revealed" to "an inferred departure; the app may have moved itself to Main",
            "secondary_app_return" to "the app left on its own; not an explicit reclaim",
            "explicit_reclaim" to "the blank-reason default",
            "manual_reclaim" to "an adapter default with no callers",
            "OVERLAY_EDGE_BACK" to "reasons are exact strings",
            "" to "blank",
        ).forEach { (reason, why) ->
            assertFalse("'$reason' must not request the floor: $why", reclaimRequestsFloorFront(reason))
        }
    }

    @Test
    fun `every reason that asks for the floor is still the literal its caller passes`() {
        val file = File("src/main/kotlin/com/gruning/mimir/rewrite/MainActivity.kt")
        assertTrue("MainActivity.kt must be readable from the app module dir", file.isFile)
        val main = file.readText()
        listOf(
            "restoreCompanionFromOverlay(\"overlay_edge_back\")",
            "reason = \"secondary_menu_return_to_shelf\"",
            "restore(\"tab_close_reclaim\"",
        ).forEach { call ->
            assertTrue("MainActivity no longer contains $call, so the floor allow-list would silently stop matching", call in main)
        }
        // The primary-resume reclaim moved behind the shared budget (P1, combined43 sweep 2026-10-15): MainActivity asks the
        // coordinator, and the coordinator passes the literal.
        val coordinator = File("src/main/kotlin/com/gruning/mimir/rewrite/CompanionReturnCoordinator.kt").readText()
        assertTrue(
            "CompanionReturnCoordinator no longer passes the primary-resume literal, so the floor allow-list would silently stop matching",
            "explicitReclaim(reason = \"primary_activity_resumed\"" in coordinator,
        )
        assertTrue(
            "and MainActivity still routes its resume reclaim through the budgeted entry",
            "CompanionReturnCoordinator.reclaimOnPrimaryResume(" in main,
        )
    }

    @Test
    fun `the request is refused for display 0 a stale display another resolution or a floor the platform never placed`() {
        fun refusal(
            target: Int = 4,
            selected: Int? = 4,
            valid: Boolean = true,
            resolved: String? = floorClass,
            present: Boolean = true,
        ) = floorFrontPrecheckFailure(target, selected, valid, resolved, present)

        assertEquals("default_display", refusal(target = 0, selected = 0))
        assertEquals("display_unavailable", refusal(selected = null))
        assertEquals("display_unavailable", refusal(selected = 5))
        assertEquals("display_unavailable", refusal(valid = false))
        assertEquals("floor_unresolved", refusal(resolved = null))
        assertEquals("floor_unresolved", refusal(resolved = "com.gruning.mimir.rewrite.MainActivity"))
        assertEquals("floor_unresolved", refusal(resolved = "com.android.internal.app.ResolverActivity"))
        assertEquals("floor_task_absent", refusal(present = false))
        assertNull("a selected valid secondary display whose floor resolves and exists may be asked", refusal())
    }

    // ── F12: an absent floor is recreated, once, and only after every other check passed ─────────────────────────────────────────

    private fun absentPlan(
        throttle: FloorRecreateThrottle,
        nowMillis: Long,
        target: Int = 4,
        selected: Int? = 4,
        valid: Boolean = true,
        resolved: String? = floorClass,
        present: Boolean = false,
        seen: Boolean = true,
    ) = planFloorFront(floorFrontPrecheckFailure(target, selected, valid, resolved, present), target, seen, throttle, nowMillis)

    @Test
    fun `after an install killed the floor the edge-back reclaim starts it again on the reclaimed display`() {
        val fixture = Fixture().mirrored()
        fixture.reclaimToRender("overlay_edge_back")
        val lease = requireNotNull(fixture.floorFrontFor(fixture.expectedRender()))

        val plan = planFloorFront(
            floorFrontPrecheckFailure(lease.displayId, 4, true, floorClass, floorTaskPresent = false),
            lease.displayId,
            floorSeenBefore = true,
            throttle = FloorRecreateThrottle(),
            nowMillis = 1_000L,
        )

        assertNull("no refusal: the start is issued", plan.refusal)
        assertTrue(plan.recreate)
        assertEquals(
            "the one log line says the floor was absent and this start creates it",
            "floor front requested display=4 reason=overlay_edge_back op=${lease.operationId} result=started:recreated",
            floorFrontLogLine(lease.displayId, lease.reason, lease.operationId, plan.started),
        )
    }

    @Test
    fun `a floor that exists is the ordinary start and uses none of the recreate budget`() {
        val throttle = FloorRecreateThrottle()
        val present = planFloorFront(floorFrontPrecheckFailure(4, 4, true, floorClass, true), 4, floorSeenBefore = true, throttle = throttle, nowMillis = 1_000L)
        assertNull(present.refusal)
        assertFalse(present.recreate)
        assertEquals("started", present.started)
        assertTrue("the budget is untouched, so a later absent floor can still be recreated", absentPlan(throttle, 1_001L).recreate)
    }

    @Test
    fun `no loop - a second recreate on the same display inside the window is refused and the reclaim presents anyway`() {
        val throttle = FloorRecreateThrottle(windowMillis = 10_000L)
        assertTrue("the first attempt", absentPlan(throttle, nowMillis = 100_000L).recreate)

        val again = absentPlan(throttle, nowMillis = 105_000L)
        assertEquals("a floor that did not survive its own start is not started over and over", "recreate_throttled", again.refusal)
        assertFalse(again.recreate)
        val root = OperationId.create()
        assertEquals(
            "the refusal logs the way every other refusal does, and presenting goes on",
            "floor front requested display=4 reason=overlay_edge_back op=$root result=failed:recreate_throttled",
            floorFrontLogLine(4, "overlay_edge_back", root, "failed:${again.refusal}"),
        )

        assertTrue("a reclaim a user's moment later gets its attempt", absentPlan(throttle, nowMillis = 110_001L).recreate)
    }

    @Test
    fun `another display has its own attempt`() {
        val throttle = FloorRecreateThrottle(windowMillis = 10_000L)
        assertTrue(planFloorFront(FloorTaskAbsentRefusal, displayId = 4, floorSeenBefore = true, throttle = throttle, nowMillis = 1_000L).recreate)
        assertTrue(planFloorFront(FloorTaskAbsentRefusal, displayId = 5, floorSeenBefore = true, throttle = throttle, nowMillis = 1_500L).recreate)
        assertFalse(planFloorFront(FloorTaskAbsentRefusal, displayId = 4, floorSeenBefore = true, throttle = throttle, nowMillis = 2_000L).recreate)
    }

    @Test
    fun `a clock that went backwards does not wedge the window open`() {
        val throttle = FloorRecreateThrottle(windowMillis = 10_000L)
        assertTrue(absentPlan(throttle, nowMillis = 50_000L).recreate)
        assertTrue("an earlier reading than the last attempt is a new boot's clock, not a reason to refuse", absentPlan(throttle, nowMillis = 3_000L).recreate)
    }

    @Test
    fun `every other refusal still refuses before the recreate and spends none of its budget`() {
        val throttle = FloorRecreateThrottle()
        listOf(
            "default_display" to absentPlan(throttle, 1_000L, target = 0, selected = 0),
            "display_unavailable" to absentPlan(throttle, 1_000L, selected = null),
            "display_unavailable" to absentPlan(throttle, 1_000L, valid = false),
            "floor_unresolved" to absentPlan(throttle, 1_000L, resolved = "com.android.internal.app.ResolverActivity"),
            "floor_unresolved" to absentPlan(throttle, 1_000L, resolved = null),
        ).forEach { (refusal, plan) ->
            assertEquals("a missing display or floor class plus a missing task is a refusal, not a recreate", refusal, plan.refusal)
            assertFalse(plan.recreate)
        }
        assertTrue("and none of those spent the attempt", absentPlan(throttle, 1_001L).recreate)
    }

    @Test
    fun `the request plans before it starts and says whether it recreated`() {
        val source = File("src/main/kotlin/com/gruning/mimir/rewrite/CompanionDisplayController.kt").readText()
        val start = source.indexOf("private fun requestFloorFront(")
        assertTrue(start >= 0)
        val body = source.substring(start, source.indexOf("private fun resolvedFloorClassName(", start))
        val plan = body.indexOf("planFloorFront(floorFrontPrecheckFailure(")
        assertTrue("the precheck's answer goes through the plan", plan >= 0)
        assertTrue("before the start", plan < body.indexOf("startActivity("))
        assertTrue("the logged result is the plan's", "plan.started" in body)
    }

    @Test
    fun `a display where no floor was ever seen is refused, and the refusal spends none of the throttle`() {
        val throttle = FloorRecreateThrottle()
        val never = absentPlan(throttle, nowMillis = 1_000L, seen = false)
        assertEquals("a presentation display the platform never places a secondary home on could hand the start to display 0", "floor_never_seen", never.refusal)
        assertFalse(never.recreate)
        assertTrue("and once the floor has been seen there, the same reclaim recreates", absentPlan(throttle, nowMillis = 1_001L, seen = true).recreate)
        assertNull("a floor that exists never asks whether it was seen", planFloorFront(null, 4, floorSeenBefore = false).refusal)
    }

    @Test
    fun `the floor refuses the default display and nothing else`() {
        assertTrue("display 0 is Main", CompanionFloorDisplayGuard.refuses(0))
        assertFalse("display 4 is the Bottom", CompanionFloorDisplayGuard.refuses(4))
        assertFalse("an unknown display is not evidence of a misroute", CompanionFloorDisplayGuard.refuses(-1))
    }

    @Test
    fun `the recreate lives in its own file, not in the runtime that is at its size budget`() {
        val runtime = File("src/main/kotlin/com/gruning/mimir/rewrite/CompanionProcessRuntime.kt").readText()
        assertFalse("planFloorFront" in runtime)
        assertTrue(File("src/main/kotlin/com/gruning/mimir/rewrite/CompanionFloorRecreate.kt").isFile)
    }

    @Test
    fun `the floor class the precheck expects is the real floor`() {
        assertEquals(floorClass, CompanionSecondaryHomeActivity::class.java.name)
    }

    @Test
    fun `the log line names display reason reclaim root and result`() {
        val root = OperationId.create()
        assertEquals(
            "floor front requested display=4 reason=overlay_edge_back op=$root result=started",
            floorFrontLogLine(4, "overlay_edge_back", root, "started"),
        )
        assertEquals(
            "floor front requested display=4 reason=tab_close_reclaim op=$root result=failed:floor_task_absent",
            floorFrontLogLine(4, "tab_close_reclaim", root, "failed:floor_task_absent"),
        )
    }

    @Test
    fun `the runtime names the lease only for the render about to settle while the guard still waits`() {
        val fixture = Fixture().mirrored()
        val lease = fixture.reclaimToRender("overlay_edge_back")
        val render = fixture.expectedRender()
        val before = fixture.runtime.snapshot.value
        val guard = ReturnHostExecutionState(
            phase = ReturnHostActivityPhase.RECLAIM_REQUESTED,
            settlementOperationId = lease.operationId,
        )

        assertEquals(lease, fixture.floorFrontFor(render))
        assertSame("asking must publish nothing", before, fixture.runtime.snapshot.value)
        assertEquals(
            "while the runtime names the lease the restore guard's own decision is KEEP, so it has not finished",
            ReturnHostDispositionDecision.KEEP,
            fixture.guardDecision(guard, lease),
        )

        assertTrue(fixture.runtime.presentationShown(render.operationId, render.parentDispositionId, render.rendererOwnerId))
        assertEquals(
            "the settle publish is what completes the guard",
            ReturnHostDispositionDecision.COMPLETE,
            fixture.guardDecision(guard, lease),
        )
        assertNull("a settled render cannot ask again", fixture.floorFrontFor(render))
        assertFalse(
            "the floor's own onResume after it comes forward stays a no-op (noteSecondaryHomeResumed)",
            secondaryHomeResumeShouldReclaim(fixture.runtime.snapshot.value.disposition),
        )
    }

    @Test
    fun `a swap to Main settles without naming the floor`() {
        val fixture = Fixture().mirrored()
        fixture.reclaimToRender("overlay_swap_to_primary")
        assertNull(fixture.floorFrontFor(fixture.expectedRender()))
    }

    @Test
    fun `only the exact render the settle accepts can name the lease`() {
        val fixture = Fixture().mirrored()
        fixture.reclaimToRender("overlay_edge_back")
        val render = fixture.expectedRender()
        val stranger = RendererOwnerId.create()
        assertNull(fixture.runtime.floorFrontReclaim(OperationId.create(), render.parentDispositionId, render.rendererOwnerId))
        assertNull(fixture.runtime.floorFrontReclaim(render.operationId, OperationId.create(), render.rendererOwnerId))
        assertNull(fixture.runtime.floorFrontReclaim(render.operationId, render.parentDispositionId, stranger))
        assertFalse(fixture.runtime.presentationShown(render.operationId, render.parentDispositionId, stranger))
    }

    @Test
    fun `presenting the display without a reclaim names nothing`() {
        val fixture = Fixture()
        assertNull(fixture.floorFrontFor(fixture.expectedRender()))
    }

    private class Fixture {
        val runtime = CompanionRuntimeCoordinator(CompanionRuntimeScheduler { _, _, _ -> RuntimeCancellation {} })
        private val port = object : CompanionHostPort {
            override val rendererOwnerId: RendererOwnerId = RendererOwnerId.create()
            override fun render(attempt: RenderAttempt) = Unit
            override fun dismissPresentation() = Unit
            override fun dismissPresentationAfterCover(hostOperationId: OperationId, delayMillis: Long) = Unit
            override fun prepareForReclaim(lease: ReclaimLease) = Unit
            override fun launchRestoreGuard(lease: ReclaimLease): Boolean = true
        }

        init {
            assertTrue(runtime.bindRenderer(port))
            runtime.displayAvailable(4)
        }

        fun expectedRender(): RenderAttempt = when (val state = runtime.snapshot.value.disposition) {
            is SecondaryDisplayDisposition.Presenting -> state.attempt
            is SecondaryDisplayDisposition.ReclaimPending -> requireNotNull(state.expectedRender) { "no reclaim render in $state" }
            else -> error("no expected render in $state")
        }

        fun mirrored(): Fixture = apply {
            val attempt = expectedRender()
            assertTrue(runtime.presentationShown(attempt.operationId, attempt.parentDispositionId, attempt.rendererOwnerId))
        }

        fun reclaimToRender(reason: String): ReclaimLease {
            val lease = requireNotNull(runtime.explicitReclaim(reason, PrimaryReturnOrigin("library")))
            assertTrue(runtime.restoreGuardForeground(lease.operationId))
            return lease
        }

        fun floorFrontFor(render: RenderAttempt): ReclaimLease? =
            runtime.floorFrontReclaim(render.operationId, render.parentDispositionId, render.rendererOwnerId)

        fun guardDecision(guard: ReturnHostExecutionState, lease: ReclaimLease): ReturnHostDispositionDecision =
            guard.dispositionDecision(lease.operationId, runtime.snapshot.value) { runtime.recognizes(it) }
    }
}
