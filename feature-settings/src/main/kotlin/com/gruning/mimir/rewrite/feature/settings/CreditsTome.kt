package com.gruning.mimir.rewrite.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.gruning.mimir.rewrite.core.ui.LocalMimirThemeId
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import com.gruning.mimir.rewrite.core.ui.mimirNorseBookFrame
import com.gruning.mimir.rewrite.core.ui.theme.MimirColors
import com.gruning.mimir.rewrite.core.ui.theme.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.theme.MimirType

/**
 * One identity-data source credited in-app, and the acknowledgement Mimir owes it.
 *
 * Kept as data, not inline composables, so [MimirDataSourceCredits] can be asserted against the set of
 * sources that must stay in-app. A source whose terms require (or leave ambiguous) an in-app credit
 * should fail a test if it is missing here, not ship quietly — that is the failure mode this file exists
 * to prevent.
 *
 * `app/src/main/assets/dat/` ships identity data from four upstream projects,
 * but only two are credited here. GameTDB stays because its terms call for a visible link (`NOTICE`
 * section 2.4 — the "Visit" row below IS that link). No-Intro stays because its data terms are NOT
 * ESTABLISHED (`NOTICE` section 2.1), and an unknown term is not grounds to remove a credit. MAME and
 * Redump were moved to `NOTICE` (sections 2.2, 2.3 and 2.6) on the belief that their terms were clear.
 * `NOTICE` now records their own terms as not established, so whether they return here is unresolved until the terms are established.
 */
internal data class CreditEntry(
    /** The upstream project, named as it names itself. */
    val source: String,
    /** What Mimir bundles from it — checksums and names only. */
    val provides: String,
    /** Licence/terms line where they are known and recorded in-repo; blank means acknowledgement only. */
    val terms: String = "",
)

/**
 * The identity-data sources credited IN-APP — two of the four sources `app/src/main/assets/dat/` actually
 * bundles. See [CreditEntry]'s doc comment for which two moved to `NOTICE` and why.
 *
 * Provenance for each line is in `NOTICE` section 2 and `tools/fetch-dats.sh`. Where terms are not
 * established, this list acknowledges the source without asserting a license Mimir cannot evidence.
 */
internal val MimirDataSourceCredits: List<CreditEntry> = listOf(
    CreditEntry(
        source = "No-Intro",
        provides = "Cartridge game checksums and canonical titles.",
        terms = "Provisioned through the auto-datfile-generator project (MIT).",
    ),
    CreditEntry(
        source = "GameTDB",
        provides = "Game identifiers and titles for Wii, GameCube, Wii U, Nintendo 3DS, Nintendo DS and PlayStation 3.",
    ),
)

/** Header line — the posture claim, stated the same way `tools/fetch-dats.sh:5` states it. */
internal const val CreditsPostureLine: String =
    "Mimir bundles checksums and canonical names only — never game content, BIOS, or firmware."

/** Closing line. Trademarks are acknowledged, never claimed. */
internal const val CreditsTrademarkLine: String =
    "Game titles, trademarks, and emulator projects belong to their respective owners."

internal const val AndroidRobotArtworkCreditLine: String =
    "Android robot artwork by Google, used under CC BY 3.0 and recolored with Mimir's active palette."

/**
 * The tome-framed credits page, revealed inline from About.
 *
 * Presentation is the companion open-book frame lifted from core-ui (`mimirNorseBookFrame`), so this
 * inherits the shelf's book language and the active palette rather than inventing a surface. It is
 * deliberately NOT a floating modal: a true overlay needs its own `MimirOverlayKind`, dispatch, and
 * focus land/restore through the single FocusAuthority, and hand-rolling focus trapping here would risk
 * exactly the focus regression this project forbids. Rendered inline, every row above and below stays in
 * `DetailScope` and controller traversal is correct by construction.
 *
 * Contains no interactive elements, so it introduces no focus surface of its own — the GameTDB `Visit`
 * row remains the one actionable credit, unchanged, in the section above. The legal reader ([LegalReader])
 * sits under this panel and has the rows.
 *
 * The panel opens with the Appropriate Legal Notices GPL-3.0 section 5(d) asks an interactive interface to
 * show: the copyright line, that Mimir is free software with no warranty, and where the source code is.
 *
 * Shows two of the four bundled identity-data sources (No-Intro, GameTDB). MAME and Redump are in the
 * repo's `NOTICE` file only  — see [MimirDataSourceCredits]'s doc comment.
 */
@Composable
internal fun CreditsTomePanel(modifier: Modifier = Modifier) {
    val palette = MimirThemePalette.forId(LocalMimirThemeId.current)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .mimirNorseBookFrame(palette)
            .padding(MimirSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(MimirSpacing.md),
    ) {
        Text(stringResource(R.string.settings_about_legal_eyebrow), style = MimirType.Eyebrow, color = MimirColors.GoldBright)
        Text(stringResource(R.string.settings_about_legal_copyright), style = MimirType.RowTitle, color = MimirColors.TextPrimary)
        Text(stringResource(R.string.settings_about_legal_free_software), style = settingsRowSupportStyle, color = MimirColors.TextSecondary)
        Text(
            stringResource(R.string.settings_about_legal_source, MimirSourceUrl),
            style = settingsRowSupportStyle,
            color = MimirColors.TextSecondary,
        )
        Text("ACKNOWLEDGEMENTS", style = MimirType.Eyebrow, color = MimirColors.GoldBright)
        Text(
            CreditsPostureLine,
            // Small-pane support role: same role, same gated pane (this panel renders inside
            // SettingsDetailPane via AboutPanel -> CreditsTomePanel) as SettingsRow/SettingsInfoRow, so it
            // reads the same ambient resolver rather than the raw MimirType.RowSupport token those two now
            // avoid. See settingsRowSupportStyle's own KDoc.
            style = settingsRowSupportStyle,
            color = MimirColors.TextSecondary,
        )
        MimirDataSourceCredits.forEach { entry ->
            Column(verticalArrangement = Arrangement.spacedBy(MimirSpacing.xs)) {
                Text(entry.source, style = MimirType.RowTitle, color = MimirColors.TextPrimary)
                Text(entry.provides, style = settingsRowSupportStyle, color = MimirColors.TextSecondary)
                if (entry.terms.isNotBlank()) {
                    Text(entry.terms, style = settingsRowSupportStyle, color = MimirColors.TextMuted)
                }
            }
        }
        Text(
            AndroidRobotArtworkCreditLine,
            style = settingsRowSupportStyle,
            color = MimirColors.TextMuted,
        )
        Text(
            CreditsTrademarkLine,
            style = settingsRowSupportStyle,
            color = MimirColors.TextMuted,
        )
    }
}
