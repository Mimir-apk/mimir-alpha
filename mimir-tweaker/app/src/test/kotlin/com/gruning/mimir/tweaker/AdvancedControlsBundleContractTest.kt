package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Daemon half of the advanced-controls contract, completing the set. This half only became
 * possible once the marshalling moved out of a private `MimirTweakerService` member into
 * [advancedCapabilitiesBundle]; before that there was no seam to call.
 *
 * The client half enforces that every key is consumed. What is pinned here is what only this side
 * can see: that the section never advertises itself without the selection the client needs to
 * anchor it, and that when the section is unavailable the fan keys are absent entirely rather than
 * present-and-empty.
 */
@RunWith(RobolectricTestRunner::class)
class AdvancedControlsBundleContractTest {

    @Test
    fun `an offered fan section emits exactly the declared contract`() {
        val emitted = offeredSection().keySet()
        val declared = TweakerAdvancedControlsContract.ALL.toSet()

        assertEquals("keys not declared in the contract", emptySet<String>(), emitted - declared)
        assertEquals("contract declares keys never written", emptySet<String>(), declared - emitted)
    }

    @Test
    fun `a withheld fan section omits every fan key rather than shipping empty ones`() {
        val emitted = withheldSection().keySet()

        // The client reads a present-but-empty capability differently from an absent one, and
        // "absent" is the honest answer when no session owns the fan. Only the always-present
        // frame-limit and version fields survive.
        assertEquals(
            setOf(
                "apiVersion",
                "fanControlContractVersion",
                "manualFanControlAvailable",
                "frameLimitsHz",
                "perGameOverridesAvailable",
                "lastRestoreReason",
                "lastRestoreIsTelemetryTransport",
                "lastRestoreIsGuardianHandback",
            ),
            emitted,
        )
        assertTrue(emitted.none { it.startsWith("fanCurve") || it == "fanControlModes" })
    }

    /**
     * Fan-fence fix F4 (2026-10-14): a guardian hand-back is classified beside the transport flag,
     * from the reason alone, independent of the fan section -- and the two classes never overlap.
     */
    @Test
    fun `a guardian hand-back reason is flagged as such, and never as a transport death`() {
        listOf(
            "GUARDIAN_RESTORE_REQUESTED/HEALTHY_STALE",
            "GUARDIAN_TERMINAL_RECEIVED",
            "GUARDIAN_HEARTBEAT_REJECTED/HEARTBEAT_REJECTED",
            "GUARDIAN_TERMINAL_FENCE",
        ).forEach { reason ->
            val bundle = withheldSection(lastRestoreReason = reason)
            assertEquals(reason, bundle.getString("lastRestoreReason"))
            assertTrue(reason, bundle.getBoolean("lastRestoreIsGuardianHandback"))
            assertTrue(reason, !bundle.getBoolean("lastRestoreIsTelemetryTransport"))
        }
        assertTrue(!withheldSection(lastRestoreReason = "MISSING_TELEMETRY_CPU").getBoolean("lastRestoreIsGuardianHandback"))
        assertTrue(!withheldSection(lastRestoreReason = "STARTUP_RECOVERY_RESTORE").getBoolean("lastRestoreIsGuardianHandback"))
        assertTrue(
            "a recovery pass that reconciled the Guardian's own receipt is a hand-back (P3-6)",
            withheldSection(lastRestoreReason = "GUARDIAN_STARTUP_RECOVERY_RESTORE").getBoolean("lastRestoreIsGuardianHandback"),
        )
        assertTrue(!withheldSection().getBoolean("lastRestoreIsGuardianHandback"))
    }

    /** P3-6 (2026-10-14): the generation keys the companion's once-per-incident latch; it travels with the reason only. */
    @Test
    fun `the restore generation travels with the reason and is absent without one`() {
        val with = withheldSection(lastRestoreReason = "GUARDIAN_TERMINAL_RECEIVED", lastRestoreGeneration = 51L)
        assertEquals(51L, with.getLong("lastRestoreGeneration"))
        assertTrue("no reason, no generation", !withheldSection(lastRestoreGeneration = 51L).containsKey("lastRestoreGeneration"))
        assertTrue(
            "never a zero the client would have to interpret",
            !withheldSection(lastRestoreReason = "GUARDIAN_TERMINAL_RECEIVED").containsKey("lastRestoreGeneration"),
        )
    }

    @Test
    fun `a session with no derate omits the whole derate block`() {
        val emitted = offeredSection(activeDerate = null).keySet()

        // Absence is the signal. A client must never be able to read "rung 0 of 0" and have to
        // guess whether that means nominal or missing -- the four keys travel together or not at
        // all, which is what makes the zero unrepresentable rather than merely unwritten.
        assertTrue(
            "no derate key may survive a session that was never derated",
            emitted.none { it.startsWith("thermalDerate") },
        )
    }

    @Test
    fun `the last restore reason survives independently of the fan section`() {
        // a dead privileged transport must never be silent, and the fan
        // section being withheld (no active session, no signed fan plan, ...) must not swallow it.
        val withheld = advancedCapabilitiesBundle(
            apiVersion = 8,
            perGameOverridesAvailable = true,
            frameLimitsHz = listOf(30, 60),
            fanSectionAvailable = false,
            manualModeAdvertised = false,
            contractFanMode = null,
            selection = null,
            lastRestoreReason = "MISSING_TELEMETRY_CPU",
        )
        assertEquals("MISSING_TELEMETRY_CPU", withheld.getString("lastRestoreReason"))
        assertTrue(withheld.getBoolean("lastRestoreIsTelemetryTransport"))
    }

    @Test
    fun `a non-telemetry restore reason is reported but not flagged as a transport death`() {
        val bundle = advancedCapabilitiesBundle(
            apiVersion = 8,
            perGameOverridesAvailable = true,
            frameLimitsHz = listOf(30, 60),
            fanSectionAvailable = false,
            manualModeAdvertised = false,
            contractFanMode = null,
            selection = null,
            lastRestoreReason = "TARGET_LOSS",
        )
        assertEquals("TARGET_LOSS", bundle.getString("lastRestoreReason"))
        assertTrue(!bundle.getBoolean("lastRestoreIsTelemetryTransport"))
    }

    @Test
    fun `no recorded restore this process lifetime reports absent, not a false transport claim`() {
        val bundle = advancedCapabilitiesBundle(
            apiVersion = 8,
            perGameOverridesAvailable = true,
            frameLimitsHz = listOf(30, 60),
            fanSectionAvailable = false,
            manualModeAdvertised = false,
            contractFanMode = null,
            selection = null,
        )
        assertEquals(null, bundle.getString("lastRestoreReason"))
        assertTrue(!bundle.getBoolean("lastRestoreIsTelemetryTransport"))
    }

    @Test
    fun `offering the section without a live selection fails loudly rather than half-rendering`() {
        // A bias range with no current selection is precisely the shape that lets a control
        // advertise something the layer beneath cannot honour: the client's parser would find no
        // capability at all and the panel would offer a fan section anchored to nothing.
        assertThrows(IllegalStateException::class.java) {
            advancedCapabilitiesBundle(
                apiVersion = 8,
                perGameOverridesAvailable = true,
                frameLimitsHz = listOf(30, 60),
                fanSectionAvailable = true,
                manualModeAdvertised = true,
                contractFanMode = TuningPlanner.FAN_MANUAL,
                selection = null,
            )
        }
    }

    @Test
    fun `the wire boolean is the un-conflated section gate, not manual PWM entry`() {
        // Moved here from AdvancedFanControlTest, which runs on plain JVM where android.os.Bundle
        // is stubbed to default returns -- getBoolean answers false whatever was written, so the
        // claim could only ever be asserted there by matching source text. It is behaviour now.
        assertTrue(offeredSection(manualModeAdvertised = false).getBoolean("manualFanControlAvailable"))
        assertTrue(!withheldSection().getBoolean("manualFanControlAvailable"))
        assertTrue(!withheldSection().containsKey("fanControlModes"))
    }

    @Test
    fun `the advertised modes always contain AUTO, with MANUAL only when it is advertised`() {
        assertEquals(arrayListOf("AUTO", "MANUAL"), offeredSection().getStringArrayList("fanControlModes"))
        assertEquals(
            arrayListOf("AUTO"),
            offeredSection(manualModeAdvertised = false).getStringArrayList("fanControlModes"),
        )
    }

    @Test
    fun `the bias range the client pins is what the daemon actually emits`() {
        // parseManualFanCapability requires exactly -100..100 step 10 and rejects the whole
        // capability otherwise, so these three values are a cross-build contract, not a default.
        val bundle = offeredSection()
        assertEquals(-100, bundle.getInt("fanCurveBiasMin"))
        assertEquals(100, bundle.getInt("fanCurveBiasMax"))
        assertEquals(10, bundle.getInt("fanCurveBiasStep"))
        assertEquals("GPU", bundle.getString("fanCurveSensorType"))
    }

    private fun offeredSection(
        manualModeAdvertised: Boolean = true,
        activeDerate: ActuatedDerate? = DEFAULT_DERATE,
    ) = advancedCapabilitiesBundle(
        apiVersion = 8,
        perGameOverridesAvailable = true,
        frameLimitsHz = listOf(30, 40, 60),
        fanSectionAvailable = true,
        manualModeAdvertised = manualModeAdvertised,
        // A MANUAL selection that IS in effect: Mimir owns duty. The divergent shape -- MANUAL
        // selection over a vendor-owned contract -- is the defect this key exists for and is
        // pinned separately below.
        contractFanMode = TuningPlanner.FAN_MANUAL,
        selection = ActiveFanControlSelection(
            generation = 43L,
            transactionId = "tx-7",
            requestId = "owner-7",
            profileId = "performance",
            mode = PublicFanControlMode.MANUAL,
            curveBias = 20,
        ),
        // P3-6: the generation is declared in the contract and travels only beside a reason, so the
        // offered fixture carries both; its absence without a reason is pinned separately below.
        lastRestoreReason = "GUARDIAN_TERMINAL_RECEIVED",
        lastRestoreGeneration = 51L,
        // The derate block is declared in the contract, so the offered fixture must emit it or the
        // bidirectional `declared - emitted` assertion above fails. Its ABSENCE is covered by
        // `a session with no derate omits the whole derate block` below.
        activeDerate = activeDerate,
    )

    private companion object {
        val DEFAULT_DERATE = ActuatedDerate(
            reason = "THERMAL_ANDROID",
            rung = 1,
            maxRung = 3,
            profileId = "performance",
            generation = 43L,
            transactionId = "tx-7",
            atEpochMs = 1_700_000_000_000L,
        )
    }

    /**
     * **The defect this key exists for.** A durable MANUAL selection published beside a
     * vendor-owned contract is exactly the state the Odin was in at 12:31:14: the fan on
     * `fan_mode=4` while the bundle advertised `fanControlMode=MANUAL`. The client could not tell
     * that apart from a real Manual, so it rendered "Manual" at a player whose fan was on the
     * vendor curve and skipped the re-arm that would have fixed it.
     */
    @Test
    fun `a stored MANUAL over a vendor-owned contract reports duty NOT owned`() {
        val diverged = advancedCapabilitiesBundle(
            apiVersion = 8,
            perGameOverridesAvailable = true,
            frameLimitsHz = listOf(30, 60),
            fanSectionAvailable = true,
            manualModeAdvertised = true,
            contractFanMode = TuningPlanner.FAN_SMART,
            selection = ActiveFanControlSelection(
                generation = 43L,
                transactionId = "tx-7",
                requestId = "owner-7",
                profileId = "performance",
                mode = PublicFanControlMode.MANUAL,
                curveBias = -100,
            ),
        )

        assertEquals(
            "the SELECTION is unchanged -- it is what a re-selection restores",
            "MANUAL",
            diverged.getString("fanControlMode"),
        )
        assertTrue(
            "but the hardware is the vendor's, and the bundle must now say so",
            !diverged.getBoolean("fanHardwareDutyOwned"),
        )
    }

    @Test
    fun `a MANUAL selection actually in effect reports duty owned`() {
        assertTrue(offeredSection().getBoolean("fanHardwareDutyOwned"))
    }

    private fun withheldSection(
        lastRestoreReason: String? = null,
        lastRestoreGeneration: Long? = null,
    ) = advancedCapabilitiesBundle(
        apiVersion = 8,
        perGameOverridesAvailable = true,
        frameLimitsHz = listOf(30, 40, 60),
        fanSectionAvailable = false,
        manualModeAdvertised = false,
        contractFanMode = null,
        selection = null,
        lastRestoreReason = lastRestoreReason,
        lastRestoreGeneration = lastRestoreGeneration,
    )
}
