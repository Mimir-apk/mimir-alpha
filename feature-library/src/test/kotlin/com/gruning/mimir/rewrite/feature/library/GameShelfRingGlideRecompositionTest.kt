package com.gruning.mimir.rewrite.feature.library

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.model.library.ShelfFace
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.MimirShelfBackground
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.mimirViewportProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * RC-47 / RC-15 (audit 2026-10-14): the ring's glide between seats must not recompose the screen around the shelf.
 *
 * [rememberGameShelfState] used to hand its caller the eased seat as a `Float`, which is the `animateFloatAsState` value read in
 * the CALLER's composition. Home calls it from [HomeShelfScreen], so every focus move recomposed the whole Home screen on each
 * frame of the [HomeShelfOutgoingFadeMs] glide, on top of the layout work of the glide itself. The seat now travels as a State
 * and is read in the ring's `offset {}` and the backdrop pool's `graphicsLayer {}`.
 *
 * The scene is [GameShelfBehaviorTest]'s: the state is built in the scope a screen would, and a counter in that same scope
 * counts its recompositions. It is a harness that FAILS on the old code (the counter rises on every glide frame there).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w900dp-h520dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GameShelfRingGlideRecompositionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val authority = DefaultFocusAuthority()
    private val scope = FocusScope("glide/game-shelf")
    private val memory = GameShelfWindowMemory()
    private val widthDp = 897f
    private val heightDp = 505f
    private var callerRecompositions = 0

    private fun game(index: Int) = GameShelfItem.Game(
        focusKey = FocusKey("glide/game-$index"),
        title = "Game $index",
        face = ShelfFace.None,
        platformIdValue = "gba",
    )

    private val items = List(5, ::game)

    private fun mount() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(2f, 1f),
                LocalMimirViewportProfile provides mimirViewportProfile(widthDp, heightDp),
            ) {
                MimirTheme { Box(Modifier.requiredSize(widthDp.dp, heightDp.dp)) { Scene() } }
            }
        }
        settle()
        authority.requestFocus(items.first().focusKey)
        settle()
    }

    @Composable
    private fun Scene() {
        DisposableEffect(authority) {
            authority.pushScope(scope)
            onDispose { authority.popScope() }
        }
        RegisterVirtualFocusRow(authority = authority, scope = scope, keys = items.map { it.focusKey })
        val live by authority.state.collectAsState()
        val selectedKey = if (live.scope == scope) live.focused else authority.rememberedFocusIn(scope)
        val geometry = remember { solveHomeShelfGeometry(widthDp, heightDp) }
        MimirShelfBackground(modifier = Modifier.fillMaxSize(), showAtmosphericClouds = false) {
            // The scope a screen builds the shelf state in. Anything the state reads in composition recomposes THIS scope.
            SideEffect { callerRecompositions++ }
            val state = rememberGameShelfState(
                items = items,
                selectedKey = selectedKey,
                geometry = geometry,
                windowMemory = memory,
                windowMemoryKey = "glide",
                surfaceEpoch = 0,
                reducedMotion = false,
            )
            GameShelf(
                scope = scope,
                authority = authority,
                focusState = live,
                onRequestFocus = { key -> authority.requestFocus(key) },
                onActivate = {},
                emptyContent = {},
                state = state,
            )
        }
    }

    private fun settle(ms: Long = 1_500L) {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    @Test
    fun `the ring's glide to the next seat recomposes the caller on none of its frames`() {
        mount()
        val before = callerRecompositions

        // One step right: the selected SEAT changes (the window has not scrolled yet), so the ring glides over HomeShelfOutgoingFadeMs.
        authority.requestFocus(items[1].focusKey)
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        val atCommit = callerRecompositions
        assertTrue("the focus change itself recomposes the caller (the selection moved)", atCommit > before)

        // The glide: 16 ms frames for well past the 210 ms tween. Nothing in it may recompose the caller.
        repeat(20) {
            compose.mainClock.advanceTimeBy(16L)
            compose.waitForIdle()
        }
        assertEquals(
            "the eased seat is read in the ring's offset lambda and the backdrop's layer lambda, never in the caller's composition: " +
                "each glide frame used to recompose the whole screen that builds the shelf state",
            atCommit,
            callerRecompositions,
        )
    }
}

