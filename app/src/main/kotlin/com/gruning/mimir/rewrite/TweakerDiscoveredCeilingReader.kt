package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.tweaker.TweakerCeilingStep
import com.gruning.mimir.rewrite.core.tweaker.TweakerDiscoveredCeiling

/**
 * The one CPU policy the Utility rail's single PERFORMANCE row speaks for.
 *
 * **the PRIME cluster** — the thermal and battery lever. Capping the GPU blind
 * from a one-line row invites visible jank, and a row whose subject moves on its own was already ruled
 * broken on this surface.
 *
 * **PRIME is DISCOVERED here, never named.** `cpuClusterLabel` only says "PRIME" on a three-cluster
 * device and falls back to `CPU <id>` otherwise, so matching that word would silently pick nothing on a
 * two- or four-cluster handheld. The big core is instead the one whose table reaches highest, which is
 * true of every big.LITTLE arrangement without knowing how many clusters there are. The tie-break is the
 * higher policy id purely so the answer is stable — two clusters sharing a top frequency are the same
 * choice either way, but an unstable one would move the row's subject between reads.
 */
internal fun primeCpuPolicy(policies: List<MimirTunerPolicy>): MimirTunerPolicy? =
    policies
        .filter { it.kind == MimirTunerPolicy.Kind.CPU && it.availableKhz.isNotEmpty() }
        .maxWithOrNull(compareBy<MimirTunerPolicy> { it.availableKhz.last() }.thenBy { it.id })

/**
 * Project what the kernel publishes into the primitives the Utility row can carry.
 *
 * Returns null when this SoC publishes no CPU table at all — R3's reserved case, where UNAVAILABLE is
 * the honest word and the row keeps saying it.
 *
 * Every label comes from [companionTunerClockLabel], which is the product's single kHz spelling. It is
 * NOT upper-cased to match the rail's other capped values: `GHZ` would be a second spelling of one unit,
 * which is the dialect defect this rail's own ruling names. One quantity, one spelling, everywhere.
 */
internal fun discoveredCeilingRow(availability: MimirTunerAvailability): TweakerDiscoveredCeiling? {
    val policy = primeCpuPolicy(availability.policies) ?: return null
    return TweakerDiscoveredCeiling(
        policyLabel = policy.label,
        ceilingLabelCaps = companionTunerClockLabel(policy.currentMaxKhz),
        steps = policy.availableKhz.map { TweakerCeilingStep(khz = it, labelCaps = companionTunerClockLabel(it)) },
        currentKhz = policy.currentMaxKhz,
        // Read and write are independent facts on this path — a device can publish tables it will not
        // let anyone change, and the row renders that as a value it cannot step.
        canWrite = availability.canWrite,
    )
}
