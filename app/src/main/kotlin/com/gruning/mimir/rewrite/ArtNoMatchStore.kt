package com.gruning.mimir.rewrite

import android.content.Context
import android.content.SharedPreferences
import com.gruning.mimir.rewrite.core.metadata.online.ScreenScraperMetadataSource

/** PERF02 negative-result cache for automatic artwork batches. Explicit per-game fetches bypass it. */
internal object ArtNoMatchStore {
    private const val PrefsName = "mimir_rewrite_art_no_matches"
    private const val PhysicalMediaPrefix = "physical-media:"
    private const val ClearPrefix = "@cleared/"

    /**
     * R6-P2-03 lever A: every managed-media slot now gets its own provider-absent negative, not just
     * PHYSICAL. [PhysicalMediaPrefix] is kept first and unchanged so already-recorded physical negatives
     * stay valid; [canonicalKey] and [remapGameId] iterate this list so a rekeyed identity carries all
     * six slot negatives, not only the physical one (`audio:` joined with the launch sound, 2026-09-30: a game whose clip has no
     * usable sound). The bare per-game key (no prefix,
     * [record]/[clear]/[isCoolingDown] called directly with a gameId) is a SEPARATE namespace and is
     * deliberately not part of this list.
     */
    internal val SlotPrefixes = listOf(PhysicalMediaPrefix, "cover:", "logo:", "hero:", "clip:", "audio:")
    /** Not a cooldown record; the coverage fingerprint every cached miss was recorded under. */
    internal const val CoverageEpochKey = "@coverage-epoch"

    /** Not a cooldown record; the [AudioNegativeEpoch] the launch sound's cached misses were recorded under. */
    internal const val AudioEpochKey = "@audio-negative-epoch"

    /**
     * The meaning of the launch sound's negatives (`audio:<gameId>`), which changes only when the rule that records them
     * does. Raise it and the next launch drops every sound negative, and only those.
     *
     * 1: the first build, which copied AAC and recorded "not AAC" as a 14-day miss. A reported initial enrich
     * recorded 11 negatives for games whose clips a transcode can now turn into a sound.
     * 2: any track the device can decode is transcoded to AAC, so a negative recorded under 1 is stale.
     *
     * It is NOT [ScreenScraperMetadataSource.coverageEpoch]: that one drops every slot's negatives for every game, and a
     * cover or logo miss has nothing to do with the sound.
     */
    internal const val AudioNegativeEpoch = 2

    /** One named policy window: an automatic batch retries an unresolved game after fourteen days. */
    internal const val CooldownMillis = 14L * 24 * 60 * 60 * 1_000

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    internal var currentEpochMillis: () -> Long = { System.currentTimeMillis() }

    /**
     * Test seam. [attach] binds once per process by design, but a test JVM runs many Applications
     * through this singleton, so without this a later test reads a SharedPreferences the store no
     * longer points at and silently asserts against the wrong file.
     */
    internal fun detachForTest() {
        prefs = null
    }

    fun attach(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
        }
        dropNegativesOnCoverageChange(ScreenScraperMetadataSource.coverageEpoch())
        dropAudioNegativesOnEpochChange()
    }

    /**
     * Drops the launch sound's cached misses (and only those) when they were recorded under another [AudioNegativeEpoch].
     * Runs on every [attach], so an install that keeps its data (no wipe) heals on its first launch: a game whose clip was
     * recorded as "not AAC" is asked for again instead of waiting out fourteen days. A store with no stamp yet counts as
     * the first epoch's, so a fresh install drops nothing because it has nothing.
     */
    @Synchronized
    private fun dropAudioNegativesOnEpochChange() {
        val preferences = prefs ?: return
        if (preferences.getInt(AudioEpochKey, 1) == AudioNegativeEpoch) return
        val editor = preferences.edit()
        val audioPrefix = "audio:"
        preferences.all.keys
            .filter { it.startsWith(audioPrefix) || it.startsWith(ClearPrefix + audioPrefix) }
            .forEach(editor::remove)
        editor.putInt(AudioEpochKey, AudioNegativeEpoch).apply()
    }

    /**
     * A cached miss is only trustworthy while the coverage that produced it holds.
     *
     * Recording a miss for a platform we could not even ask about is now prevented upstream, but
     * libraries scanned before that fix carry those entries already: an xbox360 title recorded as
     * "no match" while xbox360 had no system id stays suppressed for fourteen days, and the fix that
     * finally made it fetchable would never get to run. Clearing on a coverage change heals them on
     * the next launch instead of leaving the player to wait out a cooldown or hand-Enrich each title.
     *
     * Cost is bounded and one-off per coverage change: previously-missing games pay one extra lookup.
     * The alternative -- keeping a negative whose cause has gone -- is art that never appears at all,
     * which is the reported failure.
     */
    @Synchronized
    private fun dropNegativesOnCoverageChange(currentEpoch: String) {
        val preferences = prefs ?: return
        val stored = preferences.getString(CoverageEpochKey, null)
        if (stored == currentEpoch) return
        val editor = preferences.edit()
        // First run after this store learned about epochs has stored == null. Existing entries were
        // recorded under unknown coverage, so they are exactly the untrustworthy ones.
        preferences.all.keys
            .filterNot { it == CoverageEpochKey }
            .forEach(editor::remove)
        editor.putString(CoverageEpochKey, currentEpoch).apply()
    }

    fun record(gameId: String, nowEpochMillis: Long = currentEpochMillis()) {
        if (gameId.isBlank()) return
        GameIdentityPreferenceMutationLock.run {
            val preferences = prefs ?: return@run
            val key = canonicalKey(gameId)
            val previous = changedAt(preferences, key)
            val recordedAt = maxOf(nowEpochMillis.coerceAtLeast(0L), previous + 1L)
            preferences.edit()
                .putLong(key, recordedAt)
                .remove(clearKey(key))
                .commit()
        }
    }

    fun clear(gameId: String) {
        if (gameId.isBlank()) return
        GameIdentityPreferenceMutationLock.run {
            val preferences = prefs ?: return@run
            val key = canonicalKey(gameId)
            val clearedAt = maxOf(currentEpochMillis().coerceAtLeast(0L), changedAt(preferences, key) + 1L)
            preferences.edit()
                .remove(key)
                .putLong(clearKey(key), clearedAt)
                .commit()
        }
    }

    fun isCoolingDown(gameId: String, nowEpochMillis: Long = currentEpochMillis()): Boolean {
        val currentGameId = canonicalKey(gameId)
        val preferences = prefs ?: return false
        val lastNoMatch = preferences.takeIf {
            it.contains(currentGameId) &&
                it.getLong(currentGameId, 0L) > it.getLong(clearKey(currentGameId), -1L)
        }?.getLong(currentGameId, 0L)
            ?: return false
        return isCoolingDown(lastNoMatch, nowEpochMillis)
    }

    fun remapGameId(context: Context, fromGameId: String, toGameId: String): Boolean {
        return GameIdentityPreferenceMutationLock.run {
            attach(context)
            val preferences = prefs ?: return@run false
            if (fromGameId == toGameId) return@run true
            val editor = preferences.edit()
            var changed = false
            (listOf("") + SlotPrefixes).map { prefix ->
                "$prefix$fromGameId" to "$prefix$toGameId"
            }.forEach { (sourceKey, targetKey) ->
                val source = cooldownRecord(preferences, sourceKey)
                val target = cooldownRecord(preferences, targetKey)
                if (source.changedAtMillis <= target.changedAtMillis) return@forEach
                if (source.noMatchAtMillis != null) {
                    editor.putLong(targetKey, source.noMatchAtMillis)
                    editor.remove(clearKey(targetKey))
                } else {
                    editor.remove(targetKey)
                    editor.putLong(clearKey(targetKey), source.changedAtMillis)
                }
                changed = true
            }
            !changed || editor.commit()
        }
    }

    internal fun isCoolingDown(lastNoMatchEpochMillis: Long, nowEpochMillis: Long): Boolean {
        val age = nowEpochMillis - lastNoMatchEpochMillis
        return age >= 0L && age < CooldownMillis
    }

    private fun canonicalKey(key: String): String {
        val prefix = SlotPrefixes.firstOrNull { key.startsWith(it) }
        return if (prefix != null) {
            prefix + GameIdentityAliasCache.resolve(key.removePrefix(prefix))
        } else {
            GameIdentityAliasCache.resolve(key)
        }
    }

    private data class CooldownRecord(
        val noMatchAtMillis: Long?,
        val changedAtMillis: Long,
    )

    private fun cooldownRecord(preferences: SharedPreferences, key: String): CooldownRecord {
        val noMatch = preferences.getLong(key, -1L).takeIf { preferences.contains(key) }
        val cleared = preferences.getLong(clearKey(key), -1L).takeIf {
            preferences.contains(clearKey(key))
        }
        return if (noMatch != null && noMatch > (cleared ?: -1L)) {
            CooldownRecord(noMatch, noMatch)
        } else {
            CooldownRecord(null, cleared ?: -1L)
        }
    }

    private fun changedAt(preferences: SharedPreferences, key: String): Long =
        cooldownRecord(preferences, key).changedAtMillis

    private fun clearKey(key: String): String = ClearPrefix + key
}
