package com.gruning.mimir.rewrite.feature.settings

import com.gruning.mimir.rewrite.core.model.BiosCandidateRegistry
import com.gruning.mimir.rewrite.core.model.PlatformId

/**
 * One row of the BIOS DETECTION list (LG01 2026-07-18 — there is no "helper"; the Tools tab that named one
 * is retired): a system the user actually has games for that needs (or optionally uses) a BIOS/firmware
 * file, the canonical file NAME, whether it is required, and a friendly "where it lives" hint.
 *
 * This is REAL data, not sample — the requirement facts are canonical emulation knowledge. What Mimir does
 * NOT claim is presence: under Android scoped storage it cannot read an emulator's private BIOS folder, so
 * the UI is honest that presence is unverifiable here (policy `launch-silent-death-and-playcount`:
 * "honest about scoped-storage 'unknown'"). Posture: the file name + placement are metadata ONLY — Mimir
 * never provides a BIOS/firmware file or tells the user where to obtain one (`mimir-project-posture`).
 */
data class BiosRequirementRow(
    /** Friendly platform label (e.g. "PlayStation 2"). */
    val platform: String,
    /** The canonical BIOS file name — an identity, not a path or a source (e.g. "scph5501.bin"). */
    val fileName: String,
    /** True when the system cannot run without it; false when it only improves accuracy/compatibility. */
    val required: Boolean,
    /** A friendly hint of where the emulator keeps its BIOS folder — never an acquisition instruction. */
    val placement: String,
)

/**
 * Canonical per-platform BIOS/firmware requirements — file NAMES and placement hints only (metadata).
 *
 * Intentionally omits systems whose Android emulators run HLE with no user-supplied BIOS (NES/SNES/Genesis/
 * Game Boy/PSP/GameCube/Wii/etc.), and omits current-gen firmware/keys entirely (posture: Mimir never points
 * a user at firmware). Only systems where a BIOS file is genuinely part of setup appear, so the helper shows
 * the user exactly the systems that need attention and nothing that would read as "go find this file".
 */
object BiosRequirementRegistry {
    /** The canonical BIOS row for a platform id, projected from the detector's candidate table. */
    fun rowFor(
        platformIdValue: String,
        platformLabel: String,
        emulatorRequiresBios: Boolean,
    ): BiosRequirementRow? {
        val spec = BiosCandidateRegistry.forPlatform(PlatformId(platformIdValue)) ?: return null
        val requiredGroups = spec.candidates.filter { it.required }.groupBy { it.groupKey }
        val groups = requiredGroups.ifEmpty { spec.candidates.groupBy { it.groupKey } }

        // Name ONE file per alternative group. Listing every member reads as "you need all of these",
        // which is exactly backwards for region variants — a user needs one. Match PATTERNS (globs and
        // bare extensions) are never shown; they are how Mimir matches, not something a user places.
        val shown = groups.values.mapNotNull { members ->
            val literal = members.firstOrNull { '*' !in it.fileName } ?: return@mapNotNull null
            if (members.size > 1) "${literal.fileName} (or another regional version)" else literal.fileName
        }

        return BiosRequirementRow(
            platform = platformLabel,
            fileName = shown.joinToString(", ").ifEmpty { spec.consoleBiosLabel ?: "a console BIOS" },
            // PS1 is a console-BIOS requirement regardless of whether a package has resolved yet:
            // DuckStation and RetroArch both consume the same user-provided console BIOS. This is a
            // Settings-only projection; LaunchReadiness remains per resolved emulator and RetroArch is
            // deliberately not promoted to a global requires-BIOS target.
            required = emulatorRequiresBios || spec.platformId == PlatformId.PS1,
            placement = spec.placementHint,
        )
    }
}
