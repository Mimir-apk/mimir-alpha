package com.gruning.mimir.rewrite.core.tweaker

import android.os.Bundle
import com.gruning.mimir.tweaker.TweakerCapabilityContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Client half of the capabilities Bundle contract. See
 * `HardwareCapabilitiesBundleContractTest` in the daemon build for the other half and the why.
 *
 * The daemon cannot be linked into this JVM, so instead of comparing the two implementations
 * directly, each is held to [TweakerCapabilityContract] in the `:contract` module both builds
 * share. This side proves the real parser actually CONSUMES every key the contract says it must:
 * for each one, dropping just that key from an otherwise complete bundle has to change the parsed
 * result. A client read deleted or renamed stops changing anything and reddens here.
 *
 * That is the check that was missing when the daemon began shipping
 * `supportedUncertifiedProfileIds` and the client never read it: both sides' suites were green
 * against their own fixtures, and the field was simply dropped on the floor.
 */
@RunWith(RobolectricTestRunner::class)
class TweakerCapabilitiesBundleContractTest {

    @Test
    fun `every contract key the client must consume actually changes the parse`() {
        val inert = TweakerCapabilityContract.CONSUMED_BY_CLIENT.filter { key -> !mattersAnywhere(key) }

        assertEquals(
            "contract says the client consumes these keys, but dropping them changed nothing in " +
                "any context -- either the client stopped reading them or the contract is wrong",
            emptyList<String>(),
            inert,
        )
    }

    @Test
    fun `keys the contract marks unread really are unread`() {
        // If one of these starts mattering, it has been wired up and belongs in
        // CONSUMED_BY_CLIENT -- being read while declared unread is the same drift in the less
        // obvious direction.
        val nowConsumed = TweakerCapabilityContract.NOT_CONSUMED_BY_CLIENT.filter { key -> mattersAnywhere(key) }

        assertEquals(
            "these are declared NOT_CONSUMED_BY_CLIENT but now affect the parse; move them to " +
                "CONSUMED_BY_CLIENT",
            emptyList<String>(),
            nowConsumed,
        )
    }

    /**
     * "Consumed" means there EXISTS a bundle where dropping the key changes the parse. One fixture
     * cannot show that for every key, because several are inert outside their own context: the
     * diagnostic fields do nothing outside LAB, the certified lists are gated behind
     * `allowsProductTunableProfiles()` which LAB switches off, and `hasUsageAccessGranted` only
     * discriminates when it is false — that is the flag distinguishing "usage access unknown" from
     * "denied". Probing one posture would have reported four live reads as dead.
     */
    private fun mattersAnywhere(key: String): Boolean = contexts().any { bundle ->
        parseTweakerCapabilitiesBundle(bundle.withoutKey(key)) !=
            parseTweakerCapabilitiesBundle(bundle)
    }

    private fun contexts(): List<Bundle> = listOf(
        completeCapabilitiesBundle(posture = "PRODUCTION"),
        completeCapabilitiesBundle(posture = "LAB"),
        completeCapabilitiesBundle(posture = "PRODUCTION", usageAccessKnown = false),
    )

    @Test
    fun `an empty bundle parses without throwing and offers nothing`() {
        // The daemon can return an empty bundle on a dead binder, so the parser must degrade
        // rather than crash, and must not invent availability out of missing fields.
        val parsed = parseTweakerCapabilitiesBundle(Bundle())

        assertEquals(emptySet<String>(), parsed.certifiedProfileIds)
        assertEquals(emptySet<String>(), parsed.supportedUncertifiedProfileIds)
        assertNotEquals(true, parsed.mutationAvailable)
    }

    private fun Bundle.withoutKey(key: String): Bundle = Bundle(this).apply { remove(key) }

    /**
     * Every contract key present with a value that is distinguishable from both the type default
     * and from the other keys' values -- otherwise "dropping this key changed nothing" would be an
     * artifact of the fixture rather than a fact about the parser. Profile ids must be real
     * qualifiable ids or the parser filters them away to the same empty set.
     */
    private fun completeCapabilitiesBundle(
        posture: String,
        usageAccessKnown: Boolean = true,
    ): Bundle = Bundle().apply {
        putString(TweakerCapabilityContract.STATUS, "SUPPORTED")
        putString(TweakerCapabilityContract.MANUFACTURER, "AYN")
        putString(TweakerCapabilityContract.MODEL, "Odin3")
        putString(TweakerCapabilityContract.SOC_MODEL, "test-soc")
        putString(TweakerCapabilityContract.BUILD_FINGERPRINT, "test/fingerprint")
        putString(TweakerCapabilityContract.HARDWARE_FINGERPRINT, "fingerprint-abc")
        putString(TweakerCapabilityContract.DEVICE_PROFILE_ID, "odin3")
        putString(TweakerCapabilityContract.HARDWARE_FAMILY_ID, "family-x")
        // Vocabulary the parser actually accepts, not the enum spelling.
        putString(TweakerCapabilityContract.PROFILE_TREATMENT, "mimir-certified-reference")
        putStringArrayList(TweakerCapabilityContract.SUPPORTED_AXES, arrayListOf("CPU", "GPU", "FAN"))
        putBoolean(TweakerCapabilityContract.PSERVER_AVAILABLE, true)
        putBoolean(TweakerCapabilityContract.GPU_NODE_AVAILABLE, true)
        putBoolean(TweakerCapabilityContract.FAN_NODE_AVAILABLE, true)
        putIntegerArrayList(TweakerCapabilityContract.REFRESH_RATES_HZ, arrayListOf(60, 120))
        putStringArrayList(TweakerCapabilityContract.CPU_POLICIES, arrayListOf("policy0 cpus=0,1,2"))
        putStringArrayList(TweakerCapabilityContract.NOTES, arrayListOf("contract fixture"))
        // Distinct ids so dropping either list is visible in the merged certified set.
        putStringArrayList(TweakerCapabilityContract.CERTIFIED_PROFILE_IDS, arrayListOf("performance"))
        putStringArrayList(TweakerCapabilityContract.QUALIFIED_PROFILE_IDS, arrayListOf("quiet"))
        putStringArrayList(
            TweakerCapabilityContract.SUPPORTED_UNCERTIFIED_PROFILE_IDS,
            arrayListOf("balanced"),
        )
        putString(TweakerCapabilityContract.POSTURE, posture)
        putBoolean(TweakerCapabilityContract.MUTATION_AVAILABLE, true)
        putBoolean(TweakerCapabilityContract.DRY_RUN, false)
        putString(TweakerCapabilityContract.QUALIFICATION_SOURCE, "SIGNED_MANIFEST")
        putStringArrayList(TweakerCapabilityContract.DIAGNOSTIC_PROFILE_IDS, arrayListOf("balanced"))
        putBoolean(TweakerCapabilityContract.DIAGNOSTIC_MUTATION_AVAILABLE, true)
        putBoolean(TweakerCapabilityContract.USAGE_ACCESS_GRANTED, true)
        putBoolean(TweakerCapabilityContract.HAS_USAGE_ACCESS_GRANTED, usageAccessKnown)
    }
}
