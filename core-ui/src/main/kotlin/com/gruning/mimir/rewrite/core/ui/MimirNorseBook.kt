package com.gruning.mimir.rewrite.core.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The one radius a book cover and every ring drawn around it must share.
 *
 * Both the cover clip and `mimirFocusTileRing` derive from this, so the art can never intersect the
 * ring -- they are the same curve by construction at every width. A caller that draws a ring around
 * a book WITHOUT rendering the book itself, like the home shelf's stationary selection ring floating
 * over the seat, has nothing to keep it honest and must call this rather than hardcode a value. One
 * did, at 8.dp, and disagreed with the cover wherever this clamps above 8 .
 *
 * [widthDp] is the cover's width in dp, not pixels.
 */
fun mimirNorseBookCoverCorner(widthDp: Float): Dp =
    (widthDp.coerceAtLeast(1f) * 0.05f).coerceIn(4f, 12f).dp

/**
 * Where the glyph plate's safe band starts, in dp down from the cover's top edge. Pure, so the law is
 * pinned by a JVM test rather than trusted -- same discipline as [mimirNorseBookMannazStrokePx].
 *
 * Files folio platform labels appeared oversized (2026-08-14).
 *
 * The band is supposed to begin below the title lockup. But the lockup is anchored entirely to WIDTH
 * (`offset(y = bookWidth * 0.13f)`, `spacedBy(bookWidth * 0.028f)`, and both font sizes derived from
 * `widthValue`), while the band's start was a fraction of HEIGHT. Those two agree at exactly one
 * proportion -- the 3:4 the fraction was authored against -- and diverge at every other, in the
 * direction that a SHORTER face squeezes the mark up into the title.
 *
 * Measured on the folio's own ~118dp tome (reproduced by
 * `MimirNorseBookTest`): the band cleared the lockup by **~10.5dp at 3:4 and ~0.9dp at the Files
 * folio's 1 : 1.14**. The mark nearly touched the title, making the glyph look oversized.
 *
 * So the fix is not a new number, it is the right ANCHOR: express the same safe top as a fraction of
 * WIDTH. `fraction / MimirNorseBookAspectRatio` is written as that quotient deliberately -- it is not a
 * fresh constant that happens to match, it is algebraically the same value at 3:4 (`H = W / 0.75`, so
 * `H * 0.42 == W * (0.42 / 0.75)`), so the clearance is now the SAME at every proportion instead of a
 * property of one of them.
 *
 * **The default path is kept literally, not merely equivalently.** At [MimirNorseBookAspectRatio] this
 * returns `bookHeightDp * fraction`, the exact expression the call site used before, evaluated on the
 * exact same measured height -- so both Home tome faces (neither passes `aspectRatio`; see
 * `HomeTomeFace.kt`) are byte-identical, not approximately so. That matters because the measured height
 * comes back from the `aspectRatio` modifier already pixel-rounded, so recomputing it from width would
 * differ by a fraction of a dp and could move real pixels in the visual gate.
 */
internal fun mimirNorseBookPlateSafeTopDp(
    bookWidthDp: Float,
    bookHeightDp: Float,
    aspectRatio: Float,
    compact: Boolean,
): Float {
    val fraction = if (compact) {
        MimirNorseBookCompactPlateSafeTopFraction
    } else {
        MimirNorseBookPlateSafeTopFraction
    }
    return if (aspectRatio == MimirNorseBookAspectRatio) {
        bookHeightDp * fraction
    } else {
        bookWidthDp * (fraction / MimirNorseBookAspectRatio)
    }
}


/** One bound-book silhouette on every Mimir surface. Width / height. */
const val MimirNorseBookAspectRatio = 3f / 4f
internal const val MimirNorseBookEmptyMarkFraction = 0.30f
internal const val MimirNorseBookEmptyMarkMaxDp = 40f
internal const val MimirNorseBookFilledMarkFraction = 0.22f
internal const val MimirNorseBookFilledMarkMaxDp = 32f
internal const val MimirNorseBookMarkCenterYFraction = 0.58f
/** The mark's centre when the cover draws no lockup ([MimirNorseBookCover]'s `showLockup = false`): centred, there is no title to clear. */
internal const val MimirNorseBookLocklessMarkCenterYFraction = 0.5f
internal const val MimirNorseBookPlateFraction = 0.56f
internal const val MimirNorseBookPlateMaxDp = 104f
/** Rotating Tome previews are authored and displayed as 512×512-equivalent square thumbnails. */
internal const val MimirNorseBookPlateAspectRatio = 1f

/**
 * Clears the complete two-line Tome title envelope before the square preview begins.
 *
 * **A fraction of HEIGHT, but calibrated against a WIDTH-anchored thing** -- the title lockup's every
 * term (its `offset`, both `spacedBy` gaps, the eyebrow's size and the title's) scales off `bookWidth`,
 * never `bookHeight`. At the one aspect ratio this number was authored for, 3:4, that distinction is
 * invisible because height IS width/0.75. At any other proportion it is a live bug: see
 * [mimirNorseBookPlateSafeTopDp], which is what every caller must go through.
 */
internal const val MimirNorseBookPlateSafeTopFraction = 0.42f
internal const val MimirNorseBookPlateSafeBottomFraction = 0.82f
internal const val MimirNorseBookSignatureFraction = 0.08f
internal const val MimirNorseBookSignatureMaxDp = 11f
internal const val MimirNorseBookTitleFraction = 0.085f
internal const val MimirNorseBookEyebrowFraction = 0.042f
internal const val MimirNorseBookCompactTitleFraction = 0.078f
internal const val MimirNorseBookCompactEyebrowFraction = 0.038f
internal const val MimirNorseBookCompactPlateSafeTopFraction = 0.34f
internal const val MimirNorseBookCompactPlateFraction = 0.48f
internal const val MimirNorseBookMannazStrokeFraction = 0.030f
internal const val MimirNorseBookMannazStrokeMinDp = 1.25f
internal const val MimirNorseBookMannazStrokeMaxDp = 2f

/** The air a fitted title keeps above the embossed Mannaz or the platform plate's safe band, in dp. */
internal const val MimirNorseBookTitleClearanceDp = 2f

/** The one hyphenation answer a cover's fit is keyed on: none. The cover never breaks a word, so [MimirBookTitleFits] keeps its fits apart from the Bottom book's, which may. */
private val MimirNorseBookNoHyphenation: Any = Unit

/** What [MimirNorseBookCover]'s `fitTitle` decided: the title's fit, the line height the eyebrow above it is set in and the height its box is held to, so the lockup stands where it does at 1x. */
private class MimirNorseBookTitleSetting(val fit: MimirBookTitleFit, val eyebrowLineHeight: TextUnit, val eyebrowHeight: Dp?)

/**
 * Where the embossed Mannaz sits on a cover, in whatever unit the arguments are in (the draw and the fit pass pixels, a test may pass dp): its width, its height and
 * the y of its top edge. The one formula [drawMimirMannaz] draws with and a fitted title is bounded by, so a title can never end below where the mark begins.
 */
internal data class MimirNorseBookMannazBox(val width: Float, val height: Float, val top: Float)

internal fun mimirNorseBookMannazBox(
    bookHeight: Float,
    leafWidth: Float,
    widthFraction: Float,
    maxWidth: Float,
    centerYFraction: Float,
): MimirNorseBookMannazBox {
    val runeWidth = minOf(leafWidth * widthFraction, maxWidth)
    val runeHeight = runeWidth * 1.06f
    return MimirNorseBookMannazBox(
        width = runeWidth,
        height = runeHeight,
        top = bookHeight * centerYFraction - runeHeight / 2f,
    )
}

internal enum class MimirNorseBookSignature {
    WELL_STUD,
    GOLDEN_SUN,
    FORGE_SPARK,
    MOON_CRESCENT,
    DREAD_SEAL,
    VOID_EYE,
    WORLD_TREE,
    SHIELD_BOSS,
    SUMMER_SUN,
}

internal data class MimirNorseBookTreatment(
    val coverTop: Color,
    val coverMiddle: Color,
    val coverBottom: Color,
    val onCover: Color,
    val hardware: Color,
    val counterAccent: Color,
    val runeInk: Color,
    val signature: MimirNorseBookSignature,
)

internal fun mimirNorseBookSignature(id: MimirThemeId): MimirNorseBookSignature = when (id) {
    MimirThemeId.DEFAULT -> MimirNorseBookSignature.WELL_STUD
    MimirThemeId.FREYR -> MimirNorseBookSignature.GOLDEN_SUN
    MimirThemeId.SURTR -> MimirNorseBookSignature.FORGE_SPARK
    MimirThemeId.MANI -> MimirNorseBookSignature.MOON_CRESCENT
    MimirThemeId.HEL -> MimirNorseBookSignature.DREAD_SEAL
    MimirThemeId.NIFLHEIM -> MimirNorseBookSignature.VOID_EYE
    MimirThemeId.YGGDRASIL -> MimirNorseBookSignature.WORLD_TREE
    MimirThemeId.FOLKVANGR -> MimirNorseBookSignature.SHIELD_BOSS
    MimirThemeId.BALDR -> MimirNorseBookSignature.SUMMER_SUN
}

internal fun mimirNorseBookTreatment(palette: MimirThemePalette): MimirNorseBookTreatment {
    val hero = palette.materials.hero
    return MimirNorseBookTreatment(
        coverTop = hero.coverTop,
        coverMiddle = hero.coverMiddle,
        coverBottom = hero.coverBottom,
        onCover = hero.onCover,
        hardware = hero.hardware,
        counterAccent = palette.materials.counterAccent,
        runeInk = palette.runeMark,
        signature = mimirNorseBookSignature(palette.id),
    )
}

private fun mimirNorseBookBrush(book: MimirNorseBookTreatment): Brush = Brush.verticalGradient(
    0.00f to book.coverTop,
    0.52f to book.coverMiddle,
    1.00f to book.coverBottom,
)

/**
 * The shared bound volume used by Home Tomes. Its anatomy is deliberately the companion-display book:
 * asymmetric binding, page block, raised spine bands, clasp, Mannaz emboss, and per-theme signature.
 * [plate] is one optional tipped-in lead-art miniature; it never becomes a folder-style mosaic.
 */
@Composable
fun MimirNorseBookCover(
    focused: Boolean,
    eyebrow: String,
    title: String,
    modifier: Modifier = Modifier,
    platformIdValue: String? = null,
    hasMembers: Boolean = false,
    focusScale: Float = 1f,
    showFocusRing: Boolean = true,
    /** Additive compact face for the Collection grid; default false preserves all callers. */
    compact: Boolean = false,
    /**
     * Cover proportion, width : height. **Additive — defaults to [MimirNorseBookAspectRatio], so every
     * existing caller (both Home tome faces) renders byte-identically.**
     *
     * Added 2026-08-14 for the Files folio, which draws tomes at the design's 1 : 1.14 rather than the
     * shelf's 3 : 4. This is not a taste difference: at 833x468dp the 3:4 tome leaves only ~12dp of
     * vertical slack in the Files grid, so the moment a child-count line lands (~17dp per row) the
     * folio collapses from EIGHT tomes to FOUR. A shorter face is what pays for the count.
     *
     * Parameterised rather than forked — a second cover implementation would drift from this one, and
     * this codebase has already been burned by three copies of one helper.
     */
    aspectRatio: Float = MimirNorseBookAspectRatio,
    /**
     * Whether the cover draws its own eyebrow, rule and title. **Additive — defaults to true, so every
     * existing caller (both Home tome faces and the Files folio) renders byte-identically.**
     *
     * Added for the Companion (Thor Bottom) lattice, whose Tome face is a plain square cover and whose
     * name is the tile's own two-line nameplate under the face: a lockup inside the face would say the
     * name twice. With the lockup off the Mannaz mark is centred in the leaf
     * ([MimirNorseBookLocklessMarkCenterYFraction]) rather than pushed down to clear a title that is not
     * there. A [plate] still assumes the lockup above it; the lockless face is the plateless one.
     *
     * Parameterised rather than forked, for the reason [aspectRatio] gives.
     */
    showLockup: Boolean = true,
    /**
     * Whether the title is set to the room the cover leaves it, instead of at the cover's one size. **Additive -- defaults to false, so every existing caller (both Home tome
     * faces, the Companion lattice, onboarding) renders byte-identically.**
     *
     * Off, a title wider than the leaf is cut by the text engine inside a word ("Audiobook" / "s", alpha-cert sweep 10-09: the stray letter landed on the Mannaz). On, the
     * title comes down from the cover's size to the type scale's floor ([MimirTypeStep.Micro]) until its words all stand whole, in at most the two lines the cover has, and
     * ends above the first thing drawn under it -- the Mannaz, or the platform plate's safe band. A word that is still too wide at the floor ends its line in an ellipsis;
     * a line never ends inside a word ([mimirFitBookTitle]). Used by the Files folio's tomes, whose names are the player's own and arrive at any length.
     */
    fitTitle: Boolean = false,
    /**
     * Whether the cover draws its own embossed Mannaz (a cover with no plate and no platform draws one). **Additive -- defaults to true, so every existing caller renders
     * byte-identically.** Off only while the Tome wears its pin, which is a Mannaz of its own: one ᛗ per book . A change fades over [mannazFadeMs]; the title's
     * room is still counted down to the mark's top, so nothing else on the cover moves with it.
     */
    showMannaz: Boolean = true,
    /** The fade of [showMannaz], in ms: the pin's own clock where the pin is the other half of the crossfade. */
    mannazFadeMs: Int = MimirMotion.FocusMs,
    plate: (@Composable BoxScope.() -> Unit)? = null,
    palette: MimirThemePalette = LocalMimirPalette.current,
) {
    val book = remember(palette) { mimirNorseBookTreatment(palette) }
    val leather = remember(book) { mimirNorseBookBrush(book) }
    val cordAlpha by animateFloatAsState(
        targetValue = if (focused) 0.85f else 0.55f,
        animationSpec = tween(MimirMotion.FocusMs, easing = MimirMotion.Easing),
        label = "mimir-norse-book-cords",
    )
    val focusProgress by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = mimirFocusRingSpec(),
        label = "mimir-norse-book-focus-ring",
    )
    val scale by animateFloatAsState(
        targetValue = if (focused) focusScale else 1f,
        animationSpec = tween(MimirMotion.FocusMs, easing = MimirMotion.Easing),
        label = "mimir-norse-book-scale",
    )
    // The emboss's presence: born at its target, so a cover never toggled never animates. Read in the draw phase only.
    val mannazPresence by animateFloatAsState(
        targetValue = if (showMannaz) 1f else 0f,
        animationSpec = tween(mannazFadeMs, easing = MimirMotion.Easing),
        label = "mimir-norse-book-mannaz",
    )

    BoxWithConstraints(
        modifier = modifier
            .aspectRatio(aspectRatio)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
    ) {
        val widthValue = maxWidth.value.coerceAtLeast(1f)
        val foreCorner = mimirNorseBookCoverCorner(widthValue)
        // Uniform, and equal to the radius mimirFocusTileRing draws below. The spine corners used to be a separate, much squarer 2-5dp while the ring
        // stayed uniform at foreCorner, so on the spine side a near-square cover corner sat outside
        // the ring's curve. Matching the two by construction is what keeps the art inside the ring at every
        // width, since both now derive from the same value.
        //
        // The book still reads as a book: the spine shading and the fore-edge page stack below draw
        // that silhouette. Only the COVER's corner radius is uniform now.
        val shape = RoundedCornerShape(foreCorner)
        val spine = maxWidth * 0.085f
        val foreEdge = maxWidth * 0.085f
        val leafLeft = spine
        val leafWidth = maxWidth - spine - foreEdge
        val bookWidth = maxWidth
        val bookHeight = maxHeight
        // Keep the fallback rune inside the same title-cleared band as a tipped-in plate on the
        // shorter Files face. The default 3:4 path remains at its authored center fraction.
        val markCenterYFraction = if (!showLockup) {
            MimirNorseBookLocklessMarkCenterYFraction
        } else if (aspectRatio == MimirNorseBookAspectRatio) {
            MimirNorseBookMarkCenterYFraction
        } else {
            val safeTopDp = mimirNorseBookPlateSafeTopDp(
                bookWidthDp = bookWidth.value,
                bookHeightDp = bookHeight.value,
                aspectRatio = aspectRatio,
                compact = compact,
            )
            val safeBottomDp = bookHeight.value * MimirNorseBookPlateSafeBottomFraction
            (safeTopDp + safeBottomDp) / (2f * bookHeight.value)
        }
        val titleSize = if (compact) {
            (widthValue * MimirNorseBookCompactTitleFraction).coerceIn(7f, 11f).sp
        } else {
            (widthValue * MimirNorseBookTitleFraction).coerceIn(11f, 18f).sp
        }
        val eyebrowSize = if (compact) {
            (widthValue * MimirNorseBookCompactEyebrowFraction).coerceIn(5f, 8f).sp
        } else {
            (widthValue * MimirNorseBookEyebrowFraction).coerceIn(7f, 10f).sp
        }
        // fitTitle: the title is set to the room the lockup leaves it -- the leaf's width the Column below gives it, and the height from the tooled rule down to the first thing
        // drawn under the title (the Mannaz's top edge, or the plate's safe band) less a little air. The same numbers the Column and the drawing use, so what is measured
        // here is what the Text below is given. Decided once per title and room and remembered ([MimirBookTitleFits]).
        val titleSetting = if (fitTitle && showLockup) {
            val measurer = rememberTextMeasurer()
            val fitDensity = LocalDensity.current
            val fitBase = LocalTextStyle.current
            val hasMannaz = plate == null && platformIdValue == null
            remember(
                title, eyebrow, maxWidth, maxHeight, fitDensity, fitBase, titleSize, eyebrowSize,
                compact, aspectRatio, hasMembers, hasMannaz, plate != null,
            ) {
                with(fitDensity) {
                    val widthPx = (leafWidth * 0.78f).roundToPx().coerceAtLeast(1)
                    val gapPx = (bookWidth * 0.028f).toPx()
                    // The eyebrow inherits the theme's line height (24 sp), which grows with the font scale: at 1.3x the 7 sp label stood in a 31 dp line and pushed the title
                    // down into the Mannaz. Its line box is held at what it is at 1x, in dp, however large the type is set; at 1x this is the same 24 dp it always was.
                    // Converting that 24 dp back to sp is not enough to hold it: the sp-to-dp the text engine lays a line out with is not always the one `toSp` used (the
                    // nonlinear font scaling of API 34 and up), and the label then stood 2.6 dp taller at 1.3x and cost the title the room for its second line (sweep 10-09, Files).
                    // So the height is measured at a font scale of 1 and the label's box is given exactly that.
                    val eyebrowLineHeight = if (fitBase.lineHeight.isSp) fitBase.lineHeight.value.dp.toSp() else TextUnit.Unspecified
                    val eyebrowHeightPx = measurer.measure(
                        text = AnnotatedString(eyebrow.uppercase()),
                        style = fitBase.merge(
                            TextStyle(fontSize = eyebrowSize, fontWeight = FontWeight.SemiBold, letterSpacing = 1.1.sp, lineHeight = fitBase.lineHeight),
                        ),
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1,
                        constraints = Constraints(maxWidth = widthPx),
                        density = Density(fitDensity.density, 1f),
                    ).size.height
                    val titleTopPx = (bookWidth * 0.13f).toPx() + eyebrowHeightPx + gapPx + 1.5.dp.toPx() + gapPx
                    val limitPx = if (hasMannaz) {
                        val spinePx = (bookWidth.toPx() * 0.085f).coerceAtLeast(2.dp.toPx())
                        mimirNorseBookMannazBox(
                            bookHeight = bookHeight.toPx(),
                            leafWidth = (bookWidth.toPx() - spinePx - spinePx).coerceAtLeast(1f),
                            widthFraction = if (hasMembers) MimirNorseBookFilledMarkFraction else MimirNorseBookEmptyMarkFraction,
                            maxWidth = (if (hasMembers) MimirNorseBookFilledMarkMaxDp else MimirNorseBookEmptyMarkMaxDp).dp.toPx(),
                            centerYFraction = markCenterYFraction,
                        ).top
                    } else {
                        mimirNorseBookPlateSafeTopDp(
                            bookWidthDp = bookWidth.value,
                            bookHeightDp = bookHeight.value,
                            aspectRatio = aspectRatio,
                            compact = compact,
                        ).dp.toPx()
                    }
                    val availablePx = (limitPx - MimirNorseBookTitleClearanceDp.dp.toPx() - titleTopPx).coerceAtLeast(1f)
                    val lines = if (compact) 1 else 2
                    // The floor is the type scale's Micro as DRAWN at 1x (9 dp), not 9 sp times the font scale: the room under the lockup is the same dp at any font scale, so at 1.3x a floor of
                    // 11.7 dp left room for one line where 1x has two, and four folders "Cert Artist Alpha / Beta / Gamma / Delta" all read "Cert Artist...". The order is the
                    // same at every scale: shrink to the floor, wrap at the spaces, and only then ellipsize. A font scale under 1 keeps 9 sp.
                    val floorSp = MimirTypeStep.Micro.value / fitDensity.fontScale.coerceAtLeast(1f)
                    val key = MimirBookTitleFitKey(
                        title = title,
                        widthPx = widthPx,
                        availableHeightPx = availablePx,
                        maxSp = titleSize.value,
                        minSp = floorSp,
                        maxLines = lines,
                        density = fitDensity.density,
                        fontScale = fitDensity.fontScale,
                        style = fitBase,
                        locale = java.util.Locale.getDefault().toLanguageTag(),
                        hyphenator = MimirNorseBookNoHyphenation,
                    )
                    MimirNorseBookTitleSetting(
                        fit = MimirBookTitleFits.getOrCompute(key) {
                            mimirFitBookTitle(
                                maxSp = titleSize.value,
                                minSp = floorSp,
                                maxLines = lines,
                                availableHeightPx = availablePx,
                                measure = mimirBookTitleMeasure(measurer, title, fitBase, widthPx, maxLines = lines),
                            )
                        },
                        eyebrowLineHeight = eyebrowLineHeight,
                        eyebrowHeight = if (fitBase.lineHeight.isSp) eyebrowHeightPx.toDp() else null,
                    )
                }
            }
        } else {
            null
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (showFocusRing) {
                        Modifier.mimirFocusTileRing(
                            progress = { focusProgress },
                            color = palette.tileFocusRing,
                            cornerRadius = foreCorner,
                        )
                    } else {
                        Modifier
                    },
                )
                .clip(shape)
                .background(leather)
                .border(0.8.dp, book.hardware.copy(alpha = 0.34f), shape)
                .drawBehind {
                    val spinePx = (size.width * 0.085f).coerceAtLeast(2.dp.toPx())
                    val foreEdgePx = (size.width * 0.085f).coerceAtLeast(2.dp.toPx())
                    val pageInk = lerp(book.onCover, book.coverMiddle, 0.24f)
                    drawRect(
                        color = pageInk.copy(alpha = 0.86f),
                        topLeft = Offset(size.width - foreEdgePx, 2.dp.toPx()),
                        size = Size(foreEdgePx, size.height - 4.dp.toPx()),
                    )
                    repeat(3) { index ->
                        val x = size.width - foreEdgePx + foreEdgePx * (index + 1f) / 4f
                        drawLine(
                            book.counterAccent.copy(alpha = 0.22f),
                            Offset(x, 3.dp.toPx()),
                            Offset(x, size.height - 3.dp.toPx()),
                            0.45.dp.toPx(),
                        )
                    }
                    drawRect(
                        color = book.hardware.copy(alpha = 0.34f),
                        size = Size(spinePx, size.height),
                    )
                    drawLine(
                        book.coverBottom.copy(alpha = 0.34f),
                        Offset(spinePx + 1.dp.toPx(), 0f),
                        Offset(spinePx + 1.dp.toPx(), size.height),
                        1.dp.toPx(),
                    )
                    listOf(0.28f, 0.72f).forEach { fraction ->
                        val y = size.height * fraction
                        drawLine(
                            lerp(book.hardware, book.coverBottom, 0.55f),
                            Offset(0f, y + 0.7.dp.toPx()),
                            Offset(spinePx, y + 0.7.dp.toPx()),
                            1.3.dp.toPx(),
                        )
                        drawLine(
                            book.hardware.copy(alpha = cordAlpha),
                            Offset(0f, y),
                            Offset(spinePx, y),
                            1.3.dp.toPx(),
                        )
                        drawLine(
                            lerp(book.hardware, book.onCover, 0.64f),
                            Offset(0f, y - 0.5.dp.toPx()),
                            Offset(spinePx, y - 0.5.dp.toPx()),
                            0.55.dp.toPx(),
                        )
                    }
                    val claspHeight = size.height * 0.14f
                    val claspWidth = (foreEdgePx * 0.72f).coerceAtLeast(1.5.dp.toPx())
                    drawRect(
                        color = book.hardware.copy(alpha = 0.84f),
                        topLeft = Offset(size.width - claspWidth, (size.height - claspHeight) / 2f),
                        size = Size(claspWidth, claspHeight),
                    )

                    when {
                        platformIdValue != null -> Unit
                        plate == null -> {
                            val presence = mannazPresence
                            if (presence > 0f) {
                                drawMimirMannaz(
                                    book = book,
                                    spine = spinePx,
                                    foreEdge = foreEdgePx,
                                    widthFraction = if (hasMembers) {
                                        MimirNorseBookFilledMarkFraction
                                    } else {
                                        MimirNorseBookEmptyMarkFraction
                                    },
                                    maxWidthPx = (if (hasMembers) {
                                        MimirNorseBookFilledMarkMaxDp
                                    } else {
                                        MimirNorseBookEmptyMarkMaxDp
                                    }).dp.toPx(),
                                    centerYFraction = markCenterYFraction,
                                    alpha = 0.94f * presence,
                                )
                            }
                        }
                        else -> Unit
                    }
                    drawMimirNorseBookSignature(book.signature, book, spinePx, foreEdgePx)
                }
        ) {
            if (showLockup) {
                Column(
                    modifier = Modifier
                        .offset(x = bookWidth * 0.135f, y = bookWidth * 0.13f)
                        .width(leafWidth * 0.78f),
                    verticalArrangement = Arrangement.spacedBy(bookWidth * 0.028f),
                ) {
                    Text(
                        text = eyebrow.uppercase(),
                        fontSize = eyebrowSize,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.1.sp,
                        lineHeight = titleSetting?.eyebrowLineHeight ?: TextUnit.Unspecified,
                        color = book.runeInk.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = titleSetting?.eyebrowHeight?.let { Modifier.height(it) } ?: Modifier,
                    )
                    Box(
                        Modifier
                            .size(width = bookWidth * 0.26f, height = 1.5.dp)
                            .background(book.runeInk.copy(alpha = 0.5f)),
                    )
                    val titleFit = titleSetting?.fit
                    val shownTitleSize = titleFit?.sizeSp?.sp ?: titleSize
                    Text(
                        text = title,
                        fontSize = shownTitleSize,
                        lineHeight = (shownTitleSize.value * 1.08f).sp,
                        fontWeight = FontWeight.Bold,
                        color = book.onCover,
                        maxLines = titleFit?.maxLines ?: if (compact) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (plate != null) {
                val desiredPlateWidth = minOf(
                    leafWidth * (if (compact) MimirNorseBookCompactPlateFraction else MimirNorseBookPlateFraction),
                    (if (compact) 56f else MimirNorseBookPlateMaxDp).dp,
                )
                // UI-04: width-anchored, because the title lockup it has to clear is. See
                // mimirNorseBookPlateSafeTopDp -- at the default 3:4 this evaluates the identical
                // expression on the identical measured height, so Home does not move.
                val safeTop = mimirNorseBookPlateSafeTopDp(
                    bookWidthDp = bookWidth.value,
                    bookHeightDp = bookHeight.value,
                    aspectRatio = aspectRatio,
                    compact = compact,
                ).dp
                val safeBottom = bookHeight * MimirNorseBookPlateSafeBottomFraction
                val safeHeight = (safeBottom - safeTop).coerceAtLeast(1.dp)
                val plateWidth = minOf(
                    desiredPlateWidth,
                    safeHeight * MimirNorseBookPlateAspectRatio,
                )
                val plateHeight = plateWidth / MimirNorseBookPlateAspectRatio
                Box(
                    modifier = Modifier
                        .offset(
                            x = leafLeft + (leafWidth - plateWidth) / 2f,
                            y = safeTop + (safeHeight - plateHeight) / 2f,
                        )
                        .width(plateWidth)
                        .aspectRatio(MimirNorseBookPlateAspectRatio),
                    contentAlignment = Alignment.Center,
                    content = plate,
                )
            }
        }
    }
}

/**
 * The companion open-book frame, lifted into core-ui without changing the companion caller. [spine] draws the line down the middle where the two pages meet; a surface that is one page of
 * rows (such as the Library search overlay, where a centre line would cross the result rows) passes false.
 */
fun Modifier.mimirNorseBookFrame(palette: MimirThemePalette, spine: Boolean = true): Modifier {
    val book = mimirNorseBookTreatment(palette)
    return this
        .clip(RoundedCornerShape(18.dp))
        .background(mimirNorseBookBrush(book))
        .border(1.dp, book.hardware.copy(alpha = 0.54f), RoundedCornerShape(18.dp))
        .drawBehind {
            val inset = 7.dp.toPx()
            drawRoundRect(
                color = book.onCover.copy(alpha = 0.08f),
                topLeft = Offset(inset, inset),
                size = Size(size.width - inset * 2f, size.height - inset * 2f),
                cornerRadius = CornerRadius(13.dp.toPx()),
                style = Stroke(0.8.dp.toPx()),
            )
            if (spine) {
                drawLine(
                    color = book.counterAccent.copy(alpha = 0.16f),
                    start = Offset(size.width / 2f, 10.dp.toPx()),
                    end = Offset(size.width / 2f, size.height - 10.dp.toPx()),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            drawMimirCornerBrackets(
                accent = book.hardware,
                insetPx = 12.dp.toPx(),
                armPx = 16.dp.toPx(),
                widthPx = 1.4.dp.toPx(),
            )
        }
}

/** The companion page-position language: one accent diamond and muted-text siblings. */
@Composable
fun MimirPageRunes(
    current: Int,
    count: Int,
    modifier: Modifier = Modifier,
    palette: MimirThemePalette = LocalMimirPalette.current,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(count.coerceAtLeast(1)) { index ->
            // The resting dots are the palette's muted text colour at full strength: the old outline at 0.6 alpha was too faint against the book.
            val color = if (index == current) palette.accent else palette.textMuted
            Box(
                Modifier
                    .size(7.dp)
                    .graphicsLayer { rotationZ = 45f }
                    .background(color),
            )
        }
    }
}

/**
 * The four corner brackets a book cover wears: an L of [armPx] at each corner, [insetPx] in from the edge, in [accent] at 0.62 alpha.
 * Public so a surface in another module (the Companion Bottom) can ornament its own panel with the same brackets.
 */
fun DrawScope.drawMimirCornerBrackets(
    accent: Color,
    insetPx: Float,
    armPx: Float,
    widthPx: Float,
) {
    val color = accent.copy(alpha = 0.62f)
    drawLine(color, Offset(insetPx, insetPx), Offset(insetPx + armPx, insetPx), widthPx)
    drawLine(color, Offset(insetPx, insetPx), Offset(insetPx, insetPx + armPx), widthPx)
    drawLine(color, Offset(size.width - insetPx, insetPx), Offset(size.width - insetPx - armPx, insetPx), widthPx)
    drawLine(color, Offset(size.width - insetPx, insetPx), Offset(size.width - insetPx, insetPx + armPx), widthPx)
    drawLine(color, Offset(insetPx, size.height - insetPx), Offset(insetPx + armPx, size.height - insetPx), widthPx)
    drawLine(color, Offset(insetPx, size.height - insetPx), Offset(insetPx, size.height - insetPx - armPx), widthPx)
    drawLine(color, Offset(size.width - insetPx, size.height - insetPx), Offset(size.width - insetPx - armPx, size.height - insetPx), widthPx)
    drawLine(color, Offset(size.width - insetPx, size.height - insetPx), Offset(size.width - insetPx, size.height - insetPx - armPx), widthPx)
}

private fun DrawScope.drawMimirMannaz(
    book: MimirNorseBookTreatment,
    spine: Float,
    foreEdge: Float,
    widthFraction: Float,
    maxWidthPx: Float,
    centerYFraction: Float,
    alpha: Float,
) {
    val leafWidth = (size.width - spine - foreEdge).coerceAtLeast(1f)
    val box = mimirNorseBookMannazBox(
        bookHeight = size.height,
        leafWidth = leafWidth,
        widthFraction = widthFraction,
        maxWidth = maxWidthPx,
        centerYFraction = centerYFraction,
    )
    val runeWidth = box.width
    val runeHeight = box.height
    val centerX = spine + leafWidth / 2f
    val centerY = size.height * centerYFraction
    val left = centerX - runeWidth / 2f
    val right = centerX + runeWidth / 2f
    val top = box.top
    val bottom = centerY + runeHeight / 2f
    val strokeWidth = mimirNorseBookMannazStrokePx(
        leafWidthPx = leafWidth,
        minimumStrokePx = MimirNorseBookMannazStrokeMinDp.dp.toPx(),
        maximumStrokePx = MimirNorseBookMannazStrokeMaxDp.dp.toPx(),
    )
    val color = book.runeInk.copy(alpha = alpha)
    drawLine(color, Offset(left, top), Offset(left, bottom), strokeWidth, cap = StrokeCap.Round)
    drawLine(color, Offset(right, top), Offset(right, bottom), strokeWidth, cap = StrokeCap.Round)
    drawLine(color, Offset(left, top), Offset(right, top + runeHeight * 0.30f), strokeWidth, cap = StrokeCap.Round)
    drawLine(color, Offset(right, top), Offset(left, top + runeHeight * 0.30f), strokeWidth, cap = StrokeCap.Round)
}

internal fun mimirNorseBookMannazStrokePx(
    leafWidthPx: Float,
    minimumStrokePx: Float,
    maximumStrokePx: Float,
): Float {
    val minimum = minimumStrokePx.coerceAtLeast(0f)
    val maximum = maximumStrokePx.coerceAtLeast(minimum)
    return (leafWidthPx.coerceAtLeast(0f) * MimirNorseBookMannazStrokeFraction)
        .coerceIn(minimum, maximum)
}

private fun DrawScope.drawMimirNorseBookSignature(
    signature: MimirNorseBookSignature,
    book: MimirNorseBookTreatment,
    spine: Float,
    foreEdge: Float,
) {
    val leafWidth = (size.width - spine - foreEdge).coerceAtLeast(1f)
    val radius = minOf(
        leafWidth * MimirNorseBookSignatureFraction,
        MimirNorseBookSignatureMaxDp.dp.toPx(),
    )
    val centerX = size.width - foreEdge - radius - leafWidth * 0.08f
    val centerY = size.height - radius - leafWidth * 0.08f
    val metal = book.counterAccent
    val highlight = lerp(metal, book.onCover, 0.72f)
    val shadow = lerp(metal, book.coverBottom, 0.62f)
    val stroke = (leafWidth * 0.027f).coerceAtLeast(0.8.dp.toPx())
    val bevel = (stroke * 0.48f).coerceAtLeast(0.35.dp.toPx())
    val offset = (stroke * 0.55f).coerceAtLeast(0.45.dp.toPx())

    fun metalLine(x0: Float, y0: Float, x1: Float, y1: Float) {
        drawLine(shadow.copy(alpha = 0.76f), Offset(x0 + offset, y0 + offset), Offset(x1 + offset, y1 + offset), stroke)
        drawLine(metal.copy(alpha = 0.96f), Offset(x0, y0), Offset(x1, y1), stroke)
        drawLine(highlight.copy(alpha = 0.92f), Offset(x0 - offset * 0.45f, y0 - offset * 0.45f), Offset(x1 - offset * 0.45f, y1 - offset * 0.45f), bevel)
    }
    fun metalRing(x: Float, y: Float, r: Float) {
        drawCircle(shadow.copy(alpha = 0.76f), r, Offset(x + offset, y + offset), style = Stroke(stroke))
        drawCircle(metal.copy(alpha = 0.96f), r, Offset(x, y), style = Stroke(stroke))
        drawCircle(highlight.copy(alpha = 0.90f), r, Offset(x - offset * 0.45f, y - offset * 0.45f), style = Stroke(bevel))
    }
    fun metalDisc(x: Float, y: Float, r: Float) {
        drawCircle(shadow.copy(alpha = 0.76f), r + offset, Offset(x + offset * 0.6f, y + offset * 0.6f))
        drawCircle(
            brush = Brush.linearGradient(
                listOf(highlight, metal, shadow),
                start = Offset(x - r, y - r),
                end = Offset(x + r, y + r),
            ),
            radius = r,
            center = Offset(x, y),
        )
    }
    fun metalArc(x: Float, y: Float, r: Float, start: Float, sweep: Float) {
        val arcSize = Size(r * 2f, r * 2f)
        drawArc(shadow.copy(alpha = 0.76f), start, sweep, false, Offset(x - r + offset, y - r + offset), arcSize, style = Stroke(stroke))
        drawArc(metal.copy(alpha = 0.96f), start, sweep, false, Offset(x - r, y - r), arcSize, style = Stroke(stroke))
    }
    fun spokes(count: Int, inner: Float, outer: Float) {
        repeat(count) { index ->
            val angle = Math.toRadians(index * (360.0 / count))
            val dx = kotlin.math.cos(angle).toFloat()
            val dy = kotlin.math.sin(angle).toFloat()
            metalLine(centerX + dx * inner, centerY + dy * inner, centerX + dx * outer, centerY + dy * outer)
        }
    }

    when (signature) {
        MimirNorseBookSignature.WELL_STUD -> {
            metalLine(centerX, centerY - radius, centerX + radius * 0.7f, centerY)
            metalLine(centerX + radius * 0.7f, centerY, centerX, centerY + radius)
            metalLine(centerX, centerY + radius, centerX - radius * 0.7f, centerY)
            metalLine(centerX - radius * 0.7f, centerY, centerX, centerY - radius)
        }
        MimirNorseBookSignature.GOLDEN_SUN -> {
            spokes(8, radius * 0.38f, radius)
            metalRing(centerX, centerY, radius * 0.30f)
        }
        MimirNorseBookSignature.FORGE_SPARK -> {
            metalLine(centerX, centerY + radius, centerX + radius * 0.25f, centerY)
            metalLine(centerX + radius * 0.25f, centerY, centerX - radius * 0.18f, centerY - radius * 0.56f)
            metalLine(centerX - radius * 0.18f, centerY - radius * 0.56f, centerX + radius * 0.15f, centerY - radius)
        }
        MimirNorseBookSignature.MOON_CRESCENT -> {
            metalArc(centerX, centerY, radius, 40f, 280f)
            metalArc(centerX + radius * 0.42f, centerY, radius * 0.80f, 72f, 216f)
        }
        MimirNorseBookSignature.DREAD_SEAL -> {
            metalDisc(centerX, centerY, radius * 0.82f)
            metalLine(centerX, centerY - radius * 0.48f, centerX, centerY + radius * 0.48f)
            metalLine(centerX - radius * 0.38f, centerY - radius * 0.20f, centerX + radius * 0.38f, centerY + radius * 0.20f)
        }
        MimirNorseBookSignature.VOID_EYE -> {
            drawCircle(book.coverBottom, radius * 0.54f, Offset(centerX, centerY))
            metalRing(centerX, centerY, radius * 0.92f)
        }
        MimirNorseBookSignature.WORLD_TREE -> {
            metalLine(centerX, centerY + radius, centerX, centerY - radius)
            listOf(0.48f, 0f, -0.48f).forEach { branch ->
                metalLine(centerX, centerY + radius * branch, centerX + radius * 0.58f, centerY + radius * branch - radius * 0.40f)
                metalLine(centerX, centerY + radius * branch, centerX - radius * 0.58f, centerY + radius * branch - radius * 0.40f)
            }
        }
        MimirNorseBookSignature.SHIELD_BOSS -> {
            metalRing(centerX, centerY, radius)
            metalRing(centerX, centerY, radius * 0.55f)
            spokes(6, radius * 0.58f, radius)
        }
        MimirNorseBookSignature.SUMMER_SUN -> {
            spokes(10, radius * 0.62f, radius * 1.04f)
            metalDisc(centerX, centerY, radius * 0.52f)
        }
    }
}
