package com.gruning.mimir.rewrite.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.mimirFocusable
import com.gruning.mimir.rewrite.core.ui.MimirQuickRailAction
import com.gruning.mimir.rewrite.core.ui.MimirQuickRailTouchPhase
import com.gruning.mimir.rewrite.core.ui.MimirRunicQuickRail
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Alpha-RC1 Home dock semantic activation: [MimirRunicQuickRail] must request focus on the first
 * semantic/touch click and dispatch exactly once on the second click while focused.
 */
@RunWith(AndroidJUnit4::class)
class MimirRunicQuickRailSemanticActivationTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun settingsTouchFocusesWithoutDispatchThenSecondTapActivatesOnce() {
        assertTouchFocusThenActivate(
            contentDescription = "Settings",
            action = MimirQuickRailAction.SETTINGS,
        )
    }

    @Test
    fun libraryTouchFocusesWithoutDispatchThenSecondTapActivatesOnce() {
        assertTouchFocusThenActivate(
            contentDescription = "Library",
            action = MimirQuickRailAction.LIBRARY,
        )
    }

    @Test
    fun utilityTouchFocusesWithoutDispatchThenSecondTapActivatesOnce() {
        assertTouchFocusThenActivate(
            contentDescription = "Utility",
            action = MimirQuickRailAction.BRIGHTNESS,
        )
    }

    @Test
    fun coordinateTapReportsTheExistingDetectorPhasesAndStillActivatesOnce() {
        val phases = mutableListOf<MimirQuickRailTouchPhase>()
        val activations = mutableListOf<MimirQuickRailAction>()

        compose.setContent {
            MimirTheme {
                Box(Modifier.size(width = 700.dp, height = 100.dp)) {
                    MimirRunicQuickRail(
                        selectedAction = MimirQuickRailAction.HOME,
                        focusedAction = null,
                        onActionFocus = {},
                        onActionActivate = activations::add,
                        onActionTouch = { action, phase, _ ->
                            if (action == MimirQuickRailAction.LIBRARY) phases += phase
                        },
                    )
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Library").performTouchInput { click(center) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, phases.count { it == MimirQuickRailTouchPhase.DOWN })
            assertEquals(1, phases.count { it == MimirQuickRailTouchPhase.UP })
            assertEquals(1, phases.count { it == MimirQuickRailTouchPhase.TAP })
            assertEquals(3, phases.size)
            assertEquals(listOf(MimirQuickRailAction.LIBRARY), activations)
        }
    }

    private fun assertTouchFocusThenActivate(
        contentDescription: String,
        action: MimirQuickRailAction,
    ) {
        val authority = DefaultFocusAuthority().apply { registerHomeQuickRail() }
        val activations = mutableListOf<MimirQuickRailAction>()

        compose.setContent {
            MimirTheme {
                val focusState by authority.state.collectAsState()
                val homeRailModifier = Modifier.mimirFocusable(
                    authority = authority,
                    key = homeShelfQuickRailKey(MimirQuickRailAction.HOME),
                    scope = HomeShelfQuickRailScope,
                    autoBringIntoView = false,
                )
                val libraryRailModifier = Modifier.mimirFocusable(
                    authority = authority,
                    key = homeShelfQuickRailKey(MimirQuickRailAction.LIBRARY),
                    scope = HomeShelfQuickRailScope,
                    autoBringIntoView = false,
                )
                val brightnessRailModifier = Modifier.mimirFocusable(
                    authority = authority,
                    key = homeShelfQuickRailKey(MimirQuickRailAction.BRIGHTNESS),
                    scope = HomeShelfQuickRailScope,
                    autoBringIntoView = false,
                )
                val settingsRailModifier = Modifier.mimirFocusable(
                    authority = authority,
                    key = homeShelfQuickRailKey(MimirQuickRailAction.SETTINGS),
                    scope = HomeShelfQuickRailScope,
                    autoBringIntoView = false,
                )
                val quickLaunchRailModifier = Modifier.mimirFocusable(
                    authority = authority,
                    key = homeShelfQuickRailKey(MimirQuickRailAction.QUICK_LAUNCH),
                    scope = HomeShelfQuickRailScope,
                    autoBringIntoView = false,
                )
                val filesRailModifier = Modifier.mimirFocusable(
                    authority = authority,
                    key = homeShelfQuickRailKey(MimirQuickRailAction.FILES),
                    scope = HomeShelfQuickRailScope,
                    autoBringIntoView = false,
                )
                Box(Modifier.size(width = 700.dp, height = 100.dp)) {
                    MimirRunicQuickRail(
                        selectedAction = MimirQuickRailAction.HOME,
                        focusedAction = if (focusState.scope == HomeShelfQuickRailScope) {
                            homeShelfQuickRailAction(focusState.focused)
                        } else {
                            null
                        },
                        onActionFocus = { target ->
                            if (authority.state.value.scope != HomeShelfQuickRailScope) {
                                authority.pushScope(HomeShelfQuickRailScope)
                            }
                            authority.requestFocus(homeShelfQuickRailKey(target))
                        },
                        onActionActivate = { target ->
                            activateHomeShelfQuickAction(authority, target, activations::add)
                        },
                        actions = HomeShelfQuickActions,
                        actionModifier = { railAction ->
                            when (railAction) {
                                MimirQuickRailAction.HOME -> homeRailModifier
                                MimirQuickRailAction.LIBRARY -> libraryRailModifier
                                MimirQuickRailAction.BRIGHTNESS -> brightnessRailModifier
                                MimirQuickRailAction.SETTINGS -> settingsRailModifier
                                MimirQuickRailAction.QUICK_LAUNCH -> quickLaunchRailModifier
                                MimirQuickRailAction.FILES -> filesRailModifier
                            }
                        },
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            authority.pushScope(HomeShelfQuickRailScope)
            authority.requestFocus(homeShelfQuickRailKey(MimirQuickRailAction.HOME))
        }
        compose.waitForIdle()

        // touch acts like a physical input. This test previously pinned the
        // OPPOSITE -- "first semantic tap must focus without dispatch" -- which was the two-tap grammar
        // which is retired. Rewritten rather than deleted: the retired behaviour is exactly what must not
        // come back, so the assertion is inverted instead of removed.
        compose.onNodeWithContentDescription(contentDescription).performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(
                "ONE semantic tap on $contentDescription must dispatch exactly once. A tap that only " +
                    "moved focus is the retired grammar, and on a new control it reads as a control " +
                    "that does nothing.",
                listOf(action),
                activations,
            )
        }

        compose.onNodeWithContentDescription(contentDescription).performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(
                "a second tap dispatches again -- activation is per-press, never a toggle that " +
                    "swallows the first one",
                listOf(action, action),
                activations,
            )
        }
    }

    private fun DefaultFocusAuthority.registerHomeQuickRail() {
        HomeShelfQuickActions.forEachIndexed { index, action ->
            register(
                key = homeShelfQuickRailKey(action),
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
