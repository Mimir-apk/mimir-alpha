package com.gruning.mimir.rewrite

import android.content.Context
import androidx.compose.runtime.Composable
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.MimirNavFeedback
import com.gruning.mimir.rewrite.core.model.PlatformId

/**
 * Y on a focused LIBRARY platform tile opens this menu — Change icon art (live SGDB candidate
 * previews, user picks) + Revert to default. Mirrors the Home game-tile art flow (FUX-10) but for platform tiles on
 * the Library shelf, reusing [HomeTileMenuPhase] + the shared [TileArtMenuOverlay]. Pure reducer + plain state so
 * the flow is JVM-testable. SteamGridDB stays opt-in / key-gated / local-only (posture).
 */
internal const val PlatformArtMenuChangeArt = "Change icon art"
internal const val PlatformArtMenuRevert = "Revert to default"

internal fun platformArtMenuOptions(): List<String> = listOf(PlatformArtMenuChangeArt, PlatformArtMenuRevert)

internal fun newPlatformCustomEnrichPicker(
    platformId: PlatformId,
    label: String,
    catalog: EnrichSavedCatalog,
    catalogReady: Boolean,
    sessionId: String = "platform-icon-${platformId.value}-${System.nanoTime()}",
): HomeCustomEnrichState = newHomeCustomEnrichState(
    sessionId = sessionId,
    kind = EnrichMediaKind.ICON,
    cacheKey = SteamGridDbTileFetcher.platformKey(platformId),
    gameTitle = label,
    catalog = catalog,
    catalogReady = catalogReady,
    // CP-03: a platform's default face is Mimir's own console line-art, so the Default entry can show it.
    defaultArt = homeCustomEnrichDefaultArt(
        subject = HomeCustomEnrichSubject.PLATFORM,
        kind = EnrichMediaKind.ICON,
        title = label,
        platformIdValue = platformId.value,
        automaticArtUri = null,
    ),
).copy(
    queryDraft = SteamGridDbTileFetcher.platformArtSearchTitle(platformId.value, label),
)

/** Direct Y destination: the shared ICON picker, with legacy platform art exposed while migration settles. */
internal fun newPlatformArtMenuState(
    context: Context,
    platformId: PlatformId,
    label: String,
): PlatformArtMenuState {
    val cacheKey = SteamGridDbTileFetcher.platformKey(platformId)
    val legacy = SteamGridDbTileFetcher.tileIconFile(context, cacheKey)
        .takeIf { it.isFile && it.length() > 0L }
    val retainedUpload = CustomImageImportStore.retainedIconFile(context, cacheKey)
        .takeIf { it.isFile && it.length() > 0L }
    val activeWasUpload = SteamGridDbTileFetcher.uploadedPickActive(context, cacheKey)
    val durable = EnrichSavedStore.load(context, EnrichMediaKind.ICON, cacheKey)
    val display = enrichCatalogWithProvisionalLocal(
        catalog = durable,
        kind = EnrichMediaKind.ICON,
        legacyActive = legacy,
        retainedUpload = retainedUpload,
        activeWasUpload = activeWasUpload,
    )
    return PlatformArtMenuState(
        platformId = platformId,
        label = label,
        phase = HomeTileMenuPhase.CustomEnrichPicker(
            newPlatformCustomEnrichPicker(
                platformId = platformId,
                label = label,
                catalog = display,
                catalogReady = display == durable,
            ),
        ),
    )
}

internal data class PlatformArtMenuState(
    val platformId: PlatformId,
    val label: String,
    val phase: HomeTileMenuPhase = HomeTileMenuPhase.Menu,
    /** 0 = Change icon art, 1 = Revert to default. */
    val menuFocus: Int = 0,
)

/** Pure controller reducer. Returns the next state, or null when the menu should CLOSE. */
internal fun reducePlatformArtMenu(
    state: PlatformArtMenuState,
    action: ControllerAction,
    onLoadArt: () -> Unit,
    onApplyPick: (SteamGridDbTileFetcher.Candidate, Int) -> Unit,
    onRevert: () -> Unit,
): PlatformArtMenuState? {
    val options = platformArtMenuOptions()
    return when (val phase = state.phase) {
        is HomeTileMenuPhase.Menu -> when (action) {
            ControllerAction.NAV_UP, ControllerAction.NAV_DOWN -> {
                val delta = if (action == ControllerAction.NAV_UP) -1 else 1
                val next = (state.menuFocus + delta + options.size) % options.size
                // BM03: reducer focus is invisible to the FocusAuthority probe — record real moves.
                if (next != state.menuFocus) MimirNavFeedback.consumed()
                state.copy(menuFocus = next)
            }
            ControllerAction.CONFIRM -> when (options.getOrNull(state.menuFocus)) {
                // a refused revert HOLDS THE MENU OPEN WITH A NOTE, same as a
                // refused apply — so this can no longer return null (= close) before the suspend clear in
                // onRevert() even runs. Applying keeps the surface up; the coordinator's onRevert() is what
                // now decides close-on-success vs note-on-refusal, mirroring onApplyPick's honest twin.
                PlatformArtMenuRevert -> {
                    onRevert()
                    state.copy(phase = HomeTileMenuPhase.Applying)
                }
                else -> {
                    onLoadArt()
                    state.copy(phase = HomeTileMenuPhase.LoadingArt)
                }
            }
            ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK -> null
            else -> state
        }
        is HomeTileMenuPhase.LoadingArt, is HomeTileMenuPhase.Applying -> when (action) {
            ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK -> null
            else -> state
        }
        is HomeTileMenuPhase.CustomEnrichPicker -> state
        is HomeTileMenuPhase.ArtPick -> when (action) {
            ControllerAction.NAV_LEFT, ControllerAction.NAV_RIGHT -> {
                val size = phase.choices.size
                if (size == 0) {
                    state
                } else {
                    val delta = if (action == ControllerAction.NAV_LEFT) -1 else 1
                    val next = (phase.focusedIndex + delta + size) % size
                    if (next != phase.focusedIndex) MimirNavFeedback.consumed()
                    state.copy(phase = phase.copy(focusedIndex = next))
                }
            }
            ControllerAction.CONFIRM -> {
                // The platform menu has its own "Revert to default" ROW, so it never builds a
                // UseDefault slot; if one were ever added, treat it as the revert it names.
                when (val choice = phase.choices.getOrNull(phase.focusedIndex)) {
                    is HomeTileArtChoice.UseDefault -> {
                        // Unreachable today (comment above), kept consistent with the Menu-phase revert
                        // arm above so a future UseDefault slot doesn't reintroduce the close-before-result bug.
                        onRevert()
                        state.copy(phase = HomeTileMenuPhase.Applying)
                    }
                    is HomeTileArtChoice.Art -> {
                        val candidateIndex = phase.choices.take(phase.focusedIndex)
                            .count { it is HomeTileArtChoice.Art }
                        onApplyPick(choice.candidate, candidateIndex)
                        state.copy(phase = HomeTileMenuPhase.Applying)
                    }
                    is HomeTileArtChoice.UploadedImage,
                    HomeTileArtChoice.ImportImage,
                    -> state // game-only local-import slots are never built by the platform menu
                    null -> state
                }
            }
            ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK -> state.copy(phase = HomeTileMenuPhase.Menu)
            else -> state
        }
        // A dead end by design, mirroring the Home tile menu's own ProviderUnavailable handling
        // (HomeTileMenuOverlay.kt's reduceHomeTileMenu): the player is told the write refused and B
        // returns to the top-level menu — never stranded, never silently closed out from under them.
        is HomeTileMenuPhase.ProviderUnavailable -> when (action) {
            ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK -> state.copy(phase = HomeTileMenuPhase.Menu)
            else -> state
        }
        // Platforms never enter the game-only collection or Custom Hero phases; close defensively if
        // somehow reached rather than stranding the user in a modal with no grammar.
        is HomeTileMenuPhase.ConfirmRemove,
        is HomeTileMenuPhase.CollectionPick,
        is HomeTileMenuPhase.LoadingHero,
        is HomeTileMenuPhase.HeroPick,
        is HomeTileMenuPhase.LoadingClip,
        is HomeTileMenuPhase.ClipPick,
        -> null
    }
}

@Composable
internal fun PlatformTileMenuOverlay(
    state: PlatformArtMenuState,
    authority: FocusAuthority? = null,
    onMenuFocus: (Int) -> Unit,
    onActivateMenu: (Int) -> Unit,
    onPickFocus: (Int) -> Unit,
    onActivatePick: (Int) -> Unit,
    onCustomEnrichAction: (ControllerAction) -> Unit = {},
    onCustomEnrichQueryChange: (String) -> Unit = {},
    onCustomEnrichKeyboardDone: () -> Unit = {},
) = TileArtMenuOverlay(
    title = state.label,
    options = platformArtMenuOptions(),
    menuFocus = state.menuFocus,
    phase = state.phase,
    onMenuFocus = onMenuFocus,
    onActivateMenu = onActivateMenu,
    onPickFocus = onPickFocus,
    onActivatePick = onActivatePick,
    authority = authority,
    onCustomEnrichAction = onCustomEnrichAction,
    onCustomEnrichQueryChange = onCustomEnrichQueryChange,
    onCustomEnrichKeyboardDone = onCustomEnrichKeyboardDone,
)
