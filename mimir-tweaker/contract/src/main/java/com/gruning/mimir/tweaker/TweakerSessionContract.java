package com.gruning.mimir.tweaker;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The transaction-result and V4 session Bundle contract. Third of the set, after
 * {@link TweakerCapabilityContract} and {@link TweakerTelemetryContract}; see the first for why
 * these live in the module both Gradle builds share.
 *
 * <p>Two declared parts, because there are two producers. {@link #RESULT} is
 * {@code TransactionResult.toBundle()} -- a pure function, pinned on both sides. {@link #SESSION}
 * is what the service layers on top when answering a V4 session query: lease deadlines, caller
 * identity, session state. That composition reads live supervisor state and has no callable seam,
 * so those keys are declared here and enforced client-side, the same treatment the fan readout
 * gets in the telemetry contract.
 *
 * <p>This is the bundle that carries every apply, restore and derate outcome back to the UI, so a
 * dropped key here is how a control comes to advertise something the layer beneath refused -- the
 * defect class this codebase hit four separate times in one session.
 */
public final class TweakerSessionContract {

    /** Written by {@code TransactionResult.toBundle()}. */
    public static final List<String> RESULT = Collections.unmodifiableList(Arrays.asList(
            "success", "applied", "restored", "code", "message",
            "profileId", "packageName", "requestId", "transactionId", "generation",
            "calibrated", "cpuMaxKhz", "gpuMaxPowerLevel", "fanMode",
            "requestDeadlineElapsedRealtimeMs", "hardLeaseDeadlineElapsedRealtimeMs"));

    /** Always written, whatever the outcome. The rest describe a plan that may not exist. */
    public static final List<String> RESULT_ALWAYS = Collections.unmodifiableList(Arrays.asList(
            "success", "applied", "restored", "code", "message"));

    /** Layered on by the service when answering a V4 session query. */
    public static final List<String> SESSION = Collections.unmodifiableList(Arrays.asList(
            "sessionState", "activeProfileId", "apiVersion", "leaseRenewable",
            "mutationMayBeActive", "callerRequestId", "callerDeadlineElapsedRealtimeMs",
            "leaseDeadlineElapsedRealtimeMs", "sessionScope"));

    /** Every key either bundle may carry. */
    public static final List<String> ALL;

    static {
        java.util.List<String> all = new java.util.ArrayList<>(RESULT);
        all.addAll(SESSION);
        ALL = Collections.unmodifiableList(all);
    }

    /**
     * Read by {@code TweakerApplyResult.fromBundle} or {@code parseTweakerV4SessionBundle}, which
     * consume overlapping subsets of the same wire shape.
     */
    public static final List<String> CONSUMED_BY_CLIENT = Collections.unmodifiableList(Arrays.asList(
            "success", "applied", "restored", "code", "message", "profileId",
            "requestId", "transactionId", "generation",
            "requestDeadlineElapsedRealtimeMs", "hardLeaseDeadlineElapsedRealtimeMs",
            "sessionState", "activeProfileId", "apiVersion", "leaseRenewable",
            "mutationMayBeActive", "callerRequestId", "callerDeadlineElapsedRealtimeMs",
            "leaseDeadlineElapsedRealtimeMs", "sessionScope"));

    /**
     * Marshalled on every apply, restore and derate outcome and read by nobody, as of 2026-08-09.
     * These four describe the applied PLAN -- the CPU ceilings, GPU power level and fan mode the
     * transaction actually wrote, plus whether the plan was calibrated. The client renders profile
     * state from its own model instead, so the authoritative account of what landed on the hardware
     * crosses the wire and is discarded. Worth a decision rather than a shrug: this is exactly the
     * data a UI would need to show what is really applied rather than what was requested.
     */
    public static final List<String> NOT_CONSUMED_BY_CLIENT = Collections.unmodifiableList(Arrays.asList(
            "packageName", "calibrated", "cpuMaxKhz", "gpuMaxPowerLevel", "fanMode"));

    private TweakerSessionContract() {
    }
}
