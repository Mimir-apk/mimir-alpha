package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mimir's own kernel read of GPU busy is denied on an enforcing build and audited on every call even where it succeeds
 * (Nova, before: 124 `avc: denied ... gpu_busy_percentage` in one walk, 33 from Mimir). It does not read while a Tweaker
 * can be reached, and where it is the only source it reads sparingly.
 */
class DirectGpuBusyReaderTest {

    private class Rig(
        var tweaker: Boolean = false,
        private val files: Map<String, String> = emptyMap(),
    ) {
        var now = 0L
        val reads = mutableListOf<String>()
        val reader = DirectGpuBusyReader(
            tweakerReachable = { tweaker },
            readNode = { path -> reads += path; files[path] },
            nowMs = { now },
        )
    }

    @Test
    fun `no node is touched while a Tweaker can be reached`() {
        val rig = Rig(tweaker = true, files = mapOf(DeviceGpuPaths.BUSY_PERCENTAGE to "40"))

        repeat(5) { assertNull(rig.reader.read().percent) }

        assertTrue("the Tweaker carries GPU busy: nothing to deny", rig.reads.isEmpty())
    }

    @Test
    fun `where no Tweaker can bind a value is read once and reused for five seconds`() {
        val rig = Rig(files = mapOf(DeviceGpuPaths.BUSY_PERCENTAGE to "40 %"))

        val first = rig.reader.read()
        assertEquals(40, first.percent)
        assertEquals(DeviceGpuPaths.BUSY_PERCENTAGE, first.source)
        repeat(4) {
            rig.now += 1_000L
            assertEquals(40, rig.reader.read().percent)
        }
        assertEquals(1, rig.reads.size)

        rig.now += 1_001L // 5.001 s since the read
        assertEquals(40, rig.reader.read().percent)
        assertEquals(2, rig.reads.size)
    }

    @Test
    fun `when nothing answers it is null, never zero, and not asked again for thirty seconds`() {
        val rig = Rig()

        assertNull(rig.reader.read().percent)
        val burst = rig.reads.size
        assertEquals("one pass over the three sources", 3, burst)

        repeat(10) {
            rig.now += 1_000L
            assertNull(rig.reader.read().percent)
        }
        assertEquals(burst, rig.reads.size)

        rig.now += DirectGpuBusyReader.QUIET_MS
        assertNull(rig.reader.read().percent)
        assertEquals(burst * 2, rig.reads.size)
    }

    @Test
    fun `the Tweaker coming back ends the direct read and it resumes if the Tweaker goes`() {
        val rig = Rig(files = mapOf(DeviceGpuPaths.BUSY_PERCENTAGE to "12"))
        assertEquals(12, rig.reader.read().percent)

        rig.tweaker = true
        rig.now += 6_000L
        assertNull(rig.reader.read().percent)
        assertEquals(1, rig.reads.size)

        rig.tweaker = false
        assertEquals(12, rig.reader.read().percent)
        assertEquals(2, rig.reads.size)
    }
}
