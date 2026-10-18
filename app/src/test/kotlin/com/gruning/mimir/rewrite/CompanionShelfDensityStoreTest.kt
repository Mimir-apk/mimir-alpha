package com.gruning.mimir.rewrite

import android.app.Application
import android.content.Context
import com.gruning.mimir.rewrite.feature.library.CompanionShelfDensity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Slice L4: the platform shelf's density step is ONE global setting, kept in the shelf's own prefs file beside the filed game order. It reads as Standard on a fresh
 * install and after a data wipe, survives a restart, and a damaged value costs the player their step and nothing else (the filed order is untouched).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CompanionShelfDensityStoreTest {

    private lateinit var app: Application

    private fun prefs() = app.getSharedPreferences("mimir_companion_shelf", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        prefs().edit().clear().commit()
        CompanionShelfGameOrderStore.load(app)
    }

    @Test
    fun `a fresh install is Standard`() {
        assertEquals(CompanionShelfDensity.STANDARD, CompanionShelfGameOrderStore.density.value)
    }

    @Test
    fun `a step is written to the shelf's prefs and survives a restart`() {
        CompanionShelfGameOrderStore.setDensity(app, CompanionShelfDensity.DENSE)
        assertEquals(CompanionShelfDensity.DENSE, CompanionShelfGameOrderStore.density.value)
        assertEquals("dense", prefs().getString("companionShelfDensity.v1", null))
        // A restart: nothing in memory, the file is read.
        CompanionShelfGameOrderStore.setDensity(app, CompanionShelfDensity.LARGE)
        prefs().edit().putString("companionShelfDensity.v1", "dense").commit()
        CompanionShelfGameOrderStore.load(app)
        assertEquals("load reads the file, not the previous process's memory", CompanionShelfDensity.DENSE, CompanionShelfGameOrderStore.density.value)
    }

    @Test
    fun `every step round-trips through the file`() {
        CompanionShelfDensity.values().forEach { step ->
            CompanionShelfGameOrderStore.setDensity(app, step)
            CompanionShelfGameOrderStore.load(app)
            assertEquals(step, CompanionShelfGameOrderStore.density.value)
        }
    }

    @Test
    fun `a data wipe puts it back to Standard`() {
        CompanionShelfGameOrderStore.setDensity(app, CompanionShelfDensity.LARGE)
        prefs().edit().clear().commit()
        CompanionShelfGameOrderStore.load(app)
        assertEquals(CompanionShelfDensity.STANDARD, CompanionShelfGameOrderStore.density.value)
    }

    @Test
    fun `a damaged value reads as Standard and the filed order is untouched`() {
        CompanionShelfGameOrderStore.setOrder(app, "psx", listOf("a", "b", "c"))
        prefs().edit().putString("companionShelfDensity.v1", "\u0000enormous").commit()
        CompanionShelfGameOrderStore.load(app)
        assertEquals(CompanionShelfDensity.STANDARD, CompanionShelfGameOrderStore.density.value)
        assertEquals(listOf("a", "b", "c"), CompanionShelfGameOrderStore.order.value["psx"])
    }

    @Test
    fun `setting the step already held writes nothing`() {
        CompanionShelfGameOrderStore.setDensity(app, CompanionShelfDensity.STANDARD)
        assertTrue("Standard is the default, so nothing was written", !prefs().contains("companionShelfDensity.v1"))
    }
}
