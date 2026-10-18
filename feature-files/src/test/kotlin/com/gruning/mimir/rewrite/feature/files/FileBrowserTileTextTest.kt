package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.files.FileItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The pure halves of a file tile's name and meta ([FileBrowserTileText.kt]): where a file name may end a line, where it is cut, and what the meta line may shorten to. What is drawn from them is
 * [FileBrowserFileTileFitCaptureTest]'s; these are the tables.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class FileBrowserTileTextTest {
    private val english = filesEnglish()

    private val zwsp = "\u200B"

    @Test
    fun `a zero-width space follows each separator a line may end on`() {
        assertEquals("vqa17-${zwsp}mark", fileBrowserNameWithBreaks("vqa17-mark"))
        assertEquals("holiday_${zwsp}photo_${zwsp}final", fileBrowserNameWithBreaks("holiday_photo_final"))
        assertEquals("Screenshot_${zwsp}20261013-${zwsp}231455_${zwsp}Mimir", fileBrowserNameWithBreaks("Screenshot_20261013-231455_Mimir"))
        assertEquals("my.${zwsp}cover", fileBrowserNameWithBreaks("my.cover"))
    }

    @Test
    fun `no space follows a leading, trailing or doubled separator, a space, or a decimal point`() {
        assertEquals(".nomedia", fileBrowserNameWithBreaks(".nomedia"))
        assertEquals("cover-", fileBrowserNameWithBreaks("cover-"))
        assertEquals("a--b", fileBrowserNameWithBreaks("a--b"))
        assertEquals("Mario - World", fileBrowserNameWithBreaks("Mario - World"))
        assertEquals("v1.0-${zwsp}final", fileBrowserNameWithBreaks("v1.0-final"))
        assertEquals("a name", fileBrowserNameWithBreaks("a name"))
        assertEquals("", fileBrowserNameWithBreaks(""))
    }

    @Test
    fun `a name is cut after a separator or a space, longest head first, and a dangling separator is dropped`() {
        val name = "Screenshot_20261013-231455_Mimir"
        // Everything fits: the longest head wins.
        assertEquals("Screenshot_20261013-231455…", fileBrowserEllipsizeAtBoundary(name) { true })
        // Only a head of at most 19 characters (with its ellipsis) fits.
        assertEquals("Screenshot…", fileBrowserEllipsizeAtBoundary(name) { it.length <= 12 })
        assertEquals("Screenshot_20261013…", fileBrowserEllipsizeAtBoundary(name) { it.length <= 21 })
        assertNull("no cut fits", fileBrowserEllipsizeAtBoundary(name) { false })
        assertNull("a name with no separator has no cut", fileBrowserEllipsizeAtBoundary("Screenshotgalleryexport") { true })
        assertEquals("Super Mario…", fileBrowserEllipsizeAtBoundary("Super Mario World") { it.length <= 12 })
    }

    @Test
    fun `a cut never falls inside a number`() {
        assertNull("the dot in v1.5 is no place to cut", fileBrowserEllipsizeAtBoundary("v1.5-final") { it.length <= 3 })
        assertEquals("v1.5…", fileBrowserEllipsizeAtBoundary("v1.5-final") { it.length <= 5 })
        assertNull(fileBrowserEllipsizeAtBoundary("v1.5") { true })
    }

    @Test
    fun `a name is cut where a letter meets a digit, and never inside a run of digits or of letters`() {
        val camera = "IMG20261013WA0001"
        assertEquals("IMG20261013WA…", fileBrowserEllipsizeAtBoundary(camera) { true })
        assertEquals("IMG20261013…", fileBrowserEllipsizeAtBoundary(camera) { it.length <= 12 })
        assertEquals("IMG…", fileBrowserEllipsizeAtBoundary(camera) { it.length <= 6 })
        assertEquals("vqa18…", fileBrowserEllipsizeAtBoundary("vqa18screenshotlongname") { it.length <= 7 })
        assertEquals("vqa…", fileBrowserEllipsizeAtBoundary("vqa18screenshotlongname") { it.length <= 5 })
        assertNull("a run of digits has no cut", fileBrowserEllipsizeAtBoundary("20261013") { true })
        assertNull("a run of letters has no cut", fileBrowserEllipsizeAtBoundary("screenshot") { true })
    }

    @Test
    fun `a number and its ordinal suffix are one unit, and the break before the number stays`() {
        // (text, index a line may end before): true where the engine may end a line between text[index - 1] and text[index].
        assertTrue("Level | 19th_edition", fileBrowserLetterDigitBoundary("Level19th_edition", 5))
        assertFalse("19 | th is no place", fileBrowserLetterDigitBoundary("Level19th_edition", 7))
        assertFalse("2nd", fileBrowserLetterDigitBoundary("Disc2nd", 5))
        assertFalse("3RD, any case", fileBrowserLetterDigitBoundary("Disc3RD", 5))
        assertFalse("21st at the end of a name", fileBrowserLetterDigitBoundary("Disc21st", 6))
        assertFalse("21st before a dot", fileBrowserLetterDigitBoundary("Disc21st.png", 6))
        assertFalse("21st before a dash and before a space", fileBrowserLetterDigitBoundary("Disc21st-century", 6) || fileBrowserLetterDigitBoundary("Disc21st century", 6))
        assertFalse("21st before a digit", fileBrowserLetterDigitBoundary("Disc21st5", 6))
        assertFalse("11th", fileBrowserLetterDigitBoundary("Disc11th_run", 6))
        // A letter after the suffix makes it a word, not an ordinal: the number-letter boundary stands.
        assertTrue("3rdparty", fileBrowserLetterDigitBoundary("Disc3rdparty", 5))
        assertTrue("21stuff", fileBrowserLetterDigitBoundary("Disc21stuff", 6))
        assertTrue("12thing", fileBrowserLetterDigitBoundary("Disc12thing", 6))
        // The rest of the rule is as it was: letters then digits, digit then a word, nothing inside a run, nothing after a single leading character.
        assertTrue("IMG | 20261019", fileBrowserLetterDigitBoundary("IMG20261019", 3))
        assertTrue("2026 | WA", fileBrowserLetterDigitBoundary("IMG20261019WA", 11))
        assertFalse("inside the digits", fileBrowserLetterDigitBoundary("IMG20261019", 6))
        assertFalse("inside the letters", fileBrowserLetterDigitBoundary("screenshot18", 4))
        assertFalse("3rdparty after a single leading character", fileBrowserLetterDigitBoundary("3rdparty_logo", 1))
        assertFalse("v | 1.5", fileBrowserLetterDigitBoundary("v1.5", 1))
    }

    @Test
    fun `a name is never cut between a number and its ordinal suffix`() {
        val name = "Level19th_edition"
        assertEquals("the whole ordinal, then the cut", "Level19th…", fileBrowserEllipsizeAtBoundary(name) { it.length <= 10 })
        // Too narrow for "Level19th...": the cut falls before the number, never at "Level19...".
        assertEquals("Level…", fileBrowserEllipsizeAtBoundary(name) { it.length <= 9 })
        assertEquals("Disc21st…", fileBrowserEllipsizeAtBoundary("Disc21st.png") { it.length <= 9 })
        assertEquals("a word after the digits is no ordinal", "Disc21…", fileBrowserEllipsizeAtBoundary("Disc21stuff") { it.length <= 7 })
    }

    @Test
    fun `a deep path gives up its middle folders and keeps its volume and its last folder, longest form first`() {
        assertEquals("nothing to give up", emptyList<String>(), fileBrowserPathElisions("Internal Storage › Download"))
        assertEquals(listOf("A › … › C", "… › C"), fileBrowserPathElisions("A › B › C"))
        assertEquals(
            listOf("Internal Storage › … › Nintendo DS › Downloads", "Internal Storage › … › Downloads", "… › Downloads"),
            fileBrowserPathElisions("Internal Storage › Roms › Nintendo DS › Downloads"),
        )
    }

    @Test
    fun `the meta shortens to the size alone, never to a cut figure`() {
        val tile = FileBrowserTile(FileItem(name = "vqa17-mark.png", path = "/p/vqa17-mark.png", size = 3_072L), FileBrowserTileKind.FILE, machinery = false, platformIdValue = null)
        assertEquals(listOf("PNG · 3.0 KB", "3.0 KB"), fileBrowserTileMetaCandidates(english, tile))
        assertEquals("PNG · 3.0 KB", fileBrowserTileMeta(english, tile))
        assertEquals("PNG · 3.0 KB", fileBrowserFitTileMeta(fileBrowserTileMetaCandidates(english, tile)) { true })
        assertEquals("3.0 KB", fileBrowserFitTileMeta(fileBrowserTileMetaCandidates(english, tile)) { it.length <= 6 })
        assertEquals("the shortest is the last word, wrapped rather than cut", "3.0 KB", fileBrowserFitTileMeta(fileBrowserTileMetaCandidates(english, tile)) { false })
    }

    @Test
    fun `an archive, a file with no extension and a held delete have the one meta they always had`() {
        val archive = FileBrowserTile(FileItem(name = "backup.zip", path = "/p/backup.zip", size = 3_072L), FileBrowserTileKind.ARCHIVE, machinery = false, platformIdValue = null)
        assertEquals(listOf("3.0 KB"), fileBrowserTileMetaCandidates(english, archive))
        val bare = FileBrowserTile(FileItem(name = "README", path = "/p/README", size = 3_072L), FileBrowserTileKind.FILE, machinery = false, platformIdValue = null)
        assertEquals(listOf("3.0 KB"), fileBrowserTileMetaCandidates(english, bare))
        val deleting = FileBrowserTile(FileItem(name = "a.png", path = "/p/a.png", size = 3_072L, isPendingDelete = true), FileBrowserTileKind.FILE, machinery = false, platformIdValue = null)
        assertEquals(listOf(english.getString(FileBrowserDeletingLabel)), fileBrowserTileMetaCandidates(english, deleting))
    }
}
