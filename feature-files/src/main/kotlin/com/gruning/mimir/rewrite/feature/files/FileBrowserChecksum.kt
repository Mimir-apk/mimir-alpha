package com.gruning.mimir.rewrite.feature.files

import android.content.res.Resources
import com.gruning.mimir.rewrite.core.ui.MimirFonts
import com.gruning.mimir.rewrite.core.ui.MimirSpacing

/**
 * What the inspector's Checksum row knows about the file the player is standing on.
 *
 * The row already existed and already read `"Not checked"` — the design's own idle copy
 * (`Mimir Files.dc.html:813`). What made it a **stale** placeholder is that it never changed: Verify's
 * result was reported to `app/`, which showed it in a Toast. So the row kept saying "Not checked" about a
 * file that had just been checked, and the 40-character SHA-1 was truncated by the Toast on its way past.
 * The result had a place to live and was being thrown somewhere else.
 *
 * **PUBLIC, and that is a disclosed growth of this module's surface from three symbols to four**
 * ([FileBrowserController], [FileBrowserSurface], [FileBrowserMode], this). It has to be: the only object
 * that can ever know the answer is the one that computes it, and that is `app/`'s `FileBrowserStore` —
 * it owns the coroutine, the repository and `CHECKSUM_ALGORITHM`. This type is how that answer reaches
 * the row, as a parameter on [FileBrowserSurface].
 *
 * **Every transition is driven from outside; nothing here self-transitions, deliberately.** A row that
 * flipped itself to [Checking] the instant Verify was pressed would be claiming work had started that it
 * cannot see start — and `FileBrowserController.onVerifyChecksum` defaults to `{}`, so under that default
 * the claim would be permanent. That would claim work which has not started, which is why the sink is a plain parameter rather than a controller-side latch. Unwired, the row renders
 * exactly what it rendered before this change.
 *
 * @see fileBrowserChecksumRow for the pure projection every render goes through, including the
 *   focus-guard that stops one file's digest being shown under another file's name.
 */
sealed interface FileBrowserChecksumState {

    /**
     * The file this state is about, or null when it is about no file. **The whole point of the field:**
     * a checksum is a claim about ONE file, and the inspector re-points at whatever is focused. Without a
     * path to check against, moving focus one tile left would leave the previous file's digest sitting
     * under the new file's name — a wrong 40-character claim about the player's own data, which is
     * strictly worse than the stale "Not checked" this replaces.
     */
    val path: String?

    /** Nothing has been asked about anything. The state a surface gets when `app/` has not wired a sink. */
    data object NotChecked : FileBrowserChecksumState {
        override val path: String? get() = null
    }

    /** The digest is being computed for [path]. Set by the caller that launches the work, never here. */
    data class Checking(override val path: String) : FileBrowserChecksumState

    /**
     * @param algorithm named rather than assumed — the row prints it, so a future CRC32 pass does not
     *   need this file to change. `app/` passes its own `FileBrowserStore.CHECKSUM_ALGORITHM`, which is
     *   `"SHA-1"` deliberately (the bundled DAT corpus is keyed on SHA-1/CRC32; `getChecksum`'s SHA-256
     *   default matches nothing).
     * @param digest the complete hex digest. **Not truncated anywhere in this module** — see
     *   [fileBrowserChecksumDigestWidthDp] for the arithmetic that gives all 40 characters a home.
     */
    data class Checked(
        override val path: String,
        val algorithm: String,
        val digest: String,
    ) : FileBrowserChecksumState

    /**
     * @param reason player-facing prose about what went wrong ("This folder cannot be read"), NOT an
     *   exception's `toString`. Null falls back to [FileBrowserChecksumFailedLabel], which says only what
     *   is certainly true. The design has no failure state at all; this one is additive, because a Verify
     *   that silently does nothing is the defect the Toast was at least honest about.
     */
    data class Failed(
        override val path: String,
        val reason: String? = null,
    ) : FileBrowserChecksumState
}

/**
 * The design's own three labels, `Mimir Files.dc.html:813-814`:
 * `verify === 'done' ? 'Again' : verify === 'running' ? 'Reading' : 'Verify'`, over
 * `'8f2a1c04 · matches No-Intro'` / `'reading…'` / `'not checked'`.
 *
 * **Two of the design's four strings are deliberately NOT ported, and both are the same refusal.**
 *  - **`'· matches No-Intro'`** — a DAT verdict. Nothing in this tree looks a digest up against a DAT
 *    yet, so printing it would be a fabricated claim about the player's own ROM. The row states the
 *    algorithm and the digest, which is exactly what was computed and nothing more.
 *  - **The 8-character `'8f2a1c04'`** — the mockup only ever holds a stub, so its row can afford
 *    `white-space:nowrap; text-overflow:ellipsis` (line 199). A real SHA-1 is 40 characters and that
 *    styling would truncate it *inside the row*, reproducing the exact defect the regression report described about
 *    the Toast. The digest gets its own full-width line instead.
 */
internal val FileBrowserChecksumIdleLabel: Int = R.string.files_checksum_idle
internal val FileBrowserChecksumCheckingLabel: Int = R.string.files_checksum_reading
internal val FileBrowserChecksumFailedLabel: Int = R.string.files_checksum_failed

/**
 * The reason `app/`'s `FileBrowserStore` gives when the file could not be read, as it writes it (an English sentence: the state carries it, never a resource).
 * [fileBrowserChecksumFailureStatus] draws it from [R.string.files_checksum_unreadable] on display; any other reason is drawn as written.
 */
internal const val FileBrowserChecksumUnreadableReason = "Couldn't read this file"

/** The button's label, which is how the row proves it is live even before a digest exists. */
internal val FileBrowserVerifyLabel: Int = R.string.files_checksum_verify
internal val FileBrowserVerifyRunningLabel: Int = R.string.files_checksum_verify_running
internal val FileBrowserVerifyAgainLabel: Int = R.string.files_checksum_verify_again

/**
 * The copy control's two words. **The label IS the acknowledgement** — the same trick the Verify button
 * beside it already uses to prove it is live ([FileBrowserVerifyLabel] / [FileBrowserVerifyRunningLabel] /
 * [FileBrowserVerifyAgainLabel] track the state rather than sitting constant). A silent copy would be indistinguishable from a dead button; a button that answers in its own word
 * needs no second place on a 270 dp panel to say so, and cannot become one more surface that says nothing.
 *
 * **Not a toast, and not the system's.** `minSdk` here is 30, and Android only draws its own copy
 * confirmation from API 33, so on a device at 30-32 the system says nothing at all — the acknowledgement
 * has to be ours. On 33+ the two coexist without contradicting each other: the system's overlay reports the
 * clipboard write, this label reports the state of the row.
 *
 * **It does not fade out on a timer.** UI-03 already established that a control which appears and vanishes
 * before the player can use it does not provide a persistent acknowledgement; the queue plate follows the same rule. This clears itself when the digest it refers to leaves the row instead — see
 * [fileBrowserChecksumCopyLabel].
 */
internal val FileBrowserChecksumCopyLabel: Int = R.string.files_checksum_copy
internal val FileBrowserChecksumCopiedLabel: Int = R.string.files_checksum_copied

/** How loudly the row's status line reads. Mapped to palette tokens ONLY at the composable call site —
 * this enum carries no color, matching [FileBrowserChipTone]'s established split. */
internal enum class FileBrowserChecksumTone { IDLE, PENDING, RESULT, FAILURE }

/**
 * Everything the Checksum row draws, as data. [digest] is `""` whenever there is nothing to paste, so the
 * render has no second opinion about when the full-width line exists.
 */
internal data class FileBrowserChecksumRow(
    val status: String,
    val digest: String,
    val tone: FileBrowserChecksumTone,
    val verifyLabel: String,
)

/**
 * Project [state] onto the row for the file at [focusedPath].
 *
 * **The path guard is the load-bearing half.** A [FileBrowserChecksumState] about `Zelda.gba` must not
 * render under `Metroid.gba`'s name; anything not about the focused file collapses to the idle row. That
 * makes focus movement reset the row for free, with no clearing call anywhere and no way for a caller to
 * forget one — the same reason [FileBrowserTile.support] is derived rather than stored.
 *
 * Pure, so all four states and the cross-file case are pinned without composing anything.
 */
internal fun fileBrowserChecksumRow(
    res: Resources,
    state: FileBrowserChecksumState,
    focusedPath: String?,
): FileBrowserChecksumRow {
    val forThisFile = state.takeIf { focusedPath != null && it.path == focusedPath }
    return when (forThisFile) {
        null, FileBrowserChecksumState.NotChecked -> FileBrowserChecksumRow(
            status = res.getString(FileBrowserChecksumIdleLabel),
            digest = "",
            tone = FileBrowserChecksumTone.IDLE,
            verifyLabel = res.getString(FileBrowserVerifyLabel),
        )
        is FileBrowserChecksumState.Checking -> FileBrowserChecksumRow(
            status = res.getString(FileBrowserChecksumCheckingLabel),
            digest = "",
            tone = FileBrowserChecksumTone.PENDING,
            verifyLabel = res.getString(FileBrowserVerifyRunningLabel),
        )
        is FileBrowserChecksumState.Checked -> FileBrowserChecksumRow(
            // The algorithm is the status line, because the digest below it is otherwise 40 characters
            // of unlabelled hex. "SHA-1" is what makes it comparable against a DAT by eye.
            status = forThisFile.algorithm,
            digest = forThisFile.digest,
            tone = FileBrowserChecksumTone.RESULT,
            // The design's own invitation to re-run (`verifyLabel` at line 814). Re-verifying is always
            // meaningful and always safe, which is why the button stays live in every state rather than
            // becoming a control that looks pressable and is not.
            verifyLabel = res.getString(FileBrowserVerifyAgainLabel),
        )
        is FileBrowserChecksumState.Failed -> FileBrowserChecksumRow(
            status = fileBrowserChecksumFailureStatus(res, forThisFile.reason),
            digest = "",
            tone = FileBrowserChecksumTone.FAILURE,
            verifyLabel = res.getString(FileBrowserVerifyLabel),
        )
    }
}

/**
 * The status word of a failed Verify. The app's one reason ([FileBrowserChecksumUnreadableReason]) is drawn from its resource; a reason this module does not know
 * is drawn as written; none says only what is certainly true ([FileBrowserChecksumFailedLabel]).
 */
internal fun fileBrowserChecksumFailureStatus(res: Resources, reason: String?): String = when {
    reason == null -> res.getString(FileBrowserChecksumFailedLabel)
    reason == FileBrowserChecksumUnreadableReason -> res.getString(R.string.files_checksum_unreadable)
    else -> reason
}

/**
 * Whether there is anything to copy — **V-2's whole gate.**
 *
 * Derived from the row rather than from the state, so it cannot disagree with what is drawn: [digest] is
 * `""` in every state but [FileBrowserChecksumState.Checked], and blank for a `Checked` about a *different*
 * file (the path guard in [fileBrowserChecksumRow] collapses that to the idle row). A copy button that
 * appeared over "Not checked" would be the dead-control class this module refuses on principle
 * ([FileBrowserPlaceholderChrome], `fileBrowserFooterHints`' "a fixed legend would lie") — and it would
 * also be a second control the player could press for nothing, which is the shape of the report that opened
 * F-05 in the first place.
 *
 * **This is also what keeps the copy affordance and the refused-press notice disjoint by construction.**
 * `fileBrowserVerifyRefusalRow` only ever rewrites a row whose tone is [FileBrowserChecksumTone.IDLE], and
 * an IDLE row carries no digest, so the two can never be on screen together.
 */
internal fun fileBrowserChecksumCopyable(row: FileBrowserChecksumRow): Boolean = row.digest.isNotBlank()

/**
 * Exactly what goes on the clipboard: **the digest, alone and verbatim.**
 *
 * Not `"SHA-1 da39a3ee…"`. The one thing anyone does with a 40-character hash is paste it into a search
 * box — a DAT, a No-Intro page, a rename tool — and a prefix breaks every one of those. The algorithm is
 * on screen immediately above it ([fileBrowserChecksumRow] puts it in `status` for exactly that reason), so
 * nothing is lost by leaving it out of the payload; it is a caption, not part of the value.
 *
 * `""` when there is nothing to copy, so a caller that ignores [fileBrowserChecksumCopyable] still cannot
 * put a lie on the clipboard.
 *
 * **The clipboard is the end of the journey.** Nothing about a digest leaves the device: this module has no
 * network dependency at all (`feature-files/build.gradle.kts` — `core-files`, `core-input`, `core-ui`), and
 * a digest is never compared against anything remote. Not a claim about intent — a claim about the
 * dependency graph.
 */
internal fun fileBrowserChecksumCopyPayload(row: FileBrowserChecksumRow): String = row.digest

/**
 * The copy control's word right now.
 *
 * @param copiedDigest the digest this surface last wrote to the clipboard, or null. Compared against the
 *   row's own digest rather than against a path or a clock, which is what makes the acknowledgement clear
 *   itself with no clearing call anywhere — the same discipline [FileBrowserTile.support] and
 *   `fileBrowserVerifyRefusalRow`'s path guard follow. Move the ring and the row's digest is another
 *   file's or blank; re-verify and a new digest lands; either way this stops matching. Re-copying the same
 *   digest keeps reading [FileBrowserChecksumCopiedLabel], which is not staleness — it is still true.
 */
internal fun fileBrowserChecksumCopyLabel(res: Resources, row: FileBrowserChecksumRow, copiedDigest: String?): String =
    if (copiedDigest != null && copiedDigest == row.digest && row.digest.isNotBlank()) {
        res.getString(FileBrowserChecksumCopiedLabel)
    } else {
        res.getString(FileBrowserChecksumCopyLabel)
    }

/** The digest line's size. Small on purpose: it buys the whole hash a single line at `fontScale = 1`
 * (see [fileBrowserChecksumDigestWidthDp]), and a hash is read by comparison, not by prose. */
internal const val FileBrowserChecksumDigestSp: Float = 10f

/**
 * Exactly how wide a [length]-character digest renders in [com.gruning.mimir.rewrite.core.ui.MimirFonts.Mono].
 *
 * **Not an estimate.** Every Plex Mono glyph advances 600/1000 em, read out of the shipped TTF's own
 * `hmtx` table and named [MimirFonts.MonoAdvanceRatio] — `MimirFonts.kt` states the guarantee in those
 * words, and points at hashes as the case Mono exists for.
 *
 * This function is why the digest sits on its own line rather than beside the "Checksum" label: at
 * [FileBrowserChecksumDigestSp] a 40-character SHA-1 is 240 dp, which fits the inspector's
 * [FileBrowserInspectorWidthDp] but does NOT fit what is left of that column beside a 70 dp label and a
 * Verify button. Pinned by `FileBrowserChecksumTest`, so the layout claim is arithmetic rather than
 * confidence — the previous version of this row asserted the same thing with `maxLines = 1` and
 * `TextOverflow.Ellipsis`, which is how a truncating Toast became a truncating row.
 */
internal fun fileBrowserChecksumDigestWidthDp(
    length: Int,
    fontSizeSp: Float = FileBrowserChecksumDigestSp,
): Float = length * fontSizeSp * MimirFonts.MonoAdvanceRatio

/**
 * The smallest a control in this row is allowed to be is the shared minimum target (V-3).
 *
 * Derived straight from [MimirSpacing.minTarget], never re-picked. That token is *"minimum touch/focus
 * target on a handheld"* and this app already applies it as a hard floor at twenty-odd call sites
 * (`SettingsComponents.kt:138`, `LibraryScreen.kt:2849`, `HomeTileMenuOverlay.kt:883`,
 * `MimirKeyboard.kt:658`, …). The Verify button was `defaultMinSize(minHeight = 32.dp)` overridden down to
 * **27 dp** in this row specifically, which made this repeatedly reported control the
 * smallest thing on the screen.
 */
internal val FileBrowserChecksumActionMinWidthDp: Float = MimirSpacing.minTarget.value

/**
 * Whether the Checksum row's top line still fits the inspector with [actions] controls on it.
 *
 * **The arithmetic exists because V-3 says growing the target must not push the digest, and because the
 * honest way to check that is to compute it rather than to look at it.** The line is
 * `label | status | actions…`, so the floor is the fixed label gutter plus one [
 * FileBrowserChecksumActionMinWidthDp] per control plus the gaps between them; whatever is left is what the
 * status word has. A real button is wider than the floor (its label and padding decide), so this is a
 * necessary condition, not a sufficient one — **stated as a bound, not as a fit.**
 *
 * The DIGEST is untouched by all of it and that is the point: it lives on its own full-width line below
 * this one ([fileBrowserChecksumDigestWidthDp]'s 240 dp of the panel's 270 dp leaves no room for a
 * neighbour), so no control added here can crowd it. Adding a second button to the *digest's* line is the
 * arrangement this refuses.
 *
 * **"Fits" means the status word keeps at least one control's worth of width, not merely a positive
 * number.** Written first as `> 0`, which called a 24 dp remainder a fit — 24 dp cannot hold `SHA-1` at
 * 11 sp, so the predicate would have blessed a row whose only job is to be read. There is no advance
 * guarantee for the row's sans face the way [MimirFonts.MonoAdvanceRatio] gives one for the digest, so
 * the floor is stated in the one unit already on this line: **the word must not be narrower than the
 * buttons beside it.**
 */
internal fun fileBrowserChecksumRowFits(
    actions: Int,
    gapDp: Float = FileBrowserChecksumRowGapDp,
    labelWidthDp: Float = FileBrowserInspectorLabelWidthDp,
    panelWidthDp: Float = FileBrowserInspectorWidthDp,
): Boolean = fileBrowserChecksumStatusWidthDp(actions, gapDp, labelWidthDp, panelWidthDp) >=
    FileBrowserChecksumActionMinWidthDp

/** How much of the row is left for the status word once the label and [actions] controls have taken
 * their floor. Negative means the line is over budget. */
internal fun fileBrowserChecksumStatusWidthDp(
    actions: Int,
    gapDp: Float = FileBrowserChecksumRowGapDp,
    labelWidthDp: Float = FileBrowserInspectorLabelWidthDp,
    panelWidthDp: Float = FileBrowserInspectorWidthDp,
): Float = panelWidthDp -
    labelWidthDp -
    (actions * FileBrowserChecksumActionMinWidthDp) -
    ((actions + 1) * gapDp)

/** The row's own `Arrangement.spacedBy`, named so the budget above measures the layout that ships. */
internal const val FileBrowserChecksumRowGapDp: Float = 8f
