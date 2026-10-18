package com.gruning.mimir.rewrite.core.ui

import android.content.ContentResolver
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext

/** Clear-logo geometry is deliberately wide; it never inherits the 3:4 cover/portrait frame. */
object MimirFlatLogoArtDefaults {
    const val AspectRatio: Float = 3f
}

internal fun isMimirLocalArtScheme(scheme: String?): Boolean = when (scheme?.lowercase()) {
    ContentResolver.SCHEME_CONTENT,
    ContentResolver.SCHEME_FILE,
    ContentResolver.SCHEME_ANDROID_RESOURCE,
    -> true
    else -> false
}

/**
 * Renders a transparent clear-logo asset from a confirmed local URI.
 *
 * The container is whatever seat [modifier] gives it -- policy 2026-09-15 (R6-P2-09) retired the
 * fixed `.aspectRatio(3f)` this Box used to impose on every caller, because it re-solved a caller's own
 * fixed size (e.g. [MimirLogoStage]) back down to 3:1. A caller that wants a fixed stage now sizes it
 * itself, as [MimirLogoStage]'s two adopters do; the complete ink is always visible regardless. With
 * [ink] null (the default), that ink is the whole bitmap, plain [ContentScale.Fit] of the file's canvas
 * -- today's behaviour for a caller that gives no fixed size, unchanged except that the seat is now
 * whatever the caller's own constraints resolve to rather than a forced 3:1. With [ink] set, the file's transparent margins are trimmed and the ink is fit and
 * centred instead -- see [MimirLogoStage]. There is no cover frame, background, clipping, crop, portrait
 * ratio, or fallback glyph; missing, remote, loading, and failed logo references remain transparent so
 * surrounding shelf art stays truthful.
 */
@Composable
fun MimirFlatLogoArt(
    title: String,
    logoUri: String?,
    modifier: Modifier = Modifier,
    /**
     * when set, the logo is drawn so its INK -- the opaque
     * rectangle inside the file, transparent margins trimmed -- fits the seat, instead of its canvas.
     * Mirrors [MimirBoxArt]'s `ink` parameter (`MediaArtFrame.kt:390-398`): opt-in per call site, so a
     * surface adopts it only once its seat is shaped for the ink. Default null keeps today's plain
     * [ContentScale.Fit] of the canvas -- byte-identical to before the ink-fit change for a caller that ALSO still
     * shapes its own seat the same way it always did (this Box no longer does that shaping itself; see
     * this function's own KDoc). A caller that never gave its seat an explicit shape and relied on this
     * Box's retired `.aspectRatio(3f)` now gets whatever shape its own remaining modifiers resolve to,
     * not 3:1.
     */
    ink: MimirArtInkBounds? = null,
) {
    val localUri = remember(logoUri) {
        logoUri
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(Uri::parse)
            ?.takeIf { isMimirLocalArtScheme(it.scheme) }
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        if (localUri != null) {
            key(mimirArtPresentationIdentity(localUri, MimirArtPresentationKind.FLAT_LOGO)) {
                val painter = rememberMimirLocalArtPainter(
                    model = localUri,
                    kind = MimirArtPresentationKind.FLAT_LOGO,
                )
                if (ink != null) {
                    // The seat is the LAYOUT; only the pixels move -- mirrors MediaArtFrame.kt's
                    // MimirBoxArt ink branch (:434-462) line for line. See mimirArtInkDraw's KDoc for
                    // why this is a graphicsLayer and not an oversized child: Modifier.size is coerced
                    // into the incoming constraints and requiredSize fights the Box's placement.
                    Image(
                        painter = painter,
                        contentDescription = title,
                        // FillBounds is half the transform: the layer below undoes this stretch and
                        // re-applies the one the ink needs, in one scale.
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier
                            .fillMaxSize()
                            .clipToBounds()
                            .graphicsLayer {
                                val solve = mimirArtInkDraw(
                                    seatWidth = size.width,
                                    seatHeight = size.height,
                                    ink = ink,
                                )
                                transformOrigin = TransformOrigin(
                                    MimirArtInkDraw.transformOriginX,
                                    MimirArtInkDraw.transformOriginY,
                                )
                                scaleX = solve.scaleX
                                scaleY = solve.scaleY
                                translationX = solve.translationX
                                translationY = solve.translationY
                            },
                    )
                } else {
                    Image(
                        painter = painter,
                        contentDescription = title,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/**
 * The three-line wiring `LibraryComponents.kt:632-636` uses for the Library list's ink consumer
 * (measure-once, IO thread, persisted in [MimirArtInkStore]) -- shared here so both call sites (the Now Playing card and the launch transition) read the one store instead of each growing
 * its own measurement call.
 *
 * Filters to the same local-art schemes [MimirFlatLogoArt] itself requires (`:60-66` above) before
 * ever asking the store to measure, so a remote or blank URI never reaches [MimirArtInkStore.measure]
 * and this always returns null for it -- the primitive's plain-[ContentScale.Fit] fallback stays
 * exactly what a caller sees for those URIs.
 */
@Composable
fun rememberMimirLogoInk(logoUri: String?): MimirArtInkBounds? {
    val context = LocalContext.current
    val localUri = remember(logoUri) {
        logoUri
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.takeIf { isMimirLocalArtScheme(Uri.parse(it).scheme) }
    }
    LaunchedEffect(localUri) {
        if (localUri != null) {
            MimirArtInkStore.ensureLoaded(context)
            MimirArtInkStore.measure(context, localUri)
        }
    }
    return MimirArtInkStore.boundsFor(localUri)
}
