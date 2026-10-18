package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.RaKeyState
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.gruning.mimir.rewrite.core.model.library.GameRow
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusState
import com.gruning.mimir.rewrite.core.input.LocalFocusAuthority
import com.gruning.mimir.rewrite.core.ui.MimirGlass
import com.gruning.mimir.rewrite.core.ui.MimirMannazBadge
import com.gruning.mimir.rewrite.core.ui.LocalMimirAmbientMotionPhase
import com.gruning.mimir.rewrite.core.ui.LocalMimirControllerNavigationActive
import com.gruning.mimir.rewrite.core.ui.LocalMimirGlanceClearance
import com.gruning.mimir.rewrite.core.ui.MimirGlanceClearance
import com.gruning.mimir.rewrite.core.ui.LocalMimirControllerHintLabels
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirAchievementCard
import com.gruning.mimir.rewrite.core.ui.MimirGlassScrim
import com.gruning.mimir.rewrite.core.ui.MimirLiquidGlassScrim
import com.gruning.mimir.rewrite.core.ui.mimirGlassBackdropProgress
import com.gruning.mimir.rewrite.core.ui.MimirGlassWarmup
import com.gruning.mimir.rewrite.core.ui.rememberMimirGlassPanelProgress
import com.gruning.mimir.rewrite.core.ui.rememberMimirGlassProgress
import com.gruning.mimir.rewrite.core.ui.rememberMimirGlassReturnProgress
import com.gruning.mimir.rewrite.core.ui.mimirLedgerStep
import com.gruning.mimir.rewrite.core.ui.mimirLingeringDepartMs
import com.gruning.mimir.rewrite.core.ui.mimirReducedMotion
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirArrivalFrameCapMs
import com.gruning.mimir.rewrite.core.ui.MimirCrossSwapMs
import com.gruning.mimir.rewrite.core.ui.MimirPageMotion
import com.gruning.mimir.rewrite.core.ui.MimirSwapPageGate
import com.gruning.mimir.rewrite.core.ui.MimirPrimaryViewportProfile
import com.gruning.mimir.rewrite.core.ui.MimirViewportMode
import com.gruning.mimir.rewrite.core.ui.MimirViewportSettings
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirTransitionEpochLedger
import com.gruning.mimir.rewrite.core.ui.holdMimirInputUntilShown
import com.gruning.mimir.rewrite.core.ui.withMimirStallGuard
import com.gruning.mimir.rewrite.core.ui.mimirPageTouchShield
import com.gruning.mimir.rewrite.core.ui.mimirOverlayArriveSpec
import com.gruning.mimir.rewrite.core.ui.mimirOverlayLeaveSpec
import com.gruning.mimir.rewrite.core.ui.mimirRingSafeFadeAlpha
import com.gruning.mimir.rewrite.core.ui.mimirHold
import com.gruning.mimir.rewrite.core.ui.mimirRetainLingeringLayers
import com.gruning.mimir.rewrite.core.ui.mimirSwapTransform
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.ShelfFaceShape
import com.gruning.mimir.rewrite.feature.browse.BrowseScreen
import com.gruning.mimir.rewrite.feature.library.DisplayRole
import com.gruning.mimir.rewrite.feature.library.GameLaunchUiState
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCard
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardUiState
import com.gruning.mimir.rewrite.feature.library.HomeShelfScreen
import com.gruning.mimir.rewrite.feature.library.libraryCoverArtUri
import com.gruning.mimir.rewrite.feature.library.HomeShelfScope
import com.gruning.mimir.rewrite.feature.library.HomeTomeFolio
import com.gruning.mimir.rewrite.feature.library.HomeTomeFolioScope
import com.gruning.mimir.rewrite.feature.library.HomeTomeFolioUiState
import com.gruning.mimir.rewrite.feature.library.HomeTomeMember
import com.gruning.mimir.rewrite.feature.library.TomeId
import com.gruning.mimir.rewrite.feature.library.LaunchChoiceOverlay
import com.gruning.mimir.rewrite.feature.library.homeShelfRowForKey
import com.gruning.mimir.rewrite.feature.library.homeTomeSeatFocusKey
import com.gruning.mimir.rewrite.feature.library.launchCardFaceIsPhysical
import com.gruning.mimir.rewrite.feature.library.launchCardFacts
import com.gruning.mimir.rewrite.feature.library.launchCardSizeBytes
import com.gruning.mimir.rewrite.feature.library.rememberEqualInstance
import com.gruning.mimir.rewrite.feature.library.rememberLibraryOpenWithPrewarm
import com.gruning.mimir.rewrite.feature.library.libraryDetailDiscs
import com.gruning.mimir.rewrite.feature.library.relativePlayed
import com.gruning.mimir.rewrite.feature.library.resolveHomeLaunchCardStatusLine
import com.gruning.mimir.rewrite.feature.library.resolveHomeLaunchCardRuntimeStatusLine
import com.gruning.mimir.rewrite.feature.library.MimirShelfFace
import com.gruning.mimir.rewrite.feature.library.LibraryScreen
import com.gruning.mimir.rewrite.feature.library.LocalLibraryRepository
import com.gruning.mimir.rewrite.feature.settings.BackdropPickerOverlay
import com.gruning.mimir.rewrite.feature.settings.BackgroundPickerOverlay
import com.gruning.mimir.rewrite.feature.settings.MimirButtonPickerOverlay
import com.gruning.mimir.rewrite.core.ui.LocalMimirSceneGrounds
import com.gruning.mimir.rewrite.feature.settings.ControllerMappingOverlay
import com.gruning.mimir.rewrite.feature.settings.DetailScope
import com.gruning.mimir.rewrite.feature.settings.MusicSubPageScopes
import com.gruning.mimir.rewrite.feature.settings.detailKey
import com.gruning.mimir.rewrite.feature.settings.PlatformEmulatorPickerOverlay
import com.gruning.mimir.rewrite.feature.settings.ProfileNameEditOverlay
import com.gruning.mimir.rewrite.feature.settings.RetroAchievementsConnectUi
import com.gruning.mimir.rewrite.feature.settings.RetroAchievementsKeyEditOverlay
import com.gruning.mimir.rewrite.feature.settings.SteamGridDbKeyEditOverlay
import com.gruning.mimir.rewrite.feature.settings.SteamGridDbKeyValidationUiState
import com.gruning.mimir.rewrite.feature.settings.clearSteamGridDbKeyDraft
import com.gruning.mimir.rewrite.feature.settings.SettingsHome
import com.gruning.mimir.rewrite.feature.settings.ThemePickerScope
import com.gruning.mimir.rewrite.feature.settings.ThemePreviewGallery
import com.gruning.mimir.rewrite.core.ui.theme.MimirColors
import com.gruning.mimir.rewrite.core.files.StorageVolumeListing
import com.gruning.mimir.rewrite.feature.files.FileBrowserSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The root shelf hop's own fade — Library ↔ Home, and every path between two destinations.
 *
 * Given its own value rather than continuing to alias [MimirMotion.SceneMs]. Measured on a
 * Thor at 60 fps before the change: **Library → Home 183 ms, Home → Library 133 ms**, bracketing the
 * shared token's 160 ms plus dispatch slop.
 *
 * `SceneMs` could not simply be lowered: eight consumers share it, including
 * `LibraryHeroHandoffHoldMs` (the held-hero crossfade) and `LibraryPlatformSwitchFloorMs` (the platform
 * switch's own floor). Moving all of them to speed up one hop is the blast radius this constant's
 * separate NAME already anticipated — it just never had a separate value.
 *
 * **This duration is NOT the lever for how fast the hop feels, and that was measured rather than
 * assumed.** It is left at [MimirMotion.SceneMs] on purpose.
 *
 * Shortening this was investigated to speed Library ↔ Home transitions. Two candidate
 * values were tried and one shipped to the device: 120 ms (refused by `MimirAppHostMotionTest`, because
 * 140 is `FocusMs` and a whole-screen change must not be quicker than a focus ring moving one tile),
 * then 140 ms, built and installed.
 *
 * **140 ms measured identical to 160 ms.** 60 fps capture on the Thor, timing each transition over
 * 10 → 90% of its OWN luma excursion so the number does not depend on which game the shelf is sitting
 * on:
 *
 * | | Library → Home | Home → Library |
 * |---|---|---|
 * | 160 ms nominal | 83.3 ms | 66.7 ms |
 * | 140 ms nominal | 83.3 ms | 83.3 ms |
 *
 * The visible part of this fade is already ~83 ms whatever the nominal says, because the house curve is
 * slow at the tail — the same property [MimirMotion.OverlayDissolveCoverMs] documents from the other
 * direction. Trimming the nominal trims time the player could not see, so it buys nothing, and shipping
 * it would have been a change that moved a number without moving the feel.
 *
 * **What the hop actually costs is elsewhere.** Library → Home measured ~183 ms end to end in both
 * builds while this fade accounts for ~83 ms of it. The remaining ~100 ms is destination content
 * arriving, and that is where a real speed-up has to come from — profiled, not guessed at by turning
 * this constant down further.
 *
 * Nothing here is on the input path either way: [MimirMotion.InputDelayMs] is 0 and logical navigation
 * commits before this animation starts.
 */
internal const val RootShelfOutgoingLayerDurationMs = MimirMotion.SceneMs
internal const val RootShelfOutgoingLayerStartAlpha = 1f

/**
 * Where the page being left starts. Normally [RootShelfOutgoingLayerStartAlpha]. On a reversal -- B
 * back to the page still dissolving away -- the page returned to was painted at [paintedReturning], so the page being
 * left starts at the complement: the screen is what it was, instead of the page left jumping back to full for a few
 * frames and dissolving again (a blink). A finished dissolve painted 0, which gives the normal start.
 */
internal fun rootShelfOutgoingStartAlpha(returning: Boolean, paintedReturning: Float): Float =
    if (returning) (1f - paintedReturning).coerceIn(0f, 1f) else RootShelfOutgoingLayerStartAlpha

/*
 * Library -> Home is SYMMETRIC with every other hop, on [RootShelfOutgoingLayerDurationMs]
 * (SceneMs, 160 ms). A former ~50 ms exception briefly exposed a fallback square beside the tile
 * on Nova; it measured as a 33-42 ms cut on Thor against 134-139 ms for Home -> Library.
 * No page leaves faster than a scene change, in either direction.
 */

/**
 * How long the scene chrome — the 18dp backdrop blur and the destination's suppressed semantics — must
 * survive after the overlay stack empties. **Exactly as long as the outgoing overlay still has PIXELS on
 * screen for it to sit behind, and not one frame longer.**
 *
 *  Perfetto answered it. The
 * hold used to be unconditional — every overlay that was not [MimirOverlayKind.HOME_LAUNCH_CARD] got a
 * flat [MimirMotion.OverlayMs]. For the file manager that looked like time spent covering nothing: on
 * Back its subtree was disposed on the SAME commit that emptied the stack (measured: one
 * `Compose:onForgotten`, one frame), so the player watched a fully blurred, empty shelf until the delay
 * expired — on the Odin, the browser was gone at +30.7ms and the blur did not start unwinding
 * until **+221.8ms**. A 191ms freeze.
 *
 * **The wait was real; the emptiness was not the wait's fault.** This function first returned 0 for
 * [MimirOverlayKind.FOLDER_BROWSER] on that reading, and a 60fps pixel + `tesseract` pass over the result
 * refuted it: with no hold, the browser's fade and the scene's blur run together, which composites a
 * half-faded SHARP manager over a half-unblurred DIFFERENT shelf and puts TWO complete UIs on screen for
 * ~50ms. The hold's actual job is to keep the backdrop UNIFORM while the outgoing overlay dissolves. It
 * was empty for the file browser only because [MimirDirectOverlayContent] was missing its arm — one
 * omission that produced a dead 180ms, a missing exit AND an empty animating layer. With the arm in place
 * the hold is earned, and FOLDER_BROWSER keeps NO special case here.
 *
 * **So this function exists to hold one exception and one lesson, not to be clever.** The tempting general
 * rule — "release immediately unless the pixel-fade host is drawing it" — is WRONG twice over. It would
 * have shipped a regression on [MimirOverlayKind.HOME_TILE_MENU], which that host does not draw but which
 * keeps its own pixels through an `AnimatedVisibility` `exit = fadeOut(tween(HomeOverlayDissolveMs))` (see
 * `HomeTileMenuOverlayHost`); and it is the same shortcut that produced the double exposure above. Every
 * kind keeps exactly the hold it had before this function existed. Which of them are holding chrome they
 * do not need is a real question and an owed audit — each needs its own read of whether anything survives
 * the commit, and guessing per-kind from a list membership is what put this defect here in the first place.
 */
/**
 * Whether the destination's scene glass may be released outright — blur AND darkening scrim both to 0.
 *
 * True only for [MimirOverlayKind.HOME_TOME_LIBRARY], and only once its arrival has settled, because that
 * is the one overlay whose own ground is unconditionally opaque (`VideoBackdrop`'s floor). Under an
 * opaque ground the scene is not merely hard to see, it is not composited at all, so an 18 dp
 * full-screen blur of it is work with no pixels to show for it — measured at **8.5 ms of RenderThread
 * `issue` per frame on the Thor**, against 1.5 ms with it released.
 *
 * **The epoch fence closes a hazard on the folio return.**
 * [MimirNavigationCoordinator.homeTomeLibraryOpenSettled] is deliberately never reset on close, so on a
 * folio->Library return it still reads `true` from before the Folio was opened, for the gap between this
 * overlay becoming active and its arrival effect firing its own false-edge. Gated on the latch alone,
 * the blur was therefore suppressed *during* that return crossfade — the sharp-destination peek the
 * `mimirOverlayUsesGlass` allowlist records as a repeated defect, arriving through a new
 * door. The window is one composition-to-effect gap, so roughly one frame of a 180 ms crossfade.
 *
 * **How visible that one frame was is UNMEASURED, and the attempt is worth recording because it looked
 * conclusive and was not.** A burst capture of the return on both arms differed on 95.7% of pixels on
 * the first frame, and the outgoing Folio's covers read sharper. Then the same burst was run twice on
 * the SAME build: **frame one differs from itself on 96.7% of pixels.** `screencap` does not land on a
 * fixed phase of a running crossfade, so for that frame the instrument cannot tell a changed picture
 * from a differently-timed one, and the cross-arm number was inside its own noise. (Its magnitude was
 * not — mean delta 11.87 against a 2.93 noise floor — which is suggestive and nothing more.) The
 * settled frames, where the instrument IS reliable, are byte-identical to ship: 0.000% against a 0.223%
 * noise floor.
 *
 * So the fence is here because gating a visual suppression on a latch documented as stale is wrong by
 * construction, and it costs nothing — not because a capture convicted it.
 *
 * [MimirNavigationCoordinator.homeTomeLibraryOpenArrivalEpoch] exists for precisely this and says so:
 * *"a waiter fences on this epoch (read once, before the wait starts) so it can require 'the epoch
 * advanced past what I captured, AND settled is true' rather than trusting whatever
 * `homeTomeLibraryOpenSettled` already says."* Captured on the activation edge
 * (`remember(activeOverlay)`), which composition reaches before the arrival's `LaunchedEffect` runs, so
 * a stale `true` can never satisfy it.
 *
 * All three parts are load-bearing:
 * - `settled` keeps the arrival's blur, which is the retained CP01 arrival beat. Through it
 *   the ground is still translucent, so the scene IS visible and the blur IS doing something.
 * - `activeOverlay` restores the blur for the close dissolve. The settle latch is deliberately not reset
 *   on close, so it alone would leave the scene sharp under a dissolving overlay — two detailed images
 *   compositing, which is the double-exposure [mimirOutgoingChromeHoldMs] exists to prevent.
 * - the epoch fence keeps the blur through a RETURN arrival, where the latch's stale `true` would
 *   otherwise suppress it under a still-translucent ground.
 *
 * Deliberately NOT generalised to "any overlay with an opaque ground". Every other kind on
 * [mimirOverlayUsesGlass] is translucent to some degree, and two of them were measured idling at 0.00%
 * jank with the blur live — there is nothing to win there and a settled look to lose.
 */
internal fun mimirSceneGlassSuppressed(
    activeOverlay: MimirOverlayKind?,
    tomeLibraryOpenSettled: Boolean,
    arrivalEpoch: Long,
    epochAtActivation: Long,
): Boolean = activeOverlay == MimirOverlayKind.HOME_TOME_LIBRARY &&
    tomeLibraryOpenSettled &&
    arrivalEpoch > epochAtActivation

/**
 * The direct-overlay host's departing clock: every overlay, the file browser included, departs on [MimirMotion.OverlayMs]
 * (review 2026-10-15, Q3 siblings matched: Files used to depart on the return clock, 160 ms, while Utility departed on 180,
 * and the 130 ms cover under both was measured against 180). The glass under it holds [MimirMotion.OverlayDissolveCoverMs]
 * and clears on [MimirMotion.OverlayMs] too .
 *
 * An appearance-image picker leaving FOR the browser (Upload) dissolves on the clock the browser ARRIVES with from the
 * dock ([mimirIncomingOverlayArriveMs], the page tier's [MimirMotion.ShineMs]), after [mimirOutgoingLayerHoldMs]: see
 * that function for why the picker's dissolve IS the browser's arrival there.
 */
internal fun mimirDirectOverlayDepartMs(
    from: MimirOverlayKind?,
    to: MimirOverlayKind? = null,
    reducedMotion: Boolean = false,
): Int = when {
    to == MimirOverlayKind.FOLDER_BROWSER && mimirOverlayIsAppearanceImagePicker(from) ->
        mimirIncomingOverlayArriveMs(kind = to, homeTomeLibraryOpenOrigin = null, reducedMotion = reducedMotion)
    else -> MimirMotion.OverlayMs
}

/** The two appearance-image pickers (concept A): full-screen on an opaque backing, and what Upload opens the browser over. */
internal fun mimirOverlayIsAppearanceImagePicker(kind: MimirOverlayKind?): Boolean =
    kind == MimirOverlayKind.BACKGROUND_PICKER || kind == MimirOverlayKind.MBUTTON_PICKER

/**
 * How long a departing overlay's pixels STAND at full alpha over the arriving one before they dissolve (the
 * `departHoldMs` seam of the pixel-fade host).
 *
 * Upload-to-browser transitions exposed Settings beneath the appearance picker. Captured at 5x
 * on the emulator (before): the
 * picker's pixels left on OverlayMs from the press while the browser sat at alpha 0 through its 130 ms lead, so for
 * a beat NEITHER covered the Settings pane beneath -- two opaque surfaces crossfading at partial alpha over a third.
 *
 * The host draws the OUTGOING layer on top of the incoming one. So the browser now starts at alpha 1 beneath the
 * picker ([mimirIncomingLayerStartAlpha]), the picker's pixels stand for the browser's cold lead
 * ([mimirIncomingOverlayLeadMs], the [MimirMotion.OverlayDissolveCoverMs] beat every page arrival waits), then
 * dissolve on the browser's own arrival clock ([mimirDirectOverlayDepartMs]). Between two opaque surfaces the top
 * one dissolving over the bottom at 1 is pixel-for-pixel the bottom fading in: the browser opens over a picker with
 * the motion it has from the dock, and no frame shows the pane beneath. Nothing else holds: every other pair keeps
 * the dissolve it had.
 */
internal fun mimirOutgoingLayerHoldMs(from: MimirOverlayKind?, to: MimirOverlayKind?, reducedMotion: Boolean): Int = when {
    reducedMotion -> 0
    to == MimirOverlayKind.FOLDER_BROWSER && mimirOverlayIsAppearanceImagePicker(from) ->
        mimirIncomingOverlayLeadMs(reducedMotion = false, kind = to)
    else -> 0
}

internal fun mimirOutgoingChromeHoldMs(outgoing: MimirOverlayKind?): Int = when (outgoing) {
    null -> 0
    // The file browser closes like every other overlay, the common 130 ms hold and then a 180 ms
    // clear. Previously it had a hold of 0 and cleared glass in one frame beneath a still-covering
    // browser, so the browser dissolved over a shelf already going sharp. The common close now
    // applies; the browser's own dissolve is on the common 180 ms clock too
    // ([mimirDirectOverlayDepartMs], matched with Utility's, review 2026-10-15), which the 130 ms hold covers. Files over Utility never reaches
    // this: Utility stays the top overlay, so its chrome is presented at once.
    // The equalizer editor takes the common hold (the else below): its composition still detaches on the pop frame, but its PIXELS now
    // dissolve on [MimirPixelFadeHost] (MimirEqualizerEditorFadeHost), so the scene stays blurred behind them and clears after, "dissolve,
    // then clear" . It was 0 while the editor cut out on that frame and the glass cleared under nothing .
    // The launch card used to hold for its own 120 ms dissolve. Since motion audit batch F (2026-09-28) it
    // closes on OverlayMs like every other overlay and takes the flat hold below with them.
    // No FOLDER_BROWSER arm, and its absence is the whole lesson of 2026-08-15. It briefly returned 0
    // here, on the argument that the browser's fade and the scene's blur should share ONE clock. A pixel
    // + OCR pass over a 60fps device screen recording refuted that in one frame:
    // running both ramps together composites a half-faded SHARP manager over a half-unblurred DIFFERENT
    // shelf, and the midpoint of the transition is mush -- sharpness 10x below settled, and `tesseract`
    // could read NEITHER interface at t+367ms while it read both a frame either side. The observed
    // sequence must dissolve the browser before clearing glass.
    //
    // So the hold does have a job, and it is not the one the C2 investigation assumed: it keeps the
    // backdrop UNIFORM while the outgoing overlay dissolves, so no frame ever shows two detailed images
    // at once. It was only ever empty time for the file browser because [MimirDirectOverlayContent] was
    // missing its arm -- one omission that produced a dead 180ms, a missing exit, AND an empty animating
    // layer. Fix the omission and the hold is earned, which is why the browser now falls through to the
    // same `else` every other overlay uses.
    else -> MimirMotion.OverlayDissolveCoverMs
}

/**
 * How long an arriving overlay's pixels are held at alpha 0 while the scene softens beneath them — the
 * open-direction mirror of [mimirOutgoingChromeHoldMs].
 *
 *  [MimirOverlayKind.HOME_TOME_LIBRARY] is a surface that
 * PAINTS ITS OWN OPAQUE GROUND (`HomeTomeLibraryOverlay.kt`'s `VideoBackdrop` floor, `paintFloor`
 * defaulting true) while wearing the glass-panel open grammar on its other two channels — so the unheld
 * open ran the scene's blur ramp and the panel's alpha ramp CONCURRENTLY, and a 60fps pixel +
 * `tesseract` pass measured the same double exposure [mimirOutgoingChromeHoldMs] was built to remove on
 * the CLOSE side (sharpness 6.1-8.0x below settled; `tesseract` reading BOTH the shelf's and the
 * Library's vocabulary in one frame). SOFTEN, THEN MATERIALISE: hold the incoming layer at alpha 0
 * while the scene blur alone does the work of removing the shelf's detail, then let the panel arrive
 * over an already-soft, detail-free backdrop.
 *
 * [MimirMotion.OverlayDissolveCoverMs] (130) is the recommended hold, mirror-symmetric with the
 * reference 311 ms RETURN (`DEPART 180 · HOLD 130 concurrent · CLEAR 180`, the Folio's own return ramp
 * below) to within a millisecond: 310 = 130 + 180. That constant was MEASURED for a different physical
 * question — alpha decay on the close side, not the blur-ramp crossover on this one — so it is a
 * symmetric STARTING value here, not itself a measured one for this direction; the spec's §7.3
 * commissions the crossover measurement this function's constant may need to move to.
 *
 * **Ships with exactly ONE non-zero member.** Every other kind returns 0 and keeps precisely the
 * behaviour it has today. [mimirOutgoingChromeHoldMs]'s own kdoc records that the tempting general rule
 * — inferring this per-kind from something like "paints its own ground" — *"was WRONG twice over"*, and
 * that guessing per-kind from list membership *"is what put this defect here in the first place."*
 * Which other glass-allowlist kinds paint their own ground rather than borrowing the scene is a real
 * question and an OWED audit, one measurement per kind — not something this function guesses at by
 * widening its `when`.
 */
internal fun mimirIncomingOverlayHoldMs(incoming: MimirOverlayKind?): Int = when (incoming) {
    MimirOverlayKind.HOME_TOME_LIBRARY -> MimirMotion.OverlayDissolveCoverMs
    else -> 0
}

/**
 * The hold actually fed to the pixel-fade host for the CURRENT presentation of [kind] — layers the
 * ENTRY-PATH gate on top of [mimirIncomingOverlayHoldMs] without widening that function's own `when`
 * (it still ships with exactly one non-zero member, per-kind, unchanged).
 *
 * Entry-path distinction: `HOME_TOME_LIBRARY` presents to [MimirDirectOverlayPixelFadeHost] as
 * the identical cold `null -> HOME_TOME_LIBRARY` transition on BOTH of its entry paths — the Home
 * shelf's `X` press (CP01's measured defect) AND a Tome Folio closing back to the browser
 * (`closeTomeSurface`, `MimirAppInputDispatch.kt`, which republishes `HomeTomeLibraryState` non-null
 * before `HomeShelfCoordinator.kt`'s `closeTomeToLibrary` tears the Folio down).
 *
 * **CP02 correction, 2026-09-02**: this function's original reasoning for the FOLIO_RETURN arm was *"this ticket's evidence never
 * measured that hop"* — true when CP01 shipped, **false since CP02's BEFORE**
 * (3/3 trials). The Folio's return
 * carries the SAME double-exposure defect this function exists to remove on the open — the Library's
 * incoming pixels ran a cold 180 ms fade from alpha 0 with this arm at 0 while the outgoing Folio kept
 * painting, ≈83 ms of two complete surfaces at partial alpha (two legends on screen at once, G1).
 * HOLD = [MimirMotion.OverlayDissolveCoverMs] here too, mirroring the open.
 *
 * That correction is safe ONLY because `closeTomeToLibrary`'s four-frame count was replaced in the SAME
 * change by a release keyed on the Library's OWN arrival settling
 * (`bindings.navigation.homeTomeLibraryOpenSettled`) — adding this hold alone, on top of the old frame
 * count unchanged, would unmount the Folio ~100 ms before the Library has any pixels and reopen the
 * exposed-shelf hole `closeTomeToLibrary`'s four frames exist to close (spec §3.2). **The two changes are
 * one change; do not read this arm's value without also reading `closeTomeToLibrary`.**
 *
 * **The arm stays structurally distinct from the fallthrough on purpose**, even though both now return
 * the same constant: [homeTomeLibraryOpenOrigin] remains a real, explicit, per-arrival signal (never
 * inferred from timing or from Folio liveness) precisely so a future direction-specific tuning has
 * somewhere to land without re-deriving the discrimination from scratch. [homeTomeLibraryOpenOrigin] is
 * [HomeTomeLibraryState.openOrigin], set explicitly at both of that state's construction sites.
 */
internal fun mimirIncomingOverlayHoldMsForPresentation(
    kind: MimirOverlayKind?,
    homeTomeLibraryOpenOrigin: HomeTomeLibraryOpenOrigin?,
): Int {
    if (kind == MimirOverlayKind.HOME_TOME_LIBRARY &&
        homeTomeLibraryOpenOrigin == HomeTomeLibraryOpenOrigin.FOLIO_RETURN
    ) {
        return MimirMotion.OverlayDissolveCoverMs
    }
    return mimirIncomingOverlayHoldMs(kind)
}

internal fun mimirDirectOverlayUsesPixelFade(kind: MimirOverlayKind?): Boolean = when (kind) {
    null,
    MimirOverlayKind.RETURN_COVER,
    MimirOverlayKind.HOME_QUICK_LAUNCH_PICKER,
    MimirOverlayKind.HOME_QUICK_LAUNCH,
    MimirOverlayKind.HOME_TILE_MENU,
    MimirOverlayKind.HOME_LAUNCH_CARD,
    // The equalizer editor arrives on the glass-panel tier (its own scrim and lift) and leaves on the same frame: no pixel fade.
    MimirOverlayKind.EQUALIZER_EDITOR,
    -> false
    MimirOverlayKind.COMPANION_PERMISSION,
    MimirOverlayKind.MIGRATION,
    MimirOverlayKind.ARTWORK_GET,
    MimirOverlayKind.ARTWORK_FETCH,
    MimirOverlayKind.ARTWORK_CONNECT,
    MimirOverlayKind.PROFILE_NAME,
    MimirOverlayKind.STEAMGRIDDB_KEY,
    MimirOverlayKind.RETROACHIEVEMENTS_KEY,
    MimirOverlayKind.GAME_TITLE_EDIT,
    MimirOverlayKind.CONTROLLER_MAPPING,
    MimirOverlayKind.CONTROLLER_SHORTCUT_CAPTURE,
    MimirOverlayKind.GAMELIST_ACHIEVEMENTS,
    MimirOverlayKind.LIBRARY_PLATFORM_TILE_MENU,
    MimirOverlayKind.HOME_OPEN_WITH,
    MimirOverlayKind.HOME_LAUNCH_CHOICE,
    MimirOverlayKind.PLATFORM_PICKER,
    MimirOverlayKind.BACKDROP_PICKER,
    MimirOverlayKind.THEME_PICKER,
    MimirOverlayKind.BACKGROUND_PICKER,
    MimirOverlayKind.MBUTTON_PICKER,
    MimirOverlayKind.ACCENT_PICKER,
    MimirOverlayKind.FOCUS_RING_DIAL,
    MimirOverlayKind.MUSIC_SOURCE_PICKER,
    MimirOverlayKind.MUSIC_VOLUME_DIAL,
    MimirOverlayKind.BRIGHTNESS,
    MimirOverlayKind.HOME_TOME_LIBRARY,
    // True again, and TRUE now rather than aspirational: MimirDirectOverlayContent has a FOLDER_BROWSER
    // arm, so this host really does draw the browser and really can fade its pixels out after the
    // composition is gone. Between 2026-08-15's C2 fix and this change it was deliberately false, because
    // membership without an arm is worse than absence -- it bought an empty layer and a 180ms wait.
    MimirOverlayKind.FOLDER_BROWSER,
    -> true
}

internal data class MimirDirectOverlayTarget(
    val kind: MimirOverlayKind,
    /** State-class identity only: progress/text edits inside one page never restart its fade. */
    val phaseKey: String? = null,
)

/**
 * Preserve the alpha already on screen when a rapid reversal reuses that layer's pixels.
 *
 * @param replacedKind what the host presented before [target] (retained kind memory), for the two hand-overs between an
 *   appearance-image picker and the image browser that start at 1: the browser opening over a picker paints at 1 beneath
 *   the picker's held pixels, whose dissolve is its arrival ([mimirOutgoingLayerHoldMs]); a picker the browser returns
 *   to is the page returned to, live at 1 under the browser from its first frame, so the browser dissolves over it on
 *   the return clock and the Settings pane beneath never shows.
 */
internal fun mimirIncomingLayerStartAlpha(
    target: MimirDirectOverlayTarget?,
    cachedLayerTarget: MimirDirectOverlayTarget?,
    cachedLayerAlpha: Float,
    hasOutgoing: Boolean,
    replacedKind: MimirOverlayKind? = null,
): Float = when {
    target == null || !hasOutgoing -> 1f
    // Utility's scrim, panel alpha and lift already arrive on one glass ramp (`rememberMimirGlassProgress`); this
    // host ramping it too multiplied the two into a squared curve (motion audit batch A). Pinned like the Folio.
    target.kind in MimirOverlaysOwningTheirArrival -> 1f
    target.kind == MimirOverlayKind.FOLDER_BROWSER && mimirOverlayIsAppearanceImagePicker(replacedKind) -> 1f
    replacedKind == MimirOverlayKind.FOLDER_BROWSER && mimirOverlayIsAppearanceImagePicker(target.kind) -> 1f
    cachedLayerTarget == target -> cachedLayerAlpha.coerceIn(0f, 1f)
    else -> 0f
}

/** Direct overlays whose arrival is their own glass ramp, so the pixel-fade host only fades them out. */
internal val MimirOverlaysOwningTheirArrival: Set<MimirOverlayKind> = setOf(MimirOverlayKind.BRIGHTNESS)

/**
 * How long an overlay's panel takes to arrive: the page tier's [MimirMotion.ShineMs], from the [MimirMotion.OverlayDissolveCoverMs] lead the host adds.
 * The one exception is the Tome Library arriving on a Folio RETURN, which keeps today's [MimirMotion.OverlayMs]: a
 * return does not get slower, and CP02's Folio teardown (`HomeTomeLibraryReturnReleaseEffect`) waits on this arrival
 * settling, so 670 ms here would hold the Folio's pixels over the Library. Reduced motion snaps.
 */
internal fun mimirIncomingOverlayArriveMs(
    kind: MimirOverlayKind?,
    homeTomeLibraryOpenOrigin: HomeTomeLibraryOpenOrigin?,
    reducedMotion: Boolean,
): Int = when {
    reducedMotion -> 0
    kind == MimirOverlayKind.HOME_TOME_LIBRARY &&
        homeTomeLibraryOpenOrigin == HomeTomeLibraryOpenOrigin.FOLIO_RETURN -> MimirMotion.OverlayMs
    // Audit 2026-10-15, Thor check 7: the Theme gallery and the two appearance-image pickers are Settings sub-pages under APPEARANCE, so they take the sub-pages'
    // swap clock, `mimirCrossSwapTransform`'s one [MimirCrossSwapMs] with no lead, both ways (they already leave on OverlayMs). On the page tier's 130 + 540 ms the sharp Settings pane stood under the arriving page for about half a second.
    kind == MimirOverlayKind.THEME_PICKER || mimirOverlayIsAppearanceImagePicker(kind) -> MimirCrossSwapMs
    else -> MimirMotion.ShineMs
}

/**
 * The lead before that arrival, the page tier's: the scene softens alone for 130 ms, then the panel materialises. The
 * host applies it to a COLD arrival only and takes the larger of it and the CP01 hold, never their sum.
 *
 * A Folio RETURN takes no lead, like every other return since 3e1d73b7e: the Tome Library is where the player is going
 * back to, so it is at rest at once while the Folio dissolves over it, instead of the Folio's pixels standing 130 ms
 * over an empty scene.
 */
internal fun mimirIncomingOverlayLeadMs(
    reducedMotion: Boolean,
    kind: MimirOverlayKind? = null,
    homeTomeLibraryOpenOrigin: HomeTomeLibraryOpenOrigin? = null,
): Int = when {
    reducedMotion -> 0
    kind == MimirOverlayKind.HOME_TOME_LIBRARY &&
        homeTomeLibraryOpenOrigin == HomeTomeLibraryOpenOrigin.FOLIO_RETURN -> 0
    // The Appearance pages' cross-swap has no lead (see [mimirIncomingOverlayArriveMs]); none has scene glass to soften first either (Theme; the image pickers draw on the same opaque backing).
    kind == MimirOverlayKind.THEME_PICKER || mimirOverlayIsAppearanceImagePicker(kind) -> 0
    else -> MimirMotion.OverlayDissolveCoverMs
}

/**
 * How far one frame may move an arrival's clock: [MimirArrivalFrameCapMs] for the Appearance pages on the swap clock, 0 (the ambient clock, unguarded) for every other arrival.
 *
 * Thor drive 3 (combined45): with no lead and one 180 ms fade the pages opened in 30 ms, in one frame and in 50 ms, because the first frames of a page are long ones (the Theme gallery's first draw
 * records for about 213 ms, the Background picker's for about 85 ms, and a cold process stalls again after it) and a long frame moves the fade's clock by its whole length. Capped, a long frame moves it by a
 * frame and a bit, so the 180 ms fade is drawn across the frames it has (`MimirStallGuardedClock.kt`). The page tier's arrivals keep the clock they have: their 130 ms lead and 540 ms ramp absorb a stall.
 */
internal fun mimirIncomingOverlayStallCapMs(kind: MimirOverlayKind?, reducedMotion: Boolean): Int = when {
    reducedMotion -> 0
    kind == MimirOverlayKind.THEME_PICKER || mimirOverlayIsAppearanceImagePicker(kind) -> MimirArrivalFrameCapMs
    else -> 0
}

/**
 * One step of an open panel handing to the next : the picker to its key entry, one
 * artwork-connect phase to the next, one game to the next inside the launch card. That is the swap tier: the old step
 * leaves on [MimirMotion.OverlayMs] and the new one arrives on OverlayMs from the 130 ms lead, 310 ms in all. The full
 * [MimirMotion.ShineMs] arrival is only for an overlay opening over the scene. Reduced motion snaps.
 */
internal fun mimirOverlayStepArriveMs(reducedMotion: Boolean): Int = if (reducedMotion) 0 else MimirMotion.OverlayMs

/**
 * The arriving layer's clock: [stepArriveMs] when the departing layer showed a step of its own ([handsOver]), else
 * [arriveMs]. A host with no step clock (null) keeps [arriveMs] for every arrival.
 */
internal fun mimirOverlayArrivalClock(handsOver: Boolean, arriveMs: Int, stepArriveMs: Int?): Int =
    if (handsOver && stepArriveMs != null) stepArriveMs else arriveMs

/**
 * Whether a Tome Folio presence change is a RETURN — the only transition that arms the shelf's held cover.
 *
 * The arguments are the Folio's presence (`homeShelf.tomeFolioState != null`) before and after the change,
 * and the only armable direction is present → absent. An OPEN is not a return and keeps
 * `rememberMimirGlassProgress`'s arrival ramp; the Folio therefore uses a DIFFERENT primitive on the way
 * out than on the way in, and that asymmetry is the standing law rather than a wart
 * (`MimirGlassTransition.kt`'s two kdocs).
 *
 * **Deliberately unconditional in that direction**.
 * `folioOpenedUnderCover` below snaps the OPEN to 1f when the Folio opens under the Tome Library's held
 * pixels, and the obvious next move is a symmetric return-side special case. There is no return case to
 * special-case: a Folio return goes to the Home shelf, never under a cover. Named here so the next reader
 * meets the decision instead of the omission.
 */
internal fun homeTomeFolioReturnArmed(
    folioPresentBefore: Boolean,
    folioPresentAfter: Boolean,
): Boolean = folioPresentBefore && !folioPresentAfter

/**
 * Whether a departure additionally gets the HELD COVER, on top of the ring extinction every departure gets.
 *
 * `rememberMimirGlassReturnProgress` is born at 1f **by construction** — its own kdoc states the premise:
 * the destination is *"born already soft, under an opaque outgoing layer so there is nothing to cut."*
 * Back out of a tome DURING its 180 ms materialisation and that premise is false: the scene is at, say,
 * 0.3, and arming would step the blur and the scrim UP to full on the departing frame and record the panel
 * at an alpha it never actually reached. A flash-in, then a dissolve — a worse artefact than the one the
 * cover exists to remove, and reachable by any impatient press.
 *
 * Unarmed, the open ramp simply unwinds from wherever it got to and the recorded panel dissolves from the
 * same value: continuous, no hold, and nothing to cover because the Folio never covered anything.
 *
 * [openProgressAtDeparture] is read ONCE per departure, never per frame — the same shape as
 * `LibraryScreen`'s own B→B race guard, which reads `sceneGlassProgress()` once inside its per-transition
 * `remember`.
 */
internal fun homeTomeFolioReturnCovered(
    folioDeparted: Boolean,
    openProgressAtDeparture: Float,
): Boolean = folioDeparted && openProgressAtDeparture >= 1f

/**
 * One Folio departure, decided ONCE (RC-25 / H-03c): whether the held cover arms, and what the panel's single recorded frame is
 * painted at. The cover arms on the SCENE's glass alone ([glassAtDeparture]: it is at 1.0 from 180 ms, so arming steps nothing), which
 * a back-out in the 180 to 670 ms the panel is still arriving over used to miss; the panel's arrival is a separate clock, and a panel
 * that had not finished keeps the alpha it had ([panelAlpha]) instead of riding the return ramp that is born at 1f (recorded at full
 * alpha and lift: a flash-in, then a dissolve).
 */
internal class HomeTomeFolioDeparture(val armed: Boolean, panelAtDeparture: Float) {
    val panelFullyArrived: Boolean = panelAtDeparture >= 1f
    private val held = panelAtDeparture.coerceIn(0f, 1f)
    val panelAlpha: () -> Float = { held }
}

internal fun homeTomeFolioDeparture(
    folioDeparted: Boolean,
    glassAtDeparture: Float,
    panelAtDeparture: Float,
): HomeTomeFolioDeparture = HomeTomeFolioDeparture(
    armed = homeTomeFolioReturnCovered(folioDeparted = folioDeparted, openProgressAtDeparture = glassAtDeparture),
    panelAtDeparture = panelAtDeparture,
)

private fun mimirDirectOverlayTarget(
    bindings: MimirAppBindings,
    kind: MimirOverlayKind,
): MimirDirectOverlayTarget {
    val phase = when (kind) {
        // ARTWORK_GET and ARTWORK_FETCH are deliberately ABSENT here (2026-08-19):
        // a phase change inside either overlay is no longer a host retarget.
        // The host crossfade now fires only on this KIND opening/closing; the phase-to-phase motion
        // (fade + resize) is owned entirely by each overlay's own in-panel AnimatedContent
        // (ArtworkFetchOverlay.kt, GetArtOverlay.kt). Do not re-add these arms -- that regresses the
        // grammar back to "one surface leaving, another arriving" on every progress result.
        //
        // MIGRATION and GAMELIST_ACHIEVEMENTS are ALSO deliberately absent here now
        //: both surfaces
        // now own their own phase-to-phase motion via an in-panel AnimatedContent added in this SAME
        // commit -- MigrationImportOverlay (MigrationImport.kt) and MimirAchievementCard
        // (core-ui/.../MimirAchievementCard.kt). Do not re-add either arm.
        //
        // LIBRARY_PLATFORM_TILE_MENU's absence is a DEFECT FIX, not a conversion, and the reasoning is
        // different: HomeTileMenuOverlay.kt already had its own keyed in-panel AnimatedContent (Move 2
        // was already built, at :900-906) before this arm was ever added. This arm's presence meant every
        // phase change there drained a frozen host-crossfade snapshot for 180ms WHILE that in-panel
        // transition ran the SAME boundary -- the new page arrived on the product of two ramps
        // (approximately t^2), not MimirMotion.Easing. The proof this was drift, not design: this same
        // composable is reached from HOME_TILE_MENU too, which was already `false` in
        // mimirDirectOverlayUsesPixelFade below -- one composable animating two different ways depending
        // on which tile the user pressed. Do not re-add this arm either.
        MimirOverlayKind.ARTWORK_CONNECT -> bindings.artwork.state.connectUi
        MimirOverlayKind.RETROACHIEVEMENTS_KEY -> bindings.settings.retroAchievementsConnectUi
        else -> null
    }
    return MimirDirectOverlayTarget(kind, phase?.javaClass?.name)
}

/** The hero keeps the remembered Home row while focus is temporarily in the dock. */
internal fun homeContentFocusKey(state: FocusState, rememberedHome: FocusKey?): FocusKey? =
    if (state.scope == HomeShelfScope) state.focused else rememberedHome

/**
 * Mirrors the Folio's painted-seat law for the Home backdrop. FocusAuthority wins once its Folio
 * scope is live; the reducer index is only the cold-composition fallback.
 */
internal fun homeTomeFolioBackdropMember(
    folio: HomeTomeFolioUiState?,
    focusState: FocusState,
): HomeTomeMember? {
    folio ?: return null
    val authoritativeIndex = if (focusState.scope == HomeTomeFolioScope && focusState.focused != null) {
        folio.memberSlots.indices.firstOrNull { index ->
            val member = folio.memberSlots[index]
            (member?.focusKey ?: homeTomeSeatFocusKey(folio.tome.tomeId, index)) == focusState.focused
        }
    } else {
        null
    }
    return folio.memberSlots.getOrNull(authoritativeIndex ?: folio.focusedIndex)
}

internal fun isRootSurfaceHandoff(
    from: MimirDestination,
    to: MimirDestination,
): Boolean = from != to

internal data class RootShelfLayerFrame(
    val primaryLayerIndex: Int,
    val outgoingLayerIndex: Int?,
)

internal fun nextRootShelfLayerFrame(
    currentPrimaryLayerIndex: Int,
    retainOutgoing: Boolean,
): RootShelfLayerFrame {
    require(currentPrimaryLayerIndex == 0 || currentPrimaryLayerIndex == 1)
    return if (retainOutgoing) {
        RootShelfLayerFrame(
            primaryLayerIndex = 1 - currentPrimaryLayerIndex,
            outgoingLayerIndex = currentPrimaryLayerIndex,
        )
    } else {
        RootShelfLayerFrame(
            primaryLayerIndex = currentPrimaryLayerIndex,
            outgoingLayerIndex = null,
        )
    }
}

@Composable
internal fun MimirAppHost(
    bindings: MimirAppBindings,
    interactionMotionGate: InteractionMotionGate,
) {
    val core = bindings.core
    val primarySurfaceAvailable by CompanionProcessRuntime.services(core.context)
        .mButtonTopology.primarySurfaceAvailable.collectAsState()
    val currentBindings by rememberUpdatedState(bindings)
    val activeOverlay = bindings.overlays.top
    // What the scene's glass follows. It is the top overlay, except beneath the Quick Launch panel: a folder browser or
    // media flow the panel was opened over is still the surface on screen, and the glass behind it must not clear the
    // moment the panel arrives (MimirOverlayStack.presented). Input and Back still follow [activeOverlay].
    val chromeOverlay = bindings.overlays.presented
    // CP01 (spec §5.2): reduced motion for the Tome Library's open hop is ONE decision, taken once here
    // and THREADED to every beat that needs it -- the scene-blur override a few lines down, and (via
    // MimirAppOverlayHost's parameter, not a second mimirReducedMotion() call) MimirDirectOverlayPixelFade
    // Host's hold/fade override. Deliberately not re-derived at each site the way `homeReducedMotion` /
    // HomeTomeLibraryOverlayHost's `reducedMotion` are elsewhere in this file: the spec requires a
    // decision that "reaches beat 1 but not beat 2" be UNREPRESENTABLE, which a second independent read
    // cannot guarantee the way one value passed as a parameter can.
    val reducedMotion = mimirReducedMotion()
    val controllerMapping by ControllerMappingStore.snapshot.collectAsState()
    val controllerHints = remember(controllerMapping.applied, controllerMapping.appliedRaw) {
        controllerHintLabels(controllerMapping.applied, controllerMapping.appliedRaw)
    }
    // Input follows the logical top immediately. Scene chrome survives through the outgoing pixel fade,
    // so blur and semantics never snap off behind a dissolving glass panel.
    var presentedOverlayChrome by remember { mutableStateOf(chromeOverlay) }
    // What the scene's glass follows, ON THE COMPOSITION THAT CHANGES IT (RC-09 / H-04a): the effect below writes
    // [presentedOverlayChrome] one composition after [chromeOverlay] moves, so the glass opened two or three frames after the card and
    // the ring had gone (the first of the launch card's "three stages"). Opening is therefore read straight from the stack; only the
    // close still waits, on [presentedOverlayChrome], for the hold.
    val glassChrome = chromeOverlay ?: presentedOverlayChrome
    LaunchedEffect(chromeOverlay, bindings.homeLaunchCard.closing) {
        if (chromeOverlay != null) {
            presentedOverlayChrome = chromeOverlay
        } else if (presentedOverlayChrome == MimirOverlayKind.HOME_LAUNCH_CARD &&
            !bindings.homeLaunchCard.closing && !bindings.homeLaunchCard.suspendedForSettings
        ) {
            // A card dropped with no dissolve of its own (the launch handed on, a new card replacing it) leaves nothing to cover. Not a
            // card held for Settings (RC-25 / H-04d): START nulls its stack entry with `closing` still false, but its pixels dissolve like a
            // closing card's and the glass holds behind them like theirs, so Settings does not arrive under a clearing blur with the card
            // dissolving over it.
            presentedOverlayChrome = null
        } else {
            val outgoingDuration = mimirOutgoingChromeHoldMs(presentedOverlayChrome)
            // On the animation clock, so the hold scales with the dissolve it covers (alpha audit N3).
            if (outgoingDuration > 0) mimirHold(outgoingDuration)
            presentedOverlayChrome = null
        }
    }
    // Screen 2 browses the library itself now (see [CompanionLibraryRoute]).
    // Its Presentation owns a separate composition root, so it cannot read the CompositionLocal provided
    // two lines below; this publishes the SAME instance through the one process-scoped handle that joins
    // the two windows. `SideEffect` rather than `LaunchedEffect`: it must be in place before the companion
    // composes, and there is nothing to suspend on.
    SideEffect { CompanionLibraryRoute.publishRepository(core.repository) }
    MimirTheme(themeId = core.themeId) {
        MimirCoordinatorAmbientMotionProvider(interactionMotionGate) {
            CompositionLocalProvider(
                LocalFocusAuthority provides core.focusAuthority,
                LocalLibraryRepository provides core.repository,
                LocalMimirControllerNavigationActive provides core.controllerNavigationActive,
                LocalMimirControllerHintLabels provides controllerHints,
                LocalMimirGlanceClearance provides remember { MimirGlanceClearance() },
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(activeOverlay) {
                            awaitPointerEventScope {
                                var gestureActive = false
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val firstDown = !gestureActive && event.changes.any { change ->
                                        !change.previousPressed && change.pressed
                                    }
                                    if (firstDown) {
                                        currentBindings.homeLaunchCard.cancelOutgoingDissolveOnInteraction()
                                        if (currentBindings.navigation.destination == MimirDestination.HOME) {
                                            currentBindings.homeShelf.cancelTomeReturn()
                                        }
                                        if (currentBindings.navigation.destination == MimirDestination.LIBRARY) {
                                            currentBindings.library.noteTransitionInteraction()
                                        }
                                        gestureActive = true
                                    }
                                    if (event.changes.none { it.pressed }) gestureActive = false
                                }
                            }
                        },
                    color = MimirColors.SurfaceBase,
                ) {
                    val destinationModifier = if (glassChrome == null) {
                        Modifier
                    } else {
                        Modifier.clearAndSetSemantics { }
                    }
                    // UXP-03/UXP-04 : the glass modals blur the REAL scene behind
                    // them. The blur has to live here, on the destination, because blurring the panel
                    // itself would blur its own text — scene and overlays are siblings in this Surface,
                    // which is exactly what makes that possible. A no-op on API 30, where the platform
                    // cannot blur; the panel's scrim deepens there instead (see MimirGlass).
                    //
                    // , and *"the same transition for the
                    // return and open path"*. This one line is where BOTH halves of that ruling land, for
                    // every glass overlay in the product at once:
                    //
                    //  - OPEN: the blur ramps 0 -> 18dp on MimirMotion.OverlayMs instead of the `if` that
                    //    snapped it. Same curve the panel rides, one clock.
                    //  - RETURN: this Box is ALWAYS composed, so when an overlay pops, `visible` goes false
                    //    here and the scene ramps its own blur back off. That is the return transition, and
                    //    it obeys the house rule that departures are never animated: nothing keeps the dead
                    //    overlay composed to fade it: **the parent is what arrives.** No caller has to
                    //    implement "close", and none may -- an overlay that animated itself away would be a
                    //    popped scope still taking pointer input.
                    //
                    // `identity` is deliberately constant. A child overlay opening over its parent
                    // (GAME_TITLE_EDIT over HOME_LAUNCH_CARD, below) changes which KIND is on top while the
                    // scene stays blurred throughout; keying on the kind would rebuild the Animatable and
                    // replay the ramp, flashing the scene sharp at exactly the moment the comment below
                    // says it must not.
                    val glassProgress = rememberMimirGlassProgress(
                        visible = mimirOverlayUsesGlass(glassChrome),
                        reducedMotion = reducedMotion,
                    )
                    // The last overlay the glass was up for, so the Tome Library's scrim can ride the clear out below. Plain memory,
                    // written while an overlay is presented.
                    val lastGlassChrome = remember { arrayOfNulls<MimirOverlayKind>(1) }
                    if (glassChrome != null) lastGlassChrome[0] = glassChrome
                    val sceneGlassClearing by remember { derivedStateOf { glassProgress() > 0f } }
                    // CP01 beat 1, reduced motion only: the Tome Library's scene blur is a settled STATE
                    // (full 18dp, no ramp) from frame 1 rather than the ordinary 180ms tween above, for
                    // exactly as long as it is genuinely the ACTIVE overlay. Gated on `activeOverlay`, not
                    // `presentedOverlayChrome` -- the latter stays == HOME_TOME_LIBRARY through the whole
                    // CLOSE-side outgoing chrome hold too, and this override must turn OFF before that
                    // window starts or it would change the close path's blur, which this ticket does not
                    // touch (it has its own ruled hold). `activeOverlay` flips away the instant CANCEL is
                    // dispatched, well before `presentedOverlayChrome` catches up, so gating here is what
                    // keeps the override scoped to the open/settled window only.
                    val homeTomeLibraryReducedMotionSettle = reducedMotion &&
                        activeOverlay == MimirOverlayKind.HOME_TOME_LIBRARY
                    val arrivalGlassProgress: () -> Float = if (homeTomeLibraryReducedMotionSettle) {
                        { 1f }
                    } else {
                        glassProgress
                    }
                    // **The blur is RELEASED once the Tome Library's own opaque ground is up.**
                    //
                    // Measured on a Thor, the Tome Library sitting completely still, `dumpsys gfxinfo` over 10 s:
                    //
                    // | idle surface | jank | p50 | RenderThread `issue` |
                    // |---|---|---|---|
                    // | Home shelf | 0.00% | 5 ms | 1.5 ms |
                    // | Folio | 0.00% | 5 ms | — |
                    // | **Tome Library** | **56-64%** | **14-15 ms** | **8.5 ms** |
                    //
                    // That is the whole of what "the return is bad" measures: the return LANDS here, so
                    // a capture of it is really a capture of this. The UI thread is not involved at all
                    // (0.3 ms across input+anim+layout+record); it is `IssueDrawCommandsStart ->
                    // SwapBuffers`, the RenderThread re-rendering an 18 dp full-screen [BlurEffect].
                    //
                    // **It re-renders because it cannot be cached: it is a blur of a layer that keeps
                    // invalidating.** The same blur under [MimirOverlayKind.HOME_LAUNCH_CARD] and
                    // [MimirOverlayKind.FOLDER_BROWSER], over the same Home shelf, measured 0.00% and
                    // p50 5 ms -- there the scene beneath is static, so the layer is reused. Under the
                    // Tome Library the scene is being handed a different backdrop art
                    // (`onBackdropArt`), and something in it keeps posting frames. **Finding that
                    // invalidator is OWED** -- Home idles at ~18 fps of redraws with nothing on screen
                    // moving, which is battery this app should not be spending. Removing the blur does
                    // not fix that; it stops the blur MULTIPLYING it by five.
                    //
                    // **Releasing it is invisible, and that was measured rather than argued.**
                    // `VideoBackdrop`'s floor -- `palette.signatureBottom`, opaque, unconditional --
                    // sits above this layer and fully hides the Home shelf. A build with the blur
                    // forced off was compared against ship,
                    // same tome focused, on a noise floor of EXACTLY ZERO (two ship captures across a
                    // reinstall and cold start are byte-identical): **0.223% of pixels differ, mean
                    // delta 1.59/255, and every one of them inside one 97x98 box** -- a sampling
                    // variation on a cartridge thumbnail, indistinguishable at 3x magnification. Blur
                    // bleed would be broad and full-screen. It is not there because the floor is opaque.
                    //
                    // **Only once LANDED.** Through the arrival the ground is still translucent and the
                    // blur is the retained arrival beat, so it runs exactly as before;
                    // `homeTomeLibraryOpenSettled` is the latch that says the arrival finished. And
                    // because that latch is deliberately NOT reset on close (see its kdoc -- resetting
                    // it would reintroduce a flash on the return), the `activeOverlay` half of
                    // [mimirSceneGlassSuppressed] is what restores the blur for the close dissolve:
                    // `activeOverlay` flips away at CANCEL, well before `presentedOverlayChrome` does,
                    // which is the same edge `homeTomeLibraryReducedMotionSettle` above relies on.
                    //
                    // Read INSIDE the lambda, not beside it: `effectiveGlassProgress` is consumed in a
                    // `graphicsLayer` block, so the latch costs a draw invalidation rather than a
                    // recomposition of this host.
                    val navigation = bindings.navigation
                    // Read on the ACTIVATION edge, before this arrival's own false-edge bumps it.
                    // Without this fence the folio return's stale settle latch suppresses the blur
                    // mid-crossfade -- see mimirSceneGlassSuppressed's kdoc, including why the capture
                    // that appeared to measure it does NOT (its own noise floor is larger).
                    val arrivalEpochAtActivation = remember(activeOverlay) {
                        navigation.homeTomeLibraryOpenArrivalEpoch
                    }
                    val effectiveGlassProgress: () -> Float = {
                        if (
                            mimirSceneGlassSuppressed(
                                activeOverlay = activeOverlay,
                                tomeLibraryOpenSettled = navigation.homeTomeLibraryOpenSettled,
                                arrivalEpoch = navigation.homeTomeLibraryOpenArrivalEpoch,
                                epochAtActivation = arrivalEpochAtActivation,
                            )
                        ) {
                            0f
                        } else {
                            arrivalGlassProgress()
                        }
                    }
                    MimirPrimaryViewportProfile(
                        mode = if (primarySurfaceAvailable) {
                            MimirViewportSettings.mode
                        } else {
                            MimirViewportMode.AUTOMATIC
                        },
                    ) {
                    // RG Rotate observation: the status bar sat over the LIBRARY/Home title. On a
                    // compact profile the destination steps below it; while the bar is hidden (immersive) the inset
                    // is zero, so landscape and dual-screen are unchanged.
                    val compactProfile = LocalMimirViewportProfile.current.isCompact
                    Box(
                        Modifier
                            .fillMaxSize()
                            .then(if (compactProfile) Modifier.windowInsetsPadding(WindowInsets.statusBars) else Modifier)
                            .mimirGlassBackdropProgress(effectiveGlassProgress)
                            .then(destinationModifier),
                    ) {
                        MimirGlassWarmup()
                        MimirColdStartTrace.markOnce("span host chrome done")
                        MimirDestinationHost(bindings)
                        MimirColdStartTrace.markOnce("span destination done")
                        // **Beat 1 DARKENS as well as softens — the follow-up CP01 §2.1 named and
                        // deferred, now that its precondition is met.**
                        //
                        // Darkening was deferred until a capture demonstrated excess transition brightness.
                        // With the outgoing hero already banished
                        // from the stage, the middle of the hop still measured **+10.2 luma (+28%)**
                        // above its own settled value on the Thor, and the frame at t=+100 ms shows the
                        // source is the Home shelf's own game-cover tiles, blurred but not darkened.
                        // Blur removes DETAIL; it does not remove BRIGHTNESS, and four saturated box
                        // arts smeared across the frame are still four bright things.
                        //
                        // Same progress as the blur it rides, so this is not a second clock — it is the
                        // second channel of one beat, exactly as the neighbouring Folio hop already does
                        // it (`mimirGlassBackdropProgress(folioGlass)` beside `MimirGlassScrim(folioGlass)`).
                        //
                        // **Scoped to the Tome Library, deliberately.** Every glass overlay shares this
                        // Box, and most of them are not opaque; darkening the scene under a translucent
                        // panel would change its settled appearance. The Library paints its own opaque ground
                        // (CP01 §1c), so under it this scrim is invisible once landed and exists only
                        // for the transition. The scoping mirrors `homeTomeLibraryReducedMotionSettle`
                        // directly above rather than inventing a new discriminator.
                        //
                        // **Mounted for the whole presentation, not for the active overlay (RC-25 / H-10).** Gated on `activeOverlay` it unmounted
                        // at CANCEL, while the library's pixels still stood for the 130 ms hold and the blur snapped back to full beside it:
                        // the library dissolved over a blurred but UNDARKENED shelf (+10 to +28% luma). It now stays for the hold, and for the
                        // clear after it (`sceneGlassClearing`, a flag that flips once, so the clear is not a recomposition per frame): a
                        // `drawBehind` at progress 0 paints nothing, and unmounting it at full alpha the frame the clear began would have been
                        // the same cut one beat later.
                        if (glassChrome == MimirOverlayKind.HOME_TOME_LIBRARY ||
                            (lastGlassChrome[0] == MimirOverlayKind.HOME_TOME_LIBRARY && sceneGlassClearing)
                        ) {
                            MimirGlassScrim(progress = effectiveGlassProgress)
                        }
                    }
                    // SD02: quiet storage banner above the destination, below modals.
                    StorageNoticeHost()
                    MimirAppOverlayHost(bindings, activeOverlay, reducedMotion)
                    MimirColdStartTrace.markOnce("span overlays done")
                    // D12: Mimir's own message for Main (what a toast used to be): the one rail every message on this display goes through, in the modal band above the overlays. above the quick-rail dock on Home and the Library shelf, not on it.
                    MimirActivityMessageHost(clearsDock = mainMessageRailClearsDock(bindings.navigation.destination, bindings.core.libraryViewModel.openPlatform.collectAsState().value))
                    // The revoked-SAF-grant prompt, in the modal band ABOVE the overlay host and BELOW
                    // the launch intro — the same seat the storage banner two lines up occupies for its
                    // own quieter class of storage news. `visible` is gated on there being no active
                    // overlay so this can never push its focus scope over a live picker or launch card;
                    // when nothing is lost it composes nothing at all (see StorageGrantPromptHost).
                    StorageGrantPromptHost(
                        authority = bindings.core.focusAuthority,
                        visible = activeOverlay == null,
                    )
                            GameOpeningIntroOverlay()
                        }
                }
            }
        }
    }
}

/**
 * Which overlays wear the Mimir liquid glass and therefore blur the scene behind them (UXP-03/UXP-04).
 *
 * Deliberately an allowlist rather than "any overlay": the return cover is already opaque by design, and
 * blurring behind it would waste a full-screen render pass on a scene nobody can see.
 *
 * [MimirOverlayKind.GAME_TITLE_EDIT] joined this list to fix a layering defect reported on device
 * (2026-08-11): the Home shelf appeared behind title edit instead of only the launch card. `GAME_TITLE_EDIT` is a CHILD overlay of `HOME_LAUNCH_CARD` — opened
 * while the launch card's own session stays alive underneath (`HomeLaunchCardOverlayHost`'s `visible` is
 * never tied to which overlay sits on top) — so the moment it became the active/top overlay,
 * `presentedOverlayChrome` stopped being `HOME_LAUNCH_CARD` and this allowlist stopped matching, and the
 * SCENE blur that was keeping the destination soft while the card alone was open switched OFF: the Home
 * shelf (or, on the Library entry point, the gamelist behind the full-screen game-detail card) went from
 * blurred to fully sharp at exactly the moment `GameTitleEditOverlay`'s own near-opaque scrim was the only
 * thing standing between the player and it. Re-including `GAME_TITLE_EDIT` here keeps the destination
 * blurred throughout its parent's whole open/edit lifetime, using the SAME existing `mimirGlassBackdrop`
 * mechanism every other child overlay of a glass surface already relies on — no new blur code.
 *
 * The two entry points differ in WHAT this blurs, because they differ structurally: on Home, the launch
 * card is a separate sibling host (`HomeLaunchCardOverlayHost`) composed ABOVE the destination, so this
 * blurs only the raw shelf and leaves the launch card itself crisp; on Library, the game-detail launch
 * card is composed INSIDE the destination subtree itself (`GameDetailScreen`, full-screen, "renders over
 * the shelf/gamelist" per `LibraryScreen.kt`), so this blurs the destination's own already-frontmost
 * detail card along with it — acceptable, even fitting, since nobody needs to read that card's text while
 * a full-screen rename keyboard sits on top of it.
 *
 * [MimirOverlayKind.ARTWORK_CONNECT] and [MimirOverlayKind.RETROACHIEVEMENTS_KEY] joined this list to fix
 * a visual-continuity defect reported on hardware (2026-08-13):
 * abrupt arrival and loss of backdrop glass during credential entry. Before this fix, neither kind was here, so `mimirGlassBackdrop` was `false` for every single
 * frame of the whole credential flow — the destination was never blurred at all, from opening the
 * username step through Done/Failed. Both kinds' overlay-stack membership (`MimirOverlayCoordinator.kt`:
 * `artworkConnect = artwork.state.connectUi != null`, `retroAchievementsKey = retroAchievementsConnect
 * .isOpen` where `isOpen = state != null`) is a coarse non-null PRESENCE check that cannot distinguish
 * `EnterId`/`EnterUsername` from `EnterPassword`/`EnterKey` — so once added here, the destination blur is
 * driven by `presentedOverlayChrome` (this file, gated on overlay KIND, not on which credential step is
 * showing) and is therefore continuous by construction across the whole username -> password sequence: it
 * cannot flip off mid-sequence because nothing about advancing from username to password ever changes
 * which overlay kind is on top. No new blur code, no new duration — the SAME existing `mimirGlassBackdrop`
 * mechanism every other allowlisted overlay already uses.
 *
 * This also repairs the "lands abruptly" half of the report, without adding a second animation. Both
 * kinds already retarget `MimirDirectOverlayPixelFadeHost`'s cross-fade on every credential-step change
 * (`mimirDirectOverlayTarget`'s `phaseKey` is `connectUi`/`retroAchievementsConnectUi`'s live
 * `javaClass.name`, which genuinely differs between steps) — that motion already existed and already used
 * `MimirMotion.OverlayMs`/`Easing`. But with no scene blur behind it, the two cross-fading layers (each
 * only the overlay's own ~95%-alpha flat scrim, never fully opaque) let the SHARP, un-softened destination
 * peek through at the crossfade's midpoint, which reads as a jarring flash of an unrelated screen rather
 * than a transition. Every OTHER pixel-fade overlay that already carries glass (`GAME_TITLE_EDIT`,
 * `BRIGHTNESS`, `GAMELIST_ACHIEVEMENTS`, ...) has the identical compositing shape, but it is invisible
 * there because what peeks through is already blurred. Adding these two kinds here softens the same peek
 * instead of adding a second, competing fade over a surface the pixel-fade host already animates.
 *
 * [MimirOverlayKind.ARTWORK_FETCH] and [MimirOverlayKind.ARTWORK_GET] joined this list to fix the identical
 * defect, reported again on hardware 2026-08-19:
 * completion messages appeared to snap instead of fading. Ground, same shape as
 * the credential fix above: both kinds were already inside `MimirDirectOverlayPixelFadeHost` and already
 * cross-faded correctly on `MimirMotion.OverlayMs`/`Easing` on entry/exit — the motion was never missing.
 * What was missing was this allowlist entry: with no scene blur, the two cross-fading layers (`ArtworkFetchOverlay
 * .kt`'s scrim at 0.96 alpha, `GetArtOverlay.kt`'s at 0.92, neither fully opaque) let the sharp destination
 * peek through at the crossfade's midpoint — the reported snapping, not a missing fade
 * but an unsoftened peek through one. Same one-line-per-kind fix, no new duration, curve, or primitive.
 * (`ArtworkFetchOverlay`'s `Running -> Done` phase change USED to also retarget this same host crossfade;
 * the phase-motion change stopped that -- a phase change is now an
 * in-panel `AnimatedContent`, not a second host-level crossfade. This allowlist entry is unaffected: it
 * still governs the KIND's own entry/exit fade, which still needs glass.)
 */
internal fun mimirOverlayUsesGlass(active: MimirOverlayKind?): Boolean = when (active) {
    MimirOverlayKind.HOME_TILE_MENU,
    MimirOverlayKind.HOME_TOME_LIBRARY,
    MimirOverlayKind.LIBRARY_PLATFORM_TILE_MENU,
    MimirOverlayKind.GAMELIST_ACHIEVEMENTS,
    MimirOverlayKind.HOME_OPEN_WITH,
    MimirOverlayKind.HOME_LAUNCH_CHOICE,
    MimirOverlayKind.HOME_LAUNCH_CARD,
    MimirOverlayKind.GAME_TITLE_EDIT,
    MimirOverlayKind.BRIGHTNESS,
    MimirOverlayKind.CONTROLLER_MAPPING,
    MimirOverlayKind.CONTROLLER_SHORTCUT_CAPTURE,
    MimirOverlayKind.ARTWORK_CONNECT,
    MimirOverlayKind.RETROACHIEVEMENTS_KEY,
    MimirOverlayKind.ARTWORK_FETCH,
    MimirOverlayKind.ARTWORK_GET,
    // Glass, so the destination blurs behind the browser rather than showing through it sharply.
    // This `when` ends `else -> false`, so nothing here is compiler-enforced -- the guard is the
    // exhaustive test in CredentialFlowGlassContinuityTest, which is why that test was made
    // exhaustive before this overlay was written.
    MimirOverlayKind.FOLDER_BROWSER,
    // The three Settings pickers built on `MimirSelectOverlay` -- policy 2026-08-29, *"add backdrop
    // glass on overlay selection menu"*. They were absent, so the section pane behind them stayed SHARP
    // while the panel drew at the 0.78 body alpha `MimirLiquidGlassPanel` only uses *because it assumes a
    // blur is there*. Translucent over sharp is what reads as muddy; it is the same omission the Library's
    // own pickers had, on three more surfaces.
    //
    // THEME_PICKER is deliberately NOT here: it left this shell for its own preview gallery (per-page preview), so it is a different surface and a different ruling.
    MimirOverlayKind.BACKDROP_PICKER,
    MimirOverlayKind.ACCENT_PICKER,
    MimirOverlayKind.PLATFORM_PICKER,
    // Settings › Music › Source (2026-09-30) rides the same select shell as those three and takes the same
    // ruling. MUSIC_VOLUME_DIAL does not: like FOCUS_RING_DIAL it draws its own scrim under an opaque card.
    MimirOverlayKind.MUSIC_SOURCE_PICKER,
    // Settings › Music › Equalizer (2026-10-01): its card is a translucent MimirLiquidGlassPanel, so the Settings pane behind it blurs
    // like the pickers' (translucent over sharp reads as muddy). It draws its own scrim as well, kept: the scrim is the panel entry
    // tier's dim and the scene glass only softens, so the two do not stack into a second darkening of the same layer.
    MimirOverlayKind.EQUALIZER_EDITOR,
    -> true
    else -> false
}

@Composable
private fun MimirAppOverlayHost(
    bindings: MimirAppBindings,
    active: MimirOverlayKind?,
    // CP01 §5.2: threaded from MimirAppHost's single read, not re-derived here. See that call site's
    // kdoc for why a second mimirReducedMotion() call is not an equivalent substitute.
    reducedMotion: Boolean,
) {
    // The launch card remains mounted beneath its own child overlays (Edit, Achievements, Open with),
    // preserving both its visual context and FocusAuthority scope until the child closes.
    HomeLaunchCardOverlayHost(
        bindings = bindings,
        reducedMotion = reducedMotion,
        visible = bindings.homeLaunchCard.session != null && !bindings.homeLaunchCard.suspendedForSettings &&
            (bindings.navigation.destination == MimirDestination.HOME || bindings.navigation.destination == MimirDestination.BROWSE),
        backEnabled = active == MimirOverlayKind.HOME_LAUNCH_CARD,
        // Liquid-glass diffusion () — Home entry point ONLY. Home's launch card is this separate sibling host, composed
        // above the destination, so it stays crisp under `mimirGlassBackdrop`'s destination-only blur
        // (see the layering fix's KDoc on `mimirOverlayUsesGlass`) unless explicitly diffused here too.
        // Library's game-detail launch card needs no equivalent wiring: it is composed INSIDE the
        // destination subtree itself (`GameDetailScreen`, full-screen, in `LibraryScreen`), so the SAME
        // destination blur that keeps Home's raw shelf soft already softens Library's card as a side
        // effect — see `HomeLaunchCardTitleEditBlurRadius`'s KDoc for the exact radius/value reused here.
        titleEditActive = active == MimirOverlayKind.GAME_TITLE_EDIT,
        childModalActive = active == MimirOverlayKind.HOME_LAUNCH_CHOICE ||
            active == MimirOverlayKind.HOME_OPEN_WITH,
        // RC-25 / H-04g: the card stays at the held alpha through the sheet's own dissolve and rises after it (the 130 ms hold, then the
        // clear). It used to rise on the commit, so the card and the sheet were both half-visible on the same 180 ms.
        sheetOverCard = rememberHomeLaunchCardSheetHold(
            active = active,
            cancelKey = bindings.homeLaunchCard.dissolveCancellationEpoch,
            reducedMotion = reducedMotion,
        ),
    )
    // The Quick Launch panel and its picker draw in their own band below, not in this host. Over a kept surface (the
    // folder browser, a media flow) the host must keep presenting that surface: presenting null would dispose its
    // composition, and the browser's own DisposableEffect closes it in FileBrowserStore, so closing the panel would
    // return to nothing instead of to the same folder. Every other overlay is presented as itself.
    val directTarget = active
        ?.let { bindings.overlays.presented ?: it }
        ?.takeIf(::mimirDirectOverlayUsesPixelFade)
        ?.let { mimirDirectOverlayTarget(bindings, it) }
    // OPEN WITH on the card is built ahead, as the Library's is (HomeLaunchOpenWithSheet.kt): the sheet stands composed and measured, not placed, in the direct host's
    // `standby` slot from a moment after the card settles (or the pad is on the chip), so that A changes what it always changed and builds none of it. The wish is a derived
    // fact, so the options changing under a pick does not re-run this host; only its flipping does.
    val card = bindings.homeLaunchCard
    val authority = bindings.core.focusAuthority
    val openWithWanted = remember(card, active) {
        derivedStateOf(structuralEqualityPolicy()) { homeLaunchOpenWithWanted(card, active) }
    }
    val openWithBuiltAhead = rememberLibraryOpenWithPrewarm(
        wanted = openWithWanted.value,
        onChip = { homeLaunchOpenWithChipFocused(authority) },
    )
    val openWithStandby = MimirDirectOverlayTarget(MimirOverlayKind.HOME_OPEN_WITH).takeIf { openWithBuiltAhead }
    MimirDirectOverlayPixelFadeHost(bindings, directTarget, reducedMotion, standby = openWithStandby)
    // Unconditional: the cover's exit (a frame beat, then a fade) needs the composition to outlive its request.
    MimirPrimaryReturnCover(bindings, requested = active == MimirOverlayKind.RETURN_COVER)
    HomeTileMenuOverlayHost(bindings, visible = active == MimirOverlayKind.HOME_TILE_MENU, reducedMotion = reducedMotion)
    HomeQuickLaunchOverlayHost(
        bindings,
        visible = active == MimirOverlayKind.HOME_QUICK_LAUNCH ||
            active == MimirOverlayKind.HOME_QUICK_LAUNCH_PICKER,
        backEnabled = active == MimirOverlayKind.HOME_QUICK_LAUNCH,
    )
    HomeQuickLaunchPickerOverlayHost(
        bindings,
        visible = active == MimirOverlayKind.HOME_QUICK_LAUNCH_PICKER,
        reducedMotion = reducedMotion,
    )
    // The editor's composition still detaches on the pop frame (no stale Back handler, no live scope); only its pixels stay.
    MimirEqualizerEditorFadeHost(bindings, visible = active == MimirOverlayKind.EQUALIZER_EDITOR)
}

/** The one thing [MimirEqualizerEditorFadeHost] presents. */
private data object MimirEqualizerEditorTarget

/**
 * The equalizer editor uses the house pixel fade for LEAVING only. It was composed only while
 * it was the top overlay, so on close its subtree, scrim and card left on the pop frame
 * while the scene's glass cleared over the next 180 ms: a sheet cut out in front of a shelf still going sharp. Composed inside
 * [MimirPixelFadeHost], its composition still dies on that frame (the host records the pixels and keeps only those, so there is no
 * stale Back handler, focus scope or pointer node), and the recorded sheet dissolves on [MimirMotion.OverlayMs] while the scene stays
 * blurred behind it ([mimirOutgoingChromeHoldMs]) and clears after.
 *
 * The arrival is NOT the host's: the editor arrives on the glass-panel tier (its own scrim ramp and lift,
 * `rememberMimirGlassProgress` in `EqualizerEditorOverlay`), so the host's incoming layer starts at alpha 1 and snaps there; a
 * second ramp over the first would square the curve (the kdoc on `MimirFolderBrowserContent`).
 */
@Composable
private fun MimirEqualizerEditorFadeHost(bindings: MimirAppBindings, visible: Boolean) {
    MimirPixelFadeHost(
        target = if (visible) MimirEqualizerEditorTarget else null,
        durationMs = MimirMotion.OverlayMs,
        instantIncoming = true,
        incomingStartAlpha = { _, _, _ -> 1f },
    ) {
        MimirEqualizerEditorHost(bindings)
    }
}

/**
 * Logical focus/input swaps on the same commit as [active]. The outgoing overlay survives only as pixels
 * in one reusable layer, so no stale BackHandler or FocusAuthority scope remains alive during the fade.
 */
/**
 * The file manager / picker, composed INSIDE [MimirDirectOverlayPixelFadeHost] rather than beside it.
 *
 *  The perfetto capture  found the
 * browser had an entry fade and NO exit at all: it was composed as a sibling of the fade host, so the
 * host — the one piece of machinery in this file built to fade an overlay's PIXELS out after its
 * composition is gone — never saw it, and closing simply deleted the subtree on one frame.
 *
 * Moving it inside is what gives the return a transition without breaking the house rule that a departure
 * is never animated: when this overlay stops being active its composition is disposed on that same frame
 * (no live tree, no stale BackHandler, no FocusAuthority scope kept alive), and only the graphics layer
 * the host already recorded keeps painting while it fades. Pixels linger; the composition does not.
 *
 * The entry fade that used to live here (`mimirGlassSurfaceEntry`) is GONE on purpose: the host runs its
 * own `incomingAlpha` over the same [MimirMotion.OverlayMs]. Keeping both would multiply two identical
 * ramps into a squared curve — a slower, mushier start that is neither of the two designs.
 */
@Composable
private fun MimirFolderBrowserContent(bindings: MimirAppBindings) {
    // The browser draws itself; input and focus belong to its controller, reached through the store so
    // a mid-navigation browser survives the coordinator's recompositions. FileBrowserSurface owns
    // FileBrowserController.open()/close() itself (a DisposableEffect keyed on `browser`, per that
    // class's own KDoc) -- this wrapping DisposableEffect only clears FileBrowserStore's own reference
    // to match, so a host teardown that skips CONFIRM/CANCEL (and therefore skips FileBrowserStore's
    // own onChoose/onDismiss) does not leave a closed controller sitting in the store still reporting
    // the overlay as active.
    val folderBrowser by FileBrowserStore.controller.collectAsState()
    folderBrowser?.let { browser ->
        DisposableEffect(browser) {
            // closeIfCurrent, not close: FileBrowserStore.switchVolume swaps the controller in place to
            // re-root onto another drive, which fires THIS effect's dispose for the outgoing controller
            // while a live, freshly mounted one already sits in the store. An unconditional close()
            // there would tear down the browser the player just switched into.
            onDispose { FileBrowserStore.closeIfCurrent(browser) }
        }
        // Ruling 3: friendly volume names for the breadcrumb trail. Without this the header reads
        // "storage > emulated > 0 > Roms" -- the raw mount path -- instead of "Drives > SD Card > Roms".
        // The labels are derived, not stored: an SD card inserted while the browser is open changes
        // FileBrowserStore.volumes, which re-keys this map on the next composition.
        val volumes by FileBrowserStore.volumes.collectAsState()
        val crumbLabels = remember(volumes) { StorageVolumeListing.crumbLabels(volumes) }
        // F-05: the digest fills the inspector's own Checksum row instead of a Toast that truncates it.
        val checksum by FileBrowserStore.checksum.collectAsState()
        // F-08: the search engine, handed in as the provider rather than collected here. The store's
        // `search` is volume-scoped, names-only and COLD, so nothing walks until the search screen
        // collects it and a cancelled collection stops the walk mid-directory -- the surface owns that
        // lifecycle, which is what makes a debounced query cost one walk instead of one per keystroke.
        // Passing it (rather than leaving the parameter null) is also what makes the header's search icon
        // and the Y hint appear at all; see FileBrowserController.searchAvailable.
        // F-01: the operation queue. Collected, never decided here -- the store owns what an entry may do
        // (fileQueueDismissable refuses anything non-terminal) and feature-files owns whether the screen
        // exists at all (fileBrowserQueueAvailable, MANAGE-only). The host only carries the values across.
        //
        // Passed unconditionally, exactly like `search` above: PICK-gating is mode-derived downstream, and
        // FileBrowserStore's own `operations ?: return` guards are the backstop. Deleting is NOT wired here
        // -- `onDelete` is a FileBrowserController constructor parameter with no setter, so it lands at
        // construction in FileBrowserStore.kt instead.
        val queue by FileBrowserStore.queue.collectAsState()
        // RC-28 : the identical reservation predicate
        // MimirLibraryDestination already computes for the gamelist/hint rail and the launch card -- the
        // standing inspector sits under the same fixed top-right native anchor and had never asked for it
        // (evidence: `05b_a.png`/`07_files_tile_focus.png`, the inspector's `Folder` chip and `Where`
        // value colliding with the orb).
        //
        // Correct, and the review missed it because feature-files is FULL of correct motion --
        // fileBrowserArrivalGlide is the transition the rest of this programme was modelled on. But every
        // one of its arrivals is INTERNAL: DIRECTORY for a listing change, SCREEN for browse <-> search
        // and for the keyboard. The FIRST MOUNT is FileBrowserArrivalKind.NONE, whose own KDoc says
        // "renders as a completely idle layer -- alpha 1, no travel, no animation started". So opening
        // the manager or the picker -- the one transition the player sees most -- was the single arrival
        // the surface never animated.
        //
        // That entry fade was first built HERE as a local `mimirGlassSurfaceEntry` wrapper, which fixed
        // the arrival and left the departure untouched -- the trace of the same evening then measured the
        // departure as a one-frame cut. Both directions now belong to MimirDirectOverlayPixelFadeHost,
        // which this composable is content of, so there is exactly ONE alpha ramp and it runs both ways.
        // Do not re-add a local entry fade: two identical ramps multiply into a squared curve.
        //
        // No two-clocks risk with the arrival glide below: first mount is FileBrowserArrivalKind.NONE,
        // which starts nothing, so the host's ramp is the only animation on this surface's arrival.
        // Alpha-only, no scale: the 0.96 -> 1.0 lift reads as a lift on
        // a panel floating over a scene and as a ZOOM when the thing scaling is the whole screen. The fade
        // host is alpha-only, preserving that behavior.
        FileBrowserSurface(
            controller = browser,
            authority = bindings.core.focusAuthority,
            crumbLabels = crumbLabels,
            checksum = checksum,
            search = FileBrowserStore::search,
            queue = queue,
            onUndo = FileBrowserStore::undo,
            onReleaseUndo = FileBrowserStore::releaseUndo,
            onDismiss = FileBrowserStore::dismiss,
            onResolveConflict = FileBrowserStore::resolveConflict,
        )

    }
}

/**
 * The record-and-fade shape, extracted so the Tome Folio's departure is a SECOND CALLER of it rather than
 * a fourth copy of it.
 *
 * **What it is.** `record { }` inside `drawWithContent`, so the live subtree's pixels are captured every
 * frame; when [target] changes, the ledger rotates the two reusable layers and the previous capture is
 * drawn by a second, non-semantic `Box` at a falling alpha, whose own animation completing is what
 * unmounts it. The departing COMPOSITION dies on the commit frame either way — only its pixels outlive
 * it, which is the standing law (*"departing compositions are never animated; departing pixels may be"*).
 *
 * **What did NOT fold into it, so the next reader does not read the remaining copies as drift.** Seven
 * sites in this tree record-and-fade. Three are in other modules and are `internal` there, so they are
 * not importable here at all (`LibraryScreen`'s two, `CompanionSurface`'s one). Of the four in this file,
 * `HomeLaunchCardOverlayHost` bakes a title-edit blur into the content BEFORE the capture and runs
 * asymmetric open/return durations, and `HomeQuickLaunchPickerOverlayHost` owns presentation-ownership
 * tokens and refuses to retain a cold outgoing layer at all (`from != null && from != to`). Folding
 * either would mean parameterising this host into a description of its own call sites. They stay.
 *
 * [incomingStartAlpha] is the policy seam, and the Folio is why it exists: a surface whose ARRIVAL is
 * already owned by another ramp must pin this one at 1f, or the two multiply into a squared curve — the
 * failure `FileBrowserSurface`'s comment above already records ("do not re-add a local entry fade").
 *
 * [retainOutgoing] is the second policy seam, for the same reason: the direct overlays crossfade between
 * ANY two targets, but the Folio's brief is a DEPARTURE only, so a tome A → tome B swap inside the Tome
 * Library must keep the in-place recomposition it ships with today rather than growing a crossfade nobody
 * asked for. Named rather than defaulted at the call site, because a difference between two callers of
 * one host is exactly the thing that reads as drift a year from now.
 */
@Composable
private fun <T : Any> MimirPixelFadeHost(
    target: T?,
    durationMs: Int,
    /**
     * The arriving layer's clock (motion audit batch F): the page tier's [MimirMotion.ShineMs] for the direct
     * overlays, [durationMs] for a host that keeps its own pace. The departing layer always leaves on [durationMs].
     */
    arriveMs: Int = durationMs,
    /**
     * The most one frame may move the arriving layer's clock, in ms; 0 leaves it on the frame clock as it is ([mimirIncomingOverlayStallCapMs]). A page that arrives on one short fade with no lead must not lose
     * it to a long first frame (Thor drive 3: a 213 ms first draw left 30 ms of a 180 ms fade, a hard cut for the Background picker).
     */
    arrivalStallCapMs: Int = 0,
    incomingStartAlpha: (cachedLayerTarget: T?, cachedLayerAlpha: Float, hasOutgoing: Boolean) -> Float,
    retainOutgoing: (from: T?, to: T?) -> Boolean = { from, to -> from != to },
    /**
     * RC-54: a page still dissolving when this host is asked to present again (A to nothing to B inside A's 180 ms) stays on screen and
     * finishes its fade, instead of its layer being recycled for B ([mimirRetainLingeringLayers]). False for the Folio's departure-only
     * host, which keeps its in-place recomposition and grows no crossfade nobody asked for.
     */
    retainLingering: Boolean = false,
    /**
     * CP01: how long a COLD arrival (incoming alpha starting at 0) is held before its fade begins — the
     * open-direction timing seam [mimirIncomingOverlayHoldMs] feeds. Zero for every caller except the one
     * kind CP01 ships. Never applied to a reversal that reuses a layer already carrying alpha (guarded by
     * `incomingAlpha.value <= 0f` below) — an interrupted A→B→A must not freeze mid-fade waiting out a
     * hold that only makes sense for a genuine cold open.
     */
    holdMs: Int = 0,
    /**
     * The page tier's lead before a COLD arrival (batch F). Combined with [holdMs] as the larger of the two, never
     * their sum: the Tome Library's CP01 hold IS its lead. Like the hold, never applied to a reversal that reuses a
     * layer already carrying alpha.
     */
    leadMs: Int = 0,
    /**
     *  the arriving layer's clock when the departing layer showed a step of its own (one panel
     * handing to the next) rather than the bare scene. Null keeps [arriveMs] for every arrival.
     */
    stepArriveMs: Int? = null,
    /**
     * CP01 §5.2: reduced motion's settled STATE for this arrival — the incoming layer jumps straight to
     * alpha 1 with no hold and no tween, rather than a shortened or suppressed version of the same ramp.
     * The obvious alternative ("holdMs = 0 under reduced motion" alone) would still run the durationMs
     * tween, which is the two-clocks defect arriving through the accessibility path instead of the
     * animation one — see `MimirDirectOverlayPixelFadeHost`'s call site.
     */
    instantIncoming: Boolean = false,
    /**
     * CP01 beat 3's settle signal. Fires `false` the instant a fresh (target, epoch) begins an incoming
     * ramp that is not already settled, and `true` once that arrival's alpha has reached 1 — by tween,
     * hold+tween, or an instant snap. Never invoked while [target] is null, so a DEPARTURE cannot un-latch
     * a settlement a caller relies on surviving the departure (the return-continuity duty CP01 spec §4.2
     * names — `onBackdropArt`'s whole point is that the stage keeps this arrival's content through the
     * close). Defaults to a no-op so every existing caller of this host is unaffected.
     */
    onIncomingSettleChanged: (target: T, settled: Boolean) -> Unit = { _, _ -> },
    /**
     * The departing layer's clock, from what it showed and what replaces it; [durationMs] unless a caller keeps its own
     * (the browser's return; the appearance pickers' hand-over to the browser).
     */
    departMs: (from: T?, to: T?) -> Int = { _, _ -> durationMs },
    /**
     * How long the departing layer STANDS at its alpha before it dissolves, from what it showed and what replaces it.
     * **The outgoing layer is drawn ABOVE the incoming one** (the second Box below), so a held departure covers the
     * arriving surface completely: with the arrival started at 1 beneath it, the hold then the dissolve read as the
     * incoming surface fading in on the same clocks -- for two opaque surfaces the two are the same pixels. That is how
     * an appearance-image picker hands to the image browser without the pane beneath both showing through
     * (`mimirOutgoingLayerHoldMs`). Zero for every other hand-over. Carried as the dissolve tween's own delay, so it
     * scales with the animator setting and cancels with it, like the incoming lead; presses are held for it as they
     * are for a cold arrival.
     */
    departHoldMs: (from: T?, to: T?) -> Int = { _, _ -> 0 },
    /**
     * The state whose [content] is kept composed while there is no [target] (the Home OPEN WITH sheet, built ahead: see [HomeLaunchOpenWithOverlay]); null for every other
     * surface. It is the same call as the target's, so the arrival finds the composition already there, and the content shows nothing until it is the target. Only the
     * target ever takes part in the fade, the layers or the ledger: this is composition kept, not a surface presented. The Library's pixel-fade host has the same seam.
     */
    standby: T? = null,
    content: @Composable (T) -> Unit,
) {
    val firstLayer = rememberGraphicsLayer()
    val secondLayer = rememberGraphicsLayer()
    val layers = remember(firstLayer, secondLayer) { listOf(firstLayer, secondLayer) }
    // These are draw-thread snapshots, not Compose state. A new epoch reads the last alpha actually
    // painted for each reusable layer, so an A→B→C burst cannot reset half-visible B to 100% before
    // fading it out. The target snapshot also preserves the current alpha on an A→B→A reversal.
    val displayedLayerAlphas = remember {
        floatArrayOf(if (target != null) 1f else 0f, 0f)
    }
    val layerTargets = remember { mutableListOf<T?>(target, null) }
    val epochs = remember { MimirTransitionEpochLedger<T?>(target) }
    val frame = remember(target) {
        val prepared = epochs.prepare(target, retainOutgoing)
        if (retainLingering) mimirRetainLingeringLayers(prepared, { layerTargets[it] }, displayedLayerAlphas) else prepared
    }
    SideEffect { epochs.commit(frame) }

    val startAlpha = incomingStartAlpha(
        layerTargets[frame.primaryLayerIndex],
        displayedLayerAlphas[frame.primaryLayerIndex],
        frame.outgoingLayerIndex != null,
    )
    val incomingAlpha = remember(target, frame.epoch) {
        Animatable(startAlpha)
    }
    //  a panel handing to the next is a swap; only an arrival over the bare scene takes the
    // full clock. Frozen for this arrival: the layer targets are draw-thread snapshots that move on.
    val incomingArriveMs = remember(target, frame.epoch) {
        mimirOverlayArrivalClock(
            handsOver = frame.outgoingLayerIndex?.let { layerTargets[it] } != null,
            arriveMs = arriveMs,
            stepArriveMs = stepArriveMs,
        )
    }
    val outgoingAlpha = remember(target, frame.epoch) {
        Animatable(
            frame.outgoingLayerIndex
                ?.let(displayedLayerAlphas::get)
                ?.coerceIn(0f, 1f)
                ?: 0f,
        )
    }
    var outgoingVisible by remember(target, frame.epoch) {
        mutableStateOf(frame.outgoingLayerIndex != null)
    }
    LaunchedEffect(target, frame.epoch) {
        if (target == null) return@LaunchedEffect
        if (incomingAlpha.value < 1f) {
            // The input hold also covers overlays on the page lead: a cold arrival holds presses until its
            // first painted frame (Back and the shoulders pass), and lets go if it is cancelled first.
            if (incomingAlpha.value <= 0f) holdMimirInputUntilShown(incomingAlpha) { incomingAlpha.value > 0f }
            onIncomingSettleChanged(target, false)
            // Batch F: the page tier's lead and the CP01 hold are ONE wait, the larger of the two, never their sum.
            val lead = maxOf(holdMs, leadMs)
            // Thor drive 3: on a frame clock that a long first frame cannot move by its whole length (arrivalStallCapMs).
            withMimirStallGuard(arrivalStallCapMs) {
                when {
                    // Reduced motion wins even over a genuine cold open: settle instantly, no hold, no tween.
                    instantIncoming -> incomingAlpha.snapTo(1f)
                    // Only a TRUE cold open (alpha still at its floor) waits out the hold. A reversal that
                    // reused a partially-visible layer (mimirIncomingLayerStartAlpha's cachedLayerTarget ==
                    // target branch) is already on screen; freezing it again for holdMs would read as a
                    // stutter, not "soften, then materialise".
                    // The hold is the tween's own delay, not a coroutine `delay()`: it scales with the animator
                    // setting like the fade after it and cancels with it (motion audit batch A).
                    lead > 0 && incomingAlpha.value <= 0f -> incomingAlpha.animateTo(
                        1f,
                        tween(incomingArriveMs, delayMillis = lead, easing = MimirMotion.Easing),
                    )
                    else -> incomingAlpha.animateTo(1f, tween(incomingArriveMs, easing = MimirMotion.Easing))
                }
            }
        }
        onIncomingSettleChanged(target, true)
    }
    LaunchedEffect(target, frame.epoch, frame.outgoingLayerIndex) {
        val outgoingLayerIndex = frame.outgoingLayerIndex ?: return@LaunchedEffect
        val from = layerTargets[outgoingLayerIndex]
        val hold = departHoldMs(from, target)
        // A held departure gates presses as a cold arrival does: released the frame the departing pixels start to go,
        // which is when the arriving surface first shows -- the dock's own gate on the browser, seen from the other layer.
        if (hold > 0 && outgoingAlpha.value >= 1f) holdMimirInputUntilShown(outgoingAlpha) { outgoingAlpha.value < 1f }
        // A lingering page (RC-54) restarts from the alpha it was painted at: the remaining fade, not a full eased clock from there
        // (A to nothing to B at 90 ms would stall at ~0.2 and ghost ~270 ms in all). [mimirLingeringDepartMs] scales by that alpha.
        val departureMs = departMs(from, target).let { full ->
            if (retainLingering) mimirLingeringDepartMs(full, outgoingAlpha.value) else full
        }
        outgoingAlpha.animateTo(
            0f,
            tween(departureMs, delayMillis = hold, easing = MimirMotion.Easing),
        )
        if (epochs.acceptsCompletion(frame.epoch)) outgoingVisible = false
    }

    Box(Modifier.fillMaxSize()) {
        val primaryLayer = layers[frame.primaryLayerIndex]
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    val primaryContent = this
                    primaryLayer.alpha = 1f
                    primaryLayer.record { primaryContent.drawContent() }
                    val alpha = incomingAlpha.value
                    primaryLayer.alpha = alpha
                    displayedLayerAlphas[frame.primaryLayerIndex] = alpha
                    layerTargets[frame.primaryLayerIndex] = target
                    drawLayer(primaryLayer)
                },
        ) {
            (target ?: standby)?.let { content(it) }
        }

        // The departing pixels, ABOVE the arriving surface: `departHoldMs` relies on this order.
        val outgoingLayerIndex = frame.outgoingLayerIndex
        if (outgoingVisible && outgoingLayerIndex != null) {
            val outgoingLayer = layers[outgoingLayerIndex]
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clearAndSetSemantics { }
                    .drawWithContent {
                        val alpha = outgoingAlpha.value
                        outgoingLayer.alpha = alpha
                        displayedLayerAlphas[outgoingLayerIndex] = alpha
                        drawLayer(outgoingLayer)
                    },
            )
        }
    }
}

@Composable
private fun MimirDirectOverlayPixelFadeHost(
    bindings: MimirAppBindings,
    active: MimirDirectOverlayTarget?,
    reducedMotion: Boolean,
    /** The overlay kept composed while nothing is presented (the Home OPEN WITH sheet, built ahead): [MimirPixelFadeHost]'s `standby`. */
    standby: MimirDirectOverlayTarget? = null,
) {
    // Which overlay this one replaced, so Utility coming back from the file browser is the page
    // returned to -- at rest from its first frame -- rather than an arrival. Plain memory, written only in `remember`.
    val kindMemory = remember { MimirDirectOverlayKindMemory() }
    val replacedKind = remember(active?.kind) { kindMemory.last.also { kindMemory.last = active?.kind } }
    MimirPixelFadeHost(
        target = active,
        durationMs = MimirMotion.OverlayMs,
        retainLingering = true,
        departMs = { from, to -> mimirDirectOverlayDepartMs(from?.kind, to?.kind, reducedMotion) },
        // An appearance picker's pixels stand over the arriving image browser for its lead.
        departHoldMs = { from, to -> mimirOutgoingLayerHoldMs(from?.kind, to?.kind, reducedMotion) },
        // Batch F : the panel arrives on the page clock, 540 ms from the 130 ms lead, and still
        // leaves on OverlayMs. A Folio RETURN keeps today's pace (mimirIncomingOverlayArriveMs). The layer is the
        // whole screen, so it stays alpha-only: no lift on a full-screen surface .
        arriveMs = mimirIncomingOverlayArriveMs(
            kind = active?.kind,
            homeTomeLibraryOpenOrigin = bindings.navigation.homeTomeLibrary?.openOrigin,
            reducedMotion = reducedMotion,
        ),
        leadMs = mimirIncomingOverlayLeadMs(
            reducedMotion = reducedMotion,
            kind = active?.kind,
            homeTomeLibraryOpenOrigin = bindings.navigation.homeTomeLibrary?.openOrigin,
        ),
        // Thor drive 3: the Appearance pages' one 180 ms swap is not eaten by a long first frame.
        arrivalStallCapMs = mimirIncomingOverlayStallCapMs(active?.kind, reducedMotion),
        //  the picker to its key entry and one connect phase to the next take the swap tier.
        stepArriveMs = mimirOverlayStepArriveMs(reducedMotion),
        // CP01 correction, 2026-09-02: gated on the ENTRY PATH, not the kind alone -- the Folio's own
        // return to the Library must not inherit this hold. See the function's own kdoc.
        holdMs = mimirIncomingOverlayHoldMsForPresentation(
            kind = active?.kind,
            homeTomeLibraryOpenOrigin = bindings.navigation.homeTomeLibrary?.openOrigin,
        ),
        // CP01 §5.2: the SAME threaded `reducedMotion` gates this arrival's instant settle -- one
        // decision reaching both the scene-blur override (MimirAppHost, above) and this panel-alpha
        // override, never two independent reads that could drift. Deliberately NOT gated on entry path
        // like holdMs above: under reduced motion the hold is already collapsed to 0 via this instant
        // snap regardless of origin, so there is no 130ms-on-the-Folio-return regression to guard here.
        instantIncoming = reducedMotion && active?.kind == MimirOverlayKind.HOME_TOME_LIBRARY,
        incomingStartAlpha = { cachedLayerTarget, cachedLayerAlpha, hasOutgoing ->
            mimirIncomingLayerStartAlpha(
                target = active,
                cachedLayerTarget = cachedLayerTarget,
                cachedLayerAlpha = cachedLayerAlpha,
                hasOutgoing = hasOutgoing,
                replacedKind = replacedKind,
            )
        },
        // CP01 beat 3: the Library's own settle latch, hoisted to bindings.navigation so HomeShelfScreen
        // (a sibling composable, same structural reason onBackdropArt is hoisted there too) can gate
        // homeShelfTomeStageArt's libraryArt priority on it rather than on presence alone.
        onIncomingSettleChanged = { target, settled ->
            if (target.kind == MimirOverlayKind.HOME_TOME_LIBRARY) {
                // CP02 beat 3: bump the arrival fence on the false-edge (a FRESH arrival beginning),
                // strictly before the latch itself flips. Same writer as homeTomeLibraryOpenSettled, so
                // the two can never observe different arrivals -- see the field's own kdoc
                // (MimirNavigationCoordinator.kt).
                if (!settled) bindings.navigation.homeTomeLibraryOpenArrivalEpoch += 1
                bindings.navigation.homeTomeLibraryOpenSettled = settled
            }
        },
        standby = standby,
    ) { presented ->
        MimirDirectOverlayContent(
            bindings,
            presented.kind,
            returnedFromFiles = replacedKind == MimirOverlayKind.FOLDER_BROWSER,
        )
    }
}

/** What [MimirDirectOverlayPixelFadeHost] presented before the current kind. */
private class MimirDirectOverlayKindMemory {
    var last: MimirOverlayKind? = null
}

@Composable
private fun MimirDirectOverlayContent(
    bindings: MimirAppBindings,
    active: MimirOverlayKind,
    returnedFromFiles: Boolean = false,
) {
    when (active) {
        MimirOverlayKind.BRIGHTNESS -> AppBrightnessCoordinatorHost(
            bindings.core.brightness,
            settledOnMount = returnedFromFiles,
        )
        MimirOverlayKind.MIGRATION,
        MimirOverlayKind.PROFILE_NAME,
        MimirOverlayKind.STEAMGRIDDB_KEY,
        MimirOverlayKind.RETROACHIEVEMENTS_KEY,
        MimirOverlayKind.GAME_TITLE_EDIT,
        MimirOverlayKind.CONTROLLER_MAPPING,
        MimirOverlayKind.CONTROLLER_SHORTCUT_CAPTURE,
        MimirOverlayKind.PLATFORM_PICKER,
        MimirOverlayKind.BACKDROP_PICKER,
        MimirOverlayKind.THEME_PICKER,
        MimirOverlayKind.BACKGROUND_PICKER,
        MimirOverlayKind.MBUTTON_PICKER,
        MimirOverlayKind.ACCENT_PICKER,
        MimirOverlayKind.FOCUS_RING_DIAL,
        MimirOverlayKind.MUSIC_SOURCE_PICKER,
        MimirOverlayKind.MUSIC_VOLUME_DIAL -> MimirSettingsOverlayHost(bindings, active)
        // Never presented here: no pixel fade (mimirDirectOverlayUsesPixelFade), the editor is hosted by MimirAppOverlayHost itself.
        MimirOverlayKind.EQUALIZER_EDITOR -> Unit
        MimirOverlayKind.ARTWORK_GET,
        MimirOverlayKind.ARTWORK_FETCH,
        MimirOverlayKind.ARTWORK_CONNECT -> MimirArtworkOverlayHost(bindings, active)
        MimirOverlayKind.COMPANION_PERMISSION -> MimirCompanionPermissionOverlay(bindings)
        MimirOverlayKind.GAMELIST_ACHIEVEMENTS -> MimirAchievementCardOverlay(bindings)
        MimirOverlayKind.HOME_OPEN_WITH -> HomeLaunchOpenWithOverlay(bindings.homeLaunchCard, bindings.core.focusAuthority)
        MimirOverlayKind.HOME_LAUNCH_CHOICE -> HomeLaunchChoiceOverlay(bindings)
        MimirOverlayKind.LIBRARY_PLATFORM_TILE_MENU -> PlatformTileMenuOverlayContent(bindings)
        MimirOverlayKind.HOME_TOME_LIBRARY -> HomeTomeLibraryOverlayHost(bindings)
        // The arm whose ABSENCE was cause C2/C3 of the 2026-08-15 Files transition trace. FOLDER_BROWSER
        // was listed as using this host while falling through to `else -> Unit` here, so the host drew an
        // empty layer, the browser had no exit at all, and the chrome hold waited 180ms for a fade that
        // did not exist. It renders now, so all three become true at once.
        MimirOverlayKind.FOLDER_BROWSER -> MimirFolderBrowserContent(bindings)
        else -> Unit
    }
}

/**
 * Liquid-glass diffusion radius for the launch card while `GAME_TITLE_EDIT` is open. Reuses [GamelistBackdrop]'s exact
 * blur family and value rather than [MimirGlass]'s separate 18dp scene-blur radius: `GamelistBackdrop.kt`
 * documents 34dp as "notched down from 48 → the art stays faintly recognizable", which is exactly the
 * effect wanted here — the card should read as a soft backdrop behind the sharp rename keyboard, not
 * dissolve past recognition.
 */
private val HomeLaunchCardTitleEditBlurRadius = 34.dp

/**
 * Launch-card glass: the launch card's alpha while a glass sheet sits over it. The
 * sheet's glass is translucent by design, and the card is a sibling ABOVE the destination, so the scene blur
 * never reaches it and its PLAY band, art and title read straight through the panel. Alpha only: no blur
 * pass on the weaker GPUs, and the tile menu's own veil stays what Home's shelf tile menu already wears.
 */
internal const val HomeLaunchCardUnderSheetAlpha: Float = 0.3f

/**
 * Which overlays hold the launch card at [HomeLaunchCardUnderSheetAlpha]: the tile menu (Y on the card, whose
 * pages are Customize media and the icon-art picker) and the achievements sheet. Launch choice and Open with
 * already soften the card through `childModalActive`, and title edit through its own blur.
 */
internal fun homeLaunchCardUnderSheet(active: MimirOverlayKind?): Boolean = when (active) {
    MimirOverlayKind.HOME_TILE_MENU,
    MimirOverlayKind.GAMELIST_ACHIEVEMENTS,
    -> true
    else -> false
}

@Composable
private fun HomeLaunchCardOverlayHost(
    bindings: MimirAppBindings,
    visible: Boolean,
    backEnabled: Boolean,
    titleEditActive: Boolean = false,
    /**
     * A CHILD modal is open over this card, so the card itself must go soft behind it.
     *
     * Home's launch card is a sibling host composed ABOVE the destination, so `mimirGlassBackdrop`'s
     * destination-only blur never reaches it — the same reason [titleEditActive] has to exist at all.
     * Without this the disc / version menu drew its translucent glass over a SHARP card and the card's
     * own description text read straight through it (observed on the first Home frame).
     *
     * Blurs at [MimirGlass.backdropBlurRadius], the product's standard modal backdrop, NOT at
     * title-edit's heavier 34dp: this is an ordinary modal over a surface, not the editor's deliberate
     * push-back. Coarse like its sibling — it flips at most twice per session, so the blur node's radius
     * animates in place rather than the subtree being rebuilt.
     */
    childModalActive: Boolean = false,
    /** A glass sheet ([homeLaunchCardUnderSheet]) is over the card: hold it at [HomeLaunchCardUnderSheetAlpha]. */
    sheetOverCard: Boolean = false,
    /** Threaded from MimirAppHost's one read (batch F): the card's arrival snaps under reduced motion. */
    reducedMotion: Boolean = false,
) {
    // Outside the key(): the last face each card wore survives the dissolve-cancellation remount (review 2026-10-15, #7).
    val faceMemory = remember { HomeLaunchCardFaceMemory() }
    HomeLaunchCardLayers(
        card = bindings.homeLaunchCard,
        visible = visible,
        titleEditActive = titleEditActive,
        childModalActive = childModalActive,
        sheetOverCard = sheetOverCard,
        reducedMotion = reducedMotion,
    ) { active ->
        HomeLaunchCardLiveContent(
            bindings = bindings,
            active = active,
            backEnabled = backEnabled,
            faceMemory = faceMemory,
        )
    }
}

/**
 * The launch card's layers: the pixel fade (two reusable layers, an epoch ledger), the glass the card wears behind a child modal, and the card's content in the primary
 * layer. [HomeLaunchCardOverlayHost] with the card's content handed in, so that the app's coordinator and a test's are the same one: it reads [card] and nothing else of
 * the app's bindings.
 */
@Composable
internal fun HomeLaunchCardLayers(
    card: HomeLaunchCardCoordinator,
    visible: Boolean,
    titleEditActive: Boolean = false,
    childModalActive: Boolean = false,
    sheetOverCard: Boolean = false,
    reducedMotion: Boolean = false,
    content: @Composable (HomeLaunchCardSession) -> Unit,
) {
    key(card.dissolveCancellationEpoch) {
        val active = card.session
            ?.takeIf { visible && !card.closing }
        val target = active?.row?.focusKey
        val firstLayer = rememberGraphicsLayer()
        val secondLayer = rememberGraphicsLayer()
        val layers = remember(firstLayer, secondLayer) { listOf(firstLayer, secondLayer) }
        val displayedLayerAlphas = remember { floatArrayOf(0f, 0f) }
        val layerTargets = remember {
            arrayOfNulls<FocusKey>(2).also { it[0] = target }
        }
        val epochs = remember { MimirTransitionEpochLedger<FocusKey?>(target) }
        val frame = remember(target) { epochs.prepare(target) { from, to -> from != to } }
        SideEffect { epochs.commit(frame) }

        val incomingStartAlpha = when {
            target == null -> 1f
            frame.outgoingLayerIndex == null -> 0f
            layerTargets[frame.primaryLayerIndex] == target ->
                displayedLayerAlphas[frame.primaryLayerIndex].coerceIn(0f, 1f)
            else -> 0f
        }
        val incomingAlpha = remember(target, frame.epoch) { Animatable(incomingStartAlpha) }
        //  one game handing to the next inside the open card is a swap (OverlayMs from the lead);
        // only the card opening over the shelf takes the full ShineMs arrival. Frozen for this arrival.
        val cardHandsOver = remember(target, frame.epoch) {
            frame.outgoingLayerIndex?.let { layerTargets[it] } != null
        }
        val outgoingAlpha = remember(target, frame.epoch) {
            Animatable(
                frame.outgoingLayerIndex
                    ?.let(displayedLayerAlphas::get)
                    ?.coerceIn(0f, 1f)
                    ?: 0f,
            )
        }
        var outgoingVisible by remember(target, frame.epoch) {
            mutableStateOf(frame.outgoingLayerIndex != null)
        }
        // PERF (see GamelistBackdrop.kt's PERF-F note, the trap already hit once in this programme): this
        // radius is gated ONLY on the coarse `titleEditActive` boolean, which flips at most twice per
        // GAME_TITLE_EDIT open/close — never on typing, scrolling, launchState updates, or any other
        // per-frame/per-keystroke state this composable also reads. `animateDpAsState` updates the SAME
        // `Modifier.blur` node's radius in place across those unrelated recompositions (a plain parameter
        // change Compose diffs and reuses, not a disposed/rebuilt node) rather than tearing down and
        // rebuilding the blurred subtree the way the old AuroraBackdrop `key(uri)` bug did — the coarse
        // boolean is the ONLY thing this animation is keyed on inside a session, so it never thrashes.
        //
        //  Root cause of that regression: this Animatable used to be scoped ONLY to
        // `key(dissolveCancellationEpoch)` (this whole function), unlike every other piece of per-session
        // state below (`frame`/`incomingAlpha`/`outgoingAlpha`/`outgoingVisible`, all
        // `remember(target, frame.epoch)`-scoped) — so a blur transition left mid-flight by a PRIOR
        // session (Edit -> Cancel, then the card closed before the un-blur settled) kept animating on its
        // OWN clock while a NEW session's content dissolve played over it: two motions, decoupled, on the
        // same visual, which appears blurred while the content sharpens. `key(target,
        // frame.epoch)` gives this Animatable the SAME session lifetime as the dissolve state next to it,
        // so a new session always starts its blur at the coarse boolean's CURRENT value (0.dp outside an
        // active title-edit — title-edit can only open once a card is already up, so a fresh session's
        // `titleEditActive` is always false at mount) instead of inheriting a stale in-flight animation.
        // Still the same MimirMotion.OverlayMs/Easing tokens — only the scope changed, not the motion.
        //
        // A brief blink on launch-card option switches came from reading the Dp radius above
        // READ IN COMPOSITION whose value also decided whether the blur layer existed at all (`if (blurring)` below), which is
        // the anti-pattern `rememberMimirGlassProgress`'s kdoc forbids, and it began clearing the moment the modal's request
        // ended, while the modal was still half-visible. The card now wears `rememberHomeLaunchCardGlass`: a draw-phase level, a
        // constant modifier (the layer is never added or removed mid-transition), and "dissolve, then clear". The session scope
        // of the 08-11 note above is kept: `key(target, frame.epoch)`.
        val cardGlass: HomeLaunchCardGlass = rememberHomeLaunchCardGlassPerCard(
            cardKey = target,
            epoch = frame.epoch,
            titleEditActive = titleEditActive,
            childModalActive = childModalActive,
            cancelKey = card.dissolveCancellationEpoch,
        )
        //  the card behind a child modal is a SNAPSHOT while the glass is at its peak, so the one live blur under the
        // picker is the scene's. Core freezes at the peak (level 1) and thaws on the first frame of the clear ([mimirGlassSurfaceFrozen]); WHEN the card is asked to be
        // held is the Library's one rule for its pickers ([homeLaunchCardSnapshotWanted]): the picker's request (so a committed pick thaws the card at once), the card
        // arrived, nothing leaving it. It was `{ true }`, which held the card frozen through the hold after a pick. Keyed on `incomingAlpha` like the Library's.
        val outgoingVisibleNow = rememberUpdatedState(outgoingVisible)
        val pickerRequestedNow = rememberUpdatedState(childModalActive)
        val cardGlassModifier = remember(cardGlass, incomingAlpha) {
            Modifier.fillMaxSize().mimirGlassBackdropProgress(
                cardGlass.radius,
                cardGlass.level,
                snapshot = {
                    homeLaunchCardSnapshotWanted(
                        titleEditGlass = cardGlass.titleEdit(),
                        pickerRequested = pickerRequestedNow.value,
                        arrived = incomingAlpha.value >= 1f,
                        leaving = outgoingVisibleNow.value,
                    )
                },
            )
        }
        // F12: the State, not its value, so the fade is read in the draw phase and recomposes nothing.
        val underSheetAlpha = rememberHomeLaunchCardUnderSheetAlpha(
            cardKey = target,
            epoch = frame.epoch,
            sheetOverCard = sheetOverCard,
            reducedMotion = reducedMotion,
        )
        LaunchedEffect(target, frame.epoch) {
            if (target != null && incomingAlpha.value < 1f) {
                //  Play has focus from the open frame, so presses wait until the card first paints; a
                // double-tap A no longer launches from a card still at alpha 0.
                if (incomingAlpha.value <= 0f) holdMimirInputUntilShown(incomingAlpha) { incomingAlpha.value > 0f }
                withFrameNanos { }
                // Batch F : the game card arrives on the page clock, 540 ms from the 130 ms
                // lead, like the menu and the Tome library. Only a cold arrival takes the lead; a reversal carries on
                // from the alpha it painted. Reduced motion snaps. The close, below, keeps its own clock.
                incomingAlpha.animateTo(
                    1f,
                    if (reducedMotion) {
                        snap()
                    } else {
                        tween(
                            mimirOverlayArrivalClock(cardHandsOver, MimirMotion.ShineMs, MimirMotion.OverlayMs),
                            delayMillis = if (incomingAlpha.value <= 0f) MimirMotion.OverlayDissolveCoverMs else 0,
                            easing = MimirMotion.Easing,
                        )
                    },
                )
            }
        }
        LaunchedEffect(target, frame.epoch, frame.outgoingLayerIndex) {
            if (frame.outgoingLayerIndex == null) return@LaunchedEffect
            outgoingAlpha.animateTo(
                0f,
                tween(
                    if (target == null) HomeLaunchCardReturnDissolveMs else HomeOverlayDissolveMs,
                    easing = MimirMotion.Easing,
                ),
            )
            if (epochs.acceptsCompletion(frame.epoch)) outgoingVisible = false
        }

        Box(Modifier.fillMaxSize()) {
            val primaryLayer = layers[frame.primaryLayerIndex]
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        val primaryContent = this
                        primaryLayer.alpha = 1f
                        primaryLayer.record { primaryContent.drawContent() }
                        val alpha = incomingAlpha.value
                        primaryLayer.alpha = alpha * underSheetAlpha.value
                        displayedLayerAlphas[frame.primaryLayerIndex] = alpha
                        layerTargets[frame.primaryLayerIndex] = target
                        drawLayer(primaryLayer)
                    },
            ) {
                // A separate nested Box, never touching the drawWithContent chain above that owns the
                // incoming/outgoing pixel dissolve: the blur is baked into this content BEFORE it is
                // captured into `primaryLayer`, so it participates in that existing capture/redraw
                // mechanism rather than needing a second one.
                // The previous glass effect softened letters then snapped sharp, causing a flicker.
                //
                // Cause: Modifier.blur is `graphicsLayer { renderEffect = if (radius > 0) Blur else null }`,
                // and that graphicsLayer is applied EVEN AT 0.dp. So the card's live content — text
                // included — was permanently rasterised through an offscreen render node, then captured
                // into `primaryLayer` on top of that. Text through an offscreen layer is softer than text
                // drawn straight to the surface, and it snaps sharp the instant the layer stops applying.
                //
                // The glass modifier below never changes with the level: below ~0.1 px it draws the sharp layer alone with
                // no render effect, so the common path is as layer-free as the conditional modifier was (no offscreen node,
                // no softened text), and there is no layer to add or remove while the glass ramps.
                //
                // **One node per card, composed only while there is a card.** A Thor recording showed a card popping in at full alpha over the sharp
                // shelf. A layer recorded by hand holds the RenderNodes of children that have a layer of their own, not their pixels: one glass node for every card and the empty
                // frames between let the NULL layer (alpha 1 while no card is up) show the NEXT card whole on a re-open, and left a closing card nothing to dissolve. A node per
                // card is kept by the layer that recorded it, so a departing card dissolves as the pixels it was (HomeLaunchCardOpenSequenceTest).
                active?.let { session ->
                    key(target, frame.epoch) {
                        Box(cardGlassModifier) { content(session) }
                    }
                }
            }

            val outgoingLayerIndex = frame.outgoingLayerIndex
            if (outgoingVisible && outgoingLayerIndex != null) {
                val outgoingLayer = layers[outgoingLayerIndex]
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clearAndSetSemantics { }
                        .drawWithContent {
                            val alpha = outgoingAlpha.value
                            outgoingLayer.alpha = alpha
                            displayedLayerAlphas[outgoingLayerIndex] = alpha
                            drawLayer(outgoingLayer)
                        },
                )
            }
        }
    }
}

/*
 * Scoped to one card (the 08-11 ruling: a card starts its glass and its under-sheet hold fresh), each with the `key(...)` INSIDE a function of its own. Written inline in the
 * host as `val x = key(target, frame.epoch) { ... }`, a card change restarted every effect declared after it at the next recomposition, the arrival's among them: cancelled
 * about 220 ms in and eased in again from the alpha it had (the Library, with no such key, runs straight through). See HomeLaunchCardOpenSequenceTest.
 */
@Composable
private fun rememberHomeLaunchCardGlassPerCard(
    cardKey: Any?,
    epoch: Long,
    titleEditActive: Boolean,
    childModalActive: Boolean,
    cancelKey: Any?,
): HomeLaunchCardGlass = key(cardKey, epoch) {
    rememberHomeLaunchCardGlass(
        titleEditActive = titleEditActive,
        childModalActive = childModalActive,
        titleEditRadius = HomeLaunchCardTitleEditBlurRadius,
        childRadius = MimirGlass.backdropBlurRadius,
        cancelKey = cancelKey,
    )
}

@Composable
private fun rememberHomeLaunchCardUnderSheetAlpha(
    cardKey: Any?,
    epoch: Long,
    sheetOverCard: Boolean,
    reducedMotion: Boolean,
): State<Float> = key(cardKey, epoch) {
    animateFloatAsState(
        targetValue = if (sheetOverCard) HomeLaunchCardUnderSheetAlpha else 1f,
        animationSpec = if (reducedMotion) {
            snap<Float>()
        } else {
            tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing)
        },
        label = "home-launch-card-under-sheet",
    )
}

@Composable
private fun HomeLaunchCardLiveContent(
    bindings: MimirAppBindings,
    active: HomeLaunchCardSession,
    backEnabled: Boolean,
    faceMemory: HomeLaunchCardFaceMemory,
) {
    val game = active.game
    // the achievement count is shown on the launch card ONLY, and it is fetched when the card opens (a fresh read the lane fences,
    // cancels and supersedes). Home's focus moves ask RetroAchievements for nothing.
    val cardAchievements = bindings.achievements.state
    LaunchedEffect(game?.gameId?.value) { game?.let { cardAchievements.probe(it) } }
    val platformLabel = game?.platformId
        ?.let { id -> bindings.library.platforms.firstOrNull { it.platformId == id }?.label }
        ?: active.row.supportingLabel
        ?: when (active.row.itemId) {
            is com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId.AndroidGame -> "Android game"
            is com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId.AndroidApp -> "Android app"
            else -> "Game"
        }
    val statusLine = homeLaunchCardLiveStatusLine(
        platformStatusLine = game?.let { bindings.settingsRead.shelfLaunchStatusLines[it.platformId] },
        launchState = bindings.homeLaunchCard.launchState,
        gameId = game?.gameId,
        platformLabelFor = { platformId ->
            bindings.library.platforms.firstOrNull { it.platformId == platformId }?.label
                ?: platformLabel
        },
    )
    val runtimeStatusLine = homeLaunchCardLiveRuntimeStatusLine(
        launchState = bindings.homeLaunchCard.launchState,
        gameId = game?.gameId,
        platformLabelFor = { platformId ->
            bindings.library.platforms.firstOrNull { it.platformId == platformId }?.label
                ?: platformLabel
        },
    )
    val achievementProgress = game?.let(bindings.achievements.state::progressLabelFor)
    val fallbackEmulator = game?.let { bindings.settingsRead.shelfEmulatorLabels[it.platformId] }
    val renderRow = game?.let { current ->
        active.row.copy(
            // The cover the card paints: the Library door's own reading ([libraryCoverArtUri]) -- the automatic cover, else the cover- or
            // disc-shaped face the resolver carries on `wiredFace` (a `pc_games` portrait lives there since the 2026-10-10 ruling, no longer in `artUri`).
            artworkUri = current.libraryCoverArtUri() ?: active.row.artworkUri,
            flatArtUri = current.flatArtUri ?: active.row.flatArtUri,
            genre = current.genre ?: active.row.genre,
            description = current.description ?: active.row.description,
            descriptionSource = current.descriptionSource ?: active.row.descriptionSource,
        )
    } ?: active.row
    val screenScraperAccount by ConnectedArtStore.connectedAccount.collectAsState()
    // Same route as screenScraperConnected just above: a StateFlow the app layer already maintains,
    // reduced to the plain Boolean feature-library needs. providerValidated is true only for a durably
    // saved, provider-verified user key -- never SteamGridDbKeyStore.resolvedKey's build-time debug
    // fallback -- which is exactly "would Enrich's GateClosed check pass for pc_games" (R-a).
    val steamGridDbKeyPresent by SteamGridDbKeyStore.providerValidated.collectAsState()
    //  This is the producer for EVERY entry point that calls
    // `homeLaunchCard.open` -- the Tome Folio tile and the Home shelf tile, which shares it.
    // Applying it uniformly is the plain reading and keeps one game from wearing two different faces on the
    // same card. Gating it to the Folio origin is possible (the session carries the origin scope)
    // and remains an unresolved design question.
    //
    // Keyed remember, not a bare call: `launchCardWiredFace` stats files and decodes image headers through
    // TomeCartridgeArtStore and CustomCoverStore, and this composable recomposes on every focus move inside
    // the card. (Named the retired `launchCardCartridgeArtUri` until 2026-08-26; the cost argument is the
    // same and the new law reads one more store, so the keying matters slightly more, not less.)
    val cartridgeArtContext = LocalContext.current
    // SUPERSEDED 2026-08-25 by C1. The origin gate that stood here -- disc only when the card was opened
    // from a Tome -- was the right answer to OWED-1 and the wrong SHAPE: it DERIVED the card's face from
    // where you came from, which is unpredictable by construction. C1 replaces derivation with an explicit
    // LAUNCH CARD state.
    //
    // FL-2's ladder, and L2 is what keeps this non-regressive:
    //   L1  the face wired to the LAUNCH CARD state -- his pick, absolute (C4).
    //   L2  otherwise INHERIT the Shelf's already-RESOLVED face (active.row.faceUri), not its raw pick.
    //       Inheriting the resolved face is why a game whose Shelf shows a disc still shows that disc
    //       here, exactly as it did before this change.
    //
    // The face and its KIND are resolved together and travel together. Resolving the uri alone -- which
    // this producer did when it was first written -- leaves the card to infer the kind from nullness, and
    // that inference inverted the moment L2 started inheriting covers: every inherited box was classified
    // physical media and painted at aspect 1f, squared off. Nothing was null to warn on.
    //
    // `bindings.mediaApplyEpoch` is in the key set for the case none of the others can see: a LAUNCH_CARD
    // pick writes under `launch-card#<cacheKey>`, so `active.row.faceUri` (resolved from the SHELF key) is
    // unchanged, and `game` is unchanged unless the repository row happened to move. Without the epoch this
    // card keeps painting the previous face until it is closed and reopened -- the same shape
    // reported on the Library gamelist, by the same cause.
    //
    // RC-16 / L-20: resolved OFF the composing thread (it was a `remember` that ran these 8-15 file stats on the card's first frame, which
    // measured 49.9 ms). The card is invisible for its first 130 ms (the cold lead, and a hand-over starts at 0 too), so the answer lands
    // before anything shows; and the face the card already wears is kept while a re-key resolves (a LAUNCH_CARD pick bumps the media epoch),
    // never blanked to nothing and back. A different game starts from no face -- it must not wear its predecessor's -- but from the face
    // THIS game last wore ([HomeLaunchCardFaceMemory], host-level, review 2026-10-15 #7): a card reopened inside its own close, or one that
    // snaps in under reduced motion (no 130 ms lead to hide the resolve), does not pop its face in. The resolve below still replaces it.
    var launchCardFace by remember(active.row.itemId) { mutableStateOf(faceMemory[active.row.itemId]) }
    LaunchedEffect(
        active.row.itemId,
        game,
        active.row.faceUri,
        active.row.faceIsPhysicalMedia,
        active.row.physicalMediaUri,
        bindings.mediaApplyEpoch,
    ) {
        val row = active.row
        launchCardFace = withContext(Dispatchers.IO) {
            val cacheKey = homeTileMenuCacheKey(row.itemId)
            // L1 -- his LAUNCH CARD pick, absolute (C4). [resolveWiredFace] binds the store to the kind it
            // resolved, so a record saying PHYSICAL cannot serve a cover pick, and a kind whose file is
            // missing falls through honestly rather than reporting a wiring it cannot paint.
            resolveWiredFace(
                context = cartridgeArtContext,
                cacheKey = cacheKey,
                state = MediaState.LAUNCH_CARD,
            )
                // L2 -- THE CARTRIDGE,
                //
                // This tier REPLACES "inherit whatever the shelf resolved", which is what put a cover on a
                // tome game's card and is what the regression report described. Inheriting was defensible -- it kept the card
                // agreeing with the tile you pressed -- but it left the LAUNCH CARD state with no default of
                // its own, so the card's face was decided entirely by a switch meant for shelves.
                //
                // The card's own default is the article, and it always was -- this tier was deliberately
                // ungated while the Cartridge Hero promotion switch existed, and the switch's removal
                //  leaves it exactly as it stands. His pick above still outranks it.
                ?: (
                    TomeCartridgeArtStore.activeUri(
                        cartridgeArtContext,
                        mediaStorageKey(cacheKey, MediaState.SHELF),
                    ) ?: row.physicalMediaUri
                    )
                    ?.takeIf(String::isNotBlank)
                    ?.let { MimirWiredFace(it, physical = true) }
                // L3 -- no cartridge exists, so fall back to the face the Shelf resolved. Going through the
                // shelf's face rather than straight to the raw cover is what keeps a COVER he picked by hand
                // from being quietly replaced by the provider's.
                ?: row.faceUri
                    ?.takeIf(String::isNotBlank)
                    ?.let { MimirWiredFace(it, physical = row.faceIsPhysicalMedia) }
        }
        faceMemory.record(row.itemId, launchCardFace)
    }
    if (backEnabled) {
        BackHandler(enabled = true) { bindings.overlays.dispatch(ControllerAction.CANCEL) }
    }
    // owperf 2026-10-15: this body runs for every change of every store above, and the state used to be built anew each pass: a List field makes it an unstable
    // type, which skipping compares by identity, so the whole card re-ran for a pass that changed nothing it draws (and for OPEN WITH opening, which flips
    // `backEnabled`). The pass keeps the instance it built last time while the new one is equal to it; the close lambda reads `backEnabled` through the
    // updated-state, so it is the same lambda across that flip.
    val backEnabledNow by rememberUpdatedState(backEnabled)
    val cardState = rememberEqualInstance(
        HomeLaunchCardUiState(
            row = renderRow,
            platformLabel = platformLabel,
            coverArtUri = game?.libraryCoverArtUri()?.takeIf(String::isNotBlank) ?: active.row.artworkUri,
            cartridgeArtUri = launchCardFace?.uri,
            // One answer for both doors ([launchCardFaceIsPhysical]): no resolved face is not physical media.
            cartridgeArtIsPhysical = launchCardFaceIsPhysical(launchCardFace?.physical),
            statusLine = statusLine,
            runtimeStatusLine = runtimeStatusLine,
            achievementProgress = achievementProgress,
            // The regression case covers a game with an override pending and a card whose options are cached; a game with NEITHER read its
            // label from `fallbackEmulator`, which is empty until the emulator verdicts resolve, and the row fell to "Automatic" for as long
            // as that took. Until they do the row says what a pending override's says, not a verdict it does not have yet.
            emulatorLabel = homeLaunchCardEmulatorLabelWhileResolving(
                label = bindings.homeLaunchCard.currentOpenWithLabel(fallbackEmulator, overridePending = game?.hasEmulatorOverride == true),
                // Per platform (review 2026-10-15 #8): a rescan that adds a platform flips the global flag false until the new list
                // resolves, and must not send this card, whose own platform already has its verdict, back to the loading copy.
                resolving = game != null && homeLaunchCardEmulatorResolving(
                    platformId = game.platformId,
                    resolvedPlatforms = bindings.settingsRead.shelfLaunchStatusLines.keys,
                    statusesLoaded = bindings.settingsRead.platformStatusesLoaded,
                ),
            ),
            lastPlayedLabel = game?.lastPlayedMillis?.let(::relativePlayed),
            screenScraperConnected = screenScraperAccount != null,
            steamGridDbKeyPresent = steamGridDbKeyPresent,
            openWithLoading = bindings.homeLaunchCard.openWithLoading,
            openWithError = bindings.homeLaunchCard.openWithError,
            // The same REGION / VERSION / SIZE / DISCS facts the Library's door lists, from the one builder, over the members the card already loads for its disc menu.
            extraFacts = launchCardFacts(
                game = game,
                sizeBytes = game?.let { launchCardSizeBytes(it, bindings.homeLaunchCard.members) },
                discs = libraryDetailDiscs(bindings.homeLaunchCard.members),
            ),
        ),
    )
    HomeLaunchCard(
        state = cardState,
        onPlay = { bindings.overlays.dispatch(ControllerAction.CONFIRM) },
        onAchievements = {
            bindings.core.focusAuthority.requestFocus(
                com.gruning.mimir.rewrite.feature.library.HomeLaunchCardAchievementsKey,
            )
            bindings.overlays.dispatch(ControllerAction.CONFIRM)
        },
        onOpenWith = {
            bindings.core.focusAuthority.requestFocus(
                com.gruning.mimir.rewrite.feature.library.HomeLaunchCardOpenWithKey,
            )
            bindings.overlays.dispatch(ControllerAction.CONFIRM)
        },
        onEditTitle = {
            bindings.core.focusAuthority.requestFocus(
                com.gruning.mimir.rewrite.feature.library.HomeLaunchCardTitleEditKey,
            )
            bindings.overlays.dispatch(ControllerAction.CONFIRM)
        },
        onClose = { if (backEnabledNow) bindings.overlays.dispatch(ControllerAction.CANCEL) },
        authority = bindings.core.focusAuthority,
        descriptionScrollCommand = bindings.homeLaunchCard.descriptionScrollCommand,
        onDescriptionScrollStateChanged = bindings.homeLaunchCard::updateDescriptionScrollState,
    )
}

@Composable
private fun HomeLaunchChoiceOverlay(bindings: MimirAppBindings) {
    val card = bindings.homeLaunchCard
    val game = card.session?.game ?: return
    BackHandler(enabled = true) { bindings.overlays.dispatch(ControllerAction.CANCEL) }
    LaunchChoiceOverlay(
        authority = bindings.core.focusAuthority,
        title = game.title,
        members = card.members,
        // The TOUCH path. Controller CONFIRM commits through the overlay coordinator's dispatch arm, so
        // the two never drift into different launch behaviour.
        onChoose = { choice -> card.commitLaunchChoice(choice) },
        onDismiss = { card.dismissLaunchChoice() },
    )
}

/**
 * FUX-12o: a home menu POPS in (instant, interactive at once) and DISSOLVES out over
 * [HomeOverlayDissolveMs], revealing the static tile beneath. Each host retains the last non-null
 * state so its content renders correctly THROUGH the exit fade (nav state has already nulled).
 */
internal const val HomeOverlayDissolveMs = MimirMotion.OverlayMs

/**
 * The launch card's close, on the house overlay clock since motion audit batch F (2026-09-28). It was 120 ms, the one
 * departure in the product faster than focus motion, on the argument that uncovering an already-composed shelf needs
 * less time; the shared motion grammar puts every exit on [MimirMotion.OverlayMs], and the chrome hold for the card fell
 * back to the flat [MimirMotion.OverlayDissolveCoverMs] with it, so its return is the same 311 ms as every other
 * overlay's. Kept as a name so the card's caller and its tests still read as the close.
 */
internal const val HomeLaunchCardReturnDissolveMs = MimirMotion.OverlayMs

internal fun homeLaunchCardLiveStatusLine(
    platformStatusLine: String?,
    launchState: GameLaunchUiState,
    gameId: GameId?,
    platformLabelFor: (PlatformId) -> String,
): String? {
    val targetGameId = gameId ?: return platformStatusLine
    return resolveHomeLaunchCardStatusLine(
        platformStatusLine = platformStatusLine,
        launchState = launchState,
        sessionGameId = gameId,
        targetGameId = targetGameId,
        platformLabelFor = platformLabelFor,
    )
}

internal fun homeLaunchCardLiveRuntimeStatusLine(
    launchState: GameLaunchUiState,
    gameId: GameId?,
    platformLabelFor: (PlatformId) -> String,
): String? {
    val targetGameId = gameId ?: return null
    return resolveHomeLaunchCardRuntimeStatusLine(
        launchState = launchState,
        sessionGameId = gameId,
        targetGameId = targetGameId,
        platformLabelFor = platformLabelFor,
    )
}

@Composable
private fun HomeTileMenuOverlayHost(bindings: MimirAppBindings, visible: Boolean, reducedMotion: Boolean = false) {
    val menu = bindings.navigation.homeTileMenu
    val retained = remember { mutableStateOf(menu) }
    if (menu != null) retained.value = menu
    AnimatedVisibility(
        visible = visible && menu != null,
        // EnterTransition.None, and the entry ramp moved into the content below. This is the tile menu's
        // Missing frame beat: preserve the four visual treatments while fixing the entry timing.
        //
        // fadeIn starts the instant `visible` flips, which is the same frame this subtree begins building.
        // Under UI-01 a subtree still rebuilding discards in-flight animation handles and each restarts
        // ALREADY AT ITS TARGET, so the dissolve could resolve to a cut on exactly the busy frames where
        // it was meant to hide the work. rememberMimirGlassProgress takes `withFrameNanos { }` before it
        // moves a pixel, which lets the panel finish composing under alpha 0 first.
        //
        // The EXIT is untouched on purpose. It is not the defect, AnimatedVisibility already owns the
        // outgoing tree's lifetime correctly. Preserve that working treatment while fixing entry
        // timing. Same HomeOverlayDissolveMs either way (it is `= MimirMotion.OverlayMs`, the
        // same 180 ms the glass rides), and alpha only: no scale lift, because that would be a new
        // treatment rather than a fixed one.
        enter = EnterTransition.None,
        exit = fadeOut(tween(HomeOverlayDissolveMs, easing = MimirMotion.Easing)),
    ) {
        val m = retained.value ?: return@AnimatedVisibility
        if (visible) BackHandler(enabled = true) { bindings.overlays.dispatch(ControllerAction.CANCEL) }
        //  the menu's panel arrives on the page clock, 540 ms from the 130 ms lead, over the scene
        // blur that keeps its 180 ms ramp at MimirAppHost. The same frame beat before a pixel moves.
        val tileMenuEntry = rememberMimirGlassPanelProgress(visible = true, reducedMotion = reducedMotion)
        Box(Modifier.graphicsLayer { alpha = tileMenuEntry().coerceIn(0f, 1f) }) {
        HomeTileMenuOverlay(
            state = m,
            authority = bindings.core.focusAuthority,
            onMenuFocus = { index -> bindings.navigation.homeTileMenu = m.copy(menuFocus = index) },
            onActivateMenu = { index ->
                bindings.navigation.homeTileMenu = m.copy(menuFocus = index)
                bindings.overlays.dispatch(ControllerAction.CONFIRM)
            },
            onPickFocus = { index ->
                bindings.navigation.homeTileMenu = homeTileMenuWithFocusedPick(m, index)
            },
            onActivatePick = { index ->
                bindings.navigation.homeTileMenu = homeTileMenuWithFocusedPick(m, index)
                bindings.overlays.dispatch(ControllerAction.CONFIRM)
            },
            onFooterFocus = {
                bindings.navigation.homeTileMenu = homeTileMenuWithFooterFocused(m)
            },
            onActivateFooter = {
                bindings.navigation.homeTileMenu = homeTileMenuWithFooterFocused(m)
                bindings.overlays.dispatch(ControllerAction.CONFIRM)
            },
            onCustomEnrichAction = { action -> bindings.overlays.dispatch(action) },
            onCustomEnrichQueryChange = { query ->
                bindings.navigation.homeTileMenu?.let { live ->
                    val phase = live.phase as? HomeTileMenuPhase.CustomEnrichPicker ?: return@let
                    bindings.navigation.homeTileMenu = live.copy(
                        phase = phase.copy(picker = phase.picker.copy(queryDraft = query, note = null)),
                    )
                }
            },
            onCustomEnrichKeyboardDone = {
                bindings.navigation.homeTileMenu?.let { live ->
                    val phase = live.phase as? HomeTileMenuPhase.CustomEnrichPicker ?: return@let
                    bindings.navigation.homeTileMenu = live.copy(
                        phase = phase.copy(picker = phase.picker.copy(keyboardOpen = false)),
                    )
                    bindings.core.focusAuthority.requestFocus(homeCustomEnrichSearchKey(phase.picker))
                    bindings.overlays.dispatch(ControllerAction.SECONDARY)
                }
            },
            // the Download music row shows busy while a run is going, read from the state the Music card reads.
            musicBusy = rememberGameMusicRunning(bindings.artwork.state.gameMusicState),
            musicNotConnected = rememberGameMusicNotConnected(bindings.artwork.state.gameMusicState),
        )
        }
    }
}

@Composable
private fun PlatformTileMenuOverlayContent(bindings: MimirAppBindings) {
    val menu = bindings.navigation.platformTileMenu ?: return
    BackHandler(enabled = true) { bindings.overlays.dispatch(ControllerAction.CANCEL) }
    PlatformTileMenuOverlay(
        state = menu,
        authority = bindings.core.focusAuthority,
        onMenuFocus = { index -> bindings.navigation.platformTileMenu = menu.copy(menuFocus = index) },
        onActivateMenu = { index ->
            bindings.navigation.platformTileMenu = menu.copy(menuFocus = index)
            bindings.overlays.dispatch(ControllerAction.CONFIRM)
        },
        onPickFocus = { index ->
            bindings.navigation.platformTileMenu = when (val phase = menu.phase) {
                is HomeTileMenuPhase.ArtPick -> menu.copy(phase = phase.copy(focusedIndex = index))
                else -> menu
            }
        },
        onActivatePick = { index ->
            bindings.navigation.platformTileMenu = when (val phase = menu.phase) {
                is HomeTileMenuPhase.ArtPick -> menu.copy(phase = phase.copy(focusedIndex = index))
                else -> menu
            }
            bindings.overlays.dispatch(ControllerAction.CONFIRM)
        },
        onCustomEnrichAction = { action -> bindings.overlays.dispatch(action) },
        onCustomEnrichQueryChange = { query ->
            bindings.navigation.platformTileMenu?.let { live ->
                val phase = live.phase as? HomeTileMenuPhase.CustomEnrichPicker ?: return@let
                bindings.navigation.platformTileMenu = live.copy(
                    phase = phase.copy(picker = phase.picker.copy(queryDraft = query, note = null)),
                )
            }
        },
        onCustomEnrichKeyboardDone = {
            bindings.navigation.platformTileMenu?.let { live ->
                val phase = live.phase as? HomeTileMenuPhase.CustomEnrichPicker ?: return@let
                bindings.navigation.platformTileMenu = live.copy(
                    phase = phase.copy(picker = phase.picker.copy(keyboardOpen = false)),
                )
                bindings.core.focusAuthority.requestFocus(homeCustomEnrichSearchKey(phase.picker))
                bindings.overlays.dispatch(ControllerAction.SECONDARY)
            }
        },
    )
}

@Composable
private fun HomeTomeLibraryOverlayHost(bindings: MimirAppBindings) {
    val p = bindings.navigation.homeTomeLibrary ?: return
    val reducedMotion = mimirReducedMotion()
    LaunchedEffect(
        p.pendingFocusTomeId,
        bindings.homeShelf.tomeDocument,
        bindings.homeShelf.availableTomes,
    ) {
        val target = p.pendingFocusTomeId ?: return@LaunchedEffect
        val entries = homeTomeLibraryEntries(
            bindings.homeShelf.tomeDocument,
            bindings.homeShelf.availableTomes,
            bindings.homeShelf.homeOverviewTome,
        )
        val exact = entries.indexOfFirst { entry ->
            entry is HomeTomeLibraryEntry.Tome && entry.model.tomeId == target
        }
        if (exact >= 0) {
            bindings.navigation.homeTomeLibrary = bindings.navigation.homeTomeLibrary?.copy(
                entries = entries,
                focusedIndex = exact,
                settledFocusedIndex = null,
                firstVisibleIndex = p.firstVisibleIndex.coerceAtMost(exact),
                draft = null,
                editingTomeId = null,
                draftError = null,
                confirmDelete = false,
                deletePending = false,
                pendingFocusTomeId = null,
            )
        }
    }
    BackHandler(enabled = true) { bindings.overlays.dispatch(ControllerAction.CANCEL) }
        HomeTomeLibraryOverlay(
            state = p,
            authority = bindings.core.focusAuthority,
            active = true,
            reducedMotion = reducedMotion,
            onFirstVisibleIndex = { index ->
                bindings.navigation.homeTomeLibrary = bindings.navigation.homeTomeLibrary?.copy(
                    firstVisibleIndex = index,
                )
            },
            onFocusUnsettled = {
                bindings.navigation.homeTomeLibrary = bindings.navigation.homeTomeLibrary
                    ?.copy(settledFocusedIndex = null)
            },
            onFocusSettled = { index ->
                bindings.navigation.homeTomeLibrary = bindings.navigation.homeTomeLibrary
                    ?.let { current ->
                        if (current.focusedIndex == index) current.copy(settledFocusedIndex = index)
                        else current
                    }
            },
            onFocusChanged = { index ->
                bindings.navigation.homeTomeLibrary?.let { current ->
                    current.entries.getOrNull(index)?.let { entry ->
                        bindings.navigation.homeTomeLibrary = current.copy(
                            focusedIndex = index,
                            settledFocusedIndex = null,
                        )
                        val key = homeTomeLibraryFocusKey(entry)
                        bindings.core.focusAuthority.rememberFocusForScope(HomeTomeLibraryScope, key)
                        bindings.core.focusAuthority.requestFocus(key)
                    }
                }
            },
            onActivate = { index ->
                bindings.navigation.homeTomeLibrary = bindings.navigation.homeTomeLibrary?.copy(
                    focusedIndex = index,
                    // A touch target is necessarily composed and visible; preserve one-tap activation.
                    settledFocusedIndex = index,
                )
                bindings.overlays.dispatch(ControllerAction.CONFIRM)
            },
            onDraftChange = { next ->
                bindings.navigation.homeTomeLibrary = bindings.navigation.homeTomeLibrary?.copy(
                    draft = next,
                    draftError = null,
                    confirmDelete = false,
                )
            },
            onDraftDone = {
                bindings.navigation.homeTomeLibrary?.let { current ->
                    if (current.confirmDelete || current.deletePending) return@let
                    val done: (HomeTomeEditResult) -> Unit = { result ->
                        when (result) {
                            is HomeTomeEditResult.Saved -> {
                                bindings.navigation.homeTomeLibrary =
                                    bindings.navigation.homeTomeLibrary?.copy(
                                        pendingFocusTomeId = result.tomeId,
                                        draftError = null,
                                    )
                            }
                            is HomeTomeEditResult.Failed -> {
                                bindings.navigation.homeTomeLibrary =
                                    bindings.navigation.homeTomeLibrary?.copy(draftError = result.message)
                            }
                        }
                    }
                    current.editingTomeId?.let { tomeId ->
                        bindings.homeShelf.renameTome(tomeId, current.draft.orEmpty(), done)
                    } ?: bindings.homeShelf.createTome(current.draft.orEmpty(), done)
                }
            },
            onDeleteAction = {
                bindings.overlays.dispatch(
                    if (bindings.navigation.homeTomeLibrary?.confirmDelete == true) {
                        ControllerAction.CONFIRM
                    } else {
                        ControllerAction.TERTIARY
                    },
                )
            },
            onPinFeedbackConsumed = { eventId ->
                bindings.navigation.homeTomeLibrary = bindings.navigation.homeTomeLibrary
                    ?.let { current ->
                        if (current.pinFeedback?.eventId == eventId) current.copy(pinFeedback = null)
                        else current
                    }
            },
            onBackdropArt = { art -> bindings.navigation.homeTomeLibraryBackdropArt = art },
            onKeyboardHostChanged = { host ->
                bindings.navigation.homeTomeLibraryKeyboardHost = host
            },
        )
    DisposableEffect(Unit) {
        onDispose { bindings.navigation.homeTomeLibraryKeyboardHost = null }
    }
}

/**
 * Quick Launch's arrival, on the page tier (alpha audit F1a, 2026-09-30): the [MimirPageMotion.LeadMs] lead at alpha 0,
 * then [MimirPageMotion.ArriveMs], like the tile menu and the game card. A previous change moved it off the 180 ms fade to the
 * page tempo but with no lead, so the dock stood at 6% at 40 ms and 26% at 100 ms. Reduced motion snaps.
 *
 * One function feeds both the painting (`fadeIn`) and the gate's probe, so the two run the same clock and cannot drift.
 */
internal fun homeQuickLaunchEnterSpec(reducedMotion: Boolean): FiniteAnimationSpec<Float> =
    mimirOverlayArriveSpec(reducedMotion)

/** The dock leaves on [MimirMotion.OverlayMs], as every dissolving overlay does. */
internal fun homeQuickLaunchExitSpec(): FiniteAnimationSpec<Float> = mimirOverlayLeaveSpec()

@Composable
private fun HomeQuickLaunchOverlayHost(
    bindings: MimirAppBindings,
    visible: Boolean,
    backEnabled: Boolean,
) {
    // The dock arrives on the page tier ([homeQuickLaunchEnterSpec]) and leaves on OverlayMs. Its focus is on the first
    // pinned slot from the frame it mounts, so a second A launched an app nobody had seen: the fade stood at 6% at 40 ms
    // (the lead had been dropped) and a press already reached the slot. The pad now waits until the dock is above 15%
    // ([MimirPageMotion.ShownAlpha], the policy for Quick Launch and its picker only), about 200 ms after the
    // press, and touch waits with it; Back and the shoulders always work. The gate reads a probe on the fade's own two
    // specs and draws nothing, so the painting is exactly what it was.
    val quickLaunchReducedMotion = mimirReducedMotion()
    val enterSpec = homeQuickLaunchEnterSpec(quickLaunchReducedMotion)
    val exitSpec = homeQuickLaunchExitSpec()
    AnimatedVisibility(
        visible = visible,
        enter = if (quickLaunchReducedMotion) EnterTransition.None
            else fadeIn(enterSpec),
        exit = fadeOut(exitSpec),
    ) {
        if (backEnabled) BackHandler(enabled = true) { bindings.homeQuickLaunch.onDismiss() }
        // Keyed on the open generation: a reopen inside the dismiss fade must tear down the
        // outgoing composition and remount, or the retained content skips the mount effect and the
        // panel comes back with no focus scope (2026-08-11 review: fade-window dead panel).
        key(bindings.homeQuickLaunch.openEpoch) {
            val pageInput = MimirSwapPageGate(mimirRingSafeFadeAlpha(enterSpec, exitSpec), MimirPageMotion.ShownAlpha)
            Box(Modifier.fillMaxSize().then(pageInput)) {
                HomeQuickLaunchOverlay(
                    authority = bindings.core.focusAuthority,
                    slots = bindings.homeQuickLaunch.slots,
                    onLaunch = bindings.homeQuickLaunch.onLaunch,
                    onOpenGeneral = bindings.homeQuickLaunch.onOpenGeneral,
                    onEdit = bindings.homeQuickLaunch.onEdit,
                    onDismiss = bindings.homeQuickLaunch.onDismiss,
                )
            }
        }
    }
}

@Composable
private fun HomeQuickLaunchPickerOverlayHost(bindings: MimirAppBindings, visible: Boolean, reducedMotion: Boolean) {
    HomeQuickLaunchPickerFadeHost(
        activeMode = bindings.homeQuickLaunch.pickerMode.takeIf { visible },
        reducedMotion = reducedMotion,
    ) { mode -> HomeQuickLaunchPickerLiveContent(bindings, mode) }
}

/**
 * The picker's presentation: its live content in one of two recorded layers, faded in on [incomingAlpha] and, when it goes
 * or another mode replaces it, its last pixels dissolving out. Split from [HomeQuickLaunchPickerOverlayHost], which owns
 * the app's bindings, so the arrival can be composed without them.
 *
 * Alpha audit F3 (2026-09-30): the picker appeared in one frame at alpha 1 and left on a 180 ms fade, where every other
 * panel that opens over the scene waits out the 130 ms lead and fades in. It now arrives on the step tier, the panel
 * opening over the dock it belongs to: the lead at alpha 0, then OverlayMs, 310 ms from the press and symmetric with its
 * close ([homeQuickLaunchPickerStartAlpha]). Its focus is seeded at mount, so the pad waits until it is above 15%
 * ([MimirPageMotion.ShownAlpha], the policy for Quick Launch and its picker only) and a touch waits with it, on a
 * shield laid over the panel; Back and the shoulders always work. Reduced motion keeps the cut it always had.
 *
 * The dock stays composed under the picker (its host is visible for both overlay kinds), so a picker part-way through its
 * arrival shows the dock through it. If that reads as a double exposure on the device, dim the dock as the launch card is
 * dimmed under a sheet ([HomeLaunchCardUnderSheetAlpha]).
 */
@Composable
internal fun HomeQuickLaunchPickerFadeHost(
    activeMode: HomeQuickLaunchPickerMode?,
    reducedMotion: Boolean,
    probe: HomeQuickLaunchPickerFadeProbe? = null,
    content: @Composable (HomeQuickLaunchPickerMode) -> Unit,
) {
    val firstLayer = rememberGraphicsLayer()
    val secondLayer = rememberGraphicsLayer()
    val layers = remember(firstLayer, secondLayer) { listOf(firstLayer, secondLayer) }
    val displayedLayerAlphas = remember { floatArrayOf(0f, 0f) }
    val layerTargets = remember { arrayOfNulls<HomeQuickLaunchPickerMode>(2) }
    val epochs = remember {
        MimirTransitionEpochLedger<HomeQuickLaunchPickerMode?>(null)
    }
    val frame = remember(activeMode) {
        // A cold null→picker entry has no outgoing pixels. Recording one anyway can leave a visually
        // convincing but noninteractive cache above the new live picker on older device renderers.
        // RC-54: reopened inside its own close, the picker carries on from the alpha it had (the same mode), or finishes dissolving
        // beside the new one (another mode), instead of blinking out and arriving again ([mimirRetainLingeringLayers]).
        mimirRetainLingeringLayers(
            epochs.prepare(activeMode) { from, to -> from != null && from != to },
            { layerTargets[it] },
            displayedLayerAlphas,
        )
    }
    SideEffect { epochs.commit(frame) }

    val incomingStartAlpha = homeQuickLaunchPickerStartAlpha(
        activeMode = activeMode,
        hasOutgoing = frame.outgoingLayerIndex != null,
        cachedLayerTarget = layerTargets[frame.primaryLayerIndex],
        cachedLayerAlpha = displayedLayerAlphas[frame.primaryLayerIndex],
        reducedMotion = reducedMotion,
    )
    val incomingAlpha = remember(activeMode, frame.epoch) { Animatable(incomingStartAlpha) }
    // The touch half of the hold below, for an arrival that starts at alpha 0: until the picker is readable a touch on it
    // reaches nothing. A derived state, so the every-frame fade recomposes this host once, at the flip, and not per frame
    // (compose-expert references/performance.md, "derivedStateOf: reducing recomposition frequency"). A reversal that
    // carries on from an alpha it already painted is not cold and takes no shield, as it takes no hold.
    val shieldTouches by remember(activeMode, frame.epoch) {
        val arrivesCold = incomingStartAlpha <= 0f
        derivedStateOf { arrivesCold && incomingAlpha.value <= MimirPageMotion.ShownAlpha }
    }
    if (probe != null) {
        SideEffect { probe.incomingAlpha = { incomingAlpha.value } }
    }
    val outgoingAlpha = remember(activeMode, frame.epoch) {
        Animatable(
            frame.outgoingLayerIndex
                ?.let(displayedLayerAlphas::get)
                ?.coerceIn(0f, 1f)
                ?: 0f,
        )
    }
    var outgoingVisible by remember(activeMode, frame.epoch) {
        mutableStateOf(frame.outgoingLayerIndex != null)
    }
    LaunchedEffect(activeMode, frame.epoch) {
        if (activeMode != null && incomingAlpha.value < 1f) {
            // The pad half: presses wait until the picker is above 15%, and let go if the arrival is cancelled first
            // (B, or the next mode). The launch card's hold, at the picker's own line: first paint is a trace.
            if (incomingAlpha.value <= 0f) {
                holdMimirInputUntilShown(incomingAlpha) { incomingAlpha.value > MimirPageMotion.ShownAlpha }
            }
            // One picker mode replacing another is a swap: the new one waits out the old one's
            // dissolve cover instead of fading in at the same time (the 08-15 double exposure). A cold opening takes
            // the same lead and the same OverlayMs ([mimirOverlayStepArriveMs]), which is F3.
            incomingAlpha.animateTo(
                1f,
                tween(
                    HomeOverlayDissolveMs,
                    delayMillis = if (incomingAlpha.value <= 0f) MimirMotion.OverlayDissolveCoverMs else 0,
                    easing = MimirMotion.Easing,
                ),
            )
        }
    }
    LaunchedEffect(activeMode, frame.epoch, frame.outgoingLayerIndex) {
        if (frame.outgoingLayerIndex == null) return@LaunchedEffect
        // The remaining fade of a picker caught mid-dissolve (RC-54), not a full eased clock from its painted alpha.
        outgoingAlpha.animateTo(
            0f,
            tween(mimirLingeringDepartMs(HomeOverlayDissolveMs, outgoingAlpha.value), easing = MimirMotion.Easing),
        )
        if (epochs.acceptsCompletion(frame.epoch)) outgoingVisible = false
    }

    Box(Modifier.fillMaxSize()) {
        val primaryLayer = layers[frame.primaryLayerIndex]
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    val primaryContent = this
                    primaryLayer.alpha = 1f
                    primaryLayer.record { primaryContent.drawContent() }
                    val alpha = incomingAlpha.value
                    primaryLayer.alpha = alpha
                    displayedLayerAlphas[frame.primaryLayerIndex] = alpha
                    layerTargets[frame.primaryLayerIndex] = activeMode
                    drawLayer(primaryLayer)
                },
        ) {
            activeMode?.let { mode -> content(mode) }
        }

        val outgoingLayerIndex = frame.outgoingLayerIndex
        if (outgoingVisible && outgoingLayerIndex != null) {
            val outgoingLayer = layers[outgoingLayerIndex]
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clearAndSetSemantics { }
                    .drawWithContent {
                        val alpha = outgoingAlpha.value
                        outgoingLayer.alpha = alpha
                        displayedLayerAlphas[outgoingLayerIndex] = alpha
                        drawLayer(outgoingLayer)
                    },
            )
        }
        // Last, so it is hit first, and a sibling laid over the panel rather than a modifier on it: the scrim and the
        // tiles under it hit-test at alpha 0, and this reaches every one of them, the outside-tap dismissal included.
        if (shieldTouches) {
            Box(Modifier.fillMaxSize().mimirPageTouchShield())
        }
    }
}

/** A test seam, like core-ui's `MimirPageHandoffProbe`: what [HomeQuickLaunchPickerFadeHost]'s arriving picker is painted at. */
internal class HomeQuickLaunchPickerFadeProbe {
    var incomingAlpha: () -> Float = { 0f }
}

/**
 * Where an arriving Quick Launch picker starts (alpha audit F3, 2026-09-30). A picker with nothing before it on this
 * layer pair is opening over the dock, and it starts at 0 like every panel that arrives (the host then takes the 130 ms
 * lead and [MimirMotion.OverlayMs], the step tier's 310 ms, symmetric with its close): it used to start at 1 and cut in.
 * Reduced motion keeps that cut. A mode replacing another starts at 0 too, unless it is a reversal, the mode the layer
 * already carries, which carries on from the alpha it painted so it never blinks out and arrives again. A picker that is
 * closing ([activeMode] null) has no arrival and stands at 1 while its pixels dissolve.
 */
internal fun homeQuickLaunchPickerStartAlpha(
    activeMode: HomeQuickLaunchPickerMode?,
    hasOutgoing: Boolean,
    cachedLayerTarget: HomeQuickLaunchPickerMode?,
    cachedLayerAlpha: Float,
    reducedMotion: Boolean,
): Float = when {
    activeMode == null -> 1f
    !hasOutgoing -> if (reducedMotion) 1f else 0f
    cachedLayerTarget == activeMode -> cachedLayerAlpha.coerceIn(0f, 1f)
    else -> 0f
}

@Composable
private fun HomeQuickLaunchPickerLiveContent(
    bindings: MimirAppBindings,
    mode: HomeQuickLaunchPickerMode,
) {
    val ownership = bindings.overlays.homeQuickLaunchPickerOwnership
    val ownershipInvalidationEpoch = ownership.invalidationEpoch()
    // Ownership normally stays remembered across coordinator projections. Include its identity as a
    // defensive remount key nevertheless: if the composition genuinely swaps owners, an old token must
    // never be offered to the replacement.
    val ownershipToken = remember(ownership, mode, ownershipInvalidationEpoch) { ownership.mount() }
    DisposableEffect(ownership, ownershipToken) {
        onDispose { ownership.unmount(ownershipToken) }
    }
    // Route Back through the live logical stack. Before the far-restore frame is ready the coordinator
    // deliberately swallows it; after close this handler disappears in the same composition commit.
    BackHandler(enabled = true) { bindings.overlays.dispatch(ControllerAction.CANCEL) }
    remember(bindings.core.context, ownershipToken) {
        quickLaunchColdRead(
            load = { QuickLaunchStore.load(bindings.core.context) },
            read = { Unit },
        )
        true
    }
    // Read once per picker opening/mode. Focus moves persist the next opening's seed without doing a
    // synchronous SharedPreferences read on every recomposition of the current grid.
    val initialGeneralPackageName = remember(bindings.core.context, mode, ownershipToken) {
        QuickLaunchStore.lastGeneralPackageName(bindings.core.context)
    }
    val touchActivation = remember(ownershipToken) { HomeQuickLaunchPickerTouchActivation() }
    // A Back→reopen burst can return to the same mode before the old pixels leave composition. The
    // ownership token is the logical opening identity: replacing this subtree disposes and re-pushes
    // the picker FocusAuthority scope even when `mode` is unchanged.
    key(ownershipToken) {
        HomeQuickLaunchPickerOverlay(
            authority = bindings.core.focusAuthority,
            mode = mode,
            items = bindings.homeQuickLaunch.pickerItems,
            presentationEpoch = ownershipToken.epoch,
            initialGeneralPackageName = initialGeneralPackageName,
            onGeneralFocusChanged = { packageName ->
                QuickLaunchStore.setLastGeneralPackageName(bindings.core.context, packageName)
            },
            onPresentationReadyChanged = { ready ->
                ownership.setReady(ownershipToken, ready)
            },
            onChoose = { item ->
                touchActivation.dispatch(
                    mode = mode,
                    item = item,
                    onLaunch = bindings.homeQuickLaunch.onPickerTouchLaunch,
                    onAssign = bindings.homeQuickLaunch.onAssign,
                    onClear = bindings.homeQuickLaunch.onClear,
                )
            },
            onDismiss = { bindings.overlays.dispatch(ControllerAction.CANCEL) },
        )
    }
}

/**
 * Keeps the destination tree immediate and records only its pixels for a bounded outgoing handoff.
 * A reversal rotates the two reusable layers, replacing the prior outgoing image instead of stacking it.
 */
@Composable
private fun RootShelfMotionHost(
    destination: MimirDestination,
    content: @Composable (MimirDestination) -> Unit,
) {
    val firstLayer = rememberGraphicsLayer()
    val secondLayer = rememberGraphicsLayer()
    val layers = remember(firstLayer, secondLayer) { listOf(firstLayer, secondLayer) }
    val epochs = remember { MimirTransitionEpochLedger(destination) }
    val frame = remember(destination) {
        epochs.prepare(destination, ::isRootSurfaceHandoff)
    }
    SideEffect {
        epochs.commit(frame)
    }
    // Draw-thread snapshots, not UI state: the alpha each layer was last painted at and what it showed, so a reversal
    // starts from the screen as it was.
    val displayedLayerAlphas = remember { floatArrayOf(1f, 0f) }
    val layerDestinations = remember { arrayOfNulls<MimirDestination>(2) }

    val outgoingAlpha = remember(destination, frame.outgoingLayerIndex) {
        Animatable(
            if (frame.outgoingLayerIndex != null) {
                rootShelfOutgoingStartAlpha(
                    returning = layerDestinations[frame.primaryLayerIndex] == destination,
                    paintedReturning = displayedLayerAlphas[frame.primaryLayerIndex],
                )
            } else {
                0f
            },
        )
    }
    var outgoingVisible by remember(destination, frame.outgoingLayerIndex) {
        mutableStateOf(frame.outgoingLayerIndex != null)
    }
    LaunchedEffect(destination, frame.outgoingLayerIndex) {
        if (frame.outgoingLayerIndex == null) return@LaunchedEffect
        // Every hop clears on the one scene clock, Library -> Home included .
        val clearMs = RootShelfOutgoingLayerDurationMs
        // Give the incoming destination one complete composition/draw beat under opaque outgoing pixels.
        // This is a prewarm frame, never an input delay: logical navigation already committed above.
        withFrameNanos { }
        outgoingAlpha.animateTo(
            targetValue = 0f,
            animationSpec = tween(
                durationMillis = clearMs,
                easing = MimirMotion.Easing,
            ),
        )
        if (epochs.acceptsCompletion(frame.epoch)) outgoingVisible = false
    }

    Box(Modifier.fillMaxSize()) {
        val primaryLayer = layers[frame.primaryLayerIndex]
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    val primaryContent = this
                    primaryLayer.alpha = 1f
                    primaryLayer.record { primaryContent.drawContent() }
                    displayedLayerAlphas[frame.primaryLayerIndex] = 1f
                    layerDestinations[frame.primaryLayerIndex] = destination
                    drawLayer(primaryLayer)
                },
        ) {
            content(destination)
        }

        val outgoingLayerIndex = frame.outgoingLayerIndex
        if (outgoingVisible && outgoingLayerIndex != null) {
            val outgoingLayer = layers[outgoingLayerIndex]
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clearAndSetSemantics { }
                    .drawWithContent {
                        outgoingLayer.alpha = outgoingAlpha.value
                        displayedLayerAlphas[outgoingLayerIndex] = outgoingAlpha.value
                        drawLayer(outgoingLayer)
                    },
            )
        }
    }
}

@Composable
private fun MimirDestinationHost(bindings: MimirAppBindings) {
    val destination = bindings.navigation.destination
    RootShelfMotionHost(destination) { presentedDestination ->
        MimirDestinationContent(bindings, presentedDestination)
    }
}

@Composable
private fun MimirDestinationContent(
    bindings: MimirAppBindings,
    destination: MimirDestination,
) {
    val core = bindings.core
    val library = bindings.library
    val navigation = bindings.navigation
    val steamGridDbKeyValidated by SteamGridDbKeyStore.providerValidated.collectAsState()
    val onboardingRaUser by RetroAchievementsCredentialStore.connectedUser.collectAsState()
    val onboardingRaKeyState by RetroAchievementsCredentialStore.validationState.collectAsState()
    // FINISH's legend decides whether to name the M button at all, and the rule needs the chassis. This is the
    // same signal MButtonRuntime itself keeps -- `primarySurfaceAvailable` is set to
    // `presentationDisplayId == null` on every reconcile -- read the way AppBrightnessCoordinator reads its
    // sibling, rather than counting displays again here and risking a second answer.
    val singleScreenChassis by CompanionProcessRuntime.services(core.context)
        .mButtonTopology.primarySurfaceAvailable.collectAsState()
    //  A compact single screen no longer swaps HOME and LIBRARY for CompactUnifiedBrowser; the shelves
    // render with their own compact geometry (LibraryScreen's compactSquare solver, HomeShelfScreen's
    // compactViewport) and keep their own input and focus handling.
    when (destination) {
        // Onboarding never shows the player's background (concept A, brief §C): its shelf scenes are the theme's
        // own, so the grounds are withheld here, at the host, rather than inside the flow the onboarding lane owns.
        MimirDestination.ONBOARDING -> CompositionLocalProvider(LocalMimirSceneGrounds provides emptyMap()) {
        OnboardingFlow(
            authority = core.focusAuthority,
            step = navigation.onboardingStep,
            profile = navigation.onboardingProfile,
            accessReview = navigation.onboardingAccessReview,
            favoritesGrid = navigation.onboardingFavoritesGrid,
            welcomeIntro = navigation.onboardingWelcomeIntro,
            stepGlide = navigation.onboardingStepGlide,
            hintCarousel = navigation.onboardingHintCarousel,
            actionTour = navigation.onboardingActionTour,
            accessOrientationSeen = bindings.onboarding.checkpoint.accessOrientationSeen,
            focusIntent = navigation.onboardingFocusIntent,
            library = navigation.onboardingLibrary,
            // USAGE words itself by screen count; the same topology signal FINISH's legend reads below.
            access = remember(bindings.onboarding.access, singleScreenChassis) {
                bindings.onboarding.access.copy(hasSecondaryDisplay = !singleScreenChassis)
            },
            artworkAccount = bindings.artwork.account,
            steamGridDbKeyPresent = steamGridDbKeyValidated,
            settlement = bindings.onboarding.settlement,
            libraryGameCount = bindings.onboarding.checkpoint.scanGroups,
            libraryPlatformCount = library.platformCount,
            currentThemeId = core.themeId,
            onAdvance = bindings.onboarding.advance,
            onCommitName = { name -> ProfileNameStore.setName(core.context, name) },
            onChooseFolder = bindings.onboarding.chooseFolder,
            onRequestFilesAccess = bindings.onboarding.requestFilesAccess,
            onRequestUsageAccess = bindings.onboarding.requestUsageAccess,
            onRequestOverlayAccess = bindings.onboarding.requestOverlayAccess,
            // QA-16
            favoriteGames = bindings.onboarding.favoriteGames,
            favoritesIdentifying = bindings.onboarding.favoritesIdentifying,
            favoritesTomeOpensOnSettle = bindings.onboarding.favoritesTomeOpensOnSettle,
            favoritesIdentifyingInBackground = bindings.onboarding.favoritesIdentifyingInBackground,
            onToggleFavorite = bindings.onboarding.toggleFavorite,
            onMarkAccessOrientationSeen = bindings.onboarding.markAccessOrientationSeen,
            onRequestHomeRole = bindings.onboarding.requestHomeRole,
            onOpenArtworkSetup = bindings.onboarding.openArtworkSetup,
            // SGB key in onboarding too — reuse the app-shell key overlay (renders above onboarding).
            onOpenSteamGridDbKey = { bindings.settings.setSteamGridDbKeyEditing(true) },
            // OR-15: RetroAchievements too, through the connect overlay Settings' Profile row opens.
            onOpenRetroAchievements = {
                bindings.settings.retroAchievementsConnect.open(RetroAchievementsCredentialStore.connectedUser.value)
            },
            retroAchievementsUser = onboardingRaUser,
            retroAchievementsKeyRejected = onboardingRaKeyState == RaKeyState.REJECTED_THIS_SESSION,
            onRetrySettlement = bindings.onboarding.retrySettlement,
            onContinueLocally = bindings.onboarding.continueLocally,
            onApplyTheme = { themeId -> ThemeStore.setTheme(core.context, themeId) },
            teachMButton = onboardingTeachesMButton(
                hasSecondaryDisplay = !singleScreenChassis,
                usageGranted = bindings.onboarding.access.usageGranted,
            ),
        )
        }
        MimirDestination.HOME -> {
            BackHandler(enabled = true) { }
            // System truth for §3.6 reduced motion — Compose animations ignore the animator scale, so
            // without this read the reduced-motion branches are unreachable. Sampled per Home entry.
            val homeReducedMotion = remember { !android.animation.ValueAnimator.areAnimatorsEnabled() }
            // FUX-F: the Hearth Home backdrop reuses the persisted gamelist backdrop choice (no new toggle)
            // and the build-side kill switch; the coordinator's launch-arm/return state (FUX-E) feeds the
            // request gating read-only.
            val backdropMode by BackdropStore.mode.collectAsState()
            // Home asks RetroAchievements for NOTHING as focus moves. The count lives on the launch card only (see HomeLaunchCardLiveContent).
            val homeFocusState by core.focusAuthority.state.collectAsState()
            val homeContentFocus = homeContentFocusKey(
                state = homeFocusState,
                rememberedHome = core.focusAuthority.rememberedFocusIn(HomeShelfScope),
            )
            // OR-1: the focused Editor's Choice game's Tome mark, placed by the shelf in the status seat.
            val homeTomeMark = homeShelfRowForKey(bindings.homeShelf.rows, homeContentFocus)
                ?.itemId
                ?.let(bindings.homeShelf.tomeMarkFor)
            val folio = bindings.homeShelf.tomeFolioState
            val focusedFolioMember = homeTomeFolioBackdropMember(folio, homeFocusState)
            // `retainedFolio` used to live here, holding the departing session alive so the exit fade had
            // something to paint. Nothing keeps a departing composition alive any more (see below), so the
            // retention has no reader and is gone with it.
            // RC-34: the backdrop's Tome stage hands off from the collection hero to the focused member's
            // art only once the folio has FINISHED opening. The opening is the house glass materialisation
            // below (`MimirMotion.OverlayMs`), and `HomeShelfScreen` is its SIBLING under this Box -- a
            // sibling cannot observe another layer's transition, which is why the signal is lifted here
            // rather than solved inside the shelf.
            //
            // **It LATCHES; it must never track.** `focusSettledForActivation` is deliberately unsettled on
            // every authoritative focus change (`HomeTomeFolio.onFocusUnsettled`), so assigning the live
            // conjunction would drop this gate for a frame or two on every game-to-game move INSIDE an
            // already-open folio -- reverting the stage to the collection hero and crossfading forward
            // again on each move. That is art churn, and it is worse than the snap this fixes.
            var folioOpenSettled by remember { mutableStateOf(false) }
            // ONE clock for the Tome's materialisation -- scene blur, scrim, panel alpha and panel lift.
            //
            //  The Folio paints `mimirGlassScrimColor()`, which on
            // a blur-capable device is `MimirGlass.ScrimAlphaBlurred` = 0.52f -- a value that constant's own
            // kdoc prices against a blurred scene (*"The old recipe used 0.85 because it had to hide an
            // unblurred scene by brute force. With a real blur the scrim only needs to buy contrast"*).
            // **The scene behind the Folio was never blurred.** The scene-side ramp this file already runs
            // (`glassProgress` / `mimirGlassBackdropProgress`, ~line 405) is gated on `mimirOverlayUsesGlass`,
            // an allowlist of `MimirOverlayKind`, and the Folio has no member of that enum: it is composed
            // INSIDE this destination as `HomeShelfScreen`'s sibling, not as an overlay. So it was half a
            // glass surface -- panel and scrim, no backdrop -- and no existing wiring could ever have given
            // it one. (`MimirOverlayKind.HOME_TOME_LIBRARY` IS on that allowlist, so the Tome LIBRARY blurred
            // the scene while the Tome FOLIO, the larger surface, did not.)
            //
            // It has to live HERE for the same structural reason the scene-side comment gives: the blur is
            // applied to the sibling being covered, never to the panel, or the panel blurs its own text.
            // `HomeShelfScreen` is a sibling the Folio cannot reach, so a progress created inside
            // `HomeTomeFolio` could only ever be a SECOND clock against this one, and they would drift
            // (`BrightnessOverlay.kt:480`).
            //
            // `identity` is deliberately the default constant, exactly as at the two scene-side sites: this
            // call is always composed and only `visible` flips, so a tome A -> tome B swap keeps the scene
            // soft throughout instead of flashing it sharp between two opens.
            val folioGlassRamp = rememberMimirGlassProgress(visible = folio != null)
            // Motion audit batch F : the Folio's PANEL -- its alpha and 0.96 lift -- arrives on
            // the page clock, 540 ms from the 130 ms lead, while the scene's blur and scrim keep the 180 ms ramp and
            // every gate below (activation, the browser's release) keeps reading `folioGlass`; pad presses wait for the
            // panel's first painted frame (Q2, held inside rememberMimirGlassPanelProgress). It snaps in the same cases
            // `folioGlass` does, decided once in `folioPanel` below.
            val folioPanelRamp = rememberMimirGlassPanelProgress(visible = folio != null, reducedMotion = homeReducedMotion)
            // Reduced motion decided ONCE, for BOTH halves, so the scene and the panel can never end up in
            // different regimes -- a blurring scene behind an already-settled panel would be the two-clocks
            // defect arriving through the accessibility path instead of the animation one.
            //
            // The glass becomes a settled STATE rather than suppressed motion: the scene is blurred, the
            // scrim is at full depth and the panel is at rest, from the first frame. Reduced motion means
            // no travel, while retaining the material -- and the material is what carries legibility
            // here, so removing it would make the covered shelf MORE visible, which is the defect.
            val folioPresent = folio != null
            /**
             * Opened from the Tome Library WHILE that browser is holding its pixels over the whole
             * destination — so this Folio's entrance animation has an audience of exactly nobody.
             *
             * Playing the hidden entrance animation made opening slow.
             * Skipping it lets the browser release as soon as the Folio is opaque instead of waiting out
             * a ramp no one can see.
             *
             * **Captured ONCE per Folio identity, never live-read.** A live read flips back to the ramp
             * the instant the browser clears — and the ramp sits near 0 at that point, so the Folio would
             * DE-MATERIALISE a frame after arriving. The flag is also stamped BEFORE the Folio mounts
             * (see MimirAppCoordinator's onOpen); stamped after, this capture reads false and the snap
             * silently does nothing, which is exactly how it first shipped.
             *
             * The primitive's "never start at 1f" warning ([rememberMimirGlassProgress]) is about
             * presenting a bare surface with no transition. This cut is provably behind an opaque cover.
             */
            val folioOpenedUnderCover = remember(folio?.tome?.tomeId) {
                bindings.navigation.homeTomeLibrary?.handingOff == true
            }
            // The return cover fully hides the Home shelf throughout the transition.
            //
            // Until this, the Folio's return had NO COVER AT ALL: panel and scrim both died inside the
            // `if (folio != null)` below on the commit frame, and the only thing left on screen was the
            // Home shelf, unscrimmed and sharpening for 180 ms. The shelf was not merely visible on the
            // return -- it was the entire return.
            //
            // One ledger decides the whole shape, on Folio PRESENCE only. `remember(folioPresent)` means
            // `prepare` runs on the composition that first sees the flip, so the decision and the frame
            // that needs it are the same frame; the epoch it issues is then the identity for both the
            // held cover and the departure hold below, which is what keeps them on ONE clock.
            val folioPresenceLedger = remember { MimirTransitionEpochLedger(false) }
            val folioPresenceFrame = remember(folioPresent) {
                folioPresenceLedger.prepare(folioPresent, ::homeTomeFolioReturnArmed)
            }
            SideEffect { folioPresenceLedger.commit(folioPresenceFrame) }
            // A Folio LEFT. Independent of whether it was covered when it left, because T-2's ring
            // extinction is owed on every departure.
            val folioDeparted = folioPresenceFrame.outgoingLayerIndex != null
            // ...and it was fully materialised when it left, which is what the held cover additionally
            // needs. `rememberMimirGlassReturnProgress` is born at 1f by construction (its kdoc: the
            // destination is "born already soft, under an opaque outgoing layer so there is nothing to
            // cut"). Back out of a tome DURING its 180 ms open and that premise is false -- the scene is
            // at, say, 0.3 -- so arming would jump the blur and the scrim UP to full on the departing
            // frame and record the panel at full alpha it never reached. A flash-in, then a dissolve.
            //
            // Unarmed, the open ramp simply unwinds from wherever it got to and the recorded panel
            // dissolves from the same value: continuous, no hold, and nothing to cover because the Folio
            // never covered anything. Decided ONCE per departure inside `remember`, exactly as
            // `LibraryScreen`'s own B->B race guard reads `sceneGlassProgress()` once per transition --
            // this is not a per-frame composition read.
            //
            // BOTH clocks, since motion batch F split them: the scene settles on `folioGlassRamp` (180 ms)
            // but the panel keeps arriving on `folioPanelRamp` until ~670 ms.
            //
            // RC-25 / H-03c: the cover arms on the SCENE alone (`folioGlassRamp`), whether or not the panel has finished arriving. The scene
            // is at 1.0 from 180 ms, so arming it steps nothing; it was `minOf(glass, panel)`, which left a back-out between 180 and 670 ms
            // unarmed: the scene unwound with no hold while the recorded, half-arrived panel dissolved on top of it (the 08-15 mush). What
            // the old minimum protected was the PANEL's departing frame, which the return ramp (born at 1f) recorded at full alpha and lift
            // -- the flash-in. That is now the panel's own business: it keeps the alpha it had at the departure ([folioDeparture]).
            val folioDeparture = remember(folioPresenceFrame.epoch) {
                homeTomeFolioDeparture(
                    folioDeparted = folioDeparted,
                    glassAtDeparture = folioGlassRamp(),
                    panelAtDeparture = folioPanelRamp(),
                )
            }
            val folioReturnArmed = folioDeparture.armed
            // Beats 2 and 3: the shelf holds at FULL blur and FULL scrim for OverlayDissolveCoverMs while
            // the recorded Folio dissolves over it, then clears on OverlayMs. Strictly sequential, never a
            // second clock racing the first -- the primitive exists for exactly this and its one shipped
            // caller (`LibraryScreen.kt`, the ruled-good 311 ms manager->shelf return) is restated here
            // with `holdMillis = MimirMotion.OverlayDissolveCoverMs`, which is the frame the departing
            // layer's alpha reaches .044 on the house curve. DEPART 180 · HOLD 130 concurrent · CLEAR 180.
            //
            // Interruption needs nothing here: re-opening a tome mid-return flips `armed` false and the
            // primitive's own `snapTo(0f)` guard fires, so the blur can never park between full and clear.
            val folioReturnRamp = rememberMimirGlassReturnProgress(
                armed = folioReturnArmed,
                identity = folioPresenceFrame.epoch,
                holdMillis = MimirMotion.OverlayDissolveCoverMs,
            )
            val folioGlass: () -> Float = when {
                // Reduced motion first, and it wins over the return too: the glass is a settled STATE, so
                // the cover is present while the Folio is and gone the instant it is not. That is the same
                // "do not move" this arm already meant on the open.
                homeReducedMotion || folioOpenedUnderCover -> {
                    { if (folioPresent) 1f else 0f }
                }
                folioReturnArmed -> folioReturnRamp
                else -> folioGlassRamp
            }
            val folioPanel: () -> Float = when {
                homeReducedMotion || folioOpenedUnderCover -> {
                    { if (folioPresent) 1f else 0f }
                }
                // A return keeps the panel on the scene's own unwind, as before: it is departing pixels then. Unless it had not finished
                // arriving: then the one frame the pixel host records is painted at the alpha the panel had, not at the ramp's 1f.
                folioReturnArmed -> if (folioDeparture.panelFullyArrived) folioReturnRamp else folioDeparture.panelAlpha
                else -> folioPanelRamp
            }
            // THE DEPARTING FRAME (T-2), and it is the one part of this a straightforward implementation
            // gets wrong. `restoreRootEntry` pops the scope, nulls the session and begins the return in
            // ONE synchronous lambda (`HomeShelfCoordinator.kt`), so the last frame the Folio ever DREW is
            // the frame drawn before the press -- member ring lit. Record that and the dissolving layer
            // drags a SECOND gold ring across the shelf for the whole hold, over the shelf's own ring,
            // which `homeShelfLiveChromeLit` correctly relights on that same frame.
            //
            // So the Folio draws exactly one more frame, with `departing = true` -- the RING channel out,
            // the STATE channel conserved (`homeTomeFolioRingLit` / `homeTomeFolioStateChannelLit`) -- and
            // THAT is the frame the pixel host captures. `withFrameNanos` yields once, so the release
            // lands in the next frame's animation callback, strictly after this frame's draw.
            //
            // **This is the house's own "logical now, pixels a moment longer", not a revived
            // `retainedFolio`.** The sibling return already does it and is device-certified:
            // `closeTomeToLibrary` pops the scope and holds the whole composition FOUR frames
            // (`HomeShelfCoordinator.kt`, *"Focus has already left, so what remains on screen is only
            // pixels"*). This is one frame, and unlike the deleted `AnimatedVisibility` exit it does not
            // animate anything -- the composition still dies before a single alpha moves.
            val heldFolio = remember { arrayOfNulls<HomeTomeFolioUiState>(1) }
            SideEffect { if (folio != null) heldFolio[0] = folio }
            var departureRecorded by remember(folioPresenceFrame.epoch) { mutableStateOf(false) }
            LaunchedEffect(folioPresenceFrame.epoch, folioDeparted) {
                if (!folioDeparted) return@LaunchedEffect
                withFrameNanos { }
                departureRecorded = true
                // Release the reference too: the pixels are captured, so the last session's member list
                // and art URIs have no further reader. `departureRecorded` already gates the read, so
                // this is hygiene rather than correctness -- but a held snapshot with no reader is
                // exactly the shape `retainedFolio` was, and one of the two should not outlive the other.
                heldFolio[0] = null
            }
            val departingFolio = heldFolio[0]?.takeIf { folioDeparted && !departureRecorded }
            val presentedFolio = folio ?: departingFolio
            // Identity for the pixel host: the TOME, never the UiState. `HomeTomeFolioUiState` is a new
            // instance on every focus move, page turn and arrange tick, and targeting on it would issue a
            // fresh epoch and restart a transition on every d-pad press. Same lesson
            // `MimirDirectOverlayTarget`'s kdoc states for phase edits. A tome A->B swap with no null
            // frame between does change this id and so does start a new epoch -- but the host's
            // `retainOutgoing` below refuses to retain anything on that edge, so the swap keeps the
            // in-place recomposition it ships with today.
            val presentedFolioId = presentedFolio?.tome?.tomeId
            // Identity, including the transition to null. A direct A->B swap with no null frame between
            // must not carry A's settlement into B's opening.
            //
            // RC-34's completion signal now comes from the materialisation's OWN progress rather than from
            // an `AnimatedVisibility` transition state, because that composable is gone (see below). Same
            // latch semantics, spelled more directly: `first { }` returns once and the effect then ends, so
            // this can only ever be set true, and the identity key remains the sole un-latch. Read through
            // `snapshotFlow` so the per-frame progress is never read at composition scope -- the
            // primitive's own contract, and this is the largest subtree in the product.
            val currentFolio by rememberUpdatedState(folio)
            LaunchedEffect(folio?.tome?.tomeId) {
                folioOpenSettled = false
                if (currentFolio == null) return@LaunchedEffect
                snapshotFlow {
                    folioGlass() >= 1f && currentFolio?.focusSettledForActivation == true
                }.first { it }
                folioOpenSettled = true
            }
            // THE RELEASE. The browser holds its pixels from CONFIRM until this line, so the Folio is
            // finished before anything underneath can be seen. Keyed on the SAME identity as the settle
            // effect above and reading the SAME progress -- no second clock, which is the failure
            // BrightnessOverlay.kt:480 records and the reason RC-34's reducer refuses latches.
            //
            // The `folio == null` arm is the reversal path and it is not optional: an open that never
            // materialises (backed out mid-ramp, or refused) would otherwise strand a browser that paints
            // and swallows input forever. Clearing on a null Folio returns the screen to the Home shelf,
            // which is where a reversal belongs.
            // COVERAGE, a WEAKER condition than settlement and deliberately so. `folioOpenSettled` ANDs
            // the glass ramp with `focusSettledForActivation`; releasing the browser on it cost 333 ms
            // measured, because the browser waited out focus settlement it has no stake in. The hold
            // exists to stop the Home shelf showing through a hole, and the condition for that is
            // OPACITY. Same clock, same identity key, same progress object -- only the focus conjunct is
            // dropped, which is the half that was never about covering.
            var folioCovers by remember { mutableStateOf(false) }
            LaunchedEffect(folio?.tome?.tomeId) {
                folioCovers = false
                if (currentFolio == null) return@LaunchedEffect
                snapshotFlow { folioGlass() >= 1f }.first { it }
                folioCovers = true
            }
            LaunchedEffect(folioCovers, folio == null) {
                val browser = bindings.navigation.homeTomeLibrary
                if (browser?.handingOff == true && (folioCovers || folio == null)) {
                    bindings.navigation.homeTomeLibrary = null
                }
            }
            //  the dock's Files seat follows the session ledger as the Utility row does, withdrawn while a game runs and back
            // when it ends. Read in composition, so the game's end recomposes the rail; the D-pad reads the same answer at dispatch.
            val sessions by MimirSessionAuthority.state.collectAsState()
            val filesSeatOffered = !filesRouteGameRunning(sessions)
            Box(Modifier.fillMaxSize()) {
                // The scene half of the Folio's glass, on a wrapper around EVERY covered sibling rather
                // than on the shelf alone.
                //
                // `HomeStatusPillHost` and `HomeAlphabetJumpIndicator` are the shelf's PEERS in this Box,
                // not its children, and both paint in the 10% margin the Folio's panel does not cover. A
                // blur applied to `HomeShelfScreen`'s own modifier would leave those two sharp, over a
                // softened shelf, under the 0.52 scrim -- and the Folio would be the one glass surface in
                // the product where chrome glows through. Everywhere else this is right by construction,
                // because the product-wide ramp (~line 405) sits on the whole destination Box; here the
                // covered set has to be named, because the Folio is INSIDE the destination.
                //
                // The Folio itself stays outside this wrapper for the reason the scene-side comment gives:
                // a panel inside its own backdrop blur would blur its own text.
                Box(
                    Modifier
                        .fillMaxSize()
                        .mimirGlassBackdropProgress(folioGlass),
                ) {
                    HomeShelfScreen(
                        tomeMark = homeTomeMark,
                        rows = bindings.homeShelf.rows,
                        entries = bindings.homeShelf.entries,
                        tomes = bindings.homeShelf.tomes,
                        onOpenTome = { tomeId ->
                            if (bindings.homeShelf.canActivateHomeContent()) {
                                bindings.homeShelf.openTome(tomeId, HomeTomeOrigin.HOME_ROOT, null, null)
                            }
                        },
                        tomeReturn = bindings.homeShelf.tomeReturn,
                        tomeReturnRootReady = bindings.homeShelf.rootProjectionSettled.takeIf { it },
                        isTomeReturnCurrent = bindings.homeShelf.isTomeReturnCurrent,
                        onFinishTomeReturn = bindings.homeShelf.finishTomeReturn,
                        authority = core.focusAuthority,
                        onLaunch = { row ->
                            if (bindings.homeShelf.canActivateHomeContent() &&
                                bindings.homeShelf.canActivate(row.itemId)
                            ) {
                                bindings.homeLaunchCard.open(row, bindings.homeShelf.gameForAchievements(row))
                            }
                        },
                        onOpenLibrary = { navigation.destination = MimirDestination.LIBRARY },
                        onQuickAction = navigation::onShelfQuickAction,
                        filesSeatOffered = filesSeatOffered,
                        onQuickRailTouch = if (BuildConfig.DEBUG) {
                            DebugHomeRailObservation::recordRailTouch
                        } else {
                            null
                        },
                        toast = bindings.homeShelf.toast,
                        onToastDelivered = bindings.homeShelf.consumeDeliveredMessage,
                        restorationFocusKey = bindings.homeShelf.launchReturnFocusKey,
                        surfaceLabel = bindings.homeShelf.surfaceLabel,
                        //  the compact truthful `L/R Mode` hint — the
                        // shoulders SELECT between two live modes, so no single destination is honest copy.
                        // PR173 S2-01 keeps this Home hint aligned with the logical shoulder dispatch on
                        // both single and dual display hosts. RENDER truth here, INPUT truth at dispatch,
                        // deliberately: sharing the rule must not cost the badge a frame.
                        viewModeSelectAvailable = homeViewModeSelectOffered(
                            companionDisplayActive = bindings.core.companionDisplayActive,
                            viewModeToggleAvailable = bindings.homeShelf.viewModeToggleAvailable,
                            viewMode = bindings.homeShelf.viewMode,
                        ),
                        surfaceEpoch = bindings.homeShelf.surfaceEpoch,
                        surfaceHandoffDirection = bindings.homeShelf.surfaceHandoffDirection,
                        reducedMotion = homeReducedMotion,
                        backdropMode = backdropMode,
                        tomeFolioActive = folio != null,
                        // The Tome Library owns the screen the same way the Folio does, so the scene
                        // behind holds still for card-hopping too .
                        tomeLibraryActive = bindings.navigation.homeTomeLibrary != null,
                        tomeLibraryStageArt = bindings.navigation.homeTomeLibraryBackdropArt,
                        // CP01 beat 3: gates libraryArt's stage priority on the Library's own arrival
                        // rather than on presence alone -- see MimirDirectOverlayPixelFadeHost's
                        // onIncomingSettleChanged for where this latches.
                        tomeLibraryOpenSettled = bindings.navigation.homeTomeLibraryOpenSettled,
                        tomeFolioOpenSettled = folioOpenSettled,
                        tomeFolioFocusedMember = focusedFolioMember,
                        launchingRow = bindings.homeShelf.launchingRow,
                        launchReturnRestoring = bindings.homeShelf.launchReturnRestoring,
                        launchStatusLines = bindings.settingsRead.shelfLaunchStatusLines,
                        // SH02 §6.3: every evidence capture carries the runtime viewport record
                        // (`adb logcat -s MimirShelfViewport`, one line per settled post-inset size).
                        modifier = Modifier.reportShelfViewportEvidence(DisplayRole.PRIMARY),
                    )
                    // FUX-02 R3: the Liquid Mimir glance pill owns the upper-RIGHT corner of Home
                    // (the compact title block owns the left). Sits under all overlays/modals.
                    // The song-title chip (2026-09-30) sits beside it, inboard, on the same glance row; it holds no
                    // focus and takes no input, so the pill's own rulings are untouched.
                    MusicTitleGlanceRow(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp),
                    )
                    HomeAlphabetJumpIndicator(
                        feedback = bindings.homeShelf.alphabetJumpFeedback,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 22.dp),
                    )
                }
                // THE COVER, and it belongs to a layer that survives the return.
                //
                // The scrim used to be a `drawBehind` on the Folio's own root, which meant it died on the
                // commit frame with everything else it was covering for: a 0.52 luminance step in ONE
                // frame, underneath a ramp -- the exact shape `HomeTomeFolio` already refuses on the way
                // IN (*"a hard cut sitting underneath a ramp, which reads as the transition being
                // broken"*), and on API 30 (`MimirGlass.supportsBlur` false) the scrim is the ENTIRE
                // transition, so there it was the whole defect on its own.
                //
                // Identical stacking to what it replaced -- above the covered wrapper, below the panel,
                // full screen, `mimirGlassScrimColor()`, the same `folioGlass` lambda the panel rides --
                // so the OPEN composites exactly as before and only the RETURN changes: it now rides the
                // held cover to zero with the blur instead of vanishing with the panel. Exactly ONE scrim
                // is on screen at any time, which is also why the departing pixels below carry none.
                //
                // **Non-interactive, deliberately.** While the Folio is composed its own root is the
                // pointer sink; once it dies, the hold is paint and nothing else, so a tap landing mid-
                // return reaches the shelf on the frame it lands rather than waiting the cover out
                // (`MimirMotion.InputDelayMs` stays 0, and the destination's own `pointerInput` above
                // already cancels an in-flight tome return on first-down).
                MimirGlassScrim(progress = folioGlass)
                //  And 2026-08-28: *The return transition fully hides the Home shelf*
                //
                // The opening was a whole-scene alpha fade of the Folio subtree over a still-composed,
                // still-SHARP Home shelf, so for its whole duration the composite was part folio and part
                // fully-bright shelf. That is the reported brief Home-shelf exposure --
                // the fade is what surfaced it. The close was the same fade in reverse, which is the
                // departure constraint: compositions must unmount immediately, while captured pixels may fade. Keeping the Folio
                // composed through a 160 ms `fadeOut` left a dead subtree re-registering virtual focus
                // slots into a scope `restoreRootEntry` had ALREADY popped.
                //
                // Removing that fade was right and stays removed. But only half the law was built: the
                // Folio's departure became a ONE-FRAME CUT, and nothing was shipped in its place, so the
                // return had no departing subject at all. This host is the other half -- *"departing
                // pixels MAY be"* animated -- and it is the same record-and-fade every other direct
                // overlay in this file already uses, not a fourth copy of it.
                //
                // Incoming alpha is pinned at 1f. The Folio's ARRIVAL is already owned by
                // `mimirGlassPanelEntry` riding `folioGlass`; letting this host ramp it too would multiply
                // two identical curves into a squared one, which is the failure `FileBrowserSurface`'s
                // comment above records by name. This host animates the DEPARTURE only.
                MimirPixelFadeHost(
                    target = presentedFolioId,
                    durationMs = MimirMotion.OverlayMs,
                    incomingStartAlpha = { _, _, _ -> 1f },
                    // Departures only, and it is the SAME predicate the cover arms on, read on tome
                    // identity instead of on presence. A tome A -> tome B swap inside the Tome Library
                    // keeps the in-place recomposition it ships with; this package is the RETURN, and
                    // growing an unruled crossfade on a neighbouring path is how a fix becomes three.
                    retainOutgoing = { from, to -> homeTomeFolioReturnArmed(from != null, to != null) },
                ) {
                    val activeFolio = presentedFolio ?: return@MimirPixelFadeHost
                    val folioInteractionSource = remember { MutableInteractionSource() }
                    HomeTomeFolio(
                        state = activeFolio,
                        authority = core.focusAuthority,
                        onFocusMember = bindings.homeShelf.focusTomeMember,
                        onOpenMember = { member ->
                            bindings.homeShelf.tomeFolioRows[member.itemId]?.let { row ->
                                if (bindings.homeShelf.canActivate(row.itemId)) {
                                    bindings.homeLaunchCard.open(
                                        row,
                                        bindings.homeShelf.gameForAchievements(row),
                                        com.gruning.mimir.rewrite.feature.library.HomeTomeFolioScope,
                                        member.focusKey,
                                    )
                                }
                            }
                        },
                        onEditMember = { member ->
                            bindings.homeShelf.tomeFolioRows[member.itemId]?.let { row ->
                                if (bindings.homeShelf.canActivate(row.itemId)) {
                                    bindings.navigation.openHomeTileMenu(
                                        homeTileMenuState(
                                            row = row,
                                            game = bindings.homeShelf.gameForAchievements(row),
                                            removable = bindings.homeShelf.isMember(row.itemId),
                                            activeCustomTomeId = bindings.homeShelf.tomeSession?.tomeId as? TomeId.Custom,
                                        ),
                                    )
                                }
                            }
                        },
                        onArrangeAction = bindings.homeShelf.dispatchTomeArrange,
                        onBack = { dispatchMimirInput(bindings, ControllerAction.CANCEL) },
                        onGeneralAction = {},
                        onTouchArrangeIntent = bindings.homeShelf.dispatchTouchTomeArrange,
                        onFocusSettled = bindings.homeShelf.settleTomeFolioFocus,
                        onFocusUnsettled = bindings.homeShelf.unsettleTomeFolioFocus,
                        onFirstInteraction = bindings.homeShelf.cancelTomePageResidue,
                        reducedMotion = homeReducedMotion,
                        // Batch F: the panel's own clock (ShineMs from the lead) while the scene half above keeps
                        // OverlayMs; the reduced-motion and under-cover decisions are folded in at one source.
                        glassProgress = folioPanel,
                        // T-2: true for exactly the ONE frame that gets recorded -- the frame after the
                        // scope popped and before this composition dies. RING channel out, STATE channel
                        // conserved. `folio` is the LIVE session; `activeFolio` may be the held one.
                        departing = folio == null,
                        // The pointer sink is unconditional. Its old `if (folio != null)` gate existed
                        // because the folio used to stay composed for a 160 ms exit fade after the session
                        // ended, and swallowing taps into a dead tree for that long would have been wrong.
                        // The departing frame is a single frame, and the shelf underneath is still fully
                        // covered on it, so a tap landing there has nothing to reach anyway.
                        modifier = Modifier
                            .clickable(
                                interactionSource = folioInteractionSource,
                                indication = null,
                                onClick = {},
                            )
                            .reportShelfViewportEvidence(DisplayRole.PRIMARY),
                    )
                }
            }
        }
        MimirDestination.DIAGNOSTICS -> {
            BackHandler(enabled = true) { navigation.destination = MimirDestination.SETTINGS }
            DiagnosticsScreen(
                onOpenSettings = {
                    navigation.settingsLibraryOrigin = null
                    navigation.destination = MimirDestination.SETTINGS
                },
                onOpenLibrary = { navigation.destination = MimirDestination.LIBRARY },
                onOpenBrowse = { navigation.destination = MimirDestination.BROWSE },
            )
        }
        MimirDestination.SETTINGS -> MimirSettingsDestination(bindings)
        MimirDestination.LIBRARY -> MimirLibraryDestination(bindings)
        MimirDestination.BROWSE -> MimirBrowseDestination(bindings)
    }
}

@Composable
private fun MimirSettingsDestination(bindings: MimirAppBindings) {
    val core = bindings.core
    val navigation = bindings.navigation
    // RC-28 : the identical reservation predicate
    // MimirLibraryDestination/MimirFolderBrowserContent already compute -- the detail pane's own content
    // sits under the same fixed top-right native anchor. Evidence: `06_library.png` (the PROFILE chip/body text/card border). NOT `18_cartridge_art.png`: that frame is RC-28's own table entry for UTILITY's NAVIGATION
    // HINTS row, a different surface -- corrected here rather than propagated. The commissioning brief's
    // own "CONNECT action" collision has no cited frame in either document read this session.
    // A stored value is not a connection. Only a live provider-accepted record may render Connected.
    val steamGridDbValidated by SteamGridDbKeyStore.providerValidated.collectAsState()
    // RA01: the Profile RA row reflects whether a sign-in is stored. This is the USERNAME only — the key
    // is never read into composition, so it cannot reach a recomposition dump or a screenshot.
    val raUser by RetroAchievementsCredentialStore.connectedUser.collectAsState()
    val raKeyState by RetroAchievementsCredentialStore.validationState.collectAsState()
    BackHandler(enabled = true) {
        if (bindings.settings.sourceRemovalConfirmation != null) {
            bindings.settings.cancelSourceRemoval()
        } else if (core.focusAuthority.state.value.scope in MusicSubPageScopes) {
            // A Music sub-page is open (Behaviour, or the Forget folder confirmation): Back leaves it for the Music page, the way B does, and not Settings.
            core.focusAuthority.popScope()
        } else {
            navigation.restoreSettingsOriginOrLibraryRoot()
        }
    }
    // Settings › Music (update): while the Music folder row is focused the footer adds "X Rescan". The focus state is collected here as a State
    // object and read only inside the shell's footer, so a focus move redraws the shell and not the pane.
    val footerFocus = core.focusAuthority.state.collectAsState()
    val musicHasFolder = bindings.settingsRead.music.folder != null
    // Product review 2026-09-28 (F3): the achievement card's row target is read once, when Settings mounts.
    DisposableEffect(Unit) {
        onDispose { navigation.settingsEntryFocusKey = null }
    }
    // Post-connect: return focus to the row of the service that was just connected .
    // requestFocus alone is a no-op if the detail scope is not current (memory
    // `mimir-rewrite-focus-scope-push`), so push the scope first. Consumed once, not per recomposition.
    val feedback = bindings.settings.connectionFeedback
    LaunchedEffect(feedback.focusRowId) {
        val rowId = feedback.focusRowId ?: return@LaunchedEffect
        // RC-50 / S-03 (5): only when the detail scope is not already current. Pushed again on top of itself it left TWO detail scopes on the
        // stack, and B popped one, landed on the other, and needed a second press.
        if (core.focusAuthority.state.value.scope != DetailScope) core.focusAuthority.pushScope(DetailScope)
        core.focusAuthority.requestFocus(detailKey(rowId))
        feedback.consumeFocus()
    }
    // Whatever this destination has not delivered when it leaves is dropped, so a success cannot wait for the
    // next visit and arrive as news. The SteamGridDB key, ScreenScraper connect and RetroAchievements overlays
    // are siblings above MimirDestinationHost, so opening one never disposes this destination and a real
    // success is delivered first (SettingsConnectionFeedback.clearPending).
    DisposableEffect(feedback) {
        onDispose { feedback.clearPending() }
    }
    AppShellScaffold(
        activeTab = ShellTab.SETTINGS,
        onSelectTab = navigation::selectShellTab,
        footerHints = { settingsFooterHints(footerFocus.value, musicHasFolder) },
    ) {
        Box(Modifier.fillMaxSize()) {
        SettingsHome(
            authority = core.focusAuthority,
            onRowActivate = bindings.settings.onRowActivate,
            library = bindings.library.readModel?.copy(platforms = bindings.library.platforms, allFilesAccess = bindings.settingsRead.allFilesAccess),
            //  the one honest
            // consumer of MimirLibraryCoordinator.sourceReadFailureMessage. Reused verbatim, no new copy.
            sourceReadFailureMessage = bindings.library.sourceReadFailureMessage,
            profileDisplayName = core.profileName,
            emulatorAssignments = bindings.settingsRead.emulatorAssignments,
            biosRequirements = bindings.settingsRead.biosRequirements,
            specialAccessRows = bindings.settingsRead.specialAccessRows,
            controllerShortcutRows = bindings.settingsRead.controllerShortcutRows,
            artworkAccount = bindings.artwork.account,
            backdropValue = bindings.settings.backdropValue,
            steamGridDbKeyPresent = steamGridDbValidated,
            retroAchievementsUser = raUser,
            retroAchievementsKeyState = raKeyState,
            appVersion = BuildConfig.VERSION_NAME,
            // F3: the achievement card's one-shot row target wins over the startup restore; its scope is inferred.
            initialFocusKey = navigation.settingsEntryFocusKey
                ?: navigation.startupSettingsFocusKey.takeIf { navigation.startupSettingsFocusPending },
            initialFocusScope = if (navigation.settingsEntryFocusKey != null) {
                null
            } else {
                navigation.startupSettingsFocusScope.takeIf { navigation.startupSettingsFocusPending }
            },
            sourceRemovalConfirmation = bindings.settings.sourceRemovalConfirmation,
            sourceRemovalRunning = bindings.settings.sourceRemovalRunning,
            keyboardPickerEntry = bindings.settingsRead.keyboardPickerEntry,
            keyboardBridgeReady = bindings.settingsRead.androidAppSwitchingStatus == PrivilegedUtilityStatus.READY,
            trackpadSettings = bindings.settingsRead.trackpadSettings,
            music = bindings.settingsRead.music,
            // The engine counts the games that hold a sound only on request; the pane's entry is the request.
            onMusicPaneShown = { bindings.artwork.state.refreshGameMusicCounts() },
            artworkDownloadBlockedCopy = artworkDownloadBlockedCopy(bindings.settingsRead.artworkOperation),
        )
        // "Connection successful!" - the same rail every Main message uses, so connect feedback looks like the rest of the app rather than a bespoke Settings banner.
        // Main has ONE rail (the app host's, bottom-anchored so it never covers the row the focus return just landed on); this hands the feedback to it instead of mounting a
        // second rail at the very same spot, where two messages within 2.4 s drew over each other.
        MimirForwardMessageToHost(message = feedback.toast, onForwarded = feedback::consumeDeliveredMessage)
        }
    }
}

@Composable
private fun MimirLibraryDestination(bindings: MimirAppBindings) {
    val core = bindings.core
    val library = bindings.library
    val nativeShelf = bindings.nativeShelf
    val navigation = bindings.navigation
    val navigationHintVisibility by NavigationHintSettingsStore.visibility.collectAsState()
    //
    // LibraryScreen's own `remember` is keyed on this lambda's identity as well as on the open target, so
    // a new instance is how a changed answer reaches an already-open card.
    // 2026-08-26: repointed from `launchCardCartridgeArtUri` -- a pre-C1 resolver that was gated on
    // Cartridge Hero (vetoing a hand-picked cartridge, which C4 forbids), read the store at the BARE
    // cache key (so a LAUNCH_CARD pick was invisible to it) and returned a bare uri with no kind. The
    // Library destination's card was therefore resolving the same surface by a different, older law than
    // the Home destination's. Both now run `launchCardWiredFace`.
    // `mediaApplyEpoch` re-keys this on every apply, which is what makes an applied LAUNCH CARD face reach
    // an OPEN card: a new lambda identity is also a new key for `LibraryScreen`'s own `remember` on it,
    // which is the mechanism the comment above describes. The SHELF face travels
    // the other route -- `refreshArt` re-projects the row and `GameRow.wiredFace` carries it.
    val cartridgeArtUriForGame: (GameRow) -> MimirShelfFace? =
        remember(core.context, bindings.mediaApplyEpoch) {
            { game ->
                launchCardWiredFace(
                    context = core.context,
                    cacheKey = game.gameId.value,
                    providerPhysicalMediaUri = game.physicalMediaUri,
                    shelfArtworkUri = game.artUri,
                )?.let { MimirShelfFace(it.uri, if (it.physical) ShelfFaceShape.PHYSICAL else ShelfFaceShape.LEDGE_COVER) }
            }
        }
    val processServices = remember(core.context) { CompanionProcessRuntime.services(core.context) }
    val librarySearchKeyboardOpen by core.libraryViewModel.keyboardOpen.collectAsState()
    val suppressPrimaryMForTextEntry = suppressPrimaryMForLibraryTextEntry(
        searchKeyboardOpen = librarySearchKeyboardOpen,
        nativeAddKeyboardOpen = nativeShelf.addKeyboardOpen,
    )
    LaunchedEffect(processServices, suppressPrimaryMForTextEntry) {
        processServices.mButtonTopology.setPrimaryUiSuppressed(suppressPrimaryMForTextEntry)
    }
    DisposableEffect(processServices) {
        onDispose {
            processServices.mButtonTopology.setPrimaryUiSuppressed(false)
        }
    }
    BackHandler(enabled = true) {
        if (nativeShelf.addKeyboardOpen) {
            nativeShelf.closeAddSearch()
            return@BackHandler
        }
        dispatchMimirLibraryInput(bindings, ControllerAction.SYSTEM_BACK)
    }
    //  the Library shows the same Liquid Mimir glance pill as Home (upper-right corner,
    // under all overlays). Wrap the shelf + pill as siblings, mirroring the HOME branch.
    Box(Modifier.fillMaxSize()) {
    LibraryScreen(
        viewModel = core.libraryViewModel,
        authority = core.focusAuthority,
        platformLaunchStatusLines = bindings.settingsRead.shelfLaunchStatusLines,
        nativeGameRows = nativeShelf.snapshot.games,
        nativeAppRows = nativeShelf.snapshot.apps,
        nativeAddPickerMode = nativeShelf.addPickerMode,
        nativeAddCandidates = nativeShelf.addCandidates,
        nativeAddSearchQuery = nativeShelf.addSearchQuery,
        nativeAddKeyboardOpen = nativeShelf.addKeyboardOpen,
        onLaunchNativePackage = nativeShelf.launchPackage,
        canFetchArt = bindings.artwork.canFetchArt,
        steamGridDbKeyPresent = bindings.artwork.steamGridDbKeyPresent,
        //  the touch path to the same per-game fetch as MimirAppInputDispatch's controller
        // path, gated the same way -- under a running game-music download it says why rather than fetching into a
        // Busy result and the generic could-not card.
        onFetchArtForGame = { row ->
            if (artworkEntryMayStart(ArtworkOperationGate.process.active.value)) {
                bindings.artwork.state.fetchOne(row)
            } else {
                showMimirMessage(core.context, GameMusicDownloadingCopy, com.gruning.mimir.rewrite.core.ui.MimirMessageClass.Guidance)
            }
        },
        onCustomizeHeroForGame = bindings.onOpenLibraryHeroPicker,
        // Read as a LAMBDA the gamelist calls with its own destination game, so the coordinator can answer
        // null for any other game — a count can never end up beside the wrong title.
        achievementLabelForGame = bindings.achievements.state::progressLabelFor,
        cartridgeArtUriForGame = cartridgeArtUriForGame,
        onProbeAchievements = bindings.achievements.state::probe,
        onOpenAchievements = bindings.achievements.state::openCard,
        onEditGameTitle = { game ->
            bindings.navigation.openGameTitleEdit(
                gameId = game.gameId,
                currentTitle = game.title,
                hasOverride = game.hasTitleOverride,
            )
        },
        onOpenNativeAddPicker = nativeShelf.openAddPicker,
        onNativeAddSearchChange = nativeShelf.setAddSearchQuery,
        onOpenNativeAddSearch = nativeShelf.openAddSearch,
        onCloseNativeAddSearch = nativeShelf.closeAddSearch,
        onChooseNativeAddPackage = nativeShelf.chooseAddPackage,
        onDismissNativeAddPicker = nativeShelf.dismissAddPicker,
        pendingNativeHidePackage = nativeShelf.pendingHidePackage,
        onConfirmHideNativePackage = nativeShelf.confirmHidePackage,
        onCancelHideNativePackage = nativeShelf.cancelHidePackage,
        onRescanFolder = bindings.settings.onRescanFolder,
        backdropMode = bindings.settings.backdropMode,
        heroArtUriForGame = { game ->
            CustomHeroStore.overrideUri(core.context, game.gameId) ?: game.heroArtUri
        },
        // Hero motion in the gamelist . No `?: game.<something>` fallback on
        // purpose: a clip is only ever an explicit choice the player made in Customize media, so there is
        // nothing to fall back TO. Absence means "this game has no motion", and the gamelist then renders
        // the still exactly as before.
        heroMotionUriForGame = { game -> CustomHeroStore.previewClipUri(core.context, game.gameId) },
        detailActionState = bindings.detailActionState,
        detailSession = library.detailSession,
        detailDescriptionOwnerToken = library.detailDescriptionOwnerToken,
        detailDescriptionScrollCommand = library.detailDescriptionScrollCommand,
        onDetailDescriptionScrollStateChanged = library.updateDetailDescriptionScrollState,
        transitionCancellationEpoch = library.transitionCancellationEpoch,
        onOpenLibraryDetail = library.openDetail,
        onCloseLibraryDetail = library.closeDetail,
        navigationHintsEnabled = navigationHintVisibility.enabled,
        quickRailContent = {
            //  the Files seat is withdrawn while a game runs, on this dock as on Home's (see the Home branch).
            val sessions by MimirSessionAuthority.state.collectAsState()
            LibraryShelfQuickRail(
                authority = core.focusAuthority,
                onAction = navigation::onShelfQuickAction,
                homeEnabled = BuildConfig.FINAL_SHELF_ENABLED,
                filesSeatOffered = !filesRouteGameRunning(sessions),
            )
        },
        parkedRefusalOnHostRail = true,
    )
    // The parked-launch refusal goes to Main's ONE message rail (the app host's) instead of a rail of the Library's own, which stood at the same bottom centre.
    val parkedRefusalToast by core.libraryViewModel.parkedRefusalToast.collectAsState()
    MimirForwardMessageToHost(message = parkedRefusalToast, onForwarded = core.libraryViewModel::consumeDeliveredParkedRefusalToast)
    // Compact review 2026-09-27 (SEARCH-1): on the RG's 564.7 dp square Search's own header puts "B  BACK" in
    // this corner behind a 16 dp inset, and the HUD (to 43 dp) covered its top half. The HUD sits under every
    // overlay by rule (FUX-02 R3), so it fades out while Search is up, on Search's own fade. Update's sweep (10-09): on Main it covered "B  BACK" the same way, so the rule is
    // no longer compact-only.
    val librarySearchOpen by core.libraryViewModel.librarySearchOpen.collectAsState()
    val statusPillAlpha by animateFloatAsState(
        targetValue = if (homeStatusPillVisible(librarySearchOpen)) 1f else 0f,
        animationSpec = if (mimirReducedMotion()) snap() else tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing),
        label = "libraryStatusPillYieldsToSearch",
    )
    // The song-title chip (2026-09-30) rides the pill's own yield to compact Search, so it never sits over Search alone.
    MusicTitleGlanceRow(
        Modifier
            .align(Alignment.TopEnd)
            .padding(16.dp)
            .graphicsLayer { alpha = statusPillAlpha },
    )
    }
}

/** Whether the Library's status HUD shows: always, except over Library Search (SEARCH-1; every size since update's sweep, 10-09). */
internal fun homeStatusPillVisible(searchOpen: Boolean): Boolean = !searchOpen

internal fun suppressPrimaryMForLibraryTextEntry(
    searchKeyboardOpen: Boolean,
    nativeAddKeyboardOpen: Boolean,
): Boolean = searchKeyboardOpen || nativeAddKeyboardOpen

@Composable
private fun MimirBrowseDestination(bindings: MimirAppBindings) {
    val core = bindings.core
    val library = bindings.library
    val navigation = bindings.navigation
    BackHandler(enabled = true) { navigation.destination = MimirDestination.HOME }
    AppShellScaffold(activeTab = ShellTab.BROWSE, onSelectTab = navigation::selectShellTab) {
        BrowseScreen(
            hiddenGames = library.browseVisible,
            platformLabels = browsePlatformLabels(library.browseVisible, library.platforms),
            searchQuery = library.browseSearch,
            keyboardOpen = library.browseKeyboardOpen,
            authority = core.focusAuthority,
            onRestore = library.restoreHidden,
            onSearchChange = library.setBrowseSearch,
            onOpenSearch = { library.setBrowseKeyboardOpen(true) },
            onClearSearch = { library.setBrowseSearch("") },
            onCloseKeyboard = { library.setBrowseKeyboardOpen(false) },
            onExit = { navigation.destination = MimirDestination.HOME },
            onKeyboardHostChanged = library.setBrowseKeyboardHost,
        )
    }
}

@Composable
private fun MimirSettingsOverlayHost(
    bindings: MimirAppBindings,
    active: MimirOverlayKind,
) {
    val core = bindings.core
    val settings = bindings.settings
    // The primary M control is a system overlay, so z-order always puts it above Compose. Suppress it while
    // the full-screen theme wall owns input; otherwise it masks the Baldr preview and looks like gallery UI.
    // The two appearance-image pickers are full-screen on the same opaque backing, so they suppress it too.
    if (active == MimirOverlayKind.THEME_PICKER ||
        active == MimirOverlayKind.BACKGROUND_PICKER ||
        active == MimirOverlayKind.MBUTTON_PICKER
    ) {
        val processServices = remember(core.context) { CompanionProcessRuntime.services(core.context) }
        DisposableEffect(processServices) {
            processServices.mButtonTopology.setPrimaryUiSuppressed(true)
            onDispose { processServices.mButtonTopology.setPrimaryUiSuppressed(false) }
        }
    }
    if (active == MimirOverlayKind.CONTROLLER_MAPPING) {
        val mapping by ControllerMappingStore.snapshot.collectAsState()
        ControllerMappingOverlay(
            authority = core.focusAuthority,
            rows = mapping.toSettingsRows(),
            status = mapping.status,
            initialFocusActionName = mapping.initialFocusAction?.name,
            pendingActionName = mapping.pendingAction?.let(::controllerActionTitle),
            exitConfirmationVisible = mapping.exitConfirmationVisible,
            onBeginCapture = settings.beginControllerMappingCapture,
            onCancelCapture = { ControllerMappingStore.cancelCapture() },
            onReset = settings.resetControllerMappingDraft,
            onApply = settings.applyControllerMapping,
            onDiscard = settings.discardControllerMapping,
            onContinueEditing = settings.continueControllerMapping,
            onDismiss = settings.closeControllerMapping,
        )
    }
    if (active == MimirOverlayKind.CONTROLLER_SHORTCUT_CAPTURE) {
        // Own predictive/gesture and OS Back at the composable boundary; physical Back still reaches
        // InputRouter, where the same capture is cancelled and its release is quarantined.
        BackHandler(enabled = true) { ControllerShortcutStore.cancelCapture() }
        val shortcutSnapshot by ControllerShortcutStore.snapshot.collectAsState()
        ControllerShortcutCaptureSheet(
            pendingAction = shortcutSnapshot.pendingAction,
            onDismiss = { ControllerShortcutStore.cancelCapture() },
        )
    }
    if (active == MimirOverlayKind.THEME_PICKER) {
        ThemePreviewGallery(
            authority = core.focusAuthority,
            scope = ThemePickerScope,
            currentThemeId = core.themeId,
            title = "Choose your look",
            subtitle = "Every screen takes on this palette.",
            dismissHint = LocalContext.current.getString(R.string.app_common_back),
            onPick = settings.commitTheme,
            onDismiss = settings.closeThemePicker,
            presenceManagedExternally = true,
        )
    }
    // Concept A : the Background and Mimir-button pickers, on the theme gallery's opaque backing.
    // Each takes its model and callbacks; the stage previews from a parameter, so nothing here previews globally.
    settings.backgroundPicker?.takeIf { active == MimirOverlayKind.BACKGROUND_PICKER }?.let { picker ->
        BackgroundPickerOverlay(
            authority = core.focusAuthority,
            state = picker,
            dualScreen = settings.appearancePickerDualScreen,
            onFocus = settings.backgroundPickerFocus,
            onTransition = settings.applyBackgroundPickerTransition,
            presenceManagedExternally = true,
            // The real shelf over the previewed ground: the Library's header and the player's own platform tiles,
            // scaled to the stage; none of it takes focus, the previewed ground is the picker's own bake.
            stageChrome = {
                BackgroundPickerLiveStageChrome(
                    authority = core.focusAuthority,
                    platforms = bindings.library.platforms,
                    gameCount = bindings.library.gameCount,
                )
            },
        )
    }
    settings.mimirButtonPicker?.takeIf { active == MimirOverlayKind.MBUTTON_PICKER }?.let { picker ->
        MimirButtonPickerOverlay(
            authority = core.focusAuthority,
            state = picker,
            onFocus = settings.mimirButtonPickerFocus,
            onTransition = settings.applyMimirButtonPickerTransition,
            presenceManagedExternally = true,
            // The real button, hosted in a non-focusable AndroidView; the previewed mark is a parameter, never global.
            stage = { preview -> MimirButtonPickerLiveStage(preview) },
            // Adjust mode's two coins (the large one to work on, the real 56 dp one) are the same native view, the placement pushed in place.
            adjustCoin = { mark, scale -> MimirButtonPickerAdjustCoin(mark, scale) },
        )
    }
    settings.platformPickerFor?.takeIf { active == MimirOverlayKind.PLATFORM_PICKER }?.let { platformId ->
        PlatformEmulatorPickerOverlay(
            authority = core.focusAuthority,
            platformLabel = bindings.library.platforms
                .firstOrNull { it.platformId == platformId }
                ?.label,
            choices = settings.platformPickerChoices,
            currentDefaultIdValue = bindings.settingsRead.platformDefaults[platformId]?.value,
            onChooseAuto = { settings.choosePlatformDefault(null) },
            onChoose = settings.choosePlatformDefault,
            onDismiss = settings.closePlatformPicker,
            presenceManagedExternally = true,
        )
    }
    if (active == MimirOverlayKind.BACKDROP_PICKER) {
        BackdropPickerOverlay(
            authority = core.focusAuthority,
            currentValue = settings.backdropValue,
            onChoose = settings.chooseBackdrop,
            onDismiss = settings.closeBackdropPicker,
            presenceManagedExternally = true,
        )
    }
    // AP01/CF01 — the accent picker for EVERY theme (live app-wide preview on focus; commit persists:
    // Default = curated stop, named theme = its own cool/warm temperature).
    if (active == MimirOverlayKind.ACCENT_PICKER) {
        com.gruning.mimir.rewrite.feature.settings.AccentPickerOverlay(
            authority = core.focusAuthority,
            themeId = core.themeId,
            onCommit = settings.commitAccent,
            onCommitTemp = settings.commitAccentTemp,
            onDismiss = settings.closeAccentPicker,
            presenceManagedExternally = true,
        )
    }
    // AP01 — the all-themes focus-highlight dial (L/R detents; previews every ring + the LED live).
    if (active == MimirOverlayKind.FOCUS_RING_DIAL) {
        com.gruning.mimir.rewrite.feature.settings.MimirColorDialOverlay(
            focusedStop = settings.focusDialStop,
            authoredPalette = com.gruning.mimir.rewrite.core.ui.MimirThemePalette.forId(core.themeId),
            onStepTo = settings.stepFocusDial,
            onCommit = settings.commitFocusRing,
            onDismiss = settings.closeFocusDial,
            presenceManagedExternally = true,
        )
    }
    // Settings › Music: the Source picker on the select shell, and the one shared
    // volume dial for Volume and Launch sound volume. Both ride this host's pixel fade like the dial above.
    // The slot also hosts the Order and Music hours menus (one open at a time, one focus scope): `musicPickerMenu` says which.
    if (active == MimirOverlayKind.MUSIC_SOURCE_PICKER) {
        when (settings.musicPickerMenu) {
            com.gruning.mimir.rewrite.feature.settings.MusicPickerMenu.SOURCE ->
                com.gruning.mimir.rewrite.feature.settings.MusicSourcePickerOverlay(
                    authority = core.focusAuthority,
                    current = settings.music.source,
                    customAvailable = settings.music.customSourceAvailable,
                    onSelect = settings.chooseMusicSource,
                    onDismiss = settings.closeMusicSourcePicker,
                    presenceManagedExternally = true,
                )
            com.gruning.mimir.rewrite.feature.settings.MusicPickerMenu.ORDER ->
                com.gruning.mimir.rewrite.feature.settings.MusicOrderPickerOverlay(
                    authority = core.focusAuthority,
                    current = settings.music.order,
                    onSelect = settings.chooseMusicOrder,
                    onDismiss = settings.closeMusicSourcePicker,
                    presenceManagedExternally = true,
                )
            com.gruning.mimir.rewrite.feature.settings.MusicPickerMenu.HOURS ->
                com.gruning.mimir.rewrite.feature.settings.MusicHoursPickerOverlay(
                    authority = core.focusAuthority,
                    current = settings.music.hours,
                    onSelect = settings.chooseMusicHours,
                    onDismiss = settings.closeMusicSourcePicker,
                    presenceManagedExternally = true,
                )
            com.gruning.mimir.rewrite.feature.settings.MusicPickerMenu.LAUNCH_SOUND ->
                com.gruning.mimir.rewrite.feature.settings.MusicLaunchSoundPickerOverlay(
                    authority = core.focusAuthority,
                    current = settings.music.launchSound,
                    onSelect = settings.chooseMusicLaunchSound,
                    onDismiss = settings.closeMusicSourcePicker,
                    presenceManagedExternally = true,
                )
        }
    }
    // Keyed on `active` alone, like the ring dial above: the session outlives the open flag by the exit fade, so
    // the outgoing dial keeps its words and its level while the host fades it, rather than popping to nothing.
    if (active == MimirOverlayKind.MUSIC_VOLUME_DIAL) settings.musicVolumeDial?.let { dial ->
        com.gruning.mimir.rewrite.feature.settings.MimirVolumeDialOverlay(
            title = dial.target.title,
            subtitle = dial.target.subtitle,
            levelPercent = dial.levelPercent,
            onStepTo = settings.stepMusicVolumeDial,
            onCommit = settings.commitMusicVolumeDial,
            onDismiss = settings.closeMusicVolumeDial,
            presenceManagedExternally = true,
        )
    }
    if (active == MimirOverlayKind.PROFILE_NAME) {
        BackHandler(enabled = true) { settings.setProfileNameEditing(false) }
        val keyboardHost = rememberMimirKeyboardHost(
            authority = core.focusAuthority,
            active = true,
            owner = MimirOverlayKind.PROFILE_NAME,
        )
        SideEffect { settings.uiState.settingsKeyboardHost = keyboardHost }
        DisposableEffect(Unit) {
            onDispose { settings.uiState.settingsKeyboardHost = null }
        }
        ProfileNameEditOverlay(
            currentName = core.profileName,
            onSave = { name ->
                ProfileNameStore.setName(core.context, name)
                settings.setProfileNameEditing(false)
            },
            onCancel = { settings.setProfileNameEditing(false) },
            controller = keyboardHost?.controller,
            keyDecorator = keyboardHost?.keyDecorator,
        )
    }
    bindings.navigation.gameTitleEdit?.takeIf { active == MimirOverlayKind.GAME_TITLE_EDIT }?.let { request ->
        BackHandler(enabled = true) { bindings.navigation.closeGameTitleEdit() }
        val keyboardHost = rememberMimirKeyboardHost(
            authority = core.focusAuthority,
            active = true,
            owner = MimirOverlayKind.GAME_TITLE_EDIT,
        )
        SideEffect { settings.uiState.settingsKeyboardHost = keyboardHost }
        DisposableEffect(Unit) {
            onDispose { settings.uiState.settingsKeyboardHost = null }
        }
        GameTitleEditOverlay(
            request = request,
            onSave = { gameId, title ->
                bindings.core.libraryViewModel.setTitleOverride(gameId, title)
                bindings.navigation.closeGameTitleEdit()
                // The Home launch card holds a SNAPSHOT GameRow captured at open() — never re-synced from
                // Room (unlike Library detail's reactive `gameDetail` StateFlow). Closing it here is a
                // no-op when the rename instead came from Library detail (`close()` returns early when no
                // session is open); when it came from Home, it prevents a stale title lingering on the
                // card until the player closes and reopens it.
                bindings.homeLaunchCard.close()
            },
            // Shared with the controller CONFIRM-on-reset shortcut in MimirAppInputDispatch.kt so the
            // touch and D-pad entry points can never drift onto different side effects (piece 3).
            onReset = { gameId -> resetGameTitleOverride(bindings, gameId) },
            onCancel = { bindings.navigation.closeGameTitleEdit() },
            controller = keyboardHost?.controller,
            keyDecorator = keyboardHost?.keyDecorator,
            authority = core.focusAuthority,
        )
    }
    if (active == MimirOverlayKind.STEAMGRIDDB_KEY) {
        val sgbKey by SteamGridDbKeyStore.key.collectAsState()
        var validationState by remember { mutableStateOf(SteamGridDbKeyValidationUiState.IDLE) }
        val validationScope = rememberCoroutineScope()
        BackHandler(enabled = true) { settings.setSteamGridDbKeyEditing(false) }
        val keyboardHost = rememberMimirKeyboardHost(
            authority = core.focusAuthority,
            active = true,
            owner = MimirOverlayKind.STEAMGRIDDB_KEY,
        )
        SideEffect { settings.uiState.settingsKeyboardHost = keyboardHost }
        DisposableEffect(Unit) {
            onDispose { settings.uiState.settingsKeyboardHost = null }
        }
        SteamGridDbKeyEditOverlay(
            currentKey = sgbKey,
            validationState = validationState,
            controller = keyboardHost?.controller,
            keyDecorator = keyboardHost?.keyDecorator,
            onSave = save@ { key ->
                if (validationState == SteamGridDbKeyValidationUiState.CHECKING) return@save
                if (key.isBlank()) {
                    if (SteamGridDbKeyStore.clear(core.context)) {
                        clearSteamGridDbKeyDraft()
                        settings.setSteamGridDbKeyEditing(false)
                    } else {
                        validationState = SteamGridDbKeyValidationUiState.STORAGE_FAILED
                    }
                    return@save
                }
                validationState = SteamGridDbKeyValidationUiState.CHECKING
                validationScope.launch {
                    when (CustomHeroFetcher.validateSteamGridDbKey(key)) {
                        SteamGridDbKeyValidationResult.ACCEPTED -> {
                            if (SteamGridDbKeyStore.setValidatedKey(core.context, key)) {
                                clearSteamGridDbKeyDraft()
                                settings.connectionFeedback.succeeded("appearance/enrichment/steamgriddb")
                                settings.setSteamGridDbKeyEditing(false)
                            } else {
                                validationState = SteamGridDbKeyValidationUiState.STORAGE_FAILED
                            }
                        }
                        SteamGridDbKeyValidationResult.REJECTED ->
                            validationState = SteamGridDbKeyValidationUiState.REJECTED
                        SteamGridDbKeyValidationResult.UNREACHABLE ->
                            validationState = SteamGridDbKeyValidationUiState.UNREACHABLE
                        SteamGridDbKeyValidationResult.PROVIDER_UNAVAILABLE ->
                            validationState = SteamGridDbKeyValidationUiState.PROVIDER_UNAVAILABLE
                    }
                }
            },
            onDraftChanged = {
                if (validationState != SteamGridDbKeyValidationUiState.CHECKING) {
                    validationState = SteamGridDbKeyValidationUiState.IDLE
                }
            },
            onCancel = { settings.setSteamGridDbKeyEditing(false) },
        )
    }
    if (active == MimirOverlayKind.RETROACHIEVEMENTS_KEY) {
        settings.retroAchievementsConnectUi?.let { connectUi ->
            BackHandler(enabled = true) { settings.retroAchievementsConnect.dismiss() }
            val keyboardHost = rememberMimirKeyboardHost(
                authority = core.focusAuthority,
                active = connectUi is RetroAchievementsConnectUi.EnterUsername ||
                    connectUi is RetroAchievementsConnectUi.EnterKey,
                owner = MimirOverlayKind.RETROACHIEVEMENTS_KEY,
            )
            SideEffect { settings.uiState.settingsKeyboardHost = keyboardHost }
            DisposableEffect(Unit) {
                onDispose { settings.uiState.settingsKeyboardHost = null }
            }
            RetroAchievementsKeyEditOverlay(
                state = connectUi,
                onUsernameEntered = settings.retroAchievementsConnect::usernameEntered,
                onValidate = settings.retroAchievementsConnect::submit,
                onRetry = settings.retroAchievementsConnect::retry,
                onClose = { settings.retroAchievementsConnect.dismiss() },
                controller = keyboardHost?.controller,
                keyDecorator = keyboardHost?.keyDecorator,
            )
        }
    }
    bindings.migration.state?.takeIf { active == MimirOverlayKind.MIGRATION }?.let { migrationState ->
        // Now rendered inline (policy 2026-08-12)
        // instead of inside a Compose Dialog, so this overlay no longer gets a Dialog window's own
        // DialogProperties.dismissOnBackPress registration for free — it needs its own BackHandler to keep
        // the platform Back gesture/button dismissing it, matching every sibling overlay in this host
        // (PROFILE_NAME, STEAMGRIDDB_KEY, GAME_TITLE_EDIT, ARTWORK_CONNECT, RETROACHIEVEMENTS_KEY). Enabled
        // unconditionally, same as those siblings; the no-op-while-non-interactive guard lives inside
        // migrationInteractive so Reading/Applying still swallow the press instead of leaking it further up.
        BackHandler(enabled = true) {
            if (migrationInteractive(migrationState)) bindings.migration.close()
        }
        MigrationImportOverlay(
            state = migrationState,
            authority = core.focusAuthority,
            onConfirm = bindings.migration.confirm,
            onClose = bindings.migration.close,
        )
    }
}

@Composable
private fun MimirArtworkOverlayHost(
    bindings: MimirAppBindings,
    active: MimirOverlayKind,
) {
    val artwork = bindings.artwork
    // QA-24: the connect overlay's stack membership is a bare `connectUi != null`, so losing the stack to
    // a higher-priority overlay hides this surface without clearing it -- and when that overlay closes,
    // ARTWORK_CONNECT becomes active again and re-presents whatever was left. A delivered outcome must not
    // come back that way. Keyed on `active` rather than on disposal: disposal also fires on ordinary
    // recomposition teardown, which would eat the card while the player is still reading it.
    LaunchedEffect(active) {
        if (active != MimirOverlayKind.ARTWORK_CONNECT) artwork.state.dismissDisplacedConnectOutcome()
    }
    artwork.state.connectUi?.takeIf { active == MimirOverlayKind.ARTWORK_CONNECT }?.let { connectUi ->
        BackHandler(enabled = true) { artwork.state.closeConnect() }
        val keyboardActive = connectUi is ArtworkConnectUi.EnterId || connectUi is ArtworkConnectUi.EnterPassword
        val keyboardHost = rememberMimirKeyboardHost(
            authority = bindings.core.focusAuthority,
            active = keyboardActive,
            owner = MimirOverlayKind.ARTWORK_CONNECT,
        )
        SideEffect { artwork.connectKeyboardHost = keyboardHost }
        DisposableEffect(Unit) {
            onDispose { artwork.connectKeyboardHost = null }
        }
        ArtworkConnectOverlay(
            state = connectUi,
            onIdEntered = artwork.state::submitAccountId,
            onPasswordEntered = { password -> artwork.state.submitPassword(connectUi, password) },
            onClose = {
                // Only a Done close is a successful connect; closing from EnterId/Failed is not.
                if (connectUi is ArtworkConnectUi.Done) {
                    bindings.settings.connectionFeedback.succeeded("appearance/enrichment/screenscraper")
                }
                artwork.state.closeConnect()
            },
            controller = keyboardHost?.controller,
            keyDecorator = keyboardHost?.keyDecorator,
        )
    }
    artwork.fetchUi
        ?.takeIf { active == MimirOverlayKind.ARTWORK_FETCH && !artwork.fetchEmbedded }
        ?.let { fetchUi ->
        BackHandler(enabled = true) { artwork.state.cancelOrCloseFetch() }
        ArtworkFetchOverlay(
            state = fetchUi,
            onCancel = artwork.state::cancelFetch,
            onClose = artwork.state::closeFetch,
            kind = artwork.state.fetchKind,
            onConfirm = artwork.state::answerNewGameEnrich,
            onDismiss = artwork.state::dismissNewGameEnrichPrompt,
            authority = bindings.core.focusAuthority,
        )
    }
    artwork.state.getArtUi?.takeIf { active == MimirOverlayKind.ARTWORK_GET }?.let { getArtUi ->
        BackHandler(enabled = true) { artwork.state.dismissGetArt() }
        GetArtOverlay(
            state = getArtUi,
            provider = artwork.state.getArtProvider,
            onConnect = artwork.state::connectFromGetArt,
            // pc_games' Connect pill must go where its own sentence points --
            // the SteamGridDB key entry, the SAME destination the existing Settings row opens
            // (MimirSettingsCoordinator.kt "appearance/enrichment/steamgriddb" row), never a new screen.
            onAddSteamGridDbKey = {
                artwork.state.addSteamGridDbKeyFromGetArt { bindings.settings.setSteamGridDbKeyEditing(true) }
            },
            onDismiss = artwork.state::dismissGetArt,
            authority = bindings.core.focusAuthority,
        )
    }
}

/**
 * The RetroAchievements card, opened by SELECT on a focused emulated game in Home or the gamelist.
 *
 * READ-ONLY by design: D-pad Up/Down turns the ledger page and Back closes it, but no row is focusable or
 * actionable. Mimir is a launcher — it cannot unlock an achievement, the emulator does. The one exception is the
 * NEEDS SETUP card's "Open Settings > Profile" action (product review 2026-09-28, F3): A or a tap opens Settings
 * on the RetroAchievements row, through the same overlay callback.
 *
 * The lane state is read INSIDE this composable rather than passed down from [MimirAppOverlayHost], so a
 * lookup landing (or the "checking…" beat) recomposes this card alone instead of the whole app shell.
 */
@Composable
private fun MimirAchievementCardOverlay(bindings: MimirAppBindings) {
    val achievements = bindings.achievements.state
    val listState = rememberLazyListState()
    val reducedMotion = mimirReducedMotion()
    val scrollRequest = achievements.cardScrollRequest
    LaunchedEffect(achievements.homeProbeEpoch) {
        val epoch = achievements.homeProbeEpoch
        achievements.homeProbeGame?.let { achievements.probeFromHome(it, epoch) }
    }
    LaunchedEffect(scrollRequest?.epoch) {
        scrollRequest?.let { request ->
            listState.mimirLedgerStep(
                forward = request.direction == MimirAchievementScrollDirection.DOWN,
                reducedMotion = reducedMotion,
            )
        }
    }
    BackHandler(enabled = true) { achievements.dismissCard() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            // Absorb every touch: the scrim itself never dismisses, matching the other app-shell modals.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        MimirLiquidGlassScrim()
        // Once the lane has loaded the game, Main shows the same rich page the Bottom's hub does (hero art, etched bar, every badge, details); until then, and for every
        // state that is not a loaded set, the ledger frame says what is true. One swap between them ([AchievementCardPageSwap], RC-33 / L-28).
        AchievementCardPageSwap(
            loaded = achievements.cardProgress(),
            reducedMotion = reducedMotion,
            ledger = {
                MimirAchievementCard(
                    state = achievements.cardState(),
                    platformIdValue = achievements.openForPlatformId,
                    listState = listState,
                    reducedMotion = reducedMotion,
                    onOpenProfile = bindings.overlays.onOpenRetroAchievementsSettings,
                    modifier = Modifier.padding(MimirSpacing.xl),
                )
            },
            rich = { progress ->
                MimirMainAchievementCard(
                    achievements = achievements,
                    progress = progress,
                    authority = bindings.core.focusAuthority,
                    reducedMotion = reducedMotion,
                    modifier = Modifier.padding(MimirSpacing.xl).widthIn(max = MainAchievementPageMaxWidth).fillMaxSize(),
                )
            },
        )
    }
}

/**
 * The ledger frame handing over to the rich page (RC-33 / L-28): ONE swap on the swap tier (the departing page on [MimirMotion.OverlayMs],
 * the arriving one after the 130 ms lead), keyed on whether a set has loaded. It was an `if`, so the "checking" frame was replaced by the
 * full page in a single frame (a cut) the moment the lane answered. The loaded set is itself the target state, so the page that is
 * leaving keeps painting what it was given; a refresh of the same loaded set (same key) updates in place and plays nothing. Centred, like
 * the Box it sits in: the smaller ledger frame stays on the middle of the resizing box.
 */
@Composable
internal fun <T : Any> AchievementCardPageSwap(
    loaded: T?,
    reducedMotion: Boolean,
    ledger: @Composable () -> Unit,
    rich: @Composable (T) -> Unit,
) {
    AnimatedContent(
        targetState = loaded,
        contentKey = { it != null },
        transitionSpec = { mimirSwapTransform(reducedMotion) },
        contentAlignment = Alignment.Center,
        label = "achievement-card-page",
    ) { page ->
        if (page != null) rich(page) else ledger()
    }
}

@Composable
private fun MimirCompanionPermissionOverlay(bindings: MimirAppBindings) {
    BackHandler(enabled = true) { CompanionRecentsState.hide() }
    CompanionOverlayPermissionPrompt(
        authority = bindings.core.focusAuthority,
        onGrant = { bindings.core.companionOverlayController?.requestOverlayPermission() },
        onDismiss = { CompanionRecentsState.hide() },
    )
}

@Composable
private fun MimirPrimaryReturnCover(bindings: MimirAppBindings, requested: Boolean) {
    // Composed ALWAYS and gated by [requested] (= the cover is the top overlay): see MimirReturnCoverSurface for why the
    // cover must outlive its own request by its one-frame beat and its fade, and why its input does not.
    MimirReturnCoverSurface(requested = requested) { MimirPrimaryReturnWaitStatus(bindings, requested) }
}

@Composable
private fun MimirCoordinatorAmbientMotionProvider(
    interactionMotionGate: InteractionMotionGate,
    content: @Composable () -> Unit,
) {
    val phase by interactionMotionGate.phase.collectAsState()
    CompositionLocalProvider(LocalMimirAmbientMotionPhase provides phase, content = content)
}
