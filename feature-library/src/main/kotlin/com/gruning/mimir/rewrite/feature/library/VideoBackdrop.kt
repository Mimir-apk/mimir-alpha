package com.gruning.mimir.rewrite.feature.library

import android.content.Context
import android.graphics.Matrix
import android.graphics.ImageDecoder
import android.graphics.SurfaceTexture
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.PowerManager
import android.view.Surface
import android.view.TextureView
import android.widget.ImageView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.rememberAsyncImagePainter
import com.gruning.mimir.rewrite.core.audio.AudioPreferences
import com.gruning.mimir.rewrite.core.audio.MediaBedPolicy
import com.gruning.mimir.rewrite.core.audio.MimirAudioFocus
import com.gruning.mimir.rewrite.core.audio.MimirMediaBed
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * "BIFRÖST" backdrop (CP05 video mode) — the shimmering, colour-shifting bridge: the focused game's SHORT preview clip playing behind the gamelist, NOT blurred, **veiled by the
 * theme-colour gradient** so it reads as a "see-through video" tinted by the current theme (distinct from
 * [AuroraBackdrop], which is a *blurred* cover wash).
 *
 * Posture (local-only policy): playback is **LOCAL-ONLY**. [videoUri] is only ever a
 * `file://` / `content://` sibling the opt-in, gated fetch already downloaded ([LocalArtSource.videoUriFor]);
 * a remote `http(s)` url is refused outright. No egress happens here — this is pure presentation.
 *
 * Graceful by design: when the focused game has no downloaded clip (most of the library until a fetch runs),
 * or the device is in battery-saver, it falls back to the same game's static cover under the identical theme
 * veil — the "no-blur, theme-veiled" look — so the surface never goes black and never overclaims motion.
 *
 * Perf: the player only binds after focus SETTLES ([BifrostMediaSettleMs]) so scrolling the list doesn't spin up a
 * decoder per game; it is muted, looped, centre-cropped to fill, and paused when the app backgrounds.
 * Media3/ExoPlayer is absent from this build → a raw [TextureView] + [MediaPlayer] (TextureView is required
 * so Compose can composite the theme veil OVER the frames; a SurfaceView can't be blended into the tree).
 */
@Composable
fun VideoBackdrop(
    videoUri: String?,
    artUri: String?,
    artIsLogo: Boolean = false,
    modifier: Modifier = Modifier,
    // FUX-F: Home passes a lifted veil profile + a longer settle. Both default to the gamelist's values, so
    // every existing (gamelist) caller stays byte-identical.
    veil: Brush? = null,
    settleMs: Long = BifrostMediaSettleMs,
    /**
     * Applied to the MEDIA layers only (video surface + destination cover) — never the floor or veil,
     * which stay stage-anchored. Home passes its focal-safe transform here; the gamelist default is
     * byte-identical.
     */
    mediaModifier: Modifier = Modifier,
    /**
     * Whether to paint the opaque themed floor beneath the media. Default true: the return transition fully hides the Home shelf.
     *
     * The discriminator is **ground versus preview**, not overlay versus destination.
     *
     * - A **ground** is what a surface stands on for its whole life. It must be opaque, or whatever is
     *   behind it shows through: two scrims at `ScrimAlphaBlurred = 0.52` transmit ~23% on their own, and
     *   blur lowers spatial frequency, not transmission.
     * - A **preview** appears and disappears *inside* a surface that is already open, as content rather
     *   than as ground. `HomeTileMenuOverlay` mounts one only during `HomeTileMenuPhase.ClipPick`. Giving
     *   that a floor puts a full-screen opaque wipe in and out mid-menu, twice, with no transition on
     *   either edge — while the player has not left anything. Those pass `false`.
     */
    paintFloor: Boolean = true,
) {
    val palette = LocalMimirPalette.current
    val context = LocalContext.current

    // Battery-saver → skip the decode entirely; the static-cover fallback still enriches the surface.
    val powerSave = remember {
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true
    }
    // LOCAL-ONLY guard (posture): a downloaded sibling is file:// or content://; never play a remote url.
    val requestedMedia = eligibleBifrostVideoUri(videoUri, powerSave)
    var mediaState by remember { mutableStateOf(BifrostMediaState()) }
    val cleanArtUri = artUri?.takeIf { it.isNotBlank() }

    // Focus changes invalidate the previous coroutine and generation. The old clip remains on the one stable
    // TextureView only long enough to fade away; no decoder is rebound until the latest request rests for 300 ms.
    // Kind probing is independent: never restart settle when a probe resolves.
    LaunchedEffect(requestedMedia) {
        val request = mediaState.request(requestedMedia)
        mediaState = request
        delay(if (requestedMedia == null) BifrostVisualHandoffMs.toLong() else settleMs)
        mediaState = mediaState.settle(request.generation)
    }

    // Probe only the settled player URI. Key the state to that URI so a prior probe cannot survive a
    // destination change; usable kind still requires probe.rawUri == playerUri as a final identity guard.
    val settledPlayerUri = mediaState.playerUri
    var mediaKindProbe by remember(settledPlayerUri, context) {
        mutableStateOf<BifrostMediaKindProbe?>(null)
    }
    LaunchedEffect(settledPlayerUri, context) {
        val uri = settledPlayerUri
        mediaKindProbe = if (uri == null) {
            null
        } else {
            val kind = withContext(Dispatchers.IO) { bifrostLocalMediaKind(context, uri) }
            BifrostMediaKindProbe(
                rawUri = uri,
                kind = kind,
            )
        }
    }
    val mediaKind = usableBifrostMediaKind(mediaKindProbe, mediaState.playerUri)
    // A kind probe is asynchronous. Keep a previously confirmed video surface mounted during the
    // transient null state so A→B→A does not repeatedly destroy/recreate TextureView + controller.
    // A stale A frame remains hidden by `videoVisible`; it cannot paint as B or reopen audio.
    var retainedVideoBinding by remember { mutableStateOf<BifrostVideoSurfaceBinding?>(null) }
    LaunchedEffect(mediaKind, mediaState.playerUri, mediaState.playerGeneration) {
        when (mediaKind) {
            BifrostLocalMediaKind.VIDEO -> {
                retainedVideoBinding = bifrostVideoSurfaceBinding(
                    uri = mediaState.playerUri,
                    selectionGeneration = mediaState.playerGeneration,
                )
            }
            BifrostLocalMediaKind.ANIMATED_IMAGE -> retainedVideoBinding = null
            null -> Unit
        }
    }
    val videoSurfaceBinding = bifrostRetainedVideoSurfaceBinding(
        mediaKind = mediaKind,
        playerUri = mediaState.playerUri,
        playerGeneration = mediaState.playerGeneration,
        retainedBinding = retainedVideoBinding,
    )

    val videoVisible = mediaState.videoVisible && mediaState.requestedUri == requestedMedia
    // The State itself, never `.value` here: the fade is read inside the video layer's `graphicsLayer {}` lambda below,
    // so the 140 ms ramp is a draw-phase update and recomposes nothing. A composition read here rebuilt this whole host
    // (and the modifier chain's shape, `Modifier.alpha` dropping its layer at exactly 1.0) on every frame of it.
    val videoAlpha = animateFloatAsState(
        targetValue = if (videoVisible) 1f else 0f,
        // A new identity removes the prior video frame immediately; only the exact destination's first
        // frame fades in. The destination cover/floor is already underneath, so there is no black flash.
        animationSpec = if (videoVisible) {
            tween(durationMillis = BifrostVisualHandoffMs, easing = MimirMotion.Easing)
        } else {
            snap()
        },
        label = "bifrost-video-alpha",
    )

    // AU02 : the clip's OWN audio track — the media bed. The track arrived WITH the clip
    // download; here it is finally audible, governed by the pure MediaBedPolicy (preview-audio preference ×
    // suspended-during-game × bind-time courtesy gate) and keyed to the SAME videoVisible the frame fade
    // uses, so sound can never lead a clip that hasn't rendered. The short post-frame ramp keeps binds from
    // popping; a game-launch Suspend fades the bed out on this same curve.
    val previewAudioEnabled by AudioPreferences.previewAudioEnabled.collectAsState()
    val previewAudioMix by AudioPreferences.previewAudioMix.collectAsState()
    val bedFocus by MimirAudioFocus.state.collectAsState()
    val environmentBlocked by MimirMediaBed.environmentBlocked.collectAsState()
    val bedTarget = if (videoVisible && mediaKind == BifrostLocalMediaKind.VIDEO) {
        MediaBedPolicy.bedVolume(
            enabled = previewAudioEnabled,
            mix = previewAudioMix,
            focus = bedFocus,
            environmentBlocked = environmentBlocked,
        )
    } else {
        0f
    }
    // A State handed down, for the same reason as [videoAlpha]: the ramp is pushed to the player from a snapshotFlow in
    // [VideoSurface], so a 200 ms volume ramp no longer recomposes the host either.
    val bedVolume = animateFloatAsState(
        targetValue = bedTarget,
        // A selection swap must cut the old owner to silence immediately. Only the exact rendered
        // selection may ramp in, so a decoder that starts before its first TextureView frame cannot leak
        // a stale bed into B (or a later A in an A → B → A burst).
        animationSpec = if (bifrostBedRampDurationMs(videoVisible) > 0) {
            tween(durationMillis = bifrostBedRampDurationMs(videoVisible))
        } else {
            snap()
        },
        label = "bifrost-bed-volume",
    )

    Box(modifier.fillMaxSize()) {
        // 1 — themed floor so there is never a black flash before the first frame / when no cover exists.
        // See [paintFloor] for the one case that opts out and why it is not a glass-versus-destination line.
        //
        // Unconditional again, and it must stay that way. It was briefly opt-out (2026-08-27) on the
        // reading that a GLASS overlay has the blurred scene behind it and should show it. The return transition fully hides the Home shelf.
        // For a glass surface over Home this fill is the only thing that takes the shelf to zero — the
        // scrims do not. Two at `ScrimAlphaBlurred = 0.52` transmit `(1 - 0.52)^2` = **~23%** on their
        // own -- the figure this tree's own layering probe recorded -- and the stack only reaches single
        // digits once the overlay's art and veil are counted, which is exactly what is missing before that
        // art resolves. Blur lowers spatial frequency, not transmission, so a blurred shelf still reads as
        // a shelf-coloured cast. (An earlier draft of this comment said ~5% for the scrims alone. That was
        // the whole-stack figure mislabelled, and four times too flattering.)
        if (paintFloor) {
            Box(Modifier.fillMaxSize().background(palette.signatureBottom))
        }

        // 2 — destination-only static art. key() disposes the prior painter in the focus-commit frame;
        //     the new identity may fade in over the themed floor, never over old cover/logo pixels.
        if (cleanArtUri != null) {
            key(bifrostArtPresentationKey(cleanArtUri, artIsLogo)) {
                val destinationAlpha = remember { Animatable(0f) }
                LaunchedEffect(Unit) {
                    destinationAlpha.animateTo(1f, tween(durationMillis = BifrostVisualHandoffMs, easing = MimirMotion.Easing))
                }
                BifrostStaticCover(
                    uri = cleanArtUri,
                    artIsLogo = artIsLogo,
                    modifier = Modifier
                        .then(mediaModifier)
                        .graphicsLayer { alpha = destinationAlpha.value },
                )
            }
        }

        // 3 — exactly one persistent TextureView/controller. It stays transparent over the destination cover
        //     until the exact settled MediaPlayer reports its first rendered frame, so preparation cannot flash.
        when (mediaKind) {
            BifrostLocalMediaKind.ANIMATED_IMAGE -> {
                val animatedUri = mediaState.playerUri
                val animatedGeneration = mediaState.playerGeneration
                if (animatedUri != null && animatedGeneration != null) {
                    key(bifrostMediaSurfaceEpoch(animatedUri, animatedGeneration)) {
                        AnimatedImageSurface(
                            uri = animatedUri,
                            selectionGeneration = animatedGeneration,
                            running = videoVisible,
                            onFirstFrame = { uri, generation ->
                                mediaState = mediaState.onFirstFrame(uri, generation)
                            },
                            modifier = Modifier.fillMaxSize().then(mediaModifier)
                                .graphicsLayer { alpha = videoAlpha.value },
                        )
                    }
                }
            }
            BifrostLocalMediaKind.VIDEO, null -> if (videoSurfaceBinding != null) {
                // A stays mounted only while B's kind is unresolved. Once B is confirmed VIDEO, a
                // new keyed TextureView/controller owns B, so queued A callbacks cannot enter B.
                key(bifrostMediaSurfaceEpoch(videoSurfaceBinding.uri, videoSurfaceBinding.selectionGeneration)) {
                    VideoSurface(
                        uri = videoSurfaceBinding.uri,
                        selectionGeneration = videoSurfaceBinding.selectionGeneration,
                        bedVolume = bedVolume,
                        onFirstFrame = { uri, generation ->
                            mediaState = mediaState.onFirstFrame(uri, generation)
                        },
                        modifier = Modifier.fillMaxSize().then(mediaModifier)
                            .graphicsLayer { alpha = videoAlpha.value },
                    )
                }
            }
        }

        // 4 — the header/footer VEIL (NO blur). the gamelist's old heavy themed veil
        //     (signature hue + a flat accent wash) is RETIRED — every surface now standardises on the Home
        //     shelf's [homeVeil] profile (tileFillDeep — the contrast base per FUX-02 R5, never the gold/accent
        //     read — with the lifted see-through middle per R6), so the theme's own background reads through
        //     without a themed tint colliding over the media. Home keeps passing its own [veil]; the gamelist
        //     call sites take this default.
        //     RC-38 (spec §6.1): the default is [bifrostDefaultVeil] — the GAMELIST's dialect, which is what
        //     this comment already claimed it was. It resolves to [homeVeil] on every non-metallic theme, so
        //     only Freyr/Baldr move, and they move so the clip and the still stop wearing two veil profiles
        //     over the same hero.
        Box(Modifier.fillMaxSize().background(veil ?: bifrostDefaultVeil(palette)))
    }
}

internal enum class BifrostLocalMediaKind { VIDEO, ANIMATED_IMAGE }

/** Raw identity + probed kind. Pending/stale probes are not usable until rawUri matches the settled player. */
internal data class BifrostMediaKindProbe(
    val rawUri: String,
    val kind: BifrostLocalMediaKind,
)

/**
 * Kind may only drive the surface when it belongs to the current settled player URI.
 * A retained produceState probe for A must yield null while B is pending or settled.
 */
internal fun usableBifrostMediaKind(
    probe: BifrostMediaKindProbe?,
    playerUri: String?,
): BifrostLocalMediaKind? {
    if (probe == null || playerUri.isNullOrBlank()) return null
    if (probe.rawUri != playerUri) return null
    return probe.kind
}

/** Preserve only a confirmed video controller through an unresolved kind probe. */
internal fun bifrostRetainedVideoSurfaceUri(
    mediaKind: BifrostLocalMediaKind?,
    playerUri: String?,
    retainedVideoUri: String?,
): String? = when (mediaKind) {
    BifrostLocalMediaKind.VIDEO -> playerUri
    BifrostLocalMediaKind.ANIMATED_IMAGE -> null
    null -> retainedVideoUri?.takeIf { playerUri != null }
}

/** Confirmed URI identity is the controller/TextureView epoch. */
internal fun bifrostVideoSurfaceEpoch(uri: String): String = uri

/** A mounted video controller is owned by both the URI and the settled focus selection that chose it. */
internal data class BifrostVideoSurfaceBinding(
    val uri: String,
    val selectionGeneration: Long,
)

internal fun bifrostVideoSurfaceBinding(
    uri: String?,
    selectionGeneration: Long?,
): BifrostVideoSurfaceBinding? =
    uri?.takeIf { it.isNotBlank() }?.let { nonBlankUri ->
        selectionGeneration?.let { BifrostVideoSurfaceBinding(nonBlankUri, it) }
    }

/** Keep a confirmed A binding alive during B's type probe without relabelling it as B. */
internal fun bifrostRetainedVideoSurfaceBinding(
    mediaKind: BifrostLocalMediaKind?,
    playerUri: String?,
    playerGeneration: Long?,
    retainedBinding: BifrostVideoSurfaceBinding?,
): BifrostVideoSurfaceBinding? = when (mediaKind) {
    BifrostLocalMediaKind.VIDEO -> bifrostVideoSurfaceBinding(playerUri, playerGeneration)
    BifrostLocalMediaKind.ANIMATED_IMAGE -> null
    null -> retainedBinding?.takeIf { playerUri != null }
}

/** URI reuse is not ownership reuse: A → B → A must create a fresh controller epoch for the final A. */
internal fun bifrostMediaSurfaceEpoch(uri: String, selectionGeneration: Long): String =
    "$uri\u0000$selectionGeneration"

private fun bifrostLocalMediaKind(context: Context, rawUri: String): BifrostLocalMediaKind {
    val uri = Uri.parse(rawUri).buildUpon().fragment(null).build()
    val header = runCatching {
        context.contentResolver.openInputStream(uri)?.buffered()?.use { input ->
            val bytes = ByteArray(256 * 1024)
            val read = input.read(bytes)
            if (read <= 0) ByteArray(0) else bytes.copyOf(read)
        }
    }.getOrNull() ?: ByteArray(0)
    val gif = header.size >= 6 &&
        (header.copyOfRange(0, 6).decodeToString() == "GIF87a" ||
            header.copyOfRange(0, 6).decodeToString() == "GIF89a")
    val animatedWebp = header.size >= 21 &&
        header.copyOfRange(0, 4).decodeToString() == "RIFF" &&
        header.copyOfRange(8, 12).decodeToString() == "WEBP" &&
        header.indexOfAsciiChunk("ANIM") >= 0
    return if (gif || animatedWebp) {
        BifrostLocalMediaKind.ANIMATED_IMAGE
    } else {
        BifrostLocalMediaKind.VIDEO
    }
}

private fun ByteArray.indexOfAsciiChunk(chunk: String): Int {
    val needle = chunk.encodeToByteArray()
    if (size < needle.size) return -1
    for (start in 0..size - needle.size) {
        var matches = true
        for (offset in needle.indices) {
            if (this[start + offset] != needle[offset]) {
                matches = false
                break
            }
        }
        if (matches) return start
    }
    return -1
}

/**
 * PS-02A: raw player URI is Bifrost / first-frame identity. Fragment variants stay distinct.
 * ContentResolver source creation strips the fragment only.
 */
internal fun bifrostAnimatedImageSourceUri(rawUri: String): String {
    val fragment = rawUri.indexOf('#')
    return if (fragment >= 0) rawUri.substring(0, fragment) else rawUri
}

/** Pure decode request — generation + raw identity; source URI is derived for I/O only. */
internal data class AnimatedImageDecodeRequest(
    val rawUri: String,
    val generation: Long,
) {
    val sourceUri: String get() = bifrostAnimatedImageSourceUri(rawUri)
}

/**
 * Explicit adoption gate. Cancellation alone is not enough: a late ImageDecoder completion for A
 * must not attach after B replaced it, nor after background / disposal.
 */
internal fun acceptAnimatedImageDecodeResult(
    request: AnimatedImageDecodeRequest,
    latestRawUri: String?,
    latestGeneration: Long,
    foreground: Boolean,
    released: Boolean,
): Boolean {
    if (released || !foreground) return false
    if (latestRawUri.isNullOrBlank()) return false
    if (request.generation != latestGeneration) return false
    if (request.rawUri != latestRawUri) return false
    return true
}

/** Pure surface lifecycle — decode vs stop-retain vs resume without a second decode. */
internal enum class AnimatedImageSurfaceDecision {
    CLEAR,
    STOP_RETAIN,
    RESUME_WITHOUT_DECODE,
    DECODE,
}

internal data class AnimatedImageSurfaceInputs(
    val rawUri: String?,
    val foreground: Boolean,
    val adoptedRawUri: String?,
)

/**
 * Background stops animation but retains an accepted adoption for the same raw URI so resume can
 * restart without a second decode / floor flash. Background still clears when there is no matching
 * adoption (in-flight decode was already cleared at begin, and invalidate rejects its completion).
 * URI change / null request clears. Literal pre-draw first-frame truth remains OWED — attach still
 * reports first frame on ImageView bind.
 */
internal fun decideAnimatedImageSurface(
    inputs: AnimatedImageSurfaceInputs,
): AnimatedImageSurfaceDecision {
    val rawUri = inputs.rawUri?.takeIf { it.isNotBlank() }
    if (rawUri == null) return AnimatedImageSurfaceDecision.CLEAR
    if (!inputs.foreground) {
        return if (inputs.adoptedRawUri == rawUri) {
            AnimatedImageSurfaceDecision.STOP_RETAIN
        } else {
            AnimatedImageSurfaceDecision.CLEAR
        }
    }
    if (inputs.adoptedRawUri == rawUri) {
        return AnimatedImageSurfaceDecision.RESUME_WITHOUT_DECODE
    }
    return AnimatedImageSurfaceDecision.DECODE
}

private data class AnimatedImageAdoption(
    val rawUri: String,
    val drawable: Drawable,
)

private class AnimatedImageDecodeSession {
    var generation: Long = 0L
    var rawUri: String? = null
    var released: Boolean = false
    var adoptedDrawable: Drawable? = null

    fun begin(rawUri: String): AnimatedImageDecodeRequest {
        generation += 1L
        this.rawUri = rawUri
        return AnimatedImageDecodeRequest(rawUri = rawUri, generation = generation)
    }

    fun invalidate(keepRawUri: String? = null) {
        generation += 1L
        rawUri = keepRawUri
    }

    fun stopAdoptedAnimation() {
        (adoptedDrawable as? AnimatedImageDrawable)?.stop()
    }

    fun clearAdopted() {
        stopAdoptedAnimation()
        adoptedDrawable = null
    }
}

@Composable
private fun AnimatedImageSurface(
    uri: String?,
    selectionGeneration: Long,
    running: Boolean,
    onFirstFrame: (String, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val lifecycleOwner = LocalLifecycleOwner.current
    var foreground by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> foreground = true
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP,
                Lifecycle.Event.ON_DESTROY,
                -> foreground = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val session = remember { AnimatedImageDecodeSession() }
    var adoption by remember { mutableStateOf<AnimatedImageAdoption?>(null) }
    val latestForeground by rememberUpdatedState(foreground)

    fun clearAdoption() {
        session.clearAdopted()
        adoption = null
    }

    // Decode is keyed by raw URI + foreground only — never by [running] (that would be circular with
    // first-frame visibility). Start/stop still requires running && foreground on the main-thread view.
    // Background STOP_RETAIN keeps an accepted adoption so same-URI resume does not re-decode.
    LaunchedEffect(uri, foreground) {
        val rawUri = uri?.takeIf { it.isNotBlank() }
        when (
            decideAnimatedImageSurface(
                AnimatedImageSurfaceInputs(
                    rawUri = rawUri,
                    foreground = foreground,
                    adoptedRawUri = adoption?.rawUri,
                ),
            )
        ) {
            AnimatedImageSurfaceDecision.CLEAR -> {
                session.invalidate(keepRawUri = null)
                clearAdoption()
            }
            AnimatedImageSurfaceDecision.STOP_RETAIN -> {
                // Reject any in-flight decode; keep the accepted drawable stopped for resume.
                session.invalidate(keepRawUri = rawUri)
                session.stopAdoptedAnimation()
            }
            AnimatedImageSurfaceDecision.RESUME_WITHOUT_DECODE -> {
                // Retained adoption for this raw URI — update block restarts animation; no second decode.
            }
            AnimatedImageSurfaceDecision.DECODE -> {
                val request = session.begin(rawUri!!)
                clearAdoption()
                val sourceUri = Uri.parse(request.sourceUri)
                val decoded = withContext(Dispatchers.IO) {
                    runCatching {
                        ImageDecoder.decodeDrawable(
                            ImageDecoder.createSource(appContext.contentResolver, sourceUri),
                        )
                    }.getOrNull()
                }
                val accepted = acceptAnimatedImageDecodeResult(
                    request = request,
                    latestRawUri = session.rawUri,
                    latestGeneration = session.generation,
                    foreground = latestForeground,
                    released = session.released,
                )
                if (!accepted) {
                    (decoded as? AnimatedImageDrawable)?.stop()
                    return@LaunchedEffect
                }
                if (decoded == null) {
                    // Failure for the current identity: keep the themed floor / static art, never stale pixels.
                    clearAdoption()
                    return@LaunchedEffect
                }
                session.clearAdopted()
                session.adoptedDrawable = decoded
                adoption = AnimatedImageAdoption(
                    rawUri = request.rawUri,
                    drawable = decoded,
                )
            }
        }
    }

    DisposableEffect(session) {
        onDispose {
            session.released = true
            session.invalidate(keepRawUri = null)
            session.clearAdopted()
        }
    }

    AndroidView(
        factory = {
            ImageView(it).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
        },
        update = { view ->
            val current = adoption
            if (current == null) {
                (view.drawable as? AnimatedImageDrawable)?.stop()
                view.setImageDrawable(null)
                view.tag = null
                return@AndroidView
            }
            // Tag is the raw Bifrost identity (fragment-inclusive), not the fragment-free source URI.
            if (view.tag != current.rawUri) {
                (view.drawable as? AnimatedImageDrawable)?.stop()
                view.setImageDrawable(current.drawable)
                view.tag = current.rawUri
                (current.drawable as? AnimatedImageDrawable)?.repeatCount =
                    AnimatedImageDrawable.REPEAT_INFINITE
                // OWED: literal pre-draw first-frame truth — still reported on ImageView attach /
                // tag change, not a proven drawable pre-draw callback.
                onFirstFrame(current.rawUri, selectionGeneration)
            }
            (view.drawable as? AnimatedImageDrawable)?.let { drawable ->
                if (running && latestForeground) drawable.start() else drawable.stop()
            }
        },
        modifier = modifier,
    )
}

/**
 * The destination-only still under the clip.
 *
 * this sat OUTSIDE the hero decode law entirely — no `.size`, no cache key — so the
 * gamelist's hero changed decode identity depending purely on whether a clip existed for that game
 * ([LibraryScreen] passes the SAME `backdropHero` to this and to [LibraryHeroBackdrop]). It now takes the
 * window-solved envelope and the shared hero key, so one hero is one cache entry across both branches.
 *
 * The `0.62` alpha is deliberately UNCHANGED and is NOT part of this fix. It
 * is an authored value with its own stated rationale, it also governs two `app` overlays and the enrich
 * picker's candidate preview, and changing it would alter luminance beyond this decode-identity fix.
 */
@Composable
private fun BifrostStaticCover(
    uri: String?,
    artIsLogo: Boolean,
    modifier: Modifier = Modifier,
) {
    if (uri == null) return
    val context = LocalContext.current
    val heroEnvelope = rememberMimirHeroDecodeEnvelope()
    val request = remember(uri, context, heroEnvelope) {
        mimirHomeStaticHeroRequest(context, uri, heroEnvelope)
    }
    Image(
        painter = rememberAsyncImagePainter(request),
        contentDescription = null,
        contentScale = if (artIsLogo) ContentScale.Fit else ContentScale.Crop,
        // A full-bleed sharp cover would compete with the tiles and labels when no clip is available.
        modifier = modifier.fillMaxSize().alpha(0.62f),
    )
}

internal const val BifrostMediaSettleMs = 300L
/** The clip and still-cover handoff is the focus clock (140 ms), named from the token rather than restating it . */
internal const val BifrostVisualHandoffMs = MimirMotion.FocusMs

/** AU02: first-frame-gated media-bed ramp — brief enough to follow focus, soft enough to avoid a pop. */
internal const val BifrostAudioFadeMs = 200

/** A hidden or stale selection is muted synchronously; only a first-frame-visible selection can ramp. */
internal fun bifrostBedRampDurationMs(videoVisible: Boolean): Int =
    if (videoVisible) BifrostAudioFadeMs else 0

internal data class BifrostAspectCrop(val scaleX: Float, val scaleY: Float)

/** Prepared player + live surface + foreground are all required before one generation may start. */
internal data class VideoBackdropStartGate(
    val generation: Long = 0L,
    val preparedGeneration: Long? = null,
    val surfaceAvailable: Boolean = false,
    val foreground: Boolean = false,
) {
    fun request(nextGeneration: Long): VideoBackdropStartGate = copy(
        generation = nextGeneration,
        preparedGeneration = null,
    )

    fun prepared(requestGeneration: Long): VideoBackdropStartGate =
        if (requestGeneration == generation) copy(preparedGeneration = requestGeneration) else this

    fun surface(available: Boolean): VideoBackdropStartGate = copy(surfaceAvailable = available)

    fun foreground(isForeground: Boolean): VideoBackdropStartGate = copy(foreground = isForeground)

    fun mayStart(requestGeneration: Long): Boolean =
        requestGeneration == generation &&
            preparedGeneration == requestGeneration &&
            surfaceAvailable && foreground
}

/** A vendor info callback is advisory; an actual TextureView update is the fallback frame witness. */
internal fun bifrostTextureFirstFrameAllowed(
    requestGeneration: Long,
    currentGeneration: Long,
    renderedGeneration: Long,
    currentUri: String?,
    startGate: VideoBackdropStartGate,
): Boolean =
    currentUri != null &&
        requestGeneration == currentGeneration &&
        renderedGeneration != requestGeneration &&
        startGate.mayStart(requestGeneration)

/**
 * Render-time resize for preview clips. The decoder keeps the source stream untouched; this transform
 * center-crops it into the actual backdrop surface while conserving its aspect ratio on both axes.
 */
internal fun bifrostAspectPreservingCrop(
    viewWidth: Int,
    viewHeight: Int,
    mediaWidth: Int,
    mediaHeight: Int,
): BifrostAspectCrop? {
    if (viewWidth <= 0 || viewHeight <= 0 || mediaWidth <= 0 || mediaHeight <= 0) return null
    val scale = max(viewWidth.toFloat() / mediaWidth, viewHeight.toFloat() / mediaHeight)
    return BifrostAspectCrop(
        scaleX = mediaWidth * scale / viewWidth,
        scaleY = mediaHeight * scale / viewHeight,
    )
}

internal fun bifrostArtPresentationKey(uri: String, artIsLogo: Boolean): String =
    "$uri\u0000$artIsLogo"

internal fun eligibleBifrostVideoUri(videoUri: String?, powerSave: Boolean): String? =
    videoUri?.takeIf {
        !powerSave &&
            it.isNotBlank() &&
            !it.startsWith("http://", ignoreCase = true) &&
            !it.startsWith("https://", ignoreCase = true)
    }

/** Pure latest-wins state shared by the composable and its timing regression. */
internal data class BifrostMediaState(
    val requestedUri: String? = null,
    val playerUri: String? = null,
    /** Selection that owns [playerUri]; URI equality alone is unsafe across A → B → A. */
    val playerGeneration: Long? = null,
    val renderedUri: String? = null,
    /** Selection that supplied [renderedUri]; only this exact selection may open the bed. */
    val renderedGeneration: Long? = null,
    val generation: Long = 0L,
) {
    val videoVisible: Boolean
        get() =
            renderedGeneration == generation &&
                playerGeneration == generation &&
                renderedUri != null &&
                renderedUri == playerUri &&
                renderedUri == requestedUri

    fun request(uri: String?): BifrostMediaState = copy(
        requestedUri = uri,
        generation = generation + 1L,
    )

    fun settle(requestGeneration: Long): BifrostMediaState {
        if (requestGeneration != generation) return this
        return copy(
            playerUri = requestedUri,
            playerGeneration = requestGeneration,
            // Even a URI that returns immediately must render a frame for its new selection before it can
            // become visible or audible. This rejects late A callbacks after A → B → A.
            renderedUri = null,
            renderedGeneration = null,
        )
    }

    fun onFirstFrame(uri: String, selectionGeneration: Long): BifrostMediaState =
        if (
            selectionGeneration == generation &&
                selectionGeneration == playerGeneration &&
                uri == playerUri &&
                uri == requestedUri
        ) {
            copy(renderedUri = uri, renderedGeneration = selectionGeneration)
        } else {
            this
        }
}

/** The one stable [TextureView] + [MediaPlayer] owner, driven by settled focus and lifecycle. */
@Composable
private fun VideoSurface(
    uri: String?,
    selectionGeneration: Long,
    bedVolume: State<Float>,
    onFirstFrame: (String, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember {
        VideoBackdropController(context.applicationContext) { renderedUri ->
            // This callback is intentionally bound at controller creation rather than to the latest
            // composable lambda. An old A controller may never report a first frame for a later A selection.
            onFirstFrame(renderedUri, selectionGeneration)
        }
    }

    // The cancellable 300 ms settle happens upstream; this effect performs only the accepted latest bind.
    LaunchedEffect(controller, uri) {
        if (uri == null) controller.clear() else controller.play(uri)
    }

    // AU02: the governed, animated bed volume — a separate effect so a volume ramp never re-binds the clip.
    LaunchedEffect(controller) {
        snapshotFlow { bedVolume.value }.collect { controller.setBedVolume(it) }
    }

    // Pause the decode when the app leaves the foreground; resume on return; release when it leaves the tree.
    DisposableEffect(lifecycleOwner) {
        // A controller mounts stopped. Seed from current state before any future lifecycle event;
        // mounting while CREATED/STOPPED can prepare, but cannot start without a later ON_START.
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            controller.onForegrounded()
        } else {
            controller.onBackgrounded()
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> controller.onBackgrounded()
                Lifecycle.Event.ON_START -> controller.onForegrounded()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.release()
        }
    }

    AndroidView(
        factory = { ctx ->
            TextureView(ctx).apply {
                isOpaque = false // let the static cover underneath show through until the first frame lands
                controller.attachView(this)
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) =
                        controller.onSurfaceAvailable(Surface(st), w, h)
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) =
                        controller.onSurfaceSizeChanged(w, h)
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        controller.onSurfaceDestroyed()
                        return true
                    }
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = controller.onTextureUpdated()
                }
            }
        },
        modifier = modifier,
    )
}

/**
 * Owns the looping [MediaPlayer] and the [TextureView] surface for [VideoBackdrop]. All player calls
 * are wrapped so a bad/short/corrupt clip can never crash the gamelist — on any error the static cover simply
 * remains. Centre-crops the frame to fill (a [Matrix] transform on the TextureView) so aspect never distorts.
 *
 * AU02: playback starts SILENT and only reaches [bedVolume] as the composable's governed ramp pushes it —
 * so an unmuted bed can never pop ahead of the policy/fade, and every legacy caller stays audio-silent until
 * the governance layer says otherwise.
 */
private class VideoBackdropController(
    private val appContext: Context,
    private val onFirstFrame: (String) -> Unit,
) {
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var textureView: TextureView? = null
    private var currentUri: String? = null
    private var bedVolume = 0f
    private var startGate = VideoBackdropStartGate()
    private var currentPlaybackToken: Long? = null
    private var currentHasAudioTrack = false
    private var viewW = 0
    private var viewH = 0
    private var videoW = 0
    private var videoH = 0
    private var generation = 0L
    private var renderedGeneration = -1L

    fun attachView(view: TextureView) { textureView = view }

    /** AU02: apply the governed bed volume live (and remember it for the next prepared player). */
    fun setBedVolume(volume: Float) {
        bedVolume = volume.coerceIn(0f, 1f)
        publishAudibleState()
        runCatching { player?.setVolume(bedVolume, bedVolume) }
    }

    fun onSurfaceAvailable(newSurface: Surface, w: Int, h: Int) {
        runCatching { surface?.release() }
        surface = newSurface
        startGate = startGate.surface(available = true)
        viewW = w; viewH = h
        runCatching { player?.setSurface(newSurface) }
        player?.let { current -> currentUri?.let { uri -> maybeStartCurrent(generation, uri, current) } }
        applyCenterCrop()
    }

    fun onSurfaceSizeChanged(w: Int, h: Int) { viewW = w; viewH = h; applyCenterCrop() }

    fun onSurfaceDestroyed() {
        pauseCurrentPlayback()
        runCatching { player?.setSurface(null) }
        runCatching { surface?.release() }
        surface = null
        startGate = startGate.surface(available = false)
    }

    fun onBackgrounded() {
        startGate = startGate.foreground(isForeground = false)
        pauseCurrentPlayback()
    }

    fun onForegrounded() {
        startGate = startGate.foreground(isForeground = true)
        player?.let { current -> currentUri?.let { uri -> maybeStartCurrent(generation, uri, current) } }
    }

    /** Real compositor evidence for vendors that omit MEDIA_INFO_VIDEO_RENDERING_START. */
    fun onTextureUpdated() {
        val uri = currentUri ?: return
        val requestGeneration = generation
        if (
            bifrostTextureFirstFrameAllowed(
                requestGeneration = requestGeneration,
                currentGeneration = generation,
                renderedGeneration = renderedGeneration,
                currentUri = uri,
                startGate = startGate,
            )
        ) {
            renderedGeneration = requestGeneration
            onFirstFrame(uri)
        }
    }

    /** Bind + start [uri]. Idempotent for the same clip (a repeat just ensures it's running). */
    fun play(uri: String) {
        if (uri == currentUri && player != null) {
            player?.let { current -> maybeStartCurrent(generation, uri, current) }
            return
        }
        generation += 1L
        val requestGeneration = generation
        startGate = startGate.request(requestGeneration)
        clearPlayer()
        // The process-wide grace survives controller disposal, while real external music and calls still
        // block in MimirMediaBed.
        MimirMediaBed.refresh()
        currentPlaybackToken = MimirMediaBed.nextOwnPlaybackToken()
        currentUri = uri
        // Correlate ownership before MediaPlayer construction. Some handheld audio stacks publish
        // the player's MEDIA/MOVIE configuration as soon as it is created/prepared, before start().
        // Taking the baseline in maybeStartCurrent() is therefore too late: the player's own
        // configuration is already present and remains permanently classified as external audio.
        currentPlaybackToken?.let(MimirMediaBed::noteOwnPlaybackStarting)
        val nextPlayer = MediaPlayer()
        player = nextPlayer
        nextPlayer.apply {
            setOnPreparedListener { mp ->
                if (!isCurrent(requestGeneration, uri, mp)) return@setOnPreparedListener
                mp.isLooping = true
                currentHasAudioTrack = runCatching {
                    mp.trackInfo.any { it.trackType == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO }
                }.getOrDefault(false)
                // Enter at the CURRENT governed volume (usually 0 mid-ramp) — the bed fades in from here;
                // a pop ahead of policy is structurally impossible.
                mp.setVolume(bedVolume, bedVolume)
                surface?.let { runCatching { mp.setSurface(it) } }
                startGate = startGate.prepared(requestGeneration)
                maybeStartCurrent(requestGeneration, uri, mp)
            }
            setOnVideoSizeChangedListener { mp, w, h ->
                if (isCurrent(requestGeneration, uri, mp)) {
                    videoW = w
                    videoH = h
                    applyCenterCrop()
                }
            }
            setOnInfoListener { mp, what, _ ->
                if (
                    what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START &&
                    renderedGeneration != requestGeneration &&
                    isCurrent(requestGeneration, uri, mp)
                ) {
                    renderedGeneration = requestGeneration
                    onFirstFrame(uri)
                }
                false
            }
            setOnErrorListener { mp, _, _ ->
                if (isCurrent(requestGeneration, uri, mp)) clearPlayer()
                true
            }
            runCatching {
                // AU02: the bed rides USAGE_MEDIA like the UI cues — one master (the Utility Media volume
                // row / system media stream) governs everything Mimir plays. CONTENT_TYPE_MOVIE because
                // this IS a game clip's soundtrack, not sonification.
                setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build(),
                )
                setDataSource(appContext, Uri.parse(uri)) // LOCAL uri (file:// / content://) — guarded upstream
                surface?.let { setSurface(it) }
                prepareAsync()
            }.onFailure {
                if (isCurrent(requestGeneration, uri, nextPlayer)) clearPlayer()
            }
        }
    }

    fun clear() {
        generation += 1L
        startGate = startGate.request(generation)
        clearPlayer()
    }

    fun release() {
        clear()
        textureView = null
        runCatching { surface?.release() }
        surface = null
    }

    private fun clearPlayer() {
        pauseCurrentPlayback()
        currentPlaybackToken?.let(MimirMediaBed::noteOwnPlaybackStopped)
        currentPlaybackToken = null
        runCatching { player?.reset() }
        runCatching { player?.release() }
        player = null
        currentUri = null
        currentHasAudioTrack = false
        renderedGeneration = -1L
        videoW = 0
        videoH = 0
    }

    private fun isCurrent(requestGeneration: Long, uri: String, candidate: MediaPlayer): Boolean =
        generation == requestGeneration && currentUri == uri && player === candidate

    private fun maybeStartCurrent(requestGeneration: Long, uri: String, candidate: MediaPlayer) {
        if (isCurrent(requestGeneration, uri, candidate) && startGate.mayStart(requestGeneration)) {
            val playbackToken = currentPlaybackToken ?: return
            if (runCatching { candidate.isPlaying }.getOrDefault(false)) {
                runCatching { candidate.setVolume(bedVolume, bedVolume) }
                publishAudibleState()
                return
            }
            MimirMediaBed.noteOwnPlaybackStarting(playbackToken)
            runCatching {
                candidate.setVolume(bedVolume, bedVolume)
                candidate.start()
            }
                .onSuccess { publishAudibleState() }
                .onFailure { MimirMediaBed.noteOwnPlaybackStopped(playbackToken) }
        }
    }

    private fun pauseCurrentPlayback() {
        val active = player ?: return
        val playbackToken = currentPlaybackToken ?: return
        if (!runCatching { active.isPlaying }.getOrDefault(false)) return
        // Relinquish process ownership even if a vendor MediaPlayer pause throws: fail closed.
        runCatching { active.setVolume(0f, 0f) }
        runCatching { active.pause() }
        MimirMediaBed.noteOwnPlaybackStopped(playbackToken)
    }

    private fun publishAudibleState() {
        val token = currentPlaybackToken ?: return
        MimirMediaBed.noteOwnBedLevel(
            token = token,
            audible = currentHasAudioTrack &&
                runCatching { player?.isPlaying == true }.getOrDefault(false),
            volume = bedVolume,
        )
    }

    /** Scale the (fill-stretched) TextureView about its centre so the video covers the view without distortion. */
    private fun applyCenterCrop() {
        val view = textureView ?: return
        val crop = bifrostAspectPreservingCrop(viewW, viewH, videoW, videoH) ?: return
        val matrix = Matrix().apply {
            setScale(crop.scaleX, crop.scaleY, viewW / 2f, viewH / 2f)
        }
        view.setTransform(matrix)
    }
}
