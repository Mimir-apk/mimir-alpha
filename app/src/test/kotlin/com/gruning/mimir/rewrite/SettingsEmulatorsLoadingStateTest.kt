package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Alpha cert 2026-10-08, lane B. B1: "Checking installed emulators…" never resolved on an empty library because
 * `emulatorAssignments` used `null` for both "not loaded yet" and "loaded, zero platforms". The coordinator is a
 * `@Composable` this module cannot construct, so the decision is a pure function ([emulatorRowsOrLoading], tested
 * for real) and the wiring facts the compiler does not enforce (the remember key, the host binding) are source pins.
 * The render of each state is `SettingsLibraryAndEmulatorsPaneRenderTest` in `:feature-settings`.
 */
class SettingsEmulatorsLoadingStateTest {

    @Test
    fun `not loaded is the loading sentinel whatever the platform count`() {
        assertNull(emulatorRowsOrLoading<String>(platformsLoaded = false, platformCount = 0) { listOf("row") })
        assertNull(emulatorRowsOrLoading<String>(platformsLoaded = false, platformCount = 3) { listOf("row") })
    }

    @Test
    fun `loaded with zero platforms is an empty list, not null, and builds nothing`() {
        var built = false
        val rows = emulatorRowsOrLoading<String>(platformsLoaded = true, platformCount = 0) {
            built = true
            listOf("row")
        }
        assertEquals(emptyList<String>(), rows)
        assertFalse("nothing is built for an empty library", built)
    }

    @Test
    fun `loaded with platforms builds the rows`() {
        assertEquals(
            listOf("a", "b"),
            emulatorRowsOrLoading(platformsLoaded = true, platformCount = 2) { listOf("a", "b") },
        )
    }

    private fun source(vararg candidates: String): String {
        val file = candidates.map(::File).firstOrNull(File::isFile)
            ?: error("none of ${candidates.toList()} found from ${File(".").absolutePath}")
        return file.readText()
    }

    private fun coordinatorSource() = source(
        "app/src/main/kotlin/com/gruning/mimir/rewrite/MimirSettingsReadCoordinator.kt",
        "src/main/kotlin/com/gruning/mimir/rewrite/MimirSettingsReadCoordinator.kt",
    )

    @Test
    fun `the emulator rows are recomputed when the library finishes loading, and gated on it`() {
        val source = coordinatorSource()
        assertTrue(
            "the loaded flag must be a remember key, or the false->true flip with platforms still empty never recomputes",
            source.contains("remember(library.platforms, library.platformsLoaded, platformStatuses, platformStatusesLoaded)"),
        )
        assertTrue(
            "the rows must go through the tri-state gate, not an isEmpty() -> null",
            source.contains("emulatorRowsOrLoading(library.platformsLoaded && platformStatusesLoaded, library.platforms.size)"),
        )
    }

    @Test
    fun `the All-files grant is read off the main thread and keyed on the foreground tick`() {
        val source = coordinatorSource()
        val block = source.substringAfter("val allFilesAccess by produceState(").substringBefore("val mButtonVisibilityPolicy")
        assertTrue("keyed on the resume tick", block.startsWith("initialValue = false, specialAccessRefreshTick)"))
        assertTrue("sampled on Dispatchers.IO", block.contains("withContext(Dispatchers.IO)"))
        assertTrue("through isExternalStorageManager", block.contains("Environment.isExternalStorageManager()"))
    }

    @Test
    fun `the host hands the grant to the Library pane through the read model it already passes`() {
        val host = source(
            "app/src/main/kotlin/com/gruning/mimir/rewrite/MimirAppHost.kt",
            "src/main/kotlin/com/gruning/mimir/rewrite/MimirAppHost.kt",
        )
        assertTrue(
            "MimirSettingsDestination's SettingsHome(library = ...) must carry the live grant",
            host.contains(
                "library = bindings.library.readModel?.copy(platforms = bindings.library.platforms, " +
                    "allFilesAccess = bindings.settingsRead.allFilesAccess),",
            ),
        )
    }
}
