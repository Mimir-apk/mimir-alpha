package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.feature.settings.MimirKeyboardPickerEntry
import com.gruning.mimir.rewrite.feature.settings.MimirKeyboardRow
import com.gruning.mimir.rewrite.feature.settings.mimirKeyboardRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * W2f (policy 2026-10-06, restoring 2026-10-03): the default-keyboard preference ships ON. A fresh install has
 * no stored preference, and only a MISSING preference reads ON: an explicit stored OFF (the player turned the switch
 * off, switched keyboards in Android, or the crash-loop safety reverted it) stays OFF and Mimir never re-applies.
 *
 * How stored OFF is told from default: `SharedPreferences.getBoolean(key, true)` returns the stored value when the
 * key exists and the default only when it is missing; every OFF the product writes is an explicit
 * `putBoolean("enabled", false)`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class MimirKeyboardOnByDefaultTest {

    private val productId = "com.gruning.mimir.rewrite/.MimirInputMethodService"
    private val other = "com.google.android.inputmethod.latin/.LatinIME"

    private class CountingGateway(private val onSet: () -> Unit = {}) : PrivilegedKeyboardDefaultGateway {
        var sets = 0
        var resets = 0
        override fun setMimirKeyboardDefault(shouldContinue: () -> Boolean): KeyboardDefaultResult {
            sets++
            onSet()
            return KeyboardDefaultResult.APPLIED
        }

        override fun resetKeyboardDefault(): KeyboardDefaultResult {
            resets++
            return KeyboardDefaultResult.APPLIED
        }
    }

    private fun store() = SharedPrefsMimirKeyboardDefaultStore(RuntimeEnvironment.getApplication())

    @Test
    fun aFreshInstallReadsOnAndAppliesThroughTheBridgeOnce() {
        val store = store()
        assertTrue("no stored preference reads ON", store.enabled)
        assertFalse(store.wasDefault)

        var platform: String? = other
        val gateway = CountingGateway { platform = productId }
        val controller = MimirKeyboardDefaultController(store, gateway, { true }, { platform }, productId)
        assertEquals(KeyboardDefaultStep.APPLY, controller.onStart())
        assertEquals("one apply through the bridge", 1, gateway.sets)
        assertTrue(store.enabled)
        assertTrue(store.wasDefault)

        // The row is the platform readback: Mimir is now the default, so the switch reads On.
        assertEquals(
            MimirKeyboardRow.Switch(on = true),
            mimirKeyboardRow(MimirKeyboardPickerEntry.ALREADY_DEFAULT, bridgeReady = true),
        )

        // A second start sends nothing: it is already the default.
        assertEquals(KeyboardDefaultStep.NOTHING, controller.onStart())
        assertEquals(1, gateway.sets)
    }

    @Test
    fun anExplicitStoredOffIsKeptAndNothingIsSent() {
        val app = RuntimeEnvironment.getApplication()
        store().enabled = false
        val reread = SharedPrefsMimirKeyboardDefaultStore(app)
        assertFalse("a stored false stays false", reread.enabled)

        val gateway = CountingGateway()
        val controller = MimirKeyboardDefaultController(reread, gateway, { true }, { other }, productId)
        assertEquals(KeyboardDefaultStep.NOTHING, controller.onStart())
        assertEquals(0, gateway.sets)
        assertEquals(0, gateway.resets)
        assertFalse(reread.enabled)
    }

    @Test
    fun aPlayerWhoSwitchedAwayTurnsItOffAndMimirNeverReapplies() {
        val store = store()
        store.enabled = true
        store.wasDefault = true
        val gateway = CountingGateway()
        // Mimir was the default; the player chose another keyboard in Android.
        val controller = MimirKeyboardDefaultController(store, gateway, { true }, { other }, productId)
        assertEquals(KeyboardDefaultStep.TURN_PREFERENCE_OFF, controller.onStart())
        assertFalse(store.enabled)
        assertEquals(0, gateway.sets)
        // Later starts stay off: the stored false is explicit, not the missing-key default.
        assertEquals(KeyboardDefaultStep.NOTHING, controller.onStart())
        assertFalse(SharedPrefsMimirKeyboardDefaultStore(RuntimeEnvironment.getApplication()).enabled)
        assertEquals(0, gateway.sets)
    }

    @Test
    fun withoutALiveBridgeNothingIsSentAndThePreferenceStandsForALaterStart() {
        val store = store()
        val gateway = CountingGateway()
        val controller = MimirKeyboardDefaultController(store, gateway, { false }, { other }, productId)
        assertEquals(KeyboardDefaultStep.NOTHING, controller.onStart())
        assertEquals(0, gateway.sets)
        assertTrue(store.enabled)
    }

    @Test
    fun theSharedPreferencesStoreWritesDurablyAndReportsIt() {
        val store = store()
        assertTrue(store.write(enabled = false, wasDefault = true))
        val reread = SharedPrefsMimirKeyboardDefaultStore(RuntimeEnvironment.getApplication())
        assertFalse(reread.enabled)
        assertTrue(reread.wasDefault)
        assertTrue("a null bit is left alone", store.write(enabled = true))
        assertTrue(SharedPrefsMimirKeyboardDefaultStore(RuntimeEnvironment.getApplication()).wasDefault)
    }

    @Test
    fun theSwitchStillTurnsItOnAndAppliesAsBuilt() {
        val store = store()
        store.enabled = false
        var platform: String? = other
        val gateway = CountingGateway { platform = productId }
        val controller = MimirKeyboardDefaultController(store, gateway, { true }, { platform }, productId)
        assertEquals(KeyboardDefaultResult.APPLIED, controller.turnOn())
        assertEquals(1, gateway.sets)
        assertTrue(store.enabled)
    }
}
