package com.gruning.mimir.rewrite

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirViewportPosture
import com.gruning.mimir.rewrite.core.ui.MimirViewportProfile
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCard
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardAchievementChipTestTag
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardAchievementsKey
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardOpenWithKey
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardPlayKey
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardRenameTestTag
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardScope
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardTitleTestTag
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardUiState
import com.gruning.mimir.rewrite.feature.library.HomeShelfRow
import com.gruning.mimir.rewrite.feature.library.homeLaunchCardActionTestTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 *  The count is an etched chip beside
 * the game's name (after the Rename glyph); a name too long to leave it room wraps it onto the line below. On the REAL [HomeLaunchCard], at Thor Main's 832.5 x 468.3 dp, a
 * narrower standard card and the compact floor, at font scales 1.0, 1.3 and 2.0, this proves: the chip is fully visible (inside the screen and its own text not cut), it overlaps
 * neither Rename nor the name nor any action, the name is not narrowed or ellipsized BECAUSE of the chip (the same name with a far wider count lays out identically), and the
 * count arriving moves nothing. The minimal card (every compact screen, policy 2026-09-28) keeps no count. Pixel values are the JVM's; the handheld's are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeLaunchCardAchievementChipTest {

    @get:Rule
    val compose = createComposeRule()

    private val hostTag = "chip-test-host"
    private val shortTitle = "Chrono Trigger"
    private val longTitle = "The Legend of Zelda The Minish Cap Collectors Special Anniversary Edition Remastered Deluxe Directors Cut Of The Ages"

    private fun state(title: String, progress: String?) = HomeLaunchCardUiState(
        row = HomeShelfRow(
            itemId = HomeShelfItemId.Emulated(GameId("game-1")),
            rank = 0L,
            addedAtMillis = 0L,
            title = title,
            supportingLabel = "snes",
        ),
        platformLabel = "Super Nintendo",
        achievementProgress = progress,
        emulatorLabel = "RetroArch",
        lastPlayedLabel = "Yesterday",
    )

    private var cardState by mutableStateOf(state(shortTitle, "12/40"))
    private var fontScale by mutableStateOf(1f)
    private var focusAuthority: DefaultFocusAuthority? = null

    private fun mount(profile: MimirViewportProfile, widthDp: Float, heightDp: Float) {
        compose.setContent {
            val authority = remember { DefaultFocusAuthority().apply { pushScope(HomeLaunchCardScope) }.also { focusAuthority = it } }
            CompositionLocalProvider(
                LocalDensity provides Density(density = 1f, fontScale = fontScale),
                LocalMimirViewportProfile provides profile,
            ) {
                MimirTheme {
                    Box(modifier = Modifier.requiredWidth(widthDp.dp).requiredHeight(heightDp.dp).testTag(hostTag)) {
                        HomeLaunchCard(
                            state = cardState,
                            onPlay = {},
                            onAchievements = {},
                            onOpenWith = {},
                            onClose = {},
                            authority = authority,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun show(title: String, progress: String?, font: Float) {
        fontScale = font
        cardState = state(title, progress)
        compose.waitForIdle()
    }

    private fun rect(tag: String): Rect? =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.boundsInRoot

    private fun actions(): List<Pair<String, Rect>> = listOf(HomeLaunchCardPlayKey, HomeLaunchCardAchievementsKey, HomeLaunchCardOpenWithKey).mapNotNull { key ->
        rect(homeLaunchCardActionTestTag(key))?.let { key.value to it }
    }

    private fun layoutOfTag(tag: String): TextLayoutResult {
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        val out = ArrayList<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(out)
        return out.first()
    }

    /** The count's own text: its layout and the box it is drawn in. */
    private fun chipText(label: String): Pair<TextLayoutResult, Rect> {
        val node = compose.onAllNodes(hasText(label) and hasAnyAncestor(hasTestTag(HomeLaunchCardAchievementChipTestTag)), useUnmergedTree = true)
            .fetchSemanticsNodes().first()
        val out = ArrayList<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(out)
        return out.first() to node.boundsInRoot
    }

    private fun inside(outer: Rect, inner: Rect): Boolean =
        inner.left >= outer.left - 0.5f && inner.top >= outer.top - 0.5f && inner.right <= outer.right + 0.5f && inner.bottom <= outer.bottom + 0.5f

    /** Everything that must hold for the chip at one size, one name and one count. */
    private fun assertChipSound(label: String, tag: String) {
        val host = rect(hostTag)!!
        val title = rect(HomeLaunchCardTitleTestTag)
        val rename = rect(HomeLaunchCardRenameTestTag)
        val chip = rect(HomeLaunchCardAchievementChipTestTag)
        assertNotNull("$tag: the name is drawn", title)
        assertNotNull("$tag: the Rename glyph is drawn", rename)
        assertNotNull("$tag: the chip is drawn", chip)
        title!!; rename!!; chip!!
        println("CHIP $tag host=$host title=$title rename=$rename chip=$chip actions=${actions()}")
        assertTrue("$tag: the chip is fully on the screen: $chip in $host", inside(host, chip))
        assertTrue("$tag: the chip has a real size: $chip", chip.width > 8f && chip.height > 8f)
        assertFalse("$tag: the chip does not overlap the name: $chip vs $title", chip.overlaps(title))
        assertFalse("$tag: the chip does not overlap Rename: $chip vs $rename", chip.overlaps(rename))
        assertFalse("$tag: Rename does not overlap the name: $rename vs $title", rename.overlaps(title))
        val acts = actions()
        assertTrue("$tag: the actions are drawn: $acts", acts.isNotEmpty())
        acts.forEach { (name, bounds) ->
            assertFalse("$tag: the chip does not overlap $name: $chip vs $bounds", chip.overlaps(bounds))
            assertFalse("$tag: Rename does not overlap $name: $rename vs $bounds", rename.overlaps(bounds))
            assertFalse("$tag: the name does not overlap $name: $title vs $bounds", title.overlaps(bounds))
        }
        val (text, textBox) = chipText(label)
        assertEquals("$tag: the count is one line", 1, text.lineCount)
        assertFalse("$tag: the count is not ellipsized", text.isLineEllipsized(0))
        assertTrue("$tag: the count's box is inside the chip: $textBox in $chip", inside(chip, textBox))
        assertTrue("$tag: the count's box holds its whole line (not cut): ${textBox.width} vs ${text.getLineRight(0) - text.getLineLeft(0)}", textBox.width + 0.5f >= text.getLineRight(0) - text.getLineLeft(0))
    }

    /** The name is laid out the same whatever the count is: the chip never narrows it, so the chip is never why it wraps or ends in an ellipsis. */
    private fun assertNameIndependentOfChip(title: String, font: Float, tag: String) {
        show(title, "12/40", font)
        val base = layoutOfTag(HomeLaunchCardTitleTestTag)
        val baseRect = rect(HomeLaunchCardTitleTestTag)!!
        show(title, "99999/999999", font)
        val wide = layoutOfTag(HomeLaunchCardTitleTestTag)
        val wideRect = rect(HomeLaunchCardTitleTestTag)!!
        assertEquals("$tag: the name's lines", base.lineCount, wide.lineCount)
        assertEquals("$tag: the name's last line ellipsis", base.isLineEllipsized(base.lineCount - 1), wide.isLineEllipsized(wide.lineCount - 1))
        assertEquals("$tag: the name's width", baseRect.width, wideRect.width, 0.5f)
        assertEquals("$tag: the name's height", baseRect.height, wideRect.height, 0.5f)
        assertEquals("$tag: the name's place", baseRect.topLeft, wideRect.topLeft)
        show(title, "12/40", font)
    }

    private fun sweepStandard(widthDp: Float, heightDp: Float, name: String) {
        mount(MimirViewportProfile(MimirViewportPosture.STANDARD, widthDp, heightDp), widthDp, heightDp)
        for (font in listOf(1.0f, 1.3f, 2.0f)) {
            for ((title, titleName) in listOf(shortTitle to "short", longTitle to "long")) {
                val tag = "$name font=$font $titleName"
                show(title, "12/40", font)
                assertChipSound("12/40", tag)
                assertNameIndependentOfChip(title, font, tag)
                show(title, "123/456", font)
                assertChipSound("123/456", "$tag wide")
            }
            // A short name is not ellipsized at the sizes a player reads at; the chip is never why it wraps (the independence check above holds at every size).
            if (font <= 1.3f) {
                show(shortTitle, "12/40", font)
                val shortLayout = layoutOfTag(HomeLaunchCardTitleTestTag)
                assertFalse("$name font=$font: the short name is not ellipsized", shortLayout.isLineEllipsized(shortLayout.lineCount - 1))
            }
        }
    }

    @Test
    fun `at Thor Main the chip is visible and clear of Rename the name and every action at every font size`() {
        sweepStandard(832.5f, 468.3f, "thor-main")
    }

    @Test
    fun `on a narrower standard card the chip is visible and clear of Rename the name and every action at every font size`() {
        sweepStandard(640f, 400f, "standard-640")
    }

    @Test
    fun `a short name has the count beside it, after Rename, and a long name has it on the line below`() {
        mount(MimirViewportProfile(MimirViewportPosture.STANDARD, 832.5f, 468.3f), 832.5f, 468.3f)
        show(shortTitle, "12/40", 1f)
        run {
            val title = rect(HomeLaunchCardTitleTestTag)!!
            val rename = rect(HomeLaunchCardRenameTestTag)!!
            val chip = rect(HomeLaunchCardAchievementChipTestTag)!!
            assertTrue("beside: the chip starts after Rename: $chip vs $rename", chip.left >= rename.right - 0.5f)
            assertTrue("beside: the chip is on the name's line: $chip vs $title", chip.top < title.bottom && chip.bottom > title.top)
        }
        show(longTitle, "12/40", 1f)
        run {
            val title = rect(HomeLaunchCardTitleTestTag)!!
            val chip = rect(HomeLaunchCardAchievementChipTestTag)!!
            assertTrue("below: the chip is under the name: $chip vs $title", chip.top >= title.bottom - 0.5f)
            assertTrue("below: from the left edge of the name: $chip vs $title", kotlin.math.abs(chip.left - title.left) <= 0.5f)
        }
    }

    @Test
    fun `the count arriving moves neither the name nor the actions`() {
        mount(MimirViewportProfile(MimirViewportPosture.STANDARD, 832.5f, 468.3f), 832.5f, 468.3f)
        for (title in listOf(shortTitle, longTitle)) {
            show(title, null, 1f)
            val titleBefore = rect(HomeLaunchCardTitleTestTag)!!
            val renameBefore = rect(HomeLaunchCardRenameTestTag)!!
            val actionsBefore = actions()
            assertNull("no count, no chip in the tree for a screen reader", rect(HomeLaunchCardAchievementChipTestTag))
            show(title, "12/40", 1f)
            assertEquals("the name stays: $title", titleBefore, rect(HomeLaunchCardTitleTestTag)!!)
            assertEquals("Rename stays: $title", renameBefore, rect(HomeLaunchCardRenameTestTag)!!)
            assertEquals("the actions stay: $title", actionsBefore, actions())
            assertNotNull(rect(HomeLaunchCardAchievementChipTestTag))
        }
    }

    /**
     * Ledger D17: a count that goes away keeps its own text while it fades out (it used to swap to the placeholder "00/00" on the first frame of its own fade), and once it has gone
     * the slot is only room: no chip, no placeholder text and no semantics node of any kind where the chip stood, so a screen reader has nothing there to land on.
     */
    @Test
    fun `a count that goes away fades with its own text and leaves no node of any kind in its slot`() {
        mount(MimirViewportProfile(MimirViewportPosture.STANDARD, 832.5f, 468.3f), 832.5f, 468.3f)
        // The bare authority seeds focus onto the first key the card registers, which is Rename, and a focused Rename hides the chip (alpha 0) from the start. The card is entered
        // on Play (enterHomeLaunchCard), so the fixture is too: the chip then fades in to full before the count goes away.
        focusAuthority!!.requestFocus(HomeLaunchCardPlayKey)
        compose.waitForIdle()
        show(shortTitle, "12/40", 1f)
        val chip = rect(HomeLaunchCardAchievementChipTestTag)!!
        val slot = Rect(chip.left - 1f, chip.top - 1f, chip.right + 1f, chip.bottom + 1f)
        fun inChip(node: SemanticsNode): Boolean {
            var at: SemanticsNode? = node
            while (at != null) {
                if (at.config.getOrNull(SemanticsProperties.TestTag) == HomeLaunchCardAchievementChipTestTag) return true
                at = at.parent
            }
            return false
        }
        // The nodes inside the chip's slot rectangle. The card has some of its own there (the unfocused Rename hint keeps an empty node at its natural size, over the chip's place),
        // so the end state is compared with what the slot held beside the chip, not with zero.
        fun nodesInSlot(excludingChip: Boolean) = compose.onAllNodes(isRoot().not(), useUnmergedTree = true).fetchSemanticsNodes()
            .filter { it.boundsInRoot.let { b -> b.width > 0f && b.height > 0f && inside(slot, b) } }
            .count { !excludingChip || !inChip(it) }
        val withChip = nodesInSlot(excludingChip = false)
        val cardsOwn = nodesInSlot(excludingChip = true)
        println("D17-SLOT with the chip: $withChip nodes, of which the card's own: $cardsOwn")
        assertTrue("sanity: the chip's own nodes are in its slot while the count shows ($withChip against the card's own $cardsOwn)", withChip > cardsOwn)

        // Frame by frame through the fade (the clock paused, as the ring tests step it): on every frame, if the chip is still in the tree it reads the count it is fading out
        // (never the placeholder the old chip swapped to on the first frame of its fade), and the placeholder text is in the tree on none.
        compose.mainClock.autoAdvance = false
        cardState = state(shortTitle, null)
        Snapshot.sendApplyNotifications()
        val frames = ArrayList<String>()
        repeat(2 * MimirMotion.OverlayMs / 16 + 2) { frame ->
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            val chipNodes = compose.onAllNodesWithTag(HomeLaunchCardAchievementChipTestTag, useUnmergedTree = true).fetchSemanticsNodes()
            val description = chipNodes.firstOrNull()?.config?.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
            frames += if (chipNodes.isEmpty()) "gone" else "chip($description)"
            if (chipNodes.isNotEmpty()) {
                assertEquals("frame $frame: while it fades the chip still reads its own count", "12 of 40 achievements", description)
            }
            assertEquals(
                "frame $frame: and the placeholder never shows on the way out",
                0,
                compose.onAllNodes(hasText("00/00"), useUnmergedTree = true).fetchSemanticsNodes().size,
            )
        }
        println("D17-FADE chip per frame (16 ms each): $frames")
        val present = frames.map { it != "gone" }
        assertTrue("the chip is still drawn on the first frame of its fade: $frames", present.first())
        assertTrue("and it is a fade of several frames, not a cut (${present.count { it }} frames with the chip): $frames", present.count { it } >= 4)
        assertFalse("it does not come back once it has gone: $frames", present.dropWhile { it }.any { it })
        assertFalse("and it has gone by the end of the fade: $frames", present.last())
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertNull("the chip is gone from the tree", rect(HomeLaunchCardAchievementChipTestTag))
        assertEquals("no placeholder text", 0, compose.onAllNodes(hasText("00/00"), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(
            "and the seat adds no node of its own: the slot holds only what the card itself put there",
            cardsOwn,
            nodesInSlot(excludingChip = false),
        )
    }

    private fun sweepCompact(widthDp: Float, heightDp: Float) {
        mount(MimirViewportProfile(MimirViewportPosture.COMPACT_SQUARE, widthDp, heightDp), widthDp, heightDp)
        for (font in listOf(1.0f, 1.3f, 2.0f)) {
            for (title in listOf(shortTitle, longTitle)) {
                show(title, "12/40", font)
                val tag = "compact ${widthDp}x$heightDp font=$font"
                assertEquals("$tag: the minimal card holds no count", 0, compose.onAllNodesWithTag(HomeLaunchCardAchievementChipTestTag, useUnmergedTree = true).fetchSemanticsNodes().size)
                val name = rect(HomeLaunchCardTitleTestTag)
                val rename = rect(HomeLaunchCardRenameTestTag)
                assertNotNull("$tag: the name", name)
                assertNotNull("$tag: Rename", rename)
                assertFalse("$tag: Rename clear of the name", rename!!.overlaps(name!!))
                actions().forEach { (key, bounds) ->
                    assertFalse("$tag: Rename clear of $key", rename.overlaps(bounds))
                    assertFalse("$tag: the name clear of $key", name.overlaps(bounds))
                }
            }
        }
    }

    @Test
    fun `the compact square keeps no count, and Rename is clear of the name and the actions at every font size`() {
        sweepCompact(565f, 565f)
    }

    @Test
    fun `the compact floor keeps no count, and Rename is clear of the name and the actions at every font size`() {
        sweepCompact(320f, 300f)
    }
}
