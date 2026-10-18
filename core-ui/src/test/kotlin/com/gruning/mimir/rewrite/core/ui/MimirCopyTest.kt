package com.gruning.mimir.rewrite.core.ui

import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MimirCopyTest {
    @Test
    fun `release alpha media vocabulary stays natural for speech and authored composition`() {
        assertEquals("Customize media", MimirCopy.CustomizeMedia)
        assertEquals("Icon art", MimirCopy.IconArt)
        assertEquals("Hero art", MimirCopy.HeroArt)
        assertEquals("Hero Motion", MimirCopy.HeroMotion)
        assertEquals("Cartridge Hero", MimirCopy.CartridgeHero)
        assertEquals("Default", MimirCopy.Default)
        assertEquals("Saved choices", MimirCopy.SavedChoices)
        assertEquals("Search results", MimirCopy.SearchResults)
        assertEquals("In use", MimirCopy.InUse)
    }

    /**
     * Content-writing pass : "detected" was OUR word for what the scanner did.
     * "original" needs no explanation of scanning/identification at all — pins the wording so it can't
     * silently drift back to mechanism language.
     */
    @Test
    fun `the reset affordance names what a player has, not what the scanner did`() {
        assertEquals("Reset to original name", MimirCopy.ResetToOriginalName)
        assertFalse(MimirCopy.ResetToOriginalName.contains("detected"))
    }

    /**
     * the "no binding assigned" state read
     * "UNBOUND" in one Controller Mapping row list and "Not set" in the sibling Controller Shortcuts row
     * list, same file, same concept, two spellings. "Not set" was picked because it matches Settings'
     * sentence-case voice; pinned here so a future edit cannot quietly reintroduce ALL CAPS.
     */
    @Test
    fun `the controller mapping no-binding sentinel is sentence case, not a shout`() {
        assertEquals("Not set", MimirCopy.NoBindingAssigned)
        assertFalse(MimirCopy.NoBindingAssigned == MimirCopy.NoBindingAssigned.uppercase())
    }

    /**
     *  Search is Y across
     * the product. This lives beside the legend it constrains rather than in a picker test, because the
     * value of the shared binding is that no SECOND surface reintroduces `X SEARCH` later — the file manager's
     * `Y SEARCH` (`FileBrowserFooterHints.kt`) is the other half of the same statement and is pinned in
     * its own module.
     */
    @Test
    fun `no controller legend binds search to a key other than Y`() {
        val legends = listOf(
            MimirCopy.HintSearch,
            MimirCopy.HintFilters,
            MimirCopy.HintMedia,
            MimirCopy.HintMediaRemovable,
            MimirCopy.HintMediaPreviewable,
            MimirCopy.HintMediaRemovablePreviewable,
            MimirCopy.HintUpload,
            MimirCopy.HintOpen,
            MimirCopy.HintRetry,
            MimirCopy.HintStop,
            MimirCopy.HintRename,
        )
        legends.forEach { legend ->
            Regex("""([A-Z0-9]+) SEARCH""").findAll(legend).forEach { match ->
                assertEquals(legend, "Y", match.groupValues[1])
            }
        }
        // Guard the guard: the sweep must actually be looking at a legend that mentions search, or it
        // passes vacuously the day HintSearch stops naming the action at all.
        assertTrue(legends.any { it.contains(" SEARCH") })
    }

    @Test
    fun `controller legends and static visual projection use the capped interaction grammar`() {
        assertEquals("A EDIT · Y SEARCH · B BACK", MimirCopy.HintSearch)
        assertEquals("A CYCLE · B BACK", MimirCopy.HintFilters)
        assertEquals("A USE · B BACK", MimirCopy.HintMedia)
        assertEquals("A USE · X REMOVE · B BACK", MimirCopy.HintMediaRemovable)
        assertEquals("A CHOOSE · B BACK", MimirCopy.HintUpload)
        assertEquals("A OPEN · B BACK", MimirCopy.HintOpen)
        assertEquals("A RETRY · B BACK", MimirCopy.HintRetry)
        assertEquals("B STOP", MimirCopy.HintStop)
        assertEquals("CUSTOMIZE MEDIA", mimirStaticChrome(MimirCopy.CustomizeMedia))
        assertEquals("CARTRIDGE HERO", mimirStaticChrome(MimirCopy.CartridgeHero))
    }

    @Test
    fun `tome reference type roles stay compact and square safe`() {
        // Re-pinned to the capped scale . The previous values — 10/9/10/9/9/8,
        // 18/16, 12/11/10 — were the eighteen-size spread this scale replaced.
        assertEquals(11.sp, MimirTypeScale.RunicEyebrow.fontSize)
        assertEquals(9.sp, MimirTypeScale.RunicEyebrowCompact.fontSize)
        assertEquals(11.sp, MimirTypeScale.ControlHint.fontSize)
        assertEquals(9.sp, MimirTypeScale.ControlHintCompact.fontSize)
        assertEquals(9.sp, MimirTypeScale.MicroStatus.fontSize)
        assertEquals(9.sp, MimirTypeScale.MicroStatusCompact.fontSize)
        // TitleCaps now uses Body; its compact variant follows the same cap. See the ordering guard below.
        assertEquals(14.sp, MimirTypeScale.TitleCaps.fontSize)
        assertEquals(11.sp, MimirTypeScale.TitleCapsCompact.fontSize)
        assertEquals(11.sp, MimirTypeScale.MenuAction.fontSize)
        assertEquals(11.sp, MimirTypeScale.MenuActionCompact.fontSize)
        assertEquals(11.sp, MimirTypeScale.RailAction.fontSize)
        // A heading still outranks supporting ink. MenuAction vs ControlHint is deliberately NO LONGER
        // an ordering — the cap collapsed both onto Label, and asserting a gap that the shared cap removed
        // would just re-open the spread one role at a time.
        assertTrue(MimirTypeScale.OverlayTitle.fontSize > MimirTypeScale.MenuAction.fontSize)
        assertEquals(MimirTypeScale.ControlHint.fontSize, MimirTypeScale.MenuAction.fontSize)
    }

    /**
     * THE cap. five steps, and no surface may invent a size between them.
     *
     * This is the test that makes the type-scale invariant durable — the per-role assertions above would let someone
     * add a *new* role at 13sp without failing anything. Every style the scale exposes must land on a
     * step, so the spread cannot creep back one role at a time.
     */
    @Test
    fun `every type role sits on one of the five capped steps`() {
        val steps = setOf(
            MimirTypeStep.Display,
            MimirTypeStep.Heading,
            MimirTypeStep.Body,
            MimirTypeStep.Label,
            MimirTypeStep.Micro,
        )
        val roles = mapOf(
            "Title" to MimirTypeScale.Title,
            "TitleCaps" to MimirTypeScale.TitleCaps,
            "TitleCapsCompact" to MimirTypeScale.TitleCapsCompact,
            "OverlayTitle" to MimirTypeScale.OverlayTitle,
            "RuneAction" to MimirTypeScale.RuneAction,
            "Rail" to MimirTypeScale.Rail,
            "Row" to MimirTypeScale.Row,
            "Support" to MimirTypeScale.Support,
            "SupportCompact" to MimirTypeScale.SupportCompact,
            "Eyebrow" to MimirTypeScale.Eyebrow,
            "Pill" to MimirTypeScale.Pill,
            "RunicEyebrow" to MimirTypeScale.RunicEyebrow,
            "RunicEyebrowCompact" to MimirTypeScale.RunicEyebrowCompact,
            "ControlHint" to MimirTypeScale.ControlHint,
            "ControlHintCompact" to MimirTypeScale.ControlHintCompact,
            "MicroStatus" to MimirTypeScale.MicroStatus,
            "MicroStatusCompact" to MimirTypeScale.MicroStatusCompact,
            "MenuAction" to MimirTypeScale.MenuAction,
            "MenuActionCompact" to MimirTypeScale.MenuActionCompact,
            "MenuActionDense" to MimirTypeScale.MenuActionDense,
            "MenuActionDenseCompact" to MimirTypeScale.MenuActionDenseCompact,
            "RailAction" to MimirTypeScale.RailAction,
        )
        val offScale = roles.filterValues { it.fontSize !in steps }
        assertTrue(
            "These roles sit off the capped scale: ${offScale.keys.sorted()}. " +
                "Move them onto a MimirTypeStep; widening the cap requires a design decision.",
            offScale.isEmpty(),
        )
        assertEquals(22, roles.size)
    }

    /**
     * The paired-row invariant: a row's title and its subtext are the SAME size and the SAME weight,
     * separated by case and colour alone. This is the assertion most likely to be "fixed" by someone
     * who thinks subtext looks too heavy — which is why it states the invariant.
     */
    @Test
    fun `a row title and its subtext are typographically identical`() {
        assertEquals(MimirTypeScale.Row.fontSize, MimirTypeScale.Support.fontSize)
        assertEquals(MimirTypeScale.Row.fontWeight, MimirTypeScale.Support.fontWeight)
        assertEquals(MimirTypeStep.Body, MimirTypeScale.Row.fontSize)
    }

    /**
     * A "Compact" variant must never be LARGER than the role it compacts.
     *
     * Caught for real: the policy took TitleCaps 22 -> 14 while TitleCapsCompact sat at 18,
     * which would have made the fontScale-safe variant bigger than its base. Two surfaces
     * (AppShellScaffold, MimirShelfChrome) pick between such a pair on a `compact` flag, so the
     * inversion would have grown text exactly where the layout was already constrained.
     */
    @Test
    fun `no compact variant is larger than the role it compacts`() {
        val pairs = listOf(
            "TitleCaps" to (MimirTypeScale.TitleCaps to MimirTypeScale.TitleCapsCompact),
            "RunicEyebrow" to (MimirTypeScale.RunicEyebrow to MimirTypeScale.RunicEyebrowCompact),
            "ControlHint" to (MimirTypeScale.ControlHint to MimirTypeScale.ControlHintCompact),
            "MicroStatus" to (MimirTypeScale.MicroStatus to MimirTypeScale.MicroStatusCompact),
            "MenuAction" to (MimirTypeScale.MenuAction to MimirTypeScale.MenuActionCompact),
            "MenuActionDense" to (MimirTypeScale.MenuActionDense to MimirTypeScale.MenuActionDenseCompact),
        )
        val inverted = pairs.filter { (_, p) -> p.second.fontSize > p.first.fontSize }.map { it.first }
        assertTrue(
            "Compact variant is LARGER than its base for: $inverted",
            inverted.isEmpty(),
        )
    }

    /** Every role resolves to the product face; none may silently fall back to the platform's. */
    @Test
    fun `the type scale names the product typeface`() {
        assertEquals(MimirFonts.Sans, MimirTypeScale.Row.fontFamily)
        assertEquals(MimirFonts.Sans, MimirTypeScale.Support.fontFamily)
        assertEquals(MimirFonts.Sans, MimirTypeScale.Title.fontFamily)
        assertEquals(MimirFonts.Sans, MimirTypeScale.MicroStatus.fontFamily)
    }
}
