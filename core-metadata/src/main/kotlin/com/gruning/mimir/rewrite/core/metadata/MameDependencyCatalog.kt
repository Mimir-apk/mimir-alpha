package com.gruning.mimir.rewrite.core.metadata

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.GZIPInputStream

object MameDependencyManifest {
    const val ASSET_NAME = "mame0287.mdepgz"
    const val FORMAT_VERSION = 1
    const val MAME_RELEASE = "0.287"
    const val SOURCE_ARCHIVE_SHA256 =
        "60444261c6f2378ddf026237b91db4f01a56e83f0597d076c5a6ccc8f734eecd"
    const val PAYLOAD_SHA256 =
        "e998f2c70dfd6ba66ec48be1beda852b0533dae2e466422f5ff22110852749f7"
    const val COMPRESSED_SHA256 =
        "6e3340222605bb5109ebe774b8d614f5e4fec8d89da9b17700330f23a44c2d25"
    const val COMPRESSED_BYTES = 5_279_142
    const val PAYLOAD_BYTES = 13_441_790
    const val STRING_BYTES = 490_690
    const val MACHINES = 49_860
    const val BIOS_PROFILES = 40_500
    const val ROM_ATOMS = 157_451
    const val DISK_ATOMS = 1_028
    const val ROM_REQUIREMENTS = 363_286
    const val DISK_REQUIREMENTS = 1_211
    const val DEVICE_EDGES = 10_390

    const val POLICY_STAMP =
        "mame-dependency-v1:mame-0.287:e998f2c70dfd6ba6"
}

internal data class MameRomAtom(
    val sha1: String,
    val sizeBytes: Long,
) {
    init {
        require(SHA1.matches(sha1))
        require(sizeBytes in 0..0xffff_ffffL)
    }
}

internal data class MameDiskAtom(
    val overallSha1: String,
) {
    init {
        require(SHA1.matches(overallSha1))
    }
}

internal enum class MameObservedDiskState {
    VERIFIED,
    PARENT_REQUIRED,
    UNVERIFIED,
}

internal enum class MameInventoryFailure {
    IO_RETRYABLE,
    SEEK_UNAVAILABLE,
    RESOURCE_REFUSED,
    MALFORMED_CONTAINER,
    AMBIGUOUS_CONTAINER,
}

/** One complete, root-scoped content inventory. Names never participate in atom equality. */
internal data class MameRootInventory(
    val sourceRootKey: String,
    val complete: Boolean,
    val romAtoms: Set<MameRomAtom>,
    val diskAtoms: Map<MameDiskAtom, MameObservedDiskState>,
    val failures: Set<MameInventoryFailure> = emptySet(),
) {
    init {
        require(sourceRootKey.isNotBlank())
    }
}

internal data class MameClosureRequest(
    val sourceRootKey: String,
    val machineName: String,
    val selectedBios: Map<String, String> = emptyMap(),
    val inventory: MameRootInventory,
)

internal enum class MameClosureReason {
    CATALOG_UNAVAILABLE,
    CATALOG_IO_RETRYABLE,
    CATALOG_MALFORMED,
    CATALOG_RESOURCE_REFUSED,
    CATALOG_BLOCKED,
    INVENTORY_INCOMPLETE,
    MALFORMED_CONTAINER,
    AMBIGUOUS_CONTAINER,
    RESOURCE_REFUSED,
    ROOT_MISMATCH,
    MACHINE_UNRESOLVED,
    NON_RUNNABLE_TARGET,
    BIOS_SELECTION_REQUIRED,
    UNKNOWN_BIOS_SELECTION,
    CHD_PARENT_REQUIRED,
    DISK_UNVERIFIED,
    UNSUPPORTED_MEMBER_SIZE,
    WRITABLE_DISK_UNSUPPORTED,
    DEPENDENCY_CYCLE,
    DEPENDENCY_BUDGET,
}

internal enum class MameRequirementKind {
    ROM,
    DISK,
}

internal data class MameMissingRequirement(
    val kind: MameRequirementKind,
    val requiredByMachineNames: List<String>,
    val rom: MameRomAtom? = null,
    val disk: MameDiskAtom? = null,
)

internal data class MameCoverageReport(
    val machineName: String,
    val sourceRootKey: String,
    val nodesEvaluated: Int,
    val requiredRomAtoms: Int,
    val requiredDiskAtoms: Int,
    val declaredRomRequirements: Int,
    val declaredDiskRequirements: Int,
    val baddumpRequirements: Int,
)

internal sealed interface MameClosureResult {
    data class Satisfied(val report: MameCoverageReport) : MameClosureResult
    data class Incomplete(val missing: List<MameMissingRequirement>) : MameClosureResult
    data class NeedsReview(val reason: MameClosureReason) : MameClosureResult
    data class Retryable(val reason: MameClosureReason) : MameClosureResult
    data class PolicyBlocked(val reason: MameClosureReason) : MameClosureResult
}

internal enum class MameCatalogStatus {
    AVAILABLE,
    MISSING,
    IO_RETRYABLE,
    RESOURCE_REFUSED,
    MALFORMED,
}

/**
 * Compact MAME 0.287 dependency graph. The whole source fails closed on any manifest or reference drift.
 *
 * This source does not identify games. It describes dependency atoms only after another authority has
 * selected a machine.
 */
class MameDependencyCatalog(
    private val provider: DatAssetProvider,
) {
    private val loaded: CatalogLoad by lazy(LazyThreadSafetyMode.SYNCHRONIZED, ::load)

    val isAvailable: Boolean
        get() = loaded.status == MameCatalogStatus.AVAILABLE

    internal val status: MameCatalogStatus
        get() = loaded.status

    internal fun machine(name: String): MachineView? {
        val catalog = loaded.catalog ?: return null
        val id = catalog.machineByName[name] ?: return null
        return MachineView(catalog, id)
    }

    internal fun machineForBiosProfile(profileId: Int): MachineView? {
        val catalog = loaded.catalog ?: return null
        if (profileId !in 0 until catalog.biosCount) return null
        val bios = BiosView(catalog, profileId)
        return MachineView(catalog, bios.machineId)
    }

    internal class MachineView(
        private val catalog: Catalog,
        val id: Int,
    ) {
        private val at = catalog.machineOffset + id * MACHINE_RECORD_BYTES

        val name: String
            get() = catalog.string(catalog.payload.u32(at).toInt())
        val flags: Int
            get() = catalog.payload.u32(at + 4).toInt()
        val defaultBiosId: Int?
            get() = catalog.payload.u32(at + 16).optionalIndex(catalog.biosCount)
        val blockerFlags: Int
            get() = catalog.payload.u32(at + 52).toInt()
        val romParent: MachineView?
            get() = catalog.payload.u32(at + 12)
                .optionalIndex(catalog.machineCount)
                ?.let { MachineView(catalog, it) }

        val runnable: Boolean
            get() = flags and MACHINE_RUNNABLE != 0
        val bios: Boolean
            get() = flags and MACHINE_BIOS != 0
        val device: Boolean
            get() = flags and MACHINE_DEVICE != 0

        fun biosProfiles(): List<BiosView> {
            val start = catalog.payload.u32(at + 20).toInt()
            val count = catalog.payload.u32(at + 24).toInt()
            return List(count) { BiosView(catalog, start + it) }
        }

        fun romRequirements(): List<RomRequirementView> {
            val start = catalog.payload.u32(at + 28).toInt()
            val count = catalog.payload.u32(at + 32).toInt()
            return List(count) { RomRequirementView(catalog, start + it) }
        }

        fun diskRequirements(): List<DiskRequirementView> {
            val start = catalog.payload.u32(at + 36).toInt()
            val count = catalog.payload.u32(at + 40).toInt()
            return List(count) { DiskRequirementView(catalog, start + it) }
        }

        fun deviceDependencies(): List<MachineView> {
            val start = catalog.payload.u32(at + 44).toInt()
            val count = catalog.payload.u32(at + 48).toInt()
            return List(count) { index ->
                val id = catalog.payload.u32(catalog.deviceOffset + (start + index) * 4).toInt()
                MachineView(catalog, id)
            }
        }
    }

    internal class BiosView(
        private val catalog: Catalog,
        val id: Int,
    ) {
        private val at = catalog.biosOffset + id * BIOS_RECORD_BYTES
        val machineId: Int
            get() = catalog.payload.u32(at).toInt()
        val name: String
            get() = catalog.string(catalog.payload.u32(at + 4).toInt())
        val effectiveDefault: Boolean
            get() = catalog.payload.u32(at + 8).toInt() and BIOS_EFFECTIVE_DEFAULT != 0
    }

    internal class RomRequirementView(
        private val catalog: Catalog,
        val id: Int,
    ) {
        private val at = catalog.romRequirementOffset + id * ROM_REQUIREMENT_RECORD_BYTES
        val atom: MameRomAtom
            get() {
                val atomAt = catalog.romAtomOffset + catalog.payload.u32(at).toInt() * ROM_ATOM_RECORD_BYTES
                return MameRomAtom(
                    sha1 = catalog.payload.hex(atomAt, SHA1_BYTES),
                    sizeBytes = catalog.payload.u32(atomAt + SHA1_BYTES),
                )
            }
        val biosProfileId: Int?
            get() = catalog.payload.u32(at + 4).optionalIndex(catalog.biosCount)
        val storageOwnerId: Int
            get() = catalog.payload.u32(at + 8).toInt()
        val flags: Int
            get() = catalog.payload.u32(at + 12).toInt()
    }

    internal class DiskRequirementView(
        private val catalog: Catalog,
        val id: Int,
    ) {
        private val at = catalog.diskRequirementOffset + id * DISK_REQUIREMENT_RECORD_BYTES
        val atom: MameDiskAtom
            get() {
                val atomAt = catalog.diskAtomOffset + catalog.payload.u32(at).toInt() * DISK_ATOM_RECORD_BYTES
                return MameDiskAtom(catalog.payload.hex(atomAt, SHA1_BYTES))
            }
        val storageOwnerId: Int
            get() = catalog.payload.u32(at + 4).toInt()
        val flags: Int
            get() = catalog.payload.u32(at + 8).toInt()
    }

    private fun load(): CatalogLoad {
        val compressed = try {
            val source = provider.openRaw(MameDependencyManifest.ASSET_NAME)
                ?: return CatalogLoad(MameCatalogStatus.MISSING)
            source.use { it.readBounded(MAX_COMPRESSED_BYTES) }
                ?: return CatalogLoad(MameCatalogStatus.RESOURCE_REFUSED)
        } catch (_: IOException) {
            return CatalogLoad(MameCatalogStatus.IO_RETRYABLE)
        } catch (_: SecurityException) {
            return CatalogLoad(MameCatalogStatus.IO_RETRYABLE)
        }
        if (
            compressed.size != MameDependencyManifest.COMPRESSED_BYTES ||
            sha256(compressed) != MameDependencyManifest.COMPRESSED_SHA256
        ) {
            return CatalogLoad(MameCatalogStatus.MALFORMED)
        }
        val payload = try {
            GZIPInputStream(ByteArrayInputStream(compressed)).use {
                it.readBounded(MAX_PAYLOAD_BYTES)
            } ?: return CatalogLoad(MameCatalogStatus.RESOURCE_REFUSED)
        } catch (_: IOException) {
            // At this point the complete compressed byte array is resident and hash-locked.
            // Any gzip read failure is deterministic corruption, not transient provider I/O.
            return CatalogLoad(MameCatalogStatus.MALFORMED)
        }
        if (
            payload.size != MameDependencyManifest.PAYLOAD_BYTES ||
            sha256(payload) != MameDependencyManifest.PAYLOAD_SHA256
        ) {
            return CatalogLoad(MameCatalogStatus.MALFORMED)
        }
        val catalog = parsePayload(payload)
            ?: return CatalogLoad(MameCatalogStatus.MALFORMED)
        return CatalogLoad(MameCatalogStatus.AVAILABLE, catalog)
    }

    private fun parsePayload(payload: ByteArray): Catalog? {
        if (
            payload.size < MAGIC.size ||
            !payload.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)
        ) {
            return null
        }
        if (payload.u32(8) != MameDependencyManifest.FORMAT_VERSION.toLong()) {
            return null
        }
        val stringBytes = payload.u32(12).exactInt(MameDependencyManifest.STRING_BYTES)
            ?: return null
        val machineCount = payload.u32(16).exactInt(MameDependencyManifest.MACHINES)
            ?: return null
        val biosCount = payload.u32(20).exactInt(MameDependencyManifest.BIOS_PROFILES)
            ?: return null
        val romAtomCount = payload.u32(24).exactInt(MameDependencyManifest.ROM_ATOMS)
            ?: return null
        val diskAtomCount = payload.u32(28).exactInt(MameDependencyManifest.DISK_ATOMS)
            ?: return null
        val romRequirementCount = payload.u32(32).exactInt(MameDependencyManifest.ROM_REQUIREMENTS)
            ?: return null
        val diskRequirementCount = payload.u32(36).exactInt(MameDependencyManifest.DISK_REQUIREMENTS)
            ?: return null
        val deviceCount = payload.u32(40).exactInt(MameDependencyManifest.DEVICE_EDGES)
            ?: return null

        var offset = HEADER_BYTES
        val stringOffset = offset
        offset = offset.checkedAdvance(stringBytes) ?: return null
        val machineOffset = offset
        offset = offset.checkedAdvance(machineCount, MACHINE_RECORD_BYTES) ?: return null
        val biosOffset = offset
        offset = offset.checkedAdvance(biosCount, BIOS_RECORD_BYTES) ?: return null
        val romAtomOffset = offset
        offset = offset.checkedAdvance(romAtomCount, ROM_ATOM_RECORD_BYTES) ?: return null
        val diskAtomOffset = offset
        offset = offset.checkedAdvance(diskAtomCount, DISK_ATOM_RECORD_BYTES) ?: return null
        val romRequirementOffset = offset
        offset = offset.checkedAdvance(
            romRequirementCount,
            ROM_REQUIREMENT_RECORD_BYTES,
        ) ?: return null
        val diskRequirementOffset = offset
        offset = offset.checkedAdvance(
            diskRequirementCount,
            DISK_REQUIREMENT_RECORD_BYTES,
        ) ?: return null
        val deviceOffset = offset
        offset = offset.checkedAdvance(deviceCount, DEVICE_RECORD_BYTES) ?: return null
        if (offset != payload.size) return null

        val stringStarts = validateStrings(payload, stringOffset, stringBytes) ?: return null
        val shell = Catalog(
            payload = payload,
            stringOffset = stringOffset,
            stringBytes = stringBytes,
            stringStarts = stringStarts,
            machineOffset = machineOffset,
            machineCount = machineCount,
            biosOffset = biosOffset,
            biosCount = biosCount,
            romAtomOffset = romAtomOffset,
            romAtomCount = romAtomCount,
            diskAtomOffset = diskAtomOffset,
            diskAtomCount = diskAtomCount,
            romRequirementOffset = romRequirementOffset,
            romRequirementCount = romRequirementCount,
            diskRequirementOffset = diskRequirementOffset,
            diskRequirementCount = diskRequirementCount,
            deviceOffset = deviceOffset,
            deviceCount = deviceCount,
            machineByName = emptyMap(),
        )
        val machineByName = validateTables(shell) ?: return null
        return shell.copy(machineByName = machineByName)
    }

    private fun validateTables(catalog: Catalog): Map<String, Int>? {
        val machineByName = LinkedHashMap<String, Int>(catalog.machineCount * 4 / 3)
        var previousName = ""
        var nextBios = 0
        var nextRom = 0
        var nextDisk = 0
        var nextDevice = 0
        repeat(catalog.machineCount) { id ->
            val at = catalog.machineOffset + id * MACHINE_RECORD_BYTES
            val name = catalog.stringOrNull(catalog.payload.u32(at).toInt()) ?: return null
            if (!MACHINE_NAME.matches(name) || name <= previousName) return null
            previousName = name
            if (machineByName.put(name, id) != null) return null
            val flags = catalog.payload.u32(at + 4).toInt()
            if (flags and MACHINE_FLAGS.inv() != 0) return null
            if (!catalog.payload.u32(at + 8).validOptionalIndex(catalog.machineCount)) return null
            if (!catalog.payload.u32(at + 12).validOptionalIndex(catalog.machineCount)) return null
            val defaultBios = catalog.payload.u32(at + 16)
            if (!defaultBios.validOptionalIndex(catalog.biosCount)) return null
            val biosStart = catalog.payload.u32(at + 20).toInt()
            val biosCount = catalog.payload.u32(at + 24).toInt()
            val romStart = catalog.payload.u32(at + 28).toInt()
            val romCount = catalog.payload.u32(at + 32).toInt()
            val diskStart = catalog.payload.u32(at + 36).toInt()
            val diskCount = catalog.payload.u32(at + 40).toInt()
            val deviceStart = catalog.payload.u32(at + 44).toInt()
            val deviceCount = catalog.payload.u32(at + 48).toInt()
            if (
                biosStart != nextBios || romStart != nextRom || diskStart != nextDisk ||
                deviceStart != nextDevice ||
                !rangeFits(biosStart, biosCount, catalog.biosCount) ||
                !rangeFits(romStart, romCount, catalog.romRequirementCount) ||
                !rangeFits(diskStart, diskCount, catalog.diskRequirementCount) ||
                !rangeFits(deviceStart, deviceCount, catalog.deviceCount)
            ) {
                return null
            }
            if (
                defaultBios != NONE &&
                defaultBios.toInt() !in biosStart until biosStart + biosCount
            ) {
                return null
            }
            if ((biosCount == 0) != (defaultBios == NONE)) return null
            val biosNames = HashSet<String>(biosCount * 4 / 3 + 1)
            var effectiveDefaultId: Int? = null
            repeat(biosCount) { relativeId ->
                val biosId = biosStart + relativeId
                val biosAt = catalog.biosOffset + biosId * BIOS_RECORD_BYTES
                if (catalog.payload.u32(biosAt).toInt() != id) return null
                val biosName =
                    catalog.stringOrNull(catalog.payload.u32(biosAt + 4).toInt()) ?: return null
                if (!biosNames.add(biosName)) return null
                val biosFlags = catalog.payload.u32(biosAt + 8).toInt()
                if (biosFlags and BIOS_FLAGS.inv() != 0) return null
                if (biosFlags and BIOS_EFFECTIVE_DEFAULT != 0) {
                    if (effectiveDefaultId != null) return null
                    effectiveDefaultId = biosId
                }
            }
            if (biosCount > 0 && effectiveDefaultId != defaultBios.toInt()) return null
            var previousDeviceId = -1
            repeat(deviceCount) { relativeId ->
                val dependencyId = catalog.payload.u32(
                    catalog.deviceOffset + (deviceStart + relativeId) * DEVICE_RECORD_BYTES,
                ).toInt()
                if (dependencyId !in 0 until catalog.machineCount || dependencyId <= previousDeviceId) {
                    return null
                }
                previousDeviceId = dependencyId
            }
            val blockers = catalog.payload.u32(at + 52).toInt()
            if (blockers and BLOCKER_FLAGS.inv() != 0) return null
            nextBios += biosCount
            nextRom += romCount
            nextDisk += diskCount
            nextDevice += deviceCount
        }
        if (
            nextBios != catalog.biosCount ||
            nextRom != catalog.romRequirementCount ||
            nextDisk != catalog.diskRequirementCount ||
            nextDevice != catalog.deviceCount
        ) {
            return null
        }

        repeat(catalog.biosCount) { id ->
            val at = catalog.biosOffset + id * BIOS_RECORD_BYTES
            val machineId = catalog.payload.u32(at).toInt()
            if (machineId !in 0 until catalog.machineCount) return null
            if (catalog.stringOrNull(catalog.payload.u32(at + 4).toInt()) == null) return null
            if (catalog.payload.u32(at + 8).toInt() and BIOS_FLAGS.inv() != 0) return null
        }
        if (!validateSortedAtoms(catalog)) return null
        repeat(catalog.romRequirementCount) { id ->
            val at = catalog.romRequirementOffset + id * ROM_REQUIREMENT_RECORD_BYTES
            if (catalog.payload.u32(at) >= catalog.romAtomCount.toLong()) return null
            if (!catalog.payload.u32(at + 4).validOptionalIndex(catalog.biosCount)) return null
            if (catalog.payload.u32(at + 8) >= catalog.machineCount.toLong()) return null
            if (catalog.payload.u32(at + 12).toInt() and ROM_REQUIREMENT_FLAGS.inv() != 0) return null
        }
        repeat(catalog.diskRequirementCount) { id ->
            val at = catalog.diskRequirementOffset + id * DISK_REQUIREMENT_RECORD_BYTES
            if (catalog.payload.u32(at) >= catalog.diskAtomCount.toLong()) return null
            if (catalog.payload.u32(at + 4) >= catalog.machineCount.toLong()) return null
            if (catalog.payload.u32(at + 8).toInt() and DISK_REQUIREMENT_FLAGS.inv() != 0) return null
            if (catalog.payload.u32(at + 12) != 0L) return null
        }
        repeat(catalog.deviceCount) { id ->
            if (catalog.payload.u32(catalog.deviceOffset + id * 4) >= catalog.machineCount.toLong()) {
                return null
            }
        }
        return machineByName
    }

    private fun validateSortedAtoms(catalog: Catalog): Boolean {
        var previousRom: ByteArray? = null
        repeat(catalog.romAtomCount) { id ->
            val at = catalog.romAtomOffset + id * ROM_ATOM_RECORD_BYTES
            val atom = catalog.payload.copyOfRange(at, at + ROM_ATOM_RECORD_BYTES)
            if (previousRom != null && compareUnsigned(previousRom!!, atom) >= 0) return false
            previousRom = atom
        }
        var previousDisk: ByteArray? = null
        repeat(catalog.diskAtomCount) { id ->
            val at = catalog.diskAtomOffset + id * DISK_ATOM_RECORD_BYTES
            val atom = catalog.payload.copyOfRange(at, at + DISK_ATOM_RECORD_BYTES)
            if (previousDisk != null && compareUnsigned(previousDisk!!, atom) >= 0) return false
            previousDisk = atom
        }
        return true
    }

    internal data class Catalog(
        val payload: ByteArray,
        val stringOffset: Int,
        val stringBytes: Int,
        val stringStarts: Set<Int>,
        val machineOffset: Int,
        val machineCount: Int,
        val biosOffset: Int,
        val biosCount: Int,
        val romAtomOffset: Int,
        val romAtomCount: Int,
        val diskAtomOffset: Int,
        val diskAtomCount: Int,
        val romRequirementOffset: Int,
        val romRequirementCount: Int,
        val diskRequirementOffset: Int,
        val diskRequirementCount: Int,
        val deviceOffset: Int,
        val deviceCount: Int,
        val machineByName: Map<String, Int>,
    ) {
        fun string(relativeOffset: Int): String =
            requireNotNull(stringOrNull(relativeOffset))

        fun stringOrNull(relativeOffset: Int): String? {
            if (relativeOffset !in stringStarts) return null
            val start = stringOffset + relativeOffset
            val end = payload.zeroIndexFrom(start, stringOffset + stringBytes)
            if (end !in start until stringOffset + stringBytes) return null
            return String(payload, start, end - start, Charsets.US_ASCII)
        }
    }

    private data class CatalogLoad(
        val status: MameCatalogStatus,
        val catalog: Catalog? = null,
    ) {
        init {
            require((status == MameCatalogStatus.AVAILABLE) == (catalog != null))
        }
    }

    private companion object {
        const val HEADER_BYTES = 44
        const val MACHINE_RECORD_BYTES = 56
        const val BIOS_RECORD_BYTES = 12
        const val ROM_ATOM_RECORD_BYTES = 24
        const val DISK_ATOM_RECORD_BYTES = 20
        const val ROM_REQUIREMENT_RECORD_BYTES = 16
        const val DISK_REQUIREMENT_RECORD_BYTES = 16
        const val DEVICE_RECORD_BYTES = 4
        const val SHA1_BYTES = 20
        const val MAX_COMPRESSED_BYTES = 6 * 1024 * 1024
        const val MAX_PAYLOAD_BYTES = 14 * 1024 * 1024
        const val MACHINE_RUNNABLE = 1 shl 0
        const val MACHINE_BIOS = 1 shl 1
        const val MACHINE_DEVICE = 1 shl 2
        const val MACHINE_FLAGS = MACHINE_RUNNABLE or MACHINE_BIOS or MACHINE_DEVICE
        const val BIOS_EFFECTIVE_DEFAULT = 1 shl 1
        const val BIOS_FLAGS = (1 shl 0) or BIOS_EFFECTIVE_DEFAULT
        const val BLOCK_UNHASHABLE_ROM = 1 shl 0
        const val BLOCK_UNHASHABLE_DISK = 1 shl 1
        const val BLOCK_UNRESOLVED_MERGE = 1 shl 2
        const val BLOCK_UNKNOWN_BIOS = 1 shl 3
        const val BLOCK_MULTIPLE_DEFAULT_BIOS = 1 shl 4
        const val BLOCK_UNSUPPORTED_ROM_SIZE = 1 shl 5
        const val BLOCK_UNKNOWN_DEVICE = 1 shl 6
        const val BLOCK_ANCESTRY_CYCLE = 1 shl 7
        const val BLOCKER_FLAGS = (1 shl 8) - 1
        const val REQ_BADDUMP = 1 shl 1
        const val REQ_UNSUPPORTED_SIZE = 1 shl 2
        const val REQ_WRITABLE = 1 shl 3
        const val ROM_REQUIREMENT_FLAGS = (1 shl 3) - 1
        const val DISK_REQUIREMENT_FLAGS = (1 shl 4) - 1
        const val NONE = 0xffff_ffffL
        val MAGIC = "MAMEDEP1".toByteArray(Charsets.US_ASCII)
        val MACHINE_NAME = Regex("[a-z0-9_+.-]{1,64}")
    }
}

/** Pure, root-scoped dependency coverage. It does not identify, group or launch a game. */
internal class MameDependencyClosureEvaluator(
    private val catalog: MameDependencyCatalog,
) {
    fun evaluate(request: MameClosureRequest): MameClosureResult {
        when (catalog.status) {
            MameCatalogStatus.AVAILABLE -> Unit
            MameCatalogStatus.MISSING ->
                return MameClosureResult.PolicyBlocked(MameClosureReason.CATALOG_UNAVAILABLE)
            MameCatalogStatus.IO_RETRYABLE ->
                return MameClosureResult.Retryable(MameClosureReason.CATALOG_IO_RETRYABLE)
            MameCatalogStatus.RESOURCE_REFUSED ->
                return MameClosureResult.PolicyBlocked(MameClosureReason.CATALOG_RESOURCE_REFUSED)
            MameCatalogStatus.MALFORMED ->
                return MameClosureResult.PolicyBlocked(MameClosureReason.CATALOG_MALFORMED)
        }
        if (
            request.sourceRootKey.isBlank() ||
            request.sourceRootKey != request.inventory.sourceRootKey
        ) {
            return MameClosureResult.PolicyBlocked(MameClosureReason.ROOT_MISMATCH)
        }
        if (
            MameInventoryFailure.MALFORMED_CONTAINER in request.inventory.failures
        ) {
            return MameClosureResult.NeedsReview(MameClosureReason.MALFORMED_CONTAINER)
        }
        if (
            MameInventoryFailure.AMBIGUOUS_CONTAINER in request.inventory.failures
        ) {
            return MameClosureResult.NeedsReview(MameClosureReason.AMBIGUOUS_CONTAINER)
        }
        if (
            MameInventoryFailure.RESOURCE_REFUSED in request.inventory.failures
        ) {
            return MameClosureResult.PolicyBlocked(MameClosureReason.RESOURCE_REFUSED)
        }
        if (
            !request.inventory.complete ||
            MameInventoryFailure.IO_RETRYABLE in request.inventory.failures ||
            MameInventoryFailure.SEEK_UNAVAILABLE in request.inventory.failures
        ) {
            return MameClosureResult.Retryable(MameClosureReason.INVENTORY_INCOMPLETE)
        }
        val target = catalog.machine(request.machineName)
            ?: return MameClosureResult.NeedsReview(MameClosureReason.MACHINE_UNRESOLVED)
        if (!target.runnable || target.bios || target.device) {
            return MameClosureResult.PolicyBlocked(MameClosureReason.NON_RUNNABLE_TARGET)
        }

        // MAME listxml's device_ref rows already flatten the target's instantiated
        // device subtree. Following a referenced device's standalone graph would
        // invent configuration dependencies that are not present on this target.
        val directDependencies = target.deviceDependencies()
        if (directDependencies.size + 1 > MAX_DEPENDENCY_NODES) {
            return MameClosureResult.PolicyBlocked(MameClosureReason.DEPENDENCY_BUDGET)
        }
        if (directDependencies.any { it.id == target.id }) {
            return MameClosureResult.NeedsReview(MameClosureReason.DEPENDENCY_CYCLE)
        }
        val nodes = ArrayList<MameDependencyCatalog.MachineView>(directDependencies.size + 1)
        nodes += target
        nodes += directDependencies

        val selectedBios = HashMap<Int, Int?>()
        fun activeBios(machine: MameDependencyCatalog.MachineView): MameClosureResult? {
            if (machine.id in selectedBios) return null
            val profiles = machine.biosProfiles()
            if (profiles.isEmpty()) {
                selectedBios[machine.id] = null
                return null
            }
            val requested = request.selectedBios[machine.name]
            val selected = if (requested != null) {
                profiles.singleOrNull { it.name == requested }
                    ?: return MameClosureResult.NeedsReview(MameClosureReason.UNKNOWN_BIOS_SELECTION)
            } else {
                profiles.singleOrNull { it.effectiveDefault }
                    ?: return MameClosureResult.NeedsReview(MameClosureReason.BIOS_SELECTION_REQUIRED)
            }
            selectedBios[machine.id] = selected.id
            return null
        }
        nodes.forEach { activeBios(it)?.let { result -> return result } }

        val requiredRoms = LinkedHashMap<MameRomAtom, LinkedHashSet<String>>()
        val requiredDisks = LinkedHashMap<MameDiskAtom, LinkedHashSet<String>>()
        var declaredRomRequirements = 0
        var declaredDiskRequirements = 0
        var baddumpRequirements = 0
        for (node in nodes) {
            val blockers = node.blockerFlags
            if (blockers and BLOCK_UNSUPPORTED_ROM_SIZE != 0) {
                return MameClosureResult.PolicyBlocked(MameClosureReason.UNSUPPORTED_MEMBER_SIZE)
            }
            if (blockers and BLOCK_HARD_FAILURES != 0) {
                return MameClosureResult.PolicyBlocked(MameClosureReason.CATALOG_BLOCKED)
            }
            if (blockers and BLOCK_MULTIPLE_DEFAULT_BIOS != 0 && request.selectedBios[node.name] == null) {
                return MameClosureResult.NeedsReview(MameClosureReason.BIOS_SELECTION_REQUIRED)
            }
            for (requirement in node.romRequirements()) {
                val profileId = requirement.biosProfileId
                if (profileId != null) {
                    val owner = nodes.firstOrNull { machine ->
                        machine.biosProfiles().any { it.id == profileId }
                    } ?: catalog.machineForBiosProfile(profileId)
                    if (owner == null) {
                        return MameClosureResult.PolicyBlocked(MameClosureReason.CATALOG_BLOCKED)
                    }
                    activeBios(owner)?.let { return it }
                    if (selectedBios[owner.id] != profileId) continue
                }
                if (requirement.flags and REQ_UNSUPPORTED_SIZE != 0) {
                    return MameClosureResult.PolicyBlocked(MameClosureReason.UNSUPPORTED_MEMBER_SIZE)
                }
                if (requirement.flags and REQ_BADDUMP != 0) baddumpRequirements++
                declaredRomRequirements++
                requiredRoms.getOrPut(requirement.atom, ::LinkedHashSet) += node.name
            }
            for (requirement in node.diskRequirements()) {
                if (requirement.flags and REQ_WRITABLE != 0) {
                    return MameClosureResult.PolicyBlocked(MameClosureReason.WRITABLE_DISK_UNSUPPORTED)
                }
                if (requirement.flags and REQ_BADDUMP != 0) baddumpRequirements++
                declaredDiskRequirements++
                requiredDisks.getOrPut(requirement.atom, ::LinkedHashSet) += node.name
            }
        }

        val missing = ArrayList<MameMissingRequirement>()
        requiredRoms.forEach { (atom, owners) ->
            if (atom !in request.inventory.romAtoms) {
                missing += MameMissingRequirement(
                    kind = MameRequirementKind.ROM,
                    requiredByMachineNames = owners.toList(),
                    rom = atom,
                )
            }
        }
        var parentRequired = false
        var diskUnverified = false
        requiredDisks.forEach { (atom, owners) ->
            when (request.inventory.diskAtoms[atom]) {
                MameObservedDiskState.VERIFIED -> Unit
                MameObservedDiskState.PARENT_REQUIRED -> parentRequired = true
                MameObservedDiskState.UNVERIFIED -> diskUnverified = true
                null -> {
                    missing += MameMissingRequirement(
                        kind = MameRequirementKind.DISK,
                        requiredByMachineNames = owners.toList(),
                        disk = atom,
                    )
                }
            }
        }
        if (diskUnverified) {
            return MameClosureResult.NeedsReview(MameClosureReason.DISK_UNVERIFIED)
        }
        if (parentRequired) {
            return MameClosureResult.Retryable(MameClosureReason.CHD_PARENT_REQUIRED)
        }
        if (missing.isNotEmpty()) {
            return MameClosureResult.Incomplete(
                missing.sortedWith(
                    compareBy(
                        { it.kind.ordinal },
                        { it.requiredByMachineNames.joinToString(separator = "\u0000") },
                        { it.rom?.sha1 ?: it.disk?.overallSha1.orEmpty() },
                    ),
                ),
            )
        }
        return MameClosureResult.Satisfied(
            MameCoverageReport(
                machineName = target.name,
                sourceRootKey = request.sourceRootKey,
                nodesEvaluated = nodes.size,
                requiredRomAtoms = requiredRoms.size,
                requiredDiskAtoms = requiredDisks.size,
                declaredRomRequirements = declaredRomRequirements,
                declaredDiskRequirements = declaredDiskRequirements,
                baddumpRequirements = baddumpRequirements,
            ),
        )
    }

    private companion object {
        const val MAX_DEPENDENCY_NODES = 512
        const val BLOCK_UNHASHABLE_ROM = 1 shl 0
        const val BLOCK_UNHASHABLE_DISK = 1 shl 1
        const val BLOCK_UNRESOLVED_MERGE = 1 shl 2
        const val BLOCK_UNKNOWN_BIOS = 1 shl 3
        const val BLOCK_MULTIPLE_DEFAULT_BIOS = 1 shl 4
        const val BLOCK_UNSUPPORTED_ROM_SIZE = 1 shl 5
        const val BLOCK_UNKNOWN_DEVICE = 1 shl 6
        const val BLOCK_ANCESTRY_CYCLE = 1 shl 7
        const val BLOCK_HARD_FAILURES =
            BLOCK_UNHASHABLE_ROM or BLOCK_UNHASHABLE_DISK or BLOCK_UNRESOLVED_MERGE or
                BLOCK_UNKNOWN_BIOS or BLOCK_UNKNOWN_DEVICE or BLOCK_ANCESTRY_CYCLE
        const val REQ_BADDUMP = 1 shl 1
        const val REQ_UNSUPPORTED_SIZE = 1 shl 2
        const val REQ_WRITABLE = 1 shl 3
    }
}

private fun validateStrings(payload: ByteArray, offset: Int, bytes: Int): Set<Int>? {
    if (bytes <= 0 || offset < 0 || offset + bytes > payload.size || payload[offset + bytes - 1] != 0.toByte()) {
        return null
    }
    val starts = LinkedHashSet<Int>()
    var at = offset
    while (at < offset + bytes) {
        starts += at - offset
        val end = payload.zeroIndexFrom(at, offset + bytes)
        if (end !in at + 1 until offset + bytes) return null
        for (index in at until end) {
            if ((payload[index].toInt() and 0xff) !in 0x21..0x7e) return null
        }
        at = end + 1
    }
    return starts
}

private fun ByteArray.u32(at: Int): Long {
    require(at >= 0 && at + 4 <= size)
    return ((this[at].toLong() and 0xff) shl 24) or
        ((this[at + 1].toLong() and 0xff) shl 16) or
        ((this[at + 2].toLong() and 0xff) shl 8) or
        (this[at + 3].toLong() and 0xff)
}

private fun ByteArray.zeroIndexFrom(start: Int, limit: Int): Int {
    var index = start
    while (index < limit && this[index] != 0.toByte()) index++
    return index
}

private fun ByteArray.hex(at: Int, count: Int): String {
    require(at >= 0 && count >= 0 && at + count <= size)
    return (at until at + count).joinToString(separator = "") {
        "%02x".format(Locale.ROOT, this[it].toInt() and 0xff)
    }
}

private fun Long.exactInt(expected: Int): Int? =
    takeIf { it == expected.toLong() }?.toInt()

private fun Long.validOptionalIndex(count: Int): Boolean =
    this == 0xffff_ffffL || this < count.toLong()

private fun Long.optionalIndex(count: Int): Int? =
    takeUnless { it == 0xffff_ffffL }?.takeIf { it < count.toLong() }?.toInt()

private fun Int.checkedAdvance(count: Int): Int? {
    if (this < 0 || count < 0) return null
    val result = toLong() + count.toLong()
    return result.takeIf { it <= Int.MAX_VALUE }?.toInt()
}

private fun Int.checkedAdvance(count: Int, recordBytes: Int): Int? {
    if (this < 0 || count < 0 || recordBytes < 0) return null
    val result = toLong() + count.toLong() * recordBytes
    return result.takeIf { it <= Int.MAX_VALUE }?.toInt()
}

private fun rangeFits(start: Int, count: Int, total: Int): Boolean =
    start >= 0 && count >= 0 && start.toLong() + count <= total.toLong()

private fun compareUnsigned(left: ByteArray, right: ByteArray): Int {
    for (index in 0 until minOf(left.size, right.size)) {
        val compared = (left[index].toInt() and 0xff) - (right[index].toInt() and 0xff)
        if (compared != 0) return compared
    }
    return left.size - right.size
}

private fun InputStream.readBounded(maxBytes: Int): ByteArray? {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    var consecutiveZeroReads = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        if (read == 0) {
            consecutiveZeroReads++
            if (consecutiveZeroReads > MAX_CONSECUTIVE_ZERO_READS) return null
            continue
        }
        consecutiveZeroReads = 0
        if (read > maxBytes - total) return null
        output.write(buffer, 0, read)
        total += read
    }
    return output.toByteArray()
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString(separator = "") {
        "%02x".format(Locale.ROOT, it.toInt() and 0xff)
    }

private val SHA1 = Regex("[0-9a-f]{40}")
private const val MAX_CONSECUTIVE_ZERO_READS = 16
