package com.gruning.mimir.rewrite.feature.settings

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.ui.MimirSceneGround
import com.gruning.mimir.rewrite.core.ui.MimirSceneGroundBlur
import com.gruning.mimir.rewrite.core.ui.MimirSceneGroundDim
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Background picker's rules (concept A, brief §G.2), pinned on the pure reducer:
 *  - focus starts on the tile in use, and moving focus changes ONLY the stage's preview;
 *  - A uses the focused tile, keeps the focused Dim or Blur step, or opens the import on + Add image;
 *  - every Dim step can be kept on any image: there is no readability floor , the stored dim is the one chosen;
 *  - X removes a saved image no theme uses; the current choice and another theme's are refused with the copy;
 *  - B leaves and reverts anything not kept: nothing commits but by A; Y does nothing.
 */
class BackgroundPickerTransitionTest {

    private val dark = BackgroundPickerImage("aaaaaaaa", "Your image 1", "/app/aaaaaaaa.jpg", 1920, 1080, p90 = 0.05f)
    private val bright = BackgroundPickerImage("bbbbbbbb", "Your image 2", "/app/bbbbbbbb.jpg", 1920, 1080, p90 = 0.95f)
    private val hels = BackgroundPickerImage("cccccccc", "Your image 3", "/app/cccccccc.jpg", 1920, 1080, p90 = 0.2f, usedBy = listOf("Hel"))
    private val broken = BackgroundPickerImage("dddddddd", "Your image 4", null, 0, 0, p90 = 0f)
    private val saved = listOf(dark, bright, hels, broken)

    private fun ground(image: BackgroundPickerImage, dim: MimirSceneGroundDim = MimirSceneGroundDim.MEDIUM, blur: MimirSceneGroundBlur = MimirSceneGroundBlur.OFF) =
        MimirSceneGround(image.id, image.path!!, image.width, image.height, image.p90, dim, blur)

    private fun open(current: MimirSceneGround?) = backgroundPickerInitialState(MimirThemeId.DEFAULT, current, saved)

    @Test
    fun `the picker opens on the tile in use with its steps`() {
        val atmosphere = open(null)
        assertEquals(BackgroundAtmosphereKey, atmosphere.initialFocusKey())
        assertNull(atmosphere.previewId)
        assertEquals(MimirSceneGroundDim.MEDIUM, atmosphere.dim)
        assertEquals(MimirSceneGroundBlur.OFF, atmosphere.blur)

        val committed = open(ground(dark, MimirSceneGroundDim.STRONG, MimirSceneGroundBlur.SOFT))
        assertEquals(backgroundTileKey(dark.id), committed.initialFocusKey())
        assertEquals(dark.id, committed.previewId)
        assertEquals(MimirSceneGroundDim.STRONG, committed.dim)
        assertEquals(MimirSceneGroundBlur.SOFT, committed.blur)
    }

    @Test
    fun `moving across tiles or steps changes only the stage`() {
        val state = open(null)
        val onBright = backgroundPickerOnFocus(state, backgroundTileKey(bright.id))
        assertEquals(bright.id, onBright.previewId)
        assertNull("nothing committed by focus", onBright.current)
        val onDeep = backgroundPickerOnFocus(onBright, backgroundDimKey(MimirSceneGroundDim.DEEP))
        assertEquals(MimirSceneGroundDim.DEEP, onDeep.dim)
        assertEquals(bright.id, onDeep.previewId)
        val onStrongBlur = backgroundPickerOnFocus(onDeep, backgroundBlurKey(MimirSceneGroundBlur.STRONG))
        assertEquals(MimirSceneGroundBlur.STRONG, onStrongBlur.blur)
        // Walk 32k: leaving the Dim row without A returns the stage to the kept dim; the previewed Deep was never kept.
        assertEquals("leaving the Dim row reverts its preview", MimirSceneGroundDim.MEDIUM, onStrongBlur.dim)
        val backToAtmosphere = backgroundPickerOnFocus(onStrongBlur, BackgroundAtmosphereKey)
        assertNull(backToAtmosphere.previewId)
        assertEquals("the rows sit on the kept steps again", MimirSceneGroundDim.MEDIUM, backToAtmosphere.dim)
        assertEquals(MimirSceneGroundBlur.OFF, backToAtmosphere.blur)
        assertEquals(state, backgroundPickerOnFocus(state, null))
    }

    @Test
    fun `the stage previews the image at the rows steps exactly, a bright image at Light stays Light`() {
        val state = backgroundPickerOnFocus(open(null), backgroundTileKey(bright.id)).copy(previewDim = MimirSceneGroundDim.LIGHT)
        val preview = state.previewGround()!!
        assertEquals(bright.id, preview.id)
        assertEquals("no floor raises Light for a near-white image", MimirSceneGroundDim.LIGHT, preview.dim)
        assertNull(open(null).previewGround())
        assertNull("an unavailable image previews as Atmosphere", backgroundPickerOnFocus(open(null), backgroundTileKey(broken.id)).previewGround())
    }
    @Test
    fun `A on a saved tile commits it at the kept steps`() {
        // Walk 32k: the rows sit on what is KEPT (the ground in use), so a new tile is kept at those steps -- the steps
        // focus passed over on the way are previews, spent the moment focus left their row.
        val state = backgroundPickerOnFocus(
            backgroundPickerOnFocus(open(ground(hels, MimirSceneGroundDim.STRONG, MimirSceneGroundBlur.SOFT)), backgroundDimKey(MimirSceneGroundDim.DEEP)),
            backgroundTileKey(dark.id),
        )
        val transition = backgroundPickerTransition(state, ControllerAction.CONFIRM, backgroundTileKey(dark.id))
        val commit = transition.command as BackgroundPickerCommand.Commit
        assertEquals(ground(dark, MimirSceneGroundDim.STRONG, MimirSceneGroundBlur.SOFT), commit.ground)
        assertEquals(commit.ground, transition.state.current)
        assertEquals(dark.id, transition.state.previewId)
        assertEquals(BackgroundNoteInUse, transition.state.note)
    }

    @Test
    fun `A on Atmosphere commits the default`() {
        val state = open(ground(dark))
        val transition = backgroundPickerTransition(state, ControllerAction.CONFIRM, BackgroundAtmosphereKey)
        assertEquals(BackgroundPickerCommand.Commit(null), transition.command)
        assertNull(transition.state.current)
        assertNull(transition.state.previewId)
    }

    @Test
    fun `A on a Dim or Blur step keeps that step for the previewed image`() {
        val state = backgroundPickerOnFocus(open(ground(dark)), backgroundDimKey(MimirSceneGroundDim.DEEP))
        val dim = backgroundPickerTransition(state, ControllerAction.CONFIRM, backgroundDimKey(MimirSceneGroundDim.DEEP))
        assertEquals(BackgroundPickerCommand.Commit(ground(dark, MimirSceneGroundDim.DEEP)), dim.command)
        val blur = backgroundPickerTransition(dim.state, ControllerAction.CONFIRM, backgroundBlurKey(MimirSceneGroundBlur.STRONG))
        assertEquals(BackgroundPickerCommand.Commit(ground(dark, MimirSceneGroundDim.DEEP, MimirSceneGroundBlur.STRONG)), blur.command)
    }

    @Test
    fun `A on a step while Atmosphere is previewed commits nothing`() {
        val state = open(null)
        val transition = backgroundPickerTransition(state, ControllerAction.CONFIRM, backgroundDimKey(MimirSceneGroundDim.DEEP))
        assertNull(transition.command)
        assertNull(transition.state.note)
    }

    @Test
    fun `every Dim step can be kept on a near-white image, and the stored dim is the one chosen`() {
        //  -- no step is greyed, refused or raised.
        val state = backgroundPickerOnFocus(open(null), backgroundTileKey(bright.id))
        MimirSceneGroundDim.entries.forEach { step ->
            val kept = backgroundPickerTransition(backgroundPickerOnFocus(state, backgroundDimKey(step)), ControllerAction.CONFIRM, backgroundDimKey(step))
            assertEquals("$step is kept as chosen", step, (kept.command as BackgroundPickerCommand.Commit).ground!!.dim)
            assertEquals(BackgroundNoteInUse, kept.state.note)
        }
        // Committing a tile while the kept dim is Light writes Light, however bright the new image is.
        val keptLight = backgroundPickerOnFocus(open(ground(dark, MimirSceneGroundDim.LIGHT)), backgroundTileKey(bright.id))
        val committed = backgroundPickerTransition(keptLight, ControllerAction.CONFIRM, backgroundTileKey(bright.id))
        assertEquals(MimirSceneGroundDim.LIGHT, (committed.command as BackgroundPickerCommand.Commit).ground!!.dim)
        assertEquals(MimirSceneGroundDim.LIGHT, committed.state.keptDim)
        assertEquals(MimirSceneGroundDim.LIGHT, committed.state.dim)
    }

    @Test
    fun `the picker carries no too-bright copy, no greyed step and no raised save`() {
        val picker = java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/feature/settings/BackgroundPicker.kt").readText()
        listOf("too bright", "Too bright", "TooBright", "enabledDims", "dimGuardrailNote", "mimirGroundDimFloor", "clearsTextOn", "effectiveDim").forEach { gone ->
            assertTrue("$gone is gone from the picker", !picker.contains(gone))
        }
    }

    @Test
    fun `A on Add image opens the import`() {
        assertEquals(BackgroundPickerCommand.Import, backgroundPickerTransition(open(null), ControllerAction.CONFIRM, BackgroundAddKey).command)
    }

    @Test
    fun `A on Add image settles the preview back on the tile in use`() {
        // The pad passed the bright tile on its way to + Add image, so the stage previews it while the ring sits on Add.
        val passedBright = backgroundPickerOnFocus(open(ground(dark)), backgroundTileKey(bright.id))
        assertEquals(bright.id, passedBright.previewId)
        val opened = backgroundPickerTransition(passedBright, ControllerAction.CONFIRM, BackgroundAddKey)
        assertEquals(BackgroundPickerCommand.Import, opened.command)
        assertEquals("the preview is back on the committed tile", dark.id, opened.state.previewId)
        assertTrue("the picker has left for the file picker", opened.state.importOpen)
        assertEquals("so the remount after a cancelled file picker lands on + Upload, the tile it left from", BackgroundAddKey, opened.state.initialFocusKey())
        assertEquals("while the stage keeps showing the tile in use", dark.id, opened.state.previewId)
        assertEquals("landing on + Upload is where that ends", false, backgroundPickerOnFocus(opened.state, BackgroundAddKey).importOpen)
        assertEquals("and the next mount is the tile in use again", backgroundTileKey(dark.id), backgroundPickerOnFocus(opened.state, BackgroundAddKey).initialFocusKey())
        assertEquals("a tile focused on the way does not end it", true, backgroundPickerOnFocus(opened.state, backgroundTileKey(bright.id)).importOpen)
        assertNull("Atmosphere is the tile in use here", backgroundPickerTransition(backgroundPickerOnFocus(open(null), backgroundTileKey(bright.id)), ControllerAction.CONFIRM, BackgroundAddKey).state.previewId)
    }

    @Test
    fun `focus lands on the tile an import just added, and on the tile in use otherwise`() {
        val fresh = BackgroundPickerImage("eeeeeeee", "Your image 5", "/app/eeeeeeee.jpg", 1920, 1080, p90 = 0.3f)
        val landed = open(ground(dark)).withImportLanded(saved + fresh, importedId = fresh.id, note = "Uploaded · Saved")
        assertEquals(fresh.id, landed.previewId)
        assertEquals("Uploaded · Saved", landed.note)
        assertEquals(dark.id, landed.current?.id)
        assertEquals("the new tile: A commits it", backgroundTileKey(fresh.id), landed.initialFocusKey())
        // The preview of the tile in use is not a landing: the tile in use is.
        assertEquals(backgroundTileKey(dark.id), open(ground(dark)).initialFocusKey())
        // A preview on Atmosphere while an image is current lands on the image in use.
        assertEquals(backgroundTileKey(dark.id), backgroundPickerOnFocus(open(ground(dark)), BackgroundAtmosphereKey).initialFocusKey())
        assertEquals(BackgroundAtmosphereKey, open(null).initialFocusKey())
        // An import that added nothing (refused, or already the current tile) leaves the preview where it was.
        val refused = open(ground(dark)).withImportLanded(saved, importedId = null, note = "Saved is full. Remove an unused choice first.")
        assertEquals(dark.id, refused.previewId)
        assertEquals(backgroundTileKey(dark.id), refused.initialFocusKey())
        // The player left from + Upload: an import that added nothing leaves focus there, and one that added a tile ends it for the new tile.
        val left = backgroundPickerTransition(open(ground(dark)), ControllerAction.CONFIRM, BackgroundAddKey).state
        assertEquals(BackgroundAddKey, left.withImportLanded(saved, importedId = null, note = "Saved is full. Remove an unused choice first.").initialFocusKey())
        assertEquals(backgroundTileKey(fresh.id), left.withImportLanded(saved + fresh, importedId = fresh.id, note = "Uploaded · Saved").initialFocusKey())
    }

    @Test
    fun `a system picker dismissed with nothing chosen ends the return to + Upload`() {
        val left = backgroundPickerTransition(open(ground(dark)), ControllerAction.CONFIRM, BackgroundAddKey).state
        assertTrue("the player left from + Upload", left.importOpen)
        val cancelled = left.withImportCancelled()
        assertFalse(cancelled.importOpen)
        assertEquals("a remount now lands on the tile in use", backgroundTileKey(dark.id), cancelled.initialFocusKey())
        assertEquals("nothing else changes", left.copy(importOpen = false), cancelled)
        assertEquals("a picker that never left is untouched", open(null), open(null).withImportCancelled())
    }

    @Test
    fun `A on an unavailable image commits nothing and says so`() {
        val transition = backgroundPickerTransition(open(null), ControllerAction.CONFIRM, backgroundTileKey(broken.id))
        assertNull(transition.command)
        assertEquals(BackgroundNoteUnavailable, transition.state.note)
    }

    @Test
    fun `X removes a saved image no theme uses`() {
        val state = backgroundPickerOnFocus(open(null), backgroundTileKey(dark.id))
        val transition = backgroundPickerTransition(state, ControllerAction.SECONDARY, backgroundTileKey(dark.id))
        assertEquals(BackgroundPickerCommand.Remove(dark.id), transition.command)
        assertTrue(transition.state.saved.none { it.id == dark.id })
        assertNull("the removed image is no longer previewed", transition.state.previewId)
        assertEquals(BackgroundNoteRemoved, transition.state.note)
    }

    @Test
    fun `X refuses the current choice and another theme's image`() {
        val current = backgroundPickerTransition(open(ground(dark)), ControllerAction.SECONDARY, backgroundTileKey(dark.id))
        assertNull(current.command)
        assertEquals(BackgroundNoteRemoveCurrent, current.state.note)
        val other = backgroundPickerTransition(open(null), ControllerAction.SECONDARY, backgroundTileKey(hels.id))
        assertNull(other.command)
        assertEquals("Hel uses this image. Switch Hel first.", other.state.note)
        assertEquals(4, other.state.saved.size)
    }

    @Test
    fun `X on the default or Add image does nothing`() {
        assertNull(backgroundPickerTransition(open(null), ControllerAction.SECONDARY, BackgroundAtmosphereKey).command)
        assertNull(backgroundPickerTransition(open(null), ControllerAction.SECONDARY, BackgroundAddKey).command)
    }

    @Test
    fun `B leaves and reverts anything not kept`() {
        val previewing = backgroundPickerOnFocus(open(ground(dark)), backgroundTileKey(bright.id)).copy(previewDim = MimirSceneGroundDim.DEEP)
        val transition = backgroundPickerTransition(previewing, ControllerAction.CANCEL, backgroundTileKey(bright.id))
        assertEquals(BackgroundPickerCommand.Dismiss, transition.command)
        assertEquals("nothing was committed by the preview", ground(dark), transition.state.current)
        assertEquals(BackgroundPickerCommand.Dismiss, backgroundPickerTransition(previewing, ControllerAction.SYSTEM_BACK, null).command)
    }

    @Test
    fun `Y and the rest do nothing`() {
        val state = open(ground(dark))
        listOf(ControllerAction.TERTIARY, ControllerAction.SYSTEM_MENU, ControllerAction.PAGE_LEFT).forEach { action ->
            val transition = backgroundPickerTransition(state, action, backgroundTileKey(bright.id))
            assertNull(action.name, transition.command)
            assertEquals(action.name, state, transition.state)
        }
    }

    @Test
    fun `the tiles are Atmosphere then the saved images then Add`() {
        val tiles = open(ground(bright)).tiles()
        assertEquals(6, tiles.size)
        assertTrue(tiles.first() is AppearancePickerTile.Default)
        assertTrue(tiles.last() is AppearancePickerTile.Add)
        val brightTile = tiles[2] as AppearancePickerTile.Saved
        assertTrue(brightTile.current)
        assertEquals(AppearanceTileBadgeCurrent, appearanceTileBadge(brightTile, defaultIsCurrent = false))
        assertEquals("Your image 2, in use", appearanceTileSpokenLabel(brightTile, defaultIsCurrent = false))
        val brokenTile = tiles[4] as AppearancePickerTile.Saved
        assertEquals(AppearanceTileBadgeUnavailable, appearanceTileBadge(brokenTile, defaultIsCurrent = false))
        val helsTile = tiles[3] as AppearancePickerTile.Saved
        assertEquals("Your image 3, saved, used by Hel", appearanceTileSpokenLabel(helsTile, defaultIsCurrent = false))
    }

    @Test
    fun `the subline names the theme and both screens only on a dual-screen device`() {
        assertEquals("For Hel · behind your shelves", backgroundPickerSubtitle("Hel", dualScreen = false))
        assertEquals("For Hel · behind your shelves, on both screens", backgroundPickerSubtitle("Hel", dualScreen = true))
        assertEquals("Your image 3", backgroundImageLabel(2))
    }

    @Test
    fun `the footer names X REMOVE on a saved image, no X on Atmosphere or Add, and A UPLOAD on Add`() {
        val browse = open(null)
        // A saved image gets X REMOVE.
        assertEquals(AppearancePickerControlHint, browse.controlHint(backgroundTileKey(dark.id)))
        assertTrue("saved has X REMOVE", browse.controlHint(backgroundTileKey(dark.id)).contains("X REMOVE"))
        assertEquals(AppearancePickerControlHint, browse.controlHint(backgroundTileKey(bright.id)))
        // Atmosphere (and no focus) gets no X and A USE: A applies the built-in ground.
        for (focused in listOf(BackgroundAtmosphereKey, null)) {
            val hint = browse.controlHint(focused)
            assertEquals(AppearancePickerControlHintNoRemove, hint)
            assertEquals("A USE · B BACK", hint)
            assertFalse("no X on $focused", hint.contains("X "))
        }
        // Add gets no X either, but A there opens the file picker -- it uses nothing -- so the footer says the tile's own word .
        val upload = browse.controlHint(BackgroundAddKey)
        assertEquals(AppearancePickerControlHintUpload, upload)
        assertEquals("A UPLOAD · B BACK", upload)
        assertEquals("the footer names the word the tile wears", AppearanceUploadLabel.uppercase(), upload.removePrefix("A ").substringBefore(" "))
        assertFalse("no X on Add", upload.contains("X "))
        assertFalse("and nothing to USE on Add", upload.contains("USE"))
    }
}
