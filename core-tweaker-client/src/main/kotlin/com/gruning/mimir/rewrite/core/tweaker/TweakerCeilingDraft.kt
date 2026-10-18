package com.gruning.mimir.rewrite.core.tweaker

/**
 * A clock-ceiling preview, UI-only until the player applies it.
 *
 * **A parallel of [TweakerProfileDraft], deliberately not a widening of it.** That draft's `admitted`
 * requires `availability == READY`, and that requirement is a safety property, not an accident: confirm
 * may never apply something the row can no longer promise. Relaxing it to let ceilings ride the same
 * type would have weakened the guard protecting the certified path in order to serve the discovered
 * one. Two drafts, one discipline.
 */
data class TweakerCeilingDraft(val previewKhz: Int) {
    /**
     * A preview survives only while the visible row still offers this frequency AND can still write it.
     * Losing the write host mid-preview must retire the draft: a confirm that cannot act is a press the
     * row would have to swallow.
     */
    fun admitted(row: TweakerUtilityRowState): Boolean {
        val ceiling = row.discovered ?: return false
        return row.availability == TweakerUtilityAvailability.DISCOVERED_CEILING &&
            ceiling.canWrite &&
            ceiling.steps.any { it.khz == previewKhz }
    }
}

/**
 * Steps the preview, not the device.
 *
 * Asks the row what it would say from the PREVIEWED position rather than the live one, so every honest
 * refusal — read-only, no table, end of the scale — still comes back from [stepOutcome] itself instead
 * of being re-derived by a second code path that could disagree with it.
 */
fun steppedTweakerCeilingDraftOutcome(
    current: TweakerCeilingDraft?,
    row: TweakerUtilityRowState,
    direction: Int,
): TweakerUtilityStepOutcome {
    val ceiling = row.discovered ?: return row.stepOutcome(direction)
    val from = current?.takeIf { it.admitted(row) }?.previewKhz ?: ceiling.currentKhz
    return row.copy(discovered = ceiling.copy(currentKhz = from)).stepOutcome(direction)
}

/** Stepping back onto the ceiling already in force leaves nothing to decide, so it leaves no draft. */
fun tweakerCeilingDraftFor(
    row: TweakerUtilityRowState,
    steppedKhz: Int,
): TweakerCeilingDraft? =
    if (steppedKhz == row.discovered?.currentKhz) null else TweakerCeilingDraft(steppedKhz)

/** What CONFIRM on a discovered-ceiling row does. Exactly one branch writes, and one gives back. */
sealed class TweakerCeilingApplyDecision {
    /** Nothing is drafted; confirm is inert. */
    data object NoDraft : TweakerCeilingApplyDecision()

    /** The row no longer offers the drafted frequency. Refuse; never substitute a different one. */
    data class Unavailable(val khz: Int) : TweakerCeilingApplyDecision()

    /** The drafted ceiling is already in force: consume the press, write nothing. */
    data class AlreadyInForce(val khz: Int) : TweakerCeilingApplyDecision()

    /**
     * The draft is the TOP of the table, so this gives the device back instead of writing a cap.
     *
     * Writing the maximum frequency would leave `MimirTunerWrite.isHolding()` true — `requestCeiling`
     * captures the original on its first call whatever value follows — so the device would stay under a
     * hold with nothing on screen able to release it. This rail has one action seat, so the top of the
     * scale is the way back out.
     */
    data object Release : TweakerCeilingApplyDecision()

    /** The single branch that writes a ceiling. */
    data class Apply(val khz: Int, val labelCaps: String) : TweakerCeilingApplyDecision()
}

fun tweakerCeilingApplyDecision(
    row: TweakerUtilityRowState,
    draft: TweakerCeilingDraft?,
): TweakerCeilingApplyDecision {
    if (draft == null) return TweakerCeilingApplyDecision.NoDraft
    if (!draft.admitted(row)) return TweakerCeilingApplyDecision.Unavailable(draft.previewKhz)
    val ceiling = row.discovered ?: return TweakerCeilingApplyDecision.Unavailable(draft.previewKhz)
    // The release check comes BEFORE the already-in-force check on purpose. A device sitting at its top
    // frequency while Mimir still holds a capture must still be able to give that capture back; treating
    // it as "already in force" would consume the press and strand the hold.
    if (draft.previewKhz == ceiling.top?.khz) return TweakerCeilingApplyDecision.Release
    if (draft.previewKhz == ceiling.currentKhz) {
        return TweakerCeilingApplyDecision.AlreadyInForce(draft.previewKhz)
    }
    val step = ceiling.steps.first { it.khz == draft.previewKhz }
    return TweakerCeilingApplyDecision.Apply(khz = step.khz, labelCaps = step.labelCaps)
}
