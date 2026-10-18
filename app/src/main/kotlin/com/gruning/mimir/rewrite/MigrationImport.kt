package com.gruning.mimir.rewrite

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.input.mimirActivatable
import com.gruning.mimir.rewrite.core.input.mimirFocusable
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryRestoreExcludedCategory
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryRestoreResult
import com.gruning.mimir.rewrite.core.resolver.migration.ImportPlan
import com.gruning.mimir.rewrite.core.resolver.migration.ImportResult
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirEtchedChipLabelStyle
import com.gruning.mimir.rewrite.core.ui.MimirEtchedChipPlate
import com.gruning.mimir.rewrite.core.ui.MimirFocusTileRingReach
import com.gruning.mimir.rewrite.core.ui.MimirSwapPageGate
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.MimirIntentText
import com.gruning.mimir.rewrite.core.ui.MimirTextFragment
import com.gruning.mimir.rewrite.core.ui.MimirRingShape
import com.gruning.mimir.rewrite.core.ui.MimirTextIntent
import com.gruning.mimir.rewrite.core.ui.etchedChipInk
import com.gruning.mimir.rewrite.core.ui.mimirReducedMotion
import com.gruning.mimir.rewrite.core.ui.mimirRingSafeFade
import com.gruning.mimir.rewrite.core.ui.mimirSelectionRing
import com.gruning.mimir.rewrite.core.ui.mimirSwapFadeAlpha
import com.gruning.mimir.rewrite.core.ui.mimirSwapTransform
import com.gruning.mimir.rewrite.core.ui.theme.MimirColors
import com.gruning.mimir.rewrite.core.ui.theme.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.theme.MimirType

/**
 * "Bring over from Mimir" (M10) — the rewrite's IMPORT half. The user picks the migration bundle the
 * shipping app exported (a plain JSON file in shared storage) and this re-applies their favorites / hidden
 * / per-game emulator / theme markers onto games the rewrite ALREADY scanned. It never re-scans, never
 * touches shipping data, and honestly reports what it couldn't find (and that SAF folder grants must be
 * re-picked — those can't transfer between apps).
 *
 * Renders INLINE, in the same composition and Android window as the rest of the app shell — never a
 * Compose [androidx.compose.ui.window.Dialog]. Before 2026-08-12, this file used to open a hard-modal `Dialog`, a SEPARATE Android window with its own
 * `Window.Callback`. Both of the tree's controller feeds (`MainActivity.dispatchKeyEvent`/
 * `dispatchGenericMotionEvent`) are Activity-window methods, so while that dialog was frontmost, every
 * D-pad move and CONFIRM/A press — including "Bring them over" — was dead: [dispatchMigration] never saw
 * them. It was also lies-about-state: focus still landed on [CONFIRM_KEY] and painted a gold ring on a
 * button hardware input could never reach. The file's old justification ("modeled on the Appearance theme
 * picker: a hard-modal Dialog") was itself wrong — the theme picker (`ThemePreviewGallery`,
 * `feature-settings/ThemeGallery.kt`) has never used `Dialog`; it renders inline exactly like every other
 * overlay in this tree, per the standing invariant at `OnboardingFlow.kt:95` ("all modals render INLINE,
 * never a Compose `Dialog`, which would miss the app's routed input"). This modal now follows that same
 * shape and owns [MigrationScope] while open; [dispatchMigration] routes all input (nothing leaks to
 * Settings behind it); the app shell's own [androidx.activity.compose.BackHandler] (wired at the
 * `MimirOverlayKind.MIGRATION` call site in `MimirAppHost.kt`) preserves the Back/B escape a Compose
 * dialog window would otherwise have owned by default. Touch + controller parity.
 */
val MigrationScope = FocusScope("migration/import")

internal const val ImportBackupEyebrowCopy = "IMPORT BACKUP"
internal const val ReviewBackupTitleCopy = "Review this Mimir backup"

private val CONFIRM_KEY = FocusKey("migration/confirm")
private val CANCEL_KEY = FocusKey("migration/cancel")
private val CLOSE_KEY = FocusKey("migration/close")

/** The migration modal's lifecycle. `null` = closed. */
internal sealed interface MigrationUiState {
    /** Reading + parsing the picked file. */
    data object Reading : MigrationUiState

    /** Parsed + previewed — waiting for the user to confirm the plan. */
    data class Ready(val plan: ImportPlan) : MigrationUiState

    /** Writing the markers. */
    data object Applying : MigrationUiState

    /** Done — the honest tally. */
    data class Done(val result: ImportResult) : MigrationUiState

    /** A friendly failure (bad file, wrong kind, unreadable) — never a crash. */
    data class Failed(val message: String) : MigrationUiState

    // EP-06B — Recovery Archive restore reuses this lifecycle, not its payload types.
    data class RestoreReady(val prepared: PreparedRecoveryRestore) : MigrationUiState {
        val plan get() = prepared.plan
    }
    data object RestoreApplying : MigrationUiState
    data class RestoreDone(val result: RecoveryRestoreResult) : MigrationUiState
}

/**
 * Stable page identity for [MigrationUiState]. One arm per sealed subclass, exhaustive, matching
 * [ArtworkFetchPageKey]/[GetArtPageKey]'s precedent in `ArtworkFetchOverlay.kt`/`GetArtOverlay.kt`.
 *
 * **No collapsing here, unlike [ArtworkFetchPageKey.DONE].** Pass one collapsed `Done(writeBlocked =
 * true)` into `DONE` because it is "same shape, same buttons, no new transition" — a boolean flag on ONE
 * subclass that never changes value while that instance is displayed. None of this state's eight
 * subclasses has an equivalent same-shape internal variant: [MigrationUiState.RestoreReady] is the closest
 * candidate (`RestoreReadyBody` branches on `plan.pendingLibraryScan`) but that branch changes the BUTTON
 * COUNT itself (one `Close` vs. a `Restore`/`Cancel` row) — it fails pass one's own "same shape, same
 * buttons" test, so it stays one page with a static (never-changing-while-mounted) internal branch rather
 * than a second key.
 */
internal enum class MigrationPageKey {
    READING, READY, APPLYING, DONE, FAILED, RESTORE_READY, RESTORE_APPLYING, RESTORE_DONE
}

internal fun migrationPageKey(state: MigrationUiState): MigrationPageKey = when (state) {
    is MigrationUiState.Reading -> MigrationPageKey.READING
    is MigrationUiState.Ready -> MigrationPageKey.READY
    is MigrationUiState.Applying -> MigrationPageKey.APPLYING
    is MigrationUiState.Done -> MigrationPageKey.DONE
    is MigrationUiState.Failed -> MigrationPageKey.FAILED
    is MigrationUiState.RestoreReady -> MigrationPageKey.RESTORE_READY
    is MigrationUiState.RestoreApplying -> MigrationPageKey.RESTORE_APPLYING
    is MigrationUiState.RestoreDone -> MigrationPageKey.RESTORE_DONE
}

/**
 * Reading/Applying are transient — every dismiss path (hardware CANCEL/SYSTEM_BACK, the platform Back
 * gesture, a scrim tap) must swallow close while one is in flight so a mid-flight dismiss can't race the
 * coroutine and resurrect the modal. Only Ready/Done/Failed/RestoreReady/RestoreDone are interactive.
 * Shared by [dispatchMigration] (hardware), [MigrationImportOverlay] (scrim tap), and the
 * `MimirOverlayKind.MIGRATION` call site's `BackHandler` in `MimirAppHost.kt` (platform Back) so all three
 * dismiss paths agree on exactly the same states.
 */
internal fun migrationInteractive(state: MigrationUiState): Boolean =
    state is MigrationUiState.Ready || state is MigrationUiState.Done ||
        state is MigrationUiState.Failed || state is MigrationUiState.RestoreReady ||
        state is MigrationUiState.RestoreDone

/**
 * Route one normalized action to the open migration modal. Hard modal: it consumes EVERYTHING while open.
 *
 * @param onConfirm the user confirmed the plan (only meaningful on [MigrationUiState.Ready]).
 * @param onClose cancel / close, valid on any state.
 */
internal fun dispatchMigration(
    authority: FocusAuthority,
    state: MigrationUiState,
    action: ControllerAction,
    onConfirm: () -> Unit,
    onClose: () -> Unit,
): Boolean {
    val interactive = migrationInteractive(state)
    when (action) {
        ControllerAction.NAV_LEFT, ControllerAction.NAV_RIGHT -> authority.moveFocus(action)
        ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK -> if (interactive) onClose()
        ControllerAction.CONFIRM -> when {
            state is MigrationUiState.Ready &&
                authority.state.value.focused == CONFIRM_KEY -> onConfirm()
            state is MigrationUiState.RestoreReady &&
                !state.plan.pendingLibraryScan &&
                authority.state.value.focused == CONFIRM_KEY -> onConfirm()
            interactive -> onClose() // Cancel/Close buttons
            else -> Unit
        }
        else -> Unit // swallow everything else — nothing leaks behind the modal
    }
    return true
}

/**
 * The migration modal. Renders per [state]: a spinner while reading/applying, the dry-run plan with a
 * confirm/cancel pair, the honest result, or a friendly error. Controller + touch parity; focus is trapped
 * to [MigrationScope].
 */
@Composable
internal fun MigrationImportOverlay(
    state: MigrationUiState,
    authority: FocusAuthority,
    onConfirm: () -> Unit,
    onClose: () -> Unit,
) {
    val focus by authority.state.collectAsState()

    // Land focus on the primary action once buttons have registered their bounds.
    LaunchedEffect(state::class) {
        withFrameNanos { }
        when (state) {
            is MigrationUiState.Ready -> authority.requestFocus(CONFIRM_KEY)
            is MigrationUiState.RestoreReady -> authority.requestFocus(
                if (state.plan.pendingLibraryScan) CLOSE_KEY else CONFIRM_KEY,
            )
            is MigrationUiState.Done, is MigrationUiState.Failed, is MigrationUiState.RestoreDone ->
                authority.requestFocus(CLOSE_KEY)
            else -> Unit
        }
    }

    // Only Ready/Done/Failed can be dismissed; Reading/Applying are transient (see dispatchMigration).
    val interactive = migrationInteractive(state)
    val dismissIfInteractive = { if (interactive) onClose() }

    // Inline scrim + centered card — same shape as every other blocking overlay in this tree
    // (CompanionOverlayPermissionPrompt, ArtworkConnectOverlay, ProfileNameEditModal): a full-screen Box
    // in the SAME composition/window as the app shell, never a separate-window Dialog. A tap on the scrim
    // dismisses (when interactive); a tap on the card itself is consumed so it doesn't fall through.
    Box(
        Modifier
            .fillMaxSize()
            .background(MimirColors.SurfaceBase.copy(alpha = 0.95f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = dismissIfInteractive,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .padding(MimirSpacing.xl)
                .clip(RoundedCornerShape(MimirSpacing.radiusCard))
                .background(MimirColors.SurfaceRaised)
                .border(1.dp, MimirColors.Outline, RoundedCornerShape(MimirSpacing.radiusCard))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(MimirSpacing.xl),
        ) {
            // A phase change across this modal's 8-state lifecycle is ONE surface advancing (2026-08-20),
            // not a surface swap -- MimirAppHost.kt's MIGRATION phase-map arm was deleted (Move 1) so the
            // host crossfade no longer retargets on every phase; this AnimatedContent (Move 2) owns the
            // phase-to-phase motion instead. ANIMATE the geometry, not RESERVE it: Ready/Done/RestoreReady/
            // RestoreDone heights depend on `plan.overrides`, `notFound`, and `sourceRootHints.take(6)` --
            // content this data-dependent has no well-defined reserved height (ruling 2026-08-20).
            //
            // Audit 2026-10-14, RC-51 (S-53 (2)): the phases were a plain `fadeIn togetherWith fadeOut` on
            // `tween(OverlayMs)`, so `fadeIn`/`fadeOut` faded each page through a default-compositing layer the
            // size of the page, and every button wearing `mimirSelectionRing` had its ring (it reaches past the
            // button) cut flat for the 180 ms, then popped whole. The phases now ride the SWAP TIER, the one
            // grammar Get art and the artwork fetch already took (5b675e876): `mimirSwapTransform(fade = false)`
            // keeps the swap's clocks (the page left fades on OverlayMs, the page arriving waits the measured
            // OverlayDissolveCoverMs lead and fades in, the resize rides the arrival's clock, unclipped, never
            // the default spring) and each page fades ITSELF with `mimirRingSafeFade`, one drawing instruction
            // at a time with no layer, so a ring is never cut. THE TIMING CHANGED WITH IT: a phase change took
            // 180 ms with both pages on stage at once, and takes 310 ms now, with at most one page above alpha
            // 0.15 on any frame (the 08-15 double-exposure law, which the old simultaneous fade did not keep).
            // `MimirSwapPageGate` holds the pad until the page arriving shows and shields it from touch until
            // then: Ready's Confirm registers while the page still stands at alpha 0, and an A pressed in the
            // lead would have started an import nobody had read.
            //
            // `Modifier.weight`/`verticalArrangement` for the header+body pair MOVE from the outer Column
            // (above) to the inner Column below, one level inside this AnimatedContent -- everything that
            // used to be two direct `when(state)`-emitted groups in the outer Column is now one page's
            // content inside the transition.
            val phaseReducedMotion = mimirReducedMotion()
            AnimatedContent(
                targetState = state,
                contentKey = ::migrationPageKey,
                contentAlignment = Alignment.TopStart,
                transitionSpec = { mimirSwapTransform(phaseReducedMotion, fade = false) },
                label = "migration-phase",
            ) { page ->
                // INVARIANT (spec §6): AnimatedContent keeps the outgoing page COMPOSED for the full
                // 180ms. Unlike ArtworkFetchOverlay/GetArtOverlay (touch-only scrims, spec §6: "neither
                // overlay registers with mimirFocusable"), this modal's ActionButtons ARE FocusAuthority
                // targets (CONFIRM_KEY/CANCEL_KEY/CLOSE_KEY via `.mimirFocusable(authority, key,
                // MigrationScope)`). A stale registration surviving the 180ms fade does NOT reopen the
                // hazard the pass-two ruling flagged for onboarding SETTLING (a rejected conversion, see
                // that ruling's point 3): `dispatchMigration` gates every HARDWARE action on the LIVE
                // `state` via [migrationInteractive] ([interactive] above), not on which page is composed
                // here -- a CONFIRM press while the live state is a transient (Applying/RestoreApplying/
                // Reading) is dropped regardless of what FocusAuthority still has focused, and no real
                // Migration transition ever has the SAME FocusKey live on both the outgoing and incoming
                // page at once (Ready -> Applying, Applying -> Done, etc. never co-register a key). The
                // TOUCH path is the one genuinely live for 180ms -- a `.clickable` on the departing page's
                // own composition -- so [ActionButton]'s `live` parameter gates its ENTIRE `.clickable`
                // block, not merely its `onClick`: that button's clickable also calls
                // `authority.requestFocus(key)` as a side effect, so neutering only the callback would
                // still let a stale tap steal focus for one page's lifetime before the next page's own
                // `LaunchedEffect` re-lands it -- closing that residual, not merely the resurrected-action
                // one, is why the gate sits on the whole clickable rather than on each callback.
                val isLive = migrationPageKey(page) == migrationPageKey(state)
                val pageAlpha = mimirSwapFadeAlpha(phaseReducedMotion)
                val pageInput = MimirSwapPageGate(pageAlpha)
                Column(
                    modifier = (if (isLive) Modifier else Modifier.clearAndSetSemantics { })
                        .mimirRingSafeFade(pageAlpha)
                        .then(pageInput),
                    verticalArrangement = Arrangement.spacedBy(MimirSpacing.md),
                ) {
                    when (page) {
                        is MigrationUiState.RestoreReady, MigrationUiState.RestoreApplying -> {
                            MimirChromeText("Recovery restore", style = MimirType.RunicEyebrow, color = MimirColors.Gold)
                            MimirIntentText(
                                intent = MimirTextIntent.Mixed(
                                    listOf(
                                        MimirTextFragment.Chrome("Review this "),
                                        MimirTextFragment.Chrome("recovery archive"),
                                    ),
                                ),
                                style = MimirType.TitleCaps,
                                color = MimirColors.TextPrimary,
                            )
                        }
                        is MigrationUiState.RestoreDone -> {
                            MimirChromeText("Recovery restore", style = MimirType.RunicEyebrow, color = MimirColors.Gold)
                            MimirIntentText(
                                intent = MimirTextIntent.Authored("Restore complete"),
                                style = MimirType.TitleCaps,
                                color = MimirColors.TextPrimary,
                            )
                        }
                        else -> {
                            MimirChromeText("Import backup", style = MimirType.RunicEyebrow, color = MimirColors.Gold)
                            MimirIntentText(
                                intent = MimirTextIntent.Mixed(
                                    listOf(
                                        MimirTextFragment.Chrome("Review this "),
                                        MimirTextFragment.Authored("Mimir"),
                                        MimirTextFragment.Chrome(" backup"),
                                    ),
                                ),
                                style = MimirType.TitleCaps,
                                color = MimirColors.TextPrimary,
                            )
                        }
                    }
                    Spacer(Modifier.size(MimirSpacing.xs))

                    when (page) {
                        is MigrationUiState.Reading -> Body("Reading your file…")
                        is MigrationUiState.Applying -> Body("Bringing over your favorites, hidden games, and settings…")
                        is MigrationUiState.RestoreApplying ->
                            Body("Restoring favorites, hidden games, Open with choices, and Home Shelf items…")
                        is MigrationUiState.Failed -> {
                            Body(page.message, color = MimirColors.Danger)
                            Spacer(Modifier.size(MimirSpacing.xs))
                            ActionButton(
                                "Close", CLOSE_KEY, focus.focused == CLOSE_KEY, authority, onClose,
                                modifier = Modifier.fillMaxWidth(), live = isLive,
                            )
                        }
                        is MigrationUiState.Ready -> ReadyBody(page.plan, focus, authority, onConfirm, onClose, isLive)
                        is MigrationUiState.Done -> DoneBody(page.result, focus, authority, onClose, isLive)
                        is MigrationUiState.RestoreReady ->
                            RestoreReadyBody(page.plan, focus, authority, onConfirm, onClose, isLive)
                        is MigrationUiState.RestoreDone ->
                            RestoreDoneBody(page.result, focus, authority, onClose, isLive)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadyBody(
    plan: ImportPlan,
    focus: com.gruning.mimir.rewrite.core.input.FocusState,
    authority: FocusAuthority,
    onConfirm: () -> Unit,
    onClose: () -> Unit,
    live: Boolean,
) {
    Body("Make sure you’ve scanned your library here first, then bring over your favorites, hidden games, and settings:")
    Stat("Favorites", "${plan.favoritesMatched.size} of ${plan.favorites.size}")
    Stat("Hidden games", "${plan.hiddenMatched.size} of ${plan.hidden.size}")
    if (plan.overrides.isNotEmpty()) Stat("Open with choices", "${plan.overridesApplicable.size} of ${plan.overrides.size}")
    plan.achievements.sumOf { it.games.size }.takeIf { it > 0 }?.let { Stat("Achievements", if (it == 1) "1 game" else "$it games") }
    // Show the theme's Norse LABEL, never its internal storage key ("crimson"→"Surtr"). plan.theme is only
    // present when the key is a known MimirThemeId, so fromStorageKey resolves the right label.
    plan.theme?.let { Stat("Theme", MimirThemeId.fromStorageKey(it.themeId).label) }

    val notFound = plan.notFoundTitles()
    if (notFound.isNotEmpty()) {
        Spacer(Modifier.size(MimirSpacing.xs))
        Body("${notFound.size} not in your library yet — scan first if you’re missing games.", color = MimirColors.TextMuted)
    }
    if (plan.sourceRootHints.isNotEmpty()) {
        Body("You’ll re-pick your library folders after — folder access can’t move between apps.", color = MimirColors.TextMuted)
    }

    Spacer(Modifier.size(MimirSpacing.sm))
    Row(horizontalArrangement = Arrangement.spacedBy(MimirFocusTileRingReach)) {
        ActionButton("Bring them over", CONFIRM_KEY, focus.focused == CONFIRM_KEY, authority, onConfirm, live = live)
        ActionButton("Cancel", CANCEL_KEY, focus.focused == CANCEL_KEY, authority, onClose, primary = false, live = live)
    }
}

@Composable
private fun RestoreReadyBody(
    plan: com.gruning.mimir.rewrite.core.model.recovery.RecoveryRestorePlan,
    focus: com.gruning.mimir.rewrite.core.input.FocusState,
    authority: FocusAuthority,
    onConfirm: () -> Unit,
    onClose: () -> Unit,
    live: Boolean,
) {
    Body(
        "Restore adds back what the backup had. It never removes what you have now.",
        color = MimirColors.TextSecondary,
    )
    RestoreStoreStats("Favorites", plan.favorites)
    RestoreStoreStats("Hidden games", plan.hidden)
    RestoreStoreStats("Open with choices", plan.overrides)
    RestoreStoreStats("Home shelf", plan.shelf)
    if (plan.pendingLibraryScan) {
        Spacer(Modifier.size(MimirSpacing.xs))
        Body(
            "Scan your library here first, then run restore again to bring over owner state.",
            color = MimirColors.Danger,
        )
    }
    if (plan.sourceRootHints.isNotEmpty()) {
        Body(
            "You’ll re-pick your library folders after — folder access can’t move between apps.",
            color = MimirColors.TextMuted,
        )
    }
    NotRestoredSection(plan.notRestored)
    Spacer(Modifier.size(MimirSpacing.sm))
    if (plan.pendingLibraryScan) {
        ActionButton("Close", CLOSE_KEY, focus.focused == CLOSE_KEY, authority, onClose, live = live)
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(MimirFocusTileRingReach)) {
            ActionButton("Restore", CONFIRM_KEY, focus.focused == CONFIRM_KEY, authority, onConfirm, live = live)
            ActionButton("Cancel", CANCEL_KEY, focus.focused == CANCEL_KEY, authority, onClose, primary = false, live = live)
        }
    }
}

@Composable
private fun RestoreDoneBody(
    result: RecoveryRestoreResult,
    focus: com.gruning.mimir.rewrite.core.input.FocusState,
    authority: FocusAuthority,
    onClose: () -> Unit,
    live: Boolean,
) {
    RestoreResultStats("Favorites restored", result.favoritesRestored, result.favoritesSkipped, result.favoritesUnmatched)
    RestoreResultStats("Hidden restored", result.hiddenRestored, result.hiddenSkipped, result.hiddenUnmatched)
    if (result.overridesRestored + result.overridesSkipped + result.overridesUnmatched > 0) {
        RestoreResultStats(
            "Open with choices",
            result.overridesRestored,
            result.overridesSkipped,
            result.overridesUnmatched,
        )
    }
    if (result.shelfRestored + result.shelfSkipped + result.shelfUnmatched > 0) {
        RestoreResultStats("Home shelf restored", result.shelfRestored, result.shelfSkipped, result.shelfUnmatched)
    }
    if (result.pendingLibraryScan) {
        Spacer(Modifier.size(MimirSpacing.xs))
        Body(
            "Your library still needs a scan before more owner state can be restored.",
            color = MimirColors.TextMuted,
        )
    }
    if (result.sourceRootHints.isNotEmpty()) {
        Spacer(Modifier.size(MimirSpacing.xs))
        Body("Re-pick these library folders in Settings › Library:", color = MimirColors.TextSecondary)
        result.sourceRootHints.take(6).forEach { hint ->
            val marker = if (hint.isPrimary) " (primary)" else ""
            Body("• ${hint.friendlyLabel}$marker", color = MimirColors.TextMuted)
        }
    }
    NotRestoredSection(result.notRestored)
    Spacer(Modifier.size(MimirSpacing.xs))
    Body(
        "A safety snapshot of your current owner state was saved before restore. It is not a one-tap undo.",
        color = MimirColors.TextMuted,
    )
    Spacer(Modifier.size(MimirSpacing.sm))
    ActionButton("Close", CLOSE_KEY, focus.focused == CLOSE_KEY, authority, onClose, live = live)
}

@Composable
private fun NotRestoredSection(categories: List<RecoveryRestoreExcludedCategory>) {
    if (categories.isEmpty()) return
    Spacer(Modifier.size(MimirSpacing.xs))
    MimirChromeText("Not restored", style = MimirType.RunicEyebrowCompact, color = MimirColors.TextMuted)
    categories.forEach { category ->
        Body("• ${category.label}", color = MimirColors.TextMuted)
    }
}

@Composable
private fun RestoreStoreStats(
    label: String,
    store: com.gruning.mimir.rewrite.core.model.recovery.RestoreStorePlan<*>,
) {
    Stat(
        label,
        "${store.matched} ready · ${store.skipped} skipped (${store.unmatched} unmatched)",
    )
}

@Composable
private fun RestoreResultStats(
    label: String,
    restored: Int,
    skipped: Int,
    unmatched: Int,
) {
    Stat(label, "$restored restored · $skipped skipped · $unmatched unmatched")
}

@Composable
private fun DoneBody(
    result: ImportResult,
    focus: com.gruning.mimir.rewrite.core.input.FocusState,
    authority: FocusAuthority,
    onClose: () -> Unit,
    live: Boolean,
) {
    Stat("Favorites restored", if (result.favoritesNotFound > 0) "${result.favoritesRestored} · ${result.favoritesNotFound} not found" else "${result.favoritesRestored}")
    Stat("Hidden restored", if (result.hiddenNotFound > 0) "${result.hiddenRestored} · ${result.hiddenNotFound} not found" else "${result.hiddenRestored}")
    if (result.overridesRestored + result.overridesSkipped > 0) {
        Stat("Open with choices", if (result.overridesSkipped > 0) "${result.overridesRestored} · ${result.overridesSkipped} skipped" else "${result.overridesRestored}")
    }
    if (result.achievementGamesRestored > 0) {
        Stat("Achievements restored", if (result.achievementGamesRestored == 1) "1 game" else "${result.achievementGamesRestored} games")
    }
    // Never surface the raw ignored key (a legacy/unknown codename is meaningless to the user).
    Stat("Theme", if (result.themeApplied) "applied" else if (result.ignoredThemeId != null) "kept your current theme" else "unchanged")

    if (result.sourceRootsToRegrant.isNotEmpty()) {
        Spacer(Modifier.size(MimirSpacing.xs))
        Body("Re-pick these library folders in Settings › Library:", color = MimirColors.TextSecondary)
        result.sourceRootsToRegrant.take(6).forEach { Body("• $it", color = MimirColors.TextMuted) }
    }
    if (result.notFoundTitles.isNotEmpty()) {
        Spacer(Modifier.size(MimirSpacing.xs))
        Body("Couldn’t find ${result.notFoundTitles.size} ${if (result.notFoundTitles.size == 1) "game" else "games"} — scan them in, then import again to catch them.", color = MimirColors.TextMuted)
    }

    Spacer(Modifier.size(MimirSpacing.sm))
    ActionButton("Close", CLOSE_KEY, focus.focused == CLOSE_KEY, authority, onClose, live = live)
}

@Composable
private fun Body(text: String, color: androidx.compose.ui.graphics.Color = MimirColors.TextSecondary) {
    Text(text, style = MimirType.RowSupport, color = color)
}

@Composable
private fun Stat(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MimirSpacing.md)) {
        MimirChromeText(
            label,
            style = MimirType.RunicEyebrowCompact,
            color = MimirColors.TextMuted,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MimirType.RailItem, color = MimirColors.TextPrimary)
    }
}

@Composable
private fun ActionButton(
    label: String,
    key: FocusKey,
    focused: Boolean,
    authority: FocusAuthority,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    // Default true: every pre-existing call site (outside the phase AnimatedContent, e.g. any future
    // non-animated caller) keeps today's behaviour. The AnimatedContent in MigrationImportOverlay passes
    // `isLive` explicitly for every call reachable from a page (spec §6 invariant). Gating the WHOLE
    // `.clickable` -- not just `onClick` -- matters here specifically because this button's clickable also
    // calls `authority.requestFocus(key)` as a side effect: neutering only the callback would still let a
    // stale tap on a departing page steal focus for the remainder of that page's composed lifetime before
    // the next page's own `LaunchedEffect` re-lands it.
    live: Boolean = true,
) {
    val palette = LocalMimirPalette.current
    // An etched chip inside a 48 dp hit box. [primary] is
    // the page's main answer (the same dark plate, its rim and word in the accent), never a fill. The one ring (ring migration, 2026-09-28) is on the PLATE itself, with no inset and
    // BEFORE the surface, so it is the plate's one edge. `enabled = live` is the same gate the whole `.clickable` sat behind.
    Box(
        modifier
            .heightIn(min = MimirSpacing.minTarget)
            .widthIn(min = MimirSpacing.minTarget)
            .mimirFocusable(authority, key, MigrationScope)
            .mimirActivatable(label = label, enabled = live, authority = authority, key = key, onActivate = onClick),
        contentAlignment = Alignment.Center,
    ) {
        MimirEtchedChipPlate(
            modifier = Modifier.testTag(etchedChipPlateTag("import", label)).mimirSelectionRing(focused = focused, identity = key, shape = MimirRingShape.Pill, edgeFlush = true),
            focused = focused,
            primary = primary,
            palette = palette,
            edgeFlush = true,
        ) {
            MimirChromeText(label, style = MimirEtchedChipLabelStyle, color = palette.etchedChipInk(primary = primary), maxLines = 1, softWrap = false)
        }
    }
}
