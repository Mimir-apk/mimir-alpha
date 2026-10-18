package com.gruning.mimir.rewrite

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import com.gruning.mimir.rewrite.core.launch.PlatformEmulatorStatus
import com.gruning.mimir.rewrite.core.model.PlatformId
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * RC-30 and RC-31 (audit 2026-10-14): the read model is rebuilt on every foreground return, including the return from a game
 * while the cover is up, and it did binder and AppOps work in `remember` blocks on the composing thread. And it printed a
 * verdict ("Automatic", NEEDS_EMULATOR, "Recommended") before the emulator resolve had produced one.
 *
 * The counter below is the thread each read ran on: a read that ran on the composing thread is a read in composition.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ResumeFrameReadsOffMainTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the read runs off the composing thread, the first frame shows the initial value, a re-read never blinks back to it`() {
        val readThreads = CopyOnWriteArrayList<Thread>()
        val shown = CopyOnWriteArrayList<Int>()
        var tick by mutableStateOf(0)
        var composingThread: Thread? = null

        compose.setContent {
            composingThread = Thread.currentThread()
            val value by rememberOffMainRead(-1, tick) {
                readThreads += Thread.currentThread()
                Thread.sleep(40L)
                tick * 10
            }
            shown += value
        }
        assertEquals("the very first composition shows the placeholder, not a read", -1, shown.first())

        compose.waitUntil(timeoutMillis = 5_000L) { shown.last() == 0 }
        Snapshot.withMutableSnapshot { tick = 1 }
        Snapshot.sendApplyNotifications()
        compose.waitUntil(timeoutMillis = 5_000L) { shown.last() == 10 }

        assertEquals("one read per key", 2, readThreads.size)
        assertTrue(
            "no read ran on the composing thread: $readThreads vs $composingThread",
            readThreads.none { it === composingThread },
        )
        assertFalse(
            "a resume re-read keeps the last value until the new one lands, so it never blinks back to the placeholder: $shown",
            shown.dropWhile { it == -1 }.contains(-1),
        )
    }

    @Test
    fun `an emulator verdict exists only once the resolve has ended and covers every platform the library lists`() {
        val ps1 = PlatformId.PS1
        val snes = PlatformId.SNES
        fun status(id: PlatformId) = PlatformEmulatorStatus(id, emptyList(), null, null)

        assertFalse("before the first resolve ends nothing is known", emulatorVerdictsLoaded(null, listOf(ps1)))
        assertFalse(
            "a platform the resolve did not see has no verdict yet",
            emulatorVerdictsLoaded(mapOf(ps1 to status(ps1)), listOf(ps1, snes)),
        )
        assertTrue(emulatorVerdictsLoaded(mapOf(ps1 to status(ps1), snes to status(snes)), listOf(ps1, snes)))
        assertTrue(
            "a library with no platforms is covered once the resolve has ended",
            emulatorVerdictsLoaded(emptyMap(), emptyList()),
        )
        assertFalse("but not before it has", emulatorVerdictsLoaded(null, emptyList()))
    }

    @Test
    fun `the read model starts with no verdicts, gates the Emulators rows on them and exposes the flag`() {
        val source = source("MimirSettingsReadCoordinator.kt")
        assertTrue(
            "the resolve starts as null, not as an empty map that reads as 'no emulator anywhere'",
            source.contains("produceState<Map<PlatformId, PlatformEmulatorStatus>?>(\n        initialValue = null,"),
        )
        assertTrue(source.contains("val platformStatuses = resolvedPlatformStatuses ?: emptyMap()"))
        assertTrue(
            source.contains("val platformStatusesLoaded = emulatorVerdictsLoaded(resolvedPlatformStatuses, library.platforms.map { it.platformId })"),
        )
        assertTrue("handed to every consumer", source.contains("platformStatusesLoaded = platformStatusesLoaded,"))
        assertFalse(
            "no synchronous resolve of a missing platform in composition",
            source.substringAfter("val emulatorAssignments = remember(").substringBefore("val biosRequirements").contains("resolvePlatformEmulatorStatus"),
        )
    }

    @Test
    fun `every platform read of the resume frame goes through the off-main read`() {
        val source = source("MimirSettingsReadCoordinator.kt")
        for (anchor in listOf(
            "val specialAccessRows by rememberOffMainRead<List<SpecialAccessRow>?>(",
            "val keyboardPickerEntry by rememberOffMainRead(",
            "val trackpadPlatform by rememberOffMainRead<Pair<Boolean, Boolean>?>(",
            "val otherPlayersAccess by rememberOffMainRead(false, specialAccessRefreshTick)",
        )) {
            assertTrue("missing: $anchor", source.contains(anchor))
        }
        // The platform calls themselves appear only inside those reads.
        assertEquals("one hasOverlayPermission, inside the special-access read", 1, Regex("""hasOverlayPermission\(\)""").findAll(source).count())
        assertTrue(
            "and that one is inside the off-main block",
            source.substringAfter("val specialAccessRows by rememberOffMainRead").substringBefore("val keyboardPickerEntry")
                .contains("hasOverlayPermission()"),
        )
        for (call in listOf("currentMimirKeyboardPickerEntry(", "MimirTrackpadRuntime.accessibilityServiceEnabled(", "musicOtherPlayersAccessGranted(")) {
            assertEquals("$call appears once in the read model", 1, Regex(Regex.escape(call)).findAll(source).count())
        }
        assertEquals(
            "the helper hops to IO",
            1,
            Regex("""produceState\(initialValue, \*keys\) \{\s*value = withContext\(Dispatchers\.IO\) \{ read\(\) \}""").findAll(source).count(),
        )
    }

    @Test
    fun `the onboarding access read is skipped past onboarding and runs on IO`() {
        val source = source("MimirOnboardingCoordinator.kt")
        val refresh = source.substringAfter("val refreshAccess = {").substringBefore("LaunchedEffect(onboardingOnScreen")
        assertTrue(
            "the resume hook does nothing once the onboarding destination is gone",
            refresh.contains("if (navigation.destination == MimirDestination.ONBOARDING)"),
        )
        assertFalse("and reads nothing itself", refresh.contains("readOnboardingAccess"))
        assertEquals(
            "readOnboardingAccess is called from the two effects and nowhere else",
            2,
            Regex("""readOnboardingAccess\(core\)""").findAll(source).count(),
        )
        for (block in source.split("readOnboardingAccess(core)").dropLast(1)) {
            assertTrue(
                "a read must follow withContext(Dispatchers.IO) within a few lines: ...${block.takeLast(160)}",
                block.takeLast(160).contains("withContext(Dispatchers.IO)"),
            )
        }
        assertFalse(
            "the initial state is a placeholder, not a read at composition",
            source.contains("mutableStateOf(readOnboardingAccess(core))"),
        )
    }

    private fun source(name: String): String = listOf(
        File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        File("../app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
    ).firstOrNull(File::isFile)?.readText() ?: throw AssertionError("$name not reachable from the test working directory")
}
