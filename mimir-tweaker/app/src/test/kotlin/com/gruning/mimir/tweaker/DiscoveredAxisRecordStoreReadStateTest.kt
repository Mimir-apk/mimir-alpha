package com.gruning.mimir.tweaker

import android.util.AtomicFile
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * S3 closing review #3: [DiscoveredAxisRecordStore.read] answered null for a missing file, a file that could not be
 * read, a record that did not decode and one that no longer passed admission. The ceiling's withdraw took that null for
 * "nothing held" and cleared the file, so a transient read failure destroyed the only record of the way back from a cap.
 * [DiscoveredAxisRecordStore.readState] keeps the cases apart; [DiscoveredAxisRecordStore.read] is unchanged for the v9 memo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class DiscoveredAxisRecordStoreReadStateTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val discovery = object : DiscoveredAxisAdmission {
        override fun cpuFrequenciesKhz(): Map<Int, Set<Int>> =
            (0..2).associateWith { setOf(1_017_600, 2_016_000) }

        override fun gpuPowerLevelCount(): Int = 4
    }

    private val record = DiscoveredAxisRecord(
        bootIdentity = "boot-1",
        cpuStockRow = "0:2016000 1:2016000 2:2016000",
        cpuRequestedRow = "0:1017600 1:1017600 2:1017600",
    )

    private fun store(name: String = "ceiling.json") = DiscoveredAxisRecordStore(
        AtomicFile(File(folder.root, name)),
        discovery,
    )

    private fun writeRaw(name: String, text: String) = File(folder.root, name).writeText(text)

    @Test
    fun `no file is Absent, and read still answers null`() {
        val subject = store()

        assertEquals(DiscoveredAxisRecordRead.Absent, subject.readState())
        assertNull(subject.read())
    }

    @Test
    fun `a written record is Present`() {
        val subject = store()
        subject.write(record)

        assertEquals(DiscoveredAxisRecordRead.Present(record), subject.readState())
        assertEquals(record, subject.read())
    }

    // Two files, never an overwrite: the JVM's AtomicFile cannot rename over an existing file on Windows.
    @Test
    fun `the pending-delete marker survives the file, and an ordinary record never writes it`() {
        val marked = store("marked.json")
        marked.write(record.copy(pendingDelete = true))
        assertEquals(DiscoveredAxisRecordRead.Present(record.copy(pendingDelete = true)), marked.readState())

        val ordinary = store("ordinary.json")
        ordinary.write(record)
        assertEquals(false, (ordinary.readState() as DiscoveredAxisRecordRead.Present).record.pendingDelete)
        assertTrue("an ordinary record keeps its earlier shape", !DiscoveredAxisRecordStore.encode(record).contains("pendingDelete"))
    }

    private fun rawRecord(pendingDelete: String?) =
        """{"schema":1,"bootIdentity":"boot-1","cpuStockRow":"0:2016000","cpuRequestedRow":"0:1017600"""" +
            (pendingDelete?.let { ""","pendingDelete":$it""" } ?: "") + "}"

    // S9 round 3: optBoolean coerced "true" to true and null / 1 / "yes" to false, and a false silently resumes a record that
    // was already restored as a live ceiling. A MISSING marker stays compatible; a PRESENT malformed one makes the record Unreadable.
    @Test
    fun `a record with no pendingDelete field is compatible and not pending delete, including a legacy empty record`() {
        writeRaw("ceiling.json", rawRecord(null))
        val read = store().readState() as DiscoveredAxisRecordRead.Present
        assertEquals(false, read.record.pendingDelete)

        writeRaw("legacy.json", """{"schema":1,"bootIdentity":"boot-1","cpuStockRow":"","cpuRequestedRow":""}""")
        val legacy = store("legacy.json").readState() as DiscoveredAxisRecordRead.Present
        assertEquals(false, legacy.record.pendingDelete)
    }

    @Test
    fun `a JSON boolean pendingDelete is read as written`() {
        writeRaw("on.json", rawRecord("true"))
        writeRaw("off.json", rawRecord("false"))
        assertEquals(true, (store("on.json").readState() as DiscoveredAxisRecordRead.Present).record.pendingDelete)
        assertEquals(false, (store("off.json").readState() as DiscoveredAxisRecordRead.Present).record.pendingDelete)
    }

    @Test
    fun `a pendingDelete that is present but not a JSON boolean makes the whole record Unreadable`() {
        listOf("null", "1", "0", "\"true\"", "\"false\"", "\"yes\"", "[]", "{}").forEachIndexed { index, bad ->
            val name = "bad$index.json"
            writeRaw(name, rawRecord(bad))
            val subject = store(name)
            val state = subject.readState()
            assertTrue("pendingDelete=$bad must be Unreadable, was $state", state is DiscoveredAxisRecordRead.Unreadable)
            assertNull("read() keeps its v9 answer for pendingDelete=$bad", subject.read())
        }
    }

    @Test
    fun `a file that does not decode is Unreadable, not Absent`() {
        writeRaw("ceiling.json", "{ this is not json")
        val subject = store()

        val state = subject.readState()

        assertTrue(state.toString(), state is DiscoveredAxisRecordRead.Unreadable)
        assertNull("read() keeps its v9 answer", subject.read())
    }

    @Test
    fun `a record of a schema this build does not read is Unreadable`() {
        writeRaw("ceiling.json", """{"schema":2,"bootIdentity":"boot-1","cpuStockRow":"","cpuRequestedRow":""}""")

        assertTrue(store().readState() is DiscoveredAxisRecordRead.Unreadable)
    }

    @Test
    fun `a record that no longer passes admission is Unreadable, not Absent`() {
        val subject = store()
        subject.write(record)
        // The same file, read by a process that now discovers fewer CPUs (a transient probe failure, a different chassis).
        val narrower = DiscoveredAxisRecordStore(
            AtomicFile(File(folder.root, "ceiling.json")),
            object : DiscoveredAxisAdmission {
                override fun cpuFrequenciesKhz(): Map<Int, Set<Int>> = emptyMap()
                override fun gpuPowerLevelCount(): Int = 0
            },
        )

        val state = narrower.readState()

        assertTrue(state.toString(), state is DiscoveredAxisRecordRead.Unreadable)
        assertTrue((state as DiscoveredAxisRecordRead.Unreadable).detail.contains("admission"))
    }

    @Test
    fun `an admission that throws is Unreadable`() {
        val subject = store()
        subject.write(record)
        val throwing = DiscoveredAxisRecordStore(
            AtomicFile(File(folder.root, "ceiling.json")),
            object : DiscoveredAxisAdmission {
                override fun cpuFrequenciesKhz(): Map<Int, Set<Int>> = error("probe failed")
                override fun gpuPowerLevelCount(): Int = 0
            },
        )

        assertTrue(throwing.readState() is DiscoveredAxisRecordRead.Unreadable)
    }

    @Test
    fun `a path that exists but cannot be read as a file is Unreadable, not Absent`() {
        // A directory where the file should be: it exists, and reading it fails.
        File(folder.root, "ceiling.json").mkdir()

        assertTrue(store().readState() is DiscoveredAxisRecordRead.Unreadable)
    }

    @Test
    fun `the ceiling adapter hands the actuator the tri-state, and clear still removes the file`() {
        val subject = DiscoveredAxisCeilingRecordStore(store())
        assertEquals(DiscoveredAxisRecordRead.Absent, subject.read())
        subject.write(record)
        assertEquals(DiscoveredAxisRecordRead.Present(record), subject.read())
        assertTrue(subject.clear())
        assertEquals(DiscoveredAxisRecordRead.Absent, subject.read())
    }

    /**
     * S9 "Failed record deletion": clear() used to answer true whatever happened, so a delete that did not happen
     * looked like one and the withdraw reported Withdrawn beside a record that still said "held".
     */
    @Test
    fun `clear says false when the record is still there afterwards`() {
        // A non-empty directory where the file should be: File.delete() cannot remove it, and it still exists.
        val base = File(folder.root, "ceiling.json")
        base.mkdir()
        File(base, "child").writeText("x")

        assertEquals(false, store().clear())
        assertTrue("the record's path is still there", base.exists())
    }

    @Test
    fun `clear says true for a record that is gone, and for one that never existed`() {
        val subject = store()
        subject.write(record)
        assertTrue(subject.clear())
        assertTrue("clearing nothing is still a delete that held", subject.clear())
    }
}
