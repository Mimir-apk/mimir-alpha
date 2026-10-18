package com.gruning.mimir.rewrite.core.model

import kotlinx.serialization.Serializable

/**
 * Stable identity value types for the Mimir domain.
 *
 * These are deliberately thin `value class` wrappers over [String] so the codebase can never pass a
 * raw ambiguous string where a typed key is meant; sealed classes, enums and value types encode
 * distinct domain meanings. They cost nothing at runtime.
 */

/**
 * The stable identifier of an emulated **platform / system** — e.g. `"ps1"`, `"gba"`, `"ds"`,
 * `"snes"`, `"nec_pc_engine_turbografx_16"`.
 *
 * Ground truth from the current product: the platform id is the classifier key produced from a
 * file's extension / folder, and for RetroArch-served systems **the catalog long-id IS the launch
 * platform id** (see local-only policy). Short built-in ids (`ps1`, `gba`, …)
 * and catalog long-ids (`nec_pc_engine_turbografx_16`) both flow through here; do NOT invent aliases.
 */
@Serializable
@JvmInline
value class PlatformId(val value: String) {
    init {
        require(value.isNotBlank()) { "PlatformId must not be blank" }
    }

    override fun toString(): String = value

    companion object {
        // A few built-in ids named for convenience / test grounding. NOT an allowlist — any
        // classifier-produced or catalog long-id is a valid PlatformId.
        val GB = PlatformId("gb")
        val GBC = PlatformId("gbc")
        val GBA = PlatformId("gba")
        val NES = PlatformId("nes")
        val SNES = PlatformId("snes")
        val N64 = PlatformId("n64")
        val DS = PlatformId("ds")
        val THREE_DS = PlatformId("3ds")
        val PS1 = PlatformId("ps1")
        val PS2 = PlatformId("ps2")
        val PSP = PlatformId("psp")
        val GAMECUBE = PlatformId("gamecube")
        val WII = PlatformId("wii")
        val DREAMCAST = PlatformId("dreamcast")
        val SATURN = PlatformId("saturn")
        val ARCADE = PlatformId("arcade")
        val SWITCH = PlatformId("switch")

        /** R-2b/R-23-25: GameNative's synthetic PC-games shelf (Steam/Epic/GOG/Amazon/custom shortcuts). */
        val PC_GAMES = PlatformId("pc_games")
    }
}

/**
 * The stable identifier of a single game **as the user perceives it** — i.e. the identity of the
 * dedup *group*, not of a raw file on disk.
 *
 * This matters for the dedup rulings : region/version variants,
 * multi-disc sets, and cross-storage duplicates all collapse to ONE tile, and a favorite maps to the
 * GROUP. So a favorite / launch / hide keyed by [GameId] survives collapse and disc-swapping.
 *
 * The current product derives the group identity from (in preference order): exact content hash,
 * catalog title-id / serial, then a multi-disc `duplicate_group_id`. See [GameIdentity] for the
 * evidence, and [ResidentFile.entryKey] for the per-file key.
 */
@Serializable
@JvmInline
value class GameId(val value: String) {
    init {
        require(value.isNotBlank()) { "GameId must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * The stable **per-file** key of one resident file on disk. Distinct from [GameId]: a multi-disc
 * game has several [EntryKey]s (one per disc) but one [GameId]. Mirrors the current native
 * `library_entry.entry_key` primary key.
 */
@JvmInline
value class EntryKey(val value: String) {
    init {
        require(value.isNotBlank()) { "EntryKey must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * The stable local profile key. The storage key is a
 * fixed `LOCAL_PROFILE_ID = "local"` — **never** the user's display name. The display name is
 * optional local metadata carried separately.
 */
@JvmInline
value class ProfileId(val value: String) {
    init {
        require(value.isNotBlank()) { "ProfileId must not be blank" }
    }

    override fun toString(): String = value

    companion object {
        /** The single-profile build's stable key. Do not show this as a name. */
        val LOCAL = ProfileId("local")
    }
}
