package com.gruning.mimir.tweaker;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Vendor {@code fan_mode} values, declared once in the module both Gradle builds share.
 *
 * <p>These crossed the build boundary by value, not by reference. {@code TuningPlanner} held the
 * daemon's set and {@code TweakerModels} held a hand-copied {@code setOf(5, 6)} with a comment
 * saying, accurately, that drift between the two was NOT automatically caught and that the copy had
 * to be updated by hand. That is the same class of gap as every literal-keyed Bundle in this
 * codebase, and it matters more than most: the client uses it to decide whether a duty reading in
 * the telemetry bundle is Mimir's own value or the vendor's independent loop, so a stale copy would
 * make the panel report the vendor's duty as though Mimir had set it.
 *
 * <p>Both sides now reference this. A change here is a compile-time change on both.
 */
public final class TweakerVendorFanModes {

    public static final int SILENT = 1;
    public static final int SMART = 4;
    public static final int SPORT = 5;
    /** Vendor manual passthrough; duty is owned only by the authenticated fan controller. */
    public static final int MANUAL = 6;

    /**
     * Modes under which Mimir's authenticated fan controller directly drives PWM duty.
     *
     * <p>Device-measured, commit {@code 87f02042}: the vendor's own
     * duty-regulation loop is a 4000 ms {@code postDelayed} runnable that only re-posts while
     * {@code fan_mode == SMART}; both SPORT and MANUAL stop that loop, and a direct duty write
     * persists under either. SMART is deliberately excluded -- it is the one mode whose loop still
     * fights a direct duty write.
     */
    public static final Set<Integer> MIMIR_DUTY_OWNED = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(SPORT, MANUAL)));

    private TweakerVendorFanModes() {
    }
}
