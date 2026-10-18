package com.gruning.mimir.rewrite

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.metadata.online.RaAwardKind
import com.gruning.mimir.rewrite.core.ui.MimirAchievementCardState
import com.gruning.mimir.rewrite.core.ui.MimirAchievementCopy
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import kotlinx.coroutines.flow.MutableStateFlow
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
 * The Activity achievement page's filter header on the REAL [CompanionActivityAchievementHub], at the Bottom's 537.7 x 468.3 dp at 369 dpi and font scales 1.0 and 1.3
 * ( 10-08: one top row): the header's geometry and the strict fit of the top row (every target at least 48 dp, nothing overlapping, nothing
 * off the screen, the card below it still whole), the Show list, the search pill and its clear mark, the console list, the order, the count and the empty state, the
 * filter surviving a page swipe and the hub being left and re-entered, and no request from filtering beyond the one card that is composed. Every measurement is printed
 * as `FILTER-HUB`. Pixel values are the JVM's; the pixels on a handheld are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionAchievementFilterRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val density369 = 369f / 160f
    private val hostWidthDp = 1240f / density369
    private val hostHeightDp = 1080f / density369

    private val now = 1_700_000_000_000L
    private val day = 24L * 60L * 60L * 1000L

    private fun game(
        n: Int,
        title: String,
        unlocked: Int,
        total: Int,
        console: String?,
        awarded: Long?,
        hardcore: Int = 0,
        award: RaAwardKind? = null,
    ) = CompanionActivityAchievementSnapshot(
        gameId = "g$n",
        account = "me",
        unlocked = unlocked,
        total = total,
        gameTitle = title,
        checkedAtEpochMillis = 1L,
        rowsKnown = false,
        raGameId = 100 + n,
        consoleName = console,
        lastAwardedAtMillis = awarded,
        earnedHardcore = hardcore,
        awardKind = award,
    )

    // Default order (recent activity): g1, g3, g5, g6, g2, g4. Mastered: g2. Recent (30 days): g1, g3, g5, g6.
    private val sonic = game(1, "Sonic the Hedgehog 2", 5, 40, "Genesis/Mega Drive", now - 2 * day)
    private val metroid = game(2, "Super Metroid", 40, 40, "SNES", now - 40 * day, hardcore = 40, award = RaAwardKind.MASTERED)
    private val pokemon = game(3, "Pokémon Red", 10, 151, "Game Boy", now - 5 * day)
    private val chrono = game(4, "Chrono Trigger", 0, 60, "SNES", null)
    private val eclair = game(5, "Éclair Quest", 3, 10, "SNES", now - 10 * day)
    private val streets = game(6, "Streets of Rage 2", 12, 50, "Genesis/Mega Drive", now - 20 * day)
    private val all = listOf(sonic, metroid, pokemon, chrono, eclair, streets)
    private val allTitles = all.map { it.gameTitle!! }

    private val defaultUnknown: MimirAchievementCardState = MimirAchievementCardState.Unknown(MimirAchievementCopy.NoAnswerYet)
    private val requested = ArrayList<Int>()
    private var backs = 0

    private fun mount(fontScale: Float, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density369, fontScale)) {
                MimirTheme { Box(Modifier.requiredSize(hostWidthDp.dp, hostHeightDp.dp)) { content() } }
            }
        }
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
    }

    /** The room the recent-app tile takes from the top row's end: 78 dp is what the Bottom's header slot will reserve (the row is designed for it). */
    private val reserve = 78.dp

    private fun hub(
        holder: ActivityAchievementFilterHolder,
        snapshots: List<CompanionActivityAchievementSnapshot> = all,
        backLabel: String = "Activity",
        focusGameId: String? = null,
        lastPlayed: Map<String, Long> = emptyMap(),
    ): @Composable () -> Unit = {
        CompositionLocalProvider(LocalBottomHeaderTrailingReserve provides reserve) {
            CompanionActivityAchievementHub(
                snapshots = snapshots,
                unknownState = defaultUnknown,
                navigationEnabled = true,
                onBack = { backs += 1 },
                details = MutableStateFlow<Map<Int, ActivityCardDetail>>(emptyMap()),
                requestDetail = { requested += it },
                filterHolder = holder,
                nowMillis = { now },
                backLabel = backLabel,
                focusGameId = focusGameId,
                lastPlayed = lastPlayed,
            )
        }
    }

    private fun advance(ms: Long = 300L) {
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(ms)
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    private fun click(tag: String) {
        compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
        advance()
    }

    /** Choosing a status is two taps: the Show chip, then the row in the glass panel it opens. */
    private fun clickStatus(status: ActivityAchievementStatus) {
        click(AchievementFilterShowTag)
        advance(1_000L) // the glass panel has arrived, so its rows are at their settled size
        click(achievementFilterShowItemTag(status))
    }

    private fun exists(tag: String): Boolean = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun rect(tag: String): Rect = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot

    private fun desc(tag: String): String? =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
            ?.config?.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()

    private fun textOf(tag: String): String? =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
            ?.config?.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text

    private fun texts(): List<String> = compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().let {
        val out = ArrayList<String>()
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
            node.children.forEach(::walk)
        }
        it.forEach(::walk)
        out
    }

    /** The games whose titles are on the screen, in the order the screen holds them: the composed cards, first page first. */
    private fun shownTitles(): List<String> = texts().filter { it in allTitles }.distinct()

    private fun runes(): String? = desc(AchievementHubRunesTag)

    private fun dp(px: Float) = px / density369

    private fun insideHost(r: Rect, what: String) {
        assertTrue("$what is on the screen: $r", r.left >= -0.5f && r.top >= -0.5f && r.right <= 1240f + 0.5f && r.bottom <= 1080f + 0.5f)
    }

    // ── the header is there for two or more games, and absent for one ─────────────────────────────────────────────────────────────

    @Test
    fun `with two or more games the header stands above the cards, and with one the page keeps its old layout`() {
        mount(1.0f, hub(ActivityAchievementFilterHolder()))
        listOf(
            AchievementFilterHeaderTag, AchievementFilterSearchTag, AchievementFilterShowTag, AchievementFilterConsoleTag, AchievementFilterCountTag,
        ).forEach { assertTrue("on the page: $it", exists(it)) }
        assertFalse("the Recent activity button is gone: one Show chip holds the six words", exists("achievement-filter-sort"))
        assertEquals("Show, All", desc(AchievementFilterShowTag))
        assertTrue("the chip says what it is while nothing narrows: ${texts()}", "Show: All ▾" in texts())
        assertEquals(
            "RA's terms are the Show list: Beaten, Completed and Mastered sit beside In progress",
            listOf("All", "In progress", "Beaten", "Completed", "Mastered", "Recent"),
            ActivityAchievementStatus.entries.map { it.label },
        )
        assertEquals("6 games", textOf(AchievementFilterCountTag))
        assertEquals("Game 1 of 6", runes())
    }

    @Test
    fun `with one game there is no header and the card keeps its old place`() {
        mount(1.0f, hub(ActivityAchievementFilterHolder(), listOf(sonic)))
        assertFalse(exists(AchievementFilterHeaderTag))
        assertFalse(exists(AchievementFilterSearchTag))
        assertFalse(exists(AchievementFilterCountTag))
        assertNull("one game has no runes", runes())
        val pill = rect(NowPlayingBackPillTag)
        assertEquals("the Back pill stays at 20 dp from the left", 20f, dp(pill.left), 0.6f)
        assertEquals("and 16 dp from the top", 16f, dp(pill.top), 0.6f)
        assertEquals("the card stays 80 dp from the top", 80f, dp(rect(AchievementHubCardTag).top), 0.6f)
    }

    @Test
    fun `with no game the card is the honest Unknown state and there is no header`() {
        mount(1.0f, hub(ActivityAchievementFilterHolder(), emptyList()))
        assertFalse(exists(AchievementFilterHeaderTag))
        assertTrue(exists(AchievementHubCardTag))
        assertTrue(MimirAchievementCopy.NoAnswerYet in texts())
    }

    // ── geometry ──────────────────────────────────────────────────────────────────────────────────────────────────────────────────

    private fun assertHeaderGeometry(font: Float) {
        mount(font, hub(ActivityAchievementFilterHolder()))
        val interactive = linkedMapOf(
            "back" to rect(NowPlayingBackPillTag),
            "search" to rect(AchievementFilterSearchTag),
            "show" to rect(AchievementFilterShowTag),
            "console" to rect(AchievementFilterConsoleTag),
        )
        val count = rect(AchievementFilterCountTag)
        val runes = rect(AchievementHubRunesTag)
        val card = rect(AchievementHubCardTag)
        interactive.forEach { (name, r) ->
            println("FILTER-HUB font=$font $name=${dp(r.left)},${dp(r.top)} ${dp(r.width)}x${dp(r.height)} dp")
            insideHost(r, name)
            assertTrue("$name is at least 48 dp tall: ${dp(r.height)}", dp(r.height) >= 47.9f)
            assertTrue("$name is at least 48 dp wide: ${dp(r.width)}", dp(r.width) >= 47.9f)
        }
        println("FILTER-HUB font=$font count=${dp(count.left)},${dp(count.top)} ${dp(count.width)}x${dp(count.height)} runes=${dp(runes.left)},${dp(runes.top)} ${dp(runes.width)}x${dp(runes.height)} card=${dp(card.left)},${dp(card.top)} ${dp(card.width)}x${dp(card.height)}")
        insideHost(count, "count")
        insideHost(runes, "runes")
        insideHost(card, "card")
        val names = interactive.keys.toList()
        for (i in names.indices) for (j in i + 1 until names.size) {
            assertFalse("${names[i]} and ${names[j]} do not overlap: ${interactive[names[i]]} / ${interactive[names[j]]}", interactive.getValue(names[i]).overlaps(interactive.getValue(names[j])))
        }
        assertFalse("the count and the runes do not overlap", count.overlaps(runes))
        interactive.forEach { (name, r) -> assertTrue("$name is above the card: ${r.bottom} vs ${card.top}", r.bottom <= card.top + 0.5f) }
        assertTrue("the count is above the card", count.bottom <= card.top + 0.5f)
        assertTrue("the card is still a card, at least 260 dp tall: ${dp(card.height)}", dp(card.height) >= 260f)
        assertTrue("and ends 24 dp above the bottom: ${card.bottom} vs 1080", card.bottom <= 1080f - 24f * density369 + 1f)
        // The card's own content still fits inside the shorter card.
        val progress = rect(AchievementPanelProgressTag)
        assertTrue("the progress bar is inside the card: $progress in $card", progress.top >= card.top && progress.bottom <= card.bottom)
        val loading = compose.onAllNodes(hasText("Loading badges…"), useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot
        assertTrue("the badge row is inside the card: $loading in $card", loading.bottom <= card.bottom + 0.5f)
        assertTrue("the detail area starts inside the card", rect(AchievementPanelDetailTag).top < card.bottom)
    }

    @Test
    fun `at font 1_0 the header's targets are 48 dp, clear of each other, on the screen, and the card below is whole`() {
        assertHeaderGeometry(1.0f)
    }

    @Test
    fun `at font 1_3 the header's targets are 48 dp, clear of each other, on the screen, and the card below is whole`() {
        assertHeaderGeometry(1.3f)
    }

    // ── the one top row: back, search, Show, Console ──────────────────────────────────────────────────────────────────────────────

    /** The top row's right edge in pixels: the host's width less the page's 20 dp end gutter and the header slot's reserve (the row's own end padding is that gutter, the reserve follows it). */
    private fun rowRightPx(): Float = 1240f - (20f + reserve.value) * density369

    private fun rowLeftPx(): Float = 20f * density369

    /** Every Text under [root] (the unmerged tree), with its box and its layout: whether a word was cut is the layout's to say, not the box's. */
    private fun textsUnder(root: SemanticsNode): List<Triple<String, Rect, TextLayoutResult>> {
        val out = ArrayList<Triple<String, Rect, TextLayoutResult>>()
        fun walk(node: SemanticsNode) {
            val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action
            if (action != null) {
                val results = ArrayList<TextLayoutResult>()
                action.invoke(results)
                results.firstOrNull()?.let { layout -> out += Triple(layout.layoutInput.text.text, node.boundsInRoot, layout) }
            }
            node.children.forEach(::walk)
        }
        walk(root)
        return out
    }

    /**
     * The strict fit (the row must not clip, ellipsize or wrap, and every plate is one height): at [font], with the header slot's 78 dp reserve, every element of the
     * top row (the targets, the plates and each word) has `right <= rowRight`, the four controls stand left to right in that order without touching, every word is ONE line
     * and ends inside its own box, no word is ellipsized, all the plates are the same height within 1 dp, and every target is at least 48 dp. A chosen console is called by the
     * lattice's name ([consoleDisplay]) on one line, or, when that has no room, by the lattice's glyph and the chevron with the whole name in its content description.
     */
    private fun assertTopRowFits(
        font: Float,
        holder: ActivityAchievementFilterHolder,
        backLabel: String = "Activity",
        expectSearchWord: Boolean = false,
        snapshots: List<CompanionActivityAchievementSnapshot> = all,
    ) {
        mount(font, hub(holder, snapshots = snapshots, backLabel = backLabel))
        checkTopRow(font, holder, backLabel, expectSearchWord)
    }

    private fun checkTopRow(font: Float, holder: ActivityAchievementFilterHolder, backLabel: String, expectSearchWord: Boolean, consoleDisplay: String? = null) {
        val console = holder.state.value.console
        val consoleShown = consoleDisplay ?: console
        val rowRight = rowRightPx()
        val rowLeft = rowLeftPx()
        val header = compose.onAllNodesWithTag(AchievementFilterHeaderTag, useUnmergedTree = true).fetchSemanticsNodes().first()
        val targets = linkedMapOf(
            "back" to rect(NowPlayingBackPillTag),
            "search" to rect(AchievementFilterSearchTag),
            "show" to rect(AchievementFilterShowTag),
            "console" to rect(AchievementFilterConsoleTag),
        )
        val elements = linkedMapOf<String, Rect>()
        elements += targets
        elements["back plate"] = rect(NowPlayingBackPillPlateTag)
        elements["search plate"] = rect(AchievementFilterSearchPlateTag)
        // The plates in the row only (the empty state's Clear filters chip wears the same tag, far below it).
        val chipPlates = compose.onAllNodesWithTag(AchievementFilterChipPlateTag, useUnmergedTree = true).fetchSemanticsNodes()
            .filter { it.boundsInRoot.top >= header.boundsInRoot.top - 1f && it.boundsInRoot.bottom <= header.boundsInRoot.bottom + 1f }
            .map { it.boundsInRoot }
        chipPlates.forEachIndexed { i, plate -> elements["chip plate $i"] = plate }
        val words = textsUnder(header)
        words.forEach { (text, box, _) -> elements["word '$text'"] = box }
        println("FILTER-HUB font=$font row ${dp(rowLeft)}..${dp(rowRight)} dp (header node ${dp(header.boundsInRoot.left)}..${dp(header.boundsInRoot.right)})")
        assertEquals("the row's content ends where the gutter and the reserve leave it (the header node is that content)", rowRight, header.boundsInRoot.right, 1.5f)
        assertEquals("and starts at the 20 dp gutter", rowLeft, header.boundsInRoot.left, 1.5f)
        elements.forEach { (name, r) -> println("FILTER-HUB font=$font   $name ${dp(r.left)}..${dp(r.right)} dp, ${dp(r.width)} wide") }

        elements.forEach { (name, r) ->
            assertTrue("$name stays inside the row's end: right=${dp(r.right)} dp, row ends at ${dp(rowRight)} dp (font $font)", r.right <= rowRight + 0.5f)
            assertTrue("$name starts inside the row: left=${dp(r.left)} dp", r.left >= rowLeft - 0.5f)
        }
        targets.forEach { (name, r) ->
            assertTrue("$name is at least 48 dp tall: ${dp(r.height)}", dp(r.height) >= 47.9f)
            assertTrue("$name is at least 48 dp wide: ${dp(r.width)} (font $font)", dp(r.width) >= 47.9f)
        }
        val order = targets.values.toList()
        for (i in 0 until order.size - 1) {
            assertTrue("the controls stand left to right without touching (${targets.keys.toList()[i]} -> ${targets.keys.toList()[i + 1]}): ${dp(order[i].right)} vs ${dp(order[i + 1].left)}", order[i].right <= order[i + 1].left + 0.5f)
        }

        assertTrue("the words of the row were found (back, Show, Console at least): ${words.map { it.first }}", words.size >= 3)
        val spoken = words.map { it.first }
        assertTrue("the back pill says '$backLabel': $spoken", backLabel in spoken)
        assertTrue("the Show chip says ${activityShowChipText(holder.state.value.status)}: $spoken", activityShowChipText(holder.state.value.status) in spoken)
        if (expectSearchWord) assertTrue("the search spells out 'Search': $spoken", "Search" in spoken)
        // Every plate of the row is one height (a mismatched plate does not ship): the Back pill's, the search's and each chip's, within 1 dp.
        val plateHeights = listOf(rect(NowPlayingBackPillPlateTag), rect(AchievementFilterSearchPlateTag)).map { dp(it.height) } + chipPlates.map { dp(it.height) }
        println("FILTER-HUB font=$font plate heights $plateHeights dp")
        assertTrue("every plate is one height within 1 dp: $plateHeights (font $font)", plateHeights.maxOrNull()!! - plateHeights.minOrNull()!! <= 1f)
        if (console != null) {
            // The search has shrunk to its magnifier: a 48 dp target and no word.
            assertFalse("with a console chosen the search is only its magnifier: $spoken", "Search" in spoken)
            assertEquals("the collapsed search is a 48 dp target: ${dp(targets.getValue("search").width)}", 48f, dp(targets.getValue("search").width), 0.6f)
            // The console chip, whichever form it took, names the console in full to a screen reader, and is one line.
            assertEquals("the chip's content description is the whole name", "Console, $consoleShown", desc(AchievementFilterConsoleTag))
            val chip = targets.getValue("console")
            val inChip = words.filter { (_, box, _) -> box.left >= chip.left - 0.5f && box.right <= chip.right + 0.5f }.map { it.first }
            if (exists(AchievementFilterConsoleGlyphTag)) {
                assertEquals("the name had no room on one line, so the chip is the glyph and the chevron: $inChip", listOf("▾"), inChip)
                val glyph = rect(AchievementFilterConsoleGlyphTag)
                assertTrue("the glyph stands inside the chip: $glyph in $chip", glyph.left >= chip.left && glyph.right <= chip.right && glyph.top >= chip.top && glyph.bottom <= chip.bottom)
            } else {
                assertEquals("the lattice's name, whole, on one line: $inChip", listOf("$consoleShown ▾"), inChip)
            }
        }
        words.forEach { (text, _, layout) ->
            assertEquals("'$text' is ONE line (font $font)", 1, layout.lineCount)
            assertFalse("'$text' is not ellipsized (font $font)", (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
            assertTrue("'$text' ends inside its box: ${layout.getLineRight(0)} vs ${layout.size.width} (font $font)", layout.getLineRight(0) <= layout.size.width + 0.5f)
            // (A lone glyph such as the chevron reports an overflow of its own width in this JVM's font fallback, so the width is judged by the box it is laid in, checked above, and by no ellipsis.)
            println("FILTER-HUB font=$font   word '$text' overflowWidth=${layout.didOverflowWidth} lineRight=${layout.getLineRight(0)} width=${layout.size.width}")
        }
    }

    /** Every console RetroAchievements lists (its Games menu, audited 2026-07-20: the list RetroAchievementsTest pins): the catalog the row must hold at any font. */
    private val catalogConsoles = listOf(
        "3DO Interactive Multiplayer", "Amstrad CPC", "Arcade", "Atari 2600", "Atari 7800",
        "Atari Jaguar", "Atari Lynx", "ColecoVision", "Dreamcast", "Famicom Disk System",
        "Game Boy", "Game Boy Advance", "Game Boy Color", "GameCube", "Game Gear", "Intellivision",
        "Master System", "Genesis/Mega Drive", "MSX", "Neo Geo CD", "Neo Geo Pocket", "Nintendo 64",
        "Nintendo DS", "Nintendo DSi", "NES/Famicom", "PC Engine/TurboGrafx-16",
        "PC Engine CD/TurboGrafx-CD", "PC-FX", "PlayStation", "PlayStation 2",
        "PlayStation Portable", "Pokemon Mini", "32X", "Sega CD", "Saturn", "SG-1000",
        "SNES/Super Famicom", "Vectrex", "Virtual Boy", "Wii", "WonderSwan",
    )

    /** Show on In progress, Back reading Session, and EVERY console of the catalog chosen in turn (each with a game in progress): one line, one height, nothing past the row, nothing cut. */
    private fun assertEveryCatalogConsoleFits(font: Float) {
        val data = catalogConsoles.mapIndexed { i, name -> game(20 + i, "Catalog game ${i + 1}", 3, 10, name, now - i * day) }
        val looks = ActivityAchievementIndex(data).consoleLooks
        val holder = ActivityAchievementFilterHolder(ActivityAchievementFilter(status = ActivityAchievementStatus.IN_PROGRESS))
        mount(font, hub(holder, snapshots = data, backLabel = "Session"))
        var glyphs = 0
        for (name in catalogConsoles) {
            holder.setConsole(name)
            advance()
            assertEquals("the console the data has is the one chosen", name, holder.state.value.console)
            val shownAs = looks.getValue(name).display
            val chip = rect(AchievementFilterConsoleTag)
            println("FILTER-HUB font=$font console '$name' shown as '$shownAs' chip=${dp(chip.left)}..${dp(chip.right)} dp glyph=${exists(AchievementFilterConsoleGlyphTag)}")
            checkTopRow(font, holder, "Session", false, shownAs)
            if (exists(AchievementFilterConsoleGlyphTag)) glyphs += 1
        }
        println("FILTER-HUB font=$font: of ${catalogConsoles.size} consoles, $glyphs took the glyph form")
    }

    @Test
    fun `at font 1_0 the top row fits whole with the 78 dp reserve, nothing clipped or ellipsized, the search spelled out`() {
        assertTopRowFits(1.0f, ActivityAchievementFilterHolder(), expectSearchWord = true)
    }

    @Test
    fun `at font 1_3 the top row fits whole with the 78 dp reserve, nothing clipped or ellipsized, the search spelled out`() {
        assertTopRowFits(1.3f, ActivityAchievementFilterHolder(), expectSearchWord = true)
    }

    @Test
    fun `at font 1_0 every console in the catalog chosen with Show on In progress is one line, one plate height, inside the row and uncut`() {
        assertEveryCatalogConsoleFits(1.0f)
    }

    @Test
    fun `at font 1_3 every console in the catalog chosen with Show on In progress is one line, one plate height, inside the row and uncut`() {
        assertEveryCatalogConsoleFits(1.3f)
    }

    @Test
    fun `the console chip says the lattice's name with the spaces where it fits, and draws the lattice's glyph with the chevron where it does not, naming the console in full either way`() {
        val data = all + game(9, "Gunstar Heroes", 3, 10, "Genesis/Mega Drive", now - day)
        val holder = ActivityAchievementFilterHolder(ActivityAchievementFilter(status = ActivityAchievementStatus.IN_PROGRESS, console = "Genesis/Mega Drive"))
        // Font 1.3 with Show set: "Genesis / Mega Drive ▾" has no room on one line, so it is the glyph and the chevron.
        mount(1.3f, hub(holder, snapshots = data, backLabel = "Session"))
        assertTrue("the glyph form at 1.3: ${texts()}", exists(AchievementFilterConsoleGlyphTag))
        assertEquals("the whole name is the content description", "Console, Genesis / Mega Drive", desc(AchievementFilterConsoleTag))
        assertFalse("and the raw RetroAchievements spelling is nowhere on the chip", texts().any { it.contains("Genesis/Mega") })
        // A short name keeps its words at the same font.
        holder.setConsole("Game Boy")
        advance()
        assertFalse("Game Boy has room for its name", exists(AchievementFilterConsoleGlyphTag))
        assertTrue("and says it: ${texts()}", "Game Boy ▾" in texts())
        assertEquals("Console, Game Boy", desc(AchievementFilterConsoleTag))
    }

    @Test
    fun `with a console chosen the search is only its magnifier, a 48 dp target that opens the search, and with none chosen the word is back`() {
        val holder = ActivityAchievementFilterHolder(ActivityAchievementFilter(console = "SNES"))
        mount(1.0f, hub(holder))
        val target = rect(AchievementFilterSearchTag)
        val plate = rect(AchievementFilterSearchPlateTag)
        println("FILTER-HUB collapsed search target=${dp(target.width)}x${dp(target.height)} plate=${dp(plate.width)}x${dp(plate.height)} dp")
        assertEquals("the target is 48 dp wide", 48f, dp(target.width), 0.6f)
        assertTrue("and 48 dp tall: ${dp(target.height)}", dp(target.height) >= 47.9f)
        assertTrue("the plate inside it is smaller than the target: ${dp(plate.width)}", dp(plate.width) in 30f..46f)
        assertFalse("no word on it: ${texts()}", "Search" in texts())
        assertFalse("and no dot while there is no query", exists(AchievementFilterSearchDotTag))
        assertEquals("a screen reader still hears what it is", "Search achievements", desc(AchievementFilterSearchTag))
        assertFalse(exists(AchievementFilterKeyboardTag))
        click(AchievementFilterSearchTag)
        assertTrue("a tap on the magnifier opens the search", exists(AchievementFilterKeyboardTag))

        // The console let go: the row is the one it was, the pill with its word.
        holder.setConsole(null)
        advance()
        assertTrue("the pill is back, with its word: ${texts()}", "Search" in texts())
        assertTrue("and it is wider than a magnifier: ${dp(rect(AchievementFilterSearchTag).width)}", dp(rect(AchievementFilterSearchTag).width) > 60f)
    }

    @Test
    fun `with a console chosen and a query set the magnifier carries a dot and says the query, and the query is never cut into the room it has not got`() {
        val holder = ActivityAchievementFilterHolder(ActivityAchievementFilter(console = "SNES", query = "metroid"))
        mount(1.3f, hub(holder, backLabel = "Session"))
        assertTrue("the dot says a query is set", exists(AchievementFilterSearchDotTag))
        assertEquals("Search achievements, metroid", desc(AchievementFilterSearchTag))
        assertEquals("the target is still 48 dp", 48f, dp(rect(AchievementFilterSearchTag).width), 0.6f)
        assertTrue("the dot is inside the plate", rect(AchievementFilterSearchDotTag).let { it.left >= rect(AchievementFilterSearchPlateTag).left && it.right <= rect(AchievementFilterSearchPlateTag).right })
        assertFalse("the query's text is not squeezed onto the chip: ${texts()}", "metroid" in texts())
        assertEquals("and it filters", "Super Metroid", shownTitles().single())
        // The query is where it was typed: with the console let go, the pill shows it again, whole.
        holder.setConsole(null)
        advance()
        assertTrue("the pill shows the query: ${texts()}", "metroid" in texts())
        assertFalse(exists(AchievementFilterSearchDotTag))
    }

    @Test
    fun `at font 1_3 a chosen Show word and no console selected fit whole, every word of them`() {
        val holder = ActivityAchievementFilterHolder()
        mount(1.3f, hub(holder, backLabel = "Session"))
        for (status in ActivityAchievementStatus.entries) {
            holder.setStatus(status)
            advance()
            checkTopRow(1.3f, holder, "Session", false)
        }
    }

    // ── status chips ──────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `each Show word filters, the chip names the one chosen, and its list marks it`() {
        val holder = ActivityAchievementFilterHolder()
        mount(1.0f, hub(holder))
        fun assertSelected(selected: ActivityAchievementStatus) {
            assertEquals("Show, ${selected.label}", desc(AchievementFilterShowTag))
            assertTrue("the chip reads ${activityShowChipText(selected)}: ${texts()}", activityShowChipText(selected) in texts())
        }
        assertSelected(ActivityAchievementStatus.ALL)
        assertEquals("Sonic the Hedgehog 2", shownTitles().first())

        // The list: six rows in RA's order, the chosen one marked, each a 48 dp target.
        click(AchievementFilterShowTag)
        advance(1_000L)
        assertTrue(exists(AchievementFilterShowListTag))
        ActivityAchievementStatus.entries.forEach {
            assertEquals(activityFilterChipDescription(it.label, it == ActivityAchievementStatus.ALL), desc(achievementFilterShowItemTag(it)))
            val row = rect(achievementFilterShowItemTag(it))
            assertTrue("a Show row is at least 48 dp tall: ${dp(row.height)}", dp(row.height) >= 47.9f)
        }
        insideHost(rect(AchievementFilterShowListTag), "the Show list")
        click(AchievementFilterShowTag)
        assertFalse("the chip closes its own list", exists(AchievementFilterShowListTag))

        clickStatus(ActivityAchievementStatus.IN_PROGRESS)
        assertSelected(ActivityAchievementStatus.IN_PROGRESS)
        assertFalse("a pick closes the list", exists(AchievementFilterShowListTag))
        assertEquals("5 of 6 games", textOf(AchievementFilterCountTag))
        assertEquals("Game 1 of 5", runes())
        assertEquals(ActivityAchievementStatus.IN_PROGRESS, holder.state.value.status)
        click(AchievementFilterShowTag)
        advance(1_000L)
        assertEquals("the list marks the chosen row", "In progress, selected", desc(achievementFilterShowItemTag(ActivityAchievementStatus.IN_PROGRESS)))
        assertEquals("All", desc(achievementFilterShowItemTag(ActivityAchievementStatus.ALL)))
        click(AchievementFilterShowTag)

        clickStatus(ActivityAchievementStatus.MASTERED)
        assertSelected(ActivityAchievementStatus.MASTERED)
        assertEquals("1 of 6 games", textOf(AchievementFilterCountTag))
        assertEquals("Super Metroid", shownTitles().single())
        assertNull("one result has no runes", runes())

        clickStatus(ActivityAchievementStatus.RECENT)
        assertSelected(ActivityAchievementStatus.RECENT)
        assertEquals("4 of 6 games", textOf(AchievementFilterCountTag))
        assertEquals("Game 1 of 4", runes())

        clickStatus(ActivityAchievementStatus.ALL)
        assertSelected(ActivityAchievementStatus.ALL)
        assertEquals("6 games", textOf(AchievementFilterCountTag))
    }

    // ── search ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `Completed, Beaten and Mastered split the real page by RA's terms`() {
        val softcoreFinish = game(7, "Softcore Finish", 20, 20, "SNES", now - 3 * day, hardcore = 0, award = RaAwardKind.COMPLETED)
        val beatenQuest = game(8, "Beaten Quest", 9, 20, "SNES", now - 4 * day, hardcore = 9, award = RaAwardKind.BEATEN_HARDCORE)
        val holder = ActivityAchievementFilterHolder()
        mount(1.0f, hub(holder, all + listOf(softcoreFinish, beatenQuest)))
        assertEquals("8 games", textOf(AchievementFilterCountTag))

        clickStatus(ActivityAchievementStatus.COMPLETED)
        assertEquals("every achievement earned, none required in hardcore", "1 of 8 games", textOf(AchievementFilterCountTag))
        assertTrue("Softcore Finish" in texts())
        assertEquals("Show, Completed", desc(AchievementFilterShowTag))

        clickStatus(ActivityAchievementStatus.MASTERED)
        assertEquals("only the game finished in hardcore", "1 of 8 games", textOf(AchievementFilterCountTag))
        assertEquals("Super Metroid", shownTitles().single())

        clickStatus(ActivityAchievementStatus.BEATEN)
        assertEquals("RA's beaten award, on a game not finished", "1 of 8 games", textOf(AchievementFilterCountTag))
        assertTrue("Beaten Quest" in texts())
        assertEquals(ActivityAchievementStatus.BEATEN, holder.state.value.status)
    }

    @Test
    fun `the search pill announces its query, filters as it changes, and its clear mark empties it`() {
        val holder = ActivityAchievementFilterHolder()
        mount(1.0f, hub(holder))
        assertEquals("Search achievements", desc(AchievementFilterSearchTag))
        assertFalse("no clear mark while there is no query", exists(AchievementFilterSearchClearTag))

        holder.setQuery("pokemon")
        advance()
        assertEquals("Search achievements, pokemon", desc(AchievementFilterSearchTag))
        assertEquals("1 of 6 games", textOf(AchievementFilterCountTag))
        assertEquals("Pokémon Red", shownTitles().single())
        assertTrue("the query is on the pill", "pokemon" in texts())
        assertEquals("Clear search", desc(AchievementFilterSearchClearTag))
        val clear = rect(AchievementFilterSearchClearTag)
        assertTrue("the clear mark is a 48 dp target: ${dp(clear.width)}x${dp(clear.height)}", dp(clear.width) >= 47.9f && dp(clear.height) >= 47.9f)

        holder.setQuery("ÉCLAIR")
        advance()
        assertEquals("accents and case are ignored", "Éclair Quest", shownTitles().single())

        click(AchievementFilterSearchClearTag)
        assertEquals("", holder.state.value.query)
        assertEquals("6 games", textOf(AchievementFilterCountTag))
        assertFalse(exists(AchievementFilterSearchClearTag))
        assertEquals("Search achievements", desc(AchievementFilterSearchTag))
    }

    @Test
    fun `tapping the search pill opens the Mimir keyboard over the cards and leaves the pill and its query visible above it`() {
        val holder = ActivityAchievementFilterHolder()
        mount(1.0f, hub(holder))
        assertFalse(exists(AchievementFilterKeyboardTag))
        click(AchievementFilterSearchTag)
        assertTrue("the keyboard is up", exists(AchievementFilterKeyboardTag))
        val keyboard = rect(AchievementFilterKeyboardTag)
        val pill = rect(AchievementFilterSearchTag)
        println("FILTER-HUB keyboard=${dp(keyboard.left)},${dp(keyboard.top)} ${dp(keyboard.width)}x${dp(keyboard.height)} dp pill bottom=${dp(pill.bottom)}")
        insideHost(keyboard, "keyboard")
        assertTrue("the keyboard is anchored to the bottom edge: ${keyboard.bottom}", keyboard.bottom >= 1080f - 1f)
        assertTrue("the search pill stays above the keyboard: ${pill.bottom} vs ${keyboard.top}", pill.bottom <= keyboard.top + 0.5f)
    }

    // ── console ───────────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the console chip opens the list of consoles in the data with their counts, and a pick filters`() {
        val holder = ActivityAchievementFilterHolder()
        mount(1.0f, hub(holder))
        assertEquals("Console, all consoles", desc(AchievementFilterConsoleTag))
        assertFalse(exists(AchievementFilterConsoleListTag))

        click(AchievementFilterConsoleTag)
        advance(1_000L) // the glass panel has arrived (it lifts from 0.96 to 1 on the house clock), so its rows measure at their settled size
        assertTrue(exists(AchievementFilterConsoleListTag))
        assertEquals("All consoles, selected", desc(achievementFilterConsoleItemTag(null)))
        // The picker calls each console by the lattice's name (the filter still holds RetroAchievements' own, which is what the rows are tagged by).
        assertEquals("Game Boy, 1 game", desc(achievementFilterConsoleItemTag("Game Boy")))
        assertEquals("Genesis / Mega Drive, 2 games", desc(achievementFilterConsoleItemTag("Genesis/Mega Drive")))
        assertEquals("Super Nintendo, 3 games", desc(achievementFilterConsoleItemTag("SNES")))
        listOf("All consoles", "Game Boy (1)", "Genesis / Mega Drive (2)", "Super Nintendo (3)").forEach { assertTrue("listed: $it in ${texts()}", it in texts()) }
        listOf(null, "Game Boy", "Genesis/Mega Drive", "SNES").forEach {
            val item = rect(achievementFilterConsoleItemTag(it))
            assertTrue("a list row is at least 48 dp tall: ${dp(item.height)}", dp(item.height) >= 47.9f)
        }
        val list = rect(AchievementFilterConsoleListTag)
        insideHost(list, "the console list")
        // The picker is a glass panel over the whole (blurred) page, not a drop-down under the chip: the scrim is under it, and it is on the screen.
        assertTrue("the scrim stands behind the panel", exists(AchievementFilterConsoleScrimTag))

        click(achievementFilterConsoleItemTag("SNES"))
        assertFalse("a pick closes the list", exists(AchievementFilterConsoleListTag))
        assertEquals("Console, Super Nintendo", desc(AchievementFilterConsoleTag))
        assertEquals("3 of 6 games", textOf(AchievementFilterCountTag))
        assertEquals("SNES", holder.state.value.console)
        assertEquals("Game 1 of 3", runes())

        click(AchievementFilterConsoleTag)
        assertEquals("Super Nintendo, 3 games, selected", desc(achievementFilterConsoleItemTag("SNES")))
        click(achievementFilterConsoleItemTag(null))
        assertEquals("6 games", textOf(AchievementFilterCountTag))
        assertEquals("Console, all consoles", desc(AchievementFilterConsoleTag))

        click(AchievementFilterConsoleTag)
        assertTrue(exists(AchievementFilterConsoleListTag))
        click(AchievementFilterConsoleTag)
        assertFalse("the chip closes its own list", exists(AchievementFilterConsoleListTag))
    }

    // ── order ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the page has no sort control, lists the newest activity first, and stands the game just played before a stale zero`() {
        val holder = ActivityAchievementFilterHolder()
        mount(1.0f, hub(holder))
        assertFalse("there is no sort button", exists("achievement-filter-sort"))
        assertEquals("the newest unlock first", "Sonic the Hedgehog 2", shownTitles().first())
        assertEquals("Game 1 of 6", runes())
    }

    @Test
    fun `a game played a minute ago, with nothing earned, stands first under All, ahead of a game unlocked two days ago`() {
        val holder = ActivityAchievementFilterHolder()
        mount(1.0f, hub(holder, lastPlayed = mapOf("g4" to now - 60_000L)))
        assertEquals("Chrono Trigger (0 of 60, played a minute ago) is card 1", "Chrono Trigger", shownTitles().first())
        assertEquals("Game 1 of 6", runes())
    }

    @Test
    fun `the hub opens on the game it is told to, and a game that is not in the list opens at the first card`() {
        val holder = ActivityAchievementFilterHolder()
        // The default order is g1, g3, g5, g6, g2, g4: Super Metroid (g2) is the fifth.
        mount(1.0f, hub(holder, focusGameId = "g2"))
        assertEquals("Game 5 of 6", runes())
        assertEquals("Super Metroid", shownTitles().first())
    }

    @Test
    fun `the hub opens at its first card when the game it is told to is not in the list`() {
        mount(1.0f, hub(ActivityAchievementFilterHolder(), focusGameId = "gone"))
        assertEquals("Game 1 of 6", runes())
        assertEquals("Sonic the Hedgehog 2", shownTitles().first())
    }

    @Test
    fun `entered from Now Playing the hub clears what an earlier visit left in the filter, so the running game is listed and opens`() {
        // Mastered, a console and a query left by an earlier visit: none of them lists Pokémon Red (g3), the running game.
        val left = ActivityAchievementFilter(query = "metroid", status = ActivityAchievementStatus.MASTERED, console = "SNES")
        val holder = ActivityAchievementFilterHolder(left)
        val composedFilters = mutableListOf<ActivityAchievementFilter>()
        val content = hub(holder, focusGameId = "g3")
        mount(1.0f) {
            content()
            val heldFilter by holder.state.collectAsState()
            composedFilters += heldFilter
        }
        assertEquals("composition did not mutate the held filter", left, composedFilters.first())
        assertEquals("the filter was cleared on the way in", ActivityAchievementFilter(), holder.state.value)
        // The default order is g1, g3, g5, g6, g2, g4: Pokémon Red is the second card, and the page stands on it.
        assertEquals("Game 2 of 6", runes())
        assertEquals("Pokémon Red", shownTitles().first())
        assertEquals("6 games", textOf(AchievementFilterCountTag))
        assertEquals("Show, All", desc(AchievementFilterShowTag))
    }

    @Test
    fun `a game that is not in the list leaves the filter alone`() {
        val holder = ActivityAchievementFilterHolder(ActivityAchievementFilter(status = ActivityAchievementStatus.MASTERED))
        mount(1.0f, hub(holder, focusGameId = "gone"))
        assertEquals("nothing to open on, nothing cleared", ActivityAchievementStatus.MASTERED, holder.state.value.status)
        assertEquals("Super Metroid", shownTitles().single())
    }

    @Test
    fun `a filter set after the hub was entered on a game is the player's, and stays`() {
        val holder = ActivityAchievementFilterHolder(ActivityAchievementFilter(console = "SNES"))
        mount(1.0f, hub(holder, focusGameId = "g1"))
        assertNull("cleared on entering", holder.state.value.console)
        holder.setStatus(ActivityAchievementStatus.IN_PROGRESS)
        advance()
        assertEquals("a later change is not undone by the entry's clear", ActivityAchievementStatus.IN_PROGRESS, holder.state.value.status)
        holder.setConsole("SNES")
        advance()
        assertEquals("nor the console", "SNES", holder.state.value.console)
    }

    // ── the empty state ───────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a filter with no result says so and offers Clear filters`() {
        val holder = ActivityAchievementFilterHolder()
        mount(1.0f, hub(holder))
        holder.setQuery("zzzz nothing")
        advance()
        assertEquals("0 of 6 games", textOf(AchievementFilterCountTag))
        assertTrue(exists(AchievementFilterEmptyTag))
        assertTrue("No games match" in texts())
        assertFalse("no card while there is nothing to show", exists(AchievementHubListTag))
        assertNull(runes())
        val clear = rect(AchievementFilterClearTag)
        assertTrue("Clear filters is a 48 dp target: ${dp(clear.width)}x${dp(clear.height)}", dp(clear.width) >= 47.9f && dp(clear.height) >= 47.9f)
        insideHost(clear, "Clear filters")
        assertEquals("Clear filters", desc(AchievementFilterClearTag))
        // The header stays, so the way out is also in the chips.
        assertTrue(exists(AchievementFilterSearchTag))

        click(AchievementFilterClearTag)
        assertFalse(exists(AchievementFilterEmptyTag))
        assertTrue(exists(AchievementHubListTag))
        assertEquals("6 games", textOf(AchievementFilterCountTag))
        assertEquals("", holder.state.value.query)
        assertEquals(ActivityAchievementStatus.ALL, holder.state.value.status)
        assertEquals("Sonic the Hedgehog 2", shownTitles().first())
    }

    // ── the state survives ────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the filter survives a page swipe, and a different filter opens at its first game`() {
        val holder = ActivityAchievementFilterHolder()
        mount(1.0f, hub(holder))
        clickStatus(ActivityAchievementStatus.IN_PROGRESS)
        assertEquals("Game 1 of 5", runes())
        compose.onNodeWithTag(AchievementHubListTag, useUnmergedTree = true).performScrollToIndex(2)
        advance(500L)
        assertEquals("turned to the third of the five", "Game 3 of 5", runes())
        assertEquals("the swipe left the filter alone", "Show, In progress", desc(AchievementFilterShowTag))
        assertEquals("5 of 6 games", textOf(AchievementFilterCountTag))
        assertEquals(ActivityAchievementStatus.IN_PROGRESS, holder.state.value.status)

        clickStatus(ActivityAchievementStatus.RECENT)
        advance(500L)
        assertEquals("a new filter is a new list, at its first game", "Game 1 of 4", runes())
    }

    @Test
    fun `the filter survives the hub being left and entered again, and a cold start begins at the default`() {
        val holder = ActivityAchievementFilterHolder()
        var present by mutableStateOf(true)
        mount(1.0f) { if (present) hub(holder)() }
        clickStatus(ActivityAchievementStatus.MASTERED)
        holder.setQuery("metroid")
        advance()
        assertEquals("1 of 6 games", textOf(AchievementFilterCountTag))

        present = false
        advance(500L)
        assertFalse("the page is gone", exists(AchievementFilterHeaderTag))
        present = true
        advance(500L)
        assertEquals("Show, Mastered", desc(AchievementFilterShowTag))
        assertEquals("Search achievements, metroid", desc(AchievementFilterSearchTag))
        assertEquals("1 of 6 games", textOf(AchievementFilterCountTag))
        assertEquals("Super Metroid", shownTitles().single())

        // A cold start is a new process, and so a new holder.
        assertEquals(ActivityAchievementFilter(), ActivityAchievementFilterHolder().state.value)
    }

    @Test
    fun `a list that shrinks to one game takes the header away, and a filter held for the old list is ignored`() {
        val holder = ActivityAchievementFilterHolder(ActivityAchievementFilter(query = "zzzz"))
        var live by mutableStateOf(all)
        mount(1.0f) { hub(holder, live)() }
        assertEquals("0 of 6 games", textOf(AchievementFilterCountTag))
        live = listOf(sonic)
        advance(500L)
        assertFalse(exists(AchievementFilterHeaderTag))
        assertTrue("the one game is shown, not hidden by a filter nobody can see", "Sonic the Hedgehog 2" in texts())
        assertTrue(exists(AchievementHubCardTag))
    }

    // ── no request from filtering ─────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `filtering asks for a game's detail only while its card is composed, so one result asks for at most that one game`() {
        val holder = ActivityAchievementFilterHolder()
        mount(1.0f, hub(holder))
        // A card asks after it has stayed ACTIVITY_CARD_ASK_DEBOUNCE_MS, so each step waits that long before it counts what was asked.
        advance(ACTIVITY_CARD_ASK_DEBOUNCE_MS + 50L)
        assertEquals("only the card that is composed asked: $requested", listOf(101), requested.distinct())
        assertFalse("a game on another page was never asked for", requested.contains(102))

        requested.clear()
        holder.setQuery("chrono")
        advance()
        assertEquals("1 of 6 games", textOf(AchievementFilterCountTag))
        advance(ACTIVITY_CARD_ASK_DEBOUNCE_MS + 50L)
        assertTrue("one result asks for at most that game: $requested", requested.all { it == 104 })

        requested.clear()
        holder.setQuery("")
        holder.setStatus(ActivityAchievementStatus.MASTERED)
        advance()
        assertEquals("Super Metroid", shownTitles().single())
        advance(ACTIVITY_CARD_ASK_DEBOUNCE_MS + 50L)
        assertTrue("again only the composed game: $requested", requested.all { it == 102 })

        requested.clear()
        holder.setQuery("zzzz nothing")
        advance()
        advance(ACTIVITY_CARD_ASK_DEBOUNCE_MS + 50L)
        assertTrue("an empty result composes no card, so it asks for nothing: $requested", requested.isEmpty())
    }

    // ── more pages than runes ─────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `many games show the position as a number beside the count rather than a row of runes that runs off the screen`() {
        val many = (1..47).map { game(it, "Game %02d".format(it), 1, 5, "SNES", now - it * day / 2) }
        mount(1.0f, hub(ActivityAchievementFilterHolder(), many))
        assertEquals("Game 1 of 47", runes())
        assertEquals("Game 1 of 47", textOf(AchievementHubRunesTag))
        val position = rect(AchievementHubRunesTag)
        val count = rect(AchievementFilterCountTag)
        insideHost(position, "the position")
        assertFalse("the position and the count do not overlap", position.overlaps(count))
        assertEquals("47 games", textOf(AchievementFilterCountTag))
    }
}
