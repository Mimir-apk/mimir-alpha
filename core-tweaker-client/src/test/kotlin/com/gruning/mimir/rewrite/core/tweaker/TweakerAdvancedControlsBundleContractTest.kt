package com.gruning.mimir.rewrite.core.tweaker

import android.os.Bundle
import com.gruning.mimir.tweaker.TweakerAdvancedControlsContract
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Client half of the advanced-controls contract, last of the set. There is no daemon half: both
 * producers are private members of `MimirTweakerService` with no callable seam -- see
 * [TweakerAdvancedControlsContract].
 *
 * This is the bundle the fan section is rendered from, so consumption drift here is how a control
 * comes to advertise a mode the layer beneath refuses. Two shapes, two parsers, two fixtures.
 */
@RunWith(RobolectricTestRunner::class)
class TweakerAdvancedControlsBundleContractTest {

    @Test
    fun `every contract key the client must consume actually changes a parse`() {
        val inert = TweakerAdvancedControlsContract.CONSUMED_BY_CLIENT.filter { key -> !mattersAnywhere(key) }

        assertEquals(
            "contract says the client consumes these advanced-control keys, but dropping them " +
                "changed nothing -- the client stopped reading them, or the contract is wrong",
            emptyList<String>(),
            inert,
        )
    }

    @Test
    fun `the last restore reason is parsed, classified, and absent when the daemon reports none`() {
        val parsed = parseTweakerAdvancedCapabilitiesBundle(capabilityBundle(), API)
        assertEquals(
            // The fixture carries every classification key non-default so the drop invariant above
            // can see each one; a real daemon never sets both true for one restore.
            TweakerLastRestore(reason = "MISSING_TELEMETRY_CPU", isTelemetryTransport = true, isGuardianHandback = true, generation = 51L),
            parsed.lastRestore,
        )

        // No reason means "no restore recorded", which must read as absent rather than as an
        // empty-string incident the player surface would then try to explain.
        assertEquals(
            null,
            parseTweakerAdvancedCapabilitiesBundle(
                capabilityBundle().withoutKey("lastRestoreReason"),
                API,
            ).lastRestore,
        )

        // A restore that is NOT a telemetry-transport loss still parses -- the client is not the
        // classifier -- it simply is not the class the player surface speaks about.
        val ordinary = capabilityBundle().apply {
            putString("lastRestoreReason", "LEASE_EXPIRED")
            putBoolean("lastRestoreIsTelemetryTransport", false)
            putBoolean("lastRestoreIsGuardianHandback", false)
        }
        assertEquals(
            TweakerLastRestore(reason = "LEASE_EXPIRED", isTelemetryTransport = false, isGuardianHandback = false, generation = 51L),
            parseTweakerAdvancedCapabilitiesBundle(ordinary, API).lastRestore,
        )
    }

    /**
     * the guardian hand-back is the daemon's classification, carried
     * beside the telemetry-transport one on the same bundle. An older daemon that never sends the
     * key parses as `false`, never as a claim.
     */
    @Test
    fun `a guardian hand-back is parsed as its own class and defaults to false on an older daemon`() {
        val handback = capabilityBundle().apply {
            putString("lastRestoreReason", "GUARDIAN_RESTORE_REQUESTED/HEALTHY_STALE")
            putBoolean("lastRestoreIsTelemetryTransport", false)
            putBoolean("lastRestoreIsGuardianHandback", true)
        }
        assertEquals(
            TweakerLastRestore(
                reason = "GUARDIAN_RESTORE_REQUESTED/HEALTHY_STALE",
                isTelemetryTransport = false,
                isGuardianHandback = true,
                generation = 51L,
            ),
            parseTweakerAdvancedCapabilitiesBundle(handback, API).lastRestore,
        )
        // P3-6: an older daemon that never sends the generation parses as 0, and the incident key
        // still exists -- the latch simply cannot tell two drops with one reason apart there.
        assertEquals(
            0L,
            parseTweakerAdvancedCapabilitiesBundle(handback.withoutKey("lastRestoreGeneration"), API).lastRestore?.generation,
        )
        assertEquals(
            "GUARDIAN_RESTORE_REQUESTED/HEALTHY_STALE#51",
            parseTweakerAdvancedCapabilitiesBundle(handback, API).lastRestore?.incidentKey,
        )
        assertEquals(
            false,
            parseTweakerAdvancedCapabilitiesBundle(
                handback.withoutKey("lastRestoreIsGuardianHandback"),
                API,
            ).lastRestore?.isGuardianHandback,
        )
    }

    @Test
    fun `the fan section collapses rather than half-renders when the capability answer is empty`() {
        // A dead binder returns an empty bundle. The section must offer nothing at all: a partial
        // capability is what lets a control advertise a mode the daemon never claimed.
        val parsed = parseTweakerAdvancedCapabilitiesBundle(Bundle(), API)

        assertEquals(null, parsed.manualFan)
        assertEquals(emptyList<Int>(), parsed.frameLimitsHz)
    }

    private fun mattersAnywhere(key: String): Boolean {
        val capability = capabilityBundle()
        val selection = selectionBundle()
        return parseCapability(capability.withoutKey(key)) != parseCapability(capability) ||
            parseSelection(selection.withoutKey(key)) != parseSelection(selection)
    }

    private fun parseCapability(bundle: Bundle) =
        parseTweakerAdvancedCapabilitiesBundle(bundle, API)

    private fun parseSelection(bundle: Bundle) = parseTweakerFanControlBundle(
        bundle = bundle,
        expectedApiVersion = API,
        attempt = TweakerFanControlAttempt(
            callerRequestId = REQUEST_ID,
            requestDeadlineElapsedRealtimeMs = DEADLINE_MS,
            ownerRequestId = OWNER_REQUEST_ID,
            generation = GENERATION,
            transactionId = TRANSACTION_ID,
            priorMode = TweakerFanMode.AUTO,
            profileId = PROFILE_ID,
            mode = TweakerFanMode.MANUAL,
            curveBias = CURVE_BIAS,
        ),
        observedAtElapsedRealtimeMs = 1_000L,
    )

    private fun Bundle.withoutKey(key: String): Bundle = Bundle(this).apply { remove(key) }

    /**
     * The capability answer EMBEDS the current selection: `parseManualFanCapability` reads
     * fanControlMode, fanCurveBias and the owner block out of this same bundle, so a fixture
     * carrying only the section's shape produces a null capability and every fan key then looks
     * dead. The bias range is pinned by the parser to exactly -100..100 step 10.
     */
    /**
     * **The parse-time override, which is what stops the panel lying.**
     *
     * The daemon publishes `fanControlMode` from a durable per-profile preference that outlives
     * session teardown, so after a recovery restore it reads MANUAL while the hardware sits on
     * vendor SMART. Rewriting the mode HERE -- the single place a capability is constructed -- is
     * what lets the mode label, the toggles and the re-arm gate all see the mode in effect without
     * a sweep of every call site.
     */
    @Test
    fun `a stored MANUAL over unowned hardware parses as the AUTO actually in effect`() {
        val diverged = capabilityBundle().apply { putBoolean("fanHardwareDutyOwned", false) }

        val fan = parseTweakerAdvancedCapabilitiesBundle(diverged, API).manualFan!!

        assertEquals(
            "the mode in effect is the vendor's, whatever the stored selection says",
            TweakerFanMode.AUTO,
            fan.currentMode,
        )
        assertEquals(
            "and the bias is zeroed with it -- a stale bias riding a forced AUTO would leak the " +
                "dead selection into a fresh request",
            0,
            fan.currentCurveBias,
        )
        assertEquals(false, fan.hardwareDutyOwned)
    }

    @Test
    fun `an older daemon that cannot attest leaves the advertised mode untouched`() {
        val legacy = capabilityBundle().withoutKey("fanHardwareDutyOwned")

        val fan = parseTweakerAdvancedCapabilitiesBundle(legacy, API).manualFan!!

        assertEquals(
            "absence means UNKNOWN, never false -- forcing AUTO here would invent a fallback that " +
                "an older Tweaker never reported",
            null,
            fan.hardwareDutyOwned,
        )
        assertEquals(TweakerFanMode.MANUAL, fan.currentMode)
        assertEquals(CURVE_BIAS, fan.currentCurveBias)
    }

    private fun capabilityBundle(): Bundle = Bundle().apply {
        putInt("apiVersion", API)
        putInt("fanControlContractVersion", 2)
        putBoolean("manualFanControlAvailable", true)
        putIntegerArrayList("frameLimitsHz", arrayListOf(30, 40, 60))
        putBoolean("perGameOverridesAvailable", true)
        putStringArrayList("fanControlModes", arrayListOf("AUTO", "MANUAL"))
        putInt("fanCurveBiasMin", -100)
        putInt("fanCurveBiasMax", 100)
        putInt("fanCurveBiasStep", 10)
        putString("fanCurveSensorType", "GPU")

        // 2026-08-23: these two were missing, and their absence is why the contract test above was
        // red on this branch rather than the client being at fault. `AdvancedControlsPublicBundles`
        // puts BOTH on every capability bundle, unconditionally and independent of the fan section
        // ("a dead privileged transport must never be silent"), so a fixture without them was not
        // the bundle the client actually receives -- and dropping a key that was never there cannot
        // change a parse. `MISSING_TELEMETRY_CPU` is a real reason from the daemon's own
        // telemetry-transport set, not an invented string.
        putString("lastRestoreReason", "MISSING_TELEMETRY_CPU")
        // P3-6 (2026-10-14): the generation the restore ended -- the companion latches on reason AND generation.
        putLong("lastRestoreGeneration", 51L)
        // The derate block: all four together or not at all. Present in the fixture so this
        // suite's "every consumed key changes a parse" invariant covers them -- removing any one
        // must make parseTweakerThermalDerate return null.
        putString("thermalDerateReason", "THERMAL_ANDROID")
        putInt("thermalDerateRung", 1)
        putInt("thermalDerateMaxRung", 3)
        putString("thermalDerateProfileId", "performance")
        putBoolean("lastRestoreIsTelemetryTransport", true)
        // the daemon's second classification, always present beside the first.
        // true for the same reason as `lastRestoreIsTelemetryTransport` above -- dropping a key the
        // parser defaults to false cannot change a parse, and the invariant would call it inert.
        putBoolean("lastRestoreIsGuardianHandback", true)

        // Live hardware ownership, added 2026-08-26. Same lesson as the keys above, already
        // learned once on this fixture: a fixture missing a key the daemon always sends is not the
        // bundle the client receives, and dropping a key that was never there cannot change a
        // parse -- which the invariant above then reports as "the client stopped consuming it".
        //
        // true here, so this fixture describes a MANUAL selection genuinely IN EFFECT. The
        // divergent case is a different bundle, not a variation of this one, and is pinned below.
        putBoolean("fanHardwareDutyOwned", true)

        putString("fanControlMode", "MANUAL")
        putInt("fanCurveBias", CURVE_BIAS)
        putString("ownerRequestId", OWNER_REQUEST_ID)
        putLong("generation", GENERATION)
        putString("transactionId", TRANSACTION_ID)
        putString("profileId", PROFILE_ID)
    }

    /** A fan-selection outcome, echoing the caller's owner identity exactly. */
    private fun selectionBundle(): Bundle = Bundle().apply {
        putBoolean("success", true)
        putString("code", "FAN_CONTROL_UPDATED")
        putString("message", "fan control updated")
        putInt("apiVersion", API)
        putBoolean("mutationMayBeActive", true)
        putString("callerRequestId", REQUEST_ID)
        putLong("callerDeadlineElapsedRealtimeMs", DEADLINE_MS)
        putString("sessionState", "ACTIVE")
        putString("sessionScope", "SYSTEM")
        putString("requestId", OWNER_REQUEST_ID)

        putString("fanControlMode", "MANUAL")
        putInt("fanCurveBias", CURVE_BIAS)
        putLong("generation", GENERATION)
        putString("transactionId", TRANSACTION_ID)
        putString("ownerRequestId", OWNER_REQUEST_ID)
        putString("profileId", PROFILE_ID)
    }

    private companion object {
        const val API = TWEAKER_V6_ADVANCED_CONTROLS_MIN_API_VERSION
        const val REQUEST_ID = "req-7"
        const val OWNER_REQUEST_ID = "owner-7"
        const val TRANSACTION_ID = "tx-7"
        const val PROFILE_ID = "performance"
        const val GENERATION = 43L
        const val CURVE_BIAS = 20
        const val DEADLINE_MS = 31_000L
    }
}
