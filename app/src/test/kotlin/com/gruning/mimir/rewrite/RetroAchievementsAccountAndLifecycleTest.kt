package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsAchievement
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsProgress
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lane's account epoch, the card's always-fresh placeholder rule, the session-return signal and the collectors' lifetime. The sources are
 * MutableStateFlows, so none of this needs a Context, a credential or a connection.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RetroAchievementsAccountAndLifecycleTest {

    private fun progress(unlocked: Int = 1) = RetroAchievementsProgress(
        gameId = 9,
        title = "Game 9",
        achievements = (1..unlocked).map { RetroAchievementsAchievement(it, "A$it", "a", 5, unlocked = true, unlockedHardcore = false) },
    )

    private fun loaded(gameId: String, account: String, unlocked: Int = 1) =
        DerivedLane.of(gameId, account, MimirAchievementLaneState.Loaded(progress(unlocked)))

    // Account epoch -------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a probe started under A cannot record anything after a switch to B`() {
        val book = RetroAchievementsLaneBook()
        val epochA = book.accountEpoch()
        book.accountChanged() // B signed in

        assertFalse(book.noteSettled(loaded("x", "A"), epochA))
        assertNull(book.settledFor("x"))
        assertFalse(book.accountUnchangedSince(epochA))
    }

    @Test
    fun `a dismissal after the switch never shows the old account's data`() {
        val book = RetroAchievementsLaneBook()
        val epochA = book.accountEpoch()
        assertTrue(book.noteSettled(loaded("x", "A", unlocked = 3), epochA))

        book.accountChanged()
        // An old probe (or old recovery) finishing now cannot refill what the switch cleared...
        assertFalse(book.noteSettled(loaded("x", "A", unlocked = 3), epochA))
        // ...so the card going away with Checking on screen asks again instead of restoring A.
        assertEquals(CheckingRecovery.Reprobe, book.recoveryAfterCardGone("x", slotIsChecking = true))

        // A probe asked under B settles normally and is what a dismissal restores.
        val epochB = book.accountEpoch()
        val forB = loaded("x", "B", unlocked = 2)
        assertTrue(book.noteSettled(forB, epochB))
        assertEquals(CheckingRecovery.Restore(forB), book.recoveryAfterCardGone("x", slotIsChecking = true))
    }

    // The card ------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a card keeps a settled answer as its placeholder and shows Checking only over nothing`() {
        assertFalse(retroAchievementsCardShowsPlaceholder(null))
        assertFalse(retroAchievementsCardShowsPlaceholder(retroAchievementsCheckingState()))
        assertTrue(retroAchievementsCardShowsPlaceholder(retroAchievementsUnreachableState()))
        assertTrue(retroAchievementsCardShowsPlaceholder(MimirAchievementLaneState.NoSet(MimirAchievementNoSetReason.NOT_MATCHED)))
        assertTrue(retroAchievementsCardShowsPlaceholder(loaded("x", "A").published.state))
    }

    // Session return ------------------------------------------------------------------------------------------------------------------

    @Test
    fun `the session key tells phases of one operation apart`() {
        val dispatched = retroAchievementsSessionKey("op", MimirSessionPhase.DISPATCHED)
        val returning = retroAchievementsSessionKey("op", MimirSessionPhase.RETURNING)
        assertTrue(dispatched != returning)
        assertEquals(dispatched, retroAchievementsSessionKey("op", MimirSessionPhase.DISPATCHED))
        assertNull(retroAchievementsSessionKey(null, null))
    }

    @Test
    fun `a session counts as returned on its returning or ended phase or when it goes away, and never on launch`() {
        val launched = retroAchievementsSessionKey("op", MimirSessionPhase.DISPATCHED)
        val foreground = retroAchievementsSessionKey("op", MimirSessionPhase.FOREGROUND_PROVEN)
        val returning = retroAchievementsSessionKey("op", MimirSessionPhase.RETURNING)
        val ended = retroAchievementsSessionKey("op", MimirSessionPhase.ENDED)
        assertFalse(retroAchievementsSessionReturned(null, launched))
        assertFalse(retroAchievementsSessionReturned(launched, foreground))
        assertTrue(retroAchievementsSessionReturned(foreground, returning))
        assertTrue(retroAchievementsSessionReturned(returning, ended))
        assertTrue(retroAchievementsSessionReturned(foreground, null))
        assertFalse(retroAchievementsSessionReturned(null, null))
        assertFalse(retroAchievementsSessionReturned(returning, returning))
    }

    @Test
    fun `attach reports a return and an account change and nothing for a launch`() = runTest(UnconfinedTestDispatcher()) {
        val book = RetroAchievementsLaneBook()
        val session = MutableStateFlow<String?>(null)
        val account = MutableStateFlow<String?>("A")
        val key = MutableStateFlow(1)
        var returned = 0
        var accountChanged = 0
        val epoch = book.accountEpoch()
        book.attach(
            scope = backgroundScope,
            sessionKeys = session,
            accountSources = listOf(account, key),
            onSessionReturned = { returned += 1 },
            onAccountChanged = { accountChanged += 1 },
        )
        runCurrent()
        book.noteSettled(loaded("x", "A"), epoch)

        session.value = retroAchievementsSessionKey("op-1", MimirSessionPhase.DISPATCHED) // launch
        session.value = retroAchievementsSessionKey("op-1", MimirSessionPhase.FOREGROUND_PROVEN)
        assertEquals(0, returned)
        session.value = retroAchievementsSessionKey("op-1", MimirSessionPhase.RETURNING)
        assertEquals(1, returned)

        account.value = "B"
        assertEquals(1, accountChanged)
        assertNull(book.settledFor("x"))
        assertFalse(book.accountUnchangedSince(epoch))
        key.value = 2 // a key-state or opt-in change is the same event
        assertEquals(2, accountChanged)
    }

    // Lifetime ------------------------------------------------------------------------------------------------------------------------

    private class Sources {
        val session = MutableStateFlow<String?>(null)
        val account = MutableStateFlow<String?>("A")
        val validation = MutableStateFlow(0)
        val optIn = MutableStateFlow(true)

        fun collectors(): Int =
            session.subscriptionCount.value + account.subscriptionCount.value + validation.subscriptionCount.value + optIn.subscriptionCount.value
    }

    /** One create-and-dispose of the lane, in its own frame so nothing in the caller keeps the book reachable. */
    private fun createAndDispose(sources: Sources): WeakReference<RetroAchievementsLaneBook> = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val book = RetroAchievementsLaneBook()
        book.attach(scope, sources.session, listOf(sources.account, sources.validation, sources.optIn))
        assertEquals("one collector per source while it lives", 4, sources.collectors())
        scope.coroutineContext[Job]!!.cancelAndJoin() // what close() does
        assertEquals("every collector is gone once disposed", 0, sources.collectors())
        WeakReference(book)
    }

    @Test
    fun `creating and disposing the lane twice leaves no collector on the process flows and nothing holds the disposed lane`() {
        val sources = Sources()
        assertEquals(0, sources.collectors())
        val first = createAndDispose(sources)
        val second = createAndDispose(sources)
        assertEquals(0, sources.collectors())

        // A change after disposal reaches no one.
        sources.account.value = "B"

        var collected = false
        for (attempt in 1..40) {
            System.gc()
            if (first.get() == null && second.get() == null) {
                collected = true
                break
            }
            Thread.sleep(25)
        }
        assertTrue("the disposed lane must not be reachable from the process-wide flows", collected)
    }
}
