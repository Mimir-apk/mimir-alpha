package com.gruning.mimir.rewrite

import java.io.File
import java.nio.ReadOnlyBufferException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetDatProviderTest {

    @Test
    fun `override resolution stays inside the dat directory`() {
        val root = createTempDir(prefix = "mimir-dat-root-")
        try {
            assertEquals(
                File(root, "nes.midx").canonicalFile,
                AssetDatProvider.resolveOverride(root, "nes.midx"),
            )
            assertEquals("dat/nes.midx", AssetDatProvider.bundledAssetPath("nes.midx"))

            listOf("", ".", "..", "../nes.midx", "nested/nes.midx", "nested\\nes.midx", "bad\u0000name")
                .forEach { name ->
                    assertNull(name, AssetDatProvider.resolveOverride(root, name))
                    assertNull(name, AssetDatProvider.bundledAssetPath(name))
                }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `filesystem packed asset is a read-only direct mapping`() {
        val root = createTempDir(prefix = "mimir-mapped-dat-")
        try {
            val packed = File(root, "fixture.midx")
            val expected = byteArrayOf(0x4d, 0x49, 0x4d, 0x49, 0x44, 0x58, 0x31, 0x00)
            packed.writeBytes(expected)

            val mapped = AssetDatProvider.mapReadOnly(packed)

            requireNotNull(mapped)
            assertTrue(mapped.isReadOnly)
            assertTrue(mapped.isDirect)
            assertEquals(0, mapped.position())
            assertEquals(expected.size, mapped.remaining())
            val actual = ByteArray(mapped.remaining())
            mapped.duplicate().get(actual)
            assertArrayEquals(expected, actual)
            assertTrue(runCatching { mapped.put(0, 0.toByte()) }.exceptionOrNull() is ReadOnlyBufferException)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `missing empty and directory overrides do not map`() {
        val root = createTempDir(prefix = "mimir-invalid-dat-")
        try {
            val empty = File(root, "empty.midx").apply { createNewFile() }
            assertNull(AssetDatProvider.mapReadOnly(null))
            assertNull(AssetDatProvider.mapReadOnly(File(root, "missing.midx")))
            assertNull(AssetDatProvider.mapReadOnly(empty))
            assertNull(AssetDatProvider.mapReadOnly(root))
            assertFalse(empty.length() > 0L)
        } finally {
            root.deleteRecursively()
        }
    }
}
