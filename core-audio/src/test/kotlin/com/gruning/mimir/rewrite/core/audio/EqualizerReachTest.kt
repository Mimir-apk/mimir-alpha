package com.gruning.mimir.rewrite.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one Equalizer's switch against the global engine: who applies the curve, in every combination, and the one-time merge of the
 * two switches it used to have.
 */
class EqualizerReachTest {
    // ---- who applies the curve ----

    @Test
    fun `off, nothing applies the curve whatever the engine says`() {
        for (status in SystemEqStatus.entries) {
            val reach = equalizerReach(enabled = false, engine = status)
            assertEquals(EqualizerReach.OFF, reach)
            assertFalse(reach.engineApplies)
            assertFalse(reach.ownStageApplies)
        }
    }

    @Test
    fun `on, the engine alone applies it when it read back as on, and Mimir's own stage stands down`() {
        val reach = equalizerReach(enabled = true, engine = SystemEqStatus.ON)
        assertEquals(EqualizerReach.ALL_SOUND, reach)
        assertTrue(reach.engineApplies)
        assertFalse(reach.ownStageApplies)
    }

    @Test
    fun `on, an engine that another app holds or Android refused leaves Mimir's own stage as the one that applies it`() {
        for (status in listOf(SystemEqStatus.NO_CONTROL, SystemEqStatus.UNAVAILABLE)) {
            val reach = equalizerReach(enabled = true, engine = status)
            assertEquals(EqualizerReach.MIMIR_ONLY, reach)
            assertFalse(reach.engineApplies)
            assertTrue(reach.ownStageApplies)
        }
    }

    @Test
    fun `on, before the engine has answered, Mimir's own stage covers the moment`() {
        val reach = equalizerReach(enabled = true, engine = SystemEqStatus.OFF)
        assertEquals(EqualizerReach.STARTING, reach)
        assertFalse(reach.engineApplies)
        assertTrue(reach.ownStageApplies)
    }

    @Test
    fun `in no combination do the engine and Mimir's own stage both apply, and when it is on one of them does or is about to`() {
        for (enabled in listOf(false, true)) for (status in SystemEqStatus.entries) {
            val reach = equalizerReach(enabled, status)
            assertFalse("both stages on for $enabled/$status", reach.engineApplies && reach.ownStageApplies)
            if (enabled) assertTrue("nothing applies the curve for $enabled/$status", reach.engineApplies || reach.ownStageApplies)
        }
    }

    @Test
    fun `the player's view of the choice is switched on exactly where Mimir's own stage applies`() {
        val choice = EqualizerSettings(enabled = true).withPreset(EqualizerPresetId.ROCK)
        for (status in SystemEqStatus.entries) {
            val reach = equalizerReach(true, status)
            // AudioPreferences hands the player the choice with the claim applied; the claim is the engine reading back as on.
            val handed = choice.forInPlayer(systemEqualizerActive = reach.engineApplies)
            assertEquals("the own stage for $status", reach.ownStageApplies, handed.enabled)
            assertEquals("the curve is never lost to the bypass", choice.bandsDb, handed.bandsDb)
        }
        assertFalse("a switch that is off stays off for the player", EqualizerSettings().forInPlayer(systemEqualizerActive = false).enabled)
    }

    // ---- the merge of the two old switches ----

    private val rock = EqualizerSettings().withPreset(EqualizerPresetId.ROCK).withBass(2f)

    @Test
    fun `either old switch on is the one Equalizer on, with the stored curve and preset`() {
        // Music-only switch on, System EQ never stored.
        assertEquals(rock.copy(enabled = true), EqualizerSwitchMigration.merge(rock.copy(enabled = true), legacySystemSwitch = null))
        // Music-only on, System EQ off.
        assertEquals(rock.copy(enabled = true), EqualizerSwitchMigration.merge(rock.copy(enabled = true), legacySystemSwitch = false))
        // Music-only off, System EQ on: the one switch turns on, the curve and preset are the stored ones.
        val merged = EqualizerSwitchMigration.merge(rock.copy(enabled = false), legacySystemSwitch = true)
        assertTrue(merged.enabled)
        assertEquals(rock.bandsDb, merged.bandsDb)
        assertEquals(EqualizerPresetId.ROCK, merged.presetId)
        assertEquals(2f, merged.bassDb, 0f)
        // Both on.
        assertEquals(rock.copy(enabled = true), EqualizerSwitchMigration.merge(rock.copy(enabled = true), legacySystemSwitch = true))
    }

    @Test
    fun `both old switches off is off`() {
        assertEquals(rock.copy(enabled = false), EqualizerSwitchMigration.merge(rock.copy(enabled = false), legacySystemSwitch = false))
        assertEquals(rock.copy(enabled = false), EqualizerSwitchMigration.merge(rock.copy(enabled = false), legacySystemSwitch = null))
    }

    @Test
    fun `the merge changes nothing it has already merged`() {
        for (music in listOf(false, true)) for (system in listOf(null, false, true)) {
            val stored = rock.copy(enabled = music)
            val once = EqualizerSwitchMigration.merge(stored, system)
            // The second start has no legacy key left to read.
            assertEquals(once, EqualizerSwitchMigration.merge(once, legacySystemSwitch = null))
            // And a repeated run with the same key is the same answer.
            assertEquals(once, EqualizerSwitchMigration.merge(once, system))
        }
    }
}
