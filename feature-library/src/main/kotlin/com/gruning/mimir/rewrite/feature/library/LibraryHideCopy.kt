package com.gruning.mimir.rewrite.feature.library

/**
 * What the player is told about hiding, in one place, so the prompt, the receipt and the place they name cannot drift
 * apart.
 *
 * The receipt is the message the shatter ends on, shown on the hint-and-toast rail as a confirmation. It names where
 * the thing went and how to bring it back, in the words of the place itself, so a player who reads it can go there.
 *
 * **A hidden game** comes back from Settings: the Library panel's "Hidden games" section, which lists the games the
 * player has hidden, each with a Restore action (the section is drawn when at least one game is hidden). Browse keeps
 * its own Restore action; only the words point to Settings.
 *
 * **A hidden Android game or app** does not appear there: Hidden games is the Library database's list, and an Android
 * package is not in it. The one way back is the Android games shelf's Add game pill, which opens the picker that lists
 * a package hidden from that shelf ("Restore game") and one that lives on the other shelf ("Add game"), and
 * `addNativeShelfPackageToKind` is the only caller that un-hides one. Both routes are the same three steps for the
 * player, so one sentence serves an app and a game; the pick re-adds the package to the games shelf (a hidden Android
 * app comes back as a game, because the apps shelf is retired), and a favourite comes back starred: a hide leaves the
 * star where it is, as it does for a hidden game.
 */
internal object LibraryHideCopy {
    /** The place a hidden game is restored from. Each word is the title the player sees there. */
    const val HiddenGamesPlace = "Settings › Library › Hidden games"

    /** The receipt for a game hidden from the Library. */
    const val GameReceipt = "Hidden · Restore it in $HiddenGamesPlace"

    /** The body of the hide prompt for a game: what is kept, and where it is restored from. */
    const val GamePromptBody = "It stays on your storage. Restore it later from $HiddenGamesPlace."

    /** The place a hidden Android game or app is added back from. */
    const val NativeReturnPlace = "Library › Android games › Add game"

    /** The receipt for an Android game or app hidden from a shelf. */
    const val NativeReceipt = "Hidden · Add it back from $NativeReturnPlace"

    /** The body of the hide prompt for an Android game or app. */
    const val NativePromptBody = "It stays installed. Add it back later from $NativeReturnPlace."
}
