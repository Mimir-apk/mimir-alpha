package com.gruning.mimir.rewrite

import android.animation.ValueAnimator
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
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
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.model.CompanionAppEntry
import com.gruning.mimir.rewrite.core.model.CompanionMode
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.model.library.launchesPerLocalDay
import com.gruning.mimir.rewrite.core.ui.BottomTrackpadPlate
import com.gruning.mimir.rewrite.core.ui.BottomTrackpadPlateTag
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.MimirCompactHeader
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.LocalBottomTrackpadOpen
import com.gruning.mimir.rewrite.core.ui.MimirBottomMarkDefaultTopDp
import com.gruning.mimir.rewrite.core.ui.MimirCompactHeaderRowTag
import com.gruning.mimir.rewrite.core.ui.MimirCompactHeaderTag
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Bottom's trailing header cluster, with the coin free to go anywhere (policy 2026-10-09, which retired the 2026-10-08 corner slot): every Bottom page's header ends
 * with the Trackpad plate and the last-app tile at the header's end, with no empty band where the coin used to rest, and nothing reserved for the coin. Mounted on the real
 * workspace surface at the Thor Bottom's 1240 x 1080 px at 369 dpi (537.7 x 468.3 dp), at font scale 1.0 and 1.3, with and without an app leased to the tile.
 *
 * Held, for the Activity page, Now Playing and the achievements hub: the cluster's last control ends at the header's end inset (or within the tile's own 6 dp of it), no gap in
 * the cluster is wider than the normal spacing, the controls are one line each, the tile never overlaps a control, and the header ends above the coin's default seat
 * ([MimirBottomMarkDefaultTopDp]) so an unmoved coin covers no header control. The Activity header is saved as \`build/reports/hdr-activity-1.0.png\` (the path is printed).
 * Pixels are the JVM's; the handheld's are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BottomHeaderClusterRenderTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val density369 = 369f / 160f
    private val hostWidthDp = 1240f / density369
    private val hostHeightDp = 1080f / density369

    private val startMs = 1_000_000L
    private val clockMs = (1L * 3600 + 24 * 60 + 7) * 1000L
    private var nowMs by mutableLongStateOf(startMs + clockMs)
    private var takeoverKey by mutableStateOf<String?>(null)
    private var leased by mutableStateOf<CompanionAppEntry?>(null)
    private val chrome = CompanionAppEntry(packageName = "com.android.chrome", label = "Chrome")

    private val zone = ZoneId.of("UTC")
    private val now = Instant.parse("2026-10-02T12:00:00Z").toEpochMilli()
    private val day = 24L * 60L * 60L * 1000L

    @After
    fun restoreAnimatorScale() = setAnimatorScale(1f)

    private fun setAnimatorScale(scale: Float) {
        ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, scale)
    }

    @Before
    fun setUp() {
        CompanionNowPlayingState.resetForTest()
        write {
            nowMs = startMs + clockMs
            takeoverKey = null
            leased = null
        }
    }

    /** A bare write to Compose state outside the composition poisons every later Compose class in the fork: always through a mutable snapshot. */
    private fun write(block: () -> Unit) = Snapshot.withMutableSnapshot(block)

    // ── fixtures ─────────────────────────────────────────────────────────────────────

    private fun gameRow() = GameRow(
        gameId = GameId("g1"),
        title = "Sonic the Hedgehog 2",
        platformId = PlatformId("genesis"),
        memberCount = 1,
        duplicateKind = null,
        flatArtUri = null,
        heroArtUri = null,
    )

    private fun session() = MimirGameSession(
        operation = MimirGameSessionOperation("op-cluster"),
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

    private fun launchedGame(id: String, title: String, plays: Int, lastDaysAgo: Long) = GameRow(
        gameId = GameId(id),
        title = title,
        platformId = PlatformId("genesis"),
        memberCount = 1,
        duplicateKind = null,
        playCount = plays,
        lastPlayedMillis = now - lastDaysAgo * day,
        firstPlayedMillis = now - (lastDaysAgo + 100) * day,
        artResolutionSettled = true,
    )

    private val weekCounts = launchesPerLocalDay(
        listOf(now - 6 * day, now - 5 * day, now - 5 * day + 1000, now - 3 * day, now - day, now - day + 2000, now - 3600_000L, now - 7200_000L),
        now,
        zone,
    )

    private fun activityBento() = ActivityBentoData(
        gameCount = 87,
        launched = listOf(
            launchedGame("g1", "Sonic the Hedgehog 2", plays = 14, lastDaysAgo = 1),
            launchedGame("g2", "Streets of Rage 2", plays = 22, lastDaysAgo = 3),
            launchedGame("g3", "Phantasy Star IV", plays = 9, lastDaysAgo = 12),
        ),
        shelfPool = emptyList(),
        week = weekCounts,
        liveGameId = null,
        liveTitle = null,
        liveRow = null,
        nowMillis = now,
        zone = zone,
    )

    private fun snapshot(n: Int, title: String, unlocked: Int, total: Int) = CompanionActivityAchievementSnapshot(
        gameId = "g$n",
        account = "me",
        unlocked = unlocked,
        total = total,
        gameTitle = title,
        checkedAtEpochMillis = 1L,
        rowsKnown = false,
        consoleName = "Genesis/Mega Drive",
        lastAwardedAtMillis = 1_700_000_000_000L,
    )

    private val snapshots = listOf(snapshot(1, "Sonic the Hedgehog 2", 12, 40), snapshot(2, "Streets of Rage 2", 5, 50))

    /** Mounts [content] with the Trackpad gateway the Presentation provides on the Thor (the plate is the header's trailing control). */
    private fun mount(fontScale: Float, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        val openTrackpad: () -> Unit = {}
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density369, fontScale),
                LocalBottomTrackpadOpen provides openTrackpad,
            ) {
                MimirTheme { Box(Modifier.requiredSize(hostWidthDp.dp, hostHeightDp.dp)) { content() } }
            }
        }
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
    }

    /** The production workspace surface on the Activity page (no game live) with the recent-app tile laid over it when [leased] is set. */
    private fun activitySurface(): @Composable () -> Unit = {
        CompanionWorkspaceSurface(
            page = CompanionWorkspacePage.ACTIVITY,
            navigationEnabled = true,
            recentApp = leased,
            game = null,
            media = CompanionMediaUiState.NoActiveMedia,
            onRecentApp = {},
            onReturnToGame = null,
            onMediaAccess = {},
            onMediaAction = { _, _, _ -> },
            achievementSnapshots = snapshots,
            achievementHubAccessEnabled = true,
            bento = activityBento(),
            showRecentAppOverlay = true,
        )
    }

    /** The production workspace surface with the live game's Now Playing takeover open. */
    private fun nowPlayingSurface(): @Composable () -> Unit = {
        val game = session()
        val row = gameRow()
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
            achievementSnapshots = snapshots,
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
                zone = zone,
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
        override fun toString() = "%.1fx%.1f@(%.1f,%.1f)".format(w, h, x, y)
    }

    private fun SemanticsNode.dp(): Box4 {
        val p = positionInRoot
        return Box4(p.x / density369, p.y / density369, size.width / density369, size.height / density369)
    }

    private fun nodes(tag: String): List<SemanticsNode> =
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, tag), useUnmergedTree = true).fetchSemanticsNodes()

    private fun bounds(tag: String): Box4 = nodes(tag).first().dp()

    private fun overlaps(a: Box4, b: Box4, tol: Float = 0.5f) =
        a.x + a.w - tol > b.x && b.x + b.w - tol > a.x && a.y + a.h - tol > b.y && b.y + b.h - tol > a.y

    private fun isTileOrInsideIt(node: SemanticsNode): Boolean {
        var at: SemanticsNode? = node
        while (at != null) {
            if (at.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Open recent app Chrome") == true) return true
            at = at.parent
        }
        return false
    }

    private fun tile(): Box4 =
        compose.onAllNodes(
            SemanticsMatcher("is the recent-app tile") { node ->
                node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Open recent app Chrome") == true
            },
            useUnmergedTree = true,
        ).fetchSemanticsNodes().first().dp()

    /** Every text and every control narrower than most of the page, apart from the tile and what it draws, as a dp box and what it says. */
    private fun textAndControls(): List<Pair<Box4, String>> {
        val wide = hostWidthDp * 0.8f
        return compose.onAllNodes(
            SemanticsMatcher("text or control") { node ->
                SemanticsActions.GetTextLayoutResult in node.config || SemanticsActions.OnClick in node.config
            },
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
            .filterNot(::isTileOrInsideIt)
            .map { node ->
                node.dp() to (
                    node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
                        ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                        ?: node.config.getOrNull(SemanticsProperties.TestTag)
                    ).orEmpty()
            }
            .filter { (box, _) -> box.w > 0.5f && box.h > 0.5f && box.w < wide }
    }

    private fun settle() {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
    }

    private fun lease() {
        write { leased = chrome }
        settle()
    }

    private fun assertNothingUnderTile(tile: Box4) {
        val under = textAndControls().filter { (box, _) -> overlaps(box, tile) }
        assertTrue("nothing the page draws is under the tile $tile: $under", under.isEmpty())
    }

    // ── the Activity page ────────────────────────────────────────────────────────────

    private fun assertActivityCluster(fontScale: Float, withTile: Boolean) {
        mount(fontScale, activitySurface())
        if (withTile) lease()
        val row = bounds(MimirCompactHeaderRowTag)
        val header = bounds(MimirCompactHeaderTag)
        val plate = bounds(BottomTrackpadPlateTag)
        println("HDR-ACTIVITY font=$fontScale tile=$withTile row=$row header=$header plate=$plate" + if (withTile) " tile=${tile()}" else "")
        assertEquals("the row ends at the page's 18 dp end inset", hostWidthDp - 18f, row.right, 1f)
        if (withTile) {
            val tile = tile()
            assertEquals("the tile is 48 dp", 48f, tile.w, 0.6f)
            assertEquals("and 8 dp from the top: in the header's own band", 8f, tile.y, 0.8f)
            assertTrue("the cluster ends within the tile's own 6 dp of the header's end: ${row.right - tile.right}", row.right - tile.right in -0.5f..8f)
            assertTrue("the plate is no further from the tile than the normal spacing: ${tile.x - plate.right}", tile.x - plate.right in -0.5f..(MimirSpacing.sm.value + 0.6f))
            assertNothingUnderTile(tile)
        } else {
            assertEquals("the Trackpad plate is the cluster, and it ends where the row does (no blank band at the top right)", row.right, plate.right, 1f)
        }
        val texts = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true).fetchSemanticsNodes()
            .filter { it.dp().let { box -> box.y >= row.y - 0.5f && box.bottom <= row.bottom + 0.5f } }
        for (node in texts) {
            val layouts = mutableListOf<TextLayoutResult>()
            if (node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts) == true && layouts.isNotEmpty()) {
                assertEquals("'${layouts.first().layoutInput.text.text}' is one line", 1, layouts.first().lineCount)
            }
        }
        assertTrue("the header (to ${header.bottom} dp) ends above the coin's default seat at $MimirBottomMarkDefaultTopDp", header.bottom <= MimirBottomMarkDefaultTopDp - 0.5f)
    }

    @Test
    fun `the Activity header's cluster is the plate at the header's end with no app leased at 1 point 0`() = assertActivityCluster(1.0f, withTile = false)

    @Test
    fun `the Activity header's cluster is the plate and the tile at the header's end at 1 point 0`() = assertActivityCluster(1.0f, withTile = true)

    @Test
    fun `the Activity header's cluster is the plate at the header's end with no app leased at 1 point 3`() = assertActivityCluster(1.3f, withTile = false)

    @Test
    fun `the Activity header's cluster is the plate and the tile at the header's end at 1 point 3`() = assertActivityCluster(1.3f, withTile = true)

    // ── Now Playing ──────────────────────────────────────────────────────────────────

    private fun assertNowPlayingCluster(fontScale: Float, withTile: Boolean) {
        write { takeoverKey = nowPlayingSessionKey(session()) }
        mount(fontScale, nowPlayingSurface())
        if (withTile) lease()
        assertTrue("the takeover is shown", nodes(NowPlayingTakeoverTag).isNotEmpty())
        val clock = bounds(NowPlayingClockTag)
        val total = bounds(NowPlayingTotalTag)
        println("HDR-NOWPLAYING font=$fontScale tile=$withTile clock=$clock total=$total" + if (withTile) " tile=${tile()}" else "")
        if (withTile) {
            val tile = tile()
            assertEquals("the tile is 48 dp, 24 dp from the end", hostWidthDp - 24f - 48f, tile.x, 0.8f)
            assertTrue("the clock is no further from the tile than the normal spacing: ${tile.x - clock.right}", tile.x - clock.right in 0f..(MimirSpacing.sm.value + 0.6f))
            assertNothingUnderTile(tile)
        } else {
            assertEquals("the clock ends at the page's 18 dp end inset: no blank band at the top right", hostWidthDp - 18f, clock.right, 1f)
        }
        assertTrue("the header row (to ${maxOf(clock.bottom, total.bottom)} dp) ends above the coin's default seat at $MimirBottomMarkDefaultTopDp", maxOf(clock.bottom, total.bottom) <= MimirBottomMarkDefaultTopDp - 0.5f)
        val firstLine = compose.onNodeWithTag(NowPlayingClockTag, useUnmergedTree = true).fetchSemanticsNode()
        assertTrue("the clock is one line: ${firstLine.dp()}", firstLine.dp().h < 40f * fontScale)
    }

    @Test
    fun `Now Playing's cluster ends at the header's end with no app leased`() = assertNowPlayingCluster(1.0f, withTile = false)

    @Test
    fun `Now Playing's cluster is the clock and the tile at the header's end`() = assertNowPlayingCluster(1.0f, withTile = true)

    @Test
    fun `Now Playing's cluster is the clock and the tile at font scale 1 point 3`() = assertNowPlayingCluster(1.3f, withTile = true)

    // ── the achievements hub ─────────────────────────────────────────────────────────

    private fun openHub() {
        compose.onNodeWithTag(NowPlayingAchievementsTag).performClick()
        settle()
        assertTrue("the hub is open", nodes(AchievementHubCardTag).isNotEmpty())
    }

    private fun assertHubCluster(fontScale: Float, withTile: Boolean) {
        write { takeoverKey = nowPlayingSessionKey(session()) }
        mount(fontScale, nowPlayingSurface())
        if (withTile) lease()
        openHub()
        val row = textAndControls().filter { (box, _) -> box.y < 64f }
        assertTrue("the hub's top row has its controls: $row", row.isNotEmpty())
        val last = row.maxOf { (box, _) -> box.right }
        val end = if (withTile) tile().x else hostWidthDp - 20f
        println("HDR-HUB font=$fontScale tile=$withTile end=$end last=$last row=$row")
        assertTrue("the row's last control ($last) does not run past the cluster's start ($end)", last <= end + 0.8f)
        assertTrue(
            "and ends no further than the normal spacing from it (no blank band at the top right): ${end - last}",
            end - last <= MimirSpacing.md.value + 0.6f,
        )
        if (withTile) assertNothingUnderTile(tile())
        // Every text in the row is one line: no control wraps or ellipsizes into a second line.
        for (node in compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true).fetchSemanticsNodes().filter { it.dp().bottom <= 64f }) {
            val layouts = mutableListOf<TextLayoutResult>()
            if (node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts) == true && layouts.isNotEmpty()) {
                assertEquals("'${layouts.first().layoutInput.text.text}' is one line", 1, layouts.first().lineCount)
            }
        }
        assertTrue("the hub's top row ends above the coin's default seat", row.maxOf { (box, _) -> box.bottom } <= MimirBottomMarkDefaultTopDp - 0.5f)
    }

    @Test
    fun `the hub's top row ends at the header's end with no app leased`() = assertHubCluster(1.0f, withTile = false)

    @Test
    fun `the hub's top row ends at the tile with an app leased`() = assertHubCluster(1.0f, withTile = true)

    @Test
    fun `the hub's top row ends at the header's end at font scale 1 point 3`() = assertHubCluster(1.3f, withTile = false)

    @Test
    fun `the hub's top row ends at the tile at font scale 1 point 3`() = assertHubCluster(1.3f, withTile = true)

    /**
     * The pictures for the eye: the Activity page's header as the page composes it (the same [MimirCompactHeader] call as ActivityPage's: Activity, the game count, the Trackpad
     * plate and, with an app leased, the tile's 62 dp of room and the tile itself at its 8 dp / 24 dp seat), drawn into a software bitmap under this module's build/reports (never
     * an absolute machine path). The whole surface cannot be rasterised here (the software canvas refuses its render-node layers), so each picture is the header on its own, on the
     * page's ground; the tile's icon is a stand-in letter.
     */
    private fun saveHeaderPicture(name: String, withTile: Boolean) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density369, 1.0f)) {
                MimirTheme {
                    val palette = LocalMimirPalette.current
                    Box(Modifier.requiredSize(hostWidthDp.dp, 84.dp).testTag("hdr-activity-host").background(palette.surfaceBase)) {
                        Column(Modifier.padding(start = ActivityPageInsetDp.dp, top = ActivityPageTopDp.dp, end = ActivityPageInsetDp.dp)) {
                            MimirCompactHeader(
                                title = "Activity",
                                palette = palette,
                                stat = "87 games",
                                trailing = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        BottomTrackpadPlate(onOpen = {})
                                        if (withTile) Spacer(Modifier.width(ActivityRecentAppReserveDp.dp))
                                    }
                                },
                                ruleBleed = ActivityPageInsetDp.dp,
                            )
                        }
                        if (withTile) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = RecentAppTileTopDp.dp, end = RecentAppTileEndDp.dp)
                                    .size(MimirSpacing.minTarget)
                                    .clip(RoundedCornerShape(MimirSpacing.radiusCard))
                                    .background(palette.surfaceHigh.copy(alpha = 0.82f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                MimirChromeText("C", style = MimirTypeScale.TitleCaps, color = palette.textPrimary, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        val decor = compose.activity.window.decorView
        val full = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(full))
        val host = compose.onNodeWithTag("hdr-activity-host").fetchSemanticsNode().boundsInWindow
        val left = host.left.toInt().coerceIn(0, full.width - 1)
        val top = host.top.toInt().coerceIn(0, full.height - 1)
        val band = Bitmap.createBitmap(full, left, top, minOf(host.width.toInt(), full.width - left), minOf(host.height.toInt(), full.height - top))
        val out = File(File(System.getProperty("user.dir"), "build/reports"), name)
        out.parentFile?.mkdirs()
        out.outputStream().use { band.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("HDR-ACTIVITY-PNG ${out.absolutePath}")
        assertTrue("the PNG was written: ${out.absolutePath}", out.isFile && out.length() > 0)
    }

    @Test
    fun `the Activity header with its tile is saved as a picture`() = saveHeaderPicture("hdr-activity-1.0.png", withTile = true)

    @Test
    fun `the Activity header with no app leased is saved as a picture`() = saveHeaderPicture("hdr-activity-notile-1.0.png", withTile = false)
}
