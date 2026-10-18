package com.gruning.mimir.rewrite

import android.app.Application
import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import com.gruning.mimir.rewrite.core.ui.MimirLanguage
import com.gruning.mimir.rewrite.core.ui.MimirLanguageSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import androidx.compose.runtime.snapshots.Snapshot
import org.robolectric.shadows.ShadowLooper

/**
 * The helper on API 33 and later, where Android owns the app's language (`LocaleManager`): Mimir hands the choice over, reads it
 * back, and applies nothing to an Activity itself. The branch below 33 is [MimirLocaleTest]. Robolectric API 34 stands in for
 * "33 and later".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MimirLocaleApi33Test {

    private lateinit var context: Context
    private lateinit var manager: LocaleManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("mimir_rewrite_appearance", Context.MODE_PRIVATE).edit().clear().commit()
        manager = context.getSystemService(LocaleManager::class.java)
        manager.applicationLocales = LocaleList.getEmptyLocaleList()
        MimirLanguageSettings.language = MimirLanguage.AUTOMATIC
    }

    @After
    fun tearDown() {
        MimirLanguageSettings.language = MimirLanguage.AUTOMATIC
        // MimirLanguageSettings is Compose snapshot state written outside any composition. Apply the writes and run what they
        // posted, or a later Compose test in this Robolectric sandbox can fail "Compose did not get idle" (see CompanionThemeSeedTest).
        Snapshot.sendApplyNotifications()
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun `the platform locales for each choice, empty for automatic`() {
        assertTrue(MimirLocale.platformLocales(MimirLanguage.AUTOMATIC).isEmpty)
        assertEquals("en", MimirLocale.platformLocales(MimirLanguage.ENGLISH).toLanguageTags())
        assertEquals("es-419", MimirLocale.platformLocales(MimirLanguage.SPANISH_LATAM).toLanguageTags())
    }

    @Test
    fun `a choice is handed to LocaleManager as the app locale`() {
        MimirLanguageStore.set(context, MimirLanguage.SPANISH_LATAM)
        assertEquals("es-419", manager.applicationLocales.toLanguageTags())

        MimirLanguageStore.set(context, MimirLanguage.ENGLISH)
        assertEquals("en", manager.applicationLocales.toLanguageTags())
    }

    @Test
    fun `automatic clears the override so the device locale wins`() {
        MimirLanguageStore.set(context, MimirLanguage.SPANISH_LATAM)
        MimirLanguageStore.set(context, MimirLanguage.AUTOMATIC)

        assertTrue(manager.applicationLocales.isEmpty)
    }

    @Test
    fun `load reads the language back from LocaleManager`() {
        manager.applicationLocales = LocaleList.forLanguageTags("es-419")

        MimirLanguageStore.load(context)

        assertSame(MimirLanguage.SPANISH_LATAM, MimirLanguageSettings.language)
        assertSame("the stored key follows Android's", MimirLanguage.SPANISH_LATAM, MimirLanguageStore.read(context))
    }

    @Test
    fun `a language changed in Android's own settings wins over the stored key`() {
        MimirLanguageStore.set(context, MimirLanguage.SPANISH_LATAM)
        manager.applicationLocales = LocaleList.getEmptyLocaleList()

        MimirLanguageStore.load(context)

        assertSame(MimirLanguage.AUTOMATIC, MimirLanguageSettings.language)
        assertSame(MimirLanguage.AUTOMATIC, MimirLanguageStore.read(context))
    }

    @Test
    fun `a locale Mimir does not ship reads back as automatic`() {
        manager.applicationLocales = LocaleList.forLanguageTags("fr")

        assertSame(MimirLanguage.AUTOMATIC, MimirLocale.platformLanguage(context))
    }

    @Test
    fun `attach applies nothing to an Activity, the system does`() {
        MimirLanguageStore.set(context, MimirLanguage.SPANISH_LATAM)
        // Robolectric does not apply the app locale to resources, so a stored Spanish must not be applied by Mimir either.
        val activity = Robolectric.buildActivity(LocaleProbeActivity::class.java).create().get()

        assertNull(MimirLocale.attach(activity))
        assertEquals("en", activity.resources.configuration.locales[0].language)
    }

    @Test
    fun `no recreate is scheduled by Mimir, the system recreates`() {
        val activity = Robolectric.buildActivity(LocaleProbeActivity::class.java).create().start().resume().get()

        MimirLanguageStore.set(context, MimirLanguage.SPANISH_LATAM)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(0, activity.recreated)
    }
}
