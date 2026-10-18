package com.gruning.mimir.rewrite

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Keyboard safety: the crash-loop safety trail of the system-default keyboard. Pure functions of the
 * persisted [ImeSafetyState]; the Robolectric service test drives the same logic through the real service.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class MimirImeSafetyTest {

    private val v1 = 100L
    private val v2 = 200L

    private fun clean(version: Long = v1) = ImeSafetyState(versionStamp = version)

    @Test
    fun aCleanStartUsesComposeAndRevertsNothing() {
        val d = imeSafetyAtCreate(clean(), v1, "p1")
        assertEquals(ImeMode.COMPOSE, d.mode)
        assertFalse(d.revertDefault)
        assertEquals(clean(), d.state)
    }

    @Test
    fun aMarkerFromADeadProcessForcesTheFallbackFromThenOn() {
        val armed = imeSafetyArm(clean(), ImeMode.COMPOSE, "dead")
        val d = imeSafetyAtCreate(armed, v1, "p2")
        assertEquals(ImeMode.FALLBACK, d.mode)
        assertTrue(d.state.fallbackForced)
        assertEquals(1, d.state.composeCrashes)
        assertNull("the marker is consumed", d.state.armedMode)
        assertFalse("one Compose death is not yet a revert", d.revertDefault)

        // Every later session stays on the fallback until the next update.
        val later = imeSafetyAtCreate(d.state, v1, "p3")
        assertEquals(ImeMode.FALLBACK, later.mode)
        assertFalse(later.revertDefault)
    }

    @Test
    fun aMarkerFromTheSameLiveProcessIsASessionNotACrash() {
        val armed = imeSafetyArm(clean(), ImeMode.COMPOSE, "live")
        val d = imeSafetyAtCreate(armed, v1, "live")
        assertEquals(ImeMode.COMPOSE, d.mode)
        assertEquals(0, d.state.composeCrashes)
        assertFalse(d.state.fallbackForced)
    }

    @Test
    fun aFallbackThatDiedRevertsTheDefaultAndStaysOnTheFallback() {
        val armed = imeSafetyArm(clean(), ImeMode.FALLBACK, "dead")
        val d = imeSafetyAtCreate(armed, v1, "p2")
        assertTrue(d.revertDefault)
        assertEquals(ImeMode.FALLBACK, d.mode)
    }

    @Test
    fun aSecondComposeDeathRevertsTheDefault() {
        val once = imeSafetyAtCreate(imeSafetyArm(clean(), ImeMode.COMPOSE, "a"), v1, "b").state
        assertEquals(1, once.composeCrashes)
        // An update lifts the fallback lock; the SAME crash coming back is the second one.
        val updated = imeSafetyAtCreate(once, v2, "c")
        assertEquals(ImeMode.COMPOSE, updated.mode)
        assertEquals("deaths are remembered across the update", 1, updated.state.composeCrashes)
        val again = imeSafetyAtCreate(imeSafetyArm(updated.state, ImeMode.COMPOSE, "c"), v2, "d")
        assertEquals(2, again.state.composeCrashes)
        assertTrue(again.revertDefault)
        assertEquals(ImeMode.FALLBACK, again.mode)
    }

    @Test
    fun anUpdateLiftsTheFallbackLockAndDropsAStaleMarker() {
        val locked = imeSafetyAtCreate(imeSafetyArm(clean(), ImeMode.COMPOSE, "a"), v1, "b").state
        assertTrue(locked.fallbackForced)
        val stale = imeSafetyArm(locked, ImeMode.COMPOSE, "old-build")
        val d = imeSafetyAtCreate(stale, v2, "new")
        assertEquals(ImeMode.COMPOSE, d.mode)
        assertFalse(d.state.fallbackForced)
        assertNull(d.state.armedMode)
        assertFalse("a marker from the old build is not a death of this one", d.revertDefault)
    }

    @Test
    fun onlyTheArmingProcessMayClearTheMarker() {
        val armed = imeSafetyArm(clean(), ImeMode.COMPOSE, "me")
        assertEquals(armed, imeSafetyDisarm(armed, "someone-else"))
        val cleared = imeSafetyDisarm(armed, "me")
        assertNull(cleared.armedMode)
        assertEquals("", cleared.armedProcess)
    }

    // ---- D38: the owed revert is on disk before the revert runs ----------------------------------------------------

    @Test
    fun theRevertDemandIsSetWithTheRevertAndOnlyThen() {
        val calm = imeSafetyAtCreate(clean(), v1, "p1")
        assertFalse(calm.state.revertDemanded)

        val oneDeath = imeSafetyAtCreate(imeSafetyArm(clean(), ImeMode.COMPOSE, "dead"), v1, "p2")
        assertFalse("one Compose death is a fallback, not a revert", oneDeath.revertDefault)
        assertFalse(oneDeath.state.revertDemanded)

        val fallbackDied = imeSafetyAtCreate(imeSafetyArm(clean(), ImeMode.FALLBACK, "dead"), v1, "p2")
        assertTrue(fallbackDied.revertDefault)
        assertTrue("the demand rides the same state the service commits before it reverts", fallbackDied.state.revertDemanded)

        val twice = imeSafetyAtCreate(imeSafetyArm(clean().copy(composeCrashes = 1), ImeMode.COMPOSE, "dead"), v1, "p2")
        assertTrue(twice.revertDefault)
        assertTrue(twice.state.revertDemanded)
    }

    @Test
    fun theRevertDemandSurvivesARestartAndAnAppUpdateButNothingInThePureTrailClearsIt() {
        val demanded = imeSafetyAtCreate(imeSafetyArm(clean(), ImeMode.FALLBACK, "dead"), v1, "p2").state
        assertTrue(demanded.revertDemanded)

        // The next process, the same build: no new crash, the debt is still owed.
        val restart = imeSafetyAtCreate(demanded, v1, "p3")
        assertFalse(restart.revertDefault)
        assertTrue(restart.state.revertDemanded)

        // An app update lifts the fallback lock (unlike this debt, which it keeps like composeCrashes).
        val updated = imeSafetyAtCreate(demanded, v2, "p4")
        assertFalse(updated.state.fallbackForced)
        assertTrue(updated.state.revertDemanded)

        // Arm and disarm carry it along untouched.
        val armed = imeSafetyArm(demanded, ImeMode.COMPOSE, "me")
        assertTrue(armed.revertDemanded)
        assertTrue(imeSafetyDisarm(armed, "me").revertDemanded)
    }

    private class MemoryTrail(var state: ImeSafetyState, var writesLand: Boolean = true) : ImeSafetyStore {
        var writes = 0
        override fun read(): ImeSafetyState = state
        override fun write(state: ImeSafetyState): Boolean {
            writes++
            if (!writesLand) return false
            this.state = state
            return true
        }
    }

    @Test
    fun onlyAClearThatReachedStorageSettlesTheDebtAndItTouchesNothingElse() {
        val armedElsewhere = clean().copy(revertDemanded = true, composeCrashes = 2, armedMode = ImeMode.COMPOSE, armedProcess = "me")
        val failing = MemoryTrail(armedElsewhere, writesLand = false)
        assertFalse("a clear that did not land is not a clear", failing.clearRevertDemand())
        assertTrue(failing.state.revertDemanded)

        val store = MemoryTrail(armedElsewhere)
        assertTrue(store.clearRevertDemand())
        assertFalse(store.state.revertDemanded)
        assertEquals("nothing but the demand changed", armedElsewhere.copy(revertDemanded = false), store.state)

        val settled = MemoryTrail(clean())
        assertTrue("nothing owed: nothing written", settled.clearRevertDemand())
        assertEquals(0, settled.writes)
    }

    @Test
    fun theKeyboardIsTrustedOnlyWhenNoRevertIsOwedAndNoCrashTrailStands() {
        assertTrue(imeKeyboardTrusted(clean(), v1, "p1"))
        assertTrue("a fresh install has no record at all", imeKeyboardTrusted(ImeSafetyState(), v1, "p1"))

        // An owed revert, however old.
        assertFalse(imeKeyboardTrusted(clean().copy(revertDemanded = true), v1, "p1"))
        assertFalse("...even across an app update", imeKeyboardTrusted(clean().copy(revertDemanded = true), v2, "p1"))

        // A marker a DEAD process left: that crash is still to be counted, so nothing automatic yet.
        assertFalse(imeKeyboardTrusted(imeSafetyArm(clean(), ImeMode.COMPOSE, "dead"), v1, "p2"))
        assertFalse(imeKeyboardTrusted(imeSafetyArm(clean(), ImeMode.FALLBACK, "dead"), v1, "p2"))
        // A marker THIS process armed is a live session, not a crash.
        assertTrue(imeKeyboardTrusted(imeSafetyArm(clean(), ImeMode.COMPOSE, "p1"), v1, "p1"))

        // The fallback is forced until the next update: not trusted before it, trusted after it.
        val forced = clean().copy(fallbackForced = true, composeCrashes = 1)
        assertFalse(imeKeyboardTrusted(forced, v1, "p1"))
        assertTrue(imeKeyboardTrusted(forced, v2, "p1"))

        // A stale marker from the previous build is not a death of this one.
        assertTrue(imeKeyboardTrusted(imeSafetyArm(clean(v1), ImeMode.COMPOSE, "old-build"), v2, "new"))
    }

    @Test
    fun theTrustCheckNeverWritesAnything() {
        val trail = MemoryTrail(imeSafetyArm(clean(), ImeMode.COMPOSE, "dead"))
        val before = trail.state
        imeKeyboardTrusted(trail.read(), v1, "p2")
        assertEquals(before, trail.state)
        assertEquals(0, trail.writes)
    }

    @Test
    fun aKeyboardThatDrewAndStayedUpLeavesNoTrail() {
        // arm -> (frame drawn, stable) -> disarm -> the next start sees nothing.
        val state = imeSafetyDisarm(imeSafetyArm(clean(), ImeMode.COMPOSE, "me"), "me")
        val d = imeSafetyAtCreate(state, v1, "next-process")
        assertEquals(ImeMode.COMPOSE, d.mode)
        assertEquals(0, d.state.composeCrashes)
    }

    @Test
    fun anArmOrDisarmCannotWriteBackARevertDemandClearedSinceItsRead() {
        val context: Context = Robolectric.setupActivity(android.app.Activity::class.java)
        val store = SharedPrefsImeSafetyStore(context)

        // Write a state with revertDemanded = true
        val initial = clean().copy(revertDemanded = true)
        store.write(initial)
        assertEquals("sanity: the state has revertDemanded", true, store.read().revertDemanded)

        // Read it (stale)
        val stale = store.read()
        assertTrue("sanity: the stale read has revertDemanded", stale.revertDemanded)

        // Clear the demand
        store.clearRevertDemand()
        assertFalse("demand was cleared", store.read().revertDemanded)

        // Now arm with the stale state. Using writeArmed (not write) must NOT clobber the cleared demand.
        store.writeArmed(imeSafetyArm(stale, ImeMode.COMPOSE, "tokenA"))
        assertFalse("revertDemanded is still false after arm", store.read().revertDemanded)
        assertEquals("armedProcess was written", "tokenA", store.read().armedProcess)
        assertEquals("armedMode was written", ImeMode.COMPOSE, store.read().armedMode)

        // Now disarm with the stale state. The demand must still be false.
        store.writeArmed(imeSafetyDisarm(stale.copy(armedProcess = "tokenA"), "tokenA"))
        assertFalse("revertDemanded is still false after disarm", store.read().revertDemanded)
        assertEquals("armedProcess was cleared", "", store.read().armedProcess)
        assertNull("armedMode was cleared", store.read().armedMode)
    }
}
