package com.gruning.mimir.rewrite.feature.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.input.MimirNavFeedback
import com.gruning.mimir.rewrite.core.model.MimirMarkNudgeDp
import com.gruning.mimir.rewrite.core.model.MimirMarkPlacement
import com.gruning.mimir.rewrite.core.model.MimirMarkReferenceCoinDp
import com.gruning.mimir.rewrite.core.model.MimirMarkReferenceFaceRadiusDp
import com.gruning.mimir.rewrite.core.model.mimirMarkBoxRect
import com.gruning.mimir.rewrite.core.model.mimirMarkDrawRect
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirButtonMark
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.MimirShelfBackground
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import com.gruning.mimir.rewrite.core.ui.MimirWordEllipsisText
import com.gruning.mimir.rewrite.core.ui.theme.MimirType
import kotlin.math.roundToInt

/**
 * The MIMIR BUTTON picker (concept A), the same shell as the Background picker:
 * `ᛗ · MIMIR BUTTON`, the tiles (ᛗ Rune (DEFAULT) · saved marks · + Add image), no Style row (R3: struck only).
 *
 * The stage is a slot, because the REAL button is a native View this module cannot reach (`core-companion-native`):
 * the app hosts it through a non-focusable `AndroidView` and `createMimirButtonPreviewView`. What the stage SHOWS is
 * decided here, once, by [MimirButtonPickerStageLayout] -- the button at its real 56 dp over the current background, a
 * 2x copy for detail, and a 56 dp copy on a light panel drawn from `textPrimary` standing in for a bright game -- and
 * the host only supplies the coin. [MimirButtonPickerStagePlaceholder] is the same three seats with palette-token coins,
 * for hosts that have no overlay. Only A pushes the mark to the live button, so nothing here writes a global preview: the
 * stage receives the previewed mark as a parameter, like the background's stage receives its ground.
 *
 * The rules ([mimirButtonPickerTransition]) are the background's, without the rows: A uses the focused tile (the
 * rune commits null), A on + Add image opens the import, X removes a saved mark that is not the current one, B
 * leaves. Y on a saved image opens ADJUST:
 * the stage becomes a large coin to work on beside the real 56 dp button, the pad moves the image (D-pad, 0.5 dp a press and
 * repeating while held), sizes it (L1 and R1, 5% a press) and resets it (X); A saves the placement into that image's own
 * sidecar AND uses the image, B cancels and leaves the saved placement as it was. Touch drags the large coin, pinches it and
 * has the chips Smaller, Larger, Reset and Done. Y does nothing on the rune or Add, and the footer names it on an image only.
 * A step that changed the placement clicks (and buzzes) like a D-pad step in the tiles, whichever key made it -- the D-pad through
 * `MimirNavFeedback.consumed`, L1, R1 and X through `MimirNavFeedback.stepped` -- and a press that could change nothing is silent. A and B are
 * silent, as A is on the other Appearance pickers; the chips, like every tap in the product, play nothing.
 */
val MimirButtonPickerScope = FocusScope("appearance/mimirButtonPicker")

internal const val MimirButtonPickerTitle = "ᛗ · MIMIR BUTTON"
internal const val MimirButtonPickerSubtitle = "The M you hold for Mimir’s menu"
internal const val MimirButtonDefaultLabel = "ᛗ Rune"
internal const val MimirButtonNoteInUse = "In use"
internal const val MimirButtonNoteRemoved = "Removed"
internal const val MimirButtonNoteRemoveCurrent = "Switch first before removing the current choice."
internal const val MimirButtonNoteUnavailable = "This mark can’t be shown. X removes it."
internal const val MimirButtonNoteAdjustFailed = "That position couldn’t be saved."

/**
 * Adjust mode's words, in the picker's voice: the header names the pad's two jobs and which coin is real, the footer names the keys once. The D-pad is not in the
 * footer (the subtitle, which has two lines, names it); the size keys ARE, because device review found the size binding was hard to discover in the subtitle. The footer is one line, and the whole list ("L1 R1 SIZE · A SAVE · X RESET · B CANCEL") may be wider than a narrow screen's
 * footer at a large font: there it shortens to [MimirButtonControlHintAdjustingShort], which drops X RESET (the Reset chip and the key still work) but never the
 * size keys. [MimirButtonPickerState.controlHintFallback] says when a footer has a shorter line to fall back on.
 */
internal const val MimirButtonAdjustTitle = "ᛗ · ADJUST YOUR MARK"
internal const val MimirButtonAdjustSubtitle = "D-pad moves it, L1 and R1 size it. The small button is the real one."

/** The footer on a saved image, where Y opens ADJUST; the rune gets [AppearancePickerControlHintNoRemove] and Add [AppearancePickerControlHintUpload], neither of which has a Y. */
internal const val MimirButtonControlHintAdjustable = "A USE · X REMOVE · Y ADJUST · B BACK"
internal const val MimirButtonControlHintAdjusting = "L1 R1 SIZE · A SAVE · X RESET · B CANCEL"
internal const val MimirButtonControlHintAdjustingShort = "L1 R1 SIZE · A SAVE · B CANCEL"

/** The chips' words (touch); what each does the pad also does: Smaller is L1, Larger is R1, Reset is X, Done is A. */
internal const val MimirButtonChipSmaller = "Smaller"
internal const val MimirButtonChipLarger = "Larger"
internal const val MimirButtonChipReset = "Reset"
internal const val MimirButtonChipDone = "Done"

/** A saved mark as the picker sees it. [path] null = the app's copy is missing or corrupt. [placement] is where the player put it on the coin. */
data class MimirButtonPickerMark(
    val id: String,
    val label: String,
    val path: String?,
    val placement: MimirMarkPlacement = MimirMarkPlacement.Default,
) {
    val available: Boolean get() = path != null
}

/**
 * Adjust mode on the image [id]: [working] is the placement the player is shaping (the stage draws it, nothing else sees it), and
 * [restore] the placement saved when adjust opened. B drops [working]; A writes it. The saved list is never touched until A.
 */
data class MimirButtonPickerAdjust(
    val id: String,
    val working: MimirMarkPlacement,
    val restore: MimirMarkPlacement,
)

data class MimirButtonPickerState(
    val current: MimirButtonMark?,
    val saved: List<MimirButtonPickerMark>,
    val previewId: String?,
    val note: String? = null,
    /** Non-null while the player is adjusting one image; the pad then moves and sizes it instead of the focus. */
    val adjust: MimirButtonPickerAdjust? = null,
    /**
     * True from A on + Upload until focus is on that tile again: the picker has left for the file picker (which covers it and gives it back, remounted, when it closes) and
     * lands on the tile it left from, not on the tile in use ([initialFocusKey]). Cleared by focus landing on + Upload, and by an import that added a tile.
     */
    val importOpen: Boolean = false,
)

sealed interface MimirButtonPickerCommand {
    /** Persist [mark] (null = the rune); the host commits, ticks the overlay's revision, and the live ᛗ repaints. */
    data class Commit(val mark: MimirButtonMark?) : MimirButtonPickerCommand

    /**
     * A in adjust mode: write [mark]'s placement into that image's sidecar AND commit the image, as [Commit] does (one revision tick, so the
     * live ᛗ repaints at once). The host lands focus back on the image's tile when it has.
     */
    data class SaveAdjusted(val mark: MimirButtonMark) : MimirButtonPickerCommand

    /** B in adjust mode: nothing was written; the host lands focus back on the image [id]'s tile. */
    data class LeaveAdjust(val id: String) : MimirButtonPickerCommand
    data class Remove(val id: String) : MimirButtonPickerCommand
    data object Import : MimirButtonPickerCommand
    data object Dismiss : MimirButtonPickerCommand
}

data class MimirButtonPickerTransition(val state: MimirButtonPickerState, val command: MimirButtonPickerCommand? = null)

private const val KeyRoot = "appearance/mbutton"
/** The rune tile's key: where the host lands focus after a removal, and the default tile's identity. */
val MimirButtonRuneKey = FocusKey("$KeyRoot/tile/rune")
/** The + Upload tile's key: where the picker lands focus when the file picker it opened closes (the tile it left from). */
val MimirButtonAddKey = FocusKey("$KeyRoot/tile/add")
/** A saved mark's tile key: the host lands focus here once an import has added it. */
fun mimirButtonTileKey(id: String): FocusKey = FocusKey("$KeyRoot/tile/$id")

internal fun MimirButtonPickerState.savedForKey(key: FocusKey?): MimirButtonPickerMark? =
    saved.firstOrNull { mimirButtonTileKey(it.id) == key }

fun mimirButtonPickerInitialState(current: MimirButtonMark?, saved: List<MimirButtonPickerMark>): MimirButtonPickerState =
    MimirButtonPickerState(current = current, saved = saved, previewId = current?.id)

/**
 * Where focus lands when the picker mounts: + Upload while [MimirButtonPickerState.importOpen] (the picker comes back from the file picker on the tile it left from); otherwise the previewed saved tile when it is NOT the current one -- with [mimirButtonPickerTransition] settling the preview back onto the current tile as
 * the import opens, exactly the tile an import has just added -- otherwise the tile in use (the rune when none). Cancelling the file picker returns focus to + Upload;
 * finishing it lands on the new tile.
 */
fun MimirButtonPickerState.initialFocusKey(): FocusKey {
    if (importOpen) return MimirButtonAddKey
    val previewed = saved.firstOrNull { it.id == previewId && it.id != current?.id }
    return previewed?.let { mimirButtonTileKey(it.id) } ?: current?.let { mimirButtonTileKey(it.id) } ?: MimirButtonRuneKey
}

/**
 * The state once an import has landed: the re-read saved list, the stage previewing the NEW mark (an import that added
 * nothing leaves the preview alone, and focus on + Upload, where it was) and the note. The host lands focus on [mimirButtonTileKey] of the new id with the
 * shared retrying `landPickerFocus`.
 */
fun MimirButtonPickerState.withImportLanded(
    saved: List<MimirButtonPickerMark>,
    importedId: String?,
    note: String?,
): MimirButtonPickerState = copy(saved = saved, previewId = importedId ?: previewId, note = note, importOpen = importOpen && importedId == null)

/**
 * The state once the file picker the player opened from + Upload has closed with nothing chosen and the picker never left the screen (the system picker, which covers it
 * without remounting it): + Upload is no longer where a remount should land. The in-app file picker needs no such call: the picker remounts and lands on + Upload, which ends it.
 */
fun MimirButtonPickerState.withImportCancelled(): MimirButtonPickerState = copy(importOpen = false)

/** The placement a [MimirButtonMark] carries, as a finite number in range ([MimirMarkPlacement.sanitized]). */
fun MimirButtonMark.markPlacement(): MimirMarkPlacement = MimirMarkPlacement(scale, offsetX, offsetY).sanitized()

/** [id]'s file as a [MimirButtonMark] wearing [placement]: the three numbers the contract carries beside the path. */
fun mimirButtonMarkOf(id: String, path: String, placement: MimirMarkPlacement): MimirButtonMark {
    val p = placement.sanitized()
    return MimirButtonMark(id = id, path = path, scale = p.scale, offsetX = p.offsetX, offsetY = p.offsetY)
}

/** This saved mark as the mark it would commit (with its own placement unless one is given), or null when its copy is missing. */
private fun MimirButtonPickerMark.committable(wearing: MimirMarkPlacement = placement): MimirButtonMark? =
    path?.let { mimirButtonMarkOf(id, it, wearing) }

/**
 * The mark the stage previews, or null for the rune. An unavailable mark previews as the rune. A saved mark previews with ITS placement, and
 * the image being adjusted with the placement the player is shaping.
 */
fun MimirButtonPickerState.previewMark(): MimirButtonMark? {
    val mark = saved.firstOrNull { it.id == previewId } ?: return null
    return mark.committable(adjust?.takeIf { it.id == mark.id }?.working ?: mark.placement)
}

/**
 * The state after a save that could not be written (the host reports it): the player is back at the tiles, the lists as the stores hold
 * them, and the note says so. Nothing was committed.
 */
fun MimirButtonPickerState.withAdjustSaveFailed(
    saved: List<MimirButtonPickerMark>,
    current: MimirButtonMark?,
): MimirButtonPickerState = copy(saved = saved, current = current, adjust = null, note = MimirButtonNoteAdjustFailed)

/** Focus previews the tile's mark on the stage; the rune clears it. Nothing commits. A mark being adjusted keeps the stage. */
fun mimirButtonPickerOnFocus(state: MimirButtonPickerState, focused: FocusKey?): MimirButtonPickerState {
    if (focused == null || state.adjust != null) return state
    if (focused == MimirButtonRuneKey) return state.copy(previewId = null)
    state.savedForKey(focused)?.let { mark -> return state.copy(previewId = mark.id) }
    // + Upload: the stage keeps the last tile hovered, and a picker that came back from the file picker has landed where it left from.
    if (focused == MimirButtonAddKey && state.importOpen) return state.copy(importOpen = false)
    return state
}

fun mimirButtonPickerTransition(
    state: MimirButtonPickerState,
    action: ControllerAction,
    focused: FocusKey?,
): MimirButtonPickerTransition {
    val adjust = state.adjust
    return if (adjust != null) mimirButtonAdjustTransition(state, adjust, action) else mimirButtonBrowseTransition(state, action, focused)
}

private fun mimirButtonBrowseTransition(
    state: MimirButtonPickerState,
    action: ControllerAction,
    focused: FocusKey?,
): MimirButtonPickerTransition = when (action) {
    ControllerAction.CONFIRM -> when {
        // Settle the preview back on the committed tile as the picker leaves for the file picker: the last tile the pad passed on the way to + Upload
        // must not stay on the stage while focus returns to Upload (importOpen: the remounted picker lands there, not on the tile in use).
        focused == MimirButtonAddKey -> MimirButtonPickerTransition(
            state.copy(previewId = state.current?.id, importOpen = true),
            MimirButtonPickerCommand.Import,
        )
        focused == MimirButtonRuneKey -> MimirButtonPickerTransition(
            state.copy(current = null, previewId = null, note = MimirButtonNoteInUse),
            MimirButtonPickerCommand.Commit(null),
        )
        else -> state.savedForKey(focused)?.let { mark ->
            if (!mark.available) {
                MimirButtonPickerTransition(state.copy(note = MimirButtonNoteUnavailable))
            } else {
                val committed = mark.committable()!!
                MimirButtonPickerTransition(
                    state.copy(current = committed, previewId = mark.id, note = MimirButtonNoteInUse),
                    MimirButtonPickerCommand.Commit(committed),
                )
            }
        } ?: MimirButtonPickerTransition(state)
    }
    ControllerAction.SECONDARY -> state.savedForKey(focused)?.let { mark ->
        if (state.current?.id == mark.id) {
            MimirButtonPickerTransition(state.copy(note = MimirButtonNoteRemoveCurrent))
        } else {
            MimirButtonPickerTransition(
                state.copy(
                    saved = state.saved.filterNot { it.id == mark.id },
                    previewId = state.previewId.takeIf { it != mark.id },
                    note = MimirButtonNoteRemoved,
                ),
                MimirButtonPickerCommand.Remove(mark.id),
            )
        }
    } ?: MimirButtonPickerTransition(state)
    // Y on a saved image opens ADJUST on it; on the rune, Add or a mark that cannot be shown it does nothing.
    ControllerAction.TERTIARY -> state.savedForKey(focused)?.takeIf { it.available }?.let { mark ->
        MimirButtonPickerTransition(
            state.copy(
                previewId = mark.id,
                note = null,
                adjust = MimirButtonPickerAdjust(id = mark.id, working = mark.placement.sanitized(), restore = mark.placement.sanitized()),
            ),
        )
    } ?: MimirButtonPickerTransition(state)
    ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK -> MimirButtonPickerTransition(state, MimirButtonPickerCommand.Dismiss)
    else -> MimirButtonPickerTransition(state)
}

/**
 * Adjust mode's pad: the D-pad moves the image 0.5 dp a press (the input authority already repeats a held direction, so a hold keeps
 * moving), L1 and R1 size it 5% a press, X resets it to centred at 100%, A saves it AND uses the image, B leaves with the saved placement as it
 * was. Everything else is ignored, Y included. Nothing here reaches a store: the stage draws [MimirButtonPickerAdjust.working] and A hands the
 * host a command.
 */
private fun mimirButtonAdjustTransition(
    state: MimirButtonPickerState,
    adjust: MimirButtonPickerAdjust,
    action: ControllerAction,
): MimirButtonPickerTransition {
    fun shaped(next: MimirMarkPlacement): MimirButtonPickerTransition =
        if (next == adjust.working) MimirButtonPickerTransition(state) else MimirButtonPickerTransition(state.copy(adjust = adjust.copy(working = next)))
    return when (action) {
        ControllerAction.NAV_LEFT -> shaped(adjust.working.movedBy(-MimirMarkNudgeDp, 0f))
        ControllerAction.NAV_RIGHT -> shaped(adjust.working.movedBy(MimirMarkNudgeDp, 0f))
        ControllerAction.NAV_UP -> shaped(adjust.working.movedBy(0f, -MimirMarkNudgeDp))
        ControllerAction.NAV_DOWN -> shaped(adjust.working.movedBy(0f, MimirMarkNudgeDp))
        ControllerAction.PAGE_LEFT -> shaped(adjust.working.steppedScale(larger = false))
        ControllerAction.PAGE_RIGHT -> shaped(adjust.working.steppedScale(larger = true))
        ControllerAction.SECONDARY -> shaped(MimirMarkPlacement.Default)
        ControllerAction.CONFIRM -> {
            val mark = state.saved.firstOrNull { it.id == adjust.id }?.takeIf { it.available }
            if (mark == null) {
                // The copy went missing while the player was shaping it: nothing to save, and back to the tiles.
                MimirButtonPickerTransition(state.copy(adjust = null), MimirButtonPickerCommand.LeaveAdjust(adjust.id))
            } else {
                val placement = adjust.working.sanitized()
                val committed = mark.committable(placement)!!
                MimirButtonPickerTransition(
                    state.copy(
                        current = committed,
                        saved = state.saved.map { if (it.id == mark.id) it.copy(placement = placement) else it },
                        previewId = mark.id,
                        note = MimirButtonNoteInUse,
                        adjust = null,
                    ),
                    MimirButtonPickerCommand.SaveAdjusted(committed),
                )
            }
        }
        // Cancel puts the saved placement back explicitly (the list was never written, so this is the placement it already holds).
        ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK -> MimirButtonPickerTransition(
            state.copy(
                saved = state.saved.map { if (it.id == adjust.id) it.copy(placement = adjust.restore) else it },
                previewId = adjust.id,
                adjust = null,
            ),
            MimirButtonPickerCommand.LeaveAdjust(adjust.id),
        )
        else -> MimirButtonPickerTransition(state)
    }
}

/**
 * A finger on the large coin (touch): [dxDp] and [dyDp] are the drag on the reference 56 dp coin and [zoom] the pinch's ratio since the last
 * event. The same shaping as the pad's, continuous; held in range, and a state with no adjust open is left as it is.
 */
fun mimirButtonPickerAdjustGesture(
    state: MimirButtonPickerState,
    dxDp: Float,
    dyDp: Float,
    zoom: Float,
): MimirButtonPickerTransition {
    val adjust = state.adjust ?: return MimirButtonPickerTransition(state)
    val next = adjust.working.movedBy(dxDp, dyDp).scaledBy(zoom)
    return if (next == adjust.working) MimirButtonPickerTransition(state) else MimirButtonPickerTransition(state.copy(adjust = adjust.copy(working = next)))
}

/** The footer for the focused tile: Y ADJUST on a saved available image only, X REMOVE on any saved image, A UPLOAD on Add (it opens the file picker), A USE and no X on the Rune. */
internal fun MimirButtonPickerState.controlHint(focused: FocusKey?): String = when {
    adjust != null -> MimirButtonControlHintAdjusting
    savedForKey(focused)?.available == true -> MimirButtonControlHintAdjustable
    savedForKey(focused) != null -> AppearancePickerControlHint
    focused == MimirButtonAddKey -> AppearancePickerControlHintUpload
    else -> AppearancePickerControlHintNoRemove
}

/** The shorter footer for a screen too narrow for [controlHint]: adjust mode's only (see [MimirButtonControlHintAdjustingShort]); the tiles' footers have none. */
internal fun MimirButtonPickerState.controlHintFallback(): String? = if (adjust != null) MimirButtonControlHintAdjustingShort else null

internal fun MimirButtonPickerState.tiles(): List<AppearancePickerTile> = buildList {
    add(AppearancePickerTile.Default(MimirButtonRuneKey, MimirButtonDefaultLabel))
    saved.forEach { mark ->
        add(
            AppearancePickerTile.Saved(
                key = mimirButtonTileKey(mark.id),
                id = mark.id,
                label = mark.label,
                path = mark.path,
                current = current?.id == mark.id,
                placement = mark.placement,
            ),
        )
    }
    add(AppearancePickerTile.Add(MimirButtonAddKey))
}

/**
 * @param stage the real button's preview (builder 2), given the previewed mark (null = the rune). Defaults to
 *   [MimirButtonPickerStagePlaceholder].
 * @param adjustCoin one coin of adjust mode's stage , given the mark wearing the placement being shaped and the copy's scale
 *   (the real 56 dp button is 1, the large coin to work on is up to 3): laid out in a box of `56 dp * scale`, as [MimirButtonPickerStageLayout]'s
 *   coins are. The app hosts the native overlay's own view here, so both coins are the real button; the default draws [MarkCoin].
 */
@Composable
fun MimirButtonPickerOverlay(
    authority: FocusAuthority,
    state: MimirButtonPickerState,
    onFocus: (FocusKey?) -> Unit,
    onTransition: (MimirButtonPickerTransition) -> Unit,
    presenceManagedExternally: Boolean = false,
    stage: @Composable BoxScope.(preview: MimirButtonMark?) -> Unit = { preview -> MimirButtonPickerStagePlaceholder(preview) },
    adjustCoin: @Composable (mark: MimirButtonMark, scale: Float) -> Unit = { mark, scale ->
        MarkCoin(path = mark.path, diameter = MimirButtonStageCoinSize * scale, placement = mark.markPlacement())
    },
) {
    LaunchedEffect(authority) {
        authority.state.collect { snapshot -> if (snapshot.scope == MimirButtonPickerScope) onFocus(snapshot.focused) }
    }
    val preview = state.previewMark()
    val adjusting = state.adjust != null
    // The newest state this overlay has handed out. A finger delivers several events between two recompositions, and each must build on
    // the one before it, not on the state the last composition saw (that would drop every event but the last of a frame).
    val latest = remember { MimirButtonPickerLatest(state) }
    SideEffect { latest.state = state }
    fun hand(next: MimirButtonPickerTransition) {
        latest.state = next.state
        onTransition(next)
    }
    fun press(action: ControllerAction) = hand(mimirButtonPickerTransition(latest.state, action, focused = null))
    AppearanceImagePickerOverlay(
        authority = authority,
        scope = MimirButtonPickerScope,
        title = if (adjusting) MimirButtonAdjustTitle else MimirButtonPickerTitle,
        subtitle = if (adjusting) MimirButtonAdjustSubtitle else MimirButtonPickerSubtitle,
        tiles = state.tiles(),
        defaultIsCurrent = state.current == null,
        initialFocusKey = state.initialFocusKey(),
        note = state.note,
        onActivateTile = { tile -> onTransition(mimirButtonPickerTransition(state, ControllerAction.CONFIRM, tile.key)) },
        onRemoveTile = { tile -> onTransition(mimirButtonPickerTransition(state, ControllerAction.SECONDARY, tile.key)) },
        // Back and an outside tap are B: in adjust mode that is cancel, not leaving the picker.
        onDismiss = { press(ControllerAction.CANCEL) },
        stage = {
            if (adjusting && preview != null) {
                MimirButtonPickerAdjustStageLayout(
                    mark = preview,
                    onGesture = { dx, dy, zoom -> hand(mimirButtonPickerAdjustGesture(latest.state, dx, dy, zoom)) },
                    coin = adjustCoin,
                )
            } else {
                stage(preview)
            }
        },
        defaultTile = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { MarkCoin(path = null, diameter = 36.dp) } },
        thumbnail = { tile ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                MarkCoin(path = tile.path, diameter = 36.dp, placement = tile.placement)
            }
        },
        presenceManagedExternally = presenceManagedExternally,
        controlHint = { focused -> state.controlHint(focused) },
        controlHintFallback = { state.controlHintFallback() },
        tilesOverlay = if (adjusting) {
            {
                MimirButtonAdjustChips(
                    onSmaller = { press(ControllerAction.PAGE_LEFT) },
                    onLarger = { press(ControllerAction.PAGE_RIGHT) },
                    onReset = { press(ControllerAction.SECONDARY) },
                    onDone = { press(ControllerAction.CONFIRM) },
                )
            }
        } else {
            null
        },
    )
}

/** The newest picker state the overlay has handed to its host; see the overlay. Not observed: only the gesture handlers read it. */
private class MimirButtonPickerLatest(var state: MimirButtonPickerState)

/**
 * The stage with palette-token coins: [MimirButtonPickerStageLayout] with [MarkCoin] for the button. The default of
 * [MimirButtonPickerOverlay]'s `stage`, for a host with no native overlay to borrow the real coin from.
 */
@Composable
fun BoxScope.MimirButtonPickerStagePlaceholder(preview: MimirButtonMark?) {
    MimirButtonPickerStageLayout(preview) { mark, scale ->
        MarkCoin(mark?.path, diameter = MimirButtonStageCoinSize * scale, placement = mark?.markPlacement() ?: MimirMarkPlacement.Default)
    }
}

/**
 * The stage's three seats (brief §G.3): the button at its real [MimirButtonStageCoinSize] over the current background,
 * a 2x copy for detail, and a 56 dp copy on a light panel drawn from `textPrimary` standing in for a bright game. The
 * captions are the mockup's. [coin] draws one copy of the button: it is given the previewed mark (null = the rune) and the
 * copy's SCALE (1 or 2) and is laid out in a box of `56 dp * scale`, so the host decides only how the coin is drawn, never
 * where it sits, and the three seats cannot drift from the mockup.
 *
 * The background is the scene WITHOUT its sun (`sceneLight = false`): on a strip the Default theme's sun anchors lower right, on top of the
 * "ON A BRIGHT GAME" line (review 2026-10-13), and this stage is where the button is judged against the background's own colours, not its light.
 *
 * On a stage too narrow for three one-line captions (a 360 dp square, at any font; [stageSeatsAreNarrow]) the seats share the width in proportion to
 * their coins ([stageSeatWeights]) and every caption wraps at its spaces inside its seat -- "ACTUAL SIZE" over "56 DP" -- so no word is cut and the
 * third seat is never left what the first two did not take (it read "ON A" at font 1.3). A wide stage is laid out exactly as it was.
 */
@Composable
fun BoxScope.MimirButtonPickerStageLayout(
    preview: MimirButtonMark?,
    coin: @Composable (mark: MimirButtonMark?, scale: Float) -> Unit,
) {
    val palette = LocalMimirPalette.current
    val fontScale = LocalDensity.current.fontScale
    MimirShelfBackground(modifier = Modifier.fillMaxSize(), palette = palette, sceneLight = false) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val inside = maxWidth.value - 2f * MimirStagePaddingDp
            val narrow = stageSeatsAreNarrow(inside, fontScale)
            val weights = stageSeatWeights(inside)
            Row(
                // A narrow stage is a short one too (a 360 dp square at font 1.3 leaves it about 145 dp, and the 2x copy with its caption is 135), so it keeps
                // the sides' 12 dp and trims the top and bottom to 4.
                Modifier.fillMaxSize().padding(horizontal = MimirStagePaddingDp.dp, vertical = if (narrow) 4.dp else MimirStagePaddingDp.dp),
                horizontalArrangement = if (narrow) Arrangement.spacedBy(MimirStageSeatGapDp.dp) else Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StageSeat(
                    caption = MimirStageCaptionActual,
                    palette = palette,
                    modifier = if (narrow) Modifier.weight(weights[0]) else Modifier,
                    wrapped = narrow,
                ) { StageCoinBox(scale = 1f) { coin(preview, 1f) } }
                StageSeat(
                    caption = MimirStageCaptionDetail,
                    palette = palette,
                    modifier = if (narrow) Modifier.weight(weights[1]) else Modifier,
                    wrapped = narrow,
                ) { StageCoinBox(scale = 2f) { coin(preview, 2f) } }
                StageSeat(
                    caption = MimirStageCaptionBright,
                    palette = palette,
                    modifier = if (narrow) Modifier.weight(weights[2]) else Modifier,
                    wrapped = narrow,
                ) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(palette.textPrimary)
                            .padding(16.dp),
                    ) {
                        StageCoinBox(scale = 1f) { coin(preview, 1f) }
                    }
                }
            }
        }
    }
}

/** The stage's three captions, the mockup's. */
internal const val MimirStageCaptionActual = "ACTUAL SIZE · 56 DP"
internal const val MimirStageCaptionDetail = "2× FOR DETAIL"
internal const val MimirStageCaptionBright = "ON A BRIGHT GAME"

internal const val MimirStagePaddingDp = 12f
internal const val MimirStageSeatGapDp = 8f

/** What each seat's content is wide, in dp: the real button, the 2x copy, and the 56 dp copy on its light panel (56 dp of coin and 16 dp of panel a side). */
private val MimirStageSeatContentDp = floatArrayOf(56f, 112f, 88f)

/** The captions' lengths, for judging whether they fit one line: [MimirStageCaptionActual], [MimirStageCaptionDetail], [MimirStageCaptionBright]. */
private val MimirStageCaptionChars = intArrayOf(19, 13, 16)

/** One caption character in the micro type, in dp at font scale 1: measured off the render with the real face (5.2 to 5.5), taken at the wide end. */
private const val MimirStageCaptionCharDp = 5.5f

/**
 * Whether three seats with one-line captions cannot stand side by side in [availableWidthDp] (the stage's inside width) at [fontScale]: each seat is as wide as its
 * coin or its caption, whichever is wider. A pure estimate with a margin (the caption's width is the one thing not known until it is drawn); either layout is correct
 * where the estimate is close, and only the narrow one is correct below it.
 */
internal fun stageSeatsAreNarrow(availableWidthDp: Float, fontScale: Float): Boolean {
    val scale = fontScale.coerceAtLeast(1f)
    val natural = MimirStageSeatContentDp.indices.sumOf { index ->
        maxOf(MimirStageSeatContentDp[index], MimirStageCaptionChars[index] * MimirStageCaptionCharDp * scale).toDouble()
    }.toFloat() + MimirStageSeatGapDp * 2f
    return natural * 1.08f > availableWidthDp
}

/**
 * The seats' shares of [availableWidthDp] on a narrow stage: each seat is its own content plus an equal part of what the three leave over, so the 2x copy (112 dp)
 * is never squeezed and the other two split the rest. Never below the content: a stage narrower than the three together keeps their proportions.
 */
internal fun stageSeatWeights(availableWidthDp: Float): FloatArray {
    val spare = (availableWidthDp - MimirStageSeatGapDp * 2f - MimirStageSeatContentDp.sum()).coerceAtLeast(0f) / MimirStageSeatContentDp.size
    return FloatArray(MimirStageSeatContentDp.size) { MimirStageSeatContentDp[it] + spare }
}

/** The floating button's size, the size of the stage's actual-size copy (`AndroidCompanionOverlayController`'s 56 dp). */
val MimirButtonStageCoinSize: Dp = 56.dp

@Composable
internal fun StageCoinBox(scale: Float, content: @Composable () -> Unit) {
    Box(Modifier.size(MimirButtonStageCoinSize * scale), contentAlignment = Alignment.Center) { content() }
}

/**
 * One seat: the coin, and its caption under it. [wrapped] lays the caption out for a seat with a width of its own (a narrow stage): it wraps at its spaces, a
 * " · " starts a new line rather than ending one on a dangling dot, and it is cut at a WORD only past two lines -- never inside one. Not wrapped, it is the one
 * line it has always been.
 */
@Composable
internal fun StageSeat(
    caption: String,
    palette: MimirThemePalette,
    modifier: Modifier = Modifier,
    wrapped: Boolean = false,
    content: @Composable () -> Unit,
) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (wrapped) 4.dp else 8.dp),
    ) {
        content()
        if (wrapped) {
            // The whole caption is the one thing a screen reader reads, whatever lines it is drawn on.
            Column(
                Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = caption },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                caption.split(" · ").forEach { part ->
                    MimirWordEllipsisText(
                        text = part,
                        style = MimirType.MicroStatus,
                        color = palette.textSecondary,
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 2,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            MimirChromeText(caption, style = MimirType.MicroStatus, color = palette.textSecondary, maxLines = 1)
        }
    }
}

/** The diameter of the coin face a saved mark is clipped to on a coin of [diameter]: the live button's 26.125 dp radius over its 56 dp, scaled. */
internal fun markFaceOf(diameter: Dp): Dp = diameter * (2f * MimirMarkReferenceFaceRadiusDp / MimirMarkReferenceCoinDp)

/**
 * A coin: the button's disc and rim from the palette, carrying the rune -- or, for a saved mark, its mask struck in the
 * palette's own metal ramp ([StruckMarkThumbnail]), the Compose twin of the button's `SRC_IN` so a tile, the Settings row
 * and the stage all read as the same gold mark.
 *
 * A saved mark is drawn with the live button's geometry, scaled to this coin : the mask trimmed to its ink
 * ([rememberMimirMarkThumbnail]), fitted by aspect in the 44 dp box of the 56 dp coin (`mimirMarkBoxRect`, scaled) and clipped to the coin face of [markFaceOf], so an image
 * with padding of its own is as big on a tile as on the button and nothing crosses the rim. Where in that box, and how large, is the
 * player's own [placement] , through the same `mimirMarkDrawRect` the live button's coin calls. The rune keeps
 * its glyph.
 */
@Composable
fun MarkCoin(
    path: String?,
    diameter: Dp,
    modifier: Modifier = Modifier,
    placement: MimirMarkPlacement = MimirMarkPlacement.Default,
) {
    val palette = LocalMimirPalette.current
    Box(
        modifier
            .size(diameter)
            .clip(CircleShape)
            .background(palette.surfaceHigh)
            .border((diameter.value / 28f).coerceAtLeast(1f).dp, palette.accent, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (path != null) {
            // The face clip is the coin's and is the same circle wherever the image lands.
            Box(Modifier.size(markFaceOf(diameter)).clip(CircleShape), contentAlignment = Alignment.Center) {
                StruckMarkThumbnail(path, diameter, placement)
            }
        } else {
            Text(
                "ᛗ",
                color = palette.accentBright,
                fontSize = (diameter.value * 0.5f).sp,
                lineHeight = (diameter.value * 0.55f).sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * A saved mark's mask with the palette's console-art metal poured through it: the four `iconGrad*` stops at 0 / 0.40 /
 * 0.62 / 1 -- the ramp `CompanionMenuCoinView` strikes the live button with -- composited `SrcIn` in a layer of its own.
 * The file is white ink on alpha, so without this a tile would show a plain white glyph.
 *
 * The thumbnail fills the face the caller clipped it to and draws the mask at the rect `mimirMarkDrawRect` gives for [placement] on a coin
 * of [diameter] (the face is centred on the coin); the ramp runs the height of the placed box (`mimirMarkBoxRect`), as the live button's
 * does, so the top light follows the image. The layer is an explicit `saveLayer`, as the live button's coin takes, not an offscreen
 * `graphicsLayer`: a software canvas (the JVM's renders, a capture) does not isolate the latter, and `SrcIn` then paints the metal over the
 * whole coin instead of through the mask.
 */
@Composable
private fun StruckMarkThumbnail(path: String, diameter: Dp, placement: MimirMarkPlacement) {
    val palette = LocalMimirPalette.current
    val art = rememberMimirMarkThumbnail(path)
    val bitmap = art.bitmap
    if (bitmap != null) {
        val layerPaint = remember { Paint() }
        Canvas(Modifier.fillMaxSize()) {
            // A cold mark fades in from nothing over FocusMs (Q13): the layer is composited at the fade's alpha, read in this draw only.
            layerPaint.alpha = art.alpha
            val coin = diameter.toPx()
            val shift = (size.width - coin) / 2f
            val rect = mimirMarkDrawRect(coin, bitmap.width, bitmap.height, placement)
            val left = (rect.left + shift).roundToInt()
            val top = (rect.top + shift).roundToInt()
            val box = mimirMarkBoxRect(coin, placement)
            val metal = Brush.verticalGradient(
                0f to palette.iconGradLight,
                0.40f to palette.iconGradHot,
                0.62f to palette.iconGradMid,
                1f to palette.iconGradDeep,
                startY = box.top + shift,
                endY = box.bottom + shift,
            )
            drawIntoCanvas { canvas ->
                canvas.saveLayer(Rect(0f, 0f, size.width, size.height), layerPaint)
                drawImage(
                    image = bitmap,
                    dstOffset = IntOffset(left, top),
                    dstSize = IntSize(
                        ((rect.right + shift).roundToInt() - left).coerceAtLeast(1),
                        ((rect.bottom + shift).roundToInt() - top).coerceAtLeast(1),
                    ),
                    filterQuality = FilterQuality.Medium,
                )
                drawRect(brush = metal, blendMode = BlendMode.SrcIn)
                canvas.restore()
            }
        }
    }
}

fun dispatchMimirButtonPicker(
    authority: FocusAuthority,
    state: MimirButtonPickerState,
    action: ControllerAction,
    onTransition: (MimirButtonPickerTransition) -> Unit,
): Boolean {
    when (action) {
        ControllerAction.NAV_UP, ControllerAction.NAV_DOWN,
        ControllerAction.NAV_LEFT, ControllerAction.NAV_RIGHT,
        // Adjust mode keeps focus where it is (on the image's tile) and spends the D-pad on the image: each press, and each repeat the
        // input authority emits while the direction is held, is one 0.5 dp step.
        -> if (state.adjust != null) {
            val step = mimirButtonPickerTransition(state, action, authority.state.value.focused)
            // The tick and the haptic are for a press that visibly moved the image; at the limit it did not (BM03).
            if (step.state !== state) MimirNavFeedback.consumed()
            onTransition(step)
        } else if (authority.moveFocus(action)) {
            MimirNavFeedback.consumed()
        }
        // L1, R1 and X in adjust mode step the image's size or put it back. The router ticks a non-directional press
        // only when its surface says a value changed, so say it when the placement did, exactly as the D-pad's step above does: a size held at 60% or 160%,
        // or a Reset on a centred 100% image, changes nothing and stays silent (BM03).
        ControllerAction.PAGE_LEFT, ControllerAction.PAGE_RIGHT, ControllerAction.SECONDARY -> {
            val step = mimirButtonPickerTransition(state, action, authority.state.value.focused)
            if (state.adjust != null && step.state !== state) MimirNavFeedback.stepped()
            onTransition(step)
        }
        // A saves and uses the image and B leaves: both end adjust mode, and neither plays a sound, as A on the other Appearance pickers plays none.
        else -> onTransition(mimirButtonPickerTransition(state, action, authority.state.value.focused))
    }
    return true
}

/** The saved marks' labels, in order: "Your mark 1", "Your mark 2", ... */
fun mimirButtonMarkLabel(index: Int): String = "Your mark ${index + 1}"

/** The row's tiny coin size on Settings › Appearance. */
val MimirButtonRowCoinSize: Dp = 20.dp
