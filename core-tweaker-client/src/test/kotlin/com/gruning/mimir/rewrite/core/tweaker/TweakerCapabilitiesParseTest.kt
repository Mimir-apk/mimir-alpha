package com.gruning.mimir.rewrite.core.tweaker

import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class TweakerCapabilitiesParseTest {
    @Test
    fun `safe local certified injection cannot expose or select profiles`() {
        val caps = parseTweakerCapabilitiesBundle(
            bundle(
                posture = "SAFE",
                mutationAvailable = false,
                qualificationSource = "LOCAL_REPORT",
                certified = listOf("quiet", "balanced", "performance"),
            ),
        )
        assertEquals(TweakerPosture.SAFE, caps.posture)
        assertEquals(TweakerQualificationSource.LOCAL_REPORT, caps.qualificationSource)
        assertTrue(caps.certifiedProfileIds.isEmpty())
        assertFalse(caps.allowsProductTunableProfiles())
        assertEquals(listOf("stock"), selectableTweakerProfileIds(caps))
    }

    @Test
    fun `lab posture never exposes product tunable profiles`() {
        val caps = parseTweakerCapabilitiesBundle(
            bundle(
                posture = "LAB",
                mutationAvailable = true,
                qualificationSource = "LOCAL_REPORT",
                certified = listOf("performance"),
            ),
        )
        assertTrue(caps.certifiedProfileIds.isEmpty())
        assertEquals(listOf("stock"), selectableTweakerProfileIds(caps))
    }

    @Test
    fun `production without signed authority stays stock only`() {
        val caps = parseTweakerCapabilitiesBundle(
            bundle(
                posture = "PRODUCTION",
                mutationAvailable = true,
                qualificationSource = "SIGNED_MANIFEST",
                certified = emptyList(),
            ),
        )
        assertFalse(caps.allowsProductTunableProfiles())
        assertEquals(listOf("stock"), selectableTweakerProfileIds(caps))
    }

    @Test
    fun `production signed gate accepts quiet balanced and performance`() {
        val caps = parseTweakerCapabilitiesBundle(
            bundle(
                posture = "PRODUCTION",
                mutationAvailable = true,
                qualificationSource = "SIGNED_MANIFEST",
                certified = listOf("quiet", "balanced", "performance", "bogus"),
            ),
        )
        assertEquals(setOf("quiet", "balanced", "performance"), caps.certifiedProfileIds)
        assertEquals(listOf("stock", "quiet", "balanced", "performance"), selectableTweakerProfileIds(caps))
    }

    @Test
    fun `unreachable production capability never exposes signed profiles`() {
        val caps = parseTweakerCapabilitiesBundle(
            bundle(
                posture = "PRODUCTION",
                mutationAvailable = true,
                qualificationSource = "SIGNED_MANIFEST",
                certified = listOf("quiet", "balanced", "performance"),
            ).apply { putString("status", "UNSUPPORTED") },
        )

        assertFalse(caps.allowsProductTunableProfiles())
        assertEquals(listOf("stock"), selectableTweakerProfileIds(caps))
    }

    @Test
    fun `unknown posture and source fail closed`() {
        val caps = parseTweakerCapabilitiesBundle(
            bundle(
                posture = "WEIRD",
                mutationAvailable = true,
                qualificationSource = "MAGIC",
                certified = listOf("performance"),
            ),
        )
        assertEquals(TweakerPosture.UNKNOWN, caps.posture)
        assertEquals(TweakerQualificationSource.UNKNOWN, caps.qualificationSource)
        assertTrue(caps.certifiedProfileIds.isEmpty())
        assertEquals(listOf("stock"), selectableTweakerProfileIds(caps))
    }

    @Test
    fun `missing mutationAvailable fails closed`() {
        val bundle = Bundle().apply {
            putString("status", "CALIBRATION_REQUIRED")
            putString("posture", "PRODUCTION")
            putString("qualificationSource", "SIGNED_MANIFEST")
            putStringArrayList("certifiedProfileIds", ArrayList(listOf("performance")))
        }
        val caps = parseTweakerCapabilitiesBundle(bundle)
        assertNull(caps.mutationAvailable)
        assertFalse(caps.allowsProductTunableProfiles())
        assertTrue(caps.certifiedProfileIds.isEmpty())
    }

    @Test
    fun `missing usage access stays non-authoritative`() {
        val bundle = Bundle().apply {
            putString("status", "SUPPORTED")
            putString("posture", "SAFE")
            putString("qualificationSource", "NONE")
            putBoolean("mutationAvailable", false)
            putBoolean("hasUsageAccessGranted", false)
        }
        assertNull(parseTweakerCapabilitiesBundle(bundle).usageAccessGranted)
    }

    @Test
    fun `profile treatment is informational and parsed separately from certification`() {
        val bundle = bundle(
            posture = "SAFE",
            mutationAvailable = false,
            qualificationSource = "NONE",
            certified = emptyList(),
        ).apply {
            putString("hardwareFamilyId", "qualcomm-sm8250")
            putString("profileTreatment", "mimir-certified-reference")
        }

        val caps = parseTweakerCapabilitiesBundle(bundle)

        assertEquals("qualcomm-sm8250", caps.hardwareFamilyId)
        assertEquals(TweakerProfileTreatment.MIMIR_CERTIFIED_REFERENCE, caps.profileTreatment)
        assertTrue(caps.certifiedProfileIds.isEmpty())
        assertEquals(listOf("stock"), selectableTweakerProfileIds(caps))
    }

    /**
     * **Stage-1's scope boundary was deliberately retired here, not accidentally broken.**
     *
     * This formerly asserted `selectableTweakerProfileIds == [stock]` on a PRODUCTION device
     * with uncertified ids. Supported chipset families now expose selectable profiles derived from
     * live OPP tables, so that informational-only boundary is retired.
     *
     * Everything the test guarded APART from that boundary is kept and strengthened: the ids are
     * still filtered to the qualifiable set, `stock` and junk are still dropped, the certified set
     * is still never merged into -- and the widening is now shown to be conditional, by running the
     * identical fixture through both mutation states.
     */
    @Test
    fun `supported-uncertified profile ids are parsed, gated to the qualifiable set, and never merged into certified`() {
        fun bundleWith(mutationAvailable: Boolean) = bundle(
            posture = "PRODUCTION",
            mutationAvailable = mutationAvailable,
            qualificationSource = "NONE",
            certified = emptyList(),
        ).apply {
            putStringArrayList(
                "supportedUncertifiedProfileIds",
                ArrayList(listOf("quiet", "balanced", "performance", "stock", "unknown-id")),
            )
        }

        // Stage 3: with the daemon reporting it can mutate, the three derived ids become
        // selectable -- and `stock`/`unknown-id` are still filtered out of the uncertified set.
        val enabled = parseTweakerCapabilitiesBundle(bundleWith(mutationAvailable = true))
        assertEquals(setOf("quiet", "balanced", "performance"), enabled.supportedUncertifiedProfileIds)
        assertTrue("uncertified ids must never leak into certified", enabled.certifiedProfileIds.isEmpty())
        assertFalse(
            "an uncertified device must never satisfy the certified predicate",
            enabled.allowsProductTunableProfiles(),
        )
        assertEquals(
            listOf("stock", "quiet", "balanced", "performance"),
            selectableTweakerProfileIds(enabled),
        )

        // Fail-closed, unchanged: without the daemon's mutation truth the identical bundle exposes
        // nothing at all -- not the ids, not the selection.
        val gated = parseTweakerCapabilitiesBundle(bundleWith(mutationAvailable = false))
        assertEquals(emptySet<String>(), gated.supportedUncertifiedProfileIds)
        assertTrue(gated.certifiedProfileIds.isEmpty())
        assertEquals(listOf("stock"), selectableTweakerProfileIds(gated))
    }

    @Test
    fun `missing supportedUncertifiedProfileIds parses to empty, not null or a crash`() {
        val bundle = bundle(
            posture = "SAFE",
            mutationAvailable = false,
            qualificationSource = "NONE",
            certified = emptyList(),
        )

        assertTrue(parseTweakerCapabilitiesBundle(bundle).supportedUncertifiedProfileIds.isEmpty())
    }

    private fun bundle(
        posture: String,
        mutationAvailable: Boolean,
        qualificationSource: String,
        certified: List<String>,
    ) = Bundle().apply {
        putString("status", "CALIBRATION_REQUIRED")
        putString("posture", posture)
        putBoolean("mutationAvailable", mutationAvailable)
        putBoolean("dryRun", !mutationAvailable)
        putString("qualificationSource", qualificationSource)
        putStringArrayList("certifiedProfileIds", ArrayList(certified))
        putBoolean("hasUsageAccessGranted", true)
        putBoolean("usageAccessGranted", true)
    }
}
