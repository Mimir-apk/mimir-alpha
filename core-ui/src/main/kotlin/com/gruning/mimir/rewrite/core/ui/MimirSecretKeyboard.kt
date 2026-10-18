package com.gruning.mimir.rewrite.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One DISCRETE edit of a secret field (a password or PIN typed through the system input method).
 *
 * W2f round 3. The whole-value keyboard hands back a complete new text and the host has to work out what changed;
 * for a password every such diff has turned out ambiguous (a bullet is also what the dots look like), and the
 * keyboard briefly held the raw character in its own state. So for a secret field the keyboard never reports a
 * value at all: it reports what the player DID, one op at a time, and the host commits that op to the field
 * directly. There is nothing to diff and nothing typed is remembered.
 */
sealed interface MimirSecretEdit {
    /** Text typed or pasted at the field's own caret (a paste is one Insert). Never logged: see [toString]. */
    data class Insert(val text: String) : MimirSecretEdit {
        override fun toString(): String = "Insert(length=${text.length})"
    }

    /** One backspace. */
    data object DeleteBackward : MimirSecretEdit

    /** Move the FIELD's caret one place left (forwarded as a key; nothing is computed from text). */
    data object CaretLeft : MimirSecretEdit

    /** Move the FIELD's caret one place right. */
    data object CaretRight : MimirSecretEdit
}

/**
 * The most dots a secret caption ever draws, and the largest count, caret or selection it will hold. A field
 * reports its own selection (`EditorInfo.initialSelStart` / `initialSelEnd`, `onUpdateSelection`), so a hostile or
 * buggy one can report any Int; nothing it reports may size an allocation. Past this the caption draws this many
 * dots and an overflow mark ([MimirSecretDisplay.overflow]).
 */
const val MimirSecretDisplayBound: Int = 1_000

/**
 * What a secret field's caption draws: INTS ONLY (and one flag). [count] dots, with the caret at [caret] and
 * [selected] dots selected after it, every one bounded by [MimirSecretDisplayBound]; [overflow] says the real
 * count was larger. It is DISPLAY, derived from the field's reported selection and from the ops forwarded; it is
 * never an input, nothing reads the drawn dots back, and no character of the password is representable here.
 */
data class MimirSecretDisplay(
    val count: Int = 0,
    val caret: Int = 0,
    val selected: Int = 0,
    val overflow: Boolean = false,
) {
    /** The caption after [edit] has been forwarded to the field. */
    fun after(edit: MimirSecretEdit): MimirSecretDisplay = when (edit) {
        is MimirSecretEdit.Insert -> {
            // commitText replaces a selection, so the selected dots go and the new ones take their place. Long
            // arithmetic: a paste can be any size and must not wrap the Int.
            val length = edit.text.length.toLong()
            of(
                count = count.toLong() - selected + length,
                caret = caret.toLong() + length,
                selected = 0,
                overflow = overflow,
            )
        }
        MimirSecretEdit.DeleteBackward -> when {
            selected > 0 -> of(count.toLong() - selected, caret.toLong(), 0, overflow)
            caret > 0 -> of(count.toLong() - 1, caret.toLong() - 1, 0, overflow)
            else -> this
        }
        MimirSecretEdit.CaretLeft -> when {
            selected > 0 -> of(count.toLong(), caret.toLong(), 0, overflow)
            else -> of(count.toLong(), caret.toLong() - 1, 0, overflow)
        }
        MimirSecretEdit.CaretRight -> when {
            selected > 0 -> of(count.toLong(), caret.toLong() + selected, 0, overflow)
            else -> of(count.toLong(), caret.toLong() + 1, 0, overflow)
        }
    }

    /**
     * The caption after the field itself reported its selection (`onUpdateSelection`): no text comes with it. A
     * negative or inverted report is not trusted and changes nothing.
     */
    fun withSelection(start: Int, end: Int): MimirSecretDisplay {
        if (start < 0 || end < 0 || start > end) return this
        return of(
            count = maxOf(count.toLong(), end.toLong()),
            caret = start.toLong(),
            selected = end.toLong() - start,
            overflow = overflow || end > MimirSecretDisplayBound,
        )
    }

    /** The dots to draw: at most [MimirSecretDisplayBound] of them, plus an ellipsis when the count overflowed. */
    fun dotsText(): String = "•".repeat(count) + if (overflow) "…" else ""

    companion object {
        /** A display from any counts: every value is clamped, so no input can size anything past the bound. */
        fun of(count: Long, caret: Long, selected: Long = 0, overflow: Boolean = false): MimirSecretDisplay {
            val bound = MimirSecretDisplayBound.toLong()
            val c = count.coerceIn(0L, bound)
            val k = caret.coerceIn(0L, c)
            val s = selected.coerceIn(0L, c - k)
            return MimirSecretDisplay(
                count = c.toInt(),
                caret = k.toInt(),
                selected = s.toInt(),
                overflow = overflow || count > bound,
            )
        }

        /**
         * Seeded from `EditorInfo.initialSelStart` / `initialSelEnd` -- positions the framework reports WITHOUT any
         * text. The text after the caret is unknown, so this shows the dots up to the selection's end. A negative
         * or inverted report becomes an empty display; anything above the bound is clamped to it.
         */
        fun seed(initialSelStart: Int, initialSelEnd: Int): MimirSecretDisplay {
            if (initialSelStart < 0 || initialSelEnd < 0 || initialSelStart > initialSelEnd) return MimirSecretDisplay()
            return of(
                count = initialSelEnd.toLong(),
                caret = initialSelStart.toLong(),
                selected = initialSelEnd.toLong() - initialSelStart,
            )
        }
    }
}

/** The one edit a key stands for in a secret field, or null for the keys that only change the layout. */
internal fun mimirSecretEditFor(key: KeyboardKey, clipboardText: () -> String?): MimirSecretEdit? =
    when (key.action) {
        KeyboardAction.Back -> MimirSecretEdit.DeleteBackward
        KeyboardAction.Paste -> clipboardText()?.takeIf { it.isNotEmpty() }?.let(MimirSecretEdit::Insert)
        // A password may begin or end with a space, so unlike the ordinary field it is always an insertion.
        KeyboardAction.Space -> MimirSecretEdit.Insert(" ")
        null -> MimirSecretEdit.Insert(key.value)
        KeyboardAction.Shift,
        KeyboardAction.Symbols,
        KeyboardAction.Abc,
        KeyboardAction.Done,
        KeyboardAction.Trackpad,
        -> null
    }

/** The caret keys a space-drag owes when it goes from [lastSteps] to [steps] drag steps. */
internal fun mimirSecretCaretEdits(lastSteps: Int, steps: Int): List<MimirSecretEdit> {
    val delta = steps - lastSteps
    return when {
        delta > 0 -> List(delta) { MimirSecretEdit.CaretRight }
        delta < 0 -> List(-delta) { MimirSecretEdit.CaretLeft }
        else -> emptyList()
    }
}

/** Touching the caption of a secret field does nothing: the dots are display, never an input. */
internal val IgnoreSecretCaptionEdit: (TextFieldValue) -> Unit = { }

/**
 * The keyboard for a SECRET field typed through the system input method. Same panel, layouts and keys as
 * [MimirKeyboard], but it reports discrete [MimirSecretEdit]s through [onEdit] and takes a [display] of Ints, so
 * no typed character is ever written into its state, and there is no Show/Reveal (there is no text to reveal).
 */
@Composable
fun MimirSecretKeyboard(
    display: MimirSecretDisplay,
    onEdit: (MimirSecretEdit) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    controller: MimirKeyboardController? = null,
    keyDecorator: MimirKeyboardKeyDecorator? = null,
    palette: MimirThemePalette = LocalMimirPalette.current,
    targetLabel: String = "Password",
    showControllerLegend: Boolean = true,
    floating: Boolean = false,
    controllerLegendAction: String = "Back",
    fieldKind: MimirKeyboardFieldKind = MimirKeyboardFieldKind.TEXT,
    fill: Boolean = false,
    onHide: (() -> Unit)? = null,
) {
    // Derived every composition from three Ints and a flag, never more than the bound; remembered nowhere.
    val dots = TextFieldValue(
        text = display.dotsText(),
        selection = TextRange(display.caret, display.caret + display.selected),
    )
    MimirKeyboardSurface(
        value = dots,
        onValueChange = IgnoreSecretCaptionEdit,
        onDone = onDone,
        modifier = modifier,
        controller = controller,
        keyDecorator = keyDecorator,
        palette = palette,
        targetLabel = targetLabel,
        targetLabelIntent = MimirTextIntent.StaticChrome(targetLabel),
        showControllerLegend = showControllerLegend,
        floating = floating,
        secret = true,
        controllerLegendAction = controllerLegendAction,
        fieldKind = fieldKind,
        secretEdit = onEdit,
        secretCount = display.count,
        fill = fill,
        onHide = onHide,
    )
}

/**
 * One op applied to the OWNING field's value (text and selection), read from the owner at call time. Typed or pasted
 * text replaces any selection; a backspace over a selection deletes it; the caret ops collapse a selection to its
 * edge, or step one character (a surrogate pair is one). The selection here is the owner's own and is NEVER clamped:
 * only the drawn dots are bounded.
 */
internal fun mimirApplySecretEdit(previous: TextFieldValue, edit: MimirSecretEdit): TextFieldValue = when (edit) {
    is MimirSecretEdit.Insert -> insertAtMimirKeyboardSelection(previous, edit.text)
    MimirSecretEdit.DeleteBackward -> backspaceAtMimirKeyboardSelection(previous)
    MimirSecretEdit.CaretLeft -> moveSecretCaret(previous, forward = false)
    MimirSecretEdit.CaretRight -> moveSecretCaret(previous, forward = true)
}

private fun moveSecretCaret(value: TextFieldValue, forward: Boolean): TextFieldValue {
    if (!value.selection.collapsed) {
        return value.copy(selection = TextRange(if (forward) value.selection.max else value.selection.min), composition = null)
    }
    val at = value.selection.start.coerceIn(0, value.text.length)
    val target = if (forward) nextBoundary(value.text, at) else previousBoundary(value.text, at)
    return value.copy(selection = TextRange(target), composition = null)
}

private fun previousBoundary(text: String, at: Int): Int {
    if (at <= 0) return 0
    return if (at >= 2 && text[at - 1].isLowSurrogate() && text[at - 2].isHighSurrogate()) at - 2 else at - 1
}

private fun nextBoundary(text: String, at: Int): Int {
    if (at >= text.length) return text.length
    return if (at + 1 < text.length && text[at].isHighSurrogate() && text[at + 1].isLowSurrogate()) at + 2 else at + 1
}

/**
 * The OWNING field's side of a secret entry (a password or API key typed on Mimir's in-app keyboard). The owner holds
 * the text; this object holds only two functions that READ AND WRITE THE OWNER'S OWN STATE AT CALL TIME. The
 * keyboard never receives the text, not even to show it: it emits [MimirSecretEdit] ops to [apply], draws [display]
 * (Ints, as dots), and flips [revealed]; the owner draws its own plaintext plate while that flag is on. Because every op reads the live value, N ops fired between
 * recompositions (a held backspace, a space drag) each see the result of the one before.
 */
class MimirSecretFieldOwner internal constructor(
    private val read: () -> TextFieldValue,
    private val write: (TextFieldValue) -> Unit,
) {
    private val revealedState = mutableStateOf(false)

    /** Whether the player has pressed Show. The OWNER draws the plaintext (see [MimirSecretRevealPlate]); the keyboard only knows this flag. */
    val revealed: Boolean get() = revealedState.value

    internal fun toggleReveal() {
        revealedState.value = !revealedState.value
    }

    internal fun hideReveal() {
        revealedState.value = false
    }

    fun apply(edit: MimirSecretEdit) {
        val previous = read()
        val next = mimirApplySecretEdit(previous, edit)
        if (next != previous) write(next)
    }

    /** Count, caret and selection as bounded Ints for the caption. */
    internal fun display(): MimirSecretDisplay {
        val current = read()
        val low = current.selection.min.toLong()
        return MimirSecretDisplay.of(
            count = current.text.length.toLong(),
            caret = low,
            selected = current.selection.max.toLong() - low,
        )
    }

    /** The owner's text, for the owner's own reveal plate only; computed per call, kept nowhere, never handed to the keyboard. */
    internal fun revealedText(): String = read().text
}

/**
 * An owner whose state is a plain String (a draft the caller holds). [readText] and [writeText] must read and write
 * the caller's own state holder when called (for example `{ draft }` where `draft` is a `by mutableStateOf`), never a
 * String captured at composition. The caret lives here as an Int, following the end whenever the text changes behind
 * the keyboard's back.
 */
@Composable
fun rememberMimirSecretStringOwner(
    readText: () -> String,
    writeText: (String) -> Unit,
): MimirSecretFieldOwner {
    val reader = androidx.compose.runtime.rememberUpdatedState(readText)
    val writer = androidx.compose.runtime.rememberUpdatedState(writeText)
    return remember {
        val caret = mutableIntStateOf(-1)
        val knownLength = mutableIntStateOf(-1)
        MimirSecretFieldOwner(
            read = {
                val text = reader.value()
                val effective = if (caret.intValue < 0 || knownLength.intValue != text.length) {
                    text.length
                } else {
                    caret.intValue.coerceIn(0, text.length)
                }
                TextFieldValue(text = text, selection = TextRange(effective))
            },
            write = { next ->
                caret.intValue = next.selection.max
                knownLength.intValue = next.text.length
                if (next.text != reader.value()) writer.value(next.text)
            },
        )
    }
}

/** An owner whose state is a [TextFieldValue] (text and selection) the caller already holds, read and written at call time. */
@Composable
fun rememberMimirSecretFieldValueOwner(
    read: () -> TextFieldValue,
    write: (TextFieldValue) -> Unit,
): MimirSecretFieldOwner {
    val reader = androidx.compose.runtime.rememberUpdatedState(read)
    val writer = androidx.compose.runtime.rememberUpdatedState(write)
    return remember { MimirSecretFieldOwner(read = { reader.value() }, write = { writer.value(it) }) }
}

/**
 * The OWNER's reveal of its own secret, drawn beside the keyboard and not inside it: the keyboard only ever renders
 * dots and is never handed this text. The text is read from the owner in the DRAW phase and painted without a cached
 * layout; it is not a Text node, so it is in no semantics tree and no composition state. The plate itself exists only
 * while Show is on and the keyboard is open.
 */
@Composable
private fun MimirSecretRevealPlate(owner: MimirSecretFieldOwner, palette: MimirThemePalette) {
    val shape = RoundedCornerShape(10.dp)
    // No layout is cached: the measurer is asked for none, so the plaintext is never held in a text layout.
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val style = MimirTypeScale.Row.copy(color = palette.textPrimary, fontSize = 15.sp)
    val lineHeight = with(LocalDensity.current) {
        (if (style.lineHeight.isSp) style.lineHeight else style.fontSize * 1.4f).toDp()
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MimirSpacing.md, vertical = MimirSpacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                // A fixed height from the style, so nothing about the secret decides the layout.
                .height(lineHeight + MimirSpacing.xs * 2)
                .clip(shape)
                .background(palette.accent.copy(alpha = 0.14f), shape)
                .border(0.5.dp, palette.accent.copy(alpha = 0.5f), shape)
                .padding(horizontal = MimirSpacing.sm, vertical = MimirSpacing.xs)
                // Accessibility never receives the plaintext: one non-secret description, nothing else.
                .clearAndSetSemantics { contentDescription = "Password shown" }
                .drawBehind {
                    // The secret is read and drawn in the DRAW phase only: no String, AnnotatedString or text layout
                    // is held in composition or in anything remembered.
                    drawText(
                        textMeasurer = measurer,
                        text = owner.revealedText(),
                        style = style,
                        overflow = TextOverflow.Ellipsis,
                        softWrap = false,
                        maxLines = 1,
                        size = size,
                    )
                },
        )
    }
}

/**
 * The in-app keyboard for a secret the OWNER holds: the same ops-only path the system keyboard uses. It takes no
 * String and no text of any kind -- only the [owner], whose functions read the owner's live state when an op is
 * applied, and the owner's own reveal plate is drawn beside the keyboard while Show is on.
 */
@Composable
fun MimirSecretFieldKeyboard(
    owner: MimirSecretFieldOwner,
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
) {
    val display = owner.display()
    val dots = TextFieldValue(
        text = display.dotsText(),
        selection = TextRange(display.caret, display.caret + display.selected),
    )
    // Hidden every time the keyboard opens, and again the moment it closes: the owner's plate leaves composition with it.
    DisposableEffect(owner) {
        owner.hideReveal()
        onDispose { owner.hideReveal() }
    }
    Column(modifier = modifier) {
        if (owner.revealed) MimirSecretRevealPlate(owner = owner, palette = palette)
        MimirKeyboardSurface(
            value = dots,
            onValueChange = IgnoreSecretCaptionEdit,
            onDone = onDone,
            modifier = Modifier,
            controller = controller,
            keyDecorator = keyDecorator,
            palette = palette,
            targetLabel = targetLabel,
            targetLabelIntent = targetLabelIntent,
            showControllerLegend = showControllerLegend,
            floating = floating,
            secret = true,
            controllerLegendAction = controllerLegendAction,
            fieldKind = fieldKind,
            secretEdit = owner::apply,
            secretRevealToggle = owner::toggleReveal,
            secretRevealed = owner.revealed,
            secretCount = display.count,
        )
    }
}
