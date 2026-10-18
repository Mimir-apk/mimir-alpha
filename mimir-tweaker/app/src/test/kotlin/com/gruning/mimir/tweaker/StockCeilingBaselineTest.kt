package com.gruning.mimir.tweaker

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Thor device trace, 2026-09-19: Quiet applied, the daemon was frozen for two hours, the session was
 * gone when it thawed and the hardware was still clamped. The re-arm captured the clamp as stock,
 * and every later "Stock" restored the clamp -- `requested=stock prior=quiet result=Stock` at
 * 22:02:30, and twelve seconds later `requested=balanced prior=stock` refused
 * `CPU_TARGET_ABOVE_LIVE_CEILING` against `live window 1017600..1017600`. Read back from the device
 * fifty minutes later, the shared QoS node still held `0:1017600 ... 7:1843200` against a stock
 * `cpuinfo_max_freq` of 2016000/2803200/3187200.
 *
 * These pin the rule that closes it: **the first capture in a boot defines stock, and no later
 * capture may redefine it.**
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class StockCeilingBaselineTest {

    private val quiet = mapOf(
        "cpu:0:scaling_max_freq" to 1017600,
        "cpu:3:scaling_max_freq" to 1651200,
        "cpu:7:scaling_max_freq" to 1843200,
    )
    private val stock = mapOf(
        "cpu:0:scaling_max_freq" to 2147483647,
        "cpu:3:scaling_max_freq" to 2147483647,
        "cpu:7:scaling_max_freq" to 2147483647,
    )
    private val deviceCeilings = mapOf(
        "cpu:0:scaling_max_freq" to 2016000,
        "cpu:3:scaling_max_freq" to 2803200,
        "cpu:7:scaling_max_freq" to 3187200,
    )

    @Test
    fun theFirstCaptureOfABootDefinesStock() {
        val first = StockCeilingBaselinePolicy.merge(null, "boot-a", stock)

        assertEquals(stock, first.values)
        assertEquals("boot-a", first.bootIdentity)
    }

    /** The strand itself: a re-arm observing Quiet's clamp must not be allowed to call it stock. */
    @Test
    fun aLaterCaptureInTheSameBootCannotRedefineStockAsTheLiveClamp() {
        val first = StockCeilingBaselinePolicy.merge(null, "boot-a", stock)

        val rearm = StockCeilingBaselinePolicy.merge(first, "boot-a", quiet)

        assertEquals(stock, rearm.values)
    }

    /** A reboot is the one thing that legitimately retires a baseline -- the kernel reinitialises it. */
    @Test
    fun aNewBootDiscardsTheOldBaselineRatherThanMergingWithIt() {
        val previous = StockCeilingBaselinePolicy.merge(null, "boot-a", stock)

        val next = StockCeilingBaselinePolicy.merge(previous, "boot-b", quiet)

        assertEquals(quiet, next.values)
        assertEquals("boot-b", next.bootIdentity)
    }

    /**
     * First-write-wins is per logical ID, not per record: a boot whose first transaction tuned only
     * the CPU still has to be able to learn the GPU's stock value when one first touches it.
     */
    @Test
    fun anAxisNotYetSeenThisBootIsStillLearned() {
        val cpuOnly = StockCeilingBaselinePolicy.merge(null, "boot-a", stock)

        val withGpu = StockCeilingBaselinePolicy.merge(
            cpuOnly,
            "boot-a",
            quiet + mapOf(HardwareSnapshot.GPU_MAX_LOGICAL_ID to 4),
        )

        assertEquals(4, withGpu.values[HardwareSnapshot.GPU_MAX_LOGICAL_ID])
        assertEquals(2147483647, withGpu.values["cpu:0:scaling_max_freq"])
    }

    /**
     * The case no baseline can repair: the first capture of the boot already found the hardware
     * clamped, so the remembered "stock" is itself below the device's own maximum. Nothing on the
     * device still holds the true value -- only a reboot does -- so this has to be legible in the
     * log rather than silently becoming the restore point.
     */
    @Test
    fun aBaselineBelowTheDeviceCeilingIsNamedRatherThanTrusted() {
        val poisoned = StockCeilingBaseline.poisonedNodes(quiet, deviceCeilings)

        assertEquals(
            listOf(
                "cpu:0:scaling_max_freq 1017600<2016000",
                "cpu:3:scaling_max_freq 1651200<2803200",
                "cpu:7:scaling_max_freq 1843200<3187200",
            ),
            poisoned,
        )
        assertTrue(StockCeilingBaseline.poisonedNodes(stock, deviceCeilings).isEmpty())
    }

    /** FAN is a persistent `settings` value whose restore point already survives a reboot. */
    @Test
    fun onlyTheVolatileAxesAreGoverned() {
        assertTrue(StockCeilingBaseline.governs(TuningAxis.CPU))
        assertTrue(StockCeilingBaseline.governs(TuningAxis.GPU))
        assertTrue(!StockCeilingBaseline.governs(TuningAxis.FAN))
    }

    @Test
    fun aRecordSurvivesEncodingUnchanged() {
        val record = StockCeilingBaselineRecord("boot-a", stock)

        val round = StockCeilingBaselineCodec.decode(StockCeilingBaselineCodec.encode(record))

        assertEquals(record, round)
    }

    @Test
    fun aCorruptOrForeignRecordDecodesToNothingRatherThanGuessing() {
        assertNull(StockCeilingBaselineCodec.decode(null))
        assertNull(StockCeilingBaselineCodec.decode(ByteArray(0)))
        assertNull(StockCeilingBaselineCodec.decode("{\"schema\":99}".toByteArray()))
        assertNull(StockCeilingBaselineCodec.decode("not json".toByteArray()))
    }

    /** The rule has to hold across processes, which means across a write and a fresh read. */
    @Test
    fun theBaselineSurvivesARestartOfTheProcessThatWroteIt() {
        val directory = Files.createTempDirectory("stock-baseline").toFile()
        try {
            StockCeilingBaselineStore(directory).remember("boot-a", stock)

            val afterRestart = StockCeilingBaselineStore(directory).remember("boot-a", quiet)

            assertEquals(stock, afterRestart.values)
        } finally {
            directory.deleteRecursively()
        }
    }

    /**
     * A store that cannot persist still has to answer this transaction correctly. Degrading to
     * today's behaviour for the NEXT capture is acceptable; failing the capture in flight is not.
     */
    @Test
    fun anUnwritableStoreStillAnswersThisCapture() {
        val unwritable = File(
            Files.createTempDirectory("stock-baseline-blocked").toFile(),
            "occupied",
        )
        try {
            unwritable.writeText("a file where the directory should be")

            val answered = StockCeilingBaselineStore(unwritable).remember("boot-a", quiet)

            assertEquals(quiet, answered.values)
        } finally {
            unwritable.parentFile.deleteRecursively()
        }
    }
}
