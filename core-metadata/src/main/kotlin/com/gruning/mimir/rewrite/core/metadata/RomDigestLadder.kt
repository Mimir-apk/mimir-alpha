package com.gruning.mimir.rewrite.core.metadata

import java.io.InputStream
import java.security.MessageDigest

/**
 * One digest to compute over a ROM stream: an algorithm, a number of leading bytes to drop, and an
 * optional byte-order swap.
 *
 * [skipBytes] models the non-canonical headers dump tools prepend (iNES 16, SNES/PC-Engine copier 512,
 * Lynx 64, 7800 128). [swapUnit] models N64's three interchangeable byte orders: 0 = feed bytes as they
 * are (`.z64`, already big-endian), 2 = reverse each 16-bit pair (`.v64`), 4 = reverse each 32-bit word
 * (`.n64`). Combining a non-zero [skipBytes] with a non-zero [swapUnit] is not used by any rule here and
 * is not supported — the swap would straddle the skip boundary and the alignment claim below would not
 * hold.
 */
internal data class DigestSpec(
    val label: String,
    val algorithm: String,
    val skipBytes: Int = 0,
    val swapUnit: Int = 0,
) {
    init {
        require(skipBytes >= 0) { "skipBytes must not be negative" }
        require(swapUnit == 0 || swapUnit == 2 || swapUnit == 4) { "swapUnit must be 0, 2 or 4" }
        require(skipBytes == 0 || swapUnit == 0) { "skip+swap combined is unsupported (see KDoc)" }
    }
}

/**
 * Computes N digests over a stream in **one read**.
 *
 * ## Why one read, and why this exists at all
 *
 * The identity lanes used to *decide* which single rule applied and then hash once under that rule — the
 * SNES copier header from the file's size, the N64 byte order from its extension. Deciding is the part
 * that was believed rather than verified, and it fails closed in the worst way: a wrong decision yields a
 * confident digest that simply never matches, indistinguishable from "the catalogue doesn't carry this".
 *
 * A ladder inverts that. Emit every candidate the format could plausibly produce and let the **index**
 * adjudicate, because a hash index is an oracle: a hit is definitional truth, and a wrong rule can only
 * ever produce a miss, never a wrong match. Precision is therefore free and only coverage is at stake.
 *
 * Doing that naively would re-read the file per candidate, and the read is the expensive half: a SAF read
 * of a 4 MB ROM costs ~100 ms against ~10 ms for the digest, so three candidates would be ~330 ms instead
 * of ~110 ms. Hence one pass, N [MessageDigest]s, each fed the same bytes under its own transform.
 *
 * ## What it removes
 *
 * The size-derived copier rule needed the ROM's true uncompressed size, which a zip written in streaming
 * mode does not carry (`ZipEntry.size == -1`), forcing an honest-but-lossy null. A ladder emits the raw
 * and the header-skipped candidate unconditionally and never consults a size, so that trap disappears
 * rather than being guarded against.
 *
 * Pure, streaming, fixed buffer — never loads a ROM into memory, so it is safe over SAF for large files.
 * Does NOT close [input]: on the zip path it is a shared `ZipInputStream` still walking its siblings.
 */
internal object RomDigestLadder {

    private const val BUFFER = 1 shl 16 // 64 KiB, matching FileHasher and RetroAchievementsHash

    /**
     * Runs [specs] over [input] in a single pass and returns label → lowercase hex digest.
     *
     * A spec whose [DigestSpec.skipBytes] exceeds the stream's length is omitted from the result rather
     * than reported as a digest of nothing: a file shorter than the header it would have to carry cannot
     * honestly produce that candidate. An empty [specs] yields an empty map without reading a byte.
     */
    fun digest(input: InputStream, specs: List<DigestSpec>): Map<String, String> {
        if (specs.isEmpty()) return emptyMap()
        val states = specs.map { RunningDigest(it) }
        val buffer = ByteArray(BUFFER)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            for (state in states) state.update(buffer, read)
        }
        return buildMap {
            for (state in states) state.finish()?.let { put(state.spec.label, it) }
        }
    }

    /**
     * One candidate's in-flight state: how many bytes it has skipped so far, and — for a swapping spec —
     * the up-to-`unit-1` byte tail left over when a chunk boundary fell mid-unit.
     *
     * The carry is what makes "swap in a streaming pass" correct. A 64 KiB buffer divides evenly by both
     * 2 and 4, so in practice the carry is only ever non-empty on a short read or the final chunk, but
     * relying on that would be relying on the shape of the reads rather than on the arithmetic.
     */
    private class RunningDigest(val spec: DigestSpec) {
        private val digest = MessageDigest.getInstance(spec.algorithm)
        private var toSkip = spec.skipBytes
        private var consumed = 0L
        private val carry = ByteArray(if (spec.swapUnit > 0) spec.swapUnit else 0)
        private var carryLen = 0

        fun update(chunk: ByteArray, length: Int) {
            var offset = 0
            var remaining = length
            if (toSkip > 0) {
                val drop = minOf(toSkip, remaining)
                toSkip -= drop
                offset += drop
                remaining -= drop
            }
            if (remaining <= 0) return
            consumed += remaining
            if (spec.swapUnit == 0) {
                digest.update(chunk, offset, remaining)
                return
            }
            val unit = spec.swapUnit
            // Top up a partial unit held over from the previous chunk before touching the new bytes.
            if (carryLen > 0) {
                val need = minOf(unit - carryLen, remaining)
                System.arraycopy(chunk, offset, carry, carryLen, need)
                carryLen += need
                offset += need
                remaining -= need
                if (carryLen < unit) return
                digest.update(reversed(carry, 0, unit))
                carryLen = 0
            }
            val whole = remaining - (remaining % unit)
            if (whole > 0) digest.update(reversed(chunk, offset, whole))
            val tail = remaining - whole
            if (tail > 0) {
                System.arraycopy(chunk, offset + whole, carry, 0, tail)
                carryLen = tail
            }
        }

        /**
         * The finished hex digest, or null when the stream never reached this candidate's header — a
         * file shorter than the bytes it claims to skip has no honest digest under that rule.
         *
         * A trailing partial unit is digested unswapped: reversing fewer bytes than a whole unit would
         * invent an ordering. No real cartridge dump ends mid-word, so this is an edge case, not a path.
         */
        fun finish(): String? {
            if (toSkip > 0) return null
            if (spec.skipBytes > 0 && consumed == 0L) return null
            if (carryLen > 0) digest.update(carry, 0, carryLen)
            return digest.digest().toHex()
        }

        private fun reversed(source: ByteArray, offset: Int, length: Int): ByteArray {
            val unit = spec.swapUnit
            val out = ByteArray(length)
            var base = 0
            while (base < length) {
                for (i in 0 until unit) out[base + i] = source[offset + base + unit - 1 - i]
                base += unit
            }
            return out
        }
    }

    private fun ByteArray.toHex(): String = buildString(size * 2) {
        for (b in this@toHex) append(HEX[(b.toInt() ushr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
