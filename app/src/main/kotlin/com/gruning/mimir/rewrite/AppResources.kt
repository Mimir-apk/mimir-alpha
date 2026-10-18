package com.gruning.mimir.rewrite

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/**
 * The [Resources] the app's composable-side copy is read through (localization plan step 2): the host Activity's own, which follows the
 * player's language choice (`MimirLocale.attach`), never the Application's. The same read as [onboardingResources], named for the
 * screens that are not first-run setup. A copy helper that is not composable (`homeViewModeLabel(res, ...)`) takes the [Resources] as
 * its first parameter, so a test hands it any locale it likes.
 */
@Composable
@ReadOnlyComposable
internal fun appResources(): Resources {
    LocalConfiguration.current
    return LocalContext.current.resources
}
