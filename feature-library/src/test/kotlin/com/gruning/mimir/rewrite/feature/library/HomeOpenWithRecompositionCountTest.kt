package com.gruning.mimir.rewrite.feature.library

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.launch.OpenWithCandidate
import com.gruning.mimir.rewrite.core.launch.OpenWithOptions
import com.gruning.mimir.rewrite.core.model.EmulatorId
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirViewportPosture
import com.gruning.mimir.rewrite.core.ui.MimirViewportProfile
import com.gruning.mimir.rewrite.feature.library.ComposableInvocationTally.Companion.bodiesOf
import com.gruning.mimir.rewrite.feature.library.ComposableInvocationTally.Companion.oursInOrder
import com.gruning.mimir.rewrite.feature.library.ComposableInvocationTally.Companion.oursTotal
import com.gruning.mimir.rewrite.feature.library.ComposableInvocationTally.Companion.runsOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Home door of the same picker (owperf, 2026-10-15): the Home shelf under the launch card under the Open-with sheet, composed as `MimirAppHost` stacks them,
 * with the authority's scopes pushed as the Home card's coordinator pushes them (the target row remembered for the sheet's scope BEFORE the push). The card and
 * the sheet are the ones the Library door draws, so the counts here are the proof that the Home door got what the Library door got: a pad move inside the sheet and
 * the seating of its first row re-run two rows and not the card, and a chip move re-runs the buttons and not the card's body.
 *
 * What this cannot say is anything about the Home SHELF (the dock seat): its counts are printed in the failure messages for inspection, and are not asserted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w834dp-h520dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeOpenWithRecompositionCountTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val density369 = 369f / 160f
    private val widthDp = 1920f / density369
    private val heightDp = 1080f / density369

    private val authority = DefaultFocusAuthority()
    private val tally = ComposableInvocationTally()
    private var pickerOpen by mutableStateOf(false)

    private val row = HomeShelfRow(
        itemId = HomeShelfItemId.Emulated(GameId("home-game-1")),
        rank = 0L,
        addedAtMillis = 0L,
        title = "Crisis Core: Final Fantasy VII",
        supportingLabel = "psp",
    )

    private val cardState = HomeLaunchCardUiState(
        row = row,
        platformLabel = "PlayStation Portable",
        emulatorLabel = "PPSSPP",
        lastPlayedLabel = "Yesterday",
    )

    private fun candidate(id: String, label: String, auto: Boolean = false) = OpenWithCandidate(
        emulatorId = EmulatorId(id),
        label = label,
        packageName = "emu.$id",
        installed = true,
        isCurrentOverride = false,
        isAutoPick = auto,
        isLaunchPick = auto,
    )

    private val options = OpenWithOptions(
        hasOverride = false,
        candidates = listOf(candidate("ppsspp", "PPSSPP", auto = true), candidate("ppssppgold", "PPSSPP Gold"), candidate("ra-ppsspp", "RetroArch")),
    )

    private fun press(block: () -> Unit) {
        Snapshot.withMutableSnapshot(block)
        Snapshot.sendApplyNotifications()
    }

    private fun step(ms: Long = 16L) {
        compose.mainClock.advanceTimeBy(ms)
        Snapshot.sendApplyNotifications()
    }

    private fun mount() {
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = density369, fontScale = 1f),
                LocalMimirViewportProfile provides MimirViewportProfile(MimirViewportPosture.STANDARD, widthDp, heightDp),
            ) {
                MimirTheme {
                    Box(Modifier.requiredWidth(widthDp.dp).requiredHeight(heightDp.dp)) {
                        HomeShelfScreen(
                            rows = listOf(row),
                            authority = authority,
                            onLaunch = {},
                            onOpenLibrary = {},
                            onQuickAction = {},
                            surfaceLabel = "Home",
                        )
                        HomeLaunchCard(
                            state = cardState,
                            onPlay = {},
                            onAchievements = {},
                            onOpenWith = {},
                            onClose = {},
                            authority = authority,
                        )
                        if (pickerOpen) {
                            OpenWithOverlay(
                                authority = authority,
                                options = options,
                                onChooseAuto = {},
                                onChoose = {},
                                onDismiss = {},
                            )
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000L)
        compose.waitForIdle()
        enterHomeLaunchCard(authority, emulated = true)
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        authority.requestFocus(HomeLaunchCardOpenWithKey)
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        assertEquals("the pad starts on OPEN WITH", HomeLaunchCardOpenWithKey, authority.state.value.focused)
        compose.mainClock.autoAdvance = false
    }

    private fun framesAfter(count: Int, action: () -> Unit): List<Map<String, Int>> {
        val out = ArrayList<Map<String, Int>>()
        out += tally.measure {
            press(action)
            step(16L)
        }
        repeat(count - 1) { out += tally.measure { step(16L) } }
        return out
    }

    private fun List<Map<String, Int>>.runs(fragment: String, frames: IntRange = indices): Int =
        slice(frames).sumOf { it.runsOf(fragment) }

    private fun List<Map<String, Int>>.describe(label: String): String =
        mapIndexed { i, f -> "$label f$i: ${f.oursTotal()} of ours\n${f.oursInOrder()}" }.joinToString("\n")

    /** The coordinator's own push: the target remembered for the sheet's scope, then the scope, and the sheet mounts. */
    private fun openPicker() = framesAfter(8) {
        authority.rememberFocusForScope(OpenWithScope, OpenWithAutoKey)
        pickerOpen = true
        authority.pushScope(OpenWithScope)
    }

    @Test
    fun `after the press frame the Home sheet re-runs nothing but a row that landed, and the card's body is not re-run by it`() {
        mount()
        val frames = openPicker()
        val why = frames.describe("HOME OPEN")
        println("OWPERF home open: press frame ${frames[0].values.sum()} bodies (${frames[0].oursTotal()} of ours), next frame ${frames[1].values.sum()} (${frames[1].oursTotal()}); the Home shelf ran ${frames.runs("HomeShelf", 0..0)} bodies in the press frame, ${frames.runs("HomeShelf", 1..7)} after\n${frames[0].oursInOrder()}\nNEXT FRAME\n${frames[1].oursInOrder()}")

        assertEquals("the target row is seated", OpenWithAutoKey, authority.state.value.focused)
        listOf("HomeLaunchCard", "MimirSelectOverlay", "MimirLiquidGlassPanel", "OpenWithOverlay")
            .forEach { name -> assertEquals("$name ran after the press frame\n$why", 0, frames.runs(name, 1..7)) }
        listOf("HomeLaunchCardHeader", "HomeLaunchCardMetadata", "HomeLaunchCardWide", "HomeLaunchCardCompact")
            .forEach { name -> assertEquals("$name ran in the press frame\n$why", 0, frames.runs(name, 0..0)) }
    }

    @Test
    fun `moving the pad inside the Home sheet re-runs two rows and the counter, and nothing of the card`() {
        mount()
        openPicker()
        step(400L)
        val before = authority.state.value.focused

        val frames = framesAfter(3) { authority.moveFocus(ControllerAction.NAV_DOWN) }
        val why = frames.describe("HOME MOVE")

        assertNotEquals("the pad moved to the next row\n$why", before, authority.state.value.focused)
        listOf("HomeLaunchCard", "MimirSelectOverlay", "MimirLiquidGlassPanel", "OpenWithOverlay")
            .forEach { name -> assertEquals("$name ran for a pad move\n$why", 0, frames.runs(name)) }
        assertTrue("the ring leaves one row and arrives on another\n$why", frames.runs("SelectRowSeat") in 1..2)
    }

    @Test
    fun `moving between the Home card's chips re-runs the buttons and not the card's body above them`() {
        mount()
        val frames = framesAfter(3) { authority.moveFocus(ControllerAction.NAV_RIGHT) }
        val why = frames.describe("HOME CHIP")

        assertEquals("the pad moved to ACHIEVEMENTS\n$why", HomeLaunchCardAchievementsKey, authority.state.value.focused)
        listOf("HomeLaunchCardHeader", "HomeLaunchCardMetadata", "HomeLaunchCardWide", "HomeLaunchCardCompact")
            .forEach { name -> assertEquals("$name ran for a chip move\n$why", 0, frames.runs(name)) }
        assertTrue("the buttons' rings are what moved\n$why", frames.runs("HomeLaunchActionButton") in 1..3)
    }

    // The host half. `MimirAppHost` builds the card's state in a body that runs for every change of any store behind it, and the picker opening flips the flag the
    // card's close lambda reads. This is that body in miniature: [hostPasses] bumps a counter the body reads (a pass that changes nothing the card draws) and
    // [backOn] is the flag. [kept] is the host's own code (`rememberEqualInstance`, `rememberUpdatedState`); not [kept] is what it did before.

    private var hostPasses by mutableStateOf(0)
    private var backOn by mutableStateOf(true)

    @Composable
    private fun HostBody(kept: Boolean) {
        val pass = hostPasses
        val back = backOn
        val backNow by rememberUpdatedState(back)
        // A List field makes the state an unstable type, which skipping compares by identity: a new instance every pass, equal or not.
        val built = HomeLaunchCardUiState(
            row = row,
            platformLabel = "PlayStation Portable",
            emulatorLabel = "PPSSPP",
            lastPlayedLabel = "Yesterday",
            extraFacts = listOf("REGION" to "USA", "SIZE" to "1.2 GB"),
        )
        val state = if (kept) rememberEqualInstance(built) else built
        HomeLaunchCard(
            state = state,
            onPlay = {},
            onAchievements = {},
            onOpenWith = {},
            onClose = if (kept) ({ if (backNow) Unit }) else ({ if (back && pass >= 0) Unit }),
            authority = authority,
        )
    }

    private fun mountHostBody(kept: Boolean) {
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = density369, fontScale = 1f),
                LocalMimirViewportProfile provides MimirViewportProfile(MimirViewportPosture.STANDARD, widthDp, heightDp),
            ) {
                MimirTheme {
                    Box(Modifier.requiredWidth(widthDp.dp).requiredHeight(heightDp.dp)) { HostBody(kept) }
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000L)
        compose.waitForIdle()
        enterHomeLaunchCard(authority, emulated = true)
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    private val cardBody = "com.gruning.mimir.rewrite.feature.library.HomeLaunchCard"

    @Test
    fun `a host pass that rebuilds an equal card state, and the back flag flipping, do not re-run the card when the host keeps the instance`() {
        mountHostBody(kept = true)
        val frames = ArrayList<Map<String, Int>>()
        frames += tally.measure { press { hostPasses += 1 }; step(16L) }
        frames += tally.measure { press { backOn = false }; step(16L) }
        frames += tally.measure { press { hostPasses += 1 }; step(16L) }
        val why = frames.describe("HOST PASS")

        frames.forEachIndexed { i, f -> assertEquals("the card ran in pass $i\n$why", 0, f.bodiesOf(cardBody)) }
        assertEquals("the card's pieces ran\n$why", 0, frames.runs("HomeLaunchCard"))
    }

    @Test
    fun `without that, the same passes re-run the card each time (what the host did before)`() {
        mountHostBody(kept = false)
        val frames = ArrayList<Map<String, Int>>()
        frames += tally.measure { press { hostPasses += 1 }; step(16L) }
        frames += tally.measure { press { backOn = false }; step(16L) }
        val why = frames.describe("HOST PASS CONTROL")

        frames.forEachIndexed { i, f -> assertTrue("the card did not run in pass $i, so this control proves nothing\n$why", f.bodiesOf(cardBody) >= 1) }
    }
}
