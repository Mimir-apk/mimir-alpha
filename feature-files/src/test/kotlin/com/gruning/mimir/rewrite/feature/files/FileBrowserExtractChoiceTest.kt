package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.files.ExtractDestinationPolicy
import com.gruning.mimir.rewrite.core.input.FocusKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the Extract chooser's projection — F-06's surface half, still unbuilt as of F-11.
 *
 * Every input here is built through the real [ExtractDestinationPolicy] rather than by hand, so these
 * tests cannot drift from the policy `core-files` actually applies — in particular from its four ways of
 * declining to suggest a platform folder, which `ExtractDestinationPolicyTest` owns.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class FileBrowserExtractChoiceTest {
    private val english = filesEnglish()

    private val archive = "/storage/emulated/0/Downloads/Golden Sun.zip"

    private fun options(
        platformId: String? = null,
        platformFolders: List<String> = emptyList(),
    ) = ExtractDestinationPolicy.options(
        archivePath = archive,
        platformId = platformId,
        platformFolders = platformFolders,
    )

    @Test
    fun `Extract here is always offered, and it is the policy's own destination`() {
        val rows = fileBrowserExtractOptions(options())

        val here = rows.single()
        assertEquals(FileBrowserExtractOptionKind.HERE, here.kind)
        assertEquals("Extract here", here.label(english))
        assertEquals(
            "the destination is the policy's, never re-derived here",
            ExtractDestinationPolicy.extractHereDestination(archive),
            here.destination,
        )
        assertEquals("the second line names a place, not a path to parse", "Golden Sun", here.detail)
    }

    @Test
    fun `a recognised archive offers its platform folder, named by the code this tree can strike`() {
        val rows = fileBrowserExtractOptions(options(platformId = "gba", platformFolders = listOf("/roms/GBA")))

        assertEquals(2, rows.size)
        val platform = rows[1]
        assertEquals(FileBrowserExtractOptionKind.PLATFORM_FOLDER, platform.kind)
        assertEquals("/roms/GBA", platform.destination)
        assertTrue(
            "the row must name the platform, not the raw id: label was '${platform.label(english)}'",
            platform.label(english).startsWith("Extract in ") && !platform.label(english).contains("gba"),
        )
    }

    @Test
    fun `an unrecognised archive offers no second row at all when no picker exists`() {
        val rows = fileBrowserExtractOptions(options(platformId = null, platformFolders = listOf("/roms/GBA")))

        assertEquals(
            "an 'Extract in...' that opens nothing is chrome over a missing screen; one honest row instead",
            listOf(FileBrowserExtractOptionKind.HERE),
            rows.map { it.kind },
        )
    }

    @Test
    fun `two candidate folders is a guess, so the policy declines and this offers no default either`() {
        val rows = fileBrowserExtractOptions(
            options(platformId = "gba", platformFolders = listOf("/roms/GBA", "/roms/GameBoyAdvance")),
        )

        assertEquals(
            "offering a WRONG platform folder is worse than offering none",
            listOf(FileBrowserExtractOptionKind.HERE),
            rows.map { it.kind },
        )
    }

    @Test
    fun `the picker row appears only when a folder picker exists, and carries no destination`() {
        val withoutPicker = fileBrowserExtractOptions(options())
        val withPicker = fileBrowserExtractOptions(options(), folderPickerAvailable = true)

        assertEquals(1, withoutPicker.size)
        assertEquals(2, withPicker.size)
        val picker = withPicker[1]
        assertEquals(FileBrowserExtractOptionKind.CHOOSE_FOLDER, picker.kind)
        assertEquals("Extract in…", picker.label(english))
        assertNull(
            "a null destination is what forces the caller through a picker instead of extracting somewhere",
            picker.destination,
        )
    }

    @Test
    fun `a real suggestion stays alongside the picker row so any folder remains reachable`() {
        val rows = fileBrowserExtractOptions(
            options(platformId = "gba", platformFolders = listOf("/roms/GBA")),
            folderPickerAvailable = true,
        )

        assertEquals(
            listOf(
                FileBrowserExtractOptionKind.HERE,
                FileBrowserExtractOptionKind.PLATFORM_FOLDER,
                FileBrowserExtractOptionKind.CHOOSE_FOLDER,
            ),
            rows.map { it.kind },
        )
    }

    @Test
    fun `one option is not a choice, so no chooser is drawn for it`() {
        assertFalse(fileBrowserExtractNeedsChooser(fileBrowserExtractOptions(options())))
        assertTrue(
            fileBrowserExtractNeedsChooser(
                fileBrowserExtractOptions(options(platformId = "gba", platformFolders = listOf("/roms/GBA"))),
            ),
        )
    }

    @Test
    fun `chooser keys live in their own namespace and round-trip`() {
        FileBrowserExtractOptionKind.entries.forEach { kind ->
            val key = FileBrowserExtractFocus.optionKey(kind)

            assertTrue(key.value.startsWith("files/extract/"))
            assertEquals(kind, FileBrowserExtractFocus.optionOf(key))
        }
        assertNull(FileBrowserExtractFocus.optionOf(null))
        assertNull(FileBrowserExtractFocus.optionOf(FileBrowserFocus.tileKey("/roms/Golden Sun.zip")))
        assertNull(FileBrowserExtractFocus.optionOf(FocusKey("files/extract/SOMEWHERE_ELSE")))
    }

    @Test
    fun `the chooser registers one key per drawn row`() {
        val rows = fileBrowserExtractOptions(options(platformId = "gba", platformFolders = listOf("/roms/GBA")))

        assertEquals(
            listOf(
                FileBrowserExtractFocus.optionKey(FileBrowserExtractOptionKind.HERE),
                FileBrowserExtractFocus.optionKey(FileBrowserExtractOptionKind.PLATFORM_FOLDER),
            ),
            fileBrowserExtractKeys(rows),
        )
    }
}
