package com.gruning.mimir.rewrite

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.gruning.mimir.rewrite.core.model.EmulatorId
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.migration.MigrationBundleCodec
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveException
import com.gruning.mimir.rewrite.core.resolver.migration.MigrationImporter
import com.gruning.mimir.rewrite.core.resolver.migration.MigrationMatcher
import com.gruning.mimir.rewrite.core.resolver.migration.MigrationSeams
import com.gruning.mimir.rewrite.core.storage.MimirDatabaseFactory
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val BackupFileReadFailureCopy = "Choose a Mimir backup file."
internal const val UnsupportedBackupCopy = "That file isn’t a supported Mimir backup."
private const val RecoveryStagingSweepAgeMillis = 24L * 60L * 60L * 1000L

/** Migration/import and backup/export jobs with their modal lifetime kept out of MimirApp. */
internal class MimirMigrationCoordinator(
    val state: MigrationUiState?,
    val openPicker: () -> Unit,
    val exportBackup: () -> Unit,
    val confirm: () -> Unit,
    val close: () -> Unit,
    val openRecoveryRestorePicker: () -> Unit,
    val exportRecoveryArchive: () -> Unit,
)

@Composable
internal fun rememberMimirMigrationCoordinator(core: MimirAppCoreCoordinator): MimirMigrationCoordinator {
    val context = core.context
    val repository = core.repository
    val launchController = core.launchController
    val authority = core.focusAuthority
    val scope = rememberCoroutineScope()
    val importer = remember(repository, context) {
        MigrationImporter(
            MigrationMatcher(
                resolveCurrentGameId = { gameId ->
                    GameId(
                        MimirDatabaseFactory.get(context).gameIdentityDao()
                            .resolveCurrentGameId(gameId.value),
                    )
                },
                gameExists = { gameId -> repository.hasGame(gameId) },
            ),
        )
    }
    val seams = remember(repository, launchController, context) {
        object : MigrationSeams {
            override suspend fun setFavorite(gameId: GameId) {
                repository.setFavorite(gameId, true)
            }

            override suspend fun setHidden(gameId: GameId) {
                repository.setHidden(gameId, true)
            }

            override suspend fun setEmulatorOverride(gameId: GameId, emulatorId: String) {
                launchController.setEmulatorOverride(gameId, EmulatorId(emulatorId))
            }

            override fun applyTheme(themeStorageKey: String) {
                ThemeStore.setTheme(context, MimirThemeId.fromStorageKey(themeStorageKey))
            }

            // Saved achievement counts go to the ledger under the account they belong to (found when that account signs in); additive, never over what is held.
            override suspend fun restoreAchievements(accounts: List<com.gruning.mimir.rewrite.core.model.migration.MigrationAchievementAccount>): Int =
                withContext(Dispatchers.IO) {
                    runCatching {
                        restoreAchievementsFromBackup(RetroAchievementsLedgers.forContext(context), accounts, System.currentTimeMillis())
                    }.getOrDefault(0)
                }
        }
    }
    var state by remember { mutableStateOf<MigrationUiState?>(null) }
    val job = remember { mutableStateOf<Job?>(null) }
    var stagedImport by remember { mutableStateOf<StagedRecoveryImport?>(null) }
    var stagedExport by remember { mutableStateOf<StagedRecoveryArchive?>(null) }
    var pendingExportRequest by remember { mutableStateOf<RecoveryDocumentCreationRequest?>(null) }

    LaunchedEffect(Unit) {
        val recovered = withContext(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - RecoveryStagingSweepAgeMillis
            val port = AndroidRecoveryPublicationPort(context.applicationContext)
            val journal = AndroidRecoveryPublicationJournal(context.applicationContext)
            val recovered = RecoveryArchiveService.recoverPendingPublication(
                RecoveryArchiveService.runRoot(context.applicationContext),
                port,
                journal,
            )
            RecoveryArchiveService.sweepStaleStaging(context.applicationContext, cutoff, journal)
            RecoveryRestoreService.sweepStaleImports(context.applicationContext, cutoff)
            recovered
        }
        // N5 (2026-08-12): this runs on every app start; a null result (nothing pending) is the
        // overwhelming common case and stays silent. A non-null result means the player's LAST
        // recovery-archive export was interrupted (crash/kill) and is only now being settled — the
        // whole point of that file is disaster recovery, so whether it can be trusted is worth a
        // startup toast, not a discard. Zero new copy: reuses recoveryExportToastCopy verbatim,
        // the exact sentence the interactive export path already shows for the same verdicts.
        if (recovered != null) {
            Toast.makeText(
                context,
                recoveryExportToastCopy(recovered),
                if (recovered.verdict == RecoveryPublicationVerdict.WRITTEN_UNVERIFIED) {
                    Toast.LENGTH_LONG
                } else {
                    Toast.LENGTH_SHORT
                },
            ).show()
        }
    }

    val close = {
        job.value?.cancel()
        job.value = null
        stagedImport?.let(RecoveryRestoreService::discardImport)
        stagedImport = null
        if (authority.state.value.scope == MigrationScope) authority.popScope()
        state = null
    }
    val confirm = {
        when (val current = state) {
            is MigrationUiState.Ready -> {
                state = MigrationUiState.Applying
                job.value = scope.launch {
                    val result = importer.apply(current.plan, seams)
                    if (isActive) state = MigrationUiState.Done(result)
                }
            }
            is MigrationUiState.RestoreReady -> {
                val staged = stagedImport
                val prepared = current.prepared
                if (staged != null) {
                    state = MigrationUiState.RestoreApplying
                    job.value = scope.launch {
                        val application = RecoveryRestoreService.apply(
                            context = context,
                            staged = staged,
                            prepared = prepared,
                            isEmulatorRecognized = { gameId, emulatorId ->
                                if (emulatorId.isBlank()) {
                                    true
                                } else {
                                    val platformId = withContext(Dispatchers.IO) {
                                        MimirDatabaseFactory.get(context).gameDao()
                                            .platformIdForGame(gameId.value)
                                    }
                                    platformId != null &&
                                        launchController.openWithOptions(gameId, PlatformId(platformId))
                                            .candidates.any { it.emulatorId.value == emulatorId }
                                }
                            },
                        )
                        if (!isActive) return@launch
                        state = application.fold(
                            onSuccess = { MigrationUiState.RestoreDone(it.result) },
                            onFailure = { MigrationUiState.Failed(recoveryRestoreFailureCopy(it)) },
                        )
                    }
                }
            }
            else -> Unit
        }
        Unit
    }

    val openBundleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            if (authority.state.value.scope != MigrationScope) authority.pushScope(MigrationScope)
            state = MigrationUiState.Reading
            job.value = scope.launch {
                // The read of the whole backup file and its parse run off the main thread (audit 2026-10-14, RC-29, S-53 (1)): they sat on the UI thread while Reading spun.
                // The preview below is a suspend call that dispatches its own work.
                val parsed = withContext(Dispatchers.IO) {
                    val text = runCatching {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    }.getOrNull()
                    if (text.isNullOrBlank()) null else MigrationBundleCodec.parse(text)
                }
                val next = if (parsed == null) {
                    MigrationUiState.Failed(BackupFileReadFailureCopy)
                } else {
                    parsed.fold(
                        onSuccess = { bundle ->
                            MigrationUiState.Ready(
                                importer.preview(
                                    bundle = bundle,
                                    isKnownTheme = MimirThemeId::isKnownStorageKey,
                                    isEmulatorRecognized = { gameId, record, emulatorId ->
                                        launchController.openWithOptions(gameId, PlatformId(record.platformId))
                                            .candidates.any { it.emulatorId.value == emulatorId }
                                    },
                                ),
                            )
                        },
                        onFailure = { MigrationUiState.Failed(UnsupportedBackupCopy) },
                    )
                }
                if (isActive) state = next
            }
        }
    }
    val createBundleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    ConfigBackupStore.writeBundle(context, uri, ConfigBackupStore.buildBundle(context))
                }
                Toast.makeText(
                    context,
                    if (result.isSuccess) "Backup saved." else "Couldn’t save the backup.",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
    val restoreArchiveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            if (authority.state.value.scope != MigrationScope) authority.pushScope(MigrationScope)
            state = MigrationUiState.Reading
            job.value = scope.launch {
                val next = RecoveryRestoreService.stageImport(context, uri).fold(
                    onSuccess = { staged ->
                        stagedImport = staged
                        RecoveryRestoreService.plan(
                            context = context,
                            staged = staged,
                            hasGame = repository::hasGame,
                            isEmulatorRecognized = { gameId, record, emulatorId ->
                                launchController.openWithOptions(gameId, PlatformId(record.platformId))
                                    .candidates.any { it.emulatorId.value == emulatorId }
                            },
                        ).fold(
                            onSuccess = { prepared ->
                                MigrationUiState.RestoreReady(prepared)
                            },
                            onFailure = { MigrationUiState.Failed(recoveryRestoreFailureCopy(it)) },
                        )
                    },
                    onFailure = { MigrationUiState.Failed(recoveryRestoreFailureCopy(it)) },
                )
                if (isActive) state = next
            }
        }
    }
    val recoveryExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val request = pendingExportRequest
        val staged = stagedExport
        pendingExportRequest = null
        if (uri == null) {
            if (request != null) {
                RecoveryArchiveService.cancelDocumentCreation(request)
            }
            staged?.let { deleteOwnedRunDirectory(it.runRoot, it.runDirectory) }
            stagedExport = null
            return@rememberLauncherForActivityResult
        }
        if (request == null || staged == null) return@rememberLauncherForActivityResult
        scope.launch {
            val publication = withContext(Dispatchers.IO) {
                val created = RecoveryArchiveService.acceptCreatedDocument(request, uri.toString())
                    .getOrElse { error ->
                        return@withContext RecoveryPublicationResult(
                            verdict = RecoveryPublicationVerdict.FAILED,
                            destinationUri = uri.toString(),
                            destinationMayRemain = true,
                        ) to error
                    }
                RecoveryArchiveService.publish(
                    staged = staged,
                    createdDocument = created,
                    port = AndroidRecoveryPublicationPort(context.applicationContext),
                    journal = AndroidRecoveryPublicationJournal(context.applicationContext),
                ) to null
            }
            val (result, acceptError) = publication
            Toast.makeText(
                context,
                recoveryExportToastCopy(result, acceptError),
                if (result.verdict == RecoveryPublicationVerdict.WRITTEN_UNVERIFIED) {
                    Toast.LENGTH_LONG
                } else {
                    Toast.LENGTH_SHORT
                },
            ).show()
            when (result.verdict) {
                RecoveryPublicationVerdict.VERIFIED -> {
                    withContext(Dispatchers.IO) {
                        deleteOwnedRunDirectory(staged.runRoot, staged.runDirectory)
                    }
                    stagedExport = null
                }
                RecoveryPublicationVerdict.WRITTEN_UNVERIFIED -> Unit
                RecoveryPublicationVerdict.FAILED, RecoveryPublicationVerdict.CANCELLED -> {
                    if (!result.destinationMayRemain) {
                        deleteOwnedRunDirectory(staged.runRoot, staged.runDirectory)
                        stagedExport = null
                    }
                }
            }
        }
    }

    return MimirMigrationCoordinator(
        state = state,
        openPicker = { openBundleLauncher.launch(arrayOf("*/*")) },
        exportBackup = {
            createBundleLauncher.launch(ConfigBackupStore.SUGGESTED_FILE_NAME)
            Unit
        },
        confirm = confirm,
        close = close,
        openRecoveryRestorePicker = {
            restoreArchiveLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
        },
        exportRecoveryArchive = {
            scope.launch {
                val stagedResult = withContext(Dispatchers.IO) {
                    RecoveryArchiveService.stage(context)
                }
                stagedResult.fold(
                    onSuccess = { staged ->
                        stagedExport = staged
                        RecoveryArchiveService.beginDocumentCreation(staged).fold(
                            onSuccess = { request ->
                                pendingExportRequest = request
                                recoveryExportLauncher.launch(request.suggestedFileName)
                            },
                            onFailure = { error ->
                                deleteOwnedRunDirectory(staged.runRoot, staged.runDirectory)
                                stagedExport = null
                                Toast.makeText(
                                    context,
                                    recoveryExportToastCopy(
                                        RecoveryPublicationResult(
                                            verdict = RecoveryPublicationVerdict.FAILED,
                                            destinationUri = null,
                                        ),
                                        error,
                                    ),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                        )
                    },
                    onFailure = { error ->
                        Toast.makeText(
                            context,
                            recoveryExportToastCopy(
                                RecoveryPublicationResult(
                                    verdict = RecoveryPublicationVerdict.FAILED,
                                    destinationUri = null,
                                ),
                                error,
                            ),
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                )
            }
            Unit
        },
    )
}

private fun recoveryRestoreFailureCopy(error: Throwable): String = when (error) {
    is RecoveryArchiveException -> error.message ?: "Couldn’t restore that recovery archive."
    else -> "Couldn’t restore that recovery archive."
}

private fun recoveryExportToastCopy(
    result: RecoveryPublicationResult,
    acceptError: Throwable? = null,
): String = when (result.verdict) {
    RecoveryPublicationVerdict.VERIFIED -> "Recovery archive saved."
    RecoveryPublicationVerdict.WRITTEN_UNVERIFIED ->
        "Recovery archive may have been written but couldn’t be verified. " +
            "Try exporting again before deleting anything."
    RecoveryPublicationVerdict.CANCELLED -> "Recovery archive export cancelled."
    RecoveryPublicationVerdict.FAILED -> when {
        acceptError is RecoveryArchiveException -> acceptError.message
            ?: "Couldn’t save the recovery archive."
        result.destinationMayRemain ->
            "Couldn’t save the recovery archive. The destination may still contain a partial file."
        else -> "Couldn’t save the recovery archive."
    }
}
