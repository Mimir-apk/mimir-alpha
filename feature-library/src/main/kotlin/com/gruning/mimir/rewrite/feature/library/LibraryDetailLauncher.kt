package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.launch.OpenWithCandidate
import com.gruning.mimir.rewrite.core.launch.OpenWithOptions

/**
 * The name on a launch card's EMULATOR fact, shared by Main's Library detail and Main's Home launch card (round 5b).
 *
 * Loaded options name the emulator `LaunchController.openWithOptions` marks as the one the launch would start
 * (`OpenWithCandidate.isLaunchPick`). That is right in every loaded case, including the ones the old line got wrong:
 * an override that is not installed, not dispatchable, or no longer listed shows the Auto pick the launch really
 * falls to, never the stale override and never the platform default.
 *
 * With nothing marked (options not loaded yet, or loaded but nothing installed can carry the game) the row keeps the
 * text it showed before: [whenUnknown], passed by each caller exactly as it was (null lets the card paint its own
 * "Automatic"). This never decides whether the row is shown, so the card's layout does not move.
 */
fun launchCardEmulatorLabel(openWith: OpenWithOptions?, whenUnknown: String? = null): String? =
    openWith?.candidates?.firstOrNull { it.isLaunchPick }?.label ?: whenUnknown

/** Main's Library detail. Its text with no name was always the card's own "Automatic", so it passes nothing. */
internal fun libraryDetailLauncherLabel(openWith: OpenWithOptions?): String? = launchCardEmulatorLabel(openWith)

/**
 * The options as they will read once a pick has been written: the picked emulator is the override and the one the launch will start, no other
 * candidate is, and the game has an override. Applied the moment the player picks, so the card's EMULATOR fact names the new emulator at once
 * and never the old one while the write and the reload land (review 2026-10-14). The reload replaces it with the truth: Auto resolution or a
 * picked emulator that cannot launch the game may still decide differently, and the reload says so. Shared by Main's Library detail and Home's card.
 */
fun openWithOptionsAfterPick(options: OpenWithOptions, picked: OpenWithCandidate): OpenWithOptions = options.copy(
    hasOverride = true,
    candidates = options.candidates.map { candidate ->
        val isPick = candidate.emulatorId == picked.emulatorId
        candidate.copy(isCurrentOverride = isPick, isLaunchPick = isPick)
    },
)
