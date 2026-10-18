package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RC-50 (audit 2026-10-14, O-27b): when the Favorites Tome's game list first filled, the entry key did not exist in the authority yet
 * (the slots were registered by a second effect, declared after the landing effect), so an 8-frame retry loop spun until it did, and
 * the ring was absent for up to 8 frames after the cells appeared. The landing effect registers the slots itself and asks once.
 *
 * The authority half is behaviour on the real [DefaultFocusAuthority]; the effect's order is pinned as source.
 */
class OnboardingFavoritesEntryFocusTest {

    private val games = (1..12).map { n ->
        GameRow(gameId = GameId("g$n"), title = "Game $n", platformId = PlatformId("gb"), memberCount = 1, duplicateKind = null)
    }
    private val searchKey = OnboardingFavoritesSearchKey
    private val entryKey = onboardingFavoriteGameKey("g1")

    private fun tome(): DefaultFocusAuthority {
        val authority = DefaultFocusAuthority()
        authority.pushScope(OnboardingScope)
        // The control row is composed on the first frame and holds focus; the cells are not composed yet.
        authority.register(searchKey, OnboardingScope, FocusBounds(0f, 0f, 200f, 40f))
        return authority
    }

    @Test
    fun `without the slots the entry key does not exist, and asking for it moves nothing`() {
        val authority = tome()
        assertEquals(searchKey, authority.state.value.focused)
        authority.requestFocus(entryKey)
        assertEquals("this is the frame the old loop spun on", searchKey, authority.state.value.focused)
    }

    @Test
    fun `with the slots registered first, one request lands on the first game at once`() {
        val authority = tome()
        OnboardingFavoritesFocusRegistry().update(authority, onboardingFavoritesFocusSlots(games, columns = 5))
        authority.requestFocus(entryKey)
        assertEquals(entryKey, authority.state.value.focused)
    }

    @Test
    fun `the landing effect registers the slots before it asks, and no longer spins eight frames`() {
        val source = listOf(
            File("src/main/kotlin/com/gruning/mimir/rewrite/OnboardingFlow.kt"),
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/OnboardingFlow.kt"),
        ).first(File::isFile).readText()
        val tome = source.substringAfter("private fun OnboardingFavoritesTomeScreen(").substringBefore("\n    BackHandler(enabled = !filter.keyboardOpen")
        val registry = tome.indexOf("val focusRegistry = remember { OnboardingFavoritesFocusRegistry() }")
        val landing = tome.indexOf("LaunchedEffect(focusIntent.revision, visible.isEmpty(), interactive) {")
        assertTrue("the registry is declared before the landing effect that uses it", registry in 0 until landing)
        val effect = tome.substring(landing)
        val update = effect.indexOf("focusRegistry.update(authority, onboardingFavoritesFocusSlots(visible, filter.columns))")
        val ask = effect.indexOf("authority.requestFocus(entryKey)")
        assertTrue("the slots are registered before the first request", update in 0 until ask)
        assertFalse("the 8-frame loop is gone", effect.contains("repeat(8)"))
        assertTrue("a short retry stands in for it", effect.contains("repeat(OnboardingFavoritesEntryRetryFrames)"))
        assertEquals(2, OnboardingFavoritesEntryRetryFrames)
    }
}
