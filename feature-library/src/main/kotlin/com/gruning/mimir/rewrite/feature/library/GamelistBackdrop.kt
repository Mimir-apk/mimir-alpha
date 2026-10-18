package com.gruning.mimir.rewrite.feature.library

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import coil.compose.AsyncImagePainter
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import com.gruning.mimir.rewrite.core.ui.mimirCoverRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "AURORA" backdrop (CP05) — an ambient, blurred, dimmed wash of the focused game's LOCAL
 * cover behind the gamelist, enriching the (otherwise flat) surface without overclaiming. Named for the
 * northern-lights colour drift it produces (and the Norse theming).
 *
 * Reuses the cover we ALREADY load locally ([artUri] — a content://file:// the offline resolver confirmed,
 * via the app's network-free Coil loader). NO video, NO new download, NO new dependency, NO posture change.
 * The separate see-through *video* mode is a later, gated build.
 *
 * Cover recognition: notch the blur DOWN so a bit of the cover is recognizable — the wash should hint
 * at the art, not fully dissolve it. On API < 31 [blur] no-ops → a dim, lightly-recognizable cover (fine).
 * The crossfade is slower than the carousel's instant snap so the atmosphere *drifts* as focus changes.
 */
@Composable
fun AuroraBackdrop(
    artUri: String?,
    artIsLogo: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val uri = artUri?.takeIf { it.isNotBlank() }
    Box(modifier.fillMaxSize()) {
        // Generic Crossfade retains every interrupted target until it settles. Aurora instead reveals only
        // the latest renderable (URI + presentation kind) — the prior art vanishes at the swap.
        // PERF-F (2026-07-17): ONE persistent Image node hosts every identity. The old `key(uri)` DISPOSED
        // and rebuilt the whole full-screen blurred subtree (blur RenderEffect included) on every focus
        // commit — pure per-press composition cost. The painter and the reveal still swap per identity
        // (remember keys below), and the swap-frame is safe by construction: a painter that briefly holds
        // the PRIOR art (the documented AsyncImagePainter one-commit lag) draws at reveal alpha 0, because
        // the new identity's reveal starts at 0 in the same commit.
        if (uri != null) {
            val context = LocalContext.current
            val request = remember(uri, artIsLogo, context) {
                // PERF-B: the shared cover identity — the blurred wash reuses the EXACT bitmap the carousel
                // already decoded (one entry per cover app-wide) instead of decoding a second full-screen copy.
                mimirCoverRequest(context, uri)
            }
            val painter = rememberAsyncImagePainter(request)
            val artReady = when (val state = painter.state) {
                is AsyncImagePainter.State.Success -> true
                is AsyncImagePainter.State.Loading -> state.painter != null
                else -> false
            }
            val reveal = remember(uri, artIsLogo) { Animatable(0f) }
            LaunchedEffect(uri, artIsLogo, artReady) {
                if (artReady) {
                    reveal.animateTo(
                        1f,
                        animationSpec = tween(HomeShelfOutgoingFadeMs, easing = CoverflowMotionEasing),
                    )
                }
            }
            Image(
                painter = painter,
                contentDescription = null,
                contentScale = if (artIsLogo) ContentScale.Fit else ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(34.dp)   // notched down from 48 → the art stays faintly recognizable
                    // PERF-F: the fade alpha is a DRAW-phase read (CP04 grammar) — the ~500ms reveal no
                    // longer recomposes the full-screen node every animation frame. 0.34 = destination
                    // ceiling; a cold decode still cannot pop at full alpha.
                    .graphicsLayer { this.alpha = 0.34f * reveal.value },
            )
        }
        // Stage luminance is not art identity. Keeping the scrim outside the keyed reveal prevents every
        // focus tick (and warm→NO_ART) from pulsing the entire backdrop behind otherwise-stable tiles.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.42f)))
    }
}

/**
 * The gamelist backdrop treatments — the "Bifröst" engine (CP05 / UXCI-01.1). The persisted user choice lives
 * in `BackdropStore` (app); the three surfaced options map here:
 *  - [OFF]              — flat themed surface.
 *  - [BIFROST_BACKDROP] — "Backdrop only": the see-through preview VIDEO veiled by the theme gradient, with NO
 *                         cover art layered on top (the gamelist reads the video/theme, not the box art).
 *  - [BIFROST]          — "Backdrop + media": the full look — the same video PLUS the focused game's cover/disc.
 *  - [AURORA]           — a separate internal engine (blurred, dimmed cover wash, no video); NOT surfaced in
 *                         Settings, kept for eval. All are mutually exclusive — one ambient mode behind the list.
 */
enum class GamelistBackdropMode { OFF, AURORA, BIFROST, BIFROST_BACKDROP }

/** A Library Hero is renderable only when Bifröst is enabled and the source is already local. */
internal fun libraryHeroBackdropUri(mode: GamelistBackdropMode, candidate: String?): String? =
    libraryLocalBackdropUri(mode, candidate)

/**
 * **Hero MOTION for the gamelist**, 2026-08-14. Hero motion is wired
 * in the library gamelist as well as on Home.
 *
 * Deliberately the SAME gate as [libraryHeroBackdropUri], not a looser one. Three properties come from
 * that and each would be a defect if it drifted:
 *  - **Bifröst-gated.** [GamelistBackdropMode.OFF] and [GamelistBackdropMode.AURORA] show no hero at all,
 *    so they must show no clip either — a video playing behind a backdrop the player switched off would
 *    be the loudest possible way to ignore a setting.
 *  - **Local sources only.** `content://`, `file://`, `android.resource://`. A remote scheme reaching a
 *    `MediaPlayer` would be a network read on a surface this programme forbids one on, and unlike a
 *    still it would keep reading for as long as the clip loops.
 *  - **One answer about what "renderable" means**, shared with the still, so a future scheme added to one
 *    cannot silently be refused by the other.
 *
 * Motion is **not** a replacement for the still: [LibraryScreen] passes both to [VideoBackdrop], which
 * already draws the static art while the decode settles and keeps it as the fallback when a clip cannot
 * play (battery saver, decoder refusal). That is why this returns the clip alone and never falls back to
 * the hero itself — the fallback belongs to the renderer that can see whether the video actually started.
 */
internal fun libraryHeroMotionUri(mode: GamelistBackdropMode, candidate: String?): String? =
    libraryLocalBackdropUri(mode, candidate)

/**
 * The shared "is this a Bifröst-eligible local source?" answer behind the still and the clip.
 *
 * Extracted rather than duplicated: the two gates must agree, and the cost of them disagreeing is a hero
 * whose still and motion obey different rules — visible only on the device, and only for the games that
 * happen to sit on the wrong side of the difference.
 */
private fun libraryLocalBackdropUri(mode: GamelistBackdropMode, candidate: String?): String? {
    if (!homeBackdropActive(mode)) return null
    val value = candidate?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val scheme = value.substringBefore(':', missingDelimiterValue = "").lowercase()
    return value.takeIf { scheme == "content" || scheme == "file" || scheme == "android.resource" }
}

/** A newly focused title must enter a neutral veiled field before any different title's Hero can reveal. */
internal fun libraryHeroRequiresNeutralHandoff(presentedUri: String?, requestedUri: String?): Boolean =
    presentedUri != null && presentedUri != requestedUri

internal fun libraryHeroOwnedPresentationUri(presentedUri: String?, requestedUri: String?): String? =
    presentedUri?.takeIf { it == requestedUri }

/**
 * How long the OUTGOING hero may keep painting while the destination settles.
 *
 * One page glide ([MimirMotion.ModeTransitionMs]), because that is what the hold is for: the outgoing
 * picture survives exactly as long as the incoming page takes to arrive, and not one beat longer. It is a
 * bound, not a duration — a destination that reaches a terminal state in 40 ms hands over in 40 ms.
 */
internal val LibraryHeroHandoffHoldMs: Long = MimirMotion.ModeTransitionMs.toLong()

/**
 * The hero that may keep painting while a NEW one settles behind it — null when nothing should be held.
 *
 * **Why this exists.** Measured on the Thor, 2026-09-03: a list-view platform switch left the entire
 * surface black for 50–83 ms — longer the bigger the destination list — while the header already read the
 * destination's name and game count. [libraryHeroOwnedPresentationUri] is most of that black: draw
 * ownership is decided IN COMPOSITION, so the outgoing hero stopped painting on the very frame `imageUri`
 * changed, a frame before any effect could run. The `snapTo(0f)` in the effect was the second half, not
 * the cause.
 *
 * **Policy 2026-09-03**, given the measured endpoints: hold the outgoing hero. That deliberately
 * accepts what the neutral handoff was written to prevent — A's hero under B's title — for at most
 * [LibraryHeroHandoffHoldMs]. The two rules are still both true, they just apply to different windows:
 * A may be *seen* during the handoff, and A may never be *revealed* as B's identity.
 *
 * Held only while [holding]; never when the outgoing already IS the request (no handoff in flight).
 * A null [requestedUri] still holds, so leaving a game with no art fades the old hero out instead of
 * cutting it — the caller's `fieldPresence` owns that fade.
 */
internal fun libraryHeroHeldOutgoingUri(
    outgoingUri: String?,
    requestedUri: String?,
    holding: Boolean,
): String? = outgoingUri?.takeIf { holding && it != requestedUri }

internal const val LightGamelistVeilAlpha = 0.05f
internal const val LibraryShelfAtmosphericClouds = false
internal const val ManiLibraryFooterShadowAlpha = 0.12f

/** Máni keeps a restrained lower shadow after its former cloud bank is removed; every other theme is clear. */
internal fun libraryShelfFooterShadowColor(palette: MimirThemePalette): Color =
    if (palette.id == MimirThemeId.MANI) {
        palette.materials.scene.shadow.copy(alpha = ManiLibraryFooterShadowAlpha)
    } else {
        Color.Transparent
    }

/**
 * The gamelist's own veil dialect, taken by BOTH of its backdrop paths.
 *
 * RC-38 defect (spec §6.1): the still path ([LibraryHeroBackdrop]) took this, while the motion path
 * ([VideoBackdrop]'s default) took [homeVeil] — so on a metallic theme, focusing a game that happens to have
 * a clip swapped the veil PROFILE (uniform 5% wash -> the shaped 6/3/2/1/2/3/4% light profile) under
 * otherwise identical art. [bifrostDefaultVeil] is now the one answer both paths read, which is what
 * `VideoBackdrop`'s own KDoc already said its default was for ("Home keeps passing its own veil; the
 * gamelist call sites take this default") before the metallic fork was introduced on the still side alone.
 *
 * Non-metallic themes are bit-identical either way — this function RETURNS [homeVeil] for them.
 *
 * Library owns a uniform five-percent light wash; Home keeps its separate shaped light profile.
 */
internal fun libraryGamelistVeil(palette: MimirThemePalette) =
    if (palette.usesNearClearMediaVeil) {
        Brush.verticalGradient(
            *HomeVeilPositions.map { position ->
                position to palette.surfaceBase.copy(alpha = LightGamelistVeilAlpha)
            }.toTypedArray(),
        )
    } else {
        homeVeil(palette)
    }

/**
 * The veil a [VideoBackdrop] wears when its caller passes none. Every no-veil caller is a gamelist-dialect
 * surface (the gamelist itself, plus the two `app` overlays that float glass over it), so the gamelist's
 * answer is the default — Home is the only caller that passes its own, and it still does.
 */
internal fun bifrostDefaultVeil(palette: MimirThemePalette): Brush = libraryGamelistVeil(palette)

/**
 * Static-only Library Bifröst backdrop. It deliberately shares Home's veil, window-solved FILL decode
 * envelope ([mimirHeroDecodeEnvelopePx]), memory-cache identity, decode gate, and Mimir fade while
 * excluding every video/player/audio path.
 *
 * The next target prewarms invisibly. A burst A→B→C can therefore only publish C. A title change first
 * clears the old media layer in the same target effect, leaving one stable veil while C settles/hydrates;
 * only C can then reveal. Missing/corrupt targets fade the complete field away.
 */
@Composable
internal fun LibraryHeroBackdrop(
    imageUri: String?,
    modifier: Modifier = Modifier,
    settleMs: Long = BifrostMediaSettleMs,
) {
    val context = LocalContext.current
    val palette = LocalMimirPalette.current
    val veil = remember(palette) { libraryGamelistVeil(palette) }
    val heroEnvelope = rememberMimirHeroDecodeEnvelope()
    // Start the destination decode immediately; the settle clock below runs concurrently with it.
    val prewarmRequest = remember(imageUri, context, heroEnvelope) {
        imageUri?.let { uri -> mimirHomeStaticHeroRequest(context, uri, heroEnvelope) }
    }
    // URI-keying prevents AsyncImagePainter's documented one-commit prior-state lag from satisfying B's
    // terminal gate with A's Success. The fresh painter can emit only destination-owned terminal states.
    val prewarmPainter = key(imageUri) { rememberAsyncImagePainter(prewarmRequest) }
    var presentedUri by remember { mutableStateOf<String?>(null) }
    // Both are keyed on the destination and therefore recomputed IN COMPOSITION on the frame the switch
    // lands -- the same reason the ownership check below lives in composition rather than in the effect.
    // Captured a frame late, they would each cost exactly the black frame they exist to remove.
    val outgoingUri = remember(imageUri) { presentedUri }
    val holdOutgoing = remember(imageUri) { mutableStateOf(true) }
    // The held layer owns its OWN alpha, born at 1 for each destination. Deriving it as
    // `1f - mediaReveal` looks right and is wrong: `mediaReveal` is still 1 from the OUTGOING hero's own
    // reveal while the hold is running, so the held layer drew at alpha 0 and the surface stayed exactly
    // as black as before. Measured, not reasoned -- the frame strip showed the black, the luma threshold
    // that said "fixed" was reading a theme change.
    val outgoingReveal = remember(imageUri) { Animatable(1f) }
    val fieldPresence = remember { Animatable(0f) }
    val mediaReveal = remember { Animatable(0f) }
    LaunchedEffect(imageUri, prewarmPainter, settleMs) {
        val destination = imageUri
        coroutineScope {
            val settled = async { if (destination != null && settleMs > 0L) delay(settleMs) }
            val terminal = async {
                if (destination == null) {
                    null
                } else {
                    snapshotFlow { prewarmPainter.state }.first { state ->
                        state is AsyncImagePainter.State.Success ||
                            state is AsyncImagePainter.State.Error
                    }
                }
            }

            if (destination != null && libraryHeroRequiresNeutralHandoff(presentedUri, destination)) {
                // The outgoing hero is NO LONGER torn down here  -- it keeps
                // painting until the destination reaches a terminal state, then crossfades to it below.
                // The bound is what keeps "at most one page glide" true: a slow or stalled decode releases
                // to the neutral field rather than sitting on a stale identity indefinitely.
                //
                // `destination != null` guards the branch because `withTimeoutOrNull` cannot tell a timeout
                // from a block that returned null, and the null-destination `terminal` returns null at once.
                // That case falls through to the fade below with the hold still on, which is what makes
                // "leaving a game with no art" a fade rather than a cut.
                val landed = withTimeoutOrNull(LibraryHeroHandoffHoldMs) { terminal.await() }
                if (landed == null) {
                    outgoingReveal.snapTo(0f)
                    holdOutgoing.value = false
                    mediaReveal.snapTo(0f)
                    presentedUri = null
                }
            }

            val terminalState = terminal.await()
            // A missing/corrupt local target fades the complete prior field rather than ghosting it.
            if (destination == null || terminalState is AsyncImagePainter.State.Error) {
                settled.cancel()
                if (fieldPresence.value > 0f) {
                    fieldPresence.animateTo(0f, tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing))
                }
                holdOutgoing.value = false
                mediaReveal.snapTo(0f)
                presentedUri = null
                return@coroutineScope
            }

            // The neutral veil is independent from title identity, so hydration never flashes the base
            // scene or doubles veil opacity during URI→URI navigation.
            if (fieldPresence.value < 1f) {
                fieldPresence.animateTo(1f, tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing))
            }
            settled.await()
            if (presentedUri != destination) {
                mediaReveal.snapTo(0f)
                presentedUri = destination
            }
            if (mediaReveal.value < 1f) {
                // Concurrent, not sequential: run in series and the pair reads as a fade to black
                // followed by a fade up, which is the defect wearing a slower disguise.
                val release = launch {
                    outgoingReveal.animateTo(0f, tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing))
                }
                mediaReveal.animateTo(1f, tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing))
                release.join()
            }
            // Released only after the crossfade has finished, so the outgoing layer stays mounted for the
            // whole of it. Releasing at the swap would reinstate the cut this change exists to remove.
            holdOutgoing.value = false
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer { alpha = fieldPresence.value },
    ) {
        // The outgoing hero, fading out under the destination fading in -- the two alphas are
        // complementary, so this is one crossfade and never a beat of neither.
        libraryHeroHeldOutgoingUri(outgoingUri, imageUri, holdOutgoing.value)?.let { uri ->
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = outgoingReveal.value },
            ) {
                LibraryHeroBackdropImage(uri)
            }
        }
        // Draw ownership is synchronous with focus/title composition. LaunchedEffect bookkeeping runs after
        // composition, so comparing here closes the otherwise-visible A-under-B frame (including B=no art).
        libraryHeroOwnedPresentationUri(presentedUri, imageUri)?.let { uri ->
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = mediaReveal.value },
            ) {
                LibraryHeroBackdropImage(uri)
            }
        }
        Box(Modifier.fillMaxSize().background(veil))
    }
}

@Composable
private fun LibraryHeroBackdropImage(imageUri: String) {
    val context = LocalContext.current
    val heroEnvelope = rememberMimirHeroDecodeEnvelope()
    val request = remember(imageUri, context, heroEnvelope) {
        mimirHomeStaticHeroRequest(context, imageUri, heroEnvelope)
    }
    Image(
        painter = rememberAsyncImagePainter(request),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * FUX-F "Hearth" Home collapse: on the Home Shelf, [BIFROST] and [BIFROST_BACKDROP] map to the SAME
 * video-only backdrop recipe (there is no cover-on-top distinction on Home — box art is never layered over
 * the Home clip, dup-cover ban), [OFF] keeps today's plain scene, and [AURORA] is NEVER used on Home
 * ("never Aurora on Home" — the blurred-cover wash is a gamelist-only eval engine).
 *
 * Deliberately an exhaustive `when` with NO `else`: adding a future [GamelistBackdropMode] fails to compile
 * here (F4/F5) instead of silently falling into an unintended Home behavior.
 */
fun homeBackdropActive(mode: GamelistBackdropMode): Boolean = when (mode) {
    GamelistBackdropMode.BIFROST -> true
    GamelistBackdropMode.BIFROST_BACKDROP -> true
    GamelistBackdropMode.OFF -> false
    GamelistBackdropMode.AURORA -> false
}
