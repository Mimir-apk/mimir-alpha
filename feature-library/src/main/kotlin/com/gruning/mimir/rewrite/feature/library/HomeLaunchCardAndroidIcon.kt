package com.gruning.mimir.rewrite.feature.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirBoxArtCorner
import com.gruning.mimir.rewrite.core.ui.MimirColdArtRevealSpec
import com.gruning.mimir.rewrite.core.ui.drawMimirMetalPlate
import com.gruning.mimir.rewrite.core.ui.mimirMetalRimAlpha
import com.gruning.mimir.rewrite.core.ui.platformConsoleIconBrush
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * The Android launch card's icon at the size the card draws it (audit 2026-10-15, F7 resolution).
 *
 * An Android game's or app's art is its launcher icon, which the app caches as a 192 px PNG for the shelves' small tiles. The launch card draws it at 257 dp (593 px at Thor Main's density 369), so the
 * 192 px file was stretched about three times and read soft. The card now renders the package's own icon (an adaptive icon's foreground, no mask or backing: it sits on a dark plate) at exactly the px it will be drawn at, once, off the main
 * thread, and keeps the last few in memory so a card reopened for the same app lands on its icon at once. Only the package's own launcher icon is replaced: art the player chose (a custom icon, a hero) is not a file in the
 * icon cache and is painted exactly as before, and a package whose icon cannot be rendered keeps the cached file.
 */

/** The cache folder the app writes launcher icons to (`NativeShelfPackages`), under the app's cache dir: the card recognises its own icon by the file standing in it. */
const val NativeShelfIconCacheDirName = "native-shelf-icons"

/** The largest edge a card icon is rendered at: the decode cap every cover shares, so one icon is never a heavier bitmap than a cover. */
internal const val HomeLaunchCardIconMaxPx = 1024

/**
 * Whether [artRef] is [packageName]'s own launcher icon from the app's icon cache: a file in [NativeShelfIconCacheDirName] named for the package. A chosen icon or hero is somewhere else and is never replaced.
 */
internal fun isNativeLauncherIconRef(artRef: String?, packageName: String): Boolean {
    val path = artRef?.removePrefix("file://")?.takeIf(String::isNotBlank) ?: return false
    val file = File(path)
    return file.parentFile?.name == NativeShelfIconCacheDirName && file.name.startsWith("$packageName-")
}

/** The test tag of the dark plate the icon sits on (a node inside the card's art box, the cartridge's own slot). */
internal const val HomeLaunchCardIconPlateTestTag = "home-launch-card-icon-plate"

/** The share of the plate's edge a legacy (non-adaptive) icon is drawn at, centred: the same 72/108 an adaptive icon's visible zone is of its canvas. */
internal const val HomeLaunchCardLegacyIconFill = 72f / 108f

/**
 * [packageName]'s launcher icon drawn at [sizePx] square to sit on the card's dark plate, or null when it cannot be (the package is gone or hidden from this app). The launcher activity's icon when it has one, as the shelf's
 * cached icon is, else the application's. Drawn at the size asked, not scaled from a smaller bitmap, and NOT masked or backed: an adaptive icon contributes its FOREGROUND layer alone over its whole
 * 108 dp canvas (whose 72 dp safe zone is the central two thirds, the glyph's natural inset on the plate), and a legacy icon is drawn centred at [HomeLaunchCardLegacyIconFill].
 */
internal fun renderLauncherIcon(context: Context, packageName: String, sizePx: Int): Bitmap? = runCatching {
    val edge = sizePx.coerceIn(1, HomeLaunchCardIconMaxPx)
    val pm = context.packageManager
    val drawable = pm.getLaunchIntentForPackage(packageName)?.let { launch -> pm.getActivityIcon(launch) } ?: pm.getApplicationIcon(packageName)
    val own = drawable.constantState?.newDrawable()?.mutate() ?: drawable
    val bitmap = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    if (own is AdaptiveIconDrawable) {
        own.foreground?.apply { setBounds(0, 0, edge, edge) }?.draw(canvas)
    } else {
        val inset = (edge * (1f - HomeLaunchCardLegacyIconFill) / 2f).roundToInt()
        own.setBounds(inset, inset, edge - inset, edge - inset)
        own.draw(canvas)
    }
    bitmap
}.getOrNull()

/** What the card holds for its icon: not yet rendered (the card holds the art out for the frame or two it takes), rendered, or not renderable (the cached file is painted). */
internal sealed interface HomeLaunchCardLauncherIcon {
    data object Loading : HomeLaunchCardLauncherIcon
    data class Ready(val bitmap: Bitmap, val warm: Boolean) : HomeLaunchCardLauncherIcon
    data object Unavailable : HomeLaunchCardLauncherIcon
}

/** The last few rendered icons, by package and px, so a card reopened for the same app lands on its icon at once. Four is a few MB: one card is on screen at a time. */
internal object HomeLaunchCardIconCache {
    private const val Capacity = 4
    private val entries = object : LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean = size > Capacity
    }

    @Synchronized fun get(key: String): Bitmap? = entries[key]

    @Synchronized fun put(key: String, bitmap: Bitmap) {
        entries[key] = bitmap
    }

    @Synchronized fun clear() = entries.clear()

    fun key(packageName: String, sizePx: Int): String = "$packageName@${sizePx.coerceIn(1, HomeLaunchCardIconMaxPx)}"
}

/** [packageName]'s launcher icon at [sizePx], rendered once off the main thread; warm from the last few. */
@Composable
internal fun rememberLauncherIconAtSize(packageName: String, sizePx: Int): HomeLaunchCardLauncherIcon {
    val context = LocalContext.current.applicationContext
    val key = HomeLaunchCardIconCache.key(packageName, sizePx)
    val warm = remember(key) { HomeLaunchCardIconCache.get(key) }
    // Keyed on the icon asked for, so another app's card never wears the last one's icon while its own renders.
    val state = remember(key) {
        mutableStateOf<HomeLaunchCardLauncherIcon>(warm?.let { HomeLaunchCardLauncherIcon.Ready(it, warm = true) } ?: HomeLaunchCardLauncherIcon.Loading)
    }
    LaunchedEffect(key) {
        if (state.value is HomeLaunchCardLauncherIcon.Ready) return@LaunchedEffect
        val rendered = withContext(Dispatchers.Default) {
            renderLauncherIcon(context, packageName, sizePx)?.also { HomeLaunchCardIconCache.put(key, it) }
        }
        state.value = if (rendered != null) HomeLaunchCardLauncherIcon.Ready(rendered, warm = false) else HomeLaunchCardLauncherIcon.Unavailable
    }
    return state.value
}

/**
 * The rendered icon in the card's art box: it fills the box (the box is the icon's own square), a cold one arriving on the cover painter's own reveal ([MimirColdArtRevealSpec], one alpha read in the draw
 * phase) and a warm one snapped, as [com.gruning.mimir.rewrite.core.ui.MimirBoxArt] treats a cold and a warm cover.
 */
@Composable
internal fun HomeLaunchCardLauncherIconImage(icon: HomeLaunchCardLauncherIcon.Ready, title: String) {
    val palette = LocalMimirPalette.current
    val reveal = remember(icon.bitmap) { Animatable(if (icon.warm) 1f else 0f) }
    LaunchedEffect(icon.bitmap) {
        if (reveal.value < 1f) reveal.animateTo(1f, MimirColdArtRevealSpec)
    }
    // the icon sits on a dark etched plate, not a white disc. The plate is the struck-metal plate the etched chips and the media plates wear ([drawMimirMetalPlate]: the hero leather ground and
    // the metal rim, palette tokens only), cut at the box-art corner the art box clips to, and it is the art box's own square: exactly the cartridge's area and slot. It is drawn in the SAME layer as the icon, so
    // the two arrive together and nothing is seated ahead of a cold icon (the 2026-10-01 rule on a plate under art that has not landed). That rule is about the no-art fallback; this plate is the Android icon's own
    // presentation and exists only for the package's own launcher icon: art the player chose never reaches this composable.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag(HomeLaunchCardIconPlateTestTag)
            .graphicsLayer { alpha = reveal.value }
            .drawWithCache {
                val metal = platformConsoleIconBrush(palette)
                val corner = MimirBoxArtCorner.toPx()
                onDrawBehind { drawMimirMetalPlate(palette, metal, mimirMetalRimAlpha(warmed = false), warmed = false, cornerPx = corner) }
            },
    ) {
        Image(
            bitmap = icon.bitmap.asImageBitmap(),
            contentDescription = title,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
