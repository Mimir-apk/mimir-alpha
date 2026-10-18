package com.gruning.mimir.rewrite

import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import com.gruning.mimir.rewrite.core.companionnative.AndroidCompanionOverlayController
import com.gruning.mimir.rewrite.core.companionnative.BottomImeVisibility
import com.gruning.mimir.rewrite.core.companionnative.OverlayScreenActivity
import com.gruning.mimir.rewrite.core.ui.MimirTrackpadHost
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.util.ReflectionHelpers

/**
 * The ᛗ is unavailable while Mimir's keyboard is open on the Bottom. The Bottom's native overlays read one process-wide
 * signal, [BottomImeVisibility], and Mimir's keyboard is its only writer. It says WHICH display the keyboard is up on, from the
 * same two display reads that decide the fill ([imeShownOnBottom]), and clears when the window goes down.
 *
 * A third-party keyboard writes nothing here (no unprivileged API tells an overlay another IME is up), so for one the overlays
 * behave as they always did.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class MimirImeBottomVisibilityTest {

    /** The real service with its device-only seams cut, as the hardening test's does; the display reads are the test's. */
    class Service : MimirInputMethodService() {
        internal var editor: EditorInfo? = null
        internal var readsForTest: ImeDisplayReads? = null
        var inputViewShown = true
        private val store = MemoryImeSafetyStore()

        override fun readImeDisplays(): ImeDisplayReads = readsForTest ?: super.readImeDisplays()
        override fun isInputViewShown(): Boolean = inputViewShown
        override val safetyStore: ImeSafetyStore get() = store
        override val safetyProcessToken: String get() = "process-1"
        override fun safetyVersionStamp(): Long = 1L
        override fun revertDefaultForSafety() = Unit
        override fun reportFieldWaiting(waiting: Boolean) = Unit
        override fun trackpadHostForKeyboard(): MimirTrackpadHost? = null
        override fun getCurrentInputEditorInfo(): EditorInfo? = editor
        override fun buildComposeKeyboardView(): View = View(this)
    }

    private fun service(reads: ImeDisplayReads = ImeDisplayReads(decor = bottom, ctx = bottom)): Service =
        Robolectric.setupService(Service::class.java).also {
            it.readsForTest = reads
            it.editor = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }
        }

    @Before
    fun start() {
        BottomImeVisibility.resetForTest()
        ReflectionHelpers.setStaticField(AndroidCompanionOverlayController::class.java, "shared", null)
    }

    @After
    fun clear() {
        BottomImeVisibility.resetForTest()
        ReflectionHelpers.setStaticField(AndroidCompanionOverlayController::class.java, "shared", null)
    }

    @Test
    fun `starting the input view on the Bottom publishes that display and hiding the window clears it`() {
        val service = service()
        service.onStartInputView(service.editor, false)
        assertEquals(4, BottomImeVisibility.displayId)

        service.onWindowHidden()
        assertNull(BottomImeVisibility.displayId)
    }

    @Test
    fun `showing the window publishes it too`() {
        val service = service()
        service.onWindowShown()
        assertEquals(4, BottomImeVisibility.displayId)
    }

    @Test
    fun `a keyboard on Main publishes nothing`() {
        val service = service(ImeDisplayReads(decor = main, ctx = main))
        service.onStartInputView(service.editor, false)
        service.onWindowShown()
        assertNull(BottomImeVisibility.displayId)
    }

    @Test
    fun `the vendor's Main field with the keyboard on the second display reports that display`() {
        // pad13: the decor view still says Main while the service's own context is on the Bottom.
        val service = service(ImeDisplayReads(decor = main, ctx = bottom))
        service.onStartInputView(service.editor, false)
        assertEquals(4, BottomImeVisibility.displayId)
    }

    @Test
    fun `finishing an input view clears the signal only when the window is really down`() {
        val service = service()
        service.onStartInputView(service.editor, false)

        service.inputViewShown = true
        service.onFinishInputView(false)
        assertEquals("a restart into another field keeps the window up", 4, BottomImeVisibility.displayId)

        service.inputViewShown = false
        service.onFinishInputView(true)
        assertNull(BottomImeVisibility.displayId)
    }

    @Test
    fun `an old instance's hide does not clear the new instance's keyboard`() {
        val old = service()
        old.onWindowShown()
        val current = service()
        current.onWindowShown()

        old.onWindowHidden()
        assertEquals(4, BottomImeVisibility.displayId)

        current.onWindowHidden()
        assertNull(BottomImeVisibility.displayId)
    }

    @Test
    fun `a key press on the keyboard is passive screen activity for Main's idle, and the keyboard builds no controller for it`() {
        // Thor 10-07 review: with the ᛗ gone under a Bottom keyboard its outside-touch watcher no longer tells Main's idle that
        // someone is typing, and a commitText is no Activity interaction, so Main could dim both screens mid-word.
        val service = service()
        service.applyValue("a")
        assertNull(
            "with no controller yet, a key builds none",
            ReflectionHelpers.getStaticField<Any?>(AndroidCompanionOverlayController::class.java, "shared"),
        )

        val overlay = AndroidCompanionOverlayController.instance(RuntimeEnvironment.getApplication())
        val seen = mutableListOf<OverlayScreenActivity>()
        overlay.setScreenActivityObserver { seen += it }
        service.applyValue("ab")
        service.toolCaret(-1)
        ShadowLooper.idleMainLooper()

        assertEquals(
            "each key is OUTSIDE: it re-arms an awake session and never wakes a dimmed one",
            listOf(OverlayScreenActivity.OUTSIDE, OverlayScreenActivity.OUTSIDE),
            seen,
        )
    }

    private companion object {
        val main = ImeDisplayRead(id = 0, widthPx = 1920, heightPx = 1080)
        val bottom = ImeDisplayRead(id = 4, widthPx = 1240, heightPx = 1080)
    }
}
