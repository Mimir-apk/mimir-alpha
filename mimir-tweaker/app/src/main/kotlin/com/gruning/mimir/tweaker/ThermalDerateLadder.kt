package com.gruning.mimir.tweaker

/**
 * Thermal-derate ladders are data so rung changes do not alter decision logic.
 * [HardwareSessionSupervisor] selects a rung; this decision layer alone writes no hardware.
 *
 * CPU and GPU share a thermal/power envelope. Clamping CPU alone frees budget the GPU may spend,
 * and large one-domain swings can disturb frame pacing, so each rung moves a CPU/GPU pair.
 * Power allocation favors the GPU within a fixed budget; thermal derating reduces that budget.
 *
 * policy0 stays at the profile's nominal on every rung. policy6 is the configured prime-cluster derate target;
 * whether policy0 should also derate remains unknown until per-cluster thermal attribution maps
 * cpuss-0/cpuss-1 to policies. [ladder] binds every rung to its passed policy0MaxKhz.
 *
 * [PROFILES] holds a separate ladder per profile, each strictly dominated by that profile's signed
 * nominal. Balanced cannot begin at 2.84GHz when its nominal is already 2.44GHz. Thermal events
 * derate rather than release to Stock; sharing a ladder across unchecked nominals breaks that bound.
 *
 * Calibration limits differ by profile:
 * - Performance rung1 targets about 2.8GHz prime CPU and 967MHz GPU; both axes were verified
 *   on device, 2026-08-06. Rungs2-3, including the
 *   floor, are conservative extrapolations without device verification.
 * - Balanced and quiet nominals are configured targets; all their rungs are extrapolations and
 *   remain unverified on device.
 *
 * [PROFILES] is the authoritative table; nominals can be cross-checked against [TuningPlanner]'s signed vectors. Extrapolated rungs need device calibration before being treated as final.
 */
internal data class ThermalDerateRung(
    val policy0MaxKhz: Int,
    val policy6MaxKhz: Int,
    /** Inverted, per the vendor: LARGER number = SLOWER. 0 = 1100 MHz fastest, 13 = 160 MHz
     * slowest. A derate INCREASES this number -- getting that backwards would overclock the GPU
     * during a thermal event. See [ThermalDerateLadderTest] for the monotonicity guard. */
    val gpuMaxPwrLevel: Int,
)

/**
 * One profile's full ladder: its own signed nominal (rung 0 -- not itself a member of [rungs])
 * plus the ordered derate steps below it. [maxRung] and [at] mirror the semantics WAVE 2 first
 * defined on the single (then Performance-only) ladder.
 */
internal data class ThermalDerateProfileLadder(
    val nominal: ThermalDerateRung,
    /**
     * Index 0 == rung 1. The last entry doubles as the floor (remain there; never release --
     * spec S3.1/S3.5). Every entry must stay strictly dominated by [nominal] on `policy6`
     * (slower CPU) and no faster on GPU (slower-or-equal); see [ThermalDerateLadderTest] for the
     * by-construction proof this file's data alone cannot guarantee.
     */
    val rungs: List<ThermalDerateRung>,
) {
    /** Highest valid rung index (1-based); also the floor. */
    val maxRung: Int get() = rungs.size

    /** 1-based; 0 (or below) means nominal (no derate). Above [maxRung] clamps to the floor. */
    fun at(rung: Int): ThermalDerateRung = when {
        rung <= 0 -> nominal
        rung >= rungs.size -> rungs.last()
        else -> rungs[rung - 1]
    }
}

internal object ThermalDerateLadder {

    /**
     * Builds one profile's ladder, pinning [policy0MaxKhz] onto the nominal and every rung by
     * construction -- the value is written once, not per rung, so it cannot drift. [steps] is
     * `policy6MaxKhz to gpuMaxPwrLevel`, in ladder order; the last pair is the floor.
     */
    private fun ladder(
        policy0MaxKhz: Int,
        nominalPolicy6Khz: Int,
        nominalGpuLevel: Int,
        vararg steps: Pair<Int, Int>,
    ): ThermalDerateProfileLadder = ThermalDerateProfileLadder(
        nominal = ThermalDerateRung(policy0MaxKhz, nominalPolicy6Khz, nominalGpuLevel),
        rungs = steps.map { (policy6Khz, gpuLevel) -> ThermalDerateRung(policy0MaxKhz, policy6Khz, gpuLevel) },
    )

    /**
     * Sequence-9 per-profile ladders . Nominals are the signed
     * sequence-8/9 vectors (performance unchanged from sequence 8; balanced/quiet are sequence
     * 9's configured S1 vectors, mirrored from [TuningPlanner]'s
     * `MIMIR_CERTIFIED_FAMILY_CPU_TARGETS_KHZ` / `ODIN_3_*_GPU_MAX_POWER_LEVEL` -- the GPU half of balanced is a
     * retired signed vector, see its own note). Rung provenance
     * is documented on the file's class doc above -- only performance's rung 1 is
     * device-verified; every other rung here is TD extrapolation pending sign-off.
     */
    private val PROFILES: Map<String, ThermalDerateProfileLadder> = mapOf(
        "performance" to ladder(
            policy0MaxKhz = 1_996_800,
            nominalPolicy6Khz = 3_072_000,
            nominalGpuLevel = 0,
            2_841_600 to 2, // rung 1 -- both axes verified on-device 2026-08-06.
            2_649_600 to 2, // rung 2 -- Conservative extrapolation; unverified on device.
            2_438_400 to 3, // rung 3 == floor -- Conservative extrapolation; unverified on device.
        ),
        "balanced" to ladder(
            policy0MaxKhz = 1_996_800,
            nominalPolicy6Khz = 2_438_400,
            // Words only (2026-10-13): level 2 is the retired sequence-10 signed vector. TuningPlanner no
            // longer plans it -- Balanced is uncapped (level 0) on the 8 Elite, the current profile configuration -- so this nominal is NOT a mirror of the planner for the GPU axis.
            nominalGpuLevel = 2,
            2_246_400 to 3, // rung 1 -- Conservative extrapolation; unverified on device (ladder is new in seq 9).
            1_958_400 to 3, // rung 2 -- Conservative extrapolation; unverified on device.
            1_958_400 to 4, // rung 3 == floor -- Conservative extrapolation; unverified on device.
        ),
        "quiet" to ladder(
            policy0MaxKhz = 1_785_600,
            nominalPolicy6Khz = 2_246_400,
            nominalGpuLevel = 5,
            1_958_400 to 5, // rung 1 -- Conservative extrapolation; unverified on device (ladder is new in seq 9).
            1_958_400 to 6, // rung 2 -- Conservative extrapolation; unverified on device.
            1_689_600 to 6, // rung 3 == floor -- Conservative extrapolation; unverified on device.
        ),
    )

    val profileIds: Set<String> get() = PROFILES.keys

    /** The full per-profile ladder -- nominal plus rungs -- for [profileId]. */
    fun forProfile(profileId: String): ThermalDerateProfileLadder = PROFILES.getValue(profileId)

    /**
     * Every profile ladder currently has the same rung count (3), so one shared bound suffices.
     * [HardwareSessionSupervisor] tracks a single abstract rung POSITION across all four thermal
     * channels (WAVE2 S3.5) -- one physical CPU+GPU state, not four independent per-sensor ladders
     * -- and clamps it against this profile-agnostic value rather than a specific ladder's.
     *
     * That position IS resolved per-profile at actuation time: [HardwareSessionSupervisor.execute]'s
     * `is Derate` branch (Stage B) resolves the same rung number to the ACTIVE session's
     * profile-specific SIGNED plan (see [GenerationBoundDerater]), never a hardcoded one -- only the
     * shared rung COUNT bound here stays profile-agnostic. [ThermalDerateLadderTest] pins that every
     * profile agrees with this value so a future profile with a different rung count cannot
     * silently drift from it unnoticed.
     */
    val maxRung: Int = PROFILES.getValue("performance").maxRung
}

/**
 * LAB-only rung verification programme : resolves a caller-supplied `(profileId, rungIndex)`
 * pair against [ThermalDerateLadder] with clean rejection, never a silent default. Deliberately
 * does NOT use [ThermalDerateProfileLadder.at]'s own clamping semantics for the index bound --
 * `at` clamps out-of-range indices to nominal/floor by design (it exists to make the *supervisor*
 * fail-safe under drift), but a diagnostic that asks for rung 4 of a 3-rung ladder must fail
 * loudly, never quietly apply the floor instead.
 */
internal object ThermalDerateRungRequest {
    fun resolve(profileId: String, rungIndex: Int): ThermalDerateRungResolution {
        if (profileId !in ThermalDerateLadder.profileIds) {
            return ThermalDerateRungResolution.UnknownProfile
        }
        val ladder = ThermalDerateLadder.forProfile(profileId)
        if (rungIndex < 1 || rungIndex > ladder.maxRung) {
            return ThermalDerateRungResolution.OutOfRange(ladder.maxRung)
        }
        return ThermalDerateRungResolution.Resolved(ladder.at(rungIndex))
    }
}

internal sealed interface ThermalDerateRungResolution {
    data class Resolved(val rung: ThermalDerateRung) : ThermalDerateRungResolution
    data object UnknownProfile : ThermalDerateRungResolution
    data class OutOfRange(val maxRung: Int) : ThermalDerateRungResolution
}
