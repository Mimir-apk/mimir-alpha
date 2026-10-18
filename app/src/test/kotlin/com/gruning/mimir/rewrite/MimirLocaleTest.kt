package com.gruning.mimir.rewrite

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import com.gruning.mimir.rewrite.core.ui.MimirLanguage
import com.gruning.mimir.rewrite.core.ui.MimirLanguageSettings
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import androidx.compose.runtime.snapshots.Snapshot
import org.robolectric.shadows.ShadowLooper

/** An Activity that counts the recreates the base class asks for, standing in for MainActivity. */
class LocaleProbeActivity : MimirLocaleActivity() {
    var recreated = 0

    override fun recreate() {
        recreated++
    }
}

/** The same, standing in for a host that opts out of recreation (the display-4 floor, the return and transfer hosts). */
class LocaleOptOutProbeActivity : MimirLocaleActivity() {
    var recreated = 0

    override val recreatesWhenLanguageChanges: Boolean = false

    override fun recreate() {
        recreated++
    }
}

/**
 * The helper every Activity uses, on the branch below API 33 where Mimir applies the locale itself (localization plan step 0):
 * Automatic is no override and the device language wins; English and es-419 are applied to the Activity's resources; a change
 * recreates the Activity that asked to be recreated. The API 33 branch is [MimirLocaleApi33Test].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class MimirLocaleTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("mimir_rewrite_appearance", Context.MODE_PRIVATE).edit().clear().commit()
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

    private fun store(language: MimirLanguage) = MimirLanguageStore.set(context, language)

    private fun localeOf(configuration: Configuration): Locale = configuration.locales[0]

    // ---- the override configuration ---------------------------------------------------------------------------------------

    @Test
    fun `automatic produces no override at all`() {
        assertNull(MimirLocale.overrideConfigurationFor(MimirLanguage.AUTOMATIC))
    }

    @Test
    fun `english and es-419 produce an override that sets only the locale`() {
        val english = MimirLocale.overrideConfigurationFor(MimirLanguage.ENGLISH)!!
        assertEquals("en", localeOf(english).toLanguageTag())

        val spanish = MimirLocale.overrideConfigurationFor(MimirLanguage.SPANISH_LATAM)!!
        assertEquals("es-419", localeOf(spanish).toLanguageTag())
        assertEquals("the override must not carry the screen's own size", 0, spanish.screenWidthDp)
        assertEquals("nor its font scale", 0f, spanish.fontScale, 0f)
        assertEquals("nor its orientation", Configuration.ORIENTATION_UNDEFINED, spanish.orientation)
    }

    @Test
    fun `an override on a context themed wrapper reaches its string resources, as it does on an Activity`() {
        val wrapper = ContextThemeWrapper(context, 0)
        wrapper.applyOverrideConfiguration(MimirLocale.overrideConfigurationFor(MimirLanguage.SPANISH_LATAM)!!)

        assertEquals("es-419", localeOf(wrapper.resources.configuration).toLanguageTag())
        assertEquals("Teclado Mimir", wrapper.resources.getString(R.string.mimir_ime_label))
        assertEquals("Mimir Keyboard", context.resources.getString(R.string.mimir_ime_label))
    }

    // ---- attach on an Activity --------------------------------------------------------------------------------------------

    @Test
    fun `automatic leaves the device locale in charge`() {
        store(MimirLanguage.AUTOMATIC)

        val activity = Robolectric.buildActivity(LocaleProbeActivity::class.java).create().get()

        assertEquals(localeOf(context.resources.configuration), localeOf(activity.resources.configuration))
        assertEquals("Mimir Keyboard", activity.getString(R.string.mimir_ime_label))
    }

    @Test
    @Config(qualifiers = "es")
    fun `automatic on a Spanish device is Spanish, the device language wins`() {
        store(MimirLanguage.AUTOMATIC)

        val activity = Robolectric.buildActivity(LocaleProbeActivity::class.java).create().get()

        assertEquals("es", localeOf(activity.resources.configuration).language)
        assertEquals("Teclado Mimir", activity.getString(R.string.mimir_ime_label))
    }

    @Test
    @Config(qualifiers = "es")
    fun `english is applied to the Activity even when the device is in Spanish`() {
        store(MimirLanguage.ENGLISH)

        val activity = Robolectric.buildActivity(LocaleProbeActivity::class.java).create().get()

        assertEquals("en", localeOf(activity.resources.configuration).language)
        assertEquals("Mimir Keyboard", activity.getString(R.string.mimir_ime_label))
        assertEquals("the application context keeps the device language", "Teclado Mimir", context.getString(R.string.mimir_ime_label))
    }

    @Test
    fun `es-419 is applied to the Activity and its strings come from the Spanish resources`() {
        store(MimirLanguage.SPANISH_LATAM)

        val activity = Robolectric.buildActivity(LocaleProbeActivity::class.java).create().get()

        assertEquals("es-419", localeOf(activity.resources.configuration).toLanguageTag())
        assertEquals("Teclado Mimir", activity.getString(R.string.mimir_ime_label))
        assertEquals("Panel táctil de Mimir", activity.getString(R.string.mimir_trackpad_service_label))
    }

    @Test
    fun `the application context is not changed by the Activity override`() {
        store(MimirLanguage.SPANISH_LATAM)

        Robolectric.buildActivity(LocaleProbeActivity::class.java).create()

        assertEquals("Mimir Keyboard", context.getString(R.string.mimir_ime_label))
    }

    @Test
    fun `an Activity opting out of recreation still follows the stored choice when created`() {
        store(MimirLanguage.SPANISH_LATAM)

        val activity = Robolectric.buildActivity(LocaleOptOutProbeActivity::class.java).create().get()

        assertEquals("Teclado Mimir", activity.getString(R.string.mimir_ime_label))
    }

    // ---- recreating on a change -------------------------------------------------------------------------------------------

    @Test
    fun `a new choice recreates the Activity once`() {
        store(MimirLanguage.AUTOMATIC)
        val controller = Robolectric.buildActivity(LocaleProbeActivity::class.java).create().start().resume()
        val activity = controller.get()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("nothing changed yet", 0, activity.recreated)

        store(MimirLanguage.SPANISH_LATAM)
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(1, activity.recreated)
    }

    @Test
    fun `choosing what is already applied does not recreate`() {
        store(MimirLanguage.ENGLISH)
        val activity = Robolectric.buildActivity(LocaleProbeActivity::class.java).create().start().resume().get()

        store(MimirLanguage.ENGLISH)
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(0, activity.recreated)
    }

    @Test
    fun `an Activity that opts out is not recreated`() {
        store(MimirLanguage.AUTOMATIC)
        val activity = Robolectric.buildActivity(LocaleOptOutProbeActivity::class.java).create().start().resume().get()

        store(MimirLanguage.SPANISH_LATAM)
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(0, activity.recreated)
    }

    // ---- which Activities use the helper ----------------------------------------------------------------------------------

    @Test
    fun `every Mimir Activity extends the helper base`() {
        listOf(
            MainActivity::class.java,
            CompanionSecondaryHomeActivity::class.java,
            CompanionReturnActivity::class.java,
            PrimaryTransferActivity::class.java,
        ).forEach { activity ->
            assertTrue("${activity.simpleName} must extend MimirLocaleActivity", MimirLocaleActivity::class.java.isAssignableFrom(activity))
        }
    }

    @Test
    fun `the Language row is wired from Settings through the coordinator to the store, and the store loads at process start`() {
        fun source(path: String) = java.io.File(path).readText()
        val coordinator = source("src/main/kotlin/com/gruning/mimir/rewrite/MimirSettingsCoordinator.kt")
        val application = source("src/main/kotlin/com/gruning/mimir/rewrite/MimirApplication.kt")
        val detail = source("../feature-settings/src/main/kotlin/com/gruning/mimir/rewrite/feature/settings/SettingsDetail.kt")

        assertTrue(
            "a press on the Language row must cycle the choice through the store",
            coordinator.contains("canonicalRowId == LanguageRowId -> MimirLanguageStore.set(context, MimirLanguageSettings.language.next())"),
        )
        assertTrue("MimirApplication loads the stored choice", application.contains("MimirLanguageStore.load(this)"))
        assertTrue("the Appearance pane draws the Language card", detail.contains("LanguageSection(authority = authority, onRowActivate = onRowActivate)"))
    }

    @Test
    fun `only the long-lived Main recreates, the floor and the two hosts opt out`() {
        fun source(name: String) = java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/$name.kt").readText()
        val optOut = "override val recreatesWhenLanguageChanges: Boolean = false"

        assertFalse(source("MainActivity").contains("recreatesWhenLanguageChanges"))
        listOf("CompanionSecondaryHomeActivity", "CompanionReturnActivity", "PrimaryTransferActivity").forEach { name ->
            assertTrue("$name must opt out of recreation", source(name).contains(optOut))
        }
    }
}
