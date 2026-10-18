package com.gruning.mimir.rewrite

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.gruning.mimir.rewrite.core.tweaker.LaunchStatusLatestEventPolicy
import com.gruning.mimir.rewrite.core.tweaker.LaunchStatusPresentation
import com.gruning.mimir.rewrite.core.tweaker.TweakerLaunchOverlayMessage
import com.gruning.mimir.rewrite.core.ui.MimirMessage
import com.gruning.mimir.rewrite.core.ui.MimirMessageClass
import com.gruning.mimir.rewrite.core.ui.MimirRunicMessageCard
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.mimirReducedMotion

internal const val CrossAppLaunchStatusDisplayMs = 2_200L
internal const val CrossAppLaunchStatusSafeInsetDp = 14

enum class CrossAppLaunchStatusShowOutcome {
    OverlayShown,
    WarningFallbackShown,
    Silenced,
}

/**
 * Mimir-owned cross-app launch-status presenter. TYPE_APPLICATION_OVERLAY on the primary display when
 * overlay permission is already granted; pass-through, non-focusable, non-touchable, latest-event-wins.
 */
class CrossAppLaunchStatusPresenter private constructor(
    private val appContext: Context,
) {
    companion object {
        @Volatile
        private var shared: CrossAppLaunchStatusPresenter? = null

        fun instance(context: Context): CrossAppLaunchStatusPresenter =
            shared ?: synchronized(this) {
                shared ?: CrossAppLaunchStatusPresenter(context.applicationContext).also { shared = it }
            }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val policy = LaunchStatusLatestEventPolicy(displayDurationMs = CrossAppLaunchStatusDisplayMs)
    private var eventSequence = 0L
    private var dismissRunnable: Runnable? = null
    private var host: OverlayHost? = null
    private var composeView: ComposeView? = null
    private var windowManager: WindowManager? = null
    private var activePresentation by mutableStateOf<LaunchStatusPresentation?>(null)

    fun showSync(message: TweakerLaunchOverlayMessage?): CrossAppLaunchStatusShowOutcome {
        require(Looper.myLooper() == Looper.getMainLooper()) {
            "CrossAppLaunchStatusPresenter.showSync must run on the main thread"
        }
        if (message == null) return CrossAppLaunchStatusShowOutcome.Silenced
        if (!Settings.canDrawOverlays(appContext)) {
            return if (message is TweakerLaunchOverlayMessage.Warning) {
                Toast.makeText(appContext, message.text, Toast.LENGTH_LONG).show()
                CrossAppLaunchStatusShowOutcome.WarningFallbackShown
            } else {
                CrossAppLaunchStatusShowOutcome.Silenced
            }
        }
        val eventId = ++eventSequence
        val presentation = policy.present(eventId, message, SystemClock.uptimeMillis())
        ensureMounted()
        activePresentation = presentation
        scheduleDismiss(presentation)
        return CrossAppLaunchStatusShowOutcome.OverlayShown
    }

    private fun ensureMounted() {
        if (composeView != null) return
        val overlayHost = OverlayHost().also { it.create() }
        host = overlayHost
        val wm = appContext.getSystemService(WindowManager::class.java)
        windowManager = wm
        val view = ComposeView(appContext).apply {
            setViewTreeLifecycleOwner(overlayHost)
            setViewTreeSavedStateRegistryOwner(overlayHost)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                // RC-38 (audit 2026-10-14): the card fades in and out on OverlayMs; the window goes when the leave has settled.
                CrossAppLaunchStatusCardHost(
                    presentation = activePresentation,
                    onLeft = { mainHandler.post(::detachIfIdle) },
                )
            }
        }
        composeView = view
        wm.addView(view, overlayLayoutParams())
    }

    private fun overlayLayoutParams(): WindowManager.LayoutParams {
        val density = appContext.resources.displayMetrics.density
        val insetPx = (CrossAppLaunchStatusSafeInsetDp * density).toInt()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = insetPx
            y = insetPx
            title = "Mimir launch status"
        }
    }

    private fun scheduleDismiss(presentation: LaunchStatusPresentation) {
        dismissRunnable?.let(mainHandler::removeCallbacks)
        val runnable = Runnable {
            if (policy.isCurrent(presentation) && SystemClock.uptimeMillis() >= presentation.expiresAtMs) {
                // The card fades out (CrossAppLaunchStatusCardHost); the window follows when that has settled. The guard is for a
                // display that draws no frame, which would never report it.
                activePresentation = null
                mainHandler.postDelayed(::detachIfIdle, NativePanelExitBackstopMs)
            }
        }
        dismissRunnable = runnable
        val delayMs = (presentation.expiresAtMs - SystemClock.uptimeMillis()).coerceAtLeast(0L)
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun detachIfIdle() {
        if (activePresentation != null) return
        val view = composeView ?: return
        runCatching { windowManager?.removeView(view) }
        view.disposeComposition()
        composeView = null
        windowManager = null
        host?.destroy()
        host = null
    }

    private class OverlayHost : LifecycleOwner, SavedStateRegistryOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val savedStateController = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

        fun create() {
            savedStateController.performRestore(null)
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }

        fun destroy() {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        }
    }
}

/**
 * What the cross-app card window draws: the card for [presentation], fading in when one arrives and out when it ends, on the house
 * clock (`storageNoticeFadeSpec`: OverlayMs on `MimirMotion.Easing`, none under reduced motion). The outgoing card is retained until
 * the leave has settled, so it has something to fade; a newer event replaces the card in place (the card's own crossfade) and
 * never waits. [onLeft] is called once a leave has settled, to take the empty window down.
 */
@Composable
internal fun CrossAppLaunchStatusCardHost(
    presentation: LaunchStatusPresentation?,
    onLeft: () -> Unit,
) {
    val themeId by ThemeStore.themeId.collectAsState()
    val reducedMotion = mimirReducedMotion()
    var retained by remember { mutableStateOf<LaunchStatusPresentation?>(null) }
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = presentation != null
    // A leave is settled only after a card has been shown: a host that has never had one has nothing to report.
    val hasShown = remember { booleanArrayOf(false) }
    if (presentation != null) hasShown[0] = true
    val leaveSettled = hasShown[0] && visibility.isIdle && !visibility.currentState && !visibility.targetState
    val shown = presentation ?: if (leaveSettled) null else retained
    SideEffect { retained = shown }
    LaunchedEffect(leaveSettled) { if (leaveSettled) onLeft() }
    MimirTheme(themeId = themeId) {
        AnimatedVisibility(
            visibleState = visibility,
            enter = fadeIn(storageNoticeFadeSpec(reducedMotion)),
            exit = fadeOut(storageNoticeFadeSpec(reducedMotion)),
        ) {
            val card = shown ?: return@AnimatedVisibility
            val mimirMessage = card.message.toMimirMessage(card.eventId)
            // Compact typography at the ORIGINAL width. This card appears over someone
            // else's game -- it is a notice, not a panel -- so `compact` takes the body from
            // 16sp to 9sp and the glyph from 22sp to 12sp. The 360.dp stays: the width is
            // what keeps the Confirmation readable, whose supporting line is
            // "READY FOR <game title>" at maxLines = 1 with overflow Clip, so an arbitrary
            // title is silently cut if the card is narrowed. This pairing replaces the narrower version reviewed on device, 2026-08-07.
            MimirRunicMessageCard(
                message = mimirMessage,
                compact = true,
                modifier = Modifier.widthIn(max = 360.dp),
                exposeSemantics = true,
            )
        }
    }
}

private fun TweakerLaunchOverlayMessage.toMimirMessage(eventId: Long): MimirMessage = when (this) {
    is TweakerLaunchOverlayMessage.Confirmation ->
        MimirMessage(
            eventId = eventId,
            text = headline,
            messageClass = MimirMessageClass.Confirmation,
            supportingText = supportingText,
        )
    is TweakerLaunchOverlayMessage.Warning ->
        MimirMessage(
            eventId = eventId,
            text = text,
            messageClass = MimirMessageClass.Warning,
        )
}
