package com.gruning.mimir.rewrite

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.feature.library.CompanionLaunchCardFacts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The launch card's Journal reads an aliased game's session total under its CANONICAL id on the very first frame (audit B-45 and B-70, RC-59). It used to seed the read with the
 * raw id and resolve the alias a turn later: "Session time" said "Not tracked" for a moment and then cut to the real figure. The process's synchronous alias cache
 * ([GameIdentityAliasCache]) now seeds the first read. The first composition is read, before any effect has run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LaunchCardFactsAliasSeedTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val oldId = GameId("old-game")
    private val newId = GameId("new-game")
    private val row = GameRow(gameId = oldId, title = "Aliased", platformId = PlatformId("genesis"), memberCount = 1, duplicateKind = null)

    @Before
    fun setUp() {
        // The elapsed store holds the total under the canonical id the writer used.
        val document = HomeExternalSessionElapsedDocument(elapsedTotals = linkedMapOf(HomeShelfItemId.Emulated(newId) to 3_600_000L))
        context.getSharedPreferences("mimir_home_play_time", Context.MODE_PRIVATE).edit()
            .putString("document.v1", encodeHomeExternalSessionElapsedDocument(document))
            .commit()
    }

    @After
    fun tearDown() {
        GameIdentityAliasCache.install(emptyMap())
    }

    private fun firstFacts(): CompanionLaunchCardFacts {
        compose.mainClock.autoAdvance = false
        var first: CompanionLaunchCardFacts? = null
        compose.setContent {
            MimirTheme { Probe(row) { facts -> if (first == null) first = facts } }
        }
        return first!!
    }

    @Composable
    private fun Probe(game: GameRow, onFacts: (CompanionLaunchCardFacts) -> Unit) {
        onFacts(rememberCompanionLaunchCardFacts(game))
    }

    @Test
    fun `an aliased game's total is on the first frame, under its canonical id`() {
        GameIdentityAliasCache.install(mapOf(oldId.value to newId.value))
        assertEquals("the total is read on the first composition", 3_600_000L, firstFacts().sessionTotalMillis)
    }

    @Test
    fun `control - a game with no alias reads nothing under a raw id that holds no total`() {
        GameIdentityAliasCache.install(emptyMap())
        assertNull("the raw id has no total, so the card says so honestly", firstFacts().sessionTotalMillis)
    }
}
