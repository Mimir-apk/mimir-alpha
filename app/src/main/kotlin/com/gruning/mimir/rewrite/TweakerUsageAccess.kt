package com.gruning.mimir.rewrite

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.gruning.mimir.rewrite.core.tweaker.TweakerCapabilities
import com.gruning.mimir.rewrite.core.tweaker.TweakerSession

enum class TweakerUsageAccessState(
    val valueLabel: String,
) {
    READY("READY"),
    SET_UP("SET UP"),
    INSTALL_TWEAKER("INSTALL TWEAKER"),
    UNAVAILABLE("UNAVAILABLE"),
}

internal fun resolveTweakerUsageAccessState(
    tweakerInstalled: Boolean?,
    capabilities: TweakerCapabilities?,
): TweakerUsageAccessState = when {
    tweakerInstalled == false -> TweakerUsageAccessState.INSTALL_TWEAKER
    capabilities?.usageAccessGranted == true -> TweakerUsageAccessState.READY
    capabilities?.usageAccessGranted == false -> TweakerUsageAccessState.SET_UP
    else -> TweakerUsageAccessState.UNAVAILABLE
}

internal data class TweakerUsageAccessIntentSpec(
    val action: String,
    val data: String? = null,
    val newTask: Boolean = true,
)

internal fun tweakerUsageAccessIntentSpecs(): List<TweakerUsageAccessIntentSpec> = listOf(
    TweakerUsageAccessIntentSpec(
        action = Settings.ACTION_USAGE_ACCESS_SETTINGS,
        data = "package:${TweakerSession.TWEAKER_PACKAGE}",
    ),
    TweakerUsageAccessIntentSpec(action = Settings.ACTION_USAGE_ACCESS_SETTINGS),
    TweakerUsageAccessIntentSpec(action = Settings.ACTION_SETTINGS),
)

internal fun tweakerUsageAccessIntents(): List<Intent> = tweakerUsageAccessIntentSpecs().map { spec ->
    Intent(spec.action, spec.data?.let(Uri::parse)).apply {
        if (spec.newTask) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/** Opens only a user-mediated Android Settings surface; this function never grants an app-op. */
internal fun requestTweakerUsageAccess(
    context: Context,
    canResolve: (Intent) -> Boolean = { intent ->
        intent.resolveActivity(context.packageManager) != null
    },
    launch: (Intent) -> Unit = context::startActivity,
): Boolean {
    val intent = tweakerUsageAccessIntents().firstOrNull(canResolve) ?: return false
    return runCatching { launch(intent) }.isSuccess
}
