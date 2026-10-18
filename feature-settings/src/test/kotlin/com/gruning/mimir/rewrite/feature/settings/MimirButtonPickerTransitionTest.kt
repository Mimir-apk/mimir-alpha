package com.gruning.mimir.rewrite.feature.settings

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.ui.MimirButtonMark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Mimir-button picker shell's rules: the background's without the rows (brief §G.3, R3: no Style row). */
class MimirButtonPickerTransitionTest {

    private val star = MimirButtonPickerMark("aaaaaaaa", "Your mark 1", "/app/marks/aaaaaaaa.png")
    private val bolt = MimirButtonPickerMark("bbbbbbbb", "Your mark 2", "/app/marks/bbbbbbbb.png")
    private val broken = MimirButtonPickerMark("cccccccc", "Your mark 3", null)
    private val saved = listOf(star, bolt, broken)
    private fun mark(m: MimirButtonPickerMark) = MimirButtonMark(m.id, m.path!!)
    private fun open(current: MimirButtonMark?) = mimirButtonPickerInitialState(current, saved)

    @Test
    fun `opens on the tile in use`() {
        assertEquals(MimirButtonRuneKey, open(null).initialFocusKey())
        assertEquals(mimirButtonTileKey(bolt.id), open(mark(bolt)).initialFocusKey())
        assertEquals(bolt.id, open(mark(bolt)).previewId)
    }

    @Test
    fun `A on Add image settles the preview back on the tile in use`() {
        val passedBolt = mimirButtonPickerOnFocus(open(mark(star)), mimirButtonTileKey(bolt.id))
        assertEquals(bolt.id, passedBolt.previewId)
        val opened = mimirButtonPickerTransition(passedBolt, ControllerAction.CONFIRM, MimirButtonAddKey)
        assertEquals(MimirButtonPickerCommand.Import, opened.command)
        assertEquals(star.id, opened.state.previewId)
        assertTrue("the picker has left for the file picker", opened.state.importOpen)
        assertEquals("a cancelled file picker lands on + Upload, the tile the picker left from", MimirButtonAddKey, opened.state.initialFocusKey())
        assertEquals("while the stage keeps showing the tile in use", star.id, opened.state.previewId)
        assertEquals("landing on + Upload is where that ends", false, mimirButtonPickerOnFocus(opened.state, MimirButtonAddKey).importOpen)
        assertEquals("and the next mount is the tile in use again", mimirButtonTileKey(star.id), mimirButtonPickerOnFocus(opened.state, MimirButtonAddKey).initialFocusKey())
        assertEquals("a tile focused on the way does not end it", true, mimirButtonPickerOnFocus(opened.state, mimirButtonTileKey(bolt.id)).importOpen)
        assertNull(mimirButtonPickerTransition(mimirButtonPickerOnFocus(open(null), mimirButtonTileKey(bolt.id)), ControllerAction.CONFIRM, MimirButtonAddKey).state.previewId)
    }

    @Test
    fun `focus lands on the mark an import just added, and on the tile in use otherwise`() {
        val fresh = MimirButtonPickerMark("dddddddd", "Your mark 4", "/app/marks/dddddddd.png")
        val landed = open(mark(star)).withImportLanded(saved + fresh, importedId = fresh.id, note = "Uploaded · Saved")
        assertEquals(fresh.id, landed.previewId)
        assertEquals(star.id, landed.current?.id)
        assertEquals(mimirButtonTileKey(fresh.id), landed.initialFocusKey())
        assertEquals(mimirButtonTileKey(star.id), open(mark(star)).initialFocusKey())
        assertEquals(mimirButtonTileKey(star.id), mimirButtonPickerOnFocus(open(mark(star)), MimirButtonRuneKey).initialFocusKey())
        val refused = open(null).withImportLanded(saved, importedId = null, note = "That image couldn’t be used.")
        assertEquals(MimirButtonRuneKey, refused.initialFocusKey())
        // The player left from + Upload: an import that added nothing leaves focus there, and one that added a mark ends it for the new tile.
        val left = mimirButtonPickerTransition(open(mark(star)), ControllerAction.CONFIRM, MimirButtonAddKey).state
        assertEquals(MimirButtonAddKey, left.withImportLanded(saved, importedId = null, note = "That image couldn’t be used.").initialFocusKey())
        assertEquals(mimirButtonTileKey(fresh.id), left.withImportLanded(saved + fresh, importedId = fresh.id, note = "Uploaded · Saved").initialFocusKey())
    }

    @Test
    fun `a system picker dismissed with nothing chosen ends the return to + Upload`() {
        val left = mimirButtonPickerTransition(open(mark(star)), ControllerAction.CONFIRM, MimirButtonAddKey).state
        assertTrue("the player left from + Upload", left.importOpen)
        val cancelled = left.withImportCancelled()
        assertFalse(cancelled.importOpen)
        assertEquals("a remount now lands on the tile in use", mimirButtonTileKey(star.id), cancelled.initialFocusKey())
        assertEquals("nothing else changes", left.copy(importOpen = false), cancelled)
        assertEquals("a picker that never left is untouched", open(null), open(null).withImportCancelled())
    }

    @Test
    fun `focus previews only`() {
        val previewing = mimirButtonPickerOnFocus(open(null), mimirButtonTileKey(star.id))
        assertEquals(mark(star), previewing.previewMark())
        assertNull(previewing.current)
        assertNull(mimirButtonPickerOnFocus(previewing, MimirButtonRuneKey).previewMark())
        assertNull("an unavailable mark previews as the rune", mimirButtonPickerOnFocus(open(null), mimirButtonTileKey(broken.id)).previewMark())
    }

    @Test
    fun `A commits the focused tile and the rune commits null`() {
        val commit = mimirButtonPickerTransition(open(null), ControllerAction.CONFIRM, mimirButtonTileKey(star.id))
        assertEquals(MimirButtonPickerCommand.Commit(mark(star)), commit.command)
        assertEquals(mark(star), commit.state.current)
        assertEquals(MimirButtonNoteInUse, commit.state.note)
        val rune = mimirButtonPickerTransition(commit.state, ControllerAction.CONFIRM, MimirButtonRuneKey)
        assertEquals(MimirButtonPickerCommand.Commit(null), rune.command)
        assertNull(rune.state.current)
        assertEquals(MimirButtonPickerCommand.Import, mimirButtonPickerTransition(open(null), ControllerAction.CONFIRM, MimirButtonAddKey).command)
        val unavailable = mimirButtonPickerTransition(open(null), ControllerAction.CONFIRM, mimirButtonTileKey(broken.id))
        assertNull(unavailable.command)
        assertEquals(MimirButtonNoteUnavailable, unavailable.state.note)
    }

    @Test
    fun `X removes a saved mark that is not the current one`() {
        val removed = mimirButtonPickerTransition(open(mark(star)), ControllerAction.SECONDARY, mimirButtonTileKey(bolt.id))
        assertEquals(MimirButtonPickerCommand.Remove(bolt.id), removed.command)
        assertTrue(removed.state.saved.none { it.id == bolt.id })
        val refused = mimirButtonPickerTransition(open(mark(star)), ControllerAction.SECONDARY, mimirButtonTileKey(star.id))
        assertNull(refused.command)
        assertEquals(MimirButtonNoteRemoveCurrent, refused.state.note)
        assertNull(mimirButtonPickerTransition(open(null), ControllerAction.SECONDARY, MimirButtonRuneKey).command)
    }

    @Test
    fun `B leaves, and Y does nothing off a saved image`() {
        assertEquals(MimirButtonPickerCommand.Dismiss, mimirButtonPickerTransition(open(null), ControllerAction.CANCEL, null).command)
        assertEquals(MimirButtonPickerCommand.Dismiss, mimirButtonPickerTransition(open(null), ControllerAction.SYSTEM_BACK, null).command)
        for (focused in listOf(MimirButtonRuneKey, MimirButtonAddKey, mimirButtonTileKey(broken.id))) {
            val idle = mimirButtonPickerTransition(open(null), ControllerAction.TERTIARY, focused)
            assertNull(idle.command)
            assertEquals(open(null), idle.state)
        }
        // On a saved image Y opens ADJUST (MimirButtonPickerAdjustTest owns that mode).
        assertTrue(mimirButtonPickerTransition(open(null), ControllerAction.TERTIARY, mimirButtonTileKey(star.id)).state.adjust != null)
    }

    @Test
    fun `the tiles are the rune then the saved marks then Add`() {
        val tiles = open(mark(star)).tiles()
        assertEquals(5, tiles.size)
        assertEquals(MimirButtonDefaultLabel, (tiles.first() as AppearancePickerTile.Default).label)
        assertTrue((tiles[1] as AppearancePickerTile.Saved).current)
        assertTrue(tiles.last() is AppearancePickerTile.Add)
        assertEquals("Your mark 2", mimirButtonMarkLabel(1))
    }
}
