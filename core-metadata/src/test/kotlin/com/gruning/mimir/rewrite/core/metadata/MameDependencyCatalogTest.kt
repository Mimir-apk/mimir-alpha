package com.gruning.mimir.rewrite.core.metadata

import java.io.File
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MameDependencyCatalogTest {

    @Test
    fun `real release-pinned dependency asset passes every runtime lock`() {
        val asset = dependencyAsset()
        val catalog = catalog(asset)

        assertTrue(catalog.isAvailable)
        assertEquals(MameDependencyManifest.COMPRESSED_BYTES.toLong(), asset.length())
        assertNotNull(catalog.machine("pacman"))
        assertNotNull(catalog.machine("neogeo"))
    }

    @Test
    fun `complete root inventory satisfies a known machine without creating identity`() {
        val catalog = catalog(dependencyAsset())
        val target = requireNotNull(catalog.machine("pacman"))
        val inventory = completeInventory(target, "root-a")

        val result = MameDependencyClosureEvaluator(catalog).evaluate(
            MameClosureRequest(
                sourceRootKey = "root-a",
                machineName = target.name,
                inventory = inventory,
            ),
        )

        result as MameClosureResult.Satisfied
        assertEquals("pacman", result.report.machineName)
        assertTrue(result.report.requiredRomAtoms > 0)
        assertEquals(0, result.report.requiredDiskAtoms)
    }

    @Test
    fun `missing atom stays incomplete and a different source root is refused`() {
        val catalog = catalog(dependencyAsset())
        val target = requireNotNull(catalog.machine("pacman"))
        val complete = completeInventory(target, "root-a")
        val removed = complete.romAtoms.first()
        val incomplete = complete.copy(romAtoms = complete.romAtoms - removed)
        val evaluator = MameDependencyClosureEvaluator(catalog)

        val missing = evaluator.evaluate(
            MameClosureRequest("root-a", target.name, inventory = incomplete),
        )
        val crossRoot = evaluator.evaluate(
            MameClosureRequest("root-b", target.name, inventory = complete),
        )

        missing as MameClosureResult.Incomplete
        assertTrue(missing.missing.any { it.rom == removed })
        assertEquals(
            MameClosureResult.PolicyBlocked(MameClosureReason.ROOT_MISMATCH),
            crossRoot,
        )
    }

    @Test
    fun `verified read-only CHD atom participates in dependency coverage without claiming launch`() {
        val catalog = catalog(dependencyAsset())
        val target = requireNotNull(catalog.machine("sfiii3"))
        val inventory = completeInventory(target, "root-disk")

        val result = MameDependencyClosureEvaluator(catalog).evaluate(
            MameClosureRequest("root-disk", target.name, inventory = inventory),
        )

        assertTrue("expected satisfied disk closure, got $result", result is MameClosureResult.Satisfied)
        assertTrue((result as MameClosureResult.Satisfied).report.requiredDiskAtoms > 0)
    }

    @Test
    fun `CHD parent-needed and unverified states do not collapse into missing media`() {
        val catalog = catalog(dependencyAsset())
        val target = requireNotNull(catalog.machine("sfiii3"))
        val complete = completeInventory(target, "root-disk-state")
        val evaluator = MameDependencyClosureEvaluator(catalog)

        assertEquals(
            MameClosureResult.Retryable(MameClosureReason.CHD_PARENT_REQUIRED),
            evaluator.evaluate(
                MameClosureRequest(
                    "root-disk-state",
                    target.name,
                    inventory = complete.copy(
                        diskAtoms = complete.diskAtoms.mapValues {
                            MameObservedDiskState.PARENT_REQUIRED
                        },
                    ),
                ),
            ),
        )
        assertEquals(
            MameClosureResult.NeedsReview(MameClosureReason.DISK_UNVERIFIED),
            evaluator.evaluate(
                MameClosureRequest(
                    "root-disk-state",
                    target.name,
                    inventory = complete.copy(
                        diskAtoms = complete.diskAtoms.mapValues {
                            MameObservedDiskState.UNVERIFIED
                        },
                    ),
                ),
            ),
        )
    }

    @Test
    fun `flattened direct device list retains media owner without inventing empty intermediary closure`() {
        val catalog = catalog(dependencyAsset())
        val target = requireNotNull(catalog.machine("386sc"))

        assertTrue(target.deviceDependencies().any { it.name == "at_keybc" })
        assertTrue(target.deviceDependencies().none { it.name == "at_mb" })
        val result = MameDependencyClosureEvaluator(catalog).evaluate(
            MameClosureRequest(
                sourceRootKey = "root-transitive-device",
                machineName = target.name,
                inventory = completeInventory(target, "root-transitive-device"),
            ),
        )
        result as MameClosureResult.Satisfied
        assertTrue(result.report.nodesEvaluated >= 2)
    }

    @Test
    fun `one verified payload atom satisfies repeated declarations without duplicate files`() {
        val catalog = catalog(dependencyAsset())
        val target = requireNotNull(catalog.machine("24cdjuke"))
        val repeated = target.romRequirements()
            .groupingBy { it.atom }
            .eachCount()
            .filterValues { it > 1 }

        assertTrue(repeated.isNotEmpty())
        val result = MameDependencyClosureEvaluator(catalog).evaluate(
            MameClosureRequest(
                sourceRootKey = "root-reusable-atoms",
                machineName = target.name,
                inventory = completeInventory(target, "root-reusable-atoms"),
            ),
        )
        assertTrue("expected content-set closure, got $result", result is MameClosureResult.Satisfied)
    }

    @Test
    fun `writable MAME disk remains policy blocked despite a matching atom`() {
        val catalog = catalog(dependencyAsset())
        val target = requireNotNull(catalog.machine("kinst"))

        assertEquals(
            MameClosureResult.PolicyBlocked(MameClosureReason.WRITABLE_DISK_UNSUPPORTED),
            MameDependencyClosureEvaluator(catalog).evaluate(
                MameClosureRequest(
                    sourceRootKey = "root-writable",
                    machineName = target.name,
                    inventory = completeInventory(target, "root-writable"),
                ),
            ),
        )
    }

    @Test
    fun `unknown BIOS choice fails closed even when every payload atom is present`() {
        val catalog = catalog(dependencyAsset())
        val target = requireNotNull(catalog.machine("mslug"))
        val profileId = target.romRequirements().firstNotNullOf { it.biosProfileId }
        val profileOwner = requireNotNull(catalog.machineForBiosProfile(profileId))
        val inventory = completeInventory(target, "root-bios")

        val result = MameDependencyClosureEvaluator(catalog).evaluate(
            MameClosureRequest(
                sourceRootKey = "root-bios",
                machineName = target.name,
                selectedBios = mapOf(profileOwner.name to "not-a-real-bios"),
                inventory = inventory,
            ),
        )

        assertEquals(
            MameClosureResult.NeedsReview(MameClosureReason.UNKNOWN_BIOS_SELECTION),
            result,
        )
    }

    @Test
    fun `implicit default and explicit BIOS choices both select only their payload profile`() {
        val catalog = catalog(dependencyAsset())
        val target = requireNotNull(catalog.machine("mslug"))
        val profileId = target.romRequirements().firstNotNullOf { it.biosProfileId }
        val profileOwner = requireNotNull(catalog.machineForBiosProfile(profileId))
        val profiles = profileOwner.biosProfiles()
        val explicit = profiles.first { !it.effectiveDefault }
        val inventory = completeInventory(target, "root-bios-success")
        val evaluator = MameDependencyClosureEvaluator(catalog)

        assertTrue(
            evaluator.evaluate(
                MameClosureRequest(
                    "root-bios-success",
                    target.name,
                    inventory = inventory,
                ),
            ) is MameClosureResult.Satisfied,
        )
        assertTrue(
            evaluator.evaluate(
                MameClosureRequest(
                    "root-bios-success",
                    target.name,
                    selectedBios = mapOf(profileOwner.name to explicit.name),
                    inventory = inventory,
                ),
            ) is MameClosureResult.Satisfied,
        )
    }

    @Test
    fun `split storage atom is required from the same root but storage layout does not create identity`() {
        val catalog = catalog(dependencyAsset())
        val target = requireNotNull(catalog.machine("mslug"))
        val complete = completeInventory(target, "root-split")
        val evaluator = MameDependencyClosureEvaluator(catalog)
        val parentStored = target.romRequirements()
            .asSequence()
            .filter { it.storageOwnerId != target.id }
            .map { it.atom }
            .distinct()
            .first { candidate ->
                evaluator.evaluate(
                    MameClosureRequest(
                        "root-split",
                        target.name,
                        inventory = complete.copy(romAtoms = complete.romAtoms - candidate),
                    ),
                ) is MameClosureResult.Incomplete
            }

        assertTrue(
            evaluator.evaluate(
                MameClosureRequest(
                    "root-split",
                    target.name,
                    inventory = complete.copy(romAtoms = complete.romAtoms - parentStored),
                ),
            ) is MameClosureResult.Incomplete,
        )
        assertTrue(
            evaluator.evaluate(
                MameClosureRequest("root-split", target.name, inventory = complete),
            ) is MameClosureResult.Satisfied,
        )
    }

    @Test
    fun `clone replacement does not union unrelated parent payloads`() {
        val catalog = catalog(dependencyAsset())
        val target = requireNotNull(catalog.machine("005a"))
        val parent = requireNotNull(target.romParent)
        val targetAtoms = target.romRequirements().mapTo(LinkedHashSet()) { it.atom }
        val parentOnly = parent.romRequirements().map { it.atom }.toSet() - targetAtoms

        assertTrue(parentOnly.isNotEmpty())
        val inventory = completeInventory(target, "root-replacement")
        assertTrue(inventory.romAtoms.intersect(parentOnly).isEmpty())
        assertTrue(
            MameDependencyClosureEvaluator(catalog).evaluate(
                MameClosureRequest("root-replacement", target.name, inventory = inventory),
            ) is MameClosureResult.Satisfied,
        )
    }

    @Test
    fun `multiple verified CHD atoms are all required without filename authority`() {
        val catalog = catalog(dependencyAsset())
        val target = requireNotNull(catalog.machine("2spicy"))
        val complete = completeInventory(target, "root-multi-disk")

        assertTrue(complete.diskAtoms.size >= 2)
        assertTrue(
            MameDependencyClosureEvaluator(catalog).evaluate(
                MameClosureRequest("root-multi-disk", target.name, inventory = complete),
            ) is MameClosureResult.Satisfied,
        )
        val removed = complete.diskAtoms.keys.first()
        assertTrue(
            MameDependencyClosureEvaluator(catalog).evaluate(
                MameClosureRequest(
                    "root-multi-disk",
                    target.name,
                    inventory = complete.copy(diskAtoms = complete.diskAtoms - removed),
                ),
            ) is MameClosureResult.Incomplete,
        )
    }

    @Test
    fun `machine names do not come from inventory or archive display strings`() {
        val catalog = catalog(dependencyAsset())
        val inventory = completeInventory(requireNotNull(catalog.machine("pacman")), "root-a")

        assertEquals(
            MameClosureResult.NeedsReview(MameClosureReason.MACHINE_UNRESOLVED),
            MameDependencyClosureEvaluator(catalog).evaluate(
                MameClosureRequest(
                    sourceRootKey = "root-a",
                    machineName = "filename-that-looks-plausible",
                    inventory = inventory,
                ),
            ),
        )
    }

    @Test
    fun `truncated or drifted dependency source is a deterministic policy failure`() {
        val bytes = dependencyAsset().readBytes()
        val truncated = MameDependencyCatalog(DatAssetProvider { bytes.dropLast(1).toByteArray().inputStream() })
        val driftedBytes = bytes.copyOf().also { it[it.lastIndex / 2] = (it[it.lastIndex / 2].toInt() xor 1).toByte() }
        val drifted = MameDependencyCatalog(DatAssetProvider { driftedBytes.inputStream() })

        assertEquals(false, truncated.isAvailable)
        assertEquals(false, drifted.isAvailable)
        assertEquals(
            MameClosureResult.PolicyBlocked(MameClosureReason.CATALOG_MALFORMED),
            MameDependencyClosureEvaluator(truncated).evaluate(emptyRequest()),
        )
        assertEquals(
            MameClosureResult.PolicyBlocked(MameClosureReason.CATALOG_MALFORMED),
            MameDependencyClosureEvaluator(drifted).evaluate(emptyRequest()),
        )
    }

    @Test
    fun `catalog and inventory failures retain retryable versus terminal posture`() {
        val missing = MameDependencyCatalog(DatAssetProvider { null })
        val io = MameDependencyCatalog(DatAssetProvider { throw IOException("temporary") })
        val oversized = MameDependencyCatalog(
            DatAssetProvider {
                ByteArray(6 * 1024 * 1024 + 1).inputStream()
            },
        )
        assertEquals(
            MameClosureResult.PolicyBlocked(MameClosureReason.CATALOG_UNAVAILABLE),
            MameDependencyClosureEvaluator(missing).evaluate(emptyRequest()),
        )
        assertEquals(
            MameClosureResult.Retryable(MameClosureReason.CATALOG_IO_RETRYABLE),
            MameDependencyClosureEvaluator(io).evaluate(emptyRequest()),
        )
        assertEquals(
            MameClosureResult.PolicyBlocked(MameClosureReason.CATALOG_RESOURCE_REFUSED),
            MameDependencyClosureEvaluator(oversized).evaluate(emptyRequest()),
        )
        var zeroReadCalls = 0
        var zeroReadClosed = false
        val zeroRead = MameDependencyCatalog(
            DatAssetProvider {
                object : InputStream() {
                    override fun read(): Int = 0

                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        zeroReadCalls++
                        return 0
                    }

                    override fun close() {
                        zeroReadClosed = true
                    }
                }
            },
        )
        assertEquals(
            MameClosureResult.PolicyBlocked(MameClosureReason.CATALOG_RESOURCE_REFUSED),
            MameDependencyClosureEvaluator(zeroRead).evaluate(emptyRequest()),
        )
        assertTrue(zeroReadCalls <= 17)
        assertTrue(zeroReadClosed)

        val catalog = catalog(dependencyAsset())
        val target = requireNotNull(catalog.machine("pacman"))
        val complete = completeInventory(target, "root-failures")
        fun withFailure(failure: MameInventoryFailure) = MameDependencyClosureEvaluator(catalog).evaluate(
            MameClosureRequest(
                "root-failures",
                target.name,
                inventory = complete.copy(complete = false, failures = setOf(failure)),
            ),
        )

        assertEquals(
            MameClosureResult.NeedsReview(MameClosureReason.MALFORMED_CONTAINER),
            withFailure(MameInventoryFailure.MALFORMED_CONTAINER),
        )
        assertEquals(
            MameClosureResult.NeedsReview(MameClosureReason.AMBIGUOUS_CONTAINER),
            withFailure(MameInventoryFailure.AMBIGUOUS_CONTAINER),
        )
        assertEquals(
            MameClosureResult.PolicyBlocked(MameClosureReason.RESOURCE_REFUSED),
            withFailure(MameInventoryFailure.RESOURCE_REFUSED),
        )
        assertEquals(
            MameClosureResult.Retryable(MameClosureReason.INVENTORY_INCOMPLETE),
            withFailure(MameInventoryFailure.IO_RETRYABLE),
        )
    }

    private fun completeInventory(
        target: MameDependencyCatalog.MachineView,
        root: String,
    ): MameRootInventory {
        val roms = LinkedHashSet<MameRomAtom>()
        val disks = LinkedHashMap<MameDiskAtom, MameObservedDiskState>()
        val machines = listOf(target) + target.deviceDependencies()
        machines.forEach { machine ->
            machine.romRequirements().forEach { requirement ->
                roms += requirement.atom
            }
            machine.diskRequirements().forEach { requirement ->
                disks[requirement.atom] = MameObservedDiskState.VERIFIED
            }
        }
        return MameRootInventory(
            sourceRootKey = root,
            complete = true,
            romAtoms = roms,
            diskAtoms = disks,
        )
    }

    private fun emptyRequest() = MameClosureRequest(
        sourceRootKey = "root",
        machineName = "pacman",
        inventory = MameRootInventory(
            sourceRootKey = "root",
            complete = true,
            romAtoms = emptySet(),
            diskAtoms = emptyMap(),
        ),
    )

    private fun catalog(asset: File) = MameDependencyCatalog(
        DatAssetProvider { name -> asset.takeIf { name == MameDependencyManifest.ASSET_NAME }?.inputStream() },
    )

    private fun dependencyAsset(): File =
        listOf(File("../app/src/main/assets/dat"), File("app/src/main/assets/dat"))
            .firstOrNull(File::isDirectory)
            ?.resolve(MameDependencyManifest.ASSET_NAME)
            ?.takeIf(File::isFile)
            ?: error("bundled dependency asset is not visible from the test working directory")
}
