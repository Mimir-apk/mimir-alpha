package com.gruning.mimir.rewrite

private val MimirHexDigits = "0123456789abcdef".toCharArray()

/**
 * Lowercase hex for a digest, without `String.format`.
 *
 * `digest.joinToString("") { "%02x".format(it) }` builds and parses a whole `java.util.Formatter`
 * per BYTE — 32 of them for one SHA-256 key. That is fine on a cold path and it is not fine here:
 * these keys are built **per game, during composition**.
 *
 * Measured on the Thor 2026-09-04 with a UI-thread sampling profiler, eight folio opens:
 * `joinToString { "%02x".format(it) }` is **145.6 ms of the 346 ms of composition (42 %)**, of which
 * 123.5 ms is inside `java.util.Formatter` — and **100 % of it sits inside
 * `rememberHomeShelfCoordinator`**, whose per-open cost round 3 had measured but not explained.
 * SHA-256 itself is 7 %.
 *
 * **Byte-identical to the expression it replaces, and that is load-bearing** — both callers are frozen
 * derivations that address every art file already on the reference device. `MimirHexDigestTest` pins it against
 * the original `"%02x".format` for all 256 byte values and against hashes computed outside the JVM.
 */
internal fun ByteArray.toMimirLowerHex(): String {
    val out = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xff
        out[i * 2] = MimirHexDigits[v ushr 4]
        out[i * 2 + 1] = MimirHexDigits[v and 0x0f]
    }
    return String(out)
}
