package com.gruning.mimir.rewrite.feature.library

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.renderedFocusIn
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.mimirViewportProfile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Library's shelf strip is started afresh by the WHOLE Search the list is under, not by the query's text alone: the memory key counts a genre or a platform chosen in Search as a
 * search (`libraryShelfWindowMemoryKey`), and the strip has to agree with it. A Search already on (a query typed) is ONE memory state whatever is added to it, so narrowing it by
 * choosing a platform changes the list and not the memory key, and the strip kept its painted position where the wider list left it: the narrowed list's seats glided in from far
 * away. Keyed on the criteria, the strip seats itself at once.
 *
 * The real [LibraryGameShelf] at Main: twelve games under a query, the tenth focused, then narrowed to the last three by choosing a platform (the query unchanged). One frame later
 * the focused tile is where it will rest. A control draws the same narrowing without the criteria handed over (the strip then has nothing to restart on) and the tile is still
 * gliding a frame later.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w900dp-h600dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryShelfSearchRestartTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val authority = DefaultFocusAuthority()

    private fun game(index: Int) = GameRow(
        gameId = GameId("g$index"),
        title = "Game $index",
        platformId = PlatformId.SNES,
        memberCount = 1,
        duplicateKind = null,
        artResolutionSettled = true,
    )

    private val all = (0 until 12).map(::game)
    private val games = mutableStateOf(all)
    /** A Search is already on (a query typed), so the memory key is the Search state's both before and after: only the criteria can tell the narrowed list from the wider one. */
    private val criteria = mutableStateOf(LibrarySearchCriteria(query = "Game"))

    @After
    fun restoreAnimators() = setCarouselAnimatorScale(1f)

    private fun mount(handCriteriaOver: Boolean) {
        authority.pushScope(GamelistScope)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(2f, 1f),
                LocalMimirViewportProfile provides mimirViewportProfile(833f, 469.6f),
            ) {
                MimirTheme {
                    Box(Modifier.requiredSize(833.dp, 380.dp).background(LocalMimirPalette.current.surfaceBase)) {
                        Box(Modifier.fillMaxSize().padding(horizontal = LibraryGamelistBodyPaddingDp)) {
                            val focus by authority.state.collectAsState()
                            val shown = games.value
                            val search = criteria.value
                            LibraryGameShelf(
                                games = shown,
                                emptyMessage = "Nothing here yet.",
                                isLoading = false,
                                searchQuery = search.query,
                                authority = authority,
                                focusState = focus,
                                centeredKey = authority.renderedFocusIn(GamelistScope, focus),
                                getArtFocused = false,
                                detailGame = null,
                                metaForGame = { null },
                                statusForGame = { null },
                                onOpenGame = {},
                                mediaContrastRail = false,
                                bottomControlInset = 60.dp,
                                windowMemoryKey = libraryShelfWindowMemoryKey(PlatformId.SNES, false, searching = search != LibrarySearchCriteria()),
                                departureFrame = null,
                                shatter = null,
                                modifier = Modifier.fillMaxSize(),
                                searchCriteria = if (handCriteriaOver) search else LibrarySearchCriteria(),
                            )
                        }
                    }
                }
            }
        }
        compose.touchFrames(3)
        authority.requestFocus(gameKey(all[9]))
        compose.touchFrames(2)
        compose.touchSettle(1_500L)
    }

    private fun tileLeft(): Float =
        compose.onAllNodes(hasTestTag(libraryShelfTileTestTag(all[9])), useUnmergedTree = true).fetchSemanticsNodes().first().boundsInWindow.left

    /** Narrows the list to the last three games by choosing a platform in Search, the query unchanged, and returns how far the focused tile still has to go one frame later. */
    private fun narrowAndMeasureTheGlide(): Float {
        val before = tileLeft()
        Snapshot.withMutableSnapshot {
            criteria.value = criteria.value.copy(platform = LibrarySearchPlatformFilter.Exact(PlatformId.SNES))
            games.value = all.takeLast(3)
        }
        compose.touchFrames(1)
        val oneFrameLater = tileLeft()
        compose.touchSettle(1_500L)
        val settled = tileLeft()
        println("SEARCHRESTART before=$before oneFrameLater=$oneFrameLater settled=$settled")
        return kotlin.math.abs(oneFrameLater - settled)
    }

    @Test
    fun `a list narrowed by a platform filter seats its strip at once`() {
        mount(handCriteriaOver = true)
        val glide = narrowAndMeasureTheGlide()
        assertTrue("the focused tile is still $glide px from where it rests one frame after the narrowing", glide <= 2f)
    }

    @Test
    fun `control, without the criteria the strip carries its old position into the narrowed list and glides`() {
        mount(handCriteriaOver = false)
        val glide = narrowAndMeasureTheGlide()
        assertTrue("the control must show a glide, or the first test proves nothing (it measured $glide px)", glide > 20f)
    }

    @Test
    fun `any one of the query, the genre and the platform is a different Search`() {
        val none = LibrarySearchCriteria()
        assertNotEquals(none, none.copy(query = "z"))
        assertNotEquals(none, none.copy(platform = LibrarySearchPlatformFilter.Exact(PlatformId.GBA)))
        assertEquals("and the same Search is the same key", none.copy(query = "z"), LibrarySearchCriteria(query = "z"))
    }
}
