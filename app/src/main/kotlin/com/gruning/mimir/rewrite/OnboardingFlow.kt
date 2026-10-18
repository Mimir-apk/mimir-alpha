package com.gruning.mimir.rewrite

import android.content.res.Resources
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.testTag
import com.gruning.mimir.rewrite.core.ui.MimirControllerHintLabels
import com.gruning.mimir.rewrite.core.ui.MimirMannazBadge
import com.gruning.mimir.rewrite.core.ui.LocalMimirControllerHintLabels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.input.mimirActivatable
import com.gruning.mimir.rewrite.core.input.mimirFocusable
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.model.library.ScanOutcome
import com.gruning.mimir.rewrite.core.resolver.library.PlatformLabels
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import androidx.compose.material3.CircularProgressIndicator
import com.gruning.mimir.rewrite.core.model.jobs.ScanPhase
import com.gruning.mimir.rewrite.core.model.jobs.ScanProgress
import com.gruning.mimir.rewrite.core.model.jobs.ScanProgressState
import com.gruning.mimir.rewrite.core.ui.MediaArtFallbackGlyph
import com.gruning.mimir.rewrite.core.ui.MimirAnimatedChromeText
import com.gruning.mimir.rewrite.core.ui.MimirBeforeFirstValidViewport
import com.gruning.mimir.rewrite.core.ui.MimirBoxArt
import com.gruning.mimir.rewrite.core.ui.MimirBoxArtCorner
import com.gruning.mimir.rewrite.core.ui.MimirFilledControlRingReach
import com.gruning.mimir.rewrite.core.ui.MimirFocusFlareReach
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.MimirControlHintText
import com.gruning.mimir.rewrite.core.ui.rememberMimirFocusGameGleam
import com.gruning.mimir.rewrite.core.ui.MimirFocusRestingAlpha
import com.gruning.mimir.rewrite.core.ui.MimirHintSwap
import com.gruning.mimir.rewrite.core.ui.MimirIndeterminateBar
import com.gruning.mimir.rewrite.core.ui.MimirIntentText
import com.gruning.mimir.rewrite.core.ui.MimirKeyboard
import com.gruning.mimir.rewrite.core.ui.MimirLiquidGlassPanel
import com.gruning.mimir.rewrite.core.ui.mimirFocusRingMs
import com.gruning.mimir.rewrite.core.ui.mimirGlassPanelEntry
import com.gruning.mimir.rewrite.core.ui.mimirHintPillColors
import com.gruning.mimir.rewrite.core.ui.MimirHintKeycap
import com.gruning.mimir.rewrite.core.ui.MimirMannazPin
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirShelfBackground
import com.gruning.mimir.rewrite.core.ui.MimirTextFragment
import com.gruning.mimir.rewrite.core.ui.MimirTextIntent
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.MimirViewportPosture
import com.gruning.mimir.rewrite.core.ui.ctaMaterial
import com.gruning.mimir.rewrite.core.ui.MimirGlassMotion
import com.gruning.mimir.rewrite.core.ui.MimirRingGleam
import com.gruning.mimir.rewrite.core.ui.MimirRingShape
import com.gruning.mimir.rewrite.core.ui.mimirFocusTileRing
import com.gruning.mimir.rewrite.core.ui.mimirSelectionRing
import com.gruning.mimir.rewrite.core.ui.MimirEtchedChipLabelStyle
import com.gruning.mimir.rewrite.core.ui.etchedChipInk
import com.gruning.mimir.rewrite.core.ui.mimirEtchedChipSurface
import com.gruning.mimir.rewrite.core.ui.mimirGlassBackdropProgress
import com.gruning.mimir.rewrite.core.ui.mimirGlassScrimColor
import com.gruning.mimir.rewrite.core.ui.mimirMeasuredViewport
import com.gruning.mimir.rewrite.core.ui.mimirCrossSwapFadeAlpha
import com.gruning.mimir.rewrite.core.ui.mimirCrossSwapSpec
import com.gruning.mimir.rewrite.core.ui.mimirReducedMotion
import com.gruning.mimir.rewrite.core.ui.MimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.mimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.platformConsoleIconBrush
import com.gruning.mimir.rewrite.core.ui.rememberMeasuredViewport
import com.gruning.mimir.rewrite.feature.library.GamelistRow
import com.gruning.mimir.rewrite.feature.settings.MimirKeyboardPickerEntry
import com.gruning.mimir.rewrite.feature.settings.ThemePreviewGallery
import com.gruning.mimir.rewrite.feature.settings.dispatchThemeGallery
import com.gruning.mimir.rewrite.core.ui.theme.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.theme.MimirType
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import com.gruning.mimir.rewrite.core.ui.mimirRingSafeFade
import com.gruning.mimir.rewrite.core.ui.mimirRingSafeFadeAlpha
import com.gruning.mimir.rewrite.core.ui.mimirSheetSpec
import com.gruning.mimir.rewrite.core.ui.holdMimirInputUntilShown

/**
 * MIMIR-REWRITE — the first-run ONBOARDING flow (C4).
 *
 * ONE [MimirDestination.ONBOARDING] destination hosting an internal step machine, entered on a genuine first
 * run ([OnboardingStore.isFirstRun]) and exited via [OnboardingStore.markComplete] → the shelf. It is a HARD
 * gate: while open it owns ALL routed input — START can’t escape to Settings (router carve-out in `MimirApp`)
 * and Back can’t drop to an empty shelf ([OnboardingFlow]'s root [BackHandler]). Onboarding runs BEFORE theme
 * selection, so every step renders in the initial DEFAULT/gold palette.
 *
 * Ordered flow: `Welcome → Profile → Access → Library → Metadata → Settling → Theme →
 * Done → HOME`. Access and connected artwork remain optional; the library grant remains the only required
 * user choice. Settling reports the host's real work instead of handing an unfinished library to the shelf.
 *
 * INVARIANTS: single [FocusAuthority] via [mimirFocusable] under [OnboardingScope] — never Compose
 * `focusable()`; the in-app keyboard ([MimirKeyboard]) answers BOTH touch and the controller —
 * `dispatchMimirKeyboardInput` walks between keys, CONFIRM types one, L2 shifts and R2 cycles the
 * character set, which is what lets the FAVORITES search field exist on a
 * device with no screen keyboard; all modals render INLINE (never a Compose `Dialog`, which would
 * miss the app's routed input); all colour reads from [LocalMimirPalette] (default/gold before theme select).
 */

/** The focus scope onboarding owns while open — the welcome/profile step controls register here. */
val OnboardingScope = FocusScope("onboarding")

/** The empty-name confirm pushes its OWN scope so its nav can’t reach the profile buttons composed behind it. */
internal val OnboardingConfirmScope = FocusScope("onboarding/confirm")

/**
 * Onboarding steps in their persisted traversal order. Keep declaration order aligned with
 * [nextOnboardingStep]; [OnboardingStore] persists these names across process death.
 */
enum class OnboardingStep { WELCOME, PROFILE, ACCESS, LIBRARY, METADATA, SETTLING, THEME, FAVORITES, DONE }

/**
 * Identification still running when favourites is showing: the Tome waits to avoid opening an
 * empty list while hashing continues. The
 * the page keeps showing live progress rather than freezing. [done]/[total] come from the running sweep; a
 * null [total] means no honest denominator yet. Since 2026-09-28 the wait is capped at
 * [OnboardingFavoritesWaitCapMillis] (a 5,529-game library previously waited for minutes).
 */
data class OnboardingFavoritesIdentifying(val done: Int, val total: Int?)

/**
 * How long the favourites Tome waits for identification before it opens anyway: about 30 s,
 * long enough for a small library to finish and auto-open, but short enough that a 5,529-game
 * library does not hold the
 * page. Identification continues in the background either way.
 */
internal const val OnboardingFavoritesWaitCapMillis = 30_000L

/** The intro's line once the cap has released the Tome while identification carries on. */
internal fun onboardingFavoritesBackgroundLine(res: Resources): String =
    res.getString(R.string.app_onboarding_favorites_background)

/**
 *  The intro said "Your Tome opens when it finishes" and
 * only unlocked the button. The Tome now opens by itself on the one transition that keeps that promise: the
 * wait ends ([wasIdentifying] -> not [identifyingNow]) having identified games ([identifiedWhileWaiting]),
 * while the intro is still the page. An already-identified library keeps its intro, and closing the Tome later
 * never reopens it, because the wait does not end twice.
 */
internal fun onboardingFavoritesAutoOpensTome(
    wasIdentifying: Boolean,
    identifyingNow: Boolean,
    identifiedWhileWaiting: Boolean,
    page: OnboardingFavoritesPage,
): Boolean = wasIdentifying && !identifyingNow && identifiedWhileWaiting && page == OnboardingFavoritesPage.INTRO

internal fun onboardingFavoritesIdentifyingLine(res: Resources, identifying: OnboardingFavoritesIdentifying): String =
    identifying.total?.takeIf { it > 0 }?.let { total ->
        res.getString(R.string.app_onboarding_favorites_identifying_counted, identifying.done.coerceIn(0, total), total)
    } ?: res.getString(R.string.app_onboarding_favorites_identifying)

internal fun onboardingActionsStack(availableWidthDp: Float): Boolean = availableWidthDp < 420f

internal fun onboardingKeyboardPreviewCompact(viewportHeightDp: Float): Boolean = viewportHeightDp <= 420f

/** Live Android capability state shown by the optional access step. */
data class OnboardingAccessUi(
    val usageAvailable: Boolean,
    val usageGranted: Boolean,
    val homeRoleAvailable: Boolean,
    val homeRoleHeld: Boolean,
    /** "All files access" : the primary lane for saving artwork beside ROMs. */
    val filesGranted: Boolean = false,
    /**
     * The "Draw over other apps" permission has its own onboarding page. This is a **hard**
     * precondition for the floating M on EVERY topology, not just single-screen: `selectMButtonOverlayTarget`
     * (`MButtonRuntime.kt:184`) returns null on `!overlayPermissionGranted` as its FIRST statement, before the
     * presentation-display fork that yields SECONDARY_COMPANION — so the Thor's bottom-screen M needs it too.
     *
     * Defaults `true` (nothing to grant) so every pre-existing fixture that never mentions the overlay
     * resolves for free.
     */
    val overlayGranted: Boolean = true,
    /**
     * Whether this device shows the Mimir button on a second screen (the Thor), where it never needs Usage access
     * (`selectMButtonOverlayTarget` returns the second-screen companion before it reads usage). USAGE words itself
     * by this (topology-specific copy): one string per setup, so two setups can
     * never be told the same wrong thing. The host fills it from the same topology signal FINISH's legend uses.
     * Defaults `false`, the one-screen words, which are what every handheld but the Thor reads.
     */
    val hasSecondaryDisplay: Boolean = false,
)

/** Each optional capability must be consciously granted or deferred before this step can advance. */
internal enum class OnboardingAccessCapability { FILES, USAGE, HOME_ROLE }

/** Ephemeral UI review state: a process restart deliberately asks again rather than silently carrying a skip. */
internal class OnboardingAccessReviewState {
    private val deferred = mutableStateListOf<OnboardingAccessCapability>()

    /** QA-10: OVERLAY's own "Not now" flag -- an ephemeral per-run review flag, not a persisted setting. */
    var overlayDeferred by mutableStateOf(false)
        private set

    /**
     * Which screen of the one-at-a-time sub-sequence is on stage.
     * Null means "not yet landed" — [onboardingAccessCurrentScreen] then picks the first unresolved screen,
     * same "land on the first unresolved action" philosophy the old stacked rows used for focus. Anchored by
     * screen identity, not a list index, so a screen added to or removed from the sequence never shifts anyone
     * already mid-sequence.
     * Deliberately NOT persisted — same ephemeral-review shape as [deferred]/[overlayDeferred]: a
     * process restart re-lands on the first unresolved screen rather than restoring a stale position.
     */
    var currentScreen by mutableStateOf<OnboardingAccessScreenId?>(null)
        private set

    fun defer(capability: OnboardingAccessCapability) {
        if (capability !in deferred) deferred += capability
    }

    fun isDeferred(capability: OnboardingAccessCapability): Boolean = capability in deferred

    /** QA-10: OVERLAY's own "Not now" -- an ephemeral per-run flag, like [defer]. */
    fun deferOverlay() {
        overlayDeferred = true
    }

    fun isOverlayDeferred(): Boolean = overlayDeferred

    fun goToScreen(id: OnboardingAccessScreenId) {
        currentScreen = id
    }
}

internal fun onboardingAccessCapabilities(access: OnboardingAccessUi): List<OnboardingAccessCapability> = buildList {
    add(OnboardingAccessCapability.FILES)
    add(OnboardingAccessCapability.USAGE)
    add(OnboardingAccessCapability.HOME_ROLE)
}

/**
 * O-2 : the stacked ACCESS step split into one screen at a time.
 * ORIENTATION is the one-time, non-permission preamble (Screen 0);
 * the capability screens always appear in the defined order.
 *
 * Optional asks are deferred: the two asks
 * only Utility and Tweaker use left setup and are asked where they are used. WRITE_SETTINGS (joined on the run-10
 * walk, 2026-09-18, which this reverses) is Utility's footer again, which now carries the page's
 * manual-setup instruction ([UtilityAutoBrightnessMessage]). TWEAKER_USAGE (the permanent last screen
 * since 2026-08-20) is the Tweaker's "Tweaker usage access" row, which now carries the page's one instruction,
 * "In Android's list, pick Mimir Tweaker, not Mimir." Setup is four asks.
 *
 * KEYBOARD ("Set Mimir as your keyboard") briefly trailed the list as a seventh screen; it was
 * retired from onboarding on 2026-09-17. It was not an OS-mediated grant Mimir could request, so the
 * screen could only send the player into Android's own settings and hope they came back. The Settings row
 * (`controls/keyboard`) still offers it, which is where a keyboard choice belongs.
 *
 * SPECIAL_DEVICE_ACCESS ("Built-in advanced controls") sat after HOME_ROLE until it was
 * removed on 2026-09-29. Its consent is implicit on every certified vendor (2026-09-11), so it never drew a
 * button: the Thor, the Nova and the Odin 3 arrived already Allowed, every other device read "unavailable", and on
 * the one-screen handhelds it promised a second screen they do not have. Settings and Utility still show the
 * vendor's status, which is where it belongs.
 *
 * Deliberately a SEPARATE ordering enum from [OnboardingAccessCapability], not a superset of it: the
 * completeness gate ([onboardingAccessComplete]) and the review/defer bookkeeping stay defined in terms of
 * the real capabilities exactly as before this ticket. This enum only orders *screens* — one extra
 * (ORIENTATION) that isn't a capability at all, and OVERLAY, which stays outside [OnboardingAccessCapability].
 */
internal enum class OnboardingAccessScreenId {
    ORIENTATION,
    FILES,
    // QA-10, policy (see [OnboardingAccessUi.overlayGranted]): OVERLAY and USAGE sit side by side because
    // the two together are exactly the Mimir button's preconditions -- `selectMButtonOverlayTarget` reads
    // `overlayPermissionGranted` then `usageAccessGranted` and nothing else. OVERLAY comes FIRST:
    // it names the Mimir button, which USAGE's one-screen line then refers to. The
    // enum order is the screen order: the step glide reads `ordinal` for its direction. Not a member of
    // [OnboardingAccessCapability]: see [OnboardingAccessScreenId.asCapability]'s doc.
    OVERLAY,
    USAGE,
    HOME_ROLE,
}

/**
 * The ordered screens for THIS render of the access sub-sequence: ORIENTATION and four asks, the same on every
 * device, so the progress cue's denominator ([onboardingAccessProgressLabel]) is STABLE at 4. [access] is kept so
 * this stays the one call every screen-list consumer uses. The count read 7 while KEYBOARD trailed the list, 6
 * once it left (2026-09-17), 7 again when WRITE_SETTINGS joined (run 10, 2026-09-18), 6 when the built-in
 * controls screen went (2026-09-29), and 4 since OR-12 moved WRITE_SETTINGS and TWEAKER_USAGE to Utility and the
 * Tweaker (2026-09-29).
 */
internal fun onboardingAccessScreens(access: OnboardingAccessUi): List<OnboardingAccessScreenId> = buildList {
    add(OnboardingAccessScreenId.ORIENTATION)
    add(OnboardingAccessScreenId.FILES)
    add(OnboardingAccessScreenId.OVERLAY)
    add(OnboardingAccessScreenId.USAGE)
    add(OnboardingAccessScreenId.HOME_ROLE)
}

/** Each access sub-screen gets its own [OnboardingFocusSurface] — see that enum's ACCESS_* block for why. */
internal fun onboardingAccessFocusSurface(id: OnboardingAccessScreenId): OnboardingFocusSurface = when (id) {
    OnboardingAccessScreenId.ORIENTATION -> OnboardingFocusSurface.ACCESS_ORIENTATION
    OnboardingAccessScreenId.FILES -> OnboardingFocusSurface.ACCESS_FILES
    OnboardingAccessScreenId.USAGE -> OnboardingFocusSurface.ACCESS_USAGE
    OnboardingAccessScreenId.OVERLAY -> OnboardingFocusSurface.ACCESS_OVERLAY
    OnboardingAccessScreenId.HOME_ROLE -> OnboardingFocusSurface.ACCESS_HOME_ROLE
}

/** True once [id]'s own row/screen has been consciously resolved — granted OR deferred. ORIENTATION has no
 * grant/defer choice of its own, so it resolves the instant it is left behind (see [onboardingAccessAdvance]). */
internal fun onboardingAccessScreenGranted(
    id: OnboardingAccessScreenId,
    access: OnboardingAccessUi,
): Boolean = when (id) {
    OnboardingAccessScreenId.ORIENTATION -> false
    OnboardingAccessScreenId.FILES -> access.filesGranted
    OnboardingAccessScreenId.USAGE -> access.usageGranted
    OnboardingAccessScreenId.OVERLAY -> access.overlayGranted
    OnboardingAccessScreenId.HOME_ROLE -> access.homeRoleHeld
}

internal fun onboardingAccessScreenDeferred(
    id: OnboardingAccessScreenId,
    review: OnboardingAccessReviewState,
): Boolean = when (id) {
    OnboardingAccessScreenId.ORIENTATION -> false
    OnboardingAccessScreenId.FILES -> review.isDeferred(OnboardingAccessCapability.FILES)
    OnboardingAccessScreenId.USAGE -> review.isDeferred(OnboardingAccessCapability.USAGE)
    OnboardingAccessScreenId.OVERLAY -> review.isOverlayDeferred()
    OnboardingAccessScreenId.HOME_ROLE -> review.isDeferred(OnboardingAccessCapability.HOME_ROLE)
}

internal fun onboardingAccessScreenResolved(
    id: OnboardingAccessScreenId,
    access: OnboardingAccessUi,
    review: OnboardingAccessReviewState,
): Boolean = onboardingAccessScreenGranted(id, access) || onboardingAccessScreenDeferred(id, review)

/**
 * The screen actually on stage: the stored anchor if it still exists in this render's [screens], else the
 * first unresolved screen — the same "land on the first unresolved action, never Continue" philosophy the
 * old stacked screen used for focus, now used for screen position — else the last screen (every screen
 * resolved; nothing left to land on but the tail).
 *
 * The anchor-vanished branch below is now DEAD IN PRODUCTION: [onboardingAccessScreens] (since the
 * 2026-08-20 "Tweaker is always bundled" fix) returns the same list unconditionally, so `screens`
 * can no longer change shape under an anchored player. Left in place rather than deleted — it is still
 * correct defensive code and this function has no other caller that could reintroduce a variable-length
 * `screens`, but removing it is a separate, deliberate call this lane did not make.
 *
 * RC-13 (`Request Home, restart app and put you on the beginning of on-boarding`): the ACTUALLY LIVE null-
 * anchor branch below used to have the identical hazard the (dead) anchor-vanished branch above already
 * guards against, just unguarded. [OnboardingAccessReviewState.currentScreen] is deliberately ephemeral, so
 * a genuine Activity/process recreation mid-sequence (the HOME_ROLE grant relaunching the app is exactly
 * this) wipes the anchor back to null. ORIENTATION never resolves via grant/defer (it isn't a capability),
 * so an unguarded "first unresolved" scan always re-picks it — regressing an already-five-screens-deep
 * player all the way back to the one-time preamble on every relaunch, forever. [orientationAlreadySeen] is
 * the durable signal (persisted via [OnboardingStore.markAccessOrientationSeen]) that tells a genuinely
 * fresh entry into ACCESS apart from a recovered one, so it excludes ORIENTATION from the null-anchor scan
 * exactly the way the vanished-anchor branch already excludes it. Deliberately NOT solved by persisting the
 * anchor itself instead: [OnboardingAccessReviewState]'s `deferred` list is wiped by the same recreation,
 * so restoring a stored anchor past FILES/USAGE would strand any capability the player had only DEFERRED
 * (not granted) behind the recovered position — permanently unresolved, since nothing would ever revisit
 * it — and [onboardingAccessAdvance] silently no-ops at the last screen once nothing left is "next," which
 * is a soft-lock. A capability the player only deferred earlier being re-offered after a real
 * process/Activity recreation is accepted, pre-existing behaviour, not a new cost this fix introduces — the
 * same ephemeral-review philosophy already governs Back (`OnboardingFlow.kt`'s "Revisiting a skipped
 * screen" ruling) and a full process death always re-asked before this ticket existed.
 */
internal fun onboardingAccessCurrentScreen(
    screens: List<OnboardingAccessScreenId>,
    anchor: OnboardingAccessScreenId?,
    access: OnboardingAccessUi,
    review: OnboardingAccessReviewState,
    orientationAlreadySeen: Boolean = false,
): OnboardingAccessScreenId? {
    if (anchor != null) {
        if (anchor in screens) return anchor
        // DEAD IN PRODUCTION (see function doc): this used to fire when the conditional TWEAKER_USAGE screen
        // dropped back out from under an anchored player. screens is invariant now, so anchor is always in
        // screens when non-null. Kept as defensive recovery, not deleted.
        return screens.firstOrNull {
            it != OnboardingAccessScreenId.ORIENTATION && !onboardingAccessScreenResolved(it, access, review)
        } ?: screens.lastOrNull()
    }
    // No anchor ever stored: either a genuinely fresh, never-visited entry into ACCESS this session (land on
    // ORIENTATION, exactly as before this ticket), or — RC-13 — a player recovering mid-sequence after their
    // ephemeral anchor was wiped by a real relaunch. [orientationAlreadySeen] tells the two apart; only the
    // genuinely-fresh case leaves ORIENTATION eligible to be picked as "first unresolved."
    val candidates = if (orientationAlreadySeen) {
        screens.filter { it != OnboardingAccessScreenId.ORIENTATION }
    } else {
        screens
    }
    return candidates.firstOrNull { !onboardingAccessScreenResolved(it, access, review) } ?: screens.lastOrNull()
}

/** The next screen after [current], or null when [current] is already the last screen in [screens]. */
internal fun onboardingAccessNextScreen(
    screens: List<OnboardingAccessScreenId>,
    current: OnboardingAccessScreenId,
): OnboardingAccessScreenId? {
    val index = screens.indexOf(current)
    if (index < 0 || index == screens.lastIndex) return null
    return screens[index + 1]
}

/** The previous screen before [current], or null when [current] is already the first — Back is swallowed there. */
internal fun onboardingAccessPreviousScreen(
    screens: List<OnboardingAccessScreenId>,
    current: OnboardingAccessScreenId,
): OnboardingAccessScreenId? {
    val index = screens.indexOf(current)
    if (index <= 0) return null
    return screens[index - 1]
}

/**
 * Back within the sub-sequence: steps [review] to the previous screen and returns `true`, or does nothing
 * and returns `false` on the first screen (ORIENTATION) — swallowed exactly like the flow's outer forward-only
 * boundary, per spec §6. Callers use the `false` return to fall through to whatever "no more back" already
 * means at their layer (a no-op for the controller dispatch branch; `enabled = false` on the sub-sequence's
 * own [BackHandler] for the touch/gesture path).
 */
internal fun onboardingAccessGoBack(
    screens: List<OnboardingAccessScreenId>,
    current: OnboardingAccessScreenId,
    review: OnboardingAccessReviewState,
    focusIntent: OnboardingFocusIntentState,
): Boolean {
    val previous = onboardingAccessPreviousScreen(screens, current) ?: return false
    review.goToScreen(previous)
    focusIntent.clear(onboardingAccessFocusSurface(current))
    return true
}

/**
 * ORIENTATION is excluded from the count on purpose — it states a boundary, it is not one of the "N" asks
 * the cue exists to count down (spec §0: "not a permission ask"). The cue only appears from the first real
 * ask onward, reading "1 of 4" through "4 of 4" — the denominator is derived every render from [screens],
 * never hardcoded. The four asks are FILES, OVERLAY, USAGE and HOME_ROLE (OR-12, 2026-09-29; see
 * [onboardingAccessScreens] for how the count got here). Kept derived from [screens] rather than
 * hardcoded so a screen added or removed updates this cue for free — which is exactly why none of those
 * changes needed an edit here.
 *
 * Since 2026-09-28 the cue also names the page Android opens, in Android's words (policy
 * section 4 item 2): "3 of 4 · Usage access", drawn as it is (sentence case). A screen
 * whose grant opens no Android page would keep the bare count; see [onboardingAccessAndroidLabel].
 */
internal fun onboardingAccessProgressLabel(
    res: Resources,
    screens: List<OnboardingAccessScreenId>,
    current: OnboardingAccessScreenId,
): String? {
    val counted = screens.filter { it != OnboardingAccessScreenId.ORIENTATION }
    val position = counted.indexOf(current)
    if (position < 0) return null
    // One format per shape, never the count and the page name joined in code: a language may order them differently.
    return onboardingAccessAndroidLabel(res, current)?.let {
        res.getString(R.string.app_onboarding_access_progress_named, position + 1, counted.size, it)
    } ?: res.getString(R.string.app_onboarding_progress_of, position + 1, counted.size)
}

/**
 * The name Android gives the page each permission opens, spelled as AOSP Settings spells it, so the player can
 * recognise that page (R1). The widest, "2 of 4 · Display over other apps", is about 350 dp at font scale 2.0
 * (IBM Plex Sans SemiBold, MicroStatus), one line on every STANDARD viewport.
 *
 * Null where Android opens no page: ORIENTATION asks for nothing. The built-in controls screen, which went through
 * the vendor's own route and so had no Android label either, was removed on 2026-09-29.
 */
internal fun onboardingAccessAndroidLabel(res: Resources, id: OnboardingAccessScreenId): String? = when (id) {
    OnboardingAccessScreenId.ORIENTATION -> null
    OnboardingAccessScreenId.FILES -> res.getString(R.string.app_onboarding_access_android_files)
    OnboardingAccessScreenId.OVERLAY -> res.getString(R.string.app_onboarding_access_android_overlay)
    OnboardingAccessScreenId.USAGE -> res.getString(R.string.app_onboarding_access_android_usage)
    OnboardingAccessScreenId.HOME_ROLE -> res.getString(R.string.app_onboarding_access_android_home)
}

/**
 * Advance the sub-sequence from [current]: to the next screen if one exists, or — [current] being the last
 * screen — out of the whole ACCESS step via [onAdvance] IF every capability is now resolved. Shared by
 * "Not now" (defer, then move on) and the per-screen "Continue" (already resolved, just move on): both are
 * "leave this screen behind" and differ only in whether they mutate [review] first.
 */
internal fun onboardingAccessAdvance(
    screens: List<OnboardingAccessScreenId>,
    current: OnboardingAccessScreenId,
    review: OnboardingAccessReviewState,
    focusIntent: OnboardingFocusIntentState,
    access: OnboardingAccessUi,
    onAdvance: () -> Unit,
) {
    val next = onboardingAccessNextScreen(screens, current)
    if (next != null) {
        review.goToScreen(next)
        focusIntent.clear(onboardingAccessFocusSurface(current))
        return
    }
    if (onboardingAccessComplete(access, review)) onAdvance()
}

internal fun onboardingAccessGranted(
    capability: OnboardingAccessCapability,
    access: OnboardingAccessUi,
): Boolean = when (capability) {
    OnboardingAccessCapability.FILES -> access.filesGranted
    OnboardingAccessCapability.USAGE -> access.usageGranted
    OnboardingAccessCapability.HOME_ROLE -> access.homeRoleHeld
}

internal fun onboardingAccessNeedsReview(
    capability: OnboardingAccessCapability,
    access: OnboardingAccessUi,
    review: OnboardingAccessReviewState,
): Boolean = !onboardingAccessGranted(capability, access) && !review.isDeferred(capability)

/** The host-owned phase currently preparing the first shelf. */
enum class OnboardingSettlementPhase { INDEXING, IDENTIFYING, READY, LIMITED, FAILED }

/** Honest, render-only progress for the settling step. Counts are supplied by the orchestration layer. */
data class OnboardingSettlementUi(
    val phase: OnboardingSettlementPhase,
    val title: String,
    val detail: String,
    val done: Int = 0,
    /** Null means the active worker has no exact denominator; render an honest indeterminate state. */
    val total: Int? = null,
    val matched: Int = 0,
    val canRetry: Boolean = false,
    val canContinue: Boolean = false,
    /** Resets the display fraction when a fresh worker run begins. */
    val progressRunId: Long? = null,
)

// ── Focus keys (all in [OnboardingScope]) ──
private val OnboardingBeginKey = FocusKey("onboarding/welcome/begin")
internal val OnboardingContinueKey = FocusKey("onboarding/profile/continue")
private val OnboardingNotNowKey = FocusKey("onboarding/profile/notNow")
// The name field was touch-only by construction (no FocusKey, no mimirFocusable) — a controller-only player
// could never reach it.  Ordered FIRST in the PROFILE keys list (below) so NAV_UP/NAV_LEFT from Continue reaches it and
// NAV_DOWN/NAV_RIGHT leaves it — see `dispatchOnboarding`'s PROFILE branch.
internal val OnboardingNameFieldKey = FocusKey("onboarding/profile/name")
internal val OnboardingConfirmKeepKey = FocusKey("onboarding/profile/confirm/keep")
internal val OnboardingConfirmContinueKey = FocusKey("onboarding/profile/confirm/continue")
private val OnboardingFilesAccessKey = FocusKey("onboarding/access/files")
private val OnboardingUsageAccessKey = FocusKey("onboarding/access/usage")

// QA-16: the favourites step. Per-game keys are derived from the game id so a list that re-orders
// underneath the player (enrichment still landing titles) keeps focus on the same GAME, not the same
// index -- the identity-anchored rule the ACCESS sub-sequence already follows for screens.
internal fun onboardingFavoriteGameKey(gameId: String) = FocusKey("onboarding/favorites/game/$gameId")
internal val OnboardingFavoritesContinueKey = FocusKey("onboarding/favorites/continue")

/**
 * The button that opens the Tome, and the reason the step’s first page has no games on it at all.
 *
 *
 *
 * The complaint is arithmetic, not taste. Measured on the Thor (1080x1920 at density 369, so 832x468dp
 * in landscape) the old single-page layout spent 294 of its 468dp on header, eyebrow, title, body, count
 * line and rail, leaving 174dp for a grid whose rows were 208dp tall. The step that exists to fill the
 * Home shelf could not show one complete row of it.
 */
internal val OnboardingFavoritesOpenTomeKey = FocusKey("onboarding/favorites/openTome")

/** The Tome page’s search field. Opens the shared [MimirKeyboard], which the controller can type on. */
internal val OnboardingFavoritesSearchKey = FocusKey("onboarding/favorites/search")

/**
 * The platform filter, as ONE control beside the search field rather than a rail of chips.
 *
 * The earlier separate filter rail could move focus off screen when navigating up from the grid.
 * The platform filter now shares the search row and uses the Customize Media dropdown pattern.
 *
 * Both halves of that are one defect. The rail was a second focusable ROW above the grid, and UP out of
 * the grid landed on whichever chip sat at the focused column -- in a rail that scrolls horizontally, that
 * is frequently a chip which is not on screen. Focus was not lost; it was parked somewhere invisible,
 * which is worse, because the screen looks like it stopped responding.
 *
 * One row of two controls removes the ambiguity entirely: UP out of the grid has exactly one place to go.
 * The dropdown ([onboardingFavoritesPlatformOptionKey]) is the Customize Media pattern --
 * `EnrichFilterRow` + `EnrichFilterDropdown` -- a chip reading `LABEL · value` that opens a list.
 */
internal val OnboardingFavoritesPlatformFilterKey = FocusKey("onboarding/favorites/platformFilter")

/** One row of the open platform dropdown. Index 0 is always "All". */
internal fun onboardingFavoritesPlatformOptionKey(index: Int) =
    FocusKey("onboarding/favorites/platformOption/$index")

// QA-10: OVERLAY's grant and its Not now.
internal val OnboardingOverlayAccessKey = FocusKey("onboarding/access/overlay")
private val OnboardingOverlayDeferKey = FocusKey("onboarding/access/overlay/defer")

private val OnboardingHomeRoleKey = FocusKey("onboarding/access/homeRole")
private val OnboardingFilesDeferKey = FocusKey("onboarding/access/files/defer")
private val OnboardingUsageDeferKey = FocusKey("onboarding/access/usage/defer")
private val OnboardingHomeRoleDeferKey = FocusKey("onboarding/access/homeRole/defer")
private val OnboardingAccessContinueKey = FocusKey("onboarding/access/continue")
private val OnboardingChooseFolderKey = FocusKey("onboarding/library/choose")
private val OnboardingLibraryContinueKey = FocusKey("onboarding/library/continue")
private val OnboardingPickAnotherKey = FocusKey("onboarding/library/pickAnother")
internal val OnboardingArtworkSetupKey = FocusKey("onboarding/metadata/artworkSetup")
internal val OnboardingSteamGridDbKey = FocusKey("onboarding/metadata/steamGridDbKey")
// OR-15 (product review 2026-09-28, ): the RetroAchievements row on the accounts step.
internal val OnboardingRetroAchievementsKey = FocusKey("onboarding/metadata/retroAchievements")
internal val OnboardingMetadataContinueKey = FocusKey("onboarding/metadata/continue")
private val OnboardingSettlementRetryKey = FocusKey("onboarding/settling/retry")
private val OnboardingSettlementContinueKey = FocusKey("onboarding/settling/continue")
private val OnboardingDoneKey = FocusKey("onboarding/done/enter")

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// The DONE step — one FINISH page
// ─────────────────────────────────────────────────────────────────────────────────────────────────
// It was four taught screens (DEVICE, MEDIA, TOMES, FINISH).
// They repeated what earlier steps had just taught -- ACCESS asks for the home role and the M button,
// METADATA names the art providers, FAVORITES teaches marking -- and they were folded into FINISH:
// "You're all set." plus one glass button legend, rows only where they apply, and no Back. The tail no longer has one page per hint or internal Back navigation.
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * Whether FINISH's legend names the floating M at all (it was the M block of the retired `TAIL/DEVICE`).
 *
 * Ground truth is [selectMButtonOverlayTarget] (`MButtonRuntime.kt:206-220`), which has two paths: a device
 * with a presentation display returns `SECONDARY_COMPANION` before usage access is ever consulted, and the
 * one-screen path returns a target only when usage access is granted. So on a one-screen handheld whose
 * player has not granted usage access there is no M button and no way to reach one without first going
 * back for that permission -- which is exactly what the USAGE screen's own refusal copy told them two
 * screens earlier: *"Not now: on a one-screen handheld there is no M button."* Teaching the button after
 * saying it does not exist is the contradiction this removes.
 *
 * Deliberately NOT part of this rule, though [selectMButtonOverlayTarget] requires both:
 *  - `overlayPermissionGranted` -- a hard precondition on EVERY topology, so folding it in would drop the
 *    block on two-screen devices too, where the button is otherwise real.
 *  - `primaryEnabled` -- the player's own Settings switch, which is the thing this very block tells them to
 *    turn on. Gating the teaching on the setting it teaches would mean nobody is ever taught it.
 *
 * The narrow reading is the point: drop the block only where the product has already said the button is not
 * there. Everywhere else the three-block screen stands, and with it the fit overflow measured at the QA
 * emulator viewport (451px against 411.4px) -- this is a content correction that happens to buy 64px, not
 * a fit remedy that covers every viewport.
 */
internal fun onboardingTeachesMButton(hasSecondaryDisplay: Boolean, usageGranted: Boolean): Boolean =
    hasSecondaryDisplay || usageGranted

/** One entry in FINISH's button legend: the key as a keycap, then what it does on Home. */
internal data class OnboardingFinishLegendEntry(val key: String, val action: String)

/**
 * FINISH's button list. Home's own grammar, key then verb, one per row.
 * Each binding was checked against the dispatch when the folded pages were written, and is unchanged: X on Home
 * opens the Tome library (HomeShelfModels, SECONDARY -> onTomes), Y on a game opens its menu -- art, Tomes,
 * achievements (TERTIARY), which Home's own hint calls EDIT -- and the floating M opens its menu when held. The M
 * row shows only where [onboardingTeachesMButton] says this device can have one.
 *
 * No SELECT row. SELECT adds a game to the Home shelf in a
 * gamelist (LibraryDispatch, QA-14) but opens achievements on Home itself, which is where FINISH sends the player;
 * a row that is false on the very next screen teaches the wrong thing.
 *
 * X and Y show as the player's own pad names them ([labels], Home's rule): a blank label drops the row. M is the
 * floating on-screen button, not the pad's menu key, so it is never projected.
 *
 * The last row teaches a Library-only action: L3
 * toggles the Library's Hints legend on the shelf and in the gamelist (LibraryDispatch, SHOW_HINTS) and does nothing
 * on Home, so the row names the Library: the reason the SELECT row above was dropped. It shows whatever the player
 * bound to SHOW_HINTS, and a pad with nothing bound to it drops the row.
 */
internal fun onboardingFinishLegend(
    res: Resources,
    teachMButton: Boolean,
    labels: MimirControllerHintLabels = MimirControllerHintLabels(),
): List<OnboardingFinishLegendEntry> = buildList {
    fun pad(token: String, action: String) {
        val key = labels.labelFor(token)
        if (key.isNotBlank()) add(OnboardingFinishLegendEntry(key = key, action = action))
    }
    pad("X", res.getString(R.string.app_onboarding_hint_tomes))
    pad("Y", res.getString(R.string.app_onboarding_hint_edit))
    if (teachMButton) add(OnboardingFinishLegendEntry(key = "M", action = res.getString(R.string.app_onboarding_hint_menu)))
    pad("L3", res.getString(R.string.app_onboarding_hint_show_hints))
}

/**
 * FINISH's words. The home-screen row replaces the retired DEVICE page's first block and shows only when the
 * player skipped the home role ([onboardingFinishOffersHomeRow]); a player who made Mimir their home app
 * does not need to be told how.
 */
internal data class OnboardingFinishCopy(val title: String, val homeRow: String)

/**
 * The eyebrow says setup is done, the title says where the player goes next, the list says what the buttons do
 * there, and the pill goes. The Settings line was cut: it answered a question the
 * page never asked, and its height went to the list.
 */
internal fun onboardingFinishCopy(res: Resources): OnboardingFinishCopy = OnboardingFinishCopy(
    title = res.getString(R.string.app_onboarding_finish_title),
    homeRow = res.getString(R.string.app_onboarding_finish_home_row),
)

/** Whether FINISH shows the home-screen row: the device offers the role and the player did not take it. */
internal fun onboardingFinishOffersHomeRow(access: OnboardingAccessUi): Boolean =
    access.homeRoleAvailable && !access.homeRoleHeld

internal enum class OnboardingFocusSurface {
    WELCOME,
    PROFILE,
    PROFILE_CONFIRM,
    // The one-at-a-time ACCESS sub-sequence : one surface per screen,
    // not one shared ACCESS surface. Only one of these is ever the active render at a time, but a shared
    // surface would let a focus intent buffered on one screen (e.g. a mashed CONFIRM) survive the screen
    // change and land on the NEXT screen's action control — an accidental permission request. Distinct
    // surfaces make [OnboardingFocusIntentState.clear] scoped to "leave THIS screen" by construction.
    ACCESS_ORIENTATION,
    ACCESS_FILES,
    ACCESS_USAGE,
    ACCESS_HOME_ROLE,
    ACCESS_OVERLAY,
    // QA-16: the favourites step's own surface.
    FAVORITES,
    LIBRARY,
    METADATA,
    SETTLING,
    THEME,
    // FINISH, the one DONE page since the tail folded.
    DONE,
}

/** One logical focus intent survives the frame between input dispatch and a new surface registering bounds. */
internal class OnboardingFocusIntentState {
    private var surface: OnboardingFocusSurface? = null
    private var target: FocusKey? = null
    private var actions: List<ControllerAction> = emptyList()

    var revision by mutableStateOf(0)
        private set

    fun deferTarget(surface: OnboardingFocusSurface, target: FocusKey) {
        this.surface = surface
        this.target = target
        actions = emptyList()
        revision += 1
    }

    fun deferAction(surface: OnboardingFocusSurface, action: ControllerAction) {
        require(action.isDirectional || action == ControllerAction.CONFIRM)
        if (this.surface == surface && target == null && ControllerAction.CONFIRM in actions) return
        val queued = if (this.surface == surface && target == null) {
            actions + action
        } else {
            listOf(action)
        }
        this.surface = surface
        target = null
        actions = queued
        revision += 1
    }

    fun pendingTarget(
        surface: OnboardingFocusSurface,
        validKeys: List<FocusKey>,
    ): FocusKey? {
        if (this.surface != surface) return null
        val pending = target ?: return null
        if (pending !in validKeys) {
            clear(surface)
            return null
        }
        return pending
    }

    fun pendingActions(surface: OnboardingFocusSurface): List<ControllerAction> =
        actions.takeIf { this.surface == surface }.orEmpty()

    fun consumeTarget(surface: OnboardingFocusSurface, target: FocusKey) {
        if (this.surface == surface && this.target == target) clear(surface)
    }

    fun takeActions(surface: OnboardingFocusSurface): List<ControllerAction> {
        val pending = pendingActions(surface)
        if (pending.isNotEmpty()) clear(surface)
        return pending
    }

    fun retain(surface: OnboardingFocusSurface) {
        if (this.surface != null && this.surface != surface) clearInternal()
    }

    fun clear(surface: OnboardingFocusSurface) {
        if (this.surface == surface) clearInternal()
    }

    private fun clearInternal() {
        surface = null
        target = null
        actions = emptyList()
    }
}

/**
 * The next step after [step], or `null` when onboarding is complete (the host then calls
 * [OnboardingStore.markComplete] and routes to the shelf).
 */
fun nextOnboardingStep(
    step: OnboardingStep,
    favoritesStepApplies: Boolean = true,
): OnboardingStep? = when (step) {
    OnboardingStep.WELCOME -> OnboardingStep.PROFILE
    OnboardingStep.PROFILE -> OnboardingStep.ACCESS
    OnboardingStep.ACCESS -> OnboardingStep.LIBRARY
    OnboardingStep.LIBRARY -> OnboardingStep.METADATA
    OnboardingStep.METADATA -> OnboardingStep.SETTLING
    OnboardingStep.SETTLING -> OnboardingStep.THEME
    // QA-16: FAVORITES sits between THEME and DONE. After the theme, so the tiles render in the palette
    // the player just chose; before DONE, so "You're all set" lands on a Home shelf holding exactly what
    // they picked. [favoritesStepApplies] is false when the library is empty -- LIBRARY skipped, or the scan
    // found nothing -- because a "choose a few favourites" screen with nothing to choose is a dead end, not
    // a step. It defaults true so the chain stays a pure function of the step for every existing caller.
    OnboardingStep.THEME -> if (favoritesStepApplies) OnboardingStep.FAVORITES else OnboardingStep.DONE
    OnboardingStep.FAVORITES -> OnboardingStep.DONE
    OnboardingStep.DONE -> null
}

/** The library step's progress, including an explicit retryable traversal failure. */
enum class OnboardingLibraryStatus { IDLE, SCANNING, FAILED, DONE }

private const val OnboardingMissingGrantFailure = "Folder access was not confirmed for this scan."

/**
 * Mutable UI state for the REQUIRED library step. `granted` is the Bug-B guard: it flips true ONLY on a
 * deliberate SAF grant made in THIS flow, so a pre-existing persisted permission never auto-satisfies the
 * step ([[resident-verifier-saf-repair-regression]] / shipping `onboardingLibraryWriteReady`). Held in
 * `MimirApp` (which owns the SAF launcher) and shared with the router.
 */
class OnboardingLibraryState {
    var status by mutableStateOf(OnboardingLibraryStatus.IDLE)
    var granted by mutableStateOf(false)
    var failureReason by mutableStateOf<String?>(null)
}

internal fun applyOnboardingScanOutcome(
    state: OnboardingLibraryState,
    outcome: ScanOutcome,
) {
    when (outcome) {
        ScanOutcome.Healthy -> {
            if (state.granted) {
                state.failureReason = null
                state.status = OnboardingLibraryStatus.DONE
            } else {
                state.failureReason = OnboardingMissingGrantFailure
                state.status = OnboardingLibraryStatus.FAILED
            }
        }
        is ScanOutcome.Failed -> {
            state.granted = false
            state.failureReason = outcome.reason
            state.status = OnboardingLibraryStatus.FAILED
        }
    }
}

/**
 * Mutable UI state for the profile step: the (optional) name draft plus which touch sub-modal is open. Held
 * once in `MimirApp` and passed to BOTH the render ([OnboardingFlow]) and the router ([dispatchOnboarding]),
 * so the touch path and the controller path drive the same state — no duplicated source of truth.
 */
class OnboardingProfileState {
    /** The name being entered (empty = no name yet; a name is always optional). */
    var draft by mutableStateOf("")
    /** The in-app keyboard is showing. */
    var keyboardOpen by mutableStateOf(false)
    /** Active controller keyboard binding while the profile keyboard is open. */
    var keyboardHost: MimirKeyboardHost? = null
    /** The empty-name "Create your profile?" confirm is showing. */
    var confirmOpen by mutableStateOf(false)
    /** Monotonic modal identity so a close/reopen inside one composition frame still relands safe focus. */
    var confirmEpoch by mutableStateOf(0)
        private set

    fun openConfirm() {
        confirmEpoch += 1
        confirmOpen = true
    }

    /** Clear on advance so a later return can never show a stale draft/sub-modal. */
    fun reset() {
        draft = ""
        keyboardOpen = false
        confirmOpen = false
    }
}

/**
 * The onboarding shell: a root [BackHandler] that swallows OS Back (forward-only first run — no escape to an
 * empty shelf) and the current step's screen. Sub-modals (the profile keyboard + empty-name confirm) register
 * their own later [BackHandler]s so OS Back closes the modal first, then the root resumes swallowing.
 *
 * @param authority the app's single focus authority (controls register under [OnboardingScope]).
 * @param step the current step to render.
 * @param profile the profile step's mutable UI state (shared with the router).
 * @param onAdvance step-forward request — the host computes the next step (or completes → shelf).
 * @param onCommitName persist the chosen display name (host wires `ProfileNameStore.setName`).
 */
@Composable
internal fun OnboardingFlow(
    authority: FocusAuthority,
    step: OnboardingStep,
    profile: OnboardingProfileState,
    accessReview: OnboardingAccessReviewState,
    focusIntent: OnboardingFocusIntentState,
    access: OnboardingAccessUi,
    // RC-13: see [dispatchOnboarding]'s matching parameter — the render (touch) path needs the identical
    // durable signal the gamepad dispatch path uses, sourced from the same [OnboardingStore] checkpoint.
    accessOrientationSeen: Boolean = false,
    library: OnboardingLibraryState,
    libraryGameCount: Int,
    libraryPlatformCount: Int,
    // QA-16: the discovered library, and the one write this step makes. Defaulted so every existing caller
    // and test that predates the step keeps compiling; the step is skipped outright when the library is
    // empty (see [nextOnboardingStep]), so an empty default is never rendered in production.
    favoriteGames: List<GameRow> = emptyList(),
    favoritesIdentifying: OnboardingFavoritesIdentifying? = null,
    favoritesTomeOpensOnSettle: Boolean = false,
    favoritesIdentifyingInBackground: Boolean = false,
    onToggleFavorite: (GameRow) -> Unit = {},
    // The FAVORITES step's whole position: which of its two pages is showing, the platform rail, the
    // search text and the keyboard. Defaulted for the tests and previews that predate it; the two real
    // call sites pass the ONE instance `MimirNavigationCoordinator` holds, because every one of those is
    // written in the dispatch and read here -- two instances would mean an Open Tome button that opens
    // nothing and a chip that lights up and filters nothing.
    favoritesGrid: OnboardingFavoritesGridState = remember { OnboardingFavoritesGridState() },
    // Q1 : WELCOME's verse-first intro. The real call site passes the ONE instance
    // `MimirNavigationCoordinator` holds, shared with [dispatchOnboarding], so a press ends it.
    welcomeIntro: OnboardingWelcomeIntroState = remember { OnboardingWelcomeIntroState() },
    // the step handover. The real call site passes the ONE instance
    // `MimirNavigationCoordinator` holds, shared with [dispatchOnboarding], which drops presses while it departs.
    stepGlide: OnboardingStepGlideState = remember { OnboardingStepGlideState(step) },
    //  the loading hint carousel. The real call site passes the ONE instance
    // `MimirNavigationCoordinator` holds, shared with [dispatchOnboarding], which turns it on LEFT/RIGHT.
    hintCarousel: OnboardingHintCarouselState = remember { OnboardingHintCarouselState() },
    //  the action pages on LIBRARY. The real call site passes the ONE instance
    // `MimirNavigationCoordinator` holds, shared with [dispatchOnboarding], which turns them on A and B.
    actionTour: OnboardingActionTourState = remember { OnboardingActionTourState() },
    artworkAccount: String?,
    steamGridDbKeyPresent: Boolean,
    settlement: OnboardingSettlementUi,
    currentThemeId: MimirThemeId,
    // The decision, not the topology: the caller applies [onboardingTeachesMButton] and passes the answer,
    // so the rule lives in one testable place and this default stays a behaviour choice rather than a claim
    // about the hardware. `true` keeps today's three-block screen for every caller that predates this --
    // the same defaulting this file already uses for `favoriteGames` and `accessOrientationSeen`.
    teachMButton: Boolean = true,
    onAdvance: () -> Unit,
    onCommitName: (String) -> Unit,
    onRequestFilesAccess: () -> Unit,
    onRequestUsageAccess: () -> Unit,
    onRequestOverlayAccess: () -> Unit = {},
    // RC-13: see [dispatchOnboarding]'s matching parameter.
    onMarkAccessOrientationSeen: () -> Unit = {},
    onRequestHomeRole: () -> Unit,
    onChooseFolder: () -> Unit,
    onOpenArtworkSetup: () -> Unit,
    onOpenSteamGridDbKey: () -> Unit,
    onRetrySettlement: () -> Unit,
    onContinueLocally: () -> Unit,
    onApplyTheme: (MimirThemeId) -> Unit,
    // OR-15: see [dispatchOnboarding]'s matching parameter.
    onOpenRetroAchievements: () -> Unit = {},
    retroAchievementsUser: String? = null,
    retroAchievementsKeyRejected: Boolean = false,
) {
    // Make OnboardingScope the active focus scope for the whole flow, so requestFocus/moveFocus can land on
    // the step controls (focus can only sit inside the ACTIVE scope). Pop back on exit (→ shelf). The confirm
    // sub-modal pushes its own scope ON TOP; being a child it disposes (pops) before this, so the stack unwinds.
    DisposableEffect(Unit) {
        authority.pushScope(OnboardingScope)
        onDispose { if (authority.state.value.scope == OnboardingScope) authority.popScope() }
    }
    // HARD first-run gate: swallow OS Back at the root so a fresh install can never fall through to an empty
    // shelf. (Compose analog of the shipping `onboardingBack` forward-only guard.) Sub-modal BackHandlers,
    // composed later, win while a modal is open.
    BackHandler(enabled = true) { /* no escape from the first-run gate */ }
    // Q1: WELCOME's first appearance plays the verse alone; the sky (stars and planet) and the rest arrive after it.
    val introReducedMotion = mimirReducedMotion()
    val playWelcomeIntro = remember { step == OnboardingStep.WELCOME && !welcomeIntro.settled && !introReducedMotion }
    val welcomeRest = remember { Animatable(if (playWelcomeIntro) 0f else 1f) }
    val stepPage: @Composable (OnboardingStep, Boolean) -> Unit = { pageStep, leaving ->
        // A step the navigation has left leaves as it was: the coordinator resets some values the moment it moves on.
        val heldFavoriteGames = heldWhileDeparting(leaving, favoriteGames)
        val heldFavoritesIdentifying = heldWhileDeparting(leaving, favoritesIdentifying)
        val heldFavoritesTomeOpensOnSettle = heldWhileDeparting(leaving, favoritesTomeOpensOnSettle)
        val heldFavoritesIdentifyingInBackground = heldWhileDeparting(leaving, favoritesIdentifyingInBackground)
        val heldSettlement = heldWhileDeparting(leaving, settlement)
        when (pageStep) {
            OnboardingStep.WELCOME -> OnboardingWelcomeScreen(
                authority = authority,
                focusIntent = focusIntent,
                onBegin = onAdvance,
                playIntro = playWelcomeIntro,
                restProgress = welcomeRest,
                intro = welcomeIntro,
            )
            OnboardingStep.PROFILE -> OnboardingProfileScreen(
                authority = authority,
                profile = profile,
                focusIntent = focusIntent,
                onCommitName = onCommitName,
                onAdvance = onAdvance,
            )
            OnboardingStep.ACCESS -> OnboardingAccessScreen(
                authority = authority,
                access = access,
                accessReview = accessReview,
                orientationAlreadySeen = accessOrientationSeen,
                focusIntent = focusIntent,
                onRequestFilesAccess = onRequestFilesAccess,
                onRequestUsageAccess = onRequestUsageAccess,
                onRequestOverlayAccess = onRequestOverlayAccess,
                onMarkAccessOrientationSeen = onMarkAccessOrientationSeen,
                onRequestHomeRole = onRequestHomeRole,
                onAdvance = onAdvance,
                hintCarousel = hintCarousel,
            )
            OnboardingStep.LIBRARY -> OnboardingLibraryScreen(
                authority = authority,
                library = library,
                focusIntent = focusIntent,
                gameCount = libraryGameCount,
                platformCount = libraryPlatformCount,
                actionTour = actionTour,
                teachMButton = teachMButton,
                onChooseFolder = onChooseFolder,
                onAdvance = onAdvance,
            )
            OnboardingStep.METADATA -> OnboardingMetadataScreen(
                authority = authority,
                artworkAccount = artworkAccount,
                steamGridDbKeyPresent = steamGridDbKeyPresent,
                retroAchievementsUser = retroAchievementsUser,
                retroAchievementsKeyRejected = retroAchievementsKeyRejected,
                focusIntent = focusIntent,
                onOpenArtworkSetup = onOpenArtworkSetup,
                onOpenSteamGridDbKey = onOpenSteamGridDbKey,
                onOpenRetroAchievements = onOpenRetroAchievements,
                onAdvance = onAdvance,
            )
            OnboardingStep.SETTLING -> OnboardingSettlingScreen(
                authority = authority,
                settlement = heldSettlement,
                focusIntent = focusIntent,
                onRetry = onRetrySettlement,
                onContinueLocally = onContinueLocally,
                onAdvance = onAdvance,
            )
            OnboardingStep.FAVORITES -> OnboardingFavoritesScreen(
                authority = authority,
                focusIntent = focusIntent,
                games = heldFavoriteGames,
                identifying = heldFavoritesIdentifying,
                opensTomeOnSettle = heldFavoritesTomeOpensOnSettle,
                identifyingInBackground = heldFavoritesIdentifyingInBackground,
                filter = favoritesGrid,
                onToggleFavorite = onToggleFavorite,
                onAdvance = onAdvance,
            )
            OnboardingStep.THEME -> OnboardingThemeScreen(
                authority = authority,
                focusIntent = focusIntent,
                currentThemeId = currentThemeId,
                onApplyTheme = onApplyTheme,
                onAdvance = onAdvance,
                departing = leaving,
            )
            OnboardingStep.DONE -> OnboardingDoneScreen(
                authority = authority,
                focusIntent = focusIntent,
                onFinish = onAdvance,
                teachMButton = teachMButton,
                offerHomeRow = onboardingFinishOffersHomeRow(access),
            )
        }
    }
    Box(Modifier.fillMaxSize()) {
        OnboardingFlowSky(welcomeRest.asState())
        CompositionLocalProvider(LocalOnboardingSkyHosted provides true) {
            OnboardingStepCrossfade(step = step, stepGlide = stepGlide, page = stepPage)
        }
    }
}

/**
 * The step handover: an even cross-fade between the step the navigation left and the one it moved to, both composed for
 * [MimirCrossSwapMs] (see the comment below for the ruling and the checks). [page] draws one step, and whether the
 * navigation has left it; the host provides each page its arrival and departure ([LocalOnboardingStepArrival],
 * [LocalOnboardingStepHandover]), which the scaffold's layers turn into alpha and glide. Held apart from [OnboardingFlow]
 * so the clock and the pairing are the same thing the tests drive.
 */
@Composable
internal fun OnboardingStepCrossfade(
    step: OnboardingStep,
    stepGlide: OnboardingStepGlideState,
    page: @Composable (OnboardingStep, Boolean) -> Unit,
) {
    // A step
    // handover is an EVEN CROSS-FADE, like the rest of the product. The step the navigation left stays composed beside
    // the one it moved to for one [MimirCrossSwapMs] on [MimirMotion.Easing] ([mimirCrossSwapSpec], the swap tier's one
    // clock), and the two are complementary at every frame -- the arriving step at p, the leaving one at 1 - p -- so the
    // pane is never emptier than the overlap itself (a coverage of 0.75 at the midpoint) and nothing dips. The steps
    // still glide: the arriving one comes in from the trailing edge, the leaving one goes toward the leading
    // edge, on that same clock ([onboardingStepTravelPx]); the header seat stays still between two seated steps. Reduced
    // motion snaps: no leaving step is composed at all.
    //
    // [OnboardingStepGlideState.displayedStep] lags [step] by the cross-fade. While they differ both steps are composed,
    // and every press is dropped -- by the dispatch (a controller press would act on a step the player cannot see yet) and
    // by a touch shield here (the leaving step's own buttons would advance from the step after it). Composed together for
    // 180 ms, checked against [onboardingGlide]'s four hazards: the OS Back handlers are the sub-modals' (only while open)
    // and ACCESS's own (it fires as it did while the step was alone), the profile keyboard cannot be open when Continue is
    // pressed, no step here owns a SAF launcher (the folder pick is the host's), and a step is never composed twice.
    // Steps never share a [FocusKey] across the boundary, so the leaving step's disposal cannot unregister the arriving
    // step's pill. The sky is the flow's, drawn once beneath both ([LocalOnboardingSkyHosted]); the one step that draws its
    // own (the favourites, whose glass blurs it) is composed beneath the other, so its sky never hides the page it fades with.
    //
    // One arrival clock, keyed by the step, held here rather than in a screen, so a return inside a step (the favourites
    // TOME -> INTRO glide) never refires it. WELCOME is born settled, because its verse intro is its entry (Q1). THEME has
    // no scaffold; its gallery reads the same two clocks ([OnboardingThemeScreen], walk-32d). A step shown with nothing
    // leaving -- a cold start or a resume -- fades in without travel, because travel says where the player came from.
    val arrivalReducedMotion = mimirReducedMotion()
    // Until this flow's first effect runs it shows [step] itself: a navigation that moved on while onboarding was
    // not composed is a resume, not a departure, and must not glide a step the player never saw.
    var synced by remember { mutableStateOf(false) }
    val displayedStep = if (synced) stepGlide.displayedStep else step
    val departure = remember { Animatable(0f) }
    LaunchedEffect(step) {
        if (!synced) {
            stepGlide.show(step)
            synced = true
        }
        if (stepGlide.displayedStep == step) {
            departure.snapTo(0f)
            return@LaunchedEffect
        }
        // From where it is: a second step change mid-cross-fade continues it and shows the newest step.
        departure.animateTo(1f, mimirCrossSwapSpec(arrivalReducedMotion))
        stepGlide.show(step)
        departure.snapTo(0f)
    }
    val currentStep by rememberUpdatedState(step)
    DisposableEffect(stepGlide) {
        // Never leave the dispatch gate shut behind a flow that is no longer composed.
        onDispose { stepGlide.show(currentStep) }
    }
    val departing = step != displayedStep
    val stepArrival = remember(step) {
        Animatable(if (arrivalReducedMotion || step == OnboardingStep.WELCOME) 1f else 0f)
    }
    LaunchedEffect(stepArrival) {
        // Born at 0 with the page, on the same clock as the departure, so the two are complementary frame for frame.
        stepArrival.animateTo(1f, mimirCrossSwapSpec(arrivalReducedMotion))
    }
    val arrivingHandover = remember(departing, step, displayedStep, arrivalReducedMotion) {
        OnboardingStepHandover(
            departure = OnboardingStepDepartureSettled,
            arrivalTravels = departing && !arrivalReducedMotion,
            headerArrives = departing && onboardingStepHasHeaderSeat(step) && !onboardingStepHasHeaderSeat(displayedStep),
            headerDeparts = false,
        )
    }
    val leavingHandover = remember(departure, departing, step, displayedStep) {
        OnboardingStepHandover(
            departure = departure.asState(),
            arrivalTravels = false,
            headerArrives = false,
            headerDeparts = departing &&
                onboardingStepHasHeaderSeat(displayedStep) && !onboardingStepHasHeaderSeat(step),
        )
    }
    // The leaving step's buttons are still composed; a tap on one would advance from the step after it. The
    // parent takes every pointer event in the Initial pass, before any of them sees it, until the cross-fade is done.
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(departing) {
                if (!departing) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }
            },
    ) {
        val pages = if (departing && !arrivalReducedMotion) {
            listOf(displayedStep, step).sortedBy { if (onboardingStepDrawsOwnSky(it)) 0 else 1 }
        } else {
            listOf(step)
        }
        pages.forEach { pageStep ->
            key(pageStep) {
                val leaving = pageStep != step
                CompositionLocalProvider(
                    LocalOnboardingStepArrival provides
                        (if (leaving) OnboardingStepArrivalSettled else stepArrival.asState()),
                    LocalOnboardingStepHandover provides (if (leaving) leavingHandover else arrivingHandover),
                ) {
                    page(pageStep, leaving)
                }
            }
        }
    }
}

/**
 * Which step is on screen, shared by [OnboardingFlow] (which moves it on once the cross-fade is done) and
 * [dispatchOnboarding] (which drops every press while it differs from the navigation step). * an even cross-fade, the steps still gliding.
 */
internal class OnboardingStepGlideState(initial: OnboardingStep) {
    var displayedStep by mutableStateOf(initial)
        private set

    fun show(step: OnboardingStep) {
        displayedStep = step
    }

    /** True while the navigation has moved on and the step on screen is still cross-fading out. */
    fun departing(navigationStep: OnboardingStep): Boolean = navigationStep != displayedStep
}

/** The step whose host draws its own sky (its glass blurs it), so it is composed beneath the step it cross-fades with. */
internal fun onboardingStepDrawsOwnSky(step: OnboardingStep): Boolean = step == OnboardingStep.FAVORITES

/** The steps whose scaffold carries [OnboardingHeaderStrip]: all but WELCOME (its own lockup) and THEME. */
internal fun onboardingStepHasHeaderSeat(step: OnboardingStep): Boolean =
    step != OnboardingStep.WELCOME && step != OnboardingStep.THEME

/** The body and control slot's alpha: faded in by the arrival, out by the departure. */
internal fun onboardingStepAlpha(arrival: Float, departure: Float): Float = arrival * (1f - departure)

/**
 * The body and control slot's horizontal travel, in px, for a forward-only flow: an arriving step comes in from
 * the trailing edge (only when something departed before it), a departing one leaves toward the leading edge --
 * the direction [onboardingGlideOffsetPx] gives a forward handover.
 */
internal fun onboardingStepTravelPx(arrival: Float, departure: Float, arrivalTravels: Boolean, travelPx: Float): Float =
    (if (arrivalTravels) (1f - arrival) * travelPx else 0f) - departure * travelPx

/**
 * [value] as it was when the step began to depart, for as long as it departs; otherwise [value]. The coordinator
 * resets some step values the moment the navigation moves on -- some in the same composition, some a frame later
 * -- and the departing step must leave as it was, not blank mid-glide.
 */
@Composable
private fun <T> heldWhileDeparting(departing: Boolean, value: T): T {
    var last by remember { mutableStateOf(value) }
    SideEffect { if (!departing) last = value }
    return if (departing) last else value
}

/**
 * Route one normalized [ControllerAction] to the onboarding flow. Always returns `true` — onboarding is a
 * hard modal, so every action is consumed and nothing leaks to whatever is composed behind it.
 */
internal fun dispatchOnboarding(
    authority: FocusAuthority,
    step: OnboardingStep,
    profile: OnboardingProfileState,
    accessReview: OnboardingAccessReviewState,
    focusIntent: OnboardingFocusIntentState,
    access: OnboardingAccessUi,
    // RC-13: the durable "left ORIENTATION already" bit — see [onboardingAccessCurrentScreen]'s doc.
    // Defaulted so every pre-existing call site (all of them predate RC-13) keeps today's behaviour.
    accessOrientationSeen: Boolean = false,
    // QA-16: the discovered library, and the one write this step makes. Defaulted so every existing caller
    // and test that predates the step keeps compiling; the step is skipped outright when the library is
    // empty (see [nextOnboardingStep]), so an empty default is never rendered in production.
    favoriteGames: List<GameRow> = emptyList(),
    favoritesIdentifying: OnboardingFavoritesIdentifying? = null,
    // The FAVORITES grid's filter + measured geometry. Defaulted for tests; the real call site passes the
    // ONE instance MimirNavigationCoordinator holds, shared with the screen -- see the class KDoc.
    favoritesGrid: OnboardingFavoritesGridState = OnboardingFavoritesGridState(),
    onToggleFavorite: (GameRow) -> Unit = {},
    // Q1: see [OnboardingFlow]'s matching parameter; the real call site passes the same instance.
    welcomeIntro: OnboardingWelcomeIntroState = OnboardingWelcomeIntroState(),
    // see [OnboardingFlow]'s matching parameter. Null for tests that predate the glide.
    stepGlide: OnboardingStepGlideState? = null,
    // Carousel wiring: see [OnboardingFlow]'s matching parameter. Null for tests that predate the carousel.
    hintCarousel: OnboardingHintCarouselState? = null,
    // Action-page wiring: see [OnboardingFlow]'s matching parameter. Null for tests that predate the pages.
    actionTour: OnboardingActionTourState? = null,
    library: OnboardingLibraryState,
    artworkAccount: String?,
    settlement: OnboardingSettlementUi,
    action: ControllerAction,
    onAdvance: () -> Unit,
    onCommitName: (String) -> Unit,
    onRequestFilesAccess: () -> Unit,
    onRequestUsageAccess: () -> Unit,
    onRequestOverlayAccess: () -> Unit = {},
    // RC-13: fires once, exactly when advancing off ORIENTATION — the write side of [accessOrientationSeen].
    onMarkAccessOrientationSeen: () -> Unit = {},
    onRequestHomeRole: () -> Unit,
    onChooseFolder: () -> Unit,
    onOpenArtworkSetup: () -> Unit,
    onOpenSteamGridDbKey: () -> Unit,
    onRetrySettlement: () -> Unit,
    onContinueLocally: () -> Unit,
    onApplyTheme: (MimirThemeId) -> Unit,
    // OR-15: the app-shell RetroAchievements connect overlay, which renders above onboarding like the key overlay.
    onOpenRetroAchievements: () -> Unit = {},
): Boolean {
    // The step on screen is still gliding out: the navigation has moved on, so this press would act on a step the
    // player cannot see yet. Dropped, not queued -- a quick second A no longer skips a step.
    if (stepGlide?.departing(step) == true) return true
    when (step) {
        OnboardingStep.WELCOME -> {
            // Q1 : any press ends the verse's first appearance, and the same press still
            // counts -- A lands on Begin, it does not merely skip.
            welcomeIntro.settle()
            // One focusable (`Begin`): CONFIRM (A) begins. Everything else is swallowed (forward-only root).
            if (action == ControllerAction.CONFIRM) onAdvance()
        }

        OnboardingStep.PROFILE -> {
            when {
                // Empty-name confirm is the top-most modal: nav between the two pills; A commits the focused
                // one; B / Back = Keep editing (the safe default).
                profile.confirmOpen -> {
                    val keys = listOf(OnboardingConfirmKeepKey, OnboardingConfirmContinueKey)
                    val focused = onboardingLogicalFocus(
                        keys = keys,
                        authorityFocus = authority.state.value.focused,
                        entryFocus = OnboardingConfirmKeepKey,
                        pendingFocus = focusIntent.pendingTarget(OnboardingFocusSurface.PROFILE_CONFIRM, keys),
                    )
                    when (action) {
                    ControllerAction.NAV_LEFT, ControllerAction.NAV_RIGHT,
                    ControllerAction.NAV_UP, ControllerAction.NAV_DOWN,
                    -> moveOnboardingFocus(
                        authority = authority,
                        focusIntent = focusIntent,
                        surface = OnboardingFocusSurface.PROFILE_CONFIRM,
                        keys = keys,
                        entryFocus = OnboardingConfirmKeepKey,
                        action = action,
                    )
                    ControllerAction.CONFIRM ->
                        if (focused == OnboardingConfirmContinueKey) {
                            profile.reset(); onAdvance() // continue nameless
                        } else {
                            focusIntent.clear(OnboardingFocusSurface.PROFILE_CONFIRM)
                            profile.confirmOpen = false // Keep editing (default)
                        }
                    ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK -> {
                        focusIntent.clear(OnboardingFocusSurface.PROFILE_CONFIRM)
                        profile.confirmOpen = false
                    }
                    else -> Unit
                    }
                }
                // The keyboard supports controller focus when a host binding is active.
                profile.keyboardOpen -> {
                    val dismissed = { profile.keyboardOpen = false }
                    if (profile.keyboardHost?.let { host ->
                            com.gruning.mimir.rewrite.core.input.dispatchMimirKeyboardInput(
                                authority = authority,
                                scope = com.gruning.mimir.rewrite.core.input.MimirKeyboardFocusScope,
                                action = action,
                                binding = host.binding,
                                onDismiss = dismissed,
                            )
                        } == true
                    ) {
                        return true
                    }
                    when (action) {
                        ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK -> {
                            dismissed()
                            true
                        }
                        else -> true
                    }
                }
                // Name field + Continue + Not now: SPATIAL nav, not the flat-index walk every other
                // multi-key onboarding surface uses (see [onboardingFocusDestination]'s doc). The field sits
                // in its own row ABOVE a horizontal pair of buttons (OnboardingProfileScreen.kt's layout:
                // OnboardingNameField, then OnboardingActionGroup { Continue, Not now }). A device report after 638fb39c found that UP stayed among the buttons
                // instead of reaching the name field. The old flat model walked
                // [field, Continue, NotNow] as one list, so UP from Not now landed one step away on
                // Continue instead of two rows up on the field. [onboardingProfileFocusDestination] fixes
                // only this: UP always reaches the field; LEFT/RIGHT only move within the button row; DOWN
                // from the field lands on Continue -- the BUTTON ROW's own default, held by
                // [onboardingProfileFocusDestination] itself and deliberately NOT this screen's
                // `entryFocus`, which is the name field since 2026-09-18 (see [onboardingEntryFocus]).
                // No memory of a previous button choice, for this spatial-navigation model. Surveyed
                // every other multi-key onboarding surface (METADATA, LIBRARY, SETTLING, every ACCESS
                // screen, the PROFILE confirm modal, FINISH) — none has a focusable control
                // above its action row, so their flat-index UP/DOWN-equals-LEFT/RIGHT redundancy is
                // harmless there; PROFILE is the only screen with this shape. A activates the focused
                // control — on the field that opens the keyboard, exactly what its own onTap does; B is
                // swallowed (hard gate — no accidental skip; the explicit "Not now" pill or the confirm
                // handles skipping).
                else -> {
                    val keys = onboardingProfileFocusKeys()
                    val entry = onboardingEntryFocus(keys, OnboardingContinueKey)
                    val focused = onboardingLogicalFocus(
                        keys = keys,
                        authorityFocus = authority.state.value.focused,
                        entryFocus = entry,
                        pendingFocus = focusIntent.pendingTarget(OnboardingFocusSurface.PROFILE, keys),
                    )
                    when (action) {
                    ControllerAction.NAV_LEFT, ControllerAction.NAV_RIGHT,
                    ControllerAction.NAV_UP, ControllerAction.NAV_DOWN,
                    -> moveOnboardingProfileFocus(
                        authority = authority,
                        focusIntent = focusIntent,
                        keys = keys,
                        entryFocus = entry,
                        action = action,
                    )
                    ControllerAction.CONFIRM ->
                        when (focused) {
                            OnboardingNameFieldKey -> profile.keyboardOpen = true // same as the field's own onTap
                            OnboardingNotNowKey -> { profile.reset(); onAdvance() } // skip nameless
                            else -> profileAttemptContinue(profile, focusIntent, onCommitName, onAdvance) // Continue (default)
                        }
                    else -> Unit
                    }
                }
            }
        }

        OnboardingStep.ACCESS -> {
            val screens = onboardingAccessScreens(access)
            val current = onboardingAccessCurrentScreen(
                screens, accessReview.currentScreen, access, accessReview, accessOrientationSeen,
            ) ?: return true
            if (accessReview.currentScreen != current) accessReview.goToScreen(current)
            val surface = onboardingAccessFocusSurface(current)
            val keys = onboardingAccessScreenFocusKeys(current, access)
            val focused = onboardingLogicalFocus(
                keys = keys,
                authorityFocus = authority.state.value.focused,
                entryFocus = keys.firstOrNull(),
                pendingFocus = focusIntent.pendingTarget(surface, keys),
            )
            fun advance() {
                // RC-13: persist BEFORE navigating away, while `current` still names the screen being left.
                if (current == OnboardingAccessScreenId.ORIENTATION) onMarkAccessOrientationSeen()
                onboardingAccessAdvance(
                    screens = screens,
                    current = current,
                    review = accessReview,
                    focusIntent = focusIntent,
                    access = access,
                    onAdvance = onAdvance,
                )
            }
            when {
                // ORIENTATION's one key is Continue, so LEFT/RIGHT turn its permission cards without moving focus.
                current == OnboardingAccessScreenId.ORIENTATION &&
                    (action == ControllerAction.NAV_LEFT || action == ControllerAction.NAV_RIGHT) -> {
                    hintCarousel?.turn(action)
                }
                action.isDirectional -> moveOnboardingFocus(
                    authority = authority,
                    focusIntent = focusIntent,
                    surface = surface,
                    keys = keys,
                    entryFocus = keys.firstOrNull() ?: OnboardingAccessContinueKey,
                    action = action,
                )
                action == ControllerAction.CONFIRM -> when (focused) {
                    OnboardingFilesAccessKey -> if (!access.filesGranted) {
                        onRequestFilesAccess()
                    }
                    OnboardingUsageAccessKey -> if (access.usageAvailable && !access.usageGranted) {
                        onRequestUsageAccess()
                    }
                    OnboardingOverlayAccessKey -> onRequestOverlayAccess()
                    OnboardingOverlayDeferKey -> {
                        accessReview.deferOverlay()
                        advance()
                    }
                    OnboardingHomeRoleKey -> if (access.homeRoleAvailable && !access.homeRoleHeld) {
                        onRequestHomeRole()
                    }
                    OnboardingFilesDeferKey -> {
                        accessReview.defer(OnboardingAccessCapability.FILES)
                        advance()
                    }
                    OnboardingUsageDeferKey -> {
                        accessReview.defer(OnboardingAccessCapability.USAGE)
                        advance()
                    }
                    OnboardingHomeRoleDeferKey -> {
                        accessReview.defer(OnboardingAccessCapability.HOME_ROLE)
                        advance()
                    }
                    OnboardingAccessContinueKey -> advance()
                    else -> Unit
                }
                action == ControllerAction.CANCEL || action == ControllerAction.SYSTEM_BACK ->
                    onboardingAccessGoBack(screens, current, accessReview, focusIntent)
            }
        }

        OnboardingStep.LIBRARY -> {
            //  the action pages, from the pick until they end (OnboardingActionTour.kt).
            val tour = actionTour?.takeIf { onboardingActionTourShows(library.status, it) }
            if (tour != null) {
                dispatchOnboardingActionTour(authority, focusIntent, tour, library.status, action)
                return true
            }
            when (library.status) {
                // Before a pick: A opens the SAF folder picker. Back is swallowed (REQUIRED, forward-only).
                OnboardingLibraryStatus.IDLE -> if (action == ControllerAction.CONFIRM) onChooseFolder()
                // Scan still in flight after the pages ended: nothing to press, everything is swallowed.
                OnboardingLibraryStatus.SCANNING -> Unit
                // Failed traversal never advances; A reopens the system folder picker.
                OnboardingLibraryStatus.FAILED -> if (action == ControllerAction.CONFIRM) onChooseFolder()
                // Granted + indexed: nav Continue ↔ Pick another folder; A activates the focused one.
                OnboardingLibraryStatus.DONE -> {
                    val keys = listOf(OnboardingLibraryContinueKey, OnboardingPickAnotherKey)
                    val focused = onboardingLogicalFocus(
                        keys = keys,
                        authorityFocus = authority.state.value.focused,
                        entryFocus = OnboardingLibraryContinueKey,
                        pendingFocus = focusIntent.pendingTarget(OnboardingFocusSurface.LIBRARY, keys),
                    )
                    when (action) {
                    ControllerAction.NAV_LEFT, ControllerAction.NAV_RIGHT,
                    ControllerAction.NAV_UP, ControllerAction.NAV_DOWN,
                    -> moveOnboardingFocus(
                        authority = authority,
                        focusIntent = focusIntent,
                        surface = OnboardingFocusSurface.LIBRARY,
                        keys = keys,
                        entryFocus = OnboardingLibraryContinueKey,
                        action = action,
                    )
                    ControllerAction.CONFIRM ->
                        if (focused == OnboardingPickAnotherKey) onChooseFolder() else onAdvance()
                    else -> Unit
                    }
                }
            }
        }

        OnboardingStep.METADATA -> {
            val keys = onboardingMetadataFocusKeys(!artworkAccount.isNullOrBlank())
            val entry = onboardingEntryFocus(keys, OnboardingMetadataContinueKey)
            val focused = onboardingLogicalFocus(
                keys = keys,
                authorityFocus = authority.state.value.focused,
                entryFocus = entry,
                pendingFocus = focusIntent.pendingTarget(OnboardingFocusSurface.METADATA, keys),
            )
            when {
                action.isDirectional -> moveOnboardingFocus(
                    authority = authority,
                    focusIntent = focusIntent,
                    surface = OnboardingFocusSurface.METADATA,
                    keys = keys,
                    entryFocus = entry,
                    action = action,
                )
                action == ControllerAction.CONFIRM -> when (focused) {
                    OnboardingArtworkSetupKey -> if (artworkAccount.isNullOrBlank()) onOpenArtworkSetup()
                    OnboardingSteamGridDbKey -> onOpenSteamGridDbKey()
                    OnboardingRetroAchievementsKey -> onOpenRetroAchievements()
                    OnboardingMetadataContinueKey -> onAdvance()
                    else -> Unit
                }
                action == ControllerAction.CANCEL || action == ControllerAction.SYSTEM_BACK -> Unit
            }
        }

        OnboardingStep.SETTLING -> {
            val keys = buildList {
                if (settlement.canRetry) add(OnboardingSettlementRetryKey)
                if (settlement.canContinue) add(OnboardingSettlementContinueKey)
            }
            val entryFocus = settlementEntryFocus(settlement)
            val focused = onboardingLogicalFocus(
                keys = keys,
                authorityFocus = authority.state.value.focused,
                entryFocus = entryFocus,
                pendingFocus = focusIntent.pendingTarget(OnboardingFocusSurface.SETTLING, keys),
            )
            when {
                action.isDirectional && entryFocus != null -> moveOnboardingFocus(
                    authority = authority,
                    focusIntent = focusIntent,
                    surface = OnboardingFocusSurface.SETTLING,
                    keys = keys,
                    entryFocus = entryFocus,
                    action = action,
                )
                action == ControllerAction.CONFIRM -> when (focused) {
                    OnboardingSettlementRetryKey -> if (settlement.canRetry) onRetrySettlement()
                    OnboardingSettlementContinueKey -> activateSettlementContinue(
                        settlement = settlement,
                        onAdvance = onAdvance,
                        onContinueLocally = onContinueLocally,
                    )
                    else -> Unit
                }
            }
        }

        // The appended theme gallery (shared with Settings): 2D nav; CONFIRM applies the focused theme +
        // advances; Back keeps the current theme + advances (no setTheme on browse — nothing to revert).
        // QA-16: mirrors the ACCESS branch's shape -- directional nav over one key list, CONFIRM acts on
        // the logically focused key. Here the list is the library, so the keys are per-game and anchored to
        // the game id rather than the index.
        OnboardingStep.FAVORITES -> {
            // The keyboard owns ALL routed input while it is up, exactly as it does on PROFILE. Without
            // this gate a D-pad press meant for a letter would move the grid behind the scrim.
            if (favoritesGrid.keyboardOpen) {
                val dismissed = { favoritesGrid.closeKeyboard() }
                if (favoritesGrid.keyboardHost?.let { host ->
                        com.gruning.mimir.rewrite.core.input.dispatchMimirKeyboardInput(
                            authority = authority,
                            scope = com.gruning.mimir.rewrite.core.input.MimirKeyboardFocusScope,
                            action = action,
                            binding = host.binding,
                            onDismiss = dismissed,
                        )
                    } == true
                ) {
                    return true
                }
                when (action) {
                    ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK -> dismissed()
                    else -> Unit
                }
                return true
            }
            when (favoritesGrid.page) {
                OnboardingFavoritesPage.INTRO -> {
                    val keys = onboardingFavoritesIntroKeys()
                    val focused = onboardingLogicalFocus(
                        keys = keys,
                        authorityFocus = authority.state.value.focused,
                        entryFocus = OnboardingFavoritesOpenTomeKey,
                        pendingFocus = focusIntent.pendingTarget(OnboardingFocusSurface.FAVORITES, keys),
                    )
                    when {
                        // The two pills are one column, so LEFT/RIGHT do nothing here: the carousel they turned is
                        // one MARK demonstration now. Focus never moves for them.
                        action == ControllerAction.NAV_LEFT || action == ControllerAction.NAV_RIGHT -> Unit
                        // Two keys in one column: the flat walk is the right mover here.
                        action.isDirectional -> {
                            val at = keys.indexOf(focused)
                            val target = when (action) {
                                ControllerAction.NAV_DOWN -> keys.getOrNull(at + 1)
                                ControllerAction.NAV_UP -> if (at > 0) keys.getOrNull(at - 1) else null
                                else -> null
                            }
                            target?.let {
                                requestOnboardingFocus(
                                    authority,
                                    focusIntent,
                                    OnboardingFocusSurface.FAVORITES,
                                    it,
                                )
                            }
                        }
                        // No Y here, deliberately. This page has two pills and no filter, so binding the
                        // shortcut on it would set `filterOpen` on a page that renders no dropdown -- and
                        // `openTome()` does not clear it, so the Tome would then open with the list already
                        // up and `returnFocus` pointing at an INTRO key that page does not own. Shipped
                        // that way in 10080fb8e and caught on the run-15 walk; the binding belongs in the
                        // TOME branch below, which is the intended section.
                        action == ControllerAction.SYSTEM_SELECT || action == ControllerAction.CONFIRM ->
                            when (focused) {
                                OnboardingFavoritesOpenTomeKey -> if (favoritesIdentifying == null) {
                                    favoritesGrid.openTome()
                                    // The page that replaces this one has a completely different key set,
                                    // so a retained intent would try to land a key that no longer exists.
                                    focusIntent.clear(OnboardingFocusSurface.FAVORITES)
                                }
                                OnboardingFavoritesContinueKey ->
                                    // CONFIRM on the pill leaves. SELECT on the pill has nothing to toggle
                                    // and is swallowed rather than becoming a second, unadvertised way out.
                                    if (action == ControllerAction.CONFIRM) onAdvance()
                                else -> Unit
                            }
                        else -> return false
                    }
                    return true
                }

                OnboardingFavoritesPage.TOME -> {
                    // The screen's own cached lists: a press no longer rebuilds 5,500 keys.
                    val lists = favoritesGrid.tomeLists(favoriteGames)
                    val platforms = lists.platforms
                    val headKeys = lists.headKeys

                    // The OPEN dropdown owns the page's input, exactly as the keyboard layer does. Without
                    // this a D-pad press meant for an option would move the grid behind it.
                    if (favoritesGrid.filterOpen) {
                        val options = onboardingFavoritesPlatformOptions(platforms)
                        val optionKeys = options.indices.map(::onboardingFavoritesPlatformOptionKey)
                        val at = optionKeys.indexOf(
                            onboardingLogicalFocus(
                                keys = optionKeys,
                                authorityFocus = authority.state.value.focused,
                                entryFocus = onboardingFavoritesPlatformOptionKey(
                                    options.indexOf(favoritesGrid.platform).coerceAtLeast(0),
                                ),
                                pendingFocus = focusIntent.pendingTarget(
                                    OnboardingFocusSurface.FAVORITES,
                                    optionKeys,
                                ),
                            ),
                        ).coerceAtLeast(0)
                        when {
                            action.isDirectional ->
                                onboardingFavoritesFilterOptionDestination(options.size, at, action)
                                    ?.let { target ->
                                        requestOnboardingFocus(
                                            authority,
                                            focusIntent,
                                            OnboardingFocusSurface.FAVORITES,
                                            onboardingFavoritesPlatformOptionKey(target),
                                        )
                                    }
                            action == ControllerAction.SYSTEM_SELECT ||
                                action == ControllerAction.CONFIRM -> {
                                favoritesGrid.pick(options.getOrNull(at))
                                focusIntent.clear(OnboardingFocusSurface.FAVORITES)
                                requestOnboardingFocus(
                                    authority,
                                    focusIntent,
                                    OnboardingFocusSurface.FAVORITES,
                                    favoritesGrid.takeReturnFocus() ?: OnboardingFavoritesPlatformFilterKey,
                                )
                            }
                            action == ControllerAction.CANCEL ||
                                action == ControllerAction.SYSTEM_BACK -> {
                                // B closes the list WITHOUT choosing -- backing out of a menu is an
                                // answer, and it must not silently apply whatever the cursor was over.
                                favoritesGrid.closeFilter()
                                focusIntent.clear(OnboardingFocusSurface.FAVORITES)
                                requestOnboardingFocus(
                                    authority,
                                    focusIntent,
                                    OnboardingFocusSurface.FAVORITES,
                                    favoritesGrid.takeReturnFocus() ?: OnboardingFavoritesPlatformFilterKey,
                                )
                            }
                            else -> Unit
                        }
                        return true
                    }

                    val keys = lists.keys
                    // Entry is the first GAME, not the first key: the page opens on the boxes it exists
                    // to show, not on the controls above them. Matches the screen's own entryKey exactly
                    // -- the two must not disagree about where a player who has not moved yet logically is.
                    val entryFocus = keys.getOrNull(headKeys.size) ?: OnboardingFavoritesSearchKey
                    val focused = onboardingLogicalFocus(
                        keys = keys,
                        authorityFocus = authority.state.value.focused,
                        entryFocus = entryFocus,
                        pendingFocus = focusIntent.pendingTarget(OnboardingFocusSurface.FAVORITES, keys),
                    )
                    when {
                        // The grid's own mover, not the flat-index one: this page's keys are not a single
                        // line. Same reason PROFILE has moveOnboardingProfileFocus -- see its call site.
                        action.isDirectional -> onboardingFavoritesFocusDestination(
                            keys = keys,
                            current = focused,
                            action = action,
                            columns = favoritesGrid.columns,
                            headSize = headKeys.size,
                        )?.let { target ->
                            requestOnboardingFocus(
                                authority,
                                focusIntent,
                                OnboardingFocusSurface.FAVORITES,
                                target,
                            )
                        }
                        // B closes the Tome -- the same gesture that leaves the real Tome library, and the
                        // reason this page needs no Continue pill of its own. Focus returns to the button
                        // that opened it rather than to the top of the instructions.
                        action == ControllerAction.CANCEL || action == ControllerAction.SYSTEM_BACK -> {
                            favoritesGrid.closeTome()
                            focusIntent.clear(OnboardingFocusSurface.FAVORITES)
                            requestOnboardingFocus(
                                authority,
                                focusIntent,
                                OnboardingFocusSurface.FAVORITES,
                                OnboardingFavoritesOpenTomeKey,
                            )
                        }
                        // Y opens the filter in onboarding's open Tome section. An earlier binding was placed on INTRO,
                        // where there is no filter, leaving Y inactive here unless explicitly handled.
                        //
                        // It opens the list from anywhere on the page: the point of a shortcut is not
                        // having to walk to the chip first. Focus is remembered so closing comes back to
                        // the game the player was standing on, exactly as the chip's own path does.
                        // Open only, not a toggle -- B closes the dropdown, and the library's Y never
                        // closes what it opened either.
                        //
                        // Gated on the chip EXISTING. A single-platform library has no filter in
                        // [onboardingFavoritesHeadKeys], so an ungated Y would raise a dropdown the page
                        // offers no other way to reach -- the same shape of wedge the INTRO binding was.
                        // Falling to `else -> return false` is the honest answer: Y means nothing here.
                        action == ControllerAction.TERTIARY &&
                            OnboardingFavoritesPlatformFilterKey in headKeys ->
                            favoritesGrid.openFilter(from = focused)
                        action == ControllerAction.SYSTEM_SELECT || action == ControllerAction.CONFIRM ->
                            when (focused) {
                                OnboardingFavoritesSearchKey -> favoritesGrid.openKeyboard(from = focused)
                                // The filter is not a game, so there is no toggle/launch distinction to
                                // preserve: both buttons open the list, and a control that answered only
                                // one of the two would be the kind of dead-feeling control QA-16 already
                                // found once.
                                OnboardingFavoritesPlatformFilterKey -> favoritesGrid.openFilter(from = focused)
                                else -> lists.visibleGame(focused)?.let(onToggleFavorite)
                            }
                        else -> return false
                    }
                    return true
                }
            }
        }

        OnboardingStep.THEME -> {
            if (action.isDirectional || action == ControllerAction.CONFIRM) {
                focusIntent.deferAction(OnboardingFocusSurface.THEME, action)
                return true
            }
            if (action == ControllerAction.CANCEL || action == ControllerAction.SYSTEM_BACK) {
                focusIntent.clear(OnboardingFocusSurface.THEME)
            }
            return dispatchThemeGallery(
                authority = authority,
                scope = OnboardingScope,
                action = action,
                onPick = { id -> onApplyTheme(id); onAdvance() },
                onDismiss = onAdvance,
            )
        }

        // FINISH, the one DONE page: one focusable, Enter Mimir. CONFIRM finishes exactly
        // as before; there is no page to go back to, so CANCEL/SYSTEM_BACK is swallowed like the root's.
        OnboardingStep.DONE -> {
            val keys = listOf(OnboardingDoneKey)
            when {
                action.isDirectional -> moveOnboardingFocus(
                    authority = authority,
                    focusIntent = focusIntent,
                    surface = OnboardingFocusSurface.DONE,
                    keys = keys,
                    entryFocus = OnboardingDoneKey,
                    action = action,
                )
                action == ControllerAction.CONFIRM -> onAdvance()
            }
        }
    }
    return true
}

internal fun onboardingProfileHintVisible(profile: OnboardingProfileState): Boolean =
    !profile.keyboardOpen && !profile.confirmOpen

/**
 * The PROFILE hint uses "Select" for A wherever focus is. The separate instruction names typing:
 * "Press A on the field, or tap it, to type."
 */
@get:StringRes
internal val OnboardingProfileHintAction: Int = R.string.app_onboarding_profile_hint_action

internal fun onboardingConfirmPillGold(keepFocused: Boolean, continueFocused: Boolean): Boolean =
    keepFocused || continueFocused

/**
 * A device report on 2026-08-19 found low focus-ring contrast on onboarding's gold pills
 * (BEGIN, CONTINUE, etc). Confirmed mechanism: the gold
 * fill IS `palette.id.ctaMaterial()`, and `MimirTokens.kt`'s [mimirFocusRing] paints the FOCUSED ring in
 * `palette.focusRing`. On every authored theme where `focusRing` is left at its default (`accent`) —
 * DEFAULT, FREYR, SURTR, MANI, YGGDRASIL — `accent` is byte-identical to one of `ctaMaterial()`'s own
 * three stops (DEFAULT: both equal `0xFFD4AF69`, SURTR's own comment: "the whole CTA/focus system is
 * tuned to it"), so the ring lands on a colour the fill already contains. Same collision shape as the
 * Done key (`MimirKeyboard.kt`'s `keyboardDoneFocusedTop`/`Bottom`) and Brightness/Utility's "Allow"
 * (`BrightnessOverlay.kt`'s `utilityPrimaryActionFocusedFill`) — both fixed the same way and both cited
 * here as precedent. `OnboardingPill` has no focus scale (the ring family has none), so like those two it has
 * no scale-growth fallback to lean on either.
 *
 * That fix shifted the FOCUSED fill 22% toward the pill's ink (`onAccent`, near-black), after the Done key
 * and Utility's "Allow". **It did not work: the focused confirmation button was reported as insufficiently highlighted.** Toward near-black is DOWN: the focused pill came out duller than
 * the resting one beside it, which reads as pressed or disabled, never as chosen. And the ring was still
 * gold on gold.
 *
 * So the pill now does what the tiles do. Its focus is the lit ring OUTSIDE its edge
 * (`mimirSelectionRing` with `filled = true`: `MimirFilledControlRingGap` of ground between them), and the
 * focused fill is LIGHTER than the resting one: each stop lifted toward white, the direction the tile ring's
 * own light already goes. The ring is Home's `tileFocusRing` (one ring colour
 * everywhere); the resting fill is untouched.
 */
internal fun onboardingGoldPillFill(palette: MimirThemePalette, focused: Boolean): List<Color> {
    val authored = palette.id.ctaMaterial()
    if (!focused) return authored
    return authored.map { lerp(it, Color.White, OnboardingGoldPillFocusedLift) }
}

/** How far each stop of a FOCUSED gold pill is lifted toward white. */
private const val OnboardingGoldPillFocusedLift = 0.18f

internal fun onboardingAccessComplete(
    access: OnboardingAccessUi,
    review: OnboardingAccessReviewState,
// The keyboard term was dropped here with its screen . Setup no longer waits on
// a keyboard choice at all: nothing in onboarding asks for one, so nothing may hold completion open for it.
// OR-12: nor on the Tweaker's usage access, which the Tweaker asks for where it is used.
): Boolean = onboardingAccessCapabilities(access).none { onboardingAccessNeedsReview(it, access, review) }

/** [OnboardingAccessScreenId] → the [OnboardingAccessCapability] it renders, or null for the two screens
 * (ORIENTATION, OVERLAY) that aren't members of that enum (QA-10 for the overlay). */
private fun OnboardingAccessScreenId.asCapability(): OnboardingAccessCapability? = when (this) {
    OnboardingAccessScreenId.FILES -> OnboardingAccessCapability.FILES
    OnboardingAccessScreenId.USAGE -> OnboardingAccessCapability.USAGE
    OnboardingAccessScreenId.HOME_ROLE -> OnboardingAccessCapability.HOME_ROLE
    OnboardingAccessScreenId.ORIENTATION,
    OnboardingAccessScreenId.OVERLAY,
    -> null
}

/**
 * Focus keys for ONE screen of the sub-sequence — the one-at-a-time replacement for the old
 * `onboardingAccessFocusKeys`, which built one combined list across every stacked row. Granted → just the
 * per-screen Continue (press-to-continue on grant, not auto-advance). Not granted → action key (if this
 * capability currently offers one — reuses [onboardingAccessActionKey]'s exact availability test, unchanged)
 * plus the defer key — rendered whether or not the capability is already deferred, so revisiting a deferred
 * screen via Back re-offers the ask rather than going read-only (spec's Back ruling: "revisit a decision").
 */
internal fun onboardingAccessScreenFocusKeys(
    id: OnboardingAccessScreenId,
    access: OnboardingAccessUi,
): List<FocusKey> {
    if (onboardingAccessScreenGranted(id, access)) return listOf(OnboardingAccessContinueKey)
    if (id == OnboardingAccessScreenId.ORIENTATION) return listOf(OnboardingAccessContinueKey)
    // QA-10: one action key (Android's own overlay-permission screen for Mimir) plus the defer key.
    if (id == OnboardingAccessScreenId.OVERLAY) {
        return listOf(OnboardingOverlayAccessKey, OnboardingOverlayDeferKey)
    }
    val capability = requireNotNull(id.asCapability()) {
        "Every OnboardingAccessScreenId except ORIENTATION/OVERLAY must map to a capability"
    }
    return buildList {
        onboardingAccessActionKey(capability, access)?.let(::add)
        add(onboardingAccessDeferKey(capability))
    }
}

private fun onboardingAccessActionKey(
    capability: OnboardingAccessCapability,
    access: OnboardingAccessUi,
): FocusKey? = when (capability) {
    OnboardingAccessCapability.FILES -> OnboardingFilesAccessKey
    OnboardingAccessCapability.USAGE -> OnboardingUsageAccessKey.takeIf { access.usageAvailable }
    OnboardingAccessCapability.HOME_ROLE -> OnboardingHomeRoleKey.takeIf { access.homeRoleAvailable }
}

private fun onboardingAccessDeferKey(capability: OnboardingAccessCapability): FocusKey = when (capability) {
    OnboardingAccessCapability.FILES -> OnboardingFilesDeferKey
    OnboardingAccessCapability.USAGE -> OnboardingUsageDeferKey
    OnboardingAccessCapability.HOME_ROLE -> OnboardingHomeRoleDeferKey
}

/**
 * A press while LIBRARY shows the action pages. A on Next turns the page (and on the last one ends them), A on Skip
 * ends them, B goes one page back, and the pad moves between the two pills. Once the scan has failed, the next press
 * ends the pages onto the failure (never mid-page, never on its own).
 */
private fun dispatchOnboardingActionTour(
    authority: FocusAuthority,
    focusIntent: OnboardingFocusIntentState,
    tour: OnboardingActionTourState,
    status: OnboardingLibraryStatus,
    action: ControllerAction,
) {
    val keys = OnboardingActionTourKeys
    val focused = onboardingLogicalFocus(
        keys = keys,
        authorityFocus = authority.state.value.focused,
        entryFocus = OnboardingActionTourNextKey,
        pendingFocus = focusIntent.pendingTarget(OnboardingFocusSurface.LIBRARY, keys),
    )
    val failed = status == OnboardingLibraryStatus.FAILED
    when (action) {
        ControllerAction.NAV_LEFT, ControllerAction.NAV_RIGHT,
        ControllerAction.NAV_UP, ControllerAction.NAV_DOWN,
        -> moveOnboardingFocus(
            authority = authority,
            focusIntent = focusIntent,
            surface = OnboardingFocusSurface.LIBRARY,
            keys = keys,
            entryFocus = OnboardingActionTourNextKey,
            action = action,
        )
        ControllerAction.CONFIRM -> if (failed || focused == OnboardingActionTourSkipKey) tour.skip() else tour.next()
        ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK -> if (failed) tour.skip() else tour.back()
        else -> Unit
    }
}

private fun moveOnboardingFocus(
    authority: FocusAuthority,
    focusIntent: OnboardingFocusIntentState,
    surface: OnboardingFocusSurface,
    keys: List<FocusKey>,
    entryFocus: FocusKey,
    action: ControllerAction,
) {
    val current = onboardingLogicalFocus(
        keys = keys,
        authorityFocus = authority.state.value.focused,
        entryFocus = entryFocus,
        pendingFocus = focusIntent.pendingTarget(surface, keys),
    )
    onboardingFocusDestination(keys, current, action)?.let { target ->
        requestOnboardingFocus(authority, focusIntent, surface, target)
    }
}

/** PROFILE's own mover — see the KDoc at its `dispatchOnboarding` call site for why this screen needs a
 *  spatial destination function instead of [moveOnboardingFocus]'s flat-index one. */
private fun moveOnboardingProfileFocus(
    authority: FocusAuthority,
    focusIntent: OnboardingFocusIntentState,
    keys: List<FocusKey>,
    entryFocus: FocusKey,
    action: ControllerAction,
) {
    val current = onboardingLogicalFocus(
        keys = keys,
        authorityFocus = authority.state.value.focused,
        entryFocus = entryFocus,
        pendingFocus = focusIntent.pendingTarget(OnboardingFocusSurface.PROFILE, keys),
    )
    val target = onboardingProfileFocusDestination(current, action)
    requestOnboardingFocus(authority, focusIntent, OnboardingFocusSurface.PROFILE, target)
}

/**
 * PROFILE's directional destination, spatial rather than flat (contrast [onboardingFocusDestination]):
 * the name field is a lone row above a horizontal pair (Continue, Not now). UP always returns to the
 * field from anywhere on the screen; LEFT/RIGHT move only within the button row (and no-op past either
 * end, same `coerceIn`-to-self shape as the flat model uses for its own ends); DOWN from the field lands
 * on Continue — the row's own entry default — with no memory of a previously-focused button, matching the horizontal-button-pair-below-field layout rather than a fancier column-preserving
 * one. [current] not in the button row (i.e. on the field, or unset) makes LEFT/RIGHT a no-op, same as
 * DOWN already being a no-op from either button — neither row has a horizontal or vertical neighbor there.
 * Layout caveat: [OnboardingActionGroup] stacks Continue/Not now vertically below ~420dp width
 * ([onboardingActionsStack]); this function (like [onboardingFocusDestination] before it, and every other
 * `dispatchOnboarding` branch) has no layout awareness and always assumes the wide/horizontal arrangement,
 * which every certified target (Odin, RP5 — landscape handhelds, always far wider than 420dp) actually
 * renders.
 */
internal fun onboardingProfileFocusDestination(current: FocusKey?, action: ControllerAction): FocusKey {
    val bottomRow = listOf(OnboardingContinueKey, OnboardingNotNowKey)
    return when (action) {
        ControllerAction.NAV_UP -> OnboardingNameFieldKey
        ControllerAction.NAV_DOWN ->
            if (current == OnboardingNameFieldKey) OnboardingContinueKey else current ?: OnboardingContinueKey
        ControllerAction.NAV_LEFT, ControllerAction.NAV_RIGHT -> {
            val index = bottomRow.indexOf(current)
            if (index < 0) {
                current ?: OnboardingContinueKey
            } else {
                val delta = if (action == ControllerAction.NAV_RIGHT) 1 else -1
                bottomRow[(index + delta).coerceIn(0, bottomRow.lastIndex)]
            }
        }
        else -> current ?: OnboardingContinueKey
    }
}

/**
 * The key a step opens on: the first control that deals with what the step ASKS FOR, never the control
 * that leaves it.
 *
 *  Two steps did exactly that. PROFILE asks for a
 * name and opened on Continue, so one A committed an empty name and moved on. METADATA asks the player to
 * connect the art sources, and opened on Continue -- so one A skipped both and produced a library with no
 * art, which is the complaint this whole branch started from, reachable in a single press.
 *
 * [advance] is passed rather than inferred. "The key that leaves" is not a property a [FocusKey] carries,
 * and a heuristic on the key's name would be a guess that stops working silently the first time one is
 * renamed.
 *
 * **A step whose only key IS the advance control still gets it**, which is why this returns a key rather
 * than refusing. That is not a loophole: it is the case where the step asks for nothing. The ACCESS
 * screens depend on it -- `onboardingAccessScreenFocusKeys` returns the lone Continue once a capability is
 * granted, and already puts the grant key first while it is not, so those screens are correct without
 * being routed through here. LIBRARY at DONE and SETTLING at READY are the same shape: the ask is already
 * satisfied, so Continue is the honest default.
 */
internal fun onboardingEntryFocus(keys: List<FocusKey>, advance: FocusKey): FocusKey =
    keys.firstOrNull { it != advance } ?: advance

/**
 * PROFILE's focusable keys, in reading order: the field it asks you to fill, then the way on, then the
 * way past.
 *
 * Built here rather than at each site because [OnboardingProfileScreen] and this step's `dispatchOnboarding`
 * branch both need it, and both feed it to [onboardingEntryFocus]. Two copies would be two chances for the
 * ring the player can see and the action the next press takes to disagree about where focus is -- which is
 * the exact class of defect the entry-focus rule exists to close.
 */
internal fun onboardingProfileFocusKeys(): List<FocusKey> =
    listOf(OnboardingNameFieldKey, OnboardingContinueKey, OnboardingNotNowKey)

/**
 * METADATA's focusable keys. The art-account row drops out once an account is connected, which is the
 * only thing that changes this step's shape -- and the shorter shape is what a returning player sees.
 *
 * Same single-source reason as [onboardingProfileFocusKeys].
 */
internal fun onboardingMetadataFocusKeys(artworkAccountConnected: Boolean): List<FocusKey> = buildList {
    if (!artworkAccountConnected) add(OnboardingArtworkSetupKey)
    add(OnboardingSteamGridDbKey)
    add(OnboardingRetroAchievementsKey)
    add(OnboardingMetadataContinueKey)
}

internal fun onboardingLogicalFocus(
    keys: List<FocusKey>,
    authorityFocus: FocusKey?,
    entryFocus: FocusKey?,
    pendingFocus: FocusKey?,
): FocusKey? = pendingFocus?.takeIf { it in keys }
    ?: authorityFocus?.takeIf { it in keys }
    ?: entryFocus?.takeIf { it in keys }
    ?: keys.firstOrNull()

internal fun requestOnboardingFocus(
    authority: FocusAuthority,
    focusIntent: OnboardingFocusIntentState,
    surface: OnboardingFocusSurface,
    target: FocusKey,
) {
    focusIntent.deferTarget(surface, target)
    authority.requestFocus(target)
    if (authority.state.value.focused == target) focusIntent.consumeTarget(surface, target)
}

internal fun onboardingFocusDestination(
    keys: List<FocusKey>,
    current: FocusKey?,
    action: ControllerAction,
): FocusKey? {
    if (keys.isEmpty()) return null
    val index = keys.indexOf(current).takeIf { it >= 0 } ?: 0
    val delta = when (action) {
        ControllerAction.NAV_RIGHT, ControllerAction.NAV_DOWN -> 1
        ControllerAction.NAV_LEFT, ControllerAction.NAV_UP -> -1
        else -> 0
    }
    return keys[(index + delta).coerceIn(0, keys.lastIndex)]
}

/**
 * Land focus on [key], reliably. [FocusAuthority.requestFocus] is a no-op until the element registers its
 * bounds (onGloballyPositioned fires after layout), so a single-frame request can lose the race when a modal
 * opens over an already-focused surface (the prior focus would stick). Retry across a few frames until focus
 * actually lands on [key].
 *
 * **When the retries run out, the pending target is dropped** (recorded trace, 2026-09-18). A
 * request the authority refused (a key that never registered, like the zero-width search field) used to
 * stay pending after these eight frames. [onboardingLogicalFocus] prefers a pending target over the
 * authority's focus, so the dispatch kept acting from a key the ring never showed. The next DOWN was spent
 * getting back to where the ring already was. CONFIRM never re-requests focus, so it never overwrote the
 * target and acted on the invisible key indefinitely: A opened the search keyboard instead of marking the
 * ringed game. Eight frames is this function's own measure of "arriving soon". A target that has not
 * arrived by then is dropped, and the dispatch falls back to the focus the player can see.
 *
 * Only if nothing newer was asked for in the meantime. A request made during the last frame bumps
 * [OnboardingFocusIntentState.revision] and restarts the caller's effect, so that target is the new run's to
 * chase, not this run's to drop. THEME never comes through here: its queued actions have their own replay.
 *
 * Internal for [OnboardingLandFocusGiveUpTest], which drives it with a fake frame clock.
 */
internal suspend fun FocusAuthority.landFocus(
    focusIntent: OnboardingFocusIntentState,
    surface: OnboardingFocusSurface,
    entryFocus: FocusKey,
    validKeys: List<FocusKey>,
) {
    focusIntent.retain(surface)
    val chasing = focusIntent.revision
    repeat(8) {
        withFrameNanos { }
        if (landOnboardingFocusNow(focusIntent, surface, entryFocus, validKeys)) return
    }
    if (focusIntent.revision == chasing) focusIntent.clear(surface)
}

/**
 * One registration-aware landing attempt, shared by the frame retry and human-sequence unit tests.
 *
 * **Hazard, worth reading before wiring a new surface to this.** The first line treats "focus is already on
 * SOME valid key" as success and returns without moving. That is right for every surface whose key sets are
 * DISJOINT across states — a state change invalidates the old focus, so the landing proceeds — and wrong for
 * any surface whose key list GROWS while the currently focused key stays valid. There the landing reports
 * success and focus never reaches [entryFocus].
 *
 * QA-16's favourites step was the first surface of that shape and is the worked example: its rows arrive
 * asynchronously, so the first composition offered only the Continue pill, focus landed there, and when the
 * games appeared the pill was still valid — every player began on "Skip for now" and A advanced past the
 * step. See [OnboardingFavoritesScreen], which re-asserts entry focus once on the empty-to-non-empty
 * transition.
 *
 * Audited 2026-09-16 across every `landFocus` call site in this file; FAVORITES was the only one. WELCOME,
 * PROFILE and PROFILE_CONFIRM use literal key lists. LIBRARY switches between disjoint per-status lists.
 * METADATA can gain a key, but its `entryFocus` is the always-present Continue, so staying put is correct.
 * SETTLING's `canRetry` and `canContinue` are mutually exclusive (`MimirOnboardingCoordinator`: the FAILED
 * branch sets only the first, every other branch only the second), so its list never grows. ACCESS swaps
 * `[continue]` for `[action, defer]`, disjoint. DONE has one key, Enter Mimir.
 *
 * The risk is therefore not "computed keys" but specifically **an asynchronously loaded list**. Onboarding
 * has exactly one. If you add a second, re-assert entry focus when it fills.
 */
internal fun FocusAuthority.landOnboardingFocusNow(
    focusIntent: OnboardingFocusIntentState,
    surface: OnboardingFocusSurface,
    entryFocus: FocusKey,
    validKeys: List<FocusKey>,
): Boolean {
    val pending = focusIntent.pendingTarget(surface, validKeys)
    if (pending == null && state.value.focused in validKeys) return true
    val target = pending ?: entryFocus
    requestFocus(target)
    if (state.value.focused != target) return false
    focusIntent.consumeTarget(surface, target)
    return true
}

private fun onboardingThemeKeys(authority: FocusAuthority): List<FocusKey> =
    authority.registeredKeysInScope(OnboardingScope)
        .filter { it.value.startsWith("${OnboardingScope.value}/theme/") }

private suspend fun FocusAuthority.replayDeferredThemeActions(
    focusIntent: OnboardingFocusIntentState,
    onApplyTheme: (MimirThemeId) -> Unit,
    onAdvance: () -> Unit,
) {
    focusIntent.retain(OnboardingFocusSurface.THEME)
    if (focusIntent.pendingActions(OnboardingFocusSurface.THEME).isEmpty()) return
    repeat(8) {
        withFrameNanos { }
        val keys = onboardingThemeKeys(this)
        if (keys.size != MimirThemeId.entries.size || state.value.focused !in keys) return@repeat
        // Let the gallery's own entry-focus effect finish before applying the user's queued move.
        withFrameNanos { }
        val actions = focusIntent.takeActions(OnboardingFocusSurface.THEME)
        actions.forEach { action ->
            dispatchThemeGallery(
                authority = this,
                scope = OnboardingScope,
                action = action,
                onPick = { id -> onApplyTheme(id); onAdvance() },
                onDismiss = onAdvance,
            )
            if (action == ControllerAction.CONFIRM) return
        }
        return
    }
}

/**
 * Continue from the profile field: a non-blank name is trimmed without changing authored case, committed,
 * and advances; a blank name
 * raises the "Create your profile?" confirm (the user must acknowledge continuing without a name). Shared by
 * the touch path (Continue pill) and the controller path (A on Continue).
 */
internal fun openProfileConfirm(
    profile: OnboardingProfileState,
    focusIntent: OnboardingFocusIntentState,
) {
    focusIntent.deferTarget(OnboardingFocusSurface.PROFILE_CONFIRM, OnboardingConfirmKeepKey)
    profile.openConfirm()
}

private fun profileAttemptContinue(
    profile: OnboardingProfileState,
    focusIntent: OnboardingFocusIntentState,
    onCommitName: (String) -> Unit,
    onAdvance: () -> Unit,
) {
    val name = profile.draft.trim()
    if (name.isEmpty()) {
        openProfileConfirm(profile, focusIntent)
    } else {
        onCommitName(name)
        profile.reset()
        onAdvance()
    }
}

private fun activateSettlementContinue(
    settlement: OnboardingSettlementUi,
    onAdvance: () -> Unit,
    onContinueLocally: () -> Unit,
) {
    if (!settlement.canContinue) return
    when (settlement.phase) {
        OnboardingSettlementPhase.READY,
        OnboardingSettlementPhase.LIMITED,
        -> onAdvance()
        else -> {
            onContinueLocally()
            onAdvance()
        }
    }
}

private fun settlementEntryFocus(settlement: OnboardingSettlementUi): FocusKey? = when {
    settlement.phase == OnboardingSettlementPhase.READY && settlement.canContinue ->
        OnboardingSettlementContinueKey
    settlement.canRetry -> OnboardingSettlementRetryKey
    settlement.canContinue -> OnboardingSettlementContinueKey
    else -> null
}

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// Screen 1 — Welcome-lore ceremony
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * How long the Eoksadberg verse shows ALONE on WELCOME's first appearance before the rest of the screen
 * arrives.  -- about
 * the time its ten words take to read at 238 words a minute (Brysbaert 2019), the length three of the four
 * seats chose. It is not a wait: Begin is focused throughout (it paints with the rest, 2026-10-15), and any press ends it.
 */
internal const val OnboardingWelcomeVerseAloneMs = 2_500L

/**
 * WELCOME's first appearance (policy 2026-09-28, Q1, superseding QA-03 in time only): the verse arrives
 * alone -- Begin is focused from frame 0 but, like the sky (stars and planet), paints with the rest (2026-10-15, O-10) --
 * and after [OnboardingWelcomeVerseAloneMs] the lockup, title and promise fade in on one clock. The verse keeps its seat after the functional copy, so the resting
 * screen, and every fit measurement of it, is exactly today's.
 *
 * [settle] ends it at once: any controller press ([dispatchOnboarding]'s WELCOME branch) or touch (the
 * screen's Initial-pass observer) calls it, and neither consumes the input, so A still lands on Begin. A
 * settled intro does not replay. Held once by [MimirNavigationCoordinator], like [OnboardingProfileState],
 * because the render and the dispatch must agree on it.
 */
internal class OnboardingWelcomeIntroState {
    var settled by mutableStateOf(false)
        private set

    fun settle() {
        settled = true
    }
}

/**
 * How much WELCOME's two measured paragraphs (the promise's 420 dp, the verse's 460 dp) widen at [fontScale]: 1 at the
 * default size and below, the scale itself above it up to the 2.0 the system offers. The viewport still caps them.
 */
internal fun onboardingWelcomeMeasureWidening(fontScale: Float): Float =
    if (fontScale.isFinite() && fontScale > 1f) fontScale.coerceAtMost(2f) else 1f

/**
 * The one-time literary welcome (shipping identity-gate stage 1): the Eoksadberg "Well of Memory" tone before
 * asking for anything, plus the collapsed setup reassurance ("nothing is moved or copied", policy — the
 * two shipping welcome beats become one). Centered ceremony over the themed atmosphere; `Begin` is the only
 * focusable and is focused on entry. The verse appears first and alone; see [OnboardingWelcomeIntroState].
 */
@Composable
private fun OnboardingWelcomeScreen(
    authority: FocusAuthority,
    focusIntent: OnboardingFocusIntentState,
    onBegin: () -> Unit,
    // The flow's: whether the verse plays alone on this first appearance, and the rest of the screen's arrival -- Begin, the
    // lockup, the title and the promise here, and the sky (stars and planet) in [OnboardingFlowSky].
    playIntro: Boolean,
    restProgress: Animatable<Float, AnimationVector1D>,
    intro: OnboardingWelcomeIntroState = remember { OnboardingWelcomeIntroState() },
) {
    val palette = LocalMimirPalette.current
    val focus by authority.state.collectAsState()
    val beginFocused = focus.scope == OnboardingScope && focus.focused == OnboardingBeginKey

    LaunchedEffect(focusIntent.revision) {
        authority.landFocus(
            focusIntent = focusIntent,
            surface = OnboardingFocusSurface.WELCOME,
            entryFocus = OnboardingBeginKey,
            validKeys = listOf(OnboardingBeginKey),
        )
    }

    // Q1's clock. Under reduced motion, or once settled, the screen is born at rest -- today's screen.
    val verseProgress = remember { Animatable(if (playIntro) 0f else 1f) }
    var restArriving by remember { mutableStateOf(!playIntro) }
    LaunchedEffect(intro.settled) {
        if (intro.settled || !playIntro) {
            verseProgress.snapTo(1f)
            restProgress.snapTo(1f)
            restArriving = true
            intro.settle()
            return@LaunchedEffect
        }
        // Let the empty frame present before the verse arrives, as the badge ignite does.
        withFrameNanos { }
        verseProgress.animateTo(1f, tween(OnboardingIntroShineMs, easing = MimirMotion.Easing))
        kotlinx.coroutines.delay(OnboardingWelcomeVerseAloneMs - OnboardingIntroShineMs)
        restArriving = true
        restProgress.animateTo(1f, tween(OnboardingIntroShineMs, easing = MimirMotion.Easing))
        intro.settle()
    }
    // Read in the layer phase, so the fade never recomposes the screen.
    val restLayer = Modifier.graphicsLayer { alpha = restProgress.value }
    // Begin arrives with the rest (2026-10-15, O-10: it stood at full alpha 1.3 s before the verse). It is focused and
    // answers a press throughout; only its paint waits. ModulateAlpha, not a layer sized to the pill: its ring and flare reach past it.
    val restPillLayer = Modifier.graphicsLayer {
        alpha = restProgress.value
        compositingStrategy = CompositingStrategy.ModulateAlpha
    }

    Box(
        Modifier
            .fillMaxSize()
            // Touch ends the intro too. Initial pass and never consumed, so a tap on Begin still begins.
            .pointerInput(intro) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press) intro.settle()
                    }
                }
            },
    ) {
    OnboardingScaffold(
        content = {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = restLayer) {
            OnboardingHeaderLockup(palette, animateBadgeOnEntry = true, igniteArmed = restArriving)
        }

        // Fit: this screen's spacer total is the
        // largest in the flow, so it is the first to overflow a short viewport. Earlier spacing took
        // the two call sites below the header and above the Begin pill from xxl to xl/lg, which bought
        // back the ~34dp it was overflowing the Odin reference viewport by.
        //
        // That was not enough for a SHORTER viewport. Measured 2026-09-22 on the QA emulator
        // (914.3x411.4dp landscape — 57dp shorter than the Odin, and STANDARD, so the no-scroll branch
        // applies and nothing hides an overflow): WELCOME's natural height was 448px against 411.4
        // available, **36.6px over**. On the device that sliced the Begin pill — the screen's only
        // focusable control — in half against the bottom edge, with no scroll to reveal it.
        //
        // All four spacers in this column are now `sm`. No token VALUE changed and the header/wordmark
        // stay full-size: this is still the ceremony screen, and the trim comes out of the gaps, not the
        // type. `OnboardingScreenFitTest`'s matrix carries the QA-emulator row that measures this.
        //
        // The footer reserves the pill's whole ring and flare
        // ([OnboardingFooterClearance], 7 dp more), which put WELCOME 5 dp over the 700x350 floor. This gap and
        // the one above the verse go sm -> xs; the title keeps its sm to the line under it.
        Spacer(Modifier.height(MimirSpacing.xs))

        MimirChromeText(
            text = stringResource(R.string.app_onboarding_welcome_title),
            style = MimirType.TitleCaps,
            color = palette.textPrimary,
            textAlign = TextAlign.Center,
            modifier = restLayer,
        )

        Spacer(Modifier.height(MimirSpacing.sm))

        // At a raised font scale the measure widens with the words (up to the viewport), so a larger promise takes fewer
        // lines and the page fits where it can; at 1.0 it is the 420 dp it always was (the 2026-10-06 large-font debt).
        val measureWidening = onboardingWelcomeMeasureWidening(LocalDensity.current.fontScale)
        Text(
            text = stringResource(R.string.app_onboarding_welcome_promise),
            style = MimirType.RowSupport,
            color = palette.textMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 420.dp * measureWidening).then(restLayer),
        )

        // the CONTROLLER-READY eyebrow and the
        // "Use the D-pad to move and press A to choose. Touch works too." line are cut. The controls are taught
        // where they are used: the Begin pill's focus ring, PROFILE's hint bar, FINISH's legend.
        Spacer(Modifier.height(MimirSpacing.xs))

        // The verse sits after the functional copy, keeping instructions together, but appears first
        // alone for [OnboardingWelcomeVerseAloneMs]. One semantics node lets a screen reader read
        // the line and its attribution together. Left-ruled italic-serif blockquote + gold small caps.
        Row(
            modifier = Modifier
                .widthIn(max = 460.dp * measureWidening)
                .graphicsLayer {
                    val progress = verseProgress.value
                    alpha = progress
                    scaleX = MimirGlassMotion.PanelEnterScale + (1f - MimirGlassMotion.PanelEnterScale) * progress
                    scaleY = MimirGlassMotion.PanelEnterScale + (1f - MimirGlassMotion.PanelEnterScale) * progress
                }
                .semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.spacedBy(MimirSpacing.lg),
        ) {
            Box(
                Modifier
                    .width(2.dp)
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(palette.accent),
            )
            Column(verticalArrangement = Arrangement.spacedBy(MimirSpacing.xs)) {
                Text(
                    text = stringResource(R.string.app_onboarding_welcome_verse),
                    color = palette.textSecondary,
                    fontFamily = FontFamily.Serif,
                    fontStyle = FontStyle.Italic,
                    fontSize = 18.sp,
                    lineHeight = 26.sp,
                )
                Text(
                    text = stringResource(R.string.app_onboarding_welcome_verse_attribution),
                    style = MimirType.RunicEyebrow,
                    color = palette.accentBright,
                    letterSpacing = 0.18.em,
                )
            }
        }

        // 700x350 (viewport floor, 2026-09-28): with the controller lines cut, WELCOME still measured 356 dp
        // there. This gap and the verse's attribution gap go sm -> xs, the same token family one step down, as
        // the 2026-09-22 trim did; the type and the ceremony lockup are untouched.
        Spacer(Modifier.height(MimirSpacing.xs))
    },
    controls = {
        Box(restPillLayer) {
            OnboardingPill(
                label = stringResource(R.string.app_onboarding_welcome_begin),
                focusKey = OnboardingBeginKey,
                gold = true,
                focused = beginFocused,
                authority = authority,
                onActivate = onBegin,
            )
        }
    },
    )
    }
}

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// Screen 2 — Profile name (optional)
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * The optional profile-name step (shipping renderAuth profile stage). Controller-first: the user lands on
 * `Continue` and can proceed with A alone — no name required, no keyboard needed. Tapping the field opens the
 * touch keyboard; an empty Continue raises the "Create your profile?" confirm. The name is the WHOLE profile
 * (an optional display label over the stable `LOCAL_PROFILE_ID` — no new entity; policy).
 */
@Composable
private fun OnboardingProfileScreen(
    authority: FocusAuthority,
    profile: OnboardingProfileState,
    focusIntent: OnboardingFocusIntentState,
    onCommitName: (String) -> Unit,
    onAdvance: () -> Unit,
) {
    val palette = LocalMimirPalette.current
    val focus by authority.state.collectAsState()
    val focusReveal = remember { OnboardingFocusRevealState() }
    val modalOpen = profile.keyboardOpen || profile.confirmOpen

    // Land on the NAME FIELD on entry -- the thing this step asks for. It was Continue until 2026-09-18,
    // which meant one A committed an empty name and moved on; see [onboardingEntryFocus]. The dispatch
    // branch resolves the same key from the same function, because a screen and its input handler
    // disagreeing about where an untouched player logically is produces a ring in one place and an action
    // in another.
    //
    // When a sub-modal closes, `validKeys` below now includes the name field
    // (2026-08-20 dpad-reachability fix), so this no longer unconditionally snaps back to Continue: if the
    // keyboard was opened from the field via a gamepad CONFIRM, `authority.state.focused` is still the field
    // when it closes, that key is a valid landing target, and `landOnboardingFocusNow` leaves it there —
    // same as an Android text field keeping focus after the IME dismisses. A touch-opened keyboard never
    // moves `authority.state.focused` (the tap doesn't call `requestFocus`), so that path falls through to
    // the entry focus above -- which is now the name field itself, not Continue.
    LaunchedEffect(modalOpen, profile.confirmEpoch, focusIntent.revision) {
        when {
            profile.confirmOpen -> focusIntent.retain(OnboardingFocusSurface.PROFILE_CONFIRM)
            profile.keyboardOpen -> focusIntent.clear(OnboardingFocusSurface.PROFILE)
            else -> {
                val keys = onboardingProfileFocusKeys()
                authority.landFocus(
                    focusIntent = focusIntent,
                    surface = OnboardingFocusSurface.PROFILE,
                    entryFocus = onboardingEntryFocus(keys, OnboardingContinueKey),
                    validKeys = keys,
                )
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        OnboardingScaffold(
            authorityFocusedKey = focus.focused.takeIf { focus.scope == OnboardingScope },
            focusReveal = focusReveal,
            header = { OnboardingHeaderStrip(palette, OnboardingStep.PROFILE) },
            content = {
            MimirChromeText(
                text = stringResource(R.string.app_onboarding_profile_title),
                style = MimirType.TitleCaps,
                color = palette.textPrimary,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(MimirSpacing.lg))

            OnboardingNameField(
                draft = profile.draft,
                authority = authority,
                focused = focus.scope == OnboardingScope && focus.focused == OnboardingNameFieldKey,
                onTap = { profile.keyboardOpen = true },
                focusReveal = focusReveal,
            )

            Spacer(Modifier.height(MimirSpacing.sm))

            Text(
                text = stringResource(R.string.app_onboarding_profile_support),
                style = MimirType.RowSupport,
                color = palette.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 420.dp),
            )

            Spacer(Modifier.height(MimirSpacing.lg))

            if (onboardingProfileHintVisible(profile)) {
                // Updated 2026-08-20, immediately after the D-pad fix below made this line stale. It was
                // written when the field was touch-ONLY, so it named the only way in. Now that A reaches the
                // field, a controller player who reads "touch the field" reasonably concludes there is no
                // other way -- on a controller-first handheld, the line naming the controller is the one
                // that must not be missing. Both paths named rather than either assumed.
                OnboardingHintBar(
                    palette,
                    "A",
                    stringResource(OnboardingProfileHintAction),
                    stringResource(R.string.app_onboarding_profile_touch_hint),
                )
            }
        },
        controls = {
            Spacer(Modifier.height(MimirSpacing.xl))
            OnboardingActionGroup {
                OnboardingPill(
                    label = stringResource(R.string.app_common_continue),
                    focusKey = OnboardingContinueKey,
                    gold = true,
                    focused = focus.scope == OnboardingScope && focus.focused == OnboardingContinueKey,
                    authority = authority,
                    onActivate = { profileAttemptContinue(profile, focusIntent, onCommitName, onAdvance) },
                )
                OnboardingPill(
                    label = stringResource(R.string.app_common_not_now),
                    focusKey = OnboardingNotNowKey,
                    gold = false,
                    focused = focus.scope == OnboardingScope && focus.focused == OnboardingNotNowKey,
                    authority = authority,
                    onActivate = { profile.reset(); onAdvance() },
                )
            }
        },
        )

        // ── Touch sub-modals (each brings its own later BackHandler so OS Back closes IT, not the flow) ──
        OnboardingKeyboardLayer(
            open = profile.keyboardOpen,
            authority = authority,
            palette = palette,
            draft = profile.draft,
            onValueChange = { profile.draft = it },
            onClose = { profile.keyboardOpen = false },
            onKeyboardHostChanged = { profile.keyboardHost = it },
        )
        // RC-33 (audit 2026-10-14, O-13): the confirm layer is a sheet now, like the keyboard layer beside it: it fades in and out on the
        // sheet clock and stays composed through its exit, inert while it leaves. It used to mount at full alpha and cut out.
        OnboardingConfirmLayer(
            open = profile.confirmOpen,
            authority = authority,
            focusIntent = focusIntent,
            confirmEpoch = profile.confirmEpoch,
            palette = palette,
            onKeepEditing = {
                focusIntent.clear(OnboardingFocusSurface.PROFILE_CONFIRM)
                profile.confirmOpen = false
            },
            onContinue = {
                focusIntent.clear(OnboardingFocusSurface.PROFILE_CONFIRM)
                profile.reset()
                onAdvance()
            },
        )
    }
}

/**
 * The name field — preserves the authored draft or shows the `Add a name` placeholder; CONFIRM (gamepad) or a
 * tap (touch) opens the keyboard. Registers on the single [FocusAuthority] like every other PROFILE control
 * ([OnboardingNameFieldKey]) — it used to be reachable by touch only, which stranded controller-only players
 * (device report 2026-08-20, Odin, D-pad). The resting hairline outline used to be a static `.border()`; now
 * [mimirFocusRing] owns it — same 1.dp/`palette.outline` border at rest, but [mimirFocusRing] ALSO applies its
 * default `restingAlpha` (0.88) to the whole box when unfocused, same as the Continue/Not now pills on this
 * screen. The field (and its draft/placeholder text) now dims very slightly at rest and brightens on focus,
 * where it used to sit at full alpha always — a real, if subtle, resting-state delta, not purely additive.
 * See `dispatchOnboarding`'s PROFILE branch for how nav reaches this key.
 */
@Composable
private fun OnboardingNameField(
    draft: String,
    authority: FocusAuthority,
    focused: Boolean,
    onTap: () -> Unit,
    focusReveal: OnboardingFocusRevealState? = null,
) {
    val palette = LocalMimirPalette.current
    val shown = draft.trim()
    val shape = RoundedCornerShape(MimirSpacing.radiusCard)
    Box(
        modifier = Modifier
            .widthIn(min = 300.dp, max = 460.dp)
            .mimirFocusable(
                authority = authority,
                key = OnboardingNameFieldKey,
                scope = OnboardingScope,
                autoBringIntoView = focusReveal == null,
            )
            .onboardingFocusRevealTarget(focusReveal, OnboardingNameFieldKey)
            // The one ring (ring migration, 2026-09-28), BEFORE the clip so it can reach past the field -- the old
            // ring sat inside it. The ring paints the field's glass so the wash lands over it, and keeps the 0.88
            // rest dim the KDoc above describes.
            .mimirSelectionRing(
                focused = focused,
                identity = OnboardingNameFieldKey,
                shape = MimirRingShape.Row(MimirSpacing.radiusCard),
                restingOutline = true,
                restingAlpha = MimirFocusRestingAlpha,
                fill = SolidColor(palette.surfaceRaised.copy(alpha = if (palette.usesBrightField) 0.9f else 0.55f)),
            )
            .clip(shape)
            .mimirActivatable(label = stringResource(R.string.app_onboarding_profile_placeholder), onActivate = onTap)
            .heightIn(min = 54.dp)
            .padding(horizontal = MimirSpacing.lg, vertical = MimirSpacing.md),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = shown.ifBlank { stringResource(R.string.app_onboarding_profile_placeholder) },
            style = MimirType.RailItem,
            color = if (shown.isBlank()) palette.textMuted else palette.textPrimary,
        )
    }
}

/**
 * The keyboard layer: a scrim + a live draft preview + the shared [MimirKeyboard]. Close keeps the draft.
 *
 * Driven by touch AND by the controller -- see [dispatchOnboarding]'s keyboard gate, which hands routed
 * input to `dispatchMimirKeyboardInput` while this is up.
 *
 * The four copy parameters default to the PROFILE step's wording, which is the only caller they had until
 * the FAVORITES search field arrived. Parameterised rather than copied: a second keyboard layer is a
 * second place for the scrim, the back handling and the host lifecycle to drift apart.
 *
 * Nova device report, 2026-09-30: the layer came and went in one
 * frame. It is a sheet now, like the app's other keyboards: the scrim and the preview fade, and the keyboard slides
 * up by its own height, every channel on the one [mimirSheetSpec] clock. From the frame [open] goes false it is
 * inert while it leaves: no host (the dispatch gate reads the open flag and hands the pad back to the page at once),
 * no Back, no touch.
 */
@Composable
private fun OnboardingKeyboardLayer(
    open: Boolean,
    authority: FocusAuthority,
    palette: MimirThemePalette,
    draft: String,
    onValueChange: (String) -> Unit,
    onClose: () -> Unit,
    onKeyboardHostChanged: (MimirKeyboardHost?) -> Unit,
    eyebrow: String = stringResource(R.string.app_onboarding_profile_title),
    placeholder: String = stringResource(R.string.app_onboarding_profile_placeholder),
    support: String = stringResource(R.string.app_onboarding_profile_support),
    targetLabel: String = stringResource(R.string.app_onboarding_profile_keyboard_target),
) {
    // Not `reducedMotion`: OnboardingAccessMotionTest counts the AnimatedContent hosts by that name.
    val sheetReducedMotion = mimirReducedMotion()
    AnimatedVisibility(
        visible = open,
        enter = fadeIn(mimirSheetSpec(sheetReducedMotion)),
        exit = fadeOut(mimirSheetSpec(sheetReducedMotion)),
        modifier = Modifier.fillMaxSize(),
        label = "onboardingKeyboardSheet",
    ) {
        // The keyboard's own travel, on the layer's clock: up by its height as the layer fades in, back down as it
        // fades out. A draw-time offset, so the preview above it keeps its place.
        val keyboardDrop by transition.animateFloat(
            transitionSpec = { mimirSheetSpec(sheetReducedMotion) },
            label = "onboardingKeyboardSlide",
        ) { state -> if (state == EnterExitState.Visible) 0f else 1f }
        val display = draft.trim()
        val host = rememberMimirKeyboardHost(authority, active = open)
        SideEffect { onKeyboardHostChanged(host) }
        DisposableEffect(Unit) {
            onDispose { onKeyboardHostChanged(null) }
        }
        BackHandler(enabled = open, onBack = onClose)
        Box(
            Modifier
                .fillMaxSize()
                .background(palette.surfaceBase.copy(alpha = 0.95f))
                .then(
                    if (open) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onClose,
                        )
                    } else {
                        Modifier.onboardingHandoverTouchShield()
                    },
                ),
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val compactPreview = onboardingKeyboardPreviewCompact(maxHeight.value)
                Column(Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(
                                horizontal = MimirSpacing.xxl,
                                vertical = if (compactPreview) MimirSpacing.xs else MimirSpacing.md,
                            ),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        MimirChromeText(eyebrow, style = MimirType.RunicEyebrow, color = palette.accentBright)
                        if (!compactPreview) {
                            MimirIntentText(
                                intent = if (display.isBlank()) {
                                    MimirTextIntent.StaticChrome(placeholder)
                                } else {
                                    MimirTextIntent.Authored(display)
                                },
                                style = if (display.isBlank()) MimirType.TitleCaps else MimirType.Title,
                                color = if (display.isBlank()) palette.textMuted else palette.textPrimary,
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                text = support,
                                style = MimirType.RowSupport,
                                color = palette.textSecondary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = MimirSpacing.sm),
                            )
                        }
                    }
                    // Done finishes typing (keeps the draft) — the profile commit happens on Continue, not here.
                    MimirKeyboard(
                        value = draft,
                        onValueChange = onValueChange,
                        onDone = onClose,
                        modifier = Modifier.graphicsLayer { translationY = keyboardDrop * size.height },
                        controller = host?.controller,
                        keyDecorator = host?.keyDecorator,
                        targetLabel = targetLabel,
                    )
                }
            }
        }
    }
}

/**
 * The purpose-built inline empty-name confirm (shipping `Create your profile?`). Renders INLINE (never a
 * Compose `Dialog`, which would miss the app's routed controller input); `Keep editing` is the focused default.
 */
@Composable
internal fun OnboardingConfirmLayer(
    /** False while the layer fades out: it holds no focus scope, no Back and no touch, and its pills register nothing. */
    open: Boolean,
    authority: FocusAuthority,
    focusIntent: OnboardingFocusIntentState,
    confirmEpoch: Int,
    palette: MimirThemePalette,
    onKeepEditing: () -> Unit,
    onContinue: () -> Unit,
) {
    val focus by authority.state.collectAsState()
    // Own focus scope: its nav resolves only among the two confirm pills, never the profile buttons behind. Pushed when the layer
    // opens and popped in the frame it closes, not 180 ms later when its fade ends.
    DisposableEffect(open) {
        if (open) authority.pushScope(OnboardingConfirmScope)
        onDispose { if (open && authority.state.value.scope == OnboardingConfirmScope) authority.popScope() }
    }
    BackHandler(enabled = open, onBack = onKeepEditing)
    LaunchedEffect(confirmEpoch, focusIntent.revision, open) {
        if (!open) return@LaunchedEffect
        authority.landFocus(
            focusIntent = focusIntent,
            surface = OnboardingFocusSurface.PROFILE_CONFIRM,
            entryFocus = OnboardingConfirmKeepKey,
            validKeys = listOf(OnboardingConfirmKeepKey, OnboardingConfirmContinueKey),
        )
    }
    // Not `reducedMotion`: OnboardingAccessMotionTest counts the AnimatedContent hosts by that name.
    val sheetReducedMotion = mimirReducedMotion()
    AnimatedVisibility(
        visible = open,
        enter = fadeIn(mimirSheetSpec(sheetReducedMotion)),
        exit = fadeOut(mimirSheetSpec(sheetReducedMotion)),
        modifier = Modifier.fillMaxSize(),
        label = "onboardingConfirmSheet",
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(palette.surfaceBase.copy(alpha = 0.86f))
                .then(
                    if (open) {
                        Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onKeepEditing)
                    } else {
                        Modifier.onboardingHandoverTouchShield()
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .widthIn(max = 420.dp)
                    .padding(MimirSpacing.xl)
                    .clip(RoundedCornerShape(MimirSpacing.radiusCard))
                    .background(palette.surfaceRaised)
                    .border(1.dp, palette.outline, RoundedCornerShape(MimirSpacing.radiusCard))
                    // Consume taps on the card so a body tap doesn’t fall through to the scrim's dismiss.
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .padding(MimirSpacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MimirSpacing.lg),
            ) {
                // Policy R7 (2026-07-09): the title asks what the dialog actually asks (approved deviation
                // from shipping parity).
                MimirChromeText(
                    stringResource(R.string.app_onboarding_profile_confirm_title),
                    style = MimirType.TitleCaps,
                    color = palette.textPrimary,
                    textAlign = TextAlign.Center,
                )
                // Content review 2026-08-21 (A11): the body restated the title's question with one extra word;
                // only the second sentence carried information.
                Text(
                    text = stringResource(R.string.app_onboarding_profile_confirm_body),
                    style = MimirType.RowSupport,
                    color = palette.textSecondary,
                    textAlign = TextAlign.Center,
                )
                // A two-choice confirm has no fixed primary — the gold follows FOCUS so the emphasized pill is
                // always the one A will activate (avoids the "gold ≠ focused" trap). Keep editing is the default.
                val keepFocused = focus.scope == OnboardingConfirmScope && focus.focused == OnboardingConfirmKeepKey
                val continueFocused = focus.scope == OnboardingConfirmScope && focus.focused == OnboardingConfirmContinueKey
                OnboardingActionGroup {
                    OnboardingPill(
                        label = stringResource(R.string.app_onboarding_profile_confirm_keep),
                        focusKey = OnboardingConfirmKeepKey,
                        gold = onboardingConfirmPillGold(keepFocused, continueFocused) && keepFocused,
                        focused = keepFocused,
                        authority = authority,
                        onActivate = onKeepEditing,
                        scope = OnboardingConfirmScope,
                        interactive = open,
                    )
                    OnboardingPill(
                        label = stringResource(R.string.app_common_continue),
                        focusKey = OnboardingConfirmContinueKey,
                        gold = onboardingConfirmPillGold(keepFocused, continueFocused) && continueFocused,
                        focused = continueFocused,
                        authority = authority,
                        onActivate = onContinue,
                        scope = OnboardingConfirmScope,
                        interactive = open,
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// Screen 3 — Optional Android access

/**
 * One permission screen: its title, one line on what the permission does for the player, one line of small print,
 * and the picture that shows it.
 *
 * Each screen shows its [scene] and says one thing. [refusal] is the one
 * line of small print: what Not now costs, or the one instruction a player cannot do without (OVERLAY's).
 * The sentences on how to find Mimir in Android's lists went. ORIENTATION's cards teach that round trip once, for
 * all of them ("Android may ask you to turn it on", "Change them later in Android's settings").
 *
 * Where there is nothing to grant on this device, [why] says so, [refusal] is blank and [scene] is null: the screen
 * shows the title, that line and Not now.
 */
internal data class OnboardingAccessScreenCopy(
    val plainName: String,
    val why: String,
    val refusal: String,
    val scene: OnboardingHintScene?,
)

/**
 * This grant selects Mimir's own file manager rather than Android's folder picker. The next step's button
 * is also "Choose folder", so the title names folders. The line explains that file access supports viewing
 * and downloading artwork. Box art is stored beside games in a `media_art/` folder per platform
 * (measured on Thor, 2026-09-16). Whether folders picked in Android's picker can hold `media_art/`
 * is unconfirmed; the small print does not claim that loss.
 */
internal fun onboardingAccessFilesCopy(res: Resources): OnboardingAccessScreenCopy = OnboardingAccessScreenCopy(
    plainName = res.getString(R.string.app_onboarding_access_files_title),
    why = res.getString(R.string.app_onboarding_access_files_why),
    refusal = res.getString(R.string.app_onboarding_access_files_refusal),
    scene = OnboardingHintScene.ACCESS_FILES,
)

/**
 * Wording depends on screen count: selectMButtonOverlayTarget (MButtonRuntime.kt) returns the
 * second-screen companion before consulting usage access, so only a one-screen handheld needs
 * this grant for the Mimir button.
 * - One screen: the line and small print describe one cause and effect. "Then" refers to OVERLAY,
 *   the previous screen, which names the button.
 * - Two screens: no button claim or small print. What is lost without usage access is unconfirmed;
 *   the button still needs OVERLAY, so saying it "still works" here would overstate availability.
 * The title says "running", as TWEAKER's does: the same Android permission applies to both apps.
 */
internal fun onboardingAccessUsageCopy(res: Resources, access: OnboardingAccessUi): OnboardingAccessScreenCopy =
    if (access.usageAvailable && access.hasSecondaryDisplay) {
        OnboardingAccessScreenCopy(
            plainName = res.getString(R.string.app_onboarding_access_usage_title),
            why = res.getString(R.string.app_onboarding_access_usage_why_two_screens),
            refusal = "",
            scene = OnboardingHintScene.ACCESS_USAGE,
        )
    } else if (access.usageAvailable) {
        OnboardingAccessScreenCopy(
            plainName = res.getString(R.string.app_onboarding_access_usage_title),
            // OR-16 : "So" makes the line the purpose of the title.
            why = res.getString(R.string.app_onboarding_access_usage_why_one_screen),
            refusal = res.getString(R.string.app_onboarding_access_usage_refusal_one_screen),
            scene = OnboardingHintScene.ACCESS_USAGE,
        )
    } else {
        OnboardingAccessScreenCopy(
            plainName = res.getString(R.string.app_onboarding_access_usage_title),
            why = res.getString(R.string.app_onboarding_access_usage_unavailable),
            refusal = "",
            scene = null,
        )
    }

/**
 * The title uses Android's role name, "Home app", and FINISH's row matches it.
 * The "Allow" button is set at the call site.
 */
internal fun onboardingAccessHomeRoleCopy(res: Resources, access: OnboardingAccessUi): OnboardingAccessScreenCopy =
    if (access.homeRoleAvailable) {
        OnboardingAccessScreenCopy(
            plainName = res.getString(R.string.app_onboarding_access_home_title),
            why = res.getString(R.string.app_onboarding_access_home_why),
            refusal = res.getString(R.string.app_onboarding_access_home_refusal),
            scene = OnboardingHintScene.ACCESS_HOME,
        )
    } else {
        OnboardingAccessScreenCopy(
            plainName = res.getString(R.string.app_onboarding_access_home_title),
            why = res.getString(R.string.app_onboarding_access_home_unavailable),
            refusal = "",
            scene = null,
        )
    }

/**
 * QA-16. Vocabulary note, for QA-25 to settle rather than for this screen to decide: the tester's report says
 * "favourites", the product's own shipped strings say "Favorite"/"Favorites" throughout
 * (`CompanionPayloadAdapter.kt:116`, `:411`, `:476`, and the Library's own Favorites segment). This screen
 * follows the PRODUCT, not the report -- introducing a second spelling of one noun is precisely the
 * two-dialects defect the 2026-08-21 content review exists to catch. If QA-25 rules for the British spelling,
 * it moves everywhere at once, not here first.
 *
 * The step teaches A, the confirm button. It used to teach SELECT, because SELECT is the Home shelf's toggle
 * everywhere else (the `addToHomeShelf` tail row still teaches that, and should). SELECT still toggles here
 * too, so a player who already has the Home habit is not refused; it is just no longer the one advertised.
 */
/**
 * What the FAVORITES grid is showing, and how wide it laid itself out.
 *
 * Held here rather than in the screen because BOTH ends of the step need it and only one of them is a
 * composable: the rail chip is activated in [dispatchOnboarding], which runs outside composition, and the
 * grid geometry is measured in the screen but needed by [onboardingFavoritesFocusDestination], which the
 * dispatch calls. Same shape and same reason as [OnboardingAccessReviewState] -- and, like that one,
 * deliberately NOT persisted. A filter is a way of looking at this screen, not a decision the player made
 * about their library.
 */
internal enum class OnboardingFavoritesPage {
    /** What the shelf IS and what the button does. No games here — that is the whole point. */
    INTRO,

    /** The Tome: boxes, a search field and a platform rail, and nothing else. */
    TOME,
}

internal class OnboardingFavoritesGridState {
    /**
     * The Tome page's derived lists, shared by the screen and the dispatch. Not snapshot state: a cache of
     * values derived from the games, [platform] and [query], read and written on the main thread only.
     */
    private var tomeListsCache: OnboardingFavoritesTomeLists? = null

    /** [games] narrowed by this page's filters, with its focus keys; see [onboardingFavoritesTomeLists]. */
    fun tomeLists(games: List<GameRow>): OnboardingFavoritesTomeLists =
        onboardingFavoritesTomeLists(tomeListsCache, games, platform, query).also { tomeListsCache = it }

    /**
     * Which of the step’s two pages is showing.
     *
     * Not persisted, for the same reason the filter is not: which page you were looking at is a position,
     * not a decision. A player who leaves and comes back arrives at the instructions, which is where the
     * step explains itself.
     */
    var page by mutableStateOf(OnboardingFavoritesPage.INTRO)
        private set

    /**
     * The search text, matched against titles on the Tome page.
     *
     * Cleared with the page, not kept beside it: coming back to a Tome still filtered by a word typed
     * minutes ago is how a library looks empty for no visible reason.
     */
    var query by mutableStateOf("")
        private set

    /** The shared [MimirKeyboard] layer is up. While it is, it owns all routed input. */
    var keyboardOpen by mutableStateOf(false)
        private set

    /**
     * The platform dropdown is open. While it is, it owns the page's input the same way the keyboard does.
     *
     * Not persisted and dropped on close: an open dropdown is a gesture in progress, not a preference.
     */
    var filterOpen by mutableStateOf(false)
        private set

    /** Active controller keyboard binding while that layer is open. Same shape as [OnboardingProfileState]. */
    var keyboardHost: MimirKeyboardHost? = null

    /** Null is "All", which is also where every visit starts. */
    var platform by mutableStateOf<PlatformId?>(null)
        private set

    /**
     * How many boxes the grid ACTUALLY laid out across, written by the screen once it has measured its
     * width and read by the dispatch to move focus by the same stride.
     *
     * This is the one-source-of-truth the column count needs. The alternative -- a constant in the mover
     * and a `GridCells.Fixed` in the composable -- is how a grid ends up moving focus a different distance
     * than the eye travels, on exactly the devices whose width nobody checked. The default is
     * [OnboardingFavoritesReferenceColumns] — what the Thor lays out — so a dispatch arriving before the
     * first measure still moves sanely rather than dividing by zero.
     */
    var columns by mutableStateOf(OnboardingFavoritesReferenceColumns)
        private set

    fun show(platformId: PlatformId?) {
        platform = platformId
    }

    fun measured(columns: Int) {
        this.columns = columns.coerceAtLeast(1)
    }

    fun openTome() {
        page = OnboardingFavoritesPage.TOME
    }

    /** Leaving the Tome drops every way of looking at it, so the next visit opens on the whole library. */
    fun closeTome() {
        page = OnboardingFavoritesPage.INTRO
        keyboardOpen = false
        filterOpen = false
        query = ""
        platform = null
        returnFocus = null
    }

    /**
     * Where focus was standing when the filter or the keyboard took the page over, so closing either one
     * can put it back.
     *
     * Every close path used to land on the filter chip
     * unconditionally, so a player eight rows down who opened the list — or merely tapped it — came back
     * to the top of the page and had to walk down again. A tap is the worse half: touching a control
     * moves focus to it, which is correct, but it also silently threw away the only record of where the
     * player had been.
     *
     * Deliberately a KEY and not an index: the grid's contents change under a filter, and an index into a
     * list that just got shorter is a different game than the one the player left.
     */
    var returnFocus by mutableStateOf<FocusKey?>(null)
        private set

    /** Remember [from] only when there is something to remember; a second open must not overwrite it. */
    private fun rememberReturn(from: FocusKey?) {
        if (from != null && returnFocus == null) returnFocus = from
    }

    /** Consume the remembered key, so the next open starts a fresh record rather than a stale one. */
    fun takeReturnFocus(): FocusKey? = returnFocus.also { returnFocus = null }

    fun openFilter(from: FocusKey? = null) {
        rememberReturn(from)
        filterOpen = true
    }

    fun closeFilter() {
        filterOpen = false
    }

    /** Picking an option both applies it and closes the list — a dropdown that stays open has no answer. */
    fun pick(platformId: PlatformId?) {
        platform = platformId
        filterOpen = false
    }

    fun openKeyboard(from: FocusKey? = null) {
        rememberReturn(from)
        keyboardOpen = true
    }

    fun closeKeyboard() {
        keyboardOpen = false
    }

    fun search(text: String) {
        query = text
    }
}

internal data class OnboardingFavoritesCopy(
    val eyebrowVisual: String,
    val title: String,
    val body: String,
    val openLabel: String,
    val chosenLine: String,
    val continueLabel: String,
)

/**
 * The step's words live on INTRO. [body] explains the shelf here so the Tome page can devote its
 * height to covers. The MARK demonstration teaches marking without a separate how-line.
 */
internal fun onboardingFavoritesCopy(res: Resources, chosen: Int): OnboardingFavoritesCopy = OnboardingFavoritesCopy(
    eyebrowVisual = res.getString(R.string.app_onboarding_favorites_eyebrow),
    title = res.getString(R.string.app_onboarding_favorites_title),
    body = res.getString(R.string.app_onboarding_favorites_body),
    openLabel = res.getString(R.string.app_onboarding_favorites_open_tome),
    chosenLine = when (chosen) {
        0 -> res.getString(R.string.app_onboarding_favorites_chosen_none)
        else -> res.getQuantityString(R.plurals.app_onboarding_favorites_chosen, chosen, chosen)
    },
    // Never a dead end: zero picks is a real answer, and the label says so rather than daring the player to
    // press a Continue that looks unearned.
    continueLabel = res.getString(
        if (chosen == 0) R.string.app_onboarding_favorites_skip else R.string.app_common_continue,
    ),
)

/**
 * The step's focus order: the platform rail (when there is one), every visible game in order, then the
 * pill that leaves.
 *
 * turned this screen from a list into a grid of scraped boxes with filters. The
 * rail is part of the key list rather than a touch-only chip row on purpose: this is a controller product,
 * and a filter the D-pad cannot reach is not a filter on this device.
 */
internal fun onboardingFavoritesFocusKeys(
    games: List<GameRow>,
    platforms: List<PlatformId> = emptyList(),
): List<FocusKey> =
    onboardingFavoritesHeadKeys(platforms) +
        games.map { onboardingFavoriteGameKey(it.gameId.value) }

/**
 * The one control row above the grid: the search field, and the platform filter when there is one.
 *
 * Always at least the search field, so the page always has somewhere for UP to go and a player whose
 * library is still arriving is never on a screen with no keys at all.
 */
internal fun onboardingFavoritesHeadKeys(platforms: List<PlatformId>): List<FocusKey> =
    if (platforms.size < 2) {
        listOf(OnboardingFavoritesSearchKey)
    } else {
        listOf(OnboardingFavoritesSearchKey, OnboardingFavoritesPlatformFilterKey)
    }

/**
 * How the control row splits between the search field and the platform filter: Customize Media's own
 * top-band proportions, read from [homeCustomEnrichTopBandWeights] rather than restated, so the two bands
 * cannot drift apart.
 *
 * What matters is that BOTH halves are weighted, more than the numbers. Run 9 (2026-09-18) shipped this row
 * with only the search field weighted. The filter was measured first, its label filled the whole row (see
 * the call site), and the field was left zero wide: never drawn, never registered, and still the target of
 * every UP from the top row. A weighted field's share is fixed before the filter is measured, so no label
 * can take it away.
 *
 * Always the wide split. This row holds one chip, which the wide split already fits, and the narrow split
 * would take width from the field to give chips room they do not need here.
 */
internal val OnboardingFavoritesHeadBandWeights: HomeCustomEnrichTopBandWeights =
    homeCustomEnrichTopBandWeights(narrowWidth = false)

/**
 * The control row's width: the boxes' own width ([onboardingFavoritesGridWidthDp]), so the search field
 * starts where the first box starts.
 *
 * Floored at [OnboardingFavoritesHeadBandMinWidthDp], because a narrow window's two-box grid is too thin a
 * row to give the field a readable share. Above the floor the row can never be wider than its grid, and a
 * window narrower than the floor clamps it through the ordinary width constraint.
 */
internal fun onboardingFavoritesHeadBandWidthDp(columns: Int): Float =
    onboardingFavoritesGridWidthDp(columns).coerceAtLeast(OnboardingFavoritesHeadBandMinWidthDp)

internal const val OnboardingFavoritesHeadBandMinWidthDp = 320f

/**
 * The dropdown's options, in display order: "All" first, then every platform the library yielded.
 *
 * Null is "All". Returned as nullable ids rather than a parallel label list so the picker, the mover and
 * the chip's own caption all read one ordering.
 */
internal fun onboardingFavoritesPlatformOptions(platforms: List<PlatformId>): List<PlatformId?> =
    listOf(null) + platforms

/**
 * What the filter calls a platform, closed and open: the name the library gives it, the one the Tome
 * collection's rows carry ([PlatformLabels]), and "All" for no platform.
 * Both the closed and open filter use display labels rather than scanner IDs such as "snes" or "ps1".
 */
internal fun onboardingFavoritesPlatformLabel(res: Resources, platform: PlatformId?): String =
    platform?.let(PlatformLabels::labelFor) ?: res.getString(R.string.app_onboarding_favorites_filter_all)

/** What the closed filter chip reads: the product's own word, then the current value. */
internal fun onboardingFavoritesPlatformFilterIntent(res: Resources, platform: PlatformId?): MimirTextIntent =
    MimirTextIntent.Mixed(
        listOf(
            MimirTextFragment.Chrome(res.getString(R.string.app_onboarding_favorites_filter_chip)),
            MimirTextFragment.Chrome(" · "),
            // AUTHORED, not Chrome. Chrome fragments are the product talking and get uppercased and
            // animated; a platform's name is the player's own library talking. Getting this role wrong is
            // invisible in a screenshot and wrong in every other way -- it is why this is a typed intent
            // and not the plain "Platform · Super Nintendo" string it would otherwise be.
            MimirTextFragment.Authored(onboardingFavoritesPlatformLabel(res, platform)),
        ),
    )

/**
 * The INTRO page’s keys: open the Tome, or leave the step.
 *
 * Two keys, in the order a first-time player should read them. The Continue pill stays on THIS page and
 * not on the Tome page on purpose — the Tome is for marking games, and a "done" control sitting inside a
 * grid of boxes is a control the D-pad keeps falling into on the way to the bottom row.
 */
internal fun onboardingFavoritesIntroKeys(): List<FocusKey> =
    listOf(OnboardingFavoritesOpenTomeKey, OnboardingFavoritesContinueKey)

/**
 * The Tome page's footer hint: what the two buttons do RIGHT NOW, for whatever is focused.
 *
 * [filterKeyPresent] rather than a key list, because a single-platform library has no filter at all and a
 * hint naming a control that is not on the screen is worse than no hint.
 *
 * The Tome library's own footer grammar, which changes its verb with the focused thing rather than listing
 * every binding at once ([HomeTomeLibraryFooter] writes "A OPEN · X PIN · B BACK" the same way).
 *
 * **It says A over a game, the same button as everywhere else on this page.** Both A and SELECT toggle --
 * see the dispatch -- but only one gets advertised. From QA-16 that was SELECT, on the grounds that A on a
 * Home tile launches rather than shelves; the advertised button changed to A on 2026-09-18. The instructions page teaches A on the hint carousel's MARK
 * card (it replaced the how-line on 2026-09-28), and a footer naming a different button would have the step
 * contradicting itself across its own two pages.
 *
 * Pure, and not inlined in the composable, so [OnboardingFavoritesStepTest] can hold the two pages to one
 * button. An inline hint once said a different button from the instructions page, and no test could see it.
 *
 * Over a game that is already marked, A takes it off again, so the verb is "unmark" there
 * ([focusedMarked]; 2026-09-28). The button stays A either way.
 */
internal fun onboardingFavoritesTomeHint(
    res: Resources,
    focused: FocusKey?,
    filterKeyPresent: Boolean,
    hasGames: Boolean,
    focusedMarked: Boolean = false,
): String = res.getString(
    when {
        focused == OnboardingFavoritesSearchKey -> R.string.app_onboarding_favorites_hint_search
        filterKeyPresent && focused == OnboardingFavoritesPlatformFilterKey -> R.string.app_onboarding_favorites_hint_platform
        // Nothing to mark: do not offer a verb for it.
        !hasGames -> R.string.app_onboarding_favorites_hint_back
        focusedMarked -> R.string.app_onboarding_favorites_hint_unmark
        else -> R.string.app_onboarding_favorites_hint_mark
    },
)

/**
 * Whether the platform filter exists at all — only when there are at least two platforms to choose
 * between. A filter offering a single option teaches nothing and costs a D-pad press to get past, so a
 * single-platform library gets the grid and the search field, and nothing else.
 */
internal fun onboardingFavoritesHasPlatformFilter(platforms: List<PlatformId>): Boolean =
    platforms.size >= 2

/**
 * Every platform present in [games], in the order the library itself yielded them.
 *
 * Deliberately derived from the games rather than from `observePlatforms`: this screen shows the games it
 * was handed, and a rail chip for a platform with nothing behind it filters to an empty grid.
 */
internal fun onboardingFavoritesPlatforms(games: List<GameRow>): List<PlatformId> =
    games.map(GameRow::platformId).distinct()

/**
 * The favourites Tome's lists for one games list and filter: its platforms, its control row, the visible games,
 * their focus keys, and where each key's game sits.
 *
 * A 5,529-game measurement on RG Rotate: the screen rebuilt the platform list, the visible
 * list and every focus key on each recomposition, and the dispatch rebuilt them again on each press; finding the
 * focused game built a key per game to compare. About 3.5 MB per press, on the UI thread.
 */
internal class OnboardingFavoritesTomeLists(
    val games: List<GameRow>,
    val platform: PlatformId?,
    val query: String,
    val platforms: List<PlatformId>,
    val headKeys: List<FocusKey>,
    val visible: List<GameRow>,
    /** [headKeys], then one key per [visible] game. The SAME instance for as long as the visible ids are. */
    val keys: List<FocusKey>,
    internal val visibleIndexByKey: Map<FocusKey, Int>,
) {
    /** Where [key]'s game sits in [visible], or -1. A lookup: no key is rebuilt to find it. */
    fun visibleIndexOf(key: FocusKey?): Int = key?.let(visibleIndexByKey::get) ?: -1

    /** The visible game [key] names, or null. */
    fun visibleGame(key: FocusKey?): GameRow? = visible.getOrNull(visibleIndexOf(key))
}

/**
 * [previous] when nothing it was built from changed. Otherwise new lists, but [previous]'s keys and index when the
 * visible games are the same games in the same order -- a star or a retitle changes a row, not its identity, and
 * must not re-register every focus slot.
 */
internal fun onboardingFavoritesTomeLists(
    previous: OnboardingFavoritesTomeLists?,
    games: List<GameRow>,
    platform: PlatformId?,
    query: String,
): OnboardingFavoritesTomeLists {
    if (previous != null && previous.games === games && previous.platform == platform && previous.query == query) {
        return previous
    }
    val platforms = onboardingFavoritesPlatforms(games)
    val visible = onboardingFavoritesVisibleGames(games, platform, query)
    if (previous != null && previous.platforms == platforms && onboardingFavoritesSameGameIds(previous.visible, visible)) {
        return OnboardingFavoritesTomeLists(
            games = games,
            platform = platform,
            query = query,
            platforms = previous.platforms,
            headKeys = previous.headKeys,
            visible = visible,
            keys = previous.keys,
            visibleIndexByKey = previous.visibleIndexByKey,
        )
    }
    val headKeys = onboardingFavoritesHeadKeys(platforms)
    val keys = ArrayList<FocusKey>(headKeys.size + visible.size)
    keys.addAll(headKeys)
    val index = HashMap<FocusKey, Int>(visible.size * 2)
    visible.forEachIndexed { i, game ->
        val key = onboardingFavoriteGameKey(game.gameId.value)
        keys.add(key)
        index[key] = i
    }
    return OnboardingFavoritesTomeLists(
        games = games,
        platform = platform,
        query = query,
        platforms = platforms,
        headKeys = headKeys,
        visible = visible,
        keys = keys,
        visibleIndexByKey = index,
    )
}

private fun onboardingFavoritesSameGameIds(a: List<GameRow>, b: List<GameRow>): Boolean {
    if (a === b) return true
    if (a.size != b.size) return false
    for (i in a.indices) if (a[i].gameId != b[i].gameId) return false
    return true
}

/**
 * [games] narrowed to [platform] (null is "All") and then to [query].
 *
 * The two narrow together rather than replacing each other: a player who picked SNES and then typed
 * "mario" means both, and a search that silently widened back to every platform would be a filter
 * undoing the filter beside it.
 *
 * Matching is a case-insensitive substring of the title, trimmed. Deliberately not fuzzy and not a
 * token scorer — this is a filter over at most a few thousand rows the player can already see, and a
 * ranked match would reorder the grid under them while they are looking at it.
 */
internal fun onboardingFavoritesVisibleGames(
    games: List<GameRow>,
    platform: PlatformId?,
    query: String = "",
): List<GameRow> {
    val byPlatform = if (platform == null) games else games.filter { it.platformId == platform }
    val needle = query.trim()
    return if (needle.isEmpty()) {
        byPlatform
    } else {
        byPlatform.filter { it.title.contains(needle, ignoreCase = true) }
    }
}

/**
 * A cover in this grid: smaller than the Tome collection's compact face so three full rows fit.
 *
 * The initial target was a smaller 5x3 grid. It started at the Tome collection's own
 * compact face (`HomeTomeLibraryCompactMaxFaceWidthDp`, 112.5dp), and at that size the Thor showed two rows
 * and part of a third. 75dp is what fits THREE full rows into the grid's viewport there (374dp, measured in
 * [OnboardingFavoritesViewportFitTest] and on the emulator at the Thor's geometry) with ring clearance above
 * and below. The gap stays the collection's 15dp (`HomeTomeLibraryGridColumnGapDp`), and the face keeps the
 * collection's cover shape.
 *
 * Run 11 kept this size and changed the count instead: the row now holds as many of these as the screen is
 * wide ([onboardingFavoritesColumns]), eight on the Thor.
 */
internal const val OnboardingFavoritesFaceWidthDp = 75f

/**
 * Every seat in this grid is this shape, whatever shape the art in it happens to be.
 *
 *  The first
 * build seated each cover at its OWN aspect — [mimirLedgeCoverAspect] admits anything from 0.62 to 1.45 —
 * so a row of four boxes was four different rectangles floating at four different sizes. [MimirBoxArt]'s
 * own KDoc has the measurement: two box renders on the reference device filled 31% and ~100% of identical seats,
 * "because the seats were shaped by the artboard".
 *
 * 0.72 is [homeCustomEnrichPreviewStageAspect]'s COVER aspect, also used by the gamelist for GameCube/Wii.
 *
 * **The gallery fits its cover tiles rather than cropping them.** `EnrichAssetTile` seats COVER at 0.72 and paints
 * `ContentScale.Fit`; so does the Tome Folio, for every source that is not exactly square. There is no
 * surface in this tree that crops box art, and this one does not either — see [OnboardingFavoritesCell].
 */
internal const val OnboardingFavoritesFaceAspect = 0.72f
internal const val OnboardingFavoritesFaceHeightDp =
    OnboardingFavoritesFaceWidthDp / OnboardingFavoritesFaceAspect
internal const val OnboardingFavoritesGridGapDp = 15f

/**
 * What the reference handheld lays out across: eight. Read only as [OnboardingFavoritesGridState.columns]'
 * value before the page has measured itself; the page's own count always comes from
 * [onboardingFavoritesColumns].
 *
 * The earlier 4x4 and 5x3 layouts imposed four-column and then five-column caps.
 * The cap was removed on 2026-09-18: keep the box size while the grid spans the screen,
 * about eight across on the Thor, still three rows.
 * [OnboardingFavoritesViewportFitTest] pins eight at the Thor's width.
 */
internal const val OnboardingFavoritesReferenceColumns = 8

/**
 * Room for the focus ring, which is drawn OUTSIDE the cell it belongs to.
 *
 * [mimirFocusTileRing] strokes three rounded rects at outsets up to 10dp. A lazy grid clips its viewport,
 * so a cell flush against the edge loses that ring on the clipped side. 14dp is what the Tome Folio
 * reserves for the same component, measured there rather than guessed here.
 */
internal const val OnboardingFavoritesRingClearanceDp = 14f

/**
 * How many boxes fit across. ONE source of truth: the composable lays out `GridCells.Fixed` with this, and
 * [onboardingFavoritesFocusDestination] moves UP/DOWN by this. A second constant in either place is how a
 * grid ends up with focus that jumps a different distance than the eye does.
 *
 * Every box that fits, and no cap, at the same size ([OnboardingFavoritesReferenceColumns]). A wider screen
 * therefore gets more boxes, never bigger ones, so a tablet shows more of the library rather than fewer,
 * larger covers.
 *
 * Derived from the face size rather than a bare divisor so the two cannot drift: a phone too narrow for
 * four gets three or two at the SAME box size, rather than four boxes squeezed until the art is unreadable.
 * The floor of one is real — a 200dp-wide window is not a grid, and pretending otherwise is what makes a
 * mover divide by a column count the layout never used.
 *
 * The ring clearance comes off the width first, because the grid's viewport has to hold it as well as the
 * boxes ([onboardingFavoritesGridViewportWidthDp]).
 */
internal fun onboardingFavoritesColumns(availableWidthDp: Float): Int =
    ((availableWidthDp - 2 * OnboardingFavoritesRingClearanceDp + OnboardingFavoritesGridGapDp) /
        (OnboardingFavoritesFaceWidthDp + OnboardingFavoritesGridGapDp))
        .toInt()
        .coerceAtLeast(1)

/**
 * The exact width [columns] boxes occupy, so the grid can be CENTRED instead of stretched.
 *
 * The Tome collection does the same thing with `HomeTomeLibraryGridContentWidthDp = 398f`: it holds its
 * three wells in a centred column and leaves the rest of the screen alone. Here the column count already
 * fills the width ([onboardingFavoritesColumns]), so what is left to centre is less than one box and its
 * gap -- on the Thor, eight boxes and their clearance are 733dp of an 800dp row. Stretching the boxes to
 * take that remainder is the reading run 11 turned down: bigger covers, which is two rows on that screen,
 * not three.
 */
internal fun onboardingFavoritesGridWidthDp(columns: Int): Float {
    val safe = columns.coerceAtLeast(1)
    return safe * OnboardingFavoritesFaceWidthDp + (safe - 1) * OnboardingFavoritesGridGapDp
}

/**
 * The grid's VIEWPORT width: the boxes ([onboardingFavoritesGridWidthDp]) plus the ring clearance that its
 * content padding reserves on each side.
 *
 * The padding sits INSIDE the viewport, so a viewport only as wide as the boxes squeezes every box by
 * 2 x clearance / columns. That is what this grid did until run 9. It declared 112.5dp faces and drew
 * 105.5dp ones, and every height sum built from the declared face was quietly wrong.
 */
internal fun onboardingFavoritesGridViewportWidthDp(columns: Int): Float =
    onboardingFavoritesGridWidthDp(columns) + 2 * OnboardingFavoritesRingClearanceDp

/**
 * How many WHOLE rows of boxes fit down, with ring clearance above the first and below the last. Never
 * below one.
 *
 * Previously the grid filled whatever
 * height the page left it -- on the Thor, three rows and a few dp more -- and the scroll only moved once
 * focus reached a row that was already cut off, so part of another row showed at one edge or the other
 * most of the time. The fix is geometric, not a scroll tweak: the viewport is sized to a whole number of
 * rows ([onboardingFavoritesGridViewportHeightDp]), and the scroll only ever seats a row at the top edge
 * ([onboardingFavoritesTopRow]), so what shows is always whole rows.
 */
internal fun onboardingFavoritesRows(availableHeightDp: Float): Int =
    ((availableHeightDp - 2 * OnboardingFavoritesRingClearanceDp + OnboardingFavoritesGridGapDp) /
        (OnboardingFavoritesFaceHeightDp + OnboardingFavoritesGridGapDp) +
        // The face height is 75/0.72, which no Float holds exactly, so room for exactly N rows can divide
        // out a hair under N. An exact fit is a fit.
        RowFitEpsilon)
        .toInt()
        .coerceAtLeast(1)

private const val RowFitEpsilon = 0.001f

/**
 * The grid's VIEWPORT height for [rows] whole rows: the boxes, the gaps between them, and one ring
 * clearance at the top and one at the bottom, which is the grid's content padding.
 *
 * The bottom clearance is 1dp short of a gap, and that is what hides the next row. With a row seated at the
 * top edge, the row after the last whole one begins one gap below it -- 1dp past the bottom of this
 * viewport, so nothing of it is drawn. A taller viewport shows the top of that row.
 */
internal fun onboardingFavoritesGridViewportHeightDp(rows: Int): Float {
    val safe = rows.coerceAtLeast(1)
    return safe * OnboardingFavoritesFaceHeightDp + (safe - 1) * OnboardingFavoritesGridGapDp +
        2 * OnboardingFavoritesRingClearanceDp
}

/**
 * How far below the top of its box the grid sits: nothing on standard screens, where the grid keeps its top
 * edge under the search band, and half the leftover on compact.
 *
 * Compact review 2026-09-27 (ONB-2): on the RG's 564.7 dp square the box holds three whole rows (370.5 dp)
 * with about 96 dp to spare, and top alignment put all of it below the last row, a dead band over the hint.
 * Centring splits it. The face size and the whole-rows rule are the existing grid constraints and do not
 * change; the scroll authority seats rows inside the viewport, so it is unaffected by where the viewport sits.
 */
internal fun onboardingFavoritesGridTopOffsetDp(boxHeightDp: Float, rows: Int, compact: Boolean): Float =
    if (compact) {
        ((boxHeightDp - onboardingFavoritesGridViewportHeightDp(rows)) / 2f).coerceAtLeast(0f)
    } else {
        0f
    }

/**
 * Which row the grid shows at its top edge when [focusedIndex] has focus: the one above the focused row,
 * so the row before it and the row after it are both on screen, and the view stops at the end, where the
 * last [rows] rows fill the grid.
 *
 * Previously the
 * focused row was seated at the top edge -- the Tome collection's rule
 * (`homeTomeLibraryFocusSettlementDelta`: *"vertical focus movement must carry the Tome sheet, not merely
 * change a logical key inside a static viewport"*). It does carry the sheet, but the ring then never leaves
 * the grid's first row and the boxes slide underneath it, which reads as a grid that does not move at all.
 * This surface departs from the collection there and keeps the focused row in the middle band. Only the
 * first and the last rows put the ring at an edge, because past them there is nothing left to reveal.
 *
 * A viewport of one or two rows has no middle to sit in, so it seats the focused row at the top as before.
 * The end is clamped here rather than by the grid's scroll state, so the page never asks the grid for a
 * position it cannot reach and every position the grid reaches starts on a row.
 */
internal fun onboardingFavoritesTopRow(focusedIndex: Int, itemCount: Int, columns: Int, rows: Int): Int {
    if (itemCount <= 0 || focusedIndex !in 0 until itemCount) return 0
    val safeColumns = columns.coerceAtLeast(1)
    val safeRows = rows.coerceAtLeast(1)
    val lastRow = (itemCount - 1) / safeColumns
    val lastTopRow = (lastRow - safeRows + 1).coerceAtLeast(0)
    val lead = (safeRows - 1) / 2
    return (focusedIndex / safeColumns - lead).coerceIn(0, lastTopRow)
}

/**
 * Grid-aware focus movement on the TOME page.
 *
 * The geometry, stated once so the composable and this agree: ONE control row ([onboardingFavoritesHeadKeys]
 * — the search field, and the platform filter when there is one), then the grid, [columns] wide. [keys] is
 * always `head + games` in that order, which is what lets this work in indices rather than in guesses.
 *
 * **UP out of the grid always lands on the search field.**  The previous version had a horizontally-scrolling rail
 * of platform chips above the grid and moved UP into the chip at the focused column — which, on a rail
 * wider than the screen, is regularly a chip that is not visible. Focus was not lost, it was parked
 * off-screen, which reads as a screen that has stopped responding. One row of at most two controls, and one
 * unconditional destination, is what removes that class of answer rather than tuning it.
 *
 * LEFT/RIGHT move by one and CLAMP at a row's edges — a cursor that wraps from the end of one row to the
 * start of the next reads as a jump on a surface made of boxes.
 *
 * There is no pill below the grid: DOWN off the last row STAYS, and the way out of this page is B. The
 * ragged last row is still handled for DOWN from a full row into a short one, which must land on that
 * row's last cell rather than falling through.
 */
internal fun onboardingFavoritesFocusDestination(
    keys: List<FocusKey>,
    current: FocusKey?,
    action: ControllerAction,
    columns: Int,
    headSize: Int,
): FocusKey? {
    if (keys.isEmpty()) return null
    val safeColumns = columns.coerceAtLeast(1)
    val index = keys.indexOf(current).takeIf { it >= 0 } ?: return keys.firstOrNull()
    // At least one: the search field always exists, so the head is never empty and `head` never indexes
    // a game as though it were a control.
    val head = headSize.coerceAtLeast(1)
    val gameCount = (keys.size - head).coerceAtLeast(0)
    val inHead = index < head
    val gridIndex = if (inHead) 0 else index - head
    val column = if (inHead) 0 else gridIndex % safeColumns
    val lastRowStart = if (gameCount <= 0) 0 else ((gameCount - 1) / safeColumns) * safeColumns

    // Guards the empty grid: `coerceIn(0, -1)` THROWS, and this page is reached with no games in two
    // ordinary ways -- a library still arriving, and a search that matches nothing. A player must never be
    // able to crash setup by pressing a direction while either is true.
    fun grid(at: Int): FocusKey? =
        if (gameCount <= 0) null else keys.getOrNull(head + at.coerceIn(0, gameCount - 1))

    return when (action) {
        ControllerAction.NAV_LEFT -> when {
            inHead -> keys.getOrNull(index - 1).takeIf { index > 0 }
            column > 0 -> keys.getOrNull(index - 1)
            else -> null
        }
        ControllerAction.NAV_RIGHT -> when {
            inHead -> keys.getOrNull(index + 1).takeIf { index + 1 < head }
            column < safeColumns - 1 && gridIndex + 1 < gameCount -> keys.getOrNull(index + 1)
            else -> null
        }
        ControllerAction.NAV_UP -> when {
            // The control row is the top of the page.
            inHead -> null
            // Unconditional, and the whole point: see this function's KDoc.
            gridIndex < safeColumns -> keys.firstOrNull()
            else -> grid(gridIndex - safeColumns)
        }
        ControllerAction.NAV_DOWN -> when {
            inHead -> grid(0)
            gridIndex + safeColumns < gameCount -> grid(gridIndex + safeColumns)
            // A ragged last row: DOWN from a full row lands on that row's last cell, not past it.
            gridIndex < lastRowStart -> grid(gameCount - 1)
            // The bottom of the grid is the bottom of the page. B is the way out, not DOWN.
            else -> null
        }
        else -> null
    }
}

/**
 * A focus slot for every game in the grid, composed or not.
 *
 * **Navigating up could leave input focused on an unregistered grid cell.** It was never the
 * filters. A [LazyVerticalGrid] registers a cell with the [FocusAuthority] only when that cell is
 * PLACED, and `requestFocus` on an unregistered key is a silent no-op. The screen scrolls the focused
 * row to the top of the viewport, so after a single DOWN the row above is outside the viewport,
 * disposed, and unregistered -- and the next UP asks for a key that is no longer in the graph. Nothing
 * moves, nothing clicks, and because the pending focus intent is never consumed the dispatch's idea of
 * where focus is walks away from the ring the player can see. Several presses later it reaches row 0,
 * resolves to the search field (always composed), and the ring finally jumps there, skipping every row
 * in between. That is the whole symptom, and it starts on the first DOWN/UP pair.
 *
 * `registerVirtual` exists for exactly this -- see [FocusAuthority.registerVirtual], "a persistent,
 * virtual focus slot for a lazy item that may not currently be composed". The Tome library grid already
 * solves it this way ([homeTomeLibraryFocusSlots] / [HomeTomeLibraryFocusRegistry]); this is the same
 * shape with this grid's arithmetic.
 *
 * Bounds are the honest grid geometry, so the authority's geometric fallback (which picks the nearest
 * surviving sibling when a focused key is removed) lands somewhere a player would call nearby.
 */
internal fun onboardingFavoritesFocusSlots(
    games: List<GameRow>,
    columns: Int,
): List<Pair<FocusKey, FocusBounds>> {
    val safeColumns = columns.coerceAtLeast(1)
    val colPitch = OnboardingFavoritesFaceWidthDp + OnboardingFavoritesGridGapDp
    val rowPitch = OnboardingFavoritesFaceHeightDp + OnboardingFavoritesGridGapDp
    return games.mapIndexed { index, game ->
        val left = (index % safeColumns) * colPitch
        val top = (index / safeColumns) * rowPitch
        onboardingFavoriteGameKey(game.gameId.value) to FocusBounds(
            left = left,
            top = top,
            right = left + OnboardingFavoritesFaceWidthDp,
            bottom = top + OnboardingFavoritesFaceHeightDp,
        )
    }
}

/** How many frames the Tome's entry-focus bootstrap retries after registering the slots itself and asking once (it used to spin eight). */
internal const val OnboardingFavoritesEntryRetryFrames = 2

/**
 * Keeps the authority's virtual slots in step with what the filters are showing.
 *
 * Cloned from [HomeTomeLibraryFocusRegistry], including the ordering rule that matters: incoming
 * identities register BEFORE departing ones are removed, so narrowing the search never empties the focus
 * graph for a frame and drops focus to null.
 */
internal class OnboardingFavoritesFocusRegistry {
    private val registered = linkedSetOf<FocusKey>()

    fun update(authority: FocusAuthority, slots: List<Pair<FocusKey, FocusBounds>>) {
        val desired = slots.mapTo(linkedSetOf()) { it.first }
        slots.forEach { (key, bounds) -> authority.registerVirtual(key, OnboardingScope, bounds) }
        authority.unregisterVirtualAll(registered.filterNot(desired::contains))
        registered.clear()
        registered.addAll(desired)
    }

    fun clear(authority: FocusAuthority) {
        authority.unregisterVirtualAll(registered)
        registered.clear()
    }
}

/**
 * Movement inside the OPEN platform dropdown: a single vertical list, clamped at both ends.
 *
 * Clamped rather than wrapping for the same reason the grid clamps: a list this short reads as a jump
 * rather than a cycle, and a player holding DOWN to reach the last platform should arrive and stop.
 * Returns null when the press does nothing, so the dispatch can leave focus exactly where it is.
 */
internal fun onboardingFavoritesFilterOptionDestination(
    optionCount: Int,
    currentIndex: Int,
    action: ControllerAction,
): Int? {
    if (optionCount <= 0) return null
    val at = currentIndex.coerceIn(0, optionCount - 1)
    val next = when (action) {
        ControllerAction.NAV_UP -> at - 1
        ControllerAction.NAV_DOWN -> at + 1
        else -> return null
    }
    return next.takeIf { it in 0 until optionCount }
}


/**
 * The screen that names the Mimir button. The title matches the heading of the page Android opens, and it is
 * true on two screens too, where the button does not sit over the game. The line says what the button is with the
 * action page's verb, "hold".
 *
 * The small print is the one instruction every player needs, from Android 11 (API 30, Mimir's
 * minimum) this permission always opens Android's full list, never Mimir's own row, so the player must pick Mimir
 * there. Settings may cache the package name instead of the app label until restarted. What Not now costs is plain from the title, and USAGE's one-screen small print states it.
 * The grant is a hard gate: selectMButtonOverlayTarget (MButtonRuntime.kt) returns null without it as its FIRST
 * statement, on one screen and two.
 */
internal fun onboardingAccessOverlayCopy(res: Resources): OnboardingAccessScreenCopy = OnboardingAccessScreenCopy(
    plainName = res.getString(R.string.app_onboarding_access_overlay_title),
    why = res.getString(R.string.app_onboarding_access_overlay_why),
    refusal = res.getString(R.string.app_onboarding_access_overlay_refusal),
    scene = OnboardingHintScene.ACCESS_OVERLAY,
)



/**
 * The ACCESS sub-sequence's one house-clock motion spec, generic over the animated property (`Float` alpha,
 * `IntSize` for [SizeTransform]). Same idiom as [com.gruning.mimir.rewrite.core.ui.mimirHintToastRailFadeSpec]
 * / `StorageNoticeHost.kt`'s `storageNoticeFadeSpec`: [MimirMotion.OverlayMs] / [MimirMotion.Easing] active,
 * duration collapsed to 0 (an instant snap, not a skipped call) under [reducedMotion] — `mimirReducedMotion()`
 * — so "these transitions must not run" holds without a second code path per call site.
 *
 * Reports on 2026-08-20 identified missing permission-grant acknowledgement and screen-transition motion.
 * House grammar (already shipped at `ArtworkFetchOverlay.kt`,
 * `GetArtOverlay.kt`, `MigrationImport.kt`, `HomeTileMenuOverlay.kt`): fade-in, fade-out AND the resize run on
 * the SAME `tween` — never a bare `togetherWith`, which lets `ContentTransform`'s default-arg `SizeTransform`
 * through (`spring(dampingRatio = 1f, stiffness = 400f)`, a second unbounded clock — PRs the earlier update).
 * The duration is [OnboardingMotionMs], onboarding's own tempo, on [MimirMotion.Easing].
 */
internal fun <T> onboardingAccessMotionSpec(reducedMotion: Boolean): TweenSpec<T> =
    tween(durationMillis = if (reducedMotion) 0 else OnboardingMotionMs, easing = MimirMotion.Easing)

/**
 * Onboarding's transition clock: the house transient bound at 3x, 540 ms.
 * The step glide, in-step handovers, hints and carousel turns share [onboardingAccessMotionSpec]
 * so onboarding keeps one tempo. Home and the rest of the app keep [MimirMotion.OverlayMs].
 */
internal const val OnboardingMotionMs: Int = MimirMotion.OverlayMs * 3

/**
 * The in-step handovers (ACCESS's permission pages, LIBRARY's views and tour pages, the grant swap, the favourites INTRO
 * and Tome) and the step handover are one even cross-fade: ONE
 * [MimirCrossSwapMs] on [MimirMotion.Easing] for the page left, the page arriving and the host's resize, no lead, the same
 * each way, so the two are complementary at every frame and the pane is never emptier than the overlap itself. It
 * replaces the previous out-then-in (exit 360 ms, a 260 ms lead, enter 540 ms), which left the pane near-empty for most of
 * it. The pages still glide, on this clock ([onboardingGlide]). Reduced motion snaps.
 */
internal fun <T> onboardingHandoverExitSpec(reducedMotion: Boolean): FiniteAnimationSpec<T> =
    mimirCrossSwapSpec(reducedMotion)

/** The page an in-step handover brings: the same clock as [onboardingHandoverExitSpec], so the two stay complementary. */
internal fun <T> onboardingHandoverEnterSpec(reducedMotion: Boolean): FiniteAnimationSpec<T> =
    mimirCrossSwapSpec(reducedMotion)

/**
 * The intro's verse fade, subsequent page arrival and badge ignite use onboarding's tempo so
 * they match later steps. The verse holds alone for [OnboardingWelcomeVerseAloneMs] (about 2.5 s), including its fade.
 */
internal const val OnboardingIntroShineMs: Int = OnboardingMotionMs

/**
 * The onboarding focus rings' clock -- the favourites cell's ring: the product ring clock ([mimirFocusRingMs], 280 ms,
 * the focus response mid-traverse) on [MimirMotion.Easing], collapsed to 0 under [reducedMotion], the same idiom as
 * [onboardingAccessMotionSpec].
 * Reduced motion goes to 0 ms on every onboarding transition ; the rings were the ones
 * still easing on their own clock (2026-09-28). The hosts name their flag
 * `ringReducedMotion`, not `reducedMotion`: they are not AnimatedContent hosts, which is what
 * OnboardingAccessMotionTest counts by that name.
 */
internal fun onboardingFocusRingSpec(reducedMotion: Boolean): TweenSpec<Float> =
    tween(durationMillis = if (reducedMotion) 0 else mimirFocusRingMs(), easing = MimirMotion.Easing)

/*
 * The pills' own ring clocks, gleam keys, flare keys and wash law lived here until the ring migration (2026-09-28).
 * They were the product's clocks under other names ([MimirFocusRingSettleMs], [MimirFocusGlintMs]); `mimirSelectionRing`
 * owns them now, and the pills read nothing of their own.
 */

/** The favourites cell's Mannaz pin clock: 110 ms ([HomeTomeMutationAckMs]), or 0 under reduced motion. */
internal fun onboardingFavoritePinDurationMs(reducedMotion: Boolean): Int =
    if (reducedMotion) 0 else HomeTomeMutationAckMs

/**
 * Which way, and how far, one screen of a sequence travels as it hands over to another.
 *
 * Split out from [onboardingGlide] because it is the whole of the ruling that can be checked without a
 * composition: *"back and forth"* means going back must not look like going forward, and that is entirely
 * a statement about the sign of this number. [onboardingGlide] returns a `ContentTransform`, whose
 * enter/exit internals are not readable from a test -- so the part worth asserting lives here.
 *
 * Forward, the arriving screen comes in from the right and the departing one leaves to the left, matching
 * the direction the sequence itself runs. Back mirrors both. The two screens therefore always travel the
 * SAME way across the display, which is what reads as one surface moving rather than two crossfading.
 *
 * `reducedMotion` collapses the travel to zero. A zero-length tween already lands the offset on the same
 * frame, so this changes nothing a player can see -- it is here so the accessibility setting means "no
 * movement" in the code as plainly as it does on the screen, rather than "movement too fast to notice",
 * and so that meaning is something a test can hold.
 *
 * @param incoming true for the arriving screen's start offset, false for the departing screen's end offset.
 */
internal fun onboardingGlideOffsetPx(
    forward: Boolean,
    travelPx: Int,
    incoming: Boolean,
    reducedMotion: Boolean,
): Int {
    if (reducedMotion) return 0
    val towardTheRight = if (forward) travelPx else -travelPx
    return if (incoming) towardTheRight else -towardTheRight
}

/**
 * The house screen-handover transition: fade and travel, the page left on [onboardingHandoverExitSpec] and the page
 * arriving on [onboardingHandoverEnterSpec].
 *
 *  Every sequence in this file already faded; none of them moved, so none of them said which
 * direction the player had gone. This adds the missing half and is shared rather than copied, because three
 * boundaries need it and three hand-rolled copies are three chances to disagree about what "back" looks like.
 *
 * **The outer `OnboardingStep` boundary (WELCOME -> PROFILE -> ACCESS -> ...) deliberately does NOT use
 * this.** Four hazards apply to this boundary: each step
 * owns its own `BackHandler`, PROFILE hosts a live keyboard, LIBRARY holds a SAF launcher, and wrapping the
 * boundary would double-compose SETTLING for the whole window. It is also a forward-only gate -- the root
 * `BackHandler` swallows OS Back and there is no `previousOnboardingStep` -- so a glide's "back" half has
 * nothing to act on there. That boundary glides separately: [OnboardingFlow] holds the departing step alone while it
 * glides out, then shows the next one gliding in, so two steps are never composed at once and none of the four
 * hazards can arise. It travels the direction [onboardingGlideOffsetPx] gives a forward handover.
 *
 * The house grammar is preserved exactly : fade-in,
 * fade-out, the slides AND the resize all run on the SAME `tween` via an explicit `SizeTransform` -- never a
 * bare `togetherWith`, which lets `ContentTransform`'s default-arg `SizeTransform` through
 * (`spring(dampingRatio = 1f, stiffness = 400f)`, a second unbounded clock). The shared tween is [mimirCrossSwapSpec]: the page left, the page arriving and the host's resize ride the
 * one cross-fade clock together, so the two are complementary at every frame.
 *
 * @param travelPx [MimirMotion.GlideTravelDp] resolved at the call site -- `transitionSpec` is not
 *   `@Composable` and cannot read `LocalDensity` itself.
 * @param fade false for a host whose content fades itself through [onboardingRingSafeFadeAlpha], because it
 *   holds a gold pill whose ring this transition's own fade would cut. The travel and the resize are unchanged.
 */
internal fun onboardingGlide(
    forward: Boolean,
    travelPx: Int,
    reducedMotion: Boolean,
    fade: Boolean = true,
): ContentTransform {
    val slideIn = slideInHorizontally(onboardingHandoverEnterSpec(reducedMotion)) {
        onboardingGlideOffsetPx(forward, travelPx, incoming = true, reducedMotion = reducedMotion)
    }
    val slideOut = slideOutHorizontally(onboardingHandoverExitSpec(reducedMotion)) {
        onboardingGlideOffsetPx(forward, travelPx, incoming = false, reducedMotion = reducedMotion)
    }
    val enter = if (fade) fadeIn(onboardingHandoverEnterSpec(reducedMotion)) + slideIn else slideIn
    val exit = if (fade) fadeOut(onboardingHandoverExitSpec(reducedMotion)) + slideOut else slideOut
    // Built directly rather than via `togetherWith` + `using`: those are members of
    // AnimatedContentTransitionScope, so reaching them would mean making this a scope extension and giving
    // up the free function a test can call. Passing sizeTransform to the constructor is the same thing the
    // scope's `using` does -- and naming it here makes the rule above structural rather than remembered,
    // because there is no default-arg SizeTransform to leak through when the parameter must be supplied.
    return ContentTransform(
        targetContentEnter = enter,
        initialContentExit = exit,
        sizeTransform = SizeTransform(clip = false) { _, _ -> onboardingHandoverEnterSpec(reducedMotion) },
    )
}

/**
 * The fade for a handover whose pages hold gold pills: onboarding's clock, applied per draw call rather than
 * through an offscreen buffer.
 *
 * `fadeIn` and `fadeOut` fade through a layer with the default
 * compositing, and below alpha 1 that layer draws its content into a buffer the size of the content. The gold
 * pill's ring and flare are drawn outside the pill ([MimirFilledControlRingReach] + [MimirFocusFlareReach]),
 * so for the whole fade the buffer cut the ring's outer arcs, and they came back whole on the frame the
 * fade ended. [OnboardingScaffold]'s control slot met
 * the same hazard on 2026-08-15 and fixed it with [CompositingStrategy.ModulateAlpha]; this is that fix for
 * the `AnimatedContent` hosts inside the slot, which the slot's own layer cannot reach.
 *
 * The host passes `fade = false` to [onboardingGlide] (or no fade at all), and each page reads this in a
 * `graphicsLayer` with ModulateAlpha. It runs on the page's own enter/exit transition, so `AnimatedContent`
 * keeps a departing page until it has faded out. ModulateAlpha's one cost is that overlapping children
 * would show through each other, so it belongs only on pages of pills and text, as the slot's KDoc says.
 */
@Composable
internal fun AnimatedVisibilityScope.onboardingRingSafeFadeAlpha(reducedMotion: Boolean): State<Float> =
    mimirCrossSwapFadeAlpha(reducedMotion)

/**
 * Draws this page at [alpha] without an offscreen buffer, so a ring drawn outside it is never cut. Promoted to core-ui
 * as [mimirRingSafeFade] for the swap tier (motion audit batch A); onboarding's pages keep their own name for it.
 */
internal fun Modifier.onboardingRingSafeFade(alpha: State<Float>): Modifier = mimirRingSafeFade(alpha)

/**
 * Presses during an in-step handover wait for the page they would act on (alpha audit, 2026-09-30). The arriving page is composed, focused and live from the handover's first frame, but it stands at
 * alpha 0 on that frame: a quick second A on Continue would press Allow on a permission page nobody had seen yet. So, as the tile menu's pages do, the pad waits on [MimirTransitionInputGate] from this page's
 * first frame until it first paints, or until a reversal takes it off the target; Back and the shoulders pass as
 * ever. Touch reaches the pills without the router, so the page takes none until it paints. The page a host first
 * composes on is already showing and holds nothing.
 *
 * [pageAlpha] is the page's own fade where it draws one ([onboardingRingSafeFadeAlpha]). A page that fades through
 * the glide's `fadeIn` passes none and reads a mirror on the same clock, which paints nothing.
 */
@Composable
internal fun AnimatedVisibilityScope.onboardingHandoverArrivalHold(
    reducedMotion: Boolean,
    pageAlpha: State<Float>? = null,
): Modifier {
    val alpha = pageAlpha ?: onboardingRingSafeFadeAlpha(reducedMotion)
    val pageTransition = transition
    val gateOwner = remember { Any() }
    val shows by remember(alpha) { derivedStateOf { alpha.value > 0f } }
    LaunchedEffect(Unit) {
        if (pageTransition.currentState == EnterExitState.Visible) return@LaunchedEffect
        holdMimirInputUntilShown(gateOwner) {
            alpha.value > 0f || pageTransition.targetState != EnterExitState.Visible
        }
    }
    return if (shows) Modifier else Modifier.onboardingHandoverTouchShield()
}

/** Takes every pointer event in the Initial pass, before anything under it sees one. */
private fun Modifier.onboardingHandoverTouchShield(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
}

/**
 * How an onboarding `AnimatedContent` seats its two pages while its own height is animating between them.
 *
 * Permission-card handovers could briefly clip the footer ring. Measured before this changed
 * ([OnboardingAccessGlideOverflowTest], which prints the table): on EVERY one of those handovers the
 * arriving card was seated 11-34px LOWER than where it settles, and rose into place over the
 * [MimirMotion.OverlayMs] window. On the measured post-inset box
 * (833x444dp) that left the worst card's last line **7px** from the bottom edge mid-handover against 41px
 * at rest — and the ring is drawn OUTSIDE its pill ([mimirFocusTileRing] reaches 16.5dp past it), so the
 * ring meets the edge before the text does. A slight second, then gone.
 *
 * The cause is not in the transition; it is where the two pages sit inside it. [OnboardingScaffold]'s
 * column is `Arrangement.Center` and the `AnimatedContent` is its last child, so while that box's height is
 * animating from the departing page's `A_old` to the arriving page's `A_new`, the column centres the stack
 * on the SHORT animating height and `Alignment.TopCenter` then lays the arriving page out from the box's
 * top, at its own full height. Writing `y` for the arriving page's top, `H` for the viewport, `F` for
 * everything above the box and `A(t)` for the animating height:
 *
 * ```
 * TopCenter:  y = (H - F - A(t)) / 2 + F                     -- moves with A(t): (A_new - A_old) / 2
 * Center:     y = (H - F - A(t)) / 2 + F + (A(t) - A_new)/2  -- the A(t) terms cancel
 *               = (H + F - A_new) / 2
 * ```
 *
 * Centred, each page sits exactly where it rests for the whole handover: the column's lift and the box's
 * own centring are equal and opposite. Nothing is clipped that would not also be clipped at rest, on any
 * viewport — including the ones this tree has never measured — and a page that fits cannot be pushed off
 * the bottom by its own arrival.
 *
 * Deliberately NOT fixed by dropping the shared `SizeTransform` to `snap()`: the box would take its new
 * height instantly and the column would recentre instantly with it, which moves the same distance into the
 * header lockup instead of out of the footer. And deliberately not by shrinking the delta (making the
 * granted branch reserve the not-granted branch's height) — the grant-state swap must keep its animated resize.
 *
 * At rest the two alignments are identical (the box's height equals its content's, so there is nothing to
 * align differently), to within the one pixel a halved odd delta rounds by. This changes no settled layout
 * anywhere — only what happens in the 180ms between them.
 *
 * **Only for a handover inside [OnboardingScaffold].** The cancellation above is between the scaffold
 * column's `Arrangement.Center` and this alignment; under a top-anchored parent the same substitution would
 * seat the arriving page HIGH by the same distance instead. The favourites INTRO/TOME boundary is the one
 * that lives outside the scaffold and is deliberately left on `TopCenter` — see its own note.
 */
private val OnboardingHandoverAlignment = Alignment.Center

/**
 * The reserved control slot (D-14/D-15) is anchored to the BOTTOM of the scaffold, not centred in a column, so
 * [OnboardingHandoverAlignment]'s cancellation does not hold there. A centred arriving control group that is
 * taller than the departing one hangs half the difference below the slot until the resize settles (8dp on
 * ORIENTATION -> FILES, caught by OnboardingAccessGlideOverflowTest). Bottom-aligned, the group's bottom edge
 * never moves during a handover; only its top grows or shrinks into the body seat above it.
 */
private val OnboardingControlSlotHandoverAlignment = Alignment.BottomCenter

// ORIENTATION states the permission boundary before any grant request and is excluded from
// the "N of D" progress cue. Permission cards explain optional access and later settings changes;
// the promise stays visible even if the player immediately presses Continue.

@Composable
private fun OnboardingAccessScreen(
    authority: FocusAuthority,
    access: OnboardingAccessUi,
    accessReview: OnboardingAccessReviewState,
    // RC-13: see [dispatchOnboarding]'s matching parameter — the touch path needs the identical durable
    // signal the gamepad dispatch path uses.
    orientationAlreadySeen: Boolean = false,
    focusIntent: OnboardingFocusIntentState,
    onRequestFilesAccess: () -> Unit,
    onRequestUsageAccess: () -> Unit,
    onRequestOverlayAccess: () -> Unit = {},
    onMarkAccessOrientationSeen: () -> Unit = {},
    onRequestHomeRole: () -> Unit,
    onAdvance: () -> Unit,
    // ORIENTATION permission cards; the router passes the shared instance.
    hintCarousel: OnboardingHintCarouselState? = null,
) {
    val palette = LocalMimirPalette.current
    val focus by authority.state.collectAsState()
    val reducedMotion = mimirReducedMotion()
    val travelPx = with(LocalDensity.current) { MimirMotion.GlideTravelDp.dp.roundToPx() }
    val orientationCarousel = hintCarousel ?: remember { OnboardingHintCarouselState() }
    val res = onboardingResources()
    val screens = onboardingAccessScreens(access)
    val current = onboardingAccessCurrentScreen(
        screens, accessReview.currentScreen, access, accessReview, orientationAlreadySeen,
    )

    if (current == null) return
    // Keep the ephemeral anchor in sync BEFORE any resolution-state changes elsewhere in this composition can
    // recompute "first unresolved" out from under a screen the player is already looking at (e.g. Special
    // Device Access granting itself in the background while this screen is on stage must show "Allowed" +
    // Continue, never silently skip ahead — that would violate press-to-continue-on-grant).
    if (accessReview.currentScreen != current) accessReview.goToScreen(current)

    val surface = onboardingAccessFocusSurface(current)
    val focusKeys = onboardingAccessScreenFocusKeys(current, access)
    val entryFocus = focusKeys.firstOrNull()
    val focusReveal = remember(current) { OnboardingFocusRevealState() }
    val canGoBack = onboardingAccessPreviousScreen(screens, current) != null

    fun advanceScreen() {
        // RC-13: persist BEFORE navigating away, while `current` still names the screen being left.
        if (current == OnboardingAccessScreenId.ORIENTATION) onMarkAccessOrientationSeen()
        onboardingAccessAdvance(
            screens = screens,
            current = current,
            review = accessReview,
            focusIntent = focusIntent,
            access = access,
            onAdvance = onAdvance,
        )
    }

    // Touch/gesture Back: steps within the sub-sequence, swallowed on ORIENTATION (the first screen) exactly
    // like the flow's outer forward-only boundary. Gamepad CANCEL/SYSTEM_BACK takes the identical path
    // through dispatchOnboarding's ACCESS branch (onboardingAccessGoBack) — same function, both input paths.
    BackHandler(enabled = canGoBack) {
        onboardingAccessGoBack(screens, current, accessReview, focusIntent)
    }

    // Every screen is consciously granted or deferred before it lets the player past it. Land on its own
    // entry control whenever the screen (or its resolution state) changes — the screen-to-screen arrival now
    // plays the role the old stacked screen's focus-jump used to play (spec §6).
    LaunchedEffect(current, access, accessReview.isDeferred(OnboardingAccessCapability.FILES),
        accessReview.isDeferred(OnboardingAccessCapability.USAGE),
        accessReview.isDeferred(OnboardingAccessCapability.HOME_ROLE),
        accessReview.overlayDeferred,
        focusIntent.revision) {
        entryFocus?.let {
            authority.landFocus(
                focusIntent = focusIntent,
                surface = surface,
                entryFocus = it,
                validKeys = focusKeys,
            )
        }
    }

    val focusedKey = focus.focused.takeIf { focus.scope == OnboardingScope }

    OnboardingScaffold(
        authorityFocusedKey = focusedKey,
        focusReveal = focusReveal,
        header = { OnboardingHeaderStrip(palette, OnboardingStep.ACCESS) },
        content = {
        // Advancing WITHIN the ACCESS sub-sequence (ORIENTATION -> FILES -> OVERLAY -> USAGE -> HOME_ROLE). `current` is already a stable enum, so no separate
        // page-key function is needed (unlike ArtworkFetchUi's mutable-progress states).
        //
        //  This was the fade with no glide, and a symmetric one -- backing out of a capability
        // screen looked identical to advancing into it. The enum's own order is the sequence's order, so
        // comparing ordinals IS the direction the player travelled; nothing else needs to know about it.
        AnimatedContent(
            targetState = current,
            contentAlignment = OnboardingHandoverAlignment,
            transitionSpec = {
                onboardingGlide(
                    forward = targetState.ordinal > initialState.ordinal,
                    travelPx = travelPx,
                    reducedMotion = reducedMotion,
                )
            },
            label = "onboardingAccessScreenGlide",
        ) { presented ->
            // No liveness here: since the reserved slot (2026-09-24) this body holds no controls. The departing
            // page's controls are gated in OnboardingAccessControls.
            // The count names its own page (walk-32e). Above this host it changed on the press, over the page still
            // leaving, and shifted that page by its own height; it now arrives and leaves with the page it counts.
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                onboardingAccessProgressLabel(res, screens, presented)?.let { progressLabel ->
                    MimirChromeText(
                        text = progressLabel,
                        style = MimirType.MicroStatus,
                        color = palette.textMuted,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(MimirSpacing.sm))
                }
                when (presented) {
                    OnboardingAccessScreenId.ORIENTATION -> OnboardingAccessOrientationBody(
                        palette = palette,
                        hintCarousel = orientationCarousel,
                        steps = screens.count { it != OnboardingAccessScreenId.ORIENTATION },
                    )
                    OnboardingAccessScreenId.FILES -> OnboardingAccessCapabilityScreenBody(
                        palette = palette,
                        copy = onboardingAccessFilesCopy(res),
                        granted = access.filesGranted,
                        grantedStatusLabel = stringResource(R.string.app_onboarding_access_granted),
                        playing = presented == current,
                    )
                    OnboardingAccessScreenId.USAGE -> OnboardingAccessCapabilityScreenBody(
                        palette = palette,
                        copy = onboardingAccessUsageCopy(res, access),
                        granted = access.usageGranted,
                        grantedStatusLabel = stringResource(R.string.app_onboarding_access_granted),
                        playing = presented == current,
                    )
                    OnboardingAccessScreenId.HOME_ROLE -> OnboardingAccessCapabilityScreenBody(
                        palette = palette,
                        copy = onboardingAccessHomeRoleCopy(res, access),
                        granted = access.homeRoleHeld,
                        grantedStatusLabel = stringResource(R.string.app_onboarding_access_active),
                        playing = presented == current,
                    )
                    // QA-10: a single fixed-label grant, no per-state action text, because Android offers exactly
                    // one thing here.
                    OnboardingAccessScreenId.OVERLAY -> OnboardingAccessCapabilityScreenBody(
                        palette = palette,
                        copy = onboardingAccessOverlayCopy(res),
                        granted = access.overlayGranted,
                        grantedStatusLabel = stringResource(R.string.app_onboarding_access_granted),
                        playing = presented == current,
                    )
                }
            }
        }
        },
        controls = {
            OnboardingAccessControls(
                current = current,
                access = access,
                accessReview = accessReview,
                authority = authority,
                focusedKey = focusedKey,
                onRequestFilesAccess = onRequestFilesAccess,
                onRequestUsageAccess = onRequestUsageAccess,
                onRequestOverlayAccess = onRequestOverlayAccess,
                onRequestHomeRole = onRequestHomeRole,
                advanceScreen = ::advanceScreen,
            )
        },
    )
}

@Composable
private fun OnboardingAccessControls(
    current: OnboardingAccessScreenId,
    access: OnboardingAccessUi,
    accessReview: OnboardingAccessReviewState,
    authority: FocusAuthority,
    focusedKey: FocusKey?,
    onRequestFilesAccess: () -> Unit,
    onRequestUsageAccess: () -> Unit,
    onRequestOverlayAccess: () -> Unit,
    onRequestHomeRole: () -> Unit,
    advanceScreen: () -> Unit,
) {
    val reducedMotion = mimirReducedMotion()
    val travelPx = with(LocalDensity.current) { MimirMotion.GlideTravelDp.dp.roundToPx() }
    AnimatedContent(
        targetState = current,
        contentAlignment = OnboardingControlSlotHandoverAlignment,
        transitionSpec = {
            onboardingGlide(
                forward = targetState.ordinal > initialState.ordinal,
                travelPx = travelPx,
                reducedMotion = reducedMotion,
                fade = false,
            )
        },
        label = "onboardingAccessControlsGlide",
    ) { presented ->
        val live = presented == current
        val fadeAlpha = onboardingRingSafeFadeAlpha(reducedMotion)
        Box(Modifier.onboardingRingSafeFade(fadeAlpha).then(onboardingHandoverArrivalHold(reducedMotion, fadeAlpha))) {
            when (presented) {
                OnboardingAccessScreenId.ORIENTATION -> OnboardingAccessOrientationControls(
                    authority = authority,
                    focusedKey = focusedKey,
                    onContinue = advanceScreen,
                    interactive = live,
                )
                OnboardingAccessScreenId.FILES -> OnboardingAccessCapabilityControls(
                    authority = authority,
                    focusedKey = focusedKey,
                    granted = access.filesGranted,
                    grantedStatusLabel = stringResource(R.string.app_onboarding_access_granted),
                    actionLabel = stringResource(R.string.app_common_allow),
                    actionKey = OnboardingFilesAccessKey,
                    onAction = onRequestFilesAccess,
                    deferKey = OnboardingFilesDeferKey,
                    onDefer = { accessReview.defer(OnboardingAccessCapability.FILES); advanceScreen() },
                    onContinue = advanceScreen,
                    interactive = live,
                )
                OnboardingAccessScreenId.USAGE -> OnboardingAccessCapabilityControls(
                    authority = authority,
                    focusedKey = focusedKey,
                    granted = access.usageGranted,
                    grantedStatusLabel = stringResource(R.string.app_onboarding_access_granted),
                    actionLabel = stringResource(R.string.app_common_allow),
                    actionKey = OnboardingUsageAccessKey.takeIf { access.usageAvailable },
                    onAction = onRequestUsageAccess,
                    deferKey = OnboardingUsageDeferKey,
                    onDefer = { accessReview.defer(OnboardingAccessCapability.USAGE); advanceScreen() },
                    onContinue = advanceScreen,
                    interactive = live,
                )
                OnboardingAccessScreenId.HOME_ROLE -> OnboardingAccessCapabilityControls(
                    authority = authority,
                    focusedKey = focusedKey,
                    granted = access.homeRoleHeld,
                    grantedStatusLabel = stringResource(R.string.app_onboarding_access_active),
                    actionLabel = stringResource(R.string.app_common_allow),
                    actionKey = OnboardingHomeRoleKey.takeIf { access.homeRoleAvailable },
                    onAction = onRequestHomeRole,
                    deferKey = OnboardingHomeRoleDeferKey,
                    onDefer = { accessReview.defer(OnboardingAccessCapability.HOME_ROLE); advanceScreen() },
                    onContinue = advanceScreen,
                    interactive = live,
                )
                OnboardingAccessScreenId.OVERLAY -> OnboardingAccessCapabilityControls(
                    authority = authority,
                    focusedKey = focusedKey,
                    granted = access.overlayGranted,
                    grantedStatusLabel = stringResource(R.string.app_onboarding_access_granted),
                    actionLabel = stringResource(R.string.app_common_allow),
                    actionKey = OnboardingOverlayAccessKey,
                    onAction = onRequestOverlayAccess,
                    deferKey = OnboardingOverlayDeferKey,
                    onDefer = { accessReview.deferOverlay(); advanceScreen() },
                    onContinue = advanceScreen,
                    interactive = live,
                )
            }
        }
    }
}

@Composable
private fun OnboardingAccessBody(text: String, palette: MimirThemePalette) {
    Text(
        text = text,
        style = MimirType.RowSupport,
        color = palette.textMuted,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(max = 560.dp),
    )
}

@Composable
private fun OnboardingAccessOrientationBody(
    palette: MimirThemePalette,
    hintCarousel: OnboardingHintCarouselState,
    steps: Int,
) {
    val res = onboardingResources()
    // A wrapping Column, not loose children: this composable is invoked from inside an AnimatedContent
    // content lambda (item 3, screen-to-screen fade), which lays out multiple direct children like a Box
    // (overlaid, not stacked) — same reason ArtworkFetchOverlay/GetArtOverlay/MigrationImport wrap their own
    // AnimatedContent page bodies in one Column. horizontalAlignment reproduces the centering the OUTER
    // OnboardingScaffold Column used to provide directly; nesting one CenterHorizontally Column inside
    // another preserves the same shared center line (unaffected by the inner Column's own auto width).
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        MimirChromeText(
            text = stringResource(R.string.app_onboarding_access_orientation_eyebrow),
            style = MimirType.MicroStatus,
            color = palette.textMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(MimirSpacing.sm))
        MimirChromeText(
            text = stringResource(R.string.app_onboarding_access_orientation_title),
            style = MimirType.TitleCaps,
            color = palette.textPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(MimirSpacing.md))
        // How the screens after this one work, in four cards; LEFT/RIGHT or a finger turn them, and they turn
        // themselves (the carousel's own clock). Continue has focus from the start.
        OnboardingHintCarousel(
            state = hintCarousel,
            deck = remember(steps, res) { onboardingPermissionDeck(res, steps) },
            sceneHeight = onboardingPermissionSceneHeight(onboardingHintViewportHeight(), LocalDensity.current.fontScale),
        )
        Spacer(Modifier.height(MimirSpacing.md))
        OnboardingAccessBody(stringResource(R.string.app_onboarding_access_orientation_promise), palette)
    }
}

@Composable
private fun OnboardingAccessOrientationControls(
    authority: FocusAuthority,
    focusedKey: FocusKey?,
    onContinue: () -> Unit,
    interactive: Boolean,
) {
    Spacer(Modifier.height(MimirSpacing.lg))
    OnboardingPill(
        label = stringResource(R.string.app_common_continue),
        focusKey = OnboardingAccessContinueKey,
        gold = true,
        focused = focusedKey == OnboardingAccessContinueKey,
        authority = authority,
        onActivate = onContinue,
        compact = true,
        interactive = interactive,
    )
}

/**
 * The body every permission screen shares: title, the picture, what the permission does, then the small print or,
 * once granted, the status. The picture plays once as the screen
 * arrives ([playing]), as its card arrives, and holds its end state; under reduced motion it shows the end state.
 * The picture sizes to the viewport ([onboardingAccessSceneHeight]) and drops out where there is no room, so the page
 * never scrolls. Revisiting a deferred screen via Back renders it as a first visit, because revisiting a decision is
 * what Back is for.
 */
@Composable
internal fun OnboardingAccessCapabilityScreenBody(
    palette: MimirThemePalette,
    copy: OnboardingAccessScreenCopy,
    granted: Boolean,
    grantedStatusLabel: String,
    playing: Boolean,
) {
    val reducedMotion = mimirReducedMotion()
    val demo = remember(copy.scene) { Animatable(if (reducedMotion) 1f else 0f) }
    LaunchedEffect(copy.scene, playing, reducedMotion) {
        when {
            reducedMotion -> demo.snapTo(1f)
            playing -> demo.animateTo(
                1f,
                tween(OnboardingHintDemoMs, easing = MimirMotion.Easing),
            )
        }
    }
    val sceneHeight = copy.scene?.let {
        onboardingAccessSceneHeight(onboardingHintViewportHeight(), LocalDensity.current.fontScale)
    }
    // A wrapping Column, not loose children — same reason as OnboardingAccessOrientationBody (this
    // composable is invoked from inside item 3's outer AnimatedContent content lambda).
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        MimirChromeText(
            text = copy.plainName,
            style = MimirType.TitleCaps,
            color = palette.textPrimary,
            textAlign = TextAlign.Center,
        )
        if (copy.scene != null && sceneHeight != null) {
            Spacer(Modifier.height(MimirSpacing.sm))
            OnboardingHintSceneFrame(
                sceneHeight = sceneHeight,
                palette = palette,
                modifier = Modifier.width(sceneHeight * 16f / 9f),
            ) {
                OnboardingHintSceneView(
                    card = OnboardingHintCard(copy.scene, key = null, action = copy.plainName),
                    palette = palette,
                    sceneHeight = sceneHeight,
                    demo = { demo.value },
                )
            }
        }
        Spacer(Modifier.height(MimirSpacing.sm))
        OnboardingAccessBody(copy.why, palette)

        // Fade and resize the not-granted <-> granted swap on the shared clock, matching the
        // screen-to-screen crossfade in OnboardingAccessScreen and the overlay transition pattern.
        AnimatedContent(
            targetState = granted,
            contentAlignment = OnboardingHandoverAlignment,
            transitionSpec = {
                (fadeIn(onboardingHandoverEnterSpec(reducedMotion)) togetherWith
                    fadeOut(onboardingHandoverExitSpec(reducedMotion)))
                    .using(
                        SizeTransform(clip = false) { _, _ -> onboardingHandoverEnterSpec(reducedMotion) },
                    )
            },
            label = "onboardingAccessGrantFade",
        ) { presentedGranted ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (presentedGranted) {
                    Spacer(Modifier.height(MimirSpacing.sm))
                    MimirChromeText(
                        text = grantedStatusLabel,
                        style = MimirType.MicroStatus,
                        color = palette.success,
                        textAlign = TextAlign.Center,
                    )
                } else if (copy.refusal.isNotBlank()) {
                    Spacer(Modifier.height(MimirSpacing.sm))
                    OnboardingAccessSmallPrint(copy.refusal, palette)
                }
            }
        }
    }
}

/** A permission screen's one line of small print: what Not now costs, or the one instruction it cannot do without. */
@Composable
private fun OnboardingAccessSmallPrint(text: String, palette: MimirThemePalette) {
    Text(
        text = text,
        style = MimirType.MicroStatus,
        color = palette.textMuted,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(max = 560.dp),
    )
}

@Composable
private fun OnboardingAccessCapabilityControls(
    authority: FocusAuthority,
    focusedKey: FocusKey?,
    granted: Boolean,
    grantedStatusLabel: String,
    actionLabel: String?,
    actionKey: FocusKey?,
    onAction: () -> Unit,
    deferKey: FocusKey,
    onDefer: () -> Unit,
    onContinue: () -> Unit,
    interactive: Boolean,
) {
    val reducedMotion = mimirReducedMotion()
    AnimatedContent(
        targetState = granted,
        contentAlignment = OnboardingControlSlotHandoverAlignment,
        transitionSpec = {
            // The pages fade themselves ([onboardingRingSafeFadeAlpha]); KeepUntilTransitionsFinished holds the
            // departing one until that fade is done, where a plain None would drop it on the first frame.
            (EnterTransition.None togetherWith ExitTransition.KeepUntilTransitionsFinished)
                .using(SizeTransform(clip = false) { _, _ -> onboardingHandoverEnterSpec(reducedMotion) })
        },
        label = "onboardingAccessGrantControlsFade",
    ) { presentedGranted ->
        val live = interactive && presentedGranted == granted
        val fadeAlpha = onboardingRingSafeFadeAlpha(reducedMotion)
        Column(
            modifier = Modifier.onboardingRingSafeFade(fadeAlpha)
                .then(onboardingHandoverArrivalHold(reducedMotion, fadeAlpha)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (presentedGranted) {
                Spacer(Modifier.height(MimirSpacing.lg))
                OnboardingPill(
                    label = stringResource(R.string.app_common_continue),
                    focusKey = OnboardingAccessContinueKey,
                    gold = true,
                    focused = focusedKey == OnboardingAccessContinueKey,
                    authority = authority,
                    onActivate = onContinue,
                    compact = true,
                    interactive = live,
                )
            } else {
                Spacer(Modifier.height(MimirSpacing.lg))
                OnboardingActionGroup {
                    if (actionLabel != null && actionKey != null) {
                        OnboardingPill(
                            label = actionLabel,
                            focusKey = actionKey,
                            gold = true,
                            focused = focusedKey == actionKey,
                            authority = authority,
                            onActivate = onAction,
                            compact = true,
                            interactive = live,
                        )
                    }
                    OnboardingPill(
                        label = stringResource(R.string.app_common_not_now),
                        focusKey = deferKey,
                        gold = false,
                        focused = focusedKey == deferKey,
                        authority = authority,
                        onActivate = onDefer,
                        compact = true,
                        interactive = live,
                    )
                }
            }
        }
    }
}

// Screen 4 — Library folder pick (REQUIRED)
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * The load-bearing REQUIRED step (policy: SAF picker + auto-index, never ask the user to choose a
 * platform). `Choose folder` opens `ACTION_OPEN_DOCUMENT_TREE`; the grant is taken + indexed IN PLACE
 * (nothing moved or copied). `Continue` appears only after a deliberate grant + scan (write-ready gate);
 * there is no skip, and Back is forward-only (swallowed by the flow's root gate). A stale pre-existing
 * permission never auto-satisfies the step — [OnboardingLibraryState.granted] flips only on an in-flow grant.
 */
/** What LIBRARY shows: the ask, the action pages, the plain building screen, or the result. */
internal enum class OnboardingLibraryView { ASK, PAGES, BUILDING, RESULT }

/**
 * The view for a scan [status]. [pagesShow] ([onboardingActionTourShows]) wins while it holds: the pages start at the
 * pick and the scan runs under them. Pure, so the handover order is pinned by a JVM test.
 */
internal fun onboardingLibraryView(status: OnboardingLibraryStatus, pagesShow: Boolean): OnboardingLibraryView = when {
    pagesShow -> OnboardingLibraryView.PAGES
    status == OnboardingLibraryStatus.IDLE || status == OnboardingLibraryStatus.FAILED -> OnboardingLibraryView.ASK
    status == OnboardingLibraryStatus.SCANNING -> OnboardingLibraryView.BUILDING
    else -> OnboardingLibraryView.RESULT
}

/** The focusable pills of each view, in reading order; the building screen has none. */
internal fun onboardingLibraryFocusKeys(view: OnboardingLibraryView): List<FocusKey> = when (view) {
    OnboardingLibraryView.ASK -> listOf(OnboardingChooseFolderKey)
    OnboardingLibraryView.PAGES -> OnboardingActionTourKeys
    OnboardingLibraryView.BUILDING -> emptyList()
    OnboardingLibraryView.RESULT -> listOf(OnboardingLibraryContinueKey, OnboardingPickAnotherKey)
}

/**
 * The load-bearing REQUIRED step (policy: SAF picker + auto-index, never ask the user to choose a
 * platform). `Choose folder` opens `ACTION_OPEN_DOCUMENT_TREE`; the grant is taken + indexed IN PLACE
 * (nothing moved or copied). `Continue` appears only after a deliberate grant + scan (write-ready gate);
 * there is no skip, and Back is forward-only (swallowed by the flow's root gate). A stale pre-existing
 * permission never auto-satisfies the step — [OnboardingLibraryState.granted] flips only on an in-flow grant.
 *
 * The views were hard cuts, and a scan that ended half a second after the pick flashed its screen and cut to the
 * result. Now the action pages start at the pick and hold until the player ends them, and every change of view is
 * the house glide ([onboardingGlide]) on onboarding's clock, the body and the pills together.
 */
@Composable
private fun OnboardingLibraryScreen(
    authority: FocusAuthority,
    library: OnboardingLibraryState,
    focusIntent: OnboardingFocusIntentState,
    gameCount: Int,
    platformCount: Int,
    actionTour: OnboardingActionTourState,
    teachMButton: Boolean,
    onChooseFolder: () -> Unit,
    onAdvance: () -> Unit,
) {
    val palette = LocalMimirPalette.current
    val focus by authority.state.collectAsState()
    val focusedKey = focus.focused.takeIf { focus.scope == OnboardingScope }
    val labels = LocalMimirControllerHintLabels.current
    val res = onboardingResources()
    val pages = remember(res, teachMButton, labels) { onboardingActionPages(res, teachMButton, labels) }
    val pagesShow = onboardingActionTourShows(library.status, actionTour)
    SideEffect {
        actionTour.pageCount = pages.size
        if (pagesShow && !actionTour.started) actionTour.start()
    }
    val view = onboardingLibraryView(library.status, pagesShow)
    val focusKeys = onboardingLibraryFocusKeys(view)
    val reducedMotion = mimirReducedMotion()
    val travelPx = with(LocalDensity.current) { MimirMotion.GlideTravelDp.dp.roundToPx() }

    // Land focus on the view's first pill (Choose folder, Next, Continue); the building screen has none.
    LaunchedEffect(view, focusIntent.revision) {
        if (focusKeys.isEmpty()) {
            focusIntent.clear(OnboardingFocusSurface.LIBRARY)
            authority.clearFocus()
        } else {
            authority.landFocus(
                focusIntent = focusIntent,
                surface = OnboardingFocusSurface.LIBRARY,
                entryFocus = focusKeys.first(),
                validKeys = focusKeys,
            )
        }
    }

    OnboardingScaffold(
        header = { OnboardingHeaderStrip(palette, OnboardingStep.LIBRARY) },
        content = {
            AnimatedContent(
                targetState = view,
                contentAlignment = OnboardingHandoverAlignment,
                transitionSpec = { onboardingGlide(forward = true, travelPx = travelPx, reducedMotion = reducedMotion) },
                label = "onboardingLibraryViewGlide",
            ) { presented ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    when (presented) {
                        OnboardingLibraryView.PAGES -> OnboardingLibraryPages(
                            palette = palette,
                            library = library,
                            gameCount = gameCount,
                            platformCount = platformCount,
                            tour = actionTour,
                            pages = pages,
                            travelPx = travelPx,
                            live = presented == view,
                        )
                        else -> {
                            MimirChromeText(
                                text = stringResource(R.string.app_onboarding_library_title),
                                style = MimirType.TitleCaps,
                                color = palette.textPrimary,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(MimirSpacing.md))
                            when (presented) {
                                OnboardingLibraryView.ASK -> {
                                    Text(
                                        text = stringResource(R.string.app_onboarding_library_body),
                                        style = MimirType.RowSupport,
                                        color = palette.textMuted,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.widthIn(max = 460.dp),
                                    )
                                    // Spec B3: "No folder chosen yet." restated what the button implies; the slot now
                                    // holds only the failure line, and gives the height back otherwise.
                                    if (library.status == OnboardingLibraryStatus.FAILED) {
                                        Spacer(Modifier.height(MimirSpacing.xxl))
                                        Text(
                                            text = onboardingLibraryFailureLine(res, library.failureReason),
                                            style = MimirType.RowSupport,
                                            color = palette.textSecondary,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.widthIn(max = 440.dp),
                                        )
                                    }
                                }
                                OnboardingLibraryView.BUILDING -> OnboardingLibraryBuildingLine(palette)
                                else -> Text(
                                    // The hero count line IS the confirmation (copy review A1) — no techy eyebrow above it.
                                    text = onboardingLibraryCountLine(res, gameCount, platformCount),
                                    style = MimirType.RowSupport,
                                    color = palette.textSecondary,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.widthIn(max = 440.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        controls = {
            AnimatedContent(
                targetState = view,
                contentAlignment = OnboardingControlSlotHandoverAlignment,
                transitionSpec = {
                    onboardingGlide(forward = true, travelPx = travelPx, reducedMotion = reducedMotion, fade = false)
                },
                label = "onboardingLibraryControlsGlide",
            ) { presented ->
                val live = presented == view
                val fadeAlpha = onboardingRingSafeFadeAlpha(reducedMotion)
                Column(
                    modifier = Modifier.onboardingRingSafeFade(fadeAlpha)
                        .then(onboardingHandoverArrivalHold(reducedMotion, fadeAlpha)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    when (presented) {
                        OnboardingLibraryView.ASK -> {
                            Spacer(Modifier.height(MimirSpacing.lg))
                            OnboardingPill(
                                label = stringResource(
                                    if (library.status == OnboardingLibraryStatus.FAILED) {
                                        R.string.app_onboarding_library_choose_another
                                    } else {
                                        R.string.app_onboarding_library_choose_folder
                                    },
                                ),
                                focusKey = OnboardingChooseFolderKey,
                                gold = true,
                                focused = focusedKey == OnboardingChooseFolderKey,
                                authority = authority,
                                onActivate = onChooseFolder,
                                interactive = live,
                            )
                        }
                        OnboardingLibraryView.PAGES -> {
                            Spacer(Modifier.height(MimirSpacing.lg))
                            val failed = library.status == OnboardingLibraryStatus.FAILED
                            OnboardingActionGroup {
                                OnboardingPill(
                                    label = stringResource(
                                        if (actionTour.page + 1 >= pages.size) {
                                            R.string.app_onboarding_library_done
                                        } else {
                                            R.string.app_onboarding_library_next
                                        },
                                    ),
                                    focusKey = OnboardingActionTourNextKey,
                                    gold = true,
                                    focused = focusedKey == OnboardingActionTourNextKey,
                                    authority = authority,
                                    onActivate = { if (failed) actionTour.skip() else actionTour.next() },
                                    compact = true,
                                    interactive = live,
                                )
                                OnboardingPill(
                                    label = stringResource(R.string.app_onboarding_library_skip),
                                    focusKey = OnboardingActionTourSkipKey,
                                    gold = false,
                                    focused = focusedKey == OnboardingActionTourSkipKey,
                                    authority = authority,
                                    onActivate = { actionTour.skip() },
                                    compact = true,
                                    interactive = live,
                                )
                            }
                        }
                        OnboardingLibraryView.BUILDING -> Unit
                        OnboardingLibraryView.RESULT -> {
                            Spacer(Modifier.height(MimirSpacing.xl))
                            OnboardingActionGroup {
                                OnboardingPill(
                                    label = stringResource(R.string.app_common_continue),
                                    focusKey = OnboardingLibraryContinueKey,
                                    gold = true,
                                    focused = focusedKey == OnboardingLibraryContinueKey,
                                    authority = authority,
                                    onActivate = onAdvance,
                                    interactive = live,
                                )
                                OnboardingPill(
                                    label = stringResource(R.string.app_onboarding_library_choose_another),
                                    focusKey = OnboardingPickAnotherKey,
                                    gold = false,
                                    focused = focusedKey == OnboardingPickAnotherKey,
                                    authority = authority,
                                    onActivate = onChooseFolder,
                                    interactive = live,
                                )
                            }
                        }
                    }
                }
            }
        },
    )
}

/**
 * The pages, under the scan's one status line. The line changes with the scan (building, then the count, or the
 * failure), faded in place; the page turns glide the way the player went.
 */
@Composable
private fun OnboardingLibraryPages(
    palette: MimirThemePalette,
    library: OnboardingLibraryState,
    gameCount: Int,
    platformCount: Int,
    tour: OnboardingActionTourState,
    pages: List<OnboardingActionPage>,
    travelPx: Int,
    live: Boolean,
) {
    val reducedMotion = mimirReducedMotion()
    val res = onboardingResources()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedContent(
            targetState = library.status,
            contentAlignment = OnboardingHandoverAlignment,
            transitionSpec = {
                (fadeIn(onboardingHandoverEnterSpec(reducedMotion)) togetherWith
                    fadeOut(onboardingHandoverExitSpec(reducedMotion)))
                    .using(SizeTransform(clip = false) { _, _ -> onboardingHandoverEnterSpec(reducedMotion) })
            },
            label = "onboardingLibraryStatusFade",
        ) { status ->
            when (status) {
                OnboardingLibraryStatus.DONE -> Text(
                    text = onboardingLibraryCountLine(res, gameCount, platformCount),
                    style = MimirType.RowSupport,
                    color = palette.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 440.dp),
                )
                OnboardingLibraryStatus.FAILED -> Text(
                    text = onboardingLibraryFailureLine(res, library.failureReason),
                    style = MimirType.RowSupport,
                    color = palette.warning,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 440.dp),
                )
                else -> OnboardingLibraryBuildingLine(palette)
            }
        }
        Spacer(Modifier.height(MimirSpacing.md))
        val sceneHeight = onboardingActionSceneHeight(onboardingHintViewportHeight(), LocalDensity.current.fontScale)
        AnimatedContent(
            targetState = tour.page,
            contentAlignment = OnboardingHandoverAlignment,
            transitionSpec = {
                onboardingGlide(forward = targetState > initialState, travelPx = travelPx, reducedMotion = reducedMotion)
            },
            label = "onboardingActionPageGlide",
        ) { presentedPage ->
            // A second A on Next waits for the page it turns to ([onboardingHandoverArrivalHold]).
            Box(onboardingHandoverArrivalHold(reducedMotion)) {
                pages.getOrNull(presentedPage)?.let { page ->
                    OnboardingActionPageBody(
                        page = page,
                        index = presentedPage,
                        count = pages.size,
                        sceneHeight = sceneHeight,
                        playing = live && presentedPage == tour.page,
                    )
                }
            }
        }
    }
}

/** The honest post-scan count line (canonical `N games` vocabulary), or a gentle nudge when a folder is empty. */
// One line per singular/plural pairing, never a games phrase and a platforms phrase joined here: another language orders and
// agrees the two differently.
internal fun onboardingLibraryCountLine(res: Resources, gameCount: Int, platformCount: Int): String = when {
    gameCount <= 0 -> res.getString(R.string.app_onboarding_library_none)
    gameCount == 1 && platformCount == 1 -> res.getString(R.string.app_onboarding_library_count_one_one)
    gameCount == 1 -> res.getString(R.string.app_onboarding_library_count_one_many, platformCount)
    platformCount == 1 -> res.getString(R.string.app_onboarding_library_count_many_one, gameCount)
    else -> res.getString(R.string.app_onboarding_library_count_many_many, gameCount, platformCount)
}

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// Screen 5 — Local identity + optional connected game details

@Composable
private fun OnboardingMetadataScreen(
    authority: FocusAuthority,
    artworkAccount: String?,
    steamGridDbKeyPresent: Boolean,
    retroAchievementsUser: String?,
    retroAchievementsKeyRejected: Boolean,
    focusIntent: OnboardingFocusIntentState,
    onOpenArtworkSetup: () -> Unit,
    onOpenSteamGridDbKey: () -> Unit,
    onOpenRetroAchievements: () -> Unit,
    onAdvance: () -> Unit,
) {
    val palette = LocalMimirPalette.current
    val focus by authority.state.collectAsState()
    val focusReveal = remember { OnboardingFocusRevealState() }
    val account = artworkAccount?.trim()?.takeIf(String::isNotEmpty)
    val res = onboardingResources()
    val steamGridDbCopy = onboardingSteamGridDbCopy(res, steamGridDbKeyPresent)
    val retroAchievementsCopy = onboardingRetroAchievementsCopy(res, retroAchievementsUser, retroAchievementsKeyRejected)
    val focusKeys = onboardingMetadataFocusKeys(account != null)

    // Entry focus is the first art source, not Continue -- policy 2026-09-18, see
    // [onboardingEntryFocus]. This step asks the player to connect the thing that fetches art, and opening
    // on Continue let one A press produce exactly the artless library this PR exists to fix.
    LaunchedEffect(account, focusIntent.revision) {
        authority.landFocus(
            focusIntent = focusIntent,
            surface = OnboardingFocusSurface.METADATA,
            entryFocus = onboardingEntryFocus(focusKeys, OnboardingMetadataContinueKey),
            validKeys = focusKeys,
        )
    }

    OnboardingScaffold(
        authorityFocusedKey = focus.focused.takeIf { focus.scope == OnboardingScope },
        focusReveal = focusReveal,
        header = { OnboardingHeaderStrip(palette, OnboardingStep.METADATA) },
        content = {
        MimirChromeText(
            // Policy 2026-09-17, on reading the flow in order: this read "Identify your library" --
            // and the player pressed Continue straight into a screen headed "Identifying your games". Two
            // screens in a row appeared to do the same job. This screen never identified anything: it asks whether to connect two optional online
            // services. The screen that identifies is SETTLING, and it says so itself.
            // the title names what the player gets. It replaces the 09-17
            // "Connect your accounts"; SteamGridDB is a key, not an account.
            text = stringResource(R.string.app_onboarding_metadata_title),
            style = MimirType.TitleCaps,
            color = palette.textPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(MimirSpacing.xs))
        Text(
            // Content review 2026-08-21 (A9): "catalogues" was the wrong register for these two services,
            // and this exact sentence duplicated the SteamGridDB row's detail beneath it verbatim.
            // OR-15 made it three rows, so "Both" became "Each"; the offline sentence went to fit the third row
            // (tighten, then drop the subtitle's second sentence).
            text = stringResource(R.string.app_onboarding_metadata_subtitle),
            style = MimirType.RowSupport,
            color = palette.textMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 580.dp),
        )
        // OR-15: xs, not lg, so the third row fits at the chosen row spacing.
        Spacer(Modifier.height(MimirSpacing.xs))

        // The "Local identification / prepared locally / On-device" row was removed here (policy
        // 2026-09-17). It described a mechanism the player never chose, cannot change, and gets either way
        // -- and it was the third thing on a screen whose actual question is "do you want to connect an
        // account?". Its one real promise, that Mimir works offline, is already the subtitle above.
        //
        // Fit : xs, not sm, between these rows -- this screen overflowed the Odin
        // reference viewport by ~10dp with scroll still present to hide it (measured,
        // OnboardingScreenFitTest). Dropping a row gives that budget back; the token is unchanged.
        if (account == null) {
            OnboardingCapabilityRow(
                title = stringResource(R.string.app_onboarding_metadata_screenscraper_title),
                // "provider titles" was internal vocabulary -- the provider is the thing being named on
                // the row above it, so the word said nothing the title had not.
                detail = stringResource(R.string.app_onboarding_metadata_screenscraper_detail),
                actionLabel = stringResource(R.string.app_common_connect),
                actionKey = OnboardingArtworkSetupKey,
                actionFocused = focus.scope == OnboardingScope && focus.focused == OnboardingArtworkSetupKey,
                authority = authority,
                onAction = onOpenArtworkSetup,
                focusReveal = focusReveal,
            )
        } else {
            OnboardingCapabilityRow(
                title = stringResource(R.string.app_onboarding_metadata_screenscraper_title),
                // Corrected 2026-09-17, and it was WRONG rather than merely jargon: artwork no longer
                // "stays in Settings". Since the enrich offer moved to the end of hashing
                // (`NewGameEnrichPromptState.offerAfterHash`), Mimir asks about artwork on its own once the
                // library settles. A screen promising the player they would have to go find it in Settings
                // set up exactly the surprise the regression report described as the enrich menu appearing "random".
                // "A small details pass" went with it; nothing outside this codebase calls it a pass.
                detail = stringResource(R.string.app_onboarding_metadata_screenscraper_connected, account),
                status = stringResource(R.string.app_common_connected),
                statusColor = palette.success,
            )
        }
        Spacer(Modifier.height(MimirSpacing.xs))
        // SteamGridDB key, alongside ScreenScraper here too. Reuses the app-shell key overlay.
        OnboardingCapabilityRow(
            title = stringResource(R.string.app_onboarding_metadata_steamgriddb_title),
            detail = steamGridDbCopy.detail,
            status = steamGridDbCopy.status,
            statusColor = palette.success,
            actionLabel = steamGridDbCopy.action,
            actionKey = OnboardingSteamGridDbKey,
            actionFocused = focus.scope == OnboardingScope && focus.focused == OnboardingSteamGridDbKey,
            authority = authority,
            onAction = onOpenSteamGridDbKey,
            focusReveal = focusReveal,
        )
        Spacer(Modifier.height(MimirSpacing.xs))
        // OR-15 : RetroAchievements beside the two art sources, through the same connect overlay
        // Settings' Profile row opens.
        OnboardingCapabilityRow(
            title = "RetroAchievements",
            detail = retroAchievementsCopy.detail,
            status = retroAchievementsCopy.status,
            statusColor = palette.success,
            actionLabel = retroAchievementsCopy.action,
            actionKey = OnboardingRetroAchievementsKey,
            actionFocused = focus.scope == OnboardingScope && focus.focused == OnboardingRetroAchievementsKey,
            authority = authority,
            onAction = onOpenRetroAchievements,
            focusReveal = focusReveal,
        )
        },
        controls = {
            Spacer(Modifier.height(MimirSpacing.xs))
            OnboardingPill(
                label = stringResource(R.string.app_common_continue),
                focusKey = OnboardingMetadataContinueKey,
                gold = true,
                focused = focus.scope == OnboardingScope && focus.focused == OnboardingMetadataContinueKey,
                authority = authority,
                onActivate = onAdvance,
                compact = true,
            )
        },
        )
}

// ───────────────────────────────────────────────────────────────────────────────────────────────────
// Screen 6 — First-shelf settlement
// ──────────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * Which visual the 6 dp progress track renders. [OnboardingSettlementUi.total] is `null` whenever no
 * honest denominator exists for the current phase (gives IDENTIFYING a real one
 * while identities remain — [onboardingIdentifyingFraction] — but GROUPING and INDEXING still have none:
 * regrouping shrinks the live population while it runs, and indexing hasn't finished counting yet). Total
 * non-null and > 0 is what makes [DETERMINATE] reachable; [SWEEP] is every other in-progress case. [HIDDEN]
 * is reduced motion: never a static empty track (that was the original defect this enum fixed) — render
 * nothing where the bar was, but the caller still reserves its footprint so nothing else in the layout
 * shifts.
 */
internal enum class OnboardingProgressBarMode { DETERMINATE, SWEEP, HIDDEN }

/**
 * The displayed fraction never falls within one settlement phase.
 *
 * **Not smoothing — arithmetic honesty.** `done` is derived as `total - resolvingCount`, and regrouping
 * shrinks `total` mid-run (`onboardingIdentifyingFraction`'s KDoc: *"can only push [done] down, and the
 * clamp below floors that at 0"*). So the raw fraction can fall while nothing was undone. The job
 * model's own contract — *seen is work that has demonstrably happened* — is what forbids rendering
 * that as regress.
 *
 * A null [next] still wins: losing the denominator means the product no longer knows the fraction, and
 * the high-water mark must not keep drawing one it can no longer justify. That is the same rule as a
 * null total drawing no bar, applied one layer up.
 *
 * Pure, so the rule is pinned by a JVM test rather than trusted.
 */
internal fun onboardingMonotonicProgress(highWater: Float?, next: Float?): Float? = when {
    next == null -> null
    highWater == null -> next
    else -> maxOf(highWater, next)
}

/** Pure so the branch is pinned by a JVM test rather than felt for on device. */
internal fun onboardingProgressBarMode(progress: Float?, reducedMotion: Boolean): OnboardingProgressBarMode = when {
    progress != null && progress > 0f -> OnboardingProgressBarMode.DETERMINATE
    reducedMotion -> OnboardingProgressBarMode.HIDDEN
    else -> OnboardingProgressBarMode.SWEEP
}

/**
 * The settling screen's status line.  whenever [total] is a real
 * denominator; otherwise a plain "Working" -- never the old "Working · exact total unavailable" tail. That
 * caveat was never true (a total-primaries query has always existed, `LibraryRepository.observeSummary`),
 * and now that [total] is populated whenever an honest one exists for the phase
 * ([onboardingIdentifyingFraction]), there is nothing left to caveat: `total == null` already means the
 * bar itself fell back to [OnboardingProgressBarMode.SWEEP]. Pure so the copy is pinned by a JVM test.
 */
internal fun onboardingSettlementStatusIntent(res: Resources, done: Int, total: Int?): MimirTextIntent =
    if (total != null && total > 0) {
        onboardingMixedWithNumbers(res.getString(R.string.app_onboarding_settling_checked), listOf(done, total))
    } else {
        MimirTextIntent.Mixed(listOf(MimirTextFragment.Chrome(res.getString(R.string.app_onboarding_settling_working))))
    }

@Composable
private fun OnboardingSettlingScreen(
    authority: FocusAuthority,
    settlement: OnboardingSettlementUi,
    focusIntent: OnboardingFocusIntentState,
    onRetry: () -> Unit,
    onContinueLocally: () -> Unit,
    onAdvance: () -> Unit,
) {
    val palette = LocalMimirPalette.current
    val res = onboardingResources()
    val focus by authority.state.collectAsState()
    val entryFocus = settlementEntryFocus(settlement)
    val focusKeys = buildList {
        if (settlement.canRetry) add(OnboardingSettlementRetryKey)
        if (settlement.canContinue) add(OnboardingSettlementContinueKey)
    }
    val done = settlement.done.coerceAtLeast(0)
    val total = settlement.total?.coerceAtLeast(0)
    val rawProgress = total?.takeIf { it > 0 }?.let { (done.toFloat() / it.toFloat()).coerceIn(0f, 1f) }
    // A per-run snapshot keeps its checked denominator stable. A new run key resets the high-water
    // value; once all hashes finish, the denominator disappears until commit and regroup settle.
    var progressHighWater by remember(settlement.phase, settlement.progressRunId) { mutableStateOf<Float?>(null) }
    val progress = onboardingMonotonicProgress(progressHighWater, rawProgress)
    if (progress != progressHighWater) progressHighWater = progress
    val progressBarReducedMotion = mimirReducedMotion()
    val progressBarMode = onboardingProgressBarMode(progress, progressBarReducedMotion)
    val showProgress = total != null || settlement.phase == OnboardingSettlementPhase.INDEXING ||
        settlement.phase == OnboardingSettlementPhase.IDENTIFYING
    // The primary status line below already shows "N of M checked" when `total` is non-null;
    // repeating the same games-checked fact here would duplicate it.
    val secondaryProgressLabel = ""
    val phaseColor = when (settlement.phase) {
        OnboardingSettlementPhase.READY -> palette.success
        OnboardingSettlementPhase.LIMITED -> palette.warning
        OnboardingSettlementPhase.FAILED -> palette.danger
        else -> palette.accentBright
    }

    LaunchedEffect(entryFocus, focusIntent.revision) {
        if (entryFocus == null) {
            focusIntent.clear(OnboardingFocusSurface.SETTLING)
            authority.clearFocus()
        } else {
            authority.landFocus(
                focusIntent = focusIntent,
                surface = OnboardingFocusSurface.SETTLING,
                entryFocus = entryFocus,
                validKeys = focusKeys,
            )
        }
    }

    OnboardingScaffold(
        header = { OnboardingHeaderStrip(palette, OnboardingStep.SETTLING) },
        content = {
        MimirChromeText(
            text = onboardingSettlementPhaseLabel(res, settlement.phase),
            style = MimirType.RunicEyebrow,
            color = phaseColor,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(MimirSpacing.sm))
        MimirChromeText(
            text = settlement.title,
            style = MimirType.TitleCaps,
            color = palette.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 620.dp),
        )
        Spacer(Modifier.height(MimirSpacing.sm))
        Text(
            text = settlement.detail,
            style = MimirType.RowSupport,
            color = palette.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 600.dp),
        )
        if (showProgress) {
            Spacer(Modifier.height(MimirSpacing.lg))
            Column(
                modifier = Modifier
                    .widthIn(max = 620.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
            ) {
                when (progressBarMode) {
                    OnboardingProgressBarMode.DETERMINATE -> Box(
                        Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(palette.outline),
                    ) {
                        // progressBarMode == DETERMINATE only when progress != null (see
                        // onboardingProgressBarMode) -- the `let` is defensive, never actually null here.
                        progress?.let { fraction ->
                            Box(
                                Modifier
                                    .fillMaxWidth(fraction)
                                    .height(6.dp)
                                    .background(phaseColor),
                            )
                        }
                    }
                    // The no-honest-denominator case: GROUPING (regrouping shrinks the live population
                    // while it runs), INDEXING (still counting), and the zero-remaining tail before a
                    // fresh stage record lands all pass `total = null` here -- IDENTIFYING with identities
                    // still remaining is the one phase with a real fraction now (policy 2026-08-20,
                    // [onboardingIdentifyingFraction]) and takes the DETERMINATE arm above instead. An
                    // indeterminate sweep says "working" without pretending to a denominator that phase
                    // doesn't have.
                    OnboardingProgressBarMode.SWEEP -> MimirIndeterminateBar(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        trackColor = palette.outline,
                        sweepColor = phaseColor,
                        reducedMotion = false,
                    )
                    // Reduced motion: a static empty track reproduces the exact defect this ruling fixes,
                    // so nothing paints here -- but the Box still claims the same 6 dp height so the
                    // status row below does not shift up.
                    OnboardingProgressBarMode.HIDDEN -> Box(
                        Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                    )
                }
                Row(Modifier.fillMaxWidth()) {
                    MimirIntentText(
                        intent = onboardingSettlementStatusIntent(res, done = done, total = total),
                        style = MimirType.MicroStatus,
                        color = palette.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    if (secondaryProgressLabel.isNotEmpty()) {
                        Text(
                            text = secondaryProgressLabel,
                            style = MimirType.MicroStatus,
                            color = palette.textMuted,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        // The hashing poem (Ticket V-1) used to sit here. It was retired on 2026-09-18.

        },
        controls = {
            if (settlement.canRetry || settlement.canContinue) {
                Spacer(Modifier.height(MimirSpacing.lg))
                OnboardingActionGroup {
                    if (settlement.canRetry) {
                        OnboardingPill(
                            label = stringResource(R.string.app_onboarding_settling_retry),
                            focusKey = OnboardingSettlementRetryKey,
                            gold = settlement.phase != OnboardingSettlementPhase.READY,
                            focused = focus.scope == OnboardingScope && focus.focused == OnboardingSettlementRetryKey,
                            authority = authority,
                            onActivate = onRetry,
                            compact = true,
                        )
                    }
                    if (settlement.canContinue) {
                        OnboardingPill(
                            label = stringResource(
                                if (settlement.phase == OnboardingSettlementPhase.READY ||
                                    settlement.phase == OnboardingSettlementPhase.IDENTIFYING
                                ) {
                                    R.string.app_common_continue
                                } else {
                                    R.string.app_onboarding_settling_continue_locally
                                },
                            ),
                            focusKey = OnboardingSettlementContinueKey,
                            gold = settlement.phase == OnboardingSettlementPhase.READY || !settlement.canRetry,
                            focused = focus.scope == OnboardingScope && focus.focused == OnboardingSettlementContinueKey,
                            authority = authority,
                            onActivate = {
                                activateSettlementContinue(settlement, onAdvance, onContinueLocally)
                            },
                            compact = true,
                        )
                    }
                }
            }
        },
        )
}

/**
 *  The house quiet loader (the same
 * indicator GamelistCarousel holds while a platform's games load), with the scan's own counts beneath it
 * when the scan has published any. Counts only state work that has happened; no fraction is invented.
 * It heads the action pages while the scan runs, and stands alone once they have ended.
 */
@Composable
private fun OnboardingLibraryBuildingLine(palette: MimirThemePalette) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // The loading circle and counts already convey library-building progress; copy states the promise.
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                color = palette.accentBright,
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(MimirSpacing.sm))
            Text(
                text = stringResource(R.string.app_onboarding_library_building_promise),
                style = MimirType.RowSupport,
                color = palette.accentBright,
                textAlign = TextAlign.Center,
            )
        }
        OnboardingLibraryBuildingCountsLine(palette)
    }
}

/** Why a scan failed, in the player's words: Android dropped the grant, or the folder could not be read. */
internal fun onboardingLibraryFailureLine(res: Resources, failureReason: String?): String =
    res.getString(
        if (failureReason == OnboardingMissingGrantFailure) {
            R.string.app_onboarding_library_failure_grant
        } else {
            R.string.app_onboarding_library_failure_read
        },
    )

/** The scan leaves games in place: nothing is moved or copied. */

/** Whether LIBRARY still shows its ask: before a pick and after a failed one, not while scanning or once done. */
internal fun onboardingLibraryAsksForFolder(status: OnboardingLibraryStatus): Boolean =
    status == OnboardingLibraryStatus.IDLE || status == OnboardingLibraryStatus.FAILED

/** The scan's counts, in their own small scope, so a count tick recomposes this line and never the carousel. */
@Composable
private fun OnboardingLibraryBuildingCountsLine(palette: MimirThemePalette) {
    val scan by ScanProgressState.progress.collectAsState()
    onboardingLibraryBuildingCounts(onboardingResources(), scan)?.let { counts ->
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(MimirSpacing.xs))
            Text(
                text = counts,
                style = MimirType.MicroStatus,
                color = palette.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

internal fun onboardingLibraryBuildingCounts(res: Resources, scan: ScanProgress?): String? {
    if (scan == null || scan.seen <= 0) return null
    // Plurals, one per line shape: the noun agrees with the number it follows, which in "X of Y files" is Y.
    val seen = scan.seen
    return when (scan.phase) {
        ScanPhase.WALKING -> res.getQuantityString(R.plurals.app_onboarding_library_scan_found, seen, seen)
        ScanPhase.RECOGNISING -> scan.total?.let {
            res.getQuantityString(R.plurals.app_onboarding_library_scan_recognizing_of, it, seen, it)
        } ?: res.getQuantityString(R.plurals.app_onboarding_library_scan_recognizing, seen, seen)
        ScanPhase.GROUPING -> res.getQuantityString(R.plurals.app_onboarding_library_scan_grouping, seen, seen)
        ScanPhase.PERSISTING -> res.getQuantityString(R.plurals.app_onboarding_library_scan_saving, seen, seen)
    }
}

private fun onboardingSettlementPhaseLabel(res: Resources, phase: OnboardingSettlementPhase): String = res.getString(
    when (phase) {
        // Content review 2026-08-21 (A7): "indexing" is internal resolver vocabulary.
        OnboardingSettlementPhase.INDEXING -> R.string.app_onboarding_settling_phase_indexing
        OnboardingSettlementPhase.IDENTIFYING -> R.string.app_onboarding_settling_phase_identifying
        OnboardingSettlementPhase.READY -> R.string.app_onboarding_settling_phase_ready
        OnboardingSettlementPhase.LIMITED -> R.string.app_onboarding_settling_phase_limited
        OnboardingSettlementPhase.FAILED -> R.string.app_onboarding_settling_phase_failed
    },
)

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// Screen 7 — Theme pick + setup complete
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * The appended "choose your look" step. Renders the SHARED [ThemePreviewGallery] (also used by Settings › Appearance) in the
 * flow's active [OnboardingScope]: a 3×3 wall of nine cards each painted in its own palette. Picking a
 * theme applies it live (the DONE screen + shelf re-skin) and advances; Back keeps the current theme + advances.
 */
/**
 * The FAVORITES step, which is TWO pages: what the shelf is, then the Tome that fills it.
 *
 * The instruction page leads through an Open Tome button to a searchable grid using the Tome
 * collection's interaction pattern, transitions and Mannaz favorite mark. The earlier grid target
 * was 4x4 before the smaller-face layout below.
 *
 * The single-page layout could not show a complete row. On the reference device (1080x1920 at density 369 = 832x468dp in
 * landscape) the old layout stacked header, eyebrow, title, body, count line and rail above the grid and a
 * pill below it: 294dp of chrome, leaving 174dp for rows 208dp tall. The step whose entire job is filling
 * the Home shelf could not show one complete row of the shelf's contents.
 *
 * Splitting the step fixes it by arithmetic rather than by tuning. The prose has a page to itself and can
 * be as instructive as it likes. The Tome page spends 94dp on the control row and a hint line and gives the
 * remaining 374dp to boxes: three full rows of five, at the smaller face size
 * ([OnboardingFavoritesFaceWidthDp]).
 *
 * @see OnboardingFavoritesIntroScreen
 * @see OnboardingFavoritesTomeScreen
 */
@Composable
private fun OnboardingFavoritesScreen(
    authority: FocusAuthority,
    focusIntent: OnboardingFocusIntentState,
    games: List<GameRow>,
    identifying: OnboardingFavoritesIdentifying?,
    opensTomeOnSettle: Boolean,
    identifyingInBackground: Boolean = false,
    filter: OnboardingFavoritesGridState,
    onToggleFavorite: (GameRow) -> Unit,
    onAdvance: () -> Unit,
) {
    val reducedMotion = mimirReducedMotion()
    val travelPx = with(LocalDensity.current) { MimirMotion.GlideTravelDp.dp.roundToPx() }

    // See [onboardingFavoritesAutoOpensTome]. Keyed on the boolean alone, so the count ticking does not restart it.
    var wasIdentifying by remember { mutableStateOf(identifying != null) }
    LaunchedEffect(identifying != null) {
        val opens = onboardingFavoritesAutoOpensTome(
            wasIdentifying = wasIdentifying,
            identifyingNow = identifying != null,
            identifiedWhileWaiting = opensTomeOnSettle,
            page = filter.page,
        )
        wasIdentifying = identifying != null
        if (opens) {
            filter.openTome()
            focusIntent.clear(OnboardingFocusSurface.FAVORITES)
        }
    }

    //  This boundary had no transition AT ALL -- opening the Tome and closing it were both hard
    // cuts, which is the one place in onboarding where the player crosses between two pages of the same
    // step and got no acknowledgement of it whatsoever. Same [onboardingGlide] as ACCESS,
    // and the same source of direction: INTRO -> TOME is forward, TOME -> INTRO is back, which the enum's
    // own order already states.
    //
    // `live` matters MORE here than at the other two boundaries, not less. AnimatedContent keeps the
    // departing page composed for the whole OverlayMs window, and INTRO's Continue pill calls `onAdvance`,
    // which leaves the favourites step entirely -- so a touch landing on a departing INTRO while the Tome
    // opens would skip the step the player just chose to engage with. OnboardingPill's `interactive = false`
    // removes the clickable and clears the semantics outright, so there is nothing left to hit.
    //
    // The shared-FocusKey collision that forces this gating on ACCESS does NOT apply here (INTRO owns
    // {OpenTome, Continue} and the Tome owns {Search, PlatformFilter, per-game}, disjoint sets, so a
    // departing page's unregister can never delete an arriving page's live entry) -- and "two gold pills at
    // once" cannot happen for the same reason. This gating is purely about the stray touch.
    // NOT [OnboardingHandoverAlignment], and that is the point: this boundary is the one that does not sit
    // in [OnboardingScaffold]'s centred column, so the cancellation that alignment relies on does not apply
    // here. It is also moot -- both of this pair's pages root at `fillMaxSize()`, so they measure to the
    // same height and the box has nothing to resize between them. Left as it was rather than made uniform
    // for the look of it.
    //
    // Nova device report, 2026-09-30: each page drew its own sky, so
    // the glide slid two starfields past each other, each half-faded. The sky is the step's now, still under both
    // pages, and only the pages glide. The Tome's glass rides on it: the platform list blurs the sky and the boxes
    // together , and the Tome's two overlays sit over the blur, outside it.
    //
    // RC-27 / RC-05 (audit 2026-10-14, O-27): the glass was a `by animateFloatAsState` read into the Float overload of
    // `mimirGlassBackdrop`, so the whole page (sky, boxes, the page glide) recomposed on every frame of its 180 ms ramp and the blur
    // layer was dropped and rebuilt at both ends. It is a draw-phase progress now ([rememberOnboardingFavoritesFilterGlass]): nothing
    // recomposes per frame, and it holds the glass for the list's dissolve before it clears (08-15).
    val filterGlass = rememberOnboardingFavoritesFilterGlass(open = filter.filterOpen)
    Box(Modifier.fillMaxSize()) {
        MimirShelfBackground(
            modifier = Modifier.fillMaxSize().mimirGlassBackdropProgress(filterGlass),
            quietSceneLight = onboardingQuietSceneLight(LocalMimirViewportProfile.current),
            sceneLightBelowText = onboardingSceneLightBelowText(LocalMimirViewportProfile.current),
        ) {
            AnimatedContent(
                targetState = filter.page,
                contentAlignment = Alignment.TopCenter,
                transitionSpec = {
                    onboardingGlide(
                        forward = targetState.ordinal > initialState.ordinal,
                        travelPx = travelPx,
                        reducedMotion = reducedMotion,
                    )
                },
                label = "onboardingFavoritesPageGlide",
            ) { presented ->
                val live = presented == filter.page
                // A press into the page arriving waits for it ([onboardingHandoverArrivalHold]).
                Box(Modifier.fillMaxSize().then(onboardingHandoverArrivalHold(reducedMotion))) {
                    when (presented) {
                        OnboardingFavoritesPage.INTRO -> OnboardingFavoritesIntroScreen(
                            authority = authority,
                            focusIntent = focusIntent,
                            // The WHOLE library, never what a filter happens to be showing: "3 games on your
                            // shelf" must not drop to 1 because the Tome was last narrowed to one platform.
                            chosen = games.count { it.isFavorite },
                            identifying = identifying,
                            identifyingInBackground = identifyingInBackground,
                            onOpenTome = {
                                if (identifying != null) return@OnboardingFavoritesIntroScreen
                                filter.openTome()
                                focusIntent.clear(OnboardingFocusSurface.FAVORITES)
                            },
                            onAdvance = onAdvance,
                            interactive = live,
                        )

                        OnboardingFavoritesPage.TOME -> OnboardingFavoritesTomeScreen(
                            authority = authority,
                            focusIntent = focusIntent,
                            games = games,
                            filter = filter,
                            onToggleFavorite = onToggleFavorite,
                            onClose = {
                                filter.closeTome()
                                focusIntent.clear(OnboardingFocusSurface.FAVORITES)
                            },
                            interactive = live,
                        )
                    }
                }
            }
        }
        OnboardingFavoritesTomeOverlays(
            authority = authority,
            focusIntent = focusIntent,
            games = games,
            filter = filter,
        )
    }
}

/**
 * The Tome's keyboard and platform list, drawn over the favourites step's sky and its glass rather than inside the
 * page (walk-32d): the list blurs the sky and the boxes behind it, and a layer inside the blur would blur with them.
 * Both flags drop with the page ([OnboardingFavoritesGridState.closeTome]), so neither can stand over INTRO.
 */
@Composable
private fun OnboardingFavoritesTomeOverlays(
    authority: FocusAuthority,
    focusIntent: OnboardingFocusIntentState,
    games: List<GameRow>,
    filter: OnboardingFavoritesGridState,
) {
    OnboardingKeyboardLayer(
        open = filter.keyboardOpen,
        authority = authority,
        palette = LocalMimirPalette.current,
        draft = filter.query,
        onValueChange = filter::search,
        onClose = filter::closeKeyboard,
        onKeyboardHostChanged = { filter.keyboardHost = it },
        eyebrow = stringResource(R.string.app_onboarding_favorites_search_eyebrow),
        placeholder = stringResource(R.string.app_onboarding_favorites_search_placeholder),
        support = stringResource(R.string.app_onboarding_favorites_search_support),
        targetLabel = stringResource(R.string.app_common_search),
    )
    // RC-27 (audit 2026-10-14, O-27e): the list and its scrim used to mount at full alpha on frame one, over a blur still ramping, and
    // to cut out at once on close. They fade on the sheet clock (the keyboard layer's), and the step's glass holds under the leave
    // ([rememberOnboardingFavoritesFilterGlass]). From the frame `filterOpen` goes false the leaving list is inert: no focus
    // registration, no Back, no touch.
    val sheetReducedMotion = mimirReducedMotion()
    AnimatedVisibility(
        visible = filter.filterOpen,
        enter = fadeIn(mimirSheetSpec(sheetReducedMotion)),
        exit = fadeOut(mimirSheetSpec(sheetReducedMotion)),
        modifier = Modifier.fillMaxSize(),
        label = "onboardingFavoritesPlatformList",
    ) {
        val focus by authority.state.collectAsState()
        OnboardingFavoritesPlatformDropdown(
            open = filter.filterOpen,
            authority = authority,
            focusIntent = focusIntent,
            // The page's own lists, from the same cache ([OnboardingFavoritesGridState.tomeLists]).
            platforms = filter.tomeLists(games).platforms,
            selected = filter.platform,
            focusedKey = focus.focused.takeIf { focus.scope == OnboardingScope },
            onDismiss = filter::closeFilter,
            onPick = filter::pick,
        )
    }
}

/**
 * Page one: what the Home shelf is, and the button that opens the Tome.
 *
 * No games on this page at all. That is not an omission -- it is the ruling. A screen that teaches and a
 * screen that shows boxes want opposite things from 468dp of height, and the previous version tried to be
 * both and was neither.
 */
@Composable
private fun OnboardingFavoritesIntroScreen(
    authority: FocusAuthority,
    focusIntent: OnboardingFocusIntentState,
    chosen: Int,
    identifying: OnboardingFavoritesIdentifying?,
    identifyingInBackground: Boolean = false,
    onOpenTome: () -> Unit,
    onAdvance: () -> Unit,
    /** False while this page is the one fading OUT -- see the router's own comment. */
    interactive: Boolean = true,
) {
    val palette = LocalMimirPalette.current
    val focus by authority.state.collectAsState()
    val focusedKey = focus.focused.takeIf { focus.scope == OnboardingScope }
    val res = onboardingResources()
    val copy = onboardingFavoritesCopy(res, chosen)
    val keys = onboardingFavoritesIntroKeys()

    // Both keys exist from the first composition -- this page does not wait on the library -- so the
    // ordinary landing is enough here. The Tome page is the one that needed QA-16's re-assert.
    //
    // Gated on `interactive` for the same reason the Tome's landing is (see there): while the two pages
    // overlap, exactly one of them may speak for OnboardingFocusSurface.FAVORITES, and it is the one
    // arriving.
    LaunchedEffect(focusIntent.revision, interactive) {
        if (!interactive) return@LaunchedEffect
        authority.landFocus(
            focusIntent = focusIntent,
            surface = OnboardingFocusSurface.FAVORITES,
            entryFocus = OnboardingFavoritesOpenTomeKey,
            validKeys = keys,
        )
    }

    OnboardingScaffold(
        // The favourites step draws one sky under both its pages (walk-32d).
        drawsSky = false,
        header = { OnboardingHeaderStrip(palette, OnboardingStep.FAVORITES) },
        content = {
            Text(
                text = copy.eyebrowVisual,
                style = MimirType.Pill,
                color = palette.accent,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(MimirSpacing.xs))
            MimirChromeText(
                text = copy.title,
                style = MimirType.TitleCaps,
                color = palette.textPrimary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(MimirSpacing.sm))
            OnboardingAccessBody(copy.body, palette)
            Spacer(Modifier.height(MimirSpacing.md))
            // Show one MARK demonstration after the page arrives. The Tome footer teaches "A Unmark" in context.
            val markCard = rememberOnboardingHintDeck(teachMButton = false, markFirst = true)
                .firstOrNull { it.scene == OnboardingHintScene.MARK }
            if (markCard != null) {
                val arrival = LocalOnboardingStepArrival.current
                val arrived by remember(arrival) { derivedStateOf { arrival.value >= 1f } }
                OnboardingHintDemoCard(
                    card = markCard,
                    sceneHeight = onboardingFavoritesHintSceneHeight(onboardingHintViewportHeight()),
                    playing = arrived && interactive,
                )
            }
            Spacer(Modifier.height(MimirSpacing.md))
            Text(
                text = copy.chosenLine,
                style = MimirType.RowSupport,
                color = palette.textMuted,
                textAlign = TextAlign.Center,
            )
            if (identifying != null) {
                Spacer(Modifier.height(MimirSpacing.md))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        color = palette.accentBright,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(MimirSpacing.sm))
                    Text(
                        text = onboardingFavoritesIdentifyingLine(res, identifying),
                        style = MimirType.RowSupport,
                        color = palette.textSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 520.dp),
                    )
                }
            }
            if (identifying == null && identifyingInBackground) {
                Spacer(Modifier.height(MimirSpacing.md))
                Text(
                    text = onboardingFavoritesBackgroundLine(res),
                    style = MimirType.RowSupport,
                    color = palette.textMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 520.dp),
                )
            }
        },
        controls = {
            // This page is the only place in onboarding that stacks a
            // GOLD pill between two neighbours: its focus is the tile ring drawn OUTSIDE its edge
            // ([mimirFocusTileRing]), which reaches 10dp + [MimirFilledControlRingGap] + half of a 7dp
            // stroke = 16.5dp past the pill. With MimirSpacing.sm (8dp) above and below, the ring landed
            // inside the line above it and inside the Continue pill below it, and an overlapped ring
            // reads as a cut one. The clearance has to be the ring's own reach, so it is spelled as
            // that rather than as a spacing token that happens to be big enough today.
            Spacer(Modifier.height(MimirFilledControlRingReach))
            OnboardingPill(
                label = copy.openLabel,
                focusKey = OnboardingFavoritesOpenTomeKey,
                gold = true,
                focused = focusedKey == OnboardingFavoritesOpenTomeKey,
                authority = authority,
                onActivate = onOpenTome,
                interactive = interactive,
            )
            Spacer(Modifier.height(MimirFilledControlRingReach))
            OnboardingPill(
                label = copy.continueLabel,
                focusKey = OnboardingFavoritesContinueKey,
                gold = false,
                focused = focusedKey == OnboardingFavoritesContinueKey,
                authority = authority,
                onActivate = onAdvance,
                interactive = interactive,
            )
        },
    )
}

/**
 * Page two: the Tome. ONE control row, then boxes.
 *
 * The grid is the Tome collection's column geometry held in a CENTRED column of exactly
 * [onboardingFavoritesGridWidthDp] rather than stretched across the device, the way the Tome holds its
 * three wells in a centred 398dp one. Every seat is [OnboardingFavoritesFaceAspect], fixed.
 *
 * **One control row, not two.** the platform rail was a second focusable row, and
 * UP out of the grid landed on whichever chip sat at the focused column — on a rail wider than the screen,
 * regularly one that was not visible. The filter is now a single chip beside the search field that opens a
 * list ([OnboardingFavoritesPlatformDropdown]), matching the Customize Media pattern, and UP out
 * of the grid has exactly one place it can go.
 *
 * No prose and no Continue pill. B closes the page, which is the same gesture that leaves the real Tome
 * library, and it is also what keeps the grid's focus arithmetic honest: with nothing below the last row,
 * DOWN simply stays.
 */
@Composable
private fun OnboardingFavoritesTomeScreen(
    authority: FocusAuthority,
    focusIntent: OnboardingFocusIntentState,
    games: List<GameRow>,
    filter: OnboardingFavoritesGridState,
    onToggleFavorite: (GameRow) -> Unit,
    onClose: () -> Unit,
    /**
     * False while this page is the one fading OUT -- see the router's own comment.
     *
     * The Tome's controls are not [OnboardingPill]s, so most are gated at their callback instead: opening
     * the keyboard or the platform list would leave `filter` holding an overlay open behind a page that has
     * already left, and a toggle would mark a favourite the player never meant to mark. The platform chip
     * is the exception and takes `interactive` itself, because its touch handler moves focus before it
     * calls back -- see [OnboardingFavoritesPlatformFilterChip].
     *
     * It also gates this page's focus LANDING, which is the part a callback gate could never have reached:
     * a departing composition kept re-running its own landFocus and clearing the arriving page's pending
     * target. `onClose` needs no gate -- B is a dispatch path, which reads live state and cannot be
     * delivered to a departing composition at all.
     */
    interactive: Boolean = true,
) {
    val palette = LocalMimirPalette.current
    val focus by authority.state.collectAsState()
    val focusedKey = focus.focused.takeIf { focus.scope == OnboardingScope }
    // Built once per list or filter change and shared with the dispatch ([OnboardingFavoritesGridState.tomeLists]).
    val lists = filter.tomeLists(games)
    val platforms = lists.platforms
    val hasFilter = onboardingFavoritesHasPlatformFilter(platforms)
    val visible = lists.visible
    val keys = lists.keys
    val headKeys = lists.headKeys
    val gridState = rememberLazyGridState()

    // Entry focus is the first GAME, not the first key. The page opens on the boxes it exists to show.
    val entryKey = keys.getOrNull(headKeys.size) ?: OnboardingFavoritesSearchKey

    // QA-16, found on the emulator re-verify 2026-09-16 and carried through both grid rebuilds. The list
    // arrives asynchronously (`produceState` in MimirOnboardingCoordinator), so this page's FIRST
    // composition can have only the control row in [keys]. Focus lands there -- and then never leaves,
    // because [landOnboardingFocusNow]'s first line is `if (pending == null && state.value.focused in
    // validKeys) return true`: once the cells appear the search key is STILL valid, so the landing reports
    // success without moving.
    //
    // Re-assert entry focus ONCE, on the empty -> non-empty transition. The ordinary `landFocus` below
    // still runs, so a page that is empty because the SEARCH matched nothing lands on the field the player
    // is typing into rather than leaving the page unreachable by controller.
    var rowsEverLanded by remember { mutableStateOf(false) }
    // The virtual slots, declared ABOVE the landing effect (RC-50, audit 2026-10-14, O-27b): the effect registers them itself, before it
    // asks for focus. They used to be registered by a second effect declared after it, so when the list first filled the entry key did
    // not exist yet, and an 8-frame retry loop spun until it did: the ring was absent for up to 8 frames after the cells appeared.
    val focusRegistry = remember { OnboardingFavoritesFocusRegistry() }
    DisposableEffect(authority) {
        onDispose { focusRegistry.clear(authority) }
    }
    // `interactive` is both a guard and a KEY here, and it has to be both (reviewed 2026-09-18).
    //
    // As a guard: the controller's B out of the Tome calls requestOnboardingFocus, which defers a target
    // (OnboardingFavoritesOpenTomeKey) and bumps focusIntent.revision. That bump re-fires this effect in the
    // DEPARTING composition too, whose landFocus asks pendingTarget for a target against the TOME key list
    // -- and pendingTarget CLEARS the pending target as a side effect of finding it does not belong to the
    // surface's current keys. The arriving INTRO page then finds nothing pending. Today that still lands
    // correctly, but only because INTRO's own entryFocus happens to be the very key that was wiped; the
    // moment those two differ it misfires silently. A departing page has no business landing focus at all.
    //
    // As a key: this effect's bootstrap asks for the entry key; keying on `interactive` cancels the
    // retry the moment the page starts leaving, instead of letting it keep dragging focus back to a Tome
    // the player is walking away from.
    LaunchedEffect(focusIntent.revision, visible.isEmpty(), interactive) {
        if (!interactive) return@LaunchedEffect
        if (visible.isNotEmpty() && !rowsEverLanded) {
            rowsEverLanded = true
            focusIntent.retain(OnboardingFocusSurface.FAVORITES)
            // The rows' slots first, so the entry key exists, and focus lands on this frame rather than a frame beat later.
            focusRegistry.update(authority, onboardingFavoritesFocusSlots(visible, filter.columns))
            authority.requestFocus(entryKey)
            if (authority.state.value.focused == entryKey) return@LaunchedEffect
            // Not seated yet (a scope still settling): a short retry stands in for the old 8-frame loop.
            repeat(OnboardingFavoritesEntryRetryFrames) {
                withFrameNanos { }
                authority.requestFocus(entryKey)
                if (authority.state.value.focused == entryKey) return@LaunchedEffect
            }
        }
        // While an overlay owns input its own keys are the valid set, so the page must not fight it.
        if (!filter.keyboardOpen && !filter.filterOpen) {
            authority.landFocus(
                focusIntent = focusIntent,
                surface = OnboardingFocusSurface.FAVORITES,
                entryFocus = entryKey,
                validKeys = keys,
            )
        }
    }

    // Every visible game keeps a focus slot whether or not its cell is composed. Without this, UP out of
    // the top composed row asks the authority for a key it no longer holds and nothing happens at all --
    // see [onboardingFavoritesFocusSlots]. (The registry itself is declared above the landing effect.)
    // Keyed on the KEYS, which stay the same instance while the visible games do: a star or a retitle no longer
    // re-registers 5,500 slots on the UI thread. A slot's bounds depend only on its index
    // and the column count, so the same keys always mean the same slots.
    LaunchedEffect(authority, keys, filter.columns) {
        focusRegistry.update(authority, onboardingFavoritesFocusSlots(visible, filter.columns))
    }

    // OS Back closes the Tome rather than the flow, matching the B button's routed path. Disabled while an
    // overlay is up so it closes the overlay first, exactly as PROFILE's sub-modals do.
    BackHandler(enabled = !filter.keyboardOpen && !filter.filterOpen, onBack = onClose)

    //  [mimirGlassBackdrop] provides the shared treatment -- it owns the API-31 capability check AND drops the modifier entirely at zero, which
    // matters because a `blur` node forces this whole subtree through an offscreen pass even at radius 0.
    // Ramped rather than switched: snapping sharp-to-18dp in one frame reads as the scene being swapped
    // rather than as a list arriving in front of it.
    //
    // On the BACKGROUND, not on the content column inside it: [MimirShelfBackground] paints the atmosphere
    // -- starfield, clouds, gradient -- and blurring the boxes while leaving pinpoint stars sharp in front of
    // them reads as a rendering fault rather than as glass. Since walk-32d the sky is the step's
    // ([OnboardingFavoritesScreen]), so the glass is applied there, over this page and the sky together. It
    // costs nothing when the list is shut: [mimirGlassBackdrop] drops the modifier entirely at zero, so the
    // atmosphere's draw-once caching (which exists for the OLED handheld's battery) is untouched for all the
    // time no list is open.
    Box(Modifier.fillMaxSize()) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = MimirSpacing.lg),
        ) {
            // ONE source of truth for the column count, hoisted above the page so everything that depends on
            // it reads the same number: the grid's GridCells.Fixed, the control row's width, and
            // onboardingFavoritesFocusDestination in the dispatch (see that function's KDoc).
            val columns = onboardingFavoritesColumns(maxWidth.value)
            // Tell the dispatch what this actually laid out, so UP/DOWN move by the same stride the eye does.
            // See [OnboardingFavoritesGridState.columns].
            LaunchedEffect(columns) { filter.measured(columns) }
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(MimirSpacing.sm))
                // Customize Media's top band, built the way that band is built.
                //
                // A device report on 2026-09-18 found missing filter/search focus and incorrect confirm behavior. One defect caused all of it. The
                // metallic chip's label is a [MimirAnimatedChromeText], whose inner `Text` is `fillMaxWidth`.
                // Sitting UNWEIGHTED in this Row, the chip was measured first, against the whole row, and filled
                // it -- so the weighted search field got zero width. A zero-sized node never registers its key
                // ([mimirFocusable]), so the field never drew and UP from the top row asked for a key the authority
                // did not hold. The dispatch then kept acting on that refused request while the ring stayed on the
                // game: the next DOWN was spent getting back to where the ring already was, and A opened the
                // keyboard instead of marking.
                //
                // `EnrichFilterRow` never had this problem, for the two reasons copied here. Its chips sit in a
                // WEIGHTED slot, so the field's share is fixed before any chip is measured. And they sit inside a
                // `horizontalScroll`, whose unbounded width leaves that inner fill nothing to fill, so the chip is
                // as wide as its words.
                //
                // And the row is as wide as the boxes under it, so the field starts where the first box starts
                // and the two read as one block rather than as a bar floating over a narrower grid.
                Row(
                    modifier = Modifier.width(onboardingFavoritesHeadBandWidthDp(columns).dp),
                    horizontalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OnboardingFavoritesSearchField(
                        query = filter.query,
                        focused = focusedKey == OnboardingFavoritesSearchKey,
                        authority = authority,
                        onOpen = { from -> if (interactive) filter.openKeyboard(from = from) },
                        // Alone, a single weighted child takes the whole row, so a one-platform library still
                        // gets a full-width field.
                        modifier = Modifier.weight(OnboardingFavoritesHeadBandWeights.search),
                    )
                    if (hasFilter) {
                        Row(
                            modifier = Modifier
                                .weight(OnboardingFavoritesHeadBandWeights.filters)
                                .horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OnboardingFavoritesPlatformFilterChip(
                                platform = filter.platform,
                                focused = focusedKey == OnboardingFavoritesPlatformFilterKey,
                                authority = authority,
                                onOpen = { from -> filter.openFilter(from = from) },
                                interactive = interactive,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(MimirSpacing.sm))
                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    // Whole rows only -- see [onboardingFavoritesRows]. What this box cannot fit as a whole
                    // row stays empty below the grid rather than showing the top of the next one -- or, on
                    // compact, is split above and below it ([onboardingFavoritesGridTopOffsetDp]).
                    val rows = onboardingFavoritesRows(maxHeight.value)
                    val gridTopOffsetDp = onboardingFavoritesGridTopOffsetDp(
                        boxHeightDp = maxHeight.value,
                        rows = rows,
                        compact = LocalMimirViewportProfile.current.let { it.widthDp > 0f && it.isCompact },
                    )

                    // ONE scroll authority for this grid. The cells pass `autoBringIntoView = false` precisely
                    // so this is the only thing that moves it: with both running, `bringIntoView` and this
                    // effect take the same scroll mutex and whichever starts later cancels the other, which is
                    // why the grid used to jump a full row while the ring appeared to stay still.
                    //
                    // The focused row sits
                    // in the middle band and the view moves under it, edges excepted
                    // ([onboardingFavoritesTopRow]). Two versions preceded it: one that scrolled only when the
                    // focused box was already cut off, and left the rows at both edges cut off in the meantime,
                    // and one that seated the focused row at the top, where the ring never left the first row.
                    // Stepping between two rows of the same band computes the same target, so the effect below
                    // returns early and only the ring moves -- the view holds still, which is the point.
                    //
                    // An offset of 0 seats a row exactly one ring clearance below the top edge, because that
                    // is the grid's top content padding. Keyed on the list and the geometry as well as the
                    // key: a search or a platform change can move the focused box to another row without
                    // moving focus.
                    LaunchedEffect(focusedKey, keys, columns, rows) {
                        val index = lists.visibleIndexOf(focusedKey)
                        if (index < 0) return@LaunchedEffect
                        val target = onboardingFavoritesTopRow(index, visible.size, columns, rows) * columns
                        if (gridState.firstVisibleItemIndex == target &&
                            gridState.firstVisibleItemScrollOffset == 0
                        ) {
                            return@LaunchedEffect
                        }
                        runCatching { gridState.animateScrollToItem(target) }
                    }

                    if (visible.isEmpty() && filter.query.isNotBlank()) {
                        // A search that matches nothing says so. Silence here reads as a broken grid, which is
                        // the exact complaint this rebuild answers.
                        Text(
                            text = stringResource(R.string.app_onboarding_favorites_no_match),
                            style = MimirType.RowSupport,
                            color = palette.textMuted,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = MimirSpacing.xl),
                        )
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(columns),
                            state = gridState,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = gridTopOffsetDp.dp)
                                .width(onboardingFavoritesGridViewportWidthDp(columns).dp)
                                .height(onboardingFavoritesGridViewportHeightDp(rows).dp),
                            horizontalArrangement = Arrangement.spacedBy(OnboardingFavoritesGridGapDp.dp),
                            verticalArrangement = Arrangement.spacedBy(OnboardingFavoritesGridGapDp.dp),
                            // [mimirFocusTileRing] draws OUTSIDE its cell -- up to a 10dp outset -- and a
                            // LazyVerticalGrid clips its viewport, so without clearance the ring is sheared
                            // off the first and last rows and the edge columns. The Folio reserves exactly
                            // this for the same ring (HomeTomeFolio's `ringClearance = 14.dp`).
                            //
                            // The bottom is a ring clearance too, not the 16dp it used to be.
                            // [onboardingFavoritesGridViewportHeightDp] counts exactly these two, so content minus
                            // viewport is a whole number of rows, and the grid's own end -- where a touch drag
                            // stops -- is the very position the page seats at the end. With 16dp it sat 2dp past
                            // it: nothing cut, but the next D-pad press would nudge the view back by those 2dp.
                            contentPadding = PaddingValues(all = OnboardingFavoritesRingClearanceDp.dp),
                        ) {
                            items(visible, key = { it.gameId.value }) { row ->
                                val key = onboardingFavoriteGameKey(row.gameId.value)
                                OnboardingFavoritesCell(
                                    row = row,
                                    focused = focusedKey == key,
                                    authority = authority,
                                    onToggle = { if (interactive) onToggleFavorite(row) },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(MimirSpacing.sm))
                val tomeHint = onboardingFavoritesTomeHint(
                    onboardingResources(),
                    focused = focusedKey,
                    filterKeyPresent = hasFilter,
                    hasGames = visible.isNotEmpty(),
                    focusedMarked = lists.visibleGame(focusedKey)?.isFavorite == true,
                )
                // "A Mark", "A Unmark", "A Search" glide out, then in, as the first surface's hint does.
                MimirHintSwap(
                    key = tomeHint,
                    glide = MimirMotion.GlideTravelDp.dp,
                    durationMs = OnboardingMotionMs,
                ) {
                    Text(
                        text = tomeHint,
                        style = MimirType.Pill,
                        color = palette.textMuted,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(MimirSpacing.sm))
            }
        }
    }
}

/**
 * The Tome page's search field: `EnrichSearchRow`'s control, opening the shared [MimirKeyboard].
 *
 * Not a Compose `TextField`. This is a controller product and the routed-input authority owns focus, so
 * text arrives the way it arrives everywhere else in Mimir -- through [MimirKeyboard], which the D-pad
 * drives (`dispatchMimirKeyboardInput`: directions walk the keys, CONFIRM types one, L2 shifts, R2 cycles
 * the character set). A real text field would want IME focus this app never gives away.
 *
 * **[mimirFocusRing] goes BEFORE [clip], not after.**  The ring scales its element by 3% and draws a border at the new edge; with the clip
 * ahead of it in the chain that border is drawn into an already-clipped region and its outer edge is cut
 * off, so focusing the field visibly shaved it. `EnrichSearchRow` -- the same control on the Customize
 * Media screen, which does not have this problem -- orders it exactly this way.
 */
@Composable
private fun OnboardingFavoritesSearchField(
    query: String,
    focused: Boolean,
    authority: FocusAuthority,
    onOpen: (FocusKey?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalMimirPalette.current
    val shape = RoundedCornerShape(MimirSpacing.radiusCard)
    val typed = query.isNotBlank()
    val textScroll = rememberScrollState()
    Row(
        modifier = modifier
            .heightIn(min = MimirSpacing.minTarget)
            .mimirFocusable(authority = authority, key = OnboardingFavoritesSearchKey, scope = OnboardingScope)
            // The one ring, as the customize-media search field wears it: a row ring painting the field's glass.
            .mimirSelectionRing(
                focused = focused,
                identity = OnboardingFavoritesSearchKey,
                shape = MimirRingShape.Row(MimirSpacing.radiusCard),
                fill = SolidColor(
                    homeCustomEnrichGlassControlFill(
                        palette,
                        lightAlpha = EnrichSearchFieldLightAlpha,
                        darkAlpha = EnrichSearchFieldDarkAlpha,
                    ),
                ),
            )
            .clip(shape)
            // The key focus was standing on BEFORE this tap, so the page can come back to it. Read here
            // rather than in the caller because the tap is the only moment it still exists.
            .mimirActivatable(label = stringResource(R.string.app_common_search)) {
                onOpen(authority.state.value.focused)
            }
            .padding(horizontal = MimirSpacing.md, vertical = MimirSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "\u16cb",
            style = MimirTypeScale.RuneAction,
            color = palette.runeMark,
        )
        // The typed text is the PLAYER's, so it is never uppercased the way chrome labels are, and it
        // SCROLLS rather than truncating -- a filter you cannot read the end of is a filter you cannot
        // correct.
        Text(
            text = if (typed) query else stringResource(R.string.app_common_search),
            style = if (typed) {
                MimirTypeScale.Support
            } else {
                MimirTypeScale.MenuActionCompact.copy(fontSize = 10.sp)
            },
            color = palette.textPrimary,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .padding(start = MimirSpacing.sm)
                .weight(1f)
                .horizontalScroll(textScroll),
        )
    }
}

/**
 * The closed platform filter: metallic `PLATFORM · value`, drawn exactly as `EnrichFilterRow` draws it.
 *
 *  What shipped before was an accent-tinted pill with [MimirType.Pill] lettering -- a
 * dialect this product does not speak for filters anywhere else.
 *
 * **No background, no clip, no ring.** The focus treatment IS [enrichFilterTextFocus]'s radial accent
 * ellipse plus the 0.82 -> 1 alpha lift, which is the whole treatment on the reference surface. A chip
 * drawn around metallic lettering is the specific thing that made this look foreign; the reference row
 * has no chip either, and its own comment says the glow exists so the label stays legible "without making
 * a chip or box".
 *
 * The selected platform no longer turns the label accent-coloured. It does not need to: the value IS the
 * state ("Platform · Super Nintendo" against "Platform · All"), which is how the reference reads too.
 *
 * Touch publishes focus BEFORE opening, for the reason the reference gives at its own call site: without
 * it a single tap on an unfocused filter opens the list while the authority still believes focus is on
 * whatever was focused before, and the first press afterwards acts from there.
 */
@Composable
private fun OnboardingFavoritesPlatformFilterChip(
    platform: PlatformId?,
    focused: Boolean,
    authority: FocusAuthority,
    onOpen: (FocusKey?) -> Unit,
    /**
     * False while the Tome page is the one fading OUT.
     *
     * This chip is the one favourites control whose touch handler does something the parent cannot gate by
     * wrapping the callback: it moves `authority.state.focused` ITSELF before calling `onOpen`. A gated
     * `onOpen` therefore stopped the filter from opening but left the focus move intact, so a stray touch on
     * the departing chip parked focus on a key the arriving INTRO page does not have -- and neither of
     * INTRO's pills would paint as focused until some later input corrected it (reviewed 2026-09-18).
     *
     * Gated inside the lambda rather than by stripping the whole `clickable` the way [OnboardingPill] does:
     * the pill strips it because a departing pill must not REGISTER its shared key at all, which is a
     * different hazard. Here the registration is harmless -- the two pages own disjoint keys -- and only the
     * action needs stopping.
     */
    interactive: Boolean = true,
) {
    val palette = LocalMimirPalette.current
    val metalBrush = remember(palette) { platformConsoleIconBrush(palette) }
    Row(
        modifier = Modifier
            .heightIn(min = MimirSpacing.minTarget)
            .mimirFocusable(
                authority = authority,
                key = OnboardingFavoritesPlatformFilterKey,
                scope = OnboardingScope,
                autoBringIntoView = false,
            )
            .graphicsLayer { alpha = if (focused) 1f else 0.82f }
            // The metallic treatment stays; only the second focus node goes.
            .mimirActivatable(label = stringResource(R.string.app_onboarding_favorites_filter_label)) {
                if (interactive) {
                    // BEFORE the focus move below, which is the thing that destroys the record.
                    val cameFrom = authority.state.value.focused
                    if (!focused) authority.requestFocus(OnboardingFavoritesPlatformFilterKey)
                    onOpen(cameFrom)
                }
            }
            .padding(horizontal = MimirSpacing.sm, vertical = MimirSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MimirAnimatedChromeText(
            intent = onboardingFavoritesPlatformFilterIntent(onboardingResources(), platform),
            modifier = Modifier.enrichFilterTextFocus(focused, palette),
            color = Color.Unspecified,
            style = MimirTypeScale.MenuActionCompact.copy(brush = metalBrush, fontSize = 10.sp),
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * The OPEN platform list, in `EnrichFilterDropdown`'s material.
 *
 * The list matches the blurred material of the reference filter dropdown: a hero-gradient slab at 0.96 alpha, a hairline separator between options, metallic option
 * text, and a small metal dot marking the focused row instead of a ring around it. The previous version
 * was a flat `surfaceRaised` card with a [mimirFocusRing] per option and a Mannaz pin for the selection.
 *
 * The scrim is [mimirGlassScrimColor], not a hand-picked alpha, because it is the one value in the
 * product that knows whether the scene behind it actually got blurred: 0.52 on API 31+ where the blur
 * does the separating, 0.74 on API 30 where the scrim has to do all of it alone. A literal tuned against
 * a blurred emulator would leave Android 11 with a sharp, barely-veiled page behind the list.
 *
 * Renders INLINE (never a Compose `Dialog`, which would miss the app's routed controller input), and
 * while it is up [dispatchOnboarding] gives it every press -- the same gate the keyboard layer gets.
 *
 * **It scrolls, where the reference does not.** The reference lists a handful of fixed values; this one
 * lists every platform in the library, and the Thor's ROM folders cover twenty. At the Thor's size it
 * shows nine rows. Held to the screen's height, the list measured every row past the bottom edge at zero
 * height, and [mimirFocusable] never registers a zero-sized node: DOWN stopped at the last row on screen,
 * and PlayStation 3 and Xbox 360 could be neither seen nor picked (emulator, preparing run 13). Each row
 * now asks the scroll to reveal it when it takes focus, the way long Settings lists do.
 */
@Composable
private fun OnboardingFavoritesPlatformDropdown(
    /** False while the list fades out: it registers nothing with the authority, takes no Back and no touch. */
    open: Boolean,
    authority: FocusAuthority,
    focusIntent: OnboardingFocusIntentState,
    platforms: List<PlatformId>,
    selected: PlatformId?,
    focusedKey: FocusKey?,
    onDismiss: () -> Unit,
    onPick: (PlatformId?) -> Unit,
) {
    // Touch closes must land focus exactly where the controller's close lands it. Without this the
    // focused option row disposes while focused and the authority's geometric fallback drops focus onto
    // whatever OnboardingScope element sits nearest the disposed row -- usually a box in the middle of
    // the grid. A valid key in the wrong place, and the next controller press acts from there.
    val closeOnTo: (FocusKey) -> Unit = { key ->
        focusIntent.clear(OnboardingFocusSurface.FAVORITES)
        requestOnboardingFocus(authority, focusIntent, OnboardingFocusSurface.FAVORITES, key)
    }
    val palette = LocalMimirPalette.current
    val hero = palette.materials.hero
    val metalBrush = remember(palette) { platformConsoleIconBrush(palette) }
    val scrim = mimirGlassScrimColor()
    val options = onboardingFavoritesPlatformOptions(platforms)
    val entryKey = onboardingFavoritesPlatformOptionKey(options.indexOf(selected).coerceAtLeast(0))
    val optionKeys = options.indices.map(::onboardingFavoritesPlatformOptionKey)

    LaunchedEffect(focusIntent.revision, options.size, open) {
        // A leaving list lands nothing: the page under it owns the focus again.
        if (!open) return@LaunchedEffect
        authority.landFocus(
            focusIntent = focusIntent,
            surface = OnboardingFocusSurface.FAVORITES,
            entryFocus = entryKey,
            validKeys = optionKeys,
        )
    }
    BackHandler(enabled = open, onBack = onDismiss)

    Box(
        Modifier
            .fillMaxSize()
            .background(scrim)
            .then(
                if (open) {
                    Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        onDismiss()
                        closeOnTo(OnboardingFavoritesPlatformFilterKey)
                    }
                } else {
                    Modifier.onboardingHandoverTouchShield()
                },
            )
            // Inside the scrim's tap target, so a tap in this margin still closes the list.
            .padding(vertical = MimirSpacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 160.dp, max = 280.dp)
                .clip(RoundedCornerShape(MimirSpacing.radiusCard))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            hero.coverTop.copy(alpha = 0.96f),
                            hero.coverMiddle.copy(alpha = 0.96f),
                            hero.coverBottom.copy(alpha = 0.96f),
                        ),
                    ),
                )
                // After the slab, so the slab stays put and only the rows move.
                .verticalScroll(rememberScrollState())
                .padding(MimirSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            options.forEachIndexed { index, option ->
                val key = onboardingFavoritesPlatformOptionKey(index)
                val focused = open && focusedKey == key
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = MimirSpacing.minTarget)
                        .then(if (open) Modifier.mimirFocusable(authority, key, OnboardingScope) else Modifier)
                        .graphicsLayer { alpha = if (focused) 1f else 0.82f }
                        .drawBehind {
                            if (index > 0) {
                                drawLine(
                                    color = palette.materials.separator.core.copy(alpha = 0.46f),
                                    start = Offset(0f, 0f),
                                    end = Offset(size.width, 0f),
                                    strokeWidth = 1.dp.toPx(),
                                )
                            }
                            if (focused) {
                                drawCircle(
                                    brush = metalBrush,
                                    radius = 2.dp.toPx(),
                                    center = Offset(MimirSpacing.md.toPx() / 2f, size.height / 2f),
                                )
                            }
                        }
                        .mimirActivatable(label = stringResource(R.string.app_onboarding_favorites_filter_choose)) {
                            onPick(option)
                            closeOnTo(OnboardingFavoritesPlatformFilterKey)
                        }
                        .padding(horizontal = MimirSpacing.md, vertical = MimirSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The weight goes on the BOX, not the text: [enrichFilterTextFocus] sizes its ellipse
                    // from the element it decorates, so hanging it off a full-width weighted node would
                    // stretch the glow across the whole row instead of hugging the word.
                    Box(Modifier.weight(1f)) {
                        MimirIntentText(
                            intent = MimirTextIntent.Authored(onboardingFavoritesPlatformLabel(onboardingResources(), option)),
                            modifier = Modifier.enrichFilterTextFocus(focused, palette),
                            style = MimirTypeScale.MenuActionCompact.copy(
                                brush = metalBrush,
                                fontSize = 11.sp,
                            ),
                            color = Color.Unspecified,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    if (option == selected) {
                        // The reference's own selected marker, rune and all -- a Mannaz, but as chrome
                        // text rather than [MimirMannazPin]. The pin is the mark for a game that IS
                        // favourited; this is the mark for the row a list is currently sitting on.
                        MimirIntentText(
                            intent = MimirTextIntent.StaticChrome(
                                stringResource(R.string.app_onboarding_favorites_filter_selected),
                                visualOverride = "ᛗ",
                            ),
                            style = MimirTypeScale.MicroStatus,
                            color = palette.textSecondary.copy(alpha = 0.72f),
                        )
                    }
                }
            }
        }
        // Leaving: a sibling laid OVER the rows takes every touch, so an option cannot be picked through its own fade.
        if (!open) Box(Modifier.matchParentSize().onboardingHandoverTouchShield())
    }
}

/**
 * One box in the grid, seated and painted the way the Tome Folio seats and paints a game.
 *
 * The cell follows the Tome collection's fitted art treatment with no resting border. Three differences
 * in the earlier implementation prevented the two surfaces from matching.
 *
 * **The border was [mimirFocusRing].** That modifier paints a RESTING stroke on every element it is
 * attached to, not only the focused one -- `ringColor = if (focused) palette.focusRing else palette.outline`
 * with a hairline width -- so a grid of twenty boxes drew twenty outlines. It is the right component for a
 * pill or a chip, which want a visible edge at rest. It is the wrong one for art. The Folio never puts it
 * on a cell: it uses [mimirFocusTileRing], which draws NOTHING at progress zero and only blooms on the
 * focused tile. Same here now.
 *
 * **The scale was Crop.** The Folio paints `ContentScale.Fit` for every source that is not exactly square,
 * and so does the Customize Media gallery. Nothing in this tree crops box art -- a cropped cover loses its
 * own edges, which on a shelf of scanned boxes is the first thing the eye catches.
 *
 * **The corner was [MimirSpacing.radiusCard] (14dp)** where art in this product is [MimirBoxArtCorner]
 * (10dp). One symbol for art corners; a second literal beside it is how two surfaces drift.
 *
 * Tap is `pointerInput` + `semantics` rather than `clickable`, matching the Folio: `clickable` installs a
 * Compose focus target beside the one [mimirFocusable] registers with the authority, and this product has
 * exactly one focus authority.
 *
 * The cover RESOLUTION is unchanged (`homeShelfCoverUri`), which is what carries the step's promise: the
 * player picks a box here and lands on a Home shelf showing that identical art. A null cover is "the art
 * has not arrived yet" -- the fetch is usually still running when this step is reached.
 */
@Composable
internal fun OnboardingFavoritesCell(
    row: GameRow,
    focused: Boolean,
    authority: FocusAuthority,
    onToggle: () -> Unit,
) {
    val palette = LocalMimirPalette.current
    val cover = com.gruning.mimir.rewrite.feature.library.homeShelfCoverUri(row.platformId, row.artUri, row.flatArtUri)
    // Decided once per cell, not re-keyed on `cover`: art usually arrives while this step is up (see above),
    // and a marked pin must not jump corners the moment it does. See [onboardingFavoritePinAlignment].
    val pinAlignment = remember(row.gameId.value) { onboardingFavoritePinAlignment(hasCover = cover != null) }
    val markDescription = onboardingFavoriteCellStateDescription(onboardingResources(), row.isFavorite)
    val ringReducedMotion = mimirReducedMotion()
    val ringProgress by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = onboardingFocusRingSpec(ringReducedMotion),
        label = "onboardingFavoriteRing",
    )
    // Home's settle gleam (SPEC slice 5): a traverse re-keys faster than the 160 ms settle, so only the box
    // you stop on gleams. It is a game, so it gets Home's game gleam.
    val ringGlint = rememberMimirFocusGameGleam(if (focused) row.gameId.value else null)
    Box(
        modifier = Modifier
            .aspectRatio(OnboardingFavoritesFaceAspect)
            .mimirFocusable(
                authority = authority,
                key = onboardingFavoriteGameKey(row.gameId.value),
                scope = OnboardingScope,
                // The screen owns the scroll; see its LaunchedEffect(focusedKey). Two scroll systems on
                // one lazy grid cancel each other nondeterministically.
                autoBringIntoView = false,
            )
            // BEFORE the clip, because the ring is deliberately drawn outside the cell's own bounds.
            .mimirFocusTileRing(
                progress = { ringProgress },
                color = palette.tileFocusRing,
                cornerRadius = MimirBoxArtCorner,
                glint = ringGlint,
            )
            .clip(RoundedCornerShape(MimirBoxArtCorner))
            .pointerInput(row.gameId.value) { detectTapGestures { onToggle() } }
            .semantics(mergeDescendants = true) {
                stateDescription = markDescription
            },
    ) {
        if (cover != null) {
            MimirBoxArt(
                model = cover,
                title = row.title,
                focused = focused,
                contentScale = ContentScale.Fit,
            )
        } else {
            // The book's embossed Mannaz yields to the pin below, which is one of its own: one ᛗ per book .
            OnboardingFavoritesScaledBook(title = row.title, palette = palette, showMannaz = !row.isFavorite)
        }
        // The chosen mark, and the SAME component the Tome library draws on a pinned tome.
        //
        // Composed unconditionally rather than inside `if (row.isFavorite)`: [MimirMannazPin] animates its
        // own presence, and a mark that is only in the tree while present can appear but never fade.
        // Taking a game OFF the shelf is exactly as much of an answer as putting one on.
        MimirMannazPin(
            present = row.isFavorite,
            modifier = Modifier.align(pinAlignment).padding(MimirSpacing.xs),
            height = 16.dp,
            // The reference for this transition is the pin marker's own 110ms clock. Passed EXPLICITLY, for the reason the Tome cell passes it,
            // and 0 under reduced motion.
            durationMs = onboardingFavoritePinDurationMs(ringReducedMotion),
            // A GAME's mark: starring one plays a focused game's gleam on the rune. A cell born starred stays still.
            sparkleOnMark = true,
        )
    }
}

/**
 * Where a favourites cell wears its Mannaz pin. Art keeps the Tome library's top-right Mannaz favorite mark. The no-art book prints its title from the top
 * edge, and on a 75 dp face the top-right pin sat on the first title line ("House of the|Dead", compact review
 * 2026-09-27, ONB-3), so it moves to the bottom-right corner. The book's embossed Mannaz is not drawn while the pin is: the pin is a
 * Mannaz of its own, one ᛗ per book. The face is 75 dp on every device, so this is not a compact-only change.
 */
internal fun onboardingFavoritePinAlignment(hasCover: Boolean): Alignment =
    if (hasCover) Alignment.TopEnd else Alignment.BottomEnd

/**
 * What a screen reader says a favourites cell's state is. The Mannaz pin is the only visible mark, and it
 * fades in on a 110 ms clock; the HIG asks that motion never be the only carrier of information, so the
 * cell also says it in words (2026-09-28).
 */
internal fun onboardingFavoriteCellStateDescription(res: Resources, isFavorite: Boolean): String =
    res.getString(
        if (isFavorite) R.string.app_onboarding_favorites_cell_marked else R.string.app_onboarding_favorites_cell_not_marked,
    )

/**
 * The no-art book, drawn at the Tome collection's face width and scaled down to this grid's.
 *
 * [MediaArtFallbackGlyph]'s book scales its title with its width but floors it at 12sp. That is right on
 * every surface it was tuned for and wrong on a 75dp face: the words run out of room, "Hedgehog" breaks
 * mid-word, and long titles run into the embossed Mannaz (observed on an emulator at this smaller face size). Drawing the book at the collection's own [HomeTomeLibraryCompactMaxFaceWidthDp] and
 * scaling the whole of it preserves the collection's book treatment at the smaller size. A second, compact book design would be a new thing to keep in step.
 *
 * A face at least that wide gets the book as it is, since scaling up would only blur it.
 */
@Composable
private fun OnboardingFavoritesScaledBook(title: String, palette: MimirThemePalette, showMannaz: Boolean = true) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val designWidthDp = HomeTomeLibraryCompactMaxFaceWidthDp
        if (maxWidth.value >= designWidthDp) {
            MediaArtFallbackGlyph(title, palette = palette, showTitle = true, fitTitle = true, showMannaz = showMannaz)
            return@BoxWithConstraints
        }
        val scale = maxWidth.value / designWidthDp
        Box(
            modifier = Modifier
                .requiredSize(
                    width = designWidthDp.dp,
                    height = (designWidthDp / OnboardingFavoritesFaceAspect).dp,
                )
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
        ) {
            MediaArtFallbackGlyph(title, palette = palette, showTitle = true, fitTitle = true, showMannaz = showMannaz)
        }
    }
}

@Composable
private fun OnboardingThemeScreen(
    authority: FocusAuthority,
    focusIntent: OnboardingFocusIntentState,
    currentThemeId: MimirThemeId,
    onApplyTheme: (MimirThemeId) -> Unit,
    onAdvance: () -> Unit,
    /** True while the step has moved on and this gallery glides out: a pick or a Back then does nothing. */
    departing: Boolean = false,
) {
    LaunchedEffect(focusIntent.revision) {
        authority.replayDeferredThemeActions(
            focusIntent = focusIntent,
            onApplyTheme = onApplyTheme,
            onAdvance = onAdvance,
        )
    }
    // Nova device report, 2026-09-30: THEME was the one
    // step that did not glide. A pick cut the gallery away in one frame, and the next step (the favourites, or FINISH
    // when the library is empty) faded in over an empty sky. The gallery reads the step clocks now, as a scaffold's
    // body does -- out toward the leading edge while the next step comes in from the trailing one, on the one cross-fade
    // clock (2026-10-15) -- over the same sky, so the other steps' glide shows here too. The step owns its presence, so the
    // gallery's own entrance fade stays off. The pick's new palette applies at once and the gallery glides out in it.
    // The step's touch shield and the dispatch drop presses while it departs; its own Back handler outlives the
    // pick, so a pick or a Back does nothing then.
    val arrival = LocalOnboardingStepArrival.current
    val handover = LocalOnboardingStepHandover.current
    OnboardingSky(draws = true) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = onboardingStepAlpha(arrival.value, handover.departure.value)
                    translationX = onboardingStepTravelPx(
                        arrival.value,
                        handover.departure.value,
                        handover.arrivalTravels,
                        MimirMotion.GlideTravelDp.dp.toPx(),
                    )
                },
        ) {
            ThemePreviewGallery(
                authority = authority,
                scope = OnboardingScope,
                currentThemeId = currentThemeId,
                title = stringResource(R.string.app_onboarding_theme_title),
                subtitle = stringResource(R.string.app_onboarding_theme_subtitle),
                dismissHint = stringResource(R.string.app_onboarding_theme_keep_current),
                onPick = { id ->
                    if (!departing) {
                        onApplyTheme(id)
                        onAdvance()
                    }
                },
                onDismiss = { if (!departing) onAdvance() }, // keep the current theme, move on
                presenceManagedExternally = true,
            )
        }
    }
}

/**
 * DONE: one FINISH page  -- the ✓ eyebrow, "You're all
 * set.", one line, and a glass button legend in Home's footer grammar. It replaces the four-page tail
 * (DEVICE, MEDIA, TOMES, FINISH), whose three teaching pages repeated what ACCESS, METADATA and FAVORITES had
 * just taught.
 *
 * The legend is the one glass surface on this page, and glass here is Apple's "functional layer for controls":
 * it names the buttons the player is about to use. It enters once with [mimirGlassPanelEntry] (alpha and the
 * house 0.96 lift) on [onboardingAccessMotionSpec]'s clock, which is 0 ms under reduced motion. No backdrop
 * blur: the panel paints its own material.
 *
 * `onFinish` / [OnboardingDoneKey] / [OnboardingFocusSurface.DONE] / the completion gate are exactly as they
 * were. Back is swallowed by the root handler: there is no page before this one to return to.
 */
@Composable
private fun OnboardingDoneScreen(
    authority: FocusAuthority,
    focusIntent: OnboardingFocusIntentState,
    onFinish: () -> Unit,
    teachMButton: Boolean = true,
    offerHomeRow: Boolean = false,
) {
    val palette = LocalMimirPalette.current
    val focus by authority.state.collectAsState()
    val focusReveal = remember { OnboardingFocusRevealState() }

    LaunchedEffect(focusIntent.revision) {
        authority.landFocus(
            focusIntent = focusIntent,
            surface = OnboardingFocusSurface.DONE,
            entryFocus = OnboardingDoneKey,
            validKeys = listOf(OnboardingDoneKey),
        )
    }

    val focusedKey = focus.focused.takeIf { focus.scope == OnboardingScope }

    OnboardingScaffold(
        authorityFocusedKey = focusedKey,
        focusReveal = focusReveal,
        header = { OnboardingHeaderStrip(palette, OnboardingStep.DONE) },
        content = {
            OnboardingFinishBody(palette = palette, teachMButton = teachMButton, offerHomeRow = offerHomeRow)
        },
        controls = {
            Spacer(Modifier.height(MimirSpacing.lg))
            OnboardingPill(
                label = stringResource(R.string.app_onboarding_finish_enter),
                focusKey = OnboardingDoneKey,
                gold = true,
                focused = focusedKey == OnboardingDoneKey,
                authority = authority,
                onActivate = onFinish,
            )
        },
    )
}

/** FINISH's body: the ✓ eyebrow, the title, the glass button list, and the home-screen line when it is owed. */
@Composable
private fun OnboardingFinishBody(
    palette: MimirThemePalette,
    teachMButton: Boolean,
    offerHomeRow: Boolean,
) {
    val res = onboardingResources()
    val copy = remember(res) { onboardingFinishCopy(res) }
    // Not `reducedMotion`: OnboardingAccessMotionTest counts AnimatedContent hosts by that name.
    val legendReducedMotion = mimirReducedMotion()
    val legendProgress = remember { Animatable(if (legendReducedMotion) 1f else 0f) }
    val arrival = LocalOnboardingStepArrival.current
    LaunchedEffect(Unit) {
        // After the step has arrived, not during it: one fade at a time over the same pixels.
        snapshotFlow { arrival.value }.first { it >= 1f }
        withFrameNanos { }
        legendProgress.animateTo(1f, onboardingAccessMotionSpec(legendReducedMotion))
    }
    val labels = LocalMimirControllerHintLabels.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        OnboardingFinishEyebrow(palette)
        Spacer(Modifier.height(MimirSpacing.sm))
        MimirChromeText(
            text = copy.title,
            style = MimirType.TitleCaps,
            color = palette.textPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(MimirSpacing.md))
        // Only as wide as the list, centred; one flat tone, because rows are peers .
        MimirLiquidGlassPanel(
            modifier = Modifier.mimirGlassPanelEntry { legendProgress.value },
            contentPadding = MimirSpacing.md,
            verticalArrangement = Arrangement.spacedBy(MimirSpacing.xs),
            gradientBody = false,
        ) {
            OnboardingFinishLegendList(
                entries = remember(res, teachMButton, labels) { onboardingFinishLegend(res, teachMButton, labels) },
                palette = palette,
            )
        }
        if (offerHomeRow) {
            // Not a control, so not on the glass: a muted note under it, entering on the list's own beat.
            Spacer(Modifier.height(MimirSpacing.sm))
            Text(
                text = copy.homeRow,
                style = MimirType.RowSupport,
                color = palette.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .graphicsLayer { alpha = legendProgress.value },
            )
        }
    }
}

/** Tags FINISH's list labels, so a fit test can check that they share one left edge. */
internal const val OnboardingFinishLegendLabelTag = "onboarding/finish/legend-label"

/**
 * FINISH's button list. One row per binding: a keycap
 * column as wide as the widest keycap, keycaps right-aligned in it, then the label, so every label starts on one
 * left edge. Each row reads as one node ("X, Open Tomes"). A label is one line at the default size and wraps at a raised font scale; the list is only as wide as its
 * longest row and never wider than it is given.
 */
@Composable
private fun OnboardingFinishLegendList(
    entries: List<OnboardingFinishLegendEntry>,
    palette: MimirThemePalette,
    modifier: Modifier = Modifier,
) {
    val colors = mimirHintPillColors(palette)
    // One line at the default size; a label wraps at a raised font scale rather than ending in an ellipsis, since the
    // list is as wide as its longest row and a larger label can outgrow a narrow viewport (the 2026-10-06 large-font task).
    val labelMaxLines = if (LocalDensity.current.fontScale > 1f) Int.MAX_VALUE else 1
    Layout(
        modifier = modifier,
        content = {
            entries.forEach { entry ->
                val legendDescription = stringResource(R.string.app_onboarding_hint_card_description, entry.key, entry.action)
                MimirHintKeycap(
                    key = entry.key,
                    colors = colors,
                    modifier = Modifier.clearAndSetSemantics { contentDescription = legendDescription },
                )
                Text(
                    text = entry.action,
                    style = MimirType.RowSupport,
                    color = palette.textPrimary,
                    maxLines = labelMaxLines,
                    overflow = TextOverflow.Ellipsis,
                    // Silent: the keycap beside it already speaks the whole row.
                    modifier = Modifier.clearAndSetSemantics { testTag = OnboardingFinishLegendLabelTag },
                )
            }
        },
    ) { measurables, constraints ->
        val gap = MimirSpacing.sm.roundToPx()
        val rowGap = MimirSpacing.xs.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val keys = measurables.filterIndexed { index, _ -> index % 2 == 0 }.map { it.measure(loose) }
        val keyColumn = keys.maxOfOrNull { it.width } ?: 0
        val labelMax = (constraints.maxWidth - keyColumn - gap).coerceAtLeast(0)
        val labels = measurables.filterIndexed { index, _ -> index % 2 == 1 }
            .map { it.measure(loose.copy(maxWidth = labelMax)) }
        val rowHeights = keys.indices.map { maxOf(keys[it].height, labels[it].height) }
        val width = (keyColumn + gap + (labels.maxOfOrNull { it.width } ?: 0))
            .coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = (rowHeights.sum() + rowGap * (rowHeights.size - 1).coerceAtLeast(0))
            .coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            var y = 0
            keys.indices.forEach { row ->
                keys[row].placeRelative(keyColumn - keys[row].width, y + (rowHeights[row] - keys[row].height) / 2)
                labels[row].placeRelative(keyColumn + gap, y + (rowHeights[row] - labels[row].height) / 2)
                y += rowHeights[row] + rowGap
            }
        }
    }
}

/** The finishing check echoes the welcome mark inside the existing eyebrow slot. */
@Composable
private fun OnboardingFinishEyebrow(palette: MimirThemePalette) {
    val reducedMotion = mimirReducedMotion()
    if (reducedMotion) {
        MimirChromeText(
            text = stringResource(R.string.app_onboarding_finish_eyebrow),
            visualOverride = stringResource(R.string.app_onboarding_finish_eyebrow_visual),
            style = MimirType.RunicEyebrow.copy(letterSpacing = 0.18.em),
            color = palette.accentBright,
        )
    } else {
        val settle = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            // Give the initial visual state one frame before starting its finite settle.
            withFrameNanos { }
            settle.animateTo(
                targetValue = 1f,
                animationSpec = tween(MimirMotion.SceneMs, easing = MimirMotion.Easing),
            )
        }
        Box(
            modifier = Modifier.graphicsLayer {
                val progress = settle.value
                alpha = 0.6f + 0.4f * progress
                scaleX = 0.9f + 0.1f * progress
                scaleY = 0.9f + 0.1f * progress
            },
        ) {
            MimirChromeText(
                text = stringResource(R.string.app_onboarding_finish_eyebrow),
                visualOverride = stringResource(R.string.app_onboarding_finish_eyebrow_visual),
                style = MimirType.RunicEyebrow.copy(letterSpacing = 0.18.em),
                color = palette.accentBright,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// Shared onboarding chrome
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/** One instructional block shared by every DONE-tail teaching screen: a heading in natural case naming the subject,
 * followed by a plain instruction and body. Both use the 560dp measure [OnboardingAccessBody] already uses.
 * Teaching rows remain static and non-focusable; only the screen's single gold pill focuses. */
/**
 * QA-15 : controller instructions read as ordinary prose at arm's length on a
 * handheld, so the one word that says WHICH control to press is the first thing lost. These are the spans
 * to accent — the action token only, never the sentence around it, which is what the request specified for.
 *
 * Two rules, and the split exists to make false positives impossible rather than merely unlikely:
 *
 *  - [OnboardingAlwaysControlToken] holds the spellings that cannot be anything but a control. `SELECT`
 *    and `START` are all-caps, `L1`/`R1`/`L2`/`R2` and `D-pad` have no English reading at all.
 *  - [OnboardingVerbLedControlToken] covers the single letters, which very much can be ordinary words —
 *    "**A** Tome is a group you name yourself", "**M**imir". They are accented ONLY directly after
 *    press/hold/tap, so the article in that sentence, and every capital letter starting a sentence, is
 *    untouched by construction. A missed accent is cosmetic; an accented "A" that means "a" is a bug the
 *    reader has to decode.
 *
 * Onboarding copy is written to suit this: every control token in it follows one of those verbs, so the
 * rule never has to guess.
 */
private val OnboardingAlwaysControlToken = Regex("""\b(SELECT|START|L1|R1|L2|R2|D-pad)\b""")
private val OnboardingVerbLedControlToken = Regex("""\b(?:[Pp]ress|[Hh]old|[Tt]ap)\s+([ABXYM])\b""")

internal fun onboardingControlTokenSpans(text: String): List<IntRange> =
    (
        OnboardingAlwaysControlToken.findAll(text).map { it.range } +
            OnboardingVerbLedControlToken.findAll(text).mapNotNull { it.groups[1]?.range }
        )
        .distinct()
        .sortedBy { it.first }
        .toList()

/** [text] with each [onboardingControlTokenSpans] span in the theme's bright accent. */
@Composable
private fun onboardingAccentedControls(text: String): AnnotatedString {
    val accent = LocalMimirPalette.current.accentBright
    return remember(text, accent) {
        buildAnnotatedString {
            append(text)
            onboardingControlTokenSpans(text).forEach { span ->
                addStyle(
                    SpanStyle(color = accent, fontWeight = FontWeight.SemiBold),
                    span.first,
                    span.last + 1,
                )
            }
        }
    }
}

/**
 * The capability row's floor and its inner vertical padding. METADATA's three rows
 * took the step to 363 px against the 700x350 floor once RetroAchievements joined it, and the Connect pill
 * was squeezed to 24 px. The rows are 6 dp lower at their floor and take the tightest spacing token above and below their text,
 * so the pill (40 dp) never loses height; the gaps between the boxes stay [MimirSpacing.xs], so the bordered boxes never touch.
 * Onboarding's METADATA is the only caller.
 */
internal val OnboardingCapabilityRowMinHeight = 52.dp
internal val OnboardingCapabilityRowVerticalPadding = MimirSpacing.xs

/** A compact settings-like row used by optional onboarding capabilities. */
@Composable
private fun OnboardingCapabilityRow(
    title: String,
    detail: String,
    status: String? = null,
    statusColor: Color? = null,
    actionLabel: String? = null,
    actionKey: FocusKey? = null,
    actionFocused: Boolean = false,
    authority: FocusAuthority? = null,
    onAction: (() -> Unit)? = null,
    deferKey: FocusKey? = null,
    deferFocused: Boolean = false,
    onDefer: (() -> Unit)? = null,
    focusReveal: OnboardingFocusRevealState? = null,
) {
    val palette = LocalMimirPalette.current
    val shape = RoundedCornerShape(6.dp)
    val mayFlow = LocalDensity.current.fontScale > 1f
    // Three children, always: the title, the detail and the trailing status or pills. [onboardingCapabilityRowFlows]
    // decides how they sit.
    Layout(
        modifier = Modifier
            .widthIn(max = 680.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(palette.surfaceRaised.copy(alpha = if (palette.usesBrightField) 0.78f else 0.5f))
            .border(1.dp, palette.outline, shape)
            .heightIn(min = OnboardingCapabilityRowMinHeight)
            .padding(horizontal = MimirSpacing.lg, vertical = OnboardingCapabilityRowVerticalPadding),
        content = {
        Box { MimirChromeText(text = title, style = MimirType.RowTitle, color = palette.textPrimary) }
        Box { Text(text = detail, style = MimirType.RowSupport, color = palette.textMuted) }
        val rowAuthority = authority
        val rowActionKey = actionKey
        val rowAction = onAction
        val rowDeferKey = deferKey
        val rowDefer = onDefer
        if ((actionLabel != null && rowActionKey != null && rowAuthority != null && rowAction != null) ||
            (rowDeferKey != null && rowAuthority != null && rowDefer != null)
        ) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (status != null) {
                    MimirChromeText(
                        text = status,
                        style = MimirType.MicroStatus,
                        color = statusColor ?: palette.textMuted,
                        textAlign = TextAlign.End,
                    )
                }
                if (actionLabel != null && rowActionKey != null && rowAction != null) {
                    OnboardingPill(
                        label = actionLabel,
                        focusKey = rowActionKey,
                        gold = false,
                        focused = actionFocused,
                        authority = rowAuthority,
                        onActivate = rowAction,
                        compact = true,
                        focusReveal = focusReveal,
                    )
                }
                if (rowDeferKey != null && rowDefer != null) {
                    OnboardingPill(
                        label = stringResource(R.string.app_common_not_now),
                        focusKey = rowDeferKey,
                        gold = false,
                        focused = deferFocused,
                        authority = rowAuthority,
                        onActivate = rowDefer,
                        compact = true,
                        focusReveal = focusReveal,
                    )
                }
            }
        } else if (status != null) {
            MimirChromeText(
                text = status,
                style = MimirType.MicroStatus,
                color = statusColor ?: palette.textMuted,
                textAlign = TextAlign.End,
            )
        } else {
            Spacer(Modifier)
        }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val gap = MimirSpacing.lg.roundToPx()
        val lineGap = 2.dp.roundToPx()
        val trailing = measurables[2].measure(Constraints(maxWidth = (width - gap).coerceAtLeast(0)))
        val besideWidth = (width - gap - trailing.width).coerceAtLeast(0)
        val titleBeside = measurables[0].maxIntrinsicHeight(besideWidth)
        val sideBySideHeight = maxOf(
            titleBeside + lineGap + measurables[1].maxIntrinsicHeight(besideWidth),
            trailing.height,
        )
        val flowedHeight = maxOf(titleBeside, trailing.height) + lineGap + measurables[1].maxIntrinsicHeight(width)
        val flows = onboardingCapabilityRowFlows(
            mayFlow = mayFlow,
            hasTrailing = trailing.width > 0,
            sideBySideHeightPx = sideBySideHeight,
            flowedHeightPx = flowedHeight,
        )
        if (flows) {
            // The trailing status or pill takes the title's line, and the detail runs the row's full width beneath.
            val titleP = measurables[0].measure(Constraints(maxWidth = besideWidth))
            val detailP = measurables[1].measure(Constraints(maxWidth = width))
            val topLine = maxOf(titleP.height, trailing.height)
            val height = (topLine + lineGap + detailP.height).coerceIn(constraints.minHeight, constraints.maxHeight)
            layout(width, height) {
                titleP.placeRelative(0, (topLine - titleP.height) / 2)
                trailing.placeRelative(width - trailing.width, (topLine - trailing.height) / 2)
                detailP.placeRelative(0, topLine + lineGap)
            }
        } else {
            val titleP = measurables[0].measure(Constraints(maxWidth = besideWidth))
            val detailP = measurables[1].measure(Constraints(maxWidth = besideWidth))
            val textHeight = titleP.height + lineGap + detailP.height
            val height = maxOf(textHeight, trailing.height).coerceIn(constraints.minHeight, constraints.maxHeight)
            layout(width, height) {
                var y = (height - textHeight) / 2
                titleP.placeRelative(0, y)
                y += titleP.height + lineGap
                detailP.placeRelative(0, y)
                trailing.placeRelative(width - trailing.width, (height - trailing.height) / 2)
            }
        }
    }
}

/**
 * Whether an onboarding capability row sets its status or pill on the title's line, with the detail beneath it at the row's
 * full width, rather than in a column beside the text. Only at a raised font scale ([mayFlow]) and only when the row has a
 * trailing part, and then only when it is the shorter of the two: a column beside the pill narrows the detail, and at 2.0 that
 * is a line or two more on a 700 dp screen; at 1.3 the detail does not wrap more beside the pill, so the row stays as it was.
 */
internal fun onboardingCapabilityRowFlows(
    mayFlow: Boolean,
    hasTrailing: Boolean,
    sideBySideHeightPx: Int,
    flowedHeightPx: Int,
): Boolean = mayFlow && hasTrailing && flowedHeightPx < sideBySideHeightPx

internal data class OnboardingRevealBounds(
    val topPx: Int,
    val bottomPx: Int,
)

/**
 * Signed minimum scroll delta that reveals one focused onboarding control plus [marginPx] ring clearance.
 * Target and viewport bounds are both measured in window coordinates, so UP and DOWN use the same rule.
 */
internal fun onboardingFocusRevealDeltaPx(
    targetTopPx: Int,
    targetBottomPx: Int,
    viewportTopPx: Int,
    viewportBottomPx: Int,
    marginPx: Int,
): Int {
    val topWithMargin = targetTopPx - marginPx
    val bottomWithMargin = targetBottomPx + marginPx
    if (topWithMargin >= viewportTopPx && bottomWithMargin <= viewportBottomPx) return 0
    if (topWithMargin < viewportTopPx) return topWithMargin - viewportTopPx
    return bottomWithMargin - viewportBottomPx
}

/** Measured onboarding geometry keyed by the same identities owned by [FocusAuthority]. */
private class OnboardingFocusRevealState {
    private val targets = mutableMapOf<FocusKey, OnboardingRevealBounds>()

    /** The window y of the scrolling content's own top (it moves with the scroll); a target's bounds measured from it do not. */
    var contentTopPx = 0
        private set

    fun updateContentTop(topPx: Int) {
        contentTopPx = topPx
    }

    var viewport by mutableStateOf<OnboardingRevealBounds?>(null)
        private set

    var revision by mutableStateOf(0)
        private set

    fun updateViewport(bounds: OnboardingRevealBounds) {
        if (viewport == bounds) return
        viewport = bounds
        revision += 1
    }

    fun updateTarget(key: FocusKey, bounds: OnboardingRevealBounds) {
        if (targets[key] == bounds) return
        targets[key] = bounds
        revision += 1
    }

    fun target(key: FocusKey?): OnboardingRevealBounds? = key?.let(targets::get)
}

private data class OnboardingFocusRevealRequest(
    /** The target's bounds in the scrolling content's own coordinates, so a scroll alone (which moves the control on the screen) is not a new request. */
    val target: OnboardingRevealBounds,
    val viewport: OnboardingRevealBounds,
    val maxScroll: Int,
)

/** Registers a live focus target with the scaffold's one reveal state. */
private fun Modifier.onboardingFocusRevealTarget(
    focusReveal: OnboardingFocusRevealState?,
    key: FocusKey,
): Modifier = if (focusReveal == null) {
    this
} else {
    onGloballyPositioned { coordinates ->
        if (coordinates.size.width == 0 || coordinates.size.height == 0) return@onGloballyPositioned
        val position = coordinates.positionInWindow()
        focusReveal.updateTarget(
            key = key,
            bounds = OnboardingRevealBounds(
                topPx = position.y.roundToInt(),
                bottomPx = position.y.roundToInt() + coordinates.size.height,
            ),
        )
    }
}

/**
 * The arriving step's glide in -- fade and travel on one clock -- provided by [OnboardingFlow] and read only inside [OnboardingScaffold]'s
 * layers, never in composition, so the 180 ms costs no recomposition. Settled by default: a screen composed outside the flow -- a test, a preview -- is simply visible.
 */
internal val LocalOnboardingStepArrival = compositionLocalOf<State<Float>> { OnboardingStepArrivalSettled }

private object OnboardingStepArrivalSettled : State<Float> {
    override val value: Float = 1f
}

/**
 * The step handover's other half (): how far the step on
 * screen has glided out, whether its arrival travels, and whether the header seat fades in place. Read only in
 * [OnboardingScaffold]'s layers, like [LocalOnboardingStepArrival].
 */
internal class OnboardingStepHandover(
    val departure: State<Float>,
    val arrivalTravels: Boolean,
    val headerArrives: Boolean,
    val headerDeparts: Boolean,
)

private object OnboardingStepDepartureSettled : State<Float> {
    override val value: Float = 0f
}

internal val LocalOnboardingStepHandover = compositionLocalOf {
    OnboardingStepHandover(
        departure = OnboardingStepDepartureSettled,
        arrivalTravels = false,
        headerArrives = false,
        headerDeparts = false,
    )
}

/** The header seat's alpha: 1 between two seated steps; faded with the step only where one side has no seat. */
internal fun onboardingHeaderSeatAlpha(arrival: Float, departure: Float, handover: OnboardingStepHandover): Float =
    (if (handover.headerArrives) arrival else 1f) * (if (handover.headerDeparts) 1f - departure else 1f)

/** Identifies the measured scaffold child for the zero-viewport regression harness. */
internal const val OnboardingScaffoldViewportTag = "onboarding/scaffold/viewport"

/** The reserved, non-scrolling control slot under the body (D-14/D-15). */
internal const val OnboardingControlSlotTag = "onboarding/scaffold/control-slot"

/**
 * The centered ceremony scaffold: the shared shelf atmosphere + a centered content column.
 *
 * onboarding pages must fit their viewport and never scroll — a
 * scrolling page lets focus land somewhere not visible — but
 * that only holds for the [MimirViewportPosture.STANDARD] posture. Scrolling remains available for [MimirViewportPosture.COMPACT_SQUARE] (RG-Rotate-class handhelds): measurement
 * showed 22 of 75 scenario x viewport combinations lost a clickable control to zero size on that posture
 * once scrolling was removed everywhere — a shipped break, not a cosmetic one. `mimirViewportProfile`
 * (already used for exactly this dimension-owned posture split elsewhere in the app — never a device model
 * string) decides which path this composable takes:
 *
 * - STANDARD: a decision-sized column with a bounded, weighted body and a measured controls slot. At the
 *   default font scale it composes no scrollable surface; above that scale the body gets a scroll surface
 *   only when its natural height exceeds the body seat above the reserved controls. Every STANDARD screen was
 *   measured against the reference viewport (Odin, 832.5x468.3dp) and reflowed without touching a
 *   type or spacing token VALUE — see OnboardingScreenFitTest. On a valid measurement the decision size is
 *   the current viewport; during a held zero it remains the last valid viewport, preserving layout and focus
 *   while drawing nothing.
 * - COMPACT_SQUARE: the same decision-sized boundary around a body-only `verticalScroll` + `heightIn(min =
 *   decision height)` (so the body can still grow past the viewport when content needs more), with controls
 *   remaining outside that scroll surface. The [OnboardingFocusRevealState] /
 *   [onboardingFocusRevealDeltaPx] scroll-into-view `LaunchedEffect` keeps body focus usable; while a zero is
 *   held, zero-sized outer coordinates do not replace the last valid reveal viewport and the reveal effect is
 *   paused, so scroll position is held too.
 *
 * A bounded zero before the first valid measurement composes no column for that frame. Unbounded constraints
 * retain the defensive STANDARD `fillMaxSize()` fallback and do not enter the measured-viewport helper.
 *
 * [header] is the persistent seat above the body. Every step but WELCOME passes [OnboardingHeaderStrip]; WELCOME keeps its ceremony lockup
 * inside its own centred body. The header is laid out before the weighted body on both postures, so it holds
 * one seat across steps and never enters the body's scroll surface.
 *
 * The body and the control slot glide out with a departing step and in with an arriving one
 * ([LocalOnboardingStepHandover], [LocalOnboardingStepArrival]). The header seat
 * never travels, so it is the one thing on screen that stays while the step under it changes; it fades in place
 * only where the step on the other side has no seat. The
 * unbounded fallback is never a real-device path and does not fade.
 */
/**
 * Whether onboarding bakes its scene with the quiet light: the Default theme's sun at half, as Home and Library
 * already do on compact using the same compact-scene rule. On the RG's 564.7 dp square the sun sits at
 * about (457, 455) dp, r 95, under every bottom-right action seat (NOT NOW, CHOOSE ANOTHER FOLDER, OPEN TOME).
 *
 * Read from the root profile only, never a local measure: this is decided at the call site, before the
 * scaffold's own BoxWithConstraints, and onboarding always composes under `MimirPrimaryViewportProfile`. The
 * default 0x0 profile (no provider) stays at full light.
 */
internal fun onboardingQuietSceneLight(profile: MimirViewportProfile): Boolean =
    profile.widthDp > 0f && profile.isCompact

/**
 * Whether onboarding moves the Default sun below the text column. At 640x480 (4:3), the
 * default anchor overlapped permission text and action-page instructions. 4:3 and square fields move it
 * (`MimirViewportProfile.isFourThreeOrSquare`); wide fields keep it. Read from the root profile only,
 * like [onboardingQuietSceneLight]; the 0x0 default keeps the default anchor.
 */
internal fun onboardingSceneLightBelowText(profile: MimirViewportProfile): Boolean =
    profile.sceneLightBelowText

/**
 * The room under onboarding's last pill: its focus ring's reach ([MimirFilledControlRingReach], 17 dp) plus the
 * flare's ([MimirFocusFlareReach], 6 dp), 23 dp. The
 * scaffold's bottom padding was MimirSpacing.lg (16 dp), so the resting ring's outer arc touched the screen's edge
 * and a flare (the pill lighting as the step arrives and focus lands) ran 7 dp off it. The top keeps
 * MimirSpacing.lg.
 */
internal val OnboardingFooterClearance = MimirFilledControlRingReach + MimirFocusFlareReach

/**
 * True inside [OnboardingFlow], which draws the one sky beneath every step ([OnboardingFlowSky]): two steps cross-fading
 * must not each draw an opaque sky, or the one on top would hide the other. A step composed alone (a test, a preview)
 * draws its own.
 */
private val LocalOnboardingSkyHosted = compositionLocalOf { false }

/** The flow's one sky: [alpha] is WELCOME's, so the stars and the planet arrive with the rest of its screen, after the verse. */
@Composable
private fun OnboardingFlowSky(alpha: State<Float>) {
    MimirShelfBackground(
        modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value },
        quietSceneLight = onboardingQuietSceneLight(LocalMimirViewportProfile.current),
        sceneLightBelowText = onboardingSceneLightBelowText(LocalMimirViewportProfile.current),
    ) {}
}

/** Onboarding's sky ([MimirShelfBackground] with the steps' scene light), or a bare box where [draws] is false or the flow hosts it. */
@Composable
private fun OnboardingSky(draws: Boolean, content: @Composable BoxScope.() -> Unit) {
    if (draws && !LocalOnboardingSkyHosted.current) {
        MimirShelfBackground(
            modifier = Modifier.fillMaxSize(),
            quietSceneLight = onboardingQuietSceneLight(LocalMimirViewportProfile.current),
            sceneLightBelowText = onboardingSceneLightBelowText(LocalMimirViewportProfile.current),
            content = content,
        )
    } else {
        Box(Modifier.fillMaxSize(), content = content)
    }
}

@Composable
private fun OnboardingScaffold(
    authorityFocusedKey: FocusKey? = null,
    focusReveal: OnboardingFocusRevealState? = null,
    header: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
    controls: @Composable ColumnScope.() -> Unit = {},
    /** False where the page's host draws one sky under all its pages (the favourites step). */
    drawsSky: Boolean = true,
) {
    val viewportModifier = Modifier.fillMaxSize()
    OnboardingSky(draws = drawsSky) {
        BoxWithConstraints(viewportModifier) {
            val measuredWidth = maxWidth
            val measuredHeight = maxHeight
            // A real window always gives BoxWithConstraints bounded constraints; this guard is defensive
            // (mimirViewportProfile requires finite input) — an unbounded viewport isn't the small
            // RG-Rotate square posture by definition, so STANDARD's already-fit-tested behaviour is the
            // correct fallback, never a real-device path.
            if (!measuredWidth.value.isFinite() || !measuredHeight.value.isFinite()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(
                            start = MimirSpacing.xxl,
                            top = MimirSpacing.lg,
                            end = MimirSpacing.xxl,
                            bottom = OnboardingFooterClearance,
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    header?.invoke()
                    content()
                    controls()
                }
                return@BoxWithConstraints
            }

            val viewport = rememberMeasuredViewport(
                maxWidth = measuredWidth,
                maxHeight = measuredHeight,
                inDomain = { widthDp, heightDp ->
                    widthDp.isFinite() && heightDp.isFinite() && widthDp > 0f && heightDp > 0f
                },
                beforeFirstValid = MimirBeforeFirstValidViewport.RENDER_NOTHING,
            )
            if (!viewport.shouldRender) return@BoxWithConstraints

            // The strict classifier receives the decision's dimensions. VALID uses the current measurement;
            // TRANSIENT_ZERO uses only the held valid measurement; a FATAL decision is never replaced by history.
            val rootProfile = LocalMimirViewportProfile.current
            val isCompactSquare = if (rootProfile.widthDp > 0f) {
                rootProfile.isCompact
            } else {
                mimirViewportProfile(viewport.width.value, viewport.height.value).isCompact
            }
            val measuredViewportModifier = Modifier
                .mimirMeasuredViewport(viewport)
                .testTag(OnboardingScaffoldViewportTag)
            if (isCompactSquare) {
                OnboardingScaffoldCompactSquareColumn(
                    authorityFocusedKey = authorityFocusedKey,
                    focusReveal = focusReveal,
                    maxHeight = viewport.height,
                    revealEnabled = !viewport.suppressDrawing,
                    viewportModifier = measuredViewportModifier,
                    header = header,
                    content = content,
                    controls = controls,
                )
            } else {
                OnboardingScaffoldMeasuredColumn(
                    authorityFocusedKey = authorityFocusedKey,
                    focusReveal = focusReveal,
                    raisedScaleScroll = true,
                    revealEnabled = !viewport.suppressDrawing,
                    viewportModifier = measuredViewportModifier
                        .padding(
                            start = MimirSpacing.xxl,
                            top = MimirSpacing.lg,
                            end = MimirSpacing.xxl,
                            bottom = OnboardingFooterClearance,
                        ),
                    header = header,
                    content = content,
                    controls = controls,
                )
            }
        }
    }
}

/**
 * COMPACT_SQUARE's scrollable path. The decision-sized modifier preserves its layout through a held zero;
 * [revealEnabled] pauses focus reveal during that frame so retained scroll state cannot move against the
 * outer box's transient geometry. See [OnboardingScaffold]'s doc for the posture rules.
 */
@Composable
private fun OnboardingScaffoldCompactSquareColumn(
    authorityFocusedKey: FocusKey?,
    focusReveal: OnboardingFocusRevealState?,
    maxHeight: Dp,
    revealEnabled: Boolean,
    viewportModifier: Modifier,
    header: (@Composable () -> Unit)?,
    content: @Composable ColumnScope.() -> Unit,
    controls: @Composable ColumnScope.() -> Unit,
) {
    val scrollState = rememberScrollState()

    OnboardingScaffoldMeasuredColumn(
        authorityFocusedKey = authorityFocusedKey,
        focusReveal = focusReveal,
        raisedScaleScroll = true,
        forceScroll = true,
        revealEnabled = revealEnabled,
        externalScrollState = scrollState,
        viewportModifier = viewportModifier
            .heightIn(min = maxHeight)
            .padding(
                start = MimirSpacing.xxl,
                top = MimirSpacing.lg,
                end = MimirSpacing.xxl,
                bottom = OnboardingFooterClearance,
            ),
        header = header,
        content = content,
        controls = controls,
    )
}

/**
 * The D-16 decision-sized child. The body is the only scrolling surface; the measured controls slot is a
 * sibling below it, so an overflowing body cannot consume the action seat. A natural-height probe is used
 * only to choose the raised-scale STANDARD branch; STANDARD at 1.0 remains a plain, non-scrollable body.
 */
@Composable
private fun OnboardingScaffoldMeasuredColumn(
    authorityFocusedKey: FocusKey?,
    focusReveal: OnboardingFocusRevealState?,
    raisedScaleScroll: Boolean,
    forceScroll: Boolean = false,
    revealEnabled: Boolean,
    externalScrollState: androidx.compose.foundation.ScrollState? = null,
    viewportModifier: Modifier,
    header: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
    controls: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val scrollState = externalScrollState ?: rememberScrollState()
    var naturalBodyHeight by remember { mutableStateOf(0) }
    var bodySeatHeight by remember { mutableStateOf(0) }
    val shouldScroll = forceScroll ||
        (raisedScaleScroll && density.fontScale > 1f && naturalBodyHeight > bodySeatHeight)
    val revealRevision = focusReveal?.revision ?: 0
    val revealMarginPx = with(density) { MimirSpacing.sm.roundToPx() }
    val arrival = LocalOnboardingStepArrival.current
    val handover = LocalOnboardingStepHandover.current
    var lastRevealRequest by remember { mutableStateOf<OnboardingFocusRevealRequest?>(null) }

    LaunchedEffect(revealEnabled, authorityFocusedKey, revealRevision, scrollState.maxValue, shouldScroll) {
        if (!revealEnabled) return@LaunchedEffect
        val viewport = focusReveal?.viewport ?: return@LaunchedEffect
        val target = focusReveal.target(authorityFocusedKey) ?: return@LaunchedEffect
        // A scroll moves the focused control on the screen without changing where it is in the content; judged on screen coordinates, a
        // touch scroll that took it out of view read as a new request and was undone by the reveal, so the rows below it could not be
        // reached on a compact square (the 2026-10-06 METADATA stall). Only a change in the content, the viewport or the focused key reveals.
        val request = OnboardingFocusRevealRequest(
            target = OnboardingRevealBounds(
                topPx = target.topPx - focusReveal.contentTopPx,
                bottomPx = target.bottomPx - focusReveal.contentTopPx,
            ),
            viewport = viewport,
            maxScroll = scrollState.maxValue,
        )
        if (lastRevealRequest == request) return@LaunchedEffect
        lastRevealRequest = request
        val delta = onboardingFocusRevealDeltaPx(
            targetTopPx = target.topPx,
            targetBottomPx = target.bottomPx,
            viewportTopPx = viewport.topPx,
            viewportBottomPx = viewport.bottomPx,
            marginPx = revealMarginPx,
        )
        if (delta != 0) {
            scrollState.scrollTo((scrollState.value + delta).coerceIn(0, scrollState.maxValue))
        }
    }

    Column(
        viewportModifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The header seat (policy 2026-09-28, Q4): top of the column, outside the centred body and outside
        // its scroll surface, so it sits in the same place on every step and never scrolls away.
        if (header != null) {
            // It never travels. It fades in place only where the step on the other side has no seat
            // (WELCOME -> PROFILE, SETTLING -> THEME, THEME -> FAVORITES or FINISH); between two seated steps it stays
            // still.
            Box(
                Modifier.graphicsLayer {
                    alpha = onboardingHeaderSeatAlpha(arrival.value, handover.departure.value, handover)
                },
            ) {
                header()
            }
            Spacer(Modifier.height(MimirSpacing.md))
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                // One layer for the clip and the glide, so the clipped box travels whole rather than its content
                // sliding under a fixed cut line.
                .graphicsLayer {
                    clip = true
                    shape = RectangleShape
                    alpha = onboardingStepAlpha(arrival.value, handover.departure.value)
                    translationX = onboardingStepTravelPx(
                        arrival.value,
                        handover.departure.value,
                        handover.arrivalTravels,
                        MimirMotion.GlideTravelDp.dp.toPx(),
                    )
                }
                .onGloballyPositioned { coordinates ->
                    if (coordinates.size.width == 0 || coordinates.size.height == 0) return@onGloballyPositioned
                    if (bodySeatHeight != coordinates.size.height) bodySeatHeight = coordinates.size.height
                    focusReveal?.let { reveal ->
                        val position = coordinates.positionInWindow()
                        reveal.updateViewport(
                            OnboardingRevealBounds(
                                topPx = position.y.roundToInt(),
                                bottomPx = position.y.roundToInt() + coordinates.size.height,
                            ),
                        )
                    }
                },
            contentAlignment = if (shouldScroll) Alignment.TopCenter else Alignment.Center,
        ) {
            val bodyModifier = Modifier
                .fillMaxWidth()
                .then(if (shouldScroll) Modifier.verticalScroll(scrollState) else Modifier)
            OnboardingNaturalBody(
                modifier = bodyModifier,
                contentModifier = Modifier.onGloballyPositioned { coordinates ->
                    focusReveal?.updateContentTop(coordinates.positionInWindow().y.roundToInt())
                },
                onMeasured = { measured ->
                    if (naturalBodyHeight != measured) naturalBodyHeight = measured
                },
                content = content,
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    alpha = onboardingStepAlpha(arrival.value, handover.departure.value)
                    // With the body, on one clock and one offset, so the pills keep their place under the text.
                    translationX = onboardingStepTravelPx(
                        arrival.value,
                        handover.departure.value,
                        handover.arrivalTravels,
                        MimirMotion.GlideTravelDp.dp.toPx(),
                    )
                    // Not the default buffer: it is sized to the slot, and the gold pill's ring reaches past the
                    // slot's bottom edge, so the fade would cut the ring's lower arc and pop it back at 1. MimirTokens uses the same treatment. The
                    // slot holds pills and text only, so there is no overlapping child to double-expose.
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }
                .testTag(OnboardingControlSlotTag),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            controls()
        }
    }
}

/** Measures the body at its natural height while the parent decides whether that height needs scrolling. */
@Composable
private fun OnboardingNaturalBody(
    modifier: Modifier,
    contentModifier: Modifier = Modifier,
    onMeasured: (Int) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Layout(
        modifier = modifier,
        content = {
            Column(modifier = contentModifier, horizontalAlignment = Alignment.CenterHorizontally, content = content)
        },
    ) { measurables, constraints ->
        val child = measurables.single().measure(
            constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity),
        )
        onMeasured(child.height)
        layout(
            width = child.width.coerceIn(constraints.minWidth, constraints.maxWidth),
            height = child.height.coerceIn(constraints.minHeight, constraints.maxHeight),
        ) {
            child.placeRelative((constraints.maxWidth - child.width).coerceAtLeast(0) / 2, 0)
        }
    }
}

/**
 * Keeps paired actions horizontal on handheld landscapes and stacked on compact-square content rails -- and, since
 * the D-14/D-15 repair, also stacked whenever the MEASURED row is wider than the space it has. At raised font
 * scale a pair such as Continue + Not now outgrew a landscape width and spilled off both edges, centred; the
 * width-only [onboardingActionsStack] rule could not see that. At 1.0 every certified row fits, so nothing that
 * already rendered as a row changes.
 */
@Composable
private fun OnboardingActionGroup(content: @Composable () -> Unit) {
    Layout(content = content, modifier = Modifier.fillMaxWidth()) { measurables, constraints ->
        val rowGap = MimirSpacing.md.roundToPx()
        val columnGap = MimirSpacing.sm.roundToPx()
        val placeables = measurables.map { it.measure(Constraints(maxWidth = constraints.maxWidth)) }
        val gaps = (placeables.size - 1).coerceAtLeast(0)
        val rowWidth = placeables.sumOf { it.width } + rowGap * gaps
        val stack = onboardingActionsStack(constraints.maxWidth.toDp().value) || rowWidth > constraints.maxWidth
        val contentWidth = if (stack) placeables.maxOfOrNull { it.width } ?: 0 else rowWidth
        val contentHeight = if (stack) {
            placeables.sumOf { it.height } + columnGap * gaps
        } else {
            placeables.maxOfOrNull { it.height } ?: 0
        }
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else contentWidth
        val height = contentHeight.coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            if (stack) {
                var y = 0
                placeables.forEach { p ->
                    p.placeRelative((width - p.width) / 2, y)
                    y += p.height + columnGap
                }
            } else {
                var x = (width - rowWidth) / 2
                placeables.forEach { p ->
                    p.placeRelative(x, (contentHeight - p.height) / 2)
                    x += p.width + rowGap
                }
            }
        }
    }
}

/** Identifies the persistent header strip, so a test can hold its seat still across steps. */
internal const val OnboardingHeaderStripTag = "onboarding/header/strip"

/**
 * The persistent header : the same three pieces as
 * [OnboardingHeaderLockup] -- the ᛗ badge, the MIMIR wordmark and the rune line -- laid in one 24 dp row instead
 * of a 93 dp stack. It holds one seat on every step after WELCOME ([OnboardingScaffold]'s header seat), so it
 * reads as one header that stays while the step under it changes. The rune line gives way first when a raised
 * font scale leaves no room (one line, ellipsized); the badge and the wordmark never do. The runes lead the line,
 * so they are the last of it to go.
 *
 * The runes count the setup : [onboardingSetupRunesLit] of
 * them take the theme's bright accent and the rest keep the line's muted ink. Only a color changes, never a glyph,
 * so the row's height and width are the same on every step. The rune lights as its step arrives.
 */
@Composable
private fun OnboardingHeaderStrip(palette: MimirThemePalette, step: OnboardingStep) {
    val runes = remember(step, palette.accentBright) {
        onboardingRuneEyebrow(lit = onboardingSetupRunesLit(step), litColor = palette.accentBright)
    }
    // The strip is chrome, one 24 dp row by the policy, and it is not text the player reads for content: the
    // wordmark and the rune line keep their default size at a raised font scale. At 2.0 they made the strip 44 dp,
    // 20 dp of every page's height, taken from the body that already had to scroll (the 2026-10-06 large-font task).
    val density = LocalDensity.current
    val chromeDensity = remember(density.density, density.fontScale) {
        if (density.fontScale > 1f) Density(density = density.density, fontScale = 1f) else density
    }
    CompositionLocalProvider(LocalDensity provides chromeDensity) {
        Row(
            modifier = Modifier
                .heightIn(min = 24.dp)
                .testTag(OnboardingHeaderStripTag),
            horizontalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MimirMannazBadge(palette = palette, size = 24.dp)
            Text(
                text = stringResource(R.string.app_brand_wordmark),
                style = MimirType.Title,
                color = palette.textPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.34.em,
                maxLines = 1,
            )
            Text(
                text = runes,
                style = MimirType.RunicEyebrowCompact,
                color = palette.textMuted,
                fontSize = 10.sp,
                letterSpacing = 0.24.em,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

/**
 * How many of the header strip's five runes are lit on [step]: one per stage of setup, so every page that shows the
 * strip shows how much setup is left (policy 2026-09-28, after Cocoon's
 * whole-setup progress bar). Seven steps carry the strip and MIMIR has five runes, so the stages are the player's:
 * you (PROFILE), permissions (ACCESS), your games (LIBRARY), their details (METADATA, then SETTLING, which
 * finishes identifying them), and making Mimir yours (THEME, FAVORITES, FINISH). A rune lights on reaching its
 * stage, so FINISH shows all five. WELCOME and THEME show no strip; they are mapped only so the rule is total.
 */
internal fun onboardingSetupRunesLit(step: OnboardingStep): Int = when (step) {
    OnboardingStep.WELCOME -> 0
    OnboardingStep.PROFILE -> 1
    OnboardingStep.ACCESS -> 2
    OnboardingStep.LIBRARY -> 3
    OnboardingStep.METADATA, OnboardingStep.SETTLING -> 4
    OnboardingStep.THEME, OnboardingStep.FAVORITES, OnboardingStep.DONE -> 5
}

/**
 * [MimirRuneEyebrow] with its first [lit] runes in [litColor]. The text itself never changes, so neither does its
 * measure. [litColor] is the palette's accentBright, the accent onboarding already uses for text, whose contrast
 * with the field is held on every theme (OnboardingHintBarAccentContrastTest).
 */
internal fun onboardingRuneEyebrow(lit: Int, litColor: Color): AnnotatedString = buildAnnotatedString {
    append(MimirRuneEyebrow)
    val end = lit.coerceIn(0, MimirRuneEyebrow.substringBefore(' ').length)
    if (end > 0) addStyle(SpanStyle(color = litColor), 0, end)
}

/** The identity lockup: the ᛗ Mannaz badge over the letterspaced MIMIR wordmark + runic sub-eyebrow. WELCOME's
 * ceremony form; every later step shows [OnboardingHeaderStrip] instead. */
@Composable
private fun OnboardingHeaderLockup(
    palette: MimirThemePalette,
    animateBadgeOnEntry: Boolean = false,
    /** Holds the ignite until this turns true: WELCOME's lockup is hidden while the verse shows alone (Q1). */
    igniteArmed: Boolean = true,
) {
    // The lockup is chrome, as the header strip is: at a raised font scale its wordmark and rune line keep their default
    // size, the badge is a step smaller and the gap under it one step tighter, so WELCOME fits where it can (the 2026-10-06
    // large-font debt). At 1.0 it is the 52 dp badge, the lg gap and the same type it always was.
    val density = LocalDensity.current
    val raisedScale = density.fontScale > 1f
    val badgeSize = if (raisedScale) 44.dp else 52.dp
    val chromeDensity = remember(density.density, density.fontScale) {
        if (density.fontScale > 1f) Density(density = density.density, fontScale = 1f) else density
    }
    if (animateBadgeOnEntry && !mimirReducedMotion()) {
        val ignite = remember { Animatable(0f) }
        LaunchedEffect(igniteArmed) {
            if (!igniteArmed) return@LaunchedEffect
            // Let the still, low-alpha mark reach a presented frame before its short ignition.
            withFrameNanos { }
            ignite.animateTo(
                targetValue = 1f,
                animationSpec = tween(OnboardingIntroShineMs, easing = MimirMotion.Easing),
            )
        }
        Box(
            modifier = Modifier
                .size(badgeSize)
                .graphicsLayer {
                    val progress = ignite.value
                    alpha = 0.6f + 0.4f * progress
                    scaleX = 0.85f + 0.15f * progress
                    scaleY = 0.85f + 0.15f * progress
                },
        ) {
            MimirMannazBadge(palette = palette, size = badgeSize)
        }
    } else {
        MimirMannazBadge(palette = palette, size = badgeSize)
    }
    Spacer(Modifier.height(if (raisedScale) MimirSpacing.sm else MimirSpacing.lg))
    CompositionLocalProvider(LocalDensity provides chromeDensity) {
        Text(
            text = stringResource(R.string.app_brand_wordmark),
            style = MimirType.Title,
            color = palette.textPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.34.em,
        )
        Spacer(Modifier.height(MimirSpacing.xs))
        Text(
            text = MimirRuneEyebrow,
            style = MimirType.RunicEyebrowCompact,
            color = palette.textMuted,
            fontSize = 10.sp,
            letterSpacing = 0.24.em,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The controller-hint bar (e.g. `A CONTINUE · TAP THE FIELD TO TYPE`). The button glyph carries
 * [MimirThemePalette.accentBright] — the same accent-family token this file already uses for the
 * "Who's playing?"/"Setup complete" eyebrows — a control token reading as a token,
 * same pattern as `HomeShelfScreen.kt`'s `HomeShelfTomeShortcutHint` standalone "X" glyph. The action word
 * and the touch fallback line below stay on the existing neutral ramp; restraint is the point, not a
 * repaint.
 */
@Composable
private fun OnboardingHintBar(palette: MimirThemePalette, button: String, action: String, touchHint: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // "A Type" and "A Select" glide out, then in.
        MimirHintSwap(
            key = button to action,
            glide = MimirMotion.GlideTravelDp.dp,
            durationMs = OnboardingMotionMs,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                MimirControlHintText(
                    text = button,
                    style = MimirType.ControlHint,
                    color = palette.accentBright,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                MimirControlHintText(
                    text = action,
                    style = MimirType.ControlHint,
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        MimirChromeText(
            text = touchHint,
            style = MimirType.ControlHintCompact,
            color = palette.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A shared onboarding pill. `gold` = the shipping `--theme-cta-material` satin gradient (primary action);
 * otherwise a quiet ghost (outline) pill. Registers on the single [FocusAuthority] and commits on tap (touch +
 * gamepad parity) — no Compose `focusable()`.
 *
 * @param interactive When `false`, this pill is a purely decorative ghost: no [mimirFocusable] registration
 * (so it neither steals nor — critically — UNREGISTERS a [FocusKey] another live pill shares, see below), no
 * [focusReveal] bounds write, no activation, and [clearAndSetSemantics] so it reports nothing to touch
 * exploration or a screen reader. Used ONLY by the ACCESS sub-sequence's screen-to-screen and grant-state
 * `AnimatedContent` crossfades: `AnimatedContent` composes the DEPARTING page for the full
 * `MimirMotion.OverlayMs`, and several onboarding pills share one [FocusKey] across pages —
 * `OnboardingAccessContinueKey` is used by ORIENTATION and by every already-granted capability screen alike.
 * [DefaultFocusAuthority]'s `entries` map is keyed by [FocusKey] alone with no per-registrant identity, so
 * if the departing AND arriving pages both called [mimirFocusable] with that shared key, the arriving page's
 * fresh registration would win the bounds, and then the departing page's disposal-time `unregister` would
 * delete the ENTRY OUTRIGHT — silently unregistering the arriving, still-live pill's key. `interactive`
 * exists to make that race structurally impossible: the departing page never registers, so it has nothing to
 * unregister out from under its successor. This is unrelated to gamepad CONFIRM, which dispatches off
 * [FocusAuthority]'s remembered `focused` key and the caller's own fresh state ([dispatchOnboarding]), never
 * off which composable is on stage — only the TOUCH activation path and directional-neighbor bounds are at
 * risk here.
 */
@Composable
private fun OnboardingPill(
    label: String,
    focusKey: FocusKey,
    gold: Boolean,
    focused: Boolean,
    authority: FocusAuthority,
    onActivate: () -> Unit,
    scope: FocusScope = OnboardingScope,
    compact: Boolean = false,
    focusReveal: OnboardingFocusRevealState? = null,
    interactive: Boolean = true,
) {
    val palette = LocalMimirPalette.current
    val shape = RoundedCornerShape(MimirSpacing.radiusPill)
    // `focused` alone is not the ring's predicate: during the ACCESS sub-sequence's crossfade the DEPARTING page is still composed for the full
    // MimirMotion.OverlayMs and is handed `focused = focusedKey == OnboardingAccessContinueKey` by its caller, which is `true` -- that key is shared across
    // pages by design. Gate only the ring on `interactive` and the departing decorative pill still draws a lit ring, so the crossfade shows TWO rings. Derived
    // once here so a decorative pill can never draw one. (The gold pill once also took a focused TINT off the same predicate, [onboardingGoldPillFill]; it has
    // no fill now, so the ring is its whole focus affordance.)
    val focusAffordanceActive = focused && interactive
    // Update : neither pill is a filled capsule. The gold pill is the PRIMARY etched chip (the same dark plate as the ghost beside it, its rim and its
    // word in the accent, no fill), the ghost the unselected one; both keep their heights and widths, so the pair stays even.
    // The one ring, on the plate itself and BEFORE the surface, so its edge is the plate's edge and its
    // wash is under the opaque metal; only the FOCUSED pill wears it. Both pills flare when focus lands, never while
    // the step is still arriving -- the landing is the step's own motion. Read off the same predicate as the tint, so a departing decorative pill draws no ring either. The plate
    // is whole at rest (the ring's rest dim would dim the surface, so it is not asked for).
    val arrival = LocalOnboardingStepArrival.current
    Box(
        modifier = Modifier
            .then(
                if (!interactive) {
                    Modifier
                } else {
                    Modifier.mimirFocusable(
                        authority = authority,
                        key = focusKey,
                        scope = scope,
                        autoBringIntoView = focusReveal == null,
                    )
                },
            )
            .then(
                if (!interactive || focusReveal == null) {
                    Modifier
                } else {
                    Modifier.onboardingFocusRevealTarget(focusReveal, focusKey)
                },
            )
            // BEFORE the surface and the clip, because the ring is drawn outside the pill's own bounds.
            .mimirSelectionRing(
                focused = focusAffordanceActive,
                identity = focusKey,
                shape = MimirRingShape.Pill,
                flare = true,
                armed = { arrival.value >= 1f },
                gleam = if (gold) MimirRingGleam.Sweep else MimirRingGleam.None,
                edgeFlush = true,
            )
            .mimirEtchedChipSurface(palette, focused = focusAffordanceActive, primary = gold, edgeFlush = true)
            .clip(shape)
            .then(
                if (!interactive) {
                    Modifier.clearAndSetSemantics { }
                } else {
                    // One activation path for touch and the accessibility click, and no second focus node beside the
                    // authority's: `clickable` planted one here (the ratchet's OnboardingFlow count falls by one).
                    Modifier.mimirActivatable(label = label, authority = authority, key = focusKey, onActivate = onActivate)
                },
            )
            .heightIn(min = if (compact) 40.dp else 46.dp)
            .widthIn(
                min = if (compact) {
                    if (gold) 132.dp else 112.dp
                } else {
                    if (gold) 168.dp else 132.dp
                },
            )
            .padding(horizontal = if (compact) MimirSpacing.lg else MimirSpacing.xl),
        contentAlignment = Alignment.Center,
    ) {
        MimirChromeText(
            text = label,
            style = MimirEtchedChipLabelStyle,
            color = palette.etchedChipInk(primary = gold),
        )
    }
}

// (The ᛗ Mannaz badge moved to core-ui `MimirBrandMark.kt` — shared with the Welcome-back lane and the Bottom.)
