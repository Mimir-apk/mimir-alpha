package com.gruning.mimir.rewrite

import android.content.Intent
import android.hardware.display.DisplayManager
import android.net.Uri
import android.util.Log
import android.os.Environment
import android.widget.Toast
import com.gruning.mimir.rewrite.core.ui.MimirMessageClass
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import com.gruning.mimir.rewrite.core.audio.AudioPreferences
import com.gruning.mimir.rewrite.core.audio.MimirMusicControl
import com.gruning.mimir.rewrite.core.audio.MimirMusicLibrary
import com.gruning.mimir.rewrite.core.files.StorageVolumeListing
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.model.EmulatorId
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.LibrarySourceRole
import com.gruning.mimir.rewrite.core.model.library.SourceRow
import com.gruning.mimir.rewrite.core.ui.MimirAccentStop
import com.gruning.mimir.rewrite.core.ui.MimirAccentTemp
import com.gruning.mimir.rewrite.core.ui.MimirAppearanceOverrides
import com.gruning.mimir.rewrite.core.ui.MimirRingStop
import com.gruning.mimir.rewrite.core.ui.MimirSceneGroundBlur
import com.gruning.mimir.rewrite.core.ui.MimirSceneGroundDim
import com.gruning.mimir.rewrite.core.ui.MimirSceneGroundImages
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import com.gruning.mimir.rewrite.core.ui.MimirLanguageSettings
import com.gruning.mimir.rewrite.core.ui.MimirViewportMode
import com.gruning.mimir.rewrite.core.ui.MimirViewportSettings
import com.gruning.mimir.rewrite.feature.files.FileBrowserMode
import com.gruning.mimir.rewrite.feature.library.GamelistBackdropMode
import com.gruning.mimir.rewrite.feature.settings.AboutLegalSourceRowId
import com.gruning.mimir.rewrite.feature.settings.AccentPickerScope
import com.gruning.mimir.rewrite.feature.settings.MimirKeyboardRow
import com.gruning.mimir.rewrite.feature.settings.MimirSourceUrl
import com.gruning.mimir.rewrite.feature.settings.TrackpadAccessRowId
import com.gruning.mimir.rewrite.feature.settings.TrackpadPointerColorRowId
import com.gruning.mimir.rewrite.feature.settings.TrackpadPointerShapeRowId
import com.gruning.mimir.rewrite.feature.settings.TrackpadPointerSizeRowId
import com.gruning.mimir.rewrite.feature.settings.TrackpadScrollRowId
import com.gruning.mimir.rewrite.feature.settings.TrackpadSpeedRowId
import com.gruning.mimir.rewrite.feature.settings.mimirKeyboardRow
import com.gruning.mimir.rewrite.feature.settings.DetailScope
import com.gruning.mimir.rewrite.feature.settings.MusicBehaviourRowIds
import com.gruning.mimir.rewrite.feature.settings.MusicBehaviourScope
import com.gruning.mimir.rewrite.feature.settings.MusicFolderForgetCancelRowId
import com.gruning.mimir.rewrite.feature.settings.MusicFolderForgetScope
import com.gruning.mimir.rewrite.feature.settings.MusicHoursValue
import com.gruning.mimir.rewrite.feature.settings.MusicLaunchSoundValue
import com.gruning.mimir.rewrite.feature.settings.MusicOrderValue
import com.gruning.mimir.rewrite.feature.settings.MusicPickerMenu
import com.gruning.mimir.rewrite.feature.settings.MusicReadModel
import com.gruning.mimir.rewrite.feature.settings.MusicSourcePickerScope
import com.gruning.mimir.rewrite.feature.settings.MusicSourceValue
import com.gruning.mimir.rewrite.feature.settings.MusicVolumeDialScope
import com.gruning.mimir.rewrite.feature.settings.BackdropPickerScope
import com.gruning.mimir.rewrite.feature.settings.BackgroundAtmosphereKey
import com.gruning.mimir.rewrite.feature.settings.BackgroundPickerCommand
import com.gruning.mimir.rewrite.feature.settings.BackgroundPickerImage
import com.gruning.mimir.rewrite.feature.settings.BackgroundPickerScope
import com.gruning.mimir.rewrite.feature.settings.BackgroundPickerState
import com.gruning.mimir.rewrite.feature.settings.BackgroundPickerTransition
import com.gruning.mimir.rewrite.feature.settings.MimirButtonPickerCommand
import com.gruning.mimir.rewrite.feature.settings.MimirButtonPickerMark
import com.gruning.mimir.rewrite.feature.settings.MimirButtonPickerScope
import com.gruning.mimir.rewrite.feature.settings.MimirButtonPickerState
import com.gruning.mimir.rewrite.feature.settings.MimirButtonPickerTransition
import com.gruning.mimir.rewrite.feature.settings.MimirButtonRuneKey
import com.gruning.mimir.rewrite.feature.settings.withAdjustSaveFailed
import com.gruning.mimir.rewrite.feature.settings.backgroundImageLabel
import com.gruning.mimir.rewrite.feature.settings.backgroundPickerInitialState
import com.gruning.mimir.rewrite.feature.settings.backgroundPickerOnFocus
import com.gruning.mimir.rewrite.feature.settings.backgroundTileKey
import com.gruning.mimir.rewrite.feature.settings.landPickerFocus
import com.gruning.mimir.rewrite.feature.settings.mimirButtonMarkLabel
import com.gruning.mimir.rewrite.feature.settings.mimirButtonPickerInitialState
import com.gruning.mimir.rewrite.feature.settings.markPlacement
import com.gruning.mimir.rewrite.feature.settings.mimirButtonPickerOnFocus
import com.gruning.mimir.rewrite.feature.settings.mimirButtonTileKey
import com.gruning.mimir.rewrite.feature.settings.withImportCancelled
import com.gruning.mimir.rewrite.feature.settings.withImportLanded
import com.gruning.mimir.rewrite.feature.settings.FocusRingDialScope
import com.gruning.mimir.rewrite.feature.settings.BackdropValueBackdrop
import com.gruning.mimir.rewrite.feature.settings.BackdropValueMedia
import com.gruning.mimir.rewrite.feature.settings.BackdropValueOff
import com.gruning.mimir.rewrite.feature.settings.EmulatorChoice
import com.gruning.mimir.rewrite.feature.settings.EmulatorDefinitionsRowId
import com.gruning.mimir.rewrite.feature.settings.PlatformEmulatorPickerScope
import com.gruning.mimir.rewrite.feature.settings.ThemePickerScope
import com.gruning.mimir.rewrite.feature.settings.RetroAchievementsConnectUi
import com.gruning.mimir.rewrite.feature.settings.canonicalSettingsDetailRowId
import com.gruning.mimir.rewrite.feature.settings.clearRetroAchievementsConnectDraft
import com.gruning.mimir.rewrite.feature.settings.detailKey
import com.gruning.mimir.rewrite.feature.settings.firstSelectableEmulatorAssignment
import com.gruning.mimir.rewrite.feature.settings.LanguageRowId
import com.gruning.mimir.rewrite.feature.settings.LibraryAddRowId
import com.gruning.mimir.rewrite.feature.settings.LibrarySourceRemovalReturn
import com.gruning.mimir.rewrite.feature.settings.librarySourceRemovalReturn
import com.gruning.mimir.rewrite.feature.settings.librarySourceRemovalRowId
import com.gruning.mimir.rewrite.feature.settings.LibrarySourceRemovalCancelRowId
import com.gruning.mimir.rewrite.feature.settings.LibraryAllowFilesRowId
import com.gruning.mimir.rewrite.feature.settings.LibraryFilesAccessLostCopy
import com.gruning.mimir.rewrite.feature.settings.FirstSelectableEmulatorRowId
import com.gruning.mimir.rewrite.feature.settings.ControllerShortcutQuickLaunchRowId
import com.gruning.mimir.rewrite.feature.settings.ControllerShortcutResetRowId
import com.gruning.mimir.rewrite.feature.settings.ControllerShortcutUtilityRowId
import com.gruning.mimir.rewrite.feature.settings.ControllerShortcutTweakerRowId
import com.gruning.mimir.rewrite.feature.settings.ControllerMappingLauncherRowId
import com.gruning.mimir.rewrite.feature.settings.ControllerMappingActionRowPrefix
import com.gruning.mimir.rewrite.feature.settings.ControllerMappingExitScope
import com.gruning.mimir.rewrite.feature.settings.openControllerMappingExitScope
import com.gruning.mimir.rewrite.feature.settings.ControllerMappingScope
import com.gruning.mimir.rewrite.feature.settings.LibrarySourceRemovalConfirmRowId
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * @param settingsIsDestination whether Settings is the destination under any overlay reporting a connect
 *   success right now; it decides whether [connectionFeedback] records that success at all.
 */
internal class MimirSettingsUiState(settingsIsDestination: () -> Boolean) {
    var themePickerOpen by mutableStateOf(false)
    var profileNameEditing by mutableStateOf(false)
    var steamGridDbKeyEditing by mutableStateOf(false)
    val connectionFeedback = SettingsConnectionFeedback(settingsIsDestination)
    var platformPickerFor by mutableStateOf<PlatformId?>(null)
    var backdropPickerOpen by mutableStateOf(false)
    // AP01: the two new Appearance dialers.
    var accentPickerOpen by mutableStateOf(false)
    var focusDialOpen by mutableStateOf(false)
    var focusDialStop by mutableStateOf(MimirRingStop.THEME_DEFAULT)
    // Concept A : the two appearance-image pickers, open while non-null. Their state is the
    // pickers' own pure model; the coordinator persists on Commit and never previews globally.
    var backgroundPicker by mutableStateOf<BackgroundPickerState?>(null)
    var mimirButtonPicker by mutableStateOf<MimirButtonPickerState?>(null)
    /** "on both screens" in the Background picker's subline, read once at open. */
    var appearancePickerDualScreen by mutableStateOf(false)
    /**
     * The import landing step's trigger: a picker's + Add image sets it, `MimirAppCoordinator` -- which owns the
     * image browser and the system picker's launcher -- observes it, clears it and opens the route.
     */
    var appearanceImportRequest by mutableStateOf<AppearanceImageKind?>(null)
    var sourceRemovalConfirmation by mutableStateOf<SourceRow?>(null)
    var sourceRemovalRunning by mutableStateOf(false)
    /** Where focus lands when the open removal confirmation closes: the row it came from on Cancel, the neighbour on a removal (F6). */
    var sourceRemovalReturn by mutableStateOf<LibrarySourceRemovalReturn?>(null)
    var settingsKeyboardHost: MimirKeyboardHost? = null
    // Settings › Music: the Source picker, and the one shared volume dial. The dial's
    // open flag and its session are two fields on the ring dial's own precedent (`focusDialOpen` / `focusDialStop`):
    // the host owns the exit fade, so the outgoing dial is still composed after close and must still have a
    // session to draw from. Close clears the flag and leaves the session in place.
    var musicSourcePickerOpen by mutableStateOf(false)
    /**
     * Which menu the Source picker's overlay slot shows: Source, Order, Music hours or Launch sound share one overlay and one focus scope,
     * since one of the four is open at a time. Set before the flag is raised, and left in place by a close so the host's exit fade still
     * draws the menu that is leaving.
     */
    var musicPickerMenu by mutableStateOf(MusicPickerMenu.SOURCE)
    var musicVolumeDialOpen by mutableStateOf(false)
    var musicVolumeDial by mutableStateOf<MusicVolumeDialSession?>(null)
    /** The equalizer editor's controller while it is open, else null. It has no exit fade: closing detaches it on the same frame. */
    var equalizerEditor by mutableStateOf<EqualizerEditorController?>(null)
}

/** Settings launchers, pickers, and row commands with one compact surface for input and rendering. */
internal class MimirSettingsCoordinator(
    val uiState: MimirSettingsUiState,
    val platformPickerChoices: List<EmulatorChoice>,
    val backdropMode: GamelistBackdropMode,
    val onRescanFolder: () -> Unit,
    val onRowActivate: (String) -> Unit,
    val openThemePicker: () -> Unit,
    val closeThemePicker: () -> Unit,
    val commitTheme: (MimirThemeId) -> Unit,
    val setProfileNameEditing: (Boolean) -> Unit,
    val setSteamGridDbKeyEditing: (Boolean) -> Unit,
    val retroAchievementsConnect: RetroAchievementsConnectController,
    val closePlatformPicker: () -> Unit,
    val choosePlatformDefault: (String?) -> Unit,
    val closeBackdropPicker: () -> Unit,
    val chooseBackdrop: (String) -> Unit,
    // AP01/CF01 — accent picker (every theme) + focus-ring dial (all themes).
    val closeAccentPicker: () -> Unit,
    val commitAccent: (MimirAccentStop) -> Unit,
    val commitAccentTemp: (MimirAccentTemp) -> Unit,
    val closeFocusDial: () -> Unit,
    val stepFocusDial: (MimirRingStop) -> Unit,
    val commitFocusRing: (MimirRingStop) -> Unit,
    // Concept A: Background and Mimir-button pickers. Focus previews on the stage; a transition may commit.
    val backgroundPickerFocus: (FocusKey?) -> Unit,
    val applyBackgroundPickerTransition: (BackgroundPickerTransition) -> Unit,
    val mimirButtonPickerFocus: (FocusKey?) -> Unit,
    val applyMimirButtonPickerTransition: (MimirButtonPickerTransition) -> Unit,
    /** The Mimir-button picker's own close: the scope popped and the state cleared, nothing written (the overlay sweep's full close while adjusting). */
    val closeMimirButtonPicker: () -> Unit,
    /** The import landing step's result: the open picker gains the tile and its note, and focus lands on it. */
    val appearanceImportLanded: (AppearanceImageKind, AppearanceImportOutcome) -> Unit,
    /** The system picker, opened from a picker's + Upload, was dismissed with nothing chosen: the open picker stops expecting to land on + Upload. */
    val appearanceImportCancelled: (AppearanceImageKind) -> Unit,
    val cancelSourceRemoval: () -> Unit,
    val closeControllerMapping: () -> Unit,
    val discardControllerMapping: () -> Unit,
    val continueControllerMapping: () -> Unit,
    val beginControllerMappingCapture: (String) -> Unit,
    val resetControllerMappingDraft: () -> Unit,
    val applyControllerMapping: () -> Unit,
    // Settings › Music (2026-09-30). `music` is the read model the pane and both overlays draw from; the rest
    // are the Source picker's and the volume dial's commands, shaped like the accent picker's and the ring dial's.
    val music: MusicReadModel,
    /** SECONDARY (X) on a focused detail row; true only when a row claimed it, so every other row leaves X alone. */
    val onRowSecondary: (String) -> Boolean,
    /** TERTIARY (Y) on a focused detail row; true only when a row claimed it (the Music folder row opens its Forget confirmation). */
    val onRowTertiary: (String) -> Boolean,
    val closeMusicSourcePicker: () -> Unit,
    val chooseMusicSource: (MusicSourceValue) -> Unit,
    val chooseMusicOrder: (MusicOrderValue) -> Unit,
    val chooseMusicHours: (MusicHoursValue) -> Unit,
    val chooseMusicLaunchSound: (MusicLaunchSoundValue) -> Unit,
    val stepMusicVolumeDial: (Int) -> Unit,
    val commitMusicVolumeDial: (Int) -> Unit,
    val closeMusicVolumeDial: () -> Unit,
    val closeEqualizerEditor: () -> Unit,
) {
    val themePickerOpen: Boolean
        get() = uiState.themePickerOpen

    val musicSourcePickerOpen: Boolean
        get() = uiState.musicSourcePickerOpen

    /** The menu in the Source picker's overlay slot; see [MimirSettingsUiState.musicPickerMenu]. */
    val musicPickerMenu: MusicPickerMenu
        get() = uiState.musicPickerMenu

    val musicVolumeDialOpen: Boolean
        get() = uiState.musicVolumeDialOpen

    val equalizerEditor: EqualizerEditorController?
        get() = uiState.equalizerEditor

    /** The dial's session; outlives [musicVolumeDialOpen] by the exit fade, so the outgoing dial still draws. */
    val musicVolumeDial: MusicVolumeDialSession?
        get() = uiState.musicVolumeDial

    val profileNameEditing: Boolean
        get() = uiState.profileNameEditing

    val steamGridDbKeyEditing: Boolean
        get() = uiState.steamGridDbKeyEditing

    val retroAchievementsKeyEditing: Boolean
        get() = retroAchievementsConnect.isOpen

    val retroAchievementsConnectUi: RetroAchievementsConnectUi?
        get() = retroAchievementsConnect.state

    val retroAchievementsValidating: Boolean
        get() = retroAchievementsConnect.isValidating

    /** Post-connect confirmation + focus return, shared by every connected service. */
    val connectionFeedback: SettingsConnectionFeedback
        get() = uiState.connectionFeedback

    val platformPickerFor: PlatformId?
        get() = uiState.platformPickerFor

    val backdropPickerOpen: Boolean
        get() = uiState.backdropPickerOpen

    val backdropValue: String
        get() = backdropMode.toMimirBackdropValue()

    val accentPickerOpen: Boolean
        get() = uiState.accentPickerOpen

    val focusDialOpen: Boolean
        get() = uiState.focusDialOpen

    val focusDialStop: MimirRingStop
        get() = uiState.focusDialStop

    val backgroundPicker: BackgroundPickerState?
        get() = uiState.backgroundPicker

    val mimirButtonPicker: MimirButtonPickerState?
        get() = uiState.mimirButtonPicker

    val appearancePickerDualScreen: Boolean
        get() = uiState.appearancePickerDualScreen

    val sourceRemovalConfirmation: SourceRow?
        get() = uiState.sourceRemovalConfirmation

    val sourceRemovalRunning: Boolean
        get() = uiState.sourceRemovalRunning
}

@Composable
internal fun rememberMimirSettingsCoordinator(
    core: MimirAppCoreCoordinator,
    library: MimirLibraryCoordinator,
    navigation: MimirNavigationCoordinator,
    read: MimirSettingsReadCoordinator,
    artwork: MimirArtworkCoordinator,
    achievements: MimirAchievementsCoordinator,
    migration: MimirMigrationCoordinator,
): MimirSettingsCoordinator {
    val context = core.context
    val authority = core.focusAuthority
    val scope = rememberCoroutineScope()
    // The connect overlays are shared with onboarding and the Get-art card, so a success is recorded only when
    // Settings is the destination beneath them (SettingsConnectionFeedback). The state outlives any one
    // coordinator projection, so the predicate reads the latest `navigation` rather than the first one
    // (compose-expert side-effects.md, rememberUpdatedState: a long-lived callback reading a changing value).
    val currentNavigation by rememberUpdatedState(navigation)
    val state = remember {
        MimirSettingsUiState(settingsIsDestination = { currentNavigation.destination == MimirDestination.SETTINGS })
    }
    val backdropMode by BackdropStore.mode.collectAsState()
    val platformPickerChoices = remember(state.platformPickerFor, read.emulatorProvider) {
        state.platformPickerFor?.let(read::choicesFor) ?: emptyList()
    }

    fun acceptSource(treeUri: Uri?, role: LibrarySourceRole) {
        if (treeUri == null) return
        // Read+write (write-fallback-to-read) — a read-only persist leaves media_art enrichment
        // permanently blocked once the picker session's transient write grant dies (RP5 2026-07-16).
        persistOnboardingTreeAccess(context, treeUri)
        library.addSource(treeUri.toString(), role)
    }
    val primaryTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri -> acceptSource(treeUri, LibrarySourceRole.PRIMARY) }
    val additionalTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri -> acceptSource(treeUri, LibrarySourceRole.ADDITIONAL) }
    fun launchSourcePicker(role: LibrarySourceRole, announceMissingPrimary: Boolean = false) {
        // Mimir's own browser is the path; the system picker survives only as a
        // fallback for revoked access. Every hop into documentsui costs a measured fan surge, so the
        // fallback is never taken while Mimir can enumerate storage itself.
        val allFilesAccess = runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
        when (addGamesRoute(library.sourceOperationRunning, allFilesAccess)) {
            AddGamesRoute.BUSY -> {
                showMimirMessage(context, "Library update in progress", MimirMessageClass.Guidance)
                return
            }
            AddGamesRoute.IN_APP_BROWSER -> {
                if (role == LibrarySourceRole.PRIMARY && announceMissingPrimary) {
                    showMimirMessage(context, "Choose your Mimir ROM folder first")
                }
                FileBrowserStore.open(
                    // No rootPath: the store derives it from the device's REAL volumes via
                    // StorageVolumeListing.browserRoot -- the drive chooser when there is more than one
                    // drive, the single drive otherwise. This replaces
                    // `FolderBrowserEnumeration.storageRoots().firstOrNull()`, which handed the browser
                    // the FIRST root only; since a FileBrowserController cannot navigate above its own
                    // root, that made an SD card unreachable by construction (the reported inability to switch between drives). `context` is here purely because volume enumeration
                    // needs it -- see FileBrowserStore.open's own KDoc.
                    context = context,
                    authority = authority,
                    onChoose = { chosen ->
                        // The drive chooser is not a folder the scanner can be handed: adding
                        // "/storage" as a ROMs source would enrol every drive at once, including the
                        // one the player did not pick. Interim defense only -- the real fix is
                        // suppressing the commit affordance at the chooser root, which needs a
                        // `feature-files` change that remains pending. Until it lands, FileBrowserCommand.Commit closes the browser
                        // BEFORE onChoose runs, so this refusal is a Toast after the fact rather than
                        // an unreachable button -- honest, but rough, and flagged as such.
                        if (StorageVolumeListing.isVolumeRoot(chosen)) {
                            showMimirMessage(
                                context,
                                "Pick a folder inside a drive",
                            )
                        } else {
                            library.addSource(chosen, role)
                        }
                    },
                    // SECONDARY on an archive row -- policy 5. No progress UI exists for this
                    // yet (FileBrowserSurface has none), so a Toast is the honest minimum: the player
                    // presses SECONDARY and sees SOMETHING happen, rather than the browser silently
                    // doing nothing it did not report (matching this same function's own
                    // "Library update in progress" Toast a few lines above).
                    onExtracted = { _, destination, count ->
                        val itemWord = if (count == 1) "item" else "items"
                        showMimirMessage(
                            context,
                            "Extracted $count $itemWord to ${File(destination).name}",
                            MimirMessageClass.Confirmation,
                        )
                    },
                    onExtractFailed = { archivePath, _ ->
                        showMimirMessage(
                            context,
                            "Couldn't extract ${File(archivePath).name}",
                        )
                    },
                    // The inspector's Verify button. Same honest-minimum posture as the extraction
                    // Toasts above: no inspector Checksum-row display exists yet (that is
                    // `feature-files`), so a Toast is what keeps the button from doing something the
                    // player cannot see. The digest is REPORTED, not matched -- comparing it against
                    // the bundled DAT corpus is separately owed. Truncated because a 40-character hex
                    // string in a Toast is unreadable and the leading bytes are what a player would
                    // eyeball against a DAT entry.
                    onChecksum = { _, digest ->
                        showMimirMessage(
                            context,
                            "SHA-1 ${digest.take(12)}…",
                            MimirMessageClass.Guidance,
                            long = true,
                        )
                    },
                    onChecksumFailed = { path, _ ->
                        showMimirMessage(
                            context,
                            "Couldn't read ${File(path).name}",
                        )
                    },
                    // §6.2 , slice 3: no `onMoved`/`onCopied`
                    // sinks here, deliberately -- mirroring this exact call's own pre-existing silence on
                    // `onDeleted`. This call never sets `mode`, so it always opens
                    // FileBrowserMode.PICK, and `transfer()` -- the only place either sink fires --
                    // returns before doing anything when `operations` is null, which it always is in
                    // PICK (policy §3c; see FileBrowserStore.kt's own `operations` field KDoc). A
                    // Toast that can never show would be dead code explaining its own deadness; leaving
                    // it at open()'s no-op default says the same thing in zero lines.
                    // AppBrightnessCoordinator.kt's MANAGE-only call site is where these actually fire.
                    classifyPlatform = FileBrowserPlatformClassifier.forBrowser,
                )
                return
            }
            AddGamesRoute.SYSTEM_PICKER -> Unit
        }
        if (role == LibrarySourceRole.PRIMARY) {
            if (announceMissingPrimary) {
                // A SYSTEM toast on purpose: the system folder picker opens on the very next line and covers Main at once, so a message on Main's rail would never be seen.
                // The toast outlives the picker's launch. (The in-app browser route above opens an overlay Mimir draws itself, so its message does show.)
                Toast.makeText(context, "Choose your Mimir ROM folder first", Toast.LENGTH_SHORT).show()
            }
            primaryTreeLauncher.launch(null)
        } else {
            additionalTreeLauncher.launch(null)
        }
    }
    val closeThemePicker = {
        if (authority.state.value.scope == ThemePickerScope) authority.popScope()
        state.themePickerOpen = false
    }
    val openThemePicker = {
        state.themePickerOpen = true
        authority.pushScope(ThemePickerScope)
    }
    val commitTheme: (MimirThemeId) -> Unit = { themeId ->
        ThemeStore.setTheme(context, themeId)
        closeThemePicker()
    }
    val closePlatformPicker = {
        if (authority.state.value.scope == PlatformEmulatorPickerScope) authority.popScope()
        state.platformPickerFor = null
    }
    val openPlatformPicker: (PlatformId) -> Unit = { platformId ->
        state.platformPickerFor = platformId
        authority.pushScope(PlatformEmulatorPickerScope)
    }
    val choosePlatformDefault: (String?) -> Unit = { idValue ->
        state.platformPickerFor?.let { platformId ->
            val emulatorId = idValue?.let(::EmulatorId)
            PlatformEmulatorDefaultStore.setDefault(
                context,
                platformId,
                emulatorId,
            )
            scope.launch { core.launchController.biosEmulatorAssignmentChanged(platformId, emulatorId) }
        }
        closePlatformPicker()
    }
    val closeBackdropPicker = {
        if (authority.state.value.scope == BackdropPickerScope) authority.popScope()
        state.backdropPickerOpen = false
    }
    val openBackdropPicker = {
        state.backdropPickerOpen = true
        authority.pushScope(BackdropPickerScope)
    }
    val chooseBackdrop: (String) -> Unit = { value ->
        BackdropStore.setMode(context, value.toMimirBackdropMode())
        closeBackdropPicker()
    }
    // AP01/CF01 accent picker — Cancel clears BOTH live previews (nothing persisted); Commit persists
    // (Default = curated stop, named theme = temperature) and the preview collapses into it.
    val closeAccentPicker = {
        MimirAppearanceOverrides.accentPreview = null
        MimirAppearanceOverrides.accentTempPreview = null
        if (authority.state.value.scope == AccentPickerScope) authority.popScope()
        state.accentPickerOpen = false
    }
    val openAccentPicker = {
        state.accentPickerOpen = true
        authority.pushScope(AccentPickerScope)
    }
    val commitAccent: (MimirAccentStop) -> Unit = { stop ->
        AppearanceOverrideStore.setAccent(context, stop)
        closeAccentPicker()
    }
    val commitAccentTemp: (MimirAccentTemp) -> Unit = { temp ->
        AppearanceOverrideStore.setAccentTemp(context, ThemeStore.themeId.value, temp)
        closeAccentPicker()
    }
    // AP01 focus-ring dial — the dial opens ON the active theme's committed stop; stepping previews live;
    // Commit persists per-theme ("Theme default" = the reset detent); Cancel restores the on-open look.
    val closeFocusDial = {
        MimirAppearanceOverrides.ringPreview = null
        if (authority.state.value.scope == FocusRingDialScope) authority.popScope()
        state.focusDialOpen = false
    }
    val openFocusDial = {
        state.focusDialStop = MimirAppearanceOverrides.ringStops[ThemeStore.themeId.value]
            ?: MimirRingStop.THEME_DEFAULT
        state.focusDialOpen = true
        authority.pushScope(FocusRingDialScope)
    }
    val stepFocusDial: (MimirRingStop) -> Unit = { stop -> state.focusDialStop = stop }
    val commitFocusRing: (MimirRingStop) -> Unit = { stop ->
        AppearanceOverrideStore.setRing(context, ThemeStore.themeId.value, stop)
        closeFocusDial()
    }

    // ── Concept A : the Background and Mimir-button pickers ──────────────────────────────
    // The pickers take data and callbacks, never the store: the saved lists are projected into their models
    // here, a Commit persists through AppearanceOverrideStore, and nothing previews globally (the stage does).
    fun backgroundPickerImages(themeId: MimirThemeId): List<BackgroundPickerImage> =
        AppearanceOverrideStore.savedBackgrounds.value.mapIndexed { index, saved ->
            BackgroundPickerImage(
                id = saved.id,
                label = backgroundImageLabel(index),
                path = saved.path.takeIf { saved.available },
                width = saved.width,
                height = saved.height,
                p90 = saved.p90,
                usedBy = AppearanceOverrideStore.themesUsingBackground(saved.id).filter { it != themeId }.map { it.label },
            )
        }
    fun mimirButtonPickerMarks(): List<MimirButtonPickerMark> =
        AppearanceOverrideStore.savedMarks.value.mapIndexed { index, saved ->
            MimirButtonPickerMark(
                id = saved.id,
                label = mimirButtonMarkLabel(index),
                path = saved.path.takeIf { saved.available },
                placement = saved.placement,
            )
        }
    // The template's own retrying lander: a tile registers its bounds a layout after it composes, and
    // FocusAuthority.requestFocus drops a key that has not registered, so a one-frame attempt lost the import's new tile.
    fun landPickerFocus(key: FocusKey) {
        scope.launch { authority.landPickerFocus(key) }
    }
    val closeBackgroundPicker = {
        if (authority.state.value.scope == BackgroundPickerScope) authority.popScope()
        state.backgroundPicker = null
    }
    val openBackgroundPicker = {
        val themeId = ThemeStore.themeId.value
        state.appearancePickerDualScreen = runCatching {
            context.getSystemService(DisplayManager::class.java)
                ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
                ?.isNotEmpty() == true
        }.getOrDefault(false)
        state.backgroundPicker = backgroundPickerInitialState(
            themeId = themeId,
            current = MimirAppearanceOverrides.grounds[themeId],
            saved = backgroundPickerImages(themeId),
        )
        // Decode the saved images off the main thread now (brief §C), so the stage's first bake of a tile the
        // player focuses finds its copy resident instead of decoding on the UI thread. The store keeps the last
        // four resident; the id alone keys a decode, so the steps passed here are immaterial.
        MimirSceneGroundImages.prefetch(
            AppearanceOverrideStore.savedBackgrounds.value
                .filter { it.available }
                .map { it.toGround(MimirSceneGroundDim.MEDIUM, MimirSceneGroundBlur.OFF) },
        )
        authority.pushScope(BackgroundPickerScope)
    }
    val backgroundPickerFocus: (FocusKey?) -> Unit = { key ->
        state.backgroundPicker?.let { picker -> state.backgroundPicker = backgroundPickerOnFocus(picker, key) }
    }
    val applyBackgroundPickerTransition: (BackgroundPickerTransition) -> Unit = { transition ->
        state.backgroundPicker = transition.state
        when (val command = transition.command) {
            is BackgroundPickerCommand.Commit ->
                AppearanceOverrideStore.setGround(context, transition.state.themeId, command.ground)
            is BackgroundPickerCommand.Remove -> scope.launch {
                // The store's file work runs off the key press (audit 2026-10-14, RC-29, S-24 (3)): the press committed the transition above, and the picker is updated
                // here from its LIVE state once the list has been re-read, so a focus move made while the delete ran is kept. The tile goes when the list does.
                withContext(Dispatchers.IO) { AppearanceOverrideStore.removeSavedBackground(context, command.id) }
                state.backgroundPicker?.let { live ->
                    state.backgroundPicker = live.copy(saved = backgroundPickerImages(live.themeId))
                }
                // The removed tile's key left the graph with it; land on Atmosphere rather than on nothing.
                landPickerFocus(BackgroundAtmosphereKey)
            }
            BackgroundPickerCommand.Import -> state.appearanceImportRequest = AppearanceImageKind.BACKGROUND
            BackgroundPickerCommand.Dismiss -> closeBackgroundPicker()
            null -> Unit
        }
    }
    val closeMimirButtonPicker = {
        if (authority.state.value.scope == MimirButtonPickerScope) authority.popScope()
        state.mimirButtonPicker = null
    }
    val openMimirButtonPicker = {
        state.mimirButtonPicker = mimirButtonPickerInitialState(
            current = MimirAppearanceOverrides.buttonMark,
            saved = mimirButtonPickerMarks(),
        )
        authority.pushScope(MimirButtonPickerScope)
    }
    val mimirButtonPickerFocus: (FocusKey?) -> Unit = { key ->
        state.mimirButtonPicker?.let { picker -> state.mimirButtonPicker = mimirButtonPickerOnFocus(picker, key) }
    }
    val applyMimirButtonPickerTransition: (MimirButtonPickerTransition) -> Unit = { transition ->
        state.mimirButtonPicker = transition.state
        when (val command = transition.command) {
            // Only A pushes the mark to the live button: setButtonMark ticks the revision the overlay re-reads on.
            is MimirButtonPickerCommand.Commit -> AppearanceOverrideStore.setButtonMark(context, command.mark)
            // Adjust mode's A : the placement goes into that image's sidecar and the image is committed the way A on its
            // tile does it (setButtonMark, one revision tick, so the live ᛗ repaints at once). A failed write commits nothing and says so.
            is MimirButtonPickerCommand.SaveAdjusted -> scope.launch {
                // The sidecar write and the list re-read run off the key press (audit 2026-10-14, RC-29, S-25 (3)); the picker takes the outcome here on its LIVE
                // state, and lands focus on the tile after the state is written.
                val stored = withContext(Dispatchers.IO) {
                    AppearanceOverrideStore.saveMarkPlacement(context, command.mark.id, command.mark.markPlacement())
                }
                // The commit is on the main thread, in the block that writes the picker's own state (W1A #4): the live ᛗ and the picker's `current` change in one frame.
                stored?.let { AppearanceOverrideStore.setButtonMark(context, it) }
                state.mimirButtonPicker?.let { live ->
                    state.mimirButtonPicker = if (stored != null) {
                        live.copy(saved = mimirButtonPickerMarks(), current = stored)
                    } else {
                        live.withAdjustSaveFailed(saved = mimirButtonPickerMarks(), current = MimirAppearanceOverrides.buttonMark)
                    }
                }
                landPickerFocus(mimirButtonTileKey(command.mark.id))
            }
            // Leaving adjust mode lands focus back on the image's tile (the pad's focus never left it; this is the guarantee, not the move).
            is MimirButtonPickerCommand.LeaveAdjust -> landPickerFocus(mimirButtonTileKey(command.id))
            is MimirButtonPickerCommand.Remove -> scope.launch {
                withContext(Dispatchers.IO) { AppearanceOverrideStore.removeSavedMark(context, command.id) }
                state.mimirButtonPicker?.let { live -> state.mimirButtonPicker = live.copy(saved = mimirButtonPickerMarks()) }
                landPickerFocus(MimirButtonRuneKey)
            }
            MimirButtonPickerCommand.Import -> state.appearanceImportRequest = AppearanceImageKind.MARK
            MimirButtonPickerCommand.Dismiss -> closeMimirButtonPicker()
            null -> Unit
        }
    }
    // The system picker was dismissed with nothing chosen: the picker never remounted, so it is told that + Upload is no longer where it should land.
    val appearanceImportCancelled: (AppearanceImageKind) -> Unit = { kind ->
        when (kind) {
            AppearanceImageKind.BACKGROUND -> state.backgroundPicker?.let { state.backgroundPicker = it.withImportCancelled() }
            AppearanceImageKind.MARK -> state.mimirButtonPicker?.let { state.mimirButtonPicker = it.withImportCancelled() }
        }
    }
    val appearanceImportLanded: (AppearanceImageKind, AppearanceImportOutcome) -> Unit = { kind, outcome ->
        val note = appearanceImportNote(outcome)
        val savedId = (outcome as? AppearanceImportOutcome.Saved)?.image?.id
        when (kind) {
            AppearanceImageKind.BACKGROUND -> state.backgroundPicker?.let { picker ->
                state.backgroundPicker = picker.withImportLanded(
                    saved = backgroundPickerImages(picker.themeId),
                    importedId = savedId,
                    note = note,
                )
                savedId?.let { landPickerFocus(backgroundTileKey(it)) }
            }
            AppearanceImageKind.MARK -> state.mimirButtonPicker?.let { picker ->
                state.mimirButtonPicker = picker.withImportLanded(
                    saved = mimirButtonPickerMarks(),
                    importedId = savedId,
                    note = note,
                )
                savedId?.let { landPickerFocus(mimirButtonTileKey(it)) }
            }
        }
    }

    // ── Settings › Music ──────────────────────────
    // The Source picker on the accent picker's shape: open pushes its scope, choose persists and closes.
    val closeMusicSourcePicker = {
        if (authority.state.value.scope == MusicSourcePickerScope) authority.popScope()
        state.musicSourcePickerOpen = false
    }
    // Source, Order, Music hours and Launch sound are four menus in one overlay slot: the menu is chosen before the flag is raised.
    fun openMusicMenu(menu: MusicPickerMenu) {
        state.musicPickerMenu = menu
        state.musicSourcePickerOpen = true
        authority.pushScope(MusicSourcePickerScope)
    }
    val openMusicSourcePicker = { openMusicMenu(MusicPickerMenu.SOURCE) }
    val openMusicOrderPicker = { openMusicMenu(MusicPickerMenu.ORDER) }
    val openMusicHoursPicker = { openMusicMenu(MusicPickerMenu.HOURS) }
    val openMusicLaunchSoundPicker = { openMusicMenu(MusicPickerMenu.LAUNCH_SOUND) }
    val chooseMusicSource: (MusicSourceValue) -> Unit = { value ->
        AudioPreferences.setMusicSource(context, mimirMusicSource(value))
        // Choosing the source is the player playing Mimir's music: it takes the audio over from another app and ends their earlier pause,
        // as turning Music on does (and the lead's decision on it).
        MimirMusicControl.onSourceChosen()
        closeMusicSourcePicker()
    }
    val chooseMusicOrder: (MusicOrderValue) -> Unit = { value ->
        AudioPreferences.setMusicOrder(context, mimirMusicOrder(value))
        closeMusicSourcePicker()
    }
    val chooseMusicHours: (MusicHoursValue) -> Unit = { value ->
        AudioPreferences.setMusicHours(context, mimirMusicHours(value))
        closeMusicSourcePicker()
    }
    // Launch sound is one choice over the two stored preferences (update): Off is the toggle off, any other step is the toggle on at that
    // level, so nothing stored changes shape and a listener who had the toggle on at 50 percent still reads 50.
    val chooseMusicLaunchSound: (MusicLaunchSoundValue) -> Unit = { value ->
        val write = musicLaunchSoundWrite(value)
        AudioPreferences.setLaunchSoundEnabled(context, write.enabled)
        write.volume?.let { AudioPreferences.setLaunchSoundVolume(context, it) }
        closeMusicSourcePicker()
    }
    // The volume dial. There is no preview channel on AudioPreferences (unlike the ring's `ringPreview`), so
    // every detent is applied LIVE through the setter the Utility slider also uses -- the player hears each
    // step -- and B rewrites the level the dial opened on through that same setter. A closes with nothing to
    // restore. Opening on the stored level's nearest detent means a B with no step touches nothing at all.
    fun applyMusicVolume(target: MusicVolumeDialTarget, percent: Int) {
        val fraction = musicVolumeFraction(percent)
        when (target) {
            MusicVolumeDialTarget.MUSIC -> AudioPreferences.setAmbientVolume(context, fraction)
        }
    }
    val closeMusicVolumeDial = {
        // Guarded on the flag: the host's exit fade can call this again on a dial already closed, and a second
        // revert through the setter would be a second audible step. The session stays for the fade to draw.
        if (state.musicVolumeDialOpen) {
            state.musicVolumeDial?.let { dial ->
                if (dial.levelPercent != dial.openedAtPercent) {
                    applyMusicVolume(dial.target, dial.openedAtPercent)
                    state.musicVolumeDial = dial.copy(levelPercent = dial.openedAtPercent)
                }
            }
            if (authority.state.value.scope == MusicVolumeDialScope) authority.popScope()
            state.musicVolumeDialOpen = false
        }
    }
    val openMusicVolumeDial: (MusicVolumeDialTarget) -> Unit = { target ->
        val current = when (target) {
            MusicVolumeDialTarget.MUSIC -> musicVolumePercent(AudioPreferences.ambientVolume.value)
        }
        state.musicVolumeDial = MusicVolumeDialSession(target = target, levelPercent = current, openedAtPercent = current)
        state.musicVolumeDialOpen = true
        authority.pushScope(MusicVolumeDialScope)
    }
    val stepMusicVolumeDial: (Int) -> Unit = { percent ->
        state.musicVolumeDial?.takeIf { state.musicVolumeDialOpen }?.let { dial ->
            applyMusicVolume(dial.target, percent)
            state.musicVolumeDial = dial.copy(levelPercent = percent)
        }
    }
    val commitMusicVolumeDial: (Int) -> Unit = { percent ->
        state.musicVolumeDial?.takeIf { state.musicVolumeDialOpen }?.let { dial ->
            applyMusicVolume(dial.target, percent)
            // Kept: the on-open level becomes this one, so the close below has nothing to put back.
            state.musicVolumeDial = dial.copy(levelPercent = percent, openedAtPercent = percent)
        }
        closeMusicVolumeDial()
    }
    // The equalizer editor. Every step applies and is stored at once, so there is nothing to revert: close keeps what was set. It
    // draws no exit (the host detaches it on the same frame), so the controller goes with the flag and the scope is discarded here.
    val closeEqualizerEditor = {
        state.equalizerEditor?.let { editor ->
            state.equalizerEditor = null
            editor.dispose()
        }
        Unit
    }
    val openEqualizerEditor = {
        if (state.equalizerEditor == null) {
            state.equalizerEditor = EqualizerEditorController(
                engine = MimirEqualizerEditorEngine,
                authority = authority,
                onClose = closeEqualizerEditor,
            )
        }
    }
    // The music folder, the spec's two lanes. With all-files access Mimir's own browser in its folder-pick mode
    // hands back a plain path, which goes to the index as a `file://` tree. Without it, the system tree picker,
    // started at `<primary library tree>/music` as a hint, with a persistable READ grant only: Mimir plays from
    // this folder and never writes into it, so a write grant would be a claim with nothing behind it. Neither
    // lane goes anywhere near the privileged bridge.
    val musicTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        if (treeUri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            // The pick is a deliberate start of Your music, marked when the plan plays the folder . The control gives the
            // library the folder and then switches the source, in that order.
            MimirMusicControl.onFolderPicked(context, treeUri.toString())
        }
    }
    fun launchMusicFolderPicker() {
        val allFilesAccess = runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
        if (allFilesAccess) {
            FileBrowserStore.open(
                context = context,
                authority = authority,
                mode = FileBrowserMode.PICK_MUSIC_FOLDER,
                onChoose = { chosen ->
                    // The drive chooser is not a folder of tracks; the same refusal the ROM-source pick makes.
                    if (StorageVolumeListing.isVolumeRoot(chosen)) {
                        showMimirMessage(context, "Pick a folder inside a drive")
                    } else {
                        MimirMusicControl.onFolderPicked(context, Uri.fromFile(File(chosen)).toString())
                    }
                },
            )
        } else {
            val primaryRoot = library.readModel?.sources.orEmpty().firstOrNull(SourceRow::isPrimary)?.rootUri
            musicTreeLauncher.launch(musicFolderInitialUri(primaryRoot))
        }
    }
    // The Behaviour sub-page is a focus scope pushed over the detail pane, so the authority carries the way back: it remembers the Behaviour row, and B
    // pops to it. Entry always lands on the page's first switch, and only from the detail scope (a press inside the page is not an entry).
    fun openMusicBehaviour() {
        if (authority.state.value.scope != DetailScope) return
        authority.rememberFocusForScope(MusicBehaviourScope, detailKey(MusicBehaviourRowIds.first()))
        authority.pushScope(MusicBehaviourScope)
    }
    // Y on the Music folder row asks before it forgets (the fix round on the earlier update): the confirmation is a focus scope pushed over the detail pane, like the
    // Behaviour page, so B pops it and the authority's focus memory lands back on the Music folder row. Cancel holds focus first.
    fun openMusicFolderForget() {
        if (authority.state.value.scope != DetailScope) return
        authority.rememberFocusForScope(MusicFolderForgetScope, detailKey(MusicFolderForgetCancelRowId))
        authority.pushScope(MusicFolderForgetScope)
    }
    fun closeMusicFolderForget() {
        if (authority.state.value.scope == MusicFolderForgetScope) authority.popScope()
    }
    fun confirmMusicFolderForget() {
        MimirMusicLibrary.forget(context)
        showMimirMessage(context, "Music folder forgotten", MimirMessageClass.Confirmation)
        closeMusicFolderForget()
    }
    fun runMusicRowCommand(command: MusicRowCommand) {
        when (command) {
            MusicRowCommand.ToggleMusic -> {
                val turnOn = !AudioPreferences.musicEnabled.value
                AudioPreferences.setMusicEnabled(context, turnOn)
                // Turning Music on is the player playing Mimir's music: it takes the audio over from another app and ends their earlier
                // pause, so Music on means it plays .
                if (turnOn) MimirMusicControl.onMusicTurnedOn()
            }
            MusicRowCommand.OpenSourcePicker -> openMusicSourcePicker()
            MusicRowCommand.OpenOrderPicker -> openMusicOrderPicker()
            MusicRowCommand.OpenHoursPicker -> openMusicHoursPicker()
            MusicRowCommand.OpenLaunchSoundPicker -> openMusicLaunchSoundPicker()
            MusicRowCommand.ToggleFade ->
                AudioPreferences.setFadeBetweenTracks(context, !AudioPreferences.fadeBetweenTracks.value)
            MusicRowCommand.ToggleOutsideMimir ->
                AudioPreferences.setPlayOutsideMimir(context, !AudioPreferences.playOutsideMimir.value)
            MusicRowCommand.ToggleScreenOffPause ->
                AudioPreferences.setPauseOnScreenOff(context, !AudioPreferences.pauseOnScreenOff.value)
            MusicRowCommand.ToggleUnplugPause ->
                AudioPreferences.setPauseOnUnplug(context, !AudioPreferences.pauseOnUnplug.value)
            is MusicRowCommand.OpenVolumeDial -> openMusicVolumeDial(command.target)
            MusicRowCommand.OpenEqualizer -> openEqualizerEditor()
            MusicRowCommand.OpenBehaviour -> openMusicBehaviour()
            MusicRowCommand.ToggleSongTitles ->
                AudioPreferences.setSongTitlesEnabled(context, !AudioPreferences.songTitlesEnabled.value)
            MusicRowCommand.PickFolder -> launchMusicFolderPicker()
            MusicRowCommand.CancelFolderForget -> closeMusicFolderForget()
            MusicRowCommand.ConfirmFolderForget -> confirmMusicFolderForget()
            MusicRowCommand.OpenNotificationAccess -> CompanionMediaSessionBridge.openAccessSettingsFromHost(context)
            MusicRowCommand.ToggleKeepMusicInGames ->
                AudioPreferences.setKeepMusicInGames(context, !AudioPreferences.keepMusicInGames.value)
            is MusicRowCommand.GameDownload -> when (command.action) {
                // Not the Appearance row: the connect sheet itself, which is what that row opens too.
                MusicGameDownloadAction.CONNECT -> artwork.state.openArtworkProfile()
                MusicGameDownloadAction.WAIT -> showMimirMessage(context, ArtworkDownloadingCopy, MimirMessageClass.Guidance)
                MusicGameDownloadAction.CANCEL -> artwork.state.cancelGameMusicDownload()
                MusicGameDownloadAction.START -> artwork.state.startGameMusicDownload()
            }
        }
    }
    // X on the Music folder row rescans it (update; it forgot the folder before). The row's own line says "Scanning…" while it runs, and a press
    // during a scan is absorbed by the index.
    val onRowSecondary: (String) -> Boolean = { rowId ->
        val hasFolder = MimirMusicLibrary.state.value.treeUri != null
        if (musicRowSecondaryRescans(canonicalSettingsDetailRowId(rowId), hasFolder)) {
            MimirMusicLibrary.rescan(context)
            true
        } else {
            false
        }
    }

    // Y on the Music folder row opens the Forget folder confirmation; every other row leaves Y alone.
    val onRowTertiary: (String) -> Boolean = { rowId ->
        val hasFolder = MimirMusicLibrary.state.value.treeUri != null
        if (musicRowTertiaryForgets(canonicalSettingsDetailRowId(rowId), hasFolder)) {
            openMusicFolderForget()
            true
        } else {
            false
        }
    }

    val onRescanFolder: () -> Unit = {
        when (libraryAddGamesPickerRoute(
            library.readModel?.sources.orEmpty(),
            library.sourceOperationRunning,
        )) {
            LibraryFolderPickerRoute.BUSY ->
                showMimirMessage(context, "Library update in progress", MimirMessageClass.Guidance)
            LibraryFolderPickerRoute.PRIMARY -> launchSourcePicker(
                LibrarySourceRole.PRIMARY,
                announceMissingPrimary = true,
            )
            LibraryFolderPickerRoute.ADDITIONAL -> launchSourcePicker(LibrarySourceRole.ADDITIONAL)
        }
    }

    val retroAchievementsConnect = remember(context, achievements.state, scope) {
        RetroAchievementsConnectController(
            scope = scope,
            beginAttempt = RetroAchievementsCredentialStore::beginConnectAttempt,
            validate = achievements.state::validateAccount,
            persistAccepted = { token, username, apiKey ->
                RetroAchievementsCredentialStore.commitAccepted(
                    context = context,
                    token = token,
                    username = username,
                    apiKey = apiKey,
                )
            },
            publishIfCurrent = RetroAchievementsCredentialStore::publishConnectResultIfCurrent,
            cancelAttempt = RetroAchievementsCredentialStore::cancelConnectAttempt,
            recoverUncertain = { RetroAchievementsCredentialStore.clear(context) },
            onAcceptedClosed = {
                state.connectionFeedback.succeeded("profile/retroachievements")
            },
        )
    }

    // Disconnect closes runtime use first, then proves durable key removal. Nothing already shown is
    // deleted; a failed local removal stays closed for this process and asks the user to retry.
    val onDisconnectRetroAchievements: () -> Unit = {
        val cleared = RetroAchievementsCredentialStore.clear(context)
        clearRetroAchievementsConnectDraft()
        retroAchievementsDisconnectFailureMessage(cleared)?.let { message ->
            showMimirMessage(context, message, long = true)
        }
    }

    fun requestDetailFocus(rowId: String) {
        scope.launch {
            withFrameNanos { }
            authority.requestFocus(detailKey(rowId))
        }
    }
    // The "Allow file access" row goes when the grant returns; its focus belongs on RESCAN, not on a neighbouring source.
    LibraryAllowRowFocusHandoff(authority, read.allFilesAccess) { requestDetailFocus("library/rescan") }

    fun cancelSourceRemoval() {
        if (state.sourceRemovalRunning) return
        // Cancel and B leave the folder listed: the ring goes back to the REMOVE row that was pressed, not to ADD LIBRARY FOLDER (F6).
        val returnRowId = state.sourceRemovalReturn?.onCancel ?: LibraryAddRowId
        state.sourceRemovalConfirmation = null
        state.sourceRemovalReturn = null
        requestDetailFocus(returnRowId)
    }

    fun openSourceRemoval(source: SourceRow) {
        if (source.isPrimary || state.sourceRemovalRunning) return
        state.sourceRemovalConfirmation = source
        state.sourceRemovalReturn = librarySourceRemovalReturn(library.readModel?.sources.orEmpty(), source)
        requestDetailFocus(LibrarySourceRemovalCancelRowId)
    }

    fun confirmSourceRemoval() {
        val source = state.sourceRemovalConfirmation ?: return
        if (state.sourceRemovalRunning) return
        state.sourceRemovalRunning = true
        library.removeAdditionalSource(source.rootUri) { result ->
            state.sourceRemovalRunning = false
            when (result) {
                is com.gruning.mimir.rewrite.core.model.library.SourceRemovalResult.Removed,
                com.gruning.mimir.rewrite.core.model.library.SourceRemovalResult.NotFound -> {
                    // The folder is gone, so the ring goes to the neighbour that takes its place.
                    val returnRowId = state.sourceRemovalReturn?.onRemoved ?: LibraryAddRowId
                    state.sourceRemovalConfirmation = null
                    state.sourceRemovalReturn = null
                    showMimirMessage(context, "Library folder removed from Mimir", MimirMessageClass.Confirmation)
                    requestDetailFocus(returnRowId)
                }
                com.gruning.mimir.rewrite.core.model.library.SourceRemovalResult.PrimaryRejected ->
                    showMimirMessage(context, "The Mimir ROM folder cannot be removed here", long = true)
                com.gruning.mimir.rewrite.core.model.library.SourceRemovalResult.Unsupported ->
                    showMimirMessage(context, "This Library folder cannot be removed yet", long = true)
                is com.gruning.mimir.rewrite.core.model.library.SourceRemovalResult.Failed ->
                    showMimirMessage(context, "Couldn’t remove this Library folder", long = true)
            }
        }
    }

    fun beginControllerShortcutCapture(action: ControllerShortcutAction) {
        val generation = ControllerShortcutStore.beginCapture(action)
        showMimirMessage(
            context,
            "Press an extra controller button · B cancels",
            MimirMessageClass.Guidance,
            long = true,
        )
        scope.launch {
            delay(10_000L)
            if (ControllerShortcutStore.timeoutCapture(generation)) {
                showMimirMessage(context, "Controller shortcut capture timed out")
            }
        }
    }

    fun leaveControllerMappingScopes() {
        while (authority.state.value.scope == ControllerMappingScope ||
            authority.state.value.scope == ControllerMappingExitScope
        ) {
            authority.popScope()
        }
    }
    val closeControllerMapping = {
        if (ControllerMappingStore.requestDismiss()) {
            leaveControllerMappingScopes()
        } else if (authority.state.value.scope == ControllerMappingScope) {
            // A dirty mapping asks before it leaves: the exit sheet's scope opens in THIS frame, with Apply planted as its landing (audit 2026-10-14, S-40b),
            // not from an effect a frame after the sheet is visible. The overlay keeps its own idempotent net for any other caller.
            openControllerMappingExitScope(authority)
        }
    }
    val discardControllerMapping = {
        ControllerMappingStore.dismiss()
        leaveControllerMappingScopes()
    }
    val continueControllerMapping = {
        ControllerMappingStore.continueEditing()
        if (authority.state.value.scope == ControllerMappingExitScope) authority.popScope()
    }
    val openControllerMapping = {
        ControllerMappingStore.open()
        authority.pushScope(ControllerMappingScope)
    }
    val beginControllerMappingCapture: (String) -> Unit = { actionName ->
        controllerMappingActionFromName(actionName)?.let { action ->
            val generation = ControllerMappingStore.beginCapture(action)
            if (generation >= 0L) {
                scope.launch {
                    delay(5_000L)
                    if (ControllerMappingStore.timeoutCapture(generation)) {
                        showMimirMessage(context, "No input received · mapping unchanged", MimirMessageClass.Guidance)
                    }
                }
            }
        }
    }
    fun openControllerShortcutMapping(rowId: String) {
        val action = controllerShortcutActionForRowId(rowId) ?: return
        ControllerMappingStore.open(initialFocusAction = action)
        authority.pushScope(ControllerMappingScope)
    }
    val resetControllerMappingDraft = {
        ControllerMappingStore.resetDraft()
        showMimirMessage(context, "Factory mapping ready · Apply to save", MimirMessageClass.Guidance)
    }
    val applyControllerMapping = {
        when (val result = ControllerMappingStore.apply()) {
            ControllerMappingApplyResult.Applied -> {
                val applied = ControllerMappingStore.snapshot.value
                if (applied.hasAppliedBinding(ControllerAction.GLOBAL_QUICK_LAUNCH)) {
                    ControllerShortcutStore.remove(context, ControllerShortcutAction.QUICK_LAUNCH)
                }
                if (applied.hasAppliedBinding(ControllerAction.GLOBAL_UTILITY)) {
                    ControllerShortcutStore.remove(context, ControllerShortcutAction.UTILITY)
                }
                if (applied.hasAppliedBinding(ControllerAction.GLOBAL_TWEAKER)) {
                    ControllerShortcutStore.remove(context, ControllerShortcutAction.TWEAKER)
                }
                leaveControllerMappingScopes()
                showMimirMessage(context, "Controller mapping applied", MimirMessageClass.Confirmation)
            }
            is ControllerMappingApplyResult.MissingRequiredAction -> {
                authority.requestFocus(FocusKey(ControllerMappingActionRowPrefix + result.action.name))
                showMimirMessage(
                    context,
                    "${controllerActionTitle(result.action)} must be mapped",
                )
            }
        }
    }

    val onRowActivate: (String) -> Unit = onRowActivate@ { rowId ->
        // Every Settings row CONFIRM lands here, and the branch below is a long `when` whose misses are
        // silent -- an unmatched id, or one canonicalSettingsDetailRowId maps to null, simply returns and
        // the row reads as dead. This line is the only visibility on that path, and it is what separated
        // "the row never fired" from "the action did nothing" when `controls/keyboard` went inert on the
        // Thor (2026-09-16). Same shape and tag convention as MainActivity's MimirNavProbe lines.
        Log.i("MimirNavProbe", "onRowActivate rowId=$rowId canonical=${canonicalSettingsDetailRowId(rowId)}")
        val canonicalRowId = canonicalSettingsDetailRowId(rowId) ?: return@onRowActivate
        when {
            canonicalRowId == "library/primary" -> launchSourcePicker(LibrarySourceRole.PRIMARY)
            canonicalRowId == "library/add" -> launchSourcePicker(LibrarySourceRole.ADDITIONAL)
            canonicalRowId == "library/rescanAll" || canonicalRowId == "library/rescan" ->
                // Cert E3: with "All files access" off nothing here can be read, so say so instead of running a pass.
                if (library.readModel?.copy(allFilesAccess = read.allFilesAccess)?.everyFolderLockedByFileAccess == true) {
                    showMimirMessage(context, LibraryFilesAccessLostCopy, MimirMessageClass.Guidance, long = true)
                } else {
                    library.rescanAll { result ->
                        showMimirMessage(context, sourceReconcileFeedback(result), MimirMessageClass.Confirmation, long = true)
                    }
                }
            canonicalRowId == LibraryAllowFilesRowId ->
                if (openAllFilesAccessScreen(context)) {
                    Unit
                } else {
                    showMimirMessage(context, "File access settings are unavailable on this device", MimirMessageClass.Guidance)
                }
            canonicalRowId == LibrarySourceRemovalCancelRowId -> cancelSourceRemoval()
            canonicalRowId == LibrarySourceRemovalConfirmRowId -> confirmSourceRemoval()
            canonicalRowId.startsWith("library/source/remove/") -> {
                library.readModel?.sources
                    ?.firstOrNull { !it.isPrimary && librarySourceRemovalRowId(it.rootUri) == canonicalRowId }
                    ?.let(::openSourceRemoval)
            }
            canonicalRowId == "migration/import" || canonicalRowId == "library/backup/import" -> migration.openPicker()
            canonicalRowId == "library/backup/export" -> migration.exportBackup()
            canonicalRowId == "library/recovery/export" -> migration.exportRecoveryArchive()
            canonicalRowId == "library/recovery/restore" -> migration.openRecoveryRestorePicker()
            canonicalRowId == "profile/theme" || canonicalRowId == "appearance/theme" -> openThemePicker()
            canonicalRowId == "appearance/layout" -> AppearanceOverrideStore.setViewportMode(
                context,
                if (MimirViewportSettings.mode == MimirViewportMode.AUTOMATIC) {
                    MimirViewportMode.COMPACT
                } else {
                    MimirViewportMode.AUTOMATIC
                },
            )
            // Cycles Automatic -> English -> Español (Latinoamérica) like the trackpad rows. The store persists it and applies it:
            // Android's per-app language from API 33, a recreate of Main before that (MimirLocale).
            canonicalRowId == LanguageRowId -> MimirLanguageStore.set(context, MimirLanguageSettings.language.next())
            canonicalRowId == "appearance/background" -> openBackgroundPicker()
            canonicalRowId == "appearance/mbutton" -> openMimirButtonPicker()
            canonicalRowId == "appearance/accent" -> openAccentPicker()
            canonicalRowId == "appearance/focusring" -> openFocusDial()
            canonicalRowId == "appearance/backdrop" -> openBackdropPicker()
            canonicalRowId == "appearance/enrichment/screenscraper" -> artwork.state.openArtworkProfile()
            // The row reads "Game music is downloading." with a Busy chip while the music batch holds the shared
            // gate; a press in that window says the same thing rather than starting nothing.
            canonicalRowId == "appearance/enrichment/screenscraper/download" ->
                if (artworkEntryMayStart(ArtworkOperationGate.process.active.value)) {
                    artwork.state.downloadArtworkSettings()
                } else {
                    showMimirMessage(context, GameMusicDownloadingCopy, MimirMessageClass.Guidance)
                }
            canonicalRowId == "appearance/enrichment/steamgriddb" -> state.steamGridDbKeyEditing = true
            canonicalRowId == "profile/name" -> state.profileNameEditing = true
            canonicalRowId == "profile/retroachievements" ->
                retroAchievementsConnect.open(RetroAchievementsCredentialStore.connectedUser.value)
            canonicalRowId == "profile/retroachievements/disconnect" -> onDisconnectRetroAchievements()
            settingsExternalUrlFor(canonicalRowId) != null -> runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(settingsExternalUrlFor(canonicalRowId)))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            canonicalRowId == "controls/utility" -> core.brightness.open()
            canonicalRowId == TrackpadAccessRowId -> MimirTrackpadRuntime.openAccessibilitySettings(context)
            canonicalRowId == TrackpadScrollRowId ->
                MimirTrackpadSettings.setNaturalScroll(context, !MimirTrackpadSettings.naturalScroll.value)
            canonicalRowId == TrackpadSpeedRowId -> MimirTrackpadSettings.cycleSpeedStep(context)
            canonicalRowId == TrackpadPointerSizeRowId -> MimirTrackpadSettings.cyclePointerSize(context)
            canonicalRowId == TrackpadPointerShapeRowId -> MimirTrackpadSettings.cyclePointerShape(context)
            canonicalRowId == TrackpadPointerColorRowId -> MimirTrackpadSettings.cyclePointerColor(context)
            canonicalRowId == "controls/keyboard" -> {
                // W2f: with a live READY bridge the row is a switch (the readback is its state); without one
                // it is still the picker row.
                val keyboardRow = mimirKeyboardRow(
                    read.keyboardPickerEntry,
                    bridgeReady = read.androidAppSwitchingStatus == PrivilegedUtilityStatus.READY,
                )
                if (keyboardRow is MimirKeyboardRow.Switch) {
                    MimirKeyboardDefaultRuntime.setSwitch(context, on = !keyboardRow.on)
                } else {
                    performMimirKeyboardPickerAction(context, read.keyboardPickerEntry)
                }
            }
            canonicalRowId == ControllerShortcutQuickLaunchRowId ->
                openControllerShortcutMapping(canonicalRowId)
            canonicalRowId == ControllerShortcutUtilityRowId ->
                openControllerShortcutMapping(canonicalRowId)
            canonicalRowId == ControllerShortcutTweakerRowId ->
                openControllerShortcutMapping(canonicalRowId)
            canonicalRowId == ControllerShortcutResetRowId -> {
                ControllerShortcutStore.reset(context)
                ControllerMappingStore.clearGlobalShortcutBindings()
                showMimirMessage(context, "Controller shortcuts reset", MimirMessageClass.Confirmation)
            }
            canonicalRowId == ControllerMappingLauncherRowId -> openControllerMapping()
            canonicalRowId == "controls/special/overlay" ->
                core.companionOverlayController?.requestOverlayPermission()
            canonicalRowId == "controls/special/usage" || canonicalRowId == "emulators/launchCheck/usage" ->
                core.launchForegroundObserver?.requestUsageAccess()
            canonicalRowId == "controls/special/tabs" -> {
                core.brightness.requestAndroidAppSwitchingAccess(
                    read.androidAppSwitchingStatus,
                    onComplete = read.refreshSpecialAccess,
                )
            }
            canonicalRowId == EmulatorDefinitionsRowId -> EmulatorDefinitionsRuntime.onRowPressed()
            canonicalRowId == FirstSelectableEmulatorRowId ->
                firstSelectableEmulatorAssignment(read.emulatorAssignments.orEmpty())
                    ?.platformId
                    ?.let(::PlatformId)
                    ?.let(openPlatformPicker)
            canonicalRowId.startsWith("emulators/change/") ->
                openPlatformPicker(PlatformId(canonicalRowId.removePrefix("emulators/change/")))
            canonicalRowId.startsWith("library/hidden/") ->
                library.restoreHidden(GameId(canonicalRowId.removePrefix("library/hidden/")))
            // Settings › Music: every row that takes a press, as the table in MusicSettingsWiring.kt decides it. The Behaviour
            // sub-page's seven switches route through the same table, their ids unchanged.
            canonicalRowId.startsWith("music/") -> musicRowCommand(
                canonicalRowId = canonicalRowId,
                gameMusic = artwork.state.gameMusicState.value,
            )?.let(::runMusicRowCommand)
            else -> Unit
        }
    }

    return MimirSettingsCoordinator(
        uiState = state,
        platformPickerChoices = platformPickerChoices,
        backdropMode = backdropMode,
        onRescanFolder = onRescanFolder,
        onRowActivate = onRowActivate,
        openThemePicker = openThemePicker,
        closeThemePicker = closeThemePicker,
        commitTheme = commitTheme,
        setProfileNameEditing = { state.profileNameEditing = it },
        setSteamGridDbKeyEditing = { state.steamGridDbKeyEditing = it },
        retroAchievementsConnect = retroAchievementsConnect,
        closePlatformPicker = closePlatformPicker,
        choosePlatformDefault = choosePlatformDefault,
        closeBackdropPicker = closeBackdropPicker,
        chooseBackdrop = chooseBackdrop,
        closeAccentPicker = closeAccentPicker,
        commitAccent = commitAccent,
        commitAccentTemp = commitAccentTemp,
        closeFocusDial = closeFocusDial,
        stepFocusDial = stepFocusDial,
        commitFocusRing = commitFocusRing,
        backgroundPickerFocus = backgroundPickerFocus,
        applyBackgroundPickerTransition = applyBackgroundPickerTransition,
        mimirButtonPickerFocus = mimirButtonPickerFocus,
        applyMimirButtonPickerTransition = applyMimirButtonPickerTransition,
        closeMimirButtonPicker = closeMimirButtonPicker,
        appearanceImportLanded = appearanceImportLanded,
        appearanceImportCancelled = appearanceImportCancelled,
        cancelSourceRemoval = ::cancelSourceRemoval,
        closeControllerMapping = closeControllerMapping,
        discardControllerMapping = discardControllerMapping,
        continueControllerMapping = continueControllerMapping,
        beginControllerMappingCapture = beginControllerMappingCapture,
        resetControllerMappingDraft = resetControllerMappingDraft,
        applyControllerMapping = applyControllerMapping,
        music = read.music,
        onRowSecondary = onRowSecondary,
        onRowTertiary = onRowTertiary,
        closeMusicSourcePicker = closeMusicSourcePicker,
        chooseMusicSource = chooseMusicSource,
        chooseMusicOrder = chooseMusicOrder,
        chooseMusicHours = chooseMusicHours,
        chooseMusicLaunchSound = chooseMusicLaunchSound,
        stepMusicVolumeDial = stepMusicVolumeDial,
        commitMusicVolumeDial = commitMusicVolumeDial,
        closeMusicVolumeDial = closeMusicVolumeDial,
        closeEqualizerEditor = closeEqualizerEditor,
    )
}

/** Add Games restores/replaces the primary first when it is absent; otherwise it adds a library root. */
internal fun libraryAddGamesSourceRole(sources: List<SourceRow>): LibrarySourceRole {
    val primary = sources.firstOrNull(SourceRow::isPrimary)
    return if (primary == null || !primary.available) {
        LibrarySourceRole.PRIMARY
    } else {
        LibrarySourceRole.ADDITIONAL
    }
}

internal enum class LibraryFolderPickerRoute { BUSY, PRIMARY, ADDITIONAL }

internal fun libraryAddGamesPickerRoute(
    sources: List<SourceRow>,
    sourceOperationRunning: Boolean,
): LibraryFolderPickerRoute = when {
    sourceOperationRunning -> LibraryFolderPickerRoute.BUSY
    libraryAddGamesSourceRole(sources) == LibrarySourceRole.PRIMARY -> LibraryFolderPickerRoute.PRIMARY
    else -> LibraryFolderPickerRoute.ADDITIONAL
}

internal fun retroAchievementsDisconnectFailureMessage(cleared: Boolean): String? =
    if (cleared) {
        null
    } else {
        "RetroAchievements is off for this session, but Mimir couldn’t finish forgetting the key. " +
            "Retry before restart."
    }

internal const val GameTdbAttributionUrl = "https://www.gametdb.com/"

/** Exact allowlist: Settings rows cannot turn arbitrary data into a browser intent. */
internal fun settingsExternalUrlFor(rowId: String): String? = when (rowId) {
    "about/credits/gametdb" -> GameTdbAttributionUrl
    // GPL-3.0 section 6: the Visit row under Credits & licenses opens the source code. Its address is a placeholder pending confirmation.
    AboutLegalSourceRowId -> MimirSourceUrl
    else -> null
}

// Two states only  — see the twin mapping in MimirApp.kt.
private fun GamelistBackdropMode.toMimirBackdropValue(): String = when (this) {
    GamelistBackdropMode.OFF -> BackdropValueOff
    else -> BackdropValueMedia
}

private fun String.toMimirBackdropMode(): GamelistBackdropMode = when (this) {
    BackdropValueOff -> GamelistBackdropMode.OFF
    else -> GamelistBackdropMode.BIFROST
}
