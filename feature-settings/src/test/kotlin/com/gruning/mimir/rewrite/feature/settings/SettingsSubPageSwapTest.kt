package com.gruning.mimir.rewrite.feature.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.model.library.LibrarySummary
import com.gruning.mimir.rewrite.core.model.library.SourceRow
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * **Audit 2026-10-14, RC-34:** Settings sub-pages and panes swapped in one frame, both directions (the pane glide keys only on the category). The Music page and its Behaviour and
 * Forget-folder pages, the Library page and its source-removal confirmation, and the compact layout's rail and detail panes are now each one swap-tier `AnimatedContent`: the page left
 * fades out, the page arriving waits the measured lead and fades in, both composed in between, and a return puts the page returned to at rest at once.
 *
 * Audit 2026-10-15, F5: the Music and Library sub-pages are now ONE swap both ways on the Library view swap's clock ([SettingsSubPageSwapFrameTest] holds its alphas and travel); the compact
 * layout's rail and detail panes stay on the swap tier proper (a lead forward, a dissolve-over return).
 *
 * Frame by frame on a paused clock, off real pixels and the authority's own registrations (a page being left is inert, so its semantics are gone; its rows still register until it is
 * removed). The rule each test holds: on no frame is neither page there, on several both are, and the first frame of a swap is not a cut (its pixels have moved by well under half of the
 * way to where they settle).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w640dp-h480dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsSubPageSwapTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val authority = DefaultFocusAuthority()
    private val density = 2f
    private var mounted = false

    private var removal by mutableStateOf<SourceRow?>(null)

    private fun music() = MusicReadModel(
        musicEnabled = true,
        nowPlaying = null,
        source = MusicSourceValue.MIMIR,
        customSourceAvailable = false,
        order = MusicOrderValue.SHUFFLE,
        volumePercent = 50,
        songTitlesEnabled = true,
        folder = null,
        launchSoundEnabled = true,
        launchSoundVolumePercent = 100,
        gameMusic = GameMusicDownloadReadModel.Unavailable,
        playOnHighlight = false,
    )

    private val libraryPrimary = SourceRow(friendlyPath = "Internal storage/Mimir", rootUri = "test://source/0", gameCount = 40, available = true, isPrimary = true)
    private val libraryFolder = SourceRow(friendlyPath = "Internal storage/ROMs", rootUri = "test://source/1", gameCount = 12, available = true)

    private fun libraryModel() = LibraryReadModel(
        summary = LibrarySummary(gameCount = 52, platformCount = 8),
        sources = listOf(libraryPrimary, libraryFolder),
        allFilesAccess = true,
    )

    private fun mountPanel(category: SettingsCategory) {
        if (mounted) return
        mounted = true
        compose.runOnUiThread { compose.activity.actionBar?.hide() }
        compose.setContent {
            MimirTheme {
                val palette = LocalMimirPalette.current
                Box(Modifier.requiredSize(560.dp, 340.dp).background(palette.surfaceBase)) {
                    SettingsDetailPanel(
                        category = category,
                        authority = authority,
                        onRowActivate = {},
                        music = music(),
                        library = libraryModel().takeIf { category == SettingsCategory.LIBRARY },
                        sourceRemovalConfirmation = removal,
                    )
                }
            }
        }
    }

    private fun mountHome(widthDp: Int, heightDp: Int) {
        if (mounted) return
        mounted = true
        compose.runOnUiThread { compose.activity.actionBar?.hide() }
        compose.setContent {
            MimirTheme {
                val palette = LocalMimirPalette.current
                Box(Modifier.requiredSize(widthDp.dp, heightDp.dp).background(palette.surfaceBase)) {
                    SettingsHome(authority = authority)
                }
            }
        }
    }

    private fun settle(ms: Long = 2_000L) {
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    private fun bitmap(): Bitmap {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    /** Mean absolute channel difference over the whole frame, sampled every second pixel. */
    private fun meanDiff(a: Bitmap, b: Bitmap): Float {
        var sum = 0L
        var count = 0L
        var y = 0
        while (y < a.height) {
            var x = 0
            while (x < a.width) {
                val p = a.getPixel(x, y)
                val q = b.getPixel(x, y)
                sum += abs(((p shr 16) and 0xFF) - ((q shr 16) and 0xFF)) +
                    abs(((p shr 8) and 0xFF) - ((q shr 8) and 0xFF)) +
                    abs((p and 0xFF) - (q and 0xFF))
                count += 3
                x += 2
            }
            y += 2
        }
        return if (count == 0L) 0f else sum.toFloat() / count
    }

    private fun write(change: () -> Unit) {
        change()
        Snapshot.sendApplyNotifications()
    }

    private fun step(): Bitmap {
        compose.mainClock.advanceTimeBy(16)
        return bitmap()
    }

    private fun here(scope: FocusScope, key: FocusKey): Boolean = authority.registeredKeysInScope(scope).contains(key)

    private class Swap(
        val before: BooleanArray,
        val after: BooleanArray,
        val firstFrameDiff: Float,
        val settledDiff: Float,
        /** The first frame on which the page arriving held the key, or -1. */
        val landedOn: Int,
    )

    /** Runs [change] on a paused clock for 40 frames and reads which of the two pages is present on each, and how far the first frame moved. */
    private fun swap(
        before: () -> Boolean,
        after: () -> Boolean,
        landed: () -> Boolean,
        afterFrame: (Int) -> Unit = {},
        change: () -> Unit,
    ): Swap {
        compose.mainClock.autoAdvance = true
        settle()
        val base = bitmap()
        compose.mainClock.autoAdvance = false
        write(change)
        val b = BooleanArray(40)
        val a = BooleanArray(40)
        var firstFrameDiff = 0f
        var landedOn = -1
        for (frame in 0 until 40) {
            val shot = step()
            if (frame == 0) firstFrameDiff = meanDiff(shot, base)
            b[frame] = before()
            a[frame] = after()
            afterFrame(frame)
            if (landedOn < 0 && landed()) landedOn = frame
        }
        compose.mainClock.autoAdvance = true
        settle()
        return Swap(b, a, firstFrameDiff, meanDiff(bitmap(), base), landedOn)
    }

    /** [landBy] is the frame the key must have landed on the page arriving by: a sub-page is readable from about its third frame; the compact pane's forward swap waits its 130 ms lead (frame 8). */
    private fun assertSwapped(swap: Swap, what: String, firstFrameIsNotACut: Boolean, landBy: Int) {
        val both = swap.before.indices.count { swap.before[it] && swap.after[it] }
        val neither = swap.before.indices.count { !swap.before[it] && !swap.after[it] }
        println("SUBPAGE $what: both pages composed on $both of 40 frames, neither on $neither; first frame moved ${swap.firstFrameDiff} of ${swap.settledDiff}")
        assertEquals("$what: no frame has neither page", 0, neither)
        assertTrue("$what: both pages are composed together for several frames (a swap, not a cut): $both", both >= 4)
        assertFalse("$what: the page left is gone once it has faded", swap.before[39])
        assertTrue("$what: the page arrived stays", swap.after[39])
        assertTrue("$what: the swap leaves a visible difference to settle on (${swap.settledDiff})", swap.settledDiff > 1f)
        // The page arriving registers its rows from its first composed frame, so the key is on it by the time it is readable: a sub-page's cross-fade shows it from about frame 3, the compact
        // pane's forward swap after its 130 ms lead (frame 8), and a return that puts the page at rest at once has the authority's floor, a frame or two.
        println("SUBPAGE $what: the page arriving first held the key on frame ${swap.landedOn}")
        assertTrue("$what: the key landed on the page arriving (frame ${swap.landedOn})", swap.landedOn >= 0)
        assertTrue("$what: the key was there by the page's first readable frame (frame ${swap.landedOn}, by $landBy)", swap.landedOn < landBy)
        if (firstFrameIsNotACut) {
            assertTrue(
                "$what: the first frame is not a cut - it has moved ${swap.firstFrameDiff} of the ${swap.settledDiff} the swap settles to",
                swap.firstFrameDiff <= swap.settledDiff * 0.5f,
            )
        }
    }

    @Test
    fun `the Music page and its Behaviour page swap on the swap tier, both ways`() {
        mountPanel(SettingsCategory.MUSIC)
        compose.mainClock.autoAdvance = true
        settle()
        compose.runOnIdle {
            authority.pushScope(DetailScope)
            authority.requestFocus(detailKey(MusicBehaviourRowId))
        }
        settle()
        val page = detailKey(MusicSourceRowId)
        val behaviour = detailKey(MusicSongTitlesRowId)

        val forward = swap(
            before = { here(DetailScope, page) },
            after = { here(MusicBehaviourScope, behaviour) },
            landed = { authority.state.value.focused == behaviour },
            change = { authority.pushScope(MusicBehaviourScope) },
        )
        assertSwapped(forward, "Music to Behaviour", firstFrameIsNotACut = true, landBy = 4)
        assertEquals("the Behaviour page's first switch holds the key", behaviour, authority.state.value.focused)

        val back = swap(
            before = { here(MusicBehaviourScope, behaviour) },
            after = { here(DetailScope, page) },
            landed = { authority.state.value.focused == detailKey(MusicBehaviourRowId) },
            change = { authority.popScope() },
        )
        // F5: the way back is the way in mirrored, on the same clock, so its first frame is no more a cut than the first one's.
        assertSwapped(back, "Behaviour to Music", firstFrameIsNotACut = true, landBy = 4)
        assertEquals("a return lands on the row the page was opened from", detailKey(MusicBehaviourRowId), authority.state.value.focused)
    }

    @Test
    fun `the Forget folder confirmation swaps in over the Music page`() {
        mountPanel(SettingsCategory.MUSIC)
        compose.mainClock.autoAdvance = true
        settle()
        compose.runOnIdle {
            authority.pushScope(DetailScope)
            authority.requestFocus(detailKey(MusicFolderRowId))
            authority.rememberFocusForScope(MusicFolderForgetScope, detailKey(MusicFolderForgetCancelRowId))
        }
        settle()
        val forward = swap(
            before = { here(DetailScope, detailKey(MusicSourceRowId)) },
            after = { here(MusicFolderForgetScope, detailKey(MusicFolderForgetCancelRowId)) },
            landed = { authority.state.value.focused == detailKey(MusicFolderForgetCancelRowId) },
            change = { authority.pushScope(MusicFolderForgetScope) },
        )
        assertSwapped(forward, "Music to Forget folder", firstFrameIsNotACut = true, landBy = 4)
        assertEquals(detailKey(MusicFolderForgetCancelRowId), authority.state.value.focused)
    }

    @Test
    fun `the Library page and its source-removal confirmation swap on the swap tier, both ways`() {
        mountPanel(SettingsCategory.LIBRARY)
        compose.mainClock.autoAdvance = true
        settle()
        compose.runOnIdle { authority.pushScope(DetailScope) }
        settle()
        val page = detailKey("library/add")
        val confirm = detailKey(LibrarySourceRemovalCancelRowId)
        val source = libraryFolder
        // F6: Cancel and B land on the REMOVE row that was pressed, not on ADD LIBRARY FOLDER (the page's first row, which a swap would otherwise seed).
        val pressed = detailKey(librarySourceRemovalReturn(libraryModel().sources, source).onCancel)

        val forward = swap(
            before = { here(DetailScope, page) },
            after = { here(DetailScope, confirm) },
            landed = { authority.state.value.focused == confirm },
            // The coordinator's own beat (`requestDetailFocus`: one frame, then the request), which the page arriving has registered its rows for by then.
            afterFrame = { frame -> if (frame == 0) authority.requestFocus(confirm) },
            change = { removal = source },
        )
        assertSwapped(forward, "Library to its removal confirmation", firstFrameIsNotACut = true, landBy = 4)

        val back = swap(
            before = { here(DetailScope, confirm) },
            after = { here(DetailScope, page) },
            landed = { authority.state.value.focused == pressed },
            // The coordinator's own beat: one frame, then the request, for the row the confirmation was opened from.
            afterFrame = { frame -> if (frame == 0) authority.requestFocus(pressed) },
            change = { removal = null },
        )
        assertSwapped(back, "removal confirmation to Library", firstFrameIsNotACut = true, landBy = 4)
        assertEquals("Cancel lands on the REMOVE row it came from, not on ADD LIBRARY FOLDER", pressed, authority.state.value.focused)
    }

    @Test
    fun `on the compact layout the rail and the detail pane swap on the swap tier, both ways`() {
        mountHome(360, 360)
        compose.mainClock.autoAdvance = true
        settle()
        val rail = { authority.registeredKeysInScope(SettingsRailScope).isNotEmpty() }
        val detail = { authority.registeredKeysInScope(DetailScope).isNotEmpty() }
        assertTrue("compact opens on the rail", rail())
        assertFalse(detail())

        val forward = swap(
            before = rail,
            after = detail,
            landed = { authority.state.value.focused == detailKey(settingsFirstDetailRowId(SettingsCategory.PROFILE)) },
            change = {
                dispatchToSettings(authority, ControllerAction.NAV_RIGHT, onRailOpen = {}, onRowActivate = {}, onExit = {})
            },
        )
        assertSwapped(forward, "rail to detail", firstFrameIsNotACut = true, landBy = 8)
        assertEquals(
            "the detail's first row holds the key once it has landed",
            detailKey(settingsFirstDetailRowId(SettingsCategory.PROFILE)),
            authority.state.value.focused,
        )

        val back = swap(
            before = detail,
            after = rail,
            landed = { authority.state.value.focused == settingsRailFocusKey(SettingsCategory.PROFILE) },
            change = {
                dispatchToSettings(authority, ControllerAction.CANCEL, onRailOpen = {}, onRowActivate = {}, onExit = {})
            },
        )
        assertSwapped(back, "detail to rail", firstFrameIsNotACut = false, landBy = 4)
        assertEquals("B lands back on the rail entry it opened", settingsRailFocusKey(SettingsCategory.PROFILE), authority.state.value.focused)
    }

    /**
     * Fix round W1A #5: Utility, a picker and the select overlays push their scopes on the app's one authority. The compact pane and the Music sub-page followed the live scope, so a
     * foreign scope swapped the rail for the detail (or the sub-page for the cards) under the panel, and swapped back, a double fade, when it closed. They hold the last answer a
     * Settings scope gave. Underneath: nothing is built, nothing is dropped, on the way in or on the way out.
     */
    @Test
    fun `a foreign scope over the compact rail changes nothing underneath, in or out`() {
        mountHome(360, 360)
        compose.mainClock.autoAdvance = true
        settle()
        val rail = { authority.registeredKeysInScope(SettingsRailScope).isNotEmpty() }
        val detail = { authority.registeredKeysInScope(DetailScope).isNotEmpty() }
        assertTrue(rail())
        val utility = FocusScope("test/utility")
        compose.mainClock.autoAdvance = false
        var detailBuilt = false
        var railDropped = false
        write { authority.pushScope(utility) }
        repeat(40) { step(); detailBuilt = detailBuilt || detail(); railDropped = railDropped || !rail() }
        write { authority.popScope() }
        repeat(40) { step(); detailBuilt = detailBuilt || detail(); railDropped = railDropped || !rail() }
        assertFalse("the detail pane was never built under the panel", detailBuilt)
        assertFalse("the rail was never dropped", railDropped)
        assertEquals(SettingsRailScope, authority.state.value.scope)
    }

    @Test
    fun `a foreign scope over a Music sub-page keeps the sub-page, in or out`() {
        mountPanel(SettingsCategory.MUSIC)
        compose.mainClock.autoAdvance = true
        settle()
        compose.runOnIdle {
            authority.pushScope(DetailScope)
            authority.requestFocus(detailKey(MusicBehaviourRowId))
            authority.pushScope(MusicBehaviourScope)
        }
        settle()
        val cards = { here(DetailScope, detailKey(MusicSourceRowId)) }
        val behaviour = { here(MusicBehaviourScope, detailKey(MusicSongTitlesRowId)) }
        assertTrue(behaviour())
        assertFalse(cards())
        val utility = FocusScope("test/utility")
        compose.mainClock.autoAdvance = false
        var cardsBuilt = false
        var behaviourDropped = false
        write { authority.pushScope(utility) }
        repeat(40) { step(); cardsBuilt = cardsBuilt || cards(); behaviourDropped = behaviourDropped || !behaviour() }
        write { authority.popScope() }
        repeat(40) { step(); cardsBuilt = cardsBuilt || cards(); behaviourDropped = behaviourDropped || !behaviour() }
        assertFalse("the cards were never rebuilt under the panel", cardsBuilt)
        assertFalse("the Behaviour page was never dropped", behaviourDropped)
        assertEquals(MusicBehaviourScope, authority.state.value.scope)
    }
}
