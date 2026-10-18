package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.files.FileItem
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * **[FileBrowserMode.PICK_MUSIC_FOLDER]** -- Settings › Music › Music folder (spec 2026-09-30), the in-app
 * lane. Browses like PICK and commits the open directory through the pinned "Use this folder" row; what it
 * must NOT inherit from PICK is R-21's single-file commit, so a focused track is never an "ADD GAME". The
 * controller is driven against a real [DefaultFocusAuthority] on [FileBrowserImagePickTest]'s precedent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class FileBrowserMusicFolderPickTest {
    private val english = filesEnglish()

    private fun tree(): Map<String, List<FileItem>> = mapOf(
        "/root" to listOf(
            FileItem(name = "music", path = "/root/music", isDirectory = true),
            FileItem(name = "song.mp3", path = "/root/song.mp3", extension = "mp3"),
        ),
        "/root/music" to listOf(
            FileItem(name = "NES", path = "/root/music/NES", isDirectory = true),
            FileItem(name = "track.mp3", path = "/root/music/track.mp3", extension = "mp3"),
        ),
    )

    private class Sinks {
        var chosen: String? = null
        var dismissed = 0
        val deleted = mutableListOf<String>()
    }

    private fun controller(authority: DefaultFocusAuthority, sinks: Sinks) = FileBrowserController(
        authority = authority,
        rootPath = "/root",
        mode = FileBrowserMode.PICK_MUSIC_FOLDER,
        listDirectory = { path -> tree()[path].orEmpty() },
        onChoose = { sinks.chosen = it },
        onDismiss = { sinks.dismissed += 1 },
        onDelete = { sinks.deleted += it },
    )

    @Test
    fun `the mode has its own commit purpose and copy and header`() {
        val authority = DefaultFocusAuthority()
        val c = controller(authority, Sinks())
        c.open()

        assertTrue("the pinned row exists, like PICK", c.commitAvailable)
        assertEquals(FileBrowserCommitPurpose.CHOOSE_MUSIC_FOLDER, c.commitPurpose)
        assertNotEquals(
            "never CHOOSE_SOURCE, which is what R-21's single-file gate reads by name",
            FileBrowserCommitPurpose.CHOOSE_SOURCE,
            c.commitPurpose,
        )
        val copy = fileBrowserCommitCopy(english, FileBrowserCommitPurpose.CHOOSE_MUSIC_FOLDER)
        assertEquals("Use", copy.eyebrow)
        assertEquals("Use this folder", copy.label)
        assertEquals("ᛗ · Choose a music folder", fileBrowserHeaderTitle(english, FileBrowserMode.PICK_MUSIC_FOLDER))
    }

    @Test
    fun `CONFIRM on a focused track is inert -- no single-file commit in a folder pick`() {
        val authority = DefaultFocusAuthority()
        val sinks = Sinks()
        val c = controller(authority, sinks)
        c.open()
        authority.requestFocus(FileBrowserFocus.tileKey("/root/song.mp3"))

        assertTrue("a hard modal consumes the press", c.dispatch(ControllerAction.CONFIRM))

        assertNull("an mp3 is not a folder and must not be reported", sinks.chosen)
        assertEquals("the browser stays open", c.scope, authority.state.value.scope)
    }

    @Test
    fun `Use this folder reports the directory the player is standing in and closes first`() {
        val authority = DefaultFocusAuthority()
        val sinks = Sinks()
        val c = controller(authority, sinks)
        c.open()
        authority.requestFocus(FileBrowserFocus.tileKey("/root/music"))
        assertTrue(c.dispatch(ControllerAction.CONFIRM))
        assertEquals("/root/music", c.currentPath)
        assertNull(sinks.chosen)

        authority.requestFocus(FileBrowserFocus.commitKey("/root/music"))
        assertTrue(c.dispatch(ControllerAction.CONFIRM))

        assertEquals("/root/music", sinks.chosen)
        assertNotEquals("close-then-report, R-21's own arm", c.scope, authority.state.value.scope)
        assertEquals(0, sinks.dismissed)
    }

    @Test
    fun `nothing destructive is reachable -- every gate tests MANAGE`() {
        val authority = DefaultFocusAuthority()
        val sinks = Sinks()
        val c = controller(authority, sinks)
        c.open()
        authority.requestFocus(FileBrowserFocus.tileKey("/root/song.mp3"))

        assertTrue(c.dispatch(ControllerAction.SECONDARY))

        assertTrue("SECONDARY deletes nothing outside MANAGE", sinks.deleted.isEmpty())
        assertFalse(fileBrowserFileChoosable(FileBrowserMode.PICK_MUSIC_FOLDER, "cover.png"))
    }

    @Test
    fun `CANCEL at the root dismisses without choosing`() {
        val authority = DefaultFocusAuthority()
        val sinks = Sinks()
        val c = controller(authority, sinks)
        c.open()

        assertTrue(c.dispatch(ControllerAction.CANCEL))

        assertNull(sinks.chosen)
        assertEquals(1, sinks.dismissed)
    }
}
