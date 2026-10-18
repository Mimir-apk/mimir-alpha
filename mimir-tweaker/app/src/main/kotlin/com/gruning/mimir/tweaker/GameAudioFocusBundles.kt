package com.gruning.mimir.tweaker

import android.os.Bundle

/** One reply shape for the three v11 methods; every key comes from the contract verbatim. */
internal fun gameAudioFocusHoldBundle(
    apiVersion: Int,
    requestedPackage: String,
    result: GameAudioFocusHoldResult,
): Bundle = Bundle().apply {
    putInt(TweakerGameAudioFocusContract.KEY_API_VERSION, apiVersion)
    when (result) {
        is GameAudioFocusHoldResult.Held -> {
            putString(TweakerGameAudioFocusContract.KEY_OUTCOME, TweakerGameAudioFocusContract.OUTCOME_HELD)
            putString(TweakerGameAudioFocusContract.KEY_PACKAGE_NAME, result.packageName)
            putBoolean(TweakerGameAudioFocusContract.KEY_RELEASE_DEBT, false)
        }
        is GameAudioFocusHoldResult.Refused -> {
            putString(TweakerGameAudioFocusContract.KEY_OUTCOME, TweakerGameAudioFocusContract.OUTCOME_REFUSED)
            if (GameAudioFocusCommands.isValidPackageName(requestedPackage)) {
                putString(TweakerGameAudioFocusContract.KEY_PACKAGE_NAME, requestedPackage)
            }
            putString(TweakerGameAudioFocusContract.KEY_REFUSAL_CODE, result.code)
            putString(TweakerGameAudioFocusContract.KEY_REFUSAL_DETAIL, result.detail.take(DETAIL_MAX))
            putBoolean(TweakerGameAudioFocusContract.KEY_RELEASE_DEBT, result.releaseDebt)
        }
    }
}

internal fun gameAudioFocusReleaseBundle(
    apiVersion: Int,
    result: GameAudioFocusReleaseResult,
): Bundle = Bundle().apply {
    putInt(TweakerGameAudioFocusContract.KEY_API_VERSION, apiVersion)
    when (result) {
        is GameAudioFocusReleaseResult.Released -> {
            putString(TweakerGameAudioFocusContract.KEY_OUTCOME, TweakerGameAudioFocusContract.OUTCOME_RELEASED)
            putString(TweakerGameAudioFocusContract.KEY_PACKAGE_NAME, result.packageName)
        }
        GameAudioFocusReleaseResult.NothingHeld ->
            putString(TweakerGameAudioFocusContract.KEY_OUTCOME, TweakerGameAudioFocusContract.OUTCOME_NOTHING_HELD)
        is GameAudioFocusReleaseResult.Retained -> {
            putString(TweakerGameAudioFocusContract.KEY_OUTCOME, TweakerGameAudioFocusContract.OUTCOME_RETAINED)
            result.packageName?.takeIf(GameAudioFocusCommands::isValidPackageName)?.let {
                putString(TweakerGameAudioFocusContract.KEY_PACKAGE_NAME, it)
            }
            putString(TweakerGameAudioFocusContract.KEY_REFUSAL_CODE, result.code)
        }
    }
}

internal fun gameAudioFocusQueryBundle(apiVersion: Int, result: GameAudioFocusQueryResult): Bundle = Bundle().apply {
    putInt(TweakerGameAudioFocusContract.KEY_API_VERSION, apiVersion)
    when (result) {
        is GameAudioFocusQueryResult.Held -> {
            putString(TweakerGameAudioFocusContract.KEY_OUTCOME, TweakerGameAudioFocusContract.OUTCOME_HELD)
            putString(TweakerGameAudioFocusContract.KEY_PACKAGE_NAME, result.packageName)
        }
        GameAudioFocusQueryResult.NotHeld ->
            putString(TweakerGameAudioFocusContract.KEY_OUTCOME, TweakerGameAudioFocusContract.OUTCOME_NOT_HELD)
        is GameAudioFocusQueryResult.Unavailable -> {
            putString(TweakerGameAudioFocusContract.KEY_OUTCOME, TweakerGameAudioFocusContract.OUTCOME_UNAVAILABLE)
            putString(TweakerGameAudioFocusContract.KEY_REFUSAL_CODE, result.code.take(DETAIL_MAX))
        }
    }
}

private const val DETAIL_MAX = 240
