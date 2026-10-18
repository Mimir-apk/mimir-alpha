package com.gruning.mimir.rewrite.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * The Bottom's one compact header, in core-ui so every Bottom surface (the lattice, the Activity page, the shelf) can wear it.
 *
 * It is the lattice's identity header (feature-companion's `CompanionLatticeHeader` before slice H0b, which moved the lattice onto this one and deleted
 * its copy), with what a second and third surface need: the title is a parameter, the stat and the tagline are optional, and the row has a leading slot
 * (the shelf's back pill) and a trailing slot (its density stepper).
 *
 * The rune eyebrow that used to sit on the rule ("ᛗIMIR · THE WELL OF MEMORY", with its scrim and the reserve it needed under the row) is removed. The lattice's words are now a [tagline] on the
 * title's baseline. `MimirRuneEyebrow` itself stays in core-ui: Main's wordmark and the welcome-back lane still draw it.
 *
 * Nothing here holds the process-wide pad gate, takes focus, or animates: it is a static header. The rule's own molten drift is HeaderRule's.
 */

/** The badge's size: Main's identity header seats the same [MimirMarkBadge] at 32 dp. */
const val MimirCompactHeaderBadgeDp = 32f

/** The identity row is as tall as the badge (Main's is 36 with the badge centred; the Bottom cannot spare the 4 dp). A taller slot grows the row. */
const val MimirCompactHeaderRowDp = 32f

/** Main's gap between the identity row and the rule (`ShelfIdentityRuleGapDp`). */
const val MimirCompactHeaderRuleGapDp = 6f

/** The header's own height at a font scale where the row's text fits the badge: the row, the gap, and the rule. */
const val MimirCompactHeaderHeightDp = MimirCompactHeaderRowDp + MimirCompactHeaderRuleGapDp + MimirHeaderRuleHeightDp

/** The clearance the caller leaves between the header and whatever sits under it (the lattice shell's 8 dp gap, the Activity page's spacer). */
const val MimirCompactHeaderClearanceDp = 8f

/**
 * Where the Bottom's floating ᛗ coin starts when it has never been moved : the right edge, this far down, so just below the pages' header row and covering no
 * header control. The coin itself goes wherever it is dropped and no page reserves room for it; this is only its first seat. Every Bottom header (the row, its rule and the air
 * under it) ends above this line at font scale 1.0 and 1.3, which the header render tests hold. The native overlay cannot read core-ui, so it keeps a pinned copy
 * (`MButtonBottomDefaultTopDp`); the app's source pin holds the two equal.
 */
const val MimirBottomMarkDefaultTopDp = 72f

const val MimirCompactHeaderTag = "mimir-compact-header"
const val MimirCompactHeaderRowTag = "mimir-compact-header-row"
const val MimirCompactHeaderRuleTag = "mimir-compact-header-rule"
const val MimirCompactHeaderBadgeTag = "mimir-compact-header-badge"
const val MimirCompactHeaderTitleTag = "mimir-compact-header-title"
const val MimirCompactHeaderTaglineTag = "mimir-compact-header-tagline"
const val MimirCompactHeaderStatTag = "mimir-compact-header-stat"
const val MimirCompactHeaderLeadingTag = "mimir-compact-header-leading"
const val MimirCompactHeaderTrailingTag = "mimir-compact-header-trailing"

/**
 * The Bottom's identity header: [leading] (optional), the [MimirMarkBadge] at 32 dp, [title] in `TitleCaps` through [MimirChromeText] (chrome
 * is capped by intent, never typed in capitals), [tagline] (optional) and [stat] (optional) in `Support`, [trailing] (optional), and under them the
 * [HeaderRule]. The [tagline] and the [stat] are the caller's own text (plural rules, em dash and all; the lattice's tagline has none).
 *
 * The slots and the badge ([badge] is the [MimirMarkBadge] unless the caller seats its own) are measured first, at their own size, and keep it. The title, the tagline and the stat share the width that is left, and
 * give it up in a fixed order ([MimirCompactHeaderTitleGroup]): the title is whole, then the tagline, and the stat is the first to go. The tagline
 * sits on the title's baseline.
 *
 * [ruleBleed] is how far the molten rules of Surtr and Niflheim reach past the content gutter (the lattice passes its shell inset).
 */
@Composable
fun MimirCompactHeader(
    title: String,
    palette: MimirThemePalette,
    modifier: Modifier = Modifier,
    stat: String? = null,
    tagline: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    ruleBleed: Dp = 0.dp,
    /**
     * What sits where the [MimirMarkBadge] does. The default is the badge itself, at [MimirCompactHeaderBadgeDp] and tagged [MimirCompactHeaderBadgeTag]; a caller
     * with an identity of its own (the shelf's platform icon) replaces it, and tags and sizes its own seat. It is measured first, at its own size, like a slot.
     */
    badge: @Composable () -> Unit = {
        MimirMarkBadge(
            palette = palette,
            modifier = Modifier.testTag(MimirCompactHeaderBadgeTag),
            sizeDp = MimirCompactHeaderBadgeDp.dp,
        )
    },
    /** How many lines the title may take before it ellipsizes. 1 (the default) is the lattice's and the Activity page's single line. */
    titleMaxLines: Int = 1,
    /** Applied to the title group (the title and the stat, text only): a page that wants to know where that stretch of the row is asks through this. */
    titleGroupModifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().testTag(MimirCompactHeaderTag)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MimirCompactHeaderRowDp.dp)
                .testTag(MimirCompactHeaderRowTag),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MimirSpacing.md),
        ) {
            if (leading != null) {
                Box(Modifier.testTag(MimirCompactHeaderLeadingTag)) { leading() }
            }
            badge()
            // The title group takes the row's REMAINING width: the slots and the badge are not weighted, so Row measures them first at their own size.
            // A long title therefore ellipsizes instead of squeezing a slot's touch target away.
            val hasTagline = !tagline.isNullOrBlank()
            val hasStat = !stat.isNullOrBlank()
            MimirCompactHeaderTitleGroup(
                hasTagline = hasTagline,
                hasStat = hasStat,
                modifier = Modifier.weight(1f).then(titleGroupModifier),
            ) {
                MimirChromeText(
                    text = title,
                    style = MimirTypeScale.TitleCaps,
                    color = palette.textPrimary,
                    maxLines = titleMaxLines,
                    overflow = TextOverflow.Ellipsis,
                    // MimirChromeText speaks the whole authored title whatever the ellipsis hides.
                    modifier = Modifier.testTag(MimirCompactHeaderTitleTag),
                )
                if (hasTagline) {
                    Text(
                        text = tagline.orEmpty(),
                        style = MimirTypeScale.Support,
                        color = palette.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag(MimirCompactHeaderTaglineTag),
                    )
                }
                if (hasStat) {
                    Text(
                        text = stat.orEmpty(),
                        style = MimirTypeScale.Support,
                        color = palette.textSecondary,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag(MimirCompactHeaderStatTag),
                    )
                }
            }
            if (trailing != null) {
                Box(Modifier.testTag(MimirCompactHeaderTrailingTag)) { trailing() }
            }
        }
        Spacer(Modifier.height(MimirCompactHeaderRuleGapDp.dp))
        Box(Modifier.fillMaxWidth().height(MimirHeaderRuleHeightDp.dp).testTag(MimirCompactHeaderRuleTag)) {
            HeaderRule(palette = palette, bleed = ruleBleed)
        }
    }
}

/**
 * The title, the tagline and the stat on one row, on one baseline, in the width they are given. Their content is, in order, the title, the tagline when
 * [hasTagline], the stat when [hasStat] (the caller composes exactly those).
 *
 * The title is measured first and keeps what it needs (it ellipsizes only when it alone is wider than the row). The tagline takes what it wants of what is left
 * and ellipsizes in it. The stat takes the rest, right-aligned. With a tagline, the stat is shown only when it fits whole next to it: it yields first, as a
 * zero-width node (a stat cut to "19 platf" next to a tagline is worse than none). With no tagline the stat takes whatever the title leaves and ellipsizes, as the
 * Activity page's always has.
 */
@Composable
private fun MimirCompactHeaderTitleGroup(
    hasTagline: Boolean,
    hasStat: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val available = constraints.maxWidth
        val gap = MimirSpacing.md.roundToPx()
        var next = 0
        val titleMeasurable = measurables[next++]
        val taglineMeasurable: Measurable? = if (hasTagline) measurables[next++] else null
        val statMeasurable: Measurable? = if (hasStat) measurables[next++] else null

        val title = titleMeasurable.measure(Constraints(maxWidth = available))
        val taglineRoom = (available - title.width - gap).coerceAtLeast(0)
        val tagline: Placeable? = taglineMeasurable?.measure(Constraints(maxWidth = taglineRoom))
        val used = title.width + if (tagline != null && taglineRoom > 0) gap + tagline.width else 0

        val statRoom = (available - used - gap).coerceAtLeast(0)
        val stat: Placeable? = statMeasurable?.let { measurable ->
            // No tagline: the stat takes the room it is given and ellipsizes. With one: whole, or not at all.
            val whole = measurable.maxIntrinsicWidth(Constraints.Infinity) <= statRoom
            if (taglineMeasurable == null || whole) {
                measurable.measure(Constraints(minWidth = statRoom, maxWidth = statRoom))
            } else {
                measurable.measure(Constraints(minWidth = 0, maxWidth = 0))
            }
        }

        val placeables = listOfNotNull(title, tagline, stat)
        fun baselineOf(p: Placeable): Int = p[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: p.height
        val ascent = placeables.maxOf { baselineOf(it) }
        val descent = placeables.maxOf { it.height - baselineOf(it) }
        val width = if (available == Constraints.Infinity) used else available
        layout(width, ascent + descent) {
            title.place(0, ascent - baselineOf(title))
            tagline?.place(title.width + gap, ascent - baselineOf(tagline))
            stat?.place(width - stat.width, ascent - baselineOf(stat))
        }
    }
}
