package com.gruning.mimir.tweaker;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The FPS-limiter Bundle contract. Fourth of the set; see {@link TweakerCapabilityContract} for why
 * these live in the module both Gradle builds share.
 *
 * <p>Four wire shapes built by four pure functions in {@code FpsLimiterPublicBundles.kt} and read
 * by four parsers in {@code TweakerFpsLimiter.kt}. Unlike the session contract every producer here
 * is pure, so both halves are directly pinnable.
 *
 * <p>The four shapes are declared separately rather than as one list. They are genuinely different
 * conversations -- a capability answer describes what a package could do, a query describes what is
 * currently owned, and apply/restore report an outcome against a caller's exact request -- and the
 * apply and restore parsers validate the daemon's echo of the caller's own identity, so a merged
 * fixture would be rejected wholesale by one parser while satisfying another.
 */
public final class TweakerFpsLimiterContract {

    /**
     * Every key any of the four FPS bundles may carry.
     *
     * <p>Deliberately one list rather than four per-shape lists. Each builder branches on its
     * outcome -- an applied limit carries an owner block a rejection does not, a query answer
     * carries one only while a limit is held -- so a per-shape "exactly these keys" claim would be
     * true of one branch and false of the next. An unenforceable declaration is worse than none:
     * the first draft here carried four such lists and one of them was already missing
     * {@code message}, with no test able to notice.
     */
    public static final List<String> ALL = Collections.unmodifiableList(Arrays.asList(
            "success", "code", "message", "apiVersion",
            "packageName", "requestedPackageName", "frameLimitsHz", "targetFps",
            "state", "phase", "mutationMayBeActive", "restoreDebtRetained",
            "ownerRequestId", "callerRequestId", "callerDeadlineElapsedRealtimeMs",
            "generation", "transactionId"));

    /** Written by all four builders, on every branch, whatever the outcome. */
    public static final List<String> ALWAYS = Collections.unmodifiableList(Arrays.asList(
            "success", "code", "apiVersion"));

    /**
     * Keys the client's four FPS parsers are required to read.
     *
     * <p>The last five are the owner block a query answer carries when a limit is actually held --
     * {@code ownerRequestId}, {@code packageName}, {@code targetFps}, {@code generation} and
     * {@code transactionId} reconstruct a {@code TweakerFpsOwner} exactly. Worth noting because
     * this contract first declared three of them unread, on the reasoning that
     * {@code generation}/{@code transactionId} belong to the session bundles and that the client
     * renders the limit it requested rather than the one held. The client test disproved it: they
     * are read here, and unlike the applied plan in {@link TweakerSessionContract} the FPS surface
     * really does report what the daemon is holding.
     */
    public static final List<String> CONSUMED_BY_CLIENT = Collections.unmodifiableList(Arrays.asList(
            "success", "code", "apiVersion", "message",
            "requestedPackageName", "frameLimitsHz", "state",
            "mutationMayBeActive", "restoreDebtRetained",
            "callerRequestId", "callerDeadlineElapsedRealtimeMs",
            "ownerRequestId", "packageName", "targetFps", "generation", "transactionId"));

    /**
     * Unread as of 2026-08-09. {@code phase} is the daemon's own journal phase for the FPS record;
     * the client derives everything it shows from {@code state} and the owner block instead.
     */
    public static final List<String> NOT_CONSUMED_BY_CLIENT = Collections.unmodifiableList(Arrays.asList(
            "phase"));

    private TweakerFpsLimiterContract() {
    }
}
