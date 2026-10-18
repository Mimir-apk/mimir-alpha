package com.gruning.mimir.rewrite.core.model.library

/**
 * OFFLINE, LOCAL-ONLY preview-video resolution seam — the sibling of [ArtSource] for the
 * short gameplay/trailer clip. Given where a game's primary file lives ([sourceDocumentUri] / [managedPath]),
 * returns a LOCAL video URI for a sibling `<base>.mp4` / `.webm` that ALREADY EXISTS on the device, or null.
 *
 * NEVER a network URL (local-only policy): the player only ever plays a local file the opt-in,
 * gated fetch has already downloaded. A `fun interface` so the default is a no-op (previews/tests build
 * without a video), and the real `LocalArtSource` (which resolves both art and video) is injected at the app.
 */
fun interface PreviewVideoSource {
    /** A LOCAL video URI for the game whose primary file is [sourceDocumentUri] / [managedPath], or null. */
    fun videoUriFor(sourceDocumentUri: String?, managedPath: String?): String?
}
