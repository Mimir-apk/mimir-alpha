package com.gruning.mimir.rewrite.core.launch.remotedefs

/**
 * A deliberately small, strict JSON reader and canonical writer.
 *
 * Why not a library: the usual ones keep the LAST duplicate key, accept a BOM, accept NaN, have no depth cap
 * and decode non-ASCII freely. Each of those is a way for two different byte strings to mean different things
 * to a reviewer and to the app. This reader takes bytes directly and refuses all of them.
 *
 * What it accepts: one top-level object; objects, arrays, strings, integers (up to 15 digits), true, false, null.
 * What it refuses, each with its own [RejectCode]: a BOM, a non-`{` start, a duplicate key, depth over
 * [RemoteDefinitionsLimits.MAX_DEPTH], more than [RemoteDefinitionsLimits.MAX_KEYS_PER_OBJECT] keys, arrays over
 * [RemoteDefinitionsLimits.MAX_ROWS], strings over [RemoteDefinitionsLimits.MAX_STRING_BYTES], any non-printable or
 * non-ASCII character (raw or escaped), fractions, exponents, NaN, Infinity, leading zeros and trailing bytes.
 */
internal object StrictJson {
    fun parse(bytes: ByteArray): Any? {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            throw OverlayRejected(RejectCode.BOM, "UTF-8 byte order mark")
        }
        val reader = Reader(bytes)
        reader.skipWhitespace()
        if (reader.atEnd || reader.peek() != '{'.code) {
            throw OverlayRejected(RejectCode.NOT_JSON, "does not start with an object")
        }
        val value = reader.readValue(depth = 0)
        reader.skipWhitespace()
        if (!reader.atEnd) throw OverlayRejected(RejectCode.PARSE, "bytes after the top-level value")
        return value
    }

    /**
     * Compact, sorted-key rendering of an already parsed tree. Strings in a tree this reader produced are printable
     * ASCII, so the only escapes ever needed are the quote and the backslash; the control branch is defensive.
     */
    fun canonical(value: Any?): String = StringBuilder().also { writeCanonical(value, it) }.toString()

    private fun writeCanonical(value: Any?, out: StringBuilder) {
        when (value) {
            null -> out.append("null")
            is Boolean -> out.append(if (value) "true" else "false")
            is Long -> out.append(value)
            is String -> writeString(value, out)
            is List<*> -> {
                out.append('[')
                value.forEachIndexed { i, item ->
                    if (i > 0) out.append(',')
                    writeCanonical(item, out)
                }
                out.append(']')
            }
            is Map<*, *> -> {
                out.append('{')
                value.keys.map { it as String }.sorted().forEachIndexed { i, key ->
                    if (i > 0) out.append(',')
                    writeString(key, out)
                    out.append(':')
                    writeCanonical(value[key], out)
                }
                out.append('}')
            }
            else -> throw IllegalArgumentException("not a JSON tree value: ${value::class}")
        }
    }

    private fun writeString(s: String, out: StringBuilder) {
        out.append('"')
        for (c in s) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c.code < 0x20 -> out.append("\\u").append("%04x".format(c.code))
                else -> out.append(c)
            }
        }
        out.append('"')
    }

    private class Reader(private val b: ByteArray) {
        private var pos = 0
        val atEnd: Boolean get() = pos >= b.size

        fun peek(): Int = if (pos < b.size) (b[pos].toInt() and 0xFF) else -1

        fun skipWhitespace() {
            while (pos < b.size) {
                val c = b[pos].toInt()
                if (c == 0x20 || c == 0x09 || c == 0x0A || c == 0x0D) pos++ else break
            }
        }

        fun readValue(depth: Int): Any? {
            skipWhitespace()
            return when (val c = peek()) {
                '{'.code -> readObject(depth + 1)
                '['.code -> readArray(depth + 1)
                '"'.code -> readString()
                't'.code -> readLiteral("true", true)
                'f'.code -> readLiteral("false", false)
                'n'.code -> readLiteral("null", null)
                '-'.code, in '0'.code..'9'.code -> readInteger()
                -1 -> throw OverlayRejected(RejectCode.PARSE, "unexpected end of input")
                else -> throw OverlayRejected(RejectCode.PARSE, "unexpected byte 0x${c.toString(16)}")
            }
        }

        private fun readLiteral(word: String, value: Any?): Any? {
            for (ch in word) {
                if (peek() != ch.code) throw OverlayRejected(RejectCode.PARSE, "bad literal")
                pos++
            }
            return value
        }

        private fun readInteger(): Long {
            val start = pos
            if (peek() == '-'.code) pos++
            val digitsStart = pos
            when (peek()) {
                '0'.code -> pos++
                in '1'.code..'9'.code -> while (peek() in '0'.code..'9'.code) pos++
                else -> throw OverlayRejected(RejectCode.PARSE, "bad number")
            }
            val digits = pos - digitsStart
            val next = peek()
            if (next == '.'.code || next == 'e'.code || next == 'E'.code) {
                throw OverlayRejected(RejectCode.PARSE, "only integers are allowed")
            }
            if (next in '0'.code..'9'.code) throw OverlayRejected(RejectCode.PARSE, "leading zero")
            if (digits > 15) throw OverlayRejected(RejectCode.PARSE, "integer too long")
            return String(b, start, pos - start, Charsets.US_ASCII).toLong()
        }

        private fun readString(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                val c = peek()
                if (c == -1) throw OverlayRejected(RejectCode.PARSE, "unterminated string")
                pos++
                when {
                    c == '"'.code -> return sb.toString()
                    c == '\\'.code -> sb.append(readEscape())
                    c < 0x20 || c >= 0x7F -> throw OverlayRejected(RejectCode.BAD_STRING, "raw control or non-ASCII byte")
                    else -> sb.append(c.toChar())
                }
                if (sb.length > RemoteDefinitionsLimits.MAX_STRING_BYTES) {
                    throw OverlayRejected(RejectCode.STRING_TOO_LONG, "string over ${RemoteDefinitionsLimits.MAX_STRING_BYTES}")
                }
            }
        }

        private fun readEscape(): Char {
            val e = peek()
            if (e == -1) throw OverlayRejected(RejectCode.PARSE, "unterminated escape")
            pos++
            val decoded: Int = when (e) {
                '"'.code -> '"'.code
                '\\'.code -> '\\'.code
                '/'.code -> '/'.code
                'b'.code -> 0x08
                'f'.code -> 0x0C
                'n'.code -> 0x0A
                'r'.code -> 0x0D
                't'.code -> 0x09
                'u'.code -> {
                    var v = 0
                    repeat(4) {
                        val h = peek()
                        val digit = when (h) {
                            in '0'.code..'9'.code -> h - '0'.code
                            in 'a'.code..'f'.code -> h - 'a'.code + 10
                            in 'A'.code..'F'.code -> h - 'A'.code + 10
                            else -> throw OverlayRejected(RejectCode.PARSE, "bad unicode escape")
                        }
                        v = v * 16 + digit
                        pos++
                    }
                    v
                }
                else -> throw OverlayRejected(RejectCode.PARSE, "bad escape")
            }
            // Escapes may only spell printable ASCII, so an escaped newline or NUL is refused like a raw one.
            if (decoded < 0x20 || decoded >= 0x7F) throw OverlayRejected(RejectCode.BAD_STRING, "escaped control or non-ASCII")
            return decoded.toChar()
        }

        private fun readArray(depth: Int): List<Any?> {
            if (depth > RemoteDefinitionsLimits.MAX_DEPTH) throw OverlayRejected(RejectCode.TOO_DEEP, "depth $depth")
            pos++ // [
            val items = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']'.code) {
                pos++
                return items
            }
            while (true) {
                items += readValue(depth)
                if (items.size > RemoteDefinitionsLimits.MAX_ROWS) {
                    throw OverlayRejected(RejectCode.TOO_MANY_ROWS, "array over ${RemoteDefinitionsLimits.MAX_ROWS}")
                }
                skipWhitespace()
                when (peek()) {
                    ','.code -> pos++
                    ']'.code -> {
                        pos++
                        return items
                    }
                    else -> throw OverlayRejected(RejectCode.PARSE, "expected , or ] in array")
                }
            }
        }

        private fun readObject(depth: Int): Map<String, Any?> {
            if (depth > RemoteDefinitionsLimits.MAX_DEPTH) throw OverlayRejected(RejectCode.TOO_DEEP, "depth $depth")
            pos++ // {
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}'.code) {
                pos++
                return map
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"'.code) throw OverlayRejected(RejectCode.PARSE, "object key must be a string")
                val key = readString()
                skipWhitespace()
                if (peek() != ':'.code) throw OverlayRejected(RejectCode.PARSE, "expected : after key")
                pos++
                val value = readValue(depth)
                if (map.containsKey(key)) throw OverlayRejected(RejectCode.DUPLICATE_KEY, "duplicate key")
                map[key] = value
                if (map.size > RemoteDefinitionsLimits.MAX_KEYS_PER_OBJECT) {
                    throw OverlayRejected(RejectCode.PARSE, "too many keys in one object")
                }
                skipWhitespace()
                when (peek()) {
                    ','.code -> pos++
                    '}'.code -> {
                        pos++
                        return map
                    }
                    else -> throw OverlayRejected(RejectCode.PARSE, "expected , or } in object")
                }
            }
        }
    }
}
