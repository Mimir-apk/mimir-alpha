package com.gruning.mimir.rewrite

import android.app.Application
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
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
 * First-run cards on a device set to Latin American Spanish (localization step 1). The qualifier is the one a player on `es-419` gets;
 * the cards read their words through `stringResource` and the copy helpers' `Resources`, so the same composables that render English
 * everywhere else render Spanish here, with the controller tokens left alone. Not a layout check: fitting the longer words on the
 * Thor and the Odin is pending device visual review.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "b+es+419-w853dp-h480dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnboardingSpanishRenderTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the profile confirm card reads in Spanish, pills included`() {
        compose.setContent {
            MimirTheme {
                OnboardingConfirmLayer(
                    open = true,
                    authority = DefaultFocusAuthority(),
                    focusIntent = OnboardingFocusIntentState(),
                    confirmEpoch = 0,
                    palette = LocalMimirPalette.current,
                    onKeepEditing = {},
                    onContinue = {},
                )
            }
        }
        compose.mainClock.advanceTimeBy(600L)
        compose.waitForIdle()
        assertTrue(compose.shows("¿Continuar sin nombre?"))
        assertTrue(compose.shows("Puedes agregar uno después en Configuración."))
        assertTrue(compose.labelled("Seguir editando").fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.labelled("Continuar").fetchSemanticsNodes().isNotEmpty())
        assertFalse("the English title must not leak through", compose.shows("Continue without a name?"))
        assertFalse(compose.shows("Keep editing"))
    }

    @Test
    fun `a permission screen reads in Spanish — title, the line, the small print and the Android page names`() {
        compose.setContent {
            MimirTheme {
                val res = LocalContext.current.resources
                OnboardingAccessCapabilityScreenBody(
                    palette = LocalMimirPalette.current,
                    copy = onboardingAccessFilesCopy(res),
                    granted = false,
                    grantedStatusLabel = res.getString(R.string.app_onboarding_access_granted),
                    playing = false,
                )
            }
        }
        compose.waitForIdle()
        assertTrue(compose.shows("Permite que Mimir use tus carpetas de juegos"))
        assertTrue(compose.shows("Para el administrador de archivos de Mimir, y para mostrar y guardar tus ilustraciones."))
        assertTrue(compose.shows("Ahora no: elegirás las carpetas en el selector de Android."))
        assertFalse(compose.shows("Let Mimir use your game folders"))
    }

    @Test
    fun `the copy helpers answer in Spanish on this locale and keep numbers and tokens`() {
        val res = onboardingTestResources()
        assertEquals("3 de 4 · Acceso a datos de uso", onboardingAccessProgressLabel(res, screens, OnboardingAccessScreenId.USAGE))
        assertEquals("Aún no hay nada en tu estante de Inicio.", onboardingFavoritesCopy(res, 0).chosenLine)
        assertEquals("1 juego en tu estante de Inicio.", onboardingFavoritesCopy(res, 1).chosenLine)
        assertEquals("4 juegos en tu estante de Inicio.", onboardingFavoritesCopy(res, 4).chosenLine)
        assertEquals("A Marcar · B Atrás", onboardingFavoritesTomeHint(res, null, filterKeyPresent = false, hasGames = true))
        assertEquals("1 juego en 1 plataforma.", onboardingLibraryCountLine(res, 1, 1))
        assertEquals("12 juegos en 3 plataformas.", onboardingLibraryCountLine(res, 12, 3))
        // The status line's numbers stay authored values wherever the language puts them.
        val intent = onboardingSettlementStatusIntent(res, done = 3, total = 10)
        assertEquals("3 de 10 revisados", intent.spokenText)
        assertEquals(
            listOf("3", "10"),
            (intent as com.gruning.mimir.rewrite.core.ui.MimirTextIntent.Mixed).fragments
                .filterIsInstance<com.gruning.mimir.rewrite.core.ui.MimirTextFragment.Authored>().map { it.text },
        )
        assertEquals("Se encontró 1 archivo", scanLine(1))
        assertEquals("Se encontraron 2 archivos", scanLine(2))
    }

    private val screens = listOf(
        OnboardingAccessScreenId.ORIENTATION,
        OnboardingAccessScreenId.FILES,
        OnboardingAccessScreenId.OVERLAY,
        OnboardingAccessScreenId.USAGE,
        OnboardingAccessScreenId.HOME_ROLE,
    )

    private fun scanLine(seen: Int): String? = onboardingLibraryBuildingCounts(
        onboardingTestResources(),
        com.gruning.mimir.rewrite.core.model.jobs.ScanProgress(com.gruning.mimir.rewrite.core.model.jobs.ScanPhase.WALKING, seen),
    )
}
