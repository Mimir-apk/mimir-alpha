package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.ui.MimirBoxArtCorner
import com.gruning.mimir.rewrite.core.ui.MimirNorseBookAspectRatio
import com.gruning.mimir.rewrite.core.ui.mimirNorseBookCoverCorner

/** The Tome folio's focus ring, for a test to find it: one sibling overlay above the strip, whatever it is drawn around. */
internal const val HomeTomeFolioRingTestTag = "home_tome_folio/ring"

/**
 * What the Tome folio's focus ring is drawn around: its [widthDp] (the ring is as tall as the cell either way) and the [cornerDp] its stroke follows.
 *
 * A ring hugs what it selects (sweep 10-11, the Tome preview: the ring stood about 30 px clear of a book on each side, because it was the cell's size and the book is the 3:4 leaf the
 * square cell centres). The cell holds exactly one of two things, and [homeTomeFolioArtShowsPlaceholder] says which: real art, which fills the cell, so the ring is the cell's own with the
 * box-art corner it clips with; or the title book ([MediaArtBookCover][com.gruning.mimir.rewrite.core.ui.MediaArtBookCover]), a 3:4 leaf centred in the cell at the cell's height, so the ring
 * is that leaf's width with the book's own corner ([mimirNorseBookCoverCorner], the corner the cover clips with, so the leather stays inside the stroke at every width).
 */
internal data class HomeTomeFolioRingSeat(val widthDp: Float, val cornerDp: Float)

internal fun homeTomeFolioRingSeat(cellWidthDp: Float, cellHeightDp: Float, bookInside: Boolean): HomeTomeFolioRingSeat {
    if (!bookInside) return HomeTomeFolioRingSeat(widthDp = cellWidthDp, cornerDp = MimirBoxArtCorner.value)
    val leaf = minOf(cellWidthDp, cellHeightDp * MimirNorseBookAspectRatio)
    return HomeTomeFolioRingSeat(widthDp = leaf, cornerDp = mimirNorseBookCoverCorner(leaf).value)
}

/** Whether [member]'s cell paints the title book: exactly when the face the cell is handed has no art ([homeTomeFolioArtShowsPlaceholder]), as the cell itself decides. */
internal fun homeTomeFolioMemberShowsTitleBook(member: HomeTomeMember): Boolean = homeTomeFolioArtShowsPlaceholder(
    artUri = homeTomeWellFace(
        physicalMediaUri = member.physicalMediaUri,
        wiredFaceShape = member.wiredFaceShape,
        artworkUri = member.artworkUri,
    ).uri,
    painterReady = false,
)
