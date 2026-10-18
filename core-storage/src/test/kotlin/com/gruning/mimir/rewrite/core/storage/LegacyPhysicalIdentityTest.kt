package com.gruning.mimir.rewrite.core.storage

import com.gruning.mimir.rewrite.core.model.physicalResidentKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LegacyPhysicalIdentityTest {

    @Test
    fun `persisted exact root maps without parsing the delimiter`() {
        val mapping = mapLegacyPhysicalIdentity(
            row(
                root = "content://provider/tree/ROM%3A1",
                relativePath = "SNES/folder::game.sfc",
                locationKind = "SAF_TREE_GRANT",
            ),
        )

        assertEquals(
            LegacyPhysicalIdentityMapping(
                sourceRootUri = "content://provider/tree/ROM%3A1",
                relativePath = "SNES/folder::game.sfc",
                physicalEntryKey = physicalResidentKey(
                    "content://provider/tree/ROM%3A1",
                    "SNES/folder::game.sfc",
                ),
            ),
            mapping,
        )
    }

    @Test
    fun `legacy null root derives only from exact managed suffix and locator`() {
        val root = "/storage/card/ROMs:"
        val relativePath = "PS1/Game/Disc 1.chd"
        val mapping = mapLegacyPhysicalIdentity(
            LegacyPhysicalIdentityRow(
                entryKey = "$root::$relativePath",
                sourceRootUri = null,
                relativePath = relativePath,
                locationKind = "MANAGED_PATH",
                managedPath = "$root/$relativePath",
            ),
        )

        assertEquals(root, mapping?.sourceRootUri)
        assertEquals(physicalResidentKey(root, relativePath), mapping?.physicalEntryKey)
    }

    @Test
    fun `both sides of a legacy scalar collision map to distinct physical keys`() {
        val first = mapLegacyPhysicalIdentity(row(root = "r", relativePath = ":game"))
        val second = mapLegacyPhysicalIdentity(row(root = "r:", relativePath = "game"))

        assertEquals("r:::game", row(root = "r", relativePath = ":game").entryKey)
        assertEquals("r:::game", row(root = "r:", relativePath = "game").entryKey)
        assertNotEquals(first?.physicalEntryKey, second?.physicalEntryKey)
    }

    @Test
    fun `ambiguous malformed and unsupported legacy rows fail closed`() {
        val valid = row(root = "/root", relativePath = "game.rom")
        listOf(
            valid.copy(entryKey = "/other::game.rom"),
            valid.copy(sourceRootUri = ""),
            valid.copy(sourceRootUri = null, locationKind = "SAF_TREE_GRANT"),
            valid.copy(sourceRootUri = null, managedPath = null),
            valid.copy(
                sourceRootUri = null,
                managedPath = "/other/game.rom",
            ),
            valid.copy(relativePath = ""),
            valid.copy(relativePath = "game\u0000.rom", entryKey = "/root::game\u0000.rom"),
            valid.copy(relativePath = "\uD83D", entryKey = "/root::\uD83D"),
        ).forEach { assertNull(it.toString(), mapLegacyPhysicalIdentity(it)) }
    }

    private fun row(
        root: String,
        relativePath: String,
        locationKind: String = "MANAGED_PATH",
    ) = LegacyPhysicalIdentityRow(
        entryKey = "$root::$relativePath",
        sourceRootUri = root,
        relativePath = relativePath,
        locationKind = locationKind,
        managedPath = if (locationKind == "MANAGED_PATH") "$root/$relativePath" else null,
    )
}
