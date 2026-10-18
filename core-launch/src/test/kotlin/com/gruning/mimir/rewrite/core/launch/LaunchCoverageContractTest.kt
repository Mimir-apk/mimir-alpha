package com.gruning.mimir.rewrite.core.launch

import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.platform.IdentityNamespace
import com.gruning.mimir.rewrite.core.platform.PlatformRegistryLoader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class LaunchCoverageContractTest {
    @Test
    fun everyDiscoverableRuntimePlatformHasARecognizedRouteFamily() {
        val registry = resource("mimir-platform-registry.v2.json").use {
            PlatformRegistryLoader.load(it).requireRegistry()
        }
        val supportManifest = resource("support-set-manifest.json").use {
            Json.parseToJsonElement(it.bufferedReader().readText()).jsonObject
        }
        val sets = supportManifest.getValue("sets").jsonObject
        val canonicalIds = (ids(sets, "R39_DISCOVERABLE") + ids(sets, "DR47_DISCOVERY_RUNTIME")).toSet()
        val seeded = EmulatorSeedRegistry.allSeedPlatforms()

        canonicalIds.forEach { canonicalValue ->
            val canonical = PlatformId(canonicalValue)
            val platform = registry.definition(canonical)
            val emitted = platform
                ?.aliases
                ?.firstOrNull { it.namespace == IdentityNamespace.REWRITE_LEGACY }
                ?.normalizedValue
                ?.let(::PlatformId)
                ?: canonical
            assertTrue(
                "extension classifier emits ${emitted.value} for $canonicalValue, but core-launch has no recognized route family",
                emitted in seeded,
            )
        }
    }

    @Test
    fun everyExtensionDetectablePlatformIsRecognizedOrExplicitlyUnsupported() {
        val registryText = resource("mimir-platform-registry.v2.json").use {
            it.bufferedReader().readText()
        }
        val registry = PlatformRegistryLoader.load(registryText.byteInputStream()).requireRegistry()
        val registryJson = Json.parseToJsonElement(registryText).jsonObject
        val profiles = registryJson
            .getValue("collections").jsonObject
            .getValue("contentProfiles").jsonArray
        val baseExtensions = profiles.flatMap { profile ->
            val contentContract = profile.jsonObject["contentContract"] as? JsonObject
                ?: return@flatMap emptyList()
            contentContract.getValue("baseGameExtensions").jsonArray
                .map { it.jsonPrimitive.content }
        }.toSet()
        val canonicalIds = baseExtensions
            .flatMap { registry.extensionCandidates(it) }
            .toSet()
        val seeded = EmulatorSeedRegistry.allSeedPlatforms()
        val unsupported = ExtensionLaunchCoverage.unsupportedCanonicalPlatforms.keys

        // 57 -> 58: PC Games (5 extensions, one new canonical id) landed in the registry via the earlier change
        // before this ticket (R-2b) started; this pin was left stale by that merge, not by R-2b's own
        // change -- confirmed by running this test against R-2b's own base commit before touching
        // EmulatorSeedRegistry, where it was already red at this exact line for this exact reason.
        assertEquals("registry extension-detectable closure changed", 58, canonicalIds.size)
        assertTrue("unsupported list must not overlap recognized route families", canonicalIds.none { canonical ->
            canonical.value in unsupported && emittedId(registry, canonical) in seeded
        })
        canonicalIds.forEach { canonical ->
            assertTrue(
                "${canonical.value} is extension-detectable but neither recognized nor explicitly unsupported",
                emittedId(registry, canonical) in seeded || canonical.value in unsupported,
            )
        }
    }

    private fun ids(sets: JsonObject, setName: String): List<String> =
        sets.getValue(setName).jsonObject.getValue("ids").jsonArray.map { it.jsonPrimitive.content }

    private fun resource(name: String) = requireNotNull(javaClass.classLoader?.getResourceAsStream(name)) {
        "Missing test resource $name"
    }

    private fun emittedId(
        registry: com.gruning.mimir.rewrite.core.platform.PlatformRegistry,
        canonical: PlatformId,
    ): PlatformId = registry.definition(canonical)
        ?.aliases
        ?.firstOrNull { it.namespace == IdentityNamespace.REWRITE_LEGACY }
        ?.normalizedValue
        ?.let(::PlatformId)
        ?: canonical
}
