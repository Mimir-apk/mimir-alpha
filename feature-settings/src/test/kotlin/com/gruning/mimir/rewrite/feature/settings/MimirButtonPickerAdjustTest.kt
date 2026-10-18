package com.gruning.mimir.rewrite.feature.settings

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.MimirNavFeedback
import com.gruning.mimir.rewrite.core.model.MimirMarkNudgeDp
import com.gruning.mimir.rewrite.core.model.MimirMarkOffsetLimitDp
import com.gruning.mimir.rewrite.core.model.MimirMarkPlacement
import com.gruning.mimir.rewrite.core.model.MimirMarkScaleMax
import com.gruning.mimir.rewrite.core.model.MimirMarkScaleMin
import com.gruning.mimir.rewrite.core.ui.MimirButtonMark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Mimir-button picker's ADJUST mode : Y on a saved image opens it, the pad moves and sizes the image
 * (D-pad 0.5 dp a press, L1 and R1 5% a press, X resets), A saves AND uses the image, B cancels and leaves the saved placement as it
 * was. Pure: the stage draws the working placement and a store is touched only by the command A hands the host.
 */
class MimirButtonPickerAdjustTest {

    private val starPlacement = MimirMarkPlacement(scale = 1.2f, offsetX = 3f, offsetY = -1.5f)
    private val star = MimirButtonPickerMark("aaaaaaaa", "Your mark 1", "/app/marks/aaaaaaaa.png", starPlacement)
    private val bolt = MimirButtonPickerMark("bbbbbbbb", "Your mark 2", "/app/marks/bbbbbbbb.png")
    private val broken = MimirButtonPickerMark("cccccccc", "Your mark 3", null)
    private val saved = listOf(star, bolt, broken)

    private fun open(current: MimirButtonMark? = null) = mimirButtonPickerInitialState(current, saved)
    private fun markOf(m: MimirButtonPickerMark, placement: MimirMarkPlacement = m.placement) = mimirButtonMarkOf(m.id, m.path!!, placement)

    private fun adjusting(mark: MimirButtonPickerMark = star, current: MimirButtonMark? = null): MimirButtonPickerState {
        val opened = mimirButtonPickerTransition(open(current), ControllerAction.TERTIARY, mimirButtonTileKey(mark.id))
        assertNotNull("fixture: Y opened adjust", opened.state.adjust)
        return opened.state
    }

    private fun press(state: MimirButtonPickerState, action: ControllerAction, times: Int = 1): MimirButtonPickerTransition {
        var transition = MimirButtonPickerTransition(state)
        repeat(times) { transition = mimirButtonPickerTransition(transition.state, action, mimirButtonTileKey(star.id)) }
        return transition
    }

    // ---- entry: Y on an image, and only there ---------------------------------------------------------------------------------

    @Test
    fun `Y opens adjust on a saved image, carrying its own placement, and writes nothing`() {
        val opened = mimirButtonPickerTransition(open(), ControllerAction.TERTIARY, mimirButtonTileKey(star.id))
        assertNull("opening adjust is not a command", opened.command)
        val adjust = opened.state.adjust!!
        assertEquals(star.id, adjust.id)
        assertEquals(starPlacement, adjust.working)
        assertEquals("what B restores", starPlacement, adjust.restore)
        assertEquals("the stage shows the image being adjusted", star.id, opened.state.previewId)
        assertEquals("nothing was committed", null, opened.state.current)
        assertEquals(markOf(star), opened.state.previewMark())

        val plain = mimirButtonPickerTransition(open(), ControllerAction.TERTIARY, mimirButtonTileKey(bolt.id)).state.adjust!!
        assertEquals("an image that was never placed starts centred at 100%", MimirMarkPlacement.Default, plain.working)
    }

    @Test
    fun `Y does nothing on the rune, on Add, on an image that cannot be shown, or on no focus`() {
        for (focused in listOf(MimirButtonRuneKey, MimirButtonAddKey, mimirButtonTileKey(broken.id), null)) {
            val idle = mimirButtonPickerTransition(open(), ControllerAction.TERTIARY, focused)
            assertNull("Y on $focused", idle.state.adjust)
            assertNull(idle.command)
            assertEquals(open(), idle.state)
        }
    }

    @Test
    fun `the footer names Y ADJUST on a saved available image only, X REMOVE on any saved, no X on Rune or Add, and A UPLOAD on Add`() {
        val browse = open()
        assertEquals(MimirButtonControlHintAdjustable, browse.controlHint(mimirButtonTileKey(star.id)))
        assertEquals(MimirButtonControlHintAdjustable, browse.controlHint(mimirButtonTileKey(bolt.id)))
        assertTrue(MimirButtonControlHintAdjustable.contains("Y ADJUST"))
        // A saved unavailable image gets X REMOVE but not Y.
        assertEquals(AppearancePickerControlHint, browse.controlHint(mimirButtonTileKey(broken.id)))
        assertTrue("unavailable has X REMOVE", browse.controlHint(mimirButtonTileKey(broken.id)).contains("X REMOVE"))
        // The Rune (and no focus) gets no X and A USE: A applies the built-in mark.
        for (focused in listOf(MimirButtonRuneKey, null)) {
            val hint = browse.controlHint(focused)
            assertEquals(AppearancePickerControlHintNoRemove, hint)
            assertEquals("A USE · B BACK", hint)
            assertFalse("no X on $focused", hint.contains("X "))
        }
        // Add gets no X either, but A there opens the file picker -- it uses nothing -- so the footer says the tile's own word .
        val upload = browse.controlHint(MimirButtonAddKey)
        assertEquals(AppearancePickerControlHintUpload, upload)
        assertEquals("A UPLOAD · B BACK", upload)
        assertEquals("the footer names the word the tile wears", AppearanceUploadLabel.uppercase(), upload.removePrefix("A ").substringBefore(" "))
        assertFalse("no X on Add", upload.contains("X "))
        assertFalse("and nothing to USE on Add", upload.contains("USE"))
        // Adjust mode's legend is the same on every tile, Add included (adjust never leaves a saved image).
        assertEquals(MimirButtonControlHintAdjusting, adjusting().controlHint(MimirButtonAddKey))
        assertEquals(MimirButtonControlHintAdjusting, adjusting().controlHint(mimirButtonTileKey(star.id)))
        // Every key once. The footer names the size keys first and the three that end the
        // session; the subtitle, which has two lines where the footer has one, names the D-pad and says
        // what the shoulders do in words. The pad's keys are the footer's alone: the subtitle does not repeat A, B or X.
        assertEquals("L1 R1 SIZE · A SAVE · X RESET · B CANCEL", MimirButtonControlHintAdjusting)
        assertEquals("D-pad moves it, L1 and R1 size it. The small button is the real one.", MimirButtonAdjustSubtitle)
        val everything = MimirButtonControlHintAdjusting + " " + MimirButtonAdjustSubtitle
        for (key in listOf("L1 R1 SIZE", "A SAVE", "B CANCEL", "X RESET", "D-pad", "L1 and R1")) {
            assertEquals("$key is named once", 1, Regex(Regex.escape(key)).findAll(everything).count())
        }
        assertFalse("and there is no second line of keys under the stage", Regex("resets", RegexOption.IGNORE_CASE).containsMatchIn(everything))
    }

    @Test
    fun `a footer too narrow for the whole legend falls back to the shorter one, which never drops the size keys`() {
        assertEquals("L1 R1 SIZE · A SAVE · B CANCEL", MimirButtonControlHintAdjustingShort)
        // The fallback is the whole legend less X RESET, in the same order: nothing is reworded, and the size keys lead both.
        val whole = MimirButtonControlHintAdjusting.split(" · ")
        val short = MimirButtonControlHintAdjustingShort.split(" · ")
        assertEquals(whole - "X RESET", short)
        assertEquals("L1 R1 SIZE", short.first())

        // Only adjust mode has a fallback; the tiles' footers are drawn as they always were.
        assertEquals(MimirButtonControlHintAdjustingShort, adjusting().controlHintFallback())
        assertNull(open().controlHintFallback())

        // The choice: the whole legend when it fits, the fallback when it does not, and the whole one when there is no fallback to take.
        val widths = mapOf(MimirButtonControlHintAdjusting to 330, MimirButtonControlHintAdjustingShort to 240)
        for ((room, expected) in listOf(
            400 to MimirButtonControlHintAdjusting,
            330 to MimirButtonControlHintAdjusting,
            329 to MimirButtonControlHintAdjustingShort,
            100 to MimirButtonControlHintAdjustingShort,
        )) {
            assertEquals("room $room", expected, fittingControlHint(MimirButtonControlHintAdjusting, MimirButtonControlHintAdjustingShort) { widths.getValue(it) <= room })
        }
        assertEquals(AppearancePickerControlHint, fittingControlHint(AppearancePickerControlHint, null) { false })
    }

    // ---- the pad ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `the D-pad moves the image half a dp a press and a hold keeps moving it`() {
        val start = adjusting()
        val x = starPlacement.offsetX
        val y = starPlacement.offsetY
        assertEquals(x + MimirMarkNudgeDp, press(start, ControllerAction.NAV_RIGHT).state.adjust!!.working.offsetX, 0f)
        assertEquals(x - MimirMarkNudgeDp, press(start, ControllerAction.NAV_LEFT).state.adjust!!.working.offsetX, 0f)
        assertEquals(y - MimirMarkNudgeDp, press(start, ControllerAction.NAV_UP).state.adjust!!.working.offsetY, 0f)
        assertEquals(y + MimirMarkNudgeDp, press(start, ControllerAction.NAV_DOWN).state.adjust!!.working.offsetY, 0f)
        // The input authority emits a repeated direction while it is held; each repeat is one more step.
        val held = press(start, ControllerAction.NAV_RIGHT, times = 6).state.adjust!!.working
        assertEquals(x + 6 * MimirMarkNudgeDp, held.offsetX, 0f)
        assertEquals("the other axis and the size are untouched", y, held.offsetY, 0f)
        assertEquals(starPlacement.scale, held.scale, 0f)
        val step = press(start, ControllerAction.NAV_RIGHT)
        assertNull("a step is not a command", step.command)
        assertEquals("the saved list is untouched until A", starPlacement, step.state.saved.first { it.id == star.id }.placement)
    }

    @Test
    fun `the image cannot be moved past 12 dp of the coin's centre, and holding at the limit changes nothing`() {
        val atEdge = press(adjusting(bolt), ControllerAction.NAV_RIGHT, times = 24).state
        assertEquals(12f, atEdge.adjust!!.working.offsetX, 0f)
        val again = mimirButtonPickerTransition(atEdge, ControllerAction.NAV_RIGHT, null)
        assertSame("no new state at the limit: nothing to recompose", atEdge, again.state)
        assertEquals(-12f, press(adjusting(bolt), ControllerAction.NAV_UP, times = 80).state.adjust!!.working.offsetY, 0f)
    }

    @Test
    fun `L1 and R1 size the image by 5 percent a press within 60 to 160 percent`() {
        val start = adjusting(bolt)
        assertEquals(1.05f, press(start, ControllerAction.PAGE_RIGHT).state.adjust!!.working.scale, 0.00001f)
        assertEquals(0.95f, press(start, ControllerAction.PAGE_LEFT).state.adjust!!.working.scale, 0.00001f)
        assertEquals(1.15f, press(start, ControllerAction.PAGE_RIGHT, times = 3).state.adjust!!.working.scale, 0.00001f)
        assertEquals("three out and three back is exactly 100%", 1f, press(press(start, ControllerAction.PAGE_RIGHT, 3).state, ControllerAction.PAGE_LEFT, 3).state.adjust!!.working.scale, 0f)
        assertEquals(MimirMarkScaleMax, press(start, ControllerAction.PAGE_RIGHT, times = 30).state.adjust!!.working.scale, 0f)
        assertEquals(MimirMarkScaleMin, press(start, ControllerAction.PAGE_LEFT, times = 30).state.adjust!!.working.scale, 0f)
        assertNull(press(start, ControllerAction.PAGE_RIGHT).command)
    }

    @Test
    fun `X resets to centred at 100 percent and stays in adjust`() {
        val moved = press(press(adjusting(), ControllerAction.NAV_LEFT, 5).state, ControllerAction.PAGE_RIGHT, 4).state
        val reset = mimirButtonPickerTransition(moved, ControllerAction.SECONDARY, null)
        assertEquals(MimirMarkPlacement.Default, reset.state.adjust!!.working)
        assertEquals("what B restores is the saved placement, not the reset", starPlacement, reset.state.adjust!!.restore)
        assertNull("X resets; it removes nothing", reset.command)
        assertEquals("the saved list still holds the saved placement", starPlacement, reset.state.saved.first { it.id == star.id }.placement)
    }

    @Test
    fun `A saves the placement AND uses the image, with the same note a tile's A gives`() {
        val shaped = press(press(adjusting(star, current = markOf(bolt)), ControllerAction.NAV_DOWN, 4).state, ControllerAction.PAGE_LEFT, 2).state
        val working = shaped.adjust!!.working
        assertEquals(MimirMarkPlacement(1.1f, 3f, 0.5f).scale, working.scale, 0.00001f)
        assertEquals(0.5f, working.offsetY, 0f)

        val saved = mimirButtonPickerTransition(shaped, ControllerAction.CONFIRM, null)
        val committed = markOf(star, working)
        assertEquals(MimirButtonPickerCommand.SaveAdjusted(committed), saved.command)
        assertNull("adjust ends", saved.state.adjust)
        assertEquals("the image is the one in use", committed, saved.state.current)
        assertEquals(star.id, saved.state.previewId)
        assertEquals(MimirButtonNoteInUse, saved.state.note)
        assertEquals("its tile brings the new look", working, saved.state.saved.first { it.id == star.id }.placement)
        assertEquals("the other images are untouched", bolt, saved.state.saved.first { it.id == bolt.id })
        assertEquals("the committed mark carries the three numbers", working, committed.markPlacement())
        assertEquals(mimirButtonTileKey(star.id), saved.state.initialFocusKey())
    }

    @Test
    fun `B cancels, restores the saved placement and leaves the image in use as it was`() {
        val current = markOf(bolt)
        val shaped = press(press(adjusting(star, current = current), ControllerAction.NAV_LEFT, 7).state, ControllerAction.PAGE_RIGHT, 5).state
        assertTrue("fixture: the working placement moved", shaped.adjust!!.working != starPlacement)

        for (back in listOf(ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK)) {
            val left = mimirButtonPickerTransition(shaped, back, null)
            assertEquals(MimirButtonPickerCommand.LeaveAdjust(star.id), left.command)
            assertNull(left.state.adjust)
            assertEquals("the saved placement is exactly what it was", starPlacement, left.state.saved.first { it.id == star.id }.placement)
            assertEquals("the stage shows it again", markOf(star), left.state.previewMark())
            assertEquals("nothing was committed", current, left.state.current)
            assertEquals(star.id, left.state.previewId)
        }
        // B in the tiles is still leave-the-picker.
        assertEquals(MimirButtonPickerCommand.Dismiss, mimirButtonPickerTransition(open(), ControllerAction.CANCEL, null).command)
    }

    @Test
    fun `the stage previews the placement being shaped, and focus cannot move it off the image`() {
        val shaped = press(adjusting(), ControllerAction.NAV_RIGHT, 2).state
        assertEquals(markOf(star, starPlacement.movedBy(1f, 0f)), shaped.previewMark())
        assertSame("focus callbacks are ignored while adjusting", shaped, mimirButtonPickerOnFocus(shaped, mimirButtonTileKey(bolt.id)))
        assertSame(shaped, mimirButtonPickerOnFocus(shaped, MimirButtonRuneKey))
        // Back in the tiles a saved image previews with its own placement.
        assertEquals(markOf(star), open().let { mimirButtonPickerOnFocus(it, mimirButtonTileKey(star.id)) }.previewMark())
    }

    @Test
    fun `Y and the keys adjust does not use are ignored while adjusting, and a vanished copy leaves cleanly`() {
        val start = adjusting()
        for (action in listOf(ControllerAction.TERTIARY, ControllerAction.TRIGGER_LEFT, ControllerAction.SYSTEM_START, ControllerAction.SHOW_HINTS)) {
            assertSame("$action", start, mimirButtonPickerTransition(start, action, null).state)
        }
        val vanished = start.copy(saved = start.saved.filterNot { it.id == star.id })
        val saved = mimirButtonPickerTransition(vanished, ControllerAction.CONFIRM, null)
        assertEquals(MimirButtonPickerCommand.LeaveAdjust(star.id), saved.command)
        assertNull(saved.state.adjust)
    }

    // ---- a tile's A keeps the image's own placement; the tiles show it ---------------------------------------------------------

    @Test
    fun `A on a tile commits the image with its own placement, and the tiles carry it`() {
        val commit = mimirButtonPickerTransition(open(), ControllerAction.CONFIRM, mimirButtonTileKey(star.id))
        assertEquals(MimirButtonPickerCommand.Commit(markOf(star)), commit.command)
        assertEquals(starPlacement, (commit.command as MimirButtonPickerCommand.Commit).mark!!.markPlacement())
        val tiles = open().tiles().filterIsInstance<AppearancePickerTile.Saved>()
        assertEquals(listOf(starPlacement, MimirMarkPlacement.Default, MimirMarkPlacement.Default), tiles.map { it.placement })
    }

    @Test
    fun `a mark's placement is the three numbers it carries, in range`() {
        val wild = MimirButtonMark("a", "/p", scale = Float.NaN, offsetX = 500f, offsetY = -500f)
        assertEquals(MimirMarkPlacement(1f, 12f, -12f), wild.markPlacement())
        assertEquals(MimirMarkPlacement.Default, MimirButtonMark("a", "/p").markPlacement())
        val made = mimirButtonMarkOf("a", "/p", MimirMarkPlacement(9f, 0.5f, 0f))
        assertEquals(MimirButtonMark("a", "/p", scale = MimirMarkScaleMax, offsetX = 0.5f, offsetY = 0f), made)
    }

    // ---- the finger -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `a drag moves the image by the dp it travelled and a pinch sizes it, held in range`() {
        val start = adjusting(bolt)
        val dragged = mimirButtonPickerAdjustGesture(start, 2.5f, -4f, 1f).state.adjust!!.working
        assertEquals(MimirMarkPlacement(1f, 2.5f, -4f), dragged)
        val pinched = mimirButtonPickerAdjustGesture(start, 0f, 0f, 1.3f).state.adjust!!.working
        assertEquals(1.3f, pinched.scale, 0.00001f)
        val both = mimirButtonPickerAdjustGesture(mimirButtonPickerAdjustGesture(start, 20f, 20f, 9f).state, 20f, 20f, 9f).state.adjust!!.working
        assertEquals(MimirMarkPlacement(MimirMarkScaleMax, 12f, 12f), both)
        // A gesture that changes nothing hands back the same state, and no adjust open means nothing to shape.
        assertSame(start, mimirButtonPickerAdjustGesture(start, 0f, 0f, 1f).state)
        val browsing = open()
        assertSame(browsing, mimirButtonPickerAdjustGesture(browsing, 3f, 3f, 2f).state)
        assertNull(mimirButtonPickerAdjustGesture(start, 3f, 3f, 2f).command)
    }

    @Test
    fun `the chips expose the pad's keys by touch`() {
        assertEquals(listOf("Smaller", "Larger", "Reset", "Done"), listOf(MimirButtonChipSmaller, MimirButtonChipLarger, MimirButtonChipReset, MimirButtonChipDone))
        assertEquals("ᛗ · ADJUST YOUR MARK", MimirButtonAdjustTitle)
    }

    // ---- the pad reaches adjust mode instead of the focus ------------------------------------------------------------------------

    @Test
    fun `in the tiles the D-pad moves focus, in adjust mode it nudges the image and focus stays on the tile`() {
        val authority = DefaultFocusAuthority()
        authority.pushScope(MimirButtonPickerScope)
        authority.register(mimirButtonTileKey(star.id), MimirButtonPickerScope, FocusBounds(0f, 0f, 10f, 10f))
        authority.register(mimirButtonTileKey(bolt.id), MimirButtonPickerScope, FocusBounds(20f, 0f, 30f, 10f))
        authority.requestFocus(mimirButtonTileKey(star.id))

        var handed: MimirButtonPickerTransition? = null
        dispatchMimirButtonPicker(authority, open(), ControllerAction.NAV_RIGHT) { handed = it }
        assertNull("in the tiles a direction moves focus and hands nothing on", handed)
        assertEquals(mimirButtonTileKey(bolt.id), authority.state.value.focused)

        authority.requestFocus(mimirButtonTileKey(star.id))
        val adjust = adjusting()
        dispatchMimirButtonPicker(authority, adjust, ControllerAction.NAV_RIGHT) { handed = it }
        assertEquals("focus did not move", mimirButtonTileKey(star.id), authority.state.value.focused)
        assertEquals(starPlacement.offsetX + MimirMarkNudgeDp, handed!!.state.adjust!!.working.offsetX, 0f)

        // The other keys reach the rules in both modes.
        handed = null
        assertTrue(dispatchMimirButtonPicker(authority, adjust, ControllerAction.PAGE_RIGHT) { handed = it })
        assertEquals(1.25f, handed!!.state.adjust!!.working.scale, 0.00001f)
    }

    // ---- the click: which presses tell the router a value changed ------------------------------------------------------------------

    private fun adjustingAt(placement: MimirMarkPlacement): MimirButtonPickerState =
        adjusting().let { it.copy(adjust = it.adjust!!.copy(working = placement)) }

    /**
     * What [action] on [state] tells the router, as the change in its two ledgers: the directional one ([MimirNavFeedback.consumed], which a D-pad step uses)
     * and the non-directional one ([MimirNavFeedback.stepped], which L1, R1 and X use). Both are process-wide counters, so only the change means anything.
     */
    private fun heard(state: MimirButtonPickerState, action: ControllerAction): Pair<Long, Long> {
        val authority = DefaultFocusAuthority()
        authority.pushScope(MimirButtonPickerScope)
        authority.register(mimirButtonTileKey(star.id), MimirButtonPickerScope, FocusBounds(0f, 0f, 10f, 10f))
        authority.requestFocus(mimirButtonTileKey(star.id))
        val directional = MimirNavFeedback.epoch
        val nonDirectional = MimirNavFeedback.stepEpoch
        assertTrue(dispatchMimirButtonPicker(authority, state, action) { })
        return (MimirNavFeedback.epoch - directional) to (MimirNavFeedback.stepEpoch - nonDirectional)
    }

    @Test
    fun `L1, R1 and X tell the router a value changed when the placement did, and say nothing at a limit`() {
        val mid = adjustingAt(MimirMarkPlacement(scale = 1.2f, offsetX = 3f, offsetY = -1.5f))
        for (action in listOf(ControllerAction.PAGE_LEFT, ControllerAction.PAGE_RIGHT, ControllerAction.SECONDARY)) {
            assertEquals("$action mid-range", 0L to 1L, heard(mid, action))
        }
        // One step in from a limit still changes; at the limit it does not (BM03: a dead-end press is silent).
        assertEquals("R1 from 60%", 0L to 1L, heard(adjustingAt(MimirMarkPlacement(MimirMarkScaleMin)), ControllerAction.PAGE_RIGHT))
        assertEquals("L1 from 160%", 0L to 1L, heard(adjustingAt(MimirMarkPlacement(MimirMarkScaleMax)), ControllerAction.PAGE_LEFT))
        assertEquals("L1 at 60%", 0L to 0L, heard(adjustingAt(MimirMarkPlacement(MimirMarkScaleMin)), ControllerAction.PAGE_LEFT))
        assertEquals("R1 at 160%", 0L to 0L, heard(adjustingAt(MimirMarkPlacement(MimirMarkScaleMax)), ControllerAction.PAGE_RIGHT))
        // X puts the image back; on an image already centred at 100% there is nothing to put back, and a placement off by the offset alone still has.
        assertEquals("X on the default placement", 0L to 0L, heard(adjustingAt(MimirMarkPlacement.Default), ControllerAction.SECONDARY))
        assertEquals("X on an image moved 0.5 dp", 0L to 1L, heard(adjustingAt(MimirMarkPlacement(offsetX = 0.5f)), ControllerAction.SECONDARY))
        assertEquals("X on an image sized to 105%", 0L to 1L, heard(adjustingAt(MimirMarkPlacement(scale = 1.05f)), ControllerAction.SECONDARY))
    }

    @Test
    fun `the D-pad tells the router the image moved, and says nothing at the 12 dp limit`() {
        val mid = adjustingAt(MimirMarkPlacement(scale = 1.2f, offsetX = 3f, offsetY = -1.5f))
        for (action in listOf(ControllerAction.NAV_UP, ControllerAction.NAV_DOWN, ControllerAction.NAV_LEFT, ControllerAction.NAV_RIGHT)) {
            assertEquals("$action mid-range", 1L to 0L, heard(mid, action))
        }
        val limit = MimirMarkOffsetLimitDp
        assertEquals("right at +12", 0L to 0L, heard(adjustingAt(MimirMarkPlacement(offsetX = limit)), ControllerAction.NAV_RIGHT))
        assertEquals("left at -12", 0L to 0L, heard(adjustingAt(MimirMarkPlacement(offsetX = -limit)), ControllerAction.NAV_LEFT))
        assertEquals("down at +12", 0L to 0L, heard(adjustingAt(MimirMarkPlacement(offsetY = limit)), ControllerAction.NAV_DOWN))
        assertEquals("up at -12", 0L to 0L, heard(adjustingAt(MimirMarkPlacement(offsetY = -limit)), ControllerAction.NAV_UP))
        // Back out of a limit is a real step.
        assertEquals("left from +12", 1L to 0L, heard(adjustingAt(MimirMarkPlacement(offsetX = limit)), ControllerAction.NAV_LEFT))
    }

    @Test
    fun `A and B end adjust mode without a click, as A on the other Appearance pickers plays none, and the tiles stay as they were`() {
        val mid = adjustingAt(MimirMarkPlacement(scale = 1.2f, offsetX = 3f, offsetY = -1.5f))
        for (action in listOf(ControllerAction.CONFIRM, ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK, ControllerAction.TERTIARY)) {
            assertEquals("$action in adjust", 0L to 0L, heard(mid, action))
        }
        // In the tiles L1 and R1 do nothing and X removes a tile: neither tells the router a value was stepped.
        assertEquals("L1 in the tiles", 0L to 0L, heard(open(), ControllerAction.PAGE_LEFT))
        assertEquals("R1 in the tiles", 0L to 0L, heard(open(), ControllerAction.PAGE_RIGHT))
        assertEquals("X in the tiles", 0L to 0L, heard(open(), ControllerAction.SECONDARY))
    }

    // ---- the stage's fit ------------------------------------------------------------------------------------------------------------

    @Test
    fun `the large coin is up to 3x and gives way on a short stage, never smaller than the real button`() {
        assertEquals("a Thor Main stage has room for the full 3x", 3f, adjustCoinScale(800f, 250f, 1f), 0f)
        // A 360 dp square at font 1.0 and 1.3: the stage is about 160 and 133 dp tall (less its padding).
        val roomy = adjustCoinScale(304f, 160f - 24f, 1f)
        val tight = adjustCoinScale(304f, 133f - 24f, 1.3f)
        assertTrue("short stage: $roomy", roomy in 1.5f..2.5f)
        assertTrue("short stage at font 1.3: $tight", tight in 1f..roomy)
        assertEquals("never below the real button's size", 1f, adjustCoinScale(40f, 20f, 1.3f), 0f)
        assertTrue("the width limits it too", adjustCoinScale(190f, 400f, 1f) < 3f)
        // The coin it asks for fits the room, caption included.
        val scale = adjustCoinScale(304f, 133f - 24f, 1.3f)
        assertTrue(56f * scale + 8f + 12f * 1.3f <= 133f - 24f + 56f)
    }
}
