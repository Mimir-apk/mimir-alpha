package com.gruning.mimir.rewrite.core.tweaker

import android.os.Bundle
import com.gruning.mimir.tweaker.TweakerGameAudioFocusContract

/**
 * API v11: a game Mimir launches cannot take audio focus, so the music already playing carries on.
 *
 * Its own minimum version, for the reason every family since v8 has one: an older daemon is asked
 * nothing, and reads as "unsupported", so music behaves exactly as it did before this existed. Nothing
 * here can carry a command: the caller names one package, the daemon owns the command templates.
 */
const val TWEAKER_V11_GAME_AUDIO_FOCUS_MIN_API_VERSION = TweakerGameAudioFocusContract.MIN_API_VERSION

/** What the daemon said about a hold or a release. [Unavailable] is "could not ask or could not read the answer". */
sealed interface TweakerGameAudioFocusResult {
    /** The app-op read back as ignore: the package can no longer take audio focus. */
    data class Held(val packageName: String) : TweakerGameAudioFocusResult

    data class Released(val packageName: String) : TweakerGameAudioFocusResult

    data object NothingHeld : TweakerGameAudioFocusResult

    /** Refused; the game keeps normal focus behaviour. [releaseDebt] means a put-back is still unconfirmed. */
    data class Refused(val code: String, val detail: String, val releaseDebt: Boolean) : TweakerGameAudioFocusResult

    /** A release the daemon could not confirm; its journal still names the package. */
    data class Retained(val code: String) : TweakerGameAudioFocusResult

    data class Unavailable(val reason: String) : TweakerGameAudioFocusResult
}

internal fun parseTweakerGameAudioFocusBundle(
    bundle: Bundle,
    expectedApiVersion: Int,
): TweakerGameAudioFocusResult {
    val apiVersion = bundle.getInt(TweakerGameAudioFocusContract.KEY_API_VERSION, -1)
    if (apiVersion != expectedApiVersion) {
        return TweakerGameAudioFocusResult.Unavailable("Game audio-focus API version mismatch")
    }
    val packageName = bundle.getString(TweakerGameAudioFocusContract.KEY_PACKAGE_NAME).orEmpty()
    return when (val outcome = bundle.getString(TweakerGameAudioFocusContract.KEY_OUTCOME).orEmpty()) {
        TweakerGameAudioFocusContract.OUTCOME_HELD ->
            if (packageName.isBlank()) {
                TweakerGameAudioFocusResult.Unavailable("Held hold named no package")
            } else {
                TweakerGameAudioFocusResult.Held(packageName)
            }
        TweakerGameAudioFocusContract.OUTCOME_RELEASED -> TweakerGameAudioFocusResult.Released(packageName)
        TweakerGameAudioFocusContract.OUTCOME_NOTHING_HELD -> TweakerGameAudioFocusResult.NothingHeld
        TweakerGameAudioFocusContract.OUTCOME_REFUSED -> TweakerGameAudioFocusResult.Refused(
            code = bundle.getString(TweakerGameAudioFocusContract.KEY_REFUSAL_CODE).orEmpty().ifBlank { "UNSPECIFIED" },
            detail = bundle.getString(TweakerGameAudioFocusContract.KEY_REFUSAL_DETAIL).orEmpty(),
            releaseDebt = bundle.getBoolean(TweakerGameAudioFocusContract.KEY_RELEASE_DEBT, false),
        )
        TweakerGameAudioFocusContract.OUTCOME_RETAINED -> TweakerGameAudioFocusResult.Retained(
            bundle.getString(TweakerGameAudioFocusContract.KEY_REFUSAL_CODE).orEmpty().ifBlank { "UNSPECIFIED" },
        )
        else -> TweakerGameAudioFocusResult.Unavailable(
            "Game audio-focus call reported no usable outcome: ${outcome.ifBlank { "none" }}",
        )
    }
}
