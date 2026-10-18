package com.gruning.mimir.rewrite

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

/**
 * O13's Android half: the real [SharedPreferencesNotificationAskLedger], the real permission check, the real session
 * ledger, and the overlay's real enable path, `setPerformanceOverlayVisibility`, which the Main and Bottom Utility rows,
 * the single-screen M panel and the `mimir.overlayVisibility` dev hook all call. Only MainActivity is replaced, by a
 * host that counts launches, because no JVM test can put Android's dialog on screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NotificationPermissionAskRobolectricTest {

    private lateinit var app: Application
    private val host = CountingHost()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        MimirNotificationPermissionAsk.resetForTest()
        MimirNotificationPermissionAsk.attachHost(host)
        ShadowSettings.setCanDrawOverlays(true)
        PerformanceOverlaySettingsStore.setVisibility(app, PerformanceOverlayVisibility.OFF)
        MButtonSettingsStore.setVisibilityPolicy(app, MButtonVisibilityPolicy.DISABLED)
        while (shadowOf(app).nextStoppedService != null) {
            // drain anything recorded before this test acted
        }
        assertNull(
            "precondition: the process-wide session ledger must show a free Main",
            notificationAskMainOccupant(MimirSessionAuthority.state.value),
        )
    }

    @After
    fun tearDown() {
        MimirNotificationPermissionAsk.resetForTest()
    }

    @Test
    fun `turning the overlay on through setPerformanceOverlayVisibility asks once, and a refused enable asks nothing`() {
        // Control: an enable Android refuses (no draw-over grant) turns nothing on and must ask nothing.
        ShadowSettings.setCanDrawOverlays(false)
        assertEquals(
            PerformanceOverlayChangeResult.NEEDS_DRAW_ACCESS,
            setPerformanceOverlayVisibility(app, PerformanceOverlayVisibility.GAMES_ONLY, TweakerUsageAccessState.READY),
        )
        assertEquals(0, host.launches)

        ShadowSettings.setCanDrawOverlays(true)
        assertEquals(
            PerformanceOverlayChangeResult.ENABLED,
            setPerformanceOverlayVisibility(app, PerformanceOverlayVisibility.GAMES_ONLY, TweakerUsageAccessState.READY),
        )
        assertEquals("the enable asks", 1, host.launches)
        assertTrue("and records it where a later process reads it", ledgerPreferences().getBoolean("asked", false))
        assertEquals("PERFORMANCE_OVERLAY", ledgerPreferences().getString("asked_feature", null))

        // The second enable, to the other enabled scope, does not ask.
        assertEquals(
            PerformanceOverlayChangeResult.ENABLED,
            setPerformanceOverlayVisibility(app, PerformanceOverlayVisibility.ALWAYS, TweakerUsageAccessState.READY),
        )
        assertEquals(1, host.launches)

        // Nor does a later process: a new ask reads the same preferences.
        MimirNotificationPermissionAsk.resetForTest()
        MimirNotificationPermissionAsk.attachHost(host)
        setPerformanceOverlayVisibility(app, PerformanceOverlayVisibility.GAMES_ONLY, TweakerUsageAccessState.READY)
        assertEquals(1, host.launches)
    }

    @Test
    fun `a decline leaves the overlay on`() {
        assertEquals(
            PerformanceOverlayChangeResult.ENABLED,
            setPerformanceOverlayVisibility(app, PerformanceOverlayVisibility.GAMES_ONLY, TweakerUsageAccessState.READY),
        )
        assertEquals("precondition: the enable asked", 1, host.launches)

        MimirNotificationPermissionAsk.onPermissionResult(app, granted = false)

        assertEquals(PerformanceOverlayVisibility.GAMES_ONLY, PerformanceOverlaySettingsStore.visibility.value)
        assertEquals("GAMES_ONLY", overlayPreferences().getString("visibility", null))
        assertNull("a decline must not stop the overlay service", shadowOf(app).nextStoppedService)

        // Control: the same three probes DO see an overlay that was turned off.
        setPerformanceOverlayVisibility(app, PerformanceOverlayVisibility.OFF, TweakerUsageAccessState.READY)
        assertEquals(PerformanceOverlayVisibility.OFF, PerformanceOverlaySettingsStore.visibility.value)
        assertEquals("OFF", overlayPreferences().getString("visibility", null))
        assertEquals(
            PerformanceOverlayService::class.java.name,
            shadowOf(app).nextStoppedService?.component?.className,
        )
    }

    @Test
    fun `a decline leaves the M button on`() {
        MButtonSettingsStore.setVisibilityPolicy(app, MButtonVisibilityPolicy.ALWAYS_VISIBLE)
        MimirNotificationPermissionAsk.armMButton(app)
        assertEquals(
            "precondition: the first keep-alive start after the enable asked",
            NotificationAskOutcome.SHOWN,
            MimirNotificationPermissionAsk.onMButtonKeepAliveStarted(app)?.outcome,
        )
        assertEquals(1, host.launches)

        MimirNotificationPermissionAsk.onPermissionResult(app, granted = false)

        assertTrue(MButtonSettingsStore.visibilityPolicy.value.buttonEnabled)
        assertEquals("ALWAYS_VISIBLE", mButtonPreferences().getString("primary_visibility_policy", null))

        // Control: the same probes DO see a button that was turned off.
        MButtonSettingsStore.setVisibilityPolicy(app, MButtonVisibilityPolicy.DISABLED)
        assertFalse(MButtonSettingsStore.visibilityPolicy.value.buttonEnabled)
        assertEquals("DISABLED", mButtonPreferences().getString("primary_visibility_policy", null))
    }

    @Test
    fun `an already-granted permission never asks`() {
        // Control: without the grant, the same enable asks.
        setPerformanceOverlayVisibility(app, PerformanceOverlayVisibility.GAMES_ONLY, TweakerUsageAccessState.READY)
        assertEquals(1, host.launches)

        // A clean ledger whose permission is already granted.
        ledgerPreferences().edit().clear().commit()
        MimirNotificationPermissionAsk.resetForTest()
        MimirNotificationPermissionAsk.attachHost(host)
        PerformanceOverlaySettingsStore.setVisibility(app, PerformanceOverlayVisibility.OFF)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertEquals(
            PerformanceOverlayChangeResult.ENABLED,
            setPerformanceOverlayVisibility(app, PerformanceOverlayVisibility.GAMES_ONLY, TweakerUsageAccessState.READY),
        )
        assertEquals(1, host.launches)
        assertFalse("a skip records no ask", ledgerPreferences().getBoolean("asked", false))
    }

    /** Its control is the Android 14 enable in the tests above, which asks through this very path. */
    @Test
    @Config(sdk = [30])
    fun `below Android 13 turning the overlay on asks nothing`() {
        assertEquals(
            PerformanceOverlayChangeResult.ENABLED,
            setPerformanceOverlayVisibility(app, PerformanceOverlayVisibility.GAMES_ONLY, TweakerUsageAccessState.READY),
        )
        assertEquals(0, host.launches)
        assertFalse(ledgerPreferences().getBoolean("asked", false))
    }

    private fun ledgerPreferences() =
        app.getSharedPreferences("mimir_notification_permission", Context.MODE_PRIVATE)

    private fun overlayPreferences() = app.getSharedPreferences("mimir_performance_overlay", Context.MODE_PRIVATE)

    private fun mButtonPreferences() = app.getSharedPreferences("mimir_m_button", Context.MODE_PRIVATE)

    private class CountingHost : NotificationAskHost {
        var launches = 0

        override fun inFrontOnMain(): Boolean = true

        override fun launchNotificationPermissionRequest(): Boolean {
            launches += 1
            return true
        }
    }
}
