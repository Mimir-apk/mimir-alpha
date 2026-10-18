package com.gruning.mimir.rewrite.core.companionnative

import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where asking for Usage Access puts the player.
 *
 *
 *
 * The research answer, recorded here because it is the reason this file is so small: **there is no better
 * way for this particular grant.** Usage Access is an appop, not a runtime permission. There is no
 * in-app dialog API for it, no `requestPermissions` path, and no way for an app to grant it to itself
 * without the privileged route Mimir already offers separately. Every frontend on Android takes you to
 * Settings for it.
 *
 * What an app *does* control is **which** Settings screen. The bare action opens the device-wide list of
 * every installed app; `package:` scoping opens Mimir's own row with its toggle. Mimir already shipped the
 * scoped list for the Tweaker's copy of this grant (`tweakerUsageAccessIntentSpecs`) and simply never gave
 * its own request the same treatment, so the app that needs it most had the worst version of it.
 */
class UsageAccessIntentTest {

    private val specs = usageAccessIntentSpecs("com.gruning.mimir.rewrite")

    /**
     * The scoped form must be FIRST. This is the whole fix: it is the difference between landing on a
     * toggle and landing on a list to search.
     */
    @Test
    fun `the first surface offered is Mimir's own row, not the device-wide list`() {
        assertEquals(
            Settings.ACTION_USAGE_ACCESS_SETTINGS to "package:com.gruning.mimir.rewrite",
            specs.first(),
        )
    }

    /**
     * And the fallbacks must stay, in this order. The per-app screen is not universal -- some ROMs strip
     * it -- so an unscoped action is second and plain Settings is the floor. A "simpler" list of one would
     * leave those devices with no way to grant it at all, which is worse than a list to search.
     */
    @Test
    fun `it degrades to the list and then to Settings, never to nothing`() {
        assertEquals(
            listOf(
                Settings.ACTION_USAGE_ACCESS_SETTINGS to "package:com.gruning.mimir.rewrite",
                Settings.ACTION_USAGE_ACCESS_SETTINGS to null,
                Settings.ACTION_SETTINGS to null,
            ),
            specs,
        )
    }

    /**
     * Only the first entry is scoped. A `package:` on the fallbacks would make them the same intent as the
     * first, so a ROM without the per-app screen would fail three times instead of falling back once.
     */
    @Test
    fun `the fallbacks carry no package scope`() {
        specs.drop(1).forEach { (action, data) -> assertNull("$action should be unscoped", data) }
    }
}
