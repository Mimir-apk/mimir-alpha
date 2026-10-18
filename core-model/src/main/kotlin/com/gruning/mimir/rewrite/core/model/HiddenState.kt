package com.gruning.mimir.rewrite.core.model

/**
 * Why a game/file is hidden from the shelf & gamelist. This distinction is load-bearing per
 * `dedup-grouping-rulings` (N-DEDUP-1): a dedup **loser** is NOT a user-hidden game.
 *
 * - A user-hidden game (`USER_DISMISSED`) is optional and *recoverable* in Browse (shows a Restore).
 * - A dedup loser (`DEDUP_NON_PRIMARY`) is a REQUIRED member of a group (e.g. a multi-disc disc);
 *   "restore Resident Evil disc 2" is meaningless, so it must NOT appear in the Browse restore list.
 *   The primary tile's "N discs/versions" badge already represents it.
 *
 * The old native bug marked losers `hidden=1` and Browse couldn't tell the two apart; the fix
 * flags losers `is_primary_member=0` instead. This enum is the typed re-expression of that reason.
 */
enum class HideReason {
    /** The user explicitly dismissed this game (hold-A in Browse). Recoverable + shown in restore. */
    USER_DISMISSED,

    /** A non-primary member of a dedup group (region/version/disc/cross-storage loser). Not in restore. */
    DEDUP_NON_PRIMARY,

    /** A cross-storage duplicate hidden in favor of the SD/primary-source copy. Not in restore. */
    CROSS_STORAGE_DUPLICATE,
}

/**
 * The hidden/visible state of one game, kept non-destructively (hide never deletes; data survives, recoverable via Browse or a
 * future "split group" action).
 *
 * @property hidden whether the game is currently hidden from shelf & gamelist.
 * @property reason why it is hidden (null when [hidden] is false); drives whether Browse offers Restore.
 * @property duplicateGroupId the dedup group this belongs to, when hidden as a group member; used to suppress losers from the restore surface.
 * @property isPrimaryMember whether this is the primary (surviving) member of its dedup group.
 */
data class HiddenState(
    val hidden: Boolean = false,
    val reason: HideReason? = null,
    val duplicateGroupId: String? = null,
    val isPrimaryMember: Boolean = true,
) {
    /**
     * Whether this hidden item should appear in Browse's **restore** surface. Only genuinely
     * user-hidden games qualify; auto-collapsed dedup losers are excluded (the fix in
     * `dedup-grouping-rulings`: drop hidden rows where `duplicateGroupId && !isPrimaryMember`).
     */
    val eligibleForRestore: Boolean
        get() = hidden &&
            reason == HideReason.USER_DISMISSED &&
            !(duplicateGroupId != null && !isPrimaryMember)

    companion object {
        /** A visible, un-hidden game. */
        val VISIBLE = HiddenState(hidden = false)
    }
}
