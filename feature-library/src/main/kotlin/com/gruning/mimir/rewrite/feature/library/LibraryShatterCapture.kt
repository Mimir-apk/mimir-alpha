package com.gruning.mimir.rewrite.feature.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CancellationException

/*
 * "Every hide shatters the card" : the picture the shatter breaks.
 *
 * The card is captured AS IT IS DRAWN, through a GraphicsLayer: its frame, its focus ring and its favourite pin are
 * all in the picture, so they shatter with it. The overlay ([LibraryShatterOverlay]) is handed an ImageBitmap and
 * never sees the layer.
 *
 * THE REPLAY TRAP (project the design constraint, walk-32b): a layer the card records by hand and that
 * nothing then draws loses its recording as soon as it leaves the render tree, and Compose 1.7.3 then replays the
 * stored draw block, which for `drawContent()` on a pass that has ended paints nothing and swallows the error. A
 * capture that records only when asked would race exactly that. So this is the other shape: the card DRAWS THROUGH
 * its layer, on every pass it draws (record, then `drawLayer`). The layer is in the render tree for as long as the
 * card is, so its recording is always the last thing the card drew, and [captureOrNull] has something to read at
 * whatever frame the hide is confirmed. [LibraryShatterCapture.recordingInPass] guards the one remaining hole, a
 * replay outside the pass, so it paints nothing instead of whatever that scope draws next.
 *
 * WHAT IT COSTS. The card that carries [libraryShatterCapture] records its content into a second display list each
 * time it redraws. Give it to the one card that can be hidden, the centred one, and to nothing when reduced motion
 * cuts (there is no picture to take). If the Nova or the RG Rotate misses a frame budget on the centred card, the
 * fallback is to arm it only while the hide prompt is open, so the layer is recorded during the 130 ms the prompt
 * needs to close; that is not built, because nothing measured says it is needed.
 */
internal class LibraryShatterCapture(val layer: GraphicsLayer) {
    /** True only while the card records inside its own draw; read on the draw thread, written on it. */
    var recordingInPass: Boolean = false

    /**
     * A picture of the card as it was last drawn, or null when there is not one (the card was never drawn, the layer
     * was released, or the platform refused the read). It suspends while the renderer copies the layer, so it must
     * complete at the point before the write that seam (a) gives it, BEFORE the pending-hidden set is applied and the
     * card leaves composition.
     */
    suspend fun captureOrNull(): ImageBitmap? {
        if (layer.isReleased || layer.size == IntSize.Zero) return null
        return try {
            layer.toImageBitmap()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            null
        }
    }
}

@Composable
internal fun rememberLibraryShatterCapture(): LibraryShatterCapture {
    val layer = rememberGraphicsLayer()
    return remember(layer) { LibraryShatterCapture(layer) }
}

/**
 * Put this on the outermost node of the card that can be hidden, outside everything the shatter should carry: the
 * frame, the focus ring, the favourite pin. It draws exactly what the card draws, once: the content is drawn INTO the
 * layer and the layer is drawn onto the screen. Null does nothing.
 */
internal fun Modifier.libraryShatterCapture(capture: LibraryShatterCapture?): Modifier =
    if (capture == null) {
        this
    } else {
        drawWithContent {
            val live = this
            capture.recordingInPass = true
            try {
                // The block is kept and replayed if the renderer ever drops the recording; outside this pass `live`
                // has ended, so a replay paints nothing rather than whatever that scope draws next.
                capture.layer.record { if (capture.recordingInPass) live.drawContent() }
            } finally {
                capture.recordingInPass = false
            }
            drawLayer(capture.layer)
        }
    }
