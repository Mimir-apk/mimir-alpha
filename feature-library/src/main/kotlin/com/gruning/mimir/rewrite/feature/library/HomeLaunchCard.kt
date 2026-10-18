package com.gruning.mimir.rewrite.feature.library

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.input.LocalFocusAuthority
import com.gruning.mimir.rewrite.core.input.mimirFocusable
import com.gruning.mimir.rewrite.core.input.litFocusIn
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.model.home.androidPackageName
import com.gruning.mimir.rewrite.core.ui.MimirBoxArtCorner
import com.gruning.mimir.rewrite.core.ui.MimirBeforeFirstValidViewport
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.MimirControlHintText
import com.gruning.mimir.rewrite.core.ui.MediaArtBookCover
import com.gruning.mimir.rewrite.core.ui.MimirBoxArt
import com.gruning.mimir.rewrite.core.ui.MimirAchievementChip
import com.gruning.mimir.rewrite.core.ui.MimirCopy
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirFocusTileRingReach
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.MimirTypeStep
import com.gruning.mimir.rewrite.core.ui.liquidGlassContentPalette
import com.gruning.mimir.rewrite.core.ui.MimirEtchedChipLabelStyle
import com.gruning.mimir.rewrite.core.ui.MimirRingShape
import com.gruning.mimir.rewrite.core.ui.etchedChipInk
import com.gruning.mimir.rewrite.core.ui.mimirEtchedChipSurface
import com.gruning.mimir.rewrite.core.ui.mimirGlassScrimColor
import com.gruning.mimir.rewrite.core.ui.mimirSelectionRing
import com.gruning.mimir.rewrite.core.ui.mimirMeasuredViewport
import com.gruning.mimir.rewrite.core.ui.mimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.MimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.rememberMeasuredViewport

/** A transient Home overlay. It deliberately does not reuse Library's destination-level detail scope. */
val HomeLaunchCardScope = FocusScope("home/launch-card")

val HomeLaunchCardPlayKey = FocusKey("home/launch-card/play")
val HomeLaunchCardAchievementsKey = FocusKey("home/launch-card/achievements")
val HomeLaunchCardOpenWithKey = FocusKey("home/launch-card/open-with")
val HomeLaunchCardDescriptionKey = FocusKey("home/launch-card/description")

/**
 * The manual-rename affordance (cosmetic display override, gated to emulated
 * games only — Android games/apps carry no [com.gruning.mimir.rewrite.core.model.GameId] to rename).
 * Registered with VIRTUAL bounds in the SAME synthetic coordinate space as the footer action row
 * (`homeLaunchCardActionBounds`), never physical ones — mixing physical (real window px, e.g. the
 * title/description) and virtual (small abstract units) bounds in one geometric `moveFocus` ring is the
 * exact BUG-A mechanism : the Add pill's
 * physical bounds scored against the shelf ring's synthetic virtual bounds landed focus on the WRONG
 * neighbour. Staying in the footer's own coordinate space keeps `authority.moveFocus` reliable without any
 * new special-case dispatch branch.
 *
 *  [dispatchHomeLaunchCard] now also
 * reaches this key with a deterministic vertical ladder from Play — Play UP → Description (when there is
 * one to scroll) → UP again → Edit; DOWN mirrors that back down. Those transitions are explicit
 * `authority.requestFocus` calls, never a new `moveFocus` candidate set, so they never mix this key's
 * VIRTUAL bounds with Description's PHYSICAL ones — the exact discipline this KDoc already documents for
 * LEFT/RIGHT. The existing NAV_LEFT-from-Play reach (`moveFocus`, footer-row virtual bounds only) is KEPT,
 * not replaced: it is already pinned by a frozen test, costs nothing, and gives players who already learned
 * it a second way in — the ladder is purely additive discoverability, not a redesign of the horizontal ring.
 */
val HomeLaunchCardTitleEditKey = FocusKey("home/launch-card/title-edit")

/**
 * The description's selection corner: square, because its selection stays outside its rounded glass body, so the
 * content clip cannot cut the ring. A named zero rather than a rectangle shape, so the ring and the element take one
 * symbol (R-1) and no literal sits in a corner position.
 */
internal val HomeLaunchDescriptionSelectionCorner: Dp = 0.dp

/**
 * The cover art's tap target — T-1's second half.
 */
const val HomeLaunchCardCoverArtTestTag = "home-launch-card/cover-art"

/** The About description's viewport: its height is whole lines of the description plus its inset (see [HomeLaunchCardAboutFill]). */
const val HomeLaunchCardDescriptionViewportTestTag = "home-launch-card/description-viewport"
const val HomeLaunchCardMeasuredViewportTestTag = "home-launch-card/measured-viewport"

/**
 * Stable per-action tap-target identity for the Compose harness. Keyed on the FocusKey the button
 * already owns, so a test names the same thing the FocusAuthority does and no second registry appears.
 */
fun homeLaunchCardActionTestTag(key: FocusKey): String = "home-launch-card/action/${key.value}"

/** Presentation contract that lets Home and Library share one launch-card composition without sharing scopes. */
data class MimirLaunchCardFocusContract(
    val scope: FocusScope,
    val playKey: FocusKey,
    /** Restart: registered only while the card's game is parked behind Home (see [homeLaunchCardRestartAction]). */
    val restartKey: FocusKey,
    val openWithKey: FocusKey,
    val achievementsKey: FocusKey,
    val descriptionKey: FocusKey,
    val titleEditKey: FocusKey,
)

val HomeLaunchCardFocusContract = MimirLaunchCardFocusContract(
    scope = HomeLaunchCardScope,
    playKey = HomeLaunchCardPlayKey,
    restartKey = HomeLaunchCardRestartKey,
    openWithKey = HomeLaunchCardOpenWithKey,
    achievementsKey = HomeLaunchCardAchievementsKey,
    descriptionKey = HomeLaunchCardDescriptionKey,
    titleEditKey = HomeLaunchCardTitleEditKey,
)

data class MimirLaunchCardExtraAction(
    val label: String,
    val key: FocusKey,
    val enabled: Boolean = true,
    val onActivate: () -> Unit,
)

data class HomeLaunchDescriptionScrollState(
    val scrollable: Boolean = false,
    val canScrollUp: Boolean = false,
    val canScrollDown: Boolean = false,
    val measured: Boolean = false,
    val offset: Int = 0,
    val maxOffset: Int = 0,
    val revision: Long = 0L,
    val ownerToken: Long = 0L,
    val settledSequence: Int = 0,
)

data class HomeLaunchDescriptionScrollCommand(
    val sequence: Int = 0,
    /** -1 = toward the beginning; +1 = toward the end. */
    val direction: Int = 0,
    /** Exact session-owned offset restored after the new layout has produced a finite scroll range. */
    val restoreOffset: Int? = null,
    val expectedRevision: Long? = null,
    val ownerToken: Long = 0L,
) {
    init {
        require(direction == 0 || restoreOffset == null) {
            "Description scroll commands cannot page and restore simultaneously"
        }
    }
}

internal fun homeLaunchDescriptionRevision(text: String): Long {
    var hash = -3750763034362895579L
    text.forEach { character ->
        hash = (hash xor character.code.toLong()) * 1099511628211L
    }
    return hash
}

internal fun homeLaunchDescriptionRestoreTarget(
    command: HomeLaunchDescriptionScrollCommand,
    measuredMaxOffset: Int,
    measuredRevision: Long,
    activeOwnerToken: Long,
): Int? {
    val requestedOffset = command.restoreOffset ?: return null
    if (command.ownerToken != activeOwnerToken || measuredMaxOffset == Int.MAX_VALUE || measuredMaxOffset < 0) {
        return null
    }
    return if (command.expectedRevision == measuredRevision) {
        requestedOffset.coerceIn(0, measuredMaxOffset)
    } else {
        0
    }
}

internal fun homeLaunchDescriptionMeasured(viewportHeightPx: Int, maxOffset: Int): Boolean =
    viewportHeightPx > 0 && maxOffset != Int.MAX_VALUE && maxOffset >= 0

internal fun homeLaunchDescriptionCommandPending(
    command: HomeLaunchDescriptionScrollCommand,
    settledSequence: Int,
    activeOwnerToken: Long,
): Boolean = command.ownerToken == activeOwnerToken && command.sequence > settledSequence

internal fun homeLaunchDescriptionRevisionResetTarget(
    observedRevision: Long,
    measuredRevision: Long,
    measuredMaxOffset: Int,
): Int? = if (observedRevision != measuredRevision && measuredMaxOffset in 0 until Int.MAX_VALUE) 0 else null

internal fun homeLaunchDescriptionPublishedOffset(
    observedRevision: Long,
    measuredRevision: Long,
    scrollOffset: Int,
): Int = if (observedRevision == measuredRevision) scrollOffset else 0

fun HomeLaunchDescriptionScrollState.isSettledFor(
    command: HomeLaunchDescriptionScrollCommand,
): Boolean = measured &&
    ownerToken == command.ownerToken &&
    settledSequence >= command.sequence

internal fun homeLaunchDescriptionContinuationGlyph(canScrollUp: Boolean, canScrollDown: Boolean): String? =
    when {
        canScrollUp && canScrollDown -> "↕"
        canScrollDown -> "↓"
        canScrollUp -> "↑"
        else -> null
    }

/**
 * To the LEFT of Play, in the same virtual y-band (0..44) and the same synthetic coordinate space as
 * every other footer entry — deliberately NOT the title's real on-screen position (see
 * [HomeLaunchCardTitleEditKey]). Negative x is fine: `moveFocus`'s geometry only compares relative
 * centers, never clamps to a visible viewport. Fixed at Play's NOMINAL 96dp offset regardless of
 * [homeLaunchCardActionButtonWidths]'s shrink -- this is a pre-open virtual pre-registration standing
 * to Play's LEFT, never a painted element, so it has no rendered width to track (only the three PAINTED buttons below need to
 * become width-aware).
 */
internal val HomeLaunchCardTitleEditBounds = FocusBounds(-96f, 0f, -8f, 44f)

/**
 * The un-stacked footer's three PAINTED buttons, in the same synthetic 0-space [FocusBounds] coordinate
 * every other footer entry uses (dp-shaped numbers, but a virtual row-relative space, never a real
 * on-screen offset -- `y` stays a flat `[0, 44]` band regardless of where the footer actually renders).
 *
 * **Fixed (launch card "resize, don't stack" ticket): reads [homeLaunchCardActionButtonWidths], the
 * SAME solver [HomeLaunchCardFooter]'s un-stacked `Row` now paints with.** Before this fix the three
 * bounds below were hardcoded to the row's NOMINAL 96/120/136dp widths, correct only because
 * [homeLaunchCardActionRowWidth] never actually shrank the row (`minOf(contentWidth, 376.dp)` only ever
 * returns something narrower than 368 when `contentWidth` itself is narrower, and
 * [homeLaunchCardActionsStacked] always switched to the two-row shape before that happened). Once the
 * row can genuinely shrink to clear a reserved M lane (see [homeLaunchCardActionRowWidth]'s own KDoc),
 * a hardcoded map would silently diverge from the painted buttons -- the exact "paint and focus lattice
 * diverge" trap the Files picker hit. Calling the same pure function both places makes divergence
 * structurally impossible instead of merely untested. At the nominal (unshrunk) 376dp width this
 * reproduces the old hardcoded numbers bit-exactly -- see `HomeLaunchCardTest.kt`'s own differential
 * proof.
 */
internal fun homeLaunchCardActionBounds(
    stacked: Boolean,
    actionRowWidth: Float = HomeLaunchCardActionNominalRowWidthDp,
    hasExtraAction: Boolean = false,
    contract: MimirLaunchCardFocusContract = HomeLaunchCardFocusContract,
): Map<FocusKey, FocusBounds> {
    if (hasExtraAction) return homeLaunchCardRestartActionBounds(stacked, actionRowWidth, contract)
    if (!stacked) {
        val widths = homeLaunchCardActionButtonWidths(actionRowWidth.dp, hasExtraAction = false)
        val gap = HomeLaunchCardActionGap.value
        val playWidth = widths[0].value
        val openWithWidth = widths[1].value
        val achievementsWidth = widths[2].value
        val openWithLeft = playWidth + gap
        val achievementsLeft = openWithLeft + openWithWidth + gap
        return mapOf(
            contract.titleEditKey to HomeLaunchCardTitleEditBounds,
            contract.playKey to FocusBounds(0f, 0f, playWidth, 44f),
            contract.openWithKey to
                FocusBounds(openWithLeft, 0f, openWithLeft + openWithWidth, 44f),
            contract.achievementsKey to
                FocusBounds(achievementsLeft, 0f, achievementsLeft + achievementsWidth, 44f),
        )
    }
    val firstRowSpan = HomeLaunchActionPlayWidth.value + HomeLaunchCardActionGap.value + HomeLaunchActionOpenWithWidth.value
    val secondRowTop = 44f + HomeLaunchCardActionGap.value
    val firstRowLeft = ((actionRowWidth - firstRowSpan) / 2f).coerceAtLeast(0f)
    val achievementsLeft = ((actionRowWidth - 136f) / 2f).coerceAtLeast(0f)
    return mapOf(
        contract.titleEditKey to FocusBounds(firstRowLeft - 96f, 0f, firstRowLeft - 8f, 44f),
        contract.playKey to FocusBounds(firstRowLeft, 0f, firstRowLeft + 96f, 44f),
        contract.openWithKey to
            FocusBounds(firstRowLeft + 96f + HomeLaunchCardActionGap.value, 0f, firstRowLeft + firstRowSpan, 44f),
        contract.achievementsKey to
            FocusBounds(achievementsLeft, secondRowTop, achievementsLeft + 136f, secondRowTop + 44f),
    )
}

/**
 * Gated on [emulated] like OpenWith/Achievements: only emulated games carry a
 * [com.gruning.mimir.rewrite.core.model.GameId] a rename can target (scopes the
 * cosmetic override to games the scan/scrape pipeline can misname; Android games/apps are never renamed).
 */
internal fun homeLaunchCardActionKeys(
    emulated: Boolean,
    hasExtraAction: Boolean = false,
    contract: MimirLaunchCardFocusContract = HomeLaunchCardFocusContract,
): List<FocusKey> = buildList {
    if (emulated) add(contract.titleEditKey)
    add(contract.playKey)
    if (emulated && hasExtraAction) add(contract.restartKey)
    if (emulated) {
        add(contract.openWithKey)
        add(contract.achievementsKey)
    }
}

/**
 * Registers the whole controller graph before the scope opens, so a fast second A always reaches Play. One registrar for both doors: [contract] names the
 * scope and the keys (Home's by default, the Library's from its adapter), and the geometry is the one [homeLaunchCardActionBounds] solves.
 */
fun registerHomeLaunchCardActions(
    authority: FocusAuthority,
    emulated: Boolean,
    stacked: Boolean = false,
    actionRowWidth: Float = HomeLaunchCardActionNominalRowWidthDp,
    hasExtraAction: Boolean = false,
    contract: MimirLaunchCardFocusContract = HomeLaunchCardFocusContract,
) {
    val bounds = homeLaunchCardActionBounds(stacked, actionRowWidth, hasExtraAction, contract)
    homeLaunchCardActionKeys(emulated, hasExtraAction, contract).forEach { key ->
        authority.registerVirtual(key, contract.scope, bounds.getValue(key))
    }
    // The Restart key lives only while its game is parked: a card that no longer carries it must not keep it reachable.
    if (!(emulated && hasExtraAction)) authority.unregisterVirtual(contract.restartKey)
}

fun unregisterHomeLaunchCardActions(
    authority: FocusAuthority,
    contract: MimirLaunchCardFocusContract = HomeLaunchCardFocusContract,
) {
    listOf(
        contract.titleEditKey,
        contract.playKey,
        contract.restartKey,
        contract.openWithKey,
        contract.achievementsKey,
    ).forEach(authority::unregisterVirtual)
}

fun enterHomeLaunchCard(authority: FocusAuthority, emulated: Boolean) {
    registerHomeLaunchCardActions(authority, emulated)
    authority.rememberFocusForScope(HomeLaunchCardScope, HomeLaunchCardPlayKey)
    authority.pushScope(HomeLaunchCardScope)
    authority.requestFocus(HomeLaunchCardPlayKey)
}

fun exitHomeLaunchCard(authority: FocusAuthority) {
    if (authority.state.value.scope == HomeLaunchCardScope) authority.popScope()
    unregisterHomeLaunchCardActions(authority)
}

/** The footer keys, other than Play (which has its own rung), whose UP reaches the rename mark when nothing sits above them. */
internal fun homeLaunchCardUpReachesRename(
    focused: FocusKey?,
    contract: MimirLaunchCardFocusContract = HomeLaunchCardFocusContract,
): Boolean =
    focused == contract.restartKey || focused == contract.openWithKey || focused == contract.achievementsKey

fun dispatchHomeLaunchCard(
    authority: FocusAuthority,
    emulated: Boolean,
    action: ControllerAction,
    onPlay: () -> Unit,
    onEdit: () -> Unit,
    onEditTitle: () -> Unit,
    onAchievements: () -> Unit,
    onOpenWith: () -> Unit,
    onClose: () -> Unit,
    descriptionScrollState: HomeLaunchDescriptionScrollState = HomeLaunchDescriptionScrollState(),
    onDescriptionScroll: (direction: Int) -> Unit = {},
    contract: MimirLaunchCardFocusContract = HomeLaunchCardFocusContract,
): Boolean {
    if (authority.state.value.scope != contract.scope) return false
    return when (action) {
        ControllerAction.NAV_UP,
        ControllerAction.NAV_DOWN,
        ControllerAction.NAV_LEFT,
        ControllerAction.NAV_RIGHT,
        -> {
            val focused = authority.state.value.focused
            if (focused == contract.descriptionKey) {
                when (action) {
                    ControllerAction.NAV_UP -> if (descriptionScrollState.canScrollUp) {
                        onDescriptionScroll(-1)
                    } else {
                        // Deterministic vertical ladder (): once Description has nothing
                        // left to scroll, UP continues past it into the rename mark rather than bouncing
                        // back down to Play — Play now sits only BELOW Description on this ladder.
                        // CHANGED EDGE (was HomeLaunchCardPlayKey): see
                        // focusedDescriptionReturnsToEditWhenUpReachesTheBeginning.
                        authority.requestFocus(contract.titleEditKey)
                    }
                    ControllerAction.NAV_DOWN -> if (descriptionScrollState.canScrollDown) {
                        onDescriptionScroll(1)
                    } else {
                        // Unchanged: DOWN out of Description still exits to Play — the ladder only grew
                        // upward (Edit), the downward exit target never moved.
                        authority.requestFocus(contract.playKey)
                    }
                    ControllerAction.NAV_LEFT, ControllerAction.NAV_RIGHT -> Unit
                    else -> Unit
                }
            } else if (focused == contract.titleEditKey && action == ControllerAction.NAV_DOWN) {
                // Deterministic vertical ladder: DOWN from Edit is the mirror image of UP from Play —
                // it lands on Description when there is one to visit, otherwise straight on Play.
                // CHANGED EDGE (was HomeLaunchCardTitleEditKey, i.e. a self dead-end): see
                // emulatedSpatialGraphPinsAllSixteenDirectionalMoves. LEFT/RIGHT and UP from Edit are
                // untouched — UP still falls through to the existing scrollable-Description branch below
                // (pre-existing, unchanged) or to the geometric `moveFocus` self-loop at the top of the
                // ladder when there is nothing to scroll.
                if (descriptionScrollState.scrollable) {
                    authority.requestFocus(contract.descriptionKey)
                } else {
                    authority.requestFocus(contract.playKey)
                }
            } else if (focused == contract.playKey && action == ControllerAction.NAV_UP && emulated) {
                // Deterministic vertical ladder : Play's UP always continues the
                // ladder now — to Description when there is one to scroll, straight to Edit when there
                // is not (a short description that never registers as focusable must not dead-end the
                // ladder one rung early). Gated on `emulated` (Edit does not exist on non-emulated
                // cards). Restart/OpenWith/Achievements reach Edit by the branch below it (sweep 10-09).
                // CHANGED EDGE (non-scrollable case was HomeLaunchCardPlayKey, i.e. self): see
                // emulatedSpatialGraphPinsAllSixteenDirectionalMoves.
                if (descriptionScrollState.scrollable) {
                    authority.requestFocus(contract.descriptionKey)
                } else {
                    authority.requestFocus(contract.titleEditKey)
                }
            } else if (focused == contract.playKey && action == ControllerAction.NAV_DOWN) {
                // // this is the one F1 mixed-coordinate-space instance that actually fires. Mirrors the
                // TitleEditKey+NAV_DOWN branch above rather than inventing anything new. Previously this
                // fell through to the bare `authority.moveFocus(action)` below, which compares Play's
                // SYNTHETIC bounds (HomeLaunchCardPlayBounds, y in [0,44]) against Description's PHYSICAL
                // bounds (real on-screen pixels, registered only `if (scrollable)` -- see the
                // `.mimirFocusable(...)` gate on the description Box). Description's real centerY sits
                // hundreds of px below the synthetic footer, so it passed the NAV_DOWN filter as the ONLY
                // candidate and won regardless of the 3x cross-axis penalty -- correct by construction,
                // not by a fair geometric contest, and only because nothing else in the ring sits below
                // Play in either coordinate space. An explicit `requestFocus` hop, keyed only on
                // `descriptionScrollState.scrollable`, removes the coordinate-space comparison entirely:
                // the destination no longer depends on wherever Description happens to be registered.
                // `DefaultFocusAuthority`/`moveFocus` itself is untouched (policy: fix the firing
                // instance only, leave the focus primitive alone).
                if (descriptionScrollState.scrollable) {
                    authority.requestFocus(contract.descriptionKey)
                } else {
                    authority.requestFocus(contract.playKey)
                }
            } else if (action == ControllerAction.NAV_UP && descriptionScrollState.scrollable) {
                // Pre-existing, UNCHANGED: any other footer key (OpenWith, Achievements, or Edit itself)
                // still reaches a scrollable Description on UP through this generic fallback — the ladder
                // addition above only intercepts Play (with its own explicit branch) and never narrows
                // this existing reach.
                authority.requestFocus(contract.descriptionKey)
            } else if (action == ControllerAction.NAV_UP && emulated && homeLaunchCardUpReachesRename(authority.state.value.focused, contract)) {
                // The rest of the button row reaches the rename mark too (sweep 10-09: UP from ACHIEVEMENTS found nothing). Play's rung above already does; this is the same
                // hop for Restart, Open with and Achievements. The row's own geometric neighbour comes first, so a stacked card's second row still steps UP to the row above
                // it; only a key with nothing above it (every key of the single row, the first row of the stacked one) continues the ladder to the rename mark.
                if (!authority.moveFocus(action)) authority.requestFocus(contract.titleEditKey)
            } else {
                authority.moveFocus(action)
            }
            true
        }
        ControllerAction.CONFIRM -> {
            when (authority.state.value.focused) {
                contract.playKey -> onPlay()
                // only an emulated card registers this key, and only while its game is parked; a press files the request, then plays.
                contract.restartKey -> if (emulated) {
                    HomeLaunchCardResume.requestRestart()
                    onPlay()
                }
                contract.titleEditKey -> if (emulated) onEditTitle()
                contract.openWithKey -> if (emulated) onOpenWith()
                contract.achievementsKey -> if (emulated) onAchievements()
                contract.descriptionKey -> {
                    if (descriptionScrollState.canScrollDown) {
                        onDescriptionScroll(1)
                    } else {
                        authority.requestFocus(contract.playKey)
                    }
                }
            }
            true
        }
        ControllerAction.TERTIARY -> {
            onEdit()
            true
        }
        ControllerAction.SYSTEM_SELECT -> {
            if (emulated) onAchievements()
            true
        }
        ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK -> {
            onClose()
            true
        }
        else -> true
    }
}

internal data class HomeLaunchCardLayout(
    val outerHorizontal: Dp,
    val outerVertical: Dp,
    val coverColumnWidth: Dp,
    val coverHeight: Dp,
    val columnGap: Dp,
    val descriptionMaxLines: Int,
    val compact: Boolean,
)

/**
 * Launch-card padding convergence. Supersedes the piecewise version this pass replaced,
 * which reproduced four hard pins in `HomeLaunchCardTest.kt` (380/480/720/960dp -> 14/14/28/44dp) bit-exact
 * because at the time this ticket's writable scope did not include that file.
 *
 * **The four pins were verified synthetic before being moved, not assumed so.** `git log -L` on the pinned
 * assertions (`HomeLaunchCardTest.kt`, `footerActionClusterStaysCompactAndCenteredInsteadOfGrowingWithTheFooter`)
 * traces every value back through commits `ad10fbfd`, `78e97bf5`, `dc398c5b`, `5603e9d9` — each a generic
 * "settle …" pass with no KDoc or commit message claiming a device measurement, a shipping-app value or an
 * policy behind any of the four widths. Cross-checked against `MimirCertifiedViewports`: none of
 * 380/480/720/960 is a certified DEVICE row (Odin 832.5, Nova 640, RP5-class shelf 700) or matches a
 * certified SYNTHETIC_BOUNDARY row's actual box — 480 and 720 coincide with `CompactSquare480`/
 * `CompactSquare720`'s WIDTH only; those rows are square (480x480 / 720x720, COMPACT_SQUARE posture) while
 * the pinned tests use 480x320 and 720x432 (STANDARD posture, a different `heightBand` arm entirely), so
 * they are not the same box under a different name. All four are confirmed synthetic test-fixture artifacts
 * that happen to sample [homeLaunchCardContentBands]'s own band edges — never a shipped or measured width.
 *
 * This card is `fillMaxSize()` in an UN-INSET host (see the `BoxWithConstraints` in [HomeLaunchCard] below)
 * -- it receives the FULL viewport, never the shelf's post-inset box, unlike the other three surfaces the
 * Odin-parity harness measures -- so the anchor below is `MimirCertifiedViewports.Odin.fullViewportDp`
 * (832.5dp wide), not the 833dp post-inset box other surfaces solve against.
 *
 * Single global proportional law -- the exact same shape [homeLaunchCardOuterVertical] below already used,
 * unconstrained, for `outerVertical`. **The Odin stays bit-exact at 44.0dp**, proved (not asserted) in
 * `HomeLaunchCardLayoutGoldenTest.theOdinsOuterPaddingIsUnmoved`: `LaunchCardOuterHorizontalRatio` is
 * `44f / 832.5f`, so multiplying back by `832.5f` is float32's `x * (y / x) == y` for this exact pair -- no
 * rounding survives the round trip. A pure proportional law also makes `launchCard.chromeShare`
 * (`outerHorizontal*2/width`) CONSTANT at every width by construction -- it reduces algebraically to
 * `2 * ratio` regardless of `w` -- so the Odin-parity harness's Nova and RP5-class-shelf
 * `launchCard.chromeShare` rows now converge FULLY (delta ~1e-8, several orders below `ClassOneTolerance`
 * 0.005) rather than merely ratcheting down; both rows are deleted from `MimirOdinParityTest.kt`'s baseline
 * register. Monotonicity holds trivially (a positive-slope line through the origin only ever increases) and
 * is still swept densely in `HomeLaunchCardLayoutGoldenTest.outerHorizontalNeverShrinksAsWidthGrows`, per the
 * ticket's own "assert it anyway" instruction.
 */
private const val LaunchCardOdinAnchorWidthDp = 832.5f
private const val LaunchCardOdinOuterHorizontalDp = 44f
private const val LaunchCardOdinOuterVerticalDp = 24f
private const val LaunchCardOuterHorizontalRatio = LaunchCardOdinOuterHorizontalDp / LaunchCardOdinAnchorWidthDp
private const val LaunchCardOuterVerticalRatio = LaunchCardOdinOuterVerticalDp / LaunchCardOdinAnchorWidthDp

/** See [homeLaunchCardOuterHorizontal]'s doc comment above for the full derivation. Same shape as
 * [homeLaunchCardOuterVertical] below -- no piecewise band survives this pass. */
internal fun homeLaunchCardOuterHorizontal(width: Dp): Dp = (width.value * LaunchCardOuterHorizontalRatio).dp

/** Unconstrained by any frozen pin (see [homeLaunchCardOuterHorizontal]'s doc) -- a single proportional law. */
internal fun homeLaunchCardOuterVertical(width: Dp): Dp = (width.value * LaunchCardOuterVerticalRatio).dp

/**
 * RC-3 proportional-art correction, 2026-08-21. The two OWED items
 * [homeLaunchCardContentBands] used to carry -- the `>= 520.dp` arm's `minOf(heightBand, 176.dp)` clamp
 * always winning (every `heightBand` output already exceeds 176dp, so `coverHeight` was a constant 176dp on
 * the arm BOTH certified 4:3 devices take), and the `else` arm hardcoding 112dp without reading `height` at
 * all -- are fixed here. Cover art was 43% smaller in proportion on the Nova than the Odin while
 * `launchCard.chromeShare` reported delta 0.0000, because that proportion only ever measured outer padding.
 *
 * **Axis: HEIGHT, not width.** The two axes disagree (the panels have different aspects): matching the
 * Odin's width share gives the Nova only ~230dp of art height; matching its height share gives ~307dp. The
 * geometry doc's own recommendation, taken here: the art is a portrait object, height is what constrains
 * it, and a 4:3 panel has proportionally MORE vertical room than the Odin's 16:9 one -- it should get more
 * art, not less. Cross-checked against ground truth, not just a second derivation: `176.dp / 480.dp` (the
 * PRE-fix Nova) is 36.67%, against the Nova capture's own measured 36.7%; `300.dp / 468.3.dp` (the Odin) is
 * 64.06% against its measured 63.9% -- the height-share model reproduces both captures, which a coincidence
 * of arithmetic would not.
 *
 * Same shape as [homeLaunchCardOuterHorizontal] above -- one Odin-anchored proportional law, no bands -- but
 * anchored on **height**, the term these two quantities are actually bound by, exactly as
 * [homeLaunchCardOuterHorizontal] is anchored on width because outer padding is bound by the card's own
 * horizontal extent. `coverColumnWidth` is a SECOND independent ratio against the same height input (not
 * `coverHeight * constant`, though the two are algebraically the same to float32 rounding noise) -- this
 * reproduces the Odin's own box aspect (288/300 = 0.96) at every viewport without introducing a second input
 * variable, and mirrors [homeLaunchCardOuterVertical] carrying its own ratio alongside
 * [homeLaunchCardOuterHorizontal] rather than being derived from it. **This height-anchored ratio is now
 * only the HEIGHT term of a two-term `minOf` (Regression 1, below) -- see [homeLaunchCardCoverColumnWidth]'s own KDoc, immediately below the
 * `LaunchCardCoverColumnWidthCapRatio` constant, for the added width-anchored term and why. This paragraph's
 * "reproduces the Odin's own box aspect at every viewport" no longer holds off the Odin -- only AT the Odin,
 * where the new width term coincides exactly with this one.**
 *
 * The Odin stays bit-exact -- 300.0dp / 288.0dp, proved (not asserted) in
 * `HomeLaunchCardLayoutGoldenTest.theOdinsCoverArtBoxIsUnmoved` by the same `x * (y / x) == y` float32
 * identity [homeLaunchCardOuterHorizontal]'s KDoc uses: `LaunchCardCoverHeightRatio` is `300f / 468.3f`, so
 * multiplying back by `468.3f` recovers `300f` exactly; same for `288f / 468.3f`.
 *
 * **Not addressed here, and disclosed rather than silently accepted (pending geometry review):**
 * growing the Nova's art this much (176 -> ~307.5dp, +75%) makes the Compact arm's header+art `Row` taller
 * while the header's own text content does not grow with it -- the "dead space under the title" the
 * geometry doc's Defect B already named gets WORSE by construction, not better, pushing the metadata block
 * (description, fact rows) further down before the card's `verticalScroll` reaches it. Nothing collides or
 * overflows (the scroll container absorbs it, per this file's own `HomeLaunchCardCompact`), but less content
 * is visible before the first scroll than today. Defect B is explicitly "not yet dispatched" in that doc and
 * is not this ticket's file scope to restructure.
 */
private const val LaunchCardOdinAnchorHeightDp = 468.3f
private const val LaunchCardOdinCoverHeightDp = 300f
private const val LaunchCardOdinCoverColumnWidthDp = 288f
private const val LaunchCardCoverHeightRatio = LaunchCardOdinCoverHeightDp / LaunchCardOdinAnchorHeightDp
private const val LaunchCardCoverColumnWidthRatio = LaunchCardOdinCoverColumnWidthDp / LaunchCardOdinAnchorHeightDp

/**
 * Regression 1: starved text column on Nova build `3add68a0`, 2026-08-21. The height-anchored
 * ratio above was correct for the ART (see [homeLaunchCardCoverHeight]'s KDoc, the axis argument stands
 * unchanged) but it is ALSO what [homeLaunchCardCoverColumnWidth] used until this ticket, and that is the
 * starved-text-column defect: on a 4:3 panel, height is a BIGGER fraction of width than on the Odin's
 * 16:9 one, so a purely height-anchored column swallows a disproportionate WIDTH share. Measured
 * `[D]`: at 640x480 (Nova) the old law gave a 295.2dp column -- 46.1% of width, against the Odin's own
 * 288/832.5 = 34.6% -- starving `homeLaunchCardTextColumnWidth` down to the range that truncates
 * "Call of Duty: M...", "EXTERNAL" and "NOT TRACK..." in the capture.
 *
 * **The fix is a second, independent Odin-anchored law on the SAME pair this file already anchors
 * [homeLaunchCardOuterHorizontal] on -- width -- taken as the tighter of two constraints:**
 * `coverColumnWidth = minOf(height * (288/468.3), width * (288/832.5))`. Both ratios are fixed points of
 * the Odin's own numbers (832.5 wide, 468.3 tall, 288dp column), so at the Odin BOTH terms evaluate to
 * exactly 288.0dp by the same float32 `x * (y / x) == y` identity [homeLaunchCardOuterHorizontal]'s KDoc
 * already relies on -- the min of two identical bit-exact values is that value, so "the Odin must not
 * move" holds by construction, not by re-derivation. `[D]` cross-check: `288f/832.5f*832.5f == 288f` and
 * `288f/468.3f*468.3f == 288f` both hold to the last bit; see
 * `HomeLaunchCardLayoutGoldenTest.theOdinsCoverArtBoxIsUnmoved`.
 *
 * **Which term binds is the panel's own ASPECT relative to the Odin's (832.5/468.3 = 1.7778).** Algebra:
 * `heightAnchored < widthAnchored` iff `height/width < (288/468.3)/(288/832.5)` iff `width/height >
 * 1.7778` -- i.e. the width term only binds on a panel NARROWER (relative to its own height) than the
 * Odin. RP5-class shelf (700x350, aspect 2.0) stays on the height law, unmoved by this ticket (`[D]`
 * 215.2dp both before and after -- width term would give 242.2dp, loses the `minOf`). The Nova (aspect
 * 1.333), the 538dp companion (aspect 1.212) and the COMPACT_SQUARE floors (aspect <= ~1.0) all move to
 * the width term. Because `coverColumnWidth / width` reduces algebraically to the constant `288/832.5`
 * wherever the width term binds, `launchCard.artWidthShare` (`MimirOdinParityTest.kt`) converges FULLY --
 * delta 0.0000 -- at every viewport this ticket moves, the same mechanism that already converged
 * `launchCard.chromeShare`. Its three worst baseline rows (Nova 0.1153, the 538 companion 0.1616, the
 * 1080 square 0.2690) go STALE and are deleted, not updated -- see that file's own comment block.
 *
 * **Freed text-lane width, `[D]` from a real run (see `MimirOdinParityTest.kt`'s new `launchCard.textLaneShare`
 * proportion, whose formula this paragraph's numbers reproduce):** Nova's column moves 295.1954dp ->
 * 221.4054dp, freeing 73.79dp for [homeLaunchCardTextColumnWidth] on every call site that subtracts it
 * (`HomeLaunchCardWide`'s `Row`, `HomeLaunchCardCompact`'s header `Row` alike -- both place
 * [HomeLaunchCardFreeArt] at this same width, so the win is not Wide-skeleton-only): the pure law's own
 * text column moves 257.1530dp -> 330.9430dp, i.e. its share of the 640dp viewport moves 40.18% -> 51.71%,
 * past the Odin's own 420.5000dp / 832.5dp = 50.51% share (a 1.20pp overshoot, tracked as this ticket's
 * new, honestly RED `launchCard.textLaneShare` baseline row rather than tuned away). This is NOT proven to
 * eliminate the captured truncation -- `core-ui` carries no text-measurement harness (that evidence limit remains) -- so treat it as bracketed, OWED confirmation via a recapture, not
 * a closed claim.
 *
 * **Disclosed cost, not silently absorbed (pending review).** [homeLaunchCardCoverHeight]
 * (the BOX's reserved height) is untouched by this ticket, and the specific regression captured -- a
 * ~0.72-aspect cover, the same as [DefaultCoverAspect] in `GamelistCarousel.kt` -- stays EXACTLY at its
 * configured ~63-64% share: `[D]` its natural rendered width at that aspect (217.9908dp -- this line read
 * 218.09dp until S2 re-derived it; `sqrt(66_000 * 0.72)` is 217.9908, the two digits were transposed, and
 * the argument this figure supports is unaffected either way, from
 * [homeLaunchCardArtSize]'s own equal-area law bounded only by `coverHeight`) is narrower than the NEW
 * 221.4dp cap, so it renders unclamped, identically to before this ticket, on both axes. But
 * [homeLaunchCardArtSize] scales width and height TOGETHER for a fixed target area, so any platform whose
 * cover is WIDER than ~0.743 aspect (the exact breakeven, `221.4dp^2/66_000dp^2`) now clips against the
 * tighter column and its RENDERED height drops below the box's 64% share, for the FIRST time under this
 * exact width -- these platforms were already clipped in degree (the equal-area law is documented as
 * "uniformly reduced only when its aspect reaches a configured lane bound" -- see [homeLaunchCardArtSize]'s own
 * KDoc), this ticket only widens the affected band and deepens it. `[D]` on the Nova, before -> after this
 * ticket, rendered art height as a share of the 480dp viewport (`PlatformCoverAspects`,
 * `GamelistCarousel.kt`): GB/GBA/Dreamcast/Saturn (aspect 1.00) 53.5% -> 46.1%; PS1/PSX (1.16) 49.7% ->
 * 39.8%; SNES (1.36) 45.2% -> 33.9%; N64 (1.46) 42.1% -> 31.6% -- all already below the 64% target
 * pre-ticket, all lower after. **The SAME `edge = minOf(maxWidth, maxHeight)` square-art path
 * [HomeLaunchCardFreeArt] uses for every NON-emulated home-shelf item (Android games/apps -- its
 * `!state.emulated` branch, this same file) is affected identically to the aspect-1.0 row above** -- every
 * Android game/app tile on the Nova now renders its square art at 221.4dp instead of 295.2dp on a side, not just
 * the emulated-platform aspect table. Nothing collides or overflows; this is a real, measured reduction in
 * on-screen art size for a broad, common class of library items, disclosed rather than hidden because a
 * narrower column is what frees the text lane in the first place -- there is no cap that fixes truncation
 * without moving this number for aspects above the breakeven. The art-size trade-off remains pending hardware review.
 */
private const val LaunchCardCoverColumnWidthCapRatio = LaunchCardOdinCoverColumnWidthDp / LaunchCardOdinAnchorWidthDp

/** See this constant block's KDoc above for the full derivation -- height-anchored, no bands. */
internal fun homeLaunchCardCoverHeight(height: Dp): Dp = (height.value * LaunchCardCoverHeightRatio).dp

/** See [LaunchCardCoverColumnWidthCapRatio]'s KDoc above for the full two-constraint derivation, the Odin
 * fixed-point proof, the axis-binding algebra and the disclosed wide-aspect/non-emulated-square cost.
 * `coverHeight` stays a pure function of height alone ([homeLaunchCardCoverHeight] above); ONLY
 * `coverColumnWidth` gains the width term, so [coverBoxNoLongerDependsOnWidthAtAll] in
 * `HomeLaunchCardLayoutGoldenTest.kt` is split rather than left standing on its old, now-false premise. */
internal fun homeLaunchCardCoverColumnWidth(width: Dp, height: Dp): Dp = minOf(
    height.value * LaunchCardCoverColumnWidthRatio,
    width.value * LaunchCardCoverColumnWidthCapRatio,
).dp

internal fun homeLaunchCardLayout(width: Dp, height: Dp): HomeLaunchCardLayout =
    homeLaunchCardContentBands(width).copy(
        outerHorizontal = homeLaunchCardOuterHorizontal(width),
        outerVertical = homeLaunchCardOuterVertical(width),
        coverColumnWidth = homeLaunchCardCoverColumnWidth(width, height),
        coverHeight = homeLaunchCardCoverHeight(height),
    )

/**
 * CONTENT dimensions only that remain width-banded (the description line count and the wide/compact split)
 * -- never `outerHorizontal`/`outerVertical`/`coverColumnWidth`/`coverHeight`, all four of which
 * [homeLaunchCardLayout] above overrides with a continuous Odin-anchored law. `columnGap` also stays a
 * per-band literal (OWED, not fixed this pass -- this ticket's scope was `coverHeight`/`coverColumnWidth`
 * only). [ContentBandCoverPlaceholderDp] fills the two positional slots [homeLaunchCardLayout] always
 * overrides -- named so a future reader does not mistake it for a live value the way the old per-arm
 * literals here (156/288/250/112, and the `heightBand` three-tier clamp) invited before this pass, when they
 * silently encoded the exact bug this pass fixes.
 */
private val ContentBandCoverPlaceholderDp = 0.dp

private fun homeLaunchCardContentBands(width: Dp): HomeLaunchCardLayout = when {
    width >= 800.dp ->
        HomeLaunchCardLayout(44.dp, 24.dp, ContentBandCoverPlaceholderDp, ContentBandCoverPlaceholderDp, 36.dp, 6, false)
    width >= 720.dp ->
        HomeLaunchCardLayout(28.dp, 20.dp, ContentBandCoverPlaceholderDp, ContentBandCoverPlaceholderDp, 28.dp, 4, false)
    width >= 520.dp ->
        HomeLaunchCardLayout(20.dp, 16.dp, ContentBandCoverPlaceholderDp, ContentBandCoverPlaceholderDp, 20.dp, 4, true)
    else -> HomeLaunchCardLayout(14.dp, 12.dp, ContentBandCoverPlaceholderDp, ContentBandCoverPlaceholderDp, 14.dp, 3, true)
}

/**
 * Launch-card skeleton convergence (one skeleton fed by [band] parameter values, named as the fix for the Wide/Compact divergence that hid a
 * real defect once before).
 *
 * **What the two arms actually differ on, structurally.** In [HomeLaunchCardCompact], header and art share
 * one `Row`; `Row` sizes to its tallest child, so once the art grows to [homeLaunchCardCoverHeight] (307.5dp
 * on the Nova, post Defect A) the Row's own box is 307.5dp tall even though the header's own text content
 * only needs ~70-90dp of it -- [HomeLaunchCardMetadata] is the Row's NEXT sibling in the same scrolling
 * Column, so it starts at the Row's bottom, not the header's. [HomeLaunchCardWide] never had this: art sits
 * in its own `Box(fillMaxHeight)`, a sibling of the header+metadata `Column`, so Metadata starts right after
 * the header's own content regardless of the art's height. **Wide's shape is the fix; this function decides
 * where it is SAFE to apply it.**
 *
 * **Why not everywhere `layout.compact` is true.** [homeLaunchCardCoverColumnWidth] narrows the WIDTH share
 * it reserves on a near-square viewport (the `minOf(heightAnchored, widthAnchored)` law -- see its own KDoc), but even the
 * narrower column still eats enough of a near-square viewport's WIDTH that `HomeLaunchCardWide`'s text
 * column would fall well short of the STANDARD-postured rows' own lane: measured (this ticket's own real
 * run) 320x300 -> 161.47dp of text column (110.7027dp column, was 87.68dp before this ticket's 184.4971dp
 * column), MinimumFloor320x300's own certified box -- narrower than what a STANDARD row like the Nova now
 * gets (330.94dp, `launchCard.textLaneShare`, `MimirOdinParityTest.kt`) and UNPROVEN readable (no
 * text-measurement harness exists in this tree -- see `LaunchCardCoverColumnWidthCapRatio`'s own KDoc).
 * Those viewports must keep the stacked shape until that is actually measured; this ticket does not move
 * this gate or claim 161.47dp is enough -- only that it is a real number now, not the stale 87.68dp one.
 *
 * **The selector reuses [mimirViewportProfile].** No third [MimirViewportPosture] is needed; callers
 * should use its measured dimensions. [MimirCertifiedViewports] already
 * carries a `posture` column for every certified row (`feature-library`'s own test fixture), computed from
 * this exact function. Standard wide rows retain the existing layout decision; an active compact primary
 * profile forces the stacked shape even when the broad-window width solver would otherwise choose wide.
 */
internal fun homeLaunchCardUsesWideSkeleton(
    width: Dp,
    height: Dp,
    layout: HomeLaunchCardLayout,
    viewportProfile: MimirViewportProfile? = null,
): Boolean {
    val profileOverride = viewportProfile?.takeIf { it.widthDp > 0f }
    val profile = profileOverride
        ?: mimirViewportProfile(width.value, height.value)
    // A persisted Compact choice is an explicit primary-layout override, including on a broad
    // window. Pure geometry callers keep the established layout band and automatic posture rule.
    return if (profileOverride?.isCompact == true) {
        false
    } else {
        !layout.compact || !profile.isCompact
    }
}

/** The footer action cluster follows the metadata column, never the full modal/screen width. Mirrors
 * [homeLaunchCardUsesWideSkeleton] exactly -- callers pass the SAME boolean they used to pick the render
 * arm, so this pure function can never silently describe a different skeleton than the one that actually
 * rendered (the two used to be able to drift: this used to re-derive `layout.compact` on its own, which
 * stayed true for the Nova/RP5-shelf/538 even after [homeLaunchCardUsesWideSkeleton] moved them to Wide).
 *
 * **This solver takes no M-anchor inset, and no longer can.** It once carried an inset parameter
 * mirroring a reserved lane the render applied to the header+art `Box`. That reservation is retired
 * product-wide (no surface reserves for the orb at all), so the pure function
 * and the render agree by having nothing to disagree about. */
internal fun homeLaunchCardTextColumnWidth(
    totalWidth: Dp,
    layout: HomeLaunchCardLayout,
    wideSkeleton: Boolean,
): Dp {
    val contentWidth = (totalWidth - layout.outerHorizontal * 2).coerceAtLeast(0.dp)
    return if (!wideSkeleton) {
        contentWidth
    } else {
        (contentWidth - layout.coverColumnWidth - layout.columnGap).coerceAtLeast(0.dp)
    }
}

/** Nominal, un-shrunk button widths -- the ONLY place these four numbers are literals. Every solver and
 * the render itself ([HomeLaunchCardFooter]'s un-stacked `Row`) reads this list, so paint and the
 * width-solve below cannot drift the way the hardcoded focus bounds this ticket also fixes once did.
 * `Play`(96) is deliberately the smallest of the four -- see [homeLaunchCardActionRowFloorWidth]'s KDoc
 * for why that fact, not an assumption, is what fixes the floor's own scale factor at 0.5. */
private val HomeLaunchActionPlayWidth = 96.dp
private val HomeLaunchActionExtraWidth = 136.dp
private val HomeLaunchActionOpenWithWidth = 120.dp
private val HomeLaunchActionAchievementsWidth = 136.dp

/**
 * The gap between the launch card's action buttons, both across a row and between the two rows of the stacked shape: the focus ring's whole reach past a button
 * ([MimirFocusTileRingReach], 14 dp; these buttons ask for no arrival flare, so that is all of it). It was 8 dp, which let the Achievements / Play ring cross into the
 * neighbouring Open with button , then a literal 12 dp, which still left a focused PLAY's halo touching FAVORITE (sweep 10-09, 25b): a literal that
 * was a guess at the reach instead of the reach. The one constant: every row, every solver and every focus bound reads it, so paint and focus cannot drift apart.
 */
internal val HomeLaunchCardActionGap: Dp = MimirFocusTileRingReach

/** The nominal (un-shrunk) action row: the button widths and the gaps between them, 380 dp for three buttons and 530 dp for four. */
internal fun homeLaunchCardActionNominalRowWidth(hasExtraAction: Boolean): Dp {
    val nominal = homeLaunchCardActionNominalButtonWidths(hasExtraAction)
    return nominal.fold(0.dp) { acc, w -> acc + w } + HomeLaunchCardActionGap * (nominal.size - 1)
}

/** [homeLaunchCardActionNominalRowWidth] for the three-button row, as the dp number the focus bounds and their defaults are written in. */
internal val HomeLaunchCardActionNominalRowWidthDp: Float = homeLaunchCardActionNominalRowWidth(hasExtraAction = false).value

/** Render order, Play first: [HomeLaunchCardFooter]'s un-stacked `Row` composes its children in exactly
 * this sequence, so a caller destructuring the returned list by position (that composable; also
 * [homeLaunchCardActionBounds]) stays aligned with what actually paints. 376dp (3 buttons, 2 gaps) or
 * 524dp (4, 3 gaps) -- [homeLaunchCardActionRowWidth]'s own historical literals -- are this list's own
 * sum plus `MimirSpacing.sm` gaps, never re-typed as a second constant. */
internal fun homeLaunchCardActionNominalButtonWidths(hasExtraAction: Boolean): List<Dp> =
    if (hasExtraAction) {
        listOf(HomeLaunchActionPlayWidth, HomeLaunchActionExtraWidth, HomeLaunchActionOpenWithWidth, HomeLaunchActionAchievementsWidth)
    } else {
        listOf(HomeLaunchActionPlayWidth, HomeLaunchActionOpenWithWidth, HomeLaunchActionAchievementsWidth)
    }

/**
 *  Reverses the prior sizing rule this same
 * function carried (fixed-width Class 2 content, held rather than fractioned) to handle the sizing failure: the buttons are allowed to shrink, uniformly, before the row is allowed to stack.
 *
 * **Uniform scale, not "shrink the widest button" or "eat the gaps."** Gaps stay [HomeLaunchCardActionGap] (the ring's reach)
 * fixed -- compressing them buys at most 3×14=42dp back at the true floor for a rhythm change on every
 * certified viewport, which would change the rhythm across viewports. Scaling only the widest button
 * (Achievements/extra, 136dp) would fix the sum without preserving what the four widths encode --
 * Play's 96dp/Achievements' 136dp ratio is deliberate emphasis (primary action narrower, secondary wider
 * label), and a non-uniform solve would distort that ratio as width tightens instead of preserving it.
 * Uniform scale keeps every button's SHARE of the row constant, so the row reads as the same shape,
 * smaller, following the proportional-dp layout already used for the art column
 * and outer padding.
 */
internal fun homeLaunchCardActionButtonWidths(rowWidth: Dp, hasExtraAction: Boolean): List<Dp> {
    val nominal = homeLaunchCardActionNominalButtonWidths(hasExtraAction)
    val gaps = HomeLaunchCardActionGap * (nominal.size - 1)
    val nominalButtonsTotal = nominal.fold(0.dp) { acc, w -> acc + w }
    if (rowWidth >= nominalButtonsTotal + gaps || nominalButtonsTotal <= 0.dp) return nominal
    val scale = ((rowWidth - gaps).coerceAtLeast(0.dp).value / nominalButtonsTotal.value).coerceIn(0f, 1f)
    return nominal.map { it * scale }
}

/**
 * The genuine floor: the narrowest row [homeLaunchCardActionButtonWidths] can produce while every button
 * still clears `MimirSpacing.minTarget` (48dp) -- below this, uniform scale would push a button under the
 * touch-target floor, and stacking (two rows, each comfortably wide) becomes the only correct shape.
 * Because [HomeLaunchActionPlayWidth] (96dp) is the smallest of the four nominal widths, it is always the
 * FIRST button uniform scale would push under 48dp, so the row-wide floor reduces to one scale factor --
 * `48/96 = 0.5` -- applied to the whole nominal sum, not four independent per-button floors summed (that
 * would be the "shrink each to 48 and stack the difference" shape this ticket rejected above; it does not
 * correspond to any width [homeLaunchCardActionButtonWidths] can actually paint under uniform scale).
 */
internal fun homeLaunchCardActionRowFloorWidth(hasExtraAction: Boolean): Dp {
    val nominal = homeLaunchCardActionNominalButtonWidths(hasExtraAction)
    val gaps = HomeLaunchCardActionGap * (nominal.size - 1)
    val nominalButtonsTotal = nominal.fold(0.dp) { acc, w -> acc + w }
    val smallest = nominal.minOrNull() ?: return gaps
    if (smallest <= 0.dp) return gaps
    val minScale = (MimirSpacing.minTarget.value / smallest.value).coerceAtMost(1f)
    return nominalButtonsTotal * minScale + gaps
}

/**
 * **Corrected (launch card "resize, don't stack" ticket, policy 2026-08-21).** The prior sizing rule on
 * this function (the fixed-content-width assumption) assumed the
 * row could only ever be its own nominal 368/512dp content sum or `contentWidth`, whichever is narrower --
 * true only because nothing before this ticket ever asked the row to shrink. Now it does, floored at
 * every button clearing `MimirSpacing.minTarget` ([homeLaunchCardActionRowFloorWidth]).
 *
 * **The M-lane arm is gone (no surface reserves for the orb at all).** This
 * function once took an M-lane inset and, when it was positive, solved a centred-row budget
 * `contentWidth - 2*inset` floored at [homeLaunchCardActionRowFloorWidth]. Every production caller passed
 * zero, so that arm never executed on any device; what remains is exactly the branch that always ran.
 */
internal fun homeLaunchCardActionRowWidth(
    totalWidth: Dp,
    layout: HomeLaunchCardLayout,
    hasExtraAction: Boolean = false,
): Dp {
    val contentWidth = (totalWidth - layout.outerHorizontal * 2).coerceAtLeast(0.dp)
    return minOf(contentWidth, homeLaunchCardActionNominalRowWidth(hasExtraAction))
}

/**
 * The two-row shape below `400.dp` of content width (`544.dp` with an extra action).
 *
 * **These two literals are the pre-existing, unreserved law and are bit-identical to what shipped.**
 * `400.dp`'s own rationale ("preserve complete labels... below 400dp of usable footer width") is a
 * TEXT-fit claim, and no text-measurement harness exists in this tree, so it is carried rather than
 * re-derived. A second arm once keyed on a positive M-lane inset and stacked only when a floored single
 * row could not clear the reserved M lane; that reservation is retired product-wide (policy
 * 2026-08-21: no surface reserves for the orb at all) and every production caller passed zero, so the arm
 * never executed on any device.
 */
internal fun homeLaunchCardActionsStacked(
    totalWidth: Dp,
    layout: HomeLaunchCardLayout,
    hasExtraAction: Boolean = false,
): Boolean {
    val contentWidth = (totalWidth - layout.outerHorizontal * 2)
    return if (!hasExtraAction) contentWidth < 400.dp else contentWidth < 544.dp
}

internal data class HomeLaunchCardArtSize(val width: Dp, val height: Dp)

private const val HomeLaunchCardArtTargetAreaDp2 = 66_000f

/** Equal-area platform art, uniformly reduced only when its aspect reaches a configured lane bound. */
internal fun homeLaunchCardArtSize(
    platformId: String,
    maxWidth: Dp,
    maxHeight: Dp,
): HomeLaunchCardArtSize =
    homeLaunchCardArtSizeForAspect(platformCoverAspect(platformId), maxWidth, maxHeight)

/**
 * [homeLaunchCardArtSize]'s body, with the aspect supplied rather than looked up from the platform.
 *
 * Extracted (2026-08-22, cartridge-art support) because the launch card now paints TWO cover kinds whose
 * intrinsic proportions differ: the platform's box cover ([platformCoverAspect]) and the provider's
 * cartridge/disc/card render, which this module already treats as square
 * ([LibraryPhysicalMediaCoverAspect], `libraryListPreviewMedia`). The equal-area law itself is unchanged
 * and is deliberately NOT duplicated -- both kinds solve for the same
 * [HomeLaunchCardArtTargetAreaDp2], so a cartridge does not silently render at a different visual weight
 * than the cover it replaces.
 */
internal fun homeLaunchCardArtSizeForAspect(
    aspect: Float,
    maxWidth: Dp,
    maxHeight: Dp,
): HomeLaunchCardArtSize {
    val idealWidth = sqrt(HomeLaunchCardArtTargetAreaDp2 * aspect)
    val idealHeight = sqrt(HomeLaunchCardArtTargetAreaDp2 / aspect)
    val scale = minOf(1f, maxWidth.value / idealWidth, maxHeight.value / idealHeight)
    return HomeLaunchCardArtSize(
        width = (idealWidth * scale).dp,
        height = (idealHeight * scale).dp,
    )
}

/**
 * The size the art node ACTUALLY paints at, both skeletons, every cover kind -- the one equal-area law
 * ([homeLaunchCardArtSizeForAspect]) at the platform's cover aspect for an emulated platform, and at a square for
 * everything else (an Android game's or app's icon). Audit 2026-10-15, F7: the icon was the one art kind exempt
 * from the law, the bare `minOf(maxWidth, maxHeight)` square, so on the Thor it painted 288 dp (82,944 dp²) beside
 * a cartridge's 257 dp (66,000 dp²): a white disc a quarter larger than any cover on the same card.
 *
 * Extracted from [HomeLaunchCardFreeArt]'s own body (which now calls this) so that
 * [homeLaunchCardCoverBoxWidth] below -- and the parity harness that measures it -- read the SAME number
 * the art paints at, from the SAME function, instead of a second derivation that can drift. That drift is
 * this surface's own recurring failure mode: previous analysis found three separate instruments that measured a narrower quantity than the criterion they were read
 * as proving.
 *
 * [maxWidth]/[maxHeight] stay the layout's `coverColumnWidth`/`coverHeight` -- the two-ratio BOUND the art
 * solves inside. That bound is unchanged by the art-hugging box change below; what changed is that it is no longer
 * PAINTED as a box.
 *
 * [physicalMedia] selects WHICH aspect the emulated branch solves for: `false` is the platform's box cover,
 * `true` is the provider cartridge/disc/card render ([LibraryPhysicalMediaCoverAspect]). It is deliberately
 * NOT defaulted -- [homeLaunchCardCoverBoxWidth] and this function must be called with identical arguments
 * at every site or the box and the node inside it drift apart, which is the exact failure this file's own
 * KDoc records three times. A default would let one of the two pair-members be updated alone.
 */
internal fun homeLaunchCardRenderedArtSize(
    emulated: Boolean,
    platformId: String,
    maxWidth: Dp,
    maxHeight: Dp,
    physicalMedia: Boolean,
): HomeLaunchCardArtSize =
    homeLaunchCardArtSizeForAspect(
        aspect = when {
            !emulated -> HomeLaunchCardIconAspect
            physicalMedia -> LibraryPhysicalMediaCoverAspect
            else -> platformCoverAspect(platformId)
        },
        maxWidth = maxWidth,
        maxHeight = maxHeight,
    )

/** An Android game's or app's icon is a square, solved on the same equal-area law as the cartridge render (F7). */
private const val HomeLaunchCardIconAspect = 1f

/**
 * **The cover box hugs the art. This REVERSES a rule this tree deliberately held, and the reversal is the
 * point.**
 *
 * The previous law centred the art inside its box and aligned the box to the content end edge.
 * On the Odin, a 0.72-aspect cover sat in a 288 dp box with 36 dp slack on each side. The resulting
 * `coverBoxCentreXShare` divergence at 4:3 was known. The current law instead hugs the rendered art.
 *
 * **Why end-flush alignment could not deliver this, checked in source before writing.** The cover box
 * is already the END child of [HomeLaunchCardWide]'s `Row`, beside a `weight(1f)` text column that absorbs
 * every remaining dp -- so the box ALREADY lands flush at the content end edge, and an "end-flush" change
 * is a no-op. The remaining gap is the art node CENTRED inside a box wider than itself:
 *
 * | | content end E | cover box | rendered art (0.72) | slack each side | left margin | right margin |
 * |---|---|---|---|---|---|---|
 * | Odin 832.5x468.3 | 788.50 | 288.00 | 216.00 | **36.00** | 44.00 | **80.00** |
 * | Nova 640x480 | 606.17 | 221.41 | 217.99 | 1.71 | 33.83 | 35.53 |
 *
 * `[D]` from this file's own solvers; the Odin's art is HEIGHT-bound there, not area-cap-bound --
 * `min(1, 288/217.99, 300/302.77)` = 0.9909, so 216.0 x 300.0 = 64,800dp², 98.2% of
 * `HomeLaunchCardArtTargetAreaDp2`. The Nova sits exactly at the cap (scale 1). **Neither device is
 * width-bound at 0.72**, which is why the box could be wider than the art on both. With the box hugging,
 * the right margin collapses to `outerHorizontal` at every viewport and every aspect: Odin 80.00 -> 44.00,
 * symmetric with its own left margin; Nova 35.53 -> 33.83, so the Nova moves under 2dp and cannot regress.
 *
 * **Disclosed cost, not silently absorbed.** The freed width has exactly one place to go -- the `Row`'s
 * weighted text column, which grows by the FULL two-sided slack (Odin 420.50 -> 492.50dp at aspect 0.72).
 * So the text lane's width now varies with the PLATFORM's cover aspect: a width-bound cover leaves the box
 * at the full column and the lane where it was (`snes` 1.36 on the Odin -> art 288.0dp, box 288.0dp, lane
 * 420.50dp, right margin still 44.00 because the box still equals the art). A home shelf row is one
 * platform, so nothing reflows while a card is open; the lane changes width across rows, exactly as the art
 * already did. Second, smaller consequence: [platformCoverAspect] is a snapshot-state read
 * (`MeasuredCoverAspects`, `GamelistCarousel.kt`), so for an UNMAPPED platform the one-off "first measured
 * cover lands" reflow now moves the text column too, not just the art. Both are compositor-cheap
 * (measure/layout of two siblings, no new animation).
 *
 * **The alternative was considered and rejected.** Keeping the full-width box and
 * aligning the art `CenterEnd` inside it also collapses the right margin, moves no text, and is a smaller
 * diff -- but it RELOCATES the 72dp to the left of the art instead of removing it. The freed space must be available to the content, rather than moved to the other side of the same object.
 *
 * **The box therefore MOVES when the cover KIND changes (2026-08-22 cartridge-art behavior), and that is a
 * consequence of this law.** When cartridge art is enabled, cards opened from Home and Library
 * use that art. A cartridge/disc render is square
 * ([LibraryPhysicalMediaCoverAspect]) where a box cover is not, so the equal-area solver returns a
 * different width and the hugging box follows it. `[D]` from this file's own solvers at the two certified
 * viewports, reference platform `gamecube` (0.72):
 *
 * | | cover box (0.72) | cartridge box (1.00) | box delta | text lane delta |
 * |---|---|---|---|---|
 * | Odin 832.5x468.3 | 216.00 | 256.90 | **+40.90** | **-40.90** |
 * | Nova 640x480 | 217.99 | 221.41 | **+3.42** | **-3.42** |
 *
 * The Odin's cartridge is area-cap-bound (`min(1, 288/256.9, 300/256.9)` = 1, a full 66,000dp²); the Nova's
 * is WIDTH-bound by its own 221.41dp `coverColumnWidth`, so it lands at 221.41² = 49,022dp², 74% of the
 * target area. That shortfall is [LaunchCardCoverColumnWidthCapRatio] doing exactly what it was added to
 * do, not a second law competing with the first. Both the right margin and
 * `launchCardCoverArtMarginAsymmetryDp` stay at `outerHorizontal`/0.0 in BOTH branches, because the box is
 * still the art's own width -- the cartridge branch changes what that width IS, never the relationship. The
 * Odin's -40.90dp text lane is the one number here that wants an eye on hardware: it is well past
 * rounding noise and still needs hardware validation.
 */
internal fun homeLaunchCardCoverBoxWidth(
    emulated: Boolean,
    platformId: String,
    layout: HomeLaunchCardLayout,
    physicalMedia: Boolean,
): Dp = homeLaunchCardRenderedArtSize(
    emulated = emulated,
    platformId = platformId,
    maxWidth = layout.coverColumnWidth,
    maxHeight = layout.coverHeight,
    physicalMedia = physicalMedia,
).width


/** Render-only card data. App-owned stores and launch services never leak into the feature module. */
data class HomeLaunchCardUiState(
    val row: HomeShelfRow,
    val platformLabel: String,
    /** The current Library GameRow cover: the same source rendered by the gamelist. */
    val coverArtUri: String? = null,
    val statusLine: String? = null,
    val runtimeStatusLine: String? = null,
    val achievementProgress: String? = null,
    val emulatorLabel: String? = null,
    val lastPlayedLabel: String? = null,
    val screenScraperConnected: Boolean = false,
    /**
     * True only for a durably-saved, provider-verified user SteamGridDB key (mirrors
     * `SteamGridDbKeyStore.providerValidated` / `MimirSteamGridDbGate.isOpen`) — never the build-time
     * debug fallback `resolvedKey` may fall back to. Gates whether a keyless `pc_games` row's "no
     * description" copy may point at Enrich; see `homeShelfDescriptionText`. Defaults false (fail closed)
     * for any caller that has not wired the real signal.
     */
    val steamGridDbKeyPresent: Boolean = false,
    /**
     * The ALREADY-RESOLVED cartridge/disc/card render to paint INSTEAD of [coverArtUri], or null to paint
     * the cover.
     *
     * Every producer resolves it through the one law, [homeLaunchCardCartridgeArtUri] — including the Utility
     * on/off gate, so a null here always means "paint the cover", never "the setting is off but there is
     * art". The app layer owns both sources it chooses between (the explicit per-game selection and the
     * enrichment-downloaded provider render), exactly as it already owns `chosenHeroUri`; this module never
     * reaches into an app store to find out.
     */
    val cartridgeArtUri: String? = null,
    /**
     * Whether [cartridgeArtUri] is PHYSICAL media (a disc/cartridge render) rather than a cover.
     *
     * **This used to be inferred from the uri being non-null, and that inference died with C1.** The field
     * once held a disc or nothing; FL-2 made it hold the face the Shelf resolved, which is usually a box.
     * The kind now travels with the uri from `resolveWiredFace`, because the only consumer that cares is
     * geometry: physical media paints at [LibraryPhysicalMediaCoverAspect] (1f, square) and a cover paints
     * at its platform's box aspect. Getting this wrong squares off a tall box with nothing to warn on.
     *
     * Defaults false: a caller that has not been taught about the kind gets cover geometry, which is what
     * every non-cartridge surface rendered before the flag existed.
     */
    val cartridgeArtIsPhysical: Boolean = false,
    val openWithLoading: Boolean = false,
    val openWithError: String? = null,
    val extraFacts: List<Pair<String, String>> = emptyList(),
) {
    val emulated: Boolean get() = row.itemId is HomeShelfItemId.Emulated

    /**
     * The single derivation of "this card is painting a cartridge, not a cover", read by BOTH the cover
     * Box's width and the art node's own size. They must agree — see [homeLaunchCardCoverBoxWidth]'s KDoc
     * for why this file refuses to let those two computations take separate inputs.
     *
     * Emulated only: an Android game/app has no provider physical media, and its branch in
     * [homeLaunchCardRenderedArtSize] is the icon's square rather than a platform's cover aspect, so a non-null
     * value there could only ever be a caller error rendering as a silent geometry change.
     */
    val physicalMediaCover: Boolean
        get() = emulated && cartridgeArtIsPhysical && !cartridgeArtUri.isNullOrBlank()
    val primaryActionLabel: String
        get() = when (val id = row.itemId) {
            is HomeShelfItemId.Emulated, is HomeShelfItemId.AndroidGame -> homeLaunchCardPlayLabel(id)
            is HomeShelfItemId.AndroidApp -> MimirCopy.Open
        }

    val openWithActionLabel: String
        get() = if (openWithLoading) "Loading…" else MimirCopy.OpenWith
}

/** Provider/platform identity is dynamic copy: preserve authored casing and use the identifier role. */
internal fun HomeLaunchCardUiState.platformEyebrowLabel(): String = platformLabel

@Composable
fun HomeLaunchCard(
    state: HomeLaunchCardUiState,
    onPlay: () -> Unit,
    onAchievements: () -> Unit,
    onOpenWith: () -> Unit,
    onClose: () -> Unit,
    onEditTitle: () -> Unit = {},
    modifier: Modifier = Modifier,
    authority: FocusAuthority = LocalFocusAuthority.current,
    focusContract: MimirLaunchCardFocusContract = HomeLaunchCardFocusContract,
    /** A test seam: a host-supplied action in the extra slot. Production passes none, and the slot carries Restart (below). */
    extraAction: MimirLaunchCardExtraAction? = null,
    descriptionScrollOwnerToken: Long = 0L,
    descriptionScrollCommand: HomeLaunchDescriptionScrollCommand = HomeLaunchDescriptionScrollCommand(),
    onDescriptionScrollStateChanged: (HomeLaunchDescriptionScrollState) -> Unit = {},
) {
    PlayedDayClockEffect() // LAST PLAYED turns over at local midnight, as the Bottom's Activity page does
    // the card of the game that is parked behind Home carries Restart beside Resume, on BOTH doors (Home's and the Library's), under the contract's own
    // Restart key. Read here, in composition, so the card repaints when the lease comes or goes.
    val footerExtra = extraAction ?: homeLaunchCardRestartAction(
        state.row.itemId,
        authority,
        focusContract.playKey,
        focusContract.restartKey,
        onPlay,
    )
    // The State, never unwrapped here: what the card paints is two derived answers, so a focus emission that changes neither (the pad moving inside the
    // picker above the card, the first row of that picker being seated) re-runs nothing. [ringKey] is the key the buttons ring; [upperRingKey] is the part of
    // it the title block and the description can wear (their own two keys), so a button's ring moving does not re-run them (owperf, 2026-10-15).
    val focus = authority.state.collectAsState()
    val ringKey by remember(authority, focusContract, focus) {
        derivedStateOf(structuralEqualityPolicy()) { authority.litFocusIn(focusContract.scope, focus.value) }
    }
    val upperRingKey by remember(authority, focusContract, focus) {
        derivedStateOf(structuralEqualityPolicy()) {
            authority.litFocusIn(focusContract.scope, focus.value)
                ?.takeIf { it == focusContract.titleEditKey || it == focusContract.descriptionKey }
        }
    }
    val interaction = remember { MutableInteractionSource() }
    val launchCardPalette = LocalMimirPalette.current.liquidGlassContentPalette()
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(mimirGlassScrimColor())
            .clickable(interactionSource = interaction, indication = null, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        val viewport = rememberMeasuredViewport(
            maxWidth = maxWidth,
            maxHeight = maxHeight,
            inDomain = { widthDp, heightDp ->
                widthDp.isFinite() && heightDp.isFinite() && widthDp > 0f && heightDp > 0f
            },
            beforeFirstValid = MimirBeforeFirstValidViewport.RENDER_NOTHING,
        )
        if (!viewport.shouldRender) return@BoxWithConstraints

        val layout = homeLaunchCardLayout(viewport.width, viewport.height)
        val cardWidth = viewport.width
        // RC-3 Defect B : which render arm this
        // viewport gets. See [homeLaunchCardUsesWideSkeleton]'s own KDoc for the full derivation --
        // short version, only the STANDARD-postured certified rows that `layout.compact` still bands as
        // "compact" (Nova, RP5-class shelf, the 538dp companion) move to the Wide arm; everything else is
        // unchanged, including the Odin.
        val wideSkeleton = homeLaunchCardUsesWideSkeleton(
            cardWidth,
            viewport.height,
            layout,
            LocalMimirViewportProfile.current,
        )
        // The un-stacked row centres in the FULL content box. It once sat beside a reserved M lane that
        // the header+art `Box` (not the footer) was inset by; that reservation is retired product-wide
        // (no surface reserves for the orb at all), so there is no lane to be
        // adjusted against and no inset to thread into these two solvers.  stacking is the last resort, not the first response to a width deficit.
        val actionRowWidth =
            homeLaunchCardActionRowWidth(cardWidth, layout, footerExtra != null)
        val stackActions = homeLaunchCardActionsStacked(cardWidth, layout, footerExtra != null)
        // Pre-open virtual registration guarantees a fast second A reaches Play. Once measured, replace
        // those bounds with the exact rendered breakpoint geometry so the tiny two-row fallback also has
        // truthful Up/Down navigation. Virtual bounds remain the one FocusAuthority source of truth.
        SideEffect {
            if (!viewport.suppressDrawing) {
                registerHomeLaunchCardActions(
                    authority = authority,
                    emulated = state.emulated,
                    stacked = stackActions,
                    actionRowWidth = actionRowWidth.value,
                    hasExtraAction = footerExtra != null,
                    contract = focusContract,
                )
            }
        }
        CompositionLocalProvider(LocalMimirPalette provides launchCardPalette) {
            Column(
                modifier = Modifier
                    .mimirMeasuredViewport(viewport)
                    .fillMaxSize()
                    .testTag(HomeLaunchCardMeasuredViewportTestTag)
                    // RC-3 Defect C fault #2: NO extra end padding here any more -- the M-anchor
                    // reservation used to land on this whole Column (footer included). It now lands only
                    // on the header+art Box below, so the footer's own `fillMaxWidth()` spans the TRUE
                    // content box and its action row centres in it without a phantom right-hand gap.
                    .padding(
                        start = layout.outerHorizontal,
                        top = layout.outerVertical,
                        end = layout.outerHorizontal,
                        bottom = layout.outerVertical,
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
                verticalArrangement = Arrangement.Top,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    if (wideSkeleton) {
                        HomeLaunchCardWide(
                            state = state,
                            layout = layout,
                            authority = authority,
                            focusedKey = upperRingKey,
                            focusContract = focusContract,
                            onEditTitle = onEditTitle,
                            onPlay = onPlay,
                            descriptionScrollOwnerToken = descriptionScrollOwnerToken,
                            descriptionScrollCommand = descriptionScrollCommand,
                            onDescriptionScrollStateChanged = onDescriptionScrollStateChanged,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        HomeLaunchCardCompact(
                            state = state,
                            layout = layout,
                            authority = authority,
                            focusedKey = upperRingKey,
                            focusContract = focusContract,
                            onEditTitle = onEditTitle,
                            onPlay = onPlay,
                            descriptionScrollOwnerToken = descriptionScrollOwnerToken,
                            descriptionScrollCommand = descriptionScrollCommand,
                            onDescriptionScrollStateChanged = onDescriptionScrollStateChanged,
                            compactUnifiedMode = homeLaunchCardIsMinimal(
                                viewportIsCompact = LocalMimirViewportProfile.current.isCompact,
                            ),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Spacer(Modifier.height(MimirSpacing.md))
                HomeLaunchCardFooter(
                    state = state,
                    authority = authority,
                    focusedKey = ringKey,
                    focusContract = focusContract,
                    actionRowWidth = actionRowWidth,
                    stackActions = stackActions,
                    extraAction = footerExtra,
                    // REVERTED (Regression 2, Nova capture, build `3add68a0`, 2026-08-21). The
                    // `wideSkeleton` gate above shipped un-measured -- outside any lane -- and put "B Back"
                    // 55x11dp under the M orb on the Nova (orb resting at x565-621/y395-445, hint at
                    // x560-620/y434-445, `[D]`). Back to `!layout.compact`, which is what rendered before
                    // that flip and does not collide.
                    //
                    // Not fixed by relocating the hint instead, for two reasons. First, the orb is a
                    // draggable WindowManager overlay Compose cannot see -- its own KDoc already says "x is
                    // coerced on user drag", never y, and THIS capture has it resting well below the top-right
                    // corner its default position suggests, so no static Compose alignment (CenterEnd of the
                    // footer box, or hugging the action row's own right edge) is provably collision-free by
                    // construction; it would only be "collision-free in this one capture." Second, any fix
                    // that repositions the hint ONLY on the Nova (leaving the Odin's own CenterEnd placement
                    // alone, since the Odin has 156.1dp of measured clearance and needs no change) forks the
                    // hint's own layout rule into two device-specific implementations -- the exact
                    // Wide-vs-Compact divergence Defect B just converged away from for the header+art row,
                    // reintroduced here for one Text node. Gating visibility by `layout.compact` keeps ONE
                    // rule, keeps the Odin pixel-identical, and removes the collision deterministically: the
                    // hint simply does not render on the compact-banded widths this same flag already used to
                    // gate before the un-measured flip.
                    //
                    // 1:1 loses on this one element, deliberately: showing "B Back" is Class 3 (a HINT, not a
                    // primary action -- the B/Cancel input keeps working with or without the label), so
                    // hiding it on compact widths is an accepted, narrower-scoped divergence, not a silent
                    // drop of an action.
                    showBackHint = !layout.compact,
                    onPlay = onPlay,
                    onAchievements = onAchievements,
                    onOpenWith = onOpenWith,
                )
            }
        }
    }
}

@Composable
private fun HomeLaunchCardWide(
    state: HomeLaunchCardUiState,
    layout: HomeLaunchCardLayout,
    authority: FocusAuthority,
    focusedKey: FocusKey?,
    focusContract: MimirLaunchCardFocusContract,
    onEditTitle: () -> Unit,
    onPlay: () -> Unit,
    descriptionScrollOwnerToken: Long,
    descriptionScrollCommand: HomeLaunchDescriptionScrollCommand,
    onDescriptionScrollStateChanged: (HomeLaunchDescriptionScrollState) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(layout.columnGap),
    ) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            HomeLaunchCardHeader(state, authority, focusedKey, focusContract, onEditTitle)
            // No scroll of its own: the column is the height the card leaves, the fact rows stand at their own height and About takes what is left (fillHeight), whole
            // lines of it, so the description scrolls inside its own viewport (its down arrow, UP from Play) instead of being cut by an outer clip nothing drove (sweep 10-13, Thor 1.3).
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = MimirSpacing.md),
                verticalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
            ) {
                HomeLaunchCardMetadata(
                    state = state,
                    descriptionMaxLines = layout.descriptionMaxLines,
                    authority = authority,
                    focusedKey = focusedKey,
                    focusContract = focusContract,
                    descriptionScrollOwnerToken = descriptionScrollOwnerToken,
                    descriptionScrollCommand = descriptionScrollCommand,
                    onDescriptionScrollStateChanged = onDescriptionScrollStateChanged,
                    fillHeight = true,
                )
            }
        }
        // S2 : this Box's WIDTH is the art's own rendered width, never
        // `layout.coverColumnWidth` -- see [homeLaunchCardCoverBoxWidth]'s KDoc for the full derivation,
        // the law it overturns and the disclosed cost. `fillMaxHeight()` + `contentAlignment = Center`
        // are UNTOUCHED and load-bearing: they are what vertically centres the art in the body band
        // (the vertical layout equation, confirmed on the Odin
        // at predicted top 56.2dp vs measured 58.5). Horizontal centring inside this Box is now a no-op
        // by construction -- the box IS the art's width -- and is kept only so the box has one alignment,
        // not two.
        //
        // The art node still solves against the two-ratio BOUND, not against this width: same function,
        // same inputs as the line above, so the box and the node it holds cannot drift apart.
        Box(
            modifier = Modifier
                .width(
                    homeLaunchCardCoverBoxWidth(
                        emulated = state.emulated,
                        platformId = state.row.supportingLabel.orEmpty(),
                        layout = layout,
                        physicalMedia = state.physicalMediaCover,
                    ),
                )
                .fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            HomeLaunchCardFreeArt(
                state = state,
                maxWidth = layout.coverColumnWidth,
                maxHeight = layout.coverHeight,
                onTapPlay = {
                    activateHomeLaunchCardAction(authority, focusContract.playKey, onPlay)
                },
            )
        }
    }
}

/**
 * Whether the launch card is the MINIMAL card: header, the art as the hero, and the buttons -- no metadata column,
 * no description, no achievement progress.
 *
 * The minimal card was already built for the single-screen compact browser (since deleted, Q10) and unreachable since that browser lost
 * its caller. It applies to every compact screen, Home and
 * Library alike. Versions stay in the chooser Play opens, so the card needs no version row.
 *
 * Only the RENDER follows the viewport. On a compact card the description is never composed, so its focus key is never
 * registered and a press aimed at it finds nothing to move to.
 */
internal fun homeLaunchCardIsMinimal(viewportIsCompact: Boolean): Boolean = viewportIsCompact

@Composable
private fun HomeLaunchCardCompact(
    state: HomeLaunchCardUiState,
    layout: HomeLaunchCardLayout,
    authority: FocusAuthority,
    focusedKey: FocusKey?,
    focusContract: MimirLaunchCardFocusContract,
    onEditTitle: () -> Unit,
    onPlay: () -> Unit,
    descriptionScrollOwnerToken: Long,
    descriptionScrollCommand: HomeLaunchDescriptionScrollCommand,
    onDescriptionScrollStateChanged: (HomeLaunchDescriptionScrollState) -> Unit,
    compactUnifiedMode: Boolean,
    modifier: Modifier = Modifier,
) {
    if (compactUnifiedMode) {
        BoxWithConstraints(modifier.fillMaxSize()) {
            val artworkMaxWidth = maxWidth * 0.72f
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
            ) {
                HomeLaunchCardHeader(
                    state,
                    authority,
                    focusedKey,
                    focusContract,
                    onEditTitle,
                    compactUnifiedMode = true,
                )
                BoxWithConstraints(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    HomeLaunchCardFreeArt(
                        state = state,
                        maxWidth = artworkMaxWidth,
                        maxHeight = maxHeight,
                        onTapPlay = {
                            activateHomeLaunchCardAction(authority, focusContract.playKey, onPlay)
                        },
                        bookEyebrow = false,
                    )
                }
            }
        }
        return
    }
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(layout.columnGap)) {
            // Defect fix (device capture, Nova, 640x480dp): HomeLaunchCardHeader emits three top-to-bottom
            // siblings (platform eyebrow, title row, status-badge row) that rely on Column-style flow --
            // each row's own `padding(top = MimirSpacing.sm)` only creates separation from the PREVIOUS
            // sibling if the parent stacks children vertically. A Box does not: it places every direct
            // child at the same TopStart origin (offset only by that child's own padding), so the title
            // row and the badge row -- both carrying the identical `top = MimirSpacing.sm` -- landed at
            // the exact same y and painted on top of each other (the READY pill over "Resident Evil").
            // HomeLaunchCardWide already gets this right with a Column (see below); Compact must match.
            Column(Modifier.weight(1f)) {
                HomeLaunchCardHeader(state, authority, focusedKey, focusContract, onEditTitle)
            }
            HomeLaunchCardFreeArt(
                state = state,
                maxWidth = layout.coverColumnWidth,
                maxHeight = layout.coverHeight,
                onTapPlay = {
                    activateHomeLaunchCardAction(authority, focusContract.playKey, onPlay)
                },
                bookEyebrow = false,
            )
        }
        HomeLaunchCardMetadata(
            state = state,
            descriptionMaxLines = layout.descriptionMaxLines,
            authority = authority,
            focusedKey = focusedKey,
            focusContract = focusContract,
            descriptionScrollOwnerToken = descriptionScrollOwnerToken,
            descriptionScrollCommand = descriptionScrollCommand,
            onDescriptionScrollStateChanged = onDescriptionScrollStateChanged,
        )
    }
}

/**
 * The same platform-aspect box art the gamelist uses, without a holding frame or backing card.
 *
 * **T-1: this is the launch affordance for touch.**  [onTapPlay] performs the SAME
 * action Play performs, through the same [activateHomeLaunchCardAction] path — so the cover art is Play's
 * large, obvious touch target, not a second, differently-behaving way to start a game.
 *
 * **Declared asymmetry (§4 PER-ELEMENT ACTION PARITY).** The cover art has no [mimirFocusable] and no
 * focus key: the controller does not stop on it, and does not need to — its action *set* is a subset of
 * Play's, which the controller reaches directly. Nothing is reachable by touch here that a controller
 * cannot do. The asymmetry is in the grammar (an extra touch target for one shared action), not in the
 * action set, which is exactly the freedom the model permits. It is declared here rather than discovered.
 */
/**
 * WHICH image the launch card paints. Extracted from `HomeLaunchCardFreeArt` (C1, 2026-08-25) so it can be
 * pinned, and corrected in the move.
 *
 * It used to read `cartridgeArtUri?.takeIf { physicalMediaCover }` -- harmless while the uri and the kind
 * meant the same thing, and wrong the moment they stopped. With a real kind, that `takeIf` DROPS a
 * cover-kind face and reverts it to the provider box: a hand-picked cover silently overruled, which is
 * exactly what C4 forbids ("a pick replaces the state's media regardless of the switch").
 *
 * **The face wins kind-agnostically. The kind drives geometry only** -- see [HomeLaunchCardUiState.physicalMediaCover].
 *
 * Blank is absent at every tier: a URI column cleared rather than nulled must not out-rank real art.
 */
internal fun homeLaunchCardArtRef(state: HomeLaunchCardUiState): String? = if (state.emulated) {
    state.cartridgeArtUri?.takeIf(String::isNotBlank) ?: state.coverArtUri
} else {
    state.row.tileIconUri ?: state.row.artworkUri
}

@Composable
private fun HomeLaunchCardFreeArt(
    state: HomeLaunchCardUiState,
    maxWidth: Dp,
    maxHeight: Dp,
    onTapPlay: () -> Unit,
    // Whether the fallback book prints the platform over its rule. The compact card's header already prints it just above the book, so the book there leaves it out and gives the
    // title the room (sweep 10-13: at 320x300 "Xenogears" had no room for its two lines under a label the card had just printed). The wide card keeps it.
    bookEyebrow: Boolean = true,
) {
    // Keyed on `Unit`, target read through `rememberUpdatedState` — see [HomeLaunchActionButton] for
    // the restart contract and for the measured scope of this choice.
    val currentTapPlay by rememberUpdatedState(onTapPlay)
    // with the Utility cartridge control ON, the launch card opens on the
    // cartridge art. `state.cartridgeArtUri` is already resolved and already gated by that setting
    // (see [homeLaunchCardCartridgeArtUri]), so a non-null value here is a decision, not a candidate.
    // `physicalMediaCover` -- not a second `!= null` test -- is what both this node and the Wide
    // skeleton's Box read, so the art and the box that hugs it cannot disagree about which kind it is.
    val artRef = homeLaunchCardArtRef(state)
    // One solver, shared with [homeLaunchCardCoverBoxWidth] -- the Wide skeleton's cover Box takes its
    // width from the same call with the same inputs, so the box can never be wider than what paints in it
    // (S2, policy 2026-08-21; see that function's KDoc). The branch itself is unchanged, only moved.
    val artSize = homeLaunchCardRenderedArtSize(
        emulated = state.emulated,
        platformId = state.row.supportingLabel.orEmpty(),
        maxWidth = maxWidth,
        maxHeight = maxHeight,
        physicalMedia = state.physicalMediaCover,
    )
    Box(
        modifier = Modifier
            .size(width = artSize.width, height = artSize.height)
            .clip(RoundedCornerShape(MimirBoxArtCorner))
            .testTag(HomeLaunchCardCoverArtTestTag)
            // Pointer gesture, never `clickable` (see [activateHomeLaunchCardAction]). `onTap` only:
            // an `onDoubleTap` registration would defer this tap by the platform double-tap timeout,
            // which is the opposite of immediate tap feedback.
            .pointerInput(Unit) { detectTapGestures(onTap = { currentTapPlay() }) }
            // A DECISION, not a parity restore — this node is new, so nothing was merged here before.
            // Merging pulls the art's own `contentDescription` (the game title, set by `MimirBoxArt`)
            // up onto the node that owns the action, so a screen reader announces one thing —
            // "<title>", actionable as Play — instead of a nameless action beside a nameless image.
            .semantics(mergeDescendants = true) {
                onClick(label = state.primaryActionLabel) {
                    currentTapPlay()
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // F7 resolution: an Android icon is the package's own launcher icon, drawn at the px the card shows it at (the shelves' 192 px file is stretched about three times here). Art the player chose is not
        // that file and paints as before; while the icon renders (a frame or two, off the main thread) the art is held, and a package that cannot be rendered keeps the cached file.
        val launcherPackage = state.row.itemId.androidPackageName?.value?.takeIf { isNativeLauncherIconRef(artRef, it) }
        val launcherIcon = launcherPackage?.let { rememberLauncherIconAtSize(it, with(LocalDensity.current) { artSize.width.roundToPx() }) }
        if (artRef != null) {
            when (launcherIcon) {
                is HomeLaunchCardLauncherIcon.Ready -> HomeLaunchCardLauncherIconImage(launcherIcon, state.row.title)
                HomeLaunchCardLauncherIcon.Loading -> Unit
                else -> MimirBoxArt(
                    model = artRef,
                    title = state.row.title,
                    focused = false,
                    contentScale = ContentScale.Fit,
                )
            }
        } else {
            MediaArtBookCover(
                title = state.row.title,
                platformIdValue = state.row.supportingLabel,
                platformLabel = state.platformLabel.takeIf { bookEyebrow },
                fitTitle = true,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun HomeLaunchCardFooter(
    state: HomeLaunchCardUiState,
    authority: FocusAuthority,
    focusedKey: FocusKey?,
    focusContract: MimirLaunchCardFocusContract,
    actionRowWidth: Dp,
    stackActions: Boolean,
    extraAction: MimirLaunchCardExtraAction?,
    showBackHint: Boolean,
    onPlay: () -> Unit,
    onAchievements: () -> Unit,
    onOpenWith: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            if (!state.emulated) {
                val labelStyle = rememberHomeLaunchRowLabelStyle(
                    listOfNotNull(state.primaryActionLabel to 96.dp, extraAction?.let { it.label to 136.dp }),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(HomeLaunchCardActionGap)) {
                    HomeLaunchActionButton(
                        state.primaryActionLabel,
                        focusContract.playKey,
                        focusedKey == focusContract.playKey,
                        scope = focusContract.scope,
                        primary = true,
                        authority = authority,
                        onActivate = onPlay,
                        labelStyle = labelStyle,
                        modifier = Modifier.width(96.dp),
                    )
                    extraAction?.let { action ->
                        HomeLaunchActionButton(
                            action.label,
                            action.key,
                            focusedKey == action.key,
                            scope = focusContract.scope,
                            enabled = action.enabled,
                            authority = authority,
                            onActivate = action.onActivate,
                            labelStyle = labelStyle,
                            modifier = Modifier.width(136.dp),
                        )
                    }
                }
            } else if (stackActions) {
                // One size for the whole footer: each stacked row is solved at its own widths, and the smaller of the two is worn by every button.
                val topRowWidth = minOf(
                    actionRowWidth,
                    HomeLaunchActionPlayWidth + HomeLaunchCardActionGap +
                        if (extraAction == null) HomeLaunchActionOpenWithWidth else HomeLaunchActionExtraWidth,
                )
                val topRowStyle = rememberHomeLaunchRowLabelStyle(
                    listOf(
                        state.primaryActionLabel to HomeLaunchActionPlayWidth,
                        if (extraAction == null) {
                            state.openWithActionLabel to HomeLaunchActionOpenWithWidth
                        } else {
                            extraAction.label to HomeLaunchActionExtraWidth
                        },
                    ),
                    rowWidth = topRowWidth,
                )
                val bottomRowStyle = rememberHomeLaunchRowLabelStyle(
                    listOfNotNull(
                        extraAction?.let { state.openWithActionLabel to HomeLaunchActionOpenWithWidth },
                        "Achievements" to HomeLaunchActionAchievementsWidth,
                    ),
                )
                val labelStyle = if (bottomRowStyle.fontSize.value < topRowStyle.fontSize.value) bottomRowStyle else topRowStyle
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(
                        modifier = Modifier.width(topRowWidth),
                        horizontalArrangement = Arrangement.spacedBy(HomeLaunchCardActionGap),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        HomeLaunchActionButton(
                            state.primaryActionLabel,
                            focusContract.playKey,
                            focusedKey == focusContract.playKey,
                            scope = focusContract.scope,
                            primary = true,
                            authority = authority,
                            onActivate = onPlay,
                            labelStyle = labelStyle,
                            modifier = Modifier.width(96.dp),
                        )
                        if (extraAction == null) {
                            HomeLaunchActionButton(
                                state.openWithActionLabel,
                                focusContract.openWithKey,
                                focusedKey == focusContract.openWithKey,
                                scope = focusContract.scope,
                                authority = authority,
                                onActivate = onOpenWith,
                                labelStyle = labelStyle,
                                modifier = Modifier.width(120.dp),
                            )
                        } else {
                            HomeLaunchActionButton(
                                extraAction.label,
                                extraAction.key,
                                focusedKey == extraAction.key,
                                scope = focusContract.scope,
                                enabled = extraAction.enabled,
                                authority = authority,
                                onActivate = extraAction.onActivate,
                                labelStyle = labelStyle,
                                modifier = Modifier.width(136.dp),
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.padding(top = HomeLaunchCardActionGap),
                        horizontalArrangement = Arrangement.spacedBy(HomeLaunchCardActionGap),
                    ) {
                        if (extraAction != null) {
                            HomeLaunchActionButton(
                                state.openWithActionLabel,
                                focusContract.openWithKey,
                                focusedKey == focusContract.openWithKey,
                                scope = focusContract.scope,
                                authority = authority,
                                onActivate = onOpenWith,
                                labelStyle = labelStyle,
                                modifier = Modifier.width(120.dp),
                            )
                        }
                        HomeLaunchActionButton(
                            "Achievements",
                            focusContract.achievementsKey,
                            focusedKey == focusContract.achievementsKey,
                            scope = focusContract.scope,
                            authority = authority,
                            onActivate = onAchievements,
                            labelStyle = labelStyle,
                            modifier = Modifier.width(136.dp),
                        )
                    }
                }
            } else {
                // un-stacked buttons paint at
                // homeLaunchCardActionButtonWidths(actionRowWidth, ...) -- the SAME solver
                // homeLaunchCardActionBounds reads for focus -- instead of fixed 96/120/136dp widths, so a
                // shrunk row (see homeLaunchCardActionRowWidth's own KDoc) never asks a fixed-width child to
                // overflow its Row. At the nominal (unshrunk) width this list IS 96/120/136dp -- bit-exact,
                // nothing paints differently on any viewport that already fit.
                val buttonWidths = homeLaunchCardActionButtonWidths(actionRowWidth, extraAction != null)
                // One size for the whole row : the smallest any of its labels needs, solved at the widths the buttons really paint.
                val labelStyle = rememberHomeLaunchRowLabelStyle(
                    listOfNotNull(
                        state.primaryActionLabel to buttonWidths[0],
                        extraAction?.let { it.label to buttonWidths[1] },
                        state.openWithActionLabel to if (extraAction == null) buttonWidths[1] else buttonWidths[2],
                        "Achievements" to buttonWidths.last(),
                    ),
                    rowWidth = actionRowWidth,
                )
                Row(
                    modifier = Modifier.width(actionRowWidth),
                    horizontalArrangement = Arrangement.spacedBy(HomeLaunchCardActionGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HomeLaunchActionButton(
                        state.primaryActionLabel,
                        focusContract.playKey,
                        focusedKey == focusContract.playKey,
                        scope = focusContract.scope,
                        primary = true,
                        authority = authority,
                        onActivate = onPlay,
                        labelStyle = labelStyle,
                        modifier = Modifier.width(buttonWidths[0]),
                    )
                    extraAction?.let { action ->
                        HomeLaunchActionButton(
                            action.label,
                            action.key,
                            focusedKey == action.key,
                            scope = focusContract.scope,
                            enabled = action.enabled,
                            authority = authority,
                            onActivate = action.onActivate,
                            labelStyle = labelStyle,
                            modifier = Modifier.width(buttonWidths[1]),
                        )
                    }
                    HomeLaunchActionButton(
                        state.openWithActionLabel,
                        focusContract.openWithKey,
                        focusedKey == focusContract.openWithKey,
                        scope = focusContract.scope,
                        authority = authority,
                        onActivate = onOpenWith,
                        labelStyle = labelStyle,
                        modifier = Modifier.width(if (extraAction == null) buttonWidths[1] else buttonWidths[2]),
                    )
                    HomeLaunchActionButton(
                        "Achievements",
                        focusContract.achievementsKey,
                        focusedKey == focusContract.achievementsKey,
                        scope = focusContract.scope,
                        authority = authority,
                        onActivate = onAchievements,
                        labelStyle = labelStyle,
                        modifier = Modifier.width(buttonWidths.last()),
                    )
                }
            }
            if (showBackHint) {
                MimirControlHintText(
                    stringResource(R.string.library_home_launch_back_hint),
                    style = MimirTypeScale.ControlHint,
                    color = LocalMimirPalette.current.textMuted,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
        state.openWithError?.let { message ->
            Text(
                message,
                style = MimirTypeScale.Support,
                color = LocalMimirPalette.current.danger,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = MimirSpacing.xs),
            )
        }
    }
}

@Composable
private fun HomeLaunchCardHeader(
    state: HomeLaunchCardUiState,
    authority: FocusAuthority,
    focusedKey: FocusKey?,
    focusContract: MimirLaunchCardFocusContract,
    onEditTitle: () -> Unit,
    compactUnifiedMode: Boolean = false,
) {
    val palette = LocalMimirPalette.current
    Text(
        state.platformEyebrowLabel(),
        style = MimirTypeScale.MicroStatus,
        color = palette.accent,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    // The launch-card game title was reduced30% from26sp. 26 * 0.70 = 18.2, and Heading is 18 — so unlike the earlier type changes this one
    // lands on the cap rather than between steps, and needs no bespoke size. lineHeight follows the same
    // ratio (29 * 18/26 = 20) so a two-line title keeps its leading rather than gaining slack.
    //  The count is an
    // etched chip BESIDE the name (after the Rename glyph); a name too long to leave it room wraps the chip onto the line below, and the name is never
    // narrowed for it. The chip's slot is composed (invisibly) for every emulated game on the full card, so its arrival moves nothing; the minimal card
    //  drops it with the other specifics.
    val renameFocused = focusedKey == focusContract.titleEditKey
    val showChipSlot = state.emulated && !compactUnifiedMode
    HomeLaunchCardTitleLine(
        modifier = Modifier.padding(top = MimirSpacing.sm),
        title = {
            Text(
                state.row.title,
                style = MimirTypeScale.Title,
                fontSize = MimirTypeStep.Heading,
                lineHeight = HomeLaunchCardTitleLineHeight,
                fontWeight = FontWeight.Bold,
                color = palette.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag(HomeLaunchCardTitleTestTag),
            )
        },
        edit = if (state.emulated) {
            @Composable {
                //  — reuses this codebase's own rune vocabulary (MimirNorseBook.kt's runeInk /
                // palette.runeMark, the same ink the Tome wordmark already paints its runes with), never a new
                // icon asset or a generic pencil glyph. ᛒ (Berkano) is the chosen rename mark and is unused
                // anywhere else in the app — deliberately NOT ᛏ, which already means Quick Launch on the nav ring
                // (MimirRunicQuickRail), Shift on MimirKeyboard and performance in the overlay; on the Home hero
                // card ᛏ would have rendered directly above the Quick Launch rune meaning something else. Gated to emulated games only:
                // Android games/apps carry no GameId a rename can target. The title line measures this glyph first and gives the
                // title whatever is left, so the glyph is never pushed off narrow cards.
                // Centred (observed with Tetris): the RENAME caption stood at the Row's top, above the name's centre line; it now centres on the glyph, and the
                // title line centres this whole Row on the name's first line, the way it already centres the count chip.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MimirSpacing.xs)) {
                    HomeLaunchCardTitleEditGlyph(
                        authority = authority,
                        key = focusContract.titleEditKey,
                        scope = focusContract.scope,
                        focused = renameFocused,
                        onActivate = onEditTitle,
                    )
                    HomeLaunchCardTitleEditHint(focused = focusedKey == focusContract.titleEditKey)
                }
            }
        } else {
            null
        },
        chip = if (showChipSlot) {
            @Composable { HomeLaunchCardAchievementChip(label = state.achievementProgress, hiddenByRenameHint = renameFocused) }
        } else {
            null
        },
    )
    // F7 (product review 2026-09-28; nova-9, rg-rotate-9, thor-4): a fixed two-line band. This line used to
    // mount only when there was launch feedback, so PLAY on a game with no emulator pushed the details block
    // down 31 dp. It is now always measured at two lines and painted only when there is something to say, so
    // feedback appearing or clearing moves nothing below it.
    // the minimal (compact) card is narrow enough, at a large font, that a sentence like the RetroArch core check's takes a
    // third line. The band still starts as two lines (so a sentence that fits two moves nothing) but there it may grow to
    // four, which the art beneath it gives up, instead of cutting the half that tells the player what to do.
    val runtimeStatusLine = state.runtimeStatusLine
    Text(
        runtimeStatusLine.orEmpty(),
        style = MimirTypeScale.MicroStatus,
        color = palette.textSecondary,
        minLines = 2,
        maxLines = if (compactUnifiedMode) HomeLaunchCardMinimalStatusMaxLines else 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = MimirSpacing.sm)
            .then(if (runtimeStatusLine == null) Modifier.clearAndSetSemantics { } else Modifier),
    )
}

/** The most lines the minimal card's status band may take (it is two on every other card). */
internal const val HomeLaunchCardMinimalStatusMaxLines = 4

const val HomeLaunchCardTitleTestTag = "home-launch-card/title"
const val HomeLaunchCardRenameTestTag = "home-launch-card/rename"
const val HomeLaunchCardAchievementChipTestTag = "home-launch-card/achievement-chip"

/** The title's line height ; a title taller than one and a half of these has wrapped, and the chip goes below it. */
internal val HomeLaunchCardTitleLineHeight = 20.sp

/**
 * The room the Rename glyph's focus ring is given from the name: the ring's full outer reach ([MimirFocusTileRingReach]), so the ring never touches the last letter of the name
 * (it touched the "s" of "Tetris" at the 4 dp this was). The title line reserves it from the name's width, and the chip beside the name follows the glyph.
 */
internal val HomeLaunchCardRenameRingRoom: Dp = MimirFocusTileRingReach

private enum class HomeLaunchTitleSlot { Title, Edit, Chip }

/**
 * Whether the count chip sits beside the name (after the Rename glyph) or wraps onto the line below it. Beside only when the name is a single line AND the whole
 * first line plus the chip fits [maxWidth]; otherwise below, so the name is never narrowed, ellipsized or pushed for the chip, and the chip is never cut.
 */
internal fun homeLaunchTitleChipBeside(
    titleHeightPx: Int,
    lineHeightPx: Int,
    firstLineWidthPx: Int,
    gapPx: Int,
    chipWidthPx: Int,
    maxWidthPx: Int,
): Boolean = chipWidthPx > 0 && titleHeightPx <= lineHeightPx * 3 / 2 && firstLineWidthPx + gapPx + chipWidthPx <= maxWidthPx

/**
 * The launch card's title line: the name, the Rename glyph right after it, and the achievement chip. The glyph is measured first and the name gets what
 * remains (so a long name still shows its two lines and the glyph is never pushed off); the chip never takes width from either. When the chip does not fit
 * beside a one-line name it sits on its own line under the name, from the left edge.
 */
@Composable
private fun HomeLaunchCardTitleLine(
    title: @Composable () -> Unit,
    edit: (@Composable () -> Unit)?,
    chip: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Layout(
        content = {
            Box(Modifier.layoutId(HomeLaunchTitleSlot.Title)) { title() }
            Box(Modifier.layoutId(HomeLaunchTitleSlot.Edit)) { edit?.invoke() }
            Box(Modifier.layoutId(HomeLaunchTitleSlot.Chip)) { chip?.invoke() }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val gap = MimirSpacing.xs.roundToPx()
        val ringRoom = HomeLaunchCardRenameRingRoom.roundToPx()
        val loose = Constraints(maxWidth = constraints.maxWidth)
        val edit = measurables.first { it.layoutId == HomeLaunchTitleSlot.Edit }.measure(loose)
        val reserved = if (edit.width > 0) edit.width + ringRoom else 0
        val title = measurables.first { it.layoutId == HomeLaunchTitleSlot.Title }
            .measure(Constraints(maxWidth = (constraints.maxWidth - reserved).coerceAtLeast(0)))
        val chip = measurables.first { it.layoutId == HomeLaunchTitleSlot.Chip }.measure(loose)
        val firstLine = title.width + reserved
        val beside = homeLaunchTitleChipBeside(
            titleHeightPx = title.height,
            lineHeightPx = HomeLaunchCardTitleLineHeight.roundToPx(),
            firstLineWidthPx = firstLine,
            gapPx = gap,
            chipWidthPx = chip.width,
            maxWidthPx = constraints.maxWidth,
        )
        val hasChip = chip.width > 0
        val width = when {
            beside -> firstLine + gap + chip.width
            hasChip -> maxOf(firstLine, chip.width)
            else -> firstLine
        }
        val height = when {
            beside -> maxOf(title.height, chip.height)
            hasChip -> title.height + gap + chip.height
            else -> title.height
        }
        layout(width, height) {
            title.placeRelative(0, 0)
            // The ring's whole reach from the name, and the Rename Row centred on the name's first line (a name of two lines is two equal lines).
            val firstLineHeight = if (title.height <= HomeLaunchCardTitleLineHeight.roundToPx() * 3 / 2) title.height else title.height / 2
            edit.placeRelative(title.width + ringRoom, (firstLineHeight - edit.height) / 2)
            if (beside) chip.placeRelative(firstLine + gap, ((title.height - chip.height) / 2).coerceAtLeast(0))
            else if (hasChip) chip.placeRelative(0, title.height + gap)
        }
    }
}

/**
 * The count chip's slot. With a [label] it is the etched chip, fading in; an outgoing count keeps its own text while it fades out. With none it is a [HomeLaunchCardChipSeat]:
 * the chip's room and nothing else, so the slot's size, and so every position on the card, is the same before the count arrives and after it, and a screen reader has no node
 * there at all (the invisible chip this replaces left an empty one). It also steps aside (alpha 0, same size) while the Rename hint is up, so the transient "Rename" never
 * paints over it.
 */
@Composable
private fun HomeLaunchCardAchievementChip(label: String?, hiddenByRenameHint: Boolean) {
    val shown = animateFloatAsState(
        targetValue = if (label == null || hiddenByRenameHint) 0f else 1f,
        animationSpec = tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing),
        label = "home-launch-achievement-chip",
    )
    // The count that was last on the chip, held across the fade-out that follows it.
    val lastLabel = remember { arrayOfNulls<String>(1) }
    if (label != null) lastLabel[0] = label
    val chipLabel = label ?: lastLabel[0]
    // The fade's ALPHA is read only in the layer lambda; composition sees the discrete "still visible" gate, which flips once.
    val stillFading by remember { derivedStateOf { shown.value > 0f } }
    if (chipLabel != null && (label != null || stillFading)) {
        MimirAchievementChip(
            label = chipLabel,
            modifier = Modifier
                .graphicsLayer { alpha = shown.value }
                .testTag(HomeLaunchCardAchievementChipTestTag),
        )
    } else {
        HomeLaunchCardChipSeat()
    }
}

/**
 * The room a count chip takes, with no chip in it and no semantics node: a layout that measures what [MimirAchievementChip] measures for a typical count (the medal, the gap,
 * the line of [HomeLaunchCardChipPlaceholder] in the chip's own type step, and the chip's padding), each rounded to pixels the way the chip's own row rounds it, so the slot is the
 * same size with the chip in it as without.
 */
@Composable
internal fun HomeLaunchCardChipSeat() {
    val measurer = rememberTextMeasurer()
    Layout(content = {}) { _, _ ->
        val line = measurer.measure(
            text = HomeLaunchCardChipPlaceholder,
            style = MimirTypeScale.MicroStatus,
            softWrap = false,
            maxLines = 1,
            density = this,
            layoutDirection = layoutDirection,
        )
        val medal = (MimirSpacing.md + MimirSpacing.xs / 2).roundToPx()
        val width = MimirSpacing.sm.roundToPx() * 2 + medal + MimirSpacing.xs.roundToPx() + line.size.width
        val height = MimirSpacing.xs.roundToPx() * 2 + maxOf(medal, line.size.height)
        layout(width, height) {}
    }
}

/** A typical count ("12/40" is the shape): the seat holds roughly its width, so a count that arrives changes the line little. */
internal const val HomeLaunchCardChipPlaceholder = "00/00"

/**
 * The manual-rename affordance: small and secondary by construction — [MimirTypeStep.Label] against the
 * title's [MimirTypeStep.Heading] (11sp vs 18sp), a single rune glyph with no background chrome at rest,
 * only a quiet highlight while focused. Click semantics mirror [HomeLaunchActionButton]: a focused tap
 * activates, an unfocused one only claims focus first — touch and D-pad share one path, never a second
 * gesture-only affordance. [contentDescription] is set explicitly on the edit affordance
 * so the spoken label never desyncs from the rune.
 */
@Composable
private fun HomeLaunchCardTitleEditGlyph(
    authority: FocusAuthority,
    key: FocusKey,
    scope: FocusScope,
    focused: Boolean,
    onActivate: () -> Unit,
) {
    val palette = LocalMimirPalette.current
    // Same restart contract as [HomeLaunchActionButton] (see its note for the measured scope): key
    // the gesture on `Unit`, read the target through `rememberUpdatedState`, so no recomposition can
    // dispose the detector mid-gesture. `ink` animates on focus, so this glyph recomposes repeatedly
    // while a finger is on it.
    val currentActivate by rememberUpdatedState(onActivate)
    val currentKey by rememberUpdatedState(key)
    val inkState = animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(MimirMotion.FocusMs, easing = MimirMotion.Easing),
        label = "home-launch-title-edit-glyph",
    )
    // The highlight behind the rune is drawn from the State (draw phase); only the rune's own colour below still reads it in
    // composition, because a `Text` colour takes no lambda: this glyph is one Box and one Text, so that scope is as small as it gets.
    val ink = inkState.value
    Box(
        modifier = Modifier
            // nudged UP so the mark reads optically centred against the
            // title rather than sitting low. The row is Alignment.Top (a two-line title must keep
            // top alignment), so centring is expressed as a small offset, not a changed alignment.
            .offset(y = (-2).dp)
            .mimirFocusable(authority, key, scope)
            .testTag(HomeLaunchCardRenameTestTag)
            // The one ring (ring migration, 2026-09-28): a circle ring around the rune, the icon rule -- its radius is
            // half the glyph box's shorter side, measured at draw. The quiet highlight under the rune stays as it was.
            .mimirSelectionRing(focused = focused, identity = key, shape = MimirRingShape.Circle)
            .clip(RoundedCornerShape(4.dp))
            .drawBehind { drawRect(palette.runeMark.copy(alpha = inkState.value * 0.16f)) }
            // T-1: one tap opens Rename. Pointer gesture, not `clickable` — see
            // [activateHomeLaunchCardAction] and the note on the action button for why a clickable
            // would plant a second Compose focus target the invariants gate cannot see.
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { activateHomeLaunchCardAction(authority, currentKey, currentActivate) },
                )
            }
            .padding(horizontal = MimirSpacing.xs, vertical = 2.dp)
            // mergeDescendants restores the node shape `clickable` gave this glyph (see the action
            // button's note): one accessibility node carrying both the name and the action.
            .semantics(mergeDescendants = true) {
                contentDescription = MimirCopy.RenameGame
                // The click ACTION `clickable` used to publish, without its focus node.
                onClick(label = MimirCopy.RenameGame) {
                    activateHomeLaunchCardAction(authority, key, onActivate)
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            // a runic EDIT, spelled the way this app already spells
            // ᛏᛟᛗᛖ (TOME) — ᛖᛞᛁᛏ in Elder Futhark — one step larger than the previous single mark.
            "ᛖᛞᛁᛏ",
            fontSize = MimirTypeStep.Body,
            color = lerp(palette.runeMark, palette.accent, ink),
            maxLines = 1,
        )
    }
}

/**
 * The "Rename" hint. Reuses [MimirControlHintText] verbatim — the SAME component the footer's own "B Back" hint
 * already renders with in this file — rather than a new hint surface or [MimirHintToastRail]'s heavier
 * always-`fillMaxWidth()` band, which does not fit this tight inline slot beside the glyph. Copy comes
 * from [MimirCopy.RenameGame] (already "Rename" with no ASCII apostrophe to fix), the exact constant the
 * glyph's own [contentDescription] already uses, so the spoken/hint/visible labels never drift apart.
 *
 * APPEARS the instant [HomeLaunchCardTitleEditGlyph] gains focus; HOLDS for exactly as long as focus stays
 * there (a "normal" contextual hint, not an independent timer that could fire and fade while the player is
 * still reading it); FADES the instant focus leaves, using [MimirMotion.OverlayMs] (180 ms) and
 * [MimirMotion.Easing] — the identical duration/curve pair every other overlay transition in this file and
 * `MimirAppHost` already animates with, never a bespoke one. Always composed (never conditionally added or
 * removed from the tree) so both directions of the fade can actually animate; `clearAndSetSemantics` keeps
 * it silent to accessibility services while invisible, mirroring the achievement-progress treatment a few
 * lines below in this same file (same always-present / alpha-driven shape, same reason).
 *
 * **Launch card alignment spec §5b/§9 row 3
 * : zero-width participation.** Always-composed
 * was correct for the animation and wrong for layout — `graphicsLayer { alpha = 0 }` hides PIXELS, never a
 * measured slot, and the title Row (`weight(1f, fill=false)`) measures its non-weighted siblings (the glyph,
 * then this hint) BEFORE handing the title whatever is left, so an invisible hint was reserving its own
 * rendered width from the title every frame — the largest single measured contributor to the title
 * truncation this spec found (§5b: ~54dp of the capture's 191.2dp lane, before the lane-starvation fix in
 * `ddff2bb8`). The `Modifier.layout` below is the fix: it still measures and PLACES this composable's real
 * content at its natural size (so the fade paints exactly where it always has — right after the glyph, at
 * the Row's own `xs` `spacedBy` gap — and focused-state painting is unaffected), but reports a ZERO-width
 * layout node upward, so the Row's arrangement math no longer counts it — the title's `weight(1f,
 * fill=false)` sibling receives that width back instead. Compositor-cheap focus holds: this is a static
 * zero-width report evaluated every measure pass, not a conditional composition, so nothing thrashes layout
 * on a focus change. When focused, the real content now paints slightly PAST the zero-width bound this
 * composable reports — over the column-gap dead zone between the text lane and the cover column — which the
 * spec names explicitly as acceptable: "transient, focus-held, and covers slack, not information." How that
 * actually READS on device — whether it visually collides with anything at the glyph's exact focused
 * position — is UNKNOWN, not device-verified this session; owed to a recapture, per this ticket's own "no
 * device work" instruction.
 */
@Composable
private fun HomeLaunchCardTitleEditHint(focused: Boolean) {
    val alpha by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing),
        label = "home-launch-title-edit-hint",
    )
    MimirControlHintText(
        MimirCopy.RenameGame,
        style = MimirTypeScale.ControlHint,
        color = LocalMimirPalette.current.textMuted,
        modifier = Modifier
            // Report zero width to the title Row's own measurement pass (see this function's KDoc) --
            // FIRST in the chain so everything below (padding/alpha/semantics) is measured and placed at
            // its real, natural size, only the size reported OUTWARD to the Row is zeroed.
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(0, placeable.height) {
                    placeable.placeRelative(0, 0)
                }
            }
            // The caption starts a ring's whole reach from the glyph: the glyph's ring is lit on exactly the frames this caption is up, and at the 8 dp it stood (the Row's xs
            // gap plus xs of padding) the halo covered the caption's first letters (sweep 10-09, 48b). The Row already puts [MimirSpacing.xs] between the glyph and this
            // zero-width slot, so the padding is the rest of [HomeLaunchCardRenameRingRoom].
            .padding(start = (HomeLaunchCardRenameRingRoom - MimirSpacing.xs).coerceAtLeast(0.dp))
            .graphicsLayer { this.alpha = alpha }
            .then(if (!focused) Modifier.clearAndSetSemantics { } else Modifier),
    )
}

@Composable
private fun HomeLaunchCardMetadata(
    state: HomeLaunchCardUiState,
    descriptionMaxLines: Int,
    authority: FocusAuthority,
    focusedKey: FocusKey?,
    focusContract: MimirLaunchCardFocusContract,
    descriptionScrollOwnerToken: Long,
    descriptionScrollCommand: HomeLaunchDescriptionScrollCommand,
    onDescriptionScrollStateChanged: (HomeLaunchDescriptionScrollState) -> Unit,
    // True in the wide card, whose metadata column is the height the card leaves it and does not scroll: About then takes the rest of that height in whole lines. False in the
    // compact card, whose own column scrolls and lets About run to its full line count.
    fillHeight: Boolean = false,
) {
    val typeLabel = when (state.row.itemId) {
        is HomeShelfItemId.Emulated -> null
        is HomeShelfItemId.AndroidGame -> "Android game"
        is HomeShelfItemId.AndroidApp -> "Android app"
    }
    if (state.emulated) {
        val identityFacts = buildList {
            add("EMULATOR" to (state.emulatorLabel ?: "Automatic"))
            state.row.genre?.takeIf(String::isNotBlank)?.let { add("GENRE" to it) }
        }
        HomeLaunchFactRow(*identityFacts.toTypedArray())
        HomeLaunchFactRow(
            HomeLaunchExternalSessionFactLabel to homeShelfExternalSessionElapsedLabel(
                state.row.externalSessionElapsedMillis, neverPlayed = state.lastPlayedLabel == null,
            ),
            "LAST PLAYED" to (state.lastPlayedLabel ?: "Never"),
        )
        state.extraFacts.chunked(2).forEach { facts ->
            HomeLaunchFactRow(*facts.toTypedArray())
        }
        if (descriptionMaxLines > 0) {
            val heading: @Composable () -> Unit = {
                val palette = LocalMimirPalette.current
                MimirChromeText(
                    "About",
                    style = MimirTypeScale.RunicEyebrow,
                    color = palette.textMuted,
                    maxLines = 1,
                    modifier = Modifier.padding(top = MimirSpacing.xs),
                )
            }
            val description: @Composable (Int) -> Unit = { visibleLines ->
                HomeLaunchCardDescription(
                    text = homeShelfDescriptionText(
                        description = state.row.description,
                        descriptionSource = state.row.descriptionSource,
                        screenScraperConnected = state.screenScraperConnected,
                        isPcGames = state.row.platformId == PlatformId.PC_GAMES,
                        steamGridDbKeyPresent = state.steamGridDbKeyPresent,
                    ),
                    visibleLines = visibleLines,
                    authority = authority,
                    focusKey = focusContract.descriptionKey,
                    focusScope = focusContract.scope,
                    fallbackFocusKey = focusContract.playKey,
                    focused = focusedKey == focusContract.descriptionKey,
                    activeOwnerToken = descriptionScrollOwnerToken,
                    scrollCommand = descriptionScrollCommand,
                    onScrollStateChanged = onDescriptionScrollStateChanged,
                )
            }
            if (fillHeight) {
                HomeLaunchCardAboutFill(
                    maxLines = descriptionMaxLines,
                    heading = heading,
                    description = description,
                    modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                )
            } else {
                heading()
                description(descriptionMaxLines)
            }
        }
    } else {
        HomeLaunchFactRow(
            "TYPE" to typeLabel.orEmpty(),
            "LAST PLAYED" to (state.lastPlayedLabel ?: "Not available"),
        )
    }
}

private enum class HomeLaunchAboutSlot { Heading, Description }

/**
 * The wide card's About: the heading, then the description in a viewport of WHOLE lines, as many as the height left under the fact rows holds (at most [maxLines]).
 *
 * The description used to be handed its full line count inside a column that nothing scrolled, so a card short of room (Thor at 1.3x with REGION, VERSION, SIZE and DISCS
 * listed) cut it mid-line at the card's edge: no down arrow, and UP from Play found nothing to scroll. Measuring the heading first and giving the description the lines that
 * fit makes its own viewport end on a line, so the text scrolls inside it ([HomeLaunchCardDescription]'s `scrollable`, its arrow, the controller's UP). When not even one line
 * fits the whole block is left out, heading with it: an "ABOUT" over nothing is worse than no About.
 *
 * One pixel is held back from the room: the viewport's height is a dp sum rounded to pixels, and a viewport a pixel under its lines would clip the last one.
 */
@Composable
private fun HomeLaunchCardAboutFill(
    maxLines: Int,
    heading: @Composable () -> Unit,
    description: @Composable (visibleLines: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    SubcomposeLayout(modifier) { constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val loose = Constraints(maxWidth = width, maxHeight = height)
        val headingPlaceable = subcompose(HomeLaunchAboutSlot.Heading, heading).first().measure(loose)
        val gap = MimirSpacing.sm.roundToPx()
        val inset = HomeLaunchDescriptionContentInset.roundToPx()
        val lineHeight = HomeLaunchDescriptionLineHeight.toPx()
        val below = (height - headingPlaceable.height - gap).coerceAtLeast(0)
        val lines = minOf(maxLines, floor((below - 2 * inset - 1) / lineHeight).toInt())
        if (lines < 1) return@SubcomposeLayout layout(width, height) {}
        val descriptionPlaceable = subcompose(HomeLaunchAboutSlot.Description) { description(lines) }
            .first()
            .measure(Constraints(maxWidth = width, maxHeight = below))
        layout(width, height) {
            headingPlaceable.place(0, 0)
            descriptionPlaceable.place(0, headingPlaceable.height + gap)
        }
    }
}

@Composable
private fun HomeLaunchCardDescription(
    text: String,
    visibleLines: Int,
    authority: FocusAuthority,
    focusKey: FocusKey,
    focusScope: FocusScope,
    fallbackFocusKey: FocusKey,
    focused: Boolean,
    activeOwnerToken: Long,
    scrollCommand: HomeLaunchDescriptionScrollCommand,
    onScrollStateChanged: (HomeLaunchDescriptionScrollState) -> Unit,
) {
    val palette = LocalMimirPalette.current
    val descriptionLineHeightPx = with(LocalDensity.current) {
        HomeLaunchDescriptionLineHeight.toPx().roundToInt().coerceAtLeast(1)
    }
    val descriptionLineHeightDp = with(LocalDensity.current) {
        HomeLaunchDescriptionLineHeight.toDp()
    }
    val scrollState = rememberScrollState()
    var viewportHeightPx by remember { mutableIntStateOf(0) }
    var descriptionLineCount by remember(text) { mutableIntStateOf(0) }
    val revision = remember(text) { homeLaunchDescriptionRevision(text) }
    var settledSequence by remember(activeOwnerToken) { mutableIntStateOf(0) }
    var observedRevision by remember(activeOwnerToken) { mutableLongStateOf(revision) }
    val measuredMaxOffset = scrollState.maxValue
    val alignedMaxOffset = homeLaunchDescriptionAlignedMaxOffset(
        maxOffset = measuredMaxOffset,
        lineHeightPx = descriptionLineHeightPx,
    )
    val measured = homeLaunchDescriptionMeasured(viewportHeightPx, measuredMaxOffset)
    val scrollable = measured && alignedMaxOffset > 0
    val publishedOffset = homeLaunchDescriptionPublishedOffset(
        observedRevision = observedRevision,
        measuredRevision = revision,
        scrollOffset = scrollState.value,
    )
    val canScrollUp = scrollable && publishedOffset > 0
    val canScrollDown = scrollable && publishedOffset < alignedMaxOffset
    val state = HomeLaunchDescriptionScrollState(
        measured = measured,
        scrollable = scrollable,
        canScrollUp = canScrollUp,
        canScrollDown = canScrollDown,
        offset = publishedOffset,
        maxOffset = if (measured) alignedMaxOffset else 0,
        revision = revision,
        ownerToken = activeOwnerToken,
        settledSequence = settledSequence,
    )

    // If measurement or replacement copy removes this destination while it owns focus, reconcile
    // directly to the primary action. This uses the existing authority; it does not add a second
    // focus engine or leave the card on an unregistered description key.
    LaunchedEffect(scrollable, focused, focusScope, focusKey, fallbackFocusKey) {
        if (!scrollable && focused) authority.requestFocus(fallbackFocusKey)
    }

    // Deliberately no dispose-to-zero callback: a disappearing destination must not erase the exact return
    // snapshot before its replacement composition has measured and settled.
    LaunchedEffect(state) { onScrollStateChanged(state) }
    LaunchedEffect(revision, measured, measuredMaxOffset) {
        if (!measured) return@LaunchedEffect
        val resetTarget = homeLaunchDescriptionRevisionResetTarget(
            observedRevision = observedRevision,
            measuredRevision = revision,
            measuredMaxOffset = measuredMaxOffset,
        ) ?: return@LaunchedEffect
        scrollState.scrollTo(resetTarget)
        observedRevision = revision
    }
    LaunchedEffect(
        scrollCommand,
        measuredMaxOffset,
        revision,
        viewportHeightPx,
        activeOwnerToken,
        descriptionLineHeightPx,
    ) {
        if (!measured ||
            !homeLaunchDescriptionCommandPending(scrollCommand, settledSequence, activeOwnerToken)
        ) return@LaunchedEffect
        val restoreTarget = homeLaunchDescriptionRestoreTarget(
            command = scrollCommand,
            measuredMaxOffset = measuredMaxOffset,
            measuredRevision = revision,
            activeOwnerToken = activeOwnerToken,
        )
        when {
            restoreTarget != null -> scrollState.scrollTo(restoreTarget)
            scrollCommand.restoreOffset != null -> return@LaunchedEffect
            scrollable && scrollCommand.direction != 0 -> {
                val target = homeLaunchDescriptionPageTarget(
                    currentOffset = scrollState.value,
                    direction = scrollCommand.direction,
                    viewportHeightPx = viewportHeightPx,
                    maxOffset = measuredMaxOffset,
                    lineHeightPx = descriptionLineHeightPx,
                )
                scrollState.animateScrollTo(target, tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing))
            }
        }
        settledSequence = scrollCommand.sequence
    }

    Box(
        modifier = Modifier
            .homeLaunchDescriptionOutdent()
            .fillMaxWidth()
            .heightIn(
                max = homeLaunchDescriptionViewportHeight(
                    visibleLines = visibleLines,
                    lineHeightDp = descriptionLineHeightDp,
                ),
            )
            .testTag(HomeLaunchCardDescriptionViewportTestTag)
            .onSizeChanged { viewportHeightPx = it.height }
            .then(
                if (scrollable) Modifier.mimirFocusable(authority, focusKey, focusScope)
                else Modifier,
            )
            .homeLaunchDescriptionSelectionRing(focused && scrollable, identity = focusKey),
    ) {
        Text(
            text,
            style = MimirTypeScale.Support,
            fontSize = 13.sp,
            lineHeight = HomeLaunchDescriptionLineHeight,
            color = palette.textPrimary,
            onTextLayout = { descriptionLineCount = it.lineCount },
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(
                    start = HomeLaunchDescriptionContentInset,
                    top = HomeLaunchDescriptionContentInset,
                    end = if (scrollable) {
                        MimirSpacing.lg + HomeLaunchDescriptionContentInset
                    } else {
                        HomeLaunchDescriptionContentInset
                    },
                    bottom = HomeLaunchDescriptionContentInset + if (
                        descriptionLineCount > visibleLines
                    ) {
                        descriptionLineHeightDp
                    } else {
                        0.dp
                    },
                ),
        )
        homeLaunchDescriptionContinuationGlyph(canScrollUp, canScrollDown)?.let { glyph ->
            Text(
                glyph,
                style = MimirTypeScale.MicroStatus,
                color = palette.accent,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = HomeLaunchDescriptionContentInset,
                        bottom = HomeLaunchDescriptionContentInset,
                    )
                    .background(palette.surfaceRaised.copy(alpha = 0.78f), RoundedCornerShape(99.dp))
                    .padding(horizontal = MimirSpacing.xs, vertical = 1.dp),
            )
        }
    }
}

internal val HomeLaunchDescriptionContentInset = 4.dp

/**
 * Seats the description viewport [HomeLaunchDescriptionContentInset] out to the start of its column, and that much wider, so the
 * text inside it (inset by the same amount, which is the room the selection ring needs round it) starts ON the column's edge.
 *
 * The viewport used to start at the edge, so its text stood 4 dp in from the "ABOUT" heading above it and from every fact above that
 * (RC-20, H-04: "ABOUT indented about 4 px"). The ring still has its 4 dp of air round the text, now outside the column. The column
 * keeps its width and its right edge, so nothing else on the card moves; [placeRelative] mirrors it under RTL.
 */
private fun Modifier.homeLaunchDescriptionOutdent(): Modifier = layout { measurable, constraints ->
    val inset = HomeLaunchDescriptionContentInset.roundToPx()
    val widened = if (constraints.hasBoundedWidth) constraints.copy(maxWidth = constraints.maxWidth + inset) else constraints
    val placeable = measurable.measure(widened)
    val width = if (constraints.hasBoundedWidth) (placeable.width - inset).coerceAtLeast(0) else placeable.width
    layout(width, placeable.height) { placeable.placeRelative(-inset, 0) }
}
internal val HomeLaunchDescriptionLineHeight = 19.sp

internal fun homeLaunchDescriptionViewportHeight(
    visibleLines: Int,
    lineHeightDp: Dp,
) = lineHeightDp * visibleLines.coerceAtLeast(0) + HomeLaunchDescriptionContentInset * 2

internal fun homeLaunchDescriptionPageTarget(
    currentOffset: Int,
    direction: Int,
    viewportHeightPx: Int,
    maxOffset: Int,
    lineHeightPx: Int,
): Int {
    if (direction == 0 || viewportHeightPx <= 0 || maxOffset <= 0 || lineHeightPx <= 0) {
        return currentOffset.coerceIn(0, maxOffset.coerceAtLeast(0))
    }
    val visibleLines = (viewportHeightPx / lineHeightPx).coerceAtLeast(1)
    val pageLines = (visibleLines * 3 / 4).coerceAtLeast(1)
    val delta = pageLines * lineHeightPx * direction.coerceIn(-1, 1)
    // ScrollState.maxValue can include a few residual pixels from font shaping and density
    // conversion. Stopping on that raw value exposes a clipped fragment of the preceding line at
    // the top edge. The final controller stop therefore remains on the last complete line seat.
    val alignedMaxOffset = homeLaunchDescriptionAlignedMaxOffset(maxOffset, lineHeightPx)
    return (currentOffset + delta).coerceIn(0, alignedMaxOffset)
}

internal fun homeLaunchDescriptionAlignedMaxOffset(
    maxOffset: Int,
    lineHeightPx: Int,
): Int = if (maxOffset <= 0 || lineHeightPx <= 0) {
    0
} else {
    maxOffset - maxOffset % lineHeightPx
}

@Composable
private fun HomeLaunchFactRow(vararg facts: Pair<String, String>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MimirSpacing.xl)) {
        facts.forEach { (label, value) ->
            Column(Modifier.weight(1f)) {
                val palette = LocalMimirPalette.current
                MimirChromeText(
                    label,
                    style = MimirTypeScale.RunicEyebrow,
                    color = palette.textMuted,
                    maxLines = 1,
                )
                Text(
                    value,
                    style = MimirTypeScale.Support,
                    fontSize = 13.sp,
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The description's ring (ring migration, 2026-09-28): the one selection ring on the square corner its selection
 * carries, as a card -- no wash and no hairline, because this is prose, not a row. It reads only while the copy
 * scrolls (the caller's `focused && scrollable`): an unscrollable description cannot take focus.
 */
@Composable
private fun Modifier.homeLaunchDescriptionSelectionRing(focused: Boolean, identity: Any): Modifier = mimirSelectionRing(
    focused = focused,
    identity = identity,
    shape = MimirRingShape.Card(HomeLaunchDescriptionSelectionCorner),
)

/**
 * **T-1, the touch grammar of this card.**  One tap ACTIVATES; focus moves as a
 * *consequence* of activating, never as a prerequisite for it.
 *
 * This replaces the inherited `if (focused) onActivate() else authority.requestFocus(key)` idiom that
 * every action in this card used to carry. That idiom made touch imitate the controller's
 * focus-then-activate grammar — a habit from the retired WebView product, where separating pointer and
 * key dispatch was hard. Compose dispatches them separately, so the two inputs need only agree on what
 * an element DOES, not on how it is triggered. Concretely it cost the player a tap per action: three
 * taps from the shelf to start a game, four from inside a Tome.
 *
 * **The order of the two statements is load-bearing — do not flip it.** Home binds `onPlay` to
 * `bindings.overlays.dispatch(ControllerAction.CONFIRM)` (`MimirAppHost.kt`), and that dispatch resolves
 * its target from the CURRENTLY FOCUSED key, not from the tapped element. Publishing focus first is what
 * makes the tapped button — rather than whatever the controller last left focused — the thing that acts.
 * `DefaultFocusAuthority.requestFocus` is synchronous (a `setFocus` write to the state flow, no
 * coroutine, no frame boundary), so the call on the next line already observes the new focus.
 *
 * Same shape and same reasoning as [activateGamelistGameCard], the idiom already dominant in this tree.
 * **The controller path is untouched**: CONFIRM still arrives through `dispatchHomeLaunchCard`, which
 * this function neither calls nor changes.
 */
internal fun activateHomeLaunchCardAction(
    authority: FocusAuthority,
    key: FocusKey,
    onActivate: () -> Unit,
) {
    authority.requestFocus(key)
    onActivate()
}

@Composable
private fun HomeLaunchActionButton(
    label: String,
    key: FocusKey,
    focused: Boolean,
    scope: FocusScope = HomeLaunchCardScope,
    primary: Boolean = false,
    enabled: Boolean = true,
    authority: FocusAuthority,
    onActivate: () -> Unit,
    /** The label's type, solved once for the card's whole footer ([rememberHomeLaunchRowLabelStyle]) so every button on the card wears one size. */
    labelStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    val palette = LocalMimirPalette.current
    // Update (no filled blob; sweep 10-09: PLAY was tinted at rest and when focused): every action is an etched chip, a capsule, PLAY the PRIMARY one (the same dark
    // plate as the others, its rim and word in the accent). Its fixed width and its 44 dp height are unchanged, so the row solver and the registered focus bounds read what they did.
    val shape = RoundedCornerShape(percent = 50)
    // The gesture node below is keyed on `Unit` and reads its target through `rememberUpdatedState`,
    // deliberately NOT keyed on the lambda. `pointerInput`'s keys are a RESTART contract: a changed
    // key disposes the detector and cancels whatever gesture is in flight, so a key that changes
    // while a finger is down eats the tap.
    //
    // **Honest scope of this choice.** Keying on `onActivate` is *currently* safe, and that was
    // measured, not assumed: with the lambda-keyed variant in place, a tap spanning a real
    // recomposition of this card still fired (`HomeLaunchCardTouchGrammarTest`). The reason is
    // Compose's strong skipping (default-on from Compose compiler 2.0.20; this tree is on Kotlin
    // 2.0.21), which memoizes lambda literals even when their captures — here the `FocusAuthority`
    // interface and the host's `bindings` — are inferred unstable. So this is HARDENING, not a bug
    // fix: it makes the gesture node's lifetime independent of a compiler setting and of every
    // future capture added to these lambdas, rather than relying on memoization holding.
    val currentActivate by rememberUpdatedState(onActivate)
    val currentKey by rememberUpdatedState(key)
    Row(
        modifier = modifier
            .heightIn(min = 44.dp)
            .testTag(homeLaunchCardActionTestTag(key))
            .mimirFocusable(authority, key, scope)
            // The one ring (ring migration, 2026-09-28): a pill ring on the plate itself, no inset, before the surface, so its edge is the plate's edge and its wash is under the opaque
            // metal. The metal has its own rim, which hands over to the ring while the button is focused: no resting hairline. No arrival flare: the arrival flare remains on onboarding pills,
            // rather than this card; adding a wider flare remains a design decision. Nothing scales.
            .mimirSelectionRing(
                focused = focused,
                identity = key,
                shape = MimirRingShape.Pill,
                flare = false,
                edgeFlush = true,
            )
            // BELOW the ring, and modulating. This layer used to sit above it, and an alpha'd layer composites through
            // a buffer the size of the button, which cut the ring on every disabled button (spec §3.1). ModulateAlpha
            // dims per draw call instead; a button's ground, wash and label do not overlap, so nothing shows through.
            .graphicsLayer {
                alpha = if (enabled) 1f else 0.55f
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .clip(shape)
            .mimirEtchedChipSurface(palette, warmed = false, focused = focused, primary = primary, edgeFlush = true)
            // A raw pointer gesture, never `clickable`/`combinedClickable`: BOTH build an
            // `AbstractClickableNode`, which constructs a `FocusableNode` and therefore a real
            // `FocusTargetModifierNode` — a second Compose focus target planted beside the single
            // FocusAuthority this row already registers with via `mimirFocusable` above.
            // `tools/mimir_ui_invariants_gate.py`'s F1 rules grep only for the literals `.focusable(`
            // and `focusTarget(`, so that violation is invisible to the gate; the same reasoning is
            // written down at `GamelistCarousel.kt`'s media row. `onTap` only — registering
            // `onDoubleTap` would defer EVERY single tap by the platform double-tap timeout.
            .then(
                if (enabled) {
                    Modifier
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    activateHomeLaunchCardAction(authority, currentKey, currentActivate)
                                },
                            )
                        }
                        // Carries the click ACTION `clickable` used to publish to accessibility
                        // services, without carrying its focus node — the pairing `MimirKeyboard`
                        // (this tree's touch-primary reference surface) already uses for its keys.
                        //
                        // `mergeDescendants = true` is a PARITY restore, not a flourish:
                        // `AbstractClickableNode.getShouldMergeDescendantSemantics` is a hardcoded
                        // `return true` in the shipped `foundation-android:1.7.3` (verified from the
                        // artifact's own bytecode), so `clickable` merged this row's label into one
                        // accessibility node. Dropping the merge would leave the click action on a
                        // text-less parent with the label as a separate child — TalkBack would land
                        // on a label with no action.
                        .semantics(mergeDescendants = true) {
                            onClick(label = label) {
                                activateHomeLaunchCardAction(authority, key, onActivate)
                                true
                            }
                        }
                } else {
                    // Matches `clickable(enabled = false)`: no tap target and no click action, so a
                    // tap falls through to the card body's swallow rather than acting on a disabled
                    // action.
                    Modifier
                },
            )
            .padding(horizontal = MimirSpacing.md, vertical = MimirSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        // the earlier update / the button's width is fixed (the focus bounds and the row solver read it), so the LABEL gives, and it gives for the whole
        // footer at once: [labelStyle] is the smallest size any label on the card needs to stand whole on one line (rememberHomeLaunchRowLabelStyle),
        // so the card never wears two sizes. At font 1.0 nothing steps down; at 1.3 the longest label sets the size for every button.
        MimirChromeText(
            label,
            style = labelStyle,
            color = palette.etchedChipInk(primary = primary),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The action buttons' label type before any fitting: the etched chip's own type at 12 sp (the size they have always worn), so the card's buttons read as the chip family. */
private val HomeLaunchActionLabelBase: TextStyle = MimirEtchedChipLabelStyle.copy(fontSize = 12.sp)

/** Pixels held back from a button's label room, so a label that just fits is not ellipsized by the rounding of its last letter. */
private const val HomeLaunchActionLabelSlackPx = 2

/** Each step down is this fraction of the size before it, and the label never goes below this fraction of its base size. */
private const val HomeLaunchActionLabelStep = 0.94f
private const val HomeLaunchActionLabelFloor = 0.55f

/**
 * the one label type a whole row of action buttons is drawn in. [buttons] are the row's labels with the width each button asks for; the
 * seats the row really gives them ([homeLaunchRowSeatWidths]) set the room, and the style is solved at the card's density, so a font scale re-solves
 * it. A row of one is just that button's own size.
 */
@Composable
private fun rememberHomeLaunchRowLabelStyle(buttons: List<Pair<String, Dp>>, rowWidth: Dp = Dp.Infinity): TextStyle {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(buttons, rowWidth, density, measurer) {
        val seats = homeLaunchRowSeatWidths(buttons.map { it.second }, rowWidth)
        val padding = with(density) { MimirSpacing.md.roundToPx() * 2 }
        homeLaunchActionRowLabelStyle(
            labels = buttons.mapIndexed { index, (label, _) ->
                label to (with(density) { seats[index].roundToPx() } - padding - HomeLaunchActionLabelSlackPx).coerceAtLeast(0)
            },
            base = HomeLaunchActionLabelBase,
        ) { text, style -> measurer.measure(text, style, maxLines = 1, softWrap = false).size.width }
    }
}

/**
 * The width each button of a row really gets: a `Row` hands its children the room that is left, in order, so a button asked for more than
 * that is squeezed to it ([rowWidth] is [Dp.Infinity] for a row that wraps its content). The gap is [HomeLaunchCardActionGap].
 */
internal fun homeLaunchRowSeatWidths(widths: List<Dp>, rowWidth: Dp): List<Dp> {
    var left = rowWidth
    return widths.map { width ->
        val seat = minOf(width, left).coerceAtLeast(0.dp)
        left -= seat + HomeLaunchCardActionGap
        seat
    }
}

/**
 * the label type for a whole row of buttons, solved once: the smallest size any of its labels needs in its own room ([labels] pair a
 * label with its room in px), so no button in the row is drawn larger or smaller than another. [base] when every label already fits.
 */
internal fun homeLaunchActionRowLabelStyle(
    labels: List<Pair<String, Int>>,
    base: TextStyle,
    measure: (text: String, style: TextStyle) -> Int,
): TextStyle = labels
    .map { (label, room) -> homeLaunchActionLabelStyle(label, room, base, measure) }
    .minByOrNull { it.fontSize.value }
    ?: base

/**
 * The type a button's [label] is drawn in so that all of it stands on one line in [roomPx]: [base] when it already fits, else [base]
 * stepped down (6% at a time, to no less than 55% of it) until it does. [measure] is the width in px of the text as drawn at a
 * style; the label is measured uppercase because the card draws its labels as chrome (full caps).
 */
internal fun homeLaunchActionLabelStyle(
    label: String,
    roomPx: Int,
    base: TextStyle,
    measure: (text: String, style: TextStyle) -> Int,
): TextStyle {
    val text = label.uppercase(Locale.ROOT)
    var size = base.fontSize.value
    val floor = size * HomeLaunchActionLabelFloor
    var style = base
    while (size > floor && measure(text, style) > roomPx) {
        size *= HomeLaunchActionLabelStep
        style = base.copy(fontSize = size.sp)
    }
    return style
}
