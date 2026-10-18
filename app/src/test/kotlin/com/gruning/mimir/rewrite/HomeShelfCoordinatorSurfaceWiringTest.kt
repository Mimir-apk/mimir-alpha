package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-text pins on two closures inside `HomeShelfCoordinator` (a `@Composable`, so the closures cannot be driven from a unit test).
 *
 * Both are review items of the Library-on-GameShelf lane (2026-10-10):
 *  - A Tome return after the last pin was removed falls back to Editor's Choice. The shelf's window, finger mover and ring seat are keyed on the
 *    surface epoch, so that fallback is a mode switch like any other and bumps it.
 *  - The automatic tile fetch is keyed on what it fetches for (titles, PC game ids, the key), not on the favourites row list its own publish re-emits.
 */
class HomeShelfCoordinatorSurfaceWiringTest {

    private val coordinator: String by lazy {
        listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/HomeShelfCoordinator.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/HomeShelfCoordinator.kt"),
        ).firstOrNull(File::isFile)?.readText() ?: error("HomeShelfCoordinator.kt not found from ${File(".").absolutePath}")
    }

    private fun between(after: String, before: String): String {
        assertTrue("anchor missing: $after", coordinator.contains(after))
        val tail = coordinator.substringAfter(after)
        assertTrue("anchor missing after '$after': $before", tail.contains(before))
        return tail.substringBefore(before)
    }

    @Test
    fun `a Tome return that falls back to Editor's Choice bumps the epoch like every other mode switch`() {
        // RC-49: the destination is resolved before the pop, so the slice now runs to the ordered return at the end of the lambda.
        val restore = between("val restoreRootEntry: (VisualHomeShelfEntry?) -> Unit = { entry ->", "landingKey?.let { key ->")
        assertTrue("the destination is decided before the epoch is", restore.indexOf("val destinationMode") in 0 until restore.indexOf("homeSurfaceEpoch += 1"))
        assertTrue(
            "the fallback is a switch: current mode against the destination, not only against the mode the Tome was opened from",
            restore.contains("homeViewMode != restoringMode || homeViewMode != destinationMode"),
        )
        assertTrue(restore.contains("homeViewMode = destinationMode"))
        assertEquals("one bump", 1, Regex("homeSurfaceEpoch \\+= 1").findAll(restore).count())
    }

    @Test
    fun `the automatic tile fetch is not keyed on the favourites row list`() {
        val window = between("val sgdbKey by SteamGridDbKeyStore.key.collectAsState()", "val tiledRows = remember(")
        assertTrue("the effect is keyed on the derived maps", window.contains("LaunchedEffect(sgdbTitles, sgdbPcGameIds, sgdbKey)"))
        assertTrue(
            "its own publish re-emits the list (the tile changes wiredFace), so the list must not be a key",
            !window.contains("LaunchedEffect(emulatedFavorites"),
        )
    }
}
