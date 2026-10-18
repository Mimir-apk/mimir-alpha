package com.gruning.mimir.rewrite

/**
 * Whether the recents drawer's ✕ is offered at all.
 *
 * **Policy L-6, 2026-09-01, from review finding D8: hidden in `RecoveryNeeded`, and hidden rather
 * than relabelled.** In that state the ✕ cannot do the thing it promises. Its close path reclaims the
 * display FIRST so the target drops to background importance — background is the only importance
 * `killBackgroundProcesses` can touch — and that reclaim is driven off a live `Yielded` lease. There is
 * no lease in `RecoveryNeeded`, so nothing is reclaimed, the reap no-ops against a still-visible app,
 * and the press removes a tab while changing nothing else. A control that silently does nothing is the
 * "close didn't close" report the I7 ruling already fixed once, arriving by a different door.
 *
 * **Deliberately narrower than every state outside `Yielded`.** That broader condition
 * would also hide the ✕ while `Presenting` or `MirrorPresented`, and the drawer lists
 * every recent task on the secondary display — not only the one hosting it. Closing a tab for an app
 * that is not currently the host is ordinary, works, and must keep working. The condition is restricted to
 * `RecoveryNeeded` to avoid that overreach.
 *
 * Fail-closed elsewhere is NOT the posture here: every other disposition offers the ✕, because in every
 * other disposition it does what it says.
 */
internal fun companionTabCloseIsOffered(snapshot: CompanionRuntimeSnapshot): Boolean =
    snapshot.disposition !is SecondaryDisplayDisposition.RecoveryNeeded
