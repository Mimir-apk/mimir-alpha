package com.gruning.mimir.rewrite

import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Debug
import android.util.Log
import com.gruning.mimir.rewrite.feature.companion.trimCompanionArtCachesToHalf
import com.gruning.mimir.rewrite.feature.library.trimPlatformCoverBitmapCacheToHalf
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * What this process lets go of when the system says memory is short.
 *
 * Measured on the Thor with a Switch game at its title screen: Mimir held 373 MB PSS behind the game (native heap
 * 74 MB, almost all decoded art; Java heap 70 MB; the Bottom's window buffers 121 MB; code 68 MB). The decoded art is
 * the part this process can give back on its own: the shelf's platform heroes, the Bottom's covers and icons, the
 * picker's icons. Each cache is trimmed to half its budget, least recently used first, so the art a mounted tile
 * reads every paint stays found and nothing re-decodes in view (the F2 rewarm in HomeShelfCoordinator covers only
 * Home's Coil window). Coil's own cache is left to Coil, which trims itself from RUNNING_LOW and clears from
 * BACKGROUND; its `clear()` would also empty the weak cache a mounted tile still draws from.
 *
 * Only the system's own pressure signal triggers it: RUNNING_LOW and RUNNING_CRITICAL (the process is visible and
 * memory is short) and BACKGROUND and above (the process is cached). Never UI_HIDDEN: a game launch hides every Mimir
 * window on a single-screen device, and a release there would be a release on every launch. It is deliberately NOT
 * tied to a launch either: the Thor's lowmemorykiller empties the whole visible bucket during an Eden load whatever
 * each process weighs (every adj-100 process died, 120 MB and 480 MB alike), so a smaller Mimir is not a spared Mimir.
 *
 * A collection is then requested off the main thread: a Bitmap's pixels are native and are freed when its Java
 * object is collected, so without it the trim would only be bookkeeping until the next natural GC. The two log lines
 * report the native heap as allocated before the trim and after the collection, not the bytes the caches let go.
 */
internal object MimirMemoryRelease {
    private const val TAG = "MimirMemoryRelease"

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { context, throwable ->
            Log.e(TAG, "memory release failed in $context", throwable)
        },
    )

    /**
     * The levels that release: RUNNING_LOW (10) up to but not including UI_HIDDEN (20), and BACKGROUND (40) and
     * above. RUNNING_MODERATE (5) and UI_HIDDEN (20) keep every cache.
     */
    internal fun releasesAtTrimLevel(level: Int): Boolean =
        level in ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW until ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND

    fun onTrimMemory(context: Context, level: Int) {
        if (!releasesAtTrimLevel(level)) {
            Log.i(TAG, "trim level=$level kept")
            return
        }
        release(context, reason = "trim level=$level")
    }

    private fun release(context: Context, reason: String) {
        val nativeBeforeKb = Debug.getNativeHeapAllocatedSize() / 1024
        val heroBytes = trimPlatformCoverBitmapCacheToHalf()
        val pickerBytes = HomeQuickLaunchPickerIconCache.trimToHalf()
        val companionBytes = trimCompanionArtCachesToHalf()
        Log.i(
            TAG,
            "trimmed reason=$reason nativeBeforeKb=$nativeBeforeKb heroKb=${heroBytes / 1024} " +
                "pickerKb=${pickerBytes / 1024} companionKb=${companionBytes / 1024} process=${context.packageName}",
        )
        scope.launch {
            Runtime.getRuntime().gc()
            Log.i(TAG, "collected reason=$reason nativeAfterKb=${Debug.getNativeHeapAllocatedSize() / 1024}")
        }
    }
}
