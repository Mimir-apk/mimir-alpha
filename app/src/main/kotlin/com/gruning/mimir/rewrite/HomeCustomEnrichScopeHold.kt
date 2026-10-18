package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.FocusAuthority

/**
 * The picker's hold on [HomeCustomEnrichScope]: pushed once when the page mounts, released ONCE -- on the commit that takes the page away or, if that
 * never comes, when it is disposed -- and taken again if the page it released turns out to be staying (a re-open that lands on the page still
 * dissolving). A page that already let go does not discard again on dispose: by then a re-entered picker may own the scope.
 */
internal class HomeCustomEnrichScopeHold(private val authority: FocusAuthority) {
    private var held = false
    private var released = false

    fun acquire() {
        authority.pushScope(HomeCustomEnrichScope)
        held = true
        released = false
    }

    fun release() {
        if (!held) return
        held = false
        released = true
        leaveHomeCustomEnrichScope(authority)
    }

    fun reacquire() {
        if (held || !released) return
        acquire()
    }
}
