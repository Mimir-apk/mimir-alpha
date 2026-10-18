package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.model.library.PlatformRow

/** The emulated-game hide confirmation modal scope. */
internal val HideConfirmScope = FocusScope("library/hideConfirm")
internal val HideConfirmKey = FocusKey("library/hideConfirm/hide")
internal val HideCancelKey = FocusKey("library/hideConfirm/cancel")

/** The native Android hide confirmation modal scope. */
internal val NativeHideConfirmScope = FocusScope("library/nativeHideConfirm")
internal val NativeHideConfirmKey = FocusKey("library/nativeHideConfirm/hide")
internal val NativeHideCancelKey = FocusKey("library/nativeHideConfirm/cancel")

/** The native Android shelf Add app picker scope. */
internal val NativeAddPickerScope = FocusScope("library/nativeAdd")
internal val NativeAddPickerSearchKey = FocusKey("library/nativeAdd/search")
internal val NativeAddPickerCloseKey = FocusKey("library/nativeAdd/close")

/**
 * Pushes a confirm or picker [scope] with its SAFE key already remembered (RC-32, input hazard).
 *
 * A scope remembers the last key focused in it, and a modal's keys are not registered when it is pushed, so what lands first
 * is whatever the scope remembers: after a confirmed hide that is the Hide key, after a pick it is the last candidate. The
 * dialog's own landing effect moves focus to the safe key one or two frames later, and a stray A inside that window confirms
 * the hide or adds the app. Stamping the safe key before the push makes the first key that registers the safe one. The landing
 * effects stay: they cover a safe key that is not registered yet at the push.
 */
internal fun pushScopeLandingOn(authority: FocusAuthority, scope: FocusScope, safeKey: FocusKey) {
    authority.rememberFocusForScope(scope, safeKey)
    authority.pushScope(scope)
}

/**
 * THE single input entry point for the Library. The app shell feeds it one normalized [ControllerAction]
 * per press (already dual-source/hat/stick-coalesced by the InputAuthority). It routes by the currently
 * ACTIVE focus scope so the shelf, gamelist, and modal affordances share one focus truth, and it keeps the
 * [LibraryViewModel]'s open-platform state in lockstep with the scope stack (single truth — the view shown
 * is never derived from focus).
 *
 * Returns true if it handled the action (so the shell knows not to treat it as a shell-level Back).
 *
 * @param onExit invoked when CANCEL/Back is pressed while the platforms shelf already owns focus — the
 *   caller decides "leave Library" (single back scope).
 * @param onRescanFolder invoked when CONFIRM is over the "Add / rescan folder" affordance — the app fires
 *   the SAF picker (it owns the ActivityResult contract).
 */
fun dispatchToLibrary(
    authority: FocusAuthority,
    viewModel: LibraryViewModel,
    action: ControllerAction,
    onExit: () -> Unit,
    onRescanFolder: () -> Unit,
    onLaunchNativePackage: (String) -> Unit = {},
    onToggleNativeFavorite: (String) -> Unit = {},
    // The stable gamelist media seat runs Enrich before durable provider truth and opens Hero customization after.
    onFetchArt: (GameRow) -> Unit = {},
    onCustomizeHero: (GameRow) -> Unit = {},
    /**
     * Opens the achievement card. **No longer bound to SELECT:** achievements now have a Settings entry.
     * They are still reachable from the detail card's own Achievements action, which is the call site
     * that keeps this parameter alive.
     */
    onOpenAchievements: (GameRow) -> Unit = {},
    /**
     * Y opens the focused game's media menu — Customize Media.
     *
     * Y targets the focused game's media menu, not the app's Settings destination; routing to Settings
     * would leave the gamelist without its media shortcut.
     *
     * It is not a new behaviour, only a new way in: the gamelist already carries this action on its
     * media seat ([GamelistGetArtKey]), reachable by moving focus onto it and pressing A. Y runs the
     * SAME branch on the focused game — which is also the grammar the EMULATION shelf has used since
     * 2026-07-15, where Y on a platform tile opens its art menu.
     */
    onOpenMediaMenuForFocused: (GameRow) -> Unit = {},
    /** Y on a focused ANDROID shelf tile: the same media menu, for an installed package. */
    onOpenNativeAppTileMenu: (String) -> Unit = {},
    onRequestHideNativePackage: (String) -> Unit = {},
    onConfirmHideNativePackage: () -> Unit = {},
    onCancelHideNativePackage: () -> Unit = {},
    onOpenNativeAddPicker: (LibraryShelfMode) -> Unit = {},
    onMoveNativeAddPickerFocus: (ControllerAction) -> Boolean = { false },
    onOpenNativeAddSearch: () -> Unit = {},
    onChooseNativeAddPackage: (String) -> Unit = {},
    onDismissNativeAddPicker: () -> Unit = {},
    platformRows: List<PlatformRow> = viewModel.platforms.value,
    nativeGameRows: List<NativeShelfRow> = emptyList(),
    nativeAppRows: List<NativeShelfRow> = emptyList(),
    nativeFavoriteGameRows: List<NativeShelfRow> = emptyList(),
    nativeFavoriteAppRows: List<NativeShelfRow> = emptyList(),
    activeDetailTarget: LibraryDetailTarget? = null,
    detailActionState: LibraryDetailActionState? = null,
    detailDescriptionScrollState: HomeLaunchDescriptionScrollState = HomeLaunchDescriptionScrollState(),
    onDetailDescriptionScroll: (Int) -> Unit = {},
    /** Receives a validated game-only target and its exact source focus before detail scope is pushed. */
    onOpenLibraryDetail: ((LibraryDetailSession) -> Unit)? = null,
    onCloseLibraryDetail: (LibraryDetailTarget) -> Unit = {},
    /** Y on a focused EMULATION platform tile → open its change-icon-art menu. */
    onOpenPlatformTileMenu: (PlatformRow) -> Unit = {},
    /** CONFIRM on the launch card's title-edit glyph opens the rename overlay. */
    onEditGameTitle: (LibraryDetailTarget.Emulated) -> Unit = {},
): Boolean {
    val scope = authority.state.value.scope
    // The gamelist search keyboard supports controller focus when a host binding is active.
    if (viewModel.keyboardOpen.value) {
        val dismissed = {
            viewModel.closeSearch()
            if (viewModel.librarySearchSession.value != null) {
                authority.requestFocus(LibrarySearchQueryControlKey)
                viewModel.rememberLibrarySearchFocus(LibrarySearchFocusIdentity.Query)
            }
        }
        if (dispatchLibraryMimirKeyboard(authority, viewModel.libraryKeyboardHost, action, dismissed)) {
            return true
        }
        return when (action) {
            ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK -> {
                dismissed()
                true
            }
            else -> true
        }
    }

    return when (scope) {

        // ── Platforms shelf ──
        LibraryShelfScope -> when (action) {
            // OR-8: L3 opens or closes the controller-hint legend the Guidance button holds.
            ControllerAction.SHOW_HINTS -> {
                viewModel.requestHintLegend()
                true
            }
            // Directional input is ALWAYS consumed (never delegated to a platform focus engine — rule 4);
            // moveFocus's boolean only reports whether it moved (false at an edge, still handled).
            ControllerAction.NAV_LEFT, ControllerAction.NAV_RIGHT -> {
                // The Add pill carries physical bounds while ring tiles are synthetic virtual bounds — the
                // same mixed-space class as the vertical hop above. LEFT/RIGHT from the pill must not fall
                // through to geometric moveFocus (BUG-A: lands on the neighbour of the
                // previously focused tile). Horizontal ring traversal belongs on the ring; only DOWN may
                // settle from the pill. There is no horizontal peer on the pill row — consume as a no-op.
                val onAddPill = authority.state.value.focused?.value == "library/shelf/add"
                if (!onAddPill &&
                    !moveShelfListFocus(authority, viewModel, action, platformRows, nativeGameRows) &&
                    !onMoveNativeAddPickerFocus(action)
                ) {
                    authority.moveFocus(action)
                }
                true
            }
            ControllerAction.NAV_UP, ControllerAction.NAV_DOWN -> {
                // The Add pill carries PHYSICAL bounds while the coverflow tiles are SYNTHETIC virtual bounds
                // (top=0) — mixed coordinate spaces, so a geometric moveFocus between them is unreliable and the
                // round-trip "up to Add, back to content" lands on a stray tile. Make the vertical hop EXPLICIT + symmetric: a ring tile → UP focuses the Add pill; the
                // Add pill → DOWN returns to the CENTRED tile (the coverflow's remembered centre). DOWN from a
                // tile still hands off to the dock (AppShelfQuickRail runs first).
                val focusedValue = authority.state.value.focused?.value
                val onAddPill = focusedValue == "library/shelf/add"
                val onRingTile = focusedValue != null && !onAddPill &&
                    (focusedValue.startsWith("library/shelf/") || focusedValue.startsWith("library/native/"))
                val handled = when {
                    action == ControllerAction.NAV_DOWN && onAddPill -> focusRememberedShelfTile(authority, viewModel)
                    action == ControllerAction.NAV_UP && onRingTile -> {
                        // The Add→DOWN return anchor is stamped by onCenter POST-composition, so a
                        // coalesced ring-move+UP burst reaches the pill before any frame records the
                        // new tile, and DOWN returns to the stale centre (BUG-3, // "up to Add and back, wrong tile"). The hop itself knows exactly which ring
                        // tile focus is leaving — record it synchronously; onCenter's later stamp of
                        // the same key is idempotent.
                        authority.state.value.focused?.let(viewModel::setCenteredShelfKey)
                        authority.requestFocus(FocusKey("library/shelf/add"))
                        true
                    }
                    else -> false
                }
                if (!handled && !onMoveNativeAddPickerFocus(action)) authority.moveFocus(action)
                true
            }
            ControllerAction.CONFIRM -> {
                when (val focused = authority.state.value.focused?.value) {
                    null -> Unit
                    "library/shelf/add" -> when (val mode = viewModel.shelfMode.value) {
                        LibraryShelfMode.EMULATION -> onRescanFolder()
                        LibraryShelfMode.ANDROID_GAMES -> {
                            onOpenNativeAddPicker(mode)
                            pushScopeLandingOn(authority, NativeAddPickerScope, NativeAddPickerSearchKey)
                        }
                    }
                    else -> {
                        // Pre-action membership check (current-ring membership), mirroring Home's
                        // `entries.any { it.focusKey == key }` guard (dispatchHomeContentAction's CONFIRM,
                        // HomeShelfModels.kt:466-469): act only if the live focused key is still a member
                        // of the CURRENT mode's ring. A stale cross-mode or since-removed
                        // "library/shelf/<id>"/"library/native/<pkg>" key must not resolve to a real
                        // platform/package and open the wrong (or a gone) target -- the same
                        // registration-gap window items 1/2 above close for movement.
                        if (focusedShelfRingKey(authority, viewModel, platformRows, nativeGameRows) != null) {
                            when (viewModel.shelfMode.value) {
                                LibraryShelfMode.EMULATION -> {
                                    focusedPlatformId(focused)?.let { openGamelist(authority, viewModel, it) }
                                }
                                LibraryShelfMode.ANDROID_GAMES -> {
                                    nativePackageFromFocusKey(focused)?.let { packageName ->
                                        val target = nativeGameRows
                                            .firstOrNull { it.packageName == packageName }
                                            ?.toLibraryDetailTargetOrNull()
                                        if (target != null && onOpenLibraryDetail != null) {
                                            openTypedLibraryDetail(
                                                authority = authority,
                                                viewModel = viewModel,
                                                target = target,
                                                onOpen = onOpenLibraryDetail,
                                            )
                                        } else {
                                            launchNativePackage(viewModel, packageName, onLaunchNativePackage)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                true
            }
            ControllerAction.PAGE_LEFT, ControllerAction.TRIGGER_LEFT -> {
                cycleShelfModeWithFocusStamp(authority, viewModel, platformRows, nativeGameRows, offset = -1)
                true
            }
            ControllerAction.PAGE_RIGHT, ControllerAction.TRIGGER_RIGHT -> {
                cycleShelfModeWithFocusStamp(authority, viewModel, platformRows, nativeGameRows, offset = 1)
                true
            }
            // Shipping shelf shortcut: X opens the hidden Favorites collection. Favorites is not a
            // shelf tile; it is a gamelist view reached from the current shelf mode.
            ControllerAction.SECONDARY -> {
                viewModel.openFavorites(
                    when (viewModel.shelfMode.value) {
                        LibraryShelfMode.EMULATION -> FavoriteSegment.GAMES
                        LibraryShelfMode.ANDROID_GAMES -> FavoriteSegment.ANDROID_GAMES
                    },
                )
                authority.pushScope(GamelistScope)
                true
            }
            // Y = the focused thing's media menu, in BOTH shelf modes.
            //
            // The EMULATION half is the established binding, unchanged. The ANDROID half used to toggle a
            // favorite here, which left the two modes one shoulder-press apart running opposite grammars
            // —  The
            // favorite moved to SELECT, where it now lives everywhere.
            ControllerAction.TERTIARY -> {
                // Membership-checked (current-ring membership, same pattern as CONFIRM above).
                val ringFocused = focusedShelfRingKey(authority, viewModel, platformRows, nativeGameRows)
                if (viewModel.shelfMode.value != LibraryShelfMode.EMULATION) {
                    ringFocused?.value
                        ?.let(::nativePackageFromFocusKey)
                        ?.let(onOpenNativeAppTileMenu)
                } else {
                    // Y on a focused EMULATION platform tile opens its change-icon-art menu.
                    ringFocused?.value
                        ?.let(::focusedPlatformId)
                        ?.let { id -> platformRows.firstOrNull { it.platformId == id } }
                        ?.let(onOpenPlatformTileMenu)
                }
                true
            }
            // SELECT = favorite, here as everywhere else now.
            //
            // The asymmetry recorded here — *"native shelf SELECT keeps its shipping hide shortcut"* —
            // was written when the gamelist's SELECT meant achievements, so the two could not agree
            // anyway. Both have moved, so the exception has nothing left to except: hide goes to START,
            // which is where every gamelist has kept it all along.
            ControllerAction.SYSTEM_SELECT -> {
                if (viewModel.shelfMode.value != LibraryShelfMode.EMULATION) {
                    // Membership-checked (current-ring membership, same pattern as CONFIRM above).
                    focusedShelfRingKey(authority, viewModel, platformRows, nativeGameRows)?.value
                        ?.let(::nativePackageFromFocusKey)
                        ?.let(onToggleNativeFavorite)
                }
                true
            }
            ControllerAction.SYSTEM_START -> {
                if (viewModel.shelfMode.value != LibraryShelfMode.EMULATION) {
                    authority.state.value.focused?.value
                        ?.let(::nativePackageFromFocusKey)
                        ?.let { packageName ->
                            onRequestHideNativePackage(packageName)
                            pushScopeLandingOn(authority, NativeHideConfirmScope, NativeHideCancelKey)
                        }
                    true
                } else {
                    false
                }
            }
            // At the shelf, Back/Cancel means "leave Library" (single back scope).
            ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK -> {
                onExit()
                true
            }
            else -> false
        }

        // ── Gamelist ──
        // The launch card is ONE card for both doors: this door only names its contract and supplies what each button does here. The ladder (LEFT/RIGHT, the
        // rename mark above Play, the description, CONFIRM, Y = the media menu, SELECT = Achievements, B) is dispatchHomeLaunchCard's, the one Home's card runs.
        GameDetailScope -> {
            val target = activeDetailTarget
                ?: detailActionState?.target
                ?: viewModel.openGameDetail.value?.let { LibraryDetailTarget.Emulated(it) }
            val emulatedTarget = target as? LibraryDetailTarget.Emulated
            when (action) {
                ControllerAction.NAV_UP, ControllerAction.NAV_DOWN,
                ControllerAction.NAV_LEFT, ControllerAction.NAV_RIGHT,
                ControllerAction.CONFIRM, ControllerAction.TERTIARY, ControllerAction.SYSTEM_SELECT,
                ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK,
                -> dispatchHomeLaunchCard(
                    authority = authority,
                    emulated = emulatedTarget != null,
                    action = action,
                    onPlay = {
                        when (target) {
                            is LibraryDetailTarget.Emulated ->
                                // a collapsed group asks first, in the themed menu, rather than guessing. `openLaunchChoice` returns false
                                // when there is nothing to choose between, and then this is the ordinary launch it has always been -- a single-file game must never
                                // meet a modal on Play.
                                if (!openLaunchChoice(authority, viewModel, target.game)) {
                                    viewModel.launch(target.game)
                                }
                            is LibraryDetailTarget.AndroidGame -> launchNativePackage(
                                viewModel = viewModel,
                                packageName = target.row.packageName,
                                onLaunchNativePackage = onLaunchNativePackage,
                                restoreLaunchCard = true,
                            )
                            null -> Unit
                        }
                    },
                    // Y always opens the row's menu : Enrich leads an emulated game's when it is offered one, and favourite lives in the menu on both doors,
                    // for an Android game as for an emulated one (the Android game's menu is the one the Android shelf's Y opens). It no longer fetches art directly;
                    // that is one more press, as it is on Home.
                    onEdit = {
                        when (target) {
                            is LibraryDetailTarget.Emulated -> onOpenMediaMenuForFocused(target.game)
                            is LibraryDetailTarget.AndroidGame -> onOpenNativeAppTileMenu(target.row.packageName)
                            null -> Unit
                        }
                    },
                    onEditTitle = { emulatedTarget?.let(onEditGameTitle) },
                    onAchievements = { emulatedTarget?.let { onOpenAchievements(it.game) } },
                    onOpenWith = {
                        emulatedTarget?.let { if (viewModel.loadOpenWith(it.game)) authority.pushScope(OpenWithScope) }
                    },
                    onClose = {
                        viewModel.closeGameDetail()
                        target?.let(onCloseLibraryDetail)
                        exitLibraryDetail(
                            authority = authority,
                            target = target,
                        )
                    },
                    descriptionScrollState = detailDescriptionScrollState,
                    onDescriptionScroll = onDetailDescriptionScroll,
                    contract = LibraryLaunchCardFocusContract,
                )
                // Anything else is not the card's: it stays unhandled, as it was, for the shell to answer.
                else -> false
            }
        }
        LibrarySearchScope -> when (action) {
            ControllerAction.NAV_UP, ControllerAction.NAV_DOWN,
            ControllerAction.NAV_LEFT, ControllerAction.NAV_RIGHT -> {
                moveLibrarySearchFocus(authority, viewModel, action)
                true
            }
            ControllerAction.CONFIRM -> {
                when (val focused = authority.state.value.focused) {
                    LibrarySearchQueryControlKey -> {
                        viewModel.rememberLibrarySearchFocus(LibrarySearchFocusIdentity.Query)
                        viewModel.openSearch()
                    }
                    LibrarySearchGenreFilterKey -> {
                        viewModel.rememberLibrarySearchFocus(LibrarySearchFocusIdentity.Genre)
                        if (viewModel.openLibrarySearchPicker(LibrarySearchPicker.GENRE)) {
                            authority.rememberFocusForScope(
                                LibrarySearchFilterPickerScope,
                                librarySearchSelectedPickerKey(
                                    LibrarySearchPicker.GENRE,
                                    viewModel.searchCriteria.value,
                                    viewModel.availableSearchFacets.value,
                                ),
                            )
                            authority.pushScope(LibrarySearchFilterPickerScope)
                        }
                    }
                    LibrarySearchPlatformFilterKey -> {
                        viewModel.rememberLibrarySearchFocus(LibrarySearchFocusIdentity.Platform)
                        if (viewModel.openLibrarySearchPicker(LibrarySearchPicker.PLATFORM)) {
                            authority.rememberFocusForScope(
                                LibrarySearchFilterPickerScope,
                                librarySearchSelectedPickerKey(
                                    LibrarySearchPicker.PLATFORM,
                                    viewModel.searchCriteria.value,
                                    viewModel.availableSearchFacets.value,
                                ),
                            )
                            authority.pushScope(LibrarySearchFilterPickerScope)
                        }
                    }
                    LibrarySearchClearKey -> {
                        viewModel.clearSearchCriteria()
                        authority.requestFocus(LibrarySearchQueryControlKey)
                        viewModel.rememberLibrarySearchFocus(LibrarySearchFocusIdentity.Query)
                    }
                    else -> librarySearchResultGameId(focused)
                        ?.let { gameId -> viewModel.games.value.firstOrNull { it.gameId == gameId } }
                        ?.let { game ->
                            activateLibrarySearchGame(
                                authority = authority,
                                viewModel = viewModel,
                                game = game,
                                onOpenLibraryDetail = onOpenLibraryDetail,
                            )
                        }
                }
                true
            }
            ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK -> {
                closeLibrarySearch(authority, viewModel)
                true
            }
            else -> true
        }

        LibrarySearchFilterPickerScope -> when (action) {
            ControllerAction.NAV_UP, ControllerAction.NAV_DOWN -> {
                authority.moveFocus(action)
                true
            }
            ControllerAction.NAV_LEFT, ControllerAction.NAV_RIGHT -> true
            ControllerAction.CONFIRM -> {
                applyFocusedLibrarySearchPickerOption(authority, viewModel)
                true
            }
            ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK -> {
                closeLibrarySearchPicker(authority, viewModel)
                true
            }
            else -> true
        }

        GamelistScope -> when (action) {
            ControllerAction.SHOW_HINTS -> {
                viewModel.requestHintLegend()
                true
            }
            // In the per-platform shelf (the CAROUSEL view), LEFT/RIGHT move through games, UP reaches the search launcher and DOWN
            // stays: there is no seat under the strip. In the LIST view DOWN-less RIGHT walks to the Enrich/Hero action beside the
            // row. Controller SELECT marks a favourite; the achievement card is in the Edit menu.
            ControllerAction.NAV_UP -> {
                when {
                    authority.state.value.focused == GamelistGetArtKey ->
                        gamelistMediaSeatOwner(viewModel)?.let { authority.requestFocus(gameKey(it)) }
                    authority.state.value.focused == LibrarySearchLauncherKey -> Unit
                    inCarouselGamelist(viewModel) -> {
                        focusedGame(authority, viewModel)?.let { game ->
                            viewModel.focusMetadata(game)
                            viewModel.prepareLibrarySearchOrigin(game)
                        }
                        authority.requestFocus(LibrarySearchLauncherKey)
                    }
                    focusedGame(authority, viewModel) == viewModel.games.value.firstOrNull() -> {
                        focusedGame(authority, viewModel)?.let { game ->
                            viewModel.focusMetadata(game)
                            viewModel.prepareLibrarySearchOrigin(game)
                        }
                        authority.requestFocus(LibrarySearchLauncherKey)
                    }
                    else -> authority.moveFocus(action)
                }
                true
            }
            ControllerAction.NAV_DOWN -> {
                when {
                    authority.state.value.focused == LibrarySearchLauncherKey ->
                        (viewModel.preparedLibrarySearchOrigin()
                            ?.let { gameId -> viewModel.games.value.firstOrNull { it.gameId == gameId } }
                            ?: viewModel.games.value.firstOrNull())
                            ?.let { authority.requestFocus(gameKey(it)) }
                    // The shelf (the Library's game shelf IS the Home shelf) has nothing under the strip: no
                    // Enrich/Hero seat to walk down to. Enrich and Customize media are on Y and in the Edit menu, so DOWN stays.
                    inCarouselGamelist(viewModel) -> Unit
                    authority.state.value.focused == GamelistGetArtKey -> Unit
                    else -> authority.moveFocus(action)
                }
                true
            }
            ControllerAction.NAV_LEFT -> {
                when {
                    authority.state.value.focused == LibrarySearchLauncherKey -> Unit
                    authority.state.value.focused == GamelistGetArtKey ->
                        gamelistMediaSeatOwner(viewModel)?.let { authority.requestFocus(gameKey(it)) }
                    inCarouselGamelist(viewModel) -> moveGamelistListFocus(authority, viewModel, -1)
                    else -> authority.moveFocus(action)
                }
                true
            }
            ControllerAction.NAV_RIGHT -> {
                when {
                    authority.state.value.focused == LibrarySearchLauncherKey -> Unit
                    authority.state.value.focused == GamelistGetArtKey -> Unit
                    inCarouselGamelist(viewModel) -> moveGamelistListFocus(authority, viewModel, 1)
                    listGamelistOffersMediaAction(viewModel) -> focusedGame(authority, viewModel)?.let { game ->
                        // Compact LIST paints no preview panel, so no seat is registered and RIGHT stays on
                        // the row (policy 2026-09-27, D3). Enrich and Customize media stay on Y.
                        if (gamelistMediaSeatRegistered(authority)) {
                            viewModel.focusMetadata(game)
                            authority.requestFocus(GamelistGetArtKey)
                        }
                    }
                    else -> authority.moveFocus(action)
                }
                true
            }
            // Regular L1/R1 toggles carousel↔list. Favorites keeps every shoulder on the segment cycle.
            ControllerAction.PAGE_LEFT, ControllerAction.PAGE_RIGHT -> {
                if (viewModel.openPlatform.value == LibraryViewModel.FAVORITES_PLATFORM_ID) {
                    viewModel.cycleFavoriteSegment(
                        favoriteSegmentsFor(
                            viewModel.favoritesCount.value,
                            nativeFavoriteGameRows,
                            nativeFavoriteAppRows,
                        ),
                        if (action == ControllerAction.PAGE_LEFT) -1 else 1,
                    )
                } else {
                    val anchor = focusedGameOrMetadata(authority, viewModel)
                    anchor?.let { game ->
                        viewModel.rememberGamelistFocus(game)
                        authority.rememberFocusForScope(GamelistScope, gameKey(game))
                    }
                    viewModel.toggleGamelistViewMode()
                    anchor?.let { authority.requestFocus(gameKey(it)) }
                }
                true
            }
            // Regular L2/R2 retains previous/next platform. Favorites still cycles segments.
            ControllerAction.TRIGGER_LEFT, ControllerAction.TRIGGER_RIGHT -> {
                if (viewModel.openPlatform.value == LibraryViewModel.FAVORITES_PLATFORM_ID) {
                    viewModel.cycleFavoriteSegment(
                        favoriteSegmentsFor(
                            viewModel.favoritesCount.value,
                            nativeFavoriteGameRows,
                            nativeFavoriteAppRows,
                        ),
                        if (action == ControllerAction.TRIGGER_LEFT) -1 else 1,
                    )
                } else {
                    // Was `else if (!viewModel.favoritesOnly.value)` with no else, so with the filter on
                    // the press reached nothing and said nothing -- proven dead on device against a
                    // negative control (the same keycode moved DS -> N64 -> 3DS unfiltered minutes
                    // earlier). it switches, and lands unfiltered.
                    switchGamelistPlatform(
                        authority,
                        viewModel,
                        if (action == ControllerAction.TRIGGER_LEFT) -1 else 1,
                    )
                }
                true
            }
            // CONFIRM opens typed detail. Provider choice lives in that detail's metadata, never a launch menu.
            ControllerAction.CONFIRM -> {
                if (authority.state.value.focused == LibrarySearchLauncherKey) {
                    if (viewModel.openPreparedLibrarySearch()) {
                        val initial = viewModel.librarySearchSession.value
                            ?.lastFocused
                            ?.let(::librarySearchFocusKey)
                            ?: LibrarySearchQueryControlKey
                        authority.rememberFocusForScope(
                            LibrarySearchScope,
                            initial,
                        )
                        authority.pushScope(LibrarySearchScope)
                    }
                } else if (viewModel.isNativeFavoritesSegment()) {
                    focusedNativeFavoritePackage(authority)?.let { packageName ->
                        val row = focusedNativeFavoriteRow(
                            authority = authority,
                            viewModel = viewModel,
                            nativeGameRows = nativeFavoriteGameRows,
                            nativeAppRows = nativeFavoriteAppRows,
                        )
                        val target = row?.toLibraryDetailTargetOrNull()
                        if (target != null && onOpenLibraryDetail != null) {
                            openTypedLibraryDetail(
                                authority = authority,
                                viewModel = viewModel,
                                target = target,
                                onOpen = onOpenLibraryDetail,
                            )
                        } else {
                            // Android apps and unresolved legacy rows deliberately stay direct-launch.
                            launchNativePackage(viewModel, packageName, onLaunchNativePackage)
                        }
                    }
                } else if (authority.state.value.focused == GamelistGetArtKey) {
                    // One stable seat changes responsibility after the first durable provider disposition.
                    // Resolved through the seat owner, not detailGame directly, so a null-metadata arrival
                    // (BUG-4's geometric DOWN) leaves the seat action-live, not just exit-live (gate F1).
                    gamelistMediaSeatOwner(viewModel)?.let { game ->
                        if (game.providerEnrichmentState().shouldOfferEnrich) {
                            onFetchArt(game)
                        } else {
                            onCustomizeHero(game)
                        }
                    }
                } else {
                    // Every device uses the same primary-display launch card before Play is chosen.
                    focusedGame(authority, viewModel)?.let { game ->
                        openTypedLibraryDetail(
                            authority = authority,
                            viewModel = viewModel,
                            target = LibraryDetailTarget.Emulated(game),
                            onOpen = { session -> onOpenLibraryDetail?.invoke(session) },
                        )
                    }
                }
                true
            }
            // Y = Settings, matching every other surface . START cannot serve here:
            // inside a gamelist it is consumed for hide, which is why this surface had no button route
            // to Settings at all.
            // Y = Customize Media on the focused game. Deliberately the SAME branch the media seat
            // runs — Enrich first while the provider has never answered, the media menu afterwards —
            // so the shortcut and the seat can never offer different things for one game.
            // Where no seat is painted (compact: no LIST panel, no carousel band), Y opens Customize media
            // outright; with no
            // seat on screen there is nothing for the shortcut to disagree with.
            ControllerAction.TERTIARY -> {
                focusedGameOrMetadata(authority, viewModel)?.let { game ->
                    if (game.providerEnrichmentState().shouldOfferEnrich && gamelistMediaSeatRegistered(authority)) {
                        onFetchArt(game)
                    } else {
                        onOpenMediaMenuForFocused(game)
                    }
                }
                true
            }
            // SELECT = quick-toggle favorite on the focused game (a fast console gesture).
            //
            // MOVED from Y, 2026-08-25, with BOTH segment branches intact. The native-favorites branch
            // and the emulated branch below are two duties this one action carries -- the second is the
            // survivor-focus dance that has to move authority before Room unregisters the current node --
            // and splitting them across two keys is how one of them would have been quietly lost.
            ControllerAction.SYSTEM_SELECT -> {
                if (viewModel.isNativeFavoritesSegment()) {
                    focusedNativeFavoritePackage(authority)?.let(onToggleNativeFavorite)
                } else {
                    focusedGameOrMetadata(authority, viewModel)?.let { game ->
                        val favoritesContext = viewModel.favoritesOnly.value ||
                            viewModel.openPlatform.value == LibraryViewModel.FAVORITES_PLATFORM_ID
                        // In a filtered Favorites ring, removal is an identity mutation. Move authority
                        // to the next (or previous terminal) survivor before Room removes the current node,
                        // so unregister can never geometrically flash game one and then jump again.
                        if (game.isFavorite && favoritesContext) {
                            survivingGamelistGameAfterRemoval(viewModel.games.value, game)?.let { survivor ->
                                handOffGamelistFocus(authority, viewModel, survivor)
                            }
                        }
                        val nowFavorite = viewModel.toggleFavorite(game)
                        val wasLastVisibleFavorite = viewModel.games.value.none {
                            it.gameId != game.gameId && it.isFavorite
                        }
                        if (!nowFavorite && favoritesContext && wasLastVisibleFavorite) {
                            leaveEmptiedGamelist(authority, viewModel, nativeFavoriteGameRows, nativeFavoriteAppRows)
                        }
                    }
                }
                true
            }
            // X = show favorites / show all in a regular gamelist. In Favorites itself this is a no-op.
            ControllerAction.SECONDARY -> {
                val focusedGameId = focusedGameOrMetadata(authority, viewModel)?.gameId
                if (viewModel.toggleFavoritesOnly(focusedGameId)) {
                    // Stamp the destination before the reactive list unregisters its source. Normal→Favorites
                    // can land immediately because every Favorite is still registered in the Normal ring;
                    // Favorites→Normal settles after the restored non-Favorite key registers next frame.
                    viewModel.pendingGamelistFilterLanding.value?.targetGameId?.let { targetId ->
                        val target = FocusKey("library/gamelist/${targetId.value}")
                        authority.rememberFocusForScope(GamelistScope, target)
                        authority.requestFocus(target)
                    }
                }
                true
            }
            // SELECT used to open achievements here , and its own KDoc noted that
            // native favorites carry no ROM identity so the key was consumed without acting. That whole
            // branch is retired: achievements live in Settings, SELECT now marks favorites, and the
            // native segment is handled inside that block rather than opted out of.
            // START owns hide inside every gamelist and is always consumed so it cannot fall through to the
            // shell's Settings shortcut. The existing protective confirmation scopes remain unchanged.
            ControllerAction.SYSTEM_START -> {
                if (viewModel.isNativeFavoritesSegment()) {
                    focusedNativeFavoritePackage(authority)?.let { packageName ->
                        onRequestHideNativePackage(packageName)
                        pushScopeLandingOn(authority, NativeHideConfirmScope, NativeHideCancelKey)
                    }
                } else {
                    focusedGameOrMetadata(authority, viewModel)?.let { game ->
                        viewModel.requestHide(game)
                        pushScopeLandingOn(authority, HideConfirmScope, HideCancelKey)
                    }
                }
                true
            }
            // Back/Cancel returns to the platforms shelf (pop the gamelist scope + clear open platform).
            // Keyboard-open dismissal is quarantined before scope dispatch above.
            ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK -> {
                closeGamelist(authority, viewModel)
                true
            }
            else -> false
        }

        // ── Hide confirmation (hard modal over the gamelist) ──
        HideConfirmScope -> when (action) {
            ControllerAction.NAV_UP, ControllerAction.NAV_DOWN,
            ControllerAction.NAV_LEFT, ControllerAction.NAV_RIGHT -> {
                authority.moveFocus(action)
                true
            }
            ControllerAction.CONFIRM -> {
                when (authority.state.value.focused) {
                    HideConfirmKey ->
                        confirmLibraryHide(authority, viewModel, nativeFavoriteGameRows, nativeFavoriteAppRows)
                    HideCancelKey -> cancelLibraryHide(authority, viewModel)
                    else -> Unit
                }
                true
            }
            ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK -> {
                cancelLibraryHide(authority, viewModel)
                true
            }
            else -> true
        }

        NativeHideConfirmScope -> when (action) {
            ControllerAction.NAV_LEFT -> {
                authority.requestFocus(NativeHideCancelKey)
                true
            }
            ControllerAction.NAV_RIGHT -> {
                authority.requestFocus(NativeHideConfirmKey)
                true
            }
            ControllerAction.NAV_UP, ControllerAction.NAV_DOWN -> {
                authority.moveFocus(action)
                true
            }
            ControllerAction.CONFIRM -> {
                when (authority.state.value.focused) {
                    NativeHideConfirmKey -> {
                        authority.popScope()
                        confirmNativeHide(
                            authority = authority,
                            viewModel = viewModel,
                            nativeGameRows = nativeGameRows,
                            nativeFavoriteGameRows = nativeFavoriteGameRows,
                            nativeFavoriteAppRows = nativeFavoriteAppRows,
                            write = onConfirmHideNativePackage,
                        )
                    }
                    NativeHideCancelKey -> {
                        onCancelHideNativePackage()
                        authority.popScope()
                    }
                    else -> Unit
                }
                true
            }
            ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK -> {
                onCancelHideNativePackage()
                authority.popScope()
                true
            }
            else -> true
        }

        NativeAddPickerScope -> when (action) {
            ControllerAction.NAV_UP, ControllerAction.NAV_DOWN,
            ControllerAction.NAV_LEFT, ControllerAction.NAV_RIGHT -> {
                if (!onMoveNativeAddPickerFocus(action)) authority.moveFocus(action)
                true
            }
            ControllerAction.CONFIRM -> {
                when (val focused = authority.state.value.focused?.value) {
                    NativeAddPickerSearchKey.value -> onOpenNativeAddSearch()
                    NativeAddPickerCloseKey.value -> {
                        onDismissNativeAddPicker()
                        authority.popScope()
                    }
                    null -> Unit
                    else -> nativeAddPackageFromFocusKey(focused)?.let { packageName ->
                        authority.popScope()
                        onChooseNativeAddPackage(packageName)
                    }
                }
                true
            }
            ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK -> {
                onDismissNativeAddPicker()
                authority.popScope()
                true
            }
            else -> true
        }

        // ── Open-With picker (a hard modal opened from typed detail metadata) ──
        LaunchChoiceScope -> when (action) {
            ControllerAction.NAV_UP, ControllerAction.NAV_DOWN -> {
                authority.moveFocus(action)
                true
            }
            ControllerAction.CONFIRM -> {
                val game = viewModel.detailGame.value
                val choice = launchChoiceFor(authority.state.value.focused)
                if (game != null && choice != null) {
                    authority.popScope()
                    when (choice) {
                        LaunchChoice.GroupDefault -> viewModel.launch(game)
                        is LaunchChoice.Disc -> viewModel.launch(game, preferredDisc = choice.number)
                        is LaunchChoice.Version ->
                            viewModel.launch(game, launchMemberEntryKey = choice.entryKey)
                    }
                }
                true
            }
            ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK, ControllerAction.NAV_LEFT -> {
                authority.popScope()
                true
            }
            // Hard modal, same as Open-With: swallow everything else so nothing reaches the card behind it.
            else -> true
        }

        OpenWithScope -> when (action) {
            ControllerAction.NAV_UP, ControllerAction.NAV_DOWN -> {
                authority.moveFocus(action)
                true
            }
            ControllerAction.CONFIRM -> {
                val focused = authority.state.value.focused?.value
                val game = viewModel.detailGame.value
                when {
                    focused == "library/openWith/auto" -> {
                        game?.let { viewModel.resetEmulator(it) }
                        authority.popScope()
                    }
                    focused != null && focused.startsWith("library/openWith/emu/") -> {
                        val emuId = focused.removePrefix("library/openWith/emu/")
                        val candidate = viewModel.openWith.value?.candidates?.firstOrNull { it.emulatorId.value == emuId }
                        // An absent or unwired candidate is non-committable (no dead launch).
                        if (candidate != null && candidate.selectable && game != null) {
                            viewModel.chooseEmulator(game, candidate)
                            authority.popScope()
                        }
                    }
                }
                true
            }
            ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK, ControllerAction.NAV_LEFT -> {
                viewModel.closeOpenWith()
                authority.popScope()
                true
            }
            else -> true // hard modal: swallow everything else so nothing leaks to the gamelist behind it
        }

        else -> false
    }
}

private fun moveLibrarySearchFocus(
    authority: FocusAuthority,
    viewModel: LibraryViewModel,
    action: ControllerAction,
): Boolean {
    val session = viewModel.librarySearchSession.value ?: return false
    val top = buildList {
        add(LibrarySearchQueryControlKey)
        add(LibrarySearchGenreFilterKey)
        if (session.origin == LibrarySearchOrigin.FavoriteGames) add(LibrarySearchPlatformFilterKey)
    }
    val results = viewModel.games.value.map { librarySearchGameResultKey(it.gameId) }
    val clearVisible = session.criteria != LibrarySearchCriteria()
    val current = authority.state.value.focused
    val rememberedResult = session.resultScrollAnchor
        ?.let { it as? LibrarySearchResultIdentity.Game }
        ?.gameId
        ?.let(::librarySearchGameResultKey)
        ?.takeIf(results::contains)
    val rememberedTop = librarySearchTopControlKey(viewModel.librarySearchLastTopFocus.value)
        .takeIf(top::contains)
        ?: LibrarySearchQueryControlKey
    val next = when {
        current in top -> {
            val index = top.indexOf(current)
            when (action) {
                ControllerAction.NAV_LEFT -> top[(index - 1).coerceAtLeast(0)]
                ControllerAction.NAV_RIGHT -> top[(index + 1).coerceAtMost(top.lastIndex)]
                ControllerAction.NAV_DOWN -> rememberedResult
                    ?: results.firstOrNull()
                    ?: LibrarySearchClearKey.takeIf { clearVisible }
                    ?: current
                ControllerAction.NAV_UP -> current
                else -> current
            }
        }
        current in results -> {
            val index = results.indexOf(current)
            when (action) {
                ControllerAction.NAV_UP -> results.getOrNull(index - 1) ?: rememberedTop
                ControllerAction.NAV_DOWN -> results.getOrNull(index + 1)
                    ?: LibrarySearchClearKey.takeIf { clearVisible }
                    ?: current
                ControllerAction.NAV_LEFT, ControllerAction.NAV_RIGHT -> current
                else -> current
            }
        }
        current == LibrarySearchClearKey -> when (action) {
            ControllerAction.NAV_UP -> results.lastOrNull() ?: top.last()
            else -> current
        }
        else -> LibrarySearchQueryControlKey
    }
    if (next != null && next != current) authority.requestFocus(next)
    next?.let { key ->
        librarySearchFocusIdentity(key)?.let(viewModel::rememberLibrarySearchFocus)
    }
    return true
}

private fun librarySearchTopControlKey(focus: LibrarySearchFocusIdentity): FocusKey = when (focus) {
    LibrarySearchFocusIdentity.Query -> LibrarySearchQueryControlKey
    LibrarySearchFocusIdentity.Genre -> LibrarySearchGenreFilterKey
    LibrarySearchFocusIdentity.Platform -> LibrarySearchPlatformFilterKey
    else -> LibrarySearchQueryControlKey
}

internal fun librarySearchFocusKey(focus: LibrarySearchFocusIdentity): FocusKey = when (focus) {
    LibrarySearchFocusIdentity.Query -> LibrarySearchQueryControlKey
    LibrarySearchFocusIdentity.Genre -> LibrarySearchGenreFilterKey
    LibrarySearchFocusIdentity.Platform -> LibrarySearchPlatformFilterKey
    LibrarySearchFocusIdentity.Clear -> LibrarySearchClearKey
    is LibrarySearchFocusIdentity.Result -> when (val identity = focus.identity) {
        is LibrarySearchResultIdentity.Game -> librarySearchGameResultKey(identity.gameId)
        is LibrarySearchResultIdentity.NativePackage ->
            FocusKey("library/search/result/native/${identity.packageName}")
    }
}

private fun librarySearchFocusIdentity(key: FocusKey): LibrarySearchFocusIdentity? = when (key) {
    LibrarySearchQueryControlKey -> LibrarySearchFocusIdentity.Query
    LibrarySearchGenreFilterKey -> LibrarySearchFocusIdentity.Genre
    LibrarySearchPlatformFilterKey -> LibrarySearchFocusIdentity.Platform
    LibrarySearchClearKey -> LibrarySearchFocusIdentity.Clear
    else -> librarySearchResultGameId(key)?.let { gameId ->
        LibrarySearchFocusIdentity.Result(LibrarySearchResultIdentity.Game(gameId))
    }
}

private fun applyFocusedLibrarySearchPickerOption(
    authority: FocusAuthority,
    viewModel: LibraryViewModel,
) {
    when (viewModel.librarySearchPicker.value) {
        LibrarySearchPicker.GENRE -> {
            val focused = authority.state.value.focused
            val option = viewModel.availableSearchFacets.value.genres
                .firstOrNull { librarySearchGenrePickerKey(it) == focused }
            when {
                focused == librarySearchGenrePickerKey(null) ->
                    viewModel.setSearchGenre(LibrarySearchGenreFilter.All)
                option != null ->
                    viewModel.setSearchGenre(LibrarySearchGenreFilter.Exact(option.key))
                else -> return
            }
        }
        LibrarySearchPicker.PLATFORM -> {
            val focused = authority.state.value.focused
            val platform = viewModel.availableSearchFacets.value.platforms
                .firstOrNull { librarySearchPlatformPickerKey(it) == focused }
            when {
                focused == librarySearchPlatformPickerKey(null) ->
                    viewModel.setSearchPlatform(LibrarySearchPlatformFilter.All)
                platform != null ->
                    viewModel.setSearchPlatform(LibrarySearchPlatformFilter.Exact(platform))
                else -> return
            }
        }
        null -> return
    }
    closeLibrarySearchPicker(authority, viewModel)
}

internal fun closeLibrarySearchPicker(
    authority: FocusAuthority,
    viewModel: LibraryViewModel,
) {
    val parent = when (viewModel.librarySearchPicker.value) {
        LibrarySearchPicker.GENRE -> LibrarySearchGenreFilterKey
        LibrarySearchPicker.PLATFORM -> LibrarySearchPlatformFilterKey
        null -> LibrarySearchQueryControlKey
    }
    viewModel.closeLibrarySearchPicker()
    authority.popScope()
    authority.requestFocus(parent)
}

internal fun closeLibrarySearch(
    authority: FocusAuthority,
    viewModel: LibraryViewModel,
) {
    val origin = viewModel.closeLibrarySearch()
    authority.popScope()
    val target = (origin as? LibrarySearchOriginFocusIdentity.Result)
        ?.identity
        ?.let { it as? LibrarySearchResultIdentity.Game }
        ?.gameId
        ?.takeIf { gameId -> viewModel.games.value.any { it.gameId == gameId } }
        ?.let { gameId -> FocusKey("library/gamelist/${gameId.value}") }
        ?: LibrarySearchLauncherKey
    authority.rememberFocusForScope(GamelistScope, target)
    authority.requestFocus(target)
}

internal fun activateLibrarySearchGame(
    authority: FocusAuthority,
    viewModel: LibraryViewModel,
    game: GameRow,
    onOpenLibraryDetail: ((LibraryDetailSession) -> Unit)?,
) {
    viewModel.closeLibrarySearch()
    authority.popScope()
    val baseKey = gameKey(game)
    authority.rememberFocusForScope(GamelistScope, baseKey)
    authority.requestFocus(baseKey)
    openTypedLibraryDetail(
        authority = authority,
        viewModel = viewModel,
        target = LibraryDetailTarget.Emulated(game),
        onOpen = { session -> onOpenLibraryDetail?.invoke(session) },
    )
}

/** Open a platform's gamelist: set the VM's open platform + push [GamelistScope] so its tiles get focus. */
private fun openGamelist(authority: FocusAuthority, viewModel: LibraryViewModel, id: PlatformId) {
    viewModel.openPlatform(id)
    authority.pushScope(GamelistScope)
}

private fun LibraryViewModel.isNativeFavoritesSegment(): Boolean =
    openPlatform.value == LibraryViewModel.FAVORITES_PLATFORM_ID &&
        favoriteSegment.value != FavoriteSegment.GAMES

private fun favoriteSegmentsFor(
    romFavoritesCount: Int,
    nativeGameRows: List<NativeShelfRow>,
    nativeAppRows: List<NativeShelfRow>,
): List<FavoriteSegment> = buildList {
    if (romFavoritesCount > 0) add(FavoriteSegment.GAMES)
    if (nativeGameRows.isNotEmpty()) add(FavoriteSegment.ANDROID_GAMES)
    if (nativeAppRows.isNotEmpty()) add(FavoriteSegment.ANDROID_APPS)
}

internal fun nativeFallbackFavoriteSegment(
    nativeFavoriteGameRows: List<NativeShelfRow>,
    nativeFavoriteAppRows: List<NativeShelfRow>,
): FavoriteSegment? = when {
    nativeFavoriteGameRows.isNotEmpty() -> FavoriteSegment.ANDROID_GAMES
    nativeFavoriteAppRows.isNotEmpty() -> FavoriteSegment.ANDROID_APPS
    else -> null
}

private fun focusedNativeFavoritePackage(authority: FocusAuthority): String? =
    authority.state.value.focused?.value?.let(::nativePackageFromFocusKey)

private fun focusedNativeFavoriteRow(
    authority: FocusAuthority,
    viewModel: LibraryViewModel,
    nativeGameRows: List<NativeShelfRow>,
    nativeAppRows: List<NativeShelfRow>,
): NativeShelfRow? {
    val packageName = focusedNativeFavoritePackage(authority) ?: return null
    val rows = when (viewModel.favoriteSegment.value) {
        FavoriteSegment.GAMES -> return null
        FavoriteSegment.ANDROID_GAMES -> nativeGameRows
        FavoriteSegment.ANDROID_APPS -> nativeAppRows
    }
    return rows.firstOrNull { it.packageName == packageName }
}

internal fun enterLibraryDetail(
    authority: FocusAuthority,
    target: LibraryDetailTarget,
    onOpen: (LibraryDetailSession) -> Unit,
): Boolean {
    val session = captureLibraryDetailSession(authority, target) ?: return false
    authority.rememberFocusForScope(session.originScope, session.originFocusKey)
    onOpen(session)
    return restoreLibraryDetailSession(authority, session)
}

/** Reconciles a still-visible launch card with its focus scope after an external activity returns. */
fun restoreLibraryDetailSession(
    authority: FocusAuthority,
    session: LibraryDetailSession,
): Boolean {
    val currentScope = authority.state.value.scope
    if (currentScope != session.originScope && currentScope != GameDetailScope) return false

    authority.rememberFocusForScope(session.originScope, session.originFocusKey)
    authority.rememberFocusForScope(GameDetailScope, LibraryDetailPlayKey)
    registerHomeLaunchCardActions(
        authority = authority,
        emulated = session.target is LibraryDetailTarget.Emulated,
        contract = LibraryLaunchCardFocusContract,
    )
    if (currentScope == session.originScope) authority.pushScope(GameDetailScope)
    authority.requestFocus(LibraryDetailPlayKey)
    return authority.state.value.scope == GameDetailScope &&
        authority.state.value.focused == LibraryDetailPlayKey
}

/** Rebuilds the retained Library surface stack when the destination remounts after global navigation. */
internal fun reconcileLibraryEntryScope(
    authority: FocusAuthority,
    openPlatform: PlatformId?,
    detailSession: LibraryDetailSession?,
): Boolean {
    enterLibraryBaseScope(authority)
    if (openPlatform != null && authority.state.value.scope == LibraryShelfScope) {
        authority.pushScope(GamelistScope)
    }

    val expectedOrigin = if (openPlatform == null) LibraryShelfScope else GamelistScope
    val validSession = detailSession?.takeIf { it.originScope == expectedOrigin }
    if (validSession != null && authority.state.value.scope == expectedOrigin) {
        restoreLibraryDetailSession(authority, validSession)
    }

    val expectedScope = if (validSession == null) expectedOrigin else GameDetailScope
    return authority.state.value.scope == expectedScope
}

/** Shared touch/controller game activation. Presentation placement must not change the Play/Add state machine. */
internal fun openTypedLibraryDetail(
    authority: FocusAuthority,
    viewModel: LibraryViewModel,
    target: LibraryDetailTarget,
    onOpen: (LibraryDetailSession) -> Unit,
): Boolean = enterLibraryDetail(
    authority = authority,
    target = target,
) { session ->
    if (target is LibraryDetailTarget.Emulated) viewModel.openGameDetail(target.game)
    onOpen(session)
}

fun exitLibraryDetail(
    authority: FocusAuthority,
    target: LibraryDetailTarget?,
) {
    authority.popScope()
    target?.sourceFocusKey?.let(authority::requestFocus)
    unregisterLibraryDetailSessionActions(authority)
}

internal fun launchNativePackage(
    viewModel: LibraryViewModel,
    packageName: String,
    onLaunchNativePackage: (String) -> Unit,
    restoreLaunchCard: Boolean = false,
) {
    viewModel.noteNativePackageLaunch(packageName, restoreLaunchCard)
    onLaunchNativePackage(packageName)
}

/** Close the gamelist: pop back to the shelf scope + clear the VM's open platform. */
private fun closeGamelist(authority: FocusAuthority, viewModel: LibraryViewModel) {
    authority.popScope()
    viewModel.closeGamelist()
}

/**
 * Confirm the START-hide modal and return focus before the row leaves composition.
 *
 * Walk 32g : hide a game from a gamelist and its card went with no motion, the focus fell through to
 * game one for a frame, and the hero art went with it. Hiding now does what SELECT already does when it un-stars a game
 * in a favourites view, and in the same order: the focus is handed to the card that stays, the next one along or else the
 * one before, BEFORE the write, so the moment the list drops the hidden game the authority already names a survivor and
 * cannot geometrically flash game one and then jump again ([handOffGamelistFocus]). When the game that leaves is the last
 * one the list has, the gamelist leaves with it by SELECT's rule ([leaveEmptiedGamelist]).
 *
 * The survivor is taken from the list the player sees, without what is already on its way out, so a hide that follows a
 * hide cannot hand focus to a game that is no longer there. Focus is handed off unless the player is on a different game
 * card: a touch confirm can name a card they are not on, and that must not pull focus off the card they are on. (Focus on
 * the artwork pill still hands off, because the game the pill serves is the one that is leaving.)
 */
internal fun confirmLibraryHide(
    authority: FocusAuthority,
    viewModel: LibraryViewModel,
    nativeFavoriteGameRows: List<NativeShelfRow> = emptyList(),
    nativeFavoriteAppRows: List<NativeShelfRow> = emptyList(),
) {
    authority.popScope()
    val leaving = viewModel.pendingHideGame.value ?: return
    val pending = viewModel.pendingRemovals.value
    val shown = viewModel.games.value.filterNot { it.gameId in pending }
    val onAnotherCard = focusedGame(authority, viewModel)?.takeIf { it.gameId != leaving.gameId } != null
    val survivor = if (onAnotherCard) null else survivingGamelistGameAfterRemoval(shown, leaving)
    viewModel.confirmPendingHide(beforeWrite = { survivor?.let { handOffGamelistFocus(authority, viewModel, it) } })
    // The list held only this game: nothing stays to hand focus to, and the list has nothing left to show.
    if (shown.singleOrNull()?.gameId == leaving.gameId) {
        leaveEmptiedGamelist(authority, viewModel, nativeFavoriteGameRows, nativeFavoriteAppRows)
    }
}

/**
 * Move the authority, and the metadata seat, onto [survivor] BEFORE the list unregisters the card that is leaving.
 * `rememberFocusForScope` stamps the scope's memory too, so a re-registration finds the survivor and not a stale key.
 */
internal fun handOffGamelistFocus(authority: FocusAuthority, viewModel: LibraryViewModel, survivor: GameRow) {
    val survivorKey = gameKey(survivor)
    viewModel.focusMetadata(survivor)
    authority.rememberFocusForScope(GamelistScope, survivorKey)
    authority.requestFocus(survivorKey)
}

/**
 * The last game the list held has gone (hidden, or un-starred out of a favourites view). Favorites falls back to a native
 * segment that still has rows; every other list, and Favorites with nothing to fall back to, closes.
 */
internal fun leaveEmptiedGamelist(
    authority: FocusAuthority,
    viewModel: LibraryViewModel,
    nativeFavoriteGameRows: List<NativeShelfRow>,
    nativeFavoriteAppRows: List<NativeShelfRow>,
) {
    val nativeFallback = if (viewModel.openPlatform.value == LibraryViewModel.FAVORITES_PLATFORM_ID) {
        nativeFallbackFavoriteSegment(
            nativeFavoriteGameRows = nativeFavoriteGameRows,
            nativeFavoriteAppRows = nativeFavoriteAppRows,
        )
    } else {
        null
    }
    if (nativeFallback != null) {
        viewModel.setFavoriteSegment(nativeFallback)
    } else {
        closeGamelist(authority, viewModel)
    }
}

/**
 * Confirm the native "Hide from Shelf" modal (the shelf's Android tile, or a Favorites row of an Android game or app), in
 * the order [confirmLibraryHide] gives an emulated game and for the same reason (walk-32h): the card the player is on is
 * broken where it stands, then the focus is handed to the card that stays, and only then is the ONE write made, so the
 * moment the shelf drops the hidden package the authority already names a survivor and cannot land on the first card.
 * Called after the modal's scope has been popped, so the scope here is the surface the hide happens in.
 *
 * The package is the one the player is on: START asked for the hide from the focused card and the modal holds focus in
 * its own scope meanwhile, so the surface's remembered focus is still that card. The survivor is taken from the list the
 * player sees ([nativeRowsShownForHide]). When the package cannot be resolved (nothing native has focus) nothing is handed
 * off, the write is still made and the hide still says where the package went, at once.
 */
internal fun confirmNativeHide(
    authority: FocusAuthority,
    viewModel: LibraryViewModel,
    nativeGameRows: List<NativeShelfRow>,
    nativeFavoriteGameRows: List<NativeShelfRow>,
    nativeFavoriteAppRows: List<NativeShelfRow>,
    write: () -> Unit,
) {
    val scope = authority.state.value.scope
    val leaving = focusedNativeFavoritePackage(authority)
    val shown = nativeRowsShownForHide(scope, viewModel, nativeGameRows, nativeFavoriteGameRows, nativeFavoriteAppRows)
    val survivor = leaving?.let { survivingNativeRowAfterRemoval(shown, it) }
    viewModel.hideNativePackage(
        packageName = leaving.orEmpty(),
        beforeWrite = { survivor?.let { handOffNativeFocus(authority, scope, it) } },
        write = write,
    )
}

/**
 * The native rows the player sees where a hide happens, in the order they are drawn: the ANDROID GAMES ring on the shelf,
 * or the native segment's rows in Favorites ([nativeFavoriteRowsShown], narrowed by the search the list is showing).
 */
internal fun nativeRowsShownForHide(
    scope: FocusScope,
    viewModel: LibraryViewModel,
    nativeGameRows: List<NativeShelfRow>,
    nativeFavoriteGameRows: List<NativeShelfRow>,
    nativeFavoriteAppRows: List<NativeShelfRow>,
): List<NativeShelfRow> = when {
    scope == LibraryShelfScope -> nativeGameRows
    scope == GamelistScope && viewModel.isNativeFavoritesSegment() -> nativeFavoriteRowsShown(
        segment = viewModel.favoriteSegment.value,
        nativeFavoriteGameRows = nativeFavoriteGameRows,
        nativeFavoriteAppRows = nativeFavoriteAppRows,
        searchQuery = viewModel.searchQuery.value,
    )
    else -> emptyList()
}

/** Stable neighbour for a native removal: the row that shifts into the removed slot, else its predecessor. */
internal fun survivingNativeRowAfterRemoval(rows: List<NativeShelfRow>, removedPackage: String): NativeShelfRow? {
    val index = rows.indexOfFirst { it.packageName == removedPackage }
    if (index < 0) return null
    return rows.getOrNull(index + 1) ?: rows.getOrNull(index - 1)
}

/**
 * Move the authority onto [survivor] BEFORE the shelf or the list unregisters the card that is leaving.
 * `rememberFocusForScope` stamps the scope's memory too, so a re-registration finds the survivor and not a stale key.
 */
internal fun handOffNativeFocus(authority: FocusAuthority, scope: FocusScope, survivor: NativeShelfRow) {
    val survivorKey = nativeShelfKey(survivor.packageName)
    authority.rememberFocusForScope(scope, survivorKey)
    authority.requestFocus(survivorKey)
}

/** Cancel the START-hide modal and restore gamelist focus. */
internal fun cancelLibraryHide(authority: FocusAuthority, viewModel: LibraryViewModel) {
    viewModel.clearPendingHide()
    authority.popScope()
}

/** The dedup-collapsed game whose gamelist tile is focused ("library/gamelist/<gameId>"), if any. */
private fun focusedGame(authority: FocusAuthority, viewModel: LibraryViewModel): GameRow? {
    // One lookup in the list's key index (RC-45), where this walked the list comparing ids on every press. A focused key that is not
    // "library/gamelist/<gameId>" of a game in the list names no game, as it did.
    val focused = authority.state.value.focused ?: return null
    return GamelistKeyIndex.of(viewModel.games.value).gameFor(focused)
}

/** Stable neighbor for a list mutation: the item that shifts into the removed slot, else its predecessor. */
internal fun survivingGamelistGameAfterRemoval(games: List<GameRow>, removed: GameRow): GameRow? {
    val index = games.indexOfFirst { it.gameId == removed.gameId }
    if (index < 0) return null
    return games.getOrNull(index + 1) ?: games.getOrNull(index - 1)
}

/**
 * Open the disc / version menu for [game], or return false when the group offers no choice.
 *
 * The emptiness of [launchChoiceItems] IS the trigger test — one source of truth for "is there anything
 * to pick", shared with the overlay that renders those same rows. A separate boolean here would be a
 * second law that could disagree with the menu, and the disagreement would show up as either a modal
 * with one row in it or a silent launch where the player expected to choose.
 */
internal fun openLaunchChoice(
    authority: FocusAuthority,
    viewModel: LibraryViewModel,
    game: GameRow,
): Boolean {
    if (!launchChoiceAvailable(viewModel.gameDetailMembers.value)) return false
    authority.pushScope(LaunchChoiceScope)
    return true
}

private fun focusedGameOrMetadata(authority: FocusAuthority, viewModel: LibraryViewModel): GameRow? {
    // The artwork action keeps the hero game selected, so row-level shortcuts still target that game rather
    // than falling through to nothing.
    val focused = authority.state.value.focused
    return if (focused == GamelistGetArtKey) {
        // Seat owner, not detailGame directly: a null-metadata arrival must not make row-level
        // shortcuts fall through to nothing (gate F1, same class as the seat's exits).
        gamelistMediaSeatOwner(viewModel)
    } else {
        focusedGame(authority, viewModel)
    }
}

/** Recover the [PlatformId] from a focused platform tile key ("library/shelf/<id>"). */
private fun focusedPlatformId(focusedKey: String): PlatformId? {
    val prefix = "library/shelf/"
    if (!focusedKey.startsWith(prefix)) return null
    val id = focusedKey.removePrefix(prefix)
    if (id.isBlank() || id == "add") return null
    return PlatformId(id)
}

/**
 * The ordered ring for [mode] -- shared by [moveShelfListFocus] (movement), [focusedShelfRingKey] (the
 * CONFIRM/TERTIARY/SYSTEM_SELECT membership check, current-ring membership), and the mode-switch focus
 * stamp ([cycleShelfModeWithFocusStamp]): one clock for "what is this mode's ring right now" instead of
 * three independent re-derivations. The emulated PLATFORM selector is the one deliberately circular
 * Library ring; native Android rows stay finite. For Android modes the caller's rows can arrive empty at
 * dispatch, so fall back to the keys the render registered in this scope -- reducer order stays identical
 * to paint order without handing movement to a second focus engine. (Production always passes real
 * `nativeGameRows` -- `MimirAppInputDispatch.kt`'s `nativeGameRows = snapshot.games` -- so this fallback
 * is a defensive floor for tests/edge cases, not the production path.)
 */
private fun shelfRingKeys(
    mode: LibraryShelfMode,
    authority: FocusAuthority,
    scope: FocusScope,
    platformRows: List<PlatformRow>,
    nativeGameRows: List<NativeShelfRow>,
): List<FocusKey> {
    fun nativeRing(rows: List<NativeShelfRow>): List<FocusKey> =
        rows.map { nativeShelfKey(it.packageName) }.ifEmpty {
            authority.registeredKeysInScope(scope).filter { it.value.startsWith(NativeShelfKeyPrefix) }
        }
    return when (mode) {
        LibraryShelfMode.EMULATION -> platformRows.map { platformShelfKey(it.platformId) }
        LibraryShelfMode.ANDROID_GAMES -> nativeRing(nativeGameRows)
    }
}

/**
 * the live focused key, but only if it is still a member of the CURRENT mode's
 * ring -- mirrors Home's `entries.any { it.focusKey == key }` pre-action guard
 * (`dispatchHomeContentAction`, e.g. `HomeShelfModels.kt:429-432, 465-469, 481-484`). A stale cross-mode
 * or since-removed key must not resolve to a real action.
 */
private fun focusedShelfRingKey(
    authority: FocusAuthority,
    viewModel: LibraryViewModel,
    platformRows: List<PlatformRow>,
    nativeGameRows: List<NativeShelfRow>,
): FocusKey? {
    val scope = authority.state.value.scope
    val ring = shelfRingKeys(viewModel.shelfMode.value, authority, scope, platformRows, nativeGameRows)
    return authority.state.value.focused?.takeIf { it in ring }
}

private fun moveShelfListFocus(
    authority: FocusAuthority,
    viewModel: LibraryViewModel,
    action: ControllerAction,
    platformRows: List<PlatformRow>,
    nativeGameRows: List<NativeShelfRow>,
): Boolean {
    val delta = when (action) {
        ControllerAction.NAV_LEFT -> -1
        ControllerAction.NAV_RIGHT -> 1
        else -> return false
    }
    val shelfScope = authority.state.value.scope
    val list = shelfRingKeys(viewModel.shelfMode.value, authority, shelfScope, platformRows, nativeGameRows)
    if (list.isEmpty()) return false
    val current = authority.state.value.focused?.let(list::indexOf) ?: -1
    if (current < 0) {
        // Off-ring / unresolvable focus -- a stale cross-mode key, the mode-switch registration gap, or
        // genuinely null focus (Home's moveHomeShelfEntryFocus, HomeShelfModels.kt:555-574, self-heals
        // all three the same way via its own `current = -1` fallthrough). Home's rule: self-heal to
        // index 0 and NEVER fall through to the geometric engine for ring traversal -- returning `true`
        // here (not `false`) is what keeps the caller (LEFT/RIGHT branch above) from reaching
        // `authority.moveFocus(action)`, closing the BUG-3/BUG-4 mixed physical/synthetic-bounds hazard
        // for this axis.
        //
        // Write-through, remember-then-request -- Home's own order (moveHomeShelfEntryFocus stamps
        // `rememberFocusForScope` BEFORE `requestFocus`). The target is stamped into
        // registration-independent memory FIRST, into TWO places: `rememberFocusForScope`'s map (shape
        // parity with Home; also read by `popScope`/`maybeSeedFocusOnRegister`), and the ViewModel's
        // `centeredShelfKey` (BUG-3 precedent, `setCenteredShelfKey`) which, unlike FocusAuthority's own
        // `focusMemory`, SURVIVES a Compose dispose/re-mount cascade --
        // `VirtualFocusRegistrationTracker.clearAndRestoreFocus` (BoundedVirtualFocusRegistration.kt)
        // re-stamps `focusMemory` from the pre-clear LIVE focus when the outgoing ring tears down, in the
        // SAME recompose pass that mounts the incoming one, which would otherwise clobber a
        // `rememberFocusForScope`-only stamp before the landing effect's first frame ever runs. So a
        // `requestFocus` that no-ops here (ring not yet mounted -- documented no-op,
        // `FocusAuthority.kt:103-104`) still leaves a correct landing target for `LibraryScreen`'s
        // `shelfModeLandingTarget` to read.
        val target = list.first()
        viewModel.setCenteredShelfKey(target)
        authority.rememberFocusForScope(shelfScope, target)
        authority.requestFocus(target)
        return true
    }
    val next = if (viewModel.shelfMode.value == LibraryShelfMode.EMULATION) {
        Math.floorMod(current + delta, list.size)
    } else {
        (current + delta).coerceIn(0, list.lastIndex)
    }
    if (next != current) authority.requestFocus(list[next])
    return true
}

/**
 * Mode-switch focus ordering (CP04): a bare `cycleShelfMode` flips `_shelfMode` synchronously but Compose does not
 * recompose synchronously with it -- the destination ring's tiles register on the NEXT recomposition
 * (`RegisterVirtualFocusRow`'s `SideEffect`, `BoundedVirtualFocusRegistration.kt:244-251`), which a
 * same-tick input burst (mode switch immediately followed by a NAV or CONFIRM press) can outrun. The
 * destination ring's first key is stamped into `viewModel.centeredShelfKey` in the SAME reducer step as
 * the flip. That stamp is a FALLBACK seed for a mode's first-ever visit this session only:
 * `LibraryScreen`'s mode-switch landing effect (the `LaunchedEffect(authority, shelfMode, modeStopKeys)`
 * reading `shelfModeLandingTarget`) prefers the render's own per-mode memory (`rememberedKeysByMode`, a
 * real past visit) over this stamp whenever both exist -- policy 2026-09-02, "MEMORY WINS": a
 * racing NAV lands on the tile the player was last centred on IN THAT MODE, not tile one of the ring. See
 * `shelfModeRememberedLandingKey` in `LibraryScreen.kt` for the precedence, pinned in
 * `ShelfModeMemoryTest`.
 *
 * CP05 (2026-09-02): `requestFocus(target)` below is a documented, harmless no-op
 * (`FocusAuthority.kt:131-135`) when the destination ring is not yet registered -- which is the normal
 * case in this same-tick window. Earlier code stopped there and left the OUTGOING mode's live focus
 * untouched, on the theory that an unresolvable stale key is harmless because every dispatch arm that
 * reads it does a ring-membership check first. That theory was wrong for exactly one arm: the
 * `"library/shelf/add"` Add-pill CONFIRM arm has no membership check (it cannot -- the pill is not a ring
 * member by design), and Thor testing found the pill's key is *not* just stale-and-ignored during
 * this window -- `DefaultFocusAuthority.removeEntryAndFallback`'s nearest-remaining-sibling rule
 * actively RE-FOCUSES the pill once the outgoing tile unregisters, because the pill is the one node in
 * `LibraryShelfScope` that never unmounts across a mode switch. A same-tick CONFIRM then read that as
 * "the player chose the pill in the new mode" and fired its action -- opening the Add picker or leaving
 * the Library for the Files browser with no such input from the player
 * (reported Delta 3 observations: 3/3 and 1/1).
 *
 * `authority.clearFocus()` closes this at the source: live focus drops to null in the SAME reducer step
 * as the flip, strictly before Compose's later teardown of the outgoing ring can run
 * `removeEntryAndFallback` against it, so that fallback's `if (_state.value.focused == key)` guard can
 * never match and the pill is never silently re-focused. A same-tick CONFIRM then reads `focused == null`
 * (the arm above already treats that as "nothing chosen yet" -- true here too) instead of the pill's
 * literal key. It has the same effect on a same-tick NAV burst: [moveShelfListFocus]'s self-heal branch
 * now deterministically runs (current index is always -1 against the destination ring), landing on the
 * destination ring's first member rather than depending on whether the pill's own no-op guard happened to
 * intercept first.
 *
 * **Deliberately does NOT call `authority.rememberFocusForScope(shelfScope, target)` any more.**
 * `clearFocus()` parks a sentinel in `FocusAuthority`'s OWN focus memory precisely so
 * `maybeSeedFocusOnRegister` cannot auto-seed a re-registering tile -- and calling
 * `rememberFocusForScope` right after would overwrite that sentinel with `target` (the ring-first key),
 * re-arming auto-seed against it. The destination ring's tiles register on the NEXT recomposition
 * (`SideEffect`, synchronous within that apply pass); the landing effect's `withFrameNanos` does not
 * resume until the frame boundary AFTER that. So an un-suppressed auto-seed would grab `target`
 * SYNCHRONOUSLY, before the landing effect's first iteration ever reads focus state -- and
 * `shelfModeLandingTarget`'s `if (focusedKey in orderedStopKeys) return null` treats that pre-empted
 * focus as authoritative, so the effect would never apply render memory. That is a live regression of the
 * render-memory precedence above (every switch where per-mode memory exists and differs from tile one --
 * the 6/6 discriminating population), found in source review rather than by
 * any of the unit pins (they exercise the pure precedence function or a dispatch-only path that never
 * registers the destination ring, so neither sees the registration-time auto-seed). Leaving the sentinel
 * parked keeps auto-seed suppressed for this scope until something ELSE genuinely focuses (any
 * `requestFocus`/`moveFocus` success calls `setFocus`, which overwrites the sentinel with a real key) --
 * which is exactly what the landing effect's own explicit `requestFocus(target)` does once the ring
 * registers, whether the effect chose `target` (first visit) or a remembered tile (every later visit).
 */
private fun cycleShelfModeWithFocusStamp(
    authority: FocusAuthority,
    viewModel: LibraryViewModel,
    platformRows: List<PlatformRow>,
    nativeGameRows: List<NativeShelfRow>,
    offset: Int,
) {
    viewModel.cycleShelfMode(offset)
    val shelfScope = authority.state.value.scope
    val target = shelfRingKeys(viewModel.shelfMode.value, authority, shelfScope, platformRows, nativeGameRows)
        .firstOrNull()
    // Drop stale live focus UNCONDITIONALLY, before checking whether a destination target even exists --
    // an empty destination ring (nothing to land on yet, see LIB-02's `emptyStopKey` fallback) must not
    // leave the outgoing mode's key (or the pill) as live focus either. See the CP05 paragraph above.
    authority.clearFocus()
    target ?: return
    viewModel.setCenteredShelfKey(target)
    authority.requestFocus(target)
}

/**
 * A TAP on a mode tab (cert 2026-10-08, A1): the same switch R1/L1 run, by the same focus-stamp path
 * ([cycleShelfModeWithFocusStamp]) -- never a bare [LibraryViewModel.setShelfMode], which flips the mode but leaves the
 * outgoing ring's key as live focus (see its CP05 paragraph). The offset is the signed distance between the two modes, so
 * [LibraryViewModel.lastShelfModeStep] gets the same +1/-1 the shoulder buttons would write and the page glides in from
 * the matching side.
 *
 * No-ops (returns false) when [mode] is already showing, and when the shelf scope does not own input: the stamp reads the
 * ACTIVE scope as "the shelf" and clears its focus, so run from the dock it would blank the dock's focus; R1/L1 are
 * no-ops there too (the dispatch routes only [LibraryShelfScope]).
 */
internal fun selectShelfModeWithFocusStamp(
    authority: FocusAuthority,
    viewModel: LibraryViewModel,
    platformRows: List<PlatformRow>,
    nativeGameRows: List<NativeShelfRow>,
    mode: LibraryShelfMode,
): Boolean {
    val current = viewModel.shelfMode.value
    if (mode == current || authority.state.value.scope != LibraryShelfScope) return false
    cycleShelfModeWithFocusStamp(authority, viewModel, platformRows, nativeGameRows, offset = mode.ordinal - current.ordinal)
    return true
}

/**
 * Item 6: DOWN off the Add pill returns to the tile the coverflow is centered on — the key onCenter stamps
 * into shelf-scope memory — instead of a geometric move that would score the leftmost tile (Add carries
 * physical bounds, tiles synthetic virtual ones). Returns false (no-op) when no valid remembered tile is
 * registered, so the caller falls back to the normal move.
 */
private fun focusRememberedShelfTile(authority: FocusAuthority, viewModel: LibraryViewModel): Boolean {
    val registered = authority.registeredKeysInScope(LibraryShelfScope)
    // The view-model centre is the primary source (onCenter and the tile→Add hop both write it). Fall
    // back to focus memory only if it is somehow unset. Either way it must be a currently-registered
    // tile, never the Add key — enforced HERE on both branches, not by writer statement order
    // (2026-08-11 review: the centre branch's Add-safety previously rested on the hop stamping
    // before requestFocus(add); a reordered writer would have latched focus onto the pill forever).
    val target = viewModel.centeredShelfKey.value?.takeIf { it in registered && it.value != "library/shelf/add" }
        ?: authority.rememberedFocusIn(LibraryShelfScope)
            ?.takeIf { it.value != "library/shelf/add" && it in registered }
        ?: return false
    authority.requestFocus(target)
    return true
}

private fun platformShelfKey(id: PlatformId): FocusKey = FocusKey("library/shelf/${id.value}")

/**
 * Switch the open platform from inside the gamelist AND update where the shelf will land on Back — to the
 * platform you are NOW viewing, not the one you opened from. Without this the shelf's focus memory still
 * points at the opened-from tile, so Back lands there for a frame and then snaps to the viewed platform
 * (the same class of jank as the return-jump fix, recorded 2026-07-08).
 */
private fun switchGamelistPlatform(
    authority: FocusAuthority,
    viewModel: LibraryViewModel,
    delta: Int,
): Boolean {
    if (!viewModel.switchOpenPlatform(delta)) return false
    // Keep A's authority alive until B has registered a valid destination. The screen's pending landing paints
    // and settles B exactly once; clearing here created the visible game-one flash before that registration.
    viewModel.openPlatform.value?.let { authority.rememberFocusForScope(LibraryShelfScope, platformShelfKey(it)) }
    return true
}

/** True while the open gamelist is a single-platform COVERFLOW (not the cross-platform Favorites list). */
private fun inCarouselGamelist(viewModel: LibraryViewModel): Boolean =
    viewModel.openPlatform.value != null &&
        viewModel.openPlatform.value != LibraryViewModel.FAVORITES_PLATFORM_ID &&
        viewModel.gamelistViewMode.value == GamelistViewMode.CAROUSEL

/**
 * Whether the Enrich/Hero seat is on screen. Compact paints none: no LIST preview panel (D3, 2026-09-27) and no
 * carousel destination band (2026-09-28). The authority is the one truth about what is registered.
 */
private fun gamelistMediaSeatRegistered(authority: FocusAuthority): Boolean =
    GamelistGetArtKey in authority.registeredKeysInScope(GamelistScope)

/** Emulated list / Favorites Games expose one Enrich/Hero seat; native favorite segments do not. */
private fun listGamelistOffersMediaAction(viewModel: LibraryViewModel): Boolean =
    !inCarouselGamelist(viewModel) && !viewModel.isNativeFavoritesSegment()

/**
 * The game that owns the Enrich/Hero seat: the focused-metadata row when one is set, else
 * `games.first()`. While the seat owns focus this IS the game the preview panel displays — with the
 * seat focused, `gamelistDisplayedGame` (`LibraryScreen.kt:2570`) has `focusedGameIndex == -1` and
 * `retainedGameIndex` from the same `detailGame`, so it reduces to exactly `detailGame ?: first`
 * (gate F2: the equivalence holds in every state this helper runs in, not by coincidence at n=1).
 * Focus can reach the seat WITHOUT `focusMetadata` having run — at list length 1 a geometric DOWN
 * lands on the seat's physical bounds while `detailGame` is still null from a platform switch
 * (BUG-4, 2026-08-11) — and every exit from AND action on the seat resolves through this
 * owner (gate F1), so that arrival can neither strand focus nor dead-end the A button.
 */
private fun gamelistMediaSeatOwner(viewModel: LibraryViewModel): GameRow? =
    viewModel.detailGame.value ?: viewModel.games.value.firstOrNull()

/** Move focus one game through the finite coverflow list. If focus is off-list, land on the first game. */
private fun moveGamelistListFocus(
    authority: FocusAuthority,
    viewModel: LibraryViewModel,
    delta: Int,
): Boolean {
    // The list's key index, found for the very list the screen shows (RC-45): a held L/R used to rebuild every key and walk them for the current
    // seat on each press. The index answers the seat in one lookup and allocates nothing for the list.
    val index = GamelistKeyIndex.of(viewModel.games.value)
    val list = index.keys
    if (list.isEmpty()) return false
    val current = authority.state.value.focused?.let(index::indexOf) ?: -1
    val next = if (current < 0) 0 else (current + delta).coerceIn(0, list.lastIndex)
    if (next != current) authority.requestFocus(list[next])
    return true
}
