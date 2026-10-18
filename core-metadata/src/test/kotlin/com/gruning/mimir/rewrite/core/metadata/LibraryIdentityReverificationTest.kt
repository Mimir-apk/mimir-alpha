package com.gruning.mimir.rewrite.core.metadata

import com.gruning.mimir.rewrite.core.storage.SourceRootMutationCoordinator
import com.gruning.mimir.rewrite.core.storage.dao.GameDao
import com.gruning.mimir.rewrite.core.storage.entity.GameEntity
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guard on the name-first trade: [LibraryIdentityEnricher.reverifyNameResolved].
 *
 * Every test here pins a property that is invisible at the call site and would be silently lost by a
 * plausible tidy-up. None of them assert that hashing works — [LibraryIdentityEnricherExactHashTest]
 * already owns that. These ask the narrower question the pass exists for: *which* games it reads, which
 * it refuses to read, and what it is forbidden from writing.
 */
class LibraryIdentityReverificationTest {

    /** Any byte read is a failure. Reused from the name-first routing test's approach, and for the same reason. */
    private class ForbiddenOpener(val reads: MutableList<String> = mutableListOf()) : ContentOpener {
        override fun open(documentUri: String?, managedPath: String?): InputStream? {
            reads += "open($managedPath)"
            return null
        }

        override fun openRandomAccess(documentUri: String?, managedPath: String?): RandomAccessContent? {
            reads += "openRandomAccess($managedPath)"
            return null
        }

        override fun openNativeSeekablePath(documentUri: String?, managedPath: String?): NativeSeekablePath? {
            reads += "openNativeSeekablePath($managedPath)"
            return null
        }
    }

    private fun game(
        platform: String,
        fileName: String,
        canonicalTitle: String = "Stored Title",
        collapseKey: String? = null,
        sizeBytes: Long = 100L,
    ) = GameEntity(
        entryKey = "root::$fileName",
        gameId = "$platform-game",
        platformId = platform,
        contentKind = "GAME",
        displayName = fileName,
        title = canonicalTitle,
        region = null,
        version = null,
        serial = null,
        titleCatalogKey = null,
        identitySource = "FILENAME",
        locationKind = "MANAGED_PATH",
        availability = "AVAILABLE",
        sizeBytes = sizeBytes,
        contentHash = null,
        sourceRootUri = "root",
        sourceDocumentUri = null,
        managedPath = "/roms/$fileName",
        relativePath = fileName,
        discNumber = 0,
        duplicateGroupId = null,
        isPrimaryMember = true,
        duplicateGroupKind = null,
        canonicalTitle = canonicalTitle,
        collapseKey = collapseKey,
        identityOrigin = "NO_INTRO",
        identityResolvedAtMillis = 1_000L,
    )

    /**
     * Records exactly what the pass touches. Anything not listed is an [error], so a future edit that
     * reaches for another DAO method has to come through this test and say why.
     */
    private class Harness(private val pending: List<GameEntity>) {
        val hashWrites = mutableListOf<Pair<String, String?>>()
        val markers = mutableListOf<String>()
        /** Every argument list the pass handed the selector, in order. */
        val selectorCalls = mutableListOf<List<Any?>>()
        var identityWritten = false
        private var served = false

        val dao: GameDao = Proxy.newProxyInstance(
            GameDao::class.java.classLoader,
            arrayOf(GameDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "unverifiedResolvedPrimaries" -> {
                    // Dropping the trailing Continuation every suspend function carries through
                    // reflection; the declared parameters are what this test is about.
                    selectorCalls += args.orEmpty().filterNot { it is Continuation<*> }
                    if (served) emptyList<GameEntity>() else pending.also { served = true }
                }

                "setContentHash" -> {
                    val callArgs = requireNotNull(args)
                    hashWrites += (callArgs[0] as String) to (callArgs[1] as String?)
                    1
                }

                "stampMaintenance" -> {
                    markers += requireNotNull(args)[0] as String
                    Unit
                }

                // INSERT OR REPLACE, where stampMaintenance is INSERT OR IGNORE. The unreadable cooldown
                // needs the newest timestamp: a volume absent for a week must still be retried a day
                // after the LAST attempt rather than once, on the strength of the first.
                "refreshMaintenance" -> {
                    markers += requireNotNull(args)[0] as String
                    Unit
                }

                // Never legitimate here. Re-verification writes facts about BYTES; the moment it writes
                // an identity it can un-resolve a row, and `awaitIdentified` polls exactly that column.
                "setIdentities", "setIdentity" -> {
                    identityWritten = true
                    Unit
                }

                else -> error("re-verification touched an unexpected GameDao method: ${method.name}")
            }
        } as GameDao

        fun attemptMarkers() = markers.filter { it.startsWith(ReverificationAttemptedPrefix) }

        fun unreadableMarkers() = markers.filter { it.startsWith(ReverificationUnreadablePrefix) }

        fun conflictMarkers() = markers.filter { it.startsWith("reverify-conflict-v1:") }
    }

    private fun enricher(harness: Harness, opener: ContentOpener) = LibraryIdentityEnricher(
        chdIdentity = ChdIdentitySource.RECOMPUTED_PAYLOAD,
        dao = harness.dao,
        datSource = BundledDatSource(PackedDatAssetProvider { null }, manifest = emptyList()),
        idSource = BundledIdSource(DatAssetProvider { null }, manifest = emptyList()),
        opener = opener,
        nowMillis = { 7L },
        sourceIndexMutations = SourceRootMutationCoordinator(),
    )

    // ── the 71.6 GB guard ───────────────────────────────────────────────────────────────────────

    /**
     * **The single most expensive mistake this pass could make.** On the reference device the id-keyed
     * platforms account for 53 unhashed rows and 71.6 GB — a 16 GB Switch NSP among them — and not one of
     * them was ever identified from its name: `resolveByDumpName` returns null for the whole set, because
     * they identify from a title ID in their head bytes. Re-verifying them would read 71.6 GB to confirm
     * content-derived evidence with more content-derived evidence.
     *
     * Deleting [LibraryIdentityEnricher.couldHaveBeenNameResolved] does not break a single other test,
     * does not change any title, and is exactly the kind of "why is this filter here" simplification that
     * looks safe. It would put a full read of every Switch, 3DS, DS, GameCube, Wii and PS3 game onto the
     * sampled card. This test is the only thing that says no.
     */
    @Test
    fun `an id-keyed game is never opened by re-verification`() = runTest {
        val harness = Harness(listOf(game("ds", "Pokemon Diamond.nds", sizeBytes = 128L * 1024 * 1024)))
        val opener = ForbiddenOpener()

        val report = enricher(harness, opener).reverifyNameResolved()

        assertEquals("nothing may be read for a platform the name path never answered for", emptyList<String>(), opener.reads)
        assertEquals(1, report.skipped)
        assertEquals(0, report.agreed)
    }

    /**
     * The other half of the same guard, and the one that is easy to get wrong in the *safe* direction.
     *
     * A skipped game still satisfies the selector — it is still a resolved primary with no hash — so if
     * the pass declines it without marking it, it comes back on the next page, and the next, forever.
     * Eight permanently-skipped games would fill every page of eight and the games that genuinely need
     * reading would never be reached. The pass would run indefinitely and verify nothing.
     */
    @Test
    fun `a skipped game is still marked, or it fills every page forever`() = runTest {
        val harness = Harness(listOf(game("switch", "Zelda TotK.nsp")))

        enricher(harness, ForbiddenOpener()).reverifyNameResolved()

        assertEquals(
            listOf(ReverificationAttemptedPrefix + "root::Zelda TotK.nsp"),
            harness.attemptMarkers(),
        )
    }

    // ── the terminating predicate ───────────────────────────────────────────────────────────────

    /**
     * **Why the selector is keyed on a marker and not on `contentHash IS NULL`.**
     *
     * A NES ROM with an iNES header hashes to the headerless payload, so `RomFingerprinter` returns
     * `exactRawSha1 = null` by design — the bytes it hashed are not the bytes on disk. The game is fully,
     * successfully verified and *still* ends the pass with no `contentHash`. A selector keyed on the hash
     * column would hand this same file back on every single run, forever, re-reading it each time.
     *
     * The same is true of every zip entry and every CHD, which is most of what is expensive.
     */
    @Test
    fun `a game that verifies without producing a raw hash is still marked as attempted`() = runTest {
        val harness = Harness(listOf(game("nes", "Zelda.nes")))
        // iNES: magic + a 16-byte header the fingerprinter strips before hashing.
        val ines = byteArrayOf(0x4E, 0x45, 0x53, 0x1A, 2, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0) +
            "payload bytes".toByteArray()

        enricher(harness, ContentOpener { _, _ -> ByteArrayInputStream(ines) }).reverifyNameResolved()

        assertTrue(
            "the header strip nulls the raw hash, so nothing lands in contentHash",
            harness.hashWrites.none { it.second != null },
        )
        assertEquals(
            "and the marker is therefore the only thing that stops this file being re-read forever",
            listOf(ReverificationAttemptedPrefix + "root::Zelda.nes"),
            harness.attemptMarkers(),
        )
    }

    /**
     * A file that could not be read gets a COOLDOWN, never an attempt marker. The distinction is the
     * whole point of having two prefixes.
     *
     * The attempt marker is a verdict -- "this pass has had its one look at these bytes" -- and it retires
     * the guard for that game permanently. An unmounted card says nothing about bytes, so it must never
     * earn one. That half is unchanged and is asserted below.
     *
     * But leaving the row wholly unmarked, which is what this test used to require, was a starvation bug
     * identified on 2026-09-18. The selector takes the eight SMALLEST unverified rows. Eight permanently
     * unreadable small ones -- an ejected volume, a revoked SAF grant -- therefore came back as the entire
     * page on every pass, the worker saw a page of nothing but unreadable rows, retried, and got the same
     * eight. Every larger game behind them was never read. The skip branch above already carried exactly
     * this guard and says so in as many words; this branch did not.
     */
    @Test
    fun `a game that could not be read gets a cooldown, not a verdict`() = runTest {
        val harness = Harness(listOf(game("gb", "Pokemon Red.gb")))
        val opener = ContentOpener { _, _ -> throw IOException("card not mounted") }

        val report = enricher(harness, opener).reverifyNameResolved()

        assertEquals(1, report.unreadable)
        assertEquals(
            "no attempt marker: the guard for this game must survive one unmounted card",
            emptyList<String>(),
            harness.attemptMarkers(),
        )
        assertEquals(
            "but a cooldown, so eight unreadable rows cannot be the whole page forever",
            listOf(ReverificationUnreadablePrefix + "root::Pokemon Red.gb"),
            harness.unreadableMarkers(),
        )
    }

    /**
     * The cooldown is only half a fix; the selector has to honour it.
     *
     * Writing the marker and then asking for rows without excluding recently-marked ones would produce the
     * identical starvation with one extra write per pass. This asserts the four arguments the pass hands
     * the query, because the exclusion lives in SQL and a unit test cannot see SQL run.
     */
    @Test
    fun `the selector is told to skip rows that were unreadable recently`() = runTest {
        val harness = Harness(listOf(game("gb", "Pokemon Red.gb")))

        enricher(harness, ContentOpener { _, _ -> ByteArrayInputStream("b".toByteArray()) })
            .reverifyNameResolved(maxGames = 8)

        assertEquals(
            listOf(
                ReverificationAttemptedPrefix,
                ReverificationUnreadablePrefix,
                // `nowMillis` is fixed at 7 by the harness, so the floor is a day behind that.
                7L - ReverificationUnreadableCooldownMillis,
                8,
            ),
            harness.selectorCalls.first(),
        )
    }

    /**
     * A page the pass broke out of early is NOT an empty queue.
     *
     * `exhausted` was `games.size < maxGames` alone, which is a statement about the SELECTOR and was being
     * read as a statement about the QUEUE. Break out of a short page on [shouldYield] and the pass reported
     * "nothing further is waiting"; the worker turns that into `Result.success()`, and since the only
     * enqueue is a fresh identity sweep, a stable library never runs the pass again. The rows the break
     * skipped keep no marker and get no second chance.
     */
    @Test
    fun `standing down mid-page does not report the queue as exhausted`() = runTest {
        val opener = ContentOpener { _, _ -> ByteArrayInputStream("b".toByteArray()) }

        val ranToEnd = enricher(Harness(listOf(game("gb", "Pokemon Red.gb"))), opener)
            .reverifyNameResolved(maxGames = 8)
        assertTrue(
            "control: a short page the pass actually finished IS the end of the queue",
            ranToEnd.exhausted,
        )

        val stoodDown = enricher(Harness(listOf(game("gb", "Pokemon Red.gb"))), opener)
            .reverifyNameResolved(maxGames = 8, shouldYield = { true })
        assertFalse(
            "the same short page, abandoned on the first game, says nothing about what remains",
            stoodDown.exhausted,
        )
    }

    // ── what it may never write ─────────────────────────────────────────────────────────────────

    /**
     * **The 22-minute door.** `IdentityEnrichmentWorker.awaitIdentified` polls `identityResolvedAtMillis`,
     * and the artwork stage waits on it. Any re-verification that signalled "look at this again" by
     * un-resolving a row would stall first-run artwork behind a background pass — reintroducing the exact
     * complaint that name-first identification was written to fix, through a door nobody is watching.
     *
     * So the pass never writes an identity at all, on any branch. The fake makes that assertable.
     */
    @Test
    fun `re-verification never writes an identity`() = runTest {
        val harness = Harness(
            listOf(
                game("gb", "Pokemon Red.gb"),
                game("ds", "Pokemon Diamond.nds"),
                game("megadrive", "Sonic.md"),
            ),
        )

        enricher(harness, ContentOpener { _, _ -> ByteArrayInputStream("bytes".toByteArray()) })
            .reverifyNameResolved()

        assertFalse(
            "a conflict is recorded and logged, never applied — the stored title stands",
            harness.identityWritten,
        )
    }

    /**
     * A clean read with no DAT entry for the bytes is not a disagreement — there is nothing to disagree
     * with — so it must not produce a conflict record. On a library with unusual dumps this is the common
     * case, and a version that treated it as a conflict would cry wolf on most of the shelf.
     */
    @Test
    fun `no DAT entry for the bytes is not a conflict`() = runTest {
        val harness = Harness(listOf(game("gb", "Pokemon Red.gb")))

        val report = enricher(harness, ContentOpener { _, _ -> ByteArrayInputStream("bytes".toByteArray()) })
            .reverifyNameResolved()

        assertEquals(1, report.noDatMatch)
        assertEquals(0, report.conflicted)
        assertEquals(emptyList<String>(), harness.conflictMarkers())
        assertEquals(
            "the whole-file hash is still a true fact about the file, so it is still persisted",
            listOf("root::Pokemon Red.gb"),
            harness.hashWrites.filter { it.second != null }.map { it.first },
        )
    }

    // ── standing down ───────────────────────────────────────────────────────────────────────────

    /**
     * The pass must be interruptible BETWEEN files, not merely between pages. A page can be eight discs;
     * the player can start a game during the first one. Asking once per page would let a CHD keep
     * decompressing for minutes against a game that is trying to read the same card.
     */
    @Test
    fun `it stops reading the moment the caller says a session started`() = runTest {
        val harness = Harness(
            listOf(
                game("gb", "First.gb"),
                game("gbc", "Second.gbc"),
                game("megadrive", "Third.md"),
            ),
        )
        var seen = 0
        // False for the first file, true from then on: one game is verified, the rest stand down.
        val report = enricher(harness, ContentOpener { _, _ -> ByteArrayInputStream("bytes".toByteArray()) })
            .reverifyNameResolved(shouldYield = { seen++ > 0 })

        assertEquals("only the first file may be attempted", 1, report.attempted)
        assertEquals(
            "and only the first file is marked, so the other two come back next time",
            listOf(ReverificationAttemptedPrefix + "root::First.gb"),
            harness.attemptMarkers(),
        )
    }

    /** Standing down before the first file reads nothing at all and marks nothing at all. */
    @Test
    fun `yielding immediately leaves the whole page untouched`() = runTest {
        val harness = Harness(listOf(game("gb", "Pokemon Red.gb")))
        val opener = ForbiddenOpener()

        val report = enricher(harness, opener).reverifyNameResolved(shouldYield = { true })

        assertEquals(0, report.attempted)
        assertEquals(emptyList<String>(), opener.reads)
        assertEquals(emptyList<String>(), harness.attemptMarkers())
    }

    // ── the conflict comparison itself ──────────────────────────────────────────────────────────

    /**
     * **The branch the whole feature exists to reach**, tested directly because every other test here runs
     * against an empty DAT manifest and so can only ever produce `NO_DAT_MATCH`.
     */
    @Test
    fun `different bytes under the same name is a conflict`() {
        assertFalse(
            reverificationAgreesWithStored(
                storedCanonicalTitle = "Sonic the Hedgehog",
                storedCollapseKey = "sonic-the-hedgehog",
                storedGroupKind = null,
                observedCanonicalTitle = "Sonic the Hedgehog 2",
                observedCollapseKey = "sonic-the-hedgehog-2",
            ),
        )
    }

    @Test
    fun `the same game under the same name agrees`() {
        assertTrue(
            reverificationAgreesWithStored(
                storedCanonicalTitle = "Sonic the Hedgehog",
                storedCollapseKey = "sonic-the-hedgehog",
                storedGroupKind = null,
                observedCanonicalTitle = "Sonic the Hedgehog",
                observedCollapseKey = "sonic-the-hedgehog",
            ),
        )
    }

    /**
     * **The one that would have burned a CHD decompress per disc, per pass, forever.**
     *
     * A multi-disc group's stored row holds the GROUP title with its disc token stripped and a null
     * collapse key. Hashing Disc 1 returns `Chrono Cross (Disc 1)` with its own per-disc key. Compared
     * raw those disagree, so every multi-disc set in the library would be reported as a conflict on
     * every pass -- each one having decompressed a CHD to reach the wrong answer.
     */
    @Test
    fun `a multi-disc group does not conflict with its own disc 1`() {
        assertTrue(
            "the group's stored shape and a member's fresh shape must be reconciled before comparing",
            reverificationAgreesWithStored(
                storedCanonicalTitle = "Chrono Cross",
                storedCollapseKey = null,
                storedGroupKind = "MULTI_DISC",
                // Already through canonicalTitleForStamp, exactly as the pass passes it.
                observedCanonicalTitle = "Chrono Cross",
                observedCollapseKey = "chrono-cross-disc-1",
            ),
        )
    }

    /** Same title, different region: a real disagreement that the collapse key is the only witness to. */
    @Test
    fun `a matching title with a different collapse key is still a conflict`() {
        assertFalse(
            reverificationAgreesWithStored(
                storedCanonicalTitle = "Castlevania",
                storedCollapseKey = "castlevania-usa",
                storedGroupKind = null,
                observedCanonicalTitle = "Castlevania",
                observedCollapseKey = "castlevania-jpn",
            ),
        )
    }

    /**
     * The batch is small on purpose. This pins it as a decision rather than an accident: re-verification
     * re-incurs the cost first-run identification sheds, so a page is one charge-and-idle slice.
     */
    @Test
    fun `the default page is a slice, not a sweep`() {
        assertTrue("a page of $ReverificationBatch would stop being a slice", ReverificationBatch <= 16)
    }
}
