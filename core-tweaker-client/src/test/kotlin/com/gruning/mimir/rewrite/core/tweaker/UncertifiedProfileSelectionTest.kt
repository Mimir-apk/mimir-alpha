package com.gruning.mimir.rewrite.core.tweaker

import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Chipset-support stage 3, client half: an uncertified profile becomes selectable and is honoured
 * exactly like a certified one. retired the on-screen "· UNCERTIFIED"
 * qualifier this file used to pin as present -- the row and toast now say nothing about
 * certification status at all, on either device.
 *
 * The daemon half lives in `mimir-tweaker`'s `UncertifiedProfileApplyAdmissionTest`. The two builds
 * are separate Gradle builds with no shared test runtime, so the "selectable implies appliable"
 * invariant cannot be one test. It is enforced as a mirrored pair,
 * the same pattern the existing bundle-contract tests already use: the daemon asserts that exactly
 * the ids it publishes as uncertified are the ids its apply gate admits, and this file asserts that
 * exactly the ids the bundle publishes are the ids the client offers.
 *
 * The fixtures below are the two real devices, from `MimirTweakerIdentity` lines captured
 * 2026-08-22 -- the Nova (test unit, no manifest) and the Odin (`test device`, signed).
 */
@RunWith(RobolectricTestRunner::class)
class UncertifiedProfileSelectionTest {

    private val allThree = arrayListOf("quiet", "balanced", "performance")

    /**
     * The Nova as its daemon reports it: PRODUCTION, transport alive, no signed manifest, three
     * chipset-derived ids. `qualificationSource` is deliberately NONE -- that is the entire point.
     */
    private fun novaBundle(
        uncertified: List<String> = allThree,
        certified: List<String> = emptyList(),
        posture: String = "PRODUCTION",
        qualificationSource: String = "NONE",
        mutationAvailable: Boolean = true,
        status: String = "CALIBRATION_REQUIRED",
    ): Bundle = Bundle().apply {
        putString("status", status)
        putString("deviceProfileId", "retroid-qcs8550-pserver")
        putString("hardwareFingerprint", "84fe4155de40b3f8c721c9362d415b723cb3fd2b669dba9b240266f8a1ebe565")
        putStringArrayList("supportedAxes", arrayListOf("CPU", "GPU"))
        putStringArrayList("certifiedProfileIds", ArrayList(certified))
        putStringArrayList("supportedUncertifiedProfileIds", ArrayList(uncertified))
        putStringArrayList("notes", arrayListOf())
        putString("posture", posture)
        putBoolean("mutationAvailable", mutationAvailable)
        putBoolean("dryRun", false)
        putBoolean("usageAccessGranted", true)
        putString("qualificationSource", qualificationSource)
        putString("hardwareFamilyId", "qualcomm-8gen2")
        putString("profileTreatment", "MIMIR_CERTIFIED_REFERENCE")
    }

    /** The Odin: signed manifest, certified ids, and (per the daemon) a now-empty uncertified set. */
    private fun odinBundle(uncertified: List<String> = emptyList()): Bundle = Bundle().apply {
        putString("status", "SUPPORTED")
        putString("deviceProfileId", "ayn-odin3-cq8725s-pserver")
        putString("hardwareFingerprint", "ece78ce6cbe6fa40a6ad8cb5fe5acb1257a2549a4fa5f10926338cdb5d9b65b2")
        putStringArrayList("supportedAxes", arrayListOf("CPU", "GPU", "FAN"))
        putStringArrayList("certifiedProfileIds", ArrayList(allThree))
        putStringArrayList("supportedUncertifiedProfileIds", ArrayList(uncertified))
        putStringArrayList("notes", arrayListOf())
        putString("posture", "PRODUCTION")
        putBoolean("mutationAvailable", true)
        putBoolean("dryRun", false)
        putBoolean("usageAccessGranted", true)
        putString("qualificationSource", "SIGNED_MANIFEST")
        putString("hardwareFamilyId", "qualcomm-8-elite")
        putString("profileTreatment", "MIMIR_CERTIFIED_REFERENCE")
    }

    private fun rowFor(caps: TweakerCapabilities, selected: String) = TweakerUtilityRowState(
        availability = tweakerUtilityAvailability(installed = true, capabilities = caps),
        selectedProfileId = selected,
        selectableProfileIds = selectableTweakerProfileIds(caps),
    )

    // ── Nova profile-selection regression ─────────────────────────────────────────────────────

    @Test
    fun `the Nova can now select all three profiles`() {
        val caps = parseTweakerCapabilitiesBundle(novaBundle())

        assertTrue(caps.allowsUncertifiedTunableProfiles())
        assertFalse(
            "an uncertified device must never satisfy the certified predicate",
            caps.allowsProductTunableProfiles(),
        )
        assertEquals(
            listOf("stock", "quiet", "balanced", "performance"),
            selectableTweakerProfileIds(caps),
        )
        assertEquals(
            TweakerUtilityAvailability.READY,
            tweakerUtilityAvailability(installed = true, capabilities = caps),
        )
    }

    /**
     * the row/toast must never say "certified" or "uncertified", and a
     * daemon-admitted profile must be honoured exactly like any other. Converted from the earlier
     * assertion that the row DID carry an "· UNCERTIFIED" qualifier (`TWEAKER_UTILITY_UNCERTIFIED_
     * QUALIFIER_CAPS` and `TweakerUtilityStepOutcome.Changed.uncertified`, both since deleted): the
     * Nova's balanced pick must now read byte-identical to how the Odin's signed balanced pick reads.
     */
    @Test
    fun `a selected chipset-supported profile reads exactly like a signed one`() {
        val caps = parseTweakerCapabilitiesBundle(novaBundle())
        val row = rowFor(caps, "balanced")

        assertEquals(tweakerProfileDisplayCaps("balanced"), row.valueLabelCaps)

        val outcome = row.stepOutcome(1)
        assertTrue(outcome is TweakerUtilityStepOutcome.Changed)
        assertEquals("TWEAKER · ${tweakerProfileDisplayCaps("performance")}", outcome.utilityToastCaps())
    }

    @Test
    fun `stock on the Nova is still exactly STOCK`() {
        val caps = parseTweakerCapabilitiesBundle(novaBundle())
        val row = rowFor(caps, "stock")
        assertEquals("STOCK", row.valueLabelCaps)
    }

    // ── The Odin must not move ────────────────────────────────────────────────────────────────

    /**
     * A/B against the Odin's real device state, captured 2026-08-22:
     * `pair=ELIGIBLE availability=READY selected=balanced profiles=[stock, quiet, balanced, performance]`.
     */
    @Test
    fun `the Odin's selectable set, availability and label are all unmoved`() {
        val caps = parseTweakerCapabilitiesBundle(odinBundle())

        assertTrue(caps.allowsProductTunableProfiles())
        assertFalse(
            "a fully certified device must not also claim uncertified support",
            caps.allowsUncertifiedTunableProfiles(),
        )
        assertEquals(
            listOf("stock", "quiet", "balanced", "performance"),
            selectableTweakerProfileIds(caps),
        )

        val row = rowFor(caps, "balanced")
        assertEquals(TweakerUtilityAvailability.READY, row.availability)
        assertEquals(
            "the Odin's row value must be exactly the profile name, with no qualifier",
            tweakerProfileDisplayCaps("balanced"),
            row.valueLabelCaps,
        )
        assertEquals(
            "TWEAKER · ${tweakerProfileDisplayCaps("performance")}",
            row.stepOutcome(1).utilityToastCaps(),
        )
    }

    /**
     * The disjointness guard. If a daemon ever published an id in BOTH buckets -- which the Odin's
     * own probe genuinely does before the service subtracts, so this is not hypothetical -- the
     * client must resolve it in favour of certified and must never label a signed profile
     * "UNCERTIFIED".
     */
    @Test
    fun `an id in both buckets is treated as certified, never labelled uncertified`() {
        val caps = parseTweakerCapabilitiesBundle(odinBundle(uncertified = allThree))

        assertEquals(
            "the uncertified set must be emptied by the certified subtraction",
            emptySet<String>(),
            caps.supportedUncertifiedProfileIds,
        )
        assertEquals(
            listOf("stock", "quiet", "balanced", "performance"),
            selectableTweakerProfileIds(caps),
        )
        val row = rowFor(caps, "balanced")
        assertEquals(tweakerProfileDisplayCaps("balanced"), row.valueLabelCaps)
    }

    // ── Fail-closed: every leg refuses on its own ─────────────────────────────────────────────

    @Test
    fun `uncertified selection fails closed on status, mutation and an empty set -- but never on posture`() {
        /**
     * Build posture and signed qualification do not gate selectable tunable profiles here. A Thor
     * daemon built as LAB had recognized the family, read three live CPU policies, confirmed PServer
     * and derived all three profiles from the device's OPP tables, but the client discarded them
     * because of the build flavor. A build flavor does not establish hardware capability.
     * Hardware facts and the daemon's ability to act remain separate fail-closed gates.
     */
        val cases = mapOf(
            "mutation unavailable" to novaBundle(mutationAvailable = false),
            "UNSUPPORTED hardware" to novaBundle(status = "UNSUPPORTED"),
            "UNAVAILABLE transport" to novaBundle(status = "UNAVAILABLE"),
            "empty uncertified set" to novaBundle(uncertified = emptyList()),
        )
        cases.forEach { (label, bundle) ->
            val caps = parseTweakerCapabilitiesBundle(bundle)
            assertFalse("$label must not allow uncertified tuning", caps.allowsUncertifiedTunableProfiles())
            assertEquals(
                "$label must collapse to Stock only",
                listOf("stock"),
                selectableTweakerProfileIds(caps),
            )
            assertEquals(
                "$label must expose no uncertified ids at all",
                emptySet<String>(),
                caps.supportedUncertifiedProfileIds,
            )
        }
    }

    @Test
    fun `a LAB daemon that derived real profiles is admitted, because posture is not hardware`() {
        // The exact shape measured on a measured AYN Thor, 2026-08-31, with the daemon built from
        // this repository: family resolved, PServer alive, three live CPU policies, all three
        // profiles derived from the device's own OPP tables -- and posture=LAB, because that is how
        // the APK was compiled. It must be offered.
        val caps = parseTweakerCapabilitiesBundle(novaBundle(posture = "LAB"))

        assertTrue("a LAB daemon that did the work is admitted", caps.allowsUncertifiedTunableProfiles())
        assertEquals(
            listOf("stock", "quiet", "balanced", "performance"),
            selectableTweakerProfileIds(caps),
        )
    }

    /**
     * The control for the test above: the SAME bundle shape with every leg healthy DOES offer the
     * profiles. Without this, the refusals could be explained by a malformed fixture.
     */
    @Test
    fun `the fail-closed control proves the fixture is otherwise capable of offering profiles`() {
        val caps = parseTweakerCapabilitiesBundle(novaBundle())
        assertTrue(caps.allowsUncertifiedTunableProfiles())
        assertEquals(4, selectableTweakerProfileIds(caps).size)
    }

    /** Only real profile ids survive; a daemon cannot invent a selectable id. */
    @Test
    fun `an unknown uncertified id is filtered out at parse`() {
        val caps = parseTweakerCapabilitiesBundle(
            novaBundle(uncertified = listOf("quiet", "ludicrous", "stock")),
        )
        assertEquals(setOf("quiet"), caps.supportedUncertifiedProfileIds)
        assertEquals(listOf("stock", "quiet"), selectableTweakerProfileIds(caps))
    }

    /**
     * A launch-time decision must accept an uncertified id, because the row now offers it. This is
     * the blast-radius check for `TweakerPerGameLaunchDecision`: a profile the player can pick from
     * the row but that the launch path silently drops to Stock would be the same
     * advertise-then-refuse defect this codebase has shipped four times.
     */
    @Test
    fun `a per-game launch accepts an uncertified profile the row offered`() {
        val caps = parseTweakerCapabilitiesBundle(novaBundle())
        assertTrue("balanced" in selectableTweakerProfileIds(caps))
    }
}
