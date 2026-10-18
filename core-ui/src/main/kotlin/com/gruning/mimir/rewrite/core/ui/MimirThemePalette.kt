package com.gruning.mimir.rewrite.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

private fun darkPresentation() = MimirThemePresentation(
    finish = MimirThemeFinish.DARK,
    fieldTone = MimirFieldTone.DARK,
    mediaVeil = MimirMediaVeilTreatment.STANDARD,
    homeMediaTint = Color.Transparent,
    localizedMediaContrast = false,
    glassUsesFieldFocusRing = false,
)

private fun metallicPresentation(homeMediaTint: Color) = MimirThemePresentation(
    finish = MimirThemeFinish.METALLIC,
    fieldTone = MimirFieldTone.DARK,
    mediaVeil = MimirMediaVeilTreatment.NEAR_CLEAR,
    homeMediaTint = homeMediaTint,
    localizedMediaContrast = false,
    glassUsesFieldFocusRing = false,
)

/**
 * The shipping theme colors from the shipping `styles.css`, encoded as explicit ARGB values.
 *
 * [accent] maps to shelf `--eok-ember`; [focusRing] maps to the effective readable focus color; [tileRing]
 * maps to `--eok-tile-ring`. Those properties intentionally split on Hel, Niflheim, Folkvangr, and Baldr. CSS
 * decimal alpha is rounded to the nearest 8-bit ARGB alpha, for example `0.34` -> `0x57`.
 *
 * Compose-only reductions are [signatureTop]/[signatureBottom], which hold the outer colors of shipping's
 * layered shelf background; [tileFill], the legacy scalar fallback for `--eok-tile-fill`; and the semantic
 * [danger]/[warning] roles, where one Compose color represents CSS's separate ink, border, and fill channels.
 * Gradient stop percentages remain consumer geometry because this palette structure stores colors only.
 */
data class MimirThemePalette(
    val id: MimirThemeId,
    val presentation: MimirThemePresentation,

    // ── Accent and focus ──
    val accent: Color,
    val accentBright: Color,
    val accentGlow: Color,       // --eok-focus-glow
    val onAccent: Color,         // --theme-cta-ink
    val onAccentBright: Color = Color(0xFF120A03),
    val onSuccess: Color,        // .status-chip.ready ink
    val focusRing: Color = accent,
    /**
     * The fill a FOCUSED quick-rail seat wears. Defaults to [accentBright], which is what every seat used
     * before this token existed, so a theme that does not name it is byte-identical.
     *
     * Yggdrasil's focused bright-gold disc overwhelmed the living-sap scene; its gold should feel
     * aged and organic. The seat wears accent at full strength over
     * a scene built to stay quiet, so it needs its own knob rather than dragging [accentBright] — which
     * also inks runes, embers and particles — down with it.
     */
    val seatFocusFill: Color = accentBright,
    // Override-aware focus-ring channel: authored palettes
    // leave this on the theme's own bright accent — byte-identical rendering — and ONLY the appearance
    // overrides ([withAppearanceOverrides]) retarget it. Feeds the shelf/gamelist mimirFocusTileRing
    // call sites. (CF01: the joycon LED channel was DISCARDED with the theme-follow feature.)
    val tileFocusRing: Color = accentBright,

    // ── Surface stack (dark or metallic-dark authored ramp) ──
    val surfaceBase: Color,      // app field
    val surfaceRaised: Color,    // cards / rail
    val surfaceHigh: Color,      // focused/selected fill
    val outline: Color,          // --line, including its shipping alpha
    val tileFill: Color,         // Compose scalar fallback; shipping uses the tileFillTop/Deep gradient

    // ── Text ramp ──
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,

    // ── Two-color reduction of shipping's layered shelf background ──
    val signatureTop: Color,
    val signatureBottom: Color,

    // ── Shelf hero-tile visual tokens ──────────────────────────────────
    // The centered console-coverflow hero (`ShelfCoverflow`/`PlatformHeroTile`) reads
    // these. They are the Compose ports of the shipping `--eok-tile-*` / `--eok-amb` / `--eok-ember-
    // soft` custom properties (`styles.css` :root + `html[data-theme=…]` blocks). Baked ARGB — the
    // same no-`color-mix()` discipline as the rest of this file. Every alpha is transcribed from the
    // live stylesheet, NOT recomputed, so the hero matches the shipping APK 1:1.
    //
    // The focused hero fill is `radial(46% 36% @50% 42%, amb, transparent 60%)` over a
    // `radial(130% 130% @50% 0%, tileFillTop 0%, tileFillDeep 78/80%)` base; the ring is a 1px
    // [tileRing] stroke with a `0 0 56px [tileGlow]` + `0 0 18px [tileGlow]` halo, a bright
    // `[tileGlowStrong]` peak on the breathing focus pulse, and an `inset 0 0 42px [emberSoft]`
    // core lift. Resting tiles use none of these (dim, desaturated, transparent fill).
    val tileFillTop: Color,      // radial hero fill — top/near colour  (--eok-tile-fill 0%)
    val tileFillDeep: Color,     // radial hero fill — deep/outer color (--eok-tile-fill 78/80%)
    val tileRing: Color,         // focused-tile ring stroke            (--eok-tile-ring)
    // Compose uses the shipping tile glows for the ambient and spot tints of elevation shadows.
    val tileGlow: Color,         // outer focus-halo glow, resting alpha (--eok-tile-glow, ~.34/.38)
    val tileGlowStrong: Color,   // focus-pulse peak glow                (--eok-tile-glow-strong, ~.50/.54)
    val amb: Color,              // ambient hero/stage wash             (--eok-amb, ~.10–.14)
    val emberSoft: Color,        // inner-core soft ember lift          (--eok-ember-soft, ~.16–.28)
    val emberHot: Color,         // hot detail target accent            (--eok-ember-hot)
    val iconGradLight: Color,    // console-art metallic gradient stop  (--eok-icon-grad 0%)
    val iconGradHot: Color,      // console-art metallic gradient stop  (--eok-icon-grad 38/40%)
    val iconGradMid: Color,      // console-art metallic gradient stop  (--eok-icon-grad 60/62%)
    val iconGradDeep: Color,     // console-art metallic gradient stop  (--eok-icon-grad 100%)

    // Shipping's counter-hue, authored shelf material, scene geometry, readable hero, and separator contract.
    val materials: MimirThemeMaterials,

    // ── Shelf identity mark neutral material (shipping `.eok-mark`) ─────────────────────────────────
    // Feature modules read these instead of hardcoding the navy/bronze badge fill locally. The mark's
    // stroke/hairline/glow still re-tint from the active accent tokens above.
    val brandMarkFillTop: Color = Color(0xFF16213F),
    val brandMarkFillBottom: Color = Color(0xFF2A1D12),

    // Semantic destructive role, authored per identity.
    val danger: Color = Color(0xFFFF6B6B),
    val onDanger: Color = Color(0xFF120A03),

    // Status roles stay separate from the decorative accent.
    val success: Color = Color(0xFF7FD6A8),
    val warning: Color = Color(0xFFFFCE8A),

    // Shipping `.select-overlay` rgba(4, 5, 12, 0.82), shared by every theme.
    val scrim: Color = Color(0xD104050C),

    // Shipping's Mannaz mark uses --eok-ember, represented by [accent]; null preserves that default.
    val runeInk: Color? = null,
) {
    /** The color used for the Mannaz book rune. */
    val runeMark: Color get() = runeInk ?: accent

    /** Authored field posture; unlike the retired `isLight` flag, media and dock behavior stay independent. */
    val usesBrightField: Boolean get() = presentation.fieldTone == MimirFieldTone.BRIGHT
    val usesNearClearMediaVeil: Boolean get() = presentation.mediaVeil == MimirMediaVeilTreatment.NEAR_CLEAR
    val needsLocalizedMediaContrast: Boolean get() = presentation.localizedMediaContrast

    companion object {
        /** Shipping's semantic success base, used only for success states. */
        val Success = Color(0xFF7FD6A8)

        /** Shipping's dark-theme pending/status amber. */
        val WarningAmber = Color(0xFFFFCE8A)

        /** Shipping's default brand gold. */
        val BrandGold = Color(0xFFD4AF69)
        val BrandGoldBright = Color(0xFFE8C57E)

        /** Stable cool tint for Home media previews; separate from theme accent and focus roles. */
        val MediaPreviewAzure = Color(0xFF5FB8FF)

        // ── The shipping palettes ────────────────────────────────────────────────────────────────

        /** DEFAULT — the gold-on-near-black baseline. */
        val Default = MimirThemePalette(
            id = MimirThemeId.DEFAULT,
            presentation = darkPresentation(),
            accent = BrandGold,
            accentBright = BrandGoldBright,
            accentGlow = Color(0x80D4AF69),
            onAccent = Color(0xFF120A03),
            onSuccess = Color(0xFF06140D),
            surfaceBase = Color(0xFF080D12),
            surfaceRaised = Color(0xFF141A30),
            surfaceHigh = Color(0xFF1B2240),
            outline = Color(0x29D4AF69),
            tileFill = Color(0xFF141C26),
            textPrimary = Color(0xFFEEF2FF),
            textSecondary = Color(0xFFAAB6D4),
            textMuted = Color(0xFF7A90C0),
            signatureTop = Color(0xFF050609),
            signatureBottom = Color(0xFF0A0810),
            // shelf hero (`default`) — ember #d4af69
            tileFillTop = Color(0xFF1B1C2B),
            tileFillDeep = Color(0xFF05060C),
            tileRing = Color(0xFFD4AF69),
            tileGlow = Color(0x57D4AF69),        // rgba(212,175,105,.34)
            tileGlowStrong = Color(0x80D4AF69),  // rgba(212,175,105,.50)
            amb = Color(0x1AD4AF69),             // rgba(212,175,105,.10)
            emberSoft = Color(0x47D4AF69),       // rgba(212,175,105,.28)
            emberHot = Color(0xFFE8C57E),
            iconGradLight = Color(0xFFFFF3C8),
            iconGradHot = Color(0xFFF7D07D),
            iconGradMid = Color(0xFFE2B873),
            iconGradDeep = Color(0xFFBB8C4E),
            materials = MimirThemeMaterials(
                counterAccent = Color(0xFF7A90C0),
                onCounterAccent = Color(0xFF080D12),
                readableAccent = BrandGold,
                contrastMuted = Color(0xFF6676A0),
                contrastSoft = Color(0x297A90C0),
                contrastLine = Color(0x477A90C0),
                contrastGlow = Color(0x387A90C0),
                contrastSheen = Color(0x0E7A90C0),
                glassSheen = Color(0x13E8C9A0),
                railSheen = Color(0x12D4AF69),
                controlTop = Color(0xC7141A30),
                controlBottom = Color(0xB8080D12),
                hero = MimirHeroMaterial(
                    coverTop = Color(0xFF1B1C2B),
                    coverMiddle = Color(0xFF141C26),
                    coverBottom = Color(0xFF05060C),
                    onCover = Color(0xFFFFF3C8),
                    hardware = BrandGold,
                ),
                scene = MimirSceneMaterial(
                    atmosphere = MimirAtmosphereKind.WELL,
                    texture = MimirShelfTexture.ARCHITECTURAL,
                    fieldFooter = Color(0xFF030409),
                    railTrough = Color(0xFF05060C),
                    shelfSheen = Color(0x0CD4AF69),
                    shelfThread = Color(0x086676A0),
                    stagePrimary = Color(0x1AD4AF69),
                    stageSecondary = Color(0x127A90C0),
                    highlight = Color(0xFFFFF3C8),
                    shadow = Color(0xFF030409),
                    particle = BrandGoldBright,
                ),
                separator = MimirSeparatorMaterial(
                    kind = MimirSeparatorKind.BEAM,
                    shoulder = Color(0x8CD4AF69),
                    core = Color(0xB8E8C57E),
                    glow = Color(0x24D4AF69),
                    node = BrandGoldBright,
                    dockTreatment = MimirDockSeparatorTreatment.QUIET,
                ),
                onScrimPrimary = Color(0xFFEEF2FF),
                onScrimSecondary = Color(0xFFAAB6D4),
                onScrimMuted = Color(0xFF7A90C0),
            ),
        )

        /** FREYR (web `gold`) — the Golden Hall: warmer hearth gold on a slightly warmer black. */
        val Freyr = MimirThemePalette(
            id = MimirThemeId.FREYR,
            presentation = darkPresentation(),
            accent = Color(0xFFE2BD70),
            accentBright = Color(0xFFFFD36F),
            accentGlow = Color(0x66E9C577),
            onAccent = Color(0xFF120A03),
            onSuccess = Color(0xFF06140D),
            surfaceBase = Color(0xFF0A0805),
            surfaceRaised = Color(0xFF1C1810),
            surfaceHigh = Color(0xFF2A2113),
            outline = Color(0x33E8C57E),
            tileFill = Color(0xFF1C160D),
            textPrimary = Color(0xFFFFF1CA),
            textSecondary = Color(0xFFD8C18A),
            textMuted = Color(0xFFA99362),
            signatureTop = Color(0xFF0A0805),
            signatureBottom = Color(0xFF100D07),
            // shelf hero (`gold`) — ember #e2bd70
            tileFillTop = Color(0xFF3A2C12),
            tileFillDeep = Color(0xFF0A0703),
            tileRing = Color(0xFFE2BD70),
            tileGlow = Color(0x57E2BD70),        // rgba(226,189,112,.34)
            tileGlowStrong = Color(0x80E2BD70),  // rgba(226,189,112,.50)
            amb = Color(0x1CE2BD70),             // rgba(226,189,112,.11)
            emberSoft = Color(0x47E2BD70),       // rgba(226,189,112,.28)
            emberHot = Color(0xFFFFD36F),
            iconGradLight = Color(0xFFFFF0B8),
            iconGradHot = Color(0xFFFFD36F),
            iconGradMid = Color(0xFFE2BD70),
            iconGradDeep = Color(0xFF9C7839),
            materials = MimirThemeMaterials(
                counterAccent = Color(0xFF7F95D6),
                onCounterAccent = Color(0xFF0A0805),
                readableAccent = Color(0xFFE2BD70),
                contrastMuted = Color(0xFF8FA2D6),
                contrastSoft = Color(0x295E75AE),
                contrastLine = Color(0x478499D6),
                contrastGlow = Color(0x38758FD6),
                contrastSheen = Color(0x0E7A91D2),
                glassSheen = Color(0x13FFE8AE),
                railSheen = Color(0x14FFE7A8),
                controlTop = Color(0xC22C2210),
                controlBottom = Color(0xB3120E08),
                hero = MimirHeroMaterial(
                    coverTop = Color(0xFF3A2C12),
                    coverMiddle = Color(0xFF1C160D),
                    coverBottom = Color(0xFF0A0703),
                    onCover = Color(0xFFFFF0B8),
                    hardware = Color(0xFFE2BD70),
                ),
                scene = MimirSceneMaterial(
                    atmosphere = MimirAtmosphereKind.GOLDEN_HALL,
                    texture = MimirShelfTexture.GOLDEN_WEAVE,
                    fieldFooter = Color(0xFF080503),
                    railTrough = Color(0xFF0A0703),
                    shelfSheen = Color(0x0BFFE17A),
                    shelfThread = Color(0x06FFE7A8),
                    stagePrimary = Color(0x1BFFD36F),
                    stageSecondary = Color(0x0A7F95D6),
                    highlight = Color(0xFFFFE17A),
                    shadow = Color(0xFF080503),
                    particle = Color(0xFFFFE7A8),
                ),
                separator = MimirSeparatorMaterial(
                    kind = MimirSeparatorKind.BEAM,
                    shoulder = Color(0x99FFD36F),
                    core = Color(0xC7E2BD70),
                    glow = Color(0x2EE2BD70),
                    node = Color(0xFFFFE17A),
                    dockTreatment = MimirDockSeparatorTreatment.QUIET,
                ),
                onScrimPrimary = Color(0xFFFFF1CA),
                onScrimSecondary = Color(0xFFD8C18A),
                onScrimMuted = Color(0xFFA99362),
            ),
        )

        /** SURTR (web `crimson`) — the Forge: molten ember accent, near-black volcanic ground. */
        val Surtr = MimirThemePalette(
            id = MimirThemeId.SURTR,
            presentation = darkPresentation(),
            accent = Color(0xFFEF5236),        // --eok-ember — the whole CTA/focus system is tuned to it
            accentBright = Color(0xFFFF8A4D),
            accentGlow = Color(0x57EF5236),
            onAccent = Color(0xFF120A03),
            onSuccess = Color(0xFF06140D),
            surfaceBase = Color(0xFF0A0605),
            surfaceRaised = Color(0xFF1A0F0B),
            surfaceHigh = Color(0xFF251410),
            outline = Color(0x33EF6E3C),
            tileFill = Color(0xFF1E0D0A),
            textPrimary = Color(0xFFFFE7D8),
            textSecondary = Color(0xFFD8A890),
            textMuted = Color(0xFFA97A66),
            signatureTop = Color(0xFF0A0605),
            signatureBottom = Color(0xFF150A06),
            // shelf hero (`crimson`) — ember #ef5236
            tileFillTop = Color(0xFF451A13),
            tileFillDeep = Color(0xFF0D0503),
            tileRing = Color(0xFFEF5236),
            tileGlow = Color(0x57EF5236),        // rgba(239,82,54,.34)
            tileGlowStrong = Color(0x80EF5236),  // rgba(239,82,54,.50)
            amb = Color(0x1FEF5236),             // rgba(239,82,54,.12)
            emberSoft = Color(0x3DEF5236),       // rgba(239,82,54,.24)
            emberHot = Color(0xFFFF8A4D),
            iconGradLight = Color(0xFFFFCDB8),
            iconGradHot = Color(0xFFFF8A4D),
            iconGradMid = Color(0xFFEF5236),
            iconGradDeep = Color(0xFF7A2412),
            materials = MimirThemeMaterials(
                counterAccent = Color(0xFFB9C4E6),
                onCounterAccent = Color(0xFF0A0605),
                readableAccent = Color(0xFFEF5236),
                contrastMuted = Color(0xFFA3AFD6),
                contrastSoft = Color(0x24A0B0DC),
                contrastLine = Color(0x40B0C0E8),
                contrastGlow = Color(0x38A0B0DC),
                contrastSheen = Color(0x0DBECCF0),
                glassSheen = Color(0x14FFA064),
                railSheen = Color(0x12FFA064),
                controlTop = Color(0xC7301810),
                controlBottom = Color(0xB3160B07),
                hero = MimirHeroMaterial(
                    coverTop = Color(0xFF451A13),
                    coverMiddle = Color(0xFF1E0D0A),
                    coverBottom = Color(0xFF0D0503),
                    onCover = Color(0xFFFFCDB8),
                    hardware = Color(0xFFFF8A4D),
                ),
                scene = MimirSceneMaterial(
                    atmosphere = MimirAtmosphereKind.FORGE,
                    texture = MimirShelfTexture.FORGED_STEEL,
                    fieldFooter = Color(0xFF0E0503),
                    railTrough = Color(0xFF0D0503),
                    shelfSheen = Color(0x14FF7A45),
                    shelfThread = Color(0x07FFAA6E),
                    stagePrimary = Color(0x1CFF7840),
                    stageSecondary = Color(0x06B9C4E6),
                    highlight = Color(0xFFFFD27A),
                    shadow = Color(0xFF080304),
                    particle = Color(0xFFFFB070),
                ),
                separator = MimirSeparatorMaterial(
                    kind = MimirSeparatorKind.SURTR_MOLTEN,
                    shoulder = Color(0x40EF5236),
                    core = Color(0xEBFF8A4D),
                    glow = Color(0x38EF5236),
                    node = Color(0xFFFFD27A),
                    dockTreatment = MimirDockSeparatorTreatment.MOLTEN,
                ),
                onScrimPrimary = Color(0xFFFFE7D8),
                onScrimSecondary = Color(0xFFD8A890),
                onScrimMuted = Color(0xFFA97A66),
            ),
        )

        /** MÁNI (web `midnight`) — Máni's Vigil: cool moonlit blue on deep indigo-navy. */
        val Mani = MimirThemePalette(
            id = MimirThemeId.MANI,
            presentation = darkPresentation(),
            accent = Color(0xFF9DB7FF),
            accentBright = Color(0xFFC5D4FF),
            accentGlow = Color(0x5C96B6FF),
            onAccent = Color(0xFF120A03),
            onSuccess = Color(0xFF06140D),
            surfaceBase = Color(0xFF03050E),
            surfaceRaised = Color(0xFF0C1422),
            surfaceHigh = Color(0xFF111D36),
            outline = Color(0x2E9DB7FF),
            tileFill = Color(0xFF141C30),
            textPrimary = Color(0xFFE4EBFF),
            textSecondary = Color(0xFFB6C4EE),
            textMuted = Color(0xFF8497CA),
            signatureTop = Color(0xFF03050E),
            signatureBottom = Color(0xFF080D1B),
            // shelf hero (`midnight`) — ember #9db7ff
            tileFillTop = Color(0xFF24306A),
            tileFillDeep = Color(0xFF05060C),
            tileRing = Color(0xFF9DB7FF),
            tileGlow = Color(0x579DB7FF),        // rgba(157,183,255,.34)
            tileGlowStrong = Color(0x809DB7FF),  // rgba(157,183,255,.50)
            amb = Color(0x1F9DB7FF),             // rgba(157,183,255,.12)
            emberSoft = Color(0x389DB7FF),       // rgba(157,183,255,.22)
            emberHot = Color(0xFFC5D4FF),
            iconGradLight = Color(0xFFEAF1FF),
            iconGradHot = Color(0xFFC5D4FF),
            iconGradMid = Color(0xFF9DB7FF),
            iconGradDeep = Color(0xFF52689F),
            materials = MimirThemeMaterials(
                counterAccent = Color(0xFFE4C27D),
                onCounterAccent = Color(0xFF03050E),
                readableAccent = Color(0xFF9DB7FF),
                contrastMuted = Color(0xFFC1A875),
                contrastSoft = Color(0x1CE4C27D),
                contrastLine = Color(0x33E2BD70),
                contrastGlow = Color(0x29E2BD70),
                contrastSheen = Color(0x0CE8C9A0),
                glassSheen = Color(0x13E6F0FF),
                railSheen = Color(0x12E6F0FF),
                controlTop = Color(0xC7172646),
                controlBottom = Color(0xB3091228),
                hero = MimirHeroMaterial(
                    coverTop = Color(0xFF24306A),
                    coverMiddle = Color(0xFF141C30),
                    coverBottom = Color(0xFF05060C),
                    onCover = Color(0xFFEAF1FF),
                    hardware = Color(0xFFE4C27D),
                ),
                scene = MimirSceneMaterial(
                    atmosphere = MimirAtmosphereKind.MOONLIT,
                    texture = MimirShelfTexture.STARLIGHT,
                    fieldFooter = Color(0xFF02040A),
                    railTrough = Color(0xFF05060C),
                    shelfSheen = Color(0x1291B4FF),
                    shelfThread = Color(0x06D6E2FF),
                    stagePrimary = Color(0x17AEC8FF),
                    stageSecondary = Color(0x1CE4C27D),
                    highlight = Color(0xFFEAF1FF),
                    shadow = Color(0xFF02040A),
                    particle = Color(0xFFE4C27D),
                ),
                separator = MimirSeparatorMaterial(
                    kind = MimirSeparatorKind.BEAM,
                    shoulder = Color(0x8C9DB7FF),
                    core = Color(0xEBD6E2FF),
                    glow = Color(0x299DB7FF),
                    node = Color(0xFFE4C27D),
                    dockTreatment = MimirDockSeparatorTreatment.QUIET,
                ),
                onScrimPrimary = Color(0xFFE4EBFF),
                onScrimSecondary = Color(0xFFB6C4EE),
                onScrimMuted = Color(0xFF8497CA),
            ),
        )

        /** HEL (web `nightmare`) — the Dread: blood-red accent welling from a near-black wine ground. */
        val Hel = MimirThemePalette(
            id = MimirThemeId.HEL,
            presentation = darkPresentation(),
            accent = Color(0xFFB5162B),
            accentBright = Color(0xFFE3384D),
            accentGlow = Color(0x6BD11F35),
            onAccent = Color(0xFFFFE9EC),
            onSuccess = Color(0xFF06140D),
            focusRing = Color(0xFFD11F35),
            runeInk = Color(0xFFE3384D),
            surfaceBase = Color(0xFF020102),
            surfaceRaised = Color(0xFF0B0607),
            surfaceHigh = Color(0xFF14090B),
            outline = Color(0x337C141E),
            tileFill = Color(0xFF1A090D),
            textPrimary = Color(0xFFFFE9EB),
            textSecondary = Color(0xFFC99095),
            // Normal supporting copy: 4.76:1 on raised, 4.85:1 on base.
            textMuted = Color(0xFFA06C74),
            signatureTop = Color(0xFF020102),
            signatureBottom = Color(0xFF090304),
            // shelf hero (`nightmare`) — ember #b5162b, ring/glow/amb #d11f35
            tileFillTop = Color(0xFF46101A),
            tileFillDeep = Color(0xFF0A0203),
            tileRing = Color(0xFFD11F35),
            tileGlow = Color(0x57D11F35),        // rgba(209,31,53,.34)
            tileGlowStrong = Color(0x80D11F35),  // rgba(209,31,53,.50)
            amb = Color(0x1CD11F35),             // rgba(209,31,53,.11)
            emberSoft = Color(0x33A00C1C),       // rgba(160,12,28,.20)
            emberHot = Color(0xFFE3384D),
            iconGradLight = Color(0xFFFF9AA8),
            iconGradHot = Color(0xFFEF4257),
            iconGradMid = Color(0xFFD11F35),
            iconGradDeep = Color(0xFF7A0F18),
            materials = MimirThemeMaterials(
                counterAccent = Color(0xFFC4B6BA),
                onCounterAccent = Color(0xFF020102),
                readableAccent = Color(0xFFE3384D),
                contrastMuted = Color(0xFF9A8E92),
                contrastSoft = Color(0x1FC4B6BA),
                contrastLine = Color(0x38CEC0C4),
                contrastGlow = Color(0x2EC4B6BA),
                contrastSheen = Color(0x0ADED4D8),
                glassSheen = Color(0x0EECD6A9),
                railSheen = Color(0x0FECD6A9),
                controlTop = Color(0xC71C080C),
                controlBottom = Color(0xB8090305),
                hero = MimirHeroMaterial(
                    coverTop = Color(0xFF401018),
                    coverMiddle = Color(0xFF1A090D),
                    coverBottom = Color(0xFF0A0203),
                    onCover = Color(0xFFFF9AA8),
                    hardware = Color(0xFFC4B6BA),
                ),
                scene = MimirSceneMaterial(
                    atmosphere = MimirAtmosphereKind.DREAD,
                    texture = MimirShelfTexture.SHADOW_VEIL,
                    fieldFooter = Color(0xFF020102),
                    railTrough = Color(0xFF0A0203),
                    shelfSheen = Color(0x0BC4B6BA),
                    shelfThread = Color(0x05DED4D8),
                    stagePrimary = Color(0x1CB5162B),
                    stageSecondary = Color(0x0AC4B6BA),
                    highlight = Color(0xFFFFE9EC),
                    shadow = Color(0xFF020102),
                    particle = Color(0xFFC4B6BA),
                ),
                separator = MimirSeparatorMaterial(
                    kind = MimirSeparatorKind.BEAM,
                    shoulder = Color(0xBF780812),
                    core = Color(0xF2D11F35),
                    glow = Color(0x2ED11F35),
                    node = Color(0xFFC4B6BA),
                    dockTreatment = MimirDockSeparatorTreatment.QUIET,
                ),
                onScrimPrimary = Color(0xFFFFE9EB),
                onScrimSecondary = Color(0xFFC99095),
                onScrimMuted = Color(0xFFA06C74),
            ),
        )

        /** NIFLHEIM (web `void`) — icy ember with a violet focus ring on abyssal near-black. */
        val Niflheim = MimirThemePalette(
            id = MimirThemeId.NIFLHEIM,
            presentation = darkPresentation(),
            accent = Color(0xFFDFF8FF),
            accentBright = Color(0xFFFFFFFF),
            accentGlow = Color(0x5C9E8CFF),
            onAccent = Color(0xFF120A03),
            onSuccess = Color(0xFF06140D),
            focusRing = Color(0xFF9E8CFF),
            surfaceBase = Color(0xFF010006),
            surfaceRaised = Color(0xFF0A0817),
            surfaceHigh = Color(0xFF120E28),
            outline = Color(0x29BCD1E6),
            tileFill = Color(0xFF151024),
            textPrimary = Color(0xFFF0FBFF),
            textSecondary = Color(0xFFBCC8DC),
            textMuted = Color(0xFF7F89A3),
            signatureTop = Color(0xFF010006),
            signatureBottom = Color(0xFF070712),
            // shelf hero (`void`) — ember #dff8ff, ring/glow/amb lavender #9e8cff, emberSoft #badaff
            tileFillTop = Color(0xFF2C2560),
            tileFillDeep = Color(0xFF05040E),
            tileRing = Color(0xFF9E8CFF),
            tileGlow = Color(0x579E8CFF),        // rgba(158,140,255,.34)
            tileGlowStrong = Color(0x809E8CFF),  // rgba(158,140,255,.50)
            amb = Color(0x1F9E8CFF),             // rgba(158,140,255,.12)
            emberSoft = Color(0x29BADAFF),       // rgba(186,218,255,.16)
            emberHot = Color(0xFFFFFFFF),
            iconGradLight = Color(0xFFECE8FF),
            iconGradHot = Color(0xFFC9BFFF),
            iconGradMid = Color(0xFF9E8CFF),
            iconGradDeep = Color(0xFF5A4DA0),
            materials = MimirThemeMaterials(
                counterAccent = Color(0xFF9E8CFF),
                onCounterAccent = Color(0xFF010006),
                readableAccent = Color(0xFFDFF8FF),
                contrastMuted = Color(0xFF9A93C8),
                contrastSoft = Color(0x1F9E8CFF),
                contrastLine = Color(0x339E8CFF),
                contrastGlow = Color(0x2E9E8CFF),
                contrastSheen = Color(0x0AE6DEFF),
                glassSheen = Color(0x0DDFF8FF),
                railSheen = Color(0x0DDFF8FF),
                controlTop = Color(0xC7141323),
                controlBottom = Color(0xB805050E),
                hero = MimirHeroMaterial(
                    coverTop = Color(0xFF2C2560),
                    coverMiddle = Color(0xFF151024),
                    coverBottom = Color(0xFF05040E),
                    onCover = Color(0xFFECE8FF),
                    hardware = Color(0xFFDFF8FF),
                ),
                scene = MimirSceneMaterial(
                    atmosphere = MimirAtmosphereKind.VOID,
                    texture = MimirShelfTexture.ABYSS,
                    fieldFooter = Color(0xFF010007),
                    railTrough = Color(0xFF05040E),
                    shelfSheen = Color(0x0D9E8CFF),
                    shelfThread = Color(0x04DFF8FF),
                    stagePrimary = Color(0x1A9E8CFF),
                    stageSecondary = Color(0x1FDFF8FF),
                    highlight = Color(0xFFDFF8FF),
                    shadow = Color(0xFF010007),
                    particle = Color(0xFFC9BFFF),
                ),
                separator = MimirSeparatorMaterial(
                    kind = MimirSeparatorKind.NIFLHEIM_MOLTEN,
                    shoulder = Color(0xCC9E8CFF),
                    core = Color(0xE6CEC4FF),
                    glow = Color(0x2E9E8CFF),
                    node = Color(0xFFDFF8FF),
                    dockTreatment = MimirDockSeparatorTreatment.MOLTEN,
                ),
                onScrimPrimary = Color(0xFFF0FBFF),
                onScrimSecondary = Color(0xFFBCC8DC),
                onScrimMuted = Color(0xFF7F89A3),
            ),
        )

        /** YGGDRASIL (web `terra`) — the world tree: bronze accent, verdant sage undertone, deep bark ground. */
        val Yggdrasil = MimirThemePalette(
            id = MimirThemeId.YGGDRASIL,
            presentation = darkPresentation(),
            accent = Color(0xFFC0873C),
            accentBright = Color(0xFFE3B063),
            accentGlow = Color(0x52C0873C),
            onAccent = Color(0xFF120A03),
            onSuccess = Color(0xFF06140D),
            surfaceBase = Color(0xFF060F09),
            surfaceRaised = Color(0xFF0E1C12),
            surfaceHigh = Color(0xFF14271A),
            outline = Color(0x3378A05A),
            tileFill = Color(0xFF161C12),
            textPrimary = Color(0xFFEDF6D4),
            textSecondary = Color(0xFFC4D3A4),
            textMuted = Color(0xFF8EA072),
            signatureTop = Color(0xFF060F09),
            signatureBottom = Color(0xFF07140B),
            // shelf hero (`terra`) — ember #c0873c
            tileFillTop = Color(0xFF3A2F16),
            tileFillDeep = Color(0xFF090602),
            // The selection halo uses warm sap/amber light rather than electric gold. The configured
            // value drives the whole selection family so the ring and halo agree, while the broad
            // glows have less weight. `accent` itself is unchanged; this is the selection treatment.
            focusRing = Color(0xFFC2A754),
            // The focused dock seat, pushed off the bright mustard toward aged amber. The
            // halo does more of the selection work; the disk stops being the loudest thing on screen.
            seatFocusFill = Color(0xFFB8863B),
            tileRing = Color(0xFFC2A754),
            tileGlow = Color(0x47C2A754),        // rgba(194,167,84,.28) — was .34 on the oranger gold
            tileGlowStrong = Color(0x6BC2A754),  // rgba(194,167,84,.42) — was .50
            amb = Color(0x1FC0873C),             // rgba(192,135,60,.12)
            emberSoft = Color(0x3DC0873C),       // rgba(192,135,60,.24)
            emberHot = Color(0xFFE3B063),
            iconGradLight = Color(0xFFF0DCA8),
            iconGradHot = Color(0xFFD8B06A),
            iconGradMid = Color(0xFFC0873C),
            iconGradDeep = Color(0xFF7A5622),
            materials = MimirThemeMaterials(
                counterAccent = Color(0xFF93C46E),
                onCounterAccent = Color(0xFF060F09),
                readableAccent = Color(0xFFC0873C),
                contrastMuted = Color(0xFFB08A52),
                contrastSoft = Color(0x21C0873C),
                contrastLine = Color(0x3DC0873C),
                contrastGlow = Color(0x33C0873C),
                contrastSheen = Color(0x0DE3B063),
                glassSheen = Color(0x0FD8B257),
                railSheen = Color(0x0FD8B257),
                controlTop = Color(0xC71C2B16),
                controlBottom = Color(0xB809140A),
                hero = MimirHeroMaterial(
                    coverTop = Color(0xFF3A2F16),
                    coverMiddle = Color(0xFF161C12),
                    coverBottom = Color(0xFF090602),
                    onCover = Color(0xFFF0DCA8),
                    hardware = Color(0xFFC0873C),
                ),
                scene = MimirSceneMaterial(
                    atmosphere = MimirAtmosphereKind.ROOTED_WELL,
                    texture = MimirShelfTexture.ROOT_GRAIN,
                    fieldFooter = Color(0xFF030B06),
                    railTrough = Color(0xFF090602),
                    shelfSheen = Color(0x0BCBE39A),
                    shelfThread = Color(0x0DC0873C),
                    stagePrimary = Color(0x1793C46E),
                    stageSecondary = Color(0x0FC0873C),
                    highlight = Color(0xFFCBE39A),
                    shadow = Color(0xFF030B06),
                    particle = Color(0xFFE3B063),
                ),
                separator = MimirSeparatorMaterial(
                    kind = MimirSeparatorKind.BEAM,
                    shoulder = Color(0x99C0873C),
                    core = Color(0xD9CBE39A),
                    glow = Color(0x33C0873C),
                    node = Color(0xFFCBE39A),
                    dockTreatment = MimirDockSeparatorTreatment.QUIET,
                ),
                onScrimPrimary = Color(0xFFEDF6D4),
                onScrimSecondary = Color(0xFFC4D3A4),
                onScrimMuted = Color(0xFF8EA072),
            ),
        )

        /**
         * FOLKVANGR (web `love`) — abyss grey: cold steel light over bone ash.
         *
         * Replacing Freyja's Brísingamen rose. Fólkvangr is her OWN hall, the
         * field where half the slain go, which is why the persisted key stays `love`: an install that
         * chose this slot chose Freyja's, and it still has it. What changed is which of her places it
         * paints — the field rather than the necklace.
         *
         * Two hues only, and both are almost colourless: a cold steel that carries the light, and a warm
         * bone ash that carries the counter. The warmth is what stops the theme reading as a greyscale
         * filter over the Default rather than a place with its own weather. Hel keeps blood and bone-ash,
         * Niflheim keeps the mist-abyss; this is neither — it is flat, lit and still.
         */
        val Folkvangr = MimirThemePalette(
            id = MimirThemeId.FOLKVANGR,
            presentation = metallicPresentation(homeMediaTint = Color(0x1AB6BEC7)),
            accent = Color(0xFFB6BEC7),
            accentBright = Color(0xFFDDE4EA),
            accentGlow = Color(0x66B6BEC7),
            onAccent = Color(0xFF0A0B0D),
            onSuccess = Color(0xFF06140D),
            focusRing = Color(0xFFC6CDD5),
            tileFocusRing = Color(0xFFC6CDD5),
            danger = Color(0xFFCF6F6F),
            onDanger = Color(0xFF0A0B0D),
            warning = Color(0xFFCBBE96),
            surfaceBase = Color(0xFF0B0C0E),
            surfaceRaised = Color(0xFF16181C),
            surfaceHigh = Color(0xFF22252A),
            outline = Color(0x33A9B2BB),
            tileFill = Color(0xFF101215),
            textPrimary = Color(0xFFE8ECF0),
            textSecondary = Color(0xFFB7BFC7),
            textMuted = Color(0xFF8C959E),
            signatureTop = Color(0xFF0B0C0E),
            signatureBottom = Color(0xFF131619),
            tileFillTop = Color(0xFF2B3036),
            tileFillDeep = Color(0xFF08090B),
            tileRing = Color(0xFFB6BEC7),
            tileGlow = Color(0x57B6BEC7),
            tileGlowStrong = Color(0x80B6BEC7),
            amb = Color(0x1CB6BEC7),
            emberSoft = Color(0x47B6BEC7),       // rgba(182,190,199,.28)
            emberHot = Color(0xFFDDE4EA),
            iconGradLight = Color(0xFFEDF1F5),
            iconGradHot = Color(0xFFCBD3DB),
            iconGradMid = Color(0xFFB6BEC7),
            iconGradDeep = Color(0xFF6E7883),
            materials = MimirThemeMaterials(
                counterAccent = Color(0xFFC7B183),
                onCounterAccent = Color(0xFF0B0C0E),
                readableAccent = Color(0xFFC6CED7),
                contrastMuted = Color(0xFFB39C6E),
                contrastSoft = Color(0x24C7B183),
                contrastLine = Color(0x3DC7B183),
                contrastGlow = Color(0x33C7B183),
                contrastSheen = Color(0x0EC7B183),
                glassSheen = Color(0x14D6DCE2),
                railSheen = Color(0x12C2CAD2),
                controlTop = Color(0xC7272B31),
                controlBottom = Color(0xB8121418),
                hero = MimirHeroMaterial(
                    coverTop = Color(0xFF3A4049),
                    coverMiddle = Color(0xFF252930),
                    coverBottom = Color(0xFF121418),
                    onCover = Color(0xFFE8ECF0),
                    hardware = Color(0xFFBFC7CF),
                ),
                scene = MimirSceneMaterial(
                    atmosphere = MimirAtmosphereKind.FOLKVANGR,
                    // BONE_ASH was declared and assigned to NO theme. A field of the slain is what it
                    // was named for, so it takes the slot ROSE_PAPER vacated rather than adding a kind.
                    texture = MimirShelfTexture.BONE_ASH,
                    fieldFooter = Color(0xFF07080A),
                    railTrough = Color(0xFF0E1013),
                    shelfSheen = Color(0x0EC2CAD2),
                    shelfThread = Color(0x0AC7B183),
                    stagePrimary = Color(0x1AB6BEC7),
                    stageSecondary = Color(0x12C7B183),
                    highlight = Color(0xFFDCE3EA),
                    shadow = Color(0xFF050607),
                    particle = Color(0xFFC7B183),
                ),
                separator = MimirSeparatorMaterial(
                    // Fólkvangr takes the molten PLINTH, not the animated
                    // header rule -- the bone seam stands under the rune-coins, and the header keeps its
                    // ordinary hairline like every theme but Surtr and Niflheim.
                    kind = MimirSeparatorKind.FOLKVANGR_MOLTEN,
                    shoulder = Color(0x8CB6BEC7),
                    core = Color(0xC7C6CDD5),
                    glow = Color(0x2EB6BEC7),
                    node = Color(0xFFC7B183),
                    dockTreatment = MimirDockSeparatorTreatment.MOLTEN,
                ),
                onScrimPrimary = Color(0xFFE8ECF0),
                onScrimSecondary = lerp(Color(0xFFE8ECF0), Color(0xFFC7B183), 0.32f),
                onScrimMuted = lerp(Color(0xFFE8ECF0), Color(0xFF252930), 0.30f),
            ),
        )

        /** BALDR (web `baldr`) — dark metallic sun-azure with a restrained summer-gold counter-hue. */
        val Baldr = MimirThemePalette(
            id = MimirThemeId.BALDR,
            presentation = metallicPresentation(homeMediaTint = Color(0x1A5FB8FF)),
            accent = Color(0xFF6FB8F0),
            accentBright = Color(0xFF9FD4FF),
            accentGlow = Color(0x666FB8F0),
            onAccent = Color(0xFF120A03),
            onSuccess = Color(0xFF06140D),
            danger = Color(0xFFFF6B6B),
            onDanger = Color(0xFF120A03),
            warning = Color(0xFFFFCE8A),
            focusRing = Color(0xFF7CC4FF),
            tileFocusRing = Color(0xFF7CC4FF),
            surfaceBase = Color(0xFF060E18),
            surfaceRaised = Color(0xFF0E1E30),
            surfaceHigh = Color(0xFF152B42),
            outline = Color(0x336FB8F0),
            tileFill = Color(0xFF10213A),
            textPrimary = Color(0xFFE6F1FF),
            textSecondary = Color(0xFFB8CCE4),
            textMuted = Color(0xFF8FA8C4),
            signatureTop = Color(0xFF060E18),
            signatureBottom = Color(0xFF0A1626),
            tileFillTop = Color(0xFF1E3358),
            tileFillDeep = Color(0xFF0A1424),
            tileRing = Color(0xFF6FB8F0),
            tileGlow = Color(0x576FB8F0),
            tileGlowStrong = Color(0x806FB8F0),
            amb = Color(0x1C6FB8F0),
            emberSoft = Color(0x476FB8F0),       // rgba(111,184,240,.28)
            emberHot = Color(0xFF9FD4FF),
            iconGradLight = Color(0xFFE8F4FF),
            iconGradHot = Color(0xFFA8D4F5),
            iconGradMid = Color(0xFF6FB8F0),
            iconGradDeep = Color(0xFF2F5F8C),
            materials = MimirThemeMaterials(
                counterAccent = Color(0xFFFFCF5C),
                onCounterAccent = Color(0xFF06121C),
                readableAccent = Color(0xFF8CC9F7),
                contrastMuted = Color(0xFFE0B45C),
                contrastSoft = Color(0x24FFCF5C),
                contrastLine = Color(0x3DFFCF5C),
                contrastGlow = Color(0x33FFCF5C),
                contrastSheen = Color(0x0EFFCF5C),
                glassSheen = Color(0x14CFE8FF),
                railSheen = Color(0x12CFE8FF),
                controlTop = Color(0xC7152A42),
                controlBottom = Color(0xB8091524),
                hero = MimirHeroMaterial(
                    coverTop = Color(0xFF1E3358),
                    coverMiddle = Color(0xFF10213A),
                    coverBottom = Color(0xFF0A1424),
                    onCover = Color(0xFFE8F4FF),
                    hardware = Color(0xFF9FD4FF),
                ),
                scene = MimirSceneMaterial(
                    atmosphere = MimirAtmosphereKind.SUNLIT,
                    texture = MimirShelfTexture.SUMMER_SKY,
                    fieldFooter = Color(0xFF040A12),
                    railTrough = Color(0xFF0A1424),
                    shelfSheen = Color(0x0E9FD4FF),
                    shelfThread = Color(0x0AFFCF5C),
                    stagePrimary = Color(0x1A6FB8F0),
                    stageSecondary = Color(0x12FFCF5C),
                    highlight = Color(0xFFDCEEFF),
                    shadow = Color(0xFF03080F),
                    particle = Color(0xFFFFCF5C),
                ),
                separator = MimirSeparatorMaterial(
                    kind = MimirSeparatorKind.BEAM,
                    shoulder = Color(0x8C6FB8F0),
                    core = Color(0xC79FD4FF),
                    glow = Color(0x2E6FB8F0),
                    node = Color(0xFFFFCF5C),
                    dockTreatment = MimirDockSeparatorTreatment.QUIET,
                ),
                onScrimPrimary = Color(0xFFE8F4FF),
                onScrimSecondary = lerp(Color(0xFFE8F4FF), Color(0xFFFFCF5C), 0.32f),
                onScrimMuted = lerp(Color(0xFFE8F4FF), Color(0xFF10213A), 0.30f),
            ),
        )

        /** All shipping themes, gallery/order-stable (matches [MimirThemeId] declaration order). */
        val all: List<MimirThemePalette> = listOf(
            Default, Freyr, Surtr, Mani, Hel, Niflheim, Yggdrasil, Folkvangr, Baldr,
        )

        /** Look up the palette for an id. */
        fun forId(id: MimirThemeId): MimirThemePalette =
            all.first { it.id == id }
    }
}

/** Shipping `--theme-cta-material` colors in 0% -> middle -> 100% order. AP01: an active Default-theme
 *  accent override re-derives the gradient in the stop's family (§2 rule); CF01: a named theme's accent
 *  TEMPERATURE hue-shifts the authored stops the same way as the rest of the accent system. Classic
 *  Gold / Original return the authored material unchanged. */
fun MimirThemeId.ctaMaterial(): List<Color> {
    MimirAppearanceOverrides.effectiveAccent(this)
        ?.takeIf { it != MimirAccentStop.CLASSIC_GOLD }
        ?.let { return it.ctaMaterial() }
    val temp = MimirAppearanceOverrides.effectiveAccentTemp(this)
    if (temp != MimirAccentTemp.ORIGINAL) {
        return authoredCtaMaterial().map { temp.shift(it) }
    }
    return authoredCtaMaterial()
}

private fun MimirThemeId.authoredCtaMaterial(): List<Color> = when (this) {
    MimirThemeId.DEFAULT -> listOf(Color(0xFFFFE6A6), Color(0xFFD4AF69), Color(0xFF7D5F2B))
    MimirThemeId.FREYR -> listOf(Color(0xFFFFE17A), Color(0xFFE2BD70), Color(0xFF8F6B32))
    MimirThemeId.SURTR -> listOf(Color(0xFFFF8A4D), Color(0xFFEF5236), Color(0xFF8A2412))
    MimirThemeId.MANI -> listOf(Color(0xFFEDF4FF), Color(0xFF9DB7FF), Color(0xFF536BA4))
    MimirThemeId.HEL -> listOf(Color(0xFFE3384D), Color(0xFFB5162B), Color(0xFF4F080F))
    MimirThemeId.NIFLHEIM -> listOf(Color(0xFFFFFFFF), Color(0xFFDFF8FF), Color(0xFF5B568E))
    MimirThemeId.YGGDRASIL -> listOf(Color(0xFFCBE39A), Color(0xFF93C46E), Color(0xFFC0873C))
    MimirThemeId.FOLKVANGR -> listOf(Color(0xFFE8ECF0), Color(0xFFB6BEC7), Color(0xFFC7B183))
    MimirThemeId.BALDR -> listOf(Color(0xFFEAF4FF), Color(0xFF6FB8F0), Color(0xFFFFCF5C))
}
