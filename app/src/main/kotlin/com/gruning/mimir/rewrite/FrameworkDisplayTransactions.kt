package com.gruning.mimir.rewrite

import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * The two display-manager calls the certified vendor route makes for the bottom screen's own brightness,
 * by the numbers THIS device's framework gives them.
 *
 * The bottom panel moves only through
 * `IDisplayManager.setBrightness(displayId, float)` -- writing AYN's `dual_screen_brightness_level` alone
 * moved nothing (2026-09-06) -- and the vendor `pservice` route had no display call, so Shizuku was the one
 * route that had one. `pservice` runs as root, and root may make that call with the stock `service` tool,
 * which addresses a binder method by its transaction NUMBER.
 *
 * Those numbers are not an API. AIDL numbers a service's methods in declaration order, so a firmware whose
 * `IDisplayManager` gained or lost a method before `setBrightness` sends the same number to a different
 * method. The app process cannot ask: the Stub's `TRANSACTION_*` fields are hidden API, BLOCKED for every
 * target SDK. A table per firmware would bind the route to stale device data. Read numbers from
 * the framework the device actually runs, `/system/framework/framework.jar`, which every app may read: the
 * Stub's two constants, accepted only when the interface's methods carry the signatures they are about to be
 * called with -- `setBrightness(int, float)` returning nothing and `getBrightness(int)` returning a float.
 * Anything else, and the route stays closed.
 *
 * On the AYN Thor (`TKQ1.231222.001`, 2026-09-18) they are 35 and 36, and 36 read the bottom screen back as
 * 0.65 over adb while AYN's own level read 65.
 */
internal data class DisplayBrightnessTransactions(
    val setBrightness: Int,
    val getBrightness: Int,
)

internal object FrameworkDisplayTransactions {
    const val FrameworkJarPath = "/system/framework/framework.jar"

    /** Larger than any framework dex seen (the Thor's biggest is 10MB); a bigger entry is not read at all. */
    private const val MaxDexBytes = 64 * 1024 * 1024

    /** `classes.dex`, then `classes2.dex` onwards, the order the runtime itself loads them in. */
    private const val MaxDexEntries = 100

    /**
     * The numbers, or null when the jar cannot be read, holds no `IDisplayManager$Stub`, or holds one whose
     * methods do not match. Never throws. Reads one dex entry at a time and stops at the first that answers.
     */
    fun read(jar: File = File(FrameworkJarPath)): DisplayBrightnessTransactions? = runCatching {
        ZipFile(jar).use { zip ->
            for (index in 1..MaxDexEntries) {
                val name = if (index == 1) "classes.dex" else "classes$index.dex"
                val entry = zip.getEntry(name) ?: break
                val size = entry.size
                if (size <= 0L || size > MaxDexBytes) continue
                val bytes = ByteArray(size.toInt())
                val complete = zip.getInputStream(entry).use { input -> input.readFully(bytes) }
                if (!complete) continue
                DexDisplayTransactions.find(bytes)?.let { return@use it }
            }
            null
        }
    }.getOrNull()

    private fun InputStream.readFully(target: ByteArray): Boolean {
        var filled = 0
        while (filled < target.size) {
            val read = read(target, filled, target.size - filled)
            if (read < 0) return false
            filled += read
        }
        return read() < 0
    }
}

/**
 * Reads the two numbers out of ONE dex file's bytes, or null. Pure, bounds-checked, and never throws: a
 * truncated or unfamiliar file is an answer of null, not a crash in the brightness path.
 *
 * Only what the question needs is parsed: the string, type, proto, field and method tables, the Stub's
 * class definition, its static fields and their constant values. Dex versions 035 to 039 share that
 * layout; anything else is declined.
 */
internal object DexDisplayTransactions {
    private const val StubDescriptor = "Landroid/hardware/display/IDisplayManager\$Stub;"
    private const val InterfaceDescriptor = "Landroid/hardware/display/IDisplayManager;"
    private const val SetField = "TRANSACTION_setBrightness"
    private const val GetField = "TRANSACTION_getBrightness"
    private const val SetMethod = "setBrightness"
    private const val GetMethod = "getBrightness"

    /** `void setBrightness(int, float)` and `float getBrightness(int)`, as dex short-form prototypes. */
    private const val SetShorty = "VIF"
    private const val GetShorty = "FI"
    private const val IntType = "I"

    /** Binder's FIRST_CALL_TRANSACTION to LAST_CALL_TRANSACTION. */
    private val CallTransactions = 1..0x00ffffff

    private const val ValueInt = 0x04

    fun find(dex: ByteArray): DisplayBrightnessTransactions? = runCatching { findIn(DexView(dex)) }.getOrNull()

    private fun findIn(dex: DexView): DisplayBrightnessTransactions? {
        if (!dex.hasSupportedHeader()) return null
        val stubType = dex.typeIndexOf(StubDescriptor) ?: return null
        val interfaceType = dex.typeIndexOf(InterfaceDescriptor)
        val intType = dex.typeIndexOf(IntType) ?: return null
        val setFieldName = dex.stringIndexOf(SetField) ?: return null
        val getFieldName = dex.stringIndexOf(GetField) ?: return null

        val classDef = dex.classDefOffset(stubType) ?: return null
        val constants = dex.staticIntConstants(classDef, stubType, intType, setOf(setFieldName, getFieldName))
        val setCode = constants[setFieldName]?.takeIf { it in CallTransactions } ?: return null
        val getCode = constants[getFieldName]?.takeIf { it in CallTransactions } ?: return null
        if (setCode == getCode) return null

        // The numbers are only half of it: they must belong to methods that take what the command will send.
        val owners = setOfNotNull(stubType, interfaceType)
        if (!dex.methodShortiesAre(owners, SetMethod, SetShorty)) return null
        if (!dex.methodShortiesAre(owners, GetMethod, GetShorty)) return null
        return DisplayBrightnessTransactions(setBrightness = setCode, getBrightness = getCode)
    }

    /** Every index access below goes through [u1], which throws on a bad offset; [find] turns that into null. */
    private class DexView(private val bytes: ByteArray) {
        private val stringIdsSize = u4(0x38)
        private val stringIdsOff = u4(0x3c)
        private val typeIdsSize = u4(0x40)
        private val typeIdsOff = u4(0x44)
        private val protoIdsSize = u4(0x48)
        private val protoIdsOff = u4(0x4c)
        private val fieldIdsSize = u4(0x50)
        private val fieldIdsOff = u4(0x54)
        private val methodIdsSize = u4(0x58)
        private val methodIdsOff = u4(0x5c)
        private val classDefsSize = u4(0x60)
        private val classDefsOff = u4(0x64)

        fun hasSupportedHeader(): Boolean {
            val magic = String(bytes, 0, 4, Charsets.US_ASCII)
            val version = String(bytes, 4, 4, Charsets.US_ASCII)
            if (magic != "dex\n" || version !in SupportedVersions) return false
            if (u4(0x28) != EndianConstant) return false
            if (u4(0x20) != bytes.size) return false
            return listOf(
                stringIdsSize to stringIdsOff,
                typeIdsSize to typeIdsOff,
                protoIdsSize to protoIdsOff,
                fieldIdsSize to fieldIdsOff,
                methodIdsSize to methodIdsOff,
                classDefsSize to classDefsOff,
            ).all { (size, offset) -> size >= 0 && offset >= 0 && offset <= bytes.size }
        }

        fun stringIndexOf(target: String): Int? {
            val wanted = target.toByteArray(Charsets.US_ASCII)
            for (index in 0 until stringIdsSize) {
                if (stringEquals(index, wanted)) return index
            }
            return null
        }

        fun typeIndexOf(descriptor: String): Int? {
            val string = stringIndexOf(descriptor) ?: return null
            for (index in 0 until typeIdsSize) {
                if (u4(typeIdsOff + index * 4) == string) return index
            }
            return null
        }

        fun classDefOffset(type: Int): Int? {
            for (index in 0 until classDefsSize) {
                val offset = classDefsOff + index * ClassDefBytes
                if (u4(offset) == type) return offset
            }
            return null
        }

        /**
         * The class's static fields named [names], of type int, that carry a constant value: name string index
         * to value. A field whose value is not an encoded int is left out rather than guessed.
         */
        fun staticIntConstants(classDef: Int, owner: Int, intType: Int, names: Set<Int>): Map<Int, Int> {
            val classData = u4(classDef + 24)
            val staticValues = u4(classDef + 28)
            if (classData == 0 || staticValues == 0) return emptyMap()

            var cursor = classData
            val staticFieldCount = uleb(cursor).also { cursor = it.next }.value
            repeat(3) { cursor = uleb(cursor).next } // instance fields, direct methods, virtual methods
            val fields = ArrayList<Int>(staticFieldCount)
            var fieldIndex = 0
            repeat(staticFieldCount) {
                val diff = uleb(cursor).also { cursor = it.next }.value
                cursor = uleb(cursor).next // access flags
                fieldIndex += diff
                fields += fieldIndex
            }

            var valueCursor = staticValues
            val valueCount = uleb(valueCursor).also { valueCursor = it.next }.value
            val found = HashMap<Int, Int>()
            for (position in 0 until minOf(valueCount, fields.size)) {
                val header = u1(valueCursor)
                val type = header and 0x1f
                val arg = header ushr 5
                val field = fields[position]
                val fieldOffset = fieldIdsOff + field * 8
                val name = u4(fieldOffset + 4)
                if (type == ValueInt && name in names && u2(fieldOffset) == owner && u2(fieldOffset + 2) == intType) {
                    found[name] = signedLittleEndian(valueCursor + 1, arg + 1)
                }
                valueCursor = skipEncodedValue(valueCursor)
            }
            return found
        }

        /**
         * Every method named [name] on [owners] has prototype [shorty], and there is at least one. AIDL forbids
         * overloads, so a second shape under the same name is a firmware this reader does not understand.
         */
        fun methodShortiesAre(owners: Set<Int>, name: String, shorty: String): Boolean {
            val nameIndex = stringIndexOf(name) ?: return false
            val wanted = shorty.toByteArray(Charsets.US_ASCII)
            var seen = false
            for (index in 0 until methodIdsSize) {
                val offset = methodIdsOff + index * 8
                if (u2(offset) !in owners || u4(offset + 4) != nameIndex) continue
                val proto = u2(offset + 2)
                if (proto >= protoIdsSize) return false
                if (!stringEquals(u4(protoIdsOff + proto * 12), wanted)) return false
                seen = true
            }
            return seen
        }

        private fun stringEquals(index: Int, wanted: ByteArray): Boolean {
            if (index !in 0 until stringIdsSize) return false
            val data = u4(stringIdsOff + index * 4)
            val length = uleb(data)
            if (length.value != wanted.size) return false
            for (position in wanted.indices) {
                if (u1(length.next + position) != (wanted[position].toInt() and 0xff)) return false
            }
            return u1(length.next + wanted.size) == 0
        }

        private fun skipEncodedValue(offset: Int): Int {
            val header = u1(offset)
            val type = header and 0x1f
            val arg = header ushr 5
            return when (type) {
                0x1c -> skipEncodedArray(offset + 1)
                0x1d -> skipEncodedAnnotation(offset + 1)
                0x1e, 0x1f -> offset + 1
                in FixedWidthValueTypes -> offset + 1 + arg + 1
                else -> throw IllegalStateException("unknown encoded value type $type")
            }
        }

        private fun skipEncodedArray(offset: Int): Int {
            var cursor = offset
            val size = uleb(cursor).also { cursor = it.next }.value
            repeat(size) { cursor = skipEncodedValue(cursor) }
            return cursor
        }

        private fun skipEncodedAnnotation(offset: Int): Int {
            var cursor = uleb(offset).next // type
            val size = uleb(cursor).also { cursor = it.next }.value
            repeat(size) {
                cursor = uleb(cursor).next // element name
                cursor = skipEncodedValue(cursor)
            }
            return cursor
        }

        private fun signedLittleEndian(offset: Int, width: Int): Int {
            var value = 0
            for (position in 0 until width) value = value or (u1(offset + position) shl (8 * position))
            val unused = 32 - 8 * width
            return (value shl unused) shr unused
        }

        private data class Uleb(val value: Int, val next: Int)

        private fun uleb(offset: Int): Uleb {
            var value = 0
            var cursor = offset
            for (shift in 0..28 step 7) {
                val byte = u1(cursor++)
                value = value or ((byte and 0x7f) shl shift)
                if (byte and 0x80 == 0) {
                    check(value >= 0) { "uleb128 out of range" }
                    return Uleb(value, cursor)
                }
            }
            throw IllegalStateException("uleb128 longer than five bytes")
        }

        private fun u1(offset: Int): Int = bytes[offset].toInt() and 0xff

        private fun u2(offset: Int): Int = u1(offset) or (u1(offset + 1) shl 8)

        private fun u4(offset: Int): Int = u2(offset) or (u2(offset + 2) shl 16)

        private companion object {
            val SupportedVersions = setOf("035\u0000", "036\u0000", "037\u0000", "038\u0000", "039\u0000")
            const val EndianConstant = 0x12345678
            const val ClassDefBytes = 32

            /** byte, short, char, int, long, float, double, method type/handle, string, type, field, method, enum. */
            val FixedWidthValueTypes = setOf(
                0x00, 0x02, 0x03, 0x04, 0x06, 0x10, 0x11, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x1b,
            )
        }
    }
}
