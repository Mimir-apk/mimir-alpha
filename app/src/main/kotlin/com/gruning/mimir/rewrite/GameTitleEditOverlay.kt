package com.gruning.mimir.rewrite

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.MimirKeyboardFocusScope
import com.gruning.mimir.rewrite.core.input.mimirActivatable
import com.gruning.mimir.rewrite.core.input.mimirFocusable
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.MimirIntentText
import com.gruning.mimir.rewrite.core.ui.MimirKeyboard
import com.gruning.mimir.rewrite.core.ui.MimirKeyboardController
import com.gruning.mimir.rewrite.core.ui.MimirKeyboardKeyDecorator
import com.gruning.mimir.rewrite.core.ui.MimirCopy
import com.gruning.mimir.rewrite.core.ui.MimirRingShape
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirTextIntent
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.MimirTypeStep
import com.gruning.mimir.rewrite.core.ui.mimirGlassScrimColor
import com.gruning.mimir.rewrite.core.ui.mimirSelectionRing

/**
 * Piece 3. Lives in the SAME [MimirKeyboardFocusScope] the keyboard's own keys already register into, with
 * REAL on-screen bounds via [mimirFocusable] — the identical coordinate space every key in this panel
 * already uses (never a synthetic/virtual bounds mix, the mechanism BUG-A and its two siblings shipped
 * from). It is only ever composed while [GameTitleEditRequest.hasOverride] is true, so its registration
 * and unregistration bracket the button's own visibility exactly — an absent override never leaves a
 * phantom focus target, and an appearing one is always freshly registered before it can be reached.
 */
internal val GameTitleEditResetKey = FocusKey("game-title-edit/reset")

/**
 * The target of the manual rename overlay — policy 2026-08-11, cosmetic display override only
 *. Shared by both launch cards
 * (Home hero and Library detail) since both open it through the same rune glyph on the same shared
 * `HomeLaunchCard` composition.
 *
 * @param currentTitle seeds the draft — this is [com.gruning.mimir.rewrite.core.model.library.GameRow.title],
 *   which already carries any existing override (the model boundary resolves it; this overlay never re-derives it).
 * @param hasOverride gates the "reset to original name" affordance — true only when the CURRENT title IS a
 *   manual override, matching [com.gruning.mimir.rewrite.core.model.library.GameRow.hasTitleOverride].
 */
internal data class GameTitleEditRequest(
    val gameId: GameId,
    val currentTitle: String,
    val hasOverride: Boolean,
)

/**
 * Manual game-rename entry + reset — a full-screen touch overlay reusing the ONE in-app [MimirKeyboard]
 * (typing is 100% touch; the controller only DISMISSES), same
 * shape as [com.gruning.mimir.rewrite.feature.settings.ProfileNameEditOverlay] /
 * `SteamGridDbKeyEditOverlay`. Done saves via [onSave]; Back/scrim cancels via [onCancel]; when
 * [request]'s [GameTitleEditRequest.hasOverride] is true, an explicit "Reset to original name" affordance
 * calls [onReset] — a bad manual edit must be undoable without hunting the original string (spec §5/§3b).
 * Unlike the rest of this overlay's typing surface, reset is ALSO controller-reachable (policy
 * 2026-08-11, piece 3) — see [GameTitleEditResetKey] and this file's `authority` parameter.
 *
 * This overlay never applies the override to a render site — [onSave]/[onReset] are the ONLY writers,
 * and they call [com.gruning.mimir.rewrite.core.model.library.LibraryRepository.setTitleOverride] /
 * `.clearTitleOverride`, which already materialize at the model boundary (`libraryDisplayTitle`). This
 * overlay only collects the typed string; it asserts nothing about where the override is applied.
 */
@Composable
internal fun GameTitleEditOverlay(
    request: GameTitleEditRequest,
    onSave: (GameId, String) -> Unit,
    onReset: (GameId) -> Unit,
    onCancel: () -> Unit,
    controller: MimirKeyboardController? = null,
    keyDecorator: MimirKeyboardKeyDecorator? = null,
    // Optional, matching controller/keyDecorator's own optionality: null keeps every existing
    // touch-only preview/test call site compiling unchanged. Non-null is what makes the reset
    // affordance below controller-reachable (piece 3).
    authority: FocusAuthority? = null,
) {
    // BM03-style: an accidental scrim tap must not destroy in-progress typing. Keyed on gameId so
    // switching targets (impossible today — the overlay is modal and single-target — but cheap to keep
    // honest) never leaks one game's draft into another's seed.
    var draft by remember(request.gameId) {
        mutableStateOf(GameTitleEditDraftStash.value?.takeIf { it.first == request.gameId }?.second ?: request.currentTitle)
    }
    val display = draft.trim()
    val palette = LocalMimirPalette.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            // Layering fix (policy 2026-08-11, device review): this used to be an unrelated flat
            // `palette.surfaceBase` wash at a bespoke 0.95 alpha — copied from ProfileNameEditOverlay's
            // shape, where there is no launch card behind it to preserve. Here there always is one (see
            // `mimirOverlayUsesGlass`, which now also keeps the destination blurred for the whole time
            // this overlay is open), so this scrim reuses `mimirGlassScrimColor()` instead — the SAME
            // token the launch card's own outer surface already paints itself with — so GameTitleEdit
            // reads as one more stacked liquid-glass layer over the already-glass card behind it, not a
            // mismatched opaque color that lets an unblurred scene's raw hue bleed through its 5% gap.
            .background(mimirGlassScrimColor())
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onCancel,
            ),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compactPreview = maxHeight.value < 420f
            Column(Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(
                            horizontal = MimirSpacing.xxl,
                            vertical = if (compactPreview) MimirSpacing.xs else MimirSpacing.md,
                        ),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Content-writing pass : was the clinical Game-title label.
                    // Reuses MimirCopy.RenameGame verbatim — the SAME word the entry hint beside the launch
                    // card's edit mark already shows the player, so the overlay's own header confirms what
                    // they just did instead of introducing a second, more formal label for the same thing.
                    MimirChromeText(MimirCopy.RenameGame, style = MimirTypeScale.RunicEyebrow, color = palette.accent)
                    if (!compactPreview) {
                        MimirIntentText(
                            intent = if (display.isBlank()) {
                                // Content-writing pass : the old placeholder framed
                                // every rename as fixing an error ("corrected"), but most renames are just
                                // preference. Reuses the exact phrase ProfileNameEditOverlay's identical
                                // keyboard-overlay shape already ships for its own placeholder, rather than
                                // inventing new wording for the same gesture.
                                MimirTextIntent.StaticChrome("Type a name")
                            } else {
                                MimirTextIntent.Authored(display)
                            },
                            style = MimirTypeScale.Title,
                            color = if (display.isBlank()) palette.textMuted else palette.textPrimary,
                            textAlign = TextAlign.Center,
                        )
                        // Policy 2026-08-11 (paraphrased to avoid re-tripping this file's own
                        // characterization tests with the deleted sentence): narrating which key saves and
                        // which one cancels is debug behavior — MimirKeyboard's own confirm key already
                        // shows a checkmark, so spelling that out in prose only restated the key. The old
                        // paragraph also carried an internal feature-name leak (piece 1's scope, already
                        // gone). This is now ONE short, secondary line directly below the field — not a
                        // help paragraph — on the capped type scale ([MimirTypeScale.SupportCompact], no
                        // new sp literal) and a palette color (no hardcoded hex).
                        Text(
                            text = MimirCopy.RenameDoesNotTouchFiles,
                            style = MimirTypeScale.SupportCompact,
                            color = palette.textMuted,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = MimirSpacing.sm),
                        )
                    }
                    if (request.hasOverride) {
                        // Piece 3: reachable by controller. resetFocused reads the SAME authority state
                        // every other mimirFocusable consumer in this tree reads from — no second source
                        // of "am I focused" truth. Touch keeps working unconditionally (the clickable
                        // below never checks focus, matching this overlay's existing touch-first shape);
                        // CONFIRM activation for the D-pad path is handled OUTSIDE this composable, in
                        // MimirAppInputDispatch's GAME_TITLE_EDIT branch — never through
                        // MimirKeyboardFocusBinding's key-activator map, which only tracks the keyboard's
                        // OWN key set and would silently drop this button's activator on the very next
                        // shift/symbols toggle (reconcileFocus prunes anything not in that frame's key
                        // rows) — a stranding bug this overlay must not ship.
                        val resetFocused = authority?.state?.collectAsState()?.value?.let {
                            it.scope == MimirKeyboardFocusScope && it.focused == GameTitleEditResetKey
                        } ?: false
                        Box(
                            modifier = Modifier
                                .padding(top = MimirSpacing.sm)
                                .then(
                                    if (authority != null) {
                                        Modifier.mimirFocusable(authority, GameTitleEditResetKey, MimirKeyboardFocusScope)
                                    } else {
                                        Modifier
                                    },
                                )
                                // The one ring (ring migration, 2026-09-28), as the keyboard's keys below now wear
                                // theirs: a row ring whose wash replaces the 0.14 ink tint, no grow. Touch-only /
                                // no-authority callers never light it (resetFocused is always false then).
                                .mimirSelectionRing(
                                    focused = resetFocused,
                                    identity = GameTitleEditResetKey,
                                    shape = MimirRingShape.Row(MimirSpacing.radiusCard),
                                )
                                .clip(RoundedCornerShape(MimirSpacing.radiusCard))
                                .mimirActivatable(label = "Reset to original name") {
                                    GameTitleEditDraftStash.value = null
                                    onReset(request.gameId)
                                }
                                .padding(horizontal = MimirSpacing.md, vertical = MimirSpacing.xs),
                        ) {
                            MimirChromeText(
                                MimirCopy.ResetToOriginalName,
                                style = MimirTypeScale.MenuAction.copy(fontSize = MimirTypeStep.Label),
                                color = palette.accent,
                            )
                        }
                    }
                }
                MimirKeyboard(
                    value = draft,
                    // The Customize media keyboard's own bottom inset: the keyboard no longer runs to the screen's last pixel. It is the floating,
                    // all-corner panel the Customize keyboard is, because a docked panel (rounded on top only) lifted off the edge would show square bottom corners.
                    modifier = Modifier.padding(bottom = EnrichKeyboardSheetAirDp.dp),
                    floating = true,
                    onValueChange = {
                        draft = it
                        GameTitleEditDraftStash.value = request.gameId to it
                    },
                    onDone = {
                        GameTitleEditDraftStash.value = null
                        onSave(request.gameId, display)
                    },
                    controller = controller,
                    keyDecorator = keyDecorator,
                    // Matches the eyebrow above (both label the same field) — was the same clinical
                    // Game-title string, now the one already-approved word for this whole flow.
                    targetLabel = MimirCopy.RenameGame,
                )
            }
        }
    }
}

/** Unfinished rename drafts survive an accidental dismissal (cleared on save/reset; process-lifetime). */
internal object GameTitleEditDraftStash {
    var value: Pair<GameId, String>? = null
}
