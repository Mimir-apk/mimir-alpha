package com.gruning.mimir.tweaker;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The advanced-controls Bundle contract -- the fan section and the frame-limit capability the
 * Tweaker panel is built from. Fifth and last of the set; see {@link TweakerCapabilityContract} for
 * why these live in the module both Gradle builds share.
 *
 * <p>CLIENT-ENFORCED ONLY, unlike the other four. Both producers are private members of
 * {@code MimirTweakerService} -- {@code advancedCapabilitiesV6()} and
 * {@code Bundle.putFanControlSelection(selection)} -- reading live session and posture state, so
 * neither has a callable seam a unit test can drive. The keys are declared here and the client half
 * enforces consumption, which is the direction that has actually caused damage: this is the bundle
 * behind the fan section, and a control advertising a mode the layer beneath refuses is a defect
 * this codebase shipped four times in one session.
 *
 * <p>Giving those two producers a pure seam would let the daemon half be pinned like the others.
 * Worth doing when the fan section is next touched.
 */
public final class TweakerAdvancedControlsContract {

    /**
     * Every key this contract owns, as one list.
     *
     * <p>Not split into a section shape and a selection shape, because they are not separate: the
     * capability answer EMBEDS the current selection. {@code parseManualFanCapability} reads
     * {@code fanControlMode}, {@code fanCurveBias} and the whole owner block out of the very same
     * bundle that describes the section, and returns null if any is absent -- so a "section only"
     * fixture yields no capability at all and every fan key then looks dead. That coupling is the
     * design: the panel must never be able to render a range without also knowing where the
     * selection currently sits inside it.
     *
     * <p>The generic transaction verdict fields a fan mutation shares with every other transaction
     * ({@code success}, {@code code}, {@code message}, {@code callerRequestId}, ...) belong to
     * {@link TweakerSessionContract} and are deliberately not re-declared here.
     */
    public static final List<String> ALL = Collections.unmodifiableList(Arrays.asList(
            "apiVersion", "fanControlContractVersion", "manualFanControlAvailable",
            "frameLimitsHz", "perGameOverridesAvailable",
            // a dead privileged transport must never be silent. Always
            // present, independent of the fan section -- see GuardianRestoreObservability in the
            // daemon and MimirTweakerServiceContractTest.
            "lastRestoreReason", "lastRestoreIsTelemetryTransport",
            // Fan-fence fix F4 (2026-10-14): the Tweaker's own guardian handed the hardware back.
            // Same shape as the transport flag: always present, absent on an older daemon is false.
            "lastRestoreIsGuardianHandback",
            // P3-6 (2026-10-14): the session generation the restore ended; the companion latches
            // its once-per-incident message on reason AND generation. Present whenever the reason is.
            "lastRestoreGeneration",
            // A thermal derate cut the session's clock ceilings. Emitted together or not at all --
            // absence means "no actuated derate for this session", never "rung 0". Independent of
            // the fan section for the same reason lastRestoreReason is.
            "thermalDerateReason", "thermalDerateRung", "thermalDerateMaxRung",
            "thermalDerateProfileId",
            "fanControlModes",
            // Whether Mimir actually owns fan duty right now, as opposed to what the durable
            // selection preference says. Added 2026-08-26 without a contract-version bump: absence
            // is meaningful and means "this daemon cannot attest ownership".
            "fanHardwareDutyOwned",
            "fanCurveBiasMin", "fanCurveBiasMax", "fanCurveBiasStep", "fanCurveSensorType",
            "fanControlMode", "fanCurveBias", "generation", "transactionId",
            "ownerRequestId", "profileId"));

    /**
     * Every key here is read by the client. That is unusual across this set -- the other four
     * contracts carry 27 keys between them that cross the wire and are read by nobody -- and it
     * makes sense: the fan section exists to be rendered, so nothing is shipped speculatively.
     */
    public static final List<String> CONSUMED_BY_CLIENT = ALL;

    /** Nothing, and the client test proves it rather than assuming it. */
    public static final List<String> NOT_CONSUMED_BY_CLIENT = Collections.emptyList();

    private TweakerAdvancedControlsContract() {
    }
}
