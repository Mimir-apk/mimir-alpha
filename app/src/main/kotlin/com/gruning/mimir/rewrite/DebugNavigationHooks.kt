package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.ui.MimirQuickRailAction
import com.gruning.mimir.rewrite.feature.files.FileBrowserMode
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Debug-only intent extra that arms the bounded Home rail input observation session. */
internal const val DebugObserveHomeRailExtra = "mimir.observeHomeRail"

/**
 * Debug-only bridge from [MainActivity]'s imperative `mimir.*` intent-extra hooks into the Compose
 * navigation/settings/overlay coordinators, which are `remember`ed inside [MimirAppCoordinator]'s
 * composition and therefore have no stable reference [MainActivity] (a plain [android.app.Activity], not
 * a composable) can hold across recomposition. Modeled directly on [MButtonNavigationCommands]
 * (`MButtonRuntime.kt`) — the app already solves this exact "Activity publishes, composition consumes"
 * shape for M-button commands: a single pending request, `publish`/`acknowledge` by id, no polling.
 *
 * ## Why this object performs no gating of its own
 * [DebugIntentHookGate] is the security boundary (see its own doc comment): [MainActivity] is exported
 * for LAUNCHER and HOME, so any installed app can start it with arbitrary extras, and only
 * `BuildConfig.DEBUG` may decide whether those extras do anything. This bridge is NOT a second gate and
 * must never become one — it is inert plumbing, exactly like [MButtonNavigationCommands] is for M-button
 * commands (which carries no gate at all, because its only callers are already-trusted in-process code).
 * The single call site that ever invokes [DebugNavigationBridge.publish] is
 * [MainActivity.maybeOpenGamelist] / [MainActivity.maybeOpenOverlay] / [MainActivity.maybeDismissTopOverlay]
 * / [MainActivity.maybeOpenFiles], each parsed from an extra INSIDE
 * `runDebugIntentHooks() = debugIntentHookGate.run { ... }`. In a release
 * build that block never runs, so `publish` is never called, so [pending] never leaves null, so the
 * [MimirAppCoordinator] consumer below never has anything to dispatch — release behaviour is provably
 * unchanged because the producer side is unreachable, not because the consumer checks a flag.
 *
 * The consumer side (a `LaunchedEffect` in [MimirAppCoordinator]) always exists, in every build variant —
 * same as [MButtonNavigationCommands]'s consumer. That is deliberate: gating the CONSUMER instead of (or
 * in addition to) the producer would be the "second weaker gate" the brief explicitly forbids, since a
 * consumer-side check can never see WHO published, only THAT something did.
 *
 * ## Why every request replays a REAL production entry point, never a synthetic one
 * - [DebugNavigationRequest.OpenGamelist] dispatches through
 *   [MimirNavigationCoordinator.openPlatformInLibrary] — the Shelf Lab's production platform entry.
 *   Its mounted-Library case also establishes GamelistScope; other destinations defer to Library mount.
 * - [DebugNavigationRequest.OpenSettingsRow] dispatches through
 *   `MimirSettingsCoordinator.onRowActivate` — the exact dispatcher a Confirm-press on a Settings row
 *   calls (`MimirSettingsCoordinator.kt:472-545`). Several canonical row ids land an overlay whose Compose
 *   host mounts a real on-screen keyboard unconditionally while open (`profile/name`,
 *   `appearance/enrichment/steamgriddb` — see `MimirAppHost.kt:1850-1930`), which is how this bridge also
 *   reaches the "keyboard on a named surface" scenario without a bespoke third request kind.
 * - [DebugNavigationRequest.DismissTopOverlay] dispatches a synthetic CANCEL through
 *   `MimirOverlayCoordinator.dispatch` — the exact function a physical B/Back press reaches
 *   (`MimirAppInputDispatch.kt:287`).
 * - [DebugNavigationRequest.LaunchGame] dispatches through `LibraryViewModel.launch` — the exact
 *   function a Confirm-press on a Library game tile calls. **This one exists because the alternative was
 *   producing false evidence.** `mimir.launchFirst` used to call `LaunchInjection.controller(...).launch`
 *   directly, which fires the right Intent at the right package but emits no
 *   `LibraryGameSessionEvent.Preparing` — so `MimirSessionAuthority` never learns a game session began,
 *   `armGameOwner` never fires, and screen 2 describes the EMULATOR instead of the game. E1 was
 *   originally reported from a run of this probe, and the companion half of that report was an artefact
 *   of the probe rather than a property of the product. A proof hook that skips the session protocol
 *   cannot be used to reason about anything downstream of it.
 * - [DebugNavigationRequest.OpenFiles] dispatches through `FileBrowserStore.open` — the one primitive
 *   both production callers converge on (`MimirSettingsCoordinator.launchSourcePicker`'s add-ROMs picker
 *   for [FileBrowserMode.PICK], `AppBrightnessCoordinator.openFileBrowser`'s Utility "Files" row for
 *   [FileBrowserMode.MANAGE]), not either wrapper itself — each wrapper carries its own availability gate
 *   (`addGamesRoute`'s BUSY/system-picker fallback, `filesRouteDecision`'s REQUEST_ALL_FILES_ACCESS
 *   fallback) that a real Confirm-press must respect but this proof hook deliberately does not, the same
 *   way `OpenGamelist` above calls the navigation primitive directly rather than replaying whatever
 *   caller sits in front of it.
 *
 * None of these bypass [com.gruning.mimir.rewrite.core.input.FocusAuthority] with a synthetic write of
 * their own. `OpenSettingsRow` and `DismissTopOverlay` dispatch through functions that push/pop their own
 * focus scope directly (`MimirSettingsCoordinator.kt`'s `openThemePicker` and siblings;
 * `MimirOverlayCoordinator`'s per-overlay CANCEL handlers). `OpenGamelist` is narrower:
 * `openPlatformInLibrary` updates the model and destination, and enters GamelistScope when Library's
 * shelf is already mounted. Cross-destination entry leaves scope creation to Library's mount effect.
 * A data-only update inside an already-mounted shelf would otherwise strand its old input scope. `OpenFiles` is narrower again, the same way:
 * `FileBrowserStore.open` itself pushes no scope either — `FileBrowserController.open()` does that
 * (`authority.pushScope(scope)`, `FileBrowserController.kt:602`), and that only runs later, when
 * `FileBrowserSurface`'s own `DisposableEffect` mounts (`FileBrowserSurface.kt:166`) — so whatever scope
 * that produces cannot distinguish this caller from a real one either.
 */
internal sealed interface DebugNavigationRequest {
    val id: UUID

    /** Opens the Library gamelist for [platformId], same as a Confirm-press on that platform's Home tile. */
    data class OpenGamelist(
        val platformId: PlatformId,
        override val id: UUID = UUID.randomUUID(),
    ) : DebugNavigationRequest

    /**
     * Activates a Settings canonical row id, same as a Confirm-press on that row
     * (`canonicalSettingsDetailRowId` in `feature-settings/SettingsFocus.kt` resolves aliases; most ids
     * prefixed `profile/`, `appearance/`, `controls/`, `library/`, `emulators/`, or `about/` pass through
     * unchanged and are valid here as-is).
     */
    data class OpenSettingsRow(
        val rowId: String,
        override val id: UUID = UUID.randomUUID(),
    ) : DebugNavigationRequest

    /** Sends one synthetic CANCEL through the real overlay dispatch, same as a physical B/Back press. */
    data class DismissTopOverlay(
        override val id: UUID = UUID.randomUUID(),
    ) : DebugNavigationRequest

    /**
     * Opens the file manager in [mode], through `FileBrowserStore.open` — the one primitive both
     * production callers converge on ([FileBrowserMode.PICK] via Settings' add-ROMs picker,
     * [FileBrowserMode.MANAGE] via the Utility "Files" row). See this interface's own KDoc, "Why every
     * request replays a REAL production entry point," for why this dispatches through that primitive
     * directly rather than either wrapper.
     */
    data class OpenFiles(
        val mode: FileBrowserMode,
        override val id: UUID = UUID.randomUUID(),
    ) : DebugNavigationRequest

    /**
     * Launches a library game through `LibraryViewModel.launch`, same as a Confirm-press on its tile.
     *
     * Carries IDENTITY, not a `GameRow`: the consumer re-reads the row from the repository at dispatch,
     * so the probe cannot smuggle a hand-built row past the real path's own view of the library. That is
     * the same reason [OpenGamelist] carries a [PlatformId] rather than a resolved gamelist.
     */
    data class LaunchGame(
        val gameId: GameId,
        val platformId: PlatformId,
        override val id: UUID = UUID.randomUUID(),
    ) : DebugNavigationRequest

    /**
     * Presses one nav-rail seat, same as a Confirm-press on that dock icon.
     *
     * **Why this exists.** On 2026-09-03 a device report identified slow traversal of platforms on the Thor Library shelf, and there was no way to get a device onto the Library shelf from adb — verified,
     * not assumed: `DPAD_DOWN` does not move focus out of the shelf into the rail and `BUTTON_R1` does
     * not change destination, both checked by screenshot with focus unchanged at item 18/21.
     * [OpenGamelist] lands a platform's *gamelist*, one level BELOW the shelf, so it cannot reach the
     * surface being reported. A whole investigation was blocked on two button presses only a human
     * could make.
     *
     * Carries the [MimirQuickRailAction] rather than a [MimirDestination] deliberately, and that is the
     * difference between replaying a real entry point and forging one. `onShelfQuickAction` is the
     * single dispatcher every rail press already goes through
     * (`MimirNavigationCoordinator.kt:238-256`), and two of its six seats are not destination writes at
     * all — BRIGHTNESS opens the Utility overlay and QUICK_LAUNCH opens the Apps picker. Publishing a
     * destination would have reached LIBRARY while silently inventing a second, simpler navigation model
     * for the other four, which is exactly the "synthetic entry point" this file's own KDoc forbids.
     */
    data class ShelfQuickAction(
        val action: MimirQuickRailAction,
        override val id: UUID = UUID.randomUUID(),
    ) : DebugNavigationRequest
}

internal object DebugNavigationBridge {
    private val pendingMutable = MutableStateFlow<DebugNavigationRequest?>(null)
    val pending: StateFlow<DebugNavigationRequest?> = pendingMutable.asStateFlow()

    fun publish(request: DebugNavigationRequest) {
        pendingMutable.value = request
    }

    fun acknowledge(id: UUID) {
        if (pendingMutable.value?.id == id) pendingMutable.value = null
    }
}

/**
 * Pure parse for `--es mimir.openGamelist <platformId>`. Trims, blank-filters, and only then wraps in
 * [PlatformId] — an absent or blank extra means "hook not fired," matching every sibling `maybe*` hook's
 * `?.takeIf { it.isNotEmpty() } ?: return` shape (see e.g. `MainActivity.maybeLaunchTargetedGame`).
 */
internal fun parseOpenGamelistPlatformId(rawExtra: String?): PlatformId? =
    rawExtra?.trim()?.takeIf { it.isNotEmpty() }?.let(::PlatformId)

/** Pure parse for `--es mimir.openOverlay <rowId>`. Trims and blank-filters; does not canonicalize. */
internal fun parseOpenOverlayRowId(rawExtra: String?): String? =
    rawExtra?.trim()?.takeIf { it.isNotEmpty() }

/**
 * Pure parse for `--es mimir.openFiles <pick|manage>`. Trims and blank-filters like
 * [parseOpenGamelistPlatformId] / [parseOpenOverlayRowId], then maps the surviving value to a
 * [FileBrowserMode] — `"pick"` to [FileBrowserMode.PICK], `"manage"` to [FileBrowserMode.MANAGE].
 *
 * An absent, blank, OR unrecognised value all return null ("hook not fired") — unlike its two siblings
 * above, which wrap whatever non-blank string survives trimming, this parser must never fall back to a
 * default mode: silently opening [FileBrowserMode.MANAGE] (delete, move, the queue) when the tester typed
 * something this parser could not read would hand a QA lane the destructive half by accident.
 */
internal fun parseOpenFilesMode(rawExtra: String?): FileBrowserMode? =
    when (rawExtra?.trim()?.takeIf { it.isNotEmpty() }) {
        "pick" -> FileBrowserMode.PICK
        "manage" -> FileBrowserMode.MANAGE
        // The Customize-media Upload picker's mode, reachable here so a device check can open it without driving the tile menu.
        "pickimage" -> FileBrowserMode.PICK_IMAGE
        // Settings › Music's folder pick (2026-09-30), reachable the same way for a device check.
        "pickmusic" -> FileBrowserMode.PICK_MUSIC_FOLDER
        else -> null
    }

/**
 * Pure parse for `--es mimir.shelfAction <home|library|utility|settings|apps|files>`.
 *
 * Case-insensitive, trimmed, blank-filtered. An absent, blank OR unrecognised value all return null
 * ("hook not fired") — the same fail-closed shape as [parseOpenFilesMode] and for the same reason: two
 * of these seats are not navigation. A typo silently falling through to a default could open the Utility
 * overlay or the Apps picker when the caller asked for a shelf, and a probe that lands somewhere other
 * than where it says it landed produces evidence about the wrong surface.
 *
 * The accepted words are the rail's own LABELS, not its enum constant names, because the labels are what
 * the dock actually reads on screen — `MimirQuickRailAction.BRIGHTNESS` is labelled "Utility" and
 * `QUICK_LAUNCH` is labelled "Apps" (`MimirRunicQuickRail.kt:59-73`). Typing what you can see beats
 * knowing which constant hides behind it.
 */
internal fun parseShelfQuickAction(rawExtra: String?): MimirQuickRailAction? =
    when (rawExtra?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }) {
        "home" -> MimirQuickRailAction.HOME
        "library" -> MimirQuickRailAction.LIBRARY
        "utility", "brightness" -> MimirQuickRailAction.BRIGHTNESS
        "settings" -> MimirQuickRailAction.SETTINGS
        "apps", "quick_launch" -> MimirQuickRailAction.QUICK_LAUNCH
        "files" -> MimirQuickRailAction.FILES
        else -> null
    }

/**
 * Pure parse for `--es mimir.introLogo <absolute image path>`. Trims and blank-filters like
 * [parseOpenOverlayRowId]; does not check that the file exists, because a missing file is a legitimate
 * thing to photograph (the stage's no-ink state) and this parser must not decide that for the caller.
 *
 * **Read the warning at the top of this file before using this one.** Unlike its siblings, this hook does
 * NOT arrive through the surface it photographs. [GameOpeningIntroState] is a process-wide holder that a
 * real launch fills, and this extra fills it directly, so what appears is the overlay rendering a plan
 * nobody launched. That is sound evidence for exactly one question — how [MimirLogoStage] lays out ink of
 * a given aspect (R5d/R5e) — and is NOT evidence about launch, audio, timing, or the intro's lifecycle.
 * A shot taken through this hook has to say so.
 *
 * It exists because `GameOpeningIntroOverlay()` is hosted on Main (`MimirAppHost.kt:785`), so the stage
 * cannot be photographed while a game owns Main, and the emulator that frees Main has no game to launch.
 */
internal fun parseIntroLogoPath(rawExtra: String?): String? =
    rawExtra?.trim()?.takeIf { it.isNotEmpty() }
