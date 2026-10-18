package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.launch.EmulatorSeedRegistry
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one privileged question the RetroArch core check asks -- "which cores does this RetroArch have" -- is a fixed,
 * read-only command with no place for caller text. These pin the exact command, the exact allowlist, and that nothing
 * outside it can be rendered.
 */
class ListRetroArchCoresOperationTest {
    private fun render(userId: Int, packageName: String): String? =
        ListRetroArchCores.create(userId, packageName)?.render()

    @Test
    fun `the rendered command is exact for each RetroArch build`() {
        assertEquals(
            "if L=\$(/system/bin/ls -1 /data/user/0/com.retroarch.aarch64/cores 2>/dev/null); " +
                "then echo \$L __MIMIR_CORES_OK__; fi",
            render(0, "com.retroarch.aarch64"),
        )
        assertEquals(
            "if L=\$(/system/bin/ls -1 /data/user/0/com.retroarch/cores 2>/dev/null); " +
                "then echo \$L __MIMIR_CORES_OK__; fi",
            render(0, "com.retroarch"),
        )
        assertEquals(
            "if L=\$(/system/bin/ls -1 /data/user/10/com.retroarch.ra32/cores 2>/dev/null); " +
                "then echo \$L __MIMIR_CORES_OK__; fi",
            render(10, "com.retroarch.ra32"),
        )
    }

    @Test
    fun `only the three RetroArch builds are admitted, and they are the seed registry's`() {
        assertEquals(
            setOf("com.retroarch", "com.retroarch.aarch64", "com.retroarch.ra32"),
            ListRetroArchCores.AllowedPackages,
        )
        assertEquals(
            EmulatorSeedRegistry.allSeedPackages().filter { it.startsWith("com.retroarch") }.toSet(),
            ListRetroArchCores.AllowedPackages,
        )
        for (pkg in ListRetroArchCores.AllowedPackages) assertNotNull(pkg, ListRetroArchCores.create(0, pkg))
    }

    @Test
    fun `a package outside the allowlist builds no operation`() {
        val refused = listOf(
            "",
            " ",
            "com.android.chrome",
            "org.dolphinemu.dolphinemu",
            "com.retroarch.evil",
            "com.retroarch.aarch64.debug",
            "com.retroarch.",
            "com.retroarch.aarch64\n",
            " com.retroarch",
            "COM.RETROARCH",
            "RetroArch",
        )
        for (pkg in refused) assertNull("pkg=[$pkg]", ListRetroArchCores.create(0, pkg))
    }

    @Test
    fun `path injection builds no operation`() {
        val injected = listOf(
            "../com.retroarch",
            "com.retroarch/../../../../etc",
            "com.retroarch.aarch64/cores",
            "com.retroarch;reboot",
            "com.retroarch && reboot",
            "com.retroarch aarch64",
            "com.retroarch\tx",
            "com.retroarch|id",
            "com.retroarch\$(id)",
            "com.retroarch`id`",
            "com.retroarch'",
            "com.retroarch\"",
            "com.retroarch>/sdcard/x",
            "com.retroarch*",
            "com.retroarch\u0000",
        )
        for (pkg in injected) assertNull("pkg=[$pkg]", ListRetroArchCores.create(0, pkg))
    }

    @Test
    fun `a negative user id builds no operation and a non-negative one is only digits`() {
        for (userId in listOf(-1, -10, Int.MIN_VALUE)) {
            assertNull("userId=$userId", ListRetroArchCores.create(userId, "com.retroarch.aarch64"))
        }
        for (userId in listOf(0, 1, 10, 999, Int.MAX_VALUE)) {
            val rendered = render(userId, "com.retroarch.aarch64")
            assertNotNull("userId=$userId", rendered)
            assertTrue(rendered, rendered!!.contains("/data/user/$userId/com.retroarch.aarch64/cores "))
        }
    }

    @Test
    fun `every rendering fits the transport cap and has no place for anything else`() {
        val shape = Regex(
            "if L=\\$\\(/system/bin/ls -1 /data/user/[0-9]+/com\\.retroarch[a-z0-9.]*/cores 2>/dev/null\\); " +
                "then echo \\\$L __MIMIR_CORES_OK__; fi",
        )
        for (pkg in ListRetroArchCores.AllowedPackages) {
            for (userId in listOf(0, 10, Int.MAX_VALUE)) {
                val rendered = render(userId, pkg)!!
                assertTrue(rendered, rendered.toByteArray(StandardCharsets.UTF_8).size <= 198)
                assertTrue(rendered, shape.matches(rendered))
            }
        }
    }

    @Test
    fun `the command lists and prints and nothing more`() {
        val rendered = render(0, "com.retroarch.aarch64")!!
        for (forbidden in listOf("rm ", "mv ", "cp ", "chmod", "chown", "settings ", "am ", "input ", "sh ", "cat ", ">>", "| ")) {
            assertTrue("$forbidden in $rendered", !rendered.contains(forbidden))
        }
    }
}
