package com.gruning.mimir.rewrite

import android.app.Application
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import com.gruning.mimir.rewrite.core.metadata.online.RaProgressOutcome
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsAchievement
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsProgress
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Toast-delivery accounting. The unlock watch marked each unlock toasted BEFORE it posted the toast, and the
 * only thing that draws the toast is the Bottom's Presentation, so on a single-screen device (no toast host) every unlock was spent on a
 * toast no one saw. An unlock is now marked only once a consumer displayed it: with no host it stays unshown (and is still in Mimir's
 * achievements view on return); the Thor Bottom's path, a host that runs the toast its five seconds, marks it after display as before.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AchievementUnlockShownOnlyWhenDisplayedTest {

    @get:Rule
    val compose = createComposeRule()

    private val minute = 60_000L
    private val startMs = LocalDateTime.of(2026, 10, 14, 12, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val session = RaPlayingSession(operation = "op-a", gameId = "local-chrono", startedAtEpochMillis = startMs)
    private val nextSession = session.copy(operation = "op-b")

    private val stored = HashMap<String, List<Int>>()
    private var saves = 0
    private val posts = ArrayList<AchievementToast>()

    private val earned = RetroAchievementsAchievement(
        id = 9,
        title = "After the start",
        description = "d9",
        points = 10,
        unlocked = true,
        unlockedHardcore = false,
        badgeName = "00009",
        earnedAt = "2026-10-14 12:30:00",
    )

    @Before
    fun setUp() {
        AchievementToasts.resetForTest()
    }

    @After
    fun tearDown() {
        AchievementToasts.resetForTest()
    }

    private fun TestScope.poller(
        sessions: MutableStateFlow<RaPlayingSession?> = MutableStateFlow(null),
        post: suspend (AchievementToast) -> Boolean,
    ): AchievementSessionPoller = AchievementSessionPoller(
        scope = backgroundScope,
        sessions = sessions,
        gateOpen = { true },
        account = { "q9-player" },
        raGameIdFor = { id -> 1_234.takeIf { id == "local-chrono" } },
        held = { false },
        read = { RaProgressOutcome.Read(RetroAchievementsProgress(gameId = 1_234, title = "Chrono Cross", achievements = listOf(earned))) },
        loadToasted = { name -> stored[name].orEmpty() },
        saveToasted = { name, ids ->
            saves += 1
            stored[name] = ids
        },
        post = post,
        pause = { delay(it) },
        log = {},
        workContext = EmptyCoroutineContext,
    ).also { it.start() }

    // ── the single-screen device: no host ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `with no toast host mounted an unlock stays unshown, nothing is saved, and it is offered again at the next read`() = runTest {
        assertFalse("single screen: nothing collects the toast", AchievementToasts.hostMounted)
        val poller = poller { toast ->
            posts += toast
            AchievementToasts.showAndAwaitDisplay(toast)
        }

        poller.onReturned(session)
        runCurrent()
        assertEquals("the toast was offered", 1, posts.size)
        assertEquals("but not marked toasted", 0, saves)
        assertTrue(stored.isEmpty())
        assertNull("and nothing was left up on a screen no one has", AchievementToasts.current.value)

        poller.onReturned(nextSession)
        runCurrent()
        assertEquals("the same unlock is offered again, because it was never shown", 2, posts.size)
        assertEquals(0, saves)
    }

    // ── the Thor Bottom: a host that runs the toast its time ─────────────────────────────────────────────────────────────────────

    @Test
    fun `with a host that runs the toast its five seconds the unlock is marked after the display, and only then`() = runTest {
        // The Bottom's host stand-in: it collects the one toast flow (that is what mounts a host) and reports each showing done after its time.
        backgroundScope.launch {
            AchievementToasts.current.collect { toast ->
                if (toast != null) {
                    delay(ACHIEVEMENT_TOAST_MS)
                    AchievementToasts.dismiss(toast.key)
                }
            }
        }
        runCurrent()
        assertTrue("a host is mounted", AchievementToasts.hostMounted)
        val poller = poller { toast ->
            posts += toast
            AchievementToasts.showAndAwaitDisplay(toast)
        }

        poller.onReturned(session)
        runCurrent()
        assertEquals(1, posts.size)
        assertEquals("on screen, but not yet displayed to its end: not marked", 0, saves)
        assertTrue("the toast is up on the host", AchievementToasts.current.value != null)

        advanceTimeBy(ACHIEVEMENT_TOAST_MS - 1)
        runCurrent()
        assertEquals("a millisecond short of its time", 0, saves)
        advanceTimeBy(2)
        runCurrent()
        assertEquals("displayed: marked and saved once", 1, saves)
        assertEquals(listOf(9), stored["q9-player"])

        poller.onReturned(nextSession)
        runCurrent()
        advanceTimeBy(minute)
        runCurrent()
        assertEquals("a shown unlock is not toasted again", 1, posts.size)
    }

    @Test
    fun `the watch ending while the toast is up still marks the unlock when the host finishes, so the return read does not toast it again`() = runTest {
        backgroundScope.launch {
            AchievementToasts.current.collect { toast ->
                if (toast != null) {
                    delay(ACHIEVEMENT_TOAST_MS)
                    AchievementToasts.dismiss(toast.key)
                }
            }
        }
        runCurrent()
        assertTrue(AchievementToasts.hostMounted)
        val sessions = MutableStateFlow<RaPlayingSession?>(session)
        val poller = poller(sessions) { toast ->
            posts += toast
            AchievementToasts.showAndAwaitDisplay(toast)
        }

        advanceTimeBy(RETRO_ACHIEVEMENTS_SESSION_POLL_MS + 1)
        runCurrent()
        assertEquals("the poll toasted the unlock", 1, posts.size)
        assertEquals("and the host is still showing it", 0, saves)

        // The game ends while the toast is up: the session goes to null and the watch loop is cancelled.
        sessions.value = null
        runCurrent()
        advanceTimeBy(ACHIEVEMENT_TOAST_MS + 1)
        runCurrent()
        assertEquals("the host finished after the watch was cancelled: the unlock is still marked, once", 1, saves)
        assertEquals(listOf(9), stored["q9-player"])

        poller.onReturned(session)
        runCurrent()
        advanceTimeBy(minute)
        runCurrent()
        assertEquals("the return read finds nothing new: the toast does not restart", 1, posts.size)
    }

    @Test
    fun `a host that never finishes the showing leaves the unlock unshown and takes the showing down`() = runTest {
        backgroundScope.launch { AchievementToasts.current.collect { } }
        runCurrent()
        assertTrue(AchievementToasts.hostMounted)
        val poller = poller { toast -> AchievementToasts.showAndAwaitDisplay(toast) }

        poller.onReturned(session)
        runCurrent()
        advanceTimeBy(ACHIEVEMENT_TOAST_MS + ACHIEVEMENT_TOAST_DISPLAY_GRACE_MS + 1)
        runCurrent()

        assertEquals("never displayed: not marked", 0, saves)
        assertNull("and not left up for a host that comes later", AchievementToasts.current.value)
    }

    // ── the real host ────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the Bottom's real host mounts the consumer, runs the toast, and reports it displayed`() {
        compose.mainClock.autoAdvance = false
        compose.setContent { MimirTheme { AchievementToastHost(badgeSource = NoActivityBadgeArt) } }
        advance(300L)
        assertTrue("the composed host is a consumer", AchievementToasts.hostMounted)

        val answer = CompletableFuture<Boolean>()
        val offered = AchievementToast(key = 0L, raGameId = 1_234, gameTitle = "Chrono Cross", count = 1, title = "After the start", points = 10, badgePath = null)
        thread(name = "q9-poster") { answer.complete(runBlocking { AchievementToasts.showAndAwaitDisplay(offered) }) }
        val deadline = System.currentTimeMillis() + 5_000L
        while (AchievementToasts.current.value == null && System.currentTimeMillis() < deadline) Thread.sleep(5L)
        assertTrue("the host was handed the toast", AchievementToasts.current.value != null)

        advance(300L)
        assertFalse("still up: not reported displayed yet", answer.isDone)
        advance(ACHIEVEMENT_TOAST_MS)
        assertTrue("run its five seconds, the host reports it displayed", answer.get(5, TimeUnit.SECONDS))
        assertNull(AchievementToasts.current.value)
    }

    @Test
    fun `with no host the real object answers false at once and posts nothing`() = runBlocking {
        assertFalse(AchievementToasts.hostMounted)
        val offered = AchievementToast(key = 0L, raGameId = 1_234, gameTitle = "Chrono Cross", count = 1, title = "x", points = 1, badgePath = null)

        assertFalse(AchievementToasts.showAndAwaitDisplay(offered))
        assertNull(AchievementToasts.current.value)
    }

    @Test
    fun `the coordinator's real sink is the displayed-or-not one`() {
        val source = listOf(
            java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/MimirAchievementsCoordinator.kt"),
            java.io.File("app/src/main/kotlin/com/gruning/mimir/rewrite/MimirAchievementsCoordinator.kt"),
        ).first(java.io.File::isFile).readText()
        assertTrue(source.contains("val toastSink: suspend (AchievementToast) -> Boolean = AchievementToasts::showAndAwaitDisplay,"))
    }

    private fun advance(ms: Long) {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(ms)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }
}
