package com.gruning.mimir.rewrite

import android.animation.ValueAnimator
import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsAchievement
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsProgress
import com.gruning.mimir.rewrite.core.model.CompanionAppEntry
import com.gruning.mimir.rewrite.core.model.CompanionMode
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.ui.MimirAchievementCopy
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Bottom's header slot for the recent-app tile (Thor drive 2026-10-08), at the Bottom Presentation's measured size: 1240 x 1080 px at 369 dpi, which is
 * 537.7 x 468.3 dp, at font scale 1.0 and 1.3.
 *
 * The tile (the Chrome icon, 48 dp, 8 dp from the top and 24 dp from the end) is laid over the Activity page once an app is leased to the Bottom. It used to cover
 * the Now Playing clock and the achievements hub's "Recent activity" sort chip. These pins hold the shared slot ([LocalBottomHeaderTrailingReserve], provided by
 * [CompanionWorkspaceSurface]) to what it promises: with an app leased, on Now Playing and on the achievements hub, no bounds of any clickable node, the clock or the
 * total overlap the tile; with none leased, the layout is the one it was. The Back pill's compact plate is measured here too: as tall as a filter chip's plate, its
 * label on the plate's centre line, one chevron-to-label gap, and a touch target of at least 48 dp. Pixels are the JVM's; the handheld's are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionBottomHeaderSlotRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val density369 = 369f / 160f
    private val hostWidthDp = 1240f / density369
    private val hostHeightDp = 1080f / density369

    private val startMs = 1_000_000L
    private val clockMs = (1L * 3600 + 24 * 60 + 7) * 1000L
    private var nowMs by mutableLongStateOf(startMs + clockMs)
    private var takeoverKey by mutableStateOf<String?>(null)
    private var leased by mutableStateOf<CompanionAppEntry?>(null)
    private val chrome = CompanionAppEntry(packageName = "com.android.chrome", label = "Chrome")

    @After
    fun restoreAnimatorScale() {
        setAnimatorScale(1f)
        AchievementToastBands.resetForTest()
        CompanionActivityCardDetails.resetForTest()
    }

    private fun setAnimatorScale(scale: Float) {
        ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, scale)
    }

    @Before
    fun setUp() {
        CompanionNowPlayingState.resetForTest()
        nowMs = startMs + clockMs
        takeoverKey = null
        leased = null
        toastFlow.value = null
        AchievementToastBands.resetForTest()
        CompanionActivityCardDetails.resetForTest()
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────

    private fun gameRow(lastPlayedMillis: Long? = null) = GameRow(
        gameId = GameId("g1"),
        title = "Sonic the Hedgehog 2",
        platformId = PlatformId("genesis"),
        memberCount = 1,
        duplicateKind = null,
        flatArtUri = null,
        heroArtUri = null,
        lastPlayedMillis = lastPlayedMillis,
    )

    private fun session() = MimirGameSession(
        operation = MimirGameSessionOperation("op-slot"),
        sequence = 1,
        gameId = GameId("g1"),
        title = "Sonic the Hedgehog 2",
        platformId = PlatformId("genesis"),
        returnAnchor = MimirGameReturnAnchor.Library(
            com.gruning.mimir.rewrite.feature.library.LibraryLaunchReturnTarget.shelf(
                com.gruning.mimir.rewrite.feature.library.LibraryShelfMode.EMULATION,
                "shelf",
            ),
        ),
        startedAtEpochMs = 1,
        dispatchedAtElapsedMs = startMs,
        targetPackageName = "org.example.emulator",
        dualScreen = false,
        phase = MimirSessionPhase.YIELDED_TO_SECONDARY_TASK,
    )

    private fun snapshot(n: Int, title: String, unlocked: Int, total: Int, awarded: Long?) = CompanionActivityAchievementSnapshot(
        gameId = "g$n",
        account = "me",
        unlocked = unlocked,
        total = total,
        gameTitle = title,
        checkedAtEpochMillis = 1L,
        rowsKnown = false,
        consoleName = "Genesis/Mega Drive",
        lastAwardedAtMillis = awarded,
    )

    private val snapshots = listOf(
        snapshot(1, "Sonic the Hedgehog 2", 12, 40, 1_700_000_000_000L),
        snapshot(2, "Streets of Rage 2", 5, 50, 1_690_000_000_000L),
        snapshot(3, "Super Metroid", 40, 40, 1_680_000_000_000L),
    )

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

    /** The production workspace surface: the live game's takeover open, the recent-app tile laid over it when [leased] is set, the hub reachable from the takeover's chip. */
    private fun surface(
        achievements: List<CompanionActivityAchievementSnapshot> = snapshots,
        lastPlayedMillis: Long? = null,
    ): @Composable () -> Unit = {
        val game = session()
        val row = gameRow(lastPlayedMillis)
        CompanionWorkspaceSurface(
            page = CompanionWorkspacePage.ACTIVITY,
            navigationEnabled = true,
            recentApp = leased,
            game = game,
            media = CompanionMediaUiState.NoActiveMedia,
            onRecentApp = {},
            onReturnToGame = null,
            onMediaAccess = {},
            onMediaAction = { _, _, _ -> },
            achievementSnapshots = achievements,
            achievementHubAccessEnabled = true,
            bento = ActivityBentoData(
                gameCount = 87,
                launched = listOf(row),
                shelfPool = emptyList(),
                week = null,
                liveGameId = game.gameId,
                liveTitle = game.title,
                liveRow = row,
                nowMillis = 0L,
                zone = ZoneId.of("UTC"),
            ),
            gameCardAvailableGameId = game.gameId,
            gameCardSessionKey = nowPlayingSessionKey(game),
            takeoverKey = takeoverKey,
            onTakeoverKeyChange = { takeoverKey = it },
            showRecentAppOverlay = true,
            gameCardContent = { onBack, onOpenAchievements ->
                CompanionActivityGameDetail(
                    game = game,
                    payload = CompanionPayload(
                        mode = CompanionMode.NOW_PLAYING,
                        gameId = game.gameId,
                        title = game.title,
                        platformId = game.platformId,
                        platformLabel = "Genesis",
                        launcherLabel = "RetroArch",
                    ),
                    row = row,
                    achievements = snapshots.first(),
                    totalBeforeMs = (18L * 60 + 40) * 60_000L,
                    platformLabel = "Genesis",
                    navigationEnabled = true,
                    onBack = onBack,
                    onOpenAchievements = onOpenAchievements,
                    onReturnToGame = { true },
                    quit = NowPlayingQuit { PrivilegedCloseOutcome.STOPPED },
                    nowElapsedMs = { nowMs },
                )
            },
        )
    }

    // ── measuring ────────────────────────────────────────────────────────────────────

    private class Box4(val x: Float, val y: Float, val w: Float, val h: Float) {
        val right get() = x + w
        val bottom get() = y + h
        val cx get() = x + w / 2f
        val cy get() = y + h / 2f
        override fun toString() = "%.1fx%.1f@(%.1f,%.1f)".format(w, h, x, y)
    }

    private fun SemanticsNode.dp(): Box4 {
        val p = positionInRoot
        return Box4(p.x / density369, p.y / density369, size.width / density369, size.height / density369)
    }

    private fun nodes(tag: String): List<SemanticsNode> =
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, tag), useUnmergedTree = true).fetchSemanticsNodes()

    private fun hasTag(tag: String) = nodes(tag).isNotEmpty()

    private fun bounds(tag: String): Box4 = nodes(tag).first().dp()

    private fun overlaps(a: Box4, b: Box4, tol: Float = 0.5f) =
        a.x + a.w - tol > b.x && b.x + b.w - tol > a.x && a.y + a.h - tol > b.y && b.y + b.h - tol > a.y

    /** The recent-app tile, found by what it says to a screen reader. */
    private fun tile(): Box4 =
        compose.onAllNodes(
            SemanticsMatcher("is the recent-app tile") { node ->
                node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Open recent app Chrome") == true
            },
            useUnmergedTree = true,
        ).fetchSemanticsNodes().first().dp()

    /** Every clickable node on screen except the tile itself. */
    private fun clickablesApartFromTile(): List<Box4> {
        val tileBox = tile()
        return compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick), useUnmergedTree = true).fetchSemanticsNodes()
            .map { it.dp() }
            .filterNot { kotlin.math.abs(it.x - tileBox.x) < 0.5f && kotlin.math.abs(it.y - tileBox.y) < 0.5f && kotlin.math.abs(it.w - tileBox.w) < 0.5f }
    }

    private fun settle() {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
    }

    private fun openHub() {
        compose.onNodeWithTag(NowPlayingAchievementsTag).performClick()
        settle()
        assertTrue("the hub is open", hasTag(AchievementHubCardTag))
        assertFalse("and the takeover is gone", hasTag(NowPlayingTakeoverTag))
    }

    private fun lease() {
        leased = chrome
        settle()
    }

    // ── the slot itself ──────────────────────────────────────────────────────────────

    @Test
    fun `the reserve is zero with no app leased and the tile's own room with one`() {
        assertEquals(0.dp, bottomHeaderTrailingReserveFor(recentAppShown = false))
        assertEquals(ActivityRecentAppReserveDp.dp, bottomHeaderTrailingReserveFor(recentAppShown = true))
        // The reserve past the page's 18 dp inset reaches the tile's near edge and 8 dp beyond it: 24 (tile's end) + 48 (tile) + 8 (clear) = 18 + 62.
        assertEquals(
            RecentAppTileEndDp + MimirSpacing.minTarget.value + 8f,
            ActivityPageInsetDp + ActivityRecentAppReserveDp,
            0.01f,
        )
    }

    // ── Now Playing ──────────────────────────────────────────────────────────────────

    private fun assertNowPlayingClearOfTile(fontScale: Float) {
        takeoverKey = nowPlayingSessionKey(session())
        mount(fontScale, surface())
        assertTrue("the takeover is shown", hasTag(NowPlayingTakeoverTag))
        lease()
        val tile = tile()
        println("SLOT-NOWPLAYING font=$fontScale tile=$tile clock=${bounds(NowPlayingClockTag)} total=${bounds(NowPlayingTotalTag)}")
        assertEquals("the tile is 48 dp, 24 dp from the end and 8 dp from the top", 48f, tile.w, 0.6f)
        assertEquals(hostWidthDp - 24f - 48f, tile.x, 0.8f)
        assertEquals(8f, tile.y, 0.8f)
        val clock = bounds(NowPlayingClockTag)
        val total = bounds(NowPlayingTotalTag)
        assertFalse("the elapsed clock ($clock) is not under the tile ($tile)", overlaps(clock, tile))
        assertFalse("nor the total ($total)", overlaps(total, tile))
        assertTrue("the clock ends 80 dp from the end, 8 dp clear of the tile: $clock", clock.right <= tile.x - 7.5f)
        clickablesApartFromTile().forEach { assertFalse("a control $it is not under the tile $tile", overlaps(it, tile)) }
        // The back pill is untouched at the left.
        assertEquals(ActivityPageInsetDp, bounds(NowPlayingBackPillTag).x, 0.6f)
    }

    @Test
    fun `with an app leased the Now Playing clock and total clear the tile and no control is under it`() = assertNowPlayingClearOfTile(1.0f)

    @Test
    fun `with an app leased the Now Playing clock and total clear the tile at font scale 1 point 3`() = assertNowPlayingClearOfTile(1.3f)

    @Test
    fun `with no app leased the Now Playing header is exactly where it was, and leasing moves only the clock column`() {
        takeoverKey = nowPlayingSessionKey(session())
        mount(1.0f, surface())
        val clock0 = bounds(NowPlayingClockTag)
        val total0 = bounds(NowPlayingTotalTag)
        val back0 = bounds(NowPlayingBackPillTag)
        val stage0 = bounds(NowPlayingStageTag)
        val title0 = bounds(NowPlayingTitleTag)
        val actions0 = bounds(NowPlayingActionsTag)
        assertEquals("no tile, so the clock is at the page's 18 dp inset as it always was", hostWidthDp - ActivityPageInsetDp, clock0.right, 1f)
        assertEquals(hostWidthDp - ActivityPageInsetDp, total0.right, 1f)
        lease()
        assertEquals("leasing moves the clock left by exactly the reserve", clock0.right - ActivityRecentAppReserveDp, bounds(NowPlayingClockTag).right, 1f)
        // Nothing else moves: the back pill, the stage, the title and the actions stand where they stood.
        assertEquals(back0.x, bounds(NowPlayingBackPillTag).x, 0.6f)
        assertEquals(back0.y, bounds(NowPlayingBackPillTag).y, 0.6f)
        assertEquals(stage0.y, bounds(NowPlayingStageTag).y, 0.6f)
        assertEquals(stage0.w, bounds(NowPlayingStageTag).w, 0.6f)
        assertEquals(title0.y, bounds(NowPlayingTitleTag).y, 0.6f)
        assertEquals(actions0.y, bounds(NowPlayingActionsTag).y, 0.6f)
        // And taking the lease away puts the clock back.
        leased = null
        settle()
        assertEquals(clock0.right, bounds(NowPlayingClockTag).right, 1f)
    }

    // ── the achievements hub ─────────────────────────────────────────────────────────

    private fun assertHubClearOfTile(fontScale: Float) {
        takeoverKey = nowPlayingSessionKey(session())
        mount(fontScale, surface())
        openHub()
        // No lease first: the row's last control (the console chip) ends at the page's 20 dp inset, as before the slot existed.
        val console0 = bounds(AchievementFilterConsoleTag)
        assertEquals("no tile: the console chip's target ends 20 dp from the end", hostWidthDp - 20f, console0.right, 0.8f)
        lease()
        val tile = tile()
        val console = bounds(AchievementFilterConsoleTag)
        val show = bounds(AchievementFilterShowTag)
        println("SLOT-HUB font=$fontScale tile=$tile console=$console show=$show search=${bounds(AchievementFilterSearchTag)} back=${bounds(NowPlayingBackPillTag)}")
        assertFalse("the console chip ($console) is not under the tile ($tile)", overlaps(console, tile))
        assertTrue("it ends at least 82 dp from the end, clear of the tile's near edge: $console", console.right <= tile.x - 7.5f)
        assertTrue("and is still a 48 dp target: $console", console.w >= 47.5f && console.h >= 47.5f)
        assertFalse("the Show chip is not under the tile", overlaps(show, tile))
        assertFalse("the search pill is not under the tile", overlaps(bounds(AchievementFilterSearchTag), tile))
        assertFalse("nor the back pill", overlaps(bounds(NowPlayingBackPillTag), tile))
        clickablesApartFromTile().forEach { assertFalse("a control $it is not under the tile $tile", overlaps(it, tile)) }
        // The search pill gave up the room: it is still a real target, not squeezed to nothing.
        assertTrue("the search pill keeps at least 48 dp: ${bounds(AchievementFilterSearchTag)}", bounds(AchievementFilterSearchTag).w >= 47.5f)
        // Taking the lease away gives the row back.
        leased = null
        settle()
        assertEquals(console0.right, bounds(AchievementFilterConsoleTag).right, 0.8f)
    }

    @Test
    fun `with an app leased the achievements hub's Show chip, console chip, search and every control clear the tile`() = assertHubClearOfTile(1.0f)

    @Test
    fun `with an app leased the achievements hub clears the tile at font scale 1 point 3`() = assertHubClearOfTile(1.3f)

    // ── while a game runs, the hub is the session's page: Back reads Session and opens on that game's card ──────────

    private fun runes(): String? =
        nodes(AchievementHubRunesTag).firstOrNull()?.config?.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()

    private fun backWords(): List<String> = textsInside(bounds(NowPlayingBackPillPlateTag)).map { it.first }

    /** The running game's snapshot is the OLDEST of three, so by the date of its unlock it is the third card, not the first. */
    private val runningGameLast = listOf(
        snapshot(1, "Sonic the Hedgehog 2", 12, 40, 1_650_000_000_000L),
        snapshot(2, "Streets of Rage 2", 5, 50, 1_690_000_000_000L),
        snapshot(3, "Super Metroid", 40, 40, 1_680_000_000_000L),
    )

    @Test
    fun `while a game runs the hub's Back pill reads Session and returns to that game's Now Playing page`() {
        takeoverKey = nowPlayingSessionKey(session())
        mount(1.0f, surface())
        openHub()
        assertEquals("the pill says Session, not Activity: ${backWords()}", listOf("‹", "Session"), backWords())
        val back = compose.onAllNodes(
            SemanticsMatcher("is the Back pill") { it.config.getOrNull(SemanticsProperties.TestTag) == NowPlayingBackPillTag },
            useUnmergedTree = true,
        ).fetchSemanticsNodes().first()
        assertEquals("and a screen reader hears it", "Back to Session", back.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull())
        compose.onNodeWithTag(NowPlayingBackPillTag, useUnmergedTree = true).performClick()
        settle()
        assertFalse("the hub is gone", hasTag(AchievementHubCardTag))
        assertTrue("and the running game's Now Playing page is back", hasTag(NowPlayingTakeoverTag))
        assertEquals("on the same session", nowPlayingSessionKey(session()), takeoverKey)
    }

    @Test
    fun `Now Playing then Achievements opens on the running game's own card, not on card 1`() {
        takeoverKey = nowPlayingSessionKey(session())
        mount(1.0f, surface(achievements = runningGameLast))
        openHub()
        assertEquals("the running game's unlock is the oldest, so it is the third of three", "Game 3 of 3", runes())
        val shown = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.Text].joinToString("") }
        assertTrue("Sonic's card is the one on show: $shown", "Sonic the Hedgehog 2" in shown)
        assertFalse("not the first card in the order: $shown", "Streets of Rage 2" in shown)
    }

    @Test
    fun `the game last played is the library's, so the hub puts the running game first when it was played most recently`() {
        takeoverKey = nowPlayingSessionKey(session())
        mount(1.0f, surface(achievements = runningGameLast, lastPlayedMillis = 1_700_000_000_000L))
        openHub()
        assertEquals("played after every other game's last unlock, so it is card 1 though its own unlock is the oldest", "Game 1 of 3", runes())
    }

    // ── the Back pill ────────────────────────────────────────────────────────────────

    private fun hub(): @Composable () -> Unit = {
        CompanionActivityAchievementHub(
            snapshots = snapshots,
            unknownState = com.gruning.mimir.rewrite.core.ui.MimirAchievementCardState.Unknown(MimirAchievementCopy.NoAnswerYet),
            navigationEnabled = true,
            onBack = {},
            details = MutableStateFlow<Map<Int, ActivityCardDetail>>(emptyMap()),
            requestDetail = {},
            filterHolder = ActivityAchievementFilterHolder(),
            nowMillis = { 1_700_000_000_000L },
        )
    }

    private fun textsInside(plate: Box4): List<Pair<String, Box4>> =
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.Text].joinToString("") to it.dp() }
            .filter { (_, b) -> b.x >= plate.x - 0.5f && b.right <= plate.right + 0.5f && b.y >= plate.y - 0.5f && b.bottom <= plate.bottom + 0.5f }

    private fun assertCompactBackPill(fontScale: Float) {
        mount(fontScale, hub())
        val target = bounds(NowPlayingBackPillTag)
        val plate = bounds(NowPlayingBackPillPlateTag)
        val chipPlate = nodes(AchievementFilterChipPlateTag).first().dp()
        println("SLOT-BACKPILL font=$fontScale target=$target plate=$plate chipPlate=$chipPlate")
        assertEquals("the plate is as tall as a filter chip's plate (±1 dp): $plate vs $chipPlate", chipPlate.h, plate.h, 1f)
        assertTrue("the visual is smaller than the 48 dp target: $plate in $target", plate.h < target.h - 4f)
        assertTrue("the touch target is at least 48 dp each way: $target", target.h >= 47.5f && target.w >= 47.5f)
        assertTrue("the plate sits inside the target: $plate in $target", plate.x >= target.x - 0.5f && plate.right <= target.right + 0.5f && plate.y >= target.y - 0.5f && plate.bottom <= target.bottom + 0.5f)
        assertEquals("the plate is centred in its target, across: $plate in $target", target.cx, plate.cx, 1f)
        assertEquals("and down: $plate in $target", target.cy, plate.cy, 1f)
        val words = textsInside(plate)
        assertEquals("the plate holds the chevron and the label: $words", listOf("‹", "Activity"), words.map { it.first })
        val (chevron, label) = words[0].second to words[1].second
        assertEquals("the label's centre line is the plate's (±1 dp): $label in $plate", plate.cy, label.cy, 1f)
        assertEquals("and so is the chevron's: $chevron in $plate", plate.cy, chevron.cy, 1f)
        assertEquals("one gap, 4 dp, between the chevron and the label", MimirSpacing.xs.value, label.x - chevron.right, 0.8f)
        assertEquals("the plate's padding is a chip's, 12 dp at the start: $plate / $chevron", MimirSpacing.md.value, chevron.x - plate.x, 0.8f)
        assertEquals("and 12 dp at the end: $plate / $label", MimirSpacing.md.value, plate.right - label.right, 0.8f)
        // The whole target is the control: a tap on the plate and one in the hit area outside it are the same press.
        compose.onNodeWithTag(NowPlayingBackPillTag).performClick()
    }

    @Test
    fun `the Back pill is a compact plate as tall as a filter chip inside a 48 dp target, its words on one centre line`() = assertCompactBackPill(1.0f)

    @Test
    fun `the Back pill keeps its compact plate and 48 dp target at font scale 1 point 3`() = assertCompactBackPill(1.3f)

    @Test
    fun `the Back pill is an unselected etched chip like the filter chips, with no fill, tint or accent colour of its own`() {
        val source = (java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/CompanionActivityGameDetail.kt").takeIf { it.isFile }
            ?: java.io.File("app/src/main/kotlin/com/gruning/mimir/rewrite/CompanionActivityGameDetail.kt")).readText()
        val start = source.indexOf("internal fun NowPlayingBackPill(")
        val textFun = source.indexOf("private fun BackPillText(")
        assertTrue("the pill and its word helper are found", start in 0 until textFun)
        // From the pill to the end of the helper that draws its words (the next KDoc after the helper starts the next piece).
        val body = source.substring(start, source.indexOf("\n/**", textFun).takeIf { it > 0 } ?: source.length)
        assertTrue("the plate is the filter chips' own etched metal, unselected", body.contains(".mimirEtchedChipSurface(palette, warmed = false)"))
        assertFalse("never warmed", body.contains("warmed = true"))
        listOf(".background(", ".clip(", "accent.copy(", "accentBright", "0.22f", "RoundedCornerShape").forEach {
            assertFalse("the Back pill has no fill or tint of its own ($it)", body.contains(it))
        }
        assertTrue("the chip's padding", body.contains(".padding(horizontal = MimirSpacing.md, vertical = MimirSpacing.sm)"))
        assertTrue("the chip's label style and weight", body.contains("MimirTypeScale.SupportCompact") && body.contains("FontWeight.SemiBold"))
        assertTrue("the chip's text colour, on the chevron and the label alike", body.split("palette.materials.hero.onCover").size - 1 == 2)
        // And the chip it copies is the same: the same modifier, padding and colour, unselected.
        val header = (java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/CompanionActivityAchievementFilterHeader.kt").takeIf { it.isFile }
            ?: java.io.File("app/src/main/kotlin/com/gruning/mimir/rewrite/CompanionActivityAchievementFilterHeader.kt")).readText()
        assertTrue(header.contains(".mimirEtchedChipSurface(palette, warmed = selected, focused = plateFocused, edgeFlush = true)") && header.contains(".padding(horizontal = MimirSpacing.md, vertical = MimirSpacing.sm)"))
        assertTrue(header.contains("if (selected) palette.accentBright else palette.materials.hero.onCover"))
    }

    @Test
    fun `the Back pill on the Now Playing header is the same compact plate in a 48 dp target`() {
        takeoverKey = nowPlayingSessionKey(session())
        mount(1.0f, surface())
        val target = bounds(NowPlayingBackPillTag)
        val plate = bounds(NowPlayingBackPillPlateTag)
        assertTrue("the visual is smaller than the target: $plate in $target", plate.h < target.h - 4f)
        assertTrue("a 48 dp target each way: $target", target.h >= 47.5f && target.w >= 47.5f)
        assertEquals(target.cy, plate.cy, 1f)
        assertEquals("the target starts at the page's 18 dp inset, where the pill always started", ActivityPageInsetDp, target.x, 0.6f)
    }

    // ── the hub's top row is one row of equal plates ─────────────────────────────────

    /** The plate drawn inside the Show chip's own 48 dp target (the console chip shares the plate tag, so it is picked by its ancestor). */
    private fun showPlate(): Box4 =
        compose.onAllNodes(
            androidx.compose.ui.test.hasTestTag(AchievementFilterChipPlateTag)
                .and(androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag(AchievementFilterShowTag))),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().first().dp()

    private fun assertEqualPlates(fontScale: Float) {
        mount(fontScale, hub())
        val back = bounds(NowPlayingBackPillPlateTag)
        val search = bounds(AchievementFilterSearchPlateTag)
        val sort = showPlate()
        println("SLOT-PLATES font=$fontScale back=$back search=$search show=$sort searchTarget=${bounds(AchievementFilterSearchTag)}")
        assertEquals("the search plate is the Show chip's height (±1 dp): $search vs $sort", sort.h, search.h, 1f)
        assertEquals("the Back plate is the Show chip's height (±1 dp): $back vs $sort", sort.h, back.h, 1f)
        assertEquals("the search plate is on the row's centre line: $search vs $sort", sort.cy, search.cy, 1f)
        assertEquals("and so is the Back plate: $back vs $sort", sort.cy, back.cy, 1f)
        // The hit boxes did not shrink with the plate.
        val searchTarget = bounds(AchievementFilterSearchTag)
        assertTrue("the search target is still 48 dp tall: $searchTarget", searchTarget.h >= 47.5f)
        assertTrue("and the plate sits inside the pill's hit row: $search in $searchTarget", search.h < searchTarget.h - 4f && search.cy >= searchTarget.y && search.cy <= searchTarget.bottom)
        // Opening the keyboard through the plate still works: the whole pill is the control.
        compose.onNodeWithTag(AchievementFilterSearchTag, useUnmergedTree = true).performClick()
    }

    @Test
    fun `the Back pill, the search pill and the Show chip are plates of one height at font scale 1 point 0`() = assertEqualPlates(1.0f)

    @Test
    fun `the Back pill, the search pill and the Show chip are plates of one height at font scale 1 point 3`() = assertEqualPlates(1.3f)

    // ── the tile steps aside for a layer that closes on a tap outside it ─────────────

    /** The Presentation's shape: the achievements hub, and beside it the surface that draws the tile, told to hide it while a layer is open. */
    private fun hubBesideTileOverlay(): @Composable () -> Unit = {
        Box(Modifier.requiredSize(hostWidthDp.dp, hostHeightDp.dp)) {
            // The workspace surface gives every scene it hosts the slot's reserve while an app is leased; this page stands beside it, so it is given the same.
            CompositionLocalProvider(LocalBottomHeaderTrailingReserve provides bottomHeaderTrailingReserveFor(recentAppShown = true)) { hub()() }
            CompanionWorkspaceSurface(
                page = CompanionWorkspacePage.ACTIVITY,
                navigationEnabled = true,
                recentApp = chrome,
                game = null,
                media = CompanionMediaUiState.NoActiveMedia,
                onRecentApp = {},
                onReturnToGame = null,
                onMediaAccess = {},
                onMediaAction = { _, _, _ -> },
                showActivityContent = false,
                showRecentAppOverlay = !BottomRecentAppTileCover.covered,
            )
        }
    }

    private fun tileShown(): Boolean =
        compose.onAllNodes(
            SemanticsMatcher("is the recent-app tile") { node ->
                node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Open recent app Chrome") == true
            },
            useUnmergedTree = true,
        ).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `the tile is not drawn while the console picker is open, so a tap in the corner cannot open the app, and it returns when the picker closes`() {
        mount(1.0f, hubBesideTileOverlay())
        assertTrue("the tile is drawn over the hub at rest", tileShown())
        compose.onNodeWithTag(AchievementFilterConsoleTag, useUnmergedTree = true).performClick()
        // Twice: the picker's effect writes the cover while the clock advances, and that write needs its own announcement before the overlay recomposes.
        settle()
        settle()
        assertTrue("the console picker is open", hasTag(AchievementFilterConsoleListTag))
        assertFalse("and the tile is gone", tileShown())
        assertTrue("the open layer holds the cover", BottomRecentAppTileCover.covered)
        compose.onNodeWithTag(achievementFilterConsoleItemTag(null), useUnmergedTree = true).performClick()
        settle()
        settle()
        assertFalse("the picker is closed", hasTag(AchievementFilterConsoleListTag))
        assertTrue("and the tile is back", tileShown())
        assertFalse("the cover is released", BottomRecentAppTileCover.covered)
    }

    @Test
    fun `a cover is released once and the count never goes below zero`() {
        val release = Snapshot.withMutableSnapshot { BottomRecentAppTileCover.cover() }
        assertTrue(BottomRecentAppTileCover.covered)
        Snapshot.withMutableSnapshot { release(); release() }
        assertFalse(BottomRecentAppTileCover.covered)
    }

    @Test
    fun `the Presentation hides the tile while the Activity launch card is open`() {
        val source = (java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/MimirCompanionPresentation.kt").takeIf { it.isFile }
            ?: java.io.File("app/src/main/kotlin/com/gruning/mimir/rewrite/MimirCompanionPresentation.kt")).readText()
        assertTrue(
            "the overlay surface draws the tile only with no launch card and no covering layer open",
            source.contains("showRecentAppOverlay = activityLaunchCardGame == null && !BottomRecentAppTileCover.covered,"),
        )
        assertFalse("and no surface asks for the tile unconditionally", source.contains("showRecentAppOverlay = true"))
    }

    // ── the "Achievement earned" toast never covers a control on the page that is showing ────────────────────────────

    private val toastFlow = MutableStateFlow<AchievementToast?>(null)
    private val toast = AchievementToast(key = 1, raGameId = 101, gameTitle = "Sonic the Hedgehog 2", count = 1, title = "Ring Collector", points = 10, badgePath = null)

    /** The cards of the hub know their RetroAchievements game, so a loaded detail (below) can give the first card its badge row. */
    private val knownSnapshots = snapshots.mapIndexed { i, game -> game.copy(raGameId = 101 + i) }

    private fun withToastHost(page: @Composable () -> Unit): @Composable () -> Unit = {
        page()
        AchievementToastHost(toasts = toastFlow, onDone = {}, badgeSource = NoActivityBadgeArt)
    }

    private fun badgeRowDetail() {
        val achievements = (1..8).map { n ->
            RetroAchievementsAchievement(
                id = n,
                title = "Achievement $n",
                description = "d$n",
                points = 5 * n,
                unlocked = n <= 5,
                unlockedHardcore = false,
                badgeName = "%05d".format(n),
                earnedAt = if (n <= 5) "2026-10-0$n 12:00:00" else null,
            )
        }
        CompanionActivityCardDetails.sink.value = mapOf(
            101 to ActivityCardDetail.Loaded(RetroAchievementsProgress(gameId = 101, title = "Sonic the Hedgehog 2", achievements = achievements), nowMs),
        )
    }

    private fun showToast() {
        toastFlow.value = toast
        settle()
        assertTrue("the toast is up", hasTag(AchievementToastTag))
    }

    /**
     * The rule : the toast never intersects any clickable on the page that is showing, the recent-app tile included. Full-screen backdrops (a node as big as the
     * page itself is the page's ground, not a button) are not controls. Every clickable is printed, with the toast and the band the page reported, as `TOAST-CLEAR`.
     */
    private fun assertToastClearOfControls(page: String, expectBandInside: Boolean = true) {
        val toastBox = bounds(AchievementToastTag)
        val band = AchievementToastBands.current(androidx.compose.ui.geometry.Rect(0f, 0f, 1240f, 1080f))
        println("TOAST-CLEAR $page toast=$toastBox band=${band?.let { "%.1f..%.1f x %.1f..%.1f dp %s".format(it.rect.left / density369, it.rect.right / density369, it.rect.top / density369, it.rect.bottom / density369, it.vertical) }}")
        val clickables = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick), useUnmergedTree = true).fetchSemanticsNodes().map { it.dp() }
        val controls = clickables.filterNot { it.w >= hostWidthDp * 0.9f && it.h >= hostHeightDp * 0.9f }
        controls.forEach { println("TOAST-CLEAR $page   clickable $it") }
        assertTrue("$page: the page has controls to be clear of (found ${controls.size})", controls.size >= 3)
        val hit = controls.filter { overlaps(it, toastBox) }
        assertTrue("$page: the toast $toastBox crosses ${hit.size} control(s): $hit", hit.isEmpty())
        assertTrue("$page: the toast is on the screen: $toastBox", toastBox.x >= -0.5f && toastBox.right <= hostWidthDp + 0.5f && toastBox.y >= -0.5f && toastBox.bottom <= hostHeightDp + 0.5f)
        if (expectBandInside) {
            assertTrue("$page: the page reported its band", band != null)
            val rect = band!!.rect
            assertTrue(
                "$page: the toast stands inside the page's band horizontally: ${toastBox.x}..${toastBox.right} vs ${rect.left / density369}..${rect.right / density369}",
                toastBox.x >= rect.left / density369 - 0.6f && toastBox.right <= rect.right / density369 + 0.6f,
            )
            when (band.vertical) {
                AchievementToastVertical.Top -> assertEquals("$page: it hangs from the band's top", rect.top / density369, toastBox.y, 1.5f)
                AchievementToastVertical.Center -> assertEquals("$page: it is centred on the band's height", rect.center.y / density369, toastBox.cy, 1.5f)
            }
        }
    }

    private fun assertNowPlayingToast(fontScale: Float, withTile: Boolean) {
        takeoverKey = nowPlayingSessionKey(session())
        mount(fontScale, withToastHost(surface()))
        assertTrue("the takeover is shown", hasTag(NowPlayingTakeoverTag))
        if (withTile) lease()
        showToast()
        val toastBox = bounds(AchievementToastTag)
        val back = bounds(NowPlayingBackPillTag)
        val clock = bounds(NowPlayingClockTag)
        println("TOAST-CLEAR NowPlaying font=$fontScale tile=$withTile back=$back clock=$clock actions=${bounds(NowPlayingActionsTag)}")
        assertTrue("the toast stands between the Back chip and the clock: $toastBox vs $back / $clock", toastBox.x >= back.right - 0.6f && toastBox.right <= clock.x + 0.6f)
        // Nor does it hide the words of the clock's column (the total under the clock is the wider of the two): the gap ends where the whole column begins.
        assertFalse("the toast ($toastBox) is not over the clock ($clock)", overlaps(toastBox, clock))
        if (hasTag(NowPlayingTotalTag)) {
            val total = bounds(NowPlayingTotalTag)
            assertFalse("the toast ($toastBox) is not over the total ($total)", overlaps(toastBox, total))
        }
        assertToastClearOfControls("NowPlaying font=$fontScale tile=$withTile")
    }

    @Test
    fun `at font 1_0 the toast on Now Playing stands in the header's gap and crosses no control`() = assertNowPlayingToast(1.0f, withTile = false)

    @Test
    fun `at font 1_3 the toast on Now Playing stands in the header's gap and crosses no control`() = assertNowPlayingToast(1.3f, withTile = false)

    @Test
    fun `at font 1_3 with an app leased the toast on Now Playing still crosses no control, the tile included`() = assertNowPlayingToast(1.3f, withTile = true)

    private fun assertHubToast(fontScale: Float, withTile: Boolean) {
        takeoverKey = nowPlayingSessionKey(session())
        badgeRowDetail()
        mount(fontScale, withToastHost(surface(achievements = knownSnapshots)))
        openHub()
        if (withTile) lease()
        showToast()
        println("TOAST-CLEAR Hub font=$fontScale tile=$withTile count=${bounds(AchievementFilterCountTag)} console=${bounds(AchievementFilterConsoleTag)} show=${bounds(AchievementFilterShowTag)} search=${bounds(AchievementFilterSearchTag)}")
        assertToastClearOfControls("Hub font=$fontScale tile=$withTile")
    }

    @Test
    fun `at font 1_0 the toast on the achievements hub stands on the count row and crosses no control of the top row, the card or its badge row`() = assertHubToast(1.0f, withTile = false)

    @Test
    fun `at font 1_3 the toast on the achievements hub stands on the count row and crosses no control of the top row, the card or its badge row`() = assertHubToast(1.3f, withTile = false)

    @Test
    fun `at font 1_3 with an app leased the toast on the achievements hub still crosses no control`() = assertHubToast(1.3f, withTile = true)

    private fun assertActivityToast(fontScale: Float, withTile: Boolean) {
        takeoverKey = null
        mount(fontScale, withToastHost(surface()))
        if (withTile) lease()
        showToast()
        assertToastClearOfControls("Activity font=$fontScale tile=$withTile")
    }

    @Test
    fun `at font 1_0 the toast on the Activity page stands in its header and crosses no control`() = assertActivityToast(1.0f, withTile = false)

    @Test
    fun `at font 1_3 the toast on the Activity page stands in its header and crosses no control`() = assertActivityToast(1.3f, withTile = false)

    @Test
    fun `at font 1_3 with an app leased the toast on the Activity page still crosses no control, the tile included`() = assertActivityToast(1.3f, withTile = true)
}
