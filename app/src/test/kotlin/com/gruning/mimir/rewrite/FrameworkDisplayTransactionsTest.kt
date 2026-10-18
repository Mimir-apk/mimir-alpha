package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * [FrameworkDisplayTransactions] reads the display manager's two brightness numbers out of the device's own
 * framework, because the vendor route sends them to root's `service call` and a wrong number is a call to a
 * different method. So this file is mostly about what it must DECLINE.
 *
 * The dex files here are built byte by byte ([TinyDex]) with the layout the reader walks: the string, type,
 * proto, field and method tables, one class definition, its class data and its static values. The reader was
 * also checked once against the AYN Thor's own `framework.jar` (four dex files, the Stub in the first), where
 * it answered 35 and 36 -- the same numbers `dexdump` printed and the same 36 that read the bottom screen back
 * as 0.65 over adb.
 */
class FrameworkDisplayTransactionsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val stub = "Landroid/hardware/display/IDisplayManager\$Stub;"
    private val iface = "Landroid/hardware/display/IDisplayManager;"

    /** The Thor's shape: a String DESCRIPTOR, the two constants among others, and the interface's methods. */
    private fun thorLike(
        setCode: Int = 35,
        getCode: Int = 36,
        setShorty: String = "VIF",
        getShorty: String = "FI",
        extraMethods: List<TinyDex.Method> = emptyList(),
        version: String = "035",
    ): ByteArray = TinyDex.build(
        stubClass = stub,
        stubFields = listOf(
            TinyDex.Field("DESCRIPTOR", "Ljava/lang/String;", "android.hardware.display.IDisplayManager"),
            TinyDex.Field("TRANSACTION_getBrightness", "I", getCode),
            TinyDex.Field("TRANSACTION_getDisplayInfo", "I", 1),
            TinyDex.Field("TRANSACTION_setBrightness", "I", setCode),
            TinyDex.Field("TRANSACTION_setTemporaryBrightness", "I", 34),
        ),
        methods = listOf(
            TinyDex.Method(iface, "getBrightness", getShorty),
            TinyDex.Method(iface, "getDisplayInfo", "LI"),
            TinyDex.Method(iface, "setBrightness", setShorty),
        ) + extraMethods,
        version = version,
    )

    @Test
    fun `the numbers are read when the constants and the signatures agree`() {
        assertEquals(DisplayBrightnessTransactions(setBrightness = 35, getBrightness = 36), DexDisplayTransactions.find(thorLike()))
    }

    @Test
    fun `a firmware that numbers them differently is read as it is, not as the Thor is`() {
        // The whole reason for reading instead of pinning: one more method before setBrightness moves both.
        assertEquals(
            DisplayBrightnessTransactions(setBrightness = 37, getBrightness = 38),
            DexDisplayTransactions.find(thorLike(setCode = 37, getCode = 38)),
        )
        // Every supported dex version shares the layout.
        for (version in listOf("035", "036", "037", "038", "039")) {
            assertEquals(version, 35, DexDisplayTransactions.find(thorLike(version = version))?.setBrightness)
        }
    }

    @Test
    fun `a setBrightness that takes anything but an int and a float is declined`() {
        assertNull(DexDisplayTransactions.find(thorLike(setShorty = "VIFZ")))
        assertNull(DexDisplayTransactions.find(thorLike(setShorty = "VF")))
        assertNull("returning something is a different method too", DexDisplayTransactions.find(thorLike(setShorty = "ZIF")))
    }

    @Test
    fun `a getBrightness that does not return a float for an int is declined`() {
        assertNull(DexDisplayTransactions.find(thorLike(getShorty = "II")))
        assertNull(DexDisplayTransactions.find(thorLike(getShorty = "F")))
    }

    @Test
    fun `a second shape under the same name is declined, because AIDL has no overloads`() {
        assertNull(
            DexDisplayTransactions.find(
                thorLike(extraMethods = listOf(TinyDex.Method(iface, "setBrightness", "VIFZ"))),
            ),
        )
    }

    @Test
    fun `the signature may be proven through the Stub's own method reference`() {
        // javac compiles onTransact's `this.setBrightness(...)` against the Stub, so a dex can carry the
        // Stub's reference and not the interface's. Either owner proves the shape.
        val dex = TinyDex.build(
            stubClass = stub,
            stubFields = listOf(
                TinyDex.Field("TRANSACTION_getBrightness", "I", 36),
                TinyDex.Field("TRANSACTION_setBrightness", "I", 35),
            ),
            methods = listOf(
                TinyDex.Method(stub, "getBrightness", "FI"),
                TinyDex.Method(stub, "setBrightness", "VIF"),
            ),
        )
        assertEquals(DisplayBrightnessTransactions(35, 36), DexDisplayTransactions.find(dex))
    }

    @Test
    fun `no methods to prove the signature with is no answer`() {
        val dex = TinyDex.build(
            stubClass = stub,
            stubFields = listOf(
                TinyDex.Field("TRANSACTION_getBrightness", "I", 36),
                TinyDex.Field("TRANSACTION_setBrightness", "I", 35),
            ),
            methods = emptyList(),
        )
        assertNull(DexDisplayTransactions.find(dex))
    }

    @Test
    fun `numbers outside binder's call range, or the same number twice, are declined`() {
        assertNull(DexDisplayTransactions.find(thorLike(setCode = 0)))
        assertNull(DexDisplayTransactions.find(thorLike(setCode = -1)))
        assertNull(DexDisplayTransactions.find(thorLike(getCode = 0x01000000)))
        assertNull(DexDisplayTransactions.find(thorLike(setCode = 36, getCode = 36)))
    }

    @Test
    fun `a missing constant is no answer`() {
        val dex = TinyDex.build(
            stubClass = stub,
            stubFields = listOf(TinyDex.Field("TRANSACTION_setBrightness", "I", 35)),
            methods = listOf(TinyDex.Method(iface, "getBrightness", "FI"), TinyDex.Method(iface, "setBrightness", "VIF")),
        )
        assertNull(DexDisplayTransactions.find(dex))
    }

    @Test
    fun `a dex without the Stub is no answer`() {
        val other = TinyDex.build(
            stubClass = "Landroid/os/IPowerManager\$Stub;",
            stubFields = listOf(TinyDex.Field("TRANSACTION_setBrightness", "I", 35)),
            methods = emptyList(),
        )
        assertNull(DexDisplayTransactions.find(other))
    }

    @Test
    fun `a damaged or unfamiliar file is no answer, and never a crash`() {
        val good = thorLike()
        assertNull("dex container version 041 has another layout", DexDisplayTransactions.find(thorLike(version = "041")))
        assertNull(DexDisplayTransactions.find(ByteArray(0)))
        assertNull(DexDisplayTransactions.find(ByteArray(0x70)))
        assertNull(DexDisplayTransactions.find(good.copyOf(good.size - 1)))
        for (length in 0 until good.size step 7) {
            assertNull("truncated to $length bytes", DexDisplayTransactions.find(good.copyOf(length)))
        }
        val random = java.util.Random(7)
        repeat(200) {
            val damaged = good.copyOf()
            damaged[0x70 + random.nextInt(damaged.size - 0x70)] = random.nextInt(256).toByte()
            // Any answer a damaged file gives must still be a pair of in-range numbers; most give none.
            DexDisplayTransactions.find(damaged)?.let { found ->
                assert(found.setBrightness in 1..0x00ffffff && found.getBrightness in 1..0x00ffffff)
            }
        }
    }

    @Test
    fun `the jar is read dex by dex until one answers`() {
        val unrelated = TinyDex.build(
            stubClass = "Landroid/os/IPowerManager\$Stub;",
            stubFields = listOf(TinyDex.Field("TRANSACTION_goToSleep", "I", 3)),
            methods = emptyList(),
        )
        val jar = jarOf("classes.dex" to unrelated, "classes2.dex" to thorLike(), "classes3.dex" to unrelated)
        assertEquals(DisplayBrightnessTransactions(35, 36), FrameworkDisplayTransactions.read(jar))
    }

    @Test
    fun `a jar with no answering dex, no dex, or no file at all is no answer`() {
        val unrelated = TinyDex.build(
            stubClass = "Landroid/os/IPowerManager\$Stub;",
            stubFields = listOf(TinyDex.Field("TRANSACTION_goToSleep", "I", 3)),
            methods = emptyList(),
        )
        assertNull(FrameworkDisplayTransactions.read(jarOf("classes.dex" to unrelated)))
        assertNull(FrameworkDisplayTransactions.read(jarOf("META-INF/MANIFEST.MF" to ByteArray(3))))
        assertNull(
            "classes2.dex is only looked for after classes.dex, as the runtime loads them",
            FrameworkDisplayTransactions.read(jarOf("classes2.dex" to thorLike())),
        )
        assertNull(FrameworkDisplayTransactions.read(File(temp.root, "missing.jar")))
        assertNull(FrameworkDisplayTransactions.read(temp.newFile("not-a-zip.jar").apply { writeText("text") }))
    }

    private fun jarOf(vararg entries: Pair<String, ByteArray>): File {
        val file = temp.newFile()
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }
}

/**
 * Just enough of a dex writer for [FrameworkDisplayTransactionsTest]: sorted string, type, proto, field and
 * method tables, one class definition with static fields only, and its static values. Checksums and the map
 * list are left empty, which the reader does not look at and the runtime would reject -- these files are
 * read, never loaded.
 */
private object TinyDex {
    data class Field(val name: String, val type: String, val value: Any)
    data class Method(val owner: String, val name: String, val shorty: String)

    fun build(stubClass: String, stubFields: List<Field>, methods: List<Method>, version: String = "035"): ByteArray {
        val strings = sortedSetOf<String>()
        strings += stubClass
        stubFields.forEach { strings += it.name; strings += it.type; (it.value as? String)?.let(strings::add) }
        methods.forEach { strings += it.owner; strings += it.name; strings += it.shorty }
        strings += "I"
        val stringList = strings.toList()
        fun str(value: String) = stringList.indexOf(value)

        val typeNames = (listOf(stubClass, "I") + stubFields.map { it.type } + methods.map { it.owner })
            .distinct()
            .sortedBy(::str)
        fun type(descriptor: String) = typeNames.indexOf(descriptor)

        val shorties = methods.map { it.shorty }.distinct().sortedBy(::str)
        fun proto(shorty: String) = shorties.indexOf(shorty)

        val fields = stubFields.sortedWith(compareBy({ str(it.name) }, { type(it.type) }))
        val methodIds = methods.sortedWith(compareBy({ type(it.owner) }, { str(it.name) }, { proto(it.shorty) }))

        val headerSize = 0x70
        val stringIdsOff = headerSize
        val typeIdsOff = stringIdsOff + 4 * stringList.size
        val protoIdsOff = typeIdsOff + 4 * typeNames.size
        val fieldIdsOff = protoIdsOff + 12 * shorties.size
        val methodIdsOff = fieldIdsOff + 8 * fields.size
        val classDefsOff = methodIdsOff + 8 * methodIds.size
        val dataOff = classDefsOff + 32

        val data = Buffer()
        val stringDataOffsets = stringList.map { value ->
            val at = dataOff + data.size
            data.uleb(value.length)
            data.bytes(value.toByteArray(Charsets.US_ASCII))
            data.u1(0)
            at
        }
        val classDataOff = dataOff + data.size
        data.uleb(fields.size)
        data.uleb(0)
        data.uleb(0)
        data.uleb(0)
        var previous = 0
        fields.forEachIndexed { index, field ->
            data.uleb(index - previous)
            previous = index
            data.uleb(0x19)
        }
        val staticValuesOff = dataOff + data.size
        data.uleb(fields.size)
        for (field in fields) {
            when (val value = field.value) {
                is Int -> data.encodedInt(0x04, value)
                is String -> data.encodedUnsigned(0x17, str(value))
                else -> error("unsupported value $value")
            }
        }

        val out = Buffer()
        out.bytes("dex\n".toByteArray(Charsets.US_ASCII))
        out.bytes(version.toByteArray(Charsets.US_ASCII))
        out.u1(0)
        repeat(4 + 20) { out.u1(0) } // checksum, signature
        val fileSize = dataOff + data.size
        out.u4(fileSize)
        out.u4(headerSize)
        out.u4(0x12345678)
        out.u4(0); out.u4(0); out.u4(0) // link size, link off, map off
        out.u4(stringList.size); out.u4(stringIdsOff)
        out.u4(typeNames.size); out.u4(typeIdsOff)
        out.u4(shorties.size); out.u4(protoIdsOff)
        out.u4(fields.size); out.u4(fieldIdsOff)
        out.u4(methodIds.size); out.u4(methodIdsOff)
        out.u4(1); out.u4(classDefsOff)
        out.u4(data.size); out.u4(dataOff)
        check(out.size == headerSize)
        stringDataOffsets.forEach(out::u4)
        typeNames.forEach { out.u4(str(it)) }
        shorties.forEach { shorty ->
            out.u4(str(shorty))
            out.u4(type("I"))
            out.u4(0)
        }
        fields.forEach { field ->
            out.u2(type(stubClass))
            out.u2(type(field.type))
            out.u4(str(field.name))
        }
        methodIds.forEach { method ->
            out.u2(type(method.owner))
            out.u2(proto(method.shorty))
            out.u4(str(method.name))
        }
        out.u4(type(stubClass)) // class_idx
        out.u4(0x0401) // access flags
        out.u4(-1) // superclass: none, which the reader does not follow
        out.u4(0) // interfaces
        out.u4(-1) // source file
        out.u4(0) // annotations
        out.u4(classDataOff)
        out.u4(staticValuesOff)
        check(out.size == dataOff)
        out.bytes(data.toByteArray())
        return out.toByteArray().also { check(it.size == fileSize) }
    }

    private class Buffer {
        private val out = ByteArrayOutputStream()
        val size get() = out.size()
        fun u1(value: Int) = out.write(value and 0xff)
        fun u2(value: Int) { u1(value); u1(value shr 8) }
        fun u4(value: Int) { u2(value); u2(value shr 16) }
        fun bytes(value: ByteArray) = out.write(value)
        fun toByteArray(): ByteArray = out.toByteArray()

        fun uleb(value: Int) {
            var remaining = value
            do {
                var byte = remaining and 0x7f
                remaining = remaining ushr 7
                if (remaining != 0) byte = byte or 0x80
                u1(byte)
            } while (remaining != 0)
        }

        /** The fewest little-endian bytes that sign-extend back to [value], as d8 writes an int constant. */
        fun encodedInt(type: Int, value: Int) {
            var width = 4
            while (width > 1) {
                val unused = 32 - 8 * (width - 1)
                if ((value shl unused) shr unused != value) break
                width--
            }
            u1(((width - 1) shl 5) or type)
            for (position in 0 until width) u1(value shr (8 * position))
        }

        fun encodedUnsigned(type: Int, value: Int) {
            var width = 1
            while (width < 4 && value ushr (8 * width) != 0) width++
            u1(((width - 1) shl 5) or type)
            for (position in 0 until width) u1(value shr (8 * position))
        }
    }
}
