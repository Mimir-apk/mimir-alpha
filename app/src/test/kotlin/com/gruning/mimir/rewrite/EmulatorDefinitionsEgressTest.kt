package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.launch.EmulatorSeedRegistry
import com.gruning.mimir.rewrite.core.launch.remotedefs.ActiveDefinitions
import com.gruning.mimir.rewrite.core.launch.remotedefs.DefinitionsCheck
import com.gruning.mimir.rewrite.core.launch.remotedefs.DefinitionsConsent
import com.gruning.mimir.rewrite.core.launch.remotedefs.DefinitionsMerge
import com.gruning.mimir.rewrite.core.launch.remotedefs.EffectiveDefinitionsHolder
import com.gruning.mimir.rewrite.core.launch.remotedefs.EmulatorDefinitionsService
import com.gruning.mimir.rewrite.core.launch.remotedefs.RemoteDefinitionsEndpoint
import com.gruning.mimir.rewrite.core.model.posture.MimirConnectedArtGate
import com.gruning.mimir.rewrite.core.model.posture.MimirEmulatorDefinitionsGate
import com.gruning.mimir.rewrite.core.model.posture.MimirSteamGridDbGate
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The definitions fetch has its own egress route: it admits one host and two kinds of path, and only while the
 * player's press is running. These tests pin the route, its gate, the production adapter over it, and the shipped
 * files `tools/emulator-definitions/` going through the whole stack.
 */
class EmulatorDefinitionsEgressTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class Recorded(val url: String, val headers: Map<String, String>)

    private val calls = mutableListOf<Recorded>()
    private val route = MimirProviderEgressRoute.EMULATOR_DEFINITIONS
    private val pointerUrl = RemoteDefinitionsEndpoint.POINTER_URL
    private val tagUrl = RemoteDefinitionsEndpoint.urlFor("defs-1")!!

    @After
    fun reset() {
        MimirSteamGridDbGate.setOpen(false)
        MimirConnectedArtGate.setOpen(false)
        calls.clear()
    }

    private class FakeResponse(
        override val statusCode: Int,
        private val body: ByteArray,
    ) : MimirProviderEgressResponse {
        override fun header(name: String): String? = null
        override val declaredLength: Long get() = body.size.toLong()
        override fun body(): InputStream = ByteArrayInputStream(body)
        override fun close() = Unit
    }

    private fun recorder(answer: (String) -> MimirProviderEgressResponse? = { FakeResponse(200, ByteArray(0)) }) =
        MimirProviderEgressOpener { url, _, _, headers ->
            calls += Recorded(url, headers)
            answer(url)
        }

    private fun open(url: String, opener: MimirProviderEgressOpener) =
        MimirProviderEgress.open(route, url, 1_000, 1_000, emptyMap(), opener)

    @Test
    fun `the route is closed unless the player's own press is running`() {
        val opener = recorder()

        assertNull(open(pointerUrl, opener))
        assertNull(open(tagUrl, opener))
        assertEquals("no open, however exact the url", 0, calls.size)
        assertFalse(MimirEmulatorDefinitionsGate.isArmed)

        MimirEmulatorDefinitionsGate.whileUserStartedRefresh {
            assertTrue(MimirEmulatorDefinitionsGate.isArmed)
            assertNotNull(open(pointerUrl, opener))
            assertNotNull(open(tagUrl, opener))
        }
        assertEquals(listOf(pointerUrl, tagUrl), calls.map { it.url })
        assertFalse("closed again afterwards", MimirEmulatorDefinitionsGate.isArmed)
        assertNull(open(pointerUrl, opener))
        assertEquals(2, calls.size)
    }

    @Test
    fun `even armed the route admits only the pinned repository's pointer and tagged release`() {
        val opener = recorder()
        val base = RemoteDefinitionsEndpoint.BASE_URL
        val refused = listOf(
            "$base/main/definitions.json",
            "$base/develop/pointer.json",
            "$base/defs-1/pointer.json",
            "$base/defs-1/definitions.json?x=1",
            "http://raw.githubusercontent.com/Mimir-apk/mimir-emulator-definitions/main/pointer.json",
            "https://raw.githubusercontent.com/someone/else/main/pointer.json",
            "https://evil.example/Mimir-apk/mimir-emulator-definitions/main/pointer.json",
            "https://cdn.steamgriddb.com/icon.png",
            "https://www.steamgriddb.com/api/v2/search/autocomplete/Mimir",
            "https://api.screenscraper.fr/api2/x",
        )

        MimirEmulatorDefinitionsGate.whileUserStartedRefresh {
            for (url in refused) assertNull(url, open(url, opener))
        }

        assertEquals(0, calls.size)
    }

    @Test
    fun `the other providers' gates never open this route and this gate never opens theirs`() {
        val opener = recorder()
        MimirSteamGridDbGate.setOpen(true)
        MimirConnectedArtGate.setOpen(true)

        assertNull("an open art gate is not an opt-in for definitions", open(pointerUrl, opener))
        assertEquals(0, calls.size)

        MimirSteamGridDbGate.setOpen(false)
        MimirConnectedArtGate.setOpen(false)
        MimirEmulatorDefinitionsGate.whileUserStartedRefresh {
            assertNull(
                MimirProviderEgress.open(MimirProviderEgressRoute.STEAMGRIDDB_API, "https://www.steamgriddb.com/api/v2/grids/game/7", 1_000, 1_000, emptyMap(), opener),
            )
            assertNull(
                MimirProviderEgress.open(MimirProviderEgressRoute.SCREENSCRAPER_MEDIA, "https://api.screenscraper.fr/x", 1_000, 1_000, emptyMap(), opener),
            )
        }
        assertEquals(0, calls.size)
    }

    @Test
    fun `the gate allows exactly one host and closes even when the refresh throws`() {
        assertTrue(MimirEmulatorDefinitionsGate.isAllowedHost("raw.githubusercontent.com"))
        for (host in listOf("evil.raw.githubusercontent.com", "raw.githubusercontent.com.evil.example", "github.com", "githubusercontent.com", "", null)) {
            assertFalse("$host", MimirEmulatorDefinitionsGate.isAllowedHost(host))
        }
        assertFalse("armed is not enough on its own", MimirEmulatorDefinitionsGate.allowsRequestTo("raw.githubusercontent.com"))
        runCatching { MimirEmulatorDefinitionsGate.whileUserStartedRefresh { error("the refresh failed") } }
        assertFalse(MimirEmulatorDefinitionsGate.isArmed)
        MimirEmulatorDefinitionsGate.whileUserStartedRefresh {
            MimirEmulatorDefinitionsGate.whileUserStartedRefresh { assertTrue(MimirEmulatorDefinitionsGate.isArmed) }
            assertTrue("an inner refresh ending does not close the outer one", MimirEmulatorDefinitionsGate.isArmed)
        }
        assertFalse(MimirEmulatorDefinitionsGate.isArmed)
    }

    // ------------------------------------------------------------------------------------------------------------
    // The production adapter, driving the real service over the shipped files

    private class Consent(override var granted: Boolean = true) : DefinitionsConsent

    private fun shipped(name: String): ByteArray {
        val relative = "tools/emulator-definitions/$name"
        val candidates = listOf(File("../$relative"), File(relative), File("../../$relative"))
        return candidates.firstOrNull(File::isFile)?.readBytes() ?: error("$relative not found from ${File(".").absolutePath}")
    }

    private fun serveShippedFiles() = recorder { url ->
        when (url) {
            pointerUrl -> FakeResponse(200, shipped("pointer.json"))
            tagUrl -> FakeResponse(200, shipped("definitions.json"))
            else -> FakeResponse(404, ByteArray(0))
        }
    }

    @Test
    fun `the shipped files go through the real adapter, route and gate and change nothing at first`() {
        val holder = EffectiveDefinitionsHolder(DefinitionsMerge.merge(ActiveDefinitions.bundled, null))
        val service = EmulatorDefinitionsService(
            dir = tmp.newFolder(),
            transport = EmulatorDefinitionsTransport(serveShippedFiles()),
            consent = Consent(),
            bundled = ActiveDefinitions.bundled,
            holder = holder,
        )
        val before = EmulatorSeedRegistry.allSeedPlatforms().associateWith { EmulatorSeedRegistry.candidatesFor(it) { true } }
        val key = holder.current.stateKey

        val result = MimirEmulatorDefinitionsGate.whileUserStartedRefresh { service.check() }

        assertEquals("the first release mirrors the compiled list", DefinitionsCheck.UpToDate, result)
        assertEquals("defs-1", service.info().tag)
        assertEquals(key, holder.current.stateKey)
        assertEquals(listOf(pointerUrl, tagUrl), calls.map { it.url })
        assertTrue(calls.all { it.headers["Accept-Encoding"] == "identity" })
        assertEquals("a repeat press reads only the pointer", DefinitionsCheck.UpToDate, MimirEmulatorDefinitionsGate.whileUserStartedRefresh { service.check() })
        assertEquals(listOf(pointerUrl, tagUrl, pointerUrl), calls.map { it.url })
        assertEquals(before, EmulatorSeedRegistry.allSeedPlatforms().associateWith { EmulatorSeedRegistry.candidatesFor(it) { true } })
    }

    /** A response whose body waits until it is closed, the way a stalled connection does. */
    private class HangingResponse : MimirProviderEgressResponse {
        private val released = CountDownLatch(1)

        @Volatile
        var closed = false
        override val statusCode: Int get() = 200
        override fun header(name: String): String? = null
        override val declaredLength: Long get() = -1L
        override fun body(): InputStream = object : InputStream() {
            override fun read(): Int {
                released.await()
                throw IOException("connection closed")
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                released.await()
                throw IOException("connection closed")
            }
        }

        override fun close() {
            closed = true
            released.countDown()
        }
    }

    @Test(timeout = 30_000)
    fun `a server that never answers ends the check at the time cap and the gate is closed again`() {
        val hang = HangingResponse()
        val service = EmulatorDefinitionsService(
            dir = tmp.newFolder(),
            transport = EmulatorDefinitionsTransport(recorder { url -> if (url == pointerUrl) hang else null }),
            consent = Consent(),
            bundled = ActiveDefinitions.bundled,
            holder = EffectiveDefinitionsHolder(DefinitionsMerge.merge(ActiveDefinitions.bundled, null)),
            checkBudgetMillis = 300,
        )
        val started = System.nanoTime()

        val result = MimirEmulatorDefinitionsGate.whileUserStartedRefresh { service.check() }

        assertEquals(DefinitionsCheck.Failed(null, offline = true, needsAppUpdate = false, usingFetched = false), result)
        assertTrue("took ${(System.nanoTime() - started) / 1_000_000} ms", (System.nanoTime() - started) / 1_000_000 < 10_000)
        assertTrue("the stalled connection was closed", hang.closed)
        assertFalse("the gate is closed once the check has ended", MimirEmulatorDefinitionsGate.isArmed)
        assertEquals(listOf(pointerUrl), calls.map { it.url })
    }

    @Test
    fun `outside the player's press the adapter reaches nothing and the check reports it could not connect`() {
        val service = EmulatorDefinitionsService(
            dir = tmp.newFolder(),
            transport = EmulatorDefinitionsTransport(serveShippedFiles()),
            consent = Consent(),
            bundled = ActiveDefinitions.bundled,
            holder = EffectiveDefinitionsHolder(DefinitionsMerge.merge(ActiveDefinitions.bundled, null)),
        )

        val result = service.check()

        assertEquals(DefinitionsCheck.Failed(null, offline = true, needsAppUpdate = false, usingFetched = false), result)
        assertEquals("the gate was never armed, so nothing was opened", 0, calls.size)
    }
}
