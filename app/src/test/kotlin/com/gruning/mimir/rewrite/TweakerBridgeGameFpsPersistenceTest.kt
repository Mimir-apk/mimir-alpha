package com.gruning.mimir.rewrite

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Preference-level truth for the per-package frame-limit store: package isolation, explicit legacy
 * discard, and admission-checked writes. Each test uses distinct package names so the singleton
 * bridge cannot leak state between methods.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class TweakerBridgeGameFpsPersistenceTest {
    private val prefs
        get() = RuntimeEnvironment.getApplication()
            .getSharedPreferences("mimir_tweaker_bridge", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        TweakerBridge.initialize(RuntimeEnvironment.getApplication())
    }

    @Test
    fun `a cap stored for one package never answers for another`() {
        assertTrue(
            TweakerBridge.persistGameFpsLimitHz(
                packageName = "dev.legacy.eden_emulator",
                frameLimitHz = 60,
                admittedOptionsHz = listOf(30, 40, 60),
            ),
        )

        assertEquals(60, TweakerBridge.selectedGameFpsLimitHz("dev.legacy.eden_emulator"))
        assertNull(TweakerBridge.selectedGameFpsLimitHz("org.other.retro_station"))
        assertNull(TweakerBridge.selectedGameFpsPolicy("org.other.retro_station").frameLimitHz)
        assertEquals(0L, TweakerBridge.selectedGameFpsPolicy("org.other.retro_station").revision)
    }

    @Test
    fun `legacy global slot migration discards without promoting to any package`() {
        prefs.edit()
            .putInt("game_fps_limit_hz", 60)
            .putLong("game_fps_policy_revision", 9L)
            .commit()

        TweakerBridge.discardLegacyGlobalGameFpsSlot()

        assertFalse(prefs.contains("game_fps_limit_hz"))
        assertFalse(prefs.contains("game_fps_policy_revision"))
        // No package key inherited the legacy value: migration attributes the slot to nothing.
        assertTrue(prefs.all.keys.none { it.startsWith(GAME_FPS_POLICY_KEY_PREFIX) })
        assertNull(TweakerBridge.selectedGameFpsLimitHz("dev.migrated.candidate_emulator"))
    }

    @Test
    fun `legacy slot content is unreadable as policy even before the discard runs`() {
        prefs.edit()
            .putInt("game_fps_limit_hz", 60)
            .putLong("game_fps_policy_revision", 9L)
            .commit()

        assertNull(TweakerBridge.selectedGameFpsLimitHz("dev.premigration.reader_emulator"))
        assertEquals(
            TweakerGameFpsPolicy(frameLimitHz = null, revision = 0L),
            TweakerBridge.selectedGameFpsPolicy("dev.premigration.reader_emulator"),
        )
    }

    @Test
    fun `persist validates admission clears explicitly and keeps revision monotonic`() {
        val game = "dev.persist.rules_emulator"
        val admitted = listOf(30, 40, 60)

        assertFalse(TweakerBridge.persistGameFpsLimitHz(game, 45, admitted))
        assertFalse(TweakerBridge.persistGameFpsLimitHz("no-dots", 60, admitted))
        assertNull(TweakerBridge.selectedGameFpsLimitHz(game))

        assertTrue(TweakerBridge.persistGameFpsLimitHz(game, 60, admitted))
        assertEquals(TweakerGameFpsPolicy(60, 1L), TweakerBridge.selectedGameFpsPolicy(game))

        // Same value is a no-op that must not burn a revision.
        assertTrue(TweakerBridge.persistGameFpsLimitHz(game, 60, admitted))
        assertEquals(TweakerGameFpsPolicy(60, 1L), TweakerBridge.selectedGameFpsPolicy(game))

        // An explicit clear keeps the record so the ticket revision still advances.
        assertTrue(TweakerBridge.persistGameFpsLimitHz(game, null, admitted))
        assertEquals(TweakerGameFpsPolicy(null, 2L), TweakerBridge.selectedGameFpsPolicy(game))
    }
}
