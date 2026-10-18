package com.gruning.mimir.rewrite

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression guard for wrapping [ArtworkFetchOverlay]/[GetArtOverlay]'s phase `when` in `AnimatedContent`
 *: the new `isLive` gate must stay TRUE for the
 * live, steady-state page (no transition in flight -- true on first composition, since `AnimatedContent`
 * has no previous state to differ from), or the fix would silently disable every button in the overlay.
 *
 * Buttons here are labelled via `MimirChromeText` -> `MimirIntentText`, which renders the UPPERCASE visual
 * projection as the `Text` node's literal string but `clearAndSetSemantics { contentDescription = ... }`s
 * the natural-case spoken string over it (`MimirTextIntent.kt:130-134`) -- so `onNodeWithText("Cancel")`
 * finds nothing (the semantics tree carries no `Text`/`EditableText` property at all past that clear) and
 * the query must be `onNodeWithContentDescription`, `useUnmergedTree = true`, matching this package's own
 * existing convention for clicking a `MimirChromeText` label (`HomeTomeFolioTouchGrammarTest.kt:137` etc).
 * Found empirically: an earlier revision of this file used `onNodeWithText` and every one of these six
 * tests failed identically ("could not find any node... Text + EditableText") regardless of overlay or
 * label -- i.e. it was this matcher choice, not the production change, and is recorded here so the next
 * person does not repeat the same wrong turn.
 *
 * This is deliberately NOT a mid-transition test. This module's Robolectric `ComposeTestRule` does not
 * reliably drive recomposition under a manually-paused animation clock (`HomeLaunchCardBlurScopeMechanismTest`'s
 * KDoc documents this as a harness limitation this package has hit before, not a claim about production
 * behaviour) -- `PhaseSequenceGrammarSourceTest` in `ArtworkPhaseSequenceGrammarTest.kt` covers the
 * departing-page inertness guard structurally instead. What THIS test proves, by actually running Compose:
 * the steady-state path -- which is what the overlay spends nearly all of its time in -- was not broken by
 * introducing that guard.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ArtworkFetchOverlayLiveDispatchTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `steady Running state still dispatches Cancel to the live callback`() {
        var cancelled = false
        compose.setContent {
            MimirTheme {
                ArtworkFetchOverlay(
                    state = ArtworkFetchUi.Running(done = 3, total = 10, matched = 1),
                    onCancel = { cancelled = true },
                    onClose = {},
                )
            }
        }
        compose.onNodeWithContentDescription("Cancel", useUnmergedTree = true).performClick()
        assertTrue("the live page's Cancel must still reach onCancel", cancelled)
    }

    @Test
    fun `steady Done state still dispatches Close to the live callback`() {
        var closed = false
        compose.setContent {
            MimirTheme {
                ArtworkFetchOverlay(
                    state = ArtworkFetchUi.Done(total = 10, matched = 5),
                    onCancel = {},
                    onClose = { closed = true },
                )
            }
        }
        compose.onNodeWithContentDescription("Close", useUnmergedTree = true).performClick()
        assertTrue("the live page's Close must still reach onClose", closed)
    }

    @Test
    fun `steady Cancelled state still dispatches Close to the live callback`() {
        var closed = false
        compose.setContent {
            MimirTheme {
                ArtworkFetchOverlay(
                    state = ArtworkFetchUi.Cancelled(matched = 2),
                    onCancel = {},
                    onClose = { closed = true },
                )
            }
        }
        compose.onNodeWithContentDescription("Close", useUnmergedTree = true).performClick()
        assertTrue(closed)
    }

    /**
     * Spec's own test list, first item: "a progress tick does not restart the transition." Unlike the
     * inert-departure guard, this one does NOT require sampling mid-flight (no manually-paused clock,
     * no assumption about what is on screen while an animation is running) -- it only needs the LIVE
     * `Running` entry to keep receiving fresh `state` values through `contentKey` equality, which a
     * normal `waitForIdle()` settles. If `AnimatedContent` ever froze the incoming entry on its first
     * composed value instead of recomposing it with each new target, the count line would freeze for the
     * whole fetch while every other gate here stayed green -- this is the test that would catch that.
     * The count line is a plain `Text` (unlike the button labels), so `onNodeWithText` is the right
     * matcher here, no `MimirChromeText` contentDescription indirection involved.
     */
    @Test
    fun `a progress tick updates the live Running page's count line without restarting the transition`() {
        var state by mutableStateOf<ArtworkFetchUi>(ArtworkFetchUi.Running(done = 3, total = 10, matched = 1))
        compose.setContent {
            MimirTheme {
                ArtworkFetchOverlay(
                    state = state,
                    onCancel = {},
                    onClose = {},
                )
            }
        }
        compose.onNodeWithText("3 of 10 games · 1 game updated").assertExists()

        state = ArtworkFetchUi.Running(done = 7, total = 10, matched = 2)
        compose.waitForIdle()

        compose.onNodeWithText("7 of 10 games · 2 games updated").assertExists()
        compose.onNodeWithText("3 of 10 games · 1 game updated").assertDoesNotExist()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class GetArtOverlayLiveDispatchTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `steady Failed state still dispatches Back to the live callback`() {
        var dismissed = false
        compose.setContent {
            MimirTheme {
                GetArtOverlay(
                    state = GetArtUi.Failed,
                    provider = GetArtProvider.SCREENSCRAPER,
                    onConnect = {},
                    onAddSteamGridDbKey = {},
                    onDismiss = { dismissed = true },
                )
            }
        }
        compose.onNodeWithContentDescription("Back", useUnmergedTree = true).performClick()
        assertTrue("the live Failed page's Back must still reach onDismiss", dismissed)
    }

    @Test
    fun `steady NeedsAccount state still dispatches Connect to the live callback`() {
        var connected = false
        compose.setContent {
            MimirTheme {
                GetArtOverlay(
                    state = GetArtUi.NeedsAccount,
                    provider = GetArtProvider.SCREENSCRAPER,
                    onConnect = { connected = true },
                    onAddSteamGridDbKey = {},
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithContentDescription("Connect", useUnmergedTree = true).performClick()
        assertTrue(connected)
    }

    @Test
    fun `steady NeedsAccount state still dispatches Not now to the live callback`() {
        var dismissed = false
        compose.setContent {
            MimirTheme {
                GetArtOverlay(
                    state = GetArtUi.NeedsAccount,
                    provider = GetArtProvider.SCREENSCRAPER,
                    onConnect = {},
                    onAddSteamGridDbKey = {},
                    onDismiss = { dismissed = true },
                )
            }
        }
        compose.onNodeWithContentDescription("Not now", useUnmergedTree = true).performClick()
        assertTrue(dismissed)
    }
}
