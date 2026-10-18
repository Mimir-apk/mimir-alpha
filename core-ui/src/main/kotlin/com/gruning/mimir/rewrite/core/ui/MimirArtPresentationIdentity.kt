package com.gruning.mimir.rewrite.core.ui

/**
 * Identifies one local-art presentation epoch.
 *
 * Coil updates an existing [coil.compose.AsyncImagePainter] after composition. A persistent Compose slot
 * can therefore expose the previous request's Success painter for the destination commit unless the painter
 * itself lives in a destination-keyed group. Keep the key on durable content identity rather than request
 * object identity so ordinary recompositions and same-identity fallback/reveal state remain uninterrupted.
 *
 * **The title is not part of the identity** (audit 2026-10-14, RC-09: H-04, L-20 one-frame blink). A card's title changes without its art changing, by a rename
 * or by enrichment, and a key that held it tore the cover's painter and reveal down and rebuilt them for the same bitmap: a remount, a frame without the art.
 * The request is built from the model alone, so a title can never make a painter stale; the title is drawn (the fallback glyph, the description) and recomposes.
 */
internal data class MimirArtPresentationIdentity(
    val model: String?,
    val kind: MimirArtPresentationKind,
)

internal enum class MimirArtPresentationKind {
    BOX_ART,
    FLAT_COVER,
    FLAT_LOGO,
}

internal fun mimirArtPresentationIdentity(
    model: Any?,
    kind: MimirArtPresentationKind,
): MimirArtPresentationIdentity = MimirArtPresentationIdentity(
    model = model?.toString(),
    kind = kind,
)
