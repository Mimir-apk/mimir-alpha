package com.gruning.mimir.rewrite.feature.library

import android.animation.ValueAnimator
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.os.Looper
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.ui.LocalBottomUiDisplay
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import java.io.File
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog

/**
 * Slices L5 and L6: the launch card's three looks (Cinematic, Journal, Logo Only) and the look switch, rendered at the Thor Bottom Presentation's size, measured on
 * the device on 2026-10-02: 1240 x 1080 px at 369 dpi, which is 537.7 x 468.3 dp.
 *
 * For each look at font scales 1.0 and 1.3: full data; no hero; no logo; (Journal) every absence and no synopsis; a long title. In each: the card is
 * 470 dp wide and sits inside the host with at least [DismissMargin] above and below it (and 24 dp at the sides), so a tap outside it still dismisses; no
 * piece is outside the card or overflows its text box; PLAY, Opens on and the look switch are each at least 48 dp; PLAY stands the ring's room
 * from the card's edges. Every card's height is printed with the tag `L5-CARD`. Pixel values are the JVM's; the handheld's are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionLaunchCardRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val density369 = 369f / 160f
    private val hostWidthDp = 1240f / density369
    private val hostHeightDp = 1080f / density369

    /** The room a tap outside the card keeps above and below it (the card's own constant, restated so a change to it is seen here). */
    private val dismissMargin = 48f

    /** The ring's reach (17 dp) and the flare's (6 dp): what PLAY needs between its edge and the card's. */
    private val ringRoom = 23f

    private var density = 0f
    private var rootView: View? = null
    private val tempFiles = mutableListOf<File>()

    // 2023-11-14 22:13 UTC; "a day before" is yesterday in every zone.
    private val now = 1_700_000_000_000L
    private val dayMs = 86_400_000L

    private val synopsis =
        "A long-running blue hedgehog races through zones of loops and springs, collecting rings and chasing a scientist across the whole island, " +
            "with a fox pal who flies behind him and a great many more words than three lines can hold at any size."

    private val fullFacts = CompanionLaunchCardFacts(
        sessionTotalMillis = (18L * 60 + 40) * 60_000L,
        achievementsDone = 12,
        achievementsTotal = 40,
    )

    @Before
    fun setUp() {
        ShadowLog.clear()
    }

    @After
    fun tearDown() {
        setAnimatorScale(1f)
        tempFiles.forEach { it.delete() }
    }

    /** Animator scale (Developer options): 0 is reduced motion. The setter is hidden, so by reflection. */
    private fun setAnimatorScale(scale: Float) {
        ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, scale)
    }

    // ── Fixtures ───────────────────────────────────────────────────────────────────────────────────

    private fun png(width: Int, height: Int, draw: (Canvas) -> Unit): String {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        draw(Canvas(bitmap))
        val file = File.createTempFile("l5-art", ".png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        tempFiles += file
        return file.toURI().toString().replaceFirst("file:/", "file:///")
    }

    private val heroUri by lazy { png(320, 180) { it.drawColor(AndroidColor.rgb(30, 90, 160)) } }

    /** A wordmark with transparent margins: its ink is the middle of the file. */
    private val logoUri by lazy {
        png(430, 215) { canvas ->
            canvas.drawRect(40f, 70f, 390f, 145f, Paint().apply { color = AndroidColor.rgb(230, 200, 40) })
        }
    }

    private fun game(
        title: String = "Sonic the Hedgehog 2",
        hero: String? = heroUri,
        logo: String? = logoUri,
        description: String? = synopsis,
        lastPlayed: Long? = now - dayMs,
        plays: Int = 14,
    ) = GameRow(
        gameId = GameId("g1"),
        title = title,
        platformId = PlatformId("genesis"),
        memberCount = 1,
        duplicateKind = null,
        lastPlayedMillis = lastPlayed,
        playCount = plays,
        description = description,
        descriptionSource = if (description != null) "screenscraper" else null,
        heroArtUri = hero,
        flatArtUri = logo,
    )

    // ── Harness ────────────────────────────────────────────────────────────────────────────────────

    private var plays = 0
    private var edits = 0
    private var dismissals = 0
    private val lookRequests = mutableListOf<CompanionLaunchLook>()

    private fun mount(
        game: GameRow,
        look: CompanionLaunchLook,
        fontScale: Float,
        progress: Float = 1f,
        facts: CompanionLaunchCardFacts = fullFacts,
        active: Boolean = true,
        platformLabel: String? = "Genesis",
        display: Int? = null,
        settleAfter: Boolean = true,
    ) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density369, fontScale)) {
                MimirTheme {
                    density = LocalDensity.current.density
                    rootView = LocalView.current.rootView
                    val body: @androidx.compose.runtime.Composable () -> Unit = {
                        Box(Modifier.requiredSize(hostWidthDp.dp, hostHeightDp.dp).testTag("l5-host")) {
                            var shown by remember { mutableStateOf(look) }
                            CompanionLaunchCard(
                                game = game,
                                progress = progress,
                                active = active,
                                onDismiss = { dismissals++ },
                                onEditDisplay = { edits++ },
                                displayChoice = CompanionLaunchDisplayChoice.ASK,
                                onPlay = { plays++ },
                                look = shown,
                                onLookChange = { next ->
                                    lookRequests += next
                                    shown = next
                                },
                                platformLabel = platformLabel,
                                journalFactsFor = { facts },
                                nowMillis = now,
                            )
                        }
                    }
                    if (display != null) CompositionLocalProvider(LocalBottomUiDisplay provides display) { body() } else body()
                }
            }
        }
        if (settleAfter) settle()
    }

    private fun settle(ms: Long = 1_000L) {
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    /** The art checks (is the file there?) run off the main thread, so the paused clock needs real time to see their answer. */
    private fun awaitArt() {
        repeat(30) {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(50L)
        }
        compose.waitForIdle()
    }

    private fun hostOrigin(): Offset = compose.onNodeWithTag("l5-host").fetchSemanticsNode().boundsInRoot.topLeft

    private fun dp(b: androidx.compose.ui.geometry.Rect): FloatArray {
        val o = hostOrigin()
        return floatArrayOf((b.left - o.x) / density, (b.top - o.y) / density, b.width / density, b.height / density)
    }

    private fun node(tag: String): SemanticsNode = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()

    private fun boundsDp(tag: String): FloatArray = dp(node(tag).boundsInRoot)

    private fun count(tag: String): Int = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun layouts(tag: String): List<TextLayoutResult> =
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().map { n ->
            val out = mutableListOf<TextLayoutResult>()
            val action = checkNotNull(n.config[SemanticsActions.GetTextLayoutResult].action) { "$tag has no layout action" }
            check(action(out)) { "$tag has no text layout yet" }
            out.single()
        }

    private fun textOf(tag: String): String = layouts(tag).single().layoutInput.text.text

    /** Every string under the node (unmerged), in order: a fact cell's label and value. */
    private fun wordsUnder(tag: String): List<String> {
        val out = mutableListOf<String>()
        fun walk(n: SemanticsNode) {
            n.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
            n.children.forEach(::walk)
        }
        walk(node(tag))
        return out
    }

    private fun assertNear(message: String, expected: Float, actual: Float, tolerance: Float = 0.8f) =
        assertTrue("$message: expected $expected, was $actual", abs(expected - actual) <= tolerance)

    private fun assertInside(name: String, inner: FloatArray, outer: FloatArray) =
        assertTrue(
            "$name ${inner.toList()} must sit inside ${outer.toList()}",
            inner[0] >= outer[0] - 0.6f && inner[0] + inner[2] <= outer[0] + outer[2] + 0.6f &&
                inner[1] >= outer[1] - 0.6f && inner[1] + inner[3] <= outer[1] + outer[3] + 0.6f,
        )

    private fun overlaps(a: FloatArray, b: FloatArray) =
        a[0] + a[2] - 0.5f > b[0] && b[0] + b[2] - 0.5f > a[0] && a[1] + a[3] - 0.5f > b[1] && b[1] + b[3] - 0.5f > a[1]

    /** What every look at every size must satisfy; returns the card's height. */
    private fun assertCard(label: String, look: CompanionLaunchLook, fontScale: Float): Float {
        val host = floatArrayOf(0f, 0f, hostWidthDp, hostHeightDp)
        val card = boundsDp(CompanionLaunchCardTag)
        val footer = boundsDp(CompanionLaunchCardFooterTag)
        val play = boundsDp(CompanionLaunchCardPlayTag)
        val opens = boundsDp(CompanionLaunchCardOpensOnTag)
        val lookChip = boundsDp(CompanionLaunchCardLookTag)
        val topMargin = card[1]
        val bottomMargin = hostHeightDp - (card[1] + card[3])
        val leftMargin = card[0]
        val rightMargin = hostWidthDp - (card[0] + card[2])
        println(
            "L5-CARD $label ${look.name} fontScale=$fontScale card=${"%.1f".format(card[2])}x${"%.1f".format(card[3])}dp " +
                "margins top=${"%.1f".format(topMargin)} bottom=${"%.1f".format(bottomMargin)} left=${"%.1f".format(leftMargin)} right=${"%.1f".format(rightMargin)} " +
                "footer=${"%.1f".format(footer[3])}dp play=${"%.1f".format(play[2])}x${"%.1f".format(play[3])} opens=${"%.1f".format(opens[2])}x${"%.1f".format(opens[3])} " +
                "look=${"%.1f".format(lookChip[2])}x${"%.1f".format(lookChip[3])}",
        )
        assertNear("$label: the card is 470 dp wide", 470f, card[2])
        assertInside("$label: the card in the host", card, host)
        assertTrue("$label: the dismiss strip above is at least $dismissMargin: $topMargin", topMargin >= dismissMargin - 0.6f)
        assertTrue("$label: the dismiss strip below is at least $dismissMargin: $bottomMargin", bottomMargin >= dismissMargin - 0.6f)
        assertTrue("$label: the strips at the sides are at least 24: $leftMargin $rightMargin", leftMargin >= 23.4f && rightMargin >= 23.4f)

        // The footer closes the card, and its three targets are 48 dp, apart, and inside it.
        assertNear("$label: the footer closes the card", card[1] + card[3], footer[1] + footer[3])
        for ((name, box) in listOf("PLAY" to play, "Opens on" to opens, "Look" to lookChip)) {
            assertTrue("$label: $name is at least 48 dp: ${box.toList()}", box[2] >= 47.5f && box[3] >= 47.5f)
            assertInside("$label: $name", box, footer)
        }
        assertFalse("$label: Opens on and Look", overlaps(opens, lookChip))
        assertFalse("$label: Look and PLAY", overlaps(lookChip, play))
        assertFalse("$label: Opens on and PLAY", overlaps(opens, play))
        assertTrue("$label: the ring's reach is clear between Look and PLAY", play[0] - (lookChip[0] + lookChip[2]) >= 16.5f)
        // PLAY's ring (17 dp and a flare of 6) has room before the card's own edge, which clips it.
        assertTrue("$label: room below PLAY: ${card[1] + card[3] - (play[1] + play[3])}", card[1] + card[3] - (play[1] + play[3]) >= ringRoom - 0.6f)
        assertTrue("$label: room right of PLAY: ${card[0] + card[2] - (play[0] + play[2])}", card[0] + card[2] - (play[0] + play[2]) >= ringRoom - 0.6f)
        assertTrue("$label: room above PLAY inside the footer: ${play[1] - footer[1]}", play[1] - footer[1] >= ringRoom - 0.6f)

        // Nothing drawn is outside the card, nothing of the body reaches into the footer (it stands the ring's room clear below), and no text is cut.
        for (tag in listOf(
            CompanionLaunchCardStageTag, CompanionLaunchCardTitleTag, CompanionLaunchCardMetaTag, CompanionLaunchCardCoverTag, CompanionLaunchCardEyebrowTag,
            CompanionLaunchCardFactsTag, CompanionLaunchCardSynopsisTag,
        )) {
            if (count(tag) > 0) {
                assertInside("$label: $tag", boundsDp(tag), card)
                assertTrue("$label: $tag ends above the footer: ${boundsDp(tag).toList()} vs ${footer.toList()}", boundsDp(tag).let { it[1] + it[3] } <= footer[1] + 0.6f)
            }
        }
        for (tag in listOf(CompanionLaunchCardTitleTag, CompanionLaunchCardMetaTag, CompanionLaunchCardEyebrowTag, CompanionLaunchCardSynopsisTag)) {
            if (count(tag) > 0) {
                val layout = layouts(tag).single()
                // `didOverflowHeight` is also true for text truncated by its maxLines, which is the design here (an ellipsis); what must never happen is a box shorter than the lines it holds.
                assertTrue(
                    "$label: $tag is cut by its box: ${layout.size.height}px of ${layout.multiParagraph.height}px (${layout.lineCount} lines)",
                    layout.size.height >= layout.multiParagraph.height - 1f,
                )
            }
        }
        return card[3]
    }

    private fun assertStage(label: String, fontScale: Float, expectFull: Boolean) {
        val card = boundsDp(CompanionLaunchCardTag)
        val stage = boundsDp(CompanionLaunchCardStageTag)
        println("L5-CARD $label stage=${"%.1f".format(stage[2])}x${"%.1f".format(stage[3])} at (${"%.1f".format(stage[0] - card[0])},${"%.1f".format(stage[1] - card[1])}) of the card")
        assertTrue("$label: the stage is never below its minimum: ${stage.toList()}", stage[3] >= 95.5f)
        assertTrue("$label: the stage is never above the configured 430 x 215: ${stage.toList()}", stage[2] <= 430.6f && stage[3] <= 215.6f)
        assertNear("$label: the stage keeps its 2:1 shape (or its full width)", minOf(430f, stage[3] * 2f), stage[2], 1.2f)
        assertNear("$label: the stage is centred in the card", card[0] + card[2] / 2f, stage[0] + stage[2] / 2f)
        assertNear("$label: the stage starts 12 dp down", card[1] + 12f, stage[1])
        if (expectFull) {
            assertNear("$label: the configured stage is 430 wide", 430f, stage[2])
            assertNear("$label: the configured stage is 215 tall", 215f, stage[3])
        }
    }

    // ── Cinematic ──────────────────────────────────────────────────────────────────────────────────

    private fun cinematicFull(fontScale: Float) {
        mount(game(), CompanionLaunchLook.CINEMATIC, fontScale)
        awaitArt()
        assertCard("cinematic full", CompanionLaunchLook.CINEMATIC, fontScale)
        assertStage("cinematic full", fontScale, expectFull = fontScale == 1.0f)
        assertEquals("the hero is drawn behind the face", 1, count(CompanionLaunchCardHeroTag))
        val hero = boundsDp(CompanionLaunchCardHeroTag)
        val card = boundsDp(CompanionLaunchCardTag)
        assertNear("the hero covers the whole card", card[3], hero[3])
        assertNear("the hero covers the whole card's width", card[2], hero[2])
        val stage = boundsDp(CompanionLaunchCardStageTag)
        val title = boundsDp(CompanionLaunchCardTitleTag)
        val meta = boundsDp(CompanionLaunchCardMetaTag)
        assertTrue("the title is under the stage", title[1] >= stage[1] + stage[3] - 0.6f)
        assertTrue("the meta is under the title", meta[1] >= title[1] + title[3] - 0.6f)
        assertEquals("OverlayTitle", MimirTypeScale.OverlayTitle.fontSize.value, layouts(CompanionLaunchCardTitleTag).single().layoutInput.style.fontSize.value, 0.01f)
        assertEquals("the title says the game", "Sonic the Hedgehog 2", textOf(CompanionLaunchCardTitleTag))
        assertEquals("the meta says the platform", "Genesis", textOf(CompanionLaunchCardMetaTag))
        assertEquals("no Journal pieces", 0, count(CompanionLaunchCardFactsTag))
    }

    @Test fun `Cinematic with full data at font scale 1_0`() = cinematicFull(1.0f)
    @Test fun `Cinematic with full data at font scale 1_3`() = cinematicFull(1.3f)

    private fun cinematicNoHero(fontScale: Float) {
        mount(game(hero = null), CompanionLaunchLook.CINEMATIC, fontScale)
        awaitArt()
        assertCard("cinematic no hero", CompanionLaunchLook.CINEMATIC, fontScale)
        assertStage("cinematic no hero", fontScale, expectFull = fontScale == 1.0f)
        assertEquals("with no hero nothing is drawn behind the face (the glass shows, never a cover)", 0, count(CompanionLaunchCardHeroTag))
        assertEquals("the layout is the same: the title is still under the stage", "Sonic the Hedgehog 2", textOf(CompanionLaunchCardTitleTag))
    }

    @Test fun `Cinematic with no hero at font scale 1_0`() = cinematicNoHero(1.0f)
    @Test fun `Cinematic with no hero at font scale 1_3`() = cinematicNoHero(1.3f)

    private fun cinematicNoLogo(fontScale: Float) {
        mount(game(logo = null), CompanionLaunchLook.CINEMATIC, fontScale)
        awaitArt()
        assertCard("cinematic no logo", CompanionLaunchLook.CINEMATIC, fontScale)
        assertStage("cinematic no logo", fontScale, expectFull = false)
        assertEquals("the title is said once", 1, count(CompanionLaunchCardTitleTag))
        assertInside("and it takes the stage", boundsDp(CompanionLaunchCardTitleTag), boundsDp(CompanionLaunchCardStageTag))
        assertEquals("it is the stage's Title, not the under-stage OverlayTitle", MimirTypeScale.Title.fontSize.value, layouts(CompanionLaunchCardTitleTag).single().layoutInput.style.fontSize.value, 0.01f)
        assertEquals("the meta stays", "Genesis", textOf(CompanionLaunchCardMetaTag))
    }

    @Test fun `Cinematic with no logo at font scale 1_0`() = cinematicNoLogo(1.0f)
    @Test fun `Cinematic with no logo at font scale 1_3`() = cinematicNoLogo(1.3f)

    private val longTitle = "The Legend of the Extraordinarily Long Named Adventure Game Part Two: Return of the Great and Powerful Hedgehog Brothers Deluxe Edition"

    private fun cinematicLongTitle(fontScale: Float) {
        mount(game(title = longTitle), CompanionLaunchLook.CINEMATIC, fontScale)
        awaitArt()
        assertCard("cinematic long title", CompanionLaunchLook.CINEMATIC, fontScale)
        assertStage("cinematic long title", fontScale, expectFull = false)
        val layout = layouts(CompanionLaunchCardTitleTag).single()
        assertTrue("two lines at most, was ${layout.lineCount}", layout.lineCount <= 2)
        assertTrue("and the rest is ellipsized, not cut", layout.isLineEllipsized(layout.lineCount - 1))
    }

    @Test fun `Cinematic with a long title at font scale 1_0`() = cinematicLongTitle(1.0f)
    @Test fun `Cinematic with a long title at font scale 1_3`() = cinematicLongTitle(1.3f)

    // ── Logo Only ──────────────────────────────────────────────────────────────────────────────────

    private fun logoOnlyFull(fontScale: Float) {
        mount(game(), CompanionLaunchLook.LOGO_ONLY, fontScale)
        awaitArt()
        val height = assertCard("logo only full", CompanionLaunchLook.LOGO_ONLY, fontScale)
        assertStage("logo only full", fontScale, expectFull = true)
        assertEquals("with a logo the title is not said (the art carries its name)", 0, count(CompanionLaunchCardTitleTag))
        assertEquals("no meta", 0, count(CompanionLaunchCardMetaTag))
        assertEquals("no hero behind it: it is on the dark glass", 0, count(CompanionLaunchCardHeroTag))
        val stage = boundsDp(CompanionLaunchCardStageTag)
        val footer = boundsDp(CompanionLaunchCardFooterTag)
        assertNear("the footer is directly under the stage", stage[1] + stage[3], footer[1])
        if (fontScale == 1.0f) assertNear("Logo Only is the stage and the footer: 12 + 215 + 96", 323f, height, 1.2f)
    }

    @Test fun `Logo Only with full data at font scale 1_0`() = logoOnlyFull(1.0f)
    @Test fun `Logo Only with full data at font scale 1_3`() = logoOnlyFull(1.3f)

    private fun logoOnlyNoLogo(fontScale: Float) {
        mount(game(logo = null), CompanionLaunchLook.LOGO_ONLY, fontScale)
        awaitArt()
        assertCard("logo only no logo", CompanionLaunchLook.LOGO_ONLY, fontScale)
        assertEquals("with no logo the title takes the stage", 1, count(CompanionLaunchCardTitleTag))
        assertInside("inside the stage", boundsDp(CompanionLaunchCardTitleTag), boundsDp(CompanionLaunchCardStageTag))
    }

    @Test fun `Logo Only with no logo at font scale 1_0`() = logoOnlyNoLogo(1.0f)
    @Test fun `Logo Only with no logo at font scale 1_3`() = logoOnlyNoLogo(1.3f)

    private fun logoOnlyLongTitle(fontScale: Float) {
        mount(game(title = longTitle, logo = null), CompanionLaunchLook.LOGO_ONLY, fontScale)
        awaitArt()
        assertCard("logo only long title", CompanionLaunchLook.LOGO_ONLY, fontScale)
        val layout = layouts(CompanionLaunchCardTitleTag).single()
        assertTrue("three lines at most on the stage, was ${layout.lineCount}", layout.lineCount <= 3)
        assertInside("inside the stage", boundsDp(CompanionLaunchCardTitleTag), boundsDp(CompanionLaunchCardStageTag))
    }

    @Test fun `Logo Only with a long title at font scale 1_0`() = logoOnlyLongTitle(1.0f)
    @Test fun `Logo Only with a long title at font scale 1_3`() = logoOnlyLongTitle(1.3f)

    // ── Journal ────────────────────────────────────────────────────────────────────────────────────

    private fun journalFull(fontScale: Float) {
        mount(game(), CompanionLaunchLook.JOURNAL, fontScale)
        awaitArt()
        assertCard("journal full", CompanionLaunchLook.JOURNAL, fontScale)
        val card = boundsDp(CompanionLaunchCardTag)
        val cover = boundsDp(CompanionLaunchCardCoverTag)
        val facts = boundsDp(CompanionLaunchCardFactsTag)
        val title = boundsDp(CompanionLaunchCardTitleTag)
        val eyebrow = boundsDp(CompanionLaunchCardEyebrowTag)
        val synopsisBox = boundsDp(CompanionLaunchCardSynopsisTag)
        println("L5-CARD journal full fontScale=$fontScale cover=${"%.1f".format(cover[2])}x${"%.1f".format(cover[3])} facts=${"%.1f".format(facts[2])}x${"%.1f".format(facts[3])} synopsis=${"%.1f".format(synopsisBox[3])}")
        assertTrue("the cover keeps its 3:4 shape and its minimum: ${cover.toList()}", cover[3] >= 95.5f && cover[3] <= 200.6f && abs(cover[2] - cover[3] * 0.75f) <= 1.2f)
        if (fontScale == 1.0f) {
            assertNear("the cover is 150 wide", 150f, cover[2])
            assertNear("the cover is 200 tall", 200f, cover[3])
        }
        assertEquals("one eyebrow, the platform", "GENESIS", textOf(CompanionLaunchCardEyebrowTag))
        assertEquals("OverlayTitle", MimirTypeScale.OverlayTitle.fontSize.value, layouts(CompanionLaunchCardTitleTag).single().layoutInput.style.fontSize.value, 0.01f)
        assertTrue("the eyebrow is above the title", eyebrow[1] + eyebrow[3] <= title[1] + 0.6f)
        assertTrue("the title and the facts sit beside the cover, not under it", title[0] >= cover[0] + cover[2] - 0.6f && facts[0] >= cover[0] + cover[2] - 0.6f)
        assertEquals("a 2 x 2 grid: two rows of two cells", 2, listOf(CompanionLaunchCardFactLastPlayedTag, CompanionLaunchCardFactLaunchesTag, CompanionLaunchCardFactSessionTimeTag, CompanionLaunchCardFactAchievementsTag)
            .map { boundsDp(it)[1].let { y -> Math.round(y * 2f) } }.distinct().size)
        assertEquals(listOf("Last played", "Yesterday"), wordsUnder(CompanionLaunchCardFactLastPlayedTag))
        assertEquals(listOf("Launches", "14"), wordsUnder(CompanionLaunchCardFactLaunchesTag))
        assertEquals(listOf("Session time", "18 h 40 min"), wordsUnder(CompanionLaunchCardFactSessionTimeTag))
        assertTrue("achievements say the counts: ${wordsUnder(CompanionLaunchCardFactAchievementsTag)}", wordsUnder(CompanionLaunchCardFactAchievementsTag).containsAll(listOf("Achievements", "12 of 40")))
        val synopsisLayout = layouts(CompanionLaunchCardSynopsisTag).single()
        assertEquals("the synopsis is three lines", 3, synopsisLayout.lineCount)
        assertTrue("and the rest is ellipsized", synopsisLayout.isLineEllipsized(2))
        assertTrue("the synopsis is under the cover and the facts", synopsisBox[1] >= cover[1] + cover[3] - 0.6f && synopsisBox[1] >= facts[1] + facts[3] - 0.6f)
        assertTrue(
            "the synopsis stays inside the card's 16 dp insets: ${synopsisBox.toList()} in ${card.toList()}",
            synopsisBox[0] >= card[0] + 16f - 0.6f && synopsisBox[0] + synopsisBox[2] <= card[0] + card[2] - 16f + 0.6f,
        )
        assertEquals("no stage", 0, count(CompanionLaunchCardStageTag))
        assertEquals("no hero behind the Journal", 0, count(CompanionLaunchCardHeroTag))
    }

    @Test fun `Journal with full data at font scale 1_0`() = journalFull(1.0f)
    @Test fun `Journal with full data at font scale 1_3`() = journalFull(1.3f)

    /**
     * Thor drive 2026-10-08 (D43), the Bottom's seam: the Journal's synopsis is the decoded text. The stored description keeps its references; what the card draws and says
     * has them decoded, and once (a double-escaped "&amp;quot;" reads "&quot;", not a quotation mark).
     */
    @Test fun `the Journal synopsis shows decoded character references, once, never the stored ones`() {
        val stored = "Play the &quot;Pop-Up Duel.&quot; &amp; more &amp;quot;"
        mount(game(hero = null, logo = null, description = stored), CompanionLaunchLook.JOURNAL, 1.0f)
        val shown = textOf(CompanionLaunchCardSynopsisTag)
        assertEquals("Play the \"Pop-Up Duel.\" & more &quot;", shown)
        assertFalse("no stored reference reaches the screen: $shown", shown.contains("&quot;Pop-Up") || shown.contains("&amp;"))
        assertEquals("the one synopsis row is the only one", 1, count(CompanionLaunchCardSynopsisTag))
    }

    private fun journalAbsences(fontScale: Float) {
        mount(
            game(hero = null, logo = null, description = null, lastPlayed = null, plays = 0),
            CompanionLaunchLook.JOURNAL,
            fontScale,
            facts = CompanionLaunchCardFacts.None,
        )
        awaitArt()
        assertCard("journal absences", CompanionLaunchLook.JOURNAL, fontScale)
        assertEquals(listOf("Last played", "Never played"), wordsUnder(CompanionLaunchCardFactLastPlayedTag))
        assertEquals(listOf("Launches", "Never played"), wordsUnder(CompanionLaunchCardFactLaunchesTag))
        assertEquals(listOf("Session time", "Not tracked"), wordsUnder(CompanionLaunchCardFactSessionTimeTag))
        assertEquals(listOf("Achievements", "Progress unavailable"), wordsUnder(CompanionLaunchCardFactAchievementsTag))
        assertEquals("no synopsis row when there is no cached one (never invented)", 0, count(CompanionLaunchCardSynopsisTag))
        // The grid's wording is the pure function's.
        val pure = companionJournalFacts(game(description = null, lastPlayed = null, plays = 0), CompanionLaunchCardFacts.None, now, java.time.ZoneOffset.UTC)
        assertEquals(CompanionJournalNeverPlayed, pure.lastPlayed.value)
    }

    @Test fun `Journal with every absence at font scale 1_0`() = journalAbsences(1.0f)
    @Test fun `Journal with every absence at font scale 1_3`() = journalAbsences(1.3f)

    private fun journalAbsencesWithSynopsis(fontScale: Float) {
        // The tightest Journal: every fact absent (the wrapped "Progress unavailable" makes the facts column tall) and a cached synopsis under it.
        mount(
            game(hero = null, logo = null, lastPlayed = null, plays = 0),
            CompanionLaunchLook.JOURNAL,
            fontScale,
            facts = CompanionLaunchCardFacts.None,
        )
        awaitArt()
        assertCard("journal absences with synopsis", CompanionLaunchLook.JOURNAL, fontScale)
        val synopsisLayout = layouts(CompanionLaunchCardSynopsisTag).single()
        println("L5-CARD journal absences with synopsis fontScale=$fontScale synopsisLines=${synopsisLayout.lineCount}")
        assertTrue("the synopsis keeps one to three lines, never a cut one, never in the footer", synopsisLayout.lineCount in 1..3)
        assertTrue("and is ellipsized when it is a long one", synopsisLayout.isLineEllipsized(synopsisLayout.lineCount - 1))
    }

    @Test fun `Journal with every absence and a synopsis at font scale 1_0`() = journalAbsencesWithSynopsis(1.0f)
    @Test fun `Journal with every absence and a synopsis at font scale 1_3`() = journalAbsencesWithSynopsis(1.3f)

    private fun journalNoLogoNoHero(fontScale: Float) {
        mount(game(hero = null, logo = null, description = null), CompanionLaunchLook.JOURNAL, fontScale)
        awaitArt()
        assertCard("journal no hero no logo no synopsis", CompanionLaunchLook.JOURNAL, fontScale)
        assertEquals("no synopsis row", 0, count(CompanionLaunchCardSynopsisTag))
        assertEquals("the Journal needs neither: the title is still said", "Sonic the Hedgehog 2", textOf(CompanionLaunchCardTitleTag))
    }

    @Test fun `Journal with no hero and no logo at font scale 1_0`() = journalNoLogoNoHero(1.0f)
    @Test fun `Journal with no hero and no logo at font scale 1_3`() = journalNoLogoNoHero(1.3f)

    private fun journalLongTitle(fontScale: Float) {
        mount(game(title = longTitle), CompanionLaunchLook.JOURNAL, fontScale)
        awaitArt()
        assertCard("journal long title", CompanionLaunchLook.JOURNAL, fontScale)
        val layout = layouts(CompanionLaunchCardTitleTag).single()
        assertEquals("the title is two lines", 2, layout.lineCount)
        assertTrue("and ellipsized", layout.isLineEllipsized(1))
    }

    @Test fun `Journal with a long title at font scale 1_0`() = journalLongTitle(1.0f)
    @Test fun `Journal with a long title at font scale 1_3`() = journalLongTitle(1.3f)

    // ── A hero draws bare or nothing ───────────────────────────────────────────────────────────────

    /** The body's pixels (the card above its footer, 8 dp in from the sides and top), every other pixel, as the window drew them. */
    private fun bodyPixels(): IntArray {
        val bitmap = windowBitmap()
        val card = node(CompanionLaunchCardTag).boundsInWindow
        val footer = node(CompanionLaunchCardFooterTag).boundsInWindow
        val x0 = (card.left + 8f * density).toInt()
        val x1 = (card.right - 8f * density).toInt()
        val y0 = (card.top + 8f * density).toInt()
        val y1 = footer.top.toInt()
        val out = ArrayList<Int>()
        for (y in y0 until y1 step 2) for (x in x0 until x1 step 2) out += bitmap.getPixel(x, y)
        return out.toIntArray()
    }

    @Test
    fun `a hero whose file is deleted draws nothing, the book and the scrim included, in any frame of its exit fade`() {
        val gone = png(32, 32) { it.drawColor(AndroidColor.rgb(200, 40, 40)) }
        File(java.net.URI(gone.replaceFirst("file:///", "file:/"))).delete()
        // No logo, so the body is static text on the glass; the first frame is before the file has been checked (the hero layer is up, as a hero being checked is).
        mount(game(hero = gone, logo = null), CompanionLaunchLook.CINEMATIC, 1.0f, settleAfter = false)
        compose.mainClock.advanceTimeBy(16L)
        compose.waitForIdle()
        assertEquals("the hero layer is up in the first frame", 1, count(CompanionLaunchCardHeroTag))
        val frames = mutableListOf(bodyPixels())
        repeat(40) {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(25L)
            compose.waitForIdle()
            frames += bodyPixels()
        }
        assertEquals("the check found the file gone and the layer left", 0, count(CompanionLaunchCardHeroTag))
        val settled = frames.last()
        val different = frames.indices.filter { !frames[it].contentEquals(settled) }
        println("L5-HERO frames=${frames.size} differing from the settled glass=$different")
        assertTrue("a deleted hero must draw nothing in every frame, but frames $different differ from the glass the card settles on", different.isEmpty())
    }

    // ── The body never leaves its allocation ───────────────────────────────────────────────────────

    private fun fullyVisible(tag: String): Boolean {
        val n = node(tag)
        return n.boundsInRoot.height >= n.size.height - 1.5f && n.boundsInRoot.width >= n.size.width - 1.5f
    }

    @Test
    fun `at font scale 2_0 the worst Journal keeps the body above the footer, scrolls inside its frame, and cuts no glyph`() {
        // A long title, every fact absent (so the cells wrap), and a synopsis: more than the allocation holds at this size.
        mount(game(title = longTitle, hero = null, logo = null, lastPlayed = null, plays = 0), CompanionLaunchLook.JOURNAL, 2.0f, facts = CompanionLaunchCardFacts.None)
        awaitArt()
        val card = boundsDp(CompanionLaunchCardTag)
        val footer = boundsDp(CompanionLaunchCardFooterTag)
        val body = boundsDp(CompanionLaunchCardBodyTag)
        val play = boundsDp(CompanionLaunchCardPlayTag)
        val range = node(CompanionLaunchCardBodyTag).config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
        println(
            "L5-CARD journal worst case fontScale=2.0 card=${"%.1f".format(card[2])}x${"%.1f".format(card[3])} body=${body.toList()} footer=${footer.toList()} " +
                "scrollMax=${range?.maxValue?.invoke()} synopsis=${count(CompanionLaunchCardSynopsisTag)}",
        )
        assertTrue("the card keeps its dismiss strips: ${card.toList()}", card[1] >= dismissMargin - 0.6f && hostHeightDp - (card[1] + card[3]) >= dismissMargin - 0.6f)
        assertTrue("the body's frame ends above the footer, so nothing of it is in PLAY's ring room: ${body.toList()} vs ${footer.toList()}", body[1] + body[3] <= footer[1] + 0.6f)
        assertInside("the body in the card", body, card)
        assertTrue("PLAY is 48 dp: ${play.toList()}", play[2] >= 47.5f && play[3] >= 47.5f)
        assertEquals("with no room the synopsis row is absent, not a cut line", 0, count(CompanionLaunchCardSynopsisTag))
        assertTrue("the facts are taller than the allocation, so the body scrolls inside it", (range?.maxValue?.invoke() ?: 0f) > 0f)
        // At rest the top is whole; every text box is as tall as its lines (never cut by its own box).
        assertTrue("the eyebrow is whole at rest", fullyVisible(CompanionLaunchCardEyebrowTag))
        for (tag in listOf(CompanionLaunchCardEyebrowTag, CompanionLaunchCardTitleTag)) {
            val layout = layouts(tag).single()
            assertTrue("$tag is cut by its own box", layout.size.height >= layout.multiParagraph.height - 1f)
        }
        // Scrolled to the end, the last fact is whole, still above the footer.
        repeat(3) {
            compose.onNodeWithTag(CompanionLaunchCardBodyTag, useUnmergedTree = true).performTouchInput { swipeUp() }
            settle(300L)
        }
        assertTrue("the last fact is whole at the end of the scroll", fullyVisible(CompanionLaunchCardFactAchievementsTag))
        val last = boundsDp(CompanionLaunchCardFactAchievementsTag)
        assertTrue("and above the footer: ${last.toList()} vs ${footer.toList()}", last[1] + last[3] <= footer[1] + 0.6f)
        assertTrue("the body's own frame did not move", boundsDp(CompanionLaunchCardBodyTag).contentEquals(body))
    }

    @Test
    fun `at font scale 2_0 the worst Cinematic keeps the body above the footer`() {
        mount(game(title = longTitle), CompanionLaunchLook.CINEMATIC, 2.0f)
        awaitArt()
        val card = boundsDp(CompanionLaunchCardTag)
        val footer = boundsDp(CompanionLaunchCardFooterTag)
        val body = boundsDp(CompanionLaunchCardBodyTag)
        println("L5-CARD cinematic worst case fontScale=2.0 card=${"%.1f".format(card[2])}x${"%.1f".format(card[3])} body=${body.toList()} footer=${footer.toList()}")
        assertTrue("the card keeps its dismiss strips: ${card.toList()}", card[1] >= dismissMargin - 0.6f && hostHeightDp - (card[1] + card[3]) >= dismissMargin - 0.6f)
        assertTrue("the body's frame ends above the footer: ${body.toList()} vs ${footer.toList()}", body[1] + body[3] <= footer[1] + 0.6f)
        for (tag in listOf(CompanionLaunchCardTitleTag, CompanionLaunchCardMetaTag)) {
            val layout = layouts(tag).single()
            assertTrue("$tag is cut by its own box", layout.size.height >= layout.multiParagraph.height - 1f)
        }
        assertTrue("the stage is never below its minimum", boundsDp(CompanionLaunchCardStageTag)[3] >= 95.5f)
    }

    // ── The look switch ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the switch cycles Cinematic to Journal to Logo Only to Cinematic, reports each, and writes one opaque receipt line per tap`() {
        mount(game(), CompanionLaunchLook.CINEMATIC, 1.0f, display = 4)
        awaitArt()
        assertEquals(listOf("Look", "Cinematic"), wordsUnder(CompanionLaunchCardLookTag))
        compose.onNodeWithTag(CompanionLaunchCardLookTag).performClick()
        settle()
        assertEquals(listOf(CompanionLaunchLook.JOURNAL), lookRequests)
        assertEquals("the card changed face", 4, listOf(CompanionLaunchCardFactLastPlayedTag, CompanionLaunchCardFactLaunchesTag, CompanionLaunchCardFactSessionTimeTag, CompanionLaunchCardFactAchievementsTag).sumOf { count(it) })
        assertEquals("and the stage left", 0, count(CompanionLaunchCardStageTag))
        assertEquals(listOf("Look", "Journal"), wordsUnder(CompanionLaunchCardLookTag))
        assertCard("after the first tap", CompanionLaunchLook.JOURNAL, 1.0f)
        compose.onNodeWithTag(CompanionLaunchCardLookTag).performClick()
        settle()
        assertEquals(listOf("Look", "Logo only"), wordsUnder(CompanionLaunchCardLookTag))
        assertEquals("Logo Only: the stage, no facts", 1, count(CompanionLaunchCardStageTag))
        assertEquals(0, count(CompanionLaunchCardFactsTag))
        compose.onNodeWithTag(CompanionLaunchCardLookTag).performClick()
        settle()
        assertEquals(listOf(CompanionLaunchLook.JOURNAL, CompanionLaunchLook.LOGO_ONLY, CompanionLaunchLook.CINEMATIC), lookRequests)
        assertEquals(listOf("Look", "Cinematic"), wordsUnder(CompanionLaunchCardLookTag))
        val lines = ShadowLog.getLogsForTag(com.gruning.mimir.rewrite.core.ui.BottomUiReceiptFormat.TAG).map { it.msg }
        val look = lines.filter { it.contains("id=card.look_") }
        println("L5-CARD receipts $look")
        assertEquals(3, look.size)
        assertTrue(look[0], look[0].startsWith("ev=control.activate id=card.look_journal display=4"))
        assertTrue(look[1], look[1].startsWith("ev=control.activate id=card.look_logo_only display=4"))
        assertTrue(look[2], look[2].startsWith("ev=control.activate id=card.look_cinematic display=4"))
        assertTrue("never a title: $look", look.none { it.contains("Sonic") })
    }

    @Test
    fun `a change of look crossfades on the swap tier, then settles on the new body alone`() {
        mount(game(), CompanionLaunchLook.CINEMATIC, 1.0f)
        awaitArt()
        compose.onNodeWithTag(CompanionLaunchCardLookTag).performClick()
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(60L)
        compose.waitForIdle()
        assertEquals("mid-swap the departing body is still composed", 1, count(CompanionLaunchCardStageTag))
        assertEquals("and the arriving one is too", 1, count(CompanionLaunchCardFactsTag))
        settle(800L)
        assertEquals("after the swap the old body is gone", 0, count(CompanionLaunchCardStageTag))
        assertEquals(1, count(CompanionLaunchCardFactsTag))
    }

    @Test
    fun `under reduced motion a change of look snaps`() {
        setAnimatorScale(0f)
        mount(game(), CompanionLaunchLook.CINEMATIC, 1.0f)
        awaitArt()
        compose.onNodeWithTag(CompanionLaunchCardLookTag).performClick()
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(60L)
        compose.waitForIdle()
        assertEquals("the old body is gone at once", 0, count(CompanionLaunchCardStageTag))
        assertEquals(1, count(CompanionLaunchCardFactsTag))
    }

    @Test
    fun `PLAY and Opens on act once and a card that is not active takes neither`() {
        mount(game(), CompanionLaunchLook.CINEMATIC, 1.0f)
        compose.onNodeWithTag(CompanionLaunchCardPlayTag).performClick()
        compose.onNodeWithTag(CompanionLaunchCardOpensOnTag).performClick()
        assertEquals(1, plays)
        assertEquals(1, edits)
        assertEquals("Opens on says where it opens", listOf("Opens on", "Ask next time"), wordsUnder(CompanionLaunchCardOpensOnTag))
    }

    @Test
    fun `an inactive card is inert on PLAY and the switch`() {
        mount(game(), CompanionLaunchLook.CINEMATIC, 1.0f, active = false)
        compose.onNodeWithTag(CompanionLaunchCardPlayTag).performClick()
        compose.onNodeWithTag(CompanionLaunchCardLookTag).performClick()
        assertEquals(0, plays)
        assertEquals(emptyList<CompanionLaunchLook>(), lookRequests)
    }

    // ── PLAY wears no ring ─────────────────────────────────────────────────────────────────────────

    private fun windowBitmap(): Bitmap {
        val view = checkNotNull(rootView) { "the content has not been composed" }
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    private fun distance(a: Int, b: Int): Int =
        abs(AndroidColor.red(a) - AndroidColor.red(b)) + abs(AndroidColor.green(a) - AndroidColor.green(b)) + abs(AndroidColor.blue(a) - AndroidColor.blue(b))

    /**
     * Update (only the focused element wears a ring; a ring that never moves reads as a fill): PLAY is the primary etched chip and draws NO ring or halo.
     * The band from the chip's edge outward on each side (2 to 17 dp, where the ring and its halos used to stand) is the same glass as the card beside it, whose reference is
     * the glass at the same height or depth left of the footer, where nothing is drawn.
     */
    private fun assertNoRingAround(progress: Float) {
        mount(game(hero = null), CompanionLaunchLook.LOGO_ONLY, 1.0f, progress = progress)
        awaitArt()
        settle()
        val bitmap = windowBitmap()
        val node = node(CompanionLaunchCardPlayTag)
        val pill = node.boundsInWindow
        val card = node(CompanionLaunchCardTag).boundsInWindow
        val inked = (0 until bitmap.width step 4).sumOf { x -> (0 until bitmap.height step 4).count { y -> bitmap.getPixel(x, y) != 0 } }
        println("L5-RING progress=$progress bitmap=${bitmap.width}x${bitmap.height} pill=$pill card=$card inked=$inked")
        assertTrue("the window drew nothing, so the sample would prove nothing", inked > 100)
        val px = density
        fun at(x: Float, y: Float): Int {
            assertTrue("sample ($x, $y) is outside the ${bitmap.width}x${bitmap.height} window", x >= 0 && y >= 0 && x < bitmap.width && y < bitmap.height)
            return bitmap.getPixel(x.toInt(), y.toInt())
        }
        val refX = card.left + 10f * px
        val cx = pill.center.x
        val cy = pill.center.y
        data class Side(val name: String, val sample: (Float) -> Pair<Float, Float>)
        val sides = listOf(
            Side("below") { d -> cx to pill.bottom + d * px },
            Side("above") { d -> cx to pill.top - d * px },
            Side("right") { d -> pill.right + d * px to cy },
        )
        for (side in sides) {
            var peak = 0
            val profile = StringBuilder()
            var d = 2f
            while (d <= 17f) {
                val (x, y) = side.sample(d)
                val delta = distance(at(x, y), at(refX, y))
                if (delta > peak) peak = delta
                profile.append(delta).append(' ')
                d += 1f
            }
            println("L5-RING progress=$progress ${side.name} peak=$peak profile(2..17dp)=$profile")
            assertTrue("no ring ${side.name} PLAY: the glass there is the glass beside it (peak $peak, profile $profile)", peak <= 14)
        }
    }

    @Test
    fun `PLAY draws no ring or halo on the card at rest`() = assertNoRingAround(progress = 1f)

    @Test
    fun `PLAY draws no ring or halo while the card arrives either`() = assertNoRingAround(progress = 0.5f)

    // ── update: a picture of PLAY ──────────────────────────────────────────────────────────────

    @Test
    fun `PLAY is the primary etched chip, drawn on the card and saved as a picture`() {
        mount(game(), CompanionLaunchLook.JOURNAL, 1.0f)
        awaitArt()
        settle()
        val play = node(CompanionLaunchCardPlayTag).boundsInWindow
        println("BTN265 PLAY ${play.width / density}x${play.height / density} dp")
        assertTrue("PLAY keeps its 48 dp height and its width", play.height / density >= 47.5f && play.width / density >= 47.5f)
        val bitmap = windowBitmap()
        val dir = File("build/btn265").apply { mkdirs() }
        val out = File(dir, "btn265-play-card.png")
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val shared = File("build/test-captures")
        if (shared.isDirectory) out.copyTo(File(shared, "btn265-play-card.png"), overwrite = true)
        println("BTN265 wrote ${out.absolutePath} (${out.length()} bytes, ${bitmap.width}x${bitmap.height} px)")
        assertTrue("the PNG was written: ${out.path}", out.isFile && out.length() > 1_000L)
    }
}
