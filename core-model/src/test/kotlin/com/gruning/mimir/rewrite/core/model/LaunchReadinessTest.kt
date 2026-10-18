package com.gruning.mimir.rewrite.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LaunchReadiness] exhaustiveness + the cert-is-a-disclaimer ruling. The `when` below has NO `else`
 * branch — if a future state is added to the sealed interface this test STOPS COMPILING, which is
 * exactly the guard we want (a new readiness state can never be silently unhandled).
 */
class LaunchReadinessTest {

    private val ps1 = PlatformId.PS1
    private val gid = GameId("group:ps1:chrono-cross")

    private fun classify(r: LaunchReadiness): String = when (r) {
        is LaunchReadiness.Ready -> "ready"
        is LaunchReadiness.NeedsEmulator -> "needs-emulator"
        is LaunchReadiness.NeedsBios -> "needs-bios"
        is LaunchReadiness.MissingFile -> "missing-file"
        is LaunchReadiness.Unsupported -> "unsupported"
    }

    private fun sampleTarget(): LaunchTarget {
        val emu = EmulatorProfile(
            id = EmulatorId("com.retroarch.aarch64#swanstation"),
            packageName = "com.retroarch.aarch64",
            displayName = "DuckStation",
            retroArchCore = "swanstation",
            installed = true,
            capability = EmulatorCapability.DIRECT_PAYLOAD,
            supportedPlatforms = setOf(ps1),
        )
        return LaunchTarget(
            gameId = gid,
            platformId = ps1,
            emulator = emu,
            locator = "/storage/SD/ROMs/ps1/ChronoCross.m3u",
            locatorForm = LaunchLocatorForm.FILE_PATH,
            title = "Chrono Cross",
        )
    }

    @Test
    fun whenIsExhaustiveOverAllStates() {
        assertEquals("ready", classify(LaunchReadiness.Ready(sampleTarget())))
        assertEquals("needs-emulator", classify(LaunchReadiness.NeedsEmulator(ps1)))
        assertEquals("needs-bios", classify(LaunchReadiness.NeedsBios(ps1)))
        assertEquals("missing-file", classify(LaunchReadiness.MissingFile(gid)))
        assertEquals("unsupported", classify(LaunchReadiness.Unsupported(ps1)))
    }

    @Test
    fun isReadyOnlyForReady() {
        assertTrue(LaunchReadiness.Ready(sampleTarget()).isReady)
        assertFalse(LaunchReadiness.NeedsEmulator(ps1).isReady)
        assertFalse(LaunchReadiness.NeedsBios(ps1).isReady)
        assertFalse(LaunchReadiness.MissingFile(gid).isReady)
        assertFalse(LaunchReadiness.Unsupported(ps1).isReady)
    }

    @Test
    fun thereIsNoCertificationReadinessState() {
        // cert is a disclaimer, never a launch gate. Assert the
        // sealed set contains no "needs certification"-style veto by enumerating the intended shapes.
        val allShapes = listOf(
            LaunchReadiness.Ready(sampleTarget()),
            LaunchReadiness.NeedsEmulator(ps1),
            LaunchReadiness.NeedsBios(ps1),
            LaunchReadiness.MissingFile(gid),
            LaunchReadiness.Unsupported(ps1),
        )
        // Exactly the five intended states; the exhaustive `when` above proves there are no others.
        assertEquals(5, allShapes.map { classify(it) }.toSet().size)
    }

    @Test
    fun needsBiosCanBeHonestlyUnknownNotMissing() {
        // honest about scoped-storage "unknown".
        val unknown = LaunchReadiness.NeedsBios(ps1, presenceUnknown = true)
        assertTrue(unknown.presenceUnknown)
    }
}
