package com.gruning.mimir.rewrite

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.down
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCard
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardAchievementsKey
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardCoverArtTestTag
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardOpenWithKey
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardPlayKey
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardScope
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardUiState
import com.gruning.mimir.rewrite.feature.library.HomeShelfRow
import com.gruning.mimir.rewrite.feature.library.homeLaunchCardActionTestTag
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * **T-1, mechanism A, the launch card.**
 *
 * The card used to spend a tap on focus before it would act: every action carried
 * `if (focused) onActivate() else authority.requestFocus(key)`, so starting a game cost three taps from
 * the Home shelf and four from inside a Tome. These tests drive the REAL `HomeLaunchCard` composition
 * under Robolectric and inject REAL touch (`performClick` is `performTouchInput { click() }` in this
 * Compose version — a down/up pair at the node centre, not a semantics shortcut), then count callbacks.
 *
 * **Every tap here lands on a target that is NOT already focused.** That is the whole point and it is
 * easy to get wrong: `enterHomeLaunchCard` parks focus on Play, so a test that taps Play first would
 * pass identically against the old two-tap code and prove nothing. Each test below therefore focuses one
 * action and taps a DIFFERENT one.
 *
 * This is a JVM proof of the tap COUNT and of which callback fires. It is not a claim about how the card
 * feels under a thumb; tap-count end to end on the reported path stays device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class HomeLaunchCardTouchGrammarTest {

    @get:Rule
    val compose = createComposeRule()

    private val row = HomeShelfRow(
        itemId = HomeShelfItemId.Emulated(GameId("game-1")),
        rank = 0L,
        addedAtMillis = 0L,
        title = "Chrono Trigger",
        supportingLabel = "snes",
    )

    private class Recorder {
        var play = 0
        var achievements = 0
        var openWith = 0
    }

    /**
     * Returns a handle that forces a REAL recomposition of the card, including the cover-art subtree:
     * changing `statusLine` changes the `HomeLaunchCardUiState` instance, so no child on that path is
     * skippable. Used to interleave a recomposition with a live gesture.
     */
    private fun host(
        authority: DefaultFocusAuthority,
        recorder: Recorder,
    ): () -> Unit {
        val density = Density(density = 1f, fontScale = 1f)
        val statusLine = mutableStateOf<String?>(null)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides density) {
                MimirTheme {
                    // The Odin reference viewport, the same host geometry
                    // HomeTileMenuPanelWidthCapTest uses. requiredWidth/Height (enforceIncoming = false)
                    // because Robolectric's default root is far narrower and plain width()/height()
                    // would silently coerce down to it.
                    Box(modifier = Modifier.requiredWidth(832.5.dp).requiredHeight(468.3.dp)) {
                        HomeLaunchCard(
                            state = HomeLaunchCardUiState(
                                row = row,
                                platformLabel = "Super Nintendo",
                                statusLine = statusLine.value,
                            ),
                            onPlay = { recorder.play++ },
                            onAchievements = { recorder.achievements++ },
                            onOpenWith = { recorder.openWith++ },
                            onClose = {},
                            authority = authority,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        var tick = 0
        return { statusLine.value = "status ${tick++}" }
    }

    /** Mirrors the app host: the card's own scope is pushed before its keys are focusable. */
    private fun armed(): DefaultFocusAuthority = DefaultFocusAuthority().apply {
        pushScope(HomeLaunchCardScope)
    }

    private fun DefaultFocusAuthority.focusedKey(): FocusKey? = state.value.focused

    @Test
    fun `one tap on an unfocused action button activates it`() {
        val authority = armed()
        val recorder = Recorder()
        host(authority, recorder)

        // Park focus on Play, then tap a DIFFERENT button. Under the old idiom this first tap would
        // only move focus and Achievements would never have fired.
        authority.requestFocus(HomeLaunchCardPlayKey)
        compose.waitForIdle()
        assertEquals(HomeLaunchCardPlayKey, authority.focusedKey())

        compose.onNodeWithTag(
            homeLaunchCardActionTestTag(HomeLaunchCardAchievementsKey),
            useUnmergedTree = true,
        ).performClick()
        compose.waitForIdle()

        assertEquals(
            "one tap on an unfocused action must ACTIVATE it, not spend the tap on focus",
            1,
            recorder.achievements,
        )
        assertEquals("the tap must not activate a neighbour", 0, recorder.play)
    }

    @Test
    fun `activating by tap publishes focus to the tapped element first`() {
        val authority = armed()
        val recorder = Recorder()
        host(authority, recorder)

        authority.requestFocus(HomeLaunchCardAchievementsKey)
        compose.waitForIdle()

        compose.onNodeWithTag(
            homeLaunchCardActionTestTag(HomeLaunchCardPlayKey),
            useUnmergedTree = true,
        ).performClick()
        compose.waitForIdle()

        assertEquals("Play must have fired on the first tap", 1, recorder.play)
        // Load-bearing, not cosmetic: Home binds onPlay to dispatch(CONFIRM), which resolves its target
        // from the CURRENTLY FOCUSED key. If focus were published after (or not at all), a tap on Play
        // while Achievements held focus would open Achievements.
        assertEquals(
            "focus must land on the tapped element, because the host's CONFIRM dispatch reads it",
            HomeLaunchCardPlayKey,
            authority.focusedKey(),
        )
    }

    @Test
    fun `one tap on the cover art launches and lands focus on Play`() {
        val authority = armed()
        val recorder = Recorder()
        host(authority, recorder)

        // Focus deliberately parked somewhere else, so a cover-art tap cannot be confused with
        // "CONFIRM happened to land on the already-focused Play".
        authority.requestFocus(HomeLaunchCardOpenWithKey)
        compose.waitForIdle()

        compose.onNodeWithTag(HomeLaunchCardCoverArtTestTag, useUnmergedTree = true).performClick()
        compose.waitForIdle()

        assertEquals(
            "a tap on the cover art launches the game",
            1,
            recorder.play,
        )
        assertEquals(
            "the cover art performs PLAY's action, so it must leave PLAY focused -- the ring is the " +
                "receipt of where the action landed",
            HomeLaunchCardPlayKey,
            authority.focusedKey(),
        )
        assertEquals("the cover art must not reach Open with", 0, recorder.openWith)
    }

    /**
     * A tap is not an instant: the card recomposes under the player's thumb (the live status line
     * updates, focus ink animates, folio seats travel). This forces the interleaving and asserts the
     * tap still lands.
     *
     * **What this test does NOT prove, stated because it was checked.** It does not discriminate the
     * `pointerInput(lambda)` keying it was originally written to catch: with the gesture node keyed
     * on `onTapPlay` instead of `Unit`, this test still PASSES. Compose's strong skipping (default-on
     * from compiler 2.0.20; this tree is Kotlin 2.0.21) memoizes those lambda literals despite their
     * unstable captures, so the key does not actually change and the detector is never restarted.
     * The `Unit` keying in production is hardening against that memoization going away, and it is
     * pinned as a SHAPE by `HomeTouchGrammarTest`, not as behaviour here.
     */
    @Test
    fun `a tap survives a recomposition between finger-down and finger-up`() {
        val authority = armed()
        val recorder = Recorder()
        val recompose = host(authority, recorder)

        authority.requestFocus(HomeLaunchCardOpenWithKey)
        compose.waitForIdle()

        val art = compose.onNodeWithTag(HomeLaunchCardCoverArtTestTag, useUnmergedTree = true)
        art.performTouchInput { down(center) }

        // A real state change on the card's own path, so the cover-art subtree is genuinely
        // recomposed -- the live-status line updating under the player's thumb is exactly this.
        recompose()
        compose.waitForIdle()

        art.performTouchInput { up() }
        compose.waitForIdle()

        assertEquals(
            "the cover art's gesture node must survive a recomposition mid-gesture: a tap is not an " +
                "instant, and the card recomposes under the thumb",
            1,
            recorder.play,
        )
    }

    @Test
    fun `a single tap fires exactly once and is not deferred by a double-tap window`() {
        val authority = armed()
        val recorder = Recorder()
        host(authority, recorder)

        authority.requestFocus(HomeLaunchCardAchievementsKey)
        compose.waitForIdle()

        compose.onNodeWithTag(HomeLaunchCardCoverArtTestTag, useUnmergedTree = true).performClick()
        // No advanceTimeBy: with `onTap` only, `detectTapGestures` invokes on the up event. Registering
        // `onDoubleTap` would push this behind the platform double-tap timeout, which is the latency
        // half of the reported complaint.
        compose.waitForIdle()

        assertEquals("exactly one launch per tap, on the up event", 1, recorder.play)
    }
}
