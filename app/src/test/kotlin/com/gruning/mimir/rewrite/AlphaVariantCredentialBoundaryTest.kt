package com.gruning.mimir.rewrite

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A build-time SteamGridDB developer key is allowed only in debuggable builds. Debug builds may
 * receive one from gitignored local.properties; fresh clones and CI may leave it empty.
 *
 * Official public Alpha builds use the non-debuggable beta build type. These debug JVM tests check
 * the conditional key boundary through BuildConfig and inspect the beta declaration in
 * app/build.gradle.kts for release inheritance, an empty key and the shared application identity.
 * The built APK is checked separately by tools/check-apk-key-free.sh.
 */
class AlphaVariantCredentialBoundaryTest {

    @Test
    fun `a build-time SteamGridDB key may only exist in a debuggable build`() {
        val key = BuildConfig.STEAMGRIDDB_API_KEY
        assertTrue(
            "STEAMGRIDDB_API_KEY is present in a NON-debuggable build. A developer credential " +
                "must never ship in an artifact a user could install as a normal release — see " +
                "the release/lab overrides in app/build.gradle.kts.",
            key.isBlank() || BuildConfig.DEBUG,
        )
    }

    /**
     * The Alpha ships an unsuffixed application id, so it installs over — and shares data with —
     * the player's real Mimir. `lab` and `devlab` deliberately take suffixes to avoid exactly
     * that. If this identity ever changes, the ON02 upgrade-preserves-state case and every
     * in-place install in the release runbook stop meaning what they say.
     */
    @Test
    fun `the alpha variant keeps the unsuffixed application identity`() {
        assertTrue(
            "Alpha application id drifted to ${BuildConfig.APPLICATION_ID}",
            BuildConfig.APPLICATION_ID == "com.gruning.mimir.rewrite",
        )
    }

    /** The `create("beta") { ... }` block of the app build script, braces matched, comments excluded. */
    private fun betaBuildTypeBlock(): String {
        val script = File(
            requireNotNull(System.getProperty("mimir.appBuildScript")) {
                "mimir.appBuildScript system property missing - check app/build.gradle.kts testOptions"
            },
        ).readText().replace("\r\n", "\n")
        val head = Regex("""create\("beta"\)\s*\{""").find(script)
        assertTrue("the beta build type is no longer declared in app/build.gradle.kts", head != null)
        var depth = 1
        var index = head!!.range.last + 1
        while (index < script.length && depth > 0) {
            when (script[index]) {
                '{' -> depth++
                '}' -> depth--
            }
            index++
        }
        assertTrue("the beta build type block in app/build.gradle.kts is unbalanced", depth == 0)
        return script.substring(head.range.last + 1, index - 1)
            .lineSequence()
            .filterNot { it.trim().startsWith("//") }
            .joinToString("\n")
    }

    /**
     * Closed-beta testers install `beta`. It must be release code (not debuggable, not profileable), carry an
     * EMPTY SteamGridDB key stated on the block itself rather than left to inheritance, and keep the unsuffixed
     * application id and version name so it supports in-place updates and pairs with the Tweaker.
     */
    @Test
    fun `the beta build type is release code with an empty key and the shared identity`() {
        val beta = betaBuildTypeBlock()
        assertTrue(
            "beta must derive from release - deriving from debug would carry the developer key",
            beta.contains("""initWith(getByName("release"))"""),
        )
        assertTrue(
            "beta must state STEAMGRIDDB_API_KEY as the empty string",
            beta.contains("""buildConfigField("String", "STEAMGRIDDB_API_KEY", "\"\"")"""),
        )
        assertTrue(
            "beta must not take any other STEAMGRIDDB_API_KEY value",
            Regex("""STEAMGRIDDB_API_KEY""").findAll(beta).count() == 1,
        )
        assertTrue(
            "beta must be signed with the shared debug config so the Tweaker pairs",
            beta.contains("""signingConfig = signingConfigs.getByName("debug")"""),
        )
        listOf(
            """initWith(getByName("debug"))""",
            "applicationIdSuffix",
            "versionNameSuffix",
            "isDebuggable = true",
            "isProfileable = true",
        ).forEach { forbidden ->
            assertTrue("beta must not contain `$forbidden`", !beta.contains(forbidden))
        }
    }
}
