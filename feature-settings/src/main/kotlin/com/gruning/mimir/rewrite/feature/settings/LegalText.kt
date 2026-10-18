package com.gruning.mimir.rewrite.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.mimirFocusable
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirFocusRestingAlpha
import com.gruning.mimir.rewrite.core.ui.MimirRingShape
import com.gruning.mimir.rewrite.core.ui.glassRestingOutline
import com.gruning.mimir.rewrite.core.ui.mimirSelectionRing
import com.gruning.mimir.rewrite.core.ui.theme.MimirColors
import com.gruning.mimir.rewrite.core.ui.theme.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.theme.MimirType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings > About > Credits & licenses, below the acknowledgements: the way to read the app's full legal texts (GPL-3.0 section 5(d)
 * asks for "how to view a copy of this License"). Three ordinary [SettingsRow]s in [DetailScope], the same rows and the same focus
 * registration as every other Settings pane: Visit the source code, and Open or Close each of the two texts. Only one text is open at
 * a time. Opening one lists its reading blocks under the rows.
 *
 * [open] and [onOpenChange] are the caller's, like the Credits row's own open state, so the reader holds no state of its own and a
 * pane that leaves composition forgets which text was open.
 */
@Composable
internal fun LegalReader(
    authority: FocusAuthority,
    onRowActivate: (String) -> Unit,
    open: LegalDoc?,
    onOpenChange: (LegalDoc?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MimirSpacing.sm)) {
        SettingsRow(
            key = detailKey(AboutLegalSourceRowId),
            scope = DetailScope,
            authority = authority,
            title = stringResource(R.string.settings_about_legal_source_title),
            titleStyle = MimirType.SettingsActionTitle,
            supporting = stringResource(R.string.settings_about_legal_source_supporting),
            onActivate = { onRowActivate(AboutLegalSourceRowId) },
            trailing = { RowAction(stringResource(R.string.settings_about_legal_visit)) },
        )
        LegalDoc.entries.forEach { doc ->
            val isOpen = open == doc
            SettingsRow(
                key = detailKey(doc.rowId),
                scope = DetailScope,
                authority = authority,
                title = stringResource(
                    if (doc == LegalDoc.Notices) R.string.settings_about_legal_notices_title else R.string.settings_about_legal_license_title,
                ),
                titleStyle = MimirType.SettingsActionTitle,
                supporting = stringResource(
                    if (doc == LegalDoc.Notices) R.string.settings_about_legal_notices_supporting else R.string.settings_about_legal_license_supporting,
                ),
                onActivate = { onOpenChange(if (isOpen) null else doc) },
                trailing = {
                    RowAction(stringResource(if (isOpen) R.string.settings_about_legal_close else R.string.settings_about_legal_open))
                },
            )
        }
        if (open != null) {
            // Keyed by the text, so the other text's blocks never show while this one loads.
            key(open) { LegalBlockList(authority, open) }
        }
    }
}

/**
 * The reading blocks of one packaged text. The text is read from the app's assets off the main thread. Each block is a focus stop, so
 * a D-pad press steps to the next block and Settings' own scroll keeps the focused block in view; touch scrolls the pane as it does
 * everywhere else.
 */
@Composable
private fun LegalBlockList(authority: FocusAuthority, doc: LegalDoc) {
    val context = LocalContext.current
    // null while loading; an empty list when the asset cannot be read.
    val blocks by produceState<List<String>?>(initialValue = null, doc) {
        value = withContext(Dispatchers.IO) { readLegalAsset(context, doc.assetPath)?.let { legalTextBlocks(it) } ?: emptyList() }
    }
    val loaded = blocks
    when {
        loaded == null -> Text(
            stringResource(R.string.settings_about_legal_loading),
            style = settingsRowSupportStyle,
            color = MimirColors.TextMuted,
        )
        loaded.isEmpty() -> Text(
            stringResource(R.string.settings_about_legal_unavailable),
            style = settingsRowSupportStyle,
            color = MimirColors.TextMuted,
        )
        else -> Column(verticalArrangement = Arrangement.spacedBy(MimirSpacing.sm)) {
            loaded.forEachIndexed { index, block ->
                val rowId = legalBlockRowId(doc, index)
                key(rowId) { LegalTextBlock(authority = authority, key = detailKey(rowId), text = block) }
            }
        }
    }
}

/**
 * One reading block: a read-only focus stop. It registers through `mimirFocusable` and wears the one selection ring, as
 * [SettingsRow] does, and it does nothing on A: there is no action to take on a piece of text. It is not a second focus engine
 * (no Compose `focusable()`).
 */
@Composable
private fun LegalTextBlock(authority: FocusAuthority, key: FocusKey, text: String) {
    val focused = isFocused(authority, DetailScope, key)
    val palette = LocalMimirPalette.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MimirSpacing.minTarget)
            .mimirFocusable(authority, key, DetailScope, revealOutset = SettingsRevealOutset)
            .mimirSelectionRing(
                focused = focused,
                identity = key,
                shape = MimirRingShape.Row(MimirSpacing.radiusCard),
                restingOutline = true,
                restingAlpha = MimirFocusRestingAlpha,
                outlineColor = palette.glassRestingOutline(),
            )
            .clip(RoundedCornerShape(MimirSpacing.radiusCard))
            .padding(horizontal = MimirSpacing.md, vertical = MimirSpacing.md),
    ) {
        Text(
            text,
            style = settingsRowSupportStyle,
            color = if (focused) MimirColors.TextPrimary else MimirColors.TextSecondary,
        )
    }
}
