package com.gruning.mimir.rewrite

import android.content.Context
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.storage.MimirDatabaseFactory

/** Canonicalizes stale UI/process ids at durable write boundaries after Room Phase A has committed. */
internal object GameIdentityAliasResolver {
    suspend fun resolve(context: Context, gameId: GameId): GameId =
        resolve(context, listOf(gameId))[gameId] ?: gameId

    suspend fun resolve(
        context: Context,
        gameIds: Collection<GameId>,
    ): Map<GameId, GameId> {
        if (gameIds.isEmpty()) return emptyMap()
        val distinct = gameIds.distinct()
        val resolved = MimirDatabaseFactory.get(context.applicationContext)
            .gameIdentityDao()
            .resolveCurrentGameIds(distinct.map { it.value })
        return distinct.associateWith { id -> GameId(resolved[id.value] ?: id.value) }
    }
}

/** Synchronous process boundary for origins that cannot suspend while a new Room alias is already live. */
internal object GameIdentityAliasCache {
    @Volatile
    private var aliases: Map<String, String> = emptyMap()

    fun install(rows: Map<String, String>) {
        aliases = rows.filter { (from, to) -> from.isNotBlank() && to.isNotBlank() && from != to }
    }

    fun resolve(gameId: String): String = aliases[gameId] ?: gameId

    fun canonicalize(origin: PrimaryReturnOrigin): PrimaryReturnOrigin {
        val mapped = origin.arguments.mapValues { (key, value) ->
            when {
                key == "detailGameId" -> resolve(value)
                key.contains("game", ignoreCase = true) -> resolve(value)
                value.startsWith("library/gamelist/") -> {
                    val gameId = value.removePrefix("library/gamelist/")
                    "library/gamelist/${resolve(gameId)}"
                }
                else -> value
            }
        }
        return if (mapped == origin.arguments) origin else origin.copy(arguments = mapped)
    }
}

/** Serializes synchronous preference writes with their replay copy at the final durable boundary. */
internal object GameIdentityPreferenceMutationLock {
    private val lock = Any()

    fun <T> run(block: () -> T): T = synchronized(lock, block)
}
