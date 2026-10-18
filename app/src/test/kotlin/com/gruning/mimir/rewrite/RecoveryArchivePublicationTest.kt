package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.recovery.RECOVERY_CONTENT_COMPAT
import com.gruning.mimir.rewrite.core.model.recovery.RECOVERY_CONTENT_CORE
import com.gruning.mimir.rewrite.core.model.recovery.RECOVERY_CONTENT_DERIVED
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveCoverageV1
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveDescriptorV1
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchivePayloadSource
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveSnapshotV1
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveTablesV1
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveWriter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecoveryArchivePublicationTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `publication is VERIFIED only after destination read-back validates`() = runBlocking {
        val staged = stagedArchive("verified")
        val journal = FakeJournal()
        val port = FakePort(reopen = true)

        val result = RecoveryArchiveService.publish(
            staged = staged,
            createdDocument = createdDocument(staged, "content://provider/new/verified"),
            port = port,
            journal = journal,
        )

        assertEquals(RecoveryPublicationVerdict.VERIFIED, result.verdict)
        assertFalse(result.destinationMayRemain)
        assertFalse(port.deleted)
        assertNull(journal.record)
        assertTrue(port.bytes.isNotEmpty())
    }

    @Test
    fun `provider that cannot reopen is truthfully WRITTEN_UNVERIFIED`() = runBlocking {
        val staged = stagedArchive("unverified")
        val journal = FakeJournal()
        val port = FakePort(reopen = false)

        val result = RecoveryArchiveService.publish(
            staged,
            createdDocument(staged, "content://provider/new/unverified"),
            port,
            journal,
        )

        assertEquals(RecoveryPublicationVerdict.WRITTEN_UNVERIFIED, result.verdict)
        assertTrue(result.destinationMayRemain)
        assertEquals(staged.runId, journal.record?.runId)
        assertTrue(journal.record?.writeCompleted == true)
    }

    @Test
    fun `mid-copy failure deletes only the created destination and clears pending record`() = runBlocking {
        val staged = stagedArchive("copy-failure")
        val journal = FakeJournal()
        val port = FakePort(reopen = true, failAfterBytes = 32)

        val result = RecoveryArchiveService.publish(
            staged,
            createdDocument(staged, "content://provider/new/failure"),
            port,
            journal,
        )

        assertEquals(RecoveryPublicationVerdict.FAILED, result.verdict)
        assertTrue(port.deleted)
        assertFalse(result.destinationMayRemain)
        assertNull(journal.record)
    }

    @Test
    fun `cancellation claims nothing and records an undeletable partial honestly`() = runBlocking {
        val staged = stagedArchive("cancel")
        val journal = FakeJournal()
        val port = FakePort(
            reopen = true,
            cancelAfterBytes = 32,
            deleteSucceeds = false,
        )

        val result = RecoveryArchiveService.publish(
            staged,
            createdDocument(staged, "content://provider/new/cancelled"),
            port,
            journal,
        )

        assertEquals(RecoveryPublicationVerdict.CANCELLED, result.verdict)
        assertTrue(result.destinationMayRemain)
        assertEquals(staged.runId, journal.record?.runId)
    }

    @Test
    fun `failed deletion retains the pending record after copy failure`() = runBlocking {
        val staged = stagedArchive("copy-failure-delete-failure")
        val journal = FakeJournal()
        val port = FakePort(
            reopen = true,
            failAfterBytes = 32,
            deleteSucceeds = false,
        )

        val result = RecoveryArchiveService.publish(
            staged,
            createdDocument(staged, "content://provider/new/undeletable-failure"),
            port,
            journal,
        )

        assertEquals(RecoveryPublicationVerdict.FAILED, result.verdict)
        assertTrue(result.destinationMayRemain)
        assertEquals(staged.runId, journal.record?.runId)
    }

    @Test
    fun `sink close failure cannot establish completed-write evidence`() = runBlocking {
        val staged = stagedArchive("sink-close-failure")
        val journal = FakeJournal()
        val port = FakePort(reopen = false, closeThrows = true)

        val result = RecoveryArchiveService.publish(
            staged,
            createdDocument(staged, "content://provider/new/close-failure"),
            port,
            journal,
        )

        assertEquals(RecoveryPublicationVerdict.FAILED, result.verdict)
        assertTrue(port.deleted)
        assertNull(journal.record)
    }

    @Test
    fun `structured coroutine cancellation propagates after destination cleanup`() {
        val staged = stagedArchive("structured-cancel")
        val journal = FakeJournal()
        lateinit var port: FakePort
        var publishReturned = false

        try {
            runBlocking {
                val operation = currentCoroutineContext().job
                port = FakePort(
                    reopen = true,
                    cancelJobAfterBytes = 32,
                    onCancelJob = {
                        operation.cancel(CancellationException("fixture job cancellation"))
                    },
                )
                RecoveryArchiveService.publish(
                    staged,
                    createdDocument(staged, "content://provider/new/structured-cancel"),
                    port,
                    journal,
                )
                publishReturned = true
            }
        } catch (_: CancellationException) {
            // Expected: structured cancellation must never be converted into a publication verdict.
        }

        assertFalse(publishReturned)
        assertTrue(port.deleted)
        assertNull(journal.record)
    }

    @Test
    fun `pending journal failure aborts before a destination byte and cleans the created document`() =
        runBlocking {
            val staged = stagedArchive("journal-failure")
            val journal = FakeJournal(saveSucceeds = false)
            val port = FakePort(reopen = true)

            val result = RecoveryArchiveService.publish(
                staged,
                createdDocument(staged, "content://provider/new/journal-failure"),
                port,
                journal,
            )

            assertEquals(RecoveryPublicationVerdict.FAILED, result.verdict)
            assertTrue(port.deleted)
            assertTrue(port.bytes.isEmpty())
        }

    @Test
    fun `throwing journal read fails closed and releases publication single-flight`() = runBlocking {
        val staged = stagedArchive("throwing-read")
        val firstPort = FakePort(reopen = true)

        val failed = RecoveryArchiveService.publish(
            staged,
            createdDocument(staged, "content://provider/new/throwing-read"),
            firstPort,
            FakeJournal(readThrows = true),
        )

        assertEquals(RecoveryPublicationVerdict.FAILED, failed.verdict)
        assertTrue(firstPort.deleted)
        assertTrue(firstPort.bytes.isEmpty())

        val second = RecoveryArchiveService.publish(
            staged,
            createdDocument(staged, "content://provider/new/after-throwing-read"),
            FakePort(reopen = true),
            FakeJournal(),
        )
        assertEquals(RecoveryPublicationVerdict.VERIFIED, second.verdict)
    }

    @Test
    fun `throwing journal clear cannot mask active cancellation cleanup`() = runBlocking {
        val staged = stagedArchive("throwing-clear-cancel")
        val journal = FakeJournal(clearThrows = true)
        val port = FakePort(reopen = true, cancelAfterBytes = 32)

        val result = RecoveryArchiveService.publish(
            staged,
            createdDocument(staged, "content://provider/new/throwing-clear-cancel"),
            port,
            journal,
        )

        assertEquals(RecoveryPublicationVerdict.CANCELLED, result.verdict)
        assertTrue(port.deleted)
        assertEquals(staged.runId, journal.record?.runId)
    }

    @Test
    fun `existing pending publication is never overwritten by a new destination`() = runBlocking {
        val staged = stagedArchive("pending-collision")
        val journal = FakeJournal().apply {
            save(
                PendingRecoveryPublication(
                    runId = "00000000-0000-0000-0000-000000000099",
                    destinationUri = "content://provider/new/older",
                    stagedArchivePath = "/owned/older/archive.zip",
                    manifestSha256 = "a".repeat(64),
                ),
            )
        }
        val port = FakePort(reopen = true)

        val result = RecoveryArchiveService.publish(
            staged,
            createdDocument(staged, "content://provider/new/newer"),
            port,
            journal,
        )

        assertEquals(RecoveryPublicationVerdict.FAILED, result.verdict)
        assertTrue(port.deleted)
        assertTrue(port.bytes.isEmpty())
        assertEquals("content://provider/new/older", journal.record?.destinationUri)
    }

    @Test
    fun `semantic failure aborts before a destination byte and cleans the created document`() =
        runBlocking {
            val staged = stagedArchive(
                name = "semantic-failure",
                favoriteRows = listOf("""{"gameId":"game/a"}"""),
            )
            val journal = FakeJournal()
            val port = FakePort(reopen = true)

            val result = RecoveryArchiveService.publish(
                staged,
                createdDocument(staged, "content://provider/new/semantic-failure"),
                port,
                journal,
            )

            assertEquals(RecoveryPublicationVerdict.FAILED, result.verdict)
            assertTrue(port.deleted)
            assertTrue(port.bytes.isEmpty())
            assertNull(journal.record)
        }

    @Test
    fun `corrupt provider read-back is never reported as verified`() = runBlocking {
        val staged = stagedArchive("corrupt-readback")
        val journal = FakeJournal()
        val port = FakePort(
            reopen = true,
            readbackTransform = { bytes -> bytes + byteArrayOf(0x4d) },
        )

        val result = RecoveryArchiveService.publish(
            staged,
            createdDocument(staged, "content://provider/new/corrupt-readback"),
            port,
            journal,
        )

        assertEquals(RecoveryPublicationVerdict.FAILED, result.verdict)
        assertTrue(port.deleted)
        assertNull(journal.record)
    }

    @Test
    fun `pending publication recovery verifies only its owned staged archive and exact URI`() =
        runBlocking {
            val staged = stagedArchive("pending")
            val journal = FakeJournal()
            val destination = "content://provider/new/pending"
            val port = FakePort(reopen = true)
            val bytes = staged.result.archive.readBytes()
            port.openSink(destination)?.use { it.write(bytes, 0, bytes.size) }
            journal.save(
                PendingRecoveryPublication(
                    runId = staged.runId,
                    destinationUri = destination,
                    stagedArchivePath = staged.result.archive.absolutePath,
                    manifestSha256 = staged.result.manifestSha256,
                ),
            )

            val result = RecoveryArchiveService.recoverPendingPublication(
                runRoot = staged.runRoot,
                port = port,
                journal = journal,
            )

            assertEquals(RecoveryPublicationVerdict.VERIFIED, result?.verdict)
            assertEquals(destination, result?.destinationUri)
            assertFalse(port.deleted)
            assertNull(journal.record)
        }

    @Test
    fun `unverified publication becomes VERIFIED when the exact destination can later reopen`() =
        runBlocking {
            val staged = stagedArchive("unverified-retry")
            val journal = FakeJournal()
            val destination = "content://provider/new/unverified-retry"
            val port = FakePort(reopen = false)

            val first = RecoveryArchiveService.publish(
                staged,
                createdDocument(staged, destination),
                port,
                journal,
            )
            assertEquals(RecoveryPublicationVerdict.WRITTEN_UNVERIFIED, first.verdict)
            assertEquals(staged.runId, journal.record?.runId)

            port.reopen = true
            val recovered = RecoveryArchiveService.recoverPendingPublication(
                runRoot = staged.runRoot,
                port = port,
                journal = journal,
            )

            assertEquals(RecoveryPublicationVerdict.VERIFIED, recovered?.verdict)
            assertFalse(port.deleted)
            assertNull(journal.record)
        }

    @Test
    fun `valid destination recovers after staged cache loss`() = runBlocking {
        val staged = stagedArchive("staged-cache-loss")
        val journal = FakeJournal()
        val destination = "content://provider/new/staged-cache-loss"
        val port = FakePort(reopen = true)
        val bytes = staged.result.archive.readBytes()
        port.openSink(destination)?.use { it.write(bytes, 0, bytes.size) }
        journal.save(
            PendingRecoveryPublication(
                runId = staged.runId,
                destinationUri = destination,
                stagedArchivePath = staged.result.archive.absolutePath,
                manifestSha256 = staged.result.manifestSha256,
            ),
        )
        assertTrue(staged.result.archive.delete())

        val recovered = RecoveryArchiveService.recoverPendingPublication(
            runRoot = staged.runRoot,
            port = port,
            journal = journal,
        )

        assertEquals(RecoveryPublicationVerdict.VERIFIED, recovered?.verdict)
        assertNull(journal.record)
        assertFalse(port.deleted)
    }

    @Test
    fun `verified destination is not reported VERIFIED when pending journal cannot clear`() =
        runBlocking {
            val staged = stagedArchive("clear-failure")
            val journal = FakeJournal(clearSucceeds = false)
            val destination = "content://provider/new/clear-failure"
            val port = FakePort(reopen = true)
            val bytes = staged.result.archive.readBytes()
            port.openSink(destination)?.use { it.write(bytes, 0, bytes.size) }
            journal.save(
                PendingRecoveryPublication(
                    runId = staged.runId,
                    destinationUri = destination,
                    stagedArchivePath = staged.result.archive.absolutePath,
                    manifestSha256 = staged.result.manifestSha256,
                ),
            )

            val recovered = RecoveryArchiveService.recoverPendingPublication(
                runRoot = staged.runRoot,
                port = port,
                journal = journal,
            )

            assertEquals(RecoveryPublicationVerdict.FAILED, recovered?.verdict)
            assertTrue(recovered?.destinationMayRemain == true)
            assertEquals(staged.runId, journal.record?.runId)
            assertFalse(port.deleted)
        }

    @Test
    fun `pre-write journal without staged cache never claims an unreadable destination was written`() =
        runBlocking {
            val staged = stagedArchive("unreadable-cache-loss")
            val journal = FakeJournal()
            val destination = "content://provider/new/unreadable-cache-loss"
            val port = FakePort(reopen = false)
            journal.save(
                PendingRecoveryPublication(
                    runId = staged.runId,
                    destinationUri = destination,
                    stagedArchivePath = staged.result.archive.absolutePath,
                    manifestSha256 = staged.result.manifestSha256,
                ),
            )
            assertTrue(staged.result.archive.delete())

            val recovered = RecoveryArchiveService.recoverPendingPublication(
                runRoot = staged.runRoot,
                port = port,
                journal = journal,
            )

            assertEquals(RecoveryPublicationVerdict.FAILED, recovered?.verdict)
            assertTrue(recovered?.destinationMayRemain == true)
            assertEquals(staged.runId, journal.record?.runId)
            assertFalse(journal.record?.writeCompleted == true)
            assertFalse(port.deleted)
        }

    @Test
    fun `pending journal with a nonexact staged path cannot delete its destination`() = runBlocking {
        val staged = stagedArchive("nonexact-pending-path")
        val journal = FakeJournal()
        val destination = "content://provider/new/nonexact-pending-path"
        val port = FakePort(reopen = true)
        journal.save(
            PendingRecoveryPublication(
                runId = staged.runId,
                destinationUri = destination,
                stagedArchivePath = File(staged.runDirectory, "different.zip").absolutePath,
                manifestSha256 = staged.result.manifestSha256,
            ),
        )

        val recovered = RecoveryArchiveService.recoverPendingPublication(
            runRoot = staged.runRoot,
            port = port,
            journal = journal,
        )

        assertEquals(RecoveryPublicationVerdict.FAILED, recovered?.verdict)
        assertTrue(recovered?.destinationMayRemain == true)
        assertFalse(port.deleted)
        assertEquals(staged.runId, journal.record?.runId)
    }

    @Test
    fun `document creation capability binds exactly one URI and is consumed once`() = runBlocking {
        val staged = stagedArchive("one-shot-capability")
        val request = RecoveryArchiveService.beginDocumentCreation(staged).getOrThrow()
        val accepted = RecoveryArchiveService.acceptCreatedDocument(
            request,
            "content://provider/new/one-shot",
        ).getOrThrow()

        assertTrue(
            RecoveryArchiveService.acceptCreatedDocument(
                request,
                "content://provider/new/second",
            ).isFailure,
        )

        val port = FakePort(reopen = true)
        val first = RecoveryArchiveService.publish(staged, accepted, port, FakeJournal())
        val second = RecoveryArchiveService.publish(staged, accepted, port, FakeJournal())

        assertEquals(RecoveryPublicationVerdict.VERIFIED, first.verdict)
        assertEquals(RecoveryPublicationVerdict.FAILED, second.verdict)
    }

    @Test
    fun `forged URI cannot consume authority or trigger deletion`() = runBlocking {
        val staged = stagedArchive("forged-uri")
        val request = RecoveryArchiveService.beginDocumentCreation(staged).getOrThrow()
        val accepted = RecoveryArchiveService.acceptCreatedDocument(
            request,
            "content://provider/new/authorized",
        ).getOrThrow()
        val forged = CreatedRecoveryDocument(
            runId = accepted.runId,
            authorizationId = accepted.authorizationId,
            destinationUri = "content://provider/new/forged",
        )
        val port = FakePort(reopen = true)

        val rejected = RecoveryArchiveService.publish(staged, forged, port, FakeJournal())

        assertEquals(RecoveryPublicationVerdict.FAILED, rejected.verdict)
        assertFalse(port.deleted)
        assertTrue(port.bytes.isEmpty())

        val authorized = RecoveryArchiveService.publish(staged, accepted, port, FakeJournal())
        assertEquals(RecoveryPublicationVerdict.VERIFIED, authorized.verdict)
    }

    @Test
    fun `throwing sweep journal fails closed without leaking the single-flight lock`() {
        val root = File(temporary.newFolder("sweep-journal"), "recovery-export").apply { mkdirs() }
        val stale = File(root, "00000000-0000-0000-0000-000000000042").apply {
            mkdirs()
            File(this, "owned.tmp").writeText("stale")
        }
        val throwingJournal = object : RecoveryPublicationJournal {
            override fun save(record: PendingRecoveryPublication): Boolean = false
            override fun read(): PendingRecoveryPublication = error("corrupt journal fixture")
            override fun clear(runId: String): Boolean = false
        }

        RecoveryArchiveService.sweepStaleStagingRoot(
            root = root,
            olderThanUtcMillis = Long.MAX_VALUE,
            journal = throwingJournal,
        )
        assertTrue(stale.exists())

        RecoveryArchiveService.sweepStaleStagingRoot(
            root = root,
            olderThanUtcMillis = Long.MAX_VALUE,
            journal = FakeJournal(),
        )
        assertFalse(stale.exists())
    }

    @Test
    fun `throwing pending journal read returns a closed failure without a forged destination`() =
        runBlocking {
            val root = File(temporary.newFolder("recover-throwing-read"), "recovery-export").apply {
                mkdirs()
            }

            val result = RecoveryArchiveService.recoverPendingPublication(
                runRoot = root,
                port = FakePort(reopen = true),
                journal = FakeJournal(readThrows = true),
            )

            assertEquals(RecoveryPublicationVerdict.FAILED, result?.verdict)
            assertNull(result?.destinationUri)
            assertTrue(result?.destinationMayRemain == true)
        }

    @Test
    fun `V1 coverage is explicit and never presented as a whole-product backup`() {
        val exclusions = recoveryArchiveExclusions()
        assertEquals(
            RecoveryArchiveCoverageV1.requiredExclusions,
            exclusions.map { it.what }.toSet(),
        )
        assertTrue(RecoveryArchiveCoverageCopy.contains("Not included: collections, art, credentials."))
        assertFalse(RecoveryArchiveCoverageCopy.contains("Backup saved"))
    }

    private fun stagedArchive(
        name: String,
        favoriteRows: List<String> = emptyList(),
    ): StagedRecoveryArchive {
        val runRoot = File(temporary.newFolder(name), "recovery-export").apply { mkdirs() }
        val run = File(runRoot, "00000000-0000-0000-0000-000000000001").apply { mkdirs() }
        val payloadRoot = File(run, "payload").apply { mkdirs() }
        val payloads = RecoveryArchiveTablesV1.paths.map { path ->
            val file = File(payloadRoot, path.substringAfterLast('/')).apply {
                if (path == "db/favorites.jsonl") {
                    writeText(
                        favoriteRows.joinToString(
                            separator = "\n",
                            postfix = if (favoriteRows.isEmpty()) "" else "\n",
                        ),
                    )
                } else {
                    writeBytes(byteArrayOf())
                }
            }
            RecoveryArchivePayloadSource(
                path = path,
                contentClass = if (path == "db/title_metadata_cache.jsonl") {
                    RECOVERY_CONTENT_DERIVED
                } else {
                    RECOVERY_CONTENT_CORE
                },
                rowCount = if (path == "db/favorites.jsonl") {
                    favoriteRows.size.toLong()
                } else {
                    0
                },
                file = file,
            )
        }.toMutableList()
        val compatibility = File(payloadRoot, "migration-bundle.json").apply {
            writeText(
                "{\"bundleVersion\":1,\"kind\":\"mimir.migration.bundle\"," +
                    "\"producedByApp\":\"com.gruning.mimir.rewrite\"," +
                    "\"producedByVersion\":\"0.1.0-alpha.1\"," +
                    "\"producedAtMillis\":1753270000000,\"sourceRoots\":[]," +
                    "\"favorites\":[],\"hidden\":[],\"emulatorOverrides\":[]}",
            )
        }
        payloads += RecoveryArchivePayloadSource(
            path = RecoveryArchiveTablesV1.COMPATIBILITY_BUNDLE_PATH,
            contentClass = RECOVERY_CONTENT_COMPAT,
            file = compatibility,
        )
        val result = RecoveryArchiveWriter.writeNew(
            destination = File(run, "archive.zip"),
            descriptor = RecoveryArchiveDescriptorV1(
                createdAtUtcMillis = 1_753_270_000_000L,
                producedByApp = "com.gruning.mimir.rewrite",
                producedByVersionName = "0.1.0-alpha.1",
                producedByVersionCode = 3,
                schemaVersion = 9,
                excluded = recoveryArchiveExclusions(),
                snapshot = RecoveryArchiveSnapshotV1(true, false, "wal"),
            ),
            payloads = payloads,
        ).getOrThrow()
        return StagedRecoveryArchive(
            runId = "00000000-0000-0000-0000-000000000001",
            runRoot = runRoot,
            runDirectory = run,
            result = result,
        )
    }

    private fun createdDocument(
        staged: StagedRecoveryArchive,
        destinationUri: String,
    ): CreatedRecoveryDocument {
        val request = RecoveryArchiveService.beginDocumentCreation(staged).getOrThrow()
        return RecoveryArchiveService.acceptCreatedDocument(request, destinationUri).getOrThrow()
    }
}

private class FakeJournal(
    private val saveSucceeds: Boolean = true,
    private val clearSucceeds: Boolean = true,
    private val readThrows: Boolean = false,
    private val clearThrows: Boolean = false,
) : RecoveryPublicationJournal {
    var record: PendingRecoveryPublication? = null

    override fun save(record: PendingRecoveryPublication): Boolean {
        if (!saveSucceeds) return false
        this.record = record
        return true
    }

    override fun read(): PendingRecoveryPublication? {
        if (readThrows) error("fixture read failure")
        return record
    }

    override fun clear(runId: String): Boolean {
        if (clearThrows) error("fixture clear failure")
        if (!clearSucceeds || record?.runId != runId) return false
        record = null
        return true
    }
}

private class FakePort(
    reopen: Boolean,
    private val failAfterBytes: Int? = null,
    private val cancelAfterBytes: Int? = null,
    private val cancelJobAfterBytes: Int? = null,
    private val onCancelJob: () -> Unit = {},
    private val deleteSucceeds: Boolean = true,
    private val closeThrows: Boolean = false,
    private val readbackTransform: (ByteArray) -> ByteArray = { it },
) : RecoveryPublicationPort {
    private var output = ByteArrayOutputStream()
    var reopen = reopen
    val bytes: ByteArray
        get() = output.toByteArray()
    var deleted = false

    override fun openSink(destinationUri: String): RecoveryPublicationSink {
        output = ByteArrayOutputStream()
        return object : RecoveryPublicationSink {
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                val terminal = listOfNotNull(
                    failAfterBytes,
                    cancelAfterBytes,
                    cancelJobAfterBytes,
                ).minOrNull()
                if (terminal != null && output.size() + length > terminal) {
                    val allowed = (terminal - output.size()).coerceAtLeast(0)
                    if (allowed > 0) output.write(bytes, offset, allowed)
                    if (cancelJobAfterBytes != null) {
                        onCancelJob()
                        return
                    }
                    if (cancelAfterBytes != null) {
                        throw CancellationException("fixture cancellation")
                    }
                    error("fixture write failure")
                }
                output.write(bytes, offset, length)
            }

            override fun sync(): Boolean = true
            override fun close() {
                if (closeThrows) error("fixture close failure")
            }
        }
    }

    override fun openSource(destinationUri: String): InputStream? =
        if (reopen) ByteArrayInputStream(readbackTransform(bytes)) else null

    override fun deleteCreatedDocument(destinationUri: String): Boolean {
        deleted = true
        return deleteSucceeds
    }
}
