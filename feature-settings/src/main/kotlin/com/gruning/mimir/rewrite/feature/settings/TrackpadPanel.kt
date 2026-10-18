package com.gruning.mimir.rewrite.feature.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirTrackpadPointerPainter
import com.gruning.mimir.rewrite.core.ui.TrackpadPointerGeometry
import com.gruning.mimir.rewrite.core.ui.TrackpadPointerShape
import com.gruning.mimir.rewrite.core.ui.TrackpadPointerStyle
import com.gruning.mimir.rewrite.core.ui.trackpadPointerFill
import com.gruning.mimir.rewrite.core.ui.theme.MimirColors
import com.gruning.mimir.rewrite.core.ui.theme.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.theme.MimirType

const val TrackpadPointerPreviewTestTag = "trackpad_pointer_preview"

/** The preview tile: two halves, a dark ground and a light one, 46 dp high so the largest pointer (34 dp) fits. */
private val PreviewWidth = 76.dp
private val PreviewHeight = 46.dp

/**
 * Settings > Controls > Trackpad: the keyboard trackpad's card. Whether the service is on, natural scroll, the pointer's speed,
 * and the pointer's look: size, shape and color, each a row that cycles on a press like the speed row beside it. The card's
 * corner shows the pointer as it will be drawn, at its real size, on a dark and a light ground.
 */
@Composable
internal fun TrackpadPanel(
    authority: FocusAuthority,
    onRowActivate: (String) -> Unit,
    trackpad: TrackpadSettingsState,
) {
    SettingsSection(
        title = "Trackpad",
        trailing = { TrackpadPointerPreview(trackpad.pointer) },
    ) {
        SettingsRow(
            key = detailKey(TrackpadAccessRowId),
            scope = DetailScope,
            authority = authority,
            title = "Keyboard trackpad",
            titleStyle = MimirType.SettingsActionTitle,
            supporting = if (trackpad.serviceEnabled) TrackpadAccessOnSupporting else TrackpadAccessOffSupporting,
            onActivate = { onRowActivate(TrackpadAccessRowId) },
            trailing = {
                if (trackpad.serviceEnabled) {
                    DynamicValueChip(text = "On", color = MimirColors.SuccessGreen)
                } else {
                    RowAction("Open")
                }
            },
        )
        SettingsRow(
            key = detailKey(TrackpadScrollRowId),
            scope = DetailScope,
            authority = authority,
            title = "Natural scroll",
            titleStyle = MimirType.SettingsActionTitle,
            supporting = TrackpadScrollSupporting,
            onActivate = { onRowActivate(TrackpadScrollRowId) },
            trailing = { RowAction(musicToggleLabel(trackpad.naturalScroll)) },
        )
        SettingsRow(
            key = detailKey(TrackpadSpeedRowId),
            scope = DetailScope,
            authority = authority,
            title = "Pointer speed",
            titleStyle = MimirType.SettingsActionTitle,
            supporting = TrackpadSpeedSupporting,
            onActivate = { onRowActivate(TrackpadSpeedRowId) },
            trailing = { RowAction("${trackpad.speed} of 5") },
        )
        SettingsRow(
            key = detailKey(TrackpadPointerSizeRowId),
            scope = DetailScope,
            authority = authority,
            title = "Pointer size",
            titleStyle = MimirType.SettingsActionTitle,
            supporting = TrackpadPointerSizeSupporting,
            onActivate = { onRowActivate(TrackpadPointerSizeRowId) },
            trailing = { RowAction(trackpad.pointer.size.label) },
        )
        SettingsRow(
            key = detailKey(TrackpadPointerShapeRowId),
            scope = DetailScope,
            authority = authority,
            title = "Pointer shape",
            titleStyle = MimirType.SettingsActionTitle,
            supporting = TrackpadPointerShapeSupporting,
            onActivate = { onRowActivate(TrackpadPointerShapeRowId) },
            trailing = { RowAction(trackpad.pointer.shape.label) },
        )
        SettingsRow(
            key = detailKey(TrackpadPointerColorRowId),
            scope = DetailScope,
            authority = authority,
            title = "Pointer color",
            titleStyle = MimirType.SettingsActionTitle,
            supporting = TrackpadPointerColorSupporting,
            onActivate = { onRowActivate(TrackpadPointerColorRowId) },
            trailing = { RowAction(trackpad.pointer.color.label) },
        )
    }
}

/**
 * The pointer as the trackpad will draw it: the same painter, the same geometry and the same fill resolution as the View on
 * Main, at the pointer's real size on a dark ground and a light one. The arrow's tip or the dot's center sits at the middle of
 * each half, so the choice of shape shows where a tap lands.
 */
@Composable
internal fun TrackpadPointerPreview(style: TrackpadPointerStyle, modifier: Modifier = Modifier) {
    val palette = LocalMimirPalette.current
    val density = LocalDensity.current.density
    val windowPx = remember(style.shape, style.size, density) {
        TrackpadPointerGeometry.windowPx(style.shape, style.size, density)
    }
    val fill = remember(style.color, palette) { trackpadPointerFill(style.color, palette) }
    val painter = remember(style.shape, windowPx, fill) {
        MimirTrackpadPointerPainter().also { it.configure(style.shape, windowPx, fill) }
    }
    val dark = palette.surfaceBase
    val light = palette.textPrimary
    val description = "Pointer preview: ${style.size.label}, ${style.shape.label}, ${style.color.label}"
    Canvas(
        modifier
            .size(PreviewWidth, PreviewHeight)
            .clip(RoundedCornerShape(MimirSpacing.sm))
            .testTag(TrackpadPointerPreviewTestTag)
            .semantics { contentDescription = description },
    ) {
        val half = size.width / 2f
        drawRect(dark, size = Size(half, size.height))
        drawRect(light, topLeft = Offset(half, 0f), size = Size(half, size.height))
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            for (side in 0..1) {
                val centreX = half * side + half / 2f
                // The arrow's ink is a narrow strip at the left of its square window: centre the strip, not the square.
                val left = if (style.shape == TrackpadPointerShape.DOT) centreX - windowPx / 2f else centreX - windowPx * 0.25f
                native.save()
                native.translate(left, (size.height - windowPx) / 2f)
                painter.draw(native)
                native.restore()
            }
        }
    }
}
