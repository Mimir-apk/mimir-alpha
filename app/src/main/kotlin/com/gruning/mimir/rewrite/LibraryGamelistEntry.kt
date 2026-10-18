package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.feature.library.GamelistScope
import com.gruning.mimir.rewrite.feature.library.LibraryShelfScope

/** A mounted shelf does not rerun Library's mount effect when only its model changes. */
internal fun enterMountedLibraryGamelist(authority: FocusAuthority, from: MimirDestination) {
    if (from == MimirDestination.LIBRARY && authority.state.value.scope == LibraryShelfScope) {
        authority.pushScope(GamelistScope)
    }
}
