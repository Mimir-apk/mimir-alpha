package com.gruning.mimir.rewrite

import android.content.Context
import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Product review 2026-09-28 (F5): the in-memory connected-services consent was published only after
 * ScreenScraper's record had been read, so a broken ScreenScraper record made RetroAchievements read "opted out"
 * on every game while Settings showed it connected. Consent now reaches memory whatever that record holds, and an
 * unmigrated install is never migrated from a record that cannot be read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class ConnectedServicesConsentRestoreTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    private val services: SharedPreferences
        get() = context.getSharedPreferences(ServicesPreferences, Context.MODE_PRIVATE)

    private val screenScraper: SharedPreferences
        get() = context.getSharedPreferences(ConnectedArtPreferences, Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        clearAll()
    }

    @After
    fun tearDown() {
        clearAll()
    }

    @Test
    fun `a corrupt ScreenScraper record no longer hides durable consent`() {
        writeDurableConsent()
        corruptScreenScraperRecord()

        ConnectedArtStore.restore(context)

        assertTrue("durable consent reaches memory", ConnectedServicesStore.optedIn.value)
        assertFalse("ScreenScraper itself stays closed", ConnectedArtStore.optedIn.value)
        assertTrue(ConnectedServicesStore.hasDurableOptIn(context))
    }

    @Test
    fun `ScreenScraper storage already closed for the process still publishes durable consent`() {
        writeDurableConsent()
        ConnectedArtStore.failClosedForProcess()

        ConnectedArtStore.restore(context)

        assertTrue(ConnectedServicesStore.optedIn.value)
        assertFalse(ConnectedArtStore.optedIn.value)
    }

    @Test
    fun `an unmigrated install with an unreadable ScreenScraper record stays closed and unmigrated`() {
        corruptScreenScraperRecord()

        ConnectedArtStore.restore(context)

        assertFalse(ConnectedServicesStore.optedIn.value)
        assertFalse("no migration is committed from an unreadable record", services.contains(KeyMigrated))
        assertFalse(services.contains(KeyOptedIn))
    }

    @Test
    fun `a completed migration without consent stays closed when the ScreenScraper record is corrupt`() {
        services.edit().putBoolean(KeyOptedIn, false).putBoolean(KeyMigrated, true).commit()
        corruptScreenScraperRecord()

        ConnectedArtStore.restore(context)

        assertFalse(ConnectedServicesStore.optedIn.value)
    }

    private fun writeDurableConsent() {
        assertTrue(services.edit().putBoolean(KeyOptedIn, true).putBoolean(KeyMigrated, true).commit())
    }

    private fun corruptScreenScraperRecord() {
        // A record format this build does not know loads as Unavailable, the state that used to return early.
        assertTrue(screenScraper.edit().putInt(ConnectedArtKeyFormat, 99).commit())
    }

    private fun clearAll() {
        services.edit().clear().commit()
        screenScraper.edit().clear().commit()
        ConnectedArtStore.resetProcessStateForTest()
        ConnectedServicesStore.restoreWithoutMigration(context)
    }

    private companion object {
        const val ServicesPreferences = "mimir_rewrite_connected_services"
        const val KeyOptedIn = "connectedServicesOptedIn"
        const val KeyMigrated = "connectedServicesMigratedV1"
    }
}
