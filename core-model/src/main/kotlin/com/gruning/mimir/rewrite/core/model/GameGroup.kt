package com.gruning.mimir.rewrite.core.model

/**
 * A dedup **group**: the set of resident files the console treats as ONE game tile. This type carries these grouping rules:
 *
 *  - Region / version variants → COLLAPSE to one tile (Pokémon Diamond USA + Europe).
 *  - Multi-disc → ONE tile per game; launch starts the lowest disc present, emulator swaps.
 *  - Cross-storage dupes → COLLAPSE, prefer the SD/primary-source copy; hide the internal copy.
 *  - Byte-identical dumps always merge regardless of storage.
 *  - A `.m3u` playlist is the PRIMARY of a multi-disc group when present.
 *
 * Invariants encoded here:
 *  - Exactly ONE [primary] member is shown on the shelf; the rest are [hiddenMembers] (losers),
 *    hidden non-destructively (indexed, never deleted).
 *  - A **favorite maps to the GROUP** ([gameId]) so it survives collapse and disc-swapping.
 *  - Losers are NOT user-hidden games — they never appear in Browse's restore surface (see
 *    [HiddenState.eligibleForRestore]); the "N discs/versions" badge represents them.
 *
 * This model is the DATA truth. The keying/folding *algorithm* (which files land in which group) lives
 * in `core-resolver`; this type just holds a formed group + exposes the launch/badge derivations the
 * UI needs.
 *
 * @property gameId the stable group identity (a favorite/override/launch keys off this).
 * @property groupKind the strongest evidence that formed this group (drives whether it could auto-collapse).
 * @property identity the resolved display identity (title/region/version/serial + how it was proven).
 * @property primary the surviving, shelf-visible member. For a multi-disc set this is the `.m3u`/lowest disc.
 * @property hiddenMembers the non-primary members (region/version/disc/cross-storage losers), hidden but indexed.
 */
data class GameGroup(
    val gameId: GameId,
    val groupKind: DuplicateGroupKind,
    val identity: GameIdentity,
    val primary: ResidentFile,
    val hiddenMembers: List<ResidentFile> = emptyList(),
) {
    init {
        require(identity.gameId == gameId) {
            "GameGroup.identity.gameId (${identity.gameId}) must equal group gameId ($gameId)"
        }
    }

    /** Every file in the group (primary first), in a stable order. */
    val allMembers: List<ResidentFile> get() = listOf(primary) + hiddenMembers

    /** Total member count — the number behind the "N discs / N versions" badge. */
    val memberCount: Int get() = 1 + hiddenMembers.size

    /** True when this group holds more than one file (i.e. something is collapsed behind the primary). */
    val isCollapsed: Boolean get() = hiddenMembers.isNotEmpty()

    /** True for a multi-disc group (discs collapsed behind an `.m3u`/lowest-disc primary). */
    val isMultiDisc: Boolean
        get() = groupKind == DuplicateGroupKind.MULTI_DISC ||
            allMembers.any { it.contentKind == ContentKind.DISC_MEMBER || it.contentKind == ContentKind.MULTI_DISC_PLAYLIST }

    /**
     * The badge text the shelf tile shows for a collapsed group, or null when nothing is collapsed.
     * Multi-disc → "N discs"; everything else (region/version/cross-storage) → "N versions".
     * Matches the recorded shelf "2 discs" / "N versions" tile badges.
     */
    val collapseBadge: String?
        get() = when {
            !isCollapsed -> null
            isTrackSet -> null // one disc split into .bin/.img tracks — still one game; nothing "extra" behind it
            isMultiDisc -> distinctDiscCount.takeIf { it > 1 }?.let { "$it discs" }
            else -> "$memberCount versions"
        }

    /** Physical discs only; `.m3u` and split tracks are group plumbing, not extra discs. */
    val distinctDiscCount: Int
        get() = allMembers.asSequence().map { it.discNumber }.filter { it > 0 }.distinct().count()

    /**
     * True for a single-disc TRACK SET: a `.cue`/`.gdi` manifest primary with only `.bin`/`.img` tracks
     * behind it (no `.m3u` playlist, no numbered discs). Such a group is ONE disc — the collapsed tracks
     * are not extra content, so the shelf shows no "N discs/versions" badge.
     */
    val isTrackSet: Boolean
        get() = primary.contentKind == ContentKind.TRACK_MANIFEST &&
            allMembers.none { it.contentKind == ContentKind.MULTI_DISC_PLAYLIST || it.discNumber > 0 }

    /**
     * The member a launch should START from. For a multi-disc group this is the `.m3u` playlist if one
     * exists (the emulator handles disc-swap); for a track set it is the `.cue`/`.gdi` manifest (the
     * emulator reads it to find the tracks); else the lowest disc number present; otherwise the primary.
     * Honors the lone-disc edge case (RE Code Veronica X has only Disc 2 → that disc, the primary, stays
     * launchable).
     */
    val launchMember: ResidentFile
        get() {
            allMembers.firstOrNull { it.contentKind == ContentKind.MULTI_DISC_PLAYLIST }?.let { return it }
            allMembers.firstOrNull { it.contentKind == ContentKind.TRACK_MANIFEST }?.let { return it }
            val discs = allMembers.filter { it.discNumber > 0 }
            return discs.minByOrNull { it.discNumber } ?: primary
        }

    /**
     * Build the [HiddenState] for a given member of this group. The primary is VISIBLE; every other
     * member is a non-restore-eligible dedup loser (`is_primary_member=0`).
     */
    fun hiddenStateFor(member: ResidentFile): HiddenState =
        if (member.entryKey == primary.entryKey) {
            HiddenState(
                hidden = false,
                duplicateGroupId = gameId.value,
                isPrimaryMember = true,
            )
        } else {
            HiddenState(
                hidden = true,
                reason = if (groupKind == DuplicateGroupKind.CROSS_STORAGE) {
                    HideReason.CROSS_STORAGE_DUPLICATE
                } else {
                    HideReason.DEDUP_NON_PRIMARY
                },
                duplicateGroupId = gameId.value,
                isPrimaryMember = false,
            )
        }
}
