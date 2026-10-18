package com.gruning.mimir.rewrite.core.ui

import androidx.compose.runtime.rememberUpdatedState
import android.util.Log
import java.io.File
import androidx.compose.ui.unit.isSpecified
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.background
import coil.compose.rememberAsyncImagePainter
import coil.compose.AsyncImagePainter
import coil.imageLoader
import coil.memory.MemoryCache
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.PI
import kotlin.math.sin
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp

/**
 * One release-alpha fallback law for every art surface .
 *
 * The fallback (glyph, book, console glyph, letter) is the answer to ONE question only: is there no art wired? A wired
 * source that is still loading draws nothing (an identity the app already knows is false would flash on cold hydration),
 * and a wired source that FAILED to decode draws nothing either: the host ground, the focus ring and the label stay, and the
 * failure is logged. There is deliberately no "decode failed" input here, so no caller can bring the fallback back for one.
 *
 * What "wired" means is [mimirArtSourceWired]: a source whose local file no longer exists is NOT wired (R8a), so it shows
 * the normal no-art fallback. Pass `rememberMimirArtWired(model)` (the off-main resolver, never a composition-thread file check) as
 * [hasArtSource].
 */
fun mimirArtFallbackVisible(hasArtSource: Boolean): Boolean = !hasArtSource

/**
 * is [model] a wired art source? Null and blank are not. A custom pick whose FILE NO LONGER EXISTS is
 * not either: it counts as unwired and shows the normal no-art fallback (the console glyph or the book). Only a file that
 * exists but fails to decode is "wired and failed", which draws nothing ([mimirArtFallbackVisible]).
 *
 * The existence check applies where the source is local: a [File], a bare absolute path, or a `file:` URI. A remote or
 * provider source (`http(s)`, `content:`, a resource id) cannot be checked cheaply and counts as wired; its failure
 * draws nothing. [fileExists] is injectable so the law is a pure, host-testable function.
 *
 * This is a DISK STAT: call it only from an IO coroutine (the Bottom's decoders do). Composition asks
 * [rememberMimirArtWired], which resolves it off the main thread.
 */
fun mimirArtSourceWired(model: Any?, fileExists: (String) -> Boolean = { File(it).exists() }): Boolean {
    val local = when (model) {
        null -> return false
        is File -> model.path
        is CharSequence -> mimirArtLocalPath(model.toString()) ?: return model.isNotBlank()
        else -> mimirArtLocalPath(model.toString()) ?: return true
    }
    return fileExists(local)
}

/** The filesystem path a source string names, or null when it is not a local file (blank, remote, `content:`, a resource id). */
internal fun mimirArtLocalPath(source: String): String? {
    val text = source.trim()
    return when {
        text.startsWith("file:") -> runCatching { java.net.URI(text).path }.getOrNull()?.takeIf { it.isNotEmpty() }
        text.startsWith("/") -> text
        else -> null
    }
}

/**
 * The model to hand a painter: [model] when it is a wired source ([mimirArtSourceWired]), null when it is not, so a missing
 * file takes the same no-art path as a source that was never set. Resolved off-main once per model ([rememberMimirArtWired]); while that stat is pending the source counts as wired.
 */
@Composable
fun rememberMimirArtModel(model: Any?): Any? {
    val wired = rememberMimirArtWired(model)
    return if (wired) model else null
}

/** The logcat tag a wired art that failed to load is reported on (R8: it draws nothing, so the log is the only trace). */
const val MimirArtFailureLogTag = "MimirArt"

/**
 * R8: a wired art that fails shows NOTHING, and the failure is logged, once per model. Call it with the painter's failed state
 * where a surface observes one; it draws nothing and holds no state beyond the effect.
 */
@Composable
fun MimirArtFailureLog(failed: Boolean, model: Any?) {
    LaunchedEffect(failed, model) {
        if (failed) runCatching { Log.w(MimirArtFailureLogTag, "wired art failed to load; drawing nothing: $model") }
    }
}

/**
 * A wired custom or sourced image never displays fallback art, including during a cold start.
 *
 * The square PLATE a surface seats behind its art is part of the fallback, exactly like the glyph and the book. It may
 * exist only for an item that truly has no art. An item with art wired draws NOTHING in its art area while the bitmap
 * is pending (cold start, a fetch landing), then fades the art in over whatever surface is behind it, so a PNG with
 * transparent pixels sits on that surface and never on a square. A wired source that FAILED to decode is answered by
 * nothing at all (the fallback is for "no art wired" only, see [mimirArtFallbackVisible]).
 */
fun mimirArtPlateVisible(hasArtSource: Boolean): Boolean = !hasArtSource

/**
 * Mimir Alpha :core-ui — the ONE box-art component.
 *
 * A 3:4 box-art shell (the focused ROM hero is a 3D media shell) with the
 * signature GOLD hairline. This REPLACES the holding-frame duplication that had started across surfaces
 * (feature-library's `MediaArtHoldingFrame`, and the shelf's tile art) — one component, one look.
 *
 * The HONEST fallback (the core reason this exists): when no art is wired, it does NOT render a broken
 * image or an empty gap. It draws the title's initial glyph on a themed panel — an intentional
 * "art goes here" placeholder that reads as premium, never as a failure.
 *
 * Art loading itself is deliberately NOT owned here: [artRef] is an opaque reference (a media_art path,
 * a resolved bitmap, etc. — the companion payload already carries `heroArtRef`). This module depends on
 * nothing but Compose (no image loader is in the offline cache), so an [artContent] slot lets the caller
 * inject a real image painter later without changing the frame's chrome, spacing, or fallback. Until a
 * loader is wired, pass `artRef = null` (or omit [artContent]) and the honest glyph fallback shows.
 *
 * @param title the game title — its initial seeds the fallback glyph; also used for a11y.
 * @param modifier caller sizing (typically `Modifier.width(...)` or `fillMaxWidth()`); the frame owns its 3:4 aspect.
 * @param frameAspectRatio width / height for the physical media shell. The default remains the common 3:4
 *   cover; callers with platform-measured media may provide that bounded ratio so Fit art never sits inside
 *   unrelated letterbox bars.
 * @param platformLabel optional short platform tag shown as a quiet footer pill inside the frame.
 * @param focused focus state — drives the gold ring (memory: focused tile gets the accent hairline).
 * @param palette the active theme; defaults to the CompositionLocal-provided one.
 * @param artRef opaque art reference. When non-null AND [artContent] is supplied, the caller's art fills the frame; otherwise the honest fallback renders.
 * @param artContent optional slot the caller uses to paint real art (e.g. an Image) into the 3:4 box.
 * @param fallbackContent optional richer no-art fill (e.g. a per-game typographic cover with a platform
 *   watermark). When supplied and no real art is present, this renders INSTEAD of the plain glyph — so a
 *   fetched cover later drops into [artContent] and replaces exactly this frame. Omit for the glyph default.
 * @param showFallbackTitle whether the honest glyph fallback prints the title UNDER the glyph. Pass `false` from
 *   a gamelist tile that already shows the title beneath the frame (avoids a doubled title); keep the
 *   default `true` for a standalone hero where the frame is the only place the title appears. Ignored when
 *   [fallbackContent] is supplied.
 */
@Composable
fun MediaArtFrame(
    title: String,
    modifier: Modifier = Modifier,
    frameAspectRatio: Float = 3f / 4f,
    platformLabel: String? = null,
    focused: Boolean = false,
    palette: MimirThemePalette = LocalMimirPalette.current,
    artRef: Any? = null,
    artContent: (@Composable (artRef: Any) -> Unit)? = null,
    fallbackContent: (@Composable BoxScope.() -> Unit)? = null,
    /**
     * Policy 2026-08-25. False when the caller does not YET know whether this game has art, so the
     * frame shows its plain themed surface and wears no costume at all.
     *
     * Both existing no-art fills are CLAIMS: the glyph says *there is art and it failed to decode*, a
     * [fallbackContent] book says *this game has no art*. Neither is true while the answer is still
     * unknown, and the product may not paint a claim it has not established. This is the same treatment
     * [MimirBoxArt] already gives a known source that is still loading — the pattern existed one layer
     * down and this branch simply could not reach it.
     *
     * Defaults true, so every existing caller is byte-identical.
     */
    showNoArtCostume: Boolean = true,
    showFallbackTitle: Boolean = true,
) {
    // ONE resolved source state for the plate AND the content (R8a): a local art whose file is gone is no art, so the frame takes
    // its no-art fallback and its plate together instead of handing the missing path to [artContent], which would draw the glyph
    // on a bare frame. The stat is off-main; while it is pending the source counts as wired.
    val artWired = rememberMimirArtWired(artRef)
    // The verdict is resolved HERE, once, and handed down to [artContent] ([LocalMimirArtVerdict]): an art composable inside it takes
    // this answer instead of stat'ing the same file on its own, so the plate above and the content below cannot disagree.
    val artVerdict = remember(artRef) { MimirArtVerdict(artRef, wired = true) }
    val hasArt = artRef != null && artContent != null && artWired
    // The plate is the no-art fallback's seat: never behind wired art, and never while the
    // caller has not yet established whether art exists (showNoArtCostume = false draws nothing at all).
    val plateVisible = mimirArtPlateVisible(hasArtSource = hasArt) && showNoArtCostume
    Box(
        modifier = modifier
            .aspectRatio(frameAspectRatio.coerceIn(0.45f, 1.60f))
            .clip(RoundedCornerShape(MimirSpacing.radiusCard))
            .then(
                if (plateVisible) {
                    Modifier.background(
                        Brush.verticalGradient(
                            listOf(palette.surfaceHigh, palette.tileFill),
                        ),
                    )
                } else {
                    Modifier
                },
            )
            .border(
                width = if (focused) MimirSpacing.focusRingWidth else MimirSpacing.hairlineWidth,
                // The GOLD hairline (accent) when focused; a quiet outline at rest.
                color = if (focused) palette.accent else palette.outline,
                shape = RoundedCornerShape(MimirSpacing.radiusCard),
            ),
        contentAlignment = Alignment.Center,
    ) {
        when {
            // Caller paints real art into the 3:4 box; the frame keeps the ring + platform pill.
            hasArt -> CompositionLocalProvider(LocalMimirArtVerdict provides artVerdict) { artContent!!(artRef!!) }
            // Nothing is known yet: the frame's own themed surface, and no claim of any kind.
            !showNoArtCostume -> Unit
            // A richer no-art fill (e.g. a per-game typographic cover) — the exact slot a fetched cover replaces.
            fallbackContent != null -> fallbackContent()
            else -> MediaArtFallbackGlyph(title = title, focused = focused, palette = palette, showTitle = showFallbackTitle, fitTitle = true)
        }

        // A quiet platform tag, bottom-start, inside the frame — reads on both art and the fallback.
        if (platformLabel != null) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(MimirSpacing.sm)
                    .clip(RoundedCornerShape(MimirSpacing.radiusPill))
                    .background(palette.surfaceBase.copy(alpha = 0.72f))
                    .padding(horizontal = MimirSpacing.sm, vertical = 2.dp),
            ) {
                Text(
                    platformLabel,
                    style = MimirTypeScale.Pill,
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The coverless-game stand-in: a bound Norse BOOK in Mimir's identity rather than a generic
 * disc/cartridge, broken-image box or fallback glyph. It uses Mimir's
 * own identity, resembling the companion folder tome, with the GAME'S TITLE set on the leather so a coverless
 * game stays recognisable. See [MediaArtBookCover]; this thin wrapper keeps the historical name/signature so
 * every caller that used to degrade to the honest glyph now degrades to the book instead.
 *
 * PUBLIC so a caller wiring a real image loader can render the SAME book in the loader's loading/error slot.
 * [showTitle] is retained for source compatibility (the title is always part of the book now);
 * [platformIdValue] applies the per-platform leather treatment when the caller knows it. [fitTitle] sets the title
 * to the book it is on, and [showMannaz] says whether its embossed Mannaz is drawn (see [MediaArtBookCover]).
 */
@Composable
fun MediaArtFallbackGlyph(
    title: String,
    focused: Boolean = false,
    palette: MimirThemePalette = LocalMimirPalette.current,
    showTitle: Boolean = true,
    platformIdValue: String? = null,
    fitTitle: Boolean = false,
    showMannaz: Boolean = true,
    /** See [MediaArtBookCover]'s `mannazClearBottomDp`: the rune stands at least this far above the book's bottom edge; 0 leaves it where it always was. */
    mannazClearBottomDp: Float = 0f,
) {
    MediaArtBookCover(
        title = title,
        platformIdValue = platformIdValue,
        focused = focused,
        palette = palette,
        fitTitle = fitTitle,
        showMannaz = showMannaz,
        mannazClearBottomDp = mannazClearBottomDp,
    )
}

/** The book's title `Text`, for a test that has to read its layout. */
const val MediaArtBookTitleTag = "media-art-book-title"

/**
 * Where the embossed Mannaz begins on a coverless book [bookWidthDp] x [bookHeightDp], in dp down from the book's top: the rune stands centred on the leaf at 0.84 of the book's
 * height, 0.18 of the leaf wide and 1.06 times that tall, between the spine (0.075 of the width, at least 3 dp) and the fore-edge (0.055, at least 2 dp). One formula for what
 * a title is fitted above and what a mark seated over the book stands clear of; [MediaArtBookCover]'s drawing is the same numbers in pixels.
 */
fun mimirBookMannazTopDp(bookWidthDp: Float, bookHeightDp: Float, clearBottomDp: Float = 0f): Float {
    val spine = maxOf(bookWidthDp * 0.075f, 3f)
    val foreEdge = maxOf(bookWidthDp * 0.055f, 2f)
    val leaf = (bookWidthDp - spine - foreEdge).coerceAtLeast(0f)
    val half = leaf * 0.18f * 1.06f / 2f
    val centre = bookHeightDp * 0.84f
    // [clearBottomDp]: the rune's lowest tip stands at least this far above the book's bottom edge (a plate seated there, see [MediaArtBookCover]'s `mannazClearBottomDp`).
    val lifted = if (clearBottomDp > 0f && centre + half > bookHeightDp - clearBottomDp) bookHeightDp - clearBottomDp - half else centre
    return lifted - half
}

/**
 * A book title set to the book: [sizeSp] is the largest size from the book's own down to the type scale's floor at which the title fits [maxLines]
 * lines inside the room the leather leaves it AND no line ends inside a word. [fits] is false only when even the floor does not fit, or would still cut
 * a word: the title then stays at the floor and ends in an ellipsis, in [maxLines] lines (fewer when the room holds fewer, or when a word the width cannot
 * hold begins the line the ellipsis then closes). [hyphenatedText] is null for a title set as it was given; when no size holds the words whole but a size
 * holds the title with the words too wide for a line broken at a hyphen, it is the title with a soft hyphen at each of those breaks, and it is the text to
 * draw at [sizeSp] (see [MimirBookTitleHyphenation]). With [fits] false it is the same text cut to [maxLines] lines at the floor, the last ending in the
 * ellipsis ([mimirBookTitleHyphenatedEllipsis]).
 */
internal data class MimirBookTitleFit(val sizeSp: Float, val maxLines: Int, val fits: Boolean, val hyphenatedText: String? = null)

/**
 * The index of the first line of [layout] that ends INSIDE a word, or -1 when every line ends where the text may be broken. A line end is a place to break when
 * either character beside it is whitespace (the wrap space stays on the line it closes), when the character before it is a hyphen, a dash, a slash or a
 * backslash, or when either character beside it belongs to a script written without spaces between words ([breaksBetweenCharacters]: Han, kana, Thai and the
 * like), where every character edge is a legal break. The last line is never a cut: it ends with the text, or with the ellipsis the caller chose. A word wider
 * than the width has no such place to break, so the text engine splits it ("Earthbo" / "und"), which is exactly what this finds. Hangul is written with spaces
 * and stays checked.
 */
internal fun mimirBookTitleFirstCutLine(layout: androidx.compose.ui.text.TextLayoutResult): Int = mimirBookTitleCutLines(layout).firstOrNull() ?: -1

/** Every line of [layout] that ends INSIDE a word, in order: the lines [mimirBookTitleFirstCutLine] looks for the first of. */
internal fun mimirBookTitleCutLines(layout: androidx.compose.ui.text.TextLayoutResult): List<Int> {
    val text = layout.layoutInput.text.text
    val cuts = ArrayList<Int>()
    for (line in 0 until layout.lineCount - 1) {
        val next = layout.getLineStart(line + 1)
        if (next <= 0 || next >= text.length) continue
        val before = text.codePointBefore(next)
        val after = text.codePointAt(next)
        val atEdge = isBookTitleSpace(before) || isBookTitleSpace(after) ||
            before == '-'.code || before == 0x2010 || before == 0x2013 || before == 0x2014 || before == '/'.code || before == '\\'.code ||
            breaksBetweenCharacters(before) || breaksBetweenCharacters(after)
        if (!atEdge) cuts.add(line)
    }
    return cuts
}

private fun isBookTitleSpace(codePoint: Int): Boolean = Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)

/**
 * True for a character of a script that is written without spaces between words, so a line may end on either side of it: Han, Hiragana, Katakana, Bopomofo,
 * Thai, Lao, Khmer and Myanmar, plus the CJK symbols and punctuation (U+3000-U+303F) and the fullwidth forms (U+FF00-U+FFEF). By code point, so a character
 * outside the Basic Multilingual Plane counts as one.
 */
private fun breaksBetweenCharacters(codePoint: Int): Boolean {
    if (codePoint in 0x3000..0x303F || codePoint in 0xFF00..0xFFEF) return true
    return when (Character.UnicodeScript.of(codePoint)) {
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.BOPOMOFO,
        Character.UnicodeScript.THAI,
        Character.UnicodeScript.LAO,
        Character.UnicodeScript.KHMER,
        Character.UnicodeScript.MYANMAR,
        -> true
        else -> false
    }
}

/** The smallest a book's eyebrow is set when its name needs the room (the label's own size is 7 sp, tooled small on purpose; this is the last step down before it is cut). */
internal const val MimirBookEyebrowFloorSp = 5.5f

/** The book title's layout at a size in sp, exactly as the title `Text` sets it (bold, 1.08 line height, [maxLines] with an ellipsis) in [widthPx]: the measure [mimirFitBookTitle] is driven with. */
internal fun mimirBookTitleMeasure(
    measurer: androidx.compose.ui.text.TextMeasurer,
    title: String,
    base: androidx.compose.ui.text.TextStyle,
    widthPx: Int,
    maxLines: Int,
): (sizeSp: Float) -> androidx.compose.ui.text.TextLayoutResult = { sp ->
    measurer.measure(
        text = androidx.compose.ui.text.AnnotatedString(title),
        style = mimirBookTitleStyleAt(base, sp),
        overflow = TextOverflow.Ellipsis,
        maxLines = maxLines,
        constraints = androidx.compose.ui.unit.Constraints(maxWidth = widthPx),
    )
}

/**
 * Chooses a book title's size. [measure] lays the title out at a size in sp, capped at [maxLines] lines with an ellipsis, in the width the book gives it.
 * Sizes run from [maxSp] down to [minSp] in half-sp steps, and the title is settled in this order:
 *  1. The first size whose layout neither drops text (past [maxLines]), nor stands taller than [availableHeightPx], nor breaks a word across lines (see
 *     [mimirBookTitleFirstCutLine]) wins, exactly as it always did. A title that holds its words whole at any size never reaches the next step.
 *  2. Only if no size does, and [hyphenation] is given: from [maxSp] down again, the first size at which the words too wide for a line are broken at a
 *     hyphen (a soft hyphen the hyphenator chose, see [MimirBookTitleHyphenation]) and the layout of that text fits the same room and line budget, with every
 *     line that ends inside a word ending at one of those hyphens. A forced cut is never accepted. A title with a script written without spaces (Han, kana,
 *     Thai and the like, [breaksBetweenCharacters]) breaks between characters and is not hyphenated.
 *  3. Otherwise, with [hyphenation] and a hyphen that shows, the floor keeps the hyphenated title cut to the room, its last line ending in the ellipsis
 *     ([mimirBookTitleHyphenatedEllipsis]): "Poké-" / "mon" / "Diamond..." and not "Poke...".
 *  4. Otherwise the floor keeps the word rule's old answer: the title keeps the floor and is cut by `maxLines` at the first line that would have split a
 *     word, so the word ends in an ellipsis instead of a break.
 * Pure, so the floor and the ellipsis are decided in one place and a test can drive it with any measure.
 */
internal fun mimirFitBookTitle(
    maxSp: Float,
    minSp: Float,
    maxLines: Int,
    availableHeightPx: Float,
    measure: (sizeSp: Float) -> androidx.compose.ui.text.TextLayoutResult,
    hyphenation: MimirBookTitleHyphenation? = null,
): MimirBookTitleFit {
    val floorSp = minSp.coerceAtMost(maxSp)
    val sizes = ArrayList<Float>()
    var step = maxSp
    while (step > floorSp) {
        sizes.add(step)
        step -= 0.5f
    }
    sizes.add(floorSp)
    fun inRoom(layout: androidx.compose.ui.text.TextLayoutResult) = !layout.didOverflowHeight && layout.size.height <= availableHeightPx + 0.5f

    var layout = measure(sizes[0])
    for (index in sizes.indices) {
        if (index > 0) layout = measure(sizes[index])
        if (inRoom(layout) && mimirBookTitleFirstCutLine(layout) < 0) return MimirBookTitleFit(sizes[index], maxLines, fits = true)
    }
    // `layout` is now the floor's. Words too wide for a line may still be broken at a hyphen.
    if (hyphenation != null && !hasCharacterBreakingScript(layout.layoutInput.text.text)) {
        for (size in sizes) {
            val text = hyphenation.softHyphenated(size, false) ?: continue
            val hyphenated = hyphenation.measure(text, size, maxLines)
            if (inRoom(hyphenated) && mimirBookTitleCutLines(hyphenated).all { mimirBookTitleEndsAtSoftHyphen(hyphenated, it) }) {
                return MimirBookTitleFit(size, maxLines, fits = true, hyphenatedText = text)
            }
        }
        mimirBookTitleHyphenatedEllipsis(hyphenation, floorSp, maxLines, availableHeightPx)?.let { return it }
    }
    val cutLine = mimirBookTitleFirstCutLine(layout)
    // Even the floor does not fit: keep it, and keep the ellipsis, in as many lines as the room holds (at least one), and no further than the line a
    // word the width cannot hold begins on, so that line ends in the ellipsis and no word is split.
    val roomLines = (0 until layout.lineCount).count { layout.getLineBottom(it) <= availableHeightPx + 0.5f }
    val wholeWordLines = if (cutLine >= 0) cutLine + 1 else maxLines
    return MimirBookTitleFit(floorSp, minOf(roomLines, wholeWordLines).coerceIn(1, maxLines), fits = false)
}

/**
 * True when a line of [layout] (not the last) ends on a hyphen or a slash that is joined to the word before it ("TURBOGRAFX-" / "16"): the engine may break there, but a name read
 * across two lines that way is cut inside a word as far as the player is concerned. A slash set apart by a space ("PC ENGINE /") is a word of its own and is not one.
 */
internal fun mimirBookEyebrowSplitsCompound(layout: androidx.compose.ui.text.TextLayoutResult): Boolean {
    val text = layout.layoutInput.text.text
    for (line in 0 until layout.lineCount - 1) {
        val end = layout.getLineEnd(line, visibleEnd = true).coerceAtMost(text.length)
        val last = text.substring(layout.getLineStart(line), end).trimEnd()
        if (last.length > 1 && (last.last() == '-' || last.last() == '/' || last.last() == '\u2010' || last.last() == '\u2013') && !last[last.length - 2].isWhitespace()) return true
    }
    return false
}

/** A book's eyebrow set to its room: the size in sp, and the lines it stands in. [fits] is false only when even the floor in [maxLines] lines cannot hold every word whole: the floor, one line, ending in an ellipsis. */
internal data class MimirBookEyebrowFit(val sizeSp: Float, val lines: Int, val fits: Boolean)

/**
 * Sets a book's eyebrow (the platform's name over the tooled rule) to the width the book gives it. From [maxSp] down to [minSp] in half-sp steps, the first size at which the
 * name stands whole wins, in the fewest lines (one, then up to [maxLines]): on several lines the name wraps at its spaces, a slash or a hyphen with every word whole, nothing
 * ellipsised -- never a line that ends inside a word. If no size holds it the floor keeps one line that ends in an ellipsis. [measure] lays the eyebrow out at a size, capped at
 * a number of lines with an ellipsis, in the book's width. Pure, so a test drives it with any measure.
 */
internal fun mimirBookEyebrowFit(
    maxSp: Float,
    minSp: Float,
    measure: (sizeSp: Float, lines: Int) -> androidx.compose.ui.text.TextLayoutResult,
    maxLines: Int = 3,
): MimirBookEyebrowFit {
    fun ellipsized(layout: androidx.compose.ui.text.TextLayoutResult) = (0 until layout.lineCount).any(layout::isLineEllipsized)
    val floorSp = minSp.coerceAtMost(maxSp)
    var size = maxSp
    while (true) {
        val current = size.coerceAtLeast(floorSp)
        if (!ellipsized(measure(current, 1))) return MimirBookEyebrowFit(current, 1, fits = true)
        for (lines in 2..maxLines) {
            val layout = measure(current, lines)
            if (!ellipsized(layout) && !layout.didOverflowHeight && mimirBookTitleFirstCutLine(layout) < 0 && !mimirBookEyebrowSplitsCompound(layout)) {
                return MimirBookEyebrowFit(current, lines, fits = true)
            }
        }
        if (current <= floorSp) return MimirBookEyebrowFit(floorSp, 1, fits = false)
        size -= 0.5f
    }
}

/** True when [text] holds a character of a script written without spaces between words ([breaksBetweenCharacters]); such a title is never hyphenated. */
private fun hasCharacterBreakingScript(text: String): Boolean {
    var i = 0
    while (i < text.length) {
        val codePoint = text.codePointAt(i)
        if (breaksBetweenCharacters(codePoint)) return true
        i += Character.charCount(codePoint)
    }
    return false
}

/**
 * A coverless game as a bound Norse book. The leather volume is the theme's
 * [MimirHeroMaterial] — the same dark, readable book the companion folder tome uses — so the light title ink
 * stays legible on every identity, light or dark. A single uniform per-platform rule ([platformIdValue] → a
 * deterministic hue) tints the leather, so every coverless game on one platform shares a family while all
 * platforms follow the same logic; [platformLabel] adds a friendly eyebrow when the caller has one. Keeps a
 * 3:4 book aspect and centres inside whatever slot it is given (square Home tile … portrait gamelist seat …
 * detail page).
 *
 * [blankTitle] draws the book with no title at all, for a picture of "a game" that is no game in particular
 * (onboarding's action pages). An empty [title] otherwise reads "Untitled", which is true of a real game.
 *
 * [fitTitle] sets the title to the book instead of cutting it at the book's edge: from the book's own title size down to the type scale's floor
 * ([MimirTypeStep.Micro]), in at most three lines that break only between words, the last ending in an ellipsis when even the floor cannot hold it whole
 * (for the Bottom shelf's small books, where the book carries the game's only name at Dense). A single word too wide for a line at
 * every size is broken at a syllable with a hyphen instead ("Earth-" / "bound"), at the largest size that holds the title that way;
 * a title no size holds even hyphenated is cut to the room at the floor with its last line ending in the ellipsis ("Poké-" / "mon" / "Diamond...",
 *) ([mimirFitBookTitle]). Off, the title keeps its fixed size and five lines.
 */
@Composable
fun MediaArtBookCover(
    title: String,
    platformIdValue: String?,
    focused: Boolean = false,
    platformLabel: String? = null,
    modifier: Modifier = Modifier,
    palette: MimirThemePalette = LocalMimirPalette.current,
    blankTitle: Boolean = false,
    fitTitle: Boolean = false,
    /**
     * Whether the platform label over the rule is set to the book instead of cut at the book's edge ("SUPER NINTE..."): on one line when it stands whole, else on two or three,
     * wrapped at its spaces with every word whole ([mimirBookEyebrowLines]). The lines share the one line's height, so the title stays where it was. Additive, off by
     * default: every other book is byte-identical. Used by the Library list's preview, whose label is the platform's full display name.
     */
    fitEyebrow: Boolean = false,
    /**
     * Whether the book's embossed Mannaz is drawn. On: always, the book's own Mimir identity. Off only for a book that wears the favourite plate, which is a Mannaz of its own: one
     * ᛗ per book , so the emboss yields while the game is a favourite and returns when it is not, on the plate's own clock ([MimirMotion.FocusMs]).
     */
    showMannaz: Boolean = true,
    /**
     * The emboss's presence driven from outside, 0 to 1, read in the draw phase; given, it replaces the fade [showMannaz] runs. For a picture that already has a clock of its own: the
     * onboarding hint's MARK scene crossfades the emboss against its pin's arrival from the demo's progress, so exactly one ᛗ stands at every instant. Null: [showMannaz] decides.
     */
    mannazAlpha: (() -> Float)? = null,
    /**
     * How far above the book's bottom edge the embossed Mannaz's lowest tip stands at least, in dp; 0, the default, is the book's own proportions (the rune at 0.84 of the height), byte-identical.
     * A book that seats the platform shelfmark in its bottom-leading corner gives the plate's band ([MimirShelfmarkBandDp]): the rune is lifted clear above it, whatever the code's width, instead
     * of standing where a wide plate covers its left leg (combined33 sweep, the Home shelf at font 1.3). The fitted title's room is counted to the lifted rune.
     */
    mannazClearBottomDp: Float = 0f,
) {
    val cleaned = remember(title) { title.trim().ifEmpty { "Untitled" } }
    // The emboss's presence, on the favourite plate's own clock so the two cross over: read in the draw phase only. Born at its target and only animated when the target moves, so a book
    // that is never toggled runs no animation at all (animateTo from the target would still tick the full duration).
    val mannazPresence = remember { androidx.compose.animation.core.Animatable(if (showMannaz) 1f else 0f) }
    val mannazMs = if (mimirReducedMotion()) 0 else MimirMotion.FocusMs
    androidx.compose.runtime.LaunchedEffect(showMannaz, mannazMs) {
        val target = if (showMannaz) 1f else 0f
        if (mannazPresence.targetValue != target) {
            mannazPresence.animateTo(target, androidx.compose.animation.core.tween(mannazMs, easing = MimirMotion.Easing))
        }
    }
    val hero = palette.materials.hero
    val counter = palette.materials.counterAccent
    val runeInk = palette.runeMark
    // One uniform per-platform rule: the id seeds a deterministic hue that tints the leather. null → neutral.
    val tint = remember(platformIdValue) {
        if (platformIdValue.isNullOrBlank()) {
            null
        } else {
            val seed = (platformIdValue.lowercase().fold(7) { a, c -> a * 31 + c.code } and 0x7fffffff) % 360
            Color.hsv(seed.toFloat(), 0.5f, 0.5f)
        }
    }
    val leatherTop = if (tint != null) androidx.compose.ui.graphics.lerp(hero.coverTop, tint, 0.22f) else hero.coverTop
    val leatherMid = if (tint != null) androidx.compose.ui.graphics.lerp(hero.coverMiddle, tint, 0.15f) else hero.coverMiddle
    val leatherBottom = hero.coverBottom
    val leather = remember(leatherTop, leatherMid, leatherBottom) {
        Brush.verticalGradient(listOf(leatherTop, leatherMid, leatherBottom))
    }
    // Only a FRIENDLY label becomes an eyebrow; a raw platform id would read as noise. The per-platform
    // treatment the request specified for is carried by the leather tint above, which every platform gets.
    val eyebrow = remember(platformLabel) {
        platformLabel?.trim()?.takeIf { it.isNotBlank() }?.uppercase()
    }
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        BoxWithConstraints(
            modifier = Modifier
                .aspectRatio(3f / 4f, matchHeightConstraintsFirst = true)
                .fillMaxSize(),
        ) {
            val wDp = maxWidth.value.coerceAtLeast(1f)
            // R-4.1 . This used to inline the
            // same coefficient and the same clamp as [mimirNorseBookCoverCorner], character for character
            // -- the arithmetic is NOT repeated here on purpose, so the gate that forbids it can grep for
            // the code shape without its own prose matching. That duplicate is why the earlier fix
            // missed this component: the fix landed on [MimirNorseBookCover] and the Library paints THIS one.
            // Calling the function is the repair. A book's corner law is PROPORTIONAL, so it is deliberately
            // not [MimirBoxArtCorner]: a book is a claim ("this game has no art"), not box art (R-4, R-7).
            val bookCorner = mimirNorseBookCoverCorner(wDp)
            // Ported from [MimirNorseBookCover] (`MimirNorseBook.kt`) -- the
            // full reasoning is written at that site. The spine corners were a separate, much squarer 2-5dp
            // while the ring stayed uniform at the fore corner, so on the spine side a near-square cover
            // corner sat OUTSIDE the ring's curve. Matching the two by construction is what keeps the art
            // inside the ring at every width.
            //
            // The book still reads as a book: the spine shading and the fore-edge page stack below draw that
            // silhouette. Only the COVER's corner radius is uniform now.
            val bookShape = RoundedCornerShape(bookCorner)
            val titleSp = (wDp * 0.11f).coerceIn(12f, 28f)
            val eyebrowSp = (wDp * 0.05f).coerceIn(7f, 12f)
            // fitTitle: the title is set to the room the leather leaves it, instead of running out of the book. The room is the leaf's width inside
            // the column's padding below, and the height between the tooled rule and the embossed Mannaz; the same numbers the column and the
            // drawing use, so what is measured here is what the Text below gets.
            val measurer = androidx.compose.ui.text.rememberTextMeasurer()
            val titleDensity = androidx.compose.ui.platform.LocalDensity.current
            val titleBase = androidx.compose.material3.LocalTextStyle.current
            val hDp = maxHeight.value.coerceAtLeast(1f)
            val hyphenator = LocalMimirBookTitleHyphenator.current
            // A fitted title is measured against the room the column really leaves it, and the eyebrow's line box is most of what stands above it: the label inherits the
            // theme's line height (24 sp), not the 1.4 x its size the fit used to assume, so on a book with a platform label the title stood 14 dp lower than it was measured
            // to and its last line ran onto the embossed Mannaz (sweep 10-11, the Tome preview). The box is held at the theme's line height in dp, however large the type is
            // set, as [MimirNorseBookCover] holds its own (at 1.3x it was 31 dp and took the title's second line), and the fit is given that same number. An unfitted book
            // keeps the label's own line height, byte-identical.
            val eyebrowBlockDp: Float? = if (fitTitle && !blankTitle && eyebrow != null && titleBase.lineHeight.isSp) titleBase.lineHeight.value else null
            // The floor is the type scale's Micro as DRAWN at 1x (9 dp), not 9 sp times the font scale, as [MimirNorseBookCover]'s fit has it: the room is the same dp at any font
            // scale, so at 1.3x a floor of 11.7 dp left a 49 dp line too narrow for "Trigger" and a small book read "Chron..." (observed in the Tome preview).
            val floorSp = MimirTypeStep.Micro.value / titleDensity.fontScale.coerceAtLeast(1f)
            val titleFit = if (fitTitle && !blankTitle) {
                remember(cleaned, wDp, hDp, eyebrow != null, eyebrowBlockDp, titleSp, floorSp, titleDensity, titleBase, hyphenator, mannazClearBottomDp) {
                    val widthPx = with(titleDensity) { (wDp * 0.765f).dp.toPx() }.toInt().coerceAtLeast(1)
                    val topDp = wDp * 0.13f + 1.5f + wDp * 0.028f + (if (eyebrow != null) (eyebrowBlockDp ?: (eyebrowSp * 1.4f)) + wDp * 0.028f else 0f)
                    val runeTopDp = mimirBookMannazTopDp(wDp, hDp, mannazClearBottomDp) - 2f
                    val availablePx = with(titleDensity) { (runeTopDp - topDp).coerceAtLeast(1f).dp.toPx() }
                    // Settling a title that no size holds whole takes dozens of layouts; a shelf paged back to, or a second book of the same name, finds it decided.
                    val key = MimirBookTitleFitKey(
                        title = cleaned,
                        widthPx = widthPx,
                        availableHeightPx = availablePx,
                        maxSp = titleSp,
                        minSp = floorSp,
                        maxLines = 3,
                        density = titleDensity.density,
                        fontScale = titleDensity.fontScale,
                        style = titleBase,
                        locale = java.util.Locale.getDefault().toLanguageTag(),
                        hyphenator = hyphenator,
                    )
                    MimirBookTitleFits.getOrCompute(key) {
                        mimirFitBookTitle(
                            maxSp = titleSp,
                            minSp = floorSp,
                            maxLines = 3,
                            availableHeightPx = availablePx,
                            measure = mimirBookTitleMeasure(measurer, cleaned, titleBase, widthPx, maxLines = 3),
                            hyphenation = hyphenator(measurer, cleaned, titleBase, widthPx),
                        )
                    }
                }
            } else {
                null
            }
            val shownTitleSp = titleFit?.sizeSp ?: titleSp
            val eyebrowFit = if (fitEyebrow && eyebrow != null) {
                remember(eyebrow, wDp, eyebrowSp, titleDensity, titleBase) {
                    val widthPx = with(titleDensity) { (wDp * 0.765f).dp.toPx() }.toInt().coerceAtLeast(1)
                    mimirBookEyebrowFit(
                        maxSp = eyebrowSp,
                        minSp = MimirBookEyebrowFloorSp,
                        measure = { sizeSp, lines ->
                            measurer.measure(
                                text = androidx.compose.ui.text.AnnotatedString(eyebrow),
                                style = titleBase.merge(
                                    androidx.compose.ui.text.TextStyle(
                                        fontSize = sizeSp.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        letterSpacing = 1.1.sp,
                                    ),
                                ),
                                overflow = TextOverflow.Ellipsis,
                                maxLines = lines,
                                constraints = androidx.compose.ui.unit.Constraints(maxWidth = widthPx),
                            )
                        },
                    )
                }
            } else {
                null
            }
            val eyebrowLines = eyebrowFit?.lines ?: 1
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(bookShape)
                    .background(leather)
                    .border(0.8.dp, hero.hardware.copy(alpha = 0.34f), bookShape)
                    .drawBehind {
                        val spine = (size.width * 0.075f).coerceAtLeast(3.dp.toPx())
                        val foreEdge = (size.width * 0.055f).coerceAtLeast(2.dp.toPx())
                        // Page block on the fore-edge (right).
                        val pageInk = androidx.compose.ui.graphics.lerp(hero.onCover, leatherMid, 0.34f)
                        drawRect(
                            color = pageInk.copy(alpha = 0.78f),
                            topLeft = Offset(size.width - foreEdge, 3.dp.toPx()),
                            size = Size(foreEdge, size.height - 6.dp.toPx()),
                        )
                        repeat(3) { i ->
                            val x = size.width - foreEdge + foreEdge * (i + 1f) / 4f
                            drawLine(
                                counter.copy(alpha = 0.16f),
                                Offset(x, 4.dp.toPx()), Offset(x, size.height - 4.dp.toPx()), 0.5.dp.toPx(),
                            )
                        }
                        // Spine (left) with two raised bands.
                        drawRect(color = hero.hardware.copy(alpha = 0.30f), size = Size(spine, size.height))
                        drawLine(
                            Color.Black.copy(alpha = 0.34f),
                            Offset(spine + 1.dp.toPx(), 0f), Offset(spine + 1.dp.toPx(), size.height), 1.dp.toPx(),
                        )
                        listOf(0.18f, 0.82f).forEach { yF ->
                            val ly = size.height * yF
                            drawLine(hero.hardware.copy(alpha = 0.9f), Offset(0f, ly), Offset(spine, ly), 1.3.dp.toPx())
                            drawLine(
                                androidx.compose.ui.graphics.lerp(hero.hardware, Color.White, 0.6f),
                                Offset(0f, ly - 0.5.dp.toPx()), Offset(spine, ly - 0.5.dp.toPx()), 0.5.dp.toPx(),
                            )
                        }
                        // Embossed Mannaz low on the leaf: the book's invariant Mimir identity, with ONE exception: it yields to the favourite plate, which is a Mannaz of its own and
                        // stands on the same book (one ᛗ per book), so [showMannaz] is off while the game is a favourite.
                        val mannaz = mannazAlpha?.invoke() ?: mannazPresence.value
                        if (mannaz <= 0f) return@drawBehind
                        val leafRight = size.width - foreEdge
                        val leafW = leafRight - spine
                        val mw = leafW * 0.18f
                        val mh = mw * 1.06f
                        val cx = spine + leafW / 2f
                        val clearPx = mannazClearBottomDp.dp.toPx()
                        val cy = if (clearPx > 0f && size.height * 0.84f + mh / 2f > size.height - clearPx) size.height - clearPx - mh / 2f else size.height * 0.84f
                        val lx = cx - mw / 2f; val rx = cx + mw / 2f
                        val ty = cy - mh / 2f; val by = cy + mh / 2f
                        val sw = (leafW * 0.026f).coerceAtLeast(1.dp.toPx())
                        val emboss = runeInk.copy(alpha = (if (focused) 0.95f else 0.6f) * mannaz)
                        drawLine(emboss, Offset(lx, ty), Offset(lx, by), sw, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                        drawLine(emboss, Offset(rx, ty), Offset(rx, by), sw, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                        drawLine(emboss, Offset(lx, ty), Offset(rx, ty + mh * 0.30f), sw, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                        drawLine(emboss, Offset(rx, ty), Offset(lx, ty + mh * 0.30f), sw, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    },
                contentAlignment = Alignment.TopStart,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = (wDp * 0.135f).dp,
                            end = (wDp * 0.10f).dp,
                            top = (wDp * 0.13f).dp,
                        ),
                    verticalArrangement = Arrangement.spacedBy((wDp * 0.028f).dp),
                ) {
                    if (eyebrow != null) {
                        Text(
                            eyebrow,
                            fontSize = (eyebrowFit?.sizeSp ?: eyebrowSp).sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.1.sp,
                            // Two lines share the one line's height (the theme's line height is 24 sp), so the rule and the title stand where they do under one.
                            lineHeight = if (eyebrowBlockDp != null) {
                                with(titleDensity) { (eyebrowBlockDp / eyebrowLines).dp.toSp() }
                            } else if (eyebrowLines > 1 && titleBase.lineHeight.isSp) {
                                (titleBase.lineHeight.value / eyebrowLines).sp
                            } else {
                                androidx.compose.ui.unit.TextUnit.Unspecified
                            },
                            color = runeInk.copy(alpha = 0.85f),
                            maxLines = eyebrowLines,
                            overflow = TextOverflow.Ellipsis,
                            // One line is held to the box exactly. Several lines share it by their line height and are left to stand in it: a height that a rounded line
                            // overshoots by a pixel would drop the last line into an ellipsis.
                            modifier = if (eyebrowBlockDp == null) {
                                Modifier
                            } else if (eyebrowLines == 1) {
                                Modifier.height(eyebrowBlockDp.dp)
                            } else {
                                Modifier.heightIn(min = eyebrowBlockDp.dp)
                            },
                        )
                    }
                    // A tooled accent rule under the eyebrow, as on a bound book cover.
                    Box(
                        Modifier
                            .width((wDp * 0.26f).dp)
                            .height(1.5.dp)
                            .background(runeInk.copy(alpha = 0.5f)),
                    )
                    if (!blankTitle) {
                        Text(
                            titleFit?.hyphenatedText ?: cleaned,
                            // The soft hyphens are for the line breaker; the title said to a screen reader, or matched by text, is the clean one. This modifier is
                            // outside the text's own semantics, so its text wins.
                            modifier = Modifier.testTag(MediaArtBookTitleTag).then(
                                if (titleFit?.hyphenatedText != null) Modifier.semantics { text = androidx.compose.ui.text.AnnotatedString(cleaned) } else Modifier,
                            ),
                            style = if (titleFit?.hyphenatedText != null) titleBase.merge(MimirBookTitleHyphenStyle) else titleBase,
                            fontSize = shownTitleSp.sp,
                            lineHeight = (shownTitleSp * 1.08f).sp,
                            fontWeight = FontWeight.Bold,
                            color = hero.onCover,
                            maxLines = titleFit?.maxLines ?: 5,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Paints a LOCAL box-art image into [MediaArtFrame]'s art slot via Coil — THE one cover painter every
 * surface shares (lifted from feature-library so the companion renders the SAME art the primary
 * does, through the same loader). Runtime-local-only: the app installs a network-free ImageLoader, and
 * [model] is always a `content://`/`file://` URI the offline resolver confirmed exists — nothing ever
 * touches the network. A cold bitmap fades in over whatever is behind it (no plate is drawn for wired art,
 * [mimirArtPlateVisible]); if it fails, the same
 * honest glyph the frame draws for no-art renders instead (never a broken image or prior identity).
 */
@Composable
fun MimirBoxArt(
    model: Any,
    title: String,
    focused: Boolean,
    // Gamelist frames CROP to fill the physical shell; flat covers off the gamelist FIT so the whole cover
    // shows without clipping .
    contentScale: ContentScale = ContentScale.Crop,
    /**
     * Policy 2026-08-28. When set, the art is drawn so its INK — the opaque rectangle inside the
     * file — fits the seat, instead of its canvas. See [MimirArtInkBounds]: two Box (3D) renders on a
     * reference device filled 31 % and ~100 % of identical seats, because the seats were shaped by the artboard.
     *
     * Opt-in per call site rather than read from the store here, so a surface adopts it only once its
     * seat is shaped for the ink. [mimirArtInkDraw] still centres and never overflows if it is not.
     */
    ink: MimirArtInkBounds? = null,
    /**
     * Walk-32i. True keeps the art out of sight, exactly as if the bitmap had not arrived: it is composed and loading, and its
     * reveal (a fade for a cold bitmap, a snap for a warm one) starts the moment this turns false. A surface that solves its
     * seat from the ink holds the art here until [MimirArtInkStore.hasVerdict], so the art is first seen at the seat it keeps.
     */
    held: Boolean = false,
) {
    val identity = mimirArtPresentationIdentity(model, MimirArtPresentationKind.BOX_ART)
    key(identity) {
        // R8a: a model whose local file is gone is unwired and takes the no-art glyph. A file that exists and fails to decode is
        // wired and draws nothing (R8), so the two never share a painter. Inside a frame that already resolved this model (MediaArtFrame,
        // the flat cover) the verdict is inherited, not stat'ed a second time.
        if (rememberMimirArtWiredOrInherited(model)) {
            MimirBoxArtIdentity(model, title, focused, contentScale, ink, held)
        } else {
            MimirBoxArtUnwired(title, focused)
        }
    }
}

@Composable
private fun MimirBoxArtUnwired(title: String, focused: Boolean) {
    Box(Modifier.fillMaxSize()) {
        MediaArtFallbackGlyph(title = title, focused = focused, showTitle = false, fitTitle = true)
    }
}

@Composable
private fun MimirBoxArtIdentity(
    model: Any,
    title: String,
    focused: Boolean,
    contentScale: ContentScale,
    ink: MimirArtInkBounds?,
    held: Boolean,
) {
    val context = LocalContext.current
    val painter = rememberMimirLocalArtPainter(model, MimirArtPresentationKind.BOX_ART)
    val artReady = when (val state = painter.state) {
        is AsyncImagePainter.State.Success -> true
        is AsyncImagePainter.State.Loading -> state.painter != null
        else -> false
    }
    // a FRESH painter reports not-ready on frame 0 even when the bitmap is memory-
    // cached, so a warm revisit faded from the fallback instead of landing ON the art. Probe the cache
    // SYNCHRONOUSLY (same key the request uses) so warm art snaps — only genuinely cold art fades.
    val warmHit = remember(model) { mimirArtWarmInMemory(context, model) }
    val warmAtBirth = remember { artReady || warmHit }
    // Held as State and read in the layer below, never here: the reveal redraws every frame and recomposes nothing (no `Modifier.alpha`).
    val artAlpha = animateFloatAsState(
        targetValue = if (artReady && !held) 1f else 0f,
        animationSpec = if (warmAtBirth) snap() else {
            MimirColdArtRevealSpec
        },
        label = "boxArtReveal",
    )
    Box(Modifier.fillMaxSize()) {
        if (ink != null) {
            // The seat is the LAYOUT; only the pixels move. See [mimirArtInkDraw] for why this is a
            // graphicsLayer and not an oversized child -- two device rounds were lost to the latter.
            Image(
                painter = painter,
                contentDescription = title,
                // FillBounds is half the transform: the layer below undoes this stretch and re-applies
                // the one the ink needs, in one scale.
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .graphicsLayer {
                        val solve = mimirArtInkDraw(
                            seatWidth = size.width,
                            seatHeight = size.height,
                            ink = ink,
                        )
                        transformOrigin = TransformOrigin(
                            MimirArtInkDraw.transformOriginX,
                            MimirArtInkDraw.transformOriginY,
                        )
                        scaleX = solve.scaleX
                        scaleY = solve.scaleY
                        translationX = solve.translationX
                        translationY = solve.translationY
                        alpha = artAlpha.value
                    },
            )
        } else {
            Image(
                painter = painter,
                contentDescription = title,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = artAlpha.value },
            )
        }
        // R8 : art here is wired, so a decode FAILURE draws nothing, exactly like Empty/Loading: no costume and
        // no plate. The glyph is for no art wired (a missing file, [MimirBoxArtUnwired]).
        MimirArtFailureLog(failed = painter.state is AsyncImagePainter.State.Error, model = model)
    }
}

/**
 * Shared request policy for confirmed local art: stable cache keys, no decode crossfade, and a painter
 * instance owned by the destination identity. The explicit [key] is important: updating the model on an
 * existing AsyncImagePainter can otherwise leave its prior Success painter visible for one commit.
 */
@Composable
internal fun rememberMimirLocalArtPainter(
    model: Any,
    kind: MimirArtPresentationKind,
): AsyncImagePainter {
    val identity = mimirArtPresentationIdentity(model = model, kind = kind)
    return key(identity) {
        val context = LocalContext.current
        val request = remember(model, context) {
            // PERF-B: the ONE cover identity (shared cap + shared key) — a warm revisit paints the
            // cached bitmap on frame 0 no matter which surface warmed it, and nothing decodes at source size.
            mimirCoverRequest(context, model)
        }
        rememberAsyncImagePainter(model = request)
    }
}

/**
 * Natural cover aspect from a decoded painter's intrinsic size (FUX-01 §3.4). Null while the size is
 * unknown or degenerate — the caller reserves neutral bounds and only ever FADES art in, never reflows.
 * The clamp exists so one corrupt or absurd source can't shatter the shelf's rhythm; real covers
 * (~0.7 portrait … ~1.45 landscape N64/genesis boxes) pass through untouched.
 */
fun mimirLedgeCoverAspect(
    intrinsicWidth: Float,
    intrinsicHeight: Float,
    minAspect: Float = MimirLedgeCoverMinAspect,
    maxAspect: Float = MimirLedgeCoverMaxAspect,
): Float? {
    if (!intrinsicWidth.isFinite() || !intrinsicHeight.isFinite()) return null
    if (intrinsicWidth <= 0f || intrinsicHeight <= 0f) return null
    return (intrinsicWidth / intrinsicHeight).coerceIn(minAspect, maxAspect)
}

const val MimirLedgeCoverMinAspect = 0.62f
const val MimirLedgeCoverMaxAspect = 1.45f
private val MimirLedgeMorphSpec = tween<Float>(MimirMotion.FocusMs, easing = MimirMotion.Easing)
private val MimirLedgeCaretHeight = 2.dp
private val MimirLedgeCaretGap = 4.dp
private val MimirLedgeCaretInset = 8.dp
/** Stable tile envelope; natural cover geometry is fitted inside this reserved square seat. */
private const val MimirLedgeEnvelopeAspect = 1f
/** Neutral inner silhouette while decoding / for the honest glyph. */
private const val MimirLedgeNeutralAspect = 3f / 4f

/**
 * FUX-01 §3.4 Cover-Art Law (Home Shelf ONLY): the FRAMELESS ledge cover. No card, no box, no border,
 * no standardized silhouette — the artwork itself, at its natural aspect, standing on the bottom edge of
 * whatever envelope the caller provides (a shelf reads as objects on a ledge). Fit is height-first;
 * width follows the source's own proportions (clamped by [mimirLedgeCoverAspect]) inside the envelope,
 * so portrait covers fill the height and landscape boxes sit lower and wider — intentional, never broken.
 * Art only ever FADES IN once decoded ([MimirColdArtRevealSpec]); the envelope never reflows.
 *
 * Focus grammar (ratified B2, palette restyle 2026-07-12): the focused cover carries the DRAWN-BEAM
 * CARET — a 2dp molten underline (accent → accentBright → accent) just below the ledge, width tracking
 * the art. [caretProgress] 0..1 animates it; the caller drives it from its focus animation.
 *
 * The no-art / decode-failure fallback is the same honest glyph every surface uses, in INVISIBLE 3:4
 * bounds — no fake cover, no panel, and the ledge rhythm holds.
 */
@Composable
fun MimirLedgeCoverArt(
    model: Any?,
    title: String,
    focused: Boolean,
    caretProgress: Float,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = false,
    palette: MimirThemePalette = LocalMimirPalette.current,
    // FUX-04: Switch-style selection ring around the painted art box (draw-phase read; 0 = off).
    focusRingProgress: () -> Float = { 0f },
    // the platform SHELFMARK, seated on the ART box's bottom-leading corner (never the
    // envelope's — a portrait cover letterboxes inside a square seat, and a plate pinned to the envelope
    // would float off the artwork). Null on every surface that does not ask for it, which is all of them
    // except the Home shelf: a gamelist is one platform by construction and its covers already carry the
    // platform's own nomenclature, so a plate there is redundant.
    shelfmark: (@Composable () -> Unit)? = null,
    // Marks that sit ON the art (the Library's favourite rune and disc badge): seated in the ART box, in its clip, so they ride its
    // corners at whatever shape the cover is -- never the envelope's, which a portrait cover letterboxes inside.
    overlay: (@Composable BoxScope.() -> Unit)? = null,
    // The same marks for when the art box holds the runed BOOK (a game with no cover): they are seated here instead of in [overlay]. The book sets its title at the top
    // of the leather, which is where a cover's marks ride, so a book's marks need a seat of their own (sweep 10-11: the favourite plate covered "Chrono" and "Earthbound").
    // Null falls back to [overlay]. Chosen by the painter, which alone knows whether the source resolved to art or to no art (R8a folds a missing file into the book).
    bookOverlay: (@Composable BoxScope.() -> Unit)? = null,
    // Whether the book (the no-art face) draws its embossed Mannaz: the caller turns it off for a favourite, whose plate is the one ᛗ on the book . Nothing to a cover.
    showMannaz: Boolean = true,
    // Put on the art box before its ring and clip, so a modifier that draws the box into a layer (the hide's capture) takes the
    // art, its marks and its corners and nothing of the empty seat around a letterboxed cover.
    artModifier: Modifier = Modifier,
) {
    MimirLedgeCoverArtIdentity(
        // R8a: a source whose file is gone is no source, and takes the no-art fallback below.
        model = rememberMimirArtModel(model),
        title = title,
        focused = focused,
        caretProgress = caretProgress,
        modifier = modifier,
        reducedMotion = reducedMotion,
        palette = palette,
        focusRingProgress = focusRingProgress,
        shelfmark = shelfmark,
        overlay = overlay,
        bookOverlay = bookOverlay,
        showMannaz = showMannaz,
        artModifier = artModifier,
    )
}

@Composable
private fun MimirLedgeCoverArtIdentity(
    model: Any?,
    title: String,
    focused: Boolean,
    caretProgress: Float,
    modifier: Modifier,
    reducedMotion: Boolean,
    palette: MimirThemePalette,
    focusRingProgress: () -> Float,
    shelfmark: (@Composable () -> Unit)?,
    overlay: (@Composable BoxScope.() -> Unit)?,
    bookOverlay: (@Composable BoxScope.() -> Unit)?,
    showMannaz: Boolean,
    artModifier: Modifier,
) {
    // The caret brush is size-relative with default coordinates — one instance per palette, never per draw.
    val caretBrush = remember(palette) {
        Brush.horizontalGradient(listOf(palette.accent, palette.accentBright, palette.accent))
    }
    // paintedWidthFraction tracks the ART's true width inside a clamped box (Fit letterboxes a degenerate
    // aspect horizontally) so the caret underlines the cover, not the letterbox.
    fun caretModifier(paintedWidthFraction: Float) = Modifier.drawBehind {
        val progress = caretProgress.coerceIn(0f, 1f)
        if (progress <= 0f) return@drawBehind
        val paintedWidth = size.width * paintedWidthFraction.coerceIn(0f, 1f)
        val caretWidth = paintedWidth - MimirLedgeCaretInset.toPx()
        if (caretWidth <= 0f) return@drawBehind
        drawRect(
            brush = caretBrush,
            topLeft = Offset(
                x = (size.width - caretWidth) / 2f,
                y = size.height + MimirLedgeCaretGap.toPx(),
            ),
            size = Size(caretWidth, MimirLedgeCaretHeight.toPx()),
            alpha = progress,
        )
    }

    // A model change creates a new painter in the same commit, so the previous identity's bitmap cannot
    // survive into the destination. Keep this key narrower than the outer aspect animator: no-art → a
    // warm URI must still MORPH from neutral geometry instead of initializing at the decoded aspect.
    val painter = if (model == null) {
        null
    } else {
        key(model) { rememberMimirLedgeArtPainter(model) }
    }
    val painterFailed = painter?.state is AsyncImagePainter.State.Error
    MimirArtFailureLog(failed = painterFailed, model = model)
    // Aspect comes from the painter's intrinsic bounds whenever they are KNOWN — including a warm-cache
    // Loading state, where the placeholderMemoryCacheKey bitmap already carries the true size. Never gate
    // on Success: the request resolves at ORIGINAL size (see the painter), so no draw pass is needed first.
    // FUX-G P0: a COLD Loading painter reports Size.Unspecified, whose width/height accessors THROW —
    // every intrinsic read happens inside the seam below, behind its isSpecified gate. Unspecified
    // resolves to unknown aspects → the neutral-bounds + fade-in branch (FUX-B's cold reveal).
    val aspects = if (painter != null && !painterFailed) {
        mimirLedgeIntrinsicAspects(painter.intrinsicSize)
    } else {
        MimirLedgeIntrinsicAspects(raw = null, clamped = null)
    }
    val rawAspect = aspects.raw
    val aspect = aspects.clamped
    // Warm revisits paint on frame 0 with NO re-fade (the file's warm-paint policy): if the cover is
    // already renderable when this composition is born, the reveal snaps.
    val revealAlpha = key(model) {
        // the earlier update : the fresh painter's intrinsic size is Unspecified on frame 0 even for a
        // memory-cached bitmap — probe the cache synchronously (the shared PERF-B cover key) so a
        // warm revisit snaps to the art instead of fading from the book.
        val context = LocalContext.current
        val warmHit = remember(model) { mimirArtWarmInMemory(context, model) }
        val warmAtBirth = remember { aspect != null || warmHit }
        animateFloatAsState(
            targetValue = if (aspect != null) 1f else 0f,
            animationSpec = if (warmAtBirth || reducedMotion) {
                snap()
            } else {
                MimirColdArtRevealSpec
            },
            label = "ledgeCoverReveal",
        ).value
    }
    // The shape this cover showed last time. Every Home mount makes fresh painters, whose size is
    // unknown for a frame or two even when the bitmap is warm, so each cover was born at the neutral 3:4 and morphed
    // to its own shape over 140 ms on every return. Only a first composition (or a new model's target) uses it:
    // a model change still morphs from the current geometry.
    val seededAspect = remember(model) { MimirLedgeAspectMemory.seedFor(model) }
    if (aspect != null && model != null) SideEffect { MimirLedgeAspectMemory.record(model, aspect) }
    // The OUTER seat never changes size. Only this bottom-centred inner silhouette morphs, so a cold
    // 3:4 fallback cannot force the shelf body or fixed focus anchor to remeasure when a landscape or
    // portrait intrinsic arrives.
    val presentedAspect by animateFloatAsState(
        targetValue = aspect ?: seededAspect ?: MimirLedgeNeutralAspect,
        // On first composition animateFloatAsState starts at its target automatically. Keeping this state
        // OUTSIDE the identity key lets later warm/cold/no-art destinations morph from current geometry.
        animationSpec = if (reducedMotion) {
            snap()
        } else {
            MimirLedgeMorphSpec
        },
        label = "ledgeCoverAspect",
    )
    val paintedWidthFraction = if (aspect != null && rawAspect != null && aspect > 0f) {
        (rawAspect / aspect).coerceAtMost(1f)
    } else {
        1f
    }
    Box(
        modifier = modifier
            .aspectRatio(MimirLedgeEnvelopeAspect, matchHeightConstraintsFirst = true),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            modifier = Modifier
                .aspectRatio(presentedAspect, matchHeightConstraintsFirst = true)
                .fillMaxSize()
                .then(artModifier)
                // No plate, ever, behind a wired cover (see [mimirArtPlateVisible]): the cold
                // 3:4 seat used to wear the surface plate until the reveal landed, a square on the shelf for the whole
                // decode and then behind transparent art mid-fade. The cover now fades in over the shelf itself.
                .mimirFocusTileRing(focusRingProgress, palette.tileFocusRing, cornerRadius = MimirLedgeCoverCorner)
                // The cover must carry the SAME curve the ring is drawn with, or its corner stays a
                // hard 90 degrees inside the ring's arc and the two read as separate objects -- the
                // reported overlap, confirmed by pixel inspection of a GameCube cover's top-left.
                // mimirFocusTileRing draws behind and outside these bounds, so clipping after it
                // rounds the art without touching the bloom. Same order MimirSwitchTileArt uses.
                .clip(RoundedCornerShape(MimirLedgeCoverCorner))
                .then(caretModifier(paintedWidthFraction)),
            // A width-limited Fit letterbox must still SEAT the art on the ledge, never float it mid-box.
            contentAlignment = Alignment.BottomCenter,
        ) {
            // A known cold source stays neutral while it hydrates, and stays neutral if it fails (R8, 2026-10-02): the glyph is honest
            // only for a true absence. [MimirLedgeCoverArt] already folded a missing file into null (R8a).
            if (mimirArtFallbackVisible(hasArtSource = model != null)) {
                MediaArtFallbackGlyph(
                    title = title,
                    focused = focused,
                    palette = palette,
                    showTitle = false,
                    fitTitle = true,
                    showMannaz = showMannaz,
                    // The shelfmark's plate stands in the bottom-leading corner: the rune is lifted clear above its band, so no code covers it at any font or width.
                    mannazClearBottomDp = if (shelfmark != null) MimirShelfmarkBandDp else 0f,
                )
            }
            if (aspect != null && painter != null) {
                Image(
                    painter = painter,
                    // The tile's merged semantics already carry the title; a second label would double it.
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.BottomCenter,
                    alpha = revealAlpha,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // Last, so the plate reads ON the cover rather than under it. Single-call arm: the seat is its own composable, never an inline block.
            if (shelfmark != null) {
                MimirShelfmarkSeat(shelfmark)
            }
            // Above the plate, in the art box's own scope; a null slot is one safe call, not a composable if-without-else. The book has its own seat for the marks
            // when the caller gave one: the book is what stands in the box exactly when the fallback is visible.
            val marks = if (bookOverlay != null && mimirArtFallbackVisible(hasArtSource = model != null)) bookOverlay else overlay
            marks?.invoke(this)
        }
    }
}

/**
 * The shelfmark's corner seat — bottom-LEADING of the art box, inset [MimirShelfmarkSeatInset].
 *
 * Bottom-leading is the spine position on a bound volume and the corner least likely to hold a cover's
 * focal content: titles sit top, publisher marks sit centre. Its own composable so every `if (shelfmark
 * != null)` arm upstream stays a single call.
 */
@Composable
private fun BoxScope.MimirShelfmarkSeat(shelfmark: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(MimirShelfmarkSeatInset),
    ) {
        shelfmark()
    }
}

/** The shelfmark seat's inset from the art box's bottom and leading edges, in dp. */
internal const val MimirShelfmarkSeatInsetDp = 6f
private val MimirShelfmarkSeatInset = MimirShelfmarkSeatInsetDp.dp

/**
 * The band the shelfmark plate stands in at the bottom of a book, in dp up from its bottom edge: the seat's inset, the tallest plate ([MimirShelfmarkTallestPlateDp]) and 2 dp of air. The
 * plate's height is fixed, so the band is too: a book that seats a plate holds its embossed Mannaz above it ([MediaArtBookCover]'s `mannazClearBottomDp`).
 */
internal const val MimirShelfmarkBandDp = MimirShelfmarkSeatInsetDp + MimirShelfmarkTallestPlateDp + 2f

/**
 * Ring-only counterpart to [MimirLedgeCoverArt]. Home keeps this shell at the fixed hero anchor while
 * the one identity-owned art body glides underneath it. The shell follows the same natural/neutral
 * aspect policy but never draws a second bitmap or fallback glyph.
 */
@Composable
fun MimirLedgeFocusRingShell(
    model: Any?,
    progress: () -> Float,
    modifier: Modifier = Modifier,
    palette: MimirThemePalette = LocalMimirPalette.current,
    square: Boolean = false,
    reducedMotion: Boolean = false,
    glint: () -> Float = MimirFocusRingNoGlint,
) {
    // Restart only the painter group on identity changes. The ring itself deliberately keeps its current
    // geometry and morphs to the destination at the fixed hero anchor; it never owns an art bitmap.
    val decodedAspect = if (square || model == null) {
        null
    } else {
        key(model) {
            val painter = rememberMimirLedgeArtPainter(model)
            if (painter.state is AsyncImagePainter.State.Error) {
                null
            } else {
                mimirLedgeIntrinsicAspects(painter.intrinsicSize).clamped
            }
        }
    }
    // The same memory as the cover's, so the ring is born at the cover's shape with it.
    val seededAspect = remember(model) { MimirLedgeAspectMemory.seedFor(model) }
    val presentedAspect by animateFloatAsState(
        targetValue = if (square) {
            MimirLedgeEnvelopeAspect
        } else {
            decodedAspect ?: seededAspect ?: MimirLedgeNeutralAspect
        },
        animationSpec = if (reducedMotion) snap() else {
            MimirLedgeMorphSpec
        },
        label = "ledgeFocusRingAspect",
    )
    val cornerRadius by animateFloatAsState(
        // bare `Float`s in a corner position are still literals -- and the one shape a "no `dp`
        // literal" grep cannot see. Same values, now checkable. `.value` because `animateFloatAsState`
        // interpolates a Float and `.dp` is re-applied at the ring below.
        targetValue = if (square) MimirSwitchTileCorner.value else MimirBoxArtCorner.value,
        animationSpec = if (reducedMotion) snap() else {
            MimirLedgeMorphSpec
        },
        label = "ledgeFocusRingRadius",
    )
    Box(
        modifier = modifier
            .aspectRatio(MimirLedgeEnvelopeAspect, matchHeightConstraintsFirst = true),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            modifier = Modifier
                .aspectRatio(presentedAspect, matchHeightConstraintsFirst = true)
                .fillMaxSize()
                .mimirFocusTileRing(progress, palette.tileFocusRing, cornerRadius = cornerRadius.dp, glint = glint),
        )
    }
}

/**
 * Ledge covers resolve at the FIXED [MimirCoverDecodeCapPx] cap: the natural-aspect envelope is
 * solved from the painter's intrinsic bounds BEFORE anything draws, and a DRAW-sized request would
 * deadlock — the Image is not composed until the aspect is known, so the request would never receive a
 * size. An explicit fixed size resolves off-draw exactly like the old `Size.ORIGINAL` did (the hazard
 * was draw-derived sizing), while a fit-within cap preserves the intrinsic RATIO the envelope needs.
 * Confirmed-local covers only; the cache identity is the ONE shared [mimirCoverCacheKey] — the same
 * bitmap serves [rememberMimirLocalArtPainter] surfaces, so a cover is decoded once app-wide.
 */
/**
 * FUX-04: the Switch-style selection — a bright rounded ring hugging the tile with a
 * soft outer bloom (three widening strokes, no blur, no shadowElevation). Draws OUTSIDE the bounds, so
 * the parent must not clip. Progress is read in the DRAW phase — animating focus never recomposes.
 */
/**
 * The ledge cover's corner, shared by its clip and the focus ring drawn around it so the two can
 * never disagree. One value, because a ring and the art inside it are one object to the eye.
 *
 * Every box-art clip and focus ring shares this corner token. Numeric literals let two ring fixes
 *  pass `cornerRadius` through without changing its curve; an 8dp
 * literal also survived the reversal of its original rationale. Duplicated
 * book components let a 2026-08-09 corner update reach one component while Library used the other.
 *
 * So the rule this token exists to enforce: **a `cornerRadius =` argument to [mimirFocusTileRing], or a
 * `RoundedCornerShape` clipping box art, may not be a numeric literal.** Ring and art take ONE symbol, at
 * every call site, always. That is not style policing — it is the only rule under which those three
 * failures cannot recur.
 */
val MimirBoxArtCorner = 10.dp

/**
 * The Home shelf's ledge cover corner, which IS [MimirBoxArtCorner] and is defined as it rather than as a
 * second 10.
 *
 * Kept as a name so the Home shelf's own call sites read in its vocabulary, and so this promotion is
 * provably byte-identical there — nobody has to re-verify a surface that did not change.
 */
val MimirLedgeCoverCorner = MimirBoxArtCorner

/**
 * The app SWITCH TILE's corner -- a square app icon tile, not box art, and deliberately its own symbol at
 * its own value rather than a second name for [MimirBoxArtCorner].
 *
 * It exists because R-1's rule is about LITERALS, not about one shared number: the switch tile carried a
 * bare `16.dp` at its clip and its ring, and [MimirLedgeFocusRingShell] carried the pair as bare `Float`s
 * -- 16f and 10f, feeding `animateFloatAsState`. Bare floats are the one shape a
 * "no `dp` literal" grep cannot see, so it needs a separate assertion. Naming the value is what makes both sites checkable.
 *
 * Promoting it did not move a pixel: 16f and 10f were already this and [MimirBoxArtCorner].
 */
val MimirSwitchTileCorner = 16.dp

/**
 * How far the ring's top stop is lifted toward white, and its bottom stop dropped toward black.
 *
 *  Measured on the
 * Thor before this changed: the ring rendered rgb(202,178,123) at its top edge and rgb(202,178,123) at
 * its bottom — one tone, unchanged down the whole tile. Against the console-art metal ramp the product
 * uses everywhere else (rgb(255,243,200) light through rgb(187,140,78) deep) the ring was a single stop,
 * and from the ramp's LOWER half. It was never the wrong hue. It had no light in it.
 *
 * These two numbers give it the ramp's dynamic range without touching its hue: at the default
 * `accentBright`, lift 0.45 and shade 0.22 put the ends at ~rgb(242,223,184) and ~rgb(181,154,98),
 * bracketing the metal's own span.
 */
internal const val MimirFocusRingLift = 0.45f
internal const val MimirFocusRingShade = 0.22f

/** The ring's bright stroke at full progress. An edge-flush chip's rim reaches it too, so the rim and that stroke are one band. */
internal const val MimirFocusRingBrightAlpha = 0.95f

/**
 * The ring's light ramp: its own colour lifted toward white at the top, as is at 42 %, shaded at the bottom, over a vertical span. One definition for the
 * ring and for an etched chip's rim as it takes the ring's colour (`mimirEtchedChipSurface`'s `edgeFlush`), so the two agree at every point of the edge.
 */
internal fun mimirFocusRingLitBrush(color: Color, startY: Float, endY: Float): Brush = Brush.verticalGradient(
    0.00f to lerp(color, Color.White, MimirFocusRingLift),
    0.42f to color,
    1.00f to lerp(color, Color.Black, MimirFocusRingShade),
    startY = startY,
    endY = endY,
)

/**
 * **The ramp is derived from the ring's OWN colour, not from the gold metal brush.**
 *
 * Striking this with `platformConsoleIconBrush` would have been one line and wrong: that ramp is the
 * console art's, and `tileFocusRing` is a per-theme token — Baldr overrides it to azure, Folkvangr to rose —
 * that `MimirAppearanceOverrides` can also retarget at RUNTIME to a colour the player chose. A fixed gold
 * would have made every theme's focus gold and silently ignored the override. Lifting and shading the
 * colour that ARRIVES keeps the token the authority and lets each theme, and each override, ramp in its
 * own hue.
 *
 * One brush spans all three strokes, built over the OUTERMOST outset rather than per-ring: three
 * concentric rings each carrying their own gradient would disagree about where the light is, which is
 * three light sources on an object that has one.
 *
 * [gap] moves all three rings out by the same distance, leaving clear ground between the element and its
 * ring. Tiles pass nothing: art never shares the ring's colour. A FILLED control does, and passes
 * [MimirFilledControlRingGap].
 */
fun Modifier.mimirFocusTileRing(
    progress: () -> Float,
    color: Color,
    cornerRadius: Dp = 14.dp,
    gap: Dp = 0.dp,
    /** The settle gleam's travel, from [rememberMimirFocusSettleGlint]; idle ([MimirFocusRingGlintIdle]) by default. */
    glint: () -> Float = MimirFocusRingNoGlint,
    /** The focus flare's heat, from [rememberMimirFocusFlare]; none ([MimirFocusRingNoFlare]) by default. */
    flare: () -> Float = MimirFocusRingNoFlare,
    /**
     * Half the gleam band's diagonal extent. Unspecified keeps the tile rule (a quarter of the shorter side); a
     * pill, whose shorter side is 40-46 dp, passes a fixed reach so its gleam is not a 10 dp flick.
     */
    glintReach: Dp = Dp.Unspecified,
): Modifier = drawWithCache {
    // Built once per size (and per call-site recomposition), never per frame: a traverse redraws this ring
    // every frame on the Thor, which is GC-sensitive at 35-55 fps.
    val paint = mimirFocusTileRingPaint(
        color = color,
        cornerRadius = cornerRadius,
        elementSize = size,
        gap = gap,
        glintReach = glintReach,
    )
    onDrawBehind { drawMimirFocusTileRing(paint, progress(), glint(), flare()) }
}

/**
 * A [mimirFocusTileRing] built once for an element of one size: its light ramp, its three strokes and their
 * geometry. [drawMimirFocusTileRing] then draws it every frame without allocating.
 *
 * It is also how a ring that is NOT a modifier on its element is drawn -- the dock's one gliding ring, which
 * travels between seats -- so that ring is the same three strokes and the same light as every tile's.
 */
class MimirFocusTileRingPaint internal constructor(
    internal val lit: Brush,
    internal val elementSize: Size,
    internal val radius: Float,
    internal val outsets: FloatArray,
    internal val strokes: Array<Stroke>,
    internal val alphas: FloatArray,
    /** The settle gleam: a diagonal band of light, built once and moved by a canvas translate. */
    internal val glint: Brush,
    /** Half the gleam band's extent along the x+y diagonal, in px; see [mimirFocusGlintShiftPx]. */
    internal val glintReach: Float,
    /** How far past its seat each stroke lands at full flare, in px; see [rememberMimirFocusFlare]. */
    internal val flareReach: Float = 0f,
    /** Each stroke's alpha at full flare; it cools to [alphas]. */
    internal val flareAlphas: FloatArray = alphas,
    /** The sparkle's star colour: the ring's own hue lifted toward white; see [rememberMimirFocusGameGleam]. */
    internal val sparkle: Color = Color.Unspecified,
    /** One star's arm length at its peak, in px; 0 draws no sparkle. */
    internal val sparkleArm: Float = 0f,
    /** A dense key ring: the bright stroke alone, and no light plays on it. See [MimirRingShape.Key]. */
    internal val dense: Boolean = false,
) {
    /**
     * A four-point star of unit arm, scaled per frame. Built on the first sparkle, not with the paint: most paints never
     * sparkle, and a plain JVM test that builds one has no android.graphics.Path to call.
     */
    internal val sparkleStar: Path by lazy(LazyThreadSafetyMode.NONE) { mimirFocusSparkleUnitStar() }
}

fun Density.mimirFocusTileRingPaint(
    color: Color,
    cornerRadius: Dp,
    elementSize: Size,
    gap: Dp = 0.dp,
    glintReach: Dp = Dp.Unspecified,
    dense: Boolean = false,
    /**
     * The ring sits FLUSH on the element's edge: its bright stroke shows 1.5 dp, from the edge outward (the default's is 3 dp and starts half a dp out), and
     * the two glow strokes follow it without a gap. For an etched chip, whose own rim is struck just inside the edge: the rim and this stroke then
     * read as ONE band, where the default's half-dp gap read as two outlines. The bright stroke runs a
     * quarter dp under the plate (which paints over it) so the plate's anti-aliased edge never shows a seam of ground between the two.
     */
    flush: Boolean = false,
): MimirFocusTileRingPaint {
    val clear = gap.toPx()
    val widestOutset = 10.dp.toPx() + clear
    val lit = mimirFocusRingLitBrush(color, startY = -widestOutset, endY = elementSize.height + widestOutset)
    val glintReachPx = if (glintReach.isSpecified) {
        glintReach.toPx().coerceAtLeast(1f)
    } else {
        (minOf(elementSize.width, elementSize.height) * MimirFocusRingGlintReach).coerceAtLeast(1f)
    }
    val restAlphas = floatArrayOf(0.08f, 0.18f, MimirFocusRingBrightAlpha)
    return MimirFocusTileRingPaint(
        lit = lit,
        elementSize = elementSize,
        radius = cornerRadius.toPx(),
        // Default: strokes 7/5/3 dp centred 10/6/2 dp out (the bright one covers 0.5 to 3.5 dp). Flush: 7/5/1.75 dp centred 10/4/0.625 dp out, so the
        // bright one covers -0.25 (under the plate) to 1.5, the middle 1.5 to 6.5 and the outer 6.5 to 13.5: the same reach, no gap between them or to the edge.
        outsets = if (flush) {
            floatArrayOf(widestOutset, 4.dp.toPx() + clear, 0.625.dp.toPx() + clear)
        } else {
            floatArrayOf(widestOutset, 6.dp.toPx() + clear, 2.dp.toPx() + clear)
        },
        strokes = arrayOf(
            Stroke(width = 7.dp.toPx()),
            Stroke(width = 5.dp.toPx()),
            Stroke(width = (if (flush) 1.75.dp else 3.dp).toPx()),
        ),
        alphas = restAlphas,
        glint = Brush.linearGradient(
            0.0f to Color.Transparent,
            0.5f to lerp(color, Color.White, MimirFocusRingGlintLift),
            1.0f to Color.Transparent,
            start = Offset.Zero,
            end = Offset(glintReachPx, glintReachPx),
        ),
        glintReach = glintReachPx,
        flareReach = MimirFocusFlareReach.toPx(),
        flareAlphas = MimirFocusFlareAlphas,
        sparkle = lerp(color, Color.White, MimirFocusSparkleLift),
        sparkleArm = MimirFocusSparkleArm.toPx(),
        dense = dense,
    )
}

/**
 * Draws [paint] with its element's top-left at the draw scope's origin. Translate first to place it elsewhere.
 *
 * [glint] below [MimirFocusRingGlintIdle] also draws the settle gleam along the bright inner stroke only: the
 * band brush stays fixed and the canvas moves under the stroke, so the gleam travels without a new brush. Between
 * [MimirFocusRingGlintIdle] and [MimirFocusRingSparkleEnd] it draws a game's sparkle instead
 * ([rememberMimirFocusGameGleam]).
 */
fun DrawScope.drawMimirFocusTileRing(
    paint: MimirFocusTileRingPaint,
    progress: Float,
    glint: Float = MimirFocusRingGlintIdle,
    flare: Float = 0f,
) {
    val p = progress.coerceIn(0f, 1f)
    val h = flare.coerceIn(0f, 1f)
    // At zero heat this is exactly the ring as it always was: strength p, no spread, the resting alphas.
    val strength = maxOf(p, h)
    if (strength <= 0f) return
    val spread = paint.flareReach * h
    // A dense key ring is the bright stroke alone ([MimirFocusRingDenseStroke]): it reaches 3.5 dp, inside
    // [MimirDenseKeyRingReach], so it never washes over the next key's legend (keys sit 4 dp apart).
    for (ring in (if (paint.dense) MimirFocusRingDenseStroke else 0) until 3) {
        val outset = paint.outsets[ring] + spread
        drawRoundRect(
            brush = paint.lit,
            topLeft = Offset(-outset, -outset),
            size = Size(paint.elementSize.width + outset * 2f, paint.elementSize.height + outset * 2f),
            cornerRadius = CornerRadius(paint.radius + outset),
            style = paint.strokes[ring],
            alpha = mimirFocusRingStrokeAlpha(paint.alphas[ring], paint.flareAlphas[ring], p, h),
        )
    }
    if (!paint.dense && p > 0f && glint >= 0f && glint < MimirFocusRingGlintIdle) {
        val outset = paint.outsets[2]
        val shift = mimirFocusGlintShiftPx(
            travel = glint,
            elementWidth = paint.elementSize.width,
            elementHeight = paint.elementSize.height,
            outset = outset,
            reach = paint.glintReach,
        )
        translate(left = shift, top = shift) {
            drawRoundRect(
                brush = paint.glint,
                topLeft = Offset(-outset - shift, -outset - shift),
                size = Size(paint.elementSize.width + outset * 2f, paint.elementSize.height + outset * 2f),
                cornerRadius = CornerRadius(paint.radius + outset),
                style = paint.strokes[2],
                alpha = p,
            )
        }
    }
    if (!paint.dense && p > 0f && glint > MimirFocusRingGlintIdle && glint < MimirFocusRingSparkleEnd) {
        drawMimirFocusSparkle(paint, sparkle = glint - MimirFocusRingGlintIdle, strength = p)
    }
}

/**
 * The silent shiny-game sparkle: four small star glints twinkle sequentially at the 45-degree
 * points of the middle stroke's corners, bottom-right where the sweep leaves, top-right, top-left, then
 * bottom-left. Each grows and fades on a half sine over [MimirFocusSparkleLife] of the window, and a star has faded
 * past its peak before the next one starts, so the eye follows one light round the corners. Every star is gone when
 * the sparkle ends. Nothing here allocates: the star is one cached unit path, scaled per frame.
 */
private fun DrawScope.drawMimirFocusSparkle(paint: MimirFocusTileRingPaint, sparkle: Float, strength: Float) {
    if (paint.sparkleArm <= 0f) return
    val star = paint.sparkleStar
    val outset = paint.outsets[1]
    val cornerRadius = paint.radius + outset
    // From the stroke rect's corner to its arc's 45-degree point, along each axis.
    val inset = cornerRadius * (1f - MimirFocusSparkleCornerCos)
    drawMimirFocusSparkleStars(
        star = star,
        color = paint.sparkle,
        sparkle = sparkle,
        left = -outset + inset,
        top = -outset + inset,
        right = paint.elementSize.width + outset - inset,
        bottom = paint.elementSize.height + outset - inset,
        arm = paint.sparkleArm,
        strength = strength,
    )
}

/**
 * One sparkle's four stars on the corners of a rect, at [sparkle] (0..1 through the window), in the ring's order --
 * 0 bottom-right, 1 top-right, 2 bottom-left, 3 top-left, staggered by [MimirFocusSparkleStagger] so they peak
 * clockwise from the bottom-right -- each [arm] px at its peak, with its soft halo. The ring plays them on its middle
 * stroke's corners; the Mannaz pin plays the same stars on its rune's four tips when a game is marked
 * ([MimirMannazPin]), so a mark sparkles as a focused game does. Nothing here allocates.
 */
internal fun DrawScope.drawMimirFocusSparkleStars(
    star: Path,
    color: Color,
    sparkle: Float,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    arm: Float,
    strength: Float,
) {
    for (index in 0 until MimirFocusSparkleStars) {
        val scale = mimirFocusSparkleStarScale(sparkle, index)
        if (scale <= 0f) continue
        // 0 bottom-right, 1 top-right, 2 bottom-left, 3 top-left.
        val x = if (index < 2) right else left
        val y = if (index % 2 == 0) bottom else top
        val reach = arm * scale
        drawCircle(color = color, radius = reach * 0.55f, center = Offset(x, y), alpha = 0.22f * strength * scale)
        withTransform({
            translate(x, y)
            scale(reach, reach, pivot = Offset.Zero)
        }) {
            drawPath(star, color = color, alpha = strength * scale)
        }
    }
}

/** One star's size at [sparkle] (0..1 through the window): 0 before its turn and after it, 1 at its peak. */
internal fun mimirFocusSparkleStarScale(sparkle: Float, star: Int): Float {
    val local = (sparkle.coerceIn(0f, 1f) - MimirFocusSparkleStagger[star]) / MimirFocusSparkleLife
    if (local <= 0f || local >= 1f) return 0f
    return sin(PI.toFloat() * local)
}

/** A four-point star of arm 1 about the origin, thin at the waist so it reads as a glint, not a cross. */
internal fun mimirFocusSparkleUnitStar(): Path = Path().apply {
    val waist = 0.2f
    moveTo(0f, -1f)
    lineTo(waist, -waist)
    lineTo(1f, 0f)
    lineTo(waist, waist)
    lineTo(0f, 1f)
    lineTo(-waist, waist)
    lineTo(-1f, 0f)
    lineTo(-waist, -waist)
    close()
}

internal const val MimirFocusSparkleStars = 4
/** The bright inner stroke's index in a paint's strokes: the one a dense key ring draws. */
internal const val MimirFocusRingDenseStroke = 2
/**
 * When each star's turn starts, as a share of the window, by index (0 bottom-right, 1 top-right, 2 bottom-left,
 * 3 top-left): clockwise from the bottom-right, a quarter-window apart, each overlapping the next only in its fade.
 * The last (bottom-left) ends just before 1, so no star is still lit when the sparkle ends. It was 0 / 0.15 / 0.15 / 0.3 over 0.7 each, which lit all four at once.
 */
private val MimirFocusSparkleStagger = floatArrayOf(0f, 0.22f, 0.66f, 0.44f)
internal const val MimirFocusSparkleLife = 0.33f
private const val MimirFocusSparkleCornerCos = 0.70710677f
/** Stays inside the ring's reach: the star's centre sits on the middle stroke, so its arm ends within 14 dp. */
internal val MimirFocusSparkleArm = 6.dp
/** How far toward white a sparkle's stars are lifted from their element's colour; the Mannaz pin lifts its gold the same. */
internal const val MimirFocusSparkleLift = 0.8f

/**
 * Where the gleam band sits at [travel] (0..1), as the shift applied to it along both axes.
 *
 * The band spans `x + y` in `[2 shift, 2 shift + 2 reach]`, and the inner stroke's outer rect spans
 * `[-2 outset, width + height + 2 outset]`. At 0 the band ends exactly where the rect begins and at 1 it starts
 * exactly where the rect ends, so it is out of sight at both ends and there is no pop in or out.
 */
internal fun mimirFocusGlintShiftPx(
    travel: Float,
    elementWidth: Float,
    elementHeight: Float,
    outset: Float,
    reach: Float,
): Float {
    val from = -outset - reach
    val to = (elementWidth + elementHeight) / 2f + outset
    return from + (to - from) * travel.coerceIn(0f, 1f)
}

/**
 * Calm shine tones down light for sensitive players. Off preserves the default appearance. On,
 * a focused game gets one gleam crossing ending in its sparkle, then still; every other ring
 * every other ring -- a platform tile or icon, Settings, Utility, the Tweaker, Quick Launch, any button or row --
 * draws no light at all. The rings themselves, their settle and their flare stay: it is the shine that goes, not
 * the focus.
 *
 * Provided by [MimirTheme] from [MimirCalmShineSettings], the Utility switch, so every window that wears the
 * theme has it. Read in [rememberMimirFocusSettleGlint], the one place every gleam is made, so no site decides
 * for itself. Dynamic, not static: a flip re-keys only the gleams
 * that read it, and each restarts on its own settle delay.
 */
val LocalMimirCalmShine = compositionLocalOf { false }

/**
 * One gleam across a focus ring after selection has SETTLED on [settledOn].
 *
 * **The ring itself stays snapped.** a ring that tweens 0 -> 1 on arrival regrows on every step of a traverse, so every ring
 * here stays at full strength and snapped. What moves is light: one band travels along the ring's bright stroke
 * after [MimirFocusSettleDelayMs] of stillness. A held pad re-keys faster than that, so a traverse never gleams;
 * only the tile you stop on does. It crosses [passes] times, [durationMs] each, and then nothing animates at rest.
 * With [sparkleMs] each crossing ends in a sparkle of that length, which is also the beat between crossings; without
 * one the crossings sit [MimirFocusGlintRestMs] apart. A focused game uses [rememberMimirFocusGameGleam]; everything
 * else keeps the single plain pass. Moving on cancels it at once, and a new selection starts its own count. Under
 * reduced motion, and while nothing is selected (a null [settledOn]), it stays idle.
 *
 * Under calm shine ([LocalMimirCalmShine]) it crosses [calmPasses] times instead: none by default, so a button's,
 * a row's or a platform tile's plain sweep is gone, and one for a game ([rememberMimirFocusGameGleam]). The
 * switch is read here, in the one place every gleam is made, so no site decides for itself; the ring under the
 * light settles and flares as it always did.
 *
 * The returned provider is read in the DRAW phase by [mimirFocusTileRing], so the gleam never recomposes. One value
 * carries both phases: a crossing's travel below [MimirFocusRingGlintIdle], a sparkle's progress above it.
 *
 * [armed] is the host's arrival: the gleam arms when the arrival settles, not on
 * focus. It is waited on through a snapshot flow after the settle delay, so it must read snapshot state -- an
 * `Animatable`'s value, as onboarding's step arrival is. A host with no arrival passes [MimirFocusAlwaysArmed].
 */
@Composable
fun rememberMimirFocusSettleGlint(
    settledOn: Any?,
    durationMs: Int = MimirFocusGlintMs,
    passes: Int = 1,
    sparkleMs: Int = 0,
    /** The last crossing's sparkle, which the eye rests on; [sparkleMs] by default. */
    finalSparkleMs: Int = sparkleMs,
    armed: () -> Boolean = MimirFocusAlwaysArmed,
    /** How many crossings calm shine leaves: none for a plain sweep, [MimirFocusGameCalmGlintPasses] for a game. */
    calmPasses: Int = 0,
): () -> Float {
    val glint = remember { Animatable(MimirFocusRingGlintIdle) }
    val reducedMotion = mimirReducedMotion()
    val currentArmed by rememberUpdatedState(armed)
    val motionLive = LocalMimirMotionLive.current
    // Folded into the count before the effect, so a flip of the switch restarts it through the key it already has.
    val crossings = mimirFocusGlintCrossings(
        passes = passes,
        calmPasses = calmPasses,
        calm = LocalMimirCalmShine.current,
    )
    LaunchedEffect(settledOn, reducedMotion, durationMs, crossings, sparkleMs, finalSparkleMs) {
        glint.snapTo(MimirFocusRingGlintIdle)
        if (crossings <= 0) return@LaunchedEffect
        if (!mimirFocusGlintArmed(settledOn = settledOn, reducedMotion = reducedMotion)) return@LaunchedEffect
        // D24: the whole wait and the gleam itself run only while the screen they are on is live. A parked pane (nobody can see it) starts no settle timer and
        // leaves no armed wait behind, and a gleam already under way is cut to its end at once with no frame left to draw. A host that never parks is the
        // always-live default and runs exactly as before.
        runWhileMotionLive(motionLive, onParked = { glint.snapTo(MimirFocusRingGlintIdle) }) {
            delay(MimirFocusSettleDelayMs)
            if (!currentArmed()) snapshotFlow { currentArmed() }.first { it }
            repeat(crossings) { pass ->
                if (pass > 0 && sparkleMs <= 0) delay(MimirFocusGlintRestMs)
                glint.snapTo(0f)
                glint.animateTo(MimirFocusRingGlintIdle, tween(durationMs, easing = MimirMotion.Easing))
                if (sparkleMs > 0) {
                    val ms = if (pass == crossings - 1) finalSparkleMs else sparkleMs
                    glint.animateTo(MimirFocusRingSparkleEnd, tween(ms, easing = LinearEasing))
                    glint.snapTo(MimirFocusRingGlintIdle)
                }
            }
        }
    }
    return remember(glint) { { glint.value } }
}

/**
 * A focused game's gleam: [MimirFocusGameGlintPasses] slow crossings of [MimirFocusGameGlintMs], each ending in a
 * silent sparkle of [MimirFocusSparkleMs], then still. The earlier sequence used three 1 s sweeps
 * with a silent star sparkle after each, about 4 s total. Used by every game tile: the Home shelf, the
 * Library's game lists and onboarding's favourites. Under calm shine ([LocalMimirCalmShine]) it is one crossing
 * ([MimirFocusGameCalmGlintPasses]) that ends in its sparkle, then still: the light still reads as crossing.
 */
@Composable
fun rememberMimirFocusGameGleam(settledOn: Any?, armed: () -> Boolean = MimirFocusAlwaysArmed): () -> Float =
    rememberMimirFocusSettleGlint(
        settledOn = settledOn,
        durationMs = MimirFocusGameGlintMs,
        passes = MimirFocusGameGlintPasses,
        sparkleMs = MimirFocusSparkleMs,
        finalSparkleMs = MimirFocusFinalSparkleMs,
        armed = armed,
        calmPasses = MimirFocusGameCalmGlintPasses,
    )

internal fun mimirFocusGlintArmed(settledOn: Any?, reducedMotion: Boolean): Boolean =
    settledOn != null && !reducedMotion

/**
 * How many times the gleam crosses: [passes], at least one, as it always was; under [calm], [calmPasses], which
 * may be none. Calm is a pass count and never a clock: the crossing it leaves keeps its duration and its sparkle.
 */
internal fun mimirFocusGlintCrossings(passes: Int, calmPasses: Int, calm: Boolean): Int =
    if (calm) calmPasses.coerceAtLeast(0) else passes.coerceAtLeast(1)

/**
 * One stroke's alpha: its resting alpha at [progress], lifted toward its [hot] alpha by [flare]. The `max` keeps the
 * hot ring whole on the flare's first frame, while the ring's own progress is still rising under it; at zero heat
 * this is `rest * progress`, the ring as it always was.
 */
internal fun mimirFocusRingStrokeAlpha(rest: Float, hot: Float, progress: Float, flare: Float): Float {
    val p = progress.coerceIn(0f, 1f)
    val h = flare.coerceIn(0f, 1f)
    return (rest + (hot - rest) * h) * maxOf(p, h)
}

/**
 * One flare when focus CHANGES onto a control: its ring lands hot and wide, then cools onto its seat over
 * [MimirMotion.OverlayMs] with [MimirMotion.Easing]. The control illuminates when engaged, then settles (2026-09-28).
 *
 * Never on mount, and never within [MimirMotion.OverlayMs] of it: a control that lands with its page is the page
 * arriving, which has its own motion, and overlapping motion over the same pixels would obscure it.
 * Never while [armed] says no (onboarding passes "the step has finished arriving"), never under reduced motion,
 * never when focus LEAVES ([settledOn] null), and never while a traverse is streaming ([MimirFocusTraversal.streaming]):
 * a held pad re-keys every 120 ms, and a flare on each step would pulse the whole way (ring migration, 2026-09-28).
 * The heat is read in the draw phase, so a flare recomposes nothing.
 *
 * [durationMs] is the cool-down only; the mount-quiet window stays [MimirMotion.OverlayMs], because that window is
 * the page arriving, not the flare's speed.
 */
@Composable
fun rememberMimirFocusFlare(
    settledOn: Any?,
    armed: () -> Boolean = { true },
    durationMs: Int = MimirFocusGlintMs,
): () -> Float {
    val heat = remember { Animatable(0f) }
    val reducedMotion = mimirReducedMotion()
    val currentArmed by rememberUpdatedState(armed)
    // What this composition was born on, so its own first value never counts as a change.
    val previous = remember { arrayOf(settledOn) }
    val mountQuiet = remember { booleanArrayOf(true) }
    LaunchedEffect(Unit) {
        mimirHold(MimirMotion.OverlayMs)
        mountQuiet[0] = false
    }
    LaunchedEffect(settledOn, reducedMotion) {
        val fires = mimirFocusFlareArmed(settledOn, previous[0], reducedMotion, MimirFocusTraversal.streaming) &&
            !mountQuiet[0] && currentArmed()
        previous[0] = settledOn
        heat.snapTo(if (fires) 1f else 0f)
        if (fires) heat.animateTo(0f, tween(durationMs, easing = MimirMotion.Easing))
    }
    return remember(heat) { { heat.value } }
}

internal fun mimirFocusFlareArmed(
    settledOn: Any?,
    previous: Any?,
    reducedMotion: Boolean,
    streaming: Boolean = false,
): Boolean = settledOn != null && settledOn != previous && !reducedMotion && !streaming

/** A flare value at which nothing flares. */
val MimirFocusRingNoFlare: () -> Float = { 0f }

/** The arming hook of a ring whose host has no arrival to wait for: the gleam and the flare run as soon as focus settles. */
val MimirFocusAlwaysArmed: () -> Boolean = { true }

/** How far past its seat the ring lands at full flare. Public so a host can leave room for it (onboarding's footer). */
val MimirFocusFlareReach = 6.dp

/** The three strokes' alphas at full flare; they cool to the resting 0.08 / 0.18 / 0.95. */
private val MimirFocusFlareAlphas = floatArrayOf(0.26f, 0.48f, 1f)

/**
 * The house focus wash: the accent laid over a focused container, Material's state layer. The glass row's focused
 * fill already uses this alpha; onboarding's ghost pills now share it.
 */
const val MimirFocusWashAlpha: Float = 0.16f

/**
 * How long a selection must hold still before its ring gleams. Longer than a held pad's repeat (120 ms,
 * `InputTuning.directionalRepeatMs`), so a traverse never gleams, and longer than the shelves' own glide into
 * the seat (140 ms, `CoverflowMotionDurationMs`), so the gleam lands on a tile that has arrived.
 */
const val MimirFocusSettleDelayMs: Long = 160L

/**
 * The gleam's one pass: three times the house transient bound, 540 ms. It was OverlayMs, the budget the
 * dock's old specular sweep had, before product-wide ring/flare timing slowed through
 * 2x, 2.5x and then 3x; see [MimirFocusRingSettleMs]. The gleam waits for focus to rest ([MimirFocusSettleDelayMs]),
 * so a traverse never meets it.
 */
const val MimirFocusGlintMs: Int = MimirMotion.OverlayMs * 3

/** Games use one sweep ending in a slow final sparkle. The prior sequence used three sweeps;
 * buttons and platform tiles retain one plain pass. Applies to Home, Library and onboarding games. */
const val MimirFocusGameGlintPasses: Int = 1

/**
 * A focused game's crossings under calm shine ([LocalMimirCalmShine]): one, ending in its sparkle, so the light
 * still reads as crossing the ring and settling. Buttons, rows and platform tiles draw none.
 */
const val MimirFocusGameCalmGlintPasses: Int = 1

/** One game-gleam crossing lasts one second. Buttons and platform tiles keep [MimirFocusGlintMs]. */
const val MimirFocusGameGlintMs: Int = 1_000

/** The silent sparkle between game-gleam crossings slowed from 300 to 600 ms;
 * the last crossing uses [MimirFocusFinalSparkleMs]. */
const val MimirFocusSparkleMs: Int = 600

/**
 * The last crossing's sparkle, the one the eye rests on: 1.5 s.
 * A game has only this one crossing, so its gleam ends [MimirFocusSettleDelayMs] + 1000 + 1500 = about
 * 2.7 s after focus lands; under calm shine the one crossing ends in this sparkle too.
 */
const val MimirFocusFinalSparkleMs: Int = 1_500

/**
 * The rest between two plain crossings (no sparkle). With [MimirFocusGlintMs] it makes a one-second beat. No shipping
 * caller loops without a sparkle since the shiny gleam; the rest stays so a plain loop cannot run back to back.
 */
const val MimirFocusGlintRestMs: Long = 1_000L - MimirFocusGlintMs

/** A glint value at which nothing is drawn: the gleam has finished, or never started. */
const val MimirFocusRingGlintIdle: Float = 1f

/** The glint value at the end of a sparkle; values between [MimirFocusRingGlintIdle] and this draw the sparkle. */
const val MimirFocusRingSparkleEnd: Float = 2f

val MimirFocusRingNoGlint: () -> Float = { MimirFocusRingGlintIdle }

/**
 * How far toward white the gleam's core is lifted from the ring's own colour, so each theme gleams in its hue. The
 * Mannaz pin's mark gleam lifts its gold the same ([MimirMannazPin]).
 */
internal const val MimirFocusRingGlintLift = 0.7f

/** Half the gleam band's diagonal extent, as a share of the element's shorter side; the Mannaz pin's rune keeps the share. */
internal const val MimirFocusRingGlintReach = 0.25f

/**
 * The clear space between a FILLED control's edge and its [mimirFocusTileRing].
 *
 * Confirmation highlights disappeared into their filled buttons. A gold button's
 * focus ring was drawn ON its edge, in `focusRing`, which on the dark themes is the gold the fill is made
 * of, so the ring disappeared into the button. The tile ring's own innermost stroke starts half a dp off the
 * edge, which on a gold fill still reads as the button's own rim. Three dp of the page's ground between them
 * is what makes it a ring.
 */
val MimirFilledControlRingGap = 3.dp

/**
 * How far a FILLED control's [mimirFocusTileRing] reaches PAST that control's own bounds: the outermost
 * ring's 10dp outset, plus [MimirFilledControlRingGap], plus half of its 7dp stroke.
 *
 * Named because layout has to reserve it. The ring is a `drawBehind`, so it takes no space and nothing in
 * a Column knows it is there -- stack a gold pill 8dp from its neighbour and the ring lands inside that
 * neighbour, which reads as the ring being cut off rather than as two things overlapping. The favourites intro page reserves this clearance to prevent that overlap.
 */
val MimirFilledControlRingReach = 17.dp

/**
 * How far the plain, 0-gap ring reaches past its element: the outermost ring's 10 dp outset plus half its 7 dp stroke,
 * 13.5 dp, rounded up. Promoted from the Folio's `ringClearance` and onboarding's 14 (ring migration, 2026-09-28).
 * It is what a container's clip edge, a scroll host's content padding and a focused row's reveal reserve; siblings
 * need only the inner and middle strokes clear, 8 dp (`MimirSpacing.sm`) at 0 gap and 12 dp gapped.
 */
val MimirFocusTileRingReach = 14.dp

/** How far a dense key ring reaches: the bright stroke's 2 dp outset plus half its 3 dp width, 3.5 dp, rounded up. */
val MimirDenseKeyRingReach = 4.dp

/**
 * FUX-04: a Switch-lookalike SQUARE tile — 1:1, rounded, cropped art, ring-on-focus.
 * Used by the Home shelf when an SGDB tile icon exists for the row; the ledge cover is the fallback.
 */
@Composable
fun MimirSwitchTileArt(
    model: Any,
    title: String,
    focused: Boolean,
    focusRingProgress: () -> Float,
    modifier: Modifier = Modifier,
    palette: MimirThemePalette = LocalMimirPalette.current,
    /** The platform shelfmark, seated bottom-leading. See [MimirLedgeCoverArt]'s parameter of the same name. */
    shelfmark: (@Composable () -> Unit)? = null,
    /** Marks on the art, in its clip. See [MimirLedgeCoverArt]'s parameter of the same name. */
    overlay: (@Composable BoxScope.() -> Unit)? = null,
    /** On the tile's box before its ring and clip. See [MimirLedgeCoverArt]'s parameter of the same name. */
    artModifier: Modifier = Modifier,
) {
    // A same-row fetched override changes [model] without necessarily replacing the caller's composition
    // slot. Own the painter + reveal by destination identity so the old square cannot survive one commit.
    key(model) {
        // R8a: a model whose local file is gone is unwired and takes the no-art glyph; a file that exists and fails to decode is
        // wired and draws nothing (R8). The two never share a painter.
        if (rememberMimirArtWired(model)) {
            MimirSwitchTileArtIdentity(
                model = model,
                title = title,
                focused = focused,
                focusRingProgress = focusRingProgress,
                modifier = modifier,
                palette = palette,
                shelfmark = shelfmark,
                overlay = overlay,
                artModifier = artModifier,
            )
        } else {
            MimirSwitchTileArtUnwired(
                title = title,
                focused = focused,
                focusRingProgress = focusRingProgress,
                modifier = modifier,
                palette = palette,
                shelfmark = shelfmark,
                overlay = overlay,
                artModifier = artModifier,
            )
        }
    }
}

/** The switch tile of a source that is not wired (its file is gone): the same seat and ring, the no-art glyph inside. */
@Composable
private fun MimirSwitchTileArtUnwired(
    title: String,
    focused: Boolean,
    focusRingProgress: () -> Float,
    modifier: Modifier,
    palette: MimirThemePalette,
    shelfmark: (@Composable () -> Unit)?,
    overlay: (@Composable BoxScope.() -> Unit)?,
    artModifier: Modifier,
) {
    Box(
        modifier = modifier
            .aspectRatio(1f, matchHeightConstraintsFirst = true)
            .then(artModifier)
            .mimirFocusTileRing(focusRingProgress, palette.tileFocusRing, cornerRadius = MimirSwitchTileCorner)
            .clip(RoundedCornerShape(MimirSwitchTileCorner)),
        contentAlignment = Alignment.Center,
    ) {
        MediaArtFallbackGlyph(
            title = title,
            focused = focused,
            palette = palette,
            showTitle = false,
            fitTitle = true,
            mannazClearBottomDp = if (shelfmark != null) MimirShelfmarkBandDp else 0f,
        )
        if (shelfmark != null) {
            MimirShelfmarkSeat(shelfmark)
        }
        overlay?.invoke(this)
    }
}

@Composable
private fun MimirSwitchTileArtIdentity(
    model: Any,
    title: String,
    focused: Boolean,
    focusRingProgress: () -> Float,
    modifier: Modifier,
    palette: MimirThemePalette,
    shelfmark: (@Composable () -> Unit)?,
    overlay: (@Composable BoxScope.() -> Unit)?,
    artModifier: Modifier,
) {
    val context = LocalContext.current
    val painter = rememberMimirLedgeArtPainter(model)
    val artReady = when (val state = painter.state) {
        is AsyncImagePainter.State.Success -> true
        is AsyncImagePainter.State.Loading -> state.painter != null
        else -> false
    }
    // the earlier update : synchronous cache probe — a warm revisit snaps to the tile art on arrival
    // instead of fading from the book (fresh painters report not-ready on frame 0 even when cached).
    val warmHit = remember(model) { mimirArtWarmInMemory(context, model) }
    val warmAtBirth = remember { artReady || warmHit }
    val artAlpha by animateFloatAsState(
        targetValue = if (artReady) 1f else 0f,
        animationSpec = if (warmAtBirth) {
            snap()
        } else {
            MimirColdArtRevealSpec
        },
        label = "switchTileArtReveal",
    )
    val shape = RoundedCornerShape(MimirSwitchTileCorner)
    Box(
        modifier = modifier
            .aspectRatio(1f, matchHeightConstraintsFirst = true)
            .then(artModifier)
            .mimirFocusTileRing(focusRingProgress, palette.tileFocusRing, cornerRadius = MimirSwitchTileCorner)
            // No plate (see [mimirArtPlateVisible]). A previous change dropped it once the art was fully
            // revealed, which still left a plate square for the whole cold decode and through the 140 ms fade --
            // the cold-start square the regression report described on Nova. The tile is a wired source: while its bitmap is
            // pending it draws nothing, then the art fades in over the shelf; transparent corners show the shelf.
            .clip(shape),
        contentAlignment = Alignment.Center,
    ) {
        // R8 (2026-10-02): this tile is a wired source, so it never draws the glyph, not even for a decode failure; the ground, the
        // ring and the shelfmark stay. The failure is logged.
        MimirArtFailureLog(failed = painter.state is AsyncImagePainter.State.Error, model = model)
        if (artReady) {
            Image(
                painter = painter,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = artAlpha,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (shelfmark != null) {
            MimirShelfmarkSeat(shelfmark)
        }
        overlay?.invoke(this)
    }
}

/**
 * Cold-Home crash guard: the ONLY place the ledge may read a painter's intrinsic bounds. A cold
 * Loading painter reports [Size.Unspecified], whose width/height accessors THROW
 * (`IllegalStateException: Size is unspecified`) — so the isSpecified gate runs before any read, and
 * Unspecified resolves to unknown aspects (the caller's neutral-bounds + fade-in cold reveal).
 */
internal fun mimirLedgeIntrinsicAspects(intrinsic: Size): MimirLedgeIntrinsicAspects {
    if (intrinsic.isUnspecified) {
        return MimirLedgeIntrinsicAspects(raw = null, clamped = null)
    }
    val raw = if (intrinsic.height > 0f && intrinsic.width.isFinite() && intrinsic.height.isFinite()) {
        intrinsic.width / intrinsic.height
    } else {
        null
    }
    return MimirLedgeIntrinsicAspects(
        raw = raw,
        clamped = mimirLedgeCoverAspect(intrinsic.width, intrinsic.height),
    )
}

/** [raw] = true painted proportions (caret width); [clamped] = the layout aspect (null = unknown). */
internal data class MimirLedgeIntrinsicAspects(val raw: Float?, val clamped: Float?)

@Composable
internal fun rememberMimirLedgeArtPainter(model: Any): AsyncImagePainter {
    val context = LocalContext.current
    val request = remember(model, context) {
        mimirCoverRequest(context, model)
    }
    return rememberAsyncImagePainter(model = request)
}

/**
 * FLAT cover art : just the cover image on a plain rounded panel — NO cartridge/box frame
 * chrome (no gradient shell, no hairline border, no platform pill). The physical-media-shell "box" treatment
 * is reserved for the GAMELIST; every OTHER surface (game-detail page, companion Now-Playing / detail) shows
 * the art only. Keeps the 3:4 aspect + card radius so it sits where a framed cover would; degrades to the same
 * honest glyph when no art is wired.
 */
@Composable
fun MimirFlatCoverArt(
    title: String,
    artRef: Any?,
    modifier: Modifier = Modifier,
    palette: MimirThemePalette = LocalMimirPalette.current,
) {
    // Replace the complete presentation group on identity changes. In particular, an art-bearing source
    // cannot lend its painter or reveal state to the next no-art/error destination in this persistent slot.
    key(mimirArtPresentationIdentity(artRef, MimirArtPresentationKind.FLAT_COVER)) {
        // ONE resolved source state for the plate and the content (R8a): a cover whose file is gone is no art, so the panel and the
        // glyph arrive together. The stat is off-main; while it is pending the cover counts as wired.
        val hasArt = artRef != null && rememberMimirArtWired(artRef)
        // Handed to the box art inside so it takes THIS verdict and does not stat the cover a second time.
        val coverVerdict = remember(artRef) { MimirArtVerdict(artRef, wired = true) }
        Box(
            modifier = modifier
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(MimirSpacing.radiusCard))
                // The panel is the no-art fallback's seat: with a cover wired it draws nothing behind it, so a
                // transparent flat cover sits on the page instead of a square.
                .then(
                    if (mimirArtPlateVisible(hasArtSource = hasArt)) {
                        Modifier.background(palette.surfaceHigh)
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (artRef != null && hasArt) {
                // FIT (not Crop) so the whole flat cover shows without clipping —.
                CompositionLocalProvider(LocalMimirArtVerdict provides coverVerdict) {
                    MimirBoxArt(model = artRef, title = title, focused = false, contentScale = ContentScale.Fit)
                }
            } else {
                MediaArtFallbackGlyph(title = title, focused = false, palette = palette, showTitle = false, fitTitle = true)
            }
        }
    }
}

/**
 * The natural aspect each ledge cover last presented, by cover key. A cover composed again -- Home is
 * mounted afresh on every return from the Library -- is born at its own shape instead of the neutral 3:4 and a 140 ms
 * morph. Bounded; a miss is today's behaviour.
 */
internal object MimirLedgeAspectMemory {
    private const val Capacity = 2_048
    private val aspects = java.util.concurrent.ConcurrentHashMap<String, Float>()

    fun seedFor(model: Any?): Float? = model?.let { aspects[mimirCoverCacheKey(it)] }

    fun record(model: Any, aspect: Float) {
        if (aspects.size > Capacity) aspects.clear()
        aspects[mimirCoverCacheKey(model)] = aspect
    }
}
