package com.gruning.mimir.rewrite.core.resolver.library

import android.content.Context
import android.content.Intent
import android.os.Process
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gruning.mimir.rewrite.core.resolver.scan.SourceLister
import com.gruning.mimir.rewrite.core.resolver.scan.SourceWalkResult
import com.gruning.mimir.rewrite.core.resolver.scan.WalkedFile
import com.gruning.mimir.rewrite.core.storage.MimirDatabase
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryRepositoryRoomTransactionDeviceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun runtimeFaultBeforeCommitReopensToTheOldSnapshot() = runBlocking {
        assertRollbackAfterReopen(InjectedTransactionFault())
    }

    @Test
    fun cancellationBeforeCommitReopensToTheOldSnapshot() = runBlocking {
        assertRollbackAfterReopen(CancellationException("cancel before commit"))
    }

    @Test
    fun processDeathBeforeTransactionReopensToTheOldSnapshot() = runBlocking {
        assertProcessDeathSnapshot(ScanProcessDeathService.Stage.BEFORE_TRANSACTION, false)
    }

    @Test
    fun processDeathDuringTransactionReopensToTheOldSnapshot() = runBlocking {
        assertProcessDeathSnapshot(ScanProcessDeathService.Stage.DURING_TRANSACTION, false)
    }

    @Test
    fun processDeathAfterCommitReopensToTheFullNewSnapshot() = runBlocking {
        assertProcessDeathSnapshot(ScanProcessDeathService.Stage.AFTER_COMMIT, true)
    }

    private suspend fun assertRollbackAfterReopen(terminalFailure: RuntimeException) {
        val databaseName = "ep02-${UUID.randomUUID()}.db"
        context.deleteDatabase(databaseName)
        var database = openDatabase(databaseName)
        try {
            val walker = MutableDeviceWalker(listOf(file("Old.gba", "GBA/Old.gba")))
            repository(database, walker, clock = { 10L }).rescan(ROOT)
            val oldGames = database.gameDao().allEntryKeys()
            val oldSource = database.sourceRootDao().allOnce().single()
            assertNotNull(database.platformDao().byId("gba"))

            walker.files = listOf(file("New.sfc", "SNES/New.sfc"))
            val failingRepository = LibraryRepositoryImpl(
                gameDao = database.gameDao(),
                platformDao = database.platformDao(),
                sourceRootDao = database.sourceRootDao(),
                hiddenItemDao = database.hiddenItemDao(),
                favoriteDao = database.favoriteDao(),
                walker = walker,
                runScanTransaction = { block ->
                    database.withTransaction {
                        block()
                        throw terminalFailure
                    }
                },
                clock = { 20L },
            )
            try {
                failingRepository.rescan(ROOT)
                fail("Expected ${terminalFailure::class.java.simpleName}")
            } catch (caught: RuntimeException) {
                if (terminalFailure is CancellationException && caught !is CancellationException) throw caught
                if (terminalFailure !is CancellationException && caught !is InjectedTransactionFault) throw caught
            }

            database.close()
            database = openDatabase(databaseName)
            assertEquals(oldGames, database.gameDao().allEntryKeys())
            assertEquals(oldSource, database.sourceRootDao().allOnce().single())
            assertNotNull(database.platformDao().byId("gba"))
            assertNull(database.platformDao().byId("snes"))
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    private suspend fun assertProcessDeathSnapshot(
        stage: ScanProcessDeathService.Stage,
        expectsNewSnapshot: Boolean,
    ) {
        val databaseName = "ep02-process-${stage.name.lowercase()}-${UUID.randomUUID()}.db"
        val markerName = "$databaseName.marker"
        val marker = java.io.File(context.filesDir, markerName)
        marker.delete()
        context.deleteDatabase(databaseName)
        val seededDatabase = openDatabase(databaseName)
        try {
            repository(
                seededDatabase,
                MutableDeviceWalker(listOf(file("Old.gba", "GBA/Old.gba"))),
                clock = { 10L },
            ).rescan(ROOT)
        } finally {
            seededDatabase.close()
        }

        val intent = Intent(context, ScanProcessDeathService::class.java)
            .putExtra(ScanProcessDeathService.EXTRA_DATABASE_NAME, databaseName)
            .putExtra(ScanProcessDeathService.EXTRA_MARKER_NAME, markerName)
            .putExtra(ScanProcessDeathService.EXTRA_STAGE, stage.name)
        assertNotNull(context.startService(intent))
        try {
            withTimeout(30_000) {
                while (true) {
                    val state = marker.takeIf(java.io.File::exists)?.readText()?.substringAfter('|', "").orEmpty()
                    if (state == stage.name) break
                    if (state.startsWith("ERROR_")) throw AssertionError(state)
                    delay(25)
                }
            }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError(
                "Timed out waiting for $stage; marker=${marker.takeIf(java.io.File::exists)?.readText()}",
                error,
            )
        }
        val probePid = marker.readText().substringBefore('|').toInt()
        assertTrue("probe must run in a secondary process", probePid != Process.myPid())
        // Remove ActivityManager's restart record while leaving the probe thread alive; the explicit
        // kill below must model process death once, not an Android service restart policy.
        context.stopService(intent)
        Process.killProcess(probePid)
        withTimeout(5_000) {
            while (java.io.File("/proc/$probePid").exists()) delay(25)
        }

        val reopened = openDatabase(databaseName)
        try {
            val keys = reopened.gameDao().allEntryKeys()
            if (expectsNewSnapshot) {
                assertEquals(listOf("$ROOT::SNES/New.sfc"), keys)
                assertEquals(20L, reopened.sourceRootDao().allOnce().single().lastSeenMillis)
            } else {
                assertEquals(listOf("$ROOT::GBA/Old.gba"), keys)
                assertEquals(10L, reopened.sourceRootDao().allOnce().single().lastSeenMillis)
            }
        } finally {
            reopened.close()
        }
        marker.delete()
        context.deleteDatabase(databaseName)
    }

    private fun repository(
        database: MimirDatabase,
        walker: SourceLister,
        clock: () -> Long,
    ) = LibraryRepositoryImpl(
        gameDao = database.gameDao(),
        platformDao = database.platformDao(),
        sourceRootDao = database.sourceRootDao(),
        hiddenItemDao = database.hiddenItemDao(),
        favoriteDao = database.favoriteDao(),
        walker = walker,
        runScanTransaction = { block -> database.withTransaction { block() } },
        clock = clock,
    )

    private fun openDatabase(name: String): MimirDatabase =
        Room.databaseBuilder(context, MimirDatabase::class.java, name).build()

    private fun file(name: String, relativePath: String) = WalkedFile(
        displayName = name,
        relativePath = relativePath,
        sizeBytes = 2L * 1024 * 1024,
        documentUri = null,
        filePath = "/virtual/$relativePath",
    )

    private class MutableDeviceWalker(var files: List<WalkedFile>) : SourceLister {
        override suspend fun walk(rootUri: String): SourceWalkResult = SourceWalkResult.Complete(files)
    }

    private class InjectedTransactionFault : RuntimeException("fault before Room commit")

    private companion object {
        const val ROOT = "/virtual"
    }
}
