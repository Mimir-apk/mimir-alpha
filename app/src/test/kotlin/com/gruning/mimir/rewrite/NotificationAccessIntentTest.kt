package com.gruning.mimir.rewrite

import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where asking for notification access puts the player.
 *
 * The companion's media bridge had the same defect the Usage Access request had, for the same reason --
 * it fired the bare action, which opens the device-wide list of every notification listener on the phone
 * and leaves the player to find "Mimir" in it.  Android gives no in-app dialog for this
 * grant either, so the only thing an app controls is **which** screen -- and the detail action opens
 * Mimir's own row with its toggle.
 *
 * This is a second file rather than a case in [com.gruning.mimir.rewrite.core.companionnative.UsageAccessIntentTest]
 * because the two grants live in different modules and carry their target differently: Usage Access takes
 * a `package:` data URI, this one takes a flattened component name as an extra.
 */
class NotificationAccessIntentTest {

    private val listener = "com.gruning.mimir.rewrite/.CompanionMediaNotificationListenerService"
    private val specs = notificationAccessIntentSpecs(listener)

    /**
     * The detail form must be FIRST. This is the whole fix: it is the difference between landing on a
     * toggle and landing on a list of every listener installed on the device.
     */
    @Test
    fun `the first surface offered is Mimir's own listener row, not the device-wide list`() {
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS to listener, specs.first())
    }

    /**
     * And the fallbacks must stay, in this order. The per-listener screen is not universal -- some ROMs
     * strip it -- so the full list is second and plain Settings is the floor. A "simpler" list of one
     * would leave those devices with no way to grant it at all, which is worse than a list to search.
     */
    @Test
    fun `it degrades to the list and then to Settings, never to nothing`() {
        assertEquals(
            listOf(
                Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS to listener,
                Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS to null,
                Settings.ACTION_SETTINGS to null,
            ),
            specs,
        )
    }

    /**
     * Only the first entry names the listener. Putting the extra on the fallbacks would make them the
     * same intent as the first, so a ROM without the detail screen would fail three times instead of
     * falling back once.
     */
    @Test
    fun `the fallbacks carry no listener component`() {
        specs.drop(1).forEach { (action, component) -> assertNull("$action should be unscoped", component) }
    }
}
