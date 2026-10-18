package com.gruning.mimir.rewrite

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.feature.library.HomeShelfTome
import com.gruning.mimir.rewrite.feature.library.HomeTomeFolio
import com.gruning.mimir.rewrite.feature.library.HomeTomeFolioMode
import com.gruning.mimir.rewrite.feature.library.HomeTomeFolioScope
import com.gruning.mimir.rewrite.feature.library.HomeTomeFolioUiState
import com.gruning.mimir.rewrite.feature.library.HomeTomeMember
import com.gruning.mimir.rewrite.feature.library.TomeId
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * **T-1, mechanism A, the Tome folio.** The seat a player taps inside a Tome used to spend the first tap
 * on focus and only open on the second — a reported two-tap selection failure which, stacked on the launch card's own two-tap Play, meant a game inside a Tome cost
 * FOUR taps to start.
 *
 * These tests drive the real `HomeTomeFolio` composition under Robolectric and inject real touch. The
 * tapped seat is deliberately NOT the focused one, so a pass cannot come from "the tile happened to be
 * settled already" — which is exactly what the old code required and what a lazier test would smuggle in.
 *
 * ARRANGE's two-step is pinned here too, as a *kept* behaviour rather than an oversight: Arrange's touch
 * grammar is press-and-hold carry, and a lift/drop is a reducer transaction. See
 * `homeTomeFolioMemberTapAction`'s KDoc for the full reasoning and `HomeTomeFolioMemberTapActionTest`
 * for the branch itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class HomeTomeFolioTouchGrammarTest {

    @get:Rule
    val compose = createComposeRule()

    private val first = HomeTomeMember(
        itemId = HomeShelfItemId.Emulated(GameId("game-1")),
        title = "Chrono Trigger",
    )
    private val second = HomeTomeMember(
        itemId = HomeShelfItemId.Emulated(GameId("game-2")),
        title = "Secret of Mana",
    )

    private class Recorder {
        val opened = mutableListOf<String>()
        val arranged = mutableListOf<ControllerAction>()
        val focused = mutableListOf<Int>()

        /** What the FocusAuthority named at the instant `onOpenMember` fired. */
        var focusedWhenOpened: FocusKey? = null
    }

    private fun host(
        authority: DefaultFocusAuthority,
        recorder: Recorder,
        mode: HomeTomeFolioMode,
    ) {
        val density = Density(density = 1f, fontScale = 1f)
        compose.setContent {
            // The seat's own focus index is live state, exactly as the coordinator drives it: a tap must
            // be observable as "focus moved AND the action ran", not one or the other.
            var focusedIndex by remember { mutableStateOf(0) }
            CompositionLocalProvider(LocalDensity provides density) {
                MimirTheme {
                    Box(modifier = Modifier.requiredWidth(832.5.dp).requiredHeight(468.3.dp)) {
                        HomeTomeFolio(
                            state = HomeTomeFolioUiState(
                                tome = HomeShelfTome(
                                    tomeId = TomeId.Custom(UUID.fromString("00000000-0000-0000-0000-000000000001")),
                                    title = "Favourites",
                                    previews = emptyList(),
                                    memberCount = 2,
                                ),
                                members = listOf(first, second),
                                focusedIndex = focusedIndex,
                                mode = mode,
                                focusSettled = true,
                            ),
                            authority = authority,
                            onFocusMember = { index ->
                                recorder.focused += index
                                focusedIndex = index
                                authority.requestFocus(listOf(first, second)[index].focusKey)
                            },
                            onOpenMember = {
                                recorder.focusedWhenOpened = authority.state.value.focused
                                recorder.opened += it.title
                            },
                            onArrangeAction = { recorder.arranged += it },
                            onFirstInteraction = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun armed(): DefaultFocusAuthority = DefaultFocusAuthority().apply {
        pushScope(HomeTomeFolioScope)
    }

    @Test
    fun `one tap on an unfocused Tome seat opens its launch detail`() {
        val authority = armed()
        val recorder = Recorder()
        host(authority, recorder, HomeTomeFolioMode.BROWSE)

        // Seat 0 owns focus. Tap seat 1 -- an UNFOCUSED, unsettled seat, the case the old code answered
        // by moving focus and doing nothing else.
        compose.onNodeWithContentDescription(second.title, useUnmergedTree = true).performClick()
        compose.waitForIdle()

        assertEquals(
            "one tap on an unfocused Tome seat must open that game's launch detail",
            listOf(second.title),
            recorder.opened,
        )
        assertEquals(
            "focus must follow the tap -- the ring is the receipt of where the action landed",
            listOf(1),
            recorder.focused,
        )
    }

    /**
     * The two calls the seat makes are ORDERED, and the order is load-bearing rather than tidy:
     * `HomeLaunchCardCoordinator.open` refuses outright unless the FocusAuthority already names the
     * origin key it was handed (`homeLaunchCardOriginIsCurrent`). Publish focus after opening — or not
     * at all — and the tap does nothing whatsoever, which is a worse defect than the two-tap it
     * replaced because it is silent.
     */
    @Test
    fun `the seat publishes focus before it asks for the launch detail`() {
        val authority = armed()
        val recorder = Recorder()
        host(authority, recorder, HomeTomeFolioMode.BROWSE)

        compose.onNodeWithContentDescription(second.title, useUnmergedTree = true).performClick()
        compose.waitForIdle()

        assertEquals(
            "at the instant the launch detail is requested, the authority must already name the " +
                "tapped seat -- the app's open() guard rejects any other origin",
            second.focusKey,
            recorder.focusedWhenOpened,
        )
    }

    @Test
    fun `the tap opens the seat it landed on, never the one that held focus`() {
        val authority = armed()
        val recorder = Recorder()
        host(authority, recorder, HomeTomeFolioMode.BROWSE)

        compose.onNodeWithContentDescription(second.title, useUnmergedTree = true).performClick()
        compose.waitForIdle()

        assertEquals(
            "the tapped seat, not the previously focused seat, is what opens",
            listOf(second.title),
            recorder.opened,
        )
    }

    @Test
    fun `ARRANGE keeps its exact-target step and a tap on an unsettled seat only focuses`() {
        val authority = armed()
        val recorder = Recorder()
        host(authority, recorder, HomeTomeFolioMode.ARRANGE)

        compose.onNodeWithContentDescription(second.title, useUnmergedTree = true).performClick()
        compose.waitForIdle()

        assertEquals(
            "ARRANGE is direct manipulation (press-and-hold carry) and a lift/drop is a reducer " +
                "transaction: a tap on an unsettled seat must NOT fire CONFIRM. Declared asymmetry, " +
                "see homeTomeFolioMemberTapAction.",
            emptyList<ControllerAction>(),
            recorder.arranged,
        )
        assertEquals("and it must never open a launch detail in ARRANGE", emptyList<String>(), recorder.opened)
        assertEquals("but it still moves focus", listOf(1), recorder.focused)
    }

    @Test
    fun `ARRANGE still lifts the settled seat on a tap`() {
        val authority = armed()
        val recorder = Recorder()
        host(authority, recorder, HomeTomeFolioMode.ARRANGE)

        // Seat 0 is the settled painted target from the start, so this single tap is the second half of
        // Arrange's own two-step -- proving the kept branch is reachable, not dead.
        compose.onNodeWithContentDescription(first.title, useUnmergedTree = true).performClick()
        compose.waitForIdle()

        assertEquals(
            "a tap on the settled Arrange target still hands CONFIRM to the reducer",
            listOf(ControllerAction.CONFIRM),
            recorder.arranged,
        )
    }
}
