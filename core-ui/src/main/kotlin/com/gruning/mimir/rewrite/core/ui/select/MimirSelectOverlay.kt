package com.gruning.mimir.rewrite.core.ui.select

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.input.FocusState
import com.gruning.mimir.rewrite.core.input.isLitFocusIn
import com.gruning.mimir.rewrite.core.input.isRenderedFocusIn
import com.gruning.mimir.rewrite.core.input.mimirFocusable
import com.gruning.mimir.rewrite.core.ui.mimirModalWidthCapDp
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirBeforeFirstValidViewport
import com.gruning.mimir.rewrite.core.ui.MimirFocusTileRingReach
import com.gruning.mimir.rewrite.core.ui.BeamSeparator
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.LocalMimirControllerHintLabels
import com.gruning.mimir.rewrite.core.ui.MimirFooterKeycap
import com.gruning.mimir.rewrite.core.ui.MimirIntentText
import com.gruning.mimir.rewrite.core.ui.MimirLiquidGlassPanel
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirRingShape
import com.gruning.mimir.rewrite.core.ui.MimirTextIntent
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.glassRestingOutline
import com.gruning.mimir.rewrite.core.ui.mimirGlassScrimColor
import com.gruning.mimir.rewrite.core.ui.mimirSelectionRing
import com.gruning.mimir.rewrite.core.ui.mimirMeasuredViewport
import com.gruning.mimir.rewrite.core.ui.mimirSurfaceShadow
import com.gruning.mimir.rewrite.core.ui.rememberMeasuredViewport
import com.gruning.mimir.rewrite.core.ui.theme.MimirColors
import com.gruning.mimir.rewrite.core.ui.theme.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.theme.MimirType

/**
 * MIMIR-REWRITE — the ONE reusable "Choose an option" overlay (shipping `.select-overlay` / `.select-dialog`).
 * Ports the shipping select model 1:1: a dim-scrim modal with a title + `n / N` counter, a scrollable column of
 * single-line options (the current one bold + ✓, the focused one a themed fill + ring + a left accent bar with
 * NO scale — the shipping "focus is a cheap colour cross-fade, never a heave" rule), optional gold group headers,
 * dim non-committable rows for unavailable options, and an `A Select · B Cancel` footer.
 *
 * The shared shell behind the backdrop / per-platform-emulator / per-game Open-With pickers (the theme
 * picker moved on to its own preview GALLERY —). Each caller now just
 * builds a [MimirSelectItem] list and routes input through [dispatchMimirSelect] — one shell, one nav contract.
 *
 * FOCUS/POSTURE: rows register in the caller's modal [FocusScope]; only enabled options carry [mimirFocusable]
 * so D-pad nav + the counter skip group heads and unavailable rows. A tap commits an enabled row (touch+gamepad
 * parity). No Compose `focusable()`. Colours read from [LocalMimirPalette] (per-theme focus) except the group
 * head, which is fixed gold per the color system.
 */
/**
 * The minimum height of a committable select row. Android's minimum touch target — these rows launch
 * games, so this is a floor the layout may not trade away for density.
 */
internal val MimirSelectRowMinHeight = 48.dp

/**
 * This overlay's panel radius — 26dp, softer than the shared [MimirGlass.panelRadius] (20dp).
 *
 *  Passed per-call through
 * `MimirLiquidGlassPanel`'s existing `radius` parameter, so the Utility rail, the Home title editor and
 * every other glass surface keep 20dp. ONE constant drives both the clip and the shadow silhouette.
 */
internal val MimirSelectPanelRadius = 26.dp

/**
 * The authored width cap for this overlay — 480dp, down from 600dp.
 *
 *  This is the ODIN-only half of that: the Nova is a 640dp
 * viewport, below `SettingsNarrowThresholdWidthDp` (680), so `settingsModalWidthCapDp` already clamps it
 * to its detail-pane width (438dp) and `min(480, 438)` leaves it exactly where it was.
 *
 * **Known risk, named rather than discovered later:** no row in this file sets `maxLines`, and rows use
 * `heightIn(min=)`. A label longer than the narrower interior REFLOWS to a second line and the row grows
 * — so narrowing can make a menu TALLER. Open-With's emulator names and Backdrop's full-sentence
 * subtitles are the two that reach that first, and Open-With is the one to look at on device.
 */
internal const val MimirSelectPanelWidthCapDp = 480f
const val MimirSelectOptionsTestTag = "mimir_select_options"

/** The panel's body, inside the shadow gutter: what the footer must sit wholly inside. */
const val MimirSelectPanelTestTag = "mimir_select_panel"

/** The footer legend's row (the keycaps and their words). */
const val MimirSelectFooterTestTag = "mimir_select_footer"

sealed interface MimirSelectItem {
    /** A non-focusable section header (shipping `.select-group-head`) — gold, uppercase. */
    data class Group(val label: String) : MimirSelectItem

    /**
     * One muted, non-focusable line of plain information, in the style of a row's own [Option.subtitle] (dim support text),
     * with no fill, ring or chip. Not an option: the n / N counter and the D-pad skip it. Open-With uses it for the one
     * sentence that says no installed emulator lists the game's file.
     */
    data class Note(val text: String) : MimirSelectItem

    /**
     * One selectable row.
     * @param key stable focus key (the caller maps it back to its own choice on commit).
     * @param label the row text.
     * @param enabled false → dim italic, non-committable (shipping `.select-option-off`).
     * @param current the active choice → bold + ✓.
     * @param swatch optional leading accent dot (the theme picker's identity swatch).
     * @param subtitle optional second line under the label (dim) — e.g. an Open-With "· Needs install" reason
     *   or a backdrop option's one-line explanation. Null keeps the classic single-line row.
     * @param recommended when true, a small gold "Recommended" chip trails the label — the auto/default pick.
     * @param iconPackage optional Android package whose launcher icon draws as a leading app icon (the Open-With
     *   per-emulator icon). Decoded on-device from PackageManager; a not-installed / undecodable package silently
     *   falls back to no icon. All four extras are ADDITIVE + default-null/false, so the theme /
     *   per-platform-emulator / backdrop callers stay byte-identical.
     */
    data class Option(
        val key: FocusKey,
        val label: String,
        val labelIntent: MimirTextIntent = MimirTextIntent.Authored(label),
        val enabled: Boolean = true,
        val current: Boolean = false,
        val swatch: Color? = null,
        val subtitle: String? = null,
        val recommended: Boolean = false,
        val iconPackage: String? = null,
    ) : MimirSelectItem
}

private fun List<MimirSelectItem>.options(): List<MimirSelectItem.Option> = filterIsInstance<MimirSelectItem.Option>()

/** The default for [MimirSelectOverlay]'s `active`: a modal that is up whenever it is composed. One instance, so the parameter is stable. */
val MimirSelectAlwaysActive: () -> Boolean = { true }

/** The system Back, owned by the modal only while it is active; the state [active] reads is read here, so a flip re-runs this and nothing else. */
@Composable
private fun SelectBackHandler(active: () -> Boolean, onBack: () -> Unit) {
    BackHandler(enabled = active(), onBack = onBack)
}

@Composable
fun MimirSelectOverlay(
    authority: FocusAuthority,
    scope: FocusScope,
    title: String,
    titleIntent: MimirTextIntent = MimirTextIntent.StaticChrome(title),
    items: List<MimirSelectItem>,
    onSelect: (FocusKey) -> Unit,
    onDismiss: () -> Unit,
    /** True when an app/feature-owned pixel handoff already owns the modal's alpha timeline. */
    presenceManagedExternally: Boolean = false,
    /** Appearance pickers use centered, plain rows and the shared separator without its center node. */
    centeredPlainRows: Boolean = false,
    /**
     * Whether the modal owns input right now. A caller that keeps the sheet composed and measured BEFORE it opens (the Library's OPEN WITH, so that
     * pressing A does not build it on the key-down frame) hands a lambda that is false until the sheet is shown: the system Back goes to whoever is
     * behind it, and the first row is not seated, until it turns true. Read in the pieces that need it and never in this body, so the flip re-runs
     * nothing here. The default is always true, which is every other caller, unchanged.
     */
    active: () -> Boolean = MimirSelectAlwaysActive,
) {
    // The State, never unwrapped in this body: a focus move (and the one that seats the first row as the sheet opens) must reach only the rows whose answer
    // changed and the counter, not rebuild the whole panel (owperf, 2026-10-15).
    val focus = authority.state.collectAsState()
    val palette = LocalMimirPalette.current
    val options = items.options()

    // Fade the whole modal in on open (every picker menu gets a fade transition). The scrim
    // and card ease up together and the card lifts a hair from 0.97 scale — compositor-only (alpha/scale), fast.
    val appear = remember(presenceManagedExternally) {
        Animatable(if (presenceManagedExternally) 1f else 0f)
    }
    LaunchedEffect(presenceManagedExternally) {
        if (!presenceManagedExternally) {
            appear.animateTo(1f, tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing))
        }
    }

    // Land focus on the current row (else the first enabled one) once the rows have registered their bounds: each time the modal becomes active, from the
    // options as they are then (a sheet kept ready between openings has seen them change).
    val latestOptions = rememberUpdatedState(options)
    LaunchedEffect(Unit) {
        while (true) {
            snapshotFlow(active).first { it }
            withFrameNanos { }
            val rows = latestOptions.value
            val target = rows.firstOrNull { it.current && it.enabled }
                ?: rows.firstOrNull { it.enabled }
            target?.let { authority.requestFocus(it.key) }
            snapshotFlow(active).first { !it }
            // Back to waiting: a sheet that is kept composed between openings leaves nothing registered while it is not open (the rows register again when it is placed).
            latestOptions.value.forEach { authority.unregister(it.key) }
        }
    }

    // The `n / N` text, as a State read by the counter's own composable. Empty options draw no counter at all.
    val hasCounter = options.isNotEmpty()
    val counter = remember(authority, scope, items, focus) {
        derivedStateOf(structuralEqualityPolicy()) {
            val rows = items.options()
            val focusedIndex = rows.indexOfFirst { authority.isRenderedFocusIn(scope, it.key, focus.value) }
            "${(focusedIndex.coerceAtLeast(0)) + 1} / ${rows.size}"
        }
    }

    // INLINE full-screen overlay — NOT a Compose Dialog. A Dialog is a separate WINDOW that never receives the
    // app's routed controller input, so D-pad nav silently dies on-device (device-verified 2026-07-07: DPAD fell
    // through to the surface behind). Composed last by the caller in the app's own window, the shared input router
    // (`if (pickerOpen) dispatchMimirSelect`) reaches it, so NAV/CONFIRM/CANCEL work. Touch + gamepad parity.
    SelectBackHandler(active = active, onBack = onDismiss)
    run {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = appear.value } // fade the whole modal (scrim + card) in on open
                .background(mimirGlassScrimColor())
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
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
            // Modal width constraint: below the Nova threshold, on
            // the STANDARD rail+detail layout, this overlay can no longer render wider than the glass
            // section card it was opened from. 0.0dp of movement on the Odin, where 600dp was already
            // narrower than the 600.5dp card; a COMPACT_SQUARE viewport's full-bleed SettingsHome pane is a
            // different card width the rail/margin formula never describes, so it is untouched too.
            val modalCapDp = mimirModalWidthCapDp(
                viewport.width.value,
                viewport.height.value,
                authoredCapDp = MimirSelectPanelWidthCapDp,
            )
            MimirLiquidGlassPanel(
                modifier = Modifier
                    .mimirMeasuredViewport(viewport)
                    .wrapContentSize(Alignment.Center)
                    .widthIn(max = modalCapDp.dp)
                    .fillMaxWidth()
                    .graphicsLayer { val s = 0.97f + 0.03f * appear.value; scaleX = s; scaleY = s }
                    .padding(MimirSpacing.xl)
                    .testTag(MimirSelectPanelTestTag)
                    // 14dp -> the panel's OWN radius. The shadow used to be cast at `radiusCard` (14dp)
                    // under a body clipped at 20dp, so a squarer silhouette sat proud of a rounder panel.
                    // Real, but pre-existing since `a19ec017` and NOT what the regression report described -- fixed here because
                    // one constant should drive silhouette and clip, not because it was the complaint.
                    // Fixed at the CALL SITE, never the token: `MimirSpacing.radiusCard` has ~80 read sites,
                    // is pinned at 14f by another test, and `SelectRow` reads it for the ROW corners inside
                    // this same overlay -- moving the token would round every row while fixing the panel.
                    .mimirSurfaceShadow(palette, MimirSpacing.elevationModal, RoundedCornerShape(MimirSelectPanelRadius))
                    // The glass border is the panel's one edge. A gold hairline inset 7 dp from it formed a second
                    // outline with a gap, as did the older book corners. Retain the shared glass border alone.
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                // Inner panel padding tightens from xl to lg (24 -> 16dp). NOTE the outer
                // `.padding(MimirSpacing.xl)` above is NOT interior padding and is deliberately left
                // alone -- it is the gutter the 22dp elevation shadow draws into, so cutting it would
                // WIDEN the glass body, the opposite of the ask.
                radius = MimirSelectPanelRadius,
                gradientBody = false,
                contentPadding = MimirSpacing.lg,
                // md -> sm: a 1dp hairline rule does not need 12dp of air on both sides.
                verticalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
            ) {
                // Head — title + n / N counter (shipping `.select-head`).
                val staticTitle = titleIntent is MimirTextIntent.StaticChrome ||
                    titleIntent is MimirTextIntent.Mixed
                if (centeredPlainRows) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        MimirIntentText(
                            intent = titleIntent,
                            style = if (staticTitle) MimirType.RunicEyebrow else MimirType.OverlayTitle,
                            color = MimirColors.TextPrimary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = MimirSpacing.xl),
                            maxLines = if (staticTitle) 1 else 2,
                            softWrap = !staticTitle,
                            textAlign = TextAlign.Center,
                        )
                        if (hasCounter) {
                            SelectCounter(counter, Modifier.align(Alignment.CenterEnd))
                        }
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MimirIntentText(
                            intent = titleIntent,
                            style = if (staticTitle) MimirType.RunicEyebrow else MimirType.OverlayTitle,
                            color = MimirColors.TextPrimary,
                            modifier = Modifier.width(0.dp).weight(1f),
                            maxLines = if (staticTitle) 1 else 2,
                            softWrap = !staticTitle,
                        )
                        if (hasCounter) {
                            SelectCounter(counter)
                        }
                    }
                }
                // The head rule. Gold, hairline, full width -- it separates the title from
                // the options without spending a row, which is what a section head does everywhere else
                // in this product. Alpha rather than a solid line so it reads as an edge, not a divider
                // competing with the focused row's own fill.
                if (centeredPlainRows) {
                    BeamSeparator(modifier = Modifier.height(1.dp), showCenterNode = false)
                } else {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 1.dp)
                            .background(MimirColors.Gold.copy(alpha = 0.34f)),
                    )
                }
                // Options — scrollable list.
                Column(
                    Modifier
                        .fillMaxWidth()
                        // The list takes what the head, the rule and the footer leave (and no more than it needs): measured last, it scrolls above a footer that keeps its
                        // full height. Without the weight the list was measured before the footer and, taller than the panel (five rows at 1.3x on Main), took every
                        // remaining pixel, so the footer was laid out below the panel's bottom edge and cut in half (sweep 10-09).
                        .weight(1f, fill = false)
                        .testTag(MimirSelectOptionsTestTag)
                        // heightIn(max = 460.dp) removed here: dead configuration (RC-8 review) -- the
                        // incoming maxHeight from the modal's own contentPadding is ~352dp at a 480dp
                        // viewport, so a 460dp cap never bound on either device and clipped nothing.
                        .verticalScroll(rememberScrollState())
                        // A scroll host clips along its axis (spec §3.5): a ringed row's ring reaches
                        // MimirFocusTileRingReach past it, so that much sits INSIDE the scroll content or
                        // the first option (focused on open: Automatic / Play) loses its top stroke at
                        // scroll 0 and the last loses its bottom at max scroll. Centred-plain rows (the
                        // Accent and Backdrop pickers) wear the same ring since F11, so they hold the same reach.
                        .padding(vertical = MimirFocusTileRingReach),
                    // Siblings 8 dp apart (spec §3.3) so neighbouring rings never touch. A centred-plain
                    // picker puts a 1 dp beam between its rows, so 4 dp either side of the beam keeps rows
                    // 9 dp apart with the beam clear of the ring's strokes, without growing the list by 8 dp a gap.
                    verticalArrangement = Arrangement.spacedBy(if (centeredPlainRows) MimirSpacing.xs else MimirSpacing.sm),
                ) {
                    // If ANY row carries an app icon (the Open-With picker), every row reserves the leading icon
                    // slot so labels align to one edge and don't reflow as icons decode in. Icon-less pickers
                    // (theme / backdrop) leave it false and render exactly as before.
                    val reserveIconSlot = items.any { it is MimirSelectItem.Option && it.iconPackage != null }
                    items.forEachIndexed { index, item ->
                        if (centeredPlainRows && index > 0) {
                            BeamSeparator(modifier = Modifier.height(1.dp), showCenterNode = false)
                        }
                        when (item) {
                            is MimirSelectItem.Group -> MimirChromeText(
                                item.label,
                                style = MimirType.RunicEyebrow,
                                color = MimirColors.Gold,
                                modifier = Modifier.padding(start = MimirSpacing.md, top = MimirSpacing.sm, bottom = 2.dp),
                            )
                            // content identity, not slot -- the ghost-free identity rule
                            // (`FileBrowserGhostFreeIdentity`). Disclosed then as belt-and-braces: the four
                            // callers of that day (AccentPicker, BackdropPicker, PlatformEmulatorPicker,
                            // OpenWithPicker) hand a list whose ORDER and LENGTH are fixed for the life of
                            // one open, and the overlay is mounted per open. **There are five callers now,
                            // and the fifth is not belt-and-braces:** LaunchChoicePicker builds its rows from
                            // `members` on every composition, and while Home's launch card loads those once
                            // per open, the Library detail card collects `gameDetailMembers` live -- so that
                            // list can change while the menu is open, and without this key `SelectRow`'s
                            // focus ring would ghost onto whichever option took the slot.
                            //
                            // Keyed on `item.key` and NOT on `item`: the row's `current`/`enabled` fields
                            // change while it is open (accent preview retargets them), and keying on the
                            // whole data class would dispose the row on a state change and restart its
                            // ring animation -- trading a ghost nobody sees for a blink everybody does.
                            // The `Group` branch needs no key: it is one Text with no focus state, and
                            // the `when` already separates the two types into their own groups.
                            // `Note` is the same: one Text, no focus state. It sits on the rows' LABEL edge (the row's lg padding, then the
                            // 28 dp icon slot and its md gap when the rows carry icons) and wears the subtitle's style, so it reads as the
                            // same kind of line as the "Not installed" under a label, not as a second voice on its own margin.
                            is MimirSelectItem.Note -> Text(
                                item.text,
                                style = MimirType.RowSupport,
                                color = MimirColors.TextMuted,
                                modifier = Modifier.fillMaxWidth().padding(
                                    start = MimirSpacing.lg + if (reserveIconSlot) 28.dp + MimirSpacing.md else 0.dp,
                                    end = MimirSpacing.lg,
                                    // A little air above the first row's ring, whose reach already overlaps the Column's own gap.
                                    bottom = MimirSpacing.xs,
                                ),
                            )
                            is MimirSelectItem.Option -> key(item.key) {
                                SelectRowSeat(
                                    authority = authority,
                                    scope = scope,
                                    focus = focus,
                                    option = item,
                                    accent = palette.accent,
                                    accentBright = palette.accentBright,
                                    reserveIconSlot = reserveIconSlot,
                                    centeredPlain = centeredPlainRows,
                                    onSelect = onSelect,
                                )
                            }
                        }
                    }
                }
                MimirSelectFooter()
            }
        }
    }
}

/** The footer's two hints, as the shell's legend names them: the factory token (projected to the pad's own label) and its verb. */
private val MimirSelectFooterHints = listOf("A" to "Select", "B" to "Back")

/** The gap between the two hints, and between a keycap and its word: the shell legend's own (AppShellScaffold's hint row). */
private val MimirSelectFooterHintGap = 18.dp
private val MimirSelectFooterKeycapGap = 6.dp

/**
 * The legend under the options: `A Select`, `B Back`. Each key is the footer's one keycap, [MimirFooterKeycap] (every footer keycap is the etched one, not plain
 * text), with its word beside it in the shell legend's hint style; a key the applied controller map leaves unbound is not drawn, as the shell's legend does.
 */
@Composable
private fun MimirSelectFooter(modifier: Modifier = Modifier) {
    val palette = LocalMimirPalette.current
    val labels = LocalMimirControllerHintLabels.current
    Row(
        modifier = modifier.fillMaxWidth().testTag(MimirSelectFooterTestTag),
        horizontalArrangement = Arrangement.spacedBy(MimirSelectFooterHintGap, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for ((token, verb) in MimirSelectFooterHints) {
            val key = labels.labelFor(token)
            if (key.isBlank()) continue
            Row(
                horizontalArrangement = Arrangement.spacedBy(MimirSelectFooterKeycapGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MimirFooterKeycap(key = key, palette = palette)
                MimirChromeText(
                    text = verb,
                    style = MimirTypeScale.ControlHint,
                    color = palette.textMuted,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun SelectRow(
    option: MimirSelectItem.Option,
    focused: Boolean,
    accent: Color,
    accentBright: Color,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    reserveIconSlot: Boolean = false,
    centeredPlain: Boolean = false,
) {
    val palette = LocalMimirPalette.current
    val focusedInk = palette.materials.onScrimPrimary
    val labelColor = when {
        !option.enabled -> MimirColors.TextMuted
        focused -> focusedInk
        option.current -> MimirColors.TextPrimary
        else -> MimirColors.TextSecondary
    }
    // A first decode fades in from nothing over FocusMs (Q13); a disabled row is dimmed under the same fade.
    val appIconArt = rememberAppIcon(option.iconPackage)
    val appIcon = appIconArt.bitmap
    if (centeredPlain) {
        val indicatorSlot = 28.dp
        Box(
            modifier
                .fillMaxWidth()
                .heightIn(min = MimirSelectRowMinHeight)
                //  the one ring, as the ringed branch below wears it. The private radial
                // accent glow that stood here was the only focus mark in the Accent and Backdrop pickers and
                // read as faint or absent on device (nova-19, rg-rotate-5). The rows stay plain: no resting
                // hairline, the house wash under the label on focus, no scale.
                .mimirSelectionRing(
                    focused = focused,
                    identity = option.key,
                    shape = MimirRingShape.Row(MimirSpacing.radiusCard),
                )
                .clickable(enabled = option.enabled, onClick = onTap)
                .padding(horizontal = MimirSpacing.lg, vertical = MimirSpacing.xs),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .size(indicatorSlot),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    appIcon != null -> Image(
                        bitmap = appIcon,
                        contentDescription = null,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .graphicsLayer { alpha = appIconArt.alpha * (if (option.enabled) 1f else 0.45f) },
                    )
                    option.swatch != null -> Box(
                        Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(option.swatch)
                            .border(1.dp, MimirColors.Outline, CircleShape),
                    )
                    reserveIconSlot -> Spacer(Modifier.size(28.dp))
                }
            }
            Column(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(horizontal = indicatorSlot),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                val staticLabel = option.labelIntent is MimirTextIntent.StaticChrome ||
                    option.labelIntent is MimirTextIntent.Mixed
                MimirIntentText(
                    intent = option.labelIntent,
                    style = (if (staticLabel) MimirType.MenuAction else MimirType.RailItem).copy(
                        fontWeight = if (option.current) FontWeight.SemiBold else FontWeight.Normal,
                        fontStyle = if (option.enabled) FontStyle.Normal else FontStyle.Italic,
                    ),
                    color = labelColor,
                    textAlign = TextAlign.Center,
                )
                option.subtitle?.let { sub ->
                    Text(
                        sub,
                        style = MimirType.RowSupport,
                        color = MimirColors.TextMuted,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .size(indicatorSlot),
                contentAlignment = Alignment.Center,
            ) {
                if (option.current) {
                    Text(
                        "✓",
                        style = MimirType.RailItem,
                        color = if (focused) focusedInk else accentBright,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        return
    }
    val shape = RoundedCornerShape(MimirSpacing.radiusCard)
    Row(
        modifier
            .fillMaxWidth()
            // 52 -> 48.
            // 48dp is the floor, not a number picked to look right: it is Android's minimum touch target,
            // and these rows commit a launch on tap. A disabled row may be smaller because it cannot be
            // committed at all -- there is nothing to hit.
            .heightIn(min = if (option.enabled) MimirSelectRowMinHeight else 44.dp)
            // The one ring (ring migration, 2026-09-28): a row ring on the card corner over the house wash, with the
            // glass resting hairline fading out under the ring. That hairline fills an otherwise empty
            // unfocused 48dp row with a short label, at the
            // glass row's own colour, so a select row and a glass row agree on "resting". A DISABLED row stays
            // bodiless: it is non-committable, and a hairline would make it look pressable. The 1 dp accent edge and
            // the 0.22 fill that marked focus were this overlay's private ring; the accent bar below stays.
            .mimirSelectionRing(
                focused = focused,
                identity = option.key,
                shape = MimirRingShape.Row(MimirSpacing.radiusCard),
                restingOutline = option.enabled,
                outlineColor = palette.glassRestingOutline(),
            )
            .clip(shape)
            // The shipping left accent bar (inset 3px), drawn only when focused — no scale, no heave.
            .drawBehind {
                if (focused) {
                    drawRect(
                        color = accentBright,
                        topLeft = Offset(0f, size.height * 0.16f),
                        size = Size(3.dp.toPx(), size.height * 0.68f),
                    )
                }
            }
            .clickable(enabled = option.enabled, onClick = onTap)
            .padding(horizontal = MimirSpacing.lg, vertical = MimirSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MimirSpacing.md),
    ) {
        // Leading visual: a real emulator app icon (Open-With) wins over the identity swatch (theme picker). When
        // the picker uses icons, EVERY row reserves a fixed 28dp slot — an undecodable / not-installed / iconless
        // row (the "Automatic" row) holds an empty slot so labels stay column-aligned and don't reflow as icons
        // decode in (the dim label + "Needs install" subtitle carry the not-installed state).
        when {
            appIcon != null -> Image(
                bitmap = appIcon,
                contentDescription = null,
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .graphicsLayer { alpha = appIconArt.alpha * (if (option.enabled) 1f else 0.45f) },
            )
            option.swatch != null -> Box(
                Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(option.swatch)
                    .border(1.dp, MimirColors.Outline, CircleShape),
            )
            reserveIconSlot -> Spacer(Modifier.size(28.dp))
        }
        // Label + optional dim subtitle (Open-With "· Needs install" reason / a backdrop explanation). A single
        // label with no subtitle renders exactly like the old standalone Text — existing callers are unchanged.
        val staticLabel = option.labelIntent is MimirTextIntent.StaticChrome ||
            option.labelIntent is MimirTextIntent.Mixed
        Column(Modifier.width(0.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            MimirIntentText(
                intent = option.labelIntent,
                style = (if (staticLabel) MimirType.MenuAction else MimirType.RailItem).copy(
                    fontWeight = if (option.current) FontWeight.SemiBold else FontWeight.Normal,
                    fontStyle = if (option.enabled) FontStyle.Normal else FontStyle.Italic,
                ),
                color = labelColor,
            )
            option.subtitle?.let { sub ->
                Text(
                    sub,
                    style = MimirType.RowSupport,
                    color = MimirColors.TextMuted,
                )
            }
        }
        // The auto/default pick's gold "Recommended" chip. Gold = accent, per the colour system.
        if (option.recommended) {
            MimirChromeText(
                "Recommended",
                style = MimirType.MicroStatus.copy(fontWeight = FontWeight.SemiBold),
                color = accentBright,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .clip(RoundedCornerShape(MimirSpacing.radiusPill))
                    .background(accentBright.copy(alpha = 0.14f))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        if (option.current) {
            Text(
                "✓", // ✓
                style = MimirType.RailItem,
                color = if (focused) focusedInk else accentBright,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** The head's `n / N`. Its own scope reads the State, so a focus move rewrites this one text and nothing else in the panel. */
@Composable
private fun SelectCounter(counter: State<String>, modifier: Modifier = Modifier) {
    Text(counter.value, style = MimirType.MicroStatus, color = MimirColors.TextMuted, maxLines = 1, modifier = modifier)
}

/**
 * One option row and the answer to "is this row lit", asked in the row's own scope. The overlay used to unwrap the authority's
 * state in its body and hand every row a Boolean, so each focus emission re-ran the whole panel (head, rule, every row's call) to change two rows' rings, and
 * the emission that seats the first row as the sheet opens re-ran it again one frame after the sheet's first composition. The row's key is the identity
 * ([key] at the call site), and the answer is the same [isLitFocusIn] the body asked.
 */
@Composable
private fun SelectRowSeat(
    authority: FocusAuthority,
    scope: FocusScope,
    focus: State<FocusState>,
    option: MimirSelectItem.Option,
    accent: Color,
    accentBright: Color,
    reserveIconSlot: Boolean,
    centeredPlain: Boolean,
    onSelect: (FocusKey) -> Unit,
) {
    val focused by remember(authority, scope, focus, option.key) {
        derivedStateOf(structuralEqualityPolicy()) { authority.isLitFocusIn(scope, option.key, focus.value) }
    }
    SelectRow(
        option = option,
        focused = focused,
        accent = accent,
        accentBright = accentBright,
        reserveIconSlot = reserveIconSlot,
        centeredPlain = centeredPlain,
        onTap = { if (option.enabled) { authority.requestFocus(option.key); onSelect(option.key) } },
        modifier = if (option.enabled) {
            Modifier.mimirFocusable(
                authority,
                option.key,
                scope,
                // Reveal the ring too, not just the row, when a move scrolls it in.
                revealOutset = MimirFocusTileRingReach,
            )
        } else {
            Modifier
        },
    )
}

/**
 * Decode [packageName]'s launcher icon to an [ImageBitmap] off the UI thread (the Open-With per-emulator icon).
 * Null while a first decode runs or when the package is absent / undecodable — a not-installed emulator simply shows no icon.
 * Local-only: a plain PackageManager read, no network. Runs once per package (keyed), and is kept in [PickerBitmapCache]: the next time the
 * modal opens, the icon is on its first frame instead of popping in beside its label (S-27).
 */
@Composable
private fun rememberAppIcon(packageName: String?): PickerArt {
    val context = LocalContext.current
    val pkg = packageName?.takeIf { it.isNotBlank() }
    return rememberPickerArt(pkg?.let { pickerAppIconKey(it, AppIconSizePx) }) {
        pkg?.let { runCatching { context.packageManager.getApplicationIcon(it).toImageBitmap(AppIconSizePx) }.getOrNull() }
    }
}

private const val AppIconSizePx = 96

/** Rasterize a launcher [Drawable] (including adaptive icons) to a square [ImageBitmap] — no androidx-core needed. */
private fun Drawable.toImageBitmap(sizePx: Int): ImageBitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    setBounds(0, 0, sizePx, sizePx)
    draw(canvas)
    return bitmap.asImageBitmap()
}

/**
 * The ONE input contract for an open [MimirSelectOverlay] (hard modal — swallows everything). UP/DOWN move focus
 * among the enabled rows; CONFIRM commits the focused option (if enabled); CANCEL/Back/Left dismiss. Mirrors the
 * shape the three pickers each hand-rolled, now shared.
 */
fun dispatchMimirSelect(
    authority: FocusAuthority,
    scope: FocusScope,
    items: List<MimirSelectItem>,
    action: ControllerAction,
    onSelect: (FocusKey) -> Unit,
    onDismiss: () -> Unit,
): Boolean {
    when (action) {
        ControllerAction.NAV_UP, ControllerAction.NAV_DOWN -> authority.moveFocus(action)
        ControllerAction.CONFIRM -> {
            val focused = authority.state.value.focused
            items.options().firstOrNull { it.key == focused && it.enabled }?.let { onSelect(it.key) }
        }
        ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK, ControllerAction.NAV_LEFT -> onDismiss()
        else -> Unit // hard modal: swallow everything else
    }
    return true
}
