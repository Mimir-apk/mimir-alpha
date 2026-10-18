package com.gruning.mimir.rewrite.feature.companion

import com.gruning.mimir.rewrite.core.model.CompanionAppEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **The defect these pin, stated as the thing that must never be true again:** a seat whose MARK, whose
 * spoken NAME and whose TAP disagree about what it is.
 *
 * The rail drew from `entry` and dispatched from `pkg`. `CompanionQuickLaunchCatalog.entries` starts
 * empty in every process and fills from a background sweep, so through the whole cold window every
 * pinned seat had a null `entry` and a non-null `pkg` — it drew `+`, told TalkBack *"Pin an app"*, and
 * launched anyway. Not an edge case: the first frames of every session.
 *
 * The table below is written as FACTS, not recomputed from the function under test. A test that derives
 * its expectation the same way the code derives its answer agrees with the code whatever the code says.
 */
class CompanionQuickSeatPlanTest {
    private val entry = CompanionAppEntry("org.example.game", "Example", null)

    @Test
    fun aResolvedPinIsTheAppItNames() {
        val plan = companionQuickSeatPlan("org.example.game", entry, catalogLoaded = true)

        assertEquals(CompanionQuickSeatFace.APP, plan.face)
        assertEquals("org.example.game", plan.launchPackage)
        assertEquals("Example", plan.name)
        assertFalse(plan.opensPicker)
    }

    @Test
    fun anUnsweptPinIsOccupiedAndStillLaunches() {
        val plan = companionQuickSeatPlan("org.example.game", entry = null, catalogLoaded = false)

        // **The whole bug in one assertion.** This was EMPTY, and EMPTY wears `+`.
        assertEquals(CompanionQuickSeatFace.PENDING, plan.face)
        // The pin is real, so the press must still work — the fix must not trade one lie for a dead seat.
        assertEquals("org.example.game", plan.launchPackage)
        assertFalse(plan.opensPicker)
        // And it must not claim to be unpinned while it is pinned.
        assertEquals(CompanionQuickSeatPendingName, plan.name)
        assertTrue("a pending seat must not be named as an empty one", plan.name != CompanionQuickSeatEmptyName)
    }

    @Test
    fun anEmptySeatOpensThePicker() {
        listOf(null, "", "   ").forEach { pin ->
            val plan = companionQuickSeatPlan(pin, entry = null, catalogLoaded = true)

            assertEquals("pin=<$pin>", CompanionQuickSeatFace.EMPTY, plan.face)
            assertNull("pin=<$pin>", plan.launchPackage)
            assertTrue("pin=<$pin>", plan.opensPicker)
            assertEquals("pin=<$pin>", CompanionQuickSeatEmptyName, plan.name)
        }
    }

    @Test
    fun aPinWhoseAppIsGoneReadsEmptyAndDoesNotLaunch() {
        // `QuickLaunchStore` never prunes, so this state is PERMANENT once a pinned app is uninstalled —
        // not a rare race. The sweep has finished and this package was not in the answer.
        val plan = companionQuickSeatPlan("org.example.gone", entry = null, catalogLoaded = true)

        assertEquals(CompanionQuickSeatFace.EMPTY, plan.face)
        // A seat wearing `+` must never fire a launch. It did, and the launch could only fail.
        assertNull(plan.launchPackage)
        assertTrue(plan.opensPicker)
    }

    /**
     * The structural property, checked across every combination rather than at the four points above: a
     * seat launches **exactly** when it is not wearing `+`. This is what makes mark and dispatch
     * incapable of disagreeing again — one field decides both, and this asserts the field's meaning.
     */
    @Test
    fun everySeatThatWearsThePlusOpensThePickerAndEveryOtherSeatLaunches() {
        val pins = listOf(null, "", "org.example.game", "org.example.gone")
        val entries = listOf(null, entry)
        val loaded = listOf(true, false)

        pins.forEach { pin ->
            entries.forEach { resolved ->
                loaded.forEach { swept ->
                    val plan = companionQuickSeatPlan(pin, resolved, swept)
                    val case = "pin=<$pin> entry=${resolved?.packageName} loaded=$swept"

                    assertEquals(
                        "$case: EMPTY must open the picker, everything else must launch",
                        plan.face == CompanionQuickSeatFace.EMPTY,
                        plan.opensPicker,
                    )
                    // A launching seat must never be nameless, or the hold-line and TalkBack go silent.
                    assertTrue("$case: name must not be blank", plan.name.isNotBlank())
                }
            }
        }
    }
}
