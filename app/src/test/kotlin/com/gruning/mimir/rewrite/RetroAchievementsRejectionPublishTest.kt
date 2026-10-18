package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsAchievement
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsProgress
import com.gruning.mimir.rewrite.core.model.RaKeyState
import com.gruning.mimir.rewrite.core.ui.MimirAchievementCardState
import com.gruning.mimir.rewrite.core.ui.MimirAchievementCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A 401/403 on the CURRENT key marks the key rejected, and that change bumps the account epoch, so the probe that detected the rejection finds its
 * own epoch stale. Its rejection must still reach an open card (no reopen), while any other answer from an older epoch must still be dropped.
 * The publish rule and the slot rule are pure, and the real epoch book drives the order the notification and the probe's publication race in.
 */
class RetroAchievementsRejectionPublishTest {

    private val account = "rejected-player"
    private val rejected = retroAchievementsRejectedState()

    private fun success() = MimirAchievementLaneState.Loaded(
        RetroAchievementsProgress(7, "Stale", listOf(RetroAchievementsAchievement(1, "A", "a", 5, unlocked = true, unlockedHardcore = false))),
    )

    /** The slot and the book as the coordinator keeps them. */
    private class Lane(private val account: String) {
        val book = RetroAchievementsLaneBook()
        var slot: MimirPublishedLane? = null
        var keyState = RaKeyState.ACCEPTED

        /** The change's notification: the key was rejected, which bumps the epoch and gives the slot its account-change treatment. */
        fun notifyKeyRejected() {
            keyState = RaKeyState.REJECTED_THIS_SESSION
            book.accountChanged()
            slot = retroAchievementsSlotAfterAccountChange(slot, account, keyState)
        }

        /** A probe's publication, gated exactly as the coordinator gates it. Returns whether it published. */
        fun publish(epoch: Long, state: MimirAchievementLaneState): Boolean {
            val allowed = retroAchievementsPublishAllowed(
                epochUnchanged = book.accountUnchangedSince(epoch),
                accountUnchanged = true,
                state = state,
                keyState = keyState,
            )
            if (allowed) slot = mimirPublishedLane("x", state, account)
            return allowed
        }

        fun cardState(): MimirAchievementCardState = slot?.card ?: mimirAchievementCardState(null)
    }

    @Test
    fun `a card whose probe gets a 401 shows the key-rejected state without a reopen in either order`() {
        // Order 1: the notification clears first, then the probe that detected the rejection publishes under the new epoch.
        val first = Lane(account)
        first.slot = mimirPublishedLane("x", retroAchievementsCheckingState(), account)
        val probeEpoch = first.book.accountEpoch()
        first.notifyKeyRejected()
        assertNull(first.slot)
        assertEquals(MimirAchievementCardState.Unknown(MimirAchievementCopy.NoAnswerYet), first.cardState())
        assertTrue(first.publish(probeEpoch, rejected))
        assertEquals(mimirAchievementCardState(rejected), first.cardState())
        assertEquals(MimirAchievementCardState.Unknown(MimirAchievementCopy.CredentialsRejected), first.cardState())

        // Order 2: the probe publishes first, then the notification arrives; the rejection it produced must survive it.
        val second = Lane(account)
        val epoch = second.book.accountEpoch()
        second.keyState = RaKeyState.REJECTED_THIS_SESSION
        assertTrue(second.publish(epoch, rejected))
        second.notifyKeyRejected()
        assertEquals(MimirAchievementCardState.Unknown(MimirAchievementCopy.CredentialsRejected), second.cardState())
    }

    @Test
    fun `a stale-epoch success is still dropped`() {
        val lane = Lane(account)
        val epoch = lane.book.accountEpoch()
        lane.notifyKeyRejected()
        assertFalse(lane.publish(epoch, success()))
        assertNull(lane.slot)
        // And so is a success asked before any other account-side change.
        val other = Lane(account)
        val old = other.book.accountEpoch()
        other.book.accountChanged()
        assertFalse(other.publish(old, success()))
    }

    @Test
    fun `a rejection is published only for the current key and the current account`() {
        // Stale epoch, but the key in force is not the rejected one (a replacement was saved): the old key's rejection is dropped.
        assertFalse(retroAchievementsPublishAllowed(epochUnchanged = false, accountUnchanged = true, state = rejected, keyState = RaKeyState.ACCEPTED))
        // A different account is signed in now: dropped even though the key state reads rejected.
        assertFalse(
            retroAchievementsPublishAllowed(epochUnchanged = false, accountUnchanged = false, state = rejected, keyState = RaKeyState.REJECTED_THIS_SESSION),
        )
        assertTrue(
            retroAchievementsPublishAllowed(epochUnchanged = false, accountUnchanged = true, state = rejected, keyState = RaKeyState.REJECTED_THIS_SESSION),
        )
        // The ordinary case is unchanged: nothing moved, anything publishes; the account moved, nothing does.
        assertTrue(retroAchievementsPublishAllowed(true, true, success(), RaKeyState.ACCEPTED))
        assertFalse(retroAchievementsPublishAllowed(true, false, success(), RaKeyState.ACCEPTED))
    }

    @Test
    fun `an account change clears the slot except the rejection it was caused by`() {
        val rejectedSlot = mimirPublishedLane("x", rejected, account)
        val loadedSlot = mimirPublishedLane("x", success(), account)
        assertEquals(rejectedSlot, retroAchievementsSlotAfterAccountChange(rejectedSlot, account, RaKeyState.REJECTED_THIS_SESSION))
        assertNull(retroAchievementsSlotAfterAccountChange(loadedSlot, account, RaKeyState.REJECTED_THIS_SESSION))
        // A sign-out, another account, or a key that is no longer rejected: nothing of the old answer survives.
        assertNull(retroAchievementsSlotAfterAccountChange(rejectedSlot, null, RaKeyState.NEVER_CHECKED))
        assertNull(retroAchievementsSlotAfterAccountChange(rejectedSlot, "someone-else", RaKeyState.REJECTED_THIS_SESSION))
        assertNull(retroAchievementsSlotAfterAccountChange(rejectedSlot, account, RaKeyState.ACCEPTED))
        assertNull(retroAchievementsSlotAfterAccountChange(null, account, RaKeyState.REJECTED_THIS_SESSION))
    }
}
