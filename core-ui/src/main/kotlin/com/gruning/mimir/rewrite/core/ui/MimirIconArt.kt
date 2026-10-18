package com.gruning.mimir.rewrite.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * Mimir Alpha :core-ui — the STAVE icon set, Compose face. GENERATED, do not hand-edit.
 *
 * Source of truth: `tools/mimir-icon-set/icons.json`. Regenerate both faces after any edit:
 *   python3 tools/mimir-icon-set/generate_compose.py      → this file
 *   python3 tools/mimir-icon-set/generate_drawables.py    → res/drawable/mimir_icon_*.xml
 *
 * WHY TWO FACES. The drawables are single-tint assets for XML call sites. A mark has TWO inks:
 * the OUTLINE takes the theme accent and the CELL — the one filled element per mark — takes
 * accentBright on focus, danger on failure, or a dim bronze at rest. One `android:tint` cannot
 * express that, so anything that changes state uses [MimirIconMark] and reads both colours from
 * [LocalMimirPalette]. Static XML chrome may keep the drawable.
 *
 * PALETTE. No hex in this file. Callers pass [ink] and [cellInk] from the palette; the defaults
 * are the palette's own accent and accentBright.
 *
 * SIZE. Marks are authored on a 24-unit grid with a 20-unit live area. Below 24.dp the geometry
 * is unchanged but the stroke is widened by [MimirIconArt.SmallStrokeBoost] so the silhouette
 * survives — the design law is that detail is DELETED, not scaled, and at this size the set has no
 * detail layer left to delete. The boost is decided from the edge the mark is ACTUALLY drawn at,
 * not from a parameter, so a caller that sizes the mark through [Modifier] still gets it.
 */
object MimirIconArt {
    /** Authoring grid. Every path below is expressed in this box. */
    const val ViewportUnits: Float = 24f

    /** Stroke of a mark's outline, in authoring units. */
    const val StrokeUnits: Float = 1.5f

    /** EMPTY's cell is an unfilled recess; its wall is thinner than the outline it sits inside. */
    const val HollowCellStrokeUnits: Float = 1.2f

    /** Below this the outline thickens so the mark holds together on a handheld at arm's length. */
    val SmallSizeThreshold: Dp = 24.dp
    const val SmallStrokeBoost: Float = 1.18f

    /** The outline never takes full ink; the cell is what the eye lands on. */
    const val OutlineAlpha: Float = 0.82f
}

/**
 * One mark of the STAVE set.
 *
 * [outline] and [dashed] are stroked; [cell] is the single filled element that carries state.
 * [hollowCell] marks the one state — EMPTY — whose cell is an unfilled recess.
 */
enum class MimirIcon(
    internal val outline: String,
    internal val cell: String,
    internal val dashed: String,
    internal val hollowCell: Boolean,
    /** The accessible name. Never rendered; always announced. */
    val label: String,
) {
    /** Home */
    HOME(
        outline = "M3 11 L12 4 L21 11 M5.5 10.4 V20 H18.5 V10.4",
        cell = "M10.5 14.5 H13.5 V20 H10.5 Z",
        dashed = "",
        hollowCell = false,
        label = "Home",
    ),
    /** Library */
    LIBRARY(
        outline = "M3.5 7 H6.75 V20 H3.5 Z M9.5 3.5 H14.5 V20 H9.5 Z M17.25 9 H20.5 V20 H17.25 Z M2.5 20 H21.5 M3.5 10 H6.75 M9.5 6.5 H14.5 M17.25 12 H20.5",
        cell = "M11 8 H13 V11 H11 Z",
        dashed = "",
        hollowCell = false,
        label = "Library",
    ),
    /** Utility */
    UTILITY(
        outline = "M14 3 L8 13 H11.5 L10 21 L16 11 H12.5 Z",
        cell = "M17.2 4.4 H20.2 V7.4 H17.2 Z",
        dashed = "",
        hollowCell = false,
        label = "Utility",
    ),
    /** Settings */
    SETTINGS(
        outline = "M6 3 V21 M12 3 V21 M18 3 V21 M3.5 8 H8.5 M15.5 6.5 H20.5",
        cell = "M10.5 13.5 H13.5 V16.5 H10.5 Z",
        dashed = "",
        hollowCell = false,
        label = "Settings",
    ),
    /** Apps */
    APPS(
        outline = "M4 4 H10 V10 H4 Z M14 4 H20 V10 H14 Z M4 14 H10 V20 H4 Z",
        cell = "M14 14 H20 V20 H14 Z",
        dashed = "",
        hollowCell = false,
        label = "Apps",
    ),
    /** Files */
    FILES(
        outline = "M3 6.5 H8.5 L10.5 9.5 H21 V19.5 H3 Z",
        cell = "M12 11.5 L15 14.5 L12 17.5 L9 14.5 Z",
        dashed = "",
        hollowCell = false,
        label = "Files",
    ),
    /** ROM */
    ROM(
        outline = "M5 3 H19 V16 H15.5 V21 H8.5 V16 H5 Z M5 7 H19 M9.5 18.5 H14.5",
        cell = "M8.5 10 H15.5 V13 H8.5 Z",
        dashed = "",
        hollowCell = false,
        label = "ROM",
    ),
    /** BIOS */
    BIOS(
        outline = "M6.5 6.5 H17.5 V17.5 H6.5 Z M9.5 3 V6.5 M12 3 V6.5 M14.5 3 V6.5 M9.5 17.5 V21 M12 17.5 V21 M14.5 17.5 V21 M3 9.5 H6.5 M3 12 H6.5 M3 14.5 H6.5 M17.5 9.5 H21 M17.5 12 H21 M17.5 14.5 H21",
        cell = "M10.5 10.5 H13.5 V13.5 H10.5 Z",
        dashed = "",
        hollowCell = false,
        label = "BIOS",
    ),
    /** Texture pack */
    TEXTURE(
        outline = "M3 8 L12 3 L21 8 L12 13 Z M3 8 V16 L12 21 L21 16 V8 M12 13 V21",
        cell = "M12 5.5 L16 8 L12 10.5 L8 8 Z",
        dashed = "",
        hollowCell = false,
        label = "Texture pack",
    ),
    /** Save data */
    SAVE(
        outline = "M4 4 H16 L20 8 V20 H4 Z M8 4 V9 H15 M7 13 H17 V20",
        cell = "M10.5 14.5 H13.5 V17.5 H10.5 Z",
        dashed = "",
        hollowCell = false,
        label = "Save data",
    ),
    /** Cover art */
    COVER(
        outline = "M5 3 H19 V21 H5 Z M5 16.5 L10 11.5 L13.5 15 L19 9.5",
        cell = "M14.5 6 H17.5 V9 H14.5 Z",
        dashed = "",
        hollowCell = false,
        label = "Cover art",
    ),
    /** Hero art */
    HERO(
        outline = "M2 6 H22 V18 H2 Z M2 14.5 L8 9 L12.5 13.5 L16 10.5 L22 15",
        cell = "M16.5 7.5 H19.5 V10.5 H16.5 Z",
        dashed = "",
        hollowCell = false,
        label = "Hero art",
    ),
    /** Music */
    MUSIC(
        outline = "M9.5 18 V6 L19.5 4 V16 M9.5 8.5 L19.5 6.5 M4.5 18 L7 15.5 L9.5 18 L7 20.5 Z",
        cell = "M14.5 16 L17 13.5 L19.5 16 L17 18.5 Z",
        dashed = "",
        hollowCell = false,
        label = "Music",
    ),
    /** Patch */
    PATCH(
        outline = "M4 4 L10.4 10.4 M13.6 13.6 L20 20 M20 4 L13.6 10.4 M10.4 13.6 L4 20 M4 4 H8 M4 4 V8 M20 20 H16 M20 20 V16",
        cell = "M10.5 10.5 H13.5 V13.5 H10.5 Z",
        dashed = "",
        hollowCell = false,
        label = "Patch",
    ),
    /** Tome */
    TOME(
        outline = "M5 3 H19 V21 H5 Z M8.5 3 V21 M11 8 V16 M17 8 V16 M11 8 L17 12 M17 8 L11 12",
        cell = "M6 10.5 H8 V13.5 H6 Z",
        dashed = "",
        hollowCell = false,
        label = "Tome",
    ),
    /** Internal */
    INTERNAL(
        outline = "M3 6 H21 V18 H3 Z M3 10 H21 M3 14 H21",
        cell = "M16.5 6.9 H19.5 V9.1 H16.5 Z",
        dashed = "",
        hollowCell = false,
        label = "Internal",
    ),
    /** SD card */
    SDCARD(
        outline = "M6 3 H15 L18 6 V21 H6 Z M9 3 V7 M11.5 3 V7 M14 3 V7",
        cell = "M10.5 15.5 H13.5 V18.5 H10.5 Z",
        dashed = "",
        hollowCell = false,
        label = "SD card",
    ),
    /** USB / OTG */
    USB(
        outline = "M12 21 V7 M12 14 L7.5 9.8 V7 M12 16.5 L16.5 12.3 V9.8 M10 3 H14 V7 H10 Z M7.5 3.6 L9.2 5.3 L7.5 7 L5.8 5.3 Z",
        cell = "M15 6.8 H18 V9.8 H15 Z",
        dashed = "",
        hollowCell = false,
        label = "USB / OTG",
    ),
    /** Scan */
    SCAN(
        outline = "M3 8 V4 H7 M17 4 H21 V8 M21 16 V20 H17 M7 20 H3 V16 M4.5 12 H20",
        cell = "M10.5 10.5 H13.5 V13.5 H10.5 Z",
        dashed = "",
        hollowCell = false,
        label = "Scan",
    ),
    /** Deep scan */
    DEEPSCAN(
        outline = "M3 8 V4 H7 M17 4 H21 V8 M21 16 V20 H17 M7 20 H3 V16 M8 9 H20 M4.5 12 H20 M4.5 15 H20",
        cell = "M4.5 7.5 H7.5 V10.5 H4.5 Z",
        dashed = "",
        hollowCell = false,
        label = "Deep scan",
    ),
    /** Folder */
    FOLDER(
        outline = "M3 6.5 H9 L11 9.5 H21 V19.5 H3 Z",
        cell = "M10.5 12.5 H13.5 V15.5 H10.5 Z",
        dashed = "",
        hollowCell = false,
        label = "Folder",
    ),
    /** Path */
    PATH(
        outline = "M5 17.5 V13 H12 V7 H17.5 M3.5 17.5 H6.5 V20.5 H3.5 Z M17.5 5.5 H20.5 V8.5 H17.5 Z",
        cell = "M10.5 11.5 H13.5 V14.5 H10.5 Z",
        dashed = "",
        hollowCell = false,
        label = "Path",
    ),
    /** Capacity */
    CAPACITY(
        outline = "M3 20.5 H21 M5 20.5 V11 H8 V20.5 M10.5 20.5 V6.5 H13.5 V20.5",
        cell = "M16 13.5 H19 V20.5 H16 Z",
        dashed = "",
        hollowCell = false,
        label = "Capacity",
    ),
    /** Place */
    PLACE(
        outline = "M12 3 V13.5 M8 10 L12 14 L16 10 M4 17 V20.5 H20 V17",
        cell = "M10.5 17.5 H13.5 V20.5 H10.5 Z",
        dashed = "",
        hollowCell = false,
        label = "Place",
    ),
    /** Transfer */
    TRANSFER(
        outline = "M4 8 H16 M13 5 L16 8 L13 11 M20 16 H8 M11 13 L8 16 L11 19",
        cell = "M3 14.5 H6 V17.5 H3 Z",
        dashed = "",
        hollowCell = false,
        label = "Transfer",
    ),
    /** Copy */
    COPY(
        outline = "M8 3 H20 V15 M4 8 H16 V21 H4 Z",
        cell = "M8.5 13 H11.5 V16 H8.5 Z",
        dashed = "",
        hollowCell = false,
        label = "Copy",
    ),
    /** Move */
    MOVE(
        outline = "M3 6 H12 V18 H3 Z M12 12 H21 M18 9 L21 12 L18 15",
        cell = "M5.5 10.5 H8.5 V13.5 H5.5 Z",
        dashed = "",
        hollowCell = false,
        label = "Move",
    ),
    /** Queue */
    QUEUE(
        outline = "M4 4 H20 V8 H4 Z M4 10 H20 V14 H4 Z M4 16 H20 V20 H4 Z",
        cell = "M6 5 H9 V7 H6 Z",
        dashed = "",
        hollowCell = false,
        label = "Queue",
    ),
    /** Progress */
    PROGRESS(
        outline = "M3 9.5 H21 V14.5 H3 Z M11 9.5 V14.5 M17 9.5 V14.5",
        cell = "M3 9.5 H11 V14.5 H3 Z",
        dashed = "",
        hollowCell = false,
        label = "Progress",
    ),
    /** Completed */
    COMPLETED(
        outline = "M4 12 L10 18 L20 6",
        cell = "M8.5 15.5 H11.5 V18.5 H8.5 Z",
        dashed = "",
        hollowCell = false,
        label = "Completed",
    ),
    /** Verify */
    VERIFY(
        outline = "M12 3 L20 6 V12 L12 21 L4 12 V6 Z M8 12 L10.2 14.2 M12.4 14.6 L16 9",
        cell = "M9.6 13.6 H12.4 V16.4 H9.6 Z",
        dashed = "",
        hollowCell = false,
        label = "Verify",
    ),
    /** Match */
    MATCH(
        outline = "M7 5 H3 V19 H7 M17 5 H21 V19 H17 M9 9 H15 V15 H9 Z",
        cell = "M10.5 10.5 H13.5 V13.5 H10.5 Z",
        dashed = "",
        hollowCell = false,
        label = "Match",
    ),
    /** Mismatch */
    MISMATCH(
        outline = "M7 5 H3 V19 H7 M17 5 H21 V19 H17 M9 9 H15 V15 H9 Z M5.5 19 L18.5 5",
        cell = "M10.5 10.5 H13.5 V13.5 H10.5 Z",
        dashed = "",
        hollowCell = false,
        label = "Mismatch",
    ),
    /** Missing */
    MISSING(
        outline = "M12 7.5 V13",
        cell = "M10.75 15 H13.25 V17.5 H10.75 Z",
        dashed = "M5.0 4.0 L8.0 4.0 M10.4 4.0 L13.4 4.0 M15.8 4.0 L18.8 4.0 M19.0 6.2 L19.0 9.2 M19.0 11.6 L19.0 14.6 M19.0 17.0 L19.0 20.0 M19.0 20.0 L19.0 20.0 M16.6 20.0 L13.6 20.0 M11.2 20.0 L8.2 20.0 M5.8 20.0 L5.0 20.0 M5.0 20.0 L5.0 17.8 M5.0 15.4 L5.0 12.4 M5.0 10.0 L5.0 7.0 M5.0 4.6 L5.0 4.0",
        hollowCell = false,
        label = "Missing",
    ),
    /** Duplicate */
    DUPLICATE(
        outline = "M4 4 H15 V15 H4 Z M9 9 H20 V20 H9 Z",
        cell = "M10.5 10.5 H13.5 V13.5 H10.5 Z",
        dashed = "",
        hollowCell = false,
        label = "Duplicate",
    ),
    /** Checksum */
    CHECKSUM(
        outline = "M8 3 V21 M15 3 V21 M3 8 H21 M3 15 H21",
        cell = "M9.5 9.5 H13.5 V13.5 H9.5 Z",
        dashed = "",
        hollowCell = false,
        label = "Checksum",
    ),
    /** Region */
    REGION(
        outline = "M8 3 H16 L21 8 V16 L16 21 H8 L3 16 V8 Z M12 3 V21 M3 12 H21",
        cell = "M10.5 10.5 H13.5 V13.5 H10.5 Z",
        dashed = "",
        hollowCell = false,
        label = "Region",
    ),
    /** Handheld */
    HANDHELD(
        outline = "M7 4 H17 V20 H7 Z M9 7 H15 V15 H9 Z M9 17.5 H11 M13 17.5 H15 M4 7 H7 V17 H4 Z M17 7 H20 V17 H17 Z",
        cell = "M10.5 9.5 H13.5 V12.5 H10.5 Z",
        dashed = "",
        hollowCell = false,
        label = "Handheld",
    ),
    /** Controller */
    CONTROLLER(
        outline = "M6 7 H18 L20.5 17 H15.5 L14 14.5 H10 L8.5 17 H3.5 Z M6 11.5 H9.5 M7.75 9.75 V13.25",
        cell = "M14.5 9.8 H17.5 V12.8 H14.5 Z",
        dashed = "",
        hollowCell = false,
        label = "Controller",
    ),
    /** Display */
    DISPLAY(
        outline = "M3 5 H21 V16 H3 Z M9 20.5 H15 M12 16 V20.5",
        cell = "M10.5 8.5 H13.5 V11.5 H10.5 Z",
        dashed = "",
        hollowCell = false,
        label = "Display",
    ),
    /** Performance */
    PERFORMANCE(
        outline = "M3 20.5 H21 M4.5 20.5 V14 H7.5 V20.5 M10 20.5 V9 H13 V20.5",
        cell = "M15.5 4.5 H18.5 V20.5 H15.5 Z",
        dashed = "",
        hollowCell = false,
        label = "Performance",
    ),
    /** Thermal */
    THERMAL(
        outline = "M10 4 H14 V14.5 M12 4 V9 M15.5 7 H17.5 M15.5 10 H17.5 M15.5 13 H17.5 M12 14.2 L15.4 17.6 L12 21 L8.6 17.6 Z",
        cell = "M10.5 9 H13.5 V17 H10.5 Z",
        dashed = "",
        hollowCell = false,
        label = "Thermal",
    ),
    /** Device profile */
    PROFILE(
        outline = "M6 3 H18 V21 H6 Z M9 7 V17 M15 7 V17 M9 7 L15 11 M15 7 L9 11",
        cell = "M10.5 18 H13.5 V19.6 H10.5 Z",
        dashed = "",
        hollowCell = false,
        label = "Device profile",
    ),
    /** Ready */
    READY(
        outline = "M12 3 L21 12 L12 21 L3 12 Z",
        cell = "M10.5 10.5 H13.5 V13.5 H10.5 Z",
        dashed = "",
        hollowCell = false,
        label = "Ready",
    ),
    /** Pending */
    PENDING(
        outline = "",
        cell = "M10.5 10.5 H13.5 V13.5 H10.5 Z",
        dashed = "M12.0 3.0 L14.121 5.121 M15.818 6.818 L17.94 8.94 M19.637 10.637 L21.0 12.0 M21.0 12.0 L20.242 12.758 M18.545 14.455 L16.424 16.576 M14.726 18.274 L12.605 20.395 M10.908 19.908 L8.787 17.787 M7.09 16.09 L4.968 13.968 M3.271 12.271 L3.0 12.0 M3.0 12.0 L4.85 10.15 M6.547 8.453 L8.668 6.332 M10.365 4.635 L12.0 3.0",
        hollowCell = false,
        label = "Pending",
    ),
    /** Warning */
    WARNING(
        outline = "M12 3.5 L21.5 20 H2.5 Z M12 9 V14",
        cell = "M10.75 15.5 H13.25 V18 H10.75 Z",
        dashed = "",
        hollowCell = false,
        label = "Warning",
    ),
    /** Error */
    ERROR(
        outline = "M12 3 L21 12 L12 21 L3 12 Z M8.6 8.6 L10.4 10.4 M13.6 13.6 L15.4 15.4 M15.4 8.6 L13.6 10.4 M10.4 13.6 L8.6 15.4",
        cell = "M10.5 10.5 H13.5 V13.5 H10.5 Z",
        dashed = "",
        hollowCell = false,
        label = "Error",
    ),
    /** Locked */
    LOCKED(
        outline = "M6 11 H18 V21 H6 Z M9 11 V8.5 L10.5 6.5 H13.5 L15 8.5 V11",
        cell = "M10.5 14 H13.5 V18 H10.5 Z",
        dashed = "",
        hollowCell = false,
        label = "Locked",
    ),
    /** Device only */
    LOCAL(
        outline = "M4 6 H20 V16 H4 Z M9 20 H15 M12 16 V20 M6.5 9 L9 12 L6.5 15 M17.5 9 L15 12 L17.5 15",
        cell = "M10.5 9.5 H13.5 V12.5 H10.5 Z",
        dashed = "",
        hollowCell = false,
        label = "Device only",
    ),
    /** Empty */
    EMPTY(
        outline = "",
        cell = "M10.5 10.5 H13.5 V13.5 H10.5 Z",
        dashed = "M12.0 3.0 L14.121 5.121 M15.818 6.818 L17.94 8.94 M19.637 10.637 L21.0 12.0 M21.0 12.0 L20.242 12.758 M18.545 14.455 L16.424 16.576 M14.726 18.274 L12.605 20.395 M10.908 19.908 L8.787 17.787 M7.09 16.09 L4.968 13.968 M3.271 12.271 L3.0 12.0 M3.0 12.0 L4.85 10.15 M6.547 8.453 L8.668 6.332 M10.365 4.635 L12.0 3.0",
        hollowCell = true,
        label = "Empty",
    ),
    /** Info */
    INFO(
        outline = "M12 3 L21 12 L12 21 L3 12 Z M12 11.5 V17.5",
        cell = "M10.5 7 H13.5 V10 H10.5 Z",
        dashed = "",
        hollowCell = false,
        label = "Info",
    ),
    /** HOME */
    DOCK_HOME(
        outline = "M3.5 10.5 L12 3.5 L20.5 10.5 M6 9.6 V20 M18 9.6 V20 M6 12.4 L18 15.6 M4 20 H20",
        cell = "M10.5 15.6 H13.5 V20 H10.5 Z",
        dashed = "",
        hollowCell = false,
        label = "HOME",
    ),
    /** LIBRARY */
    DOCK_LIBRARY(
        outline = "M7 3.5 V20.5 M7 3.5 L17.5 6.6 M17.5 6.6 V20.5 M7 20.5 H17.5 M7 9.5 L17.5 12.6",
        cell = "M8.6 14.4 H11.2 V17.8 H8.6 Z",
        dashed = "",
        hollowCell = false,
        label = "LIBRARY",
    ),
    /** UTILITY */
    DOCK_UTILITY(
        outline = "M16.8 3.4 H9.2 L15 11.4 H8.2 L13.6 20.6",
        cell = "M17 14.6 H20 V17.6 H17 Z",
        dashed = "",
        hollowCell = false,
        label = "UTILITY",
    ),
    /** SETTINGS */
    DOCK_SETTINGS(
        outline = "M4 3 V21 M8.5 6.5 L12 12 L8.5 17.5 M13.5 4 L19 12 L13.5 20",
        cell = "M5 10.5 H8 V13.5 H5 Z",
        dashed = "",
        hollowCell = false,
        label = "SETTINGS",
    ),
    /** APPS */
    DOCK_APPS(
        outline = "M3.5 3.5 H10 V10 H3.5 Z M13.5 13.5 H20.5 V20.5 H13.5 Z M17 10 V3.5 M14 6.5 L17 3.5 L20 6.5",
        cell = "M3.5 13.5 H10 V20.5 H3.5 Z",
        dashed = "",
        hollowCell = false,
        label = "APPS",
    ),
    /** FILES */
    DOCK_FILES(
        outline = "M3 6.4 H8.8 L10.8 9.4 H21 V20.2 H3 Z M10.6 15.2 V18.8 M13.4 15.2 V18.8",
        cell = "M12 10.8 L14.9 13.7 L12 16.6 L9.1 13.7 Z",
        dashed = "",
        hollowCell = false,
        label = "FILES",
    ),
    ;

    /**
     * The parsed geometry, built ONCE per entry and shared by every draw.
     *
     * [PathParser] allocates three [Path]s per call; the dock rail draws six marks per frame, so
     * parsing at draw time put that on the hot path. The enum entry is the only object that lives
     * as long as the set does, so the cache belongs here. A [Path] is never mutated after parsing.
     */
    internal val paths: MimirIconPaths by lazy(LazyThreadSafetyMode.PUBLICATION) {
        MimirIconPaths(parse(outline), parse(cell), parse(dashed))
    }
}

internal class MimirIconPaths(val outline: Path?, val cell: Path?, val dashed: Path?)

private fun parse(d: String): Path? =
    if (d.isEmpty()) null else PathParser().parsePathString(d).toPath()

/**
 * Draw one [MimirIcon] at [size], outline in [ink] and cell in [cellInk].
 *
 * Both colours default to the active palette, so a call site that wants the ordinary appearance
 * passes neither. A focused row passes `cellInk = palette.accentBright`; a failed one passes
 * `palette.danger`. The outline never changes hue — only the cell's temperature does.
 *
 * [size] is applied to the mark itself, so `MimirIconMark(MimirIcon.LIBRARY)` renders at 24.dp
 * inside any layout. A caller that puts its own size in [modifier] wins — that size is outermost —
 * and the stroke boost still tracks it, because the boost is read from the drawn edge.
 */
@Composable
fun MimirIconMark(
    icon: MimirIcon,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    ink: Color = LocalMimirPalette.current.accent,
    cellInk: Color = LocalMimirPalette.current.accentBright,
    contentDescription: String? = icon.label,
) {
    Canvas(
        modifier = modifier
            .then(
                if (contentDescription == null) Modifier
                else Modifier.semantics { this.contentDescription = contentDescription }
            )
            .size(size)
    ) {
        drawMimirIconPaths(icon.paths, ink, cellInk, icon.hollowCell)
    }
}

/** The draw-only half, for call sites that already own a [DrawScope] (rows, coins, chips). */
internal fun DrawScope.drawMimirIcon(
    icon: MimirIcon,
    ink: Color,
    cellInk: Color,
    strokeBoost: Float = 1f,
) = drawMimirIconPaths(icon.paths, ink, cellInk, icon.hollowCell, strokeBoost)

private fun DrawScope.drawMimirIconPaths(
    paths: MimirIconPaths,
    ink: Color,
    cellInk: Color,
    hollowCell: Boolean,
    strokeBoost: Float = 1f,
) {
    val edge = min(size.width, size.height)
    if (edge <= 0f) return
    val k = edge / MimirIconArt.ViewportUnits
    // The boost is a property of how small the mark ACTUALLY landed, not of what the caller asked
    // for, so it survives a size that arrived through the modifier or through parent constraints.
    // Compared in PIXELS with a half-pixel slack: layout rounds a dp size to a whole pixel, so at
    // some densities a mark asked for at exactly 24.dp lands a hair under it and would otherwise
    // pick up the boost it is not supposed to have.
    val boost = strokeBoost *
        if (edge < MimirIconArt.SmallSizeThreshold.toPx() - 0.5f) MimirIconArt.SmallStrokeBoost else 1f
    val outlineInk = ink.copy(alpha = MimirIconArt.OutlineAlpha)
    val stroke = Stroke(
        width = MimirIconArt.StrokeUnits * boost,
        cap = StrokeCap.Butt,
        join = StrokeJoin.Miter,
    )
    // Centre the 24-unit box in whatever box the caller gave us, then work in authoring units.
    translate(left = (size.width - edge) / 2f, top = (size.height - edge) / 2f) {
        scale(scaleX = k, scaleY = k, pivot = Offset.Zero) {
            paths.dashed?.let { drawPath(it, color = outlineInk, style = stroke) }
            paths.outline?.let { drawPath(it, color = outlineInk, style = stroke) }
            paths.cell?.let {
                if (hollowCell) {
                    drawPath(
                        it,
                        color = outlineInk,
                        style = Stroke(
                            width = MimirIconArt.HollowCellStrokeUnits * boost,
                            cap = StrokeCap.Butt,
                            join = StrokeJoin.Miter,
                        ),
                    )
                } else {
                    drawPath(it, color = cellInk, style = Fill)
                }
            }
        }
    }
}
