package com.gruning.mimir.rewrite.feature.settings

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.ui.MimirSceneGround
import com.gruning.mimir.rewrite.core.ui.MimirSceneGroundBlur
import com.gruning.mimir.rewrite.core.ui.MimirSceneGroundDim
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Dim and Blur rows keep only what A keeps. Pinned on the pure reducer:
 *  - focus previews only: the stage follows the focused step, [BackgroundPickerState.current] never moves on focus;
 *  - the selected marker reads what is kept ([BackgroundPickerState.keptDim] / [BackgroundPickerState.keptBlur]), never
 *    the preview;
 *  - A commits that row only: A on a Dim step keeps that dim with the KEPT blur, A on a Blur step keeps that blur with
 *    the KEPT dim, whatever focus passed over on the other row;
 *  - leaving a row without A returns the stage to the kept step; a tile previews at the kept steps.
 */
class BackgroundPickerStepCommitTest {

    private val dark = BackgroundPickerImage("aaaaaaaa", "Your image 1", "/app/aaaaaaaa.jpg", 1920, 1080, p90 = 0.05f)
    private val other = BackgroundPickerImage("cccccccc", "Your image 3", "/app/cccccccc.jpg", 1920, 1080, p90 = 0.2f)
    private val saved = listOf(dark, other)

    private fun ground(image: BackgroundPickerImage, dim: MimirSceneGroundDim = MimirSceneGroundDim.MEDIUM, blur: MimirSceneGroundBlur = MimirSceneGroundBlur.OFF) =
        MimirSceneGround(image.id, image.path!!, image.width, image.height, image.p90, dim, blur)

    private fun open(current: MimirSceneGround?) = backgroundPickerInitialState(MimirThemeId.DEFAULT, current, saved)

    /** Focus visiting [keys] in turn: each visit is one `backgroundPickerOnFocus`, as the host writes them. */
    private fun focus(state: BackgroundPickerState, vararg keys: Any): BackgroundPickerState =
        keys.fold(state) { s, key -> backgroundPickerOnFocus(s, key as FocusKey) }

    private fun dim(step: MimirSceneGroundDim) = backgroundDimKey(step)
    private fun blur(step: MimirSceneGroundBlur) = backgroundBlurKey(step)

    @Test
    fun `focus across Dim, then A on Blur, keeps the kept dim`() {
        // The defect: moving across Light, Strong and Deep, then A on Soft, kept Deep -- the last step focus passed over.
        val kept = ground(dark, MimirSceneGroundDim.MEDIUM, MimirSceneGroundBlur.OFF)
        val passed = focus(open(kept), dim(MimirSceneGroundDim.LIGHT), dim(MimirSceneGroundDim.STRONG), dim(MimirSceneGroundDim.DEEP), blur(MimirSceneGroundBlur.SOFT))
        assertEquals("nothing about focus changed what is kept", kept, passed.current)
        val transition = backgroundPickerTransition(passed, ControllerAction.CONFIRM, blur(MimirSceneGroundBlur.SOFT))
        assertEquals(BackgroundPickerCommand.Commit(ground(dark, MimirSceneGroundDim.MEDIUM, MimirSceneGroundBlur.SOFT)), transition.command)
        assertEquals(MimirSceneGroundDim.MEDIUM, transition.state.current?.dim)
        assertEquals(MimirSceneGroundBlur.SOFT, transition.state.current?.blur)
        // Even a Dim preview still standing (no focus event between) is not what A on Blur keeps.
        val standing = open(kept).copy(previewDim = MimirSceneGroundDim.DEEP)
        val direct = backgroundPickerTransition(standing, ControllerAction.CONFIRM, blur(MimirSceneGroundBlur.STRONG))
        assertEquals(BackgroundPickerCommand.Commit(ground(dark, MimirSceneGroundDim.MEDIUM, MimirSceneGroundBlur.STRONG)), direct.command)
    }

    @Test
    fun `the selected marker shows the kept step while focus previews another`() {
        val state = focus(open(ground(dark, MimirSceneGroundDim.MEDIUM, MimirSceneGroundBlur.SOFT)), dim(MimirSceneGroundDim.DEEP))
        assertEquals("the marker: what is kept", MimirSceneGroundDim.MEDIUM, state.keptDim)
        assertEquals("the stage: what focus previews", MimirSceneGroundDim.DEEP, state.dim)
        assertEquals(MimirSceneGroundDim.DEEP, state.previewGround()?.dim)
        assertEquals(MimirSceneGroundBlur.SOFT, state.previewGround()?.blur)
        val onBlur = focus(state, blur(MimirSceneGroundBlur.STRONG))
        assertEquals(MimirSceneGroundBlur.SOFT, onBlur.keptBlur)
        assertEquals(MimirSceneGroundBlur.STRONG, onBlur.blur)
        // Atmosphere in use: the rows mark the steps a tile is first kept at.
        assertEquals(MimirSceneGroundDim.MEDIUM, open(null).keptDim)
        assertEquals(MimirSceneGroundBlur.OFF, open(null).keptBlur)
        assertEquals(MimirSceneGroundDim.MEDIUM, focus(open(null), dim(MimirSceneGroundDim.DEEP)).keptDim)
    }

    @Test
    fun `leaving the Dim row without A reverts the stage`() {
        val kept = ground(dark, MimirSceneGroundDim.MEDIUM, MimirSceneGroundBlur.OFF)
        val onDeep = focus(open(kept), dim(MimirSceneGroundDim.DEEP))
        assertEquals(MimirSceneGroundDim.DEEP, onDeep.previewGround()?.dim)
        // Up onto a tile: the tile previews at the kept steps.
        val backOnTile = focus(onDeep, backgroundTileKey(dark.id))
        assertNull(backOnTile.previewDim)
        assertEquals(MimirSceneGroundDim.MEDIUM, backOnTile.previewGround()?.dim)
        // Across to the Blur row: the dim reverts while the blur previews.
        val onBlur = focus(onDeep, blur(MimirSceneGroundBlur.SOFT))
        assertNull(onBlur.previewDim)
        assertEquals(MimirSceneGroundDim.MEDIUM, onBlur.dim)
        assertEquals(MimirSceneGroundBlur.SOFT, onBlur.blur)
        // Another tile previews at the kept steps, not at the steps passed over.
        val onOther = focus(onBlur, backgroundTileKey(other.id))
        assertEquals(other.id, onOther.previewId)
        assertEquals(ground(other, MimirSceneGroundDim.MEDIUM, MimirSceneGroundBlur.OFF), onOther.previewGround())
        // + Add image keeps the hovered tile on the stage (A settles it) and drops the step previews.
        val onAdd = focus(onDeep, BackgroundAddKey)
        assertEquals(dark.id, onAdd.previewId)
        assertNull(onAdd.previewDim)
        assertEquals(kept, onAdd.current)
    }

    @Test
    fun `A on Dim keeps the kept blur`() {
        val kept = ground(dark, MimirSceneGroundDim.MEDIUM, MimirSceneGroundBlur.SOFT)
        // Focus passed Strong blur on the way to the Dim row.
        val state = focus(open(kept), blur(MimirSceneGroundBlur.STRONG), dim(MimirSceneGroundDim.DEEP))
        val transition = backgroundPickerTransition(state, ControllerAction.CONFIRM, dim(MimirSceneGroundDim.DEEP))
        assertEquals(BackgroundPickerCommand.Commit(ground(dark, MimirSceneGroundDim.DEEP, MimirSceneGroundBlur.SOFT)), transition.command)
        // Once kept, the previews are spent: the rows and the stage sit on the kept steps.
        assertNull(transition.state.previewDim)
        assertNull(transition.state.previewBlur)
        assertEquals(MimirSceneGroundDim.DEEP, transition.state.keptDim)
        assertEquals(MimirSceneGroundDim.DEEP, transition.state.dim)
        assertEquals(MimirSceneGroundBlur.SOFT, transition.state.blur)
        assertEquals(BackgroundNoteInUse, transition.state.note)
        // Atmosphere in use: A on a step keeps nothing, and the kept steps stay the defaults.
        val atmosphere = backgroundPickerTransition(focus(open(null), dim(MimirSceneGroundDim.DEEP)), ControllerAction.CONFIRM, dim(MimirSceneGroundDim.DEEP))
        assertNull(atmosphere.command)
        assertEquals(MimirSceneGroundDim.MEDIUM, atmosphere.state.keptDim)
    }
}
