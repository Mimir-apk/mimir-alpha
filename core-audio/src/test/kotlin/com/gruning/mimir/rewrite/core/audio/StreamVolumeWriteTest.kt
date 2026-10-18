package com.gruning.mimir.rewrite.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Dragging a volume slider reports every pointer event; the media stream is written only when its step changes (Thor 10-10, ~8 ms identical writes). */
class StreamVolumeWriteTest {
    @Test
    fun `a write that would change nothing is skipped, a different step is written, an unreadable stream is written as before`() {
        assertFalse("same step", StreamVolumeWrite.shouldWrite(current = 7, target = 7))
        assertTrue("one step up", StreamVolumeWrite.shouldWrite(current = 7, target = 8))
        assertTrue("one step down", StreamVolumeWrite.shouldWrite(current = 7, target = 6))
        assertTrue("to zero", StreamVolumeWrite.shouldWrite(current = 1, target = 0))
        assertFalse("already at zero", StreamVolumeWrite.shouldWrite(current = 0, target = 0))
        assertTrue("could not read the stream", StreamVolumeWrite.shouldWrite(current = null, target = 7))
    }

    @Test
    fun `a drag across a hundred pointer events on fifteen steps writes the stream at most fifteen times`() {
        var stream = 0
        var writes = 0
        for (event in 0..100) {
            val target = (event / 100f * 15).let { Math.round(it) }
            if (StreamVolumeWrite.shouldWrite(stream, target)) {
                stream = target
                writes += 1
            }
        }
        assertEquals(15, writes)
        assertEquals(15, stream)
    }

    @Test
    fun `the control writes the stream through the check, then still re-reads it`() {
        val control = File("src/main/kotlin/com/gruning/mimir/rewrite/core/audio/MimirMusicControl.kt").readText()
        val apply = control.substringAfter("private val applyMediaVolume = Runnable {").substringBefore("/**\n     * Tells the control whether a game")
        assertTrue(apply.contains("if (StreamVolumeWrite.shouldWrite(manager.getStreamVolume(AudioManager.STREAM_MUSIC), target))"))
        assertEquals("one write site", 1, Regex("""setStreamVolume\(""").findAll(apply).count())
        assertTrue("the volume is read back whether or not it was written", apply.indexOf("readMediaVolume()") > apply.indexOf("setStreamVolume("))
    }
}
