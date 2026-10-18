package com.gruning.mimir.rewrite.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The format line switches when the track on air changes, not when the renderer reads the next track's format ahead. */
class FormatTransitionGateTest {
    @Test
    fun `a format read for the current track is current at once`() {
        val gate = FormatTransitionGate<String>()
        gate.onTransition("a")
        gate.onFormat("a", "FLAC a")
        assertEquals("FLAC a", gate.current)
    }

    @Test
    fun `a format read ahead for the next track waits for its transition`() {
        val gate = FormatTransitionGate<String>()
        gate.onTransition("a")
        gate.onFormat("a", "FLAC a")
        gate.onFormat("b", "MP3 b")
        assertEquals("the current track keeps its line while the next is only read ahead", "FLAC a", gate.current)
        gate.onTransition("b")
        assertEquals("MP3 b", gate.current)
    }

    @Test
    fun `a format that arrives before the transition event is held for it`() {
        val gate = FormatTransitionGate<String>()
        gate.onFormat("a", "FLAC a")
        assertNull(gate.current)
        gate.onTransition("a")
        assertEquals("FLAC a", gate.current)
    }

    @Test
    fun `a skip to a track nothing was read for shows no line until its format arrives`() {
        val gate = FormatTransitionGate<String>()
        gate.onTransition("a")
        gate.onFormat("a", "FLAC a")
        gate.onFormat("b", "MP3 b")
        gate.onTransition("c")
        assertNull(gate.current)
        gate.onFormat("c", "Opus c")
        assertEquals("Opus c", gate.current)
    }

    @Test
    fun `the same track again changes nothing and a reset forgets everything`() {
        val gate = FormatTransitionGate<String>()
        gate.onTransition("a")
        gate.onFormat("a", "FLAC a")
        gate.onTransition("a")
        assertEquals("FLAC a", gate.current)
        gate.onFormat("b", "MP3 b")
        gate.reset()
        gate.onTransition("b")
        assertNull(gate.current)
    }

    @Test
    fun `a format with no track id is the current one`() {
        val gate = FormatTransitionGate<String>()
        gate.onTransition("a")
        gate.onFormat(null, "AAC")
        assertEquals("AAC", gate.current)
    }
}
