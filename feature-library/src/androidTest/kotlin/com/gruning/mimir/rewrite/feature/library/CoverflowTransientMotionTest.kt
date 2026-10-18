package com.gruning.mimir.rewrite.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.PlatformRow
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import org.junit.Rule
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoverflowTransientMotionTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun emulationRapidRightLeftRightGlidesWithNoOutgoingHeroAndDestinationOnlyTitle() {
        val authority = DefaultFocusAuthority()
        val rows = listOf(
            PlatformRow(PlatformId.PS1, "PlayStation", 3),
            PlatformRow(PlatformId.SNES, "Super Nintendo", 4),
            PlatformRow(PlatformId.GBA, "Game Boy Advance", 5),
        )
        val keys = rows.map { coverflowPlatformKey(it.platformId) }
        var focused by mutableStateOf(keys[0])
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MimirTheme {
                Box(Modifier.size(833.dp, 444.dp)) {
                    ShelfCoverflow(
                        platforms = rows,
                        favoritesCount = 0,
                        // `ShelfCoverflow` gained a REQUIRED `homeSeatCount` in b8617710 (the adaptive
                        // seat solve) and these two androidTest call sites were not updated, leaving
                        // `:feature-library:compileDebugAndroidTestKotlin` red at HEAD . The host Box
                        // above is 833dp wide, i.e. the Odin, which solves to the primary seat count.
                        homeSeatCount = HomeShelfPrimarySeatCount,
                        focusedKey = focused,
                        authority = authority,
                        onOpenPlatform = {},
                    )
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()

        moveAndAssert(focusedKey = keys[1], title = "Super Nintendo", oldTitle = "PlayStation") {
            focused = it
        }
        moveAndAssert(focusedKey = keys[0], title = "PlayStation", oldTitle = "Super Nintendo") {
            focused = it
        }
        moveAndAssert(focusedKey = keys[1], title = "Super Nintendo", oldTitle = "PlayStation") {
            focused = it
        }

        compose.mainClock.advanceTimeBy(CoverflowOutgoingDurationMs.toLong() + 32L)
        compose.onAllNodesWithTag(CoverflowOutgoingHeroTestTag, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun nativeRapidRightLeftRightGlidesWithNoOutgoingHero() {
        val authority = DefaultFocusAuthority()
        val rows = listOf(
            NativeShelfRow("one.pkg", "One", null, isGame = true),
            NativeShelfRow("two.pkg", "Two", null, isGame = true),
            NativeShelfRow("three.pkg", "Three", null, isGame = true),
        )
        val keys = rows.map { nativeShelfKey(it.packageName) }
        var focused by mutableStateOf(keys[0])
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MimirTheme {
                Box(Modifier.size(833.dp, 444.dp)) {
                    NativeShelfCoverflow(
                        rows = rows,
                        focusedKey = focused,
                        authority = authority,
                        mode = LibraryShelfMode.ANDROID_GAMES,
                        onLaunchPackage = {},
                    )
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()

        moveAndAssert(focusedKey = keys[1], title = "Two", oldTitle = "One") { focused = it }
        moveAndAssert(focusedKey = keys[0], title = "One", oldTitle = "Two") { focused = it }
        moveAndAssert(focusedKey = keys[1], title = "Two", oldTitle = "One") { focused = it }

        compose.mainClock.advanceTimeBy(CoverflowOutgoingDurationMs.toLong() + 32L)
        compose.onAllNodesWithTag(CoverflowOutgoingHeroTestTag, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun emulationWindowPreMountsAnAlphaZeroOuterBufferBeforeEdgeEntry() {
        val authority = DefaultFocusAuthority()
        val rows = List(10) { index ->
            PlatformRow(PlatformId("buffer-platform-$index"), "Platform $index", index + 1)
        }
        val keys = rows.map { coverflowPlatformKey(it.platformId) }
        var focused by mutableStateOf(keys[4])
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MimirTheme {
                Box(Modifier.size(833.dp, 444.dp)) {
                    ShelfCoverflow(
                        platforms = rows,
                        favoritesCount = 0,
                        // `ShelfCoverflow` gained a REQUIRED `homeSeatCount` in b8617710 (the adaptive
                        // seat solve) and these two androidTest call sites were not updated, leaving
                        // `:feature-library:compileDebugAndroidTestKotlin` red at HEAD . The host Box
                        // above is 833dp wide, i.e. the Odin, which solves to the primary seat count.
                        homeSeatCount = HomeShelfPrimarySeatCount,
                        focusedKey = focused,
                        authority = authority,
                        onOpenPlatform = {},
                    )
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(300L)

        val edgeTag = shelfCoverflowTileTestTag(keys[7])
        val bufferTag = shelfCoverflowTileTestTag(keys[8])
        assertNear(3f, visualPosition(edgeTag))
        assertNear(4f, visualPosition(bufferTag))
        assertTrue(edgeAlpha(bufferTag) <= 0.001f)

        compose.runOnIdle { focused = keys[5] }
        listOf(16L, 54L, 70L, 70L).forEach { delta ->
            compose.mainClock.advanceTimeBy(delta)
            val edge = visualPosition(edgeTag)
            val incoming = visualPosition(bufferTag)
            assertTrue(incoming > edge)
            assertNear(1f, incoming - edge, tolerance = 0.06f)
            assertTrue(edgeAlpha(shelfCoverflowTileTestTag(keys[9])) <= 0.001f)
            compose.onAllNodesWithTag(edgeTag).assertCountEquals(1)
            compose.onAllNodesWithTag(bufferTag).assertCountEquals(1)
        }
    }

    @Test
    fun nativeWindowPreMountsAnAlphaZeroOuterBufferBeforeEdgeEntry() {
        val authority = DefaultFocusAuthority()
        val rows = List(10) { index ->
            NativeShelfRow("buffer.pkg.$index", "Package $index", null, isGame = true)
        }
        val keys = rows.map { nativeShelfKey(it.packageName) }
        var focused by mutableStateOf(keys[4])
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MimirTheme {
                Box(Modifier.size(833.dp, 444.dp)) {
                    NativeShelfCoverflow(
                        rows = rows,
                        focusedKey = focused,
                        authority = authority,
                        mode = LibraryShelfMode.ANDROID_GAMES,
                        onLaunchPackage = {},
                    )
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(300L)

        val edgeTag = nativeShelfCoverflowTileTestTag(rows[7].packageName)
        val bufferTag = nativeShelfCoverflowTileTestTag(rows[8].packageName)
        assertNear(3f, visualPosition(edgeTag))
        assertNear(4f, visualPosition(bufferTag))
        assertTrue(edgeAlpha(bufferTag) <= 0.001f)

        compose.runOnIdle { focused = keys[5] }
        listOf(16L, 54L, 70L, 70L).forEach { delta ->
            compose.mainClock.advanceTimeBy(delta)
            val edge = visualPosition(edgeTag)
            val incoming = visualPosition(bufferTag)
            assertTrue(incoming > edge)
            assertNear(1f, incoming - edge, tolerance = 0.06f)
            assertTrue(edgeAlpha(nativeShelfCoverflowTileTestTag(rows[9].packageName)) <= 0.001f)
            compose.onAllNodesWithTag(edgeTag).assertCountEquals(1)
            compose.onAllNodesWithTag(bufferTag).assertCountEquals(1)
        }
    }

    // The gamelist tests that stood here drove the card-style GamelistCarousel (seats counted from its centre, a neighbour label band, a
    // destination detail band). The Library's CAROUSEL view is the Home shelf now (LibraryGameShelf), covered on the JVM by
    // CarouselTouchGamelistTest, GamelistDepartureMotionTest and LibraryShatterMotionTest.

    private fun moveAndAssert(
        focusedKey: FocusKey,
        title: String,
        oldTitle: String,
        update: (FocusKey) -> Unit,
    ) {
        compose.runOnIdle { update(focusedKey) }
        compose.mainClock.advanceTimeByFrame()

        compose.onNodeWithText(title).assertExists()
        compose.onNodeWithText(oldTitle).assertDoesNotExist()
        // Logical focus/title commit immediately while the keyed tile bodies retarget continuously. There is
        // no outgoing hero subtree and exactly one logical destination seat.
        compose.onAllNodesWithTag(CoverflowOutgoingHeroTestTag, useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithTag(LibraryShelfSelectedSlotTestTag, useUnmergedTree = true).assertCountEquals(1)
    }

    private fun visualPosition(tag: String): Float =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config[CoverflowVisualPositionSemantics]

    private fun edgeAlpha(tag: String): Float =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config[CoverflowEdgeAlphaSemantics]

    private fun assertNear(expected: Float, actual: Float, tolerance: Float = 0.02f) {
        assertTrue("expected=$expected actual=$actual", kotlin.math.abs(expected - actual) <= tolerance)
    }
}
