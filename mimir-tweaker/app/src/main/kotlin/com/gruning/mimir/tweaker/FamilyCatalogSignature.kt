package com.gruning.mimir.tweaker

import com.google.crypto.tink.subtle.Ed25519Verify
import java.util.Base64

/**
 * The one signature check the live family catalog goes through (design D2, "Signed: yes").
 *
 * A catalog entry carries an admission authority (`profileTreatment`), so the downloaded bytes are trusted only
 * when an Ed25519 detached signature over their exact bytes verifies against the public key compiled into this
 * APK. The same Tink `Ed25519Verify` as the qualification manifest (`QualificationManifestVerifier`) does the
 * arithmetic; this object adds only the wire decoding and the fail-closed length checks around it.
 *
 * Formats (fixed, shared with the host-side signing tool):
 * - public key: base64 of the raw 32-byte Ed25519 public key (`BuildConfig.FAMILY_CATALOG_PUBLIC_KEY_BASE64`);
 * - `.sig` file: one line of base64 of the raw 64-byte signature over the catalog file's exact bytes.
 *
 * Every function here is total: a bad key, a bad signature file or a verifier exception is a `null`/`false`,
 * never a throw, and never "assume valid".
 */
internal object FamilyCatalogSignature {
    const val PUBLIC_KEY_BYTES = 32
    const val SIGNATURE_BYTES = 64

    /** Largest `.sig` body worth reading: 88 base64 characters plus a line ending, with slack. */
    const val MAX_SIGNATURE_FILE_BYTES = 256

    /**
     * The raw 32-byte key for a compiled-in base64 string, or `null` when the string is blank (the "no key yet,
     * live fetch disabled" state) or is not exactly a base64 32-byte value.
     */
    fun decodePublicKey(base64: String): ByteArray? {
        val text = base64.trim()
        if (text.isEmpty()) return null
        val raw = decodeStrictBase64(text) ?: return null
        return raw.takeIf { it.size == PUBLIC_KEY_BYTES && !isSmallOrderPoint(it) }
    }

    /**
     * The raw 64-byte signature held by a `.sig` file's bytes, or `null` for anything else. Only the line ending
     * (surrounding whitespace) is tolerated; the base64 itself must be a single standard-alphabet token.
     */
    fun decodeSignatureFile(fileBytes: ByteArray): ByteArray? {
        if (fileBytes.isEmpty() || fileBytes.size > MAX_SIGNATURE_FILE_BYTES) return null
        // ISO_8859_1 maps every byte to one char, so a stray non-ASCII byte simply fails the base64 decode.
        val text = String(fileBytes, Charsets.ISO_8859_1).trim()
        if (text.isEmpty()) return null
        val raw = decodeStrictBase64(text) ?: return null
        return raw.takeIf { it.size == SIGNATURE_BYTES }
    }

    /**
     * True only when [signature] is a valid Ed25519 signature of exactly [catalogBytes] under [publicKey].
     * A wrong-length key or signature, or any verifier exception, is `false`.
     */
    fun verify(publicKey: ByteArray, catalogBytes: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != PUBLIC_KEY_BYTES || signature.size != SIGNATURE_BYTES) return false
        if (isSmallOrderPoint(publicKey)) return false
        return try {
            Ed25519Verify(publicKey).verify(signature, catalogBytes)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * True for the encodings of Ed25519's small-order points (including the non-canonical aliases of the identity
     * and of the order-4 point). A signature "by" such a key can be forged without any secret for a fraction of all
     * messages, so a placeholder key like 32 zero bytes must never count as a trust anchor. Compares the encoded
     * y coordinate with the sign bit cleared, which covers both x signs.
     */
    internal fun isSmallOrderPoint(publicKey: ByteArray): Boolean {
        if (publicKey.size != PUBLIC_KEY_BYTES) return false
        val y = publicKey.copyOf()
        y[PUBLIC_KEY_BYTES - 1] = (y[PUBLIC_KEY_BYTES - 1].toInt() and 0x7f).toByte()
        return SMALL_ORDER_Y.any { it.contentEquals(y) }
    }

    private fun leBytes(hex: String): ByteArray = ByteArray(PUBLIC_KEY_BYTES) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** y = 0, 1, p-1, p, p+1 (p = 2^255 - 19) and the two order-8 y values, little-endian, sign bit clear. */
    private val SMALL_ORDER_Y: List<ByteArray> = listOf(
        "00".repeat(32),
        "01" + "00".repeat(31),
        "ec" + "ff".repeat(30) + "7f",
        "ed" + "ff".repeat(30) + "7f",
        "ee" + "ff".repeat(30) + "7f",
        "c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac037a",
        "26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc05",
    ).map(::leBytes)

    private fun decodeStrictBase64(text: String): ByteArray? = try {
        Base64.getDecoder().decode(text)
    } catch (_: IllegalArgumentException) {
        null
    }
}
