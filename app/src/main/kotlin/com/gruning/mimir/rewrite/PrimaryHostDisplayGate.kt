package com.gruning.mimir.rewrite

/**
 * Whether MainActivity owns its display: the gate behind the game-return ticket, the "stable host" completions and the dispatch
 * watchdog (`MainActivity.primaryHostOwnsWindow`).
 *
 * It used to be `RESUMED && hasWindowFocus()`, which on a single screen is exactly "nothing foreign is in front of Main and the
 * transition has settled". On the Thor focus is GLOBAL: the last-touched display owns it, and Mimir's own Bottom holds it after a
 * Quit from the Bottom's session screen (the companion Presentation's window), after the floor took it (combined43, emulator: the
 * floor on display 3 kept it for five minutes with no guard up, logcat 001905 from 01:26:48) or while a restore guard is up. Main
 * was RESUMED and on top of display 0 the whole time, so the ticket never came, `game_returning` never fired, the ledger stayed
 * YIELDED and PLAY was refused.
 *
 * The honest form of the old gate on both kinds of device: Main is RESUMED (so nothing foreign is on top of display 0: another
 * window there would have paused it), and the window focus is Mimir's own, on either display. [windowFocused] is Main's. For the
 * Bottom the runtime already says who owns it ([companionBottomOwnedByMimir]): a leased app or a DS lower screen may hold the focus
 * there, and that still waits, as before. What is given up: waiting for a system dialog over Main to go, which the bookkeeping never
 * needed. `NoDisplay` keeps a single-screen device byte-identical.
 */
internal fun primaryHostOwnsDisplay(resumed: Boolean, windowFocused: Boolean, bottomOwnedByMimir: Boolean): Boolean =
    resumed && (windowFocused || bottomOwnedByMimir)

/**
 * Whether the Bottom's window, if it holds the focus, is Mimir's own: the companion is presented or presenting, a reclaim guard is up,
 * or the Bottom is in a recovery Mimir caused. A leased app ([SecondaryDisplayDisposition.Yielded], a DS/3DS lower screen included),
 * a launch in flight and a foreign task that survived a process death ([FailureCode.PROCESS_RECOVERED_NON_MIRROR]) may hold it, and
 * [primaryHostOwnsDisplay] then waits for Main's own focus. No display at all: nothing but Main's own focus counts.
 */
internal fun companionBottomOwnedByMimir(disposition: SecondaryDisplayDisposition): Boolean = when (disposition) {
    is SecondaryDisplayDisposition.NoDisplay -> false
    is SecondaryDisplayDisposition.Yielded, is SecondaryDisplayDisposition.LaunchPending -> false
    is SecondaryDisplayDisposition.RecoveryNeeded -> disposition.failure.code != FailureCode.PROCESS_RECOVERED_NON_MIRROR
    is SecondaryDisplayDisposition.Presenting,
    is SecondaryDisplayDisposition.MirrorPresented,
    is SecondaryDisplayDisposition.ReclaimPending,
    -> true
}
