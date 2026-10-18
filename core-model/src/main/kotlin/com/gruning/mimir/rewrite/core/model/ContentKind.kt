package com.gruning.mimir.rewrite.core.model

/**
 * What a resident file/entry *is* to Mimir, independent of its platform.
 *
 * Grounds the native `library_entry.content_kind` column and the classifier's notion of "this is a
 * playable ROM" vs "this is a multi-disc playlist" vs "this is a support file (BIOS/DLC) that is not
 * a standalone game". Kept small and closed; extend only with a reviewed domain-contract change.
 */
enum class ContentKind {
    /** A single, directly-launchable game image (the common case: a `.gba`, `.nds`, `.chd`, `.iso`). */
    GAME,

    /**
     * An `.m3u` (or `.cue`-referenced) playlist that groups the discs of one multi-disc game. In the
     * current product this is the PRIMARY member of a multi-disc group and is what launch targets;
     * the emulator handles disc-swap. See `dedup-grouping-rulings` (m3u-primary fold).
     */
    MULTI_DISC_PLAYLIST,

    /**
     * A `.cue`/`.ccd`/`.gdi` track manifest describing the layout of ONE disc split into multiple
     * `.bin`/`.img` tracks. It is the PRIMARY / launch member of a track set (the emulator reads it to
     * locate the tracks); the individual tracks are hidden members. Distinct from [MULTI_DISC_PLAYLIST],
     * which groups the DISCS of a multi-disc game — a track manifest is a single disc.
     */
    TRACK_MANIFEST,

    /**
     * A single disc that belongs to a multi-disc group (e.g. `Chrono Cross (Disc 1).chd`). Usually a
     * non-primary group member; hidden from the shelf when a playlist/primary exists, but kept
     * indexed and — for a lone-disc game like RE Code Veronica X Disc 2 — still launchable/visible.
     */
    DISC_MEMBER,

    /**
     * A file recognized as belonging to a game but not itself a standalone game — DLC/update without
     * a base, or a `support_role` member. Never a shelf tile on its own.
     */
    SUPPORT_FILE,

    /** Recognized android app/game entry surfaced by Mimir (not an emulated ROM). */
    ANDROID_APP,

    /** Extension is on the known list but the file could not be resolved to a playable identity. */
    UNKNOWN,
}

/**
 * How a duplicate/grouped set of files was proven to be the same game. Ordered strongest-first —
 * this is exactly the current product's collapse precedence (see `dedup-grouping-rulings`).
 *
 * Only a HARD identity key may auto-collapse: title-only match is never enough (avoids merging two
 * genuinely different games). There is intentionally NO user "merge" affordance yet.
 */
enum class DuplicateGroupKind {
    /** Byte-identical dumps (same [ResidentFile.contentHash]). Always merge, regardless of storage. */
    EXACT_HASH,

    /** Same catalog title-id / serial (e.g. PlayStation `SYSTEM.CNF` serial). Region/version siblings collapse here. */
    CATALOG_TITLE_ID,

    /** Discs of one multi-disc game grouped by filename disc-pattern + shared base title. */
    MULTI_DISC,

    /** Same logical game found on two storage volumes; prefer the SD/primary source, hide the internal copy. */
    CROSS_STORAGE,
}
