package com.gruning.mimir.rewrite

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.ui.mimirReducedMotion
import com.gruning.mimir.rewrite.core.ui.mimirSwapTransform
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirEtchedChipLabelStyle
import com.gruning.mimir.rewrite.core.ui.MimirFocusTileRingReach
import com.gruning.mimir.rewrite.core.ui.MimirRingShape
import com.gruning.mimir.rewrite.core.ui.etchedChipInk
import com.gruning.mimir.rewrite.core.ui.mimirEtchedChipSurface
import com.gruning.mimir.rewrite.core.ui.mimirSelectionRing
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.input.mimirActivatable
import com.gruning.mimir.rewrite.core.input.mimirFocusable
import com.gruning.mimir.rewrite.core.ui.theme.MimirColors
import com.gruning.mimir.rewrite.core.ui.theme.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.theme.MimirType

/**
 * The single-game Enrich feedback prompt: a brief in-app card — NOT an Android toast — that shows a
 * non-cancelable progress spinner while the verified metadata package is refreshed, then a
 * brief result that auto-dismisses. When the gate for this row's provider is closed it instead explains
 * what Enrich needs — a ScreenScraper account for every platform ScreenScraper serves, a SteamGridDB key
 * for `pc_games` — with an affordance that goes to the right place for each (the
 * affordance must go where its own sentence points, never a hardcoded ScreenScraper login).
 *
 * Distinct from [ArtworkFetchOverlay] (the whole-library batch modal with a Cancel + progress bar).
 */
sealed interface GetArtUi {
    /** Fetching this one game — non-cancelable, indeterminate spinner. */
    data object Working : GetArtUi
    /** A cover was found + written — brief success, auto-dismisses (the tile repaints behind it). */
    data object Found : GetArtUi
    /** No cover matched — brief note, auto-dismisses; the game keeps its current art. */
    data object NotFound : GetArtUi
    /**
     * Nothing to look up: the row has no exact provider key and no searchable title, so no provider was
     * asked (today only a `pc_games` row with no `.steam` shortcut and a blank or tag-only title). Brief
     * note, auto-dismisses like [NotFound] — but it must never borrow NotFound's "the provider had no art"
     * sentence, because no lookup happened .
     */
    data object NotSearchable : GetArtUi
    /** The provider request failed; keep this visible until the user dismisses it. */
    data object Failed : GetArtUi
    /**
     * Not connected — Enrich needs a ScreenScraper account for every platform ScreenScraper serves, or
     * (`pc_games`) a SteamGridDB key; offer the affordance that fixes whichever one this row needs.
     */
    data object NeedsAccount : GetArtUi
    /** A saved account exists, but this build cannot open the provider gate. */
    data object ProviderUnavailable : GetArtUi
    /**
     * A `pc_games` press with no saved SteamGridDB key : ScreenScraper was asked for the logo alone, and the result
     * names the one thing the player can do about the rest. [logoSaved] says whether that logo landed. Unlike [Found] and [NotFound] it
     * stays until dismissed, because a sentence with a Settings path cannot be read in the 1.4 s those give; "Add key" opens the same key
     * editor [NeedsAccount] does for SteamGridDB.
     */
    data class PcKeyNote(val logoSaved: Boolean) : GetArtUi
}

/**
 * Stable page identity for [GetArtUi]. Every
 * [GetArtUi] member is already a `data object`, so instance identity is already stable across
 * recompositions — this enum exists for symmetry with [ArtworkFetchPageKey] and for the exhaustiveness
 * guarantee (a new [GetArtUi] page cannot be added without choosing its transition key).
 */
internal enum class GetArtPageKey {
    WORKING,
    FOUND,
    NOT_FOUND,
    NOT_SEARCHABLE,
    FAILED,
    NEEDS_ACCOUNT,
    PROVIDER_UNAVAILABLE,
    PC_KEY_NOTE,
}

internal fun getArtPageKey(state: GetArtUi): GetArtPageKey = when (state) {
    GetArtUi.Working -> GetArtPageKey.WORKING
    GetArtUi.Found -> GetArtPageKey.FOUND
    GetArtUi.NotFound -> GetArtPageKey.NOT_FOUND
    GetArtUi.NotSearchable -> GetArtPageKey.NOT_SEARCHABLE
    GetArtUi.Failed -> GetArtPageKey.FAILED
    GetArtUi.NeedsAccount -> GetArtPageKey.NEEDS_ACCOUNT
    GetArtUi.ProviderUnavailable -> GetArtPageKey.PROVIDER_UNAVAILABLE
    is GetArtUi.PcKeyNote -> GetArtPageKey.PC_KEY_NOTE
}

internal val GetArtFocusScope = FocusScope("artwork/get")
internal val GetArtDismissKey = FocusKey("artwork/get/dismiss")
internal val GetArtConnectKey = FocusKey("artwork/get/connect")

/**
 * Where the artwork page lands. OR-4 (product review table picks): "Connect to enrich" lands on Not
 * now, like every other state, so a stray A never opens a sign-in; Connect is one press away. The onboarding accounts
 * step keeps its Connect-first landing (2026-09-18 ruling), which this does not touch.
 */
@Suppress("UNUSED_PARAMETER")
internal fun getArtLandingKey(state: GetArtUi): FocusKey = GetArtDismissKey

internal fun mountGetArtFocusScope(authority: FocusAuthority) {
    if (authority.state.value.scope == GetArtFocusScope) return
    // RC-32 (input hazard): the scope remembers the last key a visit ended on, so after a visit that ended on Connect the card
    // seeded Connect first and snapped to Not now a frame later; an A inside that window opened sign-in. The landing is
    // [getArtLandingKey], so stamp it before the push and the first key to register is the safe one.
    authority.rememberFocusForScope(GetArtFocusScope, GetArtDismissKey)
    authority.pushScope(GetArtFocusScope)
}

internal fun unmountGetArtFocusScope(authority: FocusAuthority) {
    authority.discardScope(GetArtFocusScope)
}

internal fun dispatchGetArt(
    authority: FocusAuthority,
    state: GetArtUi?,
    provider: GetArtProvider,
    action: ControllerAction,
    onConnect: () -> Unit,
    onAddSteamGridDbKey: () -> Unit,
    onDismiss: () -> Unit,
): Boolean {
    if (action == ControllerAction.CANCEL || action == ControllerAction.SYSTEM_BACK) {
        onDismiss()
        return true
    }
    // The overlay coordinator may observe the card one frame before its composition pushes the scope.
    // Absorb that packet, but never move or activate a control in a scope that does not own the input.
    if (authority.state.value.scope != GetArtFocusScope) return true
    when (action) {
        ControllerAction.NAV_UP,
        ControllerAction.NAV_DOWN,
        ControllerAction.NAV_LEFT,
        ControllerAction.NAV_RIGHT,
        -> authority.moveFocus(action)
        ControllerAction.CONFIRM -> when (authority.state.value.focused) {
            GetArtDismissKey -> if (state is GetArtUi.Failed ||
                state is GetArtUi.NeedsAccount ||
                state is GetArtUi.ProviderUnavailable ||
                state is GetArtUi.PcKeyNote
            ) onDismiss()
            GetArtConnectKey -> if (state is GetArtUi.NeedsAccount) {
                when (provider) {
                    GetArtProvider.SCREENSCRAPER -> onConnect()
                    GetArtProvider.STEAMGRIDDB -> onAddSteamGridDbKey()
                }
            } else if (state is GetArtUi.PcKeyNote) {
                onAddSteamGridDbKey()
            }
            else -> Unit
        }
        else -> Unit
    }
    return true
}

/**
 * Which provider Enrich actually ran for this game — policy 2026-08-19
 *: the overlay must name the
 * provider that was really queried, never a hardcoded "ScreenScraper" for a platform ScreenScraper cannot
 * identify. [GetArtUi.Working], [GetArtUi.NotFound], [GetArtUi.NotSearchable], [GetArtUi.Found] and
 * [GetArtUi.NeedsAccount] have provider-specific copy; [GetArtUi.Failed] wording is provider-agnostic and
 * unchanged (it names no provider either way). Determined by the caller from the game's platform (R-24: `pc_games` is
 * SteamGridDB's outright) — the overlay itself never queries the database.
 *
 * Follow-up policy, same date: [GetArtUi.NeedsAccount]'s Connect pill must be driven by this SAME
 * switch, not a second one — see [needsAccountCta]. The original defect this closes: the sentence named
 * SteamGridDB but the pill beneath it unconditionally opened the ScreenScraper login flow anyway.
 */
enum class GetArtProvider { SCREENSCRAPER, STEAMGRIDDB }

/**
 * [GetArtUi.NeedsAccount]'s header, sentence, button label, and button action — all chosen by this ONE
 * [provider] switch, never a separate branch per field. The defect this ruling closes was exactly a
 * SteamGridDB sentence sitting over a ScreenScraper button (the Connect pill unconditionally called
 * `MimirArtworkState.connectFromGetArt` regardless of which provider the sentence named) — yoking every
 * field to the same decision makes that disagreement structurally impossible to reintroduce.
 */
internal data class NeedsAccountCta(
    val header: String,
    val body: String,
    val buttonLabel: String,
    val onClick: () -> Unit,
)

/**
 * @param onConnect the live (not-yet-gated-by-`isLive`) ScreenScraper connect action.
 * @param onAddSteamGridDbKey the live SteamGridDB key-entry action — the product's existing key editor
 *   (`SteamGridDbKeyEditModal.kt`, opened the same way the Settings row opens it), never a new screen.
 */
internal fun needsAccountCta(
    provider: GetArtProvider,
    onConnect: () -> Unit,
    onAddSteamGridDbKey: () -> Unit,
): NeedsAccountCta = when (provider) {
    // Byte-identical to the pre-existing header/sentence/button -- accurate and long-lived for every
    // platform ScreenScraper actually serves.
    GetArtProvider.SCREENSCRAPER -> NeedsAccountCta(
        header = "Connect to enrich",
        body = "Enrich needs a free ScreenScraper account for covers, logos, and game details. " +
            "Connect one in Settings › Appearance › Enrichment & artwork, then enrich any game.",
        buttonLabel = "Connect",
        onClick = onConnect,
    )
    // Policy verbatim, 2026-08-19: R-24 -- pc_games is SteamGridDB's platform outright,
    // ScreenScraper structurally cannot serve it (matches ROM files by hash and name; a GameNative
    // shortcut is not one), so its sentence must never show here. Follow-up ruling, same date: the
    // Connect pill must go where THIS sentence points -- the SteamGridDB key entry the product already
    // has (Settings › Appearance › Enrichment & artwork) -- not the ScreenScraper login flow, or the
    // words and the button disagree on the surface the first ruling had just made honest.
    GetArtProvider.STEAMGRIDDB -> NeedsAccountCta(
        header = "Add a key to enrich",
        body = "Enrich needs a SteamGridDB key. Add one in Settings › Appearance › Enrichment & artwork.",
        buttonLabel = "Add key",
        onClick = onAddSteamGridDbKey,
    )
}

/**
 * Where the SteamGridDB key is added, spelled as Settings labels it (Settings > Appearance > the "Enrichment & artwork" section, whose
 * SteamGridDB row opens the key editor). The same path every other keyless-PC sentence in the product uses.
 */
internal const val GetArtPcKeyWhereCopy = "Settings › Appearance › Enrichment & artwork"

internal data class PcKeyNoteCopy(val header: String, val body: String)

/**
 * The card a keyless `pc_games` Enrich ends on : the plain result, then that PC art needs a SteamGridDB key and where to
 * add one. ScreenScraper can serve a PC game's logo and nothing else, so once its logo has landed the sentence names covers and heroes, and a miss names logos and heroes.
 */
internal fun getArtPcKeyNoteCopy(logoSaved: Boolean): PcKeyNoteCopy = if (logoSaved) {
    PcKeyNoteCopy(
        header = "Game enriched",
        body = "Mimir saved a logo. PC covers and heroes need a SteamGridDB key. Add one in $GetArtPcKeyWhereCopy.",
    )
} else {
    PcKeyNoteCopy(
        header = "No match this time",
        body = "ScreenScraper had no logo for this game. PC logos and heroes need a SteamGridDB key. Add one in $GetArtPcKeyWhereCopy.",
    )
}

@Composable
fun GetArtOverlay(
    state: GetArtUi,
    provider: GetArtProvider,
    onConnect: () -> Unit,
    onAddSteamGridDbKey: () -> Unit,
    onDismiss: () -> Unit,
    authority: FocusAuthority? = null,
) {
    val focus by authority?.state?.collectAsState() ?: remember { androidx.compose.runtime.mutableStateOf(null) }
    val pageKey = getArtPageKey(state)
    val focusPage = state is GetArtUi.Failed || state is GetArtUi.NeedsAccount || state is GetArtUi.ProviderUnavailable ||
        state is GetArtUi.PcKeyNote
    DisposableEffect(authority, focusPage, pageKey) {
        if (authority != null && focusPage) mountGetArtFocusScope(authority)
        onDispose {
            if (authority != null && focusPage) unmountGetArtFocusScope(authority)
        }
    }
    LaunchedEffect(authority, focusPage, pageKey) {
        if (authority == null || !focusPage) return@LaunchedEffect
        // Wait until the real button nodes have measured and registered. Fixed virtual bounds here would
        // make the focus graph disagree with the rendered button geometry.
        withFrameNanos { }
        if (authority.state.value.scope != GetArtFocusScope) return@LaunchedEffect
        authority.requestFocus(getArtLandingKey(state))
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MimirColors.SurfaceBase.copy(alpha = 0.92f))
            // Absorb every touch — the scrim never dismisses (Working is non-cancelable; results auto-dismiss;
            // NeedsAccount dismisses only via its buttons / Back).
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .padding(MimirSpacing.xl)
                .clip(RoundedCornerShape(MimirSpacing.radiusCard))
                .background(MimirColors.SurfaceRaised)
                .padding(MimirSpacing.xl),
        ) {
            //  same grammar as ArtworkFetchOverlay:
            // one surface advancing, fade + resize on one clock, page identity via contentKey. See that
            // file's identical block for the full rationale; not re-derived here.
            val stepReducedMotion = mimirReducedMotion()
            AnimatedContent(
                targetState = state,
                contentKey = ::getArtPageKey,
                contentAlignment = Alignment.TopCenter,
                // the two steps no longer fade at once (two pages at half alpha mid-way, the 08-15
                // double exposure). The swap tier: the old step out, the new one in from the 130 ms cover, the
                // resize on the arrival's clock, unclipped (still never the default spring).
                transitionSpec = { mimirSwapTransform(stepReducedMotion) },
                label = "get-art-phase",
            ) { page ->
                // INVARIANT (spec §6): make the departing page input-inert -- see ArtworkFetchOverlay.kt's
                // identical guard for the full rationale (stale Failed/NeedsAccount taps must not fire).
                val isLive = getArtPageKey(page) == getArtPageKey(state)
                val dismissAction = if (isLive) onDismiss else ({})
                val connectAction = if (isLive) onConnect else ({})
                val addSteamGridDbKeyAction = if (isLive) onAddSteamGridDbKey else ({})
                val liveAuthority = authority?.takeIf { isLive }
                val liveFocus = if (isLive) focus else null
                Column(
                    modifier = if (isLive) Modifier else Modifier.clearAndSetSemantics { },
                    verticalArrangement = Arrangement.spacedBy(MimirSpacing.md),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    when (page) {
                        GetArtUi.Working -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(40.dp),
                                color = MimirColors.Gold,
                                strokeWidth = 3.dp,
                            )
                            MimirChromeText("Enriching game", style = MimirTypeScale.RunicEyebrow, color = MimirColors.Gold)
                            Text(
                                when (provider) {
                                    GetArtProvider.SCREENSCRAPER ->
                                        "Looking up cover art, logo, and game details on ScreenScraper."
                                    GetArtProvider.STEAMGRIDDB -> "Looking up art on SteamGridDB."
                                },
                                style = MimirType.RowSupport, color = MimirColors.TextSecondary, textAlign = TextAlign.Center,
                            )
                        }
                        GetArtUi.Found -> {
                            MimirChromeText("Game enriched", style = MimirTypeScale.RunicEyebrow, color = MimirColors.Gold)
                            Text(
                                when (provider) {
                                    // Byte-identical to the pre-existing sentence -- accurate and long-lived for
                                    // ScreenScraper, which verifies a game record (not just artwork) when it matches.
                                    GetArtProvider.SCREENSCRAPER ->
                                        "Mimir saved the verified details and any available artwork it could add."
                                    // SteamGridDB fetches art only, never a synopsis -- "verified details" would
                                    // overclaim for an art-only result (policy 2026-08-19, R-b).
                                    GetArtProvider.STEAMGRIDDB -> "Mimir saved what it could find for this game."
                                },
                                style = MimirType.RowSupport, color = MimirColors.TextSecondary, textAlign = TextAlign.Center,
                            )
                        }
                        GetArtUi.NotFound -> {
                            // A REAL miss only: the provider was asked (by exact id or by name) and had
                            // nothing acceptable. A lookup that never happened is NotSearchable, below.
                            MimirChromeText("No match this time", style = MimirTypeScale.RunicEyebrow, color = MimirColors.Gold)
                            Text(
                                when (provider) {
                                    GetArtProvider.SCREENSCRAPER ->
                                        "Mimir couldn’t verify this game on ScreenScraper. Nothing was changed."
                                    GetArtProvider.STEAMGRIDDB ->
                                        "SteamGridDB had no art for this game. Nothing was changed."
                                },
                                style = MimirType.RowSupport, color = MimirColors.TextSecondary, textAlign = TextAlign.Center,
                            )
                        }
                        GetArtUi.NotSearchable -> {
                            // the pre-fix card said "SteamGridDB had no art" for a
                            // pc_games row whose lookup had never left the device. Say what actually
                            // happened instead: there was nothing to ask with.
                            MimirChromeText("Nothing to look up", style = MimirTypeScale.RunicEyebrow, color = MimirColors.Gold)
                            Text(
                                when (provider) {
                                    GetArtProvider.SCREENSCRAPER ->
                                        "This game has no searchable title. Nothing was changed."
                                    GetArtProvider.STEAMGRIDDB ->
                                        "This PC game has no Steam shortcut and no searchable title. Nothing was changed."
                                },
                                style = MimirType.RowSupport, color = MimirColors.TextSecondary, textAlign = TextAlign.Center,
                            )
                        }
                        GetArtUi.Failed -> {
                            MimirChromeText(
                                "Couldn’t enrich this game",
                                style = MimirTypeScale.RunicEyebrow,
                                color = MimirColors.Gold,
                            )
                            Text(
                                "Check your connection, then choose Enrich again.",
                                style = MimirType.RowSupport,
                                color = MimirColors.TextSecondary,
                                textAlign = TextAlign.Center,
                            )
                            PromptPill(
                                "Back",
                                onClick = dismissAction,
                                authority = liveAuthority,
                                focusKey = GetArtDismissKey,
                                focused = liveFocus?.scope == GetArtFocusScope && liveFocus?.focused == GetArtDismissKey,
                                enabled = isLive,
                            )
                        }
                        GetArtUi.NeedsAccount -> {
                            // See needsAccountCta's own KDoc: header, sentence, button label, and button action all
                            // come from this ONE provider-keyed decision -- reusing the existing GetArtProvider
                            // mechanism that already drove this card's sentence, not a second branch.
                            val cta = needsAccountCta(provider, connectAction, addSteamGridDbKeyAction)
                            MimirChromeText(cta.header, style = MimirTypeScale.RunicEyebrow, color = MimirColors.Gold)
                            Text(
                                cta.body,
                                style = MimirType.RowSupport, color = MimirColors.TextSecondary, textAlign = TextAlign.Center,
                            )
                            // The two chips stand the ring's whole reach apart: at md the focused "Not now" halo ran into "Connect" (sweep 10-09, 50b at 1.3x).
                            Row(horizontalArrangement = Arrangement.spacedBy(MimirFocusTileRingReach)) {
                                PromptPill("Not now", onClick = dismissAction,
                                    authority = liveAuthority, focusKey = GetArtDismissKey,
                                    focused = liveFocus?.scope == GetArtFocusScope && liveFocus?.focused == GetArtDismissKey,
                                    enabled = isLive)
                                PromptPill(cta.buttonLabel, primary = true, onClick = cta.onClick,
                                    authority = liveAuthority, focusKey = GetArtConnectKey,
                                    focused = liveFocus?.scope == GetArtFocusScope && liveFocus?.focused == GetArtConnectKey,
                                    enabled = isLive)
                            }
                        }
                        is GetArtUi.PcKeyNote -> {
                            // The result and the key sentence come from one pure function (getArtPcKeyNoteCopy); the pills mirror NeedsAccount's.
                            val note = getArtPcKeyNoteCopy(page.logoSaved)
                            MimirChromeText(note.header, style = MimirTypeScale.RunicEyebrow, color = MimirColors.Gold)
                            Text(
                                note.body,
                                style = MimirType.RowSupport, color = MimirColors.TextSecondary, textAlign = TextAlign.Center,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(MimirFocusTileRingReach)) {
                                PromptPill("Not now", onClick = dismissAction,
                                    authority = liveAuthority, focusKey = GetArtDismissKey,
                                    focused = liveFocus?.scope == GetArtFocusScope && liveFocus?.focused == GetArtDismissKey,
                                    enabled = isLive)
                                PromptPill("Add key", primary = true, onClick = addSteamGridDbKeyAction,
                                    authority = liveAuthority, focusKey = GetArtConnectKey,
                                    focused = liveFocus?.scope == GetArtFocusScope && liveFocus?.focused == GetArtConnectKey,
                                    enabled = isLive)
                            }
                        }
                        GetArtUi.ProviderUnavailable -> {
                            MimirChromeText("ScreenScraper unavailable", style = MimirTypeScale.RunicEyebrow, color = MimirColors.Gold)
                            Text(ScreenScraperUnavailableCopy, style = MimirType.RowSupport, color = MimirColors.TextSecondary, textAlign = TextAlign.Center)
                            PromptPill("Not now",
                                onClick = dismissAction, authority = liveAuthority, focusKey = GetArtDismissKey,
                                focused = liveFocus?.scope == GetArtFocusScope && liveFocus?.focused == GetArtDismissKey,
                                enabled = isLive)
                        }
                    }
                }
            }
        }
    }
}

/**
 * One answer on the Get-art prompt: an etched chip (update; it was a transparent capsule with a 1 dp outline and a gold-tinted one with no outline) inside a 48 dp hit box.
 * The pad's focus is the shared selection ring on the PLATE itself, with no inset and BEFORE the surface, not a private 2 dp border; [primary] is the main answer's chip (the
 * same dark plate, its rim and word in the accent), never a fill.
 */
@Composable
private fun PromptPill(label: String, onClick: () -> Unit,
    authority: FocusAuthority? = null, focusKey: FocusKey? = null, focused: Boolean = false,
    enabled: Boolean = true, primary: Boolean = false) {
    val palette = LocalMimirPalette.current
    val focusRegistration = if (enabled && authority != null && focusKey != null) {
        Modifier.mimirFocusable(authority, focusKey, GetArtFocusScope)
    } else {
        Modifier
    }
    val ringed = enabled && authority != null && focusKey != null
    val ring = if (ringed && focusKey != null) {
        Modifier.mimirSelectionRing(focused = focused, identity = focusKey, shape = MimirRingShape.Pill, edgeFlush = true)
    } else {
        Modifier
    }
    Box(
        Modifier
            .heightIn(min = MimirSpacing.minTarget)
            .widthIn(min = MimirSpacing.minTarget)
            .then(focusRegistration)
            .mimirActivatable(label = label, enabled = enabled, authority = authority, key = focusKey, onActivate = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // The ring first, then the surface: the ring hugs the plate (not the 48 dp box around it) and its wash is under the opaque metal.
        Row(
            Modifier
                .testTag(etchedChipPlateTag("get-art", label))
                .then(ring)
                .mimirEtchedChipSurface(palette, focused = ringed && focused, primary = primary, edgeFlush = true)
                .padding(horizontal = MimirSpacing.md, vertical = MimirSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MimirChromeText(label, style = MimirEtchedChipLabelStyle, color = palette.etchedChipInk(primary = primary), maxLines = 1)
        }
    }
}
