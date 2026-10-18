package com.gruning.mimir.rewrite.feature.files

import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import org.robolectric.RuntimeEnvironment

/**
 * The Files copy in a test's hands (localization step 4): the default (English) resources and the same resources for Latin American
 * Spanish. A test that calls these runs under Robolectric (`@RunWith(RobolectricTestRunner::class)`), which loads this module's `res/`.
 */
internal fun filesEnglish(): Resources = RuntimeEnvironment.getApplication().resources

internal fun filesSpanish(): Resources {
    val context = RuntimeEnvironment.getApplication()
    return context.createConfigurationContext(
        Configuration(context.resources.configuration).apply { setLocales(LocaleList.forLanguageTags("es-419")) },
    ).resources
}
