package com.gruning.mimir.rewrite

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.model.CompanionMode
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.ui.MimirPageMotion
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog

/**
 * RC-01, the Bottom half of the session-render defect (Thor 2026-10-14, B-52): the Now Playing takeover's return to the resting layout when the game ends.
 *
 * On the Thor the takeover vanished in ONE frame (`b1r2b_008822` -> `008832`, MAD 39.8), because the page the swap was dissolving had been built
 * from the live session, which is null the frame the game ends: the departing page recomposed to nothing and the ReturnMs dissolve ran on an
 * empty page over a resting layout not yet painted. The presentation now builds the page from the last live session
 * ([companionTakeoverPageGame]); this test drives [CompanionWorkspaceSurface] with both wirings at the Thor Bottom's size and reads the frames.
 *
 * With the page kept: the takeover is still composed and painted on the first frame after the end, the resting page is under it, the frames move
 * toward the resting page monotonically over [MimirPageMotion.ReturnMs] (one dissolve, no cut), and the takeover leaves only when the dissolve is
 * done. With the wiring it replaced: the takeover is gone on the first frame and that frame already IS the resting page. Pixels are the JVM's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionTakeoverDissolveRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val density369 = 369f / 160f
    private val hostWidthDp = 1240f / density369
    private val hostHeightDp = 1080f / density369

    private var takeoverKey by mutableStateOf<String?>(null)
    private var game by mutableStateOf<MimirGameSession?>(null)
    private var rootView: View? = null

    @Before
    fun setUp() {
        CompanionNowPlayingState.resetForTest()
        ShadowLog.clear()
    }

    private val row = GameRow(
        gameId = GameId("g1"),
        title = "Sonic the Hedgehog 2",
        platformId = PlatformId("genesis"),
        memberCount = 1,
        duplicateKind = null,
        flatArtUri = null,
        heroArtUri = null,
    )

    private fun session(phase: MimirSessionPhase) = MimirGameSession(
        operation = MimirGameSessionOperation("op-dissolve"),
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
        dispatchedAtElapsedMs = 1_000_000L,
        targetPackageName = "org.example.emulator",
        dualScreen = false,
        phase = phase,
    )

    /**
     * The workspace surface as the presentation wires it. [keepPage] true is the presentation's wiring ([rememberCompanionTakeoverPageGame], the function
     * the presentation itself calls); false is the wiring it replaced, the page built from the live session alone.
     */
    private fun mount(keepPage: Boolean) {
        game = session(MimirSessionPhase.YIELDED_TO_SECONDARY_TASK)
        takeoverKey = nowPlayingSessionKey(game!!)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density369, 1f)) {
                MimirTheme {
                    rootView = LocalView.current.rootView
                    Box(Modifier.requiredSize(hostWidthDp.dp, hostHeightDp.dp)) {
                        val live = game
                        // The presentation's own wiring (W2L7 review #4), not a copy of it: a presentation reverted to the live session alone fails this test.
                        val pageGame = if (keepPage) rememberCompanionTakeoverPageGame(live) else live
                        CompanionWorkspaceSurface(
                            page = CompanionWorkspacePage.ACTIVITY,
                            navigationEnabled = true,
                            recentApp = null,
                            game = live,
                            media = CompanionMediaUiState.NoActiveMedia,
                            onRecentApp = {},
                            onReturnToGame = null,
                            onMediaAccess = {},
                            onMediaAction = { _, _, _ -> },
                            bento = ActivityBentoData(
                                gameCount = 87,
                                launched = listOf(row),
                                shelfPool = emptyList(),
                                week = null,
                                liveGameId = live?.gameId,
                                liveTitle = live?.title,
                                liveRow = row,
                                nowMillis = 0L,
                                zone = ZoneId.of("UTC"),
                            ),
                            gameCardAvailableGameId = live?.gameId,
                            gameCardSessionKey = live?.let(::nowPlayingSessionKey),
                            takeoverKey = takeoverKey,
                            onTakeoverKeyChange = { takeoverKey = it },
                            gameCardContent = pageGame?.let { page ->
                                { onBack, onOpenAchievements ->
                                    CompanionActivityGameDetail(
                                        game = page,
                                        payload = CompanionPayload(
                                            mode = CompanionMode.NOW_PLAYING,
                                            gameId = page.gameId,
                                            title = page.title,
                                            platformId = page.platformId,
                                            platformLabel = "Genesis",
                                            launcherLabel = "RetroArch",
                                        ),
                                        row = row,
                                        achievements = null,
                                        totalBeforeMs = null,
                                        platformLabel = "Genesis",
                                        navigationEnabled = true,
                                        onBack = onBack,
                                        onOpenAchievements = onOpenAchievements,
                                        onReturnToGame = null,
                                        quit = null,
                                        nowElapsedMs = { 1_060_000L },
                                        segmentsFlow = MutableStateFlow(null),
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000L)
        compose.waitForIdle()
    }

    private fun hasTag(tag: String) =
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun frame(ms: Long = 16L) {
        compose.mainClock.advanceTimeBy(ms)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    private fun shoot(): IntArray {
        val view = checkNotNull(rootView)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels
    }

    /** Mean absolute difference of the RGB channels over every fourth pixel, 0..255. */
    private fun mad(a: IntArray, b: IntArray): Double {
        var sum = 0L
        var n = 0
        var i = 0
        while (i < a.size) {
            val p = a[i]
            val q = b[i]
            sum += Math.abs(((p shr 16) and 0xFF) - ((q shr 16) and 0xFF)) +
                Math.abs(((p shr 8) and 0xFF) - ((q shr 8) and 0xFF)) +
                Math.abs((p and 0xFF) - (q and 0xFF))
            n += 3
            i += 4
        }
        return sum.toDouble() / n
    }

    private fun endGame() {
        Snapshot.withMutableSnapshot { game = null }
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    @Test
    fun `the page the takeover dissolves is kept, so the game's end is one dissolve onto the resting layout and never a cut`() {
        mount(keepPage = true)
        assertTrue("fixture: the takeover is up", hasTag(NowPlayingTakeoverTag))
        assertFalse("fixture: the resting page is not composed under an open takeover", hasTag(ActivityPageTag))
        val before = shoot()

        endGame()
        frame()
        assertTrue("first frame after the end: the takeover is still composed, dissolving", hasTag(NowPlayingTakeoverTag))
        assertTrue("first frame after the end: the resting page is under it", hasTag(ActivityPageTag))
        val first = shoot()

        // The dissolve: every frame moves toward the resting page, none moves back, and the takeover leaves only when the dissolve is done.
        val distances = mutableListOf(mad(before, first))
        var takeoverGoneAtMs: Long? = null
        var elapsed = 16L
        while (elapsed < MimirPageMotion.ReturnMs + 96L) {
            frame()
            elapsed += 16L
            distances += mad(before, shoot())
            if (takeoverGoneAtMs == null && !hasTag(NowPlayingTakeoverTag)) takeoverGoneAtMs = elapsed
        }
        val settled = distances.last()
        assertTrue("fixture: the resting page differs from the takeover on the JVM canvas (settled MAD $settled)", settled > 2.0)
        assertTrue(
            "the first frame after the end is not the resting page (a cut): MAD ${distances.first()} of $settled",
            distances.first() < settled * 0.5,
        )
        // The Transition's first frame initialises the fade (alpha still 1); the dissolve shows from the second.
        assertTrue("the dissolve has begun by the second frame: $distances", distances[1] > 0.0)
        for (i in 1 until distances.size) {
            assertTrue("frame $i moves toward the resting page, never back: $distances", distances[i] >= distances[i - 1] - 0.25)
        }
        assertTrue("the takeover left once the dissolve was done: gone at $takeoverGoneAtMs ms", takeoverGoneAtMs != null && takeoverGoneAtMs!! >= MimirPageMotion.ReturnMs)
        assertFalse("and it is gone at rest", hasTag(NowPlayingTakeoverTag))
    }

    @Test
    fun `the wiring this replaced cut the takeover on the first frame after the end (the Thor's b1r2b_008832)`() {
        mount(keepPage = false)
        assertTrue("fixture: the takeover is up", hasTag(NowPlayingTakeoverTag))
        val before = shoot()

        endGame()
        frame()
        assertFalse("the page the swap dissolves was already empty: the takeover is gone on the first frame", hasTag(NowPlayingTakeoverTag))
        assertTrue("the resting page is composed under it from the first frame", hasTag(ActivityPageTag))
        val first = shoot()
        repeat((MimirPageMotion.ReturnMs / 16) + 6) { frame() }
        val restingPage = shoot()
        val settled = mad(before, restingPage)
        assertTrue("fixture: the resting page differs from the takeover (settled MAD $settled)", settled > 2.0)
        // The Thor's bare frame: the takeover's pixels are gone AND the resting page's are not there yet (it is at alpha 0 on its first frame
        // and only paints on the next), so the first frame after the end is the stage with nothing on it, a cut to the backdrop.
        assertTrue(
            "the first frame after the end is not the resting page either (MAD to it ${mad(first, restingPage)} of $settled): the stage is bare",
            mad(first, restingPage) > settled * 0.5,
        )
    }
}
