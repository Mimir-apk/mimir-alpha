package com.gruning.mimir.rewrite

import android.content.Context
import android.util.Log
import com.gruning.mimir.rewrite.feature.library.GameLaunchSessionHold
import com.gruning.mimir.rewrite.feature.library.gameLaunchSessionHeldMessage

/**
 * O15 follow-up (coordinator review, 2026-09-12): the answer a game picked on the Bottom card gets when the session ledger
 * refuses to launch it (`launchCompanionPickedGame` in MimirAppCoordinator.kt).
 *
 * The player tapped PLAY on the Bottom, so the toast is made on the Bottom. That launch path runs in Main's composition,
 * and a toast made from Main's Activity context appeared on Main; round 5b's rule is one toast, on the display the player
 * tapped. [bottomDisplayId] is the selected secondary display, which the Presentation holding the card is on, and [base] is
 * a process context, so [companionFeedbackContext] places the toast there. With no Bottom display it stays on Main.
 *
 * The sentence is the one for what held the launch ([gameLaunchSessionHeldMessage]). The log line keeps the shape of the
 * bridge's old refusal line, `launch refused game=`, with a reason that names the hold.
 */
internal fun showCompanionPickedGameRefusal(
    base: Context,
    bottomDisplayId: Int?,
    gameIdValue: String,
    hold: GameLaunchSessionHold,
) {
    Log.i(CompanionPickedGameRefusalLogTag, companionPickedGameRefusalLogMessage(gameIdValue, hold))
    showMimirMessage(companionFeedbackContext(base, bottomDisplayId), gameLaunchSessionHeldMessage(hold))
}

internal fun companionPickedGameRefusalLogMessage(gameIdValue: String, hold: GameLaunchSessionHold): String =
    "launch refused game=$gameIdValue reason=session_held_${hold.name.lowercase()}"

private const val CompanionPickedGameRefusalLogTag = "MimirOpening"
