package com.gruning.mimir.rewrite

import android.util.Log
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.feature.files.FileBrowserController
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import com.gruning.mimir.rewrite.core.ui.mimirAchievementCardOffersProfileSetup
import com.gruning.mimir.rewrite.feature.settings.MimirButtonPickerState
import com.gruning.mimir.rewrite.feature.settings.ThemePickerScope
import com.gruning.mimir.rewrite.feature.settings.dispatchAccentPicker
import com.gruning.mimir.rewrite.feature.settings.dispatchBackdropPicker
import com.gruning.mimir.rewrite.feature.settings.dispatchBackgroundPicker
import com.gruning.mimir.rewrite.feature.settings.dispatchColorDial
import com.gruning.mimir.rewrite.feature.settings.dispatchMimirButtonPicker
import com.gruning.mimir.rewrite.feature.settings.dispatchControllerMapping
import com.gruning.mimir.rewrite.feature.settings.MusicPickerMenu
import com.gruning.mimir.rewrite.feature.settings.dispatchMusicHoursPicker
import com.gruning.mimir.rewrite.feature.settings.dispatchMusicLaunchSoundPicker
import com.gruning.mimir.rewrite.feature.settings.dispatchMusicOrderPicker
import com.gruning.mimir.rewrite.feature.settings.dispatchMusicSourcePicker
import com.gruning.mimir.rewrite.feature.settings.dispatchMusicVolumeDial
import com.gruning.mimir.rewrite.feature.settings.dispatchPlatformEmulatorPicker
import com.gruning.mimir.rewrite.feature.settings.dispatchThemeGallery

internal enum class MimirOverlayKind {
    RETURN_COVER,
    /** Mimir's own folder browser, which exists so add-games never hands off to the system picker. */
    FOLDER_BROWSER,
    COMPANION_PERMISSION,
    MIGRATION,
    ARTWORK_GET,
    ARTWORK_FETCH,
    ARTWORK_CONNECT,
    PROFILE_NAME,
    STEAMGRIDDB_KEY,
    RETROACHIEVEMENTS_KEY,
    GAME_TITLE_EDIT,
    CONTROLLER_MAPPING,
    CONTROLLER_SHORTCUT_CAPTURE,
    HOME_QUICK_LAUNCH_PICKER,
    HOME_QUICK_LAUNCH,
    HOME_TILE_MENU,
    HOME_TOME_LIBRARY,
    LIBRARY_PLATFORM_TILE_MENU,
    GAMELIST_ACHIEVEMENTS,
    HOME_OPEN_WITH,

    /** The disc / version menu over Home's launch card . */
    HOME_LAUNCH_CHOICE,
    HOME_LAUNCH_CARD,
    PLATFORM_PICKER,
    BACKDROP_PICKER,
    THEME_PICKER,

    /** Settings › Appearance › Background: the player's image behind the shelves (concept A, 2026-09-30). */
    BACKGROUND_PICKER,

    /** Settings › Appearance › Mimir button: the player's mark on the ᛗ (concept A, 2026-09-30). */
    MBUTTON_PICKER,
    ACCENT_PICKER,
    FOCUS_RING_DIAL,

    /** Settings › Music › Source, on the shared select shell. */
    MUSIC_SOURCE_PICKER,

    /** Settings › Music's volume dial, for Volume and Launch sound volume (the Focus-highlight dial's shell). */
    MUSIC_VOLUME_DIAL,

    /** Settings › Music › Equalizer: the ten-band editor, a hard-modal glass sheet with no exit . */
    EQUALIZER_EDITOR,
    BRIGHTNESS,
}

internal data class MimirOverlaySnapshot(
    val returnCover: Boolean = false,
    val companionPermission: Boolean = false,
    val migration: Boolean = false,
    val artworkGet: Boolean = false,
    val artworkFetch: Boolean = false,
    val artworkConnect: Boolean = false,
    val profileName: Boolean = false,
    val steamGridDbKey: Boolean = false,
    val retroAchievementsKey: Boolean = false,
    val gameTitleEdit: Boolean = false,
    val controllerMapping: Boolean = false,
    val controllerShortcutCapture: Boolean = false,
    val folderBrowser: Boolean = false,
    val homeQuickLaunchPicker: Boolean = false,
    val homeQuickLaunch: Boolean = false,
    val homeTileMenu: Boolean = false,
    val homeTomeLibrary: Boolean = false,
    val platformTileMenu: Boolean = false,
    val gamelistAchievements: Boolean = false,
    val homeOpenWith: Boolean = false,
    val homeLaunchChoice: Boolean = false,
    val homeLaunchCard: Boolean = false,
    val platformPicker: Boolean = false,
    val backdropPicker: Boolean = false,
    val themePicker: Boolean = false,
    val backgroundPicker: Boolean = false,
    val mimirButtonPicker: Boolean = false,
    val accentPicker: Boolean = false,
    val focusRingDial: Boolean = false,
    val musicSourcePicker: Boolean = false,
    val musicVolumeDial: Boolean = false,
    val equalizerEditor: Boolean = false,
    val brightness: Boolean = false,
)

internal data class MimirOverlayStack(val entries: List<MimirOverlayKind>) {
    val top: MimirOverlayKind? get() = entries.firstOrNull()

    /**
     * The overlay whose pixels and scene chrome the host keeps presenting. It is [top], with one exception: the
     * Quick Launch panel and its picker draw in a band of their own ABOVE the direct-overlay host, so when they sit on
     * a kept surface ([mimirOverlayStaysUnderQuickLaunch]) that surface is still what the host presents beneath them.
     *
     * Presenting the panel instead would drop the direct host's target: the folder browser's composition would be
     * disposed (its own `DisposableEffect` closes the browser in `FileBrowserStore`), and the scene's glass would
     * clear behind a still-translucent modal. Closing the panel would then return to nothing, not to the folder.
     */
    val presented: MimirOverlayKind?
        get() {
            val first = entries.firstOrNull() ?: return null
            if (first != MimirOverlayKind.HOME_QUICK_LAUNCH && first != MimirOverlayKind.HOME_QUICK_LAUNCH_PICKER) {
                return first
            }
            return entries.firstOrNull(::mimirOverlayStaysUnderQuickLaunch) ?: first
        }
}

internal data class HomeQuickLaunchPickerOwnershipToken(
    /** Process-local owner identity; epochs alone restart from one for a genuine replacement owner. */
    internal val ownerIdentity: Any,
    val epoch: Long,
    val invalidationEpoch: Long,
)

/** Temporary release-debug probe for the RP5 picker ownership/readiness boundary. */
internal fun traceHomeQuickLaunchPicker(event: String) {
    // Local JVM tests expose the generated DEBUG flag but Android's Log is a throwing stub there.
    // The probe remains Android-debug-only without making ownership tests depend on a logging facade.
    if (BuildConfig.DEBUG) runCatching { Log.d("MimirQuickLaunch", event) }
}

/**
 * Bridges the logical overlay stack and the picker's first settled frame without becoming a second
 * navigation authority. A newly mounted picker starts quarantined; only its current mount may admit
 * input, and stale disposal/readiness callbacks from an outgoing pixel epoch are ignored.
 */
internal class HomeQuickLaunchPickerOwnership {
    private val ownerIdentity = Any()
    private var issuedEpoch = 0L
    private var invalidationEpoch = 0L
    private var mounted: HomeQuickLaunchPickerOwnershipToken? = null
    private var ready = false

    @Synchronized
    fun mount(): HomeQuickLaunchPickerOwnershipToken {
        check(issuedEpoch < Long.MAX_VALUE) { "Quick Launch picker ownership epoch exhausted" }
        issuedEpoch += 1L
        return HomeQuickLaunchPickerOwnershipToken(ownerIdentity, issuedEpoch, invalidationEpoch).also { token ->
            mounted = token
            ready = false
            traceHomeQuickLaunchPicker("owner mount epoch=${token.epoch} invalidation=${token.invalidationEpoch}")
        }
    }

    @Synchronized
    fun setReady(token: HomeQuickLaunchPickerOwnershipToken, value: Boolean): Boolean {
        if (token.ownerIdentity !== ownerIdentity || mounted != token) {
            traceHomeQuickLaunchPicker("owner ready rejected epoch=${token.epoch} value=$value")
            return false
        }
        ready = value
        traceHomeQuickLaunchPicker("owner ready accepted epoch=${token.epoch} value=$value")
        return true
    }

    @Synchronized
    fun unmount(token: HomeQuickLaunchPickerOwnershipToken): Boolean {
        if (token.ownerIdentity !== ownerIdentity || mounted != token) {
            traceHomeQuickLaunchPicker("owner unmount rejected epoch=${token.epoch}")
            return false
        }
        mounted = null
        ready = false
        traceHomeQuickLaunchPicker("owner unmount accepted epoch=${token.epoch}")
        return true
    }

    @Synchronized
    fun invalidate() {
        check(invalidationEpoch < Long.MAX_VALUE) { "Quick Launch picker invalidation epoch exhausted" }
        invalidationEpoch += 1L
        mounted = null
        ready = false
    }

    @Synchronized
    fun invalidationEpoch(): Long = invalidationEpoch

    /**
     * Logical input ownership begins when the live picker composition mounts. Presentation readiness is
     * transition telemetry, not a second controller gate: a missed device callback must never leave a
     * visible modal permanently swallowing D-pad input.
     */
    @Synchronized
    fun isMounted(): Boolean = mounted != null

    @Synchronized
    fun isReady(): Boolean = mounted != null && ready
}

/**
 * Mounting happens during composition, while the picker scope and its virtual tiles are registered by
 * the child effect.  A controller packet in that tiny interval must be absorbed by the modal instead
 * of running the reducer against the shelf's stale focus.  Presentation telemetry deliberately does
 * not participate here: a missed visual callback must not make an already-mounted picker inert.
 */
internal fun HomeQuickLaunchPickerOwnership.admitsControllerInput(
    authority: FocusAuthority,
): Boolean = isMounted() && authority.state.value.scope == HomeQuickLaunchPickerScope

internal fun relinquishHomeQuickLaunchPickerOwnership(
    authority: FocusAuthority,
    ownership: HomeQuickLaunchPickerOwnership,
) {
    ownership.invalidate()
    authority.discardScope(HomeQuickLaunchPickerScope)
}

internal fun resolveMimirOverlayStack(snapshot: MimirOverlaySnapshot): MimirOverlayStack = MimirOverlayStack(
    buildList {
        if (snapshot.returnCover) add(MimirOverlayKind.RETURN_COVER)
        if (snapshot.companionPermission) add(MimirOverlayKind.COMPANION_PERMISSION)
        if (snapshot.migration) add(MimirOverlayKind.MIGRATION)
        // Credential entry (ScreenScraper's id and password on the in-app keyboard): it ranks with the text-entry modals
        // below, above the Quick Launch panel, so the panel never opens over a keyboard it would share a scope stack with.
        if (snapshot.artworkConnect) add(MimirOverlayKind.ARTWORK_CONNECT)
        if (snapshot.profileName) add(MimirOverlayKind.PROFILE_NAME)
        if (snapshot.steamGridDbKey) add(MimirOverlayKind.STEAMGRIDDB_KEY)
        if (snapshot.retroAchievementsKey) add(MimirOverlayKind.RETROACHIEVEMENTS_KEY)
        if (snapshot.gameTitleEdit) add(MimirOverlayKind.GAME_TITLE_EDIT)
        if (snapshot.controllerShortcutCapture) add(MimirOverlayKind.CONTROLLER_SHORTCUT_CAPTURE)
        if (snapshot.controllerMapping) add(MimirOverlayKind.CONTROLLER_MAPPING)
        if (snapshot.homeQuickLaunchPicker) add(MimirOverlayKind.HOME_QUICK_LAUNCH_PICKER)
        if (snapshot.homeQuickLaunch) add(MimirOverlayKind.HOME_QUICK_LAUNCH)
        // The kept surfaces (mimirOverlayStaysUnderQuickLaunch), directly BELOW the Quick Launch panel: the panel opens
        // over a folder browser or the Get art and fetch cards without dismissing them . Get art
        // and the fetch card used to sit above the text-entry block as well, so the panel could not rank above them without
        // ranking above that block too; they now rank under the block. No flow opens one of them over a text-entry modal (a
        // modal covers the row that would), and the one that arrives on its own, the new-games enrich offer, is held while
        // anything else is up (newGameEnrichOfferCovered).
        if (snapshot.artworkGet) add(MimirOverlayKind.ARTWORK_GET)
        if (snapshot.artworkFetch) add(MimirOverlayKind.ARTWORK_FETCH)
        if (snapshot.folderBrowser) add(MimirOverlayKind.FOLDER_BROWSER)
        if (snapshot.homeTileMenu) add(MimirOverlayKind.HOME_TILE_MENU)
        if (snapshot.homeTomeLibrary) add(MimirOverlayKind.HOME_TOME_LIBRARY)
        if (snapshot.platformTileMenu) add(MimirOverlayKind.LIBRARY_PLATFORM_TILE_MENU)
        if (snapshot.gamelistAchievements) add(MimirOverlayKind.GAMELIST_ACHIEVEMENTS)
        if (snapshot.homeOpenWith) add(MimirOverlayKind.HOME_OPEN_WITH)
        if (snapshot.homeLaunchChoice) add(MimirOverlayKind.HOME_LAUNCH_CHOICE)
        if (snapshot.homeLaunchCard) add(MimirOverlayKind.HOME_LAUNCH_CARD)
        if (snapshot.platformPicker) add(MimirOverlayKind.PLATFORM_PICKER)
        if (snapshot.backdropPicker) add(MimirOverlayKind.BACKDROP_PICKER)
        if (snapshot.themePicker) add(MimirOverlayKind.THEME_PICKER)
        // Both appearance-image pickers rank BELOW FOLDER_BROWSER, so the image browser covers them on
        // + Add image and they come back, state intact, the moment it closes.
        if (snapshot.backgroundPicker) add(MimirOverlayKind.BACKGROUND_PICKER)
        if (snapshot.mimirButtonPicker) add(MimirOverlayKind.MBUTTON_PICKER)
        if (snapshot.accentPicker) add(MimirOverlayKind.ACCENT_PICKER)
        if (snapshot.focusRingDial) add(MimirOverlayKind.FOCUS_RING_DIAL)
        // The two Music overlays rank with the Settings pickers beside them: above Utility, below everything
        // that holds a place (2026-09-30).
        if (snapshot.musicSourcePicker) add(MimirOverlayKind.MUSIC_SOURCE_PICKER)
        if (snapshot.musicVolumeDial) add(MimirOverlayKind.MUSIC_VOLUME_DIAL)
        if (snapshot.equalizerEditor) add(MimirOverlayKind.EQUALIZER_EDITOR)
        if (snapshot.brightness) add(MimirOverlayKind.BRIGHTNESS)
    },
)

/** The sole app-shell owner of modal precedence and controller dispatch. */
internal class MimirOverlayCoordinator(
    private val snapshot: () -> MimirOverlaySnapshot,
    private val dispatchTop: (MimirOverlayKind, ControllerAction) -> Boolean,
    internal val onHomeTomeLibraryAction: (ControllerAction) -> Unit = {},
    /** The achievement card's NEEDS SETUP action (F3), shared by A on the pad and a tap on the card. */
    internal val onOpenRetroAchievementsSettings: () -> Unit = {},
    internal val homeQuickLaunchPickerOwnership: HomeQuickLaunchPickerOwnership =
        HomeQuickLaunchPickerOwnership(),
    /**
     * Close [kind] ENTIRELY in one step and report whether it did, for an overlay whose CANCEL is a step back rather than
     * a close (the folder browser: one CANCEL is "up one directory"). The M-button commands that leave the screen
     * (Home, Settings, Utility, Tweaker) sweep with this so a browser at depth is gone when the sweep ends, not merely
     * one folder shallower and still on top of the destination. Returns false for every kind that has no such step, and
     * the sweep sends those CANCEL as before.
     */
    private val closeEntirely: (MimirOverlayKind) -> Boolean = { false },
) {
    val stack: MimirOverlayStack get() = resolveMimirOverlayStack(snapshot())
    val top: MimirOverlayKind? get() = stack.top

    /** What the host presents (pixels and scene chrome); see [MimirOverlayStack.presented]. */
    val presented: MimirOverlayKind? get() = stack.presented

    /** Null means no app-shell overlay is active; otherwise the top overlay consumed the action. */
    fun dispatch(action: ControllerAction): Boolean? = top?.let { dispatchTop(it, action) }

    /**
     * One bounded top-down CANCEL sweep through each overlay's own ruled Back path. Stops at a
     * structural RETURN_COVER, at [stopAt] (a surface the caller is recovering, not dismissing),
     * at any overlay [keep] names, and on no-progress (a modal that refuses CANCEL — mid-validation,
     * migration). Shared by the three M-command branches, which previously carried three drifting
     * copies of this loop.
     *
     * [keep] is checked BEFORE the CANCEL, never after it, because a kept overlay must not receive the
     * press at all: CANCEL is what the folder browser reads as "up one directory" (and, inside a search,
     * as "close the keyboard", then "close the search"), so one sweep step is one player-visible move.
     * The loop's own no-progress break cannot stand in for this: it only runs after the CANCEL has landed.
     *
     * Every overlay NOT kept is dismissed, and a kind with a one-step full close ([closeEntirely], the folder browser)
     * is closed that way instead of being sent CANCEL, so the callers that leave the screen never stop one step short
     * (walk-32k: a browser at depth absorbed the one CANCEL as "up", the loop broke with it still on top, and Home,
     * Utility or Settings landed underneath it). Get art and the fetch card need no such step: their CANCEL already
     * closes them whenever they can close, and a lookup or fetch still in flight declines it either way.
     */
    fun dismissConflictingModals(
        stopAt: MimirOverlayKind? = null,
        keep: (MimirOverlayKind) -> Boolean = { false },
    ) {
        var remaining = MimirOverlayKind.entries.size
        while (remaining-- > 0) {
            val current = top ?: break
            if (current == MimirOverlayKind.RETURN_COVER || current == stopAt || keep(current)) break
            if (!closeEntirely(current)) dispatch(ControllerAction.CANCEL)
            if (top == current) break
        }
    }
}

/**
 * Whether [kind] sits ABOVE `HOME_QUICK_LAUNCH` in [resolveMimirOverlayStack]'s build order — i.e.
 * would bury the panel if it survived the pre-open sweep, so the press is refused while it is up.
 * Exhaustive on purpose: a new overlay kind must decide its rank against the panel explicitly (and
 * [mimirOverlayStaysUnderQuickLaunch] decides the other half).
 *
 * The sweep still gives every kind here one CANCEL through its own Back path before the press refuses,
 * so a modal that yields to Back (a profile name, a title edit, the ScreenScraper sign-in) is dismissed and
 * the panel opens; the press is refused only for the ones that decline it (validation in flight, a migration,
 * the controller mapping's exit confirmation) and for the structural return cover. That the sweep dismisses
 * the text-entry and credential-entry modals rather than refusing without touching them is the current behavior.
 */
internal fun mimirOverlayOutranksQuickLaunch(kind: MimirOverlayKind): Boolean = when (kind) {
    MimirOverlayKind.RETURN_COVER,
    MimirOverlayKind.COMPANION_PERMISSION,
    MimirOverlayKind.MIGRATION,
    MimirOverlayKind.ARTWORK_CONNECT,
    MimirOverlayKind.PROFILE_NAME,
    MimirOverlayKind.STEAMGRIDDB_KEY,
    MimirOverlayKind.RETROACHIEVEMENTS_KEY,
    MimirOverlayKind.GAME_TITLE_EDIT,
    MimirOverlayKind.CONTROLLER_SHORTCUT_CAPTURE,
    MimirOverlayKind.CONTROLLER_MAPPING,
    MimirOverlayKind.HOME_QUICK_LAUNCH_PICKER,
    -> true
    MimirOverlayKind.FOLDER_BROWSER,
    MimirOverlayKind.ARTWORK_GET,
    MimirOverlayKind.ARTWORK_FETCH,
    MimirOverlayKind.HOME_QUICK_LAUNCH,
    MimirOverlayKind.HOME_TILE_MENU,
    MimirOverlayKind.HOME_TOME_LIBRARY,
    MimirOverlayKind.LIBRARY_PLATFORM_TILE_MENU,
    MimirOverlayKind.GAMELIST_ACHIEVEMENTS,
    MimirOverlayKind.HOME_OPEN_WITH,
    MimirOverlayKind.HOME_LAUNCH_CHOICE,
    MimirOverlayKind.HOME_LAUNCH_CARD,
    MimirOverlayKind.PLATFORM_PICKER,
    MimirOverlayKind.BACKDROP_PICKER,
    MimirOverlayKind.THEME_PICKER,
    MimirOverlayKind.BACKGROUND_PICKER,
    MimirOverlayKind.MBUTTON_PICKER,
    MimirOverlayKind.ACCENT_PICKER,
    MimirOverlayKind.FOCUS_RING_DIAL,
    MimirOverlayKind.MUSIC_SOURCE_PICKER,
    MimirOverlayKind.MUSIC_VOLUME_DIAL,
    MimirOverlayKind.EQUALIZER_EDITOR,
    MimirOverlayKind.BRIGHTNESS,
    -> false
}

/**
 * Whether [kind] is a surface the Quick Launch panel opens OVER and leaves alone ( the standing ruling of 2026-08-11 is that Quick Launch opens IN PLACE and
 * closing it returns to where the player was). The folder browser and the Get art and fetch cards qualify because they
 * hold a place the player cannot afford to lose: a folder several levels deep with a search under way, a fetch in flight.
 *
 * For a kept kind: the pre-open sweep never sends it CANCEL (the browser reads CANCEL as "up one directory", so a sweep
 * step is a player-visible move), the settle steps that would alter what lies beneath it are skipped, it keeps
 * presenting beneath the panel ([MimirOverlayStack.presented]), and it ranks directly below the panel in
 * [resolveMimirOverlayStack] so the panel takes the controller and the Back press.
 *
 * Exhaustive on purpose, like [mimirOverlayOutranksQuickLaunch]: a new overlay kind must say which side it is on. A kind
 * cannot be both; [MimirOverlayQuickLaunchRankTest] pins that.
 *
 * ARTWORK_CONNECT is NOT kept, though it is one of the artwork flows: it is credential entry (ScreenScraper's id and
 * password, typed on the in-app keyboard), and a keyboard that finished loading while the panel sat on top could push
 * its focus scope above the panel's and strand the pad (lead, 2026-09-30). It ranks above the panel with the text-entry
 * modals, so the press sweeps it like them.
 */
internal fun mimirOverlayStaysUnderQuickLaunch(kind: MimirOverlayKind): Boolean = when (kind) {
    MimirOverlayKind.FOLDER_BROWSER,
    MimirOverlayKind.ARTWORK_GET,
    MimirOverlayKind.ARTWORK_FETCH,
    -> true
    MimirOverlayKind.RETURN_COVER,
    MimirOverlayKind.COMPANION_PERMISSION,
    MimirOverlayKind.MIGRATION,
    MimirOverlayKind.ARTWORK_CONNECT,
    MimirOverlayKind.PROFILE_NAME,
    MimirOverlayKind.STEAMGRIDDB_KEY,
    MimirOverlayKind.RETROACHIEVEMENTS_KEY,
    MimirOverlayKind.GAME_TITLE_EDIT,
    MimirOverlayKind.CONTROLLER_SHORTCUT_CAPTURE,
    MimirOverlayKind.CONTROLLER_MAPPING,
    MimirOverlayKind.HOME_QUICK_LAUNCH_PICKER,
    MimirOverlayKind.HOME_QUICK_LAUNCH,
    MimirOverlayKind.HOME_TILE_MENU,
    MimirOverlayKind.HOME_TOME_LIBRARY,
    MimirOverlayKind.LIBRARY_PLATFORM_TILE_MENU,
    MimirOverlayKind.GAMELIST_ACHIEVEMENTS,
    MimirOverlayKind.HOME_OPEN_WITH,
    MimirOverlayKind.HOME_LAUNCH_CHOICE,
    MimirOverlayKind.HOME_LAUNCH_CARD,
    MimirOverlayKind.PLATFORM_PICKER,
    MimirOverlayKind.BACKDROP_PICKER,
    MimirOverlayKind.THEME_PICKER,
    MimirOverlayKind.BACKGROUND_PICKER,
    MimirOverlayKind.MBUTTON_PICKER,
    MimirOverlayKind.ACCENT_PICKER,
    MimirOverlayKind.FOCUS_RING_DIAL,
    // Plain dismissable Settings pickers (2026-09-30): the sweep sends them CANCEL and the panel opens.
    MimirOverlayKind.MUSIC_SOURCE_PICKER,
    MimirOverlayKind.MUSIC_VOLUME_DIAL,
    MimirOverlayKind.EQUALIZER_EDITOR,
    MimirOverlayKind.BRIGHTNESS,
    -> false
}

internal fun createMimirOverlayCoordinator(
    core: MimirAppCoreCoordinator,
    settings: MimirSettingsCoordinator,
    migration: MimirMigrationCoordinator,
    artwork: MimirArtworkCoordinator,
    achievements: MimirAchievementsCoordinator,
    // Every visibility flag is a LIVE read at dispatch time. Input arrives as plain main-thread
    // messages that can land between a state write and the next recomposition; a captured Boolean is
    // one frame stale there, which leaks controller actions around a modal's open/dismiss edges
    // (FUX-D review: a leaked NAV corrupted the rail's focus memory; a post-dismiss A still launched).
    returnCoverVisible: () -> Boolean,
    companionPermissionVisible: () -> Boolean,
    homeQuickLaunchOpen: () -> Boolean = { false },
    homeQuickLaunchSlots: () -> List<HomeQuickLaunchSlotUi> = { emptyList() },
    onHomeQuickLaunch: (String) -> Unit = {},
    onHomeQuickLaunchGeneral: () -> Unit = {},
    onHomeQuickLaunchEdit: (Int) -> Unit = {},
    onHomeQuickLaunchDismiss: () -> Unit = {},
    homeQuickLaunchPickerMode: () -> HomeQuickLaunchPickerMode? = { null },
    homeQuickLaunchPickerItems: () -> List<HomeQuickLaunchPickerItem> = { emptyList() },
    onHomeQuickLaunchPickerLaunch: (String) -> Unit = {},
    onHomeQuickLaunchPickerAssign: (Int, String) -> Unit = { _, _ -> },
    onHomeQuickLaunchPickerClear: (Int) -> Unit = {},
    onHomeQuickLaunchPickerDismiss: () -> Unit = {},
    homeTileMenuState: () -> HomeTileMenuState? = { null },
    onHomeTileMenuAction: (ControllerAction) -> Unit = {},
    homeTomeLibraryState: () -> HomeTomeLibraryState? = { null },
    onHomeTomeLibraryAction: (ControllerAction) -> Unit = {},
    platformTileMenuState: () -> PlatformArtMenuState? = { null },
    onPlatformTileMenuAction: (ControllerAction) -> Unit = {},
    homeLaunchCardOpen: () -> Boolean = { false },
    homeLaunchCardOpenWithOpen: () -> Boolean = { false },
    homeLaunchChoiceOpen: () -> Boolean = { false },
    onHomeLaunchCardAction: (ControllerAction) -> Boolean = { false },
    onHomeLaunchCardOpenWithAction: (ControllerAction) -> Boolean = { false },
    onHomeLaunchChoiceAction: (ControllerAction) -> Boolean = { false },
    /** the manual game-rename overlay. Touch-only typing; controller only dismisses. */
    gameTitleEditOpen: () -> Boolean = { false },
    onCloseGameTitleEdit: () -> Unit = {},
    // The app coordinator is recomposed; this ownership identity must outlive those coordinator
    // projections so a mounted picker can settle its own readiness token.
    homeQuickLaunchPickerOwnership: HomeQuickLaunchPickerOwnership = HomeQuickLaunchPickerOwnership(),
    /** The live browser, or null when it is closed. Supplied by whoever owns enumeration. */
    folderBrowser: () -> FileBrowserController? = { null },
    /** Product review 2026-09-28 (F3): the achievement card's NEEDS SETUP action. */
    onOpenRetroAchievementsSettings: () -> Unit = {},
    /**
     * The folder browser's own full close: the controller shut, its scope popped and the session cleared, held deletes
     * finalised exactly as backing out of the root does. A default rather than a call-site argument so the giant
     * `MimirAppCoordinator` composable gains nothing for it.
     */
    closeFolderBrowser: () -> Unit = FileBrowserStore::close,
): MimirOverlayCoordinator {
    val authority = core.focusAuthority
    return MimirOverlayCoordinator(
        snapshot = {
            MimirOverlaySnapshot(
                returnCover = returnCoverVisible(),
                companionPermission = companionPermissionVisible(),
                migration = migration.state != null,
                artworkGet = artwork.state.getArtUi != null,
                artworkFetch = artwork.fetchUi != null && !artwork.fetchEmbedded,
                artworkConnect = artwork.state.connectUi != null,
                profileName = settings.profileNameEditing,
                steamGridDbKey = settings.steamGridDbKeyEditing,
                retroAchievementsKey = settings.retroAchievementsKeyEditing,
                gameTitleEdit = gameTitleEditOpen(),
                controllerMapping = ControllerMappingStore.snapshot.value.open,
                controllerShortcutCapture = ControllerShortcutStore.snapshot.value.pendingAction != null,
                // The injected copy is a collectAsState value, which is what recomposition tracks but lags the store by a
                // frame; the store's own value is what is true now. After the sweep closes the browser entirely
                // (closeEntirely) only the second says so, and the sweep must not still see the browser on top.
                folderBrowser = folderBrowser() != null && FileBrowserStore.controller.value != null,
                homeQuickLaunchPicker = homeQuickLaunchPickerMode() != null,
                homeQuickLaunch = homeQuickLaunchOpen(),
                homeTileMenu = homeTileMenuState() != null,
                homeTomeLibrary = homeTomeLibraryState() != null,
                platformTileMenu = platformTileMenuState() != null,
                gamelistAchievements = achievements.state.isCardOpen,
                homeOpenWith = homeLaunchCardOpenWithOpen(),
                homeLaunchChoice = homeLaunchChoiceOpen(),
                homeLaunchCard = homeLaunchCardOpen(),
                platformPicker = settings.platformPickerFor != null,
                backdropPicker = settings.backdropPickerOpen,
                themePicker = settings.themePickerOpen,
                backgroundPicker = settings.backgroundPicker != null,
                mimirButtonPicker = settings.mimirButtonPicker != null,
                accentPicker = settings.accentPickerOpen,
                focusRingDial = settings.focusDialOpen,
                musicSourcePicker = settings.musicSourcePickerOpen,
                musicVolumeDial = settings.musicVolumeDialOpen,
                equalizerEditor = settings.equalizerEditor != null,
                brightness = core.brightness.isOpen &&
                    core.brightness.presentationTarget == AppBrightnessPresentationTarget.PRIMARY,
            )
        },
        dispatchTop = { kind, action ->
            when (kind) {
                MimirOverlayKind.RETURN_COVER -> true
                // FileBrowserController.open() is deliberately deferred to FileBrowserSurface's own
                // DisposableEffect rather than called eagerly here like every other overlay's scope-push
                // above (see FileBrowserStore.kt's header) -- so there is a real, one-frame-or-less gap
                // where folderBrowser() is already non-null (this overlay is already top of stack) but
                // FileBrowserController.dispatch still returns false (its own scope-mismatch guard)
                // because its FocusScope has not landed yet. Unabsorbed, MimirAppInputDispatch.kt's
                // `dispatch(action)?.let { return it }` would turn that false into a leaked press on
                // whatever destination sits underneath -- the exact hazard HOME_QUICK_LAUNCH_PICKER
                // below already solves the same way ("Absorb that pre-mount input rather than leaking
                // it to Home").
                //
                // **P0 2026-08-14 -- this used to absorb UNCONDITIONALLY, and that was a total input
                // lockup.** `dispatch` returns false for exactly one reason: this browser's FocusScope
                // is not the active one. That is transient while mounting and PERMANENT once something
                // else takes the scope while the browser stays mounted -- and every overlay that can
                // steal it (theme/accent/backdrop/focus-ring pickers) ranks BELOW FOLDER_BROWSER in
                // resolveMimirOverlayStack, so the browser stays top and keeps being asked. The
                // unconditional `true` made every controller action vanish for the rest of the process,
                // while every touch affordance in FileBrowserSurface sat behind the same scope guard --
                // both input paths dead at once, unrecoverable without killing the app.
                // hasOpened separates the two cases, so a
                // browser that is not really there can no longer absorb.
                MimirOverlayKind.FOLDER_BROWSER -> dispatchFolderBrowser(folderBrowser(), action)
                MimirOverlayKind.COMPANION_PERMISSION -> dispatchCompanionPermission(
                    authority = authority,
                    action = action,
                    onGrant = { core.companionOverlayController?.requestOverlayPermission() },
                    onDismiss = { CompanionRecentsState.hide() },
                )
                MimirOverlayKind.MIGRATION -> {
                    val state = migration.state
                    if (state == null) {
                        true
                    } else {
                        dispatchMigration(
                            authority = authority,
                            state = state,
                            action = action,
                            onConfirm = migration.confirm,
                            onClose = migration.close,
                        )
                    }
                }
                MimirOverlayKind.ARTWORK_GET -> dispatchGetArt(
                    authority = authority,
                    state = artwork.state.getArtUi,
                    provider = artwork.state.getArtProvider,
                    action = action,
                    onConnect = artwork.state::connectFromGetArt,
                    onAddSteamGridDbKey = {
                        artwork.state.addSteamGridDbKeyFromGetArt {
                            settings.setSteamGridDbKeyEditing(true)
                        }
                    },
                    onDismiss = artwork.state::dismissGetArt,
                )
                MimirOverlayKind.ARTWORK_FETCH -> dispatchArtworkFetch(
                    action = action,
                    fetchUi = artwork.fetchUi,
                    onCloseSummary = artwork.state::closeFetch,
                    onConfirmNewGames = artwork.state::answerNewGameEnrich,
                    onBack = artwork.state::cancelOrCloseFetch,
                    inputArmed = { NewGameEnrichPromptState.cardInputArmed() },
                    authority = authority,
                )
                MimirOverlayKind.ARTWORK_CONNECT -> swallowUntilBack(action, artwork.state::closeConnect)
                MimirOverlayKind.PROFILE_NAME -> swallowUntilBack(action) {
                    settings.setProfileNameEditing(false)
                }
                MimirOverlayKind.STEAMGRIDDB_KEY -> swallowUntilBack(action) {
                    settings.setSteamGridDbKeyEditing(false)
                }
                MimirOverlayKind.RETROACHIEVEMENTS_KEY -> dispatchRetroAchievementsConnect(
                    action = action,
                    validating = settings.retroAchievementsValidating,
                    onBack = { settings.retroAchievementsConnect.dismiss() },
                )
                MimirOverlayKind.GAME_TITLE_EDIT -> swallowUntilBack(action, onCloseGameTitleEdit)
                MimirOverlayKind.CONTROLLER_MAPPING -> {
                    val mapping = ControllerMappingStore.snapshot.value
                    dispatchControllerMapping(
                        authority = authority,
                        rows = mapping.toSettingsRows(),
                        action = action,
                        exitConfirmationVisible = mapping.exitConfirmationVisible,
                        onBeginCapture = settings.beginControllerMappingCapture,
                        onReset = settings.resetControllerMappingDraft,
                        onApply = settings.applyControllerMapping,
                        onDiscard = settings.discardControllerMapping,
                        onContinueEditing = settings.continueControllerMapping,
                        onDismiss = settings.closeControllerMapping,
                    )
                }
                MimirOverlayKind.CONTROLLER_SHORTCUT_CAPTURE -> dispatchControllerShortcutCapture(action) {
                    ControllerShortcutStore.cancelCapture()
                }
                MimirOverlayKind.HOME_QUICK_LAUNCH_PICKER -> {
                    val mode = homeQuickLaunchPickerMode()
                    if (mode == null || !homeQuickLaunchPickerOwnership.admitsControllerInput(authority)) {
                        // Mode state can lead composition by one frame. Absorb that pre-mount input
                        // rather than leaking it to Home. The mounted child admits dispatch only after
                        // it has made the picker scope authoritative; readiness telemetry is not a gate.
                        true
                    } else {
                        val consumed = dispatchHomeQuickLaunchPicker(
                            authority = authority,
                            mode = mode,
                            items = homeQuickLaunchPickerItems(),
                            action = action,
                            onLaunch = onHomeQuickLaunchPickerLaunch,
                            onAssign = onHomeQuickLaunchPickerAssign,
                            onClear = onHomeQuickLaunchPickerClear,
                            onDismiss = onHomeQuickLaunchPickerDismiss,
                        )
                        if (homeQuickLaunchPickerMode() == null) {
                            relinquishHomeQuickLaunchPickerOwnership(
                                authority = authority,
                                ownership = homeQuickLaunchPickerOwnership,
                            )
                        }
                        consumed
                    }
                }
                MimirOverlayKind.HOME_TILE_MENU -> {
                    onHomeTileMenuAction(action)
                    true
                }
                MimirOverlayKind.HOME_TOME_LIBRARY -> {
                    onHomeTomeLibraryAction(action)
                    true
                }
                MimirOverlayKind.LIBRARY_PLATFORM_TILE_MENU -> {
                    onPlatformTileMenuAction(action)
                    true
                }
                MimirOverlayKind.GAMELIST_ACHIEVEMENTS -> dispatchAchievementCard(
                    action = action,
                    onScroll = achievements.state::requestCardScroll,
                    onBack = achievements.state::dismissCard,
                    setupActionShown = mimirAchievementCardOffersProfileSetup(achievements.state.cardState()),
                    onOpenProfile = onOpenRetroAchievementsSettings,
                    grid = achievements.state.cardGrid,
                )
                MimirOverlayKind.HOME_OPEN_WITH -> onHomeLaunchCardOpenWithAction(action)
                MimirOverlayKind.HOME_LAUNCH_CHOICE -> onHomeLaunchChoiceAction(action)
                MimirOverlayKind.HOME_LAUNCH_CARD -> onHomeLaunchCardAction(action)
                MimirOverlayKind.HOME_QUICK_LAUNCH -> dispatchHomeQuickLaunch(
                    authority = authority,
                    slots = homeQuickLaunchSlots(),
                    action = action,
                    onLaunch = onHomeQuickLaunch,
                    onOpenGeneral = onHomeQuickLaunchGeneral,
                    onEdit = onHomeQuickLaunchEdit,
                    onDismiss = onHomeQuickLaunchDismiss,
                )
                MimirOverlayKind.PLATFORM_PICKER -> dispatchPlatformEmulatorPicker(
                    authority = authority,
                    choices = settings.platformPickerChoices,
                    action = action,
                    onChooseAuto = { settings.choosePlatformDefault(null) },
                    onChoose = settings.choosePlatformDefault,
                    onDismiss = settings.closePlatformPicker,
                )
                MimirOverlayKind.BACKDROP_PICKER -> dispatchBackdropPicker(
                    authority = authority,
                    action = action,
                    onChoose = settings.chooseBackdrop,
                    onDismiss = settings.closeBackdropPicker,
                )
                MimirOverlayKind.THEME_PICKER -> dispatchThemeGallery(
                    authority = authority,
                    scope = ThemePickerScope,
                    action = action,
                    onPick = settings.commitTheme,
                    onDismiss = settings.closeThemePicker,
                )
                // The two appearance-image pickers (concept A).
                MimirOverlayKind.BACKGROUND_PICKER -> settings.backgroundPicker?.let { picker ->
                    dispatchBackgroundPicker(
                        authority = authority,
                        state = picker,
                        action = action,
                        onTransition = settings.applyBackgroundPickerTransition,
                    )
                } ?: true
                MimirOverlayKind.MBUTTON_PICKER -> settings.mimirButtonPicker?.let { picker ->
                    dispatchMimirButtonPicker(
                        authority = authority,
                        state = picker,
                        action = action,
                        onTransition = settings.applyMimirButtonPickerTransition,
                    )
                } ?: true
                MimirOverlayKind.ACCENT_PICKER -> dispatchAccentPicker(
                    authority = authority,
                    themeId = ThemeStore.themeId.value,
                    action = action,
                    onCommit = settings.commitAccent,
                    onCommitTemp = settings.commitAccentTemp,
                    onDismiss = settings.closeAccentPicker,
                )
                MimirOverlayKind.FOCUS_RING_DIAL -> dispatchColorDial(
                    focusedStop = settings.focusDialStop,
                    authoredPalette = MimirThemePalette.forId(ThemeStore.themeId.value),
                    action = action,
                    onStepTo = settings.stepFocusDial,
                    onCommit = settings.commitFocusRing,
                    onDismiss = settings.closeFocusDial,
                )
                // One slot, three menus (Source, Order, Music hours): the settings coordinator says which is open.
                MimirOverlayKind.MUSIC_SOURCE_PICKER -> when (settings.musicPickerMenu) {
                    MusicPickerMenu.SOURCE -> dispatchMusicSourcePicker(
                        authority = authority,
                        current = settings.music.source,
                        customAvailable = settings.music.customSourceAvailable,
                        action = action,
                        onSelect = settings.chooseMusicSource,
                        onDismiss = settings.closeMusicSourcePicker,
                    )
                    MusicPickerMenu.ORDER -> dispatchMusicOrderPicker(
                        authority = authority,
                        current = settings.music.order,
                        action = action,
                        onSelect = settings.chooseMusicOrder,
                        onDismiss = settings.closeMusicSourcePicker,
                    )
                    MusicPickerMenu.HOURS -> dispatchMusicHoursPicker(
                        authority = authority,
                        current = settings.music.hours,
                        action = action,
                        onSelect = settings.chooseMusicHours,
                        onDismiss = settings.closeMusicSourcePicker,
                    )
                    MusicPickerMenu.LAUNCH_SOUND -> dispatchMusicLaunchSoundPicker(
                        authority = authority,
                        current = settings.music.launchSound,
                        action = action,
                        onSelect = settings.chooseMusicLaunchSound,
                        onDismiss = settings.closeMusicSourcePicker,
                    )
                }
                MimirOverlayKind.MUSIC_VOLUME_DIAL -> {
                    val dial = settings.musicVolumeDial
                    if (dial == null) {
                        // Open always seats a session before raising the flag, so this is unreachable; absorb
                        // rather than leak a press to the pane beneath a hard modal.
                        true
                    } else {
                        dispatchMusicVolumeDial(
                            levelPercent = dial.levelPercent,
                            action = action,
                            onStepTo = settings.stepMusicVolumeDial,
                            onCommit = settings.commitMusicVolumeDial,
                            onDismiss = settings.closeMusicVolumeDial,
                        )
                    }
                }
                // A hard modal: every action is the editor's, and a press never leaks to the Settings pane beneath.
                MimirOverlayKind.EQUALIZER_EDITOR -> settings.equalizerEditor?.dispatch(action) ?: true
                MimirOverlayKind.BRIGHTNESS -> core.brightness.dispatch(action)
            }
        },
        onHomeTomeLibraryAction = onHomeTomeLibraryAction,
        onOpenRetroAchievementsSettings = onOpenRetroAchievementsSettings,
        homeQuickLaunchPickerOwnership = homeQuickLaunchPickerOwnership,
        // The one overlay whose CANCEL is a step back. Get art and the fetch card close on CANCEL itself.
        closeEntirely = { kind ->
            if (kind == MimirOverlayKind.FOLDER_BROWSER) {
                closeFolderBrowser()
                true
            } else if (closeMimirButtonPickerWhileAdjusting(kind, settings.mimirButtonPicker, settings.closeMimirButtonPicker)) {
                true
            } else {
                false
            }
        },
    )
}

/**
 * The Mimir-button picker's part of the sweep's one-step full close ([MimirOverlayCoordinator.dismissConflictingModals]): while the player is
 * ADJUSTING an image, [close] shuts the whole picker and this answers true. It writes nothing -- the placement being shaped is dropped, exactly
 * as B does -- and the saved placement stays as it was.
 *
 * Why it needs a full close: in adjust mode CANCEL means "cancel the adjust" (`mimirButtonAdjustTransition`: back to the browse view, the picker
 * still open), so the single CANCEL the M button's Home, Settings, Utility and Tweaker commands send each overlay left the picker on top and the
 * command landed underneath it -- the folder browser at depth, walk-32k, in a new costume. Not adjusting, CANCEL already dismisses the picker, so
 * this declines and the sweep sends it.
 */
internal fun closeMimirButtonPickerWhileAdjusting(
    kind: MimirOverlayKind,
    picker: MimirButtonPickerState?,
    close: () -> Unit,
): Boolean {
    if (kind != MimirOverlayKind.MBUTTON_PICKER || picker?.adjust == null) return false
    close()
    return true
}

/**
 * The `FOLDER_BROWSER` branch's whole decision, extracted so it is reachable from a JVM unit test with a
 * real [FileBrowserController] and a real `FocusAuthority` -- the same reason
 * [dispatchAchievementCard] / [dispatchControllerShortcutCapture] / [dispatchArtworkFetch] below are
 * free functions rather than lambda bodies inside [createMimirOverlayCoordinator]'s factory.
 *
 * See the `FOLDER_BROWSER` branch's own comment for why each arm is what it is. The load-bearing
 * property, and the one the P0 of 2026-08-14 was: **`true` here is never unconditional.**
 * [FileBrowserController.dispatch] returns false for exactly one reason -- its `FocusScope` is not the
 * active one -- which is transient while mounting and permanent once anything else takes the scope out
 * from under a still-mounted browser. Absorbing both cases made this line an infinite input sink for
 * BOTH the controller and touch (every affordance in `FileBrowserSurface` is behind the same guard).
 */
internal fun dispatchFolderBrowser(
    browser: FileBrowserController?,
    action: ControllerAction,
): Boolean = when {
    // Unreachable by construction -- `folderBrowser = folderBrowser() != null` in this coordinator's own
    // snapshot is what put FOLDER_BROWSER on the stack, and both reads are synchronous on one thread
    // inside one dispatch() call, so they cannot disagree. Kept absorbing rather than deleted: if that
    // ever stops being true, a modal that IS on screen must not leak presses to the destination.
    browser == null -> true
    // Normal operation. dispatch() answers `true` for every action that reaches it at all.
    browser.dispatch(action) -> true
    // The genuine pre-mount gap this branch exists for. Bounded: FileBrowserSurface's DisposableEffect
    // calls open() in the same frame that made this overlay top of the stack.
    !browser.hasOpened -> true
    // Mounted, but divorced from its focus scope: this browser cannot act on the action and never will.
    // Fail toward input -- refuse, so the press leaves the overlay layer instead of disappearing.
    else -> false
}

/** The ledger is read-only, but its bounded list still has to obey physical D-pad movement. */
internal fun dispatchAchievementCard(
    action: ControllerAction,
    onScroll: (MimirAchievementScrollDirection) -> Unit,
    onBack: () -> Unit,
    // Product review 2026-09-28 (F3): a NEEDS SETUP card shows one action, drawn focused; A activates it.
    setupActionShown: Boolean = false,
    onOpenProfile: () -> Unit = {},
    // Main's rich page (a loaded set), while it is mounted: the pad walks its badge grid and A selects a badge; B and the OS Back stay this overlay's own.
    grid: ((ControllerAction) -> Boolean)? = null,
): Boolean {
    if (grid != null) {
        when (action) {
            ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK -> onBack()
            else -> grid(action)
        }
        return true
    }
    when (action) {
        ControllerAction.NAV_UP -> onScroll(MimirAchievementScrollDirection.UP)
        ControllerAction.NAV_DOWN -> onScroll(MimirAchievementScrollDirection.DOWN)
        ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK -> onBack()
        ControllerAction.CONFIRM -> if (setupActionShown) onOpenProfile()
        else -> Unit
    }
    return true
}

private inline fun swallowUntilBack(action: ControllerAction, onBack: () -> Unit): Boolean {
    if (action == ControllerAction.CANCEL || action == ControllerAction.SYSTEM_BACK) onBack()
    return true
}

/** Controller and OS Back share the capture-cancel policy; every other action stays swallowed. */
internal fun dispatchControllerShortcutCapture(
    action: ControllerAction,
    onBack: () -> Unit,
): Boolean = swallowUntilBack(action, onBack)

/** Validation owns the modal until its live request settles; Back never strands a hidden in-flight save. */
internal fun dispatchRetroAchievementsConnect(
    action: ControllerAction,
    validating: Boolean,
    onBack: () -> Unit,
): Boolean {
    val isBack = action == ControllerAction.CANCEL || action == ControllerAction.SYSTEM_BACK
    if (isBack && !validating) onBack()
    return true
}

/**
 *  the fetch summary's single Close pill must be reachable with A, not only B.
 * CONFIRM activates Close ONLY on the terminal states — while Running/Stopping the sole control is a
 * destructive Cancel with no focus ring, so a habit A-press must never invisibly kill a long fetch;
 * cancel stays on B (with its immediate "Stopping…" feedback) and on the touch pill.
 */
internal fun dispatchArtworkFetch(
    action: ControllerAction,
    fetchUi: ArtworkFetchUi?,
    onCloseSummary: () -> Unit,
    onBack: () -> Unit,
    onConfirmNewGames: () -> Unit = {},
    authority: FocusAuthority? = null,
    inputArmed: () -> Boolean = { true },
): Boolean {
    if (fetchUi is ArtworkFetchUi.ConfirmNewGames) {
        // P3-2: a press of A or B in the first moments after the card lands is a reflex from whatever the player was doing, not an answer to a question they have not read.
        val answering = action == ControllerAction.CONFIRM || action == ControllerAction.CANCEL || action == ControllerAction.SYSTEM_BACK
        if (answering && !inputArmed()) return true
        if (action == ControllerAction.CANCEL || action == ControllerAction.SYSTEM_BACK) {
            onBack()
            return true
        }
        val focusAuthority = authority ?: return true
        if (focusAuthority.state.value.scope != NewGameEnrichFocusScope) return true
        when (action) {
            ControllerAction.NAV_UP,
            ControllerAction.NAV_DOWN,
            ControllerAction.NAV_LEFT,
            ControllerAction.NAV_RIGHT,
            -> focusAuthority.moveFocus(action)
            ControllerAction.CONFIRM -> when (focusAuthority.state.value.focused) {
                NewGameEnrichConfirmKey -> onConfirmNewGames()
                NewGameEnrichDismissKey -> onBack()
                else -> Unit
            }
            else -> Unit
        }
        return true
    }
    val summaryShowing = fetchUi is ArtworkFetchUi.Done || fetchUi is ArtworkFetchUi.Cancelled
    if (action == ControllerAction.CONFIRM && summaryShowing) {
        onCloseSummary()
        return true
    }
    return swallowUntilBack(action, onBack)
}
