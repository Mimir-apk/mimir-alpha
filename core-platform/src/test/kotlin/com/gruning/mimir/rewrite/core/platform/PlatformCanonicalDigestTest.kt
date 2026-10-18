package com.gruning.mimir.rewrite.core.platform

import com.gruning.mimir.rewrite.core.model.VisualAssetKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformCanonicalDigestTest {
    @Test
    fun canonicalJsonIgnoresObjectPropertyOrder() {
        val first = """{"z":[3,2,1],"a":{"y":true,"x":"value"}}"""
        val second = """{"a":{"x":"value","y":true},"z":[3,2,1]}"""

        assertEquals(PlatformCanonicalDigest.json(first), PlatformCanonicalDigest.json(second))
    }

    @Test
    fun registryDigestIsStableAcrossTopLevelRecordOrder() {
        val first = testDocument(
            platforms = listOf(testPlatform("alpha"), testPlatform("beta")),
        )
        val second = first.copy(platforms = first.platforms.reversed())

        assertEquals(
            PlatformCanonicalDigest.registry(first),
            PlatformCanonicalDigest.registry(second),
        )
    }

    @Test
    fun declaredCanonicalDigestIsValidated() {
        val unsigned = testDocument(listOf(testPlatform("alpha")))
        val digest = PlatformCanonicalDigest.registry(unsigned)

        val valid = ImmutablePlatformRegistry.create(unsigned.copy(declaredCanonicalDigest = digest))
        val invalid = ImmutablePlatformRegistry.create(unsigned.copy(declaredCanonicalDigest = SHA_A))

        assertNotNull(valid.registry)
        assertNull(invalid.registry)
        assertTrue(ValidationCode.REGISTRY_DIGEST_MISMATCH in invalid.validationReport)
    }

    @Test
    fun supportSliceDigestIncludesProfileParserButNotUnrelatedCatalogArt() {
        val fixture = certifiedFixture()
        val visualOnlyEdit = fixture.document.copy(
            visualAssets = listOf(
                VisualAssetDefinition(
                    key = VisualAssetKey("unrelated-art"),
                    role = VisualAssetRole.PLATFORM_LOGO,
                    resourceName = "art/other.svg",
                    provenance = "test",
                ),
            ),
        )
        val parserEdit = fixture.document.copy(
            contentProfiles = fixture.document.contentProfiles.map { profile ->
                if (profile.id == fixture.manageSliceA.contentProfileId) {
                    profile.copy(parserVersion = "parser-2")
                } else {
                    profile
                }
            },
        )

        val original = PlatformCanonicalDigest.supportSlice(fixture.document, fixture.manageSliceA.id)
        assertEquals(original, PlatformCanonicalDigest.supportSlice(visualOnlyEdit, fixture.manageSliceA.id))
        assertNotEquals(original, PlatformCanonicalDigest.supportSlice(parserEdit, fixture.manageSliceA.id))
    }
}
