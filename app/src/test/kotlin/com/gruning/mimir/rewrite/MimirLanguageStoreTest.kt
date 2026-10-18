package com.gruning.mimir.rewrite

import android.app.Application
import android.content.Context
import com.gruning.mimir.rewrite.core.ui.MimirLanguage
import com.gruning.mimir.rewrite.core.ui.MimirLanguageSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import androidx.compose.runtime.snapshots.Snapshot
import org.robolectric.shadows.ShadowLooper

/**
 * Where the language choice lives (localization plan step 0): one key in the appearance preferences file, read back into the
 * snapshot state Settings shows. Runs on API 30, the branch where this store is the truth; the API 33 read-back is
 * [MimirLocaleApi33Test].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class MimirLanguageStoreTest {

    private lateinit var context: Context

    private fun prefs() = context.getSharedPreferences("mimir_rewrite_appearance", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        prefs().edit().clear().commit()
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
    fun `a first run is automatic and stores nothing`() {
        assertSame(MimirLanguage.AUTOMATIC, MimirLanguageStore.read(context))
        MimirLanguageStore.load(context)
        assertSame(MimirLanguage.AUTOMATIC, MimirLanguageSettings.language)
        assertNull(prefs().getString(MimirLanguageStore.KEY_LANGUAGE, null))
    }

    @Test
    fun `every choice round trips through the store and back into the snapshot state`() {
        MimirLanguage.entries.forEach { language ->
            MimirLanguageStore.set(context, language)
            assertSame(language, MimirLanguageSettings.language)
            assertSame(language, MimirLanguageStore.read(context))

            MimirLanguageSettings.language = MimirLanguage.entries.first { it != language }
            MimirLanguageStore.load(context)
            assertSame("load restores $language", language, MimirLanguageSettings.language)
        }
    }

    @Test
    fun `the choice is kept under its stable key in the appearance file and leaves the theme alone`() {
        prefs().edit().putString("themePreference", "ODIN").commit()

        MimirLanguageStore.set(context, MimirLanguage.SPANISH_LATAM)

        assertEquals("languagePreference", MimirLanguageStore.KEY_LANGUAGE)
        assertEquals("es-419", prefs().getString("languagePreference", null))
        assertEquals("ODIN", prefs().getString("themePreference", null))
    }

    @Test
    fun `picking automatic again stores automatic rather than a locale`() {
        MimirLanguageStore.set(context, MimirLanguage.SPANISH_LATAM)
        MimirLanguageStore.set(context, MimirLanguage.AUTOMATIC)

        assertEquals("automatic", prefs().getString("languagePreference", null))
        assertNull(MimirLanguageStore.read(context).locale)
    }

    @Test
    fun `an unknown stored key reads as automatic instead of failing`() {
        prefs().edit().putString("languagePreference", "klingon").commit()

        assertSame(MimirLanguage.AUTOMATIC, MimirLanguageStore.read(context))
        MimirLanguageStore.load(context)
        assertSame(MimirLanguage.AUTOMATIC, MimirLanguageSettings.language)
    }

    @Test
    fun `read has no side effects on the snapshot state`() {
        prefs().edit().putString("languagePreference", "es-419").commit()

        MimirLanguageStore.read(context)

        assertSame(MimirLanguage.AUTOMATIC, MimirLanguageSettings.language)
    }
}
