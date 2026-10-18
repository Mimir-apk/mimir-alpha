package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.launch.remotedefs.DefinitionsCheck
import com.gruning.mimir.rewrite.core.launch.remotedefs.DefinitionsConsent
import com.gruning.mimir.rewrite.core.launch.remotedefs.DefinitionsInfo
import com.gruning.mimir.rewrite.core.launch.remotedefs.EmulatorDefinitionsApi
import com.gruning.mimir.rewrite.core.launch.remotedefs.RejectCode
import com.gruning.mimir.rewrite.core.model.posture.MimirEmulatorDefinitionsGate
import com.gruning.mimir.rewrite.feature.settings.EmulatorDefinitionsResult
import com.gruning.mimir.rewrite.feature.settings.EmulatorDefinitionsRowState
import com.gruning.mimir.rewrite.feature.settings.line
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings > Emulators > Emulator definitions, the behaviour behind the row: explain first, fetch only on the player's
 * confirming press, one check at a time, the result in plain English, the egress gate armed for exactly the check.
 */
class EmulatorDefinitionsControllerTest {
    private class Consent(override var granted: Boolean) : DefinitionsConsent

    private class FakeApi(var info: DefinitionsInfo, val answer: () -> DefinitionsCheck) : EmulatorDefinitionsApi {
        var checks = 0
        val armedDuringCheck = mutableListOf<Boolean>()
        override fun info(): DefinitionsInfo = info
        override fun check(): DefinitionsCheck {
            checks++
            armedDuringCheck += MimirEmulatorDefinitionsGate.isArmed
            return answer()
        }
    }

    private val builtIn = DefinitionsInfo("2026-10-08", null, null)
    private val states = mutableListOf<EmulatorDefinitionsRowState>()
    private val queued = mutableListOf<() -> Unit>()

    private fun controller(
        api: FakeApi,
        consent: Consent,
        inline: Boolean = true,
    ) = EmulatorDefinitionsController(
        service = api,
        consent = consent,
        publish = { states += it },
        zone = ZoneId.of("UTC"),
        locale = Locale.ENGLISH,
        runInBackground = { work -> if (inline) work() else queued += work },
    )

    @Test
    fun `the first press explains and fetches nothing, and the confirming press remembers it and checks`() {
        val api = FakeApi(builtIn) { DefinitionsCheck.Updated(3) }
        val consent = Consent(false)
        val row = controller(api, consent)
        row.showCurrent()
        assertEquals("Built-in · 8 Oct 2026", states.last().status)
        assertFalse(states.last().explaining)

        row.onPress()
        assertEquals("nothing was fetched", 0, api.checks)
        assertFalse("and nothing was remembered", consent.granted)
        assertTrue(states.last().explaining)
        assertEquals("Download", states.last().action)

        row.onPress()
        assertEquals(1, api.checks)
        assertTrue("the confirmation is remembered", consent.granted)
        val last = states.last()
        assertFalse(last.explaining)
        assertFalse(last.checking)
        assertEquals(EmulatorDefinitionsResult.Updated(3), last.result)
        assertEquals("Updated: 3 emulators", last.result!!.line())
        assertTrue("it showed Checking… on the way", states.any { it.checking && it.action == "Checking…" })
    }

    @Test
    fun `after the confirmation every press checks straight away`() {
        val api = FakeApi(builtIn) { DefinitionsCheck.UpToDate }
        val row = controller(api, Consent(true))

        row.onPress()
        row.onPress()

        assertEquals(2, api.checks)
        assertEquals(EmulatorDefinitionsResult.UpToDate, states.last().result)
        assertFalse("never asked again", states.any { it.explaining })
    }

    @Test
    fun `a press while a check is running does nothing`() {
        val api = FakeApi(builtIn) { DefinitionsCheck.UpToDate }
        val row = controller(api, Consent(true), inline = false)

        row.onPress()
        row.onPress()
        row.onPress()
        assertEquals("one check queued, none started", 1, queued.size)
        assertEquals(0, api.checks)
        assertTrue(states.last().checking)

        queued.single()()
        assertEquals(1, api.checks)
        assertFalse(states.last().checking)

        row.onPress()
        assertEquals("the next press after it ended starts a new one", 2, queued.size)
    }

    @Test
    fun `the egress gate is armed for exactly the check and closed again after it`() {
        val api = FakeApi(builtIn) { DefinitionsCheck.UpToDate }
        val row = controller(api, Consent(true))
        assertFalse(MimirEmulatorDefinitionsGate.isArmed)

        row.onPress()

        assertEquals(listOf(true), api.armedDuringCheck)
        assertFalse(MimirEmulatorDefinitionsGate.isArmed)

        // And a check that blows up still leaves it closed and the row usable.
        val exploding = FakeApi(builtIn) { throw IllegalStateException("boom") }
        val again = controller(exploding, Consent(true))
        again.onPress()
        assertFalse(MimirEmulatorDefinitionsGate.isArmed)
        assertFalse(states.last().checking)
        assertEquals(EmulatorDefinitionsResult.CouldntUpdate(usingFetched = false), states.last().result)
    }

    @Test
    fun `every outcome becomes a plain-English result and a failure never claims an update`() {
        val cases = listOf(
            DefinitionsCheck.UpToDate to "Up to date",
            DefinitionsCheck.Updated(1) to "Updated: 1 emulator",
            DefinitionsCheck.Updated(3) to "Updated: 3 emulators",
            DefinitionsCheck.Failed(null, offline = true, needsAppUpdate = false, usingFetched = false) to "Couldn’t update; using built-in",
            DefinitionsCheck.Failed(RejectCode.PIN_MISMATCH, offline = false, needsAppUpdate = false, usingFetched = false) to "Couldn’t update; using built-in",
            DefinitionsCheck.Failed(RejectCode.HASH_MISMATCH, offline = false, needsAppUpdate = false, usingFetched = true) to "Couldn’t update; keeping your last update",
            DefinitionsCheck.Failed(RejectCode.SCHEMA_NEWER, offline = false, needsAppUpdate = true, usingFetched = false) to "This update needs a newer Mimir. Using built-in.",
            DefinitionsCheck.Failed(RejectCode.NEEDS_NEWER_APP, offline = false, needsAppUpdate = true, usingFetched = false) to "This update needs a newer Mimir. Using built-in.",
        )
        for ((outcome, line) in cases) {
            states.clear()
            val row = controller(FakeApi(builtIn) { outcome }, Consent(true))
            row.onPress()
            val result = states.last().result
            assertEquals(outcome.toString(), line, result!!.line())
        }
        assertNull("a check already running says nothing", DefinitionsCheck.InFlight.toRowResult())
        assertNull(DefinitionsCheck.NeedsConsent.toRowResult())
    }

    @Test
    fun `the status line names the day and the release once an update has been stored`() {
        val noonOn12Oct = LocalDateTime.of(2026, 10, 12, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val stored = DefinitionsInfo("2026-10-08", "defs-2", noonOn12Oct)
        val api = FakeApi(stored) { DefinitionsCheck.UpToDate }

        controller(api, Consent(true)).showCurrent()

        assertEquals("Updated · 12 Oct 2026", states.last().status)
    }

    @Test
    fun `a check that needed consent again asks again instead of fetching`() {
        val api = FakeApi(builtIn) { DefinitionsCheck.NeedsConsent }
        val consent = Consent(true)
        val row = controller(api, consent)

        row.onPress()

        assertFalse("the remembered confirmation is withdrawn", consent.granted)
        assertTrue(states.last().explaining)
    }
}
