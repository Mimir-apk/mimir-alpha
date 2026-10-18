package com.gruning.mimir.rewrite.core.launch

import com.gruning.mimir.rewrite.core.model.BiosPresence
import com.gruning.mimir.rewrite.core.model.BiosProfile
import com.gruning.mimir.rewrite.core.model.EmulatorCapability
import com.gruning.mimir.rewrite.core.model.EmulatorId
import com.gruning.mimir.rewrite.core.model.EmulatorProfile
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.LaunchLocatorForm
import com.gruning.mimir.rewrite.core.model.LaunchOutcome
import com.gruning.mimir.rewrite.core.model.LaunchReadiness
import com.gruning.mimir.rewrite.core.model.PlatformId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * EP19A adversarial proofs for the immutable exact-package [BiosSeedSnapshot] contract.
 */
class BiosSeedSnapshotTest {

    private val ps2 = PlatformId.PS2
    private val gameId = GameId("group:ps2:bios-snapshot")
    private val armsx2Pkg = "come.nanodata.armsx2"
    private val netherPkg = "xyz.aethersx2.android"
    private val playPkg = "com.virtualapplications.play"
    private val armsx2Id = EmulatorId.fromNativeLauncherKey(armsx2Pkg)
    private val netherId = EmulatorId.fromNativeLauncherKey(netherPkg)
    private val playId = EmulatorId.fromNativeLauncherKey(playPkg)
    private val n64Pkg = "org.mupen64plusae.v3.fzurita"
    private val n64Id = EmulatorId.fromNativeLauncherKey(n64Pkg)
    private val ps1 = PlatformId.PS1
    private val duckPkg = "com.github.stenzek.duckstation"
    private val duckId = EmulatorId.fromNativeLauncherKey(duckPkg)
    private val sharedPs2Id = EmulatorId("shared-ps2")
    private val sharedPs2VariantAId = EmulatorId("shared-ps2.variant-a")
    private val sharedPs2VariantBId = EmulatorId("shared-ps2.variant-b")

    // ── Seeder proofs (1–3, 7, persistence, no-catalog) ───────────────────────

    @Test
    fun armsx2SnapshotRetainsExactPackageAndEmulatorIdentity() = runBlocking {
        val seeder = ps2Seeder(FakeSeederPersistence())
        val snapshot = seeder.ensureSeeded(ps2, armsx2Id, armsx2Pkg)

        assertEquals(ps2, snapshot.platformId)
        assertEquals(armsx2Pkg, snapshot.packageName)
        assertEquals(armsx2Id, snapshot.emulatorId)
        assertTrue(snapshot.requiresBios)
        assertTrue(snapshot.profiles.isNotEmpty())
        snapshot.profiles.forEach { profile ->
            assertEquals(armsx2Id, profile.emulatorId)
            assertEquals(armsx2Pkg, profile.packageName)
        }
    }

    @Test
    fun cacheHitReturnsSameImmutableSnapshotForSameRevisionAndPackage() = runBlocking {
        val persistence = FakeSeederPersistence()
        val seeder = ps2Seeder(persistence)

        val first = seeder.ensureSeeded(ps2, armsx2Id, armsx2Pkg)
        val second = seeder.ensureSeeded(ps2, armsx2Id, armsx2Pkg)

        assertSame("cache hit must return the memoized snapshot instance", first, second)
        assertEquals(1, persistence.writes.size)
        val originalSize = first.profiles.size
        try {
            @Suppress("UNCHECKED_CAST")
            (first.profiles as MutableList<BiosProfile>).add(
                BiosProfile(
                    platformId = ps2,
                    fileName = "injected.bin",
                    required = true,
                    presence = BiosPresence.MISSING,
                    emulatorId = armsx2Id,
                    packageName = armsx2Pkg,
                ),
            )
            fail("cast-and-mutate must throw on the unmodifiable profiles list")
        } catch (_: UnsupportedOperationException) {
            // expected
        }
        assertEquals("unmodifiable list must reject mutation", originalSize, first.profiles.size)
        assertEquals("defensive copy must isolate memoized facts", originalSize, second.profiles.size)
    }

    @Test
    fun sequentialArmsx2AndNetherSeedsDoNotCrossBlockReturnedSnapshots() = runBlocking {
        val seeder = ps2Seeder(FakeSeederPersistence())

        val armsx2Snapshot = seeder.ensureSeeded(ps2, armsx2Id, armsx2Pkg)
        val netherSnapshot = seeder.ensureSeeded(ps2, netherId, netherPkg)

        assertNotEquals(armsx2Snapshot.packageName, netherSnapshot.packageName)
        assertNotEquals(armsx2Snapshot.emulatorId, netherSnapshot.emulatorId)
        assertEquals(armsx2Pkg, armsx2Snapshot.packageName)
        assertEquals(netherPkg, netherSnapshot.packageName)
        armsx2Snapshot.profiles.forEach { assertEquals(armsx2Id, it.emulatorId) }
        netherSnapshot.profiles.forEach { assertEquals(netherId, it.emulatorId) }
    }

    @Test
    fun platformWithoutBiosCatalogReturnsExactEmptySnapshotFromEmulatorSeed() = runBlocking {
        val persistence = FakeSeederPersistence(platformsWithGames = listOf(PlatformId.N64))
        val seeder = BiosProfileSeeder(
            rootLister = { BiosFolderListing(enumerable = false) },
            persistence = persistence,
            emulators = InstalledEmulatorProvider {
                EmulatorSeedRegistry.candidatesFor(it) { pkg -> pkg == n64Pkg }
            },
            platformDefault = { EmulatorId.fromNativeLauncherKey(n64Pkg) },
        )

        val snapshot = seeder.ensureSeeded(PlatformId.N64, n64Id, n64Pkg)

        assertEquals(PlatformId.N64, snapshot.platformId)
        assertEquals(n64Pkg, snapshot.packageName)
        assertTrue(snapshot.profiles.isEmpty())
        assertFalse("N64 seed does not require BIOS", snapshot.requiresBios)
        assertEquals(1, persistence.writes.size)
        assertTrue(persistence.writes.single().profiles.isEmpty())
    }

    @Test
    fun persistenceFailurePublishesNoSnapshot() = runBlocking {
        val persistence = FailingSeederPersistence()
        val seeder = ps2Seeder(persistence)

        try {
            seeder.ensureSeeded(ps2, armsx2Id, armsx2Pkg)
            fail("expected persistence failure")
        } catch (_: RuntimeException) {
            // expected
        }
        assertEquals(1, persistence.attempts)

        try {
            seeder.ensureSeeded(ps2, armsx2Id, armsx2Pkg)
            fail("expected persistence failure on retry")
        } catch (_: RuntimeException) {
            // expected
        }
        assertEquals("failed seed must not memoize", 2, persistence.attempts)
    }

    @Test
    fun backgroundReseedCannotMutateInFlightSnapshot() = runBlocking {
        val persistence = FakeSeederPersistence(
            roots = listOf(BiosLibraryRoot("/library/bios", "local")),
        )
        var listing = BiosFolderListing(enumerable = true, files = emptyList())
        val seeder = BiosProfileSeeder(
            rootLister = { listing },
            persistence = persistence,
            emulators = InstalledEmulatorProvider {
                EmulatorSeedRegistry.candidatesFor(it) { pkg -> pkg == armsx2Pkg }
            },
            platformDefault = { armsx2Id },
        )

        val inFlight = seeder.ensureSeeded(ps2, armsx2Id, armsx2Pkg)
        val originalPresence = inFlight.profiles.first { it.required }.presence

        listing = BiosFolderListing(
            enumerable = true,
            files = listOf(BiosProbeFile("scph30000.bin", 4_194_304L, "/library/bios")),
        )
        seeder.libraryRevisionChanged()

        assertEquals(
            "returned snapshot must stay immutable across background reseed",
            originalPresence,
            inFlight.profiles.first { it.required }.presence,
        )
        val refreshed = seeder.ensureSeeded(ps2, armsx2Id, armsx2Pkg)
        assertEquals(BiosPresence.PRESENT, refreshed.profiles.first { it.required }.presence)
    }

    @Test
    fun barrierControlledBackgroundReseedCannotAlterReturnedSnapshot() = runBlocking {
        val persistence = BarrierSeederPersistence()
        val seeder = ps2Seeder(persistence)

        val first = seeder.ensureSeeded(ps2, armsx2Id, armsx2Pkg)

        val reseedJob = async(Dispatchers.Default) {
            seeder.libraryRevisionChanged()
        }
        assertTrue(persistence.awaitSecondPersistStarted.await(5, TimeUnit.SECONDS))
        assertTrue(
            "background reseed must not rewrite an already-returned snapshot",
            first.profiles.all { it.emulatorId == armsx2Id },
        )
        persistence.releaseSecondPersist.countDown()
        reseedJob.await()
        assertTrue(first.profiles.all { it.emulatorId == armsx2Id && it.packageName == armsx2Pkg })
    }

    @Test
    fun libraryRevisionEvictsStaleCacheSoNextSeedIsRecomputed() = runBlocking {
        val persistence = FakeSeederPersistence(
            roots = listOf(BiosLibraryRoot("/library/bios", "local")),
        )
        var listing = BiosFolderListing(enumerable = true, files = emptyList())
        val seeder = BiosProfileSeeder(
            rootLister = { listing },
            persistence = persistence,
            emulators = InstalledEmulatorProvider {
                EmulatorSeedRegistry.candidatesFor(it) { pkg -> pkg == armsx2Pkg }
            },
            platformDefault = { armsx2Id },
        )

        val first = seeder.ensureSeeded(ps2, armsx2Id, armsx2Pkg)
        assertEquals(1, persistence.writes.size)

        listing = BiosFolderListing(
            enumerable = true,
            files = listOf(BiosProbeFile("scph30000.bin", 4_194_304L, "/library/bios")),
        )
        seeder.libraryRevisionChanged()

        val second = seeder.ensureSeeded(ps2, armsx2Id, armsx2Pkg)
        assertNotSame("revision bump must evict stale cache and recompute", first, second)
        assertEquals(BiosPresence.PRESENT, second.profiles.first { it.required }.presence)
        assertEquals(2, persistence.writes.size)
    }

    @Test
    fun revisionRaceLaunchResumesWithFreshListingNeverCachesStaleUnderNewRevision() = runBlocking {
        val persistence = FakeSeederPersistence(
            roots = listOf(BiosLibraryRoot("/library/bios", "local")),
        )
        var listing = BiosFolderListing(enumerable = true, files = emptyList())
        val staleLaunchAtGate = CountDownLatch(1)
        val releaseStaleLaunch = CountDownLatch(1)
        val gateInvocations = AtomicInteger(0)
        val seeder = BiosProfileSeeder(
            rootLister = { listing },
            persistence = persistence,
            emulators = InstalledEmulatorProvider {
                EmulatorSeedRegistry.candidatesFor(it) { pkg -> pkg == armsx2Pkg }
            },
            platformDefault = { armsx2Id },
        )
        seeder.beforePersistGate = { _ ->
            if (gateInvocations.getAndIncrement() == 0) {
                staleLaunchAtGate.countDown()
                releaseStaleLaunch.await()
            }
        }

        val launchJob = async(Dispatchers.Default) {
            seeder.ensureSeeded(ps2, armsx2Id, armsx2Pkg)
        }

        staleLaunchAtGate.await()
        listing = BiosFolderListing(
            enumerable = true,
            files = listOf(BiosProbeFile("scph30000.bin", 4_194_304L, "/library/bios")),
        )
        seeder.libraryRevisionChanged()
        releaseStaleLaunch.countDown()

        val snapshot = launchJob.await()
        assertEquals(
            "returned snapshot must reflect the fresh rev1 listing, not the stale rev0 capture",
            BiosPresence.PRESENT,
            snapshot.profiles.first { it.required }.presence,
        )
        assertEquals(
            "abandoned rev0 work must never persist stale facts",
            1,
            persistence.writes.size,
        )
        assertEquals(
            "background reseed owns the sole publication under rev1",
            BiosPresence.PRESENT,
            persistence.writes.single().profiles.first { it.required }.presence,
        )
        val cached = seeder.ensureSeeded(ps2, armsx2Id, armsx2Pkg)
        assertSame("fresh rev1 snapshot must be memoized for cache-hit fast path", snapshot, cached)
        assertEquals(BiosPresence.PRESENT, cached.profiles.first { it.required }.presence)
    }

    @Test
    fun ps1AssignmentChangeDoesNotCancelBlockedPs2LibraryReseedPublication() = runBlocking {
        val persistence = FakeSeederPersistence(
            platformsWithGames = listOf(ps1, ps2),
            roots = listOf(BiosLibraryRoot("/library/bios", "local")),
        )
        val listing = BiosFolderListing(
            enumerable = true,
            files = listOf(
                BiosProbeFile("scph5501.bin", 524_288L, "/library/bios"),
                BiosProbeFile("scph30000.bin", 4_194_304L, "/library/bios"),
            ),
        )
        val ps2LibraryReseedAtGate = CountDownLatch(1)
        val releasePs2LibraryReseed = CountDownLatch(1)
        val seeder = BiosProfileSeeder(
            rootLister = { listing },
            persistence = persistence,
            emulators = InstalledEmulatorProvider { platform ->
                when (platform) {
                    ps1 -> EmulatorSeedRegistry.candidatesFor(platform) { pkg -> pkg == duckPkg }
                    ps2 -> EmulatorSeedRegistry.candidatesFor(platform) { pkg -> pkg == armsx2Pkg }
                    else -> emptyList()
                }
            },
            platformDefault = { platform ->
                when (platform) {
                    ps1 -> duckId
                    ps2 -> armsx2Id
                    else -> null
                }
            },
        )
        seeder.beforePersistGate = { platformId ->
            if (platformId == ps2) {
                ps2LibraryReseedAtGate.countDown()
                releasePs2LibraryReseed.await()
            }
        }

        val libraryReseedJob = async(Dispatchers.Default) {
            seeder.libraryRevisionChanged()
        }
        try {
            assertTrue(
                "PS2 library reseed must reach publication gate before cross-platform assignment",
                ps2LibraryReseedAtGate.await(5, TimeUnit.SECONDS),
            )
            seeder.emulatorAssignmentChanged(ps1, duckId)
            val ps1Snapshot = seeder.ensureSeeded(ps1, duckId, duckPkg)
            assertEquals(ps1, ps1Snapshot.platformId)
            assertEquals(duckPkg, ps1Snapshot.packageName)
            assertEquals(duckId, ps1Snapshot.emulatorId)
            assertEquals(BiosPresence.PRESENT, ps1Snapshot.profiles.single().presence)

            releasePs2LibraryReseed.countDown()
            libraryReseedJob.await()

            assertEquals(
                "PS2 library reseed must publish before any lazy ensureSeeded repair",
                3,
                persistence.writes.size,
            )
            assertEquals(
                setOf(ps1, ps2),
                persistence.writes.map { it.platformId }.toSet(),
            )
            val ps2Write = persistence.writes.single { it.platformId == ps2 }
            assertEquals(BiosPresence.PRESENT, ps2Write.profiles.first { it.required }.presence)

            val writesBeforePs2Ensure = persistence.writes.size
            val ps2Snapshot = seeder.ensureSeeded(ps2, armsx2Id, armsx2Pkg)
            assertEquals(
                "PS2 must be a cache hit after library reseed publication",
                writesBeforePs2Ensure,
                persistence.writes.size,
            )
            assertEquals(ps2, ps2Snapshot.platformId)
            assertEquals(armsx2Pkg, ps2Snapshot.packageName)
            assertEquals(armsx2Id, ps2Snapshot.emulatorId)
            assertEquals(BiosPresence.PRESENT, ps2Snapshot.profiles.first { it.required }.presence)
        } finally {
            releasePs2LibraryReseed.countDown()
        }
    }

    @Test
    fun retroArchPersistsUnderResolvedProfileIdNotPackage() = runBlocking {
        val retroPkg = "com.retroarch.aarch64"
        val gba = PlatformId.GBA
        val retroProfile = EmulatorSeedRegistry.candidatesFor(gba) { it == retroPkg }
            .first { it.retroArchCore == "mgba" }
        val persistence = FakeSeederPersistence(platformsWithGames = listOf(gba))
        val seeder = BiosProfileSeeder(
            rootLister = { BiosFolderListing(enumerable = false) },
            persistence = persistence,
            emulators = InstalledEmulatorProvider {
                EmulatorSeedRegistry.candidatesFor(it) { pkg -> pkg == retroPkg }
            },
            platformDefault = { retroProfile.id },
        )

        val snapshot = seeder.ensureSeeded(gba, retroProfile.id, retroProfile.packageName)

        assertEquals(retroProfile.id, snapshot.emulatorId)
        assertEquals(retroPkg, snapshot.packageName)
        assertNotEquals(retroPkg, snapshot.emulatorId?.value)
        assertEquals(retroProfile.id, persistence.writes.single().emulatorId)
    }

    @Test
    fun samePackageDifferentEmulatorIdMemoSeparates() = runBlocking {
        val persistence = FakeSeederPersistence()
        val seeder = ps2Seeder(persistence)

        val first = seeder.ensureSeeded(ps2, sharedPs2VariantAId, armsx2Pkg)
        val second = seeder.ensureSeeded(ps2, sharedPs2VariantBId, armsx2Pkg)

        assertEquals(ps2, first.platformId)
        assertEquals(ps2, second.platformId)
        assertEquals(armsx2Pkg, first.packageName)
        assertEquals(armsx2Pkg, second.packageName)
        assertEquals(sharedPs2VariantAId, first.emulatorId)
        assertEquals(sharedPs2VariantBId, second.emulatorId)
        assertNotEquals(first, second)
        assertEquals(2, persistence.writes.size)
        assertEquals(armsx2Pkg, persistence.writes[0].packageName)
        assertEquals(armsx2Pkg, persistence.writes[1].packageName)
        assertNotEquals(persistence.writes[0].emulatorId, persistence.writes[1].emulatorId)
    }

    @Test
    fun sameEmulatorIdDifferentPackageMemoSeparates() = runBlocking {
        val persistence = FakeSeederPersistence()
        val seeder = ps2Seeder(persistence)

        val first = seeder.ensureSeeded(ps2, sharedPs2Id, armsx2Pkg)
        val second = seeder.ensureSeeded(ps2, sharedPs2Id, netherPkg)

        assertEquals(ps2, first.platformId)
        assertEquals(ps2, second.platformId)
        assertEquals(sharedPs2Id, first.emulatorId)
        assertEquals(sharedPs2Id, second.emulatorId)
        assertEquals(armsx2Pkg, first.packageName)
        assertEquals(netherPkg, second.packageName)
        assertNotEquals(first, second)
        assertEquals(2, persistence.writes.size)
        assertEquals(sharedPs2Id, persistence.writes[0].emulatorId)
        assertEquals(sharedPs2Id, persistence.writes[1].emulatorId)
        assertNotEquals(persistence.writes[0].packageName, persistence.writes[1].packageName)
    }

    @Test
    fun libraryRevisionChangedPersistsRetroArchExactIdAndPackage() = runBlocking {
        val retroPkg = "com.retroarch.aarch64"
        val gba = PlatformId.GBA
        val retroProfile = EmulatorSeedRegistry.candidatesFor(gba) { it == retroPkg }
            .first { it.retroArchCore == "mgba" }
        val persistence = FakeSeederPersistence(platformsWithGames = listOf(gba))
        val seeder = BiosProfileSeeder(
            rootLister = { BiosFolderListing(enumerable = false) },
            persistence = persistence,
            emulators = InstalledEmulatorProvider {
                EmulatorSeedRegistry.candidatesFor(it) { pkg -> pkg == retroPkg }
            },
            platformDefault = { retroProfile.id },
        )

        seeder.libraryRevisionChanged()

        val write = persistence.writes.single { it.platformId == gba }
        assertEquals(retroProfile.id, write.emulatorId)
        assertEquals(retroProfile.packageName, write.packageName)
        assertNotEquals(retroProfile.packageName, write.emulatorId.value)
    }

    @Test
    fun emulatorAssignmentChangedPersistsRetroArchExactIdAndPackage() = runBlocking {
        val retroPkg = "com.retroarch.aarch64"
        val gba = PlatformId.GBA
        val retroProfile = EmulatorSeedRegistry.candidatesFor(gba) { it == retroPkg }
            .first { it.retroArchCore == "mgba" }
        val persistence = FakeSeederPersistence(platformsWithGames = listOf(gba))
        val seeder = BiosProfileSeeder(
            rootLister = { BiosFolderListing(enumerable = false) },
            persistence = persistence,
            emulators = InstalledEmulatorProvider {
                EmulatorSeedRegistry.candidatesFor(it) { pkg -> pkg == retroPkg }
            },
            platformDefault = { retroProfile.id },
        )

        seeder.emulatorAssignmentChanged(gba, retroProfile.id)

        val write = persistence.writes.single { it.platformId == gba }
        assertEquals(retroProfile.id, write.emulatorId)
        assertEquals(retroProfile.packageName, write.packageName)
        assertNotEquals(retroProfile.packageName, write.emulatorId.value)
    }

    @Test
    fun sharedEmulatorIdFallbackIsReadyForPackageBDespitePackageAMissingRow() = runBlocking {
        assertTrue(EmulatorSeedRegistry.requiresBios(armsx2Pkg))
        assertTrue(EmulatorSeedRegistry.requiresBios(netherPkg))
        var biosResolverCalls = 0
        val controller = launchController(
            emulator = ps2Emulator(sharedPs2Id, netherPkg),
            biosSeedBeforeRead = { _, _, _ -> null },
            biosResolver = { _, id, pkg ->
                biosResolverCalls++
                assertEquals(sharedPs2Id, id)
                if (pkg == armsx2Pkg) {
                    listOf(exactScopedMissingProfile(sharedPs2Id, armsx2Pkg))
                } else {
                    emptyList()
                }
            },
        )

        val readiness = controller.resolveReadiness(gameId, ps2)

        assertTrue(readiness is LaunchReadiness.Ready)
        assertEquals(1, biosResolverCalls)
    }

    @Test
    fun sharedEmulatorIdFallbackNeedsBiosForPackageAWithExactMissingRow() = runBlocking {
        assertTrue(EmulatorSeedRegistry.requiresBios(armsx2Pkg))
        var biosResolverCalls = 0
        val controller = launchController(
            emulator = ps2Emulator(sharedPs2Id, armsx2Pkg),
            biosSeedBeforeRead = { _, _, _ -> null },
            biosResolver = { _, id, pkg ->
                biosResolverCalls++
                assertEquals(sharedPs2Id, id)
                assertEquals(armsx2Pkg, pkg)
                listOf(exactScopedMissingProfile(sharedPs2Id, armsx2Pkg))
            },
        )

        val readiness = controller.resolveReadiness(gameId, ps2)

        assertTrue(readiness is LaunchReadiness.NeedsBios)
        assertEquals(1, biosResolverCalls)
    }

    @Test
    fun registryFalseIgnoresExactScopeMissingRowsOnFallback() = runBlocking {
        var biosResolverCalls = 0
        val controller = launchController(
            platformId = PlatformId.N64,
            emulator = EmulatorProfile(
                id = n64Id,
                packageName = n64Pkg,
                displayName = "M64Plus FZ",
                installed = true,
                capability = EmulatorCapability.DIRECT_PAYLOAD,
                locatorForm = LaunchLocatorForm.EITHER,
                supportedPlatforms = setOf(PlatformId.N64),
            ),
            biosSeedBeforeRead = { _, _, _ -> null },
            biosResolver = { _, _, _ ->
                biosResolverCalls++
                listOf(
                    BiosProfile(
                        platformId = PlatformId.N64,
                        fileName = "stale.bin",
                        required = true,
                        presence = BiosPresence.MISSING,
                        emulatorId = n64Id,
                        packageName = n64Pkg,
                    ),
                )
            },
        )

        val readiness = controller.resolveReadiness(GameId("group:n64:registry"), PlatformId.N64)

        assertTrue(readiness is LaunchReadiness.Ready)
        assertEquals(0, biosResolverCalls)
    }

    // ── LaunchController proofs (4–6, 8) ──────────────────────────────────────

    @Test
    fun exactSelectedEmulatorMissingBlocksThatEmulator() = runBlocking {
        val snapshot = missingBiosSnapshot(armsx2Id, armsx2Pkg)
        var biosResolverCalls = 0
        val controller = launchController(
            emulator = ps2Emulator(armsx2Id, armsx2Pkg),
            biosSeedBeforeRead = { _, _, _ -> snapshot },
            biosResolver = { _, _, _ ->
                biosResolverCalls++
                listOf(exactScopedMissingProfile(armsx2Id, armsx2Pkg))
            },
        )

        val readiness = controller.resolveReadiness(gameId, ps2)

        assertTrue(readiness is LaunchReadiness.NeedsBios)
        assertEquals(0, biosResolverCalls)
    }

    @Test
    fun stalePlatformWideRoomMissingCannotBlockForkWhenExactSnapshotPresent() = runBlocking {
        val snapshot = presentBiosSnapshot(netherId, netherPkg)
        var biosResolverCalls = 0
        val controller = launchController(
            emulator = ps2Emulator(netherId, netherPkg),
            biosSeedBeforeRead = { _, _, _ -> snapshot },
            biosResolver = { _, _, _ ->
                biosResolverCalls++
                listOf(exactScopedMissingProfile(netherId, netherPkg))
            },
        )

        val readiness = controller.resolveReadiness(gameId, ps2)

        assertTrue(readiness is LaunchReadiness.Ready)
        assertEquals(0, biosResolverCalls)
    }

    @Test
    fun exactNoBiosSnapshotOverridesStalePlatformRequiresBiosTrue() = runBlocking {
        val n64 = PlatformId.N64
        val n64EmulatorId = EmulatorId.fromNativeLauncherKey(n64Pkg)
        val snapshot = BiosSeedSnapshot(
            platformId = n64,
            emulatorId = n64EmulatorId,
            packageName = n64Pkg,
            requiresBios = false,
            profiles = emptyList(),
        )
        var biosResolverCalls = 0
        val controller = launchController(
            platformId = n64,
            emulator = EmulatorProfile(
                id = n64EmulatorId,
                packageName = n64Pkg,
                displayName = "M64Plus FZ",
                installed = true,
                capability = EmulatorCapability.DIRECT_PAYLOAD,
                locatorForm = LaunchLocatorForm.EITHER,
                supportedPlatforms = setOf(n64),
            ),
            biosSeedBeforeRead = { _, _, _ -> snapshot },
            biosResolver = { _, _, _ ->
                biosResolverCalls++
                listOf(
                    BiosProfile(
                        platformId = n64,
                        fileName = "stale.bin",
                        required = true,
                        presence = BiosPresence.MISSING,
                        emulatorId = n64EmulatorId,
                        packageName = n64Pkg,
                    ),
                )
            },
        )

        val readiness = controller.resolveReadiness(GameId("group:n64:bios-snapshot"), n64)

        assertTrue(readiness is LaunchReadiness.Ready)
        assertEquals(0, biosResolverCalls)
    }

    @Test
    fun wrongPackageSnapshotUsesExactRegistryFallbackNotPlatformRow() = runBlocking {
        var biosResolverCalls = 0
        val controller = launchController(
            emulator = ps2Emulator(netherId, netherPkg),
            biosSeedBeforeRead = { _, _, _ ->
                BiosSeedSnapshot(
                    platformId = ps2,
                    emulatorId = armsx2Id,
                    packageName = armsx2Pkg,
                    requiresBios = false,
                    profiles = emptyList(),
                )
            },
            biosResolver = { _, id, pkg ->
                biosResolverCalls++
                assertEquals(netherId, id)
                assertEquals(netherPkg, pkg)
                listOf(exactScopedMissingProfile(netherId, netherPkg))
            },
        )

        val readiness = controller.resolveReadiness(gameId, ps2)

        assertTrue(readiness is LaunchReadiness.NeedsBios)
        assertEquals(1, biosResolverCalls)
    }

    @Test
    fun wrongEmulatorIdSnapshotUsesExactRegistryFallback() = runBlocking {
        var biosResolverCalls = 0
        val controller = launchController(
            emulator = ps2Emulator(netherId, netherPkg),
            biosSeedBeforeRead = { _, _, _ ->
                BiosSeedSnapshot(
                    platformId = ps2,
                    emulatorId = armsx2Id,
                    packageName = netherPkg,
                    requiresBios = false,
                    profiles = emptyList(),
                )
            },
            biosResolver = { _, id, pkg ->
                biosResolverCalls++
                assertEquals(netherId, id)
                assertEquals(netherPkg, pkg)
                listOf(exactScopedMissingProfile(netherId, netherPkg))
            },
        )

        val readiness = controller.resolveReadiness(gameId, ps2)

        assertTrue(readiness is LaunchReadiness.NeedsBios)
        assertEquals(1, biosResolverCalls)
    }

    @Test
    fun wrongProfileIdentitySnapshotUsesExactRegistryFallback() = runBlocking {
        var biosResolverCalls = 0
        val controller = launchController(
            emulator = ps2Emulator(netherId, netherPkg),
            biosSeedBeforeRead = { _, _, _ ->
                BiosSeedSnapshot(
                    platformId = ps2,
                    emulatorId = netherId,
                    packageName = netherPkg,
                    requiresBios = true,
                    profiles = listOf(
                        BiosProfile(
                            platformId = ps2,
                            fileName = "scph*.bin",
                            required = true,
                            presence = BiosPresence.PRESENT,
                            emulatorId = armsx2Id,
                            packageName = armsx2Pkg,
                        ),
                    ),
                )
            },
            biosResolver = { _, id, pkg ->
                biosResolverCalls++
                assertEquals(netherId, id)
                assertEquals(netherPkg, pkg)
                listOf(exactScopedMissingProfile(netherId, netherPkg))
            },
        )

        val readiness = controller.resolveReadiness(gameId, ps2)

        assertTrue(readiness is LaunchReadiness.NeedsBios)
        assertEquals(1, biosResolverCalls)
    }

    @Test
    fun nullEmulatorIdSnapshotUsesExactRegistryFallback() = runBlocking {
        var biosResolverCalls = 0
        val controller = launchController(
            emulator = ps2Emulator(netherId, netherPkg),
            biosSeedBeforeRead = { _, _, _ ->
                BiosSeedSnapshot(
                    platformId = ps2,
                    emulatorId = null,
                    packageName = netherPkg,
                    requiresBios = false,
                    profiles = emptyList(),
                )
            },
            biosResolver = { _, id, pkg ->
                biosResolverCalls++
                assertEquals(netherId, id)
                assertEquals(netherPkg, pkg)
                listOf(exactScopedMissingProfile(netherId, netherPkg))
            },
        )

        val readiness = controller.resolveReadiness(gameId, ps2)

        assertTrue(readiness is LaunchReadiness.NeedsBios)
        assertEquals(1, biosResolverCalls)
    }

    @Test
    fun nullSeedSnapshotUsesExactRegistryFallback() = runBlocking {
        var biosResolverCalls = 0
        val controller = launchController(
            emulator = ps2Emulator(netherId, netherPkg),
            biosSeedBeforeRead = { _, _, _ -> null },
            biosResolver = { _, id, pkg ->
                biosResolverCalls++
                assertEquals(netherId, id)
                assertEquals(netherPkg, pkg)
                listOf(exactScopedMissingProfile(netherId, netherPkg))
            },
        )

        val readiness = controller.resolveReadiness(gameId, ps2)

        assertTrue(readiness is LaunchReadiness.NeedsBios)
        assertEquals(1, biosResolverCalls)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun ps2Seeder(
        persistence: FakeSeederPersistence,
        rootLister: BiosLibraryRootLister = BiosLibraryRootLister { BiosFolderListing(enumerable = false) },
    ): BiosProfileSeeder = BiosProfileSeeder(
        rootLister = rootLister,
        persistence = persistence,
        emulators = InstalledEmulatorProvider {
            EmulatorSeedRegistry.candidatesFor(it) { pkg ->
                pkg == armsx2Pkg || pkg == netherPkg || pkg == playPkg
            }
        },
        platformDefault = { armsx2Id },
    )

    private fun ps2Emulator(id: EmulatorId, packageName: String): EmulatorProfile = EmulatorProfile(
        id = id,
        packageName = packageName,
        displayName = packageName,
        installed = true,
        capability = EmulatorCapability.DIRECT_PAYLOAD,
        locatorForm = LaunchLocatorForm.FILE_PATH,
        supportedPlatforms = setOf(ps2),
    )

    private fun missingBiosSnapshot(emulatorId: EmulatorId, packageName: String): BiosSeedSnapshot =
        BiosSeedSnapshot(
            platformId = ps2,
            emulatorId = emulatorId,
            packageName = packageName,
            requiresBios = true,
            profiles = listOf(
                BiosProfile(
                    platformId = ps2,
                    fileName = "scph*.bin",
                    required = true,
                    presence = BiosPresence.MISSING,
                    emulatorId = emulatorId,
                    packageName = packageName,
                ),
            ),
        )

    private fun presentBiosSnapshot(emulatorId: EmulatorId, packageName: String): BiosSeedSnapshot =
        BiosSeedSnapshot(
            platformId = ps2,
            emulatorId = emulatorId,
            packageName = packageName,
            requiresBios = true,
            profiles = listOf(
                BiosProfile(
                    platformId = ps2,
                    fileName = "scph*.bin",
                    required = true,
                    presence = BiosPresence.PRESENT,
                    emulatorId = emulatorId,
                    packageName = packageName,
                ),
            ),
        )

    private fun exactScopedMissingProfile(
        emulatorId: EmulatorId,
        packageName: String,
    ): BiosProfile = BiosProfile(
        platformId = ps2,
        fileName = "scph*.bin",
        required = true,
        presence = BiosPresence.MISSING,
        emulatorId = emulatorId,
        packageName = packageName,
    )

    private fun launchController(
        platformId: PlatformId = ps2,
        emulator: EmulatorProfile,
        biosSeedBeforeRead: suspend (PlatformId, EmulatorId, String) -> BiosSeedSnapshot?,
        biosResolver: suspend (PlatformId, EmulatorId, String) -> List<BiosProfile>,
    ): LaunchController = LaunchController(
        emulatorProvider = InstalledEmulatorProvider { listOf(emulator) },
        locatorResolver = { _, _, _, preferredForm ->
            LaunchLocatorResult("/storage/emulated/0/ROMs/${platformId.value}/game.bin", preferredForm)
        },
        overrideResolver = { null },
        overrideSetter = { _, _ -> Unit },
        overrideClearer = { _ -> Unit },
        biosResolver = biosResolver,
        proofRecorder = { },
        dispatcher = LaunchDispatcher { LaunchOutcome.IntentSent },
        platformDefault = { null },
        ioDispatcher = Dispatchers.Unconfined,
        biosSeedBeforeRead = biosSeedBeforeRead,
    )

    private open class FakeSeederPersistence(
        private val platformsWithGames: List<PlatformId> = listOf(PlatformId.PS2),
        var roots: List<BiosLibraryRoot> = emptyList(),
    ) : BiosSeedPersistence {
        data class Write(
            val platformId: PlatformId,
            val emulatorId: EmulatorId,
            val packageName: String,
            val profiles: List<BiosProfile>,
        )

        val writes = mutableListOf<Write>()

        override suspend fun platformsWithGames(): List<PlatformId> = platformsWithGames

        override suspend fun platformForGame(gameId: GameId): PlatformId? = PlatformId.PS2

        override suspend fun libraryRoots(): List<BiosLibraryRoot> = roots

        override suspend fun persist(
            platformId: PlatformId,
            emulatorId: EmulatorId,
            packageName: String,
            profiles: List<BiosProfile>,
        ) {
            writes += Write(platformId, emulatorId, packageName, profiles)
        }
    }

    private class FailingSeederPersistence : FakeSeederPersistence() {
        var attempts = 0

        override suspend fun persist(
            platformId: PlatformId,
            emulatorId: EmulatorId,
            packageName: String,
            profiles: List<BiosProfile>,
        ) {
            attempts++
            throw RuntimeException("disk full")
        }
    }

    private class BarrierSeederPersistence : FakeSeederPersistence() {
        val releaseSecondPersist = CountDownLatch(1)
        val awaitSecondPersistStarted = CountDownLatch(1)
        private val persistGeneration = AtomicInteger(0)

        override suspend fun persist(
            platformId: PlatformId,
            emulatorId: EmulatorId,
            packageName: String,
            profiles: List<BiosProfile>,
        ) {
            val generation = persistGeneration.incrementAndGet()
            if (generation == 2) {
                awaitSecondPersistStarted.countDown()
                releaseSecondPersist.await(5, TimeUnit.SECONDS)
            }
            super.persist(platformId, emulatorId, packageName, profiles)
        }
    }
}
