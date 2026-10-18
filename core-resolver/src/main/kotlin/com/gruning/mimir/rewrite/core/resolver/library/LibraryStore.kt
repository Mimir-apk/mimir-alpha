package com.gruning.mimir.rewrite.core.resolver.library

import android.content.Context
import androidx.room.withTransaction
import com.gruning.mimir.rewrite.core.model.home.HomeShelfRepository
import com.gruning.mimir.rewrite.core.model.library.CoverOverrideSource
import com.gruning.mimir.rewrite.core.model.library.EnrichAttemptSource
import com.gruning.mimir.rewrite.core.model.library.LibraryRepository
import com.gruning.mimir.rewrite.core.resolver.home.HomeShelfRepositoryImpl
import com.gruning.mimir.rewrite.core.resolver.scan.SourceWalker
import com.gruning.mimir.rewrite.core.storage.MimirDatabaseFactory

/**
 * The one DI entry the app/coordinator calls to obtain a wired [LibraryRepository]. Hides the Room DB
 * singleton, the SAF/file walker, the recognizer, and the dedup grouper behind a single
 * `repository(context)` call with manual dependency injection.
 *
 * Usage (coordinator / app):
 * ```kotlin
 * val repo = LibraryStore.repository(context)
 * // reactive:
 * repo.observeSummary().collect { … }
 * repo.observePlatforms().collect { … }
 * // scan a user-picked SAF tree (or a plain path) in place:
 * val result = repo.rescan(pickedTreeUri.toString())
 * ```
 *
 * The returned repository is process-cached (it wraps the singleton DB), so repeated calls are cheap and
 * share one DB + one reactive source.
 */
object LibraryStore {

    @Volatile
    private var cached: LibraryRepository? = null

    @Volatile
    private var cachedHomeShelf: HomeShelfRepository? = null

    /** The wired [LibraryRepository] for this process (created on first call, then cached). */
    fun repository(context: Context): LibraryRepository =
        cached ?: synchronized(this) {
            cached ?: build(context.applicationContext).also { cached = it }
        }

    /** The Home Shelf membership authority, sharing this store's database and library projection. */
    fun homeShelfRepository(context: Context): HomeShelfRepository =
        cachedHomeShelf ?: synchronized(this) {
            cachedHomeShelf ?: buildHomeShelf(context.applicationContext).also { cachedHomeShelf = it }
        }

    /**
     * The player's explicitly chosen covers, registered by `:app` (which owns the store).
     *
     * Read through an indirection at CALL time rather than captured at build time, deliberately: the
     * repository is built once and cached on first use, so a source captured then would silently be the
     * no-op default for any process where registration happened a moment later. This shape has no
     * ordering hazard to get wrong.
     */
    @Volatile
    var coverOverrideSource: CoverOverrideSource = CoverOverrideSource { _, _ -> null }

    /** How the last per-game Enrich ended, registered by `:app` (which owns the store). Read at call time, like [coverOverrideSource]. */
    @Volatile
    var enrichAttemptSource: EnrichAttemptSource = EnrichAttemptSource { null }

    private fun build(appContext: Context): LibraryRepository {
        val db = MimirDatabaseFactory.get(appContext)
        // One LocalArtSource resolves the sibling cover, distinct clear logo, and preview video (it
        // implements ArtSource + PreviewVideoSource); each media kind keeps its own memoized probe cache.
        val localArt = LocalArtSource(appContext)
        return LibraryRepositoryImpl(
            gameDao = db.gameDao(),
            platformDao = db.platformDao(),
            sourceRootDao = db.sourceRootDao(),
            hiddenItemDao = db.hiddenItemDao(),
            favoriteDao = db.favoriteDao(),
            genreSource = BundledGenreSource(appContext),
            artSource = localArt,
            previewVideoSource = localArt,
            coverOverrideSource = { gameId, hasAutomaticCover ->
                coverOverrideSource.shelfFaceOverrideFor(gameId, hasAutomaticCover)
            },
            enrichAttemptSource = { gameId -> enrichAttemptSource.outcomeFor(gameId) },
            walker = SourceWalker(appContext),
            runScanTransaction = { block ->
                db.withTransaction { block() }
            },
            gameIdentityDao = db.gameIdentityDao(),
            sourceReconcileDao = db.sourceReconcileDao(),
            launchProofDao = db.launchProofDao(),
            projectionDispatcher = LibraryProjectionDispatcher,
        )
    }

    private fun buildHomeShelf(appContext: Context): HomeShelfRepository {
        val db = MimirDatabaseFactory.get(appContext)
        return HomeShelfRepositoryImpl(
            homeShelfDao = db.homeShelfDao(),
            libraryRepository = repository(appContext),
            gameIdentityDao = db.gameIdentityDao(),
            runMutationTransaction = { block -> db.withTransaction { block() } },
        )
    }
}
