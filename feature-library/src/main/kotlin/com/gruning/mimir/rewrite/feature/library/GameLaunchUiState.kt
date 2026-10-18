package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.launch.LaunchResult
import com.gruning.mimir.rewrite.core.launch.RetroArchCoreCheck
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.LaunchDispatchFailure
import com.gruning.mimir.rewrite.core.ui.MimirCopy
import com.gruning.mimir.rewrite.core.model.LaunchOutcome
import com.gruning.mimir.rewrite.core.model.LaunchReadiness
import com.gruning.mimir.rewrite.core.model.PlatformId

/**
 * The UI-facing launch state for direct gamelist launches. A flat, closed set the Compose surface can render
 * exhaustively — it collapses the launch pipeline's [LaunchResult] (readiness + dispatch outcome) into exactly
 * the honest states the console shows the user.
 *
 * Ground truth (`launch-silent-death-and-playcount`):
 *  - Not-ready states route/explain honestly (no emulator → Settings; no file → "place it"; needs BIOS).
 *  - Ready + IntentSent → "Launching…" (dispatch-only; NO "did it stay open" verdict).
 *  - Ready + Failed → the typed dispatch reason, in plain language.
 */
sealed interface GameLaunchUiState {

    /** No launch attempted yet. */
    data object Idle : GameLaunchUiState

    /** A launch is in flight (readiness being resolved / intent being sent). */
    data object Launching : GameLaunchUiState

    /** The intent was sent to the emulator (native `launchStarted`). "Launching…" — nothing more claimed. */
    data object IntentSent : GameLaunchUiState

    /** No [LaunchController] is wired (Fake/preview path) — an honest stub, never a fake success. */
    data object Unwired : GameLaunchUiState

    /** No installed emulator serves this platform → route to Settings › Emulators. Carries the ID — the
     *  render site resolves the FRIENDLY platform name (copy review P1-4: never show the raw slug). */
    data class NeedsEmulator(val platformId: PlatformId) : GameLaunchUiState

    /** A required BIOS is not present → tell the user which file to place (never a download). */
    data class NeedsBios(val missingFileNames: List<String>, val presenceUnknown: Boolean) : GameLaunchUiState

    /** The game file cannot be reached → restore it or reconnect its Library folder (never claim deletion). */
    data object MissingFile : GameLaunchUiState

    /** Mimir has no launch pathway for this platform at all. Carries the ID (see [NeedsEmulator]). */
    data class Unsupported(val platformId: PlatformId) : GameLaunchUiState

    /**
     * The intent was constructed but dispatch failed synchronously, with a typed reason. For
     * [LaunchDispatchFailure.RETROARCH_CORE_MISSING], [detail] is the missing core's name and [alternative] another core of
     * that platform RetroArch does hold. For every other reason [detail] is the sentence to show when the reason's stock
     * copy is too general (a playlist's missing file is named in it), and [alternative] is null.
     */
    data class DispatchFailed(
        val reason: LaunchDispatchFailure,
        val detail: String? = null,
        val alternative: String? = null,
    ) : GameLaunchUiState

    /**
     * A launch WAS attempted, on this row, and Mimir turned it away because another launch already holds
     * the session mutex (`MimirSessionAuthority.prepareHomeGame` / `preparePrimaryApp`, and the Library
     * launch-admission check in `app/`, all return a refusal for the identical reason: `activeGame ||
     * activePrimaryApp`). [Idle] is not honest here — its own KDoc says "No launch attempted yet", which
     * is the opposite of what happened. Outcome-dropping audit finding O-3 (2026-08-12): several launch
     * sites dropped this exact refusal on the floor, most of them landing on [Idle] or on total silence —
     *
     *
     * [hold] is what held it (O15): a launch still starting, or a game or app already running. The refused site reads
     * it from the session that refused the launch, never on its own.
     */
    data class SessionHeld(val hold: GameLaunchSessionHold) : GameLaunchUiState

    /**
     * The intent was sent and the emulator never took the screen from Mimir inside the dispatch watchdog's grace
     * period (cert 2026-10-08 F1): it crashed or closed before a frame the player could have quit from. The launch is
     * still counted (the intent WAS sent), so this is not a dispatch failure. A player who quits an emulator after
     * seeing it never reaches this state: they yield first, and only a launch still waiting on the window can expire.
     * [emulatorLabel] names the emulator, or is null when the launch never learned it.
     */
    data class ClosedUnexpectedly(val emulatorLabel: String?) : GameLaunchUiState

    companion object {
        /** Collapse a [LaunchResult] into the UI state to show. */
        fun from(result: LaunchResult): GameLaunchUiState = when (val readiness = result.readiness) {
            is LaunchReadiness.Ready -> when (val outcome = result.outcome) {
                is LaunchOutcome.IntentSent -> IntentSent
                is LaunchOutcome.Failed -> if (outcome.reason == LaunchDispatchFailure.RETROARCH_CORE_MISSING) {
                    DispatchFailed(
                        outcome.reason,
                        outcome.nativeMetadata[RetroArchCoreCheck.CORE_LABEL_KEY],
                        outcome.nativeMetadata[RetroArchCoreCheck.CORE_INSTALLED_LABEL_KEY],
                    )
                } else {
                    DispatchFailed(outcome.reason, outcome.detail)
                }
                null -> Launching // Ready but no outcome yet (defensive; controller always dispatches).
            }
            is LaunchReadiness.NeedsEmulator ->
                NeedsEmulator(platformId = readiness.platformId)
            is LaunchReadiness.NeedsBios ->
                NeedsBios(
                    missingFileNames = readiness.missing.map { it.fileName },
                    presenceUnknown = readiness.presenceUnknown,
                )
            is LaunchReadiness.MissingFile -> MissingFile
            is LaunchReadiness.Unsupported ->
                Unsupported(platformId = readiness.platformId)
        }
    }
}

/**
 * O15,
 *
 * What held a launch the session mutex refused. `app/`'s session ledger decides it from the session that refused the
 * launch (`launchHoldFor`), and every surface turns it into words through [gameLaunchSessionHeldMessage] or
 * [gameLaunchSessionHeldRailLabel]. The words themselves are internal to this module, so no call site elsewhere can
 * pick them on its own — the drift between two sites describing one refusal that O-3 set out to end.
 */
enum class GameLaunchSessionHold {
    /** A launch is still starting: Mimir's own launch or hand-off has not settled yet, and waiting clears it. */
    STARTING,

    /** A game or app is already running, and only closing it frees the launch. */
    RUNNING,
}

/**
 * A launch refused while another is still starting, on every surface able to speak a full sentence: the Library
 * detail card, the Home launch card, and the toasts of the launch sites with no card. O15: "today's sentence stays".
 */
internal const val GameLaunchSessionStartingMessage: String =
    "Mimir is already launching something else. Wait for it to finish, then try again."

/** A launch refused while a game or app is already running. Shared refusal wording. */
internal const val GameLaunchSessionRunningMessage: String =
    "A game or app is still open. Close it first, then try again."

/** The short status-label twin of [GameLaunchSessionStartingMessage]; O15 keeps it for starting. Not painted today. */
internal const val GameLaunchSessionStartingRailLabel: String = "Launch busy"

/**
 * The short status-label twin of [GameLaunchSessionRunningMessage]. Not painted today. PLAYER-FACING COPY if a surface
 * ever paints it — proposed for O15, exact wording remains unreviewed; the status label is separate from the running message.
 */
internal const val GameLaunchSessionRunningRailLabel: String = "Still open"

/** The one sentence a launch refused with [hold] shows. */
fun gameLaunchSessionHeldMessage(hold: GameLaunchSessionHold): String = when (hold) {
    GameLaunchSessionHold.STARTING -> GameLaunchSessionStartingMessage
    GameLaunchSessionHold.RUNNING -> GameLaunchSessionRunningMessage
}

/**
 * The short status label for a launch refused with [hold]. It is not painted today: `app/` writes it into the companion
 * payload's status label (`companionLaunchStatusLabel`), and no production code reads that field, so neither word reaches a
 * screen. It keeps O15's split, so a surface that starts painting it starts from the split rather than one word for both.
 */
fun gameLaunchSessionHeldRailLabel(hold: GameLaunchSessionHold): String = when (hold) {
    GameLaunchSessionHold.STARTING -> GameLaunchSessionStartingRailLabel
    GameLaunchSessionHold.RUNNING -> GameLaunchSessionRunningRailLabel
}

/**
 * The emulator's name as a sentence wants it: RetroArch's `RetroArch 64 / mGBA` is the app, not the core, to a player
 * who is told it closed. Null for a blank name.
 */
fun emulatorSentenceLabel(displayName: String?): String? =
    displayName?.substringBefore(" / ")?.trim()?.takeIf(String::isNotEmpty)

/** The emulator a successful dispatch went to, in [emulatorSentenceLabel] words; null when nothing was dispatched. */
fun LaunchResult.dispatchedEmulatorLabel(): String? {
    val target = when (val r = readiness) {
        is LaunchReadiness.Ready -> r.target
        is LaunchReadiness.NeedsBios -> r.target
        else -> null
    }
    return emulatorSentenceLabel(target?.emulator?.displayName)
}

/** F2: what the card says between the intent leaving and the emulator taking the screen. */
fun gameLaunchStartingMessage(emulatorLabel: String): String = "Starting $emulatorLabel…"

/** F1: the emulator took the launch and closed without ever reaching the screen. Calm, and says what to check. */
fun emulatorClosedUnexpectedlyMessage(emulatorLabel: String?): String =
    "${emulatorLabel ?: "The emulator"} closed unexpectedly. " +
        "The game may not be supported, or the emulator needs setup."

/** Plain-language status copy for [GameLaunchUiState], or null when the card should stay quiet ([Idle]). */
fun gameLaunchStatusMessage(
    state: GameLaunchUiState,
    platformLabelFor: (PlatformId) -> String,
): String? = gameLaunchStatusMessage(state, emulatorLabel = null, platformLabelFor = platformLabelFor)

/**
 * [gameLaunchStatusMessage] with the emulator the intent went to: [emulatorLabel], when given, turns the sent-but-not-yet-yielded
 * state into "Starting <Emulator>…" (F2). A separate overload so every existing trailing-lambda caller keeps its shape.
 */
fun gameLaunchStatusMessage(
    state: GameLaunchUiState,
    emulatorLabel: String?,
    platformLabelFor: (PlatformId) -> String,
): String? = when (state) {
    GameLaunchUiState.Idle -> null
    GameLaunchUiState.Launching -> MimirCopy.StatusLaunching
    GameLaunchUiState.IntentSent ->
        emulatorLabel?.let(::gameLaunchStartingMessage) ?: MimirCopy.StatusLaunching
    is GameLaunchUiState.ClosedUnexpectedly -> emulatorClosedUnexpectedlyMessage(state.emulatorLabel)
    GameLaunchUiState.Unwired -> "Play isn’t available for this game yet."
    is GameLaunchUiState.SessionHeld -> gameLaunchSessionHeldMessage(state.hold)
    is GameLaunchUiState.NeedsEmulator ->
        "No installed emulator for ${platformLabelFor(state.platformId)}. " +
            "Open Settings › Emulators to install one."
    is GameLaunchUiState.NeedsBios -> {
        val files = state.missingFileNames.joinToString(", ").ifBlank { "the required BIOS" }
        if (state.presenceUnknown) {
            "Mimir can’t confirm $files. Check the emulator’s BIOS setup, then try again."
        } else {
            "This system needs $files. Complete the emulator’s BIOS setup, then try again."
        }
    }
    GameLaunchUiState.MissingFile ->
        "Mimir can’t reach this game file. Restore it or reconnect its library folder, then try again."
    is GameLaunchUiState.Unsupported ->
        "Mimir can’t launch ${platformLabelFor(state.platformId)} yet."
    is GameLaunchUiState.DispatchFailed ->
        if (state.reason == LaunchDispatchFailure.RETROARCH_CORE_MISSING) {
            launchDispatchFailureMessage(state.reason, state.detail, state.alternative)
        } else {
            state.detail ?: launchDispatchFailureMessage(state.reason)
        }
}

/** Launch card status: runtime launch feedback for the open game, else the static platform line. */
internal fun resolveLibraryDetailLaunchStatusLine(
    platformStatusLine: String?,
    launchState: GameLaunchUiState,
    detailGameId: GameId?,
    targetGameId: GameId,
    platformLabelFor: (PlatformId) -> String,
    emulatorLabel: String? = null,
): String? = resolveLaunchCardStatusLine(
    platformStatusLine = platformStatusLine,
    launchState = launchState,
    ownerGameId = detailGameId,
    targetGameId = targetGameId,
    platformLabelFor = platformLabelFor,
    emulatorLabel = emulatorLabel,
)

/** Runtime-only launch feedback for the Library detail card, with no static platform fallback. */
internal fun resolveLibraryDetailLaunchRuntimeStatusLine(
    launchState: GameLaunchUiState,
    launchOwnerGameId: GameId?,
    targetGameId: GameId,
    platformLabelFor: (PlatformId) -> String,
    emulatorLabel: String? = null,
): String? = resolveLaunchCardRuntimeStatusLine(
    launchState = launchState,
    ownerGameId = launchOwnerGameId,
    targetGameId = targetGameId,
    platformLabelFor = platformLabelFor,
    emulatorLabel = emulatorLabel,
)

/** Home launch card status — same ownership rule as the library detail card. */
fun resolveHomeLaunchCardStatusLine(
    platformStatusLine: String?,
    launchState: GameLaunchUiState,
    sessionGameId: GameId?,
    targetGameId: GameId,
    platformLabelFor: (PlatformId) -> String,
): String? = resolveLaunchCardStatusLine(
    platformStatusLine = platformStatusLine,
    launchState = launchState,
    ownerGameId = sessionGameId,
    targetGameId = targetGameId,
    platformLabelFor = platformLabelFor,
)

/** Runtime-only launch feedback for the Home launch card, with no static platform fallback. */
fun resolveHomeLaunchCardRuntimeStatusLine(
    launchState: GameLaunchUiState,
    sessionGameId: GameId?,
    targetGameId: GameId,
    platformLabelFor: (PlatformId) -> String,
): String? = resolveLaunchCardRuntimeStatusLine(
    launchState = launchState,
    ownerGameId = sessionGameId,
    targetGameId = targetGameId,
    platformLabelFor = platformLabelFor,
)

/** Runtime launch feedback only when the launch owner is this card's game. */
internal fun resolveLaunchCardRuntimeStatusLine(
    launchState: GameLaunchUiState,
    ownerGameId: GameId?,
    targetGameId: GameId,
    platformLabelFor: (PlatformId) -> String,
    emulatorLabel: String? = null,
): String? = ownerGameId
    ?.takeIf { it == targetGameId }
    ?.let { gameLaunchStatusMessage(launchState, emulatorLabel, platformLabelFor) }

internal fun resolveLaunchCardStatusLine(
    platformStatusLine: String?,
    launchState: GameLaunchUiState,
    ownerGameId: GameId?,
    targetGameId: GameId,
    platformLabelFor: (PlatformId) -> String,
    emulatorLabel: String? = null,
): String? = resolveLaunchCardRuntimeStatusLine(
    launchState = launchState,
    ownerGameId = ownerGameId,
    targetGameId = targetGameId,
    platformLabelFor = platformLabelFor,
    emulatorLabel = emulatorLabel,
) ?: platformStatusLine

private fun launchDispatchFailureMessage(
    reason: LaunchDispatchFailure,
    detail: String? = null,
    alternative: String? = null,
): String = when (reason) {
    LaunchDispatchFailure.TARGET_MISSING -> "The chosen emulator couldn’t be found."
    LaunchDispatchFailure.TARGET_NOT_INSTALLED ->
        "That emulator isn’t installed. Install it or pick another in Open with."
    LaunchDispatchFailure.ROM_PATH_MISSING -> "The game file couldn’t be found."
    LaunchDispatchFailure.LOCATOR_FORM_REJECTED ->
        "The emulator wouldn’t open this file. Try a different emulator in Open with."
    LaunchDispatchFailure.CONTENT_URI_UNAVAILABLE ->
        "Mimir lost access to this file. Re-grant the folder in Settings › Library."
    LaunchDispatchFailure.LAUNCH_PROFILE_UNPROVEN ->
        "Mimir couldn’t start this game with that emulator. Try another in Open with."
    LaunchDispatchFailure.ACTIVITY_NOT_STARTED -> "Android wouldn’t start the emulator."
    // PLAYER-FACING COPY — proposed; copy review remains pending.
    LaunchDispatchFailure.LOCATOR_CONTENT_MALFORMED ->
        "This game’s shortcut looks damaged. Re-add it from GameNative, then try again."
    // Stock copy for a refusal that normally arrives with its own sentence (see DispatchFailed.detail).
    LaunchDispatchFailure.PLAYLIST_MEMBER_MISSING -> "A file this game’s playlist needs is missing."
    LaunchDispatchFailure.EMULATOR_NEEDS_OWN_ROM_FOLDER ->
        "This emulator can only open games from its own ROM folder. Update it, or add this folder in the emulator."
    // PLAYER-FACING COPY -- exact wording remains unreviewed. The action comes first and the
    // sentences are short because the Home launch card's status band is two lines and ends in an ellipsis; the longest
    // catalog labels are measured against it by HomeLaunchCardCoreMissingStatusFitTest. Pinned by RetroArchCoreMissingMessageTest.
    LaunchDispatchFailure.RETROARCH_CORE_MISSING -> when {
        detail != null && alternative != null -> "RetroArch has $alternative but not $detail. Pick it with ${MimirCopy.OpenWith}."
        detail != null -> "RetroArch needs the $detail core. Get it in RetroArch: Online Updater › Core Downloader."
        else -> "RetroArch needs another core. Get it in RetroArch: Online Updater › Core Downloader."
    }
    LaunchDispatchFailure.BRIDGE_ERROR -> "The launch failed for an unexpected reason."
}
