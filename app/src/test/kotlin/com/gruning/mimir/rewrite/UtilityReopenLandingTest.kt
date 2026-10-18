package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [AppBrightnessCoordinator.close] leaves the last-used control as the Utility scope's memory, and
 * the panel's rows are not registered when `open()` pushes the scope, so a reopen seeded THAT row until the host's landing effect moved
 * focus to the first option. A stray Left, Right or A in that window acted on the old row (a slider, a step control, a toggle).
 *
 * The first option is stamped as the scope's memory before every push, so the first row to register is the landing row. The test
 * mounts the rows the way a composition does, the old row FIRST (the worst order), and reads the focus after each registration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class UtilityReopenLandingTest {

    @Before
    fun setUp() {
        TweakerBridge.initialize(RuntimeEnvironment.getApplication())
    }

    private fun coordinator() = AppBrightnessCoordinator(
        context = RuntimeEnvironment.getApplication(),
        controller = BrightnessController(
            settings = FakeBrightnessSettings(
                values = mutableMapOf(BrightnessSettingsKeys.PRIMARY_MODE to "0"),
                writeAccess = true,
            ),
            preferences = object : WindowBrightnessPreferenceStore {
                override fun read(target: BrightnessTarget): Float? = null
                override fun write(target: BrightnessTarget, fraction: Float) = Unit
                override fun clear(target: BrightnessTarget) = Unit
            },
            identity = BrightnessDeviceIdentity(
                manufacturer = "Moorechip",
                brand = "Retroid",
                model = "Retroid Pocket 5",
                vendorSettingsPackage = VendorUtilityPackages.RETROID_SETTINGS,
            ),
            secondaryWindowAvailable = { false },
            deferInitialPrivilegedProbe = true,
        ),
        authority = DefaultFocusAuthority(),
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    @Test
    fun `a reopened Utility never rests on the row the last visit ended on`() {
        val coordinator = coordinator()
        val authority = coordinator.authority
        coordinator.open(UtilitySurface.UTILITY)
        assertTrue(coordinator.isOpen)
        val controls = brightnessControls(
            requireNotNull(coordinator.state),
            filesRouteAvailable = coordinator.filesRouteOffered,
        )
        assertTrue("the fixture needs at least two rows: $controls", controls.size >= 2)
        val first = brightnessFocusKey(controls.first())
        val old = brightnessFocusKey(controls.last())
        val bounds = FocusBounds(0f, 0f, 100f, 40f)

        // First visit: the player moves to the last row, then closes.
        authority.register(first, BrightnessScope, bounds)
        authority.register(old, BrightnessScope, FocusBounds(0f, 100f, 100f, 140f))
        authority.requestFocus(old)
        assertEquals(old, authority.state.value.focused)
        coordinator.close()
        authority.unregister(first)
        authority.unregister(old)

        // Second visit: the old row mounts first.
        coordinator.open(UtilitySurface.UTILITY)
        assertEquals(BrightnessScope, authority.state.value.scope)
        val seen = mutableListOf<FocusKey?>(authority.state.value.focused)
        authority.register(old, BrightnessScope, FocusBounds(0f, 100f, 100f, 140f))
        seen += authority.state.value.focused
        authority.register(first, BrightnessScope, bounds)
        seen += authority.state.value.focused

        assertNotEquals("the old row", first, old)
        assertFalse("the old row is never the focused one while the panel mounts: $seen", old in seen)
        assertEquals("the first option is what lands", first, authority.state.value.focused)
        assertNotNull(coordinator.state)
    }

    @Test
    fun `every push of the Utility scope stamps the landing row first, and the host stamps before its request`() {
        val coordinator = source("AppBrightnessCoordinator.kt")
        val pushes = Regex("""authority\.pushScope\(BrightnessScope\)""").findAll(coordinator).count()
        val stamped = Regex("""stampBrightnessLanding\(\)\s+authority\.pushScope\(BrightnessScope\)""").findAll(coordinator).count()
        assertEquals("both pushes (open, rebindHost) are stamped", pushes, stamped)
        assertTrue(pushes >= 2)
        val host = source("AppBrightnessHost.kt")
        assertTrue(
            "the host's request for the landing row leaves the memory behind it",
            host.contains("authority.rememberFocusForScope(BrightnessScope, brightnessFocusKey(it))\n            authority.requestFocus(brightnessFocusKey(it))"),
        )
    }

    private fun source(name: String): String = listOf(
        File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
    ).first(File::isFile).readText()
}
