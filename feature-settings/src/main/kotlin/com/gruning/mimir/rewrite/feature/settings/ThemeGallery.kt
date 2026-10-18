package com.gruning.mimir.rewrite.feature.settings

import android.content.res.Resources
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.input.isLitFocusIn
import com.gruning.mimir.rewrite.core.input.mimirActivatable
import com.gruning.mimir.rewrite.core.input.mimirFocusable
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.MimirControlHintText
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.mimirGestureHandleClearance
import com.gruning.mimir.rewrite.core.ui.MimirShelfBackground
import com.gruning.mimir.rewrite.core.ui.MimirShelfTexture
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import com.gruning.mimir.rewrite.core.ui.MimirRingShape
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import com.gruning.mimir.rewrite.core.ui.mimirSelectionRing
import com.gruning.mimir.rewrite.core.ui.MimirTypeStep
import com.gruning.mimir.rewrite.core.ui.theme.MimirType

/**
 * MIMIR-REWRITE — THE theme picker: a full-screen 3×3 wall of nine live book previews, each painted ENTIRELY
 * in its OWN [MimirThemePalette]. Every preview combines the authored scene texture, dark hero-cover volume,
 * invariant Mannaz book mark, per-theme corner signature, counter-hue, label, and mood tag. All nine identities
 * can be compared side-by-side without collapsing into recolored generic cards. Shared verbatim by first-run
 * onboarding and Settings › Appearance so the two never diverge.
 *
 * FOCUS/POSTURE: the caller pushes [scope] before opening and pops it on close; the cards register nine keys in
 * [scope] via [mimirFocusable] (never Compose `focusable()`); [dispatchThemeGallery] does geometric 2D
 * `moveFocus`. No `setTheme` on focus — committing is the only mutation, so there is nothing to revert on Back.
 * Colours read from each card's TARGET palette (not [LocalMimirPalette]); the header/footer read the live one.
 */

private const val ThemeGalleryColumns = 3
private const val ThemeGalleryRows = 3
internal const val ThemePreviewBookAspectRatio = 3f / 4f
internal const val ThemeGalleryTouchHint = "Touch a theme to select it."

internal fun themeGalleryControllerHint(res: Resources, dismissHint: String): String =
    res.getString(R.string.settings_theme_gallery_footer, dismissHint)

internal fun themeGalleryStackedTextReserveDp(fontScale: Float): Float =
    19f + 12f * fontScale.coerceAtLeast(1f)

internal enum class ThemePreviewBookSignature {
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

internal data class ThemePreviewBookTreatment(
    val coverTop: Color,
    val coverMiddle: Color,
    val coverBottom: Color,
    val onCover: Color,
    val hardware: Color,
    val counterAccent: Color,
    val runeInk: Color,
    val texture: MimirShelfTexture,
    val signature: ThemePreviewBookSignature,
)

internal fun themePreviewBookSignature(id: MimirThemeId): ThemePreviewBookSignature = when (id) {
    MimirThemeId.DEFAULT -> ThemePreviewBookSignature.WELL_STUD
    MimirThemeId.FREYR -> ThemePreviewBookSignature.GOLDEN_SUN
    MimirThemeId.SURTR -> ThemePreviewBookSignature.FORGE_SPARK
    MimirThemeId.MANI -> ThemePreviewBookSignature.MOON_CRESCENT
    MimirThemeId.HEL -> ThemePreviewBookSignature.DREAD_SEAL
    MimirThemeId.NIFLHEIM -> ThemePreviewBookSignature.VOID_EYE
    MimirThemeId.YGGDRASIL -> ThemePreviewBookSignature.WORLD_TREE
    MimirThemeId.FOLKVANGR -> ThemePreviewBookSignature.SHIELD_BOSS
    MimirThemeId.BALDR -> ThemePreviewBookSignature.SUMMER_SUN
}

internal fun themePreviewBookTreatment(palette: MimirThemePalette): ThemePreviewBookTreatment {
    val hero = palette.materials.hero
    return ThemePreviewBookTreatment(
        coverTop = hero.coverTop,
        coverMiddle = hero.coverMiddle,
        coverBottom = hero.coverBottom,
        onCover = hero.onCover,
        hardware = hero.hardware,
        counterAccent = palette.materials.counterAccent,
        runeInk = palette.runeMark,
        texture = palette.materials.scene.texture,
        signature = themePreviewBookSignature(palette.id),
    )
}

internal data class ThemeGalleryChromeTreatment(
    val primary: Color,
    val secondary: Color,
)

/** Palette-authored ink for the shared dark gallery scrim, including when the active app theme is light. */
internal fun themeGalleryChromeTreatment(palette: MimirThemePalette): ThemeGalleryChromeTreatment =
    ThemeGalleryChromeTreatment(
        primary = palette.materials.onScrimPrimary,
        secondary = palette.materials.onScrimSecondary,
    )

internal data class ThemeGalleryGeometry(
    val horizontalPaddingDp: Float,
    val verticalPaddingDp: Float,
    val sectionGapDp: Float,
    val gridGapDp: Float,
    val headerHeightDp: Float,
    val footerHeightDp: Float,
    val gridHeightDp: Float,
    val cardWidthDp: Float,
    val cardHeightDp: Float,
    val bookWidthDp: Float,
    val bookHeightDp: Float,
    val textColumnWidthDp: Float,
    val stackedCards: Boolean,
) {
    val occupiedHeightDp: Float
        get() = verticalPaddingDp * 2f + headerHeightDp + footerHeightDp +
            sectionGapDp * 2f + gridHeightDp
}

/** Fixed 3x3 solver. The type reservations are in scaled dp; typography itself never tracks viewport width. */
internal fun solveThemeGalleryGeometry(
    viewportWidthDp: Float,
    viewportHeightDp: Float,
    fontScale: Float,
): ThemeGalleryGeometry {
    val width = viewportWidthDp.coerceAtLeast(1f)
    val height = viewportHeightDp.coerceAtLeast(1f)
    val scale = fontScale.coerceAtLeast(1f)
    val horizontalPadding = 16f
    val verticalPadding = 10f
    val sectionGap = 8f
    val gridGap = 8f
    // 24sp title line + up to two 14sp subtitle lines, plus two dp of measurement slack.
    val headerHeight = 52f * scale + 2f
    val footerHeight = 14f * scale + 2f
    val contentWidth = (width - horizontalPadding * 2f).coerceAtLeast(1f)
    val cardWidth = ((contentWidth - gridGap * (ThemeGalleryColumns - 1)) / ThemeGalleryColumns)
        .coerceAtLeast(1f)
    val gridHeight = (
        height - verticalPadding * 2f - headerHeight - footerHeight - sectionGap * 2f
    ).coerceAtLeast(1f)
    val cardHeight = ((gridHeight - gridGap * (ThemeGalleryRows - 1)) / ThemeGalleryRows)
        .coerceAtLeast(1f)
    val stackedCards = cardWidth < 132f
    val bookHeight = if (stackedCards) {
        (cardHeight - themeGalleryStackedTextReserveDp(scale)).coerceIn(28f, 48f)
    } else {
        (cardHeight - 16f).coerceIn(36f, 72f)
    }
    val bookWidth = bookHeight * ThemePreviewBookAspectRatio
    val textWidth = if (stackedCards) {
        (cardWidth - 16f).coerceAtLeast(1f)
    } else {
        (cardWidth - 16f - 8f - bookWidth).coerceAtLeast(1f)
    }
    return ThemeGalleryGeometry(
        horizontalPaddingDp = horizontalPadding,
        verticalPaddingDp = verticalPadding,
        sectionGapDp = sectionGap,
        gridGapDp = gridGap,
        headerHeightDp = headerHeight,
        footerHeightDp = footerHeight,
        gridHeightDp = gridHeight,
        cardWidthDp = cardWidth,
        cardHeightDp = cardHeight,
        bookWidthDp = bookWidth,
        bookHeightDp = bookHeight,
        textColumnWidthDp = textWidth,
        stackedCards = stackedCards,
    )
}

/** Scope-qualified per-theme focus key (stable across recomposition; unique per host scope). */
private fun themeGalleryKey(scope: FocusScope, id: MimirThemeId): FocusKey =
    FocusKey("${scope.value}/theme/${id.storageKey}")

private fun themeGalleryThemeForKey(scope: FocusScope, key: FocusKey?): MimirThemeId? =
    MimirThemeId.entries.firstOrNull { themeGalleryKey(scope, it) == key }

/** Compact material pair: the card says what the rendered scene must visibly deliver. */
internal fun themeMood(res: Resources, id: MimirThemeId): String = res.getString(
    when (id) {
        MimirThemeId.DEFAULT -> R.string.settings_theme_gallery_mood_default
        MimirThemeId.FREYR -> R.string.settings_theme_gallery_mood_freyr
        MimirThemeId.SURTR -> R.string.settings_theme_gallery_mood_surtr
        MimirThemeId.MANI -> R.string.settings_theme_gallery_mood_mani
        MimirThemeId.HEL -> R.string.settings_theme_gallery_mood_hel
        MimirThemeId.NIFLHEIM -> R.string.settings_theme_gallery_mood_niflheim
        MimirThemeId.YGGDRASIL -> R.string.settings_theme_gallery_mood_yggdrasil
        MimirThemeId.FOLKVANGR -> R.string.settings_theme_gallery_mood_folkvangr
        MimirThemeId.BALDR -> R.string.settings_theme_gallery_mood_baldr
    },
)

/**
 * The full-screen gallery. Renders INLINE over a dim scrim (never a Compose `Dialog`); tapping a card selects
 * it, tapping the gutter or Back dismisses. Lands focus on the current theme's card.
 *
 * @param scope the host's already-pushed focus scope (onboarding step scope, or the Settings picker scope).
 * @param onPick a theme was chosen (touch or gamepad) — the host applies + closes.
 * @param onDismiss Back/scrim without a change — the host keeps the current theme + closes.
 * @param presenceManagedExternally true when an app/feature-owned pixel handoff already owns the modal's
 *   alpha timeline (the Settings › Appearance overlay, behind the shared app-shell pixel-fade host) — this
 *   composable must not layer a second, independently-scheduled fade on top of that one. Onboarding passes
 *   true as well since walk-32d (2026-09-30): its THEME step glides the gallery in and out on the step clocks.
 */
@Composable
fun ThemePreviewGallery(
    authority: FocusAuthority,
    scope: FocusScope,
    currentThemeId: MimirThemeId,
    title: String,
    subtitle: String,
    dismissHint: String,
    onPick: (MimirThemeId) -> Unit,
    onDismiss: () -> Unit,
    presenceManagedExternally: Boolean = false,
) {
    val headerPalette = LocalMimirPalette.current // header/footer read the CURRENT theme; cards read their own
    val chrome = themeGalleryChromeTreatment(headerPalette)
    // This full-screen picker sits above Settings' own controller rail. Stack the authored scrim with itself
    // so the underlying hints cannot visually merge with the gallery footer; cards and chrome remain unchanged.
    // The gallery uses an opaque background for bold, isolated theme presentation.
    // Two passes of a translucent scrim still let Settings' own rows ghost through behind the cards,
    // and a theme gallery that shows another screen through it cannot be
    // used to judge a theme, because every card is being read against the wrong ground.
    val galleryScrim = headerPalette.scrim
        .compositeOver(headerPalette.scrim)
        .compositeOver(headerPalette.surfaceBase)
    val focus by authority.state.collectAsState()
    val density = LocalDensity.current

    // Land on the current theme's card once the cards register (retry — requestFocus no-ops until bounds exist).
    LaunchedEffect(Unit) { authority.landGalleryFocus(themeGalleryKey(scope, currentThemeId)) }
    BackHandler(enabled = true, onBack = onDismiss)

    // Open-fade (theme-critique F15): the gallery pops in while every other picker eases — a compositor-only
    // fade on the house OverlayMs/Easing since motion audit batch F (2026-09-28; it was a literal 170 ms on
    // Compose's default curve), so all modals share one entrance. Policy
    // 2026-08-12 (b99a7e27 partly reverted): kept the presenceManagedExternally suppression below (the real B-7 double-fade fix —
    // this composable must not layer a second fade under the app-shell's own pixel-fade host) but
    // reverted the RETIMING of onboarding's own remaining fade — 170ms/Compose-default-easing was a
    // silent perceived-motion change (duration AND curve family) on a surface OUTSIDE the double-fade
    // finding that motivated this commit, made without device visual review.
    // When the app-shell overlay host already owns the entrance (presenceManagedExternally), this stays at
    // full alpha from the first frame — a second, independently-scheduled fade over the SAME visual is
    // exactly that flicker shape, not an entrance worth having twice.
    val appear = remember(presenceManagedExternally) {
        Animatable(if (presenceManagedExternally) 1f else 0f)
    }
    LaunchedEffect(presenceManagedExternally) {
        if (!presenceManagedExternally) {
            appear.animateTo(1f, tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing))
        }
    }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = appear.value }
            .background(galleryScrim)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
    ) {
        // Thor drive 3: the system's gesture handle is drawn over the bottom strip, so the wall and its footer are laid out above it (0 where there is no handle).
        val gestureClearance = mimirGestureHandleClearance()
        val geometry = remember(maxWidth, maxHeight, gestureClearance, density.fontScale) {
            solveThemeGalleryGeometry(
                viewportWidthDp = maxWidth.value,
                viewportHeightDp = maxHeight.value - gestureClearance.value,
                fontScale = density.fontScale,
            )
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(bottom = gestureClearance)
                .padding(
                    horizontal = geometry.horizontalPaddingDp.dp,
                    vertical = geometry.verticalPaddingDp.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(geometry.sectionGapDp.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().height(geometry.headerHeightDp.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                MimirChromeText(
                    text = title,
                    style = MimirType.RunicEyebrow,
                    color = chrome.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MimirType.RowSupport.copy(fontSize = MimirTypeStep.Label, lineHeight = 14.sp),
                    color = chrome.secondary,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Column(
                Modifier.fillMaxWidth().height(geometry.gridHeightDp.dp),
                verticalArrangement = Arrangement.spacedBy(geometry.gridGapDp.dp),
            ) {
                MimirThemePalette.all.chunked(3).forEach { rowThemes ->
                    Row(
                        Modifier.fillMaxWidth().height(geometry.cardHeightDp.dp),
                        horizontalArrangement = Arrangement.spacedBy(geometry.gridGapDp.dp),
                    ) {
                        rowThemes.forEach { palette ->
                            val key = themeGalleryKey(scope, palette.id)
                            ThemePreviewCard(
                                palette = palette,
                                isCurrent = palette.id == currentThemeId,
                                focused = authority.isLitFocusIn(scope, key, focus),
                                authority = authority,
                                focusKey = key,
                                scope = scope,
                                onActivate = { onPick(palette.id) },
                                bookHeightDp = geometry.bookHeightDp,
                                stacked = geometry.stackedCards,
                                modifier = Modifier.width(geometry.cardWidthDp.dp).fillMaxHeight(),
                            )
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().height(geometry.footerHeightDp.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MimirControlHintText(
                    themeGalleryControllerHint(LocalContext.current.resources, dismissHint),
                    style = if (geometry.stackedCards) MimirType.ControlHintCompact else MimirType.ControlHint,
                    color = chrome.secondary,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Route one normalized [ControllerAction] to an open gallery (hard modal — swallows everything). 2D D-pad nav
 * across the card wall; CONFIRM picks the focused theme; CANCEL/Back dismiss.
 */
fun dispatchThemeGallery(
    authority: FocusAuthority,
    scope: FocusScope,
    action: ControllerAction,
    onPick: (MimirThemeId) -> Unit,
    onDismiss: () -> Unit,
): Boolean {
    when (action) {
        ControllerAction.NAV_UP, ControllerAction.NAV_DOWN,
        ControllerAction.NAV_LEFT, ControllerAction.NAV_RIGHT -> authority.moveFocus(action)
        ControllerAction.CONFIRM ->
            themeGalleryThemeForKey(scope, authority.state.value.focused)?.let(onPick)
        ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK -> onDismiss()
        else -> Unit // hard modal: swallow everything else
    }
    return true
}

/** requestFocus is a no-op until the key registers its bounds; retry across a few frames until it lands. */
private suspend fun FocusAuthority.landGalleryFocus(key: FocusKey) {
    repeat(8) {
        withFrameNanos { }
        requestFocus(key)
        if (state.value.focused == key) return
    }
}

/**
 * One theme preview card — a faithful miniature rendered ENTIRELY in [palette] (never the app's current
 * theme). Focus = the one selection ring, in [palette], on the ring clock; no scale (ring migration, 2026-09-28).
 */
@Composable
private fun ThemePreviewCard(
    palette: MimirThemePalette,
    isCurrent: Boolean,
    focused: Boolean,
    authority: FocusAuthority,
    focusKey: FocusKey,
    scope: FocusScope,
    onActivate: () -> Unit,
    bookHeightDp: Float,
    stacked: Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(ThemePreviewCardCorner)
    val book = remember(palette) { themePreviewBookTreatment(palette) }
    Box(
        modifier
            .mimirFocusable(authority, focusKey, scope)
            // The one ring (ring migration, 2026-09-28), in [palette] -- the PREVIEWED theme's palette, so each card
            // wears its own theme's ring -- on the card's own corner, with the rest de-emphasis this gallery has
            // always carried. No whisper scale: the ring is sized to the card, and a scaled card grows past its ring.
            .mimirSelectionRing(
                focused = focused,
                identity = focusKey,
                shape = MimirRingShape.Card(ThemePreviewCardCorner),
                restingAlpha = ThemePreviewCardRestingAlpha,
                palette = palette,
            )
            .clip(shape)
            // One activation path for touch and the accessibility click, and no second focus node beside the
            // authority's: `clickable` planted one here (the ratchet's ThemeGallery count falls to nothing).
            .mimirActivatable(label = palette.id.label, authority = authority, key = focusKey, onActivate = onActivate)
            .padding(8.dp),
    ) {
        // WYSIWYG: the preview now runs the same field, texture, scene, and restrained crest renderer as the
        // real shelf instead of a parallel approximation that hid the strongest identity geometry.
        MimirShelfBackground(
            modifier = Modifier.fillMaxSize(),
            palette = palette,
        ) {}
        if (stacked) {
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                ThemeIdentityBook(
                    book = book,
                    modifier = Modifier
                        .height(bookHeightDp.dp)
                        .aspectRatio(ThemePreviewBookAspectRatio, matchHeightConstraintsFirst = true),
                )
                Spacer(Modifier.height(3.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = palette.id.label,
                        // QA-22, 10sp is deliberately off the five-step scale
                          // (Micro 9 / Label 11) and is EXEMPT, not owed. This label is laid out against the
                          // card's artwork at a fixed aspect, not read as a row. See MimirTypeStep's KDoc,
                          // which carries the exemption beside PerformanceOverlayPresenter's.
                          style = MimirType.RowTitle.copy(fontSize = 10.sp, lineHeight = 12.sp),
                        color = palette.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isCurrent) {
                        Text(
                            text = "  ✓",
                            color = palette.materials.readableAccent,
                            fontSize = MimirTypeStep.Micro,
                            lineHeight = 10.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        } else {
            Row(
                Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ThemeIdentityBook(
                    book = book,
                    modifier = Modifier
                        .height(bookHeightDp.dp)
                        .aspectRatio(ThemePreviewBookAspectRatio, matchHeightConstraintsFirst = true),
                )
                Column(Modifier.weight(1f).fillMaxHeight()) {
                Text(
                    text = palette.id.label,
                    // QA-22, 13sp here is EXEMPT, unlike the 13sp that
                      // MimirType.SettingsActionTitle's KDoc records being removed on 2026-08-11. That one was
                      // a row title pretending to be a tighter role; this one is a preview-card title sized to
                      // a measured card. Same number, different job. See MimirTypeStep's KDoc.
                      style = MimirType.RowTitle.copy(fontSize = 13.sp, lineHeight = 16.sp),
                    color = palette.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = themeMood(LocalContext.current.resources, palette.id),
                    style = MimirType.RowSupport.copy(fontSize = MimirTypeStep.Micro, lineHeight = 12.sp),
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        ThemeSwatch(palette.accent, palette.outline)
                        ThemeSwatch(palette.materials.counterAccent, palette.outline)
                        ThemeSwatch(palette.surfaceRaised, palette.outline)
                    }
                    Text(
                        text = if (isCurrent) "✓" else "",
                        color = palette.materials.readableAccent,
                        fontSize = MimirTypeStep.Label,
                        lineHeight = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun ThemeSwatch(color: Color, outline: Color) {
    Box(
        Modifier
            .size(12.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(color)
            .border(0.5.dp, outline, RoundedCornerShape(3.dp)),
    )
}

@Composable
private fun ThemeIdentityBook(
    book: ThemePreviewBookTreatment,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(topStart = 2.dp, bottomStart = 2.dp, topEnd = 6.dp, bottomEnd = 6.dp)
    Box(
        modifier
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    0.00f to book.coverTop,
                    0.52f to book.coverMiddle,
                    1.00f to book.coverBottom,
                ),
            )
            .border(0.7.dp, book.hardware.copy(alpha = 0.50f), shape)
            .drawBehind { drawThemeIdentityBook(book) },
    )
}

private fun DrawScope.drawThemeIdentityBook(book: ThemePreviewBookTreatment) {
    val spine = size.width * 0.085f
    val foreEdge = size.width * 0.085f
    val pageInk = lerp(book.onCover, book.coverMiddle, 0.22f)
    drawRect(
        pageInk.copy(alpha = 0.88f),
        topLeft = Offset(size.width - foreEdge, 1.dp.toPx()),
        size = Size(foreEdge, size.height - 2.dp.toPx()),
    )
    drawRect(book.hardware.copy(alpha = 0.38f), size = Size(spine, size.height))
    drawLine(
        Color.Black.copy(alpha = 0.36f),
        Offset(spine + 0.7.dp.toPx(), 0f),
        Offset(spine + 0.7.dp.toPx(), size.height),
        0.8.dp.toPx(),
    )
    listOf(0.28f, 0.72f).forEach { y ->
        val lineY = size.height * y
        drawLine(book.hardware.copy(alpha = 0.94f), Offset(0f, lineY), Offset(spine, lineY), 1.1.dp.toPx())
        drawLine(lerp(book.hardware, Color.White, 0.68f), Offset(0f, lineY - 0.45.dp.toPx()), Offset(spine, lineY - 0.45.dp.toPx()), 0.45.dp.toPx())
    }
    val claspHeight = size.height * 0.14f
    drawRect(
        book.hardware.copy(alpha = 0.88f),
        topLeft = Offset(size.width - foreEdge * 0.72f, (size.height - claspHeight) / 2f),
        size = Size(foreEdge * 0.72f, claspHeight),
    )

    val leafWidth = size.width - spine - foreEdge
    val centerX = spine + leafWidth / 2f
    val centerY = size.height * 0.42f
    val runeWidth = leafWidth * 0.42f
    val runeHeight = runeWidth * 1.05f
    drawMannaz(
        color = book.runeInk,
        center = Offset(centerX, centerY),
        width = runeWidth,
        height = runeHeight,
        strokeWidth = (leafWidth * 0.045f).coerceAtLeast(0.8.dp.toPx()),
    )
    drawThemeBookSignature(book, spine, foreEdge)
}

private fun DrawScope.drawThemeBookSignature(
    book: ThemePreviewBookTreatment,
    spine: Float,
    foreEdge: Float,
) {
    val leafWidth = size.width - spine - foreEdge
    val radius = leafWidth * 0.105f
    val cx = size.width - foreEdge - radius - leafWidth * 0.08f
    val cy = size.height - radius - leafWidth * 0.08f
    val ink = book.counterAccent
    val highlight = lerp(ink, Color.White, 0.72f)
    val stroke = (leafWidth * 0.027f).coerceAtLeast(0.65.dp.toPx())
    fun line(x0: Float, y0: Float, x1: Float, y1: Float) {
        drawLine(ink.copy(alpha = 0.96f), Offset(x0, y0), Offset(x1, y1), stroke, cap = StrokeCap.Round)
        drawLine(highlight.copy(alpha = 0.90f), Offset(x0 - stroke * 0.2f, y0 - stroke * 0.2f), Offset(x1 - stroke * 0.2f, y1 - stroke * 0.2f), stroke * 0.36f, cap = StrokeCap.Round)
    }
    fun ring(x: Float, y: Float, r: Float) {
        drawCircle(ink.copy(alpha = 0.96f), r, Offset(x, y), style = Stroke(stroke))
        drawCircle(highlight.copy(alpha = 0.88f), r, Offset(x - stroke * 0.2f, y - stroke * 0.2f), style = Stroke(stroke * 0.36f))
    }
    fun disc(x: Float, y: Float, r: Float) {
        drawCircle(Brush.linearGradient(listOf(highlight, ink, book.coverBottom)), r, Offset(x, y))
    }
    fun arc(x: Float, y: Float, r: Float, start: Float, sweep: Float) {
        drawArc(
            ink.copy(alpha = 0.96f),
            start,
            sweep,
            false,
            Offset(x - r, y - r),
            Size(r * 2f, r * 2f),
            style = Stroke(stroke),
        )
    }
    fun spokes(count: Int, inner: Float, outer: Float) {
        repeat(count) { index ->
            val angle = Math.toRadians(index * (360.0 / count))
            val dx = kotlin.math.cos(angle).toFloat()
            val dy = kotlin.math.sin(angle).toFloat()
            line(cx + dx * inner, cy + dy * inner, cx + dx * outer, cy + dy * outer)
        }
    }

    when (book.signature) {
        ThemePreviewBookSignature.WELL_STUD -> {
            line(cx, cy - radius, cx + radius * 0.70f, cy)
            line(cx + radius * 0.70f, cy, cx, cy + radius)
            line(cx, cy + radius, cx - radius * 0.70f, cy)
            line(cx - radius * 0.70f, cy, cx, cy - radius)
        }
        ThemePreviewBookSignature.GOLDEN_SUN -> {
            spokes(8, radius * 0.38f, radius)
            ring(cx, cy, radius * 0.30f)
        }
        ThemePreviewBookSignature.FORGE_SPARK -> {
            line(cx, cy + radius, cx + radius * 0.24f, cy)
            line(cx + radius * 0.24f, cy, cx - radius * 0.18f, cy - radius * 0.55f)
            line(cx - radius * 0.18f, cy - radius * 0.55f, cx + radius * 0.15f, cy - radius)
        }
        ThemePreviewBookSignature.MOON_CRESCENT -> {
            arc(cx, cy, radius, 40f, 280f)
            arc(cx + radius * 0.42f, cy, radius * 0.80f, 72f, 216f)
        }
        ThemePreviewBookSignature.DREAD_SEAL -> {
            disc(cx, cy, radius * 0.82f)
            line(cx, cy - radius * 0.46f, cx, cy + radius * 0.46f)
            line(cx - radius * 0.36f, cy - radius * 0.18f, cx + radius * 0.36f, cy + radius * 0.18f)
        }
        ThemePreviewBookSignature.VOID_EYE -> {
            drawCircle(book.coverBottom, radius * 0.54f, Offset(cx, cy))
            ring(cx, cy, radius * 0.92f)
        }
        ThemePreviewBookSignature.WORLD_TREE -> {
            line(cx, cy + radius, cx, cy - radius)
            listOf(0.48f, 0f, -0.48f).forEach { branch ->
                line(cx, cy + radius * branch, cx + radius * 0.56f, cy + radius * branch - radius * 0.38f)
                line(cx, cy + radius * branch, cx - radius * 0.56f, cy + radius * branch - radius * 0.38f)
            }
        }
        ThemePreviewBookSignature.SHIELD_BOSS -> {
            ring(cx, cy, radius)
            ring(cx, cy, radius * 0.55f)
            spokes(6, radius * 0.58f, radius)
        }
        ThemePreviewBookSignature.SUMMER_SUN -> {
            spokes(10, radius * 0.62f, radius * 1.04f)
            disc(cx, cy, radius * 0.52f)
        }
    }
}

/** Mannaz remains invariant across all books; authored materials and signatures carry the theme identity. */
private fun DrawScope.drawMannaz(
    color: Color,
    center: Offset,
    width: Float,
    height: Float,
    strokeWidth: Float,
) {
    val left = center.x - width / 2f
    val right = center.x + width / 2f
    val top = center.y - height / 2f
    val bottom = center.y + height / 2f
    drawLine(color, Offset(left, top), Offset(left, bottom), strokeWidth, cap = StrokeCap.Round)
    drawLine(color, Offset(right, top), Offset(right, bottom), strokeWidth, cap = StrokeCap.Round)
    drawLine(color, Offset(left, top), Offset(right, top + height * 0.32f), strokeWidth * 0.82f, cap = StrokeCap.Round)
    drawLine(color, Offset(right, top), Offset(left, top + height * 0.32f), strokeWidth * 0.82f, cap = StrokeCap.Round)
}

/** The preview card's corner: one symbol for its clip and its ring (R-1). */
private val ThemePreviewCardCorner = 8.dp

/** The rest de-emphasis the gallery's cards have always carried (it was `mimirFocusRing`'s `restingAlpha`). */
private const val ThemePreviewCardRestingAlpha = 0.94f
