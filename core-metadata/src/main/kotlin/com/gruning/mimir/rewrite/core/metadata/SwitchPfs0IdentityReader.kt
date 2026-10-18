package com.gruning.mimir.rewrite.core.metadata

import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal enum class SwitchPackageRole {
    BASE,
    UPDATE,
    DLC,
}

internal data class SwitchPayloadIdentity(
    val rawTitleId: String,
    val baseTitleId: String,
    val role: SwitchPackageRole,
)

/**
 * Keyless Switch NSP ticket-observation reader.
 *
 * It parses only the public PFS0 table and format-defined non-secret ticket fields. It never reads the title
 * key block, account/device fields or encrypted NCA payload and never acquires keys. A structurally valid
 * ticket is not authoritative identity: it is not signature-verified or bound to package NCA/CNMT bytes.
 */
internal object SwitchPfs0IdentityReader {
    const val PARSER_VERSION = 2
    const val POLICY_STAMP = "switch-pfs0-ticket-observation-v2"

    private const val HEADER_BYTES = 0x10
    private const val ENTRY_BYTES = 0x18
    private const val MAX_ENTRIES = 4_096
    private const val MAX_TABLE_BYTES = MAX_ENTRIES * ENTRY_BYTES
    private const val MAX_STRING_BYTES = 1024 * 1024
    private const val MAX_ENTRY_NAME_BYTES = 255
    private const val MAX_TICKETS = 8
    private const val MIN_TICKET_DATA_BYTES = 0x170
    private const val MAX_TICKET_BYTES = 16 * 1024
    private const val TICKET_FORMAT_VERSION_OFFSET = 0x140
    private const val TICKET_TITLE_KEY_TYPE_OFFSET = 0x141
    private const val TICKET_RIGHTS_ID_OFFSET = 0x160
    private const val RIGHTS_ID_BYTES = 16

    suspend fun identify(
        content: RandomAccessContent,
    ): ContainerIdentityResult<SwitchPayloadIdentity> {
        return try {
            currentCoroutineContext().ensureActive()
            val header = content.readExact(0L, HEADER_BYTES)
                ?: return ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.IO_RETRYABLE)
            if (!header.copyOfRange(0, 4).contentEquals(PFS0_MAGIC)) {
                return ContainerIdentityResult.PolicyAbstain(ContainerIdentityReason.UNSUPPORTED_CONTAINER)
            }
            val entryCount = header.littleU32(4)?.takeIf { it in 1..MAX_ENTRIES }
                ?: return ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.MALFORMED)
            val stringBytes = header.littleU32(8)?.takeIf { it in 1..MAX_STRING_BYTES }
                ?: return ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.RESOURCE_REFUSED)
            if (header.littleU32(12) != 0) {
                return ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.MALFORMED)
            }
            val tableBytes = entryCount.toLong() * ENTRY_BYTES
            if (tableBytes !in 1..MAX_TABLE_BYTES.toLong()) {
                return ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.RESOURCE_REFUSED)
            }
            val dataStart = HEADER_BYTES.toLong() + tableBytes + stringBytes
            if (dataStart < 0L || dataStart > content.length) {
                return ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.MALFORMED)
            }

            val table = content.readExact(HEADER_BYTES.toLong(), tableBytes.toInt())
                ?: return ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.IO_RETRYABLE)
            val strings = content.readExact(HEADER_BYTES + tableBytes, stringBytes)
                ?: return ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.IO_RETRYABLE)
            val entries = ArrayList<Pfs0Entry>(entryCount)
            val names = HashSet<String>()
            repeat(entryCount) { index ->
                currentCoroutineContext().ensureActive()
                val at = index * ENTRY_BYTES
                val relativeOffset = table.littleU64(at)
                    ?: return ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.MALFORMED)
                val size = table.littleU64(at + 8)
                    ?: return ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.MALFORMED)
                val stringOffset = table.littleU32(at + 16)
                    ?: return ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.MALFORMED)
                if (table.littleU32(at + 20) != 0 || stringOffset !in strings.indices) {
                    return ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.MALFORMED)
                }
                if (stringOffset > 0 && strings[stringOffset - 1] != 0.toByte()) {
                    return ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.MALFORMED)
                }
                val name = strings.strictAsciiName(stringOffset)
                    ?: return ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.MALFORMED)
                if (!names.add(name.lowercase(Locale.ROOT))) {
                    return ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.MALFORMED)
                }
                val absoluteOffset = checkedAdd(dataStart, relativeOffset)
                    ?: return ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.MALFORMED)
                val end = checkedAdd(absoluteOffset, size)
                    ?: return ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.MALFORMED)
                if (end > content.length) {
                    return ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.MALFORMED)
                }
                entries += Pfs0Entry(name, absoluteOffset, size, end)
            }
            if (entries.sortedBy(Pfs0Entry::offset).zipWithNext().any { (left, right) ->
                    left.size > 0 && right.size > 0 && left.end > right.offset
                }
            ) {
                return ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.MALFORMED)
            }

            val tickets = entries.filter { it.name.endsWith(".tik", ignoreCase = true) }
            if (tickets.isEmpty()) {
                return ContainerIdentityResult.PolicyAbstain(ContainerIdentityReason.NO_CATALOG_MATCH)
            }
            if (tickets.size > MAX_TICKETS) {
                return ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.RESOURCE_REFUSED)
            }
            val identities = ArrayList<SwitchPayloadIdentity>(tickets.size)
            for (ticket in tickets) {
                currentCoroutineContext().ensureActive()
                when (val parsed = parseTicket(content, ticket)) {
                    is ContainerIdentityResult.Identified -> identities += parsed.value
                    is ContainerIdentityResult.PolicyAbstain -> return parsed
                    is ContainerIdentityResult.NeedsReview -> return parsed
                    is ContainerIdentityResult.RetryableFailure -> return parsed
                }
            }
            val distinct = identities.distinct()
            if (distinct.size != 1) {
                return ContainerIdentityResult.NeedsReview(ContainerIdentityReason.AMBIGUOUS)
            }
            val identity = distinct.single()
            if (identity.role == SwitchPackageRole.DLC) {
                ContainerIdentityResult.PolicyAbstain(ContainerIdentityReason.DLC_ROLE_NOT_ADMITTED)
            } else {
                ContainerIdentityResult.Identified(identity)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.MALFORMED)
        }
    }

    private fun parseTicket(
        content: RandomAccessContent,
        entry: Pfs0Entry,
    ): ContainerIdentityResult<SwitchPayloadIdentity> {
        if (entry.size !in 1..MAX_TICKET_BYTES.toLong()) {
            return ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.RESOURCE_REFUSED)
        }
        val signature = content.readExact(entry.offset, 4)
            ?: return ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.IO_RETRYABLE)
        val signatureType = signature.littleU32(0)
            ?: return ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.MALFORMED)
        val signedDataOffset = SIGNED_DATA_OFFSETS[signatureType]
            ?: return ContainerIdentityResult.PolicyAbstain(ContainerIdentityReason.UNSUPPORTED_CONTAINER)
        if (entry.size < signedDataOffset + MIN_TICKET_DATA_BYTES) {
            return ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.MALFORMED)
        }

        val issuer = content.readExact(entry.offset + signedDataOffset, 0x40)
            ?: return ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.IO_RETRYABLE)
        if (!validIssuer(issuer)) {
            return ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.MALFORMED)
        }
        val versionAndType = content.readExact(
            entry.offset + signedDataOffset + TICKET_FORMAT_VERSION_OFFSET,
            2,
        ) ?: return ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.IO_RETRYABLE)
        if ((versionAndType[0].toInt() and 0xff) != 2 || (versionAndType[1].toInt() and 0xff) !in 0..1) {
            return ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.MALFORMED)
        }
        val rightsId = content.readExact(
            entry.offset + signedDataOffset + TICKET_RIGHTS_ID_OFFSET,
            RIGHTS_ID_BYTES,
        ) ?: return ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.IO_RETRYABLE)
        if (rightsId.all { it == 0.toByte() }) {
            return ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.MALFORMED)
        }
        val rightsHex = rightsId.toUpperHex()
        val ticketStem = entry.name.substringBeforeLast('.')
        if (!RIGHTS_ID_NAME.matches(ticketStem) || !ticketStem.equals(rightsHex, ignoreCase = true)) {
            return ContainerIdentityResult.NeedsReview(ContainerIdentityReason.AMBIGUOUS)
        }
        val rawTitleId = rightsHex.take(16)
        if (!TITLE_ID.matches(rawTitleId) || rawTitleId == "0000000000000000") {
            return ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.MALFORMED)
        }
        val role = when (rawTitleId.takeLast(3)) {
            "000" -> SwitchPackageRole.BASE
            "800" -> SwitchPackageRole.UPDATE
            else -> SwitchPackageRole.DLC
        }
        return ContainerIdentityResult.Identified(
            SwitchPayloadIdentity(
                rawTitleId = rawTitleId,
                baseTitleId = rawTitleId.take(13) + "000",
                role = role,
            ),
        )
    }

    private fun validIssuer(bytes: ByteArray): Boolean {
        val end = bytes.indexOf(0)
        if (end !in 1 until bytes.size) return false
        if (bytes.drop(end).any { it != 0.toByte() }) return false
        val issuer = String(bytes, 0, end, Charsets.US_ASCII)
        return issuer.startsWith("Root-CA") && issuer.all { it.code in 0x20..0x7e }
    }

    private fun ByteArray.strictAsciiName(at: Int): String? {
        if (at !in indices) return null
        val limit = (at + MAX_ENTRY_NAME_BYTES + 1).coerceAtMost(size)
        var end = at
        while (end < limit && this[end] != 0.toByte()) {
            val value = this[end].toInt() and 0xff
            if (value !in 0x21..0x7e) return null
            end++
        }
        if (end == at || end >= limit || this[end] != 0.toByte()) return null
        val name = String(this, at, end - at, Charsets.US_ASCII)
        if (
            name.startsWith('/') || name.startsWith('\\') || '/' in name || '\\' in name ||
            name == "." || name == ".."
        ) {
            return null
        }
        return name
    }

    private fun ByteArray.littleU32(at: Int): Int? {
        if (at < 0 || at + 4 > size) return null
        val value = (this[at].toLong() and 0xff) or
            ((this[at + 1].toLong() and 0xff) shl 8) or
            ((this[at + 2].toLong() and 0xff) shl 16) or
            ((this[at + 3].toLong() and 0xff) shl 24)
        return value.takeIf { it <= Int.MAX_VALUE }?.toInt()
    }

    private fun ByteArray.littleU64(at: Int): Long? {
        if (at < 0 || at + 8 > size || (this[at + 7].toInt() and 0x80) != 0) return null
        var value = 0L
        for (index in 7 downTo 0) {
            value = (value shl 8) or (this[at + index].toLong() and 0xff)
        }
        return value
    }

    private fun checkedAdd(left: Long, right: Long): Long? {
        if (left < 0L || right < 0L) return null
        val result = left + right
        return result.takeIf { it >= left }
    }

    private fun ByteArray.toUpperHex(): String = joinToString(separator = "") {
        "%02X".format(Locale.ROOT, it.toInt() and 0xff)
    }

    private data class Pfs0Entry(
        val name: String,
        val offset: Long,
        val size: Long,
        val end: Long,
    )

    private val PFS0_MAGIC = byteArrayOf('P'.code.toByte(), 'F'.code.toByte(), 'S'.code.toByte(), '0'.code.toByte())
    private val RIGHTS_ID_NAME = Regex("[0-9a-fA-F]{32}")
    private val TITLE_ID = Regex("[0-9A-F]{16}")

    // Ticket signature type is little-endian. The section includes type, signature and fixed alignment.
    private val SIGNED_DATA_OFFSETS = mapOf(
        0x0001_0000 to 0x240L, // RSA-4096 / SHA-1
        0x0001_0001 to 0x140L, // RSA-2048 / SHA-1
        0x0001_0002 to 0x080L, // ECDSA / SHA-1
        0x0001_0003 to 0x240L, // RSA-4096 / SHA-256
        0x0001_0004 to 0x140L, // RSA-2048 / SHA-256
        0x0001_0005 to 0x080L, // ECDSA / SHA-256
        0x0001_0006 to 0x040L, // HMAC-SHA1-160
    )
}
