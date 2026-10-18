package com.gruning.mimir.tweaker

internal data class FanNodes(
    val dutyPath: String,
    val periodPath: String,
    /**
     * Existence witness and hardware identity, NOT a sensor -- see [tachometerPath].
     *
     * `fanPresent` and the fan-node identity checks key off this path, so it stays. What it is not
     * is a reading of anything.
     */
    val speedPath: String,
    /**
     * Where a real fan tachometer lives, if this chassis has one. **Null on every handheld Mimir
     * currently supports, because neither of them has a tachometer at all.**
     *
     * Until 2026-08-26 `fanRpm` was read from [speedPath] and published as a measured fan speed. It
     * is not one. Proven on the tested hardware:
     * - With the fan audibly spinning at a CONSTANT duty (15000/50000) and a constant 60 C, thirty
     *   consecutive reads of `speed` gave 0 fifteen times, then 1800, 5400, 3900, 300, then 0 for
     *   the rest. A tachometer on a fan at fixed speed reads a fixed value.
     * - Sixty rapid reads gave 49 zeroes and eleven non-zero values spread across 300..6300 with no
     *   value repeating once.
     * - The same node reads mostly 0 on the Nova under the VENDOR's own SMART control, so this was
     *   never a Manual-mode symptom.
     * - The vendor's own controller says what it actually means: `FanBase: mSmartAction smartSpeed
     *   = 12000` logged every 4s on the Nova, whose `duty` node reads exactly 12000. The vendor's
     *   "speed" is COMMANDED PWM DUTY out of the 50000 period, not revolutions.
     * - Both chassis' `gpio5-pwm` interrupt counters are frozen, so there is no pulse train to
     *   count either.
     *
     * So every rpm this project has ever reported from that node was fabricated -- including the
     * "rpm 2400" that appeared in a device certification line on 2026-08-25. Reading rpm from a
     * separate, explicitly-null path is what stops a future lane quietly re-deriving it from
     * [speedPath] because the name looked right.
     */
    val tachometerPath: String? = null,
)

internal data class HandheldDeviceProfile(
    val id: String,
    val fanNodes: FanNodes?,
    val fanModeViaSettings: Boolean,
)

internal object HandheldDeviceProfiles {
    /**
     * Every device profile id this code mints for a named chassis, spelled once. [supportsFanMode] grants the
     * manual-fan family by the `ayn-`/`retroid-` prefix and [CpuCeilingTransport.ODIN_3_PROFILE] pins GPU
     * behaviour to one chip, so a downloaded family id must never be able to mint one of these through the
     * `"${id}-pserver"` fallback of [profileId] (F-P2-2, 2026-10-13 review): [FamilyCatalogLimits] rejects it.
     * Compile-time constants, so the pure catalog parser reads them without loading this object.
     */
    const val RP5_PROFILE_ID = "rp5"
    const val THOR_PROFILE_ID = "ayn-qcs8550-pserver"
    const val NOVA_PROFILE_ID = "retroid-qcs8550-pserver"

    private val gpio5Pwm2Fan = FanNodes(
        dutyPath = "/sys/class/gpio5_pwm2/duty",
        periodPath = "/sys/class/gpio5_pwm2/period",
        speedPath = "/sys/class/gpio5_pwm2/speed",
        // Absent by DEFAULT -- see FanNodes.tachometerPath for the device evidence. A chassis
        // must opt in to claiming a tachometer; the failure mode being closed here is a device
        // silently publishing a fabricated rpm because a node happened to be named "speed".
        tachometerPath = null,
    )

    /**
     * The RP5 keeps reading rpm from `speed`, and that is deliberate restraint, not an oversight.
     *
     * Every device shares [gpio5Pwm2Fan], so nulling the tachometer outright would also change the
     * RP5 -- the ONE device whose runtime calibration observation path actually consumes `fanRpm`
     * (`AndroidCalibration` returns UNSUPPORTED off the SM8250 family), and the one device not on
     * this desk. The evidence that `speed` is not a tachometer was gathered on the Odin 3 and the
     * Retroid Nova; the vendor framework is shared and it very probably holds there too, but
     * "probably" is not a measurement, and weakening an untestable device's calibration witness on
     * an inference is exactly the trade this project does not make.
     *
     * **OWED:** confirm on an RP5 whether `speed` is a real tachometer -- constant duty, thirty
     * reads, look for a constant value -- and if it is not, delete this and the branch that uses it.
     */
    private const val UNVERIFIED_RP5_TACHOMETER = "/sys/class/gpio5_pwm2/speed"

    /**
     * @param nodesPresent Defaults to a live [fanPresent] check against [reader]. Overridable so a
     * host JVM test can assert the widened [supportsFanMode] family actually gets handed nodes
     * without faking real `/sys/class/gpio5_pwm2` paths on the machine running the test -- no
     * production call site passes this, so [SystemReader] itself stays untouched.
     */
    fun resolve(
        manufacturer: String,
        model: String,
        socModel: String,
        reader: SystemReader,
        nodesPresent: (FanNodes) -> Boolean = { fanPresent(reader, it) },
    ): HandheldDeviceProfile {
        val isRp5 = manufacturer.equals("Moorechip", ignoreCase = true) &&
            model.contains("Retroid Pocket 5", ignoreCase = true)
        val id = if (isRp5) RP5_PROFILE_ID else profileId(manufacturer, model, socModel)
        // previously only this non-RP5 branch checked node presence
        // before RP5 (both RP5 branches always set fanModeViaSettings = true and only gated
        // fanNodes on presence) -- an id could be handed a Settings-mode grant it had no local PWM
        // nodes to back. One rule now, for every device: an id may claim fan authority only when
        // it is both in the widened supportsFanMode family AND the exact nodes are present on this
        // unit. Ambiguous branches fail toward cooling.
        val fanViaSettings = supportsFanMode(id) && nodesPresent(gpio5Pwm2Fan)
        // Only the RP5 still claims a tachometer, and only because it is unverified rather than
        // verified-present. See UNVERIFIED_RP5_TACHOMETER.
        val nodes = if (isRp5) {
            gpio5Pwm2Fan.copy(tachometerPath = UNVERIFIED_RP5_TACHOMETER)
        } else {
            gpio5Pwm2Fan
        }
        return HandheldDeviceProfile(
            id = id,
            fanNodes = nodes.takeIf { fanViaSettings },
            fanModeViaSettings = fanViaSettings,
        )
    }

    internal fun profileId(manufacturer: String, model: String, socModel: String): String {
        val isAyn = manufacturer.equals("AYN", ignoreCase = true)
        // Retroid ships under the ODM manufacturer string "Moorechip", the same one the RP5
        // reports; only the model separates the two devices.
        val isRetroid = manufacturer.equals("Moorechip", ignoreCase = true)
        val family = TweakerHardwareFamilies.resolve(socModel)
        val isOdin3 = model.contains("Odin3", ignoreCase = true) ||
            model.contains("Odin 3", ignoreCase = true)
        val isThor = model.contains("Thor", ignoreCase = true)
        val isNova = model.contains("Retroid Pocket Nova", ignoreCase = true)
        return when {
            isAyn && isOdin3 && family.id == "qualcomm-8-elite" ->
                CpuCeilingTransport.ODIN_3_PROFILE
            isAyn && isThor && family.id == "qualcomm-8gen2" ->
                THOR_PROFILE_ID
            // the Nova is a Retroid on the same
            // Snapdragon 8 Gen 2 silicon as AYN's Thor, so it must not inherit either the AYN
            // identity or the anonymous "qualcomm-8gen2-pserver" family fallback it lands on
            // today. deviceProfileId is hashed into HardwareFingerprint and is matched verbatim
            // by QualificationManifest.deviceProfile, so this id has to be pinned BEFORE any
            // Nova qualification run signs a manifest against it.
            //
            // KEPT per-device, deliberately, under the same-day SoC ruling that deleted the
            // per-device *access* gate in PServerClient. The two are different questions and
            // legitimately have different granularity:
            //
            //   access gating asks "may we speak to this service at all" -- a property of the
            //   channel, and the channel is identical on every device sharing the SoC and the
            //   vendor contract, which is exactly why it can be structural;
            //
            //   qualification asks "which measured, signed tuning envelope applies to THIS
            //   hardware" -- fan topology, thermal headroom and CPU ceilings are properties of the
            //   chassis, not the die. Two handhelds on one QCS8550 do not share a cooling solution.
            //
            // The failure modes are asymmetric, which settles it. An over-broad access gate refuses
            // a valid device: annoying, recoverable, visible. An over-broad qualification id lets a
            // manifest measured on one chassis validate against another and drive its silicon to
            // ceilings its cooling was never measured at. Ambiguous branches fail toward cooling.
            isRetroid && isNova && family.id == "qualcomm-8gen2" ->
                NOVA_PROFILE_ID
            else -> "${family.id}-pserver"
        }
    }

    fun fanPresent(reader: SystemReader, nodes: FanNodes): Boolean =
        reader.exists(nodes.dutyPath) && reader.exists(nodes.periodPath) && reader.exists(nodes.speedPath)

    /**
     * The fan-family rule covers every RP/AYN chip family. Widened from the three-id allow-list this
     * carried before (`rp5`, the exact Odin 3 id, the exact AYN Thor id) to the whole RP/AYN
     * identity family -- every id [HandheldDeviceProfiles.profileId] mints for an AYN or Retroid
     * chassis, keyed by the same manufacturer-prefix spelling `profileId` already uses ("ayn-"/
     * "retroid-") plus the hardcoded "rp5" identity.
     *
     * This function alone never grants fan authority to a device that lacks the nodes: [resolve]
     * additionally gates on live [fanPresent] before ever populating [HandheldDeviceProfile.fanNodes]
     * or setting [HandheldDeviceProfile.fanModeViaSettings]. A chassis whose id merely matches this
     * family but has no gpio5_pwm2 nodes -- or an unidentified device that happens to share a
     * manufacturer string with neither AYN nor Retroid, so falls to the bare
     * `"${family.id}-pserver"` id -- gets no fan axis regardless of what this returns.
     */
    fun supportsFanMode(deviceProfileId: String): Boolean =
        deviceProfileId == RP5_PROFILE_ID ||
            deviceProfileId.startsWith("ayn-") ||
            deviceProfileId.startsWith("retroid-")
}
