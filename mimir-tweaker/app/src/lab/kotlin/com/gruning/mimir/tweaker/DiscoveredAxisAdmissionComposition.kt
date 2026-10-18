package com.gruning.mimir.tweaker

/**
 * LAB posture: discovery is the real hardware PLUS one synthetic cluster no silicon has.
 *
 * **This is the discovery INPUT to admission, never admission itself, and the distinction is the
 * entire value of the proof.** `DiscoveredAxisAdmissionCheck.admit` runs byte-identical against the
 * union that it runs against the bare probe: all six refusal codes are live, the per-cpu OPP rule is
 * live, `INT_MAX` is still the only value legal everywhere, and every `read()` re-admits. What changes
 * is one thing — the set of cpus the process believes exist. A lab flag that made `admit` return
 * `Admitted` would prove nothing at all; this repo has that lesson written down at `0fbb8053`, and it
 * cost a whole evidence package.
 *
 * **Statically armed, with no arming IPC, and that is safe for a reason that can be checked.** This
 * record authorises nothing: the daemon writes no hardware on this path. A record naming cpu 240
 * reaching an app that is NOT running the synthetic axis fails `recordedSessionStillInForce` — the live
 * QoS node has no cpu 240 in it — and is discarded at cold start. So the worst case of a permanently
 * armed union is a memo about a machine that does not exist, which the reader already knows how to
 * throw away. An arming AIDL method would have been a new privileged surface and a protocol that can
 * itself fail, which is a strictly worse trade for a diagnostic.
 *
 * The class is compiled into the LAB APK only. `src/safe/kotlin` and `src/production/kotlin` carry an
 * identity body of the same function, so the shipping APKs do not contain this type at all — an absent
 * symbol rather than a branch that is merely never taken.
 */
internal fun composeDiscoveredAxisAdmission(discovered: DiscoveredAxisAdmission): DiscoveredAxisAdmission =
    LabUnionDiscoveredAxisAdmission(discovered)

internal class LabUnionDiscoveredAxisAdmission(
    private val discovered: DiscoveredAxisAdmission,
) : DiscoveredAxisAdmission {

    /**
     * The synthetic table first, the real one second — so the REAL map wins any id collision.
     *
     * Kotlin's `Map.plus` lets the right operand overwrite, and that direction is deliberate: if some
     * future SoC ever did publish a cpu 240, its own OPP table is the truth and the synthetic one must
     * not shadow it. A proof that could silently replace a device's real frequency set with a fixture
     * would be a way to launder a bad record through this process's authority.
     */
    override fun cpuFrequenciesKhz(): Map<Int, Set<Int>> =
        LAB_CPU_FREQUENCIES + discovered.cpuFrequenciesKhz()

    /** Untouched. The synthetic axis is CPU-only, so the GPU answer stays this device's own. */
    override fun gpuPowerLevelCount(): Int = discovered.gpuPowerLevelCount()

    private companion object {
        private val LAB_CPU_FREQUENCIES: Map<Int, Set<Int>> =
            TweakerLabTunerAxisContract.frequenciesKhz().toSet().let { published ->
                TweakerLabTunerAxisContract.cpuIds().associate { cpuId -> cpuId to published }
            }
    }
}
