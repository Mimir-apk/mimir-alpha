package com.gruning.mimir.rewrite.feature.library

import androidx.annotation.StringRes
import com.gruning.mimir.rewrite.core.input.FocusKey

/**
 * The shelf's modes. [stripLabel] is the identity the mode strip reports on a tap ([fromStripLabel]); it is never drawn. What the
 * player reads is [labelRes] on the tab and [sectionLabelRes] beside the beam, in the language of the host.
 */
enum class LibraryShelfMode(
    val stripLabel: String,
    @StringRes val labelRes: Int,
    @StringRes val sectionLabelRes: Int,
) {
    EMULATION("EMULATION", R.string.library_shelf_mode_emulation, R.string.library_shelf_eyebrow_platforms),
    // ANDROID_APPS retired. Android apps are still classified and launchable — see
    // NativeShelfKind.APP below and the dock's QUICK_LAUNCH ("Apps") seat — they just no longer have a
    // Library shelf lane of their own.
    ANDROID_GAMES("ANDROID GAMES", R.string.library_shelf_mode_android_games, R.string.library_shelf_mode_android_games),
    ;

    fun shifted(offset: Int): LibraryShelfMode {
        val modes = values()
        val current = modes.indexOf(this)
        return modes[(current + offset).floorMod(modes.size)]
    }

    companion object {
        /** The mode a mode-strip tab stands for, by the label the tab reports; null for a label no mode owns. */
        fun fromStripLabel(label: String): LibraryShelfMode? = LibraryShelfMode.entries.firstOrNull { it.stripLabel == label }
    }
}

data class NativeShelfRow(
    val packageName: String,
    val label: String,
    val launchActivity: String?,
    val isGame: Boolean,
    val iconPath: String? = null,
    val isFavorite: Boolean = false,
    val rowKindLabel: String? = null,
    /** Optional package-owned local banner. Library Home may use it; no other surface inherits it. */
    val heroArtUri: String? = null,
)

enum class NativeShelfKind(
    val storageValue: String,
    val shelfLabel: String,
    val rowKindLabel: String,
) {
    GAME("game", "Android Games", "Android game"),
    APP("app", "Android Apps", "Android app"),
}

/**
 * The Favorites tabs. [label] is the companion payload's English wording (that panel is a later localization step); the Library's own
 * chips read [labelRes].
 */
enum class FavoriteSegment(
    val label: String,
    @StringRes val labelRes: Int,
) {
    GAMES("GAMES", R.string.library_gamelist_segment_games),
    ANDROID_GAMES("ANDROID GAMES", R.string.library_shelf_mode_android_games),
    ANDROID_APPS("ANDROID APPS", R.string.library_gamelist_segment_android_apps),
}

enum class NativeShelfAddReason {
    HIDDEN_HERE,
    MOVE_FROM_OTHER_SHELF,
}

data class NativeShelfAddCandidate(
    val packageName: String,
    val label: String,
    val iconPath: String? = null,
    val targetKind: NativeShelfKind,
    val sourceKind: NativeShelfKind,
    val reason: NativeShelfAddReason,
    val isHidden: Boolean,
    val rowKindLabel: String? = null,
)

internal const val NativeShelfKeyPrefix = "library/native/"

internal fun nativeShelfKey(packageName: String) = FocusKey("$NativeShelfKeyPrefix$packageName")
internal fun nativeAddPickerKey(packageName: String) = FocusKey("library/nativeAdd/$packageName")

/** Only Android games can enter the SH03 detail surface; apps fail closed before dispatch changes scope. */
internal fun NativeShelfRow.toLibraryDetailTargetOrNull(): LibraryDetailTarget.AndroidGame? =
    takeIf { it.isGame }?.let { LibraryDetailTarget.AndroidGame(it) }

internal fun nativePackageFromFocusKey(focusedKey: String): String? {
    val prefix = "library/native/"
    if (!focusedKey.startsWith(prefix)) return null
    return focusedKey.removePrefix(prefix).takeIf { it.isNotBlank() }
}

internal fun nativeAddPackageFromFocusKey(focusedKey: String): String? {
    val prefix = "library/nativeAdd/"
    if (!focusedKey.startsWith(prefix)) return null
    return focusedKey.removePrefix(prefix).takeIf { it.isNotBlank() }
}

fun LibraryShelfMode.nativeShelfKindOrNull(): NativeShelfKind? = when (this) {
    LibraryShelfMode.EMULATION -> null
    LibraryShelfMode.ANDROID_GAMES -> NativeShelfKind.GAME
}

private fun Int.floorMod(modulus: Int): Int = ((this % modulus) + modulus) % modulus
