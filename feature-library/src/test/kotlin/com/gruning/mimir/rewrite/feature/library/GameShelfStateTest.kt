package com.gruning.mimir.rewrite.feature.library

import android.app.Application
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.model.library.ShelfFace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What starts [rememberGameShelfState]'s strip afresh. The strip's window, the finger's mover and the painted position are
 * remembered per surface epoch; a surface that pages between states of equal length without a new epoch (the Library's L2/R2
 * between platforms) hands a new [windowMemoryKey] instead, and each of them must follow it. Home's key is a function of its epoch
 * and its label, so on Home nothing here changes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w900dp-h520dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GameShelfStateTest {

    @get:Rule
    val compose = createComposeRule()

    private val geometry = solveHomeShelfGeometry(897f, 505f)
    // The Library's own memory: it pages between platforms, so it remembers more than one state.
    private val memory = GameShelfWindowMemory(capacity = LibraryGameShelfWindowCapacity)
    private val items = List(12) { index ->
        GameShelfItem.Game(FocusKey("state/game-$index"), "Game $index", ShelfFace.None)
    }

    private val memoryKey = mutableStateOf<Any>("platform-a")

    /** The selected game, as the screen's focus gives it; null leaves the window where the memory seeded it. */
    private val selected = mutableStateOf<FocusKey?>(null)
    private val epoch = mutableStateOf(0)

    /** A state the strip does not read: writing it recomposes the host and changes neither key. */
    private val unrelated = mutableStateOf(0)

    private var latest: GameShelfState? = null

    private fun mount(): GameShelfState {
        // Platform A was left with its window at 2 (the memory holds one state at a time, the surface's last).
        memory.record("platform-a", 2)
        compose.setContent {
            check(unrelated.value >= 0)
            // No selection unless a test makes one: the window stays where the memory seeded it, so what is read back is the seed.
            val state = rememberGameShelfState(
                items = items,
                selectedKey = selected.value,
                geometry = geometry,
                windowMemory = memory,
                windowMemoryKey = memoryKey.value,
                surfaceEpoch = epoch.value,
                reducedMotion = false,
            )
            SideEffect { latest = state }
        }
        compose.waitForIdle()
        return checkNotNull(latest)
    }

    private fun write(change: () -> Unit) {
        Snapshot.withMutableSnapshot(change)
        compose.waitForIdle()
    }

    @Test
    fun `a new window memory key re-seeds the window, the finger's mover and the painted position`() {
        val before = mount()
        assertEquals("platform A opens where it was left", 2, before.windowStart)
        assertEquals(2f, before.windowPos.value, 0f)

        // The user pages to platform B, of equal length, which this surface has not shown yet, and moves to its seventh game. Nothing is
        // recorded by hand: the shelf records itself, as it does in production.
        write {
            memoryKey.value = "platform-b"
            selected.value = items[6].focusKey
        }

        val after = checkNotNull(latest)
        assertEquals("the shelf recorded platform B's window itself", after.windowStart, memory.seed("platform-b"))
        assertTrue("platform B's window moved to hold its focus (${after.windowStart})", after.windowStart > 2)
        assertEquals("and the painted position starts at the window, not gliding from A's", after.windowStart.toFloat(), after.windowPos.value, 0.5f)
        assertNotSame(before.windowStartHolder, after.windowStartHolder)
        assertNotSame(before.windowPos, after.windowPos)
        assertNotSame("a finger that held A's strip does not hold B's", before.touch, after.touch)
    }

    /**
     * Platform-switch memory on the Library's platform pager: PS1, N64, PS1 (L2/R2) must put PS1 back in the seat it was left in, and N64 too
     * on the way round. The switch is the production one: a new key, recorded by the shelf's own side effect.
     */
    @Test
    fun `paging away from a platform and back puts its window where it was left`() {
        val a = mount()
        assertEquals(2, a.windowStart)
        write { selected.value = items[9].focusKey }
        val aMoved = checkNotNull(latest).windowStart
        assertTrue("A's window moved on with its focus ($aMoved)", aMoved > 2)

        write {
            memoryKey.value = "platform-b"
            selected.value = items[5].focusKey
        }
        val bStart = checkNotNull(latest).windowStart

        write {
            memoryKey.value = "platform-a"
            selected.value = null
        }
        assertEquals("back on A the window is where A was left, not at 0", aMoved, checkNotNull(latest).windowStart)
        assertEquals(aMoved.toFloat(), checkNotNull(latest).windowPos.value, 0f)

        write {
            memoryKey.value = "platform-b"
            selected.value = null
        }
        assertEquals("and B is where B was left", bStart, checkNotNull(latest).windowStart)
    }

    @Test
    fun `an unchanged key keeps the strip across a recomposition`() {
        val before = mount()

        write { unrelated.value += 1 }

        val after = checkNotNull(latest)
        assertSame(before.windowStartHolder, after.windowStartHolder)
        assertSame(before.windowPos, after.windowPos)
        assertSame(before.touch, after.touch)
    }

    @Test
    fun `a new epoch still starts the strip afresh, as it always did`() {
        val before = mount()

        write { epoch.value += 1 }

        val after = checkNotNull(latest)
        assertNotSame(before.windowPos, after.windowPos)
        assertNotSame(before.touch, after.touch)
        assertNotNull(after.windowStartHolder)
    }

    @Test
    fun `Home's memory key is the same object value for a surface and differs between surfaces`() {
        val key = HomeShelfWindowMemory.key(3, "Editor's Choice")
        assertEquals("the same epoch and label are the same key on every recomposition, so remember(key) holds", key, HomeShelfWindowMemory.key(3, "Editor's Choice"))
        assertEquals(key.hashCode(), HomeShelfWindowMemory.key(3, "Editor's Choice").hashCode())
        assertNotEquals("a new epoch is a new key", key, HomeShelfWindowMemory.key(4, "Editor's Choice"))
        assertNotEquals("a new label is a new key", key, HomeShelfWindowMemory.key(3, "Pinned Tomes"))
    }
}
