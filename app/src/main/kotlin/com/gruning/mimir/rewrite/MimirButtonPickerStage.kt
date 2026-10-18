package com.gruning.mimir.rewrite

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import android.view.View
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayTheme
import com.gruning.mimir.rewrite.core.companionnative.createMimirButtonPreviewView
import com.gruning.mimir.rewrite.core.companionnative.isMimirButtonMarkResident
import com.gruning.mimir.rewrite.core.companionnative.warmMimirButtonMark
import com.gruning.mimir.rewrite.core.companionnative.setMimirButtonPreviewPlacement
import com.gruning.mimir.rewrite.core.companionnative.setMimirButtonPreviewRinged
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirButtonMark
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.mimirReducedMotion
import com.gruning.mimir.rewrite.core.ui.rememberMimirColdArtReveal
import com.gruning.mimir.rewrite.feature.settings.MimirButtonPickerStageLayout
import com.gruning.mimir.rewrite.feature.settings.MimirButtonStageCoinSize
import com.gruning.mimir.rewrite.feature.settings.markPlacement
import kotlin.math.roundToInt

/**
 * The Mimir-button picker's stage with the REAL button on it (concept A; brief §D "Preview").
 *
 * `MimirButtonPickerStageLayout` (feature-settings) owns what the stage shows -- the button at 56 dp over the current
 * background, a 2x copy, and a 56 dp copy on a light panel drawn from `textPrimary` -- and this supplies the coin: the
 * native overlay's own view, built by `createMimirButtonPreviewView` from the same `companionOverlayTheme` the live button
 * is built from, so what the player judges here is what the overlay will wear.
 *
 * **The coin is drawn from that view into a bitmap, not hosted as a view (audit 2026-10-14, RC-13).** The app-shell host that presents this
 * picker records it into a graphics layer and, when the picker leaves (B, or A on + Upload handing over to the file browser), keeps that
 * recording on screen while it dissolves. A native view in that recording is drawn by reference to its own display list, and the view's
 * display list is discarded the moment it leaves composition: the dissolving picker lost its three coins at once, before the fade, and the
 * "actual size" mark, the 2x copy and the copy on the bright panel went blank under an intact tile row (device review, emulator, scale 5).
 * A bitmap in the recording is pixels the recording owns, so the coins stand through the whole exit. It is the same view drawing the same
 * way (`CompanionMenuCoinView.onDraw`, on a software canvas at the size it is shown); nothing about the coin is redrawn in Compose.
 *
 * **Nothing here reaches the overlay.** The view is built from the theme alone and never calls `setOverlayTheme`, because the
 * controller repaints the live button by re-adding its window and a push per previewed tile would do that on every focus
 * move; only A's commit pushes, through `AppearanceOverrideStore.setButtonMark` and the theme adapter's revision tick. The
 * view is never attached, so it takes no focus and no touch, and the picker's tiles hold every focus key.
 *
 * A change of the previewed mark cross-fades over [MimirMotion.SceneMs] on the house curve, as the Background picker's stage
 * does; under reduced motion it cuts, and at rest nothing moves. [Crossfade] keys its children by the target, so each mark is
 * its own coin. A custom mark's mask is decoded and trimmed on the Default dispatcher first ([warmMimirButtonMark], RC-29) and the coin is
 * struck the frame after; one already resident, and the rune, are drawn in the composition's first frame.
 */
@Composable
internal fun BoxScope.MimirButtonPickerLiveStage(preview: MimirButtonMark?) {
    MimirButtonPickerStageLayout(preview) { mark, scale -> LiveButtonCoin(mark, scale) }
}

@Composable
private fun LiveButtonCoin(mark: MimirButtonMark?, scale: Float) {
    val palette = LocalMimirPalette.current
    val reduced = mimirReducedMotion()
    Crossfade(
        targetState = mark,
        animationSpec = if (reduced) snap<Float>() else tween<Float>(MimirMotion.SceneMs, easing = MimirMotion.Easing),
        label = "mimirButtonStage",
    ) { shown ->
        // The overlay's own mapping of the effective palette, with the previewed mark in place of the committed one.
        val theme = remember(palette, shown) { companionOverlayTheme(palette.id, shown) }
        MimirButtonCoinSnapshot(theme = theme, markPath = shown?.path, scale = scale)
    }
}

/** The tag of each stage coin, for the tests that read what the stage draws. */
internal const val MimirButtonStageCoinTag = "mimir-button-stage-coin"

/**
 * One stage coin: the preview view of [theme] at [scale] drawn into a bitmap of the pixels it is shown at ([renderMimirButtonCoin]).
 * Drawn in this composition's first frame when it can be (the rune, a mask already resident, or a coin this stage has already struck:
 * [MimirButtonCoinCache]); a mask still to decode is warmed on the Default dispatcher and the coin is struck as soon as it lands. Nothing is
 * drawn before then, and nothing after is ever drawn from a view.
 *
 * **A cold coin is a cold-art arrival (Q13, audit W1A #7):** it draws nothing while its mask decodes and then rises from nothing on the product's
 * one cold-art clock ([rememberMimirColdArtReveal]); a coin that is warm at the seat's birth is whole on frame 0. **No coin is struck twice:**
 * the software draw (a View built, measured and drawn onto a canvas, on the composition thread) is kept by (theme, scale, size), so
 * moving the preview across tiles it has already shown, and back, draws nothing.
 */
@Composable
internal fun MimirButtonCoinSnapshot(theme: CompanionOverlayTheme, markPath: String?, scale: Float, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { (MimirButtonStageCoinSize * scale).roundToPx() }.coerceAtLeast(1)
    val key = remember(theme, scale, sizePx) { MimirButtonCoinKey(theme, scale, sizePx) }
    val immediate = remember(key) {
        MimirButtonCoinCache.peek(key)
            ?: if (markPath == null || isMimirButtonMarkResident(markPath)) strikeMimirButtonCoin(context, key) else null
    }
    // Runtime 1.7.3 lint misses this inherited setter; MimirButtonPickerStageTest exercises the cold producer.
    @SuppressLint("ProduceStateDoesNotAssignValue")
    val snapshot = immediate ?: produceState<ImageBitmap?>(initialValue = null, key) {
        markPath?.let { warmMimirButtonMark(it) }
        // The seats that share a key (the 56 dp coin on the stage and on the light panel) resume one after another on Main: the first strikes, the others find its coin.
        value = MimirButtonCoinCache.peek(key) ?: strikeMimirButtonCoin(context, key)
    }.value
    val reveal = rememberMimirColdArtReveal(identity = key, warm = immediate != null, ready = snapshot != null)
    Box(modifier.size(MimirButtonStageCoinSize * scale).testTag(MimirButtonStageCoinTag)) {
        if (snapshot != null) {
            Image(bitmap = snapshot, contentDescription = null, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = reveal() })
        }
    }
}

/** What a stage coin is struck from: the overlay theme, the size multiple and the pixels it is shown at. A data class, so the same three answer the same bitmap. */
internal data class MimirButtonCoinKey(val theme: CompanionOverlayTheme, val scale: Float, val sizePx: Int)

/**
 * The stage's struck coins, for the process, bounded by bytes with the least recently used out. The coin a (theme, scale, size) draws is a pure function of those three
 * (the rune, or a mask file the app wrote once under a content name), so an entry never goes stale; a player scrubbing the tiles revisits the same dozen previews.
 */
internal object MimirButtonCoinCache {
    private const val MaxBytes = 4 * 1024 * 1024

    private val entries = object : LruCache<MimirButtonCoinKey, ImageBitmap>(MaxBytes) {
        override fun sizeOf(key: MimirButtonCoinKey, value: ImageBitmap): Int = (value.width * value.height * 4).coerceAtLeast(1)
    }

    fun peek(key: MimirButtonCoinKey): ImageBitmap? = entries.get(key)

    fun put(key: MimirButtonCoinKey, coin: ImageBitmap) {
        entries.put(key, coin)
    }

    /** The coins held, by key (`LruCache.size()` is the byte total, not the count): what a test reads to know a coin was kept and not struck again. */
    internal fun held(): Map<MimirButtonCoinKey, ImageBitmap> = entries.snapshot()

    /** A test between cases; nothing in production needs it. */
    internal fun clear() {
        entries.evictAll()
    }
}

/** Strikes the coin of [key] in software and keeps it ([MimirButtonCoinCache]). */
private fun strikeMimirButtonCoin(context: Context, key: MimirButtonCoinKey): ImageBitmap =
    renderMimirButtonCoin(context, key.theme, key.scale, key.sizePx).also { MimirButtonCoinCache.put(key, it) }

/**
 * The preview button of [theme], built over a context of [scale] times the density ([scaledToDensity]), laid out at [sizePx] square and drawn into a bitmap of
 * that size. The 2x copy is that same view at twice the density, so its rim, inset and metal are magnified, not re-solved at another size, and it draws at the
 * pixels it is shown at. The view is detached and never shown, so no focus, touch or accessibility node exists for it.
 */
internal fun renderMimirButtonCoin(context: Context, theme: CompanionOverlayTheme, scale: Float, sizePx: Int): ImageBitmap {
    val view = createMimirButtonPreviewView(context.scaledToDensity(scale), theme)
    val exactly = View.MeasureSpec.makeMeasureSpec(sizePx, View.MeasureSpec.EXACTLY)
    view.measure(exactly, exactly)
    view.layout(0, 0, sizePx, sizePx)
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    view.draw(Canvas(bitmap))
    return bitmap.asImageBitmap()
}

/**
 * One coin of the picker's ADJUST stage : the real button, both the large one to work on and the 56 dp one beside it.
 * [scale] is 1 for the real size and up to 3 for the large coin, which is that same view at that many times the density ([scaledToDensity]; the browse
 * stage's 2x copy is built the same way), so the two cannot disagree about where the image sits and the large one draws sharp.
 *
 * Unlike [LiveButtonCoin] there is no cross-fade and no rebuild per change: the view is built once for the IMAGE (and the coin's scale), and every nudge, pinch or
 * drag is pushed onto it in place ([setMimirButtonPreviewPlacement]), because a half-dp step rebuilding the button would fade it in and out
 * for the whole time a direction is held. Like the browse stage it never reaches the overlay: only A saves a placement, and the theme push
 * that follows is the live button's one repaint.
 */
@Composable
internal fun MimirButtonPickerAdjustCoin(mark: MimirButtonMark, scale: Float) {
    val palette = LocalMimirPalette.current
    val image = remember(mark.id, mark.path) { mark.copy(scale = 1f, offsetX = 0f, offsetY = 0f) }
    val theme = remember(palette, image) { companionOverlayTheme(palette.id, image) }
    val placement = remember(mark.scale, mark.offsetX, mark.offsetY) { mark.markPlacement() }
    key(theme, scale) {
        AndroidView(
            factory = { context -> createMimirButtonPreviewView(context.scaledToDensity(scale), theme) },
            update = { view ->
                setMimirButtonPreviewPlacement(view, placement)
                // The large coin wears the picker's focus ring (`MimirButtonPickerAdjustStageLayout`) and hands it its rim; the real 56 dp button beside it keeps its own.
                setMimirButtonPreviewRinged(view, ringed = scale > 1f)
            },
            modifier = Modifier.size(MimirButtonStageCoinSize * scale),
        )
    }
}

/**
 * This context at [scale] times its density (nothing else about it changes): a view built on it reads `resources.displayMetrics.density` [scale] times as large, so
 * every dp it draws with -- the coin's rim, its inset, the face it clips to -- is [scale] times as many pixels, and laid out at `56 dp * scale` of the HOST's density it is
 * the 56 dp button exactly, proportion for proportion, drawn at the resolution it is shown at. The alternative, the real 56 dp view magnified by the compositor, is the
 * same geometry resampled from 56 dp of pixels. `densityDpi` is a whole number, so a scale that is not a whole multiple differs from the true size by well under a pixel.
 */
internal fun Context.scaledToDensity(scale: Float): Context {
    if (scale == 1f) return this
    val configuration = Configuration(resources.configuration)
    configuration.densityDpi = (resources.configuration.densityDpi * scale).roundToInt().coerceAtLeast(1)
    return createConfigurationContext(configuration)
}
