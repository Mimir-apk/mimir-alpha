package com.gruning.mimir.rewrite.core.platform

import com.gruning.mimir.rewrite.core.model.PlatformId
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RegistrySerializationTest {
    @Test
    fun flatKernelDocumentsRoundTripThroughStringLoader() {
        val fixture = certifiedFixture()
        val certificate = playCertificate(
            fixture.document,
            fixture.playSlice,
            fixture.runtimeEnvelope,
        )
        val certification = certificationDocument(fixture.document, listOf(certificate))

        val result = PlatformRegistryLoader.load(
            registryJson = RegistryJson.encodeToString(fixture.document),
            certificationJson = RegistryJson.encodeToString(certification),
        )

        assertTrue(result.validationReport.isValid)
        assertEquals(
            fixture.runtimeEnvelope,
            result.requireRegistry().certifiedSlices(PlatformId("console_a")).single().runtimeEnvelopeKey,
        )
    }

    @Test
    fun malformedCertificateFailsClosedButLeavesDescriptiveRegistryAvailable() {
        val document = testDocument(listOf(testPlatform("alpha")))

        val result = PlatformRegistryLoader.load(
            registryJson = RegistryJson.encodeToString(document),
            certificationJson = """{"certifications":"not-a-list"}""",
        )

        val registry = result.requireRegistry()
        assertNotNull(registry.definition(PlatformId("alpha")))
        assertNull(registry.activation(PlatformId("alpha")))
        assertTrue(ValidationCode.PARSE_ERROR in result.validationReport)
    }

    @Test
    fun registrySnapshotsCallerOwnedCollectionsBeforeIndexing() {
        val aliases = mutableListOf(alias(IdentityNamespace.REWRITE_LEGACY, "old_alpha", "alpha"))
        val platform = testPlatform("alpha", aliases = aliases)
        val registry = ImmutablePlatformRegistry.create(testDocument(listOf(platform))).requireRegistry()

        aliases.clear()

        assertEquals(
            PlatformId("alpha"),
            registry.canonicalize(IdentityNamespace.REWRITE_LEGACY, "old_alpha"),
        )
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (registry.platforms() as MutableList<PlatformDefinition>).add(testPlatform("beta"))
        }
    }
}
