package com.gruning.mimir.rewrite.feature.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import com.gruning.mimir.rewrite.core.ui.MimirMotion

/*
 * Opening a platform Tome looks like opening a book (motion audit batch E).
 *
 * The centred book's leaf -- its cover, without the focus ring -- keeps a recorded copy of what it draws. On the open
 * the Library host draws that copy above the departing shelf and swings it on its spine: about -24 degrees with a
 * flat perspective, growing to 1.04, 360 ms, starting one frame after the press so it plays during the wait for the
 * covers (Q4). Its fade runs on a second 360 ms clock that starts when the shelf starts to leave, over that clock's
 * last third: a long wait holds the cover open rather than fading it over a still shelf, so the cover is always there
 * as the games glide in (Q3), and the inner page is never left alone in the ring. On a warm open the two clocks start
 * together. The departing shelf still holds the book's own pixels, so an inner page
 * is painted over them beneath the swinging cover, or the cover would peel off an identical copy of itself.
 * B during the wait snaps it shut. The return now swings the closed platform's cover shut on the return
 * clock, under the dissolving game list (LibraryScreen's host, `closeBook`).
 */

/** How far the cover swings open around its spine. Negative: the leaf's free edge comes toward the viewer. */
internal const val LibraryBookOpenDegrees = -24f

/** How much the cover grows as it swings, about its spine. */
internal const val LibraryBookOpenScale = 1.04f

/** The swing's length: the house overlay clock's 2x tier, shared by the swing animation. */
internal const val LibraryBookOpenMs = MimirMotion.OverlayMs * 2

/** A flatter perspective than the platform default of 8, so a 24 degree swing reads as a cover, not a door. */
internal const val LibraryBookCameraDistance = 14f

/** The cover is fully opaque for the first two thirds of its fade clock (from the shelf's exit) and fades over the last. */
internal const val LibraryBookFadeFrom = 2f / 3f

/** Where the recorded leaf sits, and a copy of what it drew, for the open. Read and written in draw and layout. */
internal class LibraryBookLeaf(val layer: GraphicsLayer) {
    /** The platform whose book was recorded last; an open of any other platform plays no book. */
    var platformId: String? = null
    var topLeftInRoot: Offset = Offset.Zero
    var sizeInRoot: Size = Size.Zero
    var cornerPx: Float = 0f

    /** The open book's first page and spine, from the same palette the recorded cover was drawn in. */
    var pageColor: Color = Color.Transparent
    var spineColor: Color = Color.Transparent

    /** True only while the centred book records the copy, inside its own draw ([keepLibraryBookLeafRecorded]). */
    var recordingInPass: Boolean = false

    fun readyFor(platformId: String?): Boolean =
        platformId != null && this.platformId == platformId && sizeInRoot.width > 0f && sizeInRoot.height > 0f
}

/** Provided by the Library's primary host; null everywhere else, so no other shelf records anything. */
internal val LocalLibraryBookLeaf = staticCompositionLocalOf<LibraryBookLeaf?> { null }

@Composable
internal fun rememberLibraryBookLeaf(): LibraryBookLeaf {
    val layer = rememberGraphicsLayer()
    return remember(layer) { LibraryBookLeaf(layer) }
}

/**
 * On the centred book only: draw as always, and keep a copy of the same drawing in [leaf]'s layer. A copy, never a
 * draw-through: the shelf's own recording must not share the node the host later rotates.
 */
internal fun Modifier.libraryBookLeaf(
    leaf: LibraryBookLeaf?,
    platformId: String,
    cornerPx: Float,
    pageColor: Color,
    spineColor: Color,
): Modifier =
    if (leaf == null) {
        this
    } else {
        this
            .onGloballyPositioned { coordinates ->
                val width = coordinates.size.width.toFloat()
                val height = coordinates.size.height.toFloat()
                // Through the tile's focus scale: the size the book is actually painted at.
                val topLeft = coordinates.localToRoot(Offset.Zero)
                val bottomRight = coordinates.localToRoot(Offset(width, height))
                leaf.platformId = platformId
                leaf.topLeftInRoot = topLeft
                leaf.sizeInRoot = Size(bottomRight.x - topLeft.x, bottomRight.y - topLeft.y)
                leaf.cornerPx = if (width > 0f) cornerPx * (bottomRight.x - topLeft.x) / width else cornerPx
            }
            .drawWithContent {
                drawContent()
                val live = this
                leaf.platformId = platformId
                leaf.pageColor = pageColor
                leaf.spineColor = spineColor
                // The block is kept and replayed if the renderer ever drops the recording; outside this draw `live`
                // is a pass that has ended, so a replay paints nothing rather than whatever that scope draws next.
                leaf.recordingInPass = true
                try {
                    leaf.layer.record { if (leaf.recordingInPass) live.drawContent() }
                } finally {
                    leaf.recordingInPass = false
                }
            }
    }

internal data class LibraryBookLeafPose(val rotationY: Float, val scale: Float, val alpha: Float)

/**
 * The cover's pose: the swing eases on [timeFraction] of [LibraryBookOpenMs] from the press; the fade runs linearly on
 * [fadeFraction] of [LibraryBookOpenMs] from the shelf's exit. They are the same clock only when the shelf leaves at once.
 */
internal fun libraryBookLeafPose(timeFraction: Float, fadeFraction: Float = timeFraction): LibraryBookLeafPose {
    val t = timeFraction.coerceIn(0f, 1f)
    val f = fadeFraction.coerceIn(0f, 1f)
    val eased = MimirMotion.Easing.transform(t)
    val alpha = if (f <= LibraryBookFadeFrom) 1f else 1f - (f - LibraryBookFadeFrom) / (1f - LibraryBookFadeFrom)
    return LibraryBookLeafPose(
        rotationY = LibraryBookOpenDegrees * eased,
        scale = 1f + (LibraryBookOpenScale - 1f) * eased,
        alpha = alpha.coerceIn(0f, 1f),
    )
}

/**
 * The open book beneath the swinging cover: the spine in the binding's colour and the first page. Drawn with the
 * departing shelf, at its alpha, over that shelf's own copy of the closed book.
 */
internal fun DrawScope.drawLibraryBookPage(leaf: LibraryBookLeaf, origin: Offset, alpha: Float) {
    if (alpha <= 0f) return
    val topLeft = leaf.topLeftInRoot - origin
    val size = leaf.sizeInRoot
    val corner = CornerRadius(leaf.cornerPx)
    drawRoundRect(color = leaf.pageColor, topLeft = topLeft, size = size, cornerRadius = corner, alpha = alpha)
    // The same spine drawHeroBook paints: 7% of the width, never under 3.5 dp.
    val spineWidth = maxOf(size.width * 0.07f, 3.5f * density * size.width / maxOf(1f, leaf.layer.size.width.toFloat()))
    clipRect(topLeft.x, topLeft.y, topLeft.x + spineWidth, topLeft.y + size.height) {
        drawRoundRect(color = leaf.spineColor, topLeft = topLeft, size = size, cornerRadius = corner, alpha = alpha)
    }
}

/** A margin for what the flat perspective adds to the free edge as it swings toward the viewer. */
internal const val LibraryBookCoverTouchSlop = 0.1f

/**
 * Where the swinging cover can stand, in the host's coordinates: the recorded leaf displaced by [travelPx] with the
 * departing shelf, grown about its spine to the swing's full scale, plus [LibraryBookCoverTouchSlop]. The arriving page
 * takes touches from the frame it shows , but a tap here would land on what still looks like the closed
 * book and open whatever row the gamelist has put beneath it, so it reaches nothing while the cover is drawn.
 */
internal fun libraryBookCoverBounds(leaf: LibraryBookLeaf, origin: Offset, travelPx: Float): Rect {
    val topLeft = leaf.topLeftInRoot - origin
    val width = leaf.sizeInRoot.width
    val height = leaf.sizeInRoot.height
    val grownHeight = height * LibraryBookOpenScale
    val slop = maxOf(width, height) * LibraryBookCoverTouchSlop
    val left = topLeft.x + travelPx
    val top = topLeft.y - (grownHeight - height) / 2f
    return Rect(left - slop, top - slop, left + width * LibraryBookOpenScale + slop, top + grownHeight + slop)
}

/**
 * The cover itself, swinging on its spine at [timeFraction], fading at [fadeFraction], displaced by [travelPx] with the
 * departing shelf.
 */
internal fun DrawScope.drawLibraryBookCover(
    leaf: LibraryBookLeaf,
    origin: Offset,
    timeFraction: Float,
    fadeFraction: Float,
    travelPx: Float,
    /** Alpha of the page carrying the book close; the shelf may still be arriving. */
    alphaScale: Float = 1f,
) {
    val layer = leaf.layer
    val recordedWidth = layer.size.width.toFloat()
    if (recordedWidth <= 0f) return
    val pose = libraryBookLeafPose(timeFraction, fadeFraction)
    if (pose.alpha <= 0f) return
    layer.pivotOffset = Offset(0f, layer.size.height / 2f)
    layer.cameraDistance = LibraryBookCameraDistance
    layer.rotationY = pose.rotationY
    layer.scaleX = pose.scale
    layer.scaleY = pose.scale
    layer.alpha = pose.alpha * alphaScale.coerceIn(0f, 1f)
    val topLeft = leaf.topLeftInRoot - origin
    val fit = leaf.sizeInRoot.width / recordedWidth
    translate(topLeft.x + travelPx, topLeft.y) {
        scale(fit, fit, pivot = Offset.Zero) { drawLayer(layer) }
    }
}

/**
 * Nova reopen regression, 2026-09-29: the second open lost its animation and showed fallback art. The leaf is a layer recorded by hand, and the renderer keeps a
 * recording only while something draws it. The close swings the cover for one return clock and stops; the leaf then
 * left the render tree and its recording was dropped. The centred book records it again only when the book itself
 * redraws, and a book at rest does not. The next open drew a leaf with no recording, and Compose (1.7.3:
 * GraphicsLayer.draw -> recreateDisplayListIfNeeded) replayed the last draw block -- the book's `drawContent()`, from a
 * draw pass that had ended -- which painted nothing and swallowed the error. Only the inner page showed, a blank face,
 * and nothing swung. The first open worked because the leaf had never been drawn, so nothing had dropped it; moving off
 * the platform and back worked too, because the book redrew (emulator, 5x slow motion).
 *
 * So the Library host draws the leaf on every frame it draws, clipped to nothing and after its content: while the
 * Library is on screen the leaf never leaves the render tree, and its recording is the book's last one. It sits outside
 * the page transition, so it never rides the shelf/gamelist flip. When the whole Library has left the screen (Home and
 * back) the recording can still be gone; a replay then paints nothing ([LibraryBookLeaf.recordingInPass]) until the
 * shelf's book records it again, which it does before anything opens. A JVM test can only pin this by its source.
 */
internal fun Modifier.keepLibraryBookLeafRecorded(leaf: LibraryBookLeaf): Modifier =
    drawWithContent {
        drawContent()
        clipRect(0f, 0f, 0f, 0f) { drawLayer(leaf.layer) }
    }
