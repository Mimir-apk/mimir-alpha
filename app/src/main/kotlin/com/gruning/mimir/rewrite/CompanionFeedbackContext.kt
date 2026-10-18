package com.gruning.mimir.rewrite

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display

/**
 * The context to make a toast from so that it shows on [displayId], the display the player acted on.
 *
 * A text toast is placed by the display of the context it was made with: `Toast.show()` hands `Context.getDisplayId()` to
 * the notification service. A process context has no display of its own and is placed on the default display, so a
 * failure the player caused on the Bottom was reported on Main. QA 2026-09-11 (D6) found this for the Swap refusal, which
 * MainActivity's `showCompanionFeedbackToast` routes; round 5 (5b) found it for the ᛗ router's failure toasts and for the
 * overlay-hosted Tabs drawer's.
 *
 * [base] is a process context. A display context is made only for a display that exists and is not the default one. For
 * null, the default display, or a display that has gone, [base] itself is returned, so Main and single-screen devices keep
 * the context they had. The wording stays the caller's.
 */
internal fun companionFeedbackContext(base: Context, displayId: Int?): Context = displayId
    ?.takeIf { it != Display.DEFAULT_DISPLAY }
    ?.let { id -> base.getSystemService(DisplayManager::class.java)?.getDisplay(id) }
    ?.let { display -> runCatching { base.createDisplayContext(display) }.getOrNull() }
    ?: base
