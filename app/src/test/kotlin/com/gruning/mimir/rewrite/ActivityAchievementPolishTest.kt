package com.gruning.mimir.rewrite

import android.animation.ValueAnimator
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsAchievement
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsProgress
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsUserGame
import com.gruning.mimir.rewrite.core.ui.LocalMimirMotionLive
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirAchievementCardState
import com.gruning.mimir.rewrite.core.ui.MimirAchievementCopy
import com.gruning.mimir.rewrite.core.ui.MimirFocusFlareReach
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/*
 * Thor device feedback, 2026-10-07, on the achievement pages (Main's and the Bottom's Activity page): the badge ring, the console picker, the "more below" cue and the vertical snap.
 * What is JVM-provable is proved here on the real composables; the pixels and the feel of a finger on a handheld are device-owed.
 */

/** A PNG that really decodes: one flat colour, [size] px square. */
private fun writePng(dir: File, name: String, red: Int, green: Int, blue: Int, size: Int = 8): File {
    val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(android.graphics.Color.rgb(red, green, blue))
    return File(dir, name).also { file -> file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
}

/** Hands every badge the same staged file, or none at all (a badge that has not loaded yet). */
private class StagedBadges(private val file: File?) : ActivityBadgeSource {
    val asked = CopyOnWriteArrayList<String>()

    override suspend fun acquire(path: String): ActivityIconLease? {
        asked += path
        return file?.let { ActivityIconLease(it) {} }
    }
}

private class QuietPadHost : ActivityPadHost {
    override fun navigationEnabled(): Boolean = true

    override fun activate(key: FocusKey): FocusKey? = null

    override fun page(delta: Int): Boolean = false

    override fun leave() = Unit

    override fun dismissModal(): FocusKey? = null
}

/**
 * The pure rules behind the five reported issues, and the console diagnosis: where the ring rests, where a card must settle, when the cue shows, how the position is worded, and where
 * a console name comes from and goes.
 */
class ActivityAchievementPolishRulesTest {

    @Test
    fun `the ring follows the pad while its ring shows and rests on the selected badge when it does not, whatever the badge's art`() {
        // After a touch (the pad's ring dark): the tapped badge wears the ring, and nothing else does.
        assertEquals(ActivityBadgeRing(lit = true, plate = false), activityBadgeRing(selected = true, padFocused = false, padEngaged = false))
        assertEquals(ActivityBadgeRing(lit = false, plate = false), activityBadgeRing(selected = false, padFocused = false, padEngaged = false))
        // The pad's ring showing: it is on the focused badge; a selected one elsewhere keeps only the quiet plate, never a second ring.
        assertEquals(ActivityBadgeRing(lit = true, plate = false), activityBadgeRing(selected = false, padFocused = true, padEngaged = true))
        assertEquals(ActivityBadgeRing(lit = true, plate = false), activityBadgeRing(selected = true, padFocused = true, padEngaged = true))
        assertEquals(ActivityBadgeRing(lit = false, plate = true), activityBadgeRing(selected = true, padFocused = false, padEngaged = true))
        assertEquals(ActivityBadgeRing(lit = false, plate = false), activityBadgeRing(selected = false, padFocused = false, padEngaged = true))
    }

    @Test
    fun `a list that rests off a card start is sent to the nearest start, the same going up as going down`() {
        // Aligned (or within a pixel of aligned) rests where it is.
        assertNull(activityCardSettleTarget(firstIndex = 2, offsetPx = 0, itemSizePx = 400, itemCount = 5))
        assertNull(activityCardSettleTarget(firstIndex = 2, offsetPx = 1, itemSizePx = 400, itemCount = 5))
        assertNull(activityCardSettleTarget(firstIndex = 2, offsetPx = 399, itemSizePx = 400, itemCount = 5))
        // Going DOWN the list left the next card cut off: a short way past a start goes back to it, past halfway goes on to the next.
        assertEquals(2, activityCardSettleTarget(firstIndex = 2, offsetPx = 120, itemSizePx = 400, itemCount = 5))
        assertEquals(3, activityCardSettleTarget(firstIndex = 2, offsetPx = 280, itemSizePx = 400, itemCount = 5))
        // Going UP leaves the card below peeking: the first visible card is the one above, scrolled well into view; the rule is the same, and it lands on the card whole.
        assertEquals(1, activityCardSettleTarget(firstIndex = 1, offsetPx = 100, itemSizePx = 400, itemCount = 5))
        assertEquals(2, activityCardSettleTarget(firstIndex = 1, offsetPx = 300, itemSizePx = 400, itemCount = 5))
        // Exactly halfway goes forward (the rule is symmetric: never a bias that favours one direction), and the target never leaves the list.
        assertEquals(1, activityCardSettleTarget(firstIndex = 0, offsetPx = 200, itemSizePx = 400, itemCount = 5))
        assertEquals(4, activityCardSettleTarget(firstIndex = 4, offsetPx = 300, itemSizePx = 400, itemCount = 5))
        assertNull(activityCardSettleTarget(firstIndex = 0, offsetPx = 120, itemSizePx = 0, itemCount = 5))
        assertNull(activityCardSettleTarget(firstIndex = 0, offsetPx = 120, itemSizePx = 400, itemCount = 0))
    }

    @Test
    fun `the more-below cue shows while a card is below, never on the last card, never with one card`() {
        assertFalse(activityCueVisible(page = 0, pages = 0))
        assertFalse(activityCueVisible(page = 0, pages = 1))
        assertTrue(activityCueVisible(page = 0, pages = 2))
        assertTrue(activityCueVisible(page = 1, pages = 3))
        assertFalse("the last card has nothing below it", activityCueVisible(page = 2, pages = 3))
        // It plays on the house clock: its first play waits two overlay beats, a replay waits for idle, and a play is a few beats.
        assertEquals(MimirMotion.OverlayMs * 2L, ACTIVITY_CUE_OPEN_DELAY_MS)
        assertTrue(ACTIVITY_CUE_IDLE_REPLAY_MS >= 5_000L)
        assertTrue(ACTIVITY_CUE_BEATS in 1..3)
    }

    @Test
    fun `the position is worded as a place in a vertical list and never as a page`() {
        assertEquals("Game 3 of 12", activityPositionText(page = 2, pages = 12))
        assertEquals("Game 1 of 47", activityPositionText(page = 0, pages = 47))
        listOf(activityPositionText(0, 2), ActivityMoreBelowDescription).forEach { words ->
            assertFalse("no page, no left, no right: $words", listOf("page", "left", "right", "next page").any { it in words.lowercase() })
        }
        assertTrue("the cue says which way to swipe", "Swipe up" in ActivityMoreBelowDescription)
    }

    @Test
    fun `the console name travels from the bulk read to the snapshot in RA's own full name, and a launch-card check of the same game keeps it`() {
        // Diagnosis (Thor 10-07, missing console choices): the name rides ConsoleName -> RetroAchievementsUserGame -> matchActivityBulk -> publishBulk. It is the snapshot
        // a launch-card check mints that has none, and a check keyed by the library game must take it from the bulk row of the same RA game (keyed `ra:<id>` when unmatched).
        CompanionActivityAchievements.resetForTest()
        val games = listOf(
            RetroAchievementsUserGame(77, "Final Fantasy X", "PlayStation 2", earned = 3, total = 40),
            RetroAchievementsUserGame(78, "Metroid", "NES/Famicom", earned = 1, total = 12),
        )
        val bulk = matchActivityBulk(
            games = games,
            local = emptyList(),
            platformIdFor = { null },
            knownLocalByRaId = emptyMap(),
            account = "me",
            nowMillis = 5_000L,
        )
        assertEquals(listOf("PlayStation 2", "NES/Famicom"), bulk.map { it.consoleName })
        assertEquals("the bulk row is keyed by RA's own id until the library is matched", "ra:77", bulk.first().gameId)
        CompanionActivityAchievements.publishBulk("me", bulk, totalPoints = null)

        val check = CompanionActivityAchievementSnapshot(
            gameId = "local-ffx",
            account = "me",
            unlocked = 4,
            total = 40,
            gameTitle = "Final Fantasy X",
            checkedAtEpochMillis = 6_000L,
            raGameId = 77,
        )
        assertNull("a launch-card check carries no console of its own", check.consoleName)
        CompanionActivityAchievements.publish(
            gameId = "local-ffx",
            account = "me",
            lane = MimirAchievementLaneState.Unresolved(MimirAchievementUnresolvedReason.UNREACHABLE),
            snapshot = check,
        )
        val held = CompanionActivityAchievements.snapshots.value.first { it.gameId == "local-ffx" }
        assertEquals("it takes the console from the bulk row of the same RA game", "PlayStation 2", held.consoleName)
        // And the picker's index lists both, by those names, with their counts: "PlayStation 2 (2)" is one console holding the check and the bulk row.
        val index = ActivityAchievementIndex(CompanionActivityAchievements.snapshots.value)
        assertEquals(listOf("NES/Famicom", "PlayStation 2"), index.consoles.map { it.name })
        assertEquals("PlayStation 2 (2)", index.consoles.last().label)
        CompanionActivityAchievements.resetForTest()
    }

    @Test
    fun `a launch-card check names the console from the per-game answer, so the picker lists it even when no bulk read has said`() {
        CompanionActivityAchievements.resetForTest()
        val progress = RetroAchievementsProgress(
            gameId = 77,
            title = "Final Fantasy X",
            achievements = listOf(
                RetroAchievementsAchievement(id = 1, title = "A", description = "a", points = 5, unlocked = true, unlockedHardcore = false, badgeName = "00001", earnedAt = "2023-05-01 10:00:00"),
                RetroAchievementsAchievement(id = 2, title = "B", description = "b", points = 5, unlocked = false, unlockedHardcore = false, badgeName = "00002"),
            ),
            consoleName = " PlayStation 2 ",
        )
        val checked = CompanionActivityAchievements.snapshotFor("local-ffx", "me", MimirAchievementLaneState.Loaded(progress))
        assertNotNull(checked)
        assertEquals("trimmed, in RA's own words", "PlayStation 2", checked!!.consoleName)
        assertNull("an answer with no console leaves none (the bulk read's, if any, is inherited on publish)", CompanionActivityAchievements.snapshotFor("g", "me", MimirAchievementLaneState.Loaded(progress.copy(consoleName = null)))!!.consoleName)
        val index = ActivityAchievementIndex(listOf(checked))
        assertEquals(listOf(ActivityConsoleCount("PlayStation 2", 1)), index.consoles)
    }

    @Test
    fun `the sources reuse the shared ring and the Bottom's glass pieces, and keep the repo's focus and colour rules`() {
        fun source(name: String): String =
            (File("src/main/kotlin/com/gruning/mimir/rewrite/$name").takeIf { it.isFile } ?: File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name")).readText()
        val panel = source("CompanionActivityAchievementPanel.kt")
        assertTrue("the badge ring is the one product ring", panel.contains(".mimirSelectionRing("))
        assertFalse("and not a plain border", panel.contains("Modifier.border(") || panel.contains(".border("))
        val hub = source("CompanionActivityAchievementHub.kt")
        val header = source("CompanionActivityAchievementFilterHeader.kt")
        assertTrue("the page behind the picker blurs through core-ui's own plan", hub.contains("mimirGlassBackdropProgress(glass)"))
        assertTrue("the picker's scrim and glass panel are core-ui's", header.contains("MimirGlassScrim(") && header.contains("MimirLiquidGlassPanel("))
        assertFalse("the old drop-down is gone", header.contains("fun ActivityConsoleList("))
        listOf(panel, hub, header, source("CompanionActivityAchievementPaging.kt"), source("CompanionActivityPad.kt")).forEach { text ->
            assertFalse("never Compose focus", text.contains("focusable()") || text.contains("focusTarget()"))
            assertFalse("no hex colour literal", Regex("Color\\(0x[0-9A-Fa-f]+\\)").containsMatchIn(text))
        }
    }
}

/**
 * The badge ring on the REAL tile: the same ring on an earned, a locked and a still-loading badge, drawn above the art as a sibling that no art layer contains, in the palette's
 * ring colour; the pad's focus and a touch's selection share it; and (with animators off, so the ring snaps) its pixels, in the accent colour, around the tile.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActivityBadgeRingRenderTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val temp = TemporaryFolder()

    private val density369 = 369f / 160f
    private var ringColor: Color = Color.Unspecified
    private val taps = CopyOnWriteArrayList<String>()

    @After
    fun resetAnimators() = setAnimatorScale(1f)

    private fun setAnimatorScale(scale: Float) {
        ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, scale)
    }

    @Composable
    private fun Tiles(source: ActivityBadgeSource, selected: Boolean, padKeys: Map<String, FocusKey?> = emptyMap(), selectedTag: String? = null) {
        ringColor = LocalMimirPalette.current.tileFocusRing
        Row(Modifier.padding(24.dp)) {
            listOf("earned" to "/Badge/00001.png", "locked" to "/Badge/00002_lock.png", "loading" to null).forEach { (tag, path) ->
                ActivityBadgeTile(
                    path = path,
                    size = 56.dp,
                    selected = if (selectedTag != null) tag == selectedTag else selected,
                    source = if (tag == "loading") StagedBadges(null) else source,
                    label = tag,
                    contentDescription = "$tag badge",
                    tag = tag,
                    onActivate = { taps += tag },
                    padKey = padKeys[tag],
                )
                Spacer(Modifier.width(24.dp))
            }
        }
    }

    private fun mount(content: @Composable () -> Unit) {
        compose.setContent { MimirTheme { content() } }
        compose.waitForIdle()
    }

    private fun ringNode(tag: String): SemanticsNode = compose.onNodeWithTag(activityBadgeRingTag(tag), useUnmergedTree = true).fetchSemanticsNode()

    private fun lit(tag: String): Boolean? = ringNode(tag).config.getOrNull(ActivityBadgeRingLit)

    private fun plate(tag: String): Boolean? = ringNode(tag).config.getOrNull(ActivityBadgeRingPlate)

    private fun settle() {
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    @Test
    fun `a selected badge wears the same full-colour ring whether it is earned, locked or still loading, drawn after its art and in none of its layers`() {
        val source = StagedBadges(writePng(temp.root, "badge.png", 120, 120, 120))
        mount { Tiles(source, selected = true) }
        listOf("earned", "locked", "loading").forEach { tag ->
            assertEquals("the ring is lit on the $tag badge", true, lit(tag))
            assertEquals("lit, so not the plate", false, plate(tag))
            assertEquals("in the palette's ring colour, whatever the art", ringColor.toArgb(), ringNode(tag).config.getOrNull(ActivityBadgeRingColor))
            val tile = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
            val children = tile.children.mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }
            assertEquals("the art first, the ring after it (above it): $children", listOf(activityBadgeArtTag(tag), activityBadgeRingTag(tag)), children)
            val art = compose.onNodeWithTag(activityBadgeArtTag(tag), useUnmergedTree = true).fetchSemanticsNode()
            val ring = ringNode(tag)
            assertFalse("the ring is not inside the art's layer", generateSequence(ring.parent) { it.parent }.any { it.id == art.id })
            assertFalse("and the art is not inside the ring's", generateSequence(art.parent) { it.parent }.any { it.id == ring.id })
            assertEquals("the ring covers the tile it rings", art.boundsInRoot, ring.boundsInRoot)
            assertEquals("a selected badge says so to a screen reader", true, tile.config.getOrNull(SemanticsProperties.Selected))
        }
        // The art is asked for off the main thread, so give it a moment to have asked.
        compose.waitUntil(5_000L) { source.asked.containsAll(listOf("/Badge/00001.png", "/Badge/00002_lock.png")) }
        assertTrue("the earned and the locked badge asked for their own art", source.asked.containsAll(listOf("/Badge/00001.png", "/Badge/00002_lock.png")))
    }

    @Test
    fun `an unselected badge has no ring, and a tap on a tile still activates it`() {
        mount { Tiles(StagedBadges(null), selected = false) }
        listOf("earned", "locked", "loading").forEach { tag ->
            assertEquals(false, lit(tag))
            assertEquals(false, plate(tag))
        }
        compose.onNodeWithTag("locked", useUnmergedTree = true).performClick()
        settle()
        assertEquals(listOf("locked"), taps.toList())
    }

    @Test
    fun `the pad's ring is the badge's ring, a selected badge elsewhere keeps only the plate, and a touch brings the ring back to the selected badge`() {
        val authority = DefaultFocusAuthority()
        val pad = ActivityHubPad(authority, QuietPadHost())
        val keys = mapOf<String, FocusKey?>("earned" to FocusKey("test/earned"), "locked" to FocusKey("test/locked"), "loading" to FocusKey("test/loading"))
        pad.setRing(ActivityPadRing(header = listOf(keys.values.filterNotNull())))
        mount {
            CompositionLocalProvider(LocalActivityHubPad provides pad) {
                Tiles(StagedBadges(null), selected = false, padKeys = keys, selectedTag = "earned")
            }
        }
        // No pad press yet: the ring is dark, and the selected badge wears it.
        assertEquals(true, lit("earned"))
        assertEquals(false, lit("locked"))

        pad.dispatch(ControllerAction.NAV_RIGHT) // lights the ring on the first key
        settle()
        assertEquals("the pad's ring is on the first badge, which is the selected one", true, lit("earned"))
        pad.dispatch(ControllerAction.NAV_RIGHT)
        settle()
        assertEquals("the ring moved with the pad onto the locked badge", true, lit("locked"))
        assertEquals("and left the selected badge", false, lit("earned"))
        assertEquals("which keeps the quiet plate, not a second ring", true, plate("earned"))
        assertEquals(false, plate("locked"))

        pad.onTouch()
        settle()
        assertEquals("a touch darkens the pad's ring and the ring rests on the selected badge", true, lit("earned"))
        assertEquals(false, lit("locked"))
        assertEquals(false, plate("earned"))
    }

}

/**
 * The badge ring as pixels, the way this repo rasterises (the view hierarchy drawn into a software bitmap: `captureToImage` waits on a redraw Robolectric's paused looper never delivers):
 * with the animators off the ring is its settled self from the first frame, and its bright stroke stands in the palette's ring colour right outside a selected badge's art, where an
 * unselected badge's neighbour shows only the ground. Pixel values are the JVM's; the pixels on a handheld are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActivityBadgeRingPixelTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val temp = TemporaryFolder()

    private val density369 = 369f / 160f
    private val ground = Color(0xFF10141C)
    private var ringColor: Color = Color.Unspecified

    @After
    fun resetAnimators() = setAnimatorScale(1f)

    private fun setAnimatorScale(scale: Float) {
        ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, scale)
    }

    private fun frame(): android.graphics.Bitmap {
        val decor = compose.activity.window.decorView
        val full = android.graphics.Bitmap.createBitmap(decor.width, decor.height, android.graphics.Bitmap.Config.ARGB_8888)
        decor.draw(android.graphics.Canvas(full))
        return full
    }

    @Test
    fun `with the animators off the ring is real pixels in the ring colour right outside a selected locked badge, and the ground beside an unselected one`() {
        setAnimatorScale(0f)
        val source = StagedBadges(writePng(temp.root, "grey.png", 128, 128, 128))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density369, 1f)) {
                MimirTheme {
                    ringColor = LocalMimirPalette.current.tileFocusRing
                    Box(Modifier.fillMaxSize().background(ground)) {
                        Row(Modifier.padding(24.dp)) {
                            ActivityBadgeTile(path = "/Badge/00002_lock.png", size = 56.dp, selected = true, source = source, label = "on", contentDescription = "on", tag = "on", onActivate = {})
                            Spacer(Modifier.width(48.dp))
                            ActivityBadgeTile(path = "/Badge/00003_lock.png", size = 56.dp, selected = false, source = source, label = "off", contentDescription = "off", tag = "off", onActivate = {})
                        }
                    }
                }
            }
        }
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(1_000L)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()

        val full = frame()
        fun pixelOutsideLeftEdge(tag: String): Color {
            val bounds = compose.onNodeWithTag(activityBadgeRingTag(tag), useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
            // The ring's bright stroke is centred 2 dp outside the badge's edge; take it at mid height, where the edge is straight.
            val x = (bounds.left - 2f * density369).toInt()
            val y = (bounds.top + bounds.height / 2f).toInt()
            return Color(full.getPixel(x, y))
        }
        val on = pixelOutsideLeftEdge("on")
        val off = pixelOutsideLeftEdge("off")
        println("ACH-RING pixel on=$on off=$off ring=$ringColor ground=$ground")
        assertTrue("the ring is the ring colour beside the selected badge (blended a little with the ground): $on vs $ringColor", abs(on.red - ringColor.red) < 0.2f && abs(on.green - ringColor.green) < 0.2f && abs(on.blue - ringColor.blue) < 0.2f)
        assertTrue("and it is not the ground: $on", abs(on.red - ground.red) > 0.15f || abs(on.green - ground.green) > 0.15f || abs(on.blue - ground.blue) > 0.15f)
        assertTrue("nothing but the ground beside the unselected badge: $off", abs(off.red - ground.red) < 0.02f && abs(off.green - ground.green) < 0.02f && abs(off.blue - ground.blue) < 0.02f)
    }
}

/**
 * The Bottom's Activity page on the REAL hub, at the Bottom's 537.7 x 468.3 dp: the card's strip leaves the ring its room and its tapped badge wears the ring; the cards snap whole in
 * both directions; the "more below" cue is there while a card is below; and the position reads as a place in a list.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActivityHubVerticalPagingRenderTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val temp = TemporaryFolder()

    private val density369 = 369f / 160f
    private val hostWidthDp = 1240f / density369
    private val hostHeightDp = 1080f / density369
    private val now = 1_700_000_000_000L
    private val day = 24L * 60L * 60L * 1000L

    @After
    fun resetAnimators() = setAnimatorScale(1f)

    private fun setAnimatorScale(scale: Float) {
        ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, scale)
    }

    private fun game(n: Int) = CompanionActivityAchievementSnapshot(
        gameId = "g$n",
        account = "me",
        unlocked = n,
        total = 40,
        gameTitle = "Game $n",
        checkedAtEpochMillis = 1L,
        rowsKnown = false,
        raGameId = 100 + n,
        consoleName = "PlayStation 2",
        lastAwardedAtMillis = now - n * day,
    )

    private fun progress(raGameId: Int) = RetroAchievementsProgress(
        gameId = raGameId,
        title = "Game ${raGameId - 100}",
        achievements = (1..6).map { id ->
            RetroAchievementsAchievement(
                id = id,
                title = "Badge $id",
                description = "Do thing $id",
                points = 5,
                unlocked = id <= 3,
                unlockedHardcore = false,
                badgeName = "%05d".format(id),
                earnedAt = if (id <= 3) "2023-05-0$id 10:00:00" else null,
            )
        },
    )

    private fun mount(games: List<CompanionActivityAchievementSnapshot>, badges: ActivityBadgeSource = StagedBadges(null), withDetail: Boolean = false, font: Float = 1.0f) {
        val details = MutableStateFlow<Map<Int, ActivityCardDetail>>(
            if (withDetail) games.mapNotNull { it.raGameId }.associateWith { ActivityCardDetail.Loaded(progress(it), 0L) } else emptyMap(),
        )
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density369, font)) {
                MimirTheme {
                    Box(Modifier.requiredSize(hostWidthDp.dp, hostHeightDp.dp)) {
                        CompanionActivityAchievementHub(
                            snapshots = games,
                            unknownState = MimirAchievementCardState.Unknown(MimirAchievementCopy.NoAnswerYet),
                            navigationEnabled = true,
                            onBack = {},
                            badgeSource = badges,
                            details = details,
                            requestDetail = {},
                            filterHolder = ActivityAchievementFilterHolder(),
                            nowMillis = { now },
                        )
                    }
                }
            }
        }
        advance(1_000L)
    }

    private fun advance(ms: Long = 300L) {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(ms)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    private fun exists(tag: String): Boolean = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun rect(tag: String): Rect = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot

    private fun position(): String? =
        compose.onAllNodesWithTag(AchievementHubRunesTag, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
            ?.config?.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()

    /** A quick flick of the list, as a thumb does it: the fling and the snap decide where it rests. */
    private fun swipe(up: Boolean) {
        compose.onNodeWithTag(AchievementHubListTag, useUnmergedTree = true).performTouchInput {
            if (up) {
                swipeUp(startY = height * 0.8f, endY = height * 0.2f, durationMillis = 120L)
            } else {
                swipeDown(startY = height * 0.2f, endY = height * 0.8f, durationMillis = 120L)
            }
        }
        // A fling has a long tail (an exponential decay): give the paused clock up to ten seconds, half a second at a time, to bring the list to rest.
        var waited = 0L
        do {
            advance(500L)
            waited += 500L
        } while (waited < 10_000L && !restsOnACard())
    }

    /** A deliberate drag of 70% of a card that stops before the finger lifts (no fling): up is the next card, down the one before. */
    private fun drag(up: Boolean) {
        compose.onNodeWithTag(AchievementHubListTag, useUnmergedTree = true).performTouchInput {
            val dy = height * 0.7f * (if (up) -1f else 1f)
            down(Offset(width * 0.5f, height * 0.5f))
            moveBy(Offset(0f, dy / 2f))
            moveBy(Offset(0f, dy / 2f))
            advanceEventTime(300L)
            up()
        }
        advance(2_000L)
    }

    /** The cards whose area is on the screen (the lazy list composes its neighbours too; theirs lies outside the list's own rectangle). */
    private fun cardsInView(): List<Rect> {
        val list = rect(AchievementHubListTag)
        return compose.onAllNodesWithTag(AchievementHubCardTag, useUnmergedTree = true).fetchSemanticsNodes()
            // A card the lazy list scrolled out is kept for reuse, unplaced, with the bounds of its last placement: it is not on the screen, whatever those bounds say.
            .filter { it.layoutInfo.isPlaced }
            .map { it.boundsInRoot }
            .filter { it.width > 0f && it.height > 0f && it.bottom > list.top + 1f && it.top < list.bottom - 1f }
    }

    /** One card in view and its top at the list's top: the list has come to rest on a card. */
    private fun restsOnACard(): Boolean {
        val list = rect(AchievementHubListTag)
        val cards = cardsInView()
        return cards.size == 1 && abs(cards.single().top - list.top) <= 1.5f
    }

    /** Exactly one card is on the screen, it is the list's size, and it stands at the list's top: whole and aligned. [number] null leaves which card it is to the fling. */
    private fun assertOnCard(number: Int?, total: Int, what: String) {
        val list = rect(AchievementHubListTag)
        val cards = cardsInView()
        println("ACH-SNAP $what list=${list.top},${list.bottom} cards=${cards.map { it.top to it.bottom }} position=${position()}")
        assertEquals("$what: one card in view, not two (the other cut off): $cards", 1, cards.size)
        assertEquals("$what: its top is the list's top", list.top, cards.single().top, 1.5f)
        assertEquals("$what: and it is the list's whole height", list.height, cards.single().height, 1.5f)
        if (number != null) {
            assertEquals("$what: $number of $total", "Game $number of $total", position())
        } else {
            assertTrue("$what: the position reads as a place in the list: ${position()}", position()?.matches(Regex("Game [1-9][0-9]* of $total")) == true)
        }
    }

    @Test
    fun `dragging a card up and a card down settles on the next and the previous card, whole and aligned the same way in both directions`() {
        mount((1..4).map(::game))
        assertOnCard(1, 4, "at rest")
        drag(up = true)
        assertOnCard(2, 4, "up once")
        drag(up = true)
        assertOnCard(3, 4, "up twice")
        drag(up = false)
        assertOnCard(2, 4, "back down once")
        drag(up = false)
        assertOnCard(1, 4, "back to the first")
    }

    @Test
    fun `flicking the list up and down, however far the fling carries it, always comes to rest on one whole, aligned card`() {
        mount((1..6).map(::game))
        swipe(up = true)
        assertOnCard(null, 6, "flick up")
        swipe(up = true)
        assertOnCard(null, 6, "flick up again")
        swipe(up = false)
        assertOnCard(null, 6, "flick down")
        swipe(up = false)
        assertOnCard(null, 6, "flick down again")
    }

    @Test
    fun `the settle effect brings a list that stopped between cards onto the nearest card, past halfway forward and short of it back`() {
        lateinit var state: androidx.compose.foundation.lazy.LazyListState
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MimirTheme {
                Box(Modifier.requiredSize(300.dp, 200.dp)) {
                    state = rememberLazyListState()
                    ActivityCardSettleEffect(listState = state, enabled = true)
                    // A free list (no snap of its own): where the finger leaves it is where it stops, so only the effect can bring it onto a card.
                    LazyColumn(state = state, modifier = Modifier.fillMaxSize().testTag("free-pager")) {
                        items(4) { n -> Box(Modifier.fillParentMaxSize().testTag("free-card-$n")) }
                    }
                }
            }
        }
        advance(500L)
        fun drag(dyPx: Float) {
            compose.onNodeWithTag("free-pager", useUnmergedTree = true).performTouchInput {
                down(center)
                moveBy(Offset(0f, dyPx))
                advanceEventTime(400L)
                up()
            }
            advance(2_000L)
        }
        fun assertSettledOn(index: Int, what: String) {
            assertEquals("$what: the first card in view is $index", index, state.firstVisibleItemIndex)
            assertEquals("$what: and it is whole at the top", 0, state.firstVisibleItemScrollOffset)
        }
        val heightPx = rect("free-pager").height
        drag(-heightPx * 0.7f) // a finger up by 70% of a card: forward, past halfway
        assertSettledOn(1, "up past halfway")
        drag(heightPx * 0.3f) // a finger down by 30%: back, short of halfway ... it was already settled on 1, so this leaves card 0's bottom 30% showing: past halfway of card 0 is card 1
        assertSettledOn(1, "down 30% (card 0 shows 30%, nearer card 1)")
        drag(heightPx * 0.6f) // a finger down by 60%: card 0 shows 60%, nearer card 0
        assertSettledOn(0, "down 60% (nearer card 0)")
    }

    @Test
    fun `the more-below cue is on every card but the last, and a single game has none`() {
        mount((1..3).map(::game))
        assertTrue("the cue is on the first card", exists(AchievementMoreBelowCueTag))
        drag(up = true)
        assertTrue("and the second", exists(AchievementMoreBelowCueTag))
        drag(up = true)
        assertEquals("Game 3 of 3", position())
        assertFalse("the last card has none", exists(AchievementMoreBelowCueTag))
        drag(up = false)
        assertTrue("it comes back when there is a card below again", exists(AchievementMoreBelowCueTag))
    }

    @Test
    fun `the cue takes no touch and says which way to swipe, and a single game has no cue and no position`() {
        mount(listOf(game(1)))
        assertFalse(exists(AchievementMoreBelowCueTag))
        assertNull("one game has no position", position())
    }

    @Test
    fun `under reduced motion the cue is a still chevron, shown all the same`() {
        setAnimatorScale(0f)
        mount((1..3).map(::game))
        advance(12_000L)
        assertTrue("the chevron stays (it does not play, so it never moves, but it is not hidden)", exists(AchievementMoreBelowCueTag))
        val cue = rect(AchievementMoreBelowCueTag)
        val list = rect(AchievementHubListTag)
        assertTrue("it sits at the card's foot, inside the list: $cue in $list", cue.left >= list.left && cue.right <= list.right && cue.bottom <= list.bottom + 0.5f)
        advance(12_000L)
        assertEquals("and does not drift", cue, rect(AchievementMoreBelowCueTag))
    }

    @Test
    fun `the card's strip leaves the ring room each side, and a tapped badge wears the ring above its art`() {
        val badges = StagedBadges(writePng(temp.root, "badge.png", 120, 120, 120))
        mount(listOf(game(1), game(2)), badges = badges, withDetail = true)
        val order = activityBadgeOrder(progress(101).achievements)
        val first = achievementPanelBadgeTag(order.first().id)
        compose.waitUntil(5_000L) { exists(first) }
        advance(300L)
        val strip = rect(AchievementPanelBadgeStripTag)
        val tile = rect(first)
        val room = ActivityBadgeRingRoom.value * density369
        assertTrue("clear air to the left of the first badge: ${tile.left - strip.left} px of $room", tile.left - strip.left >= room - 1f)

        order.forEach { achievement ->
            val tag = achievementPanelBadgeTag(achievement.id)
            compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
            advance(300L)
            val ring = compose.onNodeWithTag(activityBadgeRingTag(tag), useUnmergedTree = true).fetchSemanticsNode()
            assertEquals("badge ${achievement.id} (${if (achievement.unlocked) "earned" else "locked"}) wears the ring once tapped", true, ring.config.getOrNull(ActivityBadgeRingLit))
            order.filter { it.id != achievement.id }.forEach { other ->
                val otherRing = compose.onAllNodesWithTag(activityBadgeRingTag(achievementPanelBadgeTag(other.id)), useUnmergedTree = true).fetchSemanticsNodes()
                otherRing.firstOrNull()?.let { assertEquals("only the tapped badge has it", false, it.config.getOrNull(ActivityBadgeRingLit)) }
            }
        }
    }

    @Test
    fun `the ring at the peak of its flare paints on air, never on the progress bar above the strip nor on the detail below it`() {
        val badges = StagedBadges(writePng(temp.root, "badge.png", 120, 120, 120))
        mount(listOf(game(1), game(2)), badges = badges, withDetail = true)
        val first = achievementPanelBadgeTag(activityBadgeOrder(progress(101).achievements).first().id)
        compose.waitUntil(5_000L) { exists(first) }
        advance(300L)
        // The widest stroke stands 10 dp out of the tile and is 7 dp wide (core-ui's drawMimirFocusTileRing); the arrival flare pushes every stroke MimirFocusFlareReach further.
        val peakDp = 10f + 3.5f + MimirFocusFlareReach.value
        assertTrue("the room the card keeps is the ring's whole peak reach: ${ActivityBadgePaintReach.value} dp for $peakDp dp", ActivityBadgePaintReach.value >= peakDp)
        val peak = peakDp * density369
        val ring = rect(activityBadgeRingTag(first))
        val bar = rect(AchievementPanelProgressTag)
        val detail = rect(AchievementPanelDetailTag)
        // The ring node is the tile's box: what the flare paints is that box grown by the peak reach on every side. It must not meet the bar's box nor the detail's.
        val painted = Rect(ring.left - peak, ring.top - peak, ring.right + peak, ring.bottom + peak)
        println("ACH-FLARE ring=$ring painted=$painted bar=$bar detail=$detail")
        assertTrue("the painted ring (top ${painted.top}) stays below the progress bar (bottom ${bar.bottom})", painted.top >= bar.bottom - 1.5f)
        assertTrue("and above the detail (top ${detail.top}) (painted bottom ${painted.bottom})", painted.bottom <= detail.top + 1.5f)
    }

    @Test
    fun `a finger that lands on a glide the settle has begun stops the list at once, and the list is left where the finger found it until it lifts`() {
        lateinit var state: androidx.compose.foundation.lazy.LazyListState
        var touching by mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MimirTheme {
                Box(Modifier.requiredSize(300.dp, 200.dp)) {
                    state = rememberLazyListState()
                    ActivityCardSettleEffect(listState = state, enabled = true, touching = { touching })
                    LazyColumn(state = state, modifier = Modifier.fillMaxSize().testTag("glide-pager")) {
                        items(4) { n -> Box(Modifier.fillParentMaxSize().testTag("glide-card-$n")) }
                    }
                }
            }
        }
        advance(500L)
        val heightPx = rect("glide-pager").height
        // A finger up by 60% of a card leaves card 1 nearer: the effect will glide the list forward onto it.
        compose.onNodeWithTag("glide-pager", useUnmergedTree = true).performTouchInput {
            down(center)
            moveBy(Offset(0f, -heightPx * 0.6f))
            advanceEventTime(400L)
            up()
        }
        // Frame by frame until the glide is under way (the quiet wait first, then the animation).
        var waited = 0L
        while (waited < 2_000L && !state.isScrollInProgress) {
            advance(16L)
            waited += 16L
        }
        assertTrue("the settle began a glide of its own", state.isScrollInProgress)
        advance(32L)
        assertTrue("and it is still moving", state.isScrollInProgress)
        // A finger lands on the moving list. It is a touch the list never sees as a drag (a child has it), so only the effect can stop the glide.
        touching = true
        advance(16L)
        assertFalse("the glide stops at once", state.isScrollInProgress)
        val stopped = state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset
        advance(1_500L)
        assertEquals("and the list stays exactly where the finger found it: no second glide fights the hand", stopped, state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset)
        assertFalse(state.isScrollInProgress)
        // The finger lifts: now the list settles onto a card start.
        touching = false
        advance(2_000L)
        assertEquals("it comes to rest at the top of a card", 0, state.firstVisibleItemScrollOffset)
        assertFalse(state.isScrollInProgress)
    }
}

/**
 * The console picker on the REAL hub at the Bottom's window and font scales 1.0 and 2.0: a glass picker over the blurred page that lists "All consoles" and every console by its full
 * name with its game count, scrolls, never clips, closes on a tap outside and picks by a tap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActivityConsolePickerRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val density369 = 369f / 160f
    private val hostWidthDp = 1240f / density369
    private val hostHeightDp = 1080f / density369
    private val now = 1_700_000_000_000L
    private val day = 24L * 60L * 60L * 1000L

    /** RA's own names for fourteen consoles, none of them a short code. */
    private val consoleNames = listOf(
        "Atari 2600", "Dreamcast", "Game Boy", "Game Boy Advance", "Game Boy Color", "GameCube", "Genesis/Mega Drive",
        "Nintendo 64", "Nintendo DS", "PlayStation", "PlayStation 2", "PlayStation Portable", "SNES/Super Famicom", "Saturn",
    )

    // Three PlayStation 2 games and one of each other console: 16 games.
    private val games = (consoleNames + listOf("PlayStation 2", "PlayStation 2")).mapIndexed { n, console ->
        CompanionActivityAchievementSnapshot(
            gameId = "g$n",
            account = "me",
            unlocked = 1,
            total = 10,
            gameTitle = "Game ${n + 1}",
            checkedAtEpochMillis = 1L,
            rowsKnown = false,
            raGameId = 100 + n,
            consoleName = console,
            lastAwardedAtMillis = now - n * day,
        )
    }

    private val holder = ActivityAchievementFilterHolder()

    private fun mount(font: Float) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density369, font)) {
                MimirTheme {
                    Box(Modifier.requiredSize(hostWidthDp.dp, hostHeightDp.dp)) {
                        CompanionActivityAchievementHub(
                            snapshots = games,
                            unknownState = MimirAchievementCardState.Unknown(MimirAchievementCopy.NoAnswerYet),
                            navigationEnabled = true,
                            onBack = {},
                            details = MutableStateFlow<Map<Int, ActivityCardDetail>>(emptyMap()),
                            requestDetail = {},
                            filterHolder = holder,
                            nowMillis = { now },
                        )
                    }
                }
            }
        }
        advance(1_000L)
    }

    private fun advance(ms: Long = 300L) {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(ms)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    private fun exists(tag: String): Boolean = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun rect(tag: String): Rect = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot

    private fun desc(tag: String): String? =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
            ?.config?.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()

    private fun texts(): List<String> {
        val out = ArrayList<String>()
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
            node.children.forEach(::walk)
        }
        compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach(::walk)
        return out
    }

    private fun open() {
        compose.onNodeWithTag(AchievementFilterConsoleTag, useUnmergedTree = true).performClick()
        advance(1_000L) // the page softens, then the panel arrives: its rows measure at their settled size
    }

    private fun scrollRowsTo(index: Int) {
        compose.onNodeWithTag(AchievementFilterConsoleRowsTag, useUnmergedTree = true).performScrollToIndex(index)
        advance(300L)
    }

    private fun checkPicker(font: Float) {
        mount(font)
        assertFalse(exists(AchievementFilterConsoleListTag))
        open()
        assertTrue("the picker is open", exists(AchievementFilterConsoleListTag))
        assertTrue("the page behind it is dimmed by the glass scrim", exists(AchievementFilterConsoleScrimTag))
        assertEquals("the eyebrow names it (the text intent speaks the authored string, so the words are the node's description)", ActivityConsolePickerEyebrow, desc(AchievementFilterConsoleEyebrowTag))
        assertEquals("All consoles, selected", desc(achievementFilterConsoleItemTag(null)))

        val host = Rect(0f, 0f, 1240f, 1080f)
        val panel = rect(AchievementFilterConsoleListTag)
        println("CONSOLE-PICKER font=$font panel=${panel.left / density369},${panel.top / density369} ${panel.width / density369}x${panel.height / density369} dp")
        assertTrue("the panel is inside the window: $panel", panel.left >= host.left - 0.5f && panel.top >= host.top - 0.5f && panel.right <= host.right + 0.5f && panel.bottom <= host.bottom + 0.5f)
        assertTrue("and leaves a margin top and bottom: $panel", panel.top > 0f && panel.bottom < host.bottom)

        // The list scrolls: fourteen consoles do not fit, so there is something to scroll to, and every row is reachable and whole inside the panel.
        val rows = compose.onNodeWithTag(AchievementFilterConsoleRowsTag, useUnmergedTree = true).fetchSemanticsNode()
        assertTrue("there is more list than the panel shows", rows.config[SemanticsProperties.VerticalScrollAxisRange].maxValue() > 0f)
        val index = ActivityAchievementIndex(games)
        assertEquals(consoleNames.toSortedSet(String.CASE_INSENSITIVE_ORDER).toList(), index.consoles.map { it.name })
        index.consoles.forEachIndexed { at, entry ->
            scrollRowsTo(at + 1)
            val tag = achievementFilterConsoleItemTag(entry.name)
            assertTrue("${entry.name} is reachable by scrolling", exists(tag))
            val row = rect(tag)
            assertTrue("${entry.name} is at least 48 dp tall: ${row.height / density369}", row.height / density369 >= 47.9f)
            // The row the pad or a finger scrolled to is whole inside the panel (the lazy list brings it in; one at an edge may be partly out, so check the one just scrolled to).
            assertTrue("${entry.name} lies within the panel's height: $row in $panel", row.top >= panel.top - 0.5f && row.bottom <= panel.bottom + 0.5f)
            // The row says the lattice's name for the console (RetroAchievements' "Genesis/Mega Drive" is "Genesis / Mega Drive" there), whole, with the count.
            val shownAs = index.consoleLooks.getValue(entry.name).display
            assertEquals("the lattice's full name and the count: ${entry.label}", "$shownAs, ${entry.count} ${if (entry.count == 1) "game" else "games"}", desc(tag))
        }
        assertTrue("PlayStation 2 is listed by its full name with its three games: ${texts()}", "PlayStation 2 (3)" in texts() || index.consoles.any { it.label == "PlayStation 2 (3)" })
        assertTrue("no short code is anywhere in the list", index.consoles.none { it.name.length <= 3 })
    }

    @Test
    fun `the picker is a scrolling glass panel inside the window listing every console by its full name with its count, at font 1_0`() {
        checkPicker(1.0f)
    }

    @Test
    fun `the picker never clips at font 2_0 either, and every console is still reachable`() {
        checkPicker(2.0f)
    }

    @Test
    fun `a tap outside the panel closes it, a tap on the panel does not, and a pick filters by the console and closes it`() {
        mount(1.0f)
        open()
        // Inside the panel, on the eyebrow: absorbed.
        val panel = rect(AchievementFilterConsoleListTag)
        compose.onNodeWithTag(AchievementFilterConsoleEyebrowTag, useUnmergedTree = true).performClick()
        advance(300L)
        assertTrue("a tap on the panel's own glass does not close it", exists(AchievementFilterConsoleListTag))
        // Outside it, in the corner of the scrim: closes.
        compose.onNodeWithTag(AchievementFilterConsoleScrimTag, useUnmergedTree = true).performTouchInput { click(Offset(6f, 6f)) }
        advance(300L)
        assertFalse("a tap outside closes the picker", exists(AchievementFilterConsoleListTag))
        assertFalse(exists(AchievementFilterConsoleScrimTag))
        assertNull("and picked nothing", holder.state.value.console)
        assertTrue("the panel was inside the window", panel.top >= 0f)

        open()
        scrollRowsTo(consoleNames.toSortedSet(String.CASE_INSENSITIVE_ORDER).toList().indexOf("PlayStation 2") + 1)
        compose.onNodeWithTag(achievementFilterConsoleItemTag("PlayStation 2"), useUnmergedTree = true).performClick()
        advance(300L)
        assertFalse("a pick closes the picker", exists(AchievementFilterConsoleListTag))
        assertEquals("PlayStation 2", holder.state.value.console)
        assertEquals("the chip says the full name", "Console, PlayStation 2", desc(AchievementFilterConsoleTag))
        assertEquals("3 of 16 games", compose.onAllNodesWithTag(AchievementFilterCountTag, useUnmergedTree = true).fetchSemanticsNodes().first().config.getOrNull(SemanticsProperties.Text)?.first()?.text)
    }
}

/**
 * The "more below" cue's idle clock, on the real composable with the page's two inputs driven by hand: it replays only after [ACTIVITY_CUE_IDLE_REPLAY_MS] of real quiet. Input
 * of any kind (a count the page bumps, a finger down or a scroll under way) starts the wait over, and the cue never plays while the page is busy or its pane is parked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActivityMoreBelowCueTimerTest {

    @get:Rule
    val compose = createComposeRule()

    private var activity by mutableIntStateOf(0)
    private var busy by mutableStateOf(false)
    private val paneLive = mutableStateOf(true)
    private var plays = 0

    /** One play: two beats of a beat down and two beats back. */
    private val playMs = MimirMotion.OverlayMs * 6L

    private fun advance(ms: Long) {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(ms)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    private fun mount() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalMimirMotionLive provides paneLive) {
                MimirTheme {
                    ActivityMoreBelowCue(
                        visible = true,
                        page = 0,
                        reducedMotion = false,
                        activity = { activity },
                        busy = { busy },
                        onPlay = { plays += 1 },
                    )
                }
            }
        }
        advance(16L)
    }

    /** Lets the open play happen and finish, so each test starts from a page that has said its piece once. */
    private fun mountAndLetItOpen() {
        mount()
        assertEquals("no play before the page has been up for a moment", 0, plays)
        advance(ACTIVITY_CUE_OPEN_DELAY_MS + 50L)
        assertEquals("the cue plays once as the page opens", 1, plays)
        advance(playMs + 100L)
        assertEquals(1, plays)
    }

    @Test
    fun `input every three seconds for thirty seconds never lets the cue replay, and eight quiet seconds after the last does`() {
        mountAndLetItOpen()
        repeat(10) { n ->
            activity += 1
            advance(3_000L)
            assertEquals("a touch or a pad press at ${(n + 1) * 3} s has restarted the wait: no replay", 1, plays)
        }
        // The last input was three quiet seconds ago.
        advance(ACTIVITY_CUE_IDLE_REPLAY_MS - 3_000L - 100L)
        assertEquals("a hair short of eight quiet seconds: still not", 1, plays)
        advance(200L)
        assertEquals("eight quiet seconds: it plays again", 2, plays)
    }

    @Test
    fun `while a finger is down or the list is scrolling the cue waits however long that is, and counts its eight seconds from the end`() {
        mountAndLetItOpen()
        busy = true
        advance(40_000L)
        assertEquals("a long hold is not idleness: no replay", 1, plays)
        busy = false
        advance(ACTIVITY_CUE_IDLE_REPLAY_MS - 100L)
        assertEquals("the wait starts when the page goes quiet, not before", 1, plays)
        advance(200L)
        assertEquals(2, plays)
    }

    @Test
    fun `input that arrives while the cue is playing cuts the play short and starts the wait over`() {
        mountAndLetItOpen()
        advance(ACTIVITY_CUE_IDLE_REPLAY_MS + 50L)
        assertEquals("the replay began", 2, plays)
        advance(200L) // part way through the play
        activity += 1
        advance(ACTIVITY_CUE_IDLE_REPLAY_MS - 100L)
        assertEquals("the play was cut by the input, and the next one waits the full eight seconds", 2, plays)
        advance(200L)
        assertEquals(3, plays)
    }

    @Test
    fun `an input during the wait before the open play pushes the open play back`() {
        mount()
        advance(ACTIVITY_CUE_OPEN_DELAY_MS - 50L)
        activity += 1
        advance(100L)
        assertEquals("the open play was waiting and the input restarted it", 0, plays)
        advance(ACTIVITY_CUE_OPEN_DELAY_MS)
        assertEquals(1, plays)
    }

    @Test
    fun `a parked pane does not play, and a pane that comes back starts its wait from nothing`() {
        mountAndLetItOpen()
        paneLive.value = false
        advance(30_000L)
        assertEquals("parked: nothing plays", 1, plays)
        paneLive.value = true
        advance(ACTIVITY_CUE_IDLE_REPLAY_MS - 100L)
        assertEquals("the wait counts from the pane coming back", 1, plays)
        advance(200L)
        assertEquals(2, plays)
    }
}
