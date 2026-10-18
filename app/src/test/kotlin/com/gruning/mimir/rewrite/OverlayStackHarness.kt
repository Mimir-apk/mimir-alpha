package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.files.FileItem
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.feature.files.FileBrowserController
import com.gruning.mimir.rewrite.feature.files.FileBrowserMode

/** One overlay kind's flag raised in a snapshot; the test-side inverse of what `resolveMimirOverlayStack` reads. */
internal fun MimirOverlaySnapshot.withOverlay(kind: MimirOverlayKind, on: Boolean = true): MimirOverlaySnapshot = when (kind) {
    MimirOverlayKind.RETURN_COVER -> copy(returnCover = on)
    MimirOverlayKind.FOLDER_BROWSER -> copy(folderBrowser = on)
    MimirOverlayKind.COMPANION_PERMISSION -> copy(companionPermission = on)
    MimirOverlayKind.MIGRATION -> copy(migration = on)
    MimirOverlayKind.ARTWORK_GET -> copy(artworkGet = on)
    MimirOverlayKind.ARTWORK_FETCH -> copy(artworkFetch = on)
    MimirOverlayKind.ARTWORK_CONNECT -> copy(artworkConnect = on)
    MimirOverlayKind.PROFILE_NAME -> copy(profileName = on)
    MimirOverlayKind.STEAMGRIDDB_KEY -> copy(steamGridDbKey = on)
    MimirOverlayKind.RETROACHIEVEMENTS_KEY -> copy(retroAchievementsKey = on)
    MimirOverlayKind.GAME_TITLE_EDIT -> copy(gameTitleEdit = on)
    MimirOverlayKind.CONTROLLER_MAPPING -> copy(controllerMapping = on)
    MimirOverlayKind.CONTROLLER_SHORTCUT_CAPTURE -> copy(controllerShortcutCapture = on)
    MimirOverlayKind.HOME_QUICK_LAUNCH_PICKER -> copy(homeQuickLaunchPicker = on)
    MimirOverlayKind.HOME_QUICK_LAUNCH -> copy(homeQuickLaunch = on)
    MimirOverlayKind.HOME_TILE_MENU -> copy(homeTileMenu = on)
    MimirOverlayKind.HOME_TOME_LIBRARY -> copy(homeTomeLibrary = on)
    MimirOverlayKind.LIBRARY_PLATFORM_TILE_MENU -> copy(platformTileMenu = on)
    MimirOverlayKind.GAMELIST_ACHIEVEMENTS -> copy(gamelistAchievements = on)
    MimirOverlayKind.HOME_OPEN_WITH -> copy(homeOpenWith = on)
    MimirOverlayKind.HOME_LAUNCH_CHOICE -> copy(homeLaunchChoice = on)
    MimirOverlayKind.HOME_LAUNCH_CARD -> copy(homeLaunchCard = on)
    MimirOverlayKind.PLATFORM_PICKER -> copy(platformPicker = on)
    MimirOverlayKind.BACKDROP_PICKER -> copy(backdropPicker = on)
    MimirOverlayKind.THEME_PICKER -> copy(themePicker = on)
    MimirOverlayKind.BACKGROUND_PICKER -> copy(backgroundPicker = on)
    MimirOverlayKind.MBUTTON_PICKER -> copy(mimirButtonPicker = on)
    MimirOverlayKind.ACCENT_PICKER -> copy(accentPicker = on)
    MimirOverlayKind.FOCUS_RING_DIAL -> copy(focusRingDial = on)
    MimirOverlayKind.MUSIC_SOURCE_PICKER -> copy(musicSourcePicker = on)
    MimirOverlayKind.MUSIC_VOLUME_DIAL -> copy(musicVolumeDial = on)
    MimirOverlayKind.EQUALIZER_EDITOR -> copy(equalizerEditor = on)
    MimirOverlayKind.BRIGHTNESS -> copy(brightness = on)
}

internal fun snapshotOf(vararg kinds: MimirOverlayKind): MimirOverlaySnapshot =
    kinds.fold(MimirOverlaySnapshot()) { snapshot, kind -> snapshot.withOverlay(kind) }

/** A two-level tree: `/root` holds `Games`, which holds two files. */
internal val overlayHarnessTree: Map<String, List<FileItem>> = mapOf(
    "/root" to listOf(FileItem(name = "Games", path = "/root/Games", isDirectory = true)),
    "/root/Games" to listOf(
        FileItem(name = "cover.png", path = "/root/Games/cover.png", extension = "png"),
        FileItem(name = "game.gba", path = "/root/Games/game.gba", extension = "gba"),
    ),
)

/** A real [FileBrowserController] on a real authority, opened and one level deep (`/root/Games`). */
internal fun browserAtDepth(authority: DefaultFocusAuthority, onDismiss: () -> Unit = {}): FileBrowserController {
    val browser = FileBrowserController(
        authority = authority,
        rootPath = "/root",
        mode = FileBrowserMode.MANAGE,
        listDirectory = { path -> overlayHarnessTree[path].orEmpty() },
        onChoose = {},
        onDismiss = onDismiss,
    )
    browser.open()
    browser.dispatch(ControllerAction.CONFIRM) // "Games" is focused at the root; this enters it
    check(browser.currentPath == "/root/Games" && !browser.atRoot) { "the browser must be one level deep" }
    return browser
}

/**
 * The real [MimirOverlayCoordinator] sweep and stack over a mutable snapshot, wired the way the app coordinator wires
 * the M-button commands. Only what an overlay does when sent a press is stood in for, and a test can swap the folder
 * browser's stand-in for a real controller ([browser]).
 *
 * The `press*` functions are the call sites' sequences in `MimirAppCoordinator`'s M-command effect, line for line;
 * the contract tests pin their strings, so a call site that drifts from them fails there.
 */
internal class OverlayStackHarness(
    vararg open: MimirOverlayKind,
    /** Kinds whose own Back path closes them when sent CANCEL; any other kind declines it, like a validating modal. */
    private val yielding: Set<MimirOverlayKind> = emptySet(),
    /** The real folder browser's dispatch, when a test wants one instead of the recording stand-in. */
    private val browser: ((ControllerAction) -> Boolean)? = null,
    /**
     * Kinds the coordinator closes ENTIRELY in one step instead of sending CANCEL (production: the folder browser, via
     * `FileBrowserStore.close`). Closing clears the kind's flag, as the store clearing its controller does.
     */
    private val closedEntirely: Set<MimirOverlayKind> = emptySet(),
    private val onClosedEntirely: (MimirOverlayKind) -> Unit = {},
    /**
     * The production decision itself, for a kind whose full close depends on live state (the Mimir-button picker while adjusting): when given it
     * replaces [closedEntirely], and a true answer clears the kind's flag and is recorded in [fullCloses], as the set's kinds are.
     */
    private val fullClose: ((MimirOverlayKind) -> Boolean)? = null,
) {
    var snapshot: MimirOverlaySnapshot = snapshotOf(*open)
    val cancels = mutableListOf<MimirOverlayKind>()
    val received = mutableListOf<Pair<MimirOverlayKind, ControllerAction>>()
    val settled = mutableListOf<String>()
    val fullCloses = mutableListOf<MimirOverlayKind>()

    val coordinator = MimirOverlayCoordinator(
        snapshot = { snapshot },
        dispatchTop = { kind, action ->
            received += kind to action
            if (action == ControllerAction.CANCEL) {
                cancels += kind
                if (kind in yielding) snapshot = snapshot.withOverlay(kind, on = false)
            }
            if (kind == MimirOverlayKind.FOLDER_BROWSER && browser != null) browser.invoke(action) else true
        },
        closeEntirely = { kind ->
            if (fullClose != null) {
                val closed = fullClose.invoke(kind)
                if (closed) {
                    fullCloses += kind
                    snapshot = snapshot.withOverlay(kind, on = false)
                }
                closed
            } else if (kind in closedEntirely) {
                fullCloses += kind
                onClosedEntirely(kind)
                snapshot = snapshot.withOverlay(kind, on = false)
                true
            } else {
                false
            }
        },
    )

    val top: MimirOverlayKind? get() = coordinator.top
    val entries: List<MimirOverlayKind> get() = coordinator.stack.entries

    /** The app coordinator's QUICK_LAUNCH wiring; `MButtonQuickLaunchInPlaceContractTest` pins its strings. */
    fun press(panelOpen: Boolean = false): QuickLaunchInPlaceOutcome = executeQuickLaunchInPlace(
        panelOpen = panelOpen,
        startupOriginApplied = true,
        closePicker = { settled += "closePicker" },
        sweepConflictingOverlays = {
            coordinator.dismissConflictingModals(
                stopAt = MimirOverlayKind.HOME_QUICK_LAUNCH,
                keep = ::mimirOverlayStaysUnderQuickLaunch,
            )
        },
        remainingObstruction = { coordinator.top?.takeIf(::mimirOverlayOutranksQuickLaunch) },
        keptSurfaceLive = { coordinator.stack.entries.any(::mimirOverlayStaysUnderQuickLaunch) },
        hardCloseTransientMenus = {
            settled += "hardCloseMenus"
            snapshot = snapshot.copy(homeTileMenu = false, homeTomeLibrary = false, platformTileMenu = false)
        },
        cancelTomeReturn = { settled += "cancelTomeReturn" },
        disarmLaunchReturns = { settled += "disarmLaunchReturns" },
        closeUtilityPanel = {
            settled += "closeUtility"
            snapshot = snapshot.copy(brightness = false)
        },
        openPanel = {
            settled += "openPanel"
            snapshot = snapshot.copy(homeQuickLaunch = true)
        },
    )

    /** `navigation.closeHomeQuickLaunch()`: the panel and its picker go, nothing else does. */
    fun closePanel() {
        snapshot = snapshot.copy(homeQuickLaunch = false, homeQuickLaunchPicker = false)
    }

    /**
     * M-button HOME, or SETTINGS when [settings] (the two share a branch): the sweep with no keep set, then Utility
     * closed, then the navigation. Recorded in [settled] in the order the call site runs it.
     */
    fun pressMNavigation(settings: Boolean = false) {
        coordinator.dismissConflictingModals()
        settled += "sweepDone"
        snapshot = snapshot.withOverlay(MimirOverlayKind.BRIGHTNESS, on = false) // core.brightness.close()
        settled += "closeUtility"
        settled += if (settings) "openSettings" else "applyMButtonHomeNavigation"
    }

    /** M-button UTILITY or TWEAKER: the sweep with no keep set, then Utility opened. */
    fun pressMUtility() {
        coordinator.dismissConflictingModals()
        settled += "sweepDone"
        snapshot = snapshot.withOverlay(MimirOverlayKind.BRIGHTNESS) // core.brightness.open(...)
        settled += "openUtility"
    }
}
