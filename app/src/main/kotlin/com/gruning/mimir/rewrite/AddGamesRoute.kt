package com.gruning.mimir.rewrite

/**
 * Where "Add games" goes.
 *
 * Mimir's own browser is the path, and the system picker survives **only** as a
 * fallback for when All-files access is missing or revoked — not as a permanently visible escape hatch.
 *
 * The reason is measured, not stylistic. Every hop between Mimir and `com.android.documentsui` slams the
 * Odin's fan to 50 % of the PWM period — worst 10.2 s before this week's repair work, ~0.6 s mean after it.
 * An always-offered system picker would put that hop one press away from the surface built to avoid it.
 */
internal enum class AddGamesRoute {
    /** A library operation is already running; starting another chooser would race it. */
    BUSY,

    /** Mimir's own folder browser. No app switch, so no vendor foreground reaction, so no fan surge. */
    IN_APP_BROWSER,

    /**
     * The system SAF picker.
     *
     * Reached only when Mimir cannot enumerate storage itself. All-files access is user-revocable at any
     * time in Android settings, so this is a live path rather than a theoretical one — deleting it would
     * leave a player who revoked the permission with no way to add games at all.
     */
    SYSTEM_PICKER,
}

/**
 * Busy is checked first: it is about whether a chooser may open at all, and it outranks which one.
 */
internal fun addGamesRoute(
    sourceOperationRunning: Boolean,
    allFilesAccessGranted: Boolean,
): AddGamesRoute = when {
    sourceOperationRunning -> AddGamesRoute.BUSY
    allFilesAccessGranted -> AddGamesRoute.IN_APP_BROWSER
    else -> AddGamesRoute.SYSTEM_PICKER
}

/**
 * Onboarding's Choose folder uses the app's picker, with Android's picker as fallback.
 * It runs no other library operation while asking, so it is never [AddGamesRoute.BUSY].
 */
internal fun onboardingFolderPickRoute(allFilesAccessGranted: Boolean): AddGamesRoute =
    addGamesRoute(sourceOperationRunning = false, allFilesAccessGranted = allFilesAccessGranted)
