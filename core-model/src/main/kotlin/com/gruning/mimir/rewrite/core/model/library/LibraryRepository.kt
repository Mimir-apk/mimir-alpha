package com.gruning.mimir.rewrite.core.model.library

import com.gruning.mimir.rewrite.core.model.DuplicateGroupKind
import com.gruning.mimir.rewrite.core.model.EntryKey
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

/**
 * THE data-layer contract between the storage/resolver implementation (core-storage) and the
 * Compose UI (feature-library / feature-settings). Defined in the pure domain so both depend on it and
 * neither depends on the other (clean DI). Returns DEDUP-COLLAPSED, product-truth view rows — the UI never sees raw
 * per-file rows, only the grouped tiles.
 *
 * Local-only: `rootUri` is a SAF tree/document URI or file path; the implementation reads it IN PLACE
 * (never moves/copies) and never touches the network.
 */
interface LibraryRepository {
    /** Total dedup-collapsed game count + count of platforms that have >0 games. */
    fun observeSummary(): Flow<LibrarySummary>

    /** Platforms that HAVE games (gamesCount>0), for the shelf. */
    fun observePlatforms(): Flow<List<PlatformRow>>

    /** The dedup-collapsed games for one platform (one tile per group; losers hidden). */
    fun observeGames(platformId: PlatformId): Flow<List<GameRow>>

    /**
     * Available, non-hidden dedup-collapsed games for a typed ID batch. The production repository
     * overrides this with one reactive SQL query; the fallback keeps previews and narrow test fakes
     * source-compatible without exposing storage details.
     */
    fun observeGames(gameIds: Set<GameId>): Flow<List<GameRow>> {
        if (gameIds.isEmpty()) return flowOf(emptyList())
        return combine(gameIds.map(::observeGame)) { rows -> rows.filterNotNull() }
    }

    /** One dedup-collapsed game, or null when that group no longer exists. */
    fun observeGame(gameId: GameId): Flow<GameRow?>

    /**
     * Every visible group on every platform, in no particular platform order, carrying identity, title,
     * platform, art and favourite state -- what onboarding's favourites picker shows. The production
     * repository overrides this with one light query; this fallback keeps narrow fakes source-compatible.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeFavoriteCandidates(): Flow<List<GameRow>> = observePlatforms().flatMapLatest { platforms ->
        if (platforms.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(platforms.map { observeGames(it.platformId) }) { perPlatform -> perPlatform.toList().flatten() }
        }
    }

    /** Every physical member of a game group, in disc/display order. */
    fun observeGameMembers(gameId: GameId): Flow<List<GameMemberRow>>

    /** Every FAVORITED group across all platforms, most-recently-favorited first (the favorites-only view). */
    fun observeFavorites(): Flow<List<GameRow>>

    /**
     * Launched groups across all platforms, most recent first, capped at [limit] — the Home "Last Played"
     * context. The default keeps narrow fakes source-compatible: no launch history, empty context.
     */
    fun observeRecentlyPlayed(limit: Int): Flow<List<GameRow>> = flowOf(emptyList())

    /**
     * Launches per local calendar day for the last [LAUNCH_HISTORY_DAYS] days ending today, across every game: the
     * Activity page's weekly bars. Always exactly that many entries, OLDEST FIRST and today LAST, a day with no launches
     * present as 0 (see [launchesPerLocalDay], which owns the bucketing and the day rules).
     *
     * A launch is the same thing [GameRow.playCount] counts: a recorded `intent_sent`. It is not playtime. Summing every
     * game's playCount can come out lower than this week's total, because the bars also count native Android-game
     * launches (they have no [GameRow]).
     *
     * A launch of a game that is no longer in the library (its folder was removed) is NOT counted: the launch history and the
     * favourite are kept so adding the folder back restores them, but until then they are orphans, and Activity counts and lists
     * only games that exist (Most launched, Last played and Recently played already read from the games themselves).
     *
     * Days are cut in the device's time zone as of each emission. The flow re-emits when a launch is recorded, not
     * when the clock crosses midnight, so a surface that stays up past midnight with no launch must re-subscribe to
     * roll its window forward. The default keeps narrow fakes source-compatible: a quiet week.
     */
    fun observeLaunchesPerDay(): Flow<List<DailyLaunchCount>> = flow {
        emit(launchesPerLocalDay(emptyList(), System.currentTimeMillis(), ZoneId.systemDefault()))
    }

    /** The user's source folders with friendly paths + availability. */
    fun observeSources(): Flow<List<SourceRow>>

    /** How many games the identity enricher still has to resolve — > 0 drives the "Identifying…" chip. */
    fun observeResolvingCount(): Flow<Int>

    /** The USER-HIDDEN groups, for a restore surface (Settings › Library › Hidden). */
    fun observeHiddenGames(): Flow<List<GameRow>>

    /** Scan a source root in place (recognize → dedup → persist to Room). Returns what the pass saw. */
    suspend fun rescan(rootUri: String): ScanResult

    /** Add or replace a source with an explicit role; [PRESERVE] is reserved for known-root rescans. */
    suspend fun addSource(rootUri: String, role: LibrarySourceRole): ScanResult = rescan(rootUri)

    /**
     * Explicit user-authorized removal from Mimir's additional-source registry and index.
     * Implementations must reject the primary source and must never delete or move user files.
     */
    suspend fun removeAdditionalSource(rootUri: String): SourceRemovalResult =
        SourceRemovalResult.Unsupported

    /**
     * **A file the library knows has already been moved on disk.**
     *
     * The file manager can move a ROM, and until this existed nothing told the library — the row went on
     * naming a path with no file at it, so the game was unlaunchable until the next scan of that root,
     * which then **deleted** it (`GameDao.pruneEntriesMissingFromSource`) and re-added it from the new
     * location as a different `entryKey` on the scan after that. Favourites and play history survived
     * that (they key on the content-derived `gameId`), but the game vanished from the shelf in between,
     * and if the destination was not inside a source root it never came back.
     *
     * **The invariant, and it is the whole contract: never leave a row a fresh scan would contradict.**
     * An implementation may only rewrite the row when the scanner would reach the same identity at the
     * new location, and must otherwise reach the state the scanner would — which for a file gone from a
     * live root is deletion, not a new "moved away" flavour of unavailable.
     *
     * **Never touches the file.** Both paths are already true when this is called; this is bookkeeping
     * catching up with the filesystem, in that order, so a crash between them leaves a stale row the next
     * scan corrects rather than a moved file the library thinks is still home.
     *
     * @param fromPath the absolute path the file used to have.
     * @param toPath the absolute path it has now.
     */
    suspend fun relocate(fromPath: String, toPath: String): LibraryRelocation =
        LibraryRelocation.NotInLibrary

    /**
     * **The multi-disc set [path] belongs to, asked BEFORE the file is moved.** Warn first and offer to move all discs.
     *
     * [relocate] can only ever report a broken set after the fact, because it runs on completion — and an
     * after-the-fact report is too late: by the time the card says the set is broken, the
     * game is already unplayable and putting it right is the player's job. This is the same knowledge,
     * one press earlier, while refusing is still free.
     *
     * ## Non-null means a playlist will really be left behind
     *
     * Answers null for everything that does not actually break:
     *
     * - a file that shares its `gameId` with no other (the ordinary case);
     * - a **duplicate** group rather than a disc set. `GameDao.memberCount > 1` is famously both — the
     *   same distinction `LibraryRelocation.partOfSet` documents and deliberately conflates, because
     *   after the fact the caller only needs "the set may be broken". Before the fact the difference is
     *   the whole warning: "this is disc 2 of 3" said about two copies of one ROM is a lie.
     * - a multi-disc group with **no `.m3u` on disk**. Those are grouped by normalised title, not by a
     *   playlist (`DedupGrouper`), so moving one member to another folder in the same source breaks
     *   nothing — the group re-forms on title at the next scan. Warning there would train the player to
     *   dismiss the warning that matters.
     *
     * So a non-null answer is a specific, checked claim: **there is a playlist file, it names this file,
     * and moving this file alone leaves the playlist pointing at nothing.**
     *
     * @param path the absolute path of the file the player is about to move.
     */
    suspend fun discSetFor(path: String): LibraryDiscSet? = null

    /**
     * Explicit source reconciliation. Production may remove registry/index state only when the source
     * lister proves [SourceFailureKind.CONFIRMED_MISSING]. Transient provider, permission, storage, and
     * traversal failures remain registered and indexed. User files are never deleted or moved.
     *
     * The fallback keeps narrow fakes source-compatible and preserves every failed rescan, because a
     * free-form failure reason is never authority to forget a source.
     */
    suspend fun reconcileSources(): SourceReconcileResult {
        val roots = observeSources().first()
        var healthy = 0
        var unavailable = 0
        var failed = 0
        var filesSeen = 0
        var recognized = 0
        var groups = 0
        var skipped = 0
        // D1-R20, 2026-08-30: summed rather than dropped. Until now this loop carried four integers
        // out of a multi-root reconcile and discarded every root's reason breakdown, so a rescan-all
        // could only ever say HOW MANY files were skipped, never why -- and a surface reading the
        // last scan would have gone stale the moment the player rescanned everything.
        val skippedReasons = LinkedHashMap<String, Int>()
        roots.forEach { root ->
            val result = rescan(root.rootUri)
            when (result.outcome) {
                ScanOutcome.Healthy -> healthy += 1
                is ScanOutcome.Failed -> when (result.outcome.kind) {
                    SourceFailureKind.UNAVAILABLE -> unavailable += 1
                    SourceFailureKind.FAILED,
                    SourceFailureKind.CONFIRMED_MISSING -> failed += 1
                }
            }
            filesSeen += result.filesSeen
            recognized += result.recognized
            groups += result.groups
            skipped += result.skipped
            // Reason order is the recognizer's declaration order and is identical across roots, so
            // merging preserves it; the per-reason invariant (values sum to `skipped`) survives
            // summation because each root's own map already satisfies it.
            result.skippedByReason.forEach { (reason, count) ->
                skippedReasons[reason] = (skippedReasons[reason] ?: 0) + count
            }
        }
        return SourceReconcileResult(
            sourcesChecked = roots.size,
            healthySources = healthy,
            removedSources = 0,
            unavailableSources = unavailable,
            failedSources = failed,
            filesSeen = filesSeen,
            recognized = recognized,
            groups = groups,
            skipped = skipped,
            skippedByReason = skippedReasons,
        )
    }

    // ── User state (pure frontend markers — never touch the file, launch, or placement) ──

    /** Mark/unmark a group as a favorite . Reactive: the gamelist re-emits. */
    suspend fun setFavorite(gameId: GameId, favorite: Boolean)

    /**
     * Migration-only Favorite promotion. Production atomically resolves [gameId] to its primary group
     * and inserts only when no primary/member Favorite already exists, preserving every existing
     * Favorite timestamp. Returns true only when Favorite truth is secured by insertion or an existing
     * group/member Favorite. The default keeps narrow fakes source-compatible and fails closed at runtime;
     * production must override.
     */
    suspend fun promoteFavoriteIfAbsent(gameId: GameId, favoritedAtMillis: Long): Boolean {
        throw UnsupportedOperationException("Migration-safe Favorite promotion is not implemented")
    }

    /**
     * Hide a group from the shelf/gamelist without deleting anything , or
     * restore it when [hidden] is false.
     *
     * **A hide leaves the Favorite mark where it is.** Every list and count that reads favourites filters hidden groups
     * out, so a hidden favourite shows nowhere; restoring returns the game with its star. Previously
     * clearing the star in a separate write made it disappear before the row; clearing it in the same
     * transaction lost it permanently. A hide is now one write, so a list re-emits
     * once and the game leaves with its star still on it.
     */
    suspend fun setHidden(gameId: GameId, hidden: Boolean)

    /**
     * Manually correct a group's display title — cosmetic only, never fed into
     * recognition. Takes precedence over both the scan title and any provider canonical title, and is
     * immune to a later re-scan or re-scrape (stored separately from both). [title] is used verbatim as
     * typed; the caller (rename affordance) owns validation/trimming policy.
     */
    suspend fun setTitleOverride(gameId: GameId, title: String)

    /** "Reset to detected name" — clears the manual override; display falls back to provider/scan title. */
    suspend fun clearTitleOverride(gameId: GameId)

    /**
     * After a per-game rescrape wrote a NEW local cover next to the ROM, force the gamelist to repaint that
     * tile: evicts the memoized art probe and version-busts the tile's art URI (a `#`-fragment the image
     * decoder ignores but Coil's cache key does not), so the fresh cover paints in place — no relaunch.
     * A pure display refresh; never touches the ROM, launch, or placement. Default no-op for previews/tests.
     */
    suspend fun refreshArt(gameId: GameId) {}

    /**
     * After a WHOLE-LIBRARY event (a rescan-scale change to an unknown set of games), force every row
     * projection to re-probe: evicts ALL memoized media probes and re-emits the row flows. Media files
     * never touch the DB, so without a bump nothing re-emits and new files stay invisible until an app
     * restart (RP5 Bifröst-backdrop repro 2026-07-17). PERF-C: batch fetches know exactly which games they
     * wrote — they call [refreshMediaFor] instead; this stays the rare unknown-set fallback. A pure display
     * refresh; touches no files, rows, or user state. Default no-op for previews/tests.
     */
    fun refreshAllMedia() {}

    /**
     * targeted batch-completion refresh — evict ONLY the [changed] games' memoized
     * media probes and version-bump ONLY their rows, in ONE emission. Replaces the library-wide
     * [refreshAllMedia] on the fetch/apply path, which re-walked SAF for every game (including artless
     * ones re-memoizing their miss) — the reported post-fetch asset-resettlement churn. The per-game
     * version bump also busts Coil's `#mv=` key for art REPLACED at the same path (same-path media replacement), which the library-wide epoch never did — a real stale-bitmap bug.
     * NON-suspend by contract: batches call it from a `finally`, so a CANCELLED batch's partial writes
     * must surface without a suspension point. Default no-op for previews/tests.
     */
    fun refreshMediaFor(changed: Collection<ChangedMedia>) {}

    /**
     * Re-project every open row flow ONCE, reading each game's shelf face again (the explicit pick or automatic SteamGridDB tile), for a writer that put a tile file on disk and touched no Room table. Evicts no memoized probe and re-keys no
     * art (no `#mv=` change), so it costs one cheap re-map, never a re-query. Default no-op, like [refreshAllMedia].
     */
    fun refreshShelfFaces() {}

    /**
     * Does the library ALREADY contain this dedup-group id? The M10 migration importer probes with this to
     * re-attach a bundle's markers to games the user already has — no re-scan, no write.
     */
    suspend fun hasGame(gameId: GameId): Boolean
}

/**
 * One batch-changed game: its identity plus the primary-file locator the media probes key on — exactly
 * the values the fetch loop already holds when a write lands, so the refresh needs NO DB re-query.
 */
data class ChangedMedia(
    val gameId: GameId,
    val sourceDocumentUri: String?,
    val managedPath: String?,
)

/** Library counts for the summary chip ("N games · N platforms"). */
data class LibrarySummary(val gameCount: Int, val platformCount: Int)

/** A platform row for the shelf: only surfaced when [gameCount] > 0. */
data class PlatformRow(val platformId: PlatformId, val label: String, val gameCount: Int)

/**
 * One dedup-collapsed game tile.
 * @property memberCount how many files fold into this group (>1 = multi-disc / variants).
 * @property duplicateKind why it's a group (multi-disc / region / version), or null for a singleton.
 * @property discCount distinct physical disc ordinals; excludes playlists and split-track plumbing.
 * @property isFavorite the user favorited this group. Regular gamelists retain recency order; the
 *   dedicated favorites filter owns favorite-only presentation.
 * @property hasEmulatorOverride the user set an "Open with…" emulator override for this group.
 * @property hasTitleOverride the user manually renamed this group; [title] is that rename, not the
 *   scan/provider title. Gates a "reset to detected name" affordance.
 * @property lastPlayedMillis epoch millis of the most-recent successful launch, or null if never played.
 * @property firstPlayedMillis epoch millis of the EARLIEST successful launch on record, or null if never played. It is
 *   the oldest `intent_sent` row the same way [lastPlayedMillis] is the newest, so it can only be as old as the launch
 *   history that is still stored (see `core-storage` `launch_proofs`).
 * @property playCount number of launch intents successfully sent for this game group.
 * @property region offline-resolved region of the primary member, or null when unknown.
 * @property version offline-resolved version of the primary member, or null when unknown.
 * @property sizeBytes byte size of the primary physical member.
 * @property genre the offline-resolved genre for this title (from the bundled map via [GenreSource]), or
 *   null when the map has no entry — a quiet display tag, never load-bearing.
 * @property artUri a LOCAL box-art image URI (a sibling file beside the ROM, resolved offline via
 *   [ArtSource]), or null when none exists — then the tile shows the honest glyph. Never a network URL.
 * @property flatArtUri a distinct LOCAL clear-logo image URI (`<base>-logo.<image-ext>`, resolved offline
 *   via [ArtSource]), or null when none exists. Never a network URL.
 * @property heroArtUri a distinct LOCAL title-screen/fan-art Hero (`<base>-hero.<image-ext>`, resolved
 *   offline via [ArtSource]), or null. It is the only Library gamelist backdrop media and is never a logo.
 * @property physicalMediaUri a distinct LOCAL provider support image (`<base>-support.<image-ext>`) showing
 *   the cartridge/disc/card itself, or null. It is never substituted into [artUri].
 * @property description a cached provider description already stored on device, or null. Reading a row
 *   never performs a network request; only an explicit connected action or the bounded post-onboarding
 *   metadata starter may enrich it.
 * @property descriptionSource the cached description provider (`screenscraper` today), or null when this
 *   game has never produced a verified provider record. A non-null source with a null [description]
 *   distinguishes a verified provider record with no synopsis from a game that has not been looked up.
 */
data class GameRow(
    val gameId: GameId,
    val title: String,
    val platformId: PlatformId,
    val memberCount: Int,
    val duplicateKind: DuplicateGroupKind?,
    val discCount: Int = 0,
    val isFavorite: Boolean = false,
    val hasEmulatorOverride: Boolean = false,
    /** True when [title] is the player's manual rename, not the scan/provider title — gates a "reset to detected name" affordance. */
    val hasTitleOverride: Boolean = false,
    val lastPlayedMillis: Long? = null,
    val firstPlayedMillis: Long? = null,
    val genre: String? = null,
    val artUri: String? = null,
    /**
     * The explicitly selected Shelf-state face and its kind, or null when none is selected.
     *
     * Deliberately NOT folded into [artUri]. It is the top tier of the resolution ladder (S1) and must
     * outrank the provider's physical render, which [artUri] cannot express because the ladder reaches
     * physical first when Cartridge Hero is on. Keeping it separate is what lets a pick replace the state's media
     * regardless of the Cartridge Hero setting.
     */
    val wiredFace: WiredFace? = null,
    /**
     * False when this row's art could not be LOOKED UP — not when it was looked up and found missing.
     *
     * Exists so a surface can tell an established absence from an unanswered question. The runed book is a
     * claim (*this game has no art*), and a claim the product has not established is the one thing it may
     * not paint. Defaults true so every existing construction site, preview and test keeps its meaning.
     */
    val artResolutionSettled: Boolean = true,
    /** a LOCAL preview-video URI (a downloaded `<base>.mp4` sibling), or null. */
    val previewVideoUri: String? = null,
    val playCount: Int = 0,
    val region: String? = null,
    val version: String? = null,
    val sizeBytes: Long = 0L,
    val flatArtUri: String? = null,
    val physicalMediaUri: String? = null,
    val description: String? = null,
    val descriptionSource: String? = null,
    val heroArtUri: String? = null,
    /**
     * The game's own sound for the launch animation: a LOCAL `file://` or the library's own SAF `content://`
     * `<base>-audio.m4a` that Enrich kept beside the art, or null .
     *
     * Deliberately NOT [previewVideoUri]. That field also drives Home's moving backdrop, and this one must never be
     * able to: it is a sound, it has no picture, and nothing paints it. It is never a provider URL (those carry
     * credentials), which the row's projection enforces and the launch intro checks again before it logs or plays it.
     */
    val launchAudioUri: String? = null,
    /**
     * How the last per-game Enrich ended, or null when none has . A finished attempt hides Enrich for this game on
     * every door; a failed one keeps it. Read through [EnrichAttemptSource], never from Room.
     */
    val enrichAttempt: EnrichAttemptOutcome? = null,
)

/** One physical member behind a dedup-collapsed [GameRow]. */
data class GameMemberRow(
    val entryKey: EntryKey,
    /** 1-based disc number when known; 0 for non-disc members. */
    val discNumber: Int,
    val displayName: String,
    val sizeBytes: Long,
)

/** A source folder row (friendly path, count, availability — never the raw UUID/codename). */
data class SourceRow(
    val friendlyPath: String,
    val rootUri: String,
    val gameCount: Int,
    val available: Boolean,
    val isPrimary: Boolean = false,
)

enum class LibrarySourceRole { PRIMARY, ADDITIONAL, PRESERVE }

sealed interface SourceRemovalResult {
    data class Removed(val rootUri: String) : SourceRemovalResult
    data object NotFound : SourceRemovalResult
    data object PrimaryRejected : SourceRemovalResult
    data object Unsupported : SourceRemovalResult
    data class Failed(val reason: String) : SourceRemovalResult
}

/** Aggregate receipt for one explicit, sequential source reconciliation pass. */
data class SourceReconcileResult(
    val sourcesChecked: Int,
    val healthySources: Int,
    val removedSources: Int,
    val filesSeen: Int,
    val recognized: Int,
    val groups: Int,
    val skipped: Int,
    /**
     * Why the [skipped] files were dropped, summed across every root this pass touched: reason name →
     * count. Same contract as [ScanResult.skippedByReason] and the same invariant — the values sum to
     * exactly [skipped] — and the same privacy posture: **counts only, never a file name, path, or
     * title**, because the keys are `SkipReason.name`.
     *
     * Defaulted to empty so producers that do not tally
     * reasons stay honest rather than claiming a breakdown they never computed.
     */
    val skippedByReason: Map<String, Int> = emptyMap(),
    /** Roots preserved because they could not be traversed or proven absent. */
    val unavailableSources: Int = 0,
    /** Roots preserved after a traversal/protocol fault that was not classified as retryable unavailability. */
    val failedSources: Int = 0,
    /** The receipt includes work committed by a prior process/invocation of this same frozen pass. */
    val resumedFromCrash: Boolean = false,
    /** The live source registry changed after the pass froze its deterministic root plan. */
    val resumedWithDrift: Boolean = false,
    /** Roots skipped for this pass after repeatedly failing to return across process restarts. */
    val quarantinedSources: Int = 0,
    /** Settled roots whose co-committed receipts were reused instead of traversing again. */
    val skippedAlreadyReconciled: Int = 0,
    /** Planned roots removed or newly registered outside the frozen plan. */
    val driftedSources: Int = 0,
    /** Primary game ids that were absent before this healthy reconciliation and present after it committed. */
    val addedGameIds: Set<String> = emptySet(),
)

/** Whether a failed traversal is authoritative enough to remove only Mimir's registry/index state. */
enum class SourceFailureKind {
    /** Provider, grant, storage, I/O, or other traversal failure. Preserve every row and source. */
    UNAVAILABLE,

    /** A traversal/protocol fault. Preserve the source; the user may retry after the fault is corrected. */
    FAILED,

    /** An explicit presence oracle proved the root no longer exists. Never inferred from failure text. */
    CONFIRMED_MISSING,
}

/** The health of a scan pass. A failed traversal must never be treated as an empty source. */
sealed interface ScanOutcome {
    /** The root was fully traversed, including when it genuinely contained no files. */
    data object Healthy : ScanOutcome

    /**
     * The root could not be traversed; [reason] is diagnostic only and [kind] owns deletion authority.
     * Defaults to [SourceFailureKind.FAILED] so old/narrow producers fail safe without claiming that
     * the fault is known to be a transient availability condition.
     */
    data class Failed(
        val reason: String,
        val kind: SourceFailureKind = SourceFailureKind.FAILED,
    ) : ScanOutcome
}

/** The outcome of a scan pass (honest counts plus an explicit traversal health state). */
data class ScanResult(
    val filesSeen: Int,
    val recognized: Int,
    val groups: Int,
    val skipped: Int,
    val outcome: ScanOutcome = ScanOutcome.Healthy,
    /**
     * Why the [skipped] files were dropped: reason name → count, in the recognizer's declaration
     * order, omitting reasons that did not fire. Diagnostic only — nothing branches on it.
     *
     * Invariant: the values sum to exactly [skipped].
     *
     * Keys are `SkipReason.name` rather than the enum itself because the enum belongs to the scan
     * recognizer in `core-resolver`, which depends on this module and not the other way round. This
     * carries counts only — never a file name, path, or title, so it is safe to display or log.
     *
     * Defaulted to empty so producers that do not tally reasons (fakes, failure paths) stay honest
     * rather than claiming a breakdown they never computed.
     */
    val skippedByReason: Map<String, Int> = emptyMap(),
    /** Primary game ids that were absent before this healthy scan and present after it committed. */
    val addedGameIds: Set<String> = emptySet(),
)

/** The primary game ids introduced by one completed scan. */
fun addedGameIds(before: Set<String>, after: Set<String>): Set<String> = after - before

/**
 * What the library did about a moved file — [LibraryRepository.relocate]'s answer.
 *
 * **Three outcomes and no fourth, deliberately.** The row either followed the file, or reached the state
 * a scan would reach for a file that is no longer where the library indexed it, or was never indexed at
 * all. A "moved away, pending" flavour would be a state nothing else in the system can read: the scanner
 * has no such concept, so the next pass would resolve it by its own rules anyway, and the only thing the
 * extra state would buy is a window where the shelf shows a game that cannot launch.
 */
sealed interface LibraryRelocation {

    /**
     * The row followed the file. Same `gameId`, same favourites, same play history, new location.
     *
     * @param partOfSet this file shares its `gameId` with other files — a multi-disc set or a duplicate
     *   group. **The row is correct and the SET may not be**: an `.m3u` playlist names its discs by
     *   path on disk, and moving one member breaks the playlist itself, which no amount of bookkeeping
     *   here can repair. Reported so a caller can say so rather than discovering it at launch.
     */
    data class Relocated(val gameId: GameId, val partOfSet: Boolean) : LibraryRelocation

    /**
     * The row was removed, because that is what a scan of its source would now do.
     *
     * @param gameId what left, so a caller can name it.
     * @param reason why the row could not simply follow — see [LibraryDepartureReason].
     * @param partOfSet as [Relocated.partOfSet].
     */
    data class Departed(
        val gameId: GameId,
        val reason: LibraryDepartureReason,
        val partOfSet: Boolean,
    ) : LibraryRelocation

    /**
     * No row named that path. **The ordinary answer, not an error** — the file manager moves screenshots,
     * saves, BIOS images and archives, and none of them are library entries.
     */
    data object NotInLibrary : LibraryRelocation
}

/** Why a moved file could not keep its row. */
enum class LibraryDepartureReason {
    /**
     * The destination is not inside any registered source root, so the scanner will never see the file
     * again. The game leaves the library until the player adds that folder as a source.
     */
    OUTSIDE_SOURCES,

    /**
     * The scanner would name it differently at the new location — the platform its folder implies
     * changed, or the file arrived under a different name (a *Keep both* rename). `gameId` is derived
     * from content, filename and platform, so either of those makes this a different entry rather than
     * the same one somewhere else, and pretending otherwise would put a row in the index that the next
     * scan contradicts.
     */
    IDENTITY_CHANGED,
}

/**
 * A multi-disc set — [LibraryRepository.discSetFor]'s answer. **A playlist is optional.**
 *
 * Requiring a playlist caused a defect reported on device on 2026-08-18: GameCube sets made of
 * bare `(Disc 1)` / `(Disc 2)`
 * `.iso` pairs with no `.m3u` anywhere, so every one of them was discarded and the warning could never
 * fire. The scanner had them right — `DedupGrouper` groups on the disc token in the filename alone —
 * and this contract threw the answer away.
 *
 * The reasoning that shipped it was that a playlist-less group has no text file to break, so there is
 * nothing to repair. True about the filesystem, false about the player: the emulator asks for disc 2 and
 * it is in another folder now. Scope the warning to what the player loses, not to the mechanism that
 * breaks.
 *
 * **Every field is a fact about files that exist**, because this is shown to a player as a reason to
 * stop. Nothing here is inferred from a title.
 *
 * @param gameId the group all of these files share, so a caller can name what it is about to break.
 * @param title the game's display title, for the warning's own sentence.
 * @param subjectDiscNumber which disc the file being moved is, 1-based, or 0 when the library never
 *   numbered it (`M3uPlaylistResolver` numbers by playlist order, and only for files it resolved).
 * @param discPaths every disc in the set INCLUDING the subject, in disc order, as absolute paths. This
 *   is what "move all of them" moves. A member the library holds only as a SAF document is absent — see
 *   [carryable].
 * @param playlistPath the `.m3u` itself, absolute, or null when the library holds it only as a SAF
 *   document and this process cannot move it by path.
 * @param wholeSetTravelsIntact **nothing about a playlist stops "move them together" from repairing
 *   this set.** Three cases, and the name has to cover all three because keying the offer on
 *   `playlistPath != null` is what hid the SAF case:
 *   - **No playlist at all** — true. There is no text file to break, so moving the discs together always
 *     works. This is the ordinary GameCube/PS2 shape.
 *   - **A playlist of bare sibling filenames** — true. `M3uPlaylistResolver` resolves entries against the
 *     playlist's own parent, so bare siblings survive relocation and fixed paths do not.
 *   - **A playlist that cannot travel** — false. Either its entries are absolute (`/sd/…`, `file://`,
 *     `content://`) or reach into a subfolder, or the library holds it only as a SAF document and this
 *     process cannot move it by path at all. Both leave the set broken wherever the discs go.
 */
data class LibraryDiscSet(
    val gameId: GameId,
    val title: String,
    val subjectDiscNumber: Int,
    val discPaths: List<String>,
    val playlistPath: String?,
    /**
     * **Whether a playlist EXISTS, which is not the same question as whether we have its path.**
     *
     * [playlistPath] is null in two unrelated situations: the set genuinely has no `.m3u` (the ordinary
     * GameCube/PS2 shape), and the library holds one only as a SAF document this process cannot move by
     * path. The caller derived "has a playlist" from `playlistPath != null` and so told a player with a
     * SAF-held playlist that their set has none — describing the wrong failure on the one screen whose
     * whole job is to describe the failure accurately.
     */
    val hasPlaylist: Boolean,
    val wholeSetTravelsIntact: Boolean,
    /**
     * **Files that are not games but belong to a disc by name** -- the `.sbi` libcrypt subchannel file and the `.sub`
     * subchannel image that sit beside it with the same basename, for every disc of the set. The scan skips them on
     * purpose (they are not launchable), so no library row holds them and they are found on disk, beside each disc, when
     * the set is asked for.
     *
     * Kept apart from [discPaths] because that list is what the warning COUNTS ("disc 2 of 3") and what [carryable]
     * measures; a subchannel file is neither a disc nor a reason the set exists. Without these, "move all" took the
     * discs and left the `.sbi` behind, and DuckStation then refused a PAL libcrypt game with "no SBI file" (Cocoon
     * D#248). Absolute paths, only ever same-basename siblings of a disc in the disc's own folder.
     */
    val companionPaths: List<String> = emptyList(),
) {
    /**
     * Whether "move the whole set" is a thing this process can actually do and would actually help.
     *
     * **Fail-closed, and deliberately stricter than "we found a set".** Offering to move a set that
     * leaves its playlist behind, or whose playlist names fixed paths, would advertise a repair that
     * does not repair — the same wrong-answer-is-worse-than-no-answer rule the extract destination
     * already follows. When this is false the caller must still warn; it just has nothing better to
     * offer than *move it anyway* or *cancel*.
     *
     * **It no longer reads `playlistPath != null`.** That test conflated "this set has no playlist"
     * (where the co-move always works) with "this set has a playlist we cannot move" (where it never
     * does), and answered the same for both — refusing the first and, once the first was allowed
     * through, it would have wrongly permitted the second. [wholeSetTravelsIntact] separates them.
     */
    val carryable: Boolean get() = wholeSetTravelsIntact && discPaths.size >= 2

    /**
     * Everything "move the whole set" moves: the playlist first -- it is the file that has to arrive with them, not one
     * of the discs -- then every disc, then each disc's [companionPaths].
     */
    val allPaths: List<String> get() = listOfNotNull(playlistPath) + discPaths + companionPaths
}
