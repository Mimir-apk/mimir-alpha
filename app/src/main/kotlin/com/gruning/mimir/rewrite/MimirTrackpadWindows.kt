package com.gruning.mimir.rewrite

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.gruning.mimir.rewrite.core.companionnative.SecondaryDisplaySnapshot
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import com.gruning.mimir.rewrite.core.ui.MimirTrackpadConsentPanel
import com.gruning.mimir.rewrite.core.ui.MimirTrackpadHost
import com.gruning.mimir.rewrite.core.ui.MimirTrackpadPad

private const val Tag = "MimirTrackpad"

/**
 * The display the pad and the consent card open on: the Bottom the companion has selected (the presentation display it
 * draws on), never a hard-coded id. Null while no Bottom is selected.
 */
internal fun trackpadPadDisplayId(snapshot: SecondaryDisplaySnapshot): Int? = snapshot.selectedId

/**
 * The pad window's layout: the whole Bottom, NOT_FOCUSABLE so Main keeps input focus and the input method is never
 * disturbed, and touchable (the pad takes the finger). Pure so the flags and the size are testable.
 */
internal fun trackpadPadWindowParams(type: Int, width: Int, height: Int): WindowManager.LayoutParams =
    WindowManager.LayoutParams(
        width,
        height,
        type,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        title = "MimirTrackpadPad"
    }

/** What the Bottom menu's Trackpad row does for the state of the device and of the service. */
internal enum class TrackpadMenuAction { UNSUPPORTED, OPEN_PAD, SHOW_CONSENT }

internal fun trackpadMenuAction(capable: Boolean, serviceConnected: Boolean): TrackpadMenuAction = when {
    !capable -> TrackpadMenuAction.UNSUPPORTED
    serviceConnected -> TrackpadMenuAction.OPEN_PAD
    else -> TrackpadMenuAction.SHOW_CONSENT
}

/** What the Bottom menu's Trackpad row did. [refusal] says why nothing opened, for the router's line. */
internal data class BottomTrackpadOpenResult(val opened: Boolean, val refusal: String? = null)

/** Opens the pad (service on) or its consent card (service off) on the Bottom. Main thread. */
internal fun interface MButtonBottomTrackpadGateway {
    fun open(): BottomTrackpadOpenResult
}

/** The router's gateway for the Bottom menu's Trackpad row. Main thread only (windows are added from it). */
internal class BottomTrackpadMenuGateway(private val context: Context) : MButtonBottomTrackpadGateway {
    override fun open(): BottomTrackpadOpenResult {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            return BottomTrackpadOpenResult(opened = false, refusal = "not_main_thread")
        }
        return MimirTrackpadRuntime.openFromBottomMenu(context)
    }
}

/** Where a pad's content is put on screen: the real Bottom window in production, a fake in a unit test. */
internal interface PadSurface {
    val isMounted: Boolean

    /** Adds the window with [content]. False when it could not be added (nothing is shown). */
    fun mount(content: @Composable () -> Unit): Boolean

    fun unmount()
}

/**
 * A Compose window of its own on a given display, owned by nothing but this object: its lifecycle, view-model store
 * and saved state live here, and they are set on the ComposeView, which IS the root of a window added through
 * `WindowManager` (the input method's decor-root lesson is about a Dialog wrapping its view; there is no wrapper here).
 */
internal class BottomOverlayWindow(
    private val appContext: Context,
    private val windowType: Int,
    private val displayIdProvider: () -> Int?,
    private val windowContextFor: (Display) -> Context?,
    private val logName: String,
) : PadSurface {
    private class Owner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val savedStateController = SavedStateRegistryController.create(this)
        private var destroyed = false

        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
        override val viewModelStore = ViewModelStore()

        fun create() {
            savedStateController.performRestore(null)
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }

        fun destroy() {
            if (destroyed) return
            destroyed = true
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
            viewModelStore.clear()
        }
    }

    private var view: ComposeView? = null
    private var owner: Owner? = null
    private var windowManager: WindowManager? = null

    override val isMounted: Boolean get() = view != null

    /** Adds the window full-size on the Bottom. False (with a fixed log line, no detail) when it could not be added. */
    override fun mount(content: @Composable () -> Unit): Boolean {
        if (view != null) return true
        val displayId = displayIdProvider()
        if (displayId == null) {
            Log.w(Tag, "$logName needs a Bottom display")
            return false
        }
        val display = runCatching {
            appContext.getSystemService(DisplayManager::class.java).getDisplay(displayId)
        }.getOrNull()
        if (display == null) {
            Log.w(Tag, "$logName display is gone")
            return false
        }
        return runCatching {
            val context = windowContextFor(display) ?: error("no window context")
            val manager = context.getSystemService(WindowManager::class.java)
            val size = Point()
            @Suppress("DEPRECATION")
            display.getRealSize(size)
            val windowOwner = Owner().also { it.create() }
            val compose = ComposeView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setViewTreeLifecycleOwner(windowOwner)
                setViewTreeViewModelStoreOwner(windowOwner)
                setViewTreeSavedStateRegistryOwner(windowOwner)
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent(content)
            }
            manager.addView(compose, trackpadPadWindowParams(windowType, size.x, size.y))
            view = compose
            owner = windowOwner
            windowManager = manager
            true
        }.getOrElse {
            Log.w(Tag, "$logName window could not be added cause=${it.javaClass.simpleName}")
            owner?.destroy()
            owner = null
            false
        }
    }

    override fun unmount() {
        val mounted = view ?: return
        view = null
        runCatching { windowManager?.removeViewImmediate(mounted) }
        windowManager = null
        owner?.destroy()
        owner = null
    }
}

/**
 * The full-screen Bottom trackpad. An overlay owned by Mimir's Accessibility service (TYPE_ACCESSIBILITY_OVERLAY, which
 * needs no overlay grant), NOT_FOCUSABLE, on the Bottom display. There is deliberately NO fallback window type: if the
 * accessibility overlay cannot be added the pad shows nothing and logs one fixed line.
 *
 * It closes only on its own Close plate (and the Keyboard plate, which is the same close), the screen going off or the
 * lock screen, and the service being disabled. An injected click, the input method hiding or the Keys key do not touch it.
 */
internal class MimirTrackpadPadWindow(
    private val begin: () -> MimirTrackpadHost,
    private val surface: PadSurface,
    private val serviceConnected: () -> Boolean = { MimirTrackpadRuntime.serviceConnected },
    /** The keyguard is up. A lock ends the pad even while the screen stays on, and a pad is never opened over one. */
    private val locked: () -> Boolean = { false },
    private val themeId: () -> MimirThemeId = { ThemeStore.themeId.value },
    private val loadTheme: () -> Unit = {},
    private val main: Handler = Handler(Looper.getMainLooper()),
) {
    private val closingState = mutableStateOf(false)
    private var handle: MimirTrackpadHost? = null
    private var removal: Runnable? = null

    /** True while the pad is on screen and not on its way out. */
    val isOpen: Boolean get() = surface.isMounted && !closingState.value

    fun open(): Boolean {
        if (isOpen) return true
        // A pad still animating out is finished first, so a quick reopen starts clean.
        finishRemoval()
        if (!serviceConnected()) return false
        if (locked()) {
            Log.i(Tag, "pad refused while locked")
            return false
        }
        loadTheme()
        val theme = themeId()
        val session = begin()
        closingState.value = false
        val mounted = surface.mount {
            MimirTheme(themeId = theme) {
                val waiting by MimirTrackpadRuntime.fieldWaiting.collectAsState()
                MimirTrackpadPad(
                    session = session,
                    keyboardWaiting = waiting,
                    onKeyboard = ::close,
                    onClose = ::close,
                    closing = closingState.value,
                    onClosed = ::padLeft,
                )
            }
        }
        if (!mounted) {
            session.end()
            return false
        }
        handle = session
        scheduleLockCheck()
        return true
    }

    /**
     * A lock does not always switch the screen off, so the screen-off receiver is not enough: while the pad is up the
     * keyguard is looked at once a [LockPollMs]. A locked device ends the activation (a held press gets its owed release
     * first) and removes the window at once, with no exit animation, so nothing is left above the keyguard.
     */
    private val lockCheck = object : Runnable {
        override fun run() {
            if (!surface.isMounted) return
            if (locked()) {
                Log.i(Tag, "pad ended: device locked")
                val ending = handle
                handle = null
                ending?.end()
                finishRemoval()
                return
            }
            main.postDelayed(this, LockPollMs)
        }
    }

    private fun scheduleLockCheck() {
        main.removeCallbacks(lockCheck)
        main.postDelayed(lockCheck, LockPollMs)
    }

    /**
     * The player closed the pad: the activation ends (a held press is released) and the pad plays out. The window goes when the pad's
     * own leave ends ([padLeft], on the clock it rose on: [MimirTrackpadPadRampMs]); the timer is only a leak guard for a display
     * that draws no frame (audit 2026-10-14, RC-22: close used to be a wall-clock OverlayMs + 60 ms against an open of OverlayMs).
     */
    fun close() {
        if (!surface.isMounted || closingState.value) return
        closingState.value = true
        handle?.end()
        handle = null
        val remove = Runnable { finishRemoval() }
        removal = remove
        main.postDelayed(remove, NativePanelExitBackstopMs)
    }

    /** The pad finished leaving, on its own frame clock: the window goes now. Posted, so no composition is disposed inside its own frame. */
    internal fun padLeft() {
        if (!closingState.value) return
        main.post { if (closingState.value && surface.isMounted) finishRemoval() }
    }

    /**
     * The activation was ended from outside (the screen went off, the service went away): the window goes at once, with
     * no exit animation, and the activation is already over.
     */
    fun killNow() {
        handle = null
        finishRemoval()
    }

    private fun finishRemoval() {
        main.removeCallbacks(lockCheck)
        removal?.let(main::removeCallbacks)
        removal = null
        surface.unmount()
        closingState.value = false
    }

    companion object {
        const val LockPollMs = 1_000L

        /** The production pad: an accessibility overlay on the Bottom the companion selected. */
        fun forBottom(appContext: Context, begin: () -> MimirTrackpadHost): MimirTrackpadPadWindow =
            MimirTrackpadPadWindow(
                begin = begin,
                surface = BottomOverlayWindow(
                    appContext = appContext,
                    windowType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    displayIdProvider = { bottomDisplayId(appContext) },
                    windowContextFor = { display ->
                        MimirTrackpadRuntime.connectedService()
                            ?.createDisplayContext(display)
                            ?.createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
                    },
                    logName = "pad",
                ),
                locked = { trackpadKeyguardLocked(appContext) },
                loadTheme = { ThemeStore.load(appContext) },
            )
    }
}

/**
 * The consent card on the Bottom, for the Bottom menu's Trackpad row while Mimir's Accessibility service is off.
 * There is no service to own an overlay yet, so this is an application-overlay window (the permission the ᛗ coin already
 * needs); without that permission nothing is shown and one fixed line is logged.
 */
internal class MimirTrackpadConsentWindow(
    private val appContext: Context,
    private val openSettings: () -> Unit,
    displayIdProvider: () -> Int? = { bottomDisplayId(appContext) },
) {
    private val window = BottomOverlayWindow(
        appContext = appContext,
        windowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        displayIdProvider = displayIdProvider,
        windowContextFor = { display ->
            if (Settings.canDrawOverlays(appContext)) {
                appContext.createDisplayContext(display)
                    .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            } else {
                null
            }
        },
        logName = "consent",
    )

    val isShowing: Boolean get() = window.isMounted

    fun show(): Boolean {
        if (window.isMounted) return true
        ThemeStore.load(appContext)
        val themeId = ThemeStore.themeId.value
        return window.mount {
            MimirTheme(themeId = themeId) {
                MimirTrackpadConsentPanel(
                    onOpenSettings = {
                        openSettings()
                        window.unmount()
                    },
                    onDismiss = { window.unmount() },
                )
            }
        }
    }

    fun dismiss() = window.unmount()
}

/**
 * Whether the keyguard is up (the device is locked), whatever the screen is doing. A read that FAILS counts as locked: the
 * pad is refused and an open one ends, because "could not tell" is not a licence to inject over a lock screen.
 */
internal fun trackpadKeyguardLocked(context: Context): Boolean = keyguardLockedOrClosed {
    context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
}

internal fun keyguardLockedOrClosed(read: () -> Boolean): Boolean = runCatching(read).getOrDefault(true)

/** The Bottom display the companion has selected, read from the process runtime. Null when none is selected. */
internal fun bottomDisplayId(context: Context): Int? = runCatching {
    trackpadPadDisplayId(
        CompanionProcessRuntime.services(context.applicationContext).secondaryDisplaySelector.snapshot.value,
    )
}.getOrNull()
