package com.gruning.mimir.rewrite.feature.library

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/**
 * The [Resources] the Home and Library copy is read through (localization plan step 2). It is the host's own, which follows the
 * player's language choice. Reading [LocalConfiguration] first is what `stringResource` does: a configuration change recomposes
 * whatever read the resources.
 *
 * For the copy helpers that are not composable (`homeTomeFolioFooter(res, ...)`): they take the [Resources] as their first
 * parameter rather than reading a context themselves, so a test hands them any locale it likes.
 */
@Composable
@ReadOnlyComposable
internal fun libraryResources(): Resources {
    LocalConfiguration.current
    return LocalContext.current.resources
}
