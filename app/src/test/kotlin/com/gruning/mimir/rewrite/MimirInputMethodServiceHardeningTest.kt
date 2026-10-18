package com.gruning.mimir.rewrite

import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import com.gruning.mimir.rewrite.core.ui.MimirKeyboardFieldKind
import com.gruning.mimir.rewrite.core.ui.MimirSecretDisplay
import com.gruning.mimir.rewrite.core.ui.MimirSecretEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * W2f hardening of [MimirInputMethodService], now that it may be the system default keyboard: a fault never
 * escapes the service or leaves it without a keyboard, each field class picks its layout, the enter key honours
 * the field's action, and a password's text never reaches a log.
 *
 * The service is driven through its public lifecycle entry points with a fake [InputConnection]; no Compose
 * window is attached, so nothing here claims composition-time throws are caught (they are not).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class MimirInputMethodServiceHardeningTest {

    /** Records what the service sent; each failure mode is switched on per test. */
    private class FakeConnection(
        private val textBeforeCursor: String = "",
    ) : InputConnectionWrapper(null, true) {
        val committed = mutableListOf<String>()
        val deletes = mutableListOf<Int>()
        val actions = mutableListOf<Int>()
        val keysDown = mutableListOf<Int>()
        val selections = mutableListOf<Pair<Int, Int>>()
        var throwOnCommit: String? = null
        var throwOnRead = false

        /** Every call that would read the field's text; a secret field must make none. */
        val reads = mutableListOf<String>()

        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? {
            reads += "textAfter"
            return ""
        }

        override fun getSelectedText(flags: Int): CharSequence? {
            reads += "selected"
            return null
        }

        override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): android.view.inputmethod.SurroundingText? {
            reads += "surrounding"
            return null
        }

        override fun getExtractedText(request: android.view.inputmethod.ExtractedTextRequest?, flags: Int): android.view.inputmethod.ExtractedText? {
            reads += "extracted"
            return null
        }

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? {
            reads += "textBefore"
            if (throwOnRead) throw IllegalStateException("read failed")
            return textBeforeCursor.take(n)
        }

        override fun beginBatchEdit(): Boolean = true
        override fun endBatchEdit(): Boolean = true

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            deletes += beforeLength
            return true
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            throwOnCommit?.let { throw IllegalStateException(it) }
            committed += text.toString()
            return true
        }

        override fun setSelection(start: Int, end: Int): Boolean {
            selections += start to end
            return true
        }

        override fun sendKeyEvent(event: KeyEvent?): Boolean {
            if (event != null && event.action == KeyEvent.ACTION_DOWN) keysDown += event.keyCode
            return true
        }

        override fun performEditorAction(editorAction: Int): Boolean {
            actions += editorAction
            return true
        }
    }

    /** A service whose connection and editor are the test's, and whose real keyboard may be made to throw. */
    class TestService : MimirInputMethodService() {
        internal var connection: InputConnection? = null
        internal var editor: EditorInfo? = null
        internal var buildFailure: Throwable? = null
        internal var store: ImeSafetyStore = MemoryImeSafetyStore()
        internal var token = "process-1"
        internal var version = 1L
        internal var reverts = 0

        /** D38: what the trail on disk said about the owed revert at the moment the revert was started. */
        internal var revertDemandedWhenRevertStarted: Boolean? = null
        internal var trackpad: com.gruning.mimir.rewrite.core.ui.MimirTrackpadHost? = null
        internal var fillForTest: ImeBottomFill? = null

        /** pad13: when set, the REAL decision runs from these two display reads (decor view, service context) instead of [fillForTest]. */
        internal var readsForTest: ImeDisplayReads? = null
        internal val waitingReports = mutableListOf<Boolean>()

        override fun reportFieldWaiting(waiting: Boolean) {
            waitingReports += waiting
        }

        override fun readImeDisplays(): ImeDisplayReads = readsForTest ?: super.readImeDisplays()
        override fun bottomFillNow(): ImeBottomFill? = if (readsForTest != null) super.bottomFillNow() else fillForTest
        override fun isOnBottomDisplay(): Boolean = if (readsForTest != null) super.isOnBottomDisplay() else fillForTest != null

        override val safetyStore: ImeSafetyStore get() = store
        override val safetyProcessToken: String get() = token
        override fun safetyVersionStamp(): Long = version
        override fun revertDefaultForSafety() {
            reverts += 1
            revertDemandedWhenRevertStarted = store.read().revertDemanded
        }

        override fun trackpadHostForKeyboard(): com.gruning.mimir.rewrite.core.ui.MimirTrackpadHost? = trackpad
        override fun getCurrentInputConnection(): InputConnection? = connection
        override fun getCurrentInputEditorInfo(): EditorInfo? = editor

        override fun buildComposeKeyboardView(): View {
            buildFailure?.let { throw it }
            return View(this)
        }
    }

    private fun service(): TestService = Robolectric.setupService(TestService::class.java)

    private fun editorInfo(inputType: Int, imeOptions: Int = 0) = EditorInfo().apply {
        this.inputType = inputType
        this.imeOptions = imeOptions
    }

    private fun logLines(): List<String> = ShadowLog.getLogs().map { "${it.tag}: ${it.msg}" }

    @Test
    fun aThrowingKeyboardBuildFallsBackToAWorkingViewInsteadOfCrashing() {
        ShadowLog.clear()
        val service = service()
        service.buildFailure = IllegalStateException("message that must not be logged")

        val view = service.onCreateInputView()

        assertTrue("the fallback keyboard, not nothing", view is MimirImeFallbackKeyboard)
        val log = logLines().filter { it.startsWith("MimirIme") }
        assertEquals(listOf("MimirIme: inputView failed cause=IllegalStateException"), log)
        assertTrue(logLines().none { "must not be logged" in it })
    }

    @Test
    fun anErrorWhileBuildingIsAlsoCaught() {
        val service = service()
        service.buildFailure = NoClassDefFoundError("compose")
        assertTrue(service.onCreateInputView() is MimirImeFallbackKeyboard)
    }

    @Test
    fun theFallbackKeyboardTypesBacksSpacesEntersAndCanLeave() {
        val service = service()
        val connection = FakeConnection()
        service.connection = connection
        service.editor = editorInfo(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_SEARCH)
        service.buildFailure = IllegalStateException("x")
        val keyboard = service.onCreateInputView() as MimirImeFallbackKeyboard

        assertTrue(keyboard.press("a"))
        assertTrue(keyboard.press("@"))
        assertTrue(keyboard.press("⌫"))
        assertTrue(keyboard.press("↵"))

        assertEquals(listOf("a", "@"), connection.committed)
        assertEquals(listOf(1), connection.deletes)
        assertEquals(listOf(EditorInfo.IME_ACTION_SEARCH), connection.actions)
        assertTrue("always a way out to another keyboard", keyboard.keyCaptions().contains("⌨"))
    }

    @Test
    fun aFailingConnectionCostsTheKeystrokeNotTheService() {
        val service = service()
        val connection = FakeConnection(textBeforeCursor = "abc").apply {
            throwOnCommit = "boom"
            throwOnRead = true
        }
        service.connection = connection

        // None of these may throw.
        service.onStartInputView(editorInfo(InputType.TYPE_CLASS_TEXT), false)
        service.applyValue("x")
        service.finishEditing()
        service.onFinishInputView(true)

        assertTrue(logLines().any { it == "MimirIme: startInputView failed cause=IllegalStateException" })
        assertTrue(logLines().any { it == "MimirIme: applyValue failed cause=IllegalStateException" })
    }

    @Test
    fun eachFieldClassPicksItsLayoutOnStart() {
        val service = service()
        fun kindFor(type: Int, secret: Boolean = false): MimirKeyboardFieldKind {
            service.onStartInputView(editorInfo(type), false)
            assertEquals(secret, service.fieldProfile.secret)
            return service.fieldProfile.kind
        }
        assertEquals(MimirKeyboardFieldKind.TEXT, kindFor(InputType.TYPE_CLASS_TEXT))
        assertEquals(MimirKeyboardFieldKind.NUMBER, kindFor(InputType.TYPE_CLASS_NUMBER))
        assertEquals(MimirKeyboardFieldKind.NUMBER, kindFor(InputType.TYPE_CLASS_PHONE))
        assertEquals(
            MimirKeyboardFieldKind.EMAIL,
            kindFor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS),
        )
        assertEquals(
            MimirKeyboardFieldKind.URI,
            kindFor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI),
        )
        assertEquals(
            MimirKeyboardFieldKind.TEXT,
            kindFor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, secret = true),
        )
        // And a field with no info at all falls back rather than throwing.
        service.onStartInputView(null, false)
        assertEquals(MimirImeFieldProfile.Default, service.fieldProfile)
    }

    private val dot = "•"

    @Test
    fun aSecretFieldNeverReadsItsTextAndHoldsNoTypedCharactersAfterManyKeys() {
        ShadowLog.clear()
        val service = service()
        val connection = FakeConnection(textBeforeCursor = "hunter2")
        service.connection = connection
        service.editor = editorInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        // The field reports where its caret is, without any text: that is the only thing the service may use.
        val info = service.editor!!.apply { initialSelStart = 7; initialSelEnd = 7 }
        service.onStartInputView(info, false)

        assertEquals("the caption shows as many dots as the caret position says", 7, service.heldSecretDisplay.count)
        assertEquals(7, service.heldSecretDisplay.caret)
        assertEquals("", service.heldText)
        assertTrue("no read of the field text at all: ${connection.reads}", connection.reads.isEmpty())

        // N keys, each committed to the field at once; the whole state of the service stays three numbers.
        val typedKeys = listOf("p", "w", "1", "2", "3", "x", "y", "z")
        typedKeys.forEach { key ->
            service.applySecretEdit(MimirSecretEdit.Insert(key))
            assertEquals("no typed character is held", "", service.heldText)
        }
        assertEquals(7 + typedKeys.size, service.heldSecretDisplay.count)
        assertEquals(typedKeys, connection.committed)
        assertTrue(connection.reads.isEmpty())

        // Backspace deletes in the field by one.
        service.applySecretEdit(MimirSecretEdit.DeleteBackward)
        assertEquals(listOf(1), connection.deletes)

        // Finishing clears even the count.
        service.onFinishInputView(true)
        assertEquals(MimirSecretDisplay(), service.heldSecretDisplay)
        assertEquals("", service.heldText)
        assertTrue(connection.reads.isEmpty())
    }

    @Test
    fun theOpSequenceReachesTheFieldAsDiscreteCallsAndNothingElse() {
        val service = service()
        val connection = FakeConnection()
        service.connection = connection
        service.editor = editorInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            .apply { initialSelStart = 3; initialSelEnd = 3 }
        service.onStartInputView(service.editor, false)

        service.applySecretEdit(MimirSecretEdit.Insert("a"))
        service.applySecretEdit(MimirSecretEdit.CaretLeft)
        service.applySecretEdit(MimirSecretEdit.Insert("B"))
        service.applySecretEdit(MimirSecretEdit.DeleteBackward)
        service.applySecretEdit(MimirSecretEdit.CaretRight)
        service.applySecretEdit(MimirSecretEdit.CaretRight)
        service.applySecretEdit(MimirSecretEdit.Insert(" "))

        assertEquals(listOf("a", "B", " "), connection.committed)
        assertEquals(listOf(1), connection.deletes)
        assertEquals(
            "caret keys go to the field as its own DPAD keys, never computed from text",
            listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT),
            connection.keysDown,
        )
        assertTrue("never a read: ${connection.reads}", connection.reads.isEmpty())
        // 3 dots, +a = 4; left; +B = 5; delete = 4; right x2; +" " = 5 dots, caret at the end.
        assertEquals(5, service.heldSecretDisplay.count)
        assertEquals(5, service.heldSecretDisplay.caret)
    }

    @Test
    fun aBackspaceOverAFieldReportedSelectionDeletesTheSelectionNotOneCharacter() {
        val service = service()
        val connection = FakeConnection()
        service.connection = connection
        service.editor = editorInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            .apply { initialSelStart = 1; initialSelEnd = 4 }
        service.onStartInputView(service.editor, false)

        service.applySecretEdit(MimirSecretEdit.DeleteBackward)

        assertEquals(listOf(""), connection.committed)
        assertTrue(connection.deletes.isEmpty())
    }

    @Test
    fun theFieldOwnSelectionReportKeepsTheCaptionHonest() {
        val service = service()
        service.connection = FakeConnection()
        service.editor = editorInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            .apply { initialSelStart = 5; initialSelEnd = 5 }
        service.onStartInputView(service.editor, false)

        service.onUpdateSelection(5, 5, 2, 2, -1, -1)

        assertEquals(2, service.heldSecretDisplay.caret)
        assertEquals(5, service.heldSecretDisplay.count)
    }

    @Test
    fun aSecretFieldNeverReachesALogEvenWhenTheConnectionFailsWithItsText() {
        ShadowLog.clear()
        val service = service()
        val connection = FakeConnection()
        service.connection = connection
        service.editor = editorInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        service.onStartInputView(service.editor, false)
        connection.throwOnCommit = "pw123 hunter2"
        service.applySecretEdit(MimirSecretEdit.Insert("pw123"))
        service.onFinishInputView(true)

        val everything = ShadowLog.getLogs().joinToString("\n") { "${it.tag} ${it.msg} ${it.throwable}" }
        assertFalse(everything.contains("hunter2"))
        assertFalse(everything.contains("pw123"))
        assertTrue(logLines().any { it == "MimirIme: applySecretEdit failed cause=IllegalStateException" })
    }

    @Test
    fun noSecretPathReachesAWholeValueDiffInTheServiceSource() {
        val source = java.io.File(
            "src/main/kotlin/com/gruning/mimir/rewrite/MimirInputMethodService.kt",
        ).readText()
        val secretBody = source.substringAfter("internal fun applySecretEdit").substringBefore("override fun onUpdateSelection")
        assertFalse(secretBody.contains("diff", ignoreCase = true))
        assertFalse(secretBody.contains("commonPrefix"))
        assertFalse(secretBody.contains("commonSuffix"))
        assertFalse(secretBody.contains("getTextBeforeCursor"))
        assertFalse(secretBody.contains("getSelectedText"))
        assertFalse("the removed placeholder diff must not exist", source.contains("applySecretValue"))
        assertFalse(source.contains("heldSecretCount"))
    }

    @Test
    fun theGenericEntryPointNeverWritesASecretFieldAtAll() {
        val service = service()
        val connection = FakeConnection()
        service.connection = connection
        service.editor = editorInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        service.onStartInputView(service.editor, false)

        service.applyValue("$dot$dot${dot}q")

        assertTrue(connection.committed.isEmpty())
        assertTrue(connection.deletes.isEmpty())
        assertTrue(connection.reads.isEmpty())
    }

    @Test
    fun theFallbackKeepsNoTextEither() {
        val service = service()
        val connection = FakeConnection()
        service.connection = connection
        service.editor = editorInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        service.buildFailure = IllegalStateException("x")
        service.onStartInputView(service.editor, false)
        val keyboard = service.onCreateInputView() as MimirImeFallbackKeyboard
        keyboard.press("a")
        keyboard.press("b")
        assertEquals(listOf("a", "b"), connection.committed)
        assertEquals("", service.heldText)
        assertEquals(MimirSecretDisplay(), service.heldSecretDisplay)
        assertTrue(connection.reads.isEmpty())
    }

    // ---- D33 (c): the fallback keyboard's backspace honours a selection the field reported ---------------------------

    private fun fallbackFor(
        inputType: Int,
        selStart: Int,
        selEnd: Int,
        connection: FakeConnection,
    ): Pair<TestService, MimirImeFallbackKeyboard> {
        val service = service()
        service.connection = connection
        service.editor = editorInfo(inputType).apply { initialSelStart = selStart; initialSelEnd = selEnd }
        service.buildFailure = IllegalStateException("x")
        service.onStartInputView(service.editor, false)
        return service to (service.onCreateInputView() as MimirImeFallbackKeyboard)
    }

    @Test
    fun theFallbackBackspaceDeletesAReportedSelectionInASecretField() {
        val connection = FakeConnection()
        val (_, keyboard) = fallbackFor(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, 1, 4, connection,
        )
        keyboard.press("⌫")
        assertEquals("the selection is deleted by committing nothing over it", listOf(""), connection.committed)
        assertTrue("and not one character before the caret", connection.deletes.isEmpty())
        assertTrue(connection.reads.isEmpty())
    }

    @Test
    fun theFallbackBackspaceDeletesOneCharacterWhenNothingIsSelected() {
        val connection = FakeConnection()
        val (_, keyboard) = fallbackFor(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, 3, 3, connection,
        )
        keyboard.press("⌫")
        assertEquals(listOf(1), connection.deletes)
        assertTrue(connection.committed.isEmpty())
    }

    @Test
    fun theFallbackBackspaceHonoursASelectionTheFieldReportsLaterAndInAnOrdinaryField() {
        val connection = FakeConnection()
        val (service, keyboard) = fallbackFor(InputType.TYPE_CLASS_TEXT, 2, 2, connection)
        service.onUpdateSelection(2, 2, 0, 5, -1, -1)
        keyboard.press("⌫")
        assertEquals(listOf(""), connection.committed)
        assertTrue(connection.deletes.isEmpty())
    }

    @Test
    fun aTypedKeyOverASelectionCollapsesItSoTheNextBackspaceDeletesOneCharacter() {
        val connection = FakeConnection()
        val (_, keyboard) = fallbackFor(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, 1, 4, connection,
        )
        keyboard.press("a")
        keyboard.press("⌫")
        assertEquals(listOf("a"), connection.committed)
        assertEquals(listOf(1), connection.deletes)
    }

    // ---- Full-screen pad (2026-10-06): the keyboard coming and going never ends the pad ---------------------------

    private class CountingTrackpadHost : com.gruning.mimir.rewrite.core.ui.MimirTrackpadHost {
        var ended = 0
        override val serviceConnected = true
        override fun begin(): com.gruning.mimir.rewrite.core.ui.MimirTrackpadHost = this
        override fun pointerMove(dx: Float, dy: Float) = Unit
        override fun click() = Unit
        override fun doubleClick() = Unit
        override fun scrollBy(dx: Float, dy: Float) = Unit
        override fun scrollEnd() = Unit
        override fun scrollCancel() = Unit
        override fun holdBegin() = Unit
        override fun holdMove(dx: Float, dy: Float) = Unit
        override fun holdEnd() = Unit
        override fun openAccessibilitySettings() = Unit
        override fun end() { ended++ }
    }

    @Test
    fun theKeyboardFinishingHidingOrBeingDestroyedNeverEndsThePad() {
        val service = service()
        val host = CountingTrackpadHost()
        service.trackpad = host
        service.connection = FakeConnection()
        service.editor = editorInfo(InputType.TYPE_CLASS_TEXT)
        service.onStartInputView(service.editor, false)
        service.onFinishInputView(true)
        service.onWindowHidden()
        service.onDestroy()
        assertEquals("the pad is its own window: no keyboard teardown ends it", 0, host.ended)
        assertEquals("the pad is told a field is waiting on show, and not waiting after", listOf(true, false, false, false), service.waitingReports)
    }

    @Test
    fun aSecretFieldTakingOverDoesNotEndThePadButStillLightsItsKeyboardPlate() {
        val service = service()
        val host = CountingTrackpadHost()
        service.trackpad = host
        service.connection = FakeConnection()
        service.editor = editorInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        service.onStartInput(service.editor, true)
        service.onStartInputView(service.editor, true)
        assertEquals("only Close, screen-off or the service ending may end the pad", 0, host.ended)
        assertEquals("the Keyboard plate lights for any field", listOf(true), service.waitingReports)
    }

    @Test
    fun aBottomShowFillsTheKeyboardAndAMainShowDoesNot() {
        val service = service()
        service.connection = FakeConnection()
        service.editor = editorInfo(InputType.TYPE_CLASS_TEXT)
        service.fillForTest = null
        service.onStartInputView(service.editor, false)
        val main = android.inputmethodservice.InputMethodService.Insets().apply { contentTopInsets = 7; visibleTopInsets = 9 }
        applyBottomFillInsets(main, null)
        assertEquals("Main keeps the platform's insets", 7, main.contentTopInsets)
        assertEquals(9, main.visibleTopInsets)

        val bottom = android.inputmethodservice.InputMethodService.Insets()
        applyBottomFillInsets(bottom, ImeBottomFill(widthDp = 537f, heightDp = 468f, heightPx = 1000))
        assertEquals("the app behind is not resized to zero", 1000, bottom.contentTopInsets)
        assertEquals(1000, bottom.visibleTopInsets)
        assertEquals(
            "the whole frame is touchable",
            android.inputmethodservice.InputMethodService.Insets.TOUCHABLE_INSETS_FRAME,
            bottom.touchableInsets,
        )
    }

    @Test
    fun anInputMethodOnTheBottomIsNeverFullscreenSoTheExtractViewCannotOverruleTheFill() {
        val service = service()
        service.connection = FakeConnection()
        service.editor = editorInfo(InputType.TYPE_CLASS_TEXT)
        service.fillForTest = ImeBottomFill(widthDp = 537f, heightDp = 468f, heightPx = 936)
        assertFalse("on the Bottom the extract mode is off whatever Main's configuration says", service.onEvaluateFullscreenMode())
        assertFalse(service.isFullscreenMode())
    }

    @Test
    fun theFillIsReevaluatedOnEveryShowAndNotOnlyWhenTheViewWasBuilt() {
        val service = service()
        service.connection = FakeConnection()
        service.editor = editorInfo(InputType.TYPE_CLASS_TEXT)
        service.fillForTest = null
        service.onStartInputView(service.editor, false)
        assertNull("a Main show has no fill", service.currentBottomFill())

        val bottom = ImeBottomFill(widthDp = 537f, heightDp = 468f, heightPx = 936)
        service.fillForTest = bottom
        service.onStartInputView(service.editor, true)
        assertEquals("the next show on the Bottom fills", bottom, service.currentBottomFill())

        service.fillForTest = null
        service.onStartInput(service.editor, true)
        assertNull("a start of input back on Main drops the fill", service.currentBottomFill())

        service.fillForTest = bottom
        service.onWindowShown()
        assertEquals("a window shown on the Bottom fills", bottom, service.currentBottomFill())
    }

    @Test
    fun theFillSizeComesFromTheBottomDisplayOnly() {
        assertNull("Main (display 0) never fills", imeBottomFill(displayId = 0, widthPx = 1920, heightPx = 1080, density = 2f))
        assertNull("an unknown size never fills", imeBottomFill(displayId = 4, widthPx = 0, heightPx = 1080, density = 2f))
        val fill = imeBottomFill(displayId = 4, widthPx = 1075, heightPx = 936, density = 2f)!!
        assertEquals(537.5f, fill.widthDp, 0.01f)
        assertEquals(468f, fill.heightDp, 0.01f)
        assertEquals(936, fill.heightPx)
    }

    // ---- pad13: the Bottom is decided from the display the IME is REALLY on --------------------------------------

    private val second = ImeDisplayRead(id = 4, widthPx = 1075, heightPx = 936)
    private val main = ImeDisplayRead(id = 0, widthPx = 1920, heightPx = 1080)

    @Test
    fun aKeyboardRaisedFromTheTrackpadForAMainFieldFillsTheBottomWhenOnlyTheServiceContextSeesTheSecondDisplay() {
        val service = service()
        service.connection = FakeConnection()
        service.editor = editorInfo(InputType.TYPE_CLASS_TEXT)
        ShadowLog.clear()
        service.readsForTest = ImeDisplayReads(decor = main, ctx = second)
        service.onStartInputView(service.editor, false)
        val fill = service.currentBottomFill()
        assertEquals("decor 0 + ctx 4: the fill is the second display's size", 1075f / service.resources.displayMetrics.density, fill!!.widthDp, 0.01f)
        assertEquals(936, fill.heightPx)
        assertTrue(service.isOnBottomDisplay())
        assertFalse("and never fullscreen, so the extract view cannot overrule it", service.onEvaluateFullscreenMode())
        assertFalse(service.isFullscreenMode())
        val lines = logLines().filter { it.contains("ime fill decided") }
        assertTrue("one line per decision, ids and sizes only: $lines", lines.isNotEmpty() && lines.all { it.contains("decor=0 ctx=4 fill=") && it.contains("fullscreen=false") })
    }

    @Test
    fun aDecorOnTheSecondDisplayFillsAndTheSizeIsThatDisplays() {
        val service = service()
        service.readsForTest = ImeDisplayReads(decor = second, ctx = null)
        assertEquals(936, service.bottomFillNow()!!.heightPx)
        assertTrue(service.isOnBottomDisplay())
        service.readsForTest = ImeDisplayReads(decor = second, ctx = main)
        assertEquals("decor 4, ctx 0: the decor says Bottom", 936, service.bottomFillNow()!!.heightPx)
    }

    @Test
    @Config(qualifiers = "land")
    fun whenBothSourcesAreMainsThereIsNoFillAndTheFrameworksFullscreenRuleStands() {
        val service = service()
        val landscape = service.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        service.connection = FakeConnection()
        service.editor = editorInfo(InputType.TYPE_CLASS_TEXT)
        service.readsForTest = ImeDisplayReads(decor = main, ctx = main)
        assertNull(service.bottomFillNow())
        assertFalse(service.isOnBottomDisplay())
        assertEquals("the default fullscreen rule (landscape's extract mode), untouched", landscape, service.onEvaluateFullscreenMode())
        service.readsForTest = ImeDisplayReads(decor = null, ctx = null)
        assertNull("unreadable displays are Main's too", service.bottomFillNow())
        assertEquals(imeFillLogLine(ImeDisplayReads(main, main), null, landscape), "ime fill decided decor=0 ctx=0 fill=none fullscreen=$landscape")
    }

    @Test
    @Config(qualifiers = "land")
    fun aSecretFieldIsNeverFullscreenWhicheverSourceSaysWhichDisplay() {
        val service = service()
        service.connection = FakeConnection()
        service.editor = editorInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        for (reads in listOf(ImeDisplayReads(main, main), ImeDisplayReads(main, second), ImeDisplayReads(second, null))) {
            service.readsForTest = reads
            assertFalse("secret, $reads", service.onEvaluateFullscreenMode())
            assertFalse(service.isFullscreenMode())
        }
    }

    @Test
    fun theFillLogLineCarriesIdsAndSizesOnly() {
        val fill = imeBottomFill(displayId = 4, widthPx = 1075, heightPx = 936, density = 2f)
        assertEquals(
            "ime fill decided decor=0 ctx=4 fill=538x468dp fullscreen=false",
            imeFillLogLine(ImeDisplayReads(main, second), fill, fullscreen = false),
        )
        assertEquals("ime fill decided decor=na ctx=na fill=none fullscreen=true", imeFillLogLine(ImeDisplayReads(null, null), null, fullscreen = true))
    }

    // ---- D33 fix round, item 2: a selection beyond the display bound is still a selection ---------------------

    @Test
    fun aBackspaceOverASelectionBeyondTheDisplayBoundDeletesTheSelection() {
        val service = service()
        val connection = FakeConnection()
        service.connection = connection
        service.editor = editorInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            .apply { initialSelStart = 1001; initialSelEnd = 1005 }
        service.onStartInputView(service.editor, false)

        service.applySecretEdit(MimirSecretEdit.DeleteBackward)

        assertEquals("the FIELD's selection is deleted", listOf(""), connection.committed)
        assertTrue("not one character before a clamped caret", connection.deletes.isEmpty())
        // The selection is gone now, so the next backspace deletes one character.
        service.applySecretEdit(MimirSecretEdit.DeleteBackward)
        assertEquals(listOf(1), connection.deletes)
    }

    @Test
    fun aSelectionBeyondTheBoundIsCollapsedByACaretKeyAndFollowsLaterReports() {
        val service = service()
        val connection = FakeConnection()
        service.connection = connection
        service.editor = editorInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            .apply { initialSelStart = 2000; initialSelEnd = 3000 }
        service.onStartInputView(service.editor, false)

        service.applySecretEdit(MimirSecretEdit.CaretRight)
        service.applySecretEdit(MimirSecretEdit.DeleteBackward)
        assertEquals("the caret key collapsed it, so this deletes one character", listOf(1), connection.deletes)
        assertTrue(connection.committed.isEmpty())

        service.onUpdateSelection(0, 0, 4000, 4100, -1, -1)
        service.applySecretEdit(MimirSecretEdit.DeleteBackward)
        assertEquals(listOf(""), connection.committed)
    }

    // ---- crash-loop safety through the real service ---------------------------------------------------------------

    @Test
    fun aComposeKeyboardThatDiedWhileShowingIsReplacedByTheFallbackOnTheNextStart() {
        val store = MemoryImeSafetyStore()
        val first = service().apply { this.store = store; token = "process-1" }
        // The Compose keyboard is built and shown: the trail is armed BEFORE anything is drawn...
        first.onCreateInputView()
        assertEquals(ImeMode.COMPOSE, store.state.armedMode)
        assertEquals("process-1", store.state.armedProcess)
        // ...and the process dies in composition, so nothing ever cleared it (no disarm). A new process:
        val second = service().apply { this.store = store; token = "process-2" }
        val view = second.onCreateInputView()
        assertTrue("the plain fallback, not Compose", view is MimirImeFallbackKeyboard)
        assertTrue(store.state.fallbackForced)
        assertEquals(1, store.state.composeCrashes)
        assertEquals("one death is a fallback, not yet a revert", 0, second.reverts)
        assertEquals("the fallback is armed in turn", ImeMode.FALLBACK, store.state.armedMode)
    }

    @Test
    fun aFallbackThatDiedOrASecondComposeDeathRevertsTheDefault() {
        val store = MemoryImeSafetyStore(
            ImeSafetyState(versionStamp = 1L, armedMode = ImeMode.FALLBACK, armedProcess = "dead"),
        )
        val service = service().apply { this.store = store; token = "process-2" }
        assertTrue(service.onCreateInputView() is MimirImeFallbackKeyboard)
        assertEquals(1, service.reverts)
        assertEquals("D38: the demand was on disk before the revert started", true, service.revertDemandedWhenRevertStarted)
        assertTrue(store.state.revertDemanded)

        val twice = MemoryImeSafetyStore(
            ImeSafetyState(versionStamp = 1L, armedMode = ImeMode.COMPOSE, armedProcess = "dead", composeCrashes = 1),
        )
        val other = service().apply { this.store = twice; token = "process-3" }
        other.onCreateInputView()
        assertEquals(1, other.reverts)
        assertEquals(true, other.revertDemandedWhenRevertStarted)

        // One Compose death is a fallback, not a revert: no demand is written.
        val once = MemoryImeSafetyStore(
            ImeSafetyState(versionStamp = 1L, armedMode = ImeMode.COMPOSE, armedProcess = "dead"),
        )
        service().apply { this.store = once; token = "process-4" }.onCreateInputView()
        assertFalse(once.state.revertDemanded)
    }

    @Test
    fun aKeyboardThatDrewAndWasHiddenClearsItsTrailSoTheNextStartStaysOnCompose() {
        val store = MemoryImeSafetyStore()
        val first = service().apply { this.store = store; token = "process-1" }
        first.onCreateInputView()
        // The keyboard was closed in an orderly way.
        first.onFinishInputView(true)
        assertNull(store.state.armedMode)
        val next = service().apply { this.store = store; token = "process-2" }
        assertTrue("Compose again: nothing died", next.onCreateInputView() !is MimirImeFallbackKeyboard)
        assertEquals(0, next.reverts)
    }

    @Test
    fun aSameProcessRebuildIsNotMistakenForACrash() {
        val store = MemoryImeSafetyStore()
        val a = service().apply { this.store = store; token = "same" }
        a.onCreateInputView()
        val b = service().apply { this.store = store; token = "same" }
        assertTrue(b.onCreateInputView() !is MimirImeFallbackKeyboard)
        assertEquals(0, store.state.composeCrashes)
    }

    @Test
    fun aRealBulletInAPasswordIsInputNotAPlaceholder() {
        val service = service()
        val connection = FakeConnection()
        service.connection = connection
        service.editor = editorInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        service.onStartInputView(service.editor, false)

        // No value is compared, so a bullet is just another character: committed exactly as typed.
        service.applySecretEdit(MimirSecretEdit.Insert("a"))
        service.applySecretEdit(MimirSecretEdit.Insert(dot))
        service.applySecretEdit(MimirSecretEdit.Insert("b"))
        assertEquals(listOf("a", dot, "b"), connection.committed)
        assertEquals(3, service.heldSecretDisplay.count)

        // A paste containing bullets commits as ONE insert, whole and in order.
        service.applySecretEdit(MimirSecretEdit.Insert("x${dot}y"))
        assertEquals(listOf("a", dot, "b", "x${dot}y"), connection.committed)
        assertEquals(3 + 3, service.heldSecretDisplay.count)

        // Deletes stay one per backspace.
        service.applySecretEdit(MimirSecretEdit.DeleteBackward)
        service.applySecretEdit(MimirSecretEdit.DeleteBackward)
        assertEquals(listOf(1, 1), connection.deletes)
        assertEquals(4, service.heldSecretDisplay.count)
    }

    @Test
    @Config(qualifiers = "land")
    fun aSecretFieldIsNeverFullscreenEvenInLandscapeAndNeverFeedsTheExtractView() {
        val service = service()
        val landscape =
            service.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val connection = FakeConnection(textBeforeCursor = "hunter2")
        service.connection = connection

        service.editor = editorInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        assertFalse("secret: not fullscreen", service.onEvaluateFullscreenMode())
        assertFalse(service.isFullscreenMode())
        service.onStartInputView(service.editor, false)
        assertFalse("no extract view for a secret field", service.isExtractViewShown())
        assertTrue("no getExtractedText or any other read: ${connection.reads}", connection.reads.isEmpty())

        // The numeric PIN variation is secret too.
        service.editor = editorInfo(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        assertFalse(service.onEvaluateFullscreenMode())

        // An ordinary field keeps the framework rule, whatever it is on this configuration.
        service.editor = editorInfo(InputType.TYPE_CLASS_TEXT)
        assertEquals(landscape, service.onEvaluateFullscreenMode())
    }

    // ---- an orderly teardown is never a crash ------------------------------------------------------------------

    @Test
    fun anOrderlyDestroyBeforeTheFirstFrameLeavesNoTrailForTheNextProcess() {
        val store = MemoryImeSafetyStore()
        val controller = Robolectric.buildService(TestService::class.java)
        val first = controller.create().get().apply { this.store = store; token = "process-1" }
        first.onCreateInputView()
        assertEquals(ImeMode.COMPOSE, store.state.armedMode)

        controller.destroy()
        assertNull("destroy is orderly: the marker goes", store.state.armedMode)

        val second = service().apply { this.store = store; token = "process-2" }
        assertTrue("Compose, not the fallback", second.onCreateInputView() !is MimirImeFallbackKeyboard)
        assertEquals(0, second.reverts)
        assertFalse(store.state.fallbackForced)
        assertEquals(0, store.state.composeCrashes)
    }

    @Test
    fun anOrderlyDestroyOfTheFallbackNeverTriggersARevert() {
        val store = MemoryImeSafetyStore()
        val controller = Robolectric.buildService(TestService::class.java)
        val first = controller.create().get().apply {
            this.store = store
            token = "process-1"
            buildFailure = IllegalStateException("x")
        }
        assertTrue(first.onCreateInputView() is MimirImeFallbackKeyboard)
        assertEquals(ImeMode.FALLBACK, store.state.armedMode)

        controller.destroy()
        assertNull(store.state.armedMode)

        val second = service().apply { this.store = store; token = "process-2" }
        second.onCreateInputView()
        assertEquals("a cleanly closed fallback is not a dead one", 0, second.reverts)
        assertFalse(store.state.fallbackForced)
    }

    @Test
    fun finishAndHideAlsoClearTheTrailWhateverTheFirstFrame() {
        val store = MemoryImeSafetyStore()
        val service = service().apply { this.store = store; token = "process-1" }
        service.onCreateInputView()
        service.onStartInputView(editorInfo(InputType.TYPE_CLASS_TEXT), false)
        assertEquals(ImeMode.COMPOSE, store.state.armedMode)
        service.onFinishInputView(true)
        assertNull(store.state.armedMode)

        service.onStartInputView(editorInfo(InputType.TYPE_CLASS_TEXT), false)
        assertEquals("each show re-arms", ImeMode.COMPOSE, store.state.armedMode)
        service.onWindowHidden()
        assertNull(store.state.armedMode)
    }

    // ---- round 3: the secret path is discrete ops; the extract view is re-decided on every start ------------------

    @Test
    fun pastingABulletThenAWithTwoDotsShownCommitsExactlyThatText() {
        val service = service()
        val connection = FakeConnection()
        service.connection = connection
        service.editor = editorInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            .apply { initialSelStart = 2; initialSelEnd = 2 }
        service.onStartInputView(service.editor, false)

        // The keyboard reports the paste as ONE insert; there is no caption to reconstruct.
        service.applySecretEdit(MimirSecretEdit.Insert("•a"))

        assertEquals("exactly what was pasted, in order", listOf("•a"), connection.committed)
        assertEquals(4, service.heldSecretDisplay.count)
    }

    @Test
    @Config(qualifiers = "land")
    fun aNonSecretLandscapeFieldRestartingIntoASecretOneIsNeitherFullscreenNorExtractShown() {
        val service = service()
        service.connection = FakeConnection(textBeforeCursor = "previous field text")
        service.editor = editorInfo(InputType.TYPE_CLASS_TEXT)
        service.onStartInputView(service.editor, false)
        service.setExtractViewShown(true)

        val secretConnection = FakeConnection(textBeforeCursor = "hunter2")
        service.connection = secretConnection
        service.editor = editorInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        service.onStartInputView(service.editor, true)

        assertFalse("not fullscreen", service.isFullscreenMode())
        assertFalse("extract view hidden", service.isExtractViewShown())
        assertTrue("no read at all: ${secretConnection.reads}", secretConnection.reads.isEmpty())
    }

    @Test
    @Config(qualifiers = "land")
    fun theExtractTextFieldIsEmptiedWhenASecretFieldFollowsAVisibleOne() {
        val service = service()
        service.connection = FakeConnection()
        service.editor = editorInfo(InputType.TYPE_CLASS_TEXT)
        service.onStartInputView(service.editor, false)

        // Build the framework extract edit text the way updateFullscreenMode would, so it exists to be cleared.
        val view = service.onCreateExtractTextView()
        org.junit.Assume.assumeTrue("this Robolectric build cannot inflate the extract view", view != null)
        service.setExtractView(view)
        val extract = view!!.findViewById<android.widget.TextView>(android.R.id.inputExtractEditText)
        org.junit.Assume.assumeTrue("the extract edit text is part of the inflated view", extract != null)
        extract!!.setText("previous field text")

        val secretConnection = FakeConnection()
        service.connection = secretConnection
        service.editor = editorInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        service.onStartInput(service.editor, true)

        assertEquals("extract text cleared on the restart itself", "", extract.text.toString())
        assertTrue("clearing it never moves the password field caret: ${secretConnection.selections}", secretConnection.selections.isEmpty())
    }

    @Test
    fun everyStartInputIncludingARestartRedecidesFullscreenFromTheNewField() {
        val service = service()
        service.connection = FakeConnection()
        service.editor = editorInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        service.onStartInput(service.editor, true)
        assertFalse(service.isFullscreenMode())
        service.onStartInputView(service.editor, true)
        assertFalse(service.isFullscreenMode())
        assertFalse(service.isExtractViewShown())
    }

    @Test
    fun enterPerformsTheFieldsActionAndAMultiLineFieldGetsANewline() {
        val service = service()
        val connection = FakeConnection()
        service.connection = connection

        service.editor = editorInfo(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_GO)
        service.finishEditing()
        service.editor = editorInfo(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_NEXT)
        service.finishEditing()
        assertEquals(listOf(EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_NEXT), connection.actions)

        service.editor = editorInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        service.finishEditing()
        assertEquals(listOf("\n"), connection.committed)
        assertEquals(1, connection.actions.count { it == EditorInfo.IME_ACTION_GO })
    }

    // ---- pad4: the tool row's caret and Select all act on the FIELD, not on the bounded preview prefix --------------

    /**
     * A field with real text and selection semantics: DPAD keys move or collapse the caret, the editor's select-all selects
     * everything, a commit replaces the selection, and every change is reported back as the framework does.
     */
    private class FieldConnection(var text: String, var selStart: Int, var selEnd: Int) : InputConnectionWrapper(null, true) {
        val menuActions = mutableListOf<Int>()
        val keysDown = mutableListOf<Int>()
        val setSelections = mutableListOf<Pair<Int, Int>>()
        val commits = mutableListOf<String>()
        val readLengths = mutableListOf<Int>()
        val afterLengths = mutableListOf<Int>()
        val otherReads = mutableListOf<String>()
        var report: (oldStart: Int, oldEnd: Int, newStart: Int, newEnd: Int) -> Unit = { _, _, _, _ -> }

        private fun change(block: () -> Unit) {
            val (os, oe) = selStart to selEnd
            block()
            report(os, oe, selStart, selEnd)
        }

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? {
            readLengths += n
            if (nullBefore) return null
            val low = minOf(selStart, selEnd)
            return text.substring(maxOf(0, low - n), low)
        }

        /** A field whose editor answers nothing when asked for the text after the caret. */
        var nullAfter = false

        /** A field whose editor answers nothing when asked for the text before the caret (after the focus seed was read). */
        var nullBefore = false

        /** An editor that refuses a selection (it returns false and moves nothing). */
        var refuseSelection = false

        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? {
            afterLengths += n
            if (nullAfter) return null
            val high = maxOf(selStart, selEnd)
            return text.substring(high, minOf(text.length, high + n))
        }

        override fun getSelectedText(flags: Int): CharSequence? { otherReads += "selected"; return null }
        override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): android.view.inputmethod.SurroundingText? {
            otherReads += "surrounding"
            return null
        }

        override fun beginBatchEdit(): Boolean = true
        override fun endBatchEdit(): Boolean = true

        override fun performContextMenuAction(id: Int): Boolean {
            menuActions += id
            if (id == android.R.id.selectAll) change { selStart = 0; selEnd = text.length }
            return true
        }

        override fun setSelection(start: Int, end: Int): Boolean {
            setSelections += start to end
            if (refuseSelection) return false
            // The editor clamps to the text, as a real one does.
            change {
                selStart = start.coerceIn(0, text.length)
                selEnd = end.coerceIn(0, text.length)
            }
            return true
        }

        override fun sendKeyEvent(event: KeyEvent?): Boolean {
            if (event == null || event.action != KeyEvent.ACTION_DOWN) return true
            keysDown += event.keyCode
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> change {
                    val to = if (selStart != selEnd) maxOf(selStart, selEnd) else minOf(selStart + 1, text.length)
                    selStart = to; selEnd = to
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> change {
                    val to = if (selStart != selEnd) minOf(selStart, selEnd) else maxOf(selStart - 1, 0)
                    selStart = to; selEnd = to
                }
            }
            return true
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            commits += text.toString()
            change {
                val low = minOf(selStart, selEnd)
                val high = maxOf(selStart, selEnd)
                this.text = this.text.substring(0, low) + text.toString() + this.text.substring(high)
                selStart = low + text.toString().length
                selEnd = selStart
            }
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            change {
                val low = minOf(selStart, selEnd)
                val from = maxOf(0, low - beforeLength)
                this.text = this.text.substring(0, from) + this.text.substring(low)
                selStart = from; selEnd = from
            }
            return true
        }
    }

    /** A service on an ordinary field already holding [text] with the caret at [caret], seeded the way a real focus seeds it. */
    private fun ordinaryFieldService(text: String, caret: Int): Pair<TestService, FieldConnection> {
        val service = service()
        val connection = FieldConnection(text, caret, caret)
        service.connection = connection
        service.editor = editorInfo(InputType.TYPE_CLASS_TEXT).apply {
            initialSelStart = caret
            initialSelEnd = caret
        }
        connection.report = { os, oe, ns, ne -> service.onUpdateSelection(os, oe, ns, ne, -1, -1) }
        service.onStartInputView(service.editor, false)
        return service to connection
    }

    @Test
    fun selectAllSelectsTheWholeFieldAndAPasteReplacesAllOfItNotJustTheSeededPrefix() {
        val (service, field) = ordinaryFieldService("abcdef", 3)
        assertEquals("seeded with the text before the caret only", "abc", service.heldText)

        service.toolSelectAll()

        assertEquals("the editor's own select-all went to the connection", listOf(android.R.id.selectAll), field.menuActions)
        assertEquals("the WHOLE field is selected", 0 to 6, field.selStart to field.selEnd)
        // The keyboard's Paste computes the next text from its preview and hands it to applyValue; the field replaces the
        // whole selection, so def does not survive (pre-fix: Select all selected only the preview prefix and this gave X|def).
        service.applyValue("X")
        assertEquals("X", field.text)
        assertEquals("the preview follows where the field landed", "X", service.heldText)
    }

    @Test
    fun theRightKeyMovesTheFieldsCaretPastTheSeedAndThePreviewFollows() {
        val (service, field) = ordinaryFieldService("abcdef", 3)

        service.toolCaret(1)

        assertEquals("the caret is set at the absolute position, one past the reported one", listOf(4 to 4), field.setSelections)
        assertTrue("never a DPAD key, which an editor may spend on moving focus: ${field.keysDown}", field.keysDown.isEmpty())
        assertEquals("the field's caret moved past the seeded prefix", 4, field.selStart)
        assertEquals("abcd", service.heldText)
        service.applyValue("abcdX")
        assertEquals("a key lands where the field's caret is", "abcdXef", field.text)
    }

    @Test
    fun theLeftKeyMovesTheFieldsCaretAndACaretKeyOverASelectionCollapsesIt() {
        val (service, field) = ordinaryFieldService("abcdef", 3)
        service.toolCaret(-1)
        assertEquals(listOf(2 to 2), field.setSelections)
        assertEquals(2, field.selStart)
        assertEquals("ab", service.heldText)
        service.toolSelectAll()
        service.toolCaret(1)
        assertEquals("a caret key over a selection collapses it to its end", 6 to 6, field.selStart to field.selEnd)
        assertEquals("abcdef", service.heldText)
        assertTrue("and no DPAD key was sent: ${field.keysDown}", field.keysDown.isEmpty())
    }

    private val emoji = "A\uD83D\uDE00B"

    @Test
    fun theCaretStepsOverAWholeEmojiAndNeverSplitsASurrogatePair() {
        // A, a two-unit emoji, B: the caret at 3 is right after the emoji.
        val (service, field) = ordinaryFieldService(emoji, 3)
        service.toolCaret(-1)
        assertEquals("left over the emoji lands before it, not inside it", 1, field.selStart)
        assertEquals(listOf(1 to 1), field.setSelections)

        service.toolCaret(1)
        assertEquals("right over the emoji lands after it", 3, field.selStart)
        assertEquals(listOf(1 to 1, 3 to 3), field.setSelections)
        assertTrue("never a DPAD key: ${field.keysDown}", field.keysDown.isEmpty())
    }

    @Test
    fun theCaretMovesOneUnitOverTextAroundTheEmojiAndStaysAtTheEnd() {
        val (service, field) = ordinaryFieldService(emoji, 4)
        service.toolCaret(1)
        assertEquals("right at the end stays at the end", 4, field.selStart)
        service.toolCaret(-1)
        assertEquals("left over a plain B is one unit", 3, field.selStart)
        val (second, f2) = ordinaryFieldService(emoji, 0)
        second.toolCaret(1)
        assertEquals("right over a plain A is one unit", 1, f2.selStart)
        // A lone surrogate is not a pair: one unit.
        val (third, f3) = ordinaryFieldService("\uD83Dx", 1)
        third.toolCaret(-1)
        assertEquals(0, f3.selStart)
    }

    @Test
    fun theCaretReadsAtMostTwoCharactersBesideItAndNothingOnASecretField() {
        val (service, field) = ordinaryFieldService(emoji, 3)
        service.toolCaret(-1)
        service.toolCaret(1)
        // The caret's own reads are 2 characters; the 1000-character reads are the focus seed and the resync after a report.
        assertTrue("the caret read 2 characters before it: ${field.readLengths}", field.readLengths.contains(2))
        assertTrue("and no other read is more than the seed bound: ${field.readLengths}", field.readLengths.all { it == 2 || it == MimirInputMethodService.MaxSeededPrefix })
        assertEquals("after reads are 2 characters", listOf(2), field.afterLengths)

        val secret = service()
        val connection = FakeConnection()
        secret.connection = connection
        secret.editor = editorInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).apply {
            initialSelStart = 2
            initialSelEnd = 2
        }
        secret.onStartInputView(secret.editor, false)
        connection.reads.clear()
        secret.toolCaret(-1)
        secret.toolCaret(1)
        assertTrue("a secret field is never read by the caret: ${connection.reads}", connection.reads.isEmpty())
        assertTrue(connection.selections.isEmpty())
    }

    @Test
    fun aRapidDoubleRightOverAnEmojiWithNoReportInBetweenEndsAfterItNotInsideIt() {
        // "A", a two-unit emoji, "B", the caret at 0. Press right twice before the editor has reported the first move.
        val (service, field) = ordinaryFieldService(emoji, 0)
        field.report = { _, _, _, _ -> }
        service.toolCaret(1)
        service.toolCaret(1)
        assertEquals("one place over A, then the whole emoji", listOf(1 to 1, 3 to 3), field.setSelections)
        assertEquals("after the emoji (3), never inside it (2)", 3, field.selStart)
    }

    @Test
    fun aRapidDoubleLeftOverAnEmojiAlsoStepsOverItWhole() {
        val (service, field) = ordinaryFieldService(emoji, 4)
        field.report = { _, _, _, _ -> }
        service.toolCaret(-1)
        service.toolCaret(-1)
        assertEquals("over B, then the whole emoji", listOf(3 to 3, 1 to 1), field.setSelections)
        assertEquals(1, field.selStart)
    }

    @Test
    fun aNullAfterReadMakesRightDoNothingEvenWhenTheSeedLooksSafe() {
        // "A", the emoji, "B" with the caret at 1: the seed ("A") ends in a plain character, but the text AFTER the caret is
        // what matters for a step right, and it could not be read: nothing is guessed.
        val (service, field) = ordinaryFieldService(emoji, 1)
        field.nullAfter = true
        service.toolCaret(1)
        assertTrue("a right step with no after-read does nothing: ${field.setSelections}", field.setSelections.isEmpty())
        assertEquals(1, field.selStart)
        val (plain, plainField) = ordinaryFieldService("abc", 1)
        plainField.nullAfter = true
        plain.toolCaret(1)
        assertTrue(plainField.setSelections.isEmpty())
    }

    @Test
    fun aNullBeforeReadStepsLeftByOneOnlyWhenTheSeedShowsNoSurrogateAtTheBoundary() {
        val (plain, plainField) = ordinaryFieldService("abc", 2)
        plainField.nullBefore = true
        plain.toolCaret(-1)
        assertEquals("the seed ends in a plain character: one step", listOf(1 to 1), plainField.setSelections)

        val (risky, riskyField) = ordinaryFieldService(emoji, 3)
        riskyField.nullBefore = true
        assertEquals("the seed ends in a low surrogate", emoji.substring(0, 3), risky.heldText)
        risky.toolCaret(-1)
        assertTrue("nothing is guessed across a surrogate: ${riskyField.setSelections}", riskyField.setSelections.isEmpty())
        assertEquals(3, riskyField.selStart)
    }

    @Test
    fun aRefusedSetSelectionDoesNotAdvanceTheCachedCaret() {
        val (service, field) = ordinaryFieldService("abcdef", 2)
        field.report = { _, _, _, _ -> }
        field.refuseSelection = true
        service.toolCaret(1)
        service.toolCaret(1)
        assertEquals("both presses aimed at 3: the cache did not move on a refusal", listOf(3 to 3, 3 to 3), field.setSelections)
        field.refuseSelection = false
        service.toolCaret(1)
        service.toolCaret(1)
        assertEquals("once accepted it advances: 3 then 4", listOf(3 to 3, 3 to 3, 3 to 3, 4 to 4), field.setSelections)
    }

    @Test
    fun theCaretStopsAtZeroAndGuessesNothingWithoutAReportedSelection() {
        val (service, field) = ordinaryFieldService("abc", 0)
        service.toolCaret(-1)
        assertEquals("left at 0 stays at 0", listOf(0 to 0), field.setSelections)
        assertEquals(0, field.selStart)

        val unknown = service()
        val connection = FieldConnection("abc", 1, 1)
        unknown.connection = connection
        unknown.editor = editorInfo(InputType.TYPE_CLASS_TEXT)
        unknown.onStartInputView(unknown.editor, false)
        unknown.toolCaret(1)
        assertTrue("no reported selection, nothing set: ${connection.setSelections}", connection.setSelections.isEmpty())
    }

    @Test
    fun selectAllThenBackspaceLeavesTheFieldEmptyAndTypingThenLeavesTheTypedKey() {
        val (service, field) = ordinaryFieldService("abcdef", 3)
        service.toolSelectAll()
        assertEquals("the preview is empty, the selection starts at 0", "", service.heldText)
        assertTrue("the FIELD's selection is what Backspace and Space look at", service.fieldSelectionActive())

        service.toolDeleteSelection()

        assertEquals("a commit of nothing over the selection, in the field", listOf(""), field.commits)
        assertEquals("the field is empty (pre-fix, Backspace edited the empty preview and sent nothing)", "", field.text)
        assertEquals("", service.heldText)
        assertFalse("and the selection is gone", service.fieldSelectionActive())

        // A key typed over a selection replaces it: select everything again in a fresh field, then type.
        val (typing, second) = ordinaryFieldService("abcdef", 3)
        typing.toolSelectAll()
        typing.applyValue("X")
        assertEquals("X", second.text)
        val (spacing, third) = ordinaryFieldService("abcdef", 3)
        spacing.toolSelectAll()
        spacing.applyValue(" ")
        assertEquals("Space replaces the selection too", " ", third.text)
    }

    @Test
    fun deletingASelectionNeedsOneAndTouchesNothingElse() {
        val (service, field) = ordinaryFieldService("abcdef", 3)
        service.toolDeleteSelection()
        assertTrue("no selection, no commit: ${field.commits}", field.commits.isEmpty())
        assertEquals("abcdef", field.text)
    }

    @Test
    fun theToolOpsReadNoMoreThanTheBoundedSeedAndNeverTheSelectedOrSurroundingText() {
        val (service, field) = ordinaryFieldService("abcdef", 3)
        service.toolCaret(1)
        service.toolSelectAll()
        service.applyValue("Z")
        assertTrue("every prefix read is at most the seed bound: ${field.readLengths}", field.readLengths.all { it <= MimirInputMethodService.MaxSeededPrefix })
        assertTrue("nothing else of the field was read: ${field.otherReads}", field.otherReads.isEmpty())
    }

    @Test
    fun aSecretFieldHasNoSelectAllAndTheToolOpsNeverTouchIt() {
        val service = service()
        val connection = FakeConnection()
        service.connection = connection
        service.editor = editorInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        service.onStartInputView(service.editor, false)

        service.toolSelectAll()
        service.toolCaret(1)
        service.toolCaret(-1)
        service.toolDeleteSelection()

        assertFalse(service.fieldSelectionActive())
        assertTrue(
            "no key, no read, no selection set, no commit: ${connection.keysDown} ${connection.reads} ${connection.selections} ${connection.committed}",
            connection.keysDown.isEmpty() && connection.reads.isEmpty() && connection.selections.isEmpty() && connection.committed.isEmpty(),
        )
    }
}

/** An in-memory stand-in for the persisted safety trail. */
internal class MemoryImeSafetyStore(var state: ImeSafetyState = ImeSafetyState()) : ImeSafetyStore {
    override fun read(): ImeSafetyState = state
    override fun write(state: ImeSafetyState): Boolean {
        this.state = state
        return true
    }
}
