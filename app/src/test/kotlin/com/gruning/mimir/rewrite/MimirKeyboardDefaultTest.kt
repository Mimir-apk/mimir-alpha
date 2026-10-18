package com.gruning.mimir.rewrite

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W2f: Mimir Keyboard as the system default through the vendor PServer bridge. Pins the fixed commands and
 * their byte budget, that no other id builds an operation, that only the readbacks decide APPLIED, that
 * nothing runs without a live READY posture, and the respect-the-user rule.
 *
 * The ids are the LITERAL strings `ComponentName.flattenToShortString` produces on a device: the product build
 * abbreviates the class, the devlab build (package `...rewrite.devlab`, class `...rewrite.MimirInputMethodService`)
 * cannot, so its id is the long form.
 */
class MimirKeyboardDefaultTest {

    private val productId = "com.gruning.mimir.rewrite/.MimirInputMethodService"
    private val devlabId = "com.gruning.mimir.rewrite.devlab/com.gruning.mimir.rewrite.MimirInputMethodService"
    private val marker = VendorPServerOperation.SuccessMarker

    private fun bytes(s: String) = s.toByteArray(StandardCharsets.UTF_8).size

    // ---- the fixed commands ------------------------------------------------------------------------------

    @Test
    fun theFixedCommandsRenderExactlyAndFitTheCommandCapForBothFlavors() {
        listOf(productId, devlabId).forEach { id ->
            val rendered = mapOf(
                "enable" to VendorPServerOperation.enableKeyboard(id)!!.render(),
                "select" to VendorPServerOperation.selectKeyboard(id)!!.render(),
                "listed" to VendorPServerOperation.keyboardListed(id)!!.render(),
                "readDefault" to VendorPServerOperation.ReadDefaultKeyboard.render(),
                "reset" to VendorPServerOperation.resetKeyboardIfDefault(id)!!.render(),
                "disable" to VendorPServerOperation.disableKeyboard(id)!!.render(),
                "otherListed" to VendorPServerOperation.otherKeyboardListed(id)!!.render(),
            )
            rendered.forEach { (name, command) ->
                assertTrue("$name for $id is ${bytes(command)} bytes", bytes(command) <= 198)
            }
            // D32: op D' is one byte over enable, readback E two over the listed-readback's pipe; the byte counts are pinned.
            val (disableBytes, otherBytes, resetBytes) = if (id == productId) Triple(130, 134, 155) else Triple(162, 166, 187)
            assertEquals(disableBytes, bytes(rendered.getValue("disable")))
            assertEquals(otherBytes, bytes(rendered.getValue("otherListed")))
            assertEquals(resetBytes, bytes(rendered.getValue("reset")))
            assertEquals(
                "/system/bin/ime disable $id >/dev/null 2>&1 && /system/bin/echo $marker",
                rendered["disable"],
            )
            assertEquals(
                "/system/bin/ime list -s 2>/dev/null | /system/bin/grep -vFx $id | /system/bin/head -n 1",
                rendered["otherListed"],
            )
            assertEquals("/system/bin/ime enable $id >/dev/null 2>&1 && /system/bin/echo $marker", rendered["enable"])
            assertEquals("/system/bin/ime set $id >/dev/null 2>&1 && /system/bin/echo $marker", rendered["select"])
            assertEquals(
                "/system/bin/ime list -s 2>/dev/null | /system/bin/grep -Fx $id >/dev/null && " +
                    "/system/bin/echo $marker",
                rendered["listed"],
            )
        }
        assertEquals("/system/bin/settings get secure default_input_method", VendorPServerOperation.ReadDefaultKeyboard.render())
        listOf(productId, devlabId).forEach { id ->
            assertEquals(
                "[ \"$(/system/bin/settings get secure default_input_method)\" = $id ] && " +
                    "/system/bin/ime reset >/dev/null 2>&1",
                VendorPServerOperation.resetKeyboardIfDefault(id)!!.render(),
            )
        }
    }

    @Test
    fun everyImeCallIsSilencedBecauseTheChannelCarriesOnlyTheFirstLineOfOutput() {
        listOf(
            VendorPServerOperation.enableKeyboard(productId)!!,
            VendorPServerOperation.selectKeyboard(productId)!!,
            VendorPServerOperation.keyboardListed(productId)!!,
            VendorPServerOperation.disableKeyboard(productId)!!,
        ).forEach { op ->
            val command = op.render()
            assertTrue(command, command.contains(">/dev/null"))
            assertTrue("the marker is the only thing that can reach the reply: $command", command.endsWith("echo $marker"))
        }
        // Readback E answers with the id itself, so there is no marker; the `ime` call's own noise is silenced.
        val other = VendorPServerOperation.otherKeyboardListed(productId)!!.render()
        assertTrue(other, other.contains("ime list -s 2>/dev/null |"))
        assertFalse(other.contains("echo"))
        // The conditional reset prints nothing at all: nothing but the readback can claim it worked.
        val reset = VendorPServerOperation.resetKeyboardIfDefault(productId)!!.render()
        assertTrue(reset.endsWith(">/dev/null 2>&1"))
        assertFalse(reset.contains("echo"))
    }

    @Test
    fun noIdButMimirsOwnBuildsAnOperation() {
        val rejected = listOf(
            "",
            "com.android.inputmethod.latin/.LatinIME",
            "com.gruning.mimir.rewrite/.OtherService",
            "com.gruning.mimir.rewrite/.MimirInputMethodService; reboot",
            "com.gruning.mimir.rewrite/.MimirInputMethodService\n/system/bin/id",
            "com.gruning.mimir.rewrite/.MimirInputMethodService && id",
            "\$(id)/.MimirInputMethodService",
            "a b/.MimirInputMethodService",
            "com.gruning.mimir.rewrite/'.MimirInputMethodService'",
            "com.gruning.mimir.rewrite.MimirInputMethodService",
            "/.MimirInputMethodService",
            "com.gruning.mimir.rewrite/.MimirInputMethodService|id",
            "com.gruning.mimir.rewrite/.MimirInputMethodService`id`",
        )
        rejected.forEach { id ->
            assertNull("enable accepted <$id>", VendorPServerOperation.enableKeyboard(id))
            assertNull("select accepted <$id>", VendorPServerOperation.selectKeyboard(id))
            assertNull("listed accepted <$id>", VendorPServerOperation.keyboardListed(id))
            assertNull("disable accepted <$id>", VendorPServerOperation.disableKeyboard(id))
            assertNull("otherListed accepted <$id>", VendorPServerOperation.otherKeyboardListed(id))
            assertNull("reset accepted <$id>", VendorPServerOperation.resetKeyboardIfDefault(id))
            assertFalse(VendorPServerOperation.isMimirKeyboardImeId(id))
        }
        assertNotNull(VendorPServerOperation.enableKeyboard(productId))
        assertNotNull(VendorPServerOperation.enableKeyboard(devlabId))
        assertNotNull(VendorPServerOperation.disableKeyboard(devlabId))
        assertNotNull(VendorPServerOperation.otherKeyboardListed(devlabId))
    }

    // ---- readback verdicts --------------------------------------------------------------------------------

    private class FakeTransport(
        var default: VendorTransportRead = VendorTransportRead.Value("com.google.android.inputmethod.latin/.LatinIME"),
        var listed: Boolean = false,
    ) : KeyboardDefaultTransport {
        val calls = mutableListOf<String>()
        var enable = VendorTransportMutation.APPLIED
        var select = VendorTransportMutation.APPLIED
        var reset = VendorTransportMutation.APPLIED
        var disable = VendorTransportMutation.APPLIED

        /** Readback E: the first enabled keyboard that is not Mimir's; Value(null) is the real answer "none". */
        var otherKeyboard: VendorTransportRead = VendorTransportRead.Value(null)

        /** What the world looks like AFTER the matching command ran; null leaves the state alone. */
        var defaultAfterSelect: VendorTransportRead? = null
        var listedAfterEnable: Boolean? = null
        var defaultAfterReset: VendorTransportRead? = null
        var defaultAfterDisable: VendorTransportRead? = null
        var listedAfterDisable: Boolean? = null

        override fun enableKeyboard(imeId: String): VendorTransportMutation {
            calls += "enable"
            if (enable == VendorTransportMutation.APPLIED) listedAfterEnable?.let { listed = it }
            return enable
        }

        override fun selectKeyboard(imeId: String): VendorTransportMutation {
            calls += "select"
            if (select == VendorTransportMutation.APPLIED) defaultAfterSelect?.let { default = it }
            return select
        }

        override fun readDefaultKeyboard(): VendorTransportRead {
            calls += "readDefault"
            return default
        }

        override fun keyboardIsEnabled(imeId: String): VendorTransportMutation {
            calls += "listed"
            return if (listed) VendorTransportMutation.APPLIED else VendorTransportMutation.FAILED
        }

        override fun resetKeyboards(imeId: String): VendorTransportMutation {
            calls += "reset"
            if (reset == VendorTransportMutation.APPLIED) defaultAfterReset?.let { default = it }
            return reset
        }

        override fun disableKeyboard(imeId: String): VendorTransportMutation {
            calls += "disable"
            if (disable == VendorTransportMutation.APPLIED) {
                defaultAfterDisable?.let { default = it }
                listedAfterDisable?.let { listed = it }
            }
            return disable
        }

        override fun readOtherKeyboard(imeId: String): VendorTransportRead {
            calls += "readOther"
            return otherKeyboard
        }
    }

    private fun applied(t: FakeTransport) = t.apply {
        listedAfterEnable = true
        defaultAfterSelect = VendorTransportRead.Value(productId)
    }

    @Test
    fun appliedOnlyWhenBothReadbacksMatch() {
        val t = applied(FakeTransport())
        assertEquals(KeyboardDefaultResult.APPLIED, MimirKeyboardDefaultOps.set(t, productId))
        assertEquals(listOf("readDefault", "enable", "select", "readDefault", "listed"), t.calls)
    }

    @Test
    fun aDefaultThatDoesNotMatchAfterTheSetIsFailedNotApplied() {
        val t = FakeTransport().apply {
            listedAfterEnable = true
            defaultAfterSelect = VendorTransportRead.Value("com.google.android.inputmethod.latin/.LatinIME")
        }
        assertEquals(KeyboardDefaultResult.FAILED, MimirKeyboardDefaultOps.set(t, productId))
    }

    @Test
    fun aKeyboardMissingFromTheListAfterTheSetIsUnconfirmedNotApplied() {
        val t = FakeTransport().apply {
            listedAfterEnable = false
            defaultAfterSelect = VendorTransportRead.Value(productId)
        }
        assertEquals(KeyboardDefaultResult.UNCONFIRMED, MimirKeyboardDefaultOps.set(t, productId))
    }

    @Test
    fun anUnreadableDefaultAfterTheSetIsUnconfirmedNotApplied() {
        val t = FakeTransport().apply {
            listedAfterEnable = true
            defaultAfterSelect = VendorTransportRead.Failed
        }
        assertEquals(KeyboardDefaultResult.UNCONFIRMED, MimirKeyboardDefaultOps.set(t, productId))
    }

    @Test
    fun aRefusedStepStopsTheSequenceWithNoRetry() {
        val enableRefused = applied(FakeTransport()).apply { enable = VendorTransportMutation.FAILED }
        assertEquals(KeyboardDefaultResult.FAILED, MimirKeyboardDefaultOps.set(enableRefused, productId))
        assertFalse("select must not run after a refused enable", enableRefused.calls.contains("select"))
        assertEquals(1, enableRefused.calls.count { it == "enable" })

        val selectRefused = applied(FakeTransport()).apply { select = VendorTransportMutation.FAILED }
        assertEquals(KeyboardDefaultResult.FAILED, MimirKeyboardDefaultOps.set(selectRefused, productId))
        assertEquals(1, selectRefused.calls.count { it == "select" })
    }

    @Test
    fun aTimeoutIsReportedSoTheGatewayCanPoison() {
        var timedOut = 0
        val t = applied(FakeTransport()).apply { enable = VendorTransportMutation.TIMED_OUT }
        assertEquals(KeyboardDefaultResult.FAILED, MimirKeyboardDefaultOps.set(t, productId, onTimedOut = { timedOut++ }))
        assertEquals(1, timedOut)
    }

    @Test
    fun alreadyTheDefaultAndListedSendsNothing() {
        val t = FakeTransport(default = VendorTransportRead.Value(productId), listed = true)
        assertEquals(KeyboardDefaultResult.APPLIED, MimirKeyboardDefaultOps.set(t, productId))
        assertFalse(t.calls.contains("enable"))
        assertFalse(t.calls.contains("select"))
    }

    @Test
    fun theDefaultButNotListedStillRunsTheSet() {
        val t = applied(FakeTransport(default = VendorTransportRead.Value(productId), listed = false))
        assertEquals(KeyboardDefaultResult.APPLIED, MimirKeyboardDefaultOps.set(t, productId))
        assertTrue(t.calls.contains("enable"))
    }

    @Test
    fun anIdThatIsNotMimirsSendsNothingAtAll() {
        val t = applied(FakeTransport())
        assertEquals(KeyboardDefaultResult.FAILED, MimirKeyboardDefaultOps.set(t, "evil/.Other"))
        assertEquals(KeyboardDefaultResult.FAILED, MimirKeyboardDefaultOps.reset(t, "evil/.Other"))
        assertTrue(t.calls.isEmpty())
    }

    // ---- turn off (D32): ime disable, never ime reset, while another keyboard exists -------------------------

    /** Mimir is the default and the player has [other] enabled too; `ime disable` moves the default to [other]. */
    private fun disabling(t: FakeTransport = FakeTransport(default = VendorTransportRead.Value(productId), listed = true)) = t.apply {
        otherKeyboard = VendorTransportRead.Value(other)
        defaultAfterDisable = VendorTransportRead.Value(other)
        listedAfterDisable = false
    }

    @Test
    fun turningOffDisablesMimirsKeyboardAndIsProvenByTheReadbacks() {
        val t = disabling()
        val notes = mutableListOf<String>()
        assertEquals(KeyboardDefaultResult.APPLIED, MimirKeyboardDefaultOps.reset(t, productId, onNote = { notes += it }))
        assertEquals(listOf("readDefault", "readOther", "disable", "readDefault", "listed"), t.calls)
        assertEquals("C is informational: Mimir is no longer listed", listOf("vendor keyboardDefault disable stillListed=false"), notes)
    }

    @Test
    fun disableWithNoDefaultSetAfterwardsIsAlsoNoLongerOurs() {
        val t = disabling().apply { defaultAfterDisable = VendorTransportRead.Value(null) }
        assertEquals(KeyboardDefaultResult.APPLIED, MimirKeyboardDefaultOps.reset(t, productId))
    }

    @Test
    fun theSwitchOffPathNeverSendsImeResetWhenAnotherKeyboardExists() {
        listOf(false, true).forEach { fallbackToReset ->
            val t = disabling()
            assertEquals(KeyboardDefaultResult.APPLIED, MimirKeyboardDefaultOps.reset(t, productId, fallbackToReset = fallbackToReset))
            assertTrue("disable is the op (fallbackToReset=$fallbackToReset): ${t.calls}", t.calls.contains("disable"))
            assertFalse("ime reset must not be sent while another keyboard exists (fallbackToReset=$fallbackToReset): ${t.calls}", t.calls.contains("reset"))
        }
        // Even when the disable is refused, the answer is not "reset instead".
        val refused = disabling().apply { disable = VendorTransportMutation.FAILED }
        assertEquals(KeyboardDefaultResult.FAILED, MimirKeyboardDefaultOps.reset(refused, productId, fallbackToReset = true))
        assertFalse(refused.calls.contains("reset"))
    }

    @Test
    fun withNoOtherKeyboardTheSwitchSendsNothingAndKeepsMimirTheDefault() {
        // Empty reply, a stray error line, and Mimir's own id are all "none".
        listOf<String?>(null, "Error: ime service not available", productId, "not an id").forEach { reply ->
            val t = FakeTransport(default = VendorTransportRead.Value(productId), listed = true).apply {
                otherKeyboard = VendorTransportRead.Value(reply)
            }
            assertEquals("reply=$reply", KeyboardDefaultResult.NO_OTHER_KEYBOARD, MimirKeyboardDefaultOps.reset(t, productId))
            assertEquals("reply=$reply: nothing but the two readbacks", listOf("readDefault", "readOther"), t.calls)
        }
    }

    @Test
    fun withNoOtherKeyboardOnlyTheSafetyRevertFallsThroughToImeReset() {
        val t = FakeTransport(default = VendorTransportRead.Value(productId), listed = true).apply {
            defaultAfterReset = VendorTransportRead.Value(other)
        }
        assertEquals(KeyboardDefaultResult.APPLIED, MimirKeyboardDefaultOps.reset(t, productId, fallbackToReset = true))
        assertEquals(listOf("readDefault", "readOther", "reset", "readDefault"), t.calls)

        // The reset's own readback still decides: a default that is still Mimir's is FAILED.
        val stuck = FakeTransport(default = VendorTransportRead.Value(productId), listed = true)
        assertEquals(KeyboardDefaultResult.FAILED, MimirKeyboardDefaultOps.reset(stuck, productId, fallbackToReset = true))

        // And it is attested like every mutation.
        val refused = FakeTransport(default = VendorTransportRead.Value(productId), listed = true)
        assertEquals(
            KeyboardDefaultResult.FAILED,
            MimirKeyboardDefaultOps.reset(refused, productId, authorize = { false }, fallbackToReset = true),
        )
        assertFalse(refused.calls.contains("reset"))
    }

    @Test
    fun anUnreadableOtherKeyboardReadbackIsFailedOnBothPathsAndSendsNothing() {
        listOf(false, true).forEach { fallbackToReset ->
            val t = FakeTransport(default = VendorTransportRead.Value(productId), listed = true).apply {
                otherKeyboard = VendorTransportRead.Failed
            }
            assertEquals(KeyboardDefaultResult.FAILED, MimirKeyboardDefaultOps.reset(t, productId, fallbackToReset = fallbackToReset))
            assertEquals(listOf("readDefault", "readOther"), t.calls)
        }
    }

    @Test
    fun turningOffNeverRunsWhenAnotherKeyboardIsTheDefault() {
        listOf(false, true).forEach { fallbackToReset ->
            val t = FakeTransport(default = VendorTransportRead.Value(other)).apply {
                otherKeyboard = VendorTransportRead.Value(other)
            }
            assertEquals(KeyboardDefaultResult.UNCHANGED, MimirKeyboardDefaultOps.reset(t, productId, fallbackToReset = fallbackToReset))
            assertEquals("only B was read", listOf("readDefault"), t.calls)
        }
    }

    @Test
    fun turningOffNeverRunsOnAnUnreadableDefault() {
        val t = FakeTransport(default = VendorTransportRead.Failed)
        assertEquals(KeyboardDefaultResult.FAILED, MimirKeyboardDefaultOps.reset(t, productId))
        assertEquals(listOf("readDefault"), t.calls)
    }

    @Test
    fun aDisableThatLeavesUsTheDefaultOrReadsAsGarbageIsFailed() {
        val stillOurs = disabling().apply { defaultAfterDisable = null }
        assertEquals(KeyboardDefaultResult.FAILED, MimirKeyboardDefaultOps.reset(stillOurs, productId))

        val unreadable = disabling().apply { defaultAfterDisable = VendorTransportRead.Failed }
        assertEquals(KeyboardDefaultResult.FAILED, MimirKeyboardDefaultOps.reset(unreadable, productId))

        val garbage = disabling().apply { defaultAfterDisable = VendorTransportRead.Value("Error: service not found") }
        assertEquals(KeyboardDefaultResult.FAILED, MimirKeyboardDefaultOps.reset(garbage, productId))

        val refused = disabling().apply { disable = VendorTransportMutation.FAILED }
        assertEquals(KeyboardDefaultResult.FAILED, MimirKeyboardDefaultOps.reset(refused, productId))
    }

    @Test
    fun aTimedOutDisableIsReportedSoTheGatewayCanPoison() {
        var timedOut = 0
        val t = disabling().apply { disable = VendorTransportMutation.TIMED_OUT }
        assertEquals(KeyboardDefaultResult.FAILED, MimirKeyboardDefaultOps.reset(t, productId, onTimedOut = { timedOut++ }))
        assertEquals(1, timedOut)
    }

    @Test
    fun theDisableIsAttestedAfterBothReadbacksAndImmediatelyBeforeItIsSent() {
        val t = disabling()
        assertEquals(
            KeyboardDefaultResult.APPLIED,
            MimirKeyboardDefaultOps.reset(t, productId, authorize = { t.calls += "auth"; true }),
        )
        assertEquals(listOf("readDefault", "readOther", "auth", "disable", "readDefault", "listed"), t.calls)

        val refused = disabling()
        assertEquals(KeyboardDefaultResult.FAILED, MimirKeyboardDefaultOps.reset(refused, productId, authorize = { false }))
        assertFalse(refused.calls.contains("disable"))
        assertFalse(refused.calls.contains("reset"))
    }

    // ---- fails closed without a live READY posture ----------------------------------------------------------

    private class CountingKeyboardGateway : PrivilegedKeyboardDefaultGateway {
        var calls = 0
        override fun setMimirKeyboardDefault(shouldContinue: () -> Boolean): KeyboardDefaultResult {
            calls++
            return KeyboardDefaultResult.APPLIED
        }

        override fun mimirKeyboardIsDefault(): Boolean? {
            calls++
            return true
        }

        override fun resetKeyboardDefault(): KeyboardDefaultResult {
            calls++
            return KeyboardDefaultResult.APPLIED
        }

        override fun revertKeyboardDefaultForSafety(): KeyboardDefaultResult {
            calls++
            return KeyboardDefaultResult.APPLIED
        }
    }

    private class PostureOnlyUtility(var status: PrivilegedUtilityStatus) : PrivilegedUtilityGateway {
        override fun posture() = PrivilegedUtilityPosture(status)
        override fun requestAccess(expectedStatus: PrivilegedUtilityStatus) = false
        override fun readVendorSetting(key: String): String? = null
        override fun writeVendorSetting(key: String, value: String) = false
        override fun readDisplayBrightness(displayId: Int): Float? = null
        override fun setSecondaryBrightness(displayId: Int, fraction: Float, mirrorPercent: Int) = false
        override fun close() = Unit
    }

    private object NoRecents : MButtonSystemRecentsGateway {
        override fun systemRecentsAccessStatus() = PrivilegedUtilityStatus.UNSUPPORTED_DEVICE
        override fun requestSystemRecentsAccess(expectedStatus: PrivilegedUtilityStatus) = false
        override fun openSystemRecents() = false
    }

    @Test
    fun theBrokerFailsClosedUnlessThePostureIsReadyRightNow() {
        val vendor = PostureOnlyUtility(PrivilegedUtilityStatus.PERMISSION_REQUIRED)
        val keyboard = CountingKeyboardGateway()
        val broker = CertifiedPrivilegeGateway(vendor = vendor, vendorRecents = NoRecents, vendorKeyboard = keyboard)

        PrivilegedUtilityStatus.entries.filter { it != PrivilegedUtilityStatus.READY }.forEach { status ->
            vendor.status = status
            assertEquals(KeyboardDefaultResult.UNAVAILABLE, broker.setMimirKeyboardDefault())
            assertEquals(KeyboardDefaultResult.UNAVAILABLE, broker.resetKeyboardDefault())
            assertEquals(KeyboardDefaultResult.UNAVAILABLE, broker.revertKeyboardDefaultForSafety())
            assertNull(broker.mimirKeyboardIsDefault())
        }
        assertEquals("no command reaches the vendor route without READY", 0, keyboard.calls)

        vendor.status = PrivilegedUtilityStatus.READY
        assertEquals(KeyboardDefaultResult.APPLIED, broker.setMimirKeyboardDefault())
        assertEquals(true, broker.mimirKeyboardIsDefault())
        assertEquals(KeyboardDefaultResult.APPLIED, broker.resetKeyboardDefault())
        assertEquals(KeyboardDefaultResult.APPLIED, broker.revertKeyboardDefaultForSafety())
        assertEquals(4, keyboard.calls)
    }

    @Test
    fun theBrokerWithNoKeyboardRouteIsUnavailableEvenWhenReady() {
        val broker = CertifiedPrivilegeGateway(
            vendor = PostureOnlyUtility(PrivilegedUtilityStatus.READY),
            vendorRecents = NoRecents,
        )
        assertEquals(KeyboardDefaultResult.UNAVAILABLE, broker.setMimirKeyboardDefault())
    }

    // ---- the respect-the-user rule and the controller -------------------------------------------------------

    private class MemoryStore(override var enabled: Boolean = true, override var wasDefault: Boolean = false) :
        MimirKeyboardDefaultStore

    private val other = "com.google.android.inputmethod.latin/.LatinIME"

    @Test
    fun theStepRule() {
        fun step(enabled: Boolean, was: Boolean, current: String?, ready: Boolean = true) =
            mimirKeyboardDefaultStep(enabled, was, current, productId, ready)
        // First run, ON, ready, another keyboard: apply once.
        assertEquals(KeyboardDefaultStep.APPLY, step(true, false, other))
        assertEquals(KeyboardDefaultStep.APPLY, step(true, false, null))
        // Not ready: never apply.
        assertEquals(KeyboardDefaultStep.NOTHING, step(true, false, other, ready = false))
        // Already the default.
        assertEquals(KeyboardDefaultStep.NOTHING, step(true, false, productId))
        assertEquals(KeyboardDefaultStep.NOTHING, step(true, true, productId))
        // The player switched away after Mimir had been the default: switch off, never re-apply.
        assertEquals(KeyboardDefaultStep.TURN_PREFERENCE_OFF, step(true, true, other))
        assertEquals(KeyboardDefaultStep.TURN_PREFERENCE_OFF, step(true, true, other, ready = false))
        // An unknown default is never read as a switch-away, and never re-applied after we had it.
        assertEquals(KeyboardDefaultStep.NOTHING, step(true, true, null))
        assertEquals(KeyboardDefaultStep.NOTHING, step(true, true, ""))
        // Preference off: nothing, whatever the platform says.
        assertEquals(KeyboardDefaultStep.NOTHING, step(false, false, other))
        assertEquals(KeyboardDefaultStep.NOTHING, step(false, true, other))
    }

    private fun controller(
        store: MimirKeyboardDefaultStore,
        gateway: PrivilegedKeyboardDefaultGateway,
        ready: Boolean = true,
        current: () -> String? = { other },
    ) = MimirKeyboardDefaultController(store, gateway, { ready }, current, productId)

    private class ScriptedGateway(
        var set: KeyboardDefaultResult = KeyboardDefaultResult.APPLIED,
        var reset: KeyboardDefaultResult = KeyboardDefaultResult.APPLIED,
        /** What the world does when the select is sent (e.g. the default becoming Mimir's). */
        val onSet: () -> Unit = {},
    ) : PrivilegedKeyboardDefaultGateway {
        var sets = 0
        var resets = 0
        override fun setMimirKeyboardDefault(shouldContinue: () -> Boolean): KeyboardDefaultResult {
            sets++
            onSet()
            return set
        }

        override fun resetKeyboardDefault(): KeyboardDefaultResult {
            resets++
            return reset
        }
    }

    @Test
    fun startDoesNothingWithoutAReadyBridgeOrWhenOff() {
        val gateway = ScriptedGateway()
        controller(MemoryStore(), gateway, ready = false).onStart()
        controller(MemoryStore(enabled = false), gateway).onStart()
        assertEquals(0, gateway.sets)
    }

    @Test
    fun theUserSwitchingAwayTurnsThePreferenceOffAndStopsReapplying() {
        val store = MemoryStore(enabled = true, wasDefault = true)
        val gateway = ScriptedGateway()
        // Android now says another keyboard is the default.
        val c = controller(store, gateway, current = { other })
        assertEquals(KeyboardDefaultStep.TURN_PREFERENCE_OFF, c.onDefaultChanged())
        assertFalse(store.enabled)
        assertFalse(store.wasDefault)
        // The next start, and every later change, leaves it alone.
        assertEquals(KeyboardDefaultStep.NOTHING, c.onStart())
        assertEquals(KeyboardDefaultStep.NOTHING, c.onDefaultChanged())
        assertEquals(0, gateway.sets)
    }

    @Test
    fun aDefaultChangeNeverAppliesAndSeeingOurselvesAsDefaultRecordsIt() {
        val store = MemoryStore(enabled = true, wasDefault = false)
        val gateway = ScriptedGateway()
        assertEquals(KeyboardDefaultStep.NOTHING, controller(store, gateway, current = { other }).onDefaultChanged())
        assertEquals(0, gateway.sets)
        controller(store, gateway, current = { productId }).onDefaultChanged()
        assertTrue("a player who picked Mimir themselves is respected if they later leave", store.wasDefault)
    }

    @Test
    fun turningOffRunsResetAfterClearingTheBitsSoTheResetIsNotReadAsASwitchAway() {
        val store = MemoryStore(enabled = true, wasDefault = true)
        var storeAtReset: Pair<Boolean, Boolean>? = null
        val gateway = object : PrivilegedKeyboardDefaultGateway {
            override fun resetKeyboardDefault(): KeyboardDefaultResult {
                storeAtReset = store.enabled to store.wasDefault
                return KeyboardDefaultResult.APPLIED
            }
        }
        assertEquals(KeyboardDefaultResult.APPLIED, controller(store, gateway).turnOff())
        assertEquals(false to false, storeAtReset)
        assertFalse(store.enabled)
    }

    @Test
    fun turningOffWithoutABridgeStillTurnsThePreferenceOffButSendsNothing() {
        val store = MemoryStore(enabled = true, wasDefault = true)
        val gateway = ScriptedGateway()
        assertEquals(KeyboardDefaultResult.UNAVAILABLE, controller(store, gateway, ready = false).turnOff())
        assertEquals(0, gateway.resets)
        assertFalse(store.enabled)
    }

    @Test
    fun turningOffWithNoOtherKeyboardReportsItAndTheToastSaysWhatToDo() {
        val store = MemoryStore(enabled = true, wasDefault = true)
        val gateway = ScriptedGateway(reset = KeyboardDefaultResult.NO_OTHER_KEYBOARD)
        assertEquals(KeyboardDefaultResult.NO_OTHER_KEYBOARD, controller(store, gateway, current = { productId }).turnOff())
        assertEquals(1, gateway.resets)

        // Wording specific to turning the switch off.
        assertEquals(
            "Enable another keyboard in Android’s keyboard settings first.",
            mimirKeyboardSwitchFeedback(false, KeyboardDefaultResult.NO_OTHER_KEYBOARD),
        )
        KeyboardDefaultResult.entries.filter { it != KeyboardDefaultResult.NO_OTHER_KEYBOARD }.forEach { result ->
            assertNotEquals(
                "Enable another keyboard in Android’s keyboard settings first.",
                mimirKeyboardSwitchFeedback(false, result),
            )
        }
    }

    @Test
    fun theSwitchFeedbackIsSilentOnSuccessAndHonestOnFailure() {
        assertNull(mimirKeyboardSwitchFeedback(true, KeyboardDefaultResult.APPLIED))
        assertNull(mimirKeyboardSwitchFeedback(false, KeyboardDefaultResult.APPLIED))
        assertNull(mimirKeyboardSwitchFeedback(false, KeyboardDefaultResult.UNCHANGED))
        assertNotNull(mimirKeyboardSwitchFeedback(true, KeyboardDefaultResult.FAILED))
        assertNotNull(mimirKeyboardSwitchFeedback(true, KeyboardDefaultResult.UNAVAILABLE))
        assertNotNull(mimirKeyboardSwitchFeedback(false, KeyboardDefaultResult.FAILED))
    }

    // ---- Mutation safety: authorisation per mutation, intent before mutation, conditional reset ---------------

    @Test
    fun everyMutationIsAuthorisedImmediatelyBeforeItRunsAndARefusalSendsNothing() {
        val order = mutableListOf<String>()
        val t = applied(FakeTransport())
        val ok = MimirKeyboardDefaultOps.set(t, productId, authorize = { order += "auth"; true })
        assertEquals(KeyboardDefaultResult.APPLIED, ok)
        // authorise, enable, authorise, select: never a stale yes carried over from the first.
        assertEquals(listOf("readDefault", "enable", "select", "readDefault", "listed"), t.calls)
        assertEquals(2, order.size)

        val refused = applied(FakeTransport())
        assertEquals(KeyboardDefaultResult.FAILED, MimirKeyboardDefaultOps.set(refused, productId, authorize = { false }))
        assertFalse(refused.calls.contains("enable"))
        assertFalse(refused.calls.contains("select"))

        val resetRefused = FakeTransport(default = VendorTransportRead.Value(productId)).apply {
            otherKeyboard = VendorTransportRead.Value(other)
        }
        assertEquals(
            KeyboardDefaultResult.FAILED,
            MimirKeyboardDefaultOps.reset(resetRefused, productId, authorize = { false }),
        )
        assertFalse(resetRefused.calls.contains("disable"))
        assertFalse(resetRefused.calls.contains("reset"))
    }

    @Test
    fun authorisationIsAskedAgainBeforeTheSecondMutationNotOnlyTheFirst() {
        var asked = 0
        val t = applied(FakeTransport())
        // Capabilities "change" after the first authorisation: the second must be refused, so select never runs.
        val result = MimirKeyboardDefaultOps.set(t, productId, authorize = { asked++ == 0 })
        assertEquals(KeyboardDefaultResult.FAILED, result)
        assertTrue(t.calls.contains("enable"))
        assertFalse(t.calls.contains("select"))
    }

    @Test
    fun mimirsOwnEnableAndSelectNeverTurnItsOwnPreferenceOff() {
        val store = MemoryStore()
        var current: String? = other
        lateinit var c: MimirKeyboardDefaultController
        val seen = mutableListOf<KeyboardDefaultStep>()
        val gateway = object : PrivilegedKeyboardDefaultGateway {
            override fun setMimirKeyboardDefault(shouldContinue: () -> Boolean): KeyboardDefaultResult {
                // The enable event: the default is still the keyboard the apply started from.
                seen += c.onDefaultChanged()
                assertTrue(shouldContinue())
                // The select event: the default is now Mimir's.
                current = productId
                seen += c.onDefaultChanged()
                assertTrue(shouldContinue())
                return KeyboardDefaultResult.APPLIED
            }
        }
        c = MimirKeyboardDefaultController(store, gateway, { true }, { current }, productId, KeyboardApplySession())
        c.onStart()
        assertEquals(listOf(KeyboardDefaultStep.NOTHING, KeyboardDefaultStep.NOTHING), seen)
        assertTrue("our own apply did not turn the preference off", store.enabled)
        assertTrue(store.wasDefault)
    }

    @Test
    fun aKeyboardThePlayerPicksDuringTheApplyCancelsTheRemainingOpsAndTurnsThePreferenceOff() {
        val third = "com.samsung.android.honeyboard/.service.HoneyBoardService"
        for (viaStart in listOf(true, false)) {
            val store = MemoryStore()
            var current: String? = other
            var selects = 0
            val gateway = object : PrivilegedKeyboardDefaultGateway {
                override fun setMimirKeyboardDefault(shouldContinue: () -> Boolean): KeyboardDefaultResult {
                    assertTrue("before enable nothing has changed", shouldContinue())
                    // The player picks a third keyboard in Android between enable and select.
                    current = third
                    if (!shouldContinue()) return KeyboardDefaultResult.FAILED
                    selects++
                    return KeyboardDefaultResult.APPLIED
                }
            }
            val c = controller(store, gateway, current = { current })
            if (viaStart) c.onStart() else c.turnOn()
            assertEquals("via start=$viaStart: select must not run over the player", 0, selects)
            assertFalse(store.enabled)
            assertFalse(store.wasDefault)
            // And it never re-applies afterwards.
            assertEquals(KeyboardDefaultStep.NOTHING, c.onStart())
        }
    }

    @Test
    fun anAcceptedSelectIsUnconfirmedWhenItsReadbackIsUnreadableOrTheListingUnproven() {
        // B unreadable after the select.
        val unreadable = FakeTransport().apply {
            listedAfterEnable = true
            defaultAfterSelect = VendorTransportRead.Failed
        }
        assertEquals(KeyboardDefaultResult.UNCONFIRMED, MimirKeyboardDefaultOps.set(unreadable, productId))

        // B is Mimir's but the listing is unproven.
        val unlisted = FakeTransport().apply {
            listedAfterEnable = false
            defaultAfterSelect = VendorTransportRead.Value(productId)
        }
        assertEquals(KeyboardDefaultResult.UNCONFIRMED, MimirKeyboardDefaultOps.set(unlisted, productId))

        // B readable and someone else's: definitely not applied.
        val other = FakeTransport().apply {
            listedAfterEnable = true
            defaultAfterSelect = VendorTransportRead.Value("com.google.android.inputmethod.latin/.LatinIME")
        }
        assertEquals(KeyboardDefaultResult.FAILED, MimirKeyboardDefaultOps.set(other, productId))
    }

    @Test
    fun theSwitchFeedbackIsSilentForUnconfirmedAndForAPlayersOwnChoice() {
        assertNull(mimirKeyboardSwitchFeedback(true, KeyboardDefaultResult.UNCONFIRMED))
        assertNull(mimirKeyboardSwitchFeedback(true, KeyboardDefaultResult.UNCHANGED))
    }

    // ---- round 3: written FIRST, red against the flag-based session ---------------------------------------------

    @Test
    fun round3AReturnToThePreApplyDefaultAfterTheSelectLandedEndsOffWithNoReselect() {
        val store = MemoryStore(enabled = false)
        var current: String? = other
        var sets = 0
        val gateway = object : PrivilegedKeyboardDefaultGateway {
            override fun setMimirKeyboardDefault(shouldContinue: () -> Boolean): KeyboardDefaultResult {
                sets++
                assertTrue(shouldContinue())
                current = productId            // Mimir's select lands...
                current = other                // ...and the player goes straight back to the keyboard it started from.
                return KeyboardDefaultResult.APPLIED
            }
        }
        val c = controller(store, gateway, current = { current })
        c.turnOn()
        assertFalse("the session ended on a fresh readback that is not Mimir: off", store.enabled)
        assertFalse(store.wasDefault)
        assertEquals(KeyboardDefaultStep.NOTHING, c.onStart())
        assertEquals("never re-applied", 1, sets)
    }

    @Test
    fun round3ASessionWhereThePlayerSwitchedNeverLeavesMimirDefaultWithItsSwitchOff() {
        val store = MemoryStore(enabled = false)
        var current: String? = other
        lateinit var c: MimirKeyboardDefaultController
        val gateway = object : PrivilegedKeyboardDefaultGateway {
            override fun setMimirKeyboardDefault(shouldContinue: () -> Boolean): KeyboardDefaultResult {
                // The observer saw the player on a third keyboard for a moment...
                current = "com.third/.Ime"
                c.onDefaultChanged()
                // ...but the select (sent before anything could cancel it) landed and Mimir IS the default.
                current = productId
                return KeyboardDefaultResult.APPLIED
            }
        }
        c = controller(store, gateway, current = { current })
        c.turnOn()
        assertEquals(productId, current)
        assertTrue("Mimir is the default, so the switch (the readback) is on and so is the preference", store.enabled)
    }

    // ---- round 3: the session ends equal to a fresh readback -----------------------------------------------------

    @Test
    fun startAppliesOnceWhenOnReadyAndNotDefaultAndEndsOnWhenMimirIsTheDefault() {
        val store = MemoryStore()
        var platform: String? = other
        val gateway = ScriptedGateway(onSet = { platform = productId })
        assertEquals(KeyboardDefaultStep.APPLY, controller(store, gateway, current = { platform }).onStart())
        assertEquals(1, gateway.sets)
        assertTrue(store.wasDefault)
        assertTrue(store.enabled)
    }

    @Test
    fun aFailedApplyEndsOffBecauseTheFreshReadbackIsNotMimirAndIsNeverRetried() {
        val store = MemoryStore()
        val gateway = ScriptedGateway(set = KeyboardDefaultResult.FAILED)
        val c = controller(store, gateway)
        c.onStart()
        assertEquals(1, gateway.sets)
        assertFalse(store.enabled)
        assertFalse(store.wasDefault)
        assertEquals(KeyboardDefaultStep.NOTHING, c.onStart())
        assertEquals(1, gateway.sets)
    }

    @Test
    fun turningOnEndsEqualToAFreshReadbackWhateverTheGatewayAnswered() {
        var platform: String? = other
        val ok = MemoryStore(enabled = false)
        assertEquals(
            KeyboardDefaultResult.APPLIED,
            controller(ok, ScriptedGateway(onSet = { platform = productId }), current = { platform }).turnOn(),
        )
        assertTrue(ok.enabled)
        assertTrue(ok.wasDefault)

        val bad = MemoryStore(enabled = false)
        assertEquals(
            KeyboardDefaultResult.FAILED,
            controller(bad, ScriptedGateway(set = KeyboardDefaultResult.FAILED)).turnOn(),
        )
        assertFalse(bad.enabled)
        assertFalse(bad.wasDefault)

        val gateway = ScriptedGateway()
        assertEquals(
            KeyboardDefaultResult.UNAVAILABLE,
            controller(MemoryStore(enabled = false), gateway, ready = false).turnOn(),
        )
        assertEquals(0, gateway.sets)
    }

    @Test
    fun theIntentIsRecordedBeforeTheMutationAndOnlyANeverSentCommandTakesItBack() {
        val store = MemoryStore()
        var seenDuring: Boolean? = null
        val gateway = object : PrivilegedKeyboardDefaultGateway {
            override fun setMimirKeyboardDefault(shouldContinue: () -> Boolean): KeyboardDefaultResult {
                seenDuring = store.wasDefault
                return KeyboardDefaultResult.FAILED
            }
        }
        controller(store, gateway).onStart()
        assertEquals("recorded before the command went out", true, seenDuring)

        val unavailable = MemoryStore()
        controller(unavailable, ScriptedGateway(set = KeyboardDefaultResult.UNAVAILABLE)).onStart()
        assertFalse("nothing was sent, so there is no intent", unavailable.wasDefault)
        assertTrue("and the preference stands for a later start", unavailable.enabled)
    }

    @Test
    fun selectLandingWithAnUnreadableReadbackStaysOnAndALaterSwitchAwayIsNeverReasserted() {
        val store = MemoryStore()
        var platform: String? = other
        // Select lands (the platform default becomes Mimir) but the bridge readback comes back unconfirmed.
        controller(store, ScriptedGateway(set = KeyboardDefaultResult.UNCONFIRMED, onSet = { platform = productId }),
            current = { platform }).onStart()
        assertTrue(store.enabled)
        assertTrue(store.wasDefault)

        // The player switches keyboards; the next start respects it and never applies again.
        platform = other
        val gateway = ScriptedGateway()
        val next = controller(store, gateway, current = { platform })
        assertEquals(KeyboardDefaultStep.TURN_PREFERENCE_OFF, next.onStart())
        assertFalse(store.enabled)
        assertEquals(KeyboardDefaultStep.NOTHING, next.onStart())
        assertEquals(0, gateway.sets)
    }

    @Test
    fun theSessionCancelsOnAThirdKeyboardAndOnlyBeforeAMutation() {
        val session = KeyboardApplySession()
        session.begin(preApplyDefault = other)
        assertFalse(session.cancelIfPlayerChose(other, productId))
        assertFalse(session.cancelIfPlayerChose(productId, productId))
        assertFalse("unknown is never a switch", session.cancelIfPlayerChose("", productId))
        assertFalse(session.cancelIfPlayerChose(null, productId))
        assertTrue(session.cancelIfPlayerChose("com.third/.Ime", productId))
        assertTrue("and it stays cancelled", session.cancelIfPlayerChose(other, productId))
    }

    @Test
    fun theObserverNeverActsDuringASessionOneEventAtATime() {
        val store = MemoryStore()
        var platform: String? = other
        lateinit var c: MimirKeyboardDefaultController
        val seen = mutableListOf<KeyboardDefaultStep>()
        val gateway = object : PrivilegedKeyboardDefaultGateway {
            override fun setMimirKeyboardDefault(shouldContinue: () -> Boolean): KeyboardDefaultResult {
                platform = "com.third/.Ime"
                seen += c.onDefaultChanged()
                platform = productId
                seen += c.onDefaultChanged()
                return KeyboardDefaultResult.APPLIED
            }
        }
        c = controller(store, gateway, current = { platform })
        c.onStart()
        assertEquals(listOf(KeyboardDefaultStep.NOTHING, KeyboardDefaultStep.NOTHING), seen)
        assertTrue("the fresh readback at the end is Mimir: on", store.enabled)
    }

    @Test
    fun anAcceptedSelectWithAnUnreadableReadbackStaysOnAndTheNextObservationDecides() {
        val store = MemoryStore(enabled = false)
        // The readback is unreadable (null) for the whole session.
        val c = controller(store, ScriptedGateway(set = KeyboardDefaultResult.UNCONFIRMED), current = { null })
        assertEquals(KeyboardDefaultResult.UNCONFIRMED, c.turnOn())
        assertTrue("the preference stays on", store.enabled)
        assertTrue("with the intent set, unconfirmed", store.wasDefault)

        // Next observation: Mimir's id -> still on, nothing to do.
        assertEquals(KeyboardDefaultStep.NOTHING, controller(store, ScriptedGateway(), current = { productId }).onDefaultChanged())
        assertTrue(store.enabled)

        // Or anything else -> the player switched away: off for good, never re-applied.
        val store2 = MemoryStore(enabled = false)
        controller(store2, ScriptedGateway(set = KeyboardDefaultResult.UNCONFIRMED), current = { null }).turnOn()
        val gatewayB = ScriptedGateway()
        val next = controller(store2, gatewayB, current = { other })
        assertEquals(KeyboardDefaultStep.TURN_PREFERENCE_OFF, next.onDefaultChanged())
        assertFalse(store2.enabled)
        assertEquals(KeyboardDefaultStep.NOTHING, next.onStart())
        assertEquals(0, gatewayB.sets)
    }

    @Test
    fun anAcceptedSelectWhoseFreshReadbackIsReadableAndNotMimirEndsOff() {
        val store = MemoryStore(enabled = false)
        assertEquals(
            KeyboardDefaultResult.UNCHANGED,
            controller(store, ScriptedGateway(set = KeyboardDefaultResult.UNCONFIRMED), current = { other }).turnOn(),
        )
        assertFalse(store.enabled)
    }

    // ---- D33 (d): finalize never overwrites what the observer recorded after the apply ---------------------------

    @Test
    fun aSwitchAwayInTheGapAfterTheFinalReadEndsOffAndIsNeverOverwrittenWithOn() {
        val store = MemoryStore(enabled = false)
        var platform: String? = other
        val session = KeyboardApplySession()
        lateinit var observer: MimirKeyboardDefaultController
        var finalizing = false
        var reads = 0
        val current: () -> String? = {
            val seen = platform
            if (finalizing && ++reads == 1) {
                // The finalize read has just seen Mimir. NOW the player switches away and the observer runs.
                platform = other
                observer.onDefaultChanged()
            }
            seen
        }
        val gateway = object : PrivilegedKeyboardDefaultGateway {
            override fun setMimirKeyboardDefault(shouldContinue: () -> Boolean): KeyboardDefaultResult {
                platform = productId
                finalizing = true
                return KeyboardDefaultResult.APPLIED
            }
        }
        val controller = MimirKeyboardDefaultController(store, gateway, { true }, current, productId, session)
        observer = MimirKeyboardDefaultController(store, ScriptedGateway(), { true }, current, productId, session)

        controller.turnOn()

        assertEquals("the platform default is the player's keyboard", other, platform)
        assertFalse("so the preference ends Off, not Mimir's stale On", store.enabled)
        assertFalse(store.wasDefault)
    }

    @Test
    fun theSessionStaysOpenUntilTheResultIsWrittenAndTheObserverWaitsForIt() {
        val store = MemoryStore(enabled = false)
        var platform: String? = other
        val session = KeyboardApplySession()
        var activeWhenFinalizeRead: Boolean? = null
        var finalizing = false
        val current: () -> String? = {
            if (finalizing && activeWhenFinalizeRead == null) activeWhenFinalizeRead = session.active
            platform
        }
        val gateway = object : PrivilegedKeyboardDefaultGateway {
            override fun setMimirKeyboardDefault(shouldContinue: () -> Boolean): KeyboardDefaultResult {
                platform = productId
                finalizing = true
                return KeyboardDefaultResult.APPLIED
            }
        }
        MimirKeyboardDefaultController(store, gateway, { true }, current, productId, session).turnOn()
        assertEquals("finalize ran while the session was still open", true, activeWhenFinalizeRead)
        assertFalse("and it is closed afterwards", session.active)
        assertTrue(store.enabled)
    }
}
