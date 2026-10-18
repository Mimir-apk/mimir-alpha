package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The presentation decision for the overlay plate.
 *
 * This exists because the branch it covers used to live inline in a private suspend function inside
 * `PerformanceOverlayService`, unreachable from any unit test — so the behaviour it encodes could
 * change with a full green suite and nothing would notice.
 */
class PerformanceOverlayPresentationKindTest {

    private fun kind(
        fresh: Boolean = true,
        samples: Int = 5,
        visibility: PerformanceOverlayVisibility = PerformanceOverlayVisibility.ALWAYS,
    ) = performanceOverlayPresentationKind(fresh, samples, visibility)

    @Test
    fun `always-on presents the first live sample instead of a warming plate`() {
        // The owner-visible defect: sample one carries CPU/GPU/battery and was withheld for a poll
        // interval to display "TELEMETRY WARMING".
        assertEquals(
            PerformanceOverlayPresentationKind.ACTIVE,
            kind(samples = 1, visibility = PerformanceOverlayVisibility.ALWAYS),
        )
    }

    @Test
    fun `games-only still warms on the first sample`() {
        // There the frame rate is the point, and it can be absent on sample one. MINIMAL and CLASSIC
        // would render a bare "—" with no warming wording, which reads as "no game in front".
        assertEquals(
            PerformanceOverlayPresentationKind.WARMING,
            kind(samples = 1, visibility = PerformanceOverlayVisibility.GAMES_ONLY),
        )
    }

    @Test
    fun `staleness outranks both, in every visibility`() {
        PerformanceOverlayVisibility.entries.forEach { visibility ->
            assertEquals(
                "stale telemetry must pause in $visibility",
                PerformanceOverlayPresentationKind.PAUSED,
                kind(fresh = false, samples = 1, visibility = visibility),
            )
            assertEquals(
                "stale telemetry must pause in $visibility",
                PerformanceOverlayPresentationKind.PAUSED,
                kind(fresh = false, samples = 99, visibility = visibility),
            )
        }
    }

    @Test
    fun `settled sampling is active regardless of visibility`() {
        PerformanceOverlayVisibility.entries.forEach { visibility ->
            assertEquals(
                PerformanceOverlayPresentationKind.ACTIVE,
                kind(samples = 2, visibility = visibility),
            )
        }
    }
}
