package com.gruning.mimir.rewrite.feature.settings

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.input.MimirNavFeedback
import com.gruning.mimir.rewrite.core.input.isLitFocusIn
import com.gruning.mimir.rewrite.core.input.mimirActivatable
import com.gruning.mimir.rewrite.core.input.mimirFocusable
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirRingShape
import com.gruning.mimir.rewrite.core.ui.MimirSceneGround
import com.gruning.mimir.rewrite.core.ui.MimirSceneGroundBlur
import com.gruning.mimir.rewrite.core.ui.MimirSceneGroundDim
import com.gruning.mimir.rewrite.core.ui.MimirShelfBackground
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import com.gruning.mimir.rewrite.core.ui.MimirTypeStep
import com.gruning.mimir.rewrite.core.ui.mimirReducedMotion
import com.gruning.mimir.rewrite.core.ui.mimirSelectionRing
import com.gruning.mimir.rewrite.core.ui.theme.MimirType

/**
 * The BACKGROUND picker, on the shared [AppearanceImagePickerOverlay]:
 * `ᛗ · BACKGROUND`, "For <Theme> · behind your shelves", the real [MimirShelfBackground] on the stage with the
 * previewed image, the tiles (Atmosphere · saved images · + Add image), then the Dim and Blur rows, every step always available:
 * there is no readability floor, so the stored Dim is what draws.
 *
 * Every rule of the picker lives in the pure [backgroundPickerTransition] (A / X / B) and [backgroundPickerOnFocus]
 * (what the stage previews), so they are pinned on the JVM without a graphics stack:
 *  - focus starts on the tile in use; moving across tiles or steps changes ONLY the stage -- focus previews, and
 *    nothing about focus changes what is kept;
 *  - the selected marker on a step reads what is KEPT (the committed ground's dim and blur), never the preview;
 *  - A uses the focused tile at the kept steps, or keeps the focused Dim or Blur step -- that row only, the other row
 *    stays at its kept step; A on + Add image opens the import;
 *  - focus leaving a row without A returns the stage to the kept step of that row; a tile previews at the kept steps;
 *  - X removes a saved image no theme uses; the current choice and another theme's are refused with the copy;
 *  - B leaves, and reverts anything not kept -- nothing is committed but by A, so leaving is the revert;
 *  - Y does nothing.
 */
val BackgroundPickerScope = FocusScope("appearance/backgroundPicker")

internal const val BackgroundPickerTitle = "ᛗ · BACKGROUND"
internal const val BackgroundDefaultLabel = "Atmosphere"
internal const val BackgroundNoteRemoveCurrent = "Switch first before removing the current choice."
internal const val BackgroundNoteInUse = "In use"
internal const val BackgroundNoteRemoved = "Removed"
internal const val BackgroundNoteUnavailable = "This image can’t be shown. X removes it."

internal fun backgroundNoteUsedBy(themeLabel: String): String = "$themeLabel uses this image. Switch $themeLabel first."

/** The picker's subline: "For <Theme> · behind your shelves", and "on both screens" on a dual-screen device only. */
internal fun backgroundPickerSubtitle(themeLabel: String, dualScreen: Boolean): String =
    if (dualScreen) "For $themeLabel · behind your shelves, on both screens" else "For $themeLabel · behind your shelves"

/** A saved background as the picker sees it. [usedBy] names the OTHER themes committed to it; [path] null = unavailable. */
data class BackgroundPickerImage(
    val id: String,
    val label: String,
    val path: String?,
    val width: Int,
    val height: Int,
    val p90: Float,
    val usedBy: List<String> = emptyList(),
) {
    val available: Boolean get() = path != null
}

/**
 * The picker's whole state. [current] is the committed choice for [themeId]: the one thing A writes and the one thing
 * the rows' selected markers read. [previewId] is what the stage shows (null = Atmosphere); [previewDim] and
 * [previewBlur] are the steps focus is previewing on their rows, null when focus is not on that row -- the stage then
 * shows the kept step. Focus writes the preview fields only; nothing about focus changes [current].
 */
data class BackgroundPickerState(
    val themeId: MimirThemeId,
    val current: MimirSceneGround?,
    val saved: List<BackgroundPickerImage>,
    val previewId: String?,
    val previewDim: MimirSceneGroundDim? = null,
    val previewBlur: MimirSceneGroundBlur? = null,
    val note: String? = null,
    /**
     * True from A on + Upload until focus is on that tile again: the picker has left for the file picker (which covers it and gives it back, remounted, when it closes) and
     * lands on the tile it left from, not on the tile in use ([initialFocusKey]). Cleared by focus landing on + Upload, and by an import that added a tile.
     */
    val importOpen: Boolean = false,
) {
    /** The kept dim: the committed ground's, or the step a tile is first kept at while Atmosphere is in use. */
    val keptDim: MimirSceneGroundDim get() = current?.dim ?: MimirSceneGroundDim.MEDIUM

    /** The kept blur: the committed ground's, or the step a tile is first kept at while Atmosphere is in use. */
    val keptBlur: MimirSceneGroundBlur get() = current?.blur ?: MimirSceneGroundBlur.OFF

    /** The dim the stage shows: the step focus previews on the Dim row, else the kept one. */
    val dim: MimirSceneGroundDim get() = previewDim ?: keptDim

    /** The blur the stage shows: the step focus previews on the Blur row, else the kept one. */
    val blur: MimirSceneGroundBlur get() = previewBlur ?: keptBlur
}

sealed interface BackgroundPickerCommand {
    /** Persist [ground] (null = Atmosphere) for the theme; the host commits and the shelves cut to it. */
    data class Commit(val ground: MimirSceneGround?) : BackgroundPickerCommand

    /** Delete the saved image [id]; the host removes the file and re-reads the saved list. */
    data class Remove(val id: String) : BackgroundPickerCommand

    /** Open the import landing step for a background. */
    data object Import : BackgroundPickerCommand

    /** Leave the picker. */
    data object Dismiss : BackgroundPickerCommand
}

data class BackgroundPickerTransition(val state: BackgroundPickerState, val command: BackgroundPickerCommand? = null)

// ── focus keys ─────────────────────────────────────────────────────────────────────────────────────────────────

private const val KeyRoot = "appearance/background"
/** The Atmosphere tile's key: where the host lands focus after a removal, and the default tile's identity. */
val BackgroundAtmosphereKey = FocusKey("$KeyRoot/tile/atmosphere")
/** The + Upload tile's key: where the picker lands focus when the file picker it opened closes (the tile it left from). */
val BackgroundAddKey = FocusKey("$KeyRoot/tile/add")
/** A saved image's tile key: the host lands focus here once an import has added it. */
fun backgroundTileKey(id: String): FocusKey = FocusKey("$KeyRoot/tile/$id")
internal fun backgroundDimKey(dim: MimirSceneGroundDim): FocusKey = FocusKey("$KeyRoot/dim/${dim.name}")
internal fun backgroundBlurKey(blur: MimirSceneGroundBlur): FocusKey = FocusKey("$KeyRoot/blur/${blur.name}")

internal fun backgroundDimForKey(key: FocusKey?): MimirSceneGroundDim? =
    MimirSceneGroundDim.entries.firstOrNull { backgroundDimKey(it) == key }

internal fun backgroundBlurForKey(key: FocusKey?): MimirSceneGroundBlur? =
    MimirSceneGroundBlur.entries.firstOrNull { backgroundBlurKey(it) == key }

internal fun BackgroundPickerState.savedForKey(key: FocusKey?): BackgroundPickerImage? =
    saved.firstOrNull { backgroundTileKey(it.id) == key }

/** The footer for the focused tile: X REMOVE on any saved image, A UPLOAD on Add (it opens the file picker), A USE and no X on Atmosphere. */
internal fun BackgroundPickerState.controlHint(focused: FocusKey?): String = when {
    savedForKey(focused) != null -> AppearancePickerControlHint
    focused == BackgroundAddKey -> AppearancePickerControlHintUpload
    else -> AppearancePickerControlHintNoRemove
}

// ── the pure rules ─────────────────────────────────────────────────────────────────────────────────────────────

/** The state a picker opens on: the tile in use previewed at its kept dim and blur (the defaults for Atmosphere). */
fun backgroundPickerInitialState(
    themeId: MimirThemeId,
    current: MimirSceneGround?,
    saved: List<BackgroundPickerImage>,
): BackgroundPickerState = BackgroundPickerState(
    themeId = themeId,
    current = current,
    saved = saved,
    previewId = current?.id,
)

/**
 * Where focus lands when the picker mounts: + Upload while [BackgroundPickerState.importOpen] (the picker comes back from the file picker on the tile it left from); otherwise the tile the stage previews when that is a saved tile that is NOT the current one -- which, with [backgroundPickerTransition] settling the preview
 * back onto the current tile the moment the import opens, is exactly the tile an import has just added -- otherwise the tile in use (Atmosphere when none).
 * Cancelling the file picker therefore returns focus to + Upload, and finishing it lands on the new tile.
 */
fun BackgroundPickerState.initialFocusKey(): FocusKey {
    if (importOpen) return BackgroundAddKey
    val previewed = saved.firstOrNull { it.id == previewId && it.id != current?.id }
    return previewed?.let { backgroundTileKey(it.id) } ?: current?.let { backgroundTileKey(it.id) } ?: BackgroundAtmosphereKey
}

/**
 * The state once an import has landed: the re-read saved list, the stage previewing the NEW tile (an import that added
 * nothing -- refused, or already saved elsewhere -- leaves the preview alone, and focus on + Upload, where it was) and the note. The host then lands focus on
 * [backgroundTileKey] of the new id with the shared retrying `landPickerFocus`; the tile registers a frame after this
 * write, so a one-shot landing is dropped.
 */
fun BackgroundPickerState.withImportLanded(
    saved: List<BackgroundPickerImage>,
    importedId: String?,
    note: String?,
): BackgroundPickerState = copy(saved = saved, previewId = importedId ?: previewId, note = note, importOpen = importOpen && importedId == null)

/**
 * The state once the file picker the player opened from + Upload has closed with nothing chosen and the picker never left the screen (the system picker, which covers it
 * without remounting it): + Upload is no longer where a remount should land. The in-app file picker needs no such call: the picker remounts and lands on + Upload, which ends it.
 */
fun BackgroundPickerState.withImportCancelled(): BackgroundPickerState = copy(importOpen = false)

/** The image the stage shows, or null for Atmosphere. Unavailable images preview as Atmosphere. */
fun BackgroundPickerState.previewImage(): BackgroundPickerImage? =
    saved.firstOrNull { it.id == previewId }?.takeIf { it.available }

/** The ground the stage previews: the previewed image at the rows' steps, exactly as stored (no floor raises the dim). */
fun BackgroundPickerState.previewGround(): MimirSceneGround? {
    val image = previewImage() ?: return null
    return MimirSceneGround(
        id = image.id,
        path = image.path ?: return null,
        width = image.width,
        height = image.height,
        p90 = image.p90,
        dim = dim,
        blur = blur,
    )
}

/**
 * What focus does: a tile previews its image at the KEPT steps (Atmosphere clears the preview), a Dim or Blur step
 * previews that step on the previewed image, and leaving a row drops that row's preview so the stage returns to the
 * kept step. Nothing else changes; nothing commits -- [BackgroundPickerState.current] is A's alone.
 */
fun backgroundPickerOnFocus(state: BackgroundPickerState, focused: FocusKey?): BackgroundPickerState {
    if (focused == null) return state
    if (focused == BackgroundAtmosphereKey) return state.copy(previewId = null, previewDim = null, previewBlur = null)
    state.savedForKey(focused)?.let { image -> return state.copy(previewId = image.id, previewDim = null, previewBlur = null) }
    backgroundDimForKey(focused)?.let { dim -> return state.copy(previewDim = dim, previewBlur = null) }
    backgroundBlurForKey(focused)?.let { blur -> return state.copy(previewDim = null, previewBlur = blur) }
    // + Add image: the stage keeps the last tile hovered (A settles it), the rows return to their kept steps.
    if (focused == BackgroundAddKey) return state.copy(previewDim = null, previewBlur = null, importOpen = false)
    return state
}

/** A / X / B on the focused key. NAV_* are the host's (`authority.moveFocus`); Y and the rest do nothing. */
fun backgroundPickerTransition(
    state: BackgroundPickerState,
    action: ControllerAction,
    focused: FocusKey?,
): BackgroundPickerTransition = when (action) {
    ControllerAction.CONFIRM -> confirm(state, focused)
    ControllerAction.SECONDARY -> remove(state, focused)
    ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK -> BackgroundPickerTransition(state, BackgroundPickerCommand.Dismiss)
    else -> BackgroundPickerTransition(state)
}

private fun confirm(state: BackgroundPickerState, focused: FocusKey?): BackgroundPickerTransition {
    if (focused == BackgroundAddKey) {
        // The picker leaves for the image browser and remounts when it closes, landing on + Upload (importOpen). Settle the preview back on the
        // committed tile first: moving right along the tiles to reach + Upload leaves the LAST tile hovered, and the stage must keep showing the
        // tile in use while focus sits on Upload, not whichever one the pad passed on the way.
        return BackgroundPickerTransition(
            state.copy(previewId = state.current?.id, previewDim = null, previewBlur = null, importOpen = true),
            BackgroundPickerCommand.Import,
        )
    }
    if (focused == BackgroundAtmosphereKey) {
        return BackgroundPickerTransition(
            state.copy(current = null, previewId = null, previewDim = null, previewBlur = null, note = BackgroundNoteInUse),
            BackgroundPickerCommand.Commit(null),
        )
    }
    state.savedForKey(focused)?.let { image ->
        if (!image.available) return BackgroundPickerTransition(state.copy(note = BackgroundNoteUnavailable))
        // A tile is kept at the kept steps: focus is on the tile, so no step is being previewed.
        return commitPreview(state.copy(previewId = image.id, previewDim = null, previewBlur = null))
    }
    val dim = backgroundDimForKey(focused)
    val blur = backgroundBlurForKey(focused)
    if (dim != null || blur != null) {
        // A keeps the focused step for ITS row; the other row's step is the kept one, whatever focus passed over
        // there on the way. The step is committed with the previewed image.
        val stepped = state.copy(previewDim = dim, previewBlur = blur)
        if (stepped.previewImage() == null) return BackgroundPickerTransition(stepped) // Atmosphere has no dim or blur.
        return commitPreview(stepped)
    }
    return BackgroundPickerTransition(state)
}

private fun commitPreview(state: BackgroundPickerState): BackgroundPickerTransition {
    val ground = state.previewGround() ?: return BackgroundPickerTransition(state)
    // The stored dim is exactly the step chosen (no floor raises it). Once kept, the
    // rows sit on the kept steps: the previews are spent.
    return BackgroundPickerTransition(
        state.copy(current = ground, previewDim = null, previewBlur = null, note = BackgroundNoteInUse),
        BackgroundPickerCommand.Commit(ground),
    )
}

private fun remove(state: BackgroundPickerState, focused: FocusKey?): BackgroundPickerTransition {
    val image = state.savedForKey(focused) ?: return BackgroundPickerTransition(state)
    if (state.current?.id == image.id) return BackgroundPickerTransition(state.copy(note = BackgroundNoteRemoveCurrent))
    image.usedBy.firstOrNull()?.let { other -> return BackgroundPickerTransition(state.copy(note = backgroundNoteUsedBy(other))) }
    return BackgroundPickerTransition(
        state.copy(
            saved = state.saved.filterNot { it.id == image.id },
            previewId = state.previewId.takeIf { it != image.id },
            note = BackgroundNoteRemoved,
        ),
        BackgroundPickerCommand.Remove(image.id),
    )
}

/** The tiles, in order: Atmosphere (DEFAULT) · the saved images (CURRENT or SAVED) · + Add image. */
internal fun BackgroundPickerState.tiles(): List<AppearancePickerTile> = buildList {
    add(AppearancePickerTile.Default(BackgroundAtmosphereKey, BackgroundDefaultLabel))
    saved.forEach { image ->
        add(
            AppearancePickerTile.Saved(
                key = backgroundTileKey(image.id),
                id = image.id,
                label = image.label,
                path = image.path,
                current = current?.id == image.id,
                usedBy = image.usedBy,
            ),
        )
    }
    add(AppearancePickerTile.Add(BackgroundAddKey))
}

// ── the overlay ────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * @param state the picker's state, owned by the host; [onFocus] and [onTransition] hand every change back.
 * @param dualScreen adds "on both screens" to the subline (one image covers both).
 * @param stageChrome what stands ON the previewed ground: the host's real shelf pieces (the app composes the Library's
 *   header and the player's own platform tiles, scaled to the stage), or null for the drawn silhouettes. It reads no
 *   preview, so a focus move recomposes the ground's cross-fade alone; the ground itself is always the real
 *   [MimirShelfBackground] bake at stage size, so what the chrome stands on is what the shelf will draw.
 */
@Composable
fun BackgroundPickerOverlay(
    authority: FocusAuthority,
    state: BackgroundPickerState,
    dualScreen: Boolean,
    onFocus: (FocusKey?) -> Unit,
    onTransition: (BackgroundPickerTransition) -> Unit,
    presenceManagedExternally: Boolean = false,
    stageChrome: (@Composable BoxScope.() -> Unit)? = null,
) {
    val palette = LocalMimirPalette.current
    val focus by authority.state.collectAsState()
    // The stage follows focus (a D-pad move or a tap): the one place a preview is produced.
    LaunchedEffect(authority) {
        authority.state.collect { snapshot -> if (snapshot.scope == BackgroundPickerScope) onFocus(snapshot.focused) }
    }
    val preview = state.previewGround()
    val reduced = mimirReducedMotion()
    AppearanceImagePickerOverlay(
        authority = authority,
        scope = BackgroundPickerScope,
        title = BackgroundPickerTitle,
        subtitle = backgroundPickerSubtitle(state.themeId.label, dualScreen),
        tiles = state.tiles(),
        defaultIsCurrent = state.current == null,
        initialFocusKey = state.initialFocusKey(),
        note = state.note,
        onActivateTile = { tile -> onTransition(backgroundPickerTransition(state, ControllerAction.CONFIRM, tile.key)) },
        onRemoveTile = { tile -> onTransition(backgroundPickerTransition(state, ControllerAction.SECONDARY, tile.key)) },
        onDismiss = { onTransition(BackgroundPickerTransition(state, BackgroundPickerCommand.Dismiss)) },
        stage = {
            // The change cross-fades over SceneMs on the stage as a compositor alpha between two ground
            // layers; under reduced motion it cuts. Each layer is the real shelf background at stage size. The chrome
            // stands ABOVE the cross-fade, static: it is drawn once, not twice at partial alpha, and no alpha layer
            // ever holds it (a layer below 1 clips a tile ring's bloom).
            Crossfade(
                targetState = preview,
                animationSpec = if (reduced) snap<Float>() else tween<Float>(MimirMotion.SceneMs, easing = MimirMotion.Easing),
                label = "backgroundStage",
            ) { ground ->
                MimirShelfBackground(modifier = Modifier.fillMaxSize(), palette = palette, ground = ground) {}
            }
            if (stageChrome != null) stageChrome() else BackgroundStageChrome(palette, state.themeId.label)
        },
        // Atmosphere's tile is the theme's own scene at tile size -- the same renderer as the stage.
        defaultTile = { MimirShelfBackground(modifier = Modifier.fillMaxSize(), palette = palette, ground = null) {} },
        thumbnail = { tile -> tile.path?.let { AppearanceThumbnailImage(it) } },
        // A photo fills its tile, so the state word stands on it as a small chip; the Mimir button's coin has room of its own and its word has a band.
        artFillsTile = true,
        rowCount = 2,
        rows = { sideBySide ->
            if (sideBySide) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.weight(1f).fillMaxHeight()) { DimRow(authority, state, focus, onTransition) }
                    Box(Modifier.weight(1f).fillMaxHeight()) { BlurRow(authority, state, focus, onTransition) }
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.fillMaxWidth().weight(1f)) { DimRow(authority, state, focus, onTransition) }
                    Box(Modifier.fillMaxWidth().weight(1f)) { BlurRow(authority, state, focus, onTransition) }
                }
            }
        },
        controlHint = { focused -> state.controlHint(focused) },
        presenceManagedExternally = presenceManagedExternally,
    )
}

/**
 * The stage's fallback chrome over the preview, for a host that supplies none: the Library shelf's eyebrow, three tile
 * silhouettes and a title in the theme's own text tokens, so the player judges text
 * over this image. The app supplies the real pieces through [BackgroundPickerOverlay]'s `stageChrome`.
 */
@Composable
private fun BackgroundStageChrome(palette: MimirThemePalette, themeLabel: String) {
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            MimirChromeText("CHOOSE A PLATFORM", style = MimirType.RunicEyebrowCompact, color = palette.textSecondary, maxLines = 1)
            MimirChromeText(
                "PREVIEW",
                style = MimirType.MicroStatus,
                color = palette.textSecondary,
                modifier = Modifier
                    .border(1.dp, palette.outline, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                maxLines = 1,
            )
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
            StageTile(palette, focused = false)
            StageTile(palette, focused = true)
            StageTile(palette, focused = false)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            themeLabel,
            style = MimirType.RowTitle,
            color = palette.textPrimary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Your shelf",
            style = MimirType.RowSupport.copy(fontSize = MimirTypeStep.Label, lineHeight = 12.sp),
            color = palette.textSecondary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.weight(0.6f))
    }
}

@Composable
private fun StageTile(palette: MimirThemePalette, focused: Boolean) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .size(width = if (focused) 44.dp else 36.dp, height = if (focused) 44.dp else 36.dp)
            .clip(shape)
            .background(palette.surfaceRaised.copy(alpha = 0.82f))
            .border(1.dp, if (focused) palette.tileFocusRing else palette.outline, shape),
    )
}

@Composable
private fun DimRow(
    authority: FocusAuthority,
    state: BackgroundPickerState,
    focus: com.gruning.mimir.rewrite.core.input.FocusState,
    onTransition: (BackgroundPickerTransition) -> Unit,
) {
    StepRow(label = "DIM") {
        MimirSceneGroundDim.entries.forEach { step ->
            StepBox(
                key = backgroundDimKey(step),
                label = step.label,
                // The marker reads what is kept; focus previews on the stage alone.
                selected = state.keptDim == step,
                enabled = true,
                spoken = "Dim ${step.label}",
                focused = authority.isLitFocusIn(BackgroundPickerScope, backgroundDimKey(step), focus),
                authority = authority,
                onActivate = {
                    onTransition(backgroundPickerTransition(state, ControllerAction.CONFIRM, backgroundDimKey(step)))
                },
            )
        }
    }
}

@Composable
private fun BlurRow(
    authority: FocusAuthority,
    state: BackgroundPickerState,
    focus: com.gruning.mimir.rewrite.core.input.FocusState,
    onTransition: (BackgroundPickerTransition) -> Unit,
) {
    StepRow(label = "BLUR") {
        MimirSceneGroundBlur.entries.forEach { step ->
            StepBox(
                key = backgroundBlurKey(step),
                label = step.label,
                selected = state.keptBlur == step,
                enabled = true,
                spoken = "Blur ${step.label}",
                focused = authority.isLitFocusIn(BackgroundPickerScope, backgroundBlurKey(step), focus),
                authority = authority,
                onActivate = {
                    onTransition(backgroundPickerTransition(state, ControllerAction.CONFIRM, backgroundBlurKey(step)))
                },
            )
        }
    }
}

@Composable
private fun StepRow(label: String, content: @Composable () -> Unit) {
    val palette = LocalMimirPalette.current
    // Siblings sit MimirSpacing.sm apart: the ring's inner and middle strokes need that much clear (MimirSelectionRing).
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MimirSpacing.sm)) {
        MimirChromeText(
            label,
            style = MimirType.RunicEyebrowCompact,
            color = palette.textSecondary,
            modifier = Modifier.width(44.dp),
            maxLines = 1,
        )
        content()
    }
}

/**
 * One step of a row: a rounded RECTANGLE, [AppearancePickerStepHeight] tall on [AppearancePickerStepCorner], centred in a
 * [MimirSpacing.minTarget] touch target. The OUTER box is the target: it
 * registers the focus key and takes the tap. The INNER box is the step the player sees, and the product ring sits on
 * THAT box -- `mimirSelectionRing(Card(corner))` before its clip, so the ring hugs the rectangle it clips rather than
 * floating around the target -- with its accent fill and border when selected; focus is the ring alone.
 *
 * A disabled step registers NO focus key, so the D-pad steps over it exactly as the colour dial skips a
 * guardrail-failed detent, and its tap does nothing but announce why. Its dim is the ring's own rest dim
 * ([AppearancePickerStepDisabledAlpha]), never an `alpha` modifier of its own: an alpha layer over the ring composites
 * at the element's bounds and would cut the ring.
 */
@Composable
private fun StepBox(
    key: FocusKey,
    label: String,
    selected: Boolean,
    enabled: Boolean,
    spoken: String,
    focused: Boolean,
    authority: FocusAuthority,
    onActivate: () -> Unit,
) {
    val palette = LocalMimirPalette.current
    val shape = RoundedCornerShape(AppearancePickerStepCorner)
    val focusable = if (enabled) Modifier.mimirFocusable(authority, key, BackgroundPickerScope) else Modifier
    Box(
        Modifier
            .height(MimirSpacing.minTarget)
            .then(focusable)
            .mimirActivatable(
                label = spoken,
                enabled = true,
                contentDescription = spoken,
                authority = if (enabled) authority else null,
                key = if (enabled) key else null,
                onActivate = { if (enabled) onActivate() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .height(AppearancePickerStepHeight)
                .mimirSelectionRing(
                    focused = focused && enabled,
                    identity = key,
                    shape = MimirRingShape.Card(AppearancePickerStepCorner),
                    restingAlpha = if (enabled) 1f else AppearancePickerStepDisabledAlpha,
                )
                .clip(shape)
                .background(if (selected) palette.accent.copy(alpha = 0.22f) else palette.surfaceRaised)
                .border(1.dp, if (selected) palette.accentBright else palette.outline, shape)
                .padding(horizontal = MimirSpacing.sm),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = MimirType.RowSupport.copy(fontSize = MimirTypeStep.Label, lineHeight = 13.sp),
                color = if (selected) palette.accentBright else palette.textPrimary,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/**
 * The ONE input entry for an open picker (hard modal): NAV_* move the authority's focus across tiles and steps (Down
 * from the tiles lands on Dim, then Blur); everything else goes through [backgroundPickerTransition].
 */
fun dispatchBackgroundPicker(
    authority: FocusAuthority,
    state: BackgroundPickerState,
    action: ControllerAction,
    onTransition: (BackgroundPickerTransition) -> Unit,
): Boolean {
    when (action) {
        ControllerAction.NAV_UP, ControllerAction.NAV_DOWN,
        ControllerAction.NAV_LEFT, ControllerAction.NAV_RIGHT,
        -> if (authority.moveFocus(action)) MimirNavFeedback.consumed()
        else -> onTransition(backgroundPickerTransition(state, action, authority.state.value.focused))
    }
    return true
}

/** The saved images' labels, in order: "Your image 1", "Your image 2", ... */
fun backgroundImageLabel(index: Int): String = "Your image ${index + 1}"

/** The remembered tiles' thumbnails are keyed by path; a removed image's tile leaves composition with its key. */
internal fun BackgroundPickerState.thumbnailPaths(): List<String> = saved.mapNotNull { it.path }
