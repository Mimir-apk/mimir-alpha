package com.gruning.mimir.rewrite.feature.library

import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey

/**
 * the Home card's focus lattice for the four-button row (Resume, Restart, Open with, Achievements) that the card of a game parked
 * behind Home paints, in the same synthetic row-relative space as [homeLaunchCardActionBounds] (dp-shaped numbers, a flat `[0, 44]` band
 * per row, never an on-screen offset).
 *
 * Both shapes read what the footer paints from, so paint and focus cannot drift apart:
 *  - one row: [homeLaunchCardActionButtonWidths], the solver the un-stacked `Row` sizes its buttons with, separated by
 *    [HomeLaunchCardActionGap];
 *  - two rows, below the stacking width: Resume and Restart on the first row, Open with and Achievements on the second, each row centred
 *    in [actionRowWidth] and the rows [HomeLaunchCardActionGap] apart, at the nominal widths the stacked shape paints with.
 *
 * Restart is the second slot, between Resume and Open with. [contract] names the keys, so both doors' cards (Home's and the Library's) solve one lattice.
 */
internal fun homeLaunchCardRestartActionBounds(
    stacked: Boolean,
    actionRowWidth: Float,
    contract: MimirLaunchCardFocusContract = HomeLaunchCardFocusContract,
): Map<FocusKey, FocusBounds> {
    val gap = HomeLaunchCardActionGap.value
    if (!stacked) {
        val widths = homeLaunchCardActionButtonWidths(actionRowWidth.dp, hasExtraAction = true).map { it.value }
        var left = 0f
        val slots = widths.map { width ->
            FocusBounds(left, 0f, left + width, 44f).also { left += width + gap }
        }
        return mapOf(
            contract.titleEditKey to HomeLaunchCardTitleEditBounds,
            contract.playKey to slots[0],
            contract.restartKey to slots[1],
            contract.openWithKey to slots[2],
            contract.achievementsKey to slots[3],
        )
    }
    val nominal = homeLaunchCardActionNominalButtonWidths(hasExtraAction = true).map { it.value }
    val firstRowSpan = nominal[0] + gap + nominal[1]
    val secondRowSpan = nominal[2] + gap + nominal[3]
    val firstRowLeft = ((actionRowWidth - firstRowSpan) / 2f).coerceAtLeast(0f)
    val secondRowLeft = ((actionRowWidth - secondRowSpan) / 2f).coerceAtLeast(0f)
    val secondRowTop = 44f + gap
    return mapOf(
        contract.titleEditKey to FocusBounds(firstRowLeft - 96f, 0f, firstRowLeft - 8f, 44f),
        contract.playKey to FocusBounds(firstRowLeft, 0f, firstRowLeft + nominal[0], 44f),
        contract.restartKey to
            FocusBounds(firstRowLeft + nominal[0] + gap, 0f, firstRowLeft + firstRowSpan, 44f),
        contract.openWithKey to
            FocusBounds(secondRowLeft, secondRowTop, secondRowLeft + nominal[2], secondRowTop + 44f),
        contract.achievementsKey to
            FocusBounds(secondRowLeft + nominal[2] + gap, secondRowTop, secondRowLeft + secondRowSpan, secondRowTop + 44f),
    )
}
