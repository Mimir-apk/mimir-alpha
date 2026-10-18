package com.gruning.mimir.rewrite.feature.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.model.DescriptionText
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import androidx.compose.ui.graphics.graphicsLayer
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import com.gruning.mimir.rewrite.core.ui.MimirFlatLogoArt
import com.gruning.mimir.rewrite.core.ui.MimirLogoStage
import com.gruning.mimir.rewrite.core.ui.MimirProgressRing
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirTimeCopy
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.bottomUiReducedMotion
import com.gruning.mimir.rewrite.core.ui.mimirMemoryCacheWarm
import com.gruning.mimir.rewrite.core.ui.mimirProgressSpoken
import com.gruning.mimir.rewrite.core.ui.mimirSwapEnterSpec
import com.gruning.mimir.rewrite.core.ui.mimirSwapExitSpec
import com.gruning.mimir.rewrite.core.ui.rememberMimirArtWired
import com.gruning.mimir.rewrite.core.ui.rememberMimirColdArtReveal
import com.gruning.mimir.rewrite.core.ui.rememberMimirLogoInk
import java.time.ZoneId
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.max
import kotlin.math.min

/*
 * The launch card's three looks and the look switch (slices L5 and L6 of the Thor Bottom shelf redesign).
 *
 * One card, three faces, chosen ON the card with a global "Look" switch (policy 5). The card is the shelf's own (and Activity opens the very same
 * one through CompanionLaunchCardLayer), so a look is chosen once for every game and every place the card opens. The footer ([Opens on] [Look] [PLAY]) is
 * common to all three and lives with the card in CompanionPlatformShelf.kt; what is here is the part that changes with the look:
 *
 *   Cinematic  the game's hero drawn bare under a palette scrim (a layer behind the card's whole face), the 430 x 215 logo stage, title and meta.
 *   Journal    the cover beside the title, an accent eyebrow, a 2 x 2 facts grid, and the synopsis only when a real cached one exists.
 *   Logo Only  the stage on the glass and nothing else.
 *
 * Art law (R3/R8/R8a): wired art is drawn bare and FIT or cropped to its own seat, never blurred; nothing is drawn while it loads or after it fails; a file
 * that no longer exists is no art; a hero is only ever the game's own. Touch only (R9a): nothing here takes focus or holds the process-wide pad gate.
 */

/** The three faces of the launch card. */
enum class CompanionLaunchLook(
    /** What the app writes to its prefs, and the id suffix of the look's receipt. A stable word, never the ordinal. */
    val storageKey: String,
    /** What the switch says. */
    val label: String,
) {
    CINEMATIC("cinematic", "Cinematic"),
    JOURNAL("journal", "Journal"),
    LOGO_ONLY("logo_only", "Logo only"),
    ;

    /** The look a tap on the switch moves to: the three in turn, round. */
    val next: CompanionLaunchLook get() = values()[(ordinal + 1) % values().size]

    companion object {
        /** The default, and the look a missing, unknown or corrupt stored value reads as. */
        val Default = CINEMATIC

        fun fromStorageKey(raw: String?): CompanionLaunchLook = values().firstOrNull { it.storageKey == raw } ?: Default
    }
}

/**
 * What the Journal look shows that the library row does not carry: the totals the app holds (lib cannot reach them, so they arrive as plain values, as the
 * display choice does). Each is null when the app has nothing to say, which the card states plainly rather than inventing.
 */
data class CompanionLaunchCardFacts(
    /** Launch-to-return time summed over earlier sessions, in milliseconds. Null (or zero) when the app has no measured total for the game. */
    val sessionTotalMillis: Long? = null,
    /** RetroAchievements unlocked, and the game's total. Both null when no snapshot is held (not connected, not checked yet). */
    val achievementsDone: Int? = null,
    val achievementsTotal: Int? = null,
) {
    companion object {
        val None = CompanionLaunchCardFacts()
    }
}

/** A fact the grid has no value for says so in these words, and no others. */
internal const val CompanionJournalNeverPlayed = "Never played"
internal const val CompanionJournalNotTracked = "Not tracked"
internal const val CompanionJournalProgressUnavailable = "Progress unavailable"

internal const val CompanionJournalLastPlayedLabel = "Last played"
internal const val CompanionJournalLaunchesLabel = "Launches"

/** Mimir measures launch to return, never the time a game ran, so the word is never "playtime". */
internal const val CompanionJournalSessionTimeLabel = "Session time"
internal const val CompanionJournalAchievementsLabel = "Achievements"

internal data class CompanionJournalFact(val label: String, val value: String, val absent: Boolean)

internal data class CompanionJournalFacts(
    val lastPlayed: CompanionJournalFact,
    val launches: CompanionJournalFact,
    val sessionTime: CompanionJournalFact,
    val achievements: CompanionJournalFact,
    /** Set only when there are counts to draw a ring for. */
    val ring: Pair<Int, Int>?,
)

/**
 * The Journal's four facts, in words. Pure, so each absence is a test:
 *  - last played: "Today", "Yesterday", "3 days ago" ([MimirTimeCopy.daysAgo], capitalised), else "Never played";
 *  - launches: the count of launches sent (dispatch only, so never "plays"), else "Never played";
 *  - session time: the elapsed total ([MimirTimeCopy.total]) when it is above zero, else "Not tracked";
 *  - achievements: "12 of 40" with its ring when there is a valid snapshot, else "Progress unavailable".
 */
internal fun companionJournalFacts(game: GameRow, facts: CompanionLaunchCardFacts, nowMillis: Long, zone: ZoneId): CompanionJournalFacts {
    val lastPlayed = game.lastPlayedMillis
        ?.let { MimirTimeCopy.daysAgo(it, nowMillis, zone).replaceFirstChar(Char::titlecase) }
    val total = facts.sessionTotalMillis?.takeIf { it > 0L }
    val done = facts.achievementsDone
    val all = facts.achievementsTotal
    val progress = if (done != null && all != null) mimirProgressSpoken(done, all) else null
    return CompanionJournalFacts(
        lastPlayed = CompanionJournalFact(CompanionJournalLastPlayedLabel, lastPlayed ?: CompanionJournalNeverPlayed, absent = lastPlayed == null),
        launches = CompanionJournalFact(
            CompanionJournalLaunchesLabel,
            if (game.playCount > 0) game.playCount.toString() else CompanionJournalNeverPlayed,
            absent = game.playCount <= 0,
        ),
        sessionTime = CompanionJournalFact(
            CompanionJournalSessionTimeLabel,
            total?.let(MimirTimeCopy::total) ?: CompanionJournalNotTracked,
            absent = total == null,
        ),
        achievements = CompanionJournalFact(
            CompanionJournalAchievementsLabel,
            progress ?: CompanionJournalProgressUnavailable,
            absent = progress == null,
        ),
        ring = if (progress != null) done!! to all!! else null,
    )
}

// ── Geometry ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * The card is 470 dp wide: the 430 x 215 logo stage ([MimirLogoStage]) with 20 dp a side. (It was 400.)
 */
internal val CompanionLaunchCardWidth = 470.dp

/**
 * The room kept above and below the card for tap-outside-to-dismiss, the Bottom's only Back: a full touch target, so the strip is always usable. The
 * card's height is whatever its look needs up to the host's height less twice this; what is flexible (the stage, the cover) gives way first.
 */
internal const val CompanionLaunchCardDismissMarginDp = 48f

/** The room kept to the left and right of the card: 34 dp on the Thor's 538, never less than this on a narrower host. */
internal const val CompanionLaunchCardSideMarginDp = 24f

/** Above the stage, and the side inset of the text under it. */
private const val StageTopInsetDp = 12
private const val StageTextGapDp = 4
private const val StageSideInsetDp = 20

/** A stage never shrinks below this (a larger font takes height from the stage, as on the Now Playing takeover, before it takes a word). */
private const val StageMinHeightDp = 96

private const val JournalSideInsetDp = 16
private const val JournalTopInsetDp = 12
private const val JournalCoverWidthDp = 150
private const val JournalCoverHeightDp = 200
private const val JournalCoverMinHeightDp = 96
private const val JournalColumnGapDp = 12
private const val JournalBlockGapDp = 8

// ── Tags ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

const val CompanionLaunchCardTag = "companion-launch-card"
const val CompanionLaunchCardHeroTag = "companion-launch-card-hero"
const val CompanionLaunchCardStageTag = "companion-launch-card-stage"
const val CompanionLaunchCardTitleTag = "companion-launch-card-title"
const val CompanionLaunchCardMetaTag = "companion-launch-card-meta"
const val CompanionLaunchCardCoverTag = "companion-launch-card-cover"
const val CompanionLaunchCardEyebrowTag = "companion-launch-card-eyebrow"
const val CompanionLaunchCardFactsTag = "companion-launch-card-facts"
const val CompanionLaunchCardSynopsisTag = "companion-launch-card-synopsis"
const val CompanionLaunchCardFooterTag = "companion-launch-card-footer"
const val CompanionLaunchCardOpensOnTag = "companion-launch-card-opens-on"
const val CompanionLaunchCardLookTag = "companion-launch-card-look"
const val CompanionLaunchCardPlayTag = "companion-launch-card-play"
const val CompanionLaunchCardFactLastPlayedTag = "companion-launch-card-fact-last-played"
const val CompanionLaunchCardFactLaunchesTag = "companion-launch-card-fact-launches"
const val CompanionLaunchCardFactSessionTimeTag = "companion-launch-card-fact-session-time"
const val CompanionLaunchCardFactAchievementsTag = "companion-launch-card-fact-achievements"

/** The scrolling frame every look's body sits in: its bounds are the body's allocation, which ends above the footer's ring room. */
const val CompanionLaunchCardBodyTag = "companion-launch-card-body"

// ── The hero behind Cinematic ────────────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * Cinematic's backdrop: the game's own hero, drawn bare and cropped to the card's whole face, under a palette scrim for legibility (never blurred). It
 * is a layer behind the face, not part of it, so it runs under the footer too. Nothing is drawn with no wired hero, while it loads, or after it fails:
 * the glass shows, which is the "same layout on glass" (never a blurred cover, never another game's art). It crosses on the swap tier.
 */
@Composable
internal fun BoxScope.CompanionLaunchHero(visible: Boolean, game: GameRow) {
    val palette = LocalMimirPalette.current
    val reduced = remember { bottomUiReducedMotion() }
    val hero = remember(game.heroArtUri) { game.heroArtUri?.trim()?.takeIf { it.isNotEmpty() } }
    // A hero whose file is gone is no hero; one still being checked counts as wired and draws nothing until it loads.
    val wired = rememberMimirArtWired(hero)
    AnimatedVisibility(
        visible = visible && hero != null && wired,
        enter = fadeIn(mimirSwapEnterSpec(reduced)),
        exit = fadeOut(mimirSwapExitSpec(reduced)),
        modifier = Modifier.matchParentSize(),
    ) {
        // A hero draws the bare wired image or NOTHING (never MimirBoxArt, whose cover ladder falls back to the book: that is a cover rule, R8a, not a hero rule).
        // Coil draws nothing while it loads or after it fails, there is no placeholder or error painter, and the scrim waits for the image, so a hero that is
        // deleted, loading or failed leaves the glass untouched in every frame, an exit fade included.
        Box(Modifier.fillMaxSize().testTag(CompanionLaunchCardHeroTag)) {
            if (hero != null) CompanionLaunchHeroArt(hero, Modifier.fillMaxSize())
        }
    }
}

/**
 * The hero and its legibility scrim as ONE draw. The request is the product's hero request ([mimirHomeStaticHeroRequest], the Home hero's decode envelope and cache
 * key with a placeholder from that key), so a hero the Home shelf already decoded paints on the card's first frame; and the scrim is drawn in the same pass as the
 * image and only when the painter has something to draw, so the two arrive on the same frame and fade together with the layer above them. The scrim used to be added
 * a recomposition after the image reported `Success`: a hero that landed late showed bright for a frame and then darkened. Policy Q13 (2026-10-14): a hero that
 * is warm at the card's birth paints whole; one that arrives COLD fades in from nothing on FocusMs, and the fade wraps the scrim's draw too (the layer is outside it), so
 * the two rise together and the bare hero is never on a frame.
 */
@Composable
internal fun CompanionLaunchHeroArt(hero: String, modifier: Modifier = Modifier) {
    val palette = LocalMimirPalette.current
    val context = LocalContext.current
    val envelope = rememberMimirHeroDecodeEnvelope()
    val request = remember(hero, envelope, context) { mimirHomeStaticHeroRequest(context, hero, envelope) }
    val painter = rememberAsyncImagePainter(model = request, contentScale = ContentScale.Crop)
    val scrim = remember(palette) {
        Brush.verticalGradient(listOf(palette.surfaceBase.copy(alpha = 0.60f), palette.surfaceBase.copy(alpha = 0.88f)))
    }
    val warm = remember(request, context) { mimirMemoryCacheWarm(context, mimirHeroCacheKey(hero, envelope)) }
    val reveal = rememberMimirColdArtReveal(identity = request, warm = warm, state = painter.state, request = request)
    Image(
        painter = painter,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.graphicsLayer { alpha = reveal() }.drawWithContent {
            drawContent()
            // Read here, in the draw phase: the painter's state is the one the image above just drew from.
            val state = painter.state
            if (state is AsyncImagePainter.State.Success || (state is AsyncImagePainter.State.Loading && state.painter != null)) drawRect(scrim)
        },
    )
}

// ── Cinematic and Logo Only ──────────────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * The stage look. [cinematic] true adds the title and meta under the stage (Logo Only is the stage and nothing else). The stage is the shared
 * [MimirLogoStage] (430 x 215, ink-fit) and keeps its 2:1 shape: when the height left (the card's cap less the footer and the words) is short of it, the
 * stage shrinks, down to [StageMinHeightDp], so a larger font or a second title line never pushes a target off the card.
 *
 * A wired logo is the stage. With none (or a file that is gone) the title takes the stage, in Title, and is not said twice: Cinematic then keeps only the
 * meta under it, and Logo Only has the title and nothing else. With a logo, Logo Only says no title at all (the art carries its name).
 */
@Composable
internal fun CompanionLaunchStageBody(game: GameRow, platformLabel: String?, cinematic: Boolean, modifier: Modifier = Modifier) {
    val palette = LocalMimirPalette.current
    val logo = remember(game.flatArtUri) { game.flatArtUri?.trim()?.takeIf { it.isNotEmpty() } }
    val logoWired = rememberMimirArtWired(logo)
    val logoShown = logo != null && logoWired
    val meta = if (cinematic) platformLabel?.trim()?.takeIf { it.isNotEmpty() } else null
    val titleUnderStage = cinematic && logoShown
    CompanionLaunchBodyFrame(modifier) { limit -> Layout(
        modifier = Modifier.fillMaxWidth(),
        content = {
            Box(Modifier.layoutId("stage").testTag(CompanionLaunchCardStageTag), contentAlignment = Alignment.Center) {
                if (logoShown) {
                    MimirFlatLogoArt(title = game.title, logoUri = logo, modifier = Modifier.fillMaxSize(), ink = rememberMimirLogoInk(logo))
                } else {
                    Text(
                        text = game.title,
                        modifier = Modifier.testTag(CompanionLaunchCardTitleTag),
                        style = MimirTypeScale.Title,
                        color = palette.textPrimary,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (titleUnderStage || meta != null) {
                Box(Modifier.layoutId("text")) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(MimirSpacing.xs / 2)) {
                        if (titleUnderStage) {
                            Text(
                                text = game.title,
                                modifier = Modifier.testTag(CompanionLaunchCardTitleTag),
                                style = MimirTypeScale.OverlayTitle,
                                color = palette.textPrimary,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (meta != null) {
                            Text(
                                text = meta,
                                modifier = Modifier.testTag(CompanionLaunchCardMetaTag),
                                style = MimirTypeScale.Support,
                                color = palette.textSecondary,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val top = StageTopInsetDp.dp.roundToPx()
        val gap = StageTextGapDp.dp.roundToPx()
        val textPlaceable = measurables.firstOrNull { it.layoutId == "text" }
            ?.measure(Constraints(maxWidth = (width - 2 * StageSideInsetDp.dp.roundToPx()).coerceAtLeast(0)))
        val used = top + (textPlaceable?.let { gap + it.height } ?: 0)
        val fullStageH = MimirLogoStage.height.roundToPx()
        val available = if (limit != Int.MAX_VALUE) limit - used else fullStageH
        val stageH = available.coerceIn(min(StageMinHeightDp.dp.roundToPx(), fullStageH), fullStageH)
        val stageW = min(min(MimirLogoStage.width.roundToPx(), (stageH * MimirLogoStage.AspectRatio).toInt()), width)
        val stagePlaceable = measurables.first { it.layoutId == "stage" }.measure(Constraints.fixed(stageW, stageH))
        layout(width, used + stageH) {
            stagePlaceable.place((width - stageW) / 2, top)
            textPlaceable?.place((width - textPlaceable.width) / 2, top + stageH + gap)
        }
    } }
}

// ── Journal ──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * The Journal look: the cover (150 x 200, fit, the same face the shelf wears) beside one accent eyebrow (the platform), the title in two lines and the 2 x 2
 * facts grid ([companionJournalFacts]); under them the synopsis in three lines, ONLY when a cached real one exists (otherwise the row is absent, never
 * invented). The cover shrinks, keeping its slot, when the card's cap asks for height, and the synopsis gives up lines (three, then two, then one) before anything else does.
 */
@Composable
internal fun CompanionLaunchJournalBody(
    game: GameRow,
    platformLabel: String?,
    facts: CompanionLaunchCardFacts,
    nowMillis: Long,
    zone: ZoneId,
    modifier: Modifier = Modifier,
) {
    val palette = LocalMimirPalette.current
    val journal = remember(game, facts, nowMillis, zone) { companionJournalFacts(game, facts, nowMillis, zone) }
    val eyebrow = platformLabel?.trim()?.takeIf { it.isNotEmpty() }
    val synopsis = remember(game.description) { game.description?.let(DescriptionText::decodeEntities)?.trim()?.takeIf { it.isNotEmpty() } }
    val measurer = rememberTextMeasurer()
    // Subcomposed, because the synopsis's line count depends on the height the head leaves it: three lines when they fit and fewer (never a cut line, never
    // a body reaching into the footer's ring room) when a larger font and a long facts column leave less. Only the chosen count is ever composed.
    CompanionLaunchBodyFrame(modifier) { limit -> SubcomposeLayout(modifier = Modifier.fillMaxWidth()) { constraints ->
        val width = constraints.maxWidth
        val inset = JournalSideInsetDp.dp.roundToPx()
        val top = JournalTopInsetDp.dp.roundToPx()
        val blockGap = JournalBlockGapDp.dp.roundToPx()
        val coverSlotW = JournalCoverWidthDp.dp.roundToPx()
        val sideX = inset + coverSlotW + JournalColumnGapDp.dp.roundToPx()
        val sidePlaceable = subcompose("side") {
            Column(verticalArrangement = Arrangement.spacedBy(MimirSpacing.xs)) {
                if (eyebrow != null) {
                    Text(
                        text = eyebrow.uppercase(),
                        modifier = Modifier.testTag(CompanionLaunchCardEyebrowTag),
                        style = MimirTypeScale.Eyebrow,
                        color = palette.accentBright,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = game.title,
                    modifier = Modifier.testTag(CompanionLaunchCardTitleTag),
                    style = MimirTypeScale.OverlayTitle,
                    color = palette.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.size(MimirSpacing.xs))
                Column(Modifier.fillMaxWidth().testTag(CompanionLaunchCardFactsTag), verticalArrangement = Arrangement.spacedBy(MimirSpacing.sm)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MimirSpacing.sm)) {
                        JournalFactCell(journal.lastPlayed, CompanionLaunchCardFactLastPlayedTag, Modifier.weight(1f))
                        JournalFactCell(journal.launches, CompanionLaunchCardFactLaunchesTag, Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MimirSpacing.sm)) {
                        JournalFactCell(journal.sessionTime, CompanionLaunchCardFactSessionTimeTag, Modifier.weight(1f))
                        JournalFactCell(journal.achievements, CompanionLaunchCardFactAchievementsTag, Modifier.weight(1f), ring = journal.ring)
                    }
                }
            }
        }.first().measure(Constraints(maxWidth = (width - sideX - inset).coerceAtLeast(0)))
        val fullCoverH = JournalCoverHeightDp.dp.roundToPx()
        val minCoverH = min(JournalCoverMinHeightDp.dp.roundToPx(), fullCoverH)
        val bounded = limit != Int.MAX_VALUE
        val synopsisWidth = (width - 2 * inset).coerceAtLeast(0)
        // The most lines (three at most) whose height still leaves the cover its minimum beside the facts column; none, if even one does not fit (the row is then
        // absent for want of room, and if the facts alone still exceed the allocation the body scrolls inside it, below).

        val synopsisLines = if (synopsis == null) 0 else {
            var lines = SynopsisMaxLines
            while (lines > 0 && bounded) {
                val textH = measurer.measure(
                    text = synopsis,
                    style = MimirTypeScale.Support,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = lines,
                    constraints = Constraints(maxWidth = synopsisWidth),
                    density = this,
                ).size.height
                if (top + blockGap + textH + max(minCoverH, sidePlaceable.height) <= limit) break
                lines--
            }
            lines
        }
        val synopsisPlaceable = if (synopsis == null || synopsisLines == 0) null else subcompose("synopsis") {
            Text(
                text = synopsis,
                modifier = Modifier.testTag(CompanionLaunchCardSynopsisTag),
                style = MimirTypeScale.Support,
                color = palette.textSecondary,
                maxLines = synopsisLines,
                overflow = TextOverflow.Ellipsis,
            )
        }.first().measure(Constraints(maxWidth = synopsisWidth))
        val fixed = top + (synopsisPlaceable?.let { blockGap + it.height } ?: 0)
        val available = if (bounded) limit - fixed else fullCoverH
        val coverH = available.coerceIn(minCoverH, fullCoverH)
        val coverW = (coverH * JournalCoverWidthDp / JournalCoverHeightDp.toFloat()).toInt()
        val coverPlaceable = subcompose("cover") {
            Box(
                Modifier.testTag(CompanionLaunchCardCoverTag).clip(RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                // The one art law (R3/R8/R8a): wired art bare and fit, nothing while it loads or fails, a deleted file is no art so the book.
                LibraryGameFace(game, Modifier.fillMaxSize())
            }
        }.first().measure(Constraints.fixed(coverW, coverH))
        val headH = max(coverH, sidePlaceable.height)
        layout(width, fixed + headH) {
            coverPlaceable.place(inset + (coverSlotW - coverW) / 2, top)
            sidePlaceable.place(sideX, top)
            synopsisPlaceable?.place(inset, top + headH + blockGap)
        }
    } }
}

/** The synopsis is three lines when it can be; a squeeze takes lines from it before it takes anything from the facts or the footer. */
private const val SynopsisMaxLines = 3

@Composable
private fun JournalFactCell(fact: CompanionJournalFact, tag: String, modifier: Modifier, ring: Pair<Int, Int>? = null) {
    val palette = LocalMimirPalette.current
    Column(modifier.testTag(tag).semantics(mergeDescendants = true) {}) {
        Text(
            text = fact.label,
            style = MimirTypeScale.SupportCompact,
            color = palette.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (ring != null) {
                // The ring says the counts to a screen reader too; the words beside it say them already, so the ring adds nothing to the merged node.
                Box(Modifier.clearAndSetSemantics { }) {
                    MimirProgressRing(done = ring.first, total = ring.second, size = 20.dp, stroke = 3.dp, palette = palette)
                }
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = fact.value,
                style = MimirTypeScale.Support,
                // An absence is quieter than a fact: the value is the words "Not tracked", not a number that could be mistaken for one.
                color = if (fact.absent) palette.textSecondary else palette.textPrimary,
                fontWeight = if (fact.absent) FontWeight.Normal else FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The body's allocation, as a frame that never lets the body reach past it: [content] is told the height it was given (`Int.MAX_VALUE` when unbounded) and
 * lays itself out to fit (the stage shrinks, the cover shrinks, the synopsis gives up lines). If what it cannot shrink (the title, the facts) is still taller than
 * the allocation, which only happens at a font scale well above 1.3, the body scrolls inside the frame, so nothing is ever drawn over the footer's ring room and
 * nothing is cut except by the frame's own edge while it is scrolled.
 */
@Composable
private fun CompanionLaunchBodyFrame(modifier: Modifier, content: @Composable (limit: Int) -> Unit) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val limit = if (constraints.hasBoundedHeight) constraints.maxHeight else Int.MAX_VALUE
        Box(Modifier.fillMaxWidth().testTag(CompanionLaunchCardBodyTag).verticalScroll(rememberScrollState())) { content(limit) }
    }
}
