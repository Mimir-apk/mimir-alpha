package com.gruning.mimir.rewrite

import android.content.Context
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.tweaker.PerGameTweakerSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One atomic user choice: profile and frame cap must follow the same logical game identity. */
internal data class PerGameTweakerConfiguration(
    val profile: PerGameTweakerSelection,
    val frameLimitHz: Int?,
    val updatedAtMillis: Long,
    val revision: Long,
) {
    init {
        require(frameLimitHz == null || frameLimitHz in MIN_FRAME_LIMIT_HZ..MAX_FRAME_LIMIT_HZ)
        require(updatedAtMillis >= 0L)
        require(revision >= 0L)
    }

    companion object {
        val Inherit = PerGameTweakerConfiguration(
            profile = PerGameTweakerSelection.INHERIT,
            frameLimitHz = null,
            updatedAtMillis = 0L,
            revision = 0L,
        )
    }
}

internal data class PerGameTweakerDocument(
    val records: Map<String, PerGameTweakerConfiguration> = emptyMap(),
)

/**
 * App-owned durable selection store. The helper owns enforcement; this store only remembers intent.
 * Every public read/write resolves the Room v9 alias before touching preferences.
 */
internal object PerGameTweakerSelectionStore {
    private const val PrefsName = "mimir_rewrite_per_game_tweaker"

    suspend fun read(context: Context, gameId: GameId): PerGameTweakerConfiguration =
        withContext(Dispatchers.IO) {
            val canonical = GameIdentityAliasResolver.resolve(context, gameId)
            preferences(context).getString(canonical.value, null)
                ?.let(::decodePerGameTweakerConfiguration)
                ?: PerGameTweakerConfiguration.Inherit
        }

    suspend fun write(
        context: Context,
        gameId: GameId,
        profile: PerGameTweakerSelection,
        frameLimitHz: Int?,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean = withContext(Dispatchers.IO) {
        if (frameLimitHz != null && frameLimitHz !in MIN_FRAME_LIMIT_HZ..MAX_FRAME_LIMIT_HZ) {
            return@withContext false
        }
        val canonical = GameIdentityAliasResolver.resolve(context, gameId)
        GameIdentityPreferenceMutationLock.run {
            val prefs = preferences(context)
            val previous = prefs.getString(canonical.value, null)
                ?.let(::decodePerGameTweakerConfiguration)
            val next = nextPerGameTweakerConfiguration(
                previous = previous,
                profile = profile,
                frameLimitHz = frameLimitHz,
                nowMillis = nowMillis,
            )
            prefs.edit().putString(canonical.value, encodePerGameTweakerConfiguration(next)).commit()
        }
    }

    fun remapGameId(context: Context, fromGameId: GameId, toGameId: GameId): Boolean =
        remapGameIds(context, mapOf(fromGameId to toGameId))

    fun remapGameIds(context: Context, aliases: Map<GameId, GameId>): Boolean =
        GameIdentityPreferenceMutationLock.run {
            if (aliases.isEmpty()) return@run true
            val prefs = preferences(context)
            val current = prefs.all.mapNotNull { (key, raw) ->
                val value = raw as? String ?: return@mapNotNull null
                decodePerGameTweakerConfiguration(value)?.let { key to it }
            }.toMap()
            val mapped = remapPerGameTweakerDocument(
                PerGameTweakerDocument(current),
                aliases.mapKeys { it.key.value }.mapValues { it.value.value },
            )
            if (mapped.records == current) return@run true
            val editor = prefs.edit().clear()
            mapped.records.toSortedMap().forEach { (gameId, selection) ->
                editor.putString(gameId, encodePerGameTweakerConfiguration(selection))
            }
            editor.commit()
        }

    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(
        PrefsName,
        Context.MODE_PRIVATE,
    )
}

internal fun nextPerGameTweakerConfiguration(
    previous: PerGameTweakerConfiguration?,
    profile: PerGameTweakerSelection,
    frameLimitHz: Int?,
    nowMillis: Long,
): PerGameTweakerConfiguration {
    val safeNow = nowMillis.coerceAtLeast(0L)
    return PerGameTweakerConfiguration(
        profile = profile,
        frameLimitHz = frameLimitHz,
        updatedAtMillis = maxOf(safeNow, previous?.updatedAtMillis ?: 0L),
        revision = (previous?.revision ?: 0L).saturatingIncrement(),
    )
}

/** Newest atomic record wins; an exact tie remains owned by the already-canonical target key. */
internal fun remapPerGameTweakerDocument(
    document: PerGameTweakerDocument,
    aliases: Map<String, String>,
): PerGameTweakerDocument {
    if (aliases.isEmpty()) return document
    data class Candidate(
        val sourceKey: String,
        val targetKey: String,
        val record: PerGameTweakerConfiguration,
    )

    val grouped = document.records.map { (sourceKey, record) ->
        val targetKey = aliases[sourceKey]?.takeIf(String::isNotBlank) ?: sourceKey
        Candidate(sourceKey, targetKey, record)
    }.groupBy(Candidate::targetKey)

    val remapped = grouped.mapValues { (targetKey, candidates) ->
        candidates.sortedWith(
            compareByDescending<Candidate> { it.record.updatedAtMillis }
                .thenByDescending { it.record.revision }
                .thenByDescending { it.sourceKey == targetKey }
                .thenBy { it.sourceKey },
        ).first().record
    }
    return if (remapped == document.records) document else PerGameTweakerDocument(remapped)
}

internal fun encodePerGameTweakerConfiguration(record: PerGameTweakerConfiguration): String = listOf(
    PER_GAME_TWEAKER_SCHEMA_VERSION.toString(),
    record.profile.name,
    record.frameLimitHz?.toString().orEmpty(),
    record.updatedAtMillis.toString(),
    record.revision.toString(),
).joinToString(PER_GAME_TWEAKER_SEPARATOR)

internal fun decodePerGameTweakerConfiguration(raw: String): PerGameTweakerConfiguration? = runCatching {
    val parts = raw.split(PER_GAME_TWEAKER_SEPARATOR)
    require(parts.size == 5 && parts[0].toInt() == PER_GAME_TWEAKER_SCHEMA_VERSION)
    val profile = PerGameTweakerSelection.valueOf(parts[1])
    val frameLimit = parts[2].takeIf(String::isNotEmpty)?.toInt()
    PerGameTweakerConfiguration(
        profile = profile,
        frameLimitHz = frameLimit,
        updatedAtMillis = parts[3].toLong(),
        revision = parts[4].toLong(),
    )
}.getOrNull()

private fun Long.saturatingIncrement(): Long = if (this == Long.MAX_VALUE) this else this + 1L

private const val PER_GAME_TWEAKER_SCHEMA_VERSION = 1
private const val PER_GAME_TWEAKER_SEPARATOR = "|"
internal const val MIN_FRAME_LIMIT_HZ = 24
internal const val MAX_FRAME_LIMIT_HZ = 240
