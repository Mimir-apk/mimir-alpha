package com.gruning.mimir.rewrite.core.platform

import com.gruning.mimir.rewrite.core.model.CatalogEntryId
import com.gruning.mimir.rewrite.core.model.ContentProfileId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.RetroArchRegistrationId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratedRegistryResourceTest {
    @Test
    fun committedGeneratedEnvelopeLoadsThroughInputStreams() {
        val result = resource("mimir-platform-registry.v2.json").use { registry ->
            resource("mimir-platform-certification.v1.json").use { certification ->
                PlatformRegistryLoader.load(registry, certification)
            }
        }
        val registry = result.requireRegistry()
        val resourceRevision = generatedRegistryRoot().getValue("registryRevision").jsonPrimitive.content

        assertTrue(result.validationReport.isValid)
        assertEquals(226, registry.platforms().size)
        assertEquals(resourceRevision, registry.registryRevision.value)
        assertTrue(resourceRevision.matches(Regex("pk00-[0-9a-f]{12}-[0-9a-f]{12}")))
        assertEquals(
            PlatformId("nintendo_wii"),
            registry.canonicalize(IdentityNamespace.SHIPPING_RUNTIME, "wii"),
        )
        assertTrue(PlatformId("nintendo_wii") in registry.folderCandidates("wii"))
        assertTrue(registry.extensionCandidates("iso").size > 1)
        assertTrue(registry.search("Nintendo").size > 1)
    }

    @Test
    fun generatedCatalogRelationsMapWithoutFallbackDowngrade() {
        val registry = loadGeneratedRegistry()

        assertCatalogMapping(
            registry,
            "shipping-catalog:nintendo_e_reader",
            CatalogRelation.HARDWARE_VARIANT,
            "gba",
        )
        assertCatalogMapping(
            registry,
            "shipping-catalog:sony_playstation_portable_psx2psp",
            CatalogRelation.CONTENT_VARIANT,
            "ps1",
        )
        assertCatalogMapping(
            registry,
            "shipping-catalog:apple_macintosh_beta",
            CatalogRelation.CONTENT_VARIANT,
            "apple_macintosh",
        )
        assertCatalogMapping(
            registry,
            "shipping-catalog:mattel_hyperscan_alt",
            CatalogRelation.PRESENTATION_ALIAS,
            "mattel_hyperscan",
        )
        assertCatalogMapping(
            registry,
            "shipping-catalog:sony_playstation_scph_100_131_flat",
            CatalogRelation.PRESENTATION_ALIAS,
            "ps1",
        )
        assertCatalogMapping(
            registry,
            "shipping-catalog:vtech_socrates_alt",
            CatalogRelation.PRESENTATION_ALIAS,
            "vtech_socrates",
        )
        assertCatalogMapping(
            registry,
            "shipping-catalog:sega_mega_drive_genesis_kr",
            CatalogRelation.REGIONAL_VARIANT,
            "genesis",
        )
    }

    @Test
    fun generatedCatalogRelationCountsAndSupportedSchemaAreExact() {
        assertEquals(
            mapOf(
                "CANONICAL" to 225,
                "HARDWARE_VARIANT" to 137,
                "CONTENT_VARIANT" to 37,
                "PRESENTATION_ALIAS" to 3,
                "REGIONAL_VARIANT" to 1,
            ),
            generatedCatalogRelationCounts(),
        )
        CatalogRelation.values().forEach { relation ->
            assertEquals(relation, generatedCatalogRelation(relation.name, "test-record"))
        }
    }

    @Test
    fun generatedNeoGeoRegistrationIdsStayDistinctAndEmptyLedgerStaysClosed() {
        val registry = loadGeneratedRegistry()
        val legacy = registry.retroArchRegistration(RetroArchRegistrationId("neo_geo"))
        val canonical = registry.retroArchRegistration(RetroArchRegistrationId("snk_neo_geo"))

        assertNotNull(legacy)
        assertNotNull(canonical)
        assertEquals(2, legacy!!.coreRegistrations.size)
        assertEquals(2, canonical!!.coreRegistrations.size)
        assertEquals(PlatformId("snk_neo_geo"), legacy.platformId)
        assertEquals(PlatformId("snk_neo_geo"), canonical.platformId)
        assertNull(registry.activation(PlatformId("nintendo_wii")))
        assertTrue(registry.certifiedSlices(PlatformId("nintendo_wii")).isEmpty())
    }

    @Test
    fun dreamcastBinIsAnAmbiguousDeclaredExtension() {
        val registry = loadGeneratedRegistry()
        val profile = registry.contentProfile(ContentProfileId("shipping-catalog-profile:dreamcast"))

        assertNotNull(profile)
        assertTrue(registry.extensionCandidates("bin").contains(PlatformId("dreamcast")))
        assertTrue(
            profile!!.extensionRules.any {
                it.extension == "bin" && it.role == ContentRole.BASE_GAME && it.launchEligible
            },
        )
        assertTrue(
            profile.extensionRules.any {
                it.extension == "bin" && it.role == ContentRole.TRACK_MEMBER && !it.launchEligible
            },
        )
    }

    @Test
    fun generatedPcGamesNativePlatformIsWiredWithAllFiveExtensions() {
        val registry = loadGeneratedRegistry()
        val platform = registry.definition(PlatformId("pc_games"))

        assertNotNull(platform)
        assertEquals("PC Games", platform!!.display.name)
        assertEquals("pcgames", platform.folder.canonical.normalizedSegment)
        listOf("steam", "epic", "gog", "amazon", "pcgame").forEach { extension ->
            assertTrue(
                "extension '$extension' should resolve to pc_games",
                PlatformId("pc_games") in registry.extensionCandidates(extension),
            )
        }
        assertTrue(PlatformId("pc_games") in registry.folderCandidates("pcgames"))
        assertEquals(PlatformId("pc_games"), registry.canonicalize(IdentityNamespace.CANONICAL_PLATFORM, "pc_games"))
        assertEquals(PlatformId("pc_games"), registry.canonicalize(IdentityNamespace.EXTERNAL_FRONTEND, "pc_games"))
    }

    @Test
    fun generatedPayloadTamperingIsRejectedBeforeIndexing() {
        val original = resource("mimir-platform-registry.v2.json").use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        }
        val tampered = original.replace(
            "\"behaviorPosture\": \"BEHAVIOR_DARK\"",
            "\"behaviorPosture\": \"TAMPERED\"",
        )

        val result = PlatformRegistryLoader.load(tampered)

        assertNull(result.registry)
        assertTrue(ValidationCode.REGISTRY_DIGEST_MISMATCH in result.validationReport)
    }

    @Test
    fun generatedEnvelopeRejectsUnknownCollectionsEvenWhenMetadataMatches() {
        val unknownValues = listOf(
            emptyList(),
            listOf<JsonElement>(JsonObject(mapOf("id" to JsonPrimitive("future")))),
        )

        unknownValues.forEach { values ->
            val result = PlatformRegistryLoader.load(generatedWithUnknownCollection(values))

            assertNull(result.registry)
            assertTrue(ValidationCode.PARSE_ERROR in result.validationReport)
            assertTrue(result.validationReport.issues.any { "Unknown keys: futureCollection" in it.message })
        }
    }

    @Test
    fun generatedStructuralTypeTamperingAlwaysReturnsFailedLoad() {
        val root = generatedRegistryRoot()
        val malformed = linkedMapOf(
            "collections object" to root.replacing("collections", JsonArray(emptyList())),
            "collection array" to root.replacingNested(
                "collections",
                "catalogEntries",
                JsonObject(emptyMap()),
            ),
            "counts object" to root.replacing("counts", JsonArray(emptyList())),
            "count scalar" to root.replacingNested(
                "counts",
                "catalogEntries",
                JsonPrimitive("403"),
            ),
            "digests object" to root.replacing("digests", JsonArray(emptyList())),
            "digest collections object" to root.replacingNested(
                "digests",
                "collections",
                JsonArray(emptyList()),
            ),
            "collection digest scalar" to root.replacingDigestCollection(
                "catalogEntries",
                JsonObject(emptyMap()),
            ),
            "nested core registrations" to root.withWrongCoreRegistrationShape(),
        )

        malformed.forEach { (name, value) ->
            val outcome = runCatching { PlatformRegistryLoader.load(value.toString()) }

            assertTrue("$name unexpectedly threw ${outcome.exceptionOrNull()}", outcome.isSuccess)
            val result = outcome.getOrThrow()
            assertNull("$name loaded a registry", result.registry)
            assertTrue("$name produced no validation errors", result.validationReport.errors.isNotEmpty())
        }
    }

    private fun loadGeneratedRegistry(): PlatformRegistry =
        resource("mimir-platform-registry.v2.json").use { registry ->
            resource("mimir-platform-certification.v1.json").use { certification ->
                PlatformRegistryLoader.load(registry, certification).requireRegistry()
            }
        }

    private fun assertCatalogMapping(
        registry: PlatformRegistry,
        catalogEntryId: String,
        relation: CatalogRelation,
        platformId: String,
    ) {
        val entry = registry.catalogEntry(CatalogEntryId(catalogEntryId))
        assertNotNull(entry)
        assertEquals(relation, entry?.relation)
        assertEquals(PlatformId(platformId), entry?.platformId)
    }

    private fun generatedCatalogRelationCounts(): Map<String, Int> =
        generatedRegistryRoot()
            .getValue("collections")
            .jsonObject
            .getValue("catalogEntries")
            .jsonArray
            .groupingBy { entry -> entry.jsonObject.getValue("relation").jsonPrimitive.content }
            .eachCount()

    private fun generatedWithUnknownCollection(values: List<JsonElement>): String {
        val root = generatedRegistryRoot()
        val futureCollection = JsonArray(values)
        val collections = root.getValue("collections").jsonObject.replacing(
            "futureCollection",
            futureCollection,
        )
        val counts = root.getValue("counts").jsonObject.replacing(
            "futureCollection",
            JsonPrimitive(values.size),
        )
        val digests = root.getValue("digests").jsonObject
        val collectionDigests = digests.getValue("collections").jsonObject.replacing(
            "futureCollection",
            JsonPrimitive(PlatformCanonicalDigest.json(futureCollection.toString())),
        )
        val payloadValues = root.filterKeys { it != "digests" }.toMutableMap().apply {
            this["collections"] = collections
            this["counts"] = counts
        }
        val updatedDigests = digests
            .replacing("collections", collectionDigests)
            .replacing(
                "registryPayloadSha256",
                JsonPrimitive(PlatformCanonicalDigest.json(JsonObject(payloadValues).toString())),
            )
        return root
            .replacing("collections", collections)
            .replacing("counts", counts)
            .replacing("digests", updatedDigests)
            .toString()
    }

    private fun generatedRegistryRoot(): JsonObject =
        resource("mimir-platform-registry.v2.json").use { input ->
            RegistryJson.parseToJsonElement(input.readBytes().toString(Charsets.UTF_8)).jsonObject
        }

    private fun JsonObject.replacing(key: String, value: JsonElement): JsonObject =
        JsonObject(toMutableMap().apply { this[key] = value })

    private fun JsonObject.replacingNested(
        parentKey: String,
        key: String,
        value: JsonElement,
    ): JsonObject = replacing(
        parentKey,
        getValue(parentKey).jsonObject.replacing(key, value),
    )

    private fun JsonObject.replacingDigestCollection(key: String, value: JsonElement): JsonObject {
        val digests = getValue("digests").jsonObject
        val collectionDigests = digests.getValue("collections").jsonObject.replacing(key, value)
        return replacing("digests", digests.replacing("collections", collectionDigests))
    }

    private fun JsonObject.withWrongCoreRegistrationShape(): JsonObject {
        val collections = getValue("collections").jsonObject
        val registrations = collections.getValue("retroArchRegistrations").jsonArray
        val first = registrations.first().jsonObject.replacing(
            "coreRegistrations",
            JsonObject(emptyMap()),
        )
        val altered = JsonArray(listOf(first) + registrations.drop(1))
        return replacing("collections", collections.replacing("retroArchRegistrations", altered))
    }

    private fun resource(name: String) = requireNotNull(javaClass.classLoader.getResourceAsStream(name)) {
        "Missing test resource $name"
    }
}
