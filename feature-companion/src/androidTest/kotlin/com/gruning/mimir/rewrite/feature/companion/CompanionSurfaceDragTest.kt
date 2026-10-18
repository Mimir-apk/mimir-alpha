package com.gruning.mimir.rewrite.feature.companion

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.test.cancel
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.up
import com.gruning.mimir.rewrite.core.model.CompanionHomeLane
import com.gruning.mimir.rewrite.core.model.CompanionHomeTile
import com.gruning.mimir.rewrite.core.model.CompanionHomeTileKind
import com.gruning.mimir.rewrite.core.model.CompanionMode
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class CompanionSurfaceDragTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun useManualClock() {
        composeRule.mainClock.autoAdvance = false
    }

    @Test
    fun quickTapActivatesOnceWhileHeldReleaseNeverCofires() {
        val activations = AtomicInteger()
        val payload = shelfPayload("a", "b", "c")
        setSurface(payload, onAppTap = { _, _ -> activations.incrementAndGet() })
        val root = bounds(RootTag)
        val first = bounds(slotTag(0)).center - root.topLeft

        composeRule.onNodeWithTag(RootTag).performTouchInput {
            down(first)
            advanceEventTime(179L)
            up()
        }
        advance(32L)
        assertEquals(1, activations.get())

        composeRule.onNodeWithTag(RootTag).performTouchInput {
            down(first)
            advanceEventTime(180L)
            moveTo(first)
        }
        advance(32L)
        composeRule.onNodeWithTag(liftTag("a")).assertExists()
        composeRule.onNodeWithTag(RootTag).performTouchInput { up() }
        advance(240L)

        assertEquals(1, activations.get())
        composeRule.onNodeWithTag(liftTag("a")).assertDoesNotExist()
    }

    @Test
    fun firstPostDeadlineMoveIsConsumedBeforeEnabledPagerInOnePointerStream() {
        val arms = AtomicInteger()
        val activations = AtomicInteger()
        val commits = CopyOnWriteArrayList<List<String>>()
        val deadlineMoveConsumed = AtomicReference<Boolean?>(null)
        val armsObservedAtFinalPass = AtomicInteger(-1)
        val ids = ('a'..'l').map { it.toString() }
        setSurface(
            payload = shelfPayload(*ids.toTypedArray()),
            modifier = Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Final,
                    )
                    val deadline = down.uptimeMillis + CompanionDragStateMachine.LongPressMillis
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (
                            change.pressed && change.uptimeMillis >= deadline &&
                            deadlineMoveConsumed.compareAndSet(null, change.isConsumed)
                        ) {
                            armsObservedAtFinalPass.set(arms.get())
                        }
                        if (!change.pressed) break
                    }
                }
            },
            onAppTap = { _, _ -> activations.incrementAndGet() },
            onArm = { arms.incrementAndGet() },
            onReorder = commits::add,
        )
        val root = bounds(RootTag)
        val source = bounds(slotTag(0)).center - root.topLeft
        val destination = bounds(slotTag(2)).center - root.topLeft

        // The manual frame clock stays frozen for this whole raw stream. HorizontalPager therefore remains
        // composed with userScrollEnabled=true while the root consumes the deadline MOVE at Initial.
        composeRule.onNodeWithTag(RootTag).performTouchInput {
            down(source)
            advanceEventTime(180L)
            moveTo(source)
            moveTo(destination)
            up()
        }

        assertEquals(true, deadlineMoveConsumed.get())
        assertEquals(1, armsObservedAtFinalPass.get())
        advance(300L)

        assertEquals(0, activations.get())
        assertEquals(1, commits.size)
        assertEquals(listOf("b", "c", "a") + ('d'..'l').map { it.toString() }, commits.single())
    }

    @Test
    fun cancelAfterPreviewRestoresAuthoritativeBoundsAndNeverPersists() {
        val commits = CopyOnWriteArrayList<List<String>>()
        setSurface(shelfPayload("a", "b", "c", "d"), onReorder = commits::add)
        val root = bounds(RootTag)
        val sourceBefore = bounds(tileTag("a"))
        val source = bounds(slotTag(0)).center - root.topLeft
        val destination = bounds(slotTag(2)).center - root.topLeft

        composeRule.onNodeWithTag(RootTag).performTouchInput {
            down(source)
            advanceEventTime(180L)
            moveTo(source)
        }
        advance(32L)
        composeRule.onNodeWithTag(RootTag).performTouchInput { moveTo(destination) }
        advance(180L)
        composeRule.onNodeWithTag(RootTag).performTouchInput { cancel() }
        advance(400L)

        assertTrue(commits.isEmpty())
        composeRule.onNodeWithTag(liftTag("a")).assertDoesNotExist()
        assertRectNear(sourceBefore, bounds(tileTag("a")))
    }

    @Test
    fun edgeTurnThenUpDuringAnimationCommitsPageTwoOrderOnce() {
        val commits = CopyOnWriteArrayList<List<String>>()
        val ids = ('a'..'l').map { it.toString() }
        setSurface(shelfPayload(*ids.toTypedArray()), onReorder = commits::add)
        val root = bounds(RootTag)
        val source = bounds(slotTag(0)).center - root.topLeft
        val edge = Offset(root.width - 1f, source.y)

        composeRule.onNodeWithTag(RootTag).performTouchInput {
            down(source)
            advanceEventTime(180L)
            moveTo(source)
        }
        advance(32L)
        composeRule.onNodeWithTag(RootTag).performTouchInput { moveTo(edge) }
        composeRule.onNodeWithTag(RootTag).performTouchInput {
            advanceEventTime(449L)
            moveTo(edge)
        }
        val sourceAt449 = bounds(slotTag(0)).center
        assertTrue(sourceAt449.x in root.left..root.right)
        assertTrue(commits.isEmpty())
        composeRule.onNodeWithTag(RootTag).performTouchInput {
            advanceEventTime(1L)
            moveTo(edge)
        }
        composeRule.mainClock.advanceTimeBy(32L)
        composeRule.onNodeWithTag(RootTag).performTouchInput { up() }
        assertTrue(commits.isEmpty())
        advance(1_300L)

        assertEquals(1, commits.size)
        assertEquals(('b'..'l').map { it.toString() } + "a", commits.single())
    }

    @Test
    fun cancelDuringPageAnimationRollsBackWithoutTerminalCallback() {
        val activations = AtomicInteger()
        val commits = CopyOnWriteArrayList<List<String>>()
        val merges = AtomicInteger()
        val ids = ('a'..'l').map { it.toString() }
        setSurface(
            payload = shelfPayload(*ids.toTypedArray()),
            onAppTap = { _, _ -> activations.incrementAndGet() },
            onReorder = commits::add,
            onBind = { _, _, _ -> merges.incrementAndGet() },
        )
        val root = bounds(RootTag)
        val sourceBounds = bounds(tileTag("a"))
        val source = bounds(slotTag(0)).center - root.topLeft
        val edge = Offset(root.width - 1f, source.y)

        composeRule.onNodeWithTag(RootTag).performTouchInput {
            down(source)
            advanceEventTime(180L)
            moveTo(source)
            moveTo(edge)
            advanceEventTime(450L)
            moveTo(edge)
        }
        composeRule.mainClock.advanceTimeBy(32L)
        composeRule.onNodeWithTag(RootTag).performTouchInput { cancel() }
        advance(1_500L)

        assertEquals(0, activations.get())
        assertTrue(commits.isEmpty())
        assertEquals(0, merges.get())
        composeRule.onNodeWithTag(liftTag("a")).assertDoesNotExist()
        assertRectNear(sourceBounds, bounds(tileTag("a")))
    }

    @Test
    fun moveDuringPageAnimationUpdatesLiftAndPreventsRepeatTurn() {
        val commits = CopyOnWriteArrayList<List<String>>()
        val ids = ('a'..'t').map { it.toString() }
        setSurface(shelfPayload(*ids.toTypedArray()), onReorder = commits::add)
        val root = bounds(RootTag)
        val source = bounds(slotTag(0)).center - root.topLeft
        val inward = bounds(slotTag(1)).center - root.topLeft
        val edge = Offset(root.width - 1f, source.y)

        composeRule.onNodeWithTag(RootTag).performTouchInput {
            down(source)
            advanceEventTime(180L)
            moveTo(source)
            moveTo(edge)
            advanceEventTime(449L)
            moveTo(edge)
            advanceEventTime(1L)
            moveTo(edge)
        }
        advance(32L)
        composeRule.onNodeWithTag(RootTag).performTouchInput { moveTo(inward) }
        advance(32L)
        assertPointNear(root.topLeft + inward, bounds(liftTag("a")).center, tolerance = 3f)

        // This exceeds a fresh 450 ms edge dwell. A stale edge pointer would turn to page three here.
        advance(1_800L)
        composeRule.onNodeWithTag(RootTag).performTouchInput { up() }
        advance(300L)

        assertEquals(1, commits.size)
        assertEquals(
            ('b'..'k').map { it.toString() } + "a" + ('l'..'t').map { it.toString() },
            commits.single(),
        )
    }

    @Test
    fun payloadRecompositionDuringDragPreservesAdditionAndRemoval() {
        val payload = mutableStateOf(shelfPayload("a", "b", "c", "d"))
        val commit = AtomicReference<List<String>?>(null)
        composeRule.setContent {
            CompanionSurface(
                payload = payload.value,
                onReorder = commit::set,
                monotonicUptimeMillis = { composeRule.mainClock.currentTime },
            )
        }
        advance(600L)
        val root = bounds(RootTag)
        val source = bounds(slotTag(0)).center - root.topLeft
        val destination = bounds(slotTag(2)).center - root.topLeft

        composeRule.onNodeWithTag(RootTag).performTouchInput {
            down(source)
            advanceEventTime(180L)
            moveTo(source)
        }
        advance(32L)
        composeRule.onNodeWithTag(RootTag).performTouchInput { moveTo(destination) }
        advance(32L)
        composeRule.runOnUiThread {
            payload.value = shelfPayload("a", "x", "b", "d")
        }
        advance(64L)
        composeRule.onNodeWithTag(RootTag).performTouchInput { up() }
        advance(240L)

        assertEquals(listOf("b", "x", "a", "d"), commit.get())
    }

    @Test
    fun allEmptyPayloadDuringDragRemovesDraggedIdInsteadOfUsingRetainedHome() {
        val payload = mutableStateOf(shelfPayload("a", "b", "c"))
        val activations = AtomicInteger()
        val commits = CopyOnWriteArrayList<List<String>>()
        val merges = AtomicInteger()
        composeRule.setContent {
            CompanionSurface(
                payload = payload.value,
                onAppTap = { _, _ -> activations.incrementAndGet() },
                onReorder = commits::add,
                onBind = { _, _, _ -> merges.incrementAndGet() },
                monotonicUptimeMillis = { composeRule.mainClock.currentTime },
            )
        }
        advance(600L)
        val root = bounds(RootTag)
        val source = bounds(slotTag(0)).center - root.topLeft
        val destination = bounds(slotTag(2)).center - root.topLeft

        composeRule.onNodeWithTag(RootTag).performTouchInput {
            down(source)
            advanceEventTime(180L)
            moveTo(source)
            moveTo(destination)
        }
        advance(32L)
        composeRule.runOnUiThread { payload.value = emptyShelfPayload() }
        advance(64L)
        composeRule.onNodeWithTag(RootTag).performTouchInput { up() }
        advance(600L)

        assertEquals(0, activations.get())
        assertTrue(commits.isEmpty())
        assertEquals(0, merges.get())
        composeRule.onNodeWithTag(liftTag("a")).assertDoesNotExist()
    }

    @Test
    fun draggedBookOverBookPreviewsTheSameOrderThatUpCommits() {
        val commits = CopyOnWriteArrayList<List<String>>()
        val merges = AtomicInteger()
        setSurface(
            payload = bookPayload(),
            onReorder = commits::add,
            onBind = { _, _, _ -> merges.incrementAndGet() },
        )
        val root = bounds(RootTag)
        val source = bounds(slotTag(0)).center - root.topLeft
        val destination = bounds(slotTag(1)).center - root.topLeft

        composeRule.onNodeWithTag(RootTag).performTouchInput {
            down(source)
            advanceEventTime(180L)
            moveTo(source)
        }
        advance(32L)
        composeRule.onNodeWithTag(RootTag).performTouchInput { moveTo(destination) }
        advance(200L)

        assertRectNear(bounds(slotTag(0)), bounds(tileTag("book-b")), tolerance = 3f)
        composeRule.onNodeWithTag(RootTag).performTouchInput { up() }
        advance(300L)

        assertEquals(listOf(listOf("book-b", "book-a", "c")), commits)
        assertEquals(0, merges.get())
    }

    @Test
    fun mergeDwellIsUnarmedAt749AndArmedAt750() {
        val merges = CopyOnWriteArrayList<Triple<List<String>, String, String>>()
        val clockAnchor = AtomicReference(0L to 0L)
        setSurface(
            payload = mergePayload(),
            modifier = Modifier.pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        clockAnchor.set(
                            event.changes.maxOf { it.uptimeMillis } to composeRule.mainClock.currentTime,
                        )
                    }
                }
            },
            onBind = { order, draggedId, targetId ->
                merges += Triple(order, draggedId, targetId)
            },
            monotonicUptimeMillis = {
                val (eventTime, composeTime) = clockAnchor.get()
                eventTime + (composeRule.mainClock.currentTime - composeTime).coerceAtLeast(0L)
            },
        )
        val root = bounds(RootTag)
        val source = bounds(slotTag(0)).center - root.topLeft
        val book = bounds(slotTag(1)).center - root.topLeft
        val bookNear = book + Offset(1f, 0f)

        composeRule.onNodeWithTag(RootTag).performTouchInput {
            down(source)
            advanceEventTime(180L)
            moveTo(source)
            advanceEventTime(16L)
            moveTo(book)
            advanceEventTime(16L)
            moveTo(bookNear)
        }
        val renderedDwellMillis = 16L
        advance(renderedDwellMillis)
        composeRule.onNodeWithTag(mergeHoverTag("book")).assertExists()
        composeRule.mainClock.advanceTimeBy(
            CompanionDragStateMachine.MergeDwellMillis - renderedDwellMillis - 1L,
            ignoreFrameDuration = true,
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(mergeArmedTag("book")).assertDoesNotExist()
        assertTrue(merges.isEmpty())

        composeRule.mainClock.advanceTimeBy(1L, ignoreFrameDuration = true)
        advance(16L)
        composeRule.onNodeWithTag(mergeArmedTag("book")).assertExists()
        composeRule.onNodeWithTag(RootTag).performTouchInput { up() }
        advance(240L)

        assertEquals(
            listOf(Triple(listOf("book", "c"), "a", "book")),
            merges,
        )
    }

    @Test
    fun armedMergeRejectsCoalescedFastUpMotion() {
        val commits = CopyOnWriteArrayList<List<String>>()
        val merges = CopyOnWriteArrayList<Triple<List<String>, String, String>>()
        setSurface(
            payload = mergePayload(),
            onReorder = commits::add,
            onBind = { order, draggedId, targetId ->
                merges += Triple(order, draggedId, targetId)
            },
        )
        val root = bounds(RootTag)
        val source = bounds(slotTag(0)).center - root.topLeft
        val book = bounds(slotTag(1)).center - root.topLeft
        val stableBook = book + Offset(1f, 0f)

        composeRule.onNodeWithTag(RootTag).performTouchInput {
            down(source)
            advanceEventTime(180L)
            moveTo(source)
            advanceEventTime(16L)
            moveTo(book)
            advanceEventTime(16L)
            moveTo(stableBook)
            advanceEventTime(749L)
            moveTo(book)
            advanceEventTime(1L)
            moveTo(stableBook)
        }
        advance(16L)
        composeRule.onNodeWithTag(mergeArmedTag("book")).assertExists()
        composeRule.onNodeWithTag(RootTag).performTouchInput {
            updatePointerTo(0, stableBook + Offset(10f, 0f))
            up()
        }
        advance(300L)

        assertTrue(merges.isEmpty())
        assertEquals(listOf(listOf("book", "a", "c")), commits)
    }

    @Test
    fun cornerPickupPreservesFingerToTileOffsetInAllNineCells() {
        val ids = ('a'..'i').map { it.toString() }
        setSurface(shelfPayload(*ids.toTypedArray()))
        val root = bounds(RootTag)

        ids.forEachIndexed { slot, id ->
            val slotBounds = bounds(slotTag(slot))
            val touchGlobal = when (slot % 4) {
                0 -> Offset(slotBounds.left + 5f, slotBounds.top + 7f)
                1 -> Offset(slotBounds.right - 5f, slotBounds.top + 7f)
                2 -> Offset(slotBounds.right - 5f, slotBounds.bottom - 7f)
                else -> Offset(slotBounds.left + 5f, slotBounds.bottom - 7f)
            }
            composeRule.onNodeWithTag(RootTag).performTouchInput {
                down(touchGlobal - root.topLeft)
                advanceEventTime(180L)
                moveTo(touchGlobal - root.topLeft)
            }
            advance(32L)

            val liftBounds = bounds(liftTag(id))
            assertRectNear(slotBounds, liftBounds, tolerance = 2f)

            composeRule.onNodeWithTag(RootTag).performTouchInput { up() }
            advance(220L)
            composeRule.onNodeWithTag(liftTag(id)).assertDoesNotExist()
        }
    }

    @Test
    fun pagerOwnedSwipeAndDragOwnedHoldCannotBothEmitTerminalIntent() {
        val activations = AtomicInteger()
        val commits = CopyOnWriteArrayList<List<String>>()
        val settledPage = AtomicInteger(0)
        val ids = ('a'..'l').map { it.toString() }
        setSurface(
            payload = shelfPayload(*ids.toTypedArray()),
            onAppTap = { _, _ -> activations.incrementAndGet() },
            onReorder = commits::add,
            onPageSettled = settledPage::set,
        )
        val root = bounds(RootTag)
        composeRule.onNodeWithTag(RootTag).performTouchInput {
            swipe(
                start = Offset(root.width * 0.82f, root.height * 0.45f),
                end = Offset(root.width * 0.18f, root.height * 0.45f),
                durationMillis = 100L,
            )
        }
        advance(1_000L)

        assertEquals(1, settledPage.get())
        assertEquals(0, activations.get())
        assertTrue(commits.isEmpty())

        val pageTwoSource = bounds(slotTag(9)).center - root.topLeft
        val pageTwoDestination = bounds(slotTag(11)).center - root.topLeft
        composeRule.onNodeWithTag(RootTag).performTouchInput {
            down(pageTwoSource)
            advanceEventTime(180L)
            moveTo(pageTwoSource)
        }
        advance(32L)
        composeRule.onNodeWithTag(RootTag).performTouchInput { moveTo(pageTwoDestination) }
        advance(32L)
        composeRule.onNodeWithTag(RootTag).performTouchInput { up() }
        advance(240L)

        assertEquals(0, activations.get())
        assertEquals(1, commits.size)
        assertEquals(
            ('a'..'i').map { it.toString() } + listOf("k", "l", "j"),
            commits.single(),
        )
    }

    private fun setSurface(
        payload: CompanionPayload,
        modifier: Modifier = Modifier,
        onAppTap: (String, String) -> Unit = { _, _ -> },
        onArm: (String) -> Unit = {},
        onReorder: (List<String>) -> Unit = {},
        onPageSettled: (Int) -> Unit = {},
        onBind: (List<String>, String, String) -> Unit = { _, _, _ -> },
        monotonicUptimeMillis: () -> Long = { composeRule.mainClock.currentTime },
    ) {
        composeRule.setContent {
            CompanionSurface(
                payload = payload,
                modifier = modifier,
                onAppTap = onAppTap,
                onArm = onArm,
                onReorder = onReorder,
                onPageSettled = onPageSettled,
                onBind = onBind,
                monotonicUptimeMillis = monotonicUptimeMillis,
            )
        }
        advance(600L)
    }

    private fun advance(millis: Long) {
        composeRule.mainClock.advanceTimeBy(millis)
        composeRule.waitForIdle()
    }

    private fun bounds(tag: String): Rect =
        composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun assertRectNear(expected: Rect, actual: Rect, tolerance: Float = 1f) {
        assertTrue("left: $expected != $actual", abs(expected.left - actual.left) <= tolerance)
        assertTrue("top: $expected != $actual", abs(expected.top - actual.top) <= tolerance)
        assertTrue("right: $expected != $actual", abs(expected.right - actual.right) <= tolerance)
        assertTrue("bottom: $expected != $actual", abs(expected.bottom - actual.bottom) <= tolerance)
    }

    private fun assertPointNear(expected: Offset, actual: Offset, tolerance: Float = 1f) {
        assertTrue("x: $expected != $actual", abs(expected.x - actual.x) <= tolerance)
        assertTrue("y: $expected != $actual", abs(expected.y - actual.y) <= tolerance)
    }

    private companion object {
        const val RootTag = "companion-drag-root"

        fun slotTag(slot: Int): String = "companion-slot-$slot"
        fun liftTag(id: String): String = "companion-lift-$id"
        fun tileTag(id: String): String = "companion-tile-$id"
        fun mergeArmedTag(id: String): String = "companion-merge-armed-$id"
        fun mergeHoverTag(id: String): String = "companion-merge-hover-$id"

        fun shelfPayload(vararg ids: String): CompanionPayload = CompanionPayload(
            mode = CompanionMode.HOME,
            homeLanes = listOf(
                CompanionHomeLane(
                    id = "apps",
                    title = "Apps",
                    hint = "",
                    tiles = ids.map { id ->
                        CompanionHomeTile(
                            id = id,
                            kind = CompanionHomeTileKind.ANDROID_APP,
                            label = id.uppercase(),
                            kindLabel = "App",
                        )
                    },
                ),
            ),
        )

        fun emptyShelfPayload(): CompanionPayload = CompanionPayload(
            mode = CompanionMode.IDLE,
            homeLanes = listOf(
                CompanionHomeLane(
                    id = "apps",
                    title = "Apps",
                    hint = "",
                    tiles = emptyList(),
                ),
            ),
        )

        fun bookPayload(): CompanionPayload = CompanionPayload(
            mode = CompanionMode.HOME,
            homeLanes = listOf(
                CompanionHomeLane(
                    id = "apps",
                    title = "Apps",
                    hint = "",
                    tiles = listOf(
                        CompanionHomeTile("book-a", CompanionHomeTileKind.FOLDER, "Book A"),
                        CompanionHomeTile("book-b", CompanionHomeTileKind.FOLDER, "Book B"),
                        CompanionHomeTile("c", CompanionHomeTileKind.ANDROID_APP, "C"),
                    ),
                ),
            ),
        )

        fun mergePayload(): CompanionPayload = CompanionPayload(
            mode = CompanionMode.HOME,
            homeLanes = listOf(
                CompanionHomeLane(
                    id = "apps",
                    title = "Apps",
                    hint = "",
                    tiles = listOf(
                        CompanionHomeTile("a", CompanionHomeTileKind.ANDROID_APP, "A"),
                        CompanionHomeTile("book", CompanionHomeTileKind.FOLDER, "Book"),
                        CompanionHomeTile("c", CompanionHomeTileKind.ANDROID_APP, "C"),
                    ),
                ),
            ),
        )
    }
}
