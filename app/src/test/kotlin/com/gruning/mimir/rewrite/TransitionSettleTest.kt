package com.gruning.mimir.rewrite

import android.app.Application
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.rememberMimirGlassHold
import com.gruning.mimir.rewrite.core.ui.rememberMimirGlassProgress
import java.io.File
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
 * Return from Eden exposed blurred background and launch-card option switches briefly blinked
 * on Thor (2026-10-14). The timing checks use real
 * composables under a paused clock:
 *  - the return cover lifts two frames after focus lands, not on the frame its state was written;
 *  - glass UNDER a departing modal is held through its dissolve and cleared afterward;
 *    a restored surface's glass is a settled state;
 *  - Home's launch card wears its glass as a draw-phase level on a constant modifier, with the same hold.
 * The wiring (which host uses which) is pinned as source, below the behaviour.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TransitionSettleTest {

    @get:Rule
    val compose = createComposeRule()

    private var requested by mutableStateOf(false)
    private var titleEdit by mutableStateOf(false)
    private var cancelKey by mutableStateOf(0L)
    private var settleOpen = false

    @Before
    fun setUp() {
        compose.mainClock.autoAdvance = false
    }

    private fun write(block: () -> Unit) {
        compose.runOnUiThread(block)
        Snapshot.sendApplyNotifications()
    }

    private fun advance(ms: Long) {
        compose.mainClock.advanceTimeBy(ms)
        Snapshot.sendApplyNotifications()
    }

    // --- The lift waits for the surface to be drawn ---

    @Test
    fun `the lift waits two frames after focus lands`() {
        var control by mutableStateOf(false)
        var lifted by mutableStateOf(false)
        compose.setContent {
            LaunchedEffect(Unit) {
                awaitLibraryLaunchReturnSurfaceDrawn()
                lifted = true
            }
            LaunchedEffect(Unit) {
                withFrameNanos { }
                control = true
            }
        }
        // Frame by frame until the one-frame control has run: the wait must not be done on that frame, and must be done one later.
        var frames = 0
        while (!control && frames < 10) {
            compose.mainClock.advanceTimeByFrame()
            frames++
        }
        assertTrue("the control frame ran", control)
        assertFalse("one frame is not enough: the surface has not drawn what the write composed", lifted)
        compose.mainClock.advanceTimeByFrame()
        assertTrue("two frames: composed in the first, drawn by the second's start", lifted)
        assertEquals(2, LibraryLaunchReturnDrawnFrames)
    }

    // --- The glass under a leaving modal is held through the modal's dissolve ---

    @Test
    fun `the glass hold stays up through the dissolve and clears after it`() {
        var held = false
        compose.setContent { held = rememberMimirGlassHold(requested = requested, cancelKey = cancelKey) }
        write { requested = true }
        advance(50L)
        assertTrue(held)
        write { requested = false }
        advance(MimirMotion.OverlayDissolveCoverMs - 30L)
        assertTrue("still held while the modal is dissolving (its alpha is above .05 until ${MimirMotion.OverlayDissolveCoverMs} ms)", held)
        advance(100L)
        assertFalse("released once the modal has gone", held)
    }

    @Test
    fun `a transition cancellation releases the glass hold at once`() {
        var held = false
        compose.setContent { held = rememberMimirGlassHold(requested = requested, cancelKey = cancelKey) }
        write { requested = true }
        advance(50L)
        write { requested = false }
        advance(40L)
        assertTrue(held)
        write { cancelKey += 1 }
        advance(20L)
        assertFalse(held)
    }

    @Test
    fun `the press that closes a modal bumps the epoch in the same frame and the hold still holds, a later bump cancels`() {
        // Every Library press bumps the transition epoch before it is handled, so the closing press moves the cancel key in the very frame the
        // request ends (review 2026-10-14: the hold was released by the press that began it, the glass at .56 under a picker at .10).
        var held = false
        compose.setContent { held = rememberMimirGlassHold(requested = requested, cancelKey = cancelKey) }
        write { requested = true }
        advance(50L)
        Snapshot.withMutableSnapshot {
            requested = false
            cancelKey += 1
        }
        Snapshot.sendApplyNotifications()
        advance(MimirMotion.OverlayDissolveCoverMs - 30L)
        assertTrue("held through the dissolve despite the same-frame bump", held)
        write { cancelKey += 1 }
        advance(20L)
        assertFalse("a bump AFTER the hold began is a real new transition and releases it", held)
    }

    @Test
    fun `the request's own first frame is held, not one frame late`() {
        var held = false
        compose.setContent { held = rememberMimirGlassHold(requested = requested, cancelKey = cancelKey) }
        assertFalse(held)
        write { requested = true }
        compose.mainClock.advanceTimeByFrame()
        assertTrue("held in the composition that carries the request", held)
    }

    @Test
    fun `no frame has the modal part-way and the glass behind it part-way`() {
        // The Library picker: the glass follows the held request; the picker's pixels fade out on OverlayMs from the commit.
        var progress: () -> Float = { 0f }
        compose.setContent {
            val held = rememberMimirGlassHold(requested = requested, cancelKey = cancelKey)
            progress = rememberMimirGlassProgress(visible = held)
        }
        write { requested = true }
        advance(MimirMotion.OverlayMs * 2L)
        assertEquals("settled behind the open picker", 1f, progress(), 0.001f)

        // The commit, with the epoch bump every real press makes in the same frame (the picker closes on A, B and Back).
        Snapshot.withMutableSnapshot {
            requested = false
            cancelKey += 1
        }
        Snapshot.sendApplyNotifications()
        var t = 0L
        val frame = 16L
        while (t <= MimirMotion.OverlayMs + 2 * frame) {
            compose.mainClock.advanceTimeBy(frame)
            t += frame
            val pickerAlpha = 1f - MimirMotion.Easing.transform((t.toFloat() / MimirMotion.OverlayMs).coerceIn(0f, 1f))
            val glass = progress()
            // Visible picker (alpha above .05): the glass has not moved. Glass moving: the picker is gone to the eye.
            assertTrue(
                "t=$t ms: picker alpha $pickerAlpha with the glass at $glass (the card un-blurring under a half-faded picker)",
                pickerAlpha <= 0.05f || glass >= 0.999f,
            )
        }
        advance(MimirMotion.OverlayMs * 2L)
        assertEquals("then it clears", 0f, progress(), 0.001f)
    }

    @Test
    fun `a settled open snaps the glass to full on the first frame, an ordinary one ramps`() {
        var progress: () -> Float = { 0f }
        compose.setContent {
            progress = rememberMimirGlassProgress(visible = requested, settleOpen = { settleOpen })
        }
        settleOpen = false
        write { requested = true }
        advance(100L)
        val ramping = progress()
        assertTrue("an ordinary open is mid-ramp at 100 ms: $ramping", ramping > 0f && ramping < 0.9f)
        write { requested = false }
        advance(MimirMotion.OverlayMs * 2L)

        settleOpen = true
        write { requested = true }
        advance(80L)
        assertEquals("a restored surface's glass is already settled", 1f, progress(), 0.001f)
    }

    // --- Home's launch card glass: what is DRAWN is a radius and a strength (review 2026-10-14) ---

    @Test
    fun `home card glass for a child modal draws the standard 18 dp backdrop at full strength, never half of a 34 dp blur`() {
        val glass = mountHomeGlass(childModal = true)
        advance(MimirMotion.OverlayMs * 2L)
        assertEquals("the whole blur shows", 1f, glass.level(), 0.001f)
        assertEquals("at the product's standard modal radius", 18.dp, glass.radius())
        // Sampled every frame of the close: strength only falls, and the radius never moves while any of it shows.
        var last = glass.level()
        write { this.childModal = false }
        repeat(40) {
            compose.mainClock.advanceTimeBy(16L)
            val now = glass.level()
            assertTrue("never rises while closing: $now > $last", now <= last + 0.0001f)
            assertEquals("the radius is frozen through the hold and the clear", 18.dp, glass.radius())
            last = now
        }
        assertEquals("cleared", 0f, glass.level(), 0.001f)
    }

    @Test
    fun `home card glass follows the rename editor to its 34 dp ceiling and holds through the dissolve`() {
        val glass = mountHomeGlass()
        assertEquals(0f, glass.level(), 0f)
        write { titleEdit = true }
        advance(MimirMotion.OverlayMs * 2L)
        assertEquals("the rename overlay: the whole blur", 1f, glass.level(), 0.001f)
        assertEquals("at its own ceiling", 34.dp, glass.radius())
        write { titleEdit = false }
        advance(MimirMotion.OverlayDissolveCoverMs - 30L)
        assertEquals("held at full strength while the overlay dissolves", 1f, glass.level(), 0.001f)
        advance(MimirMotion.OverlayMs * 2L)
        assertEquals(0f, glass.level(), 0.001f)
    }

    @Test
    fun `home card glass never steps its radius under a blur that is showing, and takes the new radius from clear`() {
        // Open with (18 dp) closes and a rename opens within the ~310 ms hold-and-clear: the radius that is on screen stays, the strength
        // runs back up from where it is. (The first cut changed 18 to 34 dp at full strength.)
        val glass = mountHomeGlass(childModal = true)
        advance(MimirMotion.OverlayMs * 2L)
        write { this.childModal = false }
        advance(MimirMotion.OverlayDissolveCoverMs + 110L) // past the hold and its frame beats, into the clear: some blur shows
        val mid = glass.level()
        assertTrue("some of the blur still shows: $mid", mid > 0.05f && mid < 0.999f)
        write { titleEdit = true }
        var lastRadius = glass.radius()
        repeat(40) {
            compose.mainClock.advanceTimeBy(16L)
            val r = glass.radius()
            if (glass.level() > 0.001f) assertEquals("no radius step while blur shows", lastRadius, r)
            lastRadius = r
        }
        assertEquals("the rename holds the radius that was showing", 18.dp, glass.radius())
        assertEquals(1f, glass.level(), 0.001f)
        // Fully cleared, the next modal takes its own radius.
        write { titleEdit = false }
        advance(MimirMotion.OverlayMs * 3L)
        assertEquals(0f, glass.level(), 0.001f)
        write { titleEdit = true }
        advance(MimirMotion.OverlayMs * 2L)
        assertEquals("from clear the rename opens at its ceiling", 34.dp, glass.radius())
        assertEquals(1f, glass.level(), 0.001f)
    }

    @Test
    fun `home card glass radius is the ceiling for the editor and the standard backdrop for a child modal`() {
        assertEquals(34.dp, homeLaunchCardGlassRadius(titleEditActive = true, titleEditRadius = 34.dp, childRadius = 18.dp))
        assertEquals(18.dp, homeLaunchCardGlassRadius(titleEditActive = false, titleEditRadius = 34.dp, childRadius = 18.dp))
    }

    private var childModal by mutableStateOf(false)

    private fun mountHomeGlass(childModal: Boolean = false): HomeLaunchCardGlass {
        var glass: HomeLaunchCardGlass? = null
        this.childModal = childModal
        compose.setContent {
            glass = rememberHomeLaunchCardGlass(
                titleEditActive = titleEdit,
                childModalActive = this@TransitionSettleTest.childModal,
                titleEditRadius = 34.dp,
                childRadius = 18.dp,
                cancelKey = cancelKey,
            )
        }
        advance(32L)
        return requireNotNull(glass)
    }

    // --- Wiring, pinned as source ---

    private fun source(name: String): String = listOf(
        File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
    ).firstOrNull(File::exists)?.readText() ?: throw AssertionError("$name not reachable from the test working directory")

    @Test
    fun `the cover is composed always and its input follows the request, not what is still showing`() {
        val host = source("MimirAppHost.kt")
        val overlayHost = host.substringAfter("private fun MimirAppOverlayHost(").substringBefore("private fun MimirEqualizerEditorFadeHost(")
        assertTrue(overlayHost.contains("MimirPrimaryReturnCover(bindings, requested = active == MimirOverlayKind.RETURN_COVER)"))
        assertFalse(
            "the cover must not be composed only while on top: it would leave on the frame its request ends",
            overlayHost.contains("if (active == MimirOverlayKind.RETURN_COVER) MimirPrimaryReturnCover"),
        )
        val cover = source("ReturnCoverSurface.kt")
        assertTrue("Back is registered only while requested", cover.contains("if (requested) BackHandler { }"))
        assertTrue("the pointer consumer exists only while requested", cover.contains("if (requested) Modifier.pointerInput(Unit)"))
        assertTrue("the exit is the house fade", cover.contains("exit = fadeOut(tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing))"))
        assertTrue("entry is unheld", cover.contains("enter = EnterTransition.None"))
        assertTrue("the visible state is the request or the one-frame beat", cover.contains("visible = requested || lingering"))
    }

    @Test
    fun `the return-wait status hangs on the host's resumed flag and freezes what the wait was`() {
        val status = source("ReturnWaitStatus.kt")
        assertTrue(status.contains("resumed = bindings.navigation.libraryHostResumed,"))
        assertTrue(status.contains("if (returnWaitIsRunning(waiting, resumed)) {"))
        assertTrue("the frozen subject and cause", status.contains("if (requested || frozen[0] == null) frozen[0] = returnWaitSubject(session, cause, armed) to cause"))
    }

    @Test
    fun `both lift sites of the Library launch return wait for the drawn surface before they lift the cover`() {
        val nav = source("MimirNavigationCoordinator.kt")
        val effect = nav.substringAfter("private fun MimirLibraryLaunchReturnEffect(").substringBefore("internal const val LibraryLaunchReturnDrawnFrames")
        val liftIndexes = Regex("""navigation\.libraryLaunchReturnRestoring = false""").findAll(effect).map { it.range.first }.toList()
        val waits = Regex("""awaitLibraryLaunchReturnSurfaceDrawn\(\)""").findAll(effect).map { it.range.first }.toList()
        // The first lift is the unreconcilable-anchor guard before the restore begins (no surface to wait for); the other two are the restore's.
        assertEquals(3, liftIndexes.size)
        assertEquals(2, waits.size)
        assertTrue("the focus-landed lift is preceded by its wait", waits[0] < liftIndexes[1] && waits[0] > liftIndexes[0])
        assertTrue("the fallthrough lift is preceded by its wait", waits[1] < liftIndexes[2] && waits[1] > liftIndexes[1])
    }

    @Test
    fun `Home's launch card wears its glass on a constant modifier, no layer is added or removed mid-transition`() {
        val host = source("MimirAppHost.kt")
        val cardHostFull = host.substringAfter("private fun HomeLaunchCardOverlayHost(").substringBefore("private fun HomeLaunchCardLiveContent(")
        // Code only: the comments explain the old shape in its own words.
        val cardHost = cardHostFull
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")
        assertFalse("no value-read blur radius", cardHost.contains("animateDpAsState"))
        assertFalse("no conditional blur layer", cardHost.contains("if (blurring)"))
        assertFalse("no Modifier.blur on the card", Regex("""\.blur\(""").containsMatchIn(cardHost))
        assertTrue(Regex("""Modifier\.fillMaxSize\(\)\.mimirGlassBackdropProgress\(\s*cardGlass\.radius,\s*cardGlass\.level,\s*snapshot = \{\s*homeLaunchCardSnapshotWanted\(""").containsMatchIn(cardHost))
        assertTrue(
            "the same session scope as the 08-11 note",
            cardHost.contains("val cardGlass: HomeLaunchCardGlass = rememberHomeLaunchCardGlassPerCard(\n            cardKey = target,\n            epoch = frame.epoch,") &&
                cardHost.contains("): HomeLaunchCardGlass = key(cardKey, epoch) {\n    rememberHomeLaunchCardGlass("),
        )
        // The held pixels of the card are untouched (pinned by HomeTileMenuSwapScrimTest as well).
        assertTrue(cardHost.contains("primaryLayer.alpha = alpha * underSheetAlpha.value"))
    }

    @Test
    fun `the equalizer editor leaves on the house pixel fade, arrives on its own tier, and the glass holds behind it`() {
        val host = source("MimirAppHost.kt")
        assertTrue(host.contains("MimirEqualizerEditorFadeHost(bindings, visible = active == MimirOverlayKind.EQUALIZER_EDITOR)"))
        assertFalse(host.contains("if (active == MimirOverlayKind.EQUALIZER_EDITOR) MimirEqualizerEditorHost(bindings)"))
        val fade = host.substringAfter("private fun MimirEqualizerEditorFadeHost(").substringBefore("\n}\n")
        assertTrue("no second ramp on the arrival", fade.contains("instantIncoming = true,") && fade.contains("incomingStartAlpha = { _, _, _ -> 1f },"))
        assertTrue(fade.contains("durationMs = MimirMotion.OverlayMs,"))
        assertEquals(MimirMotion.OverlayDissolveCoverMs, mimirOutgoingChromeHoldMs(MimirOverlayKind.EQUALIZER_EDITOR))
    }

    @Test
    fun `the Library return restore settles its page change and closes a card the target does not ask for`() {
        val nav = source("MimirNavigationCoordinator.kt")
        val effect = nav.substringAfter("private fun MimirLibraryLaunchReturnEffect(").substringBefore("internal const val LibraryLaunchReturnDrawnFrames")
        assertTrue(effect.contains("if (libraryLaunchReturnClosesOpenCard(target, detailSession, library.detailSession != null)) {"))
        val library = listOf(
            File("../feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/LibraryScreen.kt"),
            File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/LibraryScreen.kt"),
        ).firstOrNull(File::exists)?.readText() ?: throw AssertionError("LibraryScreen.kt not reachable from the test working directory")
        assertTrue(
            "the page change under a restoring Library is the settled motion",
            library.contains("settled = libraryPageRestoreSettled(viewModel.restoringLaunchReturnTarget()),"),
        )
        assertTrue(
            "and so is the shelf-mode change a restore may carry (a Favorites-launched Android game's target names ANDROID_GAMES)",
            library.contains("pageRestoreSettled = { libraryPageRestoreSettled(viewModel.restoringLaunchReturnTarget()) },") &&
                library.contains("if (pageRestoreSettled()) LibraryPageMotion.Settled else LibraryPageMotion.Entry(direction = shelfModeStep())"),
        )
        assertFalse(
            "the settled decision reads the restoring target, never the one that stays pending through the whole game session",
            Regex("""libraryPageRestoreSettled\(viewModel\.pendingLaunchReturnTarget|libraryDetailArrivalSettled\(viewModel\.pendingLaunchReturnTarget""").containsMatchIn(library),
        )
        // The ledger's log line says what the Library was showing and what the return restores, so the next odd return is one line to read.
        val status = source("ReturnWaitStatus.kt")
        assertTrue(status.contains("\"library=\${if (library.openPlatform == null) \"shelf\" else \"gamelist\"}"))
        assertTrue(status.contains("\"target=\${libraryTarget?.surface?.name?.lowercase() ?: \"none\"}"))
    }
}
