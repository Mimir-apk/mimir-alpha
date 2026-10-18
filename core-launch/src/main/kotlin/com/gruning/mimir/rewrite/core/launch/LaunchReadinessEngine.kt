package com.gruning.mimir.rewrite.core.launch

import com.gruning.mimir.rewrite.core.model.EmulatorProfile
import com.gruning.mimir.rewrite.core.model.LaunchLocatorForm
import com.gruning.mimir.rewrite.core.model.LaunchProfile
import com.gruning.mimir.rewrite.core.model.LaunchReadiness
import com.gruning.mimir.rewrite.core.model.LaunchTarget

/**
 * Resolves whether a game can launch RIGHT NOW ([LaunchReadiness]) from its [LaunchProfile] and the
 * device state exposed by the ports. Pure orchestration — every device fact comes through a port, so
 * this whole class is unit-testable with fakes (no native bridge, no `Context`).
 *
 * Decision order (each rung is a distinct readiness state):
 *  1. Emulator — resolve the target (Auto default-to-installed, or the Open-With override). No
 *     installed candidate → [LaunchReadiness.NeedsEmulator]; no candidates at all → [Unsupported].
 *     BLOCKS: there is no installed emulator to hand a launch off to.
 *  2. BIOS — if the platform requires BIOS and a required one is confirmed missing, remember it as
 *     [LaunchReadiness.NeedsBios] but keep resolving (a confirmed-missing
 *     BIOS must not trap the player behind Mimir — the installed emulator is where they can actually
 *     act, so the launch still proceeds). Honest UNKNOWN presence is likewise surfaced but never
 *     blocked, per the console posture: BIOS honesty, not a hard veto on "unknown".
 *  3. File — resolve the launch locator; unreachable → [LaunchReadiness.MissingFile]. BLOCKS even
 *     when BIOS also failed: there is genuinely no locator to hand the emulator, so nothing could be
 *     attempted — MissingFile wins over a pending BIOS reason.
 *  4. Otherwise → a fully-built [LaunchTarget]: [LaunchReadiness.NeedsBios] with that target attached
 *     when step 2 remembered a BIOS gap, else plain [LaunchReadiness.Ready].
 *
 * NOTE : certification is NEVER consulted here. A placed
 * game with an installed emulator is Ready regardless of cert; cert is a disclaimer carried elsewhere.
 */
class LaunchReadinessEngine(
    private val emulators: InstalledEmulatorProvider,
    private val overrides: OpenWithOverrideLookup,
    private val bios: BiosPresenceProvider,
    private val locators: LaunchLocatorProvider,
) {

    fun resolve(profile: LaunchProfile): LaunchReadiness {
        // ── 1. Emulator target ────────────────────────────────────────────────
        val candidates = emulators.candidatesFor(profile.platformId)
        if (candidates.isEmpty()) {
            return LaunchReadiness.Unsupported(profile.platformId)
        }

        val emulator = resolveEmulator(profile, candidates)
            ?: return LaunchReadiness.NeedsEmulator(
                platformId = profile.platformId,
                candidates = candidates.filter {
                    !it.installed || !ShippingLaunchProfiles.isCandidateDispatchSupported(profile.platformId, it)
                },
            )

        // ── 2. BIOS ───────────────────────────────────────────────────────────
        // BIOS truth is emulator-aware (native launch seam contract): resolve for the CHOSEN emulator and
        // keep only the profiles that apply to it (platform-wide rows always apply). PS2 forks etc.
        // have different BIOS folders, so filtering by the resolved emulator is what makes readiness honest.
        //
        // Play remains dispatchable while stating any unverified launch support: a confirmed-missing BIOS is correct
        // knowledge, not a reason to dead-end Play — the installed emulator is where the player can
        // actually import the file, and Mimir was trapping them before they could even reach it. So
        // this rung REMEMBERS the reason instead of returning early; steps 3–4 still run, and the
        // reason (with its resolved target attached) is what gets returned at the bottom.
        var biosReason: LaunchReadiness.NeedsBios? = null
        if (profile.requiresBios) {
            val profiles = bios.biosFor(profile.platformId, emulator.id)
                .filter { it.appliesTo(emulator.id) }
            val blockingMissing = profiles.filter { it.isBlockingMissing }
            if (blockingMissing.isNotEmpty()) {
                biosReason = LaunchReadiness.NeedsBios(
                    platformId = profile.platformId,
                    missing = blockingMissing,
                    presenceUnknown = false,
                )
            }
            // Honest "unknown" (scoped storage can't confirm) is surfaced but does NOT block launch —
            // the launch model tells the truth, then still lets A fire the intent.
        }

        // ── 3. File locator ───────────────────────────────────────────────────
        // Resolve the CHOSEN group member (disc / storage sibling), not just the group id — otherwise
        // a multi-disc/grouped game can launch the wrong member (native launch seam contract). A null member
        // key lets the provider pick the group's default launch member. Runs even when [biosReason] is
        // set: MissingFile still BLOCKS unconditionally, because there is no locator to hand the
        // emulator at all — unlike a missing BIOS, reaching the emulator would not help here.
        val locatorResult = if (emulator.locatorForm == LaunchLocatorForm.NONE) {
            LaunchLocatorResult("", LaunchLocatorForm.NONE)
        } else {
            locators.locatorFor(
                gameId = profile.gameId,
                launchMemberEntryKey = profile.launchMemberEntryKey,
                preferredDisc = profile.preferredDisc,
                preferredForm = emulator.locatorForm,
            ) ?: return LaunchReadiness.MissingFile(profile.gameId)
        }

        // ── 4. Ready (or the remembered NeedsBios, now carrying the same target) ───────────────────
        val target = LaunchTarget(
            gameId = profile.gameId,
            platformId = profile.platformId,
            emulator = emulator,
            locator = locatorResult.locator,
            locatorForm = locatorResult.form,
            title = titleFallback(profile),
            overrideApplied = profile.hasOverride,
        )
        return biosReason?.copy(target = target) ?: LaunchReadiness.Ready(target)
    }

    /**
     * Resolve the emulator to launch with:
     *  - If the game has an Open-With override AND that emulator is an INSTALLED candidate → use it.
     *    (An override pointing at a not-installed emulator falls through to Auto — the picker filters
     *    on installed, and a stale override must not brick the launch.)
     *  - Otherwise Auto: the first INSTALLED candidate (candidates are most-compatible-first).
     *  - Returns null when no candidate is installed (→ NeedsEmulator).
     *
     * `internal` (not private) so the [LaunchController] can resolve the emulator's locator FORM ahead
     * of running the full engine — the locator port is synchronous but the DAO read is suspend, so the
     * controller must know the resolved emulator's form to pre-fetch the locator. Reusing this ONE rule
     * (rather than duplicating it in the controller) keeps Auto/override behavior in a single place.
     */
    internal fun resolveEmulator(
        profile: LaunchProfile,
        candidates: List<EmulatorProfile>,
    ): EmulatorProfile? = resolveInstalledEmulator(candidates, profile.overrideEmulatorId, profile.platformId)

    private fun titleFallback(profile: LaunchProfile): String = profile.gameId.value

    /** Convenience: is this profile launchable right now? */
    fun isReady(profile: LaunchProfile): Boolean = resolve(profile).isReady
}

/** The locator form a platform's chosen emulator prefers, when the caller has one; else EITHER. */
fun EmulatorProfile.preferredLocatorForm(): LaunchLocatorForm = locatorForm
