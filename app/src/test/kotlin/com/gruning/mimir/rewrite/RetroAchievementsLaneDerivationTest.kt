package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsAchievement
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsProgress
import com.gruning.mimir.rewrite.core.model.RaKeyState
import com.gruning.mimir.rewrite.core.ui.MimirAchievementCardState
import com.gruning.mimir.rewrite.core.ui.MimirMedalState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * "Load" here means the repeated work observed in the UI: asking the source for a game's progress and deriving the card, the label, the
 * hub's snapshot list and the strip's counts from it. The lane asks the source once per probe and derives once per answer; the screens
 * (Main's hero and card, the Bottom's strip, hub and launch card) only READ. A counting fake source stands in for the RetroAchievements
 * client, so these tests need no network and no credentials.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RetroAchievementsLaneDerivationTest {

    private val account = "lane-derivation-player"

    @Before
    fun reset() = CompanionActivityAchievements.resetForTest()

    private fun progress(unlockedIds: Set<Int>) = RetroAchievementsProgress(
        gameId = 5150,
        title = "Derivation Game",
        achievements = (1..60).map { id ->
            RetroAchievementsAchievement(
                id = id,
                title = "Achievement $id",
                description = "Description $id",
                points = id,
                unlocked = id in unlockedIds,
                unlockedHardcore = id in unlockedIds && id % 3 == 0,
            )
        },
    )

    /** The source the lane reads: counts every load, answers whatever it was last told. */
    private class CountingSource(var current: RetroAchievementsProgress) {
        var loads = 0
            private set

        fun load(): RetroAchievementsProgress {
            loads += 1
            return current
        }
    }

    /** One lane probe, reduced to what it does after the source answers: derive once, publish. */
    private fun probe(source: CountingSource, gameId: String): DerivedLane {
        val lane = MimirAchievementLaneState.Loaded(source.load())
        val derived = DerivedLane.of(gameId, account, lane)
        CompanionActivityAchievements.publish(gameId, account, lane, derived.snapshot)
        return derived
    }

    @Test
    fun `a published lane carries its card and label already built and unchanged in value`() {
        val state = MimirAchievementLaneState.Loaded(progress(setOf(3, 9, 12, 40)))
        val published = mimirPublishedLane("g", state)

        // The values are exactly the ones the screens derived inline before.
        assertEquals(mimirAchievementCardState(state), published.card)
        assertEquals(mimirAchievementLaneLabel(state), published.label)
        assertEquals("4/60", published.label)
        val card = published.card as MimirAchievementCardState.Loaded
        assertEquals(3 + 9 + 12 + 40, card.earnedPoints)
        assertEquals((1..60).sum(), card.totalPoints)
        // Earned first (points high to low), then the locked ones.
        assertEquals(listOf(40, 12, 9, 3), card.rows.take(4).map { it.id })
        assertEquals(MimirMedalState.EARNED_HARDCORE, card.rows.first { it.id == 12 }.medal)
        assertEquals(MimirMedalState.EARNED, card.rows.first { it.id == 40 }.medal)
        assertEquals(MimirMedalState.LOCKED, card.rows.last().medal)

        // Reading it again is free: the same instance, not another sort.
        repeat(50) { assertSame(published.card, published.card) }
    }

    @Test
    fun `a game nothing has answered for reads one shared unknown card`() {
        assertSame(mimirAchievementCardState(null), mimirAchievementCardState(null))
        assertTrue(mimirAchievementCardState(null) is MimirAchievementCardState.Unknown)
    }

    @Test
    fun `fifty swipes page changes and re-mounts cause no extra loads and one data change causes one`() =
        runTest(UnconfinedTestDispatcher()) {
            var derivations = 0
            val view = companionActivityAchievementHubViewFlow(
                snapshots = CompanionActivityAchievements.snapshots,
                connectedServicesOn = MutableStateFlow(true),
                account = MutableStateFlow<String?>(account),
                validation = MutableStateFlow(RaKeyState.ACCEPTED),
            ) { snapshots, on, acct, validation, points ->
                derivations += 1
                companionActivityAchievementHubView(snapshots, on, acct, validation, points)
            }.stateIn(
                scope = backgroundScope,
                started = SharingStarted.Eagerly,
                initialValue = companionActivityAchievementHubView(emptyList(), true, account, RaKeyState.ACCEPTED),
            )
            val baseline = derivations

            val source = CountingSource(progress(setOf(1, 2, 3)))
            val first = probe(source, "g-swipe")
            assertEquals(1, source.loads)
            assertEquals(baseline + 1, derivations)
            assertEquals(3, view.value.unlocked)
            assertEquals(60, view.value.total)
            val heldView = view.value
            val heldCard = view.value.snapshots.single().toAchievementCardState()

            // Fifty swipes, page changes and re-mounts of the Bottom's Activity page: each one reads the holder, the strip's counts, the hub's
            // cards and the launch card's snapshot list. None of them reaches the source and none derives anything.
            repeat(50) {
                val read = view.value
                assertSame(heldView, read)
                assertSame(heldCard, read.snapshots.single().toAchievementCardState())
                assertEquals(3, read.unlocked)
                assertSame(read.snapshots, view.value.snapshots)
            }
            assertEquals(1, source.loads)
            assertEquals(baseline + 1, derivations)
            assertSame(first.published.card, first.published.card)

            // Main's focus commit asks the lane again and the source answers the same data fifty times over: the sink keeps what it holds, so
            // the Bottom's inputs do not change and nothing downstream is derived again.
            repeat(50) { probe(source, "g-swipe") }
            assertEquals(51, source.loads)
            assertEquals(baseline + 1, derivations)
            assertSame(heldView, view.value)

            // One data change (an achievement earned) is exactly one more derivation, and the new values are the new data's.
            source.current = progress(setOf(1, 2, 3, 4))
            probe(source, "g-swipe")
            assertEquals(baseline + 2, derivations)
            assertEquals(4, view.value.unlocked)
            assertEquals(60, view.value.total)
            assertEquals(1 + 2 + 3 + 4, view.value.snapshots.single().toAchievementCardState().earnedPoints)
            repeat(50) { assertEquals(4, view.value.unlocked) }
            assertEquals(baseline + 2, derivations)
        }

    @Test
    fun `the hub view gates by opt-in key and account and counts across games`() {
        val one = snapshot("g1", 3, 10, 30L)
        val two = snapshot("g2", 5, 20, 20L)
        val foreign = snapshot("g3", 1, 4, 10L).copy(account = "someone-else")

        val open = companionActivityAchievementHubView(listOf(two, foreign, one), true, account, RaKeyState.ACCEPTED)
        assertTrue(open.enabled)
        assertEquals(listOf("g1", "g2"), open.snapshots.map { it.gameId })
        assertEquals(8, open.unlocked)
        assertEquals(30, open.total)

        val closedCases = listOf(
            Triple(false, RaKeyState.ACCEPTED, account),
            Triple(true, RaKeyState.REJECTED_THIS_SESSION, account),
            Triple(true, RaKeyState.ACCEPTED, null),
            Triple(true, RaKeyState.ACCEPTED, ""),
        )
        for ((on, key, name) in closedCases) {
            val closed = companionActivityAchievementHubView(listOf(one, two), on, name, key)
            assertFalse(closed.enabled)
            assertTrue(closed.snapshots.isEmpty())
            assertEquals(0, closed.unlocked)
            assertEquals(0, closed.total)
            assertEquals(companionActivityAchievementUnknownState(on, name, key), closed.unknownState)
        }
    }

    private fun snapshot(gameId: String, unlocked: Int, total: Int, checkedAt: Long) =
        CompanionActivityAchievementSnapshot(
            gameId = gameId,
            account = account,
            unlocked = unlocked,
            total = total,
            gameTitle = "Game $gameId",
            checkedAtEpochMillis = checkedAt,
        )
}
