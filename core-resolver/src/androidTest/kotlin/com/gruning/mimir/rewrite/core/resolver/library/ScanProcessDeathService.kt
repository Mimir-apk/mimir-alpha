package com.gruning.mimir.rewrite.core.resolver.library

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.util.Log
import androidx.room.Room
import androidx.room.withTransaction
import com.gruning.mimir.rewrite.core.resolver.scan.SourceLister
import com.gruning.mimir.rewrite.core.resolver.scan.SourceWalkResult
import com.gruning.mimir.rewrite.core.resolver.scan.WalkedFile
import com.gruning.mimir.rewrite.core.storage.MimirDatabase
import java.io.File
import kotlinx.coroutines.runBlocking

/** Secondary-process instrumentation probe used to kill a real Room scan before or after commit. */
class ScanProcessDeathService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val databaseName = requireNotNull(intent?.getStringExtra(EXTRA_DATABASE_NAME))
        val markerName = requireNotNull(intent.getStringExtra(EXTRA_MARKER_NAME))
        val stage = Stage.valueOf(requireNotNull(intent.getStringExtra(EXTRA_STAGE)))
        Thread {
            val marker = File(filesDir, markerName)
            try {
                Log.i(TAG, "probe ${Process.myPid()} starting $stage")
                marker.writeText("${Process.myPid()}|START_${stage.name}")
                if (stage == Stage.BEFORE_TRANSACTION) {
                    marker.writeText("${Process.myPid()}|${stage.name}")
                    waitForProcessKill()
                }
                val database = Room.databaseBuilder(this, MimirDatabase::class.java, databaseName).build()
                marker.writeText("${Process.myPid()}|DATABASE_BUILT_${stage.name}")
                if (stage == Stage.DURING_RECONCILE_ROOT) {
                    runResumableReconcileProbe(database, marker, stage)
                    return@Thread
                }
                val repository = LibraryRepositoryImpl(
                    gameDao = database.gameDao(),
                    platformDao = database.platformDao(),
                    sourceRootDao = database.sourceRootDao(),
                    hiddenItemDao = database.hiddenItemDao(),
                    favoriteDao = database.favoriteDao(),
                    walker = object : SourceLister {
                        override suspend fun walk(rootUri: String): SourceWalkResult {
                            marker.writeText("${Process.myPid()}|WALKED_${stage.name}")
                            return newSnapshot()
                        }
                    },
                    runScanTransaction = { block ->
                        marker.writeText("${Process.myPid()}|TRANSACTION_REQUESTED_${stage.name}")
                        database.withTransaction {
                            marker.writeText("${Process.myPid()}|TRANSACTION_ENTERED_${stage.name}")
                            block()
                            marker.writeText("${Process.myPid()}|TRANSACTION_MUTATED_${stage.name}")
                            if (stage == Stage.DURING_TRANSACTION) {
                                marker.writeText("${Process.myPid()}|${stage.name}")
                                waitForProcessKill()
                            }
                        }
                    },
                    clock = { 20L },
                )
                marker.writeText("${Process.myPid()}|REPOSITORY_BUILT_${stage.name}")
                runBlocking { repository.rescan(ROOT) }
                marker.writeText("${Process.myPid()}|${Stage.AFTER_COMMIT.name}")
                waitForProcessKill()
            } catch (error: Throwable) {
                Log.e(TAG, "probe failed at $stage", error)
                marker.writeText(
                    "${Process.myPid()}|ERROR_${stage.name}:${error::class.java.simpleName}:${error.message}",
                )
            }
        }.apply {
            name = "ep02-process-death-probe"
            start()
        }
        return START_NOT_STICKY
    }

    private fun runResumableReconcileProbe(
        database: MimirDatabase,
        marker: File,
        stage: Stage,
    ) {
        val repository = LibraryRepositoryImpl(
            gameDao = database.gameDao(),
            platformDao = database.platformDao(),
            sourceRootDao = database.sourceRootDao(),
            hiddenItemDao = database.hiddenItemDao(),
            favoriteDao = database.favoriteDao(),
            walker = object : SourceLister {
                override suspend fun walk(rootUri: String): SourceWalkResult {
                    if (rootUri == RECONCILE_CRASH_ROOT) {
                        marker.writeText("${Process.myPid()}|${stage.name}")
                        waitForProcessKill()
                    }
                    val suffix = rootUri.substringAfterLast('/')
                    return SourceWalkResult.Complete(
                        listOf(
                            WalkedFile(
                                displayName = "$suffix.gba",
                                relativePath = "GBA/$suffix.gba",
                                sizeBytes = 2L * 1024 * 1024,
                                documentUri = null,
                                filePath = "$rootUri/GBA/$suffix.gba",
                            ),
                        ),
                    )
                }
            },
            runScanTransaction = { block -> database.withTransaction { block() } },
            clock = { 20L },
            gameIdentityDao = database.gameIdentityDao(),
            sourceReconcileDao = database.sourceReconcileDao(),
        )
        marker.writeText("${Process.myPid()}|RECONCILE_STARTED_${stage.name}")
        runBlocking { repository.reconcileSources() }
        marker.writeText("${Process.myPid()}|ERROR_${stage.name}:returned_without_kill")
    }

    private fun waitForProcessKill(): Nothing {
        while (true) Thread.sleep(1_000)
    }

    private fun newSnapshot(): SourceWalkResult = SourceWalkResult.Complete(
            listOf(
                WalkedFile(
                    displayName = "New.sfc",
                    relativePath = "SNES/New.sfc",
                    sizeBytes = 2L * 1024 * 1024,
                    documentUri = null,
                    filePath = "/virtual/SNES/New.sfc",
                ),
            ),
        )

    enum class Stage {
        BEFORE_TRANSACTION,
        DURING_TRANSACTION,
        AFTER_COMMIT,
        DURING_RECONCILE_ROOT,
    }

    companion object {
        private const val TAG = "Ep02ProcessProbe"
        const val EXTRA_DATABASE_NAME = "database_name"
        const val EXTRA_MARKER_NAME = "marker_name"
        const val EXTRA_STAGE = "stage"
        const val ROOT = "/virtual"
        const val RECONCILE_CRASH_ROOT = "/virtual/C"
    }
}
