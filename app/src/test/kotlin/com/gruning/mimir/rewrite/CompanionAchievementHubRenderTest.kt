package com.gruning.mimir.rewrite

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.model.RaKeyState
import com.gruning.mimir.rewrite.core.ui.MimirAchievementCardState
import com.gruning.mimir.rewrite.core.ui.MimirAchievementCopy
import com.gruning.mimir.rewrite.core.ui.MimirMedalState
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Thor Bottom revamp V10: the Bottom's achievements hub on Main's `MimirAchievementCard`, at the Bottom's 537.7 x 468.3 dp at 369 dpi and font scales 1.0 and 1.3, on the
 * REAL [CompanionActivityAchievementHub]. A 3-earned-of-12 snapshot reads as the card's Loaded page with earned rows only, no points line and rows uncapped; the shared
 * 48 dp Back pill is there and works; and with no snapshot the card is its honest Unknown state, with the reason by cause and no setup action. Every measurement is printed as
 * `V10-HUB`. Pixel values are the JVM's; the pixels on a handheld are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionAchievementHubRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val density369 = 369f / 160f
    private val hostWidthDp = 1240f / density369
    private val hostHeightDp = 1080f / density369

    private val longTitle = "Sonic the Hedgehog 2 Special Limited Collectors Edition of the Chaos Emerald Hunters Revisited and Remastered"
    // Long enough to run past six lines at the card's text width at either font scale: a cap of three (or any cap a refactor might add) would show.
    private val longDescription = "Defeat the final boss without taking a single hit, without losing a ring, and without using any continue, in any act of the final zone, " +
        "on any difficulty, with every emerald collected beforehand, never pausing the game, never using a save state, and finishing the whole run in a single sitting " +
        "of no more than forty minutes, which is the requirement a cut description would have deleted"

    private fun snapshot(title: String? = longTitle) = CompanionActivityAchievementSnapshot(
        gameId = "g1",
        account = "me",
        unlocked = 3,
        total = 12,
        gameTitle = title,
        earned = listOf(
            CompanionActivityEarnedAchievement(1, "Green Hill Hero", "Finish the first zone.", 5, hardcore = false),
            CompanionActivityEarnedAchievement(2, "Untouchable", longDescription, 25, hardcore = true),
            CompanionActivityEarnedAchievement(3, "Ring Collector", "Hold 100 rings at once.", 10, hardcore = false),
        ),
    )

    private var backs = 0

    private fun mount(fontScale: Float, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density369, fontScale)) {
                MimirTheme { Box(Modifier.requiredSize(hostWidthDp.dp, hostHeightDp.dp)) { content() } }
            }
        }
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
    }

    private fun hub(snapshots: List<CompanionActivityAchievementSnapshot>, unknown: MimirAchievementCardState = defaultUnknown): @Composable () -> Unit = {
        CompanionActivityAchievementHub(snapshots = snapshots, unknownState = unknown, navigationEnabled = true, onBack = { backs += 1 })
    }

    private val defaultUnknown: MimirAchievementCardState = MimirAchievementCardState.Unknown(MimirAchievementCopy.NoAnswerYet)

    private fun texts(): List<String> = compose.onAllNodes(androidx.compose.ui.test.isRoot(), useUnmergedTree = true).fetchSemanticsNodes().let {
        val out = ArrayList<String>()
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
            node.children.forEach(::walk)
        }
        it.forEach(::walk)
        out
    }

    private fun advance(ms: Long) {
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(ms)
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    private fun layoutOf(text: String): TextLayoutResult {
        val node = compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().first()
        val results = ArrayList<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.first()
    }

    private fun lines(text: String): Int = layoutOf(text).lineCount

    @Test
    fun `with no snapshot the card is its honest Unknown state, never an empty Loaded`() {
        mount(1.0f, hub(emptyList()))
        val all = texts()
        println("V10-HUB unknown texts=$all")
        assertTrue("the reason in the card's own copy: $all", MimirAchievementCopy.NoAnswerYet in all)
        assertTrue("the card's eyebrow still stands", MimirAchievementCopy.CardEyebrow in all)
        assertFalse("never a Loaded page: no points line and no 0 / 0: $all", all.any { it.contains("points", ignoreCase = true) || it.contains("0/0") })
        assertEquals(1, compose.onAllNodesWithTag(AchievementHubCardTag, useUnmergedTree = true).fetchSemanticsNodes().size)
        val pill = compose.onNodeWithTag(NowPlayingBackPillTag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("the Back pill is at least 48 dp each way", pill.width / density369 >= 47.9f && pill.height / density369 >= 47.9f)
    }

    /**
     * Thor drive 2026-10-08 (D42): the Activity > Achievements page of a connected account whose ledger is empty read "ACHIEVEMENTS / UNAVAILABLE" over "Mimir doesn't
     * have an answer for this game yet." (game copy on a page with no game). It now says what the Activity card above it says, with no status word.
     */
    @Test
    fun `a connected account with nothing checked reads as the Activity card does, not as a game with no answer`() {
        val state = companionActivityAchievementUnknownState(connectedServicesOn = true, account = "me", validation = RaKeyState.ACCEPTED)
        mount(1.0f, hub(emptyList(), state))
        val all = texts()
        println("V10-HUB empty-ledger texts=$all")
        assertTrue("the Activity card's headline: $all", MimirAchievementCopy.NothingCheckedYet in all)
        assertTrue("and its hint: $all", MimirAchievementCopy.CheckGamesToLoad in all)
        assertEquals("the words are the Activity card's own", "No achievements checked yet", MimirAchievementCopy.NothingCheckedYet)
        assertEquals("Check games in the library to load achievements", MimirAchievementCopy.CheckGamesToLoad)
        assertTrue("the eyebrow still stands: $all", MimirAchievementCopy.CardEyebrow in all)
        assertFalse("no UNAVAILABLE status word: $all", MimirAchievementCopy.StatusUnavailable in all)
        assertFalse("no status word at all: $all", listOf(MimirAchievementCopy.StatusNeedsSetup, MimirAchievementCopy.StatusChecking, MimirAchievementCopy.StatusNoSet).any { it in all })
        assertFalse("not the per-game line: $all", MimirAchievementCopy.NoAnswerYet in all)
        assertFalse("nothing names a game on the Activity-level page: $all", all.any { it.contains("this game") })
        assertFalse("and no setup action: $all", MimirAchievementCopy.OpenProfileSettings in all)
    }

    @Test
    fun `the reason follows the cause, in the card's own words, and a setup reason offers no action on the Bottom`() {
        assertEquals(
            MimirAchievementCardState.Unknown(MimirAchievementCopy.OptedOut),
            companionActivityAchievementUnknownState(connectedServicesOn = false, account = "me", validation = RaKeyState.ACCEPTED),
        )
        assertEquals(
            MimirAchievementCardState.Unknown(MimirAchievementCopy.NoCredentials),
            companionActivityAchievementUnknownState(connectedServicesOn = true, account = null, validation = RaKeyState.NEVER_CHECKED),
        )
        assertEquals(
            MimirAchievementCardState.Unknown(MimirAchievementCopy.CredentialsRejected),
            companionActivityAchievementUnknownState(connectedServicesOn = true, account = "me", validation = RaKeyState.REJECTED_THIS_SESSION),
        )
        // Connected, key accepted, nothing checked: the Activity card's words, not the game card's "no answer for this game yet" (D42).
        assertEquals(
            MimirAchievementCardState.Unknown(MimirAchievementCopy.NothingCheckedYet),
            companionActivityAchievementUnknownState(connectedServicesOn = true, account = "me", validation = RaKeyState.ACCEPTED),
        )
        // A setup reason on the hub: the card says NEEDS SETUP but there is no Bottom route to Settings, so it draws no action pill.
        mount(1.0f, hub(emptyList(), companionActivityAchievementUnknownState(connectedServicesOn = true, account = null, validation = RaKeyState.NEVER_CHECKED)))
        val all = texts()
        assertTrue("the card's own words for it: $all", MimirAchievementCopy.NoCredentials in all)
        assertFalse("and no action to a Settings the Bottom does not have: $all", MimirAchievementCopy.OpenProfileSettings in all)
    }

    /**
     * Alpha cert 2026-10-08 (C2, e5-07): a never-connected install has connected services off AND no account. The hub checked "services off" first and told it to
     * "Reconnect RetroAchievements ... to check this game", something it never had, on a page that has no game. The game card's own preflight asks credentials first
     * ([retroAchievementsPreflightState]); the hub now answers in that order, so the same install is told to connect.
     */
    @Test
    fun `a never-connected install is told to connect, not to reconnect, and the hub names no game`() {
        val fresh = companionActivityAchievementUnknownState(connectedServicesOn = false, account = null, validation = RaKeyState.NEVER_CHECKED)
        assertEquals(MimirAchievementCardState.Unknown(MimirAchievementCopy.NoCredentials), fresh)
        // Only a saved account that switched services off is told to "Reconnect".
        assertEquals(
            MimirAchievementCardState.Unknown(MimirAchievementCopy.OptedOut),
            companionActivityAchievementUnknownState(connectedServicesOn = false, account = "me", validation = RaKeyState.NEVER_CHECKED),
        )
        // Every cause, in the game card's order: the hub and the preflight give one card for the same inputs. The one place they part is the preflight passing
        // (nothing wrong): the game card then asks about its game, and the hub, which has no game, says nothing has been checked yet.
        for (on in listOf(true, false)) for (account in listOf(null, "", "  ", "me")) for (key in RaKeyState.values()) {
            val preflight = retroAchievementsPreflightState(
                supported = true,
                keyState = key,
                hasUsableCredentialsForSession = !account.isNullOrBlank(),
                connectedServicesOptedIn = on,
            )
            assertEquals(
                "services on=$on account=$account key=$key",
                if (preflight == null) MimirAchievementCardState.Unknown(MimirAchievementCopy.NothingCheckedYet) else mimirAchievementCardState(preflight),
                companionActivityAchievementUnknownState(connectedServicesOn = on, account = account, validation = key),
            )
        }
        mount(1.0f, hub(emptyList(), fresh))
        val all = texts()
        println("V10-HUB never-connected texts=$all")
        assertTrue("the connect words: $all", MimirAchievementCopy.NoCredentials in all)
        assertFalse("not the reconnect words: $all", MimirAchievementCopy.OptedOut in all)
        assertFalse("nothing says Reconnect or names a game on the Activity-level page: $all", all.any { it.contains("Reconnect") || it.contains("this game") })
    }

    @Test
    fun `the same card with its points known, as Main builds it, still prints its points line`() {
        mount(1.0f) {
            com.gruning.mimir.rewrite.core.ui.MimirAchievementCard(
                state = MimirAchievementCardState.Loaded(
                    gameTitle = "Ace Combat Advance", unlocked = 3, total = 12, earnedPoints = 40, totalPoints = 235,
                    rows = snapshot().toAchievementCardState().rows,
                ),
                modifier = Modifier.requiredSize(480.dp, 360.dp),
            )
        }
        val all = texts()
        assertTrue("Main's head band keeps its points line: $all", "40 of 235 points" in all)
    }

    // ── the page rune follows the live list ────────────────────────────────────────────────────────────────────────────

    private fun gameSnap(n: Int) = CompanionActivityAchievementSnapshot(
        gameId = "g$n", account = "me", unlocked = 1, total = 5, gameTitle = "Game $n",
        earned = listOf(CompanionActivityEarnedAchievement(n, "Earned $n", "Done.", 5, hardcore = false)),
    )

    private fun runes(): String? =
        compose.onAllNodesWithTag(AchievementHubRunesTag, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
            ?.config?.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()

    private fun turnTo(index: Int) {
        compose.onNodeWithTag(AchievementHubListTag, useUnmergedTree = true).performScrollToIndex(index)
        advance(500L)
    }

    @Test
    fun `a second game that arrives while the hub is open gets its own rune, and a shrink clamps the page`() {
        var live by mutableStateOf(listOf(gameSnap(1)))
        mount(1.0f) { CompanionActivityAchievementHub(snapshots = live, unknownState = defaultUnknown, navigationEnabled = true, onBack = {}) }
        assertEquals("one game has no runes", null, runes())
        live = listOf(gameSnap(1), gameSnap(2))
        advance(100L)
        assertEquals("two games: the first is page one", "Game 1 of 2", runes())
        turnTo(1)
        assertEquals("turned to the game that arrived: the rune is on page two", "Game 2 of 2", runes())
        live = listOf(gameSnap(1), gameSnap(2), gameSnap(3))
        advance(100L)
        turnTo(2)
        assertEquals("Game 3 of 3", runes())
        // A shrink clamps the current page to the last that is left.
        live = listOf(gameSnap(1), gameSnap(2))
        advance(500L)
        assertEquals("a shrink from three to two clamps to the last page: ${runes()}", "Game 2 of 2", runes())
        live = listOf(gameSnap(1))
        advance(500L)
        assertEquals("and down to one game there are no runes, and the one card is the first", null, runes())
        assertEquals(1, compose.onAllNodesWithTag(AchievementHubCardTag, useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun `the snapshot projects to a Loaded card with earned rows, hardcore medals, and points unknown`() {
        val card = snapshot().toAchievementCardState()
        assertEquals(3, card.unlocked)
        assertEquals(12, card.total)
        assertFalse(card.pointsKnown)
        assertEquals(listOf(MimirMedalState.EARNED, MimirMedalState.EARNED_HARDCORE, MimirMedalState.EARNED), card.rows.map { it.medal })
        assertEquals(longTitle, card.gameTitle)
        assertEquals("a snapshot with no title falls back to its game id", "g1", snapshot(title = " ").toAchievementCardState().gameTitle)
    }
}
