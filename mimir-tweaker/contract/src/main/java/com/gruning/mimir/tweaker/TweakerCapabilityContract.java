package com.gruning.mimir.tweaker;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The capabilities Bundle contract, declared once in the module BOTH builds share.
 *
 * <p>The daemon ({@code mimir-tweaker}) and the client ({@code core-tweaker-client}) live in
 * separate Gradle builds. They marshal this Bundle through the AIDL in this same module, but until
 * now each side spelled every key as its own string literal, and each side's tests built their own
 * fixture. So the two could disagree and nothing failed: a rename on one side yields a silent
 * default on the other, and a key the daemon starts shipping that the client never reads is
 * invisible. That is exactly how {@code supportedUncertifiedProfileIds} came to be computed,
 * marshalled and then dropped on the floor -- chipset-support stage 3 shipped half-wired and the
 * whole suite stayed green.
 *
 * <p>This class is the single source of truth those two sides are checked against.
 * {@code HardwareCapabilitiesBundleContractTest} (daemon) asserts the real marshaller emits exactly
 * these keys; {@code TweakerCapabilitiesBundleContractTest} (client) asserts the real parser
 * consumes every key in {@link #CONSUMED_BY_CLIENT} and that {@link #NOT_CONSUMED_BY_CLIENT}
 * accounts for the rest. Adding a key to the daemon without classifying it here reddens the daemon
 * test; classifying it as consumed without wiring the client reddens the client test.
 *
 * <p>Keys only, deliberately. Sharing the marshaller itself would mean moving the daemon's hardware
 * model types into this module, which is a far larger change than the drift it would prevent.
 */
public final class TweakerCapabilityContract {

    public static final String STATUS = "status";
    public static final String MANUFACTURER = "manufacturer";
    public static final String MODEL = "model";
    public static final String SOC_MODEL = "socModel";
    public static final String BUILD_FINGERPRINT = "buildFingerprint";
    public static final String HARDWARE_FINGERPRINT = "hardwareFingerprint";
    public static final String DEVICE_PROFILE_ID = "deviceProfileId";
    public static final String HARDWARE_FAMILY_ID = "hardwareFamilyId";
    public static final String PROFILE_TREATMENT = "profileTreatment";
    public static final String SUPPORTED_AXES = "supportedAxes";
    public static final String PSERVER_AVAILABLE = "pServerAvailable";
    public static final String GPU_NODE_AVAILABLE = "gpuNodeAvailable";
    public static final String FAN_NODE_AVAILABLE = "fanNodeAvailable";
    public static final String REFRESH_RATES_HZ = "refreshRatesHz";
    public static final String CPU_POLICIES = "cpuPolicies";
    public static final String NOTES = "notes";
    public static final String CERTIFIED_PROFILE_IDS = "certifiedProfileIds";
    public static final String QUALIFIED_PROFILE_IDS = "qualifiedProfileIds";
    public static final String SUPPORTED_UNCERTIFIED_PROFILE_IDS = "supportedUncertifiedProfileIds";
    public static final String POSTURE = "posture";
    public static final String MUTATION_AVAILABLE = "mutationAvailable";
    public static final String DRY_RUN = "dryRun";
    public static final String QUALIFICATION_SOURCE = "qualificationSource";
    public static final String DIAGNOSTIC_PROFILE_IDS = "diagnosticProfileIds";
    public static final String DIAGNOSTIC_MUTATION_AVAILABLE = "diagnosticMutationAvailable";
    public static final String USAGE_ACCESS_GRANTED = "usageAccessGranted";
    public static final String HAS_USAGE_ACCESS_GRANTED = "hasUsageAccessGranted";

    /** Every key the capabilities Bundle may carry. */
    public static final List<String> ALL = Collections.unmodifiableList(Arrays.asList(
            STATUS, MANUFACTURER, MODEL, SOC_MODEL, BUILD_FINGERPRINT, HARDWARE_FINGERPRINT,
            DEVICE_PROFILE_ID, HARDWARE_FAMILY_ID, PROFILE_TREATMENT, SUPPORTED_AXES,
            PSERVER_AVAILABLE, GPU_NODE_AVAILABLE, FAN_NODE_AVAILABLE, REFRESH_RATES_HZ,
            CPU_POLICIES, NOTES, CERTIFIED_PROFILE_IDS, QUALIFIED_PROFILE_IDS,
            SUPPORTED_UNCERTIFIED_PROFILE_IDS, POSTURE, MUTATION_AVAILABLE, DRY_RUN,
            QUALIFICATION_SOURCE, DIAGNOSTIC_PROFILE_IDS, DIAGNOSTIC_MUTATION_AVAILABLE,
            USAGE_ACCESS_GRANTED, HAS_USAGE_ACCESS_GRANTED));

    /**
     * Written only when the value exists. {@link #USAGE_ACCESS_GRANTED} is absent when usage access
     * is unknown -- {@link #HAS_USAGE_ACCESS_GRANTED} is what distinguishes "unknown" from "false",
     * and it is always written.
     */
    public static final List<String> CONDITIONAL = Collections.unmodifiableList(Arrays.asList(
            HARDWARE_FAMILY_ID, PROFILE_TREATMENT, USAGE_ACCESS_GRANTED));

    /** Keys the client's capabilities parser is required to read. */
    public static final List<String> CONSUMED_BY_CLIENT = Collections.unmodifiableList(Arrays.asList(
            STATUS, HARDWARE_FINGERPRINT, DEVICE_PROFILE_ID, HARDWARE_FAMILY_ID, PROFILE_TREATMENT,
            SUPPORTED_AXES, NOTES, CERTIFIED_PROFILE_IDS, QUALIFIED_PROFILE_IDS,
            SUPPORTED_UNCERTIFIED_PROFILE_IDS, POSTURE, MUTATION_AVAILABLE, DRY_RUN,
            QUALIFICATION_SOURCE, DIAGNOSTIC_PROFILE_IDS, DIAGNOSTIC_MUTATION_AVAILABLE,
            USAGE_ACCESS_GRANTED, HAS_USAGE_ACCESS_GRANTED));

    /**
     * Marshalled on every capabilities call and read by nobody, as of 2026-08-09. Recorded rather
     * than deleted because the cost is a few hundred bytes per call and removing a field from a
     * shipped contract is a compatibility decision, not a cleanup. Two are worth a second look
     * before anyone builds on them: {@link #REFRESH_RATES_HZ} is the daemon's own list of supported
     * refresh rates, which the display-rate feature does not consult, and {@link #CPU_POLICIES}
     * carries the live OPP topology that chipset-support work re-derives independently.
     */
    public static final List<String> NOT_CONSUMED_BY_CLIENT = Collections.unmodifiableList(Arrays.asList(
            MANUFACTURER, MODEL, SOC_MODEL, BUILD_FINGERPRINT, PSERVER_AVAILABLE,
            GPU_NODE_AVAILABLE, FAN_NODE_AVAILABLE, REFRESH_RATES_HZ, CPU_POLICIES));

    private TweakerCapabilityContract() {
    }
}
