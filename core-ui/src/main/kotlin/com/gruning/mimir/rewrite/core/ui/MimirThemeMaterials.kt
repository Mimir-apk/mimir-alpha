package com.gruning.mimir.rewrite.core.ui

import androidx.compose.ui.graphics.Color

/** The authored scene behind a shelf. Each shipping identity owns one scene, not a recolored shared field. */
enum class MimirAtmosphereKind {
    WELL,
    GOLDEN_HALL,
    FORGE,
    MOONLIT,
    DREAD,
    VOID,
    ROOTED_WELL,
    FOLKVANGR,
    SUNLIT,
}

/** Low-alpha shelf surface treatment. The texture controls geometry as well as color. */
enum class MimirShelfTexture {
    ARCHITECTURAL,
    GOLDEN_WEAVE,
    FORGED_STEEL,
    STARLIGHT,
    BONE_ASH,
    SHADOW_VEIL,
    ABYSS,
    ROOT_GRAIN,
    SUMMER_SKY,
}

/** Only the two shipping themes with an authored molten header may select a molten kind. */
enum class MimirSeparatorKind {
    BEAM,
    SURTR_MOLTEN,
    NIFLHEIM_MOLTEN,
    FOLKVANGR_MOLTEN,
}

/**
 * The dock separator is presentation intent, not an inference from field luminance or separator geometry.
 *
 * [MOLTEN] is the Quick Rail's plinth: the theme's own crest from [MimirShelfArt] with its bright core
 * over it, static. It is a SEPARATE claim from the animated header rule, which `MimirShelfChrome` ships
 * for Surtr and Niflheim alone -- a theme may take the plinth without taking the motion, and Fólkvangr
 * does exactly that .
 */
enum class MimirDockSeparatorTreatment {
    MOLTEN,
    QUIET,
}

data class MimirSeparatorMaterial(
    val kind: MimirSeparatorKind,
    val shoulder: Color,
    val core: Color,
    val glow: Color,
    val node: Color,
    val dockTreatment: MimirDockSeparatorTreatment,
) {
    val supportsMolten: Boolean get() = kind != MimirSeparatorKind.BEAM
}

/** User-facing finish family shown by the design gallery. */
enum class MimirThemeFinish {
    DARK,
    METALLIC,
}

/** Authored application-field posture, independent from media and dock treatment. */
enum class MimirFieldTone {
    DARK,
    BRIGHT,
}

/** Home/Library media veil intent; metallic themes may retain a restrained veil over a dark field. */
enum class MimirMediaVeilTreatment {
    STANDARD,
    NEAR_CLEAR,
}

/**
 * Cross-surface presentation intent that used to be inferred from one `isLight` boolean.
 *
 * Keeping these roles independent prevents a palette promotion from silently changing media readability
 * or dock identity. Phase 1.4 first seeds them to the current rendered result, then promotes values.
 */
data class MimirThemePresentation(
    val finish: MimirThemeFinish,
    val fieldTone: MimirFieldTone,
    val mediaVeil: MimirMediaVeilTreatment,
    /** Authored Home media wash, including final compositing alpha. Transparent means no wash. */
    val homeMediaTint: Color,
    val localizedMediaContrast: Boolean,
    val glassUsesFieldFocusRing: Boolean,
)

/** A dark, readable hero/book volume independent from the surrounding application field. */
data class MimirHeroMaterial(
    val coverTop: Color,
    val coverMiddle: Color,
    val coverBottom: Color,
    val onCover: Color,
    val hardware: Color,
)

/** Palette inputs for deterministic draw-only atmosphere composition. */
data class MimirSceneMaterial(
    val atmosphere: MimirAtmosphereKind,
    val texture: MimirShelfTexture,
    val fieldFooter: Color,
    val railTrough: Color,
    val shelfSheen: Color,
    val shelfThread: Color,
    val stagePrimary: Color,
    val stageSecondary: Color,
    val highlight: Color,
    val shadow: Color,
    val particle: Color,
)

/**
 * Roles that do not fit Material 3's single-primary model.
 *
 * [counterAccent] is the second authored hue that keeps a theme from becoming monochrome: cool blue for
 * Default, periwinkle for Freyr, quenched steel for Surtr, starlight for Mani, bone ash for Hel, violet for
 * Niflheim, foliage for Yggdrasil, worn bone for Folkvangr, and sunlight for Baldr.
 */
data class MimirThemeMaterials(
    val counterAccent: Color,
    val onCounterAccent: Color,
    val readableAccent: Color,
    val contrastMuted: Color,
    val contrastSoft: Color,
    val contrastLine: Color,
    val contrastGlow: Color,
    val contrastSheen: Color,
    val glassSheen: Color,
    val railSheen: Color,
    val controlTop: Color,
    val controlBottom: Color,
    val hero: MimirHeroMaterial,
    val scene: MimirSceneMaterial,
    val separator: MimirSeparatorMaterial,
    /** Authored high-contrast chrome over the shared near-black modal scrim. */
    val onScrimPrimary: Color,
    val onScrimSecondary: Color,
    val onScrimMuted: Color,
)
