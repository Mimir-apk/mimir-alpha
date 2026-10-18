package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Test

class HandheldDeviceProfileTest {
    @Test
    fun `Odin 3 owns a stable CQ8725S profile identity`() {
        listOf("CQ8725S", "SM8750").forEach { soc ->
            assertEquals(
                soc,
                "ayn-odin3-cq8725s-pserver",
                HandheldDeviceProfiles.profileId("ayn", "Odin3", soc),
            )
        }
    }

    @Test
    fun `AYN Snapdragon 8 Gen 2 aliases own one profile identity`() {
        listOf("QCS8550", "SM8550").forEach { soc ->
            assertEquals(
                "ayn-qcs8550-pserver",
                HandheldDeviceProfiles.profileId("AYN", "Thor", soc),
            )
        }
    }

    // supportsFanMode covers every RP/AYN chip family, widened from the three-id
    // allow-list this test used to exercise implicitly to the whole ayn-/retroid-/rp5 family, and
    // resolve() now gates fan authority on live node presence UNIFORMLY (see the retired-asymmetry
    // note on resolve() itself): before this, the non-RP5 branch handed out fanNodes on id
    // membership alone, so this test passed on a host JVM with no real `/sys/class/gpio5_pwm2` only
    // because node presence was never actually checked for AYN devices. It now is, for every
    // device, so this test drives it explicitly via resolve()'s nodesPresent seam rather than
    // depending on the test machine's real filesystem.
    @Test
    fun `qualified AYN profiles probe the shared live fan controller when the nodes are present`() {
        listOf(
            Triple("Odin3", "CQ8725S", "ayn-odin3-cq8725s-pserver"),
            Triple("Thor", "QCS8550", "ayn-qcs8550-pserver"),
        ).forEach { (model, soc, expectedId) ->
            val profile = HandheldDeviceProfiles.resolve(
                manufacturer = "AYN",
                model = model,
                socModel = soc,
                reader = SystemReader(),
                nodesPresent = { true },
            )

            assertEquals(expectedId, profile.id)
            assertEquals(true, profile.fanModeViaSettings)
            assertEquals("/sys/class/gpio5_pwm2/duty", profile.fanNodes?.dutyPath)
            assertEquals("/sys/class/gpio5_pwm2/period", profile.fanNodes?.periodPath)
            assertEquals("/sys/class/gpio5_pwm2/speed", profile.fanNodes?.speedPath)
        }
    }

    // Differential: dropping resolve()'s new fanPresent gate (reverting to the old
    // membership-alone grant) makes this test's negative half meaningless -- the widened
    // supportsFanMode family alone would then be sufficient to hand out fanNodes even when the
    // nodes are reported absent. Pinned here rather than only via the positive test above so a
    // revert of the GATE (not just the widened allow-list) is caught too.
    @Test
    fun `an AYN family id with no live nodes gets no fan authority even though the id qualifies`() {
        listOf(
            Triple("Odin3", "CQ8725S", "ayn-odin3-cq8725s-pserver"),
            Triple("Thor", "QCS8550", "ayn-qcs8550-pserver"),
        ).forEach { (model, soc, expectedId) ->
            assertEquals(expectedId, HandheldDeviceProfiles.profileId("AYN", model, soc))
            val profile = HandheldDeviceProfiles.resolve(
                manufacturer = "AYN",
                model = model,
                socModel = soc,
                reader = SystemReader(),
                nodesPresent = { false },
            )
            assertEquals(false, profile.fanModeViaSettings)
            assertEquals(null, profile.fanNodes)
        }
    }

    @Test
    fun `Retroid Pocket Nova owns a Retroid profile identity, not AYN and not the bare family`() {
        listOf("QCS8550", "SM8550").forEach { soc ->
            assertEquals(
                soc,
                "retroid-qcs8550-pserver",
                HandheldDeviceProfiles.profileId("Moorechip", "Retroid Pocket Nova", soc),
            )
        }
    }

    @Test
    fun `Nova identity needs the Retroid manufacturer, the Nova model and the 8 Gen 2 family`() {
        // Same model on foreign silicon, and the Nova model claimed by a foreign manufacturer,
        // both fall back rather than borrowing the Retroid identity.
        assertEquals(
            "qualcomm-8-elite-pserver",
            HandheldDeviceProfiles.profileId("Moorechip", "Retroid Pocket Nova", "CQ8725S"),
        )
        assertEquals(
            "qualcomm-8gen2-pserver",
            HandheldDeviceProfiles.profileId("Vendor", "Retroid Pocket Nova", "QCS8550"),
        )
        // The RP5 shares the "Moorechip" manufacturer string and must keep its own identity.
        assertEquals(
            "qualcomm-sm8250-pserver",
            HandheldDeviceProfiles.profileId("Moorechip", "Retroid Pocket 5", "SM8250"),
        )
    }

    // Smart mode exists on Nova but was not exposed in its Standard performance profile.
    // The same Smart-mode policy applies across AYN/Retroid chip families. The
    // qualification-run gate this test used to pin is RETIRED, not merely widened: the Nova is
    // confirmed on device to be running the exact target state (§1.3a's table: `fan_mode=4 SMART`,
    // `FanBase` logging every ~4s, `speed=5100 RPM`) with no per-Nova qualification run behind it.
    // supportsFanMode now grants the whole `retroid-`/`ayn-` family; live node presence (not a
    // qualification record) is the remaining, per-unit gate -- see resolve()'s own nodesPresent
    // seam and the sibling test below.
    @Test
    fun `the Nova now qualifies for the widened RP-AYN fan family, gated only on live node presence`() {
        assertEquals(true, HandheldDeviceProfiles.supportsFanMode("retroid-qcs8550-pserver"))

        val withNodes = HandheldDeviceProfiles.resolve(
            manufacturer = "Moorechip",
            model = "Retroid Pocket Nova",
            socModel = "QCS8550",
            reader = SystemReader(),
            nodesPresent = { true },
        )
        assertEquals("retroid-qcs8550-pserver", withNodes.id)
        assertEquals(true, withNodes.fanModeViaSettings)
        assertEquals("/sys/class/gpio5_pwm2/duty", withNodes.fanNodes?.dutyPath)
        assertEquals("/sys/class/gpio5_pwm2/period", withNodes.fanNodes?.periodPath)
        assertEquals("/sys/class/gpio5_pwm2/speed", withNodes.fanNodes?.speedPath)

        // The remaining, per-unit gate: a Nova-identified chassis reporting absent nodes still
        // gets nothing, exactly like every other device.
        val withoutNodes = HandheldDeviceProfiles.resolve(
            manufacturer = "Moorechip",
            model = "Retroid Pocket Nova",
            socModel = "QCS8550",
            reader = SystemReader(),
            nodesPresent = { false },
        )
        assertEquals(false, withoutNodes.fanModeViaSettings)
        assertEquals(null, withoutNodes.fanNodes)

        // On THIS test's own host JVM, with no real /sys/class/gpio5_pwm2, resolve()'s default
        // nodesPresent argument (a live fanPresent check) must agree with the explicit false case
        // above -- proving the default wiring, not just the seam, is honest about a fan-less
        // environment. [[verify-the-instrument-can-see-the-defect]]: real presence/absence on
        // hardware is Thor/device-verified, not asserted by this host test.
        val defaultReader = HandheldDeviceProfiles.resolve(
            manufacturer = "Moorechip",
            model = "Retroid Pocket Nova",
            socModel = "QCS8550",
            reader = SystemReader(),
        )
        assertEquals(false, defaultReader.fanModeViaSettings)
        assertEquals(null, defaultReader.fanNodes)
    }

    @Test
    fun `future Qualcomm family remains a qualification candidate`() {
        assertEquals(
            "qualcomm-adreno-candidate-pserver",
            HandheldDeviceProfiles.profileId("Vendor", "Handheld", "SM8650"),
        )
    }

    @Test
    fun `other SoCs receive a stable general live OPP profile identity`() {
        assertEquals(
            "general-live-opp-pserver",
            HandheldDeviceProfiles.profileId("Vendor", "Handheld", "MT6893"),
        )
    }

    @Test
    fun `AYN model and SoC family must both match an exact device profile`() {
        assertEquals(
            "qualcomm-sm8250-pserver",
            HandheldDeviceProfiles.profileId("AYN", "Odin3", "SM8250"),
        )
        assertEquals(
            "qualcomm-8-elite-pserver",
            HandheldDeviceProfiles.profileId("AYN", "Different Handheld", "CQ8725S"),
        )
        assertEquals(
            "qualcomm-8gen2-pserver",
            HandheldDeviceProfiles.profileId("AYN", "Different Handheld", "QCS8550"),
        )
    }

    // supportsFanMode widened from a three-id allow-list to the
    // whole ayn-/retroid-/rp5 identity family. Differential: reverting the widened body back to
    // the old `deviceProfileId == "rp5" || deviceProfileId == "ayn-odin3-cq8725s-pserver" ||
    // deviceProfileId == "ayn-qcs8550-pserver"` allow-list makes every "true" assertion below fail.
    @Test
    fun `supportsFanMode widened to the whole RP-AYN family, not any Qualcomm-shared identity`() {
        listOf(
            "rp5",
            "ayn-odin3-cq8725s-pserver",
            "ayn-qcs8550-pserver",
            "retroid-qcs8550-pserver",
            // A hypothetical future AYN/Retroid id on either prefix must also qualify: the rule is
            // the manufacturer prefix, not a hand-maintained enumeration of today's known ids.
            "ayn-future-handheld-pserver",
            "retroid-future-handheld-pserver",
        ).forEach { id ->
            assertEquals("$id must be in the widened RP/AYN family", true, HandheldDeviceProfiles.supportsFanMode(id))
        }

        // An unidentified device sharing AYN/Retroid's SILICON but not its manufacturer identity
        // (profileId's own bare "${family.id}-pserver" fallback) must NOT inherit fan authority --
        // the family is about the chassis, the same "two handhelds on one SoC do not share a
        // cooling solution" reasoning profileId's own KDoc already states for qualification.
        listOf(
            "qualcomm-8-elite-pserver",
            "qualcomm-8gen2-pserver",
            "qualcomm-sm8250-pserver",
            "qualcomm-adreno-candidate-pserver",
            "general-live-opp-pserver",
        ).forEach { id ->
            assertEquals(
                "$id is a bare chipset-family fallback, not an RP/AYN chassis identity",
                false,
                HandheldDeviceProfiles.supportsFanMode(id),
            )
        }
    }

    /**
     * **Which devices claim a tachometer, and which admit they have none.**
     *
     * `speedPath` and `tachometerPath` point at the same node name, which is exactly why they are
     * separate fields: the first is an existence witness used to identify this fan block, the second
     * is a sensor claim. Conflating them is how `fanRpm` came to be published as a measured fan
     * speed for the whole life of the project, on hardware that cannot measure it -- 0 on a fan
     * spinning at constant duty, non-zero values that never repeat, and a vendor controller whose
     * own `smartSpeed` is the commanded PWM duty.
     *
     * The RP5 keeps the legacy path deliberately: it is the one device whose calibration path
     * consumes `fanRpm` and the one device that was not on the desk when this was measured.
     * Unverified is not the same as verified-absent, and this test is where that distinction is
     * kept honest.
     */
    @Test
    fun `only the unverified RP5 still claims a tachometer`() {
        val nova = HandheldDeviceProfiles.resolve(
            manufacturer = "Moorechip",
            model = "Retroid Pocket Nova",
            socModel = "QCS8550",
            reader = SystemReader(),
            nodesPresent = { true },
        )
        val odin = HandheldDeviceProfiles.resolve(
            manufacturer = "AYN",
            model = "Odin3",
            socModel = "SM8750",
            reader = SystemReader(),
            nodesPresent = { true },
        )
        val rp5 = HandheldDeviceProfiles.resolve(
            manufacturer = "Moorechip",
            model = "Retroid Pocket 5",
            socModel = "SM8250",
            reader = SystemReader(),
            nodesPresent = { true },
        )

        listOf("Nova" to nova, "Odin" to odin).forEach { (name, profile) ->
            assertEquals(
                "$name has no tachometer -- measured on the hardware",
                null,
                profile.fanNodes?.tachometerPath,
            )
            assertEquals(
                "$name still uses the speed node as an existence witness",
                "/sys/class/gpio5_pwm2/speed",
                profile.fanNodes?.speedPath,
            )
        }

        assertEquals(
            "the RP5 is UNVERIFIED, not verified-absent, so its behaviour is left alone",
            "/sys/class/gpio5_pwm2/speed",
            rp5.fanNodes?.tachometerPath,
        )
    }
}
