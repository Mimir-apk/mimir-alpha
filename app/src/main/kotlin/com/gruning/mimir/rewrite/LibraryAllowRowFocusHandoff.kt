package com.gruning.mimir.rewrite

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.feature.settings.LibraryAllowFilesRowId
import com.gruning.mimir.rewrite.feature.settings.detailKey

/**
 * The Library pane's "Allow file access" row leaves the screen the moment the grant returns, and the focus authority drops
 * focus to whichever sibling is nearest, which is a source row rather than anything about the access just given. This
 * hands that focus to RESCAN instead, the row the player came to do (alpha cert review, P3).
 *
 * [fileAccessFocusHandoff] is the decision: the row held focus a moment ago, it does not now, and the grant is held. Focus
 * moving off the row by the player's own hand, with the grant still off, never triggers it.
 */
internal fun fileAccessFocusHandoff(
    allowRowWasFocused: Boolean,
    allowRowFocusedNow: Boolean,
    allFilesAccess: Boolean,
): Boolean = allowRowWasFocused && !allowRowFocusedNow && allFilesAccess

/**
 * Watches [authority] for the "Allow file access" row losing focus and calls [handOff] when [fileAccessFocusHandoff] says
 * the row went because the grant arrived. [allFilesAccessNow] is read at the moment of each change, so it is the grant as
 * the pane that removed the row saw it.
 */
internal suspend fun watchLibraryAllowRowFocus(
    authority: FocusAuthority,
    allFilesAccessNow: () -> Boolean,
    handOff: () -> Unit,
) {
    val allowKey = detailKey(LibraryAllowFilesRowId)
    var wasFocused = false
    authority.state.collect { focus ->
        val focusedNow = focus.focused == allowKey
        if (fileAccessFocusHandoff(wasFocused, focusedNow, allFilesAccessNow())) handOff()
        wasFocused = focusedNow
    }
}

/** [watchLibraryAllowRowFocus] for the life of the Settings coordinator. */
@Composable
internal fun LibraryAllowRowFocusHandoff(
    authority: FocusAuthority,
    allFilesAccess: Boolean,
    handOff: () -> Unit,
) {
    val currentAccess by rememberUpdatedState(allFilesAccess)
    val currentHandOff by rememberUpdatedState(handOff)
    LaunchedEffect(authority) {
        watchLibraryAllowRowFocus(authority, { currentAccess }, { currentHandOff() })
    }
}
