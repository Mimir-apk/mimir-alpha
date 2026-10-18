package com.gruning.mimir.rewrite

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.mimirActivatable
import com.gruning.mimir.rewrite.core.input.mimirFocusable
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirRingShape
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirEtchedChipPlate
import com.gruning.mimir.rewrite.core.ui.mimirPressResponse
import com.gruning.mimir.rewrite.core.ui.mimirSelectionRing

/*
 * The footer's actions, Upload and Retry/Stop, match the top row's UNSELECTED etched chip ([mimirEtchedChipSurface],
 * warmed = false), at the chips' plate height, padding and label style, inside a 48 dp hit box, with the top row's pill
 * ring (no flare, no resting outline). Never a filled or tinted pill: the plate is the only surface, and nothing is
 * painted into the hit box around it.
 *
 * The ring hugs the PLATE, not the 48 dp box. A top-row chip rings its box 4 dp inside, which on a plate shorter than the
 * box leaves the ring floating a few dp off the plate's own metal rim: two rims instead of a fitted ring. Here the plate is the ringed element, so one ring stands on one rim.
 */

internal const val EnrichFooterUploadPlateTag = "enrich-footer-upload-plate"
internal const val EnrichFooterRetryTag = "enrich-footer-retry"
internal const val EnrichFooterRetryPlateTag = "enrich-footer-retry-plate"

/** The drawn plus's optical seat, mark and stroke: a font-rendered full-width plus carries asymmetric side bearings on device. */
private const val EnrichFooterGlyphSeatDp = 18
private const val EnrichFooterGlyphMarkDp = 12
private const val EnrichFooterGlyphStrokeDp = 1

/** Stop and Retry share one seat so the chip does not resize as the search starts and stops. */
internal val EnrichSearchControlSeat: Dp = 88.dp

/**
 * One footer action as an etched chip: a 48 dp hit box ([modifier] carries its test tag) around a plate at the top row's
 * chip height, with [content] on one line between the chip's padding. [plateMinWidth] is the plate's seat, centred.
 */
@Composable
internal fun EnrichActionChip(
    authority: FocusAuthority,
    key: FocusKey,
    focused: Boolean,
    label: String,
    onActivate: () -> Unit,
    plateTag: String,
    modifier: Modifier = Modifier,
    plateMinWidth: Dp = Dp.Unspecified,
    content: @Composable RowScope.() -> Unit,
) {
    val palette = LocalMimirPalette.current
    val source = remember { MutableInteractionSource() }
    val plateHeight = rememberActivityChipPlateHeight()
    Box(
        modifier
            .heightIn(min = MimirSpacing.minTarget)
            .widthIn(min = MimirSpacing.minTarget)
            .mimirFocusable(authority, key, HomeCustomEnrichScope, autoBringIntoView = false)
            .mimirActivatable(label = label, interactionSource = source, onActivate = onActivate)
            .mimirPressResponse(source),
        contentAlignment = Alignment.Center,
    ) {
        // The one etched chip plate of every screen (MimirEtchedChipPlate): the ring is in the modifier, which it applies BEFORE its surface, so the ring
        // hugs the plate and the plate hands it its rim. The plate is held at the chips' height, so a seat taller than a label line (the plus) cannot
        // make it taller than the chips beside it.
        MimirEtchedChipPlate(
            modifier = Modifier
                .height(plateHeight)
                .then(if (plateMinWidth.isSpecified) Modifier.widthIn(min = plateMinWidth) else Modifier)
                .testTag(plateTag)
                .mimirSelectionRing(focused = focused, identity = key, shape = MimirRingShape.Pill, edgeFlush = true),
            focused = focused,
            palette = palette,
            edgeFlush = true,
        ) {
            // The plate's own row starts its content at the start; the seat's content is centred in it (Stop and Retry share one seat).
            Row(
                modifier = if (plateMinWidth.isSpecified) Modifier.widthIn(min = plateMinWidth - MimirSpacing.md * 2) else Modifier,
                horizontalArrangement = Arrangement.spacedBy(MimirSpacing.xs, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}

/** Upload's plus, drawn inside one fixed optical seat so its centre and the label's baseline cannot drift. */
@Composable
internal fun EnrichUploadPlus(color: Color) {
    Box(
        modifier = Modifier
            .requiredSize(EnrichFooterGlyphSeatDp.dp)
            .drawBehind {
                val mark = EnrichFooterGlyphMarkDp.dp.toPx()
                val half = mark / 2f
                val center = Offset(size.width / 2f, size.height / 2f)
                val stroke = EnrichFooterGlyphStrokeDp.dp.toPx()
                drawLine(
                    color = color,
                    start = Offset(center.x - half, center.y),
                    end = Offset(center.x + half, center.y),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = color,
                    start = Offset(center.x, center.y - half),
                    end = Offset(center.x, center.y + half),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            },
    )
}
