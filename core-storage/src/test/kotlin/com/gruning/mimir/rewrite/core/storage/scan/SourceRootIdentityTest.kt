package com.gruning.mimir.rewrite.core.storage.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The exact pair off the Odin, and the traps around it.
 *
 * Ground truth pulled read-only from the reference device 2026-08-29: `source_roots` held BOTH spellings of one
 * SD folder, which doubled every ROM and put a phantom "2 versions" badge on 25 games.
 */
class SourceRootIdentityTest {

    private val safRoot = "content://com.android.externalstorage.documents/tree/6A7F-FCFE%3AROMs"
    private val pathRoot = "/storage/6A7F-FCFE/ROMs"

    @Test
    fun theTwoSpellingsOfHisRomsFolderAreOneDirectory() {
        assertEquals("/storage/6A7F-FCFE/ROMs", SourceRootIdentity.canonicalOf(safRoot))
        assertEquals("/storage/6A7F-FCFE/ROMs", SourceRootIdentity.canonicalOf(pathRoot))
        assertTrue(SourceRootIdentity.namesSameDirectory(safRoot, pathRoot))
        assertTrue(SourceRootIdentity.namesSameDirectory(pathRoot, safRoot))
    }

    /**
     * The prefix trap. `/storage/X/ROMs` is a literal string prefix of `/storage/X/ROMs2`, so any
     * `startsWith` comparison would fold two unrelated folders into one and delete a real source.
     */
    @Test
    fun aFolderIsNotTheFolderWhoseNameItPrefixes() {
        assertFalse(SourceRootIdentity.namesSameDirectory(pathRoot, "/storage/6A7F-FCFE/ROMs2"))
        assertFalse(
            SourceRootIdentity.namesSameDirectory(
                safRoot,
                "content://com.android.externalstorage.documents/tree/6A7F-FCFE%3AROMs2",
            ),
        )
    }

    @Test
    fun twoDifferentCardsAreTwoDifferentRoots() {
        assertFalse(SourceRootIdentity.namesSameDirectory(pathRoot, "/storage/1234-5678/ROMs"))
        assertFalse(
            SourceRootIdentity.namesSameDirectory(
                safRoot,
                "content://com.android.externalstorage.documents/tree/1234-5678%3AROMs",
            ),
        )
    }

    /**
     * This is what [FriendlyPath] cannot do: it renders EVERY removable volume as the words "SD card",
     * so it would call the two cards above the same folder. Pinned side by side so a later "just reuse
     * FriendlyPath" refactor fails here rather than on a device.
     */
    @Test
    fun friendlyLabelsCollideWhereCanonicalFormDoesNot() {
        assertEquals(
            FriendlyPath.of("/storage/6A7F-FCFE/ROMs"),
            FriendlyPath.of("/storage/1234-5678/ROMs"),
        )
        assertFalse(
            SourceRootIdentity.namesSameDirectory("/storage/6A7F-FCFE/ROMs", "/storage/1234-5678/ROMs"),
        )
    }

    @Test
    fun everySpellingOfInternalStorageIsOneRoot() {
        val forms = listOf(
            "/storage/emulated/0/Roms",
            "/sdcard/Roms",
            "content://com.android.externalstorage.documents/tree/primary%3ARoms",
        )
        forms.forEach { form ->
            assertEquals("canonical of $form", "/storage/emulated/0/Roms", SourceRootIdentity.canonicalOf(form))
        }
    }

    @Test
    fun internalStoragePrefixDoesNotSwallowASimilarlyNamedPath() {
        assertEquals("/storage/emulated/01/Roms", SourceRootIdentity.canonicalOf("/storage/emulated/01/Roms"))
        assertEquals("/sdcardish/Roms", SourceRootIdentity.canonicalOf("/sdcardish/Roms"))
    }

    @Test
    fun trailingSlashesAndDotSegmentsDoNotMakeANewFolder() {
        assertTrue(SourceRootIdentity.namesSameDirectory(pathRoot, "/storage/6A7F-FCFE/ROMs/"))
        assertTrue(
            SourceRootIdentity.namesSameDirectory(
                pathRoot,
                "content://com.android.externalstorage.documents/tree/6A7F-FCFE%3AROMs%2F",
            ),
        )
    }

    @Test
    fun percentEncodedSubfoldersDecodeToTheirRealPath() {
        assertEquals(
            "/storage/6A7F-FCFE/ROMs/Game Boy",
            SourceRootIdentity.canonicalOf(
                "content://com.android.externalstorage.documents/tree/6A7F-FCFE%3AROMs%2FGame%20Boy",
            ),
        )
    }

    // ── Non-ASCII folder names ───────────────────────────────────────────────────────────────────

    /**
     * **The duplicate-root defect, in its non-ASCII spelling.** Not every provider percent-encodes, so a
     * tree URI can carry a literal `ゲーム` in its document id. The decoder used to write each un-escaped
     * character with `ByteArrayOutputStream.write(Char.code)`, whose `int` overload keeps only bits 0-7 —
     * so `あ` (U+3042) went in as `0x42` = `B`, and `ゲ` (U+30B2) as `0xB2`, a lone UTF-8 continuation
     * byte that decodes to U+FFFD.
     *
     * The two spellings of one Japanese-named folder therefore canonicalised differently, both roots
     * registered, and every ROM underneath was indexed twice — the exact 2026-08-28 defect this object
     * exists to prevent, reappearing for anyone whose folder is not named in ASCII.
     */
    @Test
    fun theTwoSpellingsOfANonAsciiFolderAreOneDirectory() {
        val literalSaf = "content://com.android.externalstorage.documents/tree/6A7F-FCFE%3Aゲーム"
        val path = "/storage/6A7F-FCFE/ゲーム"

        assertEquals("/storage/6A7F-FCFE/ゲーム", SourceRootIdentity.canonicalOf(literalSaf))
        assertEquals("/storage/6A7F-FCFE/ゲーム", SourceRootIdentity.canonicalOf(path))
        assertTrue(SourceRootIdentity.namesSameDirectory(literalSaf, path))
    }

    /** A provider that encodes only SOME of a name must land on the same folder as one that encodes all of it. */
    @Test
    fun aPartlyEncodedNameIsTheSameFolderAsTheFullyEncodedOne() {
        val fullyEncoded =
            "content://com.android.externalstorage.documents/tree/6A7F-FCFE%3A%E3%82%B2%E3%83%BC%E3%83%A0"
        val partlyEncoded =
            "content://com.android.externalstorage.documents/tree/6A7F-FCFE%3Aゲ%E3%83%BC%E3%83%A0"

        assertEquals("/storage/6A7F-FCFE/ゲーム", SourceRootIdentity.canonicalOf(fullyEncoded))
        assertEquals("/storage/6A7F-FCFE/ゲーム", SourceRootIdentity.canonicalOf(partlyEncoded))
        assertTrue(SourceRootIdentity.namesSameDirectory(fullyEncoded, partlyEncoded))
    }

    /**
     * Latin-1 is corrupted too, not just the codepoints above U+00FF: `é` (U+00E9) was written as the
     * single byte `0xE9`, which is not valid UTF-8 on its own and decodes to U+FFFD.
     */
    @Test
    fun anAccentedNameSurvivesCanonicalisation() {
        assertEquals(
            "/storage/6A7F-FCFE/Jeux vidéo",
            SourceRootIdentity.canonicalOf(
                "content://com.android.externalstorage.documents/tree/6A7F-FCFE%3AJeux%20vidéo",
            ),
        )
    }

    /**
     * **The surrogate discriminator, and the load-bearing negative of this whole file.** A `Char` is a
     * UTF-16 code unit, so `🎮` (U+1F3AE) arrives as the PAIR U+D83C U+DFAE. Encoding each half on its own
     * — `char.toString().toByteArray(UTF_8)`, the obvious one-line fix — emits `?` for each unpaired
     * surrogate, so `🎮` and `🎯` would BOTH render `??` and compare EQUAL.
     *
     * That is the same fold in the opposite direction: not two rows for one folder, but one row for two
     * folders, which loses a real source. The decoder must therefore convert whole RUNS of un-escaped
     * characters, keeping a surrogate pair together. This test fails against the pre-fix code (both
     * names corrupt to `<` + U+FFFD) and against the per-character fix alike.
     */
    @Test
    fun twoDifferentAstralNamesAreNeverTheSameDirectory() {
        val gamepad = "content://com.android.externalstorage.documents/tree/6A7F-FCFE%3A🎮"
        val target = "content://com.android.externalstorage.documents/tree/6A7F-FCFE%3A🎯"

        assertEquals("/storage/6A7F-FCFE/🎮", SourceRootIdentity.canonicalOf(gamepad))
        assertEquals("/storage/6A7F-FCFE/🎯", SourceRootIdentity.canonicalOf(target))
        assertFalse(SourceRootIdentity.namesSameDirectory(gamepad, target))
    }

    /** A literal astral character and its percent-encoded spelling are one folder. */
    @Test
    fun aLiteralAstralNameMatchesItsEncodedSpelling() {
        val literal = "content://com.android.externalstorage.documents/tree/6A7F-FCFE%3A🎮"
        val encoded = "content://com.android.externalstorage.documents/tree/6A7F-FCFE%3A%F0%9F%8E%AE"

        assertTrue(SourceRootIdentity.namesSameDirectory(literal, encoded))
        assertTrue(SourceRootIdentity.namesSameDirectory(literal, "/storage/6A7F-FCFE/🎮"))
    }

    // ── The nullness law ─────────────────────────────────────────────────────────────────────────

    /**
     * A percent-escape is exactly two HEX DIGITS. `String.toIntOrNull(16)` also accepts a leading sign,
     * so `%-1` parsed as `-1` and `ByteArrayOutputStream.write(-1)` appended the byte `0xFF` — a byte
     * that appears nowhere in the input. Refuse instead: null is "no opinion", and [namesSameDirectory]
     * then falls back to an exact string comparison rather than inventing a canonical form.
     */
    @Test
    fun aMalformedPercentEscapeHasNoOpinion() {
        listOf("%-1", "%+5", "%1G").forEach { escape ->
            val uri = "content://com.android.externalstorage.documents/tree/6A7F-FCFE%3AROMs$escape"
            assertNull("$uri must not canonicalise", SourceRootIdentity.canonicalOf(uri))
        }
    }


    @Test
    fun anUncanonicalisableRootHasNoOpinion() {
        listOf(
            "content://com.some.other.provider/tree/whatever",
            "content://com.android.externalstorage.documents/tree/home%3ADocs",
            "relative/not/absolute",
            "",
        ).forEach { assertNull("$it must not canonicalise", SourceRootIdentity.canonicalOf(it)) }
    }

    /**
     * **The load-bearing negative.** Two roots that both fail to canonicalise are NOT the same folder.
     * If null matched null, every unrecognised provider would fold into one root and registration would
     * start overwriting real, distinct sources with each other.
     */
    @Test
    fun twoUnknownProvidersAreNeverTheSameDirectory() {
        val a = "content://com.example.cloud/tree/alpha"
        val b = "content://com.example.cloud/tree/beta"

        assertNull(SourceRootIdentity.canonicalOf(a))
        assertNull(SourceRootIdentity.canonicalOf(b))
        assertFalse(SourceRootIdentity.namesSameDirectory(a, b))
    }

    /** An unknown root is still identical to itself — exact-string fallback, not canonical matching. */
    @Test
    fun anUnknownProviderStillMatchesItselfExactly() {
        val opaque = "content://com.example.cloud/tree/alpha"
        assertTrue(SourceRootIdentity.namesSameDirectory(opaque, opaque))
    }
}
