package com.gruning.mimir.rewrite.feature.settings

/**
 * One row of the Emulators pane: a platform the user has games for, the emulator actually INSTALLED for it
 * (or null when none is), and the resulting ROUTE STATE. Built LIVE by the app from the launch engine's
 * PackageManager detection + the proof-owned certification projection
 * (`mimirLauncherCertificationByPlatform.v1`) — the honest replacement for the sample rows.
 *
 * the row carries [routeState] — the pane's seven
 * honest states — instead of collapsing everything into Ready/Needs-emulator. The state NEVER derives
 * certification from installed/configured facts; `CERTIFIED` can only arrive from the projection.
 */
data class EmulatorAssignment(
    val platform: String,
    /** The resolved emulator's friendly name, or null when no selectable target resolved. */
    val emulator: String?,
    val status: SettingsSample.SampleStatus,
    /** The platform id value — routes the "Change" picker to this system. Null on sample rows (no picker). */
    val platformId: String? = null,
    /** EM01 route state; null on legacy/sample callers (renders via [status] fallback mapping). */
    val routeState: EmulatorRouteState? = null,
    /** Exact coverage reason — non-null ONLY for an explicit unsupported platform (never invented). */
    val unsupportedReason: String? = null,
    /** OR-7: the emulator Mimir names when none is installed (from the seed table), or null. */
    val recommendedEmulator: String? = null,
)

/**
 * EM01 — the Emulators pane's seven route states (LC01 spec table, one condition each). Three separate
 * truths, never collapsed: extension detection ≠ installed package + envelope ≠ visual certification.
 */
enum class EmulatorRouteState {
    /** Exact route has VISUAL_CERTIFIED evidence — read from the projection, never inferred. */
    CERTIFIED,

    /** Installed exact standalone profile; no visual proof yet. */
    DISPATCH_READY,

    /** Installed RetroArch host + named core route — the host alone is not core proof. */
    CORE_PROOF_NEEDED,

    /** Installed locator-free route: Mimir can OPEN the emulator, never send a game. */
    OPEN_ONLY,

    /** Candidates exist for this platform, but none is installed. */
    INSTALL_NEEDED,

    /** A candidate package is installed but has no exact platform/profile route — not selectable. */
    NOT_WIRED,

    /** Explicit coverage reason; no launch action exists or is implied. */
    UNSUPPORTED,
}

/**
 * Pure LC01-spec state derivation from the launch engine's independent capability facts. Order matters:
 * unsupported closes first (no candidates at all), then the no-resolved-target split
 * (installed-but-unwired vs nothing installed), then the resolved tiers — certification wins over the
 * shell/core qualifiers only because a VISUAL_CERTIFIED route has, by definition, already proven them.
 */
fun deriveEmulatorRouteState(
    hasCandidates: Boolean,
    hasResolvedTarget: Boolean,
    anyCandidateInstalled: Boolean,
    certified: Boolean,
    openOnly: Boolean,
    requiresCoreProof: Boolean,
): EmulatorRouteState = when {
    !hasCandidates -> EmulatorRouteState.UNSUPPORTED
    !hasResolvedTarget -> if (anyCandidateInstalled) {
        EmulatorRouteState.NOT_WIRED
    } else {
        EmulatorRouteState.INSTALL_NEEDED
    }
    certified -> EmulatorRouteState.CERTIFIED
    openOnly -> EmulatorRouteState.OPEN_ONLY
    requiresCoreProof -> EmulatorRouteState.CORE_PROOF_NEEDED
    else -> EmulatorRouteState.DISPATCH_READY
}

const val FirstSelectableEmulatorRowId = "emulators/assign/0"

/** The first controller-reachable emulator row; unsupported information rows never become focus targets. */
fun firstSelectableEmulatorAssignment(assignments: List<EmulatorAssignment>): EmulatorAssignment? =
    assignments.firstOrNull { it.routeState != EmulatorRouteState.UNSUPPORTED }

/**
 * Keep one stable entry focus key even though live rows route by stable platform id. This closes Settings
 * entry without replacing the per-platform identity used by every subsequent row.
 */
fun emulatorAssignmentRowId(
    assignment: EmulatorAssignment,
    index: Int,
    firstSelectable: EmulatorAssignment?,
): String = when {
    assignment === firstSelectable -> FirstSelectableEmulatorRowId
    assignment.platformId != null -> "emulators/change/${assignment.platformId}"
    else -> "emulators/assign/$index"
}

/** Plain release copy for the pane’s engineering-backed route state. */
fun EmulatorRouteState.chipLabel(): String = when (this) {
    EmulatorRouteState.CERTIFIED -> "Ready"
    EmulatorRouteState.DISPATCH_READY -> "Set up"
    EmulatorRouteState.CORE_PROOF_NEEDED -> "Needs setup"
    EmulatorRouteState.OPEN_ONLY -> "Open only"
    EmulatorRouteState.INSTALL_NEEDED -> "Needs emulator"
    EmulatorRouteState.NOT_WIRED -> "Not supported yet"
    EmulatorRouteState.UNSUPPORTED -> "Unsupported"
}

/**
 * Plain release explanation per state; UNSUPPORTED preserves the exact product coverage reason. INSTALL_NEEDED names
 * [recommendedEmulator] when there is one (OR-7).
 */
fun EmulatorRouteState.primaryCopy(unsupportedReason: String?, recommendedEmulator: String? = null): String = when (this) {
    EmulatorRouteState.CERTIFIED -> "This route has been tested."
    EmulatorRouteState.DISPATCH_READY -> "Mimir can send games to this emulator."
    EmulatorRouteState.CORE_PROOF_NEEDED ->
        "RetroArch is installed. Choose and test the required core in RetroArch."
    EmulatorRouteState.OPEN_ONLY -> "Mimir can open this emulator, but cannot send a game yet."
    EmulatorRouteState.INSTALL_NEEDED -> if (recommendedEmulator != null) {
        "Install $recommendedEmulator to play these games."
    } else {
        "Install a supported emulator to enable this route."
    }
    EmulatorRouteState.NOT_WIRED ->
        "This installed app can’t open games from Mimir for this platform yet. Choose another emulator."
    EmulatorRouteState.UNSUPPORTED ->
        unsupportedReason ?: "No supported emulator route for this platform yet."
}
