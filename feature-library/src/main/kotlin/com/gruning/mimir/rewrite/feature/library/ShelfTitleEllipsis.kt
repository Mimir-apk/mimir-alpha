package com.gruning.mimir.rewrite.feature.library

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import com.gruning.mimir.rewrite.core.ui.MimirEllipsis
import com.gruning.mimir.rewrite.core.ui.MimirWordEllipsisText
import com.gruning.mimir.rewrite.core.ui.mimirEllipsizeAtWord

/** The ellipsis the shelf's title rail ends a shortened line with. */
internal const val ShelfEllipsis = MimirEllipsis

/**
 * [text] cut back to a WORD boundary and closed with an ellipsis, when [fits] says the whole of it does not ([mimirEllipsizeAtWord], the one implementation: Settings' descriptions use it too).
 *
 * Text never breaks mid-word (a standing rule): Compose's own `TextOverflow.Ellipsis` cuts at the last letter that fits, which read "Unbel..." on the Home title rail. Pure, so the order is a
 * table test.
 */
internal fun ellipsizeAtWord(text: String, fits: (String) -> Boolean): String = mimirEllipsizeAtWord(text, fits)

/**
 * One line of [text] in [style], cut at a word boundary with an ellipsis when it is wider than the room it is given ([ellipsizeAtWord]).
 *
 * The room is the width this node is constrained to, so a caller bounds it with `widthIn(max = ...)` as it did the plain `Text` this replaces. The whole text stays the node's spoken name
 * (`contentDescription`) when it is shortened, so a screen reader still reads the title in full.
 */
@Composable
internal fun WordEllipsisText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    MimirWordEllipsisText(text = text, style = style, color = color, modifier = modifier, maxLines = 1)
}
