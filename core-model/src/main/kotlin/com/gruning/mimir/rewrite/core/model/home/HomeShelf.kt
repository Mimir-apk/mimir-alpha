package com.gruning.mimir.rewrite.core.model.home

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.ProfileId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import kotlinx.coroutines.flow.Flow

/**
 * An Android package identity admitted to Home. The package string alone carries no kind — a caller
 * must wrap it in the [HomeShelfItemId] case that matches what the native snapshot actually proved
 * (game vs. app), which is what keeps the two Android surfaces from bleeding into each other.
 */
@JvmInline
value class AndroidPackageName(val value: String) {
    init {
        require(value.isNotBlank()) { "AndroidPackageName must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * The durable namespaces admitted by the primary Home Shelf.
 *
 * [ANDROID_APP] is a real typed namespace, not an Android game wearing a
 * different label.
 *
 * **Persistence status — READ-ONLY until the schema migration lands.** The Kotlin column is a plain
 * `String`, but the hand-written `CREATE TABLE` in `MimirDatabaseFactory` additionally carries
 * `CHECK(itemKind IN ('EMULATED_GAME', 'ANDROID_GAME'))`. Room does not model CHECK constraints, so
 * the exported `8.json` looks permissive while SQLite would reject every [ANDROID_APP] insert at
 * runtime. Widening that CHECK means rebuilding the table, which means a Room schema-version bump —
 * explicitly out of scope for this package. So [ANDROID_APP] is fully typed, projected, and
 * rendered, but `HomeShelfRepository.add` refuses it (fail-closed, with the reason) until the
 * migration lands. This package ships no intake that could reach that path: the
 * Android-app intake arrives with the later Library `Y = Edit` settlement.
 */
enum class HomeShelfItemKind {
    EMULATED_GAME,
    ANDROID_GAME,
    ANDROID_APP,
}

/**
 * A typed Home Shelf identity. Every case names the namespace it came from, so an Android app can
 * never be mistaken for an Android game (and therefore never enters the RA, hero, or game-recency
 * paths that only an emulated or Android *game* row may reach).
 */
sealed interface HomeShelfItemId {
    val kind: HomeShelfItemKind

    data class Emulated(val gameId: GameId) : HomeShelfItemId {
        override val kind: HomeShelfItemKind = HomeShelfItemKind.EMULATED_GAME
    }

    data class AndroidGame(val packageName: AndroidPackageName) : HomeShelfItemId {
        override val kind: HomeShelfItemKind = HomeShelfItemKind.ANDROID_GAME
    }

    /**
     * A non-game Android package. It launches through the same native package path as
     * [AndroidGame] but must never produce an `AndroidGameLaunchProof`, and belongs only to the
     * Android Apps context and user collections — never to game recency or Editor Choice.
     */
    data class AndroidApp(val packageName: AndroidPackageName) : HomeShelfItemId {
        override val kind: HomeShelfItemKind = HomeShelfItemKind.ANDROID_APP
    }
}

/** The package carried by an Android identity, or null for an emulated one. */
val HomeShelfItemId.androidPackageName: AndroidPackageName?
    get() = when (this) {
        is HomeShelfItemId.Emulated -> null
        is HomeShelfItemId.AndroidGame -> packageName
        is HomeShelfItemId.AndroidApp -> packageName
    }

/** Durable membership metadata. Rank and insertion time survive temporary content unavailability. */
data class HomeShelfMembership(
    val itemId: HomeShelfItemId,
    val rank: Long,
    val addedAtMillis: Long,
)

/**
 * Resolver projection of a durable membership row.
 *
 * Emulated entries carry an available, non-hidden library row. Missing or hidden emulated games are
 * omitted while their membership remains persisted. Android entries remain typed package candidates;
 * the app host combines them with its existing package snapshot and omits unavailable packages.
 */
sealed interface HomeShelfEntry {
    val itemId: HomeShelfItemId
    val rank: Long
    val addedAtMillis: Long

    data class Emulated(
        val game: GameRow,
        override val rank: Long,
        override val addedAtMillis: Long,
    ) : HomeShelfEntry {
        override val itemId: HomeShelfItemId.Emulated = HomeShelfItemId.Emulated(game.gameId)
    }

    data class AndroidGame(
        override val itemId: HomeShelfItemId.AndroidGame,
        override val rank: Long,
        override val addedAtMillis: Long,
    ) : HomeShelfEntry

    data class AndroidApp(
        override val itemId: HomeShelfItemId.AndroidApp,
        override val rank: Long,
        override val addedAtMillis: Long,
    ) : HomeShelfEntry
}

/** Idempotent add outcome, including the stable membership selected by the transaction. */
sealed interface AddToShelfResult {
    val membership: HomeShelfMembership

    data class Added(override val membership: HomeShelfMembership) : AddToShelfResult

    data class AlreadyPresent(override val membership: HomeShelfMembership) : AddToShelfResult
}

/** Profile-scoped authority for primary Home Shelf membership and its playable local projection. */
interface HomeShelfRepository {
    /** Durable membership truth, including entries whose playable content is temporarily unavailable. */
    fun observeMemberships(
        profileId: ProfileId = ProfileId.LOCAL,
    ): Flow<List<HomeShelfMembership>>

    fun observe(profileId: ProfileId = ProfileId.LOCAL): Flow<List<HomeShelfEntry>>

    /**
     * Adds [itemId] to the profile's shelf.
     *
     * Throws [UnsupportedOperationException] for [HomeShelfItemId.AndroidApp] until the
     * `home_shelf_items` CHECK constraint is widened — see [HomeShelfItemKind.ANDROID_APP].
     */
    suspend fun add(profileId: ProfileId, itemId: HomeShelfItemId): AddToShelfResult

    suspend fun remove(profileId: ProfileId, itemId: HomeShelfItemId): Boolean

    /**
     * Moves [itemId] directly before [before], or to the tail when [before] is null. Returns true only
     * when the profile's order changed; missing identities and no-op moves return false.
     */
    suspend fun move(
        profileId: ProfileId,
        itemId: HomeShelfItemId,
        before: HomeShelfItemId?,
    ): Boolean
}
