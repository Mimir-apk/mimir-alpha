package com.gruning.mimir.rewrite.core.model.identity

/**
 * Canonical launch-root order shared by scan grouping and durable winner rescue.
 *
 * A playlist represents the whole set, a manifest represents one track set, then physical discs sort by
 * ordinal. Ordinary single-file content follows those forms and is resolved by storage/tie-break policy.
 */
fun logicalGameLaunchRank(contentKind: String, discNumber: Int): Int = when {
    contentKind == "MULTI_DISC_PLAYLIST" -> 0
    contentKind == "TRACK_MANIFEST" -> 1
    discNumber > 0 -> 2 + discNumber.coerceAtMost(Int.MAX_VALUE - 2)
    else -> Int.MAX_VALUE
}
