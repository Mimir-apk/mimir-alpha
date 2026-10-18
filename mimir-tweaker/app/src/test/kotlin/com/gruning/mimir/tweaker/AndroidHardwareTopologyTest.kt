package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidHardwareTopologyTest {
    @Test
    fun `protected CPU topology read uses one allowlisted read-only command`() {
        val commands = mutableListOf<String>()
        val access = PServerProtectedNodeAccess(
            client(commands) { "300000 600000 900000" },
        )
        val path = "/sys/devices/system/cpu/cpufreq/policy0/scaling_available_frequencies"

        assertEquals("300000 600000 900000", access.read(path))
        assertEquals(listOf("cat $path"), commands)
    }

    @Test
    fun `SystemReader falls back when direct protected topology is unreadable`() {
        val commands = mutableListOf<String>()
        val access = PServerProtectedNodeAccess(client(commands) { "300000 600000" })
        val reader = SystemReader(
            privilegedNodes = access,
            directRead = { null },
            directDirectories = { _, _ -> emptyList() },
        )
        val path = "/sys/devices/system/cpu/cpufreq/policy0/scaling_available_frequencies"

        assertEquals("300000 600000", reader.read(path))
        assertEquals(listOf("cat $path"), commands)
    }

    @Test
    fun `SystemReader falls back for protected transaction ceiling readback`() {
        val commands = mutableListOf<String>()
        val access = PServerProtectedNodeAccess(client(commands) { "1804800" })
        val reader = SystemReader(
            privilegedNodes = access,
            directRead = { null },
            directDirectories = { _, _ -> emptyList() },
        )
        val path = "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"

        assertEquals("1804800", reader.read(path))
        assertEquals(listOf("cat $path"), commands)
    }

    @Test
    fun `protected topology fallback admits immutable cpuinfo minimum but not arbitrary policy files`() {
        val commands = mutableListOf<String>()
        val access = PServerProtectedNodeAccess(client(commands) { "300000" })
        val reader = SystemReader(
            privilegedNodes = access,
            directRead = { null },
            directDirectories = { _, _ -> emptyList() },
        )
        val cpuinfoMin = "/sys/devices/system/cpu/cpufreq/policy0/cpuinfo_min_freq"

        assertEquals("300000", reader.read(cpuinfoMin))
        assertNull(reader.read("/sys/devices/system/cpu/cpufreq/policy0/scaling_governor"))
        assertEquals(listOf("cat $cpuinfoMin"), commands)
    }

    @Test
    fun `protected topology rejects non CPU paths without contacting PServer`() {
        val commands = mutableListOf<String>()
        val access = PServerProtectedNodeAccess(client(commands) { "unexpected" })

        assertNull(access.read("/sys/class/kgsl/kgsl-3d0/devfreq/available_frequencies"))
        assertTrue(access.directories("/sys/devices/system/cpu", "cpu").isEmpty())
        assertTrue(commands.isEmpty())
    }

    @Test
    fun `protected policy listing filters malformed and out of root entries`() {
        val commands = mutableListOf<String>()
        val access = PServerProtectedNodeAccess(
            client(commands) {
                """
                /sys/devices/system/cpu/cpufreq/policy7
                /data/local/tmp/policy4
                /sys/devices/system/cpu/cpufreq/policy0
                /sys/devices/system/cpu/cpufreq/policy0
                /sys/devices/system/cpu/cpufreq/policybad
                """.trimIndent()
            },
        )

        assertEquals(
            listOf(
                "/sys/devices/system/cpu/cpufreq/policy0",
                "/sys/devices/system/cpu/cpufreq/policy7",
            ),
            access.directories("/sys/devices/system/cpu/cpufreq", "policy").map { it.path },
        )
        assertEquals(1, commands.size)
        assertTrue(commands.single().startsWith("find /sys/devices/system/cpu/cpufreq "))
    }

    private fun client(
        commands: MutableList<String>,
        output: (String) -> String?,
    ): PServerClient = PServerClient(
        transport = PServerBinderTransport { command, _ ->
            commands += command
            PServerTransactResult(
                accepted = true,
                output = output(command),
            )
        },
    )
}
