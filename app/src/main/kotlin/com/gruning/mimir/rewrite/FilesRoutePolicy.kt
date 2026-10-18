package com.gruning.mimir.rewrite

/**
 * One route from Utility into Mimir's own file manager, and one permission guarding it.
 *
 * Capability splits by MODE, not by screen. `FileBrowserMode.PICK` is the add-games picker — browse,
 * extract, commit a folder, nothing destructive ever. `FileBrowserMode.MANAGE` is the file manager
 * proper: move, delete, the queue, Search/Storage/Permissions, retro identity. **Utility is the only
 * door to MANAGE**, with all destructive file-manager operations behind it.
 *
 * Modelled on [tweakerRouteDecision] deliberately, including the reason that one is a policy and not
 * an `if` at a call site: the row's rendered value label, the row's presence in the focus order, and
 * what CONFIRM actually does must all reach the same conclusion, or the rail grows a control that says
 * one thing and does another. That has shipped on this rail twice.
 */
internal enum class FilesRouteDecision {
    /** All-files access is held. Open the manager in [com.gruning.mimir.rewrite.feature.files.FileBrowserMode.MANAGE]. */
    OPEN,

    /**
     * The manager exists and is wanted, but Mimir cannot enumerate storage yet. Raise the system
     * "All files access" screen; never open a browser that would list nothing and read as broken.
     */
    REQUEST_ALL_FILES_ACCESS,

    /**
     * There is no browser surface this host can reach. See [filesRouteDecision]'s `hostsInAppSurfaces`.
     * The row is not offered at all in this state — this arm exists so a press that somehow arrives
     * anyway refuses in one auditable place instead of arming the defect described there.
     */
    UNAVAILABLE,

    /**
     * A game is running ( [filesRouteGameRunning]). The row and the dock's seat are withdrawn in this state
     * ([utilityFilesRowOffered]); this arm is for the press that arrives anyway (a stale frame, the compact menu) and it refuses
     * quietly. It is its own arm, and not [UNAVAILABLE], because that arm's copy, "open Mimir to browse files", is the native
     * overlay's: inside MainActivity it is false (Mimir IS open), and the combined43 sweep (F1, 2026-10-15) showed it there, on
     * the dock, after every game.
     */
    GAME_RUNNING,
}

/**
 * Whether Utility's Files row is offered at all : the host hosts in-app surfaces ([filesRouteDecision]'s gate)
 * and no game is running. While a game runs the row is withdrawn, from the rail, the focus order and the press alike, and it comes
 * back when the game ends.
 */
internal fun utilityFilesRowOffered(hostsInAppSurfaces: Boolean, gameRunning: Boolean): Boolean =
    hostsInAppSurfaces && !gameRunning

/**
 * Whether the session ledger holds a game that still has the screen: the one answer behind [utilityFilesRowOffered]'s
 * `gameRunning`, for the Utility row, the dock's Files seat and the host that keeps the row's mirror fresh.
 *
 * RC-06 (Thor and Odin, 2026-10-14): the ledger NEVER clears a game session. `completeGameReturn` leaves it in ENDED (or
 * FAILED, STALE) until the next launch replaces it, so asking whether a session EXISTS answers "a game is running" for the
 * rest of the process after the first game, and the dock's Files seat toasted "OPEN MIMIR TO BROWSE FILES" until the
 * process was recreated. The phases that hold the screen are the ledger's own [ActivePresentationPhases] (the set that
 * refuses a second launch), so the row is withdrawn for exactly as long as a new launch would be.
 */
internal fun filesRouteGameRunning(sessions: MimirSessionSnapshot): Boolean =
    sessions.gameSession?.phase?.let { it in ActivePresentationPhases } == true

/**
 * Where an activation should land.
 *
 * [hostsInAppSurfaces] is whether this Utility panel is hosted by the Activity that also composes
 * `FileBrowserSurface`. **It is false for `PrimaryMButtonPanelHost`**, which draws the very same panel
 * into a native overlay window over whatever app is in the foreground, with its own isolated
 * `DefaultFocusAuthority` (see that class's own header: "deliberately does not borrow MainActivity's
 * lifecycle, window, or FocusAuthority").
 *
 * That is not a cosmetic difference, and it is why this is a hard refusal rather than a disabled row:
 * `FileBrowserSurface` is composed only in `MimirAppHost`, inside MainActivity. Opening the store from
 * the native panel would publish a `FileBrowserController` bound to the *panel's* dead authority, and
 * MainActivity would later mount a browser whose focus scope lives on an authority nothing dispatches
 * to — while `resolveMimirOverlayStack` ranks `FOLDER_BROWSER` above everything the player could use to
 * escape it. That is the divorced-browser input lockup, exactly the family `FileBrowserOverlayAbsorbTest`
 * exists to kill. The row is therefore withheld from that host, on the same "no dead row" principle
 * [tweakerRoutePaired] already applies to a device with no Tweaker to route to.
 *
 * [allFilesAccessGranted] is `Environment.isExternalStorageManager()`, read as a snapshot by the
 * coordinator rather than live in composition. Mimir's own browser genuinely requires it: [AddGamesRoute]
 * already rules that the in-app browser is reachable only with all-files access
 * (`addGamesRoute`: `allFilesAccessGranted -> IN_APP_BROWSER`), because without it `LocalFileRepository`'s
 * `java.io.File` enumeration cannot see shared storage.
 */
internal fun filesRouteDecision(
    hostsInAppSurfaces: Boolean,
    allFilesAccessGranted: Boolean,
    /** [filesRouteGameRunning], read from the ledger at the press (RC-06). The host gate outranks it: a window with no browser has nothing to withdraw. */
    gameRunning: Boolean = false,
): FilesRouteDecision = when {
    !hostsInAppSurfaces -> FilesRouteDecision.UNAVAILABLE
    gameRunning -> FilesRouteDecision.GAME_RUNNING
    allFilesAccessGranted -> FilesRouteDecision.OPEN
    else -> FilesRouteDecision.REQUEST_ALL_FILES_ACCESS
}

/**
 * Row value, so the row states its outcome before it is pressed.
 *
 * NEEDS ACCESS rather than a disabled row: the press does something real and useful in that state —
 * it raises the grant screen — so disabling it would remove the only regrant path a player has. The
 * design's own Permissions screen carries "lost-permission regrant", and that screen sits *inside* the
 * manager, which is unreachable without the permission; this row is what breaks that circle.
 */
internal fun filesRouteValueLabel(decision: FilesRouteDecision): String = when (decision) {
    FilesRouteDecision.OPEN -> "OPEN"
    FilesRouteDecision.REQUEST_ALL_FILES_ACCESS -> "NEEDS ACCESS"
    FilesRouteDecision.UNAVAILABLE -> "UNAVAILABLE"
    FilesRouteDecision.GAME_RUNNING -> "GAME RUNNING"
}

/**
 * Supporting copy.
 *
 * Utility also holds the Tweaker and diagnostics, so the Files
 * entry must read as a player tool without diagnostic chrome. This copy therefore names
 * what a player does with it — drives, folders, archives — and never mentions posture, telemetry,
 * privilege, or a subsystem. The access line says plainly that pressing asks for the permission, on the
 * same "awareness before the prompt" rule [tweakerRouteSupportingText] follows.
 *
 * It also does not offer the system file picker as an alternative. Switching into it was reported to spike the fan, so it is not offered as a shortcut.
 */
internal fun filesRouteSupportingText(decision: FilesRouteDecision): String = when (decision) {
    FilesRouteDecision.OPEN -> "Browse your drives, sort your games, and unpack archives."
    FilesRouteDecision.REQUEST_ALL_FILES_ACCESS ->
        "Asks Android for file access first, so Mimir can see your drives."
    FilesRouteDecision.UNAVAILABLE -> "Open Mimir to browse your files."
    FilesRouteDecision.GAME_RUNNING -> "Comes back when the game ends."
}
