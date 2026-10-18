package com.gruning.mimir.rewrite.feature.library

import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.AndroidPackageName
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * the launch card of the game that HOME parked carries Restart beside Resume. What the press does (file a one-shot request, put
 * focus back on Play, press Play), what the request is worth, and where the extra key sits in the focus lattice. The painted row at
 * fonts 1.0 and 1.3 is `HomeLaunchCardRestartFitTest`'s; what `:app` does with the request is `MainGameHomeParkTest`'s.
 */
class HomeLaunchCardRestartTest {
    @After
    fun unpark() {
        HomeLaunchCardResume.set(null)
    }

    private val xenogears = HomeShelfItemId.Emulated(GameId("xenogears"))

    private fun authorityOnTheCard(hasExtraAction: Boolean, stacked: Boolean = false, width: Float = 530f): DefaultFocusAuthority {
        val authority = DefaultFocusAuthority()
        enterHomeLaunchCard(authority, emulated = true)
        registerHomeLaunchCardActions(authority, emulated = true, stacked = stacked, actionRowWidth = width, hasExtraAction = hasExtraAction)
        return authority
    }

    // ---- the word ---------------------------------------------------------------------------------------------------------

    @Test
    fun `the Restart word is one constant, and it is Restart`() {
        assertEquals("Restart", HomeLaunchCardResume.RestartLabel)
    }

    // ---- who gets the action ----------------------------------------------------------------------------------------------

    @Test
    fun `only the card of the parked game carries Restart`() {
        val authority = DefaultFocusAuthority()
        assertNull("nothing parked", homeLaunchCardRestartAction(xenogears, authority, HomeLaunchCardPlayKey) {})

        HomeLaunchCardResume.set(GameId("xenogears"))
        val action = homeLaunchCardRestartAction(xenogears, authority, HomeLaunchCardPlayKey) {}
        assertNotNull(action)
        assertEquals("Restart", action!!.label)
        assertEquals(HomeLaunchCardRestartKey, action.key)
        assertTrue(action.enabled)

        assertNull("another game's card", homeLaunchCardRestartAction(HomeShelfItemId.Emulated(GameId("crash")), authority, HomeLaunchCardPlayKey) {})
        assertNull(
            "an Android game of the same name",
            homeLaunchCardRestartAction(HomeShelfItemId.AndroidGame(AndroidPackageName("xenogears")), authority, HomeLaunchCardPlayKey) {},
        )

        HomeLaunchCardResume.set(null)
        assertNull("released again", homeLaunchCardRestartAction(xenogears, authority, HomeLaunchCardPlayKey) {})
    }

    // ---- the press --------------------------------------------------------------------------------------------------------

    @Test
    fun `a touch on Restart files the request, puts focus back on Play, and presses Play`() {
        HomeLaunchCardResume.set(GameId("xenogears"))
        val authority = authorityOnTheCard(hasExtraAction = true)
        authority.requestFocus(HomeLaunchCardRestartKey)
        val order = mutableListOf<String>()
        val action = homeLaunchCardRestartAction(xenogears, authority, HomeLaunchCardPlayKey) {
            // The Home card routes a tap through the FOCUSED key: Play must be it by now, or the press would be a second Restart.
            order += "play(focus=${authority.state.value.focused?.value})"
        }!!

        action.onActivate()

        assertEquals(listOf("play(focus=${HomeLaunchCardPlayKey.value})"), order)
        assertTrue("the request is filed", HomeLaunchCardResume.consumeRestart(GameId("xenogears")))
    }

    @Test
    fun `a controller A on Restart files the request and presses Play, and A on Play files nothing`() {
        HomeLaunchCardResume.set(GameId("xenogears"))
        val authority = authorityOnTheCard(hasExtraAction = true)
        var plays = 0

        fun confirm() = dispatchHomeLaunchCard(
            authority = authority,
            emulated = true,
            action = ControllerAction.CONFIRM,
            onPlay = { plays++ },
            onEdit = {},
            onEditTitle = { error("not the rename") },
            onAchievements = { error("not Achievements") },
            onOpenWith = { error("not Open with") },
            onClose = {},
        )

        authority.requestFocus(HomeLaunchCardPlayKey)
        assertTrue(confirm())
        assertEquals(1, plays)
        assertFalse("Resume is not a Restart", HomeLaunchCardResume.consumeRestart(GameId("xenogears")))

        authority.requestFocus(HomeLaunchCardRestartKey)
        assertTrue(confirm())
        assertEquals(2, plays)
        assertTrue(HomeLaunchCardResume.consumeRestart(GameId("xenogears")))
    }

    // ---- the request ------------------------------------------------------------------------------------------------------

    @Test
    fun `a request is worth one press for its own game, inside its window`() {
        HomeLaunchCardResume.set(GameId("xenogears"))

        assertTrue(HomeLaunchCardResume.requestRestart(nowMs = 10_000L))
        assertTrue(HomeLaunchCardResume.consumeRestart(GameId("xenogears"), nowMs = 10_200L))
        assertFalse("one-shot", HomeLaunchCardResume.consumeRestart(GameId("xenogears"), nowMs = 10_300L))
    }

    @Test
    fun `two presses in a row cannot both take one request, and a press on another game clears it`() {
        HomeLaunchCardResume.set(GameId("xenogears"))

        HomeLaunchCardResume.requestRestart(nowMs = 10_000L)
        val first = HomeLaunchCardResume.consumeRestart(GameId("xenogears"), nowMs = 10_001L)
        val second = HomeLaunchCardResume.consumeRestart(GameId("xenogears"), nowMs = 10_002L)
        assertTrue("the press that came first gets the Restart", first)
        assertFalse("the double-press does not get a second one", second)

        HomeLaunchCardResume.requestRestart(nowMs = 20_000L)
        assertFalse("another game's press never ends this one, and takes the request with it", HomeLaunchCardResume.consumeRestart(GameId("crash"), nowMs = 20_001L))
        assertFalse("so the parked game's own next press is a plain Resume", HomeLaunchCardResume.consumeRestart(GameId("xenogears"), nowMs = 20_002L))
    }

    @Test
    fun `many presses racing for one request give it to exactly one`() {
        HomeLaunchCardResume.set(GameId("xenogears"))
        HomeLaunchCardResume.requestRestart(nowMs = 10_000L)
        val wins = java.util.concurrent.atomic.AtomicInteger(0)
        val start = java.util.concurrent.CountDownLatch(1)
        val threads = (1..8).map {
            Thread {
                start.await()
                if (HomeLaunchCardResume.consumeRestart(GameId("xenogears"), nowMs = 10_001L)) wins.incrementAndGet()
            }.also(Thread::start)
        }
        start.countDown()
        threads.forEach(Thread::join)
        assertEquals(1, wins.get())
    }

    @Test
    fun `a request that no Play press picked up lapses, and one that is not yet due is not a request`() {
        HomeLaunchCardResume.set(GameId("xenogears"))

        HomeLaunchCardResume.requestRestart(nowMs = 10_000L)
        assertFalse(HomeLaunchCardResume.consumeRestart(GameId("xenogears"), nowMs = 10_000L + HomeLaunchCardResume.RestartRequestWindowMs + 1L))

        HomeLaunchCardResume.requestRestart(nowMs = 10_000L)
        assertTrue("the edge of the window still counts", HomeLaunchCardResume.consumeRestart(GameId("xenogears"), nowMs = 10_000L + HomeLaunchCardResume.RestartRequestWindowMs))

        HomeLaunchCardResume.requestRestart(nowMs = 10_000L)
        assertFalse("a clock that moved back is never a fresh request", HomeLaunchCardResume.consumeRestart(GameId("xenogears"), nowMs = 9_000L))
    }

    @Test
    fun `nothing parked means nothing to file, and a lease that comes or goes starts clean`() {
        assertFalse(HomeLaunchCardResume.requestRestart(nowMs = 1L))
        assertFalse(HomeLaunchCardResume.consumeRestart(GameId("xenogears"), nowMs = 2L))

        HomeLaunchCardResume.set(GameId("xenogears"))
        HomeLaunchCardResume.requestRestart(nowMs = 10L)
        HomeLaunchCardResume.set(GameId("xenogears"))
        assertTrue("the same lease keeps its request", HomeLaunchCardResume.consumeRestart(GameId("xenogears"), nowMs = 11L))

        HomeLaunchCardResume.requestRestart(nowMs = 10L)
        HomeLaunchCardResume.set(null)
        HomeLaunchCardResume.set(GameId("xenogears"))
        assertFalse("a released lease takes its request with it", HomeLaunchCardResume.consumeRestart(GameId("xenogears"), nowMs = 11L))
    }

    // ---- the lattice ------------------------------------------------------------------------------------------------------

    @Test
    fun `Restart is the second slot, between Resume and Open with, and only on a card that carries it`() {
        assertEquals(
            listOf(
                HomeLaunchCardTitleEditKey,
                HomeLaunchCardPlayKey,
                HomeLaunchCardRestartKey,
                HomeLaunchCardOpenWithKey,
                HomeLaunchCardAchievementsKey,
            ),
            homeLaunchCardActionKeys(emulated = true, hasExtraAction = true),
        )
        assertEquals(
            listOf(HomeLaunchCardTitleEditKey, HomeLaunchCardPlayKey, HomeLaunchCardOpenWithKey, HomeLaunchCardAchievementsKey),
            homeLaunchCardActionKeys(emulated = true),
        )
        assertEquals(listOf(HomeLaunchCardPlayKey), homeLaunchCardActionKeys(emulated = false, hasExtraAction = true))
    }

    @Test
    fun `one row at its nominal width is Resume, Restart, Open with, Achievements with a ring reach between`() {
        val bounds = homeLaunchCardActionBounds(stacked = false, actionRowWidth = 530f, hasExtraAction = true)

        assertEquals(FocusBounds(0f, 0f, 96f, 44f), bounds.getValue(HomeLaunchCardPlayKey))
        assertEquals(FocusBounds(110f, 0f, 246f, 44f), bounds.getValue(HomeLaunchCardRestartKey))
        assertEquals(FocusBounds(260f, 0f, 380f, 44f), bounds.getValue(HomeLaunchCardOpenWithKey))
        assertEquals(FocusBounds(394f, 0f, 530f, 44f), bounds.getValue(HomeLaunchCardAchievementsKey))
        assertEquals(HomeLaunchCardTitleEditBounds, bounds.getValue(HomeLaunchCardTitleEditKey))
    }

    @Test
    fun `a shrunk row still reads the painted solver - every slot at least 48 dp, a ring reach apart, ending at the row`() {
        val floor = homeLaunchCardActionRowFloorWidth(hasExtraAction = true).value
        listOf(530f, 480f, 440f, floor).forEach { width ->
            val bounds = homeLaunchCardActionBounds(stacked = false, actionRowWidth = width, hasExtraAction = true)
            val slots = listOf(HomeLaunchCardPlayKey, HomeLaunchCardRestartKey, HomeLaunchCardOpenWithKey, HomeLaunchCardAchievementsKey)
                .map { bounds.getValue(it) }
            val widths = homeLaunchCardActionButtonWidths(width.dp, hasExtraAction = true).map { it.value }
            slots.forEachIndexed { index, slot ->
                assertEquals("width $width slot $index", widths[index], slot.right - slot.left, 0.001f)
                assertTrue("width $width slot $index is at least 48 dp: ${slot.right - slot.left}", slot.right - slot.left >= 48f - 0.001f)
                if (index > 0) assertEquals("width $width gap before slot $index", HomeLaunchCardActionGap.value, slot.left - slots[index - 1].right, 0.001f)
            }
            assertEquals("width $width: the row ends where the painted row does", width.coerceAtMost(530f), slots.last().right, 0.01f)
        }
    }

    @Test
    fun `below the stacking width Resume and Restart sit on the first row, Open with and Achievements on the second`() {
        val bounds = homeLaunchCardActionBounds(stacked = true, actionRowWidth = 400f, hasExtraAction = true)

        val play = bounds.getValue(HomeLaunchCardPlayKey)
        val restart = bounds.getValue(HomeLaunchCardRestartKey)
        val openWith = bounds.getValue(HomeLaunchCardOpenWithKey)
        val achievements = bounds.getValue(HomeLaunchCardAchievementsKey)
        assertEquals(0f, play.top, 0f)
        assertEquals(play.top, restart.top, 0f)
        assertEquals("a ring reach between the first row's two buttons", HomeLaunchCardActionGap.value, restart.left - play.right, 0.001f)
        assertEquals("the second row is a ring reach below the first", 44f + HomeLaunchCardActionGap.value, openWith.top, 0.001f)
        assertEquals(openWith.top, achievements.top, 0f)
        assertEquals("a ring reach between the second row's two buttons", HomeLaunchCardActionGap.value, achievements.left - openWith.right, 0.001f)
        // Each row is centred in the row width.
        assertEquals((400f - (restart.right - play.left)) / 2f, play.left, 0.001f)
        assertEquals(400f / 2f, (openWith.left + achievements.right) / 2f, 0.001f)
    }

    @Test
    fun `without an extra action the lattice is exactly what it was`() {
        listOf(false, true).forEach { stacked ->
            assertEquals(
                homeLaunchCardActionBounds(stacked, 376f),
                homeLaunchCardActionBounds(stacked, 376f, hasExtraAction = false),
            )
        }
        assertFalse(HomeLaunchCardRestartKey in homeLaunchCardActionBounds(stacked = false))
    }

    @Test
    fun `registering for a card that carries Restart adds the key, and the next card without it takes it away again`() {
        val authority = authorityOnTheCard(hasExtraAction = true)
        authority.requestFocus(HomeLaunchCardRestartKey)
        assertEquals(HomeLaunchCardRestartKey, authority.state.value.focused)

        registerHomeLaunchCardActions(authority, emulated = true, hasExtraAction = false)

        assertNotNull(authority.state.value.focused)
        assertTrue("focus fell back onto a key that is still on the card", authority.state.value.focused != HomeLaunchCardRestartKey)
        authority.requestFocus(HomeLaunchCardRestartKey)
        assertTrue("the key is gone, so it cannot be focused again", authority.state.value.focused != HomeLaunchCardRestartKey)

        exitHomeLaunchCard(authority)
    }
}
