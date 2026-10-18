package com.gruning.mimir.tweaker

import com.gruning.mimir.tweaker.FamilyCatalogStore.PromoteResult
import com.gruning.mimir.tweaker.FamilyCatalogStore.Source
import com.gruning.mimir.tweaker.FamilyCatalogStore.StageResult
import com.gruning.mimir.tweaker.FamilyCatalogTestSupport.bundledBytes
import com.gruning.mimir.tweaker.FamilyCatalogTestSupport.withRevision
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The store's load rule, its idempotent staging and idle-only promotion, and its failure handling. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class FamilyCatalogStoreTest {
    private lateinit var directory: File
    private val logged = ArrayList<String>()

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("family_catalog_test").toFile()
        logged.clear()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun store(
        bundled: () -> ByteArray? = { bundledBytes() },
        gate: (ByteArray, ByteArray?) -> Boolean = { _, _ -> true },
    ) = FamilyCatalogStore(File(directory, FamilyCatalogStore.DIRECTORY), bundled, gate) { logged += it }

    private fun file(name: String) = File(File(directory, FamilyCatalogStore.DIRECTORY), name)

    private fun leftovers() = File(directory, FamilyCatalogStore.DIRECTORY).listFiles().orEmpty().map { it.name }.filter { it.endsWith(".tmp") }

    @Test
    fun `with no download the bundled catalog is the one in force`() {
        val loaded = store().load()

        assertEquals(Source.BUNDLED, loaded.source)
        assertEquals(1, loaded.catalog!!.revision)
        assertTrue(loaded.notes.isEmpty())
        assertFalse(File(directory, FamilyCatalogStore.DIRECTORY).exists())
    }

    @Test
    fun `an unreadable or invalid bundled catalog and nothing else leaves no catalog and never throws`() {
        listOf<() -> ByteArray?>({ null }, { error("asset missing") }, { ByteArray(0) }, { "garbage".toByteArray() }).forEach { bundled ->
            val loaded = store(bundled).load()
            assertEquals(Source.NONE, loaded.source)
            assertNull(loaded.catalog)
            assertTrue(loaded.notes.isNotEmpty())
        }
        assertTrue("the notes are logged", logged.isNotEmpty())
    }

    @Test
    fun `a newer verified catalog stages, waits for idle, promotes atomically and then wins`() {
        val store = store()
        val newer = withRevision(2)

        assertEquals(StageResult.Staged(2), store.stageVerified(newer, "sig-bytes".toByteArray()))
        assertTrue(file("staged.json").isFile)
        assertArrayEquals(newer, file("staged.json").readBytes())
        // Staged is not in force.
        assertEquals(Source.BUNDLED, store.load().source)

        assertSame(PromoteResult.NotIdle, store.promoteStagedIfIdle { false })
        assertTrue(file("staged.json").isFile)
        assertFalse(file("active.json").exists())
        assertEquals(Source.BUNDLED, store.load().source)

        assertEquals(PromoteResult.Promoted(2), store.promoteStagedIfIdle { true })
        assertFalse(file("staged.json").exists())
        assertFalse(file("staged.json.sig").exists())
        assertArrayEquals(newer, file("active.json").readBytes())
        assertArrayEquals("sig-bytes".toByteArray(), file("active.json.sig").readBytes())
        assertTrue("no temporary file is left behind: ${leftovers()}", leftovers().isEmpty())

        val loaded = store.load()
        assertEquals(Source.ACTIVE, loaded.source)
        assertEquals(2, loaded.catalog!!.revision)
        // A fresh store over the same directory (a new process) sees the same thing.
        assertEquals(2, store().load().catalog!!.revision)
        assertSame(PromoteResult.NothingStaged, store.promoteStagedIfIdle { true })
    }

    @Test
    fun `staging is idempotent and never goes backwards`() {
        val store = store()
        assertEquals(StageResult.Staged(3), store.stageVerified(withRevision(3)))

        assertEquals(StageResult.Unchanged(3), store.stageVerified(withRevision(3)))
        assertEquals(StageResult.NotNewer(3), store.stageVerified(withRevision(2)))
        // Different bytes at the same revision are not newer either.
        assertEquals(StageResult.NotNewer(3), store.stageVerified(FamilyCatalogTestSupport.mutated { it.put("revision", 3).put("note", "same revision, other bytes") }))
        assertEquals(StageResult.Staged(4), store.stageVerified(withRevision(4)))
        assertArrayEquals(withRevision(4), file("staged.json").readBytes())

        assertEquals(PromoteResult.Promoted(4), store.promoteStagedIfIdle { true })
        // The same bytes again, now active, are a no-op; nothing at or below the active revision is accepted.
        assertEquals(StageResult.Unchanged(4), store.stageVerified(withRevision(4)))
        assertEquals(StageResult.NotNewer(4), store.stageVerified(withRevision(1)))
        assertFalse(file("staged.json").exists())
    }

    @Test
    fun `the bundled revision is the floor for staging`() {
        val bundled = withRevision(5)
        val store = store(bundled = { bundled })

        assertEquals(StageResult.NotNewer(5), store.stageVerified(withRevision(5)))
        assertEquals(StageResult.NotNewer(5), store.stageVerified(withRevision(4)))
        assertEquals(StageResult.Staged(6), store.stageVerified(withRevision(6)))
    }

    @Test
    fun `a rejected catalog is never persisted and the active one stays`() {
        val store = store()
        store.stageVerified(withRevision(2))
        store.promoteStagedIfIdle { true }

        val forward = FamilyCatalogTestSupport.mutated { it.put("schema", 2).put("revision", 9) }
        val missingReserved = FamilyCatalogTestSupport.mutated { it.put("revision", 9).getJSONArray("families").remove(0) }
        val results = listOf(
            store.stageVerified(forward),
            store.stageVerified(missingReserved),
            store.stageVerified("not json".toByteArray()),
            store.stageVerified(ByteArray(FamilyCatalogLimits.MAX_BYTES + 1)),
        )

        assertEquals(CatalogRejection.SCHEMA, (results[0] as StageResult.Invalid).reason)
        assertEquals(CatalogRejection.RESERVED_ID, (results[1] as StageResult.Invalid).reason)
        assertEquals(CatalogRejection.SYNTAX, (results[2] as StageResult.Invalid).reason)
        assertEquals(CatalogRejection.TOO_LARGE, (results[3] as StageResult.Invalid).reason)
        assertFalse(file("staged.json").exists())
        assertEquals(2, store.load().catalog!!.revision)
    }

    @Test
    fun `a corrupt or unreadable active file falls back to the bundled catalog`() {
        val store = store()
        file("active.json").also { it.parentFile!!.mkdirs() }.writeBytes("{ this is not a catalog".toByteArray())

        var loaded = store.load()
        assertEquals(Source.BUNDLED, loaded.source)
        assertEquals(1, loaded.catalog!!.revision)
        assertTrue(loaded.notes.toString(), loaded.notes.any { it.contains("active catalog rejected") })

        // A catalog that parses but breaks a whole-catalog rule is as good as corrupt.
        file("active.json").writeBytes(FamilyCatalogTestSupport.mutated { it.put("revision", 8).put("schema", 3) })
        assertEquals(Source.BUNDLED, store.load().source)

        file("active.json").writeBytes(ByteArray(0))
        assertEquals(Source.BUNDLED, store.load().source)

        // An oversize file is refused without being read whole.
        file("active.json").writeBytes(ByteArray(FamilyCatalogLimits.MAX_BYTES + 1) { ' '.code.toByte() })
        loaded = store.load()
        assertEquals(Source.BUNDLED, loaded.source)
        assertTrue(loaded.notes.toString(), loaded.notes.any { it.contains("unreadable") })

        // A directory where the file should be is not a crash either.
        file("active.json").delete()
        assertTrue(file("active.json").mkdirs())
        assertEquals(Source.BUNDLED, store.load().source)
    }

    @Test
    fun `an active catalog below the bundled revision loses and an equal one wins`() {
        val bundled = withRevision(5)
        val store = store(bundled = { bundled })
        file("active.json").also { it.parentFile!!.mkdirs() }.writeBytes(withRevision(2))

        val older = store.load()
        assertEquals(Source.BUNDLED, older.source)
        assertEquals(5, older.catalog!!.revision)
        assertTrue(older.notes.toString(), older.notes.any { it.contains("below bundled") })

        file("active.json").writeBytes(withRevision(5))
        assertEquals(Source.ACTIVE, store.load().source)
        file("active.json").writeBytes(withRevision(6))
        assertEquals(6, store.load().catalog!!.revision)
    }

    @Test
    fun `an active catalog is used even when the bundled one is unusable`() {
        file("active.json").also { it.parentFile!!.mkdirs() }.writeBytes(withRevision(2))

        val loaded = store(bundled = { null }).load()

        assertEquals(Source.ACTIVE, loaded.source)
        assertEquals(2, loaded.catalog!!.revision)
    }

    @Test
    fun `the load gate sees the stored signature and can refuse the active catalog`() {
        val seen = ArrayList<Pair<Int, String?>>()
        val allow = booleanArrayOf(true)
        val store = store(gate = { bytes, signature ->
            seen += bytes.size to signature?.let { String(it) }
            allow[0]
        })
        store.stageVerified(withRevision(2), "the-signature".toByteArray())
        store.promoteStagedIfIdle { true }

        assertEquals(Source.ACTIVE, store.load().source)
        assertTrue(seen.toString(), seen.any { it.second == "the-signature" })

        allow[0] = false
        val refused = store.load()
        assertEquals(Source.BUNDLED, refused.source)
        assertTrue(refused.notes.toString(), refused.notes.any { it.contains("load gate") })
    }

    @Test
    fun `a staged catalog the load gate refuses is discarded, never promoted over a trusted active one`() {
        val goodSignature = "good-sig".toByteArray()
        val allowSig = booleanArrayOf(true)
        val store = store(gate = { _, signature ->
            // Accept only the good signature.
            signature?.contentEquals(goodSignature) == true
        })

        // Stage and promote revision 2 with the good signature; it becomes active and trusted.
        assertEquals(StageResult.Staged(2), store.stageVerified(withRevision(2), goodSignature))
        assertEquals(PromoteResult.Promoted(2), store.promoteStagedIfIdle { true })
        assertEquals(Source.ACTIVE, store.load().source)
        assertEquals(2, store.load().catalog!!.revision)

        // Stage a higher revision with the good signature.
        assertEquals(StageResult.Staged(3), store.stageVerified(withRevision(3), goodSignature))
        assertTrue(file("staged.json").isFile)

        // Damage the staged signature file on disk, simulating a corrupted .sig.
        file("staged.json.sig").writeBytes("corrupted-bytes".toByteArray())

        // Attempt promotion: the damaged signature is refused by the gate, so the staged catalog is discarded.
        val result = store.promoteStagedIfIdle { true }
        assertTrue(result.toString(), result is PromoteResult.Discarded)

        // The active catalog (revision 2) stays in force, not replaced by the discarded staged one.
        assertEquals(Source.ACTIVE, store.load().source)
        assertEquals(2, store.load().catalog!!.revision)

        // The staged files are gone; they are not retried.
        assertFalse(file("staged.json").exists())
        assertFalse(file("staged.json.sig").exists())
    }

    @Test
    fun `an unsigned stage removes a stale signature`() {
        val store = store()
        store.stageVerified(withRevision(2), "old".toByteArray())
        assertTrue(file("staged.json.sig").isFile)

        store.stageVerified(withRevision(3), null)

        assertFalse(file("staged.json.sig").exists())
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun `an unusable staged file is discarded rather than retried forever`() {
        val store = store()
        file("staged.json").also { it.parentFile!!.mkdirs() }.writeBytes("garbage".toByteArray())

        val garbage = store.promoteStagedIfIdle { true }
        assertTrue(garbage.toString(), garbage is PromoteResult.Discarded)
        assertFalse(file("staged.json").exists())

        // Staged under an older bundled revision, then the app was upgraded past it.
        val oldStore = store(bundled = { withRevision(1) })
        assertEquals(StageResult.Staged(2), oldStore.stageVerified(withRevision(2)))
        val upgraded = store(bundled = { withRevision(7) })
        val result = upgraded.promoteStagedIfIdle { true }
        assertTrue(result.toString(), result is PromoteResult.Discarded)
        assertFalse(file("staged.json").exists())
        assertEquals(7, upgraded.load().catalog!!.revision)
        assertTrue(logged.any { it.contains("discarded") })
    }

    @Test
    fun `promotion checks idleness only when something is staged`() {
        var asked = 0
        val store = store()

        assertSame(PromoteResult.NothingStaged, store.promoteStagedIfIdle { asked++; true })
        assertEquals("idleness is not consulted when there is nothing to promote", 0, asked)

        store.stageVerified(withRevision(2))
        assertSame(PromoteResult.NotIdle, store.promoteStagedIfIdle { asked++; false })
        assertEquals(1, asked)
    }

    @Test
    fun `a write that cannot happen is reported and never throws`() {
        // The directory is a plain file, so nothing can be created under it.
        val blocker = File(directory, "blocked").also { it.writeText("x") }
        val store = FamilyCatalogStore(File(blocker, FamilyCatalogStore.DIRECTORY), { bundledBytes() })

        assertSame(StageResult.WriteFailed, store.stageVerified(withRevision(2)))
        assertEquals(Source.BUNDLED, store.load().source)
        assertSame(PromoteResult.NothingStaged, store.promoteStagedIfIdle { true })
    }

    @Test
    fun `the registry installs what the store loads and a null means nothing is installed`() {
        val store = store()
        try {
            FamilyCatalogRegistry.install(null)
            assertNull(FamilyCatalogRegistry.current())

            val loaded = FamilyCatalogRegistry.installFrom(store)

            assertNotNull(FamilyCatalogRegistry.current())
            assertSame(loaded.catalog, FamilyCatalogRegistry.current())
        } finally {
            FamilyCatalogRegistry.install(null)
        }
    }

    @Test
    fun `sha256 hex is lower case and stable`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            FamilyCatalogStore.sha256Hex(ByteArray(0)),
        )
        assertEquals(64, FamilyCatalogStore.sha256Hex(bundledBytes()).length)
    }
}
