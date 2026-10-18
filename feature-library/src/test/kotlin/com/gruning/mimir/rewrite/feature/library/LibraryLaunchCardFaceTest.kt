package com.gruning.mimir.rewrite.feature.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.model.library.ShelfFaceShape
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Audit 2026-10-14, RC-16 (L-20): "the launch screen's first frame is 49.9 ms". The card's face (the cover, cartridge and wired-face stores, about 8 to 15 file stats) was
 * resolved inside a `remember` on its FIRST frame, on the composition thread. [rememberLibraryLaunchCardFace] resolves it on `Dispatchers.IO` when the card opens, so the
 * first frame composes without it and the face lands a frame or two later, still behind the card's lead.
 *
 * Behaviour, not text: the resolver is held on a latch while the composition goes on without it, and it is shown to run on a thread that is not the composition's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LibraryLaunchCardFaceTest {

    @get:Rule
    val compose = createComposeRule()

    private fun game(id: String) = GameRow(
        gameId = GameId(id),
        title = id,
        platformId = PlatformId.PS1,
        memberCount = 1,
        duplicateKind = null,
        isFavorite = false,
    )

    private val faceA = MimirShelfFace("file:///a.png", ShelfFaceShape.LEDGE_COVER)
    private val faceB = MimirShelfFace("file:///b.png", ShelfFaceShape.PHYSICAL)

    private var activeGame by mutableStateOf(game("one"))
    private var epoch by mutableStateOf(0)
    private var resolver: (GameRow) -> MimirShelfFace? = { null }
    private var face: androidx.compose.runtime.State<MimirShelfFace?>? = null
    private var composedThread: Thread? = null

    private fun host() {
        compose.setContent {
            composedThread = Thread.currentThread()
            // The host re-keys the resolver on every media apply: a lambda that closes over the epoch is a new lambda each time it changes.
            val e = epoch
            val resolve: (GameRow) -> MimirShelfFace? = remember(e) { { row -> resolver(row) } }
            face = rememberLibraryLaunchCardFace(game = activeGame, target = activeGame.gameId, resolve = resolve)
        }
    }

    private fun waitFor(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        compose.waitUntil(timeoutMs) { condition() }
    }

    @Test
    fun `the first frame composes without the resolver, which runs off the composition thread`() {
        val release = CountDownLatch(1)
        val ranOn = AtomicReference<Thread>()
        resolver = { _ ->
            ranOn.set(Thread.currentThread())
            release.await(5, TimeUnit.SECONDS)
            faceA
        }
        host()
        compose.waitForIdle()
        assertNull("the card composed with no face while the resolver is still held (a main-thread resolve would have blocked here)", face!!.value)
        waitFor { ranOn.get() != null }
        assertNotEquals("the resolver ran on a thread of its own", composedThread, ranOn.get())
        assertNull(face!!.value)
        release.countDown()
        waitFor { face!!.value != null }
        assertEquals(faceA, face!!.value)
    }

    @Test
    fun `a new answer for the same game replaces the face when it lands, and the old face stays until then`() {
        resolver = { faceA }
        host()
        waitFor { face!!.value == faceA }
        val release = CountDownLatch(1)
        resolver = { _ ->
            release.await(5, TimeUnit.SECONDS)
            faceB
        }
        Snapshot.withMutableSnapshot { epoch += 1 }
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
        assertEquals("a media apply never blanks the card: the last face holds until the new one lands", faceA, face!!.value)
        release.countDown()
        waitFor { face!!.value == faceB }
    }

    @Test
    fun `a different game never wears the last game's face`() {
        resolver = { row -> if (row.gameId.value == "one") faceA else null }
        host()
        waitFor { face!!.value == faceA }
        val release = CountDownLatch(1)
        resolver = { row ->
            release.await(5, TimeUnit.SECONDS)
            if (row.gameId.value == "two") faceB else null
        }
        Snapshot.withMutableSnapshot { activeGame = game("two") }
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
        assertNull("the new game starts from no face", face!!.value)
        release.countDown()
        waitFor { face!!.value == faceB }
    }

    @Test
    fun `an answer of none clears the face, and a resolver that fails leaves it as it was`() {
        resolver = { faceA }
        host()
        waitFor { face!!.value == faceA }
        resolver = { throw IllegalStateException("the store is busy") }
        Snapshot.withMutableSnapshot { epoch += 1 }
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
        Thread.sleep(150)
        compose.waitForIdle()
        assertEquals("a failing resolver keeps the face", faceA, face!!.value)
        resolver = { null }
        Snapshot.withMutableSnapshot { epoch += 1 }
        Snapshot.sendApplyNotifications()
        waitFor { face!!.value == null }
        assertNull(face!!.value)
    }

    @Test
    fun `a card that opens again for a game it showed starts from that face, so nothing pops in`() {
        resolver = { faceA }
        host()
        waitFor { face!!.value == faceA }
        resolver = { null }
        Snapshot.withMutableSnapshot { activeGame = game("two") }
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
        assertNull("another game has no face of its own yet", face!!.value)
        val release = CountDownLatch(1)
        resolver = { _ ->
            release.await(5, TimeUnit.SECONDS)
            faceA
        }
        Snapshot.withMutableSnapshot { activeGame = game("one") }
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
        assertEquals("the reopened card wears the face it had while the resolver is still out", faceA, face!!.value)
        release.countDown()
        compose.waitForIdle()
        assertEquals(faceA, face!!.value)
    }

    @Test
    fun `the face memory is recency-bounded, remembers a none, and is read without being disturbed`() {
        val memory = LibraryLaunchCardFaceMemory(capacity = 3)
        val ids = (1..4).map { GameId("g$it") }
        memory.remember(ids[0], faceA)
        memory.remember(ids[1], faceB)
        memory.remember(ids[2], null)
        assertEquals(faceA, memory.recall(ids[0]))
        memory.remember(ids[3], faceA)
        assertNull("the least recently used game fell out", memory.recall(ids[1]))
        assertEquals("a recalled game stayed", faceA, memory.recall(ids[0]))
        assertNull("a game whose pick was reset is remembered as none", memory.recall(ids[2]))
    }

    // ------------------------------------------------------------------------------------------------ the call site says it

    private fun screenCode(): String = listOf(
        File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/LibraryScreen.kt"),
        File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/LibraryScreen.kt"),
    ).first(File::isFile).readText()

    @Test
    fun `the Library door resolves the launch card face off the main thread, not in a remember on its first frame`() {
        val screen = screenCode()
        assertTrue(screen.contains("rememberLibraryLaunchCardFace("))
        assertTrue(screen.contains("resolve = cartridgeArtUriForGame"))
        assertTrue("the screen holds the last-face memory and hands it to every card", screen.contains("val launchCardFaces = remember { LibraryLaunchCardFaceMemory() }") && screen.contains("memory = launchCardFaces,"))
        assertFalse("the first-frame resolve is gone", screen.contains("val launchCardFace = remember("))
        assertTrue(screen.contains("cartridgeArtIsPhysical = launchCardFaceIsPhysical(launchCardFace?.physical),"))
    }
}
