package com.gruning.mimir.rewrite.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Mimir Alpha :core-ui — the device-free review surface for the STAVE icon set and the v2 scenes.
 *
 * Open this file in Android Studio and use the split/design pane. Every preview here renders in the
 * IDE with no emulator and no device, which is the point: the set and the nine scenes can be judged
 * before either is wired to a call site.
 *
 * WHAT THE SCENE PREVIEWS DO AND DO NOT SHOW. `MimirShelfBackground` composes four layers —
 * `drawAtmosphereField`, `drawShelfTexture`, `drawAmbientWash`, then `drawThemeScene`. Only the
 * FOURTH is what v2 replaces, and the first three are `private` to `MimirAtmosphere.kt`. So
 * [SceneV2Field] below reproduces the field gradient from the palette (the same four stops
 * `drawAtmosphereField` reads, no invented colour) and then calls [drawThemeSceneV2]. The TEXTURE and
 * AMBIENT WASH layers are ABSENT from these previews. On device the real field carries them, so a
 * scene that looks sparse here may not be, and a scene that already fights for attention here will
 * fight harder there. Widening those three painters to `internal` would make this preview
 * full-fidelity; that is a one-word change in `MimirAtmosphere.kt` and is not taken here.
 *
 * PULSE IS 0. `drawThemeScene` is called by `MimirShelfBackground` WITHOUT a pulse: v1's architecture
 * comment is explicit that the heavy scene is drawn once and stays static, and that only the tiny
 * `drawAmbientBreath` delta layer animates, on Niflheim and Hel alone, idle-paused, to protect an OLED
 * handheld's battery. `pulse = 0f` is therefore the shipping state, not a compromise.
 * [DreadPulseCostPreview] shows what threading it would actually buy, so the cost can be judged by eye.
 */

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// The icon set
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/** Columns on the contact sheet. Six keeps a 24.dp mark plus its label inside a 420.dp preview. */
private const val SheetColumns = 6

@Preview(
    name = "STAVE icon set — all 56 marks at 24.dp",
    widthDp = 420,
    heightDp = 1180,
    showBackground = true,
    backgroundColor = 0xFF05070A,
)
@Composable
private fun IconSetSheetPreview() {
    MimirTheme(MimirThemeId.DEFAULT) {
        val palette = LocalMimirPalette.current
        Column(
            Modifier
                .background(palette.surfaceBase)
                .verticalScroll(rememberScrollState())
                .padding(MimirSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(MimirSpacing.md),
        ) {
            Text("STAVE — the whole set", style = MimirTypeScale.Title, color = palette.accent)
            Text(
                "${MimirIcon.entries.size} marks · outline at ${MimirIconArt.OutlineAlpha} alpha in " +
                    "accent · one filled cell in accentBright · rendered at ${IconSheetSize.value.toInt()}.dp",
                style = MimirTypeScale.Support,
                color = palette.textSecondary,
            )
            // Iterating entries (not a hand-written list) means a mark added to icons.json shows up
            // on this sheet the moment the generator runs. Ordinal order is icons.json order, which
            // is family order: navigation, assets, storage, placement, validation, device, state, dock.
            MimirIcon.entries.chunked(SheetColumns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    row.forEach { icon -> IconCell(icon) }
                    repeat(SheetColumns - row.size) { Box(Modifier.width(IconCellWidth)) }
                }
            }
        }
    }
}

/** The size the set is authored for. Everything below this is where the stroke boost applies. */
private val IconSheetSize: Dp = 24.dp
private val IconCellWidth: Dp = 60.dp

@Composable
private fun IconCell(icon: MimirIcon) {
    val palette = LocalMimirPalette.current
    Column(
        Modifier.width(IconCellWidth),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MimirSpacing.xs),
    ) {
        MimirIconMark(icon, size = IconSheetSize)
        Text(
            icon.label,
            style = MimirTypeScale.Pill,
            color = palette.textMuted,
            textAlign = TextAlign.Center,
        )
    }
}

@Preview(
    name = "STAVE — size ramp, does it survive below 24.dp",
    widthDp = 420,
    heightDp = 560,
    showBackground = true,
    backgroundColor = 0xFF05070A,
)
@Composable
private fun IconSizeRampPreview() {
    // The ramp exists to answer one question by eye: the stroke widens by SmallStrokeBoost below
    // 24.dp while the geometry stays fixed, so on the tightest marks the boost CLOSES the counters
    // instead of holding the silhouette.
    //
    // LIBRARY and PATH used to be the two worst cases and are the reason this ramp exists: both had
    // features 1.5 units apart carrying a 1.5-unit stroke, so their ink gap was exactly 0.0 at
    // 24.dp and −0.27 once boosted — LIBRARY's three spines fused into one slab and PATH's node
    // squares filled solid around their connector stubs. Both were re-spaced to 2.75 units in
    // icons.json (gap 1.25 / 0.98 boosted), which is at the set's own norm; no mark now has a
    // non-negative-to-zero gap. The tightest remaining are TOME, QUEUE, HANDHELD and THERMAL at
    // 0.50 / 0.23, which is where this set has always sat, so the ramp still earns its place.
    val ramp = listOf(14.dp, 16.dp, 20.dp, 24.dp, 32.dp, 48.dp)
    val probes = listOf(
        MimirIcon.LIBRARY, MimirIcon.PATH, MimirIcon.BIOS, MimirIcon.HANDHELD,
        MimirIcon.TOME, MimirIcon.QUEUE, MimirIcon.COMPLETED, MimirIcon.PENDING, MimirIcon.EMPTY,
    )
    MimirTheme(MimirThemeId.DEFAULT) {
        val palette = LocalMimirPalette.current
        Column(
            Modifier
                .background(palette.surfaceBase)
                .verticalScroll(rememberScrollState())
                .padding(MimirSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(MimirSpacing.md),
        ) {
            Text("Size ramp", style = MimirTypeScale.Title, color = palette.accent)
            Text(
                ramp.joinToString(" · ") { "${it.value.toInt()}dp" },
                style = MimirTypeScale.Eyebrow,
                color = palette.textSecondary,
            )
            probes.forEach { icon ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(MimirSpacing.md),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text(
                        icon.label,
                        style = MimirTypeScale.Pill,
                        color = palette.textMuted,
                        modifier = Modifier.width(72.dp),
                    )
                    ramp.forEach { s -> MimirIconMark(icon, size = s) }
                }
            }
        }
    }
}

@Preview(
    name = "STAVE — the two-ink rule: rest · focus · failure",
    widthDp = 420,
    heightDp = 420,
    showBackground = true,
    backgroundColor = 0xFF05070A,
)
@Composable
private fun IconTwoInkStatePreview() {
    // The whole set rests on one law: the outline never changes hue, only the CELL's temperature
    // does. These three rows are that law, side by side, so it can be judged rather than described.
    val probes = listOf(
        MimirIcon.VERIFY, MimirIcon.MATCH, MimirIcon.MISMATCH, MimirIcon.MISSING,
        MimirIcon.PENDING, MimirIcon.EMPTY, MimirIcon.ERROR, MimirIcon.WARNING,
    )
    MimirTheme(MimirThemeId.DEFAULT) {
        val palette = LocalMimirPalette.current
        Column(
            Modifier
                .background(palette.surfaceBase)
                .padding(MimirSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(MimirSpacing.lg),
        ) {
            Text("Two inks, one cell", style = MimirTypeScale.Title, color = palette.accent)
            StateRow("rest", probes, palette.accent, palette.textMuted)
            StateRow("focus", probes, palette.accent, palette.accentBright)
            StateRow("failure", probes, palette.accent, palette.danger)
            Text(
                "PENDING and EMPTY share one silhouette — same dashed ring, same 3-unit cell. " +
                    "Only the fill/hollow of that cell separates them.",
                style = MimirTypeScale.Support,
                color = palette.textSecondary,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MimirSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("dock", style = MimirTypeScale.Pill, color = palette.textMuted, modifier = Modifier.width(56.dp))
                listOf(
                    MimirIcon.DOCK_HOME, MimirIcon.DOCK_LIBRARY, MimirIcon.DOCK_UTILITY,
                    MimirIcon.DOCK_SETTINGS, MimirIcon.DOCK_APPS, MimirIcon.DOCK_FILES,
                ).forEach { MimirIconMark(it, size = 28.dp) }
            }
        }
    }
}

@Composable
private fun StateRow(label: String, icons: List<MimirIcon>, ink: Color, cellInk: Color) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MimirSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MimirTypeScale.Pill,
            color = LocalMimirPalette.current.textMuted,
            modifier = Modifier.width(56.dp),
        )
        icons.forEach { MimirIconMark(it, ink = ink, cellInk = cellInk) }
    }
}

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// The nine v2 scenes
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * One theme's field with the v2 scene painted on it.
 *
 * The field gradient is `drawAtmosphereField`'s four stops read straight off the palette — no colour
 * is invented here — but the texture and ambient-wash layers that sit between the field and the scene
 * are private to `MimirAtmosphere.kt` and are therefore MISSING. See the file KDoc.
 */
@Composable
private fun SceneV2Field(themeId: MimirThemeId, pulse: Float = 0f, modifier: Modifier = Modifier) {
    val palette = MimirThemePalette.forId(themeId)
    Canvas(modifier) {
        drawV2FieldOnly(palette, pulse)
    }
}

private fun DrawScope.drawV2FieldOnly(palette: MimirThemePalette, pulse: Float) {
    val field = palette.fieldTreatment()
    drawRect(
        brush = Brush.verticalGradient(
            0.00f to field.top,
            0.34f to field.horizon,
            0.60f to field.body,
            1.00f to field.footer,
        ),
    )
    drawRect(
        brush = Brush.verticalGradient(
            0.42f to Color.Transparent,
            0.58f to field.rail.copy(alpha = field.railAlpha),
            0.74f to Color.Transparent,
        ),
    )
    drawThemeSceneV2(palette, pulse = pulse)
}

@Composable
private fun ScenePanel(themeId: MimirThemeId, height: Dp, modifier: Modifier = Modifier) {
    val palette = MimirThemePalette.forId(themeId)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(MimirSpacing.xs)) {
        SceneV2Field(themeId, modifier = Modifier.fillMaxWidth().height(height))
        Row(horizontalArrangement = Arrangement.spacedBy(MimirSpacing.sm)) {
            Text(themeId.label, style = MimirTypeScale.Pill, color = palette.accent)
            Text(
                palette.materials.scene.atmosphere.name,
                style = MimirTypeScale.Pill,
                color = palette.textMuted,
            )
            Text(
                palette.presentation.finish.name + " · " + palette.presentation.fieldTone.name,
                style = MimirTypeScale.Pill,
                color = palette.textMuted,
            )
        }
    }
}

@Preview(
    name = "Scenes v2 — all nine, one sheet",
    widthDp = 560,
    heightDp = 1080,
    showBackground = true,
    backgroundColor = 0xFF05070A,
)
@Composable
private fun ScenesV2ContactSheetPreview() {
    // The design question this sheet exists to answer: does each identity read as its OWN place, or
    // do the nine share a shape? Judge them together, at a glance, before judging any one of them.
    MimirTheme(MimirThemeId.DEFAULT) {
        Column(
            Modifier
                .background(MimirThemePalette.Default.surfaceBase)
                .verticalScroll(rememberScrollState())
                .padding(MimirSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(MimirSpacing.lg),
        ) {
            Text(
                "Nine authored scenes, v2",
                style = MimirTypeScale.Title,
                color = MimirThemePalette.Default.accent,
            )
            Text(
                "Field + scene only — texture and ambient wash are private and absent here. " +
                    "Folkvangr and Baldr are DARK METALLIC; a bright field on either is a defect.",
                style = MimirTypeScale.Support,
                color = MimirThemePalette.Default.textSecondary,
            )
            MimirThemeId.entries.chunked(2).forEach { pair ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(MimirSpacing.md),
                ) {
                    pair.forEach { id ->
                        ScenePanel(id, height = 132.dp, modifier = Modifier.width(248.dp))
                    }
                }
            }
        }
    }
}

@Preview(name = "Scene v2 — Default · THE WELL", widthDp = 640, heightDp = 380, showBackground = true)
@Composable
private fun SceneWellPreview() = SceneV2Field(MimirThemeId.DEFAULT, modifier = Modifier.fillMaxSize())

@Preview(name = "Scene v2 — Freyr · GOLDEN HALL", widthDp = 640, heightDp = 380, showBackground = true)
@Composable
private fun SceneGoldenHallPreview() = SceneV2Field(MimirThemeId.FREYR, modifier = Modifier.fillMaxSize())

@Preview(name = "Scene v2 — Surtr · THE FORGE", widthDp = 640, heightDp = 380, showBackground = true)
@Composable
private fun SceneForgePreview() = SceneV2Field(MimirThemeId.SURTR, modifier = Modifier.fillMaxSize())

@Preview(name = "Scene v2 — Máni · MOONLIT VIGIL", widthDp = 640, heightDp = 380, showBackground = true)
@Composable
private fun SceneMoonlitPreview() = SceneV2Field(MimirThemeId.MANI, modifier = Modifier.fillMaxSize())

@Preview(name = "Scene v2 — Hel · THE DREAD", widthDp = 640, heightDp = 380, showBackground = true)
@Composable
private fun SceneDreadPreview() = SceneV2Field(MimirThemeId.HEL, modifier = Modifier.fillMaxSize())

@Preview(name = "Scene v2 — Niflheim · THE ABYSS", widthDp = 640, heightDp = 380, showBackground = true)
@Composable
private fun SceneVoidPreview() = SceneV2Field(MimirThemeId.NIFLHEIM, modifier = Modifier.fillMaxSize())

@Preview(name = "Scene v2 — Yggdrasil · ROOTED WELL", widthDp = 640, heightDp = 380, showBackground = true)
@Composable
private fun SceneRootedWellPreview() = SceneV2Field(MimirThemeId.YGGDRASIL, modifier = Modifier.fillMaxSize())

@Preview(name = "Scene v2 — Folkvangr · BLOOM (dark metallic)", widthDp = 640, heightDp = 380, showBackground = true)
@Composable
private fun SceneBloomPreview() = SceneV2Field(MimirThemeId.FOLKVANGR, modifier = Modifier.fillMaxSize())

@Preview(name = "Scene v2 — Baldr · SUNLIT (dark metallic)", widthDp = 640, heightDp = 380, showBackground = true)
@Composable
private fun SceneSunlitPreview() = SceneV2Field(MimirThemeId.BALDR, modifier = Modifier.fillMaxSize())

@Preview(
    name = "Scene v2 — the pulse question: static vs breathing",
    widthDp = 560,
    heightDp = 460,
    showBackground = true,
    backgroundColor = 0xFF05070A,
)
@Composable
private fun DreadPulseCostPreview() {
    // v1 draws the heavy scene ONCE and animates only a small glow delta, on Niflheim and Hel alone,
    // idle-paused. Every v2 painter takes a `pulse`, which invites the whole scene to be redrawn each
    // frame of the breath. The left panel is what ships today; the right is what threading the pulse
    // would buy. The delta is what has to be worth a per-frame full-scene redraw on an OLED handheld.
    MimirTheme(MimirThemeId.HEL) {
        Column(
            Modifier
                .background(MimirThemePalette.Hel.surfaceBase)
                .padding(MimirSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
        ) {
            Text("pulse 0 (ships) vs pulse 1", style = MimirTypeScale.Title, color = MimirThemePalette.Hel.accent)
            listOf(MimirThemeId.HEL, MimirThemeId.NIFLHEIM).forEach { id ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
                ) {
                    SceneV2Field(id, pulse = 0f, modifier = Modifier.width(252.dp).height(142.dp))
                    SceneV2Field(id, pulse = 1f, modifier = Modifier.width(252.dp).height(142.dp))
                }
            }
            Text(
                "The other seven themes never breathe at all — AmbientBreath runs on Hel and " +
                    "Niflheim only — so their `pulse` parameter can never be anything but 0.",
                style = MimirTypeScale.Support,
                color = MimirThemePalette.Hel.textSecondary,
            )
        }
    }
}
