package com.gruning.mimir.rewrite

import android.content.Context
import com.gruning.mimir.rewrite.core.files.FileItem
import com.gruning.mimir.rewrite.core.files.PlatformFolderCandidate
import com.gruning.mimir.rewrite.core.resolver.ExtensionPlatformClassifier
import com.gruning.mimir.rewrite.core.resolver.GameNameResolver
import com.gruning.mimir.rewrite.core.resolver.ResolutionInput
import com.gruning.mimir.rewrite.core.storage.MimirDatabaseFactory
import com.gruning.mimir.rewrite.core.storage.entity.GameEntity
import java.io.File

/**
 * **Where the player's games for a platform actually live** — F-06's smart default, answered from the
 * real library rather than guessed from a folder name.
 *
 * The policy on this item is a refusal, not a feature: *"offering the WRONG platform folder is
 * worse than offering none."* So the question this object answers is deliberately narrow — "which
 * directories has the scanner actually found this platform's games in" — and the answer is a list that
 * is very often empty, which [ExtractDestinationPolicy][com.gruning.mimir.rewrite.core.files.ExtractDestinationPolicy]
 * turns into "no default, plain folder picker".
 *
 * **[foldersFor] is derived from `GameEntity`, not from folder names.** A name-based guess (find a
 * directory the registry maps to GBA) would happily nominate an empty `Roms/GBA` a player made and never
 * used, or the *first* of three such folders. The library's own rows cannot be wrong about where its
 * games are: each row's `managedPath` IS a file the scanner read. `sourceRootUri` rows are excluded — a
 * SAF tree URI is not a filesystem path an extraction can write into.
 *
 * > **[discoveredFolders] deliberately does the thing the paragraph above refuses, and the difference is
 * > the whole ruling.**  Naming
 * > an empty `Roms/ps2` is a bad **guess** and a good **option**: the paragraph above is about what
 * > Mimir may assert unprompted, this is about what the player may be shown to choose from. Setting a new
 * > platform up is precisely when its folder is empty, so a library-only list cannot serve the case it is
 * > most needed for. The two lists stay separate rather than merged, and
 * > `PlatformDestinationPolicy` keeps them distinguishable (`fromLibrary`) so a surface can order the
 * > provable ones first.
 *
 * **Snapshot, refreshed off the main thread, never queried inline.** `FileBrowserStore.extractOptions`
 * is called from an input dispatch, and that path already carries a documented OWED note about doing
 * blocking work on the composition thread. A `runBlocking` Room query there would make it worse. So this
 * cache is filled by [refresh] when a browser session opens, and read as a plain map afterwards.
 *
 * **The race this used to lose, and how [refresh] stopped losing it.**  Traced to `FileBrowserStore.open`
 * firing [refresh] with `scope.launch { ... }` and mounting the browser in the same breath, never
 * awaiting it — so a player fast enough to press SECONDARY on an archive before that Room read landed
 * hit [foldersFor] while [byPlatform] was still the pre-refresh `emptyMap()`, got the honest-empty
 * answer, and never saw the platform row again because nothing re-asked. `open` now sequences the two
 * (see its own `openAfterRomFolderRefresh`): [refresh] completes BEFORE the browser's controller is
 * published, so nothing can dispatch SECONDARY — nothing can reach [foldersFor] at all — while this
 * object is still holding a not-yet-refreshed snapshot. **[foldersFor] can still answer empty**: an
 * unrecognised archive, a platform the library has no folder for, or [refresh] itself failing (its own
 * KDoc) all remain live, honest reasons. Only the "hasn't landed YET" reason is gone.
 */
internal object FileBrowserRomFolders {

    @Volatile
    private var byPlatform: Map<String, List<String>> = emptyMap()

    @Volatile
    private var discovered: List<PlatformFolderCandidate> = emptyList()

    /**
     * Re-read the library from the real database. Suspend, so the caller keeps it off the input thread
     * — and now the caller (`FileBrowserStore.open`) also AWAITS it before the browser it feeds becomes
     * reachable; see this object's own KDoc for why that is what closes the race the regression report described.
     *
     * A thin wrapper over [refreshFrom]: this is the one line that cannot be exercised by a JVM test
     * (it needs a real `Context` and `MimirDatabaseFactory`), kept as small as possible so everything
     * that CAN be tested — the grouping, the failure posture — lives in [refreshFrom] instead.
     */
    suspend fun refresh(context: Context) {
        refreshFrom { MimirDatabaseFactory.get(context).gameDao().allPrimaryGames() }
    }

    /**
     * [refresh]'s body, with the data source injected. Lifted out so the grouping rule and the
     * failure posture are directly testable with fixture [GameEntity] rows and a fake [load] — no
     * `Context`, no Room — following `FileBrowserStore.runExtraction`'s own precedent for pulling
     * async-store logic somewhere a plain JUnit test can drive it.
     *
     * [load] is called exactly once. Grouping itself is [groupByPlatform], kept as a free function so
     * it is testable with no object state at all.
     *
     * Failure is empty, never a throw: an extract-destination hint is not worth crashing a file browser
     * over, and empty degrades to the honest fallback rather than to a wrong suggestion. Same posture
     * `FileBrowserStore.enumerateVolumes` already takes for volume enumeration.
     */
    internal suspend fun refreshFrom(load: suspend () -> List<GameEntity>) {
        byPlatform = runCatching { groupByPlatform(load()) }.getOrDefault(emptyMap())
    }

    /**
     * Every folder the library sources [platformId] from. Empty for null or unknown — and, for the
     * narrow window before this process's first [refresh]/[refreshFrom] call has landed, also for
     * not-yet-refreshed. **That last case is no longer reachable through a live chooser**: `FileBrowserStore.open`
     * awaits the refresh before the browser this feeds is reachable at all, so by the time anything can
     * dispatch SECONDARY and reach this method, the read has either succeeded or failed — never
     * "still pending". See this object's own KDoc for the race this closed.
     *
     * More than one entry is NOT an error — it is the ambiguity
     * `ExtractDestinationPolicy.suggestedPlatformFolder` is built to decline on. This object reports
     * what it found; the policy decides whether that is enough to act on.
     */
    fun foldersFor(platformId: String?): List<String> =
        platformId?.let { byPlatform[it] }.orEmpty()

    /**
     * The whole `platformId -> folders` map, for the destination LIST rather than for one guess.
     *
     * [foldersFor] answers one platform because [ExtractDestinationPolicy][com.gruning.mimir.rewrite.core.files.ExtractDestinationPolicy]
     * asks about one; `PlatformDestinationPolicy` asks about all of them so the player can choose
     * from a list instead of being handed a single guess.
     */
    fun libraryFolders(): Map<String, List<String>> = byPlatform

    /**
     * Platform-named folders that exist on the drives — ****
     *
     * Empty until [discover] has run. That is deliberate and it is why [discover] is fired rather than
     * awaited: a folder list that is still filling costs a shorter destination list for a moment, and
     * awaiting it would put a directory read back on the path that opens the file manager — the exact
     * cost the async listing seam was built to remove.
     */
    fun discoveredFolders(): List<PlatformFolderCandidate> = discovered

    /**
     * The ROM root a NEW platform folder would be created under — **R-22**.
     *
     * The parent every existing platform folder already agrees on. Derived rather than configured, on
     * [discoverFrom]'s own reasoning: the folders the library sources from are the only evidence on this
     * device of where he keeps games, and their shared parent is where the next one belongs.
     *
     * **Null unless they agree.** Two roots means two right answers and no way to choose, and a proposal
     * that lands on the wrong drive is worse than no proposal — he would move a game somewhere he never
     * looks. `discovered` is preferred over `byPlatform` because it already includes empty folders.
     */
    fun proposalRoot(): String? {
        val parents = (discovered.map { it.path } + byPlatform.values.flatten())
            .mapNotNull { File(it).parent?.trimEnd('/') }
            .filter { it.isNotBlank() }
            .distinct()
        return parents.singleOrNull()
    }

    /**
     * Walk one level inside the library's ROM roots and keep the subfolders whose names name a platform.
     *
     * **Fired, never awaited** — see [discoveredFolders]. Called after [refresh] has landed, because the
     * roots are derived from what that read found.
     *
     * The real filesystem and the real registry, injected in [discoverFrom] so both are testable.
     */
    suspend fun discover(context: Context) {
        val roots = runCatching {
            MimirDatabaseFactory.get(context).sourceRootDao().allOnce().map { it.rootUri }
        }.getOrDefault(emptyList())
        discoverFrom(
            sourceRoots = roots,
            listChildDirectories = { path ->
                File(path).listFiles().orEmpty().filter { it.isDirectory }.map { it.name to it.path }
            },
            platformOfFolderName = { name -> ExtensionPlatformClassifier.platformHintFromFolder(name)?.value },
        )
    }

    /**
     * [discover]'s body with the filesystem and the registry injected.
     *
     * **The roots are derived, not configured.** Two sources, both already true about this device:
     * the paths the player added as ROM sources, and the PARENTS of the folders the library's own games
     * sit in. The second is what finds an empty `ps2` standing beside a populated `gba` — the empty-folder
     * setup case, and the one a library-only list cannot see by construction.
     *
     * **SAF tree URIs are skipped, not failed.** A `content://` root cannot be listed with `File`, and
     * Mimir keeps this surface in its own browser rather than switching through SAF. A skipped root costs some rows, never a crash.
     *
     * Failure is empty, for [refreshFrom]'s reason: a destination list is not worth crashing a browser.
     */
    internal suspend fun discoverFrom(
        sourceRoots: List<String>,
        listChildDirectories: suspend (String) -> List<Pair<String, String>>,
        platformOfFolderName: (String) -> String?,
    ) {
        discovered = runCatching {
            val roots = buildList {
                addAll(sourceRoots.filterNot { it.startsWith("content://") })
                byPlatform.values.flatten().forEach { folder ->
                    File(folder).parent?.let { add(it) }
                }
            }.map { it.trimEnd('/') }.filter { it.isNotBlank() }.distinct()

            roots.flatMap { root ->
                listChildDirectories(root).mapNotNull { (name, path) ->
                    platformOfFolderName(name)?.let { PlatformFolderCandidate(platformId = it, path = path) }
                }
            }.distinctBy { it.path }
        }.getOrDefault(emptyList())
    }

    /** Test seam and session teardown — a browser session's cache must not outlive the library it read. */
    fun clear() {
        byPlatform = emptyMap()
        discovered = emptyList()
    }

    /**
     * Whether [item] is a BIOS payload — F-08's BIOS filter chip.
     *
     * **The resolver's own answer, injected, never a second table.** `GameNameResolver` already owns
     * this knowledge (`BIOS_EXTENSIONS`, `EXACT_BIOS_NAMES`, the `scph`/`dc_boot` name regex, and the
     * `bios`/`firmware` support-folder rule), all of it `private` — so the honest way to reach it is its
     * public [GameNameResolver.resolve] entry point, exactly as `core-storage`'s `BrowserListing`
     * requires its `Entry.isBios` to be supplied from outside rather than decided locally.
     *
     * **Cheap on purpose**: this runs once per row on the main thread, so it asks [GameNameResolver.isBiosPayload]
     * (folder rule, then extension, then the name pattern) rather than a full title resolve.
     *
     * The parent directory is passed as the relative path, which is what lets a plain `scph1001.bin`
     * sitting in a folder named `bios` be recognised by its *location* as well as its name.
     */
    fun isBiosPayload(item: FileItem): Boolean {
        if (item.isDirectory) return false
        val parent = File(item.path).parentFile?.name.orEmpty()
        val relativePath = if (parent.isBlank()) item.name else "$parent/${item.name}"
        return runCatching {
            resolver.isBiosPayload(
                input = ResolutionInput(fileName = item.name, extension = item.extension.lowercase()),
                relativePath = relativePath,
            )
        }.getOrDefault(false)
    }

    private val resolver = GameNameResolver()
}

/**
 * [games] grouped into `platformId -> distinct parent directories of their managedPath rows` — the
 * mapping [FileBrowserRomFolders.refreshFrom] populates its cache with, lifted out as a free function
 * (no object state, no suspend) so the grouping RULE is testable with plain fixture [GameEntity] lists,
 * independent of the database read around it.
 *
 * `sourceRootUri`-only rows are excluded implicitly: a SAF tree URI is never a `managedPath`, so a game
 * with `managedPath == null` contributes nothing, exactly as [FileBrowserRomFolders]'s own KDoc requires
 * ("a SAF tree URI is not a filesystem path an extraction can write into"). A blank `managedPath`, or one
 * whose [java.io.File.getParent] is null or blank (no directory component), is skipped the same way —
 * both would otherwise poison a real platform's folder list with a value nothing could extract into.
 */
internal fun groupByPlatform(games: List<GameEntity>): Map<String, List<String>> =
    games.mapNotNull { game ->
        val path = game.managedPath?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val parent = File(path).parent?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        game.platformId to parent
    }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, folders) -> folders.distinct() }
