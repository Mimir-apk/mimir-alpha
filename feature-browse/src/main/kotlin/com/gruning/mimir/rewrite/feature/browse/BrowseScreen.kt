package com.gruning.mimir.rewrite.feature.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.input.mimirActivatable
import com.gruning.mimir.rewrite.core.input.mimirFocusable
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.MimirKeyboard
import com.gruning.mimir.rewrite.core.ui.MimirKeyboardHeight
import com.gruning.mimir.rewrite.core.ui.MimirFocusRestingAlpha
import com.gruning.mimir.rewrite.core.ui.MimirFocusTileRingReach
import com.gruning.mimir.rewrite.core.ui.MimirRingShape
import com.gruning.mimir.rewrite.core.ui.MimirSearchGlyph
import com.gruning.mimir.rewrite.core.ui.MimirSheetHost
import com.gruning.mimir.rewrite.core.ui.mimirReducedMotion
import com.gruning.mimir.rewrite.core.ui.mimirSelectionRing
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirEtchedChipLabelStyle
import com.gruning.mimir.rewrite.core.ui.etchedChipInk
import com.gruning.mimir.rewrite.core.ui.mimirEtchedChipSurface
import com.gruning.mimir.rewrite.core.ui.theme.MimirColors
import com.gruning.mimir.rewrite.core.ui.theme.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.theme.MimirType

/**
 * BROWSE — the RECOVER surface for games the user hid. It is deliberately NOT
 * a placement queue, a duplicate shelf, a Settings replacement, or a file organizer. Mimir Alpha indexes
 * in place , so there is no "unplaced inbox" concept — a placed game
 * lives only on the shelf/gamelist; a HIDDEN game lives only here until restored.
 *
 * Restoring a group puts it back on the shelf/gamelist (the shelf/companion counts re-sync reactively off
 * the same repository the shelf reads). One focus authority; controller + touch parity. A touch search bar
 * filters the hidden list (reusing the shared touch [MimirKeyboard]).
 */
val BrowseScope = FocusScope("browse")

private fun restoreKey(id: GameId) = FocusKey("browse/restore/${id.value}")

/**
 * The search bar's focus identity: a pad reaches it by UP from the first hidden game and opens the touch keyboard with A (observed: "Search hidden games" had no
 * focus registration, so a controller could not reach it). It registers before the rows, so it is where Browse's entry focus lands; DOWN steps to the first game.
 */
val BrowseSearchKey = FocusKey("browse/search")

internal fun browsePlatformLabel(displayLabel: String?): String =
    displayLabel
        ?.takeIf(String::isNotBlank)
        ?: "Unknown platform"

/**
 * @param hiddenGames the user-hidden groups ALREADY filtered by [searchQuery] (host owns the filter so the
 *   dispatch's focused-game lookup resolves against the same visible list the screen renders).
 * @param searchQuery the live filter text; drives the search bar + the search-aware empty copy.
 * @param keyboardOpen whether the in-app keyboard is up (host state).
 * @param onRestore un-hide a group → it returns to the shelf/gamelist.
 * @param onSearchChange / onOpenSearch / onClearSearch / onCloseKeyboard the touch search controls.
 * @param onExit CANCEL/Back with nothing focused (or at the list edge) leaves Browse (single back scope).
 */
@Composable
fun BrowseScreen(
    hiddenGames: List<GameRow>,
    platformLabels: Map<PlatformId, String>,
    searchQuery: String,
    keyboardOpen: Boolean,
    authority: FocusAuthority,
    onRestore: (GameId) -> Unit,
    onSearchChange: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onClearSearch: () -> Unit,
    onCloseKeyboard: () -> Unit,
    onExit: () -> Unit,
    onKeyboardHostChanged: (BrowseMimirKeyboardHost?) -> Unit = {},
) {
    val focusState by authority.state.collectAsState()

    DisposableEffect(authority) {
        authority.pushScope(BrowseScope)
        onDispose {
            if (authority.state.value.scope == BrowseScope) authority.popScope()
        }
    }

    Box(Modifier.fillMaxSize().background(MimirColors.SurfaceBase)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(MimirSpacing.xxl),
            verticalArrangement = Arrangement.spacedBy(MimirSpacing.md),
        ) {
            // No "BROWSE" eyebrow — the app-shell header already names the section (// "[themed logo] + section" header). The content title ("Hidden games") stands on its own.
            MimirChromeText("Hidden games", style = MimirType.RunicEyebrow, color = MimirColors.Gold)
            Text(
                "Games you’ve taken off the shelf. Restore one to put it back.",
                style = MimirType.RowSupport,
                color = MimirColors.TextMuted,
            )
            Spacer(Modifier.height(MimirSpacing.xs))

            // Search the hidden list — shown once there's something to search or a filter is active. Touch
            // entry to the keyboard (the controller drives the rows; search is a touch affordance).
            if (hiddenGames.isNotEmpty() || searchQuery.isNotBlank()) {
                BrowseSearchBar(
                    query = searchQuery,
                    focused = focusState.scope == BrowseScope && focusState.focused == BrowseSearchKey,
                    authority = authority,
                    onOpen = onOpenSearch,
                    onClear = onClearSearch,
                )
            }

            if (hiddenGames.isEmpty()) {
                if (searchQuery.isNotBlank()) {
                    Text(
                        "No hidden games match “${searchQuery.trim()}”.",
                        style = MimirType.RowSupport,
                        color = MimirColors.TextMuted,
                    )
                } else {
                    EmptyBrowse()
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
                    // Reserve room for the floating keyboard so the last rows scroll clear of the keys.
                    // The rows' rings reach MimirFocusTileRingReach past them and the list clips on its axis: both
                    // ends reserve it (ring migration, 2026-09-28).
                    contentPadding = PaddingValues(
                        top = MimirFocusTileRingReach,
                        bottom = if (keyboardOpen) MimirKeyboardHeight else MimirFocusTileRingReach,
                    ),
                ) {
                    items(hiddenGames, key = { it.gameId.value }) { row ->
                        val key = restoreKey(row.gameId)
                        HiddenRow(
                            title = row.title,
                            platformLabel = browsePlatformLabel(platformLabels[row.platformId]),
                            focused = focusState.scope == BrowseScope && focusState.focused == key,
                            identity = key,
                            onActivate = { authority.requestFocus(key); onRestore(row.gameId) },
                            modifier = Modifier.mimirFocusable(authority, key, BrowseScope, revealOutset = MimirFocusTileRingReach),
                        )
                    }
                }
            }
        }

        // The touch keyboard floats at the bottom while search is active; the empty area above has no touch
        // handler so the filtered rows behind stay tappable. Done dismisses (host clears keyboardOpen).
        //
        // it used to be `if (keyboardOpen)`, so it popped in and out in one frame.
        // It rides the shared sheet host now, like the Files and Library keyboards: it slides in from the bottom edge and fades on
        // the sheet tier's one clock, and it stays composed through its exit with every touch shielded. The pad is withdrawn in the
        // frame `keyboardOpen` flips: the host is null once the keyboard is closed ([rememberBrowseMimirKeyboardHost]), so the
        // leaving keyboard draws with nothing wired to it.
        val host = rememberBrowseMimirKeyboardHost(authority, keyboardOpen)
        SideEffect { onKeyboardHostChanged(host) }
        DisposableEffect(Unit) {
            onDispose { onKeyboardHostChanged(null) }
        }
        MimirSheetHost(
            visible = keyboardOpen,
            reducedMotion = mimirReducedMotion(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            MimirKeyboard(
                value = searchQuery,
                onValueChange = onSearchChange,
                onDone = onCloseKeyboard,
                controller = host?.controller,
                keyDecorator = host?.keyDecorator,
                targetLabel = "Search hidden games",
            )
        }
    }
}

/**
 * THE single input entry point for Browse. With the touch keyboard open, controller input is quarantined and
 * only Back/Cancel closes it. Otherwise Up/down move focus (the search bar sits above the rows), CONFIRM restores the focused hidden group or, on the search bar, opens the keyboard, and
 * Back leaves Browse. Returns true when handled (so the shell doesn't treat it as a shell-level Back).
 */
fun dispatchToBrowse(
    authority: FocusAuthority,
    hiddenGames: List<GameRow>,
    keyboardOpen: Boolean,
    keyboardHost: BrowseMimirKeyboardHost?,
    action: ControllerAction,
    onRestore: (GameId) -> Unit,
    onCloseKeyboard: () -> Unit,
    onExit: () -> Unit,
    onOpenSearch: () -> Unit = {},
): Boolean {
    if (authority.state.value.scope != BrowseScope) return false
    if (keyboardOpen) {
        if (dispatchBrowseMimirKeyboard(authority, keyboardHost, action, onCloseKeyboard)) {
            return true
        }
        return when (action) {
            ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK -> {
                onCloseKeyboard()
                true
            }
            else -> true
        }
    }
    return when (action) {
        ControllerAction.NAV_UP, ControllerAction.NAV_DOWN -> {
            authority.moveFocus(action)
            true
        }
        ControllerAction.CONFIRM -> {
            val focused = authority.state.value.focused?.value
            // A on the search bar opens the touch keyboard, the same as a tap on it.
            if (authority.state.value.focused == BrowseSearchKey) {
                onOpenSearch()
                return true
            }
            val prefix = "browse/restore/"
            focused?.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)
                ?.let { id -> hiddenGames.firstOrNull { it.gameId.value == id } }
                ?.let { onRestore(it.gameId) }
            true
        }
        // Back leaves Browse; keyboard-open dismissal is quarantined before normal dispatch above.
        ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK -> {
            onExit()
            true
        }
        else -> false
    }
}

/**
 * The Browse search bar: a capsule etched chip showing the live query (or a placeholder) + a clear (×) when non-empty. A tap, or A with the bar focused, opens the touch [MimirKeyboard].
 * Its focus ring is on the plate itself (pill, before the surface, no inset), and the rim hands over to it while focused; the tap is one activation beside the focus registration, never a second
 * Compose focus target.
 */
@Composable
private fun BrowseSearchBar(query: String, focused: Boolean, authority: FocusAuthority, onOpen: () -> Unit, onClear: () -> Unit) {
    val palette = LocalMimirPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .mimirFocusable(authority, BrowseSearchKey, BrowseScope, revealOutset = MimirFocusTileRingReach)
            .mimirSelectionRing(focused = focused, identity = BrowseSearchKey, shape = MimirRingShape.Pill, edgeFlush = true)
            // Update: the etched chip's surface, not a filled capsule. Surface only: the bar keeps its padding and its type, as the Library's does.
            .mimirEtchedChipSurface(palette, warmed = false, focused = focused, edgeFlush = true)
            .mimirActivatable(label = "Search hidden games", authority = authority, key = BrowseSearchKey, onActivate = onOpen)
            .padding(horizontal = MimirSpacing.lg, vertical = MimirSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
    ) {
        // The one drawn magnifier at the one search size (the font glyph was 14 px beside 30 px text), the same mark the Library, the Activity filter and Customize media draw.
        MimirSearchGlyph(color = MimirColors.TextSecondary)
        Text(
            query.ifBlank { "Search hidden games" },
            style = MimirType.RowSupport,
            color = if (query.isBlank()) MimirColors.TextMuted else MimirColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(0.dp).weight(1f),
        )
        if (query.isNotBlank()) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(MimirSpacing.radiusPill))
                    .clickable(onClick = onClear)
                    .padding(MimirSpacing.xs),
            ) {
                Text("×", style = MimirType.RowTitle, color = MimirColors.TextSecondary)
            }
        }
    }
}

@Composable
private fun HiddenRow(
    title: String,
    platformLabel: String,
    focused: Boolean,
    /** The row's FocusKey: the ring's identity is content, never a position. */
    identity: Any,
    onActivate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The one ring (ring migration, 2026-09-28) on the row's card corner, no grow. The ring paints the resting fill so
    // its wash shows (the SurfaceHigh lift retires, as the recovery gate's did); the rest dim is the old ring's 0.88.
    // The tap is one activation beside the caller's focus registration.
    val palette = LocalMimirPalette.current
    Row(
        modifier
            .fillMaxWidth()
            .mimirSelectionRing(
                focused = focused,
                identity = identity,
                shape = MimirRingShape.Row(MimirSpacing.radiusCard),
                fill = SolidColor(MimirColors.SurfaceRaised),
                restingAlpha = MimirFocusRestingAlpha,
            )
            .clip(RoundedCornerShape(MimirSpacing.radiusCard))
            .mimirActivatable(label = "Restore $title", onActivate = onActivate)
            .padding(horizontal = MimirSpacing.lg, vertical = MimirSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MimirSpacing.md),
    ) {
        Column(Modifier.width(0.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MimirType.RailItem,
                color = if (focused) MimirColors.GoldBright else MimirColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                platformLabel,
                style = MimirType.MicroStatus,
                color = MimirColors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Update: an etched chip's plate, not a gold-tinted capsule. The row is the control; this is its label, so it keeps its small height.
        Box(
            Modifier
                .mimirEtchedChipSurface(palette, warmed = false)
                .padding(horizontal = MimirSpacing.md, vertical = MimirSpacing.xs),
        ) {
            MimirChromeText("Restore", style = MimirEtchedChipLabelStyle, color = palette.etchedChipInk())
        }
    }
}

@Composable
private fun EmptyBrowse() {
    Column(
        Modifier.fillMaxWidth().padding(top = MimirSpacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
    ) {
        MimirChromeText(
            "No hidden games",
            style = MimirType.TitleCaps,
            color = MimirColors.TextPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            "When you hide a game it lands here, ready to restore.",
            style = MimirType.RowSupport,
            color = MimirColors.TextSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(MimirSpacing.xs))
    }
}
