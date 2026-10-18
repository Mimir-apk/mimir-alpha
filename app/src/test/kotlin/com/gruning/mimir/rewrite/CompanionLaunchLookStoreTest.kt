package com.gruning.mimir.rewrite

import android.app.Application
import android.content.Context
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.feature.library.CompanionLaunchCardFacts
import com.gruning.mimir.rewrite.feature.library.CompanionLaunchLook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Slices L5 and L6: the launch card's look is ONE global setting, kept in the shelf's own prefs file beside the density and the filed order. It reads as Cinematic on a
 * fresh install and after a data wipe, survives a restart, and a damaged value costs the player their look and nothing else.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CompanionLaunchLookStoreTest {

    private lateinit var app: Application

    private fun prefs() = app.getSharedPreferences("mimir_companion_shelf", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        prefs().edit().clear().commit()
        CompanionShelfGameOrderStore.load(app)
    }

    @Test
    fun `a fresh install is Cinematic`() {
        assertEquals(CompanionLaunchLook.CINEMATIC, CompanionShelfGameOrderStore.look.value)
    }

    @Test
    fun `a look is written to the shelf's prefs and survives a restart`() {
        CompanionShelfGameOrderStore.setLook(app, CompanionLaunchLook.JOURNAL)
        assertEquals(CompanionLaunchLook.JOURNAL, CompanionShelfGameOrderStore.look.value)
        assertEquals("journal", prefs().getString("companionLaunchLook.v1", null))
        // A restart: nothing in memory, the file is read.
        CompanionShelfGameOrderStore.setLook(app, CompanionLaunchLook.LOGO_ONLY)
        prefs().edit().putString("companionLaunchLook.v1", "journal").commit()
        CompanionShelfGameOrderStore.load(app)
        assertEquals("load reads the file, not the previous process's memory", CompanionLaunchLook.JOURNAL, CompanionShelfGameOrderStore.look.value)
    }

    @Test
    fun `every look round-trips through the file`() {
        CompanionLaunchLook.values().forEach { look ->
            CompanionShelfGameOrderStore.setLook(app, look)
            CompanionShelfGameOrderStore.load(app)
            assertEquals(look, CompanionShelfGameOrderStore.look.value)
        }
    }

    @Test
    fun `a data wipe puts it back to Cinematic`() {
        CompanionShelfGameOrderStore.setLook(app, CompanionLaunchLook.LOGO_ONLY)
        prefs().edit().clear().commit()
        CompanionShelfGameOrderStore.load(app)
        assertEquals(CompanionLaunchLook.CINEMATIC, CompanionShelfGameOrderStore.look.value)
    }

    @Test
    fun `a damaged value reads as Cinematic and the density and the filed order are untouched`() {
        CompanionShelfGameOrderStore.setOrder(app, "psx", listOf("a", "b", "c"))
        CompanionShelfGameOrderStore.setDensity(app, com.gruning.mimir.rewrite.feature.library.CompanionShelfDensity.DENSE)
        prefs().edit().putString("companionLaunchLook.v1", "\u0000sepia").commit()
        CompanionShelfGameOrderStore.load(app)
        assertEquals(CompanionLaunchLook.CINEMATIC, CompanionShelfGameOrderStore.look.value)
        assertEquals(listOf("a", "b", "c"), CompanionShelfGameOrderStore.order.value["psx"])
        assertEquals(com.gruning.mimir.rewrite.feature.library.CompanionShelfDensity.DENSE, CompanionShelfGameOrderStore.density.value)
    }

    @Test
    fun `setting the look already held writes nothing`() {
        CompanionShelfGameOrderStore.setLook(app, CompanionLaunchLook.CINEMATIC)
        assertTrue("Cinematic is the default, so nothing was written", !prefs().contains("companionLaunchLook.v1"))
    }

    @Test
    fun `the look and the density are separate keys`() {
        CompanionShelfGameOrderStore.setLook(app, CompanionLaunchLook.JOURNAL)
        assertTrue("setting the look does not write the density", !prefs().contains("companionShelfDensity.v1"))
    }

    // ── The facts the app hands the Journal ────────────────────────────────────────────────────────

    private val sonic = GameId("sonic")
    private val sonicCanonical = GameId("sonic-canonical")

    private fun snapshot(gameId: String, unlocked: Int = 12, total: Int = 40) =
        CompanionActivityAchievementSnapshot(gameId = gameId, account = "player", unlocked = unlocked, total = total)

    @Test
    fun `the session total is the elapsed store's under the canonical id, and a missing key is no total`() {
        val totals = mapOf<HomeShelfItemId, Long>(HomeShelfItemId.Emulated(sonicCanonical) to 4_000_000L, HomeShelfItemId.Emulated(sonic) to 9L)
        assertEquals(4_000_000L, companionLaunchCardFacts(sonic, sonicCanonical, totals, emptyList()).sessionTotalMillis)
        assertNull("no key is no total (the card says Not tracked), never zero", companionLaunchCardFacts(sonic, GameId("other"), totals, emptyList()).sessionTotalMillis)
        assertNull(companionLaunchCardFacts(sonic, sonic, emptyMap(), emptyList()).sessionTotalMillis)
    }

    @Test
    fun `the achievements are this game's last snapshot, else both counts are null`() {
        val withSnapshot = companionLaunchCardFacts(sonic, sonic, emptyMap(), listOf(snapshot("other", 1, 2), snapshot("sonic", 12, 40)))
        assertEquals(12, withSnapshot.achievementsDone)
        assertEquals(40, withSnapshot.achievementsTotal)
        val without = companionLaunchCardFacts(sonic, sonic, emptyMap(), listOf(snapshot("other")))
        assertNull(without.achievementsDone)
        assertNull(without.achievementsTotal)
        assertEquals(CompanionLaunchCardFacts.None, companionLaunchCardFacts(sonic, sonic, emptyMap(), emptyList()))
    }
}
