package com.gruning.mimir.rewrite.core.metadata.online

import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Process-cacheable result of the undocumented, keyless RA hash-library endpoint. */
internal sealed interface RaHashLibraryResult {
    data class Loaded(val gamesByHash: PackedMd5GameIndex) : RaHashLibraryResult
    data object Unavailable : RaHashLibraryResult
}

/**
 * A sorted, packed MD5 → RA game-id index. Each hash occupies two primitive [Long]s and one parallel [Int]
 * rather than retaining 53,000 JSON strings and map nodes. The provider already sends the game id; retaining
 * it lets an incomplete achievement-bearing index recover the exact identity without guessing.
 */
internal class PackedMd5GameIndex internal constructor(
    private val words: LongArray,
    private val gameIds: IntArray,
) {
    val size: Int get() = words.size / WORDS_PER_HASH
    internal val packedBytes: Int get() = words.size * Long.SIZE_BYTES + gameIds.size * Int.SIZE_BYTES

    operator fun get(md5: String): Int? {
        if (md5.length != MD5_LENGTH) return null
        val high = parseHexWord(md5, 0) ?: return null
        val low = parseHexWord(md5, HEX_WORD_LENGTH) ?: return null

        var left = 0
        var right = size - 1
        while (left <= right) {
            val middle = (left + right) ushr 1
            when (comparePair(words, middle, high, low)) {
                0 -> return gameIds[middle]
                in Int.MIN_VALUE until 0 -> left = middle + 1
                else -> right = middle - 1
            }
        }
        return null
    }

    operator fun contains(md5: String): Boolean = get(md5) != null
}

/**
 * Streams the exact `{ "Success": true, "MD5List": { md5: gameId } }` shape into a packed game index.
 * The parser is intentionally strict: any shape drift returns null so the caller preserves NotMatched rather
 * than fabricating RecognizedNoSet from data it no longer understands.
 */
internal fun parsePackedHashLibrary(reader: Reader): PackedMd5GameIndex? = try {
    HashLibraryJsonParser(reader).parse()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: InvalidHashLibrary) {
    null
} catch (_: Exception) {
    null
}

/** The one streaming socket for the keyless fallback. Every refusal/shape failure is deliberately silent. */
internal suspend fun defaultRetroAchievementsHashLibraryGet(): RaHashLibraryResult =
    withContext(Dispatchers.IO) {
        var current = RetroAchievementsApi.hashLibraryUrl()
        var redirects = 0
        try {
            while (redirects <= RA_MAX_REDIRECTS) {
                if (!retroAchievementsHashLibraryUrlAllowed(current)) {
                    return@withContext RaHashLibraryResult.Unavailable
                }
                val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 20_000
                    requestMethod = "GET"
                    instanceFollowRedirects = false
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", "Mimir/0.1 (Android)")
                }
                try {
                    val code = connection.responseCode
                    if (code in RetroAchievementsRedirectStatusCodes) {
                        if (redirects == RA_MAX_REDIRECTS) {
                            return@withContext RaHashLibraryResult.Unavailable
                        }
                        current = retroAchievementsHashLibraryRedirectTarget(
                            currentUrl = current,
                            location = connection.getHeaderField("Location"),
                        ) ?: return@withContext RaHashLibraryResult.Unavailable
                        redirects++
                        continue
                    }
                    if (code !in 200..299 ||
                        !retroAchievementsContentLengthAllowed(
                            connection.contentLengthLong,
                            RA_MAX_HASH_LIBRARY_RESPONSE_BYTES,
                        )
                    ) {
                        return@withContext RaHashLibraryResult.Unavailable
                    }
                    val hashes = InputStreamReader(
                        RetroAchievementsBoundedInputStream(
                            connection.inputStream,
                            RA_MAX_HASH_LIBRARY_RESPONSE_BYTES,
                        ),
                        Charsets.UTF_8,
                    ).use(::parsePackedHashLibrary)
                        ?: return@withContext RaHashLibraryResult.Unavailable
                    return@withContext RaHashLibraryResult.Loaded(hashes)
                } finally {
                    connection.disconnect()
                }
            }
            RaHashLibraryResult.Unavailable
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            RaHashLibraryResult.Unavailable
        }
    }

/**
 * A streaming byte ceiling. The EOF probe after exactly [maxBytes] is load-bearing: a body whose valid JSON
 * prefix ends on the limit must still be rejected when the provider appends even one unbounded byte.
 */
internal class RetroAchievementsBoundedInputStream(
    private val source: InputStream,
    private val maxBytes: Long,
) : InputStream() {
    private var remaining = maxBytes

    init {
        require(maxBytes > 0L)
    }

    override fun read(): Int {
        if (remaining == 0L) return requireEndOfInput()
        val value = source.read()
        if (value >= 0) remaining--
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return requireEndOfInput()
        val boundedLength = minOf(length.toLong(), remaining).toInt()
        val read = source.read(buffer, offset, boundedLength)
        if (read > 0) remaining -= read
        return read
    }

    override fun close() = source.close()

    private fun requireEndOfInput(): Int =
        if (source.read() == -1) {
            -1
        } else {
            throw IOException("RetroAchievements response exceeded its byte limit")
        }
}

private class HashLibraryJsonParser(
    private val reader: Reader,
) {
    private var current: Int = reader.read()

    fun parse(): PackedMd5GameIndex {
        expect('{')
        var success: Boolean? = null
        var hashes: PackedMd5GameIndex? = null

        while (true) {
            skipWhitespace()
            if (consume('}')) break
            val field = readString()
            expect(':')
            when (field) {
                "Success" -> {
                    if (success != null) invalid()
                    success = readBoolean()
                }
                "MD5List" -> {
                    if (hashes != null) invalid()
                    hashes = readMd5List()
                }
                else -> invalid()
            }
            skipWhitespace()
            if (consume(',')) continue
            expect('}')
            break
        }

        skipWhitespace()
        if (current != -1 || success != true || hashes == null || hashes.size == 0) invalid()
        return hashes
    }

    private fun readMd5List(): PackedMd5GameIndex {
        expect('{')
        val builder = PackedMd5Builder()
        while (true) {
            skipWhitespace()
            if (consume('}')) break
            val hash = readString()
            expect(':')
            val gameId = readUnsignedInteger()
            if (!builder.add(hash, gameId)) invalid()
            skipWhitespace()
            if (consume(',')) continue
            expect('}')
            break
        }
        return builder.build()
    }

    private fun readBoolean(): Boolean {
        skipWhitespace()
        return when (current) {
            't'.code -> {
                expectLiteral("true")
                true
            }
            'f'.code -> {
                expectLiteral("false")
                false
            }
            else -> invalid()
        }
    }

    private fun readUnsignedInteger(): Int {
        skipWhitespace()
        if (current !in '0'.code..'9'.code) invalid()
        var digits = 0
        var value = 0L
        while (current in '0'.code..'9'.code) {
            if (++digits > HASH_LIBRARY_MAX_NUMBER_DIGITS) invalid()
            value = value * 10L + (current - '0'.code)
            if (value > Int.MAX_VALUE) invalid()
            advance()
        }
        if (value <= 0L) invalid()
        return value.toInt()
    }

    private fun readString(): String {
        skipWhitespace()
        expectRaw('"')
        val out = StringBuilder()
        while (current != '"'.code) {
            if (current == -1 || current < 0x20) invalid()
            if (current == '\\'.code) {
                advance()
                out.append(
                    when (current) {
                        '"'.code -> '"'
                        '\\'.code -> '\\'
                        '/'.code -> '/'
                        'b'.code -> '\b'
                        'f'.code -> '\u000C'
                        'n'.code -> '\n'
                        'r'.code -> '\r'
                        't'.code -> '\t'
                        'u'.code -> readUnicodeEscape()
                        else -> invalid()
                    },
                )
                advance()
            } else {
                out.append(current.toChar())
                advance()
            }
            if (out.length > HASH_LIBRARY_MAX_STRING_CHARS) invalid()
        }
        advance()
        return out.toString()
    }

    private fun readUnicodeEscape(): Char {
        var value = 0
        repeat(4) {
            advance()
            val digit = hexDigit(current) ?: invalid()
            value = (value shl 4) or digit
        }
        return value.toChar()
    }

    private fun expectLiteral(value: String) {
        value.forEach { expected ->
            if (current != expected.code) invalid()
            advance()
        }
    }

    private fun expect(expected: Char) {
        skipWhitespace()
        expectRaw(expected)
    }

    private fun expectRaw(expected: Char) {
        if (current != expected.code) invalid()
        advance()
    }

    private fun consume(expected: Char): Boolean {
        if (current != expected.code) return false
        advance()
        return true
    }

    private fun skipWhitespace() {
        while (current == ' '.code || current == '\n'.code || current == '\r'.code || current == '\t'.code) {
            advance()
        }
    }

    private fun advance() {
        current = reader.read()
    }
}

private class PackedMd5Builder {
    private var words = LongArray(2048)
    private var gameIds = IntArray(1024)
    private var size = 0

    fun add(md5: String, gameId: Int): Boolean {
        if (size >= HASH_LIBRARY_MAX_ENTRIES) return false
        if (gameId <= 0) return false
        if (md5.length != MD5_LENGTH) return false
        val high = parseHexWord(md5, 0) ?: return false
        val low = parseHexWord(md5, HEX_WORD_LENGTH) ?: return false
        val required = (size + 1) * WORDS_PER_HASH
        if (required > words.size) words = words.copyOf(words.size * 2)
        if (size == gameIds.size) gameIds = gameIds.copyOf(gameIds.size * 2)
        words[size * WORDS_PER_HASH] = high
        words[size * WORDS_PER_HASH + 1] = low
        gameIds[size] = gameId
        size++
        return true
    }

    fun build(): PackedMd5GameIndex {
        val packed = words.copyOf(size * WORDS_PER_HASH)
        val packedGameIds = gameIds.copyOf(size)
        if (size > 1) quickSortPairs(packed, packedGameIds, 0, size - 1)
        for (index in 1 until size) {
            val at = index * WORDS_PER_HASH
            if (comparePair(packed, index - 1, packed[at], packed[at + 1]) == 0) invalid()
        }
        return PackedMd5GameIndex(packed, packedGameIds)
    }
}

private fun quickSortPairs(words: LongArray, gameIds: IntArray, low: Int, high: Int) {
    var left = low
    var right = high
    val pivot = ((low + high) ushr 1) * WORDS_PER_HASH
    val pivotHigh = words[pivot]
    val pivotLow = words[pivot + 1]

    while (left <= right) {
        while (comparePair(words, left, pivotHigh, pivotLow) < 0) left++
        while (comparePair(words, right, pivotHigh, pivotLow) > 0) right--
        if (left <= right) {
            swapPairs(words, gameIds, left, right)
            left++
            right--
        }
    }
    if (low < right) quickSortPairs(words, gameIds, low, right)
    if (left < high) quickSortPairs(words, gameIds, left, high)
}

private fun comparePair(words: LongArray, index: Int, high: Long, low: Long): Int {
    val at = index * WORDS_PER_HASH
    val highResult = java.lang.Long.compareUnsigned(words[at], high)
    return if (highResult != 0) highResult else java.lang.Long.compareUnsigned(words[at + 1], low)
}

private fun swapPairs(words: LongArray, gameIds: IntArray, first: Int, second: Int) {
    if (first == second) return
    val firstAt = first * WORDS_PER_HASH
    val secondAt = second * WORDS_PER_HASH
    val high = words[firstAt]
    val low = words[firstAt + 1]
    words[firstAt] = words[secondAt]
    words[firstAt + 1] = words[secondAt + 1]
    words[secondAt] = high
    words[secondAt + 1] = low
    val gameId = gameIds[first]
    gameIds[first] = gameIds[second]
    gameIds[second] = gameId
}

private fun parseHexWord(value: String, start: Int): Long? {
    var word = 0L
    for (index in start until start + HEX_WORD_LENGTH) {
        val digit = hexDigit(value[index].code) ?: return null
        word = (word shl 4) or digit.toLong()
    }
    return word
}

private fun hexDigit(value: Int): Int? = when (value) {
    in '0'.code..'9'.code -> value - '0'.code
    in 'a'.code..'f'.code -> value - 'a'.code + 10
    in 'A'.code..'F'.code -> value - 'A'.code + 10
    else -> null
}

private fun invalid(): Nothing = throw InvalidHashLibrary()

private class InvalidHashLibrary : Exception() {
    override fun fillInStackTrace(): Throwable = this
}

private const val MD5_LENGTH = 32
private const val HEX_WORD_LENGTH = 16
private const val WORDS_PER_HASH = 2

/** Measured live population is 53,555; this leaves ample growth while bounding adversarial allocation. */
internal const val HASH_LIBRARY_MAX_ENTRIES = 100_000
private const val HASH_LIBRARY_MAX_STRING_CHARS = 256
private const val HASH_LIBRARY_MAX_NUMBER_DIGITS = 10
