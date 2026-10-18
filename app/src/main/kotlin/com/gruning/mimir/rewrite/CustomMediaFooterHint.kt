package com.gruning.mimir.rewrite

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import com.gruning.mimir.rewrite.core.ui.LocalMimirControllerHintLabels
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import java.util.Locale

/*
 * The Customize media footer's controller legend . It used to be one line that the panel clipped mid-word ("A USE ·
 * SELECT PREVIEW · B BAC") and that began right beside UPLOAD at a compact width and a large font. It now takes only the room
 * its slot gives it and, when the whole legend does not fit on one line, stacks as TWO (or more) lines, each made of whole
 * " · " segments: a break never falls inside "SELECT PREVIEW" or between a key and its action, and the separator at the break
 * is dropped. Every line is one line of text, never clipped and never ellipsized.
 */

/** The footer's Upload pill (tests find it here). */
internal const val EnrichFooterUploadTag = "enrich-footer-upload"

/** The slot the legend stack is given: the footer's room to the right of its action pills. */
internal const val EnrichFooterHintSlotTag = "enrich-footer-hint-slot"

/** One legend's stack of lines (the whole of it, however many lines it took). */
internal const val EnrichHintStackTag = "enrich-hint-stack"

private const val EnrichHintSeparator = " · "

/** Pixels held back from the slot's width, so a line that just fits is not clipped by the rounding of its last letter. */
private const val EnrichHintSlackPx = 2

/**
 * Breaks a legend's [segments] into lines, greedily: a segment joins the line before it (with " · " between them) when [fits]
 * says the joined line fits, otherwise it starts a new line. A segment is never split, and the line order is the segment order.
 */
internal fun enrichHintLines(segments: List<String>, fits: (String) -> Boolean): List<String> {
    if (segments.isEmpty()) return emptyList()
    val lines = ArrayList<String>()
    var current = segments.first()
    for (segment in segments.drop(1)) {
        val joined = current + EnrichHintSeparator + segment
        if (fits(joined)) {
            current = joined
        } else {
            lines += current
            current = segment
        }
    }
    lines += current
    return lines
}

/**
 * One controller legend ("A USE · SELECT PREVIEW · B BACK") laid out in the room it is given: one line when it fits, else
 * stacked lines of whole segments (see [enrichHintLines]), right-aligned. The text is the projected legend (the applied
 * controller map's own key names), measured as it is drawn: uppercase, at [style].
 */
@Composable
internal fun EnrichHintLines(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val projected = LocalMimirControllerHintLabels.current.rewrite(text)
    if (projected.isBlank()) return
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val room = constraints.maxWidth - EnrichHintSlackPx
        val lines = remember(projected, style, room, measurer) {
            enrichHintLines(projected.split(EnrichHintSeparator)) { candidate ->
                measurer.measure(candidate.uppercase(Locale.ROOT), style, maxLines = 1, softWrap = false).size.width <= room
            }
        }
        Column(Modifier.testTag(EnrichHintStackTag), horizontalAlignment = Alignment.End) {
            lines.forEach { line ->
                MimirChromeText(
                    text = line,
                    style = style,
                    color = color,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}
