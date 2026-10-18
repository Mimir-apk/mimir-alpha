package com.gruning.mimir.rewrite.feature.companion

import com.gruning.mimir.rewrite.core.model.CompanionJobProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The job line's copy, pinned against the LAW each string is derived from rather than against taste.
 *
 * The UI owns the final wording, which still needs review. These tests are not there to freeze
 * the words — they are there so that changing them cannot silently break the rules in standing §5b and
 * §4, which is a different thing.
 */
class CompanionJobSentenceTest {

    private fun job(kind: String, seen: Int, total: Int? = null) =
        CompanionJobProgress(kind = kind, seen = seen, total = total)

    @Test
    fun `a known denominator reads as what is true`() {
        // §5b: titled by what is true. The sentence IS the state.
        assertEquals("Scanning · 412 of 2,010", companionJobSentence(job("SCAN", 412, 2010)))
        assertEquals("Finding art · 88 of 240", companionJobSentence(job("SCRAPE", 88, 240)))
        assertEquals("Identifying · 12 of 40", companionJobSentence(job("ENRICH", 12, 40)))
    }

    @Test
    fun `no denominator states the count, never a zero`() {
        // §5b: absence is stated, not filled. A walk has no total until it finishes, so the sentence
        // says what it knows. "0%" would be a claim the job has not earned.
        val walking = companionJobSentence(job("SCAN", 1204, null))

        assertEquals("Scanning · 1,204 files found", walking)
        assertFalse("a null total must never render as a percentage", walking.contains("%"))
        assertFalse("nor as a zero", walking.contains("0 of"))
    }

    @Test
    fun `a zero total is treated as no denominator, not as a division`() {
        assertEquals("Scanning · 0 files found", companionJobSentence(job("SCAN", 0, 0)))
    }

    @Test
    fun `a four-digit quantity is grouped, because Mimir already speaks it that way`() {
        // §4's dialect law: a quantity Mimir already speaks must be spoken identically everywhere, and
        // MimirAchievementCard already renders a grouped "N of M" (`"%,d of %,d points"`). Standing §5b
        // (2026-08-31) settles the visible form; the mechanism is the builder's.
        assertEquals("Scanning · 1,204 of 12,000", companionJobSentence(job("SCAN", 1204, 12000)))
        assertEquals("Scanning · 1,204 files found", companionJobSentence(job("SCAN", 1204, null)))
        // Below four digits nothing changes -- the grouping is not a restyle of every number.
        assertEquals("Identifying · 12 of 40", companionJobSentence(job("ENRICH", 12, 40)))
        assertEquals("Working · 999 of 999", companionJobSentence(job("WHATEVER", 999, 999)))
    }

    @Test
    fun `a countless job says what IT counted, not what a scan counts`() {
        // A regression in the job sentence appended the same noun to every kind: each appended "files found",
        // so a scrape reported finding art and counting files. Wrong, not merely clumsy — and it
        // shipped because an earlier review checked the SCAN case, where a walk genuinely does
        // find files, and let four other kinds inherit a noun true for one of them.
        assertEquals("Scanning · 1,204 files found", companionJobSentence(job("SCAN", 1204, null)))
        // The others were handed their work; they have CHECKED it, not discovered it.
        assertEquals("Finding art · 1,204 checked", companionJobSentence(job("SCRAPE", 1204, null)))
        assertEquals("Identifying · 88 checked", companionJobSentence(job("ENRICH", 88, null)))
        assertEquals("Checking for duplicates · 40 checked", companionJobSentence(job("DEDUPE", 40, null)))
        // An unknown kind cannot claim either verb.
        assertEquals("Working · 7 so far", companionJobSentence(job("SOMETHING_NEW", 7, null)))
    }

    @Test
    fun `no sentence names a product noun`() {
        // §5b: a destination needs a noun, a state needs a sentence. Nothing here is a place the player
        // can navigate to, and naming it would make it one. This is the rule most likely to be broken
        // by a well-meaning edit, so it is checked rather than trusted.
        val nouns = listOf("Jobs", "Activity", "Queue", "Tasks", "Progress", "Pod", "Log")
        listOf("SCAN", "SCRAPE", "ENRICH", "DEDUPE", "SOMETHING_NEW").forEach { kind ->
            val sentence = companionJobSentence(job(kind, 1, 2))
            nouns.forEach { noun ->
                assertFalse("$kind must not be titled with the product noun \"$noun\": $sentence",
                    sentence.contains(noun))
            }
        }
    }

    @Test
    fun `an unknown kind still says something true rather than nothing`() {
        // A family added later must not render a blank line or a raw enum name at the player.
        val sentence = companionJobSentence(job("SOMETHING_NEW", 3, 9))
        assertEquals("Working · 3 of 9", sentence)
        assertFalse(sentence.contains("SOMETHING_NEW"))
    }
}
