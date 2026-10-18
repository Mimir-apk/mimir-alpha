package com.gruning.mimir.tweaker.mgde

import android.system.OsConstants
import java.io.FileDescriptor
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MgdePublicationTest {
    private val nonce = "a".repeat(32)
    private val locator = MgdeAdmission.locatorFor(nonce)
    private val dir = "${MgdeD7PublicationStore.ROOT}/$locator"
    private val stage = "$dir/.stage"
    private val record = "$dir/record"

    @Test
    fun `D7 pins and fixed authority topology are exact`() {
        assertEquals("8b377b4ae37590980de3e73e3758c814d04dba73", MgdeD7Pins.COMMIT)
        assertEquals("dd13758c4455b65980deb29aa5e2eeff7828502f0c705d85e309df9e014f5700", MgdeD7Pins.MANIFEST_SHA256)
        assertEquals("/data/local/tmp/mimir-mgde1", MgdeD7PublicationStore.ROOT)
        assertEquals(16, locator.length)
    }

    @Test
    fun `two stores are process serialized while sealed D7 binds cross process O_EXCL`() {
        val fake = FakeMgdePosixOps(dir)
        val stores = listOf(MgdeD7PublicationStore(fake), MgdeD7PublicationStore(fake))
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val futures = stores.mapIndexed { index, store ->
            pool.submit<MgdePublicationResult> {
                start.await()
                store.publish(nonce, byteArrayOf(index.toByte(), 7))
            }
        }
        start.countDown()
        val results = futures.map { it.get() }
        pool.shutdownNow()
        assertEquals(1, results.count { it === MgdePublicationResult.Published })
        assertEquals(1, results.count { it === MgdePublicationResult.Rejected })
        assertEquals(setOf("record"), fake.immediateNames(dir))
        assertEquals(1, fake.statForAssertion(record)!!.nlink)
        assertNull(fake.statForAssertion(stage))
        assertEquals("8b377b4ae37590980de3e73e3758c814d04dba73", MgdeD7Pins.COMMIT)
        assertEquals("dd13758c4455b65980deb29aa5e2eeff7828502f0c705d85e309df9e014f5700", MgdeD7Pins.MANIFEST_SHA256)
    }

    @Test
    fun `published observation is full payload and destination preexist never clobbers`() {
        val fake = FakeMgdePosixOps(dir)
        val payload = ByteArray(4097) { (it and 0xff).toByte() }
        val store = MgdeD7PublicationStore(fake)
        assertSame(MgdePublicationResult.Published, store.publish(nonce, payload))
        fake.lstatPaths.clear()
        val observed = store.observe(nonce)
        assertArrayEquals(payload, observed.attestedRecord())
        assertEquals(
            listOf(
                MgdeD7PublicationStore.ROOT,
                dir,
                record,
                record,
                MgdeD7PublicationStore.ROOT,
                dir,
            ),
            fake.lstatPaths,
        )

        val preexisting = FakeMgdePosixOps(dir)
        preexisting.regular(record, byteArrayOf(9))
        assertSame(MgdePublicationResult.Rejected, MgdeD7PublicationStore(preexisting).publish(nonce, byteArrayOf(1)))
        assertArrayEquals(byteArrayOf(9), preexisting.bytes(record))
    }

    @Test
    fun `symlink hardlink metadata label unreadable and foreign listings fail closed`() {
        val mutations: List<(FakeMgdePosixOps) -> Unit> = listOf(
            { it.symlink(record) },
            { it.regular(record, byteArrayOf(1)); it.hardlink(record, "$dir/foreign") },
            { it.regular(record, byteArrayOf(1), uid = 1) },
            { it.regular(record, byteArrayOf(1), gid = 1) },
            { it.regular(record, byteArrayOf(1), mode = 0x1a0) },
            { it.regular(record, byteArrayOf(1), mode = 0x980) },
            { it.regular(record, byteArrayOf(1), mode = 0x580) },
            { it.regular(record, byteArrayOf(1), mode = 0x380) },
            { it.regular(record, byteArrayOf(1), label = "wrong") },
            { it.regular(record, byteArrayOf(1)); it.unreadableLists = true },
            { it.regular(record, byteArrayOf(1)); it.regular("$dir/foreign", byteArrayOf(2)) },
        )
        mutations.forEachIndexed { index, mutate ->
            val fake = FakeMgdePosixOps(dir)
            mutate(fake)
            val source = MgdeD7PublicationStore(fake).observe(nonce)
            assertSame("mutation=$index", MgdeObservedSource.MalformedPresent, source)
        }
    }

    @Test
    fun `pre-link failures and list races reject`() {
        listOf("openWrite", "write", "fsyncFile", "fstat").forEach { failure ->
            val fake = FakeMgdePosixOps(dir)
            fake.failNext = failure
            assertSame("failure=$failure", MgdePublicationResult.Rejected, MgdeD7PublicationStore(fake).publish(nonce, byteArrayOf(1, 2, 3)))
        }
        val partial = FakeMgdePosixOps(dir).also { it.partialWrite = true }
        assertSame(MgdePublicationResult.Rejected, MgdeD7PublicationStore(partial).publish(nonce, byteArrayOf(1, 2, 3)))
        val raced = FakeMgdePosixOps(dir).also { fake ->
            fake.onList = { call ->
                if (call == 2) fake.regular("$dir/foreign", byteArrayOf(8))
            }
        }
        assertSame(MgdePublicationResult.Rejected, MgdeD7PublicationStore(raced).publish(nonce, byteArrayOf(1)))
        val eexist = FakeMgdePosixOps(dir).also { it.openExclusiveEexist = true }
        assertSame(MgdePublicationResult.Rejected, MgdeD7PublicationStore(eexist).publish(nonce, byteArrayOf(1)))
        assertEquals(emptySet<String>(), eexist.immediateNames(dir))
    }

    @Test
    fun `unknown link and every post-link uncertainty are ambiguous without retry`() {
        listOf("link", "remove", "fsyncDirectory").forEach { failure ->
            val fake = FakeMgdePosixOps(dir)
            fake.failNext = failure
            assertSame(
                "failure=$failure",
                MgdePublicationResult.Ambiguous,
                MgdeD7PublicationStore(fake).publish(nonce, byteArrayOf(1, 2, 3)),
            )
            assertEquals("failure=$failure", 1, fake.linkCalls)
        }
        listOf(
            FakeMgdePosixOps(dir).also { it.swapStageAfterLink = true },
            FakeMgdePosixOps(dir).also { it.swapRecordAfterLink = true },
        ).forEachIndexed { index, fake ->
            assertSame(
                "post-link substitution=$index",
                MgdePublicationResult.Ambiguous,
                MgdeD7PublicationStore(fake).publish(nonce, byteArrayOf(1, 2, 3)),
            )
            assertEquals("post-link substitution=$index", 1, fake.linkCalls)
        }
    }

    @Test
    fun `known link destination exists rejects without retry or replacement`() {
        val fake = FakeMgdePosixOps(dir).also { it.linkDestinationExists = true }

        assertSame(
            MgdePublicationResult.Rejected,
            MgdeD7PublicationStore(fake).publish(nonce, byteArrayOf(1, 2, 3)),
        )
        assertEquals(1, fake.linkCalls)
        assertNull(fake.statForAssertion(record))
    }

    @Test
    fun `lstat errno classification maps ENOENT to absent and others to unknown`() {
        assertSame(
            MgdeStatResult.Absent,
            classifyLstatErrno(errno = 2, enoent = 2),
        )
        assertSame(
            MgdeStatResult.Unknown,
            classifyLstatErrno(errno = 13, enoent = 2),
        )
        assertSame(
            MgdeStatResult.Unknown,
            classifyLstatFailure(IllegalStateException("unknown")),
        )
    }

    @Test
    fun `root and session lstat unknown observe malformed present`() {
        listOf(MgdeD7PublicationStore.ROOT, dir).forEach { path ->
            val fake = FakeMgdePosixOps(dir).also { it.injectUnknownStat(path) }
            assertSame(
                "$path unknown",
                MgdeObservedSource.MalformedPresent,
                MgdeD7PublicationStore(fake).observe(nonce),
            )
        }
    }

    @Test
    fun `present root regular file symlink or insecure label observes malformed present`() {
        listOf<(FakeMgdePosixOps) -> Unit>(
            { it.regular(MgdeD7PublicationStore.ROOT, byteArrayOf(1)) },
            { it.replaceDirectoryWithSymlink(MgdeD7PublicationStore.ROOT) },
            { it.mutateMetadata(MgdeD7PublicationStore.ROOT, label = "wrong") },
        ).forEachIndexed { index, mutate ->
            val fake = FakeMgdePosixOps(dir)
            mutate(fake)
            assertSame(
                "root case=$index",
                MgdeObservedSource.MalformedPresent,
                MgdeD7PublicationStore(fake).observe(nonce),
            )
        }
    }

    @Test
    fun `derived locator remains valid for empty and high bit nonces`() {
        listOf("", "\u00ff\u2603").forEach { taskNonce ->
            val derived = MgdeAdmission.locatorFor(taskNonce)
            assertTrue(derived.matches(Regex("[0-9a-f]{16}")))
            val session = "${MgdeD7PublicationStore.ROOT}/$derived"
            assertSame(
                MgdeObservedSource.Absent,
                MgdeD7PublicationStore(FakeMgdePosixOps(session)).observe(taskNonce),
            )
        }
    }

    @Test
    fun `listed record disappearance or unknown lstat observes malformed present`() {
        val disappeared = FakeMgdePosixOps(dir).also {
            it.regular(record, byteArrayOf(1, 2, 3))
            it.injectAbsentStat(record)
        }
        assertSame(MgdeObservedSource.MalformedPresent, MgdeD7PublicationStore(disappeared).observe(nonce))

        val unknown = FakeMgdePosixOps(dir).also {
            it.regular(record, byteArrayOf(1, 2, 3))
            it.injectUnknownStat(record)
        }
        assertSame(MgdeObservedSource.MalformedPresent, MgdeD7PublicationStore(unknown).observe(nonce))
    }

    @Test
    fun `empty observation rejects every post-attestation directory race`() {
        val cases = listOf<Pair<String, (FakeMgdePosixOps) -> Unit>>(
            "session inode" to { it.reallocateDirectory(dir) },
            "root inode" to { it.reallocateDirectory(MgdeD7PublicationStore.ROOT) },
            "session absent" to { it.injectAbsentStat(dir) },
            "root absent" to { it.injectAbsentStat(MgdeD7PublicationStore.ROOT) },
            "session unknown" to { it.injectUnknownStat(dir) },
            "root unknown" to { it.injectUnknownStat(MgdeD7PublicationStore.ROOT) },
            "session label" to { it.mutateMetadata(dir, label = "wrong") },
            "session uid" to { it.mutateMetadata(dir, uid = 1) },
            "session gid" to { it.mutateMetadata(dir, gid = 1) },
            "session mode" to { it.mutateMetadata(dir, mode = OsConstants.S_IFDIR or 0x1c8) },
            "root label" to { it.mutateMetadata(MgdeD7PublicationStore.ROOT, label = "wrong") },
            "session symlink" to { it.replaceDirectoryWithSymlink(dir) },
        )
        val failures = cases.mapNotNull { (name, mutate) ->
            val fake = FakeMgdePosixOps(dir)
            val beforePaths = fake.paths()
            fake.onList = { call -> if (call == 1) mutate(fake) }
            val observed = MgdeD7PublicationStore(fake).observe(nonce)
            val valid =
                observed === MgdeObservedSource.MalformedPresent &&
                    observed.attestedRecord() == null &&
                    observed.attestedLocator() == null &&
                    fake.openWriteCalls == 0 &&
                    fake.removeCalls == 0 &&
                    fake.rmdirCalls == 0 &&
                    fake.fsyncDirectoryCalls == 0 &&
                    fake.paths() == beforePaths
            name.takeUnless { valid }
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun `positive observation rejects every post-record directory race`() {
        val cases = listOf<Pair<String, (FakeMgdePosixOps) -> Unit>>(
            "session inode" to { it.reallocateDirectory(dir) },
            "root inode" to { it.reallocateDirectory(MgdeD7PublicationStore.ROOT) },
            "session absent" to { it.injectAbsentStat(dir) },
            "root unknown" to { it.injectUnknownStat(MgdeD7PublicationStore.ROOT) },
            "session label" to { it.mutateMetadata(dir, label = "wrong") },
        )
        val failures = cases.mapNotNull { (name, mutate) ->
            val fake = FakeMgdePosixOps(dir)
            fake.regular(record, byteArrayOf(1, 2, 3))
            val beforePaths = fake.paths()
            fake.onList = { call -> if (call == 2) mutate(fake) }
            val observed = MgdeD7PublicationStore(fake).observe(nonce)
            val valid =
                observed === MgdeObservedSource.MalformedPresent &&
                    observed.attestedRecord() == null &&
                    observed.attestedLocator() == null &&
                    fake.openWriteCalls == 0 &&
                    fake.removeCalls == 0 &&
                    fake.rmdirCalls == 0 &&
                    fake.fsyncDirectoryCalls == 0 &&
                    fake.paths() == beforePaths
            name.takeUnless { valid }
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun `clean empty observation reattests root and session before absent`() {
        val fake = FakeMgdePosixOps(dir)
        assertSame(MgdeObservedSource.Absent, MgdeD7PublicationStore(fake).observe(nonce))
        assertEquals(
            listOf(MgdeD7PublicationStore.ROOT, dir, MgdeD7PublicationStore.ROOT, dir),
            fake.lstatPaths,
        )
        assertEquals(1, fake.listCalls)
        assertEquals(0, fake.openWriteCalls)
        assertEquals(0, fake.removeCalls)
        assertEquals(0, fake.rmdirCalls)
        assertEquals(0, fake.fsyncDirectoryCalls)
    }

    @Test
    fun `stage path substitution after pinned fd fstat rejects before link`() {
        val fake = FakeMgdePosixOps(dir).also { it.swapStageAfterFstat = true }
        assertSame(MgdePublicationResult.Rejected, MgdeD7PublicationStore(fake).publish(nonce, byteArrayOf(1, 2)))
        assertNull(fake.statForAssertion(record))
    }

    @Test
    fun `root and session directory type owner mode label and listing are exact`() {
        val paths = listOf(MgdeD7PublicationStore.ROOT, dir)
        paths.forEach { path ->
            listOf<(FakeMgdePosixOps) -> Unit>(
                { it.replaceDirectoryWithSymlink(path) },
                { it.mutateMetadata(path, uid = 1) },
                { it.mutateMetadata(path, gid = 1) },
                { it.mutateMetadata(path, mode = OsConstants.S_IFDIR or 0x1c8) },
                { it.mutateMetadata(path, mode = OsConstants.S_IFDIR or 0x9c0) },
                { it.mutateMetadata(path, mode = OsConstants.S_IFDIR or 0x5c0) },
                { it.mutateMetadata(path, mode = OsConstants.S_IFDIR or 0x3c0) },
                { it.mutateMetadata(path, label = "wrong") },
                { it.unreadableLists = true },
            ).forEachIndexed { index, mutate ->
                val fake = FakeMgdePosixOps(dir)
                mutate(fake)
                assertSame("$path case=$index", MgdeObservedSource.MalformedPresent, MgdeD7PublicationStore(fake).observe(nonce))
            }
        }
        assertSame(MgdeObservedSource.Absent, MgdeD7PublicationStore(FakeMgdePosixOps(dir)).observe(nonce))
    }

    @Test
    fun `observation uses nofollow fd fstat bounded max plus one and path reconciliation`() {
        val cases = listOf<(FakeMgdePosixOps) -> Unit>(
            { it.failNext = "openRead" },
            { it.failNext = "fstat" },
            { it.readOversize = true },
            { it.swapAfterRead = true },
        )
        cases.forEachIndexed { index, configure ->
            val fake = FakeMgdePosixOps(dir)
            fake.regular(record, byteArrayOf(1, 2, 3))
            configure(fake)
            assertSame("case=$index", MgdeObservedSource.MalformedPresent, MgdeD7PublicationStore(fake).observe(nonce))
        }
    }

    @Test
    fun `cleanup is policy disabled and preserves every formerly admitted residue exactly`() {
        listOf("stage", "record", "linked").forEach { residue ->
            val fake = FakeMgdePosixOps(dir)
            when (residue) {
                "stage" -> fake.regular(stage, byteArrayOf(1, 2))
                "record" -> fake.regular(record, byteArrayOf(3, 4))
                else -> {
                    fake.regular(stage, byteArrayOf(5, 6))
                    fake.hardlink(stage, record)
                }
            }
            val beforeNames = fake.immediateNames(dir)
            val beforeStats = beforeNames.associateWith { fake.statForAssertion("$dir/$it") }
            val beforeBytes = beforeNames.associateWith { fake.bytes("$dir/$it") }
            val beforeListCalls = fake.listCalls
            val beforeLstatCalls = fake.lstatCalls
            var removeHookReached = false
            fake.onRemove = { _, _ -> removeHookReached = true }

            assertSame(residue, MgdeCleanupResult.Rejected, MgdeD7PublicationStore(fake).cleanupResidue(nonce))
            assertEquals(beforeListCalls, fake.listCalls)
            assertEquals(beforeLstatCalls, fake.lstatCalls)
            assertEquals(beforeNames, fake.immediateNames(dir))
            beforeNames.forEach { name ->
                assertEquals("$residue stat $name", beforeStats[name], fake.statForAssertion("$dir/$name"))
                assertArrayEquals("$residue bytes $name", beforeBytes.getValue(name), fake.bytes("$dir/$name"))
            }
            assertEquals(0, fake.removeCalls)
            assertEquals(0, fake.rmdirCalls)
            assertEquals(0, fake.fsyncDirectoryCalls)
            assertFalse(removeHookReached)
        }
    }

    @Test
    fun `failure after link leaves exact nlink2 pair and cleanup preserves it`() {
        val fake = FakeMgdePosixOps(dir).also { it.failNext = "remove" }
        assertSame(MgdePublicationResult.Ambiguous, MgdeD7PublicationStore(fake).publish(nonce, byteArrayOf(1)))
        assertEquals(setOf(".stage", "record"), fake.immediateNames(dir))
        assertEquals(2, fake.statForAssertion(stage)!!.nlink)
        assertEquals(2, fake.statForAssertion(record)!!.nlink)
        val removeCallsBeforeCleanup = fake.removeCalls

        assertSame(MgdeCleanupResult.Rejected, MgdeD7PublicationStore(fake).cleanupResidue(nonce))
        assertEquals(removeCallsBeforeCleanup, fake.removeCalls)
        assertEquals(setOf(".stage", "record"), fake.immediateNames(dir))
        assertEquals(2, fake.statForAssertion(stage)!!.nlink)
        assertEquals(2, fake.statForAssertion(record)!!.nlink)
    }

    @Test
    fun `cleanup never touches foreign mixed or mismatched hardlink entries`() {
        val foreign = FakeMgdePosixOps(dir)
        foreign.regular(stage, byteArrayOf(1))
        foreign.regular("$dir/foreign", byteArrayOf(2))
        assertSame(MgdeCleanupResult.Rejected, MgdeD7PublicationStore(foreign).cleanupResidue(nonce))
        assertTrue(foreign.immediateNames(dir).contains("foreign"))
        assertEquals(0, foreign.removeCalls)

        val mismatched = FakeMgdePosixOps(dir)
        mismatched.regular(stage, byteArrayOf(1), nlink = 2)
        mismatched.regular(record, byteArrayOf(1), nlink = 2)
        assertSame(MgdeCleanupResult.Rejected, MgdeD7PublicationStore(mismatched).cleanupResidue(nonce))
        assertEquals(setOf(".stage", "record"), mismatched.immediateNames(dir))
        assertEquals(0, mismatched.removeCalls)
    }

    @Test
    fun `concurrent cleanup and rejected publication cannot mutate existing residue`() {
        val fake = FakeMgdePosixOps(dir)
        fake.regular(stage, byteArrayOf(7, 8, 9))
        val store = MgdeD7PublicationStore(fake)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val cleanup = pool.submit<MgdeCleanupResult> {
            start.await()
            store.cleanupResidue(nonce)
        }
        val publish = pool.submit<MgdePublicationResult> {
            start.await()
            store.publish(nonce, byteArrayOf(1))
        }
        start.countDown()

        assertSame(MgdeCleanupResult.Rejected, cleanup.get())
        assertSame(MgdePublicationResult.Rejected, publish.get())
        pool.shutdownNow()
        assertEquals(setOf(".stage"), fake.immediateNames(dir))
        assertArrayEquals(byteArrayOf(7, 8, 9), fake.bytes(stage))
        assertEquals(0, fake.removeCalls)
        assertEquals(0, fake.rmdirCalls)
    }

    @Test
    fun `missing root observes absent`() {
        val fake = FakeMgdePosixOps(dir).also { it.eraseRoot() }
        assertSame(MgdeObservedSource.Absent, MgdeD7PublicationStore(fake).observe(nonce))
        assertEquals(listOf(MgdeD7PublicationStore.ROOT), fake.lstatPaths)
    }

    @Test
    fun `secure root with missing session observes absent`() {
        val fake = FakeMgdePosixOps(dir).also { it.eraseSession() }
        assertSame(MgdeObservedSource.Absent, MgdeD7PublicationStore(fake).observe(nonce))
        assertEquals(listOf(MgdeD7PublicationStore.ROOT, dir), fake.lstatPaths)
    }

    @Test
    fun `absent root observes absent and publish rejects without mutation`() {
        val fake = FakeMgdePosixOps(dir).also { it.eraseRoot() }
        val store = MgdeD7PublicationStore(fake)
        assertSame(MgdeObservedSource.Absent, store.observe(nonce))
        val openWrites = fake.openWriteCalls
        val removes = fake.removeCalls
        val rmdirs = fake.rmdirCalls
        val paths = fake.paths()
        assertSame(MgdePublicationResult.Rejected, store.publish(nonce, byteArrayOf(1)))
        assertEquals(openWrites, fake.openWriteCalls)
        assertEquals(removes, fake.removeCalls)
        assertEquals(rmdirs, fake.rmdirCalls)
        assertEquals(paths, fake.paths())
    }
}

internal class FakeMgdePosixOps(private val session: String) : MgdePosixOps {
    private enum class Kind { DIR, REG, SYMLINK }
    private data class Node(
        val kind: Kind,
        val dev: Long = 1,
        val ino: Long,
        var uid: Int,
        var gid: Int,
        var mode: Int,
        var nlink: Long,
        var label: String,
        var bytes: ByteArray = ByteArray(0),
    )

    private val nodes = LinkedHashMap<String, Node>()
    private val fds = IdentityHashMap<FileDescriptor, Node>()
    private var nextIno = 10L
    var listCalls = 0
    var lstatCalls = 0
    val lstatPaths = mutableListOf<String>()
    var failNext: String? = null
    var partialWrite = false
    var unreadableLists = false
    var readOversize = false
    var swapAfterRead = false
    var swapStageAfterFstat = false
    var openExclusiveEexist = false
    var linkDestinationExists = false
    var swapStageAfterLink = false
    var swapRecordAfterLink = false
    var linkCalls = 0
    var removeCalls = 0
    var rmdirCalls = 0
    var fsyncDirectoryCalls = 0
    var openWriteCalls = 0
    var onList: ((Int) -> Unit)? = null
    var onRemove: ((String, Int) -> Unit)? = null
    private val unknownStatPaths = mutableSetOf<String>()
    private val absentStatPaths = mutableSetOf<String>()

    init {
        directory(MgdeD7PublicationStore.ROOT)
        directory(session)
    }

    @Synchronized
    fun injectUnknownStat(path: String) {
        absentStatPaths.remove(path)
        unknownStatPaths += path
    }

    @Synchronized
    fun injectAbsentStat(path: String) {
        unknownStatPaths.remove(path)
        absentStatPaths += path
    }

    @Synchronized
    fun statForAssertion(path: String): MgdeFileStat? = nodes[path]?.stat()

    @Synchronized
    override fun immediateNames(directory: String): Set<String> {
        listCalls++
        onList?.invoke(listCalls)
        if (unreadableLists || fail("list")) return setOf("<unreadable>")
        val prefix = "$directory/"
        return nodes.keys.mapNotNull { path ->
            if (path.startsWith(prefix)) path.removePrefix(prefix).takeIf { '/' !in it } else null
        }.toSet()
    }

    @Synchronized
    override fun openExclusiveNoFollow(path: String, mode: Int): FileDescriptor {
        openWriteCalls++
        if (openExclusiveEexist || fail("openWrite") || nodes.containsKey(path)) error("EEXIST")
        val node = Node(Kind.REG, ino = nextIno++, uid = 0, gid = 0, mode = OsConstants.S_IFREG or mode, nlink = 1, label = LABEL)
        nodes[path] = node
        return FileDescriptor().also { fds[it] = node }
    }

    @Synchronized
    override fun openReadNoFollow(path: String): FileDescriptor {
        if (fail("openRead")) error("open")
        val node = nodes[path] ?: error("ENOENT")
        if (node.kind != Kind.REG) error("ELOOP")
        return FileDescriptor().also { fds[it] = node }
    }

    @Synchronized
    override fun writeAll(fd: FileDescriptor, bytes: ByteArray): Boolean {
        val node = fds[fd] ?: return false
        if (fail("write")) return false
        node.bytes = if (partialWrite) bytes.copyOf(bytes.size / 2) else bytes.copyOf()
        return !partialWrite
    }

    @Synchronized
    override fun readBounded(fd: FileDescriptor, maxBytes: Int): ByteArray? {
        val node = fds[fd] ?: return null
        val result = if (readOversize) ByteArray(maxBytes + 1) else node.bytes.copyOf()
        if (swapAfterRead) {
            val path = nodes.entries.singleOrNull { it.value === node }?.key
            if (path != null) regular(path, byteArrayOf(9))
        }
        return result.takeIf { it.size in 1..maxBytes }
    }

    @Synchronized override fun fsync(fd: FileDescriptor): Boolean = !fail("fsyncFile")
    @Synchronized override fun close(fd: FileDescriptor) { fds.remove(fd) }
    @Synchronized
    override fun lstat(path: String): MgdeStatResult {
        lstatCalls++
        lstatPaths += path
        if (path in unknownStatPaths) return MgdeStatResult.Unknown
        if (path in absentStatPaths) return MgdeStatResult.Absent
        val node = nodes[path] ?: return MgdeStatResult.Absent
        return MgdeStatResult.Present(node.stat())
    }
    @Synchronized
    override fun fstat(fd: FileDescriptor): MgdeFileStat? {
        if (fail("fstat")) return null
        val node = fds[fd] ?: return null
        val result = node.stat()
        if (swapStageAfterFstat) {
            val path = nodes.entries.singleOrNull { it.value === node }?.key
            if (path?.endsWith("/.stage") == true) {
                regular(path, byteArrayOf(9))
                swapStageAfterFstat = false
            }
        }
        return result
    }
    @Synchronized override fun securityLabel(path: String): String? = nodes[path]?.label

    @Synchronized
    override fun linkNoReplace(existing: String, destination: String): MgdeLinkNoReplaceResult {
        linkCalls++
        if (linkDestinationExists || nodes.containsKey(destination)) {
            return MgdeLinkNoReplaceResult.DESTINATION_EXISTS
        }
        if (fail("link")) return MgdeLinkNoReplaceResult.FAILED
        val node = nodes[existing] ?: return MgdeLinkNoReplaceResult.FAILED
        if (node.kind != Kind.REG) return MgdeLinkNoReplaceResult.FAILED
        node.nlink++
        nodes[destination] = node
        if (swapStageAfterLink) {
            regular(existing, byteArrayOf(8))
            swapStageAfterLink = false
        }
        if (swapRecordAfterLink) {
            regular(destination, byteArrayOf(9))
            swapRecordAfterLink = false
        }
        return MgdeLinkNoReplaceResult.LINKED
    }

    @Synchronized
    override fun removeExact(path: String): Boolean {
        removeCalls++
        if (fail("remove")) return false
        val node = nodes.remove(path) ?: return false
        node.nlink--
        onRemove?.invoke(path, removeCalls)
        return true
    }

    @Synchronized
    override fun rmdirExact(path: String): Boolean {
        rmdirCalls++
        if (fail("rmdir") || immediateNames(path).isNotEmpty()) return false
        return nodes.remove(path)?.kind == Kind.DIR
    }

    @Synchronized
    override fun fsyncDirectory(directory: String): Boolean {
        fsyncDirectoryCalls++
        return !fail("fsyncDirectory")
    }

    @Synchronized
    fun regular(
        path: String,
        bytes: ByteArray,
        uid: Int = 0,
        gid: Int = 0,
        mode: Int = 0x180,
        label: String = LABEL,
        nlink: Long = 1,
    ) {
        nodes[path] = Node(Kind.REG, ino = nextIno++, uid = uid, gid = gid, mode = OsConstants.S_IFREG or mode, nlink = nlink, label = label, bytes = bytes.copyOf())
    }

    @Synchronized
    fun symlink(path: String) {
        nodes[path] = Node(Kind.SYMLINK, ino = nextIno++, uid = 0, gid = 0, mode = OsConstants.S_IFLNK or 0x1ff, nlink = 1, label = LABEL)
    }

    @Synchronized
    fun eraseRoot() {
        nodes.remove(MgdeD7PublicationStore.ROOT)
    }

    @Synchronized
    fun eraseSession() {
        nodes.remove(session)
    }

    @Synchronized
    fun hardlink(existing: String, destination: String) {
        check(linkNoReplace(existing, destination) == MgdeLinkNoReplaceResult.LINKED)
    }
    @Synchronized fun bytes(path: String): ByteArray = nodes.getValue(path).bytes.copyOf()

    @Synchronized
    fun replaceDirectoryWithSymlink(path: String) {
        nodes[path] = Node(Kind.SYMLINK, ino = nextIno++, uid = 0, gid = 0, mode = OsConstants.S_IFLNK or 0x1ff, nlink = 1, label = LABEL)
    }

    @Synchronized
    fun reallocateDirectory(path: String) {
        val previous = nodes.getValue(path)
        nodes[path] = previous.copy(ino = nextIno++)
    }

    @Synchronized
    fun mutateMetadata(
        path: String,
        uid: Int? = null,
        gid: Int? = null,
        mode: Int? = null,
        label: String? = null,
    ) {
        val node = nodes.getValue(path)
        if (uid != null) node.uid = uid
        if (gid != null) node.gid = gid
        if (mode != null) node.mode = mode
        if (label != null) node.label = label
    }

    private fun directory(path: String) {
        nodes[path] = Node(Kind.DIR, ino = nextIno++, uid = 0, gid = 0, mode = OsConstants.S_IFDIR or 0x1c0, nlink = 2, label = LABEL)
    }

    @Synchronized fun paths(): Set<String> = nodes.keys.toSet()

    private fun Node.stat() = MgdeFileStat(dev, ino, uid, gid, mode, nlink)
    private fun fail(operation: String): Boolean =
        (failNext == operation).also { if (it) failNext = null }

    private companion object {
        const val LABEL = "u:object_r:shell_data_file:s0"
    }
}
