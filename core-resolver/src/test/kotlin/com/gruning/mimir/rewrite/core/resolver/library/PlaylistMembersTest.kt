package com.gruning.mimir.rewrite.core.resolver.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * **an `.m3u`'s members are never deleted with the playlist**, and the card says how
 * many discs it left. This is the reading side: which listed discs are there and are not going.
 */
class PlaylistMembersTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun File.make(name: String, text: String = name): File = File(this, name).apply {
        parentFile?.mkdirs()
        writeText(text)
    }

    @Test
    fun `a playlist's members are the listed files that exist, read with the scanner's own syntax`() {
        val folder = temp.newFolder()
        folder.make("FF9 (Disc 1).chd")
        folder.make("FF9 (Disc 2).chd")
        folder.make("discs/FF9 (Disc 3).chd")
        val m3u = folder.make(
            "FF9.m3u",
            listOf(
                "﻿#EXTM3U",
                "# a comment",
                "FF9 (Disc 1).chd",
                "\"FF9 (Disc 2).chd\"", // quoted, as the scanner's unquote reads it
                "discs/FF9 (Disc 3).chd", // a subfolder resolves against the playlist's folder
                "FF9 (Disc 4).chd", // listed, not there
                "../outside.chd", // above the folder: normalizeRelative refuses it
                "content://tree/doc/FF9 (Disc 5).chd", // a SAF document is not a file this process can count
                "FF9 (Disc 1).chd", // twice
            ).joinToString("\r\n"),
        )

        assertEquals(
            listOf("FF9 (Disc 1).chd", "FF9 (Disc 2).chd", "FF9 (Disc 3).chd"),
            PlaylistMembers.membersOf(m3u).map { it.name },
        )
    }

    @Test
    fun `deleting a playlist leaves every disc it lists, and says how many`() {
        val folder = temp.newFolder()
        folder.make("A (Disc 1).chd")
        folder.make("A (Disc 2).chd")
        folder.make("A (Disc 3).chd")
        val m3u = folder.make("A.m3u", "A (Disc 1).chd\nA (Disc 2).chd\nA (Disc 3).chd\n")

        assertEquals(PlaylistMembers.Staying(playlists = 1, members = 3), PlaylistMembers.staying(listOf(m3u.path)))
    }

    @Test
    fun `a disc the same delete is also taking does not stay, and a delete with no playlist says nothing`() {
        val folder = temp.newFolder()
        val one = folder.make("A (Disc 1).chd")
        val two = folder.make("A (Disc 2).chd")
        val m3u = folder.make("A.m3u", "A (Disc 1).chd\nA (Disc 2).chd\n")

        assertEquals(
            PlaylistMembers.Staying(playlists = 1, members = 1),
            PlaylistMembers.staying(listOf(m3u.path, one.path)),
        )
        assertNull("everything it lists is going too", PlaylistMembers.staying(listOf(m3u.path, one.path, two.path)))
        assertNull("no playlist in the delete", PlaylistMembers.staying(listOf(one.path)))
        assertNull("a playlist that lists nothing there", PlaylistMembers.staying(listOf(folder.make("Empty.m3u", "# nothing\n").path)))
    }

    @Test
    fun `a playlist is not a member of itself and two playlists count a shared disc once`() {
        val folder = temp.newFolder()
        folder.make("Shared.chd")
        folder.make("Only B.chd")
        val a = folder.make("A.m3u", "A.m3u\nShared.chd\n")
        val b = folder.make("B.m3u", "Shared.chd\nOnly B.chd\n")

        assertEquals(listOf("Shared.chd"), PlaylistMembers.membersOf(a).map { it.name })
        assertEquals(PlaylistMembers.Staying(playlists = 2, members = 2), PlaylistMembers.staying(listOf(a.path, b.path)))
    }
}
