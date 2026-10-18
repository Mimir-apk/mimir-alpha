package com.gruning.mimir.rewrite

/**
 * Which picker the Customize-media **Upload** action opens -- reported picker mismatch, 2026-09-11, on the standing stance that Mimir owns its surfaces: an in-app
 * picker insulates the flow from vendor picker behaviour, and skips the fan surge every hop into
 * documentsui costs (the measurement that moved add-games in-app on 2026-08-13).
 *
 * Modelled on [AddGamesRoute] deliberately, including the reason that one is a policy rather than an
 * `if` at a call site: three entry points (`HomeCustomEnrichCommand.Upload`, the ArtPick and HeroPick
 * import buttons) must all land on the same answer, and `MimirAppCoordinator.startImageImport` is the
 * one function that asks.
 */
internal enum class ImageImportPickerRoute {
    /** Mimir's own file browser in `FileBrowserMode.PICK_IMAGE`. No app switch, no vendor reaction. */
    IN_APP,

    /**
     * The system `OpenDocument` picker.
     *
     * Reached only when Mimir cannot enumerate storage itself: the in-app browser lists through
     * `java.io.File`, which cannot see shared storage without all-files access. That permission is
     * user-revocable at any time, so this is a live path rather than a theoretical one -- deleting it
     * would leave a player who revoked it unable to upload at all.
     */
    SYSTEM,
}

/**
 * The one decision. `allFilesAccess` is `PermissionHelper.hasFullStorageAccess()`, read as a snapshot at
 * the press -- the same read `addGamesRoute` and `filesRouteDecision` make for the same browser.
 */
internal fun imageImportPickerRoute(allFilesAccess: Boolean): ImageImportPickerRoute =
    if (allFilesAccess) ImageImportPickerRoute.IN_APP else ImageImportPickerRoute.SYSTEM
