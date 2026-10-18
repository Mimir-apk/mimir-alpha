package com.gruning.mimir.rewrite

import android.os.Environment
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A card that mounts after the library has drawn must give its covers back without a restart.
 *
 * The art memo remembers a miss for the life of the process, and Mimir is the home app: at boot the library draws before
 * the SD card mounts. `StorageVolumeMonitor` brought the GAMES back when the card returned and never told the memo, so the
 * shelves kept their glyphs. [StorageMountArtRefresh] is the decision; the monitor's wiring of it is pinned below because
 * the monitor itself needs a real `StorageManager`.
 */
class StorageMountArtRefreshTest {

    @Test
    fun `restored games drop the art memo`() {
        val refresh = StorageMountArtRefresh()
        var drops = 0

        val dropped = refresh.afterReconcile(restoredGames = 12) { drops++ }

        assertTrue(dropped)
        assertEquals(1, drops)
    }

    @Test
    fun `a volume that mounted drops the memo even when no game flipped back`() {
        // The card mounted before the first reconcile ran, so its games never left AVAILABLE -- but the covers were
        // probed while it was away.
        val refresh = StorageMountArtRefresh()
        var drops = 0

        refresh.onVolumeState(Environment.MEDIA_MOUNTED)
        val dropped = refresh.afterReconcile(restoredGames = 0) { drops++ }

        assertTrue(dropped)
        assertEquals(1, drops)
    }

    @Test
    fun `a mount is spent by the reconcile that handled it`() {
        val refresh = StorageMountArtRefresh()
        var drops = 0
        refresh.onVolumeState(Environment.MEDIA_MOUNTED)

        refresh.afterReconcile(restoredGames = 0) { drops++ }
        val again = refresh.afterReconcile(restoredGames = 0) { drops++ }

        assertFalse("nothing new happened, so nothing is re-resolved", again)
        assertEquals(1, drops)
    }

    @Test
    fun `a steady reconcile and a card going away leave the memo alone`() {
        val refresh = StorageMountArtRefresh()
        var drops = 0

        // Startup pass with nothing restored, then the card being pulled: it reports unmounted/ejecting, never mounted.
        assertFalse(refresh.afterReconcile(restoredGames = 0) { drops++ })
        refresh.onVolumeState(Environment.MEDIA_UNMOUNTED)
        refresh.onVolumeState(Environment.MEDIA_EJECTING)
        refresh.onVolumeState(Environment.MEDIA_CHECKING)
        refresh.onVolumeState(null)
        assertFalse(refresh.afterReconcile(restoredGames = 0) { drops++ })

        assertEquals("a cold start must not re-project the whole library for nothing", 0, drops)
    }

    @Test
    fun `a failed drop is retried by the next reconcile`() {
        val refresh = StorageMountArtRefresh()
        var drops = 0
        refresh.onVolumeState(Environment.MEDIA_MOUNTED)

        try {
            refresh.afterReconcile(restoredGames = 0) { error("synthetic memo failure") }
            throw AssertionError("the failure must reach the monitor's own handler")
        } catch (expected: IllegalStateException) {
            assertEquals("synthetic memo failure", expected.message)
        }
        val retried = refresh.afterReconcile(restoredGames = 0) { drops++ }

        assertTrue("the mount was not lost with the failed drop", retried)
        assertEquals(1, drops)
    }

    @Test
    fun `the monitor reports mounts to it and hands it the library's media refresh`() {
        val source = listOf(
            File("src/main/kotlin/com/gruning/mimir/rewrite/StorageVolumeMonitor.kt"),
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/StorageVolumeMonitor.kt"),
            File("../app/src/main/kotlin/com/gruning/mimir/rewrite/StorageVolumeMonitor.kt"),
        ).firstOrNull(File::isFile)?.readText()
            ?: throw AssertionError("StorageVolumeMonitor.kt not reachable from the test working directory")

        assertTrue(
            "every volume callback must be reported, or a card mounting before the first reconcile is never noticed",
            source.contains("artRefresh.onVolumeState(volume.state)"),
        )
        assertTrue(
            "the reconcile must end by dropping the memo AND bumping the media epoch -- refreshAllMedia does both",
            source.contains("artRefresh.afterReconcile(restored) { LibraryInjection.repository(app).refreshAllMedia() }"),
        )
        assertTrue(
            "after the availability flip commits, so the shelves re-project against the restored games",
            source.indexOf("reconcileRootAvailability(") < source.indexOf("artRefresh.afterReconcile("),
        )
    }
}
