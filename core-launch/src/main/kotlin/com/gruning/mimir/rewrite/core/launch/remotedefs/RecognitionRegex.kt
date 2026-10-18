package com.gruning.mimir.rewrite.core.launch.remotedefs

/**
 * The only regex dialect a downloaded file may carry, matched against a lowercase FILE NAME.
 *
 * Restricted syntax AND Mimir's own matcher instead of a timeout, so the worst case is bounded by construction and no
 * thread or clock is involved (the test matrix pins that no decision reads time):
 *  - `^` first and `$` last, at most [RemoteDefinitionsLimits.MAX_REGEX_CHARS] characters;
 *  - atoms: a literal from `[a-z0-9_-]`, `\.`, `.`, a class `[...]` of literals and `a-z` style ranges;
 *  - `(?:a|b|c)` non-capturing groups of those atoms, not nested, and a group may only take a `?` after it;
 *  - one repeat per atom: `?` at most 4 times (4 groups, 16 alternatives each, at most), and `*` or `+` at most ONCE
 *    in the whole pattern, only straight after a single-character atom outside a group, and never `{`.
 *
 * The matcher is NOT `java.util.regex`. The platform engine backtracks, and on Android a `Matcher` reads its input
 * through `toString()` in native ICU, so a step counter on `charAt` (what stood here before) never sees the work. A
 * validated pattern is compiled to a small Thompson NFA ([Program]) and run as a set-of-states simulation: every
 * code point of the name makes at most one visit per instruction, so a match costs at most
 * `(length + 1) * 2 * instructions`, whatever the pattern and whatever the name. A name longer than [MAX_NAME_CHARS]
 * never matches. Compile and match never throw: a rule that cannot be compiled, or whose match fails for any reason,
 * matches nothing.
 */
internal object RecognitionRegex {
    /** The longest file name any rule is asked about, in UTF-16 units. A longer one matches nothing (Android's NAME_MAX is 255 bytes). */
    const val MAX_NAME_CHARS = 255
    private const val MAX_INSTRUCTIONS = 2_048
    private const val LITERALS = "abcdefghijklmnopqrstuvwxyz0123456789_-"
    private const val MAX_GROUPS = 4
    private const val MAX_ALTERNATIVES = 16
    private const val MAX_OPTIONALS = 4

    /** Null when [pattern] is inside the dialect, else the reason. */
    fun problem(pattern: String): String? {
        if (pattern.length > RemoteDefinitionsLimits.MAX_REGEX_CHARS) return "too long"
        if (pattern.length < 2 || pattern.first() != '^' || pattern.last() != '$') return "must start with ^ and end with $"
        val body = pattern.substring(1, pattern.length - 1)
        var i = 0
        var inGroup = false
        var groups = 0
        var alternatives = 0
        var optionals = 0
        var unbounded = 0
        var lastWasSingleAtomOutsideGroup = false
        var lastWasQuantifierOrOpener = true // forbids a repeat at the very start or right after ( or |
        while (i < body.length) {
            val c = body[i]
            when {
                c == '(' -> {
                    if (inGroup) return "nested group"
                    if (!body.startsWith("(?:", i)) return "only non-capturing groups"
                    groups++
                    if (groups > MAX_GROUPS) return "too many groups"
                    inGroup = true
                    alternatives = 1
                    i += 3
                    lastWasSingleAtomOutsideGroup = false
                    lastWasQuantifierOrOpener = true
                    continue
                }
                c == ')' -> {
                    if (!inGroup) return "unbalanced )"
                    inGroup = false
                    lastWasSingleAtomOutsideGroup = false
                    lastWasQuantifierOrOpener = false
                }
                c == '|' -> {
                    if (!inGroup) return "alternation outside a group"
                    alternatives++
                    if (alternatives > MAX_ALTERNATIVES) return "too many alternatives"
                    lastWasSingleAtomOutsideGroup = false
                    lastWasQuantifierOrOpener = true
                }
                c == '?' -> {
                    if (lastWasQuantifierOrOpener) return "nothing to repeat"
                    optionals++
                    if (optionals > MAX_OPTIONALS) return "too many optional atoms"
                    lastWasSingleAtomOutsideGroup = false
                    lastWasQuantifierOrOpener = true
                }
                c == '*' || c == '+' -> {
                    if (inGroup) return "unbounded repeat inside a group"
                    if (!lastWasSingleAtomOutsideGroup) return "unbounded repeat must follow a single atom"
                    unbounded++
                    if (unbounded > 1) return "more than one unbounded repeat"
                    lastWasSingleAtomOutsideGroup = false
                    lastWasQuantifierOrOpener = true
                }
                c == '{' || c == '}' -> return "counted repeats are not allowed"
                c == '\\' -> {
                    if (i + 1 >= body.length || body[i + 1] != '.') return "only a dot may be escaped"
                    i += 2
                    lastWasSingleAtomOutsideGroup = !inGroup
                    lastWasQuantifierOrOpener = false
                    continue
                }
                c == '.' -> {
                    lastWasSingleAtomOutsideGroup = !inGroup
                    lastWasQuantifierOrOpener = false
                }
                c == '[' -> {
                    val end = body.indexOf(']', i + 1)
                    if (end < 0) return "unterminated class"
                    classProblem(body.substring(i + 1, end))?.let { return it }
                    i = end + 1
                    lastWasSingleAtomOutsideGroup = !inGroup
                    lastWasQuantifierOrOpener = false
                    continue
                }
                c in LITERALS -> {
                    lastWasSingleAtomOutsideGroup = !inGroup
                    lastWasQuantifierOrOpener = false
                }
                else -> return "character not allowed"
            }
            i++
        }
        if (inGroup) return "unterminated group"
        return null
    }

    private fun classProblem(inner: String): String? {
        if (inner.isEmpty()) return "empty class"
        if (inner.length > 32) return "class too long"
        var i = 0
        while (i < inner.length) {
            val c = inner[i]
            if (c !in LITERALS && c != '.') return "character not allowed in class"
            if (i + 2 < inner.length && inner[i + 1] == '-' && inner[i + 2] != '-') {
                val hi = inner[i + 2]
                if (hi !in LITERALS || hi < c) return "bad range"
                i += 3
            } else {
                i++
            }
        }
        return null
    }

    /** A compiled, already validated rule. */
    class Compiled internal constructor(private val program: Program) {
        /** Does the whole of [fileName] (compared in lowercase) match? Never throws; a name past [MAX_NAME_CHARS] never matches. */
        fun matches(fileName: String): Boolean = run(fileName).matched

        /** The answer and how many instruction visits it cost; the cost has a fixed ceiling ([stepCeiling]). */
        internal fun run(fileName: String): Outcome = try {
            program.run(fileName)
        } catch (_: Exception) {
            Outcome(false, 0L)
        }

        /** The most visits any name of [length] characters can cost this rule. */
        internal fun stepCeiling(length: Int): Long = 2L * program.size * (length + 1)

        internal val instructionCount: Int get() = program.size
    }

    internal class Outcome(val matched: Boolean, val steps: Long)

    /** Null when [pattern] is outside the dialect, or when anything at all goes wrong building it. Never throws. */
    fun compile(pattern: String): Compiled? = try {
        if (problem(pattern) != null) null else Program.build(pattern.substring(1, pattern.length - 1))?.let(::Compiled)
    } catch (_: Exception) {
        null
    }

    // ---- the NFA ----------------------------------------------------------------------------------------------------

    /** The code points one position may hold: any non-line-break character, or a union of inclusive ranges. */
    internal class CharSet private constructor(private val any: Boolean, private val ranges: IntArray) {
        fun matches(codePoint: Int): Boolean {
            if (any) {
                // `.` as java.util.regex reads it without DOTALL: every code point except the line terminators.
                return codePoint != '\n'.code && codePoint != '\r'.code && codePoint != 0x85 &&
                    codePoint != 0x2028 && codePoint != 0x2029
            }
            var i = 0
            while (i < ranges.size) {
                if (codePoint >= ranges[i] && codePoint <= ranges[i + 1]) return true
                i += 2
            }
            return false
        }

        companion object {
            val AnyChar = CharSet(true, IntArray(0))

            fun of(ranges: List<IntArray>): CharSet = CharSet(false, IntArray(ranges.size * 2) { ranges[it / 2][it % 2] })

            fun single(c: Char): CharSet = of(listOf(intArrayOf(c.code, c.code)))
        }
    }

    private const val OP_CHAR = 0
    private const val OP_SPLIT = 1
    private const val OP_JMP = 2
    private const val OP_MATCH = 3

    /** One character position with its optional repeat (`?`, `*`, `+`). */
    private class Atom(val set: CharSet, val repeat: Char?)

    /** `(?:a|b)` with its optional `?`. */
    private class Group(val alternatives: List<List<Atom>>, val optional: Boolean)

    /**
     * The compiled NFA, as parallel arrays. [run] is the Thompson/Pike simulation without captures: a set of live
     * instructions advances one code point at a time, and an instruction is visited at most once per code point, so no
     * input can make the work superlinear.
     */
    internal class Program private constructor(
        private val op: IntArray,
        private val x: IntArray,
        private val y: IntArray,
        private val sets: Array<CharSet?>,
    ) {
        val size: Int get() = op.size

        fun run(fileName: String): Outcome {
            if (fileName.length > MAX_NAME_CHARS) return Outcome(false, 0L)
            val name = fileName.lowercase()
            if (name.length > MAX_NAME_CHARS) return Outcome(false, 0L)
            val n = op.size
            var current = IntArray(n)
            var next = IntArray(n)
            val mark = IntArray(n)
            val stack = IntArray(n)
            var generation = 1
            var steps = 0L

            // Adds the closure of [start] (following jumps and splits) to [list]; returns the new size.
            fun close(list: IntArray, size0: Int, start: Int): Int {
                var size = size0
                var top = 0
                if (mark[start] != generation) {
                    mark[start] = generation
                    stack[top++] = start
                }
                while (top > 0) {
                    val pc = stack[--top]
                    steps++
                    when (op[pc]) {
                        OP_JMP -> if (mark[x[pc]] != generation) {
                            mark[x[pc]] = generation
                            stack[top++] = x[pc]
                        }
                        OP_SPLIT -> {
                            if (mark[y[pc]] != generation) {
                                mark[y[pc]] = generation
                                stack[top++] = y[pc]
                            }
                            if (mark[x[pc]] != generation) {
                                mark[x[pc]] = generation
                                stack[top++] = x[pc]
                            }
                        }
                        else -> list[size++] = pc
                    }
                }
                return size
            }

            var currentSize = close(current, 0, 0)
            var i = 0
            while (i < name.length && currentSize > 0) {
                val codePoint = name.codePointAt(i)
                i += Character.charCount(codePoint)
                generation++
                var nextSize = 0
                for (k in 0 until currentSize) {
                    val pc = current[k]
                    steps++
                    if (op[pc] == OP_CHAR && sets[pc]!!.matches(codePoint)) nextSize = close(next, nextSize, pc + 1)
                }
                val swap = current
                current = next
                next = swap
                currentSize = nextSize
            }
            if (i < name.length) return Outcome(false, steps)
            for (k in 0 until currentSize) if (op[current[k]] == OP_MATCH) return Outcome(true, steps)
            return Outcome(false, steps)
        }

        companion object {
            /** [body] is the text between `^` and `$` of a pattern [problem] accepted. Null if it somehow cannot be read. */
            fun build(body: String): Program? {
                val nodes = parse(body) ?: return null
                val ops = ArrayList<Int>()
                val xs = ArrayList<Int>()
                val ys = ArrayList<Int>()
                val sets = ArrayList<CharSet?>()
                fun emit(opCode: Int, set: CharSet? = null): Int {
                    ops += opCode
                    xs += -1
                    ys += -1
                    sets += set
                    return ops.size - 1
                }
                fun emitAtom(atom: Atom) {
                    when (atom.repeat) {
                        null -> emit(OP_CHAR, atom.set)
                        '?' -> {
                            val split = emit(OP_SPLIT)
                            emit(OP_CHAR, atom.set)
                            xs[split] = split + 1
                            ys[split] = ops.size
                        }
                        '*' -> {
                            val split = emit(OP_SPLIT)
                            emit(OP_CHAR, atom.set)
                            val jump = emit(OP_JMP)
                            xs[jump] = split
                            xs[split] = split + 1
                            ys[split] = ops.size
                        }
                        else -> { // '+'
                            val char = emit(OP_CHAR, atom.set)
                            val split = emit(OP_SPLIT)
                            xs[split] = char
                            ys[split] = ops.size
                        }
                    }
                }
                for (node in nodes) {
                    if (ops.size > MAX_INSTRUCTIONS) return null
                    when (node) {
                        is Atom -> emitAtom(node)
                        is Group -> {
                            val skip = if (node.optional) emit(OP_SPLIT) else -1
                            if (skip >= 0) xs[skip] = skip + 1
                            val toEnd = ArrayList<Int>()
                            node.alternatives.forEachIndexed { index, alternative ->
                                val last = index == node.alternatives.size - 1
                                val split = if (last) -1 else emit(OP_SPLIT)
                                if (split >= 0) xs[split] = split + 1
                                alternative.forEach(::emitAtom)
                                if (!last) {
                                    toEnd += emit(OP_JMP)
                                    ys[split] = ops.size
                                }
                            }
                            toEnd.forEach { xs[it] = ops.size }
                            if (skip >= 0) ys[skip] = ops.size
                        }
                    }
                }
                emit(OP_MATCH)
                if (ops.size > MAX_INSTRUCTIONS) return null
                return Program(ops.toIntArray(), xs.toIntArray(), ys.toIntArray(), sets.toTypedArray())
            }

            private fun parse(body: String): List<Any>? {
                val nodes = ArrayList<Any>()
                var i = 0
                while (i < body.length) {
                    if (body.startsWith("(?:", i)) {
                        val close = body.indexOf(')', i + 3)
                        if (close < 0) return null
                        val alternatives = ArrayList<List<Atom>>()
                        for (part in body.substring(i + 3, close).split('|')) {
                            alternatives += parseSequence(part) ?: return null
                        }
                        i = close + 1
                        val optional = i < body.length && body[i] == '?'
                        if (optional) i++
                        nodes += Group(alternatives, optional)
                    } else {
                        val (set, after) = parseAtom(body, i) ?: return null
                        i = after
                        val repeat = body.getOrNull(i)?.takeIf { it == '?' || it == '*' || it == '+' }
                        if (repeat != null) i++
                        nodes += Atom(set, repeat)
                    }
                }
                return nodes
            }

            /** A group alternative: atoms, each with an optional `?`. No `*`, `+` or nested group inside a group (the validator's rule). */
            private fun parseSequence(text: String): List<Atom>? {
                val atoms = ArrayList<Atom>()
                var i = 0
                while (i < text.length) {
                    val (set, after) = parseAtom(text, i) ?: return null
                    i = after
                    val repeat = if (i < text.length && text[i] == '?') '?' else null
                    if (repeat != null) i++
                    atoms += Atom(set, repeat)
                }
                return atoms
            }

            private fun parseAtom(text: String, at: Int): Pair<CharSet, Int>? = when (val c = text[at]) {
                '\\' -> if (at + 1 < text.length && text[at + 1] == '.') CharSet.single('.') to at + 2 else null
                '.' -> CharSet.AnyChar to at + 1
                '[' -> {
                    val end = text.indexOf(']', at + 1)
                    if (end < 0) null else parseClass(text.substring(at + 1, end))?.let { it to end + 1 }
                }
                else -> CharSet.single(c) to at + 1
            }

            /** `a-z` ranges and single characters, read the way [classProblem] checks them; `.` inside a class is a plain dot. */
            private fun parseClass(inner: String): CharSet? {
                if (inner.isEmpty()) return null
                val ranges = ArrayList<IntArray>()
                var i = 0
                while (i < inner.length) {
                    val lo = inner[i]
                    if (i + 2 < inner.length && inner[i + 1] == '-' && inner[i + 2] != '-') {
                        val hi = inner[i + 2]
                        if (hi < lo) return null
                        ranges += intArrayOf(lo.code, hi.code)
                        i += 3
                    } else {
                        ranges += intArrayOf(lo.code, lo.code)
                        i++
                    }
                }
                return CharSet.of(ranges)
            }
        }
    }
}
