package com.gruning.mimir.rewrite.core.platform

import com.gruning.mimir.rewrite.core.model.ContentProfileId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.RetroArchRegistrationId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformRegistryNamespaceTest {
    @Test
    fun sameScopedAliasCannotTargetTwoPlatforms() {
        val first = testPlatform(
            id = "first",
            aliases = listOf(alias(IdentityNamespace.REWRITE_LEGACY, "shared", "first")),
        )
        val second = testPlatform(
            id = "second",
            aliases = listOf(alias(IdentityNamespace.REWRITE_LEGACY, "shared", "second")),
        )

        val result = ImmutablePlatformRegistry.create(testDocument(listOf(first, second)))

        assertNull(result.registry)
        assertTrue(ValidationCode.ALIAS_COLLISION in result.validationReport)
    }

    @Test
    fun blankAndWhitespaceAliasesAreRejected() {
        listOf("", " \t ").forEach { value ->
            val platform = testPlatform(
                id = "alpha",
                aliases = listOf(alias(IdentityNamespace.REWRITE_LEGACY, value, "alpha")),
            )

            val result = ImmutablePlatformRegistry.create(testDocument(listOf(platform)))

            assertNull(result.registry)
            assertTrue(ValidationCode.BLANK_FIELD in result.validationReport)
        }
    }

    @Test
    fun canonicalizationRejectsBlankAndNormalizationEmptyInput() {
        val platform = testPlatform(
            id = "alpha",
            aliases = listOf(alias(IdentityNamespace.REWRITE_LEGACY, "old_alpha", "alpha")),
        )
        val registry = ImmutablePlatformRegistry.create(testDocument(listOf(platform))).requireRegistry()

        assertNull(registry.canonicalize(IdentityNamespace.REWRITE_LEGACY, ""))
        assertNull(registry.canonicalize(IdentityNamespace.REWRITE_LEGACY, " \t "))
        assertNull(registry.canonicalize(IdentityNamespace.REWRITE_LEGACY, "---"))
    }

    @Test
    fun normalizedFolderCollisionRequiresExplicitAmbiguityGroup() {
        val first = testPlatform("first", folder = "shared")
        val second = testPlatform("second", folder = "shared")

        val result = ImmutablePlatformRegistry.create(testDocument(listOf(first, second)))

        assertNull(result.registry)
        assertTrue(ValidationCode.FOLDER_COLLISION in result.validationReport)
    }

    @Test
    fun searchAndExtensionLookupAreOneToManyButCanonicalizationIsNot() {
        val profileOne = testProfile("profile_one", "console_one", listOf("iso"))
        val profileTwo = testProfile("profile_two", "console_two", listOf("iso"))
        val first = testPlatform(
            id = "console_one",
            name = "PlayStation One",
            manufacturer = "Sony",
            contentProfileIds = listOf(profileOne.id),
        )
        val second = testPlatform(
            id = "console_two",
            name = "PlayStation Two",
            manufacturer = "Sony",
            contentProfileIds = listOf(profileTwo.id),
        )
        val document = testDocument(
            platforms = listOf(first, second),
            contentProfiles = listOf(profileOne, profileTwo),
            importBindings = listOf(
                testBinding("binding_one", "console_one", profileOne.id.value),
                testBinding("binding_two", "console_two", profileTwo.id.value),
            ),
        )
        val registry = ImmutablePlatformRegistry.create(document).requireRegistry()

        assertEquals(
            setOf(PlatformId("console_one"), PlatformId("console_two")),
            registry.search("Sony").map { it.platformId }.toSet(),
        )
        assertEquals(
            listOf(PlatformId("console_one"), PlatformId("console_two")),
            registry.extensionCandidates(".ISO"),
        )
        assertNull(registry.canonicalize(IdentityNamespace.REWRITE_LEGACY, "sony"))
    }

    @Test
    fun wiiRuntimeAliasCanonicalizesToCatalogLongId() {
        val wii = testPlatform(
            id = "nintendo_wii",
            name = "Wii",
            aliases = listOf(
                alias(IdentityNamespace.SHIPPING_RUNTIME, "wii", "nintendo_wii"),
                alias(IdentityNamespace.REWRITE_LEGACY, "wii", "nintendo_wii"),
                alias(IdentityNamespace.SHIPPING_CATALOG, "nintendo_wii", "nintendo_wii"),
            ),
        )
        val registry = ImmutablePlatformRegistry.create(testDocument(listOf(wii))).requireRegistry()

        assertEquals(
            PlatformId("nintendo_wii"),
            registry.canonicalize(IdentityNamespace.SHIPPING_RUNTIME, " Wii "),
        )
        assertEquals(
            PlatformId("nintendo_wii"),
            registry.canonicalize(IdentityNamespace.REWRITE_LEGACY, "wii"),
        )
        assertNull(registry.canonicalize(IdentityNamespace.SHIPPING_CATALOG, "wii"))
    }

    @Test
    fun neoGeoPlatformAndRegistrationNamespacesRemainDistinct() {
        val neoGeoId = PlatformId("snk_neo_geo")
        val firstProfile = ContentProfileDefinition(
            id = ContentProfileId("neo_geo_profile"),
            platformId = neoGeoId,
            parserVersion = "source-gap",
            discoveryStatus = ContentProfileStatus.GAP,
        )
        val secondProfile = firstProfile.copy(id = ContentProfileId("snk_neo_geo_profile"))
        val platform = testPlatform(
            id = neoGeoId.value,
            name = "Neo Geo",
            contentProfileIds = listOf(firstProfile.id, secondProfile.id),
        )
        fun registration(id: String, profileId: ContentProfileId) = RetroArchRegistrationDefinition(
            id = RetroArchRegistrationId(id),
            platformId = neoGeoId,
            contentProfileId = profileId,
            label = "Neo Geo",
            coreRegistrations = listOf(
                RetroArchCoreRegistrationDefinition("core:$id", "fbneo", "FBNeo", true),
            ),
            recommendedCore = "fbneo",
        )
        val document = testDocument(
            platforms = listOf(platform),
            contentProfiles = listOf(firstProfile, secondProfile),
            retroArchRegistrations = listOf(
                registration("neo_geo", firstProfile.id),
                registration("snk_neo_geo", secondProfile.id),
            ),
        )
        val result = ImmutablePlatformRegistry.create(document)
        val registry = result.requireRegistry()

        assertFalse(result.validationReport.issues.any { it.code == ValidationCode.DUPLICATE_ID })
        assertNotNull(registry.retroArchRegistration(RetroArchRegistrationId("neo_geo")))
        assertNotNull(registry.retroArchRegistration(RetroArchRegistrationId("snk_neo_geo")))
        assertEquals(
            neoGeoId,
            registry.canonicalize(IdentityNamespace.RETROARCH_REGISTRATION, "neo_geo"),
        )
        assertEquals(
            neoGeoId,
            registry.canonicalize(IdentityNamespace.RETROARCH_REGISTRATION, "snk_neo_geo"),
        )
    }
}
