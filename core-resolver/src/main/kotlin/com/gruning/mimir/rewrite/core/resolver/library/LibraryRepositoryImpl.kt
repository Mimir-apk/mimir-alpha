package com.gruning.mimir.rewrite.core.resolver.library

import com.gruning.mimir.rewrite.core.model.jobs.ScanProgressState
import com.gruning.mimir.rewrite.core.model.jobs.ScanPhase
import com.gruning.mimir.rewrite.core.model.ContentKind
import com.gruning.mimir.rewrite.core.model.DisplayTitle
import com.gruning.mimir.rewrite.core.model.DuplicateGroupKind
import com.gruning.mimir.rewrite.core.model.EntryKey
import com.gruning.mimir.rewrite.core.model.GameGroup
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.ResidentFile
import com.gruning.mimir.rewrite.core.model.library.CoverOverrideSource
import com.gruning.mimir.rewrite.core.model.library.ArtSource
import com.gruning.mimir.rewrite.core.model.library.ChangedMedia
import com.gruning.mimir.rewrite.core.model.library.DailyLaunchCount
import com.gruning.mimir.rewrite.core.model.library.EnrichAttemptSource
import com.gruning.mimir.rewrite.core.model.library.GameMemberRow
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.model.library.GenreSource
import com.gruning.mimir.rewrite.core.model.library.LibraryRepository
import com.gruning.mimir.rewrite.core.model.library.LibrarySourceRole
import com.gruning.mimir.rewrite.core.model.library.LibrarySummary
import com.gruning.mimir.rewrite.core.model.library.launchesPerLocalDay
import com.gruning.mimir.rewrite.core.model.library.PlatformRow
import com.gruning.mimir.rewrite.core.model.library.PreviewVideoSource
import com.gruning.mimir.rewrite.core.model.library.ScanResult
import com.gruning.mimir.rewrite.core.model.library.ScanOutcome
import com.gruning.mimir.rewrite.core.model.library.SourceFailureKind
import com.gruning.mimir.rewrite.core.model.library.SourceRootUnreadableReason
import com.gruning.mimir.rewrite.core.model.library.SourceRow
import com.gruning.mimir.rewrite.core.model.library.SourceReconcileResult
import com.gruning.mimir.rewrite.core.model.library.SourceRemovalResult
import com.gruning.mimir.rewrite.core.resolver.KeyingDedupGrouper
import com.gruning.mimir.rewrite.core.resolver.M3uPlaylistResolver
import com.gruning.mimir.rewrite.core.resolver.scan.RecognitionResult
import com.gruning.mimir.rewrite.core.resolver.scan.ScanRecognizer
import com.gruning.mimir.rewrite.core.resolver.scan.ScanSkipLedger
import com.gruning.mimir.rewrite.core.resolver.scan.SourceLister
import com.gruning.mimir.rewrite.core.resolver.scan.SourceWalkResult
import com.gruning.mimir.rewrite.core.resolver.scan.WalkedFile
import com.gruning.mimir.rewrite.core.storage.dao.FavoriteDao
import com.gruning.mimir.rewrite.core.storage.dao.GameDao
import com.gruning.mimir.rewrite.core.storage.dao.GameIdentityDao
import com.gruning.mimir.rewrite.core.storage.dao.GameRowProjection
import com.gruning.mimir.rewrite.core.storage.dao.HiddenItemDao
import com.gruning.mimir.rewrite.core.storage.dao.LaunchProofDao
import com.gruning.mimir.rewrite.core.storage.dao.observeLaunchesPerDay
import com.gruning.mimir.rewrite.core.storage.dao.PlatformDao
import com.gruning.mimir.rewrite.core.storage.dao.SourceRootDao
import com.gruning.mimir.rewrite.core.storage.dao.SourceReconcileDao
import com.gruning.mimir.rewrite.core.storage.entity.FavoriteEntity
import com.gruning.mimir.rewrite.core.storage.entity.GameEntity
import com.gruning.mimir.rewrite.core.storage.entity.HiddenItemEntity
import com.gruning.mimir.rewrite.core.storage.entity.PlatformEntity
import com.gruning.mimir.rewrite.core.storage.entity.SourceRootEntity
import com.gruning.mimir.rewrite.core.storage.entity.SourceReconcileRootEntity
import com.gruning.mimir.rewrite.core.storage.entity.SourceReconcileRunEntity
import com.gruning.mimir.rewrite.core.storage.mapper.toEntity
import com.gruning.mimir.rewrite.core.storage.scan.FriendlyPath
import com.gruning.mimir.rewrite.core.storage.scan.SourceRootIdentity
import com.gruning.mimir.rewrite.core.storage.ProcessSourceRootMutations
import com.gruning.mimir.rewrite.core.storage.SourceMutationGeneration
import com.gruning.mimir.rewrite.core.storage.SourceRootMutationCoordinator
import com.gruning.mimir.rewrite.core.storage.SourceScanCommitResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.util.UUID
import com.gruning.mimir.rewrite.core.resolver.ResolutionInput
import com.gruning.mimir.rewrite.core.resolver.ExtensionPlatformClassifier
import com.gruning.mimir.rewrite.core.model.physicalResidentKey
import com.gruning.mimir.rewrite.core.model.library.LibraryDepartureReason
import com.gruning.mimir.rewrite.core.model.library.LibraryDiscSet
import com.gruning.mimir.rewrite.core.model.library.LibraryRelocation
import com.gruning.mimir.rewrite.core.model.library.addedGameIds

/**
 * The real [LibraryRepository]: SAF/file scan → recognize → dedup → Room → Flows. It lives in
 * `core-resolver` (which depends on both `core-model` and `core-storage`) so it can wire the recognition
 * pipeline to the persistence layer without inverting the module dependency graph — `core-storage` stays
 * a leaf that only owns the schema.
 *
 * All Flows are DEDUP-COLLAPSED product-truth rows (one tile per group, losers hidden), computed in SQL
 * so the UI never re-folds. Every DB call runs off the caller's thread ([ioDispatcher]); Flows are
 * emitted on [ioDispatcher] too.
 *
 * @property gameDao folded game queries.
 * @property platformDao shelf platform rows + counts.
 * @property sourceRootDao user source registry.
 * @property walker lists a source root in place (SAF tree or plain path).
 * @property recognizer per-file recognition.
 * @property grouper dedup/grouping over a batch of recognized files.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class LibraryRepositoryImpl(
    private val gameDao: GameDao,
    private val platformDao: PlatformDao,
    private val sourceRootDao: SourceRootDao,
    private val hiddenItemDao: HiddenItemDao,
    private val favoriteDao: FavoriteDao,
    // Offline genre lookup (bundled map). Defaults to a no-op so previews/tests build without an asset.
    private val genreSource: GenreSource = GenreSource { null },
    // Offline LOCAL-ONLY box-art lookup (sibling image beside the ROM). No-op default for previews/tests.
    private val artSource: ArtSource = ArtSource { _, _ -> null },
    // Offline LOCAL-ONLY preview-video lookup (sibling <base>.mp4). No-op default for previews/tests.
    private val previewVideoSource: PreviewVideoSource = PreviewVideoSource { _, _ -> null },
    // The shelf face the player chose by hand, or the automatic square standing in for a missing cover (policy
    // 2026-10-10: every platform). No-op default keeps every existing site and preview on the automatic cover.
    private val coverOverrideSource: CoverOverrideSource = CoverOverrideSource { _, _ -> null },
    // How the last per-game Enrich ended . No-op default: no game has an attempt on record.
    private val enrichAttemptSource: EnrichAttemptSource = EnrichAttemptSource { null },
    private val walker: SourceLister,
    /**
     * Required transaction authority. Production supplies Room's `withTransaction`; tests must
     * explicitly supply either a rollback-capable fake or a deliberate pass-through.
     */
    private val runScanTransaction: suspend (suspend () -> Unit) -> Unit,
    private val recognizer: ScanRecognizer = ScanRecognizer(),
    private val grouper: KeyingDedupGrouper = KeyingDedupGrouper(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * Where the favourites picker maps its 5,500 rows. Production passes [LibraryProjectionDispatcher], a
     * background-priority thread, so mapping yields to the UI. Tests keep [ioDispatcher]
     * and its virtual time.
     */
    private val projectionDispatcher: CoroutineDispatcher = ioDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
    /** v9 durable logical-lineage seam. Nullable only so pre-v9 unit fakes remain deliberately narrow. */
    private val gameIdentityDao: GameIdentityDao? = null,
    /** Commit-time ordering shared with live volume callbacks; walking remains outside this gate. */
    private val sourceRootMutations: SourceRootMutationCoordinator =
        ProcessSourceRootMutations.coordinator,
    /** v10 durable multi-root pass ledger. Nullable only for deliberately narrow pre-v10 test fakes. */
    private val sourceReconcileDao: SourceReconcileDao? = null,
    /** The launch history behind [observeLaunchesPerDay]. Nullable only so narrow test fakes read a quiet week. */
    private val launchProofDao: LaunchProofDao? = null,
) : LibraryRepository {

    /**
     * One process owns scan persistence at a time. Room still owns the durable transaction; this mutex
     * also prevents an older, slower walk from committing after a newer snapshot of the same source.
     */
    private companion object {
        /**
         * Files between RECOGNISING progress reports. Same reasoning as the walk's own throttle: a
         * [kotlinx.coroutines.flow.StateFlow] write per file over a tens-of-thousands-entry library is
         * noise at a different scale. The final file always reports regardless, so the last number a
         * reader sees is exact.
         */
        const val RecogniseProgressStep = 64

        val processScanMutex = Mutex()
        val reconcileOwnerToken: String = UUID.randomUUID().toString()
        // v11 rewrites every physical entry key; a paused v10 root pass must restart under p2 keys.
        const val RECONCILE_POLICY_STAMP = "ep14-p2-v2"
        const val MAX_NON_RETURNING_ATTEMPTS = 3
        const val ROOT_PENDING = "PENDING"
        const val ROOT_RUNNING = "RUNNING"
        const val ROOT_SETTLED = "SETTLED"
        const val OUTCOME_HEALTHY = "HEALTHY"
        const val OUTCOME_UNAVAILABLE = "UNAVAILABLE"
        const val OUTCOME_FAILED = "FAILED"
        const val OUTCOME_REMOVED = "REMOVED"
        const val OUTCOME_QUARANTINED = "QUARANTINED"
        const val OUTCOME_DRIFTED = "DRIFTED"
        const val SCAN_LOG_TAG = "MimirScanSkips"
    }

    /**
     * One DEBUG line per scan pass naming why files were dropped, so a "my games did not appear"
     * report is readable straight off logcat instead of guessing.
     *
     * Emits counts and reason names ONLY — never a file name, path, or title. A user's library
     * contents are private and must not reach a log. Per pass, never per file.
     *
     * `runCatching` mirrors the `core-input` instrument idiom: this module's JVM unit tests do not
     * stub `android.util.Log` (no `unitTests.isReturnDefaultValues`), so an unguarded call would
     * throw. An instrument must never change behavior anywhere.
     */
    private fun logScanSkipBreakdown(
        filesSeen: Int,
        recognized: Int,
        groups: Int,
        ledger: ScanSkipLedger,
    ) {
        runCatching {
            android.util.Log.d(
                SCAN_LOG_TAG,
                "scan seen=$filesSeen recognized=$recognized groups=$groups " +
                    "skipped=${ledger.total} [${ledger.summary()}]",
            )
        }
    }

    private data class ReconcileCommitContext(
        val runId: String,
        val rootId: String,
    )

    private data class ReconcilePlan(
        val run: SourceReconcileRunEntity,
        val roots: List<SourceReconcileRootEntity>,
        val resumed: Boolean,
        val skippedAlreadySettled: Int,
    )

    private sealed interface SourceScanAttempt {
        data class Completed(
            val result: ScanResult,
            val sourceGeneration: SourceMutationGeneration,
        ) : SourceScanAttempt

        data object Stale : SourceScanAttempt
    }

    // Media revision folded into the row-projection combines. [MediaVersions.perGame] is the per-game art
    // "epoch" — bumped by [refreshArt] after a one-game rescrape so that tile's image URIs get a fresh
    // `#mv=` fragment, busting Coil's memory-cache key. [MediaVersions.batchEpoch] is the library-wide
    // epoch — bumped by [refreshAllMedia] after a batch fetch: media files never touch the DB, so this
    // bump is the only thing that makes freshly downloaded covers/logos/clips surface without a rescan
    // or an app restart (RP5 Bifröst-backdrop repro 2026-07-17).
    private val artVersion = MutableStateFlow(MediaVersions())

    /** See [artVersion]: the per-game rescrape epoch map + the library-wide batch epoch. */
    private data class MediaVersions(
        val batchEpoch: Int = 0,
        val perGame: Map<String, Int> = emptyMap(),
        val faceEpoch: Int = 0, // [refreshShelfFaces]: a re-projection with no art re-keyed
    )

    // ── Reactive reads (all folded in SQL) ───────────────────────────────────

    override fun observeSummary(): Flow<LibrarySummary> =
        combine(
            gameDao.observeVisibleGameCount(),
            gameDao.observePlatformCounts(),
        ) { gameCount, platformCounts ->
            LibrarySummary(
                gameCount = gameCount,
                platformCount = platformCounts.count { it.gameCount > 0 },
            )
        }.distinctUntilChanged().flowOn(ioDispatcher)

    // Shelf tiles from LIVE truth: platform LABELS come from the platforms table (stable), but the COUNT
    // (and whether a platform appears at all) comes from the games table's live per-platform count — the
    // same reactive source [observeSummary] uses. This is what makes the shelf re-render when background
    // identity enrichment collapses duplicates (games-table writes); gating on the denormalized
    // `platforms.gamesCount` — written once at scan, never by enrichment — was the "shelf doesn't update
    // after identifying" bug. A platform shows only when its live primary/non-hidden count is > 0.
    override fun observePlatforms(): Flow<List<PlatformRow>> =
        combine(
            platformDao.observeAll(),
            gameDao.observePlatformCounts(),
        ) { platforms, counts ->
            val liveCount = counts.associate { it.platformId to it.gameCount }
            platforms.mapNotNull { p ->
                val n = liveCount[p.platformId] ?: 0
                if (n > 0) PlatformRow(PlatformId(p.platformId), p.displayName, n) else null
            }
        }
            .distinctUntilChanged()
            .flowOn(ioDispatcher)

    override fun observeGames(platformId: PlatformId): Flow<List<GameRow>> =
        // Combine with artVersion so a per-game rescrape ([refreshArt]) re-runs the projection AND version-
        // busts the rescraped tile's art URI — the fresh cover repaints without a relaunch.
        // Throttled for the reason observeFavoriteCandidates gives: the read holds Room's write connection, and
        // identification writes `games` in bursts. Outside a burst the first change still lands at once.
        combine(
            gameDao.visibleGameRowsForPlatform(platformId.value).atMostEvery(GameListRefreshMillis),
            artVersion,
        ) { rows, versions ->
            // distinctBy gameId is belt-and-suspenders: the query already GROUP BYs gameId, but the UI
            // grid keys on gameId, so a duplicate must never reach it (a title-based gameId can collide
            // across un-collapsed region variants — a resolver edge tracked separately).
            rows.map { toGameRow(it, versions) }.distinctBy { it.gameId }
        }
            .distinctUntilChanged()
            .flowOn(ioDispatcher)

    override fun observeFavoriteCandidates(): Flow<List<GameRow>> =
        combine(
            combine(
                // The big read holds Room's write connection while it runs (see favoriteCandidateGameRows), and
                // identification writes `games` many times a second. Throttled, a burst of writes costs one
                // re-read per period instead of one per write (regrouping measured ~8x slower with this
                // page up).
                gameDao.favoriteCandidateGameRows().atMostEvery(FavoriteCandidateRefreshMillis),
                artVersion,
            ) { rows, versions ->
                rows.map { toGameRow(it, versions) }.distinctBy { it.gameId }
            },
            // The star must not wait on that throttle, so favourite state comes from its own small read. It
            // re-runs on every `games` write too; the dedupe keeps those from reaching the list at all.
            gameDao.favoriteGroupIds().map { it.toSet() }.distinctUntilChanged(),
        ) { games, favoriteIds ->
            // A star re-marks rows the list already mapped; it never re-maps 5,500 rows. The big
            // read carries no favourite state, so only favourites are copied.
            games.map { game ->
                val favourite = game.gameId.value in favoriteIds
                if (game.isFavorite == favourite) game else game.copy(isFavorite = favourite)
            }
        }
            .distinctUntilChanged()
            .flowOn(projectionDispatcher)

    override fun observeGames(gameIds: Set<GameId>): Flow<List<GameRow>> {
        if (gameIds.isEmpty()) return flowOf(emptyList())
        val requested = gameIds.map { it.value }
        return currentAliasMapFlow().flatMapLatest { aliases ->
            val currentIds = requested.map { aliases[it] ?: it }.distinct()
            combine(
                gameDao.visibleAvailableGameRowsByIds(currentIds),
                artVersion,
            ) { rows, versions ->
                rows.map { toGameRow(it, versions) }.distinctBy { it.gameId }
            }
        }
            .distinctUntilChanged()
            .flowOn(ioDispatcher)
    }

    override fun observeGame(gameId: GameId): Flow<GameRow?> =
        currentAliasMapFlow().flatMapLatest { aliases ->
            val currentGameId = aliases[gameId.value] ?: gameId.value
            combine(gameDao.observeGameRow(currentGameId), artVersion) { row, versions ->
                row?.let { toGameRow(it, versions) }
            }
        }
            .distinctUntilChanged()
            .flowOn(ioDispatcher)

    override fun observeGameMembers(gameId: GameId): Flow<List<GameMemberRow>> =
        currentAliasMapFlow()
            .flatMapLatest { aliases ->
                gameDao.observeGameMembers(aliases[gameId.value] ?: gameId.value)
            }
            .map { members ->
                members.map { member ->
                    GameMemberRow(
                        entryKey = EntryKey(member.entryKey),
                        discNumber = member.discNumber,
                        displayName = member.displayName,
                        sizeBytes = member.sizeBytes,
                    )
                }
            }
            .distinctUntilChanged()
            .flowOn(ioDispatcher)

    override fun observeFavorites(): Flow<List<GameRow>> =
        // PERF-C: combined with artVersion AND consuming the versions — favorites previously took neither,
        // so a fetch/rescrape never repainted a favorite's art until relaunch (found by the PERF01 critique).
        combine(gameDao.favoriteGameRows(), artVersion) { rows, versions ->
            rows.map { toGameRow(it, versions) }.distinctBy { it.gameId }
        }
            .distinctUntilChanged()
            .flowOn(ioDispatcher)

    override fun observeRecentlyPlayed(limit: Int): Flow<List<GameRow>> =
        // Combined with artVersion so a rescrape repaints the Last Played context like every other surface.
        combine(gameDao.recentlyPlayedGameRows(limit), artVersion) { rows, versions ->
            rows.map { toGameRow(it, versions) }.distinctBy { it.gameId }
        }
            .distinctUntilChanged()
            .flowOn(ioDispatcher)

    // Launches per local day for the Activity bars. The DAO read owns the bucketing and re-reads the clock on every
    // emission; this adds only the injected clock and the dispatcher. Without a DAO the week is quiet, never missing.
    override fun observeLaunchesPerDay(): Flow<List<DailyLaunchCount>> =
        (
            launchProofDao?.observeLaunchesPerDay(clock = clock)
                ?: flow { emit(launchesPerLocalDay(emptyList(), clock(), ZoneId.systemDefault())) }
            )
            .flowOn(ioDispatcher)

    override fun observeResolvingCount(): Flow<Int> =
        gameDao.unresolvedPrimaryCount().distinctUntilChanged()
            .flowOn(ioDispatcher)

    override fun observeHiddenGames(): Flow<List<GameRow>> =
        // PERF-C: same versions wiring as [observeFavorites] — the hidden/restore surface shows art too.
        combine(gameDao.hiddenGameRows(), artVersion) { rows, versions ->
            rows.map { toGameRow(it, versions) }
        }
            .distinctUntilChanged()
            .flowOn(ioDispatcher)

    override fun observeSources(): Flow<List<SourceRow>> =
        combine(sourceRootDao.all(), gameDao.observeStorageUnavailableSourceRoots()) { roots, unavailableRoots ->
                val unavailable = unavailableRoots.toSet()
                // combine's transform is suspend, so each root can carry a live folded count here.
                roots.map { r ->
                    SourceRow(
                        friendlyPath = r.friendlyLabel,
                        rootUri = r.rootUri,
                        gameCount = gameDao.visibleGameCountForSource(r.rootUri),
                        available = r.availableFromLastSeen() && r.rootUri !in unavailable,
                        isPrimary = r.isPrimarySource,
                    )
                }
            }
            .distinctUntilChanged()
            .flowOn(ioDispatcher)

    // ── The scan ─────────────────────────────────────────────────────────────
    // rootUri is an exact user-selected SAF tree or readable path, not an Inbox or /Mimir-scoped path.
    // Preserve it unchanged through walking, recognition, entry keys, locators, and source registration.

    /**
     *  The contract, and the invariant it
     * exists to hold, are in [LibraryRepository.relocate]; this is how it is held here.
     *
     * ## Why the row can follow at all
     *
     * `gameId` is **content**-derived — `hash:`, `serial:platform:…`, `catalog:key`, or
     * `title:platform:filename` ([GameIdentityBuilder]). A move changes neither the bytes nor the name,
     * so the only two inputs it can disturb are the **platform** its folder implies and, on a *Keep both*
     * collision, the **filename**. `entryKey` is by contrast pure location
     * (`physicalResidentKey(sourceRootUri, relativePath)`) and always changes. So a move is a location
     * rewrite under a stable identity — provided those two inputs held, which is exactly what this checks
     * before touching anything.
     *
     * That check costs one classifier call and no IO. It deliberately does not re-hash: a 3.5 GB `.iso`
     * that moved is the same 3.5 GB of bytes, and reading them to prove it would make a move cost more
     * than the move.
     *
     * ## Why departure is deletion
     *
     * [pruneEntriesMissingFromSource] deletes rows for files gone from a walked root — that is what the
     * scanner already does, and inventing a "moved away" availability here would put the shelf in a state
     * nothing else in the system can read, which the next scan would resolve by its own rules anyway.
     *
     * ## Ordering
     *
     * Inside [serializedScan] and [runScanTransaction]: a concurrent reconcile must not interleave with a
     * delete-then-insert of the same row, and a crash between the two halves must leave the old row
     * rather than no row.
     */
    override suspend fun relocate(fromPath: String, toPath: String): LibraryRelocation =
        serializedScan {
            withContext(ioDispatcher) {
                val row = gameDao.gameByManagedPath(fromPath) ?: return@withContext LibraryRelocation.NotInLibrary
                val gameId = GameId(row.gameId)
                val partOfSet = gameDao.memberCount(row.gameId) > 1
                val destinationRoot = plainFileSourceRootFor(toPath)
                    ?: return@withContext depart(row, gameId, LibraryDepartureReason.OUTSIDE_SOURCES, partOfSet)
                if (!relocationKeepsIdentity(row, fromPath, toPath)) {
                    return@withContext depart(row, gameId, LibraryDepartureReason.IDENTITY_CHANGED, partOfSet)
                }

                val relativePath = toPath.removePrefix(destinationRoot).trimStart('/')
                val moved = row.copy(
                    entryKey = physicalResidentKey(destinationRoot, relativePath),
                    sourceRootUri = destinationRoot,
                    managedPath = toPath,
                    relativePath = relativePath,
                )
                runScanTransaction {
                    // Delete first: entryKey is the PRIMARY KEY, so this is a rewrite rather than an
                    // update, and leaving the old row would double the game on the shelf.
                    gameDao.deleteByEntryKey(row.entryKey)
                    gameDao.upsertAll(listOf(moved))
                }
                LibraryRelocation.Relocated(gameId, partOfSet)
            }
        }

    /**
     *  The contract is on
     * [LibraryRepository.discSetFor]; this is the checking it promises.
     *
     * ## Four refusals, in cost order
     *
     * 1. **No row** — the file manager moves screenshots and BIOS images; most subjects are not games.
     * 2. **Not `MULTI_DISC`** — `memberCount > 1` is a disc set *or* a duplicate group, and
     *    [DuplicateGroupKind] is the only thing that separates them. Saying "disc 2 of 3" about two
     *    copies of one ROM would be a fabricated fact in a warning whose whole job is to be believed.
     * 3. **No playlist row** — a title-grouped multi-disc set has no `.m3u` and therefore nothing on disk
     *    to leave dangling; the group re-forms on normalised title at the next scan wherever the members
     *    are.
     * 4. **Fewer than two discs beside it** — not a set.
     *
     * ## Reading the playlist, and why here
     *
     * `.m3u` entries resolve against the playlist's own parent ([M3uPlaylistResolver], `parent` +
     * `normalizeRelative`), so a set that lands together in one folder keeps working **only if every
     * entry is a bare sibling filename**. An entry that is absolute or reaches into a subfolder names a
     * place the move does not preserve, and offering "move them together" for one of those would
     * advertise a repair that does not repair.
     *
     * That question is m3u syntax, and m3u syntax lives in this module — [M3uPlaylistResolver.parseEntries]
     * is `public` for exactly this ("shared with read-only consumers that must honor scan-time M3U
     * syntax"). Answering it in the file manager would be a second parser, and two parsers is how a
     * playlist starts meaning two different things. The read is bounded by the scanner's own
     * [M3uPlaylistResolver.MAX_PLAYLIST_BYTES] and is a plain file read, not a walk: this runs off the
     * press on [ioDispatcher], never on a composition.
     *
     * **Not inside [serializedScan].** This writes nothing and blocking a warning behind an in-flight
     * scan would make the press feel broken; a set that changed under a concurrent scan is answered by
     * the move's own [relocate], which *is* serialized.
     */
    override suspend fun discSetFor(path: String): LibraryDiscSet? = withContext(ioDispatcher) {
        val row = gameDao.gameByManagedPath(path) ?: return@withContext null
        if (row.duplicateGroupKind != DuplicateGroupKind.MULTI_DISC.name) return@withContext null
        val members = gameDao.membersOfGame(row.gameId)
        // **Exactly one playlist, or this is not one set.** `gameId` is content-derived, so the same
        // game sitting in two folders -- `/Roms/psx/ff7/` and `/Roms/psx/backup-ff7/` -- lands in ONE
        // member list carrying two `.m3u`s and two of every disc. `firstOrNull` took a playlist from one
        // copy and then counted the other copy's files as its discs. Gatekeeper finding 2.
        val playlists = members.filter { it.contentKind == ContentKind.MULTI_DISC_PLAYLIST.name }
        // More than one playlist means more than one COPY of this game -- the duplicate-copy shape. Still
        // refused. Zero playlists is the ordinary GameCube/PS2 set and is now allowed through: it was
        // refused until 2026-08-18, so moving a disc of a real set produced no warning.
        if (playlists.size > 1) return@withContext null
        val playlist = playlists.firstOrNull()
        val playlistPath = playlist?.managedPath
        // Filtered by KIND, not by `entryKey != playlist.entryKey`: that predicate excluded ONE row, so
        // a second playlist was counted as a disc and reported to the player as one ("disc 2 of 7").
        val discRows = members.filter { it.contentKind != ContentKind.MULTI_DISC_PLAYLIST.name }
        val discs = discRows.mapNotNull { it.managedPath }
        if (discs.size < 2 || path !in discs) return@withContext null
        // **Two DISTINCT, POSITIVE disc numbers, or this is not a multi-disc set.**
        //
        // `duplicateGroupKind == MULTI_DISC` is necessary and nowhere near sufficient: `DedupGrouper`
        // also stamps it on a TRACK SET (`GroupingKey.TrackSetBase.kind`, `DedupGrouper.kt:59-61`), so a
        // perfectly ordinary single-disc `Game.cue` + `Game.bin` pair arrives here as a two-member
        // MULTI_DISC group and warned "MULTI-DISC GAME / One of 2 discs" on every move. The same held for
        // two REVISIONS of one disc -- `FF9 (Disc 1).chd` beside `FF9 (Disc 1) (Rev 1).chd` -- which
        // warned "Disc 1 of 2" and offered to co-move a game that has one disc.
        //
        // Both are the exact failure this feature was built to avoid: a warning that fires when nothing
        // breaks teaches the player to dismiss it, and then the one that matters is dismissed too. Executable reproductions exposed both false positives.
        //
        // `ScanRecognizer.discNumberOf` reads the number off the FILENAME's disc token, and
        // `M3uPlaylistResolver` numbers by playlist order for members a playlist resolved -- so a real
        // set has two positive numbers and neither false shape does: a cue/bin pair is {0, 0}, and two
        // revisions of disc 1 are {1, 1}. Measured against the measured card: the GameCube and PS1 sets are
        // {1, 2} and are untouched.
        val discNumbers = discRows.map { it.discNumber }.filter { it > 0 }.toSet()
        if (discNumbers.size < 2) return@withContext null
        /**
     * Distinct basenames prevent duplicate copies from colliding during a whole-set move. Because
     * `gameId` is content-derived, two folders of the same game can share one member list. Collapsing
     * repeated names into a Set let the move commit the first copy and then hit an ASK conflict on the
     * second, leaving the card with original paths that had already moved.
     *
     * Cross-folder sets must still be warned about: `M3uPlaylistResolver` supports `content://`,
     * `file://`, absolute and subfolder entries as well as bare siblings. Such sets are real and
     * require the degraded warn-only answer when they cannot travel intact. The single-playlist
     * check and distinct-basename check reject duplicate-copy shapes without excluding these sets.
     *
     * Compare basenames case-insensitively. Raw `toSet()` is case-sensitive, so `disc 1.chd` and
     * `Disc 1.chd` in two folders could pass it and collide on a case-insensitive exFAT destination.
     */
        val names = discs.map { java.io.File(it).name.lowercase() }
        if (names.size != names.toSet().size) return@withContext null
        LibraryDiscSet(
            gameId = GameId(row.gameId),
            title = row.title,
            subjectDiscNumber = row.discNumber,
            discPaths = discs,
            playlistPath = playlistPath,
            hasPlaylist = playlist != null,
            wholeSetTravelsIntact = when {
                // A playlist row the library holds only as a SAF document: it EXISTS, it will be left
                // behind, and this process cannot move it by path. A warning with no co-move -- the
                // model's documented null-playlistPath shape, which the gatekeeper called unconstructible
                // dead vocabulary (finding 1) because the old code returned null for the whole set here.
                playlist != null && playlistPath == null -> false
                // No playlist at all: nothing to break, so moving them together always works.
                playlistPath == null -> true
                else -> playlistTravelsIntact(playlistPath, discs)
            },
            companionPaths =DiscCompanionFiles.siblingsOf(discs, alreadyInSet = listOfNotNull(playlistPath)),
        )
    }

    /**
     * Whether every entry in the playlist at [playlistPath] is a bare filename naming one of [discs].
     *
     * **Fail-closed on every uncertainty**: unreadable, oversized, empty, or one entry that is absolute,
     * scheme-prefixed, in a subfolder, or names a file not in the set. Each of those means "moving them
     * together may not fix it", and the caller's only use for a true is to offer the player a repair.
     */
    private fun playlistTravelsIntact(playlistPath: String, discs: List<String>): Boolean {
        val file = java.io.File(playlistPath)
        if (!file.isFile || file.length() > M3uPlaylistResolver.MAX_PLAYLIST_BYTES) return false
        val body = runCatching { file.readText() }.getOrNull() ?: return false
        val entries = M3uPlaylistResolver.parseEntries(body)
        if (entries.isEmpty()) return false
        val names = discs.map { java.io.File(it).name }.toSet()
        return entries.all { entry ->
            entry.none { it == '/' || it == '\\' } && entry != "." && entry != ".." && entry in names
        }
    }

    private suspend fun depart(
        row: GameEntity,
        gameId: GameId,
        reason: LibraryDepartureReason,
        partOfSet: Boolean,
    ): LibraryRelocation {
        runScanTransaction { gameDao.deleteByEntryKey(row.entryKey) }
        return LibraryRelocation.Departed(gameId, reason, partOfSet)
    }

    /**
     * The registered source root [path] sits under, or null.
     *
     * **SAF roots cannot match and that is not a bug.** A `content://` tree uri is not a prefix of a
     * filesystem path, so a file moved into a SAF-granted folder answers null here and departs — which is
     * honest: `ManagedFileOperations` writes plain files, and a row claiming a SAF root it was not
     * written through would be a lie about how the scanner will find it.
     *
     * Longest match wins, so a source nested inside another source claims its own files.
     */
    private suspend fun plainFileSourceRootFor(path: String): String? =
        sourceRootDao.allOnce()
            .map { it.rootUri.trimEnd('/') }
            .filterNot { it.startsWith("content://") }
            .filter { it.isNotBlank() && path.startsWith("$it/") }
            .maxByOrNull { it.length }

    /**
     * Whether the scanner would still name this file the same thing at [toPath].
     *
     * Two questions, and both are the identity builder's own inputs rather than a proxy for them: did the
     * **name** survive (a *Keep both* collision renames the destination, and `title:` ids are
     * filename-derived), and does the destination folder still imply the **same platform** (a `.gba`
     * dropped into `ps2/` classifies to nothing, and a row claiming otherwise is one the next scan
     * deletes).
     */
    private fun relocationKeepsIdentity(row: GameEntity, fromPath: String, toPath: String): Boolean {
        val name = toPath.substringAfterLast('/')
        if (name != fromPath.substringAfterLast('/')) return false
        val platformAtDestination = ExtensionPlatformClassifier().classifyWithContext(
            ResolutionInput(fileName = name, extension = name.substringAfterLast('.', "").lowercase()),
            ExtensionPlatformClassifier.platformHintFromFolder(toPath.substringBeforeLast('/', "")),
        )
        return platformAtDestination?.value == row.platformId
    }

    override suspend fun rescan(rootUri: String): ScanResult = serializedScan {
        scanSourceUntilStable(rootUri, LibrarySourceRole.PRESERVE).also { result ->
            if (result.outcome == ScanOutcome.Healthy) refreshAllMedia()
        }
    }

    override suspend fun addSource(rootUri: String, role: LibrarySourceRole): ScanResult = serializedScan {
        scanSourceUntilStable(rootUri, role).also { result ->
            if (result.outcome == ScanOutcome.Healthy) refreshAllMedia()
        }
    }

    private suspend fun scanSourceUntilStable(
        rootUri: String,
        role: LibrarySourceRole,
    ): ScanResult {
        // The progress clear moved to `serializedScan` -- see its KDoc. This loop is not the outermost
        // scan boundary it was believed to be: `reconcileSources` bypasses it entirely.
        while (true) {
            when (val attempt = scanSource(rootUri, role)) {
                is SourceScanAttempt.Completed -> return attempt.result
                SourceScanAttempt.Stale -> currentCoroutineContext().ensureActive()
            }
        }
    }

    private suspend fun scanSource(
        rootUri: String,
        role: LibrarySourceRole,
        reconcileCommit: ReconcileCommitContext? = null,
        onCommittedMutation: (() -> Unit)? = null,
    ): SourceScanAttempt = withContext(ioDispatcher) {
        val sourceGeneration = sourceRootMutations.currentSourceMutationGeneration()
        val gameIdsBeforeScan = gameDao.allPrimaryGames().asSequence().map(GameEntity::gameId).toSet()
        // Two different jobs, and conflating them is what doubled a measured library.
        //
        //  - [rootUri] is what we can READ THROUGH. A SAF grant URI may be the only spelling with access,
        //    so the walk must use exactly what the caller handed us.
        //  - [identityRootUri] is what rows are KEYED ON: `sourceRootUri`, every `entryKey` beneath it,
        //    the prune scope, and the source row.
        //
        // When this directory is already registered under a different spelling, the scan adopts the
        // REGISTERED one. Otherwise a SAF rescan of a folder added by path re-keys every file
        // (`physicalResidentKey(rootUri, relativePath)`), inserts a parallel row for each, and prunes only
        // within its own root so the originals survive -- 38 files becoming 76, which is exactly what a QA
        // rescan hook did to the Odin on 2026-08-26.
        //
        // **Found on device, after the source-row fix alone looked sufficient.** Guarding registration kept
        // `source_roots` at one row while the games table quietly doubled again; the unit tests asserted
        // the source count and never looked at the rows.
        val identityRootUri = sourceRootDao.allOnce()
            .firstOrNull { SourceRootIdentity.namesSameDirectory(it.rootUri, rootUri) }
            ?.rootUri
            ?: rootUri
        val walk = try {
            // A listing is only believed from a root that is positively readable. Without "All files access" a
            // shared folder still opens and lists as EMPTY, which the prune below would take for a real deletion
            // (observed: 195 rows to 0). An unreadable root ends here as unavailable and nothing under it changes.
            if (!walker.rootReadable(rootUri)) {
                SourceWalkResult.Unavailable(SourceRootUnreadableReason)
            } else {
                // No total exists until this returns, so the walk reports a COUNT and no fraction. `ScanProgress.fraction` is null for this phase by construction.
                walker.walk(rootUri) { seen -> ScanProgressState.report(ScanPhase.WALKING, seen = seen) }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            SourceWalkResult.Failed(error.message ?: "Unable to traverse source root")
        }
        val walked = when (walk) {
            is SourceWalkResult.Complete -> walk.files.sortedWith(
                compareBy<WalkedFile> { it.relativePath }
                    .thenBy { it.displayName }
                    .thenBy { it.documentUri.orEmpty() }
                    .thenBy { it.filePath.orEmpty() },
            )
            is SourceWalkResult.Failure -> {
                return@withContext SourceScanAttempt.Completed(
                    result = ScanResult(
                        filesSeen = 0,
                        recognized = 0,
                        groups = 0,
                        skipped = 0,
                        outcome = ScanOutcome.Failed(walk.reason, walk.kind),
                    ),
                    sourceGeneration = sourceGeneration,
                )
            }
        }
        if (walked.zipWithNext().any { (left, right) -> left.relativePath == right.relativePath }) {
            return@withContext SourceScanAttempt.Completed(
                result = ScanResult(
                    filesSeen = 0,
                    recognized = 0,
                    groups = 0,
                    skipped = 0,
                    outcome = ScanOutcome.Failed(
                        reason = "Source traversal repeated a relative path",
                        kind = SourceFailureKind.FAILED,
                    ),
                ),
                sourceGeneration = sourceGeneration,
            )
        }
        val filesSeen = walked.size

        // 1. Recognize each file in place (extension + folder context → platform → identity).
        //    The recognizer already decides a SkipReason for every dropped file; the ledger keeps it
        //    instead of collapsing it into a bare total, so missing games can be diagnosed.
        //    Instrumentation only — recording happens after the decision and never feeds back into it.
        val recognized = ArrayList<ResidentFile>(walked.size)
        val skipLedger = ScanSkipLedger()
        // The one phase that can honestly draw a bar: a known total, advanced one COMPLETED file at a
        // time. That is the honesty contract's second permitted source of a real fraction -- a
        // completed, discrete unit of work -- rather than a model of one.
        ScanProgressState.report(ScanPhase.RECOGNISING, seen = 0, total = filesSeen)
        for ((index, w) in walked.withIndex()) {
            currentCoroutineContext().ensureActive()
            when (val r = recognizer.recognize(w, identityRootUri)) {
                is RecognitionResult.Recognized -> recognized += r.file
                is RecognitionResult.Skipped -> skipLedger.record(r.reason)
            }
            val done = index + 1
            if (done % RecogniseProgressStep == 0 || done == filesSeen) {
                ScanProgressState.report(ScanPhase.RECOGNISING, seen = done, total = filesSeen)
            }
        }
        val skipped = skipLedger.total

        // 2. Read bounded local `.m3u` manifests before grouping. Explicit playlist membership is hard
        //    evidence even when files use character/scenario names instead of `(Disc N)` tags.
        currentCoroutineContext().ensureActive()
        val recognizedInStableOrder = recognized.sortedWith(
            compareBy<ResidentFile>(
                { it.sourceRootUri.orEmpty() },
                ResidentFile::relativePath,
                { it.entryKey.value },
            ),
        )
        val playlistResolution = M3uPlaylistResolver.resolve(
            walked,
            recognizedInStableOrder,
            walker::readUtf8,
        )
        currentCoroutineContext().ensureActive()

        // 3. Dedup/group the batch (multi-disc / m3u / cross-storage / exact-hash rules).
        // Bulk work with nothing to count: a phase, and deliberately no fraction.
        ScanProgressState.report(ScanPhase.GROUPING, seen = filesSeen, total = null)
        val groups: List<GameGroup> = grouper.group(playlistResolution.files, playlistResolution.groups)
        currentCoroutineContext().ensureActive()

        // 4. Persist in place: game rows (with dedup group facts), platform rows, and the friendly
        //    source-registry row. No FILE is moved/copied/deleted — but the
        //    INDEX is reconciled: rows for THIS root whose file is gone from the current scan are pruned
        //    (a rescan reflects deletions, not just additions). Other roots' rows are untouched.
        ScanProgressState.report(ScanPhase.PERSISTING, seen = filesSeen, total = null)
        val currentKeys = groups.asSequence()
            .flatMap { it.allMembers.asSequence() }
            .map { it.entryKey.value }
            .toHashSet()
        val healthyResult = ScanResult(
            filesSeen = filesSeen,
            recognized = recognized.size,
            groups = groups.size,
            skipped = skipped,
            outcome = ScanOutcome.Healthy,
            skippedByReason = skipLedger.toNameMap(),
        )
        var committedAddedGameIds = emptySet<String>()
        logScanSkipBreakdown(filesSeen, recognized.size, groups.size, skipLedger)
        currentCoroutineContext().ensureActive()
        val commitResult = sourceRootMutations.commitScanIfCurrent(identityRootUri, sourceGeneration) {
            runScanTransaction {
                currentCoroutineContext().ensureActive()
                if (reconcileCommit == null) {
                    // A standalone add/rescan changes the source incarnation outside any paused pass.
                    // Invalidate the complete aggregate instead of deleting one root row and risking a
                    // double-counted resumed receipt.
                    sourceReconcileDao?.clearAll()
                }
                val touchedLogicalIds =
                    gameIdentityDao?.logicalGameIdsTouchingRoot(identityRootUri).orEmpty()
                // Which id each file carries BEFORE the scan writes: a file the scan now names differently keeps the
                // launch history of the game it was (carried below, once the rows have settled).
                val identitiesBeforeScan =
                    gameIdentityDao?.entryGameIdentitiesForRoot(identityRootUri).orEmpty()
                gameDao.prepareSourceForRescan(identityRootUri)
                val scannedLogicalIds = persistGroups(groups)
                val returningLogicalIds = gameIdentityDao?.logicalGameIdsForPhysicalIds(
                    groups.asSequence()
                        .flatMap { it.allMembers.asSequence() }
                        .mapNotNull { it.identity?.gameId?.value }
                        .distinct()
                        .toList(),
                ).orEmpty()
                persistPlatforms(groups)
                pruneStaleEntries(identityRootUri, currentKeys)
                gameIdentityDao?.reconcileCurrentWinners(
                    logicalGameIds = touchedLogicalIds + scannedLogicalIds + returningLogicalIds,
                    nowMillis = clock(),
                )
                gameIdentityDao?.carryLaunchHistoryAcrossReidentification(
                    rootUri = identityRootUri,
                    before = identitiesBeforeScan,
                )
                committedAddedGameIds = addedGameIds(
                    before = gameIdsBeforeScan,
                    after = gameDao.allPrimaryGames().asSequence().map(GameEntity::gameId).toSet(),
                )
                val rootSeenMillis = persistSource(
                    rootUri = identityRootUri,
                    role = role,
                    allowPrimaryElection = reconcileCommit == null,
                )
                reconcileCommit?.let { context ->
                    settleReconcileRoot(
                        context = context,
                        outcome = OUTCOME_HEALTHY,
                        result = healthyResult.copy(addedGameIds = committedAddedGameIds),
                        rootSeenMillis = rootSeenMillis,
                    )
                }
                // Conservative pre-return marker: if cancellation lands after Room commits but before
                // scanSource returns, the caller's finally still refreshes media. A rollback may cause
                // one harmless extra refresh, never a missed one.
                onCommittedMutation?.invoke()
            }
        }
        when (commitResult) {
            SourceScanCommitResult.STALE -> return@withContext SourceScanAttempt.Stale
            SourceScanCommitResult.ABSENT -> {
                return@withContext SourceScanAttempt.Completed(
                    result = ScanResult(
                        filesSeen = filesSeen,
                        recognized = recognized.size,
                        groups = groups.size,
                        skipped = skipped,
                        outcome = ScanOutcome.Failed(
                            reason = "Source became unavailable before scan commit",
                            kind = SourceFailureKind.UNAVAILABLE,
                        ),
                        // The walk did complete, so the breakdown it produced is real; keep it with
                        // the counts it explains rather than reporting a total with no reasons.
                        skippedByReason = skipLedger.toNameMap(),
                    ),
                    sourceGeneration = sourceGeneration,
                )
            }
            SourceScanCommitResult.COMMITTED -> Unit
        }

        SourceScanAttempt.Completed(
            result = healthyResult.copy(addedGameIds = committedAddedGameIds),
            sourceGeneration = sourceGeneration,
        )
    }

    override suspend fun reconcileSources(): SourceReconcileResult = serializedScan {
        val gameIdsBeforeScan = withContext(ioDispatcher) {
            gameDao.allPrimaryGames().asSequence().map(GameEntity::gameId).toSet()
        }
        val result = reconcileSourcesInternal()
        val gameIdsAfterScan = withContext(ioDispatcher) {
            gameDao.allPrimaryGames().asSequence().map(GameEntity::gameId).toSet()
        }
        result.copy(addedGameIds = addedGameIds(gameIdsBeforeScan, gameIdsAfterScan))
    }

    private suspend fun reconcileSourcesInternal(): SourceReconcileResult {
        if (sourceReconcileDao == null) return reconcileSourcesWithoutLedger()

        var mediaDirty = false
        return try {
            withContext<SourceReconcileResult>(ioDispatcher) {
                passLoop@ while (true) {
                    val plan = prepareReconcilePlan()
                    val runId = plan.run.runId
                    var restartPlan = false

                    for (planned in plan.roots) {
                        currentCoroutineContext().ensureActive()
                        val currentRoot = requireNotNull(
                            sourceReconcileDao.rootsForRun(runId)
                                .firstOrNull { it.rootId == planned.rootId },
                        ) { "Reconciliation root ${planned.rootId} disappeared from active run $runId" }
                        if (currentRoot.phase == ROOT_SETTLED) continue

                        val liveSnapshot = sourceRootMutations.snapshotSourceMutation {
                            sourceRootDao.allOnce().firstOrNull { it.rootId == currentRoot.rootId }
                        }
                        if (liveSnapshot.value == null) {
                            val settled = sourceRootMutations.commitIfSourceUnchanged(
                                liveSnapshot.generation,
                            ) {
                                runScanTransaction {
                                    settleReconcileRoot(
                                        context = ReconcileCommitContext(runId, currentRoot.rootId),
                                        outcome = OUTCOME_DRIFTED,
                                        result = emptyFailedScan(
                                            SourceFailureKind.FAILED,
                                            "Planned source drifted",
                                        ),
                                        rootSeenMillis = null,
                                        countAsSourceFailure = false,
                                        countAsDrift = true,
                                    )
                                }
                            }
                            if (!settled) {
                                restartPlan = true
                                break
                            }
                            continue
                        }

                        try {
                            // Claim is inside the cancellation boundary. If cancellation commits RUNNING
                            // and then throws on transaction return, the catch below resets it to PENDING.
                            runScanTransaction {
                                sourceReconcileDao.upsertRoot(currentRoot.copy(phase = ROOT_RUNNING))
                            }

                            when (
                                val attempt = scanSource(
                                    rootUri = currentRoot.rootUri,
                                    role = LibrarySourceRole.PRESERVE,
                                    reconcileCommit = ReconcileCommitContext(
                                        runId,
                                        currentRoot.rootId,
                                    ),
                                    onCommittedMutation = { mediaDirty = true },
                                )
                            ) {
                                SourceScanAttempt.Stale -> {
                                    restartPlan = true
                                    break
                                }
                                is SourceScanAttempt.Completed -> {
                                    val result = attempt.result
                                    when (val outcome = result.outcome) {
                                        ScanOutcome.Healthy -> Unit
                                        is ScanOutcome.Failed -> when (outcome.kind) {
                                            SourceFailureKind.CONFIRMED_MISSING -> {
                                                val removed = sourceRootMutations.commitRootRemoval(
                                                    currentRoot.rootUri,
                                                    attempt.sourceGeneration,
                                                ) {
                                                    runScanTransaction {
                                                        val touchedLogicalIds =
                                                            gameIdentityDao?.logicalGameIdsTouchingRoot(
                                                                currentRoot.rootUri,
                                                            ).orEmpty()
                                                        gameDao.prepareSourceForRescan(currentRoot.rootUri)
                                                        pruneStaleEntries(
                                                            currentRoot.rootUri,
                                                            emptySet(),
                                                        )
                                                        gameIdentityDao?.reconcileCurrentWinners(
                                                            logicalGameIds = touchedLogicalIds,
                                                            nowMillis = clock(),
                                                        )
                                                        sourceRootDao.remove(currentRoot.rootId)
                                                        settleReconcileRoot(
                                                            context = ReconcileCommitContext(
                                                                runId,
                                                                currentRoot.rootId,
                                                            ),
                                                            outcome = OUTCOME_REMOVED,
                                                            result = result,
                                                            rootSeenMillis = null,
                                                        )
                                                        mediaDirty = true
                                                    }
                                                }
                                                if (!removed) {
                                                    restartPlan = true
                                                    break
                                                }
                                            }
                                            SourceFailureKind.UNAVAILABLE,
                                            SourceFailureKind.FAILED,
                                            -> {
                                                val settled =
                                                    settleNonMutatingReconcileOutcomeIfUnchanged(
                                                        runId = runId,
                                                        rootId = currentRoot.rootId,
                                                        sourceGeneration = attempt.sourceGeneration,
                                                        outcome = if (
                                                            outcome.kind ==
                                                            SourceFailureKind.UNAVAILABLE
                                                        ) {
                                                            OUTCOME_UNAVAILABLE
                                                        } else {
                                                            OUTCOME_FAILED
                                                        },
                                                        result = result,
                                                    )
                                                if (!settled) {
                                                    restartPlan = true
                                                    break
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (cancelled: CancellationException) {
                            // A returning cancellation is not a non-returning/crash attempt. Reset only
                            // the advisory phase; a committed SETTLED root remains settled.
                            withContext(NonCancellable) {
                                runScanTransaction {
                                    val latest = sourceReconcileDao.rootsForRun(runId)
                                        .firstOrNull { it.rootId == currentRoot.rootId }
                                    if (latest?.phase == ROOT_RUNNING) {
                                        sourceReconcileDao.upsertRoot(
                                            latest.copy(phase = ROOT_PENDING),
                                        )
                                    }
                                }
                            }
                            throw cancelled
                        } catch (error: Exception) {
                            // Returned infrastructure faults preserve the source. Bind the failure receipt
                            // to fresh source truth; concurrent source change restarts instead of settling
                            // a stale outcome. Finish this recovery non-cancellably because a cancellation
                            // thrown from inside a sibling catch would otherwise leave RUNNING behind.
                            val settled = withContext(NonCancellable) {
                                val snapshot = sourceRootMutations.snapshotSourceMutation {
                                    sourceRootDao.allOnce()
                                        .firstOrNull { it.rootId == currentRoot.rootId }
                                }
                                snapshot.value != null &&
                                    sourceRootMutations.commitIfSourceUnchanged(snapshot.generation) {
                                        runScanTransaction {
                                            settleReconcileRoot(
                                                context = ReconcileCommitContext(
                                                    runId,
                                                    currentRoot.rootId,
                                                ),
                                                outcome = OUTCOME_FAILED,
                                                result = emptyFailedScan(
                                                    SourceFailureKind.FAILED,
                                                    error.message ?: "Source reconciliation failed",
                                                ),
                                                rootSeenMillis = snapshot.value?.lastSeenMillis,
                                            )
                                        }
                                    }
                            }
                            if (!settled) {
                                restartPlan = true
                                break
                            }
                            currentCoroutineContext().ensureActive()
                        }
                    }

                    if (restartPlan) {
                        // Stale work is never a crash attempt. Clear its RUNNING row even if user
                        // cancellation arrived while the stale decision was being returned.
                        withContext(NonCancellable) {
                            invalidateReconcilePlan()
                        }
                        currentCoroutineContext().ensureActive()
                        continue@passLoop
                    }

                    val finalRun = finalizeReconcilePlan(plan) ?: continue@passLoop
                    return@withContext finalRun.toReceipt(
                        resumed = plan.resumed,
                        skippedAlreadyReconciled = plan.skippedAlreadySettled,
                    )
                }
                error("Reconciliation pass loop terminated without a receipt")
            }
        } finally {
            // Cancellation may arrive after earlier roots committed. Keep the surviving process's media
            // probes coherent even when the aggregate pass does not return normally.
            if (mediaDirty) refreshAllMedia()
        }
    }

    override suspend fun removeAdditionalSource(rootUri: String): SourceRemovalResult = serializedScan {
        withContext(ioDispatcher) {
            repeat(3) {
                val snapshot = sourceRootMutations.snapshotSourceMutation {
                    sourceRootDao.allOnce().firstOrNull { it.rootUri == rootUri }
                }
                val root = snapshot.value ?: return@withContext SourceRemovalResult.NotFound
                if (root.isPrimarySource) return@withContext SourceRemovalResult.PrimaryRejected
                val committed = sourceRootMutations.commitRootRemoval(
                    rootUri = rootUri,
                    expectedGeneration = snapshot.generation,
                ) {
                    runScanTransaction {
                        // A standalone owner mutation invalidates any frozen reconciliation plan.
                        sourceReconcileDao?.clearAll()
                        val touchedLogicalIds =
                            gameIdentityDao?.logicalGameIdsTouchingRoot(rootUri).orEmpty()
                        gameDao.prepareSourceForRescan(rootUri)
                        pruneStaleEntries(rootUri, emptySet())
                        gameIdentityDao?.reconcileCurrentWinners(
                            logicalGameIds = touchedLogicalIds,
                            nowMillis = clock(),
                        )
                        sourceRootDao.remove(root.rootId)
                        normalizePrimarySources()
                    }
                }
                if (committed) {
                    refreshAllMedia()
                    return@withContext SourceRemovalResult.Removed(rootUri)
                }
            }
            SourceRemovalResult.Failed("Source changed while removal was being committed")
        }
    }

    private suspend fun settleNonMutatingReconcileOutcomeIfUnchanged(
        runId: String,
        rootId: String,
        sourceGeneration: SourceMutationGeneration,
        outcome: String,
        result: ScanResult,
    ): Boolean = sourceRootMutations.commitIfSourceUnchanged(sourceGeneration) {
        runScanTransaction {
            val liveRoot = sourceRootDao.allOnce().firstOrNull { it.rootId == rootId }
            check(liveRoot != null) {
                "Source $rootId disappeared while settling a non-mutating reconciliation outcome"
            }
            settleReconcileRoot(
                context = ReconcileCommitContext(runId, rootId),
                outcome = outcome,
                result = result,
                rootSeenMillis = liveRoot.lastSeenMillis,
            )
        }
    }

    private suspend fun invalidateReconcilePlan() {
        sourceRootMutations.commitIndexMutation {
            runScanTransaction {
                sourceReconcileDao?.clearAll()
            }
        }
    }

    /**
     * Revalidates the complete frozen plan under the source-mutation authority before publishing its
     * receipt. A changed settled incarnation abandons the ledger and forces a fresh pass; unplanned roots
     * remain outside this frozen pass but are counted as drift.
     */
    private suspend fun finalizeReconcilePlan(
        plan: ReconcilePlan,
    ): SourceReconcileRunEntity? {
        val dao = requireNotNull(sourceReconcileDao)
        var completedRun: SourceReconcileRunEntity? = null
        val finalizeMutation: suspend () -> Unit = {
            runScanTransaction {
                val run = requireNotNull(dao.activeRun()) {
                    "Active reconciliation run ${plan.run.runId} disappeared before finalization"
                }
                check(run.runId == plan.run.runId) {
                    "Reconciliation run changed from ${plan.run.runId} to ${run.runId}"
                }
                val roots = dao.rootsForRun(run.runId)
                check(roots.all { it.phase == ROOT_SETTLED }) {
                    "Refusing to finalize reconciliation with unsettled roots"
                }
                val liveRoots = sourceRootDao.allOnce()
                val liveById = liveRoots.associateBy(SourceRootEntity::rootId)
                val invalidWitness = roots.any { root ->
                    val live = liveById[root.rootId]
                    when (root.outcome) {
                        OUTCOME_HEALTHY,
                        OUTCOME_UNAVAILABLE,
                        OUTCOME_FAILED,
                        OUTCOME_QUARANTINED,
                        -> live == null || root.rootSeenMillis != live.lastSeenMillis
                        OUTCOME_REMOVED,
                        OUTCOME_DRIFTED,
                        -> live != null
                        else -> true
                    }
                }
                if (invalidWitness) {
                    dao.clearAll()
                    return@runScanTransaction
                }

                val plannedIds = roots.map(SourceReconcileRootEntity::rootId).toSet()
                val addedAfterFreeze = liveById.keys - plannedIds
                val driftedPlanned = roots.count { it.outcome == OUTCOME_DRIFTED }
                val driftedSources = driftedPlanned + addedAfterFreeze.size
                val updatedRun = run.copy(
                    resumedWithDrift = run.resumedWithDrift || driftedSources > 0,
                    driftedSources = driftedSources,
                )
                dao.upsertRun(updatedRun)
                // One terminal normalization makes primary state independent of crash position.
                // If the previous primary was removed, normalizePrimarySources deliberately elects no
                // replacement so Add Games can ask for a new primary.
                normalizePrimarySources()
                dao.clearRun(run.runId)
                completedRun = updatedRun
            }
        }

        // Every terminal pass uses the source-mutation gate because primary-role normalization writes the
        // source registry even when no root was added or removed. The revision advance happens before Room
        // work and publishes in the coordinator's finally, remaining explicitly best-effort across process
        // death (no outbox, no exactly-once claim).
        sourceRootMutations.commitSourceMutation(finalizeMutation)
        return completedRun
    }

    /**
     * Pre-v10 fallback retained only for narrow unit fakes that deliberately omit [sourceReconcileDao].
     * Production always receives the v10 DAO from [LibraryStore].
     */
    private suspend fun reconcileSourcesWithoutLedger(): SourceReconcileResult =
        withContext(ioDispatcher) {
            val roots = sourceRootDao.allOnce().sortedWith(
                compareByDescending<SourceRootEntity> { it.isPrimarySource }
                    .thenBy { it.friendlyLabel.lowercase() }
                    .thenBy(SourceRootEntity::rootUri),
            )
            var healthySources = 0
            var removedSources = 0
            var unavailableSources = 0
            var failedSources = 0
            var filesSeen = 0
            var recognized = 0
            var groups = 0
            var skipped = 0
            for (root in roots) {
                while (true) {
                    val attempt = scanSource(root.rootUri, LibrarySourceRole.PRESERVE)
                    if (attempt == SourceScanAttempt.Stale) continue
                    val completed = attempt as SourceScanAttempt.Completed
                    val result = completed.result
                    var retry = false
                    filesSeen += result.filesSeen
                    recognized += result.recognized
                    groups += result.groups
                    skipped += result.skipped
                    when (val outcome = result.outcome) {
                        ScanOutcome.Healthy -> healthySources += 1
                        is ScanOutcome.Failed -> when (outcome.kind) {
                            SourceFailureKind.CONFIRMED_MISSING -> {
                                // Positive absence proof removes the exact root through one durable
                                // transaction, but only for the source incarnation that was walked.
                                val removed = sourceRootMutations.commitRootRemoval(
                                    root.rootUri,
                                    completed.sourceGeneration,
                                ) {
                                    runScanTransaction {
                                        val touchedLogicalIds =
                                            gameIdentityDao?.logicalGameIdsTouchingRoot(
                                                root.rootUri,
                                            ).orEmpty()
                                        gameDao.prepareSourceForRescan(root.rootUri)
                                        pruneStaleEntries(root.rootUri, emptySet())
                                        gameIdentityDao?.reconcileCurrentWinners(
                                            logicalGameIds = touchedLogicalIds,
                                            nowMillis = clock(),
                                        )
                                        sourceRootDao.remove(root.rootId)
                                        normalizePrimarySources()
                                    }
                                }
                                if (removed) removedSources += 1 else retry = true
                            }
                            SourceFailureKind.UNAVAILABLE -> unavailableSources += 1
                            SourceFailureKind.FAILED -> failedSources += 1
                        }
                    }
                    if (!retry) break
                }
            }
            if (healthySources > 0 || removedSources > 0) refreshAllMedia()
            SourceReconcileResult(
                sourcesChecked = roots.size,
                healthySources = healthySources,
                removedSources = removedSources,
                filesSeen = filesSeen,
                recognized = recognized,
                groups = groups,
                skipped = skipped,
                unavailableSources = unavailableSources,
                failedSources = failedSources,
            )
        }

    private suspend fun prepareReconcilePlan(): ReconcilePlan {
        val dao = requireNotNull(sourceReconcileDao)
        var prepared: ReconcilePlan? = null
        sourceRootMutations.commitIndexMutation {
            runScanTransaction {
                val liveRoots = sourceRootDao.allOnce().sortedWith(
                    compareByDescending<SourceRootEntity> { it.isPrimarySource }
                        .thenBy(SourceRootEntity::rootUri),
                )
                val existing = dao.activeRun()
                val existingRoots = existing?.let { dao.rootsForRun(it.runId) }.orEmpty()
                val invalidPolicy = existing != null &&
                    existing.policyStamp != RECONCILE_POLICY_STAMP
                val invalidLedger = existing != null && (
                    existingRoots.size != existing.sourcesChecked ||
                        existingRoots.map(SourceReconcileRootEntity::ordinal).distinct().size !=
                        existingRoots.size
                    )
                val invalidWitness = existing != null && existingRoots.any { root ->
                    if (root.phase != ROOT_SETTLED) return@any false
                    val live = liveRoots.firstOrNull { it.rootId == root.rootId }
                    when (root.outcome) {
                        OUTCOME_REMOVED, OUTCOME_DRIFTED -> live != null
                        OUTCOME_HEALTHY,
                        OUTCOME_UNAVAILABLE,
                        OUTCOME_FAILED,
                        OUTCOME_QUARANTINED,
                        ->
                            live == null || root.rootSeenMillis != live.lastSeenMillis
                        else -> false
                    }
                }

                if (existing == null || invalidPolicy || invalidLedger || invalidWitness) {
                    dao.clearAll()
                    prepared = createFreshReconcilePlan(dao, liveRoots)
                    return@runScanTransaction
                }

                val plannedIds = existingRoots.map(SourceReconcileRootEntity::rootId).toSet()
                val liveIds = liveRoots.map(SourceRootEntity::rootId).toSet()
                val addedAfterFreeze = liveIds - plannedIds
                val missingPending = existingRoots.count {
                    it.phase != ROOT_SETTLED && it.rootId !in liveIds
                }
                val settledDrift = existingRoots.count { it.outcome == OUTCOME_DRIFTED }
                val driftCount = settledDrift + addedAfterFreeze.size + missingPending
                var resumedRun = existing.copy(
                    ownerToken = reconcileOwnerToken,
                    resumeCount = existing.resumeCount + 1,
                    resumedWithDrift = existing.resumedWithDrift || driftCount > 0,
                    // Missing pending roots are counted when their rows settle below. Extras are not in
                    // the frozen plan, so account for them here without compounding across resumes.
                    driftedSources = settledDrift + addedAfterFreeze.size,
                )
                val resumedRoots = existingRoots.map { root ->
                    if (root.phase != ROOT_RUNNING) {
                        root
                    } else {
                        val attempts = root.attempts + 1
                        if (attempts >= MAX_NON_RETURNING_ATTEMPTS) {
                            resumedRun = resumedRun.copy(
                                failedSources = resumedRun.failedSources + 1,
                                quarantinedSources = resumedRun.quarantinedSources + 1,
                            )
                            root.copy(
                                phase = ROOT_SETTLED,
                                outcome = OUTCOME_QUARANTINED,
                                attempts = attempts,
                                rootSeenMillis = liveRoots
                                    .firstOrNull { it.rootId == root.rootId }
                                    ?.lastSeenMillis,
                                settledAtMillis = clock(),
                            )
                        } else {
                            root.copy(phase = ROOT_PENDING, attempts = attempts)
                        }
                    }
                }
                dao.upsertRun(resumedRun)
                dao.upsertRoots(resumedRoots)
                prepared = ReconcilePlan(
                    run = resumedRun,
                    roots = resumedRoots,
                    resumed = true,
                    skippedAlreadySettled = resumedRoots.count {
                        it.phase == ROOT_SETTLED && it.outcome != OUTCOME_QUARANTINED
                    },
                )
            }
        }
        return requireNotNull(prepared)
    }

    private suspend fun createFreshReconcilePlan(
        dao: SourceReconcileDao,
        roots: List<SourceRootEntity>,
    ): ReconcilePlan {
        val run = SourceReconcileRunEntity(
            runId = UUID.randomUUID().toString(),
            policyStamp = RECONCILE_POLICY_STAMP,
            ownerToken = reconcileOwnerToken,
            startedAtMillis = clock(),
            resumeCount = 0,
            sourcesChecked = roots.size,
            healthySources = 0,
            removedSources = 0,
            unavailableSources = 0,
            failedSources = 0,
            quarantinedSources = 0,
            filesSeen = 0,
            recognized = 0,
            groups = 0,
            skipped = 0,
            resumedWithDrift = false,
            driftedSources = 0,
        )
        val planned = roots.mapIndexed { ordinal, root ->
            SourceReconcileRootEntity(
                runId = run.runId,
                rootId = root.rootId,
                rootUri = root.rootUri,
                ordinal = ordinal,
                plannedPrimary = root.isPrimarySource,
                phase = ROOT_PENDING,
                outcome = null,
                attempts = 0,
                rootSeenMillis = null,
                settledAtMillis = null,
            )
        }
        dao.upsertRun(run)
        dao.upsertRoots(planned)
        return ReconcilePlan(run, planned, resumed = false, skippedAlreadySettled = 0)
    }

    private suspend fun settleReconcileRoot(
        context: ReconcileCommitContext,
        outcome: String,
        result: ScanResult,
        rootSeenMillis: Long?,
        countAsSourceFailure: Boolean = true,
        countAsDrift: Boolean = false,
    ) {
        val dao = requireNotNull(sourceReconcileDao)
        val run = requireNotNull(dao.activeRun()) {
            "No active reconciliation run while settling ${context.rootId}"
        }
        check(run.runId == context.runId) {
            "Refusing to settle root for stale reconciliation run ${context.runId}"
        }
        val root = requireNotNull(
            dao.rootsForRun(context.runId).firstOrNull { it.rootId == context.rootId },
        ) { "No reconciliation root ${context.rootId} in ${context.runId}" }
        if (root.phase == ROOT_SETTLED) return

        val failedIncrement = if (countAsSourceFailure && outcome == OUTCOME_FAILED) 1 else 0
        val updatedRun = run.copy(
            healthySources = run.healthySources + if (outcome == OUTCOME_HEALTHY) 1 else 0,
            removedSources = run.removedSources + if (outcome == OUTCOME_REMOVED) 1 else 0,
            unavailableSources = run.unavailableSources +
                if (outcome == OUTCOME_UNAVAILABLE) 1 else 0,
            failedSources = run.failedSources + failedIncrement,
            filesSeen = run.filesSeen + result.filesSeen,
            recognized = run.recognized + result.recognized,
            groups = run.groups + result.groups,
            skipped = run.skipped + result.skipped,
            // The reason breakdown accumulates exactly like the counts above it. Before v14 this map
            // stopped here and only `skipped` survived, so a Rescan All could say HOW MANY files were
            // dropped and never why.
            skippedByReason = encodeSkipReasons(
                mergeSkipReasons(decodeSkipReasons(run.skippedByReason), result.skippedByReason),
            ),
            resumedWithDrift = run.resumedWithDrift || countAsDrift,
            driftedSources = run.driftedSources + if (countAsDrift) 1 else 0,
        )
        dao.upsertRun(updatedRun)
        dao.upsertRoot(
            root.copy(
                phase = ROOT_SETTLED,
                outcome = outcome,
                rootSeenMillis = rootSeenMillis,
                settledAtMillis = clock(),
            ),
        )
    }

    private fun SourceReconcileRunEntity.toReceipt(
        resumed: Boolean,
        skippedAlreadyReconciled: Int,
    ) = SourceReconcileResult(
        skippedByReason = decodeSkipReasons(skippedByReason),
        sourcesChecked = sourcesChecked,
        healthySources = healthySources,
        removedSources = removedSources,
        filesSeen = filesSeen,
        recognized = recognized,
        groups = groups,
        skipped = skipped,
        unavailableSources = unavailableSources,
        failedSources = failedSources,
        resumedFromCrash = resumed,
        resumedWithDrift = resumedWithDrift,
        quarantinedSources = quarantinedSources,
        skippedAlreadyReconciled = skippedAlreadyReconciled,
        driftedSources = driftedSources,
    )

    private fun emptyFailedScan(kind: SourceFailureKind, reason: String) = ScanResult(
        filesSeen = 0,
        recognized = 0,
        groups = 0,
        skipped = 0,
        outcome = ScanOutcome.Failed(reason, kind),
    )

    /**
     * **The progress clear lives here, not in `scanSourceUntilStable`.**
     *
     * It was in `scanSourceUntilStable`'s `finally` when D1-R21 landed, which covers `rescan` and
     * `addSource` and nothing else. `reconcileSources` calls `scanSource` DIRECTLY, per root (`:909`
     * for the ledgered pass, `:1223` for the unledgered one), so a Rescan All finished with a
     * `ScanProgressState` row still on the channel and a `JobKind.SCAN` row still in
     * `JobProgressState` -- a bar outliving its job, which is exactly a stale progress indication. Found by review, 2026-08-30.
     *
     * `serializedScan` is the one function every scan entry point goes through -- `rescan`,
     * `addSource` and both `reconcileSources` paths -- so the clear belongs here and cannot be
     * forgotten by a future entry point that follows the same convention.
     */
    private suspend fun <T> serializedScan(block: suspend () -> T): T {
        processScanMutex.lock()
        return try {
            block()
        } finally {
            ScanProgressState.clear()
            processScanMutex.unlock()
        }
    }

    /**
     * Upsert every member of every group with its dedup facts. The primary is `isPrimaryMember = 1`
     * (the shelf tile); losers are `isPrimaryMember = 0` (folded away by the visibility queries).
     */
    private suspend fun persistGroups(groups: List<GameGroup>): List<String> {
        val entities = ArrayList<GameEntity>()
        groups.forEach { group ->
            currentCoroutineContext().ensureActive()
            val groupId = group.gameId.value
            group.allMembers.forEach { member ->
                currentCoroutineContext().ensureActive()
                val isPrimary = member.entryKey == group.primary.entryKey
                entities += member.toEntity(
                    duplicateGroupId = if (group.isCollapsed) groupId else null,
                    isPrimaryMember = isPrimary,
                    groupKind = group.groupKind.takeIf { group.isCollapsed },
                )
            }
        }
        currentCoroutineContext().ensureActive()
        gameDao.upsertAll(entities)
        val logicalIds = mutableListOf<String>()
        groups.forEach { group ->
            val physicalGameIds = group.allMembers
                .mapNotNull { it.identity?.gameId?.value }
                .distinct()
            if (physicalGameIds.size > 1) {
                gameIdentityDao?.reconcileScannedGroup(
                    winnerGameId = group.gameId.value,
                    memberGameIds = physicalGameIds,
                    groupKind = group.groupKind.name,
                    nowMillis = clock(),
                )?.logicalGameId?.let(logicalIds::add)
            }
        }

        // NOTE: dedup losers are NOT written to `hidden_items`. A loser is hidden purely by
        // `isPrimaryMember = 0` — the shelf/gamelist queries already filter on `isPrimaryMember = 1`,
        // so losers never surface. `hidden_items` is reserved for USER-dismiss and is keyed by the GROUP gameId; writing a loser hide
        // here would (wrongly) hide the group's PRIMARY too, since primary + losers share one gameId.
        // The scan therefore leaves user-hide state untouched — a rescan must never resurrect or bury a
        // user's dismiss decision.
        return logicalIds
    }

    /** Drop only this successfully walked root's missing rows through the exact-root, batched DAO seam. */
    private suspend fun pruneStaleEntries(rootUri: String, currentKeys: Set<String>) {
        gameDao.pruneEntriesMissingFromSource(rootUri, currentKeys)
    }

    /** Upsert a platform row per platform seen, with its dedup-collapsed visible game count. */
    private suspend fun persistPlatforms(groups: List<GameGroup>) {
        val countByPlatform = linkedMapOf<String, Int>()
        groups.forEach { group ->
            currentCoroutineContext().ensureActive()
            val platformId = group.primary.platformId.value
            countByPlatform[platformId] = (countByPlatform[platformId] ?: 0) + 1
        }
        val rows = ArrayList<PlatformEntity>(countByPlatform.size)
        countByPlatform.toSortedMap().forEach { (platformId, count) ->
            currentCoroutineContext().ensureActive()
            val existing = platformDao.byId(platformId)
            rows += PlatformEntity(
                platformId = platformId,
                displayName = existing?.displayName ?: PlatformLabels.labelFor(PlatformId(platformId)),
                gamesCount = count,
                requiresBios = existing?.requiresBios ?: false,
                catalogLongId = existing?.catalogLongId,
            )
        }
        currentCoroutineContext().ensureActive()
        platformDao.upsertAll(rows)
    }

    /** Upsert the source-root registry row with a FRIENDLY path (never the raw UUID/codename). */
    private suspend fun persistSource(
        rootUri: String,
        role: LibrarySourceRole,
        allowPrimaryElection: Boolean = true,
    ): Long {
        currentCoroutineContext().ensureActive()
        val isSaf = walker.isSafTree(rootUri)
        val current = sourceRootDao.allOnce()
        // Match on the DIRECTORY, not on the URI string. `rootId = rootUri`, so the two spellings of one
        // folder -- `content://…/tree/6A7F-FCFE%3AROMs` and `/storage/6A7F-FCFE/ROMs` -- used to register
        // as rival roots, and every file underneath was indexed twice. On the Odin that put a
        // phantom "2 versions" badge on 25 games, none of which had a variant.
        //
        // Exact match first so an unchanged re-registration costs nothing.
        val existing = current.firstOrNull { it.rootUri == rootUri }
            ?: current.firstOrNull { SourceRootIdentity.namesSameDirectory(it.rootUri, rootUri) }
        val shouldBePrimary = if (!allowPrimaryElection) {
            existing?.isPrimarySource ?: false
        } else {
            when (role) {
                LibrarySourceRole.PRIMARY -> true
                LibrarySourceRole.ADDITIONAL -> existing?.isPrimarySource == true
                LibrarySourceRole.PRESERVE -> existing?.isPrimarySource
                    ?: current.none(SourceRootEntity::isPrimarySource)
            }
        }
        val seenAtMillis = clock()
        // When [existing] was matched by DIRECTORY rather than by string, this registration is a second
        // spelling of a folder already registered. Keep the row that is already there -- its `rootId`,
        // its `rootUri`, and its `locationKind` -- and refresh only `lastSeenMillis`.
        //
        // Writing the incoming values here instead would be worse than the bug it fixes: the row's
        // `rootUri` is embedded in every `entryKey` beneath it, so rewriting it would orphan every game
        // under that root, and taking `locationKind` from the caller would silently flip a MANAGED_PATH
        // root to SAF_TREE_GRANT the first time a SAF rescan touched it. Re-keying is the alias
        // subsystem's job and is deliberately not attempted here.
        val sameDirectoryAsExisting = existing != null && existing.rootUri != rootUri
        sourceRootDao.upsert(
            SourceRootEntity(
                rootId = if (sameDirectoryAsExisting) existing.rootId else rootUri,
                rootUri = if (sameDirectoryAsExisting) existing.rootUri else rootUri,
                locationKind = if (sameDirectoryAsExisting) {
                    existing.locationKind
                } else if (isSaf) {
                    "SAF_TREE_GRANT"
                } else {
                    "MANAGED_PATH"
                },
                friendlyLabel = if (sameDirectoryAsExisting) {
                    existing.friendlyLabel
                } else {
                    FriendlyPath.of(rootUri)
                },
                isPrimarySource = shouldBePrimary,
                lastSeenMillis = seenAtMillis,
            ),
        )
        if (allowPrimaryElection) {
            normalizePrimarySources(rootUri.takeIf { role == LibrarySourceRole.PRIMARY })
        }
        return seenAtMillis
    }

    /**
     * Collapse duplicate legacy primaries deterministically, or honor an explicit replacement.
     * A missing primary stays missing so Add Games can truthfully route through primary selection.
     */
    private suspend fun normalizePrimarySources(preferredRootUri: String? = null) {
        val roots = sourceRootDao.allOnce().sortedWith(
            compareBy<SourceRootEntity>({ it.friendlyLabel.lowercase() }, SourceRootEntity::rootUri),
        )
        if (roots.isEmpty()) return
        val selected = roots.firstOrNull { it.rootUri == preferredRootUri }
            ?: roots.filter(SourceRootEntity::isPrimarySource).firstOrNull()
            ?: return
        roots.filter { it.isPrimarySource != (it.rootId == selected.rootId) }.forEach { root ->
            currentCoroutineContext().ensureActive()
            sourceRootDao.upsert(root.copy(isPrimarySource = root.rootId == selected.rootId))
        }
    }

    // ── User state (pure frontend markers) ────────────────────────────────────

    override suspend fun setFavorite(gameId: GameId, favorite: Boolean) = withContext(ioDispatcher) {
        runScanTransaction {
            val currentGameId = resolveCurrentGameId(gameId.value)
            if (favorite) {
                favoriteDao.set(FavoriteEntity(gameId = currentGameId, favoritedAtMillis = clock()))
            } else {
                favoriteDao.clear(currentGameId)
            }
        }
    }

    override suspend fun promoteFavoriteIfAbsent(
        gameId: GameId,
        favoritedAtMillis: Long,
    ): Boolean = withContext(ioDispatcher) {
        var promoted = false
        runScanTransaction {
            val currentGameId = resolveCurrentGameId(gameId.value)
            val inserted = favoriteDao.promoteGroupIfAbsent(currentGameId, favoritedAtMillis) > 0
            promoted = inserted || favoriteDao.isFavorite(currentGameId)
        }
        promoted
    }

    /**
     * One transaction and one write, the hidden row alone, so a list observing the library re-emits once. The favourite
     * row is never touched: the star waits under the hidden filter every list and count applies, and a restore brings the
     * game back with it (see [LibraryRepository.setHidden]).
     */
    override suspend fun setHidden(gameId: GameId, hidden: Boolean) = withContext(ioDispatcher) {
        runScanTransaction {
            val currentGameId = resolveCurrentGameId(gameId.value)
            if (hidden) {
                hideGroup(currentGameId)
            } else {
                hiddenItemDao.clear(currentGameId)
            }
        }
    }

    /**
     * Hide the whole GROUP (keyed by the group gameId). isPrimaryMember = true so the restore surface (`restorable`)
     * lists it; carry the group id for parity with the loser-exclusion rule. Runs inside the caller's transaction.
     */
    private suspend fun hideGroup(currentGameId: String) {
        val primary = gameDao.membersOfGame(currentGameId).firstOrNull()
        hiddenItemDao.upsert(
            HiddenItemEntity(
                gameId = currentGameId,
                hidden = true,
                reason = "USER_DISMISSED",
                duplicateGroupId = primary?.duplicateGroupId,
                isPrimaryMember = true,
                hiddenAtMillis = clock(),
            ),
        )
    }

    override suspend fun hasGame(gameId: GameId): Boolean = withContext(ioDispatcher) {
        gameDao.membersOfGame(resolveCurrentGameId(gameId.value)).isNotEmpty()
    }

    override suspend fun setTitleOverride(gameId: GameId, title: String) = withContext(ioDispatcher) {
        requireNotNull(gameIdentityDao) {
            "setTitleOverride requires a wired GameIdentityDao; LibraryStore always supplies one in production"
        }.setTitleOverrideForCurrent(gameId.value, title, clock())
    }

    override suspend fun clearTitleOverride(gameId: GameId) = withContext(ioDispatcher) {
        requireNotNull(gameIdentityDao) {
            "clearTitleOverride requires a wired GameIdentityDao; LibraryStore always supplies one in production"
        }.clearTitleOverrideForCurrent(gameId.value)
        Unit
    }

    override suspend fun refreshArt(gameId: GameId) {
        // Evict the memoized probes for this game's ROM so the projection re-hits storage and finds newly
        // written cover/logo siblings, THEN bump the shared version so [observeGames] re-emits both URIs.
        val currentGameId = withContext(ioDispatcher) {
            val currentGameId = resolveCurrentGameId(gameId.value)
            val member = gameDao.membersOfGame(currentGameId).firstOrNull()
            artSource.invalidate(member?.sourceDocumentUri, member?.managedPath)
            currentGameId
        }
        artVersion.update { v ->
            v.copy(perGame = v.perGame + (currentGameId to ((v.perGame[currentGameId] ?: 0) + 1)))
        }
    }

    private fun currentAliasMapFlow(): Flow<Map<String, String>> =
        gameIdentityDao?.observeCurrentGameAliases()
            ?.map { rows -> rows.associate { it.oldGameId to it.currentGameId } }
            ?: flowOf(emptyMap())

    private suspend fun resolveCurrentGameId(gameId: String): String =
        gameIdentityDao?.resolveCurrentGameId(gameId) ?: gameId

    override fun refreshAllMedia() {
        // Evict EVERY memoized probe first (the caches memoize misses, so a stale "known-absent" would
        // survive a bare bump), THEN bump the library-wide epoch so every combined row flow re-projects.
        // In production [artSource] and [previewVideoSource] are one LocalArtSource, so this evicts the
        // video probes too (the same shared-instance idiom [refreshArt] relies on).
        try {
            artSource.invalidateAll()
        } catch (_: Exception) {
            // Persistence has already committed when scan callers reach this hook. A cache-maintenance
            // failure cannot turn a complete durable snapshot into an ambiguous scan exception.
        }
        artVersion.update { it.copy(batchEpoch = it.batchEpoch + 1) }
    }

    override fun refreshShelfFaces() = artVersion.update { it.copy(faceEpoch = it.faceEpoch + 1) }

    override fun refreshMediaFor(changed: Collection<ChangedMedia>) {
        if (changed.isEmpty()) return
        // the batch loop already held every changed game's locator, so this needs NO DB re-query
        // and NO suspension (the `finally` contract — a cancelled batch still surfaces partial writes).
        // Evict exactly the changed locators (in production [artSource] is the one shared LocalArtSource,
        // so cover+logo+video probes all drop), then bump every changed game's version in ONE update —
        // a single re-emission re-projects the open flows, unchanged/artless games stay memoized, and the
        // `#mv=` fragment busts Coil for art REPLACED at the same path (which the library-wide batch
        // epoch never re-keyed — the stale-replaced-cover bug).
        changed.forEach { artSource.invalidate(it.sourceDocumentUri, it.managedPath) }
        artVersion.update { v ->
            val bumped = v.perGame.toMutableMap()
            changed.forEach { c -> bumped[c.gameId.value] = (bumped[c.gameId.value] ?: 0) + 1 }
            v.copy(perGame = bumped)
        }
    }

    // ── mapping ───────────────────────────────────────────────────────────────

    private fun toGameRow(p: GameRowProjection, artVersions: MediaVersions = MediaVersions()): GameRow {
        // p.title is COALESCE(canonicalTitle, title): a DAT match brings the raw No-Intro/Redump name
        // (verbose region/language tags), an un-matched game a filename fallback (maybe smashed camelCase).
        // Tidy for DISPLAY only; the DB keeps the raw title for matching/dedup/cover-keying.
        val duplicateKind = p.duplicateGroupKind?.let { name ->
            DuplicateGroupKind.entries.firstOrNull { it.name == name }
        }?.takeIf { p.memberCount > 1 }
        // a folded multi-disc GROUP reads as ONE game — its
        // row title drops the primary member's disc tag ("Chrono Cross (Disc 2)" → "Chrono Cross"). A LONE
        // disc file (no group) keeps the tag: it honestly says which disc the user has.
        // Provider canonical naming is explicit-enrichment presentation truth only. gameId, collapse, and
        // launch remain grounded in the offline scan; a fuzzy network title can never regroup the library.
        // p.manualTitleOverride  is the same category one level higher: presentation
        // only, outranks both, and is stored in title_overrides — never games.title/canonicalTitle — so it
        // is immune to the next re-scan (GameDao.upsertAll) and the next re-scrape (LibraryIdentityEnricher).
        val display = libraryDisplayTitle(
            scanTitle = p.title,
            providerCanonicalTitle = p.providerCanonicalTitle,
            duplicateKind = duplicateKind,
            memberCount = p.memberCount,
            manualTitleOverride = p.manualTitleOverride,
        )
        val perGameVersion = artVersions.perGame[p.gameId]
        val mediaVersion = when {
            artVersions.batchEpoch == 0 -> perGameVersion?.toString()
            perGameVersion == null -> "b${artVersions.batchEpoch}"
            else -> "b${artVersions.batchEpoch}-g$perGameVersion"
        }
        val automaticCoverUri = localRenderUri(artSource.artUriFor(p.sourceDocumentUri, p.managedPath), mediaVersion)
        return GameRow(
            gameId = GameId(p.gameId),
            title = display,
            platformId = PlatformId(p.platformId),
            memberCount = p.memberCount,
            duplicateKind = duplicateKind,
            discCount = p.discCount,
            isFavorite = p.isFavorite == 1,
            hasEmulatorOverride = p.hasOverride == 1,
            hasTitleOverride = p.manualTitleOverride != null,
            lastPlayedMillis = p.lastPlayedMillis,
            firstPlayedMillis = p.firstPlayedMillis,
            playCount = p.playCount,
            region = p.region,
            version = p.version,
            sizeBytes = p.sizeBytes,
            // Offline genre tag — resolved from the bundled map by the cleaned title. A miss = null.
            genre = genreSource.genreFor(display),
            // Offline LOCAL box-art -- a sibling image beside the primary ROM; a miss = null, the honest glyph. A per-game
            // rescrape ([refreshArt]) appends a `#mv=` fragment (decoder-ignored, but it re-keys Coil's cache). artUri is
            // the AUTOMATIC cover and nothing else: folding the pick in made the first Cover picker violate C4, so the
            // pick rides its own field and tier -- see [GameRow.wiredFace].
            artUri = automaticCoverUri,
            // The explicit pick with its SHAPE, on EVERY platform , and the automatic square only
            // when there is no cover. The store stamps its own `#v=`, so `localRenderUri` is skipped.
            wiredFace = coverOverrideSource.shelfFaceOverrideFor(p.gameId, hasAutomaticCover = !automaticCoverUri.isNullOrBlank()),
            // Whether the null above (if it is null) is a fact or an unanswered question.
            artResolutionSettled = artSource.artResolutionSettled(p.sourceDocumentUri, p.managedPath),
            // Offline MANAGED preview video (CP05) — media_art/<base>.mp4 only. A clip left beside the
            // ROM by another frontend is deliberately NOT adopted : a
            // backdrop that starts moving on its own is opt-in, and the opt-in is Customize Media.
            // A miss = null.
            previewVideoUri = localRenderUri(
                previewVideoSource.videoUriFor(p.sourceDocumentUri, p.managedPath),
                mediaVersion,
            ),
            // Offline LOCAL clear logo — the distinct <base>-logo image, sharing the cover refresh epoch.
            flatArtUri = localRenderUri(artSource.logoUriFor(p.sourceDocumentUri, p.managedPath), mediaVersion),
            // Offline LOCAL Hero — title-screen first, then fan art/screenshot at fetch time. This distinct
            // slot is the only Library backdrop source; logos and covers are never substituted into it.
            heroArtUri = localRenderUri(artSource.heroUriFor(p.sourceDocumentUri, p.managedPath), mediaVersion),
            // Real ScreenScraper support art is cached independently from the cover and uses the same
            // per-game revision so a newly enriched cartridge/disc appears without an app restart.
            physicalMediaUri = localRenderUri(
                artSource.physicalMediaUriFor(p.sourceDocumentUri, p.managedPath),
                mediaVersion,
            ),
            // The game's own sound for the launch animation: `media_art/<base>-audio.m4a` only, through [ArtSource]'s
            // audio probe and never through `previewVideoSource` above, so it cannot become a backdrop. Left WITHOUT the
            // `#mv=` fragment on purpose: nothing decodes it through Coil, Enrich never replaces it (create-only), and a
            // plain `file://` or `content://` is the least surprising thing to hand MediaPlayer. The same local-only gate
            // as every URI above, so a provider URL cannot reach a render model through this door either.
            launchAudioUri = localRenderUri(artSource.audioUriFor(p.sourceDocumentUri, p.managedPath)),
            // Enrichment is already cached in Room. Projection is read-only and must never cause a lookup.
            description = p.description,
            descriptionSource = p.descriptionSource,
            // The app's own record of how the last Enrich ended; no network, no Room.
            enrichAttempt = enrichAttemptSource.outcomeFor(p.gameId),
        )
    }
}

/**
 * Provider titles refine presentation only. They may not erase which physical disc a singleton row
 * represents; folded multi-disc groups remain disc-neutral as before.
 *
 * A manual [manualTitleOverride]  outranks both: it is what the player explicitly
 * typed, so it is shown VERBATIM (only whitespace-trimmed) — [DisplayTitle.clean]'s clutter-tag stripping,
 * camelCase splitting, and article normalization exist to tidy AUTOMATIC scan/provider strings, and running
 * them on a deliberate manual title risks silently rewriting the exact text the player chose. The
 * scan/provider disc-tag heuristics below, which exist only to arbitrate between those two automatic
 * sources, never run either.
 */
internal fun libraryDisplayTitle(
    scanTitle: String,
    providerCanonicalTitle: String?,
    duplicateKind: DuplicateGroupKind?,
    memberCount: Int,
    manualTitleOverride: String? = null,
): String {
    manualTitleOverride?.trim()?.takeIf(String::isNotBlank)?.let { return it }
    val scanDisplay = DisplayTitle.clean(scanTitle)
    val providerDisplay = providerCanonicalTitle?.takeIf(String::isNotBlank)?.let(DisplayTitle::clean)
    val chosen = if (
        memberCount <= 1 &&
        DisplayTitle.hasDiscTag(scanDisplay) &&
        providerDisplay != null &&
        !DisplayTitle.hasDiscTag(providerDisplay)
    ) {
        scanDisplay
    } else {
        providerDisplay ?: scanDisplay
    }
    return if (duplicateKind == DuplicateGroupKind.MULTI_DISC && memberCount > 1) {
        DisplayTitle.stripDiscTag(chosen)
    } else {
        chosen
    }
}

/** Only local file/content media reaches a render model; [version] changes the decoder cache key. */
private fun localRenderUri(uri: String?, version: String? = null): String? =
    uri
        ?.takeIf {
            it.startsWith("file://", ignoreCase = true) || it.startsWith("content://", ignoreCase = true)
        }
        ?.let { localUri -> version?.let { "$localUri#mv=$it" } ?: localUri }

/** Availability from the last-seen stamp: a root that a scan has reached is available/known. */
private fun SourceRootEntity.availableFromLastSeen(): Boolean = lastSeenMillis > 0

/**
 * `REASON=count` pairs joined by `;`. Reasons are `SkipReason.name` -- `[A-Z_]+` -- and counts are
 * non-negative integers, so neither can contain a separator and no escaping is needed. A malformed
 * or unparseable pair is DROPPED rather than guessed at: a receipt that invents a count is worse
 * than one that admits it lost a reason.
 */
internal fun encodeSkipReasons(byReason: Map<String, Int>): String =
    byReason.entries.joinToString(";") { (reason, count) -> "$reason=$count" }

internal fun decodeSkipReasons(encoded: String): Map<String, Int> {
    if (encoded.isBlank()) return emptyMap()
    val out = LinkedHashMap<String, Int>()
    encoded.split(';').forEach { pair ->
        val at = pair.indexOf('=')
        if (at <= 0) return@forEach
        val reason = pair.substring(0, at)
        val count = pair.substring(at + 1).toIntOrNull() ?: return@forEach
        if (count >= 0) out[reason] = (out[reason] ?: 0) + count
    }
    return out
}

internal fun mergeSkipReasons(left: Map<String, Int>, right: Map<String, Int>): Map<String, Int> {
    if (right.isEmpty()) return left
    val out = LinkedHashMap(left)
    right.forEach { (reason, count) -> out[reason] = (out[reason] ?: 0) + count }
    return out
}
