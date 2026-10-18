package com.gruning.mimir.rewrite.feature.settings

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import java.security.MessageDigest

private const val SettingsRailFocusPrefix = "settings/rail/"
private const val SettingsDetailFocusPrefix = "settings/detail/"

/** Stable persisted focus key for [category]'s Settings rail entry. */
fun settingsRailFocusKey(category: SettingsCategory): FocusKey = FocusKey(
    SettingsRailFocusPrefix + when (category) {
        SettingsCategory.PROFILE -> "PROFILE"
        SettingsCategory.LIBRARY -> "LIBRARY"
        SettingsCategory.EMULATORS -> "EMULATORS"
        SettingsCategory.APPEARANCE -> "APPEARANCE"
        SettingsCategory.MUSIC -> "MUSIC"
        SettingsCategory.CONTROLS -> "CONTROLS"
        SettingsCategory.ABOUT -> "ABOUT"
    },
)

/** The rail category represented by [key], or null when [key] is not an exact Settings rail key. */
fun settingsRailCategoryForFocusKey(key: FocusKey?): SettingsCategory? = when (key?.value) {
    "${SettingsRailFocusPrefix}PROFILE" -> SettingsCategory.PROFILE
    "${SettingsRailFocusPrefix}LIBRARY" -> SettingsCategory.LIBRARY
    "${SettingsRailFocusPrefix}EMULATORS" -> SettingsCategory.EMULATORS
    "${SettingsRailFocusPrefix}APPEARANCE" -> SettingsCategory.APPEARANCE
    "${SettingsRailFocusPrefix}MUSIC" -> SettingsCategory.MUSIC
    "${SettingsRailFocusPrefix}CONTROLS" -> SettingsCategory.CONTROLS
    "${SettingsRailFocusPrefix}ABOUT" -> SettingsCategory.ABOUT
    // Unit 10 retired Troubleshooting. Its rail key cannot strand startup focus in a missing target.
    "${SettingsRailFocusPrefix}TROUBLESHOOTING" -> SettingsCategory.PROFILE
    else -> null
}

/** Stable persisted focus key for a Settings detail [rowId]. */
fun settingsDetailFocusKey(rowId: String): FocusKey {
    require(rowId.isNotBlank()) { "Settings detail row id must not be blank" }
    return FocusKey("$SettingsDetailFocusPrefix$rowId")
}

/**
 * Stable opaque row id for a removable Library source. The raw SAF URI must never become focus state,
 * analytics copy, or screenshot-visible text.
 */
fun librarySourceRemovalRowId(rootUri: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(rootUri.toByteArray(Charsets.UTF_8))
        .take(8)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
    return "library/source/remove/$digest"
}

/** The Library pane's ADD LIBRARY FOLDER row, its first and the focus of last resort. */
const val LibraryAddRowId = "library/add"

const val LibrarySourceRemovalCancelRowId = "library/source/remove/cancel"
const val LibrarySourceRemovalConfirmRowId = "library/source/remove/confirm"

/** The Library pane's route to Android's "All files access" screen; present only while that access is off. */
const val LibraryAllowFilesRowId = "library/allowFiles"

/** The detail row id represented by [key], or null when [key] is outside the Settings detail namespace. */
fun settingsDetailRowIdForFocusKey(key: FocusKey?): String? {
    val value = key?.value ?: return null
    if (!value.startsWith(SettingsDetailFocusPrefix)) return null
    return value.removePrefix(SettingsDetailFocusPrefix).takeIf(String::isNotBlank)
}

/**
 * The Settings category that owns [key], whether [key] belongs to the rail or one of that category's
 * detail rows. This mapping is prefix-based for detail rows so dynamic ids remain restorable.
 */
fun settingsCategoryForFocusKey(key: FocusKey?): SettingsCategory? {
    settingsRailCategoryForFocusKey(key)?.let { return it }
    val rowId = settingsDetailRowIdForFocusKey(key) ?: return null
    return settingsCategoryForDetailRowId(rowId)
}

/** The Settings-owned scope for [key], or null when the key cannot be restored by Settings. */
fun settingsScopeForFocusKey(key: FocusKey?): FocusScope? = when {
    settingsRailCategoryForFocusKey(key) != null -> SettingsRailScope
    settingsDetailRowIdForFocusKey(key) != null && settingsCategoryForFocusKey(key) != null -> DetailScope
    else -> null
}

/**
 * Resolve the category that should be painted on Settings' first frame for an initial focus request.
 * A null [initialFocusScope] infers the scope from the key; an explicit mismatched scope rejects the
 * request so stale or malformed startup state falls through to normal Settings entry behavior.
 */
fun settingsInitialFocusCategory(
    initialFocusKey: FocusKey?,
    initialFocusScope: FocusScope? = null,
): SettingsCategory? {
    val inferredScope = settingsScopeForFocusKey(initialFocusKey) ?: return null
    // A persisted Music sub-page scope (Behaviour, Forget folder) stands for the detail scope: the page is not open at startup, so its rows restore to
    // the row that opens it.
    val explicitScope = if (initialFocusScope in MusicSubPageScopes) DetailScope else initialFocusScope
    if (explicitScope != null && explicitScope != inferredScope) return null
    return settingsCategoryForFocusKey(initialFocusKey)
}

/**
 * Canonicalize persisted pre-Unit-10 focus without reviving removed release routes. Supported controls,
 * library, and credit rows migrate to their new owner; dead diagnostics/remapping targets fail closed to
 * Profile. Non-Settings keys remain outside this surface.
 */
fun canonicalSettingsFocusKey(key: FocusKey?): FocusKey? {
    val value = key?.value ?: return null
    settingsRailCategoryForFocusKey(key)?.let(::settingsRailFocusKey)?.let { return it }
    val rowId = settingsDetailRowIdForFocusKey(key)
    if (rowId != null) {
        // A Behaviour switch is on a sub-page that is not open when a persisted focus is restored: land on the row that opens it.
        if (rowId in MusicBehaviourRowIds) return settingsDetailFocusKey(MusicBehaviourRowId)
        // The same for the Forget folder confirmation: it is never open at startup, and a forget is never a press to restore.
        if (rowId in MusicFolderForgetRowIds) return settingsDetailFocusKey(MusicFolderRowId)
        // The legal reader's rows exist only while Credits & licenses is open, which it is not at startup: land on the row that opens it.
        if (rowId.startsWith(AboutLegalRowPrefix)) return settingsDetailFocusKey(AboutCreditsRowId)
        return canonicalSettingsDetailRowId(rowId)
            ?.let(::settingsDetailFocusKey)
            ?: settingsRailFocusKey(SettingsCategory.PROFILE)
    }
    return if (value.startsWith(SettingsRailFocusPrefix) || value.startsWith(SettingsDetailFocusPrefix)) {
        settingsRailFocusKey(SettingsCategory.PROFILE)
    } else {
        null
    }
}

/** Canonical live row id; also the only compatibility table for retired Settings detail ids. */
fun canonicalSettingsDetailRowId(rowId: String): String? = when {
    rowId == "profile/theme" -> "appearance/theme"
    rowId == "backup/export" -> "library/backup/export"
    rowId == "backup/import" -> "library/backup/import"
    rowId == "migration/import" -> "library/backup/import"
    rowId == "appearance/artwork" -> "appearance/enrichment/screenscraper"
    // The Hero Motion download row was retired 2026-09-17. Its two legacy ids land on the enrichment
    // anchor instead of a key that no longer registers, so a restored focus still finds a real row.
    rowId == "appearance/artwork/video" -> "appearance/enrichment/screenscraper"
    rowId == "library/enrichment/preview-clips" -> "appearance/enrichment/screenscraper"
    rowId == "appearance/artwork/disconnect" -> "appearance/enrichment/screenscraper"
    rowId == "library/enrichment/screenscraper" -> "appearance/enrichment/screenscraper"
    rowId == "library/enrichment/screenscraper/download" -> "appearance/enrichment/screenscraper/download"
    rowId == "library/enrichment/screenscraper/disconnect" -> "appearance/enrichment/screenscraper"
    rowId == "appearance/enrichment/screenscraper/disconnect" -> "appearance/enrichment/screenscraper"
    rowId == "library/enrichment/steamgriddb" -> "appearance/enrichment/steamgriddb"
    rowId == "appearance/artwork/steamgriddb" -> "appearance/enrichment/steamgriddb"
    rowId == "controls/controller/settings" -> ControllerShortcutQuickLaunchRowId
    // Update folded three Music rows into their neighbours; a focus persisted on one lands on the row that now carries it.
    rowId == "music/player/nowplaying" -> MusicPlayerEnabledRowId
    rowId == "music/custom/rescan" -> MusicFolderRowId
    rowId == "music/game/launchsound/volume" -> MusicLaunchSoundRowId
    rowId == "trouble/rescan" -> "library/rescan"
    rowId == "library/rescanAll" -> "library/rescan"
    rowId == "trouble/special/m_button" -> "controls/utility"
    rowId.startsWith("trouble/special/") -> rowId.replaceFirst("trouble/special/", "controls/special/")
    rowId == "trouble/gametdb" -> "about/credits/gametdb"
    rowId.startsWith("trouble/") -> null
    rowId.startsWith("emulators/bios/") -> FirstSelectableEmulatorRowId
    rowId.startsWith("profile/") ||
        rowId.startsWith("library/") ||
        rowId.startsWith("emulators/") ||
        rowId.startsWith("appearance/") ||
        // Without this line every Music row canonicalises to null and dies silently (spec, "Prefix allowlist").
        rowId.startsWith("music/") ||
        rowId.startsWith("controls/") ||
        rowId.startsWith("about/") -> rowId
    else -> null
}

/**
 * Restore a registered Settings detail target without ever activating an empty [DetailScope].
 * Returns false, without changing scope, until [targetKey] is registered in [DetailScope].
 */
fun restoreSettingsDetailFocusIfReady(
    authority: FocusAuthority,
    targetKey: FocusKey,
): Boolean {
    if (settingsScopeForFocusKey(targetKey) != DetailScope) return false
    if (targetKey !in authority.registeredKeysInScope(DetailScope)) return false

    when (authority.state.value.scope) {
        SettingsRailScope -> {
            // Seed memory before the push so the scope lands directly on the target without a first-row flash.
            authority.rememberFocusForScope(DetailScope, targetKey)
            authority.pushScope(DetailScope)
        }
        DetailScope -> Unit
        else -> return false
    }
    authority.requestFocus(targetKey)
    return authority.state.value.scope == DetailScope && authority.state.value.focused == targetKey
}

private fun settingsCategoryForDetailRowId(rowId: String): SettingsCategory? {
    val canonical = canonicalSettingsDetailRowId(rowId)
    return when {
        canonical?.startsWith("profile/") == true -> SettingsCategory.PROFILE
        canonical?.startsWith("library/") == true -> SettingsCategory.LIBRARY
        canonical?.startsWith("emulators/") == true -> SettingsCategory.EMULATORS
        canonical?.startsWith("appearance/") == true -> SettingsCategory.APPEARANCE
        canonical?.startsWith("music/") == true -> SettingsCategory.MUSIC
        canonical?.startsWith("controls/") == true -> SettingsCategory.CONTROLS
        canonical?.startsWith("about/") == true -> SettingsCategory.ABOUT
        else -> null
    }
}

/**
 * The Settings detail controls that stand side by side, each pair as (left row id, right row id): the Library pane's Backup and Recovery buttons. The pane's pad grammar reads LEFT as
 * "back to the rail" and picks a vertical landing by the nearest centre, so a pair needs its own rule or its left button cannot be reached: DOWN into a pair landed on whichever half won a
 * near-tie, and LEFT from the right half left the pane (EXPORT BACKUP and EXPORT RECOVERY were unreachable). BackupSection builds its two Rows from these ids.
 */
internal val SettingsDetailSidePairs: List<Pair<String, String>> = listOf(
    "library/backup/export" to "library/backup/import",
    "library/recovery/export" to "library/recovery/restore",
)

private fun settingsDetailPairOf(key: FocusKey?): Pair<String, String>? {
    val rowId = settingsDetailRowIdForFocusKey(key) ?: return null
    return SettingsDetailSidePairs.firstOrNull { it.first == rowId || it.second == rowId }
}

/**
 * The control beside [key] in [direction] within its side-by-side pair: the left half's RIGHT is the right half, the right half's LEFT is the left half. Null for a control in no pair, and for
 * the pair's outer edges (LEFT from the left half is the rail's; RIGHT from the right half has nothing beyond it).
 */
internal fun settingsDetailPairSibling(key: FocusKey?, direction: ControllerAction): FocusKey? {
    val pair = settingsDetailPairOf(key) ?: return null
    val rowId = settingsDetailRowIdForFocusKey(key)
    val sibling = when {
        direction == ControllerAction.NAV_RIGHT && rowId == pair.first -> pair.second
        direction == ControllerAction.NAV_LEFT && rowId == pair.second -> pair.first
        else -> return null
    }
    return settingsDetailFocusKey(sibling)
}

/**
 * Where an UP or DOWN move that landed on [landed] should end, given the key it left: entering a pair from outside it (a row above or below, not the other pair or its own half) puts focus on the
 * LEFT half, the one a reader reaches first; a move between the two pairs keeps its column, as the nearest-centre pick already does. Returns [landed] when nothing changes.
 */
internal fun settingsDetailPairEntry(left: FocusKey?, landed: FocusKey?): FocusKey? {
    val pair = settingsDetailPairOf(landed) ?: return landed
    if (settingsDetailPairOf(left) != null) return landed
    return settingsDetailFocusKey(pair.first)
}
