package com.gruning.mimir.rewrite.core.storage.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The `/storage/...` path a SAF document uri names. `core-launch` records exactly this string as a launch's `locator` for a
 * file-path emulator, and the launch-history carry/split looks for it, so what it returns for the shapes the walker stores is
 * load-bearing.
 */
class ExternalStorageDocumentPathTest {

    private val authority = "content://com.android.externalstorage.documents"

    @Test
    fun `the tree-plus-document form the walker stores reads the document id`() {
        assertEquals(
            "/storage/emulated/0/ROMs/psx/Chrono Cross (Disc 1).chd",
            ExternalStorageDocumentPath.pathOf(
                "$authority/tree/primary%3AROMs/document/primary%3AROMs%2Fpsx%2FChrono%20Cross%20(Disc%201).chd",
            ),
        )
    }

    @Test
    fun `a bare document uri and a removable volume`() {
        assertEquals(
            "/storage/6A7F-FCFE/ROMs/gc/Melee.iso",
            ExternalStorageDocumentPath.pathOf("$authority/document/6A7F-FCFE%3AROMs%2Fgc%2FMelee.iso"),
        )
    }

    @Test
    fun `a volume root, a query or fragment suffix, and a leading slash`() {
        assertEquals("/storage/emulated/0", ExternalStorageDocumentPath.pathOf("$authority/document/primary%3A"))
        assertEquals(
            "/storage/emulated/0/ROMs/a.zip",
            ExternalStorageDocumentPath.pathOf("$authority/document/primary%3AROMs%2Fa.zip?x=1#frag"),
        )
        assertEquals(
            "/storage/emulated/0/ROMs/a.zip",
            ExternalStorageDocumentPath.pathOf("$authority/document/primary%3A%2FROMs%2Fa.zip"),
        )
    }

    @Test
    fun `it spells the path as stored, without normalising segments`() {
        assertEquals(
            "/storage/emulated/0/ROMs/../a.zip",
            ExternalStorageDocumentPath.pathOf("$authority/document/primary%3AROMs%2F..%2Fa.zip"),
        )
    }

    @Test
    fun `home, a foreign authority, a missing document id and a bad escape name no path`() {
        assertNull(ExternalStorageDocumentPath.pathOf("$authority/document/home%3ADocuments%2Fa.zip"))
        assertNull(ExternalStorageDocumentPath.pathOf("content://com.android.providers.downloads.documents/document/primary%3Aa"))
        assertNull(ExternalStorageDocumentPath.pathOf("$authority/tree/primary%3AROMs"))
        assertNull(ExternalStorageDocumentPath.pathOf("$authority/document/"))
        assertNull(ExternalStorageDocumentPath.pathOf("$authority/document/noseparator"))
        assertNull(ExternalStorageDocumentPath.pathOf("$authority/document/primary%3AROMs%2"))
        assertNull(ExternalStorageDocumentPath.pathOf("/storage/emulated/0/ROMs/a.zip"))
    }
}
