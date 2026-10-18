package com.gruning.mimir.rewrite.core.launch.remotedefs

import com.gruning.mimir.rewrite.core.launch.EmulatorSeedRegistry
import com.gruning.mimir.rewrite.core.model.EmulatorCapability
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Generates `tools/emulator-definitions/` from the compiled tables, deterministically: sorted keys, sorted rows, LF,
 * a final newline. The same bytes come out every time, so the checksum the pointer pins never moves on its own.
 *
 * Regenerate (after the tables change, bumping BUNDLED_DEFINITIONS_DATE and, to publish, the ordinal):
 *   MIMIR_REGENERATE_DEFINITIONS=1 bash build/test-captures/mimir_gradle_guarded.sh <worktree> <lane> <log> \
 *       :core-launch:testDebugUnitTest --tests "com.gruning.mimir.rewrite.core.launch.remotedefs.DefinitionsDataFilesTest"
 */
internal object DefinitionsDataGenerator {
    /** The release the files in `tools/emulator-definitions/` are. Bumped only to publish a new one. */
    const val ORDINAL: Long = 1L

    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    private val PLATFORM = Regex("^[a-z0-9_]{1,64}$")
    private val CLASS_NAME = Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+$")
    private val CORE = Regex("^[a-z0-9][a-z0-9_-]{0,63}$")

    /** Details only: the alternate activities the compiled profiles know, and every RetroArch core of a dispatchable seed. */
    fun rows(bundled: BundledDefinitions): List<String> = buildList {
        bundled.activityAlternates.entries
            .sortedWith(compareBy({ it.key.packageName }, { it.key.platform.value }))
            .forEach { (key, activities) ->
                activities.sorted().forEach { activity ->
                    require(PACKAGE.matches(key.packageName) && PLATFORM.matches(key.platform.value) && CLASS_NAME.matches(activity))
                    add(
                        """{"activity":"$activity","package":"${key.packageName}","platform":"${key.platform.value}","type":"activity_alternate"}""",
                    )
                }
            }
        bundled.seeds
            .filter { it.capability != EmulatorCapability.RECOGNIZE_ONLY && it.retroArchCores.isNotEmpty() }
            .sortedBy { it.packageName }
            .forEach { seed ->
                seed.retroArchCores.entries.sortedBy { it.key.value }.forEach { (platform, cores) ->
                    cores.sorted().forEach { core ->
                        require(PACKAGE.matches(seed.packageName) && PLATFORM.matches(platform.value) && CORE.matches(core)) {
                            "${seed.packageName} ${platform.value} $core does not fit the overlay grammar"
                        }
                        add(
                            """{"core":"$core","package":"${seed.packageName}","platform":"${platform.value}","type":"retroarch_core"}""",
                        )
                    }
                }
            }
    }

    /** The release file: one row per line, keys sorted, LF, ASCII. The inner `sha256` is the canonical body hash. */
    fun definitionsJson(bundled: BundledDefinitions, ordinal: Long = ORDINAL): String {
        val rows = rows(bundled)
        val body = """{"rows":[${rows.joinToString(",")}]}"""
        val canonicalSha = sha256Hex(StrictJson.canonical(StrictJson.parse(body.toByteArray(Charsets.US_ASCII))).toByteArray(Charsets.US_ASCII))
        return buildString {
            append("{\n")
            append("  \"body\": {\n")
            append("    \"rows\": [\n")
            rows.forEachIndexed { i, row -> append("      ").append(row).append(if (i < rows.size - 1) ",\n" else "\n") }
            append("    ]\n")
            append("  },\n")
            append("  \"ordinal\": $ordinal,\n")
            append("  \"schema\": 1,\n")
            append("  \"sha256\": \"$canonicalSha\",\n")
            append("  \"sig\": null\n")
            append("}\n")
        }
    }

    /** The pointer: the release's ordinal and tag, and the SHA-256 of the exact bytes of its `definitions.json`. */
    fun pointerJson(ordinal: Long, definitionsBytes: ByteArray): String = buildString {
        append("{\n")
        append("  \"ordinal\": $ordinal,\n")
        append("  \"schema\": 1,\n")
        append("  \"sha256\": \"${sha256Hex(definitionsBytes)}\",\n")
        append("  \"sig\": null,\n")
        append("  \"tag\": \"defs-$ordinal\"\n")
        append("}\n")
    }
}

class DefinitionsDataFilesTest {
    private val regenerate = System.getenv("MIMIR_REGENERATE_DEFINITIONS") == "1"
    private val bundled = ActiveDefinitions.bundled

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        error("no settings.gradle.kts above ${System.getProperty("user.dir")}")
    }

    private val dataDir: File get() = File(repoRoot(), "tools/emulator-definitions")

    private fun expectedDefinitions(): ByteArray = DefinitionsDataGenerator.definitionsJson(bundled).toByteArray(Charsets.US_ASCII)

    private fun expectedPointer(): ByteArray =
        DefinitionsDataGenerator.pointerJson(DefinitionsDataGenerator.ORDINAL, expectedDefinitions()).toByteArray(Charsets.US_ASCII)

    @Before
    fun regenerateWhenAsked() {
        if (!regenerate) return
        dataDir.mkdirs()
        File(dataDir, "definitions.json").writeBytes(expectedDefinitions())
        File(dataDir, "pointer.json").writeBytes(expectedPointer())
        // The README states the checksum of the file it describes; regeneration keeps it true.
        val readme = File(dataDir, "README.md")
        if (readme.isFile) {
            val sha = sha256Hex(expectedDefinitions())
            val row = Regex("""(\| `definitions.json` \| the release, tag `defs-1` \| `)([0-9a-f]{64}|@@SHA256@@)(` \|)""")
            readme.writeBytes(row.replace(readme.readText(Charsets.UTF_8)) { it.groupValues[1] + sha + it.groupValues[3] }.toByteArray(Charsets.UTF_8))
        }
    }

    @Test
    fun the_published_files_are_exactly_what_the_compiled_tables_generate() {
        assertArrayEquals(
            "definitions.json no longer mirrors the compiled tables: regenerate it (see this file's header), bump " +
                "BUNDLED_DEFINITIONS_DATE, and publish a new release if the change matters to players",
            expectedDefinitions(),
            File(dataDir, "definitions.json").readBytes(),
        )
        assertArrayEquals("pointer.json must name the checksum of definitions.json", expectedPointer(), File(dataDir, "pointer.json").readBytes())
    }

    @Test
    fun the_readme_states_the_checksum_of_the_published_file_and_names_the_first_tag() {
        val readme = File(dataDir, "README.md").readText(Charsets.UTF_8)
        assertTrue("README must state the SHA-256 of definitions.json", readme.contains(sha256Hex(File(dataDir, "definitions.json").readBytes())))
        assertFalse("README still has a placeholder", readme.contains("@@"))
        assertTrue(readme.contains("git tag defs-1"))
        assertTrue(readme.contains(RemoteDefinitionsEndpoint.POINTER_URL))
        assertTrue(readme.contains(RemoteDefinitionsEndpoint.urlFor("defs-1")!!))
        assertFalse("README is LF", readme.contains('\r'))
    }

    @Test
    fun the_files_are_lf_ascii_with_one_final_newline() {
        for (name in listOf("definitions.json", "pointer.json")) {
            val bytes = File(dataDir, name).readBytes()
            assertFalse("$name has a CR", bytes.any { it == '\r'.code.toByte() })
            assertTrue("$name is not ASCII", bytes.all { it >= 0 })
            assertEquals("$name must end in exactly one newline", '\n'.code.toByte(), bytes.last())
            assertFalse("$name has a blank last line", bytes.size > 1 && bytes[bytes.size - 2] == '\n'.code.toByte())
        }
    }

    @Test
    fun the_published_release_is_valid_for_this_build_and_pinned_by_the_pointer() {
        val definitions = File(dataDir, "definitions.json").readBytes()
        val parsed = OverlayParser.parse(definitions, bundled)
        assertTrue(parsed.toString().take(300), parsed is ParseResult.Accepted)
        val overlay = (parsed as ParseResult.Accepted).overlay
        assertEquals(DefinitionsDataGenerator.ORDINAL, overlay.ordinal)
        assertTrue("an empty release is not a release", overlay.rows.size > 50)

        val pointer = PointerParser.parse(File(dataDir, "pointer.json").readBytes())
        assertTrue(pointer.toString(), pointer is PointerParseResult.Accepted)
        val accepted = (pointer as PointerParseResult.Accepted).pointer
        assertEquals("defs-1", accepted.tag)
        assertEquals(overlay.rawSha256, accepted.sha256)
        assertEquals(sha256Hex(definitions), accepted.sha256)
        assertEquals("${RemoteDefinitionsEndpoint.BASE_URL}/defs-1/definitions.json", RemoteDefinitionsEndpoint.urlFor(accepted.tag))
    }

    @Test
    fun the_first_release_mirrors_the_compiled_tables_so_applying_it_changes_no_behaviour() {
        val overlay = (OverlayParser.parse(File(dataDir, "definitions.json").readBytes(), bundled) as ParseResult.Accepted).overlay
        val bundledOnly = DefinitionsMerge.merge(bundled, null)
        val withRelease = DefinitionsMerge.merge(bundled, overlay)

        assertEquals(1L, withRelease.overlayOrdinal)
        assertEquals("the release repeats what is compiled in, so the merged view is identical", bundledOnly.stateKey, withRelease.stateKey)
        assertEquals(emptySet<String>(), withRelease.packagesChangedFrom(bundledOnly))
        // Details only: every row is an alternate activity or a core, and every core of every dispatchable seed is there.
        assertTrue(overlay.rows.all { it is ActivityAlternateRow || it is RetroArchCoreRow })
        val coreRows = overlay.rows.filterIsInstance<RetroArchCoreRow>().map { Triple(it.packageName, it.platform, it.core) }.toSet()
        val compiled = EmulatorSeedRegistry.SEEDS
            .filter { it.capability != EmulatorCapability.RECOGNIZE_ONLY }
            .flatMap { seed ->
                (seed.retroArchCoreByPlatform.keys + seed.retroArchAdditionalCoresByPlatform.keys).flatMap { platform ->
                    (listOfNotNull(seed.retroArchCoreByPlatform[platform]) + seed.retroArchAdditionalCoresByPlatform[platform].orEmpty())
                        .map { Triple(seed.packageName, platform, it) }
                }
            }
            .toSet()
        assertEquals(compiled, coreRows)
    }

    @Test
    fun the_generator_is_deterministic() {
        assertEquals(DefinitionsDataGenerator.definitionsJson(bundled), DefinitionsDataGenerator.definitionsJson(RegistryBundledDefinitions.build()))
    }
}
