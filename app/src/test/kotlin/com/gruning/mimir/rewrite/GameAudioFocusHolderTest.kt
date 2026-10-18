package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.tweaker.TweakerGameAudioFocusResult
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * a game Mimir launches cannot take audio focus. This is the Mimir half: the
 * hold goes in BEFORE the game starts, comes out on return, session end or launch failure, only while the
 * setting is on, and every failure leaves the game with normal focus behaviour and the launch untouched.
 */
class GameAudioFocusHolderTest {
    private val game = "org.libretro.retroarch"

    // ---- sequencing -------------------------------------------------------------------------------

    @Test
    fun `a launch holds before the game and stays bound until the return releases and unbinds`() = runTest {
        val rig = Rig()

        val held = rig.holder.holdForLaunch(game)

        assertEquals(GameAudioFocusHoldOutcome.Held(game), held)
        assertEquals(listOf("mayBind", "bind", "apiVersion", "hold:11:$game"), rig.calls)
        assertEquals(game, rig.holder.mayBeHeld)
        assertFalse("bound while held, so the daemon outlives the launch window", rig.unbound)

        rig.calls.clear()
        rig.holder.releaseAfterReturn("return")

        assertEquals(listOf("mayBind", "bind", "apiVersion", "release:11", "unbind"), rig.calls)
        assertNull(rig.holder.mayBeHeld)
        assertEquals(
            listOf(
                "hold package=$game result=HELD",
                "release package=$game reason=return result=RELEASED",
            ),
            rig.log,
        )
    }

    @Test
    fun `a failed launch releases through the same path`() = runTest {
        val rig = Rig()
        rig.holder.holdForLaunch(game)

        rig.holder.releaseAfterReturn("dispatch failed")

        assertNull(rig.holder.mayBeHeld)
        assertTrue(rig.log.last().contains("reason=dispatch failed result=RELEASED"))
    }

    @Test
    fun `a release with nothing held touches nothing and logs nothing`() = runTest {
        val rig = Rig()

        rig.holder.releaseAfterReturn("return")

        assertTrue(rig.calls.isEmpty())
        assertTrue(rig.log.isEmpty())
    }

    @Test
    fun `a release the daemon cannot confirm is kept and retried on the next return`() = runTest {
        val rig = Rig()
        rig.holder.holdForLaunch(game)
        rig.releaseReply = TweakerGameAudioFocusResult.Retained("RELEASE_NOT_OBSERVED")

        rig.holder.releaseAfterReturn("return")

        assertEquals(game, rig.holder.mayBeHeld)
        assertTrue(rig.unbound)
        assertTrue(rig.log.last().contains("result=RETAINED(RELEASE_NOT_OBSERVED)"))

        rig.releaseReply = TweakerGameAudioFocusResult.Released(game)
        rig.holder.releaseAfterReturn("return")

        assertNull(rig.holder.mayBeHeld)
    }

    @Test
    fun `an unreachable daemon at release keeps the obligation`() = runTest {
        val rig = Rig()
        rig.holder.holdForLaunch(game)
        rig.bindResult = false

        rig.holder.releaseAfterReturn("return")

        assertEquals(game, rig.holder.mayBeHeld)
        assertTrue(rig.log.last().contains("UNAVAILABLE(bind)"))
    }

    // ---- the setting gate -------------------------------------------------------------------------

    @Test
    fun `with the setting off nothing is asked of the Tweaker`() = runTest {
        val rig = Rig(enabled = false)

        val outcome = rig.holder.holdForLaunch(game)

        assertEquals(GameAudioFocusHoldOutcome.SkippedSettingOff, outcome)
        assertTrue(rig.calls.isEmpty())
        assertNull(rig.holder.mayBeHeld)
    }

    @Test
    fun `turning the setting off puts back a hold that is still out`() = runTest {
        val rig = Rig()
        rig.holder.holdForLaunch(game)
        rig.enabled = false

        rig.holder.holdForLaunch("com.example.next")

        assertNull(rig.holder.mayBeHeld)
        assertTrue(rig.log.any { it.contains("reason=setting off result=RELEASED") })
    }

    @Test
    fun `a Mimir package is never held`() = runTest {
        val rig = Rig()

        assertEquals(
            GameAudioFocusHoldOutcome.SkippedOwnPackage,
            rig.holder.holdForLaunch("com.gruning.mimir.rewrite"),
        )
        assertTrue(rig.calls.isEmpty())
    }

    // ---- degradation: music behaves as it always did -----------------------------------------------

    @Test
    fun `an older Tweaker reads as unsupported and is left alone`() = runTest {
        val rig = Rig()
        rig.version = 10

        val outcome = rig.holder.holdForLaunch(game)

        assertEquals(GameAudioFocusHoldOutcome.Unsupported(10), outcome)
        assertFalse(rig.calls.any { it.startsWith("hold") })
        assertTrue(rig.unbound)
        assertNull(rig.holder.mayBeHeld)
    }

    @Test
    fun `no Tweaker, or one that will not bind, does nothing`() = runTest {
        val unpaired = Rig().apply { pairMayBind = false }
        assertTrue(unpaired.holder.holdForLaunch(game) is GameAudioFocusHoldOutcome.Unavailable)
        assertEquals(listOf("mayBind"), unpaired.calls)

        val unbound = Rig().apply { bindResult = false }
        assertTrue(unbound.holder.holdForLaunch(game) is GameAudioFocusHoldOutcome.Unavailable)
        assertNull(unbound.holder.mayBeHeld)
        assertTrue(unbound.unbound)
    }

    @Test
    fun `a clean refusal holds nothing and unbinds`() = runTest {
        val rig = Rig()
        rig.holdReply = TweakerGameAudioFocusResult.Refused("SYSTEM_APP", "system", releaseDebt = false)

        val outcome = rig.holder.holdForLaunch(game)

        assertEquals(GameAudioFocusHoldOutcome.Refused("SYSTEM_APP", false), outcome)
        assertNull(rig.holder.mayBeHeld)
        assertTrue(rig.unbound)
    }

    @Test
    fun `a refusal that left a put-back unconfirmed keeps the obligation`() = runTest {
        val rig = Rig()
        rig.holdReply = TweakerGameAudioFocusResult.Refused("WRITE_NOT_HELD", "readback", releaseDebt = true)

        rig.holder.holdForLaunch(game)

        assertEquals(game, rig.holder.mayBeHeld)
    }

    @Test
    fun `an unanswered hold keeps the obligation and the binding so the return can release it`() = runTest {
        val rig = Rig()
        rig.holdReply = TweakerGameAudioFocusResult.Unavailable("timed out after 3000 ms")

        val outcome = rig.holder.holdForLaunch(game)

        assertTrue(outcome is GameAudioFocusHoldOutcome.Unavailable)
        assertEquals(game, rig.holder.mayBeHeld)
        assertFalse(rig.unbound)
    }

    @Test
    fun `a hold that outlasts its budget is abandoned without costing the launch more than the budget`() = runTest {
        val rig = Rig()
        rig.bindDelayMs = 60_000L

        val outcome = rig.holder.holdForLaunch(game)

        assertEquals(GameAudioFocusHoldOutcome.TimedOut, outcome)
        assertEquals(GameAudioFocusHolder.HOLD_BUDGET_MS, currentTime)
        assertNull(rig.holder.mayBeHeld)
    }

    @Test
    fun `a newer launch replaces the obligation and the daemon puts the earlier game back`() = runTest {
        val rig = Rig()
        rig.holder.holdForLaunch(game)

        rig.holder.holdForLaunch("com.example.next")

        assertEquals("com.example.next", rig.holder.mayBeHeld)
        assertEquals(2, rig.calls.count { it.startsWith("hold:") })
    }

    // ---- wiring pins ------------------------------------------------------------------------------

    @Test
    fun `the bridge holds ahead of every early-out in prepareForLaunch and releases in settleAfterReturn`() {
        val bridge = source("TweakerBridge.kt")
        val prepare = bridge.substringAfter("internal suspend fun prepareForLaunch(")
        val holdAt = prepare.indexOf("holdGameAudioFocusForLaunch(packageName)")
        val firstEarlyOut = prepare.indexOf("mayOwnTransport")
        assertTrue("prepareForLaunch must hold audio focus", holdAt >= 0)
        assertTrue("the hold must come before the owner-settling early-out", holdAt < firstEarlyOut)
        val settle = bridge.substringAfter("suspend fun settleAfterReturn(): TweakerCapabilities? {").substringBefore("returnSettlementLock")
        assertTrue(settle.contains("releaseGameAudioFocusAfterReturn()"))
        assertTrue(
            "the setting is read at each launch, never cached",
            bridge.contains("enabled = { AudioPreferences.keepMusicInGames.value }"),
        )
        assertTrue(
            "the hold has its own session, not the bridge's reservation-owned one",
            bridge.contains("session = TweakerSession(appContext)"),
        )
    }

    // ---- fakes ------------------------------------------------------------------------------------

    private class Rig(enabled: Boolean = true) {
        val calls = mutableListOf<String>()
        val log = mutableListOf<String>()
        var enabled = enabled
        var pairMayBind = true
        var bindResult = true
        var bindDelayMs = 0L
        var version: Int? = 11
        var holdReply: TweakerGameAudioFocusResult? = null
        var releaseReply: TweakerGameAudioFocusResult? = null
        var unbound = true

        val transport = object : GameAudioFocusTransport {
            override fun mayBind(): Boolean {
                calls += "mayBind"
                return pairMayBind
            }

            override suspend fun bind(timeoutMs: Long): Boolean {
                calls += "bind"
                if (bindDelayMs > 0L) delay(bindDelayMs)
                unbound = !bindResult
                return bindResult
            }

            override suspend fun apiVersion(): Int? {
                calls += "apiVersion"
                return version
            }

            override fun hold(apiVersion: Int, packageName: String): TweakerGameAudioFocusResult {
                calls += "hold:$apiVersion:$packageName"
                return holdReply ?: TweakerGameAudioFocusResult.Held(packageName)
            }

            override fun release(apiVersion: Int): TweakerGameAudioFocusResult {
                calls += "release:$apiVersion"
                return releaseReply ?: TweakerGameAudioFocusResult.Released("x")
            }

            override fun unbind() {
                calls += "unbind"
                unbound = true
            }
        }

        val holder = GameAudioFocusHolder(
            transport = transport,
            enabled = { this.enabled },
            log = { log += it },
            io = Dispatchers.Unconfined,
        )
    }

    private fun source(name: String): String = sequenceOf(
        File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
    ).firstOrNull(File::isFile)?.readText() ?: error("source $name not found")
}
