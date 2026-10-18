package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.AndroidPackageName
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A restarted process gets the kept game's discount back only on proof. The elapsed store's
 * orphan record must be open AND the task lookup (the one a HOME-parked session already uses) must find the emulator's task. Otherwise nothing is
 * seeded, and the game's stream is a stranger as before.
 */
class KeptGameRestartSeedTest {
    private val zelda = HomeShelfItemId.Emulated(GameId("catalog:n3ds:zelda-link-between-worlds"))
    private val session = HomeExternalSession(zelda, "token", 10_000L, bootCount = 3, placement = MimirGamePlacement.MAIN)
    private val launched = listOf(
        LaunchedGame("io.github.lime3ds.android", "op-1", gameId = zelda.gameId.value, launchedAtEpochMs = 100L),
        LaunchedGame("org.other.emulator", "op-2", gameId = "catalog:gba:other", launchedAtEpochMs = 200L),
    )

    /** The lookup, with the answers it will give in turn, and how often it was asked. */
    private class Gateway(vararg answers: VendorTaskOutcome) {
        private val queue = answers.toMutableList()
        val asked = mutableListOf<String>()
        suspend fun resolve(packageName: String): VendorTaskOutcome {
            asked += packageName
            return if (queue.size > 1) queue.removeAt(0) else queue.first()
        }
    }

    private fun seed(record: HomeExternalSession?, gateway: Gateway, pauses: MutableList<Long> = mutableListOf(), games: List<LaunchedGame> = launched): KeptGameSeed =
        runBlocking { keptGameSeed(record, games, gateway::resolve, pause = { pauses += it }) }

    // ── The three cases ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the record is open and the task is present, so the kept game's stream is discounted`() {
        val gateway = Gateway(VendorTaskOutcome.Found(taskId = 42))
        val result = seed(session, gateway)
        assertEquals(1, result.keptTasks)
        assertEquals("the lookup asks about the emulator the game launched", listOf("io.github.lime3ds.android"), gateway.asked)
        assertTrue(result.reason, result.reason.contains("io.github.lime3ds.android") && result.reason.contains("task found"))
    }

    @Test
    fun `the record is open and the task is gone, so nothing is seeded`() {
        val gateway = Gateway(VendorTaskOutcome.NoTask)
        val pauses = mutableListOf<Long>()
        val result = seed(session, gateway, pauses)
        assertEquals(0, result.keptTasks)
        assertEquals("proof of absence is not asked again", 1, gateway.asked.size)
        assertTrue("and does not wait", pauses.isEmpty())
    }

    @Test
    fun `there is no open record, so nothing is seeded and nothing is asked`() {
        val gateway = Gateway(VendorTaskOutcome.Found(taskId = 42))
        val result = seed(null, gateway)
        assertEquals(0, result.keptTasks)
        assertTrue("the gateway is not asked without a record", gateway.asked.isEmpty())
    }

    // ── The pure decision ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `only an open record with a found task counts a kept game`() {
        assertEquals(1, keptGameSeedCount(orphanOpen = true, taskOutcome = VendorTaskOutcome.Found(7)))
        assertEquals("the task is gone", 0, keptGameSeedCount(orphanOpen = true, taskOutcome = VendorTaskOutcome.NoTask))
        assertEquals("no answer is proof of nothing", 0, keptGameSeedCount(orphanOpen = true, taskOutcome = VendorTaskOutcome.Unavailable("timeout")))
        assertEquals("never asked", 0, keptGameSeedCount(orphanOpen = true, taskOutcome = null))
        assertEquals("no record", 0, keptGameSeedCount(orphanOpen = false, taskOutcome = VendorTaskOutcome.Found(7)))
    }

    @Test
    fun `a gateway that is not up yet is asked once more, then given up on`() {
        val pauses = mutableListOf<Long>()
        val late = Gateway(VendorTaskOutcome.Unavailable("not_ready"), VendorTaskOutcome.Found(9))
        assertEquals("the second ask is answered", 1, seed(session, late, pauses).keptTasks)
        assertEquals(2, late.asked.size)
        assertEquals("one wait between the asks", listOf(KeptGameSeedRetryDelayMs), pauses)

        val never = Gateway(VendorTaskOutcome.Unavailable("timeout"))
        val morePauses = mutableListOf<Long>()
        assertEquals("two asks and no more", 0, seed(session, never, morePauses).keptTasks)
        assertEquals(KeptGameSeedAttempts, never.asked.size)
        assertEquals(1, morePauses.size)
    }

    // ── Which package the record means ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `an emulated game's record is matched to its package by game id, newest launch first`() {
        assertEquals("io.github.lime3ds.android", keptGameSeedPackage(session, launched))
        val twice = launched + LaunchedGame("io.github.lime3ds.android.nightly", "op-3", gameId = zelda.gameId.value, launchedAtEpochMs = 300L)
        assertEquals("the newest launch of the game", "io.github.lime3ds.android.nightly", keptGameSeedPackage(session, twice))
    }

    @Test
    fun `a record whose game was never launched in this boot names no package, and nothing is asked`() {
        assertNull(keptGameSeedPackage(session, emptyList()))
        assertNull("another game's launch is not this one's", keptGameSeedPackage(session, launched.drop(1)))
        val gateway = Gateway(VendorTaskOutcome.Found(1))
        assertEquals(0, seed(session, gateway, games = emptyList()).keptTasks)
        assertTrue(gateway.asked.isEmpty())
    }

    @Test
    fun `only an emulated launch seeds, an Android game's or app's record names no package, and nothing is asked`() {
        // Suspended is armed by the launch controller for emulated launches alone, so an Android game or app never had a discount to lose.
        val androidGame = session.copy(itemId = HomeShelfItemId.AndroidGame(AndroidPackageName("com.example.game")))
        val androidApp = session.copy(itemId = HomeShelfItemId.AndroidApp(AndroidPackageName("com.example.app")))
        val remembered = listOf(
            LaunchedGame("com.example.game", "op-4", gameId = "com.example.game", launchedAtEpochMs = 400L),
            LaunchedGame("com.example.app", "op-5", gameId = "com.example.app", launchedAtEpochMs = 500L),
        )
        for (record in listOf(androidGame, androidApp)) {
            assertNull("${record.itemId}", keptGameSeedPackage(record, remembered))
            val gateway = Gateway(VendorTaskOutcome.Found(taskId = 7))
            assertEquals(0, seed(record, gateway, games = remembered).keptTasks)
            assertTrue("the gateway is not asked", gateway.asked.isEmpty())
        }
        assertEquals("the emulated record still seeds", "io.github.lime3ds.android", keptGameSeedPackage(session, launched))
    }

    // ── The wiring ───────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the elapsed store hands the record over once as it closes it, and the music wiring seeds the bed from it`() {
        val store = source("HomeExternalSessionElapsedStore.kt")
        val finish = store.substringAfter("suspend fun finishOrphanedMainReturn(").substringBefore("@Volatile")
        assertTrue("only after the record is written closed", finish.indexOf("val written = replace(") in 0 until finish.indexOf("orphanObserver?.invoke(session)"))
        assertTrue("a hand-off that cannot fail the finish", finish.contains("runCatching { orphanObserver?.invoke(session) }"))
        assertTrue(store.contains("var orphanObserver: ((HomeExternalSession) -> Unit)? = null"))

        val music = source("YourMusicEverywhere.kt")
        assertTrue(music.contains("HomeExternalSessionElapsedStore.orphanObserver = { session -> KeptGameRestartSeed.onOrphanRecord(app, session) }"))

        val seed = source("KeptGameRestartSeed.kt")
        assertTrue("the same lookup a kept session uses, on Main's display", seed.contains("gateway.resolveTaskOutcome(packageName, Display.DEFAULT_DISPLAY)"))
        assertTrue("the gateway is looked at before it is asked", seed.indexOf("PrivilegedUtilityStatus.READY") < seed.indexOf("gateway.resolveTaskOutcome("))
        assertTrue("bounded like the HOME press", seed.contains("answerWithin(HomeParkResolveTimeoutMs)"))
        assertTrue("seeds the media bed", seed.contains("MimirMediaBed.seedKeptGame(seed.keptTasks, strangerSessionPlaying)"))
        assertFalse("no new privileged call", seed.contains("PrivilegedUtilityGateway") || seed.contains("closeLaunchTarget") || seed.contains("removeTask"))
        assertTrue("a process-lifetime scope has its exception handler", seed.contains("CoroutineExceptionHandler"))
    }

    private fun source(name: String): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText() ?: error("$name not found from ${File(".").absolutePath}")
    }
}
