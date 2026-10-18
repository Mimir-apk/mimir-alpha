package com.gruning.mimir.rewrite.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PhysicalResidentKeyTest {

    @Test
    fun `legacy delimiter collisions encode as distinct physical residents`() {
        assertEquals("r:::game", "r::" + ":game")
        assertEquals("r:::game", "r:" + "::game")
        assertNotEquals(
            physicalResidentKey("r", ":game"),
            physicalResidentKey("r:", "game"),
        )

        assertEquals("r::x::y", "r::" + "x::y")
        assertEquals("r::x::y", "r::x" + "::y")
        assertNotEquals(
            physicalResidentKey("r", "x::y"),
            physicalResidentKey("r::x", "y"),
        )
    }

    @Test
    fun `codec round trips delimiter encoded and unicode paths without normalization`() {
        val roots = listOf(
            "/storage/roms",
            "/storage/roms:",
            "content://com.android.externalstorage.documents/tree/691E%3A7BEA",
            "p2:4:root",
            "m1|legacy-looking",
            "u2|legacy-looking",
            "A😀e\u0301",
            "שלום/ゲーム",
            "𠀀-root",
        )
        val relativePaths = listOf(
            "game.nes",
            ":game",
            "folder::game.chd",
            "100%/literal%3Aname.rom",
            "😀/e\u0301/ゲーム.rom",
            "𠀀/مرحبا.rom",
            " ",
        )

        roots.forEach { root ->
            relativePaths.forEach { relativePath ->
                assertEquals(
                    DecodedPhysicalResidentKey(root, relativePath),
                    decodePhysicalResidentKey(physicalResidentKey(root, relativePath)),
                )
            }
        }
    }

    @Test
    fun `codec is injective over the adversarial cross product`() {
        val roots = listOf("r", "r:", "r::x", "p2:", "😀", "e\u0301", "é")
        val relativePaths = listOf("x", ":x", "x::y", "%", "%3A", "😀", "e\u0301", "é")
        val pairs = roots.flatMap { root -> relativePaths.map { relativePath -> root to relativePath } }
        val encoded = pairs.map { (root, relativePath) -> physicalResidentKey(root, relativePath) }

        assertEquals(pairs.size, encoded.toSet().size)
    }

    @Test
    fun `codec counts unicode code points rather than utf16 code units`() {
        val root = "A😀e\u0301"
        val relativePath = "𠀀.rom"
        val encoded = physicalResidentKey(root, relativePath)

        assertTrue(encoded.startsWith("p2:4:5:"))
        assertEquals(
            DecodedPhysicalResidentKey(root, relativePath),
            decodePhysicalResidentKey(encoded),
        )
    }

    @Test
    fun `decoder rejects malformed and noncanonical keys`() {
        listOf(
            "",
            "p1:1:1:ab",
            "p2::1:a",
            "p2:01:1:ab",
            "p2:1:01:ab",
            "p2:x:1:ab",
            "p2:1:x:ab",
            "p2:1:1:a",
            "p2:1:1:abc",
            "p2:1:0:a",
            "p2:2147483647:1:ab",
            "p2:2147483648:1:ab",
            "p2:1:2147483648:ab",
            "p2:1:1:a\u0000",
            "p2:1:1:a\uD83D",
            "p2:1:1:a\uDE00",
        ).forEach(::assertRejected)
    }

    @Test
    fun `encoder rejects unsafe text and missing identity components`() {
        assertRejected { physicalResidentKey("", "game.rom") }
        assertRejected { physicalResidentKey("root", "") }
        assertRejected { physicalResidentKey("root\u0000suffix", "game.rom") }
        assertRejected { physicalResidentKey("root", "game\u0000.rom") }
        assertRejected { physicalResidentKey("\uD83D", "game.rom") }
        assertRejected { physicalResidentKey("root", "\uDE00.rom") }
    }

    private fun assertRejected(value: String) {
        assertRejected { decodePhysicalResidentKey(value) }
    }

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
            fail("Expected physical resident key rejection")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
