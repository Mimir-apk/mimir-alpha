package com.gruning.mimir.rewrite

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.feature.library.HomeShelfRow
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Opening Customize media no longer reads the saved stores in the key handler (audit 2026-10-14, RC-29: H-09, L-08).
 *
 * The builder is handed a context that records the thread of every store touch (the files dir, the cache dir, the preferences). A page being
 * OPENED comes back at once with an empty gallery and `catalogFirstRead`, the stores are read on another thread, and the gallery lands
 * through the page's own `update` -- ready, and no longer "first read". A switch of type INSIDE the open page keeps its reads in the handler,
 * so the gallery on screen does not empty and refill.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CustomMediaPageOpenOffMainTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val callerThread: Thread = Thread.currentThread()

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** A context that remembers which threads reached the stores. */
    private class RecordingContext(base: Context) : ContextWrapper(base) {
        val touchedBy: MutableSet<Thread> = Collections.synchronizedSet(mutableSetOf())

        private fun touch() {
            touchedBy += Thread.currentThread()
        }

        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = super.getFilesDir().also { touch() }
        override fun getCacheDir(): File = super.getCacheDir().also { touch() }
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = super.getSharedPreferences(name, mode).also { touch() }
    }

    private val row = HomeShelfRow(
        itemId = HomeShelfItemId.Emulated(GameId("game-1")),
        rank = 1L,
        addedAtMillis = 10L,
        title = "Metroid Prime",
    )

    private fun menu(phase: HomeTileMenuPhase = HomeTileMenuPhase.Menu) =
        HomeTileMenuState(row = row, removable = true, entryPoint = HomeTileMenuEntryPoint.EDIT, phase = phase)

    /** The page as the host holds it: the state the builder returned, and the `update` the builder is handed. */
    private class Page {
        val live = AtomicReference<HomeCustomEnrichState?>(null)
        val updates = Collections.synchronizedList(mutableListOf<HomeCustomEnrichState>())
        val update: (String, (HomeCustomEnrichState) -> HomeCustomEnrichState) -> Unit = { sessionId, transform ->
            // The builder's read can land before the test has stored what it returned; the host installs the page first.
            while (live.get() == null) Thread.sleep(2)
            live.updateAndGet { current -> if (current!!.sessionId == sessionId) transform(current) else current }.also { updates += it!! }
        }

        fun state(): HomeCustomEnrichState = live.get()!!
    }

    private fun build(
        recording: RecordingContext,
        kind: EnrichMediaKind,
        mediaState: MediaState,
        current: HomeTileMenuState,
        update: (String, (HomeCustomEnrichState) -> HomeCustomEnrichState) -> Unit,
    ): HomeCustomEnrichState = buildHomeCustomEnrichPicker(
        context = recording,
        current = current,
        kind = kind,
        mediaState = mediaState,
        migrations = mutableMapOf<String, Deferred<EnrichLegacyMigrationOutcome>>(),
        scope = scope,
        update = update,
    )

    private fun awaitReady(page: Page) = runBlocking {
        withTimeout(10_000) {
            while (!(page.state().catalogReady && !page.state().catalogFirstRead)) kotlinx.coroutines.delay(10)
        }
    }

    @Test
    fun `a single-lane page being opened returns before its stores are read, and the gallery lands from another thread`() {
        val recording = RecordingContext(ApplicationProvider.getApplicationContext())
        val page = Page()
        val initial = build(recording, EnrichMediaKind.HERO, MediaState.SHELF, menu(), page.update)
        page.live.set(initial)

        assertTrue("the first frame is the first read, waiting", initial.catalogFirstRead)
        assertFalse(initial.catalogReady)
        assertTrue("with an empty gallery", initial.saved.isEmpty() && initial.activeAssetId == null)

        awaitReady(page)
        assertFalse(page.state().catalogFirstRead)
        assertTrue("the stores were reached", recording.touchedBy.isNotEmpty())
        assertFalse("and never by the thread that handled the press", callerThread in recording.touchedBy)
        assertTrue("one update carried the read", page.updates.size >= 1)
        assertEquals("it went to this page and no other", initial.sessionId, page.state().sessionId)
    }

    @Test
    fun `the merged Icon art page being opened reads its three lanes on another thread too`() {
        val recording = RecordingContext(ApplicationProvider.getApplicationContext())
        val page = Page()
        val initial = build(recording, EnrichMediaKind.ICON, MediaState.SHELF, menu(), page.update)
        page.live.set(initial)

        assertTrue(initial.catalogFirstRead)
        assertFalse(initial.catalogReady)
        assertTrue("the merged picker", initial.shelfIconArt)
        assertTrue(initial.saved.isEmpty())

        awaitReady(page)
        assertTrue(recording.touchedBy.isNotEmpty())
        assertFalse("not the press's thread", callerThread in recording.touchedBy)
    }

    @Test
    fun `a switch of type inside the open page keeps its reads in the handler and comes back whole`() {
        val recording = RecordingContext(ApplicationProvider.getApplicationContext())
        val open = menu(HomeTileMenuPhase.CustomEnrichPicker(HomeCustomEnrichState(
            sessionId = "open",
            kind = EnrichMediaKind.HERO,
            cacheKey = "game-1",
            gameTitle = "Metroid Prime",
            queryDraft = "Metroid Prime",
        )))
        val initial = build(recording, EnrichMediaKind.COVER, MediaState.LAUNCH_CARD, open) { _, _ -> error("a whole page has nothing to deliver") }

        assertFalse("not waiting on a first read", initial.catalogFirstRead)
        assertTrue("whole at once", initial.catalogReady)
        assertTrue("the press's own thread read the stores", callerThread in recording.touchedBy)
    }

    @Test
    fun `while the first read runs the page says nothing about it and waits for it`() {
        val waiting = HomeCustomEnrichState(
            sessionId = "s",
            kind = EnrichMediaKind.HERO,
            cacheKey = "game-1",
            gameTitle = "Metroid Prime",
            queryDraft = "Metroid Prime",
            catalogReady = false,
            catalogFirstRead = true,
        )
        val migrating = waiting.copy(catalogFirstRead = false)
        assertEquals("nothing is claimed in the first frames", "", homeCustomEnrichIdleStatusIntent(waiting).spokenText)
        assertEquals("Preparing saved choices…", homeCustomEnrichIdleStatusIntent(migrating).spokenText)
        assertNotEquals("a migration the player can see still says so in the footer", "Preparing saved choices…", homeCustomEnrichFooterCopy(waiting, lane = null).status)
        assertEquals("Preparing saved choices…", homeCustomEnrichFooterCopy(migrating, lane = null).status)
    }

    @Test
    fun `the page lands on the choice in use when the read arrives and the player has not moved`() {
        val source = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/HomeCustomEnrichPicker.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/HomeCustomEnrichPicker.kt"),
        ).first(File::exists).readText()
        val overlay = source.substringAfter("internal fun HomeCustomEnrichPickerOverlay(").substringBefore("\n}\n")
        assertTrue(overlay.contains("val mountedFocus = remember(state.sessionId) { homeCustomEnrichInitialKey(state) }"))
        assertTrue(
            "only from the key it mounted on, so a player who moved keeps his place",
            overlay.contains("now.focused == mountedFocus) authority.requestFocus(target)"),
        )
    }
}
