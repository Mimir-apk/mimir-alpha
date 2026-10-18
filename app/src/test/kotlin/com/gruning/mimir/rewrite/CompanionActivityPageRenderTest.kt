package com.gruning.mimir.rewrite

import android.app.Application
import android.media.session.MediaSession
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.runtime.snapshots.Snapshot
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.model.library.launchesPerLocalDay
import com.gruning.mimir.rewrite.core.ui.MimirCompactHeaderTag
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import com.gruning.mimir.rewrite.core.ui.drawMimirMetalPlate
import java.io.File
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Activity page (slice L2) rendered at the Thor Bottom Presentation's measured size: the full 1240 x 1080 px at 369 dpi, which is 537.7 x 468.3 dp.
 * Four states, each at font scale 1.0 and 1.3:
 *
 *  (a) full data with music playing: Last played, This week, Most launched and the media tile, and the achievements strip below the fold;
 *  (b) no music: the lower-left tile is Been a while;
 *  (c) no launch history at all: Last played and the lower-left tile are both Been a while ("From your shelf"), the bars are all zero and say so;
 *  (d) a game is live: the top-left tile is Now playing.
 *
 * Every state asserts: no text is cut (nothing exceeds its lines or its tile; only a game's or track's own title may ellipsise), every touch target is
 * at least 48 dp each way, the tiles do not overlap and sit inside the 502 dp body, and, at font scale 1.0, the four tiles fit above the 36 dp dock
 * reserve (at 1.3 a tile grows to fit its text and the page scrolls). Each prints its measured zones as `L2-ZONES`. Pixels are the JVM's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionActivityPageRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val density369 = 369f / 160f
    private val hostWidthDp = 1240f / density369
    private val hostHeightDp = 1080f / density369
    private var density = 0f
    private var measureChrome: (String) -> Int = { 0 }

    private val zone = ZoneId.of("UTC")
    private val now = Instant.parse("2026-10-02T12:00:00Z").toEpochMilli()
    private val day = 24L * 60L * 60L * 1000L

    private fun game(
        id: String,
        title: String,
        plays: Int,
        lastDaysAgo: Long?,
        firstDaysAgo: Long? = lastDaysAgo,
        settled: Boolean = true,
    ) = GameRow(
        gameId = GameId(id),
        title = title,
        platformId = PlatformId("genesis"),
        memberCount = 1,
        duplicateKind = null,
        playCount = plays,
        lastPlayedMillis = lastDaysAgo?.let { now - it * day },
        firstPlayedMillis = firstDaysAgo?.let { now - it * day },
        artResolutionSettled = settled,
    )

    private val launched = listOf(
        game("g1", "Sonic the Hedgehog 2", plays = 14, lastDaysAgo = 1, firstDaysAgo = 200),
        game("g2", "Streets of Rage 2", plays = 22, lastDaysAgo = 3, firstDaysAgo = 150),
        game("g3", "Phantasy Star IV", plays = 9, lastDaysAgo = 12, firstDaysAgo = 90),
        game("g4", "Shining Force", plays = 5, lastDaysAgo = 40, firstDaysAgo = 41),
        game("g5", "Gunstar Heroes", plays = 3, lastDaysAgo = 60, firstDaysAgo = null),
    )

    private val weekCounts = launchesPerLocalDay(
        listOf(now - 6 * day, now - 5 * day, now - 5 * day + 1000, now - 3 * day, now - day, now - day + 2000, now - 3600_000L, now - 7200_000L),
        now,
        zone,
    )

    private fun data(
        launched: List<GameRow> = this.launched,
        shelfPool: List<GameRow> = emptyList(),
        week: List<com.gruning.mimir.rewrite.core.model.library.DailyLaunchCount>? = weekCounts,
        live: GameRow? = null,
    ) = ActivityBentoData(
        gameCount = 1_234,
        launched = launched,
        shelfPool = shelfPool,
        week = week,
        liveGameId = live?.gameId,
        liveTitle = live?.title,
        liveRow = live,
        nowMillis = now,
        zone = zone,
    )

    private fun track(): CompanionMediaTrack {
        val token = MediaSession(ApplicationProvider.getApplicationContext<Application>(), "activity-render").sessionToken
        return CompanionMediaTrack(
            title = "Dreams of the Hedgehog",
            artist = "Anonymous",
            appLabel = "Player",
            packageName = "example.player",
            canPrevious = true,
            canPlayPause = true,
            canNext = true,
            sessionIdentity = token,
            trackIdentity = "track-1",
            playbackState = CompanionMediaPlaybackState.PLAYING,
            positionMs = 61_000L,
            durationMs = 240_000L,
            canSeek = true,
        )
    }

    private val snapshots = listOf(
        CompanionActivityAchievementSnapshot(gameId = "g1", account = "me", unlocked = 12, total = 40, gameTitle = "Sonic the Hedgehog 2"),
        CompanionActivityAchievementSnapshot(gameId = "g2", account = "me", unlocked = 3, total = 20, gameTitle = "Streets of Rage 2"),
    )

    private fun mount(fontScale: Float, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density369, fontScale)) {
                MimirTheme {
                    density = LocalDensity.current.density
                    val measurer = rememberTextMeasurer()
                    measureChrome = { text -> measurer.measure(AnnotatedString(text.uppercase()), MimirTypeScale.RunicEyebrow, maxLines = 1).size.width }
                    Box(Modifier.requiredSize(hostWidthDp.dp, hostHeightDp.dp)) { content() }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
    }

    private fun surface(
        bento: ActivityBentoData,
        media: CompanionMediaUiState,
        snapshots: List<CompanionActivityAchievementSnapshot> = emptyList(),
    ): @Composable () -> Unit = {
        CompanionWorkspaceSurface(
            page = CompanionWorkspacePage.ACTIVITY,
            navigationEnabled = true,
            recentApp = null,
            game = null,
            media = media,
            onRecentApp = {},
            onReturnToGame = null,
            onMediaAccess = {},
            onMediaAction = { _, _, _ -> },
            achievementSnapshots = snapshots,
            bento = bento,
        )
    }

    private fun page(
        bento: ActivityBentoData,
        media: (@Composable (Modifier) -> Unit)? = null,
        snapshots: List<CompanionActivityAchievementSnapshot> = emptyList(),
        achievementsAccessEnabled: Boolean = false,
        onOpenAchievements: () -> Unit = {},
        onOpenGame: (GameRow) -> Unit = {},
        onOpenLiveGame: (() -> Unit)? = {},
    ): @Composable () -> Unit = {
        ActivityPage(
            data = bento,
            navigationEnabled = true,
            mediaTile = media,
            achievementSnapshots = snapshots,
            achievementsAccessEnabled = achievementsAccessEnabled,
            onOpenAchievements = onOpenAchievements,
            onOpenGame = onOpenGame,
            onOpenLiveGame = onOpenLiveGame,
            trailingReserve = 0.dp,
        )
    }

    // ── measuring ──────────────────────────────────────────────────────────────────────────────────────────────────────────────────

    private class Box4(val x: Float, val y: Float, val w: Float, val h: Float) {
        val right get() = x + w
        val bottom get() = y + h
        override fun toString() = "%.1fx%.1f@(%.1f,%.1f)".format(w, h, x, y)
    }

    private fun SemanticsNode.description(): String? =
        if (SemanticsProperties.ContentDescription in config) config[SemanticsProperties.ContentDescription].joinToString() else null

    private fun SemanticsNode.dp(): Box4 {
        // The node's own size and unclipped position: `boundsInRoot` is clipped by the scroll viewport, which would hide the part of a tile below the fold.
        val p = positionInRoot
        return Box4(p.x / density, p.y / density, size.width / density, size.height / density)
    }

    private fun bounds(tag: String): Box4 = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().dp()

    private fun hasTag(tag: String): Boolean =
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private class TextNode(val text: String, val box: Box4, val layout: TextLayoutResult)

    private fun textNodes(): List<TextNode> =
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .mapNotNull { node ->
                val layouts = mutableListOf<TextLayoutResult>()
                val action = node.config[SemanticsActions.GetTextLayoutResult].action ?: return@mapNotNull null
                if (!action(layouts) || layouts.isEmpty()) return@mapNotNull null
                TextNode(layouts.first().layoutInput.text.text, node.dp(), layouts.first())
            }

    /** Cut to width: a line is ellipsised, or runs past the node's own width. (`didOverflowWidth` compares against the constraint, not the text, so it is not the test.) */
    private fun TextLayoutResult.isCut(): Boolean =
        (0 until lineCount).any { isLineEllipsized(it) || getLineRight(it) > size.width + 1f }

    /** A paused clock needs the write announced and two frames: one to deliver it, one to recompose. */
    private fun settle() {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
    }

    /** The eyebrows are chrome text (caps by intent), which the semantics tree carries as a description, so they are found and measured by that. */
    private fun chromeNodes(text: String): List<SemanticsNode> =
        compose.onAllNodesWithContentDescription(text, ignoreCase = true, useUnmergedTree = true).fetchSemanticsNodes()
            .filter { SemanticsActions.OnClick !in it.config }

    private fun overlaps(a: Box4, b: Box4, tol: Float = 0.5f) =
        a.x + a.w - tol > b.x && b.x + b.w - tol > a.x && a.y + a.h - tol > b.y && b.y + b.h - tol > a.y

    private fun tileTags(live: Boolean, hasStrip: Boolean): List<String> = buildList {
        add(if (live) ActivityTileNowPlayingTag else ActivityTileLastPlayedTag)
        add(ActivityTileLowerLeftTag)
        add(ActivityTileWeekTag)
        add(ActivityTileRankedTag)
        if (hasStrip) add(ActivityTileAchievementsTag)
    }

    /** The whole page's rules, for any state. [dynamicTitles] are the texts that may ellipsise (a game's or track's own name). */
    private fun assertPage(
        scenario: String,
        fontScale: Float,
        live: Boolean = false,
        hasStrip: Boolean = false,
        dynamicTitles: Set<String> = emptySet(),
        eyebrows: List<String> = emptyList(),
    ) {
        val header = compose.onNodeWithTag(MimirCompactHeaderTag, useUnmergedTree = true).fetchSemanticsNode().dp()
        val tags = tileTags(live, hasStrip)
        val tiles = tags.associateWith { bounds(it) }
        println(
            "L2-ZONES scenario=$scenario fontScale=$fontScale host=${"%.1f".format(hostWidthDp)}x${"%.1f".format(hostHeightDp)}dp header=${header} " +
                tiles.entries.joinToString(" ") { (tag, b) -> "${tag.removePrefix("activity-tile-")}=$b" },
        )

        // Tiles sit inside the 502 dp body (18 dp insets) and do not overlap one another.
        tiles.forEach { (tag, b) ->
            assertTrue("$tag ($b) starts at or after the 18 dp inset", b.x >= ActivityPageInsetDp - 0.5f)
            assertTrue("$tag ($b) ends inside the right inset", b.right <= hostWidthDp - ActivityPageInsetDp + 0.5f)
            assertTrue("$tag ($b) is below the header ($header)", b.y >= header.bottom - 0.5f)
        }
        val tagList = tiles.keys.toList()
        for (i in tagList.indices) for (j in i + 1 until tagList.size) {
            assertFalse("${tagList[i]} ${tiles[tagList[i]]} must not overlap ${tagList[j]} ${tiles[tagList[j]]}", overlaps(tiles.getValue(tagList[i]), tiles.getValue(tagList[j])))
        }

        // At the default font scale the four tiles fit above the dock reserve; at a larger one they grow and the page scrolls.
        val fold = hostHeightDp - ActivityDockReserveDp
        val fourTiles = tags.filter { it != ActivityTileAchievementsTag }.map { tiles.getValue(it) }
        val bottom = fourTiles.maxOf { it.bottom }
        if (fontScale == 1.0f) {
            assertTrue("the four tiles end at ${"%.1f".format(bottom)} dp, above the $fold dp fold", bottom <= fold + 0.6f)
            if (hasStrip) assertTrue("the achievements strip is below the fold", tiles.getValue(ActivityTileAchievementsTag).y >= fold - 0.6f)
        }

        // Nothing cut: text exceeds neither its lines nor its tile.
        // Text inside an art seat (the book draws its game's or track's title on its leather) is art, not copy.
        val seats = listOf(ActivityGameFaceTag, ActivityMediaArtTag).flatMap { tag ->
            compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, tag), useUnmergedTree = true).fetchSemanticsNodes().map { it.dp() }
        }
        val texts = textNodes().filter { t ->
            val cx = t.box.x + t.box.w / 2f
            val cy = t.box.y + t.box.h / 2f
            seats.none { cx >= it.x && cx <= it.right && cy >= it.y && cy <= it.bottom }
        }
        assertTrue("some text was measured", texts.isNotEmpty())
        texts.forEach { t ->
            val why = "'${t.text}' ${t.box} at fontScale=$fontScale"
            // A game's or track's own name may ellipsise; no other text may be cut by lines, height or width.
            if (t.text !in dynamicTitles) {
                assertFalse("$why exceeds its height or its lines", t.layout.didOverflowHeight || t.layout.multiParagraph.didExceedMaxLines)
                assertFalse("$why is cut to its width", t.layout.isCut())
            }
            assertTrue("$why sits inside the host width", t.box.x >= -0.5f && t.box.right <= hostWidthDp + 0.5f)
            val cx = t.box.x + t.box.w / 2f
            val cy = t.box.y + t.box.h / 2f
            tiles.entries.firstOrNull { (_, b) -> cx >= b.x && cx <= b.right && cy >= b.y && cy <= b.bottom }?.let { (tag, b) ->
                assertTrue("$why must sit inside $tag $b", t.box.x >= b.x - 0.75f && t.box.right <= b.right + 0.75f && t.box.y >= b.y - 0.75f && t.box.bottom <= b.bottom + 0.75f)
            }
        }

        // The eyebrows are present, once per tile that wears one, and each is as wide as its own caps text: none is ellipsised.
        eyebrows.groupingBy { it }.eachCount().forEach { (text, expected) ->
            val nodes = chromeNodes(text)
            assertEquals("eyebrow count for $text at fontScale=$fontScale", expected, nodes.size)
            nodes.forEach { node ->
                assertTrue("eyebrow $text (${node.size.width}px) fits its caps text (${measureChrome(text)}px) at fontScale=$fontScale", node.size.width + 1 >= measureChrome(text))
            }
        }

        // Every control is a 48 dp target each way.
        val targets = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertTrue("controls were found", targets.size >= 3)
        targets.forEach { node ->
            val b = node.dp()
            val label = node.description() ?: node.id.toString()
            assertTrue("'$label' $b is at least 48 dp wide at fontScale=$fontScale", b.w >= 47.5f)
            assertTrue("'$label' $b is at least 48 dp tall at fontScale=$fontScale", b.h >= 47.5f)
        }

        // The bars are one semantics node.
        val bars = compose.onNodeWithTag(ActivityWeekBarsTag).fetchSemanticsNode()
        val spoken = bars.config[SemanticsProperties.ContentDescription].joinToString()
        assertTrue("the bars read as launches: $spoken", spoken.startsWith("Launches this week:"))
        assertTrue("the bars never say play time: $spoken", "playtime" !in spoken.lowercase())
        assertEquals("the bars expose no children to a screen reader", 0, bars.children.size)
    }

    private fun titleCount(title: String): Int = textNodes().count { it.text == title }

    /** The ranked tile's rows, in order, read back from their descriptions. */
    private fun rankedRowTitles(): List<String> {
        val rows = mutableListOf<String>()
        fun walk(n: SemanticsNode) {
            n.description()?.let { d ->
                if (d.startsWith("Open ") && d.endsWith(" launch card")) rows += d.removePrefix("Open ").removeSuffix(" launch card")
            }
            n.children.forEach(::walk)
        }
        walk(compose.onNodeWithTag(ActivityTileRankedTag).fetchSemanticsNode())
        return rows
    }

    // ── (a) full data, music playing ───────────────────────────────────────────────────────────────────────────────────────────────

    private fun mountA(fontScale: Float) =
        mount(fontScale, surface(data(), CompanionMediaUiState.Active(track()), snapshots))

    private val titlesA = launched.map { it.title }.toSet() + "Dreams of the Hedgehog"
    private val eyebrowsA = listOf("Last played", "This week", "Most launched", "Playing", "Achievements")
    private val eyebrowsB = listOf("Last played", "This week", "Most launched", "Been a while")
    private val eyebrowsC = listOf("From your shelf", "From your shelf", "This week", "Most launched")
    private val eyebrowsD = listOf("Now playing", "This week", "Most launched", "Been a while")

    @Test
    fun `a - full data with music at font scale 1 point 0`() {
        mountA(1.0f)
        assertPage("a-full-music", 1.0f, hasStrip = true, dynamicTitles = titlesA, eyebrows = eyebrowsA)
        assertTrue("the media tile is the lower-left tile", hasTag(ActivityTileLowerLeftTag))
        compose.onNodeWithContentDescription("Pause", useUnmergedTree = true).assertExists()
        assertEquals("no Been a while while music plays", 0, chromeNodes("Been a while").size)
    }

    @Test
    fun `a - full data with music at font scale 1 point 3`() {
        mountA(1.3f)
        assertPage("a-full-music", 1.3f, hasStrip = true, dynamicTitles = titlesA, eyebrows = eyebrowsA)
    }

    /**
     * Ruling 7 on the Bottom, measured on the real page at the Presentation's 537.7 x 468.3 dp: a long authored track title is drawn in its own case,
     * on one line, ellipsised, in the media tile's real text lane. The node is the tile's own `Text`, so the rendered string is read back as it is drawn.
     */
    private fun assertLongTitleOnTheMediaTile(fontScale: Float) {
        val long = "The Quick Brown Fox Jumps Over The Lazy Dog And Keeps On Running Far Beyond The Horizon"
        mount(fontScale, surface(data(), CompanionMediaUiState.Active(track().copy(title = long)), snapshots))
        val seats = listOf(ActivityGameFaceTag, ActivityMediaArtTag).flatMap { tag ->
            compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, tag), useUnmergedTree = true).fetchSemanticsNodes().map { it.dp() }
        }
        val drawn = textNodes().filter { t ->
            val cx = t.box.x + t.box.w / 2f
            val cy = t.box.y + t.box.h / 2f
            t.text.equals(long, ignoreCase = true) && seats.none { cx >= it.x && cx <= it.right && cy >= it.y && cy <= it.bottom }
        }
        assertEquals("the title is drawn once on the tile", 1, drawn.size)
        val line = drawn.single()
        assertEquals("authored case, as the session named it (never capped)", long, line.text)
        assertEquals("one line at font scale $fontScale", 1, line.layout.lineCount)
        assertTrue("and ellipsised, because it is wider than the tile's lane", line.layout.isLineEllipsized(0))
        println("R7-BOTTOM-WIDTH fontScale=$fontScale host=${"%.1f".format(hostWidthDp)}x${"%.1f".format(hostHeightDp)}dp mediaTitleLane=${"%.1f".format(line.box.w)}dp lines=${line.layout.lineCount} ellipsized=${line.layout.isLineEllipsized(0)}")
    }

    @Test
    fun `a long authored track title keeps one line and its ellipsis in the media tile at 1 point 0`() = assertLongTitleOnTheMediaTile(1.0f)

    @Test
    fun `a long authored track title keeps one line and its ellipsis in the media tile at 1 point 3`() = assertLongTitleOnTheMediaTile(1.3f)

    // ── (b) no music ───────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `b - no music makes the lower-left tile Been a while, at 1 point 0`() {
        mount(1.0f, surface(data(), CompanionMediaUiState.NoActiveMedia))
        assertPage("b-no-music", 1.0f, dynamicTitles = titlesA, eyebrows = eyebrowsB)
        assertEquals("the Open pill", 1, compose.onAllNodesWithText("Open", useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun `b - no music makes the lower-left tile Been a while, at 1 point 3`() {
        mount(1.3f, surface(data(), CompanionMediaUiState.NoActiveMedia))
        assertPage("b-no-music", 1.3f, dynamicTitles = titlesA, eyebrows = eyebrowsB)
    }

    // ── (c) no launch history ──────────────────────────────────────────────────────────────────────────────────────────────────────

    private val shelf = listOf(
        game("s1", "Alex Kidd in the Enchanted Castle", plays = 0, lastDaysAgo = null),
        game("s2", "Columns", plays = 0, lastDaysAgo = null),
    )
    private val emptyWeek = launchesPerLocalDay(emptyList(), now, zone)

    @Test
    fun `c - no launch history fills Last played and the lower-left tile with Been a while, bars at zero, at 1 point 0`() {
        mount(1.0f, page(data(launched = emptyList(), shelfPool = shelf, week = emptyWeek)))
        assertPage("c-no-history", 1.0f, dynamicTitles = shelf.map { it.title }.toSet(), eyebrows = eyebrowsC)
        assertEquals("and neither claims a while", 0, chromeNodes("Been a while").size)
        assertEquals("the week says none yet", 1, compose.onAllNodesWithText("None yet", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(
            "Launches this week: none yet",
            compose.onNodeWithTag(ActivityWeekBarsTag, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString(),
        )
        assertEquals("Most launched has an honest line instead of rows", 1, compose.onAllNodesWithText("Nothing launched yet", useUnmergedTree = true).fetchSemanticsNodes().size)
        // The two tiles show two different games.
        fun described(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
        assertNotEquals(described(ActivityTileLastPlayedTag), described(ActivityTileLowerLeftTag))
    }

    @Test
    fun `c - no launch history at 1 point 3`() {
        mount(1.3f, page(data(launched = emptyList(), shelfPool = shelf, week = emptyWeek)))
        assertPage("c-no-history", 1.3f, dynamicTitles = shelf.map { it.title }.toSet(), eyebrows = eyebrowsC)
    }

    // ── (d) a game is live ─────────────────────────────────────────────────────────────────────────────────────────────────────────

    private val liveRow = game("live", "Ristar", plays = 2, lastDaysAgo = 0)

    @Test
    fun `d - a live game makes the top-left tile Now playing, at 1 point 0`() {
        mount(1.0f, page(data(launched = listOf(liveRow) + launched, live = liveRow)))
        assertPage("d-live", 1.0f, live = true, dynamicTitles = titlesA + "Ristar", eyebrows = eyebrowsD)
        assertFalse("no Last played tile while a game is live", hasTag(ActivityTileLastPlayedTag))
        compose.onNodeWithContentDescription("Open Ristar game details").assertExists()
    }

    @Test
    fun `d - a live game at 1 point 3`() {
        mount(1.3f, page(data(launched = listOf(liveRow) + launched, live = liveRow)))
        assertPage("d-live", 1.3f, live = true, dynamicTitles = titlesA + "Ristar", eyebrows = eyebrowsD)
    }

    // ── the art law ────────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `art that is still being looked up draws nothing, and a game with no art wears the book`() {
        // A ranked row's thumbnail: the book carries the game's title on its leather, so a game wearing the book has its name drawn twice
        // (the book and the row), and a game whose art lookup is not finished has it drawn once (the row): the book is a claim the product
        // has not established yet.
        // A third, more recent game takes the top-left tile, and Been a while no longer repeats a game that Most launched already lists (the L2
        // review's finding), so each of the two has exactly the tile it is under test in: its ranked row.
        val recent = game("r", "Recent Game", plays = 1, lastDaysAgo = 1)
        val unresolved = game("u", "Unresolved Quest", plays = 99, lastDaysAgo = 2, settled = false)
        val missing = game("m", "Missing Cover", plays = 98, lastDaysAgo = 2, settled = true)
        mount(1.0f, page(data(launched = listOf(recent, unresolved, missing))))
        assertTrue("the unresolved game's name is drawn once, by its row, not by a book", titleCount("Unresolved Quest") >= 1)
        val unresolvedCount = titleCount("Unresolved Quest")
        val missingCount = titleCount("Missing Cover")
        println("L2-ART unresolved=$unresolvedCount missing=$missingCount")
        assertTrue("a settled game with no art draws the book (more name nodes than the unresolved one)", missingCount > unresolvedCount)
    }

    // ── behaviour ──────────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `tiles and rows act, the sort chip re-ranks, and a disabled page does nothing`() {
        val opened = mutableListOf<String>()
        mount(1.0f) {
            ActivityPage(
                data = data(),
                navigationEnabled = true,
                mediaTile = null,
                achievementSnapshots = emptyList(),
                achievementsAccessEnabled = false,
                onOpenAchievements = {},
                onOpenGame = { opened += it.gameId.value },
                onOpenLiveGame = {},
                trailingReserve = 0.dp,
            )
        }
        compose.onNodeWithTag(ActivityTileLastPlayedTag).performClick()
        assertEquals(listOf("g1"), opened)
        // The default order is launches: Streets of Rage 2 (22) first.
        compose.onNodeWithContentDescription("Open Streets of Rage 2 launch card").performClick()
        assertEquals(listOf("g1", "g2"), opened)
        // The sort chip opens a menu of the three orders, and choosing one re-ranks the rows.
        compose.onNodeWithTag(ActivitySortChipTag).performClick()
        settle()
        compose.onNodeWithContentDescription("Sort by First played").assertExists()
        compose.onNodeWithContentDescription("Sort by Launches").assertExists()
        compose.onNodeWithContentDescription("Sort by Last played").performClick()
        settle()
        compose.onNodeWithContentDescription("Sort order: Last played").assertExists()
        assertEquals(
            "last played is most recent first: Sonic (1 day), Streets (3), Phantasy (12)",
            listOf("Sonic the Hedgehog 2", "Streets of Rage 2", "Phantasy Star IV"),
            rankedRowTitles(),
        )
    }

    // ── review fixes ───────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a live game opens its session details from its tile and its ranked row, never a launch card`() {
        val cards = mutableListOf<String>()
        var details = 0
        mount(1.0f, page(data(launched = listOf(liveRow) + launched, live = liveRow), onOpenGame = { cards += it.gameId.value }, onOpenLiveGame = { details++ }))
        compose.onNodeWithTag(ActivityTileNowPlayingTag).performClick()
        assertEquals(1, details)
        // The live game is the 2 launches game, so it is not in the top three by launches; sort by last played to bring its row up.
        compose.onNodeWithTag(ActivitySortChipTag).performClick()
        settle()
        compose.onNodeWithContentDescription("Sort by Last played").performClick()
        settle()
        compose.onNodeWithContentDescription("Open Ristar launch card").performClick()
        assertEquals("the live game's row opens the same details", 2, details)
        assertTrue("and no card was opened for it: $cards", "live" !in cards)
    }

    @Test
    fun `the achievements entry is always reachable with its honest setup state`() {
        var opened = 0
        mount(1.0f, page(data(), onOpenAchievements = { opened++ }, achievementsAccessEnabled = false))
        assertEquals(1, textNodes().count { it.text == "No achievements checked yet" })
        assertEquals(1, textNodes().count { it.text == "Connect RetroAchievements in Settings to load achievements" })
        compose.onNodeWithTag(ActivityTileAchievementsTag).performClick()
        assertEquals(1, opened)
        val b = bounds(ActivityTileAchievementsTag)
        assertTrue("a compact entry, at least a 48 dp target: $b", b.h >= 48f && b.h <= 80f)
    }

    @Test
    fun `an entry with access connected says to check games in the library`() {
        mount(1.0f, page(data(), achievementsAccessEnabled = true))
        assertEquals(1, textNodes().count { it.text == "Check games in the library to load achievements" })
    }

    @Test
    fun `an unavailable media session keeps the media tile with its honest explanation and how to recover`() {
        mount(1.0f, surface(data(), CompanionMediaUiState.SessionUnavailable))
        assertPage("e-media-unavailable", 1.0f, dynamicTitles = titlesA, eyebrows = listOf("Last played", "This week", "Most launched", "Media"))
        assertEquals(1, textNodes().count { it.text == "Media unavailable" })
        assertEquals(1, textNodes().count { it.text == "Reconnect the source app to resume playback controls." })
        assertTrue(hasTag(ActivityMediaStateTag))
        assertEquals("Been a while does not take the tile", 0, chromeNodes("Been a while").size + chromeNodes("Recently played").size)
    }

    @Test
    fun `an unavailable media session at 1 point 3`() {
        mount(1.3f, surface(data(), CompanionMediaUiState.SessionUnavailable))
        assertPage("e-media-unavailable", 1.3f, dynamicTitles = titlesA, eyebrows = listOf("Last played", "This week", "Most launched", "Media"))
    }

    @Test
    fun `media access still to be granted keeps the media tile with a 48 dp action that asks for it`() {
        var asked = 0
        mount(1.0f) {
            CompanionWorkspaceSurface(
                page = CompanionWorkspacePage.ACTIVITY,
                navigationEnabled = true,
                recentApp = null,
                game = null,
                media = CompanionMediaUiState.AccessRequired,
                onRecentApp = {},
                onReturnToGame = null,
                onMediaAccess = { asked++ },
                onMediaAction = { _, _, _ -> },
                bento = data(),
            )
        }
        assertPage("f-media-access", 1.0f, dynamicTitles = titlesA, eyebrows = listOf("Last played", "This week", "Most launched", "Media"))
        assertEquals(1, textNodes().count { it.text == "Connect your media" })
        compose.onNodeWithContentDescription("Open notification access").performClick()
        assertEquals(1, asked)
    }

    @Test
    fun `three launched games all in Most launched leave Been a while to the shelf instead of repeating one of them`() {
        // L2 review: a third launched game already visible in Most launched disabled the shelf pool, so Been a while repeated it.
        val three = launched.take(3)
        val shelf = game("s1", "Shelf Game", plays = 0, lastDaysAgo = null)
        mount(1.0f, page(data(launched = three, shelfPool = listOf(shelf))))
        assertEquals("Been a while reads from the shelf", 1, chromeNodes("From your shelf").size)
        assertEquals("and repeats no launched game", 0, chromeNodes("Been a while").size + chromeNodes("Recently played").size)
        assertTrue("the shelf game is the one Been a while shows", textNodes().any { it.text == "Shelf Game" })
    }

    @Test
    fun `the lower-left tile is Been a while only when media is available and idle`() {
        mount(1.0f, surface(data(), CompanionMediaUiState.NoActiveMedia))
        assertFalse(hasTag(ActivityMediaStateTag))
        assertEquals(1, chromeNodes("Been a while").size)
    }

    @Test
    fun `Last played gets a stacked landscape art zone and Now playing the same`() {
        mount(1.0f, page(data()))
        val zone = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, ActivityGameFaceTag), useUnmergedTree = true)
            .fetchSemanticsNodes().map { it.dp() }.maxByOrNull { it.w * it.h }!!
        val tile = bounds(ActivityTileLastPlayedTag)
        println("L2-ART-SIZES lastPlayedZone=$zone tile=$tile")
        assertTrue("a landscape zone across the tile, not a narrow seat beside text: $zone", zone.w >= tile.w - 22f && zone.h >= 70f && zone.w > zone.h * 2f)
        assertTrue("the zone sits over the title, inside the tile: $zone in $tile", zone.y >= tile.y && zone.bottom <= tile.bottom)
        val title = textNodes().single { it.text == "Sonic the Hedgehog 2" && it.box.y > zone.bottom - 1f && it.box.x < tile.x + tile.w }
        assertTrue("the title is under the art", title.box.y >= zone.bottom - 0.5f)
    }

    @Test
    fun `the launch card opened from Activity has a 48 dp PLAY that launches the game`() {
        val unresolved = game("u", "Unresolved Quest", plays = 99, lastDaysAgo = 2, settled = false)
        val missing = game("m", "Missing Cover", plays = 98, lastDaysAgo = 2, settled = true)
        var played: String? = null
        mount(1.0f) {
            com.gruning.mimir.rewrite.feature.library.CompanionLaunchCardLayer(
                game = missing,
                active = true,
                displayChoiceFor = { com.gruning.mimir.rewrite.feature.library.CompanionLaunchDisplayChoice.ASK },
                onDismiss = {},
                onEditDisplay = {},
                onPlay = { played = it.gameId.value },
            ) {
                surface(data(launched = listOf(unresolved, missing)), CompanionMediaUiState.NoActiveMedia)()
            }
        }
        val play = compose.onNode(hasClickAction().and(androidx.compose.ui.test.hasText("PLAY"))).fetchSemanticsNode().dp()
        println("L2-CARD play=$play")
        assertTrue("PLAY is at least 48 dp tall: $play", play.h >= 47.5f)
        assertTrue("and 48 dp wide: $play", play.w >= 47.5f)
        compose.onNode(hasClickAction().and(androidx.compose.ui.test.hasText("PLAY"))).performClick()
        assertEquals("m", played)
    }

    @Test
    fun `the launch card opened from Activity wears the chosen look and the switch asks the host to change it`() {
        // Slices L5 and L6: Activity opens the very card the shelf draws, in the look the host passes (the global one), with the app's totals and the platform's name.
        val missing = game("m", "Missing Cover", plays = 98, lastDaysAgo = 2, settled = true)
        val asked = mutableListOf<com.gruning.mimir.rewrite.feature.library.CompanionLaunchLook>()
        mount(1.0f) {
            val look = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(com.gruning.mimir.rewrite.feature.library.CompanionLaunchLook.JOURNAL) }
            com.gruning.mimir.rewrite.feature.library.CompanionLaunchCardLayer(
                game = missing,
                active = true,
                displayChoiceFor = { com.gruning.mimir.rewrite.feature.library.CompanionLaunchDisplayChoice.BOTTOM },
                onDismiss = {},
                onEditDisplay = {},
                onPlay = {},
                look = look.value,
                onLookChange = { asked += it; look.value = it },
                platformLabelFor = { "Genesis" },
                journalFactsFor = {
                    com.gruning.mimir.rewrite.feature.library.CompanionLaunchCardFacts(sessionTotalMillis = 4_980_000L, achievementsDone = 3, achievementsTotal = 10)
                },
            ) {
                surface(data(launched = listOf(missing)), CompanionMediaUiState.NoActiveMedia)()
            }
        }
        val texts = textNodes().map { it.text }
        println("L5-ACTIVITY-CARD texts=${texts.filter { it in listOf("GENESIS", "98", "1 h 23 min", "3 of 10", "Missing Cover", "Look", "Journal") }}")
        assertTrue("the Journal's facts are drawn: $texts", hasTag(com.gruning.mimir.rewrite.feature.library.CompanionLaunchCardFactsTag))
        assertTrue("the eyebrow is the platform", "GENESIS" in texts)
        assertTrue("the launches are the row's playCount", "98" in texts)
        assertTrue("the session time is the app's total, in the house wording", "1 h 23 min" in texts)
        assertTrue("the achievements are the snapshot's counts", "3 of 10" in texts)
        assertTrue("Opens on says the game's display", "Bottom screen" in texts)
        assertFalse("no stage under the Journal", hasTag(com.gruning.mimir.rewrite.feature.library.CompanionLaunchCardStageTag))
        val card = compose.onNodeWithTag(com.gruning.mimir.rewrite.feature.library.CompanionLaunchCardTag, useUnmergedTree = true).fetchSemanticsNode().dp()
        println("L5-ACTIVITY-CARD card=$card")
        assertTrue("the card is 470 dp wide and leaves 48 dp above and below it: $card", kotlin.math.abs(card.w - 470f) < 1f && card.h <= hostHeightDp - 96f + 0.6f)
        compose.onNodeWithTag(com.gruning.mimir.rewrite.feature.library.CompanionLaunchCardLookTag).performClick()
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        assertEquals(listOf(com.gruning.mimir.rewrite.feature.library.CompanionLaunchLook.LOGO_ONLY), asked)
        assertTrue("the card changed to Logo Only: the stage is there", hasTag(com.gruning.mimir.rewrite.feature.library.CompanionLaunchCardStageTag))
        assertFalse(hasTag(com.gruning.mimir.rewrite.feature.library.CompanionLaunchCardFactsTag))
    }

    // ── receipts ───────────────────────────────────────────────────────────────────────────────────────────────────────────────────

    private fun receipts(): List<String> =
        org.robolectric.shadows.ShadowLog.getLogsForTag(com.gruning.mimir.rewrite.core.ui.BottomUiReceiptFormat.TAG).map { it.msg }

    @Test
    fun `the media surface receipt is still written while music plays and the new taps write opaque control lines`() {
        org.robolectric.shadows.ShadowLog.clear()
        var opened: GameRow? = null
        mount(1.0f) {
            CompositionLocalProvider(com.gruning.mimir.rewrite.core.ui.LocalBottomUiDisplay provides 4) {
                CompanionWorkspaceSurface(
                    page = CompanionWorkspacePage.ACTIVITY,
                    navigationEnabled = true,
                    recentApp = null,
                    game = null,
                    media = CompanionMediaUiState.Active(track()),
                    onRecentApp = {},
                    onReturnToGame = null,
                    onMediaAccess = {},
                    onMediaAction = { _, _, _ -> },
                    bento = data(),
                    onOpenGame = { opened = it },
                )
            }
        }
        assertTrue(
            "the stacked layout wrote surface.enter for activity-media and this one still does: ${receipts()}",
            receipts().any { it.startsWith("ev=surface.enter surface=activity-media display=4") },
        )
        compose.onNodeWithTag(ActivityTileLastPlayedTag).performClick()
        assertEquals("g1", opened?.gameId?.value)
        val lines = receipts()
        // pad8 : the Shelf button is gone from the header, so its receipt is gone with it; the swipe is the way back.
        assertFalse("no back-to-shelf control exists any more: $lines", lines.any { it.contains("activity.back_to_shelf") })
        val card = lines.single { it.contains("id=activity.open_launch_card") }
        assertTrue("the card tap names an opaque game id: $card", Regex("target=game:[0-9a-f]{10}").containsMatchIn(card))
        assertTrue("and never a title: $card", "Sonic" !in card)
        // The swipe-back receipt is written by the Presentation's own callback and is not this surface's: nothing here writes it.
        assertFalse(lines.any { it.contains("activity.swipe_back") })
    }

    @Test
    fun `no media surface receipt is written when nothing plays`() {
        org.robolectric.shadows.ShadowLog.clear()
        mount(1.0f) {
            CompositionLocalProvider(com.gruning.mimir.rewrite.core.ui.LocalBottomUiDisplay provides 4) {
                surface(data(), CompanionMediaUiState.NoActiveMedia)()
            }
        }
        assertFalse(receipts().any { it.startsWith("ev=surface.enter surface=activity-media") })
    }

    // ---- Pad4: the Trackpad plate on the Activity header ---------------------------------------------------------------------------

    private fun pageWithTrackpad(offered: Boolean, reserve: androidx.compose.ui.unit.Dp, onOpen: () -> Unit): @Composable () -> Unit = {
        CompositionLocalProvider(com.gruning.mimir.rewrite.core.ui.LocalBottomTrackpadOpen provides if (offered) onOpen else null) {
            ActivityPage(
                data = data(),
                navigationEnabled = true,
                mediaTile = null,
                achievementSnapshots = emptyList(),
                achievementsAccessEnabled = false,
                onOpenAchievements = {},
                    onOpenGame = {},
                onOpenLiveGame = {},
                trailingReserve = reserve,
            )
        }
    }

    private fun assertPlateOnTheActivityHeader(fontScale: Float, reserve: androidx.compose.ui.unit.Dp) {
        mount(fontScale, pageWithTrackpad(offered = true, reserve = reserve, onOpen = {}))
        val row = bounds(com.gruning.mimir.rewrite.core.ui.MimirCompactHeaderRowTag)
        val badge = bounds(com.gruning.mimir.rewrite.core.ui.MimirCompactHeaderBadgeTag)
        val title = bounds(com.gruning.mimir.rewrite.core.ui.MimirCompactHeaderTitleTag)
        val plate = bounds(com.gruning.mimir.rewrite.core.ui.BottomTrackpadPlateTag)
        println(
            "PAD8-ACTIVITY-HEADER host=${"%.1f".format(hostWidthDp)}dp scale=$fontScale reserve=${reserve.value} row=$row badge=$badge title=$title plate=$plate " +
                "plateRightToRowRight=${"%.1f".format(row.right - plate.right)}",
        )
        assertFalse("pad8: no Shelf button seat", hasTag(com.gruning.mimir.rewrite.core.ui.MimirCompactHeaderLeadingTag))
        assertEquals("the mark starts at the row's left edge now that the button is gone: badge $badge row $row", row.x, badge.x, 0.6f)
        assertTrue("the plate is at least a 48 dp target: $plate", plate.w >= 47.9f && plate.h >= 47.9f)
        assertTrue("the plate sits inside the header row: $plate in $row", plate.right <= row.right + 0.5f)
        assertTrue("the plate clears the recent-app icon's ${reserve.value} dp reserve", plate.right <= row.right - reserve.value + 0.6f)
        assertTrue("the plate is right of the title: title $title plate $plate", plate.x >= title.right - 0.5f)
        assertFalse("the page does not scroll sideways: the plate is on the page", plate.right > hostWidthDp)
    }

    @Test
    fun `the Activity header carries the Trackpad plate beside its title at the system font size`() = assertPlateOnTheActivityHeader(1.0f, 0.dp)

    @Test
    fun `the Activity header's plate still fits at font scale 1_3`() = assertPlateOnTheActivityHeader(1.3f, 0.dp)

    @Test
    fun `the Activity header plate clears the recent-app icon reserve at the system font size`() {
        assertPlateOnTheActivityHeader(1.0f, ActivityRecentAppReserveDp.dp)
    }

    @Test
    fun `the Activity header's plate clears the recent-app icon's reserve at font scale 1_3`() = assertPlateOnTheActivityHeader(1.3f, ActivityRecentAppReserveDp.dp)

    @Test
    fun `a tap on the Activity plate opens the pad through the gateway`() {
        var opened = 0
        mount(1.0f, pageWithTrackpad(offered = true, reserve = 0.dp, onOpen = { opened++ }))
        compose.onNodeWithTag(com.gruning.mimir.rewrite.core.ui.BottomTrackpadPlateTag, useUnmergedTree = true).performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `with no trackpad offered the Activity header has no plate and keeps its reserve`() {
        mount(1.0f, pageWithTrackpad(offered = false, reserve = ActivityRecentAppReserveDp.dp, onOpen = {}))
        assertFalse(hasTag(com.gruning.mimir.rewrite.core.ui.BottomTrackpadPlateTag))
        val trailing = bounds(com.gruning.mimir.rewrite.core.ui.MimirCompactHeaderTrailingTag)
        assertEquals("the reserve is the trailing slot, as before", ActivityRecentAppReserveDp, trailing.w, 0.6f)
    }

    // ---- pad8: no Shelf button; the molten week ------------------------------------------------------------------------------------

    private fun assertNoShelfButton(fontScale: Float) {
        mount(fontScale, pageWithTrackpad(offered = true, reserve = 0.dp, onOpen = {}))
        assertEquals("no 'Back to Shelf' control", 0, compose.onAllNodesWithContentDescription("Back to Shelf", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals("and no '‹ Shelf' text", 0, compose.onAllNodesWithText("\u2039 Shelf", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertFalse("and no leading slot", hasTag(com.gruning.mimir.rewrite.core.ui.MimirCompactHeaderLeadingTag))
        val row = bounds(com.gruning.mimir.rewrite.core.ui.MimirCompactHeaderRowTag)
        val title = bounds(com.gruning.mimir.rewrite.core.ui.MimirCompactHeaderTitleTag)
        val plate = bounds(com.gruning.mimir.rewrite.core.ui.BottomTrackpadPlateTag)
        println("PAD8-ACTIVITY-NOSHELF host=${"%.1f".format(hostWidthDp)}dp scale=$fontScale row=$row title=$title plate=$plate titleToPlate=${"%.1f".format(plate.x - title.right)}")
        assertTrue("the title has room before the plate: title $title plate $plate", plate.x - title.right >= 0f)
    }

    @Test
    fun `the Activity header has no Shelf button at the system font size`() = assertNoShelfButton(1.0f)

    @Test
    fun `the Activity header has no Shelf button at font scale 1_3`() = assertNoShelfButton(1.3f)

    @Test
    fun `swiping right is how the Activity page returns, and its page-level swipe covers every subsurface`() {
        val source = File("src/main/kotlin/com/gruning/mimir/rewrite/CompanionWorkspaceSurface.kt").readText()
        // The drag wraps the whole Activity page (the resting layout and its takeover, hub and music subsurfaces are all inside this one box), and
        // is enabled by the page's own navigation gate: nothing about it depended on the removed button.
        val swipe = source.substringAfter("val activitySwipeModifier = ").substringBefore("Box(Modifier.fillMaxSize().then(activitySwipeModifier)")
        assertTrue("the swipe is the Activity page's", swipe.contains("page == CompanionWorkspacePage.ACTIVITY") && swipe.contains("Orientation.Horizontal"))
        assertTrue("the box that takes it holds the scenes", source.contains("Box(Modifier.fillMaxSize().then(activitySwipeModifier).activityReturnAction(returnAction)) {\n        if (showActivityContent && page == CompanionWorkspacePage.ACTIVITY) {"))
        assertFalse("no Shelf callback is left in the surface", source.contains("onBackToShelf"))
        val bento = File("src/main/kotlin/com/gruning/mimir/rewrite/CompanionActivityBento.kt").readText()
        assertFalse("nor in the page", bento.contains("ActivityBackPill") || bento.contains("back_to_shelf") || bento.contains("onBack"))
        assertFalse("nor in the Presentation", File("src/main/kotlin/com/gruning/mimir/rewrite/MimirCompanionPresentation.kt").readText().contains("onBackToShelf"))
    }

    @Test
    fun `a day with no launch is a short dark ingot and a launch day is a bar that never goes under its minimum`() {
        assertEquals(ActivityWeekBarKind.INGOT, activityWeekBarKind(0))
        assertEquals(ActivityWeekBarKind.BAR, activityWeekBarKind(1))
        // Arrival: a bar grows with the first read; the ingot is there from the start (it is the old stub's place) and is taller than the old 2 dp stub.
        assertEquals("the ingot is its fixed height before the arrival", ActivityWeekIngotDp, activityWeekBarHeight(0, 5, 100f, 0f, 3f, ActivityWeekIngotDp), 0f)
        assertEquals("and after it", ActivityWeekIngotDp, activityWeekBarHeight(0, 5, 100f, 1f, 3f, ActivityWeekIngotDp), 0f)
        assertTrue("an ingot is a stub, not a missing bar and not a bar: ${ActivityWeekIngotDp}", ActivityWeekIngotDp > 2f && ActivityWeekIngotDp < 8f)
        assertEquals("the busiest day fills the track", 100f, activityWeekBarHeight(5, 5, 100f, 1f, 3f, ActivityWeekIngotDp), 0.001f)
        assertEquals("half the launches, half the height", 50f, activityWeekBarHeight(5, 10, 100f, 1f, 3f, ActivityWeekIngotDp), 0.001f)
        assertEquals("a bar at the start of the arrival is never under its minimum", 3f, activityWeekBarHeight(1, 100, 100f, 0f, 3f, ActivityWeekIngotDp), 0f)
        val zeroDays = weekCounts.count { it.count == 0 }
        assertTrue("the fixture week has a zero-launch day to draw: $zeroDays", zeroDays >= 1)
        assertEquals("today is the last day", weekCounts.last().date, weekCounts.maxOf { it.date })
    }

    @Test
    fun `the week's bars are drawn from theme tokens on every theme and read the same to a screen reader`() {
        for (palette in listOf(
            MimirThemePalette.Default, MimirThemePalette.Freyr, MimirThemePalette.Surtr, MimirThemePalette.Mani, MimirThemePalette.Hel,
            MimirThemePalette.Niflheim, MimirThemePalette.Yggdrasil, MimirThemePalette.Folkvangr, MimirThemePalette.Baldr,
        )) {
            val base = activityWeekBase(palette)
            assertTrue("the base is cooler (darker) than the accent for ${palette.id}", base.luminance() < palette.accent.luminance())
            val (top, bottom) = activityWeekIngot(palette)
            assertTrue("the ingot is dark, darker than a bar's base, for ${palette.id}: ${top.luminance()} vs ${base.luminance()}", top.luminance() <= base.luminance() + 0.0001f)
            assertTrue("and darkens toward its foot", bottom.luminance() <= top.luminance() + 0.0001f)
        }
        mount(1.0f, page(data()))
        val spoken = compose.onNodeWithTag(ActivityWeekBarsTag).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
        assertTrue("the spoken week is unchanged: $spoken", spoken.startsWith("Launches this week:"))
    }

    @Test
    fun `the week's glow and every new loop are parked-aware and token-only`() {
        val bento = File("src/main/kotlin/com/gruning/mimir/rewrite/CompanionActivityBento.kt").readText()
        assertTrue("today's glow breathes on the shared, bounded ember life, told the pane's own liveness", bento.contains("rememberEmberLife(LocalMimirMotionLive.current)") && bento.contains("life.wave()"))
        assertFalse("no hex literal in the week", Regex("""Color\(\s*0x""").containsMatchIn(bento))
        assertFalse("no endless transition of its own", bento.contains("rememberInfiniteTransition") || bento.contains("infiniteRepeatable"))
    }

    // ---- pad9: an accessible way back to the Shelf ----------------------------------------------------------------------------------

    private fun returnActions() = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions), useUnmergedTree = true)
        .fetchSemanticsNodes()
        .flatMap { it.config[SemanticsActions.CustomActions] }
        .filter { it.label == ActivityReturnToShelfLabel }

    private fun returnSurface(navigationEnabled: Boolean, onReturn: (() -> Boolean)?): @Composable () -> Unit = {
        CompositionLocalProvider(com.gruning.mimir.rewrite.core.ui.LocalBottomUiDisplay provides 4) {
            CompanionWorkspaceSurface(
                page = CompanionWorkspacePage.ACTIVITY,
                navigationEnabled = navigationEnabled,
                recentApp = null,
                game = null,
                media = CompanionMediaUiState.NoActiveMedia,
                onRecentApp = {},
                onReturnToGame = null,
                onMediaAccess = {},
                onMediaAction = { _, _, _ -> },
                bento = data(),
                onReturnToShelf = onReturn,
            )
        }
    }

    @Test
    fun `the Activity page offers Return to Shelf as an accessibility action on its root and its page, with no visible control`() {
        var returned = 0
        mount(1.0f, returnSurface(navigationEnabled = true, onReturn = { returned++; true }))
        val actions = returnActions()
        assertTrue("the action is on the page root (every subsurface sits under it) and on the page: ${actions.size}", actions.size >= 2)
        compose.runOnUiThread { assertTrue("the action reports that the transition began", actions.first().action()) }
        compose.runOnUiThread { assertTrue(actions.last().action()) }
        assertEquals("each one asks the host for the swipe's own transition", 2, returned)
        assertEquals("and nothing visible says it", 0, compose.onAllNodesWithContentDescription(ActivityReturnToShelfLabel, useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText(ActivityReturnToShelfLabel, useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun `a page with navigation off, or a host with no strip, offers no way back`() {
        mount(1.0f, returnSurface(navigationEnabled = false, onReturn = { true }))
        assertEquals("a disabled page does nothing", 0, returnActions().size)
    }

    @Test
    fun `a host with no strip offers no action`() {
        mount(1.0f, returnSurface(navigationEnabled = true, onReturn = null))
        assertEquals(0, returnActions().size)
    }

    @Test
    fun `the Presentation wires the action through the workspace strip, the swipe's own settle`() {
        val presentation = File("src/main/kotlin/com/gruning/mimir/rewrite/MimirCompanionPresentation.kt").readText()
        assertTrue(presentation.contains("onReturnToShelf = { workspaceStrip.returnToShelf(workspaceStripFlingPx) != null }"))
        val strip = File("src/main/kotlin/com/gruning/mimir/rewrite/CompanionWorkspaceStrip.kt").readText()
        val twin = strip.substringAfter("fun returnToShelf(flingPx: Float)").substringBefore("/** The drag ended without a release")
        assertTrue("it is the same drag and the same release as a swipe", twin.contains("drag(CompanionWorkspacePage.ACTIVITY, -widthPx * ReturnFlickFraction)") && twin.contains("release("))
        assertFalse("and writes no receipt of its own", twin.contains("log.control") || twin.contains("receipt"))
    }

    // ---- The Open pill on the Been a while / Recently played tile uses struck metal ----------------------

    private fun openPlateSource(): String {
        val src = File("src/main/kotlin/com/gruning/mimir/rewrite/CompanionActivityBento.kt").readText()
        return src.substring(src.indexOf("private fun BeenAWhileTile("), src.indexOf("// ── This week"))
    }

    @Test
    fun `the Open pill is drawn as a raised metal plate with no idle animation and nothing else about the tile moved`() {
        val tile = openPlateSource()
        assertTrue(
            "the shared struck-metal plate, raised, pressed by the tile's own interaction source",
            tile.contains(".mimirMetalPlate(palette, MimirSpacing.radiusCard, lit = false, life = null, pressed = pressed, raised = true)"),
        )
        assertTrue("the press state is the tile's own source", tile.contains("val pressed = source.collectIsPressedAsState()"))
        assertTrue("the label is inked in the plates' metal and sinks with the press", tile.contains("copy(brush = metal, alpha = MimirMetalRestAlpha)") && tile.contains("mimirRaisedPressNudge(pressed)"))
        assertFalse("no ember life: nothing animates at rest", tile.contains("rememberEmberLife") || tile.contains("infiniteRepeatable") || tile.contains("rememberInfiniteTransition"))
        assertFalse("no longer a flat accent pill", tile.contains("palette.accent.copy(alpha = 0.18f)"))
        assertTrue("the whole tile stays the one activatable target", tile.contains("label = \"Open launch card\"") && tile.contains(".mimirActivatable("))
        assertFalse("no hex literal", Regex("""Color\(\s*0x""").containsMatchIn(tile))
    }

    @Test
    fun `the Open plate keeps its 48 dp target, its label and the tile's click and semantics`() {
        val opened = mutableListOf<String>()
        mount(1.0f) {
            ActivityPage(
                data = data(),
                navigationEnabled = true,
                mediaTile = null,
                achievementSnapshots = emptyList(),
                achievementsAccessEnabled = false,
                onOpenAchievements = {},
                onOpenGame = { opened += it.gameId.value },
                onOpenLiveGame = {},
                trailingReserve = 0.dp,
            )
        }
        val plate = compose.onNodeWithTag(ActivityOpenPlateTag, useUnmergedTree = true).fetchSemanticsNode()
        assertEquals("the target stays 48 dp tall", 48f, plate.size.height / density, 0.6f)
        assertEquals("the one Open label", 1, compose.onAllNodesWithText("Open", useUnmergedTree = true).fetchSemanticsNodes().size)
        val tile = compose.onNodeWithTag(ActivityTileLowerLeftTag).fetchSemanticsNode()
        assertEquals("Open launch card", tile.config[SemanticsActions.OnClick].label)
        val description = tile.config[SemanticsProperties.ContentDescription].joinToString()
        assertTrue("the same spoken name (the eyebrow reads with it): $description", description.startsWith("Open ") && description.contains(" launch card"))
        compose.onNodeWithTag(ActivityTileLowerLeftTag).performClick()
        assertEquals("a click on the tile still opens the picked game's launch card, once", 1, opened.size)
    }

    @Test
    fun `the plate under the Open label is struck metal, a leather ground and a metal rim, on all nine themes`() {
        for (palette in MimirThemePalette.all) {
            val width = 160
            val height = 68
            val bitmap = androidx.compose.ui.graphics.ImageBitmap(width, height)
            androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(
                Density(2f),
                androidx.compose.ui.unit.LayoutDirection.Ltr,
                androidx.compose.ui.graphics.Canvas(bitmap),
                androidx.compose.ui.geometry.Size(width.toFloat(), height.toFloat()),
            ) {
                drawMimirMetalPlate(
                    palette,
                    com.gruning.mimir.rewrite.core.ui.platformConsoleIconBrush(palette),
                    com.gruning.mimir.rewrite.core.ui.MimirMetalRestAlpha,
                    warmed = false,
                    cornerPx = 28f,
                )
            }
            val pixels = bitmap.toPixelMap()
            val hero = palette.materials.hero
            val ground = pixels[width / 2, height / 2]
            val rim = pixels[width / 2, 1]
            val lo = minOf(hero.coverTop.luminance(), hero.coverBottom.luminance()) - 0.02f
            val hi = maxOf(hero.coverTop.luminance(), hero.coverBottom.luminance()) + 0.02f
            assertTrue("${palette.id}: a leather ground: ${ground.luminance()} in $lo..$hi", ground.alpha > 0.99f && ground.luminance() in lo..hi)
            assertTrue("${palette.id}: a metal rim over it: ${rim.luminance()} vs ${ground.luminance()}", rim.alpha > 0.99f && rim.luminance() > ground.luminance())
        }
    }
}
