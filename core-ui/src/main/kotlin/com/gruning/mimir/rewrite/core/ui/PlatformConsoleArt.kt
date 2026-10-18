package com.gruning.mimir.rewrite.core.ui

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

/**
 * MIMIR-REWRITE :core-ui — per-platform CONSOLE line-art.
 *
 * The signature element of the shelf hero: the focused platform tile shows its CONSOLE drawn as
 * line-art (an N64 with a cart-slot lid, a DS clamshell, a PS1 disc drive, a PSP, ...), NOT a text label
 * and NOT the deprecated poster/gradient set. The generated art catalog resolves every shipping canonical
 * id and alias to a bundled BAXY-derived Android vector or the named generic-console fallback. The older
 * Compose stroke sketches remain only as that deterministic fallback so a tile is never blank.
 *
 * GEOMETRY: every drawable is normalized to a SQUARE viewport — the
 * icon's content sits in a uniform em-box with its INK CENTROID optically centered (nudges clamped to 6%
 * of the content box, derived from a raster analysis of the source art). Square canvases mean every
 * consumer (companion tile, hero book, settings chip) letterboxes identically: a wide Switch and a tall
 * arcade cabinet read as the same visual weight instead of whichever axis their crop happened to fill.
 * Intrinsic size is a uniform 48dp, preventing per-icon aspect variance at callers.
 * Regenerate the normalized XMLs rather than hand-editing their geometry.
 *
 * DESIGN:
 *  - Known ids use the shipping vector resource directly. The composable remembers only the resource id by
 *    platform id; the shelf swaps tint, never layout.
 *  - Unknown ids fall back to the old 100×100 stroke artist so the shelf can still show an honest generic
 *    console if a future platform arrives before its catalog vector is ported.
 *  - Unknown ids fall back to [drawGenericConsole] (a neutral cartridge-console silhouette) — never a broken
 *    glyph, matching MediaArtFrame's honest-fallback discipline.
 *
 * TRADEMARK POSTURE. Every Compose fallback artist in this
 * file draws HARDWARE ONLY — bodies, lids, slots, screens, D-pads, vents, feet. A machine's shape is a
 * functional fact no one owns. No artist may draw a vendor SYMBOL: no logotype, wordmark, roundel, sphere,
 * monogram, or emblem, and no geometry traced from one. The one violation that existed — a stylised swirl on
 * the Dreamcast lid — was deleted on 2026-07-18; see [drawDreamcast]. Naming a platform is NOT this file's
 * job: [MimirPlatformShelfmark] does it with a short code struck in Mimir's own type. If a future artist
 * "needs" a mark to be recognisable, that is the signal to let the code carry the identity instead.
 *
 * NOTE this posture governs the CODE in this file. The 403 bundled BAXY vectors are third-party art under
 * MIT — which clears copyright, not the trademarks the drawings may depict — and are a separate, product-level
 * audit (see the root `NOTICE`, section 3.3).
 *
 * COLOR: flat `tint`, or an optional [tintBrush] for the shipping metallic icon gradient (`--eok-icon-grad`).
 * No palette read here — the caller (hero tile) owns the per-theme colors, so this stays a pure leaf that
 * re-skins for free when the tint changes.
 */

/** The shared author-space edge. Every console glyph is drawn inside a 0..[VIEW] × 0..[VIEW] box. */
private const val VIEW = 100f

// a modest uniform inset gives the "thinner line" read (strokes scale down with the icon,
// centre unchanged); the metallic sheen rides the gradient mask. No dark under-edge.
private const val IconLineScale = 0.9f

/**
 * Resolve a platform id to its console line-art artist (a pure [DrawScope] drawing in 100×100 author space).
 * Case-insensitive; a handful of common aliases fold onto the canonical 16 ids. Unknown → [drawGenericConsole].
 *
 * PUBLIC so a caller (e.g. a Browse card, the companion Now-Playing) can reuse the exact same silhouette set
 * without going through the composable. Covers the rewrite's 15 shelf platforms + `megadrive`↔`genesis`.
 */
fun platformConsoleArtist(platformIdValue: String): DrawScope.(tint: Color, strokeAuthorWidth: Float) -> Unit =
    ARTISTS[normalizePlatformArtId(platformIdValue)] ?: DrawScope::drawGenericConsole

/** Legacy shelf-gallery order; the generated manifest owns complete canonical and alias coverage. */
val platformConsoleArtIds: List<String> = listOf(
    "3ds", "arcade", "dreamcast", "ds", "gamecube", "gb", "gba", "gbc",
    "genesis", "n64", "nes", "ps1", "ps2", "ps3", "psp", "saturn", "snes", "switch",
    "xbox", "xbox360",
)

private const val PlatformConsoleArtManifest = "platform-console-art/mimir-platform-console-art.v1.json"

/**
 * Parses the console-art manifest and resolves every bundled platform's drawable now. Call it OFF the main thread, ahead of the first
 * tile: the first art on screen otherwise parses the manifest in composition. Safe from any thread, and a no-op once done.
 */
fun primePlatformConsoleArt(context: Context) = PlatformConsoleArtCatalog.prime(context.applicationContext)

/**
 * The manifest, parsed once per process, and each platform's resolved drawable, looked up once per process.
 *
 * Before the caching change the manifest was parsed on the composition thread by the first art on screen, and every tile
 * that composed a platform it had not shown before paid a `getIdentifier` string lookup again: a coverflow tile scrolling in is a
 * new composition with a new `remember`. A resource id does not change while the process lives, so both are memoised (a miss is not:
 * "no bundled vector" is the rare fallback and costs one lookup). [prime] does the parse off the main thread, ahead of the first tile.
 */
private object PlatformConsoleArtCatalog {
    @Volatile private var cached: Catalog? = null
    private val resolved = ConcurrentHashMap<String, Int>()

    private fun catalog(context: Context): Catalog =
        cached ?: synchronized(this) {
            cached ?: load(context.applicationContext).also { cached = it }
        }

    fun drawableRes(context: Context, rawPlatformId: String): Int? {
        val normalized = normalizeRegistryArtId(rawPlatformId)
        resolved[normalized]?.let { return it }
        val catalog = catalog(context)
        val resourceName = catalog.aliases[normalized]?.let(catalog.resources::get)
        val id = resourceName?.let { context.resources.getIdentifier(it, "drawable", context.packageName) } ?: 0
        if (id != 0) resolved[normalized] = id
        return id.takeIf { it != 0 }
    }

    /** Parses the manifest and resolves every bundled platform's drawable; safe from any thread, and a no-op once done. */
    fun prime(context: Context) {
        val catalog = catalog(context)
        catalog.aliases.keys.forEach { drawableRes(context, it) }
    }

    private fun load(context: Context): Catalog {
        val root = context.assets.open(PlatformConsoleArtManifest).bufferedReader().use { JSONObject(it.readText()) }
        val aliases = buildMap {
            val values = root.getJSONArray("aliases")
            for (index in 0 until values.length()) {
                val entry = values.getJSONObject(index)
                put(entry.getString("normalizedId"), entry.getString("canonicalId"))
            }
        }
        val resources = buildMap {
            val values = root.getJSONArray("canonicalResolutions")
            for (index in 0 until values.length()) {
                val entry = values.getJSONObject(index)
                if (entry.getString("kind") == "BUNDLED_VECTOR") {
                    put(entry.getString("id"), entry.getString("resourceName"))
                }
            }
        }
        return Catalog(aliases = aliases, resources = resources)
    }

    private data class Catalog(
        val aliases: Map<String, String>,
        val resources: Map<String, String>,
    )
}

private fun normalizeRegistryArtId(raw: String): String =
    raw.trim().lowercase(Locale.US).replace(" ", "").replace("-", "").replace("_", "")

/**
 * The console line-art for [platformIdValue], stroked in [tint], filling [modifier]'s box.
 *
 * The stroke width scales with the box so the glyph reads the same at any tile size (hero vs resting). Pass
 * the theme accent as [tint] (line stroke = `--eok-ember`); the drawing is otherwise transparent so
 * it composites over the tile's radial fill.
 *
 * @param platformIdValue the shelf platform id (`n64`, `ds`, `ps1`, …); case-insensitive, aliases folded.
 * @param tint the line-stroke color — the theme's live accent (`LocalMimirPalette.current.accent`).
 * @param modifier caller sizing; the art fills it and centers its 100×100 author space (aspect-preserving).
 */
@Composable
fun PlatformConsoleArt(
    platformIdValue: String,
    tint: Color,
    modifier: Modifier = Modifier,
    tintBrush: Brush? = null,
) {
    val context = LocalContext.current
    val drawableRes = remember(context.applicationContext, platformIdValue) {
        PlatformConsoleArtCatalog.drawableRes(context, platformIdValue)
    }
    val artModifier = modifier.consoleArtGradientMask(tintBrush)
    if (drawableRes != null) {
        //  The console art is FILLED
        // outline art, so the thinner read is a modest uniform inset (strokes scale down with the icon,
        // centre unchanged); the directional gradient mask carries the top→bottom metallic sheen. No offset
        // occlusion layer — it left a faint dark rim.
        Image(
            painter = painterResource(drawableRes),
            contentDescription = null,
            colorFilter = if (tintBrush == null) ColorFilter.tint(tint) else null,
            modifier = artModifier.scale(IconLineScale),
        )
        return
    }

    // Fallback only: a future/unknown id that has no bundled shipping BAXY vector yet still gets an honest
    // generic console glyph instead of an empty tile.
    val artist = remember(platformIdValue) { platformConsoleArtist(platformIdValue) }
    Canvas(modifier = artModifier) {
        // Fit the 100×100 author box into the smaller edge, centered, so the glyph never distorts.
        val edge = minOf(size.width, size.height)
        if (edge <= 0f) return@Canvas
        val scale = edge / VIEW
        val dx = (size.width - edge) / 2f
        val dy = (size.height - edge) / 2f
        // Strokes are specified in AUTHOR units and drawn inside the scaled transform, so a ~3.2u nominal
        // width auto-resolves to px at any tile size — reading like the reference's thin premium line.
        withTransform({
            translate(dx, dy)
            scale(scale, scale, pivot = Offset.Zero)
        }) {
            artist(if (tintBrush == null) tint else Color.White, STROKE_AUTHOR)
        }
    }
}

private fun Modifier.consoleArtGradientMask(tintBrush: Brush?): Modifier {
    if (tintBrush == null) return this
    return this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithCache {
            onDrawWithContent {
                drawContent()
                drawRect(brush = tintBrush, blendMode = BlendMode.SrcIn)
            }
        }
}

// ─────────────────────────────────────────────────────────────────────────────────────────────────────
// DRAW HELPERS (author space, 0..100)
// ─────────────────────────────────────────────────────────────────────────────────────────────────────

/** The nominal stroke weight in AUTHOR units. Scaled to px by the composable; ~3.2 reads thin+premium. */
private const val STROKE_AUTHOR = 3.2f

private fun DrawScope.strokeSpec(strokePx: Float): Stroke =
    Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round)

/** Stroke a rounded rect in author coords. */
private fun DrawScope.rr(
    tint: Color, sw: Float,
    x: Float, y: Float, w: Float, h: Float, r: Float,
) {
    drawRoundRect(
        color = tint,
        topLeft = Offset(x, y),
        size = Size(w, h),
        cornerRadius = CornerRadius(r, r),
        style = strokeSpec(sw),
    )
}

/** Stroke a circle in author coords. */
private fun DrawScope.circ(tint: Color, sw: Float, cx: Float, cy: Float, radius: Float) {
    drawCircle(color = tint, radius = radius, center = Offset(cx, cy), style = strokeSpec(sw))
}

/** Stroke a straight line segment in author coords. */
private fun DrawScope.seg(tint: Color, sw: Float, x1: Float, y1: Float, x2: Float, y2: Float) {
    drawLine(tint, Offset(x1, y1), Offset(x2, y2), strokeWidth = sw, cap = StrokeCap.Round)
}

/** Stroke an already-built [Path] in author coords. */
private fun DrawScope.path(tint: Color, sw: Float, p: Path) {
    drawPath(p, tint, style = strokeSpec(sw))
}

/** A small D-pad plus (two crossed rounded bars) centered at (cx,cy). */
private fun DrawScope.dpad(tint: Color, sw: Float, cx: Float, cy: Float, arm: Float, thick: Float) {
    rr(tint, sw, cx - arm, cy - thick / 2f, arm * 2f, thick, thick / 2f)
    rr(tint, sw, cx - thick / 2f, cy - arm, thick, arm * 2f, thick / 2f)
}

/** A cluster of [n] small face buttons in a diamond/row centered at (cx,cy). */
private fun DrawScope.faceButtons(tint: Color, sw: Float, cx: Float, cy: Float, spread: Float, r: Float) {
    circ(tint, sw, cx, cy - spread, r)
    circ(tint, sw, cx, cy + spread, r)
    circ(tint, sw, cx - spread, cy, r)
    circ(tint, sw, cx + spread, cy, r)
}

// ─────────────────────────────────────────────────────────────────────────────────────────────────────
// THE 16 CONSOLES  (author space 0..100; y grows DOWN)
// Each is a DrawScope extension taking (tint, sw). Registered in ARTISTS, keyed by canonical id.
// ─────────────────────────────────────────────────────────────────────────────────────────────────────

/** N64 — squat home console: raised cart-slot lid on top, front vent grille, two rounded feet. */
private fun DrawScope.drawN64(tint: Color, sw: Float) {
    // Main body.
    rr(tint, sw, 18f, 46f, 64f, 34f, 8f)
    // Raised cartridge-slot lid (trapezoid-ish rounded block) sitting on the body top.
    val lid = Path().apply {
        moveTo(34f, 46f)
        lineTo(38f, 30f)
        lineTo(62f, 30f)
        lineTo(66f, 46f)
    }
    path(tint, sw, lid)
    // Slot mouth line across the lid.
    seg(tint, sw * 0.8f, 40f, 38f, 60f, 38f)
    // Front vent grille (a row of short verticals).
    var vx = 42f
    while (vx <= 58f) {
        seg(tint, sw * 0.7f, vx, 56f, vx, 70f)
        vx += 4f
    }
    // Two rounded feet under the body.
    rr(tint, sw, 26f, 80f, 14f, 6f, 3f)
    rr(tint, sw, 60f, 80f, 14f, 6f, 3f)
}

/** SNES — flat home console: gently stepped top ridge + two front cartridge/eject buttons. */
private fun DrawScope.drawSnes(tint: Color, sw: Float) {
    rr(tint, sw, 16f, 40f, 68f, 30f, 7f)
    // Center stepped ridge on the deck (the SNES power/reset shelf).
    val ridge = Path().apply {
        moveTo(38f, 40f)
        lineTo(38f, 33f)
        lineTo(62f, 33f)
        lineTo(62f, 40f)
    }
    path(tint, sw, ridge)
    // Two round front buttons (power/reset).
    circ(tint, sw, 44f, 55f, 4.5f)
    circ(tint, sw, 56f, 55f, 4.5f)
    // Cartridge slot hint across the top deck.
    seg(tint, sw * 0.8f, 30f, 47f, 70f, 47f)
    rr(tint, sw, 26f, 70f, 12f, 5f, 2.5f)
    rr(tint, sw, 62f, 70f, 12f, 5f, 2.5f)
}

/** NES — the low, boxy front-loader: wide body, hinged flap door, two indicator LEDs. */
private fun DrawScope.drawNes(tint: Color, sw: Float) {
    rr(tint, sw, 14f, 42f, 72f, 28f, 5f)
    // The front-loading flap door (a wide inset rectangle).
    rr(tint, sw, 22f, 52f, 40f, 14f, 3f)
    // Flap hinge line.
    seg(tint, sw * 0.7f, 22f, 59f, 62f, 59f)
    // Two small round indicator LEDs on the right of the fascia.
    circ(tint, sw * 0.9f, 72f, 55f, 2.6f)
    circ(tint, sw * 0.9f, 72f, 63f, 2.6f)
    // Thin top ridge.
    seg(tint, sw * 0.8f, 20f, 42f, 80f, 42f)
}

/** Genesis / Mega Drive — angled home console with a big central recessed badge ring + cart slot. */
private fun DrawScope.drawGenesis(tint: Color, sw: Float) {
    rr(tint, sw, 16f, 40f, 68f, 34f, 8f)
    // The large off-centre circular RECESS moulded into the deck. Two concentric circles: the well and its
    // centre boss. This is the moulding, NOT the badge that sat in it — no vendor mark is drawn (2026-07-18).
    circ(tint, sw, 40f, 57f, 12f)
    circ(tint, sw * 0.8f, 40f, 57f, 5f)
    // Cartridge slot across the top deck.
    seg(tint, sw * 0.85f, 24f, 40f, 76f, 40f)
    // Right-side control cluster (start/reset).
    rr(tint, sw, 60f, 50f, 14f, 5f, 2.5f)
    circ(tint, sw * 0.9f, 63f, 63f, 3f)
    circ(tint, sw * 0.9f, 71f, 63f, 3f)
}

/** Saturn — wide flat deck with a large rounded CD-tray lid + a slim control strip. */
private fun DrawScope.drawSaturn(tint: Color, sw: Float) {
    rr(tint, sw, 14f, 42f, 72f, 30f, 8f)
    // Circular disc-lid on the deck.
    circ(tint, sw, 42f, 57f, 13f)
    circ(tint, sw * 0.7f, 42f, 57f, 3f)
    // Slim front control strip with two buttons (open / power).
    rr(tint, sw, 62f, 50f, 16f, 14f, 3f)
    circ(tint, sw * 0.9f, 70f, 57f, 3.5f)
}

/**
 * Dreamcast — rounded home console with a big circular top-load lid + a front memory-card slot.
 *
 * TRADEMARK (2026-07-18): this artist used to stamp a small stylised SWIRL on the lid corner — a vendor
 * SYMBOL, not a piece of hardware, and the only drawn vendor mark that ever existed in this file. It is
 * REMOVED. Nothing here reproduces a logotype, roundel, or emblem: the body, the top-load disc lid, and the
 * memory-card slot are all physical features of the machine, which no one owns. Identification is not this
 * artist's job anyway — the platform is NAMED by [MimirPlatformShelfmark]'s short code (NS = Switch, GB = Game Boy, GBC = Game Boy Color), which is Mimir's own
 * mark in Mimir's own type. A drawing identifies the SHAPE of a machine; the code identifies the machine.
 */
private fun DrawScope.drawDreamcast(tint: Color, sw: Float) {
    rr(tint, sw, 18f, 42f, 64f, 34f, 12f)
    // The big circular disc-lid dominating the top.
    circ(tint, sw, 44f, 58f, 15f)
    // Front memory-card expansion slot (a small rounded rect on the fascia).
    rr(tint, sw * 0.9f, 60f, 66f, 14f, 6f, 2f)
}

/** GameCube — the compact cube: near-square body, top carry-handle arc, a launch/disc button. */
private fun DrawScope.drawGamecube(tint: Color, sw: Float) {
    rr(tint, sw, 28f, 34f, 44f, 44f, 8f)
    // The top carry handle (an arc bridging the back edge).
    val handle = Path().apply {
        moveTo(38f, 34f)
        cubicTo(40f, 24f, 60f, 24f, 62f, 34f)
    }
    path(tint, sw, handle)
    // Square disc-lid inset on the top face.
    rr(tint, sw * 0.85f, 36f, 42f, 28f, 22f, 4f)
    // The round open button + a small power slider hint.
    circ(tint, sw * 0.9f, 50f, 71f, 3.5f)
    seg(tint, sw * 0.8f, 60f, 71f, 66f, 71f)
}

/** PS1 — flat grey disc drive: squarish body with a large circular disc-lid + open button (reference match). */
private fun DrawScope.drawPs1(tint: Color, sw: Float) {
    rr(tint, sw, 18f, 40f, 64f, 34f, 8f)
    // Big circular disc-lid.
    circ(tint, sw, 46f, 57f, 15f)
    circ(tint, sw * 0.7f, 46f, 57f, 3f)
    // Front-right button cluster (open / power / reset).
    circ(tint, sw * 0.9f, 71f, 50f, 3f)
    circ(tint, sw * 0.9f, 71f, 58f, 3f)
    rr(tint, sw * 0.9f, 66f, 66f, 12f, 5f, 2.5f)
}

/** PS2 — the tall slab (stood upright): tall thin body, disc-tray slot, twin memory-card doors, PWR/RESET. */
private fun DrawScope.drawPs2(tint: Color, sw: Float) {
    rr(tint, sw, 34f, 22f, 32f, 60f, 6f)
    // Vertical disc-tray slot line.
    seg(tint, sw * 0.85f, 40f, 30f, 60f, 30f)
    // Two stacked memory-card door rects.
    rr(tint, sw * 0.9f, 40f, 40f, 20f, 8f, 2f)
    rr(tint, sw * 0.9f, 40f, 52f, 20f, 8f, 2f)
    // Power / reset touch strip near the base.
    circ(tint, sw * 0.9f, 45f, 72f, 2.6f)
    circ(tint, sw * 0.9f, 55f, 72f, 2.6f)
}

/** PSP — wide horizontal handheld: central screen, D-pad left, face buttons right, rounded grips. */
private fun DrawScope.drawPsp(tint: Color, sw: Float) {
    // Outer shell with pronounced end grips.
    rr(tint, sw, 8f, 34f, 84f, 32f, 16f)
    // Central screen.
    rr(tint, sw * 0.9f, 30f, 40f, 40f, 20f, 3f)
    // D-pad left.
    dpad(tint, sw * 0.85f, 20f, 50f, 5f, 3.4f)
    // Face buttons right (diamond).
    faceButtons(tint, sw * 0.85f, 80f, 50f, 5f, 2.2f)
}

/** GBA — wide handheld brick: rounded landscape shell, square screen, D-pad + A/B, small speaker. */
private fun DrawScope.drawGba(tint: Color, sw: Float) {
    rr(tint, sw, 12f, 36f, 76f, 28f, 12f)
    // Screen (slightly left-of-center like the real GBA).
    rr(tint, sw * 0.9f, 34f, 42f, 26f, 16f, 2f)
    // D-pad left.
    dpad(tint, sw * 0.85f, 24f, 50f, 4.5f, 3.2f)
    // A/B buttons right (diagonal pair).
    circ(tint, sw * 0.85f, 72f, 46f, 3f)
    circ(tint, sw * 0.85f, 78f, 53f, 3f)
    // Speaker dots lower-right.
    circ(tint, sw * 0.6f, 66f, 58f, 1.4f)
    circ(tint, sw * 0.6f, 70f, 58f, 1.4f)
}

/** GB (original Game Boy) — tall portrait brick: screen window, D-pad, A/B, angled speaker grille. */
private fun DrawScope.drawGb(tint: Color, sw: Float) {
    rr(tint, sw, 30f, 16f, 40f, 68f, 8f)
    // Screen bezel window (upper).
    rr(tint, sw * 0.9f, 36f, 24f, 28f, 22f, 3f)
    // D-pad lower-left.
    dpad(tint, sw * 0.8f, 41f, 60f, 4.2f, 3f)
    // A/B buttons lower-right (round, diagonal).
    circ(tint, sw * 0.8f, 58f, 57f, 2.6f)
    circ(tint, sw * 0.8f, 63f, 62f, 2.6f)
    // Start/Select short bars.
    seg(tint, sw * 0.8f, 44f, 70f, 50f, 70f)
    seg(tint, sw * 0.8f, 53f, 70f, 59f, 70f)
    // Angled speaker grille lines lower-right corner.
    seg(tint, sw * 0.6f, 58f, 76f, 66f, 74f)
    seg(tint, sw * 0.6f, 58f, 79f, 66f, 77f)
}

/** GBC (Game Boy Color) — like GB but a taller full-face screen + the round power LED. */
private fun DrawScope.drawGbc(tint: Color, sw: Float) {
    rr(tint, sw, 30f, 14f, 40f, 72f, 12f)
    // Larger, more centered screen window.
    rr(tint, sw * 0.9f, 36f, 22f, 28f, 26f, 3f)
    // Power LED dot upper-left of the screen.
    circ(tint, sw * 0.7f, 34f, 22f, 1.6f)
    // D-pad + A/B lower.
    dpad(tint, sw * 0.8f, 41f, 62f, 4.2f, 3f)
    circ(tint, sw * 0.8f, 58f, 59f, 2.6f)
    circ(tint, sw * 0.8f, 63f, 64f, 2.6f)
    // Start/Select bars.
    seg(tint, sw * 0.8f, 45f, 72f, 51f, 72f)
    seg(tint, sw * 0.8f, 54f, 72f, 60f, 72f)
}

/** DS — clamshell: two stacked screens (open book), a hinge, d-pad + face buttons on the lower half. */
private fun DrawScope.drawDs(tint: Color, sw: Float) {
    // Upper lid + screen.
    rr(tint, sw, 26f, 20f, 48f, 30f, 5f)
    rr(tint, sw * 0.85f, 33f, 25f, 34f, 20f, 2f)
    // Hinge bar between halves.
    seg(tint, sw * 0.8f, 30f, 52f, 70f, 52f)
    // Lower base + screen.
    rr(tint, sw, 26f, 54f, 48f, 30f, 5f)
    rr(tint, sw * 0.85f, 40f, 58f, 20f, 16f, 2f)
    // D-pad left of lower screen.
    dpad(tint, sw * 0.75f, 33f, 66f, 3.4f, 2.6f)
    // Face buttons right of lower screen (diamond).
    faceButtons(tint, sw * 0.75f, 67f, 66f, 3.4f, 1.8f)
}

/** 3DS — clamshell like DS but the upper screen is WIDE (3D) + a circle-pad hint on the lower half. */
private fun DrawScope.draw3ds(tint: Color, sw: Float) {
    // Upper lid with the wide 3D screen (full-bleed-ish).
    rr(tint, sw, 24f, 18f, 52f, 30f, 5f)
    rr(tint, sw * 0.85f, 30f, 23f, 40f, 20f, 2f)
    // Hinge.
    seg(tint, sw * 0.8f, 28f, 50f, 72f, 50f)
    // Lower base + smaller screen.
    rr(tint, sw, 26f, 52f, 48f, 32f, 5f)
    rr(tint, sw * 0.85f, 41f, 56f, 18f, 18f, 2f)
    // Circle-pad + d-pad left.
    circ(tint, sw * 0.8f, 34f, 62f, 3.6f)
    dpad(tint, sw * 0.7f, 34f, 72f, 2.8f, 2.2f)
    // Face buttons right.
    faceButtons(tint, sw * 0.75f, 66f, 65f, 3.4f, 1.8f)
}

/** Switch — the tablet with two detachable Joy-Cons (rounded end grips + a stick per side). */
private fun DrawScope.drawSwitch(tint: Color, sw: Float) {
    // Central tablet + screen.
    rr(tint, sw, 28f, 30f, 44f, 40f, 4f)
    rr(tint, sw * 0.85f, 33f, 35f, 34f, 30f, 2f)
    // Left Joy-Con (rounded grip) + stick.
    rr(tint, sw, 12f, 28f, 18f, 44f, 9f)
    circ(tint, sw * 0.85f, 21f, 42f, 4f)
    dpad(tint, sw * 0.7f, 21f, 58f, 3f, 2.2f)
    // Right Joy-Con (rounded grip) + stick + face buttons.
    rr(tint, sw, 70f, 28f, 18f, 44f, 9f)
    faceButtons(tint, sw * 0.75f, 79f, 42f, 4f, 1.8f)
    circ(tint, sw * 0.85f, 79f, 58f, 4f)
}

/** Arcade — upright cabinet: marquee, screen, control deck with a joystick + button row, base. */
private fun DrawScope.drawArcade(tint: Color, sw: Float) {
    // Cabinet outline (tall, slightly waisted).
    val cab = Path().apply {
        moveTo(30f, 14f)
        lineTo(70f, 14f)
        lineTo(70f, 52f)
        lineTo(74f, 60f)
        lineTo(74f, 86f)
        lineTo(26f, 86f)
        lineTo(26f, 60f)
        lineTo(30f, 52f)
        close()
    }
    path(tint, sw, cab)
    // Marquee band.
    seg(tint, sw * 0.85f, 30f, 24f, 70f, 24f)
    // Screen.
    rr(tint, sw * 0.9f, 36f, 28f, 28f, 20f, 2f)
    // Control deck line.
    seg(tint, sw * 0.85f, 30f, 52f, 70f, 52f)
    // Joystick + ball.
    seg(tint, sw * 0.9f, 38f, 68f, 38f, 60f)
    circ(tint, sw * 0.85f, 38f, 58f, 2.6f)
    // Button row.
    circ(tint, sw * 0.8f, 52f, 64f, 2.4f)
    circ(tint, sw * 0.8f, 60f, 64f, 2.4f)
    circ(tint, sw * 0.8f, 56f, 70f, 2.4f)
}

/**
 * Generic fallback — a neutral cartridge-console silhouette (a slot-top box + a inserted cartridge tab). Used
 * for unknown ids so the tile is never empty. Deliberately reads as "a console" without claiming a family.
 */
private fun DrawScope.drawGenericConsole(tint: Color, sw: Float) {
    // Body.
    rr(tint, sw, 18f, 44f, 64f, 34f, 8f)
    // A cartridge tab standing in a top slot.
    rr(tint, sw, 40f, 26f, 20f, 22f, 3f)
    seg(tint, sw * 0.8f, 44f, 32f, 56f, 32f)
    seg(tint, sw * 0.8f, 44f, 37f, 56f, 37f)
    // Front control hints.
    circ(tint, sw * 0.9f, 32f, 62f, 4f)
    rr(tint, sw * 0.9f, 52f, 58f, 22f, 8f, 3f)
}

// ─────────────────────────────────────────────────────────────────────────────────────────────────────
// REGISTRY + ALIASES
// ─────────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * id → artist. Built once (top-level val). Each artist is `DrawScope.(tint, strokeAuthorWidth) -> Unit` in
 * 100×100 author space. [platformConsoleArtist] does the alias fold before the lookup.
 */
private val ARTISTS: Map<String, DrawScope.(Color, Float) -> Unit> = mapOf(
    "3ds" to DrawScope::draw3ds,
    "arcade" to DrawScope::drawArcade,
    "dreamcast" to DrawScope::drawDreamcast,
    "ds" to DrawScope::drawDs,
    "gamecube" to DrawScope::drawGamecube,
    "gb" to DrawScope::drawGb,
    "gba" to DrawScope::drawGba,
    "gbc" to DrawScope::drawGbc,
    "genesis" to DrawScope::drawGenesis,
    "n64" to DrawScope::drawN64,
    "nes" to DrawScope::drawNes,
    "ps1" to DrawScope::drawPs1,
    "ps2" to DrawScope::drawPs2,
    "psp" to DrawScope::drawPsp,
    "saturn" to DrawScope::drawSaturn,
    "snes" to DrawScope::drawSnes,
    "switch" to DrawScope::drawSwitch,
)

/**
 * Fold a raw platform id to a canonical [ARTISTS] key. Lower-cases, strips whitespace, and maps the common
 * shipping aliases (`megadrive`/`mega drive`/`md` → `genesis`; `psx`/`playstation` → `ps1`; `nintendo 64` →
 * `n64`; etc.) onto the 16 bespoke ids. Anything unknown returns as-is → the generic fallback.
 */
internal fun normalizePlatformArtId(raw: String): String {
    val k = raw.trim().lowercase().replace(" ", "").replace("-", "").replace("_", "")
    return when (k) {
        "megadrive", "md", "sega", "segagenesis", "segamegadrive" -> "genesis"
        "psx", "playstation", "playstation1", "psone" -> "ps1"
        "playstation2" -> "ps2"
        "playstation3" -> "ps3"
        "playstationportable" -> "psp"
        "nintendo64" -> "n64"
        "gameboy" -> "gb"
        "gameboyadvance" -> "gba"
        "gameboycolor" -> "gbc"
        "nintendods" -> "ds"
        "nintendo3ds", "new3ds" -> "3ds"
        "nintendoswitch" -> "switch"
        "supernintendo", "supernes", "superfamicom" -> "snes"
        "famicom", "nintendo" -> "nes"
        "gc", "ngc", "nintendogamecube" -> "gamecube"
        "segasaturn" -> "saturn"
        "segadreamcast", "dc" -> "dreamcast"
        "microsoftxbox", "originalxbox" -> "xbox"
        "xbox360", "microsoftxbox360", "xb360", "x360" -> "xbox360"
        // Neo Geo has its own bundled console art now — only true arcade runtimes fold onto the cabinet.
        "mame", "fbneo" -> "arcade"
        "neogeoaes", "neogeomvs" -> "neogeo"
        "vita", "playstationvita" -> "psvita"
        "playstation4" -> "ps4"
        "xbone", "microsoftxboxone" -> "xboxone"
        "famicomdisksystem", "fdsystem" -> "fds"
        "commodore64", "commodore" -> "c64"
        "turbografx16", "turbografx", "tg16" -> "pcengine"
        "turbografxcd", "tgcd" -> "pcenginecd"
        "dos", "msdos", "ibmpc" -> "pc"
        "philipscdi" -> "cdi"
        "wiiuconsole" -> "wiiu"
        "atari800", "atarixl", "atarixe" -> "atari8bit"
        "mac" -> "macintosh"
        "spectrum", "zxspec" -> "zxspectrum"
        "odyssey" -> "odyssey2"
        else -> k
    }
}

/**
 * The shelf's 4-stop metallic console-icon gradient, lifted to core-ui (HP-11 v2 S1) so the main shelf
 * hero tiles and the companion 3DS-shelf grid share ONE authority. Memoize per palette at the call site.
 */
fun platformConsoleIconBrush(palette: MimirThemePalette): Brush =
    Brush.linearGradient(
        colorStops = arrayOf(
            0.00f to palette.iconGradLight,
            0.40f to palette.iconGradHot,
            0.62f to palette.iconGradMid,
            1.00f to palette.iconGradDeep,
        ),
    )
