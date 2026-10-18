package com.gruning.mimir.rewrite

import android.content.Context
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import com.gruning.mimir.rewrite.core.companionnative.AndroidCompanionOverlayController
import com.gruning.mimir.rewrite.core.companionnative.CompanionButtonMark
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayTheme
import com.gruning.mimir.rewrite.core.model.MimirMarkPlacement
import com.gruning.mimir.rewrite.core.ui.MimirAppearanceOverrides
import com.gruning.mimir.rewrite.core.ui.MimirButtonMark
import com.gruning.mimir.rewrite.core.ui.MimirFonts
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

private const val TomeSubtitleMinimumContrast = 4.5

/**
 * Round 5d: the process's native ᛗ overlay, wearing the player's theme from the moment it exists.
 *
 * The Bottom's ᛗ button window, the menu card a long press opens and the card's coins are Views in
 * [AndroidCompanionOverlayController]. It builds all of them from one [CompanionOverlayTheme] field, which starts as
 * [CompanionOverlayTheme.Default], and `core-companion-native` cannot read [ThemeStore]. The only push into that field was
 * MainActivity's ThemeStore collector, while [MButtonTopologyController] targets the ᛗ from process start, before any
 * MainActivity exists. On the Thor (build bd8f88db, round 5c), with Hel stored, the floor's cover was Hel crimson 1.25 s
 * after Mimir's process was ended, and the ᛗ was Default gold.
 *
 * This subscribes the controller to [ThemeStore.themeId] for as long as [scope] lives: in production, the companion
 * runtime's process scope. The first value is applied undispatched, before this returns. On the main thread, where
 * `Application.onCreate` runs it, that sets the controller's material directly, and the controller mounts only from its
 * handler, after this call, so no ᛗ, card or coin is built from Default. Off the main thread the controller posts that
 * first value, ahead of the topology's first target. Round 5c's seed loads the store before the runtime starts, so the
 * first value is the stored theme. Later values go through the controller's own theme change, which repaints a mounted ᛗ
 * or an open menu in place and keeps its deferrals (an idle-hidden ᛗ, a menu card mid-gesture).
 *
 * Round 5d follow-up: the push maps [companionOverlayTheme], the palette the Bottom's covers draw with, so the player's
 * accent stop or temperature reaches the menu card as it reaches the covers. Round 5c's seed loads those overrides before
 * the runtime starts too, so the first value already carries them. [AppearanceOverrideStore.revision] ticks on every
 * committed override and every load, and each tick after the one current at this call pushes again. That collector
 * resumes on [Dispatchers.Main], after the committing call has returned: a tap in the Accent picker focuses and commits
 * its row in one call (`MimirSelectOverlay`), before the picker's focus collector has previewed that row, and the commit
 * clears the preview as the picker closes (`MimirSettingsCoordinator.commitAccent`). A push inside the commit would read
 * the previous row's preview and keep it, because nothing ticks after that clear. A live preview alone pushes nothing:
 * the controller repaints the ᛗ by re-adding its window, and a push per preview would do that for every row the picker's
 * focus visits. The covers retint during a preview; the ᛗ follows at the commit.
 *
 * The player's own mark (concept A) rides in the same push and follows the same rule. [companionOverlayTheme]
 * reads the COMMITTED `MimirAppearanceOverrides.buttonMark` -- `AppearanceOverrideStore.setButtonMark` writes it and ticks
 * `revision`, which is the only tick this needs -- so the Mimir-button picker's A commits and the live ᛗ repaints, while the
 * picker's focus, which only previews on its own stage through `createMimirButtonPreviewView`, pushes nothing: each push that
 * changes the theme re-adds the overlay window, and one per previewed tile would do that on every focus move. Its mark is
 * mapped to a [CompanionButtonMark] (the rune for none), and the controller falls back to the rune if the file is gone.
 *
 * This is the only push, so the controller never holds a material the stores do not. Each push logs the theme, the accent
 * stop and temperature it read (`accent=none` on a named theme, `temp=ORIGINAL` on Default), the menu card's rim and
 * sheen colours it carried, and the mark (`mark=rune`, or the mark file's content-hash id, never its path). `pushUs` is
 * what the push took on the calling thread, including a repaint the controller ran in place; a deferred repaint is not in
 * it. Pinned by CompanionOverlayProcessThemeTest and CompanionOverlayThemePushSourceTest.
 */
internal fun startCompanionOverlay(context: Context, scope: CoroutineScope): AndroidCompanionOverlayController {
    val overlay = AndroidCompanionOverlayController.instance(context)
    // T9: the menu's words are the product's face. Set before the first mount, with the first theme.
    overlay.menuLabelTypeface = MimirFonts.sansSemiBoldTypeface(context)
    // Localization step 2: the menu's words are read with the player's language, which below API 33 the process's contexts do not carry.
    overlay.menuResourcesProvider = MimirLocale::resourcesFor
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
        ThemeStore.themeId.collect { themeId -> pushCompanionOverlayTheme(overlay, themeId) }
    }
    // Subscribed undispatched, so the dropped value is the state just pushed; resumed posted, after the committing call.
    scope.launch(Dispatchers.Main, start = CoroutineStart.UNDISPATCHED) {
        AppearanceOverrideStore.revision.drop(1).collect { pushCompanionOverlayTheme(overlay, ThemeStore.themeId.value) }
    }
    return overlay
}

/** The one production push: [companionOverlayTheme] for [themeId] into [overlay], and its device log line. */
private fun pushCompanionOverlayTheme(overlay: AndroidCompanionOverlayController, themeId: MimirThemeId) {
    val accent = MimirAppearanceOverrides.effectiveAccent(themeId)?.name ?: "none"
    val temperature = MimirAppearanceOverrides.effectiveAccentTemp(themeId).name
    val pushStart = System.nanoTime()
    val theme = companionOverlayTheme(themeId)
    overlay.setOverlayTheme(theme)
    val pushMicros = (System.nanoTime() - pushStart) / 1_000L
    Log.i(
        "MimirTheme",
        "overlay theme pushed theme=${themeId.storageKey} accent=$accent temp=$temperature " +
            "cardRim=${rgbHex(theme.pageEdgeArgb)} cardSheen=${rgbHex(theme.toolingArgb)} mark=${theme.mark.logName()} " +
            "pushUs=$pushMicros",
    )
}

/**
 * The overlay's material roles, from the palette the Bottom's Compose covers draw with.
 *
 * Every cover wears `MimirTheme`, which resolves the authored palette with the player's appearance overrides applied
 * (`MimirTheme.kt`, `withAppearanceOverrides`). [MimirAppearanceOverrides.effectivePalette] is that same lookup for callers
 * outside a composition. An accent stop on Default, or a temperature on a named theme, retints `accentBright` and the
 * hero's `hardware`, which are the menu card's rim ([CompanionOverlayTheme.pageEdgeArgb]), its sheen line
 * ([CompanionOverlayTheme.toolingArgb]) and `coverEdgeArgb`. No override reaches the other roles (the surfaces, the ink,
 * `counterAccent`, the console metal); a focus-ring stop reaches only [CompanionOverlayTheme.focusRingArgb], the primary
 * quick menu's key-focus ring, which wears `tileFocusRing` like every shelf ring.
 */
internal fun companionOverlayTheme(
    themeId: MimirThemeId,
    /** The mark the ᛗ wears: the COMMITTED one unless a preview surface asks for another (it never pushes it). */
    buttonMark: MimirButtonMark? = MimirAppearanceOverrides.buttonMark,
): CompanionOverlayTheme {
    val palette = MimirAppearanceOverrides.effectivePalette(themeId)
    val materials = palette.materials
    return CompanionOverlayTheme(
        coverArgb = materials.hero.coverBottom.toArgb(),
        coverEdgeArgb = materials.hero.hardware.toArgb(),
        pageArgb = materials.hero.coverTop.toArgb(),
        pageEdgeArgb = palette.accentBright.toArgb(),
        inkArgb = materials.hero.onCover.toArgb(),
        mutedInkArgb = readableMutedInkArgb(
            preferred = palette.textSecondary,
            highContrast = materials.hero.onCover,
            page = materials.hero.coverTop,
            cover = materials.hero.coverBottom,
        ),
        accentArgb = materials.counterAccent.toArgb(),
        toolingArgb = materials.hero.hardware.toArgb(),
        // The console-art metal, verbatim. `platformConsoleIconBrush` reads these same four off the
        // same palette, so a rune coin and a shelf tile are struck from one ramp rather than two that
        // drift apart the first time either is adjusted.
        metalLightArgb = palette.iconGradLight.toArgb(),
        metalHotArgb = palette.iconGradHot.toArgb(),
        metalMidArgb = palette.iconGradMid.toArgb(),
        metalDeepArgb = palette.iconGradDeep.toArgb(),
        focusRingArgb = palette.tileFocusRing.toArgb() or 0xFF000000.toInt(),
        mark = buttonMark.toCompanionButtonMark(),
    )
}

/**
 * The app's mark as the native overlay's: the player's image with the placement they gave it , or the rune for none. The
 * placement is sanitized here, once, so a value from anywhere reaches the coin as a finite number inside its range.
 */
internal fun MimirButtonMark?.toCompanionButtonMark(): CompanionButtonMark =
    if (this == null) {
        CompanionButtonMark.Rune
    } else {
        CompanionButtonMark.Image(id = id, path = path, placement = MimirMarkPlacement(scale, offsetX, offsetY).sanitized())
    }

/** What the device log says of a mark: `rune`, or the content-hash id that names the file. Never a path. */
private fun CompanionButtonMark.logName(): String = when (this) {
    CompanionButtonMark.Rune -> "rune"
    is CompanionButtonMark.Image -> id
}

private fun readableMutedInkArgb(
    preferred: Color,
    highContrast: Color,
    page: Color,
    cover: Color,
): Int {
    val pageArgb = page.toArgb()
    val coverArgb = cover.toArgb()

    for (step in 0..16) {
        val candidate = lerp(preferred, highContrast, step / 16f).toArgb()
        if (
            contrastRatio(candidate, pageArgb) >= TomeSubtitleMinimumContrast &&
            contrastRatio(candidate, coverArgb) >= TomeSubtitleMinimumContrast
        ) {
            return candidate
        }
    }

    return highContrast.toArgb()
}

private fun contrastRatio(first: Int, second: Int): Double {
    val light = max(luminance(first), luminance(second))
    val dark = min(luminance(first), luminance(second))
    return (light + 0.05) / (dark + 0.05)
}

private fun luminance(argb: Int): Double {
    fun linear(channel: Int): Double {
        val value = channel / 255.0
        return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
    }

    return 0.2126 * linear(argb ushr 16 and 0xff) +
        0.7152 * linear(argb ushr 8 and 0xff) +
        0.0722 * linear(argb and 0xff)
}

/** The RGB of an opaque overlay role, as a device log shows it. */
private fun rgbHex(argb: Int): String = "#" + Integer.toHexString(argb and 0xffffff).padStart(6, '0')
