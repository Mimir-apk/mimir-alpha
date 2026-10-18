package com.gruning.mimir.rewrite.core.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlin.math.max
import kotlin.math.min

/**
 *  the two user-dialled appearance
 * overrides, applied OVER the authored palettes at theme-resolution time ([MimirTheme]):
 *
 *  1. **Accent stop** (Default theme only): curated warm-metal gold-family pairs that re-tint Default's
 *     accent system by the color derivation, never touching the eight named identities. PERSISTS across restarts.
 *  2. **Focus-ring stop** (every theme, per-theme persisted): ONE focus signature hue driving the nav
 *     ring, the tile ring, and the joycon LED together,
 *     superseding the authored per-surface split while active. LEDs always get the BRIGHT tone; the
 *     on-screen ring gets bright on dark themes / deep on light ones, guarded by a ≥3:1 contrast floor.
 *
 * State lives here as Compose snapshot state so [MimirTheme] (and `ctaMaterial()`) recompose the moment a
 * store or a picker's live preview writes it. The app layer's `AppearanceOverrideStore` is the persistence
 * bridge; pickers write the `*Preview` fields while open (preview wins until cleared).
 */

/** §2 — curated Default-accent stops (accent / accentBright pairs, gold-family harmony). */
enum class MimirAccentStop(val label: String, val accent: Color, val accentBright: Color) {
    CLASSIC_GOLD("Classic Gold", Color(0xFFD4AF69), Color(0xFFE8C57E)),
    WARM_BRASS("Warm Brass", Color(0xFFD9A85C), Color(0xFFF2C572)),
    AMBER_EMBER("Amber Ember", Color(0xFFE3B45E), Color(0xFFFFD277)),
    CHAMPAGNE("Champagne", Color(0xFFCBB98A), Color(0xFFEEDCA6)),
    ROSE_GOLD("Rose Gold", Color(0xFFD8A489), Color(0xFFF3C4A8)),
    ANTIQUE_BRONZE("Antique Bronze", Color(0xFFBF9A55), Color(0xFFE0BE78)),
}

/**
 * focus-highlight stops. Each carries a {bright, deep} tone pair: bright = dark-theme ring,
 * deep = light-theme ring. [THEME_DEFAULT] is the reset detent (authored values; tones unused).
 * NO green stop — green stays semantic success only.
 */
enum class MimirRingStop(val label: String, val bright: Color, val deep: Color) {
    THEME_DEFAULT("Theme default", Color.Unspecified, Color.Unspecified),
    GOLD("Gold", Color(0xFFE8C57E), Color(0xFF8A6A22)),
    AMBER("Amber", Color(0xFFFFB347), Color(0xFF9A5E12)),
    EMBER("Ember", Color(0xFFFF7A45), Color(0xFFA83618)),
    CRIMSON("Crimson", Color(0xFFFF4D6A), Color(0xFFA81E38)),
    VIOLET("Violet", Color(0xFFB79CFF), Color(0xFF5B44C0)),
    AZURE("Azure", Color(0xFF5FB8FF), Color(0xFF1F5E90)),
}

/**
 * Accent controls for the
 * NAMED themes' accent control: five temperature stops that cool/warm the theme's OWN authored accent
 * system the way photographic white balance does — a blend toward an azure (cool) or amber (warm) pole
 * with the authored brightness restored afterward. On the metal accent language that walks gold through
 * champagne into platinum on the cool side and into molten amber on the warm side. A plain hue rotation
 * was tried first and REJECTED on device evidence: gold's shortest hue path to azure runs through red,
 * so "Cooler" rendered PINK. [ORIGINAL] is the reset stop (authored, untouched).
 */
enum class MimirAccentTemp(val label: String, internal val towardWarm: Boolean, internal val fraction: Float) {
    COOLER("Cooler", towardWarm = false, fraction = 0.30f),
    COOL("Cool", towardWarm = false, fraction = 0.15f),
    ORIGINAL("Original", towardWarm = false, fraction = 0f),
    WARM("Warm", towardWarm = true, fraction = 0.15f),
    WARMER("Warmer", towardWarm = true, fraction = 0.30f),
}

object MimirAppearanceOverrides {
    /** The COMMITTED Default-theme accent stop (persisted by the app's store; policy #4). */
    var accentStop by mutableStateOf(MimirAccentStop.CLASSIC_GOLD)

    /** Live picker preview — wins over [accentStop] while non-null; cleared on commit/cancel. */
    var accentPreview by mutableStateOf<MimirAccentStop?>(null)

    /** CF01: the COMMITTED per-NAMED-theme accent temperatures (absent theme → [MimirAccentTemp.ORIGINAL]). */
    var accentTemps by mutableStateOf<Map<MimirThemeId, MimirAccentTemp>>(emptyMap())

    /** Live picker preview for the ACTIVE named theme's temperature — wins while non-null. */
    var accentTempPreview by mutableStateOf<MimirAccentTemp?>(null)

    /** The COMMITTED per-theme focus-ring stops (persisted; absent theme → authored default). */
    var ringStops by mutableStateOf<Map<MimirThemeId, MimirRingStop>>(emptyMap())

    /** Live dial preview — wins over [ringStops] for the ACTIVE theme while non-null. */
    var ringPreview by mutableStateOf<MimirRingStop?>(null)

    /**
     * The COMMITTED per-theme backgrounds (concept A; persisted; absent theme → Atmosphere).
     * Committed choices ONLY: the Background picker previews on its own stage through a parameter, never through a
     * preview field here, because a global preview would rebuild the Bottom's backdrop on every D-pad move.
     */
    var grounds by mutableStateOf<Map<MimirThemeId, MimirSceneGround>>(emptyMap())

    /** The COMMITTED Mimir-button mark (persisted; null → the rune). One mark, across every theme. */
    var buttonMark by mutableStateOf<MimirButtonMark?>(null)

    /** The background in force for [themeId], or null for the theme's own atmosphere. */
    fun effectiveGround(themeId: MimirThemeId): MimirSceneGround? = grounds[themeId]

    /** The accent stop in force for [themeId] (Default only; named identities temperature-shift instead). */
    fun effectiveAccent(themeId: MimirThemeId): MimirAccentStop? =
        if (themeId == MimirThemeId.DEFAULT) accentPreview ?: accentStop else null

    /** CF01: the temperature in force for a NAMED [themeId] (Default returns [MimirAccentTemp.ORIGINAL]). */
    fun effectiveAccentTemp(themeId: MimirThemeId): MimirAccentTemp =
        if (themeId == MimirThemeId.DEFAULT) {
            MimirAccentTemp.ORIGINAL
        } else {
            accentTempPreview ?: accentTemps[themeId] ?: MimirAccentTemp.ORIGINAL
        }

    /** The ring stop in force for [themeId] (preview wins; committed map next; else authored). */
    fun effectiveRing(themeId: MimirThemeId): MimirRingStop =
        ringPreview ?: ringStops[themeId] ?: MimirRingStop.THEME_DEFAULT

    /**
     * True when any override mutates the ACTIVE look — feeds the Appearance card's honest chip. Counts the theme's
     * background and the button mark too: otherwise the chip reads Default over a custom look.
     */
    fun customizes(themeId: MimirThemeId): Boolean =
        (effectiveAccent(themeId) ?: MimirAccentStop.CLASSIC_GOLD) != MimirAccentStop.CLASSIC_GOLD ||
            effectiveAccentTemp(themeId) != MimirAccentTemp.ORIGINAL ||
            effectiveRing(themeId) != MimirRingStop.THEME_DEFAULT ||
            effectiveGround(themeId) != null ||
            buttonMark != null

    /** The fully-resolved palette for [themeId] with every override applied — the ONE lookup non-compose
     *  callers share with [MimirTheme]. */
    fun effectivePalette(themeId: MimirThemeId): MimirThemePalette =
        MimirThemePalette.forId(themeId).withAppearanceOverrides()
}

/**
 * Calm shine, the Utility switch for sensitive players. Off is the default
 * and the shipped product. On tones the focus shine down: one soft glint on a game, none anywhere else (see
 * [LocalMimirCalmShine]).
 *
 * Snapshot state, like [MimirAppearanceOverrides] and [MimirViewportSettings]: [MimirTheme], which every window
 * wraps its content in (the app, both screens, the panels over other apps), publishes it as
 * [LocalMimirCalmShine], so a flip reaches every gleam that is showing. The app's `AppearanceOverrideStore`
 * persists it and loads it at process start; nothing here is durable.
 */
object MimirCalmShineSettings {
    /** True only when the player turned calm shine on. Off until a stored yes is loaded. */
    var enabled by mutableStateOf(false)
}

/**
 * Apply the active overrides to an authored palette: the accent layer first (Default = curated stop
 * via the §2 derivation rule; named themes = CF01 temperature shift over their OWN accent system), then
 * the ring override (§3 — supersedes the authored focus split, including an accent-derived one).
 */
fun MimirThemePalette.withAppearanceOverrides(): MimirThemePalette {
    var p = this
    val accentStop = MimirAppearanceOverrides.effectiveAccent(id)
    if (accentStop != null && accentStop != MimirAccentStop.CLASSIC_GOLD) {
        val a = accentStop.accent
        val ab = accentStop.accentBright
        // §2 derivation: the tint rides ONLY the accent system — surfaces, text ramp, scene and
        // counterAccent stay authored. Alphas transcribed from Default's authored tokens.
        p = p.copy(
            accent = a,
            accentBright = ab,
            focusRing = ab, // Default is dark → the ring follows the bright variant
            accentGlow = a.copy(alpha = 0x80 / 255f),
            tileRing = ab,
            tileGlow = a.copy(alpha = 0x57 / 255f),
            tileGlowStrong = a.copy(alpha = 0x80 / 255f),
            emberHot = ab,
            tileFocusRing = ab,
            materials = p.materials.copy(hero = p.materials.hero.copy(hardware = a)),
        )
    }
    val temp = MimirAppearanceOverrides.effectiveAccentTemp(id)
    if (temp != MimirAccentTemp.ORIGINAL) {
        // CF01: cool/warm the NAMED theme's own accent system. Every accent-family token blends by the
        // SAME transform while keeping its authored brightness and alpha, so the identity (and its
        // per-surface contrast splits — deep light-theme rings, bright tile rings) survives the shift.
        p = p.copy(
            accent = temp.shift(p.accent),
            accentBright = temp.shift(p.accentBright),
            focusRing = temp.shift(p.focusRing),
            accentGlow = temp.shift(p.accentGlow),
            tileRing = temp.shift(p.tileRing),
            tileGlow = temp.shift(p.tileGlow),
            tileGlowStrong = temp.shift(p.tileGlowStrong),
            emberHot = temp.shift(p.emberHot),
            tileFocusRing = temp.shift(p.tileFocusRing),
            materials = p.materials.copy(
                hero = p.materials.hero.copy(hardware = temp.shift(p.materials.hero.hardware)),
            ),
        )
    }
    val ringStop = MimirAppearanceOverrides.effectiveRing(id)
    if (ringStop != MimirRingStop.THEME_DEFAULT) {
        val tone = ringStop.screenToneFor(p)
        p = p.copy(
            focusRing = tone,
            tileFocusRing = tone,
        )
    }
    return p
}

// ── CF01 temperature math (photographic white balance, pure Kotlin on the JVM test path) ──

/** The temperature poles: molten amber (warm) and deep azure (cool). */
private val WarmAnchor = Color(1f, 0.5f, 0f)
private val CoolAnchor = Color(0f, 0.235f, 1f)

/**
 * Temperature-blend [color] toward this stop's pole, then restore the authored brightness (max
 * channel) so no stop ever dims or blows out a token — a ring stays as luminous as its authored
 * self, it just runs colder or hotter. Alpha is preserved. Deliberately NOT a hue rotation: the
 * shortest hue arc from the gold family to azure passes through red, which rendered "Cooler" as
 * pink on device. The blend walks the metal family instead (gold → champagne → platinum on the
 * cool side, gold → molten amber on the warm side) and gives near-neutral accents like Niflheim's
 * platinum a true temperature cast rather than a no-op.
 */
fun MimirAccentTemp.shift(color: Color): Color =
    temperatureBlend(color, if (towardWarm) WarmAnchor else CoolAnchor, fraction)

/**
 * The warm half of [MimirAccentTemp.shift], at an arbitrary fraction rather than one of the
 * enum's fixed stops. The fan readout warms near its target temperature, so that surface warms an authored token through the SAME blend
 * every accent stop uses -- same anchor, same brightness restore, same device finding behind it --
 * instead of introducing a colour of its own. Palette-driven theming means no feature module picks
 * a hex; it does not mean a feature module cannot ask the theme to run a token hotter.
 */
fun warmShifted(color: Color, fraction: Float): Color =
    temperatureBlend(color, WarmAnchor, fraction.coerceIn(0f, 1f))

private fun temperatureBlend(color: Color, anchor: Color, fraction: Float): Color {
    if (fraction == 0f) return color
    val r = lerp(color.red, anchor.red, fraction)
    val g = lerp(color.green, anchor.green, fraction)
    val b = lerp(color.blue, anchor.blue, fraction)
    val blendedMax = maxOf(r, g, b)
    if (blendedMax <= 0f) return color
    val scale = maxOf(color.red, color.green, color.blue) / blendedMax
    return Color(
        red = (r * scale).coerceIn(0f, 1f),
        green = (g * scale).coerceIn(0f, 1f),
        blue = (b * scale).coerceIn(0f, 1f),
        alpha = color.alpha,
    )
}

private fun lerp(from: Float, to: Float, fraction: Float): Float = from + (to - from) * fraction

/**
 * The on-screen ring tone for [palette] maximizes its worst contrast across every authored field surface.
 * This is stable for dark and bright ramps without inferring presentation from one luminance sample.
 */
fun MimirRingStop.screenToneFor(palette: MimirThemePalette): Color {
    val surfaces = listOf(palette.surfaceBase, palette.surfaceRaised, palette.surfaceHigh)
    return listOf(bright, deep).maxBy { tone ->
        surfaces.minOf { surface -> contrastRatio(tone, surface) }
    }
}

/**
 * §3 contrast guardrail: a stop is offerable for a theme only when its theme-appropriate tone clears
 * ≥ 3:1 (WCAG 2.1 §1.4.11 UI-component minimum) against every surface the ring is drawn over.
 * [MimirRingStop.THEME_DEFAULT] is always enabled (authored + already verified).
 */
fun MimirRingStop.enabledFor(palette: MimirThemePalette): Boolean {
    if (this == MimirRingStop.THEME_DEFAULT) return true
    return listOf(palette.surfaceBase, palette.surfaceRaised, palette.surfaceHigh).all { surface ->
        contrastRatio(screenToneFor(palette), surface) >= 3.0
    }
}

/** WCAG 2.1 relative luminance of an sRGB colour. */
internal fun Color.relativeLuminance(): Double {
    fun channel(c: Float): Double {
        val v = c.toDouble()
        return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * channel(red) + 0.7152 * channel(green) + 0.0722 * channel(blue)
}

/** WCAG 2.1 contrast ratio between two colours (order-independent, 1..21). */
internal fun contrastRatio(a: Color, b: Color): Double {
    val la = a.relativeLuminance()
    val lb = b.relativeLuminance()
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
}

/** §2 — the accent-derived CTA gradient (0% → mid → 100%), keeping the material in the stop's family. */
fun MimirAccentStop.ctaMaterial(): List<Color> = listOf(
    lerp(accentBright, Color.White, 0.25f),
    accent,
    lerp(accent, Color.Black, 0.45f),
)
