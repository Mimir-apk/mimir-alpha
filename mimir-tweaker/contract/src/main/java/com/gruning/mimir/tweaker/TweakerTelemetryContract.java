package com.gruning.mimir.tweaker;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The telemetry Bundle contract. Companion to {@link TweakerCapabilityContract}; see that class for
 * why these live in the module both Gradle builds share.
 *
 * <p>Telemetry is assembled by THREE producers, not one --
 * {@code sample.telemetry.toBundle().putLivePerformanceTelemetry(sample.live).putActiveFanControlReadout()}
 * in {@code MimirTweakerService.getTelemetry}. Splitting the declaration the same way is
 * deliberate: a key silently moving between producers, or a producer dropping out of the chain, is
 * a real failure mode and one flat list would hide it.
 *
 * <p>{@link #FAN_READOUT} is the one part the daemon test cannot exercise directly:
 * {@code putActiveFanControlReadout} is a private service member that reads live supervisor
 * session state, so there is no pure function to call. Those keys are still declared and still
 * enforced on the client side, which is where the consumption drift actually bit.
 */
public final class TweakerTelemetryContract {

    /** Written by {@code TelemetrySnapshot.toBundle()}. All but the CPU rows are conditional. */
    public static final List<String> SNAPSHOT = Collections.unmodifiableList(Arrays.asList(
            "cpuCurrentKhz", "cpuMinKhz", "cpuMaxKhz",
            "gpuClockHz", "gpuBusyPercent", "gpuMinPowerLevel", "gpuMaxPowerLevel",
            "fanDuty", "fanPeriod", "fanRpm", "vendorFanMode",
            "cpuTempC", "cpuPackageTempC", "cpuPackageTempZoneCount", "gpuTempC",
            "batteryLevelPercent", "batteryCurrentUa", "batteryVoltageMv", "batteryPowerMw",
            "batteryTempDeciC", "batteryStatus", "externalPowerState", "thermalStatus"));

    /** Always written by {@code TelemetrySnapshot.toBundle()}, whatever the sample holds. */
    public static final List<String> SNAPSHOT_ALWAYS = Collections.unmodifiableList(Arrays.asList(
            "cpuCurrentKhz", "cpuMinKhz", "cpuMaxKhz", "externalPowerState"));

    /** Written by {@code Bundle.putLivePerformanceTelemetry(sample)}. */
    public static final List<String> LIVE = Collections.unmodifiableList(Arrays.asList(
            "sampledAtElapsedRealtimeMs", "cpuBusyPercent", "foregroundPackageName", "frameStatus",
            "frameLayerName", "frameAverageFpsMilli", "frameCount", "frameWindowDurationMs",
            "frameLatestPresentAgeMs"));

    /** Always written by {@code Bundle.putLivePerformanceTelemetry(sample)}. */
    public static final List<String> LIVE_ALWAYS = Collections.unmodifiableList(Arrays.asList(
            "sampledAtElapsedRealtimeMs", "frameStatus"));

    /** Written by {@code MimirTweakerService.putActiveFanControlReadout()} when a session owns fan. */
    public static final List<String> FAN_READOUT = Collections.unmodifiableList(Arrays.asList(
            "activeFanProfileId", "fanControlMode", "fanControlStrategy", "fanCurveBias",
            "fanPowerReferenceMw", "fanTargetTempC"));

    /** Every key the telemetry Bundle may carry, across all three producers. */
    public static final List<String> ALL;

    static {
        java.util.List<String> all = new java.util.ArrayList<>();
        all.addAll(SNAPSHOT);
        all.addAll(LIVE);
        all.addAll(FAN_READOUT);
        ALL = Collections.unmodifiableList(all);
    }

    /** Keys the client's telemetry parser is required to read. */
    public static final List<String> CONSUMED_BY_CLIENT = Collections.unmodifiableList(Arrays.asList(
            "cpuCurrentKhz", "gpuClockHz", "gpuBusyPercent", "cpuTempC", "cpuPackageTempC",
            "cpuPackageTempZoneCount", "gpuTempC", "batteryPowerMw", "batteryStatus",
            "fanDuty", "fanPeriod", "fanRpm", "vendorFanMode",
            "sampledAtElapsedRealtimeMs", "cpuBusyPercent", "foregroundPackageName", "frameStatus",
            "frameAverageFpsMilli", "frameCount", "frameWindowDurationMs", "frameLatestPresentAgeMs",
            "activeFanProfileId", "fanControlStrategy", "fanPowerReferenceMw", "fanTargetTempC"));

    /**
     * Marshalled on every telemetry call and read by nobody, as of 2026-08-09. Telemetry is polled
     * about once a second while a session is live, so this is the hottest dead payload in the
     * product -- but removing a field from a shipped contract is a compatibility decision, so they
     * are declared rather than deleted, and will now trip a test the moment either side changes.
     *
     * <p>Two are worth a decision rather than a shrug. {@code thermalStatus} is the Android thermal
     * status the supervisor derates on, and {@code externalPowerState} is the charge-separation
     * signal the efficiency work needs -- neither reaches the UI today.
     */
    public static final List<String> NOT_CONSUMED_BY_CLIENT = Collections.unmodifiableList(Arrays.asList(
            "cpuMinKhz", "cpuMaxKhz", "gpuMinPowerLevel", "gpuMaxPowerLevel",
            "batteryLevelPercent", "batteryCurrentUa", "batteryVoltageMv", "batteryTempDeciC",
            "externalPowerState", "thermalStatus", "frameLayerName",
            "fanControlMode", "fanCurveBias"));

    private TweakerTelemetryContract() {
    }
}
