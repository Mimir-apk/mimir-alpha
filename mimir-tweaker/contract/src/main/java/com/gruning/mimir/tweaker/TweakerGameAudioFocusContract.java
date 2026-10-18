package com.gruning.mimir.tweaker;

/**
 * API v11 — a game Mimir launches cannot take audio focus, so the music already playing carries on.
 *
 * <p>Policy 2026-10-01. Device evidence (Nova, 2026-10-01): an emulator that takes audio focus at
 * launch makes YouTube Music pause and never resume, because that player's own listener reports
 * {@code shouldResumeOnAudioFocusGain=false}. Android's {@code TAKE_AUDIO_FOCUS} app-op, set to
 * {@code ignore} for the game's package, makes that package's {@code requestAudioFocus} fail while its
 * audio still plays.
 *
 * <p>Shape rules, in the family of v6..v10 and kept on purpose:
 * <ul>
 *   <li>The only thing a caller names is ONE package. No command, path, op name, mode or key crosses
 *       this boundary: the daemon owns three fixed command templates and the one validated argument is
 *       the package. The API cannot carry anything else rather than merely declining to.</li>
 *   <li>Fail closed. A hold is reported {@link #OUTCOME_HELD} only after the app-op READS BACK as
 *       {@code ignore}; otherwise the daemon immediately puts the package back and says
 *       {@link #OUTCOME_REFUSED}. The failure state is the game keeping normal focus behaviour.</li>
 *   <li>At most one package is ever held; a new hold releases the previous one first.</li>
 *   <li>The daemon journals the hold and releases it on: the client Binder's death, a daemon restart, a
 *       boot, a new hold, or an explicit release. App-ops persist across reboot, so a boot never merely
 *       forgets the record.</li>
 *   <li>Reachable in every posture, like the display-rate write: an app-op is a per-app preference, not
 *       thermal or clock state. No qualification, certification or Guardian participation.</li>
 * </ul>
 */
public final class TweakerGameAudioFocusContract {
    private TweakerGameAudioFocusContract() {}

    public static final int MIN_API_VERSION = 11;

    public static final String KEY_API_VERSION = "apiVersion";
    public static final String KEY_OUTCOME = "outcome";
    /** The package held, released or refused. Absent when none applies. */
    public static final String KEY_PACKAGE_NAME = "packageName";

    /** The app-op read back as ignore; the package can no longer take audio focus. */
    public static final String OUTCOME_HELD = "HELD";
    /** The app-op was put back and the readback confirmed it. */
    public static final String OUTCOME_RELEASED = "RELEASED";
    /** Nothing was held, so nothing was done. */
    public static final String OUTCOME_NOTHING_HELD = "NOTHING_HELD";
    /** Query: no package is held. */
    public static final String OUTCOME_NOT_HELD = "NOT_HELD";
    /**
     * Query: the daemon could not establish the state (the worker was busy or failed, the journal is damaged, or the
     * app-op could not be read back). It is NOT {@link #OUTCOME_NOT_HELD}: "I could not ask" is not "nothing is held".
     */
    public static final String OUTCOME_UNAVAILABLE = "UNAVAILABLE";
    /** The request was refused; the game keeps normal focus behaviour (see {@link #KEY_RELEASE_DEBT}). */
    public static final String OUTCOME_REFUSED = "REFUSED";
    /** A release could not be confirmed; the journal still names the package and the next release, hold or daemon start retries. */
    public static final String OUTCOME_RETAINED = "RETAINED";

    public static final String KEY_REFUSAL_CODE = "refusalCode";
    public static final String KEY_REFUSAL_DETAIL = "refusalDetail";
    /**
     * True when a refusal left a put-back UNCONFIRMED, so the journal still holds the package and
     * the next release, hold or daemon start retries. False on every clean refusal.
     */
    public static final String KEY_RELEASE_DEBT = "releaseDebt";

    public static final String REFUSAL_INVALID_PACKAGE = "INVALID_PACKAGE";
    /** A Mimir package ({@code com.gruning.mimir.*}), including this daemon. */
    public static final String REFUSAL_SELF_PACKAGE = "SELF_PACKAGE";
    public static final String REFUSAL_NOT_INSTALLED = "NOT_INSTALLED";
    public static final String REFUSAL_SYSTEM_APP = "SYSTEM_APP";
    public static final String REFUSAL_CLIENT_TOKEN_DEAD = "CLIENT_TOKEN_DEAD";
    /** No privileged transport on this device, or the command could not be delivered. */
    public static final String REFUSAL_HOST_UNAVAILABLE = "HOST_UNAVAILABLE";
    /** The previous package could not be put back, so no new hold was started. */
    public static final String REFUSAL_RELEASE_PREVIOUS_FAILED = "RELEASE_PREVIOUS_FAILED";
    /** The journal could not be written, so nothing was changed. */
    public static final String REFUSAL_JOURNAL_UNAVAILABLE = "JOURNAL_UNAVAILABLE";
    /** The command ran and the readback did not report ignore. */
    public static final String REFUSAL_WRITE_NOT_HELD = "WRITE_NOT_HELD";
    /**
     * The privileged route did not answer a readback inside its bound, or the transaction worker was busy. Nothing is
     * claimed: the hold is not reported, and any put-back left unconfirmed is {@link #KEY_RELEASE_DEBT}.
     */
    public static final String REFUSAL_SERVICE_BUSY = "SERVICE_BUSY";
}
