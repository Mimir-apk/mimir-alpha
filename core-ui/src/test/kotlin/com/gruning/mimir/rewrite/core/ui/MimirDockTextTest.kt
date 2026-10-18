package com.gruning.mimir.rewrite.core.ui

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The dock's words: the six seat names are string resources, English in `values/`, Latin American Spanish in the
 * two Spanish folders. The seat's accessible name is the resolved label, so a device set to Español announces and draws the Spanish one.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "b+es+419-w853dp-h480dp-xhdpi")
class MimirDockTextTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = RuntimeEnvironment.getApplication()
    private val spanish: Resources = context.resources
    private val english: Resources = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply { setLocales(LocaleList.forLanguageTags("en")) },
    ).resources

    @Test
    fun `the dock keeps its English names in the default folder`() {
        assertEquals(
            listOf("Home", "Library", "Utility", "Settings", "Apps", "Files"),
            MimirQuickRailAction.entries.map { quickRailLabelIn(english, it) },
        )
    }

    @Test
    fun `the dock reads in Latin American Spanish with the glossary's words`() {
        assertEquals(
            listOf("Inicio", "Biblioteca", "Utilidades", "Configuración", "Apps", "Archivos"),
            MimirQuickRailAction.entries.map { quickRailLabelIn(spanish, it) },
        )
    }

    @Test
    fun `the rail's seats are named in Spanish on a Spanish device`() {
        compose.setContent {
            MimirTheme {
                Box(Modifier.width(640.dp).height(120.dp)) {
                    MimirRunicQuickRail(
                        selectedAction = MimirQuickRailAction.HOME,
                        focusedAction = null,
                        onActionFocus = { },
                        onActionActivate = { },
                    )
                }
            }
        }
        compose.waitForIdle()
        listOf("Inicio", "Biblioteca", "Utilidades", "Configuración", "Apps", "Archivos").forEach { name ->
            compose.onNodeWithContentDescription(name).assertExists()
        }
    }
}
