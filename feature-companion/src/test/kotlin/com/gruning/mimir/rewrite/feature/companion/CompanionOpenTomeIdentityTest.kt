package com.gruning.mimir.rewrite.feature.companion

import com.gruning.mimir.rewrite.core.model.CompanionHomeTile
import com.gruning.mimir.rewrite.core.model.CompanionHomeTileKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** an open Tome is identified by its folder's id, not by the tile list it was opened from. */
class CompanionOpenTomeIdentityTest {
    private fun leaf(id: String) = CompanionHomeTile(id, CompanionHomeTileKind.PLATFORM, id)
    private fun tome(vararg children: String) =
        CompanionHomeTile("folder-1", CompanionHomeTileKind.FOLDER, "Tome", children = children.map(::leaf))

    @Test
    fun `moving one child out of three keeps the Tome open on the other two`() {
        val before = listOf(leaf("gba"), tome("a", "b", "c"))
        assertEquals("folder-1", companionOpenTomeId("folder-1", before, wasFilled = true))
        val after = listOf(leaf("gba"), tome("a", "c"), leaf("b"))
        assertEquals("folder-1", companionOpenTomeId("folder-1", after, wasFilled = true))
        assertEquals(listOf("a", "c"), after.first { it.id == "folder-1" }.children.map { it.id })
    }

    @Test
    fun `a reorder or a merge into the Tome keeps it open`() {
        assertEquals("folder-1", companionOpenTomeId("folder-1", listOf(tome("a", "b"), leaf("gba")), wasFilled = true))
        assertEquals("folder-1", companionOpenTomeId("folder-1", listOf(leaf("gba"), tome("a", "b", "c")), wasFilled = true))
    }

    @Test
    fun `a folder that is gone closes it`() {
        assertNull(companionOpenTomeId("folder-1", listOf(leaf("gba"), leaf("a"), leaf("b")), wasFilled = true))
        assertNull(companionOpenTomeId("folder-1", emptyList(), wasFilled = false))
    }

    @Test
    fun `the last child leaving closes it but a Tome opened empty stays open`() {
        assertNull(companionOpenTomeId("folder-1", listOf(tome()), wasFilled = true))
        assertEquals("folder-1", companionOpenTomeId("folder-1", listOf(tome()), wasFilled = false))
    }

    @Test
    fun `nothing requested is nothing open`() {
        assertNull(companionOpenTomeId(null, listOf(tome("a")), wasFilled = true))
    }
}
