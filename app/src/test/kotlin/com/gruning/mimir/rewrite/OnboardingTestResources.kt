package com.gruning.mimir.rewrite

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider

/**
 * The app's own resources under Robolectric, for the first-run copy helpers that take a [Resources] (localization step 1). The
 * default device locale is English, so a test that pins an English sentence reads the `values/` folder; a Spanish test asks for
 * [onboardingTestResourcesFor] with a locale tag instead.
 */
internal fun onboardingTestResources(): Resources = ApplicationProvider.getApplicationContext<Context>().resources

/** The resources of a context whose configuration carries [languageTag] (`es-419`): what an Activity on that locale reads. */
internal fun onboardingTestResourcesFor(languageTag: String): Resources {
    val base = ApplicationProvider.getApplicationContext<Context>()
    val configuration = android.content.res.Configuration(base.resources.configuration)
    configuration.setLocales(android.os.LocaleList.forLanguageTags(languageTag))
    return base.createConfigurationContext(configuration).resources
}
