package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.feature.companion.CompanionQuickLaunchDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class CompanionGameDisplayPreferenceTest {
    private val context = RuntimeEnvironment.getApplication()
    private val gameId = GameId("preference-game")

    @Before
    fun reset() {
        CompanionGameDisplayPreferenceStore.clear(context, gameId)
        CompanionGameDisplayPreferenceStore.load(context)
    }

    @Test
    fun `destination is keyed by game and survives reload`() {
        CompanionGameDisplayPreferenceStore.set(
            context,
            gameId,
            CompanionQuickLaunchDestination.BOTTOM,
        )
        CompanionGameDisplayPreferenceStore.load(context)

        assertEquals(
            CompanionGameDisplayPreference.BOTTOM,
            CompanionGameDisplayPreferenceStore.preference(gameId),
        )
    }

    @Test
    fun `ask next time clears the remembered destination`() {
        CompanionGameDisplayPreferenceStore.set(
            context,
            gameId,
            CompanionQuickLaunchDestination.MAIN,
        )
        CompanionGameDisplayPreferenceStore.set(
            context,
            gameId,
            CompanionQuickLaunchDestination.ASK_NEXT_TIME,
        )

        assertNull(CompanionGameDisplayPreferenceStore.preference(gameId))
    }
}
