package com.gruning.mimir.rewrite

import android.animation.ValueAnimator
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * the earlier update, the menu's half: while a game-music run is going, "Download music for this game" LOOKS busy, the way Enrich does while it runs. Enrich's
 * running look is the Working card's: a gold round spinner, here seated after the row's label with the label dimmed. The row reads the state the
 * Settings > Music card reads ([GameMusicDownloadState.Running], through [rememberGameMusicRunning]) and nothing else, so one game's press, the
 * Settings batch and the card all agree. The row stays pressable: a second press is answered on the rail ("Still finding music...").
 *
 * The overlay is mounted as the host mounts it (its own composable, the row list as the Home Edit menu builds it), and the flow is driven through
 * Idle, Running (with progress ticks) and Finished. Pixels are the JVM's, API 30 like the toast pictures (a software canvas cannot blur); when this
 * environment cannot draw the overlay the semantics still stand and the picture is skipped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w832dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GameMusicMenuBusyRowTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val density369 = 369f / 160f
    private val label = HomeTileMenuAction.DOWNLOAD_GAME_MUSIC.label
    private val rows = listOf(
        HomeTileMenuAction.CUSTOM_ENRICH,
        HomeTileMenuAction.DOWNLOAD_GAME_MUSIC,
        HomeTileMenuAction.ADD_TO_COLLECTION,
        HomeTileMenuAction.ACHIEVEMENTS,
        HomeTileMenuAction.REMOVE_FAVORITE,
    ).map { it.label }
    private val state = MutableStateFlow<GameMusicDownloadState>(GameMusicDownloadState.Idle(have = 0, total = 10))

    @After
    fun tearDown() {
        setAnimatorScale(1f)
    }

    private fun setAnimatorScale(scale: Float) {
        ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, scale)
    }

    private fun advance(ms: Long) {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(ms / 2L)
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(ms - ms / 2L)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    private fun mount(menuFocus: Int) {
        setAnimatorScale(0f)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density369, 1.0f)) {
                MimirTheme {
                    Box(Modifier.requiredWidth(832.dp).requiredHeight(468.dp)) {
                        // The host's own read: the flow the Music card reads, turned into the one flag the overlay takes.
                        val musicBusy = rememberGameMusicRunning(state)
                        val musicNotConnected = rememberGameMusicNotConnected(state)
                        TileArtMenuOverlay(
                            title = "Chrono Trigger",
                            options = rows,
                            menuFocus = menuFocus,
                            phase = HomeTileMenuPhase.Menu,
                            onMenuFocus = {},
                            onActivateMenu = {},
                            onPickFocus = {},
                            onActivatePick = {},
                            destructiveLabel = HomeTileMenuAction.REMOVE_FAVORITE.label,
                            busyLabel = label.takeIf { musicBusy },
                            dimmedLabel = label.takeIf { musicNotConnected },
                        )
                    }
                }
            }
        }
        advance(600L)
    }

    private val spinner = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
    private val busyRow = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "In progress")

    private fun spinners() = compose.onAllNodes(spinner, useUnmergedTree = true).fetchSemanticsNodes().size
    private fun busyRows() = compose.onAllNodes(busyRow, useUnmergedTree = true).fetchSemanticsNodes()

    private fun frame(): Bitmap? = runCatching {
        val decor = compose.activity.window.decorView
        Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888).also { decor.draw(Canvas(it)) }
    }.onFailure { println("MUSIC264-MENU the overlay cannot be drawn here: $it") }.getOrNull()

    private fun save(frame: Bitmap?, name: String) {
        val dir = File("build/test-captures")
        if (frame == null || !dir.isDirectory) return
        File(dir, name).outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun `the row is plain while nothing runs, busy for the whole of a run, and plain again when it ends`() {
        mount(menuFocus = 1)
        assertEquals("no spinner at rest", 0, spinners())
        assertEquals("no row reads busy at rest", 0, busyRows().size)

        state.value = GameMusicDownloadState.Running(done = 0, total = 0, written = 0)
        advance(300L)
        assertEquals("one spinner while the run goes", 1, spinners())
        assertEquals("one busy row", 1, busyRows().size)

        // A batch reports each game it gets through; the row stays busy through every tick and shows still one spinner.
        state.value = GameMusicDownloadState.Running(done = 3, total = 10, written = 2)
        advance(300L)
        assertEquals(1, spinners())
        assertEquals(1, busyRows().size)

        state.value = GameMusicDownloadState.Finished(written = 1, skipped = 0, failed = 0)
        advance(300L)
        assertEquals("the spinner is gone when the run ends", 0, spinners())
        assertEquals("and the row is plain", 0, busyRows().size)

        state.value = GameMusicDownloadState.Idle(have = 1, total = 10)
        advance(300L)
        assertEquals(0, spinners())
    }

    @Test
    fun `only the music row is busy, it keeps its words, and it can still be pressed`() {
        mount(menuFocus = 0)
        state.value = GameMusicDownloadState.Running(done = 0, total = 0, written = 0)
        advance(300L)

        val busy = busyRows().single()
        fun labelIn(text: String) = compose.onAllNodes(hasContentDescription(text), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.boundsInWindow
        val rowBounds = busy.boundsInWindow
        val musicLabel = labelIn(label)
        assertTrue("the busy row still carries its label", musicLabel != null)
        assertTrue("and the busy row IS the music row: its label stands inside it", rowBounds.contains(musicLabel!!.center))
        assertTrue("it is still pressable (a second press is answered on the rail)", busy.config.contains(SemanticsActions.OnClick))
        rows.filter { it != label }.forEach { other ->
            val otherLabel = labelIn(other) ?: throw AssertionError("$other is not on the menu")
            assertTrue("$other is not on the busy row", !rowBounds.contains(otherLabel.center))
        }
    }

    @Test
    fun `the busy row is drawn differently from the idle one, focused or not`() {
        mount(menuFocus = 1)
        val idle = frame()
        save(idle, "music264-menu-idle-1.0.png")

        state.value = GameMusicDownloadState.Running(done = 0, total = 0, written = 0)
        advance(300L)
        val busyFocused = frame()
        save(busyFocused, "music264-menu-busy-focused-1.0.png")
        val bounds = busyRows().single().boundsInWindow

        if (idle != null && busyFocused != null) {
            var sum = 0L
            var count = 0L
            val x0 = bounds.left.toInt().coerceAtLeast(0)
            val x1 = bounds.right.toInt().coerceAtMost(idle.width)
            val y0 = bounds.top.toInt().coerceAtLeast(0)
            val y1 = bounds.bottom.toInt().coerceAtMost(idle.height)
            for (y in y0 until y1 step 2) {
                for (x in x0 until x1 step 2) {
                    val a = idle.getPixel(x, y)
                    val b = busyFocused.getPixel(x, y)
                    sum += abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) + abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) + abs((a and 0xFF) - (b and 0xFF))
                    count += 3
                }
            }
            val meanChange = sum.toDouble() / count
            println("MUSIC264-MENU busy row=$bounds meanChange=$meanChange size=${idle.width}x${idle.height}")
            assertTrue("the music row is drawn differently while it runs: mean channel change $meanChange", meanChange > 2.0)
        }
    }

    // ── Not connected: dimmed at rest, the way Enrich's pill is ─────────────────────────────────────

    private val dimmedRow = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Not connected")
    private fun dimmedRows() = compose.onAllNodes(dimmedRow, useUnmergedTree = true).fetchSemanticsNodes()

    @Test
    fun `the music row is dimmed at rest while no account is connected, still pressable, and plain once one is`() {
        state.value = GameMusicDownloadState.Idle(have = 0, total = 10, block = GameMusicBlock.NotConnected)
        mount(menuFocus = 0)
        val dimmed = dimmedRows().single()
        fun labelIn(text: String) = compose.onAllNodes(hasContentDescription(text), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.boundsInWindow
        assertTrue("the dimmed row IS the music row", dimmed.boundsInWindow.contains(labelIn(label)!!.center))
        assertTrue("it is still pressable: the press answers on the rail, 'connect first'", dimmed.config.contains(SemanticsActions.OnClick))
        assertEquals("it is not busy: no spinner, no 'In progress'", 0, spinners() + busyRows().size)
        rows.filter { it != label }.forEach { other -> assertTrue("$other is not dimmed", !dimmed.boundsInWindow.contains(labelIn(other)!!.center)) }
        val notConnected = frame()
        save(notConnected, "music266-menu-not-connected-1.0.png")

        // Connected: the same row, plain.
        state.value = GameMusicDownloadState.Idle(have = 0, total = 10)
        advance(300L)
        assertEquals("plain once an account is connected", 0, dimmedRows().size)
        val connected = frame()
        save(connected, "music266-menu-connected-1.0.png")
        if (notConnected != null && connected != null) {
            val b = dimmed.boundsInWindow
            var most = 0
            for (y in b.top.toInt().coerceAtLeast(0) until b.bottom.toInt().coerceAtMost(connected.height)) {
                for (x in b.left.toInt().coerceAtLeast(0) until b.right.toInt().coerceAtMost(connected.width)) {
                    val p = notConnected.getPixel(x, y)
                    val q = connected.getPixel(x, y)
                    most = maxOf(most, abs(((p shr 16) and 0xFF) - ((q shr 16) and 0xFF)))
                }
            }
            println("MUSIC266-MENU the label's brightest change when not connected: $most")
            assertTrue("the row's label is drawn dimmer when no account is connected: $most", most >= 20)
        }
    }

    @Test
    fun `only a missing connection dims the row, the way only a missing account dims Enrich`() {
        mount(menuFocus = 0)
        assertEquals(0, dimmedRows().size)
        // Another action holding the gate is answered on the rail when pressed, not dimmed at rest: Enrich's pill is no dimmer while a music run goes.
        state.value = GameMusicDownloadState.Idle(have = 0, total = 10, block = GameMusicBlock.EnrichRunning)
        advance(300L)
        assertEquals(0, dimmedRows().size)
        // A run in progress is busy, and busy wins: one spinner, no 'Not connected'.
        state.value = GameMusicDownloadState.Running(done = 0, total = 0, written = 0)
        advance(300L)
        assertEquals(0, dimmedRows().size)
        assertEquals(1, spinners())
        state.value = GameMusicDownloadState.Idle(have = 0, total = 10, block = GameMusicBlock.NotConnected)
        advance(300L)
        assertEquals(1, dimmedRows().size)
        assertEquals(0, spinners())
    }

    @Test
    fun `the row is unfocused-busy in a second picture`() {
        mount(menuFocus = 0)
        state.value = GameMusicDownloadState.Running(done = 0, total = 0, written = 0)
        advance(300L)
        save(frame(), "music264-menu-busy-unfocused-1.0.png")
        assertEquals(1, spinners())
    }

    // ── The wiring, as source ─────────────────────────────────────────────────────────────────────────────────

    private fun source(name: String): String = listOf(
        File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
    ).firstOrNull(File::isFile)?.readText() ?: throw AssertionError("$name not reachable from the test working directory")

    @Test
    fun `the host reads the state the Music card reads and hands the overlay one flag`() {
        val host = source("MimirAppHost.kt")
        assertTrue(host.contains("musicBusy = rememberGameMusicRunning(bindings.artwork.state.gameMusicState)"))
        assertTrue("the dim reads the same flow's block, the one the Music card and the press read", host.contains("musicNotConnected = rememberGameMusicNotConnected(bindings.artwork.state.gameMusicState)"))
        assertTrue(source("GameMusicGameFeedback.kt").contains("current.block == GameMusicBlock.NotConnected"))
        val feedback = source("GameMusicGameFeedback.kt")
        assertTrue("busy means a Running state, nothing finer", feedback.contains("current is GameMusicDownloadState.Running"))
        val settings = source("MimirSettingsReadCoordinator.kt")
        assertTrue("the Music card reads the same flow", settings.contains("val gameMusic by artwork.state.gameMusicState.collectAsState()"))
        val overlay = source("HomeTileMenuOverlay.kt")
        assertTrue(overlay.contains("busyLabel = HomeTileMenuAction.DOWNLOAD_GAME_MUSIC.label.takeIf { musicBusy }"))
        assertTrue("the spinner is the Working card's: gold, round", overlay.contains("CircularProgressIndicator(Modifier.size(14.dp), color = MimirColors.Gold"))
    }
}
