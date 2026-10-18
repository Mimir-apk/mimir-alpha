package com.gruning.mimir.rewrite

import android.content.Context
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfEntry
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfMembership
import com.gruning.mimir.rewrite.core.model.library.LibraryRepository
import com.gruning.mimir.rewrite.feature.library.NativeShelfRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LegacyEmulatedFavoritePromotion(
    val gameId: GameId,
    val favoritedAtMillis: Long,
)

data class LegacyHomeFavoritePromotion(
    val emulatedFavorites: List<LegacyEmulatedFavoritePromotion>,
    val androidGamePackages: List<String>,
)

/**
 * Builds the one-way promotion plan from currently proven rows only. Emulated identities come from
 * the resolver's canonical GameRow, never the raw legacy member key; Android Apps are inexpressible.
 */
fun planLegacyHomeFavoritePromotion(
    memberships: List<HomeShelfMembership>,
    resolvedEntries: List<HomeShelfEntry>,
    provenNativeGames: List<NativeShelfRow>,
): LegacyHomeFavoritePromotion {
    // resolvedEntries is itself the repository projection of legacy membership rows. Its canonical
    // GameRow identity may intentionally differ from an old duplicate-member key in memberships.
    val emulated = resolvedEntries.asSequence()
        .filterIsInstance<HomeShelfEntry.Emulated>()
        .sortedWith(
            compareBy<HomeShelfEntry.Emulated> { it.rank }
                .thenBy { it.addedAtMillis }
                .thenBy { it.game.gameId.value },
        )
        .distinctBy { it.game.gameId }
        .mapIndexed { index, entry ->
            // Negative deterministic timestamps append migrated rows after ordinary clock-stamped
            // Favorites while DESC ordering preserves the legacy rank: -1, -2, -3, ...
            LegacyEmulatedFavoritePromotion(entry.game.gameId, -(index.toLong() + 1L))
        }
        .toList()
    val nativeMemberships = memberships.asSequence()
        .mapNotNull { (it.itemId as? HomeShelfItemId.AndroidGame)?.packageName?.value }
        .toHashSet()
    val native = provenNativeGames.asSequence()
        .filter(NativeShelfRow::isGame)
        .map(NativeShelfRow::packageName)
        .filter(nativeMemberships::contains)
        .distinct()
        .sorted()
        .toList()
    return LegacyHomeFavoritePromotion(emulated, native)
}

object HomeFavoritesMigration {
    private const val PrefsName = "mimir_home_favorites_migration"
    private const val CompleteKey = "legacy_home_shelf_items_to_favorites.v1.complete"

    suspend fun isComplete(context: Context): Boolean = withContext(Dispatchers.IO) {
        context.applicationContext
            .getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
            .getBoolean(CompleteKey, false)
    }

    /**
     * Crash-safe, deliberately not falsely transactional across Room and SharedPreferences: incomplete
     * marker -> idempotent desired-state writes -> durable complete marker. A retry repeats safe writes.
     */
    suspend fun run(
        context: Context,
        plan: LegacyHomeFavoritePromotion,
        libraryRepository: LibraryRepository,
    ): Boolean = withContext(Dispatchers.IO) {
        if (isComplete(context)) return@withContext true
        try {
            val emulatedTruthSecured = plan.emulatedFavorites.all { promotion ->
                libraryRepository.promoteFavoriteIfAbsent(
                    promotion.gameId,
                    promotion.favoritedAtMillis,
                )
            }
            val nativeWritesCommitted = emulatedTruthSecured && plan.androidGamePackages.all { packageName ->
                setNativeShelfFavorite(context, packageName, favorite = true)
            }
            if (!nativeWritesCommitted) {
                false
            } else {
                context.applicationContext
                    .getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(CompleteKey, true)
                    .commit()
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            false
        }
    }
}
