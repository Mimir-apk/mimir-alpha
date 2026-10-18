package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.model.home.AddToShelfResult
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfMembership
import com.gruning.mimir.rewrite.feature.library.HomeLaunchReturnTarget
import com.gruning.mimir.rewrite.feature.library.HomeShelfRow
import com.gruning.mimir.rewrite.feature.library.HomeShelfScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeShelfIntegrityTest {
    private val targetId = HomeShelfItemId.Emulated(GameId("target"))
    private val target = HomeLaunchReturnTarget(itemId = targetId, rank = 2_048L)
    private val membership = HomeShelfMembership(targetId, rank = 2_048L, addedAtMillis = 20L)

    @Test
    fun `delayed row truth keeps exact pending identity until it can register`() {
        // Rows not yet emitted for the restored context → the cover holds.
        assertEquals(
            HomeLaunchReturnResolution.AwaitingRowTruth,
            resolveHomeLaunchReturn(target, rows = null),
        )

        val first = row("first", rank = 1_024L)
        val exact = row("target", rank = 2_048L)
        assertEquals(
            HomeLaunchReturnResolution.Ready(exact.focusKey),
            resolveHomeLaunchReturn(target, rows = listOf(first, exact)),
        )
        assertFalse(exact.focusKey == first.focusKey)
    }

    @Test
    fun `loaded rows without the identity report absence rather than guessing another row`() {
        // The coordinator releases after the grace window — never by substituting a row.
        assertEquals(
            HomeLaunchReturnResolution.RowAbsent,
            resolveHomeLaunchReturn(target, rows = emptyList()),
        )
    }

    @Test
    fun `exact return never substitutes rank neighbor`() {
        val neighbor = row("neighbor", rank = target.rank)

        assertEquals(
            HomeLaunchReturnResolution.RowAbsent,
            resolveHomeLaunchReturn(target, rows = listOf(neighbor)),
        )
    }

    @Test
    fun `process recreation focus memory waits for exact row instead of seeding item zero`() {
        val authority = DefaultFocusAuthority()
        val first = row("first", rank = 1_024L)
        val exact = row("target", rank = 2_048L)
        val bounds = FocusBounds(0f, 0f, 10f, 10f)
        authority.rememberFocusForScope(HomeShelfScope, target.focusKey)
        authority.pushScope(HomeShelfScope)

        authority.registerVirtual(first.focusKey, HomeShelfScope, bounds)
        assertEquals(null, authority.state.value.focused)
        authority.registerVirtual(exact.focusKey, HomeShelfScope, bounds)

        assertEquals(exact.focusKey, authority.state.value.focused)
    }

    @Test
    fun `add no-op is truthful and does not advance revision`() {
        val feedback = resolveHomeShelfAddMutation(
            Result.success(AddToShelfResult.AlreadyPresent(membership)),
        )

        assertEquals(HomeShelfMutationDisposition.NO_OP, feedback.disposition)
        assertEquals("Already a favorite", feedback.message)
        assertEquals(7L, nextHomeShelfMutationRevision(7L, feedback))
    }

    @Test
    fun `remove no-op is truthful and does not advance revision`() {
        val feedback = resolveHomeShelfRemoveMutation(Result.success(false))

        assertEquals(HomeShelfMutationDisposition.NO_OP, feedback.disposition)
        assertEquals("Not a favorite", feedback.message)
        assertEquals(7L, nextHomeShelfMutationRevision(7L, feedback))
    }

    @Test
    fun `mutation failure reports failure and does not advance revision`() {
        val feedback = resolveHomeShelfAddMutation(Result.failure(IllegalStateException("disk")))

        assertEquals(HomeShelfMutationDisposition.FAILURE, feedback.disposition)
        assertEquals("Favorite not saved", feedback.message)
        assertEquals(7L, nextHomeShelfMutationRevision(7L, feedback))
    }

    @Test
    fun `real mutation advances revision exactly once`() {
        val feedback = resolveHomeShelfAddMutation(Result.success(AddToShelfResult.Added(membership)))

        assertTrue(feedback.changed)
        assertEquals(8L, nextHomeShelfMutationRevision(7L, feedback))
    }

    private fun row(id: String, rank: Long) = HomeShelfRow(
        itemId = HomeShelfItemId.Emulated(GameId(id)),
        rank = rank,
        addedAtMillis = rank,
        title = id,
    )
}
