package com.gruning.mimir.rewrite.core.companionnative

import java.util.concurrent.CopyOnWriteArraySet

/**
 * Process-wide: the display Mimir's own input method is showing its keyboard on, or null when it shows none.
 *
 * The keyboard on the Bottom fills the whole display (`MimirIme: ime fill decided … fill=538x468dp`) and the
 * Bottom's native overlay windows (the ᛗ, the edge-back strips) are `TYPE_APPLICATION_OVERLAY`. Observed on the
 * Thor (not a platform rule: stock Android documents app overlays below the input method), while a keyboard
 * was up the NATIVE_EDGE_BACK strip kept taking touches and reclaimed the Bottom, and the ᛗ was still there
 * Both overlays must be unavailable while the Bottom keyboard is open. Those windows live in this module and the input method in the app, in ONE process, so the
 * input method writes here and the overlay controller listens.
 *
 * Only Mimir's own input method reports. No unprivileged API tells an overlay that ANOTHER keyboard is up on a
 * display (its insets reach only the focused window, and the accessibility window list needs a service Mimir
 * does not run), so with a third-party keyboard nothing is reported and the overlays behave as they always did.
 * Nothing here reads or writes a setting.
 */
object BottomImeVisibility {
    private val lock = Any()
    private var reporter: Any? = null

    /** The display the keyboard is shown on, or null. */
    @Volatile
    var displayId: Int? = null
        private set

    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    /**
     * [reporter] says its keyboard is shown on [displayId], or (null) that it is not. A hide from anyone but the
     * reporter that last showed it is ignored, so an old service instance being destroyed cannot clear the new one's
     * keyboard. Listeners run on the caller's thread and only when the value changed.
     */
    fun report(reporter: Any, displayId: Int?) {
        synchronized(lock) {
            if (displayId == null && this.reporter !== reporter) return
            this.reporter = if (displayId == null) null else reporter
            if (this.displayId == displayId) return
            this.displayId = displayId
        }
        listeners.forEach { runCatching { it() } }
    }

    /** [listener] is called when the display changes. Held for the life of the process. */
    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private val keyListeners = CopyOnWriteArraySet<() -> Unit>()

    /** [listener] is called for every key Mimir's keyboard reports ([noteKeyActivity]). Held for the life of the process. */
    fun addKeyActivityListener(listener: () -> Unit) {
        keyListeners.add(listener)
    }

    /**
     * Mimir's keyboard says a key was pressed. The ᛗ's outside-touch watcher used to tell Main's screen idle that someone was typing on
     * the Bottom; with the ᛗ gone under the keyboard nothing does (a `commitText` is no Activity interaction), so Main could dim both
     * screens mid-word. The overlay controller listens and reports it as passive activity, which re-arms an awake session and never
     * wakes a dimmed one. Nothing listens, nothing happens: the keyboard never builds the controller for this.
     */
    fun noteKeyActivity() {
        keyListeners.forEach { runCatching { it() } }
    }

    /** Tests only: back to "no keyboard" and no listeners, whoever left it so (the process singleton outlives a test class). */
    fun resetForTest() {
        synchronized(lock) {
            reporter = null
            displayId = null
        }
        listeners.clear()
        keyListeners.clear()
    }
}

/**
 * Whether a keyboard is up on the display the Bottom's overlays are mounted on. Only the Bottom's own role stands
 * down: the single-display badge on Main does not stand down.
 */
internal fun companionBottomImeShown(
    role: CompanionOverlayRole?,
    mountedDisplayId: Int?,
    imeDisplayId: Int?,
): Boolean = role == CompanionOverlayRole.SECONDARY_COMPANION && mountedDisplayId != null && mountedDisplayId == imeDisplayId

/**
 * Whether the mounted overlay root is shown. [visible] is the topology's structural verdict ([CompanionOverlayController.setVisible]);
 * a keyboard on the Bottom takes the ᛗ BUTTON away on top of it, so the two sources can never fight over one flag. An
 * open menu is not the button and is left alone.
 */
internal fun companionOverlayViewVisible(visible: Boolean, menuOpen: Boolean, bottomImeShown: Boolean): Boolean =
    visible && (menuOpen || !bottomImeShown)
