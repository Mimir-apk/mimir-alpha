package com.gruning.mimir.rewrite.core.metadata

import com.gruning.mimir.rewrite.core.model.DisplayTitle
import com.gruning.mimir.rewrite.core.model.DuplicateGroupKind
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.resolver.LibraryHashReconciler
import com.gruning.mimir.rewrite.core.storage.ProcessSourceRootMutations
import com.gruning.mimir.rewrite.core.storage.SourceRootMutationCoordinator
import com.gruning.mimir.rewrite.core.storage.SourceIndexGeneration
import com.gruning.mimir.rewrite.core.storage.dao.GameDao
import com.gruning.mimir.rewrite.core.storage.dao.GameIdentityDao
import com.gruning.mimir.rewrite.core.storage.dao.IdentityWrite
import com.gruning.mimir.rewrite.core.storage.entity.GameEntity
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Loose `.chd` identity uses name or header rather than whole-file hashing; dump-name lookup stays first. */
enum class ChdIdentitySource {
    /**
     * The combined SHA-1 the header declares, looked up exactly as a recomputed one would be. Nothing is
     * decompressed. The first-run identification pass uses this.
     */
    HEADER_DECLARED,

    /** libchdr decompresses the payload and recomputes the digest before it is looked up. Reads every byte. */
    RECOMPUTED_PAYLOAD,
}

/**
 * Resolves each library game's authoritative identity from the bundled offline data and persists it onto
 * the `games` rows — the productionized, single-source version of the on-device probe. Runs OFF the scan
 * path (hashing a multi-GB disc must never stall a scan): the scan writes filename-derived rows, then this
 * enricher walks the un-resolved primaries in the background, smallest-first, and fills in
 * `canonicalTitle` / `collapseKey` / `identityOrigin`.
 *
 * Per-platform strategy mirrors the identity engine:
 *  - **gamecube / wii** → a payload-bound Game ID from the file's head bytes;
 *  - **3ds** → the product code in partition 0's NCCH header, then the No-Intro hash as fallback;
 *  - **ds** → the cart header's game code at 0x0C, then the No-Intro hash as fallback;
 *  - **arcade ZIP** → an exact complete member SHA-1 multiset against a release-pinned MAME index;
 *  - **switch NSP** → a structurally validated PFS0 ticket Rights ID retained only as a provisional hint;
 *    without ticket-to-NCA/CNMT binding it never mutates durable identity;
 *  - **ps3** → a payload-bound TITLE_ID from local sibling `PARAM.SFO` first, then a bounded ISO9660 volume
 *    descriptor probe, then the Redump whole-image hash fallback. A filename serial is retained only as
 *    provisional/conflict evidence.
 *  - **CHD** → libchdr-recomputed raw payload agreement before the combined catalog SHA-1 is admitted;
 *    a differential CHD first discovers its parent chain digest-only over the available residents
 *    ([ChdParentChainDiscovery]) and every chain node recomputes independently;
 *  - **dreamcast** (multi-track) → hash bounded disc members and require all observed authoritative hits
 *    to agree; huge whole-file tracks are skipped.
 *  - **everything else** → format-aware content hash ([RomFingerprinter]) → [BundledDatSource].
 *
 * Before ANY of the above, a game whose file name matches exactly one dump in the bundled DATs is resolved
 * from the name alone ([resolveByDumpName]) and no byte of it is read. This is the one authoritative class
 * that is not payload-bound, admitted on 2026-09-17 because identifying discs by
 * content is the product's largest first-run stall — 19.5 GB for fifteen games on the reference device. It
 * fails closed on every ambiguity, and a later content hash that disagrees raises an authoritative conflict
 * rather than being overwritten.
 *
 * What that trade costs, stated plainly: a game resolved from its name never has its whole-file SHA-1
 * persisted, so it does not take part in exact-duplicate folding and a same-path byte replacement under it
 * goes unnoticed. Authoritative REGION collapse is unaffected — the DAT's clone parent arrives with the
 * name. Re-verifying name-resolved games in the background is owed work, not present here.
 *
 * Every successful adapter result crosses [AthenaIdentityEngine] before it can become an [IdentityWrite].
 * Apart from [AthenaIdentityEvidence.DAT_NAME_UNIQUE] above, the authoritative adapters carry only
 * validated SHA-1 or payload-bound embedded catalog-key authority; connected metadata and CRC/lexical
 * candidates still cannot be promoted by this persistence path.
 *
 * Idempotent terminal outcomes are stamped. Seek, I/O, malformed-content and budget failures remain
 * unresolved so a later capability/storage replay can retry them. Fully offline; metadata only.
 */
class LibraryIdentityEnricher(
    private val dao: GameDao,
    private val datSource: BundledDatSource,
    private val idSource: BundledIdSource,
    opener: ContentOpener,
    /** Where a loose `.chd`'s identity comes from. No default: every construction site decides. */
    private val chdIdentity: ChdIdentitySource,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val nanoTime: () -> Long = System::nanoTime,
    private val identityDao: GameIdentityDao? = null,
    payloadProvider: DatAssetProvider? = null,
    private val sourceIndexMutations: SourceRootMutationCoordinator =
        ProcessSourceRootMutations.coordinator,
    /**
     * Runs one batch's publication. Production passes a Room transaction, so a batch's hashes and identities land
     * as ONE commit and ONE `games` invalidation. On RG Rotate (2026-09-28), each hash was its own
     * commit, and every commit re-ran every open query on `games`. The default passes straight through.
     */
    private val runPublishTransaction: suspend (suspend () -> Unit) -> Unit = { block -> block() },
    /**
     * Where files are read and hashed. Production passes the sweep's background-priority pool: the probe used to
     * hop to Dispatchers.IO, at normal priority, beside the UI, whatever pool the sweep itself ran on.
     */
    private val hashDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val baseOpener = opener
    private val athenaIdentityEngine = AthenaIdentityEngine()
    private val mamePayloadCatalog = payloadProvider?.let(::MamePayloadCatalog)

    private val idKeyedPlatforms = setOf("arcade", "switch", "gamecube", "wii", "ps3", "3ds", "ds")

    /**
     * Platforms whose keyed lookup is an OPTIMISATION over a hash that still works, so a miss must fall
     * through to it rather than leave the game unresolved.
     *
     * This is deliberately not every entry in [idKeyedPlatforms]. For GameCube and Wii the disc Game ID is
     * the identity: a miss means the catalogue does not carry that title, and hashing the image against
     * `gamecube.midx` would not find it either — so the existing behaviour (stay unresolved, retry when the
     * catalogue changes) is kept exactly as it was. PS3, 3DS and DS are different: each has a full Redump /
     * No-Intro hash index that can answer independently, and for each the keyed read exists only to avoid
     * reading a whole file to learn something the first bytes already say.
     *
     * QA-17: 3DS and DS join PS3 here rather than the shared branch being widened, so nothing about
     * GameCube or Wii moves as a side effect.
     */
    private val idKeyedPlatformsWithHashFallback = setOf("ps3", "3ds", "ds")

    /** Test seam mirroring [ChdPayloadVerifier.verify]'s injectable backend; production keeps the default. */
    internal var chainVerifier: suspend (List<ChdVerificationNode>, ChdVerificationBudget) -> ChdChainVerification = {
            nodes,
            budget,
        ->
        ChdChainPayloadVerifier.verifyChain(nodes, budget = budget)
    }

    /** Typed archive seam for focused integration tests; production remains offline and IO-dispatched. */
    internal var archivePayloadResolver:
        suspend (GameEntity, ChdVerificationBudget, ContentOpener) -> ArchivePayloadSelectionResult =
        { game, budget, opener ->
            withContext(hashDispatcher) {
                ArchivePayloadSelector(
                    platformId = game.platformId,
                    residentSizeBytes = game.sizeBytes,
                    openResident = {
                        opener.open(game.sourceDocumentUri, game.managedPath)
                    },
                    oracle = { pid, fingerprint ->
                        datSource.lookup(
                            MetadataQuery(pid, contentHash = fingerprint.datHash),
                        )?.let { record ->
                            ArchivePayloadOracleMatch(
                                canonicalTitle = record.canonicalTitle,
                                collapseKey = datSource.collapseKeyFor(pid, fingerprint.datHash),
                                origin = record.origin,
                            )
                        }
                    },
                    nanoTime = nanoTime,
                ).select(budget)
            }
        }

    /**
     * One bounded digest-only parent catalog per enrichment sweep: N differential CHDs cost one header
     * pass over at most 4,096 residents, never N inventory rescans.
     */
    private fun chdChainDiscovery(opener: ContentOpener) = ChdParentChainDiscovery(
        inventory = {
            dao.availableChdShapedResidents(
                limit = ChdParentChainDiscovery.MAX_RESIDENTS + 1,
            ).map { resident ->
                ChdChainResident(
                    entryKey = resident.entryKey,
                    sourceRootUri = resident.sourceRootUri,
                    relativePath = resident.relativePath,
                    documentUri = resident.sourceDocumentUri,
                    managedPath = resident.managedPath,
                )
            }
        },
        headerReader = { resident -> readChainResidentHeader(resident, opener) },
        holderOpener = { resident ->
            opener.openNativeSeekablePath(resident.documentUri, resident.managedPath)
        },
        verifier = { nodes, budget -> chainVerifier(nodes, budget) },
        nanoTime = nanoTime,
    )

    /** Result of an enrichment run. */
    data class Report(
        val attempted: Int,
        val matched: Int,
        val exactGroupsFolded: Int,
        val retryable: Int = 0,
        val nextCursor: IdentityEnrichmentCursor? = null,
        /** Witness paired with [nextCursor]; a resumed keyset page must present both or restart. */
        val nextGeneration: SourceIndexGeneration? = null,
        val exhausted: Boolean = false,
        /** A source/index mutation invalidated this pass; a durable caller must discard its keyset cursor. */
        val restartFromBeginning: Boolean = false,
    )

    data class IdentityEnrichmentCursor(
        val sizeBytes: Long,
        val entryKey: String,
    ) {
        init {
            require(sizeBytes >= 0L)
            require(entryKey.isNotBlank())
        }
    }

    private data class ExactHashWrite(
        val entryKey: String,
        val contentHash: String,
    )

    private data class ResolvedGame(
        val game: GameEntity,
        val attempt: ResolveAttempt,
        val exactHashes: List<ExactHashWrite>,
    )

    /**
     * Resolve up to [maxGames] unresolved primaries (in [batch]-sized reads). Safe to call repeatedly.
     *
     * For the observed 17-minute dirty-card identification case, each batch resolves with [concurrency]
     * in-flight hashes (the sources are thread-safe memoized ConcurrentHashMaps and each game only
     * reads its own file), then the whole chunk lands as ONE [GameDao.setIdentities] transaction.
     * Per-row writes were the real floor — every write invalidated the `games` table and every
     * observed Flow re-queried per game, pinning Room's executor while the hashers idled. A modest
     * permit count keeps an SD card's sequential-read behavior intact rather than thrashing it.
     *
     * @param onGameAttempted Fires once per game, the instant ITS OWN resolution attempt finishes
     *   (hash read, DAT/ID lookup, and the Identified/TerminalAbstain/Retryable decision) — strictly
     *   BEFORE that game's batch reaches [GameDao.setIdentities]. Receives the cumulative count of
     *   completed attempts within THIS `enrich` call (1..`maxGames`, monotonically increasing). This is
     *   real, in-memory work-done, not a proxy for a Room transaction commit: a caller wiring this
     *   straight through gets honest per-file progress even while a whole-file hash of one large disc
     *   image inside the batch is still running, instead of a bar frozen until every game in the batch
     *   lands. Never throws and does no I/O of its own — it runs on the hashing hot path under
     *   [concurrency] concurrent permits, so it must stay cheap and non-blocking.
     */
    suspend fun enrich(
        maxGames: Int = 5000,
        batch: Int = 50,
        concurrency: Int = 3,
        reconcile: Boolean = true,
        afterCursor: IdentityEnrichmentCursor? = null,
        afterGeneration: SourceIndexGeneration? = null,
        absoluteDeadlineNanos: Long? = null,
        onCheckpoint: (IdentityEnrichmentCursor) -> Unit = {},
        onGameAttempted: (attemptedSoFar: Int) -> Unit = {},
    ): Report = withContentCancellation { scope ->
        val opener = baseOpener.boundTo(scope)
        val probe = ResidentHashProbe(opener, hashDispatcher)
        val chdDiscovery = chdChainDiscovery(opener)
        var attempted = 0
        var matched = 0
        var retryable = 0
        var cursor = afterCursor
        var expectedGeneration = afterGeneration
        var exhausted = false
        var restartFromBeginning = false
        val gate = Semaphore(concurrency.coerceAtLeast(1))
        // Per-call, cross-batch counter behind onGameAttempted -- see that param's KDoc. Atomic because
        // up to `concurrency` async tasks complete concurrently and each must see a distinct, increasing
        // count with no lost updates.
        val gamesAttempted = AtomicInteger(0)
        while (attempted < maxGames) {
            if (absoluteDeadlineNanos != null && nanoTime() >= absoluteDeadlineNanos) {
                break
            }
            val snapshot = sourceIndexMutations.snapshotIndex {
                dao.unresolvedPrimariesAfter(
                    afterSizeBytes = cursor?.sizeBytes ?: -1L,
                    afterEntryKey = cursor?.entryKey.orEmpty(),
                    limit = minOf(batch, maxGames - attempted),
                )
            }
            if (cursor != null && expectedGeneration != null && snapshot.generation != expectedGeneration) {
                cursor = null
                restartFromBeginning = true
                break
            }
            val games = snapshot.value
            if (games.isEmpty()) {
                exhausted = true
                break
            }
            val batchCursor = IdentityEnrichmentCursor(games.last().sizeBytes, games.last().entryKey)
            onCheckpoint(batchCursor)
            val resolved = coroutineScope {
                games.map { g ->
                    async {
                        gate.withPermit {
                            val exactHashes = mutableListOf<ExactHashWrite>()
                            val perGameBudget = ChdVerificationBudget.forResolve(
                                nanoTime = nanoTime,
                                absoluteDeadlineNanos = absoluteDeadlineNanos,
                            )
                            val result = try {
                                resolveWith(opener, probe, chdDiscovery, g, perGameBudget, exactHashes)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: RuntimeException) {
                                ResolveAttempt.Retryable
                            }
                            // Real work just finished (hash + decision), strictly before this game's
                            // batch is written. See onGameAttempted's KDoc.
                            onGameAttempted(gamesAttempted.incrementAndGet())
                            ResolvedGame(g, result, exactHashes)
                        }
                    }
                }.awaitAll()
            }
            val ts = nowMillis()
            val writes = resolved.mapNotNull { resolvedGame ->
                val g = resolvedGame.game
                when (val result = resolvedGame.attempt) {
                    is ResolveAttempt.Identified ->
                        IdentityWrite(
                            gameId = g.gameId,
                            title = canonicalTitleForStamp(
                                result.candidate.canonicalTitle,
                                g.duplicateGroupKind,
                            ),
                            collapse = result.candidate.collapseKey,
                            origin = result.candidate.origin.name,
                            ts = ts,
                        )

                    ResolveAttempt.TerminalAbstain ->
                    IdentityWrite(
                        gameId = g.gameId,
                        title = null,
                        collapse = null,
                        origin = null,
                        ts = ts,
                    )

                    ResolveAttempt.Retryable -> null
                }
            }
            val exactHashes = resolved
                .flatMap(ResolvedGame::exactHashes)
                .associateBy(ExactHashWrite::entryKey)
                .values
                .sortedBy(ExactHashWrite::entryKey)
            // Even a fully retryable batch must revalidate its resident witness before its keyset
            // cursor can advance. Otherwise a same-key replacement that lands while an opener is
            // blocked can move behind a durable cursor without this batch publishing any writes.
            val publicationGeneration =
                sourceIndexMutations.commitIndexIfUnchangedAndGet(snapshot.generation) {
                runPublishTransaction {
                    exactHashes.forEach { write ->
                        dao.setContentHash(write.entryKey, write.contentHash)
                    }
                    if (writes.isNotEmpty()) dao.setIdentities(writes)
                }
            }
            val published = publicationGeneration != null
            attempted += resolved.size
            if (published) {
                matched += resolved.count { it.attempt is ResolveAttempt.Identified }
            }
            val batchRetryable = if (published) {
                resolved.count { it.attempt == ResolveAttempt.Retryable }
            } else {
                // Every result was derived from an obsolete resident snapshot. Retry all of them and
                // never count an identified candidate that was correctly refused publication.
                resolved.size
            }
            retryable += batchRetryable
            if (published) {
                cursor = batchCursor
                expectedGeneration = publicationGeneration
            } else {
                // The checkpoint was emitted before file I/O so process death cannot lose the page.
                // This refusal is different: the page is still unresolved and now sits behind that
                // checkpoint. Force durable callers to clear it and begin from the current first row.
                cursor = null
                restartFromBeginning = true
                break
            }
        }
        // SD01b: a LOOPING caller (the worker drains in maxGames-sized slices) passes reconcile=false
        // and reconciles ONCE after the drain — the exact-hash pass scans the whole library's hash
        // candidates and folds with per-row writes, so running it per slice multiplied full-table
        // work and invalidation churn at 5.5k scale. Idempotent: one final pass = the same end state.
        val exactGroupsFolded = if (reconcile) {
            sourceIndexMutations.commitIndexMutation {
                LibraryHashReconciler(dao, identityDao, nowMillis).reconcile()
            }
        } else {
            0
        }
        if (cursor != null && reconcile) {
            // Reconciliation is itself an ordered index mutation. Return its post-mutation witness so a
            // partial direct caller can safely resume instead of treating our own fold as external drift.
            expectedGeneration = sourceIndexMutations.currentIndexGeneration()
        }
        Report(
            attempted = attempted,
            matched = matched,
            exactGroupsFolded = exactGroupsFolded,
            retryable = retryable,
            nextCursor = cursor,
            nextGeneration = expectedGeneration.takeIf { cursor != null },
            exhausted = exhausted,
            restartFromBeginning = restartFromBeginning,
        )
    }

    /** The final exact-hash fold for callers that drained with `reconcile = false`. */
    suspend fun reconcileNow(): Int = sourceIndexMutations.commitIndexMutation {
        LibraryHashReconciler(dao, identityDao, nowMillis).reconcile()
    }

    private suspend fun resolveWith(
        opener: ContentOpener,
        probe: ResidentHashProbe,
        chdDiscovery: ChdParentChainDiscovery,
        game: GameEntity,
        budget: ChdVerificationBudget,
        exactHashes: MutableList<ExactHashWrite>,
    ): ResolveAttempt {
        val fileName = residentFileName(game)
        val route = ContainerRoutingPolicy.route(game.platformId, fileName)

        // Before any byte of the file is read. A unique dump-name match answers here; anything else --
        // a miss, an ambiguity, a platform that reads its identity from head bytes anyway -- returns null
        // and the routing below runs exactly as it always has.
        resolveByDumpName(game, fileName, route)?.let { return it }

        when (route) {
            ContainerRoutingPolicy.Route.OPTICAL_ZIP_UNSUPPORTED ->
                return ResolveAttempt.TerminalAbstain
            ContainerRoutingPolicy.Route.CHD -> return resolveChd(opener, chdDiscovery, game, budget)
            ContainerRoutingPolicy.Route.MAME_ZIP ->
                if (mamePayloadCatalog != null) return resolveMameZip(opener, game)
            ContainerRoutingPolicy.Route.MAME_LOOSE ->
                if (mamePayloadCatalog != null) return resolveMameLooseMember(opener, game)
            ContainerRoutingPolicy.Route.SWITCH_NSP_PROVISIONAL ->
                return resolveSwitchPackage(opener, game, fileName)
            ContainerRoutingPolicy.Route.DEFAULT -> Unit
        }

        val candidates = if (game.platformId in idKeyedPlatforms) {
            val keyed = resolveByKey(opener, game)
            if (
                game.platformId in idKeyedPlatformsWithHashFallback &&
                keyed.none { it.evidence.authority == AthenaIdentityAuthority.AUTHORITATIVE }
            ) {
                when (val hash = resolveByHash(opener, probe, game, budget, exactHashes)) {
                    is HashAttempt.Candidate -> keyed + hash.candidate
                    HashAttempt.NoMatch -> keyed
                    HashAttempt.Retryable -> return ResolveAttempt.Retryable
                }
            } else {
                keyed
            }
        } else {
            // Hash the primary; if it doesn't match (e.g. it is an .m3u/.cue manifest), the disc adapter
            // returns its own typed result so CHD seek failures cannot become a durable no-match.
            when (val primary = resolveByHash(opener, probe, game, budget, exactHashes)) {
                is HashAttempt.Candidate -> listOf(primary.candidate)
                HashAttempt.NoMatch -> return resolveDisc(opener, probe, chdDiscovery, game, budget, exactHashes)
                HashAttempt.Retryable -> return ResolveAttempt.Retryable
            }
        }
        return when (val decision = athenaIdentityEngine.decide(candidates)) {
            is AthenaIdentityDecision.Identified -> ResolveAttempt.Identified(decision.candidate)
            is AthenaIdentityDecision.NeedsReview,
            -> ResolveAttempt.Retryable
            is AthenaIdentityDecision.Abstained -> ResolveAttempt.TerminalAbstain
        }
    }

    /**
     * Identity from the file's own name, against the same shards the hash path would have consulted.
     *
     * Reducing identification cost matters: identifying the fifteen sampled discs by
     * content means reading 19.5 GB, and CHD costs more than its file size because the payload is
     * decompressed to confirm the header's own claim. Measured on that library (2026-09-17), the name path
     * answers eleven of the twelve expensive files without opening one of them.
     *
     * Deliberately placed BEFORE the route switch rather than inside the non-keyed branch: `Route.CHD`
     * returns straight to [resolveChd], so a branch-local check would miss every PS1, PSP and Dreamcast
     * `.chd` — which is most of what is slow.
     *
     * Scope, and why each exclusion:
     *  - `OPTICAL_ZIP_UNSUPPORTED` / `MAME_*` / `SWITCH_NSP_PROVISIONAL` are skipped. MAME names are
     *    shortnames (`robotbwl.zip`), not dump names, and the Switch route is provisional by design.
     *  - [idKeyedPlatforms] are skipped: they already identify from head bytes, so there is no hash to
     *    avoid and a name could only be weaker evidence than what they have.
     *
     * A non-[AthenaIdentityDecision.Identified] outcome returns null rather than a failure, so a name that
     * cannot be trusted costs nothing beyond the lookup and the file is hashed as before.
     */
    private fun resolveByDumpName(
        game: GameEntity,
        fileName: String?,
        route: ContainerRoutingPolicy.Route,
    ): ResolveAttempt? {
        if (route != ContainerRoutingPolicy.Route.DEFAULT && route != ContainerRoutingPolicy.Route.CHD) {
            return null
        }
        if (game.platformId in idKeyedPlatforms) return null
        val baseName = fileName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: return null
        val pid = PlatformId(game.platformId)

        // The sampled PS1 discs are grouped behind an `.m3u` playlist, and the PLAYLIST is the primary this
        // walk resolves. Its name is `Chrono Cross (USA)` -- the set -- while Redump carries only
        // `(Disc 1)` and `(Disc 2)`, so the strict rule failed closed, the playlist fell through to the
        // hash, and resolveDisc then read every member. Measured on device 2026-09-17: 2.2 GB of CHD
        // decompressed, and because the enricher walks smallest-first those tiny playlists were FIRST --
        // 55 seconds before the progress counter moved at all.
        //
        // A missing disc number on a multi-disc group's primary is not a missing fact; it is what a
        // playlist name looks like. Disc 1 always exists in such a set, so it stands for the group, and
        // the result is normalised through the same [forExistingGroup] the hash path uses -- same group
        // title, same deliberately-null collapse key -- rather than a second convention.
        val multiDisc = game.duplicateGroupKind == DuplicateGroupKind.MULTI_DISC.name
        val match = datSource.resolveByName(
            pid,
            baseName,
            discFallback = if (multiDisc) 1 else null,
        ) ?: return null
        val key = DatNameKey.parse(baseName)

        val candidate = AthenaIdentityCandidate(
            canonicalTitle = match.canonicalTitle,
            collapseKey = match.collapseKey,
            origin = match.origin,
            evidence = AthenaIdentityEvidence.DAT_NAME_UNIQUE,
            artifact = AthenaArtifactEvidence(
                parser = AthenaParserKind.DAT_DUMP_NAME,
                parserVersion = DatNameKey.VERSION,
                catalogStamp = match.catalogStamp,
                validation = AthenaValidationLevel.UNIQUE_DUMP_NAME_IN_SHARD,
                physicalRole = AthenaPhysicalRole.COMPLETE_DISC,
                // Records the disc this actually matched on, so a group resolved through disc 1 is
                // distinguishable in the artifact from a single file that named no disc at all.
                rawKey = "${key.stem}|${key.disc ?: if (multiDisc) 1 else -1}",
                complete = true,
                unique = true,
            ),
        ).forExistingGroup(game.duplicateGroupKind)
        return when (val decision = athenaIdentityEngine.decide(listOf(candidate))) {
            is AthenaIdentityDecision.Identified -> ResolveAttempt.Identified(decision.candidate)
            is AthenaIdentityDecision.NeedsReview,
            is AthenaIdentityDecision.Abstained,
            -> null
        }
    }

    private suspend fun resolveByHash(
        opener: ContentOpener,
        probe: ResidentHashProbe,
        game: GameEntity,
        budget: ChdVerificationBudget,
        exactHashes: MutableList<ExactHashWrite>,
    ): HashAttempt {
        val fileName = residentFileName(game)
        if (
            fileName?.endsWith(".zip", ignoreCase = true) == true &&
            ContainerRoutingPolicy.route(game.platformId, fileName) == ContainerRoutingPolicy.Route.DEFAULT
        ) {
            return when (val result = archivePayloadResolver(game, budget, opener)) {
                is ArchivePayloadSelectionResult.Identified -> HashAttempt.Candidate(
                    AthenaIdentityCandidate(
                        canonicalTitle = result.identity.canonicalTitle,
                        collapseKey = result.identity.collapseKey,
                        origin = result.identity.origin,
                        evidence = if (
                            result.identity.fingerprint.proof == RomFingerprintProof.CHD_HEADER_CLAIM
                        ) {
                            AthenaIdentityEvidence.CHD_HEADER_SHA1_CANDIDATE
                        } else {
                            AthenaIdentityEvidence.CONTENT_SHA1
                        },
                    ),
                )
                is ArchivePayloadSelectionResult.TerminalAbstain -> HashAttempt.NoMatch
                is ArchivePayloadSelectionResult.Retryable -> HashAttempt.Retryable
            }
        }
        val evidence = when (
            val result = probe.fingerprintEvidenceResultFor(
                platformId = game.platformId,
                documentUri = game.sourceDocumentUri,
                managedPath = game.managedPath,
                fileNameOverride = fileName,
            )
        ) {
            is ResidentFingerprintResult.Fingerprinted -> result.evidence
            ResidentFingerprintResult.Unrecognized -> return HashAttempt.NoMatch
            ResidentFingerprintResult.Retryable -> return HashAttempt.Retryable
        }
        stageExactHash(exactHashes, game.entryKey, evidence)
        val pid = PlatformId(game.platformId)
        val rec = datSource.lookup(MetadataQuery(pid, contentHash = evidence.datHash))
            ?: return HashAttempt.NoMatch
        return HashAttempt.Candidate(
            AthenaIdentityCandidate(
                canonicalTitle = rec.canonicalTitle,
                collapseKey = datSource.collapseKeyFor(pid, evidence.datHash),
                origin = rec.origin,
                evidence = if (evidence.proof == RomFingerprintProof.CHD_HEADER_CLAIM) {
                    AthenaIdentityEvidence.CHD_HEADER_SHA1_CANDIDATE
                } else {
                    AthenaIdentityEvidence.CONTENT_SHA1
                },
            ),
        )
    }

    private suspend fun resolveMameZip(opener: ContentOpener, game: GameEntity): ResolveAttempt {
        val catalog = mamePayloadCatalog ?: return ResolveAttempt.TerminalAbstain
        val stream = opener.open(game.sourceDocumentUri, game.managedPath)
            ?: return ResolveAttempt.Retryable
        return when (val result = MameZipIdentityReader.identify(stream, catalog)) {
            is ContainerIdentityResult.Identified -> {
                val identity = result.value
                val candidate = AthenaIdentityCandidate(
                    canonicalTitle = identity.record.canonicalTitle,
                    collapseKey = "mame:${identity.record.cloneRoot}",
                    origin = MetadataOrigin.MAME,
                    evidence = AthenaIdentityEvidence.MAME_MACHINE_PAYLOAD_SHA1,
                    artifact = AthenaArtifactEvidence(
                        parser = AthenaParserKind.MAME_ZIP_SET,
                        parserVersion = 1,
                        catalogStamp = MamePayloadManifest.POLICY_STAMP,
                        validation = AthenaValidationLevel.DIRECT_MACHINE_PAYLOAD_MULTISET,
                        physicalRole = AthenaPhysicalRole.MAME_MACHINE_PAYLOAD,
                        rawKey = identity.setSignature,
                        // The entire direct ZIP payload was observed. External romof/BIOS/device
                        // dependencies were not, so this is deliberately not a complete launch artifact.
                        complete = false,
                        unique = true,
                    ),
                )
                when (val decision = athenaIdentityEngine.decide(listOf(candidate))) {
                    is AthenaIdentityDecision.Identified -> ResolveAttempt.Identified(decision.candidate)
                    is AthenaIdentityDecision.Abstained -> ResolveAttempt.TerminalAbstain
                    is AthenaIdentityDecision.NeedsReview -> ResolveAttempt.Retryable
                }
            }

            is ContainerIdentityResult.PolicyAbstain -> ResolveAttempt.TerminalAbstain
            is ContainerIdentityResult.NeedsReview,
            is ContainerIdentityResult.RetryableFailure,
            -> ResolveAttempt.Retryable
        }
    }

    private suspend fun resolveMameLooseMember(opener: ContentOpener, game: GameEntity): ResolveAttempt {
        if (game.sizeBytes > MameZipIdentityReader.MAX_ENTRY_BYTES) {
            // The persisted resident size cannot become smaller on a later capability replay. This is an
            // explicitly unsupported loose-member shape, not a transient read/resource failure.
            return ResolveAttempt.TerminalAbstain
        }
        val catalog = mamePayloadCatalog ?: return ResolveAttempt.TerminalAbstain
        val stream = opener.open(game.sourceDocumentUri, game.managedPath)
            ?: return ResolveAttempt.Retryable
        return when (MameLooseMemberReader.classify(stream, catalog)) {
            is ContainerIdentityResult.PolicyAbstain -> ResolveAttempt.TerminalAbstain
            is ContainerIdentityResult.Identified -> ResolveAttempt.TerminalAbstain
            is ContainerIdentityResult.NeedsReview,
            is ContainerIdentityResult.RetryableFailure,
            -> ResolveAttempt.Retryable
        }
    }

    private suspend fun resolveSwitchPackage(
        opener: ContentOpener,
        game: GameEntity,
        fileName: String?,
    ): ResolveAttempt {
        val content = opener.openRandomAccess(game.sourceDocumentUri, game.managedPath)
            ?: return ResolveAttempt.Retryable
        val payload = content.use { SwitchPfs0IdentityReader.identify(it) }
        val embedded = when (payload) {
            is ContainerIdentityResult.Identified -> payload.value
            is ContainerIdentityResult.PolicyAbstain -> return ResolveAttempt.TerminalAbstain
            is ContainerIdentityResult.NeedsReview,
            is ContainerIdentityResult.RetryableFailure,
            -> return ResolveAttempt.Retryable
        }
        val resolution = idSource.resolveEmbedded(
            platformId = "switch",
            extractedId = ExtractedId(IdKeyType.TITLE_ID, embedded.baseTitleId),
        )
        val match = (resolution as? BundledIdResolution.Identified)?.match
            ?: return ResolveAttempt.TerminalAbstain
        val embeddedCandidate = AthenaIdentityCandidate(
            canonicalTitle = match.record.canonicalTitle,
            collapseKey = null,
            origin = match.record.origin,
            evidence = AthenaIdentityEvidence.SWITCH_TICKET_CATALOG_KEY,
            artifact = AthenaArtifactEvidence(
                parser = AthenaParserKind.SWITCH_PFS0_TICKET,
                parserVersion = SwitchPfs0IdentityReader.PARSER_VERSION,
                catalogStamp = SWITCH_CATALOG_STAMP,
                validation = AthenaValidationLevel.STRUCTURED_EMBEDDED_FIELD,
                physicalRole = when (embedded.role) {
                    SwitchPackageRole.BASE -> AthenaPhysicalRole.BASE_PACKAGE
                    SwitchPackageRole.UPDATE -> AthenaPhysicalRole.UPDATE_PACKAGE
                    SwitchPackageRole.DLC -> return ResolveAttempt.TerminalAbstain
                },
                rawKey = embedded.rawTitleId,
                complete = true,
                unique = true,
            ),
        )
        val filenameCandidates = idSource.resolve("switch", fileName, null, 0).toAthenaCandidates()
        return when (
            val decision = athenaIdentityEngine.decide(listOf(embeddedCandidate) + filenameCandidates)
        ) {
            is AthenaIdentityDecision.Identified -> ResolveAttempt.Identified(decision.candidate)
            is AthenaIdentityDecision.Abstained -> ResolveAttempt.TerminalAbstain
            is AthenaIdentityDecision.NeedsReview -> ResolveAttempt.Retryable
        }
    }

    private suspend fun resolveChd(
        opener: ContentOpener,
        chdDiscovery: ChdParentChainDiscovery,
        game: GameEntity,
        budget: ChdVerificationBudget,
    ): ResolveAttempt {
        val candidate = when (val attempt = resolveChdCandidate(opener, chdDiscovery, game, budget)) {
            is ResolveAttempt.Identified -> attempt.candidate
            ResolveAttempt.TerminalAbstain -> return ResolveAttempt.TerminalAbstain
            ResolveAttempt.Retryable -> return ResolveAttempt.Retryable
        }
        return when (val decision = athenaIdentityEngine.decide(listOf(candidate))) {
            is AthenaIdentityDecision.Identified -> ResolveAttempt.Identified(decision.candidate)
            is AthenaIdentityDecision.Abstained -> ResolveAttempt.TerminalAbstain
            is AthenaIdentityDecision.NeedsReview -> ResolveAttempt.Retryable
        }
    }

    private suspend fun resolveChdCandidate(
        opener: ContentOpener,
        chdDiscovery: ChdParentChainDiscovery,
        game: GameEntity,
        budget: ChdVerificationBudget,
    ): ResolveAttempt {
        val random = opener.openRandomAccess(game.sourceDocumentUri, game.managedPath)
            ?: return ResolveAttempt.Retryable
        val header = random.use {
            val bytes = it.readExact(0L, CHD_HEADER_BYTES) ?: return ResolveAttempt.Retryable
            RomFingerprinter.chdHeaderEvidence(bytes, bytes.size)
                ?: return ResolveAttempt.Retryable
        }
        if (chdIdentity == ChdIdentitySource.HEADER_DECLARED) {
            // nothing is decompressed. The header's combined SHA-1 already names the
            // complete image, a differential child's included, so no parent chain is discovered either:
            // identity is not playability. See AthenaIdentityEvidence.CHD_HEADER_DECLARED_SHA1.
            return chdCatalogueCandidate(
                game = game,
                combinedSha1 = header.combinedSha1,
                logicalBytes = header.logicalBytes,
                evidence = AthenaIdentityEvidence.CHD_HEADER_DECLARED_SHA1,
            ) { catalogStamp, role ->
                AthenaArtifactEvidence(
                    parser = AthenaParserKind.CHD_V5_HEADER,
                    parserVersion = RomFingerprinter.CHD_HEADER_PARSER_VERSION,
                    catalogStamp = catalogStamp,
                    validation = AthenaValidationLevel.HEADER_DECLARED_DIGEST,
                    physicalRole = role,
                    rawKey = header.combinedSha1,
                    complete = role == AthenaPhysicalRole.COMPLETE_DISC,
                    unique = true,
                )
            }
        }
        val verifiedResult = if (header.parentSha1 != null) {
            // A1D: a differential CHD is no longer a terminal abstention. Its parent chain is
            // discovered digest-only across the available residents and every node recomputed.
            chdDiscovery.resolve(
                target = ChdChainResident(
                    entryKey = game.entryKey,
                    sourceRootUri = game.sourceRootUri,
                    relativePath = game.relativePath,
                    documentUri = game.sourceDocumentUri,
                    managedPath = game.managedPath,
                ),
                targetHeader = header,
                budget = budget,
            )
        } else {
            val nativeResident = opener.openNativeSeekablePath(
                game.sourceDocumentUri,
                game.managedPath,
            )
                ?: return ResolveAttempt.Retryable
            nativeResident.use {
                ChdPayloadVerifier.verify(it.path, header, budget = budget)
            }
        }
        val verified = when (val result = verifiedResult) {
            is ContainerIdentityResult.Identified -> result.value
            is ContainerIdentityResult.PolicyAbstain -> return ResolveAttempt.TerminalAbstain
            is ContainerIdentityResult.NeedsReview,
            is ContainerIdentityResult.RetryableFailure,
            -> return ResolveAttempt.Retryable
        }
        return chdCatalogueCandidate(
            game = game,
            combinedSha1 = verified.combinedSha1,
            logicalBytes = verified.logicalBytes,
            evidence = AthenaIdentityEvidence.CHD_RECOMPUTED_OVERALL_SHA1,
        ) { catalogStamp, role -> verified.toArtifact(catalogStamp = catalogStamp, role = role) }
    }

    /**
     * The one catalogue lookup behind both CHD identity sources, so the header path and the recompute path
     * look up the same key in the same catalogue and can only ever agree about which disc a digest names.
     */
    private suspend fun chdCatalogueCandidate(
        game: GameEntity,
        combinedSha1: String,
        logicalBytes: Long,
        evidence: AthenaIdentityEvidence,
        artifact: (catalogStamp: String, role: AthenaPhysicalRole) -> AthenaArtifactEvidence,
    ): ResolveAttempt {
        val candidate = if (game.platformId == "arcade") {
            val record = when (val result = mamePayloadCatalog?.classifyDisk(combinedSha1)) {
                is ContainerIdentityResult.Identified -> result.value
                is ContainerIdentityResult.PolicyAbstain -> return ResolveAttempt.TerminalAbstain
                is ContainerIdentityResult.NeedsReview,
                is ContainerIdentityResult.RetryableFailure,
                null,
                -> return ResolveAttempt.Retryable
            }
            AthenaIdentityCandidate(
                canonicalTitle = record.canonicalTitle,
                collapseKey = "mame:${record.cloneRoot}",
                origin = MetadataOrigin.MAME,
                evidence = evidence,
                artifact = artifact(MamePayloadManifest.POLICY_STAMP, AthenaPhysicalRole.MAME_DISK_MEMBER),
            )
        } else {
            val pid = PlatformId(game.platformId)
            val hash = ContentHash(sha1 = combinedSha1, sizeBytes = logicalBytes)
            val record = datSource.lookup(MetadataQuery(pid, contentHash = hash))
                ?: return ResolveAttempt.TerminalAbstain
            AthenaIdentityCandidate(
                canonicalTitle = record.canonicalTitle,
                collapseKey = datSource.collapseKeyFor(pid, hash),
                origin = record.origin,
                evidence = evidence,
                artifact = artifact(BundledDatManifest.catalogueStamp, AthenaPhysicalRole.COMPLETE_DISC),
            )
        }
        return ResolveAttempt.Identified(candidate)
    }

    /**
     * Reads one candidate resident's header claim for the sweep catalog. A short or unparseable file is
     * simply not a CHD (never a candidate, never a blocker); only an unopenable resident stays unknown.
     */
    private suspend fun readChainResidentHeader(
        resident: ChdChainResident,
        opener: ContentOpener,
    ): ChdResidentHeaderRead {
        val random = opener.openRandomAccess(resident.documentUri, resident.managedPath)
            ?: return ChdResidentHeaderRead.Unreadable
        return random.use { content ->
            if (content.length < CHD_HEADER_BYTES) return@use ChdResidentHeaderRead.NotChd
            val bytes = content.readExact(0L, CHD_HEADER_BYTES)
                ?: return@use ChdResidentHeaderRead.Unreadable
            RomFingerprinter.chdHeaderEvidence(bytes, bytes.size)
                ?.let(ChdResidentHeaderRead::Parsed)
                ?: ChdResidentHeaderRead.NotChd
        }
    }

    private fun VerifiedChdIdentity.toArtifact(
        catalogStamp: String,
        role: AthenaPhysicalRole,
    ): AthenaArtifactEvidence = AthenaArtifactEvidence(
        parser = if (chainDepth > 1) {
            AthenaParserKind.LIBCHDR_PARENT_CHAIN
        } else {
            AthenaParserKind.LIBCHDR_RAW_AND_METADATA
        },
        parserVersion = if (chainDepth > 1) {
            ChdChainPayloadVerifier.PARSER_VERSION
        } else {
            ChdPayloadVerifier.PARSER_VERSION
        },
        catalogStamp = catalogStamp,
        validation = AthenaValidationLevel.RAW_AND_METADATA_RECOMPUTED,
        physicalRole = role,
        rawKey = combinedSha1,
        complete = role == AthenaPhysicalRole.COMPLETE_DISC,
        unique = true,
    )

    private fun BundledIdMatch.toAthenaCandidate(): AthenaIdentityCandidate = AthenaIdentityCandidate(
        canonicalTitle = record.canonicalTitle,
        collapseKey = null, // id-catalogs carry no clone-parent
        origin = record.origin,
        evidence = when (binding) {
            BundledIdBinding.EMBEDDED_HEADER -> AthenaIdentityEvidence.EMBEDDED_CATALOG_KEY
            BundledIdBinding.FILENAME -> AthenaIdentityEvidence.FILENAME_CATALOG_KEY_CANDIDATE
        },
    )

    private fun BundledIdResolution.toAthenaCandidates(): List<AthenaIdentityCandidate> = when (this) {
        is BundledIdResolution.Identified -> listOf(match.toAthenaCandidate())
        is BundledIdResolution.Conflict -> listOf(
            embedded.toAthenaCandidate(),
            filename.toAthenaCandidate(),
        )
        BundledIdResolution.NoMatch -> emptyList()
    }

    private fun BundledIdResolution.hasEmbeddedEvidence(): Boolean = when (this) {
        is BundledIdResolution.Identified -> match.binding == BundledIdBinding.EMBEDDED_HEADER
        is BundledIdResolution.Conflict -> true
        BundledIdResolution.NoMatch -> false
    }

    private suspend fun resolveByKey(opener: ContentOpener, game: GameEntity): List<AthenaIdentityCandidate> {
        val fileName = residentFileName(game)
        if (game.platformId == "ps3") {
            var filenameOnly: BundledIdResolution? = null
            for (candidate in PS3_SFO_CANDIDATES) {
                val sfo = readRelated(opener, game.sourceDocumentUri, game.managedPath, candidate, MAX_SFO_BYTES)
                    ?: continue
                val resolution = idSource.resolve(game.platformId, fileName, sfo.first, sfo.second)
                if (resolution.hasEmbeddedEvidence()) return resolution.toAthenaCandidates()
                filenameOnly = resolution
            }
            val isoHead = readHead(
                opener,
                game.sourceDocumentUri,
                game.managedPath,
                PS3_ISO_VOLUME_ID_END,
            )
            if (isoHead != null) {
                val resolution = idSource.resolve(game.platformId, fileName, isoHead.first, isoHead.second)
                if (resolution.hasEmbeddedEvidence()) return resolution.toAthenaCandidates()
                filenameOnly = resolution
            }
            return (filenameOnly ?: idSource.resolve(game.platformId, fileName, null, 0))
                .toAthenaCandidates()
        }

        val primaryHead = when (game.platformId) {
            "gamecube", "wii" -> readHead(opener, game.sourceDocumentUri, game.managedPath, HEAD_BYTES)
            // QA-17: a 3DS product code sits inside partition 0's NCCH header, which the NCSD partition
            // table places at 0x4000 on a normal dump -- past HEAD_BYTES, so this platform reads further.
            // Still four orders of magnitude below the 4 GB whole-file hash it replaces, and the same
            // shape as the PS3 volume-descriptor probe above, which already reads 32 KB.
            "3ds" -> readHead(opener, game.sourceDocumentUri, game.managedPath, THREE_DS_HEAD_BYTES)
            // A DS cart's game code is at 0x0C, so the ordinary head budget already reaches it.
            "ds" -> readHead(opener, game.sourceDocumentUri, game.managedPath, HEAD_BYTES)
            else -> null
        }
        return idSource.resolve(
            game.platformId,
            fileName,
            primaryHead?.first,
            primaryHead?.second ?: 0,
        ).toAthenaCandidates()
    }

    /**
     * Multi-file disc (a `.m3u`/`.cue` manifest whose primary didn't hash-match): hash the game's disc/track
     * members smallest-first until an unambiguous DAT match. Skipped for single-file games (already hashed).
     */
    private suspend fun resolveDisc(
        opener: ContentOpener,
        probe: ResidentHashProbe,
        chdDiscovery: ChdParentChainDiscovery,
        game: GameEntity,
        budget: ChdVerificationBudget,
        exactHashes: MutableList<ExactHashWrite>,
    ): ResolveAttempt {
        val members = dao.membersOfGameBySize(game.gameId)
        if (members.size <= 1) return ResolveAttempt.TerminalAbstain
        val pid = PlatformId(game.platformId)
        val candidates = ArrayList<AthenaIdentityCandidate>()
        for (m in members) {
            val fn = residentFileName(m).orEmpty()
            val cheap = fn.endsWith(".chd", true) || fn.endsWith(".gdi", true)
            if (!cheap && (m.sizeBytes < 4096 || m.sizeBytes > MAX_TRACK_BYTES)) continue
            if (fn.endsWith(".chd", true)) {
                when (val attempt = resolveChdCandidate(opener, chdDiscovery, m, budget)) {
                    is ResolveAttempt.Identified ->
                        candidates += attempt.candidate.forExistingGroup(game.duplicateGroupKind)
                    ResolveAttempt.TerminalAbstain -> Unit
                    ResolveAttempt.Retryable -> return ResolveAttempt.Retryable
                }
                continue
            }
            val evidence = when (
                val result = probe.fingerprintEvidenceResultFor(
                    platformId = game.platformId,
                    documentUri = m.sourceDocumentUri,
                    managedPath = m.managedPath,
                    fileNameOverride = fn,
                )
            ) {
                is ResidentFingerprintResult.Fingerprinted -> result.evidence
                ResidentFingerprintResult.Unrecognized -> continue
                ResidentFingerprintResult.Retryable -> return ResolveAttempt.Retryable
            }
            stageExactHash(exactHashes, m.entryKey, evidence)
            val rec = datSource.lookup(MetadataQuery(pid, contentHash = evidence.datHash)) ?: continue
            candidates += AthenaIdentityCandidate(
                canonicalTitle = rec.canonicalTitle,
                collapseKey = datSource.collapseKeyFor(pid, evidence.datHash),
                origin = rec.origin,
                evidence = if (evidence.proof == RomFingerprintProof.CHD_HEADER_CLAIM) {
                    AthenaIdentityEvidence.CHD_HEADER_SHA1_CANDIDATE
                } else {
                    AthenaIdentityEvidence.CONTENT_SHA1
                },
            ).forExistingGroup(game.duplicateGroupKind)
        }
        return when (val decision = athenaIdentityEngine.decide(candidates)) {
            is AthenaIdentityDecision.Identified -> ResolveAttempt.Identified(decision.candidate)
            is AthenaIdentityDecision.Abstained -> ResolveAttempt.TerminalAbstain
            is AthenaIdentityDecision.NeedsReview -> ResolveAttempt.Retryable
        }
    }

    // ── Background re-verification of name-resolved identities ──────────────────────────────────

    /**
     * What reading a game's bytes concluded about the identity its NAME had already been given.
     *
     * [CONFLICT] is the outcome this whole pass exists to be able to produce. Everything else is the
     * pass confirming that the trade in [AthenaIdentityEvidence.DAT_NAME_UNIQUE] held for that file.
     */
    enum class ReverificationOutcome {
        /** The content-derived identity is the stored one. The name was telling the truth. */
        AGREED,

        /** The bytes say this is a different game. The stored identity is LEFT ALONE -- see the pass. */
        CONFLICT,

        /** Read fine, no DAT entry for these bytes. Not a disagreement: nothing to disagree with. */
        NO_DAT_MATCH,

        /** Routed somewhere [resolveByDumpName] never admitted, so its identity was never name-derived. */
        NOT_NAME_RESOLVABLE,

        /** Could not be read this time. Deliberately NOT marked, so the next pass tries again. */
        UNREADABLE,
    }

    data class ReverificationReport(
        val attempted: Int,
        val agreed: Int,
        val conflicted: Int,
        val noDatMatch: Int,
        val skipped: Int,
        val unreadable: Int,
        /**
         * True when the pass ran the page to its end AND that page was short: nothing further is waiting.
         *
         * Both halves are load-bearing. A short page the loop broke out of early -- on the deadline or on
         * [shouldYield] -- is not an empty queue, and the worker turns `exhausted` into `Result.success()`.
         */
        val exhausted: Boolean,
        /** False when a source mutation landed mid-pass and the batch was refused publication. */
        val published: Boolean,
    )

    /**
     * Read the bytes that a file NAME stood in for, and say whether it was telling the truth.
     *
     * This is the guard on the trade [resolveByDumpName] makes. That path accepts a dump name as
     * authoritative identity without opening the file, which is what took first-run identification on the
     * reference device from 22 minutes to 1.5 seconds. [AthenaIdentityEvidence.DAT_NAME_UNIQUE] states the
     * cost in its own KDoc -- *"a deliberately mislabelled file gets a wrong title until something reads
     * it"* -- and promises that a later content hash which disagrees "raises an authoritative conflict, a
     * review, not a silent overwrite". **This pass is the something that reads it.** Until it existed that
     * promise had no keeper: nothing in the app ever went back, so a name-resolved game was never
     * re-examined and the recoverability the evidence class claims was theoretical.
     *
     * ## What it costs, measured rather than assumed
     *
     * On the reference device (2026-09-17, 92 rows) 88 games carry no `contentHash` at all, and hashing
     * every one of them would read **83.7 GB**. That is not this pass. Two populations hide in that
     * number and only one of them is this pass's business:
     *
     *  - **id-keyed platforms** (`ds`, `3ds`, `switch`, `gamecube`, `wii`, `ps3`, `arcade`) -- 53 rows,
     *    **71.6 GB**, including a 16 GB Switch NSP. [resolveByDumpName] returns null for every one of
     *    them, so their identity was never name-derived: it came from a title ID in the head bytes, which
     *    is content-derived evidence already. Hashing them would verify nothing that is not already
     *    verified. The one thing it would add is exact-duplicate folding -- and on this library that is
     *    worth nothing either: all ten repeated titles (`Pokemon Red` x3, `The Legend of Zelda` x3,
     *    `Oracle of Ages` x3, ...) are ALREADY folded to one group and one primary, by the `collapseKey`
     *    path, with no `contentHash` on any of them. 71.6 GB to re-confirm ten folds that already work.
     *    Excluded, and [couldHaveBeenNameResolved] is where that exclusion lives.
     *  - **everything else** -- 18 rows, 12.1 GB, most of it two files. These went through
     *    [resolveByDumpName]. This is the pass.
     *
     * ## Two things it must never do
     *
     * 1. **It must never touch `identityResolvedAtMillis`.** That column is what
     *    `IdentityEnrichmentWorker.awaitIdentified` polls and what the artwork stage waits on. Un-resolving
     *    a row to signal "look at this again" would reopen the 22-minute first-run stall through a new
     *    door. A conflict therefore changes NOTHING about the identity -- it writes the hash, which is a
     *    true fact about the bytes either way, records the conflict, and leaves the title standing. A
     *    wrong title the player can see beats a game that silently leaves the example library.
     * 2. **It must never run where it can be felt.** [shouldYield] is how the caller says so, and the
     *    pass asks again before every file. Nothing here is on any interactive path.
     *
     * ## The honest imprecision
     *
     * A CHD is admitted by [resolveByDumpName] (the KDoc there explains why the check sits before the
     * route switch), and a CHD never persists an `exactRawSha1` even when it WAS resolved by content. So
     * for `.chd` rows "no hash" cannot distinguish name-resolved from content-resolved, and this pass may
     * re-verify a disc that never needed it -- one full decompress, once, ever, on charge. That is stated
     * rather than hidden because the alternative is skipping CHDs, and a hand-named `.chd` is the single
     * most likely file in a library to be mislabelled.
     *
     * @param onOutcome fires per game, before the batch is written, with the content-derived canonical
     *   title when there was one. The observed title is the whole value of a conflict record -- "these
     *   bytes are Sonic 2" is actionable and "this game is wrong" is not -- and it is passed out rather
     *   than logged here because this module runs in JVM unit tests where `android.util.Log` throws. That
     *   is not hypothetical: a log line added inside a `runCatching` in `ScreenScraperApi` on 2026-09-17
     *   turned a good quota into null on every JVM test that touched it.
     */
    suspend fun reverifyNameResolved(
        maxGames: Int = ReverificationBatch,
        absoluteDeadlineNanos: Long? = null,
        shouldYield: () -> Boolean = { false },
        onOutcome: (
            game: GameEntity,
            outcome: ReverificationOutcome,
            observedTitle: String?,
        ) -> Unit = { _, _, _ -> },
    ): ReverificationReport = withContentCancellation { scope ->
        val opener = baseOpener.boundTo(scope)
        val probe = ResidentHashProbe(opener, hashDispatcher)
        val chdDiscovery = chdChainDiscovery(opener)
        val snapshot = sourceIndexMutations.snapshotIndex {
            dao.unverifiedResolvedPrimaries(
                attemptedPrefix = ReverificationAttemptedPrefix,
                unreadablePrefix = ReverificationUnreadablePrefix,
                unreadableSince = nowMillis() - ReverificationUnreadableCooldownMillis,
                limit = maxGames,
            )
        }
        val games = snapshot.value
        val hashWrites = mutableListOf<ExactHashWrite>()
        val attemptedKeys = mutableListOf<String>()
        val conflictKeys = mutableListOf<String>()
        val unreadableKeys = mutableListOf<String>()
        var agreed = 0
        var conflicted = 0
        var noDatMatch = 0
        var skipped = 0
        var unreadable = 0

        // Whether the loop reached the end of the page. `exhausted` used to be `games.size < maxGames`
        // alone, which reports "there is no more work" for a SHORT page the loop broke out of early --
        // and the worker turns that into Result.success(). Since the only enqueue is a fresh identity
        // sweep, a stable library then never re-runs the pass, and the rows the break skipped are
        // stranded with no marker and no second chance.
        var pageCompleted = true

        for (game in games) {
            if (absoluteDeadlineNanos != null && nanoTime() >= absoluteDeadlineNanos) {
                pageCompleted = false
                break
            }
            // Checked per FILE, not once per page: a page can be eight discs and the player can start a
            // game during the first one. Breaking here keeps everything already staged -- those writes and
            // their markers still land below -- so standing down costs the run nothing it had earned.
            if (shouldYield()) {
                pageCompleted = false
                break
            }
            val fileName = residentFileName(game)
            val route = ContainerRoutingPolicy.route(game.platformId, fileName)
            if (!couldHaveBeenNameResolved(game, route)) {
                // Marked anyway, and that is the point. A row this pass will always decline still
                // satisfies the selector, so leaving it unmarked would let a handful of permanently
                // skipped games fill every page forever and starve the games that do need reading.
                attemptedKeys += game.entryKey
                skipped++
                onOutcome(game, ReverificationOutcome.NOT_NAME_RESOLVABLE, null)
                continue
            }
            val staged = mutableListOf<ExactHashWrite>()
            val budget = ChdVerificationBudget.forResolve(
                nanoTime = nanoTime,
                absoluteDeadlineNanos = absoluteDeadlineNanos,
            )
            // The SAME ladder the first pass would have used had the name missed -- deliberately not a
            // second hashing implementation that could drift from it and invent disagreements.
            var retryable = false
            val fresh: AthenaIdentityCandidate? = try {
                when (route) {
                    ContainerRoutingPolicy.Route.CHD ->
                        when (val attempt = resolveChd(opener, chdDiscovery, game, budget)) {
                            is ResolveAttempt.Identified -> attempt.candidate
                            ResolveAttempt.TerminalAbstain -> null
                            ResolveAttempt.Retryable -> {
                                retryable = true
                                null
                            }
                        }

                    else ->
                        when (val attempt = resolveByHash(opener, probe, game, budget, staged)) {
                            is HashAttempt.Candidate -> attempt.candidate
                            HashAttempt.NoMatch -> null
                            HashAttempt.Retryable -> {
                                retryable = true
                                null
                            }
                        }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                retryable = true
                null
            }
            if (retryable) {
                // Still NOT the attempted marker: an unmounted card or a busy file is not a verdict about
                // the bytes, and burning this game's one attempt on it would retire the guard for that
                // game permanently.
                //
                // But it does get a COOLDOWN, which is a different thing. Leaving it wholly unmarked was
                // a starvation bug: the selector takes the eight SMALLEST unverified rows, so eight
                // permanently unreadable small ones -- an ejected volume, a revoked SAF grant -- came
                // back as the entire page every single time, the worker saw `attempted == unreadable`
                // and retried, and nothing larger was ever read. The skip branch above already carried
                // this exact guard and says so in as many words; this branch did not.
                //
                unreadableKeys += game.entryKey
                unreadable++
                onOutcome(game, ReverificationOutcome.UNREADABLE, null)
                continue
            }
            hashWrites += staged
            attemptedKeys += game.entryKey
            if (fresh == null) {
                noDatMatch++
                onOutcome(game, ReverificationOutcome.NO_DAT_MATCH, null)
                continue
            }
            // Normalised through exactly what the write path applies, or a multi-disc group whose stored
            // title had its disc token stripped would "disagree" with itself on every single pass.
            val freshTitle = canonicalTitleForStamp(fresh.canonicalTitle, game.duplicateGroupKind)
            if (
                reverificationAgreesWithStored(
                    storedCanonicalTitle = game.canonicalTitle,
                    storedCollapseKey = game.collapseKey,
                    storedGroupKind = game.duplicateGroupKind,
                    observedCanonicalTitle = freshTitle,
                    observedCollapseKey = fresh.collapseKey,
                )
            ) {
                agreed++
                onOutcome(game, ReverificationOutcome.AGREED, freshTitle)
            } else {
                conflicted++
                conflictKeys += game.entryKey
                onOutcome(game, ReverificationOutcome.CONFLICT, freshTitle)
            }
        }

        val ts = nowMillis()
        // An empty batch must NOT reach the coordinator: commitIndexIfUnchangedAndGet advances the index
        // generation before it runs the mutation, so committing nothing would still invalidate every peer
        // reader's witness and make a concurrent enrichment sweep restart its keyset walk for no reason.
        val published = if (hashWrites.isEmpty() && attemptedKeys.isEmpty() && unreadableKeys.isEmpty()) {
            true
        } else {
            sourceIndexMutations.commitIndexIfUnchangedAndGet(snapshot.generation) {
                hashWrites.forEach { write -> dao.setContentHash(write.entryKey, write.contentHash) }
                attemptedKeys.forEach { key -> dao.stampMaintenance(ReverificationAttemptedPrefix + key, ts) }
                conflictKeys.forEach { key -> dao.stampMaintenance(ReverificationConflictPrefix + key, ts) }
                // REFRESH, not ignore: the cooldown runs from the LAST attempt, so a volume that has been
                // absent for a week is still retried one day after the most recent try rather than once.
                unreadableKeys.forEach { key ->
                    dao.refreshMaintenance(ReverificationUnreadablePrefix + key, ts)
                }
            } != null
        }
        ReverificationReport(
            attempted = agreed + conflicted + noDatMatch + skipped + unreadable,
            agreed = agreed,
            conflicted = conflicted,
            noDatMatch = noDatMatch,
            skipped = skipped,
            unreadable = unreadable,
            exhausted = pageCompleted && games.size < maxGames,
            published = published,
        )
    }

    /**
     * Whether [resolveByDumpName] would have been allowed to answer for this game -- the same two
     * conditions, read off the same fields, so the pass can never verify a population that path never
     * touched. Kept beside it: if that guard gains a third condition, this one is wrong until it does too.
     */
    private fun couldHaveBeenNameResolved(
        game: GameEntity,
        route: ContainerRoutingPolicy.Route,
    ): Boolean =
        (route == ContainerRoutingPolicy.Route.DEFAULT || route == ContainerRoutingPolicy.Route.CHD) &&
            game.platformId !in idKeyedPlatforms

    private fun AthenaIdentityCandidate.forExistingGroup(
        storedGroupKind: String?,
    ): AthenaIdentityCandidate {
        val kind = storedGroupKind?.let { raw ->
            DuplicateGroupKind.entries.firstOrNull { it.name == raw }
        }
        return if (kind == DuplicateGroupKind.MULTI_DISC) {
            copy(
                canonicalTitle = DisplayTitle.canonicalForGroup(canonicalTitle, kind),
                // The physical members already share one authoritative gameId. Per-disc DAT keys must not
                // manufacture a second fold axis or make otherwise agreeing disc titles conflict.
                collapseKey = null,
            )
        } else {
            this
        }
    }

    private fun stageExactHash(
        writes: MutableList<ExactHashWrite>,
        entryKey: String,
        evidence: RomFingerprint,
    ) {
        val sha1 = evidence.exactRawSha1 ?: return
        require(RAW_SHA1.matches(sha1)) { "RomFingerprinter returned a malformed raw SHA-1" }
        writes += ExactHashWrite(entryKey, "raw-sha1:$sha1")
    }

    private fun rawFileName(documentUri: String?, managedPath: String?): String? {
        val s = documentUri ?: managedPath ?: return null
        val decoded = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
        return decoded.substringAfterLast('/').substringAfterLast('\\').ifBlank { null }
    }

    private fun residentFileName(game: GameEntity): String? = ContainerRoutingPolicy.fileName(
        relativePath = game.relativePath,
        displayName = game.displayName,
        legacyLocation = rawFileName(game.sourceDocumentUri, game.managedPath),
    )

    private fun readHead(
        opener: ContentOpener,
        documentUri: String?,
        managedPath: String?,
        n: Int,
    ): Pair<ByteArray, Int>? {
        val stream = opener.open(documentUri, managedPath) ?: return null
        return stream.use {
            val buf = ByteArray(n)
            var len = 0
            while (len < n) {
                val r = it.read(buf, len, n - len)
                if (r < 0) break
                len += r
            }
            buf to len
        }
    }

    private fun readRelated(
        opener: ContentOpener,
        documentUri: String?,
        managedPath: String?,
        relativePath: String,
        n: Int,
    ): Pair<ByteArray, Int>? {
        val stream = opener.openRelated(documentUri, managedPath, relativePath) ?: return null
        return stream.use {
            val buf = ByteArray(n)
            var len = 0
            while (len < n) {
                val read = it.read(buf, len, n - len)
                if (read < 0) break
                len += read
            }
            buf to len
        }
    }

    private companion object {
        const val CHD_HEADER_BYTES = 132
        const val HEAD_BYTES = 512

        /**
         * Enough to reach partition 0's NCCH product code on a 3DS card image. A normal dump puts
         * partition 0 at 0x4000 and the code at +0x150; 32 KB tolerates a dump whose partition table says
         * something else, and `IdExtractor.threeDsProductCode` returns null rather than guessing if even
         * this does not reach — which routes the game to the hash, exactly as before this existed.
         */
        const val THREE_DS_HEAD_BYTES = 32 * 1024
        const val PS3_ISO_VOLUME_ID_END = 16 * 2_048 + 40 + 32
        const val MAX_SFO_BYTES = 64 * 1024
        const val MAX_TRACK_BYTES = 256L * 1024 * 1024 // don't whole-file-hash a >256MB track
        val RAW_SHA1 = Regex("[0-9a-f]{40}")
        val SWITCH_CATALOG_STAMP = BundledIdManifest.assets.single {
            it.platformId == PlatformId.SWITCH
        }.contentSha256
        val PS3_SFO_CANDIDATES = listOf("PARAM.SFO", "../PARAM.SFO", "../../PARAM.SFO")
    }

    private sealed interface ResolveAttempt {
        data class Identified(val candidate: AthenaIdentityCandidate) : ResolveAttempt
        data object TerminalAbstain : ResolveAttempt
        data object Retryable : ResolveAttempt
    }

    private sealed interface HashAttempt {
        data class Candidate(val candidate: AthenaIdentityCandidate) : HashAttempt
        data object NoMatch : HashAttempt
        data object Retryable : HashAttempt
    }
}

/**
 * Marker key prefixes for [LibraryIdentityEnricher.reverifyNameResolved], in `identity_maintenance`.
 *
 * Versioned in the key itself. Re-verification is only as good as the DAT corpus it ran against, so the
 * day that corpus changes shape the correct move is to bump this to `-v2:` and let every game come back
 * around, NOT to delete rows. The old markers then read as a record of what v1 concluded.
 *
 * Public, unlike its two neighbours, for one reason: `:app` reads it to ask how many games are still
 * waiting BEFORE it schedules a worker, and Kotlin `internal` stops at the module edge.
 */
const val ReverificationAttemptedPrefix = "reverify-v1:"

/** Written ALONGSIDE the attempt marker, never instead of it, so a conflict is still a completed attempt. */
internal const val ReverificationConflictPrefix = "reverify-conflict-v1:"

/**
 * An attempt that could not READ the file. Distinct from [ReverificationAttemptedPrefix] because it is a
 * cooldown, not a verdict: an unmounted card is not a statement about bytes, and the row must come back.
 */
internal const val ReverificationUnreadablePrefix = "reverify-unreadable-v1:"

/**
 * How long a row that could not be read stays out of the page.
 *
 * **Unreadable-row starvation.** The selector takes the eight SMALLEST unverified rows
 * and an unreadable row was deliberately left unmarked, so it came back in the very next page. Eight
 * permanently unreadable small rows -- an ejected second volume, a revoked SAF grant, a card that did not
 * remount -- therefore filled every page forever, the worker saw `attempted == unreadable`, retried, and
 * got the same eight. Every larger game behind them was never verified. The pass had a starvation guard
 * for permanently SKIPPED rows already (see the marker in the not-name-resolvable branch, which says in
 * as many words that leaving them unmarked "would let a handful of permanently skipped games fill every
 * page forever"); the unreadable branch is the same shape and did not have one.
 *
 * A day, because the failure it models is physical: a card put back, a permission re-granted, a volume
 * remounted. Short enough that a genuine remount is picked up the same night it happens, long enough that
 * a missing volume cannot hold the queue.
 */
internal const val ReverificationUnreadableCooldownMillis = 24L * 60L * 60L * 1000L

/** Small on purpose: a page is one charge-and-idle slice, not a sweep. */
internal const val ReverificationBatch = 8

/**
 * Whether content-derived identity agrees with what a file's NAME had already been accepted as.
 *
 * Extracted, and `internal` rather than private, for one reason: it is the only line in
 * [LibraryIdentityEnricher.reverifyNameResolved] that can produce a CONFLICT, and every other test of that
 * pass runs against an empty DAT manifest -- so without a direct test the one branch the whole feature
 * exists to reach would ship with no coverage at all.
 *
 * The normalisation is the entire subtlety, and it is not symmetric. A multi-disc group's stored row
 * deliberately holds the GROUP title with the disc token stripped and a null collapse key, because its
 * members share one authoritative identity; a freshly-hashed Disc 1 comes back from the DAT as
 * `Chrono Cross (Disc 1)` carrying its own per-disc key. Comparing those raw is a disagreement, so a naive
 * version would flag every multi-disc set in the library as a conflict, on every pass, forever -- and each
 * one would have re-decompressed a CHD to get there. [observedCanonicalTitle] therefore arrives already
 * through [canonicalTitleForStamp] (the write path's own normaliser) and the collapse key is dropped here
 * under exactly the condition that made the stored one null.
 */
internal fun reverificationAgreesWithStored(
    storedCanonicalTitle: String?,
    storedCollapseKey: String?,
    storedGroupKind: String?,
    observedCanonicalTitle: String,
    observedCollapseKey: String?,
): Boolean {
    val observedCollapse =
        if (storedGroupKind == DuplicateGroupKind.MULTI_DISC.name) null else observedCollapseKey
    return observedCanonicalTitle == storedCanonicalTitle && observedCollapse == storedCollapseKey
}

/** Unknown persisted group kinds fail closed: only the exact typed MULTI_DISC value strips a disc token. */
internal fun canonicalTitleForStamp(title: String, storedGroupKind: String?): String {
    val kind = storedGroupKind?.let { raw ->
        DuplicateGroupKind.entries.firstOrNull { it.name == raw }
    }
    return DisplayTitle.canonicalForGroup(title, kind)
}
