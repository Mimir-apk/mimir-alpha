package com.gruning.mimir.rewrite

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gruning.mimir.rewrite.core.model.library.SourceReconcileResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Alpha cert 2026-10-08 (E4): "Add artwork?" came back after rescans and after a source was removed and added again, though
 * "Not now" had been pressed each time. A game id is content-derived, so a folder added back returns the SAME ids; they were
 * present when "Not now" was pressed. After it the question stays away until a scan adds a game that was not, and the answer
 * is on disk so a restart keeps it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NewGameEnrichAnsweredGamesTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun settle() {
        NewGameEnrichPromptState.restore(context)
        NewGameEnrichPromptState.dismiss(context)
    }

    @After
    fun clear() {
        NewGameEnrichPromptState.dismiss(context)
    }

    private fun scan(vararg ids: String) = SourceReconcileResult(
        sourcesChecked = 1,
        healthySources = 1,
        removedSources = 0,
        filesSeen = ids.size,
        recognized = ids.size,
        groups = ids.size,
        skipped = 0,
        addedGameIds = ids.toSet(),
    )

    @Test
    fun `after Not now the question stays away until a scan adds a game that was not there`() {
        NewGameEnrichPromptState.recordScan(context, scan("g1", "g2", "g3"))
        assertEquals(setOf("g1", "g2", "g3"), NewGameEnrichPromptState.pending.value?.gameIds)
        NewGameEnrichPromptState.dismiss(context)
        assertNull(NewGameEnrichPromptState.pending.value)

        // A rescan that finds nothing new.
        NewGameEnrichPromptState.recordScan(context, scan())
        assertNull("a scan that adds nothing asks nothing", NewGameEnrichPromptState.pending.value)

        // The folder is removed and added back: the same games return, and they were here when Not now was pressed.
        NewGameEnrichPromptState.recordScan(context, scan("g2", "g3"))
        assertNull("games already answered are not new", NewGameEnrichPromptState.pending.value)
        assertNull(NewGameEnrichPromptState.current.value)

        // A game that really was not there is offered, and only it.
        NewGameEnrichPromptState.recordScan(context, scan("g2", "g9"))
        assertEquals(setOf("g9"), NewGameEnrichPromptState.pending.value?.gameIds)
    }

    @Test
    fun `the answer is kept on disk, so a restart still knows it`() {
        NewGameEnrichPromptState.recordScan(context, scan("r1", "r2"))
        NewGameEnrichPromptState.dismiss(context)

        val stored = context.getSharedPreferences(NewGameEnrichPromptState.AnsweredPreferencesName, Context.MODE_PRIVATE)
            .getStringSet(NewGameEnrichPromptState.AnsweredGameIdsKey, null)

        assertNotNull(stored)
        assertTrue("both answered games are on disk: $stored", stored!!.containsAll(listOf("r1", "r2")))
        // A process that starts with only the file in front of it reads the same answer.
        NewGameEnrichPromptState.recordScan(context, scan("r1", "r2"))
        assertNull(NewGameEnrichPromptState.pending.value)
    }

    @Test
    fun `a question held back keeps its games when a newer scan adds more, and the card offers all of them`() {
        val held = (1..195).map { "held-$it" }.toTypedArray()
        NewGameEnrichPromptState.recordScan(context, scan(*held))
        NewGameEnrichPromptState.recordScan(context, scan("late-1", "late-2", "late-3"))

        val pending = requireNotNull(NewGameEnrichPromptState.pending.value)
        assertEquals("195 held back plus 3 new is 198, not 3", 198, pending.gameIds.size)
        assertTrue(pending.gameIds.containsAll(held.toList()))

        // Nothing was answered, so none of them is on the answered list yet.
        val answeredBefore = context.getSharedPreferences(NewGameEnrichPromptState.AnsweredPreferencesName, Context.MODE_PRIVATE)
            .getStringSet(NewGameEnrichPromptState.AnsweredGameIdsKey, null).orEmpty()
        assertTrue("an unasked game is not an answered one: ${answeredBefore.size}", answeredBefore.none { it.startsWith("held-") })

        // The card, once the surface is resting and quiet, offers all 198.
        NewGameEnrichPromptState.holdWhile(covered = false, quiet = true)
        NewGameEnrichPromptState.offerAfterHash(context, pending)
        assertEquals(198, NewGameEnrichPromptState.current.value?.gameIds?.size)

        NewGameEnrichPromptState.dismiss(context)
        NewGameEnrichPromptState.holdWhile(covered = true, quiet = false)
        // Now they are answered, and the same games coming back ask nothing.
        NewGameEnrichPromptState.recordScan(context, scan(*held))
        assertNull(NewGameEnrichPromptState.pending.value)
    }

    @Test
    fun `leaving onboarding's accounts step answers the first scan's games for good`() {
        NewGameEnrichPromptState.stampOnboardingSkip(context)
        NewGameEnrichPromptState.recordScan(context, scan("o1", "o2"))
        assertNull("the skip answered the first-run scan", NewGameEnrichPromptState.pending.value)

        NewGameEnrichPromptState.recordScan(context, scan("o1", "o2"))
        assertNull("and the same games coming back later are not new", NewGameEnrichPromptState.pending.value)

        NewGameEnrichPromptState.recordScan(context, scan("o3"))
        assertEquals(setOf("o3"), NewGameEnrichPromptState.pending.value?.gameIds)
    }

    @Test
    fun `the answered set keeps the newest answers when it is trimmed`() {
        assertEquals(setOf("new", "old1"), rememberAnsweredGames(setOf("old1", "old2"), setOf("new"), cap = 2))
        assertEquals(setOf("a", "b"), gamesNotYetAnswered(setOf("a", "b", "c"), setOf("c")))
    }
}
