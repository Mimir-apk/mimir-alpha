package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Written while chasing a `WRITE_SETTINGS` `SecurityException` seen at 12:21:11.898 in the tester's
 * 2026-09-16 first-run capture, 40 ms after the HOME role landed.
 *
 * **The hunt found no culprit, and that is the finding.** Every `Settings.System` write in the app module
 * goes through one gateway, which checks `Settings.System.canWrite()` first *and* wraps the write in
 * `runCatching` -- so it cannot surface an uncaught exception even if the check were wrong. There are no
 * `Settings.Secure`/`Settings.Global` writes, no reflective settings-provider access from Mimir's own
 * process, and no other WRITE_SETTINGS-demanding API in use. Privileged vendor settings are accessed
 * through the separately admitted vendor service.
 *
 * The original capture is no longer on disk, and the triage line that recorded it was abbreviated to
 * `SecurityException: ... was not granted ... WRITE_SETTINGS` -- it never recorded which PROCESS threw. So
 * "Mimir has an unguarded caller" was an inference, not an observation, and it is withdrawn.
 *
 * These tests exist so the conclusion stays true rather than merely having been true once: if someone adds
 * a second `Settings.System` writer, or removes the guard from the one that exists, this fails and the
 * question reopens with a real culprit instead of a hypothesis.
 */
class SystemSettingsWriteGuardTest {

    private fun appSources(): List<File> {
        val roots = listOf(File("app/src/main/kotlin"), File("src/main/kotlin"))
        val root = roots.firstOrNull(File::isDirectory)
            ?: error("app main sources not found from ${File(".").absolutePath}")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test
    fun `exactly one file in the app module writes Settings System`() {
        val writers = appSources()
            .filter { it.readText().contains("Settings.System.put") }
            .map { it.name }
            .sorted()

        assertEquals(
            "a new Settings.System writer appeared. It must go through AndroidBrightnessSettingsGateway, " +
                "or carry its own canWrite() guard -- an unguarded one throws SecurityException on any " +
                "device where the player has not granted the special access. Writers found: $writers",
            listOf("AndroidBrightnessStore.kt"),
            writers,
        )
    }

    @Test
    fun `the one writer checks canWrite before writing, and cannot throw out of the attempt`() {
        val source = appSources().single { it.name == "AndroidBrightnessStore.kt" }.readText()

        val write = source.substringAfter("override fun write(key: String, value: String): Boolean {")
            .substringBefore("\n    }")
        assertTrue(
            "search window missed the write() body -- the assertions below would pass vacuously",
            write.contains("Settings.System.putString") && write.length > 80,
        )
        assertTrue(
            "the write must be gated on canWrite() -- declaring WRITE_SETTINGS in the manifest does NOT " +
                "grant it, and checkSelfPermission reports GRANTED regardless, so canWrite() is the only " +
                "check that means anything",
            write.contains("if (!canWrite()) return false"),
        )
        assertTrue(
            "the write must also be wrapped, so a revoke racing the check degrades to false rather than " +
                "throwing out of a caller that has no reason to expect it",
            write.contains("runCatching {"),
        )
        assertTrue(
            "canWrite() itself must read Settings.System.canWrite, not a cached or permission-check proxy",
            source.contains("Settings.System.canWrite(appContext)"),
        )
    }

    @Test
    fun `no Secure or Global settings writes exist in the app module`() {
        // A different permission (WRITE_SECURE_SETTINGS) that an installed app can never hold. Any such
        // write is dead code at best and a crash at worst; Mimir's privileged writes go through the
        // admitted vendor service.
        val offenders = appSources()
            .filter { f ->
                val t = f.readText()
                t.contains("Settings.Secure.put") || t.contains("Settings.Global.put")
            }
            .map { it.name }

        assertTrue(
            "these files write Secure/Global settings from the app process, which cannot succeed: $offenders",
            offenders.isEmpty(),
        )
    }
}
