package com.gruning.mimir.tweaker

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuUtilizationTelemetryTest {
    @Test
    fun `preferred direct percentage wins and stops lower priority reads`() {
        val paths = mutableListOf<String>()
        val result = GpuUtilizationTelemetry.read(readNode = { path ->
            paths += path
            when (path) {
                GpuUtilizationTelemetry.BUSY_PERCENTAGE -> "4 %"
                else -> error("lower-priority GPU source must not be read")
            }
        })

        assertEquals(4, result)
        assertEquals(listOf(GpuUtilizationTelemetry.BUSY_PERCENTAGE), paths)
    }

    @Test
    fun `malformed preferred source falls back to averaged load then strict interval`() {
        val paths = mutableListOf<String>()
        val values = mapOf(
            GpuUtilizationTelemetry.BUSY_PERCENTAGE to "bad",
            GpuUtilizationTelemetry.LOAD to "61%",
            GpuUtilizationTelemetry.BUSY_INTERVAL to "9 10",
        )

        assertEquals(61, GpuUtilizationTelemetry.read(readNode = { path ->
            paths += path
            values[path]
        }))
        assertEquals(
            listOf(GpuUtilizationTelemetry.BUSY_PERCENTAGE, GpuUtilizationTelemetry.LOAD),
            paths,
        )
        assertEquals(90, GpuUtilizationTelemetry.parseBusyInterval("9 10"))

        paths.clear()
        assertEquals(
            38,
            GpuUtilizationTelemetry.read(readNode = { path ->
                paths += path
                mapOf(
                    GpuUtilizationTelemetry.BUSY_PERCENTAGE to "bad",
                    GpuUtilizationTelemetry.LOAD to "bad",
                    GpuUtilizationTelemetry.BUSY_INTERVAL to "3 8",
                )[path]
            }),
        )
        assertEquals(
            listOf(
                GpuUtilizationTelemetry.BUSY_PERCENTAGE,
                GpuUtilizationTelemetry.LOAD,
                GpuUtilizationTelemetry.BUSY_INTERVAL,
            ),
            paths,
        )
    }

    @Test
    fun `interval parser rejects invalid bounds and handles large nonoverflowing values`() {
        assertNull(GpuUtilizationTelemetry.parsePercent("101"))
        assertNull(GpuUtilizationTelemetry.parsePercent("-1%"))
        assertNull(GpuUtilizationTelemetry.parseBusyInterval("0 0"))
        assertNull(GpuUtilizationTelemetry.parseBusyInterval("9 8"))
        assertNull(GpuUtilizationTelemetry.parseBusyInterval("-1 8"))
        assertEquals(
            100,
            GpuUtilizationTelemetry.parseBusyInterval("922337203685477580 922337203685477581"),
        )
    }

    @Test
    fun `hardware probe pins production wiring to the shared ordered parser`() {
        val candidates = listOf(
            File("src/main/kotlin/com/gruning/mimir/tweaker/AndroidHardware.kt"),
            File("mimir-tweaker/app/src/main/kotlin/com/gruning/mimir/tweaker/AndroidHardware.kt"),
            File("../mimir-tweaker/app/src/main/kotlin/com/gruning/mimir/tweaker/AndroidHardware.kt"),
        )
        val source = candidates.firstOrNull(File::exists)?.readText()
            ?: error("AndroidHardware.kt source not found")

        assertTrue(source.contains("GpuUtilizationReader("))
        assertTrue(source.contains("BoundedRead(reader::readPrivileged)"))
        assertTrue(source.contains("helperPresent = reader::helperPresent"))
        assertTrue(source.contains("gpuSourceInitialized"))
        // The edge is process-wide: a per-probe edge logged once per service creation (22 lines in 30 s on the Nova).
        assertTrue(source.substringAfter("companion object {").contains("var gpuSourceInitialized"))
        assertTrue(source.contains("Process.myUid()"))
    }
}
