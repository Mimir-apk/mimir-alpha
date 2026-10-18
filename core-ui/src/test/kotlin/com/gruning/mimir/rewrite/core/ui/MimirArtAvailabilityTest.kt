package com.gruning.mimir.rewrite.core.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The ONE off-main availability resolver: every existence guard asks
 * [rememberMimirArtWired], which stats the file once per model on the IO dispatcher, never on the composition thread and never per
 * recomposition. While the stat is pending the source counts as WIRED (the painter loads under the 10-01 fade) and flips to the
 * no-art fallback only when the stat returns missing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MimirArtAvailabilityTest {

    @get:Rule
    val compose = createComposeRule()

    /** A dispatcher that holds every block until [release], so the "stat pending" frame can be observed. */
    private class GateDispatcher : CoroutineDispatcher() {
        val held = ArrayDeque<Runnable>()
        var dispatched = 0

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatched += 1
            held.addLast(block)
        }

        fun release() {
            while (held.isNotEmpty()) held.removeFirst().run()
        }
    }

    @Before
    fun cold() {
        MimirArtAvailability.clearForTest()
    }

    @After
    fun restore() {
        MimirArtAvailability.dispatcher = Dispatchers.IO
        MimirArtAvailability.nowMs = { System.nanoTime() / 1_000_000L }
        MimirArtAvailability.clearForTest()
    }

    // ---- the pure parts ----------------------------------------------------------------------------------------------

    @Test
    fun `no source is unwired, and a source that needs no stat is wired without one`() {
        assertFalse(mimirArtWiredBeforeCheck(null))
        assertFalse(mimirArtWiredBeforeCheck(""))
        assertFalse(mimirArtWiredBeforeCheck("   "))
        assertTrue(mimirArtWiredBeforeCheck("https://cdn.example.org/art.png"))
        assertTrue(mimirArtWiredBeforeCheck("content://media/external/images/1"))
        assertNull(mimirArtLocalFile("content://media/external/images/1"))
    }

    @Test
    fun `a local file is pending-wired before it has been stat-ed, whatever has been remembered`() {
        assertTrue("pending counts as wired", mimirArtWiredBeforeCheck("/data/pick.png"))
        MimirArtAvailability.record("/data/pick.png", exists = true)
        assertTrue(mimirArtWiredBeforeCheck("/data/pick.png"))
        MimirArtAvailability.record("/data/pick.png", exists = false)
        assertTrue("a remembered 'missing' does not start the mount unwired", mimirArtWiredBeforeCheck("/data/pick.png"))
        assertEquals("/data/pick.png", mimirArtLocalFile("file:///data/pick.png?v=3"))
        assertEquals(File("/data/pick.png").path, mimirArtLocalFile(File("/data/pick.png")))
    }

    @Test
    fun `the stat runs off the calling thread and is remembered`() = runBlocking {
        val caller = Thread.currentThread()
        var statThread: Thread? = null
        val exists = MimirArtAvailability.resolve("/data/pick.png") { statThread = Thread.currentThread(); true }
        assertTrue(exists)
        assertNotEquals("the stat must not run on the composing thread", caller, statThread)
        assertTrue(MimirArtAvailability.peekExists("/data/pick.png"))
    }

    // ---- the composable ------------------------------------------------------------------------------------------------

    @Test
    fun `a gone file is wired while the stat is pending and unwired when it returns missing`() {
        val gate = GateDispatcher()
        MimirArtAvailability.dispatcher = gate
        val seen = mutableListOf<Boolean>()
        compose.setContent { seen += rememberMimirArtWired(MissingFile) }
        compose.waitForIdle()
        assertEquals("the first frame is pending, so wired", true, seen.first())
        assertEquals("one stat was asked for", 1, gate.dispatched)

        gate.release()
        compose.waitForIdle()
        assertEquals("the stat found the file gone", false, seen.last())
    }

    @Test
    fun `recomposition never repeats the stat`() {
        val gate = GateDispatcher()
        MimirArtAvailability.dispatcher = gate
        var tick by mutableStateOf(0)
        compose.setContent {
            tick.let { }
            rememberMimirArtWired(MissingFile)
        }
        compose.waitForIdle()
        repeat(5) {
            tick += 1
            compose.waitForIdle()
        }
        assertEquals("one stat for the model however often it recomposes", 1, gate.dispatched)
    }

    @Test
    fun `a source that names no local file is wired with no stat at all`() {
        val gate = GateDispatcher()
        MimirArtAvailability.dispatcher = gate
        val seen = mutableListOf<Boolean>()
        compose.setContent {
            seen += rememberMimirArtWired("https://cdn.example.org/art.png")
            seen += rememberMimirArtWired(null)
        }
        compose.waitForIdle()
        assertEquals(listOf(true, false), seen.take(2))
        assertEquals("nothing to stat", 0, gate.dispatched)
    }
    // ---- the fresh-"exists" cache (pending = wired; a cached "missing" never seeds; newer stat wins) -------------------------

    private var clock = 0L

    private fun fakeClock() {
        clock = 1_000_000L
        MimirArtAvailability.nowMs = { clock }
    }

    @Test
    fun `a cached MISSING verdict never seeds a mount, fresh or not, so restored art is pending-wired`() {
        fakeClock()
        MimirArtAvailability.record(MissingFile, exists = false)
        assertTrue("a fresh missing verdict does not seed the fallback", mimirArtWiredBeforeCheck(MissingFile))
        clock += MimirArtAvailability.FreshMs
        assertTrue("not even exactly at the freshness bound", mimirArtWiredBeforeCheck(MissingFile))
        clock += 1
        assertTrue("and not past it", mimirArtWiredBeforeCheck(MissingFile))
        assertFalse("a missing verdict is never trusted as an answer either", MimirArtAvailability.peekExists(MissingFile))
    }

    @Test
    fun `a mount over a fresh MISSING verdict starts wired and always stats`() {
        fakeClock()
        val gate = GateDispatcher()
        MimirArtAvailability.dispatcher = gate
        MimirArtAvailability.record(MissingFile, exists = false)
        val seen = mutableListOf<Boolean>()
        compose.setContent { seen += rememberMimirArtWired(MissingFile) }
        compose.waitForIdle()
        assertEquals("a cached missing never seeds: the first frame is pending, so wired", true, seen.first())
        assertEquals("and it asked for a stat of its own", 1, gate.dispatched)
        gate.release()
        compose.waitForIdle()
        assertEquals("the stat found it still gone", false, seen.last())
    }

    @Test
    fun `art restored at a recently missing path loads within the freshness window and stays wired`() {
        val restored = File.createTempFile("mimir-art-restored", ".png")
        try {
            val path = restored.path
            fakeClock()
            MimirArtAvailability.record(path, exists = false) // it was missing a moment ago ...
            val seen = mutableListOf<Boolean>()
            compose.setContent { seen += rememberMimirArtWired(restored) } // ... and the file is back, inside the TTL
            compose.waitForIdle()
            assertEquals("the first frame is wired", true, seen.first())
            // Real IO dispatcher here: wait for the stat to land.
            compose.waitUntil(5_000) { MimirArtAvailability.peekExists(path) }
            compose.waitForIdle()
            assertEquals("the restored file stays wired, never the fallback", true, seen.last())
            assertFalse("no frame of the mount was the fallback", seen.contains(false))
        } finally {
            restored.delete()
        }
    }

    @Test
    fun `a remount with a fresh EXISTS verdict reuses it with no new stat`() {
        fakeClock()
        val gate = GateDispatcher()
        MimirArtAvailability.dispatcher = gate
        MimirArtAvailability.record(MissingFile, exists = true)
        val seen = mutableListOf<Boolean>()
        compose.setContent { seen += rememberMimirArtWired(MissingFile) }
        compose.waitForIdle()
        assertEquals("a fresh exists is wired", true, seen.first())
        assertEquals("and it asked for no stat", 0, gate.dispatched)
    }

    @Test
    fun `a remount with a stale EXISTS verdict starts wired and stats again`() {
        fakeClock()
        val gate = GateDispatcher()
        MimirArtAvailability.dispatcher = gate
        MimirArtAvailability.record(MissingFile, exists = true)
        clock += MimirArtAvailability.FreshMs + 1
        val seen = mutableListOf<Boolean>()
        compose.setContent { seen += rememberMimirArtWired(MissingFile) }
        compose.waitForIdle()
        assertEquals("a stale verdict starts wired", true, seen.first())
        assertEquals("and stats once", 1, gate.dispatched)
    }

    @Test
    fun `resolve trusts a fresh exists without a stat, and stats past the bound or over a remembered missing`() = runBlocking {
        fakeClock()
        MimirArtAvailability.record("/data/pick.png", exists = true)
        val answer = MimirArtAvailability.resolve("/data/pick.png") { error("a fresh exists needs no stat") }
        assertTrue(answer)
        clock += MimirArtAvailability.FreshMs + 1
        var statted = 0
        val renewed = MimirArtAvailability.resolve("/data/pick.png") { statted += 1; false }
        assertFalse("past the bound it stats and follows the new answer", renewed)
        assertEquals(1, statted)
        // The path is restored a moment later, well inside the window of the 'missing' just recorded.
        clock += 1
        val restored = MimirArtAvailability.resolve("/data/pick.png") { statted += 1; true }
        assertTrue("a remembered missing is never reused: it stats again and sees the restored file", restored)
        assertEquals(2, statted)
    }

    @Test
    fun `the newer stat wins whichever finishes last`() {
        fakeClock()
        val path = "/data/race.png"
        // Two stats started, in this order. The later one answered 'missing' and landed first; the earlier answers late.
        val older = MimirArtAvailability.nextTicket()
        val newer = MimirArtAvailability.nextTicket()
        assertTrue("a ticket is strictly greater than the one before it", newer > older)
        assertFalse(MimirArtAvailability.record(path, exists = false, ticket = newer))
        assertFalse("the older 'exists' arrives late and does not overwrite", MimirArtAvailability.record(path, exists = true, ticket = older))
        assertFalse(MimirArtAvailability.peekExists(path))
        // And the other way round: a late older 'missing' never erases a newer 'exists'.
        val other = "/data/race-2.png"
        val olderOther = MimirArtAvailability.nextTicket()
        val newerOther = MimirArtAvailability.nextTicket()
        assertTrue(MimirArtAvailability.record(other, exists = true, ticket = newerOther))
        assertTrue(MimirArtAvailability.record(other, exists = false, ticket = olderOther))
        assertTrue(MimirArtAvailability.peekExists(other))
    }

    @Test
    fun `two stats that start in the same millisecond are still ordered`() {
        fakeClock() // the clock never moves: every stamp below is the same millisecond
        val path = "/data/same-ms.png"
        val first = MimirArtAvailability.nextTicket()
        val second = MimirArtAvailability.nextTicket()
        // The second stat (a deletion) lands first; the first stat (the file still there) lands late with an EQUAL timestamp.
        assertFalse(MimirArtAvailability.record(path, exists = false, ticket = second, startedAtMs = clock))
        assertFalse(
            "an equal millisecond is not a tie the older answer wins",
            MimirArtAvailability.record(path, exists = true, ticket = first, startedAtMs = clock),
        )
        assertFalse(MimirArtAvailability.peekExists(path))
        // And in the other order the newer 'exists' stands over the late older 'missing'.
        val restored = "/data/same-ms-2.png"
        val a = MimirArtAvailability.nextTicket()
        val b = MimirArtAvailability.nextTicket()
        assertTrue(MimirArtAvailability.record(restored, exists = true, ticket = b, startedAtMs = clock))
        assertTrue(MimirArtAvailability.record(restored, exists = false, ticket = a, startedAtMs = clock))
        assertTrue(MimirArtAvailability.peekExists(restored))
    }

    @Test
    fun `a capacity clear cannot resurrect an older verdict over a newer one it forgot`() {
        fakeClock()
        val path = "/data/cleared.png"
        val older = MimirArtAvailability.nextTicket() // the stat that finds the file still there ...
        val newer = MimirArtAvailability.nextTicket() // ... and the later one that finds it gone
        assertFalse(MimirArtAvailability.record(path, exists = false, ticket = newer))
        // The cache fills with fresh verdicts and restarts, which forgets the newer 'missing' along with everything else.
        repeat(MimirArtAvailability.Capacity - 1) { MimirArtAvailability.record("/fill/$it.png", exists = true) }
        assertEquals(MimirArtAvailability.Capacity, MimirArtAvailability.size())
        MimirArtAvailability.record("/trigger.png", exists = true)
        assertEquals("the restart left only the verdict that caused it", 1, MimirArtAvailability.size())
        // The older stat now lands. Nothing newer is remembered for the path any more, but it must not stand as the answer.
        MimirArtAvailability.record(path, exists = true, ticket = older)
        assertFalse("the older verdict is not resurrected as a fresh 'exists'", MimirArtAvailability.peekExists(path))
        assertEquals("and is not remembered at all", 1, MimirArtAvailability.size())
        // A stat that started AFTER the clear is newer than everything the clear took: it is remembered as usual.
        MimirArtAvailability.record(path, exists = true)
        assertTrue(MimirArtAvailability.peekExists(path))
        assertEquals(2, MimirArtAvailability.size())
    }

    @Test
    fun `a stat that lands after a clear answers its caller but leaves the cache cold`() = runBlocking {
        fakeClock()
        val path = "/data/in-flight.png"
        val answer = MimirArtAvailability.resolve(path) {
            // While this stat is in flight, a newer verdict for ANOTHER path fills the cache, which restarts it.
            repeat(MimirArtAvailability.Capacity) { MimirArtAvailability.record("/fill/$it.png", exists = true) }
            MimirArtAvailability.record("/trigger.png", exists = true)
            true
        }
        assertTrue("the caller still gets the answer its own stat found", answer)
        assertFalse("but a stat that began before the clear is not trusted afterwards", MimirArtAvailability.peekExists(path))
    }

    @Test
    fun `resolve reports the standing verdict when a newer stat overtook it`() = runBlocking {
        val now = java.util.concurrent.atomic.AtomicLong(1_000_000L)
        MimirArtAvailability.nowMs = { now.get() }
        val answer = MimirArtAvailability.resolve("/data/overtaken.png") {
            // While this (older) stat is in flight, a newer one starts and lands: 'missing'.
            now.set(1_000_200L)
            MimirArtAvailability.record("/data/overtaken.png", exists = false, startedAtMs = now.get())
            true
        }
        assertFalse("the older answer lost: the newer verdict stands and is what the mount sees", answer)
    }

    @Test
    fun `the cache is bounded, dropping expired verdicts first`() {
        fakeClock()
        repeat(MimirArtAvailability.Capacity) { MimirArtAvailability.record("/old/$it.png", exists = true) }
        assertEquals(MimirArtAvailability.Capacity, MimirArtAvailability.size())
        clock += MimirArtAvailability.FreshMs + 1
        MimirArtAvailability.record("/new/a.png", exists = true)
        assertEquals("every expired verdict was dropped to make room", 1, MimirArtAvailability.size())
        // All fresh and full: the cache restarts rather than growing.
        repeat(MimirArtAvailability.Capacity) { MimirArtAvailability.record("/fresh/$it.png", exists = true) }
        assertTrue(MimirArtAvailability.size() <= MimirArtAvailability.Capacity)
    }

    @Test
    fun `a verdict handed down is used with no resolver of its own`() {
        val gate = GateDispatcher()
        MimirArtAvailability.dispatcher = gate
        val seen = mutableListOf<Boolean>()
        val inherited = MimirArtVerdict(MissingFile, wired = true)
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalMimirArtVerdict provides inherited) {
                seen += rememberMimirArtWiredOrInherited(MissingFile)
            }
        }
        compose.waitForIdle()
        assertEquals(listOf(true), seen.take(1))
        assertEquals("the outer verdict is the answer: no stat here", 0, gate.dispatched)
    }

    @Test
    fun `a model the handed-down verdict does not cover still resolves itself`() {
        val gate = GateDispatcher()
        MimirArtAvailability.dispatcher = gate
        val inherited = MimirArtVerdict(MissingFile, wired = true)
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalMimirArtVerdict provides inherited) {
                rememberMimirArtWiredOrInherited("/some/other/model.png")
            }
        }
        compose.waitForIdle()
        assertEquals("a different model is not covered and resolves itself", 1, gate.dispatched)
    }

    private companion object {
        const val MissingFile = "/no/such/dir/custom-cover-art/removed-pick.png"
    }
}
