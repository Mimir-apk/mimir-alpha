package com.gruning.mimir.rewrite.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.ui.MimirLanguage
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings > Appearance > Language (localization plan step 0): one row that cycles Automatic, English, Español (Latinoamérica). It is
 * a focus stop in the Settings detail scope like its neighbours, a press raises its own row id, each language is shown in its own
 * language, and the title and "Automatic" come from the string resources, so a Spanish device reads them in Spanish.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w640dp-h480dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LanguagePanelRenderTest {
    @get:Rule
    val compose = createComposeRule()

    private val paneWidthDp = 560f
    private val authority = DefaultFocusAuthority()
    private val activations = mutableListOf<String>()
    private var language by mutableStateOf(MimirLanguage.AUTOMATIC)

    /** What the app's coordinator does for the row: move to the next choice. */
    private fun pressed(id: String) {
        activations += id
        language = language.next()
    }

    private fun show() {
        compose.setContent {
            MimirTheme {
                Box(Modifier.width(paneWidthDp.dp).verticalScroll(rememberScrollState())) {
                    LanguageSection(authority = authority, onRowActivate = ::pressed, language = language)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun named(text: String) = hasContentDescription(text) or hasText(text)

    private fun node(text: String): SemanticsNodeInteraction = compose.onAllNodes(named(text), useUnmergedTree = true)[0]

    private fun exists(text: String): Boolean = compose.onAllNodes(named(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun anInstallThatNeverChoseReadsAutomaticInEnglish() {
        show()
        assertTrue("the card and row are titled Language", exists("Language"))
        assertTrue("Automatic is the default value", exists("Automatic"))
        assertFalse(exists("English") || exists("Español (Latinoamérica)"))
        assertTrue("the supporting line explains Automatic", exists("The language Mimir’s menus use. Automatic follows your device."))
    }

    @Test
    fun theRowIsAFocusStopInTheSettingsDetailScope() {
        show()
        assertTrue(
            "appearance/language must register like every Settings row",
            detailKey(LanguageRowId) in authority.registeredKeysInScope(DetailScope),
        )
        assertEquals("settings/detail/appearance/language", detailKey(LanguageRowId).value)
        assertEquals(SettingsCategory.APPEARANCE, settingsCategoryForFocusKey(detailKey(LanguageRowId)))
        assertEquals(DetailScope, settingsScopeForFocusKey(detailKey(LanguageRowId)))
        assertEquals("a restored focus keeps the row", LanguageRowId, canonicalSettingsDetailRowId(LanguageRowId))
    }

    @Test
    fun aPressRaisesTheRowIdAndTheValueCyclesThroughTheThreeChoices() {
        show()
        node("Language").performScrollTo()

        node("Automatic").performClick()
        compose.waitForIdle()
        assertEquals(LanguageRowId, activations.last())
        assertTrue("English is next, in its own language", exists("English"))
        assertFalse(exists("Automatic"))

        node("English").performClick()
        compose.waitForIdle()
        assertTrue("then Español (Latinoamérica), in its own language", exists("Español (Latinoamérica)"))

        node("Español (Latinoamérica)").performClick()
        compose.waitForIdle()
        assertTrue("and back to Automatic", exists("Automatic"))
        assertEquals(listOf(LanguageRowId, LanguageRowId, LanguageRowId), activations.toList())
        assertEquals(MimirLanguage.AUTOMATIC, language)
    }

    @Test
    fun theRowStaysInsideThePane() {
        show()
        val bounds = node("Automatic").getUnclippedBoundsInRoot()
        assertTrue("starts inside the pane", bounds.left.value >= -1.5f)
        assertTrue("ends inside the pane", bounds.right.value <= paneWidthDp + 1.5f)
    }

    @Test
    @Config(qualifiers = "b+es+419-w640dp-h480dp-xhdpi")
    fun aSpanishDeviceReadsTheTitleAndAutomaticInSpanishAndKeepsTheLanguageNames() {
        show()
        assertTrue("Idioma", exists("Idioma"))
        assertTrue("Automático", exists("Automático"))
        assertFalse("the English title is gone", exists("Language"))

        language = MimirLanguage.ENGLISH
        compose.waitForIdle()
        assertTrue("English stays English", exists("English"))
        language = MimirLanguage.SPANISH_LATAM
        compose.waitForIdle()
        assertTrue("Español (Latinoamérica) stays as written", exists("Español (Latinoamérica)"))
    }
}
