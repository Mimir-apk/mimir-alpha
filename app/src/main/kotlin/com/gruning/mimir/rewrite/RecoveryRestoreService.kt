package com.gruning.mimir.rewrite

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.ProfileId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemKind
import com.gruning.mimir.rewrite.core.model.migration.MigrationGameRecord
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveCancelledException
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveException
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveValidator
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryRestorePlan
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryRestoreResult
import com.gruning.mimir.rewrite.core.resolver.migration.MatchOutcome
import com.gruning.mimir.rewrite.core.resolver.migration.MigrationMatcher
import com.gruning.mimir.rewrite.core.resolver.migration.RecoveryRestoreLocalState
import com.gruning.mimir.rewrite.core.resolver.migration.RecoveryRestorePlanner
import com.gruning.mimir.rewrite.core.resolver.migration.RecoveryRestoreSeams
import com.gruning.mimir.rewrite.core.storage.MimirDatabase
import com.gruning.mimir.rewrite.core.storage.MimirDatabaseFactory
import com.gruning.mimir.rewrite.core.storage.RecoveryArchiveRestoreReader
import com.gruning.mimir.rewrite.core.storage.RecoveryArchiveV1SemanticValidator
import com.gruning.mimir.rewrite.core.storage.dao.HOME_SHELF_RANK_STEP
import com.gruning.mimir.rewrite.core.storage.entity.EmulatorOverrideEntity
import com.gruning.mimir.rewrite.core.storage.entity.HiddenItemEntity
import com.gruning.mimir.rewrite.core.storage.entity.HomeShelfItemEntity
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

internal const val RecoveryImportRootDirectory = "recovery-import"

internal class StagedRecoveryImport internal constructor(
    internal val runId: String,
    internal val runRoot: File,
    internal val runDirectory: File,
    internal val archive: File,
    internal val manifestSha256: String,
    internal val authorizationId: String,
    internal val rootName: String = RecoveryImportRootDirectory,
) {
    init {
        require(RecoveryRootNamePattern.matches(rootName))
    }
}

internal class PreparedRecoveryRestore internal constructor(
    val plan: RecoveryRestorePlan,
    internal val runId: String,
    internal val importAuthorizationId: String,
    internal val applyAuthorizationId: String,
    internal val manifestSha256: String,
)

internal data class RecoveryRestoreApplication(
    val result: RecoveryRestoreResult,
    /**
     * Mandatory pre-apply safety snapshot staged through [RecoveryArchiveService]. This is a point-in-time
     * copy of owner state, not a one-tap undo.
     */
    val safetyArchive: StagedRecoveryArchive,
)

internal class RecoveryRestoreEnvironment(
    val database: (Context) -> MimirDatabase,
    val importRootName: String = RecoveryImportRootDirectory,
    /** The mandatory pre-apply safety archive is staged through the existing exporter. */
    val safetyArchive: suspend (Context) -> Result<StagedRecoveryArchive>,
    val openImportStream: (Context, Uri) -> InputStream? = { context, uri ->
        context.contentResolver.openInputStream(uri)
    },
    internal val runInTransaction: suspend (
        MimirDatabase,
        suspend () -> RecoveryRestoreResult,
    ) -> RecoveryRestoreResult = { database, block ->
        database.withTransaction { block() }
    },
) {
    init {
        require(RecoveryRootNamePattern.matches(importRootName))
    }
}

internal object RecoveryRestoreService {
    private val singleFlight = Mutex()
    private val activeImport = java.util.concurrent.atomic.AtomicReference<ActiveRecoveryImport?>(null)

    private data class ActiveRecoveryImport(
        val runId: String,
        val importAuthorizationId: String,
        val manifestSha256: String,
        val pendingApplyAuthorizationId: String? = null,
        val retainedPlan: RecoveryRestorePlan? = null,
        val discardRequested: Boolean = false,
    )

    @SuppressLint("UsableSpace")
    suspend fun stageImport(
        context: Context,
        source: Uri,
    ): Result<StagedRecoveryImport> = stageImport(context, source, ProductionRecoveryRestoreEnvironment)

    @SuppressLint("UsableSpace")
    internal suspend fun stageImport(
        context: Context,
        source: Uri,
        environment: RecoveryRestoreEnvironment,
    ): Result<StagedRecoveryImport> = withContext(Dispatchers.IO) {
        if (!singleFlight.tryLock()) {
            return@withContext Result.failure(
                RecoveryArchiveException("A recovery restore operation is already running."),
            )
        }
        val app = context.applicationContext
        val root = File(app.cacheDir, environment.importRootName)
        val runId = UUID.randomUUID().toString()
        val runDirectory = File(root, runId)
        var registeredImport: ActiveRecoveryImport? = null
        try {
            currentCoroutineContext().ensureActive()
            if (activeImport.get() != null) {
                throw RecoveryArchiveException(
                    "Finish or cancel the current recovery restore before choosing another archive.",
                )
            }
            if (!isSafeCreatedDocumentUri(source.toString())) {
                throw RecoveryArchiveException("Choose a recovery archive from Android’s document picker.")
            }
            if (app.cacheDir.usableSpace < RecoveryMinimumFreeBytes) {
                throw RecoveryArchiveException("There is not enough free space to stage a recovery import.")
            }
            if (!runDirectory.mkdirs()) {
                throw RecoveryArchiveException("Couldn’t create the recovery import staging directory.")
            }
            val archive = File(runDirectory, RecoveryArchiveFileName)
            val coroutineContext = currentCoroutineContext()
            val shouldContinue = { coroutineContext.isActive }
            val input = environment.openImportStream(app, source)
                ?: throw RecoveryArchiveException("Couldn’t read the selected recovery archive.")
            input.use { stream ->
                copyRecoveryArchiveStream(stream, archive)
            }
            if (!isExactStagedImportArchive(
                    app,
                    root,
                    runDirectory,
                    archive,
                    runId,
                    environment.importRootName,
                )
            ) {
                throw RecoveryArchiveException("Staged recovery import archive failed ownership checks.")
            }
            val structural = RecoveryArchiveValidator.validate(archive, shouldContinue).getOrThrow()
            requireCurrentRecoverySemantics(
                RecoveryArchiveV1SemanticValidator.validate(structural, shouldContinue).getOrThrow(),
            )
            val authorizationId = UUID.randomUUID().toString()
            val active = ActiveRecoveryImport(
                runId = runId,
                importAuthorizationId = authorizationId,
                manifestSha256 = structural.manifestSha256,
            )
            if (!activeImport.compareAndSet(null, active)) {
                throw RecoveryArchiveException(
                    "Finish or cancel the current recovery restore before choosing another archive.",
                )
            }
            registeredImport = active
            Result.success(
                StagedRecoveryImport(
                    runId = runId,
                    runRoot = root,
                    runDirectory = runDirectory,
                    archive = archive,
                    manifestSha256 = structural.manifestSha256,
                    authorizationId = authorizationId,
                    rootName = environment.importRootName,
                ),
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            deleteOwnedRunDirectory(root, runDirectory)
            registeredImport?.let { activeImport.compareAndSet(it, null) }
            throw cancelled
        } catch (cancelled: RecoveryArchiveCancelledException) {
            deleteOwnedRunDirectory(root, runDirectory)
            registeredImport?.let { activeImport.compareAndSet(it, null) }
            if (!currentCoroutineContext().isActive) {
                throw kotlinx.coroutines.CancellationException(
                    "Recovery import staging was cancelled.",
                    cancelled,
                )
            }
            Result.failure(cancelled)
        } catch (error: Throwable) {
            deleteOwnedRunDirectory(root, runDirectory)
            registeredImport?.let { activeImport.compareAndSet(it, null) }
            Result.failure(error)
        } finally {
            singleFlight.unlock()
        }
    }

    suspend fun plan(
        context: Context,
        staged: StagedRecoveryImport,
        hasGame: suspend (GameId) -> Boolean,
        isEmulatorRecognized: suspend (GameId, MigrationGameRecord, String) -> Boolean,
        isPackageInstalled: (String) -> Boolean = { packageName ->
            runCatching {
                context.applicationContext.packageManager.getPackageInfo(packageName, 0)
                true
            }.getOrDefault(false)
        },
    ): Result<PreparedRecoveryRestore> = plan(
        context = context,
        staged = staged,
        environment = ProductionRecoveryRestoreEnvironment,
        hasGame = hasGame,
        isEmulatorRecognized = isEmulatorRecognized,
        isPackageInstalled = isPackageInstalled,
    )

    internal suspend fun plan(
        context: Context,
        staged: StagedRecoveryImport,
        environment: RecoveryRestoreEnvironment,
        hasGame: suspend (GameId) -> Boolean,
        isEmulatorRecognized: suspend (GameId, MigrationGameRecord, String) -> Boolean,
        isPackageInstalled: (String) -> Boolean,
    ): Result<PreparedRecoveryRestore> = withContext(Dispatchers.IO) {
        if (!singleFlight.tryLock()) {
            return@withContext Result.failure(
                RecoveryArchiveException("A recovery restore operation is already running."),
            )
        }
        try {
            if (!isExactStagedImportArchive(
                    context.applicationContext,
                    staged.runRoot,
                    staged.runDirectory,
                    staged.archive,
                    staged.runId,
                    staged.rootName,
                )
            ) {
                return@withContext Result.failure(
                    RecoveryArchiveException("Recovery import staging is no longer valid."),
                )
            }
            val active = requireActiveImport(staged).getOrElse {
                return@withContext Result.failure(it)
            }
            val coroutineContext = currentCoroutineContext()
            val shouldContinue = { coroutineContext.isActive }
            val structural = RecoveryArchiveValidator.validate(staged.archive, shouldContinue).getOrThrow()
            requireMatchingManifest(staged, structural.manifestSha256)
            requireCurrentRecoverySemantics(
                RecoveryArchiveV1SemanticValidator.validate(structural, shouldContinue).getOrThrow(),
            )
            val snapshot = RecoveryArchiveRestoreReader.read(structural, shouldContinue).getOrThrow()
            val database = environment.database(context.applicationContext)
            val matcher = buildMigrationMatcher(database, hasGame)
            val planner = RecoveryRestorePlanner(matcher, shelfRankStep = HOME_SHELF_RANK_STEP)
            val local = buildRestoreLocalState(database, isPackageInstalled)
            val preview = planner.preview(snapshot, local, isEmulatorRecognized)
            val retainedPlan = snapshotPlan(preview)
            val applyAuthorizationId = UUID.randomUUID().toString()
            val prepared = PreparedRecoveryRestore(
                plan = snapshotPlan(retainedPlan),
                runId = staged.runId,
                importAuthorizationId = staged.authorizationId,
                applyAuthorizationId = applyAuthorizationId,
                manifestSha256 = structural.manifestSha256,
            )
            val preparedActive = active.copy(
                pendingApplyAuthorizationId = applyAuthorizationId,
                retainedPlan = retainedPlan,
            )
            if (!activeImport.compareAndSet(active, preparedActive)) {
                throw RecoveryArchiveException("Recovery import authority changed while planning.")
            }
            Result.success(prepared)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            discardImportWhileLocked(staged)
            throw cancelled
        } catch (cancelled: RecoveryArchiveCancelledException) {
            discardImportWhileLocked(staged)
            if (!currentCoroutineContext().isActive) {
                throw kotlinx.coroutines.CancellationException(
                    "Recovery restore planning was cancelled.",
                    cancelled,
                )
            }
            Result.failure(cancelled)
        } catch (error: Throwable) {
            Result.failure(error)
        } finally {
            discardRequestedImportWhileLocked(staged)
            singleFlight.unlock()
        }
    }

    suspend fun apply(
        context: Context,
        staged: StagedRecoveryImport,
        prepared: PreparedRecoveryRestore,
        isEmulatorRecognized: suspend (GameId, String) -> Boolean = { _, emulatorId ->
            emulatorId.isBlank()
        },
        isPackageInstalled: (String) -> Boolean = { packageName ->
            runCatching {
                context.applicationContext.packageManager.getPackageInfo(packageName, 0)
                true
            }.getOrDefault(false)
        },
    ): Result<RecoveryRestoreApplication> = apply(
        context = context,
        staged = staged,
        prepared = prepared,
        environment = ProductionRecoveryRestoreEnvironment,
        isEmulatorRecognized = isEmulatorRecognized,
        isPackageInstalled = isPackageInstalled,
    )

    internal suspend fun apply(
        context: Context,
        staged: StagedRecoveryImport,
        prepared: PreparedRecoveryRestore,
        environment: RecoveryRestoreEnvironment,
        isEmulatorRecognized: suspend (GameId, String) -> Boolean = { _, emulatorId ->
            emulatorId.isBlank()
        },
        isPackageInstalled: (String) -> Boolean = { false },
    ): Result<RecoveryRestoreApplication> = withContext(Dispatchers.IO) {
        if (!singleFlight.tryLock()) {
            return@withContext Result.failure(
                RecoveryArchiveException("A recovery restore operation is already running."),
            )
        }
        try {
            if (!isExactStagedImportArchive(
                    context.applicationContext,
                    staged.runRoot,
                    staged.runDirectory,
                    staged.archive,
                    staged.runId,
                    staged.rootName,
                )
            ) {
                return@withContext Result.failure(
                    RecoveryArchiveException("Recovery import staging is no longer valid."),
                )
            }
            val active = requirePreparedImport(staged, prepared).getOrElse {
                return@withContext Result.failure(it)
            }
            val coroutineContext = currentCoroutineContext()
            val shouldContinue = { coroutineContext.isActive }
            val structural = RecoveryArchiveValidator.validate(
                staged.archive,
                shouldContinue,
            ).getOrThrow()
            requireMatchingManifest(staged, structural.manifestSha256)
            requireCurrentRecoverySemantics(
                RecoveryArchiveV1SemanticValidator.validate(structural, shouldContinue).getOrThrow(),
            )
            val retainedPlan = active.retainedPlan
                ?: return@withContext Result.failure(
                    RecoveryArchiveException("Recovery restore approval has no bound dry-run plan."),
                )
            if (
                prepared.manifestSha256 != structural.manifestSha256 ||
                snapshotPlan(prepared.plan) != retainedPlan ||
                !activeImport.compareAndSet(
                    active,
                    active.copy(
                        pendingApplyAuthorizationId = null,
                        retainedPlan = null,
                    ),
                )
            ) {
                return@withContext Result.failure(
                    RecoveryArchiveException("Recovery restore approval is stale or was already consumed."),
                )
            }
            applyAfterSafetyArchive(
                safetyArchive = { environment.safetyArchive(context.applicationContext) },
                applyTransaction = {
                    val database = environment.database(context.applicationContext)
                    environment.runInTransaction(database) {
                        val planner = RecoveryRestorePlanner(
                            matcher = MigrationMatcher(gameExists = { true }),
                            shelfRankStep = HOME_SHELF_RANK_STEP,
                        )
                        planner.apply(
                            retainedPlan,
                            buildRestoreSeams(
                                database = database,
                                isEmulatorRecognized = isEmulatorRecognized,
                                isPackageInstalled = isPackageInstalled,
                            ),
                        )
                    }
                },
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            discardImportWhileLocked(staged)
            throw cancelled
        } catch (cancelled: RecoveryArchiveCancelledException) {
            discardImportWhileLocked(staged)
            if (!currentCoroutineContext().isActive) {
                throw kotlinx.coroutines.CancellationException(
                    "Recovery restore apply was cancelled.",
                    cancelled,
                )
            }
            Result.failure(cancelled)
        } catch (error: Throwable) {
            Result.failure(error)
        } finally {
            discardRequestedImportWhileLocked(staged)
            singleFlight.unlock()
        }
    }

    internal suspend fun applyAfterSafetyArchive(
        safetyArchive: suspend () -> Result<StagedRecoveryArchive>,
        applyTransaction: suspend () -> RecoveryRestoreResult,
    ): Result<RecoveryRestoreApplication> {
        val safety = safetyArchive().getOrElse { error ->
            return Result.failure(
                RecoveryArchiveException(
                    "Recovery restore refused because the mandatory safety archive couldn’t be created.",
                    error,
                ),
            )
        }
        return try {
            Result.success(
                RecoveryRestoreApplication(
                    result = applyTransaction(),
                    safetyArchive = safety,
                ),
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    fun discardImport(staged: StagedRecoveryImport) {
        if (!singleFlight.tryLock()) {
            requestDiscard(staged)
            return
        }
        try {
            discardImportWhileLocked(staged)
        } finally {
            singleFlight.unlock()
        }
    }

    private fun requestDiscard(staged: StagedRecoveryImport) {
        while (true) {
            val active = activeImport.get() ?: return
            if (
                active.runId != staged.runId ||
                active.importAuthorizationId != staged.authorizationId
            ) {
                return
            }
            if (active.discardRequested) return
            if (activeImport.compareAndSet(active, active.copy(discardRequested = true))) return
        }
    }

    private fun discardRequestedImportWhileLocked(staged: StagedRecoveryImport) {
        val active = activeImport.get()
        if (
            active?.runId == staged.runId &&
            active.importAuthorizationId == staged.authorizationId &&
            active.discardRequested
        ) {
            discardImportWhileLocked(staged)
        }
    }

    private fun discardImportWhileLocked(staged: StagedRecoveryImport) {
        deleteOwnedRunDirectory(staged.runRoot, staged.runDirectory)
        val active = activeImport.get()
        if (
            active?.runId == staged.runId &&
            active.importAuthorizationId == staged.authorizationId
        ) {
            activeImport.compareAndSet(active, null)
        }
    }

    fun sweepStaleImports(
        context: Context,
        olderThanUtcMillis: Long,
    ) = sweepStaleImports(
        context = context,
        olderThanUtcMillis = olderThanUtcMillis,
        importRootName = RecoveryImportRootDirectory,
    )

    internal fun sweepStaleImports(
        context: Context,
        olderThanUtcMillis: Long,
        importRootName: String,
    ) {
        if (!singleFlight.tryLock()) return
        try {
            val root = File(context.applicationContext.cacheDir, importRootName)
            val pendingRunId = activeImport.get()?.runId
            root.listFiles()
                .orEmpty()
                .filter { candidate ->
                    candidate.isDirectory &&
                        candidate.parentFile == root &&
                        runCatching { UUID.fromString(candidate.name) }.isSuccess &&
                        candidate.name != pendingRunId &&
                        candidate.lastModified() < olderThanUtcMillis
                }
                .forEach { deleteOwnedRunDirectory(root, it) }
        } finally {
            singleFlight.unlock()
        }
    }

    internal fun buildMigrationMatcher(
        database: MimirDatabase,
        hasGame: suspend (GameId) -> Boolean,
    ): MigrationMatcher = MigrationMatcher(
        resolveCurrentGameId = { gameId ->
            GameId(resolveCurrentGameId(database, gameId.value))
        },
        gameExists = hasGame,
    )

    internal fun buildRestoreLocalState(
        database: MimirDatabase,
        isPackageInstalled: (String) -> Boolean,
    ): RecoveryRestoreLocalState = object : RecoveryRestoreLocalState {
        override suspend fun libraryHasGames(): Boolean = database.gameDao().anyGamesExist()

        override suspend fun hasFavorite(gameId: GameId): Boolean =
            database.favoriteDao().isFavorite(resolveCurrentGameId(database, gameId.value))

        override suspend fun hasHidden(gameId: GameId): Boolean =
            database.hiddenItemDao().byGame(resolveCurrentGameId(database, gameId.value)) != null

        override suspend fun hasEmulatorOverride(gameId: GameId): Boolean =
            database.emulatorOverrideDao().forGame(resolveCurrentGameId(database, gameId.value)) != null

        override suspend fun hasShelfItem(profileId: ProfileId, itemId: HomeShelfItemId): Boolean =
            normalizeShelfItemId(database, itemId)?.let { currentItemId ->
                database.homeShelfDao().item(
                    profileId.value,
                    homeShelfKind(currentItemId),
                    homeShelfItemValue(currentItemId),
                ) != null
            } ?: false

        override suspend fun maxShelfRank(profileId: ProfileId): Long? =
            database.homeShelfDao().maxRank(profileId.value)

        override suspend fun isPackageInstalled(packageName: String): Boolean =
            isPackageInstalled(packageName)
    }

    internal fun buildRestoreSeams(
        database: MimirDatabase,
        isEmulatorRecognized: suspend (GameId, String) -> Boolean = { _, emulatorId ->
            emulatorId.isBlank()
        },
        isPackageInstalled: (String) -> Boolean = { false },
    ): RecoveryRestoreSeams =
        buildRestoreSeams(
            RestoreWriteDaos(
                gameDao = database.gameDao(),
                favoriteDao = database.favoriteDao(),
                hiddenItemDao = database.hiddenItemDao(),
                emulatorOverrideDao = database.emulatorOverrideDao(),
                homeShelfDao = database.homeShelfDao(),
                resolveCurrentGameId = { gameId -> database.gameIdentityDao().resolveCurrentGameId(gameId) },
                isEmulatorRecognized = isEmulatorRecognized,
                isPackageInstalled = isPackageInstalled,
            ),
        )

    internal fun buildRestoreSeams(daos: RestoreWriteDaos): RecoveryRestoreSeams =
        object : RecoveryRestoreSeams {
            override suspend fun currentMaxShelfRank(profileId: ProfileId): Long? =
                daos.homeShelfDao.maxRank(profileId.value)

            override suspend fun insertFavorite(gameId: GameId, favoritedAtMillis: Long): Boolean {
                val current = daos.resolveCurrentGameId(gameId.value)
                if (daos.gameDao.membersOfGame(current).isEmpty()) return false
                return daos.favoriteDao.promoteGroupIfAbsent(current, favoritedAtMillis) > 0L
            }

            override suspend fun insertHidden(gameId: GameId, hiddenAtMillis: Long): Boolean {
                val current = daos.resolveCurrentGameId(gameId.value)
                if (daos.hiddenItemDao.byGame(current) != null) return false
                val primary = daos.gameDao.membersOfGame(current).firstOrNull() ?: return false
                daos.hiddenItemDao.upsert(
                    HiddenItemEntity(
                        gameId = current,
                        hidden = true,
                        reason = "USER_DISMISSED",
                        duplicateGroupId = primary?.duplicateGroupId,
                        isPrimaryMember = true,
                        hiddenAtMillis = hiddenAtMillis,
                    ),
                )
                return true
            }

            override suspend fun insertEmulatorOverride(
                gameId: GameId,
                emulatorId: String,
                setAtMillis: Long,
            ): Boolean {
                val current = daos.resolveCurrentGameId(gameId.value)
                if (daos.gameDao.membersOfGame(current).isEmpty()) return false
                if (daos.emulatorOverrideDao.forGame(current) != null) return false
                if (
                    emulatorId.isNotBlank() &&
                    !daos.isEmulatorRecognized(GameId(current), emulatorId)
                ) {
                    return false
                }
                daos.emulatorOverrideDao.set(
                    EmulatorOverrideEntity(
                        gameId = current,
                        emulatorId = emulatorId,
                        setAtMillis = setAtMillis,
                    ),
                )
                return true
            }

            override suspend fun insertShelfItem(
                profileId: ProfileId,
                itemId: HomeShelfItemId,
                rank: Long,
                addedAtMillis: Long,
            ): Boolean {
                val currentItemId = when (itemId) {
                    is HomeShelfItemId.Emulated -> {
                        val current = daos.resolveCurrentGameId(itemId.gameId.value)
                        if (daos.gameDao.membersOfGame(current).isEmpty()) return false
                        HomeShelfItemId.Emulated(GameId(current))
                    }
                    is HomeShelfItemId.AndroidGame -> {
                        if (!daos.isPackageInstalled(itemId.packageName.value)) return false
                        itemId
                    }
                    is HomeShelfItemId.AndroidApp -> return false
                }
                val kind = homeShelfKind(currentItemId)
                val value = homeShelfItemValue(currentItemId)
                if (daos.homeShelfDao.item(profileId.value, kind, value) != null) return false
                return daos.homeShelfDao.insertItem(
                    HomeShelfItemEntity(
                        profileId = profileId.value,
                        itemKind = kind,
                        itemId = value,
                        rank = rank,
                        addedAtMillis = addedAtMillis,
                    ),
                ) != -1L
            }
        }

    internal data class RestoreWriteDaos(
        val gameDao: com.gruning.mimir.rewrite.core.storage.dao.GameDao,
        val favoriteDao: com.gruning.mimir.rewrite.core.storage.dao.FavoriteDao,
        val hiddenItemDao: com.gruning.mimir.rewrite.core.storage.dao.HiddenItemDao,
        val emulatorOverrideDao: com.gruning.mimir.rewrite.core.storage.dao.EmulatorOverrideDao,
        val homeShelfDao: com.gruning.mimir.rewrite.core.storage.dao.HomeShelfDao,
        val resolveCurrentGameId: suspend (String) -> String,
        val isEmulatorRecognized: suspend (GameId, String) -> Boolean = { _, emulatorId ->
            emulatorId.isBlank()
        },
        val isPackageInstalled: (String) -> Boolean = { false },
    )

    private val ProductionRecoveryRestoreEnvironment = RecoveryRestoreEnvironment(
        database = MimirDatabaseFactory::get,
        importRootName = RecoveryImportRootDirectory,
        safetyArchive = { context -> RecoveryArchiveService.stage(context) },
    )

    private suspend fun resolveCurrentGameId(database: MimirDatabase, gameId: String): String =
        database.gameIdentityDao().resolveCurrentGameId(gameId)

    private suspend fun normalizeShelfItemId(
        database: MimirDatabase,
        itemId: HomeShelfItemId,
    ): HomeShelfItemId? = when (itemId) {
        is HomeShelfItemId.Emulated -> {
            val current = resolveCurrentGameId(database, itemId.gameId.value)
            if (database.gameDao().membersOfGame(current).isEmpty()) {
                null
            } else {
                HomeShelfItemId.Emulated(GameId(current))
            }
        }
        is HomeShelfItemId.AndroidGame -> itemId
        is HomeShelfItemId.AndroidApp -> null
    }

    private fun homeShelfKind(itemId: HomeShelfItemId): String = when (itemId) {
        is HomeShelfItemId.Emulated -> HomeShelfItemKind.EMULATED_GAME.name
        is HomeShelfItemId.AndroidGame -> HomeShelfItemKind.ANDROID_GAME.name
        is HomeShelfItemId.AndroidApp -> HomeShelfItemKind.ANDROID_APP.name
    }

    private fun homeShelfItemValue(itemId: HomeShelfItemId): String = when (itemId) {
        is HomeShelfItemId.Emulated -> itemId.gameId.value
        is HomeShelfItemId.AndroidGame -> itemId.packageName.value
        is HomeShelfItemId.AndroidApp -> itemId.packageName.value
    }

    private fun isExactStagedImportArchive(
        context: Context,
        runRoot: File,
        runDirectory: File,
        archive: File,
        runId: String,
        rootName: String,
    ): Boolean = runCatching {
        UUID.fromString(runId)
        val canonicalRoot = runRoot.canonicalFile
        val expectedRoot = File(context.cacheDir, rootName).canonicalFile
        val canonicalRun = runDirectory.canonicalFile
        val canonicalArchive = archive.canonicalFile
        canonicalRoot == expectedRoot &&
            canonicalRoot.name == rootName &&
            !Files.isSymbolicLink(runRoot.toPath()) &&
            isOwnedRunPath(canonicalRoot, canonicalRun) &&
            canonicalRun.parentFile == canonicalRoot &&
            canonicalRun.name == runId &&
            !Files.isSymbolicLink(runDirectory.toPath()) &&
            !Files.isSymbolicLink(archive.toPath()) &&
            canonicalArchive == File(canonicalRun, RecoveryArchiveFileName).canonicalFile &&
            Files.isRegularFile(canonicalArchive.toPath(), NOFOLLOW_LINKS) &&
            RecoveryRootNamePattern.matches(rootName)
    }.getOrDefault(false)

    private fun requireActiveImport(
        staged: StagedRecoveryImport,
    ): Result<ActiveRecoveryImport> {
        val active = activeImport.get()
        return if (
            active?.runId == staged.runId &&
            active.importAuthorizationId == staged.authorizationId &&
            active.manifestSha256 == staged.manifestSha256 &&
            !active.discardRequested
        ) {
            Result.success(active)
        } else {
            Result.failure(
                RecoveryArchiveException("Recovery import authority is missing or no longer current."),
            )
        }
    }

    private fun requirePreparedImport(
        staged: StagedRecoveryImport,
        prepared: PreparedRecoveryRestore,
    ): Result<ActiveRecoveryImport> {
        val active = activeImport.get()
        return if (
            active?.runId == staged.runId &&
            active.importAuthorizationId == staged.authorizationId &&
            active.manifestSha256 == staged.manifestSha256 &&
            active.pendingApplyAuthorizationId == prepared.applyAuthorizationId &&
            !active.discardRequested &&
            prepared.runId == staged.runId &&
            prepared.importAuthorizationId == staged.authorizationId &&
            prepared.manifestSha256 == staged.manifestSha256
        ) {
            Result.success(active)
        } else {
            Result.failure(
                RecoveryArchiveException("Recovery restore approval is stale or was already consumed."),
            )
        }
    }

    private fun requireMatchingManifest(
        staged: StagedRecoveryImport,
        manifestSha256: String,
    ) {
        if (manifestSha256 != staged.manifestSha256) {
            throw RecoveryArchiveException("The staged recovery archive changed after it was validated.")
        }
    }

    private fun snapshotPlan(plan: RecoveryRestorePlan): RecoveryRestorePlan = plan.copy(
        favorites = plan.favorites.copy(decisions = plan.favorites.decisions.toList()),
        hidden = plan.hidden.copy(decisions = plan.hidden.decisions.toList()),
        overrides = plan.overrides.copy(decisions = plan.overrides.decisions.toList()),
        shelf = plan.shelf.copy(decisions = plan.shelf.decisions.toList()),
        sourceRootHints = plan.sourceRootHints.toList(),
        notRestored = plan.notRestored.toList(),
    )

    private fun requireCurrentRecoverySemantics(
        report: com.gruning.mimir.rewrite.core.storage.RecoveryArchiveSemanticReport,
    ) {
        if (report.newerSchema) {
            throw RecoveryArchiveException(
                "This recovery archive uses a newer Room schema and cannot be restored by this build.",
            )
        }
    }
}
