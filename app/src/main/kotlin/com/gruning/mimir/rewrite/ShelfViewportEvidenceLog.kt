package com.gruning.mimir.rewrite

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import com.gruning.mimir.rewrite.feature.library.DisplayRole
import com.gruning.mimir.rewrite.feature.library.ShelfViewportEvidence

/**
 * SH02 §6.3 emitter — logs the [ShelfViewportEvidence] runtime viewport record whenever the Home shelf's
 * post-inset size settles, so EVERY evidence capture (screenshot, frame trace, logcat pull) carries the
 * exact geometry it rendered into. One line per DISTINCT record (rotation / display move re-emits; steady
 * recomposition does not). Grep: `adb logcat -s MimirShelfViewport`.
 */
internal object ShelfViewportEvidenceLog {
    private const val TAG = "MimirShelfViewport"

    @Volatile
    private var last: ShelfViewportEvidence? = null

    fun emit(context: Context, role: DisplayRole, postInsetSize: IntSize) {
        if (postInsetSize.width <= 0 || postInsetSize.height <= 0) return
        val metrics = context.resources.displayMetrics
        val refreshHz = context.display?.refreshRate ?: 0f
        val record = ShelfViewportEvidence.of(
            displayRole = role,
            postInsetWidthPx = postInsetSize.width,
            postInsetHeightPx = postInsetSize.height,
            density = metrics.density,
            densityDpi = metrics.densityDpi,
            xDpi = metrics.xdpi,
            yDpi = metrics.ydpi,
            refreshRateHz = refreshHz,
        )
        if (record == last) return
        last = record
        Log.i(TAG, record.evidenceLine())
    }
}

/** Attach to the shelf host's root: reports the settled post-inset size to [ShelfViewportEvidenceLog]. */
@Composable
internal fun Modifier.reportShelfViewportEvidence(role: DisplayRole): Modifier {
    val context = LocalContext.current
    return remember(context, role) {
        this.onSizeChanged { size -> ShelfViewportEvidenceLog.emit(context, role, size) }
    }
}
