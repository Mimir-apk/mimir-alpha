package com.gruning.mimir.rewrite

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import com.gruning.mimir.rewrite.core.ui.AmbientMotionPhase
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * An explicit theme change wakes the ambient gate, and the theme a
 * composition starts with is not one. On 3d2428b1 Main's first composition pulsed for it, and that pulse was the last wake
 * of every restart.
 *
 * Composes the real [ThemeChangeMotionWake] over the real [ThemeStore]; the gate only records pulses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ThemeChangeMotionWakeTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val gate = PulseRecordingGate()

    // Two storage keys from the dev hook's own list; the fixture checks they are real and different.
    private val stored = MimirThemeId.fromStorageKey("midnight")
    private val chosen = MimirThemeId.fromStorageKey("void")

    @Before
    fun aProcessStartWearingAStoredTheme() {
        assertNotEquals("the fixture needs two themes", stored, chosen)
        assertNotEquals("and a stored one that is not the default", MimirThemeId.DEFAULT, stored)
        storeAndLoad(stored.storageKey)
        // ThemeStore is a process object: a change an earlier test made is not part of this start.
        ThemeStore.claimExplicitChangeWake()
        assertEquals(stored, ThemeStore.themeId.value)
    }

    @After
    fun forgetTheStoredTheme() {
        storeAndLoad(null)
        ThemeStore.claimExplicitChangeWake()
    }

    /** Writes where ThemeStore keeps the choice, then loads it as a process start does. */
    private fun storeAndLoad(key: String?) {
        context.getSharedPreferences("mimir_rewrite_appearance", Context.MODE_PRIVATE)
            .edit()
            .putString("themePreference", key)
            .commit()
        ThemeStore.load(context)
    }

    @Test
    fun `a composition that starts wearing the stored theme does not wake the gate, and a later explicit change does`() {
        compose.setContent { ThemeChangeMotionWake(gate) }
        compose.waitForIdle()
        assertEquals("the theme a composition starts with is not a change", emptyList<InteractionSource>(), gate.pulses)

        compose.runOnIdle { ThemeStore.setTheme(context, chosen) }
        compose.waitForIdle()
        assertEquals("the Appearance picker's change wakes it once", listOf(InteractionSource.THEME_CHANGE), gate.pulses)

        compose.runOnIdle { ThemeStore.setTheme(context, chosen) }
        compose.waitForIdle()
        assertEquals(
            "re-selecting the theme it wears changes nothing",
            listOf(InteractionSource.THEME_CHANGE),
            gate.pulses,
        )
    }

    @Test
    fun `a change made before Main composes wakes once, and a recreated composition does not wake again`() {
        // MainActivity runs the mimir.setTheme dev hook in onCreate, before its first composition.
        ThemeStore.setTheme(context, chosen)
        var composed by mutableStateOf(true)
        compose.setContent { if (composed) ThemeChangeMotionWake(gate) }
        compose.waitForIdle()
        assertEquals("the hook's change still wakes the gate", listOf(InteractionSource.THEME_CHANGE), gate.pulses)

        composed = false
        compose.waitForIdle()
        composed = true
        compose.waitForIdle()
        assertEquals("a recreated composition is not a change", listOf(InteractionSource.THEME_CHANGE), gate.pulses)
    }

    private class PulseRecordingGate : InteractionMotionGate {
        val pulses = mutableListOf<InteractionSource>()

        override val phase: StateFlow<AmbientMotionPhase> = MutableStateFlow(AmbientMotionPhase.IDLE)

        override fun pulse(source: InteractionSource, root: InteractionRoot?) {
            pulses += source
        }

        override fun hold(root: RootLease, source: InteractionSource): InteractionLease = error("not used")

        override fun registerRoot(kind: InteractionRoot, initiallyVisible: Boolean): RootLease = error("not used")
    }
}
