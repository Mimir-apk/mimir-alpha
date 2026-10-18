package com.gruning.mimir.rewrite

import java.io.ByteArrayInputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class EmulatorRenderFpsHelperTest {
    @Test
    fun `resolver selects the global Mupen renderer and verifies its installed owner`() = resolverFixture {
        assertEquals("4321 10118 99001\n", resolve())
        registry = "$packageName 10119 0 /data/user/0/example\n"
        assertEquals("", resolve())
        registry = ""
        assertEquals("", resolve())
        registry = "$packageName 10118\n$packageName 10118\n"
        assertEquals("", resolve())
    }

    @Test
    fun `resolver never chooses among multiple renderer pids or emits unbounded numbers`() = resolverFixture {
        // Use the ordinary process route so the old resolver cannot false-green every negative
        // case merely because it does not know Mupen's renderer alias.
        packageName = "org.dolphinemu.dolphinemu"
        expectedProcess = packageName
        registry = "$packageName 10118\n"
        assertEquals("4321 10118 99001\n", resolve())
        pidList = "4321 4322"
        assertEquals("", resolve())
        pidList = "4321"
        for (bad in listOf("-1", "not-a-uid", "9".repeat(1024))) {
            registry = "$packageName $bad\n"
            assertEquals("", resolve())
        }
        registry = "$packageName 10118\n"
        for (bad in listOf("0", "-1", "2147483648", "9".repeat(1024))) {
            pidList = bad
            assertEquals("", resolve())
        }
        pidList = "4321"
        for (bad in listOf("0", "-1", "invalid", "9".repeat(1024))) {
            start = bad
            assertEquals("", resolve())
        }
        start = "99001"
        uid = "9".repeat(1024)
        assertEquals("", resolve())
    }

    @Test
    fun `resolver preserves X1BOX child and ordinary emulator process selection`() = resolverFixture {
        packageName = "com.izzy2lost.x1box"
        expectedProcess = "$packageName:xemu"
        registry = "$packageName 10118\n"
        assertEquals("4321 10118 99001\n", resolve())
        packageName = "org.dolphinemu.dolphinemu"
        expectedProcess = packageName
        registry = "$packageName 10118\n"
        assertEquals("4321 10118 99001\n", resolve())
        uid = "110118" // another Android user's process cannot borrow user 0's app ID
        assertEquals("", resolve())
        packageName = "com.example.unapproved"
        expectedProcess = packageName
        registry = "$packageName 10118\n"
        uid = "10118"
        assertEquals("", resolve())
    }

    private fun resolverFixture(test: ResolverFixture.() -> Unit) {
        val fixture = ResolverFixture()
        try { fixture.test() } finally { fixture.root.deleteRecursively() }
    }

    /** Runs the shipped script; substitutions replace only Android executable/file locations. */
    private class ResolverFixture {
        val root = Files.createTempDirectory("mimir-render-resolver").toFile()
        var packageName = "org.mupen64plusae.v3.alpha"
        var expectedProcess = "paulscode.android.mupen64plusae.GameActivity"
        var registry = "$packageName 10118\n"
        var pidList = "4321"
        var uid = "10118"
        var start = "99001"

        fun resolve(): String {
            val commands = File(root, "bin").apply { mkdirs() }
            for (command in listOf("awk", "cat", "printf")) {
                val executable = listOf("/usr/bin/$command", "/bin/$command").first { File(it).canExecute() }
                val link = File(commands, command).toPath()
                if (!Files.exists(link)) Files.createSymbolicLink(link, File(executable).toPath())
            }
            File(commands, "pidof").apply {
                writeText("#!/bin/sh\n[ \"\$1\" = \"\$EXPECTED_PROCESS\" ] || exit 1\nprintf '%s\\n' \"\$FAKE_PID_LIST\"\n")
                setExecutable(true)
            }
            val packageFile = File(root, "packages.list").apply { writeText(registry) }
            val proc = File(root, "proc/4321").apply { mkdirs() }
            File(proc, "stat").writeText("4321 (renderer name) S " + "0 ".repeat(18) + "$start\n")
            File(proc, "status").writeText("Uid:\t$uid\t$uid\t$uid\t$uid\n")
            val script = File(root, "resolve.sh").apply {
                writeText(EMULATOR_RENDER_FPS_RESOLVER_SCRIPT
                    .replace("/system/bin/", "${commands.absolutePath}/")
                    .replace("/data/system/packages.list", packageFile.absolutePath)
                    .replace("/proc/", "${root.absolutePath}/proc/"))
            }
            val builder = ProcessBuilder("/bin/sh", script.absolutePath, packageName)
            builder.environment()["EXPECTED_PROCESS"] = expectedProcess
            builder.environment()["FAKE_PID_LIST"] = pidList
            val process = builder.start()
            assertTrue("resolver timed out", process.waitFor(3, TimeUnit.SECONDS))
            val output = process.inputStream.bufferedReader().readText()
            assertTrue("reply exceeded numeric envelope", output.toByteArray().size <= 43)
            assertEquals(0, process.exitValue())
            return output
        }
    }

    @Test
    fun `stager writes executable helper resolver and atomic request beside them`() {
        val root = Files.createTempDirectory("mimir-render-fps-helper").toFile()
        val stager = EmulatorRenderFpsHelperStager(root) {
            ByteArrayInputStream("helper-binary".toByteArray())
        }
        try {
            val files = requireNotNull(stager.stage())
            assertEquals("helper-binary", files.helper.readText())
            assertTrue(files.helper.canExecute())
            assertTrue(files.resolverScript.canExecute())
            assertTrue(files.launcherScript.canExecute())
            assertTrue(files.readerScript.canExecute())
            assertTrue(files.resolverScript.readText().contains("package=\$1"))
            assertTrue(files.resolverScript.readText().contains("start=\${20}"))
            assertTrue(files.launcherScript.readText().contains("nohup"))
            assertTrue(files.readerScript.readText().contains("-le 512"))
            assertTrue(files.readerScript.readText().contains("-le 684"))
            val start = requireNotNull(VendorPServerOperation.StartEmulatorRenderFps.create(files))
            val read = requireNotNull(VendorPServerOperation.ReadEmulatorRenderFpsRecord.create(files))
            assertTrue(start.render().toByteArray().size <= 198)
            assertTrue(read.render().toByteArray().size <= 198)
            assertTrue(read.render().contains(files.readerScript.name))

            val request = EmulatorRenderFpsRequest(
                token = "0123abcd",
                packageName = "org.dolphinemu.dolphinemu",
                uid = 10_123L,
                pid = 4_321,
                startTicks = 99_001L,
                generation = 1L,
                leaseBoottimeMs = 10_000L,
            )
            assertTrue(stager.writeRequest(files, request))
            assertEquals(request, parseEmulatorRenderFpsRequest(files.request.readText()))
        } finally {
            root.deleteRecursively()
        }
    }
}
