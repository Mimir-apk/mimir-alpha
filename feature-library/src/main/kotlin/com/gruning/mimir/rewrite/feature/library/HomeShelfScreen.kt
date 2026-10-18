package com.gruning.mimir.rewrite.feature.library

import android.content.Context
import android.os.PowerManager
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.input.mimirActivatable
import com.gruning.mimir.rewrite.core.input.mimirFocusable
import com.gruning.mimir.rewrite.core.input.isLitFocusIn
import com.gruning.mimir.rewrite.core.input.litFocusIn
import com.gruning.mimir.rewrite.core.input.renderedFocusIn
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.MimirBeforeFirstValidViewport
import com.gruning.mimir.rewrite.core.ui.LocalMimirControllerHintLabels
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.MimirControlHintText
import com.gruning.mimir.rewrite.core.ui.MimirHintSwap
import com.gruning.mimir.rewrite.core.ui.MimirEtchedChipLabelStyle
import com.gruning.mimir.rewrite.core.ui.MimirFocusTileRingReach
import com.gruning.mimir.rewrite.core.ui.etchedChipInk
import com.gruning.mimir.rewrite.core.ui.mimirEtchedChipSurface
import com.gruning.mimir.rewrite.core.ui.MimirRingShape
import com.gruning.mimir.rewrite.core.ui.mimirSelectionRing
import com.gruning.mimir.rewrite.core.ui.mimirHintPill
import com.gruning.mimir.rewrite.core.ui.mimirHintPillColors
import com.gruning.mimir.rewrite.core.ui.MimirHintKeycap
import com.gruning.mimir.rewrite.core.ui.MimirHintPillColors
import com.gruning.mimir.rewrite.core.ui.MimirHintToastRail
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirQuickRailAction
import com.gruning.mimir.rewrite.core.ui.MimirQuickRailTouchPhase
import com.gruning.mimir.rewrite.core.ui.MimirRunicQuickRail
import com.gruning.mimir.rewrite.core.ui.MimirRunicQuickRailDefaults
import com.gruning.mimir.rewrite.core.ui.MimirShelfBackground
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirToastMessage
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.mimirMeasuredViewport
import com.gruning.mimir.rewrite.core.ui.mimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.mimirQuickRailTargetWidth
import com.gruning.mimir.rewrite.core.ui.rememberMeasuredViewport
import kotlinx.coroutines.delay
import com.gruning.mimir.rewrite.core.ui.MimirPageHandoff
import com.gruning.mimir.rewrite.core.ui.MimirPageHandoffState
import com.gruning.mimir.rewrite.core.ui.MimirPageMotion
import com.gruning.mimir.rewrite.core.ui.MimirTransitionInputGate
import androidx.compose.ui.platform.LocalDensity

internal const val HomeShelfRootTestTag = "home_shelf"
const val HomeShelfMeasuredViewportTestTag = "home_shelf_measured_viewport"
internal const val HomeShelfTitleTestTag = "home_shelf_title"
internal const val HomeShelfHintToastTestTag = "home_shelf_hint_toast"
internal const val HomeShelfNavigationHintTestTag = "home_shelf_navigation_hint"
internal const val HomeShelfTomeShortcutHintTestTag = "home_shelf_tome_shortcut_hint"
internal const val HomeShelfQuickRailTestTag = "home_shelf_quick_rail"
internal const val HomeShelfEmptyActionTestTag = "home_shelf_open_library"
internal const val HomeShelfStatusTestTag = "home_shelf_status"

/**
 * The Home mode switch's one clock.
 *
 * Was a bespoke `120`. It is now [MimirMotion.ModeTransitionMs] -- the house token for exactly this
 * event, which the Library's own mode transition already rides (`LibraryScreen.kt`) and which Home was
 * the one surface not using. Same correction as `HomeTomeStatusLegibilityMs` got the same night: when a
 * surface reads too fast and the ladder already classifies it, the answer is the ladder, not a new
 * literal. If it still lands hard after this, the next move is to adjust the shared token, not a
 * private number here.
 */
internal const val HomeShelfModeHandoffMs = MimirMotion.ModeTransitionMs

/**
 * Whether an L1/R1 arrival that starts, or starts again, carries on from a band already on screen instead of beginning
 * from nothing (alpha audit F5). Two bands can be: one still leaving that the shoulder turned back to ([returningAlpha],
 * a reversal), and one this same arrival was already painting when its list changed under it ([painted], above 0).
 * Either carries on from the alpha it was painted at, as the retarget rule has it (MimirPageHandoff's KDoc): no snap to 0
 * and no second lead before the fade. A fresh switch shows neither, and starts hidden.
 */
internal fun homeArrivalCarriesOn(returningAlpha: Float?, painted: Float): Boolean =
    returningAlpha != null || painted > 0f

/*
 * Motion audit batch D (2026-09-28) retired UI-01's arrival settle (0.965 -> 1) and its unsigned 22 dp glide: the switch
 * is now a page change on core-ui's MimirPageMotion, signed by the shoulder that was pressed, and the page family uses no scale. HomeShelfModeHandoffMs above remains the restore's in-place fade.
 */
internal const val HomeShelfOutgoingStartAlpha = 0.68f
internal const val LightHomeTitleRailAlpha = 0.88f
internal const val LightHomeTitleRailOutlineAlpha = 0.22f
private val HomeShelfTitleRailShape = RoundedCornerShape(20.dp)
private val HomeShelfTitleRailHorizontalPadding = 10.dp
private val HomeShelfTitleRailVerticalPadding = 5.dp
internal val HomeShelfTomeTitleRailWidth = 144.dp

/** A localized contrast rail appears only where a light theme can place dark app ink over arbitrary media. */
internal fun homeShelfShowsMediaTitleRail(
    palette: MimirThemePalette,
    mediaBackdropAvailable: Boolean,
    tomeSurfaceActive: Boolean = false,
): Boolean = palette.needsLocalizedMediaContrast && (mediaBackdropAvailable || tomeSurfaceActive)

internal fun homeShelfStableTitleRailWidth(
    titleBandWidth: Dp,
    showMediaRail: Boolean,
    tomeSurfaceActive: Boolean,
): Dp? = if (showMediaRail && tomeSurfaceActive) {
    HomeShelfTomeTitleRailWidth.coerceAtMost(titleBandWidth)
} else {
    null
}
// FUX-01 §3.3/§3.4 focus grammar (panel §5, adopted): frameless art carries focus through scale, a DRAWN
// radial glow (never shadowElevation — the Thor graphicsLayer-alpha artifact, and a ghost box on frameless
// art), and the B2 caret. Glow lives only on the FOCUSED hero; the demoted hero (rail/picker owns focus)
// quiets to 0.9 with the glow dead.
// Shelf halo: Cocoon-style soft halo — wider, softer, three-stop shoulder; no hard ring/caret.
private const val HomeShelfGlowCenterAlpha = 0.10f
private const val HomeShelfGlowMidAlphaFactor = 0.4f
private const val HomeShelfGlowMidStop = 0.45f
private const val HomeShelfGlowRadiusFactor = 1.05f
/**
 * The status chip's own width allowance beside the title. It was 190 dp while the chip shared its row with a 64 dp
 * RetroAchievements count seat (plus a 4 dp gap); the count lives on the launch card now , so the
 * seat is gone and the title gets that width back.
 */
internal val HomeShelfStatusChipTrailingReserve = 122.dp
internal val HomeShelfGameCountLaneHeight = 18.dp
internal val HomeShelfGameCountPaintWidth = 104.dp
internal val HomeShelfTomeShortcutHintPaintWidth = 104.dp

/**
 * Fixed 2026-08-19/20 after the Tome hint collided with the Files dock item on Retroid Pocket Nova. [HomeShelfTomeShortcutHint]'s box is anchored to a FIXED offset from the viewport's right edge
 * (`viewport.right - gutter - HomeShelfTomeShortcutHintPaintWidth`); [MimirRunicQuickRail] is CENTERED,
 * and previously used a fixed preferred width ([MimirRunicQuickRailDefaults.RailWidth], 528dp) that only
 * shrank below the authored width once the viewport could no longer physically fit it -- so the rail's
 * right edge moved at HALF the rate the hint's fixed-edge anchor did as the viewport narrowed. The fix is
 * on the rail's side, not the hint's: `core-ui`'s `mimirQuickRailTargetWidth` now narrows the rail's
 * TARGET before that physical limit, so its right edge moves fast enough to stay clear much sooner.
 *
 * Returns the signed clearance in dp between the hint's LEFT edge and the rail's CONSERVATIVE right edge
 * -- `min(mimirQuickRailTargetWidth(viewport), RailWidth)`, i.e. the SAME value
 * [MimirRunicQuickRail]'s own early-return contract guarantees its true rendered width can never exceed
 * (capped at [MimirRunicQuickRailDefaults.RailWidth] because the target function alone can exceed the
 * authored ceiling at wide viewports, e.g. the Odin, where the real rail never does). Positive = provably
 * clear at this viewport; negative = the fixed-edge lane still reaches into the rail's worst-case span.
 *
 * Certified/reference viewports (before -> after this fix): 640x480dp (Nova) -67.8dp -> **+12.16dp**
 * (real, captured overlap resolved). 832.5x468.3dp (Odin) +22.4dp -> +22.4dp (byte-identical: the target
 * function resolves to 560.5dp there, above [MimirRunicQuickRailDefaults.RailWidth], so the rail's early
 * return keeps it exactly as it always was). 700x350dp (RP5) -39.7dp -> +10.3dp (not the reported device,
 * fixed as a side effect of the same rule -- previously uncaptured by any test).
 *
 * OWED, still: below ~597dp (e.g. the 538dp fixture used elsewhere in this test suite: -27.7dp, even at
 * [MimirRunicQuickRailDefaults.MinTargetRailWidth], the rail's real structural floor) the hint still
 * cannot clear without shrinking a coin past [MimirRunicQuickRailDefaults.MinActionDiameter] -- an
 * untappable dock, which this fix will not trade for a discoverability affordance. What to DO for THAT
 * range (shorten the mark, suppress it, or something else) is still a design call this function does not
 * make -- it only proves the geometry so the design decision has real numbers, now considerably better ones.
 */
internal fun homeShelfTomeShortcutHintClearanceDp(geometry: HomeShelfGeometry, compact: Boolean = false): Float {
    val conservativeRailWidth = minOf(
        mimirQuickRailTargetWidth(
            geometry.viewport.width.dp,
            if (compact) MimirRunicQuickRailDefaults.CompactRailEndReserve else MimirRunicQuickRailDefaults.RailEndReserve,
        ),
        MimirRunicQuickRailDefaults.RailWidth,
    ).value
    val conservativeRailRightEdge = (geometry.viewport.right + conservativeRailWidth) / 2f
    val hintLeftEdge =
        geometry.viewport.right - geometry.gutter - HomeShelfTomeShortcutHintPaintWidth.value
    return hintLeftEdge - conservativeRailRightEdge
}

/**
 * Where compact Home seats its X · TOME mark: the left end of the hint lane, the mirror of the navigation hint
 * band at its right end (same inset, same height), so it clears the focused tile's ring for the same reason
 * that band does.
 *
 * Policy 2026-09-27 (compact review HOME-3). the earlier update widened the compact dock to 436.7 dp, and the mark's old
 * home -- the dock band's right end, mirroring the count -- came out 57.5 dp inside the rail, so compact hid it.
 * The lane's left end is empty on compact: the navigation and tile hints sit at its right end, and the toast is
 * a centred 196 dp. Only the mark moves; the count stays in the dock band.
 */
internal fun homeShelfCompactTomeMarkBounds(geometry: HomeShelfGeometry): HomeShelfBounds {
    val top = geometry.hintToastBand.top + HomeShelfNavigationHintLaneInset
    return HomeShelfBounds(
        left = geometry.gutter,
        top = top,
        right = geometry.gutter + HomeShelfTomeShortcutHintPaintWidth.value,
        bottom = top + HomeShelfNavigationHintHeight,
    )
}

// FUX-12d: the selection-focus landing beat — deliberately FASTER than the 210ms glide.
private sealed interface HomeShelfDisplayItem {
    val focusKey: FocusKey
    val title: String

    data class Game(val row: HomeShelfRow) : HomeShelfDisplayItem {
        override val focusKey: FocusKey get() = row.focusKey
        override val title: String get() = row.title
    }

    data class Tome(val tome: HomeShelfTome) : HomeShelfDisplayItem {
        override val focusKey: FocusKey get() = homeShelfTomeFocusKey(tome.tomeId)
        override val title: String get() = tome.title
    }

    /**
     * The seat the shared shelf paints for this item. A game carries the face its row resolved; the platform id is the
     * row's supporting label, which is empty for an Android game (no console, so no shelfmark plate).
     */
    fun toGameShelfItem(): GameShelfItem = when (this) {
        is Game -> GameShelfItem.Game(
            focusKey = row.focusKey,
            title = row.title,
            face = row.face,
            platformIdValue = row.supportingLabel.orEmpty(),
        )
        is Tome -> GameShelfItem.Tome(focusKey = focusKey, tome = tome)
    }
}

/** The full-screen stage follows the exact decode-presented Tome game, never its physical-media plate. */
internal fun homeTomeBackdropLeadArt(preview: HomeTomePreview?): String? = preview
    ?.let { presented -> presented.backdropArtUri ?: presented.artUri.takeUnless { presented.isPhysicalMedia } }
    ?.takeIf(::isHomeTomeLocalArtUri)

/**
 * The Tome Library's background: the presented game's HERO slot ([HomeTomePreview.heroArtUri], the column the shelf's
 * own hero backdrop reads) and nothing else. A game without
 * a local hero is null, which the library paints as its theme backdrop (VideoBackdrop's floor): there is no rung
 * for the cover, the tile icon or physical media, so none of them can reach this surface. Remote URIs never do either.
 */
fun homeTomeLibraryBackdropArt(preview: HomeTomePreview?): String? = preview
    ?.heroArtUri
    ?.takeIf(::isHomeTomeLocalArtUri)

/**
 * The shelf's own Tome lead art. A Tome the Library has been opened on stays on the Library's rule afterwards (shared hero-or-theme background): the hero slot or the theme, never the cover or icon the Library refused to show, so closing
 * it does not cut from the theme to a cover. [libraryVisited] false is the shelf's own ladder ([homeTomeBackdropLeadArt]), unchanged.
 * A Tome with a hero gives the same art on either path.
 */
internal fun homeShelfTomeLeadArt(preview: HomeTomePreview?, libraryVisited: Boolean): String? =
    if (libraryVisited) homeTomeLibraryBackdropArt(preview) else homeTomeBackdropLeadArt(preview)

/** Focused Folio games may project only their already-local Hero Art into Home's backdrop stage. */
internal fun homeTomeFolioBackdropArt(member: HomeTomeMember?): String? =
    member?.backdropArtUri?.takeIf(::isHomeTomeLocalArtUri)

/**
 * RC-34: wait for the Tome opening to settle before handing off from collection hero art to the
 * focused member hero.
 *
 * ONE stage owns every Tome hero — the shelf's collection-view lead art AND the opened Folio's focused
 * member — so a Tome opening is a URI change on a mounted stage rather than a change of stage.
 *
 * Before this, the two were separate `if` branches over the same composable
 * ([HomeShelfStaticBackdrop]), which are distinct call sites and therefore distinct composition groups:
 * flipping the branch DISPOSED the outgoing stage and MOUNTED a fresh one, whose
 * `synchronizedPresence` is born at 1f for a non-null URI. Two frames, zero animation — the collection
 * hero vanished and the member hero appeared, both at full opacity. That hard cut is a defect on its
 * own, independent of WHEN the swap is triggered, and merging the call sites is what lets the existing
 * synchronized handoff dissolve URI→URI instead.
 *
 * @param folioActive the Folio session exists — a PRESENCE test, true on the first frame of activation.
 * @param folioOpenSettled the Folio's own opening has finished. See [HomeShelfScreen]'s parameter of the
 *   same name for who owns this and why it is not a timer.
 */
internal fun homeShelfFolioStageHandedOff(folioActive: Boolean, folioOpenSettled: Boolean): Boolean =
    folioActive && folioOpenSettled

/**
 * Whether the Tome LIBRARY's own materialisation has finished — [libraryArt]'s settle gate, the CP01
 * mirror of [homeShelfFolioStageHandedOff] for [folioArt] (spec §4.3, *"the shared stage hands off only
 * when the arriving surface is opaque"*).
 *
 * Deliberately ONE input, not [folioActive]/[folioOpenSettled]'s two. [libraryOpenSettled] is already a
 * LATCH that can only be true while the Library was genuinely opened and has finished arriving
 * (`MimirAppHost`'s pixel-fade host sets it, once, when the incoming layer reaches full alpha) and that
 * is deliberately NOT reset when the Library starts closing — see `homeTomeLibraryOpenSettled`'s own
 * kdoc. ANDing this with a live "is the Library still open" presence flag, the way the Folio's gate ANDs
 * with [homeShelfFolioStageHandedOff]'s `folioActive`, would flip this back OFF the instant the close
 * begins — reintroducing the exact art-mismatch flash [libraryArt]'s own kdoc says this hoist exists to
 * prevent on the RETURN, a direction this ticket does not touch.
 */
/**
 * How long after the Tome Library's own arrival is opaque before its hero art is allowed onto the
 * shared stage.
 *
 * **This is NOT the parallel timer CP01 §4.3 forbids, and the difference is the direction of the
 * dependency.** The banned shape is a `delay(hold + OverlayMs)` running BESIDE the tween and standing in
 * for a settle signal — it drifts under a dropped frame and hands off while the cover is translucent.
 * This starts FROM the settle latch, which is itself derived from the arriving animation reaching full
 * alpha. It cannot fire early, because it cannot fire before the thing it waits on. It is a
 * presentation beat after a landing, not a prediction of one.
 *
 * 90 ms is a beat the eye reads as sequence rather than lag, and it is two thirds of the 140 ms
 * `BifrostVisualHandoffMs` crossfade that follows it, so the pause is shorter than the arrival it
 * introduces.
 */
internal const val TomeLibraryArtLandingDelayMs: Long = 90L

internal fun homeShelfLibraryStageHandedOff(libraryOpenSettled: Boolean): Boolean = libraryOpenSettled

/**
 * The hero the Home stage paints while a Tome owns the surface.
 *
 * Total in its inputs and holds NO latch of its own, which is what makes the two failure modes on this
 * project's record unrepresentable rather than merely handled:
 *
 * - **Reversal** — back out mid-open and [folioStageHandedOff] is false (the session is gone), so the
 *   answer is the collection lead art. There is no state left holding the Folio member's hero, because
 *   this function never held any.
 * - **Burst** — open→close→open faster than the opening settles. A stale settled latch cannot leak in
 *   here: the only way this returns Folio art is for the CALLER to assert settlement for the CURRENT
 *   session. Invalidating that assertion on every authoritative change is the caller's half of the
 *   contract, exactly as `HomeTomeFolio`'s `onFocusUnsettled` already does for activation.
 *
 * **Changed tier order, 2026-08-25:** the shelf stays static behind a Tome collection rather than
 * following every collection focus move.
 *
 * The handoff was deliberate and it worked as designed — the scene behind followed the member you were
 * on. What that costs is a scene that re-renders under every DPAD press, and from the front it does not
 * read as a feature; it reads as the shelf behind rebuilding itself while you browse. So the collection's
 * own lead art now WINS, and the member hero is kept only as the fallback for a collection that has no
 * lead art of its own — without which this surface would go empty, which is worse than either.
 *
 * The function stays TOTAL and latch-free, which is the property the two failure modes below depend on.
 * Freezing the member input at open time would have been the other way to get a static scene, and it
 * would have put exactly the latch this KDoc exists to refuse back into the producer.
 *
 * Prior behaviour, kept because it was correct reasoning about a different question (RC-34 chose WHEN
 * the handoff fires, never WHETHER): `if (folioStageHandedOff) folioArt else tomeLeadArt`.
 */
internal fun homeShelfTomeStageArt(
    folioStageHandedOff: Boolean,
    folioArt: String?,
    tomeLeadArt: String?,
    /**
     * The Tome LIBRARY's focused-tome art. Policy 2026-08-27, *"one backdrop under both surfaces"*:
     * the Library used to paint its own full-screen host over the shelf's, so crossing that boundary
     * mounted and unmounted a whole backdrop. It now drives THIS stage instead, and there is one host.
     *
     * First in priority because it is the surface actually on top: while the Library is up, what the
     * player is looking at is the tome under their focus, not the shelf's held lead art beneath it.
     */
    libraryArt: String? = null,
    /**
     * CP01 §4.3: [libraryArt]'s settle gate, the RC-34 pattern restated for the Library rather than a new
     * primitive — see [homeShelfLibraryStageHandedOff]. Before this, [libraryArt] was first-priority and
     * gated on NOTHING, so the shared stage handed off to the Library's art the instant the Library asked
     * — while the Library's own pixels were still near alpha 0 — which measured as a +31% one-frame luma
     * flash (the neighbouring Folio hop already had this exact gate via [folioStageHandedOff]).
     *
     * Defaults to **true**, exactly today's unwired behaviour: an unwired caller keeps `libraryArt`
     * unconditional priority, as it always has.
     */
    libraryStageHandedOff: Boolean = true,
    /**
     * The Tome Library is on screen but has not handed off yet — the OPEN window, beat 1 into beat 2.
     *
     * Opening the Tome collection clears Home hero art; the Tome carousel hero arrives after a
     * short delay on landing so the two art transitions do not read as an uneven overlap.
     *
     * Without this the expression falls through to [tomeLeadArt] for the whole open — the art of the
     * surface you are LEAVING — and CP01's beat 1 then blurs it rather than removing it. Measured on the
     * Thor at 60 fps: the middle of the hop is **117 ms of a bright, detail-free wash of the outgoing
     * art**, backdrop luma climbing 36.6 → 45.2 while detail sits at ~0, then dropping to 36.8 as the
     * Tome's own art arrives. The hop ends 23% darker than its own middle, which is the "uneven" read.
     *
     * CP01 §4.3 predicted a residual excursion here and named `palette.signatureBottom` as the suspect.
     * **That was refuted by colour**: the peak measures RGB (45.55, 46.66, 37.15), carrying the Home
     * art's own hue balance at nearly its own brightness, while every theme's `signatureBottom` is
     * `0xFF0A0810`-dark — luma ≤ 15, three times darker than the peak and unable to produce it.
     *
     * So this REPLACES part of a established behavior rather than filling a gap: beat 3 deliberately keeps
     * the outgoing art until the cover is opaque and swaps beneath it, *"an audience of exactly
     * nobody"*. The measurement is why the replacement clears the bar — that middle is not an unwatched
     * swap, it is the brightest thing in the transition, and it belongs to the surface being left.
     *
     * Nulling here reaches `HomeShelfBackdrop`'s existing `nextUri == null` branch, which already fades
     * the outgoing art out and clears only once the composite is invisible. No new clock, no new token.
     */
    libraryOpening: Boolean = false,
    /**
     * The Library is up and has handed off, so the stage stands on what the Library shows. The shared background contract is: the Library's background is the game's hero art or, with none, the theme backdrop -- never the cover. A Library
     * that has handed off but has no art for the stage (its game has no hero) must therefore leave the stage on the theme, not fall
     * through to [tomeLeadArt], which for that game is the cover or icon the Library refuses to show.
     *
     * Defaults to false: an unwired caller keeps today's fall-through.
     */
    libraryThemeOnly: Boolean = false,
): String? = when {
    libraryArt != null && libraryStageHandedOff -> libraryArt
    // The open window: show nothing rather than the departing surface's hero.
    libraryOpening -> null
    // The Library has landed on a game without a hero: the theme, and only the Folio's own settled art may still win.
    libraryThemeOnly -> folioArt.takeIf { folioStageHandedOff }
    else -> tomeLeadArt ?: folioArt.takeIf { folioStageHandedOff }
}

/**
 * Whether the single Tome hero stage is mounted at all.
 *
 * Deliberately the union of the two conditions that used to select between two stages: the stage must
 * outlive the open and the close, or the merge above buys nothing — a stage that unmounts at either
 * edge is the same hard cut one step further along.
 */
internal fun homeShelfTomeStageMounted(
    folioActive: Boolean,
    tomeSelected: Boolean,
    /**
     * The Tome LIBRARY is up. Added 2026-08-27 under *"one backdrop under both surfaces"*: this
     * deliberately used to be EXCLUDED, because the Library owned a backdrop of its own and the shelf's
     * job was to hold still underneath it. With that second host gone, the Library is a third condition
     * this one stage must cover, for the same reason the other two are here.
     */
    tomeLibraryActive: Boolean = false,
): Boolean = folioActive || tomeSelected || tomeLibraryActive

/**
 * Whether the Tome hero stage must change its art **at rest** rather than crossfading it.
 *
 * The full argument is on [homeTomeStageHandoffMs]. On a return **the departure is the
 * subject and the destination arrives at rest**, and it measured what happens when both move — two
 * animations end to end, whose seam reads as jitter. Closing the Folio does exactly that today: the
 * stage art reverts on the commit frame and crossfades for [HomeTomePreviewFadeMs] while the Folio is still dissolving
 * over it.
 *
 * **Both terms are load-bearing, in opposite directions.**
 *
 * - [folioActive] must be **false**. While a Folio is open, art changes are game-to-game focus moves
 *   INSIDE it — ordinary navigation, not a return — and RC-34's whole point was that those crossfade
 *   rather than snap. Suppressing them would reinstate the art churn its KDoc warns about at length.
 * - [tomeReturnPending] must be **true**, and it is the only thing that distinguishes a return from any
 *   other reason the stage art might change with no Folio open.
 *
 * **The one case this deliberately also catches, named rather than left to be discovered:**
 * `beginHomeShelfTomeReturn` has a second caller — the pin/unpin projection settling
 * (`HomeShelfCoordinator.kt:2240`) — where no Folio is departing. That is still a return to a remembered
 * place, travelling in the same direction, so it takes the same grammar as `abandonDestinationPick`
 * and the no-commit-row branch: all return to a place the player has already visited.
 */
internal fun homeShelfTomeStageArrivesAtRest(
    folioActive: Boolean,
    tomeReturnPending: Boolean,
): Boolean = !folioActive && tomeReturnPending

/**
 * RC-50. Whether the Home shelf still paints its **live-input chrome** — the stationary focus ring, the
 * continuation runes, the focused tile's button hint — while FocusAuthority's active scope is no longer
 * [HomeShelfScope].
 *
 * **This is a SCOPE-CHECK of the existing two-channel law.** The two-channel law
 * (`core-input`'s `litFocusIn` / `renderedFocusIn`) rules the RING channel *not* conserved, and the
 * STATE-channel comment inside `HomeShelfScreen` repeats it. That law applies to the **dock and the
 * Utility modal** — small adjacent surfaces where the shelf stays fully visible beside them, so a ring
 * left lit is a second ring with no input behind it: the ghost trail the regression report described on device the
 * same day conservation shipped. It is correct there and this function does not touch it.
 *
 * **The Tome Folio is not that surface.** It is a full-surface glass cover that materialises over the
 * shelf on one clock (`MimirAppHost`'s `folioGlassRamp` / `mimirGlassBackdropProgress`). Nothing about
 * the shelf is supposed to change when it opens; the shelf is supposed to be *covered*. Same words,
 * different surface, different meaning — the same failure shape RC-49 found in the box-texture seam.
 *
 * **Why it is literally recomposition and not alpha.** `openTome` enters the Folio scope
 * (`HomeShelfCoordinator.enterHomeTomeScope`) **before** it assigns `tomeSession`, in the same
 * synchronous lambda, and `tomeFolioActive` is `tomeFolioState != null`, which is derived from
 * `tomeSession` in composition. So both land on ONE commit frame — and on that frame the glass progress
 * is still **0**. Without this conservation the ring, the runes and the hint all unmount over a
 * still-sharp, still-unscrimmed shelf. That pop *is* the "re-render mid split".
 *
 * **The return needs no third term, and one was deliberately rejected.** `restoreRootEntry` pops the
 * scope, nulls the session and begins the return in one synchronous lambda, so those also land on one
 * commit frame. `popScope` restores `focusMemory[HomeShelfScope]` — still valid, because
 * `RegisterVirtualFocusRow` keeps the shelf's keys registered underneath the Folio — and `selectedItem`
 * is derived from `focusState.focused`, so the lit read is true on the exact frame [folioActive] drops.
 * The channel is continuous across both edges from [folioActive] alone.
 *
 * A `tomeReturnPending` term was checked first and is **disqualified**:
 * `beginHomeShelfTomeReturn`'s second caller is the pin/unpin projection settling
 * (`HomeShelfCoordinator.kt:2240`), which fires while the **Tome Library overlay** owns input. Adding
 * that term would light the shelf's ring under a live overlay — the exact ghost the ring-channel rule above
 * forbids. It is not merely unnecessary; it would reintroduce the defect the scope boundary prevents.
 *
 * **The dock and Utility stay byte-identical by construction, not by care:** neither ever sets
 * [folioActive], so for both this reduces to [scopeOwnsInput] — today's `isLitFocusIn` read exactly.
 * `HomeShelfEmptyAction`'s accent fill is deliberately NOT routed through here: it lives in the
 * `orderedItems.isEmpty()` branch, which no Folio can be open over, so wiring it would be an unverified
 * claim rather than a fix.
 */
internal fun homeShelfLiveChromeLit(
    scopeOwnsInput: Boolean,
    folioActive: Boolean,
): Boolean = scopeOwnsInput || folioActive

/**
 * The Folio's half of the same two-channel law, for the ONE frame a departing Folio still paints.
 *
 * The pixel-handoff design gives the Folio a departure made of recorded
 * pixels. The pixels that get recorded are the last frame the Folio actually DREW, and `restoreRootEntry`
 * pops the scope, nulls the session and begins the return **in one synchronous lambda**
 * (`HomeShelfCoordinator.kt`), so without this the last drawn frame is the one drawn *before* the press —
 * member ring lit — and the dissolving layer then drags a second gold ring across the Home shelf for the
 * whole hold. The shelf's own ring is lit on that same frame ([homeShelfLiveChromeLit] above, plus
 * `popScope` restoring `focusMemory[HomeShelfScope]`), so it really is two rings, not one moving.
 *
 * The law that decides it is already committed and needs no separate policy (`core-input`'s
 * `litFocusIn`/`renderedFocusIn`, restated on [homeShelfLiveChromeLit]): **on a scope pop the RING channel
 * is gone immediately; the STATE channel is conserved.** This function is the RING half.
 *
 * [departing] is not "the return is in flight" — it is strictly "this Folio composition has already lost
 * its scope and is only being drawn so its pixels can be captured". Exactly one frame wears it.
 */
internal fun homeTomeFolioRingLit(
    scopeOwnsFolio: Boolean,
    departing: Boolean,
): Boolean = scopeOwnsFolio && !departing

/**
 * The STATE half of the same law, and the reason [homeTomeFolioRingLit] is not simply reused for
 * everything the Folio draws from focus.
 *
 * The recorded frame has one job: to be **the frame the player was already looking at, minus the ring**.
 * Anything else that changes between the last live frame and the recorded one is a content pop baked into
 * the departure — the footer's focused-member title vanishing is the concrete one this exists for, because
 * it reads `focusState.scope == HomeTomeFolioScope` for a purely *stateful* purpose (which member is
 * selected) rather than for a lit one.
 *
 * So the STATE channel is conserved across the departing frame exactly as it is conserved across the
 * OPENING commit frame one function above. Same law, same shape, opposite edge.
 */
internal fun homeTomeFolioStateChannelLit(
    scopeOwnsFolio: Boolean,
    departing: Boolean,
): Boolean = scopeOwnsFolio || departing

private data class PresentedHomeTomePreview(
    val tomeId: TomeId,
    val preview: HomeTomePreview,
)

internal enum class HomeTileHintKind { GAME, TOME }

/** One hint beside a keycap on the focused-tile pill: [glyph] is the controller token (never translated), [labelRes] the word beside it. */
internal data class HomeTileHintAction(val glyph: String, @StringRes val labelRes: Int)

internal fun homeTileFocusHintActions(
    kind: HomeTileHintKind,
    viewModeSelectAvailable: Boolean = false,
): List<HomeTileHintAction> {
    val primary = when (kind) {
        HomeTileHintKind.GAME -> HomeTileHintAction(glyph = "Y", labelRes = R.string.library_home_hint_edit)
        HomeTileHintKind.TOME -> HomeTileHintAction(glyph = "A", labelRes = R.string.library_home_hint_open)
    }
    // The temporary focused-tile hint owns only the tile action and, when live, directional mode.
    // Tome discovery now has one permanent footer affordance, so repeating X here would advertise the
    // same action twice. (never destination copy for the shoulders —
    // they SELECT, they do not describe a single target.)
    return if (viewModeSelectAvailable) {
        listOf(primary, HomeTileHintAction(glyph = "L/R", labelRes = R.string.library_home_hint_mode))
    } else {
        listOf(primary)
    }
}

private data class HomeTileFocusHintTarget(
    val focusKey: FocusKey,
    val kind: HomeTileHintKind,
)

/**
 * Curated game-first root surface. The caller applies window insets once before this composable; every
 * measurement below is solved from those post-inset constraints.
 */
@Composable
fun HomeShelfScreen(
    rows: List<HomeShelfRow>,
    entries: List<HomeShelfEntry> = rows.map { HomeShelfEntry.Game(it.itemId) },
    tomes: Map<TomeId, HomeShelfTome> = emptyMap(),
    authority: FocusAuthority,
    onLaunch: (HomeShelfRow) -> Unit,
    onOpenTome: (TomeId) -> Unit = {},
    tomeReturn: HomeShelfTomeReturn? = null,
    tomeReturnRootReady: Boolean? = null,
    isTomeReturnCurrent: (Long) -> Boolean = { false },
    onFinishTomeReturn: (Long, HomeShelfTomeReturnOutcome) -> Unit = { _, _ -> },
    onOpenLibrary: () -> Unit,
    onQuickAction: (MimirQuickRailAction) -> Unit,
    onQuickRailTouch: ((MimirQuickRailAction, MimirQuickRailTouchPhase, Offset) -> Unit)? = null,
    /** the dock's Files seat is withdrawn while a game runs ([homeShelfQuickActions]); the app answers from its session ledger. */
    filesSeatOffered: Boolean = true,
    modifier: Modifier = Modifier,
    hint: String? = null,
    toast: MimirToastMessage? = null,
    onToastDelivered: (Long) -> Unit = {},
    reducedMotion: Boolean = false,
    restorationFocusKey: FocusKey? = null,
    surfaceLabel: String? = null,
    viewModeSelectAvailable: Boolean = false,
    surfaceEpoch: Int = 0,
    /**
     * Which way the last surface change went (motion audit batch D): +1 for R1 (Pinned Tomes arrives from the right),
     * -1 for L1, 0 for a restore or return that no press caused (it fades in place, nothing departs).
     */
    surfaceHandoffDirection: Int = 0,
    // FUX-F Hearth Home backdrop. Defaults keep every existing caller/test byte-identical: with mode
    // OFF the wrapper never mounts and today's plain Home scene is unchanged. The former
    // `homeBackdropEnabled` build kill-switch is gone — Settings is the sole authority, so this mode IS the on/off state.
    backdropMode: GamelistBackdropMode = GamelistBackdropMode.OFF,
    /**
     * Active Folio focus projects an existing local Hero; null means an honest no-media well.
     *
     * RC-50 gave this a second, unrelated duty and it is named here so the next reader does not have to
     * infer it: it is also the shelf's only signal that a **full-surface cover** owns input, which is what
     * lets the live-input chrome stay painted instead of unmounting on the tome's first commit frame (see
     * [homeShelfLiveChromeLit]). Any future caller wiring this false while a Folio is genuinely up would
     * silently reintroduce the re-render the regression report described, not merely lose a backdrop.
     */
    tomeFolioActive: Boolean = false,
    /**
     * A Tome surface other than the Folio owns the screen — today, the Tome Library.
     *
     *  The static-scene rule was first built for the OPENED collection only, and
     * that was the smaller half: moving between tome CARDS re-selects a tome, which re-picks the lead
     * art, which crossfades the scene on every press.
     */
    tomeLibraryActive: Boolean = false,
    /**
     * The Tome Library's focused-tome art, hoisted out of that overlay so ONE host paints both surfaces
     * (policy 2026-08-27, *"one backdrop under both surfaces"*). Null while the Library is closed,
     * or before its focused tome has resolved a preview -- the stage then falls back to the shelf's own
     * still rather than unmounting, because an unmount is the cut this change exists to remove.
     */
    tomeLibraryStageArt: String? = null,
    /**
     * CP01 §4.3: the Tome LIBRARY's own open arrival has finished — the mirror of [tomeFolioOpenSettled]
     * for [tomeLibraryStageArt], feeding [homeShelfLibraryStageHandedOff]. Same reason this screen cannot
     * compute it either: the arrival is `MimirDirectOverlayPixelFadeHost`'s incoming alpha in
     * `MimirAppHost.kt`, a sibling composable this screen cannot observe. Defaults to **true**, exactly
     * today's unwired behaviour (see [homeShelfTomeStageArt]'s `libraryStageHandedOff` default).
     */
    tomeLibraryOpenSettled: Boolean = true,
    /**
     * RC-34: the Tome's opening has run to completion and settled — NOT that a Folio session exists.
     *
     * [tomeFolioActive] is a presence test that is true on the first frame of activation, so using it to
     * hand the hero stage over swaps the art while the Tome is still opening. That is the snap observed on device. This parameter is the settled test that presence was standing in for.
     *
     * **This screen cannot compute it, and deliberately does not try.** The opening is
     * `AnimatedVisibility(visible = folio != null, enter = fadeIn(tween(MimirMotion.SceneMs)))` in
     * `MimirAppHost.kt` — this screen's SIBLING under the host's `Box`, not its child. A sibling cannot
     * observe another layer's transition, and re-running the same tween here off the same boolean would
     * be a second clock on one surface, which this tree already rules against by name
     * (`BrightnessOverlay.kt:480`, *"a second rememberMimirGlassProgress here would be two clocks on one
     * surface, and they drift"*) — and would in fact drift, since a `LaunchedEffect` mirror starts a
     * frame behind the parent's transition. A duration constant here would be worse still: a guessable
     * second source of truth sitting beside a real one.
     *
     * So the value is the host's to supply, from the opening's OWN completion
     * (`transition.currentState == EnterExitState.Visible`, available only inside that
     * `AnimatedVisibility` scope) conjoined with the Folio's existing motion-true settlement
     * (`HomeTomeFolioUiState.focusSettledForActivation`), invalidated on Folio identity so an
     * open→close→open burst cannot reuse the previous open's settlement.
     *
     * **It must LATCH on that conjunction, never track it.** `focusSettledForActivation` is deliberately
     * unsettled on EVERY authoritative focus change — that is `HomeTomeFolio.onFocusUnsettled`'s whole
     * job, and settlement re-opens a frame later. A host that assigns this parameter the live
     * conjunction therefore drops it for a frame or two on every game-to-game move INSIDE an
     * already-open Folio, which would hand the stage back to the collection hero and crossfade it
     * forward again on each move: art churn, worse than the snap it was meant to fix, and invisible to
     * the reducers below because they are stateless by design. Latch true when the conjunction first
     * holds; un-latch only when the Folio's identity changes (including to none).
     *
     * Defaults to **true**, which is exactly today's behaviour: an unwired host hands off on presence as
     * it always has, and the merge of the two hero stages below still removes the hard cut. Wiring this
     * is what removes the snap.
     */
    tomeFolioOpenSettled: Boolean = true,
    tomeFolioFocusedMember: HomeTomeMember? = null,
    launchingRow: HomeShelfRow? = null,
    launchReturnRestoring: Boolean = false,
    /** FUX-12p: per-platform launch-status line, so the focused hero shows a Ready/etc badge. */
    launchStatusLines: Map<PlatformId, String> = emptyMap(),
    /**
     * OR-1 : the focused Editor's Choice game's Tome mark ("In Cabinet", "In 2 Tomes"), or null.
     * Resolved app-side; this screen only places it, last in the status seat, and never over an open Tome.
     */
    tomeMark: String? = null,
) {
    DisposableEffect(authority) {
        authority.pushScope(HomeShelfScope)
        onDispose {
            while (
                authority.state.value.scope != FocusScope.Root &&
                authority.state.value.scope in HomeShelfScopes
            ) {
                authority.popScope()
            }
        }
    }

    val canonicalRows = remember(rows) { canonicalHomeShelfRows(rows) }
    val rowsById = remember(canonicalRows) { canonicalRows.associateBy(HomeShelfRow::itemId) }
    val gameRowsByFocusKey = remember(canonicalRows) { canonicalRows.associateBy(HomeShelfRow::focusKey) }
    val orderedItems = remember(entries, rowsById, tomes) {
        entries.mapNotNull { entry ->
            when (entry) {
                is HomeShelfEntry.Game -> rowsById[entry.itemId]?.let(HomeShelfDisplayItem::Game)
                is HomeShelfEntry.Tome -> tomes[entry.tomeId]?.let(HomeShelfDisplayItem::Tome)
            }
        }
    }
    val itemKeys = remember(orderedItems) { orderedItems.map { it.focusKey } }
    RegisterVirtualFocusRow(
        authority = authority,
        scope = HomeShelfScope,
        keys = itemKeys,
        // Exact return still seeds its durable identity first, but every typed destination remains
        // registered so a second burst/hold event cannot outrun the one-item compositional buffer.
        preferredRegistrationKey = restorationFocusKey,
    )

    val focusState by authority.state.collectAsState()
    var presentedTomePreview by remember { mutableStateOf<PresentedHomeTomePreview?>(null) }
    val rememberedContentKey = when (focusState.scope) {
        HomeShelfScope -> focusState.focused ?: authority.rememberedFocusIn(HomeShelfScope)
        else -> authority.rememberedFocusIn(HomeShelfScope)
    }
    val selectedIndex = orderedItems
        .indexOfFirst { it.focusKey == rememberedContentKey }
        .takeIf { restorationFocusKey == null && it >= 0 }

    // A removed remembered key intentionally prevents DefaultFocusAuthority from seeding a sibling. Once
    // the incoming ring is registered, land directly on its valid fallback without waiting another frame.
    LaunchedEffect(itemKeys, focusState.scope, focusState.focused, restorationFocusKey, tomeReturn?.generation) {
        if (restorationFocusKey != null) return@LaunchedEffect
        val pendingReturn = tomeReturn
        if (pendingReturn != null && isTomeReturnCurrent(pendingReturn.generation)) {
            return@LaunchedEffect
        }
        val current = authority.state.value
        if (current.scope != HomeShelfScope) return@LaunchedEffect
        val rememberedFocus = authority.rememberedFocusIn(HomeShelfScope).takeIf { it in itemKeys }
        val desiredFocus = current.focused?.takeIf { it in itemKeys }
            ?: rememberedFocus
            ?: itemKeys.firstOrNull()
            ?: HomeShelfOpenLibraryKey
        val validFocus = current.focused == desiredFocus ||
            (itemKeys.isEmpty() && current.focused == HomeShelfOpenLibraryKey)
        if (!validFocus) {
            authority.requestFocus(desiredFocus)
        }
    }

    val homeRailModifier = Modifier.mimirFocusable(
        authority = authority,
        key = homeShelfQuickRailKey(MimirQuickRailAction.HOME),
        scope = HomeShelfQuickRailScope,
        autoBringIntoView = false,
    )
    val libraryRailModifier = Modifier.mimirFocusable(
        authority = authority,
        key = homeShelfQuickRailKey(MimirQuickRailAction.LIBRARY),
        scope = HomeShelfQuickRailScope,
        autoBringIntoView = false,
    )
    val brightnessRailModifier = Modifier.mimirFocusable(
        authority = authority,
        key = homeShelfQuickRailKey(MimirQuickRailAction.BRIGHTNESS),
        scope = HomeShelfQuickRailScope,
        autoBringIntoView = false,
    )
    val settingsRailModifier = Modifier.mimirFocusable(
        authority = authority,
        key = homeShelfQuickRailKey(MimirQuickRailAction.SETTINGS),
        scope = HomeShelfQuickRailScope,
        autoBringIntoView = false,
    )
    val quickLaunchRailModifier = Modifier.mimirFocusable(
        authority = authority,
        key = homeShelfQuickRailKey(MimirQuickRailAction.QUICK_LAUNCH),
        scope = HomeShelfQuickRailScope,
        autoBringIntoView = false,
    )
    // The Files end-cap is on the HOME rail too, and that is the point of it rather than an extension
    // of it: limiting the icon to Library would require an extra destination change to reach Files.
    // registered only while the seat is offered. The registration lives in THIS call (its DisposableEffect), not in the rail,
    // so a withdrawn seat that stayed registered kept a dead focus: nothing lit, A dead, and a remembered Files restored onto it on the
    // next dock entry. Unregistering drops a lit Files onto its neighbour, the authority's own fall.
    val filesRailModifier = if (filesSeatOffered) {
        Modifier.mimirFocusable(
            authority = authority,
            key = homeShelfQuickRailKey(MimirQuickRailAction.FILES),
            scope = HomeShelfQuickRailScope,
            autoBringIntoView = false,
        )
    } else {
        Modifier
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .testTag(HomeShelfRootTestTag),
    ) {
        val viewport = rememberMeasuredViewport(
            maxWidth = maxWidth,
            maxHeight = maxHeight,
            inDomain = { widthDp, heightDp -> widthDp >= 320f && heightDp >= 300f },
            beforeFirstValid = MimirBeforeFirstValidViewport.FATAL,
        )
        if (!viewport.shouldRender) return@BoxWithConstraints

        val rootProfile = LocalMimirViewportProfile.current
        val compactViewport = if (rootProfile.widthDp > 0f) {
            rootProfile.isCompact
        } else {
            mimirViewportProfile(viewport.width.value, viewport.height.value).isCompact
        }
        // on a 4:3 or square field the Default sun moves below the text
        // column. Decided from the same profile as `compactViewport`, never from a device model.
        val sceneLightBelowText = if (rootProfile.widthDp > 0f) {
            rootProfile.sceneLightBelowText
        } else {
            mimirViewportProfile(viewport.width.value, viewport.height.value).sceneLightBelowText
        }

        val geometry = remember(viewport.width, viewport.height, compactViewport) {
            solveHomeShelfGeometry(viewport.width.value, viewport.height.value, compact = compactViewport)
        }
        // Mode changes fade the authored strip/title over the house scene clock. The atmosphere and rail
        // remain mounted, avoiding the full-screen brightness flash a veil would introduce.
        val initialSurfaceEpoch = remember { surfaceEpoch }
        // A shoulder that turns back to the band still leaving (R1 then L1 inside the 180 ms exit) carries that band on
        // from the alpha it was painted at, as the Library's strip does, instead of blinking it out and bringing it in
        // again after the lead. Read from the handoff's plain arrays, never an Animatable, so the exit does not
        // recompose Home. The travel needs no seed: the exit eases alpha 1->0 and travel 0->exit on one tween, so the
        // band's painted travel is exactly bandArrivalFromPx * (1 - alpha) for the reversed shoulder.
        val bandHandoff = remember { MimirPageHandoffState() }
        val modeReturningAlpha = remember(surfaceEpoch) {
            if (surfaceHandoffDirection != 0 && !reducedMotion) bandHandoff.returningAlpha(surfaceLabel) else null
        }
        val surfaceFade = remember(surfaceEpoch) {
            Animatable(if (surfaceEpoch == initialSurfaceEpoch || reducedMotion) 1f else modeReturningAlpha ?: 0f)
        }
        // Motion audit batch D: an L1/R1 switch is a page change. The outgoing band leaves on the
        // press ([MimirPageHandoff] below); this arrival waits for the 130 ms lead and for its focus to register, then
        // fades and glides in on the slow clock. Presses wait until it shows. A restore fades in place as before.
        val modeHandoff = surfaceHandoffDirection != 0
        var modeArrived by remember(surfaceEpoch) { mutableStateOf(surfaceEpoch == initialSurfaceEpoch || reducedMotion) }
        // The touch half of the gate: the band's tiles hit-test at rest while it is hidden, so touches wait with the
        // pad. Up on the flip frame itself, and dropped where the gate is released.
        var modeShielding by remember(surfaceEpoch) { mutableStateOf(modeHandoff && !modeArrived) }
        // An empty band that finished its arrival is already on screen (Editor's Choice on a library still scanning).
        // Its first games only take focus: a late scan is not a second handoff, so no veil, no held pad, no re-fade.
        var modeShownEmpty by remember(surfaceEpoch) { mutableStateOf(false) }
        val modeGateOwner = remember(surfaceEpoch) { Any() }
        LaunchedEffect(surfaceEpoch, reducedMotion, itemKeys) {
            suspend fun registerArrivalFocus() {
                for (attempt in 0 until 8) {
                    // Re-read memory on every frame: a HAT edge can legitimately retarget the
                    // destination while its rings are registering, and that newer identity wins.
                    val desired = authority.rememberedFocusIn(HomeShelfScope).takeIf { it in itemKeys }
                        ?: itemKeys.first()
                    if (authority.state.value.scope == HomeShelfScope &&
                        authority.state.value.focused == desired
                    ) {
                        break
                    }
                    authority.requestFocus(desired)
                    withFrameNanos { }
                }
            }
            if (surfaceEpoch == initialSurfaceEpoch || reducedMotion) {
                surfaceFade.snapTo(1f)
                modeArrived = true
                modeShielding = false
            } else if (modeArrived) {
                // A list change after the arrival (a favourite, say) is not a new handoff: no veil, no re-fade.
                surfaceFade.snapTo(1f)
            } else if (modeShownEmpty) {
                surfaceFade.snapTo(1f)
                if (itemKeys.isNotEmpty()) {
                    registerArrivalFocus()
                    modeArrived = true
                }
            } else try {
                // A re-run inside the same epoch (the list changed while the band was arriving) finds the band already
                // painting. It carries on from that alpha, as a reversal does, and does not snap to 0 and play the lead
                // and the fade again (alpha audit F5). `itemKeys` stays a key: the empty arrival above needs the re-run.
                val carryOn = homeArrivalCarriesOn(modeReturningAlpha, surfaceFade.value)
                if (modeHandoff) MimirTransitionInputGate.hold(modeGateOwner)
                modeShielding = modeHandoff
                // Keep the destination tree hidden until its remembered identity is both registered and
                // authoritative. A shoulder/HAT edge in this gap updates that memory synchronously, so the
                // first painted destination frame cannot briefly show item zero or the outgoing mode.
                // A reversal, or a band this arrival was already painting, keeps where it was painted; anything else
                // starts hidden.
                if (!carryOn) surfaceFade.snapTo(0f)
                val flipNs = withFrameNanos { it }
                if (itemKeys.isNotEmpty()) {
                    registerArrivalFocus()
                } else {
                    withFrameNanos { }
                }
                if (modeHandoff && !carryOn) {
                    // The outgoing band leaves on the press; the arrival starts at the lead, never over it. A band already
                    // on screen (a reversal, or this arrival run again) carries on at once.
                    while (withFrameNanos { it } - flipNs < MimirPageMotion.LeadMs * 1_000_000L) Unit
                }
                surfaceFade.animateTo(
                    1f,
                    tween(if (modeHandoff) MimirPageMotion.ArriveMs else HomeShelfModeHandoffMs, easing = MimirMotion.Easing),
                ) {
                    if (value > 0f) MimirTransitionInputGate.release(modeGateOwner)
                    if (value > 0f && modeShielding) modeShielding = false
                }
                // Only a band that arrived with its games counts: a list that lands after an empty arrival still
                // registers its focus, but the band is already showing, so it does not fade in a second time.
                modeArrived = itemKeys.isNotEmpty()
                modeShownEmpty = itemKeys.isEmpty()
            } finally {
                MimirTransitionInputGate.release(modeGateOwner)
                modeShielding = false
            }
        }
        // Read only in draw-phase and deferred lambdas: a composition read
        // here recomposed the whole shelf on every frame of the 160 ms switch.
        val surfaceFadeHero: () -> Float = remember(surfaceFade) { { surfaceFade.value } }
        val modeTravelPx = with(LocalDensity.current) { MimirMotion.GlideTravelDp.dp.toPx() }
        val bandArrivalFromPx = MimirPageMotion.arrivalTravelPx(surfaceHandoffDirection, modeTravelPx)
        val selectedItem = selectedIndex?.let(orderedItems::getOrNull)
        val selectedRow = (selectedItem as? HomeShelfDisplayItem.Game)?.row
        val selectedTome = (selectedItem as? HomeShelfDisplayItem.Tome)?.tome
        // THE ONE LATCH, and it is deliberately here rather than in the reducer.
        //
        // [homeShelfTomeStageArt] stays total and latch-free -- that is the property which makes its
        // reversal and burst failures unrepresentable, and it is not worth trading for this. What a
        // static scene actually needs is one fact those functions cannot express: the art as it stood
        // when a Tome surface took the screen. So the value is HELD here, at the single point where it
        // is chosen, and every consumer downstream keeps taking a plain String?.
        //
        // It updates freely whenever no Tome surface is up, so there is nothing stale to leak: the held
        // value is always the last thing the shelf actually painted before the surface opened.
        // A Library visit moves this Tome's lead art onto the Library's rule (hero or theme) until the selection moves on.
        var libraryVisited by remember { mutableStateOf(false) }
        val liveTomeLeadArt = homeShelfTomeLeadArt(
            presentedTomePreview
                ?.takeIf { it.tomeId == selectedTome?.tomeId }
                ?.preview,
            libraryVisited = libraryVisited,
        )
        val tomeSurfacePresented = tomeFolioActive || tomeLibraryActive
        val heldTomeLeadArt = remember { mutableStateOf(liveTomeLeadArt) }
        if (!tomeSurfacePresented) heldTomeLeadArt.value = liveTomeLeadArt
        val selectedTomeLeadArt = if (tomeSurfacePresented) heldTomeLeadArt.value else liveTomeLeadArt
        LaunchedEffect(selectedTome?.tomeId) {
            if (presentedTomePreview?.tomeId != selectedTome?.tomeId) presentedTomePreview = null
        }
        // UI-02, 2026-08-14: a Tome return briefly moved the ring to tile one before cancelling that move.
        //
        // NOT an animation defect. The ring is animating CORRECTLY, to a WRONG intermediate position, so
        // smoothing it would only make the wrong movement prettier. The wrong position comes from this
        // surface having spelled "the selection has not RESOLVED yet" as the integer `0` in two places --
        // here (the window anchor) and at the emphasis seat below -- and `0` is not a neutral value on a
        // shelf: it is the FIRST TILE. Both fallbacks feed motion, so an unknown selection does not read
        // as "nothing highlighted", it reads as "the player is on tile 1", and the ring travels there.
        //
        // The unresolved window is real and unavoidable, not a bug to remove: on return, `selectedIndex`
        // is null whenever `restorationFocusKey != null` (:313), and again whenever `focusState.focused`
        // is a key the incoming `orderedItems` no longer contains -- which is routine, because
        // `restoreRootEntry` pops the Tome scope (landing focus from the OLD memory) BEFORE it stamps the
        // key it actually wants. The exact-return effect below then repairs it over up to 8 frames. That
        // repair is correct; what made it VISIBLE was these two `?: 0`s painting tile 1 in the meantime.
        //
        // So: hold the last RESOLVED position until the real one arrives, rather than inventing one.
        // First paint is unchanged (nothing resolved yet, holder is 0, and the animatable below starts AT
        // its target so there is no travel) -- the two differ only once a selection has existed and then
        // goes transiently unknown, which is precisely the defect window. Same reason the band still
        // renders side tiles rather than blank-flashing: degrade by staying put, never by teleporting.
        LaunchedEffect(
            itemKeys,
            restorationFocusKey,
            tomeReturn?.generation,
            tomeReturnRootReady,
        ) {
            if (restorationFocusKey != null) return@LaunchedEffect
            val pendingReturn = tomeReturn ?: return@LaunchedEffect
            val generation = pendingReturn.generation
            if (!isTomeReturnCurrent(generation)) return@LaunchedEffect
            if (tomeReturnRootReady == null) return@LaunchedEffect
            if (tomeReturnRootReady != true || pendingReturn.focusKey !in itemKeys) {
                if (isTomeReturnCurrent(generation)) {
                    onFinishTomeReturn(generation, HomeShelfTomeReturnOutcome.FAILED)
                }
                return@LaunchedEffect
            }
            repeat(8) {
                withFrameNanos { }
                if (!isTomeReturnCurrent(generation)) return@LaunchedEffect
                authority.requestFocus(pendingReturn.focusKey)
                if (!isTomeReturnCurrent(generation)) return@LaunchedEffect
                if (authority.state.value.focused == pendingReturn.focusKey) {
                    if (isTomeReturnCurrent(generation)) {
                        onFinishTomeReturn(generation, HomeShelfTomeReturnOutcome.SETTLED)
                    }
                    return@LaunchedEffect
                }
            }
            if (isTomeReturnCurrent(generation)) {
                onFinishTomeReturn(generation, HomeShelfTomeReturnOutcome.FAILED)
            }
        }
        // The strip -- its window, its touch movers and the eased seat the ring sits on -- is the shared GameShelf's
        // (GameShelf.kt). It is resolved here, in this composition, because the Hearth backdrop below follows the seat.
        val shelfItems = remember(orderedItems) { orderedItems.map(HomeShelfDisplayItem::toGameShelfItem) }
        val shelfState = rememberGameShelfState(
            items = shelfItems,
            selectedKey = selectedItem?.focusKey,
            geometry = geometry,
            windowMemory = HomeShelfWindowMemory.memory,
            windowMemoryKey = HomeShelfWindowMemory.key(surfaceEpoch, surfaceLabel),
            surfaceEpoch = surfaceEpoch,
            reducedMotion = reducedMotion,
        )
        val visualSelectedSlotOffset = shelfState.visualSelectedSeat
        // FUX-F Hearth backdrop mount gate (eligibility ladder — each failure leaves today's plain scene):
        // kill switch off · mode OFF/AURORA (homeBackdropActive) · reduced motion · Android focused row ·
        // power-save or no local clip (eligibleBifrostVideoUri). Only a settled emulated row with a real
        // local preview clip mounts the wrapper; otherwise MimirShelfBackground below is byte-identical.
        val localContext = LocalContext.current
        val backdropPowerSave = remember {
            (localContext.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true
        }
        val backdropMount = homeBackdropMount(
            mode = backdropMode,
            reducedMotion = reducedMotion,
            focusedItemId = selectedRow?.itemId,
            focusedPreviewVideoUri = selectedRow?.previewVideoUri,
            powerSave = backdropPowerSave,
            chosenHeroUri = selectedRow?.chosenHeroUri ?: selectedTomeLeadArt,
            chosenPreviewVideoUri = selectedRow?.chosenPreviewVideoUri,
            focusedHeroUri = selectedRow?.heroArtUri,
        )
        val tomeFolioBackdropArt = homeTomeFolioBackdropArt(tomeFolioFocusedMember)
        // RC-34: the hero hands over when the Tome has finished OPENING, not when its session appears.
        val folioStageHandedOff = homeShelfFolioStageHandedOff(
            folioActive = tomeFolioActive,
            folioOpenSettled = tomeFolioOpenSettled,
        )
        // CP01 §4.3: same rule, restated for the Library -- the shared stage hands off to libraryArt only
        // once the Library's own arrival is opaque, not the instant it asks.
        // the Library's hero lands a beat AFTER its surface is opaque. Held in
        // a latch fed BY the settle signal (never beside it -- see TomeLibraryArtLandingDelayMs), and
        // reset the moment settlement drops so a close/reopen inside the window cannot land early.
        var libraryArtLanded by remember { mutableStateOf(false) }
        LaunchedEffect(tomeLibraryOpenSettled) {
            if (!tomeLibraryOpenSettled) {
                libraryArtLanded = false
                return@LaunchedEffect
            }
            delay(TomeLibraryArtLandingDelayMs)
            libraryArtLanded = true
        }
        val libraryStageHandedOff = homeShelfLibraryStageHandedOff(
            libraryOpenSettled = libraryArtLanded,
        )
        // The open window: the Library is up, its art has not landed. The outgoing hero is banished for
        // exactly this span rather than being blurred and carried through it.
        val libraryOpening = tomeLibraryActive && !libraryStageHandedOff
        LaunchedEffect(tomeLibraryActive, libraryStageHandedOff) {
            if (tomeLibraryActive && libraryStageHandedOff) libraryVisited = true
        }
        LaunchedEffect(selectedTome?.tomeId) {
            if (!tomeLibraryActive) libraryVisited = false
        }
        val tomeStageArt = homeShelfTomeStageArt(
            folioStageHandedOff = folioStageHandedOff,
            folioArt = tomeFolioBackdropArt,
            tomeLeadArt = selectedTomeLeadArt,
            libraryArt = tomeLibraryStageArt,
            libraryStageHandedOff = libraryStageHandedOff,
            libraryOpening = libraryOpening,
            libraryThemeOnly = tomeLibraryActive && libraryStageHandedOff,
        )
        // The return's destination arrives at rest — see homeShelfTomeStageArrivesAtRest.
        val tomeStageArrivesAtRest = homeShelfTomeStageArrivesAtRest(
            folioActive = tomeFolioActive,
            tomeReturnPending = tomeReturn != null,
        )
        // Chrome follows the art that is actually PAINTED. Reporting on the Folio's hero while the stage
        // still holds the collection hero would flip the localized-contrast title rail a beat ahead of
        // the media — a second snap, on exactly the metallic themes that depend on that rail.
        val mediaBackdropAvailable = if (tomeFolioActive) {
            tomeStageArt != null
        } else {
            backdropMount != null || selectedTomeLeadArt != null
        }
        val tomeSurfaceActive = homeShelfTomeStageMounted(
            folioActive = tomeFolioActive,
            tomeSelected = selectedTome != null,
            tomeLibraryActive = tomeLibraryActive,
        )
        // A Home view-mode switch is a chrome/projection handoff, not a media-context change. Key the
        // decoder/still settle gate to the actual media identity so switching modes never dark-gates an
        // unchanged hero, while a genuinely different clip/still retains the existing bleed protection.
        val backdropContextEpoch = remember(
            folioStageHandedOff,
            tomeFolioFocusedMember?.itemId,
            tomeStageArt,
            selectedTome?.tomeId,
            backdropMount,
        ) {
            if (folioStageHandedOff) {
                // Keyed on the ART ALONE since the static-scene rule above. Including the focused
                // member would re-gate the decoder on every move through the folio and reintroduce the
                // churn from the other side -- the picture would hold still while the settle gate
                // flickered underneath it.
                tomeStageArt?.hashCode() ?: 0
            } else {
                selectedTome?.tomeId?.stableValue()?.hashCode() ?: (backdropMount?.hashCode() ?: 0)
            }
        }

        MimirShelfBackground(
            modifier = Modifier
                .mimirMeasuredViewport(viewport)
                .testTag(HomeShelfMeasuredViewportTestTag)
                .fillMaxSize(),
            showAtmosphericClouds = false,
            // on a compact square the Default sun sits under the dock; dimmed, not moved.
            quietSceneLight = compactViewport,
            sceneLightBelowText = sceneLightBelowText,
        ) {
            // FUX-07: the scene's accent-tinted center stage bloom clashes with the accent
            // focus ring — on HOME the center blends back into the field gradient instead (a
            // surfaceBase radial over the bloom, fading out). Library keeps its atmosphere untouched.
            val palette = LocalMimirPalette.current
            val lightMediaChrome = homeShelfShowsMediaTitleRail(
                palette = palette,
                mediaBackdropAvailable = mediaBackdropAvailable,
                tomeSurfaceActive = tomeSurfaceActive,
            )
            //  This veil already existed for FUX-07's centre-bloom clash, but it fell to ZERO
            // at the edges -- and the edges are exactly where the scenes put their subjects. The Well's
            // sun sits at roughly (0.66, 0.81) and Niflheim's bank runs off both sides, so the one part of
            // the field the veil did not cover was the part with something painted in it, which is what
            // was showing through the hero art.
            //
            // Raised throughout AND given a floor at the rim, so it is a veil over the whole field rather
            // than a spot over the middle. Deliberately still a gradient: flat would dull the centre, and
            // the centre is where the focused tile needs the field to recede. LIBRARY is untouched -- it
            // has no hero art to compete with, and the scenes are meant to be seen there.
            val centerBlend = remember(palette) {
                Brush.radialGradient(
                    0f to palette.surfaceBase.copy(alpha = 0.72f),
                    0.55f to palette.surfaceBase.copy(alpha = 0.54f),
                    1f to palette.surfaceBase.copy(alpha = 0.36f),
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind { drawRect(centerBlend) },
            )
            // The Hearth backdrop is the LOWEST layer of the HOME scene — over the atmosphere field, under
            // the title/tiles/hero/glow/dock. When absent the atmosphere shows through unchanged; idle-sleep
            // fades this wrapper to alpha 0, which also reveals the atmosphere beneath.
            // ONE call site for the still host. Compose keys composition groups by CALL SITE, so the
            // same composable written in two branches is two groups: flipping the branch disposes one and
            // mounts the other, and everything the disposed one was animating is simply gone.
            //
            // RC-34 fixed that once, for the shelf's collection lead art versus the opened Folio's focused
            // member -- those two became the single `tomeStageArt` host below. **The outer boundary was
            // left split**, so the same defect survived one level up: entering the Tome moved the still
            // host from the `HomeBackdropMount.Static` branch to the tome branch, and coming back moved it
            // back. Measured on the Odin 2026-08-27 ,
            // backdrop-only frame difference on X-into-Tome: **30.6 in a single frame**, then 8.4, 7.3,
            // 2.5 -- essentially the whole change in one frame. Nothing else in the 18s path exceeded 5.7.
            //
            // The mechanism is named in `HomeShelfBackdrop`: `synchronizedPresence` is
            // `remember { Animatable(if (synchronizedTomeHandoff && desiredUri != null) 1f else 0f) }` --
            // no keys, so a freshly mounted host starts FULLY PRESENT. There is no animation to soften
            // anything, whatever triggered it.
            //
            // Kept mounted, the same URI change is a crossfade the host already performs
            // (`LaunchedEffect(desiredUri, synchronizedTomeHandoff)` animates `synchronizedPresence`).
            // The tome/shelf difference is now PARAMETERS on one host, not two hosts.
            //
            // Still a genuine swap, and deliberately not solved here: Video <-> Static. Those are
            // different composables with no shared layer to fade between; a persistent host owning both a
            // player and a still slot is the shape, and it is owed work rather than this change.
            // The shelf's own still is a FALLBACK, not an alternative: if the tome stage is active but its
            // art has not resolved yet, falling through to null would unmount the host -- the exact cut
            // this change removes. Better to keep painting what is already on screen.
            // Nova regression, 2026-09-29: opening the Tome collection flashed a hero behind it. The 09-03
            // hero-suppression rule nulls `tomeStageArt` while the Library opens, but this fallback served the focused
            // row's still straight back, and the synchronized host faded it in behind the see-through collection.
            // While the Library opens the stage is the blurred scene only, until the collection's own art lands.
            val resolvedStill = if (libraryOpening) {
                null
            } else {
                tomeStageArt.takeIf { tomeSurfaceActive } ?: (backdropMount as? HomeBackdropMount.Static)?.uri
            }
            // CP03: entering tome mode nulls BOTH terms until the tome's own art decodes -- `tomeStageArt`
            // has not resolved, and the shelf's fallback is null whenever the carousel was on a VIDEO
            // backdrop (`videoClip` is force-nulled one line below once `tomeSurfaceActive`). A null here
            // reaches `else -> Unit` and DISPOSES the host, so the theme ground is briefly the only thing
            // on screen -- the observed theme-background flash, reported three
            // times (UI-01 2026-08-14, 2026-08-21, CP03).
            //
            // Hold the last painted art for exactly that window. Gated on `tomeSurfaceActive` so the
            // carousel's own "no backdrop eligible" case is untouched and the plain scene stays
            // byte-identical, which the comment above requires. Keeping the host alive is all this needs:
            // the correct null->URI presence fade already exists (`HomeShelfBackdrop.kt`'s
            // `synchronizedPresentedUri` ramp) and today is simply routed around by the dispose.
            val lastPaintedStill = remember { arrayOfNulls<String>(1) }
            SideEffect { if (resolvedStill != null) lastPaintedStill[0] = resolvedStill }
                // `lastPaintedStill` exists to stop a null DISPOSING the host mid-open, which showed bare
            // theme ground -- reported three times. It is deliberately bypassed while the Library is
            // opening: there the null is intentional stage state, not a gap, and the host is kept alive by the
            // outgoing art's own presence fade rather than by re-serving the art we are removing.
            val stillUri = resolvedStill
                ?: lastPaintedStill[0]?.takeIf { tomeSurfaceActive && !libraryOpening }
            val videoClip = if (tomeSurfaceActive) null else (backdropMount as? HomeBackdropMount.Video)?.uri
            when {
                videoClip != null -> HomeShelfBackdrop(
                    clip = videoClip,
                    geometry = geometry,
                    launching = launchingRow != null,
                    launchReturnRestoring = launchReturnRestoring,
                    contextEpoch = backdropContextEpoch,
                    wakeSignal = focusState,
                    heroSlotOffset = { visualSelectedSlotOffset.value },
                    modifier = Modifier.fillMaxSize(),
                )
                // The Tome stage and the shelf's chosen still are the SAME host, differing only in how it
                // is driven. A null `stillUri` is deliberately NOT mounted: the plain scene must stay
                // byte-identical when no backdrop is eligible (the atmosphere shows through unchanged).
                // While the Library opens, a null still reaches the live host, which fades what it shows out
                // (null is its "plain atmosphere" state) instead of being disposed mid-fade.
                stillUri != null || libraryOpening -> HomeShelfStaticBackdrop(
                    imageUri = stillUri,
                    geometry = geometry,
                    launching = launchingRow != null,
                    launchReturnRestoring = launchReturnRestoring,
                    contextEpoch = backdropContextEpoch,
                    wakeSignal = focusState,
                    heroSlotOffset = if (tomeSurfaceActive && folioStageHandedOff) {
                        { 0f }
                    } else {
                        { visualSelectedSlotOffset.value }
                    },
                    modifier = Modifier.fillMaxSize(),
                    contextSettleMs = if (tomeSurfaceActive) 0L else HomeBackdropSettleMs,
                    synchronizedTomeHandoff = tomeSurfaceActive,
                    reducedMotion = reducedMotion,
                    tomeStageArrivesAtRest = tomeStageArrivesAtRest,
                )
                else -> Unit
            }
            // Favorite updates deliberately do not mount a full-screen alpha veil. The existing backdrop
            // runtime gates stale media while the stable shelf field remains visible; an animated opaque
            // veil caused a brightness flash and visually superseded the newly committed tiles.
            // FUX-12p: the focused game's launch status ("Ready" / "Needs emulator" / …)
            // rides beside the corner title. Resolved from the hero's platform (row.supportingLabel
            // carries platformId.value); null on Android rows or platforms with no status line.
            val heroStatus = selectedRow?.supportingLabel
                ?.let { launchStatusLines[PlatformId(it)] }
                ?.let { line ->
                    launchStatusChipLabel(line)?.let { GameStatusChip(it, launchStatusReady(line)) }
                }
            // The header stays still and crossfades: the old title and eyebrow leave
            // as pixels in place while the new ones arrive on the band's clock, without travel. Keyed on surfaceEpoch:
            // the departing page only references a layered child's RenderNode (the title Text), so a
            // child that survived the flip would re-record and depart showing the NEW title.
            MimirPageHandoff(
                epoch = surfaceEpoch,
                departs = modeHandoff,
                exitTravelPx = 0f,
                incomingAlpha = surfaceFadeHero,
                incomingTravelPx = { 0f },
                reducedMotion = reducedMotion,
                modifier = Modifier.fillMaxSize(),
            ) {
                key(surfaceEpoch) {
                    HomeShelfTitle(
                        title = selectedItem?.title,
                        eyebrow = surfaceLabel,
                        geometry = geometry,
                        mediaBackdropAvailable = mediaBackdropAvailable,
                        tomeSurfaceActive = tomeSurfaceActive,
                        statusChip = heroStatus,
                        tomeMark = if (tomeSurfaceActive) null else tomeMark,
                        stackStatus = compactViewport,
                    )
                }
            }

            // Motion audit batch D: the band (tiles, ring and runes) is one page. On an L1/R1 press its last frame glides
            // out the far side on 180 ms while the new band glides in from the side of the shoulder, from the 130 ms lead
            // on the slow clock. One layer, so the ring can never detach from its seat and no book fades on its own.
            MimirPageHandoff(
                epoch = surfaceEpoch,
                departs = modeHandoff,
                exitTravelPx = MimirPageMotion.exitTravelPx(surfaceHandoffDirection, modeTravelPx),
                incomingAlpha = surfaceFadeHero,
                incomingTravelPx = { bandArrivalFromPx * (1f - surfaceFadeHero()) },
                target = surfaceLabel,
                state = bandHandoff,
                reducedMotion = reducedMotion,
                modifier = Modifier.fillMaxSize(),
                shieldTouches = { modeShielding },
            ) {
                // The band: seats, the one stationary ring and the continuation runes. GameShelf is scope-parametric; this
                // screen hands it HomeShelfScope, the Folio's hold on the live chrome (RC-50) and the mode switch's gates.
                GameShelf(
                    scope = HomeShelfScope,
                    authority = authority,
                    focusState = focusState,
                    onRequestFocus = { key -> authority.requestFocus(key) },
                    onActivate = { shelfItem ->
                        when (shelfItem) {
                            is GameShelfItem.Game -> gameRowsByFocusKey[shelfItem.focusKey]?.let(onLaunch)
                            is GameShelfItem.Tome -> onOpenTome(shelfItem.tome.tomeId)
                        }
                    },
                    emptyContent = {
                        HomeShelfEmptyAction(
                            authority = authority,
                            // RING channel (core-input's two-channel law): this flag drives only the accent fill
                            // that says "input lands here", so it dies with the scope instead of being conserved.
                            focused = authority.isLitFocusIn(HomeShelfScope, HomeShelfOpenLibraryKey, focusState),
                            geometry = geometry,
                            compactViewport = compactViewport,
                            onOpenLibrary = onOpenLibrary,
                        )
                    },
                    inert = restorationFocusKey != null,
                    chromeHeldByCover = tomeFolioActive,
                    touchEnabled = { !modeShielding },
                    ringGleamArmed = { modeArrived },
                    onTomePreviewPresented = { tomeId, preview ->
                        if (selectedTome?.tomeId == tomeId &&
                            focusState.scope == HomeShelfScope &&
                            focusState.focused == homeShelfTomeFocusKey(tomeId)
                        ) {
                            presentedTomePreview = preview?.let {
                                PresentedHomeTomePreview(tomeId, it)
                            }
                        }
                    },
                    state = shelfState,
                )
            }

            // FUX-12b: game count PROGRESS — "3 / 12" tracking the focused tile — pinned to
            // the FOOTER's lower-left corner (the quick-rail band), updating as you move.
            if (orderedItems.isNotEmpty() && selectedIndex != null) {
                Box(
                    modifier = Modifier
                        .offset(
                            x = geometry.gutter.dp,
                            y = geometry.quickRailBand.top.dp,
                        )
                        .width(HomeShelfGameCountPaintWidth)
                        .height(geometry.quickRailBand.height.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = surfaceFadeHero() },
                    ) {
                        HomeShelfGameCount(
                            position = selectedIndex + 1,
                            count = orderedItems.size,
                            mediaContrastRail = mediaBackdropAvailable,
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .height(HomeShelfGameCountLaneHeight),
                        )
                    }
                }
            }
            // Permanent, visual-only Tome affordance. It mirrors the progress-count lane at the
            // opposite edge, so Tomes remain discoverable even on an empty Home without creating a
            // second focus stop or competing with the temporary per-tile action hint. Compact has its own
            // seat for it at the left end of the hint lane ([homeShelfCompactTomeMarkBounds]).
            val tomeShortcutDescription = stringResource(R.string.library_home_hint_tome_shortcut_description)
            if (compactViewport) {
                val mark = homeShelfCompactTomeMarkBounds(geometry)
                Box(
                    modifier = Modifier
                        .offset(x = mark.left.dp, y = mark.top.dp)
                        .width(mark.width.dp)
                        .height(mark.height.dp)
                        .clearAndSetSemantics {
                            contentDescription = tomeShortcutDescription
                        }
                        .testTag(HomeShelfTomeShortcutHintTestTag),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    HomeShelfTomeShortcutHint(
                        mediaContrastRail = mediaBackdropAvailable,
                        modifier = Modifier.height(HomeShelfGameCountLaneHeight),
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .offset(
                            x = (geometry.viewport.right - geometry.gutter -
                                HomeShelfTomeShortcutHintPaintWidth.value).dp,
                            y = geometry.quickRailBand.top.dp,
                        )
                        .width(HomeShelfTomeShortcutHintPaintWidth)
                        .height(geometry.quickRailBand.height.dp)
                        .clearAndSetSemantics {
                            contentDescription = tomeShortcutDescription
                        }
                        .testTag(HomeShelfTomeShortcutHintTestTag),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    HomeShelfTomeShortcutHint(
                        mediaContrastRail = mediaBackdropAvailable,
                        modifier = Modifier.height(HomeShelfGameCountLaneHeight),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .offset(
                        x = geometry.hintToastBand.left.dp,
                        y = geometry.hintToastBand.top.dp,
                    )
                    .width(geometry.hintToastBand.width.dp)
                    .height(geometry.hintToastBand.height.dp),
                contentAlignment = Alignment.Center,
            ) {
                MimirHintToastRail(
                    hint = null,
                    toast = toast,
                    onMessageDelivered = onToastDelivered,
                    modifier = Modifier
                        .width(minOf(HomeShelfStatusToastWidth, geometry.hintToastBand.width).dp)
                        .testTag(HomeShelfHintToastTestTag),
                )
            }

            // navigation guidance occupies the vacant right end of the
            // hint/toast lane. It is a visual-only glass hover: no focus target or shelf remeasurement.
            Box(
                modifier = Modifier
                    .offset(
                        x = geometry.navigationHintBand.left.dp,
                        y = geometry.navigationHintBand.top.dp,
                    )
                    .width(geometry.navigationHintBand.width.dp)
                    .height(geometry.navigationHintBand.height.dp)
                    .testTag(HomeShelfNavigationHintTestTag),
                contentAlignment = Alignment.CenterEnd,
            ) {
                if (hint != null) {
                    HomeNavigationTextHint(hint)
                } else if (toast == null) {
                    // Typed temporary binding reminder: compact, ~1.6s, reset per identity.
                    //
                    // RC-50 sibling, and the worst of the three because it is not just a pop: the hint is
                    // keyed on its target's identity, so today the tome commit drops it to null and the
                    // RETURN re-arms the whole ~1.6s reminder over a shelf the player never left. Reading
                    // the conserved key under the Folio keeps one target across both edges, so the timer
                    // runs once. When this scope owns input, renderedFocusIn IS focusState.focused, so the
                    // dock, Utility and every other pushed scope see the identical value they see today.
                    val chromeFocusKey = authority.renderedFocusIn(HomeShelfScope, focusState)
                    val focusedDisplayItem = if (
                        homeShelfLiveChromeLit(
                            scopeOwnsInput = focusState.scope == HomeShelfScope,
                            folioActive = tomeFolioActive,
                        )
                    ) {
                        orderedItems.firstOrNull { it.focusKey == chromeFocusKey }
                    } else {
                        null
                    }
                    HomeTileFocusHint(
                        target = focusedDisplayItem?.let { item ->
                            HomeTileFocusHintTarget(
                                focusKey = item.focusKey,
                                kind = when (item) {
                                    is HomeShelfDisplayItem.Game -> HomeTileHintKind.GAME
                                    is HomeShelfDisplayItem.Tome -> HomeTileHintKind.TOME
                                },
                            )
                        },
                        viewModeSelectAvailable = viewModeSelectAvailable,
                    )
                }
            }

            MimirRunicQuickRail(
                selectedAction = MimirQuickRailAction.HOME,
                // RING channel. The conserved version showed a device-visible defect: a dock that stays lit after focus went back to the tiles is a ghost, not an
                // anchor. The reported ring failed to leave the dock when input moved; the
                // dock is persistent visible chrome, never a covered surface, so its seat is lit only
                // while the dock actually owns input.
                focusedAction = homeShelfQuickRailAction(
                    authority.litFocusIn(HomeShelfQuickRailScope, focusState),
                ),
                onActionFocus = { action ->
                    if (authority.state.value.scope != HomeShelfQuickRailScope) {
                        authority.pushScope(HomeShelfQuickRailScope)
                    }
                    authority.requestFocus(homeShelfQuickRailKey(action))
                },
                onActionActivate = { action ->
                    activateHomeShelfQuickAction(authority, action, onQuickAction)
                },
                onActionTouch = onQuickRailTouch,
                actions = homeShelfQuickActions(filesSeatOffered),
                actionDiameter = geometry.quickActionDiameter.dp,
                mediaContrastRail = lightMediaChrome,
                actionModifier = { action ->
                    val focusModifier = when (action) {
                        MimirQuickRailAction.HOME -> homeRailModifier
                        MimirQuickRailAction.LIBRARY -> libraryRailModifier
                        MimirQuickRailAction.BRIGHTNESS -> brightnessRailModifier
                        MimirQuickRailAction.SETTINGS -> settingsRailModifier
                        MimirQuickRailAction.QUICK_LAUNCH -> quickLaunchRailModifier
                        // Reachable, and deliberately so. The dock lane left this arm as an inert
                        // `Modifier` because HomeShelfQuickActions did not list FILES and opting the
                        // Home rail in was outside its file fence. It is in scope here: the Home shelf
                        // is where the end-cap has to appear, so Files is reachable without navigating to Library.
                        MimirQuickRailAction.FILES -> filesRailModifier
                    }
                    Modifier
                        .offset(y = geometry.quickActionOffsetY.dp)
                        .then(focusModifier)
                },
                modifier = Modifier
                    .offset(y = geometry.quickRailBand.top.dp)
                    .testTag(HomeShelfQuickRailTestTag),
            )
        }
    }
}

internal fun homeShelfTitleTrailingReserve(
    hasStatusChip: Boolean,
    hasTomeMark: Boolean = false,
): Dp = when {
    hasStatusChip -> HomeShelfStatusChipTrailingReserve
    else -> 0.dp
} + if (hasTomeMark) HomeShelfTomeMarkTrailingReserve else 0.dp

/** OR-1's Tome mark: its "·" and at most [HomeShelfTomeMarkMaxWidth] of name, with the seat's xs gap. */
internal val HomeShelfTomeMarkMaxWidth = 120.dp
internal val HomeShelfTomeMarkTrailingReserve = HomeShelfTomeMarkMaxWidth + 16.dp
internal const val HomeShelfTomeMarkTestTag = "home_shelf_tome_mark"

@Composable
internal fun HomeShelfTitle(
    title: String?,
    geometry: HomeShelfGeometry,
    eyebrow: String? = null,
    mediaBackdropAvailable: Boolean = false,
    tomeSurfaceActive: Boolean = false,
    statusChip: GameStatusChip? = null,
    tomeMark: String? = null,
    /**
     * Compact: the status chip leaves the title's line (122 dp beside it) for the end of the eyebrow row above (compact review
     * HOME-1; 2026-10-11: a third line, under the title, did not fit the 60 dp band and the chip was cut). The block stays two lines
     * inside the fixed-height band, so a chip arriving between games moves nothing else, and the eyebrow gives way to it at a word.
     * The eyebrow row is as tall as the chip WITH OR WITHOUT one ([LibraryStatusBadgeHeightReserve]), so the title does not drop when
     * focus moves from a game with no status to one with.
     */
    stackStatus: Boolean = false,
) {
    val palette = LocalMimirPalette.current
    val showMediaRail = homeShelfShowsMediaTitleRail(
        palette = palette,
        mediaBackdropAvailable = mediaBackdropAvailable,
        tomeSurfaceActive = tomeSurfaceActive,
    )
    val stableRailWidth = homeShelfStableTitleRailWidth(
        titleBandWidth = geometry.titleBand.width.dp,
        showMediaRail = showMediaRail,
        tomeSurfaceActive = tomeSurfaceActive,
    )
    val trailingReserve = if (stackStatus) {
        0.dp
    } else {
        homeShelfTitleTrailingReserve(
            hasStatusChip = statusChip != null,
            hasTomeMark = tomeMark != null,
        )
    }
    val statusSeat: @Composable () -> Unit = {
        Row(
            horizontalArrangement = Arrangement.spacedBy(MimirSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            statusChip?.let { chip ->
                LibraryStatusBadge(
                    label = chip.label,
                    ready = chip.ready,
                    rowStyle = true,
                    modifier = Modifier.testTag(HomeShelfStatusTestTag),
                )
            }
            // OR-1: the Tome mark, last and quiet: the X · TOME mark's ink, no chip, no rune, no colour of its own.
            // It rides the title's handoff and has no motion of its own.
            tomeMark?.let { mark ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(MimirSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.testTag(HomeShelfTomeMarkTestTag),
                ) {
                    Text(
                        text = "·",
                        style = MimirTypeScale.MicroStatusCompact,
                        color = palette.textMuted.copy(alpha = 0.78f),
                        maxLines = 1,
                    )
                    MimirChromeText(
                        text = mark,
                        style = MimirTypeScale.ControlHintCompact,
                        color = palette.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = HomeShelfTomeMarkMaxWidth),
                    )
                }
            }
        }
    }
    val titleMaxWidth = (
        (stableRailWidth ?: geometry.titleBand.width.dp) -
            trailingReserve -
            if (showMediaRail) HomeShelfTitleRailHorizontalPadding * 2 else 0.dp
        ).coerceAtLeast(120.dp)
    Box(
        modifier = Modifier
            .offset(x = geometry.titleBand.left.dp, y = geometry.titleBand.top.dp)
            .width(geometry.titleBand.width.dp)
            .height(geometry.titleBand.height.dp)
            .testTag(HomeShelfTitleTestTag),
        contentAlignment = Alignment.TopStart,
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .then(
                    if (stableRailWidth != null) {
                        Modifier.width(stableRailWidth)
                    } else {
                        Modifier.widthIn(max = geometry.titleBand.width.dp)
                    },
                )
                .then(
                    if (showMediaRail) {
                        Modifier
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        palette.materials.controlTop.copy(alpha = LightHomeTitleRailAlpha),
                                        palette.materials.controlBottom.copy(alpha = LightHomeTitleRailAlpha),
                                    ),
                                ),
                                HomeShelfTitleRailShape,
                            )
                            .border(
                                MimirSpacing.hairlineWidth,
                                palette.textSecondary.copy(alpha = LightHomeTitleRailOutlineAlpha),
                                HomeShelfTitleRailShape,
                            )
                            .drawBehind {
                                drawLine(
                                    color = palette.materials.glassSheen,
                                    start = Offset(size.width * 0.12f, 0.75.dp.toPx()),
                                    end = Offset(size.width * 0.88f, 0.75.dp.toPx()),
                                    strokeWidth = 1.5.dp.toPx(),
                                )
                            }
                            .padding(
                                horizontal = HomeShelfTitleRailHorizontalPadding,
                                vertical = HomeShelfTitleRailVerticalPadding,
                            )
                    } else {
                        Modifier
                    },
                ),
            // The switch's crossfade is its caller's MimirPageHandoff (motion audit batch D): the lockup holds
            // still, no scale and no travel.
            verticalArrangement = Arrangement.spacedBy(MimirSpacing.xs),
        ) {
            // FUX-C context eyebrow: header and shelf commit as ONE state — the label rides the same
            // progress as the title/rail, inside the solved band (ellipsized, single line).
            // Stacked (compact), the status seat joins this row: measured first at its own width, so the eyebrow is what gives way. The row
            // is always as tall as the chip, a chip or not, so the title under it holds still between games that have a status and ones that don't.
            val statusInEyebrowRow = stackStatus && (statusChip != null || tomeMark != null)
            when {
                stackStatus -> Box(
                    modifier = Modifier.widthIn(max = titleMaxWidth),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    LibraryStatusBadgeHeightReserve()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
                    ) {
                        if (eyebrow != null) {
                            WordEllipsisText(
                                text = eyebrow,
                                style = MimirTypeScale.RunicEyebrow,
                                color = palette.textSecondary,
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .clearAndSetSemantics { },
                            )
                        }
                        if (statusInEyebrowRow) statusSeat()
                    }
                }
                eyebrow != null -> WordEllipsisText(
                    // Shortened at a word, never inside one (the Library puts a game's genre and launcher here).
                    text = eyebrow,
                    style = MimirTypeScale.RunicEyebrow,
                    color = palette.textSecondary,
                    modifier = Modifier
                        .widthIn(max = titleMaxWidth)
                        .clearAndSetSemantics { },
                )
            }
            // FUX-02 R1: the title sits below the eyebrow; title/status/count share this one rail.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
            ) {
                if (title == null) {
                    MimirChromeText(
                        text = stringResource(R.string.library_home_title),
                        style = MimirTypeScale.TitleCaps,
                        color = palette.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = titleMaxWidth),
                    )
                } else {
                    HomeShelfTitleText(
                        text = title,
                        alpha = 1f,
                        semantic = true,
                        compactForMediaRail = showMediaRail,
                        modifier = Modifier.widthIn(max = titleMaxWidth),
                    )
                }
                if (!stackStatus) statusSeat()
            }
        }
    }
}

@Composable
private fun HomeShelfTitleText(
    text: String,
    alpha: Float,
    semantic: Boolean,
    compactForMediaRail: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val semanticModifier = if (semantic) Modifier else Modifier.clearAndSetSemantics { }
    val palette = LocalMimirPalette.current
    // One line, shortened at a WORD boundary when it is wider than its rail: "Unbel..." is a break inside a word, which the product never
    // makes (2026-10-10; the Library's game titles ride this same rail).
    WordEllipsisText(
        text = text,
        // Header sizing: localized step-down; the shared Title token is untouched.
        style = MimirTypeScale.Title.copy(
            fontSize = (
                MimirTypeScale.Title.fontSize.value * 0.85f -
                    if (compactForMediaRail) 1f else 0f
                ).sp,
            fontWeight = FontWeight.Bold,
        ),
        color = palette.textPrimary,
        modifier = modifier
            .graphicsLayer { this.alpha = alpha.coerceIn(0f, 1f) }
            .then(semanticModifier),
    )
}

@Composable
internal fun HomeShelfEmptyAction(
    authority: FocusAuthority,
    focused: Boolean,
    geometry: HomeShelfGeometry,
    compactViewport: Boolean,
    onOpenLibrary: () -> Unit,
) {
    val palette = LocalMimirPalette.current
    val width = minOf(220f, geometry.viewport.width - geometry.gutter * 2f)
    val left = if (compactViewport) {
        // `centerAnchor` is seat-zero's center and therefore intentionally sits left of center on a
        // populated shelf. The empty state is a viewport-level action: keep its copy and button inside
        // the compact window's gutters and center them on the actual window.
        ((geometry.viewport.width - width) / 2f)
            .coerceIn(geometry.gutter, geometry.viewport.width - geometry.gutter - width)
    } else {
        geometry.centerAnchor - width / 2f
    }
    val height = MimirSpacing.minTarget

    Column(
        modifier = Modifier
            .offset(
                x = left.dp,
                y = geometry.gameRailBand.top.dp,
            )
            .width(width.dp),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.library_home_empty_title),
            style = MimirTypeScale.Rail,
            color = palette.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(R.string.library_home_empty_body),
            style = MimirTypeScale.Support,
            color = palette.textSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            modifier = Modifier
                // The ring reaches MimirFocusTileRingReach past the plate and the line above stands 8 dp from it: room for the rest of the reach, so the halo does not run over the text (the
                // Enrich chip's lesson).
                .padding(top = MimirFocusTileRingReach - 8.dp)
                .width(width.dp)
                .height(height)
                .mimirFocusable(
                    authority = authority,
                    key = HomeShelfOpenLibraryKey,
                    scope = HomeShelfScope,
                    autoBringIntoView = false,
                )
                // Update (no filled blob; sweep 10-09: it turned solid gold when focused): the PRIMARY etched chip, the screen's one action. The ring is on the plate itself, a
                // pill before the surface, so its edge is the plate's edge and its wash is under the opaque metal; the rim hands over to it while focused. Nothing else changes colour on focus.
                .mimirSelectionRing(focused = focused, identity = HomeShelfOpenLibraryKey, shape = MimirRingShape.Pill, edgeFlush = true)
                .mimirEtchedChipSurface(palette, focused = focused, primary = true, edgeFlush = true)
                .mimirActivatable(label = stringResource(R.string.library_home_empty_action_description), authority = authority, key = HomeShelfOpenLibraryKey, onActivate = onOpenLibrary)
                .testTag(HomeShelfEmptyActionTestTag),
            contentAlignment = Alignment.Center,
        ) {
            MimirChromeText(
                text = stringResource(R.string.library_home_empty_action),
                style = MimirEtchedChipLabelStyle,
                color = palette.etchedChipInk(primary = true),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val HomeShelfScopes = setOf(HomeShelfScope, HomeShelfQuickRailScope)

internal fun homeShelfTileTestTag(row: HomeShelfRow): String = HomeShelfTileTestTagPrefix + row.focusKey.value

internal fun homeShelfOutgoingArtTestTag(row: HomeShelfRow): String =
    "home_shelf_outgoing_${row.focusKey.value}"

/** Temporary typed binding reminder; Game and Tome never advertise an action they cannot dispatch. */
@Composable
private fun HomeTileFocusHint(
    target: HomeTileFocusHintTarget?,
    viewModeSelectAvailable: Boolean,
) {
    var visibleFor by remember { mutableStateOf<FocusKey?>(null) }
    var lastKind by remember { mutableStateOf(HomeTileHintKind.GAME) }
    LaunchedEffect(target) {
        if (target == null) {
            visibleFor = null
        } else {
            lastKind = target.kind
            visibleFor = target.focusKey
            kotlinx.coroutines.delay(HomeTileFocusHintMs)
            visibleFor = null
        }
    }
    val shown = visibleFor != null
    val alpha = animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        // the hint fades on the overlay clock, not a 150 of its own.
        animationSpec = tween(durationMillis = MimirMotion.OverlayMs, easing = MimirMotion.Easing),
        label = "tileFocusHint",
    )
    // A discrete gate over the animated alpha: composition re-runs when the pill appears or finishes fading out, never once per
    // frame of the fade. The alpha itself is read only in the layer lambda below.
    val visibleNow by remember { derivedStateOf { alpha.value > 0.01f } }
    if (!visibleNow) return
    val actions = homeTileFocusHintActions(lastKind, viewModeSelectAvailable)
    // The liquid-glass sheet is gone for the one quiet hint pill Library's legend now shares. Its ink
    // is solved against its own body (mimirHintPillColors), never the outer field, so the Folkvangr/Baldr case
    // the glass provider existed for -- dark app ink on a dark panel -- cannot recur.
    val palette = LocalMimirPalette.current
    val colors = remember(palette) { mimirHintPillColors(palette) }
    // The hint transition is keyed on the ACTIONS, so
    // game to game changes nothing and only game to Tome (EDIT to OPEN) dissolves; pinned at its right edge.
    // The fade rides a layer that spans the hint rail, NOT the swap's own bounds. Below alpha 1 an Auto layer is an offscreen buffer
    // cut to its node's bounds, and the swap draws a wider departing frame past the arriving one's edges (its KDoc: never inside a
    // clip), so a Game <-> Tome change inside the 180 ms fade lost the overhang. The rail-wide layer holds it; the swap stays pinned
    // at the right edge exactly where the parent's CenterEnd put it.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { this.alpha = alpha.value },
        contentAlignment = Alignment.CenterEnd,
    ) {
        MimirHintSwap(
            key = actions,
            anchor = Alignment.CenterEnd,
        ) {
            Row(
                modifier = Modifier
                    .mimirHintPill(colors)
                    .padding(horizontal = HomeHintPillPaddingH, vertical = HomeHintPillPaddingV),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actions.forEachIndexed { index, action ->
                    if (index > 0) {
                        Text(
                            text = "·",
                            style = MimirTypeScale.MicroStatusCompact,
                            color = colors.ink.copy(alpha = 0.5f),
                        )
                    }
                    HomeTileFocusHintBadge(glyph = action.glyph, label = stringResource(action.labelRes), colors = colors)
                }
            }
        }
    }
}

@Composable
private fun HomeTileFocusHintBadge(glyph: String, label: String, colors: MimirHintPillColors) {
    val projectedGlyph = LocalMimirControllerHintLabels.current.labelFor(glyph)
    if (projectedGlyph.isBlank()) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MimirHintKeycap(key = projectedGlyph, colors = colors)
        MimirChromeText(
            text = label,
            style = MimirTypeScale.ControlHint,
            color = colors.ink,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
    }
}

@Composable
private fun HomeNavigationTextHint(hint: String) {
    val palette = LocalMimirPalette.current
    val colors = remember(palette) { mimirHintPillColors(palette) }
    Row(
        modifier = Modifier
            .mimirHintPill(colors)
            .padding(horizontal = HomeHintPillPaddingH, vertical = HomeHintPillPaddingV),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "ᚨ",
            style = MimirTypeScale.MicroStatus,
            color = colors.rune,
            maxLines = 1,
        )
        MimirControlHintText(
            text = hint,
            style = MimirTypeScale.ControlHintCompact,
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Inside the 24 dp navigation-hint band: a 16 dp keycap plus 3 dp above and below. */
private val HomeHintPillPaddingH = 10.dp
private val HomeHintPillPaddingV = 3.dp

private const val HomeTileFocusHintMs = 1_600L
private const val HomeShelfStatusToastWidth = 196f

@Composable
private fun HomeShelfGameCount(
    position: Int,
    count: Int,
    mediaContrastRail: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = LocalMimirPalette.current
    val countShape = RoundedCornerShape(MimirSpacing.radiusPill)
    Box(
        modifier = if (mediaContrastRail) {
            modifier
                .background(
                    palette.materials.controlBottom.copy(alpha = 0.84f),
                    countShape,
                )
                .border(
                    MimirSpacing.hairlineWidth,
                    palette.textSecondary.copy(alpha = 0.18f),
                    countShape,
                )
                .padding(horizontal = 8.dp, vertical = 3.dp)
        } else {
            modifier
        },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "$position / $count",
            style = MimirTypeScale.MicroStatus,
            color = palette.textMuted,
        )
    }
}

@Composable
private fun HomeShelfTomeShortcutHint(
    mediaContrastRail: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = LocalMimirPalette.current
    val shape = RoundedCornerShape(MimirSpacing.radiusPill)
    Row(
        modifier = if (mediaContrastRail) {
            modifier
                .background(
                    palette.materials.controlBottom.copy(alpha = 0.84f),
                    shape,
                )
                .border(
                    MimirSpacing.hairlineWidth,
                    palette.textSecondary.copy(alpha = 0.18f),
                    shape,
                )
                .padding(horizontal = 8.dp, vertical = 2.dp)
        } else {
            modifier
        },
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MimirControlHintText(
            text = "X",
            style = MimirTypeScale.MicroStatus,
            color = palette.focusRing,
            maxLines = 1,
        )
        Text(
            text = "·",
            style = MimirTypeScale.MicroStatusCompact,
            color = palette.textMuted.copy(alpha = 0.78f),
            maxLines = 1,
        )
        MimirChromeText(
            text = stringResource(R.string.library_home_hint_tome),
            style = MimirTypeScale.ControlHintCompact,
            color = palette.textMuted,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
    }
}

/**
 * Where the Home shelf's window last stood, for the surface it stood on. Home is composed afresh on
 * every return from the Library; the shelf seeds its window from [GameShelfWindowMemory] under this key, which puts the
 * focused tile back in the seat it left from. Process memory, one surface: another surface epoch or label starts afresh.
 */
internal object HomeShelfWindowMemory {
    /** Home's own memory: the shelf records here, so another surface's shelf can never displace it. */
    val memory = GameShelfWindowMemory()

    fun key(surfaceEpoch: Int, surfaceLabel: String?): Any = surfaceEpoch to surfaceLabel

    fun seed(surfaceEpoch: Int, surfaceLabel: String?): Int? =
        memory.seed(key(surfaceEpoch, surfaceLabel))

    fun record(surfaceEpoch: Int, surfaceLabel: String?, windowStart: Int) =
        memory.record(key(surfaceEpoch, surfaceLabel), windowStart)
}
