package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The writes that gate an automatic attempt (the intent recorded before the bridge is asked, and every OFF) are durable
 * and checked. A write that does not reach storage sends nothing to the bridge and latches the automatic path off for
 * the rest of the process, so a death or a full disk can never leave the record behind the keyboard that was set.
 */
class MimirKeyboardDefaultDurabilityTest {

    private val productId = "com.gruning.mimir.rewrite/.MimirInputMethodService"
    private val other = "com.google.android.inputmethod.latin/.LatinIME"

    /** Persists only while [failWrites] is false; a failed write changes nothing, like a commit that did not land. */
    private class FlakyStore(
        override var enabled: Boolean = true,
        override var wasDefault: Boolean = false,
        var failWrites: Boolean = true,
    ) : MimirKeyboardDefaultStore {
        var writes = 0

        override fun write(enabled: Boolean?, wasDefault: Boolean?): Boolean {
            writes++
            if (failWrites) return false
            if (enabled != null) this.enabled = enabled
            if (wasDefault != null) this.wasDefault = wasDefault
            return true
        }
    }

    private class CountingGateway : PrivilegedKeyboardDefaultGateway {
        var sets = 0
        var resets = 0
        override fun setMimirKeyboardDefault(shouldContinue: () -> Boolean): KeyboardDefaultResult {
            sets++
            return KeyboardDefaultResult.APPLIED
        }

        override fun resetKeyboardDefault(): KeyboardDefaultResult {
            resets++
            return KeyboardDefaultResult.APPLIED
        }
    }

    private fun controller(store: MimirKeyboardDefaultStore, gateway: PrivilegedKeyboardDefaultGateway, current: () -> String? = { other }) =
        MimirKeyboardDefaultController(store, gateway, { true }, current, productId)

    @Test
    fun anIntentThatCannotBeWrittenSendsNothingToTheBridgeAndNothingAutomaticRunsAgain() {
        val store = FlakyStore()
        val gateway = CountingGateway()
        val controller = controller(store, gateway)
        assertEquals(KeyboardDefaultStep.NOTHING, controller.onStart())
        assertEquals("the bridge received nothing", 0, gateway.sets)
        assertFalse("and no intent was recorded", store.wasDefault)

        // The disk recovers, but this process stays off the automatic path.
        store.failWrites = false
        assertEquals(KeyboardDefaultStep.NOTHING, controller.onStart())
        assertEquals(0, gateway.sets)
        assertFalse(store.wasDefault)
    }

    @Test
    fun anOffThatCannotBeWrittenNeverTurnsIntoAnAutomaticAttempt() {
        // The player switched away; recording OFF fails.
        val store = FlakyStore(enabled = true, wasDefault = true)
        val gateway = CountingGateway()
        val controller = controller(store, gateway)
        assertEquals(KeyboardDefaultStep.TURN_PREFERENCE_OFF, controller.onStart())
        store.failWrites = false
        assertEquals("nothing automatic follows", KeyboardDefaultStep.NOTHING, controller.onStart())
        assertEquals(0, gateway.sets)
        assertEquals(0, gateway.resets)
    }

    @Test
    fun theSwitchTurnedOnSendsNothingWhenItsIntentCannotBeWritten() {
        val store = FlakyStore(enabled = false)
        val gateway = CountingGateway()
        assertEquals(KeyboardDefaultResult.FAILED, controller(store, gateway).turnOn())
        assertEquals(0, gateway.sets)
        assertFalse("nothing reads ON afterwards", store.enabled)
    }

    @Test
    fun theSwitchTurnedOffSendsNoResetWhenItsOffCannotBeWritten() {
        val store = FlakyStore(enabled = true, wasDefault = true)
        val gateway = CountingGateway()
        assertEquals(KeyboardDefaultResult.FAILED, controller(store, gateway, current = { productId }).turnOff())
        assertEquals(0, gateway.resets)
    }

    // ---- D38: a revert whose OFF never landed is owed, and an untrusted keyboard is never applied automatically ------

    private val stamp = 1L

    /** The keyboard-safety trail on disk; a write that does not land changes nothing, like a commit that failed. */
    private class MemoryTrail(var state: ImeSafetyState = ImeSafetyState(versionStamp = 1L), var writesLand: Boolean = true) :
        ImeSafetyStore {
        var writes = 0
        override fun read(): ImeSafetyState = state
        override fun write(state: ImeSafetyState): Boolean {
            writes++
            if (!writesLand) return false
            this.state = state
            return true
        }
    }

    /** A fresh controller as a NEW process builds it: its own session (the latch is gone), the same stores. */
    private fun restarted(
        store: MimirKeyboardDefaultStore,
        gateway: PrivilegedKeyboardDefaultGateway,
        trail: ImeSafetyStore,
        token: String = "process-2",
        current: () -> String? = { other },
    ) = MimirKeyboardDefaultController(
        store, gateway, { true }, current, productId, KeyboardApplySession(),
        safetyTrail = trail,
        keyboardTrusted = { imeKeyboardTrusted(trail.read(), stamp, token) },
    )

    @Test
    fun aFailedDoubleOffFollowedByAProcessRestartCannotReEnableAutomaticApply() {
        // The service committed the demand, then revertForSafety's OFF failed twice: the stored preference still reads ON
        // and nothing says the player ever switched away.
        val trail = MemoryTrail(ImeSafetyState(versionStamp = 1L, revertDemanded = true))
        val store = FlakyStore(enabled = true, wasDefault = false, failWrites = true)
        assertFalse(store.write(enabled = false, wasDefault = false))
        assertFalse(store.write(enabled = false, wasDefault = false))
        val gateway = CountingGateway()

        // The process died. Restart 1: the disk still refuses everything.
        assertEquals(KeyboardDefaultStep.NOTHING, restarted(store, gateway, trail).onStart())
        assertEquals("no apply", 0, gateway.sets)
        assertFalse("no intent recorded for an apply that never ran", store.wasDefault)
        assertTrue("the debt is still owed", trail.state.revertDemanded)

        // Restart 2: the disk takes writes again. The OFF is retried and lands; only then is the debt settled.
        store.failWrites = false
        assertEquals(KeyboardDefaultStep.NOTHING, restarted(store, gateway, trail).onStart())
        assertEquals(0, gateway.sets)
        assertFalse("the owed OFF landed", store.enabled)
        assertFalse(store.wasDefault)
        assertFalse("and only then is the demand cleared", trail.state.revertDemanded)

        // Restart 3 and every later one: still no apply (the stored preference is an explicit OFF now).
        assertEquals(KeyboardDefaultStep.NOTHING, restarted(store, gateway, trail).onStart())
        assertEquals(0, gateway.sets)
    }

    @Test
    fun anOwedRevertRetriesTheOffAndKeepsTheDemandUntilItReachesStorage() {
        val trail = MemoryTrail(ImeSafetyState(versionStamp = 1L, revertDemanded = true))
        val store = FlakyStore(enabled = true, wasDefault = true, failWrites = true)
        val gateway = CountingGateway()

        restarted(store, gateway, trail).onStart()
        assertTrue("the OFF was attempted at start", store.writes >= 1)
        assertTrue("a write that did not land does not settle the debt", trail.state.revertDemanded)
        assertEquals("and nothing else was written to the trail", 0, trail.writes)

        store.failWrites = false
        restarted(store, gateway, trail).onStart()
        assertFalse(store.enabled)
        assertFalse(store.wasDefault)
        assertFalse(trail.state.revertDemanded)
        assertEquals(0, gateway.sets)
    }

    @Test
    fun aClearThatDidNotReachStorageLeavesTheDemandAndTheNextStartRetriesIt() {
        val trail = MemoryTrail(ImeSafetyState(versionStamp = 1L, revertDemanded = true), writesLand = false)
        val store = FlakyStore(enabled = true, failWrites = false)
        val gateway = CountingGateway()

        restarted(store, gateway, trail).onStart()
        assertFalse("the OFF landed", store.enabled)
        assertTrue("the demand did not clear", trail.state.revertDemanded)
        assertEquals("and the owed state still refuses an apply", 0, gateway.sets)

        trail.writesLand = true
        restarted(store, gateway, trail).onStart()
        assertFalse(trail.state.revertDemanded)
    }

    @Test
    fun anArmedMarkerFromAnotherProcessRefusesTheAutomaticApplyAndWritesNothing() {
        val trail = MemoryTrail(imeSafetyArm(ImeSafetyState(versionStamp = 1L), ImeMode.COMPOSE, "dead-process"))
        val store = FlakyStore(failWrites = false)
        val gateway = CountingGateway()

        assertEquals(KeyboardDefaultStep.NOTHING, restarted(store, gateway, trail, token = "process-2").onStart())
        assertEquals(0, gateway.sets)
        assertEquals("not one write to the preference store", 0, store.writes)
        assertFalse(store.wasDefault)
        assertEquals("the trail is only read, never written", 0, trail.writes)
        assertEquals("and the marker is still there for the service to count", ImeMode.COMPOSE, trail.state.armedMode)
    }

    @Test
    fun aMarkerFromTheSameProcessIsALiveSessionAndAppliesAsBefore() {
        val trail = MemoryTrail(imeSafetyArm(ImeSafetyState(versionStamp = 1L), ImeMode.COMPOSE, "process-2"))
        val store = FlakyStore(failWrites = false)
        var platform: String? = other
        val gateway = object : PrivilegedKeyboardDefaultGateway {
            var sets = 0
            override fun setMimirKeyboardDefault(shouldContinue: () -> Boolean): KeyboardDefaultResult {
                sets++
                platform = productId
                return KeyboardDefaultResult.APPLIED
            }
        }
        assertEquals(
            KeyboardDefaultStep.APPLY,
            restarted(store, gateway, trail, token = "process-2", current = { platform }).onStart(),
        )
        assertEquals(1, gateway.sets)
        assertTrue(store.enabled)
    }

    @Test
    fun aForcedFallbackRefusesTheAutomaticApplyUntilTheNextUpdateOnly() {
        val forced = ImeSafetyState(versionStamp = 1L, fallbackForced = true, composeCrashes = 1)
        val gateway = CountingGateway()
        assertEquals(
            KeyboardDefaultStep.NOTHING,
            restarted(FlakyStore(failWrites = false), gateway, MemoryTrail(forced)).onStart(),
        )
        assertEquals(0, gateway.sets)

        // The next build lifts the lock: the same trail, a newer version stamp.
        val updated = MemoryTrail(forced)
        val store = FlakyStore(failWrites = false)
        var platform: String? = other
        val applying = object : PrivilegedKeyboardDefaultGateway {
            override fun setMimirKeyboardDefault(shouldContinue: () -> Boolean): KeyboardDefaultResult {
                platform = productId
                return KeyboardDefaultResult.APPLIED
            }
        }
        val controller = MimirKeyboardDefaultController(
            store, applying, { true }, { platform }, productId, KeyboardApplySession(),
            safetyTrail = updated,
            keyboardTrusted = { imeKeyboardTrusted(updated.read(), 2L, "process-2") },
        )
        assertEquals(KeyboardDefaultStep.APPLY, controller.onStart())
    }

    @Test
    fun aTrustedTrailChangesNothingAboutTheApply() {
        val trail = MemoryTrail(ImeSafetyState(versionStamp = 1L))
        val store = FlakyStore(failWrites = false)
        var platform: String? = other
        var intentBeforeBridge = false
        val gateway = object : PrivilegedKeyboardDefaultGateway {
            override fun setMimirKeyboardDefault(shouldContinue: () -> Boolean): KeyboardDefaultResult {
                intentBeforeBridge = store.wasDefault
                platform = productId
                return KeyboardDefaultResult.APPLIED
            }
        }
        assertEquals(KeyboardDefaultStep.APPLY, restarted(store, gateway, trail, current = { platform }).onStart())
        assertTrue(intentBeforeBridge)
        assertTrue(store.enabled)
        assertEquals("nothing was owed, so the trail was never written", 0, trail.writes)
    }

    @Test
    fun theSwitchTurnedOnIsTheStandingExplicitChoiceAndSupersedesAnOwedRevert() {
        val trail = MemoryTrail(ImeSafetyState(versionStamp = 1L, revertDemanded = true, fallbackForced = true, composeCrashes = 2))
        val store = FlakyStore(enabled = false, failWrites = false)
        var platform: String? = other
        val gateway = object : PrivilegedKeyboardDefaultGateway {
            override fun setMimirKeyboardDefault(shouldContinue: () -> Boolean): KeyboardDefaultResult {
                platform = productId
                return KeyboardDefaultResult.APPLIED
            }
        }
        assertEquals(KeyboardDefaultResult.APPLIED, restarted(store, gateway, trail, current = { platform }).turnOn())
        assertTrue("not gated by the trail: the explicit user choice", store.enabled)
        assertFalse("and the next start does not undo it", trail.state.revertDemanded)
    }

    @Test
    fun aDurableStoreStillAppliesOnceAndRecordsTheIntentBeforeTheBridge() {
        val store = FlakyStore(failWrites = false)
        var platform: String? = other
        var intentBeforeBridge = false
        val gateway = object : PrivilegedKeyboardDefaultGateway {
            var sets = 0
            override fun setMimirKeyboardDefault(shouldContinue: () -> Boolean): KeyboardDefaultResult {
                sets++
                intentBeforeBridge = store.wasDefault
                platform = productId
                return KeyboardDefaultResult.APPLIED
            }
        }
        assertEquals(KeyboardDefaultStep.APPLY, controller(store, gateway, current = { platform }).onStart())
        assertEquals(1, gateway.sets)
        assertTrue("the intent was on record when the bridge was asked", intentBeforeBridge)
        assertTrue(store.enabled)
    }
}
