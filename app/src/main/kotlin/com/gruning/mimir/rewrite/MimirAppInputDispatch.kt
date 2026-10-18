package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.MimirKeyboardFocusScope
import com.gruning.mimir.rewrite.core.input.MimirNavFeedback
import com.gruning.mimir.rewrite.core.input.dispatchMimirKeyboardInput
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.ui.MimirQuickRailAction
import com.gruning.mimir.rewrite.feature.browse.dispatchToBrowse
import com.gruning.mimir.rewrite.feature.library.GameDetailScope
import com.gruning.mimir.rewrite.feature.library.HomeShelfQuickRailScope
import com.gruning.mimir.rewrite.feature.library.HomeShelfScope
import com.gruning.mimir.rewrite.feature.library.HomeTomeFolioScope
import com.gruning.mimir.rewrite.feature.library.homeShelfEntryForKey
import com.gruning.mimir.rewrite.feature.library.HomeTomeFolioActionRequest
import com.gruning.mimir.rewrite.feature.library.LibraryDetailPlayKey
import com.gruning.mimir.rewrite.feature.library.LibraryDetailRestartKey
import com.gruning.mimir.rewrite.feature.library.TomeId
import com.gruning.mimir.rewrite.feature.library.dispatchLibraryMimirKeyboard
import com.gruning.mimir.rewrite.feature.library.dispatchToHomeShelf
import com.gruning.mimir.rewrite.feature.library.dispatchToLibrary
import com.gruning.mimir.rewrite.feature.settings.dispatchToSettings
import com.gruning.mimir.rewrite.core.audio.MimirSoundEvent
import com.gruning.mimir.rewrite.core.audio.MimirUiSounds

private fun Int?.orEmptyIndex(entries: List<*>): Int =
    (this ?: 0).coerceIn(0, entries.lastIndex.coerceAtLeast(0))

/**
 * Piece 3's side effect, shared by the overlay's own touch tap (`MimirAppHost`'s `onReset` closure) and
 * the controller CONFIRM-on-reset shortcut below, so the two entry points can never drift apart.
 */
internal fun resetGameTitleOverride(bindings: MimirAppBindings, gameId: GameId) {
    bindings.core.libraryViewModel.clearTitleOverride(gameId)
    bindings.navigation.closeGameTitleEdit()
    // The Home launch card's snapshot isn't Room-reactive (unlike Library detail's `gameDetail`
    // StateFlow) — closing it here matches MimirAppHost's own onSave/onReset shape so neither entry
    // point can leave a stale title lingering on an already-open Home card.
    bindings.homeLaunchCard.close()
}

/** Routes controller input to the keyboard owned by the current top overlay before generic overlay fallback. */
internal fun dispatchTopOverlayMimirKeyboard(
    bindings: MimirAppBindings,
    action: ControllerAction,
): Boolean = dispatchTopOverlayMimirKeyboard(
    top = bindings.overlays.top,
    authority = bindings.core.focusAuthority,
    settingsKeyboardHost = bindings.settings.uiState.settingsKeyboardHost,
    artworkConnectKeyboardHost = bindings.artwork.connectKeyboardHost,
    homeTomeLibraryDraftOpen = bindings.navigation.homeTomeLibrary?.draft != null,
    homeTomeLibraryConfirmDelete = bindings.navigation.homeTomeLibrary?.confirmDelete == true,
    homeTomeLibraryKeyboardHost = bindings.navigation.homeTomeLibraryKeyboardHost,
    action = action,
    onDismissProfileName = { bindings.settings.setProfileNameEditing(false) },
    onDismissSteamGridDbKey = { bindings.settings.setSteamGridDbKeyEditing(false) },
    onDismissRetroAchievements = { bindings.settings.retroAchievementsConnect.dismiss() },
    onDismissArtworkConnect = { bindings.artwork.state.closeConnect() },
    onDismissGameTitleEdit = { bindings.navigation.closeGameTitleEdit() },
    onResetGameTitle = {
        bindings.navigation.gameTitleEdit?.let { request -> resetGameTitleOverride(bindings, request.gameId) }
    },
    onHomeTomeLibraryAction = bindings.overlays.onHomeTomeLibraryAction,
    retroAchievementsValidating = bindings.settings.retroAchievementsValidating,
)

internal fun dispatchTopOverlayMimirKeyboard(
    top: MimirOverlayKind?,
    authority: com.gruning.mimir.rewrite.core.input.FocusAuthority,
    settingsKeyboardHost: MimirKeyboardHost?,
    artworkConnectKeyboardHost: MimirKeyboardHost?,
    homeTomeLibraryDraftOpen: Boolean,
    homeTomeLibraryConfirmDelete: Boolean,
    homeTomeLibraryKeyboardHost: MimirKeyboardHost?,
    action: ControllerAction,
    onDismissProfileName: () -> Unit,
    onDismissSteamGridDbKey: () -> Unit,
    onDismissRetroAchievements: () -> Unit,
    onDismissArtworkConnect: () -> Unit,
    onDismissGameTitleEdit: () -> Unit,
    onResetGameTitle: () -> Unit,
    onHomeTomeLibraryAction: (ControllerAction) -> Unit,
    retroAchievementsValidating: Boolean = false,
): Boolean {
    if (authority.state.value.scope != MimirKeyboardFocusScope) return false
    return when (top) {
        MimirOverlayKind.PROFILE_NAME -> {
            val host = settingsKeyboardHost ?: return false
            if (host.owner != top.name) return false
            dispatchMimirKeyboardInput(
                authority = authority,
                scope = MimirKeyboardFocusScope,
                action = action,
                binding = host.binding,
                onDismiss = onDismissProfileName,
            )
        }
        MimirOverlayKind.STEAMGRIDDB_KEY -> {
            val host = settingsKeyboardHost ?: return false
            if (host.owner != top.name) return false
            dispatchMimirKeyboardInput(
                authority = authority,
                scope = MimirKeyboardFocusScope,
                action = action,
                binding = host.binding,
                onDismiss = onDismissSteamGridDbKey,
            )
        }
        MimirOverlayKind.RETROACHIEVEMENTS_KEY -> {
            val host = settingsKeyboardHost ?: return false
            if (host.owner != top.name) return false
            // Product review 2026-09-28, F1: B and Back leave the editor on both steps. The shared keyboard's
            // CANCEL presses the Done key, which does nothing on an empty step, so B used to trap a controller
            // user here. The overlay's own Back rule is reused: it dismisses, and swallows Back while the key
            // is being checked.
            if (action == ControllerAction.CANCEL || action == ControllerAction.SYSTEM_BACK) {
                return dispatchRetroAchievementsConnect(
                    action = action,
                    validating = retroAchievementsValidating,
                    onBack = onDismissRetroAchievements,
                )
            }
            dispatchMimirKeyboardInput(
                authority = authority,
                scope = MimirKeyboardFocusScope,
                action = action,
                binding = host.binding,
                onDismiss = onDismissRetroAchievements,
            )
        }
        MimirOverlayKind.ARTWORK_CONNECT -> {
            val host = artworkConnectKeyboardHost ?: return false
            if (host.owner != top.name) return false
            // Artwork Connect documents Back as cancel. The shared keyboard's CANCEL contract is
            // B/Done for ordinary text fields, so route both controller/system Back actions directly
            // to the overlay close path before they can activate the username Done key.
            if (action == ControllerAction.CANCEL || action == ControllerAction.SYSTEM_BACK) {
                onDismissArtworkConnect()
                return true
            }
            dispatchMimirKeyboardInput(
                authority = authority,
                scope = MimirKeyboardFocusScope,
                action = action,
                binding = host.binding,
                onDismiss = onDismissArtworkConnect,
            )
        }
        MimirOverlayKind.GAME_TITLE_EDIT -> {
            val host = settingsKeyboardHost ?: return false
            if (host.owner != top.name) return false
            // Piece 3: CONFIRM on the reset key fires BEFORE the generic keyboard dispatch, never through
            // it. dispatchMimirKeyboardInput's CONFIRM branch resolves exclusively through
            // MimirKeyboardFocusBinding.activateFocused(), whose activator map is reconciled against the
            // keyboard's OWN key-row set on every shift/symbols toggle (MimirKeyboardFocusBinding's
            // reconcileFocus prunes anything outside that set) — wiring reset through that same map would
            // silently stop firing the instant the player touched Shift, with no visible sign anything
            // was wrong. This check is scope-independent of that map entirely.
            if (action == ControllerAction.CONFIRM && authority.state.value.focused == GameTitleEditResetKey) {
                onResetGameTitle()
                return true
            }
            dispatchMimirKeyboardInput(
                authority = authority,
                scope = MimirKeyboardFocusScope,
                action = action,
                binding = host.binding,
                onDismiss = onDismissGameTitleEdit,
            )
        }
        MimirOverlayKind.HOME_TOME_LIBRARY -> {
            if (!homeTomeLibraryDraftOpen) return false
            val host = homeTomeLibraryKeyboardHost ?: return false
            if (host.owner != top.name) return false
            if (homeTomeLibraryConfirmDelete) return false
            dispatchMimirKeyboardInput(
                authority = authority,
                scope = MimirKeyboardFocusScope,
                action = action,
                binding = host.binding,
                onDismiss = { onHomeTomeLibraryAction(ControllerAction.SYSTEM_BACK) },
            )
        }
        else -> false
    }
}

internal fun closeTomeSurface(bindings: MimirAppBindings): Boolean {
    val session = bindings.homeShelf.tomeSession ?: return false
    if (session.origin != HomeTomeOrigin.TOME_LIBRARY) return bindings.homeShelf.closeTome()
    val entries = homeTomeLibraryEntries(
        bindings.homeShelf.tomeDocument,
        bindings.homeShelf.availableTomes,
        bindings.homeShelf.homeOverviewTome,
    )
    val exactIndex = entries.indexOfFirst { entry ->
        entry is HomeTomeLibraryEntry.Tome && entry.model.tomeId == session.tomeId
    }.takeIf { it >= 0 } ?: session.browserFocusedIndex.orEmptyIndex(entries)
    val focusKey = entries.getOrNull(exactIndex)?.let(::homeTomeLibraryFocusKey)
    val returnState = HomeTomeLibraryState(
        entries = entries,
        invokingEntry = session.rootEntry,
        focusedIndex = exactIndex,
        firstVisibleIndex = session.browserFirstVisibleIndex.orEmptyIndex(entries),
        // CP01/CP02: this is the Folio's own return, not the Home shelf's cold X-press -- the entry-path
        // signal `mimirIncomingOverlayHoldMsForPresentation` discriminates on (MimirAppHost.kt). CP02
        // corrected that function's FOLIO_RETURN arm to carry the SAME OverlayDissolveCoverMs hold as the
        // cold open  -- this is no longer "the hold must be 0 here". The signal
        // stays real and explicit regardless; see HomeTomeLibraryState.openOrigin's kdoc.
        openOrigin = HomeTomeLibraryOpenOrigin.FOLIO_RETURN,
    )
    // Publish the exact browser target before removing the folio. Compose therefore never receives
    // an intermediate null/default-index frame while the outgoing Tome closes.
    bindings.navigation.homeTomeLibrary = returnState
    // CP02: captured SYNCHRONOUSLY, in this same (non-@Composable) call stack, strictly before the write
    // above can be observed by recomposition -- the fence closeTomeToLibrary's own kdoc
    // (HomeShelfCoordinator.kt) depends on. Read AFTER the publish rather than before: both reads happen
    // before any recomposition either way, so only the epoch's own semantics matter, not statement order.
    val arrivalFence = bindings.navigation.homeTomeLibraryOpenArrivalEpoch
    if (!bindings.homeShelf.closeTomeToLibrary(arrivalFence)) {
        bindings.navigation.homeTomeLibrary = null
        return false
    }
    // Same rollback as closeTomeToLibrary's own failure branch above, and for the same reason: the
    // publish on line ~195 is the ONLY thing that says "the Tome-library overlay is open." By this point
    // closeTomeToLibrary has already succeeded -- the Folio is gone, unrecoverable -- so if entering the
    // library's focus scope then fails, leaving the publish in place would compose the overlay with no
    // focus scope ever entered (a surface the controller cannot drive) while returning false, which the
    // caller reads as "nothing happened." Both failure paths must leave the same, consistent trace:
    // publish rolled back, false returned.
    if (!enterHomeTomeScope(bindings.core.focusAuthority, HomeTomeLibraryScope, focusKey)) {
        bindings.navigation.homeTomeLibrary = null
        return false
    }
    return true
}

internal fun mappedGlobalMButtonCommand(action: ControllerAction): MButtonPrimaryCommand? = when (action) {
    ControllerAction.GLOBAL_QUICK_LAUNCH -> MButtonPrimaryCommand.QUICK_LAUNCH
    ControllerAction.GLOBAL_UTILITY -> MButtonPrimaryCommand.UTILITY
    ControllerAction.GLOBAL_TWEAKER -> MButtonPrimaryCommand.TWEAKER
    ControllerAction.GLOBAL_SETTINGS -> MButtonPrimaryCommand.SETTINGS
    else -> null
}

private fun dispatchTomeFolioInput(
    bindings: MimirAppBindings,
    action: ControllerAction,
): Boolean {
    return when (val request = bindings.homeShelf.routeTomeFolioAction(action) ?: return false) {
        is HomeTomeFolioActionRequest.Navigate -> {
            bindings.homeShelf.cancelTomePageResidue()
            bindings.homeShelf.navigateTomeFolio(request.update)
            if (request.update.moved) MimirNavFeedback.consumed()
            true
        }
        is HomeTomeFolioActionRequest.Open -> {
            bindings.homeShelf.tomeFolioRows[request.member.itemId]?.let { row ->
                if (bindings.homeShelf.canActivate(row.itemId)) {
                    bindings.homeLaunchCard.open(
                        row,
                        bindings.homeShelf.gameForAchievements(row),
                        HomeTomeFolioScope,
                        request.member.focusKey,
                    )
                }
            }
            true
        }
        is HomeTomeFolioActionRequest.Edit -> {
            bindings.homeShelf.tomeFolioRows[request.member.itemId]?.let { row ->
                if (bindings.homeShelf.canActivate(row.itemId)) {
                    bindings.navigation.openHomeTileMenu(
                        homeTileMenuState(
                            row = row,
                            game = bindings.homeShelf.gameForAchievements(row),
                            removable = bindings.homeShelf.isMember(row.itemId),
                            activeCustomTomeId = bindings.homeShelf.tomeSession?.tomeId as? TomeId.Custom,
                        ),
                    )
                }
            }
            true
        }
        HomeTomeFolioActionRequest.ToggleArrange -> {
            bindings.homeShelf.enterTomeArrange()
            true
        }
        is HomeTomeFolioActionRequest.Arrange -> {
            bindings.homeShelf.cancelTomePageResidue()
            bindings.homeShelf.dispatchTomeArrange(request.action)
            true
        }
        HomeTomeFolioActionRequest.Consumed -> true
        HomeTomeFolioActionRequest.Back -> closeTomeSurface(bindings)
        is HomeTomeFolioActionRequest.General -> false
    }
}

internal fun dispatchMimirInput(bindings: MimirAppBindings, action: ControllerAction): Boolean {
    val core = bindings.core
    val navigation = bindings.navigation
    val settings = bindings.settings
    val authority = core.focusAuthority

    if (BuildConfig.DEBUG && DebugHomeRailObservation.isArmed()) {
        DebugHomeRailObservation.recordAppDispatch(
            action = action,
            destination = navigation.destination,
            scope = authority.state.value.scope.toString(),
            focused = authority.state.value.focused?.toString(),
            overlay = bindings.overlays.top?.toString(),
        )
    }

    mappedGlobalMButtonCommand(action)?.let {
        MButtonNavigationCommands.publish(it)
        return true
    }

    if (navigation.destination == MimirDestination.HOME) {
        // Content commands arriving in the Back→recompose gap belong to the Favorite root, never the
        // still-captured Tome-member rows. Keep their order on the return generation and consume now;
        // global commands cancel and continue immediately.
        if (bindings.homeShelf.deferTomeReturnAction(action)) return true
        if (!bindings.homeShelf.canActivateHomeContent() &&
            com.gruning.mimir.rewrite.feature.library.isHomeShelfTomeDeferredContentAction(action)
        ) {
            return true
        }
        bindings.homeShelf.cancelTomeReturn()
    }
    if (navigation.destination == MimirDestination.LIBRARY) {
        bindings.library.noteTransitionInteraction()
    }
    bindings.homeLaunchCard.cancelOutgoingDissolveOnInteraction()
    if (dispatchTopOverlayMimirKeyboard(bindings, action)) return true
    // START from Home's launch card opens Settings and B comes back to the card , as on the Library's. The card is an overlay, and an overlay answers every
    // press, so START is taken here, before it: the card is held (its session and scopes stay) and Settings is opened over it.
    if (action == ControllerAction.SYSTEM_START &&
        navigation.destination == MimirDestination.HOME &&
        bindings.overlays.top == MimirOverlayKind.HOME_LAUNCH_CARD &&
        bindings.homeLaunchCard.holdForSettingsVisit(navigation.destination) { navigation.settingsFromHomeLaunchCard = true }
    ) {
        navigation.openSettingsFromCurrent()
        return true
    }
    bindings.overlays.dispatch(action)?.let { return it }
    // The revoked-storage-grant prompt (StorageGrantPromptHost) composes only while no overlay is open,
    // in its own focus scope, so its pad handling sits here rather than on the overlay stack: the same
    // NAV/CONFIRM/CANCEL shape the NewGameEnrich prompt uses inside MimirOverlayCoordinator. When nothing
    // is lost, or the player deferred it, this is a null root and costs one read.
    if (bindings.overlays.top == null) {
        val storagePromptRoot = storageGrantPromptRoot(
            StorageGrantAuditCoordinator.lostRoots.value,
            StorageGrantAuditCoordinator.promptDeferred.value,
        )
        if (storagePromptRoot != null &&
            dispatchStorageGrantPrompt(
                action = action,
                promptRoot = storagePromptRoot,
                onReconnect = { StorageGrantAuditCoordinator.requestRegrant(storagePromptRoot) },
                onLater = StorageGrantAuditCoordinator::deferForProcess,
                authority = authority,
            )
        ) {
            return true
        }
    }
    if (navigation.destination == MimirDestination.LIBRARY && bindings.nativeShelf.addKeyboardOpen) {
        val dismissed = {
            MimirUiSounds.play(MimirSoundEvent.BackCancel)
            bindings.nativeShelf.closeAddSearch()
        }
        if (dispatchLibraryMimirKeyboard(
                authority = authority,
                host = core.libraryViewModel.nativeAddKeyboardHost,
                action = action,
                onDismiss = dismissed,
            )
        ) {
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
    if (navigation.destination == MimirDestination.LIBRARY && core.libraryViewModel.keyboardOpen.value) {
        val dismissed = {
            MimirUiSounds.play(MimirSoundEvent.BackCancel)
            core.libraryViewModel.closeSearch()
        }
        if (dispatchLibraryMimirKeyboard(
                authority = authority,
                host = core.libraryViewModel.libraryKeyboardHost,
                action = action,
                onDismiss = dismissed,
            )
        ) {
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
    if (navigation.destination == MimirDestination.BROWSE && bindings.library.browseKeyboardOpen) {
        val dismissed = {
            MimirUiSounds.play(MimirSoundEvent.BackCancel)
            bindings.library.setBrowseKeyboardOpen(false)
        }
        if (com.gruning.mimir.rewrite.feature.browse.dispatchBrowseMimirKeyboard(
                authority = authority,
                host = bindings.library.browseKeyboardHost,
                action = action,
                onDismiss = dismissed,
            )
        ) {
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
    if (navigation.destination == MimirDestination.ONBOARDING) {
        return dispatchOnboarding(
            authority = authority,
            step = navigation.onboardingStep,
            profile = navigation.onboardingProfile,
            accessReview = navigation.onboardingAccessReview,
            favoritesGrid = navigation.onboardingFavoritesGrid,
            welcomeIntro = navigation.onboardingWelcomeIntro,
            stepGlide = navigation.onboardingStepGlide,
            hintCarousel = navigation.onboardingHintCarousel,
            actionTour = navigation.onboardingActionTour,
            accessOrientationSeen = bindings.onboarding.checkpoint.accessOrientationSeen,
            focusIntent = navigation.onboardingFocusIntent,
            library = navigation.onboardingLibrary,
            access = bindings.onboarding.access,
            artworkAccount = bindings.artwork.account,
            settlement = bindings.onboarding.settlement,
            action = action,
            onAdvance = bindings.onboarding.advance,
            onCommitName = { name -> ProfileNameStore.setName(core.context, name) },
            onChooseFolder = bindings.onboarding.chooseFolder,
            onRequestFilesAccess = bindings.onboarding.requestFilesAccess,
            onRequestUsageAccess = bindings.onboarding.requestUsageAccess,
            onRequestOverlayAccess = bindings.onboarding.requestOverlayAccess,
            // QA-16
            favoriteGames = bindings.onboarding.favoriteGames,
            favoritesIdentifying = bindings.onboarding.favoritesIdentifying,
            onToggleFavorite = bindings.onboarding.toggleFavorite,
            onMarkAccessOrientationSeen = bindings.onboarding.markAccessOrientationSeen,
            onRequestHomeRole = bindings.onboarding.requestHomeRole,
            onOpenArtworkSetup = bindings.onboarding.openArtworkSetup,
            onOpenSteamGridDbKey = { bindings.settings.setSteamGridDbKeyEditing(true) },
            onOpenRetroAchievements = {
                bindings.settings.retroAchievementsConnect.open(RetroAchievementsCredentialStore.connectedUser.value)
            },
            onRetrySettlement = bindings.onboarding.retrySettlement,
            onContinueLocally = bindings.onboarding.continueLocally,
            onApplyTheme = { themeId -> ThemeStore.setTheme(core.context, themeId) },
        )
    }
    if (action == ControllerAction.SYSTEM_START) {
        if (navigation.destination == MimirDestination.LIBRARY && dispatchMimirLibraryInput(bindings, action)) {
            return true
        }
        if (navigation.destination != MimirDestination.SETTINGS) {
            bindings.library.prepareDetailDescriptionReturn()
            navigation.openSettingsFromCurrent(bindings.library.detailSession)
        }
        return true
    }
    // Favorite Home has one root. Shoulder/trigger context cycling is intentionally retired; X owns
    // Tome discovery and direct entry.
    if ((action == ControllerAction.TRIGGER_LEFT || action == ControllerAction.TRIGGER_RIGHT) &&
        (navigation.destination == MimirDestination.SETTINGS ||
            navigation.destination == MimirDestination.BROWSE)
    ) {
        // LG01: TOOLS retired from the shell, so the L2/R2 cycle is a three-stop ring.
        val order = listOf(
            MimirDestination.HOME,
            MimirDestination.BROWSE,
            MimirDestination.SETTINGS,
        )
        val index = order.indexOf(navigation.destination)
        val delta = if (action == ControllerAction.TRIGGER_RIGHT) 1 else -1
        navigation.destination = order[(index + delta + order.size) % order.size]
        return true
    }

    return when (navigation.destination) {
        MimirDestination.ONBOARDING -> true
        MimirDestination.HOME -> if (bindings.homeShelf.tomeFolioState != null) {
            dispatchTomeFolioInput(bindings, action)
        } else if (action == ControllerAction.TRIGGER_LEFT || action == ControllerAction.TRIGGER_RIGHT) {
            bindings.homeShelf.jumpLooseHomeAlphabetBucket(action == ControllerAction.TRIGGER_RIGHT)
        } else {
            val observeController = BuildConfig.DEBUG && DebugHomeRailObservation.isArmed()
            val scopeBefore = if (observeController) authority.state.value.scope.toString() else null
            val focusedBefore = if (observeController) authority.state.value.focused?.toString() else null
            val handled = dispatchToHomeShelf(
                authority = authority,
                rows = bindings.homeShelf.rows,
                entries = bindings.homeShelf.inputEntries,
                action = action,
                //  the rail the D-pad travels is the rail that is drawn (MimirAppHost reads the same ledger in composition).
                filesSeatOffered = !filesRouteGameRunning(MimirSessionAuthority.state.value),
                onLaunch = { row ->
                    if (bindings.homeShelf.canActivate(row.itemId)) {
                        bindings.homeLaunchCard.open(row, bindings.homeShelf.gameForAchievements(row))
                    }
                },
                onOpenAchievements = { row ->
                    bindings.homeShelf.gameForAchievements(row)?.let(
                        bindings.achievements.state::openCardFromHome,
                    )
                },
                onOpenTome = { tomeId ->
                    bindings.homeShelf.openTome(tomeId, HomeTomeOrigin.HOME_ROOT, null, null)
                },
                onOpenLibrary = { navigation.destination = MimirDestination.LIBRARY },
                onQuickAction = navigation::onShelfQuickAction,
                onTileMenu = { row ->
                    if (bindings.homeShelf.canActivate(row.itemId)) {
                        navigation.openHomeTileMenu(
                            homeTileMenuState(row, bindings.homeShelf.gameForAchievements(row), bindings.homeShelf.isMember(row.itemId)),
                        )
                    }
                },
                onTomes = {
                    val homeFocusedKey = if (authority.state.value.scope == HomeShelfScope) {
                        authority.state.value.focused
                    } else {
                        authority.rememberedFocusIn(HomeShelfScope)
                    }
                    val entries = homeTomeLibraryEntries(
                        bindings.homeShelf.tomeDocument,
                        bindings.homeShelf.availableTomes,
                        bindings.homeShelf.homeOverviewTome,
                    )
                    val focusedIndex = homeTomeLibraryRestoredIndex(
                        entries = entries,
                        rememberedFocus = authority.rememberedFocusIn(HomeTomeLibraryScope),
                    )
                    val libraryFocusKey = entries.getOrNull(focusedIndex)?.let(::homeTomeLibraryFocusKey)
                    if (enterHomeTomeScope(authority, HomeTomeLibraryScope, libraryFocusKey)) {
                        navigation.homeTomeLibrary = HomeTomeLibraryState(
                            entries = entries,
                            invokingEntry = homeShelfEntryForKey(bindings.homeShelf.entries, homeFocusedKey),
                            focusedIndex = focusedIndex,
                            // CP01: the cold open from the Home shelf -- the measured defect, gets the
                            // incoming pixel-fade hold (see HomeTomeLibraryState.openOrigin's kdoc).
                            openOrigin = HomeTomeLibraryOpenOrigin.HOME_SHELF,
                        )
                    }
                },
                onExit = { closeTomeSurface(bindings) },
                logicalFocusKey = bindings.homeShelf.inputFocusKey,
                onLogicalFocusChanged = bindings.homeShelf::updateInputFocus,
            )
            if (observeController) {
                DebugHomeRailObservation.recordController(
                    action = action,
                    scopeBefore = scopeBefore.orEmpty(),
                    focusedBefore = focusedBefore,
                    scopeAfter = authority.state.value.scope.toString(),
                    focusedAfter = authority.state.value.focused?.toString(),
                    handled = handled,
                )
            }
            handled
        }
        MimirDestination.DIAGNOSTICS -> when (action) {
            ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK -> {
                MimirUiSounds.play(MimirSoundEvent.BackCancel) // audio R-3: real navigate — no-op CANCEL stays silent
                navigation.destination = MimirDestination.SETTINGS
                true
            }
            else -> false
        }
        MimirDestination.SETTINGS -> {
            if (settings.sourceRemovalConfirmation != null &&
                (action == ControllerAction.CANCEL || action == ControllerAction.SYSTEM_BACK)
            ) {
                settings.cancelSourceRemoval()
                true
            } else {
                dispatchToSettings(
                    authority = authority,
                    action = action,
                    onRailOpen = { },
                    onRowActivate = settings.onRowActivate,
                    onExit = navigation::restoreSettingsOriginOrLibraryRoot,
                    // Settings › Music: X on the Music folder row rescans it and Y opens its Forget confirmation; every other row leaves both alone.
                    onRowSecondary = settings.onRowSecondary,
                    onRowTertiary = settings.onRowTertiary,
                )
            }
        }
        MimirDestination.LIBRARY -> dispatchMimirLibraryInput(bindings, action)
        MimirDestination.BROWSE -> dispatchToBrowse(
            authority = authority,
            hiddenGames = bindings.library.browseVisible,
            keyboardOpen = bindings.library.browseKeyboardOpen,
            keyboardHost = bindings.library.browseKeyboardHost,
            action = action,
            onRestore = bindings.library.restoreHidden,
            onCloseKeyboard = { bindings.library.setBrowseKeyboardOpen(false) },
            onExit = { navigation.destination = MimirDestination.HOME },
            // A on the focused search bar opens the touch keyboard, as a tap on it does.
            onOpenSearch = { bindings.library.setBrowseKeyboardOpen(true) },
        )
    }
}

internal fun dispatchMimirLibraryInput(
    bindings: MimirAppBindings,
    action: ControllerAction,
): Boolean {
    val core = bindings.core
    if (action == ControllerAction.CONFIRM &&
        core.focusAuthority.state.value.scope == GameDetailScope &&
        (core.focusAuthority.state.value.focused == LibraryDetailPlayKey || core.focusAuthority.state.value.focused == LibraryDetailRestartKey)
    ) {
        // External emulator/native activity returns reuse the same session-owned exact description snapshot. Restart launches too (it presses Play), so it takes the snapshot as Play does.
        bindings.library.prepareDetailDescriptionReturn()
    }
    val nativeShelf = bindings.nativeShelf
    val snapshot = nativeShelf.snapshot
    if (dispatchLibraryQuickRail(
            authority = core.focusAuthority,
            action = action,
            homeEnabled = BuildConfig.FINAL_SHELF_ENABLED,
            filesSeatOffered = !filesRouteGameRunning(MimirSessionAuthority.state.value),
            onAction = { quickAction ->
                if (quickAction == MimirQuickRailAction.SETTINGS) {
                    bindings.library.prepareDetailDescriptionReturn()
                    bindings.navigation.openSettingsFromCurrent(bindings.library.detailSession)
                } else {
                    bindings.navigation.onShelfQuickAction(quickAction)
                }
            },
        )
    ) {
        return true
    }
    return dispatchToLibrary(
        authority = core.focusAuthority,
        viewModel = core.libraryViewModel,
        action = action,
        onExit = { bindings.navigation.destination = MimirDestination.HOME },
        onRescanFolder = bindings.settings.onRescanFolder,
        onLaunchNativePackage = nativeShelf.launchPackage,
        onToggleNativeFavorite = nativeShelf.toggleFavorite,
        //  the Library's per-game Enrich / Get art seat under a running game-music download
        // says why rather than fetching into a Busy result and the generic could-not card. The seat is drawn in
        // feature-library from the game's own state, so the line arrives as a toast rather than under the seat.
        onFetchArt = { row ->
            if (artworkEntryMayStart(ArtworkOperationGate.process.active.value)) {
                bindings.artwork.state.fetchOne(row)
            } else {
                showMimirMessage(core.context, GameMusicDownloadingCopy, com.gruning.mimir.rewrite.core.ui.MimirMessageClass.Guidance)
            }
        },
        onCustomizeHero = bindings.onOpenLibraryHeroPicker,
        onOpenAchievements = bindings.achievements.state::openCard,
        // Y from the library: the game's media menu, the same callback the media seat already uses.
        onOpenMediaMenuForFocused = bindings.onOpenLibraryHeroPicker,
        onOpenNativeAppTileMenu = { packageName ->
            (snapshot.games + snapshot.apps).firstOrNull { it.packageName == packageName }?.let { row ->
                // The menu carries favourite only when it is the open Library card's own (Y on an Android game's card), with the shelf's live state, which follows a write
                // at once. The shelf ring's Y has no card open for this package and keeps the menu it always had.
                val cardFavorite = bindings.detailActionState
                    ?.takeIf { (it.itemId as? HomeShelfItemId.AndroidGame)?.packageName?.value == packageName }
                    ?.isFavorite
                bindings.navigation.openHomeTileMenu(
                    nativeAppTileMenuState(row.packageName, row.label, row.iconPath, row.isGame, favorite = cardFavorite),
                )
            }
        },
        onRequestHideNativePackage = nativeShelf.requestHidePackage,
        onConfirmHideNativePackage = nativeShelf.confirmHidePackage,
        onCancelHideNativePackage = nativeShelf.cancelHidePackage,
        onOpenNativeAddPicker = nativeShelf.openAddPicker,
        onMoveNativeAddPickerFocus = nativeShelf.moveAddPickerFocus,
        onOpenNativeAddSearch = nativeShelf.openAddSearch,
        onChooseNativeAddPackage = nativeShelf.chooseAddPackage,
        onDismissNativeAddPicker = nativeShelf.dismissAddPicker,
        platformRows = bindings.library.platforms,
        nativeGameRows = snapshot.games,
        nativeAppRows = snapshot.apps,
        nativeFavoriteGameRows = snapshot.games.filter { it.isFavorite },
        nativeFavoriteAppRows = snapshot.apps.filter { it.isFavorite },
        activeDetailTarget = bindings.library.activeDetailTarget,
        detailActionState = bindings.detailActionState,
        detailDescriptionScrollState = bindings.library.detailDescriptionScrollState,
        onDetailDescriptionScroll = bindings.library.requestDetailDescriptionScroll,
        onOpenLibraryDetail = bindings.library.openDetail,
        onCloseLibraryDetail = bindings.library.closeDetail,
        onOpenPlatformTileMenu = { row ->
            bindings.navigation.openPlatformTileMenu(
                newPlatformArtMenuState(core.context, row.platformId, row.label),
            )
        },
        onEditGameTitle = { target ->
            bindings.navigation.openGameTitleEdit(
                gameId = target.game.gameId,
                currentTitle = target.game.title,
                hasOverride = target.game.hasTitleOverride,
            )
        },
    )
}
