package com.gruning.mimir.rewrite.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.ui.MimirArtInkBounds
import com.gruning.mimir.rewrite.core.ui.MimirArtInkHoldCapMs
import com.gruning.mimir.rewrite.core.ui.MimirArtInkMeasurement
import com.gruning.mimir.rewrite.core.ui.MimirArtInkStore
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicInteger

/**
 * Preview-art seat stability, in Compose with a held clock and the real [GamePreviewPanel].
 *
 * The panel solves the seat its art is painted in from where the art's ink is inside its file, which only a decode that has scanned
 * the file can say, and which the store has not said for an art it has not measured yet. The art used to be painted at the
 * platform's seat on the frame it was reached and again at its own a decode later. What has to hold now is that the art is
 * never SHOWN at a seat it does not keep: here an art whose measurement lands late (the test decides when), and the one rule
 * stated as a property over every frame, which checks the observed resize defect.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GamelistPreviewArtHoldTest {

    @get:Rule
    val compose = createComposeRule()

    private val art = "file:///cover-a.png"
    private val media = mutableStateOf(mediaFor(art))

    /** A 3D box render: the ink is a good deal wider than the platform's cover seat, so the seat it is solved into changes shape. */
    private val ink = MimirArtInkBounds(left = 0.05f, top = 0.2f, right = 0.95f, bottom = 0.8f, aspect = 1.3f)

    private fun mediaFor(uri: String) = LibraryListPreviewMedia(
        artUri = uri,
        aspectRatio = 0.72f,
        source = LibraryListPreviewMediaSource.COVER,
    )

    @Before
    fun fresh() = MimirArtInkStore.clearForTest()

    @After
    fun restore() = MimirArtInkStore.clearForTest()

    private fun host() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MimirTheme {
                Box(Modifier.size(260.dp, 480.dp)) {
                    GamePreviewPanel(
                        title = "Game",
                        supportLabel = null,
                        previewMedia = media.value,
                        mediaAction = { Box(Modifier.size(80.dp, 28.dp).testTag(ChipTag)) },
                        modifier = Modifier.width(170.dp).height(420.dp),
                    )
                }
            }
        }
    }

    private fun shown(): Boolean =
        compose.onNodeWithTag(GamelistPreviewPanelTestTag).fetchSemanticsNode().config[GamelistPreviewShownSemantics]

    private fun artSize(): IntSize = compose.onNodeWithTag(GamelistPreviewArtTestTag).fetchSemanticsNode().size

    private fun chipTop(): Float = compose.onNodeWithTag(ChipTag).fetchSemanticsNode().boundsInRoot.top

    private class Frame(val shown: Boolean, val art: IntSize, val chipTop: Float)

    /** One frame, as the player would see it: what is shown, and where the art and the chip under it are. */
    private fun frame(): Frame {
        compose.mainClock.advanceTimeByFrame()
        return Frame(shown(), artSize(), chipTop())
    }

    private fun land(outcome: MimirArtInkMeasurement, gate: CompletableDeferred<MimirArtInkMeasurement>) {
        gate.complete(outcome)
        Snapshot.sendApplyNotifications()
    }

    // ----------------------------------------------------------------------------------------- an art that decodes late

    @Test
    fun `an art whose ink lands late is never shown at a seat it does not keep`() {
        val gate = CompletableDeferred<MimirArtInkMeasurement>()
        MimirArtInkStore.setMeasurementForTest(Dispatchers.Unconfined) { _, _ -> gate.await() }
        host()

        val frames = mutableListOf<Frame>()
        repeat(8) { frames += frame() }
        val pending = frames.last()
        assertFalse("the ink has not landed, so the art is held out of sight", pending.shown)

        land(MimirArtInkMeasurement.Measured(ink), gate)
        repeat(8) { frames += frame() }
        val settled = frames.last()
        assertTrue("the ink has landed, so the panel shows", settled.shown)
        assertNotEquals(
            "the precondition that makes this a test: the seat the art is held at is NOT the seat it keeps, so showing it early would have resized it",
            pending.art,
            settled.art,
        )
        assertNotEquals("and the chip under it moves with its height", pending.chipTop, settled.chipTop, 0.5f)

        frames.filter { it.shown }.forEach {
            assertEquals("a frame that shows the art shows it at the seat it keeps", settled.art, it.art)
            assertEquals("and the chip where it stays", settled.chipTop, it.chipTop, 0.5f)
        }
        assertTrue("and it was held for the frames before", frames.count { !it.shown } >= 8)
    }

    @Test
    fun `an art the store already knows is shown from the first frame, at the seat it keeps`() {
        val context = RuntimeEnvironment.getApplication()
        MimirArtInkStore.setMeasurementForTest(Dispatchers.Unconfined) { _, _ -> MimirArtInkMeasurement.Measured(ink) }
        runBlocking { MimirArtInkStore.measure(context, art) }
        host()

        val first = frame()
        val later = (1..6).map { frame() }

        assertTrue("a known art is not held", first.shown)
        later.forEach {
            assertTrue(it.shown)
            assertEquals(first.art, it.art)
            assertEquals(first.chipTop, it.chipTop, 0.5f)
        }
    }

    @Test
    fun `a flat art, which has no ink to hug, is shown at the platform's seat once the store has said so`() {
        val gate = CompletableDeferred<MimirArtInkMeasurement>()
        MimirArtInkStore.setMeasurementForTest(Dispatchers.Unconfined) { _, _ -> gate.await() }
        host()
        val held = (1..6).map { frame() }.last()

        land(MimirArtInkMeasurement.NoInkToHug, gate)
        val shownAt = (1..6).map { frame() }

        assertFalse(held.shown)
        shownAt.forEach {
            assertTrue(it.shown)
            assertEquals("a flat art keeps the seat it was held at: nothing to move", held.art, it.art)
        }
    }

    @Test
    fun `an art that cannot be measured is shown at the platform's seat rather than held for ever`() {
        val gate = CompletableDeferred<MimirArtInkMeasurement>()
        MimirArtInkStore.setMeasurementForTest(Dispatchers.Unconfined) { _, _ -> gate.await() }
        host()
        val held = (1..6).map { frame() }.last()

        land(MimirArtInkMeasurement.Unmeasurable, gate)
        val after = frame()
        val later = frame()

        assertTrue("the failure is a verdict for this session, so the hold ends", after.shown && later.shown)
        assertEquals(held.art, later.art)
        assertTrue(MimirArtInkStore.hasVerdict(art))
    }

    @Test
    fun `a measurement that never comes is waited for only so long`() {
        MimirArtInkStore.setMeasurementForTest(Dispatchers.Unconfined) { _, _ -> CompletableDeferred<MimirArtInkMeasurement>().await() }
        host()

        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(MimirArtInkHoldCapMs.toLong() - 100L)
        assertFalse("still held inside the cap", shown())

        compose.mainClock.advanceTimeBy(200L)
        assertTrue("let go at the cap: an art is never the thing that is missing from the screen", shown())
    }

    @Test
    fun `an art with nothing to measure, the book or the quiet surface, is never held`() {
        media.value = LibraryListPreviewMedia(
            artUri = null,
            aspectRatio = 0.72f,
            source = LibraryListPreviewMediaSource.FALLBACK,
        )
        MimirArtInkStore.setMeasurementForTest(Dispatchers.Unconfined) { _, _ -> CompletableDeferred<MimirArtInkMeasurement>().await() }
        host()

        compose.mainClock.advanceTimeByFrame()

        assertTrue(shown())
    }

    // ----------------------------------------------------------------------------------------------------- the look-ahead

    @Test
    fun `the store measures what it is asked about once, nearest first, and a second ask measures nothing`() {
        val context = RuntimeEnvironment.getApplication()
        val asked = mutableListOf<String>()
        MimirArtInkStore.setMeasurementForTest(Dispatchers.Unconfined) { _, key ->
            asked += key
            MimirArtInkMeasurement.Measured(ink)
        }

        runBlocking { MimirArtInkStore.prefetch(context, listOf("file:///1.png", "file:///2.png", null, "", "file:///3.png")) }

        assertEquals(listOf("file:///1.png", "file:///2.png", "file:///3.png"), asked)
        assertTrue(listOf("file:///1.png", "file:///2.png", "file:///3.png").all(MimirArtInkStore::hasVerdict))
        assertFalse("an art nobody asked about has no verdict", MimirArtInkStore.hasVerdict("file:///4.png"))

        runBlocking { MimirArtInkStore.prefetch(context, listOf("file:///1.png", "file:///2.png", "file:///3.png")) }
        assertEquals("nothing is measured twice", 3, asked.size)
    }

    @Test
    fun `a second asker for an art being measured waits for it, and measures it itself if the first is dropped`() {
        val context = RuntimeEnvironment.getApplication()
        val calls = AtomicInteger(0)
        val release = CompletableDeferred<Unit>()
        MimirArtInkStore.setMeasurementForTest(Dispatchers.Unconfined) { _, _ ->
            calls.incrementAndGet()
            release.await()
            MimirArtInkMeasurement.Measured(ink)
        }

        runBlocking {
            val first = launch(Dispatchers.Unconfined) { MimirArtInkStore.measure(context, art) }
            val second = launch(Dispatchers.Unconfined) { MimirArtInkStore.measure(context, art) }
            assertEquals("the second found the first measuring and is waiting, not decoding", 1, calls.get())
            assertFalse(MimirArtInkStore.hasVerdict(art))

            // The player moved on: the look-ahead that was measuring it is dropped. The panel that asked second must not be
            // left waiting for a verdict nobody is producing.
            first.cancel()
            first.join()
            assertEquals("so the second measures it itself", 2, calls.get())

            release.complete(Unit)
            second.join()
            assertTrue(MimirArtInkStore.hasVerdict(art))
            assertEquals(ink, MimirArtInkStore.boundsFor(art))
        }
    }

    @Test
    fun `the look-ahead names the arts around the focused row, nearest first, further ahead than behind, each once`() {
        val games = List(20) { index ->
            GameRow(
                gameId = GameId("g$index"),
                title = "Game $index",
                platformId = PlatformId.GBA,
                memberCount = 1,
                duplicateKind = null,
                artUri = if (index == 12) null else "file:///cover-$index.png",
            )
        }

        val uris = gamelistInkPrefetchUris(games, centre = 10)

        assertEquals(
            listOf(10, 11, 9, 8, 13, 7, 14, 15, 16).map { "file:///cover-$it.png" },
            uris,
        )
        assertEquals("an empty list names nothing", emptyList<String>(), gamelistInkPrefetchUris(emptyList(), 0))
        assertEquals("the ends are clamped, not wrapped", listOf("file:///cover-0.png", "file:///cover-1.png"), gamelistInkPrefetchUris(games, centre = -5).take(2))
    }

    // ------------------------------------------------------------------------------------------- the wiring, characterised

    private fun source(name: String): String {
        val candidates = listOf(
            java.io.File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name"),
            java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name"),
        )
        return candidates.firstOrNull(java.io.File::isFile)?.readText() ?: error("$name not found")
    }

    @Test
    fun `the panel hands its hold to the art, and the list asks the store about the rows around the focused one`() {
        val panel = source("LibraryComponents.kt").substringAfter("internal fun GamePreviewPanel(")
        assertTrue("the real art is held until the store has a verdict", panel.contains("MimirArtInkStore.hasVerdict(artUri)"))
        assertTrue("and the held art is the one given to MimirBoxArt", panel.contains("held = !shown,"))
        val view = source("LibraryScreen.kt").substringAfter("internal fun GamelistView(").substringBefore("internal fun ScrollFocusedLazyItemIntoView(")
        assertTrue("the list measures ahead of the player", view.contains("MimirArtInkStore.prefetch(inkContext, inkPrefetch)"))
        assertTrue("and the store is loaded while the shelf is still showing", source("LibraryScreen.kt").contains("MimirArtInkStore.ensureLoaded(settleContext)"))
    }

    private companion object {
        const val ChipTag = "preview_chip"
    }
}
