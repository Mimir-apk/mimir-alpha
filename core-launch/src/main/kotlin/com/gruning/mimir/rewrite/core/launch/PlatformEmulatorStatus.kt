package com.gruning.mimir.rewrite.core.launch

import com.gruning.mimir.rewrite.core.model.EmulatorId
import com.gruning.mimir.rewrite.core.model.EmulatorProfile
import com.gruning.mimir.rewrite.core.model.PlatformId

/**
 * Per-platform emulator resolution for display surfaces that do not have a game/file locator yet.
 *
 * This mirrors the launch engine's emulator rung: a live installed platform default wins when present;
 * otherwise Auto falls through to the first installed candidate that can actually carry the game, and
 * only then to any installed candidate at all (an honest open-shell emulator, so a player who owns only
 * that one can still open it). Stale defaults never brick the shelf.
 */
data class PlatformEmulatorStatus(
    val platformId: PlatformId,
    val candidates: List<EmulatorProfile>,
    val defaultEmulatorId: EmulatorId?,
    val resolved: EmulatorProfile?,
) {
    val unsupported: Boolean get() = candidates.isEmpty()
    val needsEmulator: Boolean get() = candidates.isNotEmpty() && resolved == null
}

/** Resolve the emulator a platform would use before a per-game Open-With override is involved. */
fun InstalledEmulatorProvider.resolvePlatformEmulatorStatus(
    platformId: PlatformId,
    defaultEmulatorId: EmulatorId? = null,
): PlatformEmulatorStatus {
    val candidates = candidatesFor(platformId)
    return PlatformEmulatorStatus(
        platformId = platformId,
        candidates = candidates,
        defaultEmulatorId = defaultEmulatorId,
        resolved = resolveInstalledEmulator(candidates, defaultEmulatorId, platformId),
    )
}

internal fun resolveInstalledEmulator(
    candidates: List<EmulatorProfile>,
    preferredEmulatorId: EmulatorId?,
    platformId: PlatformId? = null,
): EmulatorProfile? {
    if (preferredEmulatorId != null) {
        candidates.firstOrNull {
            it.id == preferredEmulatorId &&
                it.installed &&
                (platformId == null || ShippingLaunchProfiles.isCandidateDispatchSupported(platformId, it))
        }?.let { return it }
    }
    if (platformId != null) {
        // Tier 1 (Auto only — the explicit-override branch above already returned): a candidate whose
        // dispatch profile actually carries the game, e.g. aenu.aps3e (DIRECT_PAYLOAD) over com.armsx3
        // (OPEN_ONLY) for PS3 — device evidence 2026-08-11, see ShippingLaunchProfiles.isPayloadCapable.
        // `candidates` is registry order (most-compatible-first, EmulatorSeedRegistry.SEEDS declaration
        // order) so `firstOrNull` here is deterministic and independent of install order or any map
        // iteration; if two payload-capable candidates are installed, the one declared earlier wins.
        candidates.firstOrNull {
            it.installed &&
                ShippingLaunchProfiles.isDispatchSupported(platformId, it.packageName) &&
                ShippingLaunchProfiles.isPayloadCapable(platformId, it)
        }?.let { return it }
        // Tier 2: any dispatch-supported candidate, including an honest OPEN_ONLY shell — a player who
        // owns only e.g. com.armsx3 must still be able to open it rather than see NeedsEmulator.
        candidates.firstOrNull {
            it.installed &&
                ShippingLaunchProfiles.isDispatchSupported(platformId, it.packageName) &&
                ShippingLaunchProfiles.isCandidateDispatchSupported(platformId, it)
        }?.let { return it }
        return candidates.firstOrNull {
            it.installed && ShippingLaunchProfiles.isCandidateDispatchSupported(platformId, it)
        }
    }
    return candidates.firstOrNull { it.installed }
}
