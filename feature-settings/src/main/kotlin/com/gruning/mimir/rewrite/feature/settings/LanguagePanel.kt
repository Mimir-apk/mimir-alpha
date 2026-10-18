package com.gruning.mimir.rewrite.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.ui.MimirLanguage
import com.gruning.mimir.rewrite.core.ui.MimirLanguageSettings
import com.gruning.mimir.rewrite.core.ui.theme.MimirType

/** Settings > Appearance > Language. A press cycles Automatic, English, Español (Latinoamérica), like the Trackpad rows beside it. */
const val LanguageRowId = "appearance/language"

/**
 * Settings > Appearance > Language: the card with the one row that picks the language of Mimir's own text. Automatic follows the
 * device and overrides nothing. The row cycles on a press like the Trackpad speed and pointer rows, so it is one focus stop with the
 * same activation path as its neighbours; the app persists the choice and applies it (`MimirLanguageStore`, `MimirLocale`).
 *
 * The title and the Automatic label are string resources; the two language names are not: each is shown in its own language, so a
 * player who landed in the wrong one can still find theirs.
 */
@Composable
internal fun LanguageSection(
    authority: FocusAuthority,
    onRowActivate: (String) -> Unit,
    language: MimirLanguage = MimirLanguageSettings.language,
) {
    val title = stringResource(R.string.settings_language_title)
    SettingsSection(title = title) {
        SettingsRow(
            key = detailKey(LanguageRowId),
            scope = DetailScope,
            authority = authority,
            title = title,
            titleStyle = MimirType.SettingsActionTitle,
            supporting = stringResource(R.string.settings_language_supporting),
            onActivate = { onRowActivate(LanguageRowId) },
            trailing = { RowAction(languageLabel(language)) },
        )
    }
}

/** The row's value: a language in its own name, or the translated "Automatic". */
@Composable
internal fun languageLabel(language: MimirLanguage): String =
    language.nativeName ?: stringResource(R.string.settings_language_automatic)
