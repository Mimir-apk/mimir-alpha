package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.library.ChangedMedia
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The game-music download's rules, on the JVM: who is eligible, how a step is
 * counted, what cancel and resume mean, what a per-game press may touch. The effects arrive as parameters, so none of
 * this needs a network, a codec or a database.
 */
class GameMusicBatchTest {
    private fun changed(n: Int) = ChangedMedia(GameId("ps1/game-$n"), sourceDocumentUri = null, managedPath = "/roms/game-$n.gba")

    // ── which games ──────────────────────────────────────────────────────────────────────────────────

    private fun eligibility(
        platformId: String = "ps1",
        hasSound: Boolean = false,
        writeAllowed: Boolean = true,
        audioCooling: Boolean = false,
        gameCooling: Boolean = false,
    ) = gameMusicEligibility(
        platformId = platformId,
        hasSound = { hasSound },
        writeAllowed = { writeAllowed },
        audioCoolingDown = { audioCooling },
        gameCoolingDown = { gameCooling },
    )

    @Test
    fun `a game on a covered platform with no sound, a writable folder and no cooldown is eligible`() {
        assertEquals(GameMusicEligibility.ELIGIBLE, eligibility())
    }

    @Test
    fun `PC games and platforms ScreenScraper has no id for are never asked`() {
        assertEquals(GameMusicEligibility.PC, eligibility(platformId = "pc_games"))
        assertEquals(GameMusicEligibility.NOT_COVERED, eligibility(platformId = "no-such-platform"))
    }

    @Test
    fun `a game that has its sound is never asked again, and the cooldowns and the folder decide the rest`() {
        assertEquals(GameMusicEligibility.HAS_SOUND, eligibility(hasSound = true))
        assertEquals(GameMusicEligibility.HAS_SOUND, eligibility(hasSound = true, audioCooling = true, writeAllowed = false))
        assertEquals(GameMusicEligibility.WRITE_BLOCKED, eligibility(writeAllowed = false))
        assertEquals(GameMusicEligibility.COOLING_DOWN, eligibility(audioCooling = true))
        assertEquals("the game-level no-match negative cools the sound down too", GameMusicEligibility.COOLING_DOWN, eligibility(gameCooling = true))
    }

    @Test
    fun `the cheap rules decide before any file or store is touched`() {
        var touched = 0
        val verdict = gameMusicEligibility(
            platformId = "pc_games",
            hasSound = { touched++; false },
            writeAllowed = { touched++; true },
            audioCoolingDown = { touched++; false },
            gameCoolingDown = { touched++; false },
        )
        assertEquals(GameMusicEligibility.PC, verdict)
        assertEquals("a PC game never costs a folder probe", 0, touched)
    }

    @Test
    fun `the plan counts have and total over the games a sound could exist for and keeps the library order`() {
        val verdicts = mapOf(
            "a" to GameMusicEligibility.HAS_SOUND,
            "b" to GameMusicEligibility.ELIGIBLE,
            "c" to GameMusicEligibility.PC,
            "d" to GameMusicEligibility.COOLING_DOWN,
            "e" to GameMusicEligibility.ELIGIBLE,
            "f" to GameMusicEligibility.NOT_COVERED,
            "g" to GameMusicEligibility.WRITE_BLOCKED,
        )
        val plan = planGameMusic(verdicts.keys.toList()) { verdicts.getValue(it) }

        assertEquals(listOf("b", "e"), plan.eligible)
        assertEquals("a, b, d, e, g could have one; c and f never can", 5, plan.total)
        assertEquals(1, plan.have)
    }

    /** Pressing again resumes with exactly the games still missing: the plan is recomputed from what is on disk. */
    @Test
    fun `resuming asks only about the games still missing`() {
        val onDisk = mutableSetOf<String>()
        val library = listOf("a", "b", "c", "d")
        fun plan() = planGameMusic(library) { if (it in onDisk) GameMusicEligibility.HAS_SOUND else GameMusicEligibility.ELIGIBLE }

        assertEquals(library, plan().eligible)
        onDisk += listOf("a", "c") // an earlier, stopped run wrote these two
        val resumed = plan()
        assertEquals(listOf("b", "d"), resumed.eligible)
        assertEquals(2, resumed.have)
        assertEquals(4, resumed.total)
    }

    // ── how a step is counted ────────────────────────────────────────────────────────────────────────

    @Test
    fun `every way a sound step can end lands in exactly one count`() {
        val expected = mapOf(
            LaunchAudioStepOutcome.Written to GameMusicStepOutcome.WRITTEN,
            LaunchAudioStepOutcome.NoAudioTrack to GameMusicStepOutcome.SKIPPED,
            LaunchAudioStepOutcome.UnsupportedCodec to GameMusicStepOutcome.SKIPPED,
            LaunchAudioStepOutcome.RemuxFailed to GameMusicStepOutcome.SKIPPED,
            LaunchAudioStepOutcome.TooLarge to GameMusicStepOutcome.SKIPPED,
            LaunchAudioStepOutcome.NotWritten to GameMusicStepOutcome.SKIPPED,
            LaunchAudioStepOutcome.DownloadUnavailable to GameMusicStepOutcome.FAILED,
            LaunchAudioStepOutcome.WriteFailed to GameMusicStepOutcome.FAILED,
            LaunchAudioStepOutcome.SkippedByBreaker to GameMusicStepOutcome.FAILED,
            LaunchAudioStepOutcome.StepFailed to GameMusicStepOutcome.FAILED,
            LaunchAudioStepOutcome.TranscodeFailed to GameMusicStepOutcome.FAILED,
        )
        assertEquals("a new outcome must decide which count it belongs to", LaunchAudioStepOutcome.entries.toSet(), expected.keys)
        expected.forEach { (outcome, count) -> assertEquals("$outcome", count, gameMusicOutcomeFor(outcome)) }
        assertEquals("a step that never reported is a failure, not a skip", GameMusicStepOutcome.FAILED, gameMusicOutcomeFor(null))
    }

    @Test
    fun `a clip that cannot become a sound is a skip exactly when it records a negative`() {
        LaunchAudioStepOutcome.entries.filter { audioStepRecordsNegative(it) }.forEach {
            assertEquals("$it records a negative, so the next press would skip it too", GameMusicStepOutcome.SKIPPED, gameMusicOutcomeFor(it))
        }
    }

    // ── the per-game step: what it may touch ─────────────────────────────────────────────────────────

    private class Effects(
        var hasSound: Boolean = false,
        var lookup: GameMusicLookup = GameMusicLookup.Loaded("https://example.test/clip"),
        var cut: GameMusicCut = GameMusicCut(null, LaunchAudioStepOutcome.Written),
    ) {
        var lookups = 0
        var cuts = 0
        var clearedGame = 0
        var recordedGame = 0
        var recordedNoClip = 0
        var cutUrl: String? = null
    }

    private fun step(forced: Boolean, e: Effects): GameMusicStepResult = runBlocking {
        gameMusicStepWith(
            forced = forced,
            alreadyHasSound = { e.hasSound },
            lookup = { e.lookups++; e.lookup },
            clearGameNegative = { e.clearedGame++ },
            recordGameNegative = { e.recordedGame++ },
            recordNoClipNegative = { e.recordedNoClip++ },
            cutSound = { url -> e.cuts++; e.cutUrl = url; e.cut },
        )
    }

    @Test
    fun `a sound already there costs no lookup and is never replaced`() {
        val e = Effects(hasSound = true)
        val result = step(forced = true, e)
        assertEquals(GameMusicStepOutcome.SKIPPED, result.outcome)
        assertEquals(0, e.lookups)
        assertEquals(0, e.cuts)
    }

    @Test
    fun `a matched game with a clip is cut once and counted as written`() {
        val e = Effects(cut = GameMusicCut(changed(1), LaunchAudioStepOutcome.Written))
        val result = step(forced = false, e)
        assertEquals(GameMusicStepOutcome.WRITTEN, result.outcome)
        assertEquals(changed(1), result.changed)
        assertEquals("one fresh lookup, then one cut from that record's clip", 1, e.lookups)
        assertEquals("https://example.test/clip", e.cutUrl)
        assertEquals("a verified match clears the game-level no-match negative", 1, e.clearedGame)
        assertEquals(0, e.recordedGame)
        assertEquals(0, e.recordedNoClip)
    }

    @Test
    fun `a record with no clip records the sound's own negative and cuts nothing`() {
        val e = Effects(lookup = GameMusicLookup.Loaded(clipUrl = null))
        val result = step(forced = false, e)
        assertEquals(GameMusicStepOutcome.SKIPPED, result.outcome)
        assertEquals(1, e.recordedNoClip)
        assertEquals(0, e.cuts)
        assertNull(result.changed)
    }

    @Test
    fun `an empty answer cools the game down for a batch and never for an explicit press`() {
        val batch = Effects(lookup = GameMusicLookup.Empty)
        assertEquals(GameMusicStepOutcome.SKIPPED, step(forced = false, batch).outcome)
        assertEquals(1, batch.recordedGame)

        val press = Effects(lookup = GameMusicLookup.Empty)
        assertEquals(GameMusicStepOutcome.SKIPPED, step(forced = true, press).outcome)
        assertEquals("a press is never a reason to cool a game down", 0, press.recordedGame)
        assertEquals(0, press.cuts)
    }

    @Test
    fun `an unsupported platform records nothing, and a failed lookup is a failure that the breaker counts`() {
        val unsupported = Effects(lookup = GameMusicLookup.PlatformUnsupported)
        assertEquals(GameMusicStepOutcome.SKIPPED, step(forced = false, unsupported).outcome)
        assertEquals(0, unsupported.recordedGame + unsupported.recordedNoClip + unsupported.clearedGame)

        val failed = Effects(lookup = GameMusicLookup.Failed())
        val result = step(forced = false, failed)
        assertEquals(GameMusicStepOutcome.FAILED, result.outcome)
        assertTrue(result.lookupFailed)
        assertEquals(0, failed.recordedGame + failed.recordedNoClip)
        assertEquals(0, failed.cuts)
    }

    @Test
    fun `a transient failure after the lookup is a failure that records no negative`() {
        val e = Effects(cut = GameMusicCut(null, LaunchAudioStepOutcome.DownloadUnavailable))
        val result = step(forced = false, e)
        assertEquals(GameMusicStepOutcome.FAILED, result.outcome)
        assertFalse("the provider answered, so this is not a lookup failure", result.lookupFailed)
        assertEquals(0, e.recordedNoClip)
    }

    // ── the batch ────────────────────────────────────────────────────────────────────────────────────

    private fun run(
        items: List<Int>,
        concurrency: Int = 1,
        step: suspend (Int) -> GameMusicStepResult,
        progress: MutableList<GameMusicTally> = mutableListOf(),
        published: MutableList<ChangedMedia> = mutableListOf(),
    ): GameMusicTally = runBlocking {
        runGameMusicBatch(
            items = items,
            concurrency = concurrency,
            step = step,
            onProgress = { progress += it },
            publish = { published += it },
        )
    }

    @Test
    fun `the tally counts written, skipped and failed separately and reports every step`() {
        val progress = mutableListOf<GameMusicTally>()
        val published = mutableListOf<ChangedMedia>()
        val tally = run(
            items = (1..6).toList(),
            progress = progress,
            published = published,
            step = { n ->
                when (n) {
                    1, 2 -> GameMusicStepResult(GameMusicStepOutcome.WRITTEN, changed = changed(n))
                    3 -> GameMusicStepResult(GameMusicStepOutcome.SKIPPED)
                    4 -> GameMusicStepResult(GameMusicStepOutcome.SKIPPED)
                    5 -> GameMusicStepResult(GameMusicStepOutcome.FAILED)
                    else -> GameMusicStepResult(GameMusicStepOutcome.WRITTEN, changed = changed(n))
                }
            },
        )
        assertEquals(GameMusicTally(done = 6, total = 6, written = 3, skipped = 2, failed = 1), tally)
        assertEquals("the opening 0 of 6, then a report when each game starts and one when it ends", 13, progress.size)
        assertEquals(GameMusicTally(total = 6), progress.first())
        assertEquals(listOf(1, 2, 6).map(::changed), published)
    }

    @Test
    fun `three failed lookups in a row stop the lookups and count the rest as failed without calling the step`() {
        val called = mutableListOf<Int>()
        val tally = run(
            items = (1..8).toList(),
            step = { n ->
                called += n
                GameMusicStepResult(GameMusicStepOutcome.FAILED, lookupFailed = true)
            },
        )
        assertEquals(listOf(1, 2, 3), called)
        assertEquals(GameMusicTally(done = 8, total = 8, failed = 8), tally)
    }

    @Test
    fun `a success between failures keeps the lookups going`() {
        val called = mutableListOf<Int>()
        val tally = run(
            items = (1..6).toList(),
            step = { n ->
                called += n
                if (n % 3 == 0) GameMusicStepResult(GameMusicStepOutcome.SKIPPED) else GameMusicStepResult(GameMusicStepOutcome.FAILED, lookupFailed = true)
            },
        )
        assertEquals((1..6).toList(), called)
        assertEquals(GameMusicTally(done = 6, total = 6, skipped = 2, failed = 4), tally)
    }

    @Test
    fun `an unexpected throw in one game is that game's failure and spares its siblings`() {
        val tally = run(
            items = listOf(1, 2, 3),
            step = { n -> if (n == 2) error("codec exploded") else GameMusicStepResult(GameMusicStepOutcome.WRITTEN, changed = changed(n)) },
        )
        assertEquals(GameMusicTally(done = 3, total = 3, written = 2, failed = 1), tally)
    }

    /**
     * Cancel stops new work: the game in flight is cancelled where it stands and is not counted, nothing after it starts,
     * and the sounds already written are still published so they are not invisible until a restart.
     */
    @Test
    fun `cancelling starts no new game, counts none it interrupted, and still publishes what was written`() = runBlocking {
        val started = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val progress = java.util.Collections.synchronizedList(mutableListOf<GameMusicTally>())
        val published = java.util.Collections.synchronizedList(mutableListOf<ChangedMedia>())
        val job = launch {
            runGameMusicBatch(
                items = (1..10).toList(),
                concurrency = 1,
                step = { n ->
                    started += n
                    if (n == 3) awaitCancellation()
                    GameMusicStepResult(GameMusicStepOutcome.WRITTEN, changed = changed(n))
                },
                onProgress = { progress += it },
                publish = { published += it },
            )
        }
        withTimeout(5_000) { while (3 !in started.toList()) delay(5) }

        job.cancelAndJoin()

        assertEquals("nothing after the interrupted game started", listOf(1, 2, 3), started.toList())
        assertEquals("the interrupted game is not counted", 2, progress.last().done)
        assertEquals(2, progress.last().written)
        assertEquals(listOf(1, 2).map(::changed), published.toList())
    }

    @Test
    fun `a stopped run followed by a resumed one writes every game once and never one twice`() = runBlocking {
        val onDisk = java.util.Collections.synchronizedSet(mutableSetOf<Int>())
        val writes = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val library = (1..6).toList()
        fun plan() = planGameMusic(library) { if (it in onDisk) GameMusicEligibility.HAS_SOUND else GameMusicEligibility.ELIGIBLE }

        val firstRun = launch {
            runGameMusicBatch(
                items = plan().eligible,
                concurrency = 1,
                step = { n ->
                    if (n == 4) awaitCancellation()
                    onDisk += n
                    writes += n
                    GameMusicStepResult(GameMusicStepOutcome.WRITTEN, changed = changed(n))
                },
                onProgress = {},
                publish = {},
            )
        }
        withTimeout(5_000) { while (onDisk.size < 3) delay(5) }
        delay(50)
        firstRun.cancelAndJoin()
        assertEquals(listOf(1, 2, 3), writes.toList())

        val resumed = plan()
        assertEquals("exactly the games still missing", listOf(4, 5, 6), resumed.eligible)
        runGameMusicBatch(
            items = resumed.eligible,
            concurrency = 1,
            step = { n ->
                onDisk += n
                writes += n
                GameMusicStepResult(GameMusicStepOutcome.WRITTEN, changed = changed(n))
            },
            onProgress = {},
            publish = {},
        )
        assertEquals((1..6).toList(), writes.toList())
        assertTrue(plan().eligible.isEmpty())
    }

    @Test
    fun `an empty batch reports its opening and finishes`() {
        val progress = mutableListOf<GameMusicTally>()
        val tally = run(items = emptyList(), progress = progress, step = { error("nothing to step") })
        assertEquals(GameMusicTally(), tally)
        assertEquals(listOf(GameMusicTally()), progress)
    }

    // ── the card's reasons ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `the block names the cause the player can act on`() {
        assertEquals(GameMusicBlock.NotConnected, gameMusicBlock(connected = false, activity = ArtworkOperation.NONE))
        assertEquals("not connected comes first", GameMusicBlock.NotConnected, gameMusicBlock(connected = false, activity = ArtworkOperation.ENRICH))
        assertEquals(GameMusicBlock.EnrichRunning, gameMusicBlock(connected = true, activity = ArtworkOperation.ENRICH))
        assertNull(gameMusicBlock(connected = true, activity = ArtworkOperation.NONE))
        assertNull("the download's own claim is not a reason to block its own row", gameMusicBlock(connected = true, activity = ArtworkOperation.GAME_MUSIC))
    }

    @Test
    fun `the block swaps into every state and nothing else changes`() {
        val states = listOf(
            GameMusicDownloadState.Idle(have = 3, total = 9),
            GameMusicDownloadState.Running(done = 2, total = 6, written = 1),
            GameMusicDownloadState.Finished(written = 4, skipped = 1, failed = 2),
        )
        states.forEach { state ->
            assertEquals(GameMusicBlock.EnrichRunning, state.withBlock(GameMusicBlock.EnrichRunning).block)
            assertNull(state.withBlock(GameMusicBlock.NotConnected).withBlock(null).block)
            assertEquals(state, state.withBlock(GameMusicBlock.NotConnected).withBlock(null))
        }
    }

    // ── own meter, own breaker, no URL ───────────────────────────────────────────────────────────────

    private fun source(name: String): String = File(
        listOf(
            "app/src/main/kotlin/com/gruning/mimir/rewrite/$name",
            "src/main/kotlin/com/gruning/mimir/rewrite/$name",
        ).first { File(it).isFile },
    ).readText()

    @Test
    fun `the download has a meter and a breaker of its own, labelled game-music`() {
        assertEquals("game-music", ArtworkFetchController.EnrichBatchGameMusic)
        assertFalse(
            "a meter shared with the Enrich would report the other's work",
            GameMusicBatch.cost === ArtworkFetchController.enrichCost,
        )
        val batch = source("GameMusicBatch.kt")
        assertTrue(batch.contains("cost.begin(ArtworkFetchController.EnrichBatchGameMusic,"))
        assertTrue(batch.contains("emitBatchCost("))
        assertTrue("the breaker is armed per run and disarmed on every way out", batch.contains("breaker.begin()") && batch.contains("} finally {\n            breaker.end()"))
        assertTrue("and the stage folder is cleaned on every way out", batch.contains("clearLaunchAudioStage(context)"))
    }

    @Test
    fun `the lookup counts into the meter it is given and only the Enrich meter feeds the old log counter`() {
        val fetch = source("ArtworkFetchController.kt")
        val lookup = fetch.substringAfter("private suspend fun verifiedLookupOutcome(").substringBefore("val record = when (outcome)")
        assertTrue(lookup.contains("cost: EnrichCostAccumulator = enrichCost"))
        assertTrue(lookup.contains("if (cost === enrichCost) lookups.incrementAndGet()"))
        assertTrue(lookup.contains("cost.addLookup(cost.now() - lookupStartedAt)"))
        assertFalse("no direct use of the global meter in the lookup body", lookup.replace("cost: EnrichCostAccumulator = enrichCost", "").replace("cost === enrichCost", "").contains("enrichCost"))
    }

    @Test
    fun `the download writes no log line of its own and never names a URL, a credential or a title`() {
        val batch = source("GameMusicBatch.kt")
        assertFalse("GameMusicBatch.kt must not log", Regex("""\bLog\.[a-z]\(""").containsMatchIn(batch) || batch.contains("println("))
        val coordinator = source("MimirArtworkCoordinator.kt")
        val section = coordinator.substringAfter("// ── Settings > Music: the game-music download").substringBefore("private var connectJob")
        assertFalse("the download section must not log", Regex("""\bLog\.[a-z]\(""").containsMatchIn(section))
    }

    @Test
    fun `the per-game press goes through the sound step only and never the artwork body`() {
        val batch = source("GameMusicBatch.kt")
        val perGame = batch.substringAfter("suspend fun runForGame(").substringBefore("private fun classify(")
        assertFalse(perGame.contains("fetchArtworkForGame"))
        assertFalse(perGame.contains("fetchOneArtwork"))
        assertTrue("it bypasses the cooldowns by not asking for them", perGame.contains("audioCoolingDown = { false }") && perGame.contains("gameCoolingDown = { false }"))
        assertTrue(perGame.contains("forced = true"))
        val step = source("ArtworkFetchController.kt").substringAfter("internal suspend fun fetchGameMusicForGame(").substringBefore("internal suspend fun fetchLaunchAudio(")
        assertFalse("no art, no metadata refresh, no IGDB in the sound step", step.contains("fetchImage") || step.contains("fetchAndPersistIgdb") || step.contains("fetchArtworkForGame"))
        assertFalse("no other slot's negative is touched", step.contains("ManagedMediaSlot.COVER") || step.contains("ManagedMediaSlot.LOGO") || step.contains("ManagedMediaSlot.HERO") || step.contains("ManagedMediaSlot.PHYSICAL"))
    }

    @Test
    fun `staging files go with a cancelled step and nothing is written over a present sound`() {
        val fetch = source("ArtworkFetchController.kt")
        val step = fetch.substringAfter("internal suspend fun fetchLaunchAudioWith(").substringBefore("// ── What a batch cost")
        assertTrue("both temp files are deleted in a finally, which a cancellation reaches", step.contains("} finally {\n            part?.delete()\n            sound?.delete()"))
        val install = fetch.substringAfter("private suspend fun installDownloadedMediaAtCurrentGame(").substringBefore("private suspend fun clearStaleUnattributedLogo(")
        assertTrue("create-only: the writer is never asked to overwrite a sound", !install.contains("overwrite = true"))
    }
    // ── why games failed, and what is being looked up (combined49: "4 failed" with no reason, a card that sat still) ─────────

    @Test
    fun `a failed lookup carries the reason its answer gave into the step result`() {
        val dailyLimit = Effects(lookup = GameMusicLookup.Failed(GameMusicFailure.DAILY_LIMIT))
        val result = step(forced = false, dailyLimit)
        assertEquals(GameMusicStepOutcome.FAILED, result.outcome)
        assertTrue(result.lookupFailed)
        assertEquals(GameMusicFailure.DAILY_LIMIT, result.failure)

        assertNull("a lookup that was never asked (the gate closed) names no cause", step(forced = false, Effects(lookup = GameMusicLookup.Failed())).failure)
    }

    @Test
    fun `each way a sound step fails after the lookup maps to one reason, and a step that did not fail has none`() {
        fun reasonOf(outcome: LaunchAudioStepOutcome): GameMusicFailure? =
            step(forced = false, Effects(cut = GameMusicCut(null, outcome))).failure

        assertEquals(GameMusicFailure.NO_ANSWER, reasonOf(LaunchAudioStepOutcome.DownloadUnavailable))
        assertEquals(GameMusicFailure.NO_ANSWER, reasonOf(LaunchAudioStepOutcome.SkippedByBreaker))
        assertEquals(GameMusicFailure.COULDNT_SAVE, reasonOf(LaunchAudioStepOutcome.WriteFailed))
        assertEquals(GameMusicFailure.COULDNT_SAVE, reasonOf(LaunchAudioStepOutcome.TranscodeFailed))
        assertEquals(GameMusicFailure.COULDNT_SAVE, reasonOf(LaunchAudioStepOutcome.StepFailed))
        // A skip or a write is not a failure, so it carries no reason.
        for (outcome in listOf(LaunchAudioStepOutcome.Written, LaunchAudioStepOutcome.NoAudioTrack, LaunchAudioStepOutcome.TooLarge, LaunchAudioStepOutcome.NotWritten)) {
            assertNull("$outcome", reasonOf(outcome))
        }
    }

    @Test
    fun `the source's classification maps onto the card's reasons one for one`() {
        val mapped = com.gruning.mimir.rewrite.core.metadata.online.ScreenScraperLookupFailure.entries.map(::gameMusicFailureOfLookup)
        assertEquals(
            listOf(
                GameMusicFailure.NO_ANSWER,
                GameMusicFailure.DAILY_LIMIT,
                GameMusicFailure.BUSY,
                GameMusicFailure.SIGN_IN_REFUSED,
                GameMusicFailure.BUILD_REFUSED,
            ),
            mapped,
        )
    }

    @Test
    fun `games the breaker skips carry the reason of the failures that tripped it, and so does the tally`() {
        val called = mutableListOf<Int>()
        val tally = run(
            items = (1..6).toList(),
            step = { n ->
                called += n
                GameMusicStepResult(GameMusicStepOutcome.FAILED, lookupFailed = true, failure = GameMusicFailure.DAILY_LIMIT)
            },
        )
        assertEquals("only three lookups were made", listOf(1, 2, 3), called)
        assertEquals(6, tally.failed)
        assertEquals("the skipped three carry the same cause, so the card names the cause and not a guess", GameMusicFailure.DAILY_LIMIT, tally.failure)
    }

    @Test
    fun `the tally's reason is the one most failed games share`() {
        val tally = run(
            items = (1..4).toList(),
            step = { n ->
                when (n) {
                    1 -> GameMusicStepResult(GameMusicStepOutcome.FAILED, failure = GameMusicFailure.COULDNT_SAVE)
                    2, 3 -> GameMusicStepResult(GameMusicStepOutcome.FAILED, failure = GameMusicFailure.NO_ANSWER)
                    else -> GameMusicStepResult(GameMusicStepOutcome.WRITTEN, changed = changed(n))
                }
            },
        )
        assertEquals(GameMusicFailure.NO_ANSWER, tally.failure)
        assertNull("a clean run names no cause", run(items = listOf(1), step = { GameMusicStepResult(GameMusicStepOutcome.WRITTEN, changed = changed(1)) }).failure)
    }

    @Test
    fun `the card is told which game is being looked up the moment its lookup starts`() {
        val progress = mutableListOf<GameMusicTally>()
        val titles = mapOf(1 to "Chrono Trigger", 2 to "The Legend of Zelda: A Link to the Past — Hyrule Warriors Special Edition")
        runBlocking {
            runGameMusicBatch(
                items = listOf(1, 2),
                concurrency = 1,
                step = { n -> GameMusicStepResult(GameMusicStepOutcome.SKIPPED).also { check(progress.last().currentIndex == n) { "the start was reported before the step ran" } } },
                onProgress = { progress += it },
                publish = {},
                titleOf = { n -> titles[n] },
            )
        }
        val starts = progress.filter { it.currentIndex > 0 }
        assertEquals(listOf(1, 2), starts.map { it.currentIndex })
        assertEquals("Chrono Trigger", starts[0].current)
        val second = starts[1].current!!
        assertTrue("a long title is cut to one line with an ellipsis: $second", second.length <= GameMusicNameMaxChars && second.endsWith("…"))
        assertEquals("between games, and at the end, nothing is in flight", 0, progress.last().currentIndex)
        assertNull(progress.last().current)
    }

    @Test
    fun `with several lookups in flight the card names the latest started, and the next when it ends`() = runBlocking {
        val progress = java.util.Collections.synchronizedList(mutableListOf<GameMusicTally>())
        val release = mapOf(1 to CompletableDeferred<Unit>(), 2 to CompletableDeferred<Unit>())
        val job = launch {
            runGameMusicBatch(
                items = listOf(1, 2),
                concurrency = 2,
                step = { n -> release.getValue(n).await(); GameMusicStepResult(GameMusicStepOutcome.SKIPPED) },
                onProgress = { progress += it },
                publish = {},
                titleOf = { n -> "Game $n" },
            )
        }
        withTimeout(5_000) { while (progress.none { it.currentIndex == 2 }) delay(5) }
        assertEquals("Game 2", progress.last().current)

        release.getValue(2).complete(Unit)
        withTimeout(5_000) { while (progress.last().done < 1) delay(5) }
        assertEquals("game 1 is the one still being looked up", 1, progress.last().currentIndex)
        assertEquals("Game 1", progress.last().current)

        release.getValue(1).complete(Unit)
        job.join()
        assertEquals(0, progress.last().currentIndex)
    }
}
