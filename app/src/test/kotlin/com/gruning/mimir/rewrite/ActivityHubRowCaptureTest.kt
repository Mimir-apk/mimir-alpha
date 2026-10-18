package com.gruning.mimir.rewrite

import android.animation.ValueAnimator
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.model.CompanionMode
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.ui.MimirAchievementCardState
import com.gruning.mimir.rewrite.core.ui.MimirAchievementCopy
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pictures of the Bottom's achievement pages : the real [CompanionActivityAchievementHub]'s ONE top row at 537.7 x 468.3 dp with the header slot's 78 dp reserve in
 * place (with nothing chosen, and with the Genesis console chosen under Show: In progress, where the search has shrunk to its magnifier), the Now Playing header, and Now Playing with
 * the "Achievement earned" toast showing in its header gap. Drawn the way this repo rasterises (the view hierarchy into a software bitmap; `captureToImage` waits on a redraw the paused
 * looper never delivers). The PNGs land in `build/test-captures` (the build folder's temp directory on a machine with no `build/test-captures`). Pixel values are the JVM's; the pixels on a handheld are
 * device-owed.
 *
 * The class runs as API 30, the level at which the platform cannot blur ([com.gruning.mimir.rewrite.core.ui.MimirGlass.supportsBlur]): the hub's page wrapper otherwise records its
 * blur as RenderNode layers, which a software canvas cannot draw. The pictures are of pages at rest, which have no blur in them either way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActivityHubRowCaptureTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val temp = TemporaryFolder()

    private val density369 = 369f / 160f
    private val now = 1_700_000_000_000L
    private val day = 24L * 60L * 60L * 1000L

    @Before
    fun clearBands() = AchievementToastBands.resetForTest()

    @After
    fun resetAnimators() {
        setAnimatorScale(1f)
        AchievementToastBands.resetForTest()
    }

    private fun setAnimatorScale(scale: Float) {
        ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, scale)
    }

    private fun snap(n: Int, title: String, unlocked: Int, total: Int, console: String, awarded: Long?) = CompanionActivityAchievementSnapshot(
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
    )

    private val games = listOf(
        snap(1, "Chrono Cross", 5, 40, "PlayStation", now - 2 * day),
        snap(2, "Super Metroid", 40, 40, "SNES", now - 40 * day),
        snap(3, "Sonic the Hedgehog 2", 12, 50, "Genesis/Mega Drive", now - 5 * day),
        snap(4, "Streets of Rage 2", 8, 50, "Genesis/Mega Drive", now - 9 * day),
    )

    private fun settleFrame() {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(1_000L)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    private fun drawWindow(): android.graphics.Bitmap {
        val decor = compose.activity.window.decorView
        val full = android.graphics.Bitmap.createBitmap(decor.width, decor.height, android.graphics.Bitmap.Config.ARGB_8888)
        decor.draw(android.graphics.Canvas(full))
        return full
    }

    private fun assertDrew(band: android.graphics.Bitmap, what: String) {
        val ground = band.getPixel(2, band.height - 2)
        var differing = 0
        for (y in 0 until band.height step 2) for (x in 0 until band.width step 2) if (band.getPixel(x, y) != ground) differing += 1
        assertTrue("$what drew something: $differing sampled pixels differ from the ground", differing > 300)
    }

    private fun write(band: android.graphics.Bitmap, fileName: String, tag: String) {
        val outDir = File("build/test-captures").takeIf { it.isDirectory } ?: temp.root
        val out = File(outDir, fileName)
        out.outputStream().use { band.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        println("$tag wrote ${out.path} (${out.length()} bytes)")
        assertTrue("the PNG was written: ${out.path}", out.isFile && out.length() > 1_000L)
    }

    private fun capture(font: Float, fileName: String, filter: ActivityAchievementFilter = ActivityAchievementFilter()) {
        setAnimatorScale(0f)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density369, font), LocalBottomHeaderTrailingReserve provides 78.dp) {
                MimirTheme {
                    Box(Modifier.requiredSize((1240f / density369).dp, (1080f / density369).dp)) {
                        CompanionActivityAchievementHub(
                            snapshots = games,
                            unknownState = MimirAchievementCardState.Unknown(MimirAchievementCopy.NoAnswerYet),
                            navigationEnabled = true,
                            onBack = {},
                            details = MutableStateFlow<Map<Int, ActivityCardDetail>>(emptyMap()),
                            requestDetail = {},
                            filterHolder = ActivityAchievementFilterHolder(filter),
                            nowMillis = { now },
                        )
                    }
                }
            }
        }
        settleFrame()

        val full = drawWindow()
        // The band is cut from the row's own place (16 dp above it to 12 dp below), the whole width, so its right end (the reserve's room) shows too.
        val header = compose.onAllNodesWithTag(AchievementFilterHeaderTag, useUnmergedTree = true).fetchSemanticsNodes().first().boundsInWindow
        val top = maxOf(0, (header.top - 16f * density369).toInt())
        val bottom = minOf(full.height, (header.bottom + 12f * density369).toInt())
        val band = android.graphics.Bitmap.createBitmap(full, 0, top, full.width, bottom - top)
        println("HUB-ROW font=$font decor=${full.width}x${full.height} px, header node ${header.left},${header.top} to ${header.right},${header.bottom} px, band y=$top..$bottom")
        assertTrue("the row lies inside the cropped band: $header vs y=$top..$bottom", header.top >= top && header.bottom <= bottom && header.right <= full.width)
        assertDrew(band, "the row")
        write(band, fileName, "HUB-ROW")
    }

    @Test
    fun `the top row is captured at font 1_0`() = capture(1.0f, "hub-row-1.0.png")

    @Test
    fun `the top row is captured at font 1_3`() = capture(1.3f, "hub-row-1.3.png")

    /** The console is RetroAchievements' "Genesis/Mega Drive"; the chip calls it by the lattice's name, "Genesis / Mega Drive", or draws the lattice's glyph where that has no room. */
    private val genesisInProgress = ActivityAchievementFilter(status = ActivityAchievementStatus.IN_PROGRESS, console = "Genesis/Mega Drive")

    @Test
    fun `the top row with the Genesis console chosen under In progress is captured at font 1_0`() = capture(1.0f, "hub-row-console-1.0.png", genesisInProgress)

    @Test
    fun `the top row with the Genesis console chosen under In progress is captured at font 1_3`() = capture(1.3f, "hub-row-console-1.3.png", genesisInProgress)

    private val session = MimirGameSession(
        operation = MimirGameSessionOperation("op-capture"),
        sequence = 1,
        gameId = GameId("g1"),
        title = "Chrono Cross",
        platformId = PlatformId("psx"),
        returnAnchor = MimirGameReturnAnchor.Library(
            com.gruning.mimir.rewrite.feature.library.LibraryLaunchReturnTarget.shelf(
                com.gruning.mimir.rewrite.feature.library.LibraryShelfMode.EMULATION,
                "shelf",
            ),
        ),
        startedAtEpochMs = 1,
        dispatchedAtElapsedMs = 1_000_000L,
        targetPackageName = "org.example.emulator",
        dualScreen = false,
        phase = MimirSessionPhase.YIELDED_TO_SECONDARY_TASK,
    )

    /** Now Playing for the running game, with the toast host over it when [toast] is given. */
    private fun mountNowPlaying(toast: AchievementToast?) {
        setAnimatorScale(0f)
        CompanionNowPlayingState.resetForTest()
        val game = session
        val row = GameRow(gameId = game.gameId, title = game.title, platformId = game.platformId, memberCount = 1, duplicateKind = null, flatArtUri = null, heroArtUri = null)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density369, 1.0f)) {
                MimirTheme {
                    Box(Modifier.requiredSize((1240f / density369).dp, (1080f / density369).dp)) {
                        CompanionActivityGameDetail(
                            game = game,
                            payload = CompanionPayload(
                                mode = CompanionMode.NOW_PLAYING,
                                gameId = game.gameId,
                                title = game.title,
                                platformId = game.platformId,
                                platformLabel = "PlayStation",
                                launcherLabel = "DuckStation",
                            ),
                            row = row,
                            achievements = games.first(),
                            totalBeforeMs = (18L * 60 + 40) * 60_000L,
                            platformLabel = "PlayStation",
                            navigationEnabled = true,
                            onBack = {},
                            onOpenAchievements = {},
                            onReturnToGame = { true },
                            quit = NowPlayingQuit { PrivilegedCloseOutcome.STOPPED },
                            nowElapsedMs = { 1_000_000L + (1L * 3600 + 24 * 60 + 7) * 1000L },
                        )
                        if (toast != null) {
                            AchievementToastHost(toasts = MutableStateFlow<AchievementToast?>(toast), onDone = {}, badgeSource = NoActivityBadgeArt)
                        }
                    }
                }
            }
        }
        settleFrame()
        settleFrame()
    }

    @Test
    fun `the Now Playing header with its Back pill is captured at font 1_0`() {
        mountNowPlaying(toast = null)
        val full = drawWindow()
        val pill = compose.onAllNodesWithTag(NowPlayingBackPillTag, useUnmergedTree = true).fetchSemanticsNodes().first().boundsInWindow
        // The header band: from the window's top to 24 dp under the Back pill, the whole width (the clock stands at the right end).
        val bottom = minOf(full.height, (pill.bottom + 24f * density369).toInt())
        val band = android.graphics.Bitmap.createBitmap(full, 0, 0, full.width, bottom)
        println("NOWPLAYING-HEADER pill=${pill.left},${pill.top} to ${pill.right},${pill.bottom} px, band 0..$bottom of ${full.height}")
        assertTrue("the Back pill lies in the band", pill.bottom <= bottom && pill.right <= full.width)
        assertDrew(band, "the header")
        write(band, "nowplaying-header-1.0.png", "NOWPLAYING-HEADER")
    }

    @Test
    fun `Now Playing with the achievement toast showing in its header gap is captured at font 1_0`() {
        mountNowPlaying(
            toast = AchievementToast(key = 1, raGameId = 101, gameTitle = "Chrono Cross", count = 1, title = "Time Traveller", points = 25, badgePath = null),
        )
        assertTrue("the toast is up", compose.onAllNodesWithTag(AchievementToastTag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        val full = drawWindow()
        val toast = compose.onAllNodesWithTag(AchievementToastTag, useUnmergedTree = true).fetchSemanticsNodes().first().boundsInWindow
        val actions = compose.onAllNodesWithTag(NowPlayingActionsTag, useUnmergedTree = true).fetchSemanticsNodes().first().boundsInWindow
        println("NOWPLAYING-TOAST toast=${toast.left},${toast.top} to ${toast.right},${toast.bottom} px; actions=${actions.left},${actions.top} to ${actions.right},${actions.bottom} px")
        assertTrue("the toast stands above the actions row: $toast vs $actions", toast.bottom <= actions.top)
        assertDrew(full, "the page")
        write(full, "nowplaying-toast-1.0.png", "NOWPLAYING-TOAST")
    }
}
