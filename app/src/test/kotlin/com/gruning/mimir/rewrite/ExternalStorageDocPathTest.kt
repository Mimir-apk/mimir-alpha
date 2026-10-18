package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * SD01b — the SAF→File translation behind the identity pass's direct-read lane. Wrong mapping
 * here would silently hash the WRONG file (identity corruption), so the shape is pinned tight:
 * only plain, traversal-free externalstorage documents translate; everything else must return
 * null so the opener falls back to the ContentResolver.
 */
class ExternalStorageDocPathTest {

    @Test
    fun `sd-card document maps to its storage path`() {
        assertEquals(
            "/storage/6E0E-FBB0/ROMS/snes/Fatal Fury (USA).zip",
            ExternalStorageDocPath.resolve(
                "content://com.android.externalstorage.documents/tree/6E0E-FBB0%3AROMS/" +
                    "document/6E0E-FBB0%3AROMS%2Fsnes%2FFatal%20Fury%20(USA).zip",
            ),
        )
    }

    @Test
    fun `primary volume maps to emulated-0`() {
        assertEquals(
            "/storage/emulated/0/ROMs/gba/x.gba",
            ExternalStorageDocPath.resolve(
                "content://com.android.externalstorage.documents/tree/primary%3AROMs/" +
                    "document/primary%3AROMs%2Fgba%2Fx.gba",
            ),
        )
    }

    @Test
    fun `non-externalstorage providers never translate`() {
        assertNull(
            ExternalStorageDocPath.resolve(
                "content://com.other.provider/tree/x/document/6E0E-FBB0%3AROMS%2Fa.zip",
            ),
        )
    }

    @Test
    fun `tree-only uris and malformed ids never translate`() {
        assertNull(
            ExternalStorageDocPath.resolve(
                "content://com.android.externalstorage.documents/tree/6E0E-FBB0%3AROMS",
            ),
        )
        assertNull(
            ExternalStorageDocPath.resolve(
                "content://com.android.externalstorage.documents/tree/x/document/no-colon-here",
            ),
        )
    }

    @Test
    fun `traversal and empty segments never translate`() {
        assertNull(
            ExternalStorageDocPath.resolve(
                "content://com.android.externalstorage.documents/tree/x/" +
                    "document/6E0E-FBB0%3AROMS%2F..%2Fsecrets.bin",
            ),
        )
        assertNull(
            ExternalStorageDocPath.resolve(
                "content://com.android.externalstorage.documents/tree/x/" +
                    "document/6E0E-FBB0%3AROMS%2F%2Fa.zip",
            ),
        )
    }

    // ── resolveTreeRoot (SD03) ───────────────────────────────────────────────────────────────
    // Covered indirectly through LibraryRootMatcherTest, but pinned directly here too: this is the
    // function the whole targeted-rescan feature rests on. A library source is registered as
    // `treeUri.toString()` (MimirSettingsCoordinator's acceptSource), so if this returns null the
    // rescan matches no root, does nothing, and every gate stays green — the silent-success class.

    @Test
    fun `tree root decodes to its real path`() {
        assertEquals(
            "/storage/emulated/0/Roms",
            ExternalStorageDocPath.resolveTreeRoot(
                "content://com.android.externalstorage.documents/tree/primary%3ARoms",
            ),
        )
        assertEquals(
            "/storage/6E0E-FBB0/ROMS",
            ExternalStorageDocPath.resolveTreeRoot(
                "content://com.android.externalstorage.documents/tree/6E0E-FBB0%3AROMS",
            ),
        )
    }

    @Test
    fun `a combined tree-plus-document grant resolves from its TREE half`() {
        // The grant names a file, but the registered SOURCE is the tree. Resolving from the document
        // half would register a single ROM as a library root and rescan a file instead of a folder.
        assertEquals(
            "/storage/emulated/0/Roms",
            ExternalStorageDocPath.resolveTreeRoot(
                "content://com.android.externalstorage.documents/tree/primary%3ARoms/" +
                    "document/primary%3ARoms%2FPS2%2Fgame.iso",
            ),
        )
    }

    @Test
    fun `a whole-volume grant resolves to the bare volume root`() {
        assertEquals(
            "/storage/emulated/0",
            ExternalStorageDocPath.resolveTreeRoot(
                "content://com.android.externalstorage.documents/tree/primary%3A",
            ),
        )
    }

    @Test
    fun `the two resolvers deliberately disagree on a bare volume id`() {
        // Not incidental: resolve() must reject it (a /document/ URI always names something), and
        // resolveTreeRoot() must accept it (a whole-volume grant is real). Pinned so a later
        // "unification" of the two entry points fails here instead of silently changing behaviour.
        val bareVolumeDocument =
            "content://com.android.externalstorage.documents/tree/x/document/primary%3A"
        assertNull(ExternalStorageDocPath.resolve(bareVolumeDocument))
        assertEquals(
            "/storage/emulated/0",
            ExternalStorageDocPath.resolveTreeRoot(
                "content://com.android.externalstorage.documents/tree/primary%3A",
            ),
        )
    }

    @Test
    fun `foreign providers, missing tree segments and traversal never resolve as roots`() {
        assertNull(
            ExternalStorageDocPath.resolveTreeRoot(
                "content://com.other.provider/tree/primary%3ARoms",
            ),
        )
        assertNull(
            ExternalStorageDocPath.resolveTreeRoot(
                "content://com.android.externalstorage.documents/document/primary%3ARoms%2Fa.zip",
            ),
        )
        assertNull(
            ExternalStorageDocPath.resolveTreeRoot(
                "content://com.android.externalstorage.documents/tree/primary%3ARoms%2F..%2Fsecrets",
            ),
        )
    }
}
