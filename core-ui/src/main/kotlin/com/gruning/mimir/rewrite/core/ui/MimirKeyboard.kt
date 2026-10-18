package com.gruning.mimir.rewrite.core.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlin.math.abs
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The keyboard's approximate full-size height (editing caption + 5 rows x 48dp + gaps + padding). A
 * caller that floats the keyboard OVER scrollable content should reserve this much bottom inset so its last
 * rows can scroll clear of the keys (else a tapped-for item can hide permanently under the keyboard).
 */
val MimirKeyboardHeight: Dp = 352.dp

/** App hosts may provide Mimir's user-controlled haptic tick; isolated previews use Android's fallback. */
val LocalMimirKeyboardHapticFeedback = staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * The tool row's caret and Select-all ops when the keyboard does NOT own the field's whole text: the system input method
 * holds only a bounded prefix of what the field holds (the text before the caret, at most a thousand characters), so a
 * local "move one place" or "select everything" would act on that prefix and leave the rest of the field out of reach.
 * The host routes these to the field itself (a DPAD key, the editor's own select-all), and the keyboard's preview follows
 * the field's reported selection afterwards. Null (every in-app caller) keeps the local ops, which are correct there
 * because the keyboard holds the whole value. Never used for a secret field, which has its own discrete ops.
 */
class MimirKeyboardFieldOps(
    val caretLeft: () -> Unit,
    val caretRight: () -> Unit,
    val selectAll: () -> Unit,
    /**
     * Whether the FIELD reports a non-empty selection, read when a key is tapped. The preview holds only the text before the
     * selection's lower edge (after Select all it is empty), so its own value cannot say whether something is selected.
     */
    val selectionActive: () -> Boolean = { false },
    /** Deletes the field's selection (a commit of nothing over it), whatever the preview holds. */
    val deleteSelection: () -> Unit = {},
)

/**
 * MIMIR-REWRITE :core-ui — THE in-app keyboard. The rewrite's first (and only) text-input mechanism.
 *
 * Touch drives typing; an optional [controller] lets the host wire D-pad focus through [FocusAuthority]
 * without coupling core-ui to core-input. When [controller] is null, behavior matches the touch-only path.
 *
 * The compact field in the rune caption owns only caret/selection state. The caller still owns [value], and
 * the existing String API remains source-compatible. Tapping or long-pressing the caption field places the
 * caret or selects text without opening the Android IME; key taps then replace that selection.
 *
 * @param value the current text the field holds. An external replacement moves the local caret to its end.
 * @param onValueChange the next text after a key press; selection-only changes stay inside the keyboard.
 * @param onDone the user finished (Done key) — the caller closes the keyboard + keeps or applies the value.
 * @param controller optional host focus contract; null preserves legacy touch-only behavior.
 * @param palette the active theme; defaults to the CompositionLocal-provided one.
 * @param targetLabel short label for the field this keyboard is editing; shown in the shipping caption.
 * @param showControllerLegend whether to show the trailing controller B / Done legend.
 * @param controllerLegendAction the word after B in that legend: what B does in this host. Product review
 *   2026-09-28 (F2) made it the host's; since OR-10 (2026-09-29) B cancels in every editor, so the default is Back.
 * @param fieldKind which existing layout to open on for a number, email or URL field; [MimirKeyboardFieldKind.TEXT]
 *   (the default) is the shipping behaviour every in-app caller keeps.
 * @param floating whether to use the centered, all-corner floating panel treatment.
 * There is NO secret mode here: a password or key is typed through [MimirSecretFieldKeyboard], which takes no text
 * at all (D33 fix round, 2026-10-07), so nothing this keyboard remembers or captures can hold one.
 */
@Composable
fun MimirKeyboard(
    value: String,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    controller: MimirKeyboardController? = null,
    keyDecorator: MimirKeyboardKeyDecorator? = null,
    palette: MimirThemePalette = LocalMimirPalette.current,
    targetLabel: String = "Type",
    targetLabelIntent: MimirTextIntent = MimirTextIntent.StaticChrome(targetLabel),
    showControllerLegend: Boolean = true,
    floating: Boolean = false,
    controllerLegendAction: String = "Back",
    fieldKind: MimirKeyboardFieldKind = MimirKeyboardFieldKind.TEXT,
    trackpad: MimirTrackpadHost? = null,
    trackpadResetKey: Any? = null,
    fill: Boolean = false,
    onHide: (() -> Unit)? = null,
    fieldOps: MimirKeyboardFieldOps? = null,
) {
    var editingValue by remember {
        mutableStateOf(TextFieldValue(text = value, selection = TextRange(value.length)))
    }
    val currentValue = synchronizeMimirKeyboardText(editingValue, value)
    SideEffect {
        if (editingValue != currentValue) editingValue = currentValue
    }

    MimirKeyboardSurface(
        value = currentValue,
        onValueChange = { nextValue ->
            editingValue = nextValue
            if (nextValue.text != currentValue.text) onValueChange(nextValue.text)
        },
        onDone = onDone,
        modifier = modifier,
        controller = controller,
        keyDecorator = keyDecorator,
        palette = palette,
        targetLabel = targetLabel,
        targetLabelIntent = targetLabelIntent,
        showControllerLegend = showControllerLegend,
        floating = floating,
        secret = false,
        controllerLegendAction = controllerLegendAction,
        fieldKind = fieldKind,
        trackpad = trackpad,
        trackpadResetKey = trackpadResetKey,
        fill = fill,
        onHide = onHide,
        fieldOps = fieldOps,
    )
}

/**
 * Selection-hoisted overload for fields that already use [TextFieldValue]. The String overload above remains
 * the compatibility entry point for existing call sites.
 */
@Composable
fun MimirKeyboard(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    controller: MimirKeyboardController? = null,
    keyDecorator: MimirKeyboardKeyDecorator? = null,
    palette: MimirThemePalette = LocalMimirPalette.current,
    targetLabel: String = "Type",
    targetLabelIntent: MimirTextIntent = MimirTextIntent.StaticChrome(targetLabel),
    showControllerLegend: Boolean = true,
    floating: Boolean = false,
    controllerLegendAction: String = "Back",
    fieldKind: MimirKeyboardFieldKind = MimirKeyboardFieldKind.TEXT,
    trackpad: MimirTrackpadHost? = null,
    trackpadResetKey: Any? = null,
    fill: Boolean = false,
    onHide: (() -> Unit)? = null,
) {
    MimirKeyboardSurface(
        value = value,
        onValueChange = onValueChange,
        onDone = onDone,
        modifier = modifier,
        controller = controller,
        keyDecorator = keyDecorator,
        palette = palette,
        targetLabel = targetLabel,
        targetLabelIntent = targetLabelIntent,
        showControllerLegend = showControllerLegend,
        floating = floating,
        secret = false,
        controllerLegendAction = controllerLegendAction,
        fieldKind = fieldKind,
        trackpad = trackpad,
        trackpadResetKey = trackpadResetKey,
        fill = fill,
        onHide = onHide,
    )
}

/**
 * Whether an editor opens with shift on. An empty ordinary field starts in sentence case; a secret never does,
 * because a password or Web API key is case-exact and is drawn as dots, so an auto-capitalised first letter would be
 * both wrong and invisible.
 */
internal fun mimirKeyboardStartsShifted(
    text: String,
    secret: Boolean,
    fieldKind: MimirKeyboardFieldKind = MimirKeyboardFieldKind.TEXT,
): Boolean = text.isEmpty() && !secret && fieldKind == MimirKeyboardFieldKind.TEXT

/**
 * Where D-pad focus lands when the keyboard opens. Always landing on Done meant the first A
 * on an empty field submitted
 * it. An empty field lands on the first letter, q, where typing starts; a field that already holds text keeps Done,
 * because confirming it is the likelier next step.
 */
fun mimirKeyboardLandingKeyId(text: String): String = mimirKeyboardLandingKeyIdForLength(text.length)

/** The same landing from a COUNT: a secret field knows how many dots it holds, never what they stand for. */
fun mimirKeyboardLandingKeyIdForLength(length: Int): String =
    if (length <= 0) MimirKeyboardKeyIds.character("q").value else MimirKeyboardKeyIds.Done.value

internal const val MimirKeyboardShowLabel = "Show"
internal const val MimirKeyboardHideLabel = "Hide"
private val KeyboardRevealWidth = 64.dp

@Composable
internal fun MimirKeyboardSurface(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier,
    controller: MimirKeyboardController?,
    keyDecorator: MimirKeyboardKeyDecorator?,
    palette: MimirThemePalette,
    targetLabel: String,
    targetLabelIntent: MimirTextIntent,
    showControllerLegend: Boolean,
    floating: Boolean,
    secret: Boolean,
    controllerLegendAction: String,
    fieldKind: MimirKeyboardFieldKind,
    /**
     * Non-null for a SECRET field typed through discrete edit ops ([MimirSecretEdit]): keys call this immediately
     * and nothing typed is written into [value], any remembered state or any buffer. [value] is then only the
     * derived dots of the caption, and caption touches do nothing.
     */
    secretEdit: ((MimirSecretEdit) -> Unit)? = null,
    /**
     * Main's in-app secret entry only: the Show/Hide key flips the OWNER's reveal flag. The keyboard is never handed the
     * text it would reveal (the owner draws that itself, outside this composable), so the caption only ever renders
     * dots. Null for the system keyboard's secret path, which has no Show key.
     */
    secretRevealToggle: (() -> Unit)? = null,
    /** Whether the owner's reveal is on: it only picks the Show/Hide key's label and state. */
    secretRevealed: Boolean = false,
    /** A secret keyboard's bounded dot COUNT (an Int), so initial focus never consults the rendered dots. */
    secretCount: Int? = null,
    /** D34b: non-null only for an ordinary (never secret) system-keyboard field on a device that can host the trackpad. */
    trackpad: MimirTrackpadHost? = null,
    /** Changes on every show so the keyboard returns to its keys (and its consent card is dismissed). */
    trackpadResetKey: Any? = null,
    /**
     * The keyboard fills its host: the Bottom screen (the system keyboard when the input method window is on the
     * Bottom, and the in-app rename plate in the Presentation). The key rows take all the height the caption leaves,
     * the panel spans the whole width, and the caption is a 44 dp editing line. False everywhere else: Main and phones
     * keep their sizes exactly.
     */
    fill: Boolean = false,
    /**
     * The tool row's Hide (fill mode): closes the editor WITHOUT the field's editor action and without saving anything the
     * host would save on Done. Null means the host has no
     * such close, and the row shows no Hide at all: it never falls back to [onDone], which commits.
     */
    onHide: (() -> Unit)? = null,
    /** The tool row's caret and Select-all when the host owns the field's text (the system input method); see [MimirKeyboardFieldOps]. */
    fieldOps: MimirKeyboardFieldOps? = null,
) {
    val trackpadOffered = trackpad != null && !secret && secretEdit == null
    // 0 = keys, 2 = the consent card (the trackpad itself is its own window).
    var padMode by remember(trackpadResetKey) { mutableStateOf(0) }
    var shiftOn by remember { mutableStateOf(!secret && mimirKeyboardStartsShifted(value.text, false, fieldKind)) }
    var characterSet by remember { mutableStateOf(fieldKind.startingCharacterSet()) }
    // A password field offers Show/Hide; reset to hidden whenever the keyboard opens.
    var ordinaryRevealed by remember { mutableStateOf(false) }
    val revealed = if (secretEdit != null) secretRevealed else ordinaryRevealed
    val landing = remember {
        if (secretCount != null) mimirKeyboardLandingKeyIdForLength(secretCount) else mimirKeyboardLandingKeyId(value.text)
    }
    val clipboard = LocalClipboardManager.current
    val keyRows = remember(shiftOn, characterSet, fieldKind, fill) {
        keyboardRows(shiftOn = shiftOn, characterSet = characterSet, fieldKind = fieldKind, trackpad = trackpadOffered, toolRow = fill)
    }
    DisposableEffect(controller) {
        controller?.let { c -> c.onEnterAt?.invoke(landing) ?: c.onEnter() }
        onDispose { controller?.onExit() }
    }
    LaunchedEffect(controller, keyRows, secret, padMode) {
        val activeController = controller ?: return@LaunchedEffect
        withFrameNanos { }
        // While the trackpad or its consent card replaces the keys there are no keys to focus.
        val keys = if (padMode != 0) emptySet() else keyRows.flatten().map { it.keyId.value }.toSet()
        activeController.onKeysChanged(
            if (secret && (secretEdit == null || secretRevealToggle != null)) keys + MimirKeyboardKeyIds.Reveal.value else keys,
        )
    }
    // L2/R2 shoulder shortcuts: registered ONCE per controller instance,
    // not re-registered on every shift/characterSet change — `shiftOn`/`characterSet` are `remember`ed
    // MutableState delegates, so a closure created here keeps reading/writing the CURRENT value on every
    // later invocation regardless of which recomposition captured it (same reasoning as DisposableEffect
    // above keying only on `controller`). Re-registering on every toggle would mean one extra frame where
    // the host still holds the PREVIOUS closure, capturing a discarded state snapshot.
    LaunchedEffect(controller) {
        controller?.onShoulderActionsChanged(
            { shiftOn = !shiftOn },
            { characterSet = characterSet.next() },
        )
        // Row layout for LEFT/RIGHT: registered ONCE per controller instance,
        // same reasoning as the shoulder actions above — `characterSet` is a `remember`ed MutableState
        // delegate, so this closure reads its CURRENT value on every later invocation (e.g. after R2 has
        // cycled it), not the value captured at registration time.
        // A secret's Show/Hide chip is its own row above the keys, so LEFT/RIGHT there stays put.
        controller?.onRowLayoutChanged {
            if (secret && (secretEdit == null || secretRevealToggle != null)) {
                listOf(listOf(MimirKeyboardKeyIds.Reveal.value)) + mimirKeyboardLayoutRows(characterSet, fieldKind, trackpad = trackpadOffered, toolRow = fill)
            } else {
                mimirKeyboardLayoutRows(characterSet, fieldKind, trackpad = trackpadOffered, toolRow = fill)
            }
        }
    }
    val emitEdit: (TextFieldValue) -> Unit = { nextValue ->
        if (nextValue != value) onValueChange(nextValue)
    }
    // One measurer for the whole keyboard: the press bubbles draw through it (draw phase, nothing remembered per key).
    val bubbleMeasurer = rememberTextMeasurer()
    // Every key's tap, the tool row's Paste and PAD included, goes through this one table.
    fun actionFor(key: KeyboardKey): () -> Unit = keyTapAction(
        key = key,
        value = value,
        clipboard = clipboard,
        shiftOn = shiftOn,
        characterSet = characterSet,
        onShiftToggle = { shiftOn = !shiftOn },
        // Touch's "symbols"/"abc" mode keys keep their pre-existing two-stop behavior
        // (Alpha <-> Symbols, skipping Numbers) -- only R2's cycle visits Numbers.
        onSymbolsOn = { characterSet = MimirKeyboardCharacterSet.SYMBOLS },
        onAbc = { characterSet = MimirKeyboardCharacterSet.ALPHA; shiftOn = true },
        onShiftOffAfterChar = {
            if (shiftOn &&
                characterSet == MimirKeyboardCharacterSet.ALPHA &&
                key.value.any { it.isLetter() }
            ) {
                shiftOn = false
            }
        },
        emitEdit = emitEdit,
        onDone = onDone,
        secretEdit = secretEdit,
        fieldOps = fieldOps,
        onTrackpad = {
            // With the service on the key opens the full-screen pad WINDOW on the Bottom (it is not part of
            // this keyboard, so nothing here closes it); with it off, the consent card instead.
            if (trackpad?.serviceConnected == true) trackpad.openPad() else padMode = 2
        },
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = if (fill) 0.dp else MimirKeyboardPanelSideInset),
        contentAlignment = if (floating) Alignment.Center else Alignment.BottomCenter,
    ) {
        val compact = maxHeight <= KeyboardShortDisplayThreshold
        val panelGap = if (compact) MimirSpacing.xs else MimirSpacing.sm
        val captionHeight = if (compact) KeyboardCompactEditingHeight else KeyboardEditingHeight
        // The floating sheet's air is one value on all four sides (sweep 10-09: about 7 px above the field, 16 below the last row, 26 at the sides, on Main). The Bottom's
        // fill geometry is solved from its own measured overhead and keeps the values it had.
        val panelTopPadding = if (!fill) KeyboardPanelInnerPadding else if (compact) MimirSpacing.xs else MimirSpacing.sm
        val panelBottomPadding = if (!fill) KeyboardPanelInnerPadding else if (compact) MimirSpacing.sm else MimirSpacing.md
        val captionAndPaddingHeight = captionHeight + panelTopPadding + panelBottomPadding
        val naturalPanelHeight = captionAndPaddingHeight +
            MimirSpacing.minTarget * keyRows.size.toFloat() +
            panelGap * keyRows.size.toFloat()
        // on the Bottom the keys stay NORMAL (about 1:1.1, never stretched, anchored at the bottom near the
        // thumbs) and the spare height becomes a text preview and a tool row.
        val fillGeometry = if (fill) {
            mimirKeyboardFillGeometry(
                availableWidth = maxWidth,
                availableHeight = maxHeight,
                rows = keyRows.size,
                widestRowWeight = keyRows.maxOfOrNull { row -> row.sumOf { it.weight.toDouble() }.toFloat() } ?: 1f,
                sidePadding = MimirSpacing.md,
                gap = panelGap,
                toolRowHeight = KeyboardToolRowHeight,
                verticalOverhead = panelTopPadding + panelBottomPadding + panelGap * 2f,
            )
        } else {
            null
        }
        val keyHeight = if (fillGeometry != null) {
            fillGeometry.keyHeight
        } else if (maxHeight.value.isFinite() && maxHeight < naturalPanelHeight) {
            mimirKeyboardKeyHeight(
                available = maxHeight,
                rows = keyRows.size,
                fixedOverhead = captionAndPaddingHeight,
                rowGap = panelGap,
            )
        } else {
            MimirSpacing.minTarget
        }
        val panelShape = if (floating) KeyboardFloatingPanelShape else KeyboardPanelShape
        // fillMaxWidth().widthIn(max=) was inert here: fillMaxWidth() (fraction 1.0) fixes min==max at
        // this BoxWithConstraints's own maxWidth, so widthIn's cap only ever coerced UP to that width.
        val panelWidth = if (fill) maxWidth else minOf(maxWidth, KeyboardPanelMaxWidth)
        Column(
            modifier = Modifier
                .width(panelWidth)
                .testTag(MimirKeyboardPanelTag)
                .then(if (fill) Modifier.fillMaxHeight() else Modifier)
                .mimirSurfaceShadow(palette, KeyboardPanelElevation, panelShape)
                .clip(panelShape)
                .background(keyboardPanelBrush(palette))
                .border(BorderStroke(KeyboardHairlineWidth, keyboardPanelBorder(palette)), panelShape)
                // UX-debt : the panel swallows EVERY touch inside its own bounds, so a
                // mis-tap in the gaps/padding "outside the key" can't fall through to an actionable item
                // sitting behind the floating keyboard. Keys are children and win their own hits; only the
                // empty area ABOVE the panel stays pass-through (tap a filtered search result to open it).
                // No-op + indication=null: it blocks touches, it doesn't look or behave like a button.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(
                    start = KeyboardPanelInnerPadding,
                    top = panelTopPadding,
                    end = KeyboardPanelInnerPadding,
                    bottom = panelBottomPadding,
                ),
            verticalArrangement = Arrangement.spacedBy(if (fill) 0.dp else panelGap),
        ) {
            KeyboardCaption(
                value = value,
                onValueChange = if (secretEdit != null) IgnoreSecretCaptionEdit else emitEdit,
                inert = secretEdit != null,
                revealable = secretEdit == null || secretRevealToggle != null,
                targetLabel = targetLabel.ifBlank { "Type" },
                targetLabelIntent = if (targetLabel.isBlank()) {
                    MimirTextIntent.StaticChrome("Type")
                } else {
                    targetLabelIntent
                },
                palette = palette,
                compact = compact,
                fill = fill,
                modifier = if (fill) Modifier.weight(1f) else Modifier,
                showControllerLegend = showControllerLegend,
                controllerLegendAction = controllerLegendAction,
                secret = secret,
                revealed = revealed,
                onToggleReveal = {
                    if (secretRevealToggle != null) secretRevealToggle() else ordinaryRevealed = !ordinaryRevealed
                },
                controller = controller,
                keyDecorator = keyDecorator,
            )
            if (fill) {
                Spacer(Modifier.height(panelGap))
                KeyboardToolRow(
                    palette = palette,
                    gap = panelGap,
                    secret = secretEdit != null,
                    trackpadOffered = trackpadOffered,
                    onCaretLeft = {
                        if (secretEdit != null) {
                            secretEdit(MimirSecretEdit.CaretLeft)
                        } else if (fieldOps != null) {
                            fieldOps.caretLeft()
                        } else {
                            emitEdit(moveMimirKeyboardCursorBySpaceDrag(value, -MimirKeyboardSpaceDragStepDp))
                        }
                    },
                    onCaretRight = {
                        if (secretEdit != null) {
                            secretEdit(MimirSecretEdit.CaretRight)
                        } else if (fieldOps != null) {
                            fieldOps.caretRight()
                        } else {
                            emitEdit(moveMimirKeyboardCursorBySpaceDrag(value, MimirKeyboardSpaceDragStepDp))
                        }
                    },
                    onSelectAll = {
                        if (fieldOps != null) {
                            fieldOps.selectAll()
                        } else {
                            emitEdit(selectMimirKeyboardText(value, 0, value.text.length))
                        }
                    },
                    onPaste = actionFor(toolKey(KeyboardAction.Paste)),
                    onPad = actionFor(toolKey(KeyboardAction.Trackpad)),
                    onHide = onHide,
                )
                Spacer(Modifier.height(panelGap))
            }
            val rowsHeight = if (fill) {
                (keyHeight + panelGap) * keyRows.size.toFloat()
            } else {
                keyHeight * keyRows.size.toFloat() + panelGap * (keyRows.size - 1).coerceAtLeast(0).toFloat()
            }
            // the trackpad consent card replaced the key rows in one frame. It is a crossfade now on the
            // sheet clock (OverlayMs, the house easing). The card is exactly as tall as the rows (`rowsHeight`), so the size is held rather
            // than ridden on Compose's default spring, and `clip = false` keeps a key's ring from being cut at the box. Rows that are
            // leaving register nothing with the controller and take no touch (a sibling shield), so a key cannot be tapped or focused
            // through the fade.
            val consentShown = padMode == 2 && trackpad != null
            val padSwapReducedMotion = mimirReducedMotion()
            AnimatedContent(
                targetState = consentShown,
                transitionSpec = {
                    fadeIn(mimirSheetSpec(padSwapReducedMotion)) togetherWith fadeOut(mimirSheetSpec(padSwapReducedMotion)) using
                        SizeTransform(clip = false) { _, _ -> snap() }
                },
                label = "mimirKeyboardPadConsent",
            ) { consent ->
                val live = consent == consentShown
                Box {
                    if (consent && trackpad != null) {
                        MimirTrackpadConsentCard(
                            onOpenSettings = trackpad::openAccessibilitySettings,
                            height = rowsHeight,
                            palette = palette,
                            onDismiss = { padMode = 0 },
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(if (fill) 0.dp else panelGap)) {
                            keyRows.forEach { row ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(if (fill) 0.dp else MimirSpacing.sm),
                                ) {
                                    row.forEach { key ->
                                        val keyAction = actionFor(key)
                                        // **the key cap's composition identity is its keyId, not its
                                        // slot.** `keyRows` is rebuilt whenever `shiftOn` or `characterSet`
                                        // changes (`remember(shiftOn, characterSet)`, :186), so cycling ALPHA -> SYMBOLS
                                        // -> NUMBERS repopulates every grid position with a DIFFERENT key. Unkeyed, each
                                        // slot handed its `remember`ed state to whatever key inherited it: the focused
                                        // cap's `mimirFocusRing` (:688) then animated its gold border out over
                                        // `MimirMotion.FocusMs` on a key the player never selected -- the folio's ghost,
                                        // on the surface every text field in this app opens. `gesturePressed` (:532) and
                                        // the `MutableInteractionSource` (:528) rode along the same way, so a freshly
                                        // swapped-in cap could also appear mid-press.
                                        //
                                        // **Shift is deliberately NOT disturbed, and that is the whole reason this is
                                        // keyed on `keyId` rather than on the label or the value.** `keyboardRows` derives
                                        // `keyId` from the RAW layout value, never from the shift-uppercased `outputValue`
                                        // (:1113-1121), and `mimirKeyboardLayoutRows`' KDoc states it outright: shift
                                        // *"never affects a key's ID (only its rendered label/value)"*. So a shift press
                                        // recomposes labels in place with identity intact and the ring stays lit, while a
                                        // character-set cycle disposes only the caps whose identity genuinely changed. The
                                        // shared action caps (shift/backspace/abc/symbols/done) keep one `keyId` across
                                        // all three sets, so they keep their slot and their ring correctly.
                                        //
                                        // Fully qualified because this loop's own variable is named `key` and shadows it
                                        // -- `GamelistCarousel.kt:314` resolves the identical collision the same way.
                                        androidx.compose.runtime.key(key.keyId) {
                                            KeyCap(
                                                label = key.label,
                                                palette = palette,
                                                modifier = Modifier.weight(key.weight),
                                                keyHeight = keyHeight,
                                                // Fill mode: each hit area reaches the middle of the gap on every side, so the row has no dead
                                                // zones; a pressed ordinary key shows its character above the thumb (a dot for a secret).
                                                hitPadding = if (fill) panelGap / 2f else 0.dp,
                                                bubble = if (fill && key.action == null) (if (secretEdit != null) "\u2022" else key.label) else null,
                                                bubbleMeasurer = bubbleMeasurer,
                                                accent = key.action == KeyboardAction.Done,
                                                checkMark = key.action == KeyboardAction.Done,
                                                subdued = key.subdued,
                                                spacious = key.action == KeyboardAction.Space,
                                                selected = key.action == KeyboardAction.Shift && shiftOn,
                                                repeatOnHold = key.action == KeyboardAction.Back,
                                                horizontalDragHandler = if (key.action == KeyboardAction.Space && secretEdit != null) {
                                                    // Space-drag moves the FIELD's own caret by forwarded caret keys, one per step.
                                                    {
                                                        var lastSteps = 0
                                                        val handler: (Float) -> Unit = { totalDistanceDp ->
                                                            val steps = mimirKeyboardSpaceDragSteps(totalDistanceDp)
                                                            mimirSecretCaretEdits(lastSteps, steps).forEach(secretEdit)
                                                            lastSteps = steps
                                                        }
                                                        handler
                                                    }
                                                } else if (key.action == KeyboardAction.Space) {
                                                    {
                                                        var session = MimirKeyboardSpaceDragSession(value)
                                                        val handler: (Float) -> Unit = { totalDistanceDp ->
                                                            val update = advanceMimirKeyboardSpaceDrag(session, totalDistanceDp)
                                                            session = update.session
                                                            update.value?.let(onValueChange)
                                                            Unit
                                                        }
                                                        handler
                                                    }
                                                } else {
                                                    null
                                                },
                                                accessibilityLabel = keyboardAccessibilityLabel(key),
                                                controller = if (live) controller else null,
                                                keyDecorator = if (live) keyDecorator else null,
                                                keyId = key.keyId,
                                                onTap = keyAction,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (!live) Box(Modifier.matchParentSize().mimirPageTouchShield())
                }
            }
        }
    }
}

@Composable
private fun KeyboardCaption(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    inert: Boolean,
    targetLabel: String,
    targetLabelIntent: MimirTextIntent,
    palette: MimirThemePalette,
    compact: Boolean,
    fill: Boolean = false,
    modifier: Modifier = Modifier,
    showControllerLegend: Boolean,
    controllerLegendAction: String,
    secret: Boolean,
    revealable: Boolean = true,
    revealed: Boolean = false,
    onToggleReveal: () -> Unit = {},
    controller: MimirKeyboardController? = null,
    keyDecorator: MimirKeyboardKeyDecorator? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = MimirSpacing.xs, end = MimirSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KeyboardCaptionGap),
    ) {
        Text(
            text = "ᚱᚢᚾ",
            style = MimirTypeScale.Pill.copy(letterSpacing = 2.4.sp),
            color = keyboardEmber(palette),
            maxLines = 1,
        )
        KeyboardEditingDisplay(
            value = value,
            onValueChange = onValueChange,
            inert = inert,
            targetLabel = targetLabel,
            targetLabelIntent = targetLabelIntent,
            palette = palette,
            compact = compact,
            fill = fill,
            // An inert (manual) caption only ever holds dots and stays a password node however Show is set.
            secret = secret && (inert || !revealed),
            modifier = Modifier.weight(1f),
        )
        if (secret && revealable) {
            // The password's Show/Hide: a key like the others, so touch and the D-pad (UP from the top row) both reach it.
            KeyCap(
                label = if (revealed) MimirKeyboardHideLabel else MimirKeyboardShowLabel,
                palette = palette,
                modifier = Modifier.width(KeyboardRevealWidth),
                keyHeight = if (fill) KeyboardToolRowHeight else if (compact) KeyboardCompactEditingHeight else KeyboardEditingHeight,
                subdued = true,
                selected = revealed,
                accessibilityLabel = if (revealed) "Hide password" else "Show password",
                controller = controller,
                keyDecorator = keyDecorator,
                keyId = MimirKeyboardKeyIds.Reveal,
                onTap = onToggleReveal,
            )
        }
        if (showControllerLegend) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MimirSpacing.xs),
            ) {
                Text(
                    text = "B",
                    style = MimirTypeScale.Pill.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
                    color = palette.textPrimary,
                    maxLines = 1,
                )
                MimirChromeText(
                    text = controllerLegendAction,
                    style = MimirTypeScale.Pill.copy(letterSpacing = 0.6.sp),
                    color = palette.textMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun KeyboardEditingDisplay(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    inert: Boolean,
    targetLabel: String,
    targetLabelIntent: MimirTextIntent,
    palette: MimirThemePalette,
    compact: Boolean,
    fill: Boolean = false,
    secret: Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(KeyboardEditingRadius)
    // One dot per character, so the offsets (and the drawn caret) map one to one.
    val visualTransformation = remember(secret, inert) {
        if (secret && !inert) PasswordVisualTransformation() else VisualTransformation.None
    }
    val displayHeight = if (compact) KeyboardCompactEditingHeight else KeyboardEditingHeight
    // Fill mode: the display takes the height the keys and the tool row leave, as a two-line preview under the target label.
    val displayHeightModifier = if (fill) Modifier.fillMaxHeight() else Modifier.height(displayHeight)
    val selectionColors = remember(palette.accent) {
        TextSelectionColors(
            handleColor = palette.accent,
            backgroundColor = palette.accent.copy(alpha = 0.30f),
        )
    }
    // Drawn-beam caret: a rounded accent bar at cap-height with a faint molten-core
    // glow and a soft blink, drawn from the text layout — replaces the inserted "|" glyph. The field is
    // readOnly (the in-app keyboard drives it), but onTextLayout still fires so getCursorRect places the beam.
    // the infinite blink clock runs only while editing is active (collapsed caret), system animation
    // is enabled, the host is RESUMED, and shared ambient motion is INTERACTING/RECENT; otherwise a steady
    // full-alpha beam and no live infinite transition.
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val caretColor = palette.accent
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val caretBlinkRuns = mimirKeyboardCaretBlinkRuns(
        selectionCollapsed = value.selection.collapsed,
        reducedMotion = mimirReducedMotion(),
        ambientPhase = LocalMimirAmbientMotionPhase.current,
        lifecycleAtLeastResumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED),
        paneLive = LocalMimirMotionLive.current.value,
    )
    val caretBlink = if (caretBlinkRuns) {
        rememberInfiniteTransition(label = "caret").animateFloat(
            initialValue = 1f,
            targetValue = 0.18f,
            // MimirMotion.ShineMs each way (tween's default FastOutSlowIn easing): a soft fade, not a hard on/off.
            // the pulse rides the shine clock rather than a 560 of its own.
            animationSpec = infiniteRepeatable(tween(MimirMotion.ShineMs), RepeatMode.Reverse),
            label = "caret-alpha",
        ).value
    } else {
        1f
    }

    CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
        BasicTextField(
            value = value,
            onValueChange = { candidate ->
                // A secret caption (inert) has NO selection or diff path: its text is derived dots, its gestures
                // emit nothing, and the field's caret moves only by the ops the keys send.
                if (!inert) {
                    val selectionOnly = selectMimirKeyboardText(
                        value = value,
                        start = candidate.selection.start,
                        end = candidate.selection.end,
                    )
                    if (selectionOnly.selection != value.selection) onValueChange(selectionOnly)
                }
            },
            modifier = modifier
                .then(displayHeightModifier)
                .then(if (secret) Modifier.semantics { password() } else Modifier),
            readOnly = true,
            singleLine = !fill,
            maxLines = if (fill) 2 else 1,
            visualTransformation = visualTransformation,
            textStyle = MimirTypeScale.Row.copy(
                color = keyboardKeyInk(palette),
                fontSize = if (fill) 17.sp else if (compact) 13.sp else 15.sp,
                letterSpacing = 0.sp,
            ),
            cursorBrush = SolidColor(Color.Unspecified),
            onTextLayout = { textLayout = it },
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(displayHeightModifier)
                        .clip(shape)
                        .background(keyBrush(palette, pressed = false, selected = false))
                        .border(keyBorder(palette, pressed = false, selected = false), shape)
                        .padding(horizontal = MimirSpacing.sm),
                    contentAlignment = if (fill) Alignment.TopStart else Alignment.CenterStart,
                ) {
                    if (fill) {
                        MimirIntentText(
                            intent = targetLabelIntent,
                            style = MimirTypeScale.Pill.copy(letterSpacing = 0.sp),
                            color = palette.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (value.text.isEmpty() && !fill) {
                        MimirIntentText(
                            intent = targetLabelIntent,
                            style = MimirTypeScale.Pill.copy(letterSpacing = 0.sp),
                            color = palette.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = MimirSpacing.sm),
                        )
                    }
                    Box(
                        modifier = (if (fill) Modifier.padding(top = KeyboardPreviewLabelHeight) else Modifier).drawWithContent {
                            drawContent()
                            val layout = textLayout
                            if (layout != null && value.selection.collapsed) {
                                val offset = value.selection.start.coerceIn(0, value.text.length)
                                val cursor = layout.getCursorRect(offset)
                                val a = caretBlink
                                val barW = 2.5.dp.toPx()
                                val top = cursor.top
                                val h = cursor.height
                                val x = cursor.left
                                // Faint molten-core glow: two translucent halos behind the beam.
                                drawRoundRect(
                                    color = caretColor.copy(alpha = 0.12f * a),
                                    topLeft = Offset(x - 3.5.dp.toPx(), top - 1.dp.toPx()),
                                    size = Size(barW + 7.dp.toPx(), h + 2.dp.toPx()),
                                    cornerRadius = CornerRadius(5.dp.toPx()),
                                )
                                drawRoundRect(
                                    color = caretColor.copy(alpha = 0.22f * a),
                                    topLeft = Offset(x - 1.5.dp.toPx(), top),
                                    size = Size(barW + 3.dp.toPx(), h),
                                    cornerRadius = CornerRadius(3.dp.toPx()),
                                )
                                // The beam.
                                drawRoundRect(
                                    color = caretColor.copy(alpha = a),
                                    topLeft = Offset(x, top),
                                    size = Size(barW, h),
                                    cornerRadius = CornerRadius(barW / 2f),
                                )
                            }
                        },
                    ) {
                        innerTextField()
                    }
                }
            },
        )
    }
}

/** The Done key's drawn check, tagged for the tests that ask whether it is a drawing and where it stands. */
const val MimirKeyboardDoneCheckTag = "mimir-keyboard-done-check"

/** The check's three points as fractions of its square seat: the short stroke down, then the long stroke up. */
internal val KeyboardDoneCheckPoints: List<Pair<Float, Float>> = listOf(0.12f to 0.54f, 0.38f to 0.80f, 0.90f to 0.22f)

/**
 * The Done key's check, DRAWN as one stroked vector path with round caps and joins. The font's U+2713 is not in the UI face, so it fell back to a
 * symbol font and read as a root sign; a path is the same mark on every device and every font.
 */
@Composable
private fun KeyboardDoneCheck(color: Color, seat: Dp) {
    Canvas(Modifier.size(seat).testTag(MimirKeyboardDoneCheckTag)) {
        val path = Path().apply {
            KeyboardDoneCheckPoints.forEachIndexed { index, (x, y) ->
                if (index == 0) moveTo(size.width * x, size.height * y) else lineTo(size.width * x, size.height * y)
            }
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = size.minDimension * 0.15f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/** One tappable key. Touch target ≥ [MimirSpacing.minTarget]; the primary (Done) key is the warm etched chip: the dark plate, an accent rim and accent ink. */
@Composable
private fun KeyCap(
    label: String,
    palette: MimirThemePalette,
    modifier: Modifier = Modifier,
    keyHeight: Dp = MimirSpacing.minTarget,
    accent: Boolean = false,
    subdued: Boolean = false,
    spacious: Boolean = false,
    selected: Boolean = false,
    repeatOnHold: Boolean = false,
    horizontalDragHandler: (() -> (totalDistanceDp: Float) -> Unit)? = null,
    accessibilityLabel: String = label,
    /** Draws the key's mark as a check path instead of its label (the Done key). */
    checkMark: Boolean = false,
    controller: MimirKeyboardController? = null,
    keyDecorator: MimirKeyboardKeyDecorator? = null,
    keyId: MimirKeyboardKeyId? = null,
    /** Fill mode: how far the hit area reaches past the drawn key on every side (half the gap), so no touch lands in a dead zone. */
    hitPadding: Dp = 0.dp,
    /** Fill mode: what the press bubble above the key shows, or null for none. A secret key's is a dot, never the character. */
    bubble: String? = null,
    bubbleMeasurer: TextMeasurer? = null,
    onTap: () -> Unit,
) {
    val hostView = LocalView.current
    val hapticFeedback = LocalMimirKeyboardHapticFeedback.current
    // All three `inputModifier` branches now drive their own press state through a pointer gesture — the
    // `else` branch used to be a `clickable` reading an `interactionSource`, which is what planted the
    // second focus node (see the comment on that branch).
    var gesturePressed by remember { mutableStateOf(false) }
    val latestOnTap = rememberUpdatedState(onTap)
    val latestHapticFeedback = rememberUpdatedState(hapticFeedback)
    val activateWithHaptic = {
        activateMimirKeyboardKey(
            emitHaptic = {
                val emit = latestHapticFeedback.value
                if (emit != null) {
                    emit()
                } else {
                    hostView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
            },
            action = latestOnTap.value,
        )
    }
    val latestHorizontalDragHandler = rememberUpdatedState(horizontalDragHandler)
    val pressed = gesturePressed
    val shape = RoundedCornerShape(KeyboardKeyRadius)
    val keyInk = keyboardKeyInk(palette)
    val inputModifier = when {
        repeatOnHold -> Modifier
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = accessibilityLabel
                onClick(label = accessibilityLabel) {
                    activateWithHaptic()
                    true
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    gesturePressed = true
                    down.consume()
                    activateWithHaptic()

                    val downAt = android.os.SystemClock.uptimeMillis()
                    var nextRepeatAt = downAt + MimirKeyboardDeleteFirstRepeatDelayMs
                    try {
                        while (true) {
                            val remaining = (nextRepeatAt - android.os.SystemClock.uptimeMillis())
                                .coerceAtLeast(1L)
                            val event = withTimeoutOrNull(remaining) {
                                awaitPointerEvent(PointerEventPass.Initial)
                            }
                            if (event == null) {
                                latestOnTap.value()
                                val heldFor = nextRepeatAt - downAt
                                nextRepeatAt += mimirKeyboardDeleteRepeatIntervalMillis(heldFor)
                                continue
                            }
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || event.changes.count { it.pressed } > 1) break
                            change.consume()
                            if (!change.pressed) break
                        }
                    } finally {
                        gesturePressed = false
                    }
                }
            }
        horizontalDragHandler != null -> Modifier
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = accessibilityLabel
                onClick(label = accessibilityLabel) {
                    activateWithHaptic()
                    true
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val drag = latestHorizontalDragHandler.value?.invoke()
                    val tap = activateWithHaptic
                    val downPosition = down.position
                    val horizontalSlop = viewConfiguration.touchSlop
                    var dragging = false
                    var released = false
                    gesturePressed = true
                    down.consume()
                    try {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || event.changes.count { it.pressed } > 1) break
                            val totalX = change.position.x - downPosition.x
                            val totalY = change.position.y - downPosition.y
                            if (
                                !dragging &&
                                mimirKeyboardGestureCrossedTouchSlop(totalX, totalY, horizontalSlop)
                            ) {
                                dragging = true
                            }
                            if (dragging) {
                                drag?.invoke(totalX / density)
                            }
                            change.consume()
                            if (!change.pressed) {
                                released = true
                                break
                            }
                        }
                        if (released && !dragging) tap()
                    } finally {
                        gesturePressed = false
                    }
                }
            }
        // A raw pointer gesture, never `clickable`: `clickable` builds an `AbstractClickableNode`, which
        // constructs a `FocusableNode` and therefore a real `FocusTargetModifierNode` — a SECOND Compose
        // focus target planted on the very same `Box` that receives `decoration?.focusModifier` below
        // (`:683`), which is `Modifier.mimirFocusable` handed in by `MimirKeyboardFocusBinding.decorateKey`.
        //
        // This `when` branches on key BEHAVIOUR (`repeatOnHold`, `horizontalDragHandler`), never on whether
        // a controller is attached — so before this change every ordinary letter key carried both focus
        // systems on every host that supplies a decorator (`MimirAppHost`, `OnboardingFlow`,
        // `HomeCustomEnrichPicker`, `HomeTomeLibraryOverlay`, `ArtworkConnectOverlay`).
        // `tools/mimir_ui_invariants_gate.py`'s F1 rules grep only for the literals `.focusable(` and
        // `focusTarget(`, so that violation was invisible to the gate.
        //
        // The shape below is the two branches above, minus their custom drag/repeat handling: the same
        // `semantics` block (which is what carries the click ACTION and the descendant merge that
        // `clickable` used to publish to accessibility services) and the same `pointerInput`. `onTap` only
        // — registering `onDoubleTap` would defer EVERY single tap by the platform double-tap timeout.
        //
        // `onPress`/`tryAwaitRelease` replaces what `interactionSource` + `collectIsPressedAsState` used to
        // give this branch, so the key's press visual is unchanged; `pressed` now reads `gesturePressed` for
        // all three branches. Deliberately NO focus write on tap — these keys are focus-driven by the
        // controller and a touch here is a pure activation, which is exactly the prior `clickable`
        // behaviour. Changing that would be a behaviour change, not a fix.
        else -> Modifier
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = accessibilityLabel
                onClick(label = accessibilityLabel) {
                    activateWithHaptic()
                    true
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        gesturePressed = true
                        try {
                            tryAwaitRelease()
                        } finally {
                            gesturePressed = false
                        }
                    },
                    onTap = { activateWithHaptic() },
                )
            }
    }
    val decoration = if (keyDecorator != null && keyId != null) {
        keyDecorator(keyId, activateWithHaptic)
    } else {
        null
    }

    // The drawn key. [visualModifier] places it; [input] is the pointer handling, which sits on the drawn Box (as always)
    // or, in fill mode, on the larger hit Box around it.
    val drawnKey: @Composable (Modifier, Modifier) -> Unit = { visualModifier, inputModifier ->
        Box(
            modifier = visualModifier
                .then(decoration?.focusModifier ?: Modifier)
                .height(keyHeight)
                .shadow(
                    elevation = when {
                        pressed -> 4.dp
                        selected -> 2.dp
                        else -> 0.dp
                    },
                    shape = shape,
                    clip = false,
                    ambientColor = keyboardEmber(palette).copy(alpha = if (pressed) 0.30f else 0.16f),
                    spotColor = keyboardEmber(palette).copy(alpha = if (pressed) 0.42f else 0.20f),
                )
                .graphicsLayer {
                    scaleX = if (pressed) PressedKeyScale else 1f
                    scaleY = if (pressed) PressedKeyScale else 1f
                    translationY = if (pressed) PressedKeyLift.toPx() else 0f
                }
                // The one ring on a key (ring migration, 2026-09-28): the dense key ring, after the press layer so it
                // moves with the cap, before the clip so it can reach past it. Keyed on the key's own id.
                .then(
                    if (decoration != null) {
                        Modifier.mimirSelectionRing(
                            focused = decoration.focused,
                            identity = keyId ?: label,
                            shape = MimirRingShape.Key(KeyboardKeyRadius),
                            // The Done key wears the etched primary below: its rim and this ring are one band. Every other key has no rim to hand over.
                            edgeFlush = accent,
                        )
                    } else {
                        Modifier
                    },
                )
                .clip(shape)
                .then(
                    if (accent) {
                        // The Done key is the WARM ETCHED primary (every gold or gradient primary is the dark plate with an accent rim and accent ink, never a
                        // fill; the sweep found this one still a filled gold gradient): [mimirEtchedChipSurface] cut to the key's own rounded shape. A focused Done hands its
                        // rim to the ring on its plate, which is then its one edge. A press washes the plate with the accent, the way an ordinary key's press does.
                        Modifier
                            .mimirEtchedChipSurface(palette, focused = decoration?.focused == true, primary = true, corner = KeyboardKeyRadius, edgeFlush = true)
                            .then(
                                if (pressed) {
                                    Modifier.background(Brush.linearGradient(listOf(keyPressedTop(palette), keyPressedBottom(palette))))
                                } else {
                                    Modifier
                                },
                            )
                    } else {
                        Modifier
                            .background(keyBrush(palette = palette, pressed = pressed, selected = selected))
                            .border(keyBorder(palette = palette, pressed = pressed, selected = selected), shape)
                    },
                )
                .then(inputModifier),
            contentAlignment = Alignment.Center,
        ) {
            if (checkMark) {
                KeyboardDoneCheck(
                    color = if (accent) palette.etchedChipInk(primary = true) else keyInk,
                    seat = (keyHeight * 0.42f).coerceIn(12.dp, 20.dp),
                )
            } else {
                Text(
                    text = label,
                    style = (if (keyHeight < 40.dp) MimirTypeScale.Pill else MimirTypeScale.Rail).copy(
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = if (spacious) 3.sp else 0.3.sp,
                    ),
                    color = when {
                        accent -> palette.etchedChipInk(primary = true)
                        selected -> keyboardEmber(palette)
                        subdued -> palette.textMuted
                        else -> keyInk
                    },
                    maxLines = 1,
                )
            }
        }
    }

    if (hitPadding > 0.dp) {
        Box(
            modifier = modifier
                .height(keyHeight + hitPadding * 2f)
                .then(inputModifier)
                .drawWithContent {
                    drawContent()
                    // Draw phase only: the pressed state is read here, so a press redraws this node and recomposes nothing.
                    if (pressed && bubble != null && bubbleMeasurer != null) {
                        drawPressBubble(bubble, bubbleMeasurer, palette, keyInk)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            drawnKey(Modifier.padding(hitPadding).fillMaxWidth(), Modifier)
        }
    } else {
        drawnKey(modifier, inputModifier)
    }
}

/** The bubble above a pressed key: the key's character in a raised plate, over the row above it, drawn in the key's own hit Box. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPressBubble(
    text: String,
    measurer: TextMeasurer,
    palette: MimirThemePalette,
    ink: Color,
) {
    val layout = measurer.measure(text, MimirTypeScale.Rail.copy(fontSize = 26.sp, fontWeight = FontWeight.SemiBold))
    val pad = 8.dp.toPx()
    val width = maxOf(size.width, layout.size.width + pad * 2f)
    val height = size.height * 1.15f
    val left = (size.width - width) / 2f
    val top = -height - 2.dp.toPx()
    val corner = CornerRadius(10.dp.toPx())
    drawRoundRect(palette.surfaceRaised, topLeft = Offset(left, top), size = Size(width, height), cornerRadius = corner)
    drawRoundRect(
        palette.accent.copy(alpha = 0.7f),
        topLeft = Offset(left, top),
        size = Size(width, height),
        cornerRadius = corner,
        style = Stroke(width = 1.dp.toPx()),
    )
    drawText(
        layout,
        color = ink,
        topLeft = Offset(left + (width - layout.size.width) / 2f, top + (height - layout.size.height) / 2f),
    )
}

/** A synthetic key for a tool-row button, so its tap goes through the same table as a key's. */
private fun toolKey(action: KeyboardAction): KeyboardKey = KeyboardKey(
    value = action.name.lowercase(),
    label = action.name,
    action = action,
    keyId = mimirKeyboardKeyId(action.name.lowercase(), action),
    weight = 1f,
    subdued = true,
)

/**
 * The tool row between the preview and the keys (fill mode): the caret one place at a time (the same op as the Space drag),
 * Select all, Paste, PAD and Hide (Hide only where the host gave one). A secret field's row carries the caret, Paste and Hide
 * only: no Select all (it would read the field) and no PAD. Touch only, so no key is registered with a controller.
 */
@Composable
private fun KeyboardToolRow(
    palette: MimirThemePalette,
    gap: Dp,
    secret: Boolean,
    trackpadOffered: Boolean,
    onCaretLeft: () -> Unit,
    onCaretRight: () -> Unit,
    onSelectAll: () -> Unit,
    onPaste: () -> Unit,
    onPad: () -> Unit,
    onHide: (() -> Unit)?,
) {
    @Composable
    fun RowScope.tool(label: String, weight: Float, description: String, onTap: () -> Unit) {
        KeyCap(
            label = label,
            palette = palette,
            modifier = Modifier.weight(weight),
            keyHeight = KeyboardToolRowHeight - gap,
            subdued = true,
            accessibilityLabel = description,
            hitPadding = gap / 2f,
            onTap = onTap,
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth().height(KeyboardToolRowHeight).testTag(MimirKeyboardToolRowTag),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        tool("\u25C0", 1f, "Cursor left", onCaretLeft)
        tool("\u25B6", 1f, "Cursor right", onCaretRight)
        if (!secret) tool("Select all", 1.8f, "Select all", onSelectAll)
        tool("Paste", 1.5f, "Paste", onPaste)
        if (trackpadOffered && !secret) tool("PAD", 1.3f, "Trackpad", onPad)
        // Only where the host has a close that does not commit: never a stand-in for Done.
        if (onHide != null) tool("Hide", 1.3f, "Hide keyboard", onHide)
    }
}

internal const val MimirKeyboardToolRowTag = "mimir-keyboard-tool-row"

/**
 * The keyboard panel's ground: the theme's own opaque surfaces, raised at the top and base at the bottom, at FULL alpha. It was 0.66 / 0.78 (0.88 / 0.92 on a bright field), so
 * whatever the host drew behind the keyboard (Chrome's suggestion list on the Bottom, a page's tiles) read through between and behind the keys and looked messy (sweep 10-09). The
 * keys keep their own translucent plates, which now sit on a ground that is the same under every host.
 */
private fun keyboardPanelBrush(palette: MimirThemePalette): Brush =
    Brush.verticalGradient(listOf(palette.surfaceRaised, palette.surfaceBase))

private fun keyboardPanelBorder(palette: MimirThemePalette): Color =
    palette.accent.copy(alpha = if (palette.usesBrightField) 0.26f else 0.18f)

private fun keyBrush(
    palette: MimirThemePalette,
    pressed: Boolean,
    selected: Boolean,
): Brush {
    val colors = when {
        pressed -> listOf(keyPressedTop(palette), keyPressedBottom(palette))
        selected -> listOf(keySelectedTop(palette), keySelectedBottom(palette))
        else -> listOf(keyRestTop(palette), keyRestBottom(palette))
    }
    return Brush.linearGradient(colors)
}

private fun keyBorder(
    palette: MimirThemePalette,
    pressed: Boolean,
    selected: Boolean,
): BorderStroke =
    BorderStroke(
        width = KeyboardHairlineWidth,
        color = when {
            pressed || selected -> keyboardEmber(palette)
            else -> keyRestBorder(palette)
        },
    )

private fun keyboardKeyInk(palette: MimirThemePalette): Color = palette.textPrimary

private fun keyRestTop(palette: MimirThemePalette): Color =
    palette.surfaceRaised.copy(alpha = if (palette.usesBrightField) 0.94f else 0.85f)

private fun keyRestBottom(palette: MimirThemePalette): Color =
    palette.surfaceBase.copy(alpha = if (palette.usesBrightField) 0.96f else 0.92f)

private fun keySelectedTop(palette: MimirThemePalette): Color =
    keyRestTop(palette)

private fun keySelectedBottom(palette: MimirThemePalette): Color =
    keyboardEmber(palette).copy(alpha = 0.30f)

private fun keyPressedTop(palette: MimirThemePalette): Color =
    palette.accent.copy(alpha = 0.24f)

private fun keyPressedBottom(palette: MimirThemePalette): Color =
    palette.accent.copy(alpha = 0.42f)

private fun keyRestBorder(palette: MimirThemePalette): Color =
    palette.outline.copy(alpha = if (palette.usesBrightField) 0.55f else 0.72f)

/** The keyboard's interactive accent = the theme accent (already per-theme, no per-theme branch needed). */
private fun keyboardEmber(palette: MimirThemePalette): Color = palette.accent

/**
 * Pure gate for the keyboard caret blink clock. True only when a collapsed caret may animate; callers must
 * keep [rememberInfiniteTransition] off the composition path whenever this returns false.
 */
internal fun mimirKeyboardCaretBlinkRuns(
    selectionCollapsed: Boolean,
    reducedMotion: Boolean,
    ambientPhase: AmbientMotionPhase,
    lifecycleAtLeastResumed: Boolean,
    /** D24: false inside a parked workspace pane: a keyboard nobody can see runs no endless clock. True (the default) everywhere else. */
    paneLive: Boolean = true,
): Boolean =
    selectionCollapsed &&
        !reducedMotion &&
        lifecycleAtLeastResumed &&
        paneLive &&
        (ambientPhase == AmbientMotionPhase.INTERACTING || ambientPhase == AmbientMotionPhase.RECENT)

/** Emits one semantic keyboard tick before applying the accepted key action. */
internal fun activateMimirKeyboardKey(
    emitHaptic: () -> Unit,
    action: () -> Unit,
) {
    emitHaptic()
    action()
}

internal fun synchronizeMimirKeyboardText(
    value: TextFieldValue,
    externalText: String,
): TextFieldValue =
    if (value.text == externalText) {
        value
    } else {
        TextFieldValue(text = externalText, selection = TextRange(externalText.length))
    }

internal fun placeMimirKeyboardCursor(
    value: TextFieldValue,
    offset: Int,
): TextFieldValue =
    value.copy(
        selection = TextRange(value.text.codePointBoundaryAtOrBefore(offset)),
        composition = null,
    )

internal fun selectMimirKeyboardText(
    value: TextFieldValue,
    start: Int,
    end: Int,
): TextFieldValue {
    if (start == end) return placeMimirKeyboardCursor(value, start)

    val selection = if (start < end) {
        TextRange(
            start = value.text.codePointBoundaryAtOrBefore(start),
            end = value.text.codePointBoundaryAtOrAfter(end),
        )
    } else {
        TextRange(
            start = value.text.codePointBoundaryAtOrAfter(start),
            end = value.text.codePointBoundaryAtOrBefore(end),
        )
    }
    return value.copy(selection = selection, composition = null)
}

internal fun insertAtMimirKeyboardSelection(
    value: TextFieldValue,
    insertion: String,
): TextFieldValue {
    val replacementStart = value.text.codePointBoundaryAtOrBefore(value.selection.min)
    val replacementEnd = if (value.selection.collapsed) {
        replacementStart
    } else {
        value.text.codePointBoundaryAtOrAfter(value.selection.max)
    }
    val nextText = value.text.replaceRange(replacementStart, replacementEnd, insertion)
    return TextFieldValue(
        text = nextText,
        selection = TextRange(replacementStart + insertion.length),
    )
}

internal fun backspaceAtMimirKeyboardSelection(value: TextFieldValue): TextFieldValue {
    if (!value.selection.collapsed) return insertAtMimirKeyboardSelection(value, insertion = "")

    val caret = value.text.codePointBoundaryAtOrBefore(value.selection.start)
    if (caret == 0) return placeMimirKeyboardCursor(value, offset = 0)

    val previous = value.text.codePointStartBefore(caret)
    val nextText = value.text.removeRange(previous, caret)
    return TextFieldValue(text = nextText, selection = TextRange(previous))
}

internal fun pasteAtMimirKeyboardSelection(
    value: TextFieldValue,
    clipboardText: String?,
): TextFieldValue =
    clipboardText
        ?.takeIf(String::isNotEmpty)
        ?.let { insertAtMimirKeyboardSelection(value, it) }
        ?: value

internal fun mimirKeyboardDeleteRepeatIntervalMillis(totalHeldMillis: Long): Long =
    if (totalHeldMillis <= MimirKeyboardDeleteAccelerationAtMs) {
        MimirKeyboardDeleteInitialRepeatIntervalMs
    } else {
        MimirKeyboardDeleteAcceleratedRepeatIntervalMs
    }

internal data class MimirKeyboardSpaceDragSession(
    val origin: TextFieldValue,
    val lastValue: TextFieldValue = origin,
    val lastSteps: Int = 0,
    val lastDirection: Int = 0,
)

internal data class MimirKeyboardSpaceDragUpdate(
    val session: MimirKeyboardSpaceDragSession,
    val value: TextFieldValue?,
)

internal fun advanceMimirKeyboardSpaceDrag(
    session: MimirKeyboardSpaceDragSession,
    totalDistanceDp: Float,
): MimirKeyboardSpaceDragUpdate {
    val steps = mimirKeyboardSpaceDragSteps(totalDistanceDp)
    if (steps == session.lastSteps) return MimirKeyboardSpaceDragUpdate(session, value = null)

    val direction = steps.compareTo(0)
    val resolved = if (
        steps == 0 &&
        !session.origin.selection.collapsed &&
        session.lastDirection != 0
    ) {
        val boundary = if (session.lastDirection < 0) {
            session.origin.selection.min
        } else {
            session.origin.selection.max
        }
        session.origin.copy(selection = TextRange(boundary), composition = null)
    } else if (steps == 0 && session.lastDirection != 0) {
        session.origin.copy(composition = null)
    } else {
        moveMimirKeyboardCursorBySpaceDrag(session.origin, totalDistanceDp)
    }
    val nextSession = session.copy(
        lastValue = resolved,
        lastSteps = steps,
        lastDirection = direction.takeIf { it != 0 } ?: session.lastDirection,
    )
    return MimirKeyboardSpaceDragUpdate(
        session = nextSession,
        value = resolved.takeIf { it != session.lastValue },
    )
}

internal fun moveMimirKeyboardCursorBySpaceDrag(
    value: TextFieldValue,
    totalDistanceDp: Float,
): TextFieldValue {
    val steps = mimirKeyboardSpaceDragSteps(totalDistanceDp)
    if (steps == 0) return value

    val direction = if (steps > 0) 1 else -1
    val startingOffset = when {
        value.selection.collapsed -> value.selection.start
        direction < 0 -> value.selection.min
        else -> value.selection.max
    }
    val movementSteps = if (value.selection.collapsed) abs(steps) else abs(steps) - 1
    var cursor = value.text.codePointBoundaryAtOrBefore(startingOffset)
    repeat(movementSteps) {
        cursor = if (direction < 0) {
            if (cursor == 0) 0 else value.text.codePointStartBefore(cursor)
        } else {
            value.text.codePointEndAfter(cursor)
        }
    }
    return value.copy(selection = TextRange(cursor), composition = null)
}

internal fun mimirKeyboardSpaceDragSteps(totalDistanceDp: Float): Int =
    (totalDistanceDp / MimirKeyboardSpaceDragStepDp).toInt()

internal fun mimirKeyboardGestureCrossedTouchSlop(
    totalX: Float,
    totalY: Float,
    touchSlop: Float,
): Boolean =
    Offset(totalX, totalY).getDistance() > touchSlop

private fun String.codePointBoundaryAtOrBefore(offset: Int): Int {
    val clamped = offset.coerceIn(0, length)
    return if (
        clamped in 1 until length &&
        this[clamped - 1].isHighSurrogate() &&
        this[clamped].isLowSurrogate()
    ) {
        clamped - 1
    } else {
        clamped
    }
}

private fun String.codePointBoundaryAtOrAfter(offset: Int): Int {
    val clamped = offset.coerceIn(0, length)
    return if (
        clamped in 1 until length &&
        this[clamped - 1].isHighSurrogate() &&
        this[clamped].isLowSurrogate()
    ) {
        clamped + 1
    } else {
        clamped
    }
}

private fun String.codePointStartBefore(offset: Int): Int =
    if (
        offset >= 2 &&
        this[offset - 2].isHighSurrogate() &&
        this[offset - 1].isLowSurrogate()
    ) {
        offset - 2
    } else {
        offset - 1
    }

private fun String.codePointEndAfter(offset: Int): Int {
    val start = codePointBoundaryAtOrBefore(offset)
    if (start >= length) return length
    return if (
        start + 1 < length &&
        this[start].isHighSurrogate() &&
        this[start + 1].isLowSurrogate()
    ) {
        start + 2
    } else {
        start + 1
    }
}

/**
 * What kind of field a keyboard is typing into, where that changes WHICH existing layout it opens on.
 * Added for the system-wide input method (`MimirInputMethodService`), which meets other apps'
 * number, phone, email and URL fields; every in-app call site keeps [TEXT], which is the pre-existing behaviour.
 *
 * Nothing here adds a character set, so the R2 cycle ([MimirKeyboardCharacterSet.next]) is untouched.
 *
 *  - [TEXT]: letters, shifted when empty -- the shipping keyboard.
 *  - [NUMBER]: opens on the existing [MimirKeyboardCharacterSet.NUMBERS] grid (number, phone and date fields).
 *  - [EMAIL]: the letter layer with `@` and `.` on the bottom row, so neither needs the symbols page.
 *  - [URI]: the letter layer with `/` and `.` on the bottom row. Neither starts shifted.
 */
enum class MimirKeyboardFieldKind {
    TEXT, NUMBER, EMAIL, URI;

    internal fun startingCharacterSet(): MimirKeyboardCharacterSet =
        if (this == NUMBER) MimirKeyboardCharacterSet.NUMBERS else MimirKeyboardCharacterSet.ALPHA

    internal fun letterRows(): List<List<String>> = when (this) {
        EMAIL -> EMAIL_LETTER_ROWS
        URI -> URI_LETTER_ROWS
        else -> LETTER_ROWS
    }
}

/**
 * The keyboard's three character sets (R2 cycles alphabet → numbers → symbols → alphabet). [ALPHA] and [SYMBOLS] are the pre-existing two touch-reachable
 * pages (unchanged content/behavior); [NUMBERS] is new and reachable via R2's cycle (touch has no
 * dedicated mode key onto it — see the `onSymbolsOn`/`onAbc` comment at their call site — since its
 * digits are already present on both other pages; it exists as a controller-only fast path to a bigger,
 * uncluttered digit grid, e.g. for PIN-shaped input).
 */
internal enum class MimirKeyboardCharacterSet {
    ALPHA, NUMBERS, SYMBOLS;

    fun next(): MimirKeyboardCharacterSet = entries[(ordinal + 1) % entries.size]
}

internal fun mimirKeyboardLayoutKeyIds(
    shiftOn: Boolean,
    characterSet: MimirKeyboardCharacterSet,
): List<MimirKeyboardKeyId> =
    keyboardRows(shiftOn = shiftOn, characterSet = characterSet).flatten().map { it.keyId }

/**
 * The keyboard's row structure as bare key-ID strings, in row/column order — what
 * [com.gruning.mimir.rewrite.core.input.MimirKeyboardFocusBinding.registerRowLayout] needs to move
 * LEFT/RIGHT by row index instead of pixel geometry. `shiftOn` is a
 * required parameter of the underlying [keyboardRows] but never affects a key's ID (only its rendered
 * label/value — see `keyboardRows`'s `outputValue` vs `keyId` computation), so it is fixed to `false`
 * here: this function's result depends on [characterSet] alone, matching the fact that shift never
 * changes which row a key lives in or its position within it.
 */
internal fun mimirKeyboardLayoutRows(
    characterSet: MimirKeyboardCharacterSet,
    fieldKind: MimirKeyboardFieldKind = MimirKeyboardFieldKind.TEXT,
    trackpad: Boolean = false,
    toolRow: Boolean = false,
): List<List<String>> =
    keyboardRows(shiftOn = false, characterSet = characterSet, fieldKind = fieldKind, trackpad = trackpad, toolRow = toolRow)
        .map { row -> row.map { it.keyId.value } }

internal fun keyboardRows(
    shiftOn: Boolean,
    characterSet: MimirKeyboardCharacterSet,
    fieldKind: MimirKeyboardFieldKind = MimirKeyboardFieldKind.TEXT,
    /** D34b: add the Trackpad key beside Paste on the bottom row. Off (the default) leaves the layout exactly as it was. */
    trackpad: Boolean = false,
    /** Fill mode: Paste and PAD live in the tool row, so the key rows drop them. */
    toolRow: Boolean = false,
): List<List<KeyboardKey>> {
    val baseLayout = when (characterSet) {
        MimirKeyboardCharacterSet.ALPHA -> fieldKind.letterRows()
        MimirKeyboardCharacterSet.NUMBERS -> NUMBER_ROWS
        MimirKeyboardCharacterSet.SYMBOLS -> SYMBOL_ROWS
    }
    val withTrackpad = if (trackpad) {
        baseLayout.map { row -> if ("paste" in row) row.flatMap { if (it == "paste") listOf(it, "trackpad") else listOf(it) } else row }
    } else {
        baseLayout
    }
    val layout = if (toolRow) withTrackpad.map { row -> row.filter { it != "paste" && it != "trackpad" } } else withTrackpad
    val isAlpha = characterSet == MimirKeyboardCharacterSet.ALPHA
    return layout.map { row ->
        row.map { value ->
            val action = KeyboardAction.forValue(value)
            val outputValue = if (action == null && shiftOn && isAlpha) value.uppercase() else value
            KeyboardKey(
                value = outputValue,
                label = keyboardKeyLabel(value = value, action = action, shiftOn = shiftOn, isAlpha = isAlpha),
                action = action,
                keyId = mimirKeyboardKeyId(value, action),
                weight = keyboardKeyWeight(action),
                subdued = action != null && action != KeyboardAction.Done,
            )
        }
    }
}

private fun keyboardKeyLabel(
    value: String,
    action: KeyboardAction?,
    shiftOn: Boolean,
    isAlpha: Boolean,
): String =
    when (action) {
        KeyboardAction.Shift -> "ᛏ"
        KeyboardAction.Symbols -> "?#"
        KeyboardAction.Abc -> "ABC"
        KeyboardAction.Back -> "⌫"
        KeyboardAction.Paste -> "PASTE"
        KeyboardAction.Space -> "ᚹᛖᛚᛚ"
        // The Done key's mark is DRAWN (a path, see [KeyboardDoneCheck]); the font's U+2713 fell back to a symbol face and read as a root sign.
        KeyboardAction.Done -> "DONE"
        KeyboardAction.Trackpad -> "PAD"
        null -> if (shiftOn && isAlpha) value.uppercase() else value
    }

private fun keyboardKeyWeight(action: KeyboardAction?): Float =
    when (action) {
        KeyboardAction.Shift,
        KeyboardAction.Back,
        KeyboardAction.Symbols,
        KeyboardAction.Abc -> 1.5f
        KeyboardAction.Paste, KeyboardAction.Trackpad -> 1.7f
        KeyboardAction.Space -> 5f
        KeyboardAction.Done -> 1.8f
        null -> 1f
    }

private fun mimirKeyboardKeyId(value: String, action: KeyboardAction?): MimirKeyboardKeyId =
    when (action) {
        KeyboardAction.Shift -> MimirKeyboardKeyIds.Shift
        KeyboardAction.Symbols -> MimirKeyboardKeyIds.Symbols
        KeyboardAction.Abc -> MimirKeyboardKeyIds.Abc
        KeyboardAction.Back -> MimirKeyboardKeyIds.Back
        KeyboardAction.Paste -> MimirKeyboardKeyIds.Paste
        KeyboardAction.Space -> MimirKeyboardKeyIds.Space
        KeyboardAction.Done -> MimirKeyboardKeyIds.Done
        KeyboardAction.Trackpad -> MimirKeyboardKeyIds.Trackpad
        null -> MimirKeyboardKeyIds.character(value)
    }

private fun keyboardAccessibilityLabel(key: KeyboardKey): String =
    when (key.action) {
        KeyboardAction.Back -> "Backspace"
        KeyboardAction.Space -> "Space"
        KeyboardAction.Paste -> "Paste"
        KeyboardAction.Done -> "Done"
        KeyboardAction.Shift -> "Shift"
        KeyboardAction.Symbols -> "Symbols"
        KeyboardAction.Abc -> "Letters"
        KeyboardAction.Trackpad -> "Trackpad"
        null -> key.label
    }

internal fun keyTapAction(
    key: KeyboardKey,
    value: TextFieldValue,
    clipboard: androidx.compose.ui.platform.ClipboardManager,
    shiftOn: Boolean,
    characterSet: MimirKeyboardCharacterSet,
    onShiftToggle: () -> Unit,
    onSymbolsOn: () -> Unit,
    onAbc: () -> Unit,
    onShiftOffAfterChar: () -> Unit,
    emitEdit: (TextFieldValue) -> Unit,
    onDone: () -> Unit,
    secretEdit: ((MimirSecretEdit) -> Unit)? = null,
    fieldOps: MimirKeyboardFieldOps? = null,
    onTrackpad: () -> Unit = {},
): () -> Unit = {
    if (secretEdit != null) {
        // A SECRET field: the key becomes one discrete edit op, handed straight to the sink. Nothing typed is
        // written into [value], a remembered state or a buffer, and [value] is never read back.
        when (key.action) {
            KeyboardAction.Shift -> onShiftToggle()
            KeyboardAction.Symbols -> onSymbolsOn()
            KeyboardAction.Abc -> onAbc()
            KeyboardAction.Done -> onDone()
            else -> {
                mimirSecretEditFor(key) { clipboard.getText()?.text }?.let(secretEdit)
                if (key.action == null) onShiftOffAfterChar()
            }
        }
    } else when (key.action) {
        KeyboardAction.Shift -> onShiftToggle()
        KeyboardAction.Symbols -> onSymbolsOn()
        KeyboardAction.Abc -> onAbc()
        // Over a selection the FIELD reports (Select all leaves the preview empty), Backspace deletes that selection in the field
        // and Space replaces it: both act on the field's selection, independent of what the preview holds.
        KeyboardAction.Back ->
            if (fieldOps?.selectionActive?.invoke() == true) fieldOps.deleteSelection() else emitEdit(backspaceAtMimirKeyboardSelection(value))
        KeyboardAction.Paste -> emitEdit(
            pasteAtMimirKeyboardSelection(
                value = value,
                clipboardText = clipboard.getText()?.text,
            ),
        )
        KeyboardAction.Space ->
            if (value.text.isNotEmpty() || fieldOps?.selectionActive?.invoke() == true) emitEdit(insertAtMimirKeyboardSelection(value, " "))
        KeyboardAction.Done -> onDone()
        KeyboardAction.Trackpad -> onTrackpad()
        null -> {
            emitEdit(insertAtMimirKeyboardSelection(value, key.value))
            onShiftOffAfterChar()
        }
    }
}

internal data class KeyboardKey(
    val value: String,
    val label: String,
    val action: KeyboardAction?,
    val keyId: MimirKeyboardKeyId,
    val weight: Float,
    val subdued: Boolean,
)

internal enum class KeyboardAction(private val raw: String) {
    Shift("shift"),
    Symbols("symbols"),
    Abc("abc"),
    Back("back"),
    Paste("paste"),
    Space("space"),
    Done("done"),

    /** D34b: switches the keys to the trackpad; only present when a [MimirTrackpadHost] is supplied. */
    Trackpad("trackpad");

    companion object {
        fun forValue(value: String): KeyboardAction? = entries.firstOrNull { it.raw == value }
    }
}

/** Shipping A-Z (QWERTY) + numeric row + Nordic action glyphs. */
private val LETTER_ROWS: List<List<String>> = listOf(
    listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
    listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
    listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
    listOf("shift", "z", "x", "c", "v", "b", "n", "m", "back"),
    listOf("symbols", "paste", "space", "done"),
)

/** [MimirKeyboardFieldKind.EMAIL]: LETTER_ROWS with `@` and `.` either side of space; paste stays. */
private val EMAIL_LETTER_ROWS: List<List<String>> = LETTER_ROWS.dropLast(1) +
    listOf(listOf("symbols", "@", "paste", "space", ".", "done"))

/** [MimirKeyboardFieldKind.URI]: LETTER_ROWS with `/` and `.` either side of space; paste stays. */
private val URI_LETTER_ROWS: List<List<String>> = LETTER_ROWS.dropLast(1) +
    listOf(listOf("symbols", "/", "paste", "space", ".", "done"))

private val SYMBOL_ROWS: List<List<String>> = listOf(
    listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
    listOf("@", "#", "$", "_", "&", "-", "+", "(", ")"),
    listOf("*", "\"", "'", ":", ";", "!", "?", "/"),
    listOf("symbols", ".", ",", "=", "%", "back"),
    listOf("abc", "paste", "space", "done"),
)

/**
 * Layout review remains open: exact glyph choices/ordering below are a
 * first pass, not a design-reviewed layout. Structurally grounded in the common ABC/123/#+= three-page
 * on-screen-keyboard convention (digits + the punctuation most useful alongside a number — SYMBOL_ROWS,
 * unchanged, stays the richer "#+=" page). Same 5-row shape as LETTER_ROWS/SYMBOL_ROWS so the panel's
 * height never changes across an R2 cycle (compositor-cheap focus invariant — no layout thrash on nav).
 */
private val NUMBER_ROWS: List<List<String>> = listOf(
    listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
    listOf("+", "-", "*", "/", "=", "%"),
    listOf(".", ",", ":", ";", "back"),
    listOf("symbols", "(", ")", "abc"),
    listOf("paste", "space", "done"),
)

private val KeyboardPanelMaxWidth: Dp = 720.dp

/** The floating keyboard's panel, tagged for the tests that ask how a host's sheet stands around it. */
const val MimirKeyboardPanelTag = "mimir-keyboard-panel"

/**
 * The air a floating keyboard keeps between its own panel and the box it is given, on each side: the panel's edge stands this far in from the box's edge. A host that
 * draws a sheet behind the keyboard reads it (with [mimirKeyboardPanelWidth]) to know where the panel stands, so its sheet can hug the panel evenly.
 */
val MimirKeyboardPanelSideInset: Dp = MimirSpacing.md

/** The width of the floating keyboard's panel in a box [boxWidth] wide: the box less its two side insets, no wider than the panel's cap. */
fun mimirKeyboardPanelWidth(boxWidth: Dp): Dp = minOf((boxWidth - MimirKeyboardPanelSideInset * 2).coerceAtLeast(0.dp), KeyboardPanelMaxWidth)

/**
 * The floating panel's inner air, one value on all four sides: between its edge and the field above, the last key row below, and the keys at each side. It is the larger of the
 * two values the sides and the edges used to have (12 dp at the sides; 4 above and 8 below on a short display such as Main's), so nothing a player sees sits closer to the edge.
 */
internal val KeyboardPanelInnerPadding: Dp = MimirSpacing.md
private val KeyboardPanelElevation: Dp = 24.dp
private val KeyboardKeyRadius: Dp = 10.dp
private val KeyboardMinKeyHeight = 34.dp
private val KeyboardFloatingPanelShape = RoundedCornerShape(18.dp)
private val KeyboardEditingRadius: Dp = 8.dp
private val KeyboardEditingHeight: Dp = 40.dp
private val KeyboardCompactEditingHeight: Dp = 34.dp

/** The tool row's height in fill mode (cursor, Select all, Paste, PAD, Hide). */
internal val KeyboardToolRowHeight: Dp = 44.dp
private val KeyboardFillMaxKeyHeight: Dp = 60.dp
private const val KeyboardFillKeyAspect = 1.1f
private val KeyboardFillPreviewMin: Dp = 56.dp

/** Room above the preview's text for the target label. */
private val KeyboardPreviewLabelHeight: Dp = 18.dp
private val KeyboardShortDisplayThreshold: Dp = 520.dp
private val KeyboardCaptionGap: Dp = 9.dp
private val PressedKeyLift: Dp = 1.dp
private val KeyboardHairlineWidth: Dp = 0.5.dp
private const val PressedKeyScale = 0.97f
internal const val MimirKeyboardDeleteFirstRepeatDelayMs = 400L
private const val MimirKeyboardDeleteAccelerationAtMs = 1_200L
private const val MimirKeyboardDeleteInitialRepeatIntervalMs = 110L
private const val MimirKeyboardDeleteAcceleratedRepeatIntervalMs = 55L
internal const val MimirKeyboardSpaceDragStepDp = 16f
private val KeyboardPanelShape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)

/** What a keyboard that FILLS its host (the Bottom) lays out: normal keys, and the height they leave for the preview. */
internal data class MimirKeyboardFillGeometry(
    /** The drawn key's width. */
    val keyWidth: Dp,
    /** The drawn key's height: about 1.1 times its width, between the touch minimum and [KeyboardFillMaxKeyHeight]. */
    val keyHeight: Dp,
    /** One key row's hit height: the drawn key plus the gap, so the hit areas tile the rows. */
    val rowHeight: Dp,
    /** What is left over for the two-line text preview. */
    val previewHeight: Dp,
)

/**
 * The keys keep their natural proportions
 * (height = width x 1.1, clamped to the touch minimum and 60 dp) and are anchored at the bottom; the rest of the height is
 * the preview. If the display is too short for the preview's minimum, the keys shrink (never below the minimum key).
 *
 * [widestRowWeight] is the widest row's total key weight (10 for the letter rows), so a key's width is one tenth of the row.
 */
internal fun mimirKeyboardFillGeometry(
    availableWidth: Dp,
    availableHeight: Dp,
    rows: Int,
    widestRowWeight: Float,
    sidePadding: Dp,
    gap: Dp,
    toolRowHeight: Dp,
    verticalOverhead: Dp,
): MimirKeyboardFillGeometry {
    if (!availableWidth.value.isFinite() || !availableHeight.value.isFinite() || rows <= 0 || widestRowWeight <= 0f) {
        return MimirKeyboardFillGeometry(MimirSpacing.minTarget, MimirSpacing.minTarget, MimirSpacing.minTarget + gap, 0.dp)
    }
    val cell = (availableWidth - sidePadding * 2f) / widestRowWeight
    val keyWidth = (cell - gap).coerceAtLeast(1.dp)
    var keyHeight = (keyWidth * KeyboardFillKeyAspect).coerceIn(MimirSpacing.minTarget, KeyboardFillMaxKeyHeight)
    fun preview(forKeyHeight: Dp): Dp =
        availableHeight - (forKeyHeight + gap) * rows.toFloat() - toolRowHeight - verticalOverhead
    if (preview(keyHeight) < KeyboardFillPreviewMin) {
        val fits = (availableHeight - toolRowHeight - verticalOverhead - KeyboardFillPreviewMin) / rows.toFloat() - gap
        keyHeight = fits.coerceIn(KeyboardMinKeyHeight, keyHeight)
    }
    return MimirKeyboardFillGeometry(
        keyWidth = keyWidth,
        keyHeight = keyHeight,
        rowHeight = keyHeight + gap,
        previewHeight = preview(keyHeight).coerceAtLeast(0.dp),
    )
}

internal fun mimirKeyboardKeyHeight(
    available: Dp,
    rows: Int,
    fixedOverhead: Dp,
    rowGap: Dp,
    minKey: Dp = KeyboardMinKeyHeight,
    maxKey: Dp = MimirSpacing.minTarget,
): Dp {
    if (!available.value.isFinite() || rows <= 0) return maxKey
    return ((available - fixedOverhead - rowGap * rows.toFloat()) / rows.toFloat())
        .coerceIn(minKey, maxKey)
}
