package com.gruning.mimir.rewrite

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirTheme

private const val GameLaunchCoverPresenterLogTag = "MimirOpening"

/**
 * The launch cover's window: a full-bleed `TYPE_APPLICATION_OVERLAY` that draws one frozen [GameLaunchCoverSnapshot] and
 * nothing else, modelled on [CrossAppLaunchStatusPresenter] (the always-RESUMED [OverlayHost], the contained add) and
 * [PerformanceOverlayPresenter] (a window context, no inset fitting, the cutout drawn into).
 *
 * **Never focusable, and TOUCHABLE on purpose** (walk 32k device log): `FLAG_NOT_FOCUSABLE` so the emulator keeps every key
 * and the controller. The window does NOT carry `FLAG_NOT_TOUCHABLE`, because on Android 12 and later the system clamps an
 * untrusted overlay that has it to the obscuring-opacity limit: the log line was "has a system alert window (type = 2038)
 * with FLAG_NOT_TOUCHABLE and LayoutParams.alpha = 1.00 > 0.80, setting alpha to 0.80 to let touches pass through", so the
 * cover drawn at alpha 1 was really 80% opaque and the game showed through it. A window that takes touches is left at the
 * alpha it asks for. **The consequence, accepted:** a touch during the cover lands on the cover and does nothing (no handler
 * is attached, so it is consumed and never reaches the emulator). The block lasts at most as long as the cover does, and the
 * cover's end conditions and its ceiling ([GameLaunchCoverCeilingMs], [GameLaunchCoverUnyieldedCeilingMs]) are unchanged.
 * The exit is the CONTENT changing (a Compose `graphicsLayer` alpha and translation), never the window's alpha, so the
 * window stays at alpha 1 to the last frame. Device proof, not proven here: the clamp log line no longer appears for this
 * window, and the cover reads fully opaque.
 *
 * The exit blends while gliding: the whole picture, hero, zoom, logo
 * and title as the one layer they already are, fades from 1 to 0 while it drifts [GameLaunchCoverGlideDriftFraction] of the
 * window toward [GameLaunchCoverGlideExit], on ONE eased clock ([MimirMotion.Easing] over [GameLaunchCoverGlideMs], the
 * intro's own entrance clock), so the game shows through as it goes. It ends at alpha 0, so nothing of it is left to show
 * when the window is removed. While it fades the layer is composited offscreen at its own bounds (the window, which is the
 * screen), so nothing the picture draws beyond that is seen, and nothing of the zoom's overshoot ever was.
 *
 * It draws the snapshot and nothing else: no [GameOpeningIntroState] read, because the yield path clears that state at the
 * moment this window takes over. The frame is the intro's own ([GameOpeningIntroFrame]) with constant values, so it cannot
 * differ from the one under it, and it does not animate (no zoom) so it costs one layer.
 */
internal class GameLaunchCoverPresenter(
    private val appContext: Context,
) : GameLaunchCoverWindow {
    private var host: OverlayHost? = null
    private var composeView: ComposeView? = null
    private var windowManager: WindowManager? = null
    private var gliding by mutableStateOf(false)
    private var glideMs by mutableStateOf(0L)

    override fun mount(snapshot: GameLaunchCoverSnapshot, onRevealed: () -> Unit): Boolean {
        removeImmediate()
        gliding = false
        val display = appContext.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
            ?: return false
        // A WindowContext is the authoritative visual context for a non-activity overlay (PerformanceOverlayPresenter).
        val windowContext = runCatching {
            appContext.createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        }.getOrNull() ?: return false
        val manager = windowContext.getSystemService(WindowManager::class.java) ?: return false
        val owner = OverlayHost().also { it.create() }
        val view = ComposeView(windowContext).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                val themeId by ThemeStore.themeId.collectAsState()
                MimirTheme(themeId = themeId) {
                    GameLaunchCoverContent(
                        snapshot = snapshot,
                        gliding = gliding,
                        glideMs = glideMs.toInt(),
                        onRevealed = onRevealed,
                    )
                }
            }
        }
        val added = runCatching { manager.addView(view, overlayLayoutParams()) }
            .onFailure { failure ->
                Log.w(
                    GameLaunchCoverPresenterLogTag,
                    "cover window not added reason=${failure.message ?: failure.javaClass.simpleName}",
                )
            }
            .isSuccess
        if (!added) {
            runCatching { view.disposeComposition() }
            owner.destroy()
            return false
        }
        host = owner
        composeView = view
        windowManager = manager
        return true
    }

    override fun beginGlide(glideMs: Long) {
        this.glideMs = glideMs
        gliding = true
    }

    override fun removeImmediate() {
        val mounted = composeView ?: return
        composeView = null
        runCatching { windowManager?.removeViewImmediate(mounted) }
        runCatching { mounted.disposeComposition() }
        host?.destroy()
        host = null
        windowManager = null
        gliding = false
    }

    private fun overlayLayoutParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        // No FLAG_NOT_TOUCHABLE: see the class doc. It is what made the system clamp this window's alpha to 0.8.
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Full-bleed, as the performance overlay is: the presenter owns placement, so the system fits no bars, and
            // the cutout is drawn into so the picture meets the same edges the intro's does.
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            // No window animation: removeViewImmediate must leave nothing behind for the system to play out.
            windowAnimations = 0
            title = "Mimir launch cover"
        }
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
 * The cover's content. Transparent until it has settled for [GameLaunchCoverSettleFrames] frames (its logo painter lands a
 * frame or two after the first composition, and a window that opened without it would show the intro under it losing its
 * logo for a moment), then the intro's frame at the snapshot's values, snapped on (the frame under it is the same picture,
 * so nothing is seen to change), then [onRevealed]. [gliding] blends the whole picture out, as one piece: over [glideMs],
 * on the one eased clock ([MimirMotion.Easing]), its alpha goes 1 to 0 (exactly 0 at the end) while it drifts
 * [GameLaunchCoverGlideDriftFraction] of the window's width (height, for an up or down exit) toward [edge].
 */
@Composable
internal fun GameLaunchCoverContent(
    snapshot: GameLaunchCoverSnapshot,
    gliding: Boolean,
    glideMs: Int,
    onRevealed: () -> Unit,
    edge: GameLaunchCoverGlideEdge = GameLaunchCoverGlideExit,
) {
    var revealed by remember { mutableStateOf(false) }
    val glide = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        repeat(GameLaunchCoverSettleFrames) { withFrameNanos { } }
        revealed = true
        withFrameNanos { }
        onRevealed()
    }
    LaunchedEffect(gliding) {
        if (gliding) glide.animateTo(1f, tween(glideMs, easing = MimirMotion.Easing))
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("game-launch-cover")
            .graphicsLayer {
                alpha = if (revealed) gameLaunchCoverGlideAlpha(glide.value) else 0f
                val (dx, dy) = gameLaunchCoverGlideOffsetPx(edge, glide.value, size.width, size.height)
                translationX = dx
                translationY = dy
            },
    ) {
        val frame = snapshot.frame
        GameOpeningIntroFrame(
            title = snapshot.title,
            logoUri = snapshot.logoUri,
            heroAlpha = { frame.heroAlpha },
            logoAlpha = { frame.logoAlpha },
            logoScale = { frame.logoScale },
            heroZoom = { frame.zoom },
        ) { heroModifier ->
            Image(
                bitmap = snapshot.hero,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = heroModifier,
            )
        }
    }
}
