package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Dragging the Bottom Utility volume slider reports every pointer event (about every 8 ms on the Thor, 10-10). The media stream is written only
 * when the step changes, so a drag does not flood AudioService and the vendor's volume listener with identical writes.
 */
class MediaVolumeWriteOnlyOnChangeTest {
    @Test
    fun `the Utility media volume writes the stream only when its step differs, and still reports a readable stream`() {
        val file = File(
            listOf(
                "app/src/main/kotlin/com/gruning/mimir/rewrite/AndroidUtilityControls.kt",
                "src/main/kotlin/com/gruning/mimir/rewrite/AndroidUtilityControls.kt",
            ).first { File(it).isFile },
        )
        val media = file.readText().substringAfter("class AndroidMediaVolume(").substringBefore("override fun step(")
        val write = media.substringAfter("override fun writeFraction(fraction: Float): Boolean {")
        assertTrue(write.contains("if (StreamVolumeWrite.shouldWrite(manager.getStreamVolume(AudioManager.STREAM_MUSIC), level))"))
        assertEquals("one write site", 1, Regex("""setStreamVolume\(""").findAll(write).count())
        assertTrue("the result is still whether the stream reads", write.indexOf("readFraction() != null") > write.indexOf("setStreamVolume("))
    }
}
