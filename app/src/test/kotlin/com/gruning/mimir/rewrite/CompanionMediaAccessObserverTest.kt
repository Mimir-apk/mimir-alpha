package com.gruning.mimir.rewrite

import android.content.ComponentName
import android.os.Looper
import android.provider.Settings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * QA-09 . A player granted notification access in Android's Settings and
 * the Activity Hub kept saying "Connect your media".
 *
 * Before this test's fix, the ONLY thing that could move [CompanionMediaSessionBridge] off
 * [CompanionMediaUiState.AccessRequired] after a grant was Android binding
 * [CompanionMediaNotificationListenerService]. This pins the second, independent route: the bridge now
 * observes `enabled_notification_listeners` itself, so the grant is noticed whether or not the service
 * bind reaches the app -- which is exactly what could not be established from the 195K-line
 * device capture, because nothing on this path logged anything.
 *
 * What the state becomes after the grant is deliberately NOT asserted. Under Robolectric there is no
 * real media session server, so `getActiveSessions` may legitimately land on `SessionUnavailable`
 * rather than `NoActiveMedia`. The defect is being STUCK on AccessRequired, and that is what is pinned.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class CompanionMediaAccessObserverTest {

    private val application get() = RuntimeEnvironment.getApplication()

    private fun setEnabledListeners(value: String) {
        Settings.Secure.putString(
            application.contentResolver,
            "enabled_notification_listeners",
            value,
        )
        // The platform notifies observers when the setting is written. Robolectric's settings provider
        // does not always, so the change is announced explicitly -- the test is about the bridge's
        // reaction to the notification, not about who emits it.
        application.contentResolver.notifyChange(
            Settings.Secure.getUriFor("enabled_notification_listeners"),
            null,
        )
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun mimirListener(): String =
        ComponentName(application, CompanionMediaNotificationListenerService::class.java)
            .flattenToString()

    @Before
    fun setUp() {
        CompanionMediaSessionBridge.stop()
        setEnabledListeners("")
    }

    @After
    fun tearDown() {
        // A process-wide object: leaving it started would leak an observer into the next test.
        CompanionMediaSessionBridge.stop()
        setEnabledListeners("")
    }

    @Test
    fun grantMadeInAndroidSettingsIsNoticedWithoutAServiceBind() {
        CompanionMediaSessionBridge.start(application)
        assertEquals(
            "a bridge started with no listener enabled must report AccessRequired",
            CompanionMediaUiState.AccessRequired,
            CompanionMediaSessionBridge.state.value,
        )

        // The grant action under test: flip Mimir on in Android's notification-access screen. No Mimir
        // Activity resumes for this, and no service bind is simulated here.
        setEnabledListeners(mimirListener())

        assertNotEquals(
            "the grant landed in Settings.Secure and the bridge must not still be asking for it",
            CompanionMediaUiState.AccessRequired,
            CompanionMediaSessionBridge.state.value,
        )
    }

    @Test
    fun revokingAccessReturnsTheBridgeToAskingForIt() {
        CompanionMediaSessionBridge.start(application)
        setEnabledListeners(mimirListener())
        assertNotEquals(
            CompanionMediaUiState.AccessRequired,
            CompanionMediaSessionBridge.state.value,
        )

        // The toggle moves both ways, and a revoke is the case where continuing to claim access would
        // be the more damaging error -- the Hub would offer controls it can no longer drive.
        setEnabledListeners("")

        assertEquals(
            CompanionMediaUiState.AccessRequired,
            CompanionMediaSessionBridge.state.value,
        )
    }

    @Test
    fun anObserverIsNotStackedPerStartCall() {
        // start() runs on every Presentation construction, and the Presentation is recreated often
        // (reclaim, display changes). Registration must be idempotent or each recreate adds a live
        // observer that outlives it.
        CompanionMediaSessionBridge.start(application)
        CompanionMediaSessionBridge.start(application)
        CompanionMediaSessionBridge.start(application)

        setEnabledListeners(mimirListener())
        assertNotEquals(
            CompanionMediaUiState.AccessRequired,
            CompanionMediaSessionBridge.state.value,
        )

        CompanionMediaSessionBridge.stop()
        // After stop() the observer is gone, so a later grant must NOT resurrect a stopped bridge:
        // refresh() has no appContext to read and returns early.
        setEnabledListeners(mimirListener())
        assertEquals(
            CompanionMediaUiState.AccessRequired,
            CompanionMediaSessionBridge.state.value,
        )
    }
}
