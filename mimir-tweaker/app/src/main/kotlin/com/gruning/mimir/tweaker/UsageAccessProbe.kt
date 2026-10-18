package com.gruning.mimir.tweaker

import android.app.AppOpsManager
import android.content.Context
import android.os.Process

/** Fail-closed Usage Access probe for capability bundles. */
internal object UsageAccessProbe {
    fun granted(context: Context): Boolean? {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return null
        return runCatching {
            val mode = appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
            mode == AppOpsManager.MODE_ALLOWED
        }.getOrNull()
    }
}
