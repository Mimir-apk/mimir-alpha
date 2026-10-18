package com.gruning.mimir.tweaker;

/**
 * API v10 — the discovered clock ceiling, WRITTEN by the daemon.
 *
 * <p>the daemon owns the clock node whenever it is present; the frontend's
 * discovered tuner routes through it and writes directly only on a device with no daemon. The write path is deterministic and recoverable
 * from any state.
 *
 * <p>Shape rules, inherited from v9 and kept on purpose:
 * <ul>
 *   <li>No sysfs path crosses this boundary. The caller names an AXIS, a POLICY ID and a kHz; the
 *       daemon re-admits every value against its OWN discovery, snaps it onto the published table and
 *       (CPU) clamps it to the live floor itself.</li>
 *   <li>Every reply carries {@link #KEY_API_VERSION} and an {@link #KEY_OUTCOME}; a refusal carries a
 *       {@link #KEY_REFUSAL_CODE} and a {@link #KEY_REFUSAL_DETAIL}.</li>
 *   <li>A ceiling is player-owned and boot-bound: it is not a leased session, it arms no Guardian
 *       envelope, and it is restored only by withdraw (the player's press) or by the daemon itself
 *       immediately before it applies a named profile over it.</li>
 * </ul>
 */
public final class TweakerDiscoveredCeilingContract {
    private TweakerDiscoveredCeilingContract() {}

    public static final int MIN_API_VERSION = 10;

    public static final int AXIS_CPU = 0;
    public static final int AXIS_GPU = 1;
    /** The GPU is not a cpufreq policy; the frontend already uses this id for it. */
    public static final int GPU_POLICY_ID = -1;

    public static final String KEY_API_VERSION = "apiVersion";
    public static final String KEY_OUTCOME = "outcome";
    public static final String OUTCOME_APPLIED = "APPLIED";
    public static final String OUTCOME_WITHDRAWN = "WITHDRAWN";
    public static final String OUTCOME_NOTHING_HELD = "NOTHING_HELD";
    public static final String OUTCOME_HELD = "HELD";
    public static final String OUTCOME_NOT_HELD = "NOT_HELD";
    public static final String OUTCOME_REFUSED = "REFUSED";

    public static final String KEY_REFUSAL_CODE = "refusalCode";
    public static final String KEY_REFUSAL_DETAIL = "refusalDetail";
    /** A named profile session is active (any V4/V5 session state). The profile is the richer truth. */
    public static final String REFUSAL_PROFILE_ACTIVE = "PROFILE_ACTIVE";
    /** This build cannot write hardware ({@code HARDWARE_WRITES_COMPILED} false). */
    public static final String REFUSAL_POSTURE_NOT_ADMITTED = "POSTURE_NOT_ADMITTED";
    /** No PServer on this device, or the transaction could not be delivered. */
    public static final String REFUSAL_HOST_UNAVAILABLE = "HOST_UNAVAILABLE";
    /** The transaction was delivered and the readback did not take the value. */
    public static final String REFUSAL_WRITE_NOT_HELD = "WRITE_NOT_HELD";
    /** The record could not be persisted; nothing was written to hardware. */
    public static final String REFUSAL_STORE_UNAVAILABLE = "STORE_UNAVAILABLE";
    /** The policy id is not one this device published for that axis. */
    public static final String REFUSAL_UNDISCOVERED_POLICY = "UNDISCOVERED_POLICY";
    // Discovery refusals reuse the v9 admission codes verbatim.
    public static final String REFUSAL_UNDISCOVERED_CPU = "UNDISCOVERED_CPU";
    public static final String REFUSAL_UNDISCOVERED_FREQUENCY = "UNDISCOVERED_FREQUENCY";
    public static final String REFUSAL_NO_GPU_AXIS = "NO_GPU_AXIS";
    public static final String REFUSAL_UNDISCOVERED_POWER_LEVEL = "UNDISCOVERED_POWER_LEVEL";

    public static final String KEY_AXIS = "axis";
    public static final String KEY_POLICY_ID = "policyId";
    public static final String KEY_REQUESTED_KHZ = "requestedKhz";
    /** After the table snap and (CPU) the live-floor clamp: what the daemon actually asked the kernel for. */
    public static final String KEY_ADMITTED_KHZ = "admittedKhz";
    /** Read back after the write: {@code scaling_max_freq} for CPU, {@code max_pwrlevel} as kHz for GPU. */
    public static final String KEY_IN_FORCE_KHZ = "inForceKhz";
    public static final int UNREADABLE_KHZ = -1;

    public static final String KEY_HELD = "held";
    /**
     * Query only: whether any value the record asked for is actually on the node right now.
     *
     * <p>{@link #KEY_HELD} is a fact about the daemon's RECORD; this is a fact about the HARDWARE,
     * and on a device whose clock node has a second writer the two come apart. The vendor
     * performance HAL's app-launch boost/release resets a capped CPU to {@code INT_MAX} with no
     * daemon involvement at all (AYN Thor, 2026-09-11), so a query answering from the record alone
     * would keep reporting a cap that evaporated. A query that finds none of its values on the node
     * attempts one immediate repair before answering; {@code held=true} with {@code inForce=false}
     * means the way back still exists and the cap does not currently, which is the honest thing for
     * a surface to draw. Absent from apply and withdraw replies.
     */
    public static final String KEY_IN_FORCE = "inForce";
    public static final String KEY_BOOT_IDENTITY = "bootIdentity";
    public static final String KEY_CPU_STOCK_ROW = "cpuStockRow";
    public static final String KEY_CPU_REQUESTED_ROW = "cpuRequestedRow";
    public static final String KEY_GPU_STOCK_MIN_LEVEL = "gpuStockMinLevel";
    public static final String KEY_GPU_STOCK_MAX_LEVEL = "gpuStockMaxLevel";
    public static final String KEY_GPU_REQUESTED_MIN_LEVEL = "gpuRequestedMinLevel";
    public static final String KEY_GPU_REQUESTED_MAX_LEVEL = "gpuRequestedMaxLevel";
    /** {@code -1}: no GPU value. Absence, never a level. Same spelling as v9. */
    public static final int ABSENT_LEVEL = -1;

    /** Withdraw: how many CPUs were still holding the daemon's value and were given back. */
    public static final String KEY_RESTORED_CPUS = "restoredCpus";
    /**
     * Withdraw: how many CPUs had MOVED since the daemon wrote them and were therefore left alone.
     * Another writer owns those now; stamping the captured value over theirs would be the daemon
     * undoing a change it never made and cannot see.
     */
    public static final String KEY_LEFT_ALONE_CPUS = "leftAloneCpus";
    /** Withdraw: whether the Adreno bounds were put back (false when none were ours to give back). */
    public static final String KEY_GPU_RESTORED = "gpuRestored";

    /**
     * A named-profile apply refusal, NOT a ceiling refusal: a discovered ceiling is held and the
     * daemon could not withdraw it first. The profile is refused rather than allowed to capture the
     * ceiling as its own stock — a restore that faithfully puts back a cap is the one failure mode
     * nothing anywhere reports.
     */
    public static final String REFUSAL_CEILING_HELD = "CEILING_HELD";

    /**
     * A named-profile apply refusal for the case the daemon could not even establish whether a
     * discovered ceiling is held (the check threw, or the record that says so could not be read).
     * Nothing was changed. Unlike {@link #REFUSAL_CEILING_HELD} it does not claim a ceiling exists:
     * a client treats it as a refusal exactly like {@code CEILING_HELD}, and may retry.
     */
    public static final String REFUSAL_CEILING_UNCONFIRMED = "CEILING_UNCONFIRMED";
}
