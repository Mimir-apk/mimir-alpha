package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.ui.MimirViewportPosture
import com.gruning.mimir.rewrite.core.ui.MimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.mimirViewportProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**  -- compact draws a small pill. */
class GamelistCompactSearchPillTest {

    @Test
    fun `the RG draws the small pill and standard screens keep theirs`() {
        assertTrue(gamelistSearchPillIsSmall(mimirViewportProfile(564.7f, 540.7f)))
        assertTrue(gamelistSearchPillIsSmall(mimirViewportProfile(564.7f, 564.7f)))
        listOf(832.5f to 468.3f, 640f to 480f, 700f to 350f).forEach { (w, h) ->
            assertFalse("${w}x$h", gamelistSearchPillIsSmall(mimirViewportProfile(w, h)))
        }
        assertFalse(gamelistSearchPillIsSmall(MimirViewportProfile(MimirViewportPosture.COMPACT_SQUARE, 0f, 0f)))
    }

    @Test
    fun `favourites label their pill as the games list does, so it fits the same pill`() {
        // Favorites and game search use the same compact label to avoid clipping.
        // At 640x480 (not compact) "Search favorites" ellipsised in the 136 dp pill "Search games" fits.
        val source = listOf(
            java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/LibraryScreen.kt"),
            java.io.File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/LibraryScreen.kt"),
        ).first(java.io.File::isFile).readText()
        val header = source
            .substringAfter("title = platformLabel(libraryRes, primary.platforms, primary.openPlatform),")
            .substringBefore("authority = authority,")
        assertFalse("the pill never says Search favorites", header.contains("library_gamelist_search_favorites"))
        assertTrue(header.contains("R.string.library_gamelist_search_games"))
        assertTrue("the apps segment names its own rows", header.contains("R.string.library_gamelist_search_apps"))
    }

    @Test
    fun `the small pill is smaller in both directions but keeps the full touch height`() {
        assertTrue(LibraryGamelistCompactSearchWidthDp < 136f)
        assertTrue(LibraryGamelistCompactSearchPillHeightDp < LibraryGamelistSearchControlMinHeightDp)
        assertTrue(LibraryGamelistSearchControlMinHeightDp >= 44f)
    }
}
