package com.gruning.mimir.tweaker

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The readback end to end through the REAL transport: a genuine `PServerClient` over an injected Binder transport that
 * answers exactly the frame the daemon's wrapper prints (`MIMIR_TX_EXIT:<nonce>:<exit>:<len>:<payload>`), not the bare
 * app-op text. This is the shape the first review found unproven: a parser that reads bare text can never confirm a
 * hold or a release against this, and that is what these tests would have caught.
 */
class GameAudioFocusFramedReadbackTest {
    private val game = "org.libretro.retroarch"
    private val gate = CountDownLatch(1)

    @After
    fun releaseAnythingStuck() = gate.countDown()

    // ---- the transport -------------------------------------------------------------------------

    /** What the fake device holds for the one package, and how the next `get` replies. */
    private class Device {
        var mode = "default"
        var getBody: ((String) -> String)? = null
        var getExit = 0
        var rawGetReply: ((String) -> String?)? = null
        var hang: CountDownLatch? = null
        val seen = mutableListOf<String>()
    }

    private fun transport(device: Device) = PServerBinderTransport { wrapped, _ ->
        device.seen += wrapped
        val nonce = Regex("MIMIR_TX_EXIT:([A-Za-z0-9_-]+):").find(wrapped)!!.groupValues[1]
        val inner = Regex("\\( (cmd appops [^)]*) \\)").find(wrapped)!!.groupValues[1]
        fun frame(exit: Int, body: String) = "MIMIR_TX_EXIT:$nonce:$exit:${body.length}:$body\n"
        when {
            inner.endsWith(" ignore") -> {
                device.mode = "ignore"
                PServerTransactResult(accepted = true, output = frame(0, ""))
            }
            inner.endsWith(" default") -> {
                device.mode = "default"
                PServerTransactResult(accepted = true, output = frame(0, ""))
            }
            else -> {
                device.hang?.await(30, TimeUnit.SECONDS)
                val raw = device.rawGetReply?.invoke(nonce)
                val body = device.getBody?.invoke(device.mode) ?: "TAKE_AUDIO_FOCUS: ${device.mode}"
                PServerTransactResult(accepted = true, output = raw ?: frame(device.getExit, body))
            }
        }
    }

    private class Journal : GameAudioFocusJournal {
        var record: GameAudioFocusRecord? = null
        override fun load(): GameAudioFocusJournalLoad =
            record?.let(GameAudioFocusJournalLoad::Loaded) ?: GameAudioFocusJournalLoad.Empty
        override fun persist(record: GameAudioFocusRecord): Boolean {
            this.record = record
            return true
        }
        override fun clear(): Boolean {
            record = null
            return true
        }
        override fun quarantine(): Boolean = true
    }

    private class Packages : GameAudioFocusPackagePolicy {
        override fun admission(packageName: String): GameAudioFocusAdmission = GameAudioFocusAdmission.Admitted
        override fun isInstalled(packageName: String): Boolean? = true
    }

    private fun controller(device: Device, readDeadlineMs: Long = 2_000L): GameAudioFocusController {
        val client = PServerClient(
            transport = transport(device),
            callBound = PServerCallBound(defaultDeadlineMs = readDeadlineMs),
            readDeadlineMs = readDeadlineMs,
        )
        return GameAudioFocusController(
            journal = Journal(),
            control = PServerGameAudioFocusControlPort(client),
            packages = Packages(),
            bootIdentity = { "boot-1" },
            elapsedRealtimeMs = { 10L },
            sleepMs = {},
        )
    }

    // ---- real framed replies confirm a hold and a release ---------------------------------------

    @Test
    fun `a framed ignore reply confirms the hold and a framed default reply confirms the release`() {
        val device = Device()
        val controller = controller(device)

        assertEquals(GameAudioFocusHoldResult.Held(game), controller.hold(game))
        assertEquals("ignore", device.mode)
        assertEquals(GameAudioFocusReleaseResult.Released(game), controller.release())
        assertEquals("default", device.mode)
        assertNull(controller.heldPackage())
    }

    @Test
    fun `framed allow and no-operations replies read as not ignored, so a release confirms on either`() {
        listOf("TAKE_AUDIO_FOCUS: allow", "TAKE_AUDIO_FOCUS: default; time=+3m ago", "No operations.").forEach { reply ->
            val device = Device()
            val controller = controller(device)
            controller.hold(game)
            device.getBody = { reply }

            assertEquals(reply, GameAudioFocusReleaseResult.Released(game), controller.release())
        }
    }

    @Test
    fun `a framed allow reply to the hold's readback refuses it and rolls back with no debt`() {
        val device = Device()
        device.getBody = { "TAKE_AUDIO_FOCUS: allow" }
        val controller = controller(device)

        val result = controller.hold(game) as GameAudioFocusHoldResult.Refused

        assertEquals(TweakerGameAudioFocusContract.REFUSAL_WRITE_NOT_HELD, result.code)
        assertEquals(false, result.releaseDebt)
        assertEquals("default", device.mode)
        assertNull(controller.heldPackage())
    }

    @Test
    fun `the bare app-op text with no frame around it is never a confirmation`() {
        val device = Device()
        device.rawGetReply = { "TAKE_AUDIO_FOCUS: ignore\n" }
        val controller = controller(device)

        val result = controller.hold(game) as GameAudioFocusHoldResult.Refused

        assertEquals(TweakerGameAudioFocusContract.REFUSAL_WRITE_NOT_HELD, result.code)
        assertTrue("the put-back could not be read back either, so the debt is kept", result.releaseDebt)
    }

    // ---- a malformed frame fails closed ------------------------------------------------------------

    @Test
    fun `malformed frames fail closed on a hold and on a release`() {
        val malformed = listOf<(String) -> String?>(
            { nonce -> "MIMIR_TX_EXIT:$nonce:0:99:TAKE_AUDIO_FOCUS: ignore\n" },          // declared length lies
            { nonce -> "MIMIR_TX_EXIT:$nonce:0:24\n" },                                      // truncated, no payload field
            { _ -> "MIMIR_TX_EXIT:nOtMyNonce1:0:24:TAKE_AUDIO_FOCUS: ignore\n" },           // someone else's nonce
            { nonce -> "junk MIMIR_TX_EXIT:$nonce:0:24:TAKE_AUDIO_FOCUS: ignore\n" },       // not anchored at the start
            { nonce -> "MIMIR_TX_EXIT:$nonce:x:24:TAKE_AUDIO_FOCUS: ignore\n" },            // exit code not a number
            { nonce -> "MIMIR_TX_EXIT:$nonce:1:24:TAKE_AUDIO_FOCUS: ignore\n" },            // nonzero exit
            { _ -> "" },                                                                      // an empty reply: no frame at all
        )
        malformed.forEachIndexed { index, reply ->
            val holdDevice = Device().apply { rawGetReply = reply }
            val held = controller(holdDevice).hold(game)
            assertTrue("hold, malformed #$index", held is GameAudioFocusHoldResult.Refused)

            val releaseDevice = Device()
            val releasing = controller(releaseDevice)
            assertTrue(releasing.hold(game) is GameAudioFocusHoldResult.Held)
            releaseDevice.rawGetReply = reply
            val released = releasing.release()
            assertTrue("release, malformed #$index: $released", released is GameAudioFocusReleaseResult.Retained)
            assertEquals(game, releasing.heldPackage())
        }
    }

    @Test
    fun `the frame decoder hands back exactly the payload and nothing else`() {
        val ok = "MIMIR_TX_EXIT:nAbcd1234:0:24:TAKE_AUDIO_FOCUS: ignore\n"
        assertEquals("TAKE_AUDIO_FOCUS: ignore", PServerClient.decodeCheckedBody(ok, "nAbcd1234"))
        assertEquals("", PServerClient.decodeCheckedBody("MIMIR_TX_EXIT:nAbcd1234:0:0:\n", "nAbcd1234"))
        assertNull(PServerClient.decodeCheckedBody(ok, "nOther1234"))
        assertNull(PServerClient.decodeCheckedBody("TAKE_AUDIO_FOCUS: ignore", "nAbcd1234"))
        assertNull(PServerClient.decodeCheckedBody(null, "nAbcd1234"))
    }

    // ---- multiline payloads (AOSP 13 and 14 print the uid mode and the package mode on separate lines) ----

    @Test
    fun `a uid line plus a package line confirms the hold and the release through the real frame`() {
        val device = Device()
        device.getBody = { mode ->
            if (mode == "ignore") {
                "Uid mode: TAKE_AUDIO_FOCUS: ignore\nTAKE_AUDIO_FOCUS: allow; time=+3m ago"
            } else {
                "Uid mode: TAKE_AUDIO_FOCUS: allow\nTAKE_AUDIO_FOCUS: default; time=+3m ago"
            }
        }
        val controller = controller(device)

        assertEquals(GameAudioFocusHoldResult.Held(game), controller.hold(game))
        assertEquals(GameAudioFocusReleaseResult.Released(game), controller.release())
        assertNull(controller.heldPackage())
    }

    @Test
    fun `no operations plus the default mode confirms a release and refuses a hold, through the real frame`() {
        val device = Device()
        device.getBody = { mode -> if (mode == "ignore") "TAKE_AUDIO_FOCUS: ignore" else "No operations.\nDefault mode: allow" }
        val controller = controller(device)
        assertEquals(GameAudioFocusHoldResult.Held(game), controller.hold(game))
        assertEquals(GameAudioFocusReleaseResult.Released(game), controller.release())

        // The same reply to a hold's readback means the op did not change: refused, rolled back, no debt.
        val stuck = Device().apply { getBody = { "No operations.\nDefault mode: allow" } }
        val refused = controller(stuck).hold(game) as GameAudioFocusHoldResult.Refused
        assertEquals(TweakerGameAudioFocusContract.REFUSAL_WRITE_NOT_HELD, refused.code)
        assertEquals(false, refused.releaseDebt)
    }

    @Test
    fun `a multiline payload still needs the exact declared length, the anchored nonce and a zero exit`() {
        val nonce = "nAbcd1234"
        val body = "Uid mode: TAKE_AUDIO_FOCUS: ignore\nTAKE_AUDIO_FOCUS: allow"
        val good = "MIMIR_TX_EXIT:$nonce:0:${body.length}:$body\n"
        assertEquals(body, PServerClient.decodeCheckedBody(good, nonce, allowLineFeedsInPayload = true))
        // Other callers keep the strict single-line rule: a line feed is not payload for them.
        assertNull(PServerClient.decodeCheckedBody(good, nonce))
        assertNull("length lies", PServerClient.decodeCheckedBody("MIMIR_TX_EXIT:$nonce:0:${body.length + 1}:$body\n", nonce, true))
        assertNull("foreign nonce", PServerClient.decodeCheckedBody(good, "nOther1234", true))
        assertNull("nonzero exit", PServerClient.decodeCheckedBody("MIMIR_TX_EXIT:$nonce:1:${body.length}:$body\n", nonce, true))
        assertNull("not anchored", PServerClient.decodeCheckedBody("x" + good, nonce, true))
        // A line feed is allowed in the payload only, never in the header fields.
        assertNull(PServerClient.decodeCheckedBody("MIMIR_TX_EXIT:$nonce:0\n:${body.length}:$body\n", nonce, true))
        assertNull(PServerClient.decodeCheckedBody("MIMIR_TX_EXIT:$nonce:0:${body.length}\n:$body\n", nonce, true))
        // Other control characters are still malformed.
        assertNull(PServerClient.decodeCheckedBody("MIMIR_TX_EXIT:$nonce:0:3:a\u0001b\n", nonce, true))
    }

    @Test
    fun `an unknown multiline shape fails closed on a hold and on a release`() {
        listOf(
            "TAKE_AUDIO_FOCUS: ignore\nPermission Denial: not allowed",
            "No operations.\nDefault mode: ignore",
            "Uid mode: TAKE_AUDIO_FOCUS: allow\nTAKE_AUDIO_FOCUS: ignore",
        ).forEach { reply ->
            val holdDevice = Device().apply { getBody = { reply } }
            assertTrue(reply, controller(holdDevice).hold(game) is GameAudioFocusHoldResult.Refused)

            val releaseDevice = Device()
            val controller = controller(releaseDevice)
            assertTrue(controller.hold(game) is GameAudioFocusHoldResult.Held)
            releaseDevice.getBody = { reply }
            assertTrue(reply, controller.release() is GameAudioFocusReleaseResult.Retained)
            assertEquals(game, controller.heldPackage())
        }
    }

    // ---- a hung readback is bounded ----------------------------------------------------------------

    @Test
    fun `a hung readback lapses at the deadline and is SERVICE_BUSY, not a worker held forever`() {
        val device = Device()
        val controller = controller(device, readDeadlineMs = 150L)
        assertTrue(controller.hold(game) is GameAudioFocusHoldResult.Held)
        device.hang = gate

        val started = System.nanoTime()
        val query = controller.query()
        val release = controller.release() as GameAudioFocusReleaseResult.Retained
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        assertEquals(GameAudioFocusQueryResult.Unavailable(TweakerGameAudioFocusContract.REFUSAL_SERVICE_BUSY), query)
        assertEquals(TweakerGameAudioFocusContract.REFUSAL_SERVICE_BUSY, release.code)
        assertEquals("the journal still names it", game, controller.heldPackage())
        assertTrue("two bounded reads returned in ${elapsedMs}ms, not after the 30 s hang", elapsedMs < 5_000)
    }

    @Test
    fun `a hold whose readback hangs is refused as SERVICE_BUSY with the put-back debt kept`() {
        val device = Device().apply { hang = gate }
        val controller = controller(device, readDeadlineMs = 150L)

        val result = controller.hold(game) as GameAudioFocusHoldResult.Refused

        assertEquals(TweakerGameAudioFocusContract.REFUSAL_SERVICE_BUSY, result.code)
        assertTrue(result.releaseDebt)
    }

    // ---- the query reports the readback, end to end -------------------------------------------------

    @Test
    fun `the query reads the op back through a framed reply`() {
        val device = Device()
        val controller = controller(device)
        assertEquals(GameAudioFocusQueryResult.NotHeld, controller.query())
        controller.hold(game)
        assertEquals(GameAudioFocusQueryResult.Held(game), controller.query())
        device.mode = "default"
        assertEquals(GameAudioFocusQueryResult.NotHeld, controller.query())
    }
}
