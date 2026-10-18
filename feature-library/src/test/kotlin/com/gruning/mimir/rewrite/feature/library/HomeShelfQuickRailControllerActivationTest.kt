package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.ui.MimirQuickRailAction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Alpha-RC1 Home dock controller activation: a directly focused rail command must dispatch once on CONFIRM
 * through the existing [dispatchToHomeShelf] reducer seam (controller path stays in the feature reducer).
 */
class HomeShelfQuickRailControllerActivationTest {
    @Test
    fun directlyFocusedSettingsConfirmDispatchesOnce() {
        assertDirectConfirmDispatchesOnce(MimirQuickRailAction.SETTINGS)
    }

    @Test
    fun directlyFocusedLibraryConfirmDispatchesOnce() {
        assertDirectConfirmDispatchesOnce(MimirQuickRailAction.LIBRARY)
    }

    @Test
    fun directlyFocusedUtilityConfirmDispatchesOnce() {
        assertDirectConfirmDispatchesOnce(MimirQuickRailAction.BRIGHTNESS)
    }

    /**
     * Release UX/UI gate, sibling-inconsistency finding (charter §3.2): X (SECONDARY) opens the Tome
     * Library from home/shelf ("X opens the Tome Library without changing the exact focused root
     * identity", `HomeShelfModels.kt`'s `dispatchHomeContentAction` `ControllerAction.SECONDARY` branch)
     * but silently did nothing from home/rail one D-pad press away — the same physical button, live or
     * dead purely by which of the two Home scopes currently owns focus, with no visual difference in
     * either state to warn the player. TERTIARY/SYSTEM_SELECT are deliberately left unfixed: both act on
     * a focused GAME entry, and the rail has none to act on, so a no-op there is the correct behavior,
     * not an oversight — only SECONDARY names a genuinely global, game-independent action.
     */
    @Test
    fun secondaryActionInTheDockOpensTomeLibraryLikeItDoesInTheShelf() {
        val authority = DefaultFocusAuthority().apply {
            registerHomeQuickRail()
            pushScope(HomeShelfQuickRailScope)
            requestFocus(homeShelfQuickRailKey(MimirQuickRailAction.HOME))
        }
        var tomesOpened = 0

        dispatchToHomeShelf(
            authority = authority,
            rows = emptyList(),
            action = ControllerAction.SECONDARY,
            onLaunch = {},
            onOpenLibrary = {},
            onQuickAction = {},
            onExit = {},
            onTomes = { tomesOpened += 1 },
        )

        assertEquals(1, tomesOpened)
    }

    private fun assertDirectConfirmDispatchesOnce(action: MimirQuickRailAction) {
        val authority = DefaultFocusAuthority().apply {
            registerHomeQuickRail()
            pushScope(HomeShelfQuickRailScope)
            requestFocus(homeShelfQuickRailKey(action))
        }
        val activated = mutableListOf<MimirQuickRailAction>()

        dispatchToHomeShelf(
            authority = authority,
            rows = emptyList(),
            action = ControllerAction.CONFIRM,
            onLaunch = {},
            onOpenLibrary = {},
            onQuickAction = activated::add,
            onExit = {},
        )

        assertEquals(listOf(action), activated)
    }

    private fun DefaultFocusAuthority.registerHomeQuickRail() {
        HomeShelfQuickActions.forEachIndexed { index, railAction ->
            register(
                key = homeShelfQuickRailKey(railAction),
                scope = HomeShelfQuickRailScope,
                bounds = FocusBounds(
                    left = index * 100f,
                    top = 1_000f,
                    right = index * 100f + 50f,
                    bottom = 1_050f,
                ),
            )
        }
    }
}
