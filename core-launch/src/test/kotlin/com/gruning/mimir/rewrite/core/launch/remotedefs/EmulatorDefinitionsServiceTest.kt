package com.gruning.mimir.rewrite.core.launch.remotedefs

import com.gruning.mimir.rewrite.core.launch.EmulatorSeedRegistry
import com.gruning.mimir.rewrite.core.launch.remotedefs.TestBundled.DREAMCAST
import com.gruning.mimir.rewrite.core.launch.remotedefs.TestBundled.FLYCAST
import com.gruning.mimir.rewrite.core.launch.remotedefs.TestBundled.GB
import com.gruning.mimir.rewrite.core.launch.remotedefs.TestBundled.RETROARCH
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
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
 * The wired path end to end on the JVM: the same service Settings calls, a fake repository (pointer plus immutable
 * files) behind the transport seam, a temp directory for the store. It proves the idempotency contract (note section 6)
 * for the real flow, which reads the pointer first: fetch twice, kill mid-write, damaged or mismatched files, offline,
 * rollbacks, and an overlay that tries to add a direct-launch entry.
 */
class EmulatorDefinitionsServiceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val bundled = TestBundled.small()
    private val alt = Rows.alt(FLYCAST, "dreamcast", "com.reicast.emulator.AltActivity")
    private val coreGb = Rows.core(RETROARCH, "gb", "mgba_extra")
    private val bundledAltRepeat = Rows.alt(FLYCAST, "dreamcast", TestBundled.FLYCAST_REICAST)

    @After
    fun noGlobalStateLeaks() {
        ActiveDefinitions.install(DefinitionsMerge.merge(ActiveDefinitions.bundled, null))
    }

    private class Consent(override var granted: Boolean) : DefinitionsConsent

    /** What a scripted request does instead of the normal answer. */
    private sealed interface Scripted {
        data object Normal : Scripted
        data object Unreachable : Scripted
        class Respond(val response: RemoteDefinitionsResponse) : Scripted
    }

    /** The fake repository: one mutable pointer and immutable files by tag, with a per-URL request count. */
    private class Site {
        var pointer: ByteArray = ByteArray(0)
        val files = ConcurrentHashMap<String, ByteArray>()
        val hits = ConcurrentHashMap<String, AtomicInteger>()
        var script: ((String) -> Scripted)? = null

        fun hitsFor(url: String): Int = hits[url]?.get() ?: 0
        val total: Int get() = hits.values.sumOf { it.get() }

        fun publish(built: Built, sha: String = built.rawSha, ordinal: Long = built.ordinal, tag: String = "defs-$ordinal") {
            files["defs-${built.ordinal}"] = built.bytes
            pointer = pointerText(ordinal, tag, sha).toByteArray(Charsets.US_ASCII)
        }

        val transport = RemoteDefinitionsTransport { url, _, _, _ ->
            hits.getOrPut(url) { AtomicInteger() }.incrementAndGet()
            when (val scripted = script?.invoke(url) ?: Scripted.Normal) {
                Scripted.Unreachable -> null
                is Scripted.Respond -> scripted.response
                Scripted.Normal -> when {
                    url == RemoteDefinitionsEndpoint.POINTER_URL -> FakeResponse.ok(pointer, etag = null)
                    else -> {
                        val tag = url.removePrefix(RemoteDefinitionsEndpoint.BASE_URL + "/").substringBefore('/')
                        files[tag]?.let { FakeResponse.ok(it, etag = null) } ?: FakeResponse.status(404)
                    }
                }
            }
        }
    }

    private fun urlOf(ordinal: Long) = RemoteDefinitionsEndpoint.urlFor("defs-$ordinal")!!

    private fun release(ordinal: Long, vararg rows: String) = Overlays.build(ordinal, rows.toList())

    private class Rig(
        val dir: File,
        val site: Site,
        val consent: Consent,
        val holder: EffectiveDefinitionsHolder,
        val service: EmulatorDefinitionsService,
    )

    private fun rig(
        site: Site = Site(),
        consent: Boolean = true,
        dir: File = tmp.newFolder(),
        bundled: BundledDefinitions = this.bundled,
        faultHook: (StoreStep) -> Unit = {},
        checkBudgetMillis: Long = RemoteDefinitionsLimits.CHECK_BUDGET_MS,
    ): Rig {
        val holder = EffectiveDefinitionsHolder(DefinitionsMerge.merge(bundled, null))
        val c = Consent(consent)
        return Rig(dir, site, c, holder, EmulatorDefinitionsService(dir, site.transport, c, bundled, holder, displayClock = { 1_700_000_000_000L }, faultHook = faultHook, checkBudgetMillis = checkBudgetMillis))
    }

    // ------------------------------------------------------------------------------------------------------------
    // Opt-in and the happy path

    @Test
    fun without_the_opt_in_nothing_is_requested_and_nothing_is_written() {
        val site = Site().also { it.publish(release(2, alt, coreGb)) }
        val r = rig(site, consent = false)

        assertEquals(DefinitionsCheck.NeedsConsent, r.service.check())

        assertEquals("no request left the process", 0, site.total)
        assertEquals(emptyList<String>(), r.dir.list()!!.toList())
        assertNull(r.service.info().tag)
    }

    @Test
    fun a_new_release_is_stored_installed_and_counted_by_the_emulators_it_changes() {
        val site = Site().also { it.publish(release(2, alt, coreGb)) }
        val r = rig(site)
        assertEquals(DefinitionsInfo(TestBundled_BUILT_IN, null, null), r.service.info())

        val result = r.service.check()

        assertEquals("Flycast and RetroArch changed", DefinitionsCheck.Updated(2), result)
        assertEquals(2L, r.holder.current.overlayOrdinal)
        assertTrue(r.holder.current.retroArchCoresFor(RETROARCH, GB).contains("mgba_extra"))
        assertEquals(listOf(TestBundled.FLYCAST_REICAST, "com.reicast.emulator.AltActivity"), r.holder.current.activityAlternatesFor(FLYCAST, DREAMCAST))
        assertEquals(DefinitionsInfo(TestBundled_BUILT_IN, "defs-2", 1_700_000_000_000L), r.service.info())
        assertEquals("one request for the pointer, one for the release", 2, site.total)
    }

    @Test
    fun the_stored_release_is_back_in_force_after_a_restart_without_any_request() {
        val site = Site().also { it.publish(release(2, alt, coreGb)) }
        val first = rig(site)
        assertTrue(first.service.check() is DefinitionsCheck.Updated)
        val requestsBefore = site.total

        val restarted = rig(site, dir = first.dir)
        val loaded = restarted.service.load()

        assertEquals(LoadStatus.OVERLAY_IN_USE, loaded.status)
        assertEquals(first.holder.current.stateKey, restarted.holder.current.stateKey)
        assertEquals("start-up reads the disk, never the network", requestsBefore, site.total)
    }

    // ------------------------------------------------------------------------------------------------------------
    // Idempotency: fetch twice

    @Test
    fun checking_twice_makes_one_small_request_and_writes_nothing_the_second_time() {
        val site = Site().also { it.publish(release(2, alt, coreGb)) }
        val r = rig(site)
        assertTrue(r.service.check() is DefinitionsCheck.Updated)
        ageFiles(r.dir)
        val files = snapshot(r.dir)
        val commits = r.service.store.commitCount
        val emissions = r.holder.emissions
        val key = r.holder.current.stateKey

        assertEquals(DefinitionsCheck.UpToDate, r.service.check())
        assertEquals(DefinitionsCheck.UpToDate, r.service.check())

        assertEquals("no file written and no mtime touched", files, snapshot(r.dir))
        assertEquals(commits, r.service.store.commitCount)
        assertEquals("no emission", emissions, r.holder.emissions)
        assertEquals(key, r.holder.current.stateKey)
        assertEquals("the pointer was read each time", 3, site.hitsFor(RemoteDefinitionsEndpoint.POINTER_URL))
        assertEquals("the release was downloaded once", 1, site.hitsFor(urlOf(2)))
    }

    @Test
    fun a_release_that_repeats_what_is_compiled_in_is_stored_but_reported_as_up_to_date() {
        val site = Site().also { it.publish(release(1, bundledAltRepeat)) }
        val r = rig(site)
        val key = r.holder.current.stateKey

        assertEquals(DefinitionsCheck.UpToDate, r.service.check())

        assertEquals("behaviour did not change", key, r.holder.current.stateKey)
        assertEquals("so nothing was announced downstream", 0, r.holder.emissions)
        assertEquals("defs-1", r.service.info().tag)
    }

    @Test
    fun a_pointer_at_or_below_what_the_app_already_contains_is_up_to_date_with_no_download() {
        val site = Site().also { it.publish(release(2, alt, coreGb)) }
        val r = rig(site, bundled = TestBundled.small(baselineOrdinal = 2))

        assertEquals(DefinitionsCheck.UpToDate, r.service.check())

        assertEquals(1, site.total)
        assertEquals(emptyList<String>(), r.dir.list()!!.toList())
    }

    @Test
    fun an_app_upgrade_that_ships_newer_data_retires_a_stored_release() {
        val site = Site().also { it.publish(release(2, alt, coreGb)) }
        val old = rig(site)
        assertTrue(old.service.check() is DefinitionsCheck.Updated)

        val upgraded = rig(site, dir = old.dir, bundled = TestBundled.small(baselineOrdinal = 2))
        assertEquals(LoadStatus.OVERLAY_RETIRED, upgraded.service.load().status)
        assertFalse(upgraded.holder.current.retroArchCoresFor(RETROARCH, GB).contains("mgba_extra"))
        assertNull("Settings goes back to Built-in", upgraded.service.info().tag)
    }

    // ------------------------------------------------------------------------------------------------------------
    // Integrity: checksum, damage, pointer shape

    @Test
    fun a_file_that_does_not_match_the_pointers_checksum_is_refused_and_what_was_in_force_stays() {
        val site = Site().also { it.publish(release(2, alt)) }
        val r = rig(site)
        assertTrue(r.service.check() is DefinitionsCheck.Updated)
        ageFiles(r.dir)
        val files = snapshot(r.dir)
        val key = r.holder.current.stateKey

        // A complete, valid file for a newer ordinal, but the pointer names a different checksum.
        site.publish(release(3, alt, coreGb), sha = "0".repeat(64))
        val result = r.service.check()

        assertEquals(DefinitionsCheck.Failed(RejectCode.PIN_MISMATCH, offline = false, needsAppUpdate = false, usingFetched = true), result)
        assertEquals(files, snapshot(r.dir))
        assertEquals(key, r.holder.current.stateKey)
        assertEquals("defs-2", r.service.info().tag)
    }

    @Test
    fun a_mismatched_checksum_with_nothing_fetched_before_leaves_the_built_in_list() {
        val site = Site().also { it.publish(release(2, alt, coreGb), sha = "f".repeat(64)) }
        val r = rig(site)
        val key = r.holder.current.stateKey

        val result = r.service.check()

        assertEquals(DefinitionsCheck.Failed(RejectCode.PIN_MISMATCH, offline = false, needsAppUpdate = false, usingFetched = false), result)
        assertEquals(key, r.holder.current.stateKey)
        assertEquals(emptyList<String>(), r.dir.list()!!.toList())
        assertNull(r.service.info().tag)
    }

    @Test
    fun a_damaged_file_never_applies_whatever_byte_is_wrong() {
        val good = release(3, alt, coreGb)
        val site = Site().also { it.publish(release(2, alt)) }
        val r = rig(site)
        assertTrue(r.service.check() is DefinitionsCheck.Updated)
        val files = fileNames(r.dir)
        val key = r.holder.current.stateKey
        val text = String(good.bytes, Charsets.US_ASCII)

        val damaged = listOf(
            "a flipped letter in a row" to text.replace("mgba_extra", "mgba_extrb"),
            "a flipped digit in the ordinal" to text.replace("\"ordinal\":3", "\"ordinal\":4"),
            "a brace missing" to text.dropLast(1),
            "html in place of the file" to "<html><body>Sign in to Wi-Fi</body></html>",
            "an empty body" to "",
            "an unknown key added" to text.replace("\"schema\":1,", "\"schema\":1,\"note\":\"x\","),
        )
        for ((name, body) in damaged) {
            site.files["defs-3"] = body.toByteArray(Charsets.US_ASCII)
            // The pointer honestly names the GOOD file's checksum, as it would after a CDN served something else.
            site.pointer = pointerText(3, "defs-3", good.rawSha).toByteArray(Charsets.US_ASCII)
            val result = r.service.check()
            assertTrue("$name: $result", result is DefinitionsCheck.Failed && result.usingFetched)
        }

        assertEquals("nothing new was written and no temp file is left", files, fileNames(r.dir))
        assertEquals(key, r.holder.current.stateKey)
        assertEquals("defs-2", r.service.info().tag)
    }

    @Test
    fun a_pointer_with_anything_beyond_its_closed_shape_is_refused() {
        val sha = "a".repeat(64)
        val big = "{\"schema\":1,\"ordinal\":2,\"tag\":\"defs-2\",\"sha256\":\"$sha\",\"sig\":\"${"A".repeat(200)}\"}".padEnd(PointerParser.MAX_BYTES + 10, ' ')
        val cases = listOf(
            "a url field" to (pointerText(2, "defs-2", sha, ",\"url\":\"https://evil.example/\"") to RejectCode.FORBIDDEN_FIELD),
            "a baseUri field" to (pointerText(2, "defs-2", sha, ",\"baseUri\":\"https://evil.example/\"") to RejectCode.FORBIDDEN_FIELD),
            "an unknown field" to (pointerText(2, "defs-2", sha, ",\"note\":\"x\"") to RejectCode.UNKNOWN_KEY),
            "a tag that is not defs-<ordinal>" to (pointerText(2, "defs-3", sha) to RejectCode.BAD_PIN),
            "a branch as the tag" to (pointerText(2, "main", sha) to RejectCode.BAD_PIN),
            "a checksum that is not hex" to (pointerText(2, "defs-2", "xyz") to RejectCode.BAD_PIN),
            "an uppercase checksum" to (pointerText(2, "defs-2", sha.uppercase()) to RejectCode.BAD_PIN),
            "a newer schema" to ("""{"schema":2,"ordinal":2,"tag":"defs-2","sha256":"$sha"}""" to RejectCode.SCHEMA_NEWER),
            "ordinal zero" to (pointerText(0, "defs-0", sha) to RejectCode.BAD_ENVELOPE),
            "a duplicate key" to ("""{"schema":1,"schema":1,"ordinal":2,"tag":"defs-2","sha256":"$sha"}""" to RejectCode.DUPLICATE_KEY),
            "not an object" to ("[1,2]" to RejectCode.NOT_JSON),
            "oversize" to (big to RejectCode.SIZE),
        )
        for ((name, case) in cases) {
            val (text, code) = case
            val parsed = PointerParser.parse(text.toByteArray(Charsets.US_ASCII))
            assertTrue("$name: $parsed", parsed is PointerParseResult.Rejected && parsed.code == code)
        }
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + pointerText(2, "defs-2", sha).toByteArray(Charsets.US_ASCII)
        assertTrue(PointerParser.parse(bom).let { it is PointerParseResult.Rejected && it.code == RejectCode.BOM })
        val ok = PointerParser.parse(pointerText(2, "defs-2", sha).toByteArray(Charsets.US_ASCII))
        assertEquals(PointerParseResult.Accepted(DefinitionsPointer(2, "defs-2", sha)), ok)
    }

    @Test
    fun a_pointer_for_a_newer_schema_asks_for_an_app_update_and_changes_nothing() {
        val site = Site().also { it.publish(release(2, alt)) }
        val r = rig(site)
        assertTrue(r.service.check() is DefinitionsCheck.Updated)
        val files = snapshot(r.dir)
        site.pointer = """{"schema":2,"ordinal":3,"tag":"defs-3","sha256":"${"a".repeat(64)}","shape":"unknown"}""".toByteArray(Charsets.US_ASCII)

        val result = r.service.check()

        assertEquals(DefinitionsCheck.Failed(RejectCode.SCHEMA_NEWER, offline = false, needsAppUpdate = true, usingFetched = true), result)
        assertTrue(r.holder.needsAppUpdate)
        assertEquals(files, snapshot(r.dir))
    }

    // ------------------------------------------------------------------------------------------------------------
    // Idempotency: rollback and ordinal reuse are judged from the pointer, before any download

    @Test
    fun a_pointer_that_goes_backwards_or_reuses_an_ordinal_is_refused_without_downloading() {
        val site = Site().also { it.publish(release(3, alt, coreGb)) }
        val r = rig(site)
        assertTrue(r.service.check() is DefinitionsCheck.Updated)
        val before = site.total

        site.publish(release(2, alt))
        assertEquals(DefinitionsCheck.Failed(RejectCode.ROLLBACK, false, false, true), r.service.check())
        assertEquals("only the pointer was read", before + 1, site.total)

        val reused = release(3, alt)
        site.publish(reused, ordinal = 3)
        assertEquals(DefinitionsCheck.Failed(RejectCode.ORDINAL_REUSE, false, false, true), r.service.check())
        assertEquals("defs-3", r.service.info().tag)
        assertTrue(r.holder.current.retroArchCoresFor(RETROARCH, GB).contains("mgba_extra"))
    }

    // ------------------------------------------------------------------------------------------------------------
    // Idempotency: offline

    @Test
    fun offline_changes_nothing_in_every_shape_it_takes() {
        val site = Site().also { it.publish(release(2, alt, coreGb)) }
        val r = rig(site)
        assertTrue(r.service.check() is DefinitionsCheck.Updated)
        ageFiles(r.dir)
        val files = snapshot(r.dir)
        val key = r.holder.current.stateKey
        val emissions = r.holder.emissions
        site.publish(release(3, alt, coreGb))

        val pointerOnly = { url: String ->
            if (url == RemoteDefinitionsEndpoint.POINTER_URL) Scripted.Normal else Scripted.Unreachable
        }
        val shapes = listOf<Pair<String, (String) -> Scripted>>(
            "no connection" to { _ -> Scripted.Unreachable },
            "503" to { _ -> Scripted.Respond(FakeResponse.status(503)) },
            "429" to { _ -> Scripted.Respond(FakeResponse.status(429)) },
            "pointer fine, release unreachable" to pointerOnly,
        )
        for ((name, script) in shapes) {
            site.script = script
            val result = r.service.check()
            assertTrue("$name: $result", result is DefinitionsCheck.Failed && result.offline && result.usingFetched)
        }
        // A captive portal answers 200 with a login page: not JSON, so refused, not mistaken for an update.
        site.script = { Scripted.Respond(FakeResponse.ok("<html>Sign in</html>".toByteArray(), etag = null)) }
        val portal = r.service.check()
        assertTrue(portal.toString(), portal is DefinitionsCheck.Failed && portal.reason == RejectCode.NOT_JSON && !portal.offline)
        // A 404 on the tag the pointer named.
        site.script = { url ->
            if (url == RemoteDefinitionsEndpoint.POINTER_URL) Scripted.Normal else Scripted.Respond(FakeResponse.status(404))
        }
        assertEquals(DefinitionsCheck.Failed(RejectCode.HTTP_STATUS, false, false, true), r.service.check())

        assertEquals("every disk byte as it was", files, snapshot(r.dir))
        assertEquals(key, r.holder.current.stateKey)
        assertEquals(emissions, r.holder.emissions)
        assertEquals("defs-2", r.service.info().tag)
    }

    @Test
    fun a_redirect_out_of_the_pinned_repository_is_refused_before_any_body_is_read() {
        val site = Site().also { it.publish(release(2, alt)) }
        val r = rig(site)
        val targets = listOf(
            "https://evil.example/Mimir-apk/mimir-emulator-definitions/main/pointer.json",
            "http://raw.githubusercontent.com/Mimir-apk/mimir-emulator-definitions/main/pointer.json",
            "https://raw.githubusercontent.com/someone/else/main/pointer.json",
            "https://raw.githubusercontent.com/Mimir-apk/mimir-emulator-definitions/main/definitions.json",
        )
        for (target in targets) {
            val redirect = FakeResponse.redirect(target)
            site.script = { Scripted.Respond(redirect) }
            val result = r.service.check()
            assertEquals(target, DefinitionsCheck.Failed(RejectCode.REDIRECT, false, false, false), result)
            assertEquals(0, redirect.bodyCalls.get())
        }
        assertEquals(emptyList<String>(), r.dir.list()!!.toList())
    }

    // ------------------------------------------------------------------------------------------------------------
    // Idempotency: killed mid-write

    @Test
    fun killed_at_any_step_the_next_start_sees_the_previous_release_or_the_built_in_list_never_a_mix() {
        class Crash : RuntimeException("simulated process death")
        for (hadPrevious in listOf(false, true)) {
            for (step in StoreStep.values()) {
                val site = Site().also { it.publish(release(2, alt)) }
                val dir = tmp.newFolder()
                if (hadPrevious) assertTrue(rig(site, dir = dir).service.check() is DefinitionsCheck.Updated)
                val previousKey = rig(site, dir = dir).also { it.service.load() }.holder.current.stateKey

                site.publish(release(3, alt, coreGb))
                var armed = true
                val dying = rig(site, dir = dir, faultHook = { at -> if (armed && at == step) throw Crash() })
                dying.service.load()
                val died = dying.service.check()
                armed = false
                assertTrue("$step: $died", died is DefinitionsCheck.Failed)

                // The process is gone. A new one starts on the same directory.
                val restarted = rig(site, dir = dir)
                val loaded = restarted.service.load()
                val newKey = rig(site).also { it.service.check() }.holder.current.stateKey
                val allowed = if (step == StoreStep.AFTER_POINTER_SWITCH) setOf(newKey) else setOf(previousKey)
                assertTrue("$step previous=$hadPrevious: ${loaded.status}", restarted.holder.current.stateKey in allowed)
                assertTrue("no temp file survives a restart", dir.list()!!.none { it.endsWith(".tmp") })

                // And the next check converges on the pointer's release.
                val again = restarted.service.check()
                assertTrue("$step: $again", again is DefinitionsCheck.Updated || again == DefinitionsCheck.UpToDate)
                assertEquals("defs-3", restarted.service.info().tag)
                assertEquals(DefinitionsCheck.UpToDate, restarted.service.check())
            }
        }
    }

    @Test
    fun a_stored_copy_damaged_on_disk_falls_back_to_the_built_in_list_and_the_next_check_repairs_it() {
        val site = Site().also { it.publish(release(2, alt, coreGb)) }
        val first = rig(site)
        assertTrue(first.service.check() is DefinitionsCheck.Updated)
        val stored = first.dir.listFiles()!!.single { it.name.startsWith("overlay-") }
        val bytes = stored.readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x01).toByte()
        stored.writeBytes(bytes)

        val restarted = rig(site, dir = first.dir)
        val loaded = restarted.service.load()

        assertEquals(LoadStatus.CORRUPT_CLEARED, loaded.status)
        assertEquals(DefinitionsMerge.merge(bundled, null).stateKey, restarted.holder.current.stateKey)
        assertNull(restarted.service.info().tag)
        assertEquals(DefinitionsCheck.Updated(2), restarted.service.check())
        assertEquals(first.holder.current.stateKey, restarted.holder.current.stateKey)
    }

    // ------------------------------------------------------------------------------------------------------------
    // Scope: details only, on the REAL registry, through the wired path

    @Test
    fun an_overlay_that_tries_to_add_a_new_direct_launch_entry_is_refused_through_the_wired_path_and_changes_nothing() {
        val real = ActiveDefinitions.bundled
        val before = EmulatorSeedRegistry.allSeedPlatforms().associateWith { EmulatorSeedRegistry.candidatesFor(it) { true } }
        val hostile = listOf(
            "a package the app does not query" to Rows.alt("com.example.newemu", "dreamcast", "com.example.newemu.MainActivity"),
            "a new row shape" to """{"type":"direct_launch","package":"com.flycast.emulator","platform":"dreamcast","activity":"x.y.Main"}""",
            "a capability" to """{"type":"activity_alternate","package":"com.flycast.emulator","platform":"dreamcast","activity":"x.y.Main","capability":"DIRECT_PAYLOAD"}""",
            "an intent action" to """{"type":"activity_alternate","package":"com.flycast.emulator","platform":"dreamcast","activity":"x.y.Main","action":"android.intent.action.VIEW"}""",
        )
        for ((name, row) in hostile) {
            val built = Overlays.build(7, listOf(Rows.core("com.retroarch.aarch64", "gb", "zz_extra_core"), row))
            val site = Site().also { it.publish(built) }
            val holder = ActiveDefinitions.holder
            val service = EmulatorDefinitionsService(tmp.newFolder(), site.transport, Consent(true), real, holder)

            val result = service.check()

            assertTrue("$name: $result", result is DefinitionsCheck.Failed && !result.offline && !result.usingFetched)
            assertEquals("$name left the process view untouched", null, ActiveDefinitions.current.overlayOrdinal)
            assertEquals(name, before, EmulatorSeedRegistry.allSeedPlatforms().associateWith { EmulatorSeedRegistry.candidatesFor(it) { true } })
        }
        assertFalse(EmulatorSeedRegistry.allSeedPackages().contains("com.example.newemu"))
    }

    @Test
    fun a_valid_release_installed_through_the_service_reaches_scan_and_launch_resolution() {
        val real = ActiveDefinitions.bundled
        val built = Overlays.build(2, listOf(Rows.core("com.retroarch.aarch64", "gb", "zz_extra_core")))
        val site = Site().also { it.publish(built) }
        val service = EmulatorDefinitionsService(tmp.newFolder(), site.transport, Consent(true), real, ActiveDefinitions.holder)
        val before = EmulatorSeedRegistry.candidatesFor(com.gruning.mimir.rewrite.core.model.PlatformId.GB) { true }

        assertEquals(DefinitionsCheck.Updated(1), service.check())

        val after = EmulatorSeedRegistry.candidatesFor(com.gruning.mimir.rewrite.core.model.PlatformId.GB) { true }
        assertEquals(before.size + 1, after.size)
        assertEquals("zz_extra_core", after.single { it !in before }.retroArchCore)
        // The transport that fetched it is not reachable from resolution: calling it again would show as a hit.
        val requests = site.total
        repeat(5) { EmulatorSeedRegistry.candidatesFor(com.gruning.mimir.rewrite.core.model.PlatformId.GB) { true } }
        assertEquals("scan and launch resolution make no request", requests, site.total)
    }

    // ------------------------------------------------------------------------------------------------------------
    // Fix round: one ordinal limit, a bounded jump, a time cap, and a stored release this build cannot use

    @Test
    fun the_tag_grammar_and_the_ordinal_limit_are_the_same_number() {
        val max = RemoteDefinitionsLimits.MAX_ORDINAL
        assertEquals(999_999_999L, max)
        assertNotNull(RemoteDefinitionsEndpoint.urlFor("defs-$max"))
        assertNull(RemoteDefinitionsEndpoint.urlFor("defs-${max + 1}"))

        val sha = "a".repeat(64)
        assertTrue(PointerParser.parse(pointerText(max, "defs-$max", sha).toByteArray()) is PointerParseResult.Accepted)
        val tooBigPointer = PointerParser.parse(pointerText(max + 1, "defs-${max + 1}", sha).toByteArray())
        assertEquals(RejectCode.BAD_ENVELOPE, (tooBigPointer as PointerParseResult.Rejected).code)

        assertTrue(OverlayParser.parse(Overlays.build(max, listOf(alt)).bytes, bundled) is ParseResult.Accepted)
        val tooBigFile = OverlayParser.parse(Overlays.build(max + 1, listOf(alt)).bytes, bundled)
        assertEquals(RejectCode.BAD_ENVELOPE, (tooBigFile as ParseResult.Rejected).code)
    }

    @Test
    fun a_pointer_more_than_100_ahead_of_the_built_in_list_is_refused_without_downloading() {
        val site = Site().also { it.publish(release(101, alt)) }
        val r = rig(site)

        assertEquals(DefinitionsCheck.Failed(RejectCode.ORDINAL_JUMP, false, false, false), r.service.check())
        assertEquals("only the pointer was read", 1, site.total)
        assertEquals(emptyList<String>(), r.dir.list()!!.toList())

        site.publish(release(100, alt))
        assertTrue("100 ahead is the furthest allowed", r.service.check() is DefinitionsCheck.Updated)
        assertEquals("defs-100", r.service.info().tag)
    }

    @Test
    fun a_pointer_more_than_100_ahead_of_the_stored_release_is_refused_and_an_honest_one_still_works() {
        val site = Site().also { it.publish(release(3, alt)) }
        val r = rig(site)
        assertTrue(r.service.check() is DefinitionsCheck.Updated)
        val before = site.total

        site.publish(release(104, coreGb))
        assertEquals(DefinitionsCheck.Failed(RejectCode.ORDINAL_JUMP, false, false, true), r.service.check())
        assertEquals("only the pointer was read", before + 1, site.total)
        assertEquals("defs-3", r.service.info().tag)

        site.publish(release(103, coreGb))
        assertTrue(r.service.check() is DefinitionsCheck.Updated)
        assertEquals("defs-103", r.service.info().tag)
    }

    @Test
    fun the_jump_is_measured_from_the_build_when_the_build_is_ahead_of_anything_stored() {
        val ahead = TestBundled.small(baselineOrdinal = 50)
        val site = Site().also { it.publish(release(151, alt)) }
        val r = rig(site, bundled = ahead)

        assertEquals(DefinitionsCheck.Failed(RejectCode.ORDINAL_JUMP, false, false, false), r.service.check())

        site.publish(release(150, alt))
        assertTrue(r.service.check() is DefinitionsCheck.Updated)
    }

    @Test
    fun the_largest_ordinal_can_never_be_stored_so_it_cannot_block_every_later_release() {
        val site = Site().also { it.publish(release(RemoteDefinitionsLimits.MAX_ORDINAL, alt)) }
        val r = rig(site)

        assertEquals(DefinitionsCheck.Failed(RejectCode.ORDINAL_JUMP, false, false, false), r.service.check())
        assertEquals(emptyList<String>(), r.dir.list()!!.toList())

        site.publish(release(2, alt))
        assertTrue("an honest release still lands", r.service.check() is DefinitionsCheck.Updated)
    }

    /** A response whose body waits until it is closed, the way a stalled connection does. */
    private class HangingResponse : RemoteDefinitionsResponse {
        val closeCalls = AtomicInteger(0)
        private val released = CountDownLatch(1)
        override val statusCode = 200
        override fun header(name: String): String? = null
        override val declaredLength = -1L
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
            closeCalls.incrementAndGet()
            released.countDown()
        }
    }

    /** A response that never stops but never goes quiet either: one byte every so often. */
    private class DrippingResponse : RemoteDefinitionsResponse {
        override val statusCode = 200
        override fun header(name: String): String? = null
        override val declaredLength = -1L
        override fun body(): InputStream = object : InputStream() {
            override fun read(): Int {
                Thread.sleep(40)
                return ' '.code
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                Thread.sleep(40)
                b[off] = ' '.code.toByte()
                return 1
            }
        }

        override fun close() = Unit
    }

    @Test
    fun the_time_cap_is_thirty_seconds() {
        assertEquals(30_000L, RemoteDefinitionsLimits.CHECK_BUDGET_MS)
    }

    @Test(timeout = 30_000)
    fun a_release_that_never_finishes_downloading_ends_the_check_at_the_time_cap_and_stores_nothing() {
        val hang = HangingResponse()
        val site = Site().also { it.publish(release(2, alt, coreGb)) }
        site.script = { url -> if (url == urlOf(2)) Scripted.Respond(hang) else Scripted.Normal }
        val r = rig(site, checkBudgetMillis = 300)
        val started = System.nanoTime()

        val result = r.service.check()

        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("ended near the cap, took $tookMs ms", tookMs < 10_000)
        assertEquals(DefinitionsCheck.Failed(null, offline = true, needsAppUpdate = false, usingFetched = false), result)
        assertTrue("the open response was closed", hang.closeCalls.get() >= 1)
        assertEquals(emptyList<String>(), r.dir.list()!!.toList())
        assertNull(r.service.info().tag)

        site.script = null
        assertTrue("the check released its slot, so the next press works", r.service.check() is DefinitionsCheck.Updated)
    }

    @Test(timeout = 30_000)
    fun a_server_that_drips_bytes_cannot_hold_the_check_past_the_time_cap() {
        val site = Site().also { it.publish(release(2, alt)) }
        site.script = { url -> if (url == RemoteDefinitionsEndpoint.POINTER_URL) Scripted.Respond(DrippingResponse()) else Scripted.Normal }
        val r = rig(site, checkBudgetMillis = 300)
        val started = System.nanoTime()

        val result = r.service.check()

        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("ended near the cap, took $tookMs ms", tookMs < 10_000)
        assertEquals(DefinitionsCheck.Failed(null, offline = true, needsAppUpdate = false, usingFetched = false), result)
        assertEquals("the release was never asked for", 0, site.hitsFor(urlOf(2)))
        assertEquals(emptyList<String>(), r.dir.list()!!.toList())
    }

    @Test
    fun a_stored_release_this_build_cannot_use_is_never_reported_as_up_to_date() {
        val site = Site().also { it.publish(release(2, alt, coreGb)) }
        val first = rig(site)
        assertTrue(first.service.check() is DefinitionsCheck.Updated)
        val requestsBefore = site.total

        // An app update that no longer contains Flycast: the stored release names a package this build does not know.
        val newerBuild = bundled.copy(seeds = bundled.seeds.filterNot { it.packageName == FLYCAST }, activityAlternates = emptyMap())
        val restarted = rig(site, dir = first.dir, bundled = newerBuild)
        assertEquals(LoadStatus.INVALID_FOR_THIS_BUILD, restarted.service.load().status)
        assertNull("not in force, so the row says built-in", restarted.service.info().tag)

        val result = restarted.service.check()

        assertEquals(DefinitionsCheck.Failed(RejectCode.NEEDS_NEWER_APP, offline = false, needsAppUpdate = true, usingFetched = false), result)
        assertNull(restarted.holder.current.overlayOrdinal)
        assertEquals("only the pointer was read", requestsBefore + 1, site.total)
    }

    private companion object {
        const val TestBundled_BUILT_IN = BUNDLED_DEFINITIONS_DATE
    }
}

/** The pointer file as published by the data repository; a function of its own so the fake repository and the tests share it. */
private fun pointerText(ordinal: Long, tag: String, sha: String, extra: String = "") =
    """{"schema":1,"ordinal":$ordinal,"tag":"$tag","sha256":"$sha","sig":null$extra}"""
