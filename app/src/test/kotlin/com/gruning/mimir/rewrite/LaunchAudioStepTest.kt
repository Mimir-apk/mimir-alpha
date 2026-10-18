package com.gruning.mimir.rewrite

import android.content.ContextWrapper
import com.gruning.mimir.rewrite.core.storage.entity.GameEntity
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The launch sound's download-remux-install step, end to end with every seam faked : slot
 * selection is pinned in [ArtworkFetchControllerTest], the remux itself in `LaunchAudioRemuxDeviceTest` on a device, and
 * this class pins everything between them: what is asked of the network, what is recorded, what is written, what is
 * cleaned up, and what the batch's cost line counts.
 *
 * Nothing here touches a network, a codec, Room or a real clock.
 */
class LaunchAudioStepTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val game = GameEntity(
        entryKey = "g-primary",
        gameId = "g",
        platformId = "snes",
        contentKind = "GAME",
        displayName = "Test Game.sfc",
        title = "Test Game",
        region = null,
        version = null,
        serial = null,
        titleCatalogKey = null,
        identitySource = "FILENAME",
        locationKind = "SAF",
        availability = "AVAILABLE",
        sizeBytes = 64,
        contentHash = null,
        sourceRootUri = "content://root",
        sourceDocumentUri = "content://root/g",
        managedPath = null,
        relativePath = "g.sfc",
        discNumber = 0,
        duplicateGroupId = "g",
        isPrimaryMember = true,
        duplicateGroupKind = null,
    )

    // ── harness ───────────────────────────────────────────────────────────────────────────────────

    private var now = 0L
    private val stage: File by lazy { File(folder.root, "stage") }
    private val cost = EnrichCostAccumulator { now }.also { it.begin("first-run", requestsBefore = 100) }

    private var downloadCalls = 0
    private var remuxCalls = 0
    private var probeCalls = 0
    private var transcodeCalls = 0
    private val downloadedUrls = mutableListOf<String>()
    private val downloadCaps = mutableListOf<Long>()
    private val downloadTargets = mutableListOf<File>()
    private val remuxInputs = mutableListOf<File>()
    private val transcodeInputs = mutableListOf<File>()
    private val durationCaps = mutableListOf<Long>()
    private val installed = mutableListOf<ByteArray>()
    private var negatives = 0
    private var clears = 0

    private val clipBytes = ByteArray(1_000) { it.toByte() }
    private val soundBytes = ByteArray(321) { (it * 3).toByte() }

    private var downloadResult: (File) -> ClipDownload = { target ->
        now += 30
        target.writeBytes(clipBytes)
        ClipDownload.Ok(clipBytes.size.toLong())
    }
    /** What the clip's audio track is, as the probe sees it: AAC by default, which takes the copy path. */
    private var probeResult: (File) -> ClipAudioProbe = { ClipAudioProbe.Track(LaunchAudioAacMime) }

    /** The AAC copy path ("remux"): what [LaunchAudioRemux.remuxAacTrack] stands for. */
    private var remuxResult: (File, File) -> LaunchAudioRemuxOutcome = { _, output ->
        now += 5
        output.writeBytes(soundBytes)
        LaunchAudioRemuxOutcome.Written(soundBytes.size.toLong(), samples = 3)
    }

    /** The decode-and-encode path: what [LaunchAudioTranscode.transcodeToAac] stands for. */
    private var transcodeResult: (File, File) -> LaunchAudioRemuxOutcome = { _, output ->
        now += 70
        output.writeBytes(soundBytes)
        LaunchAudioRemuxOutcome.Written(soundBytes.size.toLong(), samples = 9)
    }
    private var installResult: () -> ArtworkFetchController.FinalMediaWrite = {
        ArtworkFetchController.FinalMediaWrite(target = game)
    }

    private fun run(
        breaker: LaunchAudioBreaker = LaunchAudioBreaker().also { it.begin() },
        url: String = "https://www.screenscraper.fr/medias/clip.mp4",
    ): ArtworkFetchController.FinalMediaWrite = runBlocking {
        ArtworkFetchController.fetchLaunchAudioWith(
            clipUrl = url,
            stageDir = stage,
            download = { u, cap, target ->
                downloadCalls++
                downloadedUrls += u
                downloadCaps += cap
                downloadTargets += target
                downloadResult(target)
            },
            probe = { input ->
                probeCalls++
                probeResult(input)
            },
            copy = { input, output, maxDurationUs ->
                remuxCalls++
                remuxInputs += input
                durationCaps += maxDurationUs
                remuxResult(input, output)
            },
            transcode = { input, output, maxDurationUs ->
                transcodeCalls++
                transcodeInputs += input
                durationCaps += maxDurationUs
                transcodeResult(input, output)
            },
            install = { bytes ->
                installed += bytes
                installResult()
            },
            recordNegative = { negatives++ },
            clearNegative = { clears++ },
            cost = cost,
            breaker = breaker,
        )
    }

    private fun stageFiles(): List<String> = stage.listFiles()?.map { it.name }.orEmpty()

    // ── the happy path ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a clip with sound is streamed under the video cap remuxed installed once and cleaned up`() {
        val result = run()

        assertTrue(result.written)
        assertEquals("one download, no second request", 1, downloadCalls)
        assertEquals(listOf(ArtworkFetchController.MaxVideoBytes), downloadCaps)
        assertTrue("not the 8 MB image cap", downloadCaps.single() > ArtworkFetchController.MaxImageBytes)
        assertTrue("streamed to a .part file in the stage folder", downloadTargets.single().name.endsWith(".part"))
        assertEquals(stage, downloadTargets.single().parentFile)
        assertEquals("the remux reads the file the download wrote", downloadTargets, remuxInputs)
        assertEquals("exactly the remuxed sound is installed, once", 1, installed.size)
        assertTrue(soundBytes.contentEquals(installed.single()))
        assertEquals("the negative is cleared only by a written sound", 1, clears)
        assertEquals(0, negatives)
        assertEquals("both temp files are deleted", emptyList<String>(), stageFiles())
    }

    @Test
    fun `the cost line counts the clip its bytes and the time it took`() {
        run()

        val line = cost.summary(games = 1, matched = 1, wallMs = 99, requestsAfter = 104, audioBreakerTripped = false)
        assertTrue(line, line.contains(" audioClips=1 "))
        assertTrue(line, line.contains(" audioBytes=1000 "))
        assertTrue(line, line.contains(" audioDownloadMs=30 "))
        assertTrue(line, line.contains(" audioRemuxMs=5 audioTranscodeMs=0 "))
        assertTrue(line, line.contains(" audioMs=35 "))
        assertTrue(line, line.contains(" audioWritten=1 audioTranscoded=0 "))
        assertTrue(line, line.contains(" audioCodecs=audio/mp4a-latm:1 "))
        assertTrue(line, line.contains(" requestsTodayDelta=4"))
    }

    // ── the two paths: AAC is copied, anything else is transcoded (walk 32k) ──────────────────────────

    @Test
    fun `an AAC track takes the copy path and never the transcode`() {
        val result = run()

        assertTrue(result.written)
        assertEquals(1, probeCalls)
        assertEquals(1, remuxCalls)
        assertEquals("the fast path: no decode, no encode", 0, transcodeCalls)
        assertEquals(0, cost.audioOutcomeCount(LaunchAudioStepOutcome.UnsupportedCodec))
    }

    @Test
    fun `a track that is not AAC is transcoded and installed once, counted as a transcode and not a copy`() {
        probeResult = { ClipAudioProbe.Track("audio/mpeg") }

        val result = run()

        assertTrue(result.written)
        assertEquals(1, probeCalls)
        assertEquals("the copy path is not tried first", 0, remuxCalls)
        assertEquals(1, transcodeCalls)
        assertEquals("the transcode reads the file the download wrote", downloadTargets, transcodeInputs)
        assertEquals("exactly the transcoded sound is installed, once", 1, installed.size)
        assertTrue(soundBytes.contentEquals(installed.single()))
        assertEquals("a written sound clears the negative, however it was made", 1, clears)
        assertEquals(0, negatives)
        assertEquals("both temp files are deleted", emptyList<String>(), stageFiles())

        val line = cost.summary(games = 1, matched = 1, wallMs = 99, requestsAfter = 104, audioBreakerTripped = false)
        assertTrue("the transcode's time is its own figure: $line", line.contains(" audioRemuxMs=0 audioTranscodeMs=70 "))
        assertTrue(line, line.contains(" audioMs=100 "))
        assertTrue("a transcoded sound is a written sound, and is also counted as transcoded: $line", line.contains(" audioWritten=1 audioTranscoded=1 "))
        assertTrue(line, line.contains(" audioCodecs=audio/mpeg:1 "))
    }

    @Test
    fun `a clip the probe finds no audio track in is a negative and neither path runs`() {
        probeResult = { ClipAudioProbe.NoAudioTrack }

        val result = run()

        assertFalse(result.written)
        assertNull(result.target)
        assertFalse(result.writeBlocked)
        assertEquals(0, remuxCalls)
        assertEquals(0, transcodeCalls)
        assertEquals("nothing is installed", 0, installed.size)
        assertEquals(1, negatives)
        assertEquals(0, clears)
        assertEquals(emptyList<String>(), stageFiles())
        assertEquals(1, cost.audioOutcomeCount(LaunchAudioStepOutcome.NoAudioTrack))
        assertTrue(cost.summary(1, 0, 1, null, false).contains(" audioCodecs=none "))
    }

    @Test
    fun `a clip the probe cannot read is a bad-file negative, neither path runs, and no codec is tallied`() {
        probeResult = { ClipAudioProbe.Unreadable }

        val result = run()

        assertFalse(result.written)
        assertEquals(0, remuxCalls)
        assertEquals(0, transcodeCalls)
        assertEquals(1, negatives)
        assertEquals(1, cost.audioOutcomeCount(LaunchAudioStepOutcome.RemuxFailed))
        assertTrue(cost.summary(1, 0, 1, null, false).contains(" audioCodecs=none "))
    }

    @Test
    fun `a track the device has no decoder for records the negative and writes nothing`() {
        probeResult = { ClipAudioProbe.Track("audio/ac4") }
        transcodeResult = { _, _ -> LaunchAudioRemuxOutcome.UnsupportedCodec }

        val result = run()

        assertFalse(result.written)
        assertEquals(1, transcodeCalls)
        assertEquals(0, installed.size)
        assertEquals("a device fact about this clip, so it is remembered for the cooldown", 1, negatives)
        assertEquals(emptyList<String>(), stageFiles())
        assertEquals(1, cost.audioOutcomeCount(LaunchAudioStepOutcome.UnsupportedCodec))
        val line = cost.summary(1, 0, 1, null, false)
        assertTrue(line, line.contains(" audioNoDecoder=1 "))
        assertTrue("the codec that had no decoder is named in the tally: $line", line.contains(" audioCodecs=audio/ac4:1 "))
        assertTrue("and it is not a written transcode: $line", line.contains(" audioTranscoded=0 "))
    }

    @Test
    fun `a transcode that fails records the negative and its time is still counted`() {
        probeResult = { ClipAudioProbe.Track("audio/mpeg") }
        transcodeResult = { _, _ ->
            now += 40
            LaunchAudioRemuxOutcome.Failed
        }

        val result = run()

        assertFalse(result.written)
        assertEquals(0, installed.size)
        assertEquals(1, negatives)
        assertEquals(emptyList<String>(), stageFiles())
        assertEquals(1, cost.audioOutcomeCount(LaunchAudioStepOutcome.RemuxFailed))
        val line = cost.summary(1, 0, 1, null, false)
        assertTrue("the work a failed transcode cost is not lost: $line", line.contains(" audioTranscodeMs=40 "))
        assertTrue(line, line.contains(" audioTranscoded=0 "))
    }

    // ── transient trouble records nothing (walk 32l); facts about the clip still do ───────────────────────

    @Test
    fun `a transcode that hits transient trouble records no negative and counts in its own field`() {
        probeResult = { ClipAudioProbe.Track("audio/mpeg") }
        transcodeResult = { _, _ ->
            now += 40
            LaunchAudioRemuxOutcome.TransientFailure
        }

        val result = run()

        assertFalse(result.written)
        assertNull(result.target)
        assertEquals(0, installed.size)
        assertEquals("a codec having a bad moment says nothing about the clip", 0, negatives)
        assertEquals(0, clears)
        assertEquals(emptyList<String>(), stageFiles())
        assertFalse(audioStepRecordsNegative(LaunchAudioStepOutcome.TranscodeFailed))
        assertEquals(1, cost.audioOutcomeCount(LaunchAudioStepOutcome.TranscodeFailed))
        assertEquals("and it is not counted as a bad file", 0, cost.audioOutcomeCount(LaunchAudioStepOutcome.RemuxFailed))
        val line = cost.summary(1, 0, 1, null, false)
        assertTrue("the new field is the last one: $line", line.endsWith(" audioTranscodeFailed=1"))
        assertTrue("the work it cost is still counted: $line", line.contains(" audioTranscodeMs=40 "))
        assertTrue(line, line.contains(" audioTranscoded=0 "))
        assertTrue("the clip's codec is still tallied: $line", line.contains(" audioCodecs=audio/mpeg:1 "))
    }

    @Test
    fun `the facts about a clip still record a negative and are not counted as transient`() {
        // No audio track, no decoder on this device, a file the probe cannot read, a transcode that finds the file unusable.
        val cases = listOf<Pair<(File) -> ClipAudioProbe, LaunchAudioRemuxOutcome?>>(
            ({ _: File -> ClipAudioProbe.NoAudioTrack }) to null,
            ({ _: File -> ClipAudioProbe.Unreadable }) to null,
            ({ _: File -> ClipAudioProbe.Track("audio/ac4") }) to LaunchAudioRemuxOutcome.UnsupportedCodec,
            ({ _: File -> ClipAudioProbe.Track("audio/mpeg") }) to LaunchAudioRemuxOutcome.Failed,
        )
        for ((probe, transcodeAnswer) in cases) {
            negatives = 0
            probeResult = probe
            if (transcodeAnswer != null) transcodeResult = { _, _ -> transcodeAnswer }

            val result = run()

            assertFalse(result.written)
            assertEquals("a fact about the clip is remembered: $probe $transcodeAnswer", 1, negatives)
        }
        assertEquals("none of them is transient", 0, cost.audioOutcomeCount(LaunchAudioStepOutcome.TranscodeFailed))
    }

    @Test
    fun `a transient transcode does not trip the download breaker or hide the sound from the next game`() {
        probeResult = { ClipAudioProbe.Track("audio/mpeg") }
        transcodeResult = { _, _ -> LaunchAudioRemuxOutcome.TransientFailure }
        val breaker = LaunchAudioBreaker(limit = 2).also { it.begin() }

        repeat(5) { run(breaker) }

        assertFalse("codec trouble is not a dead download endpoint", breaker.tripped)
        assertEquals("every game was attempted", 5, downloadCalls)
        assertEquals(5, cost.audioOutcomeCount(LaunchAudioStepOutcome.TranscodeFailed))
        assertEquals(0, negatives)
    }

    @Test
    fun `a transcode that throws is a silent step failure that records nothing and cleans up`() {
        probeResult = { ClipAudioProbe.Track("audio/mpeg") }
        transcodeResult = { _, _ -> error("codec exploded") }

        val result = run()

        assertFalse(result.written)
        assertEquals(0, installed.size)
        assertEquals("an unexpected throw says nothing about the clip", 0, negatives)
        assertEquals(emptyList<String>(), stageFiles())
        assertEquals(1, cost.audioOutcomeCount(LaunchAudioStepOutcome.StepFailed))
    }

    // ── the duration cap, on both paths ───────────────────────────────────────────────────────────

    @Test
    fun `both paths are asked for the same named duration cap`() {
        run()
        probeResult = { ClipAudioProbe.Track("audio/mpeg") }
        run()

        assertEquals("one ask per path", 2, durationCaps.size)
        assertEquals("the copy and the transcode get the one constant", setOf(LaunchAudioMaxDurationUs), durationCaps.toSet())
        assertEquals(15_000L, LaunchAudioMaxDurationMs)
        assertEquals(LaunchAudioMaxDurationMs * 1_000L, LaunchAudioMaxDurationUs)
    }

    @Test
    fun `the cap stays above everything that can be heard`() {
        val introMs = GameOpeningIntroHoldMs + GameOpeningIntroEnterMs + GameOpeningIntroExitMs
        assertTrue(
            "the sound is heard through the intro and then under the cover, which ends by its ceiling",
            LaunchAudioMaxDurationMs > introMs + GameLaunchCoverCeilingMs,
        )
    }

    @Test
    fun `the launch-audio cap is documented as a technical bound`() {
        val text = source("LaunchAudioRemux.kt")
        val doc = text.substringBefore("internal const val LaunchAudioMaxDurationMs")
        assertTrue(doc, doc.contains("TECHNICAL bound, not\n * a product rule"))
        assertFalse("the cap is not an owner decision", doc.contains("owner"))
    }

    // ── the bound on concurrent transcodes (walk 32l) ─────────────────────────────────────────────────

    /**
     * One lane's whole step with its own thread-safe fakes (the harness's fields are plain vars, fine for one lane at a time
     * and not for several at once), so concurrent lanes can be started on real threads.
     */
    private fun laneStep(
        gate: TranscodeGate,
        mime: String,
        downloads: AtomicInteger,
        installs: AtomicInteger = AtomicInteger(),
        negativesSeen: AtomicInteger = AtomicInteger(),
        transcode: (File, File) -> LaunchAudioRemuxOutcome = { _, _ -> error("this lane must not transcode") },
    ): suspend () -> ArtworkFetchController.FinalMediaWrite = {
        ArtworkFetchController.fetchLaunchAudioWith(
            clipUrl = "https://www.screenscraper.fr/medias/clip.mp4",
            stageDir = stage,
            download = { _, _, target ->
                downloads.incrementAndGet()
                target.writeBytes(clipBytes)
                ClipDownload.Ok(clipBytes.size.toLong())
            },
            probe = { ClipAudioProbe.Track(mime) },
            copy = { _, output, _ ->
                output.writeBytes(soundBytes)
                LaunchAudioRemuxOutcome.Written(soundBytes.size.toLong(), samples = 3)
            },
            transcode = { input, output, _ -> transcode(input, output) },
            install = {
                installs.incrementAndGet()
                ArtworkFetchController.FinalMediaWrite(target = game)
            },
            recordNegative = { negativesSeen.incrementAndGet() },
            clearNegative = {},
            cost = cost,
            breaker = LaunchAudioBreaker().also { it.begin() },
            transcodeGate = gate,
        )
    }

    private suspend fun waitUntil(what: String, condition: () -> Boolean) {
        withTimeout(10_000) { while (!condition()) delay(10) }
        assertTrue(what, condition())
    }

    @Test
    fun `at most two transcodes run at once and a third lane waits while its download and probe are already done`() {
        assertEquals("the named bound", 2, MaxConcurrentTranscodes)
        val gate = TranscodeGate(MaxConcurrentTranscodes)
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val entered = AtomicInteger()
        val finished = AtomicInteger()
        val downloads = AtomicInteger()
        val installs = AtomicInteger()
        val release = CountDownLatch(1)
        val transcode: (File, File) -> LaunchAudioRemuxOutcome = { _, output ->
            entered.incrementAndGet()
            peak.accumulateAndGet(running.incrementAndGet()) { a, b -> maxOf(a, b) }
            check(release.await(10, TimeUnit.SECONDS)) { "the test never released the transcodes" }
            running.decrementAndGet()
            finished.incrementAndGet()
            output.writeBytes(soundBytes)
            LaunchAudioRemuxOutcome.Written(soundBytes.size.toLong(), samples = 9)
        }

        runBlocking {
            val lanes = List(3) { launch(Dispatchers.IO) { laneStep(gate, "audio/mpeg", downloads, installs, transcode = transcode)() } }
            waitUntil("two lanes hold a permit and are transcoding") { entered.get() == 2 }
            waitUntil("the third lane has downloaded its clip too") { downloads.get() == 3 }
            delay(300) // every chance for the third lane to start when it must not
            assertEquals("a third concurrent transcode waits", 2, running.get())
            assertEquals(2, entered.get())
            assertEquals("downloads are not held to the bound", 3, downloads.get())

            release.countDown()
            lanes.forEach { it.join() }
        }

        assertEquals("the waiting lane transcodes once a permit is free", 3, finished.get())
        assertEquals("never more than the bound at once", 2, peak.get())
        assertEquals(3, installs.get())
        assertEquals(emptyList<String>(), stageFiles())
    }

    @Test
    fun `the AAC copy never waits for a transcode permit`() {
        val gate = TranscodeGate(permits = 1)
        val held = CountDownLatch(1)
        val letGo = CountDownLatch(1)
        val downloads = AtomicInteger()
        val installs = AtomicInteger()

        runBlocking {
            val holder = launch(Dispatchers.IO) { gate.run { held.countDown(); letGo.await(10, TimeUnit.SECONDS) } }
            assertTrue(held.await(5, TimeUnit.SECONDS))

            val result = withTimeout(5_000) { laneStep(gate, LaunchAudioAacMime, downloads, installs)() }

            assertTrue("the copy ran while every permit was taken", result.written)
            assertEquals(1, downloads.get())
            assertEquals(1, installs.get())
            letGo.countDown()
            holder.join()
        }
    }

    @Test
    fun `a lane cancelled while it waits for a permit leaves no file and records no negative`() {
        val gate = TranscodeGate(permits = 1)
        val held = CountDownLatch(1)
        val letGo = CountDownLatch(1)
        val downloads = AtomicInteger()
        val negativesSeen = AtomicInteger()

        runBlocking {
            val holder = launch(Dispatchers.IO) { gate.run { held.countDown(); letGo.await(10, TimeUnit.SECONDS) } }
            assertTrue(held.await(5, TimeUnit.SECONDS))
            val waiting = launch(Dispatchers.IO) {
                laneStep(gate, "audio/mpeg", downloads, negativesSeen = negativesSeen)()
            }
            waitUntil("the lane downloaded and is now waiting for the permit") { downloads.get() == 1 }
            delay(100)

            waiting.cancelAndJoin()

            assertTrue(waiting.isCancelled)
            assertEquals("a cancelled lane says nothing about the clip", 0, negativesSeen.get())
            assertEquals("and its temp files are gone", emptyList<String>(), stageFiles())
            letGo.countDown()
            holder.join()
        }
    }

    @Test
    fun `the process-wide gate is shared by every batch and holds two permits`() {
        val fetch = source("ArtworkFetchController.kt")
        assertTrue(fetch.contains("internal val launchAudioTranscodeGate = TranscodeGate()"))
        assertEquals("one gate, not one per call", 1, Regex("""TranscodeGate\(\)""").findAll(fetch).count())
        assertTrue(
            "the step takes the shared gate by default",
            fetch.contains("transcodeGate: TranscodeGate = launchAudioTranscodeGate"),
        )
        val production = fetch.substringAfter("suspend fun fetchLaunchAudio(").substringBefore("recordNegative = { ArtNoMatchStore.record(key) }")
        assertFalse("the production call passes no gate of its own, so it uses the shared one", production.contains("transcodeGate ="))
        val step = fetch.substringAfter("internal suspend fun fetchLaunchAudioWith(").substringBefore("// ── What a batch cost")
        assertEquals("only the transcode takes a permit", 1, Regex("""transcodeGate\.run""").findAll(step).count())
        assertTrue(step.indexOf("download(clipUrl") < step.indexOf("transcodeGate.run"))
        assertTrue(step.indexOf("probe(clip)") < step.indexOf("transcodeGate.run"))
        val copyAt = step.indexOf("copy(clip, m4a, LaunchAudioMaxDurationUs)")
        assertTrue("the copy path is outside the gate", copyAt in 0 until step.indexOf("transcodeGate.run"))
    }

    // ── silent failure: nothing written, the negative decided by what the failure says about the clip ─────

    @Test
    fun `an AAC clip whose copy finds no audio track records the negative and writes nothing`() {
        remuxResult = { _, _ -> LaunchAudioRemuxOutcome.NoAudioTrack }

        val result = run()

        assertFalse(result.written)
        assertEquals(0, installed.size)
        assertEquals(1, negatives)
        assertEquals(emptyList<String>(), stageFiles())
        assertEquals(1, cost.audioOutcomeCount(LaunchAudioStepOutcome.NoAudioTrack))
    }

    @Test
    fun `a bad remux records the negative and writes nothing`() {
        remuxResult = { _, _ -> LaunchAudioRemuxOutcome.Failed }

        val result = run()

        assertFalse(result.written)
        assertEquals(0, installed.size)
        assertEquals(1, negatives)
        assertEquals(emptyList<String>(), stageFiles())
        assertEquals(1, cost.audioOutcomeCount(LaunchAudioStepOutcome.RemuxFailed))
    }

    @Test
    fun `a clip over the cap records the negative and is never remuxed`() {
        downloadResult = { target ->
            target.writeBytes(ByteArray(10))
            ClipDownload.TooLarge
        }

        val result = run()

        assertFalse(result.written)
        assertEquals("no point remuxing a clip that was refused", 0, remuxCalls)
        assertEquals(0, installed.size)
        assertEquals(1, negatives)
        assertEquals("the partial file is gone", emptyList<String>(), stageFiles())
        assertEquals(1, cost.audioOutcomeCount(LaunchAudioStepOutcome.TooLarge))
    }

    /**
     * ScreenScraper answering once with a quota refusal, or the network dropping, says nothing about the clip. Recording a
     * fourteen-day negative for it would silence that game's sound for a fortnight because of one bad minute.
     */
    @Test
    fun `a refused or dropped download records no negative`() {
        downloadResult = { ClipDownload.Unavailable }

        val result = run()

        assertFalse(result.written)
        assertEquals(0, remuxCalls)
        assertEquals("a transport failure says nothing about the clip", 0, negatives)
        assertEquals(0, clears)
        assertEquals(emptyList<String>(), stageFiles())
        assertEquals(1, cost.audioOutcomeCount(LaunchAudioStepOutcome.DownloadUnavailable))
    }

    @Test
    fun `a write failure records no negative and a game that already has a sound keeps it`() {
        installResult = { ArtworkFetchController.FinalMediaWrite(writeFailed = true) }
        val failed = run()
        assertFalse(failed.written)
        assertEquals("the sound was fine, the card was not", 0, negatives)
        assertEquals(0, clears)
        assertEquals(1, cost.audioOutcomeCount(LaunchAudioStepOutcome.WriteFailed))

        installResult = { ArtworkFetchController.FinalMediaWrite() }
        val alreadyThere = run()
        assertFalse(alreadyThere.written)
        assertEquals(0, negatives)
        assertEquals("nothing was written, so nothing is cleared", 0, clears)
        assertEquals(1, cost.audioOutcomeCount(LaunchAudioStepOutcome.NotWritten))

        installResult = { ArtworkFetchController.FinalMediaWrite(writeBlocked = true) }
        val blocked = run()
        assertTrue("a read-only folder still reports blocked to the batch", blocked.writeBlocked)
        assertEquals(0, negatives)
    }

    @Test
    fun `an unexpected throw is swallowed cleaned up and never fails the game's art`() {
        downloadResult = { throw IllegalStateException("disk full") }

        val result = run()

        assertFalse(result.written)
        assertEquals("it is an extra: no negative either", 0, negatives)
        assertEquals(emptyList<String>(), stageFiles())
        assertEquals(1, cost.audioOutcomeCount(LaunchAudioStepOutcome.StepFailed))
    }

    @Test
    fun `cancellation is never swallowed and still cleans up`() {
        downloadResult = { throw CancellationException("batch cancelled") }

        var cancelled = false
        try {
            run()
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertEquals(0, negatives)
        assertEquals(emptyList<String>(), stageFiles())
    }

    @Test
    fun `a stage folder that cannot exist is a silent step failure`() {
        val blocker = folder.newFile("stage-is-a-file")
        val result = runBlocking {
            ArtworkFetchController.fetchLaunchAudioWith(
                clipUrl = "https://www.screenscraper.fr/medias/clip.mp4",
                stageDir = File(blocker, "nested"),
                download = { _, _, _ -> error("must not download") },
                probe = { error("must not probe") },
                copy = { _, _, _ -> error("must not copy") },
                transcode = { _, _, _ -> error("must not transcode") },
                install = { error("must not install") },
                recordNegative = { negatives++ },
                clearNegative = { clears++ },
                cost = cost,
                breaker = LaunchAudioBreaker().also { it.begin() },
            )
        }

        assertFalse(result.written)
        assertEquals(0, negatives)
        assertEquals(1, cost.audioOutcomeCount(LaunchAudioStepOutcome.StepFailed))
    }

    // ── the breaker ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `consecutive dead downloads trip the breaker and the rest of the batch skips the sound without a negative`() {
        downloadResult = { ClipDownload.Unavailable }
        val breaker = LaunchAudioBreaker(limit = 3).also { it.begin() }

        repeat(3) { run(breaker) }
        assertTrue(breaker.tripped)
        assertEquals(3, downloadCalls)

        val skipped = run(breaker)

        assertFalse(skipped.written)
        assertEquals("a tripped breaker stops paying for downloads", 3, downloadCalls)
        assertEquals("and says nothing about the game's clip, so records nothing", 0, negatives)
        assertEquals(1, cost.audioOutcomeCount(LaunchAudioStepOutcome.SkippedByBreaker))
        assertEquals("a skipped clip is not counted as attempted", 3, cost.audioClipCount())
    }

    @Test
    fun `clips that are silent or too big are facts about the game and never trip the breaker`() {
        val breaker = LaunchAudioBreaker(limit = 3).also { it.begin() }
        remuxResult = { _, _ -> LaunchAudioRemuxOutcome.NoAudioTrack }
        repeat(10) { run(breaker) }
        downloadResult = { ClipDownload.TooLarge }
        repeat(10) { run(breaker) }

        assertFalse(breaker.tripped)
    }

    @Test
    fun `a successful download resets the run of failures`() {
        val breaker = LaunchAudioBreaker(limit = 3).also { it.begin() }
        downloadResult = { ClipDownload.Unavailable }
        repeat(2) { run(breaker) }
        downloadResult = { target ->
            target.writeBytes(clipBytes)
            ClipDownload.Ok(clipBytes.size.toLong())
        }
        run(breaker)
        downloadResult = { ClipDownload.Unavailable }
        repeat(2) { run(breaker) }

        assertFalse("2 + success + 2 is not 3 in a row", breaker.tripped)
    }

    @Test
    fun `outside a batch the breaker never trips so one press cannot silence the next`() {
        val breaker = LaunchAudioBreaker(limit = 2)
        repeat(10) { breaker.recordFailure() }
        assertFalse(breaker.tripped)

        breaker.begin()
        repeat(2) { breaker.recordFailure() }
        assertTrue(breaker.tripped)
        breaker.end()
        assertFalse("ending the batch disarms and resets it", breaker.tripped)
        repeat(10) { breaker.recordFailure() }
        assertFalse(breaker.tripped)
    }

    /**
     * A batch the player cancels never reaches its finish. The breaker it tripped must not outlive it: otherwise every
     * per-game Enrich until the next batch would silently skip the sound, with no log and no negative.
     */
    @Test
    fun `a breaker tripped in an abandoned batch no longer skips the next press`() {
        val breaker = LaunchAudioBreaker(limit = 2).also { it.begin() }
        downloadResult = { ClipDownload.Unavailable }
        repeat(2) { run(breaker) }
        assertTrue(breaker.tripped)
        assertEquals(2, downloadCalls)

        breaker.end()
        run(breaker)

        assertFalse(breaker.tripped)
        assertEquals("the press after the abandoned batch downloads again", 3, downloadCalls)
        assertEquals(0, cost.audioOutcomeCount(LaunchAudioStepOutcome.SkippedByBreaker))
    }

    /**
     * This was `abandoning a batch through the controller
     * disarms the sound's breaker`: the Enrich batches armed a breaker for the sound they fetched. They fetch none now, and
     * the breaker moved with the sound to the game-music download ([GameMusicBatch]), which arms and disarms its own.
     */
    @Test
    fun `the Enrich controller owns no sound breaker`() {
        val fetch = source("ArtworkFetchController.kt")
        assertFalse(fetch.contains("launchAudioBreaker"))
        assertFalse(fetch.contains("abandonEnrichBatch"))
        assertFalse(fetch.contains("LaunchAudioBreaker()"))
    }

    /**
     * The mechanism [ArtworkFetchController.fetchAllArtwork] relies on to abandon a cancelled batch: a completion handler
     * registered on the `withContext` job runs on the normal exit and on cancellation alike.
     */
    @Test
    fun `a completion handler on the withContext job runs whether the batch finishes or is cancelled`() = runBlocking {
        val normal = CompletableDeferred<Unit>()
        withContext(Dispatchers.IO) {
            currentCoroutineContext()[Job]?.invokeOnCompletion { normal.complete(Unit) }
        }
        // The handler runs as the job finishes, a moment after withContext hands control back, so wait for it.
        withTimeout(5_000) { normal.await() }

        val cancelled = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val job = launch {
            withContext(Dispatchers.IO) {
                currentCoroutineContext()[Job]?.invokeOnCompletion { cancelled.complete(Unit) }
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        job.cancelAndJoin()
        withTimeout(5_000) { cancelled.await() }
    }

    /**
     * FLIPPED DELIBERATELY. This was `both batches disarm the breaker on every way out`, pinning the abandon handler in the
     * Settings batch and a `finally` in the first-run batch. Neither Enrich batch has a sound breaker, a stage sweep or an
     * abandon step now; the game-music download does all three (see its own tests).
     */
    @Test
    fun `neither Enrich batch arms, disarms or sweeps for a sound`() {
        val fetch = source("ArtworkFetchController.kt")
        val all = fetch.substringAfter("suspend fun fetchAllArtwork(").substringBefore("Compatibility entry point retained")
        assertFalse(all.contains("abandon"))
        assertFalse(all.contains("Breaker.begin") || all.contains("sweepLaunchAudioStage"))
        val begin = fetch.substringAfter("internal fun beginEnrichBatch(").substringBefore("\n    }")
        assertFalse("beginEnrichBatch only zeroes the meter", begin.contains("sweepLaunchAudioStage") || begin.contains("Breaker"))
        val firstRun = source("MimirArtworkCoordinator.kt")
            .substringAfter("private fun startNewGamesFetch(")
            .substringBefore("private fun publishProgress(")
        assertFalse(firstRun.contains("abandonEnrichBatch"))
        assertFalse(firstRun.contains("Breaker.begin") || firstRun.contains("sweepLaunchAudioStage"))
    }

    // ── what the decision table says ──────────────────────────────────────────────────────────────

    @Test
    fun `only facts about the clip record a negative`() {
        val records = LaunchAudioStepOutcome.entries.filter { audioStepRecordsNegative(it) }.toSet()

        assertEquals(
            setOf(
                LaunchAudioStepOutcome.NoAudioTrack,
                LaunchAudioStepOutcome.UnsupportedCodec,
                LaunchAudioStepOutcome.RemuxFailed,
                LaunchAudioStepOutcome.TooLarge,
            ),
            records,
        )
    }

    @Test
    fun `a lookup that found a clip never records an audio negative by itself`() {
        assertTrue(audioNegativeAfterLookup(asked = true, clipUrlPresent = false))
        assertFalse(audioNegativeAfterLookup(asked = true, clipUrlPresent = true))
        assertFalse(audioNegativeAfterLookup(asked = false, clipUrlPresent = false))
    }

    // ── the bounded streaming copy ────────────────────────────────────────────────────────────────

    @Test
    fun `a stream at exactly the cap is kept and one byte over is refused with no file left`() {
        val target = File(folder.root, "clip.part")

        val ok = copyBoundedToFile(ByteArrayInputStream(ByteArray(100)), maxBytes = 100, target = target)
        assertEquals(ClipDownload.Ok(100), ok)
        assertEquals(100L, target.length())

        val over = copyBoundedToFile(ByteArrayInputStream(ByteArray(101)), maxBytes = 100, target = target)
        assertEquals(ClipDownload.TooLarge, over)
        assertFalse("a capped download leaves nothing behind", target.exists())
    }

    @Test
    fun `an empty body is unavailable and leaves no file`() {
        val target = File(folder.root, "clip.part")

        assertEquals(ClipDownload.Unavailable, copyBoundedToFile(ByteArrayInputStream(ByteArray(0)), 100, target))
        assertFalse(target.exists())
        assertEquals(ClipDownload.Unavailable, copyBoundedToFile(ByteArrayInputStream(ByteArray(5)), 0, target))
    }

    /**
     * A server that lies about Content-Length, or sends none, is stopped by the bytes it actually sends, and the body is
     * written as it arrives: this stream never has more than one chunk read at a time.
     */
    @Test
    fun `a stream is cut at the cap as it arrives and is never read whole`() {
        val target = File(folder.root, "clip.part")
        var chunksRead = 0
        val endless = object : InputStream() {
            override fun read(): Int = error("the copy must read in chunks")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                chunksRead++
                val n = minOf(len, 4_096)
                java.util.Arrays.fill(b, off, off + n, 7)
                return n
            }
        }

        val result = copyBoundedToFile(endless, maxBytes = 20_000, target = target)

        assertEquals(ClipDownload.TooLarge, result)
        assertTrue("stopped within a chunk or two of the cap, not after reading forever: $chunksRead", chunksRead in 5..7)
        assertFalse(target.exists())
    }

    @Test
    fun `a clip that trickles past the deadline is unavailable and leaves no file`() {
        val target = File(folder.root, "clip.part")
        var nanos = 0L
        val slow = object : InputStream() {
            override fun read(): Int = error("chunks only")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                nanos += 20_000_000_000L
                b[off] = 1
                return 1
            }
        }

        val result = copyBoundedToFile(slow, maxBytes = 1_000_000, target = target, maxMillis = 60_000, nowNanos = { nanos })

        assertEquals(ClipDownload.Unavailable, result)
        assertFalse(target.exists())
    }

    // ── the batch's cost line ─────────────────────────────────────────────────────────────────────

    @Test
    fun `the cost line has the diagnostic keys, in this order`() {
        val meter = EnrichCostAccumulator { 0L }.also { it.begin("download", requestsBefore = null) }

        val line = meter.summary(games = 58, matched = 50, wallMs = 812_345, requestsAfter = null, audioBreakerTripped = false)

        assertEquals(
            listOf(
                "batch", "games", "matched", "wallMs", "lookups", "lookupMs",
                "coverMs", "logoMs", "heroMs", "physicalMs",
                "audioMs", "audioClips", "audioBytes", "audioCodecs", "audioDownloadMs", "audioRemuxMs", "audioTranscodeMs",
                "audioWritten", "audioTranscoded", "audioNoTrack", "audioNoDecoder", "audioTooLarge", "audioRemuxFailed",
                "audioDownloadFailed",
                "audioWriteFailed", "audioNotWritten", "audioStepFailed", "audioSkipped", "audioBreakerTripped",
                "requestsTodayBefore", "requestsTodayAfter", "requestsTodayDelta",
                // Walk 32l: appended at the END, so a reader of the line before it still parses everything it knew.
                "audioTranscodeFailed",
            ),
            line.split(' ').map { it.substringBefore('=') },
        )
        assertTrue(line, line.startsWith("batch=download games=58 matched=50 wallMs=812345 "))
        assertTrue(
            line,
            line.endsWith(" requestsTodayBefore=unknown requestsTodayAfter=unknown requestsTodayDelta=unknown audioTranscodeFailed=0"),
        )
    }

    @Test
    fun `the cost line holds only words and numbers so it can never carry a URL or a name`() {
        val meter = EnrichCostAccumulator { 0L }.also { it.begin("first-run", requestsBefore = 12) }
        meter.addLookup(40)
        meter.addSlot(ArtworkFetchController.ManagedMediaSlot.COVER, 25)
        meter.audioClip()

        val line = meter.summary(games = 2, matched = 1, wallMs = 10, requestsAfter = 15, audioBreakerTripped = true)

        assertTrue(line, Regex("^[A-Za-z]+=[A-Za-z0-9-]+( [A-Za-z]+=[A-Za-z0-9-]+)*$").matches(line))
        assertFalse(line, line.contains("http") || line.contains("/") || line.contains("."))
        assertTrue(line, line.contains(" requestsTodayBefore=12 requestsTodayAfter=15 requestsTodayDelta=3"))
        assertTrue(line, line.contains(" audioBreakerTripped=true "))
    }

    @Test
    fun `the codec tally counts each source track type, most common first, and starts from zero with every batch`() {
        val meter = EnrichCostAccumulator { 0L }.also { it.begin("first-run", requestsBefore = null) }
        assertEquals("none", meter.audioCodecsField())

        repeat(11) { meter.audioCodec("audio/mpeg") }
        meter.audioCodec("audio/mp4a-latm")
        meter.audioCodec("AUDIO/MPEG")
        meter.audioCodec("audio/ac3")

        assertEquals("audio/mpeg:12,audio/ac3:1,audio/mp4a-latm:1", meter.audioCodecsField())
        val line = meter.summary(15, 12, 1, null, false)
        assertTrue(line, line.contains(" audioCodecs=audio/mpeg:12,audio/ac3:1,audio/mp4a-latm:1 "))

        meter.begin("download", requestsBefore = null)
        assertEquals("none", meter.audioCodecsField())
        assertTrue(meter.summary(0, 0, 0, null, false).contains(" audioCodecs=none "))
    }

    @Test
    fun `a track type that comes out of a downloaded file can never break the cost line`() {
        assertEquals("audio/mpeg", sanitizeCodecMime("audio/mpeg"))
        assertEquals("audio/mpeg", sanitizeCodecMime("  Audio/MPEG \n"))
        assertEquals("audio/vnd.dts.hd", sanitizeCodecMime("audio/vnd.dts.hd"))
        assertEquals("audio/x-ms-wma", sanitizeCodecMime("audio/x-ms-wma"))
        for (hostile in listOf(
            "",
            "audio",
            "audio/",
            "/mpeg",
            "audio/mpeg extra=field",
            "audio/mpeg,audio/aac",
            "audio/mpeg:5",
            "audio/mp\neg",
            "audio/mpeg\tx",
            "https://www.screenscraper.fr/media?devid=x&devpassword=y",
            "audio/../../etc/passwd",
            "audio/mpeg=1",
            "audio/" + "a".repeat(80),
            "audio/mépeg",
        )) {
            assertEquals("hostile track type `${hostile.take(20)}`", OtherCodec, sanitizeCodecMime(hostile))
        }

        val meter = EnrichCostAccumulator { 0L }.also { it.begin("first-run", requestsBefore = 1) }
        meter.audioCodec("audio/mpeg")
        meter.audioCodec("audio/mpeg extra=field")
        meter.audioCodec("https://www.screenscraper.fr/x?devpassword=secret")
        val line = meter.summary(3, 3, 1, 2, false)

        assertEquals(
            "a hostile value adds no field: the keys are exactly the fixed ones",
            EnrichCostAccumulator { 0L }.also { it.begin("first-run", requestsBefore = 1) }
                .summary(3, 3, 1, 2, false).split(' ').map { it.substringBefore('=') },
            line.split(' ').map { it.substringBefore('=') },
        )
        assertTrue(line, line.contains(" audioCodecs=other:2,audio/mpeg:1 "))
        assertFalse(line, line.contains("screenscraper") || line.contains("secret") || line.contains("http"))
    }

    @Test
    fun `too many distinct track types are all counted as other so the tally stays short`() {
        val meter = EnrichCostAccumulator { 0L }.also { it.begin("download", requestsBefore = null) }
        repeat(40) { meter.audioCodec("audio/x-made-up-$it") }

        val field = meter.audioCodecsField()
        val entries = field.split(',')

        assertTrue("at most a dozen named types plus other: $field", entries.size <= 13)
        assertEquals("every clip is still counted", 40, entries.sumOf { it.substringAfterLast(':').toInt() })
        assertTrue(field, entries.any { it.startsWith("other:") })
    }

    @Test
    fun `the codec field is the only place the cost line may hold a slash or a colon`() {
        val meter = EnrichCostAccumulator { 0L }.also { it.begin("first-run", requestsBefore = 12) }
        meter.audioCodec("audio/mpeg")
        meter.audioCodec("audio/mp4a-latm")

        val line = meter.summary(games = 2, matched = 1, wallMs = 10, requestsAfter = 15, audioBreakerTripped = false)

        assertTrue(line, Regex("^[A-Za-z]+=[A-Za-z0-9_.:,/+-]+( [A-Za-z]+=[A-Za-z0-9_.:,/+-]+)*$").matches(line))
        assertFalse(line, line.contains("http") || line.contains("://") || line.contains(".."))
        val withoutCodecs = line.split(' ').filterNot { it.startsWith("audioCodecs=") }.joinToString(" ")
        assertTrue(
            "every other field is still words and numbers: $withoutCodecs",
            Regex("^[A-Za-z]+=[A-Za-z0-9-]+( [A-Za-z]+=[A-Za-z0-9-]+)*$").matches(withoutCodecs),
        )
    }

    @Test
    fun `every lane's time adds up per slot and a new batch starts from zero`() {
        var time = 1_000L
        val meter = EnrichCostAccumulator { time }.also { it.begin("download", requestsBefore = 0) }
        listOf(10L, 20L, 30L).forEach { meter.addSlot(ArtworkFetchController.ManagedMediaSlot.HERO, it) }
        meter.addSlot(ArtworkFetchController.ManagedMediaSlot.LOGO, 7)
        meter.addLookup(5)
        meter.addLookup(6)
        time = 4_000L

        assertEquals(3_000L, meter.wallMs())
        val first = meter.summary(3, 3, meter.wallMs(), 0, false)
        assertTrue(first, first.contains(" lookups=2 lookupMs=11 coverMs=0 logoMs=7 heroMs=60 physicalMs=0 "))

        meter.begin("first-run", requestsBefore = 0)
        val second = meter.summary(0, 0, 0, 0, false)
        assertTrue(second, second.contains(" lookups=0 lookupMs=0 coverMs=0 logoMs=0 heroMs=0 physicalMs=0 "))
        assertTrue(second, second.startsWith("batch=first-run "))
    }

    @Test
    fun `finishing a batch logs exactly one line and reads the quota once`() = runBlocking {
        val lines = mutableListOf<String>()
        var reads = 0
        ArtworkFetchController.beginEnrichBatch(ArtworkFetchController.EnrichBatchFirstRun)

        ArtworkFetchController.finishEnrichBatch(
            context = ContextWrapper(null),
            games = 7,
            matched = 5,
            readRequestsToday = {
                reads++
                321
            },
            emit = { lines += it },
        )

        assertEquals(1, lines.size)
        assertEquals(1, reads)
        assertTrue(lines.single(), lines.single().startsWith("batch=first-run games=7 matched=5 "))
        assertEquals("MimirEnrichCost", ArtworkFetchController.EnrichCostLogTag)
    }

    @Test
    fun `an unreadable quota is reported as unknown and never stops the line`() = runBlocking {
        val lines = mutableListOf<String>()
        ArtworkFetchController.beginEnrichBatch(ArtworkFetchController.EnrichBatchDownload)

        ArtworkFetchController.finishEnrichBatch(
            context = ContextWrapper(null),
            games = 1,
            matched = 0,
            readRequestsToday = { error("offline") },
            emit = { lines += it },
        )

        assertEquals(1, lines.size)
        assertTrue(lines.single(), lines.single().contains(" requestsTodayAfter=unknown requestsTodayDelta=unknown"))
    }

    // ── the SAF create type: keeping the `-audio.m4a` name on a provider that normalises it ─────────────

    /**
     * A DocumentsProvider appends the extension it believes a MIME type has unless the type and the extension agree in
     * the platform's tables, so the sound is created with the platform's own type for `m4a` (and `octet-stream`, the type
     * a provider assumes for an extension it does not know, when there is none). Whatever the tables say, the name stays.
     */
    @Test
    fun `the sound is created with the platform's own type for its extension`() {
        assertEquals("audio/mpeg", safCreateMimeFor("m4a") { "audio/mpeg" })
        assertEquals("audio/mp4", safCreateMimeFor("m4a") { "audio/mp4" })
        assertEquals("audio/mp4", safCreateMimeFor("M4A") { ext -> "audio/mp4".takeIf { ext == "m4a" } })
        assertEquals("application/octet-stream", safCreateMimeFor("m4a") { null })
        assertEquals("application/octet-stream", safCreateMimeFor("m4a") { "  " })
        // With no platform table at all (a plain JVM), it still answers rather than throws.
        assertEquals("application/octet-stream", safCreateMimeFor("m4a"))
    }

    @Test
    fun `only the sound reconciles its create type and the images and clip keep theirs`() {
        val install = source("ArtworkFetchController.kt")
            .substringAfter("private suspend fun installDownloadedMediaAtCurrentGame(")
            .substringBefore("private suspend fun clearStaleUnattributedLogo(")

        assertTrue(install.contains("if (slot == ManagedMediaSlot.AUDIO) {"))
        assertTrue(install.contains("safCreateMimeFor(managedMediaExtAndMime(slot).first)"))
        assertTrue(install.contains("managedMediaExtAndMime(slot).second"))
        assertEquals("one reconciled path, for the sound only", 1, Regex("safCreateMimeFor\\(").findAll(install).count())
    }

    // ── source pins: where the meter is wired and what the new code may never say ─────────────────

    private fun source(name: String): String = File(
        listOf(
            "app/src/main/kotlin/com/gruning/mimir/rewrite/$name",
            "src/main/kotlin/com/gruning/mimir/rewrite/$name",
        ).first { File(it).isFile },
    ).readText()

    @Test
    fun `both enrich batches start and finish the meter around their work`() {
        val fetch = source("ArtworkFetchController.kt")
        val all = fetch.substringAfter("suspend fun fetchAllArtwork(").substringBefore("Compatibility entry point retained")
        assertTrue(
            "the Settings batch starts the meter after the live quota read and before its work",
            all.indexOf("refreshAccountMaxThreads(context)") in 0 until all.indexOf("beginEnrichBatch(EnrichBatchDownload)") &&
                all.indexOf("beginEnrichBatch(EnrichBatchDownload)") in 0 until all.indexOf("runBoundedArtworkBatch("),
        )
        assertTrue(
            "and finishes it before it returns",
            all.indexOf("runBoundedArtworkBatch(") in 0 until all.indexOf("finishEnrichBatch(context, games = total, matched = matched)") &&
                all.indexOf("finishEnrichBatch(context, games = total, matched = matched)") in 0 until all.indexOf("FetchResult(total = total"),
        )

        val firstRun = source("MimirArtworkCoordinator.kt")
            .substringAfter("private fun startNewGamesFetch(")
            .substringBefore("private fun publishProgress(")
        val begin = firstRun.indexOf("beginEnrichBatch(ArtworkFetchController.EnrichBatchFirstRun)")
        val work = firstRun.indexOf("ArtworkFetchController.runBoundedArtworkBatch(")
        val finish = firstRun.indexOf("finishEnrichBatch(context, games = ids.size, matched = matched)")
        assertTrue(
            "the first-run-card enrich is measured too: begin=$begin work=$work finish=$finish",
            begin >= 0 && begin < work && work < finish,
        )
        assertTrue(
            "and the quota it reads is the live one the batch just refreshed",
            firstRun.indexOf("refreshAccountMaxThreads(context)") in 0 until begin,
        )
    }

    @Test
    fun `the quota figure comes from the read the batch already makes`() {
        val connect = source("ArtworkConnectController.kt")
        val refresh = connect.substringAfter("suspend fun refreshAccountMaxThreads(").substringBefore("var lastRequestsToday")

        assertTrue(refresh.contains("lastRequestsToday = quota?.requestsToday"))
        assertEquals("one validate call in the refresh, as before", 1, Regex("source\\.validate\\(").findAll(refresh).count())
    }

    /**
     * **URL safety.** ScreenScraper media URLs carry credentials. None of the new code may log at all except the one
     * `MimirEnrichCost` line, whose only inputs are counts: no `Log.` in the remux helper, the fetch helpers, the step,
     * the streaming download or the accumulator, and no URL ever interpolated into a message there.
     */
    @Test
    fun `the new sound code logs nothing but the cost line`() {
        listOf("LaunchAudioRemux.kt", "LaunchAudioTranscode.kt", "LaunchAudioFetch.kt", "EnrichCostAccumulator.kt").forEach { name ->
            val text = source(name)
            assertFalse("$name must not log", Regex("""\bLog\.[a-z]\(""").containsMatchIn(text) || text.contains("println("))
        }
        val fetch = source("ArtworkFetchController.kt")
        val step = fetch.substringAfter("internal fun downloadGatedToFile(").substringBefore("// fetchAllPreviewVideos was retired here")
        val stepWithoutFinish = step.substringBefore("internal suspend fun finishEnrichBatch(")
        assertFalse("the download and the step must not log", stepWithoutFinish.contains("Log."))
        assertTrue(
            "the only log call in the block is the cost line",
            Regex("""\bLog\.[a-z]\(""").findAll(step).count() == 1 && step.contains("Log.i(EnrichCostLogTag, line)"),
        )
    }

    @Test
    fun `the transcode writes one AAC track into an MP4 and no other container or codec`() {
        val code = source("LaunchAudioTranscode.kt")
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .lineSequence().joinToString("\n") { it.substringBefore("//") }

        assertTrue(code.contains("MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4"))
        assertEquals("one muxer", 1, Regex("""MediaMuxer\(""").findAll(code).count())
        assertTrue("the encoder is AAC", code.contains("createEncoderByType(LaunchAudioAacMime)"))
        assertTrue(code.contains("AACObjectLC"))
        for (other in listOf("MUXER_OUTPUT_WEBM", "MUXER_OUTPUT_OGG", "MUXER_OUTPUT_3GPP", "MUXER_OUTPUT_HEIF", "MIMETYPE_AUDIO_MPEG", "MIMETYPE_AUDIO_OPUS", "MIMETYPE_AUDIO_AMR", "MIMETYPE_AUDIO_VORBIS", "mp3")) {
            assertFalse("the transcode must not name `$other` as an output", code.contains(other))
        }
        assertTrue("the work is bounded by a wall-clock deadline in both halves", Regex("""checkTranscodeDeadline\(deadlineNanos\)""").findAll(code).count() >= 2)
        assertTrue("and the duration cap reaches the decoder's input", code.contains("timeUs > maxDurationUs"))
        assertTrue("a missing decoder is told apart from any other codec trouble", code.contains("findDecoderForFormat(format)") && code.contains("LaunchAudioRemuxOutcome.UnsupportedCodec"))
    }

    @Test
    fun `the step is wired to the probe the copy and the transcode by name`() {
        val fetch = source("ArtworkFetchController.kt")
        val wiring = fetch.substringAfter("suspend fun fetchLaunchAudio(").substringBefore("recordNegative = { ArtNoMatchStore.record(key) }")
        assertTrue(wiring, wiring.contains("probe = LaunchAudioRemux::probeAudio"))
        assertTrue(wiring, wiring.contains("LaunchAudioRemux.remuxAacTrack(input, output, maxDurationUs)"))
        assertTrue(wiring, wiring.contains("LaunchAudioTranscode.transcodeToAac(input, output, maxDurationUs)"))

        val step = fetch.substringAfter("internal suspend fun fetchLaunchAudioWith(").substringBefore("// ── What a batch cost")
        val probeAt = step.indexOf("probe(clip)")
        val copyAt = step.indexOf("copy(clip, m4a, LaunchAudioMaxDurationUs)")
        val transcodeAt = step.indexOf("transcode(clip, m4a, LaunchAudioMaxDurationUs)")
        assertTrue("probe, then the copy or the transcode: $probeAt $copyAt $transcodeAt", probeAt in 0 until copyAt && copyAt in 0 until transcodeAt)
        assertTrue("only a track that is exactly AAC is copied", step.contains("if (look.mime == LaunchAudioAacMime)"))
    }

    /**
     * This asserted the intro read no `AudioPreferences` at all.
     * It now reads exactly two of them, the launch sound's own switch and volume (see LaunchSoundSettingTest), and still reads
     * nothing of the backdrop preview audio switch AU02 added for the retired video bed: that rule is what stays.
     */
    @Test
    fun `the intro does not read the backdrop preview audio switch`() {
        val intro = source("GameOpeningIntro.kt")
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .lineSequence().joinToString("\n") { it.substringBefore("//") }
        // The clip branch of the intro never read previewAudioEnabled (AU02's bed switch for the retired video backdrop),
        // and the saved sound is deliberately the same: one rule for both sources of the launch sound.
        assertFalse(intro.contains("previewAudioEnabled"))
        assertFalse(intro.contains("previewAudioMix"))
        val read = Regex("""AudioPreferences\.(\w+)""").findAll(intro).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("launchSoundEnabled", "launchSoundVolume"), read)
    }

    // ── a stop reaches the transcode (combined49) ─────────────────────────────────────────────────────────────────────

    /**
     * A cancel that lands while the codec loop is running interrupts it (`runInterruptible`), and what comes back is the
     * cancellation: no outcome is reported, no negative is recorded for the clip, nothing is installed, and the staging files go.
     */
    @Test
    fun `a stop during the transcode interrupts it and records no negative and installs nothing`() = runBlocking {
        val parked = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val installs = AtomicInteger()
        val negativesSeen = AtomicInteger()
        val step = laneStep(
            gate = TranscodeGate(1),
            mime = "audio/mpeg",
            downloads = AtomicInteger(),
            installs = installs,
            negativesSeen = negativesSeen,
            transcode = { _, _ ->
                parked.countDown()
                try {
                    CountDownLatch(1).await() // the codec loop: only an interrupt ends it
                    error("not reached")
                } catch (e: InterruptedException) {
                    interrupted.countDown()
                    throw e
                }
            },
        )
        val job = launch(Dispatchers.IO) { step() }
        assertTrue(parked.await(10, TimeUnit.SECONDS))

        withTimeout(10_000) { job.cancelAndJoin() }

        assertTrue("the codec loop was interrupted", interrupted.await(5, TimeUnit.SECONDS))
        assertEquals("a stop is not a fact about the clip", 0, negativesSeen.get())
        assertEquals(0, installs.get())
        assertEquals("the staging files go with the cancelled step", emptyList<String>(), stageFiles())
    }

    @Test
    fun `the codec loops stop at the next turn once the thread is interrupted, and an interrupt is not a codec failure`() {
        checkTranscodeNotInterrupted() // not interrupted: nothing
        Thread.currentThread().interrupt()
        try {
            var thrown: Throwable? = null
            try {
                checkTranscodeNotInterrupted()
            } catch (e: InterruptedException) {
                thrown = e
            }
            assertTrue("the check throws once interrupted", thrown is InterruptedException)
            // Why transcodeToAac rethrows it: the failure table would file it as a fact about the clip (a negative, two weeks of cooldown).
            assertTrue(transcodeFailureOutcome(thrown!!) is LaunchAudioRemuxOutcome.Failed)
        } finally {
            Thread.interrupted() // leave the thread clean for the next test
        }
    }
}
