package com.gruning.mimir.rewrite.core.model.jobs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The projection converts producer progress to the shared job model: producers report [JobProgress], and this is where it
 * becomes the one job model every surface reads.
 *
 * The pin that matters most is the last one. The UI owns the job surface's wording,
 * so this file must not contain a player-facing string — and "must not" is only worth anything if
 * something checks.
 */
class JobQueueProjectionTest {

    /** Marks every string with its source, so any string the projection invented would stand out. */
    private val markerCopy = JobCopy { JobCopyText(title = "TITLE:${it.kind}", subtitle = "SUB:${it.seen}") }

    @Test
    fun `a running job becomes one entry carrying its fraction`() {
        val entries = JobQueueProjection.project(
            listOf(JobProgress(JobKind.SCAN, seen = 1005, total = 2010)),
            markerCopy,
        )

        val entry = entries.single()
        assertEquals(JobKind.SCAN, entry.kind)
        val state = entry.state as FileQueueState.Running
        assertEquals(0.5f, checkNotNull(state.fraction), 0.0001f)
    }

    @Test
    fun `a job with no denominator becomes a Running with no bar, not a stalled one`() {
        val entries = JobQueueProjection.project(
            // The walk's state, and a scrape's first instant.
            listOf(JobProgress(JobKind.SCAN, seen = 1204, total = null)),
            markerCopy,
        )

        val state = entries.single().state as FileQueueState.Running
        assertNull("the null carries through untouched -- no bar, never stalled", state.fraction)
    }

    @Test
    fun `order is preserved so rows do not reshuffle as counts tick`() {
        val entries = JobQueueProjection.project(
            listOf(
                JobProgress(JobKind.SCAN, seen = 412, total = 2010),
                JobProgress(JobKind.SCRAPE, seen = 88, total = 240),
            ),
            markerCopy,
        )

        assertEquals(listOf(JobKind.SCAN, JobKind.SCRAPE), entries.map { it.kind })
    }

    @Test
    fun `a job's id is stable across ticks, so its card is never duplicated or reordered`() {
        val first = JobQueueProjection.project(listOf(JobProgress(JobKind.SCRAPE, 10, 240)), markerCopy)
        val later = JobQueueProjection.project(listOf(JobProgress(JobKind.SCRAPE, 200, 240)), markerCopy)

        assertEquals("the same job must keep the same id as it advances", first.single().id, later.single().id)
    }

    @Test
    fun `nothing running projects to nothing, not to an empty row`() {
        assertTrue(JobQueueProjection.project(emptyList(), markerCopy).isEmpty())
    }

    @Test
    fun `every string in the output came from the copy provider`() {
        // Copy delegation: if a later change hardcodes "Scanning" here, this fails -- which is the
        // only way a rule about who owns the words survives contact with a hurry.
        val entries = JobQueueProjection.project(
            listOf(
                JobProgress(JobKind.SCAN, seen = 412, total = 2010),
                JobProgress(JobKind.ENRICH, seen = 12, total = 40),
            ),
            markerCopy,
        )

        entries.forEach { entry ->
            assertTrue(
                "title must come from the provider, not from the projection: ${entry.title}",
                entry.title.startsWith("TITLE:"),
            )
            assertTrue(
                "subtitle must come from the provider, not from the projection: ${entry.subtitle}",
                entry.subtitle.startsWith("SUB:"),
            )
        }
    }
}
