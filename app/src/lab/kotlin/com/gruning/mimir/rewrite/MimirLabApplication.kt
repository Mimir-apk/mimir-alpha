package com.gruning.mimir.rewrite

import android.app.Application
import android.content.Context

internal const val MIMIR_LAB_PACKAGE = "com.gruning.mimir.rewrite.lab"

/**
 * Test-only target Application.
 *
 * It deliberately performs no production startup: no Room open, replay, WorkManager, companion
 * runtime, scan, launch, provider restore, or UI initialization.
 */
class MimirLabApplication : Application() {
    override fun attachBaseContext(base: Context) {
        check(base.packageName == MIMIR_LAB_PACKAGE) {
            "The inert recovery Application may attach only to $MIMIR_LAB_PACKAGE."
        }
        super.attachBaseContext(base)
    }
}
