package com.gruning.mimir.rewrite.feature.settings

import com.gruning.mimir.rewrite.core.ui.select.MimirSelectOverlay

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewResponder
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.input.isLitFocusIn
import com.gruning.mimir.rewrite.core.input.mimirActivatable
import com.gruning.mimir.rewrite.core.input.mimirFocusable
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirSwapPageGate
import com.gruning.mimir.rewrite.core.ui.mimirReducedMotion
import com.gruning.mimir.rewrite.core.ui.mimirRingSafeFade
import com.gruning.mimir.rewrite.core.ui.mimirCrossSwapFadeAlpha
import com.gruning.mimir.rewrite.core.ui.mimirCrossSwapTransform
import com.gruning.mimir.rewrite.core.ui.mimirSwapExitSpec
import com.gruning.mimir.rewrite.core.ui.mimirSwapTransform
import com.gruning.mimir.rewrite.core.ui.MimirFilledControlRingReach
import com.gruning.mimir.rewrite.core.ui.MimirFocusRestingAlpha
import com.gruning.mimir.rewrite.core.ui.MimirFocusTileRingReach
import com.gruning.mimir.rewrite.core.ui.MimirIntentText
import com.gruning.mimir.rewrite.core.ui.MimirLiquidGlassPanel
import com.gruning.mimir.rewrite.core.ui.MimirRingShape
import com.gruning.mimir.rewrite.core.ui.MimirTextIntent
import com.gruning.mimir.rewrite.core.ui.MimirWordEllipsisText
import com.gruning.mimir.rewrite.core.ui.resolve
import com.gruning.mimir.rewrite.core.ui.ctaMaterial
import com.gruning.mimir.rewrite.core.ui.glassRestingOutline
import com.gruning.mimir.rewrite.core.ui.mimirSelectionRing
import com.gruning.mimir.rewrite.core.ui.MimirEtchedChipLabelStyle
import com.gruning.mimir.rewrite.core.ui.etchedChipInk
import com.gruning.mimir.rewrite.core.ui.mimirEtchedChipSurface
import com.gruning.mimir.rewrite.core.ui.mimirSurfaceShadow
import com.gruning.mimir.rewrite.core.ui.theme.MimirColors
import com.gruning.mimir.rewrite.core.ui.theme.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.theme.MimirType
import java.util.Locale

/** SettingsSection titles are a closed static vocabulary; dynamic/user-authored values never enter here. */
internal fun settingsStaticHeader(title: String): String = title.uppercase(Locale.ROOT)

/** Dynamic chip/value copy is never routed through the static uppercase transform. */
internal fun settingsDynamicValue(value: String): String = value

internal fun settingsReceiptStacks(availableWidthDp: Float): Boolean = availableWidthDp < 420f

/** The label column's width in a receipt row, in dp: the label wraps at its spaces inside it. */
internal const val SettingsReceiptLabelWidthDp = 200f

/** The lines a receipt row's label may wrap to in its column. */
internal const val SettingsReceiptLabelMaxLines = 2

/**
 * Whether a receipt row's label cannot stand in its column ([SettingsReceiptLabelWidthDp] wide, [SettingsReceiptLabelMaxLines] lines at most) as the layout [label] was measured in drew
 * it: it ran past its lines, or a line ended inside a word because a word is wider than the column (a very large font). The row then stacks, the label over the value at the pane's full
 * width, instead of the text engine cutting the label or breaking its word.
 */
internal fun settingsReceiptLabelNeedsStack(label: TextLayoutResult): Boolean {
    if (label.didOverflowHeight || label.didOverflowWidth) return true
    val text = label.layoutInput.text.text
    return (0 until label.lineCount - 1).any { line ->
        val end = label.getLineEnd(line, visibleEnd = false)
        end in 1 until text.length && !text[end - 1].isWhitespace() && !text[end].isWhitespace()
    }
}

/** The lines a row's supporting text may take: a sentence that does not fit two lines wraps to a third, and is cut at a word only past that. */
internal const val SettingsSupportingMaxLines = 3

internal fun settingsKeyboardPreviewCompact(viewportHeightDp: Float): Boolean = viewportHeightDp <= 420f

/** Small-pane support threshold, named so the gate below reads as a rule rather than a bare literal. */
internal const val SettingsRowSupportCompactThresholdDp = 500f

/**
 * Small-pane support type: row supports move [MimirType.RowSupport] (Body 14) -> [MimirType.RowSupportCompact]
 * (Label 11) once the Settings pane's OWN measured width drops under 500dp.
 *
 * This is a DELIBERATE, SCOPED REVERSAL of the 2026-08-11 same-size pairing (title and support share
 * one size/weight) — AT SMALL PANES ONLY. Do not "fix" this back to a flat [MimirType.RowSupport]; the
 * same-size rule still holds at every pane at/above this threshold (the Odin's 616.5dp pane included —
 * see `SettingsRc8GeometrySolverTest`, zero movement there).
 *
 * Pure function of the pane's own measured width: no device/manufacturer check, no per-device band. Same
 * shape as [settingsReceiptStacks] just above and [ReceiptRow]'s own `BoxWithConstraints` gate below —
 * named reuse, not a new mechanism.
 */
internal fun settingsRowSupportCompact(paneWidthDp: Float): Boolean =
    paneWidthDp < SettingsRowSupportCompactThresholdDp

/**
 * Ambient form of [settingsRowSupportCompact], threaded from the ONE place that measures the Settings
 * pane's own box ([SettingsHome]'s `SettingsDetailPane`) down to every [SettingsRow] / [SettingsInfoRow]
 * leaf without adding a width parameter to the ~10 category panel functions in `SettingsDetail.kt` and
 * their ~34 call sites — those never see the pane's geometry today and this small-pane change doesn't need them to.
 *
 * Defaults to `false` (today's [MimirType.RowSupport], Body 14, unchanged) so any composition that never
 * provides it keeps current behaviour. That matters concretely for `ControllerMappingOverlay`'s own
 * `SettingsRow` call sites: that modal composes as a sibling of [SettingsHome] (from
 * `MimirAppHost.kt`, out of this lane's file scope), not inside its subtree, so it never sees this
 * provider and is untouched by construction — it measures a DIFFERENT pane this spec never measured, and
 * changing it would extend this small-pane change to a separately measured surface.
 */
internal val LocalSettingsRowSupportCompact = compositionLocalOf { false }

/**
 * The one resolver every [MimirType.RowSupport] call site in this pane should read instead of the raw
 * token, named reuse for [SettingsRow], [SettingsInfoRow] and [CreditsTomePanel] (identical role, same
 * gated pane —  leaving one `RowSupport` site unconverted
 * beside a converted one is the exact defect class this centralizes against).
 */
internal val settingsRowSupportStyle: TextStyle
    @Composable get() = if (LocalSettingsRowSupportCompact.current) MimirType.RowSupportCompact else MimirType.RowSupport

/**
 * True where [SettingsDetailPanel] has handed a pane's scrolling to its sub-page swap ([SettingsSubPageSwap]) instead of wearing one scroll column itself. The Music pane and the
 * Library pane do: each of their pages scrolls on its own, so a page being left holds its place while it fades and the page arriving starts at its top, never one shared offset
 * that jumps the page leaving (audit 2026-10-14, RC-34). Default false: a caller that lays the pane out itself (a test, a preview) supplies the scroll and gets the pages plain.
 */
internal val LocalSettingsPaneHostsScroll = compositionLocalOf { false }

/**
 * One Settings page's scroll: a fresh [ScrollState] per [scrollKey] (a page always starts at its top), attached to [SettingsDetailScrollBridge] so a D-pad dead end reveals the page's
 * clipped foot before it chains to the rail, and the air under the last row so it clears the pane's edge when scrolled.
 */
@Composable
internal fun SettingsPaneScroll(
    scrollKey: Any?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = remember(scrollKey) { ScrollState(initial = 0) }
    val bridgeScope = rememberCoroutineScope()
    DisposableEffect(scroll) {
        SettingsDetailScrollBridge.attach(scroll, bridgeScope)
        onDispose { SettingsDetailScrollBridge.detach(scroll) }
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(MimirSpacing.md),
    ) {
        content()
        // Bottom breathing room so the last row clears the pane edge when scrolled.
        Spacer(Modifier.height(MimirSpacing.xl))
    }
}

/**
 * A Settings pane's second page taking the first one's place in the pane (the Music pane's Behaviour and Forget folder pages, the Library pane's source-removal confirmation), and giving it back,
 * as ONE swap both ways (audit 2026-10-15, F5; RC-34 before it: `when (subPage)` replaced the page in one frame). The page left fades out while the page arriving fades in, on the Library view swap's
 * clock ([mimirCrossSwapTransform]: one [MimirMotion.OverlayMs], no lead, complementary at every frame), each travelling [MimirMotion.SwapTravelDp]: forward the page arriving rises from below,
 * a return ([returning]: B, or the move back to the pane's own page) brings it down from above. The swap tier's lead left the pane near-empty from about 130 ms on the way in, and its return dissolved the
 * page left over the page returned to for three frames of two pages' text; now the pane is never emptier than the cross-fade's own overlap and the two are readable together for about two frames, each way.
 * The pages fade themselves with [mimirRingSafeFade] because their rows' rings reach past them, and [MimirSwapPageGate] holds the pad until the arriving page shows, so an A pressed on the way in does not
 * act on a page nobody has seen (a switch on Behaviour, Remove on the confirmation).
 *
 * The page arriving registers its rows as it comes up from alpha 0, so the key has landed by the time it is readable; a return's one ringless frame is the authority's floor.
 *
 * [returning] is the caller's, computed from the swap's current [target] outside the pages: the page left picks up the mirrored travel in the same composition that retargets it.
 */
@Composable
internal fun <S> SettingsSubPageSwap(
    target: S,
    returning: Boolean,
    label: String,
    content: @Composable (S) -> Unit,
) {
    val reduced = mimirReducedMotion()
    val hosted = LocalSettingsPaneHostsScroll.current
    val travelPx = with(LocalDensity.current) { MimirMotion.SwapTravelDp.dp.roundToPx() }
    AnimatedContent(
        targetState = target,
        modifier = if (hosted) Modifier.fillMaxSize() else Modifier,
        contentAlignment = Alignment.TopStart,
        transitionSpec = { mimirCrossSwapTransform(reduced, fade = false, returning = returning, travelPx = travelPx) },
        label = label,
    ) { page ->
        val pageAlpha = mimirCrossSwapFadeAlpha(reduced)
        val pageInput = MimirSwapPageGate(pageAlpha)
        val pageModifier = Modifier.mimirRingSafeFade(pageAlpha).then(pageInput)
        if (hosted) {
            SettingsPaneScroll(scrollKey = page, modifier = pageModifier) { content(page) }
        } else {
            Column(modifier = pageModifier, verticalArrangement = Arrangement.spacedBy(MimirSpacing.md)) { content(page) }
        }
    }
}

/**
 * Shared Settings building blocks — the carded-row visual language reused by every detail panel so the
 * panels read as one product. Token-only (every gap/color is a token). The section card was a 1:1
 * transcription of the shipping Diagnostics screen's opaque cards; the shared glass treatment replaced that design — the card now rides the house Liquid Glass material
 * instead, so the two surfaces no longer look identical by construction (geometry/rhythm still match).
 */

/**
 * A section card with an optional trailing chip slot and stacked content. Release-alpha section titles
 * use the shared Tome-derived capped runic lane; the pane identity lives in the first card, so there is
 * no separate page-level title.
 */
@Composable
fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    titleIntent: MimirTextIntent = MimirTextIntent.StaticChrome(title),
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    // Shared glass treatment: the card rides the house Liquid Glass
    // material instead of an opaque SurfaceRaised fill. Geometry is otherwise unchanged from the card it
    // replaces — same 14dp radius (`radius = MimirSpacing.radiusCard`; the panel's own default is 20dp,
    // OWED-2 recommendation A — one variable moves so the eye judges one thing), same `lg` content
    // padding, same `spacedBy(sm)` inner rhythm. The shadow this card has always carried (B16 /
    // theme-critique F3: device frames showed "outlined regions, not raised surfaces") is kept, now
    // behind a translucent body instead of an opaque one — re-decided, not carried blindly, because
    // Settings' own unblurred glass pickers (MimirSelectOverlay) already pair `mimirSurfaceShadow` with
    // `MimirLiquidGlassPanel` this exact way; whether it reads as depth or as smudge on this narrower
    // card is a device fact owed to the eye, not asserted here.
    val palette = LocalMimirPalette.current
    MimirLiquidGlassPanel(
        modifier = modifier
            .fillMaxWidth()
            .mimirSurfaceShadow(palette, MimirSpacing.elevationCard, RoundedCornerShape(MimirSpacing.radiusCard)),
        contentPadding = MimirSpacing.lg,
        verticalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
        radius = MimirSpacing.radiusCard,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MimirIntentText(
                intent = titleIntent,
                style = MimirType.RunicEyebrow,
                color = MimirColors.GoldBright,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = if (trailing != null) MimirSpacing.sm else 0.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            trailing?.invoke()
        }
        content()
    }
}

/**
 * How far past a focused row the pane's scroll reveals it: the ring's reach ([MimirFocusTileRingReach], 14 dp, the outermost stroke's outer edge) and [MimirSpacing.xs] of air.
 * Revealed at exactly the reach, the halo's outer edge met the scroll host's clip flush, and the pane's top and bottom edges read as a cut through it (sweep 10-09: Music,
 * Equalizer and Music folder, at both edges); with the air the halo ends in the pane's own dark before the edge.
 */
internal val SettingsRevealOutset: Dp = MimirFocusTileRingReach + MimirSpacing.xs

/**
 * A row and the note under it, revealed as ONE: a focused row's reveal stops at the row and its ring's room, so the line standing under the last row of a card was left cut through by
 * the footer (Settings > Music > Behaviour, "If Android blocks it...", at font 1.3, sweep 10-09). The note is not a focus stop of its own, so the scroll never went for it; here the group
 * tells its parent that the whole of it, with [SettingsRevealNoteAir] below, is what the focus wants in view. The row inside keeps its own ring and registration.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SettingsRowWithNote(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val airPx = with(density) { SettingsRevealNoteAir.toPx() }
    // The laid-out size, in a plain array: written on every layout pass, read only when a reveal is asked for, so nothing recomposes for it.
    val laidOut = remember { IntArray(2) }
    val responder = remember(airPx) {
        object : BringIntoViewResponder {
            override fun calculateRectForParent(localRect: Rect): Rect {
                val whole = Rect(0f, 0f, laidOut[0].toFloat(), laidOut[1] + airPx)
                return Rect(
                    left = minOf(localRect.left, whole.left),
                    top = minOf(localRect.top, whole.top),
                    right = maxOf(localRect.right, whole.right),
                    bottom = maxOf(localRect.bottom, whole.bottom),
                )
            }

            // The scroll host above does the scrolling; this group only widens what it is asked to show.
            override suspend fun bringChildIntoView(localRect: () -> Rect?) = Unit
        }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { laidOut[0] = it.width; laidOut[1] = it.height }
            .bringIntoViewResponder(responder),
        // The room the focused row's halo needs under it, not the card's own rhythm: at the card's sm the halo lay over the note's first line (sweep 10-09, font 1.3).
        verticalArrangement = Arrangement.spacedBy(SettingsNoteGap),
        content = content,
    )
}

/**
 * The gap between a focusable row and the note under it: the ring's reach ([MimirFocusTileRingReach], 14 dp, the outermost stroke's outer edge), so the halo of the focused
 * row ends where the note's line box begins. The card's own [MimirSpacing.sm] left it 6 dp over the note's first line (Settings > Music > Behaviour, "If Android blocks it...",
 * at font 1.0 and 1.3). The gap is in dp, so it is the same at every font scale.
 */
internal val SettingsNoteGap: Dp = MimirFocusTileRingReach

/** The air [SettingsRowWithNote] asks to be revealed under its note: the pane never ends flush on the note's last line. */
internal val SettingsRevealNoteAir: Dp = MimirSpacing.sm

/**
 * A focusable, activatable settings row: title + optional supporting line on the left, an optional
 * trailing value/chip on the right. Focus AND tap both flow through the single [FocusAuthority]
 * : the row registers via `Modifier.mimirFocusable`, and
 * a tap requests focus AND activates — never a second focus engine.
 */
@Composable
fun SettingsRow(
    key: FocusKey,
    scope: FocusScope,
    authority: FocusAuthority,
    title: String,
    modifier: Modifier = Modifier,
    titleIntent: MimirTextIntent = MimirTextIntent.StaticChrome(title),
    titleStyle: TextStyle = MimirType.RowTitle,
    supporting: String? = null,
    /** The supporting line changes in place (a track, a preset): it takes the house value swap rather than a snap. */
    swapSupporting: Boolean = false,
    onActivate: () -> Unit = {},
    trailing: (@Composable () -> Unit)? = null,
) {
    val focused = isFocused(authority, scope, key)
    val palette = LocalMimirPalette.current
    // RC-11: every SettingsRow lives inside a MimirLiquidGlassPanel (SettingsSection, or ControllerMappingOverlay's
    // own glass panels), on the shared on-glass grammar: the house wash when focused, the glass hairline at rest.
    // The ring migration (2026-09-28) put that grammar on the one selection ring: the row's card corner, the hairline
    // fading out under the arriving ring, the house wash, and the 0.88 rest dim this list has always carried, eased
    // on the ring clock . `identity = key` feeds this row's own FocusKey to the ring's
    // `key(identity)` guard: several panels in SettingsDetail.kt render SettingsRow from an unkeyed `forEach` over a
    // list that can change shape (library-source removal rows, emulator-assignment rows), and a recomposed slot must
    // never carry one logical row's ring toward another's. The FocusKey is already unique per logical row.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MimirSpacing.minTarget)
            // Revealed with the ring's reach: a plain reveal stopped a focused row flush with the pane's edge, its
            // ring cut (observed on the bottom-bar PlayStation 2 row).
            .mimirFocusable(authority, key, scope, revealOutset = SettingsRevealOutset)
            .mimirSelectionRing(
                focused = focused,
                identity = key,
                shape = MimirRingShape.Row(MimirSpacing.radiusCard),
                restingOutline = true,
                restingAlpha = MimirFocusRestingAlpha,
                outlineColor = palette.glassRestingOutline(),
            )
            .clip(RoundedCornerShape(MimirSpacing.radiusCard))
            // This row's KDoc has claimed "never a second focus engine" since it was written, and until
            // this change that claim was FALSE: the `.clickable` that used to sit here built an
            // `AbstractClickableNode`, whose `FocusableNode` is a real focus target — planted on the same
            // `Row` as the `mimirFocusable` directly above. Now the claim is true. See [GradientCta].
            .mimirActivatable(label = title, authority = authority, key = key, onActivate = onActivate)
            .padding(horizontal = MimirSpacing.md, vertical = MimirSpacing.md),
        horizontalArrangement = Arrangement.spacedBy(MimirSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MimirSpacing.xs)) {
            MimirIntentText(
                intent = titleIntent,
                style = titleStyle,
                color = if (focused) MimirColors.GoldBright else MimirColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (supporting != null) {
                val supportingText: @Composable (String) -> Unit = { line ->
                    // Never cut inside a word ("your pa…", combined33 sweep): it wraps to a third line, and a sentence past that ends at a word.
                    MimirWordEllipsisText(
                        line,
                        // Small-pane support: see settingsRowSupportStyle's own KDoc for why this
                        // reads the ambient gate rather than a new parameter here.
                        style = settingsRowSupportStyle,
                        color = MimirColors.TextSecondary,
                        maxLines = SettingsSupportingMaxLines,
                    )
                }
                if (swapSupporting) SettingsValueSwap(supporting, Alignment.CenterStart, supportingText) else supportingText(supporting)
            }
        }
        trailing?.invoke()
    }
}

/** A passive settings fact with row geometry but no focus registration, click target, or implied action. */
@Composable
fun SettingsInfoRow(
    title: String,
    supporting: String,
    titleIntent: MimirTextIntent = MimirTextIntent.StaticChrome(title),
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MimirSpacing.minTarget)
            .padding(horizontal = MimirSpacing.md, vertical = MimirSpacing.md),
        horizontalArrangement = Arrangement.spacedBy(MimirSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MimirSpacing.xs)) {
            MimirIntentText(
                intent = titleIntent,
                style = MimirType.RowTitle,
                color = MimirColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            MimirWordEllipsisText(
                supporting,
                // Small-pane support: see settingsRowSupportStyle's own KDoc.
                style = settingsRowSupportStyle,
                color = MimirColors.TextSecondary,
                maxLines = SettingsSupportingMaxLines,
            )
        }
        trailing?.invoke()
    }
}

/**
 * A status pill. GREEN is reserved for success ("Ready" / "Local only") ONLY ; "Reconnect"/"Repair" read danger; everything else is a neutral
 * gold-tinted chip. The color decision lives HERE so no panel hand-picks a hue.
 */
@Composable
fun StatusPill(status: SettingsSample.SampleStatus) {
    val color = when (status) {
        SettingsSample.SampleStatus.READY -> MimirColors.SuccessGreen
        SettingsSample.SampleStatus.RECONNECT,
        SettingsSample.SampleStatus.REPAIR -> MimirColors.Danger
        SettingsSample.SampleStatus.NEEDS_EMULATOR,
        SettingsSample.SampleStatus.NEEDS_BIOS,
        SettingsSample.SampleStatus.UNSUPPORTED -> MimirColors.Gold
    }
    StaticStatusChip(text = status.label, color = color)
}

/** Static state vocabulary uses the capped micro-status lane. */
@Composable
fun StaticStatusChip(text: String, color: Color) {
    SettingsChip(intent = MimirTextIntent.StaticChrome(text), color = color)
}

/** Dynamic values preserve their authored casing; the chip supplies geometry, never copy mutation. */
@Composable
fun DynamicValueChip(text: String, color: Color) {
    SettingsChip(intent = MimirTextIntent.Authored(settingsDynamicValue(text)), color = color)
}

/** Shared chip geometry — translucent fill + solid label in [color]. Alpha is explicit. */
@Composable
private fun SettingsChip(intent: MimirTextIntent, color: Color) {
    val ink by animateColorAsState(color, mimirSwapExitSpec(mimirReducedMotion()), label = "settings-chip-ink")
    Box(
        Modifier
            .clip(RoundedCornerShape(MimirSpacing.radiusPill))
            // The wash is read in the draw phase (audit 2026-10-14, S-03 (4)): a colour change repaints it without recomposing the chip around it.
            .drawBehind { drawRect(ink.copy(alpha = 0.16f)) }
            .padding(horizontal = MimirSpacing.md, vertical = MimirSpacing.xs),
    ) {
        SettingsValueSwap(intent, Alignment.Center) { shown ->
            MimirIntentText(
                intent = shown,
                style = MimirType.MicroStatus,
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A row that comes and goes with the data (the Music pane's Output row once the player has reported its format): it grows in and
 * fades in on [MimirMotion.OverlayMs] and the house easing, and shrinks and fades out the same way, never popping. While it is gone it
 * is not composed at all, so it leaves no gap in the section's spacing. For rows that take no focus (a focusable row leaving would
 * need its ring and registration handled). Reduced motion jumps.
 */
@Composable
fun SettingsRowReveal(visible: Boolean, content: @Composable () -> Unit) {
    val ms = if (mimirReducedMotion()) 0 else MimirMotion.OverlayMs
    val state = remember { MutableTransitionState(visible) }
    state.targetState = visible
    if (state.currentState || state.targetState) {
        AnimatedVisibility(
            visibleState = state,
            enter = fadeIn(tween(ms, easing = MimirMotion.Easing)) + expandVertically(tween(ms, easing = MimirMotion.Easing)),
            exit = fadeOut(tween(ms, easing = MimirMotion.Easing)) + shrinkVertically(tween(ms, easing = MimirMotion.Easing)),
        ) { content() }
    }
}

/** The small affordance value shown on a row that opens a picker / runs an action (e.g. "Change"). */
@Composable
fun RowAction(label: String, color: Color = MimirColors.Gold) {
    SettingsValueSwap(label, Alignment.CenterEnd) { shown ->
        MimirChromeText(
            shown,
            style = MimirType.MenuAction,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// Shared swap-tier travel; the detail pane keeps its separate 6dp glide.
private val SettingsValueTravel = MimirMotion.SwapTravelDp.dp

// A value changed in place (a toggle, a pick, a count) takes the house swap tier with a short rise, never a snap (walk-32m).
@Composable
private fun <T> SettingsValueSwap(target: T, contentAlignment: Alignment, content: @Composable (T) -> Unit) {
    val reduced = mimirReducedMotion()
    val travelPx = with(LocalDensity.current) { SettingsValueTravel.roundToPx() }
    AnimatedContent(
        targetState = target,
        contentAlignment = contentAlignment,
        transitionSpec = { mimirSwapTransform(reduced, enterTravelPx = travelPx) },
        label = "settings-value",
    ) { shown -> content(shown) }
}

/**
 * A non-interactive label/value "receipt" row — the shipping Library-folders detail grid (a muted label
 * in a fixed left column, the value in white to its right). Not focusable; the pane's actions live on a
 * [GradientCta] / [SettingsRow].
 */
@Composable
fun ReceiptRow(label: String, value: String) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = MimirSpacing.sm)) {
        // The label wraps at its spaces ("ADDITIONAL LIBRARY / FOLDERS" at 1.3x, combined33 sweep: it ended "F…"); when its longest word is wider than the column the row stacks.
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val labelStacks = remember(label, measurer, density) {
            val columnPx = with(density) { SettingsReceiptLabelWidthDp.dp.roundToPx() }
            settingsReceiptLabelNeedsStack(
                measurer.measure(
                    text = AnnotatedString(MimirTextIntent.StaticChrome(label).resolve().visualText),
                    style = MimirType.RunicEyebrow,
                    maxLines = SettingsReceiptLabelMaxLines,
                    constraints = Constraints(maxWidth = columnPx),
                ),
            )
        }
        if (settingsReceiptStacks(maxWidth.value) || labelStacks) {
            Column(verticalArrangement = Arrangement.spacedBy(MimirSpacing.xs)) {
                MimirChromeText(
                    label,
                    style = MimirType.RunicEyebrowCompact,
                    color = MimirColors.TextMuted,
                    maxLines = SettingsReceiptLabelMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    value,
                    style = MimirType.SettingsReceiptValue,
                    color = MimirColors.TextPrimary,
                )
            }
        } else {
            Row(Modifier.fillMaxWidth()) {
                MimirChromeText(
                    label,
                    style = MimirType.RunicEyebrow,
                    color = MimirColors.TextMuted,
                    modifier = Modifier.width(SettingsReceiptLabelWidthDp.dp),
                    maxLines = SettingsReceiptLabelMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    value,
                    style = MimirType.SettingsReceiptValue,
                    color = MimirColors.TextPrimary,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * A full-width primary CTA (e.g. "Library folders", "Export backup"): the PRIMARY etched chip (update, no gradient fill), the same dark plate as
 * [TonalCta], its rim and its word in the accent. Its name stays from when it was the gold gradient. Focusable through the single [FocusAuthority] (never Compose `focusable()`);
 * the one selection ring is on the plate itself (it is the whole control), BEFORE the surface, so its edge is the plate's edge and its wash is under the opaque metal.
 */
@Composable
fun GradientCta(
    label: String,
    key: FocusKey,
    scope: FocusScope,
    authority: FocusAuthority,
    onActivate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focused = isFocused(authority, scope, key)
    val palette = LocalMimirPalette.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MimirSpacing.minTarget)
            .mimirFocusable(authority, key, scope, revealOutset = SettingsRevealOutset)
            // The shared ring has no arrival flare here: a flare would outreach
            // SettingsSection's 16 dp clip. It goes before the surface: it is drawn outside the plate, and the plate paints over its wash.
            .mimirSelectionRing(focused = focused, identity = key, shape = MimirRingShape.Pill, flare = false, edgeFlush = true)
            .mimirEtchedChipSurface(palette, focused = focused, primary = true, edgeFlush = true)
            // `mimirActivatable`, never `clickable`: `clickable` builds an `AbstractClickableNode`, which
            // constructs a `FocusableNode` and therefore a real focus target — a SECOND one, beside the
            // `mimirFocusable` registration three lines above on this same `Box`. F1 in
            // `tools/mimir_ui_invariants_gate.py` greps only for `.focusable(` / `focusTarget(`, so it
            // never saw this. Passing `authority`/`key` keeps the exact prior order: publish focus, then
            // act — the CTA's action can resolve against the focused key, so the tapped button must be
            // the focused one before it runs.
            .mimirActivatable(label = label, authority = authority, key = key, onActivate = onActivate)
            .padding(vertical = MimirSpacing.md),
        contentAlignment = Alignment.Center,
    ) {
        MimirChromeText(label, style = MimirEtchedChipLabelStyle, color = palette.etchedChipInk(primary = true), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A tonal (secondary) CTA (e.g. "Import backup") that pairs with [GradientCta]: an unselected etched chip (update; it was a gold-tinted capsule), at the pair's own row
 * height so the two stay even. Focus is the one selection ring, on the plate itself and before the surface. Focusable through the single authority, never Compose `focusable()`.
 */
@Composable
fun TonalCta(
    label: String,
    key: FocusKey,
    scope: FocusScope,
    authority: FocusAuthority,
    onActivate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalMimirPalette.current
    val focused = isFocused(authority, scope, key)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MimirSpacing.minTarget)
            .mimirFocusable(authority, key, scope, revealOutset = SettingsRevealOutset)
            // The one ring: an unfilled pill, before the surface, and no arrival flare. The metal has its own rim, so
            // there is no resting hairline.
            .mimirSelectionRing(focused = focused, identity = key, shape = MimirRingShape.Pill, flare = false, edgeFlush = true)
            .mimirEtchedChipSurface(palette, warmed = false, focused = focused, edgeFlush = true)
            // See [GradientCta] for why this is `mimirActivatable` and not `clickable` — same shape, same
            // `mimirFocusable` sitting on this same `Box`, same second-focus-target hazard.
            .mimirActivatable(label = label, authority = authority, key = key, onActivate = onActivate)
            .padding(vertical = MimirSpacing.md),
        contentAlignment = Alignment.Center,
    ) {
        MimirChromeText(label, style = MimirEtchedChipLabelStyle, color = palette.etchedChipInk(), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * RETIRED (removed the debug banner) — zero call sites; kept only for standalone
 * previews. Not rendered on any panel; the old text claimed every panel showed it so no reviewer ever
 * mistakes the hand-authored [SettingsSample] state for a live native readout.
 */
@Composable
fun SampleDataNote(
    label: String = "SAMPLE DATA",
    message: String = "Static placeholder state — no native bridge is wired yet.",
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MimirSpacing.radiusPill))
            .background(MimirColors.GoldGlow)
            .padding(horizontal = MimirSpacing.md, vertical = MimirSpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(settingsStaticHeader(label), style = MimirType.RunicEyebrow, color = MimirColors.Gold)
        Text(
            message,
            style = MimirType.RowSupport,
            color = MimirColors.TextSecondary,
        )
    }
}

/**
 * True when [key] is the key [scope] should currently paint a focus RING around — the ring channel of
 * core-input's two-channel focus law, so it goes false the moment a pushed child scope (a select overlay,
 * a picker) takes input, and true again on return. Every one of this function's three callers in this file
 * drives pure focus indication with it and nothing else: a row background, a CTA border tint, an outline
 * width. None of them scales (the row explicitly passes `focusedScale = 1f`), so there is no geometry here
 * to conserve — which is exactly why this whole function belongs on the lit channel rather than the
 * conserved one. The conserved version incorrectly kept the ring lit after input moved away.
 */
@Composable
internal fun isFocused(authority: FocusAuthority, scope: FocusScope, key: FocusKey): Boolean {
    val state by authority.state.collectAsState()
    return authority.isLitFocusIn(scope, key, state)
}
