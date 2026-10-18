package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.files.ExtractDestinationOptions
import com.gruning.mimir.rewrite.core.files.ExtractDestinationPolicy
import com.gruning.mimir.rewrite.core.files.FileItem
import com.gruning.mimir.rewrite.core.files.PlatformDestination
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * **F-06 — is the Extract chooser actually REACHABLE, and does the destination pick
 * unwind honestly?**
 *
 * The companion to `FileBrowserQueueWiringTest`, guarding the same class of failure it was written for:
 * three PRs running, the *rules* were pinned and the *wiring* was pinned by nothing, so deleting three
 * lines left a green suite over a dead feature. `FileBrowserExtractChoiceTest` already pins every row
 * rule against the real `ExtractDestinationPolicy`; this file pins that a press reaches them.
 *
 * **What a passing run here does and does not prove.** It drives a real [DefaultFocusAuthority], a real
 * [FileBrowserController] and real [ControllerAction] dispatches, so reachability, scope discipline,
 * focus landings and every sink invocation are genuinely exercised. It composes **nothing** — this module
 * configures no Compose and no Robolectric harness — so nothing here is evidence about pixels, about the
 * chooser's appearance, or about `FileBrowserSurface` having been wired at all. And it cannot
 * cross the module seam: the two sinks are constructor parameters filled in `app/`'s
 * `FileBrowserStore.mount()`, so **every test below installs its own fakes
 * and none of them prove the shipped browser has any.** That wiring remains an integration requirement,
 * and until it lands the whole feature is dead-by-default on device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class FileBrowserExtractWiringTest {
    private val english = filesEnglish()

    // A recognised archive next to an unrecognised one, in a tree with a real ROM folder to suggest.
    private val tree: Map<String, List<FileItem>> = mapOf(
        "/root" to listOf(
            FileItem(name = "Roms", path = "/root/Roms", isDirectory = true),
            FileItem(name = "Deep", path = "/root/Deep", isDirectory = true),
            FileItem(name = "Golden Sun.zip", path = "/root/Golden Sun.zip", extension = "zip"),
            FileItem(name = "mystery.zip", path = "/root/mystery.zip", extension = "zip"),
        ),
        "/root/Roms" to listOf(
            FileItem(name = "GBA", path = "/root/Roms/GBA", isDirectory = true),
        ),
        "/root/Roms/GBA" to listOf(
            FileItem(name = "existing.gba", path = "/root/Roms/GBA/existing.gba", extension = "gba"),
        ),
        // A second branch with an archive of its own, so a pick can BEGIN below the root -- the only
        // arrangement in which abandoning has a back-stack to restore. See the test that uses it.
        "/root/Deep" to listOf(
            FileItem(name = "deep.zip", path = "/root/Deep/deep.zip", extension = "zip"),
        ),
    )

    private val recognised = "/root/Golden Sun.zip"
    private val unrecognised = "/root/mystery.zip"

    private fun archiveTile() = FileBrowserTile(
        item = FileItem(name = "Golden Sun.zip", path = recognised, extension = "zip"),
        kind = FileBrowserTileKind.ARCHIVE,
        machinery = false,
        platformIdValue = null,
    )

    /** Every extract-to call this controller made, in order. */
    private val extracted = mutableListOf<Pair<String, String>>()

    /** Every legacy `onExtract` call — the pre-F-06 path. */
    private val extractedHere = mutableListOf<String>()

    /**
     * Resolved through the REAL [ExtractDestinationPolicy], not by hand, so these tests cannot drift from
     * the four ways it declines. `/root/Golden Sun.zip` is recognised as `gba` and the library has
     * exactly one GBA folder; `/root/mystery.zip` is recognised as nothing at all, which is decline
     * reason 1 and the case F-06's "option 2" has to answer for.
     */
    private fun options(archivePath: String): ExtractDestinationOptions = ExtractDestinationPolicy.options(
        archivePath = archivePath,
        platformId = if (archivePath == recognised) "gba" else null,
        platformFolders = listOf("/root/Roms/GBA"),
    )

    private fun controller(
        authority: DefaultFocusAuthority,
        mode: FileBrowserMode = FileBrowserMode.MANAGE,
        wired: Boolean = true,
        commitAllowed: (String) -> Boolean = { true },
        scope: FocusScope = FileBrowserFocus.DefaultScope,
        resolvePlatformDestinations: ((String) -> List<PlatformDestination>)? = null,
    ) = FileBrowserController(
        authority = authority,
        rootPath = "/root",
        mode = mode,
        listDirectory = { path -> tree[path].orEmpty() },
        onChoose = {},
        onDismiss = {},
        onExtract = { path -> extractedHere += path },
        resolveExtractOptions = if (wired) ::options else null,
        onExtractTo = if (wired) { archive, destination -> extracted += archive to destination } else null,
        commitAllowed = commitAllowed,
        scope = scope,
        resolvePlatformDestinations = resolvePlatformDestinations,
    )

    /**
     * Put the ring on a tile the way the player would, then reach the chooser.
     *
     * **F-1/R-13, 2026-08-18: via SELECT's action overlay, not a direct X.** X itself is now quiet on
     * every archive in MANAGE -- the overlay always carries an EXTRACT row for one, unconditionally on
     * `kind == ARCHIVE` regardless of wiring (`fileBrowserActionOverlayRows`' "Option B"; a stricter
     * reading that also gates the row on `extractChoiceAvailable` remains an open alternative).
     * This helper calls [FileBrowserController.handleInspectorSecondaryTap] -- the SAME production seam
     * the inspector's button now uses -- so callers below are still exercising a real, reachable path, not
     * a test-only shortcut. The two tests that are actually ABOUT X's own binding drive SECONDARY directly
     * instead of through here.
     */
    private fun FileBrowserController.pressExtractOn(path: String) {
        val authority = authorityOf(this)
        authority.requestFocus(FileBrowserFocus.tileKey(path))
        handleInspectorSecondaryTap()
        if (authority.state.value.scope == actionOverlayScope) {
            authority.requestFocus(
                FileBrowserActionOverlayFocus.optionKey(FileBrowserActionOverlayRowKind.EXTRACT),
            )
            dispatch(ControllerAction.CONFIRM)
        }
    }

    // The controller does not expose its authority; every test holds its own and passes it in, so this
    // is a lookup rather than a reach-through. Kept as one map so the helper above reads cleanly.
    private val authorities = mutableMapOf<FileBrowserController, DefaultFocusAuthority>()
    private fun authorityOf(c: FileBrowserController) = authorities.getValue(c)
    private fun open(
        mode: FileBrowserMode = FileBrowserMode.MANAGE,
        wired: Boolean = true,
        commitAllowed: (String) -> Boolean = { true },
        scope: FocusScope = FileBrowserFocus.DefaultScope,
        resolvePlatformDestinations: ((String) -> List<PlatformDestination>)? = null,
    ): Pair<FileBrowserController, DefaultFocusAuthority> {
        val authority = DefaultFocusAuthority()
        val c = controller(authority, mode, wired, commitAllowed, scope, resolvePlatformDestinations)
        authorities[c] = authority
        c.open()
        return c to authority
    }

    // ─────────────────────────────────────────── 1 · the gate fails closed (D-1) ──

    @Test
    fun `with no sinks wired, X still reaches the legacy onExtract sink through the overlay`() {
        val (c, authority) = open(wired = false)

        assertFalse(
            "extractChoiceAvailable must be false with no resolver -- same polarity as deleteAvailable, " +
                "so an unwired browser cannot advertise a chooser that opens nothing",
            c.extractChoiceAvailable,
        )
        authority.requestFocus(FileBrowserFocus.tileKey(recognised))
        c.dispatch(ControllerAction.SECONDARY)
        assertEquals(
            "D-3: X is the overlay's door now, and an ARCHIVE always has an " +
                "EXTRACT row (Option B) -- unlike deleteAvailable/transferChoiceAvailable, wiring does not " +
                "remove the row, only what pressing it does once inside",
            FileBrowserCommand.OpenActionOverlay(archiveTile().path, archiveTile().kind),
            fileBrowserCommand(action = ControllerAction.SECONDARY, focused = archiveTile(), atRoot = true, actionsAvailable = true),
        )
        assertTrue("nothing reached the legacy sink from the swallowed press alone", extractedHere.isEmpty())

        // The SAME sink is still reachable -- SELECT, then the overlay's one row (EXTRACT is the only row
        // an unwired browser's archive carries; delete/transfer are both off in this fixture).
        c.dispatch(ControllerAction.SECONDARY)
        authority.requestFocus(FileBrowserActionOverlayFocus.optionKey(FileBrowserActionOverlayRowKind.EXTRACT))
        c.dispatch(ControllerAction.CONFIRM)

        assertFalse("no CHOOSER may open -- there is still nothing to resolve rows with", c.extractChooserOpen)
        assertEquals(
            "the overlay's EXTRACT row falls through to the SAME legacy onExtract sink, byte-identically " +
                "to the pre-F-1/pre-PR#141 press, once extractChoiceAvailable is false",
            listOf(recognised),
            extractedHere,
        )
        assertTrue("and nothing may reach the destination sink", extracted.isEmpty())
        c.close()
    }

    @Test
    fun `half-wired is unwired -- a resolver with no performer must not open a chooser`() {
        val authority = DefaultFocusAuthority()
        val c = FileBrowserController(
            authority = authority,
            rootPath = "/root",
            mode = FileBrowserMode.MANAGE,
            listDirectory = { path -> tree[path].orEmpty() },
            onChoose = {},
            onDismiss = {},
            onExtract = { path -> extractedHere += path },
            resolveExtractOptions = ::options,
            onExtractTo = null,
        )
        authorities[c] = authority
        c.open()

        assertFalse(
            "both sinks or neither: a resolver alone would draw two rows, take focus, advertise a verb " +
                "and do nothing on A",
            c.extractChoiceAvailable,
        )
        c.pressExtractOn(recognised)
        assertFalse(c.extractChooserOpen)
        assertEquals(listOf(recognised), extractedHere)
        c.close()
    }

    @Test
    fun `the pure gate is what makes it incapable -- no other argument produces OfferExtract`() {
        val archive = archiveTile()
        listOf(true, false).forEach { atRoot ->
            listOf(true, false).forEach { searchAvailable ->
                // F-1: swept in place of the retired `deleteAvailable` parameter. `queueAvailable` is
                // exactly as orthogonal to SECONDARY as `deleteAvailable` was -- neither is read by that
                // arm -- and unlike `actionsAvailable` (which this SAME arm now gates on, R-13) it cannot
                // flip the expected command to Consumed and silently launder a real regression through
                // this sweep.
                listOf(true, false).forEach { queueAvailable ->
                    val command = fileBrowserCommand(
                        action = ControllerAction.SECONDARY,
                        focused = archive,
                        atRoot = atRoot,
                        searchAvailable = searchAvailable,
                        queueAvailable = queueAvailable,
                        // extractChoiceAvailable and actionsAvailable left at their false defaults
                    )
                    assertEquals(
                        "with the gate false there must be NO combination that offers a chooser",
                        FileBrowserCommand.Extract(recognised),
                        command,
                    )
                }
            }
        }
    }

    @Test
    fun `the inspector Extract button opens the extract chooser directly and keeps browse reachable`() {
        val (c, authority) = open(
            resolvePlatformDestinations = {
                listOf(
                    PlatformDestination(
                        platformId = "gba",
                        path = "/root/Roms/GBA",
                        fromLibrary = true,
                        recognised = true,
                        onSubjectVolume = true,
                    ),
                )
            },
        )
        authority.requestFocus(FileBrowserFocus.tileKey(recognised))

        c.handleInspectorExtractTap()

        assertFalse("the direct inspector button must not open MANAGE's action overlay", c.actionOverlayOpen)
        val chooser = c.extractChooser
        assertNotNull(chooser)
        assertEquals(
            listOf(
                FileBrowserExtractOptionKind.HERE,
                FileBrowserExtractOptionKind.PLATFORM_FOLDER,
                FileBrowserExtractOptionKind.CHOOSE_FOLDER,
            ),
            chooser!!.rows.map { it.kind },
        )
        assertEquals("/root/Roms/GBA", chooser.rows[1].destination)

        authority.requestFocus(
            FileBrowserExtractFocus.optionKey(FileBrowserExtractOptionKind.CHOOSE_FOLDER),
        )
        c.dispatch(ControllerAction.CONFIRM)

        assertTrue("the explicit manual-browse path remains reachable", c.destinationListOpen)
        assertEquals(
            "Browse for a folder instead…",
            fileBrowserDestinationRowLabel(english, c.destinationList!!.rows.first()),
        )
        assertEquals(
            "Extract Golden Sun.zip to…",
            fileBrowserDestinationContext(english, recognised),
        )
        c.close()
    }

    @Test
    fun `the direct inspector Extract entry refuses a foreign scope and an open overlay`() {
        val (c, authority) = open()
        authority.requestFocus(FileBrowserFocus.tileKey(recognised))
        authority.pushScope(FocusScope("files/foreign"))

        c.handleInspectorExtractTap()

        assertFalse(c.extractChooserOpen)
        assertFalse(c.actionOverlayOpen)
        authority.popScope()

        authority.requestFocus(FileBrowserFocus.tileKey(recognised))
        c.handleInspectorSecondaryTap()
        assertTrue(c.actionOverlayOpen)
        c.handleInspectorExtractTap()

        assertTrue("an open generic overlay owns the press", c.actionOverlayOpen)
        assertFalse(c.extractChooserOpen)
        c.close()
    }

    // ───────────────────────────────── 2 · wired, every archive gets explicit destination browse (D-5) ──

    @Test
    fun `a RECOGNISED archive shows the smart folder and an explicit browse row`() {
        val (c, authority) = open()
        c.pressExtractOn(recognised)

        val state = c.extractChooser
        assertNotNull("X on an archive must open the chooser once the sinks are wired", state)
        assertEquals(
            listOf(
                FileBrowserExtractOptionKind.HERE,
                FileBrowserExtractOptionKind.PLATFORM_FOLDER,
                FileBrowserExtractOptionKind.CHOOSE_FOLDER,
            ),
            state!!.rows.map { it.kind },
        )
        assertEquals(
            "row 2's destination is the REAL library folder, resolved by the policy, not invented here",
            "/root/Roms/GBA",
            state.rows[1].destination,
        )
        assertEquals(
            "the chooser's own scope must be the active one, or the D-pad is driving the folio underneath",
            c.extractScope,
            authority.state.value.scope,
        )
        c.close()
    }

    @Test
    fun `an UNRECOGNISED archive ALSO shows two rows -- the second is the folder picker (D-5)`() {
        val (c, _) = open()
        c.pressExtractOn(unrecognised)

        val state = c.extractChooser
        assertNotNull("Mimir cannot name the platform", state)
        assertEquals(
            "option 2 must still exist -- as the PICKER, because this browser IS one now. Before PR #141 " +
                "this archive got one row and extracted immediately.",
            listOf(FileBrowserExtractOptionKind.HERE, FileBrowserExtractOptionKind.CHOOSE_FOLDER),
            state!!.rows.map { it.kind },
        )
        assertNull(
            "and it carries NO destination -- that null is what forces the picker route",
            state.rows[1].destination,
        )
        c.close()
    }

    @Test
    fun `PICK mode gets the chooser too -- extraction writes files and removes none`() {
        val (c, _) = open(mode = FileBrowserMode.PICK)

        assertTrue(
            "gating this to MANAGE by reflex would take from PICK a capability PICK already had: " +
                "FileBrowserStore.extractOptions is explicitly available in BOTH modes",
            c.extractChoiceAvailable,
        )
        c.pressExtractOn(recognised)
        assertTrue(c.extractChooserOpen)
        c.close()
    }

    @Test
    fun `the chooser scope is files-slash-extract for the default browse scope, and derives for a custom one`() {
        val (c, _) = open()
        assertEquals(
            "satisfies the brief's 'push FileBrowserExtractFocus.Scope' literally, rather than by two " +
                "string literals in different files that happen to agree",
            FileBrowserExtractFocus.Scope,
            c.extractScope,
        )
        c.close()

        val (other, _) = open(scope = FocusScope("files-2"))
        assertEquals(
            "a caller mounting two browsers at once must get two distinct chooser graphs",
            FocusScope("files-2/extract"),
            other.extractScope,
        )
        other.close()
    }

    // ─────────────────────────────── 3 · the chooser acts on its OWN archive (D-2) ──

    @Test
    fun `activating a row extracts the CHOOSER's archive, never whatever the ring is on`() {
        val (c, authority) = open()
        c.pressExtractOn(recognised)
        // Move the browse ring onto the OTHER archive while the chooser is up. The chooser's own scope is
        // active, so this writes the browse scope's memory underneath it -- exactly the drift a
        // re-read-the-focused-tile implementation would pick up.
        authority.rememberFocusForScope(c.scope, FileBrowserFocus.tileKey(unrecognised))

        authority.requestFocus(FileBrowserExtractFocus.optionKey(FileBrowserExtractOptionKind.HERE))
        c.dispatch(ControllerAction.CONFIRM)

        assertEquals(
            "the archive is carried on the chooser state; reading the ring would extract the wrong file",
            listOf(recognised to "/root/Golden Sun"),
            extracted,
        )
        assertFalse("and the chooser closes behind the press", c.extractChooserOpen)
        c.close()
    }

    @Test
    fun `the platform row extracts straight to the library folder`() {
        val (c, authority) = open()
        c.pressExtractOn(recognised)
        authority.requestFocus(
            FileBrowserExtractFocus.optionKey(FileBrowserExtractOptionKind.PLATFORM_FOLDER),
        )
        c.dispatch(ControllerAction.CONFIRM)

        assertEquals(listOf(recognised to "/root/Roms/GBA"), extracted)
        assertNull("no destination pick is needed -- this row carries its own", c.destinationPick)
        c.close()
    }

    @Test
    fun `a malformed chooser key degrades to nothing, never to another row's action`() {
        val (c, authority) = open()
        // The recognised archive now carries all three legal kinds. A malformed key still must not
        // silently map to one of them if a registration ever leaks across chooser lifetimes. Register it
        // by hand precisely because the tracker does not leak today: an unregistered key cannot take
        // focus at all, so the guard is otherwise unreachable.
        c.pressExtractOn(recognised)
        val stale = FocusKey("files/extract/NOT_A_REAL_OPTION")
        authority.registerVirtual(stale, c.extractScope, fileBrowserExtractOptionBounds(2))
        authority.requestFocus(stale)
        assertEquals("the stale key really does hold the ring", stale, authority.state.value.focused)

        c.dispatch(ControllerAction.CONFIRM)

        assertTrue("nothing extracted", extracted.isEmpty())
        assertNull("and no pick began", c.destinationPick)
        assertTrue("the chooser is still up -- a no-op, not a silent dismissal", c.extractChooserOpen)
        c.close()
    }

    @Test
    fun `CANCEL closes the chooser before it can ever mean Up`() {
        val (c, authority) = open()
        c.pressExtractOn(recognised)
        assertTrue(c.extractChooserOpen)
        assertTrue("the archive sits at the root, where CANCEL otherwise CLOSES the browser", c.atRoot)

        c.dispatch(ControllerAction.CANCEL)

        assertFalse("B closes the chooser", c.extractChooserOpen)
        assertEquals("and must not have closed the BROWSER instead", "/root", c.currentPath)
        assertEquals(
            "and must NOT also have walked the player out of the directory they are standing in -- the " +
                "chooser arm runs AHEAD of the browse table for exactly this reason",
            "/root",
            c.currentPath,
        )
        assertEquals("the browse scope is restored", c.scope, authority.state.value.scope)
        assertEquals(
            "with focus back on the archive the player pressed X on -- popScope restores it, no " +
                "bookkeeping here",
            FileBrowserFocus.tileKey(recognised),
            authority.state.value.focused,
        )
        c.close()
    }

    @Test
    fun `CANCEL on a chooser opened BELOW the root does not also walk the player up`() {
        val (c, authority) = open()
        authority.requestFocus(FileBrowserFocus.tileKey("/root/Roms"))
        c.dispatch(ControllerAction.CONFIRM)
        assertEquals("/root/Roms", c.currentPath)
        // No archive lives here, so drive the chooser directly -- the point under test is the ROUTING
        // arm's position in dispatch(), not how the chooser was opened.
        c.openExtractChooser(recognised, fileBrowserExtractOptions(options(recognised)))
        assertTrue(c.extractChooserOpen)

        c.dispatch(ControllerAction.CANCEL)

        assertFalse(c.extractChooserOpen)
        assertEquals(
            "one press must not both leave the chooser and go up a directory",
            "/root/Roms",
            c.currentPath,
        )
        c.close()
    }

    @Test
    fun `X still closes the chooser once inside it, though SELECT is the door that opens it now`() {
        // F-1/R-13: the press that OPENS the chooser moved from X to SELECT+the overlay's EXTRACT row
        // (pressExtractOn's own KDoc). dispatchExtract's SECONDARY arm is untouched by this ticket,
        // though -- `ControllerAction.SECONDARY -> closeExtractChooser()` -- so X's courtesy-close, once
        // the chooser is already up, is unchanged.
        val (c, _) = open()
        c.pressExtractOn(recognised)
        assertTrue(c.extractChooserOpen)
        c.dispatch(ControllerAction.SECONDARY)
        assertFalse("X's close-courtesy inside the chooser must survive the door it is reached by moving", c.extractChooserOpen)
        c.close()
    }

    @Test
    fun `the D-pad moves between the chooser's rows, and horizontals are absorbed rather than escaping`() {
        val (c, authority) = open()
        c.pressExtractOn(recognised)
        val here = FileBrowserExtractFocus.optionKey(FileBrowserExtractOptionKind.HERE)
        val platform = FileBrowserExtractFocus.optionKey(FileBrowserExtractOptionKind.PLATFORM_FOLDER)
        assertEquals("the chooser lands on row 0", here, authority.state.value.focused)

        c.dispatch(ControllerAction.NAV_DOWN)
        assertEquals("DOWN reaches row 1 -- the synthetic bounds have to be ordered for this", platform, authority.state.value.focused)
        c.dispatch(ControllerAction.NAV_UP)
        assertEquals(here, authority.state.value.focused)

        assertTrue("a horizontal is still absorbed by the modal", c.dispatch(ControllerAction.NAV_RIGHT))
        assertEquals(
            "and moves nothing -- full-width rows give the scoring no candidate, which is why this " +
                "screen needs no consumption rule of the browse table's kind",
            here,
            authority.state.value.focused,
        )
        assertEquals("focus never leaves the chooser's scope", c.extractScope, authority.state.value.scope)
        c.close()
    }

    @Test
    fun `handleExtractTap is the touch seam, and it goes through the same CONFIRM path`() {
        val (c, _) = open()
        c.pressExtractOn(recognised)

        c.handleExtractTap(FileBrowserExtractFocus.optionKey(FileBrowserExtractOptionKind.PLATFORM_FOLDER))

        assertEquals(
            "a tap must reach the identical outcome as A on the same row -- one true path, the " +
                "discipline handleTap/handleCommitTap/handleSearchTap/handleQueueTap already follow",
            listOf(recognised to "/root/Roms/GBA"),
            extracted,
        )
        assertFalse(c.extractChooserOpen)
        c.close()
    }

    // ────────────────────────────────── 4 · destination-pick mode (D-3) ──

    @Test
    fun `the picker row enters destination-pick and the commit row appears in MANAGE`() {
        val (c, authority) = open()
        assertNull("MANAGE has no commit affordance to begin with", c.commitPurpose)

        c.pressExtractOn(unrecognised)
        authority.requestFocus(
            FileBrowserExtractFocus.optionKey(FileBrowserExtractOptionKind.CHOOSE_FOLDER),
        )
        c.dispatch(ControllerAction.CONFIRM)

        assertFalse("the chooser closes -- the browser itself is the picker", c.extractChooserOpen)
        assertEquals(unrecognised, c.destinationPick?.archivePath)
        assertEquals(
            "the ONE gate now answers EXTRACT_DESTINATION, in a mode that has no commit row otherwise",
            FileBrowserCommitPurpose.EXTRACT_DESTINATION,
            c.commitPurpose,
        )
        assertTrue("so the row exists", c.commitAvailable)
        assertEquals(
            "and it is REGISTERED and focused, not merely allowed -- a gate that says the row exists " +
                "over a graph that has never heard of it is UI-05 inverted",
            FileBrowserFocus.commitKey("/root"),
            authority.state.value.focused,
        )
        assertEquals("in the browse scope; the pick is not a screen", c.scope, authority.state.value.scope)
        c.close()
    }

    @Test
    fun `manual destination browsing starts at the browser root and can reach a sibling folder`() {
        val (c, authority) = open(
            resolvePlatformDestinations = {
                listOf(
                    PlatformDestination(
                        platformId = "gba",
                        path = "/root/Roms/GBA",
                        fromLibrary = true,
                        recognised = false,
                        onSubjectVolume = true,
                    ),
                )
            },
        )
        authority.requestFocus(FileBrowserFocus.tileKey("/root/Deep"))
        c.dispatch(ControllerAction.CONFIRM)
        c.pressExtractOn("/root/Deep/deep.zip")
        authority.requestFocus(
            FileBrowserExtractFocus.optionKey(FileBrowserExtractOptionKind.CHOOSE_FOLDER),
        )
        c.dispatch(ControllerAction.CONFIRM)

        assertEquals(
            "the origin is nested, but the live destination journey starts at the browser root",
            "/root",
            c.currentPath,
        )
        assertTrue("the optional destination list remains available at the root", c.destinationListOpen)
        authority.requestFocus(FileBrowserDestinationFocus.optionKey(0))
        c.dispatch(ControllerAction.CONFIRM)

        authority.requestFocus(FileBrowserFocus.tileKey("/root/Roms"))
        c.dispatch(ControllerAction.CONFIRM)
        authority.requestFocus(FileBrowserFocus.tileKey("/root/Roms/GBA"))
        c.dispatch(ControllerAction.CONFIRM)
        authority.requestFocus(FileBrowserFocus.commitKey("/root/Roms/GBA"))
        c.dispatch(ControllerAction.CONFIRM)

        assertEquals(listOf("/root/Deep/deep.zip" to "/root/Roms/GBA"), extracted)
        c.close()
    }

    @Test
    fun `cancel from the re-rooted browse journey restores the nested origin chooser`() {
        val (c, authority) = open(
            resolvePlatformDestinations = {
                listOf(
                    PlatformDestination(
                        platformId = "gba",
                        path = "/root/Roms/GBA",
                        fromLibrary = true,
                        recognised = false,
                        onSubjectVolume = true,
                    ),
                )
            },
        )
        authority.requestFocus(FileBrowserFocus.tileKey("/root/Deep"))
        c.dispatch(ControllerAction.CONFIRM)
        c.pressExtractOn("/root/Deep/deep.zip")
        authority.requestFocus(
            FileBrowserExtractFocus.optionKey(FileBrowserExtractOptionKind.CHOOSE_FOLDER),
        )
        c.dispatch(ControllerAction.CONFIRM)
        assertEquals("/root", c.currentPath)

        authority.requestFocus(FileBrowserDestinationFocus.optionKey(0))
        c.dispatch(ControllerAction.CONFIRM)
        authority.requestFocus(FileBrowserFocus.tileKey("/root/Deep"))
        c.dispatch(ControllerAction.CONFIRM)
        assertEquals("/root/Deep", c.currentPath)
        assertEquals(FileBrowserDestinationPickPhase.AWAY, c.destinationPickPhase())
        c.dispatch(ControllerAction.CANCEL)
        assertEquals("B at the origin still goes up", "/root", c.currentPath)
        assertFalse(c.extractChooserOpen)
        assertEquals(FileBrowserDestinationPickPhase.SETTLED, c.destinationPickPhase())
        c.dispatch(ControllerAction.CANCEL)

        assertEquals("the saved origin path is restored", "/root/Deep", c.currentPath)
        assertTrue("the same chooser is restored after abandoning the pick", c.extractChooserOpen)
        assertFalse("the restored origin stack still has /root", c.atRoot)
        assertTrue("nothing was extracted", extracted.isEmpty())
        c.close()
    }

    @Test
    fun `committing a destination extracts into the directory the player navigated to`() {
        val (c, authority) = open()
        c.pressExtractOn(unrecognised)
        authority.requestFocus(
            FileBrowserExtractFocus.optionKey(FileBrowserExtractOptionKind.CHOOSE_FOLDER),
        )
        c.dispatch(ControllerAction.CONFIRM)

        // Navigate two folders deep, the way a player choosing a ROM folder would.
        authority.requestFocus(FileBrowserFocus.tileKey("/root/Roms"))
        c.dispatch(ControllerAction.CONFIRM)
        authority.requestFocus(FileBrowserFocus.tileKey("/root/Roms/GBA"))
        c.dispatch(ControllerAction.CONFIRM)
        assertEquals("/root/Roms/GBA", c.currentPath)

        authority.requestFocus(FileBrowserFocus.commitKey("/root/Roms/GBA"))
        c.dispatch(ControllerAction.CONFIRM)

        assertEquals(
            "the archive comes from the PICK and the destination from currentPath",
            listOf(unrecognised to "/root/Roms/GBA"),
            extracted,
        )
        assertNull("the pick is over", c.destinationPick)
        assertNull("so the gate says the commit row no longer exists in MANAGE", c.commitPurpose)
        // **The gate answering null is NOT enough, and asserting only that would be a test that passes
        // with its own subject deleted.** `commitPurpose` reads `destinationPick`, which this press
        // nulls either way; what the reload in `endDestinationPick` actually buys is the row leaving the
        // FOCUS GRAPH. Without it the key stays registered and keeps the ring -- a focusable, advertised
        // row whose gate has already retired it, which is UI-05 arriving by a door UI-05's fix does not
        // cover. Only this assertion can see that.
        assertFalse(
            "the retired commit key must not still hold focus",
            authority.state.value.focused == FileBrowserFocus.commitKey("/root/Roms/GBA"),
        )
        assertEquals(
            "focus lands back in the listing instead",
            FileBrowserFocus.tileKey("/root/Roms/GBA/existing.gba"),
            authority.state.value.focused,
        )
        assertEquals(
            "the player stays where they chose: that is where runExtraction's refresh will show the files",
            "/root/Roms/GBA",
            c.currentPath,
        )
        c.close()
    }

    @Test
    fun `a PICK-mode commit row means the DESTINATION while a pick is open, never onChoose`() {
        val chosen = mutableListOf<String>()
        val authority = DefaultFocusAuthority()
        val c = FileBrowserController(
            authority = authority,
            rootPath = "/root",
            mode = FileBrowserMode.PICK,
            listDirectory = { path -> tree[path].orEmpty() },
            onChoose = { chosen += it },
            onDismiss = {},
            resolveExtractOptions = ::options,
            onExtractTo = { archive, destination -> extracted += archive to destination },
        )
        authorities[c] = authority
        c.open()
        assertEquals(FileBrowserCommitPurpose.CHOOSE_SOURCE, c.commitPurpose)

        c.pressExtractOn(unrecognised)
        authority.requestFocus(
            FileBrowserExtractFocus.optionKey(FileBrowserExtractOptionKind.CHOOSE_FOLDER),
        )
        c.dispatch(ControllerAction.CONFIRM)
        assertEquals(
            "the pick must OUTRANK PICK's own commit -- otherwise A closes the browser and reports a " +
                "ROM source the player never asked for, with the archive unextracted",
            FileBrowserCommitPurpose.EXTRACT_DESTINATION,
            c.commitPurpose,
        )

        authority.requestFocus(FileBrowserFocus.commitKey("/root"))
        c.dispatch(ControllerAction.CONFIRM)

        assertEquals(listOf(unrecognised to "/root"), extracted)
        assertTrue("onChoose must NOT have fired", chosen.isEmpty())
        assertEquals(
            "and once the pick is over the row reverts to PICK's own meaning",
            FileBrowserCommitPurpose.CHOOSE_SOURCE,
            c.commitPurpose,
        )
        c.close()
    }

    @Test
    fun `commitAllowed suppresses the destination row too -- the drive chooser is not a destination`() {
        val (c, authority) = open(commitAllowed = { it != "/root" })
        c.pressExtractOn(unrecognised)
        authority.requestFocus(
            FileBrowserExtractFocus.optionKey(FileBrowserExtractOptionKind.CHOOSE_FOLDER),
        )
        c.dispatch(ControllerAction.CONFIRM)

        assertNotNull("the pick is open", c.destinationPick)
        assertNull(
            "but the row is refused HERE, exactly as Contract C refuses it for a ROM source: /storage " +
                "is a synthetic listing of volumes, not a directory anything can be extracted into",
            c.commitPurpose,
        )
        assertFalse(c.commitAvailable)
        c.close()
    }

    @Test
    fun `handleCommitTap during a pick commits the DESTINATION, not a ROM source`() {
        val chosen = mutableListOf<String>()
        val authority = DefaultFocusAuthority()
        // PICK mode deliberately: this is the seam where a tap could reach the WRONG one of the two
        // meanings, because PICK is the mode where the commit row also has a CHOOSE_SOURCE job.
        val c = FileBrowserController(
            authority = authority,
            rootPath = "/root",
            mode = FileBrowserMode.PICK,
            listDirectory = { path -> tree[path].orEmpty() },
            onChoose = { chosen += it },
            onDismiss = {},
            resolveExtractOptions = ::options,
            onExtractTo = { archive, destination -> extracted += archive to destination },
        )
        authorities[c] = authority
        c.open()
        c.pressExtractOn(unrecognised)
        authority.requestFocus(
            FileBrowserExtractFocus.optionKey(FileBrowserExtractOptionKind.CHOOSE_FOLDER),
        )
        c.dispatch(ControllerAction.CONFIRM)

        c.handleCommitTap()

        assertEquals(
            "the touch path routes through the same CONFIRM branch, so it reads the same ONE gate the " +
                "controller does -- a tap that took the other meaning would close the browser and " +
                "report a ROM source instead of extracting",
            listOf(unrecognised to "/root"),
            extracted,
        )
        assertTrue("onChoose must NOT have fired", chosen.isEmpty())
        c.close()
    }

    // ──────────────────────────── 5 · CANCEL has three levels, each unwinding one step (D-3) ──

    @Test
    fun `CANCEL inside a pick goes UP while the player has navigation of their own to unwind`() {
        val (c, authority) = open()
        c.pressExtractOn(unrecognised)
        authority.requestFocus(
            FileBrowserExtractFocus.optionKey(FileBrowserExtractOptionKind.CHOOSE_FOLDER),
        )
        c.dispatch(ControllerAction.CONFIRM)

        authority.requestFocus(FileBrowserFocus.tileKey("/root/Roms"))
        c.dispatch(ControllerAction.CONFIRM)
        assertEquals(FileBrowserDestinationPickPhase.AWAY, c.destinationPickPhase())

        c.dispatch(ControllerAction.CANCEL)
        assertEquals(
            "B must still be Up here -- taking Up away for the duration of a pick traps a player who " +
                "descended into the wrong folder",
            "/root",
            c.currentPath,
        )
        assertNotNull("and the pick survives it", c.destinationPick)
        c.close()
    }

    @Test
    fun `CANCEL at the pick's origin unwinds the PICK, back to the chooser it came from`() {
        val (c, authority) = open()
        c.pressExtractOn(unrecognised)
        val rows = c.extractChooser!!.rows
        authority.requestFocus(
            FileBrowserExtractFocus.optionKey(FileBrowserExtractOptionKind.CHOOSE_FOLDER),
        )
        c.dispatch(ControllerAction.CONFIRM)
        assertEquals(FileBrowserDestinationPickPhase.SETTLED, c.destinationPickPhase())

        c.dispatch(ControllerAction.CANCEL)

        assertNull("the pick is abandoned", c.destinationPick)
        assertTrue("and the CHOOSER is back -- one press, one step", c.extractChooserOpen)
        assertEquals(
            "with the SAME two options, carried rather than re-resolved: a player pressing B must not " +
                "be shown a different question than the one they were just looking at",
            rows,
            c.extractChooser!!.rows,
        )
        assertEquals(unrecognised, c.extractChooser!!.archivePath)
        assertTrue("nothing was extracted", extracted.isEmpty())
        c.close()
    }

    @Test
    fun `abandoning from three folders deep restores the origin directory and its whole back-stack`() {
        val (c, authority) = open()
        c.pressExtractOn(unrecognised)
        authority.requestFocus(
            FileBrowserExtractFocus.optionKey(FileBrowserExtractOptionKind.CHOOSE_FOLDER),
        )
        c.dispatch(ControllerAction.CONFIRM)

        authority.requestFocus(FileBrowserFocus.tileKey("/root/Roms"))
        c.dispatch(ControllerAction.CONFIRM)
        authority.requestFocus(FileBrowserFocus.tileKey("/root/Roms/GBA"))
        c.dispatch(ControllerAction.CONFIRM)
        assertEquals("/root/Roms/GBA", c.currentPath)

        // Two CANCELs walk back to the origin, the third abandons.
        c.dispatch(ControllerAction.CANCEL)
        c.dispatch(ControllerAction.CANCEL)
        assertEquals("/root", c.currentPath)
        assertNotNull("still picking", c.destinationPick)
        c.dispatch(ControllerAction.CANCEL)

        assertNull(c.destinationPick)
        assertTrue(c.extractChooserOpen)
        assertEquals("back where the pick began", "/root", c.currentPath)
        assertTrue("and at the root, so the stack really was restored", c.atRoot)

        // And the fourth and fifth close the chooser, then the browser.
        c.dispatch(ControllerAction.CANCEL)
        assertFalse(c.extractChooserOpen)
        assertEquals("/root", c.currentPath)
        c.close()
    }

    @Test
    fun `abandoning from a directory the pick never came from restores the origin AND its back-stack`() {
        // **The only reachable arrangement in which the stack restore does any work, and finding it took
        // a differential that came back GREEN.** CANCEL unwinds the player's own navigation first, so an
        // abandon normally happens standing exactly where the pick began -- the stack is already right
        // and `stack.clear(); stack.addAll(originStack)` is a no-op. The player also cannot walk UP past
        // the origin, because at the origin CANCEL abandons instead of going up.
        //
        // What CAN move them off the origin's own branch is a SEARCH REVEAL: `revealPath` rebuilds the
        // whole back-stack for the file it lands on. So a pick begun in one branch, followed by a reveal
        // into another, followed by CANCELs back to the root, is the case the restore exists for -- and
        // it doubles as the pin that a pick SURVIVES a search excursion rather than being silently lost.
        val (c, authority) = open()
        c.setSearchAvailable(true)
        authority.requestFocus(FileBrowserFocus.tileKey("/root/Deep"))
        c.dispatch(ControllerAction.CONFIRM)
        assertEquals("/root/Deep", c.currentPath)

        c.pressExtractOn("/root/Deep/deep.zip")
        authority.requestFocus(
            FileBrowserExtractFocus.optionKey(FileBrowserExtractOptionKind.CHOOSE_FOLDER),
        )
        c.dispatch(ControllerAction.CONFIRM)
        assertEquals("/root/Deep", c.destinationPick?.originPath)

        // Search, and reveal a file in the OTHER branch.
        c.openSearch()
        val hit = FileItem(name = "existing.gba", path = "/root/Roms/GBA/existing.gba", extension = "gba")
        c.publishSearchResults(listOf(hit), FileBrowserSearchPhase.SETTLED)
        authority.requestFocus(FileBrowserSearchFocus.resultKey(hit.path))
        c.dispatch(ControllerAction.CONFIRM)
        assertEquals("the reveal landed in the other branch", "/root/Roms/GBA", c.currentPath)
        assertNotNull("and the pick survived the excursion", c.destinationPick)
        assertEquals(FileBrowserDestinationPickPhase.AWAY, c.destinationPickPhase())

        // Walk up out of that branch entirely.
        c.dispatch(ControllerAction.CANCEL)
        c.dispatch(ControllerAction.CANCEL)
        assertEquals("/root", c.currentPath)
        assertTrue("at the root, so the next CANCEL is SETTLED rather than AWAY", c.atRoot)

        c.dispatch(ControllerAction.CANCEL)

        assertEquals("the abandon returns the player to where the pick began", "/root/Deep", c.currentPath)
        assertFalse(
            "and the back-stack with it -- restoring the path alone would leave the player somewhere " +
                "whose parent chain is a different directory's, so the next B would CLOSE the browser " +
                "instead of going up",
            c.atRoot,
        )
        assertTrue(c.extractChooserOpen)
        c.close()
    }

    @Test
    fun `abandoning puts the ring back on the archive the pick was about`() {
        val (c, authority) = open()
        c.pressExtractOn(unrecognised)
        authority.requestFocus(
            FileBrowserExtractFocus.optionKey(FileBrowserExtractOptionKind.CHOOSE_FOLDER),
        )
        c.dispatch(ControllerAction.CONFIRM)
        c.dispatch(ControllerAction.CANCEL)
        // The chooser is up; leave it and inspect where browse focus landed.
        c.dispatch(ControllerAction.CANCEL)

        assertEquals(
            "seeded through focusByDirectory so it arrives by the same validated path a returning " +
                "player's remembered tile does",
            FileBrowserFocus.tileKey(unrecognised),
            authority.state.value.focused,
        )
        c.close()
    }

    @Test
    fun `CANCEL never closes the browser while a pick is open, even at the root`() {
        var dismissed = 0
        val authority = DefaultFocusAuthority()
        val c = FileBrowserController(
            authority = authority,
            rootPath = "/root",
            mode = FileBrowserMode.MANAGE,
            listDirectory = { path -> tree[path].orEmpty() },
            onChoose = {},
            onDismiss = { dismissed += 1 },
            resolveExtractOptions = ::options,
            onExtractTo = { archive, destination -> extracted += archive to destination },
        )
        authorities[c] = authority
        c.open()
        c.pressExtractOn(unrecognised)
        authority.requestFocus(
            FileBrowserExtractFocus.optionKey(FileBrowserExtractOptionKind.CHOOSE_FOLDER),
        )
        c.dispatch(ControllerAction.CONFIRM)
        assertTrue("standing at the root", c.atRoot)

        c.dispatch(ControllerAction.CANCEL)
        assertEquals(
            "losing a browse session to a reflex B press is not recoverable; two extra presses are",
            0,
            dismissed,
        )
        c.close()
    }

    // ─────────────────────────── 6 · a pick is a question, and it is answered one at a time ──

    @Test
    fun `X is swallowed during a pick, so a second archive cannot overwrite the pending one`() {
        val (c, authority) = open()
        c.pressExtractOn(unrecognised)
        authority.requestFocus(
            FileBrowserExtractFocus.optionKey(FileBrowserExtractOptionKind.CHOOSE_FOLDER),
        )
        c.dispatch(ControllerAction.CONFIRM)

        c.pressExtractOn(recognised)

        assertFalse("no second chooser", c.extractChooserOpen)
        assertEquals(
            "and the pending pick still points at the archive the player actually chose",
            unrecognised,
            c.destinationPick?.archivePath,
        )
        assertTrue("nothing reached the legacy sink either", extractedHere.isEmpty())
        c.close()
    }

    // ─────────────────────────────────────── 7 · lifecycle: nothing survives a teardown ──

    @Test
    fun `close clears the chooser, the pick and the pushed scope`() {
        val (c, authority) = open()
        c.pressExtractOn(recognised)
        assertTrue(c.extractChooserOpen)

        c.close()

        assertFalse(c.extractChooserOpen)
        assertNull(c.destinationPick)
        assertFalse(
            "a chooser scope left pushed buries the browse scope, so NOTHING is popped and the stack " +
                "stays corrupted for whatever surface mounts next",
            authority.state.value.scope == c.extractScope,
        )
    }

    @Test
    fun `a re-opened instance does not inherit the previous session's pick`() {
        val (c, authority) = open()
        c.pressExtractOn(unrecognised)
        authority.requestFocus(
            FileBrowserExtractFocus.optionKey(FileBrowserExtractOptionKind.CHOOSE_FOLDER),
        )
        c.dispatch(ControllerAction.CONFIRM)
        assertNotNull(c.destinationPick)

        c.open()

        assertNull(
            "a pick from a session that is over would make the commit row answer EXTRACT_DESTINATION " +
                "for an archive nobody is looking at",
            c.destinationPick,
        )
        assertFalse(c.extractChooserOpen)
        assertNull("and MANAGE gets no commit row again", c.commitPurpose)
        c.close()
    }

    // ──────────────────── 8 · the three screens are mutually exclusive from INSIDE the controller ──

    @Test
    fun `opening search closes the queue -- the header can reach it from the queue screen`() {
        val (c, authority) = open()
        c.setSearchAvailable(true)
        c.setQueueAction { }
        c.openQueue()
        assertTrue(c.queueOpen)

        c.openSearch()

        assertFalse(
            "FileBrowserSurface's header comment ASSERTED this already happened and it did not: the " +
                "header stays composed above the queue, so a magnifier tap pushed searchScope on top of " +
                "queueScope and left two screens' graphs alive at once",
            c.queueOpen,
        )
        assertEquals(c.searchScope, authority.state.value.scope)
        c.closeSearch()
        c.close()
    }

    @Test
    fun `opening search or the queue closes an open extract chooser`() {
        val (c, _) = open()
        c.setSearchAvailable(true)
        c.pressExtractOn(recognised)
        assertTrue(c.extractChooserOpen)
        c.openSearch()
        assertFalse("or the chooser's scope is buried under search's", c.extractChooserOpen)
        c.closeSearch()

        c.setQueueAction { }
        c.pressExtractOn(recognised)
        assertTrue(c.extractChooserOpen)
        c.openQueue()
        assertFalse(c.extractChooserOpen)
        c.closeQueue()
        c.close()
    }

    // ────────────────────────────────────── 9 · the legend, exactly as the wired surface will ask ──

    @Test
    fun `the browse legend during a pick -- the contract Lane E's line 350 has to satisfy`() {
        val archive = archiveTile()
        val onCommitRow = fileBrowserFooterHints(english,
            focused = null,
            atRoot = true,
            commitFocused = true,
            commitPurpose = FileBrowserCommitPurpose.EXTRACT_DESTINATION,
            destinationPick = FileBrowserDestinationPickPhase.SETTLED,
        )
        assertTrue(
            "A must say what it DOES -- 'CHOOSE' answers the add-a-ROM-source question, not this one",
            "A" to "Extract here" in onCommitRow,
        )
        assertTrue("B never closes the browser mid-pick", "B" to "Back" in onCommitRow)

        val onArchive = fileBrowserFooterHints(english,
            focused = archive,
            atRoot = false,
            destinationPick = FileBrowserDestinationPickPhase.AWAY,
        )
        assertFalse(
            "X is swallowed for the duration, and a legend entry over a dead binding is the same lie " +
                "as a binding with no legend entry",
            "X" to "Extract" in onArchive,
        )
    }

    @Test
    fun `every pre-PR-141 caller's legend is byte-identical -- the new parameters all default off`() {
        val archive = archiveTile()
        assertEquals(
            listOf("X" to "Extract", "B" to "Close"),
            fileBrowserFooterHints(english, focused = archive, atRoot = true),
        )
        assertEquals(
            "commitFocused = true with commitPurpose = null IS the pre-#141 caller, by definition",
            listOf("A" to "Choose", "B" to "Back"),
            fileBrowserFooterHints(english, focused = null, atRoot = false, commitFocused = true),
        )
    }

    @Test
    fun `the chooser's own legend names each row's real verb`() {
        assertEquals(
            listOf("A" to "Extract", "B" to "Back"),
            fileBrowserExtractFooterHints(english, FileBrowserExtractOptionKind.HERE),
        )
        assertEquals(
            listOf("A" to "Extract", "B" to "Back"),
            fileBrowserExtractFooterHints(english, FileBrowserExtractOptionKind.PLATFORM_FOLDER),
        )
        assertEquals(
            "this row writes NOTHING -- calling it EXTRACT promises a write that does not happen and " +
                "leaves the player waiting for files",
            listOf("A" to "Choose folder", "B" to "Back"),
            fileBrowserExtractFooterHints(english, FileBrowserExtractOptionKind.CHOOSE_FOLDER),
        )
        assertEquals(
            "no A hint before the landing focus arrives, but B still leaves",
            listOf("B" to "Back"),
            fileBrowserExtractFooterHints(english, null),
        )
    }
}
