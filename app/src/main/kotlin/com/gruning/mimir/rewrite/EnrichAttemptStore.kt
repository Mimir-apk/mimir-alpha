package com.gruning.mimir.rewrite

import android.content.Context
import android.content.SharedPreferences
import com.gruning.mimir.rewrite.ArtworkFetchController.OneResult
import com.gruning.mimir.rewrite.core.model.library.EnrichAttemptOutcome

/**
 * The per-game record of how the last Enrich ended : a FINISHED attempt hides Enrich for the game on the direct
 * seat and in the Y menu, even when a source had no logo or cover for it, and a FAILED one keeps (or brings back) it.
 *
 * Kept like [ArtNoMatchStore], the app's other per-game provider record: app-private SharedPreferences keyed by the game's CURRENT id
 * ([GameIdentityAliasCache]), never the ROM folder and never Room. The library projection reads it through
 * `EnrichAttemptSource` (`LibraryStore.enrichAttemptSource`, registered in `MimirApplication`), so `GameRow.enrichAttempt` carries it
 * to every door. It holds an outcome NAME and nothing else: no title, URL, key or account.
 *
 * Known limit, deliberate: an identity re-key (a hash newly proven on a filename-only game) does not carry the record to the new id, so
 * that one game offers Enrich again until it is pressed once more. Wiring a remap phase would touch the persisted replay bit set.
 */
internal object EnrichAttemptStore {
    private const val PrefsName = "mimir_rewrite_enrich_attempts"

    @Volatile
    private var prefs: SharedPreferences? = null

    /** Test seam, like [ArtNoMatchStore.detachForTest]: a test JVM runs many Applications through this singleton. */
    internal fun detachForTest() {
        prefs = null
    }

    fun attach(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
        }
    }

    /** How the last Enrich of [gameId] ended, or null when none has finished or failed. */
    fun outcomeFor(gameId: String): EnrichAttemptOutcome? {
        if (gameId.isBlank()) return null
        val preferences = prefs ?: return null
        return EnrichAttemptOutcome.fromName(preferences.getString(GameIdentityAliasCache.resolve(gameId), null))
    }

    /** Stores [outcome] for [gameId]; true when the stored answer changed (so a caller knows to repaint). */
    fun record(gameId: String, outcome: EnrichAttemptOutcome): Boolean {
        if (gameId.isBlank()) return false
        return GameIdentityPreferenceMutationLock.run {
            val preferences = prefs ?: return@run false
            val key = GameIdentityAliasCache.resolve(gameId)
            if (preferences.getString(key, null) == outcome.name) return@run false
            preferences.edit().putString(key, outcome.name).commit()
        }
    }
}

/**
 * What a per-game Enrich result says about the attempt. A provider that answered, or a title with nothing to ask with, is FINISHED; a
 * transport or provider failure is FAILED. A closed gate or a busy gate asked nothing, so it is not an attempt at all and leaves the
 * record as it was (the button was never hidden by it).
 */
internal fun enrichAttemptOutcomeFor(result: OneResult): EnrichAttemptOutcome? = when (result) {
    OneResult.Matched -> EnrichAttemptOutcome.MATCHED
    OneResult.NoMatch -> EnrichAttemptOutcome.NO_MATCH
    OneResult.NoTitle -> EnrichAttemptOutcome.NO_TITLE
    OneResult.Failed -> EnrichAttemptOutcome.FAILED
    OneResult.GateClosed, OneResult.Busy -> null
}
