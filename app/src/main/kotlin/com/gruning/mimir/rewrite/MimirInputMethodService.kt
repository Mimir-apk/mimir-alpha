package com.gruning.mimir.rewrite

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.gruning.mimir.rewrite.core.companionnative.BottomImeVisibility
import com.gruning.mimir.rewrite.core.ui.MimirKeyboard
import com.gruning.mimir.rewrite.core.ui.MimirKeyboardFieldOps
import com.gruning.mimir.rewrite.core.ui.MimirSecretDisplay
import com.gruning.mimir.rewrite.core.ui.MimirSecretEdit
import com.gruning.mimir.rewrite.core.ui.MimirSecretKeyboard
import com.gruning.mimir.rewrite.core.ui.MimirTrackpadHost
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import kotlinx.coroutines.delay

/**
 * `MimirKeyboard`, registered as a real Android input method.
 *
 * ## Why this rather than typing at another app through a shell
 *
 * The existing Mimir keyboard can serve other apps through Android's input-method interface.
 *
 * The previous answer to "type into a foreign app" ran `/system/bin/input` through PServer, as root,
 * and needed a character allow-list, shell quoting, a refusal path and a 6.6 s transport warm — all of
 * it machinery to work around a boundary an input method does not have. Here the framework hands over
 * an [android.view.inputmethod.InputConnection]: real text with a composing region, selection and
 * editor actions, no privilege, and nothing that can be made to run a command.
 *
 * It also lands in the right place by itself. On the Thor, the platform already opens the IME on
 * SCREEN 2 when a field on the main display takes focus — which is precisely the seat the reverted
 * surface built its own window host to occupy.
 *
 * ## What this does NOT change
 *
 * Mimir's own fields keep using [MimirKeyboard] directly, in-process, exactly as they always have. This
 * service is for OTHER apps' fields. The two never meet: an IME is only ever asked for by a window
 * Mimir does not own.
 *
 * ## Lifecycle
 *
 * An [android.inputmethodservice.InputMethodService] is a [android.app.Service], so it carries none of
 * the owners a `ComposeView` requires. It implements all three itself rather than borrowing an
 * Activity's — the Activity is not running when another app is in front, which is the entire situation
 * this serves.
 *
 * ## Hardened for being the system default (W2f, 2026-10-03)
 *
 * Mimir may now set itself as the system default keyboard, so a fault here must never leave the player
 * without one. Nothing in the view-building, start/finish, key-handling or enter paths throws out of the
 * service; a failure is logged as a fixed line (see [logFailure]) and the keyboard falls back to a plain
 * one. A crash INSIDE Compose's composition or draw cannot be caught, so a persistent "armed" marker
 * (`MimirImeSafety.kt`) turns it into a plain fallback keyboard on the next start, and into a reset of
 * Mimir's own default after a repeat. The field's `inputType` picks the layout ([mimirImeFieldProfile]) and
 * its IME action drives the enter key ([mimirImeEnterBehavior]).
 *
 * **A password field never has its text read, kept or logged.** It is typed per key: each key goes straight to
 * the field with `commitText` / `deleteSurroundingText`, the service holds only a COUNT of dots, and nothing
 * is diffed against a remembered value, so moving the caption caret cannot commit anything.
 */
open class MimirInputMethodService :
    android.inputmethodservice.InputMethodService(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    /**
     * What this service believes an ORDINARY field holds, as far as it has typed into it.
     *
     * Seeded from the field's ACTUAL text on each start, so backspace works on content the player did
     * not type here. Bounded, because the field may hold a great deal more than a keyboard needs to
     * reason about and reading all of it would be an unbounded IPC on every focus.
     *
     * **Never used for a secret field**, which keeps only [secretDisplay] (Ints). Cleared by [onFinishInputView].
     */
    private var typed by mutableStateOf("")

    /**
     * A secret field's whole state: how many dots to draw, where the caret is, how many are selected. INTS ONLY --
     * no character of the password is representable here. It is display, derived from the field's reported
     * selection and from the ops forwarded; nothing reads it back as an input.
     */
    private var secretDisplay by mutableStateOf(MimirSecretDisplay())

    /**
     * Where the field says its selection is, as two Ints and no text: seeded from `EditorInfo.initialSelStart` /
     * `initialSelEnd` and kept by `onUpdateSelection`. The plain fallback keyboard (which keeps no display) needs
     * only this to know whether a backspace is over a selection. -1 is unknown.
     */
    private var fieldSelStart: Int = -1
    private var fieldSelEnd: Int = -1

    /**
     * Set by a tool-row op that moved or widened the FIELD's own caret or selection (the caret keys, Select all) or by an
     * edit made over a selection: the next selection report re-reads the same bounded prefix the focus seed reads
     * ([MaxSeededPrefix] before the caret), so the preview follows where the field now is. Never set for a secret field,
     * which keeps no text.
     */
    private var resyncFromField = false

    /** What the current field is, decided on each start from its `inputType`; drives the layout. */
    internal var fieldProfile by mutableStateOf(MimirImeFieldProfile.Default)
        private set

    private var themeId: MimirThemeId = MimirThemeId.DEFAULT

    /** Bumped on every show so the drawn-and-stayed-up check restarts for each. */
    private var showToken by mutableStateOf(0)

    private var mode = ImeMode.COMPOSE
    private val mainHandler = Handler(Looper.getMainLooper())
    private val disarmRunnable = Runnable { disarm() }

    // ---- crash-loop safety seams, open so a test can drive them without a device ----------------------------

    internal open val safetyStore: ImeSafetyStore by lazy { SharedPrefsImeSafetyStore(this) }
    internal open val safetyProcessToken: String get() = ImeProcess.token
    internal open fun safetyVersionStamp(): Long = ImeProcess.versionStamp(this)

    /** Turn Mimir's default-keyboard preference off and run the fixed reset. Open for tests. */
    internal open fun revertDefaultForSafety() = MimirKeyboardDefaultRuntime.revertForSafety(this)

    override fun onCreate() {
        super.onCreate()
        guarded("create") {
            savedStateController.performRestore(null)
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
            // The keyboard wears the owner's theme. Read here rather than in composition: the Activity may
            // never have run in this process, so nothing else has loaded it.
            ThemeStore.load(this)
            themeId = ThemeStore.themeId.value
        }
    }

    /**
     * **Never throws, and never returns nothing.** First the persistent trail is read: a Compose keyboard that
     * died while showing forces the plain fallback (and, repeated, a reset of Mimir's default). Then anything
     * thrown while building the chosen keyboard is caught, logged as a fixed line (the exception's class only --
     * never its message, which can carry text), and replaced with [MimirImeFallbackKeyboard].
     *
     * Not covered, and said plainly: an exception thrown later, inside Compose's own composition or draw on the
     * main looper, never passes through this function -- it is the armed marker, not this catch, that protects
     * the NEXT start from it.
     */
    override fun onCreateInputView(): View {
        mode = chooseMode()
        arm()
        if (mode == ImeMode.COMPOSE) {
            try {
                return buildComposeKeyboardView()
            } catch (cause: Throwable) {
                if (cause is VirtualMachineError) throw cause
                logFailure("inputView", cause)
                mode = ImeMode.FALLBACK
                arm()
            }
        }
        return try {
            buildFallbackKeyboardView()
        } catch (second: Throwable) {
            if (second is VirtualMachineError) throw second
            logFailure("fallbackView", second)
            View(this)
        }
    }

    /** Reads the trail a dead process left, persists the verdict, and starts a revert when it demands one. */
    private fun chooseMode(): ImeMode {
        return try {
            val decision = imeSafetyAtCreate(safetyStore.read(), safetyVersionStamp(), safetyProcessToken)
            safetyStore.write(decision.state)
            if (decision.revertDefault) {
                Log.w(LogTag, "keyboard found dead while showing; reverting default mode=${decision.mode}")
                revertDefaultForSafety()
            }
            decision.mode
        } catch (cause: Throwable) {
            if (cause is VirtualMachineError) throw cause
            logFailure("safetyRead", cause)
            ImeMode.COMPOSE
        }
    }

    /** Written (synchronously) before a keyboard is shown; see `MimirImeSafety.kt`. */
    private fun arm() {
        guarded("arm") {
            safetyStore.writeArmed(imeSafetyArm(safetyStore.read(), mode, safetyProcessToken))
        }
        mainHandler.removeCallbacks(disarmRunnable)
        if (mode == ImeMode.FALLBACK) {
            // Plain views have no first-frame subtlety: up and stable for a moment is enough.
            mainHandler.postDelayed(disarmRunnable, ImeProcess.StableMillis)
        }
    }

    /**
     * D34b: the host for the keyboard's Trackpad key, or null where this device cannot host one. Open so a test can
     * hand in a fake; production reads the process runtime. Never offered to a secret field's keyboard.
     */
    internal open fun trackpadHostForKeyboard(): MimirTrackpadHost? =
        runCatching { MimirTrackpadRuntime.hostFor(this) }.getOrNull()

    private val trackpadHost: MimirTrackpadHost? by lazy { trackpadHostForKeyboard() }

    /**
     * The full-screen trackpad is its own window and stays open until its own Close, the screen going off or the service
     * being disabled: nothing here ends it any more (not the keyboard hiding, not a secret field taking over). What the
     * keyboard does tell it is that a field is waiting for the keyboard, so the pad can light its Keyboard plate. Open so
     * a test can observe it. Never throws.
     */
    internal open fun reportFieldWaiting(waiting: Boolean) {
        guarded("fieldWaiting") { MimirTrackpadRuntime.setFieldWaiting(waiting) }
    }

    /**
     * The one signal the Bottom's native overlays read (the ᛗ hides, the edge-back strips stand down): whether this keyboard is up on
     * a display other than Main's, and which. Open so a test can observe it. Never throws.
     */
    internal open fun reportImeShown(shown: Boolean) {
        guarded("bottomIme") {
            BottomImeVisibility.report(this, if (shown) imeShownOnBottom(readImeDisplays())?.id else null)
        }
    }

    /**
     * K1: the Bottom this keyboard is on, when it is on one. Open so a test can hand in a size. pad13: decided from the display the input method is
     * REALLY shown on, read from its decor view AND from the service's own context ([readImeDisplays]); either being a non-default display makes it the
     * Bottom, and the size is that display's. Never throws.
     */
    internal open fun bottomFillNow(): ImeBottomFill? = runCatching {
        imeFillFor(readImeDisplays(), resources.displayMetrics.density)
    }.getOrNull()

    /**
     * Both reads of the display, each guarded on its own (the window context is API 30+ and a plain service context throws for it). Open so a test can
     * say what each source reports. Never throws.
     */
    internal open fun readImeDisplays(): ImeDisplayReads {
        fun read(display: android.view.Display?): ImeDisplayRead? = runCatching {
            if (display == null) return@runCatching null
            val size = android.graphics.Point()
            @Suppress("DEPRECATION")
            display.getRealSize(size)
            ImeDisplayRead(display.displayId, size.x, size.y)
        }.getOrNull()
        val decor = read(runCatching { window?.window?.decorView?.display }.getOrNull())
        val ctx = read(runCatching { display }.getOrNull())
        return ImeDisplayReads(decor, ctx)
    }

    /** Decides the fill now, keeps it for the keyboard, and logs the one line for the decision (ids and sizes only). Never throws. */
    private fun decideFill() {
        val fill = bottomFillNow()
        bottomFill = fill
        guarded("fillLog") {
            val fullscreen = runCatching { onEvaluateFullscreenMode() }.getOrDefault(false)
            Log.i(LogTag, imeFillLogLine(readImeDisplays(), fill, fullscreen))
        }
    }


    /** The fill the composed keyboard reads; refreshed at every show (a display can change between two). */
    private var bottomFill by mutableStateOf<ImeBottomFill?>(null)

    override fun onComputeInsets(outInsets: android.inputmethodservice.InputMethodService.Insets) {
        super.onComputeInsets(outInsets)
        applyBottomFillInsets(outInsets, bottomFill)
    }

    /** The keyboard drew and stayed up (or was hidden after drawing): the trail is cleared. */
    internal fun disarm() {
        guarded("disarm") {
            safetyStore.writeArmed(imeSafetyDisarm(safetyStore.read(), safetyProcessToken))
        }
    }

    /** The real keyboard. Open so a test can make it throw; production never overrides it. */
    internal open fun buildComposeKeyboardView(): View {
        // **The owners must go on the WINDOW DECOR, not only on the ComposeView.** Found by crash on
        // the Thor: `IllegalStateException: ViewTreeLifecycleOwner not found from
        // android.widget.LinearLayout{… android:id/parentPanel}`.
        //
        // An `InputMethodService` does not host the returned view directly — it wraps it in its own
        // hierarchy under a Dialog, and Compose's `WindowRecomposer` resolves its parent context by
        // walking to the ROOT of that window rather than reading the view it was set on. Setting the
        // owners on the ComposeView alone leaves everything above it ownerless, and the recomposer
        // throws before the first frame.
        window?.window?.decorView?.let { decor ->
            decor.setViewTreeLifecycleOwner(this)
            decor.setViewTreeViewModelStoreOwner(this)
            decor.setViewTreeSavedStateRegistryOwner(this)
        }
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@MimirInputMethodService)
            setViewTreeViewModelStoreOwner(this@MimirInputMethodService)
            setViewTreeSavedStateRegistryOwner(this@MimirInputMethodService)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                MimirTheme(themeId = themeId) {
                    // A frame has drawn, and the keyboard has stayed up: the armed marker may go. If
                    // composition or the first frame throws, this never runs and the marker survives.
                    val token = showToken
                    LaunchedEffect(token) {
                        withFrameNanos { }
                        delay(ImeProcess.StableMillis)
                        disarm()
                    }
                    val profile = fieldProfile
                    val fill = bottomFill
                    // On the Bottom the keyboard fills the display; elsewhere nothing changes.
                    val fillModifier = if (fill == null) {
                        androidx.compose.ui.Modifier
                    } else {
                        androidx.compose.ui.Modifier
                            .fillMaxWidth()
                            .height(fill.heightDp.dp)
                    }
                    // Keyed on the profile so the keyboard's own remembered state (layout page, shift) starts
                    // over when the next field is a different kind -- a number field must open on digits even
                    // if the previous one left the letters up.
                    key(profile) {
                        // No controller: an IME is touch-driven, and Mimir's controller input belongs to
                        // whatever owns the screen, never to this window.
                        if (profile.secret) {
                            // A secret field: discrete edit ops, three Ints of display, no value at all.
                            MimirSecretKeyboard(
                                display = secretDisplay,
                                onEdit = ::applySecretEdit,
                                onDone = ::finishEditing,
                                onHide = ::hideKeyboard,
                                targetLabel = SecretTargetLabel,
                                fieldKind = profile.kind,
                                modifier = fillModifier,
                                fill = fill != null,
                            )
                        } else {
                            MimirKeyboard(
                                value = typed,
                                onValueChange = ::applyValue,
                                onDone = ::finishEditing,
                                onHide = ::hideKeyboard,
                                targetLabel = currentTargetLabel(profile),
                                fieldKind = profile.kind,
                                trackpad = trackpadHost,
                                trackpadResetKey = showToken,
                                modifier = fillModifier,
                                fill = fill != null,
                                // The preview holds only a bounded prefix, so the tool row's caret and Select all act on the FIELD.
                                fieldOps = MimirKeyboardFieldOps(
                                    caretLeft = { toolCaret(-1) },
                                    caretRight = { toolCaret(1) },
                                    selectAll = ::toolSelectAll,
                                    selectionActive = ::fieldSelectionActive,
                                    deleteSelection = ::toolDeleteSelection,
                                ),
                            )
                        }
                    }
                }
            }
        }
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        return view
    }

    private fun buildFallbackKeyboardView(): View = MimirImeFallbackKeyboard(
        context = this,
        onText = ::commitFallbackText,
        onBackspace = ::backspaceFallback,
        onEnter = ::finishEditing,
        onSwitch = ::leaveForAnotherKeyboard,
    )

    /**
     * Every start of input, including a RESTART into a different field in the same window, re-decides fullscreen
     * and the extract view from the NEW field ([refreshFullscreenFor]). A password field coming after a visible
     * landscape text field must not inherit that field's fullscreen mode or the text its extract view still holds.
     */
    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        guarded("startInput") { super.onStartInput(attribute, restarting) }
        refreshFullscreenFor(attribute)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        guarded("startInputView") {
            super.onStartInputView(info, restarting)
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
            val profile = mimirImeFieldProfile(info?.inputType ?: 0)
            fieldProfile = profile
            fieldSelStart = info?.initialSelStart ?: -1
            fieldSelEnd = info?.initialSelEnd ?: -1
            typed = ""
            secretDisplay = MimirSecretDisplay()
            if (profile.secret) {
                // **A password is never read, not even to count it.** The only things used are where the field
                // says its caret and selection are (`initialSelStart` / `initialSelEnd`, reported by the framework
                // without any text), so the caption shows that many dots and the caret where the field has it.
                secretDisplay = MimirSecretDisplay.seed(info?.initialSelStart ?: -1, info?.initialSelEnd ?: -1)
            } else {
                // Seeded from the real field so a backspace deletes what is actually there. Without this the
                // keyboard would believe the field is empty and backspace would silently do nothing.
                typed = currentInputConnection
                    ?.getTextBeforeCursor(MaxSeededPrefix, 0)
                    ?.toString()
                    .orEmpty()
            }
        }
        refreshFullscreenFor(info)
        reportFieldWaiting(true)
        reportImeShown(true)
        // Each show re-arms the trail, so a crash on ANY first frame is caught, not only the first ever.
        arm()
        showToken += 1
    }

    /**
     * Re-evaluates fullscreen for the field now in front, and for a secret field first hides the extract view and
     * empties its text -- it may still hold the PREVIOUS, non-secret field's text, and it must never be shown or
     * fed again for a password. Each step is guarded on its own so one failing cannot skip the next.
     */
    private fun refreshFullscreenFor(info: EditorInfo?) {
        // The fill and the fullscreen decision both follow the display the window is on NOW: decided on every start of input,
        // never once when the view was built (the window can move between two shows).
        guarded("refreshFill") { decideFill() }
        val secret = info != null && mimirImeFieldProfile(info.inputType).secret
        if (secret) {
            guarded("hideExtract") { setExtractViewShown(false) }
            guarded("clearExtract") {
                val extract = window?.window?.decorView
                    ?.findViewById<android.widget.TextView>(android.R.id.inputExtractEditText)
                if (extract is android.inputmethodservice.ExtractEditText) {
                    // The extract view reports every selection change back to the field (setSelection on the
                    // connection). Cleared as an INTERNAL change it reports nothing, so the password field's own
                    // caret is not moved by this clean-up.
                    extract.startInternalChanges()
                    try {
                        extract.setText("")
                    } finally {
                        extract.finishInternalChanges()
                    }
                } else {
                    extract?.setText("")
                }
            }
        }
        guarded("updateFullscreen") { updateFullscreenMode() }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        guarded("finishInputView") {
            super.onFinishInputView(finishingInput)
        }
        typed = ""
        secretDisplay = MimirSecretDisplay()
        fieldSelStart = -1
        fieldSelEnd = -1
        reportFieldWaiting(false)
        // A restart into another field keeps the window up; only a window that is really down clears the signal.
        reportImeShown(runCatching { isInputViewShown }.getOrDefault(false))
        // An orderly teardown means the process did not die: the trail is cleared NOW, drawn or not, so a keyboard
        // closed before its first frame is never mistaken for a crash by the next process.
        disarm()
    }

    override fun onWindowShown() {
        guarded("windowShown") { super.onWindowShown() }
        guarded("windowShownFill") {
            decideFill()
            updateFullscreenMode()
        }
        reportImeShown(true)
    }

    /** The fill the keyboard is composed with right now (null on Main and phones). */
    internal fun currentBottomFill(): ImeBottomFill? = bottomFill

    override fun onWindowHidden() {
        guarded("windowHidden") { super.onWindowHidden() }
        reportFieldWaiting(false)
        reportImeShown(false)
        disarm()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(disarmRunnable)
        reportFieldWaiting(false)
        reportImeShown(false)
        disarm()
        guarded("destroy") {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
            store.clear()
        }
        super.onDestroy()
    }

    /**
     * The write path for ORDINARY fields. Diffs against what this service last believed, and sends only the
     * edit -- so an ordinary keystroke is one `commitText` of one character rather than a retype.
     *
     * Never throws: a failing connection loses the keystroke, not the keyboard. Logs nothing about the text.
     */
    internal fun applyValue(next: String) {
        BottomImeVisibility.noteKeyActivity() // a key press is screen activity: the ᛗ that used to say so is gone under this keyboard
        // A secret field never comes through here: it has its own per-op path, and no whole value is ever diffed.
        if (fieldProfile.secret) return
        guarded("applyValue") {
            val connection = currentInputConnection
            val edit = mimirKeyboardEdit(typed, next)
            typed = next
            // Over a selection (Select all, then Paste or a key) the field replaces the WHOLE selection, which the
            // preview prefix does not hold: re-read where the field landed once it reports.
            if (fieldHasSelection() && !edit.isEmpty) resyncFromField = true
            if (connection == null || edit.isEmpty) return@guarded
            connection.beginBatchEdit()
            try {
                if (edit.deleteBefore > 0) connection.deleteSurroundingText(edit.deleteBefore, 0)
                if (edit.commit.isNotEmpty()) connection.commitText(edit.commit, 1)
            } finally {
                connection.endBatchEdit()
            }
        }
    }

    /**
     * The write path for a SECRET field: ONE discrete edit op per key, committed to the field at once.
     *
     * There is no value to compare. The keyboard reports what the player DID, and each op goes straight to the
     * field's own caret:
     *  - [MimirSecretEdit.Insert] (a typed key, or a paste as one insert) -> `commitText(text, 1)`, which also
     *    replaces a selection the field reported;
     *  - [MimirSecretEdit.DeleteBackward] -> `deleteSurroundingText(1, 0)`, or `commitText("", 1)` when the field
     *    reported a selection (a backspace over a selection deletes the selection);
     *  - the caret ops -> the field's own DPAD_LEFT / DPAD_RIGHT key, so the FIELD moves its cursor and nothing is
     *    computed from text.
     * Only [secretDisplay] (three Ints) changes. The inserted text lives in this call's locals for the length of
     * the call, and is never written to state, a buffer or a log.
     */
    internal fun applySecretEdit(edit: MimirSecretEdit) {
        BottomImeVisibility.noteKeyActivity()
        guarded("applySecretEdit") {
            val connection = currentInputConnection
            val before = secretDisplay
            // Whether the FIELD has a selection comes from the two unclamped Ints it reported, never from the
            // display, which is bounded for drawing only (a selection at 1001..1005 is still a selection).
            val hadSelection = fieldHasSelection()
            secretDisplay = before.after(edit)
            if (connection == null) return@guarded
            when (edit) {
                is MimirSecretEdit.Insert -> {
                    connection.commitText(edit.text, 1)
                    moveFieldCaretAfterInsert(edit.text.length)
                }
                MimirSecretEdit.DeleteBackward ->
                    if (hadSelection) {
                        connection.commitText("", 1)
                        collapseFieldSelection()
                    } else {
                        connection.deleteSurroundingText(1, 0)
                        shiftFieldCaret(-1)
                    }
                MimirSecretEdit.CaretLeft -> {
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT)
                    if (hadSelection) collapseFieldSelection() else shiftFieldCaret(-1)
                }
                MimirSecretEdit.CaretRight -> {
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT)
                    if (hadSelection) collapseFieldSelectionToEnd() else shiftFieldCaret(1)
                }
            }
        }
    }

    private fun fieldHasSelection(): Boolean = fieldSelStart >= 0 && fieldSelEnd >= 0 && fieldSelStart != fieldSelEnd

    /** The field's own caret after a commit: low edge plus the inserted length, in long arithmetic, never wrapping. */
    private fun moveFieldCaretAfterInsert(length: Int) {
        if (fieldSelStart < 0 || fieldSelEnd < 0) return
        val next = (minOf(fieldSelStart, fieldSelEnd).toLong() + length).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        fieldSelStart = next
        fieldSelEnd = next
    }

    private fun shiftFieldCaret(delta: Int) {
        if (fieldSelStart < 0 || fieldSelEnd < 0) return
        val next = (minOf(fieldSelStart, fieldSelEnd).toLong() + delta).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        fieldSelStart = next
        fieldSelEnd = next
    }

    private fun collapseFieldSelectionToEnd() {
        if (fieldSelStart < 0 || fieldSelEnd < 0) return
        val high = maxOf(fieldSelStart, fieldSelEnd)
        fieldSelStart = high
        fieldSelEnd = high
    }

    /**
     * The field reported its own selection (no text comes with it): the caption follows the field's caret and
     * selection, so it stays honest after the app, a caret key or the player's finger moved it.
     */
    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        guarded("updateSelection") {
            super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
            fieldSelStart = newSelStart
            fieldSelEnd = newSelEnd
            if (fieldProfile.secret) {
                secretDisplay = secretDisplay.withSelection(newSelStart, newSelEnd)
            } else if (resyncFromField) {
                // A tool-row op moved the field's own caret or selection: the same bounded read the focus seed makes.
                resyncFromField = false
                typed = currentInputConnection
                    ?.getTextBeforeCursor(MaxSeededPrefix, 0)
                    ?.toString()
                    .orEmpty()
            }
        }
    }

    /**
     * A secret field never goes fullscreen. In landscape the framework would otherwise show its extract view,
     * which asks the field for `getExtractedText` and keeps the password in `mExtractedText` and an
     * `ExtractEditText`. Decided from the field itself ([getCurrentInputEditorInfo]) because this runs BEFORE
     * [onStartInputView] has set [fieldProfile]. For every other field the framework's own rule stands.
     */
    /**
     * Device report 2026-10-06: on the Bottom the framework's own rule read MAIN's landscape configuration and put the
     * input method into fullscreen EXTRACT mode (about 42 `ExtractEditText` lines in the log), and the extract view overruled
     * the fill. Mimir's caption is the editor, so an input method that sits on the Bottom display is never fullscreen.
     */
    override fun onEvaluateFullscreenMode(): Boolean =
        if (currentFieldIsSecret() || isOnBottomDisplay()) false else super.onEvaluateFullscreenMode()

    /** Belt and braces: `startExtractingText` only runs while this is true, so a secret field can never feed it. */
    override fun isFullscreenMode(): Boolean =
        if (currentFieldIsSecret() || isOnBottomDisplay()) false else super.isFullscreenMode()

    /** Whether this input method is on a display other than Main's, by either source (pad13). Open so a test can say so. Never throws. */
    internal open fun isOnBottomDisplay(): Boolean = runCatching {
        imeShownOnBottom(readImeDisplays()) != null
    }.getOrDefault(false)

    private fun currentFieldIsSecret(): Boolean = try {
        mimirImeFieldProfile(currentInputEditorInfo?.inputType ?: 0).secret
    } catch (cause: Throwable) {
        if (cause is VirtualMachineError) throw cause
        // Cannot tell: refuse the extract view rather than risk feeding it a password.
        true
    }

    /** What the service is holding, for tests: ordinary text, and a secret field's three display Ints. */
    internal val heldText: String get() = typed
    internal val heldSecretDisplay: MimirSecretDisplay get() = secretDisplay

    /**
     * The enter key honours the field's OWN declared action -- Search, Go, Send, Done close the keyboard after
     * performing it, Next and Previous leave it up for the next field, a multi-line field gets a line break --
     * and never sends a blind ENTER, which would submit forms the field never asked to submit.
     */
    /** The tool row's Hide: closes the keyboard without performing the field's editor action. Never throws. */
    internal fun hideKeyboard() {
        guarded("hideKeyboard") { requestHideSelf(0) }
    }

    /**
     * The tool row's caret on an ORDINARY field: one place left (-1) or right (+1) from the selection the FIELD last reported,
     * set with `setSelection` at that absolute position (never a DPAD key, which an editor may spend on moving focus). Over a
     * selection the caret goes to its lower edge for left and its upper edge for right, as a caret key does. Left stops at 0;
     * right stays at the end. A step is a whole code point (a surrogate pair is one place). Nothing is guessed when the field has not reported a selection. The preview
     * follows from the field's next selection report. A secret field has its own discrete ops and never comes here. Never throws.
     */
    internal fun toolCaret(direction: Int) {
        BottomImeVisibility.noteKeyActivity()
        guarded("toolCaret") {
            if (fieldProfile.secret) return@guarded
            if (fieldSelStart < 0 || fieldSelEnd < 0) return@guarded
            val low = minOf(fieldSelStart, fieldSelEnd)
            val high = maxOf(fieldSelStart, fieldSelEnd)
            val connection = currentInputConnection
            // One whole code point, never half a surrogate pair: at most two characters are read beside the caret (ordinary
            // fields only; the secret return above makes no read), well inside the bound of the focus seed.
            val target: Int = when {
                low != high -> if (direction < 0) low else high
                direction < 0 -> {
                    val step = codePointStepBefore(connection?.getTextBeforeCursor(2, 0))
                    if (step == 0) return@guarded
                    (low.toLong() - step).coerceAtLeast(0L).toInt()
                }
                else -> {
                    // A null after-read does nothing: the text BEFORE the caret cannot prove a step right is safe (A|emoji B).
                    val step = codePointStepAfter(connection?.getTextAfterCursor(2, 0))
                    if (step == 0) return@guarded
                    (high.toLong() + step).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                }
            }
            // Optimistic only when the editor took the selection: the next press reads the caret from here, not from a report that
            // may not have arrived yet (a rapid double press would otherwise step from the OLD position and land inside a pair).
            // The editor's own report corrects it. A refused setSelection (or no connection) moves nothing.
            // The flag is raised BEFORE the call: an editor may report the new selection from inside it.
            val flagged = resyncFromField
            resyncFromField = true
            if (connection?.setSelection(target, target) != true) {
                resyncFromField = flagged
                return@guarded
            }
            fieldSelStart = target
            fieldSelEnd = target
        }
    }

    /**
     * How far back one place goes: 2 over a surrogate pair that ends at the caret, else 1. When nothing could be read, 1 only
     * if the seed shows no surrogate at the boundary; otherwise 0 (do nothing rather than risk splitting a pair).
     */
    private fun codePointStepBefore(before: CharSequence?): Int = when {
        before == null -> stepWhenNothingWasRead()
        before.length >= 2 && Character.isLowSurrogate(before[before.length - 1]) && Character.isHighSurrogate(before[before.length - 2]) -> 2
        else -> 1
    }

    /** How far forward one place goes: 2 over a surrogate pair that starts at the caret, 1 over any other character, 0 at the end or when nothing was read. */
    private fun codePointStepAfter(after: CharSequence?): Int = when {
        after == null -> 0
        after.isEmpty() -> 0
        after.length >= 2 && Character.isHighSurrogate(after[0]) && Character.isLowSurrogate(after[1]) -> 2
        else -> 1
    }

    /** A null BEFORE-read: step by 1 only when the bounded seed (the text before the caret) shows no surrogate at the boundary. */
    private fun stepWhenNothingWasRead(): Int = if (typed.isNotEmpty() && Character.isSurrogate(typed.last())) 0 else 1

    /** Whether the FIELD reports a non-empty selection: what the keyboard's Backspace and Space act on, whatever the preview holds. */
    internal fun fieldSelectionActive(): Boolean = !fieldProfile.secret && fieldHasSelection()

    /**
     * Backspace over the field's selection (Select all leaves the preview empty, so its own Backspace would have nothing to
     * delete): a commit of nothing over the selection, in the field. The preview follows from the field's next report.
     * Never throws; a secret field keeps its own discrete ops.
     */
    internal fun toolDeleteSelection() {
        guarded("toolDeleteSelection") {
            if (fieldProfile.secret || !fieldHasSelection()) return@guarded
            resyncFromField = true
            currentInputConnection?.commitText("", 1)
        }
    }

    /**
     * The tool row's Select all on an ORDINARY field: the editor's own select-all through the connection, so it selects the
     * whole field and not the preview prefix. No text is read to do it. A secret field has no Select all. Never throws.
     */
    internal fun toolSelectAll() {
        guarded("toolSelectAll") {
            if (fieldProfile.secret) return@guarded
            resyncFromField = true
            currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
        }
    }

    internal fun finishEditing() {
        guarded("finishEditing") {
            val info = currentInputEditorInfo
            when (val enter = mimirImeEnterBehavior(info?.imeOptions ?: 0, info?.inputType ?: 0)) {
                is MimirImeEnter.PerformAction -> {
                    currentInputConnection?.performEditorAction(enter.action)
                    if (enter.hideAfter) requestHideSelf(0)
                }
                MimirImeEnter.InsertNewline -> if (!fieldProfile.secret) applyValue(typed + "\n")
                MimirImeEnter.Hide -> requestHideSelf(0)
            }
        }
    }

    // The plain fallback keeps NO text at all: it commits straight to the field and deletes by count.
    private fun commitFallbackText(text: String) {
        guarded("fallbackText") {
            currentInputConnection?.commitText(text, 1)
            // A commit replaces any selection, so the caret is collapsed until the field reports otherwise.
            collapseFieldSelection()
        }
    }

    /**
     * D33 (c): a backspace over a selection the field reported deletes the SELECTION (commit nothing over it);
     * `deleteSurroundingText` excludes selected text, so it would leave the selection and delete the wrong thing.
     * With nothing selected it deletes one character before the caret, as before.
     */
    private fun backspaceFallback() {
        guarded("fallbackBackspace") {
            val connection = currentInputConnection ?: return@guarded
            if (fieldSelStart >= 0 && fieldSelEnd >= 0 && fieldSelStart != fieldSelEnd) {
                connection.commitText("", 1)
                collapseFieldSelection()
            } else {
                connection.deleteSurroundingText(1, 0)
            }
        }
    }

    private fun collapseFieldSelection() {
        if (fieldSelStart >= 0 && fieldSelEnd >= 0) {
            val low = minOf(fieldSelStart, fieldSelEnd)
            fieldSelStart = low
            fieldSelEnd = low
        }
    }

    private fun leaveForAnotherKeyboard() {
        guarded("switch") {
            if (!switchToNextInputMethod(false)) {
                getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
            }
        }
    }

    /**
     * What the caption says this keyboard is typing into.
     *
     * Uses the field's own declared hint or label when it has one, because that is the field TELLING us
     * what it is. Falls back to a role rather than a guess — a package id is not a name. A password field
     * never reads a hint at all.
     */
    private fun currentTargetLabel(profile: MimirImeFieldProfile): String {
        if (profile.secret) return SecretTargetLabel
        val info = currentInputEditorInfo ?: return DefaultTargetLabel
        val declared = info.hintText?.toString()?.trim()?.takeIf(String::isNotEmpty)
            ?: info.label?.toString()?.trim()?.takeIf(String::isNotEmpty)
        return declared ?: DefaultTargetLabel
    }

    /** Runs [block]; any throw is logged as a fixed line and swallowed, so a fault never escapes the service. */
    private inline fun guarded(where: String, block: () -> Unit) {
        try {
            block()
        } catch (cause: Throwable) {
            if (cause is VirtualMachineError) throw cause
            logFailure(where, cause)
        }
    }

    /**
     * The only logging in this service: a fixed tag, a fixed place name, and the exception's CLASS. Never its
     * message, never the typed text, never the field's hint -- a password must not reach a log by any route.
     */
    private fun logFailure(where: String, cause: Throwable) {
        Log.w(LogTag, "$where failed cause=${cause.javaClass.simpleName}")
    }

    internal companion object {
        const val LogTag = "MimirIme"

        /** Enough to backspace through a realistic field without an unbounded read on every focus. */
        const val MaxSeededPrefix = 1_000

        const val DefaultTargetLabel = "Type"
        const val SecretTargetLabel = "Password"

    }
}
