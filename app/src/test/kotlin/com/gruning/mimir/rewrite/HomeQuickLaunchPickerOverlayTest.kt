package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.model.CompanionAppEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeQuickLaunchPickerOverlayTest {
    @Test
    fun touchUsesTheExactTappedItemRatherThanThePreviouslyFocusedItem() {
        val activation = HomeQuickLaunchPickerTouchActivation()
        val launched = mutableListOf<String>()
        val items = listOf(HomeQuickLaunchPickerItem.App(AppOne), HomeQuickLaunchPickerItem.App(AppTwo))

        assertTrue(
            activation.dispatch(
                mode = HomeQuickLaunchPickerMode.General,
                item = items[1],
                onLaunch = launched::add,
                onAssign = { _, _ -> error("General touch must not assign") },
                onClear = { error("General touch must not clear") },
            ),
        )

        assertEquals(listOf(AppTwo.packageName), launched)
    }

    @Test
    fun touchActivationIsConsumedExactlyOnceForAssignAndClear() {
        val assign = HomeQuickLaunchPickerTouchActivation()
        val assigned = mutableListOf<Pair<Int, String>>()
        val app = HomeQuickLaunchPickerItem.App(AppTwo)
        assertTrue(
            assign.dispatch(
                HomeQuickLaunchPickerMode.Assign(2), app,
                onLaunch = { error("Assign touch must not launch") },
                onAssign = { slot, pkg -> assigned += slot to pkg },
                onClear = { error("App touch must not clear") },
            ),
        )
        assertFalse(
            assign.dispatch(
                HomeQuickLaunchPickerMode.Assign(2), HomeQuickLaunchPickerItem.Clear,
                onLaunch = { error("must stay consumed") },
                onAssign = { _, _ -> error("must stay consumed") },
                onClear = { error("must stay consumed") },
            ),
        )
        assertEquals(listOf(2 to AppTwo.packageName), assigned)

        val clear = HomeQuickLaunchPickerTouchActivation()
        val cleared = mutableListOf<Int>()
        assertTrue(
            clear.dispatch(
                HomeQuickLaunchPickerMode.Assign(1), HomeQuickLaunchPickerItem.Clear,
                onLaunch = { error("Clear touch must not launch") },
                onAssign = { _, _ -> error("Clear touch must not assign") },
                onClear = cleared::add,
            ),
        )
        assertEquals(listOf(1), cleared)
    }

    @Test
    fun retainedGeneralLaunchRearmsTouchWhileTerminalLaunchStaysConsumed() {
        val activation = HomeQuickLaunchPickerTouchActivation()
        val launched = mutableListOf<String>()

        assertTrue(
            activation.dispatch(
                HomeQuickLaunchPickerMode.General,
                HomeQuickLaunchPickerItem.App(AppOne),
                onLaunch = { packageName ->
                    launched += packageName
                    false
                },
                onAssign = { _, _ -> error("General touch must not assign") },
                onClear = { error("General touch must not clear") },
            ),
        )
        assertTrue(
            activation.dispatch(
                HomeQuickLaunchPickerMode.General,
                HomeQuickLaunchPickerItem.App(AppOne),
                onLaunch = { packageName ->
                    launched += packageName
                    true
                },
                onAssign = { _, _ -> error("General touch must not assign") },
                onClear = { error("General touch must not clear") },
            ),
        )
        assertFalse(
            activation.dispatch(
                HomeQuickLaunchPickerMode.General,
                HomeQuickLaunchPickerItem.App(AppOne),
                onLaunch = { error("Terminal launch must stay consumed") },
                onAssign = { _, _ -> error("Terminal launch must stay consumed") },
                onClear = { error("Terminal launch must stay consumed") },
            ),
        )
        assertEquals(listOf(AppOne.packageName, AppOne.packageName), launched)
    }

    @Test
    fun pickerSeparatesSentenceCaseHelpFromCappedControllerCopy() {
        assertEquals(
            HomeQuickLaunchPickerCopy(
                header = "ᛗ · QUICK LAUNCH",
                instruction = "Choose any installed app.",
                legend = "A OPEN · B BACK",
            ),
            homeQuickLaunchPickerCopy(HomeQuickLaunchPickerMode.General),
        )
        assertEquals(
            HomeQuickLaunchPickerCopy(
                header = "ᛗ · PIN Q3",
                instruction = "Choose an app or clear this slot.",
                legend = "A CHOOSE · B BACK",
            ),
            homeQuickLaunchPickerCopy(HomeQuickLaunchPickerMode.Assign(2)),
        )
    }

    @Test
    fun generalNeverOffersClearWhilePinnedAssignDoes() {
        val apps = listOf(AppOne, AppTwo, AppOne)

        val general = homeQuickLaunchPickerItems(
            mode = HomeQuickLaunchPickerMode.General,
            apps = apps,
            slots = listOf("pkg.one", null, null),
        )
        val pinnedAssign = homeQuickLaunchPickerItems(
            mode = HomeQuickLaunchPickerMode.Assign(0),
            apps = apps,
            slots = listOf("pkg.one", null, null),
        )
        val emptyAssign = homeQuickLaunchPickerItems(
            mode = HomeQuickLaunchPickerMode.Assign(1),
            apps = apps,
            slots = listOf("pkg.one", null, null),
        )

        assertEquals(listOf(AppOne, AppTwo), general.map { (it as HomeQuickLaunchPickerItem.App).entry })
        assertEquals(HomeQuickLaunchPickerItem.Clear, pinnedAssign.first())
        assertEquals(3, pinnedAssign.size)
        assertTrue(emptyAssign.none { it is HomeQuickLaunchPickerItem.Clear })
    }

    @Test
    fun generalUsesSuccessfulLaunchMRUWhileAssignStaysAlphabetical() {
        val apps = listOf(AppTwo, AppOne)
        val sequences = mapOf(AppTwo.packageName to 8L, AppOne.packageName to 4L)

        val general = homeQuickLaunchPickerItems(HomeQuickLaunchPickerMode.General, apps, emptyList(), sequences)
        val assign = homeQuickLaunchPickerItems(HomeQuickLaunchPickerMode.Assign(0), apps, emptyList(), sequences)

        assertEquals(listOf(AppTwo, AppOne), general.map { (it as HomeQuickLaunchPickerItem.App).entry })
        assertEquals(listOf(AppOne, AppTwo), assign.map { (it as HomeQuickLaunchPickerItem.App).entry })
    }

    @Test
    fun runtimeRegistrationThenSeedRestoresGeneralAndKeepsAssignIndependent() {
        val authority = DefaultFocusAuthority()
        val general = listOf(
            HomeQuickLaunchPickerItem.App(AppOne),
            HomeQuickLaunchPickerItem.App(AppTwo),
        )

        authority.pushScope(HomeQuickLaunchPickerScope)
        register(authority, general)
        // registerVirtual provisionally lands on the first app before the composition can restore state.
        assertEquals(homeQuickLaunchPickerKey(general.first()), authority.state.value.focused)
        assertTrue(
            seedHomeQuickLaunchPickerFocus(
                authority = authority,
                mode = HomeQuickLaunchPickerMode.General,
                items = general,
                lastGeneralPackageName = AppTwo.packageName,
            ),
        )
        assertEquals(homeQuickLaunchPickerKey(general.last()), authority.state.value.focused)

        authority.requestFocus(homeQuickLaunchPickerKey(general.first()))
        assertEquals(homeQuickLaunchPickerKey(general.first()), authority.state.value.focused)
        var seedLatch = true
        val refreshedGeneral = general + HomeQuickLaunchPickerItem.App(
            CompanionAppEntry(packageName = "pkg.three", label = "Three"),
        )
        reregisterHomeQuickLaunchPickerFocus(
            authority = authority,
            mode = HomeQuickLaunchPickerMode.General,
            items = refreshedGeneral,
            lastGeneralPackageName = AppTwo.packageName,
            seedLatch = seedLatch,
            preservedFocus = authority.state.value.focused,
            keysToUnregister = general,
        ) { seedLatch = it }
        assertEquals(homeQuickLaunchPickerKey(general.first()), authority.state.value.focused)

        authority.requestFocus(homeQuickLaunchPickerKey(general.last()))
        assertEquals(homeQuickLaunchPickerKey(general.last()), authority.state.value.focused)
        val removedFocusedGeneral = listOf(general.first())
        reregisterHomeQuickLaunchPickerFocus(
            authority = authority,
            mode = HomeQuickLaunchPickerMode.General,
            items = removedFocusedGeneral,
            lastGeneralPackageName = AppTwo.packageName,
            seedLatch = seedLatch,
            preservedFocus = authority.state.value.focused,
            keysToUnregister = refreshedGeneral,
        ) { seedLatch = it }
        assertEquals(homeQuickLaunchPickerKey(general.first()), authority.state.value.focused)

        general.forEach { authority.unregisterVirtual(homeQuickLaunchPickerKey(it)) }
        authority.popScope()

        val assign = listOf(HomeQuickLaunchPickerItem.Clear) + general
        authority.pushScope(HomeQuickLaunchPickerScope)
        register(authority, assign)
        // The shared modal scope may remember General. The explicit post-registration seed must make
        // Assign own its deterministic first tile instead of inheriting the last launched app.
        assertTrue(
            seedHomeQuickLaunchPickerFocus(
                authority = authority,
                mode = HomeQuickLaunchPickerMode.Assign(slotIndex = 0),
                items = assign,
                lastGeneralPackageName = AppTwo.packageName,
            ),
        )
        assertEquals(homeQuickLaunchPickerKey(HomeQuickLaunchPickerItem.Clear), authority.state.value.focused)
    }

    @Test
    fun emptyInitialListThenHydrationRestoresRememberedGeneralFocus() {
        val authority = DefaultFocusAuthority()
        var seedLatch = false
        val hydrated = listOf(
            HomeQuickLaunchPickerItem.App(AppOne),
            HomeQuickLaunchPickerItem.App(AppTwo),
        )

        authority.pushScope(HomeQuickLaunchPickerScope)
        reregisterHomeQuickLaunchPickerFocus(
            authority = authority,
            mode = HomeQuickLaunchPickerMode.General,
            items = emptyList(),
            lastGeneralPackageName = AppTwo.packageName,
            seedLatch = seedLatch,
            preservedFocus = null,
            keysToUnregister = emptyList(),
        ) { seedLatch = it }
        assertEquals(false, seedLatch)

        reregisterHomeQuickLaunchPickerFocus(
            authority = authority,
            mode = HomeQuickLaunchPickerMode.General,
            items = hydrated,
            lastGeneralPackageName = AppTwo.packageName,
            seedLatch = seedLatch,
            preservedFocus = null,
            keysToUnregister = emptyList(),
        ) { seedLatch = it }
        assertEquals(homeQuickLaunchPickerKey(hydrated.last()), authority.state.value.focused)
        assertEquals(true, seedLatch)
    }

    @Test
    fun assignModeSeedsClearWhenModeKeyedPreservedFocusIsAbsent() {
        val authority = DefaultFocusAuthority()
        val general = listOf(
            HomeQuickLaunchPickerItem.App(AppOne),
            HomeQuickLaunchPickerItem.App(AppTwo),
        )
        val assign = listOf(HomeQuickLaunchPickerItem.Clear) + general

        authority.pushScope(HomeQuickLaunchPickerScope)
        register(authority, general)
        authority.requestFocus(homeQuickLaunchPickerKey(general.last()))
        general.forEach { authority.unregisterVirtual(homeQuickLaunchPickerKey(it)) }
        register(authority, assign)
        // remember(mode) resets preserved registration focus, so Assign cannot inherit General's tile.
        reregisterHomeQuickLaunchPickerFocus(
            authority = authority,
            mode = HomeQuickLaunchPickerMode.Assign(slotIndex = 0),
            items = assign,
            lastGeneralPackageName = AppTwo.packageName,
            seedLatch = false,
            preservedFocus = null,
            keysToUnregister = emptyList(),
        ) { }
        assertEquals(homeQuickLaunchPickerKey(HomeQuickLaunchPickerItem.Clear), authority.state.value.focused)
        assertNotEquals(homeQuickLaunchPickerKey(general.last()), authority.state.value.focused)
    }

    @Test
    fun threeColumnNavigationClampsAtEveryBoundaryAndNeverWraps() {
        val count = 8

        assertEquals(0, nextHomeQuickLaunchPickerIndex(0, count, ControllerAction.NAV_LEFT))
        assertEquals(2, nextHomeQuickLaunchPickerIndex(2, count, ControllerAction.NAV_RIGHT))
        assertEquals(1, nextHomeQuickLaunchPickerIndex(1, count, ControllerAction.NAV_UP))
        assertEquals(7, nextHomeQuickLaunchPickerIndex(7, count, ControllerAction.NAV_DOWN))
        assertEquals(0, nextHomeQuickLaunchPickerIndex(-1, count, ControllerAction.NAV_RIGHT))
        assertEquals(7, nextHomeQuickLaunchPickerIndex(7, count, ControllerAction.NAV_RIGHT))
        assertEquals(6, nextHomeQuickLaunchPickerIndex(6, count, ControllerAction.NAV_DOWN))
    }

    @Test
    fun navDownClampsShortFinalRowToLastItemWithoutLeavingTheLastRow() {
        val count = 8

        assertEquals(3, nextHomeQuickLaunchPickerIndex(0, count, ControllerAction.NAV_DOWN))
        assertEquals(4, nextHomeQuickLaunchPickerIndex(1, count, ControllerAction.NAV_DOWN))
        assertEquals(5, nextHomeQuickLaunchPickerIndex(2, count, ControllerAction.NAV_DOWN))
        assertEquals(6, nextHomeQuickLaunchPickerIndex(3, count, ControllerAction.NAV_DOWN))
        assertEquals(7, nextHomeQuickLaunchPickerIndex(4, count, ControllerAction.NAV_DOWN))
        assertEquals(7, nextHomeQuickLaunchPickerIndex(5, count, ControllerAction.NAV_DOWN))
        assertEquals(6, nextHomeQuickLaunchPickerIndex(6, count, ControllerAction.NAV_DOWN))
        assertEquals(7, nextHomeQuickLaunchPickerIndex(7, count, ControllerAction.NAV_DOWN))
    }

    @Test
    fun navDownUsesFullColumnWhenFinalRowIsComplete() {
        val count = 9

        assertEquals(6, nextHomeQuickLaunchPickerIndex(3, count, ControllerAction.NAV_DOWN))
        assertEquals(7, nextHomeQuickLaunchPickerIndex(4, count, ControllerAction.NAV_DOWN))
        assertEquals(8, nextHomeQuickLaunchPickerIndex(5, count, ControllerAction.NAV_DOWN))
        assertEquals(8, nextHomeQuickLaunchPickerIndex(8, count, ControllerAction.NAV_DOWN))
    }

    @Test
    fun minimumRevealReturnsZeroWhenFocusedTileIsAlreadyFullyVisible() {
        assertEquals(
            0,
            homeQuickLaunchPickerRevealDeltaPx(
                itemTopPx = 120,
                itemBottomPx = 220,
                viewportTopPx = 100,
                viewportBottomPx = 240,
                marginPx = 16,
            ),
        )
    }

    @Test
    fun minimumRevealMovesOnlyTheDeficitAndKeepsTheRingOffTheEdge() {
        assertEquals(
            -20,
            homeQuickLaunchPickerRevealDeltaPx(
                itemTopPx = 96,
                itemBottomPx = 196,
                viewportTopPx = 100,
                viewportBottomPx = 240,
                marginPx = 16,
            ),
        )
        assertEquals(
            12,
            homeQuickLaunchPickerRevealDeltaPx(
                itemTopPx = 120,
                itemBottomPx = 236,
                viewportTopPx = 100,
                viewportBottomPx = 240,
                marginPx = 16,
            ),
        )
    }

    @Test
    fun rowPitchIsReadFromTheLayoutRatherThanAssumed() {
        // Two measured rows give height + spacing exactly.
        assertEquals(
            112,
            homeQuickLaunchPickerRowPitchPx(
                rowTops = listOf(0 to 100, 0 to 100, 1 to 212, 1 to 212),
                fallbackHeightPx = 100,
            ),
        )
        // A gap in the visible rows still divides out to the per-row pitch.
        assertEquals(
            112,
            homeQuickLaunchPickerRowPitchPx(rowTops = listOf(2 to 100, 4 to 324), fallbackHeightPx = 100),
        )
        // One row visible: preserve the authored gap so the single projection reaches the ring margin.
        assertEquals(
            112,
            homeQuickLaunchPickerRowPitchPx(
                rowTops = listOf(3 to 100),
                fallbackHeightPx = 100,
                fallbackGapPx = 12,
            ),
        )
        assertEquals(0, homeQuickLaunchPickerRowPitchPx(rowTops = emptyList(), fallbackHeightPx = -5))
    }

    @Test
    fun steppingOntoTheContentEdgeAnimatesOneRowInsteadOfSeekingAPage() {
        // Anchor: row 3 measured with its top at 240, 112px pitch, 100px tall, viewport 0..340.
        // Row 4 therefore projects to 352..452 — past the edge by exactly one row plus its clearance.
        val delta = homeQuickLaunchPickerProjectedRevealDeltaPx(
            targetRow = 4,
            anchorRow = 3,
            anchorTopPx = 240,
            rowPitchPx = 112,
            rowHeightPx = 100,
            viewportTopPx = 0,
            viewportBottomPx = 340,
            marginPx = 16,
        )
        // Minimum reveal: bottom (452) + margin (16) - viewport bottom (340) = 128. One row of travel,
        // not the whole page a scrollToItem would have jumped.
        assertEquals(128, delta)
        assertTrue("a downward step must scroll forward", delta > 0)

        // Stepping UP onto a row above the viewport is symmetric and negative.
        assertEquals(
            -128,
            homeQuickLaunchPickerProjectedRevealDeltaPx(
                targetRow = 2,
                anchorRow = 3,
                anchorTopPx = 0,
                rowPitchPx = 112,
                rowHeightPx = 100,
                viewportTopPx = 0,
                viewportBottomPx = 340,
                marginPx = 16,
            ),
        )
        // Degenerate pitch never scrolls a guess.
        assertEquals(
            0,
            homeQuickLaunchPickerProjectedRevealDeltaPx(
                targetRow = 4,
                anchorRow = 3,
                anchorTopPx = 240,
                rowPitchPx = 0,
                rowHeightPx = 100,
                viewportTopPx = 0,
                viewportBottomPx = 340,
                marginPx = 16,
            ),
        )
    }

    @Test
    fun onlyAnAdjacentStepIsProjectedAndAFarRestoreStillSeeks() {
        assertTrue(homeQuickLaunchPickerCanProjectReveal(targetRow = 4, anchorRow = 3))
        assertTrue(homeQuickLaunchPickerCanProjectReveal(targetRow = 2, anchorRow = 4))
        // Same row is not a reveal at all.
        assertFalse(homeQuickLaunchPickerCanProjectReveal(targetRow = 3, anchorRow = 3))
        // A restored selection deep in the list has no useful anchor — extrapolating that far from one
        // row's geometry would be a guess, so it seeks instead.
        assertFalse(homeQuickLaunchPickerCanProjectReveal(targetRow = 19, anchorRow = 0))
    }

    @Test
    fun oneRevealCommandBelongsToOneStableFocusEpoch() {
        val ledger = HomeQuickLaunchPickerRevealLedger()
        val first = ledger.issue(
            "app/a",
            HomeQuickLaunchPickerRevealCommand.Measured(deltaPx = 12),
        )!!

        assertEquals(1L, first.epoch)
        assertEquals(
            HomeQuickLaunchPickerRevealCommand.Measured(deltaPx = 12),
            first.command,
        )
        assertNull(
            "repeating the same stable destination must not issue another command",
            ledger.issue(
                "app/a",
                HomeQuickLaunchPickerRevealCommand.Projected(deltaPx = 40),
            ),
        )
    }

    @Test
    fun rapidRetargetLeavesOnlyTheLatestRevealEpochAuthoritative() {
        val ledger = HomeQuickLaunchPickerRevealLedger()
        val a = ledger.issue("app/a", HomeQuickLaunchPickerRevealCommand.Measured(12))!!
        val b = ledger.issue("app/b", HomeQuickLaunchPickerRevealCommand.Projected(112))!!
        val c = ledger.issue("app/c", HomeQuickLaunchPickerRevealCommand.Measured(-12))!!

        assertFalse(ledger.accepts(a.epoch))
        assertFalse(ledger.accepts(b.epoch))
        assertTrue(ledger.accepts(c.epoch))
        assertEquals(c, ledger.latest())
        assertEquals("app/c", ledger.latest()?.targetStableId)
    }

    @Test
    fun composedAndAdjacentUncomposedTargetsResolveOneCommandWithoutACorrectionPass() {
        assertEquals(
            HomeQuickLaunchPickerRevealCommand.Measured(deltaPx = 16),
            homeQuickLaunchPickerRevealCommand(
                targetIndex = 7,
                visibleItems = listOf(
                    HomeQuickLaunchPickerVisibleItem(index = 6, topPx = 240, heightPx = 100),
                    HomeQuickLaunchPickerVisibleItem(index = 7, topPx = 240, heightPx = 100),
                ),
                viewportTopPx = 0,
                viewportBottomPx = 340,
                marginPx = 16,
            ),
        )
        assertEquals(
            HomeQuickLaunchPickerRevealCommand.Projected(deltaPx = 128),
            homeQuickLaunchPickerRevealCommand(
                targetIndex = 12,
                visibleItems = listOf(
                    HomeQuickLaunchPickerVisibleItem(index = 9, topPx = 240, heightPx = 100),
                    HomeQuickLaunchPickerVisibleItem(index = 10, topPx = 240, heightPx = 100),
                    HomeQuickLaunchPickerVisibleItem(index = 6, topPx = 128, heightPx = 100),
                ),
                viewportTopPx = 0,
                viewportBottomPx = 340,
                marginPx = 16,
            ),
        )
        assertEquals(
            HomeQuickLaunchPickerRevealCommand.Projected(deltaPx = 128),
            homeQuickLaunchPickerRevealCommand(
                targetIndex = 12,
                visibleItems = listOf(
                    HomeQuickLaunchPickerVisibleItem(index = 9, topPx = 240, heightPx = 100),
                ),
                viewportTopPx = 0,
                viewportBottomPx = 340,
                marginPx = 16,
                fallbackRowGapPx = 12,
            ),
        )
    }

    @Test
    fun firstAndLastMeasuredCommandsPreserveTheRingMarginExactly() {
        val first = homeQuickLaunchPickerRevealCommand(
            targetIndex = 0,
            visibleItems = listOf(HomeQuickLaunchPickerVisibleItem(index = 0, topPx = 0, heightPx = 100)),
            viewportTopPx = 0,
            viewportBottomPx = 340,
            marginPx = 16,
        )
        val last = homeQuickLaunchPickerRevealCommand(
            targetIndex = 7,
            visibleItems = listOf(HomeQuickLaunchPickerVisibleItem(index = 7, topPx = 240, heightPx = 100)),
            viewportTopPx = 0,
            viewportBottomPx = 340,
            marginPx = 16,
        )

        assertEquals(HomeQuickLaunchPickerRevealCommand.Measured(-16), first)
        assertEquals(HomeQuickLaunchPickerRevealCommand.Measured(16), last)
    }

    @Test
    fun farRestoreIsPrepositionedBeforePresentationAndIssuesNoVisibleCorrection() {
        val plan = homeQuickLaunchPickerOpeningPlan(
            targetIndex = 30,
            targetStableId = "app/restored",
        )
        assertEquals(30, plan.firstVisibleItemIndex)
        assertEquals("app/restored", plan.preVisibleSettledTargetStableId)

        val ledger = HomeQuickLaunchPickerRevealLedger(plan.preVisibleSettledTargetStableId)
        assertNull(
            ledger.issue(
                "app/restored",
                HomeQuickLaunchPickerRevealCommand.Measured(deltaPx = 4),
            ),
        )
        assertNull("no visible reveal epoch exists for the pre-visible seek", ledger.latest())
    }

    @Test
    fun theLastPartialRowEndsFullyRevealedWithItsRingClearance() {
        // 8 apps in a 3-wide grid: the last row holds indices 6 and 7 only.
        val count = 8
        assertEquals(2, homeQuickLaunchPickerRowOf(count - 1))
        assertEquals(7, nextHomeQuickLaunchPickerIndex(4, count, ControllerAction.NAV_DOWN))
        assertEquals(7, nextHomeQuickLaunchPickerIndex(5, count, ControllerAction.NAV_DOWN))
        // The terminal element sits flush against the content edge (bottom == viewport bottom): the
        // reveal must still move, or the ring is clipped by the footer.
        val flush = homeQuickLaunchPickerRevealDeltaPx(
            itemTopPx = 240,
            itemBottomPx = 340,
            viewportTopPx = 0,
            viewportBottomPx = 340,
            marginPx = 16,
        )
        assertEquals(16, flush)
        // After that move it is fully revealed and further presses on the last element cost nothing.
        assertEquals(
            0,
            homeQuickLaunchPickerRevealDeltaPx(
                itemTopPx = 224,
                itemBottomPx = 324,
                viewportTopPx = 0,
                viewportBottomPx = 340,
                marginPx = 16,
            ),
        )
        // And the last element never wraps to the start.
        assertEquals(7, nextHomeQuickLaunchPickerIndex(7, count, ControllerAction.NAV_DOWN))
        assertEquals(7, nextHomeQuickLaunchPickerIndex(7, count, ControllerAction.NAV_RIGHT))
    }

    @Test
    fun theFirstRowIsRevealedAtTheStartEdgeAndNeverWrapsBackwards() {
        val count = 8
        assertEquals(0, homeQuickLaunchPickerRowOf(0))
        assertEquals(0, nextHomeQuickLaunchPickerIndex(0, count, ControllerAction.NAV_UP))
        assertEquals(0, nextHomeQuickLaunchPickerIndex(0, count, ControllerAction.NAV_LEFT))
        // Flush against the start edge: pull back by exactly the ring clearance, no more.
        assertEquals(
            -16,
            homeQuickLaunchPickerRevealDeltaPx(
                itemTopPx = 0,
                itemBottomPx = 100,
                viewportTopPx = 0,
                viewportBottomPx = 340,
                marginPx = 16,
            ),
        )
    }

    @Test
    fun reopeningGeneralRestoresTheExactRememberedAppAndItsRow() {
        val items = listOf(
            HomeQuickLaunchPickerItem.App(AppOne),
            HomeQuickLaunchPickerItem.App(AppTwo),
            HomeQuickLaunchPickerItem.App(CompanionAppEntry(packageName = "pkg.three", label = "Three")),
            HomeQuickLaunchPickerItem.App(CompanionAppEntry(packageName = "pkg.four", label = "Four")),
            HomeQuickLaunchPickerItem.App(CompanionAppEntry(packageName = "pkg.five", label = "Five")),
        )

        // Exact focus, not "somewhere on the right row".
        val restored = homeQuickLaunchPickerInitialIndex(
            HomeQuickLaunchPickerMode.General,
            items,
            lastGeneralPackageName = "pkg.four",
        )
        assertEquals(3, restored)
        assertEquals(1, homeQuickLaunchPickerRowOf(restored))

        // A remembered app that is gone falls back to the first item rather than a stale row.
        assertEquals(
            0,
            homeQuickLaunchPickerInitialIndex(
                HomeQuickLaunchPickerMode.General,
                items,
                lastGeneralPackageName = "pkg.uninstalled",
            ),
        )
        // Assign keeps its own deterministic rule and never inherits General's memory.
        assertEquals(
            0,
            homeQuickLaunchPickerInitialIndex(
                HomeQuickLaunchPickerMode.Assign(1),
                items,
                lastGeneralPackageName = "pkg.four",
            ),
        )
    }

    @Test
    fun generalConfirmLaunchesSelectedApp() {
        val items = listOf(HomeQuickLaunchPickerItem.App(AppOne))
        val authority = registeredAuthority(items)
        val launched = mutableListOf<String>()

        assertTrue(
            dispatchHomeQuickLaunchPicker(
                authority = authority,
                mode = HomeQuickLaunchPickerMode.General,
                items = items,
                action = ControllerAction.CONFIRM,
                onLaunch = launched::add,
                onAssign = { _, _ -> error("assign must not run") },
                onClear = { error("clear must not run") },
                onDismiss = {},
            ),
        )

        assertEquals(listOf("pkg.one"), launched)
    }

    @Test
    fun assignConfirmWritesTheRequestedSlot() {
        val items = listOf(HomeQuickLaunchPickerItem.App(AppTwo))
        val authority = registeredAuthority(items)
        val assignments = mutableListOf<Pair<Int, String>>()

        dispatchHomeQuickLaunchPicker(
            authority = authority,
            mode = HomeQuickLaunchPickerMode.Assign(2),
            items = items,
            action = ControllerAction.CONFIRM,
            onLaunch = { error("launch must not run") },
            onAssign = { index, packageName -> assignments += index to packageName },
            onClear = { error("clear must not run") },
            onDismiss = {},
        )

        assertEquals(listOf(2 to "pkg.two"), assignments)
    }

    @Test
    fun clearTileClearsOnlyItsAssignSlot() {
        val items = listOf(HomeQuickLaunchPickerItem.Clear, HomeQuickLaunchPickerItem.App(AppOne))
        val authority = registeredAuthority(items)
        val cleared = mutableListOf<Int>()

        dispatchHomeQuickLaunchPicker(
            authority = authority,
            mode = HomeQuickLaunchPickerMode.Assign(1),
            items = items,
            action = ControllerAction.CONFIRM,
            onLaunch = { error("launch must not run") },
            onAssign = { _, _ -> error("assign must not run") },
            onClear = cleared::add,
            onDismiss = {},
        )

        assertEquals(listOf(1), cleared)
    }

    @Test
    fun backDismissesAndDirectionFromDegenerateFocusLandsOnFirstTile() {
        val items = listOf(HomeQuickLaunchPickerItem.App(AppOne), HomeQuickLaunchPickerItem.App(AppTwo))
        val authority = registeredAuthority(items, requestFirst = false)
        var dismissed = 0

        dispatchHomeQuickLaunchPicker(
            authority = authority,
            mode = HomeQuickLaunchPickerMode.General,
            items = items,
            action = ControllerAction.NAV_DOWN,
            onLaunch = {},
            onAssign = { _, _ -> },
            onClear = {},
            onDismiss = { dismissed++ },
        )
        assertEquals(homeQuickLaunchPickerKey(items.first()), authority.state.value.focused)

        dispatchHomeQuickLaunchPicker(
            authority = authority,
            mode = HomeQuickLaunchPickerMode.General,
            items = items,
            action = ControllerAction.SYSTEM_BACK,
            onLaunch = {},
            onAssign = { _, _ -> },
            onClear = {},
            onDismiss = { dismissed++ },
        )
        assertEquals(1, dismissed)
    }

    private fun registeredAuthority(
        items: List<HomeQuickLaunchPickerItem>,
        requestFirst: Boolean = true,
    ) = DefaultFocusAuthority().also { authority ->
        authority.pushScope(HomeQuickLaunchPickerScope)
        register(authority, items)
        if (!requestFirst) authority.clearFocus()
    }

    private fun register(
        authority: DefaultFocusAuthority,
        items: List<HomeQuickLaunchPickerItem>,
    ) {
        items.forEachIndexed { index, item ->
            authority.registerVirtual(
                key = homeQuickLaunchPickerKey(item),
                scope = HomeQuickLaunchPickerScope,
                bounds = FocusBounds(0f, index * 100f, 90f, index * 100f + 90f),
            )
        }
    }

    private fun reregisterHomeQuickLaunchPickerFocus(
        authority: DefaultFocusAuthority,
        mode: HomeQuickLaunchPickerMode,
        items: List<HomeQuickLaunchPickerItem>,
        lastGeneralPackageName: String?,
        seedLatch: Boolean,
        preservedFocus: FocusKey?,
        keysToUnregister: List<HomeQuickLaunchPickerItem>,
        onSeedLatchChanged: (Boolean) -> Unit,
    ) {
        val keys = items.map(::homeQuickLaunchPickerKey)
        keysToUnregister.forEach { authority.unregisterVirtual(homeQuickLaunchPickerKey(it)) }
        register(authority, items)
        val preserved = preservedFocus?.takeIf { it in keys }
        val authorityFocused = authority.state.value.focused?.takeIf { it in keys }
        val survivingFocus = when {
            preserved != null -> preserved
            seedLatch -> authorityFocused
            else -> null
        }
        if (survivingFocus != null) {
            authority.requestFocus(survivingFocus)
        } else if (!seedLatch) {
            onSeedLatchChanged(seedHomeQuickLaunchPickerFocus(authority, mode, items, lastGeneralPackageName))
        } else {
            seedHomeQuickLaunchPickerFocus(authority, mode, items, lastGeneralPackageName)
        }
    }

    private companion object {
        val AppOne = CompanionAppEntry(packageName = "pkg.one", label = "One")
        val AppTwo = CompanionAppEntry(packageName = "pkg.two", label = "Two")
    }
}
