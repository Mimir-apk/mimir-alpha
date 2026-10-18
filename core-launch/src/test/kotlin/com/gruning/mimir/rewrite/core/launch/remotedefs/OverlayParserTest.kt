package com.gruning.mimir.rewrite.core.launch.remotedefs

import com.gruning.mimir.rewrite.core.launch.remotedefs.TestBundled.DREAMCAST
import com.gruning.mimir.rewrite.core.launch.remotedefs.TestBundled.FLYCAST
import com.gruning.mimir.rewrite.core.launch.remotedefs.TestBundled.FLYCAST_REICAST
import com.gruning.mimir.rewrite.core.launch.remotedefs.TestBundled.GB
import com.gruning.mimir.rewrite.core.launch.remotedefs.TestBundled.PPSSPP
import com.gruning.mimir.rewrite.core.launch.remotedefs.TestBundled.PSP
import com.gruning.mimir.rewrite.core.launch.remotedefs.TestBundled.RETROARCH
import com.gruning.mimir.rewrite.core.model.PlatformId
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The pieces under the matrix: the strict reader, the canonical form, the regex dialect, the merge, the store line. */
class OverlayParserTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val bundled = TestBundled.small()

    private fun accepted(bytes: ByteArray, expected: String? = null): ValidatedOverlay {
        val result = OverlayParser.parse(bytes, bundled, expected)
        assertTrue(result.toString(), result is ParseResult.Accepted)
        return (result as ParseResult.Accepted).overlay
    }

    private fun code(bytes: ByteArray): RejectCode? = (OverlayParser.parse(bytes, bundled) as? ParseResult.Rejected)?.code

    // ---- canonical form, with hashes computed independently by `sha256sum` --------------------------------------

    @Test
    fun the_canonical_form_matches_independently_computed_hashes() {
        val empty = StrictJson.canonical(StrictJson.parse("{\"rows\":[]}".toByteArray()))
        assertEquals("{\"rows\":[]}", empty)
        // printf '%s' '{"rows":[]}' | sha256sum
        assertEquals("1633902c6cbba5e7770dbed172df754a25078bb76efe1f23474edc87f1a47655", sha256Hex(empty.toByteArray()))

        val messy = " {\n \"rows\" : [ {\"type\":\"extensions\", \"platform\":\"gb\", \"package\":\"org.x.y\", \"extensions\":[\"b\",\"a\"]} ] } "
        val canonical = StrictJson.canonical(StrictJson.parse(messy.toByteArray()))
        assertEquals("{\"rows\":[{\"extensions\":[\"b\",\"a\"],\"package\":\"org.x.y\",\"platform\":\"gb\",\"type\":\"extensions\"}]}", canonical)
        // printf '%s' '<that line>' | sha256sum
        assertEquals("dccd555897f855efcb9f3070ee4d1e5d203180cf46e0658ca1d9012092eb4519", sha256Hex(canonical.toByteArray()))
    }

    @Test
    fun key_order_and_whitespace_change_the_raw_hash_but_not_the_canonical_hash() {
        val a = Overlays.build(5, listOf(Rows.alt(FLYCAST, "dreamcast", "com.x.Main")))
        val b = Overlays.build(5, listOf(Rows.alt(FLYCAST, "dreamcast", "com.x.Main")), pretty = true)
        assertFalse(a.rawSha == b.rawSha)
        assertEquals(a.canonicalSha, b.canonicalSha)
        assertEquals(accepted(a.bytes).canonicalSha256, accepted(b.bytes).canonicalSha256)
    }

    // ---- the envelope ------------------------------------------------------------------------------------------

    @Test
    fun a_valid_file_parses_into_typed_rows_and_the_signature_field_is_ignored() {
        val rows = listOf(
            Rows.alt(FLYCAST, "dreamcast", "com.x.Main"),
            Rows.core(RETROARCH, "gb", "mgba_extra"),
            Rows.ext(PPSSPP, "psp", "pbp", "cso"),
            Rows.regex(PPSSPP, "psp", "^.*\\\\.(?:cso|ciso)\$"),
        )
        val unsigned = Overlays.build(7, rows)
        val signed = Overlays.build(7, rows, sigJson = "\"QUJDREVGRw==\"")

        val overlay = accepted(unsigned.bytes, expected = unsigned.canonicalSha)
        assertEquals(7L, overlay.ordinal)
        assertEquals(1, overlay.schema)
        assertEquals(4, overlay.rows.size)
        assertEquals(listOf("cso", "pbp"), (overlay.rows[2] as ExtensionsRow).extensions)
        assertEquals("^.*\\.(?:cso|ciso)\$", (overlay.rows[3] as FilenameRegexRow).regex)

        val withSig = accepted(signed.bytes)
        assertEquals("a signature is not part of the checked body", overlay.canonicalSha256, withSig.canonicalSha256)
        assertFalse("but the raw bytes differ", overlay.rawSha256 == withSig.rawSha256)

        assertEquals(RejectCode.BAD_ENVELOPE, code(Overlays.build(7, rows, sigJson = "123").bytes))
        assertEquals(RejectCode.BAD_ENVELOPE, code(Overlays.build(7, rows, sigJson = "\"has spaces\"").bytes))
        assertEquals("a signature is bounded by the 256-byte string cap", RejectCode.STRING_TOO_LONG, code(Overlays.build(7, rows, sigJson = "\"" + "A".repeat(257) + "\"").bytes))
        assertNotNull(accepted(Overlays.build(7, rows, sigJson = "\"" + "A".repeat(256) + "\"").bytes))
    }

    @Test
    fun the_pin_must_match_the_canonical_body_hash() {
        val built = Overlays.build(7, listOf(Rows.alt(FLYCAST, "dreamcast", "com.x.Main")))
        val other = Overlays.build(7, listOf(Rows.alt(FLYCAST, "dreamcast", "com.x.Other")))
        assertNotNull(accepted(built.bytes, built.canonicalSha))
        val wrong = OverlayParser.parse(built.bytes, bundled, other.canonicalSha)
        assertTrue(wrong is ParseResult.Rejected && wrong.code == RejectCode.PIN_MISMATCH)
    }

    @Test
    fun ordinal_schema_and_hash_fields_are_range_and_type_checked() {
        val good = String(Overlays.build(7, listOf(Rows.alt(FLYCAST, "dreamcast", "com.x.Main"))).bytes, Charsets.US_ASCII)
        fun with(old: String, new: String) = good.replace(old, new).toByteArray()
        assertEquals(RejectCode.BAD_ENVELOPE, code(with("\"ordinal\":7", "\"ordinal\":0")))
        assertEquals(RejectCode.BAD_ENVELOPE, code(with("\"ordinal\":7", "\"ordinal\":-3")))
        assertEquals(RejectCode.BAD_ENVELOPE, code(with("\"ordinal\":7", "\"ordinal\":\"7\"")))
        assertEquals(RejectCode.PARSE, code(with("\"ordinal\":7", "\"ordinal\":1234567890123456")))
        assertEquals(RejectCode.BAD_ENVELOPE, code(with("\"sha256\":\"", "\"sha256\":\"A")))
        assertEquals("a misspelt body key is an unknown key", RejectCode.UNKNOWN_KEY, code(good.replace("\"body\"", "\"bodyy\"").toByteArray()))
        assertEquals("a missing body", RejectCode.BAD_ENVELOPE, code((good.substringBefore(",\"body\"") + "}").toByteArray()))
        assertEquals(RejectCode.HASH_MISMATCH, code(with("com.x.Main", "com.x.Maim")))
    }

    // ---- the strict reader ------------------------------------------------------------------------------------

    @Test
    fun nesting_depth_eight_is_read_and_nine_is_refused() {
        fun nested(levels: Int) = "{\"a\":".repeat(levels) + "1" + "}".repeat(levels)
        assertNotNull(StrictJson.parse(nested(RemoteDefinitionsLimits.MAX_DEPTH).toByteArray()))
        try {
            StrictJson.parse(nested(RemoteDefinitionsLimits.MAX_DEPTH + 1).toByteArray())
            throw AssertionError("depth 9 must be refused")
        } catch (e: OverlayRejected) {
            assertEquals(RejectCode.TOO_DEEP, e.code)
        }
    }

    @Test
    fun escapes_decode_and_integers_are_exact() {
        @Suppress("UNCHECKED_CAST")
        val tree = StrictJson.parse("{\"a\":\"\\u0041\\/\\\\\\\"\",\"n\":-0,\"t\":true,\"f\":false,\"z\":null,\"big\":999999999999999}".toByteArray()) as Map<String, Any?>
        assertEquals("A/\\\"", tree["a"])
        assertEquals(0L, tree["n"])
        assertEquals(true, tree["t"])
        assertEquals(false, tree["f"])
        assertNull(tree["z"])
        assertEquals(999_999_999_999_999L, tree["big"])
    }

    @Test
    fun a_package_name_over_128_characters_is_refused_as_malformed_before_the_pattern_is_asked() {
        val longPackage = "com." + "a".repeat(125) + ".b" // well-formed, 131 characters
        assertTrue(longPackage.length > 128)
        val tooLong = Overlays.build(7, listOf(Rows.ext(longPackage, "psp", "iso")))
        val result = OverlayParser.parse(tooLong.bytes, bundled)
        assertEquals(RejectCode.UNKNOWN_PACKAGE, (result as ParseResult.Rejected).code)
        assertTrue(result.detail, result.detail.endsWith("malformed package"))
        // Exactly 128 passes the length test and is then judged as a package that is not bundled.
        val at128 = "com." + "a".repeat(122) + ".b"
        assertEquals(128, at128.length)
        val atCap = OverlayParser.parse(Overlays.build(7, listOf(Rows.ext(at128, "psp", "iso"))).bytes, bundled) as ParseResult.Rejected
        assertTrue(atCap.detail, atCap.detail.endsWith("not a bundled package"))
    }

    // ---- the regex dialect --------------------------------------------------------------------------------------

    @Test
    fun the_regex_dialect_accepts_useful_patterns_and_refuses_the_dangerous_ones() {
        val fine = listOf(
            "^.*\\.(?:iso|cso)\$",
            "^(?:default\\.xbe|eboot\\.bin)\$",
            "^[a-z0-9_]+\\.chd\$",
            "^eboot\\.bin\$",
            "^a?b?c?d?e\$",
            "^disc[0-9]?\\.(?:bin|cue)\$",
            "^[a-z]-[0-9]\$",
        )
        for (p in fine) assertNull("should be accepted: $p", RecognitionRegex.problem(p))

        val bad = listOf(
            "^(a+)+\$", "^(a*)*\$", "^(a|aa)+\$", "^(?:a|aa)+\$", "^(?:a+)+\$", "^.*.*\$", "^.+.*\$", "^a*b*\$",
            "^a{2}\$", "^a{1,}\$", "^(?:a)(?:b)(?:c)(?:d)(?:e)\$", "^a?b?c?d?e?\$", "^x*?\$", "^x+?\$", "^x??\$",
            "^(?:a(?:b))\$", "^(?<n>a)\$", "^(a)\$", "^(?=a)\$", "^(?!a)\$", "^\\1\$", "^\\d\$", "^\\w+\$", "^\\\$", "^[^a]\$",
            "^[a-]\$z", "^[\$]\$", "^[z-a]\$", "^[]\$", "^[a\$", "^a|b\$", "^|\$", "^(?:a|)\$x", "^(?:a\$", "^a)\$", "^\$\$",
            ".*", "^.*", ".*\$", "", "^", "^A\$", "^a b\$", "^a\\.\\+\$",
            "^" + "a".repeat(99) + "\$",
        )
        for (p in bad) assertNotNull("should be refused: $p", RecognitionRegex.problem(p))
    }

    @Test
    fun an_accepted_regex_matches_lowercase_file_names_only_as_written() {
        val iso = RecognitionRegex.compile("^.*\\.(?:iso|cso)\$")!!
        assertTrue(iso.matches("Game.ISO"))
        assertTrue(iso.matches("a.b.cso"))
        assertFalse(iso.matches("game.isox"))
        assertFalse(iso.matches("game.bin"))
        val marker = RecognitionRegex.compile("^eboot\\.bin\$")!!
        assertTrue(marker.matches("EBOOT.BIN"))
        assertFalse(marker.matches("xeboot.bin"))
        assertNull(RecognitionRegex.compile("^(a+)+\$"))
    }

    @Test(timeout = 20_000)
    fun a_catastrophic_pattern_is_refused_and_the_matcher_has_no_backtracking_to_hang_on() {
        // The validator refuses it, and the compiler is a second line of defence: it reads only the dialect, never java.util.regex.
        assertNotNull(RecognitionRegex.problem("^(a+)+\$"))
        assertNull(RecognitionRegex.compile("^(a+)+\$"))
        // The dialect's worst legal shape, on the longest name: bounded steps (RecognitionMatcherTest pins the ceiling).
        val worst = RecognitionRegex.compile("^(?:a|aa)?(?:a|aa)?(?:a|aa)?(?:a|aa)?a*b\$")!!
        assertFalse(worst.matches("a".repeat(254) + "c"))
        assertTrue("a normal match still works", worst.matches("aaaab"))
    }

    // ---- the endpoint -------------------------------------------------------------------------------------------

    @Test
    fun only_an_immutable_tag_or_commit_on_the_exact_host_is_allowed() {
        val base = "https://raw.githubusercontent.com/Mimir-apk/mimir-emulator-definitions"
        assertEquals("$base/defs-42/definitions.json", RemoteDefinitionsEndpoint.urlFor("defs-42"))
        assertTrue(RemoteDefinitionsEndpoint.urlAllowed("$base/defs-42/definitions.json"))
        assertTrue(RemoteDefinitionsEndpoint.urlAllowed("$base/0123456789abcdef0123456789abcdef01234567/definitions.json"))
        assertTrue(RemoteDefinitionsEndpoint.urlAllowed("https://raw.githubusercontent.com:443/Mimir-apk/mimir-emulator-definitions/defs-1/definitions.json"))
        val refused = listOf(
            "$base/main/definitions.json",
            "$base/defs-42/other.json",
            "$base/defs-42/definitions.json?x=1",
            "$base/defs-42/definitions.json#frag",
            "http://raw.githubusercontent.com/Mimir-apk/mimir-emulator-definitions/defs-42/definitions.json",
            "https://Raw.GitHubUserContent.com/Mimir-apk/mimir-emulator-definitions/defs-42/definitions.json",
            "https://raw.githubusercontent.com.evil.example/Mimir-apk/mimir-emulator-definitions/defs-42/definitions.json",
            "https://raw.githubusercontent.com:8443/Mimir-apk/mimir-emulator-definitions/defs-42/definitions.json",
            "https://evil@raw.githubusercontent.com/Mimir-apk/mimir-emulator-definitions/defs-42/definitions.json",
            "https://raw.githubusercontent.com/other/repo/defs-42/definitions.json",
            "$base/defs-42/../defs-43/definitions.json",
            "not a url",
            "",
        )
        for (url in refused) assertFalse("should be refused: $url", RemoteDefinitionsEndpoint.urlAllowed(url))
        for (ref in listOf("main", "defs-", "defs-1234567890", "DEFS-1", "../x", "")) assertNull(ref, RemoteDefinitionsEndpoint.urlFor(ref))
    }

    @Test
    fun the_pointer_is_the_one_branch_path_that_is_allowed_and_the_base_url_is_the_pinned_repository() {
        assertEquals("https://raw.githubusercontent.com/Mimir-apk/mimir-emulator-definitions", RemoteDefinitionsEndpoint.BASE_URL)
        assertEquals("${RemoteDefinitionsEndpoint.BASE_URL}/main/pointer.json", RemoteDefinitionsEndpoint.POINTER_URL)
        assertEquals(RemoteDefinitionsEndpoint.POINTER_URL, RemoteDefinitionsEndpoint.PRODUCTION.pointerUrl)
        assertTrue(RemoteDefinitionsEndpoint.urlAllowed(RemoteDefinitionsEndpoint.POINTER_URL))
        val base = RemoteDefinitionsEndpoint.BASE_URL
        val refused = listOf(
            "$base/develop/pointer.json",
            "$base/defs-1/pointer.json",
            "$base/main/pointer.json?x=1",
            "$base/main/pointer.json#x",
            "$base/main/pointer.json/extra",
            "$base/main/other.json",
            "http://raw.githubusercontent.com/Mimir-apk/mimir-emulator-definitions/main/pointer.json",
            "https://raw.githubusercontent.com/other/repo/main/pointer.json",
            "https://evil.example/Mimir-apk/mimir-emulator-definitions/main/pointer.json",
        )
        for (url in refused) assertFalse("should be refused: $url", RemoteDefinitionsEndpoint.urlAllowed(url))
    }

    // ---- the merge ---------------------------------------------------------------------------------------------

    private fun overlayOf(ordinal: Long, vararg rows: OverlayRow) = ValidatedOverlay(1, ordinal, "r$ordinal", "c$ordinal", rows.toList())

    @Test
    fun the_merge_appends_sorts_dedupes_and_ignores_rows_that_do_not_fit() {
        val rows = arrayOf<OverlayRow>(
            RetroArchCoreRow(RETROARCH, GB, "zzz"),
            RetroArchCoreRow(RETROARCH, GB, "aaa"),
            RetroArchCoreRow(RETROARCH, GB, "mgba"), // already bundled
            ActivityAlternateRow(FLYCAST, DREAMCAST, "com.b.Main"),
            ActivityAlternateRow(FLYCAST, DREAMCAST, "com.a.Main"),
            ActivityAlternateRow(FLYCAST, DREAMCAST, FLYCAST_REICAST), // already bundled
            ExtensionsRow(PPSSPP, PSP, listOf("pbp")),
            ExtensionsRow(PPSSPP, PSP, listOf("cso")),
            // These do not fit the bundled state and must create nothing:
            ActivityAlternateRow("com.not.bundled", DREAMCAST, "com.a.Main"),
            RetroArchCoreRow(FLYCAST, DREAMCAST, "flycast"),
            ActivityAlternateRow(TestBundled.RECOGNIZE_ONLY, TestBundled.PS2, "com.a.Main"),
            ActivityAlternateRow(FLYCAST, GB, "com.a.Main"),
        )
        val effective = DefinitionsMerge.merge(bundled, overlayOf(3, *rows))

        assertEquals(listOf("gambatte", "mgba", "aaa", "zzz"), effective.retroArchCoresFor(RETROARCH, GB))
        assertEquals(listOf(FLYCAST_REICAST, "com.a.Main", "com.b.Main"), effective.activityAlternatesFor(FLYCAST, DREAMCAST))
        assertEquals(listOf("cso", "pbp"), effective.extensionsFor(PPSSPP, PSP))
        assertEquals(emptyList<String>(), effective.retroArchCoresFor(FLYCAST, DREAMCAST))
        assertEquals(emptyList<String>(), effective.activityAlternatesFor("com.not.bundled", DREAMCAST))
        assertEquals(emptyList<String>(), effective.activityAlternatesFor(TestBundled.RECOGNIZE_ONLY, TestBundled.PS2))
        assertEquals(emptyList<String>(), effective.activityAlternatesFor(FLYCAST, GB))
        assertEquals(bundled.dispatchPackages, effective.dispatchPackages)

        val reversed = DefinitionsMerge.merge(bundled, overlayOf(3, *rows.reversedArray()))
        assertEquals(effective.render(), reversed.render())
        assertEquals(effective.stateKey, reversed.stateKey)
    }

    @Test
    fun an_overlay_at_or_below_the_baseline_is_retired_and_changes_nothing() {
        val row = RetroArchCoreRow(RETROARCH, GB, "extra")
        val base = DefinitionsMerge.merge(TestBundled.small(baselineOrdinal = 4), null)
        for (ordinal in listOf(1L, 3L, 4L)) {
            val merged = DefinitionsMerge.merge(TestBundled.small(baselineOrdinal = 4), overlayOf(ordinal, row))
            assertTrue(merged.overlayRetired)
            assertNull(merged.overlayOrdinal)
            assertEquals(base.stateKey, merged.stateKey)
        }
        val live = DefinitionsMerge.merge(TestBundled.small(baselineOrdinal = 4), overlayOf(5, row))
        assertFalse(live.overlayRetired)
        assertEquals(5L, live.overlayOrdinal)
        assertTrue(live.hasCore(RETROARCH, GB, "extra"))
    }

    @Test
    fun recognition_rules_apply_per_package_and_platform_only() {
        val effective = DefinitionsMerge.merge(
            bundled,
            overlayOf(
                3,
                ExtensionsRow(PPSSPP, PSP, listOf("pbp")),
                FilenameRegexRow(PPSSPP, PSP, "^eboot\\.bin\$"),
            ),
        )
        assertTrue(effective.recognizes(PPSSPP, PSP, "Game.PBP"))
        assertTrue(effective.recognizes(PPSSPP, PSP, "EBOOT.BIN"))
        assertFalse(effective.recognizes(PPSSPP, PSP, "game.iso"))
        assertFalse("other platform", effective.recognizes(PPSSPP, PlatformId("ps2"), "game.pbp"))
        assertFalse("other package", effective.recognizes(FLYCAST, PSP, "game.pbp"))
        assertFalse("no extension is not an empty extension", effective.recognizes(PPSSPP, PSP, "pbp"))
    }

    @Test
    fun the_holder_emits_only_when_behaviour_changes() {
        val holder = EffectiveDefinitionsHolder(DefinitionsMerge.merge(bundled, null))
        val heard = mutableListOf<Long?>()
        holder.addListener { heard += it.overlayOrdinal }
        val a = DefinitionsMerge.merge(bundled, overlayOf(3, RetroArchCoreRow(RETROARCH, GB, "x")))
        val sameBehaviourNewOrdinal = DefinitionsMerge.merge(bundled, overlayOf(4, RetroArchCoreRow(RETROARCH, GB, "x")))
        val different = DefinitionsMerge.merge(bundled, overlayOf(5, RetroArchCoreRow(RETROARCH, GB, "y")))

        assertFalse("bundled-only view is the starting point", holder.publish(DefinitionsMerge.merge(bundled, null)))
        assertTrue(holder.publish(a))
        assertFalse(holder.publish(sameBehaviourNewOrdinal))
        assertEquals("the view is swapped quietly so the ordinal stays current", 4L, holder.current.overlayOrdinal)
        assertTrue(holder.publish(different))
        assertEquals(listOf<Long?>(3L, 5L), heard)
        assertEquals(2, holder.emissions)
    }

    // ---- the store's pointer line ------------------------------------------------------------------------------

    @Test
    fun the_pointer_line_round_trips_and_refuses_garbage() {
        val sha = "a".repeat(64)
        val sha2 = "b".repeat(64)
        val record = RemoteDefinitionsStore.Record(sha, 12, 1, sha2, "W/\"abc\"", 1_700_000_000_000L)
        assertEquals(record, RemoteDefinitionsStore.parsePointer(RemoteDefinitionsStore.formatPointer(record)))
        val bare = RemoteDefinitionsStore.Record(sha, 12, 1, sha2, null, null)
        assertEquals(bare, RemoteDefinitionsStore.parsePointer(RemoteDefinitionsStore.formatPointer(bare)))
        // An ETag that is not a clean quoted token is dropped rather than written.
        val odd = RemoteDefinitionsStore.Record(sha, 12, 1, sha2, "has space", null)
        assertNull(RemoteDefinitionsStore.parsePointer(RemoteDefinitionsStore.formatPointer(odd))!!.etag)

        for (bad in listOf("", "rd1", "rd2 $sha 1 1 $sha2 - -", "rd1 $sha 0 1 $sha2 - -", "rd1 ${sha}x 1 1 $sha2 - -", "rd1 $sha 1 1 $sha2 - - extra", "rd1 $sha one 1 $sha2 - -")) {
            assertNull("should be refused: $bad", RemoteDefinitionsStore.parsePointer(bad))
        }
    }

    @Test
    fun the_store_reads_only_a_file_that_hashes_to_what_its_pointer_says() {
        val dir: File = tmp.newFolder("s")
        val store = RemoteDefinitionsStore(dir)
        val built = Overlays.build(5, listOf(Rows.alt(FLYCAST, "dreamcast", "com.x.Main")))
        val record = RemoteDefinitionsStore.Record(built.rawSha, 5, 1, built.canonicalSha, null, null)

        assertNull(store.readVerified())
        assertEquals(RemoteDefinitionsStore.CommitResult.Committed, store.commit(built.bytes, record, expectedPreviousRaw = null))
        assertNotNull(store.readVerified())

        // A commit that names the wrong previous state is a conflict and writes nothing.
        val before = snapshot(dir)
        val conflict = store.commit(built.bytes, record, expectedPreviousRaw = "f".repeat(64))
        assertTrue(conflict is RemoteDefinitionsStore.CommitResult.Conflict)
        assertEquals(before, snapshot(dir))

        // A record whose hash does not match the bytes is refused before any pointer moves.
        val lie = record.copy(rawSha256 = "0".repeat(64))
        try {
            store.commit(built.bytes, lie, expectedPreviousRaw = built.rawSha)
            throw AssertionError("a mismatching record must not commit")
        } catch (_: java.io.IOException) {
        }
        assertEquals(built.rawSha, store.readVerified()!!.record.rawSha256)
        assertFalse(fileNames(dir).any { it.endsWith(".tmp") })
    }
}
