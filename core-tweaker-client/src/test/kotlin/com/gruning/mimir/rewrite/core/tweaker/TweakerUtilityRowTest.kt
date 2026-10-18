package com.gruning.mimir.rewrite.core.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TweakerUtilityRowTest {
    private fun caps(
        status: TweakerCapabilityStatus,
        certified: Set<String> = emptySet(),
    ) = TweakerCapabilities(
        status = status,
        deviceProfileId = "tw03",
        hardwareFingerprint = "rp5",
        supportedAxes = emptySet(),
        certifiedProfileIds = certified,
        notes = emptyList(),
        posture = if (certified.isEmpty()) TweakerPosture.SAFE else TweakerPosture.PRODUCTION,
        mutationAvailable = certified.isNotEmpty(),
        qualificationSource = if (certified.isEmpty()) {
            TweakerQualificationSource.NONE
        } else {
            TweakerQualificationSource.SIGNED_MANIFEST
        },
    )

    @Test
    fun `an absent package is the only state that may ask for an install`() {
        assertEquals(
            TweakerUtilityAvailability.NOT_INSTALLED,
            tweakerUtilityAvailability(installed = false, capabilities = null),
        )
        // Presence not read yet (the read is a Binder call kept off the UI path): not having looked is
        // not evidence of absence, so the row must not tell the player to install anything.
        assertEquals(
            TweakerUtilityAvailability.UNAVAILABLE,
            tweakerUtilityAvailability(installed = null, capabilities = null),
        )
        assertEquals(
            TweakerUtilityAvailability.UNAVAILABLE,
            tweakerUtilityAvailability(null, caps(TweakerCapabilityStatus.SUPPORTED)),
        )
        // Installed but the bind failed / permission refused: capabilities are null, yet telling the player
        // to install something already on the device would be a lie.
        assertEquals(
            TweakerUtilityAvailability.UNAVAILABLE,
            tweakerUtilityAvailability(installed = true, capabilities = null),
        )
        assertEquals(
            TweakerUtilityAvailability.RESPONDING_STOCK_ONLY,
            tweakerUtilityAvailability(true, caps(TweakerCapabilityStatus.UNSUPPORTED)),
        )
        assertEquals(
            TweakerUtilityAvailability.UNAVAILABLE,
            tweakerUtilityAvailability(true, caps(TweakerCapabilityStatus.UNAVAILABLE)),
        )
        assertEquals(
            TweakerUtilityAvailability.RESPONDING_STOCK_ONLY,
            tweakerUtilityAvailability(true, caps(TweakerCapabilityStatus.SUPPORTED)),
        )
        // Reachable without a signed profile is connected, but remains honestly Stock-only.
        assertEquals(
            TweakerUtilityAvailability.RESPONDING_STOCK_ONLY,
            tweakerUtilityAvailability(true, caps(TweakerCapabilityStatus.CALIBRATION_REQUIRED)),
        )
        assertEquals(
            TweakerUtilityAvailability.READY,
            tweakerUtilityAvailability(
                true,
                caps(TweakerCapabilityStatus.SUPPORTED, certified = setOf("performance")),
            ),
        )
    }

    @Test
    fun `the row shows one capped truth in every availability`() {
        assertEquals(
            "INSTALL TWEAKER",
            TweakerUtilityRowState(TweakerUtilityAvailability.NOT_INSTALLED).valueLabelCaps,
        )
        assertEquals(
            "UNAVAILABLE",
            TweakerUtilityRowState(TweakerUtilityAvailability.UNAVAILABLE).valueLabelCaps,
        )
        assertEquals(
            "STOCK ONLY",
            TweakerUtilityRowState(TweakerUtilityAvailability.RESPONDING_STOCK_ONLY).valueLabelCaps,
        )
        assertEquals(
            "STOCK",
            TweakerUtilityRowState(TweakerUtilityAvailability.READY).valueLabelCaps,
        )
        assertEquals(
            "PERFORMANCE",
            TweakerUtilityRowState(
                availability = TweakerUtilityAvailability.READY,
                selectedProfileId = "performance",
                selectableProfileIds = listOf("stock", "performance"),
            ).valueLabelCaps,
        )
    }

    @Test
    fun `qualification marker is presentation truth and cannot make a row selectable`() {
        val row = TweakerUtilityRowState(
            availability = TweakerUtilityAvailability.RESPONDING_STOCK_ONLY,
            qualificationTestMode = true,
        )

        assertTrue(row.qualificationTestMode)
        assertFalse(row.canStep)
        assertEquals("STOCK ONLY", row.valueLabelCaps)
    }

    @Test
    fun `an uncertified stored pick is never displayed as if it were in force`() {
        // Preparation restores stock for an uncertified pick, so the row saying "BALANCED" would promise a
        // mode the device will not enter.
        val stale = TweakerUtilityRowState(
            availability = TweakerUtilityAvailability.READY,
            selectedProfileId = "balanced",
            selectableProfileIds = listOf("stock", "performance"),
        )
        assertEquals("stock", stale.effectiveProfileId)
        assertEquals("STOCK", stale.valueLabelCaps)
    }

    @Test
    fun `a selection is never shown while the service cannot honour it`() {
        val unreachable = TweakerUtilityRowState(
            availability = TweakerUtilityAvailability.UNAVAILABLE,
            selectedProfileId = "performance",
            selectableProfileIds = listOf("stock", "performance"),
        )
        assertEquals("stock", unreachable.effectiveProfileId)
        assertEquals("UNAVAILABLE", unreachable.valueLabelCaps)
        assertFalse(unreachable.canStep)
    }

    @Test
    fun `every press is answered and only a real change claims a mode`() {
        val notInstalled = TweakerUtilityRowState(TweakerUtilityAvailability.NOT_INSTALLED)
        assertEquals(TweakerUtilityStepOutcome.NotInstalled, notInstalled.stepOutcome(1))
        assertEquals("TWEAKER NOT INSTALLED", notInstalled.stepOutcome(1).utilityToastCaps())

        val unavailable = TweakerUtilityRowState(TweakerUtilityAvailability.UNAVAILABLE)
        assertEquals(TweakerUtilityStepOutcome.Unavailable, unavailable.stepOutcome(-1))
        assertEquals("TWEAKER UNAVAILABLE", unavailable.stepOutcome(-1).utilityToastCaps())

        val stockOnly = TweakerUtilityRowState(TweakerUtilityAvailability.RESPONDING_STOCK_ONLY)
        assertFalse(stockOnly.canStep)
        assertEquals(TweakerUtilityStepOutcome.StockOnly, stockOnly.stepOutcome(1))
        assertEquals("TWEAKER CONNECTED · STOCK ONLY", stockOnly.stepOutcome(1).utilityToastCaps())

        // Reachable but nothing beyond stock: stock is the only member, so there is nowhere to go and we say so.
        val readyStockOnly = TweakerUtilityRowState(TweakerUtilityAvailability.READY)
        assertFalse(readyStockOnly.canStep)
        assertEquals(TweakerUtilityStepOutcome.NoOtherProfiles, readyStockOnly.stepOutcome(1))
        assertEquals("NO OTHER PROFILES", readyStockOnly.stepOutcome(1).utilityToastCaps())

        val ready = TweakerUtilityRowState(
            availability = TweakerUtilityAvailability.READY,
            selectedProfileId = "stock",
            selectableProfileIds = listOf("stock", "performance"),
        )
        assertTrue(ready.canStep)
        assertEquals(TweakerUtilityStepOutcome.Changed("performance"), ready.stepOutcome(1))
        assertEquals("TWEAKER · PERFORMANCE", ready.stepOutcome(1).utilityToastCaps())
        assertEquals(TweakerUtilityStepOutcome.Changed("performance"), ready.stepOutcome(-1))
    }

    @Test
    fun `stepping from a stale pick starts at the effective profile not the stored one`() {
        val stale = TweakerUtilityRowState(
            availability = TweakerUtilityAvailability.READY,
            selectedProfileId = "quiet",
            selectableProfileIds = listOf("stock", "balanced", "performance"),
        )
        // Effective is stock (index 0), so +1 lands on balanced. Stepping from the stored "quiet" would
        // have used indexOf == -1 and silently landed somewhere the player did not ask for.
        assertEquals(TweakerUtilityStepOutcome.Changed("balanced"), stale.stepOutcome(1))
    }

    /**
     * the row/toast must never say "certified" or "uncertified" to the
     * player, and a daemon-admitted profile must be honoured exactly like any other -- no separate
     * qualifier, no separate outcome shape. Converted from the earlier assertion that the
     * qualifier DID appear (`TWEAKER_UTILITY_UNCERTIFIED_QUALIFIER_CAPS`, since deleted).
     */
    @Test
    fun `no row value or toast ever names certification status`() {
        val ready = TweakerUtilityRowState(
            availability = TweakerUtilityAvailability.READY,
            selectedProfileId = "balanced",
            selectableProfileIds = listOf("stock", "quiet", "balanced", "performance"),
        )
        assertEquals("BALANCED", ready.valueLabelCaps)
        val changed = ready.stepOutcome(1)
        assertTrue(changed is TweakerUtilityStepOutcome.Changed)
        assertEquals("TWEAKER · PERFORMANCE", changed.utilityToastCaps())
        listOf(ready.valueLabelCaps, changed.utilityToastCaps()).forEach { text ->
            assertFalse("$text must not name certification status", text.contains("CERTIF"))
        }
    }

    @Test
    fun `capped values stay short enough for one Utility row`() {
        val values = listOf(
            TWEAKER_UTILITY_INSTALL_CAPS,
            TWEAKER_UTILITY_UNAVAILABLE_CAPS,
            TWEAKER_UTILITY_STOCK_ONLY_CAPS,
            tweakerProfileDisplayCaps("stock"),
            tweakerProfileDisplayCaps("performance"),
        )
        values.forEach { value ->
            assertEquals(value, value.uppercase())
            assertTrue("$value is too long for the capped row lane", value.length <= 16)
        }
    }
}
