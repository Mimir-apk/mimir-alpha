package com.gruning.mimir.rewrite

import android.content.Context
import android.content.SharedPreferences

/**
 * ART02 : the per-game record of a VERIFIED art resolution — "this gameId is
 * ScreenScraper jeu <providerGameId>". Written the moment a system-verified lookup succeeds; read into
 * every later [com.gruning.mimir.rewrite.core.metadata.MetadataQuery] so re-fetches resolve precisely in
 * one call and can never re-run the name ladder into a wrong match.
 *
 * Storage note: a SharedPreferences map rather than a Room table ON PURPOSE for this slice — RS01
 * (the resolver upgrade lane) owns the database schema tonight and two concurrent schema bumps would
 * collide on migrations. The mapping is append-only, tiny (one short string per resolved game), and
 * upgrade-safe: when RS01 lands, this store can be folded into a proper table in the SAME migration.
 */
internal object ArtResolutionStore {
    private const val PrefsName = "mimir_rewrite_art_resolutions"
    private const val Separator = "|"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun attach(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
        }
    }

    /** The verified ScreenScraper `jeu.id` for [gameId], or null when this game never resolved. */
    fun resolvedGameIdFor(gameId: String): String? =
        prefs?.getString(GameIdentityAliasCache.resolve(gameId), null)?.let(::decode)?.providerGameId

    /** Record a system-verified resolution. Last write wins; identical rewrites are harmless. */
    fun record(gameId: String, providerGameId: String) {
        if (gameId.isBlank() || providerGameId.isBlank()) return
        GameIdentityPreferenceMutationLock.run {
            val preferences = prefs ?: return@run
            val currentGameId = GameIdentityAliasCache.resolve(gameId)
            val previous = preferences.getString(currentGameId, null)?.let(::decode)
            val changedAt = maxOf(
                System.currentTimeMillis().coerceAtLeast(0L),
                (previous?.changedAtMillis ?: -1L) + 1L,
            )
            preferences.edit()
                .putString(currentGameId, encode(ResolutionRecord(providerGameId, changedAt)))
                .commit()
        }
    }

    fun remapGameId(context: Context, fromGameId: String, toGameId: String): Boolean {
        return GameIdentityPreferenceMutationLock.run {
            attach(context)
            val preferences = prefs ?: return@run false
            if (fromGameId == toGameId) return@run true
            val source = preferences.getString(fromGameId, null)?.let(::decode) ?: return@run true
            val target = preferences.getString(toGameId, null)?.let(::decode)
            if (target != null && target.changedAtMillis >= source.changedAtMillis) return@run true
            preferences.edit().putString(toGameId, encode(source)).commit()
        }
    }

    private data class ResolutionRecord(
        val providerGameId: String,
        val changedAtMillis: Long,
    )

    private fun encode(record: ResolutionRecord): String =
        "${record.providerGameId}$Separator${record.changedAtMillis.coerceAtLeast(0L)}"

    private fun decode(raw: String): ResolutionRecord {
        val separator = raw.lastIndexOf(Separator)
        if (separator <= 0) return ResolutionRecord(raw, 0L)
        val changedAt = raw.substring(separator + Separator.length).toLongOrNull()
            ?: return ResolutionRecord(raw, 0L)
        return ResolutionRecord(raw.substring(0, separator), changedAt.coerceAtLeast(0L))
    }
}
