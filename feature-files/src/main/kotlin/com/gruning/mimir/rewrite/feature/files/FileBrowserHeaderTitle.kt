package com.gruning.mimir.rewrite.feature.files

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import com.gruning.mimir.rewrite.core.ui.MimirTypeStep

/** What the header title was set at: its size, and the lines it may take ([fileBrowserTitleFit]). */
internal data class FileBrowserTitleFit(val sizeSp: Float, val maxLines: Int)

/** How far each rung of the shrink is below the last, in sp: fine enough that the title is as large as the room allows, coarse enough to stay a handful of measures. */
private const val FileBrowserTitleStepSp = 0.5f

/**
 * The fitting order for a title that must not break mid-word: SHRINK it from [ceilingSp] toward [floorSp] until it stands on one line; at the floor, WRAP it at its word
 * boundaries onto two lines; only when that does not fit either, ELLIPSIZE it on one line at the floor. [fits] answers whether the whole text stands, with no line cut
 * inside a word, at a size on that many lines. Pure, so the order is a table test.
 */
internal fun fileBrowserTitleFit(ceilingSp: Float, floorSp: Float, fits: (sizeSp: Float, lines: Int) -> Boolean): FileBrowserTitleFit {
    val floor = minOf(floorSp, ceilingSp)
    var size = ceilingSp
    while (size > floor) {
        if (fits(size, 1)) return FileBrowserTitleFit(size, 1)
        size -= FileBrowserTitleStepSp
    }
    if (fits(floor, 1)) return FileBrowserTitleFit(floor, 1)
    if (fits(floor, 2)) return FileBrowserTitleFit(floor, 2)
    return FileBrowserTitleFit(floor, 1)
}

/**
 * Whether [layout]'s lines end at word boundaries: a line may end at a space, after a hyphen, or at the end of the text, never between two letters of one word (a word
 * wider than the room is broken mid-word by the text engine, and that reads as a defect).
 */
internal fun fileBrowserTitleBreaksOnlyBetweenWords(text: String, layout: TextLayoutResult): Boolean =
    (0 until layout.lineCount - 1).all { line ->
        val end = layout.getLineEnd(line)
        end <= 0 || end >= text.length || text[end - 1].isWhitespace() || text[end].isWhitespace() || text[end - 1] == '-'
    }

/**
 * The Files header's title ("ᛗ · CHOOSE A MUSIC FOLDER"): the Heading size, shrunk, then wrapped, then ellipsized ([fileBrowserTitleFit]) to the room it is given, which is
 * the header's own height and the width its row leaves it. The floor is the Heading as it is DRAWN at a font scale of 1 (the convention the folder covers' titles took in sweep
 * 10-09): at 1.3x the title starts 30% larger and shrinks back to no smaller than it is at 1x, so it reads whole at the larger type, where a fixed 280 dp used to cut it to
 * "ᛗ · CHOOSE A MUSIC FO...".
 */
@Composable
internal fun FileBrowserHeaderTitle(title: String, color: Color, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val base = LocalTextStyle.current
    BoxWithConstraints(modifier) {
        val widthPx = constraints.maxWidth
        val heightPx = constraints.maxHeight
        val fit = remember(title, widthPx, heightPx, density.density, density.fontScale, base) {
            val ceilingSp = MimirTypeStep.Heading.value
            val floorSp = ceilingSp / density.fontScale.coerceAtLeast(1f)
            fileBrowserTitleFit(ceilingSp, floorSp) { sizeSp, lines ->
                val layout = measurer.measure(
                    text = title,
                    style = base.merge(fileBrowserTitleStyle(sizeSp)),
                    overflow = TextOverflow.Clip,
                    softWrap = true,
                    maxLines = lines,
                    constraints = Constraints(maxWidth = widthPx, maxHeight = heightPx),
                )
                !layout.hasVisualOverflow && layout.lineCount <= lines && fileBrowserTitleBreaksOnlyBetweenWords(title, layout)
            }
        }
        Text(
            text = title,
            style = base.merge(fileBrowserTitleStyle(fit.sizeSp)),
            color = color,
            maxLines = fit.maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The title's style at [sizeSp]. The line height follows the size (1.3 times it, the Heading's own at 1x): the ambient style's is a fixed 24 sp, which at 1.3x is 31 dp a line however far
 * the title has shrunk, so two lines were 62 dp in a 52 dp header and the wrap step of the fitting order could never be reached.
 */
private fun fileBrowserTitleStyle(sizeSp: Float) = TextStyle(fontSize = sizeSp.sp, fontWeight = FontWeight.SemiBold, lineHeight = (sizeSp * 1.3f).sp)
