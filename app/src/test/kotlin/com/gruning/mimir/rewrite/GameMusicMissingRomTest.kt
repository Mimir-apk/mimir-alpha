package com.gruning.mimir.rewrite

import android.content.ContextWrapper
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.library.ChangedMedia
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A game whose ROM document is gone is not asked about (Odin, combined50: four stale QA-fixture rows whose folder had been
 * deleted passed the preflight on the tree grant that outlives it, so every press downloaded and transcoded four clips and
 * then failed to write them). It is a skip, never a failure, and it costs no lookup, no download and no transcode.
 * Fakes only: the provider, the codec and the network never run here.
 */
class GameMusicMissingRomTest {
    private fun changed(n: Int) = ChangedMedia(GameId("snes/game-$n"), sourceDocumentUri = null, managedPath = "/roms/game-$n.sfc")

    private fun eligibility(romPresent: Boolean) = gameMusicEligibility(
        platformId = "snes",
        hasSound = { false },
        writeAllowed = { true },
        audioCoolingDown = { false },
        gameCoolingDown = { false },
        romPresent = { romPresent },
    )

    @Test
    fun `a game whose ROM is gone is ROM_MISSING and a present one stays eligible`() {
        assertEquals(GameMusicEligibility.ROM_MISSING, eligibility(romPresent = false))
        assertEquals(GameMusicEligibility.ELIGIBLE, eligibility(romPresent = true))
    }

    @Test
    fun `the sound already there and the grant are answered before the ROM is asked`() {
        var asked = 0
        fun verdict(hasSound: Boolean, writeAllowed: Boolean) = gameMusicEligibility(
            platformId = "snes",
            hasSound = { hasSound },
            writeAllowed = { writeAllowed },
            audioCoolingDown = { false },
            gameCoolingDown = { false },
            romPresent = { asked++; false },
        )
        assertEquals(GameMusicEligibility.HAS_SOUND, verdict(hasSound = true, writeAllowed = true))
        assertEquals("a revoked grant is the grant's fault, not the file's", GameMusicEligibility.WRITE_BLOCKED, verdict(hasSound = false, writeAllowed = false))
        assertEquals("neither verdict cost a provider query", 0, asked)
    }

    @Test
    fun `the plan leaves a missing ROM out of the games to ask and keeps it in the total`() {
        val verdicts = mapOf(
            "a" to GameMusicEligibility.ELIGIBLE,
            "b" to GameMusicEligibility.ROM_MISSING,
            "c" to GameMusicEligibility.HAS_SOUND,
        )
        val plan = planGameMusic(verdicts.keys.toList()) { verdicts.getValue(it) }

        assertEquals(listOf("a"), plan.eligible)
        assertEquals(3, plan.total)
        assertEquals(1, plan.have)
    }

    private class Effects {
        var lookups = 0
        var cuts = 0
    }

    private fun step(romPresent: Boolean, e: Effects): GameMusicStepResult = runBlocking {
        gameMusicStepWith(
            forced = false,
            alreadyHasSound = { false },
            romPresent = { romPresent },
            lookup = { e.lookups++; GameMusicLookup.Loaded("clip") },
            clearGameNegative = {},
            recordGameNegative = {},
            recordNoClipNegative = {},
            cutSound = { e.cuts++; GameMusicCut(changed(1), LaunchAudioStepOutcome.Written) },
        )
    }

    @Test
    fun `a missing ROM costs no lookup and no download and is a skip`() {
        val e = Effects()
        val result = step(romPresent = false, e)

        assertEquals(GameMusicStepOutcome.SKIPPED, result.outcome)
        assertFalse(result.lookupFailed)
        assertEquals(0, e.lookups)
        assertEquals("no clip is downloaded or transcoded for it", 0, e.cuts)
    }

    @Test
    fun `a present ROM still looks up and downloads`() {
        val e = Effects()
        val result = step(romPresent = true, e)

        assertEquals(GameMusicStepOutcome.WRITTEN, result.outcome)
        assertEquals(1, e.lookups)
        assertEquals(1, e.cuts)
    }

    @Test
    fun `in a batch the missing ROM is tallied as skipped, never failed, and only the present ones download`() = runBlocking {
        val downloaded = mutableListOf<Int>()
        val present = mapOf(1 to true, 2 to false, 3 to true)
        val tally = runGameMusicBatch(
            items = listOf(1, 2, 3),
            concurrency = 1,
            step = { n ->
                gameMusicStepWith(
                    forced = false,
                    alreadyHasSound = { false },
                    romPresent = { present.getValue(n) },
                    lookup = { GameMusicLookup.Loaded("clip") },
                    clearGameNegative = {},
                    recordGameNegative = {},
                    recordNoClipNegative = {},
                    cutSound = { downloaded += n; GameMusicCut(changed(n), LaunchAudioStepOutcome.Written) },
                )
            },
            onProgress = {},
            publish = {},
        )

        assertEquals(listOf(1, 3), downloaded)
        assertEquals(2, tally.written)
        assertEquals(1, tally.skipped)
        assertEquals(0, tally.failed)
        assertNull(tally.failure)
    }

    // ── the preflight's own question ─────────────────────────────────────────────────────────────────

    private val doc = "content://com.android.externalstorage.documents/tree/primary%3AROMs/document/primary%3AROMs%2Fsnes%2FChrono%20Trigger.sfc"

    private fun preflight(present: (String) -> Boolean) =
        ArtworkFetchController.SafWritePreflight(ContextWrapper(null), romPresent = present)

    @Test
    fun `a ROM document that no longer resolves is missing, even while the tree grant stands`() {
        assertTrue(preflight { false }.romMissing(sourceDocumentUri = doc, managedPath = null))
        assertFalse(preflight { true }.romMissing(sourceDocumentUri = doc, managedPath = null))
    }

    @Test
    fun `each ROM document is asked about once, plain-file and path-less games never`() {
        val asked = mutableListOf<String>()
        val preflight = preflight { asked += it; false }

        assertTrue(preflight.romMissing(doc, null))
        assertTrue(preflight.romMissing(doc, null))
        assertEquals("the provider is asked once per document", listOf(doc), asked)

        assertFalse(preflight.romMissing(null, "/roms/Metroid Fusion.gba"))
        assertFalse(preflight.romMissing(doc, "/roms/Metroid Fusion.gba"))
        assertFalse(preflight.romMissing(null, null))
        assertFalse(preflight.romMissing("file:///roms/x.gba", null))
        assertEquals(listOf(doc), asked)
    }

    // ── the wiring, pinned in the source the way the codec seams are ─────────────────────────────────

    @Test
    fun `the batch classifier, the per-game press and the step all ask whether the ROM is still there`() {
        val batch = source("GameMusicBatch.kt")
        assertEquals(
            "the plan's classifier and the per-game press each pass the preflight's answer",
            2,
            Regex("""romPresent = \{ !preflight\.romMissing\(game\.sourceDocumentUri, game\.managedPath\) \}""").findAll(batch).count(),
        )
        val controller = source("ArtworkFetchController.kt")
        val gameMusicStep = controller.substringAfter("internal suspend fun fetchGameMusicForGame(").substringBefore("internal suspend fun fetchLaunchAudio(")
        assertTrue(gameMusicStep, gameMusicStep.contains("romPresent = { !SafWritePreflight(context).romMissing(g.sourceDocumentUri, g.managedPath) }"))
    }

    @Test
    fun `allowsWrite does not answer for a missing ROM, because the Enrich card words its false as read-only folder access`() {
        val controller = source("ArtworkFetchController.kt")
        val allowsWrite = controller.substringAfter("fun allowsWrite(").substringBefore("private fun treeGrantsWrite")
        assertFalse(allowsWrite, allowsWrite.contains("romMissing") || allowsWrite.contains("romPresent"))
    }

    private fun source(name: String): String {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "app/src/main/kotlin/com/gruning/mimir/rewrite/$name")
            if (candidate.isFile) return candidate.readText()
            if (File(dir, "settings.gradle.kts").isFile) break
            dir = dir.parentFile
        }
        error("$name not found from ${File(".").absolutePath}")
    }
}
