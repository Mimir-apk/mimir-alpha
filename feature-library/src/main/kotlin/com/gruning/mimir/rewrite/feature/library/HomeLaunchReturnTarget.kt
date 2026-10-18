package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId

/** Exact typed Home identity captured before launch; restoration never guesses by title or index. */
data class HomeLaunchReturnTarget(
    val itemId: HomeShelfItemId,
    val rank: Long,
    val focusKey: FocusKey = homeShelfFocusKey(itemId),
    /** Legacy v1-v3 decode compatibility only; modern Favorite Home launches leave this null. */
    val contextKey: String? = null,
) {
    init {
        require(focusKey == homeShelfFocusKey(itemId)) {
            "Home return focus key must encode the same typed identity"
        }
    }

    companion object {
        fun from(row: HomeShelfRow, contextKey: String? = null): HomeLaunchReturnTarget = HomeLaunchReturnTarget(
            itemId = row.itemId,
            rank = row.rank,
            focusKey = row.focusKey,
            contextKey = contextKey,
        )
    }
}
