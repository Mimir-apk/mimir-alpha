package com.gruning.mimir.rewrite.core.ui

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * The fixed final-shelf command order. Runes are visual icons; [labelRes] is the accessible command name, a string resource so the
 * dock reads in the player's language (localization step 2). A test resolves it with the `Resources` of the locale it checks.
 */
enum class MimirQuickRailAction(
    val rune: String,
    @StringRes val labelRes: Int,
) {
    HOME(rune = "ᚺ", labelRes = R.string.ui_dock_home),
    LIBRARY(rune = "ᛚ", labelRes = R.string.ui_dock_library),
    BRIGHTNESS(rune = "ᛋ", labelRes = R.string.ui_dock_utility),
    SETTINGS(rune = "ᚲ", labelRes = R.string.ui_dock_settings),
    /**
     * FUX-01 §4.4: the dock entry for the EXISTING quick-launch capability (Tiwaz — the arrow, launch).
     * Policy, Nova device look at commit `7f80b480`: the DOCK LABEL reads "Apps", not "Quick Launch" —
     * the enum constant, dispatch, and focus-key identity are unchanged (still `QUICK_LAUNCH`
     * everywhere else in the tree); only this display string moved. The Quick Launch picker/overlay's
     * OWN header ("ᛗ · QUICK LAUNCH" — HomeQuickLaunchOverlay.kt, HomeQuickLaunchPickerOverlay.kt,
     * CompanionSurface.kt) and every other surface naming the feature are a SEPARATE, unresolved
     * question — reported to the player, not renamed here.
     */
    QUICK_LAUNCH(rune = "ᛏ", labelRes = R.string.ui_dock_apps),
    /**
     * Dock end-cap:
     * opens the real file manager. A rune, not a folder pictograph -- five runes and one Material
     * pictograph on the one surface most concentrated with the product's visual identity would make
     * the pictograph the odd one out. Othala (ᛟ) is the futhark's rune of inherited property, the
     * estate -- the closest semantic match to "your kept things" of any rune in the alphabet, and it
     * occurs nowhere else on the rail as a standalone mark. Appended LAST: terminal position, after
     * QUICK LAUNCH, so no existing action's index or muscle memory moves.
     */
    FILES(rune = "ᛟ", labelRes = R.string.ui_dock_files),
    ;

    /**
     * The drawn STAVE mark for this action, if the set is being used instead of the text rune.
     *
     *  Each dock mark ENCODES
     * its rune rather than replacing it — `DOCK_HOME` is a house whose two posts and single diagonal
     * are Hagalaz, `DOCK_UTILITY` is Sowilo outright, `DOCK_APPS` carries Tiwaz as the arrow above the
     * tiles — so the mapping below is identity, not translation, and [rune] stays the accessible name
     * and the fallback.
     */
    val dockMark: MimirIcon
        get() = when (this) {
            HOME -> MimirIcon.DOCK_HOME
            LIBRARY -> MimirIcon.DOCK_LIBRARY
            BRIGHTNESS -> MimirIcon.DOCK_UTILITY
            SETTINGS -> MimirIcon.DOCK_SETTINGS
            QUICK_LAUNCH -> MimirIcon.DOCK_APPS
            FILES -> MimirIcon.DOCK_FILES
        }
}

/**
 * Phases reported by the optional Home rail diagnostic observer.
 *
 * The observer is attached to the rail's existing tap detector. It never participates in gesture
 * consumption or activation, and the default callback is inert for normal and release builds.
 */
enum class MimirQuickRailTouchPhase {
    DOWN,
    UP,
    CANCEL,
    TAP,
}

/**
 * Whether the dock draws the STAVE marks or the text runes.
 *
 * One constant selects the drawn marks so they can be compared with the text runes.
 *
 * The marks are 1.5 units of stroke against this rail's 1 dp hairline chrome, so they will read
 * HEAVIER than the glyphs they stand in for. That is the thing to judge in the photograph.
 */
const val MimirDockUsesDrawnMarks = true

/** Stable quick-rail geometry. Locale and focus state never participate in measurement. */
object MimirRunicQuickRailDefaults {
    val MinActionDiameter: Dp = MimirSpacing.minTarget
    /** Stable paint envelope; focus scaling must never escape this box. */
    val ActionBoxDiameter: Dp = 64.dp
    val MaxActionDiameter: Dp = 60.dp

    /**
     * The drawn STAVE mark's size on a rail coin, when [MimirDockUsesDrawnMarks] is on.
     *
     * 26 dp rather than the set's authored 24: it sits inside a 60 dp coin where the text rune it
     * replaces rendered taller than 24, and dropping to the authored size would shrink the dock's
     * marks as a side effect of changing what they ARE. Above the set's small-size threshold, so no
     * stroke boost applies and this is the geometry as drawn.
     */
    val DrawnMarkSize: Dp = 26.dp
    const val FocusedScale: Float = 1.04f

    /**
     * The gliding focus ring's diameter as a share of the seat's coin ([mimirQuickRailItemDiameter]).
     *
     * The seats paint no discs; focus is one [drawMimirFocusTileRing] gliding between them.
     * them. At the full coin size that ring's strokes would spill well into the label band below the 64 dp
     * envelope. At three quarters its two strong strokes (95 % and 18 %) stay inside the envelope and only the
     * 8 % outer bloom reaches the band's top 4 dp, above the label's glyphs. It still scales with the coin, so
     * the FILES end-cap's smaller seat (D-3) keeps its smaller ring and the rail's narrowing still shrinks it.
     */
    const val FocusRingCoinRatio: Float = 0.75f

    /**
     * The accent wash inside the focus ring: the glass rows' focus fill (RC-11, accent at 0.16), not the old solid
     * molten disc. The mark stays the loudest thing on the seat.
     */
    const val FocusWashAlpha: Float = 0.16f
    /**
     * One seat width for all six actions, correcting uneven spacing measured on Nova.
     * (build `f0a937f0`): coin centres at 324/428/532/636/784/944px -- gaps of 104/104/104/148/160,
     * i.e. four evenly spaced seats then two drifting apart. The cause was two WIDER, differentiated
     * seats:
     * - `QuickLaunchSlotWidth` (was 104dp): authored when the dock label read "QUICK LAUNCH" and needed
     *   more room than a single word. **OVERTURNED 2026-08-21**: the label is now "Apps" (commit
     *   `7f80b480`), four characters, shorter than every other label on the rail -- the rationale the
     *   wider seat existed for is gone.
     * - `EndCapSlotWidth` (was 72dp): documented as
     *   "deliberate, not a near-miss of either neighbour." **OVERTURNED 2026-08-21** by the same device
     *   observation -- a differentiated end-cap width is exactly what produced the measured uneven gap. The coin-size constraint (the FILES coin itself pinned to 48dp, not this slot
     *   width) is UNCHANGED -- see [mimirQuickRailItemDiameter], not touched by this override.
     *
     * Verified neutral on total rail width: `6 x 88 == 528` (`ActionSlotWidth.value * 6`), exactly what
     * `88x4 + 104 + 72` produced before -- [RailWidth] is unaffected by this override at the Odin, where
     * the rail already rendered at its unscaled preferred width. It is NOT neutral at every seat's own
     * position: QUICK_LAUNCH and FILES each move (see [preferredSlotWidth]/[preferredRailWidth]'s own
     * doc for each seat's resulting position).
     */
    val ActionSlotWidth: Dp = 88.dp

    /**
     * Observed on Nova: the rail-narrowing that already
     * scales [MaxActionDiameter] down to [MinActionDiameter] left the label fixed at 10/11sp with
     * `TextOverflow.Clip` -- "SETTINGS"/"UTILITY" clipped in the narrowed 52dp seat. The label must
     * shrink WITH the rail exactly as the coin does (see [mimirQuickRailLabelFontSize]), floored here at
     * [MimirTypeStep.Micro] -- the capped five-step type scale's own lowest step, never an invented size between steps. Micro already ships as a legible size in
     * comparably tight chrome ([MimirTypeScale.MicroStatusCompact], [MimirTypeScale.ControlHintCompact],
     * [MimirTypeScale.RunicEyebrowCompact]) on these same devices.
     */
    val MinLabelFontSize = MimirTypeStep.Micro

    /**
     * Nova capture ( dock at 82.5% of the viewport's 640dp width, "X ·
     * TOME" crammed against FILES): the narrowest a six-seat rail can EVER be asked to render without
     * forcing a seat's visible coin -- not merely its paint envelope -- below [MinActionDiameter].
     * **Derived** alongside [ActionSlotWidth]'s equal-seat override: six seats, each at
     * the coin's own hard floor -- `6 x 48 = 288dp` (was `4x48 + 96 + 64 = 352dp`, the sum of the two
     * per-seat floors [ActionSlotWidth]'s KDoc retired). [mimirQuickRailSlotWidths] never lets a seat
     * render below [MinActionDiameter] regardless of how far [availableWidth] drops below this value --
     * going lower is unsafe (an unhittable coin), not merely undesired -- but with every seat now sharing
     * one preferred width, a per-seat floor pinning only a subset would itself reintroduce the uneven
     * spacing this override exists to remove, so the floor is applied uniformly to all six (see
     * [mimirQuickRailSlotWidths]).
     *
     * This is a real narrowing, not a wash: 288dp < 352dp, so [mimirQuickRailTargetWidth] can now ask
     * for a narrower rail than before at very constrained viewports. It does not move any of the three
     * certified device targets (Odin/Nova/RP5 all resolve their target well above either value), and it
     * fixes a latent inconsistency the old number carried: 352dp exceeded `HomeShelfGeometry`'s own
     * declared minimum supported viewport width (320dp) -- a "floor" wider than the narrowest thing it
     * was supposed to floor. 288dp does not.
     */
    val MinTargetRailWidth: Dp = MinActionDiameter * MimirQuickRailAction.entries.size

    /**
     * Reserved on EITHER side of the centered rail before deciding how much room it gets. Sized to the
     * corner marks that flank the dock on Home (`feature-library`'s `HomeShelfGameCountPaintWidth` /
     * `HomeShelfTomeShortcutHintPaintWidth`, both 104dp) plus their gutter (`HomeShelfGeometry`'s own
     * clamp, ~16-28dp) and a further ~12dp of deliberate breathing room, measured against the
     * Odin/Nova/RP5/538dp family -- see [mimirQuickRailTargetWidth]'s own doc and
     * MimirFinalShelfPrimitivesTest / HomeShelfGeometryTest for the pinned numbers this produces.
     */
    val RailEndReserve: Dp = 136.dp

    /**
     * The end reserve on a compact profile. On an RG Rotate 564 dp square viewport, 136 dp a side left the rail
     * 293 dp, six coins at their 48 dp floor 0.8 dp apart. 64 dp holds the compact corner count (~44 dp reach)
     * plus the compact gutter and breathing room, and gives the coins ~25 dp between them.
     */
    val CompactRailEndReserve: Dp = 64.dp
    // 22dp holds the Pill line at font-scale 1.3; the band's own leading is the circle-to-label gap,
    // so the rail stays 86dp and the Library shelf's certified 444dp lane budget is untouched.
    val LabelBandHeight: Dp = 22.dp
    val RailHeight: Dp = ActionBoxDiameter + LabelBandHeight
    val RailWidth: Dp = preferredRailWidth(MimirQuickRailAction.entries)

    // One seat width for every action -- see ActionSlotWidth's own KDoc for the 2026-08-21 override
    // that retired QUICK_LAUNCH's and FILES's differentiated widths. [action] stays a parameter (rather
    // than dropping to a bare constant reference) so a future genuinely-differentiated seat -- should one
    // ever be needed -- has one call site to change, not every caller of preferredSlotWidth.
    fun preferredSlotWidth(action: MimirQuickRailAction): Dp = ActionSlotWidth

    fun preferredRailWidth(actions: List<MimirQuickRailAction>): Dp =
        actions.fold(0.dp) { width, action -> width + preferredSlotWidth(action) }
}

/**
 * Below the authored width: one uniform scale factor across every element, floored at the coin's own
 * hard floor ([MimirRunicQuickRailDefaults.MinActionDiameter]).
 *
 * Before correction on 2026-08-21, this scaled uniformly and then floored QUICK LAUNCH (96dp) and
 * the FILES end-cap (64dp), IN THAT ORDER, against the scaled result -- a floored element PINNED at its
 * floor while the shortfall redistributed across whichever elements were still unpinned. That shape
 * existed because QUICK_LAUNCH and FILES had different PREFERRED widths than the four regular seats and
 * than each other, so a floor pinning only two of them (in a specific order) was how their extra
 * breathing room degraded gracefully without the regular seats collapsing first.
 *
 * With every seat now sharing one preferred width ([MimirRunicQuickRailDefaults.ActionSlotWidth]), that
 * shape is exactly wrong: pinning a SUBSET of otherwise-identical seats at a floor is what reintroduces
 * uneven spacing at narrow viewports -- the same spacing defect, just moved from the
 * authored width to a narrowed one. Because every seat starts equal, uniform scaling alone keeps every
 * seat equal all the way down; either none of them cross the floor (all scale together) or all of them do
 * (same preferred width, same scale factor, same result), so there is never a "some pinned, some not"
 * case left to redistribute -- the old function's whole `unpinned`/`rescaleUnpinned` machinery is gone,
 * not merely reparented onto six entries instead of two.
 *
 * Seat-count geometry: At 528dp of
 * authored width the rail never enters the scaled branch at all (every certified viewport clears it);
 * the floor itself is dormant at every currently-known caller too -- see
 * [MimirRunicQuickRailDefaults.MinTargetRailWidth]'s own doc for why -- but stays as a defensive
 * per-call invariant since this function is called directly (bypassing that caller-side clamp) from
 * tests and could be again from a future caller.
 */
internal fun mimirQuickRailSlotWidths(
    actions: List<MimirQuickRailAction>,
    availableWidth: Dp,
): List<Dp> {
    if (actions.isEmpty()) return emptyList()
    val preferred = actions.map(MimirRunicQuickRailDefaults::preferredSlotWidth)
    val preferredRailWidth = preferred.fold(0.dp) { width, slot -> width + slot }
    if (preferredRailWidth <= availableWidth) return preferred

    val scale = (availableWidth.value / preferredRailWidth.value).coerceAtLeast(0f)
    return preferred.map { slot -> maxOf(MimirRunicQuickRailDefaults.MinActionDiameter, slot * scale) }
}

/**
 * [mimirQuickRailSlotWidths] for a rail whose seats are partly present. the Files end-cap leaves the dock while a
 * game runs and returns when it ends; the seat fades and the others close over it. A seat
 * at [presences] `p` counts for `p` of a seat, so the one uniform scale and the per-seat floor the discrete solver applies at rest apply at
 * every frame between: at the Nova's 368 dp six seats are 61.33 dp and five are 73.6 dp, and the survivors travel from one to the other
 * as the end-cap goes instead of jumping the frame it is dropped, while the rail keeps the target width throughout. With every presence
 * at 1 this is the discrete solver, value for value. The result is each seat's FULL slot (what its content is measured at); the seat is
 * DRAWN at that times its presence ([Modifier.quickRailSeatWidth]).
 */
internal fun mimirQuickRailSlotWidths(
    actions: List<MimirQuickRailAction>,
    presences: List<Float>,
    availableWidth: Dp,
): List<Dp> {
    require(presences.size == actions.size) { "one presence per seat: ${presences.size} for ${actions.size}" }
    if (actions.isEmpty()) return emptyList()
    val preferred = actions.map(MimirRunicQuickRailDefaults::preferredSlotWidth)
    val preferredRailWidth = mimirQuickRailPreferredRailWidth(actions, presences)
    if (preferredRailWidth <= availableWidth) return preferred

    val scale = (availableWidth.value / preferredRailWidth.value).coerceAtLeast(0f)
    return preferred.map { slot -> maxOf(MimirRunicQuickRailDefaults.MinActionDiameter, slot * scale) }
}

/** [MimirRunicQuickRailDefaults.preferredRailWidth] weighted by each seat's presence; the pair [mimirQuickRailSlotWidths] scales against. */
internal fun mimirQuickRailPreferredRailWidth(actions: List<MimirQuickRailAction>, presences: List<Float>): Dp =
    actions.indices.fold(0.dp) { width, index -> width + MimirRunicQuickRailDefaults.preferredSlotWidth(actions[index]) * presences[index] }

/**
 * The seats the rail draws while [actions] changes: every offered seat, in the offered order, plus the seats of [previous] that are no
 * longer offered, each kept where it sat so it can fade in place. A returning seat goes in after the nearest offered seat that precedes
 * it (or first), so the end-cap returns at the end and a leading seat at the front. A departed seat leaves this list only when its fade
 * is done ([QuickRailSeats.settle]).
 */
internal fun mimirQuickRailSeatsShown(
    previous: List<MimirQuickRailAction>,
    actions: List<MimirQuickRailAction>,
): List<MimirQuickRailAction> {
    val shown = previous.toMutableList()
    actions.forEachIndexed { index, action ->
        if (action in shown) return@forEachIndexed
        val predecessor = actions.take(index).lastOrNull { it in shown }
        shown.add(if (predecessor == null) 0 else shown.indexOf(predecessor) + 1, action)
    }
    return shown
}

/**
 * The rail's viewport-aware TARGET width -- fed to [mimirQuickRailSlotWidths] in place of the raw
 * available width. The Retroid Pocket Nova capture showed an oversized dock. [MimirRunicQuickRailDefaults.RailWidth] (528dp) is an authored CEILING, never a target --
 * rendering it at whatever a viewport happened to physically fit made the dock occupy an uncontrolled,
 * viewport-dependent fraction of the screen (63% of the Odin's 832.5dp, fine; 82.5% of the Nova's
 * 640dp, not). This reserves [MimirRunicQuickRailDefaults.RailEndReserve] on EACH side before deciding
 * how much room the rail gets, so it starts yielding BEFORE it physically collides with its neighbours
 * -- reusing [mimirQuickRailSlotWidths]'s existing uniform-scale-then-floor path rather than adding a
 * second sizing mechanism.
 *
 * Two guarantees, both load-bearing:
 * - Never above the raw [viewportWidth] itself (a rail can never be WIDER than its viewport).
 * - Never below [MimirRunicQuickRailDefaults.MinTargetRailWidth] -- a real structural floor (every
 *   seat's coin stays at or above [MimirRunicQuickRailDefaults.MinActionDiameter]), not a tuned choice.
 *
 * At the certified Odin (832.5dp) this resolves to 560.5dp -- comfortably above
 * [MimirRunicQuickRailDefaults.RailWidth] (32.5dp of headroom, not a float-rounding coincidence), so
 * [mimirQuickRailSlotWidths] takes its unscaled early-return path and the Odin's rail stays the exact
 * 528dp it always was. At the Nova (640dp) it resolves to 368dp -- the rail drops from 82.5% to 57.5%
 * of the viewport, and `homeShelfTomeShortcutHintClearanceDp` (feature-library) turns positive.
 */
fun mimirQuickRailTargetWidth(
    viewportWidth: Dp,
    endReserve: Dp = MimirRunicQuickRailDefaults.RailEndReserve,
): Dp = minOf(
    viewportWidth,
    maxOf(
        MimirRunicQuickRailDefaults.MinTargetRailWidth,
        viewportWidth - endReserve * 2,
    ),
)

internal fun mimirQuickRailShowsMoltenSeparator(palette: MimirThemePalette): Boolean =
    palette.materials.separator.dockTreatment == MimirDockSeparatorTreatment.MOLTEN

internal data class MimirQuickRailEmphasis(
    val usesSolidAccent: Boolean,
    val usesFocusRing: Boolean,
    val showsLabel: Boolean,
    val usesDestinationInk: Boolean,
)

/** The active destination is a quiet marker; focus alone owns the solid treatment. */
internal fun mimirQuickRailEmphasis(
    selected: Boolean,
    focused: Boolean,
): MimirQuickRailEmphasis = MimirQuickRailEmphasis(
    usesSolidAccent = focused,
    usesFocusRing = focused,
    // FUX-D (panel §5): labels are PERSISTENT — unfocused actions must stay discoverable; focus
    // brightens the label instead of revealing it.
    showsLabel = true,
    usesDestinationInk = selected && !focused,
)

/**
 * Resolves a 48..60 dp visual diameter inside the fixed 64 dp paint envelope.
 * Feature geometry solvers may pass an explicit diameter to [MimirRunicQuickRail].
 */
internal fun mimirQuickRailDiameter(availableWidth: Dp): Dp =
    (availableWidth / 10f).coerceIn(
        MimirRunicQuickRailDefaults.MinActionDiameter,
        MimirRunicQuickRailDefaults.MaxActionDiameter,
    )

/**
 * the FILES end-cap's coin is a FIXED 48dp -- the bottom of the 48-60dp clamp every OTHER coin
 * resolves inside, never the rail's solved [resolvedDiameter]. Its five neighbours sit at the top of
 * that same range; the end-cap pins to the bottom of a range the system already defines rather than
 * inventing a new size. The coin is centered in the unchanged 64dp paint envelope regardless
 * (`Box(Modifier.size(ActionBoxDiameter))` below), so a smaller coin never moves any other geometry.
 */
internal fun mimirQuickRailItemDiameter(action: MimirQuickRailAction, resolvedDiameter: Dp): Dp =
    if (action == MimirQuickRailAction.FILES) MimirRunicQuickRailDefaults.MinActionDiameter else resolvedDiameter

/**
 * The rail-narrowing label counterpart to [mimirQuickRailItemDiameter]/the coin's own `scaledDiameter`
 * math in [MimirRunicQuickRail]: full authored size ([MimirTypeScale.RailAction]'s [MimirTypeStep.Label])
 * whenever the solved [railWidth] is at or above the rail's [preferredWidth], scaled down by that exact
 * same ratio below it -- floored at [MimirRunicQuickRailDefaults.MinLabelFontSize] so a label never
 * shrinks past the type scale's own lowest step. One shared ratio across every seat, not a per-slot one:
 * before the 2026-08-21 equal-seat override, QUICK LAUNCH/FILES kept differently floored slots than a
 * regular seat, and sizing each label off its OWN slot would have put three different label sizes on one
 * rail -- the exact "bolted-on special case" the fix must avoid. Every seat now shares one preferred
 * width too, so a per-slot ratio and this shared one agree by construction -- but the shared ratio stays
 * the mechanism, not a coincidence of the current widths. At the Odin (`railWidth == preferredWidth ==
 * 528dp`) this is an exact `>=`, the same early-return the coin relies on, so the label is untouched --
 * not merely close.
 */
internal fun mimirQuickRailLabelFontSize(railWidth: Dp, preferredWidth: Dp): TextUnit {
    val base = MimirTypeScale.RailAction.fontSize
    if (preferredWidth <= 0.dp || railWidth >= preferredWidth) return base
    val scale = railWidth.value / preferredWidth.value
    return maxOf(MimirRunicQuickRailDefaults.MinLabelFontSize.value, base.value * scale).sp
}

/**
 * Four icon-first final-shelf actions in a centered, fixed-width rail.
 *
 * Focus remains owned by the app's single focus authority: [actionModifier] is where a feature attaches
 * its physical focus registration, and [focusedAction] is the visual truth read back from that authority.
 * A touch on an unfocused action requests focus only; a later release on the focused action activates it.
 * Controller activation remains in the feature reducer, so animation can never delay input dispatch.
 */
@Composable
fun MimirRunicQuickRail(
    selectedAction: MimirQuickRailAction?,
    focusedAction: MimirQuickRailAction?,
    onActionFocus: (MimirQuickRailAction) -> Unit,
    onActionActivate: (MimirQuickRailAction) -> Unit,
    onActionTouch: ((MimirQuickRailAction, MimirQuickRailTouchPhase, Offset) -> Unit)? = null,
    modifier: Modifier = Modifier,
    actions: List<MimirQuickRailAction> = MimirQuickRailAction.entries,
    palette: MimirThemePalette = LocalMimirPalette.current,
    actionDiameter: Dp? = null,
    /** Localized label contrast when the rail sits directly over arbitrary light-theme media. */
    mediaContrastRail: Boolean = false,
    labelForAction: @Composable (MimirQuickRailAction) -> String = { stringResource(it.labelRes) },
    actionModifier: (MimirQuickRailAction) -> Modifier = { Modifier },
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(MimirRunicQuickRailDefaults.RailHeight),
        contentAlignment = Alignment.Center,
    ) {
        val resolvedDiameter = actionDiameter
            ?.coerceIn(
                MimirRunicQuickRailDefaults.MinActionDiameter,
                MimirRunicQuickRailDefaults.MaxActionDiameter,
            )
            ?: mimirQuickRailDiameter(maxWidth)
        // Policy 2026-08-19/20: the rail's TARGET narrows before it physically has to, not only
        // once it can no longer fit -- see mimirQuickRailTargetWidth's own doc. Below the preferred
        // authored width, scale every seat proportionally. This preserves QUICK LAUNCH's extra breathing
        // room without allowing a trailing focusable action to clip away.
        // a seat the host withdraws (the Files end-cap while a game runs) is drawn until its fade is done, at its
        // presence share of its slot, and the others close over it. `shown` and `presences` are read HERE, in
        // composition, on purpose: the seat list itself is changing, which recomposes the rail anyway, and every width below derives from
        // them. Ten frames on FocusMs once per game launch and return; a focus move still recomposes nothing per frame.
        val seats = rememberQuickRailSeats(actions)
        val shown = seats.shown
        val presences = seats.presences
        val preferredWidth = mimirQuickRailPreferredRailWidth(shown, presences)
        val railEndReserve = if (LocalMimirViewportProfile.current.isCompact) {
            MimirRunicQuickRailDefaults.CompactRailEndReserve
        } else {
            MimirRunicQuickRailDefaults.RailEndReserve
        }
        val slotWidths = mimirQuickRailSlotWidths(shown, presences, mimirQuickRailTargetWidth(maxWidth, railEndReserve))
        val seatWidths = slotWidths.mapIndexed { index, slot -> slot * presences[index] }
        val railWidth = seatWidths.fold(0.dp) { width, seat -> width + seat }
        // The label must shrink WITH the rail exactly as the coin below does -- same railWidth/
        // preferredWidth pair, same guard, so the Odin's label is untouched by the same exact `>=` the
        // coin relies on. See mimirQuickRailLabelFontSize's own doc for the floor and why it is one
        // shared ratio across every seat rather than a per-slot one.
        val labelFontSize = mimirQuickRailLabelFontSize(railWidth, preferredWidth)
        // The coin itself must shrink with the rail, or a narrowed slot squeezes a still-full-size
        // circle into an oval (Modifier.size on the paint envelope below gets coerced to the incoming
        // slot width, but the visible circle inside it does not shrink to match on its own). Guarded on
        // railWidth, not divided unconditionally: at/above the preferred width this is an exact `>=`
        // comparison against the SAME Dp values mimirQuickRailSlotWidths's own early return hands back,
        // so resolvedDiameter passes through with no float op at all -- the Odin's coin stays literally
        // untouched. FILES is unaffected either way: mimirQuickRailItemDiameter always overrides it to
        // MinActionDiameter regardless of what is passed in.
        val scaledDiameter = if (railWidth >= preferredWidth) {
            resolvedDiameter
        } else {
            maxOf(
                MimirRunicQuickRailDefaults.MinActionDiameter,
                resolvedDiameter * (railWidth.value / preferredWidth.value),
            )
        }

        // The molten plinth the rune-coins stand on: the theme's own crest with its bright core over it,
        // static by contract (only the HEADER rule animates, and only on Surtr/Niflheim). It sits on the
        // coin/label seam inside the fixed 86dp envelope, so rail geometry is untouched.
        val plinthArt = remember(palette.id) { MimirShelfArt.moltenSeparatorFor(palette.id) }
        val plinthCrest = remember(palette.id) {
            Brush.horizontalGradient(*plinthArt.crestStops.toTypedArray())
        }
        val plinthCore = remember(palette.id) {
            Brush.horizontalGradient(*plinthArt.coreStops.toTypedArray())
        }
        // ONE focus ring for the whole dock, gliding from seat to seat. The seats no longer each light a disc on arrival; this ring travels instead.
        // Every value is read in the DRAW phase of the layer below, so a focus move never recomposes the rail
        // per frame, and at rest nothing animates (idle dock = 0 frames).
        val density = LocalDensity.current
        val reducedMotion = mimirReducedMotion()
        // The centres of the seats as DRAWN (a leaving seat's centre closes with it), so the ring and the media discs sit on what is painted.
        val seatCentresPx = remember(seatWidths, density) {
            with(density) {
                seatWidths.runningFold(0f) { start, seat -> start + seat.toPx() }
                    .zipWithNext { start, end -> (start + end) / 2f }
            }
        }
        val coinDiametersPx = remember(shown, scaledDiameter, density) {
            with(density) { shown.map { action -> mimirQuickRailItemDiameter(action, scaledDiameter).toPx() } }
        }
        val ringDiametersPx = remember(coinDiametersPx) {
            coinDiametersPx.map { coin -> coin * MimirRunicQuickRailDefaults.FocusRingCoinRatio }
        }
        val focusedIndex = focusedAction?.let(shown::indexOf)?.takeIf { it >= 0 }
        val focusedSeat = focusedIndex?.let(shown::get)
        val ringCentre = remember { Animatable(0f) }
        val ringDiameter = remember { Animatable(0f) }
        val ringPresence = remember { Animatable(0f) }
        // The effect below is keyed on the SEAT alone, never on the geometry: a neighbour leaving or returning moves the geometry under a lit
        // seat every frame of its fade (the stayers recentre, and at a scaled width they widen), and an effect keyed on it restarted
        // each frame and cut a glide in flight to a snap. Instead the effect records what it aimed at
        // ([QuickRailRingAim]) and the draw adds the difference between that and the seat's LIVE geometry, read through [liveGeometry], so
        // the ring follows its seat frame for frame while a glide already running keeps running.
        val ringAim = remember { QuickRailRingAim() }
        val liveGeometry = rememberUpdatedState(seatCentresPx to ringDiametersPx)
        LaunchedEffect(focusedSeat) {
            val seat = focusedSeat
            if (seat == null) {
                // Departure keeps the house ease: a fade-out reads as polish, never as latency (BM02).
                ringPresence.animateTo(0f, quickRailRingDepartureSpec())
                return@LaunchedEffect
            }
            val (centres, diameters) = liveGeometry.value
            val index = shown.indexOf(seat)
            val centre = centres[index]
            val diameter = diameters[index]
            ringAim.seat = seat
            ringAim.centre = centre
            ringAim.diameter = diameter
            if (mimirQuickRailRingSnaps(presence = ringPresence.value, reducedMotion = reducedMotion)) {
                // Arrival from the shelf lands ON the seat (BM02: arrival snaps), and reduced motion never glides.
                ringCentre.snapTo(centre)
                ringDiameter.snapTo(diameter)
                ringPresence.snapTo(1f)
            } else {
                ringPresence.snapTo(1f)
                coroutineScope {
                    val glide = quickRailGlideSpec()
                    launch { ringCentre.animateTo(centre, glide) }
                    ringDiameter.animateTo(diameter, glide)
                }
            }
        }
        // The TILE ring's token, not the chrome focusRing: the dock's ring is the shelves' ring, so a theme that
        // tints it (Baldr's azure, Folkvangr's rose) and a player's appearance override reach the dock too.
        val ringColor = palette.tileFocusRing
        val washColor = palette.accent.copy(alpha = MimirRunicQuickRailDefaults.FocusWashAlpha)
        // Over light media a bare mark would sit on arbitrary art, so each seat keeps a quiet disc there: the
        // controlBottom-at-0.84 body and textSecondary hairline the Home count pill uses over media. It is drawn
        // in this layer, UNDER the ring, so it can never cover the focus it sits beside.
        val mediaDisc = palette.materials.controlBottom.copy(alpha = 0.84f)
        val mediaDiscRim = palette.textSecondary.copy(alpha = 0.18f)

        val mediaLabelRail = remember(palette) {
            Brush.verticalGradient(
                listOf(
                    palette.materials.controlTop.copy(alpha = 0.92f),
                    palette.materials.controlBottom.copy(alpha = 0.92f),
                ),
            )
        }

        Box(
            modifier = Modifier
                .width(railWidth)
                .height(MimirRunicQuickRailDefaults.RailHeight)
                .drawWithCache {
                    // Built once at the largest seat's ring and scaled to the seat it is on, so a glide draws
                    // every frame with zero allocations (Thor is GC-sensitive on the D-pad focus path).
                    val referenceDiameter = ringDiametersPx.maxOrNull() ?: 0f
                    val paint = mimirFocusTileRingPaint(
                        color = ringColor,
                        cornerRadius = (referenceDiameter / 2f).toDp(),
                        elementSize = Size(referenceDiameter, referenceDiameter),
                    )
                    val centreY = MimirRunicQuickRailDefaults.ActionBoxDiameter.toPx() / 2f
                    val rimStroke = Stroke(width = MimirSpacing.hairlineWidth.toPx())
                    onDrawBehind {
                        // Order is the point: media discs, then the wash, then the ring. The marks draw above.
                        if (mediaContrastRail) {
                            for (seat in seatCentresPx.indices) {
                                val seatCentre = Offset(seatCentresPx[seat], centreY)
                                val coinRadius = coinDiametersPx[seat] / 2f
                                drawCircle(color = mediaDisc, radius = coinRadius, center = seatCentre)
                                drawCircle(
                                    color = mediaDiscRim,
                                    radius = coinRadius - rimStroke.width / 2f,
                                    center = seatCentre,
                                    style = rimStroke,
                                )
                            }
                        }
                        val presence = ringPresence.value
                        if (presence <= 0f || referenceDiameter <= 0f) return@onDrawBehind
                        // The seat may have moved since the effect aimed at it (a neighbour leaving or returning): follow it here, so the
                        // effect never has to restart for geometry. Only while the lit seat is the one aimed at; between a focus change and
                        // the effect's first step the aim is still the old seat's, and the ring stays where it is for that frame.
                        val lit = focusedIndex
                        val follows = lit != null && shown[lit] == ringAim.seat
                        val diameter = ringDiameter.value + if (follows) ringDiametersPx[lit!!] - ringAim.diameter else 0f
                        val centreX = ringCentre.value + if (follows) seatCentresPx[lit!!] - ringAim.centre else 0f
                        drawCircle(
                            color = washColor,
                            radius = diameter / 2f,
                            center = Offset(centreX, centreY),
                            alpha = presence,
                        )
                        val ringScale = diameter / referenceDiameter
                        withTransform({
                            translate(left = centreX - diameter / 2f, top = centreY - diameter / 2f)
                            scale(scaleX = ringScale, scaleY = ringScale, pivot = Offset.Zero)
                        }) {
                            drawMimirFocusTileRing(paint, presence)
                        }
                    }
                },
        )

        Row(
            modifier = Modifier
                .width(railWidth)
                .height(MimirRunicQuickRailDefaults.RailHeight)
                .drawBehind {
                    val ledgeY = MimirRunicQuickRailDefaults.ActionBoxDiameter.toPx()
                    val crestHeight = 1.5.dp.toPx()
                    val glowHeight = 5.dp.toPx()
                    if (mediaContrastRail) {
                        // Keep this a label rail, not a second dock: inset it below and inside the molten
                        // separator so neither edge visually collides with the rune-coin plinth.
                        val railInsetX = 8.dp.toPx()
                        val railTop = ledgeY + 4.dp.toPx()
                        val railSize = Size(
                            width = (size.width - railInsetX * 2f).coerceAtLeast(0f),
                            height = (size.height - railTop - 2.dp.toPx()).coerceAtLeast(0f),
                        )
                        drawRoundRect(
                            brush = mediaLabelRail,
                            topLeft = Offset(railInsetX, railTop),
                            size = railSize,
                            cornerRadius = CornerRadius(8.dp.toPx()),
                        )
                        drawRoundRect(
                            color = palette.textSecondary.copy(alpha = 0.12f),
                            topLeft = Offset(railInsetX, railTop),
                            size = railSize,
                            cornerRadius = CornerRadius(8.dp.toPx()),
                            style = Stroke(width = MimirSpacing.hairlineWidth.toPx()),
                        )
                    }
                    if (mimirQuickRailShowsMoltenSeparator(palette)) {
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(Color.Transparent, plinthArt.floor.copy(alpha = 0.16f)),
                                startY = ledgeY - glowHeight,
                                endY = ledgeY,
                            ),
                            topLeft = Offset(0f, ledgeY - glowHeight),
                            size = Size(size.width, glowHeight),
                        )
                        val crestTop = ledgeY - crestHeight / 2f
                        drawRect(
                            brush = plinthCrest,
                            topLeft = Offset(0f, crestTop),
                            size = Size(size.width, crestHeight),
                            alpha = 0.85f,
                        )
                        drawRect(
                            brush = plinthCore,
                            topLeft = Offset(0f, crestTop),
                            size = Size(size.width, crestHeight),
                            alpha = 0.45f,
                        )
                    } else {
                        val railInsetX = 8.dp.toPx()
                        drawLine(
                            color = palette.materials.contrastLine,
                            start = Offset(railInsetX, ledgeY),
                            end = Offset((size.width - railInsetX).coerceAtLeast(railInsetX), ledgeY),
                            strokeWidth = MimirSpacing.hairlineWidth.toPx(),
                        )
                    }
                },
            verticalAlignment = Alignment.Top,
        ) {
            // V-4, 2026-08-14: content identity, not slot. **A live case, not a precaution** -- this
            // parameter defaults to `MimirQuickRailAction.entries` but `AppShelfQuickRail.kt:43` passes
            // `libraryQuickRailActions(homeEnabled)`, so the rail GAINS AND LOSES an entry when Home is
            // toggled and every action behind the change shifts a slot. Unkeyed, the focused item's
            // `animateFloatAsState` (inside MimirRunicQuickRailItem) was inherited by whichever action
            // took its position and animated out under it. The rail sits on the Home shelf, which is
            // See FileBrowserGhostFreeIdentity for the rule. `shown` is `actions` plus the seats still
            // fading out; a leaving seat keeps its key, fades in place and is inert: it answers no tap and no touch observer.
            shown.forEachIndexed { index, action ->
                key(action) {
                    val offered = action in actions
                    MimirRunicQuickRailItem(
                        action = action,
                        label = labelForAction(action),
                        selected = selectedAction == action,
                        focused = focusedAction == action,
                        diameter = mimirQuickRailItemDiameter(action, scaledDiameter),
                        slotWidth = slotWidths[index],
                        presence = presences[index],
                        labelFontSize = labelFontSize,
                        palette = palette,
                        mediaContrastRail = mediaContrastRail,
                        focusModifier = actionModifier(action),
                        onRequestFocus = { if (offered) onActionFocus(action) },
                        onActivate = { if (offered) onActionActivate(action) },
                        onTouch = onActionTouch?.takeIf { offered }?.let { observer ->
                            { phase, offset -> observer(action, phase, offset) }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MimirRunicQuickRailItem(
    action: MimirQuickRailAction,
    label: String,
    selected: Boolean,
    focused: Boolean,
    diameter: Dp,
    slotWidth: Dp,
    /** 1 at rest; below it the seat is leaving or returning and is drawn at this share of [slotWidth], faded to match. */
    presence: Float,
    labelFontSize: TextUnit,
    palette: MimirThemePalette,
    mediaContrastRail: Boolean,
    focusModifier: Modifier,
    onRequestFocus: () -> Unit,
    onActivate: () -> Unit,
    onTouch: ((MimirQuickRailTouchPhase, Offset) -> Unit)?,
) {
    val emphasis = mimirQuickRailEmphasis(selected = selected, focused = focused)
    // The seat paints no disc, no rim, no outline
    // and no sweep any more -- six filled coins in a row were the clutter. Focus is the rail's ONE gliding ring
    // (MimirRunicQuickRail), and the mark itself carries the state: bright when focused, the accent when it is
    // the current destination, quiet otherwise. Every animated value below is read in the DRAW or LAYER phase.
    val runeColor = animateColorAsState(
        targetValue = when {
            focused -> mimirQuickRailFocusedRuneInk(palette)
            emphasis.usesDestinationInk -> palette.accentBright
            else -> palette.textSecondary
        },
        animationSpec = quickRailColorSpec(arriving = focused),
        label = "quickRailRune",
    )
    val focusScale = animateFloatAsState(
        targetValue = if (focused) MimirRunicQuickRailDefaults.FocusedScale else 1f,
        animationSpec = quickRailFloatSpec(arriving = focused),
        label = "quickRailScale",
    )
    val labelAlpha = animateFloatAsState(
        targetValue = if (focused || mediaContrastRail) 1f else 0.55f,
        animationSpec = quickRailFloatSpec(arriving = focused),
        label = "quickRailLabel",
    )
    // The gesture node is keyed on `Unit` and reads its target through `rememberUpdatedState`, NOT keyed
    // on the lambda: `pointerInput`'s keys are a RESTART contract, so a key that changes while a finger is
    // down disposes the detector and eats the tap. Same reasoning as `Modifier.mimirActivatable`'s own
    // gesture-node-lifetime KDoc (core-input) -- see the block comment on the modifier chain below for why
    // this file reproduces that primitive's shape instead of calling it.
    val currentActivate by rememberUpdatedState(onActivate)
    val currentTouchObserver = onTouch?.let { rememberUpdatedState(it) }

    Column(
        // the TAP TARGET is the whole slot (glyph + label band),
        // not the inner 48-60dp visual circle — several physical rail taps were landing in the dead margin
        // between circles. Visuals (ring, fill, scale) stay on the circle; only the hitbox widened.
        //
        // RC-52 investigation, 2026-08-22 (Odin `test device`, frontend `08bd06abfe37a9cc…`): this
        // was `Modifier.clickable` + a SEPARATE `.semantics` block, and the device accessibility tree
        // showed what that costs. Each seat emitted THREE nodes at identical bounds
        // (`[351,857][554,1055]`): one `clickable=true focusable=TRUE` with no name, one named `"Home"`
        // with `clickable=false selected=false`, and a bare `android.widget.Button`. Two real defects in
        // that dump:
        //  - `focusable=true` is `clickable`'s `AbstractClickableNode` building a `FocusableNode` — a
        //    SECOND Compose focus target on the product's most controller-navigated surface, beside the
        //    `mimirFocusable` registration on the coin below. Invariant 2 (CONTRIBUTING.md). The same dump shows
        //    `mimirActivatable`-converted Settings surfaces at `focusable=FALSE`, so this is the
        //    modifier's signature, not the platform's.
        //  - the NAMED node was not the ACTIONABLE node, and `selected` never reached it — so TalkBack
        //    lands on a label with no action and cannot say which dock destination is current, and any
        //    automation driver resolving a seat by `content-desc` gets an inert node. `MimirActivatable.kt`
        //    documents that split as the exact failure a hurried migration drops.
        // The cure is the sanctioned primitive `Modifier.mimirActivatable` (core-input, 2026-08-15), whose
        // shape is reproduced inline here: `core-ui` declares NO `project()` dependency by design ("Depends
        // on NOTHING but Compose + core-model", core-ui/build.gradle.kts:3) and adding a `core-ui ->
        // core-input` edge to reach one modifier is a module-contract change, not this lane's to make. The
        // three parts that must stay together are the ones that primitive's KDoc calls load-bearing: the
        // Unit-keyed gesture node above, ONE merged semantics block below (role + name + `selected` + the
        // click action, so they cannot separate again), and no `onDoubleTap` (registering one puts the
        // ~300ms platform double-tap timeout in front of every single tap).
        modifier = Modifier
            .quickRailSeatWidth(slotWidth, presence)
            .height(MimirRunicQuickRailDefaults.RailHeight)
            .pointerInput(Unit) {
                // Policy: touch acts like a physical input -- ONE tap, ONE event, always.
                // Never onRequestFocus() from touch: this item does not branch on `focused` any more,
                // so it can never push a focus/scope request only to have the caller's onActivate pop
                // it straight back off on the SAME tap (the LIBRARY/HOME toggle's dead-tap trap --
                // AppShelfQuickRail.kt / HomeShelfModels.kt already key that toggle off the CURRENT
                // scope, so a direct onActivate() alone reaches the correct decision). core-ui still
                // knows nothing about scopes; onActionFocus is unused by touch now but kept wired for
                // any other caller (pointer/hover, controller).
                if (currentTouchObserver == null) {
                    detectTapGestures(onTap = { currentActivate() })
                } else {
                    detectTapGestures(
                        onPress = { position ->
                            currentTouchObserver.value(MimirQuickRailTouchPhase.DOWN, position)
                            val released = tryAwaitRelease()
                            currentTouchObserver.value(
                                if (released) MimirQuickRailTouchPhase.UP else MimirQuickRailTouchPhase.CANCEL,
                                position,
                            )
                        },
                        onTap = { position ->
                            currentTouchObserver.value(MimirQuickRailTouchPhase.TAP, position)
                            currentActivate()
                        },
                    )
                }
            }
            .semantics(mergeDescendants = true) {
                this.role = Role.Button
                contentDescription = label
                this.selected = selected
                // ONE activation path for the finger and for the accessibility service, so the two can
                // never drift into different meanings of "activate".
                onClick(label = label) {
                    currentActivate()
                    true
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.size(MimirRunicQuickRailDefaults.ActionBoxDiameter),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(diameter)
                    .graphicsLayer {
                        scaleX = focusScale.value
                        scaleY = focusScale.value
                    }
                    .then(focusModifier),
                contentAlignment = Alignment.Center,
            ) {
                if (MimirDockUsesDrawnMarks) {
                    // ONE ink, deliberately, for this comparison. The set's own rule is two — an
                    // outline plus a filled cell that carries state — but lighting the cell here would
                    // change TWO things at once against the glyph it replaces, and then the photograph
                    // could not say which one the eye is reacting to. The mark's SHAPE is the question.
                    // Drawn on a Canvas, not through MimirIconMark with the colour as an argument: that read the animated colour in COMPOSITION, so every
                    // focus move recomposed the seat for the whole 140 ms colour tween (audit 2026-10-14, F-03 (2)). The same drawing, the same size and the
                    // same semantics (none), with the colour read where it is painted.
                    Canvas(
                        modifier = Modifier
                            .clearAndSetSemantics { }
                            .size(MimirRunicQuickRailDefaults.DrawnMarkSize),
                    ) {
                        drawMimirIcon(action.dockMark, runeColor.value, runeColor.value)
                    }
                } else {
                    BasicText(
                        text = action.rune,
                        style = MimirTypeScale.RuneAction,
                        color = { runeColor.value },
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(MimirRunicQuickRailDefaults.LabelBandHeight),
            contentAlignment = Alignment.Center,
        ) {
            // At large accessibility font scales the longest label ("Settings") outgrows the fixed slot;
            // cap the label's effective scale at 1.1 and drop tracking instead of ellipsizing — a
            // truncated persistent label defeats its whole discoverability purpose. This composes with
            // [labelFontSize] (the rail-narrowing floor computed once in MimirRunicQuickRail): it only
            // ever DAMPENS growth past 1x baseline, so it can never push a label's rendered size below
            // labelFontSize's own physical size, whatever labelFontSize already is.
            val fontScale = LocalDensity.current.fontScale
            val baseLabelStyle = MimirTypeScale.RailAction
            val labelStyle = if (fontScale > MaxLabelFontScale) {
                baseLabelStyle.copy(
                    fontSize = labelFontSize * (MaxLabelFontScale / fontScale),
                    letterSpacing = 0.sp,
                )
            } else {
                baseLabelStyle.copy(fontSize = labelFontSize)
            }
            Text(
                text = mimirStaticChrome(label),
                style = labelStyle,
                color = if (focused) palette.textPrimary else palette.textSecondary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .graphicsLayer { alpha = labelAlpha.value }
                    .clearAndSetSemantics { },
            )
        }
    }
}

/**
 * The seat's width while it leaves or returns: its content is measured at the full [slotWidth] and placed centred, faded to
 * [presence] and clipped to the [presence] share of the slot, so the coin never squashes into an oval (`Modifier.size` yields to a narrower
 * incoming width) and the neighbours close over it as it goes. At presence 1 this is the `width(slotWidth)` it always was.
 */
private fun Modifier.quickRailSeatWidth(slotWidth: Dp, presence: Float): Modifier =
    if (presence >= 1f) {
        width(slotWidth)
    } else {
        graphicsLayer {
            alpha = presence
            clip = true
        }.layout { measurable, constraints ->
            val full = slotWidth.roundToPx()
            val placeable = measurable.measure(constraints.copy(minWidth = full, maxWidth = full))
            val drawn = (full * presence).roundToInt()
            layout(drawn, placeable.height) { placeable.placeRelative(-(full - drawn) / 2, 0) }
        }
    }

/** What the ring effect last aimed at: the seat, and its centre and ring diameter in px at that moment. Written by the effect, read by the draw. */
private class QuickRailRingAim {
    var seat: MimirQuickRailAction? = null
    var centre = 0f
    var diameter = 0f
}

/**
 * The seats the rail draws ([shown]: the offered ones and any still leaving) with each one's presence, 0..1. A presence animates on
 * [quickRailSeatSpec] towards 1 for an offered seat and 0 for a withdrawn one, from wherever it is, so a seat withdrawn and offered again
 * inside one fade turns back without a cut; reduced motion snaps. A seat whose fade reached 0 leaves [shown].
 */
private class QuickRailSeats(initial: List<MimirQuickRailAction>) {
    var shown: List<MimirQuickRailAction> by mutableStateOf(initial)
        private set
    private val presence = initial.associateWith { Animatable(1f) }.toMutableMap()

    val presences: List<Float> get() = shown.map { seat -> presence[seat]?.value ?: 1f }

    suspend fun settle(actions: List<MimirQuickRailAction>, reducedMotion: Boolean) {
        val next = mimirQuickRailSeatsShown(shown, actions)
        shown = next
        coroutineScope {
            for (seat in next) {
                val target = if (seat in actions) 1f else 0f
                val animatable = presence.getOrPut(seat) { Animatable(0f) }
                if (animatable.value == target) continue
                launch {
                    if (reducedMotion) animatable.snapTo(target) else animatable.animateTo(target, quickRailSeatSpec())
                    if (target == 0f) shown = shown - seat
                }
            }
        }
    }
}

@Composable
private fun rememberQuickRailSeats(actions: List<MimirQuickRailAction>): QuickRailSeats {
    val seats = remember { QuickRailSeats(actions) }
    val reducedMotion = mimirReducedMotion()
    LaunchedEffect(actions, reducedMotion) { seats.settle(actions, reducedMotion) }
    return seats
}

/** A seat leaving or returning, and the dock recentring over it: the focus clock with the house ease, as the seat's own ink and scale. */
private fun quickRailSeatSpec() = tween<Float>(durationMillis = MimirMotion.FocusMs, easing = MimirMotion.Easing)

/**
 * The focused mark's ink. Before 2026-09-27 the focused seat was a solid molten disc, and this
 * was the dark ink that survived it (onAccent, or the hero cover's onCover on light themes). The seat paints no
 * disc now: the focused mark sits on the dock's own field under the ring's faint accent wash
 * ([MimirRunicQuickRailDefaults.FocusWashAlpha]), so it takes the field's primary ink, the brightest thing on
 * the rail on a dark theme and the darkest on a light one.
 */
fun mimirQuickRailFocusedRuneInk(palette: MimirThemePalette): Color = palette.textPrimary

private const val MaxLabelFontScale = 1.1f

/** The gliding ring's size for a seat whose coin is [coinDiameter]; see [MimirRunicQuickRailDefaults.FocusRingCoinRatio]. */
internal fun mimirQuickRailFocusRingDiameter(coinDiameter: Dp): Dp =
    coinDiameter * MimirRunicQuickRailDefaults.FocusRingCoinRatio

/**
 * Whether the ring lands on its seat instead of gliding there: when it is not on the dock at all ([presence]
 * zero, i.e. focus is arriving from the shelf -- BM02, arrival snaps) and whenever the system asks for reduced
 * motion. A ring still fading out from a departure glides back from where it is.
 */
internal fun mimirQuickRailRingSnaps(presence: Float, reducedMotion: Boolean): Boolean =
    reducedMotion || presence <= 0f

/**
 * The ring's seat-to-seat travel. An ease-OUT, deliberately not the house [MimirMotion.Easing]: Measurement found
 * that curve's slow start caused perceived input lag on this dock.
 * This one leaves the old seat at full speed on the frame the press lands and settles into the new one, and a
 * held pad retargets it in flight rather than queueing steps. On the ring clock at the product-wide 2x duration: [mimirFocusRingMs], so a held pad still glides at the focus response.
 */
private fun quickRailGlideSpec() = tween<Float>(durationMillis = mimirFocusRingMs(), easing = LinearOutSlowInEasing)

/** The ring fading out as focus leaves the dock, on the ring clock; the mark's scale and label keep [quickRailFloatSpec]. */
private fun quickRailRingDepartureSpec() = tween<Float>(durationMillis = mimirFocusRingMs(), easing = MimirMotion.Easing)

/**
 * Shelf-to-dock response: focus ARRIVAL snaps —
 * the 140ms slow-start ease left the fill barely visible for its first ~60ms, which read as input lag
 * against the shelf's snap-grammar ring. FUX-D allowed the dock ONE transient; since 2026-09-27
 * that is the ring's seat-to-seat glide (it replaced the per-seat specular sweep), so the mark's own ink and
 * scale still snap on arrival. DEPARTURE keeps the house ease (a fade-out reads as polish, never as latency).
 */
private fun quickRailColorSpec(arriving: Boolean): androidx.compose.animation.core.AnimationSpec<Color> =
    if (arriving) snap() else tween(durationMillis = MimirMotion.FocusMs, easing = MimirMotion.Easing)

private fun quickRailFloatSpec(arriving: Boolean): androidx.compose.animation.core.AnimationSpec<Float> =
    if (arriving) snap() else tween(durationMillis = MimirMotion.FocusMs, easing = MimirMotion.Easing)
