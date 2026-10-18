package com.gruning.mimir.rewrite.core.model

/**
 * A fully-resolved, ready-to-dispatch launch instruction: *this* game, launched with *this*
 * emulator, using *this* locator. Produced by `core-launch` when [LaunchReadiness] is
 * [LaunchReadiness.Ready], and also carried by [LaunchReadiness.NeedsBios] when a confirmed-missing
 * BIOS resolved a full target anyway (see [LaunchReadiness.NeedsBios]); it
 * is the typed argument to the native `launchPlacedGameWithTarget` bridge (`platformId,
 * launchLocator, title, targetPackageKey`).
 *
 * @property gameId the game being launched (group identity — a favorite/override keys off this).
 * @property platformId the resolved launch platform (for RetroArch, the catalog long-id IS the platform id).
 * @property emulator the resolved emulator choice (Auto default or per-game Open-With override).
 * @property locator the ROM locator string in the form [locatorForm] requires.
 * @property locatorForm which form [locator] is in (so the native side picks the right intent extra).
 * @property title human-facing title passed to the emulator/telemetry.
 * @property overrideApplied true when a per-game Open-With override selected [emulator] (vs the Auto default).
 */
data class LaunchTarget(
    val gameId: GameId,
    val platformId: PlatformId,
    val emulator: EmulatorProfile,
    val locator: String,
    val locatorForm: LaunchLocatorForm,
    val title: String,
    val overrideApplied: Boolean = false,
)

/**
 * The launch *policy* for one game before a concrete [LaunchTarget] is resolved — i.e. the inputs to
 * the readiness decision. Separates "what do we know / what did the user choose" (this) from "can we
 * launch right now" ([LaunchReadiness]) and "here is the exact instruction" ([LaunchTarget]).
 *
 * @property gameId the game.
 * @property platformId its platform.
 * @property overrideEmulatorId optional per-game Open-With override (`settings.gameLaunchers[gameId]`); null = Auto.
 * @property launchMemberEntryKey the specific group member (disc / storage sibling) to launch; null = provider picks the group's default member (grouped/multi-disc must not launch the wrong member).
 * @property preferredDisc for a multi-disc group, the disc to start with (default: lowest present, emulator swaps). Advisory when [launchMemberEntryKey] is null.
 * @property requiresBios whether the platform requires a BIOS to run at all (drives [LaunchReadiness.NeedsBios]).
 */
data class LaunchProfile(
    val gameId: GameId,
    val platformId: PlatformId,
    val overrideEmulatorId: EmulatorId? = null,
    val launchMemberEntryKey: EntryKey? = null,
    val preferredDisc: Int = 0,
    val requiresBios: Boolean = false,
) {
    /** True when the user pinned a specific emulator for this game (Open-With override present). */
    val hasOverride: Boolean get() = overrideEmulatorId != null
}

/**
 * Why a launch attempt failed to *dispatch*, mirroring the native `mimirLaunchFailureResult.v1`
 * `reason` field. Dispatch-only scope: silent
 * post-dispatch death detection was RETIRED from the product as jargon — this models only the
 * synchronous dispatch failures the launcher actually reports, never a timing heuristic. The
 * dispatch adapter reads the native **`reason`** string (see per-value notes), not just `code`,
 * because native `code` is coarser than `reason`.
 */
enum class LaunchDispatchFailure {
    /**
     * No target could be resolved (Open-With points at nothing). Native `code:"LAUNCH_TARGET_MISSING"`
     * / `reason:"target_missing"`.
     */
    TARGET_MISSING,

    /**
     * The resolved target's package is not installed. NOTE: native `code` still says
     * `LAUNCH_TARGET_MISSING` here — the adapter MUST distinguish this by the native
     * `reason:"target_not_installed"`, not by `code`, or it will collapse into [TARGET_MISSING].
     */
    TARGET_NOT_INSTALLED,

    /** The ROM file/locator is missing at launch time. Native `reason:"rom_path_missing"`. */
    ROM_PATH_MISSING,

    /**
     * Emulator wanted a file path; a content URI was supplied (or vice-versa). Covers native
     * `reason:"file_path_required"` and `reason:"content_uri_required"` — a WRONG locator FORM.
     */
    LOCATOR_FORM_REJECTED,

    /**
     * A modelled content URI is no longer readable — the SAF grant was lost (native
     * `reason:"content_uri_unavailable"`). Distinct from [LOCATOR_FORM_REJECTED]: the form was right,
     * but the exact document URI can no longer be opened (DS/3DS/Switch SAF grant revoked/moved).
     */
    CONTENT_URI_UNAVAILABLE,

    /** The launch profile was never proven launchable for this platform/emulator pair. Native `reason:"launch_profile_unproven"`. */
    LAUNCH_PROFILE_UNPROVEN,

    /** The intent was constructed but Android refused to start the activity. Native `reason:"activity_not_started"`. */
    ACTIVITY_NOT_STARTED,

    /**
     * The locator resolved to a real, readable file, but its CONTENT could not be parsed into a valid
     * launch payload — e.g. a GameNative shortcut whose body is not a positive decimal `app_id`, or
     * whose extension maps to no known launch source. Distinct from [LOCATOR_FORM_REJECTED]: the FORM
     * (path vs URI) was correct; the file's CONTENT was unusable. These files are managed by an external
     * app and can be malformed by anything that edits the card, so this is read defensively rather than
     * trusted (`core-launch`'s `GameNativeLaunchRoute`).
     */
    LOCATOR_CONTENT_MALFORMED,

    /**
     * An `.m3u` or `.cue` names a file that is not there. Refused before any intent is built;
     * the file's name rides in the failure's `detail` ("Disc 2 is missing: name").
     */
    PLAYLIST_MEMBER_MISSING,

    /**
     * The installed emulator build can only open a game from the folder it scanned itself, and this game is
     * somewhere else (cert 2026-10-08 F8: melonDS 1.10). Refused before any intent is built.
     */
    EMULATOR_NEEDS_OWN_ROM_FOLDER,

    /**
     * RetroArch is installed but a privileged listing of its cores folder proved this game's libretro core file
     * is not in it. Nothing was dispatched: launching would only show RetroArch's black screen. The core's display
     * name rides in [LaunchOutcome.Failed.nativeMetadata] under `coreLabel`.
     */
    RETROARCH_CORE_MISSING,

    /** A generic bridge-side error (fallback for unmapped native codes/bridge failures). */
    BRIDGE_ERROR,
}

/**
 * Whether a game can be launched *right now*, and if not, exactly what is missing. This is the
 * central launch decision `core-launch` computes from (game, installed emulators, BIOS presence,
 * Open-With override).
 *
 * Design note grounded in policy `cert-disclaimer-gamelist-placed`: **certification is a
 * DISCLAIMER, never a launch gate.** So there is deliberately NO `NeedsCertification` state — a
 * placed game with an installed emulator is [Ready] regardless of cert. The disclaimer travels as
 * separate UI metadata, not as a readiness veto. The set is closed (sealed) so `when` blocks are
 * exhaustive and a new state can never be silently unhandled.
 *
 * Policy 2026-08-11 (verbatim: *Play remains dispatchable while stating any unverified launch support*): a confirmed-missing BIOS is correct knowledge, not a
 * reason to trap the player behind Mimir. [NeedsBios] is therefore NON-BLOCKING — [LaunchController]
 * still dispatches to the installed emulator (which can show its own BIOS error, the only place the
 * player can actually import the file) while [NeedsBios] keeps reporting the honest reason. See
 * [NeedsBios.target]. [MissingFile], [NeedsEmulator], and [Unsupported] stay blocking: none of them
 * has an installed emulator target to hand off to, so a launch attempt would be a silent no-op.
 */
sealed interface LaunchReadiness {

    /** Everything is in place: placed file + installed emulator (+ BIOS if required). A must fire the intent. */
    data class Ready(val target: LaunchTarget) : LaunchReadiness

    /**
     * No installed emulator can serve this platform. The only correct route is Settings → Emulators.
     *
     * @property platformId the platform with no installed emulator.
     * @property candidates the (not-installed) candidates the user could install to satisfy this.
     */
    data class NeedsEmulator(
        val platformId: PlatformId,
        val candidates: List<EmulatorProfile> = emptyList(),
    ) : LaunchReadiness

    /**
     * The platform requires a BIOS to run and it is not present. The launch model reports required-BIOS presence
     * and preserves scoped-storage uncertainty through [presenceUnknown].
     *
     * NON-BLOCKING : everything else about the launch still resolved, so
     * [target] carries the same dispatchable [LaunchTarget] a [Ready] readiness would have — the
     * emulator IS installed and reachable, only its BIOS folder came back empty. [LaunchController]
     * dispatches to [target] while this state keeps reporting the honest reason (never both silently
     * become [Ready] — the caller must still see "NeedsBios" to show the right copy).
     *
     * @property platformId the platform needing BIOS.
     * @property missing the BIOS profiles that are required but not confirmed present.
     * @property presenceUnknown true when scoped storage prevents confirming presence (honest "unknown", not "missing").
     * @property target the resolved launch target, when the emulator/file otherwise resolved fully
     *   (null only if some OTHER requirement — e.g. the file locator — also failed to resolve, in
     *   which case that failure's own readiness state is returned instead of this one).
     */
    data class NeedsBios(
        val platformId: PlatformId,
        val missing: List<BiosProfile> = emptyList(),
        val presenceUnknown: Boolean = false,
        val target: LaunchTarget? = null,
    ) : LaunchReadiness

    /**
     * The game's file cannot be reached — not placed, or the storage volume is unavailable. An unavailable placed file routes to detail; the copy is "Place it to play".
     *
     * @property gameId the game.
     * @property availability the soft availability that made the file unreachable (never "deleted").
     */
    data class MissingFile(
        val gameId: GameId,
        val availability: ResidentAvailability = ResidentAvailability.MISSING_PENDING,
    ) : LaunchReadiness

    /**
     * Mimir has no launch pathway for this platform at all (no candidate emulators are even known).
     * Distinct from [NeedsEmulator], which has known candidates the user could install.
     *
     * @property platformId the unsupported platform.
     */
    data class Unsupported(val platformId: PlatformId) : LaunchReadiness

    /** True only for [Ready]; convenience for call sites that just gate the A-press. */
    val isReady: Boolean get() = this is Ready
}
