package com.gruning.mimir.rewrite.core.metadata

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal object MamePayloadManifest {
    const val ASSET_NAME = "mame0287.mpaygz"
    const val FORMAT_VERSION = 1
    const val MAME_RELEASE = "0.287"
    const val SOURCE_ARCHIVE_SHA256 =
        "60444261c6f2378ddf026237b91db4f01a56e83f0597d076c5a6ccc8f734eecd"
    const val PAYLOAD_SHA256 =
        "c750bbcd372169de86fbda30570305a958dd183dd340395edcd3ed797244ac33"
    const val COMPRESSED_SHA256 =
        "4069892519e61111cab1343099b97f92c279018ac41d1e4f63b31bb89609f8b1"
    const val COMPRESSED_BYTES = 2_708_487
    const val PAYLOAD_BYTES = 5_300_397
    const val SET_ROWS = 39_563
    const val DISK_ROWS = 926

    const val POLICY_STAMP =
        "mame-payload-v1:mame-0.287:c750bbcd372169de"
}

internal object MameMemberManifest {
    const val ASSET_NAME = "mame0287.mmemgz"
    const val FORMAT_VERSION = 1
    const val RECORD_BYTES = 24
    const val ROWS = 154_392
    const val PAYLOAD_BYTES = 3_705_424
    const val COMPRESSED_BYTES = 3_292_388
    const val PAYLOAD_SHA256 =
        "e4a367071c6a0d48257097f86e2cb278db58e18c833234530b0fb0ef133c8591"
    const val COMPRESSED_SHA256 =
        "eea4cf67a010418a4020addf9b1d18ee502c038fa16aca78a4706ec12068cd91"
}

internal data class MameSetRecord(
    val memberCount: Int,
    val totalBytes: Long,
    val machineName: String,
    val cloneRoot: String,
    val canonicalTitle: String,
)

internal data class MameDiskRecord(
    val machineName: String,
    val cloneRoot: String,
    val requiredRomCount: Int,
    val requiredDiskCount: Int,
    val canonicalTitle: String,
)

internal interface MameSetLookup {
    val isAvailable: Boolean
    fun set(signature: String): MameSetRecord?
}

internal interface MameMemberLookup {
    val memberIndexAvailable: Boolean
    fun containsMember(sha1: ByteArray, sizeBytes: Long): Boolean
}

internal interface MameDiskLookup {
    fun classifyDisk(combinedSha1: String): ContainerIdentityResult<MameDiskRecord>
}

/**
 * Release-pinned exact MAME payload index. Any manifest, gzip, parser, duplicate-key or row-count drift
 * disables the whole source instead of answering from a partially recovered catalog.
 */
internal class MamePayloadCatalog(
    private val provider: DatAssetProvider,
) : MameSetLookup, MameMemberLookup, MameDiskLookup {
    private val loaded: Catalog? by lazy(LazyThreadSafetyMode.SYNCHRONIZED, ::load)
    private val memberPayload: ByteArray? by lazy(LazyThreadSafetyMode.SYNCHRONIZED, ::loadMembers)

    override val isAvailable: Boolean
        get() = loaded != null

    override fun set(signature: String): MameSetRecord? = loaded?.sets?.get(signature)

    fun disk(combinedSha1: String): MameDiskRecord? = loaded?.disks?.get(combinedSha1)

    override fun classifyDisk(combinedSha1: String): ContainerIdentityResult<MameDiskRecord> {
        if (!isAvailable) {
            return ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.IO_RETRYABLE)
        }
        val record = disk(combinedSha1)
            ?: return ContainerIdentityResult.PolicyAbstain(ContainerIdentityReason.NO_CATALOG_MATCH)
        // The digest identifies this unique machine disk payload. Required ROM/BIOS/device dependencies
        // remain a separate launch-readiness concern and are never inferred from this record.
        return ContainerIdentityResult.Identified(record)
    }

    override val memberIndexAvailable: Boolean
        get() = memberPayload != null

    override fun containsMember(sha1: ByteArray, sizeBytes: Long): Boolean {
        val payload = memberPayload ?: return false
        if (sha1.size != SHA1_BYTES || sizeBytes !in 1..0xffff_ffffL) return false
        var low = 0
        var high = MameMemberManifest.ROWS - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val at = MEMBER_HEADER_BYTES + middle * MameMemberManifest.RECORD_BYTES
            val compared = compareMember(payload, at, sha1, sizeBytes)
            when {
                compared < 0 -> low = middle + 1
                compared > 0 -> high = middle - 1
                else -> return true
            }
        }
        return false
    }

    private fun load(): Catalog? = runCatching {
        val compressed = provider.openRaw(MamePayloadManifest.ASSET_NAME)?.use {
            it.readBounded(MAX_COMPRESSED_BYTES)
        } ?: return@runCatching null
        if (compressed.size != MamePayloadManifest.COMPRESSED_BYTES) return@runCatching null
        if (sha256(compressed) != MamePayloadManifest.COMPRESSED_SHA256) return@runCatching null

        val payload = GZIPInputStream(ByteArrayInputStream(compressed)).use {
            it.readBounded(MAX_PAYLOAD_BYTES)
        } ?: return@runCatching null
        if (payload.size != MamePayloadManifest.PAYLOAD_BYTES) return@runCatching null
        if (sha256(payload) != MamePayloadManifest.PAYLOAD_SHA256) return@runCatching null

        val sets = HashMap<String, MameSetRecord>(MamePayloadManifest.SET_ROWS * 4 / 3)
        val disks = HashMap<String, MameDiskRecord>(MamePayloadManifest.DISK_ROWS * 4 / 3)
        val lines = payload.toString(Charsets.UTF_8).lineSequence().iterator()
        if (!lines.hasNext()) return@runCatching null
        val expectedHeader =
            "#MIMIR_MAME_PAYLOAD_V1\tmame=${MamePayloadManifest.MAME_RELEASE}" +
                "\tsource_sha256=${MamePayloadManifest.SOURCE_ARCHIVE_SHA256}"
        if (lines.next() != expectedHeader) return@runCatching null

        while (lines.hasNext()) {
            val line = lines.next()
            if (line.isEmpty()) continue
            val fields = line.split('\t', limit = 7)
            when (fields.firstOrNull()) {
                "S" -> {
                    if (fields.size != 7 || !SHA256.matches(fields[1])) return@runCatching null
                    val count = fields[2].toIntOrNull()?.takeIf { it in 1..MameZipIdentityReader.MAX_ENTRIES }
                        ?: return@runCatching null
                    val bytes = fields[3].toLongOrNull()?.takeIf { it in 1..MameZipIdentityReader.MAX_TOTAL_BYTES }
                        ?: return@runCatching null
                    val record = MameSetRecord(
                        memberCount = count,
                        totalBytes = bytes,
                        machineName = fields[4].validMachineName() ?: return@runCatching null,
                        cloneRoot = fields[5].validMachineName() ?: return@runCatching null,
                        canonicalTitle = fields[6].takeIf { it.isNotBlank() } ?: return@runCatching null,
                    )
                    if (sets.put(fields[1], record) != null) return@runCatching null
                }

                "D" -> {
                    if (fields.size != 7 || !SHA1.matches(fields[1])) return@runCatching null
                    val record = MameDiskRecord(
                        machineName = fields[2].validMachineName() ?: return@runCatching null,
                        cloneRoot = fields[3].validMachineName() ?: return@runCatching null,
                        requiredRomCount = fields[4].toIntOrNull()?.takeIf { it >= 0 }
                            ?: return@runCatching null,
                        requiredDiskCount = fields[5].toIntOrNull()?.takeIf { it > 0 }
                            ?: return@runCatching null,
                        canonicalTitle = fields[6].takeIf { it.isNotBlank() } ?: return@runCatching null,
                    )
                    if (disks.put(fields[1], record) != null) return@runCatching null
                }

                else -> return@runCatching null
            }
        }
        if (
            sets.size != MamePayloadManifest.SET_ROWS ||
            disks.size != MamePayloadManifest.DISK_ROWS
        ) {
            return@runCatching null
        }
        Catalog(sets = sets, disks = disks)
    }.getOrNull()

    private fun loadMembers(): ByteArray? = runCatching {
        val compressed = provider.openRaw(MameMemberManifest.ASSET_NAME)?.use {
            it.readBounded(MAX_MEMBER_COMPRESSED_BYTES)
        } ?: return@runCatching null
        if (
            compressed.size != MameMemberManifest.COMPRESSED_BYTES ||
            sha256(compressed) != MameMemberManifest.COMPRESSED_SHA256
        ) {
            return@runCatching null
        }
        val payload = GZIPInputStream(ByteArrayInputStream(compressed)).use {
            it.readBounded(MAX_MEMBER_PAYLOAD_BYTES)
        } ?: return@runCatching null
        if (
            payload.size != MameMemberManifest.PAYLOAD_BYTES ||
            sha256(payload) != MameMemberManifest.PAYLOAD_SHA256 ||
            !payload.copyOfRange(0, MEMBER_MAGIC.size).contentEquals(MEMBER_MAGIC) ||
            payload.bigU32(8) != MameMemberManifest.ROWS ||
            payload.bigU32(12) != MameMemberManifest.RECORD_BYTES
        ) {
            return@runCatching null
        }
        val expectedBytes = MEMBER_HEADER_BYTES.toLong() +
            MameMemberManifest.ROWS.toLong() * MameMemberManifest.RECORD_BYTES
        if (expectedBytes != payload.size.toLong()) return@runCatching null
        for (index in 1 until MameMemberManifest.ROWS) {
            val previous = MEMBER_HEADER_BYTES + (index - 1) * MameMemberManifest.RECORD_BYTES
            val current = MEMBER_HEADER_BYTES + index * MameMemberManifest.RECORD_BYTES
            if (compareRecords(payload, previous, current) >= 0) return@runCatching null
        }
        payload
    }.getOrNull()

    private fun compareMember(
        payload: ByteArray,
        at: Int,
        sha1: ByteArray,
        sizeBytes: Long,
    ): Int {
        for (index in 0 until SHA1_BYTES) {
            val compared = (payload[at + index].toInt() and 0xff) -
                (sha1[index].toInt() and 0xff)
            if (compared != 0) return compared
        }
        return payload.bigU32Long(at + SHA1_BYTES).compareTo(sizeBytes)
    }

    private fun compareRecords(payload: ByteArray, left: Int, right: Int): Int {
        for (index in 0 until MameMemberManifest.RECORD_BYTES) {
            val compared = (payload[left + index].toInt() and 0xff) -
                (payload[right + index].toInt() and 0xff)
            if (compared != 0) return compared
        }
        return 0
    }

    private fun ByteArray.bigU32(at: Int): Int? =
        bigU32Long(at).takeIf { it <= Int.MAX_VALUE }?.toInt()

    private fun ByteArray.bigU32Long(at: Int): Long {
        require(at >= 0 && at + 4 <= size)
        return ((this[at].toLong() and 0xff) shl 24) or
            ((this[at + 1].toLong() and 0xff) shl 16) or
            ((this[at + 2].toLong() and 0xff) shl 8) or
            (this[at + 3].toLong() and 0xff)
    }

    private fun String.validMachineName(): String? =
        takeIf { it.length in 1..MAX_MACHINE_NAME && MACHINE_NAME.matches(it) }

    private fun InputStream.readBounded(maxBytes: Int): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            if (read == 0) continue
            if (read > maxBytes - total) return null
            output.write(buffer, 0, read)
            total += read
        }
        return output.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .toHex()

    private data class Catalog(
        val sets: Map<String, MameSetRecord>,
        val disks: Map<String, MameDiskRecord>,
    )

    private companion object {
        const val MAX_COMPRESSED_BYTES = 3 * 1024 * 1024
        const val MAX_PAYLOAD_BYTES = 6 * 1024 * 1024
        const val MAX_MEMBER_COMPRESSED_BYTES = 4 * 1024 * 1024
        const val MAX_MEMBER_PAYLOAD_BYTES = 4 * 1024 * 1024
        const val MEMBER_HEADER_BYTES = 16
        const val SHA1_BYTES = 20
        const val MAX_MACHINE_NAME = 64
        val SHA1 = Regex("[0-9a-f]{40}")
        val SHA256 = Regex("[0-9a-f]{64}")
        val MACHINE_NAME = Regex("[a-z0-9_+.-]+")
        val MEMBER_MAGIC = "MMEMPAY1".toByteArray(Charsets.US_ASCII)
    }
}

internal data class MameSetIdentity(
    val record: MameSetRecord,
    val setSignature: String,
)

/**
 * One atomically completed ZIP observation. [romAtoms] are direct content evidence only; an archive
 * receives machine authority solely when [exactSetIdentity] is non-null.
 */
internal data class MameZipObservation(
    val romAtoms: Set<MameRomAtom>,
    val exactSetIdentity: MameSetIdentity?,
    val expandedBytes: Long,
)

/** Exact direct MAME machine-payload reader with hard expansion and shape budgets. */
internal object MameZipIdentityReader {
    const val MAX_ENTRIES = 512
    const val MAX_ENTRY_BYTES = 32L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 128L * 1024 * 1024
    private const val MAX_NAME_BYTES = 256
    private const val MAX_RATIO = 200L
    private const val MIN_RATIO_CHECK_BYTES = 1024L * 1024
    private const val READ_BUFFER_BYTES = 32 * 1024
    private const val MAX_WALL_NANOS = 45L * 1_000_000_000L

    suspend fun identify(
        input: InputStream,
        catalog: MameSetLookup,
        nanoTime: () -> Long = System::nanoTime,
    ): ContainerIdentityResult<MameSetIdentity> =
        when (val observed = observe(input, catalog, nanoTime)) {
            is ContainerIdentityResult.Identified ->
                observed.value.exactSetIdentity?.let { ContainerIdentityResult.Identified(it) }
                    ?: ContainerIdentityResult.PolicyAbstain(ContainerIdentityReason.NO_CATALOG_MATCH)
            is ContainerIdentityResult.PolicyAbstain -> observed
            is ContainerIdentityResult.NeedsReview -> observed
            is ContainerIdentityResult.RetryableFailure -> observed
        }

    /**
     * Reads one ZIP once and returns its complete CRC-verified member atom set plus an optional exact-set
     * identity. Split/merged/BIOS/device ZIPs can therefore contribute dependency atoms without acquiring
     * machine identity. No partial observation escapes any failure path.
     */
    suspend fun observe(
        input: InputStream,
        catalog: MameSetLookup,
        nanoTime: () -> Long = System::nanoTime,
        absoluteDeadlineNanos: Long? = null,
    ): ContainerIdentityResult<MameZipObservation> {
        return try {
            ZipInputStream(input).use zipUse@ { zip ->
                currentCoroutineContext().ensureActive()
                if (!catalog.isAvailable) {
                    return@zipUse ContainerIdentityResult.RetryableFailure(
                        ContainerIdentityReason.IO_RETRYABLE,
                    )
                }
                val started = nanoTime()
                // `identify` historically admitted exactly MAX_WALL_NANOS and refused only the next tick.
                val localDeadline = MonotonicDeadline.deadlineAfter(started, MAX_WALL_NANOS + 1L)
                val deadline = absoluteDeadlineNanos?.let { minOf(it, localDeadline) } ?: localDeadline
                val names = HashSet<String>()
                val members = ArrayList<MameRomAtom>()
                var totalBytes = 0L
                var entryCount = 0
                val buffer = ByteArray(READ_BUFFER_BYTES)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (!MonotonicDeadline.hasTimeRemaining(deadline, nanoTime())) throw BudgetRefused()
                    val entry = zip.nextEntry ?: break
                    entryCount++
                    if (entryCount > MAX_ENTRIES) throw BudgetRefused()
                    if (entry.isDirectory) {
                        // ZipInputStream.closeEntry() drains the current entry. A malicious archive can
                        // therefore hide a large compressed payload behind a trailing-slash name and make
                        // that drain bypass every byte/ratio/time/cancellation budget below. MAME payload
                        // sets do not need directory records, so reject immediately and let closing the
                        // whole stream release the resident without inflating the entry.
                        throw UnsupportedShape()
                    }
                    val normalizedName = normalizedEntryName(entry.name) ?: throw UnsupportedShape()
                    if (!names.add(normalizedName.lowercase(Locale.ROOT))) throw UnsupportedShape()
                    if (isNestedArchive(normalizedName)) throw UnsupportedShape()
                    if (entry.size > MAX_ENTRY_BYTES) throw BudgetRefused()

                    val digest = MessageDigest.getInstance("SHA-1")
                    var entryBytes = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        if (!MonotonicDeadline.hasTimeRemaining(deadline, nanoTime())) {
                            throw BudgetRefused()
                        }
                        val read = zip.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        entryBytes += read
                        totalBytes += read
                        if (entryBytes > MAX_ENTRY_BYTES || totalBytes > MAX_TOTAL_BYTES) {
                            throw BudgetRefused()
                        }
                        digest.update(buffer, 0, read)
                    }
                    if (entry.size >= 0L && entry.size != entryBytes) throw MalformedArchive()
                    val compressed = entry.compressedSize
                    if (compressed == 0L && entryBytes > 0L) throw BudgetRefused()
                    // Tiny PROM/ROM members are often intentionally repetitive. Absolute caps already
                    // bound them; compression ratio becomes meaningful only at 1 MiB expansion.
                    if (
                        entryBytes >= MIN_RATIO_CHECK_BYTES &&
                        compressed > 0L &&
                        entryBytes / compressed.coerceAtLeast(1L) > MAX_RATIO
                    ) {
                        throw BudgetRefused()
                    }
                    zip.closeEntry() // forces CRC verification before the digest is admitted
                    members += MameRomAtom(
                        sha1 = digest.digest().toHex(),
                        sizeBytes = entryBytes,
                    )
                }
                if (members.isEmpty()) {
                    // ZipInputStream reports no entry for a valid empty ZIP, a zero-byte resident, and
                    // some truncated local headers. Without a positive complete-container proof these
                    // shapes are indistinguishable, so preserve replay instead of terminal-stamping.
                    return@zipUse ContainerIdentityResult.RetryableFailure(
                        ContainerIdentityReason.MALFORMED,
                    )
                }
                val signature = setSignature(members)
                val record = catalog.set(signature)
                if (record != null &&
                    (record.memberCount != members.size || record.totalBytes != totalBytes)
                ) {
                    return@zipUse ContainerIdentityResult.NeedsReview(
                        ContainerIdentityReason.AMBIGUOUS,
                    )
                }
                ContainerIdentityResult.Identified(
                    MameZipObservation(
                        romAtoms = members.toSet(),
                        exactSetIdentity = record?.let { MameSetIdentity(it, signature) },
                        expandedBytes = totalBytes,
                    ),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: UnsupportedShape) {
            ContainerIdentityResult.PolicyAbstain(ContainerIdentityReason.UNSUPPORTED_CONTAINER)
        } catch (_: BudgetRefused) {
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.RESOURCE_REFUSED)
        } catch (_: MalformedArchive) {
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.MALFORMED)
        } catch (_: ZipException) {
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.MALFORMED)
        } catch (_: IOException) {
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.IO_RETRYABLE)
        } catch (_: RuntimeException) {
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.MALFORMED)
        }
    }

    private fun normalizedEntryName(raw: String): String? {
        if (raw.isBlank() || raw.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES) return null
        if (raw.startsWith('/') || raw.startsWith('\\') || '\u0000' in raw || '\\' in raw) return null
        val parts = raw.split('/')
        if (parts.any { it.isBlank() || it == "." || it == ".." }) return null
        return parts.joinToString("/")
    }

    private fun isNestedArchive(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase(Locale.ROOT) in NESTED_ARCHIVES

    private fun setSignature(members: List<MameRomAtom>): String {
        val canonical = buildString {
            for (atom in members.sortedWith(compareBy({ it.sha1 }, { it.sizeBytes }))) {
                append(atom.sha1).append(':').append(atom.sizeBytes).append('\n')
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.US_ASCII))
            .toHex()
    }

    private val NESTED_ARCHIVES = setOf("zip", "7z", "rar", "tar", "gz", "bz2", "xz")
    private class BudgetRefused : IOException()
    private class UnsupportedShape : IOException()
    private class MalformedArchive : IOException()
}

internal data class MameLooseMemberObservation(
    val atom: MameRomAtom,
    val inCatalog: Boolean,
)

/** Bounded loose-member classifier. A hit is informative but never promoted to a complete launch root. */
internal object MameLooseMemberReader {
    private const val READ_BUFFER_BYTES = 32 * 1024
    private const val MAX_WALL_NANOS = 45L * 1_000_000_000L

    suspend fun classify(
        input: InputStream,
        catalog: MameMemberLookup,
        nanoTime: () -> Long = System::nanoTime,
    ): ContainerIdentityResult<Nothing> =
        when (val observed = observe(input, catalog, nanoTime)) {
            is ContainerIdentityResult.Identified ->
                ContainerIdentityResult.PolicyAbstain(
                    if (observed.value.inCatalog) {
                        ContainerIdentityReason.MEMBER_ONLY
                    } else {
                        ContainerIdentityReason.NO_CATALOG_MATCH
                    },
                )
            is ContainerIdentityResult.PolicyAbstain -> observed
            is ContainerIdentityResult.NeedsReview -> observed
            is ContainerIdentityResult.RetryableFailure -> observed
        }

    /** Returns the already-computed loose atom without changing the identity classifier's posture. */
    suspend fun observe(
        input: InputStream,
        catalog: MameMemberLookup,
        nanoTime: () -> Long = System::nanoTime,
        absoluteDeadlineNanos: Long? = null,
    ): ContainerIdentityResult<MameLooseMemberObservation> {
        return try {
            input.use { stream ->
                currentCoroutineContext().ensureActive()
                if (!catalog.memberIndexAvailable) {
                    return@use ContainerIdentityResult.RetryableFailure(
                        ContainerIdentityReason.IO_RETRYABLE,
                    )
                }
                val started = nanoTime()
                // Preserve the classifier's historical `elapsed > MAX_WALL_NANOS` boundary exactly.
                val localDeadline = MonotonicDeadline.deadlineAfter(started, MAX_WALL_NANOS + 1L)
                val deadline = absoluteDeadlineNanos?.let { minOf(it, localDeadline) } ?: localDeadline
                val digest = MessageDigest.getInstance("SHA-1")
                val buffer = ByteArray(READ_BUFFER_BYTES)
                var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (!MonotonicDeadline.hasTimeRemaining(deadline, nanoTime())) {
                        throw MemberBudgetRefused()
                    }
                    val read = stream.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    total += read
                    if (total > MameZipIdentityReader.MAX_ENTRY_BYTES) throw MemberBudgetRefused()
                    digest.update(buffer, 0, read)
                }
                val sha1 = digest.digest()
                ContainerIdentityResult.Identified(
                    MameLooseMemberObservation(
                        atom = MameRomAtom(sha1.toHex(), total),
                        inCatalog = catalog.containsMember(sha1, total),
                    ),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: MemberBudgetRefused) {
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.RESOURCE_REFUSED)
        } catch (_: IOException) {
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.IO_RETRYABLE)
        } catch (_: RuntimeException) {
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.MALFORMED)
        }
    }

    private class MemberBudgetRefused : IOException()
}

private fun ByteArray.toHex(): String = joinToString(separator = "") {
    "%02x".format(Locale.ROOT, it.toInt() and 0xff)
}
