package com.gruning.mimir.rewrite

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.animation.PathInterpolator
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirPointerPosition
import com.gruning.mimir.rewrite.core.ui.MimirTrackpadHost
import com.gruning.mimir.rewrite.core.ui.TrackpadPointerColor
import com.gruning.mimir.rewrite.core.ui.TrackpadPointerGeometry
import com.gruning.mimir.rewrite.core.ui.TrackpadPointerShape
import com.gruning.mimir.rewrite.core.ui.TrackpadPointerSize
import com.gruning.mimir.rewrite.core.ui.TrackpadPointerStyle
import com.gruning.mimir.rewrite.core.ui.TrackpadSpeed
import com.gruning.mimir.rewrite.core.ui.mimirTrackpadCapability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * D34b -- Mimir's one Accessibility service. It exists for exactly one thing: placing taps and swipes on Main
 * where the keyboard trackpad's pointer is. `res/xml/mimir_trackpad_service.xml` declares `canPerformGestures` and
 * NOT `canRetrieveWindowContent` and listens for no event types, so the service cannot read the screen, a field or
 * what is typed, and [onAccessibilityEvent] ignores everything. No privileged bridge op is involved: the player
 * turns the service on in Android's Accessibility settings (and clears Restricted settings in App info once).
 */
class MimirTrackpadAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        MimirTrackpadRuntime.onServiceConnected(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        MimirTrackpadRuntime.onServiceInterrupted()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        MimirTrackpadRuntime.onServiceGone(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        MimirTrackpadRuntime.onServiceGone(this)
        super.onDestroy()
    }
}

/** The scroll-direction setting (Settings > Controls > Trackpad); natural scroll by default . */
internal object MimirTrackpadSettings {
    private const val PrefsName = "mimir_trackpad"
    private const val KeyNaturalScroll = "natural_scroll"
    private const val KeySpeedStep = "speed_step"
    private const val KeyPointerSize = "pointer_size"
    private const val KeyPointerShape = "pointer_shape"
    private const val KeyPointerColor = "pointer_color"
    private val naturalScrollState = MutableStateFlow(true)
    val naturalScroll: StateFlow<Boolean> = naturalScrollState

    /** The pointer speed dial's step, 1..5, beside natural scroll; the default is the speed the pad had before the dial. */
    private val speedStepState = MutableStateFlow(TrackpadSpeed.Default)
    val speedStep: StateFlow<Int> = speedStepState

    /**
     * The pointer's look : its size, shape and color. Small, slim arrow and white until the player chooses,
     * which is also what an install from before the choice reads, as none of the three keys is stored.
     */
    private val pointerStyleState = MutableStateFlow(TrackpadPointerStyle.Default)
    val pointerStyle: StateFlow<TrackpadPointerStyle> = pointerStyleState

    @Synchronized
    fun load(context: Context) {
        runCatching {
            val prefs = context.applicationContext.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
            naturalScrollState.value = prefs.getBoolean(KeyNaturalScroll, true)
            speedStepState.value = TrackpadSpeed.clamp(prefs.getInt(KeySpeedStep, TrackpadSpeed.Default))
            pointerStyleState.value = TrackpadPointerStyle(
                size = TrackpadPointerSize.fromStored(prefs.getString(KeyPointerSize, null)),
                shape = TrackpadPointerShape.fromStored(prefs.getString(KeyPointerShape, null)),
                color = TrackpadPointerColor.fromStored(prefs.getString(KeyPointerColor, null)),
            )
        }
    }

    @Synchronized
    fun setPointerStyle(context: Context, style: TrackpadPointerStyle) {
        pointerStyleState.value = style
        runCatching {
            context.applicationContext.getSharedPreferences(PrefsName, Context.MODE_PRIVATE).edit()
                .putString(KeyPointerSize, style.size.name)
                .putString(KeyPointerShape, style.shape.name)
                .putString(KeyPointerColor, style.color.name)
                .apply()
        }
    }

    /** Settings' three pointer rows each cycle their own choice: store it and return the one now in force. */
    @Synchronized
    fun cyclePointerSize(context: Context): TrackpadPointerSize {
        val next = pointerStyleState.value.size.next()
        setPointerStyle(context, pointerStyleState.value.copy(size = next))
        return next
    }

    @Synchronized
    fun cyclePointerShape(context: Context): TrackpadPointerShape {
        val next = pointerStyleState.value.shape.next()
        setPointerStyle(context, pointerStyleState.value.copy(shape = next))
        return next
    }

    @Synchronized
    fun cyclePointerColor(context: Context): TrackpadPointerColor {
        val next = pointerStyleState.value.color.next()
        setPointerStyle(context, pointerStyleState.value.copy(color = next))
        return next
    }

    @Synchronized
    fun setSpeedStep(context: Context, step: Int) {
        val clamped = TrackpadSpeed.clamp(step)
        speedStepState.value = clamped
        runCatching {
            context.applicationContext.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
                .edit().putInt(KeySpeedStep, clamped).apply()
        }
    }

    /** Cycles to the next step (5 wraps to 1), stores it and returns it: Settings' row comes here (the pad's gauge sets a level directly). */
    @Synchronized
    fun cycleSpeedStep(context: Context): Int {
        val next = TrackpadSpeed.next(speedStepState.value)
        setSpeedStep(context, next)
        return next
    }

    @Synchronized
    fun setNaturalScroll(context: Context, natural: Boolean) {
        naturalScrollState.value = natural
        runCatching {
            context.applicationContext.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
                .edit().putBoolean(KeyNaturalScroll, natural).apply()
        }
    }
}

/** Process-wide wiring: the connected service, the capability decision and the single trackpad host. */
internal object MimirTrackpadRuntime {
    private const val Tag = "MimirTrackpad"

    @Volatile
    private var service: MimirTrackpadAccessibilityService? = null
    private var host: AccessibilityTrackpadHost? = null

    val serviceConnected: Boolean get() = service != null

    internal fun connectedService(): AccessibilityService? = service

    fun onServiceConnected(connected: MimirTrackpadAccessibilityService) {
        service = connected
        Log.i(Tag, "service connected")
    }

    /**
     * Android interrupted the service's feedback (it does for many reasons, none of them a close): the pad stays up, and
     * only a held press is let go so nothing is left down underneath.
     */
    fun onServiceInterrupted() {
        host?.releaseHeldPress()
    }

    private val fieldWaitingState = MutableStateFlow(false)

    /** True while a text field has asked for the keyboard (the input method is up) -- the pad lights its Keyboard plate. */
    val fieldWaiting: StateFlow<Boolean> = fieldWaitingState

    fun setFieldWaiting(waiting: Boolean) {
        fieldWaitingState.value = waiting
    }

    private var consentWindow: MimirTrackpadConsentWindow? = null

    /**
     * The Bottom menu's Trackpad row: the pad when the service is on, the consent card (still on the Bottom) when it is
     * off, a refusal when this device cannot host a trackpad. Main thread.
     */
    fun openFromBottomMenu(context: Context): BottomTrackpadOpenResult {
        val app = context.applicationContext
        return when (trackpadMenuAction(capability(app), serviceConnected)) {
            TrackpadMenuAction.UNSUPPORTED -> BottomTrackpadOpenResult(false, "trackpad is not available on this device")
            TrackpadMenuAction.OPEN_PAD -> {
                val opened = accessibilityHost(app).openPadWindow()
                if (opened) BottomTrackpadOpenResult(true) else BottomTrackpadOpenResult(false, "trackpad window did not open")
            }
            TrackpadMenuAction.SHOW_CONSENT -> {
                val card = consentWindow
                    ?: MimirTrackpadConsentWindow(app, openSettings = { openAccessibilitySettings(app) })
                        .also { consentWindow = it }
                if (card.show()) BottomTrackpadOpenResult(true) else BottomTrackpadOpenResult(false, "consent card did not show")
            }
        }
    }

    fun onServiceGone(gone: MimirTrackpadAccessibilityService) {
        if (service === gone) {
            service = null
            host?.end()
            Log.i(Tag, "service gone")
        }
    }

    /**
     * The keyboard offers the trackpad only where it can work: API 32+ (the gesture API's display id), a presentation
     * display to be the other screen, and an SoC that is not turned away. The SoC never gates by model (policy
     * 2026-08-21/22), so the posture passes unless the platform reports no SoC at all.
     */
    fun capability(context: Context): Boolean = mimirTrackpadCapability(
        sdkInt = Build.VERSION.SDK_INT,
        hasPresentationDisplay = hasPresentationDisplay(context),
        socPosturePasses = true,
    )

    private fun hasPresentationDisplay(context: Context): Boolean = runCatching {
        context.getSystemService(DisplayManager::class.java)
            .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .any { it.displayId != Display.DEFAULT_DISPLAY }
    }.getOrDefault(false)

    /** The host the keyboard hands to its Trackpad key, or null when this device cannot host a trackpad. */
    fun hostFor(context: Context): MimirTrackpadHost? {
        if (!capability(context)) return null
        MimirTrackpadSettings.load(context)
        return accessibilityHost(context.applicationContext)
    }

    private fun accessibilityHost(appContext: Context): AccessibilityTrackpadHost {
        // The pad can be opened from the Bottom's menu before any keyboard ever asked for a host: the stored speed and scroll
        // direction are read here, so every route reads them, not just the keyboard's.
        MimirTrackpadSettings.load(appContext)
        return host ?: AccessibilityTrackpadHost(appContext).also { host = it }
    }

    /** Whether the service is switched on in Android's settings (reads only our own entry in the enabled list). */
    fun accessibilityServiceEnabled(context: Context): Boolean = runCatching {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        val component = ComponentName(context, MimirTrackpadAccessibilityService::class.java)
        enabled.split(':').any {
            it.equals(component.flattenToString(), ignoreCase = true) ||
                it.equals(component.flattenToShortString(), ignoreCase = true)
        }
    }.getOrDefault(false)

    /** Opens Android's Accessibility settings on Main (the player turns the service on there). */
    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle()
        runCatching { context.startActivity(intent, options) }
            .onFailure { Log.w(Tag, "accessibility settings did not open cause=${it.javaClass.simpleName}") }
    }
}

/** The real strokes: `GestureDescription.StrokeDescription`s dispatched on display 0 through the connected service. */
private class GestureStrokes(private val main: Handler) : TrackpadStrokes<GestureDescription.StrokeDescription> {
    private val tag = "MimirTrackpad"

    override fun stroke(
        previous: GestureDescription.StrokeDescription?,
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMs: Long,
        willContinue: Boolean,
    ): GestureDescription.StrokeDescription {
        val path = Path().apply {
            moveTo(fromX, fromY)
            if (toX != fromX || toY != fromY) lineTo(toX, toY)
        }
        return previous?.continueStroke(path, 0L, durationMs, willContinue)
            ?: GestureDescription.StrokeDescription(path, 0L, durationMs, willContinue)
    }

    override fun dispatch(
        stroke: GestureDescription.StrokeDescription,
        what: String,
        done: (completed: Boolean) -> Unit,
    ) {
        val service = MimirTrackpadRuntime.connectedService()
        if (Build.VERSION.SDK_INT < 32 || service == null) {
            main.post { done(false) }
            return
        }
        val gesture = GestureDescription.Builder()
            .setDisplayId(Display.DEFAULT_DISPLAY)
            .addStroke(stroke)
            .build()
        val dispatchedAt = SystemClock.uptimeMillis()
        val accepted = service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.i(tag, "$what completed in ${SystemClock.uptimeMillis() - dispatchedAt} ms")
                    done(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.i(tag, "$what cancelled after ${SystemClock.uptimeMillis() - dispatchedAt} ms")
                    done(false)
                }
            },
            main,
        )
        if (!accepted) {
            Log.w(tag, "$what was not accepted")
            main.post { done(false) }
        }
    }
}

/**
 * The trackpad host: draws the pointer on Main (display 0) and hands every gesture to [MimirTrackpadInjector], which
 * owns the pointer's place, the session guard and the held press. All calls arrive on the main thread.
 *
 * Touch only: a stroke is a touch, so there is no hover and no true right-click (a long press stands in). Secure
 * windows, and some games and emulators, may ignore injected strokes. Nothing here reads the screen.
 */
internal class AccessibilityTrackpadHost(private val appContext: Context) : MimirTrackpadHost {
    private val tag = "MimirTrackpad"
    private val main = Handler(Looper.getMainLooper())
    private val injector = MimirTrackpadInjector(
        strokes = GestureStrokes(main),
        displaySize = ::displaySize,
        screenOn = ::screenOn,
        keyguardLocked = { trackpadKeyguardLocked(appContext) },
        gestureMargins = { gestureZones },
        naturalScroll = { MimirTrackpadSettings.naturalScroll.value },
        performBack = { mimirTrackpadPerformBack(MimirTrackpadRuntime.connectedService()) },
    )
    private var pointerView: MimirPointerView? = null
    private var pointerParams: WindowManager.LayoutParams? = null
    private var pointerWindowManager: WindowManager? = null

    // The look the pointer window wears now, and the watch that re-applies it when the player's choice or the theme changes.
    private var pointerShape = TrackpadPointerShape.SLIM_ARROW
    private var pointerWindowPx = 0
    private var pointerStyleShown: TrackpadPointerStyle? = null
    private var pointerStyleWatch: Job? = null
    private val pointerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var screenOffReceiver: BroadcastReceiver? = null

    // Display 0's system-gesture zones: the named floors, or the platform's own system-gesture insets where larger. Read at each
    // activation (a rotation between two is covered by the floors).
    private var gestureZones: GestureMargins = TrackpadGestureZones.margins(appContext.resources.displayMetrics.density)
    private val padWindow = MimirTrackpadPadWindow.forBottom(appContext, begin = ::begin)

    private val fadeOut = Runnable {
        pointerView?.animate()?.cancel()
        pointerView?.animate()?.alpha(0f)?.setDuration(if (reducedMotion()) 0L else FadeMs)
            ?.setInterpolator(PathInterpolator(0.25f, 0.1f, 0.25f, 1f))?.start()
    }

    override val serviceConnected: Boolean get() = MimirTrackpadRuntime.serviceConnected

    /**
     * A new activation: mints the session token and returns the handle that carries it. The handle is the ONLY way to
     * inject; the host itself never reads "the current token" to inject, so a callback left over from an earlier
     * activation cannot act in a later one.
     */
    override fun begin(): MimirTrackpadHost {
        gestureZones = readGestureZones()
        val token = injector.activate()
        val (w, h) = displaySize()
        Log.i(tag, "activated display0=${w}x$h source=$lastSizeSource")
        registerScreenOff()
        showPointer()
        return Activation(token)
    }

    override fun openAccessibilitySettings() {
        MimirTrackpadRuntime.openAccessibilitySettings(appContext)
    }

    override fun openPad() {
        openPadWindow()
    }

    /** Opens the full-screen pad on the Bottom (an a11y-owned overlay). False when it could not be added. */
    fun openPadWindow(): Boolean = padWindow.open()

    /** A held press is let go; the activation and the pad stay. Used when Android interrupts the service. */
    fun releaseHeldPress() = injector.releaseHold()

    // The host itself has no activation, so a gesture sent to it injects nothing: gestures go through the handle `begin()`
    // returned (which carries the session token).
    override fun pointerMove(dx: Float, dy: Float) = Unit
    override fun click() = Unit
    override fun doubleClick() = Unit
    override fun scrollBy(dx: Float, dy: Float) = Unit
    override fun scrollEnd() = Unit
    override fun scrollCancel() = Unit
    override fun holdBegin() = Unit
    override fun holdMove(dx: Float, dy: Float) = Unit
    override fun holdEnd() = Unit

    /**
     * The screen went off, the service was disabled: ends whichever activation is live and takes the pad window with it.
     * (The keyboard no longer ends it: the pad stays until its own Close, the screen going off, or the service going.)
     */
    override fun end() {
        // The injector releases any held stroke FIRST (a finger-up is owed to the app underneath); then the pointer goes.
        injector.endCurrent()
        teardown()
        padWindow.killNow()
    }

    private fun teardown() {
        unregisterScreenOff()
        hidePointer()
    }

    /** One activation's handle: every call carries the token captured when it began, and its press ids. */
    private inner class Activation(private val token: Long) : MimirTrackpadHost {
        private var holdId = 0L

        override val serviceConnected: Boolean get() = MimirTrackpadRuntime.serviceConnected

        override fun begin(): MimirTrackpadHost = this

        override fun pointerMove(dx: Float, dy: Float) {
            if (injector.move(token, dx, dy)) showPointer()
        }

        override fun click() {
            if (injector.click(token)) showPointer()
        }

        override fun doubleClick() {
            if (injector.doubleClick(token)) showPointer()
        }

        override fun scrollBy(dx: Float, dy: Float) = injector.scrollBy(token, dx, dy)

        override fun scrollEnd() = injector.scrollEnd(token)

        override fun scrollCancel() = injector.scrollCancel(token)

        override fun holdBegin() {
            holdId = injector.holdBegin(token)
        }

        override fun holdMove(dx: Float, dy: Float) {
            injector.holdMove(token, holdId, dx, dy)
            if (injector.isLive(token)) showPointer()
        }

        override fun holdEnd() {
            injector.holdEnd(token, holdId)
            holdId = 0L
        }

        override fun openAccessibilitySettings() = this@AccessibilityTrackpadHost.openAccessibilitySettings()

        override val speedStep: Int get() = MimirTrackpadSettings.speedStep.value

        /** The pad's Slow-to-Fast gauge sets a level directly: clamped, stored where Settings reads it, and the step now in force returned. */
        override fun setSpeed(step: Int): Int {
            MimirTrackpadSettings.setSpeedStep(appContext, step)
            return MimirTrackpadSettings.speedStep.value
        }

        /** One Back on Main, gated like every other action by THIS activation's token and the lock. */
        override fun back() {
            injector.back(token)
        }

        override val sessionLive: Boolean get() = injector.isLive(token)

        /** Ends THIS activation only; a stale handle ending changes nothing. */
        override fun end() {
            if (injector.end(token)) teardown()
        }
    }

    // ---- the pointer --------------------------------------------------------------------------------------------

    /** The floors in [TrackpadGestureZones], widened by display 0's own system-gesture insets when the platform will say. */
    private fun readGestureZones(): GestureMargins {
        val density = appContext.resources.displayMetrics.density
        val insets = runCatching {
            val display = appContext.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            val context = MimirTrackpadRuntime.connectedService()
                ?.createDisplayContext(display)
                ?.createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
            context?.getSystemService(WindowManager::class.java)
                ?.currentWindowMetrics?.windowInsets?.getInsets(android.view.WindowInsets.Type.systemGestures())
        }.getOrNull()
        return TrackpadGestureZones.margins(
            density = density,
            apiLeft = (insets?.left ?: 0).toFloat(),
            apiTop = 0f,
            apiRight = (insets?.right ?: 0).toFloat(),
            apiBottom = (insets?.bottom ?: 0).toFloat(),
        )
    }

    private var lastGoodSize: Pair<Int, Int>? = null
    private var lastSizeSource = "none"
    private var boundsService: AccessibilityService? = null
    private var boundsWindowManager: WindowManager? = null

    /**
     * Display 0's live size in its current rotation; the pointer is re-clamped to it on every use. Read from the maximum window
     * bounds of a window context bound to display 0 (the frame the injected strokes live in), with the display's real size as
     * the fallback and the last good size after that: never another display's.
     */
    private fun displaySize(): Pair<Int, Int> {
        val reading = TrackpadDisplayReading(windowBounds = readWindowBounds(), realSize = readRealSize())
        val size = mimirTrackpadDisplaySize(reading, lastGoodSize)
        lastSizeSource = when {
            reading.windowBounds == size -> "windowBounds"
            reading.realSize == size -> "realSize"
            else -> "lastGood"
        }
        lastGoodSize = size
        return size
    }

    private fun readWindowBounds(): Pair<Int, Int>? = runCatching {
        val service = MimirTrackpadRuntime.connectedService() ?: return@runCatching null
        if (boundsService !== service || boundsWindowManager == null) {
            val display = appContext.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
                ?: return@runCatching null
            boundsWindowManager = service.createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
                .getSystemService(WindowManager::class.java)
            boundsService = service
        }
        val bounds = boundsWindowManager?.maximumWindowMetrics?.bounds ?: return@runCatching null
        bounds.width() to bounds.height()
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun readRealSize(): Pair<Int, Int>? = runCatching {
        val point = Point(0, 0)
        appContext.getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)
            ?.getRealSize(point)
        point.x to point.y
    }.getOrNull()

    private fun screenOn(): Boolean = runCatching {
        appContext.getSystemService(PowerManager::class.java).isInteractive
    }.getOrDefault(false)

    private fun reducedMotion(): Boolean = runCatching {
        Settings.Global.getFloat(appContext.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)

    private fun showPointer() {
        if (!injector.active) return
        val at = injector.pointer ?: return
        if (!ensurePointerWindow()) return
        val view = pointerView ?: return
        val params = pointerParams ?: return
        // A choice made in the instant between the window mounting and the watch subscribing would otherwise wait for the next theme tick.
        if (MimirTrackpadSettings.pointerStyle.value != pointerStyleShown) applyPointerStyle(view, params)
        placePointer(view, params, at)
        revealPointer(view)
    }

    /** The window's top-left for the pointer at [at], by the shape on screen: the arrow just off its click, the dot centred on it. */
    private fun placePointer(view: MimirPointerView, params: WindowManager.LayoutParams, at: MimirPointerPosition) {
        val (originX, originY) = mimirTrackpadPointerOrigin(pointerShape, at.x, at.y, pointerWindowPx, params.type)
        params.x = originX
        params.y = originY
        runCatching { pointerWindowManager?.updateViewLayout(view, params) }
    }

    private fun revealPointer(view: MimirPointerView) {
        view.animate().cancel()
        view.alpha = 1f
        main.removeCallbacks(fadeOut)
        main.postDelayed(fadeOut, IdleFadeMs)
    }

    /** Lays [view] and its window [params] out for the player's current choice and theme. */
    private fun applyPointerStyle(view: MimirPointerView, params: WindowManager.LayoutParams) {
        val style = MimirTrackpadSettings.pointerStyle.value
        val density = appContext.resources.displayMetrics.density
        val windowPx = TrackpadPointerGeometry.windowPx(style.shape, style.size, density)
        pointerShape = style.shape
        pointerWindowPx = windowPx
        pointerStyleShown = style
        params.width = windowPx
        params.height = windowPx
        view.applyStyle(style.shape, windowPx, mimirTrackpadPointerFill(style.color))
    }

    /**
     * A choice in Settings, or a theme or accent change, reaches a pointer that is on screen at once: it is redrawn, resized
     * and re-placed, and shown again if the choice itself changed so the player sees it. One pointer window mounted, one watch.
     * The first emission is the state the window was just built from, so it changes nothing and reveals nothing; no emission is
     * skipped, so a choice landing just after the window mounts is applied.
     */
    private fun watchPointerStyle() {
        pointerStyleWatch?.cancel()
        pointerStyleWatch = pointerScope.launch {
            combine(MimirTrackpadSettings.pointerStyle, ThemeStore.themeId, AppearanceOverrideStore.revision) { style, _, _ -> style }
                .collect { style ->
                    val view = pointerView ?: return@collect
                    val params = pointerParams ?: return@collect
                    val changed = style != pointerStyleShown
                    applyPointerStyle(view, params)
                    injector.pointer?.let { placePointer(view, params, it) }
                    if (changed) revealPointer(view)
                }
        }
    }

    /**
     * TYPE_ACCESSIBILITY_OVERLAY on display 0 first (it needs no overlay grant and sits above apps); if that window
     * cannot be added, the PerformanceOverlayPresenter pattern (a TYPE_APPLICATION_OVERLAY window context on display 0).
     */
    private fun ensurePointerWindow(): Boolean {
        if (pointerView != null) return true
        val display = appContext.getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY) ?: return false
        val style = MimirTrackpadSettings.pointerStyle.value
        val sizePx = TrackpadPointerGeometry.windowPx(style.shape, style.size, appContext.resources.displayMetrics.density)
        val attempts = buildList<() -> Pair<Context, Int>?> {
            add {
                MimirTrackpadRuntime.connectedService()
                    ?.createDisplayContext(display)
                    ?.createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
                    ?.let { it to WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY }
            }
            add {
                if (Settings.canDrawOverlays(appContext)) {
                    appContext.createDisplayContext(display)
                        .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                        .let { it to WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY }
                } else {
                    null
                }
            }
        }
        for (attempt in attempts) {
            val mounted = runCatching {
                val (context, type) = attempt() ?: return@runCatching null
                val wm = context.getSystemService(WindowManager::class.java)
                val view = MimirPointerView(context)
                val params = mimirTrackpadPointerParams(type, sizePx)
                applyPointerStyle(view, params)
                wm.addView(view, params)
                Triple(wm, view, params)
            }.onFailure { Log.w(tag, "pointer window attempt failed cause=${it.javaClass.simpleName}") }.getOrNull()
            if (mounted != null) {
                pointerWindowManager = mounted.first
                pointerView = mounted.second
                pointerParams = mounted.third
                watchPointerStyle()
                return true
            }
        }
        return false
    }

    private fun hidePointer() {
        main.removeCallbacks(fadeOut)
        pointerStyleWatch?.cancel()
        pointerStyleWatch = null
        val view = pointerView ?: return
        view.animate().cancel()
        runCatching { pointerWindowManager?.removeViewImmediate(view) }
        pointerView = null
        pointerParams = null
        pointerWindowManager = null
    }

    private fun registerScreenOff() {
        if (screenOffReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                end()
            }
        }
        screenOffReceiver = receiver
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(
                appContext,
                receiver,
                IntentFilter(Intent.ACTION_SCREEN_OFF),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
    }

    private fun unregisterScreenOff() {
        val receiver = screenOffReceiver ?: return
        screenOffReceiver = null
        runCatching { appContext.unregisterReceiver(receiver) }
    }

    private companion object {
        const val IdleFadeMs = 3_000L

        /** The pointer's idle fade-out: the overlay tier's clock (no off-token clocks), on the house curve. */
        const val FadeMs = MimirMotion.OverlayMs.toLong()
    }
}
