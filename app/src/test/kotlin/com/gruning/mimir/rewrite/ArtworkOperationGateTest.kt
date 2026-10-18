package com.gruning.mimir.rewrite

import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The process gate that keeps the game-music download and the artwork enrich from overlapping: shared among enrich operations, exclusive for the download, both directions decided in one place.
 */
class ArtworkOperationGateTest {
    private val gate = ArtworkOperationGate()

    /** The process gate is a singleton: whatever a test takes it gives back, so no test poisons the next. */
    @After
    fun processGateIsFree() {
        assertEquals(ArtworkOperation.NONE, ArtworkOperationGate.process.active.value)
    }

    @Test
    fun `an idle gate admits either kind`() {
        assertEquals(ArtworkOperation.NONE, gate.active.value)
        assertNotNull(gate.tryClaimEnrich()?.also { it.release() })
        assertNotNull(gate.tryClaimGameMusic()?.also { it.release() })
        assertEquals(ArtworkOperation.NONE, gate.active.value)
    }

    @Test
    fun `enrich claims are shared, so a batch's own per-game steps need no way around the gate`() {
        val batch = gate.tryClaimEnrich()
        val perGameStep = gate.tryClaimEnrich()
        val settingsDownload = gate.tryClaimEnrich()
        assertNotNull(batch)
        assertNotNull(perGameStep)
        assertNotNull(settingsDownload)
        assertEquals(ArtworkOperation.ENRICH, gate.active.value)

        perGameStep!!.release()
        settingsDownload!!.release()
        assertEquals("one hold is still standing", ArtworkOperation.ENRICH, gate.active.value)
        batch!!.release()
        assertEquals(ArtworkOperation.NONE, gate.active.value)
    }

    @Test
    fun `the game-music download is refused while any enrich holds the gate`() {
        val enrich = gate.tryClaimEnrich()!!
        assertNull(gate.tryClaimGameMusic())
        assertEquals(ArtworkOperation.ENRICH, gate.active.value)
        enrich.release()
        assertNotNull(gate.tryClaimGameMusic())
    }

    @Test
    fun `every enrich entry is refused while the game-music download holds the gate`() {
        val music = gate.tryClaimGameMusic()!!
        assertEquals(ArtworkOperation.GAME_MUSIC, gate.active.value)
        assertNull("Settings Download, the first-run batch and a per-game Enrich all claim through here", gate.tryClaimEnrich())
        assertNull("one download at a time: the batch and the per-game action share one claim", gate.tryClaimGameMusic())
        music.release()
        assertEquals(ArtworkOperation.NONE, gate.active.value)
        assertNotNull(gate.tryClaimEnrich())
    }

    @Test
    fun `a claim releases once however many ends it has`() {
        val first = gate.tryClaimEnrich()!!
        val second = gate.tryClaimEnrich()!!
        first.release()
        first.release()
        first.release()
        assertEquals(
            "a second release of the same claim must not take the other holder's hold",
            ArtworkOperation.ENRICH,
            gate.active.value,
        )
        second.release()
        assertEquals(ArtworkOperation.NONE, gate.active.value)

        val music = gate.tryClaimGameMusic()!!
        music.release()
        music.release()
        assertNotNull("and the gate is not left negative or stuck", gate.tryClaimEnrich())
    }

    // ── every real entry claims the process gate, before anything else ───────────────────────────────

    @Test
    fun `a per-game Enrich is refused while the download runs and takes nothing when it is not`() = runBlocking {
        val music = ArtworkOperationGate.process.tryClaimGameMusic()!!
        try {
            assertEquals(
                ArtworkFetchController.OneResult.Busy,
                ArtworkFetchController.fetchOneArtwork(ContextWrapper(null), "ps1/game"),
            )
        } finally {
            music.release()
        }
        // With the gate free the same call is decided by its own rules (here: the art gate is closed) and gives its claim back.
        assertEquals(
            ArtworkFetchController.OneResult.GateClosed,
            ArtworkFetchController.fetchOneArtwork(ContextWrapper(null), "ps1/game"),
        )
        assertEquals(ArtworkOperation.NONE, ArtworkOperationGate.process.active.value)
    }

    @Test
    fun `Settings Download answers busy while the download runs and gives its claim back otherwise`() = runBlocking {
        val music = ArtworkOperationGate.process.tryClaimGameMusic()!!
        try {
            val refused = ArtworkFetchController.fetchAllArtwork(ContextWrapper(null))
            assertTrue(refused.busy)
            assertEquals(0, refused.total)
        } finally {
            music.release()
        }
        val closed = ArtworkFetchController.fetchAllArtwork(ContextWrapper(null))
        assertFalse(closed.busy)
        assertEquals(ArtworkOperation.NONE, ArtworkOperationGate.process.active.value)
    }

    @Test
    fun `the download cannot start while a per-game Enrich is held either`() {
        val enrich = ArtworkOperationGate.process.tryClaimEnrich()!!
        try {
            assertNull(ArtworkOperationGate.process.tryClaimGameMusic())
        } finally {
            enrich.release()
        }
    }

    private fun source(name: String): String = File(
        listOf(
            "app/src/main/kotlin/com/gruning/mimir/rewrite/$name",
            "src/main/kotlin/com/gruning/mimir/rewrite/$name",
        ).first { File(it).isFile },
    ).readText()

    @Test
    fun `each entry claims before any work and releases on every way out`() {
        val fetch = source("ArtworkFetchController.kt")
        val all = fetch.substringAfter("suspend fun fetchAllArtwork(").substringBefore("private suspend fun fetchAllArtworkClaimed(")
        assertTrue(
            "Settings Download claims, then runs, then releases in a finally",
            all.indexOf("tryClaimEnrich()") in 0 until all.indexOf("fetchAllArtworkClaimed(") &&
                all.contains("} finally {\n            claim.release()"),
        )
        val one = fetch.substringAfter("explicitPress: Boolean = false,\n    ): OneResult {").substringBefore("private suspend fun fetchOneArtworkClaimed(")
        assertTrue("the slice is the per-game entry, not the whole file", one.length in 100..1_500)
        assertTrue(
            "a per-game Enrich claims, then runs, then releases in a finally",
            one.indexOf("tryClaimEnrich()") in 0 until one.indexOf("fetchOneArtworkClaimed(") &&
                one.contains("} finally {\n            claim.release()"),
        )
        val claimed = fetch.substringAfter("private suspend fun fetchAllArtworkClaimed(").substringBefore("Compatibility entry point retained")
        assertTrue("the quota refresh happens inside the claim", claimed.contains("refreshAccountMaxThreads(context)"))

        val coordinator = source("MimirArtworkCoordinator.kt")
        val firstRun = coordinator.substringAfter("private fun startNewGamesFetch(").substringBefore("private fun publishProgress(")
        assertTrue(
            "the first-run batch claims before its quota refresh and releases from a completion handler",
            firstRun.indexOf("tryClaimEnrich()") in 0 until firstRun.indexOf("refreshAccountMaxThreads(context)") &&
                firstRun.contains("job.invokeOnCompletion { claim.release() }"),
        )
        val music = coordinator.substringAfter("private fun claimAndLaunchGameMusic(").substringBefore("private fun currentGameMusicBlock(")
        assertTrue(
            "the download claims before it launches and releases from a completion handler",
            music.indexOf("tryClaimGameMusic()") in 0 until music.indexOf("scope.launch") &&
                music.contains("job.invokeOnCompletion { claim.release() }"),
        )
    }
}
