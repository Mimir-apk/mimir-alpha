package com.gruning.mimir.rewrite

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.ui.mimirSwapTransform
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirEtchedChipLabelStyle
import com.gruning.mimir.rewrite.core.ui.MimirEtchedChipPlate
import com.gruning.mimir.rewrite.core.ui.MimirRingShape
import com.gruning.mimir.rewrite.core.ui.etchedChipInk
import com.gruning.mimir.rewrite.core.ui.mimirSelectionRing
import com.gruning.mimir.rewrite.core.ui.MimirIndeterminateBar
import com.gruning.mimir.rewrite.core.ui.mimirReducedMotion
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.input.mimirFocusable
import com.gruning.mimir.rewrite.core.input.mimirActivatable
import com.gruning.mimir.rewrite.core.ui.theme.MimirColors
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.theme.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.theme.MimirType

internal const val ArtworkWriteBlockedCopy =
    "Mimir couldn’t save beside one or more games. Choose the affected folder again in Settings › Library, then retry."

/**
 * The artwork-download progress window — a HARD MODAL over Settings. It replaces the old
 * dev-looking Toast. While [ArtworkFetchUi.Running], nothing behind it is reachable and there is exactly one
 * control — Cancel — which cooperatively stops the fetch ([ArtworkFetchController.fetchAllArtwork] is
 * cancellable). On finish/cancel it shows a summary with a single Close.
 *
 * The app shell swallows controller nav while this is open and routes Back → cancel (running) / close (done).
 * On the terminal summary (Done/Cancelled) A also activates Close (G-5) — while running, A stays inert so a
 * habit press can never invisibly cancel a long fetch.
 */
sealed interface ArtworkFetchUi {
    /** Confirmation shown after a scan and its identity sweep discover new games. */
    /**
     * The one-time artwork question, raised when the hash settles.
     *
     * [hasAccount] is not decoration: with no ScreenScraper credentials every fetch returns `GateClosed`,
     * so the card must ask to CONNECT rather than offer to enrich. Both renderings share the same two focus
     * keys, so the controller arm and the focus order do not fork.
     */
    data class ConfirmNewGames(val count: Int, val hasAccount: Boolean) : ArtworkFetchUi
    /** In flight. [total] is 0 until the first game is counted (show "Preparing…" until then). */
    data class Running(val done: Int, val total: Int, val matched: Int) : ArtworkFetchUi
    /** Cancel was pressed; the in-flight game finishes then the run stops. Immediate feedback so it never
     *  looks stuck during the (cooperative) ~1–2s wind-down. [matched] carries the count for the summary. */
    data class Stopping(val matched: Int) : ArtworkFetchUi
    /** [writeBlocked]: nothing (more) could be saved — library folder access is read-only or writes fail. */
    data class Done(val total: Int, val matched: Int, val writeBlocked: Boolean = false) : ArtworkFetchUi
    data class Cancelled(val matched: Int) : ArtworkFetchUi
}

/** The new-game card's words and its primary button, in one place so the two renderings cannot drift. */
internal data class NewGameEnrichCopy(
    val title: String,
    val body: String,
    val primaryLabel: String,
)

/**
 * With no ScreenScraper account every fetch returns `GateClosed`, so the card asks to connect instead of
 * offering to enrich. It deliberately does NOT name the art kinds it would download: what a fetch actually
 * pulls is a separate open question , and naming boxes or cartridges here would promise
 * it before it is true.
 */
internal fun newGameEnrichCopy(count: Int, hasAccount: Boolean): NewGameEnrichCopy {
    val games = "$count game${if (count == 1) "" else "s"}"
    return if (hasAccount) {
        NewGameEnrichCopy(
            title = "Your library is identified",
            body = "$games are ready for artwork. Fetch it now?",
            primaryLabel = "Fetch artwork",
        )
    } else {
        NewGameEnrichCopy(
            title = "Add artwork?",
            body = "$games are ready for artwork. It comes from ScreenScraper, which needs a free account.",
            primaryLabel = "Connect",
        )
    }
}

/**
 * Stable page identity for [ArtworkFetchUi].
 *
 * [ArtworkFetchUi.Running] is a NEW data-class instance on every ScreenScraper progress tick
 * (`MimirArtworkCoordinator.publishProgress`) — without a page identity that collapses every tick to the
 * same key, an `AnimatedContent` keyed on the raw state would restart its fade on every single result: a
 * strobe, not a fix. `Done(writeBlocked = true)` renders different copy from a plain `Done` but is
 * deliberately kept inside the same [DONE] page — same shape, same buttons, no new transition.
 */
internal enum class ArtworkFetchPageKey { CONFIRM, RUNNING, STOPPING, DONE, CANCELLED }

internal val NewGameEnrichFocusScope = FocusScope("artwork/new-games")
internal val NewGameEnrichConfirmKey = FocusKey("artwork/new-games/confirm")
internal val NewGameEnrichDismissKey = FocusKey("artwork/new-games/dismiss")

/**
 * Where this card's focus lands: "Not now", whatever the primary button says.
 *
 * The primary does network work on the player's own account. With an account it is "Fetch artwork", a ScreenScraper batch; with
 * none it is "Connect", a credential flow. Either way a bare A must never start it, so the dismiss holds the default and the
 * primary stays one deliberate move away. A means "not now", which is what A has meant on every card before this one.
 *
 * Until 2026-10-08 the with-account default was Fetch, on the reasoning that A meaning "yes, the thing I just asked for" is what a
 * confirmation is for. The Thor re-check on combined16 (F7) showed the card arriving over Library Search while a query was being
 * typed, so the A press that would have "confirmed" was one the player never meant for it. The no-account half was found on the
 * emulator, 2026-09-22, by walking onboarding the way a player would.
 */
internal fun newGameEnrichInitialFocus(): FocusKey = NewGameEnrichDismissKey

/**
 * Pushes the card's scope with "Not now" already remembered as where it lands (P2-1, code review 2026-10-08). The scope is pushed in an effect, before the
 * buttons have been measured, and each button registers from its own layout: `DefaultFocusAuthority.maybeSeedFocusOnRegister` gives focus to the first key
 * to register, or to the scope's remembered key, which after an earlier showing is wherever the player last left it. "Fetch artwork" sits above "Not now",
 * so without this it held focus for the frames before the landing request below, and an A press in them started the batch. With "Not now" remembered
 * first, "Fetch artwork" registering waits for it, and the first focus the card ever has is "Not now".
 */
internal fun mountNewGameEnrichFocusScope(authority: FocusAuthority) {
    authority.rememberFocusForScope(NewGameEnrichFocusScope, newGameEnrichInitialFocus())
    authority.pushScope(NewGameEnrichFocusScope)
}

internal fun artworkFetchPageKey(state: ArtworkFetchUi): ArtworkFetchPageKey = when (state) {
    is ArtworkFetchUi.ConfirmNewGames -> ArtworkFetchPageKey.CONFIRM
    is ArtworkFetchUi.Running -> ArtworkFetchPageKey.RUNNING
    is ArtworkFetchUi.Stopping -> ArtworkFetchPageKey.STOPPING
    is ArtworkFetchUi.Done -> ArtworkFetchPageKey.DONE
    is ArtworkFetchUi.Cancelled -> ArtworkFetchPageKey.CANCELLED
}

/** Which fetch is running — only the user-facing noun differs; the window + Cancel flow are identical. */
enum class ArtworkFetchKind(
    val findingTitle: String,
    val doneTitle: String,
    val nounSingular: String,
    val nounPlural: String,
) {
    ARTWORK("Finding artwork", "Artwork updated", "game", "games"),
    /** Retained for source compatibility; artwork batches now count updated games, not individual covers. */
    @Deprecated("Use ARTWORK")
    COVERS("Finding artwork", "Artwork updated", "game", "games"),
}

@Composable
fun ArtworkFetchOverlay(
    state: ArtworkFetchUi,
    onCancel: () -> Unit,
    onClose: () -> Unit,
    kind: ArtworkFetchKind = ArtworkFetchKind.ARTWORK,
    onConfirm: () -> Unit = {},
    onDismiss: () -> Unit = onCancel,
    authority: FocusAuthority? = null,
) {
    fun count(n: Int) = "$n ${if (n == 1) kind.nounSingular else kind.nounPlural}"
    val isConfirm = state is ArtworkFetchUi.ConfirmNewGames
    val focus = authority?.state?.collectAsState()?.value
    DisposableEffect(authority, isConfirm) {
        if (authority != null && isConfirm) {
            if (authority.state.value.scope != NewGameEnrichFocusScope) mountNewGameEnrichFocusScope(authority)
        }
        onDispose {
            if (authority != null && isConfirm) authority.discardScope(NewGameEnrichFocusScope)
        }
    }
    // Keyed on the copy too: when the card's words change (an account appears) focus lands again, on "Not now" again.
    val confirmHasAccount = (state as? ArtworkFetchUi.ConfirmNewGames)?.hasAccount
    LaunchedEffect(authority, isConfirm, confirmHasAccount) {
        if (authority == null || !isConfirm) return@LaunchedEffect
        withFrameNanos { }
        authority.requestFocus(newGameEnrichInitialFocus())
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MimirColors.SurfaceBase.copy(alpha = 0.96f))
            // Absorb every touch — the scrim NEVER dismisses; exit is only via the explicit button.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .padding(MimirSpacing.xl)
                .clip(RoundedCornerShape(MimirSpacing.radiusCard))
                .background(MimirColors.SurfaceRaised)
                .padding(MimirSpacing.xl),
        ) {
            //  a phase change (Running -> Stopping ->
            // Done/Cancelled) is ONE surface advancing, not a surface swap. The fade-out, fade-in and the
            // panel's resize all run on the SAME tween(OverlayMs, Easing) -- the exact sentence
            // HomeTomeLibraryOverlay.kt:1301-1320 already settled on. `contentKey` collapses every Running
            // progress tick to one page identity so a ScreenScraper result cannot restart this transition.
            // Never the bare `fadeIn(...) togetherWith fadeOut(...)` form -- that carries a default SPRING
            // SizeTransform (verified from animation-android:1.7.3 bytecode), a different curve family.
            val stepReducedMotion = mimirReducedMotion()
            AnimatedContent(
                targetState = state,
                contentKey = ::artworkFetchPageKey,
                contentAlignment = Alignment.TopCenter,
                // the two steps no longer fade at once (two pages at half alpha mid-way, the 08-15
                // double exposure). The swap tier: the old step out, the new one in from the 130 ms cover, the
                // resize on the arrival's clock, unclipped (still never the default spring).
                transitionSpec = { mimirSwapTransform(stepReducedMotion) },
                label = "artwork-fetch-phase",
            ) { page ->
                // INVARIANT (spec §6): AnimatedContent keeps the outgoing page COMPOSED for the full
                // 180ms, unlike the host's own outgoing layer (recorded pixels, MimirAppHost.kt:702-714).
                // `page` is this composition's own target; `state` (the composable's live parameter) is
                // the actual current phase. When they disagree, this entry is the one fading OUT --
                // reproduce the host's own treatment: no live callback, no live semantics, so a stale tap
                // (e.g. Running's Cancel, ArtworkFetchOverlay.kt -> cancelFetch, which writes Stopping
                // unconditionally) can never resurrect a dead page.
                val isLive = artworkFetchPageKey(page) == artworkFetchPageKey(state)
                val cancelAction = if (isLive) onCancel else ({})
                val closeAction = if (isLive) onClose else ({})
                val confirmAction = if (isLive) onConfirm else ({})
                val dismissAction = if (isLive) onDismiss else ({})
                val liveAuthority = authority?.takeIf { isLive && isConfirm }
                val confirmFocused = focus?.scope == NewGameEnrichFocusScope &&
                    focus.focused == NewGameEnrichConfirmKey
                val dismissFocused = focus?.scope == NewGameEnrichFocusScope &&
                    focus.focused == NewGameEnrichDismissKey
                Column(
                    modifier = if (isLive) Modifier else Modifier.clearAndSetSemantics { },
                    verticalArrangement = Arrangement.spacedBy(MimirSpacing.md),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    when (page) {
                        is ArtworkFetchUi.ConfirmNewGames -> {
                            val copy = newGameEnrichCopy(page.count, page.hasAccount)
                            MimirChromeText(
                                copy.title,
                                style = MimirType.TitleCaps,
                                color = MimirColors.TextPrimary,
                            )
                            Text(
                                text = copy.body,
                                style = MimirType.RowSupport,
                                color = MimirColors.TextSecondary,
                                textAlign = TextAlign.Center,
                            )
                            ActionButton(
                                copy.primaryLabel,
                                primary = true,
                                onClick = confirmAction,
                                authority = liveAuthority,
                                focusKey = NewGameEnrichConfirmKey,
                                focused = confirmFocused,
                            )
                            ActionButton(
                                "Not now",
                                primary = false,
                                onClick = dismissAction,
                                authority = liveAuthority,
                                focusKey = NewGameEnrichDismissKey,
                                focused = dismissFocused,
                            )
                        }
                        is ArtworkFetchUi.Running -> {
                            MimirChromeText(kind.findingTitle, style = MimirType.TitleCaps, color = MimirColors.TextPrimary)
                            Text(
                                "Looking up your games on ScreenScraper. Keep this open until it’s done.",
                                style = MimirType.RowSupport, color = MimirColors.TextSecondary, textAlign = TextAlign.Center,
                            )
                            if (page.total > 0) {
                                ProgressBar(page.done.toFloat() / page.total)
                            } else {
                                IndeterminateProgressBar()
                            }
                            Text(
                                text = if (page.total > 0)
                                    "${page.done} of ${page.total} games · ${count(page.matched)} " +
                                        "updated"
                                else "Preparing…",
                                style = MimirType.RowSupport, color = MimirColors.TextMuted, textAlign = TextAlign.Center,
                            )
                            ActionButton("Cancel", primary = false, onClick = cancelAction)
                        }
                        is ArtworkFetchUi.Stopping -> {
                            MimirChromeText("Stopping…", style = MimirType.TitleCaps, color = MimirColors.TextPrimary)
                            Text(
                                "Finishing the current game, then stopping.",
                                style = MimirType.RowSupport, color = MimirColors.TextSecondary, textAlign = TextAlign.Center,
                            )
                        }
                        is ArtworkFetchUi.Done -> {
                            MimirChromeText(
                                if (page.writeBlocked) "Couldn’t save" else kind.doneTitle,
                                style = MimirType.TitleCaps, color = MimirColors.TextPrimary,
                            )
                            Text(
                                text = when {
                                    page.writeBlocked -> ArtworkWriteBlockedCopy
                                    page.matched > 0 ->
                                        "Updated artwork for ${count(page.matched)}. Existing artwork stays in place."
                                    else ->
                                        "No new artwork this time. Existing artwork stays in place."
                                },
                                style = MimirType.RowSupport, color = MimirColors.TextSecondary, textAlign = TextAlign.Center,
                            )
                            ActionButton("Close", primary = true, onClick = closeAction)
                        }
                        is ArtworkFetchUi.Cancelled -> {
                            MimirChromeText("Stopped", style = MimirType.TitleCaps, color = MimirColors.TextPrimary)
                            Text(
                                "Stopped after updating artwork for ${count(page.matched)}. What we found stays.",
                                style = MimirType.RowSupport, color = MimirColors.TextSecondary, textAlign = TextAlign.Center,
                            )
                            ActionButton("Close", primary = true, onClick = closeAction)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IndeterminateProgressBar() {
    val palette = LocalMimirPalette.current
    val modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
    if (mimirReducedMotion()) {
        Box(modifier.background(palette.emberSoft)) {
            Box(
                Modifier.fillMaxWidth(0.24f).fillMaxHeight().background(palette.accent),
            )
        }
    } else {
        MimirIndeterminateBar(
            modifier = modifier,
            trackColor = palette.emberSoft,
            sweepColor = palette.accent,
            reducedMotion = false,
        )
    }
}

@Composable
private fun ProgressBar(fraction: Float) {
    // Policy 2026-08-12 (commit
    // 1535e293 reverted): reverted back to Compose's default spring. The tween/MimirMotion swap was a
    // silent perceived-motion change (curve FAMILY, spring -> cubic-bezier) on a live, continuously
    // data-driven fill -- not an entry/exit transition the "existing MimirMotion tokens only" invariant
    // was written for -- made without a visual review.
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), label = "artwork-progress")
    val gold = MimirColors.Gold
    Box(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MimirColors.GoldGlow)
            // The fill is drawn with the spring's value read HERE (audit 2026-10-14, the P3 draw pass): a width modifier took it as a composition parameter, so the bar
            // recomposed on every frame of the spring. The spring itself is the policy above and is untouched; the fill is the same rounded rect.
            .drawBehind {
                drawRoundRect(
                    color = gold,
                    size = Size(size.width * f, size.height),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                )
            },
    )
}

/**
 * One answer on the artwork card: an etched chip inside a 48 dp hit box, never a filled or outlined rectangle.
 * [primary] is the card's main answer (the same dark plate, its rim and word in the accent), never a fill. The pad's focus is the shared
 * selection ring on the PLATE itself, with no inset and BEFORE the surface, so it is the plate's one edge; a button with no [focusKey] (Cancel while a fetch runs) takes no
 * focus and wears no ring. **the pill ban is universal** applies to filled buttons; the etched chip is the sanctioned control, never a filled shape.
 */
@Composable
private fun ActionButton(
    label: String,
    primary: Boolean,
    onClick: () -> Unit,
    authority: FocusAuthority? = null,
    focusKey: FocusKey? = null,
    focused: Boolean = false,
) {
    val palette = LocalMimirPalette.current
    val focusRegistration = if (authority != null && focusKey != null) {
        Modifier.mimirFocusable(authority, focusKey, NewGameEnrichFocusScope)
    } else {
        Modifier
    }
    val ring = if (authority != null && focusKey != null) {
        Modifier.mimirSelectionRing(focused = focused, identity = focusKey, shape = MimirRingShape.Pill, edgeFlush = true)
    } else {
        Modifier
    }
    Box(
        Modifier
            .heightIn(min = MimirSpacing.minTarget)
            .widthIn(min = MimirSpacing.minTarget)
            .then(focusRegistration)
            .mimirActivatable(label = label, authority = authority, key = focusKey, onActivate = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // The ring first, then the surface (the plate applies its modifier before its own): the ring hugs the plate, not the 48 dp box, and its wash is under the opaque metal.
        MimirEtchedChipPlate(
            modifier = Modifier.testTag(etchedChipPlateTag("artwork", label)).then(ring),
            focused = focused && authority != null && focusKey != null,
            primary = primary,
            palette = palette,
            edgeFlush = true,
        ) {
            MimirChromeText(label, style = MimirEtchedChipLabelStyle, color = palette.etchedChipInk(primary = primary), maxLines = 1, softWrap = false)
        }
    }
}
