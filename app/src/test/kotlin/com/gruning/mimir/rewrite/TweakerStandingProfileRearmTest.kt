package com.gruning.mimir.rewrite

import android.content.Context
import com.gruning.mimir.rewrite.core.tweaker.TweakerCapabilities
import com.gruning.mimir.rewrite.core.tweaker.TweakerCapabilityStatus
import com.gruning.mimir.rewrite.core.tweaker.TweakerCeilingStep
import com.gruning.mimir.rewrite.core.tweaker.TweakerDiscoveredCeiling
import com.gruning.mimir.rewrite.core.tweaker.TweakerPosture
import com.gruning.mimir.rewrite.core.tweaker.TweakerQualificationSource
import com.gruning.mimir.rewrite.core.tweaker.TWEAKER_UTILITY_CHECKING_CAPS
import com.gruning.mimir.rewrite.core.tweaker.TweakerUtilityAvailability
import com.gruning.mimir.rewrite.core.tweaker.TweakerUtilityRowState
import com.gruning.mimir.rewrite.core.tweaker.tweakerProfileDisplayCaps
import com.gruning.mimir.rewrite.core.tweaker.tweakerUtilityAvailability
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The standing system-profile choice and its re-arm on process start.
 *
 * Guardian ties a live profile to the owning process and restores exact Stock when that process dies.
 * That is the safety mechanism working as designed — a dead app must never strand the hardware in a
 * derate — and it is deliberately untouched here. Device-proven on Odin 2026-08-10: force-stop at
 * 03:05:29 with Balanced applied, exact Stock at 03:05:33, with no Mimir process running at all.
 *
 * The defect the player actually reported is that their *intent* died with the process. `profile_id`
 * cannot carry it: that key tracks authoritative truth and is faithfully rewritten to Stock by the very
 * next observation after Guardian cleans up. So intent lives in its own key, written only by an
 * explicit selection, and re-stated once on startup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class TweakerStandingProfileRearmTest {
    private val prefs
        get() = RuntimeEnvironment.getApplication()
            .getSharedPreferences("mimir_tweaker_bridge", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        TweakerBridge.initialize(RuntimeEnvironment.getApplication())
        prefs.edit().clear().commit()
        TweakerBridge.resetStandingRearmStateForTest()
    }

    @Test
    fun `intent is blank until the player has ever chosen`() {
        assertEquals("", TweakerBridge.standingSystemProfileId())
    }

    @Test
    fun `an applied selection becomes the standing choice`() {
        TweakerBridge.recordStandingSelectionForTest(
            TweakerSystemProfileSelectionResult.Active("balanced", changed = true),
        )

        assertEquals("balanced", TweakerBridge.standingSystemProfileId())
    }

    @Test
    fun `choosing Stock is a real choice and clears the standing profile`() {
        TweakerBridge.recordStandingSelectionForTest(
            TweakerSystemProfileSelectionResult.Active("balanced", changed = true),
        )
        TweakerBridge.recordStandingSelectionForTest(
            TweakerSystemProfileSelectionResult.Stock(changed = true),
        )

        assertEquals(TweakerBridge.STOCK_PROFILE, TweakerBridge.standingSystemProfileId())
    }

    /**
     * The important negative. A request that never reached the hardware is not a new standing choice —
     * if it overwrote intent, one Busy press while a launch was in flight would silently disarm the
     * player's profile for every future boot.
     */
    @Test
    fun `a selection that never reached the hardware never changes intent`() {
        TweakerBridge.recordStandingSelectionForTest(
            TweakerSystemProfileSelectionResult.Active("balanced", changed = true),
        )

        listOf(
            TweakerSystemProfileSelectionResult.Busy,
            TweakerSystemProfileSelectionResult.Unavailable,
            TweakerSystemProfileSelectionResult.UpgradeRequired,
            TweakerSystemProfileSelectionResult.Rejected("QUALIFICATION_REQUIRED"),
            TweakerSystemProfileSelectionResult.Uncertain("transport"),
            // Cancelled is the player taking the request back, not choosing something new.
            TweakerSystemProfileSelectionResult.Cancelled("balanced"),
        ).forEach { outcome ->
            TweakerBridge.recordStandingSelectionForTest(outcome)
            assertEquals(
                "$outcome must not rewrite the standing choice",
                "balanced",
                TweakerBridge.standingSystemProfileId(),
            )
        }
    }

    @Test
    fun `re-arm is inert when the player never chose a profile`() = runTest {
        assertNull(TweakerBridge.rearmStandingSystemProfile())
    }

    @Test
    fun `re-arm is inert when the player deliberately chose Stock`() = runTest {
        TweakerBridge.recordStandingSelectionForTest(
            TweakerSystemProfileSelectionResult.Stock(changed = true),
        )

        assertNull(TweakerBridge.rearmStandingSystemProfile())
    }

    /**
     * With no capability truth there is nothing that says this profile is still offered on this
     * hardware, so re-arm must decline rather than push a profile the normal path could refuse.
     */
    @Test
    fun `re-arm declines without capability truth rather than guessing`() = runTest {
        prefs.edit().putString("standing_profile_id", "balanced").commit()

        assertNull(TweakerBridge.rearmStandingSystemProfile())
    }

    // ---- Watcher decision ----------------------------------------------------------------------
    //
    // The re-arm above is correct and device-proven; until 2026-08-10 it was simply wired to one
    // trigger — process start — that the failing case never reaches. Device evidence (Odin3, Mimir
    // `abede8c4…`, Tweaker `847070d3…`): force-stopping the Tweaker service while Mimir stays ALIVE
    // dropped balanced -> Stock in ~8s and it never returned across 80s, with Mimir's pid unchanged
    // throughout. These pin the decision that now drives re-arm from every authoritative observation.

    /** The reported defect: intent says balanced, the device says Stock, and nothing noticed. */
    @Test
    fun `a standing profile lost to Stock is re-armed`() {
        assertEquals(
            StandingRearmDecision.REARM,
            decision(standing = "balanced", effective = "stock"),
        )
    }

    @Test
    fun `a standing profile that is still live is left alone`() {
        assertEquals(
            StandingRearmDecision.HONOURED,
            decision(standing = "balanced", effective = "balanced"),
        )
    }

    /**
     * A profile the player did not choose is still not the watcher's business — another surface
     * applied it, or the service outlived us. Re-applying would restart a live session for nothing.
     */
    @Test
    fun `a different live profile is not overridden`() {
        assertEquals(
            StandingRearmDecision.HONOURED,
            decision(standing = "balanced", effective = "quiet"),
        )
    }

    /**
     * The one that would be infuriating if it regressed: choosing Stock is a real choice, and the
     * watcher must never argue with it — at Stock, forever, with no re-arm.
     */
    @Test
    fun `the watcher never fights a player who chose Stock`() {
        assertEquals(
            StandingRearmDecision.NOTHING_TO_ASSERT,
            decision(standing = "stock", effective = "stock"),
        )
    }

    @Test
    fun `the watcher is inert before the player has ever chosen`() {
        assertEquals(
            StandingRearmDecision.NOTHING_TO_ASSERT,
            decision(standing = "", effective = "stock"),
        )
    }

    /**
     * Capability truth must distinguish an unavailable service from a confirmed Stock profile.
     *
     * With the service dead, capabilities are null, so the row reports UNAVAILABLE and collapses the
     * effective profile to Stock — indistinguishable, by value alone, from a genuine Stock device.
     * Reading that as HONOURED cancelled the watcher and stranded the hardware at Stock. Anything
     * short of READY must keep watching instead.
     */
    @Test
    fun `an unreadable service is never mistaken for an honoured choice`() {
        listOf(
            TweakerUtilityAvailability.UNAVAILABLE,
            TweakerUtilityAvailability.NOT_INSTALLED,
            TweakerUtilityAvailability.RESPONDING_STOCK_ONLY,
            TweakerUtilityAvailability.DISCOVERED_CEILING,
        ).forEach { availability ->
            // Never HONOURED, whatever the flavour of "not READY" -- that mistake stranded the
            // hardware at Stock. NOT_INSTALLED and RESPONDING_STOCK_ONLY are additionally definitive,
            // so they stand the watch down rather than retry; see the NOT_OFFERED tests below.
            assertNotEquals(
                "$availability must not be read as an honoured choice",
                StandingRearmDecision.HONOURED,
                decision(standing = "balanced", effective = "stock", availability = availability),
            )
        }
    }

    /** Even unreadable truth must not override a player who chose Stock. */
    @Test
    fun `an unreadable service still never fights a Stock choice`() {
        assertEquals(
            StandingRearmDecision.NOTHING_TO_ASSERT,
            decision(
                standing = "stock",
                effective = "stock",
                availability = TweakerUtilityAvailability.UNAVAILABLE,
            ),
        )
    }

    /**
     * Definitive negative availability states must stop the watcher.
     *
     * `NOT_INSTALLED` and `RESPONDING_STOCK_ONLY` are definitive negatives — the package is gone, or
     * the service answered and decertified this hardware. Retrying cannot change either. Treating them
     * as unknown made the watch immortal: it ticked every 15 s for the life of the process, and because
     * a tick refresh is not a re-arm it bypassed the backoff entirely, re-binding (and so restarting)
     * the Tweaker service on that cadence.
     */
    @Test
    fun `a definitive negative stands the watcher down instead of retrying forever`() {
        listOf(
            TweakerUtilityAvailability.NOT_INSTALLED,
            TweakerUtilityAvailability.RESPONDING_STOCK_ONLY,
            // The discovered path offers CLOCK CEILINGS and no named profiles, so a standing profile
            // has nothing to be honoured by and nothing for a watch to confirm. Added when
            // DISCOVERED_CEILING began outranking NOT_INSTALLED: without this it fell through the
            // `!= READY` comparison into TRUTH_UNKNOWN and started a rearm watch on a device that may
            // have no Tweaker installed at all -- a watch on a service that is not there.
            TweakerUtilityAvailability.DISCOVERED_CEILING,
        ).forEach { availability ->
            assertEquals(
                "$availability is definitive and must not be retried",
                StandingRearmDecision.NOT_OFFERED,
                decision(standing = "balanced", effective = "stock", availability = availability),
            )
        }
    }

    /** Only UNAVAILABLE is genuinely unknown: bind failed, or capabilities not read yet. */
    @Test
    fun `only an unreadable service keeps the watcher alive`() {
        assertEquals(
            StandingRearmDecision.TRUTH_UNKNOWN,
            decision(
                standing = "balanced",
                effective = "stock",
                availability = TweakerUtilityAvailability.UNAVAILABLE,
            ),
        )
    }

    /**
     * Regression coverage for the `no_capability_truth` exit, at decision level: an
     * observation that cannot read the service must not be terminal, and the very next observation
     * that CAN read it must ask for the re-arm. Before 2026-08-11 the first reading was mistaken for
     * HONOURED and cancelled the watcher.
     */
    @Test
    fun `an unreadable observation followed by a readable one re-arms`() {
        val whileDown = decision(
            standing = "balanced",
            effective = "stock",
            availability = TweakerUtilityAvailability.UNAVAILABLE,
        )
        val onceBack = decision(standing = "balanced", effective = "stock")

        assertEquals(StandingRearmDecision.TRUTH_UNKNOWN, whileDown)
        assertEquals(StandingRearmDecision.REARM, onceBack)
    }

    /**
     * F1 at the wiring level, not just the table. Robolectric has no Tweaker package, so the row
     * reports NOT_INSTALLED — the hook must stand the watch down rather than start an immortal one.
     */
    @Test
    fun `the hook does not start a watch when the Tweaker is not installed`() {
        prefs.edit().putString("standing_profile_id", "balanced").commit()

        TweakerBridge.noteAuthoritativeTruthForStandingRearmForTest()

        assertFalse(
            "a definitive negative must not leave a watch running",
            TweakerBridge.standingRearmWatchActiveForTest(),
        )
    }

    // ---- Single-flight admission (spec §8 tests 1 and 4) ----------------------------------------

    @Test
    fun `an idle scheduler with no backoff debt dispatches`() {
        assertEquals(
            StandingRearmAdmission.DISPATCH,
            standingRearmAdmission(nowElapsedRealtimeMs = 1_000L, notBeforeElapsedRealtimeMs = 0L, inFlight = false),
        )
    }

    /** A second observation arriving mid-attempt is dropped, never queued. */
    @Test
    fun `a second observation during an in-flight re-arm is dropped`() {
        assertEquals(
            StandingRearmAdmission.IN_FLIGHT,
            standingRearmAdmission(nowElapsedRealtimeMs = 1_000L, notBeforeElapsedRealtimeMs = 0L, inFlight = true),
        )
    }

    /** Backoff outranks everything: a failing re-arm must not be retried on every observation. */
    @Test
    fun `backoff debt suppresses dispatch even when idle`() {
        assertEquals(
            StandingRearmAdmission.BACKOFF,
            standingRearmAdmission(nowElapsedRealtimeMs = 500L, notBeforeElapsedRealtimeMs = 2_000L, inFlight = false),
        )
    }

    @Test
    fun `dispatch resumes once the backoff window elapses`() {
        assertEquals(
            StandingRearmAdmission.DISPATCH,
            standingRearmAdmission(nowElapsedRealtimeMs = 2_000L, notBeforeElapsedRealtimeMs = 2_000L, inFlight = false),
        )
    }

    private fun decision(
        standing: String,
        effective: String,
        availability: TweakerUtilityAvailability = TweakerUtilityAvailability.READY,
        negativeIsDefinitive: Boolean = true,
        capabilitiesUnanswered: Boolean = false,
    ): StandingRearmDecision = standingRearmDecision(
        standingProfileId = standing,
        effectiveProfileId = effective,
        availability = availability,
        negativeIsDefinitive = negativeIsDefinitive,
        capabilitiesUnanswered = capabilitiesUnanswered,
    )

    // ---- Sticky apply: an unanswered query is unknown, not "no" (2026-10-01) -------------------------

    private fun knownCaps() = TweakerCapabilities(
        status = TweakerCapabilityStatus.SUPPORTED,
        deviceProfileId = "retroid-qcs8550-pserver",
        hardwareFingerprint = "nova",
        supportedAxes = emptySet(),
        certifiedProfileIds = setOf("quiet", "balanced", "performance"),
        notes = emptyList(),
        posture = TweakerPosture.PRODUCTION,
        mutationAvailable = true,
        qualificationSource = TweakerQualificationSource.SIGNED_MANIFEST,
    )

    private val publishedCeiling = TweakerDiscoveredCeiling(
        policyLabel = "PRIME",
        ceilingLabelCaps = "3.2 GHZ",
        steps = listOf(TweakerCeilingStep(1_478_400, "1.5 GHZ"), TweakerCeilingStep(3_187_200, "3.2 GHZ")),
        currentKhz = 3_187_200,
    )

    @Test
    fun `a null refresh after an unanswered query keeps the last known capabilities and marks truth unknown`() {
        val last = knownCaps()
        val outcome = reconcileCapabilitiesAfterRefresh(previous = last, incoming = null, silent = true)

        assertEquals(last, outcome.capabilities)
        assertTrue(outcome.truthUnknown)
    }

    @Test
    fun `a null refresh with no unanswered query is a definitive negative and still clears`() {
        val outcome = reconcileCapabilitiesAfterRefresh(previous = knownCaps(), incoming = null, silent = false)

        assertNull(outcome.capabilities)
        assertFalse(outcome.truthUnknown)
    }

    @Test
    fun `an answer ends the unknown even when a query went unanswered earlier`() {
        val fresh = knownCaps().copy(hardwareFingerprint = "fresh")
        val outcome = reconcileCapabilitiesAfterRefresh(previous = knownCaps(), incoming = fresh, silent = true)

        assertEquals(fresh, outcome.capabilities)
        assertFalse(outcome.truthUnknown)
    }

    @Test
    fun `kept capabilities keep the row READY on the standing profile and the watch armed`() {
        val kept = reconcileCapabilitiesAfterRefresh(knownCaps(), incoming = null, silent = true)
        val availability = tweakerUtilityAvailability(
            installed = true,
            capabilities = kept.capabilities,
            discovered = publishedCeiling,
            capabilitiesUnknown = kept.truthUnknown,
        )

        // Not DISCOVERED_CEILING (the reported Stock/Unavailable fallback row). The row keeps the profile LIST, but it is
        // answerPending: it no longer claims quiet is in force, and the watch keeps asking rather than standing down
        // on HONOURED (the service may have restarted onto Stock since the last answer).
        assertEquals(TweakerUtilityAvailability.READY, availability)
        val row = TweakerUtilityRowState(
            availability = availability,
            selectedProfileId = "quiet",
            selectableProfileIds = listOf("stock", "quiet", "balanced"),
            answerPending = kept.truthUnknown,
        )
        assertEquals(TWEAKER_UTILITY_CHECKING_CAPS, row.valueLabelCaps)
        assertTrue("the profile list stays reachable", row.canStep)
        assertEquals(
            StandingRearmDecision.TRUTH_UNKNOWN,
            decision(
                standing = "quiet",
                effective = row.effectiveProfileId,
                availability = availability,
                capabilitiesUnanswered = row.answerPending,
            ),
        )
    }

    @Test
    fun `with nothing to keep, an unanswered query reads UNAVAILABLE so the rearm watch stays armed`() {
        // Without the unknown flag this very input is DISCOVERED_CEILING. It used to be NOT_OFFERED and stand
        // the watch down (Nova 2026-10-01 12:45:27); with an installed, bindable Tweaker that has never
        // answered it is unknown, so the watch lives and its refresh rebinds.
        val definitive = tweakerUtilityAvailability(
            installed = true,
            capabilities = null,
            discovered = publishedCeiling,
        )
        assertEquals(TweakerUtilityAvailability.DISCOVERED_CEILING, definitive)
        assertEquals(
            StandingRearmDecision.TRUTH_UNKNOWN,
            decision(
                standing = "quiet",
                effective = "stock",
                availability = definitive,
                negativeIsDefinitive = tweakerNegativeIsDefinitive(TweakerPairState.ELIGIBLE, false),
            ),
        )

        val unknown = tweakerUtilityAvailability(
            installed = true,
            capabilities = null,
            discovered = publishedCeiling,
            capabilitiesUnknown = true,
        )
        assertEquals(TweakerUtilityAvailability.UNAVAILABLE, unknown)
        assertEquals(
            StandingRearmDecision.TRUTH_UNKNOWN,
            decision(standing = "quiet", effective = "stock", availability = unknown),
        )
        // The standing choice itself is never touched by an observation.
        TweakerBridge.recordStandingSelectionForTest(
            TweakerSystemProfileSelectionResult.Active("quiet", changed = true),
        )
        assertEquals("quiet", TweakerBridge.standingSystemProfileId())
    }

    /**
     * Nova 2026-10-01 12:45:27: `decision=NOT_OFFERED standing=quiet effective=stock availability=DISCOVERED_CEILING
     * bound=false`, then `watch=stopped`, with the CPU QoS node still held at Quiet. The row is DISCOVERED_CEILING
     * whenever capabilities are null, and null capabilities on a Tweaker that can bind are the absence of an
     * answer. Only an answer, or a pair that can never bind, makes the negative definitive.
     */
    @Test
    fun `a discovered ceiling stands the watch down only when something actually said no`() {
        assertEquals(
            StandingRearmDecision.NOT_OFFERED,
            decision("quiet", "stock", TweakerUtilityAvailability.DISCOVERED_CEILING, negativeIsDefinitive = true),
        )
        assertEquals(
            "no answer yet is not an answer: the watch lives and rebinds",
            StandingRearmDecision.TRUTH_UNKNOWN,
            decision("quiet", "stock", TweakerUtilityAvailability.DISCOVERED_CEILING, negativeIsDefinitive = false),
        )
        // The two named negatives never depended on it: they carry their own proof.
        listOf(TweakerUtilityAvailability.NOT_INSTALLED, TweakerUtilityAvailability.RESPONDING_STOCK_ONLY)
            .forEach { availability ->
                assertEquals(
                    StandingRearmDecision.NOT_OFFERED,
                    decision("quiet", "stock", availability, negativeIsDefinitive = false),
                )
            }
    }

    @Test
    fun `a negative is definitive after an answer or on a pair that can never bind`() {
        // An answer settles it whatever the pair says.
        TweakerPairState.values().forEach { pair ->
            assertTrue("$pair with capabilities", tweakerNegativeIsDefinitive(pair, capabilitiesAnswered = true))
        }
        // No answer: only the pairs that can never bind are definitive.
        val bindable = setOf(
            TweakerPairState.ELIGIBLE,
            TweakerPairState.UNCHECKED,
            TweakerPairState.PACKAGE_MANAGER_ERROR,
        )
        TweakerPairState.values().forEach { pair ->
            assertEquals(
                "$pair with no capabilities",
                pair !in bindable,
                tweakerNegativeIsDefinitive(pair, capabilitiesAnswered = false),
            )
        }
    }

    @Test
    fun `an unreadable Tweaker is retried at the recovery cadence and then slows, never stops`() {
        val recovery = TweakerBridge.STANDING_REARM_WATCH_INTERVAL_MS_FOR_TEST
        val slow = TweakerBridge.STANDING_CONFIRM_INTERVAL_MS_FOR_TEST

        (0 until UNKNOWN_TICKS_BEFORE_SLOWING).forEach { ticks ->
            assertEquals(recovery, standingWatchDelayMs(StandingRearmDecision.TRUTH_UNKNOWN, consecutiveUnknownTicks = ticks))
        }
        assertEquals(
            slow,
            standingWatchDelayMs(StandingRearmDecision.TRUTH_UNKNOWN, consecutiveUnknownTicks = UNKNOWN_TICKS_BEFORE_SLOWING),
        )
        // A lost choice is still chased at the recovery cadence however long the streak.
        assertEquals(
            recovery,
            standingWatchDelayMs(StandingRearmDecision.REARM, consecutiveUnknownTicks = 99),
        )
    }

    @Test
    fun `a bind that fails on a pair that can bind keeps what was last answered and marks it unanswered`() {
        val last = knownCaps()
        val eligible = reconcileCapabilitiesAfterFailedBind(last, TweakerPairIdentity(TweakerPairState.ELIGIBLE))
        assertEquals(last, eligible.capabilities)
        assertTrue(eligible.truthUnknown)

        // A package that is gone, or the wrong build, is the answer: it clears.
        listOf(
            TweakerPairState.ABSENT,
            TweakerPairState.SIGNATURE_MISMATCH,
            TweakerPairState.SERVICE_MISSING,
            TweakerPairState.COMPONENT_DISABLED,
        ).forEach { pair ->
            val outcome = reconcileCapabilitiesAfterFailedBind(last, TweakerPairIdentity(pair))
            assertNull("$pair", outcome.capabilities)
            assertFalse("$pair", outcome.truthUnknown)
        }
    }

    /**
     * "DISCOVERED_CEILING with a held node is never Unavailable." The kernel shows the ceiling held below the top
     * of its table, so the row names the real clock even when the daemon has not answered; at the top of the
     * table, with nothing held, an unanswered daemon is still UNAVAILABLE.
     */
    @Test
    fun `a ceiling the kernel shows held is never read as Unavailable`() {
        val held = publishedCeiling.copy(currentKhz = 1_478_400, ceilingLabelCaps = "1.5 GHZ")
        assertTrue(held.ceilingHeld)
        assertFalse(publishedCeiling.ceilingHeld)

        assertEquals(
            TweakerUtilityAvailability.DISCOVERED_CEILING,
            tweakerUtilityAvailability(
                installed = true,
                capabilities = null,
                discovered = held,
                capabilitiesUnknown = true,
            ),
        )
        assertEquals(
            TweakerUtilityAvailability.UNAVAILABLE,
            tweakerUtilityAvailability(
                installed = true,
                capabilities = null,
                discovered = publishedCeiling,
                capabilitiesUnknown = true,
            ),
        )
        // An unreadable node is not a held one.
        assertFalse(publishedCeiling.copy(currentKhz = null).ceilingHeld)
    }

    /**
     * The restart case: the Tweaker service restarts and its startup recovery puts the hardware on Stock (Odin
     * 2026-08-13); Mimir's next bind times out on a pair that can still bind. The row keeps the daemon's last
     * capabilities, so it is READY on `quiet` over Stock silicon. That must not read as HONOURED, name `quiet`, or
     * keep the overlay's glyph.
     */
    @Test
    fun `a daemon that restarted onto Stock behind a timed out bind is never reported as honoured`() {
        val retained = reconcileCapabilitiesAfterFailedBind(knownCaps(), TweakerPairIdentity(TweakerPairState.ELIGIBLE))
        assertTrue(retained.truthUnknown)
        val row = TweakerUtilityRowState(
            availability = TweakerUtilityAvailability.READY,
            selectedProfileId = "quiet",
            selectableProfileIds = listOf("stock", "quiet", "balanced"),
            answerPending = retained.truthUnknown,
        )

        // Before: the stored selection decided it.
        assertEquals(
            StandingRearmDecision.HONOURED,
            decision("quiet", row.effectiveProfileId, row.availability),
        )
        // Now: unanswered READY is unknown whatever the stored selection says, and the next answer decides.
        assertEquals(
            StandingRearmDecision.TRUTH_UNKNOWN,
            decision("quiet", row.effectiveProfileId, row.availability, capabilitiesUnanswered = row.answerPending),
        )
        assertEquals(
            "answered and still on stock: re-arm",
            StandingRearmDecision.REARM,
            decision("quiet", "stock", row.availability, capabilitiesUnanswered = false),
        )
        assertEquals(TWEAKER_UTILITY_CHECKING_CAPS, row.valueLabelCaps)
        assertTrue(row.canStep)
        assertEquals("the profile choice itself is untouched", "quiet", row.effectiveProfileId)
        // The answer arrives: the same row names the profile again.
        assertEquals(tweakerProfileDisplayCaps("quiet"), row.copy(answerPending = false).valueLabelCaps)
    }

    @Test
    fun `an unanswered ask never overrides a definitive negative or the absence of intent`() {
        assertEquals(
            StandingRearmDecision.NOTHING_TO_ASSERT,
            decision("", "stock", TweakerUtilityAvailability.READY, capabilitiesUnanswered = true),
        )
        assertEquals(
            StandingRearmDecision.NOT_OFFERED,
            decision(
                "quiet", "stock", TweakerUtilityAvailability.RESPONDING_STOCK_ONLY,
                capabilitiesUnanswered = true,
            ),
        )
    }

    @Test
    fun `the supporting line says the profile is being checked and that the list is still usable`() {
        val line = tweakerProfileSupportingText(TweakerUtilityAvailability.READY, answerPending = true)
        assertEquals("Checking which profile is in force. You can still choose one.", line)
        assertEquals(
            "Choose a performance profile.",
            tweakerProfileSupportingText(TweakerUtilityAvailability.READY, answerPending = false),
        )
    }

    /** A daemon that answered and declined to give capabilities (Uncertain, a non-SYSTEM Blocked) is not "no answer". */
    @Test
    fun `the daemon counts as having answered when it named its version and has not gone silent since`() {
        assertTrue(tweakerCapabilitiesAnswered(capabilitiesPresent = true, daemonAnsweredInProcess = false, truthUnknown = false))
        assertTrue(tweakerCapabilitiesAnswered(capabilitiesPresent = false, daemonAnsweredInProcess = true, truthUnknown = false))
        assertFalse("silent since", tweakerCapabilitiesAnswered(false, daemonAnsweredInProcess = true, truthUnknown = true))
        assertFalse("never heard from", tweakerCapabilitiesAnswered(false, daemonAnsweredInProcess = false, truthUnknown = false))

        // End to end through the decision: a bindable pair, null capabilities, daemon answered earlier -> definitive.
        val answered = tweakerNegativeIsDefinitive(
            TweakerPairState.ELIGIBLE,
            tweakerCapabilitiesAnswered(false, daemonAnsweredInProcess = true, truthUnknown = false),
        )
        assertEquals(
            "the watch stands down instead of binding every 60 s for the process",
            StandingRearmDecision.NOT_OFFERED,
            decision("quiet", "stock", TweakerUtilityAvailability.DISCOVERED_CEILING, negativeIsDefinitive = answered),
        )
        val neverHeard = tweakerNegativeIsDefinitive(
            TweakerPairState.ELIGIBLE,
            tweakerCapabilitiesAnswered(false, daemonAnsweredInProcess = false, truthUnknown = false),
        )
        assertEquals(
            StandingRearmDecision.TRUTH_UNKNOWN,
            decision("quiet", "stock", TweakerUtilityAvailability.DISCOVERED_CEILING, negativeIsDefinitive = neverHeard),
        )
    }

    /** An already-active profile has no ticket, so the confirmation window is what keeps the overlay off the token. */
    @Test
    fun `an already active profile's confirmation holds observers out for its window and no longer`() {
        assertTrue(preparedActiveConfirmationOutstanding(hasPreparedProfile = true, ageMs = 0L, windowMs = 10_000L))
        assertTrue(preparedActiveConfirmationOutstanding(true, 9_999L, 10_000L))
        assertFalse("a dispatch that never confirmed must not lock them out", preparedActiveConfirmationOutstanding(true, 10_000L, 10_000L))
        assertFalse("nothing prepared", preparedActiveConfirmationOutstanding(false, 0L, 10_000L))
        assertFalse("a clock that went backwards", preparedActiveConfirmationOutstanding(true, -1L, 10_000L))
    }

    @Test
    fun `a package that is not installed is still NOT_INSTALLED whatever timed out`() {
        assertEquals(
            TweakerUtilityAvailability.NOT_INSTALLED,
            tweakerUtilityAvailability(installed = false, capabilities = null, capabilitiesUnknown = true),
        )
    }

    // ---- Backoff -------------------------------------------------------------------------------

    /** No failures means no delay: the first attempt after a drop is immediate. */
    @Test
    fun `the first re-arm attempt is not delayed`() {
        assertEquals(0L, backoff(0))
    }

    @Test
    fun `consecutive failures back off exponentially`() {
        assertEquals(2_000L, backoff(1))
        assertEquals(4_000L, backoff(2))
        assertEquals(8_000L, backoff(3))
        assertEquals(16_000L, backoff(4))
    }

    /** A re-arm that can never succeed must settle at a ceiling, not grow without bound. */
    @Test
    fun `backoff is capped and never overflows`() {
        assertEquals(60_000L, backoff(6))
        assertEquals(60_000L, backoff(50))
        assertEquals(60_000L, backoff(Int.MAX_VALUE))
    }

    private fun backoff(failures: Int): Long = standingRearmBackoffMs(
        consecutiveFailures = failures,
        baseMs = 2_000L,
        maxMs = 60_000L,
        maxSteps = 6,
    )

    // ---- P7: a successful scheduled standing re-arm keeps the foreground watch alive ------------
    //
    // Device-observed defect: after the daemon's own restore, the standing profile sat at Stock for
    // 16 minutes because the watch had been cancelled by the previous successful re-arm. The fix
    // mirrors the HONOURED branch in [noteAuthoritativeTruthForStandingRearm] (~:1630) so a
    // successful re-arm heals only when nobody is watching the screen.
    //
    // No harness in this test can drive `scheduleStandingRearm()`'s coroutine to an
    // `Active` result: `rearmStandingSystemProfile()` requires real, bound `capabilities` (`private
    // set`, refreshed only via `refreshCapabilities()`'s real service IO), and Robolectric has no
    // Tweaker package to bind to (see `the hook does not start a watch when the Tweaker is not
    // installed` above). So this is a source-text pin (a) on the Active branch's call order, plus
    // reuse of the already-exhaustive pure pin (b) on [standingWatchConfirms] itself
    // (`TweakerStandingProfileConfirmTest` already pins both
    // `standingWatchConfirms(HONOURED, mimirResumed = true)` == true and
    // `standingWatchConfirms(HONOURED, mimirResumed = false)` == false, which is exactly the decision
    // this branch now consults) rather than a redundant extraction. (c), a full watch-lifecycle pin
    // across a real scheduled re-arm, is not reachable here for the reason above.

    /**
     * RED FIRST. Before this fix the `Active` branch unconditionally called `standingRearmHealed()`,
     * which cancels the watch. That is the exact defect: a successful re-arm cancelled the very watch
     * that had just re-armed it, so the NEXT loss went unnoticed until the app was restarted by hand.
     */
    @Test
    fun `the Active branch of the scheduled re-arm mirrors the HONOURED branch before healing`() {
        val body = tweakerBridgeSource()
            .substringAfter("private fun scheduleStandingRearm() {")
            .substringBefore("\n    }\n")

        val confirmsIndex = body.indexOf("standingWatchConfirms(")
        val startWatchIndex = body.indexOf("startStandingRearmWatch()")
        val healedIndex = body.indexOf("standingRearmHealed()")

        assertTrue("the Active branch must consult standingWatchConfirms(", confirmsIndex >= 0)
        assertTrue("the Active branch must be able to restart the watch", startWatchIndex >= 0)
        assertTrue("a successful re-arm must still heal when nobody is watching", healedIndex >= 0)
        assertTrue(
            "standingWatchConfirms( must run before any standingRearmHealed() call, or a successful " +
                "re-arm cancels the very watch it just re-armed",
            confirmsIndex < healedIndex,
        )
        assertTrue(
            "startStandingRearmWatch() must run before any standingRearmHealed() call in this branch",
            startWatchIndex < healedIndex,
        )
        assertTrue(
            "clearing backoff on a kept watch must not disappear either",
            body.contains("standingRearmBackoffCleared()"),
        )
        assertTrue(
            "the non-Active branch's failure bookkeeping is unchanged by this fix",
            body.contains("recordStandingRearmFailure()"),
        )
    }

    // ---- O16: a re-arm's end is signalled -------------------------------------------------------

    /**
     * A display-rate APPLY refused while a re-arm is in flight waits for that re-arm's
     * end, so the end has to come from the dispatched job itself, not from an estimate. This dispatches through the real
     * `scheduleStandingRearm`. Robolectric has no Tweaker package, so the job declines on its first checks
     * (`no_capability_truth`), and its end must still be signalled.
     */
    @Test
    fun `a dispatched re-arm signals its end when its job ends`() = runBlocking {
        prefs.edit().putString("standing_profile_id", "balanced").commit()
        // The bridge is a process singleton, and process-start confirmation can dispatch a re-arm of its own in this
        // JVM at any moment. Clear the in-flight flag and backoff, and ask until the scheduler dispatches.
        var dispatched: StandingRearmFlight? = null
        for (tries in 0 until 100) {
            TweakerBridge.resetStandingRearmStateForTest()
            dispatched = TweakerBridge.scheduleStandingRearmForTest()
            if (dispatched != null) break
            delay(10L)
        }
        val flight = dispatched ?: error("the scheduler dispatched nothing in 100 tries")

        assertTrue("the job ended within 10 s and signalled it", flight.awaitEnd(10_000L))
        assertNotSame("an ended re-arm is no longer reported in flight", flight, TweakerBridge.inFlightStandingRearm())
    }

    private fun tweakerBridgeSource(): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/TweakerBridge.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/TweakerBridge.kt"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("TweakerBridge.kt not found from ${File(".").absolutePath}")
    }
}
