package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Every `fps:` string in this file is a VERBATIM capture from one of the two certified devices on
 * 2026-08-22, not a hand-written plausible value. The window arithmetic is the whole point of the
 * parser, and a parser pinned against invented numbers is pinned against nothing.
 *
 * Provenance:
 * - Odin `test device`, kernel `6.6.87-android15-8`, node `card0-sde-crtc-0`, 120 Hz panel,
 *   sampled at ~15 Hz through `/data/local/tmp/rc47_sampler.sh` during a d-pad burst in Mimir.
 * - Nova test unit, kernel `5.15.123-android13-8`, node `sde-crtc-0`, 120 Hz active mode, sampled
 *   around a notification-shade animation after a long static period.
 */
class DisplayFrameTelemetryTest {

    // ---- CRTC selection -----------------------------------------------------------------

    /** Verbatim `ls /sys/class/drm` on the Odin: eight CRTCs, all `card0-` prefixed. */
    private val odinDrmEntries = listOf(
        "card0", "card0-DP-1", "card0-DSI-1", "card0-Virtual-1", "card0-Virtual-2",
        "card0-sde-crtc-0", "card0-sde-crtc-1", "card0-sde-crtc-2", "card0-sde-crtc-3",
        "card0-sde-crtc-4", "card0-sde-crtc-5", "card0-sde-crtc-6", "card0-sde-crtc-7",
        "renderD128", "sde-conn-2-DSI-1", "version",
    )

    /** Verbatim `ls /sys/class/drm` on the Nova: six CRTCs, NO `card0-` prefix. */
    private val novaDrmEntries = listOf(
        "card0", "card0-DP-1", "card0-DSI-1", "card0-Virtual-1", "card0-Virtual-2",
        "renderD128", "sde-crtc-0", "sde-crtc-1", "sde-crtc-2", "sde-crtc-3", "sde-crtc-4",
        "sde-crtc-5", "version",
    )

    @Test
    fun `odin selects the card-prefixed panel crtc`() {
        assertEquals("card0-sde-crtc-0", DisplayFrameTelemetry.selectCrtcDirectory(odinDrmEntries))
    }

    @Test
    fun `nova selects the unprefixed panel crtc`() {
        assertEquals("sde-crtc-0", DisplayFrameTelemetry.selectCrtcDirectory(novaDrmEntries))
    }

    @Test
    fun `crtc index orders numerically, not lexicographically`() {
        // "sde-crtc-10" sorts before "sde-crtc-2" as text; CRTC 2 is the lower index.
        assertEquals(
            "sde-crtc-2",
            DisplayFrameTelemetry.selectCrtcDirectory(listOf("sde-crtc-10", "sde-crtc-2")),
        )
    }

    @Test
    fun `a tree with no sde crtc selects nothing`() {
        assertNull(
            DisplayFrameTelemetry.selectCrtcDirectory(listOf("card0", "renderD128", "version")),
        )
    }

    @Test
    fun `selection is deterministic when both naming schemes are present`() {
        val both = listOf("sde-crtc-0", "card0-sde-crtc-0")
        assertEquals(
            DisplayFrameTelemetry.selectCrtcDirectory(both),
            DisplayFrameTelemetry.selectCrtcDirectory(both.reversed()),
        )
    }

    // ---- Parsing ------------------------------------------------------------------------

    @Test
    fun `parses a steady odin read`() {
        val sample = DisplayFrameTelemetry.parse("fps: 120.7 duration:1000000 frame_count:121\n")
        assertEquals(1_207, sample?.fpsTenths)
        assertEquals(1_000_000L, sample?.configuredDurationUs)
        assertEquals(121, sample?.frameCount)
    }

    @Test
    fun `duration is the configured periodicity in microseconds, not the measured window`() {
        // 25 commits at a reported 16.8 fps is a 1.488 s window, while `duration:` still says 1 s.
        val sample = DisplayFrameTelemetry.parse("fps: 16.8 duration:1000000 frame_count:25")!!
        assertEquals(1_000_000L, sample.configuredDurationUs)
        assertEquals(1_488_095L, sample.measuredWindowUs)
    }

    @Test
    fun `parses the idle read`() {
        val sample = DisplayFrameTelemetry.parse("fps: 0.0 duration:1000000 frame_count:0")
        assertEquals(0, sample?.fpsTenths)
        assertEquals(0, sample?.frameCount)
        assertNull(sample?.measuredWindowUs)
    }

    @Test
    fun `the kernel's own failure strings do not parse`() {
        // Both are emitted verbatim by measured_fps_show() when the CRTC or its ring is missing.
        assertNull(DisplayFrameTelemetry.parse("fps information not available"))
        assertNull(DisplayFrameTelemetry.parse("timebuf null - fps information not available"))
        assertNull(DisplayFrameTelemetry.parse(""))
        assertNull(DisplayFrameTelemetry.parse(null))
    }
}
