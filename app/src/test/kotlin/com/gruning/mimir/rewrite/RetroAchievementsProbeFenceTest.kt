package com.gruning.mimir.rewrite

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fence is what keeps an older RetroAchievements answer from overwriting a newer one. These tests drive it the way the lane does (claim a token
 * before suspending, publish only while current, finish in a finally) with controllable answers, so the order the answers ARRIVE in is the test's to choose.
 * A probe that "swallows" cancellation stands for a dependency that ignores it, which is the case the fence must still drop.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RetroAchievementsProbeFenceTest {

    private val fence = RetroAchievementsProbeFence()
    private val published = mutableListOf<String>()

    private suspend fun probe(game: String, label: String, answer: CompletableDeferred<Unit>, swallowCancellation: Boolean = false) {
        val token = fence.begin(game, currentCoroutineContext()[Job])
        try {
            if (swallowCancellation) withContext(NonCancellable) { answer.await() } else answer.await()
            if (fence.isCurrent(game, token)) published += "$game:$label"
        } finally {
            fence.finish(game, token)
        }
    }

    private fun TestScope.start(game: String, label: String, answer: CompletableDeferred<Unit>, swallow: Boolean = false): Job =
        launch { probe(game, label, answer, swallow) }.also { runCurrent() }

    @Test
    fun `ambient then card with the ambient answer last publishes only the card's answer`() = runTest {
        val ambient = CompletableDeferred<Unit>()
        val card = CompletableDeferred<Unit>()
        start("x", "ambient", ambient, swallow = true)
        start("x", "card", card)
        card.complete(Unit)
        advanceUntilIdle()
        ambient.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("x:card"), published)
    }

    @Test
    fun `an ambient probe that honours cancellation is cancelled by the card and never publishes`() = runTest {
        val ambient = CompletableDeferred<Unit>()
        val card = CompletableDeferred<Unit>()
        val ambientJob = start("x", "ambient", ambient)
        start("x", "card", card)
        assertTrue(ambientJob.isCancelled)
        ambient.complete(Unit)
        card.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("x:card"), published)
    }

    @Test
    fun `card then ambient the newer ambient wins in either arrival order`() = runTest {
        val card = CompletableDeferred<Unit>()
        val ambient = CompletableDeferred<Unit>()
        start("x", "card", card, swallow = true)
        start("x", "ambient", ambient)
        // The older card answer arrives last.
        ambient.complete(Unit)
        advanceUntilIdle()
        card.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("x:ambient"), published)

        published.clear()
        val card2 = CompletableDeferred<Unit>()
        val ambient2 = CompletableDeferred<Unit>()
        start("x", "card", card2, swallow = true)
        start("x", "ambient", ambient2, swallow = true)
        // The older card answer arrives first: it is already superseded, so it is dropped and the ambient one publishes.
        card2.complete(Unit)
        advanceUntilIdle()
        ambient2.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("x:ambient"), published)
    }

    @Test
    fun `focus moving from X to Y never lets a late X answer touch Y`() = runTest {
        val x = CompletableDeferred<Unit>()
        val y = CompletableDeferred<Unit>()
        start("x", "x", x, swallow = true)
        start("y", "y", y)
        y.complete(Unit)
        advanceUntilIdle()
        // X's response lands after Y's: dropped, because Y is the game the lane is about now.
        x.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("y:y"), published)

        // And if X's answer lands BEFORE Y's, it is dropped too, so it cannot sit in the slot Y is about to fill.
        published.clear()
        val x2 = CompletableDeferred<Unit>()
        val y2 = CompletableDeferred<Unit>()
        start("x", "x", x2, swallow = true)
        start("y", "y", y2, swallow = true)
        x2.complete(Unit)
        advanceUntilIdle()
        y2.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("y:y"), published)
    }

    @Test
    fun `an answer for the game the lane is about publishes and a finished probe leaves nothing behind`() = runTest {
        val first = CompletableDeferred<Unit>()
        start("x", "first", first)
        first.complete(Unit)
        advanceUntilIdle()
        val second = CompletableDeferred<Unit>()
        start("x", "second", second)
        second.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("x:first", "x:second"), published)

        val token = fence.begin("z", null)
        assertTrue(fence.isCurrent("z", token))
        fence.finish("z", token)
        assertFalse(fence.isCurrent("z", token))
    }
}
