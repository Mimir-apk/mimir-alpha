package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MimirOverlayCoordinatorTest {
    @Test
    fun shortcutCaptureIsATopLevelModalAboveSettingsAndMapping() {
        val stack = resolveMimirOverlayStack(
            MimirOverlaySnapshot(
                controllerMapping = true,
                controllerShortcutCapture = true,
                brightness = true,
            ),
        )

        assertEquals(MimirOverlayKind.CONTROLLER_SHORTCUT_CAPTURE, stack.top)
        assertTrue(MimirOverlayKind.CONTROLLER_MAPPING in stack.entries)
    }

    @Test
    fun systemBackCancelsShortcutCaptureWhileOtherActionsRemainSwallowed() {
        var cancellations = 0

        assertTrue(
            dispatchControllerShortcutCapture(ControllerAction.SYSTEM_BACK) { cancellations += 1 },
        )
        assertEquals(1, cancellations)
        assertTrue(
            dispatchControllerShortcutCapture(ControllerAction.CONFIRM) { cancellations += 1 },
        )
        assertEquals(1, cancellations)
    }

    @Test
    fun mountedPickerAbsorbsControllerUntilItsOwnFocusScopeIsAuthoritative() {
        val authority = DefaultFocusAuthority()
        val shelfScope = FocusScope("test/shelf")
        authority.pushScope(shelfScope)
        val ownership = HomeQuickLaunchPickerOwnership()

        assertFalse(ownership.admitsControllerInput(authority))
        ownership.mount()
        assertFalse("mount precedes virtual picker registration", ownership.admitsControllerInput(authority))

        authority.pushScope(HomeQuickLaunchPickerScope)
        assertTrue("mounted picker admits without waiting for visual telemetry", ownership.admitsControllerInput(authority))

        ownership.invalidate()
        assertFalse(ownership.admitsControllerInput(authority))
    }

    @Test
    fun pickerReadinessTokenSurvivesARecomposedCoordinatorProjectionWhenItsOwnerIsRetained() {
        val retainedOwnership = HomeQuickLaunchPickerOwnership()
        val token = retainedOwnership.mount()
        assertTrue("mounted picker must own physical input before visual telemetry settles", retainedOwnership.isMounted())
        assertFalse("presentation telemetry remains independently unsettled", retainedOwnership.isReady())

        // This models `remember { HomeQuickLaunchPickerOwnership() }` injected into the next live
        // dispatch projection. The mounted token must be admitted by that same identity.
        val recomposed = MimirOverlayCoordinator(
            snapshot = { MimirOverlaySnapshot(homeQuickLaunchPicker = true) },
            dispatchTop = { _, _ -> true },
            homeQuickLaunchPickerOwnership = retainedOwnership,
        )
        assertTrue(retainedOwnership.setReady(token, true))
        assertTrue(recomposed.homeQuickLaunchPickerOwnership.isReady())

        // A fresh owner rejects the old token — the exact failure mode this injection prevents.
        val recreatedOwnership = HomeQuickLaunchPickerOwnership()
        assertFalse(recreatedOwnership.setReady(token, true))
        assertFalse(recreatedOwnership.isMounted())
        assertFalse(recreatedOwnership.isReady())
    }

    @Test
    fun genuinePickerOwnershipReplacementRequiresANewMountAndOldUnmountCannotClearIt() {
        val outgoingOwner = HomeQuickLaunchPickerOwnership()
        val outgoingToken = outgoingOwner.mount()
        assertTrue(outgoingOwner.setReady(outgoingToken, true))

        val replacementOwner = HomeQuickLaunchPickerOwnership()
        val replacementToken = replacementOwner.mount()
        assertFalse(replacementOwner.setReady(outgoingToken, true))
        assertTrue(replacementOwner.setReady(replacementToken, true))
        assertTrue(replacementOwner.isReady())

        // The old composable's disposal operates on its own owner and cannot disturb the replacement.
        assertTrue(outgoingOwner.unmount(outgoingToken))
        assertFalse(outgoingOwner.isReady())
        assertTrue(replacementOwner.isReady())

        // Closing the actual mounted picker clears admission deterministically.
        assertTrue(replacementOwner.unmount(replacementToken))
        assertFalse(replacementOwner.isMounted())
        assertFalse(replacementOwner.isReady())
    }

    @Test
    fun oneTypedStackDrivesBothVisualTopAndInputOwner() {
        var snapshot = MimirOverlaySnapshot(brightness = true, artworkGet = true)
        val dispatched = mutableListOf<MimirOverlayKind>()
        val coordinator = MimirOverlayCoordinator(
            snapshot = { snapshot },
            dispatchTop = { kind, _ -> dispatched += kind; true },
        )

        assertEquals(MimirOverlayKind.ARTWORK_GET, coordinator.top)
        assertTrue(coordinator.dispatch(ControllerAction.CANCEL) == true)
        assertEquals(listOf(MimirOverlayKind.ARTWORK_GET), dispatched)

        snapshot = MimirOverlaySnapshot()
        assertNull(coordinator.top)
        assertNull(coordinator.dispatch(ControllerAction.CONFIRM))
    }

    @Test
    fun backClosesOnlyTheTopOverlayAndRestoresItsExactPredecessorFocus() {
        val authority = DefaultFocusAuthority()
        val shelfScope = FocusScope("test/shelf")
        val overlayScope = FocusScope("test/overlay")
        val shelfKey = FocusKey("test/shelf/selected")
        val overlayKey = FocusKey("test/overlay/close")
        val bounds = FocusBounds(0f, 0f, 100f, 100f)
        authority.pushScope(shelfScope)
        authority.register(shelfKey, shelfScope, bounds)
        authority.requestFocus(shelfKey)
        authority.pushScope(overlayScope)
        authority.register(overlayKey, overlayScope, bounds)

        var snapshot = MimirOverlaySnapshot(brightness = true)
        val coordinator = MimirOverlayCoordinator(
            snapshot = { snapshot },
            dispatchTop = { _, action ->
                if (action == ControllerAction.CANCEL) {
                    snapshot = MimirOverlaySnapshot()
                    authority.popScope()
                }
                true
            },
        )

        assertTrue(coordinator.dispatch(ControllerAction.CANCEL) == true)
        assertEquals(shelfScope, authority.state.value.scope)
        assertEquals(shelfKey, authority.state.value.focused)
    }

    // ── G-5: the fetch summary's Close must be A-reachable; running fetch must never A-cancel ──────

    @Test
    fun confirmClosesTheArtworkFetchSummary() {
        for (summary in listOf(ArtworkFetchUi.Done(total = 61, matched = 58), ArtworkFetchUi.Cancelled(matched = 3))) {
            var closed = 0
            var backed = 0
            assertTrue(
                dispatchArtworkFetch(
                    action = ControllerAction.CONFIRM,
                    fetchUi = summary,
                    onCloseSummary = { closed++ },
                    onBack = { backed++ },
                ),
            )
            assertEquals(1, closed)
            assertEquals(0, backed)
        }
    }

    @Test
    fun confirmStaysInertWhileTheFetchIsRunningOrStopping() {
        for (inFlight in listOf(ArtworkFetchUi.Running(done = 3, total = 61, matched = 2), ArtworkFetchUi.Stopping(matched = 2))) {
            var closed = 0
            var backed = 0
            assertTrue(
                dispatchArtworkFetch(
                    action = ControllerAction.CONFIRM,
                    fetchUi = inFlight,
                    onCloseSummary = { closed++ },
                    onBack = { backed++ },
                ),
            )
            assertEquals(0, closed)
            assertEquals(0, backed)
        }
    }

    @Test
    fun backStillRoutesToCancelOrCloseInEveryFetchState() {
        for (state in listOf(
            ArtworkFetchUi.Running(done = 3, total = 61, matched = 2),
            ArtworkFetchUi.Stopping(matched = 2),
            ArtworkFetchUi.Done(total = 61, matched = 58),
            ArtworkFetchUi.Cancelled(matched = 3),
        )) {
            var backed = 0
            assertTrue(
                dispatchArtworkFetch(
                    action = ControllerAction.CANCEL,
                    fetchUi = state,
                    onCloseSummary = { },
                    onBack = { backed++ },
                ),
            )
            assertEquals(1, backed)
        }
    }

    @Test
    fun retroAchievementsValidationSwallowsBackUntilTheLiveCheckSettles() {
        var dismissed = 0
        assertTrue(
            dispatchRetroAchievementsConnect(
                action = ControllerAction.CANCEL,
                validating = true,
                onBack = { dismissed++ },
            ),
        )
        assertEquals(0, dismissed)

        assertTrue(
            dispatchRetroAchievementsConnect(
                action = ControllerAction.SYSTEM_BACK,
                validating = false,
                onBack = { dismissed++ },
            ),
        )
        assertEquals(1, dismissed)
    }

    @Test
    fun achievementCardRoutesVerticalNavigationAndKeepsOtherInputInert() {
        val scrolls = mutableListOf<MimirAchievementScrollDirection>()
        var dismissed = 0

        listOf(
            ControllerAction.NAV_UP,
            ControllerAction.NAV_DOWN,
            ControllerAction.CONFIRM,
            ControllerAction.NAV_LEFT,
            ControllerAction.CANCEL,
        ).forEach { action ->
            assertTrue(
                dispatchAchievementCard(
                    action = action,
                    onScroll = scrolls::add,
                    onBack = { dismissed++ },
                ),
            )
        }

        assertEquals(
            listOf(MimirAchievementScrollDirection.UP, MimirAchievementScrollDirection.DOWN),
            scrolls,
        )
        assertEquals(1, dismissed)
    }

    @Test
    fun returnCoverAlwaysWinsAConflictingTransientOverlay() {
        val stack = resolveMimirOverlayStack(
            MimirOverlaySnapshot(
                returnCover = true,
                companionPermission = true,
                migration = true,
                homeQuickLaunchPicker = true,
                homeQuickLaunch = true,
                brightness = true,
            ),
        )

        // FUX-D: if the Quick Launch picker is still flagged when a launch-return restore begins
        // (companion-footer launch while the primary picker was open), the COVER must stay on top and
        // swallow input — an A reaching the picker mid-restore would launch a slot over the restore.
        assertEquals(MimirOverlayKind.RETURN_COVER, stack.top)
        assertEquals(6, stack.entries.size)
    }

    @Test
    fun primaryAppPickerSitsAboveItsDockAndBothSitAboveSettingsPickers() {
        // Precedence is SOLELY the buildList order in resolveMimirOverlayStack — pin the new kind's slot.
        val stack = resolveMimirOverlayStack(
            MimirOverlaySnapshot(
                profileName = true,
                homeQuickLaunchPicker = true,
                homeQuickLaunch = true,
                platformPicker = true,
                themePicker = true,
                brightness = true,
            ),
        )

        assertEquals(
            listOf(
                MimirOverlayKind.PROFILE_NAME,
                MimirOverlayKind.HOME_QUICK_LAUNCH_PICKER,
                MimirOverlayKind.HOME_QUICK_LAUNCH,
                MimirOverlayKind.PLATFORM_PICKER,
                MimirOverlayKind.THEME_PICKER,
                MimirOverlayKind.BRIGHTNESS,
            ),
            stack.entries,
        )
        assertEquals(
            MimirOverlayKind.HOME_QUICK_LAUNCH_PICKER,
            resolveMimirOverlayStack(
                MimirOverlaySnapshot(
                    homeQuickLaunchPicker = true,
                    homeQuickLaunch = true,
                    brightness = true,
                ),
            ).top,
        )
    }

    @Test
    fun closingPickerCommitsBaseOwnershipBeforeImmediateNavAndConfirmExactlyOnce() {
        val authority = DefaultFocusAuthority()
        val bounds = FocusBounds(0f, 0f, 100f, 100f)
        val baseKey = homeQuickLaunchSlotKey(0)
        val pickerKey = FocusKey("home/quicklaunch/apps/app/test")
        authority.pushScope(HomeQuickLaunchScope)
        authority.registerVirtual(baseKey, HomeQuickLaunchScope, bounds)
        authority.requestFocus(baseKey)
        authority.pushScope(HomeQuickLaunchPickerScope)
        authority.registerVirtual(pickerKey, HomeQuickLaunchPickerScope, bounds)
        authority.requestFocus(pickerKey)
        val ownership = HomeQuickLaunchPickerOwnership()
        val token = ownership.mount()
        ownership.setReady(token, true)
        var snapshot = MimirOverlaySnapshot(
            homeQuickLaunchPicker = true,
            homeQuickLaunch = true,
        )
        val pickerActions = mutableListOf<ControllerAction>()
        val baseActions = mutableListOf<ControllerAction>()
        val coordinator = MimirOverlayCoordinator(
            snapshot = { snapshot },
            dispatchTop = { kind, action ->
                when (kind) {
                    MimirOverlayKind.HOME_QUICK_LAUNCH_PICKER -> {
                        pickerActions += action
                        if (action == ControllerAction.CANCEL) {
                            snapshot = snapshot.copy(homeQuickLaunchPicker = false)
                            relinquishHomeQuickLaunchPickerOwnership(authority, ownership)
                        }
                    }
                    MimirOverlayKind.HOME_QUICK_LAUNCH -> baseActions += action
                    else -> error("unexpected owner $kind")
                }
                true
            },
            homeQuickLaunchPickerOwnership = ownership,
        )

        assertTrue(coordinator.dispatch(ControllerAction.CANCEL) == true)
        assertEquals(MimirOverlayKind.HOME_QUICK_LAUNCH, coordinator.top)
        assertEquals(HomeQuickLaunchScope, authority.state.value.scope)
        assertEquals(baseKey, authority.state.value.focused)
        assertTrue(coordinator.dispatch(ControllerAction.NAV_DOWN) == true)
        assertTrue(coordinator.dispatch(ControllerAction.CONFIRM) == true)

        assertEquals(listOf(ControllerAction.CANCEL), pickerActions)
        assertEquals(listOf(ControllerAction.NAV_DOWN, ControllerAction.CONFIRM), baseActions)
    }

    @Test
    fun closingQuickLaunchBeforeUtilityRestoresHomeInsteadOfLeavingADeadScope() {
        val authority = DefaultFocusAuthority()
        val homeScope = FocusScope("home/test")
        val homeKey = FocusKey("home/test/game")
        val quickKey = homeQuickLaunchSlotKey(0)
        val pickerKey = FocusKey("home/quicklaunch/apps/app/test")
        val bounds = FocusBounds(0f, 0f, 100f, 100f)
        authority.pushScope(homeScope)
        authority.registerVirtual(homeKey, homeScope, bounds)
        authority.requestFocus(homeKey)
        authority.pushScope(HomeQuickLaunchScope)
        authority.registerVirtual(quickKey, HomeQuickLaunchScope, bounds)
        authority.requestFocus(quickKey)
        authority.pushScope(HomeQuickLaunchPickerScope)
        authority.registerVirtual(pickerKey, HomeQuickLaunchPickerScope, bounds)
        authority.requestFocus(pickerKey)

        unwindHomeQuickLaunchFocus(authority, includeQuickLaunch = true)

        assertEquals(homeScope, authority.state.value.scope)
        assertEquals(homeKey, authority.state.value.focused)
    }

    @Test
    fun sameModeReopenGetsANewCompositionIdentityAndStaleExitCannotClearIt() {
        val ownership = HomeQuickLaunchPickerOwnership()
        val outgoing = ownership.mount()
        assertFalse(ownership.isReady())
        assertTrue(ownership.setReady(outgoing, true))
        val closingInvalidation = ownership.invalidationEpoch()
        ownership.invalidate()
        assertTrue(ownership.invalidationEpoch() > closingInvalidation)

        val current = ownership.mount()
        assertNotEquals("same-mode reopen must replace the keyed picker subtree", outgoing, current)
        assertTrue(ownership.setReady(current, true))
        assertFalse("old pixel-exit disposal must not pop the current picker", ownership.unmount(outgoing))
        assertFalse("old readiness completion must not repush input ownership", ownership.setReady(outgoing, true))
        assertTrue(ownership.isReady())

        assertTrue(ownership.unmount(current))
        assertFalse(ownership.isReady())
    }

    @Test
    fun launchCardChildrenStackAboveTheCardAndReturnCoverStillWins() {
        val stack = resolveMimirOverlayStack(
            MimirOverlaySnapshot(
                returnCover = true,
                homeTileMenu = true,
                gamelistAchievements = true,
                homeOpenWith = true,
                homeLaunchCard = true,
            ),
        )

        assertEquals(MimirOverlayKind.RETURN_COVER, stack.top)
        assertEquals(
            listOf(
                MimirOverlayKind.RETURN_COVER,
                MimirOverlayKind.HOME_TILE_MENU,
                MimirOverlayKind.GAMELIST_ACHIEVEMENTS,
                MimirOverlayKind.HOME_OPEN_WITH,
                MimirOverlayKind.HOME_LAUNCH_CARD,
            ),
            stack.entries,
        )

        assertEquals(
            MimirOverlayKind.HOME_OPEN_WITH,
            resolveMimirOverlayStack(
                MimirOverlaySnapshot(homeOpenWith = true, homeLaunchCard = true),
            ).top,
        )
    }
}
