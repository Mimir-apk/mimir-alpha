package com.gruning.mimir.rewrite.feature.library

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.ui.BottomSurface
import com.gruning.mimir.rewrite.core.ui.BottomUiFloatTransitionReceipt
import com.gruning.mimir.rewrite.core.ui.BottomUiReceiptFormat
import com.gruning.mimir.rewrite.core.ui.BottomUiSurfaceReceipt
import com.gruning.mimir.rewrite.core.ui.MimirGlass
import com.gruning.mimir.rewrite.core.ui.mimirGlassBackdropProgress
import com.gruning.mimir.rewrite.core.ui.mimirReducedMotion
import com.gruning.mimir.rewrite.core.ui.mimirSwapEnterSpec
import com.gruning.mimir.rewrite.core.ui.mimirSwapExitSpec
import com.gruning.mimir.rewrite.core.ui.rememberBottomUiControl
import com.gruning.mimir.rewrite.core.ui.rememberMimirHeldGlassProgress

/**
 * The Bottom's launch card, opened from a surface other than the platform shelf (the Activity page).
 *
 * It is the SAME card ([CompanionLaunchCard], the one the shelf draws) on the same clocks, with the same surface and transition receipts and
 * the same `launch-card.*` control lines, so a game's card is one thing wherever it is opened from. What it does not do is own the game: the
 * caller holds [game] (null the moment the card starts closing, as the shelf's `pending`) and decides what PLAY and the display editor mean, so
 * the shelf's PLAY path in `:app` is reused rather than copied here.
 *
 * [content] is the page the card opens over. The SCENE takes the blur, never the panel, through core-ui's cross-fade plan (RC-05, audit
 * 2026-10-14: the progress is read in draw, so the page is not recomposed per frame and there is no radius sweep). Soften first: the blur opens
 * on OverlayMs from the tap while the card waits the swap tier's 130 ms lead, then arrives on OverlayMs; on close the card dissolves on OverlayMs
 * and the blur holds through it before it clears (`rememberMimirGlassHold`). The game the card was showing is kept for the dissolve.
 * Reduced motion snaps both.
 *
 * Touch only. The card holds no focus and never the process-wide pad gate; the Presentation window cannot take key input.
 */
@Composable
fun CompanionLaunchCardLayer(
    game: GameRow?,
    active: Boolean,
    displayChoiceFor: (GameRow) -> CompanionLaunchDisplayChoice,
    onDismiss: () -> Unit,
    onEditDisplay: (GameRow) -> Unit,
    onPlay: (GameRow) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * The card's look (slices L5 and L6), the same global setting the shelf reads: the card changes face on the tap and reports the new look through
     * [onLookChange], which the app persists. A new value here (a restored one) shows the same way.
     */
    look: CompanionLaunchLook = CompanionLaunchLook.Default,
    onLookChange: (CompanionLaunchLook) -> Unit = {},
    /** The game's platform name, for Cinematic's meta line and the Journal's eyebrow. Null when the host does not know it; then neither is drawn. */
    platformLabelFor: (GameRow) -> String? = { null },
    /** The Journal look's totals (session time, achievements), which live in the app. Called only while the Journal is composed. */
    journalFactsFor: @Composable (GameRow) -> CompanionLaunchCardFacts = { CompanionLaunchCardFacts.None },
    content: @Composable () -> Unit,
) {
    var shownLook by remember { mutableStateOf(look) }
    LaunchedEffect(look) { shownLook = look }
    var dissolving by remember { mutableStateOf<GameRow?>(null) }
    LaunchedEffect(game) { if (game != null) dissolving = game }
    val card = game ?: dissolving
    val sceneGlass = rememberMimirHeldGlassProgress(visible = game != null)
    val reducedMotion = mimirReducedMotion()
    val glass by animateFloatAsState(
        targetValue = if (game != null) 1f else 0f,
        animationSpec = companionLaunchCardSpec(up = game != null, reducedMotion = reducedMotion),
        label = "activity-launch-card-glass",
        finishedListener = { settled -> if (settled == 0f) dissolving = null },
    )
    val cardShowing by remember { derivedStateOf { glass > 0.001f } }
    BottomUiSurfaceReceipt(
        surface = BottomSurface.LAUNCH_CARD,
        visible = game != null,
        transition = if (game != null) "launch-card.enter" else "launch-card.exit",
    )
    BottomUiFloatTransitionReceipt(
        name = if (game != null) "launch-card.enter" else "launch-card.exit",
        target = if (game != null) 1f else 0f,
        value = { glass },
    )
    val cardControl = rememberBottomUiControl(BottomSurface.LAUNCH_CARD)
    Box(modifier.fillMaxSize()) {
        // One live blur under an open picker: the page under the card is a SNAPSHOT while the glass is at its peak
        // (core-ui's one freeze rule), so the Now Playing card's own media blur inside it stops re-rendering under the open card.
        Box(Modifier.fillMaxSize().mimirGlassBackdropProgress({ MimirGlass.backdropBlurRadius }, { sceneGlass() }, snapshot = { true })) { content() }
        if (card != null && cardShowing) {
            CompanionLaunchCard(
                game = card,
                progress = glass,
                // Both halves, as the shelf: a dissolving card (`game == null`) is never PLAY-able even while active.
                active = active && game != null,
                onDismiss = {
                    cardControl("launch-card.dismiss", null)
                    onDismiss()
                },
                onEditDisplay = {
                    if (active) {
                        game?.let {
                            cardControl("launch-card.edit_display", BottomUiReceiptFormat.opaque("game", it.gameId.value))
                            onEditDisplay(it)
                        }
                    }
                },
                displayChoice = displayChoiceFor(card),
                onPlay = {
                    val live = game
                    if (active && live != null) {
                        cardControl("launch-card.play", BottomUiReceiptFormat.opaque("game", live.gameId.value))
                        onPlay(live)
                    }
                },
                look = shownLook,
                onLookChange = { next ->
                    shownLook = next
                    onLookChange(next)
                },
                platformLabel = platformLabelFor(card),
                journalFactsFor = journalFactsFor,
            )
        }
    }
}

/**
 * The Bottom launch card's own clock (RC-05; Q3, siblings matched with the picker, the prompt and the chooser): it arrives on
 * the swap tier (the 130 ms lead while the glass softens the scene, then OverlayMs) and leaves on OverlayMs; reduced motion snaps both. The
 * platform shelf's card and the Activity page's layer share it, and `CompanionLaunchCardGlassTimelineTest` runs it against the held glass.
 */
internal fun companionLaunchCardSpec(up: Boolean, reducedMotion: Boolean): FiniteAnimationSpec<Float> =
    if (up) mimirSwapEnterSpec(reducedMotion) else mimirSwapExitSpec(reducedMotion)
