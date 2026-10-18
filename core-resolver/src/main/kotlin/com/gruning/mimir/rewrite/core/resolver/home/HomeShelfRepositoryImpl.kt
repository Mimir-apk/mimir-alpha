package com.gruning.mimir.rewrite.core.resolver.home

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.ProfileId
import com.gruning.mimir.rewrite.core.model.home.AddToShelfResult
import com.gruning.mimir.rewrite.core.model.home.AndroidPackageName
import com.gruning.mimir.rewrite.core.model.home.HomeShelfEntry
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemKind
import com.gruning.mimir.rewrite.core.model.home.HomeShelfMembership
import com.gruning.mimir.rewrite.core.model.home.HomeShelfRepository
import com.gruning.mimir.rewrite.core.model.library.LibraryRepository
import com.gruning.mimir.rewrite.core.storage.dao.HomeShelfDao
import com.gruning.mimir.rewrite.core.storage.dao.GameIdentityDao
import com.gruning.mimir.rewrite.core.storage.entity.HomeShelfItemEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Room-backed Home Shelf authority with read-only emulated-game enrichment from the local library. */
class HomeShelfRepositoryImpl(
    private val homeShelfDao: HomeShelfDao,
    private val libraryRepository: LibraryRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val gameIdentityDao: GameIdentityDao? = null,
    private val runMutationTransaction: suspend (suspend () -> Unit) -> Unit = { block -> block() },
) : HomeShelfRepository {

    override fun observeMemberships(profileId: ProfileId): Flow<List<HomeShelfMembership>> =
        homeShelfDao.observeItems(profileId.value)
            .map { rows -> rows.mapNotNull(HomeShelfItemEntity::toMembershipOrNull) }
            .distinctUntilChanged()
            .flowOn(ioDispatcher)

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observe(profileId: ProfileId): Flow<List<HomeShelfEntry>> =
        observeMemberships(profileId)
            .flatMapLatest { memberships ->
                val emulatedIds = memberships.mapNotNullTo(linkedSetOf()) { membership ->
                    (membership.itemId as? HomeShelfItemId.Emulated)?.gameId
                }
                libraryRepository.observeGames(emulatedIds).map { games ->
                    val visibleGames = games.associateBy { it.gameId }
                    memberships.mapNotNull { membership ->
                        when (val itemId = membership.itemId) {
                            is HomeShelfItemId.Emulated -> visibleGames[itemId.gameId]?.let { game ->
                                HomeShelfEntry.Emulated(
                                    game = game,
                                    rank = membership.rank,
                                    addedAtMillis = membership.addedAtMillis,
                                )
                            }

                            is HomeShelfItemId.AndroidGame -> HomeShelfEntry.AndroidGame(
                                itemId = itemId,
                                rank = membership.rank,
                                addedAtMillis = membership.addedAtMillis,
                            )

                            is HomeShelfItemId.AndroidApp -> HomeShelfEntry.AndroidApp(
                                itemId = itemId,
                                rank = membership.rank,
                                addedAtMillis = membership.addedAtMillis,
                            )
                        }
                    }
                }
            }
            .distinctUntilChanged()

    override suspend fun add(
        profileId: ProfileId,
        itemId: HomeShelfItemId,
    ): AddToShelfResult = withContext(ioDispatcher) {
        // Fail closed, loudly, and with the reason. `home_shelf_items` was created with
        // CHECK(itemKind IN ('EMULATED_GAME','ANDROID_GAME')), so this insert would otherwise die as
        // an opaque SQLiteConstraintException. Widening the CHECK requires a table rebuild and a
        // schema-version bump, which is not performed here. Reads, removes, and moves of ANDROID_APP rows are all safe and stay enabled.
        if (itemId is HomeShelfItemId.AndroidApp) {
            throw UnsupportedOperationException(
                "Home Shelf cannot persist ANDROID_APP yet: home_shelf_items still carries " +
                    "CHECK(itemKind IN ('EMULATED_GAME','ANDROID_GAME')). Widen it in a Room " +
                    "migration (schema 8 -> 9) before enabling the Android-app intake.",
            )
        }
        var transactionResult: com.gruning.mimir.rewrite.core.storage.dao.HomeShelfInsertResult? = null
        runMutationTransaction {
            val storedId = itemId.resolveCurrent(gameIdentityDao).toStoredId()
            transactionResult = homeShelfDao.addIfAbsent(
                profileId = profileId.value,
                itemKind = storedId.kind,
                itemId = storedId.value,
                addedAtMillis = clock(),
            )
        }
        val result = checkNotNull(transactionResult)
        val membership = checkNotNull(result.item.toMembershipOrNull()) {
            "Home Shelf DAO returned an unsupported identity"
        }
        if (result.inserted) {
            AddToShelfResult.Added(membership)
        } else {
            AddToShelfResult.AlreadyPresent(membership)
        }
    }

    override suspend fun remove(profileId: ProfileId, itemId: HomeShelfItemId): Boolean =
        withContext(ioDispatcher) {
            var removed = false
            runMutationTransaction {
                val storedId = itemId.resolveCurrent(gameIdentityDao).toStoredId()
                removed = homeShelfDao.deleteItem(profileId.value, storedId.kind, storedId.value) > 0
            }
            removed
        }

    override suspend fun move(
        profileId: ProfileId,
        itemId: HomeShelfItemId,
        before: HomeShelfItemId?,
    ): Boolean = withContext(ioDispatcher) {
        var moved = false
        runMutationTransaction {
            val storedId = itemId.resolveCurrent(gameIdentityDao).toStoredId()
            val storedBefore = before?.resolveCurrent(gameIdentityDao)?.toStoredId()
            moved = homeShelfDao.moveItem(
                profileId = profileId.value,
                itemKind = storedId.kind,
                itemId = storedId.value,
                beforeKind = storedBefore?.kind,
                beforeId = storedBefore?.value,
            )
        }
        moved
    }
}

private data class StoredHomeShelfItemId(val kind: String, val value: String)

private fun HomeShelfItemId.toStoredId(): StoredHomeShelfItemId = when (this) {
    is HomeShelfItemId.Emulated -> StoredHomeShelfItemId(kind.name, gameId.value)
    is HomeShelfItemId.AndroidGame -> StoredHomeShelfItemId(kind.name, packageName.value)
    is HomeShelfItemId.AndroidApp -> StoredHomeShelfItemId(kind.name, packageName.value)
}

private suspend fun HomeShelfItemId.resolveCurrent(identityDao: GameIdentityDao?): HomeShelfItemId =
    when (this) {
        is HomeShelfItemId.Emulated -> HomeShelfItemId.Emulated(
            GameId(identityDao?.resolveCurrentGameId(gameId.value) ?: gameId.value),
        )
        is HomeShelfItemId.AndroidGame,
        is HomeShelfItemId.AndroidApp -> this
    }

private fun HomeShelfItemEntity.toMembershipOrNull(): HomeShelfMembership? {
    val typedId = runCatching {
        when (itemKind) {
            HomeShelfItemKind.EMULATED_GAME.name -> HomeShelfItemId.Emulated(GameId(itemId))
            HomeShelfItemKind.ANDROID_GAME.name ->
                HomeShelfItemId.AndroidGame(AndroidPackageName(itemId))
            // The kind column is TEXT, so ANDROID_APP rows round-trip on the existing schema. An
            // older build reading a newer row falls through to `null` here — the membership stays on
            // disk untouched and simply doesn't project, which is the same fail-closed behaviour any
            // unknown kind already had.
            HomeShelfItemKind.ANDROID_APP.name ->
                HomeShelfItemId.AndroidApp(AndroidPackageName(itemId))
            else -> return null
        }
    }.getOrNull() ?: return null
    return HomeShelfMembership(
        itemId = typedId,
        rank = rank,
        addedAtMillis = addedAtMillis,
    )
}
