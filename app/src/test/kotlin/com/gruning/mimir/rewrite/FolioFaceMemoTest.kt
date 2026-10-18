package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.library.ShelfFace
import com.gruning.mimir.rewrite.core.model.library.ShelfFaceShape
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Opening a folio resolves no face.**
 *
 * The folio open was measured on 2026-09-04: ~31 ms of UI-thread work, ~95% of it a per-member face read (file
 * stats on three stores). It was memoized per game then. Policy 2026-10-10 (one shelf face) made the read the
 * full shelf-face resolver, which the shelf's own rows had ALREADY run when the projection was built -- so the wells'
 * face is now taken from that same resolution and put in a side map beside the rows. Nothing is resolved twice, and
 * nothing is stat'ted at open.
 */
class FolioFaceMemoTest {

    private val source: String by lazy {
        val f = File("src/main/kotlin/com/gruning/mimir/rewrite/HomeShelfCoordinator.kt")
        assertTrue("could not locate ${f.absolutePath}", f.isFile)
        f.readText()
    }

    private fun resolved(shelf: ResolvedShelfFace) = ResolvedShelfFaces(shelf = shelf, card = shelf)

    private fun face(uri: String?, shape: ShelfFaceShape, tier: ShelfFaceTier) =
        ResolvedShelfFace(ShelfFace(uri, shape), tier)

    // -- what a well wears ------------------------------------------------------------------------------

    @Test
    fun `a picked cover or article and the automatic box are the wells wired face, with the right kind`() {
        assertEquals(
            MimirWiredFace("file:///pick.png", physical = false),
            resolved(face("file:///pick.png", ShelfFaceShape.LEDGE_COVER, ShelfFaceTier.PICK)).folioWiredFace(),
        )
        assertEquals(
            MimirWiredFace("file:///disc.png", physical = true),
            resolved(face("file:///disc.png", ShelfFaceShape.PHYSICAL, ShelfFaceTier.PICK)).folioWiredFace(),
        )
        assertEquals(
            MimirWiredFace("file:///portrait.png", physical = false),
            resolved(face("file:///portrait.png", ShelfFaceShape.LEDGE_COVER, ShelfFaceTier.AUTOMATIC_TILE)).folioWiredFace(),
        )
    }

    /** A square rides the row's `tileIconUri` and a plain automatic cover is the well's `artworkUri`: neither is "wired". */
    @Test
    fun `a square, the automatic cover, a stand in and nothing are not a wired face`() {
        assertNull(resolved(face("file:///sq.png", ShelfFaceShape.SQUARE, ShelfFaceTier.PICK)).folioWiredFace())
        assertNull(resolved(face("file:///sq.png", ShelfFaceShape.SQUARE, ShelfFaceTier.AUTOMATIC_TILE)).folioWiredFace())
        assertNull(resolved(face("file:///c.png", ShelfFaceShape.LEDGE_COVER, ShelfFaceTier.AUTOMATIC_COVER)).folioWiredFace())
        assertNull(resolved(face("file:///logo.png", ShelfFaceShape.LEDGE_COVER, ShelfFaceTier.STAND_IN)).folioWiredFace())
        assertNull(resolved(face("file:///s.png", ShelfFaceShape.PHYSICAL, ShelfFaceTier.SUPPORT)).folioWiredFace())
        assertNull(resolved(face(null, ShelfFaceShape.NONE, ShelfFaceTier.NONE)).folioWiredFace())
        assertNull(resolved(face(null, ShelfFaceShape.LEDGE_COVER, ShelfFaceTier.PICK)).folioWiredFace())
    }

    // -- where it is computed ----------------------------------------------------------------------------

    /**
     * The side map is filled inside the SAME `remember` that builds `tiledDisplayRows`, from the `faces` each row is
     * given. That is what keeps it invalidating on everything the rows do: an applied cover arrives through
     * `displayRows` (the repository's `artVersion` re-emission) and a tile write through `sgdbEpoch`. Move it to its own
     * `remember`, or let it call the resolver, and either a fresh pick is cached away or the cost comes back.
     */
    @Test
    fun `the wells face comes from the resolution the row already got, in the same remember`() {
        val block = source.substringAfter("val tiledRows = remember(").substringBefore("val tiledDisplayRows = tiledRows.rows")
        assertTrue("search window missed the tiled rows remember", block.contains(".withShelfFaces(faces)"))
        assertTrue(
            "the keys must be the ones the rows are built from",
            block.substringBefore(") {").let { "displayRows" in it && "sgdbEpoch" in it },
        )
        assertTrue("the side map is filled from `faces`", block.contains("faces.folioWiredFace()"))
        assertTrue(
            "Emulated games only: an Android row's tile is its launcher icon",
            block.contains("row.itemId is HomeShelfItemId.Emulated"),
        )
    }

    @Test
    fun `the folio reads a map and the coordinator resolves each game once`() {
        val folio = source.substringAfter("val folioMemberSlots = remember(").substringBefore("val folioMembers = remember(")
        assertTrue("search window missed the folio members", folio.contains("HomeTomeMember("))
        assertTrue("the open reads the side map", folio.contains("folioFacesById[resolvedRow.itemId]"))
        assertTrue("and calls no resolver", !folio.contains("resolveShelfFaces(") && !folio.contains("resolveWiredFace("))
        val codeLines = source.lines().filterNot { line ->
            line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
        }
        assertEquals(
            "the coordinator resolves a face in exactly one place: the projection of the rows",
            1,
            codeLines.count { it.contains("resolveShelfFaces(") },
        )
    }

    /**
     * Scope. `resolveWiredFace` is also read by the launch card (`MimirAppHost.kt`) and by `WiredFaceStore`'s own
     * fallbacks; a cache down there would make a stale entry a visibly wrong cover on surfaces nobody has measured. Share the work only at the coordinator to avoid introducing that store-wide cache risk.
     */
    @Test
    fun `nothing is cached inside the resolver or the wired face store`() {
        val store = File("src/main/kotlin/com/gruning/mimir/rewrite/WiredFaceStore.kt").readText()
        val resolver = File("src/main/kotlin/com/gruning/mimir/rewrite/ShelfFaceResolver.kt").readText()
        for ((name, text) in listOf("WiredFaceStore" to store, "ShelfFaceResolver" to resolver)) {
            assertTrue(
                "$name must stay uncached: a stale entry reaches the launch card, a wrong cover on a surface nobody measured.",
                !text.contains("getOrPut") && !text.contains("HashMap<"),
            )
        }
    }
}
