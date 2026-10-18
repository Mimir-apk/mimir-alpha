package com.gruning.mimir.rewrite

import android.animation.ValueAnimator
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.session.MediaSession
import android.view.View
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.gruning.mimir.rewrite.core.model.CompanionMode
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.ui.MimirFilledControlRingReach
import com.gruning.mimir.rewrite.core.ui.MimirLogoStage
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.mimirStaticChrome
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
 * The Now Playing takeover (slice L3) rendered at the Thor Bottom Presentation's measured size: the full 1240 x 1080 px at 369 dpi, which is
 * 537.7 x 468.3 dp, at font scale 1.0 and 1.3, in the states that change what is on it:
 *
 *  (a) full: a logo and a hero, achievements, Return and Quit offered, the total and the subtitle;
 *  (b) no logo: the title takes the stage (and is not said twice);
 *  (c) no achievements: the chip drops out;
 *  (d) the close authority unavailable: no Quit;
 *  (e) Quit asked to confirm: "Tap again".
 *
 * Every state asserts: nothing is cut (only the game's own name may ellipsise), nothing overlaps, everything is inside the 502 dp body above the 36 dp dock
 * reserve, every touch target is at least 48 dp each way, and the logo stage is the ruled 430 x 215 when there is room and never smaller than its
 * floor. Each prints its measured zones as `L3-ZONES`. Pixels are the JVM's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionNowPlayingTakeoverRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val density369 = 369f / 160f
    private val hostWidthDp = 1240f / density369
    private val hostHeightDp = 1080f / density369
    private var density = 0f

    private val startMs = 1_000_000L
    private val clockMs = (1L * 3600 + 24 * 60 + 7) * 1000L
    private var nowMs by mutableLongStateOf(startMs + clockMs)
    private var takeoverKey by mutableStateOf<String?>(null)
    private val total = (18L * 60 + 40) * 60_000L

    @After
    fun restoreAnimatorScale() = setAnimatorScale(1f)

    /** Animator scale (Developer options): 0 is reduced motion. The setter is hidden, so by reflection. */
    private fun setAnimatorScale(scale: Float) {
        ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, scale)
    }

    @Before
    fun setUp() {
        CompanionNowPlayingState.resetForTest()
        nowMs = startMs + clockMs
        takeoverKey = null
        org.robolectric.shadows.ShadowLog.clear()
    }

    private fun gameRow(title: String = "Sonic the Hedgehog 2", logo: String? = "content://mimir/test-logo.webp", hero: String? = "content://mimir/test-hero.webp") =
        GameRow(
            gameId = GameId("g1"),
            title = title,
            platformId = PlatformId("genesis"),
            memberCount = 1,
            duplicateKind = null,
            flatArtUri = logo,
            heroArtUri = hero,
        )

    private fun session(phase: MimirSessionPhase = MimirSessionPhase.YIELDED_TO_SECONDARY_TASK) = MimirGameSession(
        operation = MimirGameSessionOperation("op-render"),
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
        phase = phase,
    )

    private val snapshot = CompanionActivityAchievementSnapshot(gameId = "g1", account = "me", unlocked = 12, total = 40, gameTitle = "Sonic the Hedgehog 2")

    private var returned = 0
    private var openedHub = 0
    private var rootView: View? = null

    private fun mount(fontScale: Float, content: @Composable () -> Unit) {
        // The takeover starts open on the live game, as the entry glide leaves it.
        takeoverKey = nowPlayingSessionKey(session())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density369, fontScale)) {
                MimirTheme {
                    density = LocalDensity.current.density
                    rootView = LocalView.current.rootView
                    Box(Modifier.requiredSize(hostWidthDp.dp, hostHeightDp.dp)) { content() }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
    }

    /** The workspace surface with the takeover open on the live game: the production host, with the production slot filled by the test's values. */
    private fun surface(
        row: GameRow = gameRow(),
        achievements: CompanionActivityAchievementSnapshot? = snapshot,
        totalBefore: Long? = total,
        quit: NowPlayingQuit? = NowPlayingQuit { PrivilegedCloseOutcome.STOPPED },
        withReturn: Boolean = true,
        game: MimirGameSession = session(),
        media: CompanionMediaUiState = CompanionMediaUiState.NoActiveMedia,
        segmentsFlow: StateFlow<HomeParkSegments?> = MutableStateFlow(null),
    ): @Composable () -> Unit = {
        val key = nowPlayingSessionKey(game)
        CompanionWorkspaceSurface(
            page = CompanionWorkspacePage.ACTIVITY,
            navigationEnabled = true,
            recentApp = null,
            game = game,
            media = media,
            onRecentApp = {},
            onReturnToGame = null,
            onMediaAccess = {},
            onMediaAction = { _, _, _ -> },
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
            gameCardSessionKey = key,
            takeoverKey = takeoverKey,
            onTakeoverKeyChange = { takeoverKey = it },
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
                    achievements = achievements,
                    totalBeforeMs = totalBefore,
                    platformLabel = "Genesis",
                    navigationEnabled = true,
                    onBack = onBack,
                    onOpenAchievements = { openedHub++; onOpenAchievements() },
                    onReturnToGame = if (withReturn) {
                        { returned++; true }
                    } else {
                        null
                    },
                    quit = quit,
                    nowElapsedMs = { nowMs },
                    segmentsFlow = segmentsFlow,
                )
            },
        )
    }

    // ── measuring ──────────────────────────────────────────────────────────────────────────────────────────────────────────────────

    private class Box4(val x: Float, val y: Float, val w: Float, val h: Float) {
        val right get() = x + w
        val bottom get() = y + h
        override fun toString() = "%.1fx%.1f@(%.1f,%.1f)".format(w, h, x, y)
    }

    private fun SemanticsNode.dp(): Box4 {
        val p = positionInRoot
        return Box4(p.x / density, p.y / density, size.width / density, size.height / density)
    }

    private fun nodes(tag: String): List<SemanticsNode> =
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, tag), useUnmergedTree = true).fetchSemanticsNodes()

    private fun hasTag(tag: String) = nodes(tag).isNotEmpty()

    private fun bounds(tag: String): Box4 = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().dp()

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

    /** The line's own width, not its right edge: a right-aligned line sits at the layout's right edge, which is not cut. */
    private fun TextLayoutResult.isCut(): Boolean =
        (0 until lineCount).any { isLineEllipsized(it) || (getLineRight(it) - getLineLeft(it)) > size.width + 1f }

    private fun settle() {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
    }

    /** A write announced and two short frames, for taps whose state changes inside the 3 s confirm window. */
    private fun frames() {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(50L)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(50L)
        compose.waitForIdle()
    }

    private fun overlaps(a: Box4, b: Box4, tol: Float = 0.5f) =
        a.x + a.w - tol > b.x && b.x + b.w - tol > a.x && a.y + a.h - tol > b.y && b.y + b.h - tol > a.y

    private class Expect(
        val logo: Boolean = true,
        val total: Boolean = true,
        val subtitle: Boolean = true,
        val chip: Boolean = true,
        val ret: Boolean = true,
        val quit: Boolean = true,
        val hero: Boolean = true,
    )

    private fun assertTakeover(scenario: String, fontScale: Float, expect: Expect = Expect(), dynamicTitles: Set<String> = setOf("Sonic the Hedgehog 2")) {
        assertTrue("the takeover is shown", hasTag(NowPlayingTakeoverTag))
        fun present(tag: String, wanted: Boolean) = assertEquals("$scenario $tag present", wanted, hasTag(tag))
        present(NowPlayingBackPillTag, true)
        present(NowPlayingClockTag, true)
        present(NowPlayingStageTag, true)
        present(NowPlayingTitleTag, true)
        present(NowPlayingTotalTag, expect.total)
        present(NowPlayingSubtitleTag, expect.subtitle)
        present(NowPlayingAchievementsTag, expect.chip)
        present(NowPlayingReturnTag, expect.ret)
        present(NowPlayingQuitTag, expect.quit)
        present(NowPlayingBackdropTag, expect.hero)

        val back = bounds(NowPlayingBackPillTag)
        val clock = bounds(NowPlayingClockTag)
        val stage = bounds(NowPlayingStageTag)
        val title = bounds(NowPlayingTitleTag)
        val totalBox = if (expect.total) bounds(NowPlayingTotalTag) else null
        val subtitleBox = if (expect.subtitle) bounds(NowPlayingSubtitleTag) else null
        val actions = if (expect.chip || expect.ret || expect.quit) bounds(NowPlayingActionsTag) else null
        val chip = if (expect.chip) bounds(NowPlayingAchievementsTag) else null
        val ret = if (expect.ret) bounds(NowPlayingReturnTag) else null
        val quit = if (expect.quit) bounds(NowPlayingQuitTag) else null
        println(
            "L3-ZONES scenario=$scenario fontScale=$fontScale host=${"%.1f".format(hostWidthDp)}x${"%.1f".format(hostHeightDp)}dp " +
                "back=$back clock=$clock total=$totalBox stage=$stage title=$title subtitle=$subtitleBox actions=$actions chip=$chip return=$ret quit=$quit",
        )

        // Inside the 502 dp body: 18 dp side insets, the 10 dp top, above the 36 dp dock reserve.
        val fold = hostHeightDp - ActivityDockReserveDp
        val all = listOfNotNull(back, clock, totalBox, stage, title, subtitleBox, actions, chip, ret, quit)
        all.forEach { b ->
            assertTrue("$scenario $b starts inside the left inset", b.x >= ActivityPageInsetDp - 0.5f)
            assertTrue("$scenario $b ends inside the right inset", b.right <= hostWidthDp - ActivityPageInsetDp + 0.5f)
            assertTrue("$scenario $b is below the top inset", b.y >= ActivityPageTopDp - 0.5f)
            assertTrue("$scenario $b ends at ${"%.1f".format(b.bottom)}, above the ${"%.1f".format(fold)} dp fold", b.bottom <= fold + 0.6f)
        }
        assertEquals("$scenario back pill at the inset", ActivityPageInsetDp, back.x, 0.6f)
        // The header is as tall as its tallest child (the clock with the total under it), and the 48 dp pill is centred in it: it starts at the top
        // inset when the header is 48 dp and a few dp lower when the clock column is taller.
        assertTrue("$scenario back pill ($back) starts at the top inset or centred in a taller header", back.y >= ActivityPageTopDp - 0.5f && back.y <= ActivityPageTopDp + 8f)
        assertTrue("$scenario back pill is a 48 dp target: $back", back.h >= 47.5f && back.w >= 47.5f)
        assertTrue("$scenario the clock sits at the right edge of the header: $clock", clock.right >= hostWidthDp - ActivityPageInsetDp - 1f)

        // The zones stack in order and never overlap: header, stage, words, actions.
        val headerBottom = listOfNotNull(back, clock, totalBox).maxOf { it.bottom }
        assertTrue("$scenario the stage ($stage) is under the header (bottom ${"%.1f".format(headerBottom)})", stage.y >= headerBottom - 0.5f)
        assertFalse("$scenario back pill and clock do not overlap", overlaps(back, clock))
        totalBox?.let { assertFalse("$scenario back pill and total do not overlap", overlaps(back, it)) }
        var floor = stage.bottom
        if (expect.logo) {
            assertTrue("$scenario the title ($title) is under the stage ($stage)", title.y >= stage.bottom - 0.5f)
            floor = title.bottom
        } else {
            assertTrue("$scenario the title sits on the stage, not under it", title.y >= stage.y - 0.5f && title.bottom <= stage.bottom + 0.5f)
        }
        subtitleBox?.let {
            assertTrue("$scenario the subtitle ($it) is under what is above it (${"%.1f".format(floor)})", it.y >= floor - 0.5f)
            floor = it.bottom
        }
        actions?.let {
            assertTrue("$scenario the actions ($it) are under the words (${"%.1f".format(floor)})", it.y >= floor - 0.5f)
            assertTrue("$scenario the actions are 48 dp or taller: $it", it.h >= 47.5f)
        }
        // The ring pills' rings reach 17 dp past the pill, and the scrolling column clips at its own bounds: the ring has to fit inside them on every
        // side, or the lit ring is cut flat (the offscreen-fade clip that cut rings elsewhere).
        val reach = MimirFilledControlRingReach.value
        listOfNotNull(ret, quit).forEach { pill ->
            assertTrue("$scenario ring of $pill is cut at the left edge", pill.x - reach >= ActivityPageInsetDp - 0.6f)
            assertTrue("$scenario ring of $pill is cut at the right edge", pill.right + reach <= hostWidthDp - ActivityPageInsetDp + 0.6f)
            assertTrue("$scenario ring of $pill touches the words above it", pill.y - reach >= floor - 0.6f)
            assertTrue("$scenario ring of $pill is cut at the bottom (fold ${"%.1f".format(fold)})", pill.bottom + reach <= fold + 0.6f)
        }

        // The stage: the ruled 430 x 215 when there is room, never below its floor, always 2:1 or the width it is capped at.
        assertTrue("$scenario the stage is at most the specified 430 x 215: $stage", stage.w <= MimirLogoStage.width.value + 0.5f && stage.h <= MimirLogoStage.height.value + 0.5f)
        assertTrue("$scenario the stage keeps its floor: $stage", stage.h >= 95.5f)
        assertEquals("$scenario the stage keeps 2:1: $stage", stage.h * 2f, stage.w, 1.5f)
        if (fontScale == 1.0f) {
            assertEquals("$scenario the specified stage fits at the default font: $stage", MimirLogoStage.width.value, stage.w, 0.6f)
            assertEquals("$scenario the specified stage fits at the default font: $stage", MimirLogoStage.height.value, stage.h, 0.6f)
        }

        // Every target is at least 48 dp each way.
        val targets = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick), useUnmergedTree = true).fetchSemanticsNodes().map { it.dp() }
        assertTrue("$scenario has targets", targets.isNotEmpty())
        targets.forEach { assertTrue("$scenario target $it is at least 48 dp each way", it.w >= 47.5f && it.h >= 47.5f) }
        listOfNotNull(chip, ret, quit).forEach { assertTrue("$scenario action $it is at least 48 dp tall", it.h >= 47.5f) }
        listOfNotNull(chip, ret, quit).sortedBy { it.x }.zipWithNext().forEach { (a, b) ->
            assertFalse("$scenario actions $a and $b do not overlap", overlaps(a, b))
        }

        // A chip never wraps (update): every action word is ONE line, and the three actions share one centre line.
        listOf("Return", "Quit", "Tap again", "12 of 40").forEach { word ->
            textNodes().filter { it.text == word }.forEach { assertEquals("$scenario the chip '$word' is ONE line at fontScale=$fontScale", 1, it.layout.lineCount) }
        }
        val centres = listOfNotNull(chip, ret, quit).map { it.y + it.h / 2f }
        if (centres.isNotEmpty()) assertTrue("$scenario the actions share one centre line: $centres", centres.maxOf { it } - centres.minOf { it } <= 1.0f)

        // Nothing cut: no text exceeds its lines or its width, except the game's own name.
        val texts = textNodes()
        assertTrue("some text was measured", texts.isNotEmpty())
        texts.forEach { t ->
            val why = "$scenario '${t.text}' ${t.box} at fontScale=$fontScale"
            if (t.text !in dynamicTitles) {
                assertFalse("$why is cut", t.layout.isCut())
                assertTrue("$why has more lines than it allows", !t.layout.didOverflowHeight)
            }
        }
        assertEquals("1:24:07", textNodes().single { it.box.y < headerBottom && it.text.matches(Regex("""\d+:\d\d:\d\d""")) }.text)
    }

    private fun title() = "Sonic the Hedgehog 2"

    // ── (a) full ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a full takeover at font scale 1 point 0`() {
        mount(1.0f, surface())
        assertTakeover("a-full", 1.0f)
        assertEquals("18 h 40 min before this session", textNodes().single { it.text.endsWith("before this session") }.text)
        assertTrue(textNodes().any { it.text == "Genesis · opens with RetroArch" })
        assertTrue(textNodes().any { it.text == "12 of 40" })
        assertTrue(textNodes().any { it.text == "Return" })
        assertTrue(textNodes().any { it.text == "Quit" })
    }

    @Test
    fun `a full takeover at font scale 1 point 3`() {
        mount(1.3f, surface())
        assertTakeover("a-full", 1.3f)
    }

    @Test
    fun `a long game name wraps and may ellipsise at 1 point 3 without moving anything out of the body`() {
        val long = "The Legend of Zelda: A Link to the Past and Four Swords Special Edition"
        mount(1.3f, surface(row = gameRow(title = long)))
        assertTakeover("a-long-title", 1.3f, dynamicTitles = setOf(long))
    }

    // ── (b) no logo ──────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `with no logo the title takes the stage and is not said twice at 1 point 0`() {
        mount(1.0f, surface(row = gameRow(logo = null, hero = null)))
        assertTakeover("b-no-logo", 1.0f, Expect(logo = false, hero = false))
        assertEquals("the title is said once", 1, textNodes().count { it.text == title() })
    }

    @Test
    fun `with no logo the title takes the stage at 1 point 3`() {
        mount(1.3f, surface(row = gameRow(logo = null, hero = null)))
        assertTakeover("b-no-logo", 1.3f, Expect(logo = false, hero = false))
    }

    // ── (c) no achievements ─────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `with no achievement snapshot the chip drops out and nothing is invented at 1 point 0`() {
        mount(1.0f, surface(achievements = null))
        assertTakeover("c-no-achievements", 1.0f, Expect(chip = false))
        assertFalse(textNodes().any { it.text.contains(" of ") && it.text.first().isDigit() })
    }

    @Test
    fun `with no achievement snapshot at 1 point 3`() {
        mount(1.3f, surface(achievements = null))
        assertTakeover("c-no-achievements", 1.3f, Expect(chip = false))
    }

    // ── (d) close authority unavailable ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `with the close authority unavailable there is no Quit at 1 point 0`() {
        mount(1.0f, surface(quit = null))
        assertTakeover("d-no-quit", 1.0f, Expect(quit = false))
        assertFalse(textNodes().any { it.text == "Quit" })
    }

    @Test
    fun `with the close authority unavailable at 1 point 3`() {
        mount(1.3f, surface(quit = null))
        assertTakeover("d-no-quit", 1.3f, Expect(quit = false))
    }

    @Test
    fun `absent actions drop out and nothing is invented, down to no actions at all`() {
        mount(1.0f, surface(achievements = null, quit = null, withReturn = false))
        assertTakeover("d-no-actions", 1.0f, Expect(chip = false, ret = false, quit = false))
        assertFalse(hasTag(NowPlayingActionsTag))
    }

    // ── (e) Quit asked to confirm ────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the first tap on Quit asks to confirm and the layout still fits at 1 point 0`() {
        mount(1.0f, surface())
        compose.onNodeWithTag(NowPlayingQuitTag).performClick()
        frames()
        assertTrue(textNodes().any { it.text == "Tap again" })
        assertTakeover("e-confirm", 1.0f)
    }

    @Test
    fun `the first tap on Quit asks to confirm and the layout still fits at 1 point 3`() {
        mount(1.3f, surface())
        compose.onNodeWithTag(NowPlayingQuitTag).performClick()
        frames()
        assertTrue(textNodes().any { it.text == "Tap again" })
        assertTakeover("e-confirm", 1.3f)
    }


    @Test
    fun `the confirm window expires back to Quit and closes nothing`() {
        var performed = 0
        mount(1.0f, surface(quit = NowPlayingQuit { performed++; PrivilegedCloseOutcome.STOPPED }))
        compose.onNodeWithTag(NowPlayingQuitTag).performClick()
        frames()
        assertTrue(textNodes().any { it.text == "Tap again" })
        compose.mainClock.advanceTimeBy(NowPlayingQuitConfirmMs - 600L)
        compose.waitForIdle()
        assertTrue("still asking inside the window", textNodes().any { it.text == "Tap again" })
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        frames()
        assertTrue("back to Quit after the window", textNodes().any { it.text == "Quit" })
        assertEquals("expiry closed nothing", 0, performed)
    }

    @Test
    fun `a second tap inside the window closes, and a verified stop says Closed`() {
        var performed = 0
        mount(1.0f, surface(quit = NowPlayingQuit { performed++; PrivilegedCloseOutcome.STOPPED }))
        compose.onNodeWithTag(NowPlayingQuitTag).performClick()
        frames()
        compose.onNodeWithTag(NowPlayingQuitTag).performClick()
        frames()
        assertEquals(1, performed)
        assertTrue(textNodes().any { it.text == "Closed" })
        // A Closed button takes no further tap.
        compose.onNodeWithTag(NowPlayingQuitTag).performClick()
        frames()
        assertEquals(1, performed)
        assertTakeover("e-closed", 1.0f)
    }

    private fun assertCouldNotClose(outcome: PrivilegedCloseOutcome) {
        var performed = 0
        mount(1.0f, surface(quit = NowPlayingQuit { performed++; outcome }))
        compose.onNodeWithTag(NowPlayingQuitTag).performClick()
        frames()
        compose.onNodeWithTag(NowPlayingQuitTag).performClick()
        frames()
        assertEquals("$outcome", 1, performed)
        assertTrue("$outcome says it could not", textNodes().any { it.text == "Couldn't close it" })
        assertFalse("$outcome never says Closed", textNodes().any { it.text == "Closed" })
        assertTakeover("e-failed-$outcome", 1.0f)
        // The words are held for the window, then Quit is offered again.
        compose.mainClock.advanceTimeBy(NowPlayingQuitConfirmMs + 500L)
        compose.waitForIdle()
        frames()
        assertTrue("$outcome offers Quit again", textNodes().any { it.text == "Quit" })
    }

    @Test
    fun `a firmware that exits 0 and kills nothing says Couldn't close it, then offers Quit again`() =
        assertCouldNotClose(PrivilegedCloseOutcome.STILL_ALIVE)

    @Test
    fun `a close the authority could not prove says Couldn't close it too`() =
        assertCouldNotClose(PrivilegedCloseOutcome.REQUEST_UNVERIFIED)


    // ── structure: the clock is a sibling of the art ───────────────────────────────────────────────────────────────────────────────

    private fun ancestors(node: SemanticsNode): List<SemanticsNode> = generateSequence(node.parent) { it.parent }.toList()

    private fun SemanticsNode.tag(): String? =
        if (SemanticsProperties.TestTag in config) config[SemanticsProperties.TestTag] else null

    @Test
    fun `the clock is a sibling of the art layers, never a child, and the art is never a child of the clock`() {
        mount(1.0f, surface())
        val root = nodes(NowPlayingTakeoverTag).single()
        val backdrop = nodes(NowPlayingBackdropTag).single()
        val stage = nodes(NowPlayingStageTag).single()
        val clock = nodes(NowPlayingClockTag).single()
        val clockAncestors = ancestors(clock)
        val clockAncestorTags = clockAncestors.mapNotNull { it.tag() }
        assertFalse("the clock is not inside the hero layer: $clockAncestorTags", NowPlayingBackdropTag in clockAncestorTags)
        assertFalse("the clock is not inside the logo stage: $clockAncestorTags", NowPlayingStageTag in clockAncestorTags)
        assertTrue("the clock is inside the takeover", root.id in clockAncestors.map { it.id })
        // The hero layer's parent is the takeover root itself: the clock's branch hangs off the same parent, beside it.
        assertEquals("the hero layer is a direct child of the takeover", root.id, backdrop.parent?.id)
        assertFalse("the clock branch is not under the hero layer", backdrop.id in clockAncestors.map { it.id })
        // And the art is not under the clock.
        assertFalse(clock.id in ancestors(backdrop).map { it.id })
        assertFalse(clock.id in ancestors(stage).map { it.id })
        // The stage and the clock share the content branch, but neither contains the other.
        assertFalse(stage.id in clockAncestors.map { it.id })
        assertFalse(clock.id in ancestors(stage).map { it.id })
    }

    @Test
    fun `no wired hero means no art layer at all`() {
        mount(1.0f, surface(row = gameRow(hero = null)))
        assertFalse(hasTag(NowPlayingBackdropTag))
        assertTrue(hasTag(NowPlayingClockTag))
    }

    @Test
    fun `the clock ticks once a second and reads h mm ss`() {
        mount(1.0f, surface())
        assertEquals("1:24:07", textNodes().single { it.text.matches(Regex("""\d+:\d\d:\d\d""")) }.text)
        nowMs += 3_000L
        settle()
        assertEquals("1:24:10", textNodes().single { it.text.matches(Regex("""\d+:\d\d:\d\d""")) }.text)
    }

    /**
     * While parked, hold the played time and label it PAUSED. Resume removes the label and continues
     * from the held value. The total excludes the clock's minutes throughout.
     */
    @Test
    fun `a parked game holds the clock still and says PAUSED, the total leaves out the clock's minutes, then Resume runs it on`() {
        val parkedFlow = MutableStateFlow<HomeParkSegments?>(null)
        val clockRegex = Regex("""\d+:\d\d:\d\d""")
        fun clockText() = textNodes().single { it.text.matches(clockRegex) }.text
        fun totalText() = textNodes().single { it.text.endsWith("before this session") }.text
        // The store has already added the finished 1:24:07 to the live total: 18 h 40 min before, plus the clock's time.
        mount(1.0f, surface(totalBefore = total + clockMs, segmentsFlow = parkedFlow))
        assertEquals("1:24:07", clockText())
        assertFalse("running: no PAUSED", hasTag(NowPlayingPausedTag))

        // HOME pressed now: the segment ends here, and the session's first total was 18 h 40 min.
        parkedFlow.value = HomeParkSegments(
            operation = MimirGameSessionOperation("op-render"),
            playedMs = clockMs,
            segmentStartedAtElapsedMs = null,
            totalBeforeMs = total,
        )
        settle()
        assertTrue("parked: PAUSED beside the clock", hasTag(NowPlayingPausedTag))
        // Chrome text speaks the natural word and draws the caps: the visual projection is the pure check (MimirTextIntent).
        assertEquals("in caps, as the Bottom's labels", "PAUSED", mimirStaticChrome("PAUSED"))
        nowMs += 60_000L
        settle()
        assertEquals("a parked minute is not on the clock", "1:24:07", clockText())
        assertTrue("still PAUSED", hasTag(NowPlayingPausedTag))
        assertEquals("18 h 40 min before this session", totalText())

        // Resume fronts the game: the label leaves, and a minute later the clock has moved by a minute from the held value.
        parkedFlow.value = parkedFlow.value?.copy(segmentStartedAtElapsedMs = nowMs)
        settle()
        assertFalse("resumed: PAUSED has left", hasTag(NowPlayingPausedTag))
        assertEquals("the clock runs on from the held value", "1:24:07", clockText())
        nowMs += 60_000L
        settle()
        assertEquals("1:25:07", clockText())
        assertEquals("18 h 40 min before this session", totalText())
    }

    // ── receipts and entry ─────────────────────────────────────────────────────────────────────────────────────────────────────────

    private fun receipts(): List<String> =
        org.robolectric.shadows.ShadowLog.getLogsForTag(com.gruning.mimir.rewrite.core.ui.BottomUiReceiptFormat.TAG).map { it.msg }

    @Test
    fun `the controls write opaque receipts and the old ids keep their meaning`() {
        mount(1.0f) {
            CompositionLocalProvider(com.gruning.mimir.rewrite.core.ui.LocalBottomUiDisplay provides 4) {
                surface()()
            }
        }
        compose.onNodeWithTag(NowPlayingQuitTag).performClick()
        frames()
        compose.onNodeWithTag(NowPlayingQuitTag).performClick()
        frames()
        compose.onNodeWithTag(NowPlayingReturnTag).performClick()
        frames()
        assertEquals(1, returned)
        val lines = receipts()
        assertTrue("quit ask: $lines", lines.any { it.startsWith("ev=control.activate id=nowplaying.quit display=4") })
        assertTrue("quit confirm: $lines", lines.any { it.startsWith("ev=control.activate id=nowplaying.quit_confirm display=4") })
        val ret = lines.single { it.contains("id=activity.return_to_game") }
        assertTrue("the return receipt names an opaque game id: $ret", Regex("target=game:[0-9a-f]{10}").containsMatchIn(ret))
        assertTrue("and never a title: $ret", "Sonic" !in ret)
        assertFalse("the new controls never write a game id or a package: $lines", lines.any { it.contains("nowplaying") && (it.contains("target=") || it.contains("org.example")) })
        compose.onNodeWithTag(NowPlayingBackPillTag).performClick()
        settle()
        assertTrue("the old back id is unchanged: ${receipts()}", receipts().any { it.startsWith("ev=control.activate id=activity.back_to_activity display=4") })
    }

    @Test
    fun `the Activity pill returns to the resting layout, where the Now playing tile re-enters the takeover`() {
        mount(1.0f, surface())
        assertTrue(hasTag(NowPlayingTakeoverTag))
        compose.onNodeWithTag(NowPlayingBackPillTag).performClick()
        settle()
        assertFalse("the takeover is gone", hasTag(NowPlayingTakeoverTag))
        assertTrue("the resting layout is back", hasTag(ActivityPageTag))
        assertTrue("with the live game as the Now playing tile", hasTag(ActivityTileNowPlayingTag))
        compose.onNodeWithTag(ActivityTileNowPlayingTag).performClick()
        settle()
        assertTrue("tapping the tile re-enters the takeover", hasTag(NowPlayingTakeoverTag))
        assertFalse(hasTag(ActivityPageTag))
    }

    @Test
    fun `the achievements chip opens the hub and leaves the takeover`() {
        mount(1.0f, surface())
        compose.onNodeWithTag(NowPlayingAchievementsTag).performClick()
        settle()
        assertEquals(1, openedHub)
        assertFalse(hasTag(NowPlayingTakeoverTag))
        assertEquals(null, takeoverKey)
    }

    // ── the music backdrop ───────────────────────────────────────────────────────────────────────────────────────────────

    private fun musicTrack(): CompanionMediaTrack {
        val token = MediaSession(ApplicationProvider.getApplicationContext<Application>(), "takeover-render").sessionToken
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
            artwork = CompanionMediaArtwork.BitmapValue(Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)),
        )
    }

    @Test
    fun `with music playing and no game hero the takeover shows no blurred music backdrop, and the resting layout keeps it`() {
        mount(1.0f, surface(row = gameRow(hero = null), media = CompanionMediaUiState.Active(musicTrack())))
        assertTrue(hasTag(NowPlayingTakeoverTag))
        assertFalse("no hero, so no game art layer", hasTag(NowPlayingBackdropTag))
        assertFalse("and no album art behind a game's page either", hasTag(MediaBackdropTag))
        // Back to the resting layout: the music backdrop is part of it, as it always was.
        compose.onNodeWithTag(NowPlayingBackPillTag).performClick()
        settle()
        assertFalse(hasTag(NowPlayingTakeoverTag))
        assertTrue("the resting layout keeps its music backdrop", hasTag(MediaBackdropTag))
        // And back into the takeover: gone again.
        compose.onNodeWithTag(ActivityTileNowPlayingTag).performClick()
        settle()
        assertTrue(hasTag(NowPlayingTakeoverTag))
        assertFalse("the takeover never shows the music backdrop", hasTag(MediaBackdropTag))
    }

    @Test
    fun `with music playing and a game hero the takeover shows the hero and only the hero`() {
        mount(1.0f, surface(media = CompanionMediaUiState.Active(musicTrack())))
        assertTrue(hasTag(NowPlayingBackdropTag))
        assertFalse(hasTag(MediaBackdropTag))
    }

    // ── update: pictures of the actions ─────────────────────────────────────────────────────────────────────────────────────────

    /** The window as a PNG in `app/build/btn265/`, copied to `build/test-captures` when it exists. Pixel values are the JVM's; the handheld's are device-owed. */
    private fun writePng(fileName: String) {
        val view = checkNotNull(rootView) { "the content has not been composed" }
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val dir = File("build/btn265").apply { mkdirs() }
        val out = File(dir, fileName)
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val shared = File("build/test-captures")
        if (shared.isDirectory) out.copyTo(File(shared, fileName), overwrite = true)
        println("BTN265 wrote ${out.absolutePath} (${out.length()} bytes, ${bitmap.width}x${bitmap.height} px)")
        assertTrue("the PNG was written: ${out.path}", out.isFile && out.length() > 1_000L)
    }

    @Test
    fun `the actions are etched chips at one height, one line each, and the picture shows Return and an armed Quit as primaries`() {
        mount(1.0f, surface())
        settle()
        val chip = bounds(NowPlayingAchievementsTag)
        val ret = bounds(NowPlayingReturnTag)
        val quit = bounds(NowPlayingQuitTag)
        println("BTN265 nowplaying idle chip=$chip return=$ret quit=$quit")
        listOf("the achievements chip" to chip, "Return" to ret, "Quit" to quit).forEach { (name, box) ->
            assertTrue("$name is at least 48 dp each way: $box", box.h >= 47.5f && box.w >= 47.5f)
        }
        assertEquals("Return and Quit are one height", ret.h, quit.h, 1.0f)
        assertEquals("and the achievements chip is that height too", ret.h, chip.h, 1.0f)
        assertTrue("Quit is a capsule beside Return, not a circle: ${quit.w} x ${quit.h}", quit.w >= quit.h * 1.3f)
        assertEquals("Quit is one line", 1, textNodes().first { it.text == "Quit" }.layout.lineCount)
        assertEquals("Return is one line", 1, textNodes().first { it.text == "Return" }.layout.lineCount)
        writePng("btn265-nowplaying.png")

        compose.onNodeWithTag(NowPlayingQuitTag).performClick()
        frames()
        val armed = textNodes().first { it.text == "Tap again" }
        assertEquals("the confirming Quit is ONE line", 1, armed.layout.lineCount)
        assertFalse("and its word is not cut", armed.layout.isCut())
        val armedQuit = bounds(NowPlayingQuitTag)
        println("BTN265 nowplaying armed return=${bounds(NowPlayingReturnTag)} quit=$armedQuit")
        assertTrue("the armed Quit keeps the row's 48 dp height: $armedQuit", armedQuit.h >= 47.5f)
        writePng("btn265-nowplaying-armed.png")
    }

    @Test
    fun `at font scale 1 point 3 every chip is still one line, idle and armed, and the pictures are saved`() {
        mount(1.3f, surface())
        settle()
        listOf("Return", "Quit", "12 of 40").forEach { word ->
            val node = textNodes().first { it.text == word }
            assertEquals("'$word' is one line at 1.3", 1, node.layout.lineCount)
            assertFalse("'$word' is not cut at 1.3", node.layout.isCut())
        }
        writePng("btn265-nowplaying-1_3.png")
        compose.onNodeWithTag(NowPlayingQuitTag).performClick()
        frames()
        val armed = textNodes().first { it.text == "Tap again" }
        assertEquals("the confirming Quit is one line at 1.3", 1, armed.layout.lineCount)
        assertFalse("and is not cut", armed.layout.isCut())
        writePng("btn265-nowplaying-armed-1_3.png")
    }

    @Test
    fun `a screen reader hears what the short words mean`() {
        assertEquals("Tap again to quit", nowPlayingQuitSpoken(NowPlayingQuitPhase.CONFIRM))
        assertEquals("Quit", nowPlayingQuitSpoken(NowPlayingQuitPhase.IDLE))
        mount(1.0f, surface())
        val returnNode = compose.onNodeWithTag(NowPlayingReturnTag, useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(listOf("Return to game"), returnNode.config.getOrNull(SemanticsProperties.ContentDescription))
    }

    // ── the action chips (update) ───────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `Return and a confirming Quit are primary etched chips with no ring, and every other action is the unselected one`() {
        val source = java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/CompanionActivityGameDetail.kt").readText()
        val pill = source.substringAfter("private fun NowPlayingPill(").substringBefore("/** \"12 of 40\"")
        // Update : the primary is the SAME dark plate as the unselected chip, its rim and word in the accent -- never warmed (the selected look), never
        // filled, and wearing no ring: the Bottom is touch, a ring that never moves reads as a fill, and only a focused element wears one.
        assertTrue("the primary's surface is the etched chip struck as a primary", ".mimirEtchedChipSurface(palette, primary = true)" in pill)
        assertTrue("with the accent for its word", "palette.etchedChipInk(primary = true)" in pill)
        assertFalse("never warmed: that is the selected look", "warmed = " in pill)
        assertFalse("no ring, no fill, no clip, no background", "mimirSelectionRing" in pill || "filled = " in pill || "fill = " in pill || ".clip(" in pill || ".background(" in pill)
        assertFalse("never a second focus node or a clickable beside mimirActivatable", "clickable" in pill || "mimirFocusable" in pill)
        assertTrue("a chip never wraps: one line", "maxLines = 1" in pill.substringAfter("text = label,").substringBefore("if (sublabel != null)"))
        // Every other state of an action is the unselected etched chip, in a 48 dp hit box, on one line.
        assertTrue("an action without emphasis is the unselected chip", "NowPlayingEtchedAction(label = label, description = description, tag = tag, enabled = enabled" in pill)
        val chip = source.substringAfter("private fun NowPlayingEtchedAction(").substringBefore(" * Quit: two taps.")
        assertTrue(chip, ".mimirEtchedChipSurface(palette)" in chip)
        assertTrue(chip, ".heightIn(min = MimirSpacing.minTarget)" in chip && ".widthIn(min = 72.dp)" in chip)
        assertTrue(chip, "style = MimirEtchedChipLabelStyle" in chip && "maxLines = 1" in chip)
        assertFalse("no ring, no fill", "mimirSelectionRing" in chip || ".background(" in chip || ".clip(" in chip || "fill = " in chip)
        // Both actions take that pill; the takeover keeps no ring arrival now that nothing reads it.
        assertEquals(2, Regex("""NowPlayingPill\(""").findAll(source).count() - 1)
        assertFalse("no ring arrival is kept for a ring that is gone", "val arrival" in source)
        // The achievements chip beside them is an unselected etched chip too.
        val achievements = source.substringAfter("private fun NowPlayingAchievementsChip(").substringBefore("private fun NowPlayingEtchedAction(")
        assertTrue(achievements, ".mimirEtchedChipSurface(palette, warmed = false)" in achievements)
        assertFalse("with no fill", ".background(" in achievements || ".clip(" in achievements)
    }

    // ── the achievements hub on the swap tier ───────────────────────────────────────────────────────────────────────────

    // The hub is Main's achievement card (V10): it is shown when its card is.
    private fun hubShown(): Boolean = hasTag(AchievementHubCardTag)

    @Test
    fun `the achievements hub arrives on the swap, the takeover dissolving first and the hub after the lead`() {
        mount(1.0f, surface(achievements = snapshot))
        compose.onNodeWithTag(NowPlayingAchievementsTag).performClick()
        frames()
        assertTrue("mid-swap the page being left is still drawn", hasTag(NowPlayingTakeoverTag))
        assertTrue("and the hub has arrived to take over", hubShown())
        settle()
        assertFalse("settled, the takeover is gone", hasTag(NowPlayingTakeoverTag))
        assertTrue(hubShown())
        // With the game running, Back reads Session and closes the hub into that game's Now Playing page, and settles on it alone.
        compose.onNode(hasContentDescription("Back to Session"), useUnmergedTree = true).performClick()
        frames()
        assertTrue("mid-return the hub is still there", hubShown())
        settle()
        assertFalse(hubShown())
        assertTrue("the session's page is back", hasTag(NowPlayingTakeoverTag))
        assertFalse("not the resting layout", hasTag(ActivityPageTag))
    }

    @Test
    fun `a hub open and close write the scene receipts the scene host wrote before the swap tier`() {
        mount(1.0f) {
            CompositionLocalProvider(com.gruning.mimir.rewrite.core.ui.LocalBottomUiDisplay provides 4) {
                surface(achievements = snapshot)()
            }
        }
        assertTrue("the first scene on screen writes no scene receipt: ${receipts()}", receipts().none { it.contains("name=scene.") })
        compose.onNodeWithTag(NowPlayingAchievementsTag).performClick()
        frames()
        settle()
        fun count(prefix: String) = receipts().count { it.startsWith(prefix) }
        assertEquals("opening the hub begins scene.crossfade: ${receipts()}", 1, count("ev=transition.begin name=scene.crossfade display=4"))
        assertEquals("opening the hub begins scene.glide: ${receipts()}", 1, count("ev=transition.begin name=scene.glide display=4"))
        assertEquals(1, receipts().count { it.startsWith("ev=transition.end name=scene.crossfade display=4 ms=") && it.endsWith("result=done") })
        assertEquals(1, receipts().count { it.startsWith("ev=transition.end name=scene.glide display=4 ms=") && it.endsWith("result=done") })
        compose.onNode(hasContentDescription("Back to Session"), useUnmergedTree = true).performClick()
        frames()
        settle()
        assertEquals("closing the hub writes the same two again: ${receipts()}", 2, count("ev=transition.begin name=scene.crossfade display=4"))
        assertEquals(2, count("ev=transition.begin name=scene.glide display=4"))
        assertEquals(2, receipts().count { it.startsWith("ev=transition.end name=scene.crossfade display=4 ms=") && it.endsWith("result=done") })
        assertEquals(2, receipts().count { it.startsWith("ev=transition.end name=scene.glide display=4 ms=") && it.endsWith("result=done") })
    }

    @Test
    fun `with reduced motion the hub swap snaps, with neither page left behind`() {
        setAnimatorScale(0f)
        mount(1.0f, surface(achievements = snapshot))
        compose.onNodeWithTag(NowPlayingAchievementsTag).performClick()
        frames()
        assertFalse("reduced motion has no dissolve: the takeover is gone at once", hasTag(NowPlayingTakeoverTag))
        assertTrue(hubShown())
        compose.onNode(hasContentDescription("Back to Session"), useUnmergedTree = true).performClick()
        frames()
        assertFalse("and the return snaps too", hubShown())
        assertTrue("onto the session's page", hasTag(NowPlayingTakeoverTag))
    }
}
