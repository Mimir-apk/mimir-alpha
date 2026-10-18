package com.gruning.mimir.tweaker

import com.gruning.mimir.tweaker.FamilyCatalogStore.Source
import com.gruning.mimir.tweaker.FamilyCatalogTestSupport.bundledBytes
import com.gruning.mimir.tweaker.FamilyCatalogTestSupport.extraEntry
import com.gruning.mimir.tweaker.FamilyCatalogTestSupport.mutated
import java.io.File
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import org.json.JSONObject
import org.junit.After
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The sink the fetcher stages into, the idle-only promotion, and the load-time trust gate, over a real
 * [FamilyCatalogStore] in a temp directory.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class FamilyCatalogPromotionTest {
    private lateinit var directory: File
    private val pair: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val keyBase64 = Base64.getEncoder().encodeToString(checkNotNull(ed25519RawPublicKey(pair.public.encoded)))
    private val logged = ArrayList<String>()

    private val mt9999Policies = FamilyCatalogGoldenFixtures.all().single { it.id == "general-mt9999" }.capabilities.cpuPolicies

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("family_promotion_test").toFile()
        FamilyCatalogStores.clearForTest()
        FamilyCatalogRegistry.install(null)
        SupportedUncertifiedProfiles.clearComputeMemoForTest()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
        FamilyCatalogStores.clearForTest()
        FamilyCatalogRegistry.install(null)
        SupportedUncertifiedProfiles.clearComputeMemoForTest()
    }

    private fun store(bundled: () -> ByteArray? = { bundledBytes() }, key: String = keyBase64) =
        FamilyCatalogStores.forDirectory(directory, bundled, key)

    private fun file(name: String) = File(directory, name)

    private fun sign(bytes: ByteArray): ByteArray = Signature.getInstance("Ed25519").run {
        initSign(pair.private)
        update(bytes)
        sign()
    }

    /** The bundled catalog plus the MT9999 family as a certified reference, at [revision]. */
    private fun catalog(revision: Int, socs: List<String> = listOf("MT9999")): ByteArray = mutated { root ->
        root.put("revision", revision)
        val entry = JSONObject(
            """
            {
              "id": "mediatek-mt9999",
              "label": "MediaTek MT9999 test family",
              "treatment": "mimir-certified-reference",
              "match": { "soc": ${socs.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }} },
              "cpu": {
                "kind": "khz",
                "profiles": {
                  "quiet": { "0": 300000, "4": 500000 },
                  "balanced": { "0": 700000, "4": 800000 },
                  "performance": { "0": 900000, "4": 1100000 }
                }
              }
            }
            """.trimIndent(),
        )
        root.getJSONArray("families").put(entry)
    }

    private fun admitted() = SupportedUncertifiedProfiles.compute(CapabilityStatus.SUPPORTED, "MT9999", mt9999Policies)

    // ---- the sink -----------------------------------------------------------------------------

    @Test
    fun `the sink reports the hash of the bytes the store actually chose`() {
        val store = store()
        val sink = FamilyCatalogStoreSink(store) { logged += it }
        assertEquals(FamilyCatalogStore.sha256Hex(bundledBytes()), sink.activeSha256Hex())

        val rev2 = catalog(2)
        assertTrue(sink.promoteVerified(rev2, sign(rev2)))
        // Staged is not in force: the hash is still the bundled one, so the fetcher's same-sha no-op is not fooled.
        assertEquals(FamilyCatalogStore.sha256Hex(bundledBytes()), sink.activeSha256Hex())

        assertTrue(FamilyCatalogPromotion.promoteIfIdle(store, isIdle = { true }, invalidate = {}))
        assertEquals(FamilyCatalogStore.sha256Hex(rev2), sink.activeSha256Hex())
    }

    @Test
    fun `the sink hashes the bundled bytes when the active catalog is below the bundled revision`() {
        val rev2 = catalog(2)
        val first = store()
        assertTrue(FamilyCatalogStoreSink(first).promoteVerified(rev2, sign(rev2)))
        assertTrue(FamilyCatalogPromotion.promoteIfIdle(first, isIdle = { true }, invalidate = {}))
        assertEquals(Source.ACTIVE, first.load().source)

        // An APK upgrade ships a newer bundled catalog: it wins, and its hash is the one in force.
        val newerBundled = catalog(3, socs = listOf("MT0001"))
        val upgraded = FamilyCatalogStore(directory, { newerBundled }, FamilyCatalogStores.activeGate(keyBase64)) { logged += it }
        val loaded = upgraded.load()
        assertEquals(Source.BUNDLED, loaded.source)
        assertEquals(FamilyCatalogStore.sha256Hex(newerBundled), loaded.sha256Hex)
        assertEquals(FamilyCatalogStore.sha256Hex(newerBundled), FamilyCatalogStoreSink(upgraded).activeSha256Hex())
    }

    @Test
    fun `the sink stages and never promotes, and declines what the store declines`() {
        val store = store()
        val sink = FamilyCatalogStoreSink(store) { logged += it }
        val rev2 = catalog(2)

        assertTrue(sink.promoteVerified(rev2, sign(rev2)))
        assertTrue("staged, not in force", file("staged.json").isFile)
        assertFalse(file("active.json").exists())
        assertNull(FamilyCatalogRegistry.current())
        assertEquals("general-live-opp", TweakerHardwareFamilies.resolve("MT9999").id)

        // The same bytes again are an idempotent success, nothing new on disk.
        assertTrue(sink.promoteVerified(rev2, sign(rev2)))

        // A revision at or below the floor, and unparseable bytes, are declined without throwing.
        val rev1 = catalog(1)
        assertFalse(sink.promoteVerified(rev1, sign(rev1)))
        assertFalse(sink.promoteVerified("not json".toByteArray(), ByteArray(64)))
        assertTrue(logged.any { "declined" in it })
    }

    // ---- promotion ----------------------------------------------------------------------------

    @Test
    fun `an idle apply promotes, installs, clears the memo and only then invalidates`() {
        val store = store()
        FamilyCatalogRegistry.installFrom(store)
        assertEquals(emptySet<String>(), admitted()) // memoised: the family is unknown today
        val rev2 = catalog(2)
        assertTrue(FamilyCatalogStoreSink(store).promoteVerified(rev2, sign(rev2)))

        var invalidations = 0
        var familySeenByInvalidate: String? = null
        var admittedSeenByInvalidate: Set<String>? = null
        val promoted = FamilyCatalogPromotion.promoteIfIdle(
            store,
            isIdle = { true },
            invalidate = {
                invalidations++
                familySeenByInvalidate = TweakerHardwareFamilies.resolve("MT9999").id
                admittedSeenByInvalidate = admitted()
            },
        )

        assertTrue(promoted)
        assertEquals(1, invalidations)
        assertEquals("the registry is updated before the topology is dropped", "mediatek-mt9999", familySeenByInvalidate)
        assertEquals(setOf("quiet", "balanced", "performance"), admittedSeenByInvalidate)
        assertEquals("mediatek-mt9999", TweakerHardwareFamilies.resolve("MT9999").id)
        assertEquals(2, FamilyCatalogRegistry.current()!!.revision)
        assertFalse("staged moved to active", file("staged.json").exists())
        assertTrue(file("active.json").isFile)
        assertEquals(Source.ACTIVE, store.load().source)

        // Nothing staged any more: a further idle apply is a no-op and invalidates nothing.
        assertFalse(FamilyCatalogPromotion.promoteIfIdle(store, isIdle = { true }, invalidate = { invalidations++ }))
        assertEquals(1, invalidations)
    }

    @Test
    fun `an active session blocks promotion and the staged catalog waits for the next idle apply`() {
        val store = store()
        FamilyCatalogRegistry.installFrom(store)
        val rev2 = catalog(2)
        assertTrue(FamilyCatalogStoreSink(store).promoteVerified(rev2, sign(rev2)))

        var invalidations = 0
        assertFalse(FamilyCatalogPromotion.promoteIfIdle(store, isIdle = { false }, invalidate = { invalidations++ }))
        assertEquals(0, invalidations)
        assertEquals("general-live-opp", TweakerHardwareFamilies.resolve("MT9999").id)
        assertEquals(1, FamilyCatalogRegistry.current()!!.revision)
        assertTrue("still staged", file("staged.json").isFile)
        assertFalse(file("active.json").exists())

        assertTrue(FamilyCatalogPromotion.promoteIfIdle(store, isIdle = { true }, invalidate = { invalidations++ }))
        assertEquals(1, invalidations)
        assertEquals("mediatek-mt9999", TweakerHardwareFamilies.resolve("MT9999").id)
    }

    private fun orphanedJournal() = TransactionJournalRecord(
        schemaVersion = TRANSACTION_JOURNAL_SCHEMA_V1,
        generation = 1,
        requestId = "req-1",
        transactionId = "tx-1",
        hardwareFingerprint = "fp",
        deviceProfile = "ayn-odin3-cq8725s-pserver",
        planHash = "ph",
        profileId = "quiet",
        targetPackage = "",
        phase = TransactionPhase.APPLIED,
        creationTime = 1,
        requestDeadline = 2,
        leaseDeadline = 3,
        bootIdentity = "boot",
        snapshot = HardwareSnapshot(
            schemaVersion = HARDWARE_SNAPSHOT_SCHEMA,
            hardwareFingerprint = "fp",
            bootIdentity = "boot",
            nodes = emptyList(),
        ),
        expectedTopologySha256 = "e",
        snapshotSha256 = "s",
        lastCompletedMutationBoundary = 1,
    )

    /**
     * F-P3-5 (2026-10-13 review): the idle check was `supervisor.activeSession() == null` alone, so a crashed
     * daemon's session still awaiting restore (a journal, no live session) did not hold promotion back.
     */
    @Test
    fun `a journal awaiting restore blocks promotion even when no session is live`() {
        assertTrue(FamilyCatalogPromotion.isIdle(sessionActive = false, journal = JournalLoadResult.Empty))
        assertFalse(FamilyCatalogPromotion.isIdle(sessionActive = true, journal = JournalLoadResult.Empty))
        assertFalse(FamilyCatalogPromotion.isIdle(sessionActive = false, journal = JournalLoadResult.Loaded(orphanedJournal())))
        assertFalse(FamilyCatalogPromotion.isIdle(sessionActive = false, journal = JournalLoadResult.Corrupt("unreadable")))
        assertFalse(FamilyCatalogPromotion.isIdle(sessionActive = true, journal = JournalLoadResult.Loaded(orphanedJournal())))

        val store = store()
        FamilyCatalogRegistry.installFrom(store)
        val rev2 = catalog(2)
        assertTrue(FamilyCatalogStoreSink(store).promoteVerified(rev2, sign(rev2)))
        var journal: JournalLoadResult = JournalLoadResult.Loaded(orphanedJournal())
        var invalidations = 0
        val promote = {
            FamilyCatalogPromotion.promoteIfIdle(
                store,
                isIdle = { FamilyCatalogPromotion.isIdle(sessionActive = false, journal = journal) },
                invalidate = { invalidations++ },
            )
        }

        assertFalse("the orphaned journal holds the staged catalog back", promote())
        assertTrue("still staged", file("staged.json").isFile)
        assertFalse(file("active.json").exists())
        assertEquals("general-live-opp", TweakerHardwareFamilies.resolve("MT9999").id)

        journal = JournalLoadResult.Corrupt("unreadable")
        assertFalse(promote())

        // The restore settled and the journal is gone: the next idle apply promotes it.
        journal = JournalLoadResult.Empty
        assertTrue(promote())
        assertEquals(1, invalidations)
        assertEquals("mediatek-mt9999", TweakerHardwareFamilies.resolve("MT9999").id)
    }

    @Test
    fun `a bad staged file is discarded, nothing is invalidated and the previous catalog is kept`() {
        val store = store()
        val rev2 = catalog(2)
        assertTrue(FamilyCatalogStoreSink(store).promoteVerified(rev2, sign(rev2)))
        assertTrue(FamilyCatalogPromotion.promoteIfIdle(store, isIdle = { true }, invalidate = {}))
        assertEquals(2, FamilyCatalogRegistry.current()!!.revision)

        var invalidations = 0
        listOf(
            "garbage, not a catalog".toByteArray(),
            catalog(2, socs = listOf("MT0002")), // valid, but not above the active revision
            ByteArray(FamilyCatalogLimits.MAX_BYTES + 1) { ' '.code.toByte() }, // oversize
        ).forEach { bad ->
            file("staged.json").writeBytes(bad)
            assertFalse(FamilyCatalogPromotion.promoteIfIdle(store, isIdle = { true }, invalidate = { invalidations++ }))
            assertFalse("the unusable staged file is removed, not retried forever", file("staged.json").exists())
            assertEquals(2, FamilyCatalogRegistry.current()!!.revision)
            assertEquals("mediatek-mt9999", TweakerHardwareFamilies.resolve("MT9999").id)
        }
        assertEquals(0, invalidations)
        assertEquals(Source.ACTIVE, store.load().source)
    }

    @Test
    fun `promotion never throws into an apply`() {
        val store = store()
        val rev2 = catalog(2)
        assertTrue(FamilyCatalogStoreSink(store).promoteVerified(rev2, sign(rev2)))
        assertFalse(FamilyCatalogPromotion.promoteIfIdle(store, isIdle = { error("supervisor unavailable") }, invalidate = {}))
        assertTrue("an idle-check failure leaves it staged", file("staged.json").isFile)

        // The promotion stands even if the follow-up steps fail, and each step is independent.
        var invalidated = false
        assertTrue(
            FamilyCatalogPromotion.promoteIfIdle(
                store,
                isIdle = { true },
                invalidate = { invalidated = true; error("probe gone") },
                clearMemos = { error("memo") },
            ),
        )
        assertTrue(invalidated)
        assertEquals("mediatek-mt9999", TweakerHardwareFamilies.resolve("MT9999").id)
    }

    // ---- the load-time trust gate -------------------------------------------------------------

    @Test
    fun `an active catalog is used only while its stored signature verifies`() {
        val store = store()
        val rev2 = catalog(2)
        assertTrue(FamilyCatalogStoreSink(store).promoteVerified(rev2, sign(rev2)))
        assertTrue(FamilyCatalogPromotion.promoteIfIdle(store, isIdle = { true }, invalidate = {}))
        assertEquals(Source.ACTIVE, store.load().source)

        // The signature is damaged: the catalog is not trusted, the bundled one is.
        val signature = file("active.json.sig").readBytes()
        file("active.json.sig").writeBytes(signature.also { it[0] = (it[0].toInt() xor 0x01).toByte() })
        assertEquals(Source.BUNDLED, store.load().source)
        FamilyCatalogRegistry.installFrom(store)
        assertEquals("general-live-opp", TweakerHardwareFamilies.resolve("MT9999").id)

        // The signature is gone.
        file("active.json.sig").delete()
        assertEquals(Source.BUNDLED, store.load().source)

        // The catalog bytes are edited under a once-valid signature.
        file("active.json.sig").writeBytes(sign(rev2))
        assertEquals(Source.ACTIVE, store.load().source)
        file("active.json").writeBytes(catalog(2, socs = listOf("MT0003")))
        assertEquals(Source.BUNDLED, store.load().source)
    }

    /** A key rotation: the build now carries a new key, and the catalog on disk was signed under the old one. */
    private class Rotation(val pair: KeyPair) {
        val keyBase64: String = Base64.getEncoder().encodeToString(checkNotNull(ed25519RawPublicKey(pair.public.encoded)))

        fun sign(bytes: ByteArray): ByteArray = Signature.getInstance("Ed25519").run {
            initSign(pair.private)
            update(bytes)
            sign()
        }
    }

    private fun rotatedStore(rotation: Rotation) =
        FamilyCatalogStore(directory, { bundledBytes() }, FamilyCatalogStores.activeGate(rotation.keyBase64)) { logged += it }

    /**
     * F-P3-8 (2026-10-13 review): the same-bytes check compared against an `active.json` that the load gate
     * refuses, so the same catalog re-signed under the new key was reported `Unchanged` and never staged: the
     * device stayed on the bundled file for good.
     */
    @Test
    fun `a catalog re-signed under a rotated key replaces an active file the new key cannot verify`() {
        val rev2 = catalog(2)
        val before = store()
        assertTrue(FamilyCatalogStoreSink(before).promoteVerified(rev2, sign(rev2)))
        assertTrue(FamilyCatalogPromotion.promoteIfIdle(before, isIdle = { true }, invalidate = {}))
        assertEquals(Source.ACTIVE, before.load().source)

        val rotation = Rotation(KeyPairGenerator.getInstance("Ed25519").generateKeyPair())
        val after = rotatedStore(rotation)
        assertEquals("the old signature no longer verifies, so the bundled catalog wins", Source.BUNDLED, after.load().source)

        val resigned = rotation.sign(rev2)
        assertEquals(FamilyCatalogStore.StageResult.Staged(2), after.stageVerified(rev2, resigned))
        assertEquals(FamilyCatalogStore.PromoteResult.Promoted(2), after.promoteStagedIfIdle { true })
        assertEquals(Source.ACTIVE, after.load().source)
        assertEquals(FamilyCatalogStore.sha256Hex(rev2), after.load().sha256Hex)

        // Now the file is trusted, so the same bytes are a no-op again.
        assertEquals(FamilyCatalogStore.StageResult.Unchanged(2), after.stageVerified(rev2, resigned))
    }

    @Test
    fun `a catalog re-signed under a rotated key replaces a staged file the new key cannot verify`() {
        val rev2 = catalog(2)
        assertTrue(FamilyCatalogStoreSink(store()).promoteVerified(rev2, sign(rev2))) // staged under the old key

        val rotation = Rotation(KeyPairGenerator.getInstance("Ed25519").generateKeyPair())
        val after = rotatedStore(rotation)
        val resigned = rotation.sign(rev2)
        assertEquals(FamilyCatalogStore.StageResult.Staged(2), after.stageVerified(rev2, resigned))
        assertEquals("the new signature replaced the old one", resigned.toList(), file("staged.json.sig").readBytes().toList())
        assertEquals(FamilyCatalogStore.StageResult.Unchanged(2), after.stageVerified(rev2, resigned))
    }

    @Test
    fun `with no key compiled in no active catalog is ever trusted`() {
        // Staged and promoted under a key, then the key is removed from the build.
        val rev2 = catalog(2)
        val keyed = store()
        assertTrue(FamilyCatalogStoreSink(keyed).promoteVerified(rev2, sign(rev2)))
        assertTrue(FamilyCatalogPromotion.promoteIfIdle(keyed, isIdle = { true }, invalidate = {}))
        assertEquals(Source.ACTIVE, keyed.load().source)

        val keyless = FamilyCatalogStore(directory, { bundledBytes() }, FamilyCatalogStores.activeGate("")) { logged += it }
        val loaded = keyless.load()
        assertEquals(Source.BUNDLED, loaded.source)
        assertEquals(FamilyCatalogStore.sha256Hex(bundledBytes()), loaded.sha256Hex)
        assertNull(FamilyCatalogSignature.decodePublicKey(""))
        assertTrue(logged.any { "refused by the load gate" in it })
    }

    // ---- wiring -------------------------------------------------------------------------------

    @Test
    fun `one store per directory is shared by the fetcher, the application and the service`() {
        val first = store()
        val second = store()
        assertSame(first, second)
        assertFalse(first === FamilyCatalogStores.forDirectory(File(directory, "elsewhere"), { bundledBytes() }, keyBase64))
    }

    @Test
    fun `the context entry point builds the device-protected store and the sink`() {
        val app = RuntimeEnvironment.getApplication()
        val store = FamilyCatalogStores.forContext(app)
        assertSame(store, FamilyCatalogStores.forContext(app))
        // The unit-test application carries no packaged assets, so the bundled catalog is unreadable here and
        // the store says so (NONE), which is the "compiled default applies" case; it never throws.
        assertTrue(store.load().source in setOf(Source.BUNDLED, Source.NONE))
        assertNotNull(familyCatalogSinkFor(app))
        assertFalse(FamilyCatalogPromotion.promoteIfIdle(app, isIdle = { true }, invalidate = { error("nothing is staged") }))
    }

    @Test
    fun `the main and the guardian process install the live catalog and only the main one fetches`() {
        val pkg = "com.gruning.mimir.tweaker"
        assertTrue(FamilyCatalogProcess.usesLiveCatalogIn(pkg, pkg))
        assertTrue("the guardian mints the same device profile as main", FamilyCatalogProcess.usesLiveCatalogIn("$pkg:guardian", pkg))
        assertFalse(FamilyCatalogProcess.fetchesIn("$pkg:guardian", pkg))
        // Exact names only: a bare suffix, another app's process, an unknown process and any other suffix read nothing.
        assertFalse(FamilyCatalogProcess.usesLiveCatalogIn(":guardian", pkg))
        assertFalse(FamilyCatalogProcess.usesLiveCatalogIn("$pkg:other", pkg))
        assertFalse(FamilyCatalogProcess.usesLiveCatalogIn("com.other.app:guardian", pkg))
        assertFalse(FamilyCatalogProcess.usesLiveCatalogIn(null, pkg))
        assertFalse(FamilyCatalogProcess.usesLiveCatalogIn("", pkg))
    }

    /** The bundled catalog with [alias] added to the Snapdragon 8 Gen 2 family, as a newer revision. */
    private fun withLiveAlias(revision: Int, alias: String): ByteArray = mutated { root ->
        root.put("revision", revision)
        FamilyCatalogTestSupport.entryAt(root, "qualcomm-8gen2").getJSONObject("match").getJSONArray("soc").put(alias)
    }

    /**
     * F-P2-1 (2026-10-13 review): the guardian used to keep the compiled default, so a live-added alias on a
     * reserved family minted the manual-fan Thor profile in main but a candidate profile (no fan axis) in the
     * guardian, and a manual fan could not be restored there.
     */
    @Test
    fun `the guardian arm reads the catalog main promoted, so both processes mint the same device profile`() {
        val store = store()
        FamilyCatalogRegistry.installFrom(store) // the guardian process start (TweakerApplication)
        val thor = { HandheldDeviceProfiles.profileId("AYN", "Thor", "SM8550P") }
        assertEquals("not a known SoC yet: no fan-bearing identity", "qualcomm-adreno-candidate-pserver", thor())

        // The main process downloads, then promotes at an idle apply (a different process: it does not touch ours).
        val rev2 = withLiveAlias(2, "SM8550P")
        assertTrue(FamilyCatalogStoreSink(store).promoteVerified(rev2, sign(rev2)))
        assertTrue(store.promoteStagedIfIdle { true } is FamilyCatalogStore.PromoteResult.Promoted)
        assertEquals("the guardian has not re-read yet", "qualcomm-adreno-candidate-pserver", thor())

        var invalidations = 0
        assertTrue(GuardianFamilyCatalog.installAtArm(store, invalidate = { invalidations++ }, log = { logged += it }))

        assertEquals("ayn-qcs8550-pserver", thor())
        assertTrue(HandheldDeviceProfiles.supportsFanMode(thor()))
        assertEquals("the cached topology is dropped once, because the revision moved", 1, invalidations)

        // The next arm with nothing new installs the same revision and keeps the cached topology.
        assertTrue(GuardianFamilyCatalog.installAtArm(store, invalidate = { invalidations++ }, log = { logged += it }))
        assertEquals(1, invalidations)
        assertEquals("ayn-qcs8550-pserver", thor())
    }

    @Test
    fun `a guardian arm never fails because of the catalog`() {
        val store = store()
        // An invalidation that throws is contained, and the catalog still installs.
        val rev2 = withLiveAlias(2, "SM8550P")
        assertTrue(FamilyCatalogStoreSink(store).promoteVerified(rev2, sign(rev2)))
        assertTrue(store.promoteStagedIfIdle { true } is FamilyCatalogStore.PromoteResult.Promoted)
        assertTrue(GuardianFamilyCatalog.installAtArm(store, invalidate = { error("probe gone") }, log = {}))
        assertEquals("ayn-qcs8550-pserver", HandheldDeviceProfiles.profileId("AYN", "Thor", "SM8550P"))

        // A store with nothing readable installs nothing, so the compiled default applies, exactly as in main.
        val unreadable = FamilyCatalogStore(File(directory, "none"), { null })
        assertTrue(GuardianFamilyCatalog.installAtArm(unreadable, invalidate = {}, log = {}))
        assertNull(FamilyCatalogRegistry.current())
    }

    @Test
    fun `the catalog extra entry used elsewhere does not collide with the promoted test family`() {
        // Guards the fixtures themselves: extraEntry() and the MT9999 family claim disjoint aliases.
        val aliases = extraEntry().getJSONObject("match").getJSONArray("soc")
        assertTrue((0 until aliases.length()).none { aliases.getString(it) == "MT9999" })
    }
}
