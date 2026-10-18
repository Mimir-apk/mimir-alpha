package com.gruning.mimir.rewrite

import android.content.ContextWrapper
import android.provider.DocumentsContract
import com.gruning.mimir.rewrite.core.metadata.MetadataOrigin
import com.gruning.mimir.rewrite.core.metadata.MetadataRecord
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.posture.MimirConnectedArtGate
import com.gruning.mimir.rewrite.core.storage.entity.GameEntity
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkFetchControllerTest {

    @Test
    fun `connected media urls require provider https default port and no embedded authority`() {
        assertTrue(
            connectedArtMediaUrlAllowed(
                "https://www.screenscraper.fr/image.php?devid=D&devpassword=P",
            ),
        )
        assertTrue(
            connectedArtMediaUrlAllowed(
                "https://media.screenscraper.fr/video.mp4?token=1",
            ),
        )
        assertFalse(connectedArtMediaUrlAllowed("http://www.screenscraper.fr/image.php"))
        assertFalse(connectedArtMediaUrlAllowed("https://www.screenscraper.fr:8443/image.php"))
        assertFalse(connectedArtMediaUrlAllowed("https://user@www.screenscraper.fr/image.php"))
        assertFalse(connectedArtMediaUrlAllowed("https://www.screenscraper.fr/image.php#secret"))
        assertFalse(connectedArtMediaUrlAllowed("https://notscreenscraper.fr/image.php"))
        assertFalse(connectedArtMediaUrlAllowed("https://example.com/image.php"))
    }

    @Test
    fun `connected media redirects remain inside the provider policy`() {
        val current = "https://www.screenscraper.fr/media/video.mp4?token=old"
        assertEquals(
            "https://www.screenscraper.fr/media/video.mp4?token=next",
            connectedArtMediaRedirectTarget(current, "?token=next"),
        )
        assertEquals(
            "https://media.screenscraper.fr/poster.jpg",
            connectedArtMediaRedirectTarget(current, "https://media.screenscraper.fr/poster.jpg"),
        )
        assertNull(connectedArtMediaRedirectTarget(current, "http://www.screenscraper.fr/image.php"))
        assertNull(connectedArtMediaRedirectTarget(current, "https://example.com/capture"))
        assertNull(
            connectedArtMediaRedirectTarget(
                current,
                "https://www.screenscraper.fr:8443/image.php",
            ),
        )
    }

    @Test
    fun `connected media declared length accepts unknown and exact bound but rejects empty`() {
        assertTrue(connectedArtContentLengthAllowed(-1L, 4L))
        assertFalse(connectedArtContentLengthAllowed(0L, 4L))
        assertTrue(connectedArtContentLengthAllowed(4L, 4L))
        assertFalse(connectedArtContentLengthAllowed(5L, 4L))
        assertFalse(connectedArtContentLengthAllowed(-1L, 0L))
    }

    @Test
    fun `provider title ladder keeps DAT canonical truth first and de-duplicates display aliases`() {
        assertEquals(
            listOf("Resident Evil 2", "Biohazard 2", "Resident Evil 2 (USA)"),
            providerSearchTitleLadder(
                canonicalTitle = " Resident Evil 2 ",
                storedTitle = "Biohazard 2",
                rawBaseName = "Resident Evil 2 (USA)",
                fallbackTitle = "Resident Evil 2",
            ),
        )
    }

    // ── FA-M5: the fetch CONTROLLER itself no-ops when the connected-art gate is closed ────────────
    // The ContextWrapper(null) stub is deliberate: the gate check must run BEFORE any context/DB/network
    // use, so if the short-circuit ever moved after attach(context) these tests would CRASH, not pass.

    @Test
    fun `fetchAllArtwork no-ops with the gate closed before touching context`() = runBlocking {
        MimirConnectedArtGate.setOpen(false)
        val result = ArtworkFetchController.fetchAllArtwork(ContextWrapper(null))
        assertEquals(0, result.total)
        assertEquals(0, result.matched)
    }

    @Test
    fun `metadata starter no-ops with the gate closed before touching context`() = runBlocking {
        MimirConnectedArtGate.setOpen(false)

        val report = ConnectedMetadataHydrator.hydrate(ContextWrapper(null))

        assertEquals(0, report.attempted)
        assertEquals(0, report.cached)
        assertFalse(report.stoppedEarly)
    }

    @Test
    fun `fetchAllFlatArt no-ops with the gate closed before touching context`() = runBlocking {
        MimirConnectedArtGate.setOpen(false)
        val result = ArtworkFetchController.fetchAllFlatArt(ContextWrapper(null))
        assertEquals(0, result.total)
        assertEquals(0, result.matched)
    }

    @Test
    fun `fetchOneArtwork reports GateClosed with the gate closed before touching context`() = runBlocking {
        MimirConnectedArtGate.setOpen(false)
        val result = ArtworkFetchController.fetchOneArtwork(ContextWrapper(null), gameId = "any")
        assertEquals(ArtworkFetchController.OneResult.GateClosed, result)
    }

    @Test
    fun `managed art names keep cover logo hero and physical support distinct`() {
        val cover = ArtworkFetchController.managedDisplayName("Metroid Fusion.gba")
        val logo = ArtworkFetchController.managedDisplayName("Metroid Fusion.gba", nameSuffix = "-logo")
        val hero = ArtworkFetchController.managedDisplayName("Metroid Fusion.gba", nameSuffix = "-hero")
        val support = ArtworkFetchController.managedDisplayName("Metroid Fusion.gba", nameSuffix = "-support")

        assertEquals("Metroid Fusion.png", cover)
        assertEquals("Metroid Fusion-logo.png", logo)
        assertEquals("Metroid Fusion-hero.png", hero)
        assertEquals("Metroid Fusion-support.png", support)
        assertNotEquals(cover, logo)
        assertNotEquals(cover, hero)
        assertNotEquals(cover, support)
    }

    @Test
    fun `managed logo preserves dots that belong to the ROM basename`() {
        assertEquals(
            "Super Mario Bros. 3-logo.png",
            ArtworkFetchController.managedDisplayName("Super Mario Bros. 3.nes", nameSuffix = "-logo"),
        )
    }

    @Test
    fun `artwork success counts each game once when either image is written`() {
        val coverOnly = ArtworkFetchController.ArtworkWriteResult(coverWritten = true)
        val logoOnly = ArtworkFetchController.ArtworkWriteResult(flatArtWritten = true)
        val heroOnly = ArtworkFetchController.ArtworkWriteResult(heroArtWritten = true)
        val both = ArtworkFetchController.ArtworkWriteResult(coverWritten = true, flatArtWritten = true)
        val physicalOnly = ArtworkFetchController.ArtworkWriteResult(physicalMediaWritten = true)
        val metadataOnly = ArtworkFetchController.ArtworkWriteResult(metadataMatched = true)
        val neither = ArtworkFetchController.ArtworkWriteResult()

        assertTrue(coverOnly.wroteAny)
        assertTrue(logoOnly.wroteAny)
        assertTrue(heroOnly.wroteAny)
        assertTrue(both.wroteAny)
        assertTrue(physicalOnly.wroteAny)
        assertFalse(metadataOnly.wroteAny)
        assertTrue(metadataOnly.matchedAny)
        assertFalse(neither.wroteAny)
        assertEquals(5, listOf(coverOnly, logoOnly, heroOnly, both, physicalOnly, neither).count { it.wroteAny })
    }

    @Test
    fun `explicit Enrich refreshes metadata when media is complete or storage is read only`() {
        assertTrue(
            ArtworkFetchController.artworkMetadataLookupAllowed(
                forceLookup = true,
                needsMedia = false,
                writeAllowed = false,
            ),
        )
        assertTrue(
            ArtworkFetchController.artworkMetadataLookupAllowed(
                forceLookup = true,
                needsMedia = true,
                writeAllowed = false,
            ),
        )
    }

    @Test
    fun `automatic artwork lookup stays bounded by missing media and writable storage`() {
        assertFalse(
            ArtworkFetchController.artworkMetadataLookupAllowed(
                forceLookup = false,
                needsMedia = false,
                writeAllowed = true,
            ),
        )
        assertFalse(
            ArtworkFetchController.artworkMetadataLookupAllowed(
                forceLookup = false,
                needsMedia = true,
                writeAllowed = false,
            ),
        )
        assertTrue(
            ArtworkFetchController.artworkMetadataLookupAllowed(
                forceLookup = false,
                needsMedia = true,
                writeAllowed = true,
            ),
        )
    }

    @Test
    fun `raw sha1 identity is forwarded as a typed ScreenScraper fingerprint`() {
        val sha1 = "a".repeat(40)
        val hash = contentHashFromStored("raw-sha1:$sha1", sizeBytes = 42L)

        assertEquals(sha1, hash?.sha1)
        assertEquals(42L, hash?.sizeBytes)
        assertNull(hash?.crc32)
        assertNull(hash?.md5)
    }

    @Test
    fun `malformed prefixed hashes fail closed`() {
        assertNull(contentHashFromStored("raw-sha1:abcd", sizeBytes = 42L))
        assertNull(contentHashFromStored("raw-md5:${"a".repeat(32)}", sizeBytes = 42L))
    }

    @Test
    fun `identical verified bytes attribute in place without rewriting`() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        var writes = 0

        val written = ArtworkFetchController.writeReplacementIfChanged(
            downloaded = bytes,
            existingLength = bytes.size.toLong(),
            openExisting = { ByteArrayInputStream(bytes) },
            write = {
                writes++
                true
            },
        )

        assertFalse(written)
        assertEquals(0, writes)
    }

    @Test
    fun `same-length differing verified bytes overwrite legacy art`() {
        val downloaded = byteArrayOf(1, 2, 3, 4)
        var writes = 0

        val written = ArtworkFetchController.writeReplacementIfChanged(
            downloaded = downloaded,
            existingLength = downloaded.size.toLong(),
            openExisting = { ByteArrayInputStream(byteArrayOf(1, 2, 9, 4)) },
            write = {
                writes++
                true
            },
        )

        assertTrue(written)
        assertEquals(1, writes)
    }

    @Test
    fun `winner flip during download commits only to the final current target`() = runBlocking {
        var current = "A"
        val resolutionReached = CompletableDeferred<Unit>()
        val releaseResolution = CompletableDeferred<Unit>()
        val writes = mutableListOf<String>()

        val install = async {
            ArtworkFetchController.withFinalArtworkTarget(
                runTransaction = { block -> block() },
                resolveCurrent = {
                    resolutionReached.complete(Unit)
                    releaseResolution.await()
                    current
                },
                missing = "missing",
                commit = { finalTarget ->
                    writes += finalTarget
                    finalTarget
                },
            )
        }
        resolutionReached.await()
        current = "B"
        releaseResolution.complete(Unit)

        assertEquals("B", install.await())
        assertEquals(listOf("B"), writes)
    }

    @Test
    fun `different byte length overwrites without reading legacy content`() {
        var opened = false
        var writes = 0

        val written = ArtworkFetchController.writeReplacementIfChanged(
            downloaded = byteArrayOf(1, 2, 3, 4),
            existingLength = 3L,
            openExisting = {
                opened = true
                ByteArrayInputStream(byteArrayOf(1, 2, 3))
            },
            write = {
                writes++
                true
            },
        )

        assertTrue(written)
        assertFalse(opened)
        assertEquals(1, writes)
    }

    @Test
    fun `recent batch no-match skips lookup while explicit per-game lookup forces it`() = runBlocking {
        var lookups = 0
        var noMatchesRecorded = 0
        var matchesCleared = 0

        val batch = ArtworkFetchController.lookupWithNoMatchCooldown(
            forceLookup = false,
            isCoolingDown = { true },
            lookup = {
                lookups++
                "match"
            },
            recordNoMatch = { noMatchesRecorded++ },
            clearNoMatch = { matchesCleared++ },
        )
        val forced = ArtworkFetchController.lookupWithNoMatchCooldown(
            forceLookup = true,
            isCoolingDown = { true },
            lookup = {
                lookups++
                "match"
            },
            recordNoMatch = { noMatchesRecorded++ },
            clearNoMatch = { matchesCleared++ },
        )

        assertNull(batch)
        assertEquals("match", forced)
        assertEquals(1, lookups)
        assertEquals(0, noMatchesRecorded)
        assertEquals(1, matchesCleared)
    }

    @Test
    fun `batch lookup records a miss and cooldown expires at fourteen days`() = runBlocking {
        var noMatchesRecorded = 0
        val now = 2_000_000_000L

        val result = ArtworkFetchController.lookupWithNoMatchCooldown<String>(
            forceLookup = false,
            isCoolingDown = { false },
            lookup = { null },
            recordNoMatch = { noMatchesRecorded++ },
            clearNoMatch = {},
        )

        assertNull(result)
        assertEquals(1, noMatchesRecorded)
        assertTrue(ArtNoMatchStore.isCoolingDown(now, now + ArtNoMatchStore.CooldownMillis - 1))
        assertFalse(ArtNoMatchStore.isCoolingDown(now, now + ArtNoMatchStore.CooldownMillis))
    }

    @Test
    fun completedArtworkItemsPublishMonotonicCountsBeforeAnEarlierItemFinishes() = runBlocking {
        val releaseFirst = CompletableDeferred<Unit>()
        val twoCompleted = CompletableDeferred<Unit>()
        val progress = mutableListOf<Int>()
        var done = 0

        val batch = async {
            ArtworkFetchController.runBoundedArtworkBatch(
                items = listOf(1, 2, 3),
                concurrency = 3,
                step = { item ->
                    if (item == 1) releaseFirst.await()
                    item
                },
                onCompleted = { _, _ ->
                    done += 1
                    progress.add(done)
                    if (done == 2) twoCompleted.complete(Unit)
                },
                publish = {},
                recover = { 0 },
            )
        }

        kotlinx.coroutines.withTimeout(1_000) { twoCompleted.await() }
        assertEquals(listOf(1, 2), progress)
        assertFalse(batch.isCompleted)
        releaseFirst.complete(Unit)
        batch.await()
        assertEquals(listOf(1, 2, 3), progress)
    }

    @Test
    fun `bounded batch isolates one failure and always publishes completed writes`() = runBlocking {
        val completed = java.util.Collections.synchronizedList(mutableListOf<Pair<Int, Boolean>>())
        var published = 0

        ArtworkFetchController.runBoundedArtworkBatch(
            items = listOf(1, 2, 3),
            concurrency = 2,
            step = { item ->
                if (item == 2) error("malformed record")
                ArtworkFetchController.ArtworkWriteResult(coverWritten = true)
            },
            onCompleted = { item, written -> completed.add(item to written.wroteAny) },
            publish = { published++ },
            recover = { ArtworkFetchController.ArtworkWriteResult() },
        )

        assertEquals(setOf(1 to true, 2 to false, 3 to true), completed.toSet())
        assertEquals(1, published)
    }

    /**
     * The runner became generic over its result so the NEW-GAME batch — the one the first-run card starts —
     * could stop being a serial `forEach` and share this machinery. [ArtworkFetchController.OneResult] is
     * that batch's result type, and `recover` exists because its failure value is not an empty write:
     * swallowing a throw into a "matched" would overcount silently.
     */
    @Test
    fun `the runner carries a result type that is not a write, and recovers to that type`() = runBlocking {
        val seen = java.util.Collections.synchronizedList(mutableListOf<ArtworkFetchController.OneResult>())

        ArtworkFetchController.runBoundedArtworkBatch(
            items = listOf("a", "b", "c"),
            concurrency = 2,
            step = { id ->
                if (id == "b") error("provider blew up")
                ArtworkFetchController.OneResult.Matched
            },
            onCompleted = { _, result -> seen.add(result) },
            publish = {},
            recover = { ArtworkFetchController.OneResult.Failed },
        )

        assertEquals(
            "a throw must become the batch's own failure value, not a success",
            listOf(ArtworkFetchController.OneResult.Failed, ArtworkFetchController.OneResult.Matched),
            listOf(
                seen.single { it == ArtworkFetchController.OneResult.Failed },
                seen.first { it == ArtworkFetchController.OneResult.Matched },
            ),
        )
        assertEquals("every sibling still completes", 3, seen.size)
    }

    /**
     * Concurrency is a ceiling, not a target. Asserted by watching how many steps are in flight at once,
     * because the failure that matters is exceeding the account's `ssuser.maxthreads` — which is what
     * ScreenScraper would see as abuse, and what no count of completions would reveal.
     */
    @Test
    fun `no more steps run at once than the account allows`() = runBlocking {
        val inFlight = java.util.concurrent.atomic.AtomicInteger(0)
        val peak = java.util.concurrent.atomic.AtomicInteger(0)

        ArtworkFetchController.runBoundedArtworkBatch(
            items = (1..24).toList(),
            concurrency = 3,
            step = {
                val now = inFlight.incrementAndGet()
                peak.updateAndGet { high -> maxOf(high, now) }
                kotlinx.coroutines.yield()
                inFlight.decrementAndGet()
                ArtworkFetchController.OneResult.Matched
            },
            onCompleted = { _, _ -> },
            publish = {},
            recover = { ArtworkFetchController.OneResult.Failed },
        )

        assertTrue("peak in-flight was ${peak.get()}, above the permitted 3", peak.get() <= 3)
    }

    /**
     * The breaker is what makes running six wide safe rather than six times as rude. Three consecutive
     * provider failures and the rest of the batch drains without touching the network — the same lever B
     * rule `fetchAllArtwork` already used, which the serial new-game loop never had.
     */
    @Test
    fun `three consecutive provider failures stop the batch reaching the network`() = runBlocking {
        val breaker = ArtworkFetchController.WriteFailureBreaker(
            limit = ArtworkFetchController.MaxConsecutiveProviderFailures,
        )
        val attempted = java.util.Collections.synchronizedList(mutableListOf<Int>())

        ArtworkFetchController.runBoundedArtworkBatch(
            items = (1..20).toList(),
            // Serial, so "consecutive" is well defined and the assertion is not racing the scheduler.
            concurrency = 1,
            step = { item ->
                if (breaker.tripped) {
                    ArtworkFetchController.OneResult.Failed
                } else {
                    attempted.add(item)
                    ArtworkFetchController.OneResult.Failed
                }
            },
            onCompleted = { _, result ->
                if (result == ArtworkFetchController.OneResult.Failed) breaker.recordFailure()
            },
            publish = {},
            recover = { ArtworkFetchController.OneResult.Failed },
        )

        assertEquals(
            "exactly the three failures that tripped it should have reached the provider",
            listOf(1, 2, 3),
            attempted.toList(),
        )
        assertTrue("and the breaker must be tripped, not merely quiet", breaker.tripped)
    }

    @Test
    fun `unknown and single-thread accounts stay sequential`() {
        assertEquals(1, ArtworkFetchController.artworkBatchConcurrency(accountMaxThreads = null))
        assertEquals(1, ArtworkFetchController.artworkBatchConcurrency(accountMaxThreads = 1))
        assertEquals(1, ArtworkFetchController.artworkBatchConcurrency(accountMaxThreads = 0))
    }

    @Test
    fun `paid account concurrency uses its live allowance below the ceiling`() {
        assertEquals(3, ArtworkFetchController.artworkBatchConcurrency(accountMaxThreads = 3))
    }

    @Test
    fun `paid account concurrency never exceeds the local ceiling`() {
        // R6-P2-03 lever E, the local safety ceiling
        // rises from 4 to 6 so a multi-thread ScreenScraper account can use more of its own live
        // allowance; pin updated with the ruling as the stated reason.
        assertEquals(6, ArtworkFetchController.ArtworkBatchConcurrency)
        assertEquals(6, ArtworkFetchController.artworkBatchConcurrency(accountMaxThreads = 8))
    }

    @Test
    fun `bounded read accepts a stream at the byte limit`() {
        val bytes = ByteArray(16) { 7 }

        assertTrue(
            bytes.contentEquals(
                requireNotNull(ArtworkFetchController.readBounded(ByteArrayInputStream(bytes), bytes.size.toLong())),
            ),
        )
    }

    @Test
    fun `bounded read rejects a chunked stream that exceeds the byte limit`() {
        val bytes = ByteArray(17) { 7 }

        assertNull(ArtworkFetchController.readBounded(ByteArrayInputStream(bytes), maxBytes = 16))
    }

    @Test
    fun `bounded media read rejects an empty response`() {
        assertNull(ArtworkFetchController.readBounded(ByteArrayInputStream(byteArrayOf()), maxBytes = 16))
    }

    @Test
    fun `managed cache creation never replaces an existing target or its user sibling`() {
        val parent = createTempDir(prefix = "managed-art-")
        try {
            val userSibling = File(parent, "Metroid Fusion.png").apply { writeText("user art") }
            val managedTarget = File(parent, "media_art/Metroid Fusion.png").apply {
                parentFile?.mkdirs()
                writeText("existing managed art")
            }

            assertFalse(ArtworkFetchController.writeNewFile(managedTarget, "remote art".toByteArray()))
            assertEquals("user art", userSibling.readText())
            assertEquals("existing managed art", managedTarget.readText())
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun `failed managed overwrite preserves the complete prior file`() {
        val parent = createTempDir(prefix = "managed-art-replace-")
        try {
            val target = File(parent, "media_art/Metroid Fusion-hero.png").apply {
                parentFile?.mkdirs()
                writeText("OLD")
            }

            assertFalse(
                ArtworkFetchController.writeNewFile(
                    target = target,
                    bytes = "NEW".toByteArray(),
                    overwrite = true,
                    move = { _, _, _ -> false },
                ),
            )
            assertEquals("OLD", target.readText())
            assertTrue(parent.walkTopDown().none { it.name.endsWith(".mimir-stage") })
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun `managed overwrite atomically publishes the complete replacement`() {
        val parent = createTempDir(prefix = "managed-art-replace-")
        try {
            val target = File(parent, "media_art/Metroid Fusion-hero.png").apply {
                parentFile?.mkdirs()
                writeText("OLD")
            }

            assertTrue(
                ArtworkFetchController.writeNewFile(
                    target = target,
                    bytes = "NEW".toByteArray(),
                    overwrite = true,
                ),
            )
            assertEquals("NEW", target.readText())
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun `SAF managed child lookup uses display names instead of path-shaped document IDs`() {
        val children = listOf(
            ArtworkFetchController.SafChild("opaque-9f", "Metroid Fusion-logo.png", "image/png"),
            ArtworkFetchController.SafChild("provider:dir:0042", "media_art", DocumentsContract.Document.MIME_TYPE_DIR),
        )

        assertEquals(
            "provider:dir:0042",
            ArtworkFetchController.namedSafChild(children, "media_art")?.documentId,
        )
        assertEquals(
            "opaque-9f",
            ArtworkFetchController.namedSafChild(children, "Metroid Fusion-logo.png")?.documentId,
        )
    }

    // ── Write preflight + failure breaker (RP5 read-only-grant regression, 2026-07-16) ─────────────
    // A read-only persisted tree grant made every createDocument throw SecurityException AFTER each clip
    // had already been downloaded. The preflight refuses doomed games before any network; the breaker
    // stops a batch whose writes fail systemically mid-run. Context-free branches use the same
    // ContextWrapper(null) crash-canary convention as the FA-M5 gate tests above.

    /**
     * Was `fetchAllPreviewVideos no-ops with the gate closed before touching context` — a gate test for a
     * library-wide Hero Motion download that no longer exists. Kept as its inverse rather than deleted, because the
     * useful assertion survived the feature: no bulk path may request a CLIP, on any input.
     *
     * The per-game Customize Media picker still fetches one on request. That is a different seam and is
     * deliberately untouched — the ruling retired the download from enrich, not the ability to have one.
     */
    @Test
    fun `no combination of inputs makes the bulk path ask for a clip`() {
        val every = ArtworkFetchController.ManagedMediaSlot.entries.toSet()
        listOf(true, false).forEach { force ->
            listOf(true, false).forEach { unattributed ->
                listOf(emptySet(), every).forEach { present ->
                    listOf(true, false).forEach { cooling ->
                        val slots = ArtworkFetchController.artworkSlotsToFetch(
                            present = present,
                            unattributed = unattributed,
                            forceLookup = force,
                            slotCoolingDown = { cooling },
                        )
                        assertFalse(
                            "force=$force unattributed=$unattributed present=$present cooling=$cooling " +
                                "asked for a clip; enrich must download Hero ART, never motion",
                            ArtworkFetchController.ManagedMediaSlot.CLIP in slots,
                        )
                    }
                }
            }
        }
    }

    // ── The launch sound  is NOT an Enrich slot any more (Settings > Music, same day) ──────

    private val allSlots = ArtworkFetchController.ManagedMediaSlot.entries
    private val audio = ArtworkFetchController.ManagedMediaSlot.AUDIO
    private val clip = ArtworkFetchController.ManagedMediaSlot.CLIP

    /**
     * Audio is downloaded only by the game-music operation. This was `audio is asked for when absent and not cooling down and never when present` and
     * `audio cools down on its own negative and has no legacy repair`: the first-run enrich, Settings Download and the
     * per-game Enrich all asked for the sound. None of them does now, on any input: not absent, not cooling, not on an
     * explicit press that bypasses every cooldown. The sound is the game-music download's alone.
     */
    @Test
    fun `no combination of inputs makes any Enrich path ask for the sound`() {
        var combinations = 0
        for (mask in 0 until (1 shl allSlots.size)) {
            val present = allSlots.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
            listOf(true, false).forEach { force ->
                listOf(true, false).forEach { unattributed ->
                    listOf(true, false).forEach { cooling ->
                        combinations++
                        assertFalse(
                            "present=$present force=$force unattributed=$unattributed cooling=$cooling asked for the sound",
                            audio in ArtworkFetchController.artworkSlotsToFetch(
                                present = present,
                                unattributed = unattributed,
                                forceLookup = force,
                                slotCoolingDown = { cooling },
                            ),
                        )
                    }
                }
            }
        }
        assertEquals("every subset of the slots, four ways", 64 * 8, combinations)
    }

    /**
     * `needsMedia` is `toFetch.isNotEmpty()`, so a game whose only missing piece is its sound has an empty set and an
     * automatic batch makes no lookup for it: nothing of an Enrich is spent on a sound.
     */
    @Test
    fun `a game whose only missing piece is its sound pays for no lookup`() {
        val everythingButSound = allSlots.toSet() - audio
        val due = ArtworkFetchController.artworkSlotsToFetch(
            present = everythingButSound,
            unattributed = false,
            forceLookup = false,
            slotCoolingDown = { false },
        )
        assertEquals(emptySet<ArtworkFetchController.ManagedMediaSlot>(), due)
        assertFalse(
            "no media due and no explicit press: no lookup",
            ArtworkFetchController.artworkMetadataLookupAllowed(forceLookup = false, needsMedia = due.isNotEmpty(), writeAllowed = true),
        )
        // The sound's own negative has no say over the images either way.
        val soundCooling = ArtworkFetchController.artworkSlotsToFetch(
            present = emptySet(),
            unattributed = false,
            forceLookup = false,
            slotCoolingDown = { it == audio },
        )
        assertEquals(
            setOf(
                ArtworkFetchController.ManagedMediaSlot.COVER,
                ArtworkFetchController.ManagedMediaSlot.LOGO,
                ArtworkFetchController.ManagedMediaSlot.HERO,
                ArtworkFetchController.ManagedMediaSlot.PHYSICAL,
            ),
            soundCooling,
        )
    }

    @Test
    fun `audio never makes a clip due, whatever is asked`() {
        listOf(true, false).forEach { force ->
            listOf(true, false).forEach { unattributed ->
                listOf(emptySet(), setOf(audio), allSlots.toSet()).forEach { present ->
                    listOf(true, false).forEach { cooling ->
                        assertFalse(
                            clip in ArtworkFetchController.artworkSlotsToFetch(
                                present = present,
                                unattributed = unattributed,
                                forceLookup = force,
                                slotCoolingDown = { cooling },
                            ),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `each slot has its own negative key and audio takes the audio prefix`() {
        val keys = allSlots.map { ArtworkFetchController.artNoMatchSlotKey(it, "game-1") }
        assertEquals("audio:game-1", ArtworkFetchController.artNoMatchSlotKey(audio, "game-1"))
        assertEquals("the keys must not collide", keys.size, keys.toSet().size)
        assertEquals(
            "physical keeps the literal it always had",
            "physical-media:game-1",
            ArtworkFetchController.artNoMatchSlotKey(ArtworkFetchController.ManagedMediaSlot.PHYSICAL, "game-1"),
        )
        // The store must know the same prefix, or a rekeyed identity would drop the sound's negative.
        allSlots.forEach { slot ->
            val prefix = ArtworkFetchController.artNoMatchSlotKey(slot, "").takeIf { it.isNotEmpty() }
            assertTrue("$slot prefix '$prefix' missing from ArtNoMatchStore.SlotPrefixes", prefix in ArtNoMatchStore.SlotPrefixes)
        }
    }

    @Test
    fun `audio asks the same clip URL as the clip slot and nothing else`() {
        val withClip = MetadataRecord(
            canonicalTitle = "Test",
            origin = MetadataOrigin.SCREENSCRAPER,
            previewVideoUri = "https://www.screenscraper.fr/clip.mp4",
        )
        val withoutClip = withClip.copy(previewVideoUri = null, boxArtUri = "https://www.screenscraper.fr/box.png")

        assertTrue(ArtworkFetchController.slotHasProviderUrl(withClip, audio))
        assertFalse(ArtworkFetchController.slotHasProviderUrl(withoutClip, audio))
        assertFalse("cover art is not a sound", ArtworkFetchController.slotHasProviderUrl(withoutClip.copy(boxArtUri = null), audio))
    }

    /**
     * The hazard the image slots do not have: "the record has a URL" CLEARS an image slot's negative, but for the sound
     * a URL says nothing about whether the clip has audio. The negative for a silent clip must survive the next lookup,
     * or every batch would download the same silent clip again. So a lookup records a negative only when there is no clip
     * at all, and never clears one (only a written sound does).
     */
    @Test
    fun `a lookup records an audio negative only when the record has no clip at all`() {
        assertTrue(audioNegativeAfterLookup(asked = true, clipUrlPresent = false))
        assertFalse(audioNegativeAfterLookup(asked = true, clipUrlPresent = true))
        assertFalse(
            "a slot never gets a negative for a question it was not asked",
            audioNegativeAfterLookup(asked = false, clipUrlPresent = false),
        )
        assertFalse(audioNegativeAfterLookup(asked = false, clipUrlPresent = true))
    }

    @Test
    fun `the clip is downloaded under its own cap and not the image cap`() {
        assertEquals(12L * 1024 * 1024, ArtworkFetchController.MaxVideoBytes)
        assertTrue(ArtworkFetchController.MaxVideoBytes > ArtworkFetchController.MaxImageBytes)
        assertEquals(8L * 1024 * 1024, ArtworkFetchController.MaxImageBytes)
    }

    @Test
    fun `the written sound is an m4a and every other slot keeps what it had`() {
        assertEquals("m4a" to "audio/mp4", ArtworkFetchController.managedMediaExtAndMime(audio))
        assertEquals("mp4" to "video/mp4", ArtworkFetchController.managedMediaExtAndMime(clip))
        listOf(
            ArtworkFetchController.ManagedMediaSlot.COVER,
            ArtworkFetchController.ManagedMediaSlot.LOGO,
            ArtworkFetchController.ManagedMediaSlot.HERO,
            ArtworkFetchController.ManagedMediaSlot.PHYSICAL,
        ).forEach { assertEquals("png" to "image/png", ArtworkFetchController.managedMediaExtAndMime(it)) }
        assertEquals(
            "Metroid Fusion-audio.m4a",
            ArtworkFetchController.managedDisplayName("Metroid Fusion.gba", ArtworkFetchController.AudioSuffix, "m4a"),
        )
    }

    /**
     * FLIPPED DELIBERATELY. This was `an audio write lets a game count as matched and publish even when no image changed`
     * (`ArtworkWriteResult(launchAudioWritten = true)`). An Enrich result carries no sound at all now, so a game can
     * never count as matched, or be published, because of one.
     */
    @Test
    fun `an Enrich write result carries no sound`() {
        val fields = ArtworkFetchController.ArtworkWriteResult::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue(fields.toString(), fields.none { "audio" in it || "sound" in it })
        assertFalse(ArtworkFetchController.ArtworkWriteResult().wroteAny)
        assertFalse(ArtworkFetchController.ArtworkWriteResult().matchedAny)
    }

    /**
     * FLIPPED DELIBERATELY. This was `the negative clear loop skips audio and the download waits for the audio slot to be
     * due`, which pinned the sound's request inside the per-game Enrich body. The body keeps the one line that matters
     * (the clear loop must never decide the sound's negative) and now says nothing else about the sound.
     */
    @Test
    fun `the per-game Enrich body never fetches or decides a sound`() {
        val source = java.io.File(
            listOf(
                "app/src/main/kotlin/com/gruning/mimir/rewrite/ArtworkFetchController.kt",
                "src/main/kotlin/com/gruning/mimir/rewrite/ArtworkFetchController.kt",
            ).first { java.io.File(it).isFile },
        ).readText()
        val body = source
            .substringAfter("private suspend fun fetchArtworkForGame(")
            .substringBefore("private suspend fun fetchImage(")

        assertTrue(
            "the clear loop still skips AUDIO, or a clip URL would clear the sound's negative on every lookup",
            body.contains("if (slot == ManagedMediaSlot.AUDIO) return@forEach"),
        )
        listOf(
            "fetchLaunchAudio",
            "audioUriFor",
            "audioNegativeAfterLookup",
            "ManagedMediaSlot.AUDIO in toFetch",
            "existingLaunchAudio",
            "launchAudioWrite",
        ).forEach { assertFalse("the Enrich body must not mention `$it`", body.contains(it)) }
        assertFalse(
            "the sound must never be fetched through the image path",
            body.contains("slot = ManagedMediaSlot.AUDIO"),
        )
        assertFalse("downloadGated is the image path", body.contains("downloadGated"))
    }

    @Test
    fun `preflight passes plain-file games without touching the context`() {
        val preflight = ArtworkFetchController.SafWritePreflight(ContextWrapper(null))

        assertTrue(preflight.allowsWrite(sourceDocumentUri = null, managedPath = "/roms/Metroid Fusion.gba"))
    }

    @Test
    fun `preflight refuses games with no writable destination shape`() {
        val preflight = ArtworkFetchController.SafWritePreflight(ContextWrapper(null))

        assertFalse(preflight.allowsWrite(sourceDocumentUri = null, managedPath = null))
        assertFalse(preflight.allowsWrite(sourceDocumentUri = "file:///roms/x.gba", managedPath = null))
    }

    @Test
    fun `external-storage docId maps to its on-disk path and refuses traversal`() {
        assertEquals(
            File("/storage/F97F-DAEE/Roms/N64/Mario Kart 64 (Europe) (Rev 1).z64"),
            ArtworkFetchController.externalStorageDocFile("F97F-DAEE:Roms/N64/Mario Kart 64 (Europe) (Rev 1).z64"),
        )
        assertNull(ArtworkFetchController.externalStorageDocFile("F97F-DAEE:Roms/../../etc/hosts"))
        assertNull(ArtworkFetchController.externalStorageDocFile("no-colon-doc-id"))
        assertNull(ArtworkFetchController.externalStorageDocFile("F97F-DAEE:"))
    }

    @Test
    fun `write-failure breaker trips at the consecutive limit and resets on success`() {
        val breaker = ArtworkFetchController.WriteFailureBreaker(limit = 3)

        breaker.recordFailure()
        breaker.recordFailure()
        assertFalse(breaker.tripped)

        breaker.recordSuccess()
        breaker.recordFailure()
        breaker.recordFailure()
        assertFalse(breaker.tripped)

        breaker.recordFailure()
        assertTrue(breaker.tripped)
    }

    @Test
    fun `fetch result stays compatible and carries the write-blocked signal`() {
        val plain = ArtworkFetchController.FetchResult(total = 4, matched = 1)

        assertFalse(plain.writeBlocked)
        assertEquals(3, plain.unmatched)
        assertTrue(ArtworkFetchController.FetchResult(total = 4, matched = 0, writeBlocked = true).writeBlocked)
    }

    @Test
    fun `per-game isolation never swallows coroutine cancellation`() {
        var rethrown = false
        try {
            ArtworkFetchController.runArtworkStep<Unit> { throw CancellationException("stop") }
        } catch (_: CancellationException) {
            rethrown = true
        }

        assertTrue(rethrown)
    }

    @Test
    fun `Library Enrich visible title is bounded additional name evidence only`() {
        assertEquals(
            listOf("Resident Evil 2"),
            libraryEnrichAdditionalCandidateNames("  Resident Evil 2  "),
        )
        assertEquals(emptyList<String>(), libraryEnrichAdditionalCandidateNames("   "))
        assertEquals(emptyList<String>(), libraryEnrichAdditionalCandidateNames(""))
        // One visible title → at most one additional rung; never expands into member/all-logical fields.
        assertEquals(
            listOf("Biohazard 2"),
            libraryEnrichAdditionalCandidateNames("Biohazard 2"),
        )
    }

    @Test
    fun `screenScraperMetadataQuery keeps member truth first and appends distinct Library Enrich title`() {
        val member = GameEntity(
            entryKey = "re2-primary",
            gameId = "re2",
            platformId = "ps1",
            contentKind = "GAME",
            displayName = "Resident Evil 2 (USA).chd",
            title = "Biohazard 2",
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
            sourceDocumentUri = "content://root/re2.chd",
            managedPath = null,
            relativePath = "Resident Evil 2 (USA).chd",
            discNumber = 0,
            duplicateGroupId = "re2",
            isPrimaryMember = true,
            duplicateGroupKind = null,
            canonicalTitle = "Resident Evil 2",
        )

        assertEquals(
            listOf("Resident Evil 2", "Biohazard 2", "Resident Evil 2 Dual Shock"),
            ArtworkFetchController.screenScraperMetadataQuery(
                member,
                libraryEnrichAdditionalCandidateNames("Resident Evil 2 Dual Shock"),
            ).candidateNames,
        )
        assertEquals(
            listOf("Resident Evil 2", "Biohazard 2"),
            ArtworkFetchController.screenScraperMetadataQuery(
                member,
                libraryEnrichAdditionalCandidateNames("Resident Evil 2"),
            ).candidateNames,
        )
    }

    @Test
    fun `unexpected artwork-step failure normalizes to lookupFailed Failed not NoMatch`() {
        fun toOneResult(written: ArtworkFetchController.ArtworkWriteResult) = when {
            written.lookupFailed -> ArtworkFetchController.OneResult.Failed
            written.matchedAny -> ArtworkFetchController.OneResult.Matched
            else -> ArtworkFetchController.OneResult.NoMatch
        }

        val failed = ArtworkFetchController.artworkWriteResultFromStep(
            Result.failure(IllegalStateException("provider blew up")),
        )
        assertTrue(failed.lookupFailed)
        assertFalse(failed.matchedAny)
        assertEquals(ArtworkFetchController.OneResult.Failed, toOneResult(failed))
        assertNotEquals(ArtworkFetchController.OneResult.NoMatch, toOneResult(failed))

        val emptySuccess = ArtworkFetchController.artworkWriteResultFromStep(
            Result.success(ArtworkFetchController.ArtworkWriteResult()),
        )
        assertFalse(emptySuccess.lookupFailed)
        assertEquals(ArtworkFetchController.OneResult.NoMatch, toOneResult(emptySuccess))

        val matched = ArtworkFetchController.artworkWriteResultFromStep(
            Result.success(ArtworkFetchController.ArtworkWriteResult(coverWritten = true)),
        )
        assertFalse(matched.lookupFailed)
        assertEquals(ArtworkFetchController.OneResult.Matched, toOneResult(matched))
    }

    private fun gameWithPlatform(platformId: String, gameId: String = platformId): GameEntity = GameEntity(
        entryKey = "$gameId-primary",
        gameId = gameId,
        platformId = platformId,
        contentKind = "GAME",
        displayName = "Test Game",
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
        sourceDocumentUri = "content://root/$gameId",
        managedPath = null,
        relativePath = "$gameId.rom",
        discNumber = 0,
        duplicateGroupId = gameId,
        isPrimaryMember = true,
        duplicateGroupKind = null,
    )

    @Test
    fun `gamesWithProviderCoverage excludes a platform ScreenScraper cannot identify`() {
        // pc_games has no ScreenScraperSystemMap entry (R-24) -- every lookup would return
        // PlatformUnsupported before any network request, so the batch must not walk it.
        val pcGame = gameWithPlatform(PlatformId.PC_GAMES.value)
        assertEquals(emptyList<GameEntity>(), gamesWithProviderCoverage(listOf(pcGame)))
    }

    @Test
    fun `gamesWithProviderCoverage keeps a platform ScreenScraper can identify`() {
        // Differential guard: a mapped platform (ps1 -> systeme id 57) must survive the filter
        // unchanged, proving this isn't an accidental blanket exclusion.
        val ps1Game = gameWithPlatform("ps1")
        assertEquals(listOf(ps1Game), gamesWithProviderCoverage(listOf(ps1Game)))
    }

    @Test
    fun `gamesWithProviderCoverage is driven by systemeIdFor coverage not a pc_games literal`() {
        // An arbitrary platform id that is NOT "pc_games" and has no ScreenScraper system id must
        // also be excluded -- proving the filter asks OnlineProviderPlatformProjection's coverage
        // map, never a literal `platformId == "pc_games"` check. If ScreenScraper ever gains a
        // pc_games system id, this filter must open on its own with no code change here.
        val unmapped = gameWithPlatform("totally_unmapped_platform_xyz")
        assertEquals(emptyList<GameEntity>(), gamesWithProviderCoverage(listOf(unmapped)))

        val ps1Game = gameWithPlatform("ps1", gameId = "ps1-game")
        val pcGame = gameWithPlatform(PlatformId.PC_GAMES.value, gameId = "pc-game")
        assertEquals(listOf(ps1Game), gamesWithProviderCoverage(listOf(pcGame, ps1Game)))
    }

    // ── pcArtworkLookupPlan: the ONE identification decision fetchOnePcArtwork acts on,
    // pure so the JVM can pin it without Room/Keystore/network. PcEnrichSteamGridDbRoutingWiringTest
    // pins that the route actually consumes this function's verdicts.

    @Test
    fun `an empty managed path with a title plans a name search over the sgdb candidates for that title`() {
        // A managed path alone still resolves no app id for a SAF-only row (e.g. a Nova test row
        // `title:pc_games:blasphemous 2`) -- steamAppIdForManagedPath stays null when the body cannot be
        // read or the file is not `.steam`. R6-P1-04 adds a SAF-based fallback (steamAppIdForShortcut,
        // exercised at the ArtworkFetchController/CustomHeroStore call sites, not reachable from this pure
        // pcArtworkLookupPlan/steamAppIdForManagedPath unit test).
        assertNull(steamAppIdForManagedPath(""))

        val plan = pcArtworkLookupPlan(steamAppId = steamAppIdForManagedPath(""), title = "Blasphemous 2")

        assertEquals(
            PcArtworkLookupPlan.NameSearch(title = "Blasphemous 2", candidates = sgdbSearchCandidates("Blasphemous 2")),
            plan,
        )
        assertEquals(listOf("Blasphemous 2"), (plan as PcArtworkLookupPlan.NameSearch).candidates)
    }

    @Test
    fun `a name search keeps the full title as the identity and the stripped angles only as queries`() {
        // sgdbTitleMatches validates every hit against `title`, so a bare "Castlevania" hit for the
        // second angle can never become the art identity of "Castlevania - Symphony of the Night".
        val plan = pcArtworkLookupPlan(steamAppId = null, title = "Castlevania - Symphony of the Night")

        assertEquals(
            PcArtworkLookupPlan.NameSearch(
                title = "Castlevania - Symphony of the Night",
                candidates = listOf("Castlevania - Symphony of the Night", "Castlevania"),
            ),
            plan,
        )
    }

    @Test
    fun `a shortcut app id plans the exact Steam route regardless of the title`() {
        // The Odin 3 test row: `/storage/6A7F-FCFE/ROMs/pcgames/Blasphemous 2.steam` parses to an app id.
        // R-23: an exact key is never second-guessed by a name, not even a blank one.
        assertEquals(PcArtworkLookupPlan.SteamApp(2114740), pcArtworkLookupPlan(2114740, "Blasphemous 2"))
        assertEquals(PcArtworkLookupPlan.SteamApp(2114740), pcArtworkLookupPlan(2114740, ""))
    }

    @Test
    fun `no app id and a blank or tag-only title plans NoTitle, never a search and never a provider miss`() {
        assertEquals(PcArtworkLookupPlan.NoTitle, pcArtworkLookupPlan(null, ""))
        assertEquals(PcArtworkLookupPlan.NoTitle, pcArtworkLookupPlan(null, "   "))
        // sgdbSearchCandidates cleans a tag-only label to nothing: there is no name to ask with.
        assertEquals(PcArtworkLookupPlan.NoTitle, pcArtworkLookupPlan(null, "(USA) [Disc 1]"))
    }

    @Test
    fun `a resolved SteamGridDB game is one url segment shared by every asset route`() {
        // Both identification stories collapse to the id-type segment SteamGridDB's grids/heroes/icons
        // routes accept; the app-id helper fetchMissing already uses delegates to the same value, so
        // the portrait, square and hero lanes cannot disagree about which game they fetch for.
        assertEquals("steam/2114740", SteamGridDbGameRef.SteamApp(2114740).urlSegment)
        assertEquals("game/5337459", SteamGridDbGameRef.Game(5337459L).urlSegment)
        assertEquals(
            SteamGridDbGameRef.SteamApp(2114740).urlSegment,
            steamGridDbUrlSegmentFromAppId("pc-game", mapOf("pc-game" to 2114740)),
        )
    }

    // ── per-slot provider-absent negatives for idempotency ────────────────────────────────────

    @Test
    fun `a slot the provider answered without is not fetched again inside its cooldown`() {
        // Flipped back deliberately with Settings > Music (2026-09-30): the launch sound briefly joined this set as a fourth
        // cooling slot, and no Enrich asks for it any more, so the fixture is the original three-slot one again.
        val cooling = setOf(
            ArtworkFetchController.ManagedMediaSlot.LOGO,
            ArtworkFetchController.ManagedMediaSlot.PHYSICAL,
        )
        val automatic = ArtworkFetchController.artworkSlotsToFetch(
            present = setOf(ArtworkFetchController.ManagedMediaSlot.COVER, ArtworkFetchController.ManagedMediaSlot.HERO),
            unattributed = false,
            forceLookup = false,
            slotCoolingDown = { it in cooling },
        )
        // At base: no seam existed (ManagedMediaSlot was private, artworkSlotsToFetch didn't exist), and
        // the inline code unconditionally set needsFlatArt/needsPhysicalMedia's LOGO analogue, paying one
        // jeuInfos per run for every absent, cooling-down slot.
        assertEquals(emptySet<ArtworkFetchController.ManagedMediaSlot>(), automatic)

        val explicit = ArtworkFetchController.artworkSlotsToFetch(
            present = setOf(ArtworkFetchController.ManagedMediaSlot.COVER, ArtworkFetchController.ManagedMediaSlot.HERO),
            unattributed = false,
            forceLookup = true,
            slotCoolingDown = { it in cooling },
        )
        assertEquals(
            setOf(
                ArtworkFetchController.ManagedMediaSlot.LOGO,
                ArtworkFetchController.ManagedMediaSlot.PHYSICAL,
            ),
            explicit,
        )
    }

    // ── R6-P2-03 lever F: serial SteamGridDB PC pass, run after the ScreenScraper batch ─────────────

    @Test
    fun `serial PC pass stops immediately on a provider failure and folds matches into progress`() = runBlocking {
        val calls = mutableListOf<String>()
        val completed = mutableListOf<Pair<String, ArtworkFetchController.OneResult>>()
        var delays = 0

        ArtworkFetchController.runSerialPcArtworkPass(
            gameIds = listOf("pc-1", "pc-2", "pc-3"),
            delayBetweenRows = { delays++ },
            fetchOne = { id ->
                calls += id
                when (id) {
                    "pc-1" -> ArtworkFetchController.OneResult.Matched
                    "pc-2" -> ArtworkFetchController.OneResult.Failed
                    else -> ArtworkFetchController.OneResult.Matched
                }
            },
            onCompleted = { id, result -> completed += id to result },
        )

        // pc-3 is never reached: the pass stops the moment the provider itself fails.
        assertEquals(listOf("pc-1", "pc-2"), calls)
        assertEquals(
            listOf(
                "pc-1" to ArtworkFetchController.OneResult.Matched,
                "pc-2" to ArtworkFetchController.OneResult.Failed,
            ),
            completed,
        )
        // Exactly one pace delay: between pc-1 and pc-2. None after the stop.
        assertEquals(1, delays)
    }
}
