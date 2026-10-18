package com.gruning.mimir.rewrite.feature.files

import androidx.compose.runtime.staticCompositionLocalOf
import com.gruning.mimir.rewrite.core.ui.MimirPageHandoffProbe

/**
 * A test seam, like core-ui's [MimirPageHandoffProbe]: what the browser's page motion is painting, read on the test's clock.
 * Folder-up, 2026-10-15: the surface's pixels cannot be captured on the JVM (the departing page is a recorded `GraphicsLayer`, which a
 * software canvas cannot draw), so a frame-sequence test reads the four alphas that make the pane instead -- the departing child's
 * ([listing], [panel]) and the arriving page's ([listingGlideAlpha], [panelGlideAlpha], and whether the listing has been seated and so
 * paints at all) -- and composes them the way the screen does: `1 - (1 - child)(1 - parent)` is how much of a whole page is on screen.
 *
 * Null by default, and read once per surface: production never installs one.
 */
internal class FileBrowserMotionProbe {
    /** The folio's page handoff: the departing child's alpha and travel. */
    val listing = MimirPageHandoffProbe()

    /** The description panel's page handoff. */
    val panel = MimirPageHandoffProbe()

    /** The arriving listing's own glide alpha (1 for a return: it stands at rest). */
    var listingGlideAlpha: () -> Float = { 1f }

    /** The arriving panel's own glide alpha. */
    var panelGlideAlpha: () -> Float = { 1f }

    /** False for the one frame the seat gate hides a listing that has not been re-seated yet. */
    var listingSeated: () -> Boolean = { true }
}

internal val LocalFileBrowserMotionProbe = staticCompositionLocalOf<FileBrowserMotionProbe?> { null }
