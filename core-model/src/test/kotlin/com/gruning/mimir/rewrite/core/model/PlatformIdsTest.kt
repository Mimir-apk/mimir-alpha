package com.gruning.mimir.rewrite.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PlatformIdsTest {

    @Test
    fun platformKernelIdsRejectBlankValues() {
        val constructors = listOf<(String) -> Any>(
            ::PlatformDefinitionVersion,
            ::RegistryRevision,
            ::IdMapVersion,
            ::CatalogEntryId,
            ::ContentProfileId,
            ::ImportBindingId,
            ::RetroArchRegistrationId,
            ::EmulatorFamilyId,
            ::LaunchRouteId,
            ::PackageVariantId,
            ::CoreArtifactEnvelopeId,
            ::DeviceEnvelopeId,
            ::FirmwarePolicyId,
            ::VisualAssetKey,
            ::SupportSliceId,
            ::SourceRootId,
            ::ContentMemberId,
        )

        constructors.forEach { constructor ->
            assertThrows(IllegalArgumentException::class.java) { constructor("   ") }
        }
    }

    @Test
    fun namespacesRemainDistinctAtCompileTimeAndStableAtRuntime() {
        assertEquals("snk_neo_geo", PlatformId("snk_neo_geo").value)
        assertEquals("neo_geo", RetroArchRegistrationId("neo_geo").value)
        assertEquals("snk_neo_geo", RetroArchRegistrationId("snk_neo_geo").value)
        assertEquals("shipping-runtime-v1", IdMapVersion("shipping-runtime-v1").value)
    }
}

