package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.files.DiscSetMoveOption
import com.gruning.mimir.rewrite.core.files.DiscSetWarning
import com.gruning.mimir.rewrite.core.files.discSetMoveOptions
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusScope
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The multi-disc warning's presentation rules — policy 2026-08-16.
 *
 * Wording is pinned here because on this screen the words ARE the feature: the whole point of warning
 * before the move rather than reporting after it is that the player can act, and they can only act on a
 * sentence that says what actually happens. A test that only checked the rows existed would pass while
 * the screen said "part of a multi-disc group", which is what the data says and not what a player owns.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class FileBrowserDiscSetChoiceTest {
    private val english = filesEnglish()

    private fun warning(
        canTravel: Boolean = true,
        discNumber: Int = 2,
        discCount: Int = 3,
    ) = DiscSetWarning(
        title = "Final Fantasy IX",
        discNumber = discNumber,
        discCount = discCount,
        subjectPath = "/sd/ps1/FF9/FF9 (Disc 2).chd",
        setPaths = listOf("/sd/ps1/FF9/FF9.m3u", "/sd/ps1/FF9/FF9 (Disc 2).chd"),
        wholeSetCanTravel = canTravel,
    )

    @Test
    fun `the headline names the disc and the game, in the player's words`() {
        assertEquals(
            "\"Disc 2 of 3\" is what the player owns; \"part of a multi-disc group\" is what the data says",
            "Disc 2 of 3 — Final Fantasy IX",
            fileBrowserDiscSetHeadline(english, warning()),
        )
    }

    @Test
    fun `an unnumbered disc degrades rather than inventing Disc 0`() {
        assertEquals(
            "M3uPlaylistResolver numbers by playlist order and only for files it resolved, so 0 means " +
                "\"in the set, position unknown\" -- and a screen that printed \"Disc 0 of 3\" would be " +
                "read as a bug by the one player who sees it",
            "One of 3 discs — Final Fantasy IX",
            fileBrowserDiscSetHeadline(english, warning(discNumber = 0)),
        )
    }

    @Test
    fun `the warning says what breaks, and says it differently when nothing can fix it`() {
        val repairable = fileBrowserDiscSetDetail(english, warning())
        assertTrue("names the mechanism: the playlist points at names", repairable.contains("playlist"))
        assertTrue("and the consequence, in plain words", repairable.contains("stops working"))

        val doomed = fileBrowserDiscSetDetail(english, warning(canTravel = false))
        assertTrue(
            "when the playlist names fixed locations the game breaks wherever the discs go, and the " +
                "screen must say Mimir cannot fix it rather than quietly dropping the repair row and " +
                "leaving the player to notice",
            doomed.contains("can't repair"),
        )
        assertFalse("the two are not the same sentence", repairable == doomed)
    }

    @Test
    fun `a set with no playlist gets its own sentence, never the playlist one`() {
        val gamecube = warning(discCount = 2).copy(hasPlaylist = false)
        val detail = fileBrowserDiscSetDetail(english, gamecube)

        assertFalse(
            "**his GameCube sets have no .m3u at all** -- Resident Evil (USA) (Disc 1)/(Disc 2).iso with " +
                "nothing else in the folder. Saying \"its playlist points at the other discs\" there " +
                "describes a file that does not exist, and the player who goes looking for it learns the " +
                "warning is guessing.",
            detail.contains("playlist"),
        )
        assertTrue(
            "what actually breaks is the disc swap: the emulator asks for the next disc and it is in " +
                "another folder now. Same loss as a broken playlist, different mechanism, so the sentence " +
                "has to name the mechanism the player can check.",
            detail.contains("stops when it asks for it"),
        )
        assertFalse(
            "and it is not the same sentence as either playlist case",
            detail == fileBrowserDiscSetDetail(english, warning()) ||
                detail == fileBrowserDiscSetDetail(english, warning(canTravel = false)),
        )
    }

    @Test
    fun `a set already spread across folders is never told its discs are in this folder`() {
        val split = DiscSetWarning(
            title = "Final Fantasy IX",
            discNumber = 2,
            discCount = 2,
            subjectPath = "/sd/ps1/a/FF9 (Disc 2).chd",
            setPaths = listOf("/sd/ps1/a/FF9 (Disc 1).chd", "/sd/ps1/b/FF9 (Disc 2).chd"),
            hasPlaylist = false,
            wholeSetCanTravel = true,
        )
        assertTrue("the fact is derived from the paths, not plumbed", split.spansFolders)

        val detail = fileBrowserDiscSetDetail(english, split)
        assertFalse(
            "The single-folder sentence is false in this case. The screen's copy " +
                "reading a single-folder GameCube set, and \"The other discs stay in this folder\" is " +
                "true there and false the moment they do not. A warning that states something the player " +
                "can walk into a folder and disprove teaches him the whole screen is guessing -- which " +
                "is the same failure as warning when nothing breaks, arriving by a different road.",
            detail.contains("stay in this folder"),
        )
        assertTrue(
            "and it says what is actually true: this set is ALREADY broken, so the co-move is not a " +
                "precaution, it is the repair",
            detail.contains("already in different folders"),
        )
    }

    @Test
    fun `the single-folder sentence remains unchanged`() {
        val together = warning(discCount = 2).copy(
            hasPlaylist = false,
            subjectPath = "/sd/gc/Resident Evil (USA) (Disc 2).iso",
            setPaths = listOf(
                "/sd/gc/Resident Evil (USA) (Disc 1).iso",
                "/sd/gc/Resident Evil (USA) (Disc 2).iso",
            ),
        )
        assertFalse("one folder", together.spansFolders)
        assertEquals(
            "the single-folder case keeps its existing copy",
            "The other discs stay in this folder. Move this one alone and the game stops when it asks for it.",
            fileBrowserDiscSetDetail(english, together),
        )
    }

    @Test
    fun `the move-all row stops promising a playlist that is not there`() {
        val gamecube = warning(discCount = 2).copy(hasPlaylist = false)

        assertFalse(
            "the row's whole job is to say what arrives with them, and a playlist does not",
            fileBrowserDiscSetDetail(english, DiscSetMoveOption.MOVE_WHOLE_SET, gamecube).contains("playlist"),
        )
        assertTrue(
            "it still has to say why the offer is worth taking",
            fileBrowserDiscSetDetail(english, DiscSetMoveOption.MOVE_WHOLE_SET, gamecube).contains("keeps working"),
        )
        assertTrue(
            "and the offer is still made -- with no playlist to invalidate, moving them together ALWAYS " +
                "works, so this is the case where the repair is most certainly a repair",
            discSetMoveOptions(gamecube).contains(DiscSetMoveOption.MOVE_WHOLE_SET),
        )
    }

    @Test
    fun `neither version asks the player if they are sure`() {
        listOf(warning(), warning(canTravel = false)).forEach { subject ->
            val text = fileBrowserDiscSetHeadline(english, subject) + " " + fileBrowserDiscSetDetail(english, subject)
            assertFalse(
                "a confirmation that merely asks for the press again is the pattern already " +
                    "removed from delete. The player is told what happens, once, then given the presses.",
                text.contains("Are you sure", ignoreCase = true) || text.contains("really", ignoreCase = true),
            )
        }
    }

    @Test
    fun `the move-all row names the disc count and promises the playlist comes too`() {
        val subject = warning(discCount = 4)
        assertEquals("Move all 4 discs", fileBrowserDiscSetLabel(english, DiscSetMoveOption.MOVE_WHOLE_SET, subject))
        assertTrue(
            "\"all 4 discs\" is a count the player can check against the folder and find wrong -- the " +
                "playlist is a fifth file, and it is the one that has to arrive",
            fileBrowserDiscSetDetail(english, DiscSetMoveOption.MOVE_WHOLE_SET, subject).contains("playlist"),
        )
    }

    @Test
    fun `the move-one row states the consequence only when there is a better option beside it`() {
        assertTrue(
            "with the repair one row up, the difference between the two rows is the whole decision",
            fileBrowserDiscSetDetail(english, DiscSetMoveOption.MOVE_SUBJECT_ONLY, warning()).isNotBlank(),
        )
        assertEquals(
            "with no repair available, \"the game will stop working until you move the rest\" would be " +
                "advice the screen has already said cannot help -- the detail line above carries it once",
            "",
            fileBrowserDiscSetDetail(english, DiscSetMoveOption.MOVE_SUBJECT_ONLY, warning(canTravel = false)),
        )
    }

    @Test
    fun `cancel says what it does, and says nothing more`() {
        assertEquals("Leave it where it is", fileBrowserDiscSetLabel(english, DiscSetMoveOption.CANCEL, warning()))
        assertEquals(
            "a blank detail draws NOTHING rather than an empty line -- a reserved-but-empty line would " +
                "make the row look like it lost its text",
            "",
            fileBrowserDiscSetDetail(english, DiscSetMoveOption.CANCEL, warning()),
        )
    }

    @Test
    fun `every option has a label, so a new row cannot ship nameless`() {
        DiscSetMoveOption.entries.forEach { option ->
            assertTrue("$option", fileBrowserDiscSetLabel(english, option, warning()).isNotBlank())
        }
    }

    // ── focus ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `option keys round-trip, and belong to no other screen`() {
        DiscSetMoveOption.entries.forEach { option ->
            assertEquals(option, FileBrowserDiscSetFocus.optionOf(FileBrowserDiscSetFocus.optionKey(option)))
        }
        assertNull(
            "a transfer chooser key must not validate here: a remembered key from another screen that " +
                "resolved would press a row this one never drew",
            FileBrowserDiscSetFocus.optionOf(FileBrowserTransferFocus.optionKey(FileBrowserTransferKind.MOVE)),
        )
        assertNull(FileBrowserDiscSetFocus.optionOf(FileBrowserFocus.tileKey("/sd/x.chd")))
    }

    @Test
    fun `the keys are the rows, in render order`() {
        val rows = discSetMoveOptions(warning())
        assertEquals(rows.map(FileBrowserDiscSetFocus::optionKey), fileBrowserDiscSetKeys(rows))
    }

    @Test
    fun `rows are stacked, never side by side, so a horizontal press has no answer`() {
        val first = fileBrowserDiscSetOptionBounds(0)
        val second = fileBrowserDiscSetOptionBounds(1)
        assertEquals("full width, one column", first.left, second.left, 0f)
        assertEquals(first.right, second.right, 0f)
        assertTrue("and the second is strictly below the first", second.top >= first.bottom)
    }

    @Test
    fun `the tracker registers exactly this screen's rows and gives every one back`() {
        val authority = DefaultFocusAuthority()
        val scope = FocusScope("files/discset")
        val tracker = FileBrowserDiscSetFocusTracker()
        val moveAll = FileBrowserDiscSetFocus.optionKey(DiscSetMoveOption.MOVE_WHOLE_SET)
        val moveOne = FileBrowserDiscSetFocus.optionKey(DiscSetMoveOption.MOVE_SUBJECT_ONLY)

        tracker.update(authority, scope, discSetMoveOptions(warning()))
        authority.pushScope(scope)
        authority.requestFocus(moveAll)
        assertEquals("registered rows accept the ring", moveAll, authority.state.value.focused)

        // …then the same tracker driven with a two-row warning: the row that left must really leave.
        tracker.update(authority, scope, discSetMoveOptions(warning(canTravel = false)))
        authority.requestFocus(moveOne)
        authority.requestFocus(moveAll)
        assertEquals(
            "DefaultFocusAuthority.requestFocus is a SILENT no-op on an unregistered key (BUG#01), so " +
                "the ring simply stays put -- which is why a registration that outlived its row is " +
                "invisible until a player presses A on a row nobody is drawing",
            moveOne,
            authority.state.value.focused,
        )

        tracker.clear(authority)
        val settled = authority.state.value.focused
        DiscSetMoveOption.entries.forEach { option ->
            authority.requestFocus(FileBrowserDiscSetFocus.optionKey(option))
            assertEquals(
                "everything this tracker registered must be gone when the screen closes, or the browse " +
                    "scope inherits focus slots for rows nobody is looking at ($option)",
                settled,
                authority.state.value.focused,
            )
        }
    }

    // ── the arrival ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the warning dips in and never leaves the slot empty`() {
        assertTrue("it must animate at all", fileBrowserArrivalAnimates(FileBrowserDiscSetArrival))
        assertEquals(FileBrowserArrivalKind.SCREEN, FileBrowserDiscSetArrival.kind)
        assertEquals(
            "The warning transition snapped because the incoming tree started at alpha 0 while the " +
                "screen it replaced left composition in a single frame, so the content slot was " +
                "literally empty for two frames before anything appeared. Dipping from " +
                "FileBrowserGlideStartAlpha is the folio's own arrival and its own stated reason: a dip, " +
                "not a fade from nothing -- the surface is arriving, not being born.",
            FileBrowserGlideStartAlpha,
            fileBrowserArrivalStartAlpha(FileBrowserDiscSetArrival),
            0f,
        )
        assertEquals(
            "and it still does not move geometry -- a travelling entrance can clip a ring this screen " +
                "has no way to gate, so it retains the stationary arrival",
            0f,
            fileBrowserArrivalTravelDp(FileBrowserDiscSetArrival),
            0f,
        )
    }

    @Test
    fun `the warning arrives exactly as the chooser it replaces does`() {
        listOf(
            "extract" to FileBrowserExtractArrival,
            "transfer" to FileBrowserTransferArrival,
        ).forEach { (name, sibling) ->
            assertEquals(
                "one language, measured -- the design standing calls out \"where one surface glides and " +
                    "its sibling does not\" as the defect shape, and these three occupy the same slot ($name)",
                fileBrowserArrivalStartAlpha(sibling),
                fileBrowserArrivalStartAlpha(FileBrowserDiscSetArrival),
                0f,
            )
            assertEquals(sibling.kind, FileBrowserDiscSetArrival.kind)
        }
    }

    // ── the surface, as source text ──────────────────────────────────────────────────────────────────

    @Test
    fun `the screen uses the sanctioned three-part focus pattern and no Compose focus modifier`() {
        val source = featureSource("FileBrowserDiscSetSurface.kt").readText()

        assertTrue("mimirFocusable, the single FocusAuthority", source.contains("mimirFocusable(authority, key, controller.discSetScope"))
        assertTrue("the gesture leg", source.contains("detectTapGestures(onTap = { controller.handleDiscSetTap(key) })"))
        assertTrue(
            "and the semantics leg, which restores the accessibility click action a Compose click " +
                "modifier would have carried -- without it TalkBack lands on a label with no action",
            source.contains("onClick(label = label)"),
        )
        assertFalse(
            "never Compose's own focusable()/focusTarget(): this project has a single FocusAuthority",
            Regex("""\.focusable\(|focusTarget\(""").containsMatchIn(source),
        )
        assertFalse(
            "and never `clickable` beside mimirFocusable -- the defect the invariant gate cannot see " +
                "from shipped bytecode",
            source.contains(".clickable("),
        )
    }

    @Test
    fun `no hardcoded colour reaches this screen`() {
        val source = featureSource("FileBrowserDiscSetSurface.kt").readText()
        assertFalse(
            "palette-driven theming: a warning screen is exactly where someone reaches for a red literal",
            Regex("""Color\(0x|Color\.Red|#[0-9a-fA-F]{6}""").containsMatchIn(source),
        )
        assertTrue("it paints from the palette", source.contains("palette.textMuted"))
    }

    private fun featureSource(fileName: String): File = listOf(
        File("src/main/kotlin/com/gruning/mimir/rewrite/feature/files/$fileName"),
        File("feature-files/src/main/kotlin/com/gruning/mimir/rewrite/feature/files/$fileName"),
        File("../feature-files/src/main/kotlin/com/gruning/mimir/rewrite/feature/files/$fileName"),
    ).firstOrNull { it.isFile }
        ?: throw AssertionError("$fileName not reachable from the test working directory")
}
