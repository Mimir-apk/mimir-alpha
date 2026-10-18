package com.gruning.mimir.rewrite.core.model.library

/**
 * What a shelf paints for one game: the picture AND its shape, carried together.
 *
 *  the Library game-list shelf is the Home shelf, and "Icon art" is the one art type
 * for shelves. The shape travels with the uri so no reader re-derives it from which field happened to be set;
 * that re-derivation is what painted a square pick as a box before (see [WiredFace]).
 */
data class ShelfFace(val uri: String?, val shape: ShelfFaceShape) {
    companion object {
        val None = ShelfFace(uri = null, shape = ShelfFaceShape.NONE)
    }
}

/** The silhouette a shelf tile takes: a square tile, a box on the ledge, the game article, or nothing. */
enum class ShelfFaceShape { SQUARE, LEDGE_COVER, PHYSICAL, NONE }
