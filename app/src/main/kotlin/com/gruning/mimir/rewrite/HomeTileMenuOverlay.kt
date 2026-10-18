package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.ui.MimirPageMotion
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.CircularProgressIndicator
import com.gruning.mimir.rewrite.core.ui.theme.MimirColors
import java.io.File
import kotlinx.coroutines.flow.first
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.MimirNavFeedback
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.GlassMenuCapsule
import com.gruning.mimir.rewrite.core.ui.PlainMenuSeparator
import com.gruning.mimir.rewrite.core.ui.MimirBeforeFirstValidViewport
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.MimirIntentText
import com.gruning.mimir.rewrite.core.ui.MimirTextIntent
import com.gruning.mimir.rewrite.core.ui.MimirEtchedChipPlate
import com.gruning.mimir.rewrite.core.ui.MimirControlHintText
import com.gruning.mimir.rewrite.core.ui.MimirCopy
import com.gruning.mimir.rewrite.core.ui.MimirFocusTileRingReach
import com.gruning.mimir.rewrite.core.ui.MimirLiquidGlassPanel
import com.gruning.mimir.rewrite.core.ui.MimirRingShape
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.MimirTransitionInputGate
import com.gruning.mimir.rewrite.core.ui.glassRestingOutline
import com.gruning.mimir.rewrite.core.ui.mimirGlassScrimColor
import com.gruning.mimir.rewrite.core.ui.mimirSelectionRing
import com.gruning.mimir.rewrite.core.ui.mimirMeasuredViewport
import com.gruning.mimir.rewrite.core.ui.rememberMeasuredViewport
import com.gruning.mimir.rewrite.feature.library.TomeId
import com.gruning.mimir.rewrite.feature.library.HomeShelfRow
import com.gruning.mimir.rewrite.feature.library.VideoBackdrop
import com.gruning.mimir.rewrite.core.ui.mimirReducedMotion
import com.gruning.mimir.rewrite.core.ui.mimirRingSafeFade
import com.gruning.mimir.rewrite.core.ui.mimirSwapEnterSpec
import com.gruning.mimir.rewrite.core.ui.mimirSwapExitSpec
import com.gruning.mimir.rewrite.core.ui.mimirSwapFadeAlpha
import com.gruning.mimir.rewrite.core.ui.mimirSwapTransform

/**
 *  Y on a focused Home tile opens the Edit
 * menu. The option set and its exact order are now a function of the row's TYPE, and the whole thing
 * renders on the real Mimir liquid glass (UXP-03) instead of FUX-10b's opaque leather approximation.
 *
 * Pure reducer + plain state, so the entire grammar stays JVM-testable.
 */
internal sealed interface HomeTileMenuPhase {
    data object Menu : HomeTileMenuPhase

    /**
     * The one Customize media page : Customize media opens it straight from the Edit menu, and its Media
     * chip swaps the type (Icon art, Launch card, Hero art, Hero Motion) in place. The submenu that used
     * to list those types is retired. Platform art deliberately keeps the legacy phases below.
     */
    data class CustomEnrichPicker(val picker: HomeCustomEnrichState) : HomeTileMenuPhase

    data object ConfirmRemove : HomeTileMenuPhase

    data object LoadingArt : HomeTileMenuPhase
    data class ArtPick(
        val choices: List<HomeTileArtChoice>,
        val focusedIndex: Int = 0,
        val note: String? = null,
        /** Separate lane: strip scroll position remains stable while focus visits Upload. */
        val footerFocused: Boolean = false,
    ) : HomeTileMenuPhase

    data object Applying : HomeTileMenuPhase

    /** UXP-01 Custom Hero: the merged provider strip, or an honest unavailable/empty explanation. */
    data object LoadingHero : HomeTileMenuPhase
    data class HeroPick(
        val choices: List<HomeTileHeroChoice>,
        val focusedIndex: Int = 0,
        val note: String? = null,
        /** Separate lane: strip scroll position remains stable while focus visits Upload. */
        val footerFocused: Boolean = false,
    ) : HomeTileMenuPhase

    /** Pass 2: preview clips are a third, independent user choice—not a kind of Custom Hero. */
    data object LoadingClip : HomeTileMenuPhase
    data class ClipPick(
        val choices: List<HomeTileClipChoice>,
        val focusedIndex: Int = 0,
        val note: String? = null,
    ) : HomeTileMenuPhase

    data class ProviderUnavailable(val message: String) : HomeTileMenuPhase

    /**
     * Pick custom Tomes for this Favorite; CONFIRM flips membership in place (✓) optimistically and
     * relies on the async save's result to correct it. [note] carries a save-failure message when the
     * coordinator reverts a flip it could not persist — same shape as [ArtPick]/[HeroPick]/[ClipPick].
     */
    data class CollectionPick(
        val entries: List<HomeTileCollectionEntry>,
        val focusedIndex: Int = 0,
        val note: String? = null,
    ) : HomeTileMenuPhase
}

/**
 * Stable visual identities for the Edit menu's internal pages. Focus/index updates stay inside the same
 * identity and therefore never restart the dissolve; only a real page change animates.
 */
internal enum class HomeTileMenuPhaseTransitionKey {
    MENU,
    CUSTOM_ENRICH_PICKER,
    CONFIRM_REMOVE,
    COLLECTION_PICK,
    LOADING_ART,
    ART_PICK,
    APPLYING,
    LOADING_HERO,
    HERO_PICK,
    LOADING_CLIP,
    CLIP_PICK,
    PROVIDER_UNAVAILABLE,
}

/**
 * How deep a page sits: the menu, then its own pages (Customize media is one of them since the earlier update), then the legacy
 * pick pages that hang off the media page. A move to a shallower page is a return (B, Cancel, a finished pick) and
 * takes the swap tier's return, not its 130 ms lead (review of 89d626d38).
 */
internal fun homeTileMenuPhaseDepth(key: HomeTileMenuPhaseTransitionKey): Int = when (key) {
    HomeTileMenuPhaseTransitionKey.MENU -> 0
    HomeTileMenuPhaseTransitionKey.CUSTOM_ENRICH_PICKER,
    HomeTileMenuPhaseTransitionKey.CONFIRM_REMOVE,
    HomeTileMenuPhaseTransitionKey.COLLECTION_PICK,
    -> 1
    HomeTileMenuPhaseTransitionKey.LOADING_ART,
    HomeTileMenuPhaseTransitionKey.ART_PICK,
    HomeTileMenuPhaseTransitionKey.APPLYING,
    HomeTileMenuPhaseTransitionKey.LOADING_HERO,
    HomeTileMenuPhaseTransitionKey.HERO_PICK,
    HomeTileMenuPhaseTransitionKey.LOADING_CLIP,
    HomeTileMenuPhaseTransitionKey.CLIP_PICK,
    HomeTileMenuPhaseTransitionKey.PROVIDER_UNAVAILABLE,
    -> 2
}

/**
 * The tile menu's last shown page, so a page change can be told a return or a move deeper. Asked once per composition
 * with the current page; the answer holds until the page changes again.
 */
internal class HomeTileMenuSwapDirection(private var shown: HomeTileMenuPhaseTransitionKey) {
    private var returning = false

    fun returningTo(key: HomeTileMenuPhaseTransitionKey): Boolean {
        if (key != shown) {
            returning = homeTileMenuPhaseDepth(key) < homeTileMenuPhaseDepth(shown)
            shown = key
        }
        return returning
    }
}

internal fun homeTileMenuPhaseTransitionKey(phase: HomeTileMenuPhase): HomeTileMenuPhaseTransitionKey = when (phase) {
    HomeTileMenuPhase.Menu -> HomeTileMenuPhaseTransitionKey.MENU
    is HomeTileMenuPhase.CustomEnrichPicker -> HomeTileMenuPhaseTransitionKey.CUSTOM_ENRICH_PICKER
    HomeTileMenuPhase.ConfirmRemove -> HomeTileMenuPhaseTransitionKey.CONFIRM_REMOVE
    is HomeTileMenuPhase.CollectionPick -> HomeTileMenuPhaseTransitionKey.COLLECTION_PICK
    HomeTileMenuPhase.LoadingArt -> HomeTileMenuPhaseTransitionKey.LOADING_ART
    is HomeTileMenuPhase.ArtPick -> HomeTileMenuPhaseTransitionKey.ART_PICK
    HomeTileMenuPhase.Applying -> HomeTileMenuPhaseTransitionKey.APPLYING
    HomeTileMenuPhase.LoadingHero -> HomeTileMenuPhaseTransitionKey.LOADING_HERO
    is HomeTileMenuPhase.HeroPick -> HomeTileMenuPhaseTransitionKey.HERO_PICK
    HomeTileMenuPhase.LoadingClip -> HomeTileMenuPhaseTransitionKey.LOADING_CLIP
    is HomeTileMenuPhase.ClipPick -> HomeTileMenuPhaseTransitionKey.CLIP_PICK
    is HomeTileMenuPhase.ProviderUnavailable -> HomeTileMenuPhaseTransitionKey.PROVIDER_UNAVAILABLE
}

/**
 * A slot in the Icon art strip. [UseDefault] is the canonical `Default` clear action, modelled as a
 * first-class choice rather than a magic index so the reducer and the tests
 * cannot drift about which slot means "reset".
 */
internal sealed interface HomeTileArtChoice {
    data object UseDefault : HomeTileArtChoice
    data class UploadedImage(val previewPath: String) : HomeTileArtChoice
    data object ImportImage : HomeTileArtChoice
    data class Art(val candidate: SteamGridDbTileFetcher.Candidate) : HomeTileArtChoice
}

/** A slot in the Custom Hero strip, with the same explicit reset slot. */
internal sealed interface HomeTileHeroChoice {
    data object UseDefault : HomeTileHeroChoice
    data class UploadedImage(val previewPath: String) : HomeTileHeroChoice
    data object ImportImage : HomeTileHeroChoice
    data class Hero(val candidate: CustomHeroCandidate) : HomeTileHeroChoice
}

internal sealed interface HomeTileClipChoice {
    data object UseDefault : HomeTileClipChoice
    data class Clip(val candidate: CustomHeroCandidate) : HomeTileClipChoice
}

internal data class HomeTileCollectionEntry(
    val name: String,
    val isMember: Boolean,
    val id: TomeId.Custom,
)

internal enum class HomeTileMenuEntryPoint { EDIT, LIBRARY_HERO }

internal data class HomeTileMenuState(
    val row: HomeShelfRow,
    val removable: Boolean,
    val phase: HomeTileMenuPhase = HomeTileMenuPhase.Menu,
    val menuFocus: Int = 0,
    /**
     * The (kind, state) the Customize media page was last on , so reopening it returns to the type the player
     * was editing. Null until the page has been opened: it then opens on the entry point's own default.
     */
    val customEnrichFocus: CustomMediaSelection? = null,
    /** Direct Library Hero opens carry their full resolver row for ScreenScraper's platform-aware query. */
    val sourceGame: GameRow? = null,
    val entryPoint: HomeTileMenuEntryPoint = HomeTileMenuEntryPoint.EDIT,
    /** Non-null only when Edit was opened from inside this custom Tome. */
    val activeCustomTomeId: TomeId.Custom? = null,
    /**
     * true only for a
     * verified-partial library game (set by `libraryHeroPickerMenuState`), so the top-level menu also
     * offers Enrich. Deliberately a plain data field rather than an entry-point branch in
     * [homeTileMenuActions] — the top-level list stays a function of state, preserving S-2's "one
     * shared list, not two divergent ones" for Enrich even though only the library-hero entry point ever sets it true today.
     * The one row that does follow the entry point is Download music for this game, which the compact media entry omits.
     */
    val offerEnrich: Boolean = false,
    /**
     * The Library's menus carry favourite : Add favorite for a row that is not one, Remove favorite ([removable]) for one that is. Set by the Library's game menu
     * and by the menu an Android game's Library card opens. Left false by Home's Edit menu (opened on favourites only), the Android shelf's own Y menu and the compact media
     * entry, none of which ever carried the row. An entry point could not say this alone: the Android shelf's menu and the Android card's are the same entry point.
     */
    val offerFavorite: Boolean = false,
    /**
     * The remove-favourite confirm's lit answer: false = Remove (A removes), true = Cancel (A dismisses). LEFT and RIGHT move between the two
     * and nothing else dismisses the dialog but Cancel and B (reported 10-09: RIGHT closed it). It opens on Remove.
     */
    val confirmCancelFocused: Boolean = false,
)

/** Touch and controller must update the same focused candidate for every picker phase. */
internal fun homeTileMenuWithFocusedPick(state: HomeTileMenuState, index: Int): HomeTileMenuState =
    when (val phase = state.phase) {
        is HomeTileMenuPhase.ArtPick -> state.copy(
            phase = phase.copy(focusedIndex = index, footerFocused = false),
        )
        is HomeTileMenuPhase.CollectionPick -> state.copy(phase = phase.copy(focusedIndex = index))
        is HomeTileMenuPhase.HeroPick -> state.copy(
            phase = phase.copy(focusedIndex = index, footerFocused = false),
        )
        is HomeTileMenuPhase.ClipPick -> state.copy(phase = phase.copy(focusedIndex = index))
        // A tap on Remove lights Remove first, so a Cancel the pad had lit cannot turn that tap into a dismissal.
        is HomeTileMenuPhase.ConfirmRemove -> state.copy(confirmCancelFocused = false)
        else -> state
    }

/** Touch and controller share one reducer-owned footer lane; no sentinel leaks into strip scroll math. */
internal fun homeTileMenuWithFooterFocused(state: HomeTileMenuState): HomeTileMenuState =
    when (val phase = state.phase) {
        is HomeTileMenuPhase.ArtPick -> state.copy(phase = phase.copy(footerFocused = true))
        is HomeTileMenuPhase.HeroPick -> state.copy(phase = phase.copy(footerFocused = true))
        else -> state
    }

internal fun homeArtStripIndices(choices: List<HomeTileArtChoice>): List<Int> =
    choices.indices.filter { choices[it] != HomeTileArtChoice.ImportImage }

internal fun homeHeroStripIndices(choices: List<HomeTileHeroChoice>): List<Int> =
    choices.indices.filter { choices[it] != HomeTileHeroChoice.ImportImage }

/**
 * The exact approved copy. These strings are product truth so they live in
 * one enum rather than being retyped at call sites.
 */
internal enum class HomeTileMenuAction(val label: String) {
    // reuses the exact word GamelistMediaAction.ENRICH already renders on the
    // seat's own direct-tap state — not new vocabulary, the same approved term reached from inside the
    // menu now too. Duplicated as a literal rather than a shared constant because GamelistMediaAction is
    // feature-library-internal and this enum lives in :app; same drift risk this codebase already accepted
    // once for NoBindingAssigned before centralizing it — flagged here rather than silently repeated.
    ENRICH("Enrich"),
    CUSTOM_ENRICH(MimirCopy.CustomizeMedia),
    // The SHELF's face, one "Icon art" type. It takes a square, a box, the
    // physical article or an upload, and the tile's shape follows the pick.
    CUSTOM_ART_ICON(MimirCopy.IconArt),
    // The Launch card's face, its own state (C1/N-1): the face is one medium with two wirings, not two media, and naming it
    // by the picture made the menu unpredictable.
    CUSTOM_FACE_LAUNCH_CARD(MimirCopy.FaceLaunchCard),
    CUSTOM_HERO(MimirCopy.HeroArt),
    CUSTOM_PREVIEW_CLIP(MimirCopy.HeroMotion),
    /**
     * Settings › Music: the launch sound, fetched for THIS game only. Since the earlier update a
     * top-level row right after Customize media (the page it used to sit in is one picker now, with no list of lanes),
     * for emulated games on Home Edit and from the Library hero menu , because it exists only where a
     * GameId does. The compact media entry has no menu and never shows it.
     */
    DOWNLOAD_GAME_MUSIC("Download music for this game"),
    ADD_TO_COLLECTION("Add to Tome"),
    REMOVE_FROM_COLLECTION("Remove from Tome"),
    ACHIEVEMENTS("Achievements"),
    /**
     * The Library menu's row for a game that is not a favourite yet, in the slot [REMOVE_FAVORITE] takes for one that is (favourite lives in the Y menu on both
     * doors, and the launch card's own row no longer carries a Favorite button). Proposed copy pending design review: it is the mirror of the existing row, kept in this one
     * constant, so a future copy decision changes one line. Home's own menu never shows it, because every game on Home is one.
     */
    ADD_FAVORITE("Add favorite"),
    REMOVE_FAVORITE("Remove favorite"),
}

/** Stable source name retained while the user-facing vocabulary is Tome. */
internal val HomeTileMenuAddToCollection: String = HomeTileMenuAction.ADD_TO_COLLECTION.label

/**
 * The per-type menu ordering.
 *
 *  Emulated:            Customize Media · Add to Tome · Achievements · Remove Favorite
 *                       (Home Edit and the Library hero menu add Download music for this game right after
 *                       Customize Media, the earlier update; the compact media entry keeps its own shape; the Library hero
 *                       menu of a game that is not a favourite shows Add favorite in the last slot instead)
 *  Android game:        Customize Media · Add to Tome · Remove Favorite
 *  Android app:         Customize Media only (defensive; Apps never enter Favorite Home)
 *
 * `Achievements` stays emulated-only. Android package rows expose Hero inside Customize Media rather
 * than adding another top-level action. `Remove Favorite` is always last and is omitted entirely when
 * the row is not a durable Favorite.
 *
 * when
 * [HomeTileMenuState.offerEnrich] is set, `Enrich` leads ahead of `Customize Media`.
 * This is gated on the plain data field alone,
 * not on entry point. S-2's "one shared list, not two divergent ones" holds for every row but one: Download music for this game
 * is shown by the Home Edit and Library hero entry points and not by the compact media entry (the earlier update).
 */
internal fun homeTileMenuActions(state: HomeTileMenuState): List<HomeTileMenuAction> {
    val emulated = state.row.itemId is HomeShelfItemId.Emulated
    val androidApp = state.row.itemId is HomeShelfItemId.AndroidApp
    return buildList {
        if (state.offerEnrich) add(HomeTileMenuAction.ENRICH)
        add(HomeTileMenuAction.CUSTOM_ENRICH)
        // Home Edit and the Library hero menu , emulated games only: the row needs a GameId, and the Library
        // row always carries one.
        if (emulated) {
            add(HomeTileMenuAction.DOWNLOAD_GAME_MUSIC)
        }
        if (!androidApp) {
            add(
                if (state.activeCustomTomeId != null) HomeTileMenuAction.REMOVE_FROM_COLLECTION
                else HomeTileMenuAction.ADD_TO_COLLECTION,
            )
        }
        if (emulated) add(HomeTileMenuAction.ACHIEVEMENTS)
        if (!androidApp && state.removable) {
            add(HomeTileMenuAction.REMOVE_FAVORITE)
        } else if (!androidApp && state.offerFavorite) {
            // The Library door's favourite is in this menu , for an emulated game and an Android game alike (an Android app cannot be a favourite); a row that is
            // already one is [removable] and shows Remove above.
            add(HomeTileMenuAction.ADD_FAVORITE)
        }
    }
}

internal fun homeTileMenuOptions(state: HomeTileMenuState): List<String> =
    homeTileMenuActions(state).map { it.label }

internal fun homeTileCustomEnrichActions(state: HomeTileMenuState): List<HomeTileMenuAction> = buildList {
    // The Library shortcut offers the two HERO media and nothing else. It was Hero-art-only, which is why the
    // gamelist could never reach a clip at all.
    //
    // Still deliberately NARROWER than Home Edit where it matters: an Android package's art lives only there. This entry point
    // is reached from the gamelist's hero, so it offers what a game's hero and shelf can BE. Home Edit remains the place that
    // owns the broader package picker, and widening this to match it would make the same menu mean two things.
    if (state.entryPoint == HomeTileMenuEntryPoint.LIBRARY_HERO) {
        // Every menu that exposes media lanes includes a Cover lane.
        // This entry point was deliberately narrowed on 2026-08-14 to the two HERO media, on the reasoning
        // that it is reached from the gamelist's hero so it offers what the hero can BE. That narrowing is
        // OVERTURNED for the shelf's face specifically, and the reason is not symmetry -- it is that the gamelist
        // is where he LOOKS at covers, so it is the least sensible place to be unable to change one. Since 2026-10-10
        // that lane is "Icon art", which takes the cover and the square alike.
        add(HomeTileMenuAction.CUSTOM_ART_ICON)
        add(HomeTileMenuAction.CUSTOM_FACE_LAUNCH_CARD)
        add(HomeTileMenuAction.CUSTOM_HERO)
        // Emulated only, matching the Home branch below rather than restating a looser rule: a clip is
        // stored against a GameId (CustomHeroStore.previewClipUri), which an Android game/app has none of.
        if (state.row.itemId is HomeShelfItemId.Emulated) add(HomeTileMenuAction.CUSTOM_PREVIEW_CLIP)
        return@buildList
    }
    add(HomeTileMenuAction.CUSTOM_ART_ICON)
    when (state.row.itemId) {
        is HomeShelfItemId.Emulated -> {
            add(HomeTileMenuAction.CUSTOM_FACE_LAUNCH_CARD)
            add(HomeTileMenuAction.CUSTOM_HERO)
            add(HomeTileMenuAction.CUSTOM_PREVIEW_CLIP)
        }
        is HomeShelfItemId.AndroidGame,
        is HomeShelfItemId.AndroidApp,
        -> {
            add(HomeTileMenuAction.CUSTOM_FACE_LAUNCH_CARD)
            add(HomeTileMenuAction.CUSTOM_HERO)
        }
    }
}

internal fun homeTileCustomEnrichOptions(state: HomeTileMenuState): List<String> =
    homeTileCustomEnrichActions(state).map { it.label }

internal data class HomeTileMenuGeometry(
    val compactLayout: Boolean,
    val compactTypography: Boolean,
)

/**
 * Edit/picker geometry is constraint-owned. RP5 and Odin-class 16:9 handhelds share the compact
 * type ramp; narrow 1:1/3:2/4:3 surfaces, vertical handhelds, and enlarged system text do too.
 * This avoids a density breakpoint changing menu scale between physically similar devices.
 */
internal fun homeTileMenuGeometry(
    widthDp: Float,
    heightDp: Float,
    fontScale: Float,
): HomeTileMenuGeometry {
    require(widthDp.isFinite() && heightDp.isFinite() && fontScale.isFinite())
    require(widthDp > 0f && heightDp > 0f && fontScale > 0f)
    val compactLayout = widthDp < 480f || heightDp < 480f
    return HomeTileMenuGeometry(
        compactLayout = compactLayout,
        compactTypography = compactLayout || heightDp <= 520f || fontScale > 1.1f,
    )
}

/**
 * Conservative vertical budget for Edit → Customize media. The square presentation removes only the
 * redundant support sentence; it keeps the dynamic title, runic header, every 48dp action target,
 * and the truthful controller legend at the live font scale.
 */
internal fun homeTileCustomEnrichRequiredHeightDp(
    optionCount: Int,
    compactSquare: Boolean,
    fontScale: Float = 1f,
): Int {
    val outerEdge = if (compactSquare) MimirSpacing.md.value.toInt() else MimirSpacing.xl.value.toInt()
    val contentEdge = if (compactSquare) MimirSpacing.sm.value.toInt() else MimirSpacing.xl.value.toInt()
    val gap = if (compactSquare) MimirSpacing.xs.value.toInt() else MimirSpacing.md.value.toInt()
    val titleSp = if (compactSquare) 15 else 20
    val headerSp = if (compactSquare) 9 else 10
    val hintSp = if (compactSquare) 9 else 10
    val supportCount = if (compactSquare) 0 else 1
    val phaseItemCount = 1 + optionCount + supportCount + 1
    fun line(sp: Int): Int = kotlin.math.ceil(sp * fontScale * 1.4f).toInt()
    return (outerEdge + contentEdge) * 2 +
        line(titleSp) + gap +
        line(headerSp) + optionCount * MimirSpacing.minTarget.value.toInt() + supportCount * line(13) + line(hintSp) +
        (phaseItemCount - 1).coerceAtLeast(0) * gap
}

/** Pure controller reducer. Returns the next state, or null when the menu should CLOSE. */
internal fun reduceHomeTileMenu(
    state: HomeTileMenuState,
    action: ControllerAction,
    onRemove: (HomeShelfItemId) -> Unit,
    onLoadArt: () -> Unit,
    onApplyPick: (SteamGridDbTileFetcher.Candidate, Int) -> Unit,
    collectionsFor: () -> List<HomeTileCollectionEntry> = { emptyList() },
    onToggleCollection: (TomeId.Custom) -> Unit = {},
    onRemoveFromTome: (TomeId.Custom) -> Unit = {},
    onLoadHero: () -> Unit = {},
    onApplyHero: (CustomHeroCandidate) -> Unit = {},
    onLoadClip: () -> Unit = {},
    onApplyClip: (CustomHeroCandidate) -> Unit = {},
    onResetArt: () -> Unit = {},
    onResetHero: () -> Unit = {},
    onResetClip: () -> Unit = {},
    onOpenAchievements: () -> Unit = {},
    onImportArt: () -> Unit = {},
    onApplyUploadedArt: () -> Unit = {},
    onImportHero: () -> Unit = {},
    onApplyUploadedHero: () -> Unit = {},
    /**
     * Opens a picker for one medium in one STATE. The state is only ever non-SHELF for the face: the tile
     * icon, Hero art and Hero Motion each have one rendered slot and therefore one wiring (M-2).
     */
    onOpenCustomEnrich: (EnrichMediaKind, MediaState) -> HomeCustomEnrichState? = { _, _ -> null },
    /**
     * fires the same Enrich fetch the seat's own direct tap uses
     * (`artwork.state.fetchOne`), idempotently. Closes the menu on selection (returns null) exactly like
     * [onOpenAchievements] — the caller's own [GetArtUi] overlay takes over from there, unchanged.
     */
    onEnrich: () -> Unit = {},
    /**
     * Settings › Music (2026-09-30): the engine's per-game launch-sound fetch. Closes the menu on selection
     * like [onEnrich]; the run reports on Main's message rail (start, result, or why it did not start), so this
     * lane fires and leaves. The Settings › Music row still carries the whole-library batch's progress.
     */
    onDownloadGameMusic: () -> Unit = {},
    /**
     * The Library menu's Add favorite : fires the membership write for the menu's game and closes the menu, with no confirm (nothing is lost). Last in the list,
     * after the parameters the other callers pass by position.
     */
    onAddFavorite: (HomeShelfItemId) -> Unit = {},
): HomeTileMenuState? {
    val actions = homeTileMenuActions(state)
    return when (val phase = state.phase) {
        is HomeTileMenuPhase.Menu -> when (action) {
            ControllerAction.NAV_UP, ControllerAction.NAV_DOWN -> {
                val delta = if (action == ControllerAction.NAV_UP) -1 else 1
                val next = (state.menuFocus + delta).coerceIn(0, actions.lastIndex)
                // BM03: reducer focus is invisible to the FocusAuthority probe — record real moves
                // for the router's tick gate (a single-option wrap lands in place: silent).
                if (next != state.menuFocus) MimirNavFeedback.consumed()
                state.copy(menuFocus = next)
            }
            ControllerAction.CONFIRM -> when (actions.getOrNull(state.menuFocus)) {
                HomeTileMenuAction.REMOVE_FAVORITE -> {
                    state.copy(phase = HomeTileMenuPhase.ConfirmRemove, confirmCancelFocused = false)
                }
                // Adding needs no confirm (nothing is lost): it fires and closes, like Achievements and Enrich; the message rail answers ("Favorite added").
                HomeTileMenuAction.ADD_FAVORITE -> {
                    onAddFavorite(state.row.itemId)
                    null
                }
                HomeTileMenuAction.ADD_TO_COLLECTION ->
                    state.copy(phase = HomeTileMenuPhase.CollectionPick(collectionsFor()))
                HomeTileMenuAction.REMOVE_FROM_COLLECTION -> {
                    state.activeCustomTomeId?.let(onRemoveFromTome)
                    null
                }
                HomeTileMenuAction.ACHIEVEMENTS -> {
                    // Opens the existing read-only RA card; the Select shortcut is untouched.
                    onOpenAchievements()
                    null
                }
                HomeTileMenuAction.ENRICH -> {
                    // same idempotent fetch the seat's own direct tap uses.
                    // Closes the menu, mirroring ACHIEVEMENTS above; the caller's GetArtUi overlay owns
                    // the rest of the flow from here, exactly as it does off the seat.
                    onEnrich()
                    null
                }
                // Customize media IS the page now, opened straight from here on the type the entry point
                // (or the last visit) names. No submenu stands between.
                HomeTileMenuAction.CUSTOM_ENRICH -> openCustomMedia(state, onOpenCustomEnrich) ?: state
                // Fires the engine's per-game fetch and closes, like ENRICH. The menu shows nothing itself : the
                // run answers on Main's message rail, over Home or the Library, whichever asked.
                HomeTileMenuAction.DOWNLOAD_GAME_MUSIC -> {
                    onDownloadGameMusic()
                    null
                }
                // The media lanes are the Media chip's values, never rows of this menu, so CONFIRM on one here is
                // a no-op. Listed rather than folded into an `else` so adding a lane forces this decision instead
                // of inheriting it.
                HomeTileMenuAction.CUSTOM_ART_ICON,
                HomeTileMenuAction.CUSTOM_FACE_LAUNCH_CARD,
                HomeTileMenuAction.CUSTOM_HERO,
                HomeTileMenuAction.CUSTOM_PREVIEW_CLIP,
                -> state
                null -> state
            }
            ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK -> null
            else -> state
        }
        // LEFT and RIGHT move the lit answer between Remove and Cancel (clamped at each end); A answers the lit one; only Cancel and B dismiss.
        is HomeTileMenuPhase.ConfirmRemove -> when (action) {
            ControllerAction.CONFIRM ->
                if (state.confirmCancelFocused) {
                    state.copy(phase = HomeTileMenuPhase.Menu, confirmCancelFocused = false)
                } else {
                    onRemove(state.row.itemId)
                    null
                }
            ControllerAction.CANCEL,
            ControllerAction.SYSTEM_BACK,
            -> state.copy(phase = HomeTileMenuPhase.Menu, confirmCancelFocused = false)
            ControllerAction.NAV_RIGHT ->
                if (state.confirmCancelFocused) {
                    state
                } else {
                    MimirNavFeedback.consumed()
                    state.copy(confirmCancelFocused = true)
                }
            ControllerAction.NAV_LEFT ->
                if (state.confirmCancelFocused) {
                    MimirNavFeedback.consumed()
                    state.copy(confirmCancelFocused = false)
                } else {
                    state
                }
            else -> state
        }
        is HomeTileMenuPhase.CollectionPick -> when (action) {
            ControllerAction.NAV_UP, ControllerAction.NAV_DOWN -> {
                val size = phase.entries.size
                if (size == 0) {
                    state
                } else {
                    val delta = if (action == ControllerAction.NAV_UP) -1 else 1
                    val next = (phase.focusedIndex + delta + size) % size
                    if (next != phase.focusedIndex) MimirNavFeedback.consumed()
                    state.copy(phase = phase.copy(focusedIndex = next))
                }
            }
            ControllerAction.CONFIRM -> {
                val entry = phase.entries.getOrNull(phase.focusedIndex) ?: return state
                onToggleCollection(entry.id)
                // Optimistic flip: instant response for the common (save succeeds) case. The
                // coordinator's onToggleCollection reverts this specific entry and sets [note] if the
                // async save comes back refused. A fresh
                // attempt clears any stale note from a previous refusal.
                state.copy(
                    phase = phase.copy(
                        entries = phase.entries.map {
                            if (it.name == entry.name) it.copy(isMember = !it.isMember) else it
                        },
                        note = null,
                    ),
                )
            }
            ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK ->
                state.copy(phase = HomeTileMenuPhase.Menu)
            else -> state
        }
        is HomeTileMenuPhase.LoadingArt,
        is HomeTileMenuPhase.LoadingHero,
        is HomeTileMenuPhase.LoadingClip,
        -> when (action) {
            ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK ->
                returnToCustomMedia(state, onOpenCustomEnrich)
            else -> state
        }
        is HomeTileMenuPhase.Applying -> when (action) {
            ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK -> null
            else -> state
        }
        is HomeTileMenuPhase.CustomEnrichPicker -> state
        // A dead end by design: the user is told which connection is missing and B returns to the menu,
        // rather than being left on a blank strip.
        is HomeTileMenuPhase.ProviderUnavailable -> when (action) {
            ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK ->
                returnToCustomMedia(state, onOpenCustomEnrich)
            else -> state
        }
        is HomeTileMenuPhase.ArtPick -> when (action) {
            ControllerAction.NAV_LEFT, ControllerAction.NAV_RIGHT -> {
                val stripIndices = homeArtStripIndices(phase.choices)
                if (phase.footerFocused || stripIndices.isEmpty()) {
                    state
                } else {
                    val delta = if (action == ControllerAction.NAV_LEFT) -1 else 1
                    val current = stripIndices.indexOf(phase.focusedIndex).coerceAtLeast(0)
                    val next = stripIndices[(current + delta + stripIndices.size) % stripIndices.size]
                    if (next != phase.focusedIndex) MimirNavFeedback.consumed()
                    state.copy(phase = phase.copy(focusedIndex = next))
                }
            }
            ControllerAction.NAV_DOWN -> if (
                !phase.footerFocused && phase.choices.any { it == HomeTileArtChoice.ImportImage }
            ) {
                MimirNavFeedback.consumed()
                state.copy(phase = phase.copy(footerFocused = true))
            } else {
                state
            }
            ControllerAction.NAV_UP -> if (phase.footerFocused) {
                MimirNavFeedback.consumed()
                state.copy(phase = phase.copy(footerFocused = false))
            } else {
                state
            }
            ControllerAction.CONFIRM -> if (phase.footerFocused) {
                onImportArt()
                state
            } else when (val choice = phase.choices.getOrNull(phase.focusedIndex)) {
                is HomeTileArtChoice.UseDefault -> {
                    // A refused reset HOLDS THE MENU OPEN WITH A NOTE,
                    // mirroring the Art/UploadedImage arms below. This must not return null (= close)
                    // before onResetArt()'s suspend clear even runs, or the failure has no surface left
                    // to write the note to — the exact bug that shipped in the platform icon-art menu's
                    // revert arm (`PlatformArtMenuOverlay.kt`'s reducer, now repaired).
                    onResetArt()
                    state.copy(phase = HomeTileMenuPhase.Applying)
                }
                is HomeTileArtChoice.UploadedImage -> {
                    onApplyUploadedArt()
                    state.copy(phase = HomeTileMenuPhase.Applying)
                }
                HomeTileArtChoice.ImportImage -> {
                    onImportArt()
                    state
                }
                is HomeTileArtChoice.Art -> {
                    // The index the applier needs is the index among REAL candidates, not among slots —
                    // the reset slot would otherwise shift every pick by one.
                    val candidateIndex = phase.choices.take(phase.focusedIndex)
                        .count { it is HomeTileArtChoice.Art }
                    onApplyPick(choice.candidate, candidateIndex)
                    state.copy(phase = HomeTileMenuPhase.Applying)
                }
                null -> state
            }
            ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK ->
                returnToCustomMedia(state, onOpenCustomEnrich)
            else -> state
        }
        is HomeTileMenuPhase.HeroPick -> when (action) {
            ControllerAction.NAV_LEFT, ControllerAction.NAV_RIGHT -> {
                val stripIndices = homeHeroStripIndices(phase.choices)
                if (phase.footerFocused || stripIndices.isEmpty()) {
                    state
                } else {
                    val delta = if (action == ControllerAction.NAV_LEFT) -1 else 1
                    val current = stripIndices.indexOf(phase.focusedIndex).coerceAtLeast(0)
                    val next = stripIndices[(current + delta + stripIndices.size) % stripIndices.size]
                    if (next != phase.focusedIndex) MimirNavFeedback.consumed()
                    state.copy(phase = phase.copy(focusedIndex = next))
                }
            }
            ControllerAction.NAV_DOWN -> if (
                !phase.footerFocused && phase.choices.any { it == HomeTileHeroChoice.ImportImage }
            ) {
                MimirNavFeedback.consumed()
                state.copy(phase = phase.copy(footerFocused = true))
            } else {
                state
            }
            ControllerAction.NAV_UP -> if (phase.footerFocused) {
                MimirNavFeedback.consumed()
                state.copy(phase = phase.copy(footerFocused = false))
            } else {
                state
            }
            ControllerAction.CONFIRM -> if (phase.footerFocused) {
                onImportHero()
                state
            } else when (val choice = phase.choices.getOrNull(phase.focusedIndex)) {
                is HomeTileHeroChoice.UseDefault -> {
                    // see the matching comment on
                    // the Icon art UseDefault arm above; same reasoning, same fix shape.
                    onResetHero()
                    state.copy(phase = HomeTileMenuPhase.Applying)
                }
                is HomeTileHeroChoice.UploadedImage -> {
                    onApplyUploadedHero()
                    state.copy(phase = HomeTileMenuPhase.Applying)
                }
                HomeTileHeroChoice.ImportImage -> {
                    onImportHero()
                    state
                }
                is HomeTileHeroChoice.Hero -> {
                    onApplyHero(choice.candidate)
                    state.copy(phase = HomeTileMenuPhase.Applying)
                }
                null -> state
            }
            ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK ->
                returnToCustomMedia(state, onOpenCustomEnrich)
            else -> state
        }
        is HomeTileMenuPhase.ClipPick -> when (action) {
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
            ControllerAction.CONFIRM -> when (val choice = phase.choices.getOrNull(phase.focusedIndex)) {
                is HomeTileClipChoice.UseDefault -> {
                    // see the matching comment on
                    // the Icon art UseDefault arm above; same reasoning, same fix shape.
                    onResetClip()
                    state.copy(phase = HomeTileMenuPhase.Applying)
                }
                is HomeTileClipChoice.Clip -> {
                    onApplyClip(choice.candidate)
                    state.copy(phase = HomeTileMenuPhase.Applying)
                }
                null -> state
            }
            ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK ->
                returnToCustomMedia(state, onOpenCustomEnrich)
            else -> state
        }
    }
}

/**
 * O-7 : pure reconciliation for the Tome checkbox's
 * async save result, called from [MimirAppCoordinator]'s `onToggleCollection` once
 * `HomeShelfCoordinator.toggleTomeMembership`'s callback fires. [reduceHomeTileMenu]'s CONFIRM arm
 * already flipped the entry's ✓ optimistically for an instant response; this reverts that flip and
 * carries a save-failure note only when [saved] is false. Returns null (no state change) on a
 * successful save, on a stale write (the tile's menu closed or moved on to a different row before the
 * save settled), or if the menu is no longer on [HomeTileMenuPhase.CollectionPick] — the same
 * `navigation.homeTileMenu?.takeIf { it.row.itemId == current.row.itemId }` stale-write guard every
 * other async leg in [MimirAppCoordinator] applies inline.
 */
internal fun homeTileMenuAfterToggleCollectionResult(
    latest: HomeTileMenuState,
    itemId: HomeShelfItemId,
    tomeId: TomeId.Custom,
    saved: Boolean,
): HomeTileMenuState? {
    if (saved || latest.row.itemId != itemId) return null
    val phase = latest.phase as? HomeTileMenuPhase.CollectionPick ?: return null
    return latest.copy(
        phase = phase.copy(
            entries = phase.entries.map {
                if (it.id == tomeId) it.copy(isMember = !it.isMember) else it
            },
            note = "Couldn’t save Tome",
        ),
    )
}

@Composable
internal fun HomeTileMenuOverlay(
    state: HomeTileMenuState,
    onMenuFocus: (Int) -> Unit,
    onActivateMenu: (Int) -> Unit,
    onPickFocus: (Int) -> Unit,
    onActivatePick: (Int) -> Unit,
    onFooterFocus: () -> Unit,
    onActivateFooter: () -> Unit,
    authority: FocusAuthority? = null,
    onCustomEnrichAction: (ControllerAction) -> Unit = {},
    onCustomEnrichQueryChange: (String) -> Unit = {},
    onCustomEnrichKeyboardDone: () -> Unit = {},
    /** True while a game-music run is going (one game's or the Settings batch): the Download music row shows busy, the way the Working card does for Enrich. */
    musicBusy: Boolean = false,
    /** True while no account is connected: the Download music row is dimmed at rest, the way Enrich's pill is (still pressable, the press answers on the rail). */
    musicNotConnected: Boolean = false,
) = TileArtMenuOverlay(
    title = state.row.title,
    options = homeTileMenuOptions(state),
    menuFocus = state.menuFocus,
    phase = state.phase,
    onMenuFocus = onMenuFocus,
    onActivateMenu = onActivateMenu,
    onPickFocus = onPickFocus,
    onActivatePick = onActivatePick,
    onFooterFocus = onFooterFocus,
    onActivateFooter = onActivateFooter,
    destructiveLabel = HomeTileMenuAction.REMOVE_FAVORITE.label,
    authority = authority,
    onCustomEnrichAction = onCustomEnrichAction,
    onCustomEnrichQueryChange = onCustomEnrichQueryChange,
    onCustomEnrichKeyboardDone = onCustomEnrichKeyboardDone,
    busyLabel = HomeTileMenuAction.DOWNLOAD_GAME_MUSIC.label.takeIf { musicBusy },
    dimmedLabel = HomeTileMenuAction.DOWNLOAD_GAME_MUSIC.label.takeIf { musicNotConnected },
    confirmCancelFocused = state.confirmCancelFocused,
)

/** Locates the glass panel body for width-cap regression tests (see `HomeTileMenuPanelWidthCapTest`). */
internal const val HomeTileMenuPanelTestTag = "home_tile_menu_panel"

/**
 * the shared tile art/action menu — driven by title + options + phase so BOTH the Home
 * Edit menu and the Library platform-tile menu render through one surface.
 *
 * UXP-03 : this is now the REAL Mimir liquid glass. The body is a translucent
 * palette-driven panel over a blurred scene (the blur itself is applied to the scene by
 * `Modifier.mimirGlassBackdrop` in the app host, since blurring this panel would blur its own text).
 * The previous recipe painted an opaque leather gradient over an 85% scrim and called itself glass.
 */
@Composable
internal fun TileArtMenuOverlay(
    title: String,
    options: List<String>,
    menuFocus: Int,
    phase: HomeTileMenuPhase,
    onMenuFocus: (Int) -> Unit,
    onActivateMenu: (Int) -> Unit,
    onPickFocus: (Int) -> Unit,
    onActivatePick: (Int) -> Unit,
    onFooterFocus: (() -> Unit)? = null,
    onActivateFooter: (() -> Unit)? = null,
    destructiveLabel: String? = null,
    authority: FocusAuthority? = null,
    onCustomEnrichAction: (ControllerAction) -> Unit = {},
    onCustomEnrichQueryChange: (String) -> Unit = {},
    onCustomEnrichKeyboardDone: () -> Unit = {},
    /** The label of the row whose action is running: it is dimmed and carries the gold spinner the Enrich Working card shows . Still pressable: the press answers on the rail. */
    busyLabel: String? = null,
    /**
     * The label of a row that cannot run right now for want of a connected account, dimmed at rest like Enrich's pill is when no account is connected (clearly faded, yet FULLY interactive: the press answers on the rail rather than doing nothing).
     */
    dimmedLabel: String? = null,
    /** The remove-favourite confirm's lit answer: Cancel when true, Remove (the default) when false. */
    confirmCancelFocused: Boolean = false,
) {
    val palette = LocalMimirPalette.current
    val hero = palette.materials.hero
    val swapReducedMotion = mimirReducedMotion()
    // Worked out here, from the host's page, never inside the pages: the page being left must see the return in the
    // same composition that retargets it.
    val swapDirection = remember { HomeTileMenuSwapDirection(homeTileMenuPhaseTransitionKey(phase)) }
    val swapReturning = swapDirection.returningTo(homeTileMenuPhaseTransitionKey(phase))
    val surfaceGlidePx = with(LocalDensity.current) { MimirMotion.GlideTravelDp.dp.roundToPx() }
    AnimatedContent(
        targetState = phase,
        contentKey = { it is HomeTileMenuPhase.CustomEnrichPicker },
        // The swap tier (motion audit batch A): out, then in from the 130 ms lead, never both at once. Full-screen,
        // so its fade layer is screen-sized and cuts no ring. The veil over the shelf is NOT either page's: both
        // pages share it, and with each page painting its own the out-then-in gap took it to ~5% at 130 ms, a
        // bright pulse between the menu and the media picker. It is the container's background, which neither
        // page's fade reaches, so only the panels swap. Leaving the picker is a return (review of 89d626d38): the
        // menu is at rest at once and the picker dissolves over it on the return clock, over the same veil.
        //
        // Customize media -> a slot's picker and back read as a cut: a fade in place, which the picker's heavy first frame could eat,
        // next to pages that all glide. It glides now on the page tier's clocks (MimirPageMotion): the menu leaves
        // upward on ExitMs, the picker rises from GlideTravelDp below on ArriveMs after the LeadMs lead (so its first
        // frame builds during the lead, not the arrival). The return keeps the menu at full alpha at once and the picker
        // sinks back as it dissolves on the return clock. The icon-art return still read as a snap because the
        // menu showed through the glass picker and seemed simply there, so it now glides back down from where it left
        // on the same return clock.
        transitionSpec = {
            val returning = initialState is HomeTileMenuPhase.CustomEnrichPicker &&
                targetState !is HomeTileMenuPhase.CustomEnrichPicker
            val enterMs = if (swapReducedMotion || returning) 0 else MimirPageMotion.ArriveMs
            val leadMs = if (swapReducedMotion || returning) 0 else MimirPageMotion.LeadMs
            val exitMs = when {
                swapReducedMotion -> 0
                returning -> MimirPageMotion.ReturnMs
                else -> MimirPageMotion.ExitMs
            }
            val enterSlideMs = when {
                swapReducedMotion -> 0
                returning -> MimirPageMotion.ReturnMs
                else -> MimirPageMotion.ArriveMs
            }
            val enterEasing = tween<IntOffset>(enterSlideMs, delayMillis = leadMs, easing = MimirMotion.Easing)
            val exitEasing = tween<IntOffset>(exitMs, easing = MimirMotion.Easing)
            (
                (fadeIn(tween(enterMs, delayMillis = leadMs, easing = MimirMotion.Easing)) +
                    slideInVertically(enterEasing) { if (returning) -surfaceGlidePx else surfaceGlidePx }) togetherWith
                    (fadeOut(tween(exitMs, easing = MimirMotion.Easing)) +
                        slideOutVertically(exitEasing) { if (returning) surfaceGlidePx else -surfaceGlidePx })
                ).apply { if (returning) targetContentZIndex = -1f }
        },
        modifier = Modifier.fillMaxSize().background(mimirGlassScrimColor()),
        label = "tileMenuSurfaceFade",
    ) { presentedPhase ->
        val customPicker = presentedPhase as? HomeTileMenuPhase.CustomEnrichPicker
        if (customPicker != null && authority != null) {
            HomeCustomEnrichPickerOverlay(
                authority = authority,
                state = customPicker.picker,
                onAction = onCustomEnrichAction,
                onQueryChange = onCustomEnrichQueryChange,
                onKeyboardDone = onCustomEnrichKeyboardDone,
                // This page is the one being left once its content transition targets anything but Visible: it dissolves for the swap's
                // return or the menu's exit, and lets go of its scope on that commit, not when the dissolve ends (RC-50).
                leaving = transition.targetState != EnterExitState.Visible,
            )
        } else {
    val phase = presentedPhase
    val focusedClipPreview = ((phase as? HomeTileMenuPhase.ClipPick)
        ?.choices?.getOrNull(phase.focusedIndex) as? HomeTileClipChoice.Clip)
        ?.candidate?.previewPath
        ?.let { Uri.fromFile(File(it)).toString() }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            // No scrim here: the one veil is the surface-fade container's (see tileMenuSurfaceFade above).
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        val viewport = rememberMeasuredViewport(
            maxWidth = maxWidth,
            maxHeight = maxHeight,
            inDomain = { widthDp, heightDp ->
                widthDp.isFinite() && heightDp.isFinite() && density.fontScale.isFinite() &&
                    widthDp > 0f && heightDp > 0f && density.fontScale > 0f
            },
            beforeFirstValid = MimirBeforeFirstValidViewport.RENDER_NOTHING,
        )
        if (!viewport.shouldRender) return@BoxWithConstraints
        val geometry = homeTileMenuGeometry(
            widthDp = viewport.width.value,
            heightDp = viewport.height.value,
            fontScale = density.fontScale,
        )
        val compactSquare = geometry.compactLayout
        val compactTypography = geometry.compactTypography
        val actionStyle = if (compactTypography) {
            MimirTypeScale.MenuActionDenseCompact
        } else {
            MimirTypeScale.MenuActionDense
        }
        val actionHorizontalPadding = if (compactTypography) MimirSpacing.md else MimirSpacing.lg
        // The glass panel clips before its padding (MimirLiquidGlass.kt), so this padding is every ring's clearance
        // to the clip. A full-width row's ring reaches MimirFocusTileRingReach past the row; the difference is what
        // such a row pulls in from the panel's edge (ring migration, 2026-09-28, spec §3.6).
        val panelPadding = if (compactTypography) MimirSpacing.sm else MimirSpacing.md
        val rowRingInset = homeTileMenuRowRingInset(panelPadding)
        // VideoBackdrop/scrim/panel are separate top-level layers, so this always-present wrapper is the
        // decision-sized seat that preserves their centered stacking while suppressing held-zero drawing.
        Box(Modifier.mimirMeasuredViewport(viewport), contentAlignment = Alignment.Center) {
        // One local player follows focus behind the glass; the candidate strip itself mounts no decoders.
        if (focusedClipPreview != null) {
            VideoBackdrop(
                videoUri = focusedClipPreview,
                artUri = null,
                settleMs = 0L,
                // NOT a ground: this mounts only in `HomeTileMenuPhase.ClipPick` (see `focusedClipPreview`
                // above), as a preview of the clip being chosen, inside a menu that is already open. A
                // floor here would wipe the scene opaque on the frame the phase flips and again when it
                // leaves -- twice, mid-life, with no transition on either edge, while the player has gone
                // nowhere. The 2026-08-28 ruling is return-scoped and about the Home shelf on the tome
                // return; it does not reach a centred panel's phase change. See VideoBackdrop.paintFloor.
                paintFloor = false,
                modifier = Modifier.fillMaxSize(),
            )
            Box(Modifier.fillMaxSize().background(mimirGlassScrimColor()))
        }
        // fillMaxWidth(fraction).widthIn(max=) was inert here: fillMaxWidth fixes min==max at the
        // fraction of maxWidth, so widthIn's cap only ever coerces UP to that fixed width and never
        // binds. Compute the cap against this BoxWithConstraints's own maxWidth instead.
        val panelWidth = minOf(viewport.width * 0.90f, 480.dp)
        MimirLiquidGlassPanel(
            modifier = Modifier
                .width(panelWidth)
                .padding(MimirSpacing.md)
                .testTag(HomeTileMenuPanelTestTag),
            contentPadding = panelPadding,
            verticalArrangement = Arrangement.spacedBy(
                if (compactTypography) MimirSpacing.xs else MimirSpacing.sm,
            ),
        ) {
            Text(
                // the title read left of the action labels. Action rows
                // inset their own text by actionHorizontalPadding INSIDE the row background, so a
                // title flush to the panel edge lands a padding-width short of every label under it.
                // Same inset here puts them on one optical left edge; the text helpers below match.
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = actionHorizontalPadding),
                text = title,
                style = if (compactTypography) MimirTypeScale.Row else MimirTypeScale.OverlayTitle,
                color = hero.onCover,
                maxLines = 2,
                textAlign = TextAlign.Center,
            )
            AnimatedContent(
                targetState = phase,
                contentKey = ::homeTileMenuPhaseTransitionKey,
                // The swap tier (motion audit batch A): the old page out, then the new one in from the 130 ms lead,
                // with the resize on the arrival's clock, unclipped (never the default spring). The pages fade themselves (ModulateAlpha): their
                // rows' rings reach past the page, and a page-sized fade layer would cut them. A return (B, Cancel)
                // takes the swap's return: the page returned to at rest at once, the page left dissolving over it.
                // A return glides the page back into place (it read as a snap), and the panel resizes on the
                // return clock rather than jumping to the parent's height and clipping the page still dissolving.
                transitionSpec = {
                    mimirSwapTransform(
                        swapReducedMotion,
                        fade = false,
                        returning = swapReturning,
                        returnTravelPx = surfaceGlidePx,
                    )
                },
                label = "tileMenuPhaseFade",
            ) { phase ->
                val pageAlpha = mimirSwapFadeAlpha(swapReducedMotion, returning = swapReturning)
                // Presses during a page change : ignored until the new page shows; Back and
                // the shoulders always work. The arriving page sits at alpha 0 through the swap's 130 ms lead while
                // the reducer is already live, so a second A on Remove confirmed a dialog nobody had seen. The pad
                // waits on MimirTransitionInputGate from this page's first frame until it first paints, or until a
                // reversal takes it off the target. Touch reaches the reducer without the router, so the departing
                // page takes no input at all (Utility's outgoingInput) and the arriving one none until it paints.
                val pageTransition = transition
                val pageLive = pageTransition.targetState == EnterExitState.Visible
                val pageShows by remember(pageAlpha) { derivedStateOf { pageAlpha.value > 0f } }
                val pageGateOwner = remember { Any() }
                LaunchedEffect(Unit) {
                    // The page the menu opens on is already showing: it holds nothing.
                    if (pageTransition.currentState == EnterExitState.Visible) return@LaunchedEffect
                    MimirTransitionInputGate.hold(pageGateOwner)
                    try {
                        snapshotFlow {
                            pageAlpha.value > 0f || pageTransition.targetState != EnterExitState.Visible
                        }.first { it }
                    } finally {
                        MimirTransitionInputGate.release(pageGateOwner)
                    }
                }
                val pageInput = when {
                    !pageLive -> Modifier.clearAndSetSemantics { }.homeTileMenuPageInputShield()
                    !pageShows -> Modifier.homeTileMenuPageInputShield()
                    else -> Modifier
                }
                Column(
                    modifier = Modifier.mimirRingSafeFade(pageAlpha).then(pageInput),
                    verticalArrangement = Arrangement.spacedBy(
                        if (compactSquare) MimirSpacing.xs else MimirSpacing.md,
                    ),
                ) {
                when (phase) {
                is HomeTileMenuPhase.Menu -> {
                    options.forEachIndexed { index, label ->
                        val focused = menuFocus == index
                        val destructive = label == destructiveLabel
                        val busy = label == busyLabel
                        val dimmed = !busy && label == dimmedLabel
                        PlainMenuItem {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    // Destructive rows carry the palette's danger ink and sit apart from the
                                    // safe actions above — the ruling asks for visual separation, not a
                                    // different position (Remove is already last by construction).
                                    .padding(top = if (destructive) MimirSpacing.sm else 0.dp)
                                    .heightIn(min = MimirSpacing.minTarget)
                                    .clickable(
                                        interactionSource = remember(index) { MutableInteractionSource() },
                                        indication = null,
                                    ) { if (focused) onActivateMenu(index) else onMenuFocus(index) }
                                    .then(
                                        when {
                                            busy -> Modifier.semantics { stateDescription = "In progress" }
                                            dimmed -> Modifier.semantics { stateDescription = "Not connected" }
                                            else -> Modifier
                                        },
                                    )
                                    .padding(
                                        horizontal = actionHorizontalPadding,
                                        vertical = if (compactSquare) MimirSpacing.xs else MimirSpacing.md,
                                    ),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                GlassMenuCapsule(focused = focused, identity = label) {
                                    val labelText: @Composable () -> Unit = {
                                        MimirChromeText(
                                            text = label,
                                            style = actionStyle,
                                            color = when {
                                                destructive -> palette.danger
                                                // A running row is dimmed below even an unfocused one, so it reads as not ready.
                                                busy || dimmed -> hero.onCover.copy(alpha = if (focused) 0.6f else 0.45f)
                                                focused -> hero.onCover
                                                else -> hero.onCover.copy(alpha = 0.72f)
                                            },
                                            maxLines = 1,
                                            softWrap = false,
                                        )
                                    }
                                    if (busy) {
                                        // The Enrich Working card's own spinner (gold, round), seated after the label at a row's scale.
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
                                        ) {
                                            labelText()
                                            CircularProgressIndicator(Modifier.size(14.dp), color = MimirColors.Gold, strokeWidth = 2.dp)
                                        }
                                    } else {
                                        labelText()
                                    }
                                }
                            }
                        }
                    }
                }
                is HomeTileMenuPhase.CustomEnrichPicker -> GlassChromeNote(
                    "Media picker is preparing…",
                    compact = compactTypography,
                    centered = true,
                )
                is HomeTileMenuPhase.ConfirmRemove -> {
                    // The question and its two answers, on the panel's own text inset (sweep 10-09): the question stood 18 px from the edge,
                    // the lit row repeated it twice more ("REMOVE FAVORITE" three times on one card), and the hint sat on another inset.
                    MimirIntentText(
                        // A sentence about a named game, in sentence case (a Body intent is never turned into capitals).
                        intent = MimirTextIntent.Body("Remove $title from Favorites?"),
                        style = if (compactTypography) MimirTypeScale.SupportCompact else MimirTypeScale.Support,
                        color = hero.onCover,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = actionHorizontalPadding),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = actionHorizontalPadding),
                        horizontalArrangement = Arrangement.spacedBy(MimirSpacing.md, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // The dialog's one lit answer, Remove (A), and its other, Cancel (B): etched chips of the Customize media page's
                        // own family, the lit one wearing a single rim.
                        HomeTileMenuConfirmChip(
                            label = "Remove",
                            description = "Remove favorite",
                            focused = !confirmCancelFocused,
                            identity = HomeTileMenuConfirmRemoveRingIdentity,
                            labelColor = palette.danger,
                            onActivate = { onActivatePick(0) },
                        )
                        HomeTileMenuConfirmChip(
                            label = "Cancel",
                            description = "Cancel",
                            focused = confirmCancelFocused,
                            identity = HomeTileMenuConfirmCancelRingIdentity,
                            labelColor = hero.onCover,
                            onActivate = { onCustomEnrichAction(ControllerAction.CANCEL) },
                        )
                    }
                    GlassHint(
                        if (confirmCancelFocused) "A CANCEL" else "A REMOVE · B CANCEL",
                        compact = compactTypography,
                        centered = true,
                    )
                }
                is HomeTileMenuPhase.CollectionPick -> {
                    if (phase.entries.isEmpty()) {
                        GlassChromeNote(
                            "No custom Tomes yet — press X on Home to create one",
                            compact = compactTypography,
                            centered = true,
                        )
                    } else {
                        phase.entries.forEachIndexed { index, entry ->
                            val focused = phase.focusedIndex == index
                            PlainMenuItem {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = MimirSpacing.minTarget)
                                        .clickable(
                                            interactionSource = remember("c$index") { MutableInteractionSource() },
                                            indication = null,
                                        ) { if (focused) onActivatePick(index) else onPickFocus(index) }
                                        .padding(horizontal = actionHorizontalPadding, vertical = MimirSpacing.sm),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Box(
                                        Modifier
                                            .align(Alignment.CenterStart)
                                            .size(28.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (entry.isMember) {
                                            Text(
                                                text = "✓",
                                                style = actionStyle,
                                                color = if (focused) hero.onCover else hero.onCover.copy(alpha = 0.72f),
                                            )
                                        }
                                    }
                                    Box(
                                        Modifier
                                            .align(Alignment.Center)
                                            .fillMaxWidth()
                                            .padding(horizontal = 28.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        GlassMenuCapsule(focused = focused, identity = entry.name) {
                                            Text(
                                                text = entry.name,
                                                style = MimirTypeScale.Row,
                                                color = if (focused) hero.onCover else hero.onCover.copy(alpha = 0.72f),
                                                maxLines = 2,
                                                textAlign = TextAlign.Center,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        phase.note?.let { GlassNote(it, compact = compactTypography, centered = true) }
                        GlassHint("A ADD/REMOVE · B BACK", compact = compactTypography, centered = true)
                    }
                }
                is HomeTileMenuPhase.LoadingArt -> GlassChromeNote("Finding icon art…", compact = compactTypography)
                is HomeTileMenuPhase.LoadingHero -> GlassChromeNote("Finding hero art…", compact = compactTypography)
                is HomeTileMenuPhase.LoadingClip -> GlassChromeNote("Finding Hero Motion…", compact = compactTypography)
                is HomeTileMenuPhase.Applying -> GlassChromeNote("Saving…", compact = compactTypography)
                is HomeTileMenuPhase.ProviderUnavailable -> {
                    GlassNote(phase.message, compact = compactTypography)
                    GlassHint("B BACK", compact = compactTypography)
                }
                is HomeTileMenuPhase.ArtPick -> {
                    val stripIndices = homeArtStripIndices(phase.choices)
                    val importIndex = phase.choices.indexOf(HomeTileArtChoice.ImportImage).takeIf { it >= 0 }
                    if (stripIndices.isEmpty() && importIndex == null) {
                        GlassNote(phase.note ?: "No icon art found for this game.", compact = compactTypography)
                    } else {
                        if (stripIndices.isNotEmpty()) {
                            GlassPickStrip(
                                indices = stripIndices,
                                focusedIndex = phase.focusedIndex,
                                identityOf = { phase.choices[it] },
                                onPickFocus = onPickFocus,
                                onActivatePick = onActivatePick,
                                clearance = rowRingInset,
                            ) { index ->
                                when (val choice = phase.choices[index]) {
                                    is HomeTileArtChoice.UseDefault -> GlassResetTile()
                                    is HomeTileArtChoice.UploadedImage -> GlassPreviewTile(choice.previewPath)
                                    is HomeTileArtChoice.Art -> GlassPreviewTile(choice.candidate.previewPath)
                                    HomeTileArtChoice.ImportImage -> Unit
                                }
                            }
                        }
                        when (phase.choices.getOrNull(phase.focusedIndex)) {
                            is HomeTileArtChoice.UploadedImage -> GlassNote("Uploaded image", compact = compactTypography)
                            else -> Unit
                        }
                        phase.note?.let { GlassNote(it, compact = compactTypography) }
                        if (importIndex != null && onFooterFocus != null && onActivateFooter != null) {
                            GlassUploadFooter(
                                focused = phase.footerFocused,
                                onFocus = onFooterFocus,
                                onActivate = onActivateFooter,
                                compactTypography = compactTypography,
                                ringInset = rowRingInset,
                            )
                        }
                        GlassHint(
                            if (phase.footerFocused) "A CHOOSE IMAGE · ↑ ART · B BACK"
                            else "A USE · ↓ UPLOAD IMAGE · B BACK",
                            compact = compactTypography,
                        )
                    }
                }
                is HomeTileMenuPhase.HeroPick -> {
                    val stripIndices = homeHeroStripIndices(phase.choices)
                    val importIndex = phase.choices.indexOf(HomeTileHeroChoice.ImportImage).takeIf { it >= 0 }
                    if (stripIndices.isEmpty() && importIndex == null) {
                        GlassNote(phase.note ?: "No heroes available for this title", compact = compactTypography)
                    } else {
                        if (stripIndices.isNotEmpty()) {
                            GlassPickStrip(
                                indices = stripIndices,
                                focusedIndex = phase.focusedIndex,
                                identityOf = { phase.choices[it] },
                                onPickFocus = onPickFocus,
                                onActivatePick = onActivatePick,
                                clearance = rowRingInset,
                            ) { index ->
                                when (val choice = phase.choices[index]) {
                                    is HomeTileHeroChoice.UseDefault -> GlassResetTile()
                                    is HomeTileHeroChoice.UploadedImage -> GlassPreviewTile(choice.previewPath)
                                    is HomeTileHeroChoice.Hero -> GlassHeroPreviewTile(choice.candidate)
                                    HomeTileHeroChoice.ImportImage -> Unit
                                }
                            }
                        }
                        // Source/type label for the FOCUSED candidate — the ruling requires every
                        // candidate to be attributable to its provider and media kind.
                        val focusedLabel = when (val choice = phase.choices.getOrNull(phase.focusedIndex)) {
                            is HomeTileHeroChoice.Hero -> choice.candidate.displayLabel
                            is HomeTileHeroChoice.UploadedImage -> "Uploaded image"
                            HomeTileHeroChoice.ImportImage -> MimirCopy.Default
                            else -> MimirCopy.Default
                        }
                        GlassNote(focusedLabel, compact = compactTypography)
                        phase.note?.let { GlassNote(it, compact = compactTypography) }
                        if (importIndex != null && onFooterFocus != null && onActivateFooter != null) {
                            GlassUploadFooter(
                                focused = phase.footerFocused,
                                onFocus = onFooterFocus,
                                onActivate = onActivateFooter,
                                compactTypography = compactTypography,
                                ringInset = rowRingInset,
                            )
                        }
                        GlassHint(
                            if (phase.footerFocused) "A CHOOSE IMAGE · ↑ ART · B BACK"
                            else "A USE · ↓ UPLOAD IMAGE · B BACK",
                            compact = compactTypography,
                        )
                    }
                }
                is HomeTileMenuPhase.ClipPick -> {
                    if (phase.choices.isEmpty()) {
                        GlassNote(phase.note ?: "No Hero Motion available for this title", compact = compactTypography)
                    } else {
                        GlassPickStrip(
                            indices = phase.choices.indices.toList(),
                            focusedIndex = phase.focusedIndex,
                            identityOf = { phase.choices[it] },
                            onPickFocus = onPickFocus,
                            onActivatePick = onActivatePick,
                            clearance = rowRingInset,
                        ) { index ->
                            when (val choice = phase.choices[index]) {
                                is HomeTileClipChoice.UseDefault -> GlassResetTile()
                                is HomeTileClipChoice.Clip -> GlassHeroPreviewTile(choice.candidate)
                            }
                        }
                        val focusedLabel = (phase.choices.getOrNull(phase.focusedIndex) as? HomeTileClipChoice.Clip)
                            ?.candidate?.displayLabel
                            ?: MimirCopy.Default
                        GlassNote(focusedLabel, compact = compactTypography)
                        phase.note?.let { GlassNote(it, compact = compactTypography) }
                        GlassHint(MimirCopy.HintMedia, compact = compactTypography)
                    }
                }
                }
                }
            }
        }
        }
    }
        }
    }
}

/**
 * A tile-menu page that is not live, or not yet painted, takes no touch: every pointer is consumed at the Initial
 * pass, before any row's `clickable` sees it (the Utility panel's `outgoingInput`). Touch reaches the reducer
 * without the input router, so this is the touch half of the page-change gate .
 */
private fun Modifier.homeTileMenuPageInputShield(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
}

/**
 * Panel prose/hints share the action rows' optical left edge.
 *
 * Action rows inset their label by [MimirSpacing.md] inside the row background, so any sibling text
 * flush to the panel edge reads a padding-width to the left of every label beneath it (policy
 * 2026-08-10). These three helpers are private to this overlay and are only ever rendered as panel
 * siblings of those rows, so carrying the inset here keeps one edge without threading a modifier
 * through a dozen call sites.
 */
private fun glassTextInset(compact: Boolean): Dp =
    if (compact) MimirSpacing.md else MimirSpacing.lg

@Composable
private fun GlassNote(text: String, compact: Boolean = false, centered: Boolean = false) {
    val hero = LocalMimirPalette.current.materials.hero
    Text(
        text,
        modifier = if (centered) {
            Modifier.fillMaxWidth()
        } else {
            Modifier.padding(horizontal = glassTextInset(compact))
        },
        style = MimirTypeScale.Support,
        color = hero.onCover.copy(alpha = 0.65f),
        textAlign = if (centered) TextAlign.Center else null,
    )
}

@Composable
private fun GlassChromeNote(text: String, compact: Boolean = false, centered: Boolean = false) {
    val hero = LocalMimirPalette.current.materials.hero
    MimirChromeText(
        text,
        modifier = if (centered) {
            Modifier.fillMaxWidth()
        } else {
            Modifier.padding(horizontal = glassTextInset(compact))
        },
        style = MimirTypeScale.MicroStatus,
        color = hero.onCover.copy(alpha = 0.65f),
        textAlign = if (centered) TextAlign.Center else null,
    )
}

@Composable
private fun GlassHint(text: String, compact: Boolean = false, centered: Boolean = false) {
    val hero = LocalMimirPalette.current.materials.hero
    MimirControlHintText(
        text,
        modifier = if (centered) {
            Modifier.fillMaxWidth()
        } else {
            Modifier.padding(horizontal = glassTextInset(compact))
        },
        style = if (compact) MimirTypeScale.ControlHintCompact else MimirTypeScale.ControlHint,
        color = hero.onCover.copy(alpha = 0.65f),
        maxLines = 1,
        softWrap = false,
        textAlign = if (centered) TextAlign.Center else null,
    )
}

@Composable
internal fun PlainMenuItem(content: @Composable () -> Unit) {
    Column {
        PlainMenuSeparator()
        content()
    }
}

/**
 * What a full-width row inside a glass panel pulls in from the panel's edge so its ring clears the clip: the ring's
 * reach less the panel's own padding, never negative. The glass panel clips before its padding (spec §3.6).
 */
internal fun homeTileMenuRowRingInset(panelPadding: Dp): Dp =
    (MimirFocusTileRingReach - panelPadding).coerceAtLeast(0.dp)

/** The confirm dialog's chips' ring identities: one dialog, no index. */
private const val HomeTileMenuConfirmCancelRingIdentity = "home/tile-menu/confirm-cancel"

/**
 * One answer of the confirm dialog: an UNSELECTED etched chip at the Customize media chips' plate height, in a 48 dp hit box, ringed on its
 * plate when [focused] (the plate then draws no rim: the ring is its one edge, [mimirEtchedChipSurface]). [description] is what a screen
 * reader and the tap target are called.
 */
@Composable
private fun HomeTileMenuConfirmChip(
    label: String,
    description: String,
    focused: Boolean,
    identity: String,
    labelColor: Color,
    onActivate: () -> Unit,
) {
    val palette = LocalMimirPalette.current
    val plateHeight = rememberActivityChipPlateHeight()
    Box(
        modifier = Modifier
            .heightIn(min = MimirSpacing.minTarget)
            .widthIn(min = MimirSpacing.minTarget)
            .clickable(interactionSource = remember(identity) { MutableInteractionSource() }, indication = null) { onActivate() }
            .clearAndSetSemantics {
                contentDescription = description
                onClick(label = description) {
                    onActivate()
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // The ring is in the modifier, before the plate's surface (MimirEtchedChipPlate), so it hugs the plate and the plate hands it its rim.
        MimirEtchedChipPlate(
            modifier = Modifier
                .height(plateHeight)
                .mimirSelectionRing(focused = focused, identity = identity, shape = MimirRingShape.Pill, edgeFlush = true),
            focused = focused,
            palette = palette,
            edgeFlush = true,
        ) {
            // One seat for both answers, the label centred in it.
            MimirChromeText(
                text = label,
                style = ActivityChipLabelStyle,
                color = labelColor,
                maxLines = 1,
                softWrap = false,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = 88.dp - MimirSpacing.md * 2),
            )
        }
    }
}

/** The confirm-remove row's ring identity: one row, one dialog, no index. */
private const val HomeTileMenuConfirmRemoveRingIdentity = "home/tile-menu/confirm-remove"

/** The pick strips' tile corner: one symbol for the tile's clip and its ring (R-1). */
internal val GlassPickTileCorner: Dp = 12.dp

/**
 * The horizontal candidate strip. Controller focus must stay visible across every slot: the focused
 * thumb is pulled toward the strip's left edge as the ring walks right.
 */
@Composable
private fun GlassPickStrip(
    indices: List<Int>,
    focusedIndex: Int,
    /** The choice behind an index: the ring's identity is content, never a position (the ghost-free rule). */
    identityOf: (Int) -> Any,
    onPickFocus: (Int) -> Unit,
    onActivatePick: (Int) -> Unit,
    /** What the strip pulls its tiles in from the panel's edge, above and below, so their rings clear the clip. */
    clearance: Dp,
    tile: @Composable (Int) -> Unit,
) {
    val scroll = rememberScrollState()
    val positioned = remember(scroll) { mutableStateOf(false) }
    val stridePx = with(LocalDensity.current) { (120.dp + MimirSpacing.md).toPx() }
    val focusedDisplayIndex = indices.indexOf(focusedIndex).coerceAtLeast(0)
    LaunchedEffect(focusedDisplayIndex, indices) {
        val target = (stridePx * (focusedDisplayIndex - 1)).toInt().coerceAtLeast(0)
        if (positioned.value) {
            scroll.animateScrollTo(target, tween(MimirMotion.FocusMs, easing = MimirMotion.Easing))
        } else {
            scroll.scrollTo(target)
            positioned.value = true
        }
    }
    Row(
        modifier = Modifier
            .horizontalScroll(scroll)
            // A scroll host clips along its axis: the first and last tiles need the ring's full reach at the ends
            // (spec §3.5). Above and below, the panel is the clip, so the strip takes the row inset.
            .padding(horizontal = MimirFocusTileRingReach, vertical = clearance),
        horizontalArrangement = Arrangement.spacedBy(MimirSpacing.md),
    ) {
        indices.forEach { index ->
            val focused = focusedIndex == index
            Box(
                modifier = Modifier
                    .size(120.dp)
                    // The one ring (ring migration, 2026-09-28) on the tile's own corner; the 2.5 dp accent edge it
                    // replaces was this menu's private ring. Art carries no wash and no hairline.
                    .mimirSelectionRing(
                        focused = focused,
                        identity = identityOf(index),
                        shape = MimirRingShape.Card(GlassPickTileCorner),
                    )
                    .clip(RoundedCornerShape(GlassPickTileCorner))
                    .clickable(
                        interactionSource = remember(index) { MutableInteractionSource() },
                        indication = null,
                    ) { if (focused) onActivatePick(index) else onPickFocus(index) },
            ) {
                tile(index)
            }
        }
    }
}

@Composable
private fun GlassPreviewTile(previewPath: String?) {
    // The shared retained painter (RC-28): the cover request's cache identity, so a tile seen before paints on its first frame, and a
    // path that changes under the tile keeps the last good image until the next one decodes.
    RetainedArtImage(
        model = previewPath?.let(::File),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
    )
}

/** A no-thumbnail candidate remains identifiable instead of becoming an empty 120dp square. */
@Composable
private fun GlassHeroPreviewTile(candidate: CustomHeroCandidate) {
    if (candidate.pickerArtPath != null) {
        GlassPreviewTile(candidate.pickerArtPath)
        return
    }
    val hero = LocalMimirPalette.current.materials.hero
    Box(
        Modifier.fillMaxSize().padding(MimirSpacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "${candidate.source.label}\n${candidate.mediaType.label} ${candidate.ordinal}",
            style = MimirTypeScale.Support,
            color = hero.onCover.copy(alpha = 0.82f),
        )
    }
}

/** The canonical `Default` reset slot — a labelled tile, never an image. */
@Composable
private fun GlassResetTile() {
    val hero = LocalMimirPalette.current.materials.hero
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        MimirChromeText(
            text = MimirCopy.Default,
            style = MimirTypeScale.MenuActionCompact,
            color = hero.onCover.copy(alpha = 0.82f),
        )
    }
}

@Composable
private fun GlassUploadFooter(
    focused: Boolean,
    onFocus: () -> Unit,
    onActivate: () -> Unit,
    compactTypography: Boolean,
    ringInset: Dp,
) {
    val palette = LocalMimirPalette.current
    val hero = palette.materials.hero
    val shape = RoundedCornerShape(MimirSpacing.radiusCard)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Pulled in from the panel's clip by what its padding lacks of the ring's reach (spec §3.6).
            .padding(ringInset)
            .heightIn(min = MimirSpacing.minTarget)
            // The one ring (ring migration, 2026-09-28): the glass hairline it rested on fades out under the ring.
            .mimirSelectionRing(
                focused = focused,
                identity = HomeTileMenuUploadFooterRingIdentity,
                shape = MimirRingShape.Row(MimirSpacing.radiusCard),
                restingOutline = true,
                outlineColor = palette.glassRestingOutline(),
            )
            .clip(shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { if (focused) onActivate() else onFocus() }
            .padding(horizontal = MimirSpacing.lg, vertical = MimirSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            MimirChromeText(
                text = "Upload image…",
                style = if (compactTypography) MimirTypeScale.MenuActionCompact else MimirTypeScale.MenuAction,
                color = hero.onCover,
                maxLines = 1,
                softWrap = false,
            )
            Text(
                text = "Uses the system file picker",
                style = MimirTypeScale.Support,
                color = hero.onCover.copy(alpha = 0.65f),
                maxLines = 1,
                softWrap = false,
            )
        }
        Text(
            text = "＋",
            style = MimirTypeScale.Title,
            color = if (focused) hero.onCover else hero.onCover.copy(alpha = 0.72f),
        )
    }
}

/** The upload footer's ring identity: one footer per strip page, no index. */
private const val HomeTileMenuUploadFooterRingIdentity = "home/tile-menu/upload-footer"
