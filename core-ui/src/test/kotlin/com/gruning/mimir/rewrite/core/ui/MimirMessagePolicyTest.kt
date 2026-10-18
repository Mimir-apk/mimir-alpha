package com.gruning.mimir.rewrite.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MimirMessagePolicyTest {

    @Test
    fun everyApprovedClassHasDistinctRuneAndOnlyFailuresRequestFeedback() {
        val classes = MimirMessageClass.values().toList()
        val messages = classes.mapIndexed { index, messageClass ->
            MimirMessage(
                eventId = index.toLong(),
                text = "Message $index",
                messageClass = messageClass,
            )
        }

        assertEquals(
            listOf(
                MimirMessageClass.Guidance,
                MimirMessageClass.Confirmation,
                MimirMessageClass.Warning,
                MimirMessageClass.Blocking,
            ),
            classes,
        )
        assertEquals(4, messages.map { it.runicGlyph }.toSet().size)
        assertFalse(MimirMessageClass.Guidance.requiresFailureFeedback)
        assertFalse(MimirMessageClass.Confirmation.requiresFailureFeedback)
        assertTrue(MimirMessageClass.Warning.requiresFailureFeedback)
        assertTrue(MimirMessageClass.Blocking.requiresFailureFeedback)
    }

    @Test
    fun semanticDuplicateCoalescesWithoutExtendingWindowAndDistinctLatestWins() {
        val deduplicator = MimirMessageDeduplicator()
        val confirmation = MimirMessage(
            eventId = 1L,
            text = "Saved",
            messageClass = MimirMessageClass.Confirmation,
        )
        val first = deduplicator.offer(confirmation, nowMs = 100L)

        val duplicate = deduplicator.offer(confirmation.copy(eventId = 2L), nowMs = 1_000L)
        assertFalse(duplicate.accepted)
        assertEquals(first.expiresAtMs, duplicate.expiresAtMs)
        assertEquals(confirmation, duplicate.message)

        val warning = confirmation.copy(
            eventId = 3L,
            messageClass = MimirMessageClass.Warning,
        )
        val replacement = deduplicator.offer(warning, nowMs = 1_100L)
        assertTrue(replacement.accepted)
        assertEquals(warning, replacement.message)
        assertEquals(3_500L, replacement.expiresAtMs)
        assertTrue(deduplicator.isCurrent(replacement))
    }

    @Test
    fun supportingLineParticipatesInIdentityAndAccessibilityCopy() {
        val deduplicator = MimirMessageDeduplicator()
        val first = MimirMessage(
            eventId = 1L,
            text = "SD card removed",
            messageClass = MimirMessageClass.Warning,
            supportingText = "2 games unavailable",
        )
        val changedDetail = first.copy(
            eventId = 2L,
            supportingText = "3 games unavailable",
        )

        assertTrue(deduplicator.offer(first, nowMs = 100L).accepted)
        assertTrue(deduplicator.offer(changedDetail, nowMs = 200L).accepted)
        assertEquals(
            "Warning. SD card removed. 3 games unavailable",
            changedDetail.accessibilityText,
        )
    }
    @Test
    fun aMessageThatAsksForALongerHoldKeepsItAndOneThatDoesNotKeepsTheWindow() {
        val deduplicator = MimirMessageDeduplicator()
        val plain = MimirMessage(eventId = 1L, text = "Saved")
        val held = MimirMessage(eventId = 2L, text = "Finding music…", messageClass = MimirMessageClass.Guidance, holdMs = 4_000L)

        assertEquals("no hold asked: the rail's 2.4 s window", 2_500L, deduplicator.offer(plain, nowMs = 100L).expiresAtMs)
        assertEquals("hold asked: from the moment it is offered", 5_000L, deduplicator.offer(held, nowMs = 1_000L).expiresAtMs)
        assertTrue("and it is still up at 4.9 s, past the old window", deduplicator.current(nowMs = 4_900L) != null)
        assertEquals("and gone at its expiry", null, deduplicator.current(nowMs = 5_000L))
    }

    @Test
    fun aResultReplacesTheProgressNoteInPlaceAndStartsItsOwnHold() {
        val deduplicator = MimirMessageDeduplicator()
        val finding = MimirMessage(eventId = 1L, text = "Finding music for Chrono Trigger…", messageClass = MimirMessageClass.Guidance, holdMs = 4_000L)
        val saved = MimirMessage(eventId = 2L, text = "Music saved for Chrono Trigger.", messageClass = MimirMessageClass.Confirmation, holdMs = 6_000L)

        val first = deduplicator.offer(finding, nowMs = 0L)
        val result = deduplicator.offer(saved, nowMs = 1_500L)

        assertTrue("a distinct message is never swallowed by the note it follows", result.accepted)
        assertEquals(saved, result.message)
        assertEquals("the result's own hold counts from its arrival, not from the note's", 7_500L, result.expiresAtMs)
        assertFalse("the earlier note's timer no longer owns the rail", deduplicator.isCurrent(first))
        assertTrue(deduplicator.isCurrent(result))
    }

    @Test
    fun aDuplicateOfAHeldMessageStillDoesNotExtendIt() {
        val deduplicator = MimirMessageDeduplicator()
        val refusal = MimirMessage(eventId = 1L, text = "Connect ScreenScraper to download music.", messageClass = MimirMessageClass.Warning, holdMs = 7_000L)
        val first = deduplicator.offer(refusal, nowMs = 0L)
        val again = deduplicator.offer(refusal.copy(eventId = 2L), nowMs = 3_000L)
        assertFalse(again.accepted)
        assertEquals(first.expiresAtMs, again.expiresAtMs)
    }

    @Test
    fun aHoldIsNullOrPositive() {
        assertEquals(null, MimirMessage(eventId = 1L, text = "Saved").holdMs)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { MimirMessage(eventId = 1L, text = "Saved", holdMs = 0L) }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { MimirMessage(eventId = 1L, text = "Saved", holdMs = -5L) }
    }
}
