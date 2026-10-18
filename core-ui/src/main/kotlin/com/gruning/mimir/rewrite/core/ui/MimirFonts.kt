package com.gruning.mimir.rewrite.core.ui

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/**
 * MIMIR-REWRITE :core-ui — THE product typeface.
 *
 * the product standardizes on **IBM Plex Sans**, with **IBM Plex Mono** as the
 * companion face for fixed-width readouts. Before bundling these fonts the app shipped no font asset at all and
 * every surface inherited the Android system default, so "consistent on all surfaces" was not something
 * the tree could express.
 *
 * Licensing: SIL Open Font License 1.1. The licence text ships inside the APK at
 * `assets/fonts/IBMPlex-OFL.txt` (it cannot live in `res/font`, which accepts only font binaries), as the
 * licence requires of a redistributed font. This matters beyond compliance: Mimir stays free, so a
 * per-seat foundry licence was never an option.
 *
 * WEIGHTS ARE DELIBERATELY FEW. The same policy collapses title and subtext onto one weight, so the
 * four-weight spread the tree had (Normal/Medium/SemiBold/Bold) is not carried over wholesale. Three sans
 * weights ship; adding a fourth needs a reason, because each is ~200 KB of APK.
 */
object MimirFonts {

    /** The product face. Every text surface resolves here unless it has an explicit exception not to. */
    val Sans: FontFamily = FontFamily(
        Font(R.font.ibm_plex_sans_regular, FontWeight.Normal),
        Font(R.font.ibm_plex_sans_medium, FontWeight.Medium),
        Font(R.font.ibm_plex_sans_semibold, FontWeight.SemiBold),
        // Bold ships only because 12 sites still ask for it. Omitting it does not remove bold from the
        // screen — Android synthesizes a smeared fake-bold from the nearest weight instead, which is
        // worse than the 200 KB. If the type scale drops Bold, drop this file with it.
        Font(R.font.ibm_plex_sans_bold, FontWeight.Bold),
    )

    /**
     * Fixed-width companion, for readouts where columns must not shift between frames.
     *
     * Note [SansDigitAdvanceRatio] before reaching for this: Plex *Sans* already has tabular figures, so
     * a live number does not need Mono merely to stop shivering. Mono is for genuine column alignment
     * (diagnostics dumps, hashes, paths), not for every digit.
     */
    val Mono: FontFamily = FontFamily(
        Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
        Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
    )

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // MEASURED ADVANCE RATIOS
    //
    // Read directly out of the shipped TTFs' `head`/`hmtx` tables (unitsPerEm = 1000 for all three).
    // They exist because `PerformanceOverlayPresenter` sizes its own window arithmetically, from
    // `text.length * fontSizeSp * <ratio>`, rather than from a real measurement. That estimator was
    // carrying a hardcoded 0.62 — an assumption about the *previous* system font. Swapping the family
    // without re-deriving it would silently mis-size a window whose geometry is device-certified.
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Every Plex Mono glyph advances exactly 600/1000 em. For monospaced text this is not an estimate —
     * width is `chars * size * 0.600` exactly.
     */
    const val MonoAdvanceRatio: Float = 0.600f

    /**
     * Plex Sans digits ALSO advance exactly 600/1000 em — the face is tabular by default. Use this for
     * digit-dominated strings (frame rate, temperature, battery), where it is likewise exact.
     */
    const val SansDigitAdvanceRatio: Float = 0.600f

    /**
     * Mean advance over Plex Sans Regular's UPPERCASE alphabet: 0.6336.
     *
     * This is the figure that matters for capped chrome, and it is WIDER than the 0.62 the overlay's
     * estimator used to assume — Plex's caps are broad. A surface that renders capped labels and gets
     * sized with a lowercase-inclusive average will be sized too narrow.
     */
    const val SansUppercaseAdvanceRatio: Float = 0.6336f

    /**
     * Mean advance over the character set the performance overlay ACTUALLY renders — uppercase labels,
     * digits, and its symbols (`% ° / . ·`), with no lowercase at all: 0.5927.
     *
     * Use this one for that surface. The strings are `FPS`, `CPU`, `GPU`, `BATT`, `100%`, `100°C`,
     * `72H 00M` — there is not a lowercase letter among them.
     */
    const val SansOverlayAdvanceRatio: Float = 0.5927f

    /**
     * Mean advance across MIXED-CASE alphanumeric text in Plex Sans Regular: 0.5645.
     *
     * CORRECTED 2026-08-11 — this constant was originally documented as "the sample the overlay actually
     * renders", which was wrong: the sample behind it included the full lowercase alphabet, and the
     * overlay renders none. Reasoning from it predicted the overlay plate would get NARROWER under Plex.
     * Odin measured it 3.2% WIDER (517px vs 501px, same device/surface/mode, build `b6e5c74d`), which is
     * what [SansUppercaseAdvanceRatio] and [SansDigitAdvanceRatio] predict and this figure does not.
     *
     * Kept because it is correct for prose — body copy, help text, descriptions. It is simply the wrong
     * ratio for capped chrome. Averages are per-character-set, not per-font; picking one means naming the
     * text you are actually measuring. Any surface that can measure text properly should still do that
     * instead of reaching for any of these.
     */
    const val SansMeanAdvanceRatio: Float = 0.5645f

    /**
     * The product face as a framework [android.graphics.Typeface], for the one surface that measures text
     * with [android.graphics.Paint] instead of letting Compose lay it out.
     *
     * The performance overlay sizes its own window by measuring strings with a raw Paint, then renders
     * those strings through Compose. Those are two independent text stacks, and they only agree if they
     * hold the same typeface. Before this existed the Paint was pinned to the platform `sans-serif-medium`
     * while Compose rendered the product face — which measures a window for one font and fills it with a
     * wider one.
     *
     * Returns null if the font cannot be resolved, so callers can fall back rather than crash: this
     * overlay's geometry is device-certified, and a mis-measured window is a far smaller defect than a
     * dead overlay.
     */
    fun sansMediumTypeface(context: android.content.Context): android.graphics.Typeface? =
        runCatching { context.resources.getFont(R.font.ibm_plex_sans_medium) }.getOrNull()

    /**
     * Plex Sans SemiBold as a framework [android.graphics.Typeface], for the native ᛗ menu's labels: the
     * weight of the capped eyebrow and micro-status roles those labels stand in for. The menu's Views live in `core-companion-native`,
     * which cannot reach core-ui, so the app resolves the face here and hands it over. Null if it cannot be resolved, so the menu keeps
     * the platform's bold rather than failing.
     */
    fun sansSemiBoldTypeface(context: android.content.Context): android.graphics.Typeface? =
        runCatching { context.resources.getFont(R.font.ibm_plex_sans_semibold) }.getOrNull()
}
