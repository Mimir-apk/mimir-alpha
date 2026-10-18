package com.gruning.mimir.rewrite.core.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Discovery remains authoritative when readable:** with daemon `UNAVAILABLE` and discovered
 * `canRead = true`, the row reports the discovered state rather than `UNAVAILABLE`.
 *
 * The defect: `TweakerUtilityRow` resolved availability purely from the daemon pair and capped to the
 * literal string `UNAVAILABLE`, while on the Thor the discovered path reads four policies and
 * holds a real cap. The row was stating something about Mimir's LIST while pretending to state
 * something about the DEVICE — the exact failure `CompanionTunerAbsence` was written to avoid.
 *
 * These pins could not even be expressed against the shipping signature: `tweakerUtilityAvailability`
 * had no discovered parameter, so the case had no way to be asked. The differential replay for this
 * story is removing the argument, not editing a branch.
 */
class TweakerDiscoveredCeilingTest {

    private val steps = listOf(
        TweakerCeilingStep(1_000_000, "1.0 GHz"),
        TweakerCeilingStep(2_000_000, "2.0 GHz"),
        TweakerCeilingStep(3_000_000, "3.0 GHz"),
    )

    private fun ceiling(
        currentKhz: Int? = 2_000_000,
        canWrite: Boolean = true,
        steps: List<TweakerCeilingStep> = this.steps,
    ) = TweakerDiscoveredCeiling(
        policyLabel = "PRIME",
        ceilingLabelCaps = "2.0 GHz",
        steps = steps,
        currentKhz = currentKhz,
        canWrite = canWrite,
    )

    private fun caps(
        status: TweakerCapabilityStatus,
        certified: Set<String> = emptySet(),
    ) = TweakerCapabilities(
        status = status,
        deviceProfileId = "test",
        hardwareFingerprint = "test",
        supportedAxes = emptySet(),
        certifiedProfileIds = certified,
        notes = emptyList(),
        // A READY row is only READY under the full product gate — `allowsProductTunableProfiles`
        // demands all four. The certified path is genuinely richer than discovery, and the fixture
        // has to be genuinely certified for the precedence pin below to be testing anything.
        posture = TweakerPosture.PRODUCTION,
        qualificationSource = TweakerQualificationSource.SIGNED_MANIFEST,
        mutationAvailable = true,
    )

    @Test
    fun theEpicsGateDaemonUnavailableAndKernelReadable() {
        val availability = tweakerUtilityAvailability(
            installed = true,
            capabilities = caps(TweakerCapabilityStatus.UNAVAILABLE),
            discovered = ceiling(),
        )

        assertEquals(TweakerUtilityAvailability.DISCOVERED_CEILING, availability)

        val row = TweakerUtilityRowState(availability = availability, discovered = ceiling())
        // The word UNAVAILABLE must not survive anywhere in what the row says.
        assertFalse(row.valueLabelCaps.contains("UNAVAILABLE"))
        assertEquals("2.0 GHz", row.valueLabelCaps)
    }

    @Test
    fun anUninstalledTweakerDoesNotHideAWorkingCeiling() {
        // `PServerHost` binds the VENDOR's `PServerBinder`; it has no dependency on the Tweaker package.
        // So "not installed" does not imply "cannot act" while the kernel publishes a readable ceiling.
        // INSTALL TWEAKER in the value would fail E3's device gate: the rail and the TWEAKER
        // surface must agree on one screen. The CTA lives in the supporting line instead.
        val availability = tweakerUtilityAvailability(
            installed = false,
            capabilities = null,
            discovered = ceiling(),
        )

        assertEquals(TweakerUtilityAvailability.DISCOVERED_CEILING, availability)
        assertFalse(
            TweakerUtilityRowState(availability = availability, discovered = ceiling())
                .valueLabelCaps.contains("INSTALL"),
        )
    }

    @Test
    fun theCertifiedPathStillOutranksDiscovery() {
        val certified = caps(TweakerCapabilityStatus.SUPPORTED, certified = setOf("performance"))
        // Precondition, asserted rather than assumed: this fixture really does offer an alternative.
        assertTrue(selectableTweakerProfileIds(certified).size > 1)

        assertEquals(
            TweakerUtilityAvailability.READY,
            tweakerUtilityAvailability(installed = true, capabilities = certified, discovered = ceiling()),
        )
    }

    @Test
    fun aDeviceThatPublishesNoTablesStillSaysUnavailable() {
        // R3's reserved meaning. Discovery present but EMPTY is not a licence to claim tuning works.
        val empty = ceiling(steps = emptyList(), currentKhz = null)
        assertFalse(empty.canRead)

        assertEquals(
            TweakerUtilityAvailability.UNAVAILABLE,
            tweakerUtilityAvailability(installed = true, capabilities = null, discovered = empty),
        )
        assertEquals(
            TweakerUtilityAvailability.NOT_INSTALLED,
            tweakerUtilityAvailability(installed = false, capabilities = null, discovered = empty),
        )
    }

    @Test
    fun theExistingLadderIsUntouchedWhenNothingWasDiscovered() {
        // Every pre-existing caller passes no discovered argument. None of them may move.
        assertEquals(
            TweakerUtilityAvailability.UNAVAILABLE,
            tweakerUtilityAvailability(installed = null, capabilities = null),
        )
        assertEquals(
            TweakerUtilityAvailability.NOT_INSTALLED,
            tweakerUtilityAvailability(installed = false, capabilities = null),
        )
        assertEquals(
            TweakerUtilityAvailability.UNAVAILABLE,
            tweakerUtilityAvailability(installed = true, capabilities = null),
        )
        assertEquals(
            TweakerUtilityAvailability.RESPONDING_STOCK_ONLY,
            tweakerUtilityAvailability(installed = true, capabilities = caps(TweakerCapabilityStatus.UNSUPPORTED)),
        )
        // Presence not read yet must NOT be inferred from reachable capabilities — the ladder never did.
        assertEquals(
            TweakerUtilityAvailability.UNAVAILABLE,
            tweakerUtilityAvailability(
                installed = null,
                capabilities = caps(TweakerCapabilityStatus.SUPPORTED, certified = setOf("performance")),
            ),
        )
    }

    @Test
    fun readAndWriteAreIndependentSoAReadOnlyDeviceShowsItsCeilingAndCannotStep() {
        val row = TweakerUtilityRowState(
            availability = TweakerUtilityAvailability.DISCOVERED_CEILING,
            discovered = ceiling(canWrite = false),
        )

        assertEquals("2.0 GHz", row.valueLabelCaps)
        assertFalse(row.canStep)
        assertEquals(TweakerUtilityStepOutcome.CeilingReadOnly, row.stepOutcome(1))
    }

    @Test
    fun theScaleClampsAtBothEndsAndNeverWraps() {
        // Profiles wrap because they are a short named ring. A frequency table is an ORDERED SCALE:
        // wrapping from maximum to minimum would throttle the device to its slowest clock in answer to
        // a player asking for one more step of headroom.
        assertEquals(3_000_000, steppedTweakerCeilingKhz(3_000_000, 1, steps))
        assertEquals(1_000_000, steppedTweakerCeilingKhz(1_000_000, -1, steps))
        assertEquals(3_000_000, steppedTweakerCeilingKhz(2_000_000, 1, steps))
        assertEquals(1_000_000, steppedTweakerCeilingKhz(2_000_000, -1, steps))
        // An unreadable current cap steps from the TOP, never from position zero.
        assertEquals(2_000_000, steppedTweakerCeilingKhz(null, -1, steps))
        assertEquals(3_000_000, steppedTweakerCeilingKhz(null, 1, steps))
    }

    @Test
    fun theTopOfTheScaleReleasesTheHoldRatherThanCappingAtMaximum() {
        val row = TweakerUtilityRowState(
            availability = TweakerUtilityAvailability.DISCOVERED_CEILING,
            discovered = ceiling(),
        )

        val up = row.stepOutcome(1) as TweakerUtilityStepOutcome.CeilingChanged
        assertEquals(3_000_000, up.khz)
        // `requestCeiling` captures on its first call and leaves `isHolding()` true whatever it writes,
        // so a ceiling pinned at maximum would strand a hold with nothing on this rail able to release
        // it. The top of the scale IS the way back out.
        assertTrue(up.releasesHold)

        val down = row.stepOutcome(-1) as TweakerUtilityStepOutcome.CeilingChanged
        assertEquals(1_000_000, down.khz)
        assertFalse(down.releasesHold)
    }

    @Test
    fun applyDecidesReleaseBeforeAlreadyInForce() {
        // A device sitting AT its top frequency while Mimir still holds a capture must still be able to
        // give that capture back. Ordering these the other way would consume the press and strand it.
        val atTop = TweakerUtilityRowState(
            availability = TweakerUtilityAvailability.DISCOVERED_CEILING,
            discovered = ceiling(currentKhz = 3_000_000),
        )

        assertEquals(
            TweakerCeilingApplyDecision.Release,
            tweakerCeilingApplyDecision(atTop, TweakerCeilingDraft(3_000_000)),
        )
    }

    @Test
    fun applyRefusesADraftTheRowNoLongerOffers() {
        val row = TweakerUtilityRowState(
            availability = TweakerUtilityAvailability.DISCOVERED_CEILING,
            discovered = ceiling(),
        )

        assertEquals(
            TweakerCeilingApplyDecision.Unavailable(1_800_000),
            tweakerCeilingApplyDecision(row, TweakerCeilingDraft(1_800_000)),
        )
        assertEquals(TweakerCeilingApplyDecision.NoDraft, tweakerCeilingApplyDecision(row, null))

        // Losing the write host mid-preview retires the draft: a confirm that cannot act is a press the
        // row would have to swallow.
        val readOnly = row.copy(discovered = ceiling(canWrite = false))
        assertFalse(TweakerCeilingDraft(1_000_000).admitted(readOnly))
        assertEquals(
            TweakerCeilingApplyDecision.Unavailable(1_000_000),
            tweakerCeilingApplyDecision(readOnly, TweakerCeilingDraft(1_000_000)),
        )
    }

    @Test
    fun applyWritesTheDraftedStepAndCarriesItsOwnSpelling() {
        val row = TweakerUtilityRowState(
            availability = TweakerUtilityAvailability.DISCOVERED_CEILING,
            discovered = ceiling(),
        )

        assertEquals(
            TweakerCeilingApplyDecision.Apply(1_000_000, "1.0 GHz"),
            tweakerCeilingApplyDecision(row, TweakerCeilingDraft(1_000_000)),
        )
    }

    @Test
    fun steppingPreviewsFromTheDraftedPositionNotTheLiveOne() {
        val row = TweakerUtilityRowState(
            availability = TweakerUtilityAvailability.DISCOVERED_CEILING,
            discovered = ceiling(currentKhz = 3_000_000),
        )

        val firstDown = steppedTweakerCeilingDraftOutcome(null, row, -1)
            as TweakerUtilityStepOutcome.CeilingChanged
        assertEquals(2_000_000, firstDown.khz)

        // Second press walks from the PREVIEW, not back to the live 3.0 GHz.
        val secondDown = steppedTweakerCeilingDraftOutcome(TweakerCeilingDraft(2_000_000), row, -1)
            as TweakerUtilityStepOutcome.CeilingChanged
        assertEquals(1_000_000, secondDown.khz)
    }

    @Test
    fun steppingBackOntoTheLiveCeilingLeavesNoDraft() {
        val row = TweakerUtilityRowState(
            availability = TweakerUtilityAvailability.DISCOVERED_CEILING,
            discovered = ceiling(currentKhz = 2_000_000),
        )

        assertEquals(null, tweakerCeilingDraftFor(row, 2_000_000))
        assertEquals(TweakerCeilingDraft(1_000_000), tweakerCeilingDraftFor(row, 1_000_000))
    }
}
